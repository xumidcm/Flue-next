package com.flue.launcher.watchface.standard

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** The canvas contract used by WatchMaker exports. Values are design pixels. */
object WatchmakerGeometry {
    const val DESIGN_SIZE = 512f

    fun coordinate(raw: String?, fallback: Float): Float =
        raw?.trim()?.toFloatOrNull()?.takeIf(Float::isFinite) ?: fallback

    fun dimension(raw: String?, fallback: Float): Float =
        coordinate(raw, fallback).coerceAtLeast(0f)

    fun textSize(raw: String?, fallback: Float): Float =
        coordinate(raw, fallback).coerceIn(1f, DESIGN_SIZE)
}

data class WatchCurveArc(
    val startDegrees: Float,
    val sweepDegrees: Float,
)

/** Text metrics shared by runtime and editor preview. */
object WatchTextLayout {
    const val TTF_FONT_SCALE = 1.28f

    fun ttfTextSize(designSize: Float): Float =
        (designSize.coerceIn(1f, WatchmakerGeometry.DESIGN_SIZE) * TTF_FONT_SCALE)
            .coerceAtMost(WatchmakerGeometry.DESIGN_SIZE * TTF_FONT_SCALE)

    fun curveArc(direction: String?): WatchCurveArc =
        if (direction.equals("Down", ignoreCase = true)) {
            WatchCurveArc(startDegrees = 180f, sweepDegrees = -180f)
        } else {
            WatchCurveArc(startDegrees = 180f, sweepDegrees = 180f)
        }
}

/**
 * WM_FORMAT 文字/形状描边通道的取值契约。`outline` 取 "Outline"（硬描边）
 * 或 "Glow"（发光）；`o_size` 是描边/发光尺寸（Outline 默认 4、Glow 默认
 * 10）；`o_color` 默认白；`o_opacity` 0-100。尺寸全局封顶 [MAX_WATCH_OUTLINE_SIZE]
 * 防恶意表盘拖帧；Glow 的环数与每环透明度也在这里统一定义，渲染器只消费。
 */
enum class WatchOutlineMode {
    NONE,
    OUTLINE,
    GLOW,
}

/** o_size 的全局封顶（设计像素）：再大也只会拖帧，不会更好看。 */
const val MAX_WATCH_OUTLINE_SIZE = 120f

/** Glow 每帧最多画的环数；8 环 × 8 方向已是位图字体的成本上限。 */
const val MAX_WATCH_GLOW_RINGS = 8

fun watchOutlineMode(raw: String?): WatchOutlineMode =
    when (raw.orEmpty().trim().lowercase(Locale.ROOT)) {
        "outline" -> WatchOutlineMode.OUTLINE
        "glow" -> WatchOutlineMode.GLOW
        else -> WatchOutlineMode.NONE
    }

fun watchOutlineDefaultSize(mode: WatchOutlineMode): Float = when (mode) {
    WatchOutlineMode.GLOW -> 10f
    else -> 4f
}

/** 缺省回落到模式默认值，并按全局封顶收口；负数视为 0（不画）。 */
fun watchOutlineSize(mode: WatchOutlineMode, raw: Float?): Float =
    (raw ?: watchOutlineDefaultSize(mode)).coerceIn(0f, MAX_WATCH_OUTLINE_SIZE)

/** Glow 环数 = ceil(o_size / 2)，封顶 [MAX_WATCH_GLOW_RINGS]。 */
fun watchGlowRingCount(size: Float): Int =
    ceil(size.coerceIn(0f, MAX_WATCH_OUTLINE_SIZE) / 2f).toInt().coerceIn(1, MAX_WATCH_GLOW_RINGS)

/**
 * 第 [ring]（1 起、由内向外）环的透明度系数：内环最实、逐环线性衰减，
 * 最外环仍保底 1/(ringCount+1)，不会衰减到完全不可见。
 */
fun watchGlowRingAlpha(ring: Int, ringCount: Int): Float {
    if (ringCount <= 0) return 0f
    val clamped = ring.coerceIn(1, ringCount)
    return (ringCount - clamped + 1).toFloat() / (ringCount + 1).toFloat()
}

internal data class WatchCanvasTransform(
    val scale: Float,
    val left: Float,
    val top: Float,
)

/**
 * Maps WatchMaker's design canvas into the current viewport.
 *
 * The default mode fits the complete canvas and keeps the definition's shape
 * clipping semantics. Force-fill uses a centered crop, so rectangular screens
 * never anchor the face to one edge.
 *
 * [designWidth] / [designHeight] default to the square native canvas, so every
 * existing caller keeps its exact behaviour. A face that declares a genuinely
 * non-square canvas passes its own design extents (see
 * `WatchCanvasSize.designExtentWidth`); the scale stays uniform on both axes so
 * nothing is ever anamorphically stretched — only the letterbox offsets differ.
 */
internal fun watchCanvasTransform(
    viewWidth: Float,
    viewHeight: Float,
    forceFill: Boolean,
    designWidth: Float = WatchmakerGeometry.DESIGN_SIZE,
    designHeight: Float = WatchmakerGeometry.DESIGN_SIZE,
): WatchCanvasTransform {
    require(viewWidth.isFinite() && viewWidth > 0f) { "viewWidth must be positive" }
    require(viewHeight.isFinite() && viewHeight > 0f) { "viewHeight must be positive" }
    require(designWidth.isFinite() && designWidth > 0f) { "designWidth must be positive" }
    require(designHeight.isFinite() && designHeight > 0f) { "designHeight must be positive" }
    val scaleX = viewWidth / designWidth
    val scaleY = viewHeight / designHeight
    val scale = if (forceFill) max(scaleX, scaleY) else min(scaleX, scaleY)
    return WatchCanvasTransform(
        scale = scale,
        left = (viewWidth - designWidth * scale) / 2f,
        top = (viewHeight - designHeight * scale) / 2f,
    )
}

enum class WatchFaceShape {
    ROUND,
    SQUARE,
    RECTANGULAR,
    UNKNOWN,
}

fun watchFaceShape(raw: String?): WatchFaceShape {
    return when (raw.orEmpty().trim().lowercase(Locale.ROOT)) {
        "r", "round", "circle", "circular" -> WatchFaceShape.ROUND
        "s", "square" -> WatchFaceShape.SQUARE
        "rect", "rectangle", "rectangular", "w" -> WatchFaceShape.RECTANGULAR
        else -> WatchFaceShape.UNKNOWN
    }
}

/**
 * A validated, read-only font index. Building the index performs all file I/O
 * and is intended to run from a worker dispatcher. Rendering only performs
 * map lookups and Typeface loading on its existing worker job.
 */
class WatchFontIndex private constructor(
    private val byPath: Map<String, File>,
    private val byName: Map<String, File>,
    private val byStem: Map<String, File>,
    val rejectedFiles: List<String>,
) {
    fun resolve(reference: String?): File? {
        val normalized = normalizeFontReference(reference) ?: return null
        val candidates = LinkedHashSet<String>()
        fun add(value: String) {
            if (value.isBlank()) return
            candidates += value
            if (fontExtension(value) == null) {
                FONT_EXTENSIONS.forEach { extension -> candidates += "$value.$extension" }
            }
        }

        add(normalized)
        add(normalized.substringAfterLast('/'))
        if (!normalized.startsWith("fonts/")) add("fonts/$normalized")
        candidates.forEach { candidate ->
            byPath[candidate]?.let { return it }
            byName[candidate.substringAfterLast('/')]
                ?.let { return it }
            byStem[candidate.substringAfterLast('/').substringBeforeLast('.')]
                ?.let { return it }
        }
        return null
    }

    companion object {
        private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

        fun build(root: File, files: Iterable<File> = root.walkTopDown().filter(File::isFile).asIterable()): WatchFontIndex {
            val byPath = HashMap<String, File>()
            val byName = HashMap<String, File>()
            val byStem = HashMap<String, File>()
            val ambiguousNames = HashSet<String>()
            val ambiguousStems = HashSet<String>()
            val rejected = ArrayList<String>()
            files.forEach { file ->
                if (!file.isFile || file.extension.lowercase(Locale.ROOT) !in FONT_EXTENSIONS) return@forEach
                val relative = runCatching {
                    file.relativeTo(root).invariantSeparatorsPath.lowercase(Locale.ROOT)
                }.getOrNull() ?: return@forEach
                if (!isSfnt(file)) {
                    rejected += relative
                    return@forEach
                }
                byPath.putIfAbsent(relative, file)
                val name = file.name.lowercase(Locale.ROOT)
                val stem = file.nameWithoutExtension.lowercase(Locale.ROOT)
                // Ambiguous basenames/stems are deliberately removed rather
                // than chosen by filesystem traversal order.
                putUnique(byName, ambiguousNames, name, file)
                putUnique(byStem, ambiguousStems, stem, file)
            }
            return WatchFontIndex(byPath, byName, byStem, rejected)
        }

        private fun putUnique(
            map: MutableMap<String, File>,
            ambiguous: MutableSet<String>,
            key: String,
            value: File,
        ) {
            if (key in ambiguous) return
            val previous = map[key]
            when {
                previous == null -> map[key] = value
                previous !== value -> {
                    map.remove(key)
                    ambiguous += key
                }
            }
        }

        private fun isSfnt(file: File): Boolean {
            return runCatching {
                FileInputStream(file).use { input ->
                    val header = ByteArray(4)
                    readFully(input, header) == 4 && isSfntHeader(header)
                }
            }.getOrDefault(false)
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Int {
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                if (count < 0) break
                if (count == 0) continue
                offset += count
            }
            return offset
        }

        private fun isSfntHeader(header: ByteArray): Boolean {
            if (header.size < 4) return false
            return (header[0].toInt() and 0xff) == 0x00 &&
                (header[1].toInt() and 0xff) == 0x01 &&
                (header[2].toInt() and 0xff) == 0x00 &&
                (header[3].toInt() and 0xff) == 0x00 ||
                header.contentEquals(byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte())) ||
                header.contentEquals(byteArrayOf('t'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(), 'f'.code.toByte())) ||
                header.contentEquals(byteArrayOf('t'.code.toByte(), 'r'.code.toByte(), 'u'.code.toByte(), 'e'.code.toByte())) ||
                header.contentEquals(byteArrayOf('t'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), '1'.code.toByte()))
        }

        private fun normalizeFontReference(reference: String?): String? {
            val raw = reference.orEmpty().trim()
            val value = (if (raw.startsWith("bm:", ignoreCase = true)) raw.substring(3) else raw)
                .replace('\\', '/')
                .trimStart('/')
                .lowercase(Locale.ROOT)
            if (value.isBlank() || value.contains('\u0000')) return null
            val segments = value.split('/').filter { it.isNotBlank() }
            if (segments.any { it == "." || it == ".." }) return null
            return segments.joinToString("/")
        }

        private fun fontExtension(value: String): String? =
            value.substringAfterLast('.', "").takeIf { it in FONT_EXTENSIONS }
    }
}

data class WatchDataSnapshot(
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val steps: Int? = null,
    val heartRateBpm: Int? = null,
    val gyroscopeX: Float? = null,
    val gyroscopeY: Float? = null,
    val gyroscopeZ: Float? = null,
    val wifiSignalFraction: Float? = null,
    val bluetoothEnabled: Boolean? = null,
    /**
     * Preferred weather contract. Values inside [WatchWeatherSnapshot] are
     * always Celsius; the display unit is a presentation choice, never an
     * input-unit ambiguity for renderers.
     */
    val weather: WatchWeatherSnapshot? = null,
    // Kept for callers compiled against the first runtime slice. New sources
    // should populate [weather] instead, so every renderer shares the typed
    // Celsius contract below.
    val weatherTemperature: String? = null,
    val weatherCondition: String? = null,
    val weatherIcon: String? = null,
    val weatherHigh: String? = null,
    val weatherLow: String? = null,
    val weatherTemperatureUnit: String? = null,
    val weatherHumidity: Int? = null,
    val weatherLocation: String? = null,
)

fun interface WatchDataProvider {
    fun snapshot(nowEpochMs: Long): WatchDataSnapshot

    /**
     * Best-effort re-read of on-demand data sources. Watch faces call this from
     * tap actions (e.g. the heart-rate widget) to nudge a fresh sensor
     * measurement; providers without live sources can ignore it.
     */
    fun requestRefresh() {}
}

/** Optional, application-owned weather source. No location/network permission is requested here. */
fun interface WatchWeatherSource {
    fun snapshot(nowEpochMs: Long): WatchWeatherSnapshot?
}

/** The unit selected for rendering. Weather source values themselves stay Celsius. */
enum class WatchTemperatureUnit(val symbol: String) {
    CELSIUS("C"),
    FAHRENHEIT("F");

    companion object {
        fun fromRaw(value: String?): WatchTemperatureUnit {
            val normalized = value.orEmpty().trim().removePrefix("°")
            return if (
                normalized.equals("F", ignoreCase = true) ||
                normalized.equals("FAHRENHEIT", ignoreCase = true)
            ) {
                FAHRENHEIT
            } else {
                CELSIUS
            }
        }
    }
}

/** One daily forecast entry, addressed deterministically by its one-based day offset. */
data class WatchWeatherForecast(
    val dayOffset: Int = 1,
    val temperatureCelsius: Double? = null,
    val highCelsius: Double? = null,
    val lowCelsius: Double? = null,
    val condition: String? = null,
    /** WatchMaker-style condition code, normalized to `NNd` / `NNn` when possible. */
    val icon: String? = null,
)

/** One hourly forecast entry. Offsets are one-based (`1` is the next hour). */
data class WatchWeatherHourlyForecast(
    val hourOffset: Int = 1,
    val hourOfDay: Int? = null,
    val temperatureCelsius: Double? = null,
    val condition: String? = null,
    val icon: String? = null,
)

data class WatchWeatherSnapshot(
    /** Canonical temperature source of truth, in degrees Celsius. */
    val temperatureCelsius: Double? = null,
    val highCelsius: Double? = null,
    val lowCelsius: Double? = null,
    val displayUnit: WatchTemperatureUnit = WatchTemperatureUnit.CELSIUS,
    val observedAtEpochMs: Long? = null,
    /** Optional source-formatted value; when absent the resolver emits HH:mm:ss. */
    val lastUpdatedText: String? = null,
    val isDaytime: Boolean? = null,
    val forecast: List<WatchWeatherForecast> = emptyList(),
    val hourlyForecast: List<WatchWeatherHourlyForecast> = emptyList(),
    /** WatchMaker `{wcl}` contract: cloud cover as a fraction in 0..1. */
    val cloudCoverFraction: Double? = null,
    val temperature: String? = null,
    val condition: String? = null,
    val icon: String? = null,
    val high: String? = null,
    val low: String? = null,
    val temperatureUnit: String? = null,
    val humidity: Int? = null,
    val location: String? = null,
)

/**
 * Thread-safe in-memory cache used by the application default weather seam.
 * It deliberately does no I/O: callers can update it from a location/network
 * worker, while watch render frames only read an already available snapshot.
 */
class WatchWeatherSnapshotCache(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) : WatchWeatherSource {
    private data class Entry(
        val snapshot: WatchWeatherSnapshot,
        val receivedAtEpochMs: Long,
    )

    @Volatile
    private var entry: Entry? = null

    fun update(snapshot: WatchWeatherSnapshot, receivedAtEpochMs: Long = System.currentTimeMillis()) {
        if (receivedAtEpochMs < 0L) return
        entry = Entry(snapshot, receivedAtEpochMs)
    }

    fun clear() {
        entry = null
    }

    override fun snapshot(nowEpochMs: Long): WatchWeatherSnapshot? {
        val current = entry ?: return null
        val age = nowEpochMs - current.receivedAtEpochMs
        return current.snapshot.takeIf { age in 0..maxAgeMs }
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 30L * 60L * 1_000L
    }
}

/**
 * Application-wide weather injection point. Flue intentionally does not call
 * a store endpoint for weather; an app-owned worker may install a nonblocking
 * source or publish snapshots here. Both standard and JB hosts use this same
 * default when no explicit source is supplied.
 */
object WatchWeatherSources {
    private val cache = WatchWeatherSnapshotCache()

    @Volatile
    var applicationSource: WatchWeatherSource? = null

    private val defaultSource = WatchWeatherSource { nowEpochMs ->
        // Ask the application source first: repositories may apply a user
        // preference (for example C/F) on every read even while the canonical
        // weather payload itself remains cached. The local cache is only a
        // last-good fallback when that source is temporarily unavailable.
        runCatching {
            applicationSource?.snapshot(nowEpochMs)
        }.getOrNull()?.also { snapshot ->
            cache.update(snapshot, nowEpochMs)
        } ?: cache.snapshot(nowEpochMs)
    }

    fun default(): WatchWeatherSource = defaultSource

    fun publish(snapshot: WatchWeatherSnapshot, receivedAtEpochMs: Long = System.currentTimeMillis()) {
        cache.update(snapshot, receivedAtEpochMs)
    }
}

/** Converts legacy string weather payloads into the typed Celsius contract. */
internal fun canonicalWatchWeatherSnapshot(raw: WatchWeatherSnapshot): WatchWeatherSnapshot {
    val legacyUnit = raw.temperatureUnit
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(WatchTemperatureUnit::fromRaw)
    val sourceUnit = legacyUnit ?: raw.displayUnit
    fun canonical(rawValue: String?, explicitCelsius: Double?): Double? {
        val value = explicitCelsius?.takeIf(Double::isFinite)
            ?: parseWeatherNumber(rawValue)
            ?: return null
        // A typed value is already Celsius. Legacy strings carry their unit
        // through temperatureUnit and are converted exactly once here.
        return if (explicitCelsius != null || sourceUnit == WatchTemperatureUnit.CELSIUS) {
            value
        } else {
            (value - 32.0) * 5.0 / 9.0
        }
    }
    val normalizedForecast = raw.forecast.mapNotNull { item ->
        val offset = item.dayOffset.takeIf { it > 0 } ?: return@mapNotNull null
        item.copy(
            dayOffset = offset,
            temperatureCelsius = item.temperatureCelsius?.takeIf(Double::isFinite),
            highCelsius = item.highCelsius?.takeIf(Double::isFinite),
            lowCelsius = item.lowCelsius?.takeIf(Double::isFinite),
            condition = item.condition?.trim()?.takeIf(String::isNotEmpty),
            icon = normalizeWatchWeatherIcon(item.icon, item.icon?.endsWith("d", true)),
        )
    }
    val normalizedHourlyForecast = raw.hourlyForecast.mapNotNull { item ->
        val offset = item.hourOffset.takeIf { it in 1..12 } ?: return@mapNotNull null
        item.copy(
            hourOffset = offset,
            hourOfDay = item.hourOfDay?.takeIf { it in 0..23 },
            temperatureCelsius = item.temperatureCelsius?.takeIf(Double::isFinite),
            condition = item.condition?.trim()?.takeIf(String::isNotEmpty),
            icon = normalizeWatchWeatherIcon(item.icon, item.icon?.endsWith("d", true)),
        )
    }
    return raw.copy(
        temperatureCelsius = canonical(raw.temperature, raw.temperatureCelsius),
        highCelsius = canonical(raw.high, raw.highCelsius),
        lowCelsius = canonical(raw.low, raw.lowCelsius),
        displayUnit = legacyUnit ?: raw.displayUnit,
        condition = raw.condition?.trim()?.takeIf(String::isNotEmpty),
        icon = normalizeWatchWeatherIcon(raw.icon, raw.isDaytime),
        humidity = raw.humidity?.coerceIn(0, 100),
        location = raw.location?.trim()?.takeIf(String::isNotEmpty),
        forecast = normalizedForecast,
        hourlyForecast = normalizedHourlyForecast,
        cloudCoverFraction = raw.cloudCoverFraction
            ?.takeIf(Double::isFinite)
            ?.coerceIn(0.0, 1.0),
    )
}

private fun parseWeatherNumber(raw: String?): Double? {
    val value = raw?.trim()?.replace(',', '.') ?: return null
    if (value.isBlank()) return null
    val match = Regex("[-+]?\\d+(?:\\.\\d+)?").find(value) ?: return null
    return match.value.toDoubleOrNull()?.takeIf(Double::isFinite)
}

/** Stable NNd icon form used by standard/JB tags and image condition grids. */
internal fun normalizeWatchWeatherIcon(raw: String?, isDaytime: Boolean?): String? {
    val value = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return null
    val suffix = when {
        value.endsWith('d') -> 'd'
        value.endsWith('n') -> 'n'
        isDaytime == false -> 'n'
        else -> 'd'
    }
    val digits = value.trimEnd('d', 'n').filter(Char::isDigit)
    if (digits.isBlank()) return value
    val numericCode = digits.toIntOrNull() ?: return value
    return numericCode.coerceIn(0, 99).toString().padStart(2, '0') + suffix
}

/** WatchMaker condition tags compare day-code-only values; night is `{wisday}`. */
internal fun normalizeWatchmakerConditionIcon(raw: String?): String? {
    val normalized = normalizeWatchWeatherIcon(raw, true) ?: return null
    val numeric = Regex("^(\\d{2})[dn]$").matchEntire(normalized) ?: return normalized
    return numeric.groupValues[1] + "d"
}

/** Inserts WatchMaker's `_n` suffix before the file extension. */
internal fun watchWeatherNightAssetReference(reference: String): String {
    val slash = max(reference.lastIndexOf('/'), reference.lastIndexOf('\\'))
    val dot = reference.lastIndexOf('.').takeIf { it > slash }
    return if (dot == null) {
        "${reference}_n"
    } else {
        reference.substring(0, dot) + "_n" + reference.substring(dot)
    }
}

/** Zero-based cell remap used by `weather_night=Y` 3x3 sprite exports. */
internal fun watchWeatherNightSpriteIndex(dayIndex: Int): Int = when (dayIndex) {
    0 -> 3 // clear day cell 1 -> moon cell 4
    1, 2 -> 2 // few/partly cloudy -> night cell 3
    3 -> 8 // overcast cell 4 -> fallback cell 9
    else -> dayIndex
}

internal fun weatherTemperatureNumber(celsius: Double?, unit: WatchTemperatureUnit): String? {
    val value = celsius?.takeIf(Double::isFinite)?.let {
        if (unit == WatchTemperatureUnit.FAHRENHEIT) it * 9.0 / 5.0 + 32.0 else it
    } ?: return null
    val rounded = round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        java.lang.String.format(Locale.US, "%.1f", rounded)
    }
}

internal fun weatherTemperatureWithUnit(celsius: Double?, unit: WatchTemperatureUnit): String? =
    weatherTemperatureNumber(celsius, unit)?.let { "$it°${unit.symbol}" }

private fun weatherSnapshotFor(snapshot: WatchDataSnapshot): WatchWeatherSnapshot? {
    snapshot.weather?.let { return canonicalWatchWeatherSnapshot(it) }
    val hasLegacyWeather = listOf(
        snapshot.weatherTemperature,
        snapshot.weatherCondition,
        snapshot.weatherIcon,
        snapshot.weatherHigh,
        snapshot.weatherLow,
        snapshot.weatherLocation,
    ).any { !it.isNullOrBlank() } || snapshot.weatherHumidity != null
    if (!hasLegacyWeather) return null
    return canonicalWatchWeatherSnapshot(
        WatchWeatherSnapshot(
            temperature = snapshot.weatherTemperature,
            condition = snapshot.weatherCondition,
            icon = snapshot.weatherIcon,
            high = snapshot.weatherHigh,
            low = snapshot.weatherLow,
            temperatureUnit = snapshot.weatherTemperatureUnit,
            humidity = snapshot.weatherHumidity,
            location = snapshot.weatherLocation,
        ),
    )
}

object EmptyWatchDataProvider : WatchDataProvider {
    private val empty = WatchDataSnapshot()
    override fun snapshot(nowEpochMs: Long): WatchDataSnapshot = empty
}

enum class WatchSourceReloadMode {
    CLEAR_BEFORE_LOAD,
    KEEP_CURRENT_UNTIL_READY,
}

/**
 * Refreshing the same installed face must not expose an empty renderer while
 * watch.xml is parsed again. A different descriptor cannot reuse the previous
 * frame because it belongs to another package.
 */
fun watchSourceReloadMode(
    currentDescriptorKey: String?,
    nextDescriptorKey: String,
    hasPreparedSource: Boolean,
): WatchSourceReloadMode =
    if (hasPreparedSource && currentDescriptorKey == nextDescriptorKey) {
        WatchSourceReloadMode.KEEP_CURRENT_UNTIL_READY
    } else {
        WatchSourceReloadMode.CLEAR_BEFORE_LOAD
    }

/**
 * Revision key for a single immutable watch asset. It deliberately excludes the
 * UI refresh token: refreshing watch.xml must not invalidate every decoded
 * bitmap/font/movie when the underlying file is unchanged.
 */
internal fun watchAssetCacheRevision(
    rootKey: String,
    relativePath: String,
    length: Long,
    lastModifiedEpochMs: Long,
): String = buildString(rootKey.length + relativePath.length + 48) {
    append(rootKey)
    append('\u0000')
    append(relativePath)
    append('\u0000')
    append(length.coerceAtLeast(0L))
    append('\u0000')
    append(lastModifiedEpochMs.coerceAtLeast(0L))
}

/**
 * Resolves parent links without allowing malformed self/cyclic relationships
 * to detach every layer from the render roots. Invalid links are promoted to
 * roots while valid group relationships are preserved.
 */
internal const val MAX_WATCH_LAYER_COUNT = 2_048
internal const val MAX_WATCH_PARENT_DEPTH = 64

internal data class WatchLayerGraphDiagnostics(
    val inputLayerCount: Int,
    val acceptedLayerCount: Int,
    val duplicateIdCount: Int,
    val invalidParentCount: Int,
    val cycleLayerCount: Int,
    val depthLimitBreakCount: Int,
) {
    val truncatedLayerCount: Int
        get() = (inputLayerCount - acceptedLayerCount).coerceAtLeast(0)

    val isDegraded: Boolean
        get() = truncatedLayerCount > 0 ||
            duplicateIdCount > 0 ||
            invalidParentCount > 0 ||
            cycleLayerCount > 0 ||
            depthLimitBreakCount > 0
}

internal data class WatchLayerGraphResolution(
    val parentIndices: List<Int?>,
    val diagnostics: WatchLayerGraphDiagnostics,
)

/**
 * Resolve the functional parent graph in linear time. The accepted prefix and
 * depth cap are deliberate compatibility fallbacks for hostile or malformed
 * archives: later layers are reported as truncated and a too-deep link starts
 * a new root instead of making preparation or rendering quadratic.
 */
internal fun resolveWatchLayerGraph(layers: List<WatchmakerLayer>): WatchLayerGraphResolution {
    val acceptedLayerCount = minOf(layers.size, MAX_WATCH_LAYER_COUNT)
    val firstIndexById = HashMap<String, Int>(acceptedLayerCount)
    var duplicateIdCount = 0
    repeat(acceptedLayerCount) { index ->
        val layer = layers[index]
        layer.id?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
            if (firstIndexById.putIfAbsent(id, index) != null) duplicateIdCount++
        }
    }

    val parents = IntArray(acceptedLayerCount) { NO_PARENT_INDEX }
    var invalidParentCount = 0
    repeat(acceptedLayerCount) { index ->
        val parentId = layers[index].parentId?.trim()?.takeIf(String::isNotEmpty)
            ?: return@repeat
        val requestedParent = firstIndexById[parentId]
        if (
            requestedParent == null ||
            requestedParent == index ||
            !layers[requestedParent].isGroup
        ) {
            invalidParentCount++
        } else {
            parents[index] = requestedParent
        }
    }

    // Every node has at most one outgoing parent edge, so a three-state walk
    // finds all cycles without allocating a HashSet for each layer.
    val visitState = ByteArray(acceptedLayerCount)
    val pathPosition = IntArray(acceptedLayerCount) { NO_PARENT_INDEX }
    val walkPath = ArrayList<Int>(minOf(acceptedLayerCount, MAX_WATCH_PARENT_DEPTH + 1))
    var cycleLayerCount = 0
    repeat(acceptedLayerCount) { start ->
        if (visitState[start].toInt() != VISIT_UNSEEN) return@repeat
        walkPath.clear()
        var cursor = start
        while (cursor != NO_PARENT_INDEX && visitState[cursor].toInt() == VISIT_UNSEEN) {
            visitState[cursor] = VISIT_ACTIVE.toByte()
            pathPosition[cursor] = walkPath.size
            walkPath += cursor
            cursor = parents[cursor]
        }
        if (cursor != NO_PARENT_INDEX && visitState[cursor].toInt() == VISIT_ACTIVE) {
            val cycleStart = pathPosition[cursor]
            if (cycleStart >= 0) {
                for (pathIndex in cycleStart..walkPath.lastIndex) {
                    val cycleLayer = walkPath[pathIndex]
                    parents[cycleLayer] = NO_PARENT_INDEX
                    cycleLayerCount++
                }
            }
        }
        walkPath.forEach { layerIndex ->
            visitState[layerIndex] = VISIT_DONE.toByte()
            pathPosition[layerIndex] = NO_PARENT_INDEX
        }
    }

    // Memoized depths make shared ancestors O(1) after their first walk.
    val resolvedDepth = IntArray(acceptedLayerCount) { UNKNOWN_DEPTH }
    val depthPath = ArrayList<Int>(minOf(acceptedLayerCount, MAX_WATCH_PARENT_DEPTH + 1))
    var depthLimitBreakCount = 0
    repeat(acceptedLayerCount) { start ->
        if (resolvedDepth[start] != UNKNOWN_DEPTH) return@repeat
        depthPath.clear()
        var cursor = start
        while (cursor != NO_PARENT_INDEX && resolvedDepth[cursor] == UNKNOWN_DEPTH) {
            depthPath += cursor
            cursor = parents[cursor]
        }
        for (pathIndex in depthPath.lastIndex downTo 0) {
            val layerIndex = depthPath[pathIndex]
            val parentIndex = parents[layerIndex]
            val depth = if (parentIndex == NO_PARENT_INDEX) {
                0
            } else {
                resolvedDepth[parentIndex] + 1
            }
            if (depth > MAX_WATCH_PARENT_DEPTH) {
                parents[layerIndex] = NO_PARENT_INDEX
                resolvedDepth[layerIndex] = 0
                depthLimitBreakCount++
            } else {
                resolvedDepth[layerIndex] = depth
            }
        }
    }

    return WatchLayerGraphResolution(
        parentIndices = List(acceptedLayerCount) { index ->
            parents[index].takeUnless { it == NO_PARENT_INDEX }
        },
        diagnostics = WatchLayerGraphDiagnostics(
            inputLayerCount = layers.size,
            acceptedLayerCount = acceptedLayerCount,
            duplicateIdCount = duplicateIdCount,
            invalidParentCount = invalidParentCount,
            cycleLayerCount = cycleLayerCount,
            depthLimitBreakCount = depthLimitBreakCount,
        ),
    )
}

internal fun resolveWatchLayerParentIndices(layers: List<WatchmakerLayer>): List<Int?> =
    resolveWatchLayerGraph(layers).parentIndices

internal data class WatchLayerAncestorChain(
    val layerIndex: Int,
    val outer: WatchLayerAncestorChain?,
)

internal data class WatchLayerRenderItem(
    val layerIndex: Int,
    val ancestorChain: WatchLayerAncestorChain?,
) {
    /**
     * Compatibility/debug view of the chain. Production drawing walks the
     * shared nodes directly and never materializes one list per drawable.
     */
    val ancestorIndices: List<Int>
        get() {
            val innerToOuter = ArrayList<Int>()
            var cursor = ancestorChain
            while (cursor != null) {
                innerToOuter += cursor.layerIndex
                cursor = cursor.outer
            }
            innerToOuter.reverse()
            return innerToOuter
        }
}

internal data class WatchLayerRenderPlanResult(
    val items: List<WatchLayerRenderItem>,
    val diagnostics: WatchLayerGraphDiagnostics,
)

/**
 * Keeps WatchMaker's global document z-order while carrying the group chain
 * needed to reproduce inherited transforms for each drawable layer.
 */
internal fun buildWatchLayerRenderPlanResult(layers: List<WatchmakerLayer>): WatchLayerRenderPlanResult {
    val resolution = resolveWatchLayerGraph(layers)
    val parentIndices = resolution.parentIndices
    val chainsByGroup = arrayOfNulls<WatchLayerAncestorChain>(parentIndices.size)

    fun chainFor(parentIndex: Int?): WatchLayerAncestorChain? {
        parentIndex ?: return null
        chainsByGroup[parentIndex]?.let { return it }
        val unresolved = ArrayList<Int>()
        var cursor: Int? = parentIndex
        var outer: WatchLayerAncestorChain? = null
        while (cursor != null) {
            val cached = chainsByGroup[cursor]
            if (cached != null) {
                outer = cached
                break
            }
            unresolved += cursor
            cursor = parentIndices[cursor]
        }
        for (index in unresolved.lastIndex downTo 0) {
            val layerIndex = unresolved[index]
            outer = WatchLayerAncestorChain(layerIndex = layerIndex, outer = outer)
            chainsByGroup[layerIndex] = outer
        }
        return chainsByGroup[parentIndex]
    }

    val items = ArrayList<WatchLayerRenderItem>(parentIndices.size)
    parentIndices.indices.forEach { layerIndex ->
        if (!layers[layerIndex].isGroup) {
            items += WatchLayerRenderItem(
                layerIndex = layerIndex,
                ancestorChain = chainFor(parentIndices[layerIndex]),
            )
        }
    }
    return WatchLayerRenderPlanResult(items = items, diagnostics = resolution.diagnostics)
}

internal fun buildWatchLayerRenderPlan(layers: List<WatchmakerLayer>): List<WatchLayerRenderItem> =
    buildWatchLayerRenderPlanResult(layers).items

private const val NO_PARENT_INDEX = -1
private const val UNKNOWN_DEPTH = -1
private const val VISIT_UNSEEN = 0
private const val VISIT_ACTIVE = 1
private const val VISIT_DONE = 2

internal data class DailyStepState(
    val dayKey: Long,
    val rawCounter: Int,
    val steps: Int,
)

/**
 * TYPE_STEP_COUNTER is cumulative since boot, not "today's steps". Convert it
 * to a persisted daily delta while retaining the accumulated day total across
 * a sensor reset/reboot.
 */
internal fun accumulateDailySteps(
    previous: DailyStepState?,
    dayKey: Long,
    rawCounter: Int,
): DailyStepState {
    val raw = rawCounter.coerceAtLeast(0)
    if (previous == null || previous.dayKey != dayKey) {
        return DailyStepState(dayKey = dayKey, rawCounter = raw, steps = 0)
    }
    val delta = if (raw >= previous.rawCounter) raw.toLong() - previous.rawCounter else 0L
    return DailyStepState(
        dayKey = dayKey,
        rawCounter = raw,
        steps = (previous.steps.toLong() + delta).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
    )
}

sealed class WatchTagResolution(open val renderedValue: String) {
    data class Resolved(override val renderedValue: String) : WatchTagResolution(renderedValue)
    data class Unavailable(val tag: String) : WatchTagResolution("--")
    data class Unsupported(val tag: String) : WatchTagResolution("--")
}

/**
 * Bounded, immutable variables visible to attribute expressions. Only simple
 * top-level `var_* = literal` declarations are imported; function bodies and
 * executable Lua statements are never run by the Canvas fallback runtime.
 */
class WatchExpressionScope private constructor(
    val variables: Map<String, String>,
) {
    fun resolve(name: String): String? = variables[name.lowercase(Locale.ROOT)]

    companion object {
        val EMPTY = WatchExpressionScope(emptyMap())
        private const val MAX_SCRIPT_BYTES = 256 * 1024
        private const val MAX_VARIABLES = 128
        private const val MAX_VALUE_LENGTH = 1_024
        private val ASSIGNMENT = Regex(
            "^\\s*(var_[A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*(?:--.*)?$",
        )

        fun fromInitialScript(script: String?): WatchExpressionScope {
            val source = script.orEmpty()
            if (source.isBlank() || source.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) return EMPTY
            val values = LinkedHashMap<String, String>()
            var functionDepth = 0
            source.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (functionDepth > 0) {
                    when {
                        line == "end" || line.startsWith("end ") || line.startsWith("until ") ->
                            functionDepth--
                        opensLuaBlock(line) -> functionDepth++
                    }
                    return@forEach
                }
                if (line.startsWith("function ") || line.startsWith("local function ")) {
                    functionDepth = 1
                    return@forEach
                }
                if (values.size >= MAX_VARIABLES) return@forEach
                val match = ASSIGNMENT.matchEntire(rawLine) ?: return@forEach
                val name = match.groupValues[1].lowercase(Locale.ROOT)
                val literal = parseLiteral(match.groupValues[2]) ?: return@forEach
                if (literal.length <= MAX_VALUE_LENGTH) values.putIfAbsent(name, literal)
            }
            return if (values.isEmpty()) EMPTY else WatchExpressionScope(values.toMap())
        }

        private fun opensLuaBlock(line: String): Boolean =
            line.startsWith("function ") || line.startsWith("local function ") ||
                Regex("^if\\b.*\\bthen\\s*$").containsMatchIn(line) ||
                Regex("^(?:for|while)\\b.*\\bdo\\s*$").containsMatchIn(line) ||
                line == "repeat"

        private fun parseLiteral(raw: String): String? {
            val value = raw.trim()
            if (value.length > MAX_VALUE_LENGTH || value.isEmpty()) return null
            if (value.equals("true", true) || value.equals("false", true) || value == "nil") {
                return value.lowercase(Locale.ROOT)
            }
            if (value.toDoubleOrNull()?.isFinite() == true) return value
            val quote = value.firstOrNull()
            if ((quote == '\'' || quote == '"') && value.lastOrNull() == quote && value.length >= 2) {
                return value.substring(1, value.lastIndex)
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\$quote", quote.toString())
                    .replace("\\\\", "\\")
            }
            return null
        }
    }
}

/**
 * 「表盘日期语言」契约：设置项 watchface_date_locale 的四档取值
 * （system/zh/en/ja）与日期/星期/月份类标签求值 Locale 的映射。
 * [override] 由 LauncherViewModel 在偏好加载/变更时写入进程级单例，
 * 渲染热路径（[WatchmakerTagResolver.resolve] 的默认参数）直接读取，
 * 表盘无需重建即可在下一帧生效。
 */
object WatchDateLocale {
    const val PREFERENCE_SYSTEM = "system"
    const val PREFERENCE_CHINESE = "zh"
    const val PREFERENCE_ENGLISH = "en"
    const val PREFERENCE_JAPANESE = "ja"

    /** null 表示跟随系统语言；@Volatile 保证渲染线程读到最新写入。 */
    @Volatile
    var override: Locale? = null

    /** 偏好字符串 → Locale；未知/空值一律按跟随系统处理，绝不抛异常。 */
    fun fromPreference(value: String?): Locale? =
        when (value?.trim()?.lowercase(Locale.ROOT)) {
            PREFERENCE_CHINESE -> Locale.SIMPLIFIED_CHINESE
            PREFERENCE_ENGLISH -> Locale.ENGLISH
            PREFERENCE_JAPANESE -> Locale.JAPANESE
            else -> null
        }

    /** 当前生效的日期语言：偏好覆盖优先，否则跟随系统。 */
    fun current(): Locale = override ?: Locale.getDefault()
}

/**
 * Stable tag vocabulary shared by preview/runtime/editor. Unknown tags are
 * explicitly marked unsupported, never returned as raw `{tag}` text.
 */
object WatchmakerTagResolver {
    private val knownUnavailableTags = setOf(
        // Providers are optional on Android. They stay known so a missing
        // source renders "--" and never leaks a literal tag into a face.
        "weather", "wp", "wr", "wsr", "wss",
        "cal", "calendar", "next_event",
        "phone_battery", "pbl", "pblp", "pbc",
        "bv", "bi", "btc", "btf", "sscp", "stsc",
        "alat", "alon",
        "sax", "say", "saz", "sprs", "pwc", "nwip", "nc", "ncc", "ncs", "ngc",
    )

    private val builtInTags = setOf(
        "dh", "dhz", "dh11", "dh11z", "dh12", "dh12z",
        "dh23", "dh24", "dh23z", "dh24z", "da", "dampm",
        "dm", "dmz", "ds", "dsz", "dss", "dssz", "dsps",
        "drss", "drs", "drms", "drm", "drm0", "drh", "drh0", "drh24", "drmh24",
        "dz", "dzz", "dtp",
        "dd", "ddz", "ddw0", "ddw", "ddw1", "ddw2", "ddww",
        "dn", "dnn", "dnnn", "dnnnn", "dy", "dyy", "dyyyy",
        "bl", "blp", "batt", "battery", "battery_level",
        "pbl", "pblp", "pbr",
        "bc", "charging", "is_charging",
        "ssc", "steps", "step_count", "shr", "heart", "heart_rate", "heart_rate_bpm",
        "sthr", "shrp",
        // {shr_1..9}：最近 N 分钟的历史心率读数。
        "shr_1", "shr_2", "shr_3", "shr_4", "shr_5", "shr_6", "shr_7", "shr_8", "shr_9",
        "sgx", "sgy", "sgz", "pws", "nbc",
        "wm", "wt", "wtd", "wtemp", "weather_temperature",
        "wcond", "weather_condition", "wct", "wcct",
        "wicon", "weather_icon", "wci", "wcci",
        "whigh", "weather_high", "wth", "wthd",
        "wlow", "weather_low", "wtl", "wtld",
        "whum", "weather_humidity", "wh", "whp", "wchn", "wchp",
        "wcl", "wl", "wml", "wlc", "wloc", "weather_location", "wlu", "wisday",
        // Legacy WatchMaker five-day forecast vocabulary.
        "wfah", "wfal", "wfact", "wfaci",
        "wbbh", "wfbl", "wfbct", "wfbci",
        "wfch", "wfcl", "wfcct", "wfcci",
        "wfdh", "wfdl", "wfdct", "wfdci",
        "wfeh", "wfel", "wfect", "wfeci",
        "ucolor",
    )

    private val utcCapableTags = setOf(
        "dh", "dhz", "dh11", "dh11z", "dh12", "dh12z",
        "dh23", "dh24", "dh23z", "dh24z", "dm", "dmz",
        "ds", "dsz", "dss", "dssz", "dsps", "da", "dampm",
        "drss", "drs", "drms", "drm", "drm0", "drh", "drh0", "drh24", "drmh24",
        "dd", "ddz", "ddw0", "ddw", "ddw1", "ddw2", "ddww",
        "dn", "dnn", "dnnn", "dnnnn", "dy", "dyy", "dyyyy", "dtp", "dz", "dzz",
    )

    private val fastTickTagPattern = Regex(
        "\\{\\s*(?:utc_?)?(?:dssz?|dsps|drss|drms|drm|dtp)(?:_?utc)?\\s*\\}",
        RegexOption.IGNORE_CASE,
    )

    fun isKnown(tag: String): Boolean {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        return normalized in builtInTags ||
            normalized in knownUnavailableTags ||
            utcBaseTag(normalized) != null ||
            forecastTagParts(normalized) != null ||
            hourlyForecastTagParts(normalized) != null
    }

    /** Shared high-refresh vocabulary for runtime and editor preview. */
    fun requiresFastTicks(raw: String): Boolean = fastTickTagPattern.containsMatchIn(raw)

    fun resolve(
        tag: String,
        now: Calendar,
        snapshot: WatchDataSnapshot,
        userColor: String? = null,
        // 日期/星期/月份类标签的语言：默认读进程级偏好（跟随系统或
        // settings 里选定的 zh/en/ja），测试可显式传入以保证确定性。
        locale: Locale = WatchDateLocale.current(),
    ): WatchTagResolution {
        val requestedTag = tag.trim().lowercase(Locale.ROOT)
        val utcBase = utcBaseTag(requestedTag)
        val normalized = utcBase ?: requestedTag
        val effectiveNow = if (utcBase != null) {
            Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = now.timeInMillis
            }
        } else {
            now
        }
        val weather = weatherSnapshotFor(snapshot)
        val hour24 = effectiveNow.get(Calendar.HOUR_OF_DAY)
        val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
        val value = when (normalized) {
            "dh", "dh12" -> hour12.toString()
            "dhz", "dh12z" -> hour12.toString().padStart(2, '0')
            "dh11" -> (hour24 % 12).toString()
            "dh11z" -> (hour24 % 12).toString().padStart(2, '0')
            "dh23" -> hour24.toString()
            "dh23z" -> hour24.toString().padStart(2, '0')
            "dh24" -> (if (hour24 == 0) 24 else hour24).toString()
            "dh24z" -> (if (hour24 == 0) 24 else hour24).toString().padStart(2, '0')
            "da", "dampm" -> if (effectiveNow.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
            "dm" -> effectiveNow.get(Calendar.MINUTE).toString()
            "dmz" -> effectiveNow.get(Calendar.MINUTE).toString().padStart(2, '0')
            "ds" -> effectiveNow.get(Calendar.SECOND).toString()
            "dsz" -> effectiveNow.get(Calendar.SECOND).toString().padStart(2, '0')
            "dss" -> effectiveNow.get(Calendar.MILLISECOND).toString()
            "dssz" -> effectiveNow.get(Calendar.MILLISECOND).toString().padStart(3, '0')
            "dsps" ->
                (effectiveNow.get(Calendar.SECOND) * 1_000 + effectiveNow.get(Calendar.MILLISECOND)).toString()
            "drs" -> renderCompactNumber(effectiveNow.get(Calendar.SECOND) * 6f)
            "drss" -> renderCompactNumber(
                (effectiveNow.get(Calendar.SECOND) + effectiveNow.get(Calendar.MILLISECOND) / 1_000f) * 6f,
            )
            "drms" -> renderCompactNumber(effectiveNow.get(Calendar.MILLISECOND) * 0.36f)
            "drm0" -> renderCompactNumber(effectiveNow.get(Calendar.MINUTE) * 6f)
            "drm" -> renderCompactNumber(
                (effectiveNow.get(Calendar.MINUTE) +
                    effectiveNow.get(Calendar.SECOND) / 60f +
                    effectiveNow.get(Calendar.MILLISECOND) / 60_000f) * 6f,
            )
            "drh0" -> renderCompactNumber((hour24 % 12) * 30f)
            "drh" -> renderCompactNumber(
                (hour24 % 12 +
                    effectiveNow.get(Calendar.MINUTE) / 60f +
                    effectiveNow.get(Calendar.SECOND) / 3_600f) * 30f,
            )
            "drh24" -> renderCompactNumber(hour24 * 15f)
            "drmh24" -> renderCompactNumber(
                (hour24 + effectiveNow.get(Calendar.MINUTE) / 60f) * 15f,
            )
            "dd" -> effectiveNow.get(Calendar.DAY_OF_MONTH).toString()
            "ddz" -> effectiveNow.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
            "ddw0" -> (effectiveNow.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).toString()
            // 星期标签统一 trimEnd('.')：不少 locale 的缩写数据自带尾句号
            // （es "lun."、de "Mo." 等），落到表盘上就是「周一.」式的拖尾。
            "ddw" -> effectiveNow.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale)
                ?.trimEnd('.')
            "ddw1" -> effectiveNow.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.NARROW_FORMAT, locale)
                ?.trimEnd('.')
            "ddw2" -> effectiveNow.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale)
                ?.trimEnd('.')
                ?.take(2)
            "ddww" -> effectiveNow.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, locale)
                ?.trimEnd('.')
            // WM_TAGS.md:538 —— {dn} = Month number (1–12)。此前只实现了
            // dnn/dnnn/dnnnn，漏掉 dn 本体，用它的表盘月份直接渲染成不可用。
            "dn" -> (effectiveNow.get(Calendar.MONTH) + 1).toString()
            "dnn" -> (effectiveNow.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
            "dnnn" -> effectiveNow.getDisplayName(Calendar.MONTH, Calendar.SHORT, locale)
            "dnnnn" -> effectiveNow.getDisplayName(Calendar.MONTH, Calendar.LONG, locale)
            "dy" -> (effectiveNow.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
            "dyy", "dyyyy" -> effectiveNow.get(Calendar.YEAR).toString()
            "dz" -> effectiveNow.timeZone.getDisplayName(
                effectiveNow.timeZone.inDaylightTime(effectiveNow.time),
                java.util.TimeZone.SHORT,
                locale,
            )
            "dzz" -> effectiveNow.timeZone.getDisplayName(
                effectiveNow.timeZone.inDaylightTime(effectiveNow.time),
                java.util.TimeZone.LONG,
                locale,
            )
            "dtp" -> renderCompactNumber(
                (hour24 * 3_600_000L +
                    effectiveNow.get(Calendar.MINUTE) * 60_000L +
                    effectiveNow.get(Calendar.SECOND) * 1_000L +
                    effectiveNow.get(Calendar.MILLISECOND)).toFloat() / 86_400_000f,
            )
            "bl", "batt", "battery", "battery_level" -> snapshot.batteryPercent?.toString()
            "blp" -> snapshot.batteryPercent?.let { "$it%" }
            "pbl" -> snapshot.batteryPercent?.toString()
            "pblp" -> snapshot.batteryPercent?.let { "$it%" }
            "pbr" -> snapshot.batteryPercent?.let { (it * 3.6f).toString() }
            "bc", "charging", "is_charging" -> snapshot.isCharging?.let { if (it) "1" else "0" }
            "ssc", "steps", "step_count" -> snapshot.steps?.toString()
            // Watchmaker/clox 生态惯例：心率无数据渲染 0，而不是 "--"。
            // 返回 nil 会把 `{shr} + 5` 这类表达式整体打哑；0 让算式照常算。
            // 权限门控完全不动：没权限时 snapshot.heartRateBpm 依旧是 null，
            // 这里只是把 null 的呈现从 Unavailable 收敛为数值 0。
            "shr", "heart", "heart_rate", "heart_rate_bpm" ->
                (snapshot.heartRateBpm ?: 0).toString()
            // WM_TAGS.md: {sthr} = Maximum recorded heart rate（示例 200），
            // {shrp} = Heart rate as % of maximum。Flue 没有用户健康档案，
            // 因此按规范示例使用固定的 200 BPM 作为最大心率。
            "sthr" -> DEFAULT_MAX_HEART_RATE_BPM.toString()
            "shrp" -> ((snapshot.heartRateBpm ?: 0) * 100 / DEFAULT_MAX_HEART_RATE_BPM)
                .coerceIn(0, 100)
                .toString()
            // {shr_1..9}：Flue 不保存心率历史，按无数据惯例渲染 0。
            "shr_1", "shr_2", "shr_3", "shr_4", "shr_5", "shr_6", "shr_7", "shr_8", "shr_9" -> "0"
            "sgx" -> renderCompactNumber(snapshot.gyroscopeX ?: 0f)
            "sgy" -> renderCompactNumber(snapshot.gyroscopeY ?: 0f)
            "sgz" -> renderCompactNumber(snapshot.gyroscopeZ ?: 0f)
            "pws" -> renderCompactNumber(
                (snapshot.wifiSignalFraction ?: 0f).coerceIn(0f, 1f),
            )
            "nbc" -> (snapshot.bluetoothEnabled ?: false).toString()
            "wm" -> weather?.displayUnit?.symbol
            "wt", "wtemp", "weather_temperature" ->
                weatherTemperatureNumber(weather?.temperatureCelsius, weather?.displayUnit ?: WatchTemperatureUnit.CELSIUS)
            "wtd" ->
                weatherTemperatureWithUnit(weather?.temperatureCelsius, weather?.displayUnit ?: WatchTemperatureUnit.CELSIUS)
            // wct was emitted as condition text by Flue's first runtime slice;
            // retain that alias while the canonical WatchMaker spelling wcct
            // is also supported.
            "wcond", "weather_condition", "wct", "wcct" -> weather?.condition
            "wicon", "weather_icon", "wci", "wcci" ->
                normalizeWatchmakerConditionIcon(weather?.icon)
            "whigh", "weather_high", "wth" ->
                weatherTemperatureNumber(weather?.highCelsius, weather?.displayUnit ?: WatchTemperatureUnit.CELSIUS)
            "wthd" ->
                weatherTemperatureWithUnit(weather?.highCelsius, weather?.displayUnit ?: WatchTemperatureUnit.CELSIUS)
            "wlow", "weather_low", "wtl" ->
                weatherTemperatureNumber(weather?.lowCelsius, weather?.displayUnit ?: WatchTemperatureUnit.CELSIUS)
            "wtld" ->
                weatherTemperatureWithUnit(weather?.lowCelsius, weather?.displayUnit ?: WatchTemperatureUnit.CELSIUS)
            "whum", "weather_humidity", "wh", "wchn" -> weather?.humidity?.toString()
            "whp", "wchp" -> weather?.humidity?.let { "$it%" }
            "wcl" -> weather?.cloudCoverFraction?.let { renderCompactNumber(it.toFloat()) }
            "wl", "wml", "wlc", "wloc", "weather_location" -> weather?.location
            "wisday" -> weather?.isDaytime
                ?.let { if (it) "true" else "false" }
                ?: weather?.icon?.lastOrNull()?.let { suffix ->
                    when (suffix) {
                        'd' -> "true"
                        'n' -> "false"
                        else -> null
                    }
                }
            "wlu" -> weather?.lastUpdatedText?.trim()?.takeIf(String::isNotEmpty)
                ?: weather?.observedAtEpochMs?.let { updatedAt ->
                    java.text.SimpleDateFormat("HH:mm:ss", locale).apply {
                        timeZone = effectiveNow.timeZone
                    }.format(updatedAt)
                }
            "ucolor" -> userColor
            else -> resolveForecastTag(normalized, weather)
                ?: resolveHourlyForecastTag(normalized, weather)
        }
        if (value != null) return WatchTagResolution.Resolved(value)
        return if (isKnown(requestedTag)) {
            WatchTagResolution.Unavailable(requestedTag)
        } else {
            WatchTagResolution.Unsupported(requestedTag)
        }
    }

    private data class ForecastTagParts(
        val dayOffset: Int,
        val field: String,
        val withUnit: Boolean = false,
    )

    private fun forecastTagParts(tag: String): ForecastTagParts? {
        // Canonical WatchMaker daily vocabulary: 0=today, 1=tomorrow, ... 5.
        Regex("^wf([0-5])d(t|th|tl|ct|ci)$").matchEntire(tag)?.let { match ->
            val dayOffset = match.groupValues[1].toInt() + 1
            return when (match.groupValues[2]) {
                "t" -> ForecastTagParts(dayOffset, "temperature")
                "th" -> ForecastTagParts(dayOffset, "high")
                "tl" -> ForecastTagParts(dayOffset, "low")
                "ct" -> ForecastTagParts(dayOffset, "condition")
                else -> ForecastTagParts(dayOffset, "icon")
            }
        }
        val legacy = when (tag) {
            "wbbh" -> ForecastTagParts(2, "high") // typo seen in legacy exports
            else -> Regex("^wf([a-e])(h|hd|l|ld|ct|ci)$").matchEntire(tag)?.let { match ->
                val day = match.groupValues[1][0] - 'a' + 1
                when (match.groupValues[2]) {
                    "h" -> ForecastTagParts(day, "high")
                    "hd" -> ForecastTagParts(day, "high", withUnit = true)
                    "l" -> ForecastTagParts(day, "low")
                    "ld" -> ForecastTagParts(day, "low", withUnit = true)
                    "ct" -> ForecastTagParts(day, "condition")
                    else -> ForecastTagParts(day, "icon")
                }
            }
        }
        if (legacy != null) return legacy
        Regex("^wf(\\d+)(h|hd|l|ld|ct|ci)$").matchEntire(tag)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            return when (match.groupValues[2]) {
                "h" -> ForecastTagParts(day, "high")
                "hd" -> ForecastTagParts(day, "high", withUnit = true)
                "l" -> ForecastTagParts(day, "low")
                "ld" -> ForecastTagParts(day, "low", withUnit = true)
                "ct" -> ForecastTagParts(day, "condition")
                else -> ForecastTagParts(day, "icon")
            }
        }
        Regex("^wfa(\\d+)(h|hd|l|ld|ct|ci)$").matchEntire(tag)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            return when (match.groupValues[2]) {
                "h" -> ForecastTagParts(day, "high")
                "hd" -> ForecastTagParts(day, "high", withUnit = true)
                "l" -> ForecastTagParts(day, "low")
                "ld" -> ForecastTagParts(day, "low", withUnit = true)
                "ct" -> ForecastTagParts(day, "condition")
                else -> ForecastTagParts(day, "icon")
            }
        }
        val numbered = Regex("^(wthd|wth|wtld|wtl|wct|wci)(\\d+)$").matchEntire(tag) ?: return null
        val day = numbered.groupValues[2].toIntOrNull() ?: return null
        return when (numbered.groupValues[1]) {
            "wth" -> ForecastTagParts(day, "high")
            "wthd" -> ForecastTagParts(day, "high", withUnit = true)
            "wtl" -> ForecastTagParts(day, "low")
            "wtld" -> ForecastTagParts(day, "low", withUnit = true)
            "wct" -> ForecastTagParts(day, "condition")
            else -> ForecastTagParts(day, "icon")
        }
    }

    private fun resolveForecastTag(tag: String, weather: WatchWeatherSnapshot?): String? {
        val parts = forecastTagParts(tag) ?: return null
        val forecast = weather?.forecast?.firstOrNull { it.dayOffset == parts.dayOffset } ?: return null
        return when (parts.field) {
            "temperature" -> {
                val celsius = forecast.temperatureCelsius
                    ?: (if (parts.dayOffset == 1) weather.temperatureCelsius else null)
                    ?: listOfNotNull(forecast.highCelsius, forecast.lowCelsius)
                        .takeIf { it.isNotEmpty() }
                        ?.average()
                weatherTemperatureNumber(celsius, weather.displayUnit)
            }
            "high" -> if (parts.withUnit) {
                weatherTemperatureWithUnit(forecast.highCelsius, weather.displayUnit)
            } else {
                weatherTemperatureNumber(forecast.highCelsius, weather.displayUnit)
            }
            "low" -> if (parts.withUnit) {
                weatherTemperatureWithUnit(forecast.lowCelsius, weather.displayUnit)
            } else {
                weatherTemperatureNumber(forecast.lowCelsius, weather.displayUnit)
            }
            "condition" -> forecast.condition
            else -> normalizeWatchmakerConditionIcon(forecast.icon)
        }
    }

    private data class HourlyForecastTagParts(
        val hourOffset: Int,
        val field: String,
    )

    private fun hourlyForecastTagParts(tag: String): HourlyForecastTagParts? {
        val match = Regex("^wf(1[0-2]|[1-9])h(t|h|ct|ci)$").matchEntire(tag) ?: return null
        val offset = match.groupValues[1].toInt()
        val field = when (match.groupValues[2]) {
            "t" -> "temperature"
            "h" -> "hour"
            "ct" -> "condition"
            else -> "icon"
        }
        return HourlyForecastTagParts(offset, field)
    }

    private fun resolveHourlyForecastTag(tag: String, weather: WatchWeatherSnapshot?): String? {
        val parts = hourlyForecastTagParts(tag) ?: return null
        val forecast = weather?.hourlyForecast
            ?.firstOrNull { it.hourOffset == parts.hourOffset }
            ?: return null
        return when (parts.field) {
            "temperature" -> weatherTemperatureNumber(forecast.temperatureCelsius, weather.displayUnit)
            "hour" -> forecast.hourOfDay?.toString()
            "condition" -> forecast.condition
            else -> normalizeWatchWeatherIcon(forecast.icon, forecast.icon?.endsWith("d", true))
        }
    }

    private fun utcBaseTag(tag: String): String? {
        val base = when (tag) {
            // Canonical names from WM_TAGS.md. UTC 24-hour tags use 0..23,
            // despite the local `{dh24}` tag using 1..24.
            "dhutc12" -> "dh"
            "dhutc12z" -> "dhz"
            "dhutc24" -> "dh23"
            "dhutc24z" -> "dh23z"
            else -> when {
            tag.startsWith("utc_") -> tag.removePrefix("utc_")
            tag.startsWith("utc") -> tag.removePrefix("utc")
            tag.endsWith("_utc") -> tag.removeSuffix("_utc")
            tag.endsWith("utc") -> tag.removeSuffix("utc")
            else -> return null
            }
        }
        return base.takeIf { it in utcCapableTags }
    }

    private fun renderCompactNumber(value: Float): String =
        if (value.isFinite() && value == value.toLong().toFloat()) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    /** WM_TAGS.md 中 {sthr} 的默认最大心率（BPM）。 */
    private const val DEFAULT_MAX_HEART_RATE_BPM = 200
}

/**
 * Small, deterministic expression engine for WatchMaker's hot-path numeric
 * attributes. It intentionally supports only side-effect-free Lua-style
 * arithmetic, comparisons and `and`/`or`/`not`; functions, assignments and
 * scripts remain unsupported and are surfaced by the compatibility report.
 */
object WatchmakerExpressionEvaluator {
    // 同 WatchmakerDefinitionParser：ICU 后端要求 '}' 转义。
    private val tagPattern = Regex("\\{([^\\{\\}]+)\\}")
    private const val MAX_EXPRESSION_LENGTH = 4 * 1024
    private const val MAX_EXPRESSION_TOKENS = 512
    private const val MAX_FUNCTION_ARGUMENTS = 16
    private const val MAX_RESULT_TEXT_LENGTH = 4 * 1024

    fun interpolate(
        raw: String,
        resolveTag: (String) -> WatchTagResolution,
    ): String = tagPattern.replace(raw) { match ->
        resolveTag(match.groupValues[1]).renderedValue
    }

    /**
     * Evaluate the expression forms WatchMaker commonly emits for text while
     * leaving ordinary labels as templates. Failed/unsupported code falls back
     * to tag interpolation, so one bad expression cannot blank the whole face.
     */
    fun evaluateText(
        raw: String,
        resolveTag: (String) -> WatchTagResolution,
    ): String = evaluateText(raw, WatchExpressionScope.EMPTY, resolveTag)

    fun evaluateText(
        raw: String,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): String {
        val template = interpolate(raw, resolveTag)
        val value = evaluate(raw, scope, resolveTag) ?: return template
        if (value === ExpressionValue.Nil ||
            value is ExpressionValue.Logical && !value.value
        ) {
            return template
        }
        if (value is ExpressionValue.Numeric && template.trim().toDoubleOrNull() != null) {
            // WatchMaker keeps zero-padded single-tag text such as "05".
            return template
        }
        return value.renderText().take(MAX_RESULT_TEXT_LENGTH)
    }

    fun evaluateNumber(
        raw: String?,
        fallback: Float,
        resolveTag: (String) -> WatchTagResolution,
    ): Float = evaluateNumber(raw, fallback, WatchExpressionScope.EMPTY, resolveTag)

    fun evaluateNumber(
        raw: String?,
        fallback: Float,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): Float {
        val value = evaluate(raw, scope, resolveTag) ?: return fallback
        val number = value.asNumber() ?: return fallback
        return number.toFloat().takeIf(Float::isFinite) ?: fallback
    }

    fun evaluateBoolean(
        raw: String?,
        fallback: Boolean,
        resolveTag: (String) -> WatchTagResolution,
    ): Boolean = evaluateBoolean(raw, fallback, WatchExpressionScope.EMPTY, resolveTag)

    fun evaluateBoolean(
        raw: String?,
        fallback: Boolean,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): Boolean {
        val value = evaluate(raw, scope, resolveTag) ?: return fallback
        return when (value) {
            is ExpressionValue.Logical -> value.value
            is ExpressionValue.Numeric -> value.value != 0.0
            is ExpressionValue.Text -> when {
                value.value.equals("true", true) -> true
                value.value.equals("false", true) -> false
                value.value.toDoubleOrNull() != null -> value.value.toDouble() != 0.0
                else -> value.value.isNotEmpty()
            }
            ExpressionValue.Nil -> false
        }
    }

    fun supports(raw: String): Boolean = supports(raw, WatchExpressionScope.EMPTY)

    fun supports(raw: String, scope: WatchExpressionScope): Boolean {
        if (raw.isBlank()) return true
        if (raw.length > MAX_EXPRESSION_LENGTH) return false
        val candidate = substituteTags(raw) { WatchTagResolution.Resolved("1") } ?: return false
        return runCatching { Parser(candidate, scope).parse() }.isSuccess
    }

    private fun evaluate(
        raw: String?,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): ExpressionValue? {
        val source = raw?.trim().orEmpty()
        if (source.isBlank() || source.length > MAX_EXPRESSION_LENGTH) return null
        val substituted = try { substituteTags(source, resolveTag) } catch (_: Throwable) { null }
            ?: return null
        return runCatching { Parser(substituted, scope).parse() }.getOrNull()
    }

    /**
     * WatchMaker permits tags both as values and inside quoted strings. A
     * regex-only substitution corrupts the latter (`'{da}'` became a nested
     * quoted literal), so scan once while tracking the active quote.
     */
    private fun substituteTags(
        raw: String,
        resolveTag: (String) -> WatchTagResolution,
    ): String? {
        if (raw.length > MAX_EXPRESSION_LENGTH) return null
        val output = StringBuilder(raw.length + 32)
        var index = 0
        var quote: Char? = null
        while (index < raw.length) {
            val char = raw[index]
            if (char == '\u00a0' && quote == null) {
                output.append(' ')
                index++
                continue
            }
            if (char == '\\' && quote != null && index + 1 < raw.length) {
                output.append(char).append(raw[index + 1])
                index += 2
                continue
            }
            if (char == '\'' || char == '"') {
                if (quote == null) quote = char else if (quote == char) quote = null
                output.append(char)
                index++
                continue
            }
            if (char == '{') {
                val closing = raw.indexOf('}', startIndex = index + 1)
                if (closing > index + 1 && raw.substring(index + 1, closing).none { it == '{' }) {
                    val tag = raw.substring(index + 1, closing)
                    val resolution = resolveTag(tag)
                    if (quote == null) {
                        output.append(
                            when (resolution) {
                                is WatchTagResolution.Resolved -> resolution.renderedValue.toExpressionLiteral()
                                is WatchTagResolution.Unavailable,
                                is WatchTagResolution.Unsupported -> "nil"
                            },
                        )
                    } else {
                        val value = (resolution as? WatchTagResolution.Resolved)?.renderedValue.orEmpty()
                        output.append(value.escapeForQuote(quote))
                    }
                    index = closing + 1
                    continue
                }
            }
            output.append(char)
            index++
            if (output.length > MAX_EXPRESSION_LENGTH * 2) return null
        }
        if (quote != null) return null
        return output.toString()
    }

    private fun String.escapeForQuote(quote: Char): String = buildString(length) {
        this@escapeForQuote.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                quote -> append('\\').append(char)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private fun String.toExpressionLiteral(): String {
        val value = trim()
        if (value.toDoubleOrNull()?.isFinite() == true) return value
        if (value.equals("true", true) || value.equals("false", true) || value == "nil") {
            return value.lowercase(Locale.ROOT)
        }
        return buildString(value.length + 2) {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }

    private sealed interface ExpressionValue {
        fun asNumber(): Double?
        fun isTruthy(): Boolean
        fun renderText(): String

        data class Numeric(val value: Double) : ExpressionValue {
            override fun asNumber(): Double = value
            override fun isTruthy(): Boolean = true
            override fun renderText(): String = when {
                value.isFinite() && value == floor(value) && abs(value) <= 9_007_199_254_740_992.0 ->
                    value.toLong().toString()
                value.isFinite() -> String.format(Locale.ROOT, "%.6g", value)
                else -> ""
            }
        }

        data class Text(val value: String) : ExpressionValue {
            override fun asNumber(): Double? = value.toDoubleOrNull()
            override fun isTruthy(): Boolean = true
            override fun renderText(): String = value
        }

        data class Logical(val value: Boolean) : ExpressionValue {
            override fun asNumber(): Double = if (value) 1.0 else 0.0
            override fun isTruthy(): Boolean = value
            override fun renderText(): String = value.toString()
        }

        data object Nil : ExpressionValue {
            override fun asNumber(): Double? = null
            override fun isTruthy(): Boolean = false
            override fun renderText(): String = ""
        }
    }

    private enum class TokenType {
        NUMBER,
        STRING,
        TRUE,
        FALSE,
        NIL,
        IDENTIFIER,
        AND,
        OR,
        NOT,
        PLUS,
        MINUS,
        STAR,
        SLASH,
        PERCENT,
        POWER,
        CONCAT,
        DOT,
        COMMA,
        EQUAL,
        NOT_EQUAL,
        GREATER,
        GREATER_EQUAL,
        LESS,
        LESS_EQUAL,
        LEFT_PAREN,
        RIGHT_PAREN,
        END,
    }

    private data class Token(val type: TokenType, val text: String)

    private class Lexer(private val source: String) {
        private var index = 0

        fun tokens(): List<Token> = buildList {
            while (true) {
                require(size < MAX_EXPRESSION_TOKENS) { "Expression has too many tokens" }
                val token = nextToken()
                add(token)
                if (token.type == TokenType.END) break
            }
        }

        private fun nextToken(): Token {
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length) return Token(TokenType.END, "")
            val start = index
            val char = source[index++]
            return when (char) {
                '+' -> Token(TokenType.PLUS, "+")
                '-' -> Token(TokenType.MINUS, "-")
                '*' -> Token(TokenType.STAR, "*")
                '/' -> Token(TokenType.SLASH, "/")
                '%' -> Token(TokenType.PERCENT, "%")
                '^' -> Token(TokenType.POWER, "^")
                ',' -> Token(TokenType.COMMA, ",")
                '(' -> Token(TokenType.LEFT_PAREN, "(")
                ')' -> Token(TokenType.RIGHT_PAREN, ")")
                '=' -> {
                    require(match('=')) { "Assignment is not supported" }
                    Token(TokenType.EQUAL, "==")
                }
                '!', '~' -> {
                    require(match('=')) { "Unsupported operator at $start" }
                    Token(TokenType.NOT_EQUAL, source.substring(start, index))
                }
                '>' -> if (match('=')) Token(TokenType.GREATER_EQUAL, ">=") else Token(TokenType.GREATER, ">")
                '<' -> if (match('=')) Token(TokenType.LESS_EQUAL, "<=") else Token(TokenType.LESS, "<")
                '\'', '"' -> readString(char)
                '.' -> when {
                    match('.') -> Token(TokenType.CONCAT, "..")
                    index < source.length && source[index].isDigit() -> readNumber(start)
                    else -> Token(TokenType.DOT, ".")
                }
                else -> when {
                    char.isDigit() -> readNumber(start)
                    char.isLetter() || char == '_' -> readIdentifier(start)
                    else -> error("Unsupported expression character: $char")
                }
            }
        }

        private fun readNumber(start: Int): Token {
            var hasDecimalPoint = source[start] == '.'
            while (index < source.length) {
                val char = source[index]
                when {
                    char.isDigit() -> index++
                    char == '.' && !hasDecimalPoint &&
                        (index + 1 >= source.length || source[index + 1] != '.') -> {
                        hasDecimalPoint = true
                        index++
                    }
                    else -> break
                }
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                while (index < source.length && source[index].isDigit()) index++
            }
            val text = source.substring(start, index)
            require(text.toDoubleOrNull()?.isFinite() == true) { "Invalid number: $text" }
            return Token(TokenType.NUMBER, text)
        }

        private fun readIdentifier(start: Int): Token {
            while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
            val text = source.substring(start, index)
            val type = when (text.lowercase(Locale.ROOT)) {
                "true" -> TokenType.TRUE
                "false" -> TokenType.FALSE
                "nil" -> TokenType.NIL
                "and" -> TokenType.AND
                "or" -> TokenType.OR
                "not" -> TokenType.NOT
                else -> TokenType.IDENTIFIER
            }
            return Token(type, text)
        }

        private fun readString(quote: Char): Token {
            val result = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                if (char == quote) return Token(TokenType.STRING, result.toString())
                if (char == '\\') {
                    require(index < source.length) { "Unterminated escape" }
                    result.append(
                        when (val escaped = source[index++]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            '\\', '\'', '"' -> escaped
                            else -> escaped
                        },
                    )
                } else {
                    result.append(char)
                }
            }
            error("Unterminated string")
        }

        private fun match(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index++
            return true
        }
    }

    private class Parser(
        source: String,
        private val scope: WatchExpressionScope,
    ) {
        private val tokens = Lexer(source).tokens()
        private var index = 0

        fun parse(): ExpressionValue {
            val value = parseOr()
            require(peek().type == TokenType.END) { "Unexpected token: ${peek().text}" }
            return value
        }

        private fun parseOr(): ExpressionValue {
            var value = parseAnd()
            while (match(TokenType.OR)) {
                val right = parseAnd()
                value = if (value.isTruthy()) value else right
            }
            return value
        }

        private fun parseAnd(): ExpressionValue {
            var value = parseNot()
            while (match(TokenType.AND)) {
                val right = parseNot()
                value = if (value.isTruthy()) right else value
            }
            return value
        }

        /** WatchMaker/WFTools treats `not` as applying to the following comparison. */
        private fun parseNot(): ExpressionValue =
            if (match(TokenType.NOT)) ExpressionValue.Logical(!parseNot().isTruthy()) else parseComparison()

        private fun parseComparison(): ExpressionValue {
            var value = parseConcat()
            while (peek().type in COMPARISON_TYPES) {
                val operator = advance().type
                val right = parseConcat()
                value = ExpressionValue.Logical(compare(value, right, operator))
            }
            return value
        }

        /** Lua concatenation is right-associative and lower precedence than arithmetic. */
        private fun parseConcat(): ExpressionValue {
            val left = parseAdditive()
            if (!match(TokenType.CONCAT)) return left
            val right = parseConcat()
            val text = left.renderText() + right.renderText()
            require(text.length <= MAX_RESULT_TEXT_LENGTH) { "Expression text result is too large" }
            return ExpressionValue.Text(text)
        }

        private fun parseAdditive(): ExpressionValue {
            var value = parseMultiplicative()
            while (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS) {
                val operator = advance().type
                val leftNumber = value.asNumber() ?: error("Expected number")
                val rightNumber = parseMultiplicative().asNumber() ?: error("Expected number")
                value = numericValue(
                    if (operator == TokenType.PLUS) leftNumber + rightNumber else leftNumber - rightNumber,
                )
            }
            return value
        }

        private fun parseMultiplicative(): ExpressionValue {
            var value = parseUnary()
            while (peek().type in MULTIPLICATIVE_TYPES) {
                val operator = advance().type
                val leftNumber = value.asNumber() ?: error("Expected number")
                val rightNumber = parseUnary().asNumber() ?: error("Expected number")
                value = numericValue(
                    when (operator) {
                        TokenType.STAR -> leftNumber * rightNumber
                        TokenType.SLASH -> {
                            require(abs(rightNumber) > Double.MIN_VALUE) { "Division by zero" }
                            leftNumber / rightNumber
                        }
                        TokenType.PERCENT -> {
                            require(abs(rightNumber) > Double.MIN_VALUE) { "Division by zero" }
                            leftNumber % rightNumber
                        }
                        else -> error("Unexpected operator")
                    },
                )
            }
            return value
        }

        private fun parsePower(): ExpressionValue {
            val left = parsePrimary()
            if (!match(TokenType.POWER)) return left
            val right = parseUnary().asNumber() ?: error("Expected number")
            return numericValue((left.asNumber() ?: error("Expected number")).pow(right))
        }

        private fun parseUnary(): ExpressionValue = when {
            match(TokenType.MINUS) -> numericValue(-(parseUnary().asNumber() ?: error("Expected number")))
            match(TokenType.PLUS) -> numericValue(parseUnary().asNumber() ?: error("Expected number"))
            else -> parsePower()
        }

        private fun parsePrimary(): ExpressionValue {
            val token = advance()
            return when (token.type) {
                TokenType.NUMBER -> ExpressionValue.Numeric(token.text.toDouble())
                TokenType.STRING -> ExpressionValue.Text(token.text)
                TokenType.TRUE -> ExpressionValue.Logical(true)
                TokenType.FALSE -> ExpressionValue.Logical(false)
                TokenType.NIL -> ExpressionValue.Nil
                TokenType.LEFT_PAREN -> parseOr().also {
                    require(match(TokenType.RIGHT_PAREN)) { "Missing ')'" }
                }
                TokenType.IDENTIFIER -> parseIdentifier(token.text)
                else -> error("Expected value, found ${token.text}")
            }
        }

        private fun parseIdentifier(first: String): ExpressionValue {
            val qualified = StringBuilder(first)
            while (match(TokenType.DOT)) {
                val segment = advance()
                require(segment.type == TokenType.IDENTIFIER) { "Expected identifier after '.'" }
                qualified.append('.').append(segment.text)
            }
            val name = qualified.toString().lowercase(Locale.ROOT)
            if (!match(TokenType.LEFT_PAREN)) {
                return when (name) {
                    "math.pi" -> ExpressionValue.Numeric(Math.PI)
                    "math.huge" -> ExpressionValue.Numeric(Double.MAX_VALUE)
                    else -> scope.resolve(name)?.toScopedExpressionValue()
                        ?: error("Bare identifiers and script variables are not supported: $name")
                }
            }

            val arguments = ArrayList<ExpressionValue>()
            if (!match(TokenType.RIGHT_PAREN)) {
                do {
                    require(arguments.size < MAX_FUNCTION_ARGUMENTS) { "Too many function arguments" }
                    arguments += parseOr()
                } while (match(TokenType.COMMA))
                require(match(TokenType.RIGHT_PAREN)) { "Missing ')' after function arguments" }
            }
            return callFunction(name, arguments)
        }

        private fun callFunction(name: String, arguments: List<ExpressionValue>): ExpressionValue {
            fun arg(index: Int, fallback: ExpressionValue = ExpressionValue.Numeric(0.0)): ExpressionValue =
                arguments.getOrElse(index) { fallback }
            fun number(index: Int, fallback: Double = 0.0): Double =
                arguments.getOrNull(index)?.asNumber() ?: fallback
            fun text(index: Int): String = arguments.getOrNull(index)?.renderText().orEmpty()

            return when (name) {
                "math.floor" -> numericValue(floor(number(0)))
                "math.ceil" -> numericValue(ceil(number(0)))
                "math.abs" -> numericValue(abs(number(0)))
                "math.sqrt" -> numericValue(sqrt(number(0)))
                "math.max" -> numericValue(arguments.maxOfOrNull { it.asNumber() ?: 0.0 } ?: 0.0)
                "math.min" -> numericValue(arguments.minOfOrNull { it.asNumber() ?: 0.0 } ?: 0.0)
                "math.mod", "math.fmod" -> {
                    val divisor = number(1, 1.0)
                    require(abs(divisor) > Double.MIN_VALUE) { "Division by zero" }
                    numericValue(number(0) % divisor)
                }
                "math.pow" -> numericValue(number(0, 1.0).pow(number(1, 1.0)))
                "math.sin" -> numericValue(sin(number(0)))
                "math.cos" -> numericValue(cos(number(0)))
                "math.tan" -> numericValue(tan(number(0)))
                "math.asin" -> numericValue(asin(number(0)))
                "math.acos" -> numericValue(acos(number(0)))
                "math.atan" -> if (arguments.size >= 2) {
                    numericValue(atan2(number(0), number(1)))
                } else {
                    numericValue(atan(number(0)))
                }
                "math.atan2" -> numericValue(atan2(number(0), number(1, 1.0)))
                "math.rad" -> numericValue(Math.toRadians(number(0)))
                "math.deg" -> numericValue(Math.toDegrees(number(0)))
                "math.exp" -> numericValue(exp(number(0)))
                "math.log" -> {
                    val value = number(0)
                    require(value > 0.0) { "Logarithm input must be positive" }
                    val base = arguments.getOrNull(1)?.asNumber()
                    numericValue(if (base == null) ln(value) else ln(value) / ln(base))
                }
                "string.len" -> numericValue(text(0).length.toDouble())
                "string.upper" -> ExpressionValue.Text(text(0).uppercase(Locale.ROOT))
                "string.lower" -> ExpressionValue.Text(text(0).lowercase(Locale.ROOT))
                "string.sub" -> ExpressionValue.Text(luaSubstring(text(0), number(1, 1.0), arguments.getOrNull(2)?.asNumber()))
                "string.rep" -> {
                    val count = number(1).toInt().coerceIn(0, MAX_RESULT_TEXT_LENGTH)
                    val unit = text(0)
                    require(unit.length.toLong() * count <= MAX_RESULT_TEXT_LENGTH) {
                        "Repeated string is too large"
                    }
                    ExpressionValue.Text(unit.repeat(count))
                }
                "string.find" -> {
                    val start = luaStringIndex(number(2, 1.0).toInt(), text(0).length)
                    val found = text(0).indexOf(text(1), startIndex = start.coerceAtMost(text(0).length))
                    if (found < 0) ExpressionValue.Nil else numericValue((found + 1).toDouble())
                }
                "string.format" -> ExpressionValue.Text(formatLuaString(text(0), arguments.drop(1)))
                "tonumber" -> arg(0).asNumber()?.let(::numericValue) ?: ExpressionValue.Nil
                "tostring" -> ExpressionValue.Text(arg(0, ExpressionValue.Nil).renderText())
                "type" -> ExpressionValue.Text(
                    when (arg(0, ExpressionValue.Nil)) {
                        is ExpressionValue.Numeric -> "number"
                        is ExpressionValue.Text -> "string"
                        is ExpressionValue.Logical -> "boolean"
                        ExpressionValue.Nil -> "nil"
                    },
                )
                else -> error("Unsupported function: $name")
            }
        }

        private fun luaSubstring(value: String, fromRaw: Double, toRaw: Double?): String {
            val length = value.length
            val from = luaStringIndex(fromRaw.toInt(), length)
            val inclusiveTo = toRaw?.toInt()?.let { index ->
                if (index < 0) length + index else index - 1
            } ?: (length - 1)
            val endExclusive = (inclusiveTo + 1).coerceIn(0, length)
            return if (from < endExclusive) value.substring(from, endExclusive) else ""
        }

        private fun luaStringIndex(raw: Int, length: Int): Int =
            (if (raw < 0) length + raw else raw - 1).coerceIn(0, length)

        private fun formatLuaString(
            format: String,
            arguments: List<ExpressionValue>,
        ): String {
            require(format.length <= MAX_RESULT_TEXT_LENGTH) { "Format string is too large" }
            val output = StringBuilder(format.length + 16)
            var index = 0
            var argumentIndex = 0
            while (index < format.length) {
                if (format[index] != '%') {
                    output.append(format[index++])
                    continue
                }
                if (index + 1 < format.length && format[index + 1] == '%') {
                    output.append('%')
                    index += 2
                    continue
                }
                val specStart = index++
                while (index < format.length && format[index] in "-+ #0") index++
                val widthStart = index
                while (index < format.length && format[index].isDigit()) index++
                val width = format.substring(widthStart, index).toIntOrNull() ?: 0
                require(width <= 128) { "Format width is too large" }
                if (index < format.length && format[index] == '.') {
                    index++
                    val precisionStart = index
                    while (index < format.length && format[index].isDigit()) index++
                    val precision = format.substring(precisionStart, index).toIntOrNull() ?: 0
                    require(precision <= 32) { "Format precision is too large" }
                }
                require(index < format.length) { "Incomplete format specifier" }
                val type = format[index++]
                require(type in "diufFeEgGsq") { "Unsupported format type: $type" }
                val argument = arguments.getOrNull(argumentIndex++) ?: ExpressionValue.Numeric(0.0)
                val spec = format.substring(specStart, index)
                val rendered = when (type) {
                    'd', 'i' -> String.format(Locale.ROOT, spec.dropLast(1) + "d", (argument.asNumber() ?: 0.0).toLong())
                    'u' -> (argument.asNumber() ?: 0.0).toLong().toULong().toString()
                    'f', 'F', 'e', 'E', 'g', 'G' ->
                        String.format(Locale.ROOT, spec, argument.asNumber() ?: 0.0)
                    's' -> String.format(Locale.ROOT, spec, argument.renderText())
                    'q' -> "\"${argument.renderText().replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    else -> error("Unsupported format type")
                }
                output.append(rendered)
                require(output.length <= MAX_RESULT_TEXT_LENGTH) { "Formatted text is too large" }
            }
            return output.toString()
        }

        private fun numericValue(value: Double): ExpressionValue.Numeric {
            require(value.isFinite()) { "Expression result is not finite" }
            return ExpressionValue.Numeric(value)
        }

        private fun String.toScopedExpressionValue(): ExpressionValue = when {
            equals("true", true) -> ExpressionValue.Logical(true)
            equals("false", true) -> ExpressionValue.Logical(false)
            this == "nil" -> ExpressionValue.Nil
            toDoubleOrNull()?.isFinite() == true -> ExpressionValue.Numeric(toDouble())
            else -> ExpressionValue.Text(this)
        }

        private fun compare(left: ExpressionValue, right: ExpressionValue, operator: TokenType): Boolean {
            if (operator == TokenType.EQUAL || operator == TokenType.NOT_EQUAL) {
                val equal = when {
                    left === ExpressionValue.Nil || right === ExpressionValue.Nil -> left === right
                    left.asNumber() != null && right.asNumber() != null -> left.asNumber() == right.asNumber()
                    left is ExpressionValue.Text && right is ExpressionValue.Text -> left.value == right.value
                    left is ExpressionValue.Logical && right is ExpressionValue.Logical -> left.value == right.value
                    else -> false
                }
                return if (operator == TokenType.EQUAL) equal else !equal
            }
            val leftNumber = left.asNumber() ?: error("Expected number")
            val rightNumber = right.asNumber() ?: error("Expected number")
            return when (operator) {
                TokenType.GREATER -> leftNumber > rightNumber
                TokenType.GREATER_EQUAL -> leftNumber >= rightNumber
                TokenType.LESS -> leftNumber < rightNumber
                TokenType.LESS_EQUAL -> leftNumber <= rightNumber
                else -> error("Unexpected comparison")
            }
        }

        private fun peek(): Token = tokens[index]

        private fun advance(): Token = tokens[index++]

        private fun match(type: TokenType): Boolean {
            if (peek().type != type) return false
            index++
            return true
        }

        companion object {
            private val COMPARISON_TYPES = setOf(
                TokenType.EQUAL,
                TokenType.NOT_EQUAL,
                TokenType.GREATER,
                TokenType.GREATER_EQUAL,
                TokenType.LESS,
                TokenType.LESS_EQUAL,
            )
            private val MULTIPLICATIVE_TYPES = setOf(
                TokenType.STAR,
                TokenType.SLASH,
                TokenType.PERCENT,
            )
        }
    }
}

/** A compact compatibility report surfaced to editor/diagnostics callers. */
data class WatchCompatibilityReport(
    val unsupportedLayerTypes: Set<String>,
    val unsupportedTags: Set<String>,
    val unsupportedExpressions: Set<String>,
) {
    val isFullySupported: Boolean
        get() = unsupportedLayerTypes.isEmpty() &&
            unsupportedTags.isEmpty() &&
            unsupportedExpressions.isEmpty()
}
