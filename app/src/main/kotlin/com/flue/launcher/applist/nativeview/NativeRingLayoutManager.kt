package com.flue.launcher.applist.nativeview

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * 星环的 RecyclerView 布局管理器。
 *
 * 与 [NativeDenseGridLayoutManager] 是平级兄弟，共用 [NativeAppGridLayoutManager] 那套
 * 生命周期 / 回收 / 滚动度量。差别只在几何换成 [NativeRingGeometry]（铺满可用宽度的
 * 正方格）。
 *
 * 圆屏不再有任何专属路径（2026-08 老师要求删掉径向收拢与安全轮廓，星环两种屏形
 * 一律按方屏平铺渲染）：子项视觉就是 [nativeRingChildVisual] —— 按行数触发的上下
 * 边缘鱼眼 + 入场动效 + 边缘模糊，鱼眼关掉时全部图标同一尺寸。
 */
internal class NativeRingLayoutManager(
    configProvider: () -> NativeAppListConfig,
) : NativeAppGridLayoutManager(configProvider) {
    private val childVisualScratch = NativeSpatialChildVisual()
    private var geometryLeftInset = Int.MIN_VALUE
    private var geometryRightInset = Int.MIN_VALUE
    private var geometryColumns = Int.MIN_VALUE
    private var geometryCellSize = Float.NaN

    override fun hasSameGeometryConfig(
        config: NativeAppListConfig,
    ): Boolean =
        geometryLeftInset == config.leftSafeInsetPx &&
        geometryRightInset == config.rightSafeInsetPx &&
            geometryColumns == config.honeycombNarrowColumns &&
            geometryCellSize == config.ringCellSizePx

    override fun rememberGeometryConfig(config: NativeAppListConfig) {
        geometryLeftInset = config.leftSafeInsetPx
        geometryRightInset = config.rightSafeInsetPx
        geometryColumns = config.honeycombNarrowColumns
        geometryCellSize = config.ringCellSizePx
    }

    override fun createGeometry(
        itemCount: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        config: NativeAppListConfig,
    ): NativeAppGridGeometry = NativeRingGeometry.create(
        itemCount = itemCount,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        leftSafeInsetPx = config.leftSafeInsetPx,
        rightSafeInsetPx = config.rightSafeInsetPx,
        columns = config.honeycombNarrowColumns,
        cellSizePx = config.ringCellSizePx.takeIf { it.isFinite() && it > 0f }
            ?: nativeRingCellSizePx(
                viewportWidthPx = viewportWidthPx,
                leftSafeInsetPx = config.leftSafeInsetPx,
                rightSafeInsetPx = config.rightSafeInsetPx,
                columns = config.honeycombNarrowColumns,
            ),
    )

    override fun layoutChild(
        child: View,
        geometry: NativeAppGridGeometry,
        position: Int,
        config: NativeAppListConfig,
    ) {
        val ring = geometry as NativeRingGeometry
        val cell = ring.cellSizePx.roundToInt().coerceAtLeast(1)
        val layoutParams = child.layoutParams as RecyclerView.LayoutParams
        layoutParams.width = cell
        layoutParams.height = cell
        measureChildWithMargins(child, 0, 0)
        val placement = ring.placements[position]
        val centerY = ring.screenCenterY(position, currentRenderedScrollOffsetPx())
        val left = (placement.centerX - getDecoratedMeasuredWidth(child) / 2f).roundToInt()
        val top = (centerY - getDecoratedMeasuredHeight(child) / 2f).roundToInt()
        layoutDecoratedWithMargins(
            child,
            left,
            top,
            left + getDecoratedMeasuredWidth(child),
            top + getDecoratedMeasuredHeight(child),
        )
    }

    override fun applyChildVisuals(
        geometry: NativeAppGridGeometry,
        config: NativeAppListConfig,
    ) {
        for (index in 0 until childCount) {
            val child = requireNotNull(getChildAt(index))
            val row = child as? NativeAppListRowView
            val positionTranslationX =
                row?.positionTranslationXWithoutPreviewOrFlow() ?: child.translationX
            val positionTranslationY =
                row?.positionTranslationYWithoutPreviewOrFlow() ?: child.translationY
            // 只读布局值（decorated 边界 + 拖拽 / 下拉位移），绝不读被 scale/translation 改过
            // 之后的边界 —— 那是帧率级自反馈环，ChordScale.kt 铁律一。
            //
            // **重排预览位移同样算「被自己改过的值」**：鱼眼是格心坐标的函数，一旦把预览
            // 位移喂回来，「预览 → 鱼眼 → 命中坐标 → 新预览」每帧走一遍。
            // 铁律一在这里扩写为「也不能读预览位移」。
            val centerX = getDecoratedLeft(child) + getDecoratedMeasuredWidth(child) / 2f +
                positionTranslationX
            val centerY = getDecoratedTop(child) + getDecoratedMeasuredHeight(child) / 2f +
                positionTranslationY
            val visual = nativeRingChildVisual(
                centerX = centerX,
                centerY = centerY,
                viewportWidth = width.toFloat(),
                viewportHeight = height.toFloat(),
                config = config,
                output = childVisualScratch,
            )
            if (row != null) {
                row.setFlowTranslation(visual.translationX, visual.translationY)
                // 等比缩放（铁律三）：setVisualScale 同时写 scaleX/scaleY。
                row.setVisualScale(visual.scale)
                row.setIconBlurRadiusPx(visual.blurRadiusPx, config.effectiveBlurTier())
                row.setIconSaturation(visual.saturation)
            } else {
                child.translationX = visual.translationX
                child.translationY = visual.translationY
                child.scaleX = visual.scale
                child.scaleY = visual.scale
            }
            child.alpha = visual.alpha
        }
    }
}
