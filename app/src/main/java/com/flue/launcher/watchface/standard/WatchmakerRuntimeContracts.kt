package com.flue.launcher.watchface.standard

import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

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

    // lines() 每帧每个文字层都会调；Regex 编译提到常量，别在调用点重复。
    private val LONG_SPACE_RUN = Regex(" {8,}")

    fun ttfTextSize(designSize: Float): Float =
        (designSize.coerceIn(1f, WatchmakerGeometry.DESIGN_SIZE) * TTF_FONT_SCALE)
            .coerceAtMost(WatchmakerGeometry.DESIGN_SIZE * TTF_FONT_SCALE)

    fun curveArc(direction: String?): WatchCurveArc =
        if (direction.equals("Down", ignoreCase = true)) {
            WatchCurveArc(startDegrees = 180f, sweepDegrees = -180f)
        } else {
            WatchCurveArc(startDegrees = 180f, sweepDegrees = 180f)
        }

    /**
     * `text_curved` 的逐字角槽位（**度**）。
     *
     * ## 为什么不用 `Canvas.drawTextOnPath`
     *
     * WatchMaker Studio 2.0 的弧形文字按**每个字自己的宽度**分配角槽，系数是一个
     * 扁平的 **60°／像素**：
     *
     * - CloX `wf_render.lua:2657`：`DEG60 = math.pi / 3`；`a[i] = w * DEG60 / radius`
     * - WFTools `WmPreview.kt:2172`：`slotDeg[i] = wpx * 60f / radius`
     *
     * `drawTextOnPath` 走的是**真弧长** `180/π ≈ 57.2958°／像素`。WFTools 那处注释
     * 把差别写得很直白：
     *
     * > a FLAT 60°/px, not the true arc-length 57.3°/px that drawTextOnPath used
     * > (which packed strings ~5% too tight)
     *
     * 5% 的字距差在环形排版里会累积成整串偏移，看起来就是「对不齐」。
     * 空格两处也一致取 `0.3 × 字号`（不是空格的实测宽度）。
     *
     * @param glyphWidths 每个字形的**实测宽度**（像素），与 `text` 等长。
     * @param isSpace 该位是否为空格；空格宽度被替换成 `0.3 × fontPx`。
     * @param fontPx 当前字号（像素）。
     * @param radius 曲线半径。
     * @return 与输入等长的角槽位列表，单位**度**；`radius <= 0` 时全为 0。
     */
    fun curvedGlyphSlots(
        glyphWidths: List<Float>,
        isSpace: List<Boolean>,
        fontPx: Float,
        radius: Float,
    ): List<Float> {
        if (glyphWidths.isEmpty()) return emptyList()
        val safeRadius = radius.takeIf { it.isFinite() && it > 0f }
            ?: return List(glyphWidths.size) { 0f }
        val safeFontPx = fontPx.takeIf { it.isFinite() && it > 0f } ?: 0f
        val spaceWidth = CURVED_TEXT_SPACE_WIDTH_RATIO * safeFontPx
        return List(glyphWidths.size) { index ->
            val raw = glyphWidths[index]
            val width = if (isSpace.getOrElse(index) { false }) spaceWidth else raw
            val safeWidth = width.takeIf { it.isFinite() && it > 0f } ?: 0f
            safeWidth * CURVED_TEXT_DEGREES_PER_PIXEL / safeRadius
        }
    }

    /**
     * WM 的扁平角密度：**60°／像素**。
     *
     * 比真弧长 `180/π ≈ 57.2958°／像素` 大约 4.7%，正是 WFTools 注释里那句
     * 「packed strings ~5% too tight」的来源。
     */
    const val CURVED_TEXT_DEGREES_PER_PIXEL: Float = 60f

    /** 空格宽度占字号的比例，与 CloX / WFTools 一致。 */
    const val CURVED_TEXT_SPACE_WIDTH_RATIO: Float = 0.3f

    fun lines(text: String): List<String> =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            // Some WatchMaker exporters normalize an authored XML-attribute
            // newline into a long padding run.  Eight spaces is deliberately
            // conservative: ordinary typography is preserved, while the real
            // Vibe_coder sample's 36-space marker becomes its preview's 2 lines.
            .replace(LONG_SPACE_RUN, "\n")
            .split('\n')

    /**
     * Start x for every line in a multi-line block. WatchMaker anchors the
     * first authored line, then keeps following lines left-aligned with it.
     * Using the widest line instead moves short first lines toward the edge.
     */
    fun lineStartXs(
        lineWidths: List<Float>,
        horizontalAlignment: String?,
    ): List<Float> {
        if (lineWidths.isEmpty()) return emptyList()
        val anchorWidth = lineWidths.firstOrNull()?.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        val blockLeft = when {
            horizontalAlignment.orEmpty().lowercase(Locale.ROOT).endsWith('l') -> 0f
            horizontalAlignment.orEmpty().lowercase(Locale.ROOT).endsWith('r') -> -anchorWidth
            else -> -anchorWidth / 2f
        }
        return List(lineWidths.size) { blockLeft }
    }

    fun baselines(
        lineCount: Int,
        ascent: Float,
        descent: Float,
        lineHeight: Float,
        verticalAlignment: String?,
    ): List<Float> {
        val count = lineCount.coerceAtLeast(1)
        val spacing = lineHeight.coerceAtLeast(0f)
        val singleBaseline = when {
            verticalAlignment.orEmpty().lowercase(Locale.ROOT).startsWith('t') -> -ascent
            verticalAlignment.orEmpty().lowercase(Locale.ROOT).startsWith('b') -> -descent
            else -> -(ascent + descent) / 2f
        }
        val firstBaseline = when {
            verticalAlignment.orEmpty().lowercase(Locale.ROOT).startsWith('t') -> singleBaseline
            verticalAlignment.orEmpty().lowercase(Locale.ROOT).startsWith('b') ->
                singleBaseline - (count - 1) * spacing
            else -> singleBaseline - (count - 1) * spacing / 2f
        }
        return List(count) { index -> firstBaseline + index * spacing }
    }
}

/** Top-level contract entry point kept for renderer adapters and focused tests. */
internal fun watchCurvedTextSlots(
    glyphWidths: List<Float>,
    isSpace: List<Boolean>,
    fontPx: Float,
    radius: Float,
): List<Float> = WatchTextLayout.curvedGlyphSlots(glyphWidths, isSpace, fontPx, radius)

private val WATCH_HEX_COLOR_LITERAL = Regex("^[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?$")

/** Returns a canonical Android color only for an authored 6/8 digit literal. */
fun normalizeWatchColorLiteral(raw: String?): String? {
    val value = raw.orEmpty().trim().removePrefix("#")
    return value.takeIf(WATCH_HEX_COLOR_LITERAL::matches)?.let { "#$it" }
}

/** Literal boundary for the range/enumeration-valued text_ring attribute. */
fun watchTextRingType(raw: String?): String = raw.orEmpty().trim().ifBlank { "1-12" }

/** Bounded high-density marker count; CloX accepts the sample's authored 600 ticks. */
fun watchMarkerCount(raw: Float): Int = raw.roundToInt().coerceIn(1, 1_200)

/** Studio 2.0 ring_image progress is a percentage over the authored sweep. */
fun watchRingImageSweep(percent: Float, angleTotal: Float): Float {
    val total = angleTotal.takeIf { it.isFinite() && it > 0f } ?: 360f
    return total * (percent.takeIf(Float::isFinite)?.coerceIn(0f, 100f) ?: 0f) / 100f
}

/** Candidate bundled assets for Studio 2.0 sunarc/daylight layers. */
fun watchStructuredAssetCandidates(type: String, presetOrMap: String?): List<String> =
    when (type.lowercase()) {
        "daylight" -> listOfNotNull(presetOrMap?.takeIf(String::isNotBlank)?.let { "daylight_$it.webp" })
        "sunarc" -> listOfNotNull(presetOrMap?.takeIf(String::isNotBlank)?.let { "sunarc_$it.webp" })
        // Studio bundles the three repeated-progress glyphs as regular image
        // assets. Keep several exporter naming variants for old packages;
        // missing files remain a no-op at render time.
        "progress_image" -> presetOrMap
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { shape ->
                listOf(
                    "progress_$shape.webp",
                    "progress_$shape.png",
                    "shapes/progress_$shape.webp",
                    "shapes/progress_$shape.png",
                )
            }
            .orEmpty()
        else -> emptyList()
    }

/**
 * `ring` 的端帽形状。
 *
 * @param rounding 端帽倒角，已夹在 `[0, 环厚/2]`。等于上限就是经典半圆帽。
 * @param square Flue 自己早年加的 `end_style="square"`（clox 的 ring 不读 `end_style`）。
 */
data class WatchRingCap(val rounding: Float, val square: Boolean)

/**
 * 解析 `ring` 的端帽。
 *
 * 规范（`WM_FORMAT_1.0.md` §7.3）：`radius` 是**端帽倒角**，夹在 `[0, (外半径−内半径)/2]`，
 * **省略时取上限** —— 上限正好让两个凸出角的圆角相接，退化成今天无条件在用的半圆帽。
 * 所以没写 `radius` 的表盘一帧都不会变。
 *
 * `end_style` 只在 `radius` 缺席时起作用：它是 Flue 的扩展，留着是为了不回退按 Flue
 * 写出来的表盘，但一旦作者写了规范里的 `radius`，就以 `radius` 为准。
 *
 * @param radiusValue 已求值的 `radius`；属性缺席 / 空白时传 `null`。
 * @param stroke 环厚（外半径 − 内半径）。
 */
fun watchRingCap(radiusValue: Float?, endStyle: String?, stroke: Float): WatchRingCap {
    val capMax = (stroke / 2f).coerceAtLeast(0f)
    if (radiusValue != null) {
        return WatchRingCap(radiusValue.coerceIn(0f, capMax), square = false)
    }
    return when {
        endStyle.equals("butt", ignoreCase = true) -> WatchRingCap(0f, square = false)
        endStyle.equals("square", ignoreCase = true) -> WatchRingCap(capMax, square = true)
        else -> WatchRingCap(capMax, square = false)
    }
}

/** `markers` / `markers_hm` 的刻度字形。 */
enum class WatchMarkerGlyph { SQUARE, CIRCLE, TRIANGLE }

/**
 * 刻度字形的取值。
 *
 * `shape`（`Square` / `Circle` / `Triangle`）是 1.0 就有的字段，也是 clox 唯一读的那个。
 * `markers_shape` 是 2.0 新增的「布局 / 圆角风格」（`Circle` 或 `Rounded`）—— **clox 的渲染器
 * 根本不读它**，Studio 的导入器只拿它做「字形消歧」。所以这里只在 `shape` 缺席时让它顶上：
 * `Circle` 当圆字形，`Rounded` 仍是方块（圆角另由调用方按它决定）。
 */
fun watchMarkerGlyph(shape: String?, markersShape: String?): WatchMarkerGlyph {
    val explicit = shape?.trim()?.takeIf(String::isNotEmpty)
    val effective = explicit ?: markersShape?.trim()?.takeIf(String::isNotEmpty)
    return when {
        effective.equals("Circle", ignoreCase = true) -> WatchMarkerGlyph.CIRCLE
        effective.equals("Triangle", ignoreCase = true) -> WatchMarkerGlyph.TRIANGLE
        else -> WatchMarkerGlyph.SQUARE
    }
}

/** `markers_hm` 的一个档位：尺寸步进 + 字形。`step == 0` 表示这一套刻度不画。 */
data class WatchMarkerPreset(
    val step: Float,
    val glyph: WatchMarkerGlyph = WatchMarkerGlyph.SQUARE,
)

/**
 * `m_hour` / `m_minute` 档位。
 *
 * clox 的表只有 `None/Tiny/Small/Medium/Large` → `0/1/2/3/4`，缺省 `Medium`。
 * `WM_FORMAT_2.0.md` 把这一列扩到 `None, Tiny, Small, Medium, Large, XLarge, Circle,
 * Triangle` —— 后三个 clox 还不认。这里按最合理的方式落地：
 *
 * - `XLarge` 顺着 1/2/3/4 的数列取 **5**；
 * - `Circle` / `Triangle` 是**字形**而不是尺寸，取 Medium 的尺寸配对应字形。
 *
 * 认不出来的值一律回落 Medium（而不是不画）：把刻度整层吞掉比画得略有出入糟得多。
 */
fun watchHourMinuteMarkerPreset(raw: String?): WatchMarkerPreset {
    val value = raw.orEmpty().trim()
    return when {
        value.equals("None", ignoreCase = true) -> WatchMarkerPreset(0f)
        value.equals("Tiny", ignoreCase = true) -> WatchMarkerPreset(1f)
        value.equals("Small", ignoreCase = true) -> WatchMarkerPreset(2f)
        value.equals("Large", ignoreCase = true) -> WatchMarkerPreset(4f)
        value.equals("XLarge", ignoreCase = true) -> WatchMarkerPreset(5f)
        value.equals("Circle", ignoreCase = true) ->
            WatchMarkerPreset(MARKER_PRESET_MEDIUM, WatchMarkerGlyph.CIRCLE)
        value.equals("Triangle", ignoreCase = true) ->
            WatchMarkerPreset(MARKER_PRESET_MEDIUM, WatchMarkerGlyph.TRIANGLE)
        else -> WatchMarkerPreset(MARKER_PRESET_MEDIUM)
    }
}

private const val MARKER_PRESET_MEDIUM = 3f

/**
 * `squarify` —— 把刻度圈从圆形拉向方形。
 *
 * 与 clox 的 `squarifyRadius` 逐式相同：`r * ((1 - f) + f / max(|cos|, |sin|))`，
 * `f = squarify / 100`。`0` 就是原样的圆；`100` 时四个对角方向被推到 √2 倍，圈变成正方形。
 *
 * 传进来的角度用**画布角度**（0° 在 3 点钟）都不影响结果：公式只用到 |cos| 与 |sin|，
 * y 轴朝哪边、从哪里起算都一样。
 */
fun squarifiedRadius(radius: Float, angleDegrees: Float, squarifyPercent: Float): Float {
    if (squarifyPercent <= 0f) return radius
    val fraction = (squarifyPercent / 100f).coerceIn(0f, 1f)
    val radians = Math.toRadians(angleDegrees.toDouble())
    val maxAxis = max(abs(cos(radians)), abs(sin(radians))).toFloat()
    // 极端角度下 max(|cos|,|sin|) 不可能真的到 0（两者不会同时为 0），但浮点上要防一手。
    if (maxAxis < 1e-4f) return radius
    return radius * ((1f - fraction) + fraction / maxAxis)
}

data class WatchTextRingItem(
    val label: String,
    val sourceValue: Int,
    val angleDegrees: Float,
)

/** Ordered WatchMaker label set for the common text_ring families. */
fun watchTextRingLabels(ringType: String?): List<String> {
    val value = ringType.orEmpty().trim().ifBlank { "1-12" }
    return when (value) {
        "I-XII" -> listOf("I", "II", "III", "IIII", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
        "Mo-Su" -> listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        "Mon-Sun" -> listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        "Jan-Dec" -> listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
        else -> {
            val parts = if (value.contains(" to ")) {
                value.split(" to ", limit = 2)
            } else {
                value.split('-', limit = 2)
            }
            val first = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val last = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (first == null || last == null) {
                listOf(value)
            } else if (first <= last) {
                (first..last).map(Int::toString)
            } else {
                (first downTo last).map(Int::toString)
            }
        }
    }
}

/** Filters a text ring and applies CloX's one-based angular placement. */
fun watchTextRingItems(
    ringType: String?,
    showEvery: Int,
    hiddenValues: Set<Int>,
    startDegrees: Float,
    endDegrees: Float,
): List<WatchTextRingItem> {
    val labels = watchTextRingLabels(ringType)
    if (labels.isEmpty()) return emptyList()
    val step = showEvery.coerceAtLeast(1)
    val span = endDegrees - startDegrees
    return labels.mapIndexedNotNull { index, label ->
        val sourceValue = label.toIntOrNull() ?: (index + 1)
        if (sourceValue % step != 0 || sourceValue in hiddenValues) return@mapIndexedNotNull null
        WatchTextRingItem(
            label = label,
            sourceValue = sourceValue,
            angleDegrees = startDegrees + span * (index + 1) / labels.size,
        )
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
 * WM_FORMAT 阴影通道的取值契约（`shadow` / `w_distance` / `w_angle` /
 * `w_color` / `w_opacity`）。
 *
 * 语义对齐 clox `bindEffects`（wf_render.lua）：阴影是本体的**偏移拷贝**，
 * 画在本体后面、描边前面（clox 里阴影拷贝 z=-1、描边拷贝 z=-2）。
 *
 * - `Drop`：一份拷贝，偏移 `w_distance`
 * - `Flat`：`floor(w_distance)` 份堆叠拷贝，偏移分别为 1..floor(w_distance)，
 *   形成一条实心的拖影
 *
 * `w_angle` 是**光照方向**，阴影朝反方向落，所以偏移取负：clox 用
 * `(bx − d·cos, by − d·sin)`。注意 cocos 是 y 轴向上，Android Canvas 是
 * y 轴向下，**y 分量要再翻一次符号**才是同一个视觉方向。
 * 这个换算收在 [watchShadowOffsetY] 里，渲染路径别自己写三角函数。
 */
enum class WatchShadowMode {
    NONE,

    /** 单份偏移拷贝。 */
    DROP,

    /** 1..floor(w_distance) 逐像素堆叠，视觉上是实心拖影。 */
    FLAT,
}

/** w_distance 的全局封顶（设计像素）。Flat 的拷贝数就是它，必须收口。 */
const val MAX_WATCH_SHADOW_DISTANCE = 64f

fun watchShadowMode(raw: String?): WatchShadowMode =
    when (raw.orEmpty().trim().lowercase(Locale.ROOT)) {
        "drop" -> WatchShadowMode.DROP
        "flat" -> WatchShadowMode.FLAT
        else -> WatchShadowMode.NONE
    }

/** `w_distance` 缺省 5（clox `evalNum("w_distance", 5)`），按封顶收口。 */
fun watchShadowDistance(raw: Float?): Float =
    (raw ?: 5f).coerceIn(0f, MAX_WATCH_SHADOW_DISTANCE)

/**
 * 拷贝份数：Drop 恒 1；Flat 取 `floor(w_distance)`（clox 同式），距离不足
 * 1 像素时为 0 —— 一份都不画。
 */
fun watchShadowCopyCount(mode: WatchShadowMode, distance: Float): Int = when (mode) {
    WatchShadowMode.NONE -> 0
    WatchShadowMode.DROP -> 1
    WatchShadowMode.FLAT -> floor(distance.coerceIn(0f, MAX_WATCH_SHADOW_DISTANCE)).toInt()
        .coerceAtLeast(0)
}

/**
 * 第 [copyIndex]（1 起）份拷贝的偏移距离：Drop 恒为 `distance`，Flat 为
 * `copyIndex`（clox `local d = Drop and dist or i`）。
 */
fun watchShadowCopyDistance(mode: WatchShadowMode, distance: Float, copyIndex: Int): Float =
    if (mode == WatchShadowMode.FLAT) copyIndex.toFloat() else distance

/** `w_angle` 缺省 135°（光照方向）。 */
fun watchShadowAngleDegrees(raw: Float?): Float = raw ?: 135f

/** 阴影 x 偏移。光照朝 `angle`，阴影朝反向，故取 `−d·cos(angle)`（同 clox）。 */
fun watchShadowOffsetX(distance: Float, angleDegrees: Float): Float =
    -distance * cos(angleDegrees * PI.toFloat() / 180f)

/**
 * 阴影 y 偏移。clox 在 y 轴向上的 cocos 空间里用 `−d·sin(angle)`；Android
 * Canvas 的 y 轴向下，同一个视觉方向要再翻一次符号 → `+d·sin(angle)`。
 */
fun watchShadowOffsetY(distance: Float, angleDegrees: Float): Float =
    distance * sin(angleDegrees * PI.toFloat() / 180f)

/**
 * 图片图层的实际绘制高度 —— wftools 的定尺怪癖，clox `setBaseScale` 原样照搬：
 *
 * ```lua
 * if texW ~= texH and W == H then
 *     local s = W / texW
 *     rec.bsx, rec.bsy = s, s     -- 非方图 + 方形框 → 等比，不压成正方形
 * else
 *     rec.bsx, rec.bsy = W / texW, H / texH
 * end
 * ```
 *
 * 两个分支的 x 比例都是 `W / texW`，所以**绘制宽度恒等于作者写的 width**，
 * 只有高度会变 —— 这里就只求高度。
 *
 * [texWidth]/[texHeight] 传的是**单格**尺寸：clox 给 sprite sheet 喂的是
 * `texture ÷ grid`，非 sheet 时单格就是整张图。
 */
fun watchImageDrawHeight(
    texWidth: Float,
    texHeight: Float,
    boxWidth: Float,
    boxHeight: Float,
): Float =
    if (texWidth > 0f && texHeight > 0f && texWidth != texHeight && boxWidth == boxHeight) {
        texHeight * (boxWidth / texWidth)
    } else {
        boxHeight
    }

/**
 * Long-edge decode budget for a conditional-image sprite sheet.
 *
 * A sheet is sampled one cell at a time, so applying the ordinary view-sized
 * decode cap to the whole texture destroys every cell. Preserve at least the
 * authored destination pixels per cell, while retaining a hard upper bound for
 * hostile or malformed grids. A null result means the layer has no usable
 * authored destination size and should keep the ordinary image policy.
 */
internal fun watchSpriteDecodeDimensionPx(
    targetWidth: Float?,
    targetHeight: Float?,
    columns: Int,
    rows: Int,
    maximumDimensionPx: Int = 4_096,
): Int? {
    val width = targetWidth?.takeIf { it.isFinite() && it > 0f }
    val height = targetHeight?.takeIf { it.isFinite() && it > 0f }
    if (width == null && height == null) return null
    val fallbackDimension = width ?: height ?: return null
    val safeColumns = columns.coerceIn(1, 32)
    val safeRows = rows.coerceIn(1, 32)
    val requiredWidth = (width ?: fallbackDimension) * safeColumns
    val requiredHeight = (height ?: fallbackDimension) * safeRows
    return ceil(max(requiredWidth, requiredHeight).toDouble())
        .toInt()
        .coerceIn(1, maximumDimensionPx.coerceAtLeast(1))
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
 * [DEFAULT] 是**中性变换**（FIT / zoom 1 / offset 0），拆双轴之前的输出与它逐位
 * 相同，所以解码兜底、坏字段回落都用它。
 *
 * 出厂的**全局默认**是另一回事：见 [GLOBAL_DEFAULT]。
 */
data class WatchCanvasSettings(
    val preset: WatchCanvasPreset = WatchCanvasPreset.FIT,
    val zoomX: Float = 1f,
    val zoomY: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** 是否与中性变换完全一致。 */
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        /** 中性变换：解码兜底与坏字段回落用这一份，不是出厂默认。 */
        val DEFAULT = WatchCanvasSettings()

        /**
         * 出厂的全局默认铺法：**铺满**。
         *
         * 与 [DEFAULT] 分开是刻意的 —— 前者是「没配置时用什么」，后者是「解析不出
         * 来时退回什么」。合成一个的话，改默认铺法会顺手改掉所有坏数据的回落值。
         *
         * 存量用户若显式动过旧的 `watchmaker_force_fill` 开关，读路径仍尊重那个
         * 选择（见 `LauncherViewModel` 的画布默认加载），只有从没配置过的才吃这个。
         */
        val GLOBAL_DEFAULT = WatchCanvasSettings(preset = WatchCanvasPreset.FILL)

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

/**
 * 缩放倍率的合法区间。下限不取 0：0 会让画布彻底消失且无法交互退回。
 *
 * 区间刻意比画布调节滑块的量程宽 —— 手输框只校验 `isFinite()`（见
 * `canvasInputValueOrNull`），滑块量程只管手感，真正的上下限只有这一处。
 * 上限也不敢真放开：zoom 取到 1e9 会让变换矩阵退化，画面直接空掉。
 */
private const val MIN_CANVAS_ZOOM = 0.01f
private const val MAX_CANVAS_ZOOM = 20f

/**
 * 偏移上限（**视图像素**，因为它直接加在 view 空间的 `left`/`top` 上）。
 *
 * 16384 远大于任何手表屏，够用；它挡的是脏配置把表盘整块推出屏幕后再也调不回来。
 */
private const val MAX_CANVAS_OFFSET = 16_384f

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

/** [watchRenderReadiness] 的三种结论。 */
enum class WatchRenderReadiness {
    /** 就绪：这一帧画出来的就是完整画面，加载页可以撤了。 */
    READY,

    /**
     * 还在等，但**不需要**额外推帧：有在飞的解码，每个解码完成都会 `invalidate()`，
     * 下一帧自然会来，届时重新判定。
     */
    WAITING,

    /**
     * 还在等，而且一个在飞的解码都没有 —— 本帧要画的素材没能拿到解码额度
     * （常见于上一代表盘的取消请求还占着并发槽）。这种情况没人会来叫醒我们，
     * 必须自己 `postInvalidateOnAnimation()` 推下一帧。
     */
    RETRY_NEXT_FRAME,
}

/**
 * 「表盘渲染就绪」判定 —— 决定桌面那层加载页什么时候能撤。
 *
 * 为什么需要它：`watchFaceSelectionReady` 只表示「偏好读完、磁盘扫完，知道该显示
 * 哪块表盘」，与那块表盘渲染到哪一步毫无关系。而 `prepareSource()` 只解析 watch.xml
 * 并建渲染计划，**一张位图都不解码** —— 位图 / GIF / 字体全是绘制期按需异步加载。
 * 所以连 `onSourceReady` 都只等于「结构就绪」，图还没来。老师报的「冷启动加载页直接
 * 消失、过一会儿表盘才好」就是这个缝。
 *
 * 判定用**静默条件**：这一趟绘制结束时，本代没有在飞的资产加载，且本帧没有
 * 「该画却拿不到素材」的图层。注意不能只看「加载表是空的」—— 第一次绘制**之前**
 * 表天然是空的（还没人请求任何素材），所以必须绘完一趟再判，并在之后每帧重判。
 *
 * 这个函数只做判定、不碰状态，方便直接单测。调用方在 `onDraw` 末尾调。
 *
 * @param faceVisible 表盘当前是否可见。不可见时立即算就绪 —— 拿加载页盖一块看不见的
 *   表盘没有意义，而且不可见时 tick 不跑，可能一帧都不再绘。
 * @param sourceReady 结构是否就绪（`loadedDescriptorKey` / `loadedRefreshToken` 与当次
 *   bind 对齐）。没就绪时画面是纯黑，当然不能撤。
 * @param transitionFrameVisible 跨表盘过渡帧（上一块表盘的快照）是否还盖在上面。
 *   盖着就说明新表盘还没露出来，撤加载页只会露出旧表盘。
 * @param pendingAssetLoads 本代在飞的资产解码数量。
 * @param frameHasUnreadyVisualAsset 本帧有图层解析出了素材引用，却既没位图也没 Movie。
 * @param holdExpired 保底时限是否已到。**失败的素材算已结算**（进了 failure 冷却表就
 *   不再有在飞的解码），但「解析得到却永远拿不到」的素材会让上一项一直为真，
 *   所以必须有这道闸 —— 否则一个坏素材能把加载页顶满整个失败重试冷却期。
 */
fun watchRenderReadiness(
    faceVisible: Boolean,
    sourceReady: Boolean,
    transitionFrameVisible: Boolean,
    pendingAssetLoads: Int,
    frameHasUnreadyVisualAsset: Boolean,
    holdExpired: Boolean,
): WatchRenderReadiness {
    if (!sourceReady) return WatchRenderReadiness.WAITING
    if (!faceVisible) return WatchRenderReadiness.READY
    if (holdExpired) return WatchRenderReadiness.READY
    if (transitionFrameVisible) return WatchRenderReadiness.WAITING
    if (pendingAssetLoads > 0) return WatchRenderReadiness.WAITING
    if (frameHasUnreadyVisualAsset) return WatchRenderReadiness.RETRY_NEXT_FRAME
    return WatchRenderReadiness.READY
}

/**
 * 渲染就绪的保底时限，从 bind 建立新代开始计。
 *
 * 4 秒远长于本地素材解码所需（重表盘也在 1~2 秒量级），又短到出问题时不会让用户
 * 觉得是死机。桌面那层还有各自的硬揭开兜底，这里是第一道。
 */
const val WATCH_RENDER_READY_MAX_HOLD_MS = 4_000L

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
    val accelerometerX: Float? = null,
    val accelerometerY: Float? = null,
    val accelerometerZ: Float? = null,
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

    // ── 以下是 N5 为「已认识但无数据」的那批变量补的数据源 ──────────────────
    // 全部由 provider 侧填（要 Context / 系统服务），解析器仍是纯函数。
    // 单位一律取**规范单位**（字节、摄氏度、米、毫秒、度），换算与格式化留在解析器里，
    // 免得同一个量在快照里出现两种单位。

    /** {btc} / {btf} / {btcd} / {btfd}。摄氏度，来自电池广播的 EXTRA_TEMPERATURE。 */
    val batteryTemperatureCelsius: Float? = null,

    /** {amu} / {amf} / {amt} 一族。字节。 */
    val ramUsedBytes: Long? = null,
    val ramFreeBytes: Long? = null,
    val ramTotalBytes: Long? = null,

    /** {adsu} / {adsf} / {adst} 一族。字节，取内部存储卷。 */
    val storageUsedBytes: Long? = null,
    val storageFreeBytes: Long? = null,
    val storageTotalBytes: Long? = null,

    /** {aman} / {amodel} / {aname} / {aosv} / {awname}。 */
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val deviceName: String? = null,
    val osVersion: String? = null,
    val watchName: String? = null,

    /** {around} / {atyre}。圆屏与「扁轮胎」（底部截平的圆屏）。 */
    val screenRound: Boolean? = null,
    val screenFlatTyre: Boolean? = null,

    /** {avol} 媒体音量 0–100、{abrt} 屏幕亮度 0–255。 */
    val volumePercent: Int? = null,
    val screenBrightness: Int? = null,

    /** {alowpw} 省电模式、{adark} 深色模式。 */
    val lowPowerMode: Boolean? = null,
    val darkMode: Boolean? = null,

    /** {areboot} 上次重启时刻，`yyyy-MM-dd HH:mm`。 */
    val lastRebootText: String? = null,

    /**
     * {abright} 是否亮屏（非息屏）、{adimlo} 息屏是否只支持低位色、
     * {abss} / {abssl} 亮屏以来的毫秒数（息屏时按规范给 -1）。
     */
    val brightMode: Boolean? = null,
    val dimLowBitOnly: Boolean? = null,
    val millisSinceBright: Long? = null,

    /** {scr} / {sct} / {scb} 一族。罗盘方位角 0–360（0 = 北）。 */
    val compassHeadingDegrees: Float? = null,

    /** {alat} / {alon} / {aalt} 一族。复用天气那份已授权的定位，不另外申请权限。 */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,

    /** {sw*} 一族。由 `sw_start_stop` / `sw_reset` 点击动作驱动（见 [WatchStopwatch]）。 */
    val stopwatchRunning: Boolean? = null,
    val stopwatchElapsedMs: Long? = null,

    /**
     * {cex} 与 {cNxx} 一族的日程。
     *
     * **null 与空列表语义不同**：null = 读不到（没授权 / 没开这个功能），走 Unavailable
     * 渲染 `--`；空列表 = 读到了、确实没有日程，于是 {cex} 是 "false"。混成一个的话
     * 「没授权」会被显示成「今天没有安排」，那是在骗用户。
     */
    val calendarEvents: List<WatchCalendarEvent>? = null,
)

/**
 * 一条日程。字段与 WM_TAGS.md §Calendar 的 {cNxx} 一一对应。
 *
 * 纯数据：读 `CalendarContract` 的活由宿主侧做（要 Context 与权限），解析器只负责把
 * 它格式化成表盘要的样子。
 */
data class WatchCalendarEvent(
    val id: String? = null,
    val title: String? = null,
    val location: String? = null,
    val calendarName: String? = null,
    /** `RRGGBB`，不带 `#` —— 与 {ucolor} 一族同一形状。 */
    val colorRgb: String? = null,
    val allDay: Boolean = false,
    val beginEpochMs: Long? = null,
    val endEpochMs: Long? = null,
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

/**
 * 位图字体（`bm:`）的可见高度倍率。
 *
 * ## 为什么是 1.6
 *
 * WatchMaker 的位图字形按**图集原始像素**绘制，`text_size` 不是直接字号，而是
 * 「这一行文字的可见高度」目标值。上游三处实现完全一致：
 *
 * - CloX `wf_render.lua:1940/2026/2541/2649`：`lab:setScale(1.6 * size / ch)`
 * - WFTools 预览 `WmPreview.kt:1501` / 出图 `WatchfacePreviewCapturer.kt:144`：
 *   `baseScale = 1.6f * textSizePx / fontH`
 * - WFTools 量算 `WmPreviewGeometry.kt:138`：同一个 `1.6f * textSizePx / fontH`
 *
 * 之前 Flue 用的是 `text_size / nominalSize`，少了这个 `1.6`，于是所有 `bm:` 表盘
 * 的数字与时间都**缩小到约 62%**。实测「墨水电子表」：clox 时间块高／面板宽
 * `0.269`，Flue `0.168`，比值正好 `1.598`。
 *
 * ## fontH 的取值
 *
 * 上游三处都优先取 `info.size`（BMFont 生成字号），缺失才退回 `common.lineHeight`。
 * 两者在本仓样本里数值相同，但这个优先级要照抄，否则遇到不一致的图集会分叉。
 */
internal const val WATCH_BITMAP_FONT_SCALE = 1.6f

/** 见 [WATCH_BITMAP_FONT_SCALE]。`fontH` 无法确定时退回纯 1.6 倍。 */
internal fun watchBitmapGlyphScale(
    designSize: Float,
    nominalSize: Int,
    lineHeight: Int,
): Float {
    val fontHeight = when {
        nominalSize > 0 -> nominalSize.toFloat()
        lineHeight > 0 -> lineHeight.toFloat()
        else -> 0f
    }
    if (fontHeight <= 0f) return WATCH_BITMAP_FONT_SCALE
    val size = designSize.takeIf(Float::isFinite)
        ?.coerceIn(1f, WatchmakerGeometry.DESIGN_SIZE)
        ?: 1f
    return WATCH_BITMAP_FONT_SCALE * size / fontHeight
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
    /**
     * {wws} 风速，统一存 **m/s**；天气仓库负责按数据源的实际单位转换。
     */
    val windSpeedMetersPerSecond: Double? = null,
    /** {wwd} / {wwdb} / {wwdbb} 风向，气象学约定：度数是风**来自**的方向，0 = 北。 */
    val windDirectionDegrees: Double? = null,
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
        // An installed repository owns cache validity, including a deliberate null after
        // changing cities. The published cache is only for hosts without a repository.
        val source = applicationSource
        if (source == null) cache.snapshot(nowEpochMs)
        else runCatching { source.snapshot(nowEpochMs) }.getOrNull()
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

/**
 * 表盘共享的秒表。`{sw*}` 一族读它，`sw_start_stop` / `sw_reset` 两个点击动作驱动它。
 *
 * ## 为什么是进程级单例
 *
 * 与 CloX 一致：秒表是**一块表盘共享的一个**，不是每个图层一份 —— 表盘上通常有好几个
 * 图层同时显示时/分/秒，它们必须读同一个计时。上游把它放在渲染层
 * （`WM_TAP_ACTIONS.md` §6：`sw_start_stop` / `sw_reset` 由渲染器处理、没有 Java handler），
 * 所以这里也不经宿主，直接由 provider 读进快照。
 *
 * 计时基准用 [android.os.SystemClock.elapsedRealtime] 的语义（由调用方传入 `nowMs`）
 * 而不是墙上时钟：改系统时间或跨时区不该让秒表跳。
 */
object WatchStopwatch {
    private val lock = Any()

    /** 正在跑时的起点；停着时为 null。 */
    private var startedAtMs: Long? = null

    /** 之前几段跑过的累计。 */
    private var accumulatedMs: Long = 0L

    val isRunning: Boolean get() = synchronized(lock) { startedAtMs != null }

    /** 到 [nowMs] 为止的累计毫秒。 */
    fun elapsedMs(nowMs: Long): Long = synchronized(lock) {
        val started = startedAtMs ?: return accumulatedMs
        (accumulatedMs + (nowMs - started)).coerceAtLeast(0L)
    }

    /** `sw_start_stop`：跑着就停、停着就跑。 */
    fun toggle(nowMs: Long) = synchronized(lock) {
        val started = startedAtMs
        if (started == null) {
            startedAtMs = nowMs
        } else {
            accumulatedMs = (accumulatedMs + (nowMs - started)).coerceAtLeast(0L)
            startedAtMs = null
        }
    }

    /** `sw_reset`：归零并停表。 */
    fun reset() = synchronized(lock) {
        startedAtMs = null
        accumulatedMs = 0L
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

/** Metadata plus bounded executable content used to decide whether a Lua VM may be reused. */
internal fun watchExecutableContentRevision(
    metadataRevision: String,
    initialScript: String?,
): String = buildString(metadataRevision.length + (initialScript?.length ?: 0) + 1) {
    append(metadataRevision)
    initialScript?.let { script ->
        append('\u0000')
        append(script)
    }
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
    data class Unavailable(
        val tag: String,
        val expressionFallback: String? = null,
    ) : WatchTagResolution("--")
    data class Unsupported(val tag: String) : WatchTagResolution("--")
}

/** Value supplied to arithmetic/Lua while [WatchTagResolution.renderedValue] remains user-facing. */
internal fun WatchTagResolution.expressionValue(): String? = when (this) {
    is WatchTagResolution.Resolved -> renderedValue
    is WatchTagResolution.Unavailable -> expressionFallback
    is WatchTagResolution.Unsupported -> null
}

/**
 * 属性表达式路径的取值：**缺数据的变量给中性 0，而不是 nil**。
 *
 * ## 为什么不能给 nil
 *
 * 属性表达式代入 nil 之后，一进算术或比较就整条求值失败，于是属性**落回默认值** ——
 * opacity 落回 100、x/y 落回 0。也就是说少认识一个变量，代价不是那一个字段显示异常，
 * 而是整块表盘看起来加载不了：图层全亮叠在一起，或者全部堆到左上角。老师转述的
 * 「编辑器缺少关键变量导致加载不了」就是这个。同一个机理之前还以另一种面目出现过一次
 * （表达式长度上限卡住 9 条天气图标的 opacity，九个图标同时全亮）。
 *
 * 给 0 之后，缺变量只影响真正读它的那一个字段，其余图层照常。上游 CloX 走的是同一个
 * 思路 —— 它的引擎把未知变量登记成静态 `""`（`wf_expr.lua` 的 `engine:activate`），
 * 表达式照样算得完，而不是让整条式子废掉。我们这边属性是数值语境，中性值取 0。
 *
 * ## 为什么 Lua 那条路不改
 *
 * [expressionValue] 仍然保留 nil：Lua 脚本里 `0` 是**真值**，把 nil 换成 0 会让
 * `if wm_tag("cal") then` 从走假分支变成走真分支，那是把一类问题换成另一类。脚本作者
 * 是按 nil 语义写的，那条路照旧。
 *
 * 文本语境也不走这里：模板节点只取 [WatchTagResolution.Resolved] 的
 * [WatchTagResolution.renderedValue]，缺数据自然落成空串 / `--`。
 */
internal fun WatchTagResolution.attributeExpressionValue(): String? = when (this) {
    // 空串在**数值**语境里就是「没有值」：{aw3w} 一族刻意渲染成空白（见 resolve 里
    // 那一段），可一旦有人把它代进算式，空串会让整条表达式失败、属性落回默认值 ——
    // 那正是这个函数存在的理由，所以在这里一并收进中性 0。文本语境不经过这里，
    // 空白照样是空白。
    is WatchTagResolution.Resolved -> renderedValue.ifEmpty { MISSING_TAG_NEUTRAL_VALUE }
    is WatchTagResolution.Unavailable -> expressionFallback ?: MISSING_TAG_NEUTRAL_VALUE
    is WatchTagResolution.Unsupported -> MISSING_TAG_NEUTRAL_VALUE
}

/** 属性表达式里「这个变量没有值」的中性替身。见 [attributeExpressionValue]。 */
internal const val MISSING_TAG_NEUTRAL_VALUE: String = "0"

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
        "bv", "bi",
        "sax", "say", "saz", "sprs", "pwc", "nwip", "nc", "ncc", "ncs", "ngc",
        // ── 以下都是「词表里认识、但 Flue 没有数据源」──────────────────────
        // 认识就够了：走 Unavailable 渲染 `--`、在属性表达式里取中性 0，表盘照样
        // 加载，只有真正读它的那个字段显示不可用。不认识才是灾难 —— 那会让含它的
        // 表达式整条失败、属性落回默认值，整块表盘看起来加载不了。
        //
        // 手机电池温度：独立手表没有配对手机，本机温度走 {btc}/{btf}。
        "pbtc", "pbtf", "pbtcd", "pbtfd",
        // what3words 需要第三方服务与 API key。
        "aw3w", "aw3w1", "aw3w2", "aw3w3",
    )

    private val builtInTags = setOf(
        "dh", "dhz", "dh11", "dh11z", "dh12", "dh12z",
        "dh23", "dh24", "dh23z", "dh24z", "da", "dampm",
        "dm", "dmz", "ds", "dsz", "dss", "dssz", "dsps",
        "drss", "drs", "drms", "drm", "drm0", "drh", "drh0", "drh24", "drmh24",
        "dz", "dzz", "dtp",
        "dd", "ddz", "ddy", "ddw0", "ddw", "ddw1", "ddw2", "ddww",
        "dn", "dnn", "dnnn", "dnnnn", "dy", "dyy", "dyyyy",
        // {dw} = 一年中的第几周（**不是**星期几），{ddim} = 本月天数。
        "dw", "ddim",
        // 设备/系统信息：语言码、区域码、是否 24 小时制。
        "alangcode", "alangreg", "d24h",
        "cnc",
        "bl", "blp", "batt", "battery", "battery_level",
        "pbl", "pblp", "pbr",
        "bc", "charging", "is_charging",
        "ssc", "steps", "step_count", "sstr", "shr", "heart", "heart_rate", "heart_rate_bpm",
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
        // ── 以下是本次按 WM_TAGS.md 补齐的、能就地算出真值的部分 ──────────────
        // 日期时间派生：位数提取、字钟读法、Unix 秒、UTC 偏移、今年天数、本月周序。
        "dh23i", "depoch", "dutcoff", "ddiy", "dwm",
        "dh23tt", "dh23to", "dhtt", "dhto", "dh11tt", "dh11to", "dh24tt", "dh24to",
        "dmt", "dmo", "dst", "dso",
        "dht", "dh24t", "dmat", "dmtt", "dmot", "dsat", "dstt", "dsot",
        // 时区 1 = 设备当前时区，全部可由 TimeZone 算出。2/3 需要用户另配，见
        // knownUnavailableTags 的族正则。
        "tz1l", "tz1ll", "tz1o", "tz1om", "tz1dst", "tz1t", "tz1rh", "tz1rh24", "tz1rm",
        // 语言全名 / 系统名 / 蓝牙开关。
        "alangfull", "aos", "abtc",
        // 配色切换器的另外三档。
        "ucolor2", "ucolor3", "ucolor_b",
        // 日出日落在一天中的位置（由已有的 sunriseText/sunsetText 换算）。
        "wsrp", "wssp",
        // 本机电量旋转值。
        "br",
        // ── N5 接上数据源之后从 knownUnavailableTags 移过来的 ────────────────
        // 罗盘（磁力计 + 加速度计融合）。
        "scr", "sct", "sctd", "scb", "scbb", "sctdb", "sctdbb",
        // 电池温度（电池广播的 EXTRA_TEMPERATURE）。
        "btc", "btf", "btcd", "btfd",
        // 设备与系统信息。
        "aosv", "aname", "amodel", "aman", "awname", "around", "atyre",
        "avol", "abrt", "alowpw", "adark", "areboot", "abright", "adimlo", "abss", "abssl",
        // 内存与存储。
        "amu", "amuf", "amup", "amf", "amff", "amfp", "amt", "amtf",
        "adsu", "adsuf", "adsup", "adsf", "adsff", "adsfp", "adst", "adstf",
        // 位置（复用天气那份已授权的定位）。
        "alat", "alon", "alatd", "alatdd", "alond", "alondd", "aalt",
        // 风。
        "wws", "wwd", "wwdb", "wwdbb",
        // 秒表（由 sw_start_stop / sw_reset 点击动作驱动）。
        "swr", "swh", "swm", "sws", "swss", "swsss", "swsst",
        "swrm", "swrs", "swrss",
        // 健康扩展。没有健康数据源时与 clox 逐位同值（0 与硬编码目标），不是 `--`。
        "scal", "stcal", "scalp", "sdst", "stdst", "sdstp", "sdstu",
        "hfc", "ham", "htam", "hae", "htae", "has", "htas",
        // 日程「有没有」。逐条事件 {cNxx} 由 CALENDAR_EVENT_TAG 族覆盖，两者共用
        // 同一份 snapshot.calendarEvents。
        "cex",
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
        "\\{\\s*(?:utc_?)?(?:dssz?|dsps|drss|drms|drm|dtp)(?:_?utc)?\\s*\\}" +
            // 秒表的亚秒读数：厘秒 / 毫秒 / 总毫秒 / 带毫秒平滑的秒针。
            // 长的分支必须在前，否则 `swsss` 会先被 `swss` 吃掉。
            "|\\{\\s*(?:swsss|swsst|swss|swrss)\\s*\\}",
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
            futureWeekdayTagParts(normalized) != null ||
            isUnavailableFamilyTag(normalized)
    }

    /**
     * 成族出现、我们暂时都没有数据源的标签。
     *
     * 写成正则而不是把几百个名字铺进集合：这三族分别是 10 × 16、4 × 5、2 × 9 条，
     * 铺开将近 250 行，而且以后上游把日程从 10 条扩到 20 条时，正则不用改。
     *
     * 依然必须**认识**它们 —— 认识才会走 Unavailable（渲染 `--`、表达式里取中性 0），
     * 不认识会让含它的表达式整条失败、属性落回默认值。
     */
    private fun isUnavailableFamilyTag(tag: String): Boolean =
        CALENDAR_EVENT_TAG.matches(tag) ||
            COMPLICATION_TAG.matches(tag) ||
            ALTERNATE_TIMEZONE_TAG.matches(tag)

    /**
     * 日程标签求值。
     *
     * 返回 null 有两种情形，都该落到 Unavailable（`--`）：日程读不到
     * （[WatchDataSnapshot.calendarEvents] 是 null），或者那个序号没有对应的日程
     * （只有 2 条日程时的 {c5t}）。**唯独 {cNex} 例外** —— 它问的就是「有没有第 N 条」，
     * 所以在能读到日程的前提下必须答 "false" 而不是 `--`。
     */
    private fun resolveCalendarTag(
        tag: String,
        snapshot: WatchDataSnapshot,
        now: Calendar,
    ): String? {
        val events = snapshot.calendarEvents ?: return null
        if (tag == "cex") return events.isNotEmpty().toString()
        val match = CALENDAR_EVENT_TAG_PARTS.matchEntire(tag) ?: return null
        val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return null
        val field = match.groupValues[2]
        val event = events.getOrNull(index)
        if (field == "ex") return (event != null).toString()
        if (event == null) return null
        return when (field) {
            "t" -> event.title?.takeIf(String::isNotBlank)
            "l" -> event.location?.takeIf(String::isNotBlank)
            "cal" -> event.calendarName?.takeIf(String::isNotBlank)
            "c" -> event.colorRgb?.takeIf(String::isNotBlank)
            "i" -> event.id?.takeIf(String::isNotBlank)
            "ad" -> event.allDay.toString()
            "b" -> event.beginEpochMs?.let { clockTextAt(it, now) }
            "e" -> event.endEpochMs?.let { clockTextAt(it, now) }
            "bd" -> event.beginEpochMs?.let { dateTextAt(it, now) }
            "ed" -> event.endEpochMs?.let { dateTextAt(it, now) }
            // 旋转值与百分比都是「在一天当中的位置」，只是量程不同（360 / 100）。
            "br" -> event.beginEpochMs?.let { renderCompactNumber(dayFractionAt(it, now) * 360f) }
            "bp" -> event.beginEpochMs?.let { renderCompactNumber(dayFractionAt(it, now) * 100f) }
            "er" -> event.endEpochMs?.let { renderCompactNumber(dayFractionAt(it, now) * 360f) }
            "ep" -> event.endEpochMs?.let { renderCompactNumber(dayFractionAt(it, now) * 100f) }
            else -> null
        }
    }

    /** 与 [CALENDAR_EVENT_TAG] 同一套分支，只是这里要把序号与字段名捕获出来。 */
    private val CALENDAR_EVENT_TAG_PARTS =
        Regex("^c(10|[1-9])(ex|bd|br|bp|ed|er|ep|cal|ad|t|b|e|l|c|i)$")

    private fun calendarAtSameZone(epochMs: Long, now: Calendar): Calendar =
        Calendar.getInstance(now.timeZone).apply { timeInMillis = epochMs }

    private fun clockTextAt(epochMs: Long, now: Calendar): String =
        calendarAtSameZone(epochMs, now).let {
            "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))
        }

    private fun dateTextAt(epochMs: Long, now: Calendar): String =
        calendarAtSameZone(epochMs, now).let {
            "%04d-%02d-%02d".format(
                it.get(Calendar.YEAR),
                it.get(Calendar.MONTH) + 1,
                it.get(Calendar.DAY_OF_MONTH),
            )
        }

    private fun dayFractionAt(epochMs: Long, now: Calendar): Float =
        calendarAtSameZone(epochMs, now).let {
            (it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)) / 1_440f
        }

    /**
     * 日程 1–10 的各字段（WM_TAGS.md §Calendar）。
     *
     * 交替分支**必须长的在前**：`bd` / `br` / `bp` 要先于 `b`，`ed` / `er` / `ep`
     * 先于 `e`，`cal` 先于 `c`，否则 `c1bd` 会在 `b` 上匹配掉、剩下的 `d` 让整条失配。
     */
    private val CALENDAR_EVENT_TAG =
        Regex("^c(?:10|[1-9])(?:ex|bd|br|bp|ed|er|ep|cal|ad|t|b|e|l|c|i)$")

    /** 复杂功能槽 1–4 的字段（Studio 的 20 条 m 标签）。 */
    private val COMPLICATION_TAG = Regex("^m[1-4](?:text|title|value|min|max)$")

    /** 时区 2 / 3。同理长分支在前：`ll` 先于 `l`、`rh24` 先于 `rh`、`om` 先于 `o`。 */
    private val ALTERNATE_TIMEZONE_TAG =
        Regex("^tz[23](?:ll|rh24|rh|rm|dst|om|o|l|t)$")

    /** Shared high-refresh vocabulary for runtime and editor preview. */
    fun requiresFastTicks(raw: String): Boolean =
        fastTickTagPattern.containsMatchIn(raw) || watchCounterRequiresFrameTicks(raw)

    /**
     * `WM_TAGS.md` §Location 的全部标签。
     *
     * 与 `WatchFeatureDetector.LOCATION_TAGS` 是同一份名单，只是用途不同：detector
     * 拿它算导出包的 `features`，这里拿它判断「这张表盘要不要一次位置权限引导」。
     * 两份必须逐字相同，`WatchmakerStudioTagValuesTest` 里有一条断言钉住 ——
     * 漂移的后果是「市场上标着用了定位、运行时却从不申请权限」。
     *
     * {aw3w*} 也算在内：three-word 地址本身就是坐标的另一种写法，接上服务后同样要定位。
     */
    val LOCATION_TAGS: Set<String> = setOf(
        "aalt", "alat", "alatd", "alatdd", "alon", "alond", "alondd",
        "aw3w", "aw3w1", "aw3w2", "aw3w3",
    )

    /**
     * 表盘文本（watch.xml 全文或单个属性）里是否用到位置标签。
     *
     * 不能只认「整个花括号就是一个标签」：表盘里坐标常混在算式中
     * （`rotation="{alat*2}"`），所以进花括号之后再逐个抓标识符 —— 与
     * `WatchFeatureDetector.tagIdentifiers` 同一口径。也不能直接对全文做子串搜索：
     * `{alangcode}` 里就含 `ala`，而花括号外的字面文本 `alat` 只是普通文字。
     */
    private val BRACED_GROUP = Regex("""\{([^\{\}]*)\}""")
    private val TAG_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    fun usesLocationTags(raw: String): Boolean =
        BRACED_GROUP.findAll(raw).any { braced ->
            TAG_IDENTIFIER.findAll(braced.groupValues[1]).any { identifier ->
                identifier.value.lowercase(Locale.ROOT) in LOCATION_TAGS
            }
        }

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
            // CloX wf_tagdefs.lua:235: c.t.yday，1 月 1 日为 1。
            "ddy" -> effectiveNow.get(Calendar.DAY_OF_YEAR).toString()
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
            // {dh23i} = 恒 24 小时制、忽略设备的 12/24 设置。我们的 {dh23} 读的就是
            // Calendar.HOUR_OF_DAY，本来就不受设备设置影响，所以两者同值。
            "dh23i" -> hour24.toString()
            // Unix 秒。WM_TAGS.md:468。
            "depoch" -> (effectiveNow.timeInMillis / 1_000L).toString()
            // ±HHMM。WM_TAGS.md:455 的例子是 `+0800`（没有冒号，与 {tz1o} 不同）。
            "dutcoff" -> formatUtcOffsetCompact(
                effectiveNow.timeZone.getOffset(effectiveNow.timeInMillis),
            )
            // 今年天数 365/366。注意与 {ddy}（今天是第几天）区分。
            "ddiy" -> effectiveNow.getActualMaximum(Calendar.DAY_OF_YEAR).toString()
            // 本月第几周 1–5。
            "dwm" -> effectiveNow.get(Calendar.WEEK_OF_MONTH).toString()
            // 位数提取（WM_TAGS.md §Digit Extraction）。全是纯算术，没有数据源。
            "dh23tt" -> (hour24 / 10).toString()
            "dh23to" -> (hour24 % 10).toString()
            "dhtt" -> (hour12 / 10).toString()
            "dhto" -> (hour12 % 10).toString()
            "dh11tt" -> ((hour24 % 12) / 10).toString()
            "dh11to" -> ((hour24 % 12) % 10).toString()
            "dh24tt" -> (if (hour24 == 0) 24 else hour24).let { it / 10 }.toString()
            "dh24to" -> (if (hour24 == 0) 24 else hour24).let { it % 10 }.toString()
            "dmt" -> (effectiveNow.get(Calendar.MINUTE) / 10).toString()
            "dmo" -> (effectiveNow.get(Calendar.MINUTE) % 10).toString()
            "dst" -> (effectiveNow.get(Calendar.SECOND) / 10).toString()
            "dso" -> (effectiveNow.get(Calendar.SECOND) % 10).toString()
            // 文字读法（WM_TAGS.md §Text Representations）。英文 —— 上游那张拆分表
            // 就是英文的（`o'clock` / `o'three` / `forty six`），换成本地语言会让
            // 按它排版的字钟表盘直接错位。
            "dht" -> englishNumberWord(hour12)
            "dh24t" -> englishNumberWord(if (hour24 == 0) 24 else hour24)
            "dmat" -> wordClockFull(effectiveNow.get(Calendar.MINUTE))
            "dmtt" -> wordClockTens(effectiveNow.get(Calendar.MINUTE))
            "dmot" -> wordClockOnes(effectiveNow.get(Calendar.MINUTE))
            "dsat" -> wordClockFull(effectiveNow.get(Calendar.SECOND))
            "dstt" -> wordClockTens(effectiveNow.get(Calendar.SECOND))
            "dsot" -> wordClockOnes(effectiveNow.get(Calendar.SECOND))
            // 时区 1 = 设备当前时区（WM_TAGS.md §Time Zone 1）。2 / 3 需要用户另外
            // 配置两个时区，我们没有那份设置，交给 knownUnavailableTags 渲染 --。
            "tz1l" -> effectiveNow.timeZone.id.substringAfterLast('/').replace('_', ' ')
            "tz1ll" -> effectiveNow.timeZone.id
            "tz1o" -> formatUtcOffsetColon(
                effectiveNow.timeZone.getOffset(effectiveNow.timeInMillis),
            )
            "tz1om" -> (effectiveNow.timeZone.getOffset(effectiveNow.timeInMillis) / 60_000).toString()
            "tz1dst" -> effectiveNow.timeZone.inDaylightTime(effectiveNow.time).toString()
            "tz1t" -> "%02d:%02d".format(hour24, effectiveNow.get(Calendar.MINUTE))
            "tz1rh" -> renderCompactNumber(
                (hour24 % 12 + effectiveNow.get(Calendar.MINUTE) / 60f) * 30f,
            )
            "tz1rh24" -> renderCompactNumber(
                (hour24 + effectiveNow.get(Calendar.MINUTE) / 60f) * 15f,
            )
            "tz1rm" -> renderCompactNumber(
                (effectiveNow.get(Calendar.MINUTE) + effectiveNow.get(Calendar.SECOND) / 60f) * 6f,
            )
            // 语言全名（{alangcode} / {alangreg} 已有）。
            "alangfull" -> locale.getDisplayLanguage(locale).takeIf(String::isNotBlank)
            // 系统名。这是个常量，没有数据源可缺。
            "aos" -> "Android"
            // 蓝牙是否开启。快照里的 bluetoothEnabled 正是这个语义
            // （{nbc} 是「已连接」，两者不同）。
            "abtc" -> snapshot.bluetoothEnabled?.toString()

            // ── 设备与系统（N5：由 provider 读进快照，解析器仍是纯函数）────────
            "aosv" -> snapshot.osVersion
            "aname" -> snapshot.deviceName
            "amodel" -> snapshot.deviceModel
            "aman" -> snapshot.deviceManufacturer
            "awname" -> snapshot.watchName
            "around" -> snapshot.screenRound?.toString()
            "atyre" -> snapshot.screenFlatTyre?.toString()
            "adark" -> snapshot.darkMode?.toString()
            "alowpw" -> snapshot.lowPowerMode?.toString()
            "avol" -> snapshot.volumePercent?.toString()
            "abrt" -> snapshot.screenBrightness?.toString()
            "areboot" -> snapshot.lastRebootText
            "abright" -> snapshot.brightMode?.toString()
            "adimlo" -> snapshot.dimLowBitOnly?.toString()
            // 息屏时按规范给 -1，而不是 0 —— 表盘用 `< 0` 判断「现在是息屏」。
            "abss" -> snapshot.millisSinceBright?.toString() ?: dimSentinel(snapshot)
            "abssl" -> snapshot.millisSinceBright
                ?.let { renderCompactNumber((it / 1_000f).coerceAtMost(ABSSL_CAP_SECONDS)) }
                ?: dimSentinel(snapshot)

            // ── 内存与存储。GB 一律按**十进制** 1e9 算（规范示例 106.4 对得上
            // 128GB 机型的数据分区，按 GiB 算会是 99 左右）。
            "amu" -> usedBytes(snapshot.ramUsedBytes, snapshot.ramTotalBytes, snapshot.ramFreeBytes)
                ?.let(::renderGigabytes)
            "amuf" -> usedBytes(snapshot.ramUsedBytes, snapshot.ramTotalBytes, snapshot.ramFreeBytes)
                ?.let(::renderGigabytesWithUnit)
            "amup" -> percentOf(
                usedBytes(snapshot.ramUsedBytes, snapshot.ramTotalBytes, snapshot.ramFreeBytes),
                snapshot.ramTotalBytes,
            )
            "amf" -> snapshot.ramFreeBytes?.let(::renderGigabytes)
            "amff" -> snapshot.ramFreeBytes?.let(::renderGigabytesWithUnit)
            "amfp" -> percentOf(snapshot.ramFreeBytes, snapshot.ramTotalBytes)
            "amt" -> snapshot.ramTotalBytes?.let(::renderGigabytes)
            "amtf" -> snapshot.ramTotalBytes?.let(::renderGigabytesWithUnit)
            "adsu" -> usedBytes(
                snapshot.storageUsedBytes,
                snapshot.storageTotalBytes,
                snapshot.storageFreeBytes,
            )?.let(::renderGigabytes)
            "adsuf" -> usedBytes(
                snapshot.storageUsedBytes,
                snapshot.storageTotalBytes,
                snapshot.storageFreeBytes,
            )?.let(::renderGigabytesWithUnit)
            "adsup" -> percentOf(
                usedBytes(
                    snapshot.storageUsedBytes,
                    snapshot.storageTotalBytes,
                    snapshot.storageFreeBytes,
                ),
                snapshot.storageTotalBytes,
            )
            "adsf" -> snapshot.storageFreeBytes?.let(::renderGigabytes)
            "adsff" -> snapshot.storageFreeBytes?.let(::renderGigabytesWithUnit)
            "adsfp" -> percentOf(snapshot.storageFreeBytes, snapshot.storageTotalBytes)
            "adst" -> snapshot.storageTotalBytes?.let(::renderGigabytes)
            "adstf" -> snapshot.storageTotalBytes?.let(::renderGigabytesWithUnit)

            // ── 电池温度。快照存摄氏，华氏在这里换。
            // 取整：规范给的两个例子都是整数（34 / 94），而电池温度那 0.1°C 是噪声，
            // 表盘上显示 `93.56°F` 只会显得没做完。
            "btc" -> snapshot.batteryTemperatureCelsius?.let { it.roundToInt().toString() }
            "btf" -> snapshot.batteryTemperatureCelsius
                ?.let { (it * 9f / 5f + 32f).roundToInt().toString() }
            "btcd" -> snapshot.batteryTemperatureCelsius?.let { "${it.roundToInt()}°C" }
            "btfd" -> snapshot.batteryTemperatureCelsius
                ?.let { "${(it * 9f / 5f + 32f).roundToInt()}°F" }

            // ── 罗盘。{scr} 是**负的**方位角且保留小数（规范示例 -334.581），
            // {sct} 是四舍五入的整数 —— 两者刻意不同，表盘拿 scr 直接当旋转值。
            "scr" -> snapshot.compassHeadingDegrees?.let { renderCompactNumber(-it) }
            "sct" -> snapshot.compassHeadingDegrees?.let { it.roundToInt().toString() }
            "sctd" -> snapshot.compassHeadingDegrees?.let { "${it.roundToInt()}°" }
            "scb" -> snapshot.compassHeadingDegrees?.let { compassBearing(it, sixteenWay = false) }
            "scbb" -> snapshot.compassHeadingDegrees?.let { compassBearing(it, sixteenWay = true) }
            // 度数 + **空格** + 方位（2026-07-07 对着 WM 截图核过，早先版本没空格）。
            "sctdb" -> snapshot.compassHeadingDegrees
                ?.let { "${it.roundToInt()}° ${compassBearing(it, sixteenWay = false)}" }
            "sctdbb" -> snapshot.compassHeadingDegrees
                ?.let { "${it.roundToInt()}° ${compassBearing(it, sixteenWay = true)}" }

            // ── 位置。复用天气那份已授权的定位，不另外申请权限。
            //
            // **没有定位数据时给 0，不给 `--`**。上游 clox 写的是
            // `WF.location.latitude or 0`（`wf_tagdefs.lua:483-495`），无 fix 时
            // {alat} 就是 `0`、{alatd} 是 `"0.0"`、{alatdd} 是 `"0.0°N"`、{aalt} 是 `0`。
            // 早先这里走 `?.let{}` 落到 Unavailable，于是同一张表盘在 clox 上显示 `0`、
            // 在 Flue 上显示 `--`，用户只能报「经纬度变量读不出来」；更糟的是 `--` 与
            // 「不认识这个 tag」是同一个外观，连排查方向都被指错。逐位照上游才叫兼容。
            //
            // 渲染层的授权引导见 [WatchLocationPermissionPolicy]。{aalt} 仅使用
            // 定位对象实际提供的 elevation；中国天气未提供时沿用缺失值口径。
            "alat" -> renderCoordinate(locationDegreesOrZero(snapshot.latitude))
            "alon" -> renderCoordinate(locationDegreesOrZero(snapshot.longitude))
            // 固定一位小数，**不能走 renderOneDecimal** —— 那条路经 renderCompactNumber
            // 把 `.0` 吃掉，`52.0` 会渲染成 `52`。见 [renderFixedOneDecimal]。
            "alatd" -> renderFixedOneDecimal(abs(locationDegreesOrZero(snapshot.latitude)))
            "alond" -> renderFixedOneDecimal(abs(locationDegreesOrZero(snapshot.longitude)))
            "alatdd" -> locationDegreesOrZero(snapshot.latitude).let { degrees ->
                "${renderFixedOneDecimal(abs(degrees))}°${if (degrees < 0) "S" else "N"}"
            }
            "alondd" -> locationDegreesOrZero(snapshot.longitude).let { degrees ->
                "${renderFixedOneDecimal(abs(degrees))}°${if (degrees < 0) "W" else "E"}"
            }
            // 海拔照旧走 Float：地球海拔跨度 ±9 km，Float 的有效位远够用，
            // 而经纬度不行（见 [renderCoordinate]）。
            "aalt" -> renderCompactNumber(locationDegreesOrZero(snapshot.altitudeMeters).toFloat())

            // what3words 要第三方服务与 API key，Flue 永远拿不到值。给**空串**而不是
            // `--`：上游引擎把没有值的变量登记成静态 ""（`wf_expr.lua` 的
            // `engine:activate`），而表盘上一个居中的 `--` 看着像功能坏了，空白才读作
            // 「这项没配」。仍留在 knownUnavailableTags 里，词表语义不变。
            "aw3w", "aw3w1", "aw3w2", "aw3w3" -> ""

            // ── 风。规范说 {wws} 是 mph 或 m/s，我们统一 m/s（数据源直接要 m/s）。
            "wws" -> weather?.windSpeedMetersPerSecond?.let { renderOneDecimal(it) }
            "wwd" -> weather?.windDirectionDegrees?.let { it.roundToInt().toString() }
            "wwdb" -> weather?.windDirectionDegrees
                ?.let { compassBearing(it.toFloat(), sixteenWay = false) }
            "wwdbb" -> weather?.windDirectionDegrees
                ?.let { compassBearing(it.toFloat(), sixteenWay = true) }

            // ── 秒表。格式逐位照 clox（`wf_tagdefs.lua:138-144`）：时/分/秒/厘秒
            // 两位补零、毫秒三位补零、{swsst} 是总毫秒整数、{swr} 是 "true"/"false"。
            "swr" -> stopwatchRunning(snapshot).toString()
            "swsst" -> stopwatchElapsed(snapshot).toString()
            "swh" -> (stopwatchElapsed(snapshot) / 3_600_000L).toString().padStart(2, '0')
            "swm" -> (stopwatchElapsed(snapshot) % 3_600_000L / 60_000L).toString().padStart(2, '0')
            "sws" -> (stopwatchElapsed(snapshot) % 60_000L / 1_000L).toString().padStart(2, '0')
            "swss" -> (stopwatchElapsed(snapshot) % 1_000L / 10L).toString().padStart(2, '0')
            "swsss" -> (stopwatchElapsed(snapshot) % 1_000L).toString().padStart(3, '0')
            "swrm" -> renderCompactNumber(
                (stopwatchElapsed(snapshot) % 3_600_000L) / 60_000f * 6f,
            )
            "swrs" -> renderCompactNumber((stopwatchElapsed(snapshot) % 60_000L / 1_000L) * 6f)
            "swrss" -> renderCompactNumber((stopwatchElapsed(snapshot) % 60_000L) / 1_000f * 6f)

            // ── 健康扩展。**没有健康数据源时与 clox 逐位同值**，不是 `--`：
            // clox 的 `WF.health_steps` 默认 distance/calories 都是 0
            // （`watchface_bridge.lua:127`），目标值是硬编码的 8.0 km / 350 kcal
            // （`wf_tagdefs.lua:352-357`），而 hae/ham/has/hfc 一族直接 return 0
            // （`wf_tagdefs.lua:510-516`）。表盘在 clox 上看到的就是这些数，我们照抄
            // 才叫兼容；自己按步数折算出一个距离反而是编数字。
            "sdst" -> "0"
            "stdst" -> DEFAULT_DISTANCE_GOAL_TEXT
            "sdstp" -> "0"
            "sdstu" -> "km"
            "scal" -> "0"
            "stcal" -> DEFAULT_CALORIE_GOAL.toString()
            "scalp" -> "0"
            "hae", "ham", "has", "hfc", "htae", "htam", "htas" -> "0"
            "bl", "batt", "battery", "battery_level" -> snapshot.batteryPercent?.toString()
            "blp" -> snapshot.batteryPercent?.let { "$it%" }
            "pbl" -> snapshot.batteryPercent?.toString()
            "pblp" -> snapshot.batteryPercent?.let { "$it%" }
            "pbr" -> snapshot.batteryPercent?.let { (it * 3.6f).toString() }
            // {br} = 表盘电量的旋转值，每 1% 3.6°。与 {pbr} 同一算法，只是数据源是
            // 本机电量而不是手机电量。WM_TAGS.md:333。
            "br" -> snapshot.batteryPercent?.let { (it * 3.6f).toString() }
            "bc" -> if (snapshot.isCharging == true) "Charging" else "Discharging"
            "charging", "is_charging" -> if (snapshot.isCharging == true) "1" else "0"
            "ssc", "steps", "step_count" -> snapshot.steps?.toString()
            // BHT 2.0 stress is not yet backed by a Flue provider. The
            // WatchMaker definition explicitly specifies a numeric 0 default.
            "sstr" -> "0"
            "shr", "heart", "heart_rate", "heart_rate_bpm" ->
                snapshot.heartRateBpm?.toString()
            // WM_TAGS.md: {sthr} = Maximum recorded heart rate（示例 200），
            // {shrp} = Heart rate as % of maximum。Flue 没有用户健康档案，
            // 因此按规范示例使用固定的 200 BPM 作为最大心率。
            "sthr" -> DEFAULT_MAX_HEART_RATE_BPM.toString()
            "shrp" -> snapshot.heartRateBpm?.let { bpm ->
                (bpm * 100 / DEFAULT_MAX_HEART_RATE_BPM).coerceIn(0, 100).toString()
            }
            // Flue does not persist the vendor's minute-by-minute heart-rate history.
            "shr_1", "shr_2", "shr_3", "shr_4", "shr_5", "shr_6", "shr_7", "shr_8", "shr_9" -> null
            "sgx" -> renderCompactNumber(snapshot.gyroscopeX ?: 0f)
            "sgy" -> renderCompactNumber(snapshot.gyroscopeY ?: 0f)
            "sgz" -> renderCompactNumber(snapshot.gyroscopeZ ?: 0f)
            "sax" -> snapshot.accelerometerX?.let(::renderCompactNumber)
            "say" -> snapshot.accelerometerY?.let(::renderCompactNumber)
            "saz" -> snapshot.accelerometerZ?.let(::renderCompactNumber)
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
            // 配色切换器的第二/第三档。我们只有一个用户强调色，所以两档都落回它 ——
            // **不能**让它变成 `--`：这三个标签几乎只出现在 color 属性上，那里拿到
            // 一个非颜色字符串，整个图层的颜色就崩了。上游 CloX 同样宁可返回一个
            // 可用颜色（`wf_tagdefs.lua:598-601` 四个都桩成 "ffffff"），我们至少
            // 还给的是用户真正选的那个色。
            "ucolor2", "ucolor3" -> userColor
            // 当前色的提亮版。按 35% 向白混合 —— 够亮到能当高光用，又不至于把
            // 有色相的强调色洗成纯白。
            "ucolor_b" -> userColor?.let(::brightenWatchColorLiteral)
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
            // 日出/日落在一天中的位置（0–1）。与 {wsr}/{wss} 同源，只是换算成分数，
            // 好让表盘拿它直接当环形进度或指针角度用。
            "wsrp" -> weather?.sunriseText?.let(::clockTextAsDayFraction)
                ?.let(::renderCompactNumber)
            "wssp" -> weather?.sunsetText?.let(::clockTextAsDayFraction)
                ?.let(::renderCompactNumber)
            // {wmp} 必须是 0..9 的**整数**：表盘用 `{wmp}==5` 这种等值比较，
            // 推小数永远不匹配（clox `wf_tagdefs.lua:329-334` 记了这个坑）。
            // Flue 没有月相 provider，按 clox `WeatherProvider.kt:124` 的本地
            // 天文算法自算，纯日期函数、无需数据源。
            "wmp" -> moonPhaseIndex(effectiveNow).toString()
            else -> resolveForecastTag(normalized, weather)
                ?: resolveHourlyForecastTag(normalized, weather)
                ?: resolveFutureWeekdayTag(normalized, effectiveNow, locale)
                ?: resolveCalendarTag(normalized, snapshot, effectiveNow)
        }
        if (value != null) return WatchTagResolution.Resolved(value)
        return if (isKnown(requestedTag)) {
            val expressionFallback = when (normalized) {
                "shr", "heart", "heart_rate", "heart_rate_bpm", "shrp",
                "shr_1", "shr_2", "shr_3", "shr_4", "shr_5", "shr_6", "shr_7", "shr_8", "shr_9" -> "0"
                else -> null
            }
            WatchTagResolution.Unavailable(requestedTag, expressionFallback)
        } else {
            WatchTagResolution.Unsupported(requestedTag)
        }
    }

    private data class ForecastTagParts(
        val dayOffset: Int,
        val field: String,
        val withUnit: Boolean = false,
    )

    // 预报 tag 的兜底解析链：不认识的 tag 每次解析最多要挨个试完全部正则，
    // 编译提到常量，别在调用点反复构造。
    private val FORECAST_CANONICAL_TAG = Regex("^wf([0-5])d(t|th|tl|ct|ci)$")
    private val FORECAST_LEGACY_TAG = Regex("^wf([a-e])(h|hd|l|ld|ct|ci)$")
    private val FORECAST_NUMBERED_TAG = Regex("^wf(\\d+)(h|hd|l|ld|ct|ci)$")
    private val FORECAST_ALT_NUMBERED_TAG = Regex("^wfa(\\d+)(h|hd|l|ld|ct|ci)$")
    private val FORECAST_SUFFIX_TAG = Regex("^(wthd|wth|wtld|wtl|wct|wci)(\\d+)$")

    private fun forecastTagParts(tag: String): ForecastTagParts? {
        // Canonical WatchMaker daily vocabulary: 0=today, 1=tomorrow, ... 5.
        FORECAST_CANONICAL_TAG.matchEntire(tag)?.let { match ->
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
            else -> FORECAST_LEGACY_TAG.matchEntire(tag)?.let { match ->
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
        FORECAST_NUMBERED_TAG.matchEntire(tag)?.let { match ->
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
        FORECAST_ALT_NUMBERED_TAG.matchEntire(tag)?.let { match ->
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
        val numbered = FORECAST_SUFFIX_TAG.matchEntire(tag) ?: return null
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

    // ── 文字读法（字钟）─────────────────────────────────────────────────────
    // 拆分规则见 WM_TAGS.md §Text Representations 那张表。刻意写成英文：那张表
    // 本身就是英文的（`o'clock` / `o'three` / `forty six`），而字钟表盘是按词的
    // 长度和位置排版的，换成本地语言会直接错位。

    private val ONES_WORDS = listOf(
        "zero", "one", "two", "three", "four",
        "five", "six", "seven", "eight", "nine",
    )

    private val TEEN_WORDS = listOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )

    private val TENS_WORDS = mapOf(
        2 to "twenty", 3 to "thirty", 4 to "forty", 5 to "fifty",
        6 to "sixty", 7 to "seventy", 8 to "eighty", 9 to "ninety",
    )

    /** 0–99 的英文读法，复合数用空格分隔（`twenty one`）。 */
    private fun englishNumberWord(value: Int): String {
        val bounded = value.coerceIn(0, 99)
        return when {
            bounded < 10 -> ONES_WORDS[bounded]
            bounded < 20 -> TEEN_WORDS[bounded - 10]
            else -> {
                val tens = TENS_WORDS[bounded / 10].orEmpty()
                val ones = bounded % 10
                if (ones == 0) tens else "$tens ${ONES_WORDS[ones]}"
            }
        }
    }

    /** 字钟的「十位部分」。0–9 是 `o'`，10–19 整个 teen 词都算十位部分。 */
    private fun wordClockTens(value: Int): String {
        val bounded = value.coerceIn(0, 59)
        return when {
            bounded < 10 -> "o'"
            bounded < 20 -> TEEN_WORDS[bounded - 10]
            else -> TENS_WORDS[bounded / 10].orEmpty()
        }
    }

    /** 字钟的「个位部分」。0 是 `clock`（凑成 o'clock），10–19 与整十为空。 */
    private fun wordClockOnes(value: Int): String {
        val bounded = value.coerceIn(0, 59)
        return when {
            bounded == 0 -> "clock"
            bounded < 10 -> ONES_WORDS[bounded]
            bounded < 20 -> ""
            bounded % 10 == 0 -> ""
            else -> ONES_WORDS[bounded % 10]
        }
    }

    /** 字钟完整读法。0–9 的 `o'` 后面**不加空格**，20 以上用空格分隔。 */
    private fun wordClockFull(value: Int): String {
        val bounded = value.coerceIn(0, 59)
        if (bounded < 10) return "o'" + wordClockOnes(bounded)
        return englishNumberWord(bounded)
    }

    // ── 时区偏移格式 ───────────────────────────────────────────────────────
    // 两种写法都要，且刻意不合并：{dutcoff} 是 `+0800`（无冒号，WM_TAGS.md:455），
    // {tz1o} 是 `-7:00`（有冒号且小时不补零，WM_TAGS.md:365）。混用会让按其中
    // 一种做字符串比较的表盘失配。

    private fun formatUtcOffsetCompact(offsetMs: Int): String {
        val totalMinutes = offsetMs / 60_000
        val sign = if (totalMinutes < 0) "-" else "+"
        val absolute = abs(totalMinutes)
        return "%s%02d%02d".format(sign, absolute / 60, absolute % 60)
    }

    private fun formatUtcOffsetColon(offsetMs: Int): String {
        val totalMinutes = offsetMs / 60_000
        val sign = if (totalMinutes < 0) "-" else "+"
        val absolute = abs(totalMinutes)
        return "%s%d:%02d".format(sign, absolute / 60, absolute % 60)
    }

    /**
     * `HH:mm` → 一天中的位置（0–1）。供 {wsrp} / {wssp} 用。
     *
     * 解析失败返回 null 而不是 0：0 会被表盘当成「午夜日出」照着画，而 null 让这条
     * 标签走 Unavailable，至少是诚实的「没有数据」。
     */
    private fun clockTextAsDayFraction(raw: String?): Float? {
        val parts = raw?.trim()?.split(':') ?: return null
        if (parts.size < 2) return null
        val hour = parts[0].trim().toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts[1].trim().take(2).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return (hour * 60 + minute) / 1_440f
    }

    /**
     * 把 `RRGGBB` / `RRGGBBAA` 往白色方向混 35%，作为 {ucolor_b}。
     *
     * 解析不出来就原样返回 —— 返回一个**合法颜色**比返回 null 重要：这个标签只会
     * 出现在 color 属性上。
     */
    private fun brightenWatchColorLiteral(literal: String): String {
        // normalizeWatchColorLiteral 返回的是带 `#` 的 Android 形式，而 {ucolor} 这一族
        // 在表盘里是**不带** `#` 的 RRGGBB —— 提亮版必须保持同一形状，否则同一个
        // 属性上两个标签给出两种写法。
        val normalized = normalizeWatchColorLiteral(literal)?.removePrefix("#") ?: return literal
        val builder = StringBuilder(normalized.length)
        for (index in 0..2) {
            val channel = normalized.substring(index * 2, index * 2 + 2).toIntOrNull(16)
                ?: return literal
            val lifted = (channel + (255 - channel) * BRIGHTEN_FRACTION)
                .roundToInt()
                .coerceIn(0, 255)
            builder.append("%02x".format(lifted))
        }
        // 尾部的 alpha（如果有）原样带过去。
        builder.append(normalized.drop(6))
        return builder.toString()
    }

    private const val BRIGHTEN_FRACTION = 0.35f

    // ── N5 的格式化助手 ─────────────────────────────────────────────────────

    /** {abssl} 的上限：规范写「capped at 30」。 */
    private const val ABSSL_CAP_SECONDS = 30f

    /** clox 硬编码的日目标（`wf_tagdefs.lua:353,357`）。距离要保留那个 `.0`。 */
    private const val DEFAULT_DISTANCE_GOAL_TEXT = "8.0"
    private const val DEFAULT_CALORIE_GOAL = 350

    /**
     * 息屏时 {abss} / {abssl} 该给的哨兵值。
     *
     * 规范是「-1 if dim」。只有在**确知**处于息屏时才给 -1；连亮屏状态都不知道
     * （provider 没填）就返回 null 走 Unavailable —— 谎报 -1 会让表盘以为在息屏。
     */
    private fun dimSentinel(snapshot: WatchDataSnapshot): String? =
        if (snapshot.brightMode == false) "-1" else null

    /** 字节 → GB（十进制 1e9），一位小数，整数时不带小数点。 */
    private fun renderGigabytes(bytes: Long): String =
        renderCompactNumber(roundToOneDecimal(bytes / 1_000_000_000f))

    /**
     * 已用字节。显式给了就用，否则由「总量 − 可用」推。
     *
     * 「已用 = 总量 − 可用」只在这一处知道 —— provider 只需要报它真正查到的两个量
     * （`getMemoryInfo` 给的是 total/avail，`StatFs` 给的是 total/available），
     * 不必自己再算一遍并冒着两处算法漂移的风险。
     */
    private fun usedBytes(explicit: Long?, total: Long?, free: Long?): Long? {
        explicit?.let { return it }
        if (total == null || free == null) return null
        return (total - free).coerceAtLeast(0L)
    }

    private fun renderGigabytesWithUnit(bytes: Long): String = "${renderGigabytes(bytes)} GB"

    /** 一位小数，整数时不带小数点（`renderCompactNumber` 负责去掉 `.0`）。 */
    private fun renderOneDecimal(value: Double): String =
        renderCompactNumber(roundToOneDecimal(value.toFloat()))

    private fun renderOneDecimal(value: Float): String =
        renderCompactNumber(roundToOneDecimal(value))

    private fun roundToOneDecimal(value: Float): Float =
        if (value.isFinite()) (value * 10f).roundToInt() / 10f else value

    /**
     * 经纬度本体保留完整精度（规范示例 51.5074 / -0.1278）。
     *
     * **不能先窄化成 Float**：Float 只有约 7 位有效十进制位，`116.397428` 会变成
     * `116.39743`。经度小数第 6 位大约是 0.1 米量级 —— 表盘把坐标直接印在屏幕上，
     * 我们没有资格替它改数值。整数时仍去掉 `.0`，与 clox 的 `tostring(0)` = `"0"` 对齐。
     */
    private fun renderCoordinate(value: Double): String =
        if (value.isFinite() && value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    /**
     * 位置类字段的取值：缺失或非有限一律当 0。
     *
     * 「缺失即 0」的理由见 {alat} 那一段；顺带把 NaN / Inf 也收进同一个出口 ——
     * [WatchDataSnapshot] 是纯数据类，谁都能塞进一个 NaN，而 `%.1f` 会老实地
     * 渲染出 `NaN`，那比 `--` 更难看懂。
     */
    private fun locationDegreesOrZero(value: Double?): Double =
        value?.takeIf(Double::isFinite) ?: 0.0

    /**
     * 固定一位小数，**位数不会因为值是整数而变少**。
     *
     * 不能复用 [renderOneDecimal]：那条路经 [renderCompactNumber] 把 `.0` 吃掉，
     * `52.0` 渲染成 `52`。上游是 `fmt("%.1f", …)`（`wf_tagdefs.lua:487-489`），位数固定，
     * 而 {alatd} 这一族常被位图字体按固定宽度排版（一个字符一个槽），少一位整块错位。
     *
     * Locale 锁 US：跟随系统会在德语/法语环境下把小数点写成逗号（`52,0`），
     * 而表盘和它的表达式都只认 `.`。
     */
    private fun renderFixedOneDecimal(value: Double): String =
        String.format(Locale.US, "%.1f", locationDegreesOrZero(value))

    /** 占比，一位小数。分母缺失或为 0 时返回 null 而不是 0 —— 那是「不知道」，不是「0%」。 */
    private fun percentOf(part: Long?, total: Long?): String? {
        if (part == null || total == null || total <= 0L) return null
        return renderOneDecimal(part * 100f / total)
    }

    /**
     * 方位角 → 方位名。8 向最多两个字母（N / NE），16 向最多三个（NNE）——
     * 正好对上规范里 {wwdb}「2 letters」与 {wwdbb}「3 letters」的说法。
     */
    private fun compassBearing(degrees: Float, sixteenWay: Boolean): String {
        val names = if (sixteenWay) BEARINGS_16 else BEARINGS_8
        val step = 360f / names.size
        // +0.5 步再取整 = 就近取，且 348.75°..360° 会回绕到 N。
        val normalized = ((degrees % 360f) + 360f) % 360f
        val index = ((normalized + step / 2f) / step).toInt() % names.size
        return names[index]
    }

    private val BEARINGS_8 = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    private val BEARINGS_16 = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /**
     * 秒表读数。
     *
     * 快照里没有秒表字段（编辑器预览、测试的裸快照）时**直接读单例** —— 秒表是
     * 进程级共享的一个，而不是随快照走的采样值；预览里点了开始也该动。
     */
    private fun stopwatchRunning(snapshot: WatchDataSnapshot): Boolean =
        snapshot.stopwatchRunning ?: WatchStopwatch.isRunning

    private fun stopwatchElapsed(snapshot: WatchDataSnapshot): Long =
        snapshot.stopwatchElapsedMs ?: WatchStopwatch.elapsedMs(SystemClock.elapsedRealtime())

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
        // A lone unavailable tag is display text, not arithmetic. Keep its
        // user-facing placeholder while compound expressions can still use
        // the tag's numeric fallback through evaluate().
        tagPattern.matchEntire(raw.trim())?.let { match ->
            val resolution = resolveTag(match.groupValues[1])
            if (resolution is WatchTagResolution.Unavailable) return resolution.renderedValue
        }
        // interpolate 只在「求值失败或结果该让位给模板」这几条分支里才用得上，
        // 而它是一遍正则替换 + 重建字符串。原来无条件先跑一次，等于每个文字
        // 图层每帧白烧一次正则（377 层的表盘每秒上万次），缓存也盖不住它 ——
        // 改成惰性，命中率最高的纯文本分支彻底不跑。
        val value = evaluate(raw, scope, resolveTag)
            ?: evaluateExpressionPrefixTemplate(raw, scope, resolveTag)?.let { return it }
            ?: return interpolate(raw, resolveTag)
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

    /**
     * WatchMaker Studio can concatenate one Lua expression with a plain text
     * template without an explicit `..`, for example `(expr) {sstr}`. Try only
     * top-level whitespace boundaries, longest prefix first, so nested spaces
     * and quoted strings are never treated as split points.
     */
    private fun evaluateExpressionPrefixTemplate(
        raw: String,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): String? {
        val boundaries = ArrayList<Int>()
        var depth = 0
        var quote: Char? = null
        var escaped = false
        raw.forEachIndexed { index, char ->
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                return@forEachIndexed
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                else -> if (depth == 0 && char.isWhitespace() &&
                    (index == 0 || !raw[index - 1].isWhitespace())
                ) {
                    boundaries += index
                }
            }
        }
        for (boundary in boundaries.asReversed()) {
            val prefix = raw.substring(0, boundary).trim()
            if (prefix.isEmpty()) continue
            val prefixValue = evaluate(prefix, scope, resolveTag) ?: continue
            val renderedPrefix = prefixValue.renderText()
            val renderedSuffix = interpolate(raw.substring(boundary), resolveTag)
            return (renderedPrefix + renderedSuffix).take(MAX_RESULT_TEXT_LENGTH)
        }
        return null
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
                                else -> resolution.attributeExpressionValue()
                                    ?.toExpressionLiteral() ?: "nil"
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
