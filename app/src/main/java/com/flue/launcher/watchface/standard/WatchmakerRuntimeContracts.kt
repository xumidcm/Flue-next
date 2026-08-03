package com.flue.launcher.watchface.standard

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

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
 * 这一层的文字要不要走连续坐标栅格化（Android 的 `Paint.LINEAR_TEXT_FLAG`）。
 *
 * 判据只有一个：合成后的两轴缩放**不相等**。非等比缩放下，字形先被 hinting
 * 对齐到整数像素网格、再被横向压扁，笔画粗细就不均匀了 —— wf528 的手写体
 * Patrick Hand 配 `anim_scale_x="80"` 正是这个症状。关掉 hinting 让字形在
 * 连续坐标下栅格化，压扁后粗细才均匀。
 *
 * 反过来，等比缩放时**绝不能开**：这个标志会连带关掉字形缓存。312 有 181 个
 * text 图层且一个 `anim_scale_x` 都没有，无条件打开等于让它们每帧重新栅格化，
 * 白付成本还拖帧。
 *
 * 传入的缩放是「画布基变换 × 祖先组链 × 图层自身」的累乘，所以 STRETCH 预设
 * 和画布调节里 zoomX≠zoomY 造成的非等比也一并覆盖。
 */
fun watchTextNeedsLinearRaster(scaleX: Float, scaleY: Float): Boolean {
    // 镜像（负缩放）不改变笔画粗细，比绝对值。
    val sx = abs(scaleX)
    val sy = abs(scaleY)
    if (!sx.isFinite() || !sy.isFinite() || sx <= 0f || sy <= 0f) return false
    // 容差按较大轴取 1%：画布变换算出来的比例带浮点尾数，别让等比缩放误判成
    // 非等比而白白丢掉字形缓存。
    return abs(sx - sy) > max(sx, sy) * 0.01f
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

/**
 * 设计画布 → 视口的变换。
 *
 * **双轴** scale：`STRETCH` 预设下两轴可以不等，所以这里没有单一的 `scale`
 * 属性 —— 故意不提供，免得调用点在非等比预设下静默读到一个「大概对」的值。
 * 需要等比语义的地方自己判 `scaleX == scaleY`。
 */
internal data class WatchCanvasTransform(
    val scaleX: Float,
    val scaleY: Float,
    val left: Float,
    val top: Float,
)

/**
 * 画布贴合预设。
 *
 * 中间四个是对齐 clox 语义的预设；[FIT] / [FILL] 是 Flue 存量的
 * `forceFill = false / true` 两档，保留它们才能让老配置零迁移。
 *
 * 方屏上 [FIT] / [VERTICAL] / [HORIZONTAL] 会算出同一个结果，这是预期的：
 * 差别只在圆屏与异形屏（非方视口）上才显现。
 */
enum class WatchCanvasPreset {
    /** 按短边贴合，长边留居中黑边。整块画布都可见。旧 `forceFill = false`。 */
    FIT,

    /** 按长边铺满，短边溢出被裁（居中裁切）。旧 `forceFill = true`。 */
    FILL,

    /** 按**高度**贴合：垂直铺满，水平可能溢出或留边。 */
    VERTICAL,

    /** 按**宽度**贴合：水平铺满，垂直可能溢出或留边。 */
    HORIZONTAL,

    /** 双轴各自铺满，**可非等比**（会形变）。 */
    STRETCH,

    /** 基准恒为 1，完全交给 zoom 滑杆决定。 */
    CUSTOM,
}

/**
 * 一套完整的画布调节参数。
 *
 * [DEFAULT] 必须等价于改动前的 `forceFill = false`，这样没配置过的表盘
 * 位置一个像素都不动。
 */
data class WatchCanvasSettings(
    val preset: WatchCanvasPreset = WatchCanvasPreset.FIT,
    val zoomX: Float = 1f,
    val zoomY: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** 是否与出厂默认完全一致 —— 供 UI 决定要不要显示「已自定义」标记。 */
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = WatchCanvasSettings()

        /** 存量全局开关的等价配置，供迁移期使用。 */
        fun fromForceFill(forceFill: Boolean): WatchCanvasSettings = WatchCanvasSettings(
            preset = if (forceFill) WatchCanvasPreset.FILL else WatchCanvasPreset.FIT,
        )

        /**
         * 解析 [WatchCanvasSettings.encode] 的输出。
         *
         * 任何无法识别的字段都退回该字段的默认值而**不是**整体失败：这是持久化
         * 偏好，为一个坏字段丢掉用户其余四项调节不值得。数值范围不在这里收敛
         * —— 统一由 [watchCanvasTransform] 的兜底负责，免得两处上下限漂移。
         */
        fun decode(raw: String?): WatchCanvasSettings {
            val parts = raw?.takeIf { it.isNotBlank() }?.split('|') ?: return DEFAULT
            val preset = parts.getOrNull(0)?.let { name ->
                WatchCanvasPreset.entries.firstOrNull { it.name == name }
            } ?: WatchCanvasPreset.FIT
            return WatchCanvasSettings(
                preset = preset,
                zoomX = parts.getOrNull(1)?.toFloatOrNull() ?: 1f,
                zoomY = parts.getOrNull(2)?.toFloatOrNull() ?: 1f,
                offsetX = parts.getOrNull(3)?.toFloatOrNull() ?: 0f,
                offsetY = parts.getOrNull(4)?.toFloatOrNull() ?: 0f,
            )
        }
    }

    /**
     * 扁平字符串编码，供 DataStore 存单个 key。
     *
     * 用 `|` 分隔而不是 JSON：五个标量不值得拖一个序列化框架进来，而预设名与
     * 浮点数的字面量里都不可能出现 `|`。
     */
    fun encode(): String = "${preset.name}|$zoomX|$zoomY|$offsetX|$offsetY"
}

/**
 * Maps WatchMaker's design canvas into the current viewport.
 *
 * 预设决定**基准** scale，[zoomX] / [zoomY] 再乘上去，[offsetX] / [offsetY] 加在
 * 居中后的偏移上。默认参数（[WatchCanvasPreset.FIT]、zoom 1、offset 0）的输出
 * 与拆双轴之前**逐位相同**，所以存量表盘一个像素都不会动。
 *
 * [designWidth] / [designHeight] default to the square native canvas, so every
 * existing caller keeps its exact behaviour. A face that declares a genuinely
 * non-square canvas passes its own design extents (see
 * `WatchCanvasSize.designExtentWidth`).
 *
 * 只有 [WatchCanvasPreset.STRETCH] 与「zoomX ≠ zoomY」会产生非等比缩放；其余
 * 预设两轴恒等，不会把表盘拉变形。
 */
internal fun watchCanvasTransform(
    viewWidth: Float,
    viewHeight: Float,
    preset: WatchCanvasPreset = WatchCanvasPreset.FIT,
    designWidth: Float = WatchmakerGeometry.DESIGN_SIZE,
    designHeight: Float = WatchmakerGeometry.DESIGN_SIZE,
    zoomX: Float = 1f,
    zoomY: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
): WatchCanvasTransform {
    require(viewWidth.isFinite() && viewWidth > 0f) { "viewWidth must be positive" }
    require(viewHeight.isFinite() && viewHeight > 0f) { "viewHeight must be positive" }
    require(designWidth.isFinite() && designWidth > 0f) { "designWidth must be positive" }
    require(designHeight.isFinite() && designHeight > 0f) { "designHeight must be positive" }
    val fitX = viewWidth / designWidth
    val fitY = viewHeight / designHeight
    // 基准 scale：前四个预设等比（两轴同值），STRETCH 各轴独立，CUSTOM 恒 1。
    val (baseX, baseY) = when (preset) {
        WatchCanvasPreset.FIT -> min(fitX, fitY).let { it to it }
        WatchCanvasPreset.FILL -> max(fitX, fitY).let { it to it }
        WatchCanvasPreset.VERTICAL -> fitY to fitY
        WatchCanvasPreset.HORIZONTAL -> fitX to fitX
        WatchCanvasPreset.STRETCH -> fitX to fitY
        WatchCanvasPreset.CUSTOM -> 1f to 1f
    }
    // 缩放倍率兜底：滑杆值可能来自持久化的脏数据，0 或 NaN 会让整块表盘消失
    // 且无法在 UI 上退回来（画布没了，也就点不中重置）。
    val scaleX = baseX * zoomX.sanitizedCanvasZoom()
    val scaleY = baseY * zoomY.sanitizedCanvasZoom()
    return WatchCanvasTransform(
        scaleX = scaleX,
        scaleY = scaleY,
        left = (viewWidth - designWidth * scaleX) / 2f + offsetX.sanitizedCanvasOffset(),
        top = (viewHeight - designHeight * scaleY) / 2f + offsetY.sanitizedCanvasOffset(),
    )
}

/**
 * 存量二值签名，等价于 [WatchCanvasPreset.FIT] / [WatchCanvasPreset.FILL]。
 *
 * 保留它让既有调用点与单元测试不必全改，也让「可调画布」这一层可以整体回滚。
 */
internal fun watchCanvasTransform(
    viewWidth: Float,
    viewHeight: Float,
    forceFill: Boolean,
    designWidth: Float = WatchmakerGeometry.DESIGN_SIZE,
    designHeight: Float = WatchmakerGeometry.DESIGN_SIZE,
): WatchCanvasTransform = watchCanvasTransform(
    viewWidth = viewWidth,
    viewHeight = viewHeight,
    preset = if (forceFill) WatchCanvasPreset.FILL else WatchCanvasPreset.FIT,
    designWidth = designWidth,
    designHeight = designHeight,
)

/** [WatchCanvasSettings] 版重载，渲染器与编辑器都走它。 */
internal fun watchCanvasTransform(
    viewWidth: Float,
    viewHeight: Float,
    settings: WatchCanvasSettings,
    designWidth: Float = WatchmakerGeometry.DESIGN_SIZE,
    designHeight: Float = WatchmakerGeometry.DESIGN_SIZE,
): WatchCanvasTransform = watchCanvasTransform(
    viewWidth = viewWidth,
    viewHeight = viewHeight,
    preset = settings.preset,
    designWidth = designWidth,
    designHeight = designHeight,
    zoomX = settings.zoomX,
    zoomY = settings.zoomY,
    offsetX = settings.offsetX,
    offsetY = settings.offsetY,
)

/** 缩放倍率的合法区间。下限不取 0：0 会让画布彻底消失且无法交互退回。 */
private const val MIN_CANVAS_ZOOM = 0.1f
private const val MAX_CANVAS_ZOOM = 5f

/**
 * 偏移上限（**视图像素**，因为它直接加在 view 空间的 `left`/`top` 上）。
 *
 * 4096 远大于任何手表屏，够用；它挡的是脏配置把表盘整块推出屏幕后再也调不回来。
 */
private const val MAX_CANVAS_OFFSET = 4_096f

private fun Float.sanitizedCanvasZoom(): Float =
    if (isFinite()) coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM) else 1f

private fun Float.sanitizedCanvasOffset(): Float =
    if (isFinite()) coerceIn(-MAX_CANVAS_OFFSET, MAX_CANVAS_OFFSET) else 0f

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
    /**
     * 日步数目标，供 `{stsc}` / `{sscp}` 使用。null 表示没有用户档案，解析器
     * 回落到 [WatchmakerTagResolver] 的规范默认值（对齐 clox
     * `watchface_bridge.lua:127` 的 `stepGoal = 10000`）。
     */
    val stepGoal: Int? = null,
    val heartRateBpm: Int? = null,
    val gyroscopeX: Float? = null,
    val gyroscopeY: Float? = null,
    val gyroscopeZ: Float? = null,
    val wifiSignalFraction: Float? = null,
    val bluetoothEnabled: Boolean? = null,
    /**
     * 未读通知数，供 `{cnc}` 使用。
     *
     * standard 包是**单向依赖**的渲染层，不能反过来 import 启动器的
     * NotificationListenerService；由宿主（provider 实现）把计数推进快照里。
     */
    val notificationCount: Int? = null,
    /**
     * 系统是否 24 小时制，供 `{d24h}` 使用。
     *
     * `DateFormat.is24HourFormat()` 需要 Context，而 [WatchmakerTagResolver.resolve]
     * 是纯函数（可测、可在编辑器预览里跑），所以在 provider 侧读一次塞进快照。
     */
    val is24Hour: Boolean? = null,
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

/**
 * HSC downloader 的转换包把图层坐标/height 放大到了 512 设计空间，但保留了
 * 原始 text_size。位图字体不能沿用较小的 text_size，否则数字和时间会缩小。
 * 普通 WatchMaker 包不进入这条兼容路径，避免误放大原生 BMFont。
 */
internal fun watchBitmapTextDesignSize(
    requestedSize: Float,
    declaredLayerHeight: Float?,
    bitmapFontNominalSize: Float?,
    hscGenerated: Boolean,
): Float {
    val requested = requestedSize.takeIf(Float::isFinite)
        ?.coerceIn(1f, WatchmakerGeometry.DESIGN_SIZE)
        ?: 1f
    if (!hscGenerated) return requested
    val height = declaredLayerHeight?.takeIf(Float::isFinite)
    val nominal = bitmapFontNominalSize?.takeIf(Float::isFinite)
    // HSC 的 height 是 512 画布中的实际字框高，BMFont nominal size 则是
    // 原图生成字号。两者都比残留的 text_size 更可信；取较大者才能同时覆盖
    // 示例中 f1(height=86, nominal=45) 与 f2/f3(height≈180, nominal=180)。
    return maxOf(requested, height ?: requested, nominal ?: requested)
        .coerceIn(1f, WatchmakerGeometry.DESIGN_SIZE)
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
    /**
     * 日出/日落时刻，`HH:mm` 本地时间字符串，供 `{wsr}` / `{wss}` 使用。
     *
     * 与 clox 一致（`WeatherProvider.kt:368` `parseIsoTime` 取 ISO 串的 `HH:mm`
     * 部分，`wf_tagdefs.lua:323-324` 直接把它当字符串渲染）。
     */
    val sunriseText: String? = null,
    val sunsetText: String? = null,
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

/**
 * `{cnc}`（未读通知数）的注入 seam。
 *
 * 与 [WatchWeatherSources] 同构，理由也一样：`standard` 包是**单向依赖**的渲染层，
 * 不能反过来 import 启动器的 `NotificationListenerService`。宿主监听到通知变化后
 * 调 [publish]，渲染层只读。
 *
 * 没有宿主发布过时返回 `0` 而不是 null —— 与 `{shr}` 的处理一致：表盘会写
 * `{cnc} > 0` 这类表达式，代入 nil 会让整条表达式失效，进而让 opacity 回落到
 * 默认 100（历史事故：九个天气图标同时全亮）。0 让算式照常算。
 */
object WatchNotificationCounts {
    @Volatile
    private var count: Int = 0

    fun publish(value: Int) {
        count = value.coerceAtLeast(0)
    }

    internal fun current(): Int = count
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
    // WatchMaker temperature tags are display values, not raw sensor samples.
    // Keeping fractional source precision here leaked strings such as
    // `23.6000°` through legacy/JB expressions. Round once at the shared
    // resolver boundary so the runtime, editor and every forecast alias agree.
    return round(value).toLong().toString()
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
 * Generation gate for handing an editor's live transform to a freshly parsed
 * watch source. Both descriptor and refresh token participate so a late load
 * from another face can never clear the transform currently under the finger.
 */
class WatchLiveTransformHandoff {
    private data class SourceIdentity(
        val descriptorKey: String,
        val refreshToken: Int,
    )

    private var pending: SourceIdentity? = null

    fun arm(descriptorKey: String, refreshToken: Int) {
        pending = SourceIdentity(descriptorKey, refreshToken)
    }

    fun cancel() {
        pending = null
    }

    fun consumeIfReady(descriptorKey: String, refreshToken: Int): Boolean {
        if (pending != SourceIdentity(descriptorKey, refreshToken)) return false
        pending = null
        return true
    }
}

/**
 * Never expose an empty renderer while a replacement source is prepared.
 *
 * A different descriptor cannot reuse the previous package's assets as its own,
 * but the view can keep the already-rendered face visible and hand over
 * atomically once the new source and its first visual assets are ready.
 */
fun watchSourceReloadMode(
    currentDescriptorKey: String?,
    nextDescriptorKey: String,
    hasPreparedSource: Boolean,
): WatchSourceReloadMode =
    if (hasPreparedSource) {
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
 * 一张「不可见图层也要暖在位图缓存里」的清单，按图层索引排序。
 *
 * 判据是**整条祖先链 × 自身**的 `opacity` 表达式：
 *
 * - 全部不含 `{tag}`（常量）→ 不列入。`opacity="0"` 是作者关掉的层（比如
 *   wf528 那三个挂 tap_action 的隐形 shape），拉进来纯属白占解码。
 * - 至少一个含 `{tag}`（动态）→ 列入。这类层由表达式门控显隐（wf528 的
 *   `(math.floor({ds}/4) % 6 == 1) and 100 or 0` 正是这个模式），表达式翻真前
 *   永远不会被画到，`drawRenderItem` 的 `alpha <= 0f` 提前 return 让
 *   `requestBitmap` 压根没机会跑 —— 轮到显示的那一拍才冷启动异步解码，画不出
 *   东西就黑一帧。这里预先发起解码把它们暖进 LRU，轮换时直接命中。
 *
 * 注意：这是**冷启动**问题的解，不是 LRU 淘汰的解 —— 本函数也不碰 LRU。
 * 温热度只由「画到的层」维持；表达式周期很长的层（wf528 的 `{dm}` 门控层，
 * 20~40 分钟一轮）在一次缓存清理（换表盘、系统回收）后仍可能冷，那种情况由
 * 绘制路径的 `lastDrawnFrameByLayer` 旧帧兜底顶上。
 *
 * 另外三类**故意不列入**，因为暖它们不解决问题或反而制造问题：
 *
 * - `svg`：文档在 [PreparedSource] 构造时就解析成 Path 了，绘制时只查表，
 *   压根没有异步解码这一步，不存在冷启动。
 * - `gallery` / `gallery_2d`：路径不来自 `path`，而是 gallery 目录扫描的结果，
 *   本函数拿不到；而且画廊本身在持续轮播，一直是热的。
 * - 反引号帧序列（[rawPathValues] 里含 `` ` ``）：翻真那一拍要哪一帧取决于
 *   届时的 `animatedImageElapsedMs()`，现在无从得知；312 里这类层有 19 个、
 *   每个几十上百帧，全暖一遍等于把整包图片解一遍，正是要避免的卡顿源。
 *   它们由 [WatchmakerWatchFaceView] 绘制路径里的相邻帧预取负责。
 * - 动态路径（[rawPathValues] 里含 `{tag}`）：同理，现在算出来的值不一定是
 *   翻真那一拍要的值，且大表达式的 `evaluateText` 本身就不便宜。
 *
 * @param rawOpacityValues 每层的原始 `opacity` 属性文本（下标即图层索引），
 *   取不到用空串。父链的 opacity 由 [resolveWatchLayerGraph] 的 parentIndices
 *   推导，不读 XML 的 group 子节点。
 * @param rawPathValues 每层的原始 `path` 属性文本，用途见上。
 */
internal fun planOpacityGatedImageWarmup(
    parentIndices: List<Int?>,
    layerTypes: List<String>,
    rawOpacityValues: List<String>,
    rawPathValues: List<String>,
): List<Int> {
    val result = ArrayList<Int>()
    for (layerIndex in layerTypes.indices) {
        val normalizedType = layerTypes[layerIndex].lowercase(Locale.ROOT)
        if (normalizedType !in WARMABLE_IMAGE_LAYER_TYPES) continue
        val rawPath = rawPathValues.getOrElse(layerIndex) { "" }
        if (rawPath.isBlank() || '`' in rawPath || '{' in rawPath) continue
        // 父链可能因为损坏的 XML 成环，用步数封顶而不是靠 visited 集合：
        // 层数本来就被 MAX_WATCH_LAYER_COUNT 限住，多走几步比多分配一个集合便宜。
        var dynamic = false
        var cursor: Int? = layerIndex
        var hops = 0
        while (cursor != null && hops++ <= MAX_WATCH_LAYER_COUNT) {
            if ('{' in rawOpacityValues.getOrElse(cursor) { "" }) {
                dynamic = true
                break
            }
            cursor = parentIndices.getOrNull(cursor)
        }
        if (dynamic) result += layerIndex
    }
    return result
}

/** 见 [planOpacityGatedImageWarmup]：只含「一张静态图 + 走 drawImage」的类型。 */
private val WARMABLE_IMAGE_LAYER_TYPES = setOf(
    "image",
    "gif",
    "image_gif",
    "image_tap",
    "image_cond",
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
        "weather", "wp", "wr",
        "cal", "calendar", "next_event",
        "phone_battery", "pbl", "pblp", "pbc",
        "bv", "bi", "btc", "btf",
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
        // {dw} = 一年中的第几周（**不是**星期几），{ddim} = 本月天数。
        "dw", "ddim",
        // 设备/系统信息：语言码、区域码、是否 24 小时制。
        "alangcode", "alangreg", "d24h",
        "cnc",
        "bl", "blp", "batt", "battery", "battery_level",
        "pbl", "pblp", "pbr",
        "bc", "charging", "is_charging",
        "ssc", "steps", "step_count", "shr", "heart", "heart_rate", "heart_rate_bpm",
        "sthr", "shrp", "stsc", "sscp",
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
        // 日出/日落时刻与月相。{wmp} 是 0..9 的**整数**相位序号。
        "wsr", "wss", "wmp",
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
            isWatchCounterTag(normalized) ||
            utcBaseTag(normalized) != null ||
            forecastTagParts(normalized) != null ||
            hourlyForecastTagParts(normalized) != null ||
            futureWeekdayTagParts(normalized) != null
    }

    /** Shared high-refresh vocabulary for runtime and editor preview. */
    fun requiresFastTicks(raw: String): Boolean =
        fastTickTagPattern.containsMatchIn(raw) || watchCounterRequiresFrameTicks(raw)

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
            // {dw} = 一年中的第几周。⚠️ **不是**星期几 —— 星期几是 {ddw0}/{ddw}。
            // 对齐 clox `wf_tagdefs.lua:252` `os.date("%W") + 1`：strftime 的 %W
            // 以**周一**为周首、首个周一之前的日子算第 00 周，clox 再 +1。
            // 这里不用 Calendar.WEEK_OF_YEAR：它的语义受 locale 的 firstDayOfWeek
            // 与 minimalDaysInFirstWeek 影响，同一天在不同 locale 会给出不同结果。
            "dw" -> (strftimeWeekOfYearMondayBased(effectiveNow) + 1).toString()
            // {ddim} = 本月天数（clox `wf_tagdefs.lua:240` daysInMonth）。
            "ddim" -> effectiveNow.getActualMaximum(Calendar.DAY_OF_MONTH).toString()
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
            // ── 设备/系统信息（clox `wf_tagdefs.lua:442-443,473`）──
            // {alangcode}/{alangreg} 跟随 resolve 的 locale 形参，与日期/星期
            // 标签同源：设置里选了 zh/en/ja 时，表盘的语言码要一起变。
            "alangcode" -> locale.language.takeIf(String::isNotEmpty)
            "alangreg" -> locale.country.takeIf(String::isNotEmpty)
            // clox 用 boolStr，落在表盘上是 "true"/"false"。
            "d24h" -> snapshot.is24Hour?.let { if (it) "true" else "false" }
            // 没有宿主推过计数时给 0，**不给 null**：与 {shr} 同理，表盘会写
            // `{cnc} > 0` 这类表达式，代入 nil 会让整条算式失效、opacity 回落
            // 默认 100（历史事故：九个天气图标同时全亮）。0 让算式照常算。
            "cnc" -> (snapshot.notificationCount ?: 0).coerceAtLeast(0).toString()
            // ── 步数目标（clox `wf_tagdefs.lua:347-351`）──
            "stsc" -> effectiveStepGoal(snapshot).toString()
            "sscp" -> {
                val goal = effectiveStepGoal(snapshot)
                if (goal > 0) ((snapshot.steps ?: 0).toLong() * 100 / goal).toInt().toString() else "0"
            }
            // ── 日出/日落与月相 ──
            "wsr" -> weather?.sunriseText?.trim()?.takeIf(String::isNotEmpty)
            "wss" -> weather?.sunsetText?.trim()?.takeIf(String::isNotEmpty)
            // {wmp} 必须是 0..9 的**整数**：表盘用 `{wmp}==5` 这种等值比较，
            // 推小数永远不匹配（clox `wf_tagdefs.lua:329-334` 记了这个坑）。
            // Flue 没有月相 provider，按 clox `WeatherProvider.kt:124` 的本地
            // 天文算法自算，纯日期函数、无需数据源。
            "wmp" -> moonPhaseIndex(effectiveNow).toString()
            else -> resolveForecastTag(normalized, weather)
                ?: resolveHourlyForecastTag(normalized, weather)
                ?: resolveFutureWeekdayTag(normalized, effectiveNow, locale)
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

    /**
     * strftime `%W`：以**周一**为周首的周序号，首个周一之前的日子为第 0 周。
     *
     * 刻意不用 `Calendar.WEEK_OF_YEAR` —— 后者的取值受 locale 的
     * `firstDayOfWeek` / `minimalDaysInFirstWeek` 影响（美国 locale 周日为周首、
     * 首周只需 1 天；ISO locale 周一为周首、首周需 4 天），同一天能差出 1。
     * `{dw}` 要和 clox 逐日对齐，所以直接照 C 库的定义算。
     */
    private fun strftimeWeekOfYearMondayBased(now: Calendar): Int {
        val dayOfYear = now.get(Calendar.DAY_OF_YEAR) // 1-based
        // Calendar.SUNDAY==1 … SATURDAY==7 → 周一=0 … 周日=6（strftime 的 %u-1）
        val mondayBasedWeekday = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        return (dayOfYear + 6 - mondayBasedWeekday) / 7
    }

    /**
     * 月相序号 0..9（1=新月后渐盈、5=满月、9=残月），照 clox
     * `WeatherProvider.kt:124` 的 `moonPhase()` + `wf_tagdefs.lua:332` 的整数化。
     *
     * 基准新月取 2000-01-06，朔望月长 29.53058867 天。
     */
    private fun moonPhaseIndex(now: Calendar): Int {
        // 两个日历都用 UTC，只为让「天数差」是精确整数：夏令时切换那天，两个
        // **本地**午夜之间只差 23 小时（或 25 小时），整除会把相位算错整整一天。
        // 取的年月日仍然是 now 的本地日期。
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val reference = Calendar.getInstance(utc).apply {
            clear()
            set(2000, Calendar.JANUARY, 6)
        }
        val today = Calendar.getInstance(utc).apply {
            clear()
            set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
        }
        val elapsedDays = Math.floorDiv(today.timeInMillis - reference.timeInMillis, 86_400_000L)
        // ⚠️ 这里对朔望月取模用的是 29.53058867，而 clox 的 Kotlin 侧写的是
        // `% 29`（`WeatherProvider.kt:126`）再除以 29.53058867。整数 29 会让相位
        // 每个月提前约半天复位，几个月后就和真实月相错开一格。表盘用
        // `{wmp}==5` 判满月，错一格就是画错图，所以这里按真实周期取模。
        val lunationDay = elapsedDays.toDouble().mod(SYNODIC_MONTH_DAYS)
        return Math.floor(lunationDay / SYNODIC_MONTH_DAYS * 10).toInt().coerceIn(0, 9)
    }

    private fun effectiveStepGoal(snapshot: WatchDataSnapshot): Int =
        snapshot.stepGoal?.takeIf { it > 0 } ?: DEFAULT_DAILY_STEP_GOAL

    private data class FutureWeekdayTagParts(
        val kind: String,
        val dayOffset: Int,
    )

    /**
     * `{ddw_N}` / `{ddww_N}` / `{ddw1_N}` / `{ddw2_N}` —— N 天后的星期名。
     *
     * 对照 clox `wf_tagdefs.lua:800-811` 的家族正则 `^ddww?[12]?_%d+$`，N 限
     * 1..6（与它的显式范围检查一致）。312 用的是 `{ddw1_1}`~`{ddw1_6}`。
     */
    private fun futureWeekdayTagParts(tag: String): FutureWeekdayTagParts? {
        val match = Regex("^(ddw|ddww|ddw1|ddw2)_(\\d+)$").matchEntire(tag) ?: return null
        val offset = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..6 } ?: return null
        return FutureWeekdayTagParts(match.groupValues[1], offset)
    }

    private fun resolveFutureWeekdayTag(tag: String, now: Calendar, locale: Locale): String? {
        val parts = futureWeekdayTagParts(tag) ?: return null
        val future = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, parts.dayOffset)
        }
        // 与上面 {ddw}/{ddw1}/{ddw2}/{ddww} 的取名方式逐项对齐，包括 trimEnd('.')。
        return when (parts.kind) {
            "ddw" -> future.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale)
                ?.trimEnd('.')
            "ddw1" -> future.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.NARROW_FORMAT, locale)
                ?.trimEnd('.')
            "ddw2" -> future.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale)
                ?.trimEnd('.')
                ?.take(2)
            else -> future.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, locale)
                ?.trimEnd('.')
        }
    }

    /** WM_TAGS.md 中 {sthr} 的默认最大心率（BPM）。 */
    private const val DEFAULT_MAX_HEART_RATE_BPM = 200

    /**
     * `{stsc}` 的默认日步数目标。Flue 没有用户健康档案，取 clox
     * `watchface_bridge.lua:127` 的同一默认值，让 `{sscp}` 有可用分母。
     */
    private const val DEFAULT_DAILY_STEP_GOAL = 10_000

    /** 朔望月长度（天），clox `WeatherProvider.kt:129` 同值。 */
    private const val SYNODIC_MONTH_DAYS = 29.53058867
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
    /**
     * 表达式源文本上限。
     *
     * 32K 而不是原来的 4K：WFF/WatchMaker Studio 导出的表盘会把「按天气代码
     * 选图标」这类逻辑压成一条巨型链式三元式 —— 实测某张 528 图层的表盘里，
     * 9 条天气图标的 opacity 各 7665 字符。卡在 4K 时它们全部求值失败、
     * opacity 落回默认 100，于是九个天气图标**同时全亮叠在一起**（老师实机
     * 反馈）。
     *
     * 放宽的代价由下面的求值缓存兜住：同一条表达式只在 tag 值真的变化时才
     * 重新解析，不再是每帧每图层重来一遍。
     *
     * 数值本体移到 [WatchExpressionLimits]，供编译路径共用；这里保留别名与
     * 上面的来历说明。两条路径的限额必须相等，否则会出现「一条路收、一条路拒」。
     */
    private const val MAX_EXPRESSION_LENGTH = WatchExpressionLimits.MAX_EXPRESSION_LENGTH
    /**
     * 单条表达式的 token 上限。上面那 9 条各约 2500 token，512 同样拦死。
     *
     * 仍然保留上限：它挡的是「深度嵌套导致递归下降爆栈」和「恶意档案让解析
     * 退化」。实测那批表达式的括号嵌套只有 47 层，离栈深度风险很远。
     */
    private const val MAX_EXPRESSION_TOKENS = WatchExpressionLimits.MAX_EXPRESSION_TOKENS
    private const val MAX_FUNCTION_ARGUMENTS = WatchExpressionLimits.MAX_FUNCTION_ARGUMENTS
    private const val MAX_RESULT_TEXT_LENGTH = WatchExpressionLimits.MAX_RESULT_TEXT_LENGTH

    /**
     * 求值结果缓存。键是「tag 代入后的文本 + scope 身份」，值是求值结果。
     *
     * 之所以能这么缓存：这个引擎是**纯函数** —— 内建只有 math.* / string.*，
     * 没有 random、没有时间函数，全部外部输入都经 resolveTag 进来，而 tag 已经
     * 被代入到键里了；scope 在一块表盘的生命周期内不可变。
     *
     * 之所以**必须**缓存：dynamicNumber 每帧、每图层、每个属性都调一次，
     * 而这里原本每次都要重新扫描、词法、递归下降解析一遍。一张 377 图层的
     * 表盘每帧要求值 276 条表达式（160 opacity + 113 text + 3 x），30fps 下就是
     * 每秒近万次完整解析 —— 老师报的「卡顿、背景疯狂闪烁」正是它。
     *
     * 缓存的是**代入后**的文本而不是原始文本：像 `{sscp}` 这种每帧都变的 tag
     * 本来就该重算，而 `{wci}`（天气代码）几小时才变一次，命中率接近 100%。
     */
    private val evaluationCache = object : LinkedHashMap<CacheKey, CachedEvaluation>(
        64,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CachedEvaluation>) =
            size > MAX_EVALUATION_CACHE_ENTRIES
    }

    /**
     * 缓存键 =（代入后的表达式文本, scope 引用）。
     *
     * scope 必须**进键**而不是「存一份、命中时校验引用」：后者在编辑器里会让
     * 命中率直接归零。编辑器同时活着好几个 View（画布、实时预览、图层面板
     * 缩略图），每个各持一份 [WatchExpressionScope]，于是同一条表达式在
     * 各 View 之间互相把对方的条目**踢掉**（键相同、scope 引用不同 → 覆盖写），
     * 每帧全部重新解析，还多背一层锁和哈希 —— 比不加缓存更慢，这正是老师报的
     * 「播放和编辑预览都非常卡」。进键之后各 View 各占一份条目，互不干扰。
     *
     * scope 用**引用**语义（identity）而不是 equals：它在表盘加载时构造一次后
     * 不再变动，引用相等即内容相等；而 equals 要逐个比 128 条变量，每帧几百次
     * 反而变成新的热点。
     */
    private class CacheKey(
        private val text: String,
        private val scope: WatchExpressionScope,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val that = other as? CacheKey ?: return false
            return scope === that.scope && text == that.text
        }

        override fun hashCode(): Int = 31 * text.hashCode() + System.identityHashCode(scope)
    }

    /** null 也要缓存：解析失败同样昂贵，不能每帧重试一次。 */
    private class CachedEvaluation(val value: ExpressionValue?)

    /**
     * 缓存条目上限。**必须明显大于一帧的工作集**，否则缓存会自己把自己挤掉：
     * 实测一张 377 图层的表盘每帧要求值 276 条表达式（160 opacity + 113 text
     * + 3 x），若上限取 256，本帧后半段的插入正好把本帧前半段刚算好的条目逐出，
     * 命中率归零 —— 比不加缓存还多一层哈希开销。
     *
     * 2048 而不是 276 的两倍：键含 scope（见 [CacheKey]），编辑器里画布、
     * 实时预览、图层缩略图各占一份完整工作集，几个 View 同时活着就是上千条。
     *
     * 内存代价来自键（代入后的表达式文本），单条由
     * [MAX_EVALUATION_CACHE_KEY_LENGTH] 封顶。常见表达式 ~400 字符，满载约
     * 1MB 量级，远小于同类的位图缓存。
     */
    private const val MAX_EVALUATION_CACHE_ENTRIES = 2_048

    /**
     * 缓存键长度上限。
     *
     * 不取 [MAX_EXPRESSION_LENGTH]（32K）：那是**单条**表达式的合法上限，
     * 用作缓存键上限的话满载内存会到几十 MB。8K 足以覆盖实测最长的那批
     * WFF 天气链（7.6K），更长的表达式退化成每次重新解析 —— 它们本来就罕见。
     */
    private const val MAX_EVALUATION_CACHE_KEY_LENGTH = 8 * 1024

    /**
     * 递归下降的嵌套深度上限。
     *
     * 每层括号要走 parseOr→…→parsePrimary 约 10 个栈帧，所以深度必须显式设限：
     * 不设的话「32K 个左括号」这种档案会直接把解析线程的栈打爆，而
     * StackOverflowError 虽然被 runCatching 吞掉，恢复后的 JVM 状态并不可靠。
     * 实测那批 WFF 转换表盘的嵌套是 47 层，256 留了足够余量。
     */
    private const val MAX_PARSE_DEPTH = WatchExpressionLimits.MAX_PARSE_DEPTH

    /**
     * 编译产物缓存。键与 [evaluationCache] 同构，但存的是**原始**文本而不是
     * 代入后的文本 —— 这正是编译路径省下的那一大笔开销。
     *
     * 老路径每帧都要先跑一遍 [substituteTags] 才拿得到缓存键，也就是每帧、
     * 每图层、每属性建一个 StringBuilder、逐字符抄一遍、再 toString；一条 7665
     * 字符的天气链就是每帧 ~8KB 活不过一帧的垃圾。编译路径的键是**属性原文**，
     * 一张表盘加载后就再也不变，`String.hashCode` 被 JVM 记忆化，查表几乎零成本。
     */
    private val compileCache = LinkedHashMap<CacheKey, CompiledAttr>(64, 0.75f, true)

    /** 编译缓存里所有 AST 的节点总数，用于给内存占用封顶。 */
    private var compiledNodeTotal = 0L

    private class CompiledAttr(val attr: WatchAttr, val nodeCount: Int)

    /**
     * 编译缓存条目上限。
     *
     * 比 [MAX_EVALUATION_CACHE_ENTRIES]（2048）大一个量级：求值缓存的工作集是
     * 「本帧真的求了值的表达式」，而编译缓存装的是**整张表盘的全部属性原文**。
     * 一张 377 图层的表盘每层约 20 条属性就是 ~7500 条，2048 会让它每帧自我
     * 淘汰、退化成每帧重新编译 —— 那比不缓存更糟。
     */
    private const val MAX_COMPILE_CACHE_ENTRIES = 16 * 1024

    /**
     * 编译产物的 AST 节点总数上限（计划 §1.5）。
     *
     * 单节点约 32 字节，512K 节点 ≈ 16MB，与位图缓存同量级。超限时按 LRU 逐出，
     * 被逐出的表达式下次用到再编译一遍 —— 慢一点，但不会把内存吃穿。
     */
    private const val MAX_COMPILED_NODES = 512L * 1024L

    /**
     * 取（并按需建立）一条表达式的编译产物。
     *
     * 编译失败**不上抛**：返回 [WatchAttr.Unsupported]，调用方据此退回老路径。
     * 老路径一行都没删，它既是 fallback 也是回滚开关（计划 §六「老路径被删导致
     * 无法回滚」那条风险）。
     */
    private fun compiledAttr(source: String, scope: WatchExpressionScope): WatchAttr {
        // 超过缓存键上限的表达式**不走编译路径**：它每帧都得重新编译，白做一遍功
        // 之后还要再走老路径，比直接走老路径更慢。界线取与 [evaluateLegacy] 的
        // 求值缓存同一个值，两条路径在同一处退化，行为不会分叉。
        if (source.length > MAX_EVALUATION_CACHE_KEY_LENGTH) return WatchAttr.Unsupported
        val key = CacheKey(source, scope)
        synchronized(compileCache) {
            compileCache[key]?.let { return it.attr }
        }
        // 编译放在锁外：它是这里最慢的一步，占着锁会让渲染线程与资源加载线程
        // 互堵。两条线程同时编译同一条表达式只是白做一次功，产物等价。
        val attr = runCatching { compileWatchAttr(source, scope) }
            .getOrDefault(WatchAttr.Unsupported)
        val nodeCount = (attr as? WatchAttr.Dynamic)?.nodeCount ?: 1
        synchronized(compileCache) {
            val previous = compileCache.put(key, CompiledAttr(attr, nodeCount))
            compiledNodeTotal += nodeCount - (previous?.nodeCount ?: 0)
            trimCompileCacheLocked()
        }
        return attr
    }

    /** 双上限（条目数 / 节点总数）都满足才停手；访问序 LinkedHashMap 的迭代序即 LRU 序。 */
    private fun trimCompileCacheLocked() {
        if (compileCache.size <= MAX_COMPILE_CACHE_ENTRIES && compiledNodeTotal <= MAX_COMPILED_NODES) {
            return
        }
        val iterator = compileCache.entries.iterator()
        while (iterator.hasNext() &&
            (compileCache.size > MAX_COMPILE_CACHE_ENTRIES || compiledNodeTotal > MAX_COMPILED_NODES)
        ) {
            compiledNodeTotal -= iterator.next().value.nodeCount
            iterator.remove()
        }
    }

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
        // interpolate 只在「求值失败或结果该让位给模板」这几条分支里才用得上，
        // 而它是一遍正则替换 + 重建字符串。原来无条件先跑一次，等于每个文字
        // 图层每帧白烧一次正则（377 层的表盘每秒上万次），缓存也盖不住它 ——
        // 改成惰性，命中率最高的纯文本分支彻底不跑。
        val value = evaluate(raw, scope, resolveTag) ?: return interpolate(raw, resolveTag)
        if (value === ExpressionValue.Nil ||
            value is ExpressionValue.Logical && !value.value
        ) {
            return interpolate(raw, resolveTag)
        }
        if (value is ExpressionValue.Numeric) {
            val template = interpolate(raw, resolveTag)
            // WatchMaker keeps zero-padded single-tag text such as "05".
            if (template.trim().toDoubleOrNull() != null) return template
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

    /**
     * 导入期预编译。对照 `wf_expr.lua:11`：
     *
     * > Import is allowed to be slow; the running face is not.
     *
     * 由 `prepareSource()` 在 IO dispatcher 上调用 —— 那里已经受 `sourceGeneration`
     * 保护，是天然的「导入期」。这里做的事只有一件：把每条属性原文塞进
     * [compileCache]，让第一帧就已经是全命中，不必让渲染线程在首帧现编译。
     *
     * ⚠️ 每条源文本要**同时预热原文与实体解码后的形式**：`evaluateText` 会先做
     * `&quot;`/`&apos;` 替换再进来（见 `WatchmakerWatchFaceView.evaluateText`），
     * 那是另一个字符串、另一个缓存键。只热原文的话，带实体的属性首帧照旧现编译。
     */
    fun precompile(
        sources: Collection<String>,
        scope: WatchExpressionScope,
    ): WatchExpressionCompileReport {
        var constant = 0
        var dynamic = 0
        var unsupported = 0
        val dependencies = LinkedHashMap<String, Set<String>>()
        val seen = HashSet<String>(sources.size * 2)
        sources.forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEach
            val decoded = trimmed.replace("&quot;", "\"").replace("&apos;", "'").trim()
            // decoded 与 trimmed 相同时 seen 会自动去重，不必额外判断。
            listOf(trimmed, decoded).forEach { source ->
                if (!seen.add(source)) return@forEach
                when (val attr = compiledAttr(source, scope)) {
                    is WatchAttr.Const -> constant++
                    is WatchAttr.Dynamic -> {
                        dynamic++
                        dependencies[source] = attr.tagDependencies
                    }
                    WatchAttr.Unsupported -> unsupported++
                }
            }
        }
        return WatchExpressionCompileReport(
            total = constant + dynamic + unsupported,
            constant = constant,
            dynamic = dynamic,
            unsupported = unsupported,
            tagDependencies = dependencies,
        )
    }

    fun supports(raw: String): Boolean = supports(raw, WatchExpressionScope.EMPTY)

    fun supports(raw: String, scope: WatchExpressionScope): Boolean {
        if (raw.isBlank()) return true
        if (raw.length > MAX_EXPRESSION_LENGTH) return false
        val candidate = substituteTags(raw) { WatchTagResolution.Resolved("1") } ?: return false
        return runCatching { Parser(candidate, scope).parse() }.isSuccess
    }

    /**
     * 求值总入口：先走编译路径，编译不了或求值抛错再退回老的「代入 → 词法 →
     * 边解析边求值」。
     *
     * 编译路径省掉的是每帧的 [substituteTags] + 词法 + 解析三件套；老路径即便
     * 缓存命中也躲不掉第一步（缓存键是代入**之后**的文本）。
     *
     * 编译路径**不额外缓存求值结果**：AST 走一遍就是几十次虚调用，而缓存要先
     * 把 tag 代入成字符串键才能查 —— 那正是我们要省掉的那步。
     */
    private fun evaluate(
        raw: String?,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): ExpressionValue? {
        val source = raw?.trim().orEmpty()
        if (source.isBlank() || source.length > MAX_EXPRESSION_LENGTH) return null
        when (val compiled = compiledAttr(source, scope)) {
            is WatchAttr.Const -> return compiled.value
            is WatchAttr.Dynamic -> {
                // 求值抛错就掉头走老路径：两条路径对「除零」「非数比大小」这类
                // 错误的处置本来就该一致，而老路径是既有行为的基准。
                runCatching { evaluateWatchExpr(compiled.node, scope, resolveTag) }
                    .getOrNull()
                    ?.let { return it }
            }
            WatchAttr.Unsupported -> Unit
        }
        return evaluateLegacy(source, scope, resolveTag)
    }

    /**
     * 老求值路径。**不要删**：它是编译路径的兜底，也是出问题时的回滚开关
     * （计划 §六）。
     */
    private fun evaluateLegacy(
        source: String,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): ExpressionValue? {
        val substituted = try { substituteTags(source, resolveTag) } catch (_: Throwable) { null }
            ?: return null
        // tag 已经代入到 substituted 里了，所以它连同 scope 唯一决定结果。
        if (substituted.length > MAX_EVALUATION_CACHE_KEY_LENGTH) {
            return runCatching { Parser(substituted, scope).parse() }.getOrNull()
        }
        val cacheKey = CacheKey(substituted, scope)
        synchronized(evaluationCache) {
            evaluationCache[cacheKey]?.let { cached -> return cached.value }
        }
        // 解析放在锁外：它是这里最慢的一步，占着锁会让渲染线程和资源加载线程
        // 互相堵住。两条线程同时解析同一条表达式只是白做一次功，结果一致。
        val value = runCatching { Parser(substituted, scope).parse() }.getOrNull()
        synchronized(evaluationCache) {
            evaluationCache[cacheKey] = CachedEvaluation(value)
        }
        return value
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
        // 零分配快路径：没有 tag、也没有要归一化的不换行空格时，下面那趟扫描的
        // 输出与输入逐字符相同，没必要为它建 StringBuilder 再 toString。
        //
        // 这条分支是渲染热路径的大头。求值缓存的键是**代入之后**的文本，所以
        // 缓存命中也省不掉这一步；377 层的表盘每帧要为 x/y/rotation/anim_scale/
        // opacity 各求一次值，一秒就是上万次「建一个 StringBuilder、逐字符抄一遍、
        // 再 toString」—— 全是活不过一帧的垃圾，也是掉帧时 GC 压力的主要来源。
        if (raw.substitutionIsIdentity()) return raw
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

    /**
     * [substituteTags] 的输出是否会与输入逐字符相同。
     *
     * 引号与转义的状态机必须和 [substituteTags] 一模一样地走一遍：引号内的
     * `\'` 不能翻转引号状态，引号内的不换行空格也不会被归一化成普通空格。末尾的
     * `quote == null` 对应那边「引号没闭合就 return null」的约定 —— 不闭合时
     * 这里返回 false，让它照旧走慢路径去返回 null，语义不变。
     */
    private fun String.substitutionIsIdentity(): Boolean {
        var index = 0
        var quote: Char? = null
        while (index < length) {
            val char = this[index]
            when {
                char == '{' -> return false
                char.code == 0xa0 && quote == null -> return false
                char == '\\' && quote != null && index + 1 < length -> index += 2
                char == '\'' || char == '"' -> {
                    if (quote == null) quote = char else if (quote == char) quote = null
                    index++
                }
                else -> index++
            }
        }
        return quote == null
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
        /** 当前括号 / 函数参数的嵌套层数，见 [MAX_PARSE_DEPTH]。 */
        private var depth = 0

        fun parse(): ExpressionValue {
            val value = parseOr()
            require(peek().type == TokenType.END) { "Unexpected token: ${peek().text}" }
            return value
        }

        /**
         * 进入一层嵌套。每层括号约消耗 10 个栈帧，所以在**入口**处限深，
         * 而不是指望 StackOverflowError 被兜住。
         */
        private inline fun <T> nested(block: () -> T): T {
            require(depth < MAX_PARSE_DEPTH) { "Expression nesting is too deep" }
            depth++
            return try { block() } finally { depth-- }
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
                TokenType.LEFT_PAREN -> nested { parseOr() }.also {
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
                nested {
                    do {
                        require(arguments.size < MAX_FUNCTION_ARGUMENTS) { "Too many function arguments" }
                        arguments += parseOr()
                    } while (match(TokenType.COMMA))
                }
                require(match(TokenType.RIGHT_PAREN)) { "Missing ')' after function arguments" }
            }
            return callFunction(name, arguments)
        }

        /**
         * Lua 内建走共用实现（[WatchLuaStdlib]），编译路径调的是同一份。
         *
         * 各写一遍的话，`string.format` 的手写格式串解析、`string.sub` 的 Lua
         * 负索引这些边角迟早不一致 —— 表现为「同一张表盘在编译成功与失败时
         * 显示不同的文字」，极难定位。
         */
        private fun callFunction(name: String, arguments: List<ExpressionValue>): ExpressionValue =
            WatchLuaStdlib.callFunction(name, arguments)

        private fun numericValue(value: Double): ExpressionValue.Numeric =
            WatchLuaStdlib.numericValue(value)

        private fun String.toScopedExpressionValue(): ExpressionValue =
            WatchLuaStdlib.toScopedExpressionValue(this)

        private fun compare(left: ExpressionValue, right: ExpressionValue, operator: TokenType): Boolean =
            WatchLuaStdlib.compare(left, right, operator.toCompareOp())

        private fun TokenType.toCompareOp(): WatchCompareOp = when (this) {
            TokenType.EQUAL -> WatchCompareOp.EQUAL
            TokenType.NOT_EQUAL -> WatchCompareOp.NOT_EQUAL
            TokenType.GREATER -> WatchCompareOp.GREATER
            TokenType.GREATER_EQUAL -> WatchCompareOp.GREATER_EQUAL
            TokenType.LESS -> WatchCompareOp.LESS
            TokenType.LESS_EQUAL -> WatchCompareOp.LESS_EQUAL
            else -> error("Unexpected comparison")
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
