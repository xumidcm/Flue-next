package com.flue.launcher.applist.nativeview

import com.flue.launcher.ui.common.chordScaleForDistance

/**
 * 圆屏列表模式的「曲线」：整行按它所在高度的**弦宽**等比缩小，行的两端于是沿着屏幕
 * 内切圆收进去 —— 观感上列表被裁成了一条贴合圆边缘的曲线。
 *
 * ## 为什么直接复用弦宽缩放，而不另算一条曲线
 *
 * 设置页的圆屏适配已经有一套定稿的机制（[com.flue.launcher.ui.common.chordScaleForDistance]，
 * 见 `ChordScale.kt` 的三条铁律）。列表这边再发明一条曲线，两处的收缩速率一定会漂移，
 * 用户在设置页和应用列表之间来回切会看到两种「圆屏适配」。所以这里只是把同一条弦长
 * 公式接到 RecyclerView 的行上。
 *
 * ## 三条铁律在这条路上怎么落地
 *
 * 1. **只测量布局值**：入参 `childCenterY` 由 `child.top + child.height / 2` 加上行自己的
 *    移动 / 拖拽 / 下拉位移算出，全都不是从缩放后的边界反推的，所以不存在
 *    「缩放 → 边界变小 → 算出更小的缩放」这个自反馈环。
 * 2. **只通过变换施加**：结果乘进 `NativeAppListRowView.setVisualScale`，最终落在
 *    View 的 `scaleX/scaleY` 上（graphicsLayer 在 View 世界里的对应物），不改任何
 *    布局尺寸、padding 或字号 —— 那些都会触发重新测量并导致抽搐。
 * 3. **等比**：单一返回值同时喂 scaleX 和 scaleY。
 */
internal fun nativeRoundChordScale(
    childCenterY: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    deviceShape: NativeDeviceShape,
): Float {
    if (deviceShape != NativeDeviceShape.ROUND) return 1f
    if (
        !childCenterY.isFinite() ||
        !viewportWidthPx.isFinite() || viewportWidthPx <= 0f ||
        !viewportHeightPx.isFinite() || viewportHeightPx <= 0f
    ) {
        return 1f
    }
    // 圆心取屏幕垂直中心，圆的直径取**视口宽度** —— 这是 ChordScale 的既定契约：
    // 返回值是「这一行能用的弦宽 ÷ 行的布局宽度」，而行的布局宽度就是视口宽度。
    // 圆屏机型视口本来就是正方形，所以它同时也等于内切圆直径，与
    // NativeDrawerGeometryAdapter 收图标用的那个圆重合。
    return chordScaleForDistance(
        distanceFromCenter = childCenterY - viewportHeightPx / 2f,
        viewportWidth = viewportWidthPx,
    )
}

/** LINEAR round-screen rows always receive the chord transform at draw time. */
internal fun nativeRoundLinearRowScale(
    childCenterY: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    fisheyeEnabled: Boolean,
): Float = nativeRoundChordScale(
    childCenterY = childCenterY,
    viewportWidthPx = viewportWidthPx,
    viewportHeightPx = viewportHeightPx,
    deviceShape = NativeDeviceShape.ROUND,
)
