package com.flue.launcher.applist.nativeview

/**
 * 指示器的淡入淡出时序，与 [NativeScrollIndicatorGeometry][nativeScrollIndicatorState]
 * 那套纯几何分开放:几何回答「画在哪」,这里回答「现在该有多不透明」。
 *
 * ## 为什么要淡出
 *
 * 老师要的是「和 Apple Watch 一样」的滑动进度指示器,而 watchOS 那条指示器是滚动时出现、
 * 停下后淡出的 —— 常显的一条杠会一直压在内容右边,在 230dp 的圆屏上很占地方。
 *
 * ## 为什么淡入比淡出快得多
 *
 * 淡入慢会被读成「卡了一下才反应」:手指已经推动内容了,指示器还在半透明里爬。
 * 淡出快会被读成「闪了一下没了」,像画面故障。所以是快进慢出,不是对称的。
 */

/** 淡入时长。要短到手指刚推出几像素时它就已经在了。 */
internal const val NATIVE_SCROLL_INDICATOR_FADE_IN_MS = 110L

/**
 * 停止滚动后保持全不透明的窗口。
 *
 * 留这一段是因为惯性滑动收尾时 `dy` 会先归零、状态才转 IDLE,没有保持窗口的话
 * 指示器会在滑动真正停住之前就开始淡出,末尾那一下看着像掉帧。
 */
internal const val NATIVE_SCROLL_INDICATOR_HOLD_MS = 700L

/** 淡出时长。比淡入长,退场要柔和，同时保持在 200-300ms 的响应窗口内。 */
internal const val NATIVE_SCROLL_INDICATOR_FADE_OUT_MS = 260L

/**
 * 惯性(SETTLING)状态仍算「在动」的新鲜窗口。
 *
 * 惯性中每一帧都有滚动事件把活动时间戳刷新,所以只要超过这个窗口没有新事件,
 * 就说明惯性实际上已经停了 —— 哪怕 `scrollState` 因为某条路径(拖拽移交吞事件、
 * 边界弹簧吸收)没回 IDLE,也不再按「在动」处理。没有这道保险,状态卡在 SETTLING
 * 的那次惯性会让指示器永远全亮:快速甩动抬手,指示器赖着不消失,正是这个病。
 */
internal const val NATIVE_SCROLL_INDICATOR_SETTLING_FRESH_MS = 120L

/**
 * 目标不透明度:手指按着、正在滚动、或停下后还在保持窗口内 = 1,否则 0。
 *
 * @param scrolling 列表此刻是否真的在动(DRAGGING 且手指在上,或惯性 SETTLING)。
 *   **不能直接拿 `scrollState != IDLE`**:原生列表的手势路径里 UP 事件可能根本没送到
 *   RecyclerView(拖图标那条链路会自己吞掉终止事件),状态就永久停在 DRAGGING,目标恒 1,
 *   指示器再也不淡出 —— 星环圆屏最容易稳定复现,正是老师报的「滑动指示器不会消失」。
 * @param millisSinceScrollActivity 距上一次「滚过 / 按下 / 松手」多久。松手时要重新起算,
 *   这样 [NATIVE_SCROLL_INDICATOR_HOLD_MS] 是「松手之后」的延迟,而不是「内容最后一次
 *   移动之后」的延迟。
 * @param pointerDown 手指是否还按在列表上。按着就恒 1:按住不放但已经到顶/到底时内容不
 *   移动,只看 `millisSinceScrollActivity` 会在几百毫秒后判成该淡出,而手指明明还在滑。
 */
internal fun nativeScrollIndicatorTargetAlpha(
    scrolling: Boolean,
    millisSinceScrollActivity: Long,
    pointerDown: Boolean = false,
): Float = if (
    pointerDown ||
    scrolling ||
    millisSinceScrollActivity < NATIVE_SCROLL_INDICATOR_HOLD_MS
) {
    1f
} else {
    0f
}

/**
 * 把当前不透明度朝 [targetAlpha] 推进 [elapsedMillis] 毫秒。
 *
 * 用「按时长线性推进」而不是弹簧或指数逼近:指数逼近永远到不了 0,会留一条几乎看不见
 * 但仍在每帧重绘的杠,自我调度的失效帧就一直不停。线性推进有确定的终点,到点即停。
 *
 * 中途反向(淡出没完又开始滚)从**当前值**继续推,不重头来 —— 重头来会看到一次跳变。
 */
internal fun nativeScrollIndicatorAdvanceAlpha(
    currentAlpha: Float,
    targetAlpha: Float,
    elapsedMillis: Long,
): Float {
    val from = currentAlpha.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val to = targetAlpha.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    if (from == to) return to
    val durationMs = if (to > from) {
        NATIVE_SCROLL_INDICATOR_FADE_IN_MS
    } else {
        NATIVE_SCROLL_INDICATOR_FADE_OUT_MS
    }
    // 时长常量若被改成 0,退化成立刻到位而不是除零。
    if (durationMs <= 0L) return to
    val step = elapsedMillis.coerceAtLeast(0L).toFloat() / durationMs.toFloat()
    return if (to > from) {
        (from + step).coerceAtMost(to)
    } else {
        (from - step).coerceAtLeast(to)
    }
}
