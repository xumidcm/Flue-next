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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** A location input used by the weather repository; no permission is requested here. */
data class WatchWeatherLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
    /**
     * {aalt} 的海拔（米）。
     *
     * 不是传感器读数，也不是 `Location.getAltitude()`（手表大多没有气压计，那个值常年是
     * 0）—— 而是 Open-Meteo 响应顶层就带的 `elevation`，即该坐标处地形的海拔。天气请求
     * 本来就在打那个接口，顺手取回来即可，不额外发一次网络。
     *
     * null 表示还没刷过天气；渲染侧会按上游口径当 0 处理。
     */
    val elevation: Double? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude out of range" }
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
 * The default order is met.no compact forecast, then Open-Meteo (no API key).
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
    private val refreshInFlight = AtomicBoolean(false)
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
    private var systemLocationLabelValue: String? = systemLocationValue?.label

    @Volatile
    private var temperatureUnitValue: WatchTemperatureUnit = readTemperatureUnit()

    init {
        loadPersistedSnapshot()?.let { (snapshot, receivedAtEpochMs) ->
            cache.update(snapshot, receivedAtEpochMs)
        }
    }

    override fun snapshot(nowEpochMs: Long): WatchWeatherSnapshot? {
        val cached = cache.snapshot(nowEpochMs)
        val shouldRefresh = cached == null ||
            lastRefreshAttemptMs == Long.MIN_VALUE ||
            (nowEpochMs >= lastRefreshAttemptMs &&
                nowEpochMs - lastRefreshAttemptMs >= softTtlMs)
        if (shouldRefresh && nowEpochMs >= lastRefreshAttemptMs) {
            scheduleRefresh(nowEpochMs)
        }
        // Temperature values stay canonical Celsius in the cache; only the
        // presentation unit follows the user's app-wide preference.
        return cached?.copy(displayUnit = temperatureUnitValue)
    }

    /** Returns the persisted unit used by standard/JB/editor weather tags. */
    fun temperatureUnit(): WatchTemperatureUnit = temperatureUnitValue

    /** Persists a display preference without refetching or rewriting weather data. */
    fun setTemperatureUnit(unit: WatchTemperatureUnit) {
        temperatureUnitValue = unit
        preferences.edit().putString(KEY_TEMPERATURE_UNIT, unit.symbol).apply()
    }

    /** String boundary for settings/preferences that store `C`, `F`, or `°F`. */
    fun setTemperatureUnitRaw(raw: String?) {
        setTemperatureUnit(WatchTemperatureUnit.fromRaw(raw))
    }

    /** Persists a user-selected city/coordinate fallback without requesting location permission. */
    fun setManualLocation(location: WatchWeatherLocation?) {
        manualLocation = location
        manualCityValue = null
        systemLocationValue = null
        systemLocationLabelValue = null
        val editor = preferences.edit()
        if (location == null) {
            editor.remove(KEY_LABEL).remove(KEY_LATITUDE).remove(KEY_LONGITUDE).remove(KEY_CITY)
        } else {
            editor.putString(KEY_LABEL, location.label)
                .putString(KEY_LATITUDE, location.latitude.toString())
                .putString(KEY_LONGITUDE, location.longitude.toString())
                .remove(KEY_CITY)
        }
        editor.remove(KEY_SYSTEM_LOCATION_LABEL)
            .remove(KEY_SYSTEM_LATITUDE)
            .remove(KEY_SYSTEM_LONGITUDE)
        editor.apply()
        invalidateWeatherForLocationChange()
        requestRefresh()
    }

    /**
     * Sets a manual city name. It is resolved through Open-Meteo's no-key
     * geocoding endpoint on the weather worker, then the coordinates and
     * resolved label are cached in preferences.
     */
    fun setManualCity(city: String?) {
        val normalized = city?.trim()?.takeIf(String::isNotEmpty)?.take(120)
        manualCityValue = normalized
        manualLocation = null
        systemLocationValue = null
        systemLocationLabelValue = null
        preferences.edit()
            .remove(KEY_LABEL)
            .remove(KEY_LATITUDE)
            .remove(KEY_LONGITUDE)
            .apply {
                if (normalized == null) remove(KEY_CITY) else putString(KEY_CITY, normalized)
            }
            .remove(KEY_SYSTEM_LOCATION_LABEL)
            .remove(KEY_SYSTEM_LATITUDE)
            .remove(KEY_SYSTEM_LONGITUDE)
            .apply()
        invalidateWeatherForLocationChange()
        requestRefresh()
    }

    fun manualCity(): String? = manualCityValue

    /** Label shown in settings for either a selected candidate or system location. */
    fun activeLocationLabel(): String? =
        manualLocation?.label ?: manualCityValue ?: systemLocationLabelValue

    fun isUsingSystemLocation(): Boolean = manualLocation == null && manualCityValue == null

    /**
     * 当前生效的坐标（手动选点优先，否则用已缓存的系统定位）。
     *
     * 给 `{alat}` / `{alon}` / `{aalt}` 一族用：那几个变量与天气**共用同一份已授权的
     * 定位**，绝不为它们再单独申请一次位置权限，也不在这里发起新的定位请求 —— 只读
     * 已经落盘的那份。授权引导由 UI 层走 [WatchLocationPermissionPolicy]。
     *
     * 返回 null 时渲染侧按上游口径给 0（clox 的 `WF.location.latitude or 0`），
     * 不再显示 `--`。
     */
    internal fun activeLocation(): WatchWeatherLocation? {
        val base = manualLocation ?: readSystemLocation() ?: return null
        if (base.elevation != null) return base
        return base.copy(elevation = persistedElevationFor(base))
    }

    /**
     * 落盘的海拔，且**只在坐标对得上时才认**。
     *
     * 海拔是「某一个坐标处的地形高度」，换了城市就是另一个数。所以连它所属的坐标一起
     * 存、读的时候比一遍 —— 与 [loadPersistedSnapshot] 校验 `sourceLatitude` 同一个
     * 道理：宁可让 {aalt} 显示 0，也不能把上海的海拔挂在拉萨的坐标上。
     *
     * 坐标变了不必主动清理：这里比对一次就自然失配，而下一次天气刷新（软 TTL 15 分钟，
     * 且换地点会立刻 requestRefresh）就会写入新坐标的海拔。
     */
    private fun persistedElevationFor(location: WatchWeatherLocation): Double? {
        val elevation = preferences.getString(KEY_ELEVATION, null)
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?: return null
        val latitude = preferences.getString(KEY_ELEVATION_LATITUDE, null)?.toDoubleOrNull()
            ?: return null
        val longitude = preferences.getString(KEY_ELEVATION_LONGITUDE, null)?.toDoubleOrNull()
            ?: return null
        if (
            abs(latitude - location.latitude) > ELEVATION_LOCATION_TOLERANCE_DEGREES ||
            abs(longitude - location.longitude) > ELEVATION_LOCATION_TOLERANCE_DEGREES
        ) return null
        return elevation
    }

    /** 把 Open-Meteo 那个 `elevation` 连同它所属的坐标一起写下来。 */
    private fun persistElevation(location: WatchWeatherLocation, elevation: Double?) {
        val editor = preferences.edit()
        if (elevation == null || !elevation.isFinite()) {
            editor.remove(KEY_ELEVATION)
                .remove(KEY_ELEVATION_LATITUDE)
                .remove(KEY_ELEVATION_LONGITUDE)
        } else {
            editor.putString(KEY_ELEVATION, elevation.toString())
                .putString(KEY_ELEVATION_LATITUDE, location.latitude.toString())
                .putString(KEY_ELEVATION_LONGITUDE, location.longitude.toString())
        }
        editor.apply()
    }

    /**
     * Resolves multiple forward-geocoding candidates. This method performs IO;
     * callers must invoke it off the main thread and persist only a chosen item.
     */
    fun searchLocations(query: String, limit: Int = 8): List<WatchWeatherLocation> {
        val normalized = query.trim().take(120)
        if (normalized.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 10)
        val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
        val language = Locale.getDefault().language.takeIf(String::isNotBlank) ?: "en"
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=$encoded&count=$safeLimit&language=$language&format=json"
        val response = transport.get(url, USER_AGENT) ?: return emptyList()
        return parseWatchWeatherLocationSearch(response, normalized, safeLimit)
    }

    /** Requests a fresh coarse location, then falls back to the newest cached fix. */
    fun resolveSystemLocation(): WatchWeatherLocation? {
        val raw = locationSource?.current()
            ?: readCurrentLocation()
            ?: readLastKnownLocation()
            ?: systemLocationValue
            ?: return null
        if (!raw.label.isNullOrBlank()) return raw
        val label = matchingPersistedSystemLabel(raw) ?: reverseGeocodeLabel(raw)
        return raw.copy(label = label)
    }

    /** Switches the repository to system location after UI-side resolution succeeds. */
    fun useSystemLocation(location: WatchWeatherLocation) {
        manualLocation = null
        manualCityValue = null
        val normalized = location.copy(
            label = location.label?.trim()?.takeIf(String::isNotEmpty),
        )
        systemLocationValue = normalized
        systemLocationLabelValue = normalized.label
        preferences.edit()
            .remove(KEY_LABEL)
            .remove(KEY_LATITUDE)
            .remove(KEY_LONGITUDE)
            .remove(KEY_CITY)
            .apply {
                val label = systemLocationLabelValue
                if (label == null) remove(KEY_SYSTEM_LOCATION_LABEL)
                else putString(KEY_SYSTEM_LOCATION_LABEL, label)
            }
            .putString(KEY_SYSTEM_LATITUDE, normalized.latitude.toString())
            .putString(KEY_SYSTEM_LONGITUDE, normalized.longitude.toString())
            .apply()
        invalidateWeatherForLocationChange()
        requestRefresh()
    }

    fun clearCache() {
        cache.clear()
        preferences.edit().remove(KEY_CACHE_JSON).remove(KEY_CACHE_RECEIVED_AT).apply()
    }

    private fun invalidateWeatherForLocationChange() {
        synchronized(locationCacheLock) {
            locationRevision++
            clearCache()
        }
    }

    /** Forces the next cache read to schedule a foreground/background refresh. */
    fun requestRefresh(nowEpochMs: Long = System.currentTimeMillis()) {
        lastRefreshAttemptMs = Long.MIN_VALUE
        scheduleRefresh(nowEpochMs)
    }

    private fun scheduleRefresh(nowEpochMs: Long) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        val scheduledRevision = synchronized(locationCacheLock) { locationRevision }
        lastRefreshAttemptMs = nowEpochMs
        refreshExecutor.execute {
            try {
                val city = manualCityValue
                val location = manualLocation
                    ?: if (city != null) geocodeManualCity(city) else {
                        val raw = locationSource?.current()
                            ?: readCurrentLocation()
                            ?: readLastKnownLocation()
                            ?: systemLocationValue
                        raw?.let(::refreshSystemLocationIdentity)
                    }
                if (location == null) return@execute
                val met = runCatching { fetchMetNo(location, nowEpochMs) }.getOrNull()
                // met.no is authoritative for the current observation. Its
                // compact endpoint has no daily extrema, so enrich it with
                // Open-Meteo daily data when available; Open-Meteo remains a
                // complete fallback when met.no is unavailable.
                val open = runCatching { fetchOpenMeteo(location, nowEpochMs) }.getOrNull()
                val refreshed = when {
                    met == null -> open
                    open == null -> met
                    else -> met.copy(
                        temperatureCelsius = met.temperatureCelsius ?: open.temperatureCelsius,
                        highCelsius = met.highCelsius ?: open.highCelsius,
                        lowCelsius = met.lowCelsius ?: open.lowCelsius,
                        forecast = if (met.forecast.isEmpty()) open.forecast else met.forecast,
                        hourlyForecast = if (met.hourlyForecast.isEmpty()) {
                            open.hourlyForecast
                        } else {
                            met.hourlyForecast
                        },
                        condition = met.condition ?: open.condition,
                        icon = met.icon ?: open.icon,
                        humidity = met.humidity ?: open.humidity,
                        cloudCoverFraction = met.cloudCoverFraction ?: open.cloudCoverFraction,
                        location = met.location ?: open.location,
                        isDaytime = met.isDaytime ?: open.isDaytime,
                    )
                }
                refreshed?.let { snapshot ->
                    synchronized(locationCacheLock) {
                        if (scheduledRevision != locationRevision) return@synchronized
                        val canonical = canonicalWatchWeatherSnapshot(snapshot).copy(
                            displayUnit = temperatureUnitValue,
                        )
                        cache.update(canonical, nowEpochMs)
                        persistSnapshot(canonical, nowEpochMs, location)
                    }
                }
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

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

    private fun readManualLocation(): WatchWeatherLocation? {
        val latitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return runCatching {
            WatchWeatherLocation(latitude, longitude, preferences.getString(KEY_LABEL, null))
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
        return runCatching { WatchWeatherLocation(latitude, longitude, label) }.getOrNull()
    }

    private fun matchingPersistedSystemLabel(location: WatchWeatherLocation): String? {
        val persisted = systemLocationValue ?: return null
        if (
            abs(persisted.latitude - location.latitude) > SYSTEM_LOCATION_LABEL_TOLERANCE_DEGREES ||
            abs(persisted.longitude - location.longitude) > SYSTEM_LOCATION_LABEL_TOLERANCE_DEGREES
        ) return null
        return persisted.label?.trim()?.takeIf(String::isNotEmpty)
    }

    /** Keeps a city label tied to the coordinates it was actually resolved for. */
    private fun refreshSystemLocationIdentity(raw: WatchWeatherLocation): WatchWeatherLocation {
        val normalized = if (!raw.label.isNullOrBlank()) {
            raw.copy(label = raw.label.trim())
        } else {
            raw.copy(label = matchingPersistedSystemLabel(raw) ?: reverseGeocodeLabel(raw))
        }
        systemLocationValue = normalized
        systemLocationLabelValue = normalized.label
        preferences.edit()
            .putString(KEY_SYSTEM_LATITUDE, normalized.latitude.toString())
            .putString(KEY_SYSTEM_LONGITUDE, normalized.longitude.toString())
            .apply {
                val label = normalized.label
                if (label.isNullOrBlank()) remove(KEY_SYSTEM_LOCATION_LABEL)
                else putString(KEY_SYSTEM_LOCATION_LABEL, label)
            }
            .apply()
        return normalized
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
            put("sourceLatitude", sourceLocation.latitude)
            put("sourceLongitude", sourceLocation.longitude)
            putNullable("temperatureCelsius", snapshot.temperatureCelsius)
            putNullable("highCelsius", snapshot.highCelsius)
            putNullable("lowCelsius", snapshot.lowCelsius)
            put("displayUnit", snapshot.displayUnit.name)
            putNullable("observedAtEpochMs", snapshot.observedAtEpochMs)
            putNullable("lastUpdatedText", snapshot.lastUpdatedText)
            putNullable("isDaytime", snapshot.isDaytime)
            putNullable("condition", snapshot.condition)
            putNullable("icon", snapshot.icon)
            putNullable("humidity", snapshot.humidity)
            putNullable("cloudCoverFraction", snapshot.cloudCoverFraction)
            putNullable("location", snapshot.location)
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
            condition = root.optNullableString("condition"),
            icon = root.optNullableString("icon"),
            humidity = root.optIntOrNull("humidity"),
            cloudCoverFraction = root.optDoubleOrNull("cloudCoverFraction"),
            location = root.optNullableString("location"),
            forecast = forecast,
            hourlyForecast = hourlyForecast,
        )
        return canonicalWatchWeatherSnapshot(snapshot) to receivedAt
    }

    private fun geocodeManualCity(city: String): WatchWeatherLocation? {
        val resolved = searchLocations(city, limit = 1).firstOrNull() ?: return null
        manualLocation = resolved
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_LABEL, resolved.label)
            .putString(KEY_LATITUDE, resolved.latitude.toString())
            .putString(KEY_LONGITUDE, resolved.longitude.toString())
            .apply()
        return resolved
    }

    private fun fetchMetNo(location: WatchWeatherLocation, nowEpochMs: Long): WatchWeatherSnapshot? {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact" +
            "?lat=${"%.5f".format(Locale.US, location.latitude)}" +
            "&lon=${"%.5f".format(Locale.US, location.longitude)}"
        val root = JSONObject(transport.get(url, USER_AGENT) ?: return null)
        val series = root.optJSONObject("properties")?.optJSONArray("timeseries") ?: return null
        val first = series.optJSONObject(0) ?: return null
        val details = first.optJSONObject("data")
            ?.optJSONObject("instant")
            ?.optJSONObject("details") ?: return null
        val symbol = first.optJSONObject("data")
            ?.optJSONObject("next_1_hours")
            ?.optJSONObject("summary")
            ?.optString("symbol_code")
            ?.takeIf(String::isNotBlank)
        val meaning = weatherMeaningFromMetSymbol(symbol)
        return WatchWeatherSnapshot(
            temperatureCelsius = details.optDoubleOrNull("air_temperature"),
            condition = meaning.condition,
            icon = meaning.icon,
            humidity = details.optDoubleOrNull("relative_humidity")?.roundToIntOrNull(),
            cloudCoverFraction = details.optDoubleOrNull("cloud_area_fraction")
                ?.div(100.0)
                ?.coerceIn(0.0, 1.0),
            location = location.label,
            observedAtEpochMs = nowEpochMs,
            isDaytime = meaning.isDaytime,
        )
    }

    private fun fetchOpenMeteo(location: WatchWeatherLocation, nowEpochMs: Long): WatchWeatherSnapshot? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${"%.5f".format(Locale.US, location.latitude)}" +
            "&longitude=${"%.5f".format(Locale.US, location.longitude)}" +
            "&current=temperature_2m,relative_humidity_2m,cloud_cover,weather_code,is_day" +
            ",wind_speed_10m,wind_direction_10m" +
            // 直接要 m/s，免得在渲染侧再猜一遍单位（Open-Meteo 默认是 km/h）。
            "&wind_speed_unit=ms" +
            "&daily=temperature_2m_mean,temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset" +
            "&hourly=temperature_2m,weather_code,is_day&forecast_hours=13" +
            "&forecast_days=6&timezone=auto"
        val root = JSONObject(transport.get(url, USER_AGENT) ?: return null)
        // {aalt} 的数据源。Open-Meteo 在响应**顶层**给该坐标处地形的海拔（米）——
        // 之前没解析，于是 `altitudeMeters` 全代码库没有任何赋值点，{aalt} 永远读不出来。
        // 不用 `Location.getAltitude()`：手表大多没有气压计，那个值常年是 0。
        persistElevation(location, root.optDoubleOrNull("elevation"))
        val current = root.optJSONObject("current") ?: root.optJSONObject("current_weather") ?: return null
        val currentCode = current.optIntOrNull("weather_code")
            ?: current.optIntOrNull("weathercode")
        val isDaytime = current.optIntOrNull("is_day")?.let { it != 0 }
        val currentMeaning = weatherMeaningFromOpenMeteoCode(currentCode, isDaytime)
        val daily = root.optJSONObject("daily")
        val means = daily?.optJSONArray("temperature_2m_mean")
        val highs = daily?.optJSONArray("temperature_2m_max")
        val lows = daily?.optJSONArray("temperature_2m_min")
        val codes = daily?.optJSONArray("weather_code")
        val forecasts = if (means != null || highs != null || lows != null || codes != null) {
            (0 until listOf(
                means?.length() ?: 0,
                highs?.length() ?: 0,
                lows?.length() ?: 0,
                codes?.length() ?: 0,
            ).maxOrNull()!!.coerceAtMost(6))
                .map { index ->
                    val meaning = weatherMeaningFromOpenMeteoCode(codes?.optIntOrNull(index), true)
                    WatchWeatherForecast(
                        dayOffset = index + 1,
                        temperatureCelsius = means?.optDoubleOrNull(index),
                        highCelsius = highs?.optDoubleOrNull(index),
                        lowCelsius = lows?.optDoubleOrNull(index),
                        condition = meaning.condition,
                        icon = meaning.icon,
                    )
                }
        } else {
            emptyList()
        }
        val hourly = root.optJSONObject("hourly")
        val hourlyTimes = hourly?.optJSONArray("time")
        val hourlyTemperatures = hourly?.optJSONArray("temperature_2m")
        val hourlyCodes = hourly?.optJSONArray("weather_code")
        val hourlyDayFlags = hourly?.optJSONArray("is_day")
        val hourlyCount = listOf(
            hourlyTimes?.length() ?: 0,
            hourlyTemperatures?.length() ?: 0,
            hourlyCodes?.length() ?: 0,
            hourlyDayFlags?.length() ?: 0,
        ).maxOrNull() ?: 0
        // Open-Meteo returns index 0 for the current hour when forecast_hours
        // is used. WatchMaker `{wf1h*}` starts at the next hour.
        val hourlyForecasts = (1 until hourlyCount.coerceAtMost(13)).mapNotNull { index ->
            val temperature = hourlyTemperatures?.optDoubleOrNull(index)
            val code = hourlyCodes?.optIntOrNull(index)
            val day = hourlyDayFlags?.optIntOrNull(index)?.let { it != 0 }
            val hourOfDay = hourlyTimes?.optString(index)
                ?.takeIf(String::isNotBlank)
                ?.let(::hourOfDayFromIsoLocalTime)
            if (temperature == null && code == null && hourOfDay == null) return@mapNotNull null
            val meaning = weatherMeaningFromOpenMeteoCode(code, day)
            WatchWeatherHourlyForecast(
                hourOffset = index,
                hourOfDay = hourOfDay,
                temperatureCelsius = temperature,
                condition = meaning.condition,
                icon = meaning.icon,
            )
        }
        return WatchWeatherSnapshot(
            temperatureCelsius = current.optDoubleOrNull("temperature_2m")
                ?: current.optDoubleOrNull("temperature"),
            highCelsius = highs?.optDoubleOrNull(0),
            lowCelsius = lows?.optDoubleOrNull(0),
            condition = currentMeaning.condition,
            icon = currentMeaning.icon,
            humidity = current.optDoubleOrNull("relative_humidity_2m")?.roundToIntOrNull(),
            cloudCoverFraction = current.optDoubleOrNull("cloud_cover")
                ?.div(100.0)
                ?.coerceIn(0.0, 1.0),
            location = location.label,
            observedAtEpochMs = nowEpochMs,
            isDaytime = isDaytime,
            // URL 里一直在请求 sunrise/sunset，之前只是没解析，{wsr}/{wss} 因此
            // 永远渲染成 "--"。索引 0 是今天。
            sunriseText = clockTimeFromIsoLocalTime(daily?.optJSONArray("sunrise")?.optString(0)),
            sunsetText = clockTimeFromIsoLocalTime(daily?.optJSONArray("sunset")?.optString(0)),
            forecast = forecasts,
            hourlyForecast = hourlyForecasts,
            // {wws} / {wwd} / {wwdb} / {wwdbb}。风速已按 wind_speed_unit=ms 拿到 m/s。
            windSpeedMetersPerSecond = current.optDoubleOrNull("wind_speed_10m"),
            windDirectionDegrees = current.optDoubleOrNull("wind_direction_10m"),
        )
    }

    private data class WeatherMeaning(
        val condition: String?,
        val icon: String?,
        val isDaytime: Boolean? = null,
    )

    private fun weatherMeaningFromOpenMeteoCode(code: Int?, isDaytime: Boolean?): WeatherMeaning {
        val day = isDaytime != false
        val (condition, iconNumber) = when (code) {
            0 -> "Clear" to 1
            1, 2 -> "Partly cloudy" to 2
            3 -> "Overcast" to 4
            45, 48 -> "Fog" to 50
            51, 53, 55, 56, 57 -> "Drizzle" to 9
            61, 63, 65, 66, 67, 80, 81, 82 -> "Rain" to 10
            71, 73, 75, 77, 85, 86 -> "Snow" to 13
            95, 96, 99 -> "Thunderstorm" to 11
            else -> null to null
        }
        val icon = iconNumber?.toString()?.padStart(2, '0')?.plus(if (day) "d" else "n")
        return WeatherMeaning(condition, icon, isDaytime)
    }

    private fun weatherMeaningFromMetSymbol(symbol: String?): WeatherMeaning {
        val normalized = symbol?.lowercase(Locale.ROOT).orEmpty()
        val day = !normalized.endsWith("_night") && !normalized.endsWith("_polartwilight")
        val (condition, iconNumber) = when {
            normalized.startsWith("clearsky") -> "Clear" to 1
            normalized.startsWith("fair") || normalized.startsWith("partlycloudy") -> "Partly cloudy" to 2
            normalized.startsWith("cloudy") -> "Overcast" to 4
            normalized.startsWith("fog") -> "Fog" to 50
            normalized.startsWith("lightrain") || normalized.startsWith("rain") || normalized.startsWith("heavyrain") -> "Rain" to 10
            normalized.startsWith("lightsnow") || normalized.startsWith("snow") || normalized.startsWith("heavysnow") -> "Snow" to 13
            normalized.startsWith("thunder") -> "Thunderstorm" to 11
            else -> null to null
        }
        val icon = iconNumber?.toString()?.padStart(2, '0')?.plus(if (day) "d" else "n")
        return WeatherMeaning(condition, icon, day)
    }

    companion object {
        private const val LEGACY_FUSED_PROVIDER = "fused"
        const val PREFERENCES = "watch_weather_repository"
        const val KEY_LABEL = "manual_label"
        const val KEY_CITY = "manual_city"
        const val KEY_LATITUDE = "manual_latitude"
        const val KEY_LONGITUDE = "manual_longitude"
        const val KEY_SYSTEM_LOCATION_LABEL = "system_location_label"
        const val KEY_SYSTEM_LATITUDE = "system_latitude"
        const val KEY_SYSTEM_LONGITUDE = "system_longitude"
        /** {aalt} 的海拔，以及它是在哪个坐标上取到的。三个键必须一起读写。 */
        const val KEY_ELEVATION = "elevation_meters"
        const val KEY_ELEVATION_LATITUDE = "elevation_latitude"
        const val KEY_ELEVATION_LONGITUDE = "elevation_longitude"
        const val KEY_CACHE_RECEIVED_AT = "cache_received_at"
        const val KEY_CACHE_JSON = "cache_json"
        const val KEY_TEMPERATURE_UNIT = "temperature_unit"
        const val DEFAULT_SOFT_TTL_MS = 15L * 60L * 1_000L
        const val MAX_CACHE_AGE_MS = 6L * 60L * 60L * 1_000L
        const val USER_AGENT = "FlueNext/2.0 (weather; https://github.com/xumidcm/Flue)"
        private const val CURRENT_LOCATION_TIMEOUT_MS = 6_000L
        private const val SYSTEM_LOCATION_LABEL_TOLERANCE_DEGREES = 0.005
        private const val CACHE_LOCATION_TOLERANCE_DEGREES = 0.00001

        /**
         * 海拔与坐标的绑定容差，约 1 km。
         *
         * 比缓存天气的容差（[CACHE_LOCATION_TOLERANCE_DEGREES]，几乎逐位相等）宽得多，
         * 因为地形海拔在一公里内基本不变，而每次微小漂移都丢掉海拔只会让 {aalt} 一直
         * 闪回 0。真正换了城市远超这个量级，一比就失配。
         */
        private const val ELEVATION_LOCATION_TOLERANCE_DEGREES = 0.01

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

internal fun parseWatchWeatherLocationSearch(
    payload: String,
    fallbackLabel: String,
    limit: Int,
): List<WatchWeatherLocation> {
    val results = runCatching { JSONObject(payload).optJSONArray("results") }.getOrNull()
        ?: return emptyList()
    val parsed = ArrayList<WatchWeatherLocation>(limit.coerceAtLeast(0))
    val seen = HashSet<Pair<Double, Double>>()
    for (index in 0 until minOf(results.length(), limit.coerceAtLeast(0))) {
        val result = results.optJSONObject(index) ?: continue
        val latitude = result.optDoubleOrNull("latitude") ?: continue
        val longitude = result.optDoubleOrNull("longitude") ?: continue
        if (!seen.add(latitude to longitude)) continue
        val label = listOf(
            result.optString("name").takeIf(String::isNotBlank),
            result.optString("admin1").takeIf(String::isNotBlank),
            result.optString("country").takeIf(String::isNotBlank),
        ).filterNotNull().distinct().joinToString(", ").ifBlank { fallbackLabel }
        runCatching { WatchWeatherLocation(latitude, longitude, label) }
            .getOrNull()
            // geocoding 的每条结果也带 `elevation`，顺手收下 —— 手动选城市的用户不用等到
            // 下一次天气刷新才有 {aalt}。缺这个字段（老响应）时仍是 null，按 0 渲染。
            ?.let { parsedLocation ->
                parsedLocation.copy(elevation = result.optDoubleOrNull("elevation"))
            }
            ?.let(parsed::add)
    }
    return parsed
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
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONArray.optDoubleOrNull(index: Int): Double? =
    optDouble(index, Double.NaN).takeIf(Double::isFinite)

private fun JSONArray.optIntOrNull(index: Int): Int? =
    if (index in 0 until length() && !isNull(index)) optInt(index) else null

private fun Double.roundToIntOrNull(): Int? =
    takeIf(Double::isFinite)?.let { kotlin.math.round(it).toInt().coerceIn(0, 100) }

private fun hourOfDayFromIsoLocalTime(raw: String): Int? =
    Regex("T(\\d{2}):").find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it in 0..23 }

/**
 * 取 ISO 本地时间串的 `HH:mm` 部分，供 `{wsr}` / `{wss}` 直接当字符串渲染。
 *
 * 与 clox `WeatherProvider.kt:368` 的 `parseIsoTime` 同语义。Open-Meteo 在
 * `timezone=auto` 下返回的已经是当地时间（`2026-08-02T05:41`），不需要再换算时区。
 */
private fun clockTimeFromIsoLocalTime(raw: String?): String? =
    raw?.let { Regex("T(\\d{2}:\\d{2})").find(it)?.groupValues?.getOrNull(1) }

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}
