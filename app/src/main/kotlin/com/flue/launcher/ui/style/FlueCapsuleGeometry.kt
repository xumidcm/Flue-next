package com.flue.launcher.ui.style

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 圆屏胶囊几何。三个纯函数，全部可单测（见 `FlueCapsuleGeometryTest`）。
 *
 * ## 为什么要重做
 *
 * 现状是写死的 `if (round) 22.dp else 16.dp`，**与行高无关**。两个后果叠在一起：
 *
 * 1. `22.dp` 在高 DPI 窄屏（约 230dp 宽）上等于屏宽的 9.6%，而 Wear OS 官方圆屏侧边距
 *    指引是**屏宽 5.2%**（Horologist 的 `rememberResponsiveColumnPadding` 就按 5.2% /
 *    10.8% 取值）。白吃掉两侧近 20dp —— 这是文字挤到溢出的一半原因。
 * 2. 另一半是**圆角弧啃进来的深度**没被计入。一行越高，半高圆角的弧就越往内侵，
 *    可 22dp 是常数，于是高行的文字照样贴边。
 *
 * 所以基线换成百分比（跟着屏宽走），弧深按几何单独补（跟着行高走）。两项相加才是内边距。
 */
object FlueCapsuleGeometry {
    /**
     * Wear OS 圆屏侧边距基线，占屏宽的比例。
     *
     * 5.2% 是官方指引值，Horologist 的响应式内边距同源。不要改成「看起来舒服」的数字 ——
     * 这个值的意义是跨机型一致，改成常数就回到了现在这个 bug。
     */
    const val ROUND_BASELINE_FRACTION: Float = 0.052f

    /** 圆屏基线侧边距（像素）。 */
    fun roundBaselineMarginPx(screenWidthPx: Float): Float {
        if (!screenWidthPx.isFinite() || screenWidthPx <= 0f) return 0f
        return screenWidthPx * ROUND_BASELINE_FRACTION
    }

    /**
     * 胶囊圆角半径（像素）。
     *
     * 取三者最小：半高（真胶囊）、风格上限、半宽。
     *
     * - **半高**是胶囊的定义；
     * - **风格上限**让 MIUIX / MD3 能停在自己的平滑圆角档上而不被拉成药丸
     *   （`capsuleMaxRadius = null` 表示不设上限，只有 APPLE_WATCH 是真胶囊）；
     * - **半宽**是护栏：窄而高的块取半高会左右各顶一个大半圆，内容全被啃掉。
     */
    fun capsuleRadiusPx(
        rowHeightPx: Float,
        widthPx: Float,
        maxRadiusPx: Float? = null,
    ): Float {
        if (!rowHeightPx.isFinite() || rowHeightPx <= 0f) return 0f
        var radius = rowHeightPx / 2f
        if (maxRadiusPx != null && maxRadiusPx.isFinite() && maxRadiusPx >= 0f) {
            radius = min(radius, maxRadiusPx)
        }
        if (widthPx.isFinite() && widthPx > 0f) {
            radius = min(radius, widthPx / 2f)
        }
        return radius.coerceAtLeast(0f)
    }

    /**
     * 圆角弧在内容高度处啃进来的深度（像素）：`R - sqrt(R² - (h/2)²)`。
     *
     * **这是「内容与圆角同心」的特例**，只在真胶囊（R = 行高/2）下成立。通用情形走
     * [contentArcInsetPx] —— 高行配小圆角时内容中段根本不进圆角区，用这个式子会算过头
     * （100dp 行配 22dp 圆角会得出满额 22dp，实际是 0）。
     *
     * @param radiusPx 圆角半径。
     * @param contentHeightPx 内容自身高度（不是整行高度）。超过 2R 时按 2R 处理 ——
     *   再高也不可能啃得比半径更深。
     */
    fun capsuleContentInsetPx(radiusPx: Float, contentHeightPx: Float): Float {
        if (!radiusPx.isFinite() || radiusPx <= 0f) return 0f
        if (!contentHeightPx.isFinite() || contentHeightPx <= 0f) return 0f
        val halfContent = min(contentHeightPx / 2f, radiusPx)
        val inner = radiusPx * radiusPx - halfContent * halfContent
        return radiusPx - sqrt(inner.coerceAtLeast(0f))
    }

    /**
     * 通用弧深：行高 [rowHeightPx] 的行、圆角半径 [radiusPx]，纵向居中的
     * [contentHeightPx] 高内容被圆角横向啃进来多少。
     *
     * 这是 [contentArcInsetForBandPx] 的**居中特例**，内部直接转发 —— 设置行的文字块
     * 绝大多数确实居中，留这个入口是为了调用点少写两个参数。
     *
     * 真胶囊（`R = H/2`）下退化成 [capsuleContentInsetPx]。这就是为什么两个函数都留着：
     * 胶囊那条路径的测试已经把边界钉死了。
     */
    fun contentArcInsetPx(
        radiusPx: Float,
        rowHeightPx: Float,
        contentHeightPx: Float,
    ): Float {
        if (!contentHeightPx.isFinite() || contentHeightPx <= 0f) return 0f
        if (!rowHeightPx.isFinite() || rowHeightPx <= 0f) return 0f
        val top = (rowHeightPx - contentHeightPx) / 2f
        return contentArcInsetForBandPx(
            radiusPx = radiusPx,
            rowHeightPx = rowHeightPx,
            bandTopPx = top,
            bandBottomPx = top + contentHeightPx,
        )
    }

    /**
     * 带区弧深：内容占据行内 `[bandTopPx, bandBottomPx]` 这条纵向带时，两侧圆角最深啃进来多少。
     *
     * ## 为什么必须有带区版
     *
     * [contentArcInsetPx] 假设内容**纵向居中**。滑块行不是：标题 + 数值在上部、轨道在下部
     * （轨道高 24dp、底沿距行底 14dp）。按「44dp 内容居中于 100dp 行」去算，轨道会得到
     * 0 弧深 —— 而它真实位置正好压在下圆角的弧上，于是老师看到滑轨两端顶到胶囊边缘。
     *
     * ## 几何
     *
     * 圆角矩形的左边界在高度 `y` 处：
     * - `y ∈ [R, H-R]`（直边段）→ 内缩 **0**；
     * - `y < R` → 上圆角圆心在 `(R, R)`，`dy = R - y`，内缩 `R - sqrt(R² - dy²)`；
     * - `y > H-R` → 下圆角对称。
     *
     * 也就是**离直边段越远内缩越大**，直边段上为零。所以一条带区的最深内缩必然出现在它的
     * 两个端点上：上端点看它比 `R` 高出多少，下端点看它比 `H-R` 低出多少，取两者较大者
     * （内边距要同时容下上下两个弧）。
     */
    fun contentArcInsetForBandPx(
        radiusPx: Float,
        rowHeightPx: Float,
        bandTopPx: Float,
        bandBottomPx: Float,
    ): Float {
        if (!radiusPx.isFinite() || radiusPx <= 0f) return 0f
        if (!rowHeightPx.isFinite() || rowHeightPx <= 0f) return 0f
        if (!bandTopPx.isFinite() || !bandBottomPx.isFinite()) return 0f
        val top = min(bandTopPx, bandBottomPx).coerceIn(0f, rowHeightPx)
        val bottom = max(bandTopPx, bandBottomPx).coerceIn(0f, rowHeightPx)
        val topArcDepth = if (top < radiusPx) arcDepthAtOffset(radiusPx, radiusPx - top) else 0f
        val straightBottom = rowHeightPx - radiusPx
        val bottomArcDepth = if (bottom > straightBottom) {
            arcDepthAtOffset(radiusPx, bottom - straightBottom)
        } else {
            0f
        }
        return max(topArcDepth, bottomArcDepth)
    }

    /** 距圆心 [offsetPx] 处，半径 [radiusPx] 的弧比外接矩形内缩多少。 */
    private fun arcDepthAtOffset(radiusPx: Float, offsetPx: Float): Float {
        val dy = offsetPx.coerceIn(0f, radiusPx)
        val inner = radiusPx * radiusPx - dy * dy
        return radiusPx - sqrt(inner.coerceAtLeast(0f))
    }

    /**
     * 最终水平内边距（像素）：圆屏 = 基线 + 弧深；方屏 = 方屏基线（保持现有 16dp 行为）。
     *
     * @param round 是否圆屏。
     * @param screenWidthPx 屏宽，用于算 5.2% 基线。
     * @param radiusPx 该行实际的圆角半径（见 [capsuleRadiusPx]）。
     * @param rowHeightPx 整行高度。弧深要靠它才能判断内容有没有伸进圆角区。
     * @param contentHeightPx 内容自然高度。与 [contentBandPx] 二选一，后者优先。
     * @param contentBandPx 内容在行内占据的纵向带 `top to bottom`。滑块行的轨道必须用这个 ——
     *   它不居中，居中假设会算出 0 弧深。
     * @param squareMarginPx 方屏基线（现有的 16dp 换算值）。
     * @param minimumPx 下限，避免极窄屏算出贴边的内边距。
     */
    fun horizontalPaddingPx(
        round: Boolean,
        screenWidthPx: Float,
        radiusPx: Float,
        rowHeightPx: Float,
        contentHeightPx: Float,
        squareMarginPx: Float,
        minimumPx: Float = 0f,
        contentBandPx: Pair<Float, Float>? = null,
    ): Float {
        if (!round) return squareMarginPx.coerceAtLeast(minimumPx)
        val baseline = roundBaselineMarginPx(screenWidthPx)
        val arc = if (contentBandPx != null) {
            contentArcInsetForBandPx(
                radiusPx = radiusPx,
                rowHeightPx = rowHeightPx,
                bandTopPx = contentBandPx.first,
                bandBottomPx = contentBandPx.second,
            )
        } else {
            contentArcInsetPx(radiusPx, rowHeightPx, contentHeightPx)
        }
        return (baseline + arc).coerceAtLeast(minimumPx)
    }
}

/**
 * 行在「一组」里的位置。用来按位取形：首行只圆上两角、末行只圆下两角、中间四角全直。
 *
 * 这是 A2b 的地基 —— 老师要的 HyperOS 观感是同一分类的行合并成一张大圆角卡加细分隔线，
 * 而现在每行各是一张独立卡（`MiuixSettingsCard` 本身是正确的成组容器，但四个行容器
 * 各自只包一行）。
 *
 * APPLE 与 MD3 档一律按 [Single] 处理：它们本来就是独立卡，行为不变。
 */
enum class FlueGroupPosition {
    Single,
    First,
    Middle,
    Last,
    ;

    val roundsTop: Boolean get() = this == Single || this == First
    val roundsBottom: Boolean get() = this == Single || this == Last

    /** 该行下面要不要画分隔线：组内非末行才要。 */
    val needsDivider: Boolean get() = this == First || this == Middle
}

/**
 * 按索引算出组内位置。
 *
 * 单行 → [FlueGroupPosition.Single]；两行 → First/Last；n 行 → First + Middle×(n-2) + Last。
 * 越界索引按 Single 兜（宁可少合并，也不要画出朝向错误的圆角）。
 */
fun flueGroupPositionAt(index: Int, count: Int): FlueGroupPosition = when {
    count <= 1 || index < 0 || index >= count -> FlueGroupPosition.Single
    index == 0 -> FlueGroupPosition.First
    index == count - 1 -> FlueGroupPosition.Last
    else -> FlueGroupPosition.Middle
}
