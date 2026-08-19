package com.flue.launcher.applist.nativeview

import kotlin.math.min

/**
 * 屏幕右侧「滑动进度指示器」的几何。
 *
 * ## 为什么全部是纯函数
 *
 * 指示器画在 [NativeAppListView.dispatchDraw] 里，那是绘制期回调，没法单测。把
 * 「滚动量 → 轨道上的一段」这段映射整个抽出来之后，圆屏 / 方屏两条路、进度 0 和 1
 * 的端点、内容不足一屏时不显示，全都能直接对手算结果。
 *
 * ## 圆屏与方屏是同一套映射
 *
 * 轨道和滑块都用同一个一维区间 [NativeScrollIndicatorSpan] 表达，**单位由屏形决定**：
 *  * 圆屏 → 单位是**角度**，轨道是屏幕内切圆上以 3 点方向为中点的一段弧；
 *  * 方屏 → 单位是**像素**，轨道是贴右边缘的一段竖直线段。
 *
 * 方屏可以看成圆屏半径趋于无穷的退化情形：弧拉直成直线，角度换成像素。所以
 * [nativeScrollIndicatorThumbSpan] 两种屏形共用一份，不存在「两套算法漂移成两种手感」
 * 的可能 —— 只有最后一步投影（角度→弧 / 像素→线段）不同。
 *
 * ## 为什么不从 RecyclerView 的子 View 边界反推位置
 *
 * 和 [com.flue.launcher.ui.common.chordScaleForDistance] 那套弦宽缩放的铁律 1 同源：
 * 列表的行带着鱼眼缩放和入场位移，变换后的边界不是布局值。指示器只吃
 * `computeVerticalScrollOffset/Range/Extent` —— 五种布局都把这三个量重写成了精确的
 * **布局空间**值（线性一份见 `NativeAppListLayoutStrategy`，网格基类一份见
 * `NativeAppGridLayoutManager`，蜂窝 / 心流 / 星环继承基类那份），与变换无关，
 * 因此不会形成「缩放 → 边界变 → 进度变 → 重画」的帧率级回路。
 */

/**
 * 圆屏轨道张开的总角度。
 *
 * 60° 不是随手取的：圆屏视口是正方形，半径 = 高 / 2，60° 的弦长
 * `2R·sin(30°) = R` 正好是**半个屏幕高**。方屏那侧的轨道占 76% 屏高（见
 * [NATIVE_SCROLL_INDICATOR_BAR_INSET_FRACTION]）—— 两者不等是故意的：弧贴着圆边缘走，
 * 再长两端就压到 12 点 / 6 点方向去了，那两处离右边缘已经很远，看起来不再是「右侧的条」。
 */
internal const val NATIVE_SCROLL_INDICATOR_ARC_SWEEP_DEG = 60f

/** 方屏轨道上下各留的视口高度比例（0.12 → 轨道占 76% 屏高，Apple Watch 那种细长条）。 */
internal const val NATIVE_SCROLL_INDICATOR_BAR_INSET_FRACTION = 0.12f

/** 描边粗细。3dp 是 watchOS 侧栏指示器的观感厚度，再细在低分辨率手表上会被抗锯齿吃掉。 */
internal const val NATIVE_SCROLL_INDICATOR_THICKNESS_DP = 3f

/** 描边中心到屏幕边缘的留白。 */
internal const val NATIVE_SCROLL_INDICATOR_EDGE_MARGIN_DP = 4f

/** 圆屏滑块最短角度。应用极多时按比例算出来不足 1°，圆头描边画出来就是个点。 */
internal const val NATIVE_SCROLL_INDICATOR_MIN_THUMB_DEG = 7f

/** 方屏滑块最短长度（dp）。同上，只是单位换成像素。 */
internal const val NATIVE_SCROLL_INDICATOR_MIN_THUMB_DP = 12f

/** 轨道底色不透明度。刻意压得很低：它只是给滑块一个参照，不该跟内容抢注意力。 */
internal const val NATIVE_SCROLL_INDICATOR_TRACK_ALPHA = 0.16f

/** 滑块不透明度。 */
internal const val NATIVE_SCROLL_INDICATOR_THUMB_ALPHA = 0.72f

/** 轨道 / 滑块共用的一维区间。单位由屏形决定：圆屏为角度（度），方屏为像素。 */
internal data class NativeScrollIndicatorSpan(
    val start: Float,
    val extent: Float,
) {
    val end: Float get() = start + extent
}

/**
 * 指示器的可见性与进度。
 *
 * @param progress 0 = 内容顶端，1 = 内容底端。[visible] 为 false 时无意义。
 * @param visibleFraction 视口占内容总长的比例，滑块长度按它取。
 */
internal data class NativeScrollIndicatorState(
    val visible: Boolean,
    val progress: Float,
    val visibleFraction: Float,
)

private val NATIVE_SCROLL_INDICATOR_HIDDEN = NativeScrollIndicatorState(
    visible = false,
    progress = 0f,
    visibleFraction = 1f,
)

/**
 * 由 RecyclerView 的三个滚动量算出指示器状态。
 *
 * 内容不足一屏（`range <= extent`）时直接判不可见：那种情况下滑块会占满整条轨道，
 * 画出来是一条永远不动的杠，只是噪声。
 */
internal fun nativeScrollIndicatorState(
    scrollOffsetPx: Int,
    scrollRangePx: Int,
    viewportHeightPx: Int,
): NativeScrollIndicatorState {
    if (viewportHeightPx <= 0 || scrollRangePx <= 0) return NATIVE_SCROLL_INDICATOR_HIDDEN
    val scrollablePx = scrollRangePx - viewportHeightPx
    if (scrollablePx <= 0) return NATIVE_SCROLL_INDICATOR_HIDDEN
    return NativeScrollIndicatorState(
        visible = true,
        progress = (scrollOffsetPx.toFloat() / scrollablePx).coerceIn(0f, 1f),
        visibleFraction = (viewportHeightPx.toFloat() / scrollRangePx).coerceIn(0f, 1f),
    )
}

/**
 * 进度 → 滑块在轨道上的那一段。圆屏 / 方屏共用。
 *
 * 关键约束：`progress == 0` 时滑块**起点**贴轨道起点，`progress == 1` 时滑块**终点**
 * 贴轨道终点。所以行程是 `轨道长 − 滑块长`，而不是轨道长 —— 后者会让列表滚到底时
 * 滑块整段跑出轨道尾端。
 *
 * @param minimumExtent 滑块最短长度。应用很多时 `visibleFraction` 会小到滑块只有几个
 *   像素 / 一两度，圆屏上带圆头描边画出来就是个点，看不出朝向。
 */
internal fun nativeScrollIndicatorThumbSpan(
    track: NativeScrollIndicatorSpan,
    visibleFraction: Float,
    progress: Float,
    minimumExtent: Float,
): NativeScrollIndicatorSpan {
    val trackExtent = track.extent.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val minimum = minimumExtent.takeIf(Float::isFinite)?.coerceIn(0f, trackExtent) ?: 0f
    val fraction = visibleFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
    val thumbExtent = (trackExtent * fraction).coerceIn(minimum, trackExtent)
    val travel = (trackExtent - thumbExtent).coerceAtLeast(0f)
    val clampedProgress = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    return NativeScrollIndicatorSpan(
        start = track.start + travel * clampedProgress,
        extent = thumbExtent,
    )
}

/**
 * 圆屏轨道：以 3 点方向（0°）为中点、对称张开 [sweepDeg] 的一段弧。
 *
 * 角度约定跟 [android.graphics.Canvas.drawArc] 一致：0° = 3 点方向，正角顺时针，
 * 也就是屏幕上**向下**。于是「进度增大 → 起始角增大 → 滑块下移」，和列表往下滚的方向同向。
 */
internal fun nativeScrollIndicatorArcTrack(sweepDeg: Float): NativeScrollIndicatorSpan {
    val sweep = sweepDeg.takeIf(Float::isFinite)?.coerceIn(0f, 360f) ?: 0f
    return NativeScrollIndicatorSpan(start = -sweep / 2f, extent = sweep)
}

/**
 * 圆屏弧的描边中心半径。
 *
 * 半径按**屏幕内切圆**取（`min(宽, 高) / 2`），圆心就是屏幕中心 —— 与
 * [NativeDrawerGeometryAdapter] 把图标收进圆内用的那个圆是同一个，指示器因此和列表
 * 内容贴的是同一条弧，不会出现「图标沿一条弧、指示器沿另一条弧」。
 *
 * 减掉 `thicknessPx / 2` 是因为 [android.graphics.Paint.Style.STROKE] 的描边以路径为中心
 * 向两侧各扩一半：只减边距的话描边外沿会超出边距、贴上（甚至越过）圆边缘。
 */
internal fun nativeScrollIndicatorArcRadiusPx(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    edgeMarginPx: Float,
    thicknessPx: Float,
): Float {
    val inscribedRadius = min(
        viewportWidthPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f,
        viewportHeightPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f,
    ) / 2f
    val margin = edgeMarginPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val halfThickness = (thicknessPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f) / 2f
    return (inscribedRadius - margin - halfThickness).coerceAtLeast(0f)
}

/**
 * 方屏轨道：贴右边缘的一段竖直线段，上下各留 [verticalInsetFraction] 的视口高度。
 *
 * 留白按比例而不是固定 dp：手表屏幕尺寸跨度不大但纵横比不一，比例能让指示器在任何
 * 一块屏上都占同样的视觉份额。
 */
internal fun nativeScrollIndicatorBarTrack(
    viewportHeightPx: Float,
    verticalInsetFraction: Float,
): NativeScrollIndicatorSpan {
    val height = viewportHeightPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val inset = height * (verticalInsetFraction.takeIf(Float::isFinite)?.coerceIn(0f, 0.49f) ?: 0f)
    return NativeScrollIndicatorSpan(start = inset, extent = (height - inset * 2f).coerceAtLeast(0f))
}

/**
 * 方屏描边中心的 x。同样要减半个描边宽，否则描边右沿贴到（或越过）视口右边界。
 */
internal fun nativeScrollIndicatorBarCenterXPx(
    viewportWidthPx: Float,
    edgeMarginPx: Float,
    thicknessPx: Float,
): Float {
    val width = viewportWidthPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val margin = edgeMarginPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val halfThickness = (thicknessPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f) / 2f
    return (width - margin - halfThickness).coerceAtLeast(0f)
}
