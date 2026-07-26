package com.flue.launcher.applist.nativeview

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

internal class NativeHoneycombLayoutManager(
    configProvider: () -> NativeAppListConfig,
) : NativeAppGridLayoutManager(configProvider) {
    private val childVisualScratch = NativeSpatialChildVisual()
    private val safeGeometryScratch = NativeSpatialChildVisual()
    private val safeGeometryAdapter = NativeDrawerGeometryAdapter()
    private var geometryLeftInset = Int.MIN_VALUE
    private var geometryNarrowColumns = Int.MIN_VALUE
    private var geometryIconSize = Int.MIN_VALUE
    private var geometryCellSize = Float.NaN

    override fun hasSameGeometryConfig(
        config: NativeAppListConfig,
    ): Boolean =
        geometryLeftInset == config.leftSafeInsetPx &&
            geometryNarrowColumns == config.honeycombNarrowColumns &&
            geometryIconSize == config.iconSizePx &&
            geometryCellSize == config.honeycombCellSizePx

    override fun rememberGeometryConfig(config: NativeAppListConfig) {
        geometryLeftInset = config.leftSafeInsetPx
        geometryNarrowColumns = config.honeycombNarrowColumns
        geometryIconSize = config.iconSizePx
        geometryCellSize = config.honeycombCellSizePx
    }

    override fun createGeometry(
        itemCount: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        config: NativeAppListConfig,
    ): NativeAppGridGeometry = NativeHoneycombGeometry.create(
        itemCount = itemCount,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        leftSafeInsetPx = config.leftSafeInsetPx,
        narrowColumns = config.honeycombNarrowColumns,
        iconSizePx = config.iconSizePx.toFloat(),
        cellSizePx = config.honeycombCellSizePx.takeIf { it > 0f }
            ?: config.iconSizePx * 1.02f,
    )

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
            val positionTranslationX =
                row?.positionTranslationXWithoutFlow() ?: child.translationX
            val positionTranslationY =
                row?.positionTranslationYWithoutFlow() ?: child.translationY
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
            val safe = if (config.deviceShape == NativeDeviceShape.ROUND) {
                safeGeometryAdapter.resolve(
                    centerX = centerX,
                    centerY = centerY,
                    viewportWidth = width.toFloat(),
                    viewportHeight = height.toFloat(),
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
