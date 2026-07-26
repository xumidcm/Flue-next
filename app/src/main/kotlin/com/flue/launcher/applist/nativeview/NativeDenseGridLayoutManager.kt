package com.flue.launcher.applist.nativeview

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

internal class NativeDenseGridLayoutManager(
    configProvider: () -> NativeAppListConfig,
) : NativeAppGridLayoutManager(configProvider) {
    private val childVisualScratch = NativeSpatialChildVisual()
    private val safeGeometryScratch = NativeSpatialChildVisual()
    private val safeGeometryAdapter = NativeDrawerGeometryAdapter()
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
            val positionTranslationX =
                row?.positionTranslationXWithoutFlow() ?: child.translationX
            val positionTranslationY =
                row?.positionTranslationYWithoutFlow() ?: child.translationY
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
            val safe = if (config.deviceShape == NativeDeviceShape.ROUND) {
                safeGeometryAdapter.resolve(
                    centerX = centerX,
                    centerY = centerY,
                    viewportWidth = width.toFloat(),
                    viewportHeight = height.toFloat(),
                    // Safe geometry must follow the actual painted icon, not
                    // the configured cell minimum. On a dense grid the cell
                    // can be wider/taller than the icon (inner padding), and
                    // using the estimate made round-screen edge clamping too
                    // aggressive and could throw for large icons.
                    itemWidth = row?.iconWidthForSafeGeometry()
                        ?.takeIf { it.isFinite() && it > 0f }
                        ?: getDecoratedMeasuredWidth(child).toFloat().coerceAtLeast(1f),
                    itemHeight = row?.iconHeightForSafeGeometry()
                        ?.takeIf { it.isFinite() && it > 0f }
                        ?: getDecoratedMeasuredHeight(child).toFloat().coerceAtLeast(1f),
                    config = config,
                    output = safeGeometryScratch,
                )
            } else {
                null
            }
            val flowTranslationX = safe?.translationX ?: visual.translationX
            val flowTranslationY = safe?.translationY?.plus(
                nativeEntryTranslationY(config, height.toFloat()),
            ) ?: visual.translationY
            if (row != null) {
                row.setFlowTranslation(flowTranslationX, flowTranslationY)
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
