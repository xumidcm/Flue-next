package com.flue.launcher.watchface.standard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A location input used by the weather repository; no permission is requested here. */
data class WatchWeatherLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude out of range" }
    }
}

fun interface WatchWeatherLocationSource {
    fun current(): WatchWeatherLocation?
}

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

    @Volatile
    private var lastRefreshAttemptMs = Long.MIN_VALUE

    @Volatile
    private var manualLocation: WatchWeatherLocation? = readManualLocation()

    @Volatile
    private var manualCityValue: String? = readManualCity()

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
        val editor = preferences.edit()
        if (location == null) {
            editor.remove(KEY_LABEL).remove(KEY_LATITUDE).remove(KEY_LONGITUDE).remove(KEY_CITY)
        } else {
            editor.putString(KEY_LABEL, location.label)
                .putString(KEY_LATITUDE, location.latitude.toString())
                .putString(KEY_LONGITUDE, location.longitude.toString())
                .remove(KEY_CITY)
        }
        editor.apply()
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
        preferences.edit()
            .remove(KEY_LABEL)
            .remove(KEY_LATITUDE)
            .remove(KEY_LONGITUDE)
            .apply {
                if (normalized == null) remove(KEY_CITY) else putString(KEY_CITY, normalized)
            }
            .apply()
        requestRefresh()
    }

    fun manualCity(): String? = manualCityValue

    fun clearCache() {
        cache.clear()
        preferences.edit().remove(KEY_CACHE_JSON).remove(KEY_CACHE_RECEIVED_AT).apply()
    }

    /** Forces the next cache read to schedule a foreground/background refresh. */
    fun requestRefresh(nowEpochMs: Long = System.currentTimeMillis()) {
        lastRefreshAttemptMs = Long.MIN_VALUE
        scheduleRefresh(nowEpochMs)
    }

    private fun scheduleRefresh(nowEpochMs: Long) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        lastRefreshAttemptMs = nowEpochMs
        refreshExecutor.execute {
            try {
                val city = manualCityValue
                val location = manualLocation
                    ?: if (city != null) geocodeManualCity(city) else {
                        locationSource?.current() ?: readLastKnownLocation()
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
                    val canonical = canonicalWatchWeatherSnapshot(snapshot).copy(
                        displayUnit = temperatureUnitValue,
                    )
                    cache.update(canonical, nowEpochMs)
                    persistSnapshot(canonical, nowEpochMs)
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
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        return providers.asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { location -> location.latitude.isFinite() && location.longitude.isFinite() }
            .maxByOrNull { it.time }
            ?.let { location -> WatchWeatherLocation(location.latitude, location.longitude) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun readManualLocation(): WatchWeatherLocation? {
        val latitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return runCatching {
            WatchWeatherLocation(latitude, longitude, preferences.getString(KEY_LABEL, null))
        }.getOrNull()
    }

    private fun readManualCity(): String? =
        preferences.getString(KEY_CITY, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun readTemperatureUnit(): WatchTemperatureUnit =
        WatchTemperatureUnit.fromRaw(preferences.getString(KEY_TEMPERATURE_UNIT, null))

    private fun persistSnapshot(snapshot: WatchWeatherSnapshot, receivedAtEpochMs: Long) {
        val root = JSONObject().apply {
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
        val encoded = URLEncoder.encode(city, Charsets.UTF_8.name())
        val language = Locale.getDefault().language.takeIf(String::isNotBlank) ?: "en"
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=$encoded&count=1&language=$language&format=json"
        val root = JSONObject(transport.get(url, USER_AGENT) ?: return null)
        val result = root.optJSONArray("results")?.optJSONObject(0) ?: return null
        val latitude = result.optDoubleOrNull("latitude") ?: return null
        val longitude = result.optDoubleOrNull("longitude") ?: return null
        val label = listOf(
            result.optString("name").takeIf(String::isNotBlank),
            result.optString("admin1").takeIf(String::isNotBlank),
            result.optString("country").takeIf(String::isNotBlank),
        ).filterNotNull().distinct().joinToString(", ").ifBlank { city }
        val resolved = runCatching { WatchWeatherLocation(latitude, longitude, label) }.getOrNull()
            ?: return null
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
            "&daily=temperature_2m_mean,temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset" +
            "&hourly=temperature_2m,weather_code,is_day&forecast_hours=13" +
            "&forecast_days=6&timezone=auto"
        val root = JSONObject(transport.get(url, USER_AGENT) ?: return null)
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
            forecast = forecasts,
            hourlyForecast = hourlyForecasts,
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
        const val PREFERENCES = "watch_weather_repository"
        const val KEY_LABEL = "manual_label"
        const val KEY_CITY = "manual_city"
        const val KEY_LATITUDE = "manual_latitude"
        const val KEY_LONGITUDE = "manual_longitude"
        const val KEY_CACHE_RECEIVED_AT = "cache_received_at"
        const val KEY_CACHE_JSON = "cache_json"
        const val KEY_TEMPERATURE_UNIT = "temperature_unit"
        const val DEFAULT_SOFT_TTL_MS = 15L * 60L * 1_000L
        const val MAX_CACHE_AGE_MS = 6L * 60L * 60L * 1_000L
        const val USER_AGENT = "FlueNext/2.0 (weather; https://github.com/xumidcm/Flue)"

        @Volatile
        private var defaultInstance: WatchWeatherRepository? = null

        fun default(context: Context): WatchWeatherRepository = synchronized(this) {
            defaultInstance ?: WatchWeatherRepository(context.applicationContext).also { defaultInstance = it }
        }
    }
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

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}
