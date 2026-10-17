package com.flue.launcher.util

import androidx.compose.ui.geometry.Offset

/**
 * 月牙朝向。弧线往哪一侧鼓出。
 *
 * [signum] 直接进偏移公式，所以「朝左」就是负 x，不需要调用方再判一次方向。
 */
enum class FlowCrescentDirection(val signum: Float) {
    Left(-1f),
    Right(1f),
}

data class FlowShapeCurve(
    val x1: Float = 1f / 3f,
    val y1: Float = 0f,
    val x2: Float = 2f / 3f,
    val y2: Float = 1f / 3f,
) {
    fun sanitized(): FlowShapeCurve = copy(
        x1 = x1.coerceIn(0f, 1f),
        y1 = y1.coerceIn(0f, 1f),
        x2 = x2.coerceIn(0f, 1f),
        y2 = y2.coerceIn(0f, 1f),
    )

    companion object {
        val Crescent = FlowShapeCurve()
    }
}

fun flowShapeCurveProgress(progress: Float, curve: FlowShapeCurve): Float {
    val target = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: return 0f
    if (target <= 0f) return 0f
    if (target >= 1f) return 1f
    val safe = curve.sanitized()
    if (safe == FlowShapeCurve.Crescent) return target * target
    fun cubic(t: Float, p1: Float, p2: Float): Float {
        val oneMinus = 1f - t
        return 3f * oneMinus * oneMinus * t * p1 + 3f * oneMinus * t * t * p2 + t * t * t
    }
    var low = 0f
    var high = 1f
    repeat(12) {
        val middle = (low + high) / 2f
        if (cubic(middle, safe.x1, safe.x2) < target) low = middle else high = middle
    }
    return cubic((low + high) / 2f, safe.y1, safe.y2).coerceIn(0f, 1f)
}

/**
 * 「心流」布局的规则网格。
 *
 * 与 [generateHoneycombRows] 是平级兄弟，区别只有一条：**每行等列数**，不做宽窄
 * 交替。月牙那条弧**刻意不进这里** —— 它是绘制期按视口中心算的偏移（见
 * [flowCrescentOffsetPx]），进布局就会变成「偏移 → 重测量 → 位置变 → 算出不同
 * 偏移」的自反馈抽搐，`ui/common/ChordScale.kt` 开头已经为这件事记过一次翻车。
 *
 * 末行不满时按**整行宽度**左对齐（与蜂窝同一处理），而不是按实际项数再居中一次：
 * 网格的竖向列对齐是观感的一半，末行单独居中会让最后几个图标错开半格。
 *
 * @param count 图标总数。
 * @param cols 每行列数。小于 1 按 1 处理。
 * @param cellSize 格心间距（像素），横纵同值 —— 心流是正方格，不像蜂窝那样行高要乘
 *   `sqrt(3)/2`（那是六边形交错才需要的压缩）。
 * @return 每个图标相对**网格中心**的像素偏移，y 已把整张网格居中到 0。
 */
fun generateFlowRows(count: Int, cols: Int, cellSize: Float): List<Offset> {
    if (count <= 0 || !cellSize.isFinite() || cellSize <= 0f) return emptyList()
    val columns = cols.coerceAtLeast(1)
    val fullRowWidth = (columns - 1) * cellSize
    val rowCount = (count + columns - 1) / columns
    val yCenterOffset = (rowCount - 1) * cellSize / 2f
    return List(count) { index ->
        val row = index / columns
        val column = index % columns
        Offset(
            x = column * cellSize - fullRowWidth / 2f,
            y = row * cellSize - yCenterOffset,
        )
    }
}

/**
 * 月牙偏移：整行一起往一侧平移多少像素。
 *
 * 形状是抛物线 `bulge * (1 - t²)`，`t` 是该行离**视口中心**的归一化距离。于是屏幕
 * 正中那行鼓出最多、上下边缘那两行归零，连成一道月牙；屏外继续延伸，
 * 保证图标尚未完全移出视口时仍沿曲线移动。
 *
 * **锚在视口中心而不是内容中心**是关键：锚内容的话弧会跟着内容一起滚，滚两屏之后
 * 弧就散了；锚视口则弧永远贴着屏幕中线，行从弧上滑过。这也正是它该并进鱼眼设置的
 * 原因 —— 与鱼眼共用同一个 `screenCenterY` 基准。
 *
 * 只读位置、只出一个数，调用方把它加到 `translationX`（原生侧是 `View.translationX`，
 * Compose 侧是 `graphicsLayer`），绝不碰布局属性。
 *
 * @param rowCenterY 该行在**屏幕坐标**里的中心 y（已含滚动偏移）。
 * @param viewportCenterY 视口中心 y。
 * @param viewportHeight 视口高度。非正时返回 0（还没测量完，别乱推）。
 * @param bulgePx 峰值鼓出量（像素）。可正可负，负值等于反向。
 * @param direction 弧朝哪一侧。
 */
fun flowCrescentOffsetPx(
    rowCenterY: Float,
    viewportCenterY: Float,
    viewportHeight: Float,
    bulgePx: Float,
    direction: FlowCrescentDirection,
    curve: FlowShapeCurve = FlowShapeCurve.Crescent,
): Float {
    if (
        !rowCenterY.isFinite() || !viewportCenterY.isFinite() ||
        !viewportHeight.isFinite() || viewportHeight <= 0f ||
        !bulgePx.isFinite() || bulgePx == 0f
    ) {
        return 0f
    }
    val distance = kotlin.math.abs(
        (rowCenterY - viewportCenterY) / (viewportHeight / 2f),
    )
    // A row is still partly visible after its centre crosses the viewport edge.
    // Continue the same curve there instead of freezing X and exiting vertically.
    // Bound extrapolation only well outside the visible icon footprint.
    val boundedDistance = distance.coerceAtMost(2f)
    val progress = if (curve.sanitized() == FlowShapeCurve.Crescent) {
        boundedDistance * boundedDistance
    } else if (boundedDistance <= 1f) {
        flowShapeCurveProgress(boundedDistance, curve)
    } else {
        val edgeSlope = (1f - flowShapeCurveProgress(0.99f, curve)) / 0.01f
        1f + (boundedDistance - 1f) * edgeSlope
    }
    return direction.signum * bulgePx * (1f - progress)
}
