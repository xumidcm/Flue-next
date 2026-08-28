package com.flue.launcher.applist.nativeview

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

internal class NativeDenseGridLayoutManager(
    configProvider: () -> NativeAppListConfig,
) : NativeAppGridLayoutManager(configProvider) {
    private val childVisualScratch = NativeSpatialChildVisual()
    private var geometryLeftInset = Int.MIN_VALUE
    private var geometryColumns = Int.MIN_VALUE
    private var geometryHorizontalPadding = Float.NaN
    private var geometrySpacing = Float.NaN
    private var geometryAspectRatio = Float.NaN
    private var geometryMinimumItemWidth = Float.NaN
    private var geometryMinimumItemHeight = Float.NaN
    private var geometryMinimumVerticalPadding = Float.NaN

    override fun extraLayoutSpacePx(
        geometry: NativeAppGridGeometry,
        config: NativeAppListConfig,
    ): Float = geometry.itemHeightPx / 2f

    override fun hasSameGeometryConfig(
        config: NativeAppListConfig,
    ): Boolean =
        geometryLeftInset == config.leftSafeInsetPx &&
            geometryColumns == config.denseGridColumns &&
            geometryHorizontalPadding == config.denseGridHorizontalPaddingPx &&
            geometrySpacing == config.denseGridItemSpacingPx &&
            geometryAspectRatio == config.denseGridItemAspectRatio &&
            geometryMinimumItemWidth == config.denseGridMinimumItemWidthPx &&
            geometryMinimumItemHeight == config.denseGridMinimumItemHeightPx &&
            geometryMinimumVerticalPadding == config.denseGridMinimumVerticalPaddingPx

    override fun rememberGeometryConfig(config: NativeAppListConfig) {
        geometryLeftInset = config.leftSafeInsetPx
        geometryColumns = config.denseGridColumns
        geometryHorizontalPadding = config.denseGridHorizontalPaddingPx
        geometrySpacing = config.denseGridItemSpacingPx
        geometryAspectRatio = config.denseGridItemAspectRatio
        geometryMinimumItemWidth = config.denseGridMinimumItemWidthPx
        geometryMinimumItemHeight = config.denseGridMinimumItemHeightPx
        geometryMinimumVerticalPadding = config.denseGridMinimumVerticalPaddingPx
    }

    override fun createGeometry(
        itemCount: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        config: NativeAppListConfig,
    ): NativeAppGridGeometry = NativeDenseGridGeometry.create(
        itemCount = itemCount,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        leftSafeInsetPx = config.leftSafeInsetPx,
        columns = config.denseGridColumns,
        horizontalPaddingPx = config.denseGridHorizontalPaddingPx,
        itemSpacingPx = config.denseGridItemSpacingPx,
        itemAspectRatio = config.denseGridItemAspectRatio,
        minimumItemHeightPx = config.denseGridMinimumItemHeightPx,
        minimumItemWidthPx = config.denseGridMinimumItemWidthPx,
        minimumVerticalPaddingPx = config.denseGridMinimumVerticalPaddingPx,
    )

    override fun layoutChild(
        child: View,
        geometry: NativeAppGridGeometry,
        position: Int,
        config: NativeAppListConfig,
    ) {
        val dense = geometry as NativeDenseGridGeometry
        val layoutParams = child.layoutParams as RecyclerView.LayoutParams
        layoutParams.width = dense.itemWidthPx.roundToInt().coerceAtLeast(1)
        layoutParams.height = dense.itemHeightPx.roundToInt().coerceAtLeast(1)
        measureChildWithMargins(child, 0, 0)
        val centerX = dense.centerX(position)
        val centerY = dense.screenCenterY(position, currentRenderedScrollOffsetPx())
        val left = (centerX - getDecoratedMeasuredWidth(child) / 2f).roundToInt()
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
            // 不含重排预览：鱼眼带的进度是格心 y 的函数，读了预览位移就成了
            // 帧率级自反馈环（见
            // NativeAppListRowView.positionTranslationYWithoutPreviewOrFlow）。
            val positionTranslationX =
                row?.positionTranslationXWithoutPreviewOrFlow() ?: child.translationX
            val positionTranslationY =
                row?.positionTranslationYWithoutPreviewOrFlow() ?: child.translationY
            val centerX = getDecoratedLeft(child) + getDecoratedMeasuredWidth(child) / 2f +
                positionTranslationX
            val centerY = getDecoratedTop(child) + getDecoratedMeasuredHeight(child) / 2f +
                positionTranslationY
            val visual = nativeDenseGridChildVisual(
                centerX = centerX,
                centerY = centerY,
                viewportWidth = width.toFloat(),
                viewportHeight = height.toFloat(),
                config = config,
                output = childVisualScratch,
            )
            // 圆屏专属几何只保留列表与星环（老师指定），密集网格在圆屏上与方屏一致。
            // 以前这里按 deviceShape == ROUND 走一遍安全轮廓收边，而那道护栏会把
            // 布局槽位已经出屏的那一两行拽回屏内钉在弧上 —— 顶部/底部图标重叠的
            // 三个成因之一。密集网格的诉求本来就是「铺满方格」，不要弧线边界。
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
