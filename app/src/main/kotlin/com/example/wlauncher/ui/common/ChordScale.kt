package com.flue.launcher.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 圆屏逐元素弦宽适配。
 *
 * ## 为什么不用统一内边距
 *
 * 圆屏上一整块矩形内边距要按最窄的那一行留（离圆心最远的那行），中间的行
 * 就会白白浪费两侧空间；而左对齐文字在弧线附近仍然会被切掉。所以缩放必须
 * 逐元素按它自己所在高度的**弦宽**算。
 *
 * ## 算法（已定稿）
 *
 * ```
 * r      = 视口宽度 / 2
 * d      = |元素中心 y − 视口垂直中心|
 * chord  = 2 × √(r² − min(d, r)²)
 * scale  = (chord / 视口全宽).coerceIn(0.62, 1.0)
 * ```
 *
 * ## 三条铁律（违反任意一条就会抽搐）
 *
 * 1. **测量只用纯布局值**：`layoutInfo.visibleItemsInfo` 的 `offset` + `size`。
 *    绝不能从「被 transform 改过之后的边界」反推 scale —— 那是一个自反馈环：
 *    缩放 → 边界变小 → 算出更小的 scale → 继续缩。HTML 原型里用
 *    `getBoundingClientRect()` 就是这么翻车的，换成 `offsetTop`/`offsetHeight`
 *    才稳定。Compose 侧 `visibleItemsInfo` 天然是布局值，不含 graphicsLayer
 *    的变换，所以安全。
 * 2. **施加只走 [androidx.compose.ui.graphics.graphicsLayer]**（默认
 *    `transformOrigin` 居中即正确）。禁止动态 `Modifier.width()`、动态
 *    padding、动态 fontSize/TextUnit、逐行改约束宽度 —— 这些都会触发重新
 *    测量：文字换行位置变 → 行高变 → 元素位移 → 下一帧算出不同 scale。
 * 3. **必须等比**（`scaleX == scaleY`）。只缩 `scaleX` 会把文字压扁。
 *
 * ## 已知且接受的副作用
 *
 * graphicsLayer 不改布局槽位，所以缩小之后行与行之间的**视觉**间距会略微
 * 变大（槽位还是原来那么高，内容缩了）。这和 Wear Compose 的
 * `ScalingLazyColumn` 是同一种表现，属于这套方案的固有代价。
 * **不要**为了「修」这个而退回去改布局属性 —— 那就是抽搐的根因。
 */

/** 弦宽缩放下限。再小文字就不可读了，也会让行间空隙大到不像一个列表。 */
const val MIN_CHORD_SCALE = 0.62f

/**
 * 当前列表视口的宽度（px）。
 *
 * 靠 CompositionLocal 下发而不是加函数参数：设置页有 200+ 个调用点已经接好
 * 了现成的缩放通道，加参数等于逐个改一遍。宽度对整页是同一个值，用
 * CompositionLocal 传递没有歧义。
 *
 * 默认 0f = 「还没人下发」，此时 [chordScaleForPosition] 直接返回 1f 不缩放。
 */
val LocalChordViewportWidth = compositionLocalOf { 0f }

/**
 * 纯函数版：给定元素中心的 y 坐标，算出它那一行的弦宽缩放。
 *
 * 抽成纯函数是为了可单测 —— 边界（d=0 / d=r）和中间值都能直接对手算结果。
 *
 * @param itemCenterY 元素中心在视口坐标系里的 y（视口顶边为 0，向下为正）
 * @param viewportWidth 视口全宽 px；圆的直径即取此值
 * @param screenCenterY 视口垂直中心 y，也就是圆心的 y
 */
fun chordScaleForPosition(
    itemCenterY: Float,
    viewportWidth: Float,
    screenCenterY: Float,
): Float {
    val radius = viewportWidth / 2f
    if (radius <= 0f) return 1f
    // 夹到 r：元素中心滚出圆外时 r²−d² 会变负，直接按最窄处（弦长 0）处理，
    // 再由下面的 coerceIn 兜到 MIN_CHORD_SCALE。
    val distance = abs(itemCenterY - screenCenterY).coerceAtMost(radius)
    val chord = 2f * sqrt((radius * radius - distance * distance).coerceAtLeast(0f))
    return (chord / viewportWidth).coerceIn(MIN_CHORD_SCALE, 1f)
}

/**
 * 列表版（非 @Composable）：按 key 在可见项里找到该行，用它的布局位置算弦宽缩放。
 *
 * 之所以留一个非 Composable 版本：图层列表那种「每行带缩略图」的重列表不能
 * 每帧重组，得在 `graphicsLayer { }` 的 lambda 里读 —— 那个 lambda 是绘制期
 * 回调，读快照状态只会让图层失效重跑，不会触发行的重组。
 *
 * 与 [bottomFisheyeScale] 的区别：那个只作用于最后一两行（底部鱼眼），
 * 弦宽适配要作用于**全部**可见行，所以这里不做「只看尾部」的过滤。
 *
 * @param listTopInViewport 列表容器顶边在「圆所在坐标系」里的 y。列表不一定
 *   从屏幕顶端开始（编辑器上面还有预览画布和 TabRow），要把这段偏移补回去，
 *   否则圆心算在列表中点上，弦宽就错了。设置页列表铺满全屏时传 0f。
 * @param viewportWidth 视口全宽 px；传 0f 或负数表示未知 → 不缩放
 */
fun chordScaleForListItem(
    listState: LazyListState,
    key: Any,
    screenCenterY: Float,
    viewportWidth: Float,
    listTopInViewport: Float = 0f,
): Float {
    if (viewportWidth <= 0f) return 1f
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 1f
    // 高度护栏：比半径还高的项不缩放。
    //
    // 一个 scale 只能代表「一个高度」上的弦宽。项一旦高过半径，它就横跨了
    // 弧线上差别很大的一段：按中心算，中心滚到远端时整块会被夹到下限
    // 0.62，而它靠近屏幕中央的那半截本该接近全宽 —— 那比不缩放还难看。
    // 这种项（例如编辑器里 8 个按钮那一整段）继续吃圆屏的静态内边距。
    val radius = viewportWidth / 2f
    if (info.size > radius) return 1f
    // offset 与 size 都是布局值（不含 graphicsLayer 变换），满足铁律 1。
    val itemCenterY = listTopInViewport + info.offset + info.size / 2f
    return chordScaleForPosition(itemCenterY, viewportWidth, screenCenterY)
}

/**
 * 列表版（@Composable）：设置页 200+ 调用点走这条。
 *
 * @param viewportWidth 视口全宽 px；传 0f 或负数表示未知 → 不缩放
 */
@Composable
fun chordScale(
    listState: LazyListState,
    key: Any,
    screenCenterY: Float,
    viewportWidth: Float,
): Float = chordScaleForListItem(listState, key, screenCenterY, viewportWidth)

/**
 * 给列表项挂弦宽缩放的现成 Modifier。
 *
 * 缩放在 [androidx.compose.ui.graphics.graphicsLayer] 的 lambda 里算，属于绘制期
 * 回调：滚动只会让这一层失效重画，不会重组列表项本身。
 *
 * @param enabled 方屏传 false —— 整条 modifier 退化成 no-op，行为与改动前一致
 * @param listTopInViewport 传 lambda 而不是 Float：如果当普通参数传，取值发生在
 *   组合期，列表顶边一变（面板高度动画每帧都在变）就会重组整个列表项。包成
 *   lambda 后这个读取被推迟到绘制期，只让图层失效。
 */
fun Modifier.chordScaleItem(
    listState: LazyListState,
    key: Any,
    screenCenterY: Float,
    viewportWidth: Float,
    enabled: Boolean = true,
    listTopInViewport: () -> Float = { 0f },
): Modifier = if (!enabled) {
    this
} else {
    this.graphicsLayer {
        val s = chordScaleForListItem(
            listState = listState,
            key = key,
            screenCenterY = screenCenterY,
            viewportWidth = viewportWidth,
            listTopInViewport = listTopInViewport(),
        )
        scaleX = s
        scaleY = s
    }
}
