package com.flue.launcher.applist.nativeview

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

internal class NativeHoneycombLayoutManager(
    configProvider: () -> NativeAppListConfig,
) : NativeAppGridLayoutManager(configProvider) {
    private val childVisualScratch = NativeSpatialChildVisual()
    private var geometryLeftInset = Int.MIN_VALUE
    private var geometryRightInset = Int.MIN_VALUE
    private var geometryNarrowColumns = Int.MIN_VALUE
    private var geometryIconSize = Int.MIN_VALUE
    private var geometryCellSize = Float.NaN
    private var geometryEdgeAlignEnabled = false
    private var geometryEdgeAlignPadding = Float.NaN
    private var geometryEdgeVisibility: NativeEdgeVisibilityKey? = null

    override fun hasSameGeometryConfig(
        config: NativeAppListConfig,
    ): Boolean =
        geometryLeftInset == config.leftSafeInsetPx &&
        geometryRightInset == config.rightSafeInsetPx &&
            geometryNarrowColumns == config.honeycombNarrowColumns &&
            geometryIconSize == config.iconSizePx &&
            geometryCellSize == config.honeycombCellSizePx &&
            geometryEdgeAlignEnabled == config.edgeAlignEnabled &&
            geometryEdgeAlignPadding == config.edgeAlignPaddingPx &&
            geometryEdgeVisibility == config.edgeVisibilityKey()

    override fun rememberGeometryConfig(config: NativeAppListConfig) {
        geometryLeftInset = config.leftSafeInsetPx
        geometryRightInset = config.rightSafeInsetPx
        geometryNarrowColumns = config.honeycombNarrowColumns
        geometryIconSize = config.iconSizePx
        geometryCellSize = config.honeycombCellSizePx
        geometryEdgeAlignEnabled = config.edgeAlignEnabled
        geometryEdgeAlignPadding = config.edgeAlignPaddingPx
        geometryEdgeVisibility = config.edgeVisibilityKey()
    }

    override fun createGeometry(
        itemCount: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        config: NativeAppListConfig,
    ): NativeAppGridGeometry {
        val grid = NativeHoneycombGeometry.create(
            itemCount = itemCount,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            leftSafeInsetPx = config.leftSafeInsetPx,
            rightSafeInsetPx = config.rightSafeInsetPx,
            narrowColumns = config.honeycombNarrowColumns,
            iconSizePx = config.iconSizePx.toFloat(),
            cellSizePx = config.honeycombCellSizePx.takeIf { it > 0f }
                ?: config.iconSizePx * 1.02f,
            edgePaddingPx = config.edgeAlignPaddingPx,
            centerAligned = !config.edgeAlignEnabled,
        )
        val settled = config.copy(entryMotionEnabled = false, entryProgress = 1f)
        val bounds = nativeVisibleEdgeBounds(grid, viewportWidthPx.toFloat(), config) { position, y ->
            nativeHoneycombChildVisual(
                grid.centerX(position), y, viewportWidthPx.toFloat(), viewportHeightPx.toFloat(), settled,
            )
        }
        return grid.withScrollBounds(bounds)
    }

    override fun layoutChild(
        child: View,
        geometry: NativeAppGridGeometry,
        position: Int,
        config: NativeAppListConfig,
    ) {
        val honeycomb = geometry as NativeHoneycombGeometry
        val cell = config.honeycombCellSizePx
            .takeIf { it > 0f }
            ?.roundToInt()
            ?: (honeycomb.iconSizePx * 1.02f).roundToInt()
        val layoutParams = child.layoutParams as RecyclerView.LayoutParams
        layoutParams.width = cell
        layoutParams.height = cell
        measureChildWithMargins(child, 0, 0)
        val placement = honeycomb.placements[position]
        val centerY = honeycomb.screenCenterY(position, currentRenderedScrollOffsetPx())
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
            // 不含重排预览：鱼眼缩放与间距压缩都是格心坐标的函数，读了预览位移
            // 就成了帧率级自反馈环（见
            // NativeAppListRowView.positionTranslationYWithoutPreviewOrFlow）。
            val positionTranslationX =
                row?.positionTranslationXWithoutPreviewOrFlow() ?: child.translationX
            val positionTranslationY =
                row?.positionTranslationYWithoutPreviewOrFlow() ?: child.translationY
            val centerX = getDecoratedLeft(child) + getDecoratedMeasuredWidth(child) / 2f +
                positionTranslationX
            val centerY = getDecoratedTop(child) + getDecoratedMeasuredHeight(child) / 2f +
                positionTranslationY
            val visual = nativeHoneycombChildVisual(
                centerX = centerX,
                centerY = centerY,
                viewportWidth = width.toFloat(),
                viewportHeight = height.toFloat(),
                config = config,
                output = childVisualScratch,
            )
            // 圆屏专属几何只保留列表与星环（老师指定），蜂窝在圆屏上与方屏一致。
            // 以前这里按 deviceShape == ROUND 走一遍安全轮廓收边，而那道护栏会把
            // 布局槽位已经出屏的那一两行拽回屏内钉在弧上 —— 顶部/底部图标重叠的
            // 三个成因之一。蜂窝不需要弧线边界，直接恒不收边。
            if (row != null) {
                row.setFlowTranslation(visual.translationX, visual.translationY)
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
