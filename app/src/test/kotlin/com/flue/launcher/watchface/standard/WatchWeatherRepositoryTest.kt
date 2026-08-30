package com.flue.launcher.watchface.standard

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchWeatherRepositoryTest {
    @Test
    fun changingTheConfiguredLocationDoesNotExposeThePreviousLocationsWeather() {
        val now = System.currentTimeMillis()
        val context = RuntimeEnvironment.getApplication().applicationContext
        val preferences = context.getSharedPreferences(
            WatchWeatherRepository.PREFERENCES,
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear().apply()
        preferences.edit()
            .putString(WatchWeatherRepository.KEY_LABEL, "Old City")
            .putString(WatchWeatherRepository.KEY_LATITUDE, "31.2304")
            .putString(WatchWeatherRepository.KEY_LONGITUDE, "121.4737")
            .putLong(WatchWeatherRepository.KEY_CACHE_RECEIVED_AT, now)
            .putString(
                WatchWeatherRepository.KEY_CACHE_JSON,
                JSONObject()
                    .put("temperatureCelsius", 9.0)
                    .put("location", "Old City")
                    .put("sourceLatitude", 31.2304)
                    .put("sourceLongitude", 121.4737)
                    .put("forecast", org.json.JSONArray())
                    .put("hourlyForecast", org.json.JSONArray())
                    .toString(),
            )
            .commit()
        val repository = WatchWeatherRepository(
            context = context,
            transport = WatchWeatherHttpTransport { _, _ -> null },
        )

        assertEquals("Old City", repository.snapshot(now + 1L)?.location)

        repository.setManualLocation(
            WatchWeatherLocation(39.9042, 116.4074, "New City"),
        )

        assertNull(repository.snapshot(now + 2L))
    }

    @Test
    fun anInFlightRefreshCannotRestoreWeatherForThePreviousLocation() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val preferences = context.getSharedPreferences(
            WatchWeatherRepository.PREFERENCES,
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear()
            .putString(WatchWeatherRepository.KEY_LABEL, "Old City")
            .putString(WatchWeatherRepository.KEY_LATITUDE, "31.2304")
            .putString(WatchWeatherRepository.KEY_LONGITUDE, "121.4737")
            .commit()
        val oldRequestStarted = CountDownLatch(1)
        val releaseOldRequest = CountDownLatch(1)
        val oldResponseReturned = CountDownLatch(1)
        val newRequestStarted = CountDownLatch(1)
        val releaseNewRequest = CountDownLatch(1)
        val transport = WatchWeatherHttpTransport { url, _ ->
            when {
                "api.met.no" !in url -> null
                "lat=31.23040" in url -> {
                    oldRequestStarted.countDown()
                    releaseOldRequest.await(2, TimeUnit.SECONDS)
                    oldResponseReturned.countDown()
                    metNoTemperaturePayload(9.0)
                }
                "lat=39.90420" in url -> {
                    newRequestStarted.countDown()
                    releaseNewRequest.await(2, TimeUnit.SECONDS)
                    metNoTemperaturePayload(21.0)
                }
                else -> null
            }
        }
        val repository = WatchWeatherRepository(context, transport = transport)
        repository.requestRefresh()
        assertTrue(oldRequestStarted.await(2, TimeUnit.SECONDS))

        repository.setManualLocation(
            WatchWeatherLocation(39.9042, 116.4074, "New City"),
        )
        releaseOldRequest.countDown()
        assertTrue(oldResponseReturned.await(2, TimeUnit.SECONDS))
        Thread.sleep(100L)

        try {
            assertNull(repository.snapshot(System.currentTimeMillis()))
            assertTrue(newRequestStarted.await(2, TimeUnit.SECONDS))
        } finally {
            releaseNewRequest.countDown()
        }

        val deadline = System.currentTimeMillis() + 3_000L
        var snapshot: WatchWeatherSnapshot? = null
        while (System.currentTimeMillis() < deadline && snapshot?.location != "New City") {
            snapshot = repository.snapshot(System.currentTimeMillis())
            Thread.sleep(20L)
        }
        assertEquals("New City", snapshot?.location)
        assertEquals(21.0, snapshot?.temperatureCelsius ?: Double.NaN, 0.0)
    }

    @Test
    fun boundedUtf8ReaderAcceptsAResponseAtTheExactByteLimit() {
        val expected = "上海 21℃"
        val bytes = expected.toByteArray(Charsets.UTF_8)

        assertEquals(
            expected,
            readBoundedWeatherUtf8(ByteArrayInputStream(bytes), bytes.size),
        )
    }

    @Test
    fun boundedUtf8ReaderRejectsOversizeWithoutDrainingTheResponse() {
        val input = TrackingChunkedInputStream(
            payload = ByteArray(64 * 1024) { 'x'.code.toByte() },
            chunkSize = 3,
        )

        assertNull(readBoundedWeatherUtf8(input, maxBytes = 5))
        assertEquals(6, input.bytesRead)
        assertTrue(input.bytesRead < input.payloadSize)
    }

    @Test
    fun boundedUtf8ReaderRejectsInvalidLimitsWithoutReading() {
        val input = TrackingChunkedInputStream("weather".toByteArray(), chunkSize = 2)

        assertNull(readBoundedWeatherUtf8(input, maxBytes = 0))
        assertEquals(0, input.bytesRead)
    }

    @Test
    fun locationSearchReturnsDistinctUserSelectableCandidates() {
        val payload = """
            {"results":[
              {"name":"Springfield","admin1":"Illinois","country":"United States","latitude":39.80,"longitude":-89.64},
              {"name":"Springfield","admin1":"Massachusetts","country":"United States","latitude":42.10,"longitude":-72.59},
              {"name":"Duplicate","latitude":39.80,"longitude":-89.64},
              {"name":"Invalid","latitude":999,"longitude":0}
            ]}
        """.trimIndent()

        val results = parseWatchWeatherLocationSearch(payload, "Springfield", limit = 8)

        assertEquals(2, results.size)
        assertEquals("Springfield, Illinois, United States", results[0].label)
        assertEquals(42.10, results[1].latitude, 0.0)
    }

    @Test
    fun locationProvidersIncludeLegacyAndVendorSourcesInCoarseFriendlyOrder() {
        val providers = prioritizeWatchWeatherLocationProviders(
            listOf("gps", "vendor_location", "network", "fused", "passive", "FUSED"),
        )

        assertEquals(
            listOf("fused", "network", "vendor_location", "gps", "passive"),
            providers,
        )
    }

    @Test
    fun coarseOnlyPermissionDropsTheProvidersThatRequireFineLocation() {
        val discovered = listOf("fused", "network", "gps", "passive", "vendor_location")

        // 只授予「大致位置」时 `getCurrentLocation(gps)` 抛的 SecurityException 会被
        // `runCatching` 吞掉，于是那一轮**永远等不到 fix**：定位请求挂在 GPS 上、
        // latch 一直等到超时，可用的 network/fused 结果也跟着一起白等。所以无 FINE
        // 权限时干脆别把这两个 provider 报上去。
        assertEquals(
            listOf("fused", "network", "vendor_location"),
            filterWatchWeatherLocationProvidersByPermission(
                discovered,
                hasFineLocationPermission = false,
            ),
        )
        // 有 FINE 就一个都不筛，顺序也不动（排序是 prioritize 的活）。
        assertEquals(
            discovered,
            filterWatchWeatherLocationProvidersByPermission(
                discovered,
                hasFineLocationPermission = true,
            ),
        )
        // provider 名的大小写由平台决定，不能只认小写字面量。
        assertEquals(
            listOf("network"),
            filterWatchWeatherLocationProvidersByPermission(
                listOf("GPS", "network", "PASSIVE"),
                hasFineLocationPermission = false,
            ),
        )
    }

    private class TrackingChunkedInputStream(
        private val payload: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        var bytesRead: Int = 0
            private set

        val payloadSize: Int get() = payload.size

        override fun read(): Int {
            if (bytesRead >= payload.size) return -1
            return payload[bytesRead++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= payload.size) return -1
            val count = minOf(chunkSize, length, payload.size - bytesRead)
            payload.copyInto(buffer, destinationOffset = offset, startIndex = bytesRead, endIndex = bytesRead + count)
            bytesRead += count
            return count
        }
    }

    private fun metNoTemperaturePayload(temperature: Double): String =
        """{"properties":{"timeseries":[{"data":{"instant":{"details":{"air_temperature":$temperature}},"next_1_hours":{"summary":{"symbol_code":"clearsky_day"}}}}]}}"""
}
