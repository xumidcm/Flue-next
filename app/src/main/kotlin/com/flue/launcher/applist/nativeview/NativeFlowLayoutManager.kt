package com.flue.launcher.applist.nativeview

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.util.FlowCrescentDirection
import kotlin.math.roundToInt

/**
 * 心流的 RecyclerView 布局管理器。
 *
 * 与 [NativeHoneycombLayoutManager] 是平级兄弟，共用 [NativeAppGridLayoutManager] 那套
 * 生命周期 / 回收 / 滚动度量。两处差别只有：
 *
 * 1. 几何换成 [NativeFlowGeometry]（等列网格，不做宽窄交替）；
 * 2. 子项视觉在选定基准 translationX **之后**再叠一道月牙偏移
 *    （见 [nativeFlowCrescentTranslationX] 里为什么必须是「之后」）。
 *
 * 鱼眼本体照抄蜂窝的 [nativeHoneycombChildVisual]，不另写一份：心流的图标缩放按老师要求
 * 就是吃同一套鱼眼参数，复制一份只会让两处慢慢跑偏。
 */
internal class NativeFlowLayoutManager(
    configProvider: () -> NativeAppListConfig,
) : NativeAppGridLayoutManager(configProvider) {
    private val childVisualScratch = NativeSpatialChildVisual()
    private var geometryLeftInset = Int.MIN_VALUE
    private var geometryColumns = Int.MIN_VALUE
    private var geometryIconSize = Int.MIN_VALUE
    private var geometryCellSize = Float.NaN
    private var geometryCrescentBulge = Float.NaN
    private var geometryCrescentToRight: Boolean? = null

    override fun hasSameGeometryConfig(
        config: NativeAppListConfig,
    ): Boolean =
        geometryLeftInset == config.leftSafeInsetPx &&
            geometryColumns == config.flowColumns &&
            geometryIconSize == config.iconSizePx &&
            geometryCellSize == config.flowCellSizePx &&
            // 月牙也进几何比较：基准 x 是按它算的，改弧度 / 朝向必须重建几何，
            // 否则峰值那几行的基准还是旧的，弧会重新越界。
            geometryCrescentBulge == config.flowCrescentBulgeFraction &&
            geometryCrescentToRight ==
            (config.flowCrescentDirection == FlowCrescentDirection.Right)

    override fun rememberGeometryConfig(config: NativeAppListConfig) {
        geometryLeftInset = config.leftSafeInsetPx
        geometryColumns = config.flowColumns
        geometryIconSize = config.iconSizePx
        geometryCellSize = config.flowCellSizePx
        geometryCrescentBulge = config.flowCrescentBulgeFraction
        geometryCrescentToRight = config.flowCrescentDirection == FlowCrescentDirection.Right
    }

    override fun createGeometry(
        itemCount: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        config: NativeAppListConfig,
    ): NativeAppGridGeometry = NativeFlowGeometry.create(
        itemCount = itemCount,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        leftSafeInsetPx = config.leftSafeInsetPx,
        columns = config.flowColumns,
        iconSizePx = config.iconSizePx.toFloat(),
        cellSizePx = config.flowCellSizePx.takeIf { it > 0f }
            ?: (config.iconSizePx * 1.02f),
        requestedCrescentBulgeFraction = config.flowCrescentBulgeFraction,
        crescentToRight = config.flowCrescentDirection == FlowCrescentDirection.Right,
    )

    override fun layoutChild(
        child: View,
        geometry: NativeAppGridGeometry,
        position: Int,
        config: NativeAppListConfig,
    ) {
        val flow = geometry as NativeFlowGeometry
        val cell = config.flowCellSizePx
            .takeIf { it > 0f }
            ?.roundToInt()
            ?: (flow.iconSizePx * 1.02f).roundToInt()
        val layoutParams = child.layoutParams as RecyclerView.LayoutParams
        layoutParams.width = cell
        layoutParams.height = cell
        measureChildWithMargins(child, 0, 0)
        val placement = flow.placements[position]
        val centerY = flow.screenCenterY(position, currentRenderedScrollOffsetPx())
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
        val flow = geometry as NativeFlowGeometry
        for (index in 0 until childCount) {
            val child = requireNotNull(getChildAt(index))
            val row = child as? NativeAppListRowView
            // 只读**不含重排预览**的位移通道。月牙横移是行 y 的函数，读了预览位移就成了
            // 「预览 → 月牙 → 命中坐标 → 新预览」的帧率级自反馈环 —— 那正是老师报的
            // 「心流拖动时整个列表散掉」。详见
            // NativeAppListRowView.positionTranslationYWithoutPreviewOrFlow 的注释。
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
                // 心流的鱼眼只按垂直距离算：基准列的 x 恒定偏离屏幕中线，
                // 二维半径会把明暗和月牙的横向位移绑在一起。详见那个参数的注释。
                verticalOnlyFisheye = true,
            )
            // 月牙叠在基准之后。这里**不再**按 deviceShape 分叉：老师指定圆屏专属几何
            // 只保留列表与星环，心流 / 蜂窝 / 密集网格在圆屏上与方屏一致（不收边）。
            // 圆屏收边原本会把屏外那一两行拽进屏内钉在弧上，正是「顶上和底下图标重叠」
            // 的来源之一；而心流本来就用月牙自己控制越界，不需要第二道轮廓。
            val crescentX = nativeFlowCrescentTranslationX(
                bulgePx = flow.crescentBulgePx,
                toRight = flow.crescentToRight,
                childCenterY = centerY,
                viewportHeight = height.toFloat(),
            )
            val flowTranslationX = visual.translationX + crescentX
            val flowTranslationY = visual.translationY
            if (row != null) {
                row.setFlowTranslation(flowTranslationX, flowTranslationY)
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
