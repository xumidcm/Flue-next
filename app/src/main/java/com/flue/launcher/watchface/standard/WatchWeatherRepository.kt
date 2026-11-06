package com.flue.launcher.watchface.standard

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** A location input used by the weather repository; no permission is requested here. */
data class WatchWeatherLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
    /** Optional elevation supplied with this location; absent values remain unavailable. */
    val elevation: Double? = null,
    val chinaLocationKey: String? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude out of range" }
    }
}

/** Complete facts carried from system-location lookup to the user's selection action. */
data class ResolvedSystemWeatherLocation internal constructor(
    val city: WatchWeatherLocation,
    val rawLocation: WatchWeatherLocation,
    val resolvedAnchor: WatchWeatherLocation,
    internal val selectionRevision: Long,
)

enum class WatchWeatherProvider {
    CHINA;

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun fromRaw(raw: String?): WatchWeatherProvider =
            CHINA
    }
}

fun interface WatchWeatherLocationSource {
    fun current(): WatchWeatherLocation?
}

/**
 * 按当前**实际授予**的权限过滤 provider。
 *
 * GPS 与 passive 都要求 `ACCESS_FINE_LOCATION`。只授予了「大致位置」时对它们调
 * `getCurrentLocation` 会抛 SecurityException，而那一层是 `runCatching` 包着的 ——
 * 异常被吞掉，看起来像「请求发出去了但一直没回」：latch 一路等到 6 秒超时，
 * 连同一轮里可用的 network/fused 结果也一起白等，于是永远拿不到 fix、
 * {alat} 永远没有数据源。宁可不报这两个，也别让整轮定位挂在它们身上。
 *
 * 排序不管（那是 [prioritizeWatchWeatherLocationProviders] 的活），只做筛除。
 */
internal fun filterWatchWeatherLocationProvidersByPermission(
    providers: Iterable<String>,
    hasFineLocationPermission: Boolean,
): List<String> {
    if (hasFineLocationPermission) return providers.toList()
    return providers.filterNot { provider ->
        // provider 名的大小写由平台决定，不能只比小写字面量。
        provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true) ||
            provider.equals(LocationManager.PASSIVE_PROVIDER, ignoreCase = true)
    }
}

internal fun prioritizeWatchWeatherLocationProviders(providers: Iterable<String>): List<String> =
    providers
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .sortedWith(
            compareBy<String> { provider ->
                when {
                    provider.contains("fused", ignoreCase = true) -> 0
                    provider.equals(LocationManager.NETWORK_PROVIDER, ignoreCase = true) -> 1
                    provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true) -> 3
                    provider.equals(LocationManager.PASSIVE_PROVIDER, ignoreCase = true) -> 4
                    else -> 2
                }
            }.thenBy { it.lowercase(Locale.ROOT) },
        )
        .toList()

/** Injectable synchronous HTTP seam for deterministic repository tests. */
fun interface WatchWeatherHttpTransport {
    fun get(url: String, userAgent: String): String?
}

/**
 * Application-owned weather repository. It is deliberately cache-first and
 * non-blocking on the renderer thread: [snapshot] returns the last good value
 * immediately and schedules a refresh when the soft TTL expires.
 *
 * China is the only forecast and city-search source.
 * A coarse last-known location is used only when the app already has location
 * permission. Otherwise [setManualLocation] is the explicit fallback.
 */
class WatchWeatherRepository(
    context: Context,
    private val locationSource: WatchWeatherLocationSource? = null,
    private val transport: WatchWeatherHttpTransport = DefaultWatchWeatherHttpTransport,
    private val softTtlMs: Long = DEFAULT_SOFT_TTL_MS,
) : WatchWeatherSource {
    private val appContext = context.applicationContext
    private val cache = WatchWeatherSnapshotCache(maxAgeMs = MAX_CACHE_AGE_MS)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val refreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "flue-weather-refresh").apply { isDaemon = true }
    }
    private var refreshInFlight = false
    private var pendingRefreshAtMs: Long? = null
    private var refreshingRevision = -1L
    private val locationCacheLock = Any()
    private var locationRevision = 0L

    @Volatile
    private var lastRefreshAttemptMs = Long.MIN_VALUE

    @Volatile
    private var manualLocation: WatchWeatherLocation? = readManualLocation()

    @Volatile
    private var manualCityValue: String? = readManualCity()

    @Volatile
    private var systemLocationValue: WatchWeatherLocation? = readSystemLocation()

    @Volatile
    private var systemRawLocationValue: WatchWeatherLocation? = readSystemRawLocation()

    @Volatile
    private var systemResolvedAnchorValue: WatchWeatherLocation? = readSystemResolvedAnchor()

    @Volatile
    private var temperatureUnitValue: WatchTemperatureUnit = readTemperatureUnit()

    init {
        migrateChinaProvider()
        loadPersistedSnapshot()?.let { (snapshot, receivedAtEpochMs) ->
            cache.update(snapshot, receivedAtEpochMs)
        }
    }

    override fun snapshot(nowEpochMs: Long): WatchWeatherSnapshot? = synchronized(locationCacheLock) {
        val cached = cache.snapshot(nowEpochMs)
        val shouldRefresh = lastRefreshAttemptMs == Long.MIN_VALUE ||
            (nowEpochMs >= lastRefreshAttemptMs && nowEpochMs - lastRefreshAttemptMs >= softTtlMs)
        if (shouldRefresh) scheduleRefresh(nowEpochMs)
        cached?.copy(displayUnit = temperatureUnitValue)
    }

    fun temperatureUnit(): WatchTemperatureUnit = temperatureUnitValue

    fun weatherProvider(): WatchWeatherProvider = WatchWeatherProvider.CHINA

    /** Persists a display preference without refetching or rewriting weather data. */
    fun setTemperatureUnit(unit: WatchTemperatureUnit) {
        temperatureUnitValue = unit
        preferences.edit().putString(KEY_TEMPERATURE_UNIT, unit.symbol).apply()
    }

    /** String boundary for settings/preferences that store `C`, `F`, or `°F`. */
    fun setTemperatureUnitRaw(raw: String?) {
        setTemperatureUnit(WatchTemperatureUnit.fromRaw(raw))
    }

    /** Persists a selected city or a legacy coordinate while preserving manual mode. */
    fun setManualLocation(location: WatchWeatherLocation?) {
        synchronized(locationCacheLock) {
            manualLocation = location
            manualCityValue = null
            systemLocationValue = null
            systemRawLocationValue = null
            systemResolvedAnchorValue = null
            val editor = preferences.edit().remove(KEY_CITY)
            writeManualLocation(editor, location)
            removeSystemLocation(editor)
            editor.apply()
            invalidateWeatherForLocationChange()
        }
        requestRefresh()
    }

    /** Resolves a legacy manual city using the China city-search endpoint. */
    fun setManualCity(city: String?) {
        val normalized = city?.trim()?.takeIf(String::isNotEmpty)?.take(120)
        synchronized(locationCacheLock) {
            manualCityValue = normalized
            manualLocation = null
            systemLocationValue = null
            systemRawLocationValue = null
            systemResolvedAnchorValue = null
            val editor = preferences.edit()
            writeManualLocation(editor, null)
            if (normalized == null) editor.remove(KEY_CITY) else editor.putString(KEY_CITY, normalized)
            removeSystemLocation(editor)
            editor.apply()
            invalidateWeatherForLocationChange()
        }
        requestRefresh()
    }

    fun manualCity(): String? = manualCityValue

    fun activeLocationLabel(): String? = synchronized(locationCacheLock) {
        manualLocation?.label ?: manualCityValue ?: systemLocationValue?.label
    }

    fun isUsingSystemLocation(): Boolean = synchronized(locationCacheLock) {
        manualLocation == null && manualCityValue == null
    }

    /** Location tags read the fix separately from the China forecast city's coordinates. */
    internal fun activeLocation(): WatchWeatherLocation? = synchronized(locationCacheLock) {
        manualLocation ?: systemRawLocationValue ?: systemLocationValue
    }

    /**
     * Resolves multiple forward-geocoding candidates. This method performs IO;
     * callers must invoke it off the main thread and persist only a chosen item.
     */
    fun searchLocations(query: String, limit: Int = 8): List<WatchWeatherLocation> {
        val normalized = normalizeWatchWeatherCityQuery(query)
        if (normalized.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 10)
        val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
        return transport.get(
            "https://weatherapi.market.xiaomi.com/wtr-v3/location/city/search" +
                "?name=$encoded&locale=zh_CN",
            USER_AGENT,
        )?.let { parseChinaWatchWeatherLocationSearch(it, normalized, safeLimit) }.orEmpty()
    }

    /** Reads a fix and resolves its China identity without changing the current selection. */
    fun resolveSystemLocation(): ResolvedSystemWeatherLocation? {
        val request = synchronized(locationCacheLock) { refreshRequest(System.currentTimeMillis()) }
        val raw = currentSystemFix(request.systemRawLocation ?: request.systemResolvedAnchor ?: request.systemLocation) ?: return null
        val resolved = resolveChinaLocation(raw, request.systemLocation, request.systemResolvedAnchor) ?: return null
        return synchronized(locationCacheLock) {
            if (request.revision != locationRevision) null else ResolvedSystemWeatherLocation(
                city = resolved.city,
                rawLocation = raw,
                resolvedAnchor = resolved.anchor,
                selectionRevision = request.revision,
            )
        }
    }

    /** Applies the original fix and its China identity together, without requiring a second fix. */
    fun useSystemLocation(resolved: ResolvedSystemWeatherLocation): Boolean {
        val applied = synchronized(locationCacheLock) {
            if (resolved.selectionRevision != locationRevision) false else {
                applySystemLocation(resolved.city, resolved.rawLocation, resolved.resolvedAnchor)
                true
            }
        }
        if (applied) requestRefresh()
        return applied
    }

    /** Compatibility boundary for hosts that explicitly inject only a resolved city. */
    fun useSystemLocation(location: WatchWeatherLocation) {
        synchronized(locationCacheLock) { applySystemLocation(location, null, null) }
        requestRefresh()
    }

    /** Caller holds locationCacheLock so selection, coordinates and revision change atomically. */
    private fun applySystemLocation(
        city: WatchWeatherLocation,
        raw: WatchWeatherLocation?,
        anchor: WatchWeatherLocation?,
    ) {
        manualLocation = null
        manualCityValue = null
        systemLocationValue = city.copy(label = city.label?.trim()?.takeIf(String::isNotEmpty))
        systemRawLocationValue = raw?.copy(chinaLocationKey = null)
        systemResolvedAnchorValue = anchor?.copy(chinaLocationKey = null)
        val editor = preferences.edit().remove(KEY_CITY)
        writeManualLocation(editor, null)
        writeSystemLocation(editor, systemLocationValue!!, systemRawLocationValue, systemResolvedAnchorValue)
        editor.apply()
        invalidateWeatherForLocationChange()
    }

    fun clearCache() = synchronized(locationCacheLock) {
        cache.clear()
        preferences.edit().remove(KEY_CACHE_JSON).remove(KEY_CACHE_RECEIVED_AT).apply()
    }

    private fun invalidateWeatherForLocationChange() {
        locationRevision++
        clearCache()
    }

    fun requestRefresh(nowEpochMs: Long = System.currentTimeMillis()) {
        scheduleRefresh(nowEpochMs, force = true)
    }

    private data class RefreshRequest(
        val revision: Long,
        val nowEpochMs: Long,
        val manualLocation: WatchWeatherLocation?,
        val manualCity: String?,
        val systemLocation: WatchWeatherLocation?,
        val systemRawLocation: WatchWeatherLocation?,
        val systemResolvedAnchor: WatchWeatherLocation?,
    )

    /** Caller holds locationCacheLock, so selection and revision are one atomic snapshot. */
    private fun refreshRequest(nowEpochMs: Long) = RefreshRequest(
        locationRevision, nowEpochMs, manualLocation, manualCityValue, systemLocationValue, systemRawLocationValue, systemResolvedAnchorValue,
    )

    private fun scheduleRefresh(nowEpochMs: Long, force: Boolean = false) {
        val initial = synchronized(locationCacheLock) {
            if (refreshInFlight) {
                if (force || refreshingRevision != locationRevision) pendingRefreshAtMs = nowEpochMs
                return
            }
            refreshInFlight = true
            refreshingRevision = locationRevision
            lastRefreshAttemptMs = nowEpochMs
            refreshRequest(nowEpochMs)
        }
        refreshExecutor.execute {
            var request = initial
            while (true) {
                runCatching { refresh(request) }
                request = synchronized(locationCacheLock) {
                    val pending = pendingRefreshAtMs
                    pendingRefreshAtMs = null
                    if (pending == null) {
                        refreshInFlight = false
                        return@execute
                    }
                    refreshingRevision = locationRevision
                    lastRefreshAttemptMs = pending
                    refreshRequest(pending)
                }
            }
        }
    }

    private fun refresh(request: RefreshRequest) {
        val systemMode = request.manualLocation == null && request.manualCity == null
        var rawFix: WatchWeatherLocation? = null
        val resolution = when {
            request.manualLocation != null -> resolveChinaLocation(request.manualLocation)
            request.manualCity != null -> searchLocations(request.manualCity, limit = 1).firstOrNull()
                ?.let { ResolvedChinaLocation(it, it.copy(chinaLocationKey = null)) }
            else -> {
                rawFix = currentSystemFix(request.systemRawLocation ?: request.systemResolvedAnchor ?: request.systemLocation)
                rawFix?.let { resolveChinaLocation(it, request.systemLocation, request.systemResolvedAnchor) }
            }
        }
        val resolved = resolution?.city
        synchronized(locationCacheLock) {
            if (request.revision != locationRevision) return
            if (resolved == null) {
                rawFix?.let { fix ->
                    // Location tags remain current even when the China city service fails.
                    // This raw fix is not evidence that the previous city is still correct.
                    systemRawLocationValue = fix.copy(chinaLocationKey = null)
                    val editor = preferences.edit()
                    writeSystemRawLocation(editor, systemRawLocationValue)
                    editor.apply()
                    if (!nearby(fix, request.systemResolvedAnchor)) clearCache()
                }
                return
            }
            val previous = if (systemMode) systemLocationValue else manualLocation
            if (previous?.chinaLocationKey != resolved.chinaLocationKey) clearCache()
            val editor = preferences.edit()
            if (systemMode) {
                systemLocationValue = resolved
                systemRawLocationValue = rawFix?.copy(chinaLocationKey = null)
                systemResolvedAnchorValue = resolution?.anchor
                writeSystemLocation(editor, resolved, systemRawLocationValue, systemResolvedAnchorValue)
            } else {
                manualLocation = resolved
                writeManualLocation(editor, resolved)
            }
            editor.apply()
        }
        val location = resolved ?: return
        val refreshed = fetchChina(location, request.nowEpochMs) ?: return
        synchronized(locationCacheLock) {
            if (request.revision != locationRevision) return
            val canonical = canonicalWatchWeatherSnapshot(refreshed).copy(displayUnit = temperatureUnitValue)
            cache.update(canonical, request.nowEpochMs)
            persistSnapshot(canonical, request.nowEpochMs, location)
        }
    }

    private fun currentSystemFix(fallback: WatchWeatherLocation?): WatchWeatherLocation? =
        locationSource?.current() ?: readCurrentLocation() ?: readLastKnownLocation() ?: fallback

    private data class ResolvedChinaLocation(val city: WatchWeatherLocation, val anchor: WatchWeatherLocation)

    /** The anchor moves only after a successful lookup, so small moves cannot accumulate unnoticed. */
    private fun resolveChinaLocation(
        raw: WatchWeatherLocation,
        previousCity: WatchWeatherLocation? = null,
        previousAnchor: WatchWeatherLocation? = null,
    ): ResolvedChinaLocation? {
        if (validChinaKey(raw.chinaLocationKey)) return ResolvedChinaLocation(raw, raw.copy(chinaLocationKey = null))
        if (previousCity != null && previousAnchor != null && validChinaKey(previousCity.chinaLocationKey) && nearby(raw, previousAnchor)) {
            return ResolvedChinaLocation(previousCity, previousAnchor)
        }
        val fromCoordinates = runCatching {
            transport.get(
                "https://weatherapi.market.xiaomi.com/wtr-v3/location/city/geo" +
                    "?latitude=${raw.latitude}&longitude=${raw.longitude}&locale=zh_CN",
                USER_AGENT,
            )?.let { parseChinaWatchWeatherLocationSearch(it, "", 10) }.orEmpty()
        }.getOrDefault(emptyList())
        if (fromCoordinates.isNotEmpty()) return nearestLocation(raw, fromCoordinates)
            ?.let { ResolvedChinaLocation(it, raw.copy(chinaLocationKey = null)) }
        val label = raw.label ?: reverseGeocodeLabel(raw)
        val queries = (chinaReverseGeocodeTerms(raw) + label.orEmpty().split(','))
            .map(String::trim).filter(String::isNotEmpty).distinct()
        val candidates = queries.flatMap { runCatching { searchLocations(it, 10) }.getOrDefault(emptyList()) }
        return nearestLocation(raw, candidates)?.let { ResolvedChinaLocation(it, raw.copy(chinaLocationKey = null)) }
    }

    private fun nearestLocation(raw: WatchWeatherLocation, locations: List<WatchWeatherLocation>): WatchWeatherLocation? =
        locations.minByOrNull { candidate ->
            val dLat = candidate.latitude - raw.latitude
            val dLon = candidate.longitude - raw.longitude
            dLat * dLat + dLon * dLon
        }

    private fun nearby(first: WatchWeatherLocation, second: WatchWeatherLocation?): Boolean =
        second != null && abs(first.latitude - second.latitude) <= SYSTEM_LOCATION_LABEL_TOLERANCE_DEGREES &&
            abs(first.longitude - second.longitude) <= SYSTEM_LOCATION_LABEL_TOLERANCE_DEGREES

    @SuppressLint("MissingPermission")
    private fun readLastKnownLocation(): WatchWeatherLocation? {
        if (!hasLocationPermission()) return null
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null
        val providers = systemLocationProviders(manager, enabledOnly = false)
        return providers.asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { location -> location.latitude.isFinite() && location.longitude.isFinite() }
            .maxByOrNull { it.time }
            ?.let { location -> WatchWeatherLocation(location.latitude, location.longitude) }
    }

    /**
     * Requests one fresh fix on every enabled coarse-capable provider and uses
     * the first valid result. The settings caller runs this blocking boundary
     * on Dispatchers.IO, so neither the renderer nor the main thread waits.
     * API 27-29 use requestSingleUpdate; API 30+ use getCurrentLocation.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readCurrentLocation(): WatchWeatherLocation? {
        if (!hasLocationPermission()) return null
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null
        val providers = systemLocationProviders(manager, enabledOnly = true)
        if (providers.isEmpty()) return null

        val result = AtomicReference<Location?>(null)
        val delivered = CountDownLatch(1)
        fun publish(location: Location?) {
            if (location == null || !location.latitude.isFinite() || !location.longitude.isFinite()) return
            if (result.compareAndSet(null, location)) delivered.countDown()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancellations = providers.mapNotNull { provider ->
                runCatching {
                    CancellationSignal().also { signal ->
                        manager.getCurrentLocation(
                            provider,
                            signal,
                            DirectWeatherExecutor,
                            ::publish,
                        )
                    }
                }.getOrNull()
            }
            if (cancellations.isEmpty()) return null
            runCatching { delivered.await(CURRENT_LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            cancellations.forEach { signal -> runCatching { signal.cancel() } }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = publish(location)
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            var registered = false
            providers.forEach { provider ->
                val accepted = runCatching {
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    true
                }.getOrDefault(false)
                registered = registered || accepted
            }
            if (!registered) return null
            runCatching { delivered.await(CURRENT_LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            runCatching { manager.removeUpdates(listener) }
        }
        return result.get()?.let { location ->
            WatchWeatherLocation(location.latitude, location.longitude)
        }
    }

    /**
     * Old Wear OS/vendor Android builds may expose their usable coarse source
     * as a legacy `fused` or vendor provider rather than NETWORK_PROVIDER.
     * Discover the device providers instead of assuming the three AOSP names.
     *
     * 报上来之前先按**实际授予**的权限筛一遍 —— 只有粗定位时把 GPS / passive 留在
     * 名单里会让整轮定位卡到超时，理由见 [filterWatchWeatherLocationProvidersByPermission]。
     */
    private fun systemLocationProviders(
        manager: LocationManager,
        enabledOnly: Boolean,
    ): List<String> {
        val discovered = runCatching { manager.getProviders(enabledOnly) }.getOrDefault(emptyList())
        val candidates = buildList {
            addAll(discovered)
            // Keep well-known providers as a fallback for devices whose provider
            // enumeration is incomplete. Every individual access is guarded.
            add(LEGACY_FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        val permitted = filterWatchWeatherLocationProvidersByPermission(
            candidates,
            hasFineLocationPermission = hasFineLocationPermission(),
        )
        return prioritizeWatchWeatherLocationProviders(permitted).filter { provider ->
            !enabledOnly || runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    private fun hasLocationPermission(): Boolean =
        WatchLocationPermissionPolicy.isGranted(appContext)

    private fun hasFineLocationPermission(): Boolean =
        WatchLocationPermissionPolicy.hasFineLocation(appContext)

    @Suppress("DEPRECATION")
    private fun reverseGeocodeLabel(location: WatchWeatherLocation): String? {
        val platformLabel = if (Geocoder.isPresent()) {
            runCatching {
                Geocoder(appContext, Locale.getDefault())
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
            }.getOrNull()?.let { address ->
                listOf(
                    address.locality,
                    address.subAdminArea,
                    address.adminArea,
                    address.countryName,
                ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                    .distinct()
                    .joinToString(", ")
                    .takeIf(String::isNotBlank)
            }
        } else {
            null
        }
        return platformLabel ?: reverseGeocodeLabelFromNetwork(location)
    }

    /** Fallback for older devices whose system Geocoder backend is absent. */
    private fun reverseGeocodeLabelFromNetwork(location: WatchWeatherLocation): String? = runCatching {
        reverseGeocodeLabelFromNetworkUnchecked(location)
    }.getOrNull()

    private fun reverseGeocodeLabelFromNetworkUnchecked(location: WatchWeatherLocation): String? {
        val latitude = String.format(Locale.US, "%.6f", location.latitude)
        val longitude = String.format(Locale.US, "%.6f", location.longitude)
        val language = URLEncoder.encode(
            Locale.getDefault().toLanguageTag().ifBlank { "en" },
            Charsets.UTF_8.name(),
        )
        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=jsonv2&lat=$latitude&lon=$longitude&zoom=10&addressdetails=1" +
            "&accept-language=$language"
        val root = transport.get(url, USER_AGENT)
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?: return null
        val address = root.optJSONObject("address")
        val locality = listOf("city", "town", "village", "municipality", "county")
            .firstNotNullOfOrNull { key ->
                address?.optString(key)?.trim()?.takeIf(String::isNotEmpty)
            }
        return listOf(
            locality,
            address?.optString("state")?.trim()?.takeIf(String::isNotEmpty),
            address?.optString("country")?.trim()?.takeIf(String::isNotEmpty),
        ).filterNotNull().distinct().joinToString(", ").takeIf(String::isNotBlank)
    }

    /** Returns the most specific Chinese reverse-geocoder names for system-location mapping. */
    private fun chinaReverseGeocodeTerms(location: WatchWeatherLocation): List<String> = runCatching {
        val latitude = String.format(Locale.US, "%.6f", location.latitude)
        val longitude = String.format(Locale.US, "%.6f", location.longitude)
        val root = transport.get(
            "https://nominatim.openstreetmap.org/reverse" +
                "?format=jsonv2&lat=$latitude&lon=$longitude&zoom=18&addressdetails=1" +
                "&accept-language=zh-CN",
            USER_AGENT,
        )?.let(::JSONObject) ?: return@runCatching emptyList()
        val address = root.optJSONObject("address") ?: return@runCatching emptyList()
        listOf(
            "town", "village", "municipality", "county", "city_district",
            "city", "state_district", "state",
        ).mapNotNull { key ->
            address.optString(key).trim().takeIf(String::isNotEmpty)
        }.distinct()
    }.getOrDefault(emptyList())

    private fun readManualLocation(): WatchWeatherLocation? {
        val latitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return runCatching {
            WatchWeatherLocation(
                latitude,
                longitude,
                preferences.getString(KEY_LABEL, null),
                chinaLocationKey = preferences.getString(KEY_CHINA_LOCATION_KEY, null),
            )
        }.getOrNull()
    }

    private fun readSystemLocation(): WatchWeatherLocation? {
        val latitude = preferences.getString(KEY_SYSTEM_LATITUDE, null)?.toDoubleOrNull()
            ?: return null
        val longitude = preferences.getString(KEY_SYSTEM_LONGITUDE, null)?.toDoubleOrNull()
            ?: return null
        val label = preferences.getString(KEY_SYSTEM_LOCATION_LABEL, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        return runCatching {
            WatchWeatherLocation(
                latitude,
                longitude,
                label,
                chinaLocationKey = preferences.getString(KEY_SYSTEM_CHINA_LOCATION_KEY, null),
            )
        }.getOrNull()
    }

    private fun readSystemRawLocation(): WatchWeatherLocation? =
        readLocationCoordinates(KEY_SYSTEM_RAW_LATITUDE, KEY_SYSTEM_RAW_LONGITUDE)

    private fun readSystemResolvedAnchor(): WatchWeatherLocation? =
        readLocationCoordinates(KEY_SYSTEM_RESOLVED_ANCHOR_LATITUDE, KEY_SYSTEM_RESOLVED_ANCHOR_LONGITUDE)

    private fun readLocationCoordinates(latitudeKey: String, longitudeKey: String): WatchWeatherLocation? {
        val latitude = preferences.getString(latitudeKey, null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(longitudeKey, null)?.toDoubleOrNull() ?: return null
        return runCatching { WatchWeatherLocation(latitude, longitude) }.getOrNull()
    }

    private fun writeManualLocation(editor: android.content.SharedPreferences.Editor, location: WatchWeatherLocation?) {
        if (location == null) {
            editor.remove(KEY_LABEL).remove(KEY_LATITUDE).remove(KEY_LONGITUDE).remove(KEY_CHINA_LOCATION_KEY)
        } else {
            editor.putString(KEY_LABEL, location.label)
                .putString(KEY_LATITUDE, location.latitude.toString())
                .putString(KEY_LONGITUDE, location.longitude.toString())
                .putString(KEY_CHINA_LOCATION_KEY, location.chinaLocationKey)
        }
    }

    private fun removeSystemLocation(editor: android.content.SharedPreferences.Editor) {
        editor.remove(KEY_SYSTEM_LOCATION_LABEL).remove(KEY_SYSTEM_LATITUDE).remove(KEY_SYSTEM_LONGITUDE)
            .remove(KEY_SYSTEM_CHINA_LOCATION_KEY).remove(KEY_SYSTEM_RAW_LATITUDE).remove(KEY_SYSTEM_RAW_LONGITUDE)
            .remove(KEY_SYSTEM_RESOLVED_ANCHOR_LATITUDE).remove(KEY_SYSTEM_RESOLVED_ANCHOR_LONGITUDE)
            .remove(KEY_SYSTEM_LOCATION_IDENTITY_VERSION)
    }

    private fun writeSystemRawLocation(editor: android.content.SharedPreferences.Editor, raw: WatchWeatherLocation?) {
        editor.putString(KEY_SYSTEM_RAW_LATITUDE, raw?.latitude?.toString())
            .putString(KEY_SYSTEM_RAW_LONGITUDE, raw?.longitude?.toString())
    }

    private fun writeSystemLocation(
        editor: android.content.SharedPreferences.Editor,
        city: WatchWeatherLocation,
        raw: WatchWeatherLocation?,
        anchor: WatchWeatherLocation?,
    ) {
        editor.putInt(KEY_SYSTEM_LOCATION_IDENTITY_VERSION, SYSTEM_LOCATION_IDENTITY_VERSION)
            .putString(KEY_SYSTEM_LOCATION_LABEL, city.label)
            .putString(KEY_SYSTEM_LATITUDE, city.latitude.toString())
            .putString(KEY_SYSTEM_LONGITUDE, city.longitude.toString())
            .putString(KEY_SYSTEM_CHINA_LOCATION_KEY, city.chinaLocationKey)
            .putString(KEY_SYSTEM_RESOLVED_ANCHOR_LATITUDE, anchor?.latitude?.toString())
            .putString(KEY_SYSTEM_RESOLVED_ANCHOR_LONGITUDE, anchor?.longitude?.toString())
        writeSystemRawLocation(editor, raw)
    }

    private fun migrateChinaProvider() {
        val oldProvider = preferences.getString(KEY_PROVIDER, null)
        val persisted = preferences.getString(KEY_CACHE_JSON, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sourceKnown = oldProvider == WatchWeatherProvider.CHINA.name &&
            persisted?.optString("sourceProvider") == WatchWeatherProvider.CHINA.name &&
            validChinaKey(persisted.optNullableString("sourceChinaLocationKey"))
        val editor = preferences.edit().putString(KEY_PROVIDER, WatchWeatherProvider.CHINA.name)
        val legacySystemIdentity = systemLocationValue != null &&
            preferences.getInt(KEY_SYSTEM_LOCATION_IDENTITY_VERSION, 0) != SYSTEM_LOCATION_IDENTITY_VERSION
        if (legacySystemIdentity) {
            // Older builds mixed raw coordinates with a previously resolved city's name/key.
            // Keep their coordinates available, but require a new China lookup before weather.
            val raw = (systemRawLocationValue ?: systemLocationValue)?.copy(label = null, chinaLocationKey = null)
            removeSystemLocation(editor)
            systemLocationValue = null
            systemResolvedAnchorValue = null
            systemRawLocationValue = raw
            writeSystemRawLocation(editor, raw)
            editor.putInt(KEY_SYSTEM_LOCATION_IDENTITY_VERSION, SYSTEM_LOCATION_IDENTITY_VERSION)
        }
        if (!sourceKnown || legacySystemIdentity) editor.remove(KEY_CACHE_JSON).remove(KEY_CACHE_RECEIVED_AT)
        // These legacy terrain values came from the removed weather source.
        editor.remove(KEY_ELEVATION).remove(KEY_ELEVATION_LATITUDE).remove(KEY_ELEVATION_LONGITUDE).apply()
    }

    private fun readManualCity(): String? =
        preferences.getString(KEY_CITY, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun readTemperatureUnit(): WatchTemperatureUnit =
        WatchTemperatureUnit.fromRaw(preferences.getString(KEY_TEMPERATURE_UNIT, null))

    private fun persistSnapshot(
        snapshot: WatchWeatherSnapshot,
        receivedAtEpochMs: Long,
        sourceLocation: WatchWeatherLocation,
    ) {
        val root = JSONObject().apply {
            put("sourceProvider", WatchWeatherProvider.CHINA.name)
            put("sourceChinaLocationKey", sourceLocation.chinaLocationKey)
            put("sourceLatitude", sourceLocation.latitude)
            put("sourceLongitude", sourceLocation.longitude)
            putNullable("temperatureCelsius", snapshot.temperatureCelsius)
            putNullable("highCelsius", snapshot.highCelsius)
            putNullable("lowCelsius", snapshot.lowCelsius)
            put("displayUnit", snapshot.displayUnit.name)
            putNullable("observedAtEpochMs", snapshot.observedAtEpochMs)
            putNullable("lastUpdatedText", snapshot.lastUpdatedText)
            putNullable("isDaytime", snapshot.isDaytime)
            putNullable("sunriseText", snapshot.sunriseText)
            putNullable("sunsetText", snapshot.sunsetText)
            putNullable("condition", snapshot.condition)
            putNullable("icon", snapshot.icon)
            putNullable("humidity", snapshot.humidity)
            putNullable("cloudCoverFraction", snapshot.cloudCoverFraction)
            putNullable("location", snapshot.location)
            putNullable("windSpeedMetersPerSecond", snapshot.windSpeedMetersPerSecond)
            putNullable("windDirectionDegrees", snapshot.windDirectionDegrees)
            put("forecast", JSONArray().apply {
                snapshot.forecast.take(6).forEach { item ->
                    put(JSONObject().apply {
                        put("dayOffset", item.dayOffset)
                        putNullable("temperatureCelsius", item.temperatureCelsius)
                        putNullable("highCelsius", item.highCelsius)
                        putNullable("lowCelsius", item.lowCelsius)
                        putNullable("condition", item.condition)
                        putNullable("icon", item.icon)
                    })
                }
            })
            put("hourlyForecast", JSONArray().apply {
                snapshot.hourlyForecast.take(12).forEach { item ->
                    put(JSONObject().apply {
                        put("hourOffset", item.hourOffset)
                        putNullable("hourOfDay", item.hourOfDay)
                        putNullable("temperatureCelsius", item.temperatureCelsius)
                        putNullable("condition", item.condition)
                        putNullable("icon", item.icon)
                    })
                }
            })
        }
        preferences.edit()
            .putLong(KEY_CACHE_RECEIVED_AT, receivedAtEpochMs)
            .putString(KEY_CACHE_JSON, root.toString())
            .apply()
    }

    private fun loadPersistedSnapshot(nowEpochMs: Long = System.currentTimeMillis()): Pair<WatchWeatherSnapshot, Long>? {
        val receivedAt = preferences.getLong(KEY_CACHE_RECEIVED_AT, Long.MIN_VALUE)
        if (receivedAt < 0L || nowEpochMs < receivedAt ||
            nowEpochMs - receivedAt > MAX_CACHE_AGE_MS
        ) return null
        val root = preferences.getString(KEY_CACHE_JSON, null)
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?: return null
        val configuredLocation = manualLocation ?: systemLocationValue ?: return null
        if (root.optString("sourceProvider") != WatchWeatherProvider.CHINA.name ||
            !validChinaKey(configuredLocation.chinaLocationKey) ||
            root.optNullableString("sourceChinaLocationKey") != configuredLocation.chinaLocationKey
        ) return null
        val sourceLatitude = root.optDoubleOrNull("sourceLatitude") ?: return null
        val sourceLongitude = root.optDoubleOrNull("sourceLongitude") ?: return null
        if (
            abs(sourceLatitude - configuredLocation.latitude) > CACHE_LOCATION_TOLERANCE_DEGREES ||
            abs(sourceLongitude - configuredLocation.longitude) > CACHE_LOCATION_TOLERANCE_DEGREES
        ) return null
        val forecastJson = root.optJSONArray("forecast")
        val forecast = buildList {
            if (forecastJson != null) {
                for (index in 0 until forecastJson.length().coerceAtMost(6)) {
                    val item = forecastJson.optJSONObject(index) ?: continue
                    val offset = item.optIntOrNull("dayOffset")?.takeIf { it > 0 } ?: continue
                    add(
                        WatchWeatherForecast(
                            dayOffset = offset,
                            temperatureCelsius = item.optDoubleOrNull("temperatureCelsius"),
                            highCelsius = item.optDoubleOrNull("highCelsius"),
                            lowCelsius = item.optDoubleOrNull("lowCelsius"),
                            condition = item.optNullableString("condition"),
                            icon = item.optNullableString("icon"),
                        ),
                    )
                }
            }
        }
        val hourlyForecastJson = root.optJSONArray("hourlyForecast")
        val hourlyForecast = buildList {
            if (hourlyForecastJson != null) {
                for (index in 0 until hourlyForecastJson.length().coerceAtMost(12)) {
                    val item = hourlyForecastJson.optJSONObject(index) ?: continue
                    val offset = item.optIntOrNull("hourOffset")?.takeIf { it in 1..12 } ?: continue
                    add(
                        WatchWeatherHourlyForecast(
                            hourOffset = offset,
                            hourOfDay = item.optIntOrNull("hourOfDay")?.takeIf { it in 0..23 },
                            temperatureCelsius = item.optDoubleOrNull("temperatureCelsius"),
                            condition = item.optNullableString("condition"),
                            icon = item.optNullableString("icon"),
                        ),
                    )
                }
            }
        }
        val displayUnit = runCatching {
            WatchTemperatureUnit.valueOf(root.optString("displayUnit"))
        }.getOrDefault(WatchTemperatureUnit.CELSIUS)
        val snapshot = WatchWeatherSnapshot(
            temperatureCelsius = root.optDoubleOrNull("temperatureCelsius"),
            highCelsius = root.optDoubleOrNull("highCelsius"),
            lowCelsius = root.optDoubleOrNull("lowCelsius"),
            displayUnit = displayUnit,
            observedAtEpochMs = root.optLongOrNull("observedAtEpochMs"),
            lastUpdatedText = root.optNullableString("lastUpdatedText"),
            isDaytime = root.optBooleanOrNull("isDaytime"),
            sunriseText = root.optNullableString("sunriseText"),
            sunsetText = root.optNullableString("sunsetText"),
            condition = root.optNullableString("condition"),
            icon = root.optNullableString("icon"),
            humidity = root.optIntOrNull("humidity"),
            cloudCoverFraction = root.optDoubleOrNull("cloudCoverFraction"),
            location = configuredLocation.label,
            windSpeedMetersPerSecond = root.optDoubleOrNull("windSpeedMetersPerSecond"),
            windDirectionDegrees = root.optDoubleOrNull("windDirectionDegrees"),
            forecast = forecast,
            hourlyForecast = hourlyForecast,
        )
        return canonicalWatchWeatherSnapshot(snapshot) to receivedAt
    }

    private fun fetchChina(location: WatchWeatherLocation, nowEpochMs: Long): WatchWeatherSnapshot? {
        val key = location.chinaLocationKey ?: return null
        val url = "https://weatherapi.market.xiaomi.com/wtr-v3/weather/all" +
            "?latitude=${location.latitude}&longitude=${location.longitude}" +
            "&isLocated=false&locationKey=${URLEncoder.encode(key, Charsets.UTF_8.name())}" +
            "&days=15&appKey=weather20151024&sign=zUFJoAR2ZVrDy1vF3D07" +
            "&isGlobal=false&locale=zh_CN"
        val root = JSONObject(transport.get(url, USER_AGENT) ?: return null)
        val current = root.optJSONObject("current") ?: return null
        val daily = root.optJSONObject("forecastDaily")
        val temps = daily?.optJSONObject("temperature")?.optJSONArray("value")
        val weather = daily?.optJSONObject("weather")?.optJSONArray("value")
        val sun = daily?.optJSONObject("sunRiseSet")?.optJSONArray("value")
        val now = Instant.ofEpochMilli(nowEpochMs)
        val published = chinaWeatherInstant(current.optNullableString("pubTime"))
        val firstDay = (published ?: now).atZone(CHINA_TIME_ZONE).toLocalDate()
        val todayIndex = ChronoUnit.DAYS.between(firstDay, now.atZone(CHINA_TIME_ZONE).toLocalDate()).toInt()
        val todaySun = sun?.optJSONObject(todayIndex)
        val todayTemperature = temps?.optJSONObject(todayIndex)
        val temperature = current.optJSONObject("temperature")?.optDoubleOrNull("value")
        val isDaytime = chinaDaytime(now, todaySun)
        val meaning = weatherMeaningFromChinaCode(current.optString("weather").toIntOrNull(), isDaytime)
        val forecasts = (0 until minOf(15, maxOf(temps?.length() ?: 0, weather?.length() ?: 0))).mapNotNull { index ->
            val offset = index - todayIndex
            if (offset !in 1..6) return@mapNotNull null
            val pair = temps?.optJSONObject(index)
            val dailyMeaning = weatherMeaningFromChinaCode(weather?.optJSONObject(index)?.optString("from")?.toIntOrNull())
            val high = pair?.optDoubleOrNull("from")
            val low = pair?.optDoubleOrNull("to")
            if (high == null && low == null && dailyMeaning.condition == null) return@mapNotNull null
            WatchWeatherForecast(
                dayOffset = offset,
                highCelsius = high,
                lowCelsius = low,
                condition = dailyMeaning.condition,
                icon = dailyMeaning.icon,
            )
        }
        val hourly = root.optJSONObject("forecastHourly")
        val hourlyTemperature = hourly?.optJSONObject("temperature")
        val hourlyValues = hourlyTemperature?.optJSONArray("value")
        val hourlyWeather = hourly?.optJSONObject("weather")?.optJSONArray("value")
        val hourlyStart = (chinaWeatherInstant(hourlyTemperature?.optNullableString("pubTime")) ?: published)
            ?.truncatedTo(ChronoUnit.HOURS)
        val nowHour = now.truncatedTo(ChronoUnit.HOURS)
        val hourlyForecast = if (hourlyStart == null) emptyList() else {
            (0 until minOf(48, maxOf(hourlyValues?.length() ?: 0, hourlyWeather?.length() ?: 0))).mapNotNull { index ->
                val time = hourlyStart.plus(index.toLong(), ChronoUnit.HOURS)
                val offset = ChronoUnit.HOURS.between(nowHour, time).toInt()
                if (offset !in 1..12) return@mapNotNull null
                val hourlyTemp = hourlyValues?.optDoubleOrNull(index)
                val dayIndex = ChronoUnit.DAYS.between(firstDay, time.atZone(CHINA_TIME_ZONE).toLocalDate()).toInt()
                val hourlyMeaning = weatherMeaningFromChinaCode(
                    hourlyWeather?.optIntOrNull(index),
                    chinaDaytime(time, sun?.optJSONObject(dayIndex)),
                )
                if (hourlyTemp == null && hourlyMeaning.condition == null) return@mapNotNull null
                WatchWeatherHourlyForecast(
                    hourOffset = offset,
                    hourOfDay = time.atZone(CHINA_TIME_ZONE).hour,
                    temperatureCelsius = hourlyTemp,
                    condition = hourlyMeaning.condition,
                    icon = hourlyMeaning.icon,
                )
            }
        }
        val high = todayTemperature?.optDoubleOrNull("from")
        val low = todayTemperature?.optDoubleOrNull("to")
        val humidity = current.optJSONObject("humidity")?.optDoubleOrNull("value")?.roundToIntOrNull()
        val wind = current.optJSONObject("wind")
        val windSpeed = wind?.optJSONObject("speed")?.optDoubleOrNull("value")?.takeIf { it >= 0.0 }?.div(3.6)
        val windDirection = wind?.optJSONObject("direction")?.optDoubleOrNull("value")?.takeIf { it in 0.0..360.0 }
        if (temperature == null && meaning.condition == null && high == null && low == null && humidity == null &&
            windSpeed == null && windDirection == null && forecasts.isEmpty() && hourlyForecast.isEmpty()
        ) return null
        return WatchWeatherSnapshot(
            temperatureCelsius = temperature,
            highCelsius = high,
            lowCelsius = low,
            condition = meaning.condition,
            icon = meaning.icon,
            humidity = humidity,
            location = location.label,
            observedAtEpochMs = published?.toEpochMilli() ?: nowEpochMs,
            isDaytime = isDaytime,
            sunriseText = clockTimeFromIsoLocalTime(todaySun?.optNullableString("from")),
            sunsetText = clockTimeFromIsoLocalTime(todaySun?.optNullableString("to")),
            forecast = forecasts,
            hourlyForecast = hourlyForecast,
            windSpeedMetersPerSecond = windSpeed,
            windDirectionDegrees = windDirection,
        )
    }

    private data class WeatherMeaning(val condition: String?, val icon: String?)

    private fun weatherMeaningFromChinaCode(code: Int?, isDaytime: Boolean? = null): WeatherMeaning {
        val (condition, icon) = when (code) {
            0 -> "Clear" to "01"
            1 -> "Partly cloudy" to "02"
            2 -> "Overcast" to "04"
            3, in 7..12, 19, in 21..25 -> "Rain" to "10"
            4, 5 -> "Thunderstorm" to "11"
            6 -> "Sleet" to "13"
            in 13..17, in 26..28 -> "Snow" to "13"
            18 -> "Fog" to "50"
            20, in 29..31 -> "Dust" to "50"
            in 53..56 -> "Haze" to "50"
            else -> return WeatherMeaning(null, null)
        }
        return WeatherMeaning(condition, icon + if (isDaytime == false) "n" else "d")
    }

    private fun chinaDaytime(time: Instant, sun: JSONObject?): Boolean? {
        val sunrise = chinaWeatherInstant(sun?.optNullableString("from")) ?: return null
        val sunset = chinaWeatherInstant(sun?.optNullableString("to")) ?: return null
        if (sunset <= sunrise) return null
        return time >= sunrise && time < sunset
    }

    companion object {
        private val CHINA_TIME_ZONE = ZoneId.of("Asia/Shanghai")
        private const val LEGACY_FUSED_PROVIDER = "fused"
        const val PREFERENCES = "watch_weather_repository"
        const val KEY_LABEL = "manual_label"
        const val KEY_CITY = "manual_city"
        const val KEY_LATITUDE = "manual_latitude"
        const val KEY_LONGITUDE = "manual_longitude"
        const val KEY_SYSTEM_LOCATION_LABEL = "system_location_label"
        const val KEY_SYSTEM_LATITUDE = "system_latitude"
        const val KEY_SYSTEM_LONGITUDE = "system_longitude"
        const val KEY_SYSTEM_CHINA_LOCATION_KEY = "system_china_location_key"
        const val KEY_SYSTEM_RAW_LATITUDE = "system_raw_latitude"
        const val KEY_SYSTEM_RAW_LONGITUDE = "system_raw_longitude"
        const val KEY_SYSTEM_RESOLVED_ANCHOR_LATITUDE = "system_resolved_anchor_latitude"
        const val KEY_SYSTEM_RESOLVED_ANCHOR_LONGITUDE = "system_resolved_anchor_longitude"
        const val KEY_SYSTEM_LOCATION_IDENTITY_VERSION = "system_location_identity_version"
        private const val SYSTEM_LOCATION_IDENTITY_VERSION = 1
        /** {aalt} 的海拔，以及它是在哪个坐标上取到的。三个键必须一起读写。 */
        const val KEY_ELEVATION = "elevation_meters"
        const val KEY_ELEVATION_LATITUDE = "elevation_latitude"
        const val KEY_ELEVATION_LONGITUDE = "elevation_longitude"
        const val KEY_CACHE_RECEIVED_AT = "cache_received_at"
        const val KEY_CACHE_JSON = "cache_json"
        const val KEY_TEMPERATURE_UNIT = "temperature_unit"
        const val KEY_PROVIDER = "weather_provider"
        const val KEY_CHINA_LOCATION_KEY = "china_location_key"
        const val DEFAULT_SOFT_TTL_MS = 15L * 60L * 1_000L
        const val MAX_CACHE_AGE_MS = 6L * 60L * 60L * 1_000L
        const val USER_AGENT = "FlueNext/2.0 (weather; https://github.com/xumidcm/Flue-next)"
        private const val CURRENT_LOCATION_TIMEOUT_MS = 6_000L
        private const val SYSTEM_LOCATION_LABEL_TOLERANCE_DEGREES = 0.005
        private const val CACHE_LOCATION_TOLERANCE_DEGREES = 0.00001

        @Volatile
        private var defaultInstance: WatchWeatherRepository? = null

        fun default(context: Context): WatchWeatherRepository = synchronized(this) {
            defaultInstance ?: WatchWeatherRepository(context.applicationContext).also { defaultInstance = it }
        }
    }
}

private object DirectWeatherExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

internal fun normalizeWatchWeatherCityQuery(query: String): String = query
    .trim()
    .replace(Regex("\\s+"), " ")
    .removeSuffix("市")
    .removeSuffix("省")
    .removeSuffix("自治区")
    .trim()
    .take(120)

private fun validChinaKey(key: String?): Boolean = key?.matches(Regex("weathercn:[0-9]+")) == true

private fun containsCjk(value: String): Boolean = value.any { it in '\u4e00'..'\u9fff' }

internal fun parseChinaWatchWeatherLocationSearch(
    payload: String,
    fallbackLabel: String,
    limit: Int,
): List<WatchWeatherLocation> {
    val results = runCatching { JSONArray(payload) }.getOrNull() ?: return emptyList()
    val seen = HashSet<String>()
    return buildList {
        // Validate each candidate before deduplicating; malformed results cannot hide a valid city.
        for (index in 0 until minOf(results.length(), 50)) {
            val result = results.optJSONObject(index) ?: continue
            if (result.has("status") && result.optInt("status", -1) != 0) continue
            val key = result.optNullableString("locationKey")?.takeIf(::validChinaKey) ?: continue
            val latitude = result.optDoubleOrNull("latitude") ?: continue
            val longitude = result.optDoubleOrNull("longitude") ?: continue
            val name = result.optNullableString("name") ?: fallbackLabel
            val affiliation = result.optNullableString("affiliation")
            val label = listOfNotNull(name, affiliation).filter(String::isNotBlank).joinToString(", ")
            if (label.isBlank()) continue
            val location = runCatching {
                WatchWeatherLocation(latitude, longitude, label, chinaLocationKey = key)
            }.getOrNull() ?: continue
            if (seen.add(key)) add(location)
        }
    }.sortedWith(
        compareByDescending<WatchWeatherLocation> { locationSearchScore(it, fallbackLabel) }
            .thenByDescending { it.label.orEmpty().count { character -> character == ',' } }
            .thenBy { it.label.orEmpty() },
    ).take(limit.coerceAtLeast(0))
}

private fun locationSearchScore(location: WatchWeatherLocation, query: String): Int {
    val label = location.label.orEmpty()
    var score = 0
    if (label.contains(query, ignoreCase = true)) score += 100
    // Prefer a fully-qualified administrative result over a bare provider name.
    score += label.count { it == ',' } * 5
    if (containsCjk(query) && label.any { it in '\u4e00'..'\u9fff' }) score += 10
    return score
}

private object DefaultWatchWeatherHttpTransport : WatchWeatherHttpTransport {
    private const val MAX_RESPONSE_BYTES = 1_500_000

    override fun get(url: String, userAgent: String): String? {
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.connectTimeout = 2_500
            connection.readTimeout = 3_500
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", userAgent)
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) return null
            connection.inputStream.use { input ->
                readBoundedWeatherUtf8(input, MAX_RESPONSE_BYTES)
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Reads at most [maxBytes]; oversized or invalid limits fail without buffering the rest. */
internal fun readBoundedWeatherUtf8(input: InputStream, maxBytes: Int): String? {
    if (maxBytes <= 0) return null
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    optDouble(key, Double.NaN).takeIf(Double::isFinite)

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).trim().takeIf(String::isNotEmpty) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optString(key).toIntOrNull() else null

private fun JSONArray.optDoubleOrNull(index: Int): Double? =
    optDouble(index, Double.NaN).takeIf(Double::isFinite)

private fun JSONArray.optIntOrNull(index: Int): Int? =
    if (index in 0 until length() && !isNull(index)) optString(index).toIntOrNull() else null

private fun Double.roundToIntOrNull(): Int? =
    takeIf(Double::isFinite)?.let { kotlin.math.round(it).toInt().coerceIn(0, 100) }

private fun chinaWeatherInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(raw).atZone(ZoneId.of("Asia/Shanghai")).toInstant() }.getOrNull()
}

private fun clockTimeFromIsoLocalTime(raw: String?): String? =
    raw?.let { Regex("T(\\d{2}:\\d{2})").find(it)?.groupValues?.getOrNull(1) }

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}
