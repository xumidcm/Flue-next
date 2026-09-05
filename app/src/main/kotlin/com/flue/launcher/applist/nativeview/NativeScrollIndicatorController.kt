package com.flue.launcher.applist.nativeview

/**
 * 指示器的活动和动画时钟；绘制只读取 [alpha]，不参与推进或调度。
 *
 * 宿主根据 [FrameResult] 保留一个动画帧或一个保持期唤醒，在失活、失焦、分离时调用
 * [reset] 并撤销回调。RecyclerView 的 scrollState 不进入这里，只有实际滚动或交互
 * 才刷新保持期，避免遗留 DRAGGING / SETTLING 状态让指示器一直显示。
 */
internal class NativeScrollIndicatorController {
    internal data class FrameResult(
        val alpha: Float,
        val needsNextFrame: Boolean,
        val nextWakeUptimeMillis: Long?,
    )

    var alpha = 0f
        private set

    private var pointerDown = false
    private var lastActivityUptimeMillis: Long? = null
    private var frameUptimeMillis: Long? = null
    private var animationTargetAlpha = 0f

    fun onPointerDown(nowUptimeMillis: Long): FrameResult {
        pointerDown = true
        return onActivity(nowUptimeMillis)
    }

    fun onPointerUp(nowUptimeMillis: Long): FrameResult {
        // reset 后到达的终止事件不能重新显示指示器，也不能延长已有的保持期。
        if (!pointerDown) return onFrame(nowUptimeMillis)
        pointerDown = false
        return onActivity(nowUptimeMillis)
    }

    /** 仅由非零的实际内容位移调用，不能用滚动状态通知替代。 */
    fun onScroll(nowUptimeMillis: Long): FrameResult = onActivity(nowUptimeMillis)

    /** 真实交互或显式显示请求；持续活动不会反复重置正在运行的淡入时钟。 */
    fun onActivity(nowUptimeMillis: Long): FrameResult {
        lastActivityUptimeMillis = nowUptimeMillis
        if (alpha != 1f && (frameUptimeMillis == null || animationTargetAlpha != 1f)) {
            frameUptimeMillis = nowUptimeMillis
        }
        animationTargetAlpha = 1f
        if (alpha == 1f) frameUptimeMillis = null
        return resultForTarget(1f)
    }

    /** 由独立的动画帧或保持期回调调用，时间必须和事件使用同一个 uptime 时钟。 */
    fun onFrame(nowUptimeMillis: Long): FrameResult {
        val targetAlpha = targetAlpha(nowUptimeMillis)
        val previousFrame = frameUptimeMillis
        if (previousFrame != null && animationTargetAlpha == targetAlpha) {
            alpha = nativeScrollIndicatorAdvanceAlpha(
                currentAlpha = alpha,
                targetAlpha = targetAlpha,
                elapsedMillis = (nowUptimeMillis - previousFrame).coerceAtLeast(0L),
            )
        }
        // 新方向和休眠后的首帧从当前 alpha 开始，不能把保持期或长时间空闲算作动画时长。
        animationTargetAlpha = targetAlpha
        frameUptimeMillis = if (alpha != targetAlpha) nowUptimeMillis else null
        return resultForTarget(targetAlpha)
    }

    fun reset(): FrameResult {
        alpha = 0f
        pointerDown = false
        lastActivityUptimeMillis = null
        frameUptimeMillis = null
        animationTargetAlpha = 0f
        return resultForTarget(0f)
    }

    private fun targetAlpha(nowUptimeMillis: Long): Float {
        if (pointerDown) return 1f
        val activity = lastActivityUptimeMillis ?: return 0f
        return nativeScrollIndicatorTargetAlpha(
            scrolling = false,
            millisSinceScrollActivity = (nowUptimeMillis - activity).coerceAtLeast(0L),
        )
    }

    private fun resultForTarget(targetAlpha: Float): FrameResult {
        val needsNextFrame = alpha != targetAlpha
        return FrameResult(
            alpha = alpha,
            needsNextFrame = needsNextFrame,
            nextWakeUptimeMillis = if (!needsNextFrame && alpha == 1f && !pointerDown) {
                lastActivityUptimeMillis?.plus(NATIVE_SCROLL_INDICATOR_HOLD_MS)
            } else {
                null
            },
        )
    }
}
