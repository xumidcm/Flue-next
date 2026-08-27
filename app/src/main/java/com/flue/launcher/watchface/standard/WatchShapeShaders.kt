package com.flue.launcher.watchface.standard

import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * WatchMaker `shader` 通道（`shape` / `rounded` 图层的 11 种着色器）。
 *
 * ## 为什么用 Skia 原生 Shader 而不是抄 wftools 的后处理
 *
 * wftools 在 Compose 里是先把图层画进一张离屏位图、再用 ColorMatrix / BlendMode 做
 * 后处理（`WmShaderPostProcess.kt`）—— 因为它的预览是 Compose Canvas，拿不到 Paint 的
 * shader 槽。Flue 的渲染器是 `android.graphics.Canvas`，可以**直接把渐变挂在 Paint 上**：
 * 少一张离屏位图、少一次 saveLayer，在 466×466 的手表上这是能省下整整一帧的差别。
 *
 * 两种做法的视觉结果对齐方式：
 * - 真渐变类（GradientLinear / GradientRadial / Rainbow）→ Skia 的 Linear/Radial/Sweep
 *   Gradient，本来就是同一套数学。
 * - 透明度调制类（Segment / SegmentBetween / Radar / Progress / ProgressBetween）→
 *   用**同一个填充色、只改 alpha** 的 Sweep/Linear 渐变加硬 stop 实现。原始 `.fsh`
 *   就是按角度/位置算一个 alpha 再乘上底色，所以等价。
 * - 颜色矩阵类（RGB / HSV）→ [ColorMatrixColorFilter]，与 wftools 同一条路。
 *
 * ## 角度基准
 *
 * WatchMaker 的角度一律从 **12 点方向**顺时针算；Skia 的 [SweepGradient] 从 **3 点方向**
 * 开始。所以每个 sweep 都要额外转 −90°，这一步收在 [rotatedLocalMatrix] 里，
 * 各分支不要自己写。
 *
 * ## `u_*` 的语义与缺省值
 *
 * 来自 wftools 的 `wmShaderDefaults` 与它各分支的滑杆标签（`WmShapeParams.kt`），
 * 那是目前唯一把 11 种着色器的 uniform 逐个标注出来的实现。`progress` 图层按规范
 * **不支持** shader，所以调用方只在 `shape` / `rounded` 上问。
 */
enum class WatchShapeShaderKind {
    NONE,
    GRADIENT_LINEAR,
    GRADIENT_RADIAL,
    RAINBOW,
    RGB,
    HSV,
    SEGMENT,
    SEGMENT_BETWEEN,
    RADAR,
    PROGRESS,
    PROGRESS_BETWEEN,
}

/** 写进 watch.xml 的字面值 —— 大小写照抄规范，别的渲染器按原值查表。 */
fun watchShapeShaderAttributeValue(kind: WatchShapeShaderKind): String = when (kind) {
    WatchShapeShaderKind.NONE -> ""
    WatchShapeShaderKind.GRADIENT_LINEAR -> "GradientLinear"
    WatchShapeShaderKind.GRADIENT_RADIAL -> "GradientRadial"
    WatchShapeShaderKind.RAINBOW -> "Rainbow"
    WatchShapeShaderKind.RGB -> "RGB"
    WatchShapeShaderKind.HSV -> "HSV"
    WatchShapeShaderKind.SEGMENT -> "Segment"
    WatchShapeShaderKind.SEGMENT_BETWEEN -> "SegmentBetween"
    WatchShapeShaderKind.RADAR -> "Radar"
    WatchShapeShaderKind.PROGRESS -> "Progress"
    WatchShapeShaderKind.PROGRESS_BETWEEN -> "ProgressBetween"
}

fun watchShapeShaderKindOf(raw: String?): WatchShapeShaderKind =
    when (raw.orEmpty().trim().lowercase(Locale.ROOT)) {
        "gradientlinear" -> WatchShapeShaderKind.GRADIENT_LINEAR
        "gradientradial" -> WatchShapeShaderKind.GRADIENT_RADIAL
        "rainbow" -> WatchShapeShaderKind.RAINBOW
        "rgb" -> WatchShapeShaderKind.RGB
        "hsv" -> WatchShapeShaderKind.HSV
        "segment" -> WatchShapeShaderKind.SEGMENT
        "segmentbetween" -> WatchShapeShaderKind.SEGMENT_BETWEEN
        "radar" -> WatchShapeShaderKind.RADAR
        "progress" -> WatchShapeShaderKind.PROGRESS
        "progressbetween" -> WatchShapeShaderKind.PROGRESS_BETWEEN
        else -> WatchShapeShaderKind.NONE
    }

/**
 * 选中某个着色器时要一并写下的 `u_*` 缺省值。
 *
 * 与 wftools 的 `wmShaderDefaults` 逐位一致：不预置的话面板上全是「未设置」，
 * 而渲染器把空值当 0，用户选完 shader 会看到一块纯黑或全透明，以为坏了。
 */
fun watchShapeShaderDefaults(kind: WatchShapeShaderKind): List<Pair<String, String>> = when (kind) {
    WatchShapeShaderKind.GRADIENT_LINEAR ->
        listOf("u_1" to "ffffff", "u_2" to "000000", "u_3" to "0", "u_4" to "100")
    WatchShapeShaderKind.GRADIENT_RADIAL ->
        listOf("u_1" to "ffffff", "u_2" to "000000", "u_3" to "100")
    WatchShapeShaderKind.RAINBOW -> listOf("u_1" to "0")
    WatchShapeShaderKind.RGB -> listOf("u_1" to "0", "u_2" to "0", "u_3" to "0")
    WatchShapeShaderKind.HSV -> listOf("u_1" to "0", "u_2" to "0", "u_3" to "0")
    WatchShapeShaderKind.SEGMENT -> listOf("u_1" to "90", "u_2" to "100", "u_3" to "20")
    WatchShapeShaderKind.SEGMENT_BETWEEN ->
        listOf("u_1" to "0", "u_2" to "180", "u_3" to "100", "u_4" to "10")
    WatchShapeShaderKind.RADAR -> listOf("u_1" to "0", "u_2" to "100", "u_3" to "15")
    WatchShapeShaderKind.PROGRESS ->
        listOf("u_1" to "50", "u_2" to "0", "u_3" to "100", "u_4" to "20")
    WatchShapeShaderKind.PROGRESS_BETWEEN ->
        listOf("u_1" to "0", "u_2" to "50", "u_3" to "0", "u_4" to "100", "u_5" to "15")
    WatchShapeShaderKind.NONE -> emptyList()
}

/**
 * 挂到 Paint 上的两个槽位。两者可以同时为空（该着色器这一帧不改变任何东西）。
 *
 * 调用方用完**必须**把 Paint 的 `shader` / `colorFilter` 摘回 null —— 渲染器里的
 * Paint 是复用实例，忘了摘会让下一个图层跟着套同一个渐变。
 */
class WatchShapePaintEffect(
    val shader: Shader?,
    val colorFilter: ColorFilter?,
)

/**
 * 按 [kind] 造出 Paint 效果。
 *
 * @param baseColor 图层的 `color`（含 alpha）。透明度调制类着色器拿它当底色。
 * @param number 求值器：把 `u_1`..`u_5` 解析成数字（缺省值由调用方给）。
 *   之所以传 lambda 而不是传 5 个 Float —— `u_*` 可以挂表达式（`{shr}` 之类），
 *   求值必须走渲染器的 `dynamicNumber`，那是私有的。
 * @param color 求值器：把 `u_*` 解析成颜色。
 */
fun buildWatchShapeShaderEffect(
    kind: WatchShapeShaderKind,
    width: Float,
    height: Float,
    baseColor: Int,
    number: (String, Float) -> Float,
    color: (String, Int) -> Int,
): WatchShapePaintEffect? {
    if (kind == WatchShapeShaderKind.NONE) return null
    if (width <= 0f || height <= 0f) return null
    return when (kind) {
        WatchShapeShaderKind.NONE -> null

        WatchShapeShaderKind.GRADIENT_LINEAR -> {
            val start = color("u_1", Color.WHITE)
            val end = color("u_2", Color.BLACK)
            val angle = number("u_3", 0f)
            val scale = (number("u_4", 100f) / 100f).coerceIn(0.01f, 4f)
            val radians = Math.toRadians((angle - 90f).toDouble())
            val dirX = cos(radians).toFloat()
            val dirY = sin(radians).toFloat()
            // 沿该方向的盒子跨度：轴对齐时退化成 w 或 h，斜向时是投影长度。
            val span = (abs(dirX) * width + abs(dirY) * height) * scale
            WatchShapePaintEffect(
                shader = LinearGradient(
                    -dirX * span / 2f,
                    -dirY * span / 2f,
                    dirX * span / 2f,
                    dirY * span / 2f,
                    start,
                    end,
                    Shader.TileMode.CLAMP,
                ),
                colorFilter = null,
            )
        }

        WatchShapeShaderKind.GRADIENT_RADIAL -> {
            val center = color("u_1", Color.WHITE)
            val edge = color("u_2", Color.BLACK)
            val scale = (number("u_3", 100f) / 100f).coerceIn(0.01f, 4f)
            val radius = (max(width, height) / 2f * scale).coerceAtLeast(0.01f)
            WatchShapePaintEffect(
                shader = RadialGradient(0f, 0f, radius, center, edge, Shader.TileMode.CLAMP),
                colorFilter = null,
            )
        }

        WatchShapeShaderKind.RAINBOW -> {
            val rotation = number("u_1", 0f)
            // 7 段够平滑，段数再多在手表尺寸上看不出差别，但每段都是一次插值成本。
            val hues = IntArray(RAINBOW_STOPS + 1) { index ->
                val hue = 360f * index / RAINBOW_STOPS
                Color.HSVToColor(Color.alpha(baseColor), floatArrayOf(hue % 360f, 1f, 1f))
            }
            WatchShapePaintEffect(
                shader = SweepGradient(0f, 0f, hues, null).apply {
                    setLocalMatrix(rotatedLocalMatrix(rotation))
                },
                colorFilter = null,
            )
        }

        WatchShapeShaderKind.RGB -> {
            // -100..100 → -255..255 的加法偏移，与 wftools 的滑杆范围一致。
            val red = number("u_1", 0f).coerceIn(-100f, 100f) * 2.55f
            val green = number("u_2", 0f).coerceIn(-100f, 100f) * 2.55f
            val blue = number("u_3", 0f).coerceIn(-100f, 100f) * 2.55f
            WatchShapePaintEffect(
                shader = null,
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            1f, 0f, 0f, 0f, red,
                            0f, 1f, 0f, 0f, green,
                            0f, 0f, 1f, 0f, blue,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                ),
            )
        }

        WatchShapeShaderKind.HSV -> {
            val hueShift = number("u_1", 0f).coerceIn(-100f, 100f) / 100f * 180f
            val saturation = 1f + number("u_2", 0f).coerceIn(-100f, 100f) / 100f
            val value = 1f + number("u_3", 0f).coerceIn(-100f, 100f) / 100f
            val matrix = ColorMatrix().apply {
                // 顺序有讲究：先转色相、再改饱和、最后调明度。反过来做，饱和度会把
                // 已经转过的色相再拉回去（ColorMatrix 是左乘，postConcat 是后作用）。
                postConcat(hueRotationMatrix(hueShift))
                postConcat(ColorMatrix().apply { setSaturation(saturation.coerceAtLeast(0f)) })
                postConcat(ColorMatrix().apply { setScale(value, value, value, 1f) })
            }
            WatchShapePaintEffect(shader = null, colorFilter = ColorMatrixColorFilter(matrix))
        }

        WatchShapeShaderKind.SEGMENT -> {
            val span = number("u_1", 90f).coerceIn(0f, 360f)
            val inside = alphaOf(baseColor, number("u_2", 100f))
            val outside = alphaOf(baseColor, number("u_3", 20f))
            WatchShapePaintEffect(
                shader = hardStopSweep(
                    listOf(0f to inside, span / 360f to inside, span / 360f to outside, 1f to outside),
                    rotation = 0f,
                ),
                colorFilter = null,
            )
        }

        WatchShapeShaderKind.SEGMENT_BETWEEN -> {
            val rawStart = number("u_1", 0f).coerceIn(0f, 360f)
            val rawEnd = number("u_2", 180f).coerceIn(0f, 360f)
            val start = minOf(rawStart, rawEnd) / 360f
            val end = maxOf(rawStart, rawEnd) / 360f
            val inside = alphaOf(baseColor, number("u_3", 100f))
            val outside = alphaOf(baseColor, number("u_4", 10f))
            WatchShapePaintEffect(
                shader = hardStopSweep(
                    listOf(
                        0f to outside,
                        start to outside,
                        start to inside,
                        end to inside,
                        end to outside,
                        1f to outside,
                    ),
                    rotation = 0f,
                ),
                colorFilter = null,
            )
        }

        WatchShapeShaderKind.RADAR -> {
            val leading = number("u_1", 0f)
            val peak = alphaOf(baseColor, number("u_2", 100f))
            val tail = alphaOf(baseColor, number("u_3", 15f))
            // 尾巴在后、亮边在前：整圈从 tail 线性升到 peak，再把 0 点转到 u_1。
            WatchShapePaintEffect(
                shader = SweepGradient(0f, 0f, intArrayOf(tail, peak), floatArrayOf(0f, 1f)).apply {
                    setLocalMatrix(rotatedLocalMatrix(leading))
                },
                colorFilter = null,
            )
        }

        WatchShapeShaderKind.PROGRESS -> {
            val pct = (number("u_1", 50f).coerceIn(0f, 100f)) / 100f
            val direction = number("u_2", 0f)
            val filled = alphaOf(baseColor, number("u_3", 100f))
            val unfilled = alphaOf(baseColor, number("u_4", 20f))
            WatchShapePaintEffect(
                shader = hardStopLinear(
                    width,
                    height,
                    direction,
                    listOf(0f to filled, pct to filled, pct to unfilled, 1f to unfilled),
                ),
                colorFilter = null,
            )
        }

        WatchShapeShaderKind.PROGRESS_BETWEEN -> {
            val rawStart = number("u_1", 0f).coerceIn(0f, 100f) / 100f
            val rawEnd = number("u_2", 50f).coerceIn(0f, 100f) / 100f
            val start = minOf(rawStart, rawEnd)
            val end = maxOf(rawStart, rawEnd)
            val direction = number("u_3", 0f)
            val filled = alphaOf(baseColor, number("u_4", 100f))
            val unfilled = alphaOf(baseColor, number("u_5", 15f))
            WatchShapePaintEffect(
                shader = hardStopLinear(
                    width,
                    height,
                    direction,
                    listOf(
                        0f to unfilled,
                        start to unfilled,
                        start to filled,
                        end to filled,
                        end to unfilled,
                        1f to unfilled,
                    ),
                ),
                colorFilter = null,
            )
        }
    }
}

private const val RAINBOW_STOPS = 7

/** 把 0-100 的不透明度乘到底色的 alpha 上。 */
private fun alphaOf(baseColor: Int, opacityPercent: Float): Int {
    val factor = opacityPercent.coerceIn(0f, 100f) / 100f
    val alpha = (Color.alpha(baseColor) * factor).toInt().coerceIn(0, 255)
    return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
}

/**
 * Sweep 的本地矩阵：把 0 点从 Skia 的 3 点方向转到 WatchMaker 的 12 点方向，
 * 再叠加调用方要的 [rotationDegrees]。
 */
private fun rotatedLocalMatrix(rotationDegrees: Float): Matrix =
    Matrix().apply { setRotate(rotationDegrees - 90f) }

/**
 * 带硬 stop 的 sweep 渐变。
 *
 * Skia 允许两个相邻 stop 落在同一个 position 上，从而产生硬边 —— 这正是
 * Segment / SegmentBetween 的「一段实、一段淡」需要的。position 必须单调不减，
 * 所以这里统一夹到 0..1 并做一次单调修正（作者写反的表盘不能让 Skia 抛异常）。
 */
private fun hardStopSweep(stops: List<Pair<Float, Int>>, rotation: Float): Shader {
    val (positions, colors) = monotonicStops(stops)
    return SweepGradient(0f, 0f, colors, positions).apply {
        setLocalMatrix(rotatedLocalMatrix(rotation))
    }
}

/** 带硬 stop 的线性渐变，方向由 [directionDegrees] 给（0 = 从 12 点向下）。 */
private fun hardStopLinear(
    width: Float,
    height: Float,
    directionDegrees: Float,
    stops: List<Pair<Float, Int>>,
): Shader {
    val radians = Math.toRadians((directionDegrees - 90f).toDouble())
    val dirX = cos(radians).toFloat()
    val dirY = sin(radians).toFloat()
    val span = (abs(dirX) * width + abs(dirY) * height).coerceAtLeast(0.01f)
    val (positions, colors) = monotonicStops(stops)
    return LinearGradient(
        -dirX * span / 2f,
        -dirY * span / 2f,
        dirX * span / 2f,
        dirY * span / 2f,
        colors,
        positions,
        Shader.TileMode.CLAMP,
    )
}

/** 夹到 0..1 并保证单调不减；Skia 对乱序 position 会直接抛。 */
private fun monotonicStops(stops: List<Pair<Float, Int>>): Pair<FloatArray, IntArray> {
    val positions = FloatArray(stops.size)
    val colors = IntArray(stops.size)
    var previous = 0f
    stops.forEachIndexed { index, (position, color) ->
        val clamped = position.coerceIn(0f, 1f).coerceAtLeast(previous)
        positions[index] = clamped
        colors[index] = color
        previous = clamped
    }
    return positions to colors
}

/**
 * 色相旋转矩阵（度）。
 *
 * [ColorMatrix] 没有现成的 hue rotation，标准做法是绕 RGB 立方体的灰度轴转 ——
 * 系数就是 luminance 权重 (0.213, 0.715, 0.072) 那一组，与 Android 平台
 * `ColorMatrix.setSaturation` 用的是同一套亮度基准，两者叠加不会偏色。
 */
private fun hueRotationMatrix(degrees: Float): ColorMatrix {
    val radians = Math.toRadians(degrees.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    val lumR = 0.213f
    val lumG = 0.715f
    val lumB = 0.072f
    return ColorMatrix(
        floatArrayOf(
            lumR + cosine * (1 - lumR) - sine * lumR,
            lumG - cosine * lumG - sine * lumG,
            lumB - cosine * lumB + sine * (1 - lumB),
            0f, 0f,
            lumR - cosine * lumR + sine * 0.143f,
            lumG + cosine * (1 - lumG) + sine * 0.140f,
            lumB - cosine * lumB - sine * 0.283f,
            0f, 0f,
            lumR - cosine * lumR - sine * (1 - lumR),
            lumG - cosine * lumG + sine * lumG,
            lumB + cosine * (1 - lumB) + sine * lumB,
            0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}
