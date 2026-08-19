package com.flue.launcher.applist.nativeview

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * 星环的 RecyclerView 布局管理器。
 *
 * 与 [NativeDenseGridLayoutManager] 是平级兄弟，共用 [NativeAppGridLayoutManager] 那套
 * 生命周期 / 回收 / 滚动度量。两处差别：
 *
 * 1. 几何换成 [NativeRingGeometry]（铺满可用宽度的正方格）；
 * 2. 圆屏上在选定基准 translation **之后**再叠一道径向收拢。
 */
internal class NativeRingLayoutManager(
    configProvider: () -> NativeAppListConfig,
) : NativeAppGridLayoutManager(configProvider) {
    private val childVisualScratch = NativeSpatialChildVisual()
    private val safeGeometryScratch = NativeSpatialChildVisual()
    private val convergenceScratch = NativeRingOffset()
    private val safeGeometryAdapter = NativeDrawerGeometryAdapter()
    private var geometryLeftInset = Int.MIN_VALUE
    private var geometryColumns = Int.MIN_VALUE
    private var geometryCellSize = Float.NaN

    override fun hasSameGeometryConfig(
        config: NativeAppListConfig,
    ): Boolean =
        geometryLeftInset == config.leftSafeInsetPx &&
            geometryColumns == config.honeycombNarrowColumns &&
            geometryCellSize == config.ringCellSizePx

    override fun rememberGeometryConfig(config: NativeAppListConfig) {
        geometryLeftInset = config.leftSafeInsetPx
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
        columns = config.honeycombNarrowColumns,
        cellSizePx = config.ringCellSizePx.takeIf { it.isFinite() && it > 0f }
            ?: nativeRingCellSizePx(
                viewportWidthPx = viewportWidthPx,
                leftSafeInsetPx = config.leftSafeInsetPx,
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
        val round = config.deviceShape == NativeDeviceShape.ROUND
        for (index in 0 until childCount) {
            val child = requireNotNull(getChildAt(index))
            val row = child as? NativeAppListRowView
            val positionTranslationX =
                row?.positionTranslationXWithoutFlow() ?: child.translationX
            val positionTranslationY =
                row?.positionTranslationYWithoutFlow() ?: child.translationY
            // 只读布局值（decorated 边界 + 拖拽位移），绝不读被 scale/translation 改过
            // 之后的边界 —— 那是帧率级自反馈环，ChordScale.kt 铁律一。
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
            // 方屏：收拢恒为 0，网格平铺（老师指定）。
            val convergence = if (round) {
                nativeRingConvergenceOffset(
                    centerX = centerX,
                    centerY = centerY,
                    viewportWidth = width.toFloat(),
                    viewportHeight = height.toFloat(),
                    config = config,
                    output = convergenceScratch,
                )
            } else {
                convergenceScratch.set(0f, 0f)
            }
            // 圆屏那条安全轮廓拿到的是**收拢之后**的期望格心，而不是原始格心。
            //
            // 顺序很关键：适配器整体替换 translation，把收拢先加进 visual 会被它吃掉
            // （心流的月牙踩过这个坑）。而这里比心流更进一步 —— 心流是「安全 translation
            // 之后再叠月牙」，星环把收拢喂进适配器再把它加回来，于是**夹取发生在收拢
            // 之后**：角上那些格子被收进圆里之后不会再被轮廓二次搬动，也不可能出现
            // 「收拢把已经贴边的图标推过屏心」这种越界。
            val safe = if (round) {
                safeGeometryAdapter.resolve(
                    centerX = centerX + convergence.x,
                    centerY = centerY + convergence.y,
                    viewportWidth = width.toFloat(),
                    viewportHeight = height.toFloat(),
                    // 安全轮廓要跟着**真正画出来的图标**，不是格子：星环的格子比图标
                    // 大一圈（RING_ICON_CELL_FRACTION 留的空隙），按格子夹会把边缘
                    // 图标往里推得过多。
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
            val flowTranslationX = if (safe != null) {
                convergence.x + safe.translationX
            } else {
                visual.translationX + convergence.x
            }
            val flowTranslationY = if (safe != null) {
                convergence.y + safe.translationY +
                    nativeEntryTranslationY(config, height.toFloat())
            } else {
                visual.translationY + convergence.y
            }
            if (row != null) {
                row.setFlowTranslation(flowTranslationX, flowTranslationY)
                // 等比缩放（铁律三）：setVisualScale 同时写 scaleX/scaleY。
                row.setVisualScale(visual.scale)
                row.setIconBlurRadiusPx(visual.blurRadiusPx, config.effectiveBlurTier())
                row.setIconSaturation(visual.saturation)
            } else {
                child.translationX = flowTranslationX
                child.translationY = flowTranslationY
                child.scaleX = visual.scale
                child.scaleY = visual.scale
            }
            child.alpha = visual.alpha
        }
    }
}
