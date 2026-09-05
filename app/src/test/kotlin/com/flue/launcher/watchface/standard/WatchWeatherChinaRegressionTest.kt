package com.flue.launcher.watchface.standard

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchWeatherChinaRegressionTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication().applicationContext
        preferences = context.getSharedPreferences(WatchWeatherRepository.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @Test
    fun legacyProviderMigratesToChinaWithoutLosingManualModeOrTemperatureUnit() {
        saveManualLocation(SHANGHAI)
        preferences.edit()
            .putString(WatchWeatherRepository.KEY_PROVIDER, "OPEN_METEO")
            .putString(WatchWeatherRepository.KEY_TEMPERATURE_UNIT, "F")
            .putString(WatchWeatherRepository.KEY_CACHE_JSON, cachedWeather(SHANGHAI, null))
            .putLong(WatchWeatherRepository.KEY_CACHE_RECEIVED_AT, System.currentTimeMillis())
            .commit()

        val repository = repository()

        assertEquals(WatchWeatherProvider.CHINA, repository.weatherProvider())
        assertEquals("CHINA", preferences.getString(WatchWeatherRepository.KEY_PROVIDER, null))
        assertEquals(WatchTemperatureUnit.FAHRENHEIT, repository.temperatureUnit())
        assertFalse(repository.isUsingSystemLocation())
        assertEquals(SHANGHAI.label, repository.activeLocationLabel())
        assertNull(preferences.getString(WatchWeatherRepository.KEY_CACHE_JSON, null))
    }

    @Test
    fun oldChinaCacheWithoutProvenanceIsDiscardedWhileSystemModeIsPreserved() {
        saveSystemLocation(SHANGHAI)
        preferences.edit()
            .remove("system_location_identity_version")
            .putString(WatchWeatherRepository.KEY_PROVIDER, "CHINA")
            .putString(WatchWeatherRepository.KEY_CACHE_JSON, cachedWeather(SHANGHAI, null))
            .putLong(WatchWeatherRepository.KEY_CACHE_RECEIVED_AT, System.currentTimeMillis())
            .commit()

        val repository = repository()

        assertTrue(repository.isUsingSystemLocation())
        assertNull(repository.activeLocationLabel())
        assertEquals(SHANGHAI.latitude, repository.activeLocation()?.latitude ?: Double.NaN, 0.0)
        assertNull(preferences.getString(WatchWeatherRepository.KEY_CACHE_JSON, null))
    }

    @Test
    fun legacyMixedSystemIdentityMustBeResolvedAgainBeforeChinaWeather() {
        val raw = WatchWeatherLocation(39.9, 116.4)
        preferences.edit()
            .putString(WatchWeatherRepository.KEY_SYSTEM_LOCATION_LABEL, SHANGHAI.label)
            .putString(WatchWeatherRepository.KEY_SYSTEM_LATITUDE, raw.latitude.toString())
            .putString(WatchWeatherRepository.KEY_SYSTEM_LONGITUDE, raw.longitude.toString())
            .putString(WatchWeatherRepository.KEY_SYSTEM_CHINA_LOCATION_KEY, SHANGHAI.chinaLocationKey)
            .putString(WatchWeatherRepository.KEY_TEMPERATURE_UNIT, "F")
            .commit()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val repository = repository(transport = WatchWeatherHttpTransport { url, _ ->
            calls += url
            when {
                "/location/city/geo" in url -> chinaLocations(BEIJING)
                "/weather/all" in url -> chinaWeather(22.0)
                else -> null
            }
        })
        assertTrue(repository.isUsingSystemLocation())
        assertEquals(WatchTemperatureUnit.FAHRENHEIT, repository.temperatureUnit())
        assertNull(repository.activeLocationLabel())
        assertEquals(raw.latitude, repository.activeLocation()?.latitude ?: Double.NaN, 0.0)

        repository.requestRefresh()
        awaitWorker(repository)

        assertTrue(calls.any { "/location/city/geo" in it })
        assertTrue(calls.none { "weathercn%3A101020100" in it })
        assertEquals(BEIJING.label, repository.activeLocationLabel())
        assertEquals(BEIJING.latitude.toString(), preferences.getString(WatchWeatherRepository.KEY_SYSTEM_LATITUDE, null))
        assertEquals(raw.latitude, repository.activeLocation()?.latitude ?: Double.NaN, 0.0)
        assertEquals(BEIJING.chinaLocationKey, preferences.getString(WatchWeatherRepository.KEY_SYSTEM_CHINA_LOCATION_KEY, null))
    }

    @Test
    fun chinaForecastFailureMakesNoForeignWeatherRequestAndKeepsSameCityCache() {
        saveManualLocation(SHANGHAI)
        preferences.edit()
            .putString(WatchWeatherRepository.KEY_PROVIDER, "CHINA")
            .putString(WatchWeatherRepository.KEY_CACHE_JSON, cachedWeather(SHANGHAI, "CHINA"))
            .putLong(WatchWeatherRepository.KEY_CACHE_RECEIVED_AT, System.currentTimeMillis())
            .commit()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val repository = repository(transport = WatchWeatherHttpTransport { url, _ -> calls += url; null })

        repository.requestRefresh()
        awaitWorker(repository)

        assertTrue(calls.isNotEmpty())
        assertTrue(calls.all { it.startsWith("https://weatherapi.market.xiaomi.com/") })
        assertEquals(9.0, repository.snapshot(System.currentTimeMillis())?.temperatureCelsius ?: Double.NaN, 0.0)
    }

    @Test
    fun successfulChinaForecastDoesNotEnrichMissingFieldsFromForeignSources() {
        saveManualLocation(SHANGHAI)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val repository = repository(transport = WatchWeatherHttpTransport { url, _ ->
            calls += url
            if ("weatherapi.market.xiaomi.com" in url) chinaWeather(21.0) else null
        })

        repository.requestRefresh()
        awaitWorker(repository)
        val weather = repository.snapshot(System.currentTimeMillis())

        assertEquals(21.0, weather?.temperatureCelsius ?: Double.NaN, 0.0)
        assertNull(weather?.cloudCoverFraction)
        assertTrue(weather?.hourlyForecast.isNullOrEmpty())
        assertTrue(calls.all { it.startsWith("https://weatherapi.market.xiaomi.com/") })
    }

    @Test
    fun emptyChinaObservationCannotReplaceValidSameCityWeather() {
        saveManualLocation(SHANGHAI)
        preferences.edit()
            .putString(WatchWeatherRepository.KEY_PROVIDER, "CHINA")
            .putString(WatchWeatherRepository.KEY_CACHE_JSON, cachedWeather(SHANGHAI, "CHINA"))
            .putLong(WatchWeatherRepository.KEY_CACHE_RECEIVED_AT, System.currentTimeMillis())
            .commit()
        val repository = repository(transport = WatchWeatherHttpTransport { _, _ -> "{\"current\":{}}" })

        repository.requestRefresh()
        awaitWorker(repository)

        assertEquals(9.0, repository.snapshot(System.currentTimeMillis())?.temperatureCelsius ?: Double.NaN, 0.0)
    }

    @Test
    fun locationChangeDuringWeatherRequestCannotPublishTheOldResponse() {
        saveManualLocation(SHANGHAI)
        val oldWeatherStarted = CountDownLatch(1)
        val releaseOldWeather = CountDownLatch(1)
        val newWeatherStarted = CountDownLatch(1)
        val releaseNewWeather = CountDownLatch(1)
        val repository = repository(transport = WatchWeatherHttpTransport { url, _ ->
            when {
                "weathercn%3A101020100" in url -> {
                    oldWeatherStarted.countDown()
                    releaseOldWeather.await(3, TimeUnit.SECONDS)
                    chinaWeather(9.0)
                }
                "weathercn%3A101010100" in url -> {
                    newWeatherStarted.countDown()
                    releaseNewWeather.await(3, TimeUnit.SECONDS)
                    chinaWeather(22.0)
                }
                else -> null
            }
        })
        try {
            repository.requestRefresh()
            assertTrue(oldWeatherStarted.await(2, TimeUnit.SECONDS))
            repository.setManualLocation(BEIJING)
            releaseOldWeather.countDown()
            assertTrue(newWeatherStarted.await(3, TimeUnit.SECONDS))
            assertNull(preferences.getString(WatchWeatherRepository.KEY_CACHE_JSON, null))
            assertNull(repository.snapshot(System.currentTimeMillis()))
        } finally {
            releaseOldWeather.countDown()
            releaseNewWeather.countDown()
        }
        awaitWorker(repository)
        assertEquals(BEIJING.label, repository.snapshot(System.currentTimeMillis())?.location)
    }

    @Test
    fun rawSystemFixCannotOverwriteResolvedChinaCityCoordinatesLabelOrKey() {
        saveSystemLocation(SHANGHAI)
        val raw = WatchWeatherLocation(31.2305, 121.4738, "Old English Label")
        val repository = repository(
            locationSource = WatchWeatherLocationSource { raw },
            transport = WatchWeatherHttpTransport { url, _ ->
                when {
                    "/location/city/search" in url -> chinaLocations(SHANGHAI)
                    "/weather/all" in url -> chinaWeather(21.0)
                    else -> null
                }
            },
        )

        repository.requestRefresh()
        awaitWorker(repository)

        assertEquals(SHANGHAI.label, repository.activeLocationLabel())
        assertEquals(SHANGHAI.latitude.toString(), preferences.getString(WatchWeatherRepository.KEY_SYSTEM_LATITUDE, null))
        assertEquals(SHANGHAI.longitude.toString(), preferences.getString(WatchWeatherRepository.KEY_SYSTEM_LONGITUDE, null))
        assertEquals(SHANGHAI.chinaLocationKey, preferences.getString(WatchWeatherRepository.KEY_SYSTEM_CHINA_LOCATION_KEY, null))
        assertEquals(SHANGHAI.label, repository.snapshot(System.currentTimeMillis())?.location)
    }

    @Test
    fun settingsSystemLocationHandoffPreservesTheFirstFixAcrossMissingFixesAndRepeatedClicks() {
        val raw = WatchWeatherLocation(31.251, 121.494)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val repository = repository(
            locationSource = WatchWeatherLocationSource { if (reads.getAndIncrement() == 0) raw else null },
            transport = WatchWeatherHttpTransport { url, _ ->
                when {
                    "/location/city/geo" in url -> chinaLocations(SHANGHAI)
                    "/weather/all" in url -> chinaWeather(21.0)
                    else -> null
                }
            },
        )
        val resolved = checkNotNull(repository.resolveSystemLocation())
        repository.useSystemLocation(resolved)
        awaitWorker(repository)

        assertEquals(raw.latitude, repository.activeLocation()?.latitude ?: Double.NaN, 0.0)
        assertEquals(raw.longitude, repository.activeLocation()?.longitude ?: Double.NaN, 0.0)
        assertEquals(SHANGHAI.latitude.toString(), preferences.getString(WatchWeatherRepository.KEY_SYSTEM_LATITUDE, null))

        val repeated = checkNotNull(repository.resolveSystemLocation())
        repository.useSystemLocation(repeated)
        awaitWorker(repository)

        assertEquals(raw.latitude, repository.activeLocation()?.latitude ?: Double.NaN, 0.0)
        assertEquals(raw.longitude, repository.activeLocation()?.longitude ?: Double.NaN, 0.0)
        assertEquals(raw.latitude.toString(), preferences.getString(WatchWeatherRepository.KEY_SYSTEM_RESOLVED_ANCHOR_LATITUDE, null))
    }

    @Test
    fun completedSystemResolutionCannotOverrideALaterManualSelectionWhenApplied() {
        val raw = WatchWeatherLocation(31.251, 121.494)
        val repository = repository(
            locationSource = WatchWeatherLocationSource { raw },
            transport = WatchWeatherHttpTransport { url, _ ->
                when {
                    "/location/city/geo" in url -> chinaLocations(SHANGHAI)
                    "/weather/all" in url -> chinaWeather(21.0)
                    else -> null
                }
            },
        )
        val resolved = checkNotNull(repository.resolveSystemLocation())
        repository.setManualLocation(BEIJING)

        repository.useSystemLocation(resolved)
        awaitWorker(repository)

        assertFalse(repository.isUsingSystemLocation())
        assertEquals(BEIJING.label, repository.activeLocationLabel())
        assertEquals(BEIJING.chinaLocationKey, preferences.getString(WatchWeatherRepository.KEY_CHINA_LOCATION_KEY, null))
    }

    @Test
    fun temporaryPositioningFailureKeepsTheLastRawFixSeparateFromCityCoordinates() {
        saveSystemLocation(SHANGHAI)
        preferences.edit()
            .putString(WatchWeatherRepository.KEY_SYSTEM_RAW_LATITUDE, "31.251")
            .putString(WatchWeatherRepository.KEY_SYSTEM_RAW_LONGITUDE, "121.494")
            .commit()
        val repository = repository(transport = WatchWeatherHttpTransport { url, _ ->
            if ("/weather/all" in url) chinaWeather(21.0) else null
        })

        repository.requestRefresh()
        awaitWorker(repository)

        assertEquals("31.251", preferences.getString(WatchWeatherRepository.KEY_SYSTEM_RAW_LATITUDE, null))
        assertEquals("121.494", preferences.getString(WatchWeatherRepository.KEY_SYSTEM_RAW_LONGITUDE, null))
        assertEquals(SHANGHAI.latitude.toString(), preferences.getString(WatchWeatherRepository.KEY_SYSTEM_LATITUDE, null))
    }

    @Test
    fun unresolvedSystemFixUpdatesCoordinatesWithoutReusingPreviousCityWeather() {
        saveSystemLocation(SHANGHAI)
        preferences.edit()
            .putString(WatchWeatherRepository.KEY_PROVIDER, "CHINA")
            .putString(WatchWeatherRepository.KEY_CACHE_JSON, cachedWeather(SHANGHAI, "CHINA"))
            .putLong(WatchWeatherRepository.KEY_CACHE_RECEIVED_AT, System.currentTimeMillis())
            .commit()
        val raw = WatchWeatherLocation(39.9, 116.4, "北京市")
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val repository = repository(
            locationSource = WatchWeatherLocationSource { raw },
            transport = WatchWeatherHttpTransport { url, _ -> calls += url; null },
        )

        repository.requestRefresh()
        awaitWorker(repository)

        assertEquals(raw.latitude, repository.activeLocation()?.latitude ?: Double.NaN, 0.0)
        assertEquals(raw.longitude, repository.activeLocation()?.longitude ?: Double.NaN, 0.0)
        assertEquals(SHANGHAI.chinaLocationKey, preferences.getString(WatchWeatherRepository.KEY_SYSTEM_CHINA_LOCATION_KEY, null))
        assertNull(repository.snapshot(System.currentTimeMillis()))

        repository.requestRefresh()
        awaitWorker(repository)

        // A failed resolution must not bind the new fix to the previous city's key on retry.
        assertTrue(calls.none { "/weather/all" in it })
        assertNull(repository.snapshot(System.currentTimeMillis()))
    }

    @Test
    fun smallSuccessiveMovesAreMeasuredFromTheLastResolvedAnchor() {
        saveSystemLocation(SHANGHAI)
        val raw = java.util.concurrent.atomic.AtomicReference(WatchWeatherLocation(31.2334, 121.4737))
        val nextCity = WatchWeatherLocation(31.24, 121.4737, "邻县, 中国", chinaLocationKey = "weathercn:101020600")
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val repository = repository(
            locationSource = WatchWeatherLocationSource { raw.get() },
            transport = WatchWeatherHttpTransport { url, _ ->
                calls += url
                when {
                    "/location/city/geo" in url -> chinaLocations(nextCity)
                    "/weather/all" in url -> chinaWeather(21.0)
                    else -> null
                }
            },
        )
        repository.requestRefresh()
        awaitWorker(repository)
        assertEquals(SHANGHAI.label, repository.activeLocationLabel())

        raw.set(WatchWeatherLocation(31.2364, 121.4737))
        repository.requestRefresh()
        awaitWorker(repository)

        assertEquals(nextCity.label, repository.activeLocationLabel())
        assertTrue(calls.any { "/location/city/geo" in it })
    }

    @Test
    fun selectionDuringManualCityResolutionQueuesLatestRefreshAndCannotRestoreOldCity() {
        val oldSearchStarted = CountDownLatch(1)
        val releaseOldSearch = CountDownLatch(1)
        val newWeatherStarted = CountDownLatch(1)
        val repository = repository(transport = WatchWeatherHttpTransport { url, _ ->
            when {
                "/location/city/search" in url -> {
                    oldSearchStarted.countDown()
                    releaseOldSearch.await(3, TimeUnit.SECONDS)
                    chinaLocations(SHANGHAI)
                }
                "weathercn%3A101010100" in url -> {
                    newWeatherStarted.countDown()
                    chinaWeather(22.0)
                }
                "/weather/all" in url -> chinaWeather(9.0)
                else -> null
            }
        })
        try {
            repository.setManualCity("上海")
            assertTrue(oldSearchStarted.await(2, TimeUnit.SECONDS))
            repository.setManualLocation(BEIJING)
        } finally {
            releaseOldSearch.countDown()
        }

        // No snapshot() call here: the latest selection must schedule itself.
        assertTrue("latest selection never refreshed", newWeatherStarted.await(3, TimeUnit.SECONDS))
        awaitWorker(repository)
        assertEquals(BEIJING.label, repository.activeLocationLabel())
        assertEquals(BEIJING.chinaLocationKey, preferences.getString(WatchWeatherRepository.KEY_CHINA_LOCATION_KEY, null))
        assertEquals(22.0, repository.snapshot(System.currentTimeMillis())?.temperatureCelsius ?: Double.NaN, 0.0)
    }

    @Test
    fun selectionDuringSystemFixCannotPersistOldSystemIdentity() {
        val fixStarted = CountDownLatch(1)
        val releaseFix = CountDownLatch(1)
        val newWeatherStarted = CountDownLatch(1)
        val repository = repository(
            locationSource = WatchWeatherLocationSource {
                fixStarted.countDown()
                releaseFix.await(3, TimeUnit.SECONDS)
                SHANGHAI
            },
            transport = WatchWeatherHttpTransport { url, _ ->
                if ("weathercn%3A101010100" in url) newWeatherStarted.countDown()
                if ("/weather/all" in url) chinaWeather(22.0) else null
            },
        )
        try {
            repository.requestRefresh()
            assertTrue(fixStarted.await(2, TimeUnit.SECONDS))
            repository.setManualLocation(BEIJING)
        } finally {
            releaseFix.countDown()
        }

        assertTrue(newWeatherStarted.await(3, TimeUnit.SECONDS))
        awaitWorker(repository)
        assertNull(preferences.getString(WatchWeatherRepository.KEY_SYSTEM_LATITUDE, null))
        assertEquals(BEIJING.label, repository.activeLocationLabel())
    }

    @Test
    fun chinaDailyForecastSeparatesTodayFromTomorrowAndPersistsSuppliedWind() {
        saveManualLocation(SHANGHAI)
        val repository = repository(transport = WatchWeatherHttpTransport { _, _ ->
            """{"current":{"temperature":{"value":"21"},"weather":"0","wind":{"speed":{"value":"18"},"direction":{"value":"90"}}},
                "forecastDaily":{"temperature":{"value":[{"from":"30","to":"20"},{"from":"31","to":"19"}]},"weather":{"value":[{"from":"0"},{"from":"1"}]}}}"""
        })

        repository.requestRefresh()
        awaitWorker(repository)
        val weather = repository.snapshot(System.currentTimeMillis())

        assertEquals(30.0, weather?.highCelsius ?: Double.NaN, 0.0)
        assertEquals(20.0, weather?.lowCelsius ?: Double.NaN, 0.0)
        assertEquals(31.0, weather?.forecast?.firstOrNull()?.highCelsius ?: Double.NaN, 0.0)
        assertEquals(1, weather?.forecast?.firstOrNull()?.dayOffset)
        assertNull(weather?.forecast?.firstOrNull()?.temperatureCelsius)
        val cached = repository().snapshot(System.currentTimeMillis())
        assertEquals(5.0, cached?.windSpeedMetersPerSecond ?: Double.NaN, 0.0)
        assertEquals(90.0, cached?.windDirectionDegrees ?: Double.NaN, 0.0)
    }

    @Test
    fun chinaWeatherCodesAndHourlyValuesUseTheChinaSchema() {
        saveManualLocation(SHANGHAI)
        val now = java.time.OffsetDateTime.parse("2026-09-05T10:15:00+08:00").toInstant().toEpochMilli()
        val repository = repository(transport = WatchWeatherHttpTransport { _, _ ->
            """{"current":{"pubTime":"2026-09-05T10:00:00+08:00","temperature":{"value":"21"},"weather":"9"},
                "forecastHourly":{"temperature":{"pubTime":"2026-09-05T10:00:00+08:00","value":[21,22,23]},"weather":{"value":[9,15,4]}}}"""
        })

        repository.requestRefresh(now)
        awaitWorker(repository)
        val weather = repository.snapshot(now + 1L)

        assertEquals("Rain", weather?.condition)
        assertEquals(22.0, weather?.hourlyForecast?.firstOrNull()?.temperatureCelsius ?: Double.NaN, 0.0)
        assertEquals(1, weather?.hourlyForecast?.firstOrNull()?.hourOffset)
        assertEquals(11, weather?.hourlyForecast?.firstOrNull()?.hourOfDay)
        assertEquals("Snow", weather?.hourlyForecast?.firstOrNull()?.condition)
    }

    @Test
    fun chinaLocationSearchRejectsForeignKeysAndInvalidCoordinates() {
        val locations = parseChinaWatchWeatherLocationSearch(
            """[{"name":"无身份","latitude":"31","longitude":"121"},
                {"name":"无效坐标","latitude":"999","longitude":"121","locationKey":"weathercn:101"},
                {"name":"国外身份","latitude":"31","longitude":"121","locationKey":"accu:123"},
                {"name":"上海市","latitude":"31.2304","longitude":"121.4737","locationKey":"weathercn:101020100"}]""",
            "上海",
            8,
        )

        assertEquals(1, locations.size)
        assertEquals(SHANGHAI.chinaLocationKey, locations.single().chinaLocationKey)
    }

    private fun repository(
        locationSource: WatchWeatherLocationSource = WatchWeatherLocationSource { null },
        transport: WatchWeatherHttpTransport = WatchWeatherHttpTransport { _, _ -> null },
    ) = WatchWeatherRepository(context, locationSource = locationSource, transport = transport)

    private fun saveManualLocation(location: WatchWeatherLocation) {
        preferences.edit()
            .putString(WatchWeatherRepository.KEY_LABEL, location.label)
            .putString(WatchWeatherRepository.KEY_LATITUDE, location.latitude.toString())
            .putString(WatchWeatherRepository.KEY_LONGITUDE, location.longitude.toString())
            .putString(WatchWeatherRepository.KEY_CHINA_LOCATION_KEY, location.chinaLocationKey)
            .commit()
    }

    private fun saveSystemLocation(location: WatchWeatherLocation) {
        preferences.edit()
            .putInt("system_location_identity_version", 1)
            .putString("system_resolved_anchor_latitude", location.latitude.toString())
            .putString("system_resolved_anchor_longitude", location.longitude.toString())
            .putString(WatchWeatherRepository.KEY_SYSTEM_LOCATION_LABEL, location.label)
            .putString(WatchWeatherRepository.KEY_SYSTEM_LATITUDE, location.latitude.toString())
            .putString(WatchWeatherRepository.KEY_SYSTEM_LONGITUDE, location.longitude.toString())
            .putString(WatchWeatherRepository.KEY_SYSTEM_CHINA_LOCATION_KEY, location.chinaLocationKey)
            .commit()
    }

    private fun cachedWeather(location: WatchWeatherLocation, provider: String?): String = JSONObject().apply {
        put("temperatureCelsius", 9.0)
        put("location", location.label)
        put("sourceLatitude", location.latitude)
        put("sourceLongitude", location.longitude)
        if (provider != null) {
            put("sourceProvider", provider)
            put("sourceChinaLocationKey", location.chinaLocationKey)
        }
    }.toString()

    /** Drain the actual single worker without triggering another refresh through snapshot(). */
    private fun awaitWorker(repository: WatchWeatherRepository) {
        val field = WatchWeatherRepository::class.java.getDeclaredField("refreshExecutor").apply { isAccessible = true }
        val executor = field.get(repository) as java.util.concurrent.ExecutorService
        executor.submit {}.get(4, TimeUnit.SECONDS)
    }

    private fun chinaLocations(location: WatchWeatherLocation): String =
        """[{"name":"${location.label}","latitude":"${location.latitude}","longitude":"${location.longitude}","locationKey":"${location.chinaLocationKey}"}]"""

    private fun chinaWeather(temperature: Double): String =
        """{"current":{"temperature":{"value":"$temperature"},"weather":"0"}}"""

    companion object {
        private val SHANGHAI = WatchWeatherLocation(31.2304, 121.4737, "上海市, 中国", chinaLocationKey = "weathercn:101020100")
        private val BEIJING = WatchWeatherLocation(39.9042, 116.4074, "北京市, 中国", chinaLocationKey = "weathercn:101010100")
    }
}
