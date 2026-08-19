package com.flue.launcher.applist.nativeview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView

/**
 * Move-only RecyclerView animator. Every visible displacement in a DiffUtil
 * commit shares one ValueAnimator, keeping the old-watch hot path bounded.
 */
internal class NativeStableKeyItemAnimator(
    moveDurationMillis: Long = 180L,
    private val shouldAnimateHolder: (RecyclerView.ViewHolder) -> Boolean = { true },
    private val onMoveFrame: () -> Unit = {},
) : RecyclerView.ItemAnimator() {
    private class Move(
        val holder: RecyclerView.ViewHolder,
        val startTranslationX: Float,
        val startTranslationY: Float,
    ) {
        var active: Boolean = true
    }

    private val pendingMoves = LinkedHashMap<RecyclerView.ViewHolder, Move>()
    private val activeMoves = LinkedHashMap<RecyclerView.ViewHolder, Move>()
    private var activeFrameMoves: Array<Move> = emptyArray()
    private val interpolator = DecelerateInterpolator(1.5f)
    private var batchAnimator: ValueAnimator? = null

    init {
        moveDuration = moveDurationMillis.coerceAtLeast(0L)
        addDuration = 0L
        removeDuration = 0L
        changeDuration = 0L
    }

    override fun animateDisappearance(
        viewHolder: RecyclerView.ViewHolder,
        preLayoutInfo: ItemHolderInfo,
        postLayoutInfo: ItemHolderInfo?,
    ): Boolean = false

    override fun animateAppearance(
        viewHolder: RecyclerView.ViewHolder,
        preLayoutInfo: ItemHolderInfo?,
        postLayoutInfo: ItemHolderInfo,
    ): Boolean = false

    override fun animatePersistence(
        viewHolder: RecyclerView.ViewHolder,
        preLayoutInfo: ItemHolderInfo,
        postLayoutInfo: ItemHolderInfo,
    ): Boolean {
        if (!shouldAnimateHolder(viewHolder)) return false
        val deltaX = (preLayoutInfo.left - postLayoutInfo.left).toFloat()
        val deltaY = (preLayoutInfo.top - postLayoutInfo.top).toFloat()
        if (deltaX == 0f && deltaY == 0f) return false
        endAnimation(viewHolder)
        setMoveTranslation(viewHolder, deltaX, deltaY)
        pendingMoves[viewHolder] = Move(viewHolder, deltaX, deltaY)
        return true
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        preLayoutInfo: ItemHolderInfo,
        postLayoutInfo: ItemHolderInfo,
    ): Boolean = if (oldHolder === newHolder) {
        animatePersistence(oldHolder, preLayoutInfo, postLayoutInfo)
    } else {
        false
    }

    /**
     * RecyclerView 每个动画周期只会调这里一次。
     *
     * 批动画器还活着时**故意推迟**（复用同一个 ValueAnimator 是老表上的热路径预算），
     * 靠 `onAnimationEnd` 里再进一次来接上 —— 所以那个监听绝不能在还有 pendingMoves 的
     * 时候被摘掉，见 [endAnimation] 与 [stopBatchAnimator]。
     */
    override fun runPendingAnimations() {
        if (pendingMoves.isEmpty() || batchAnimator != null) return
        activeMoves.putAll(pendingMoves)
        pendingMoves.clear()
        activeFrameMoves = activeMoves.values.toTypedArray()
        if (moveDuration == 0L) {
            finishActiveBatch()
            return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = moveDuration
            interpolator = this@NativeStableKeyItemAnimator.interpolator
            addUpdateListener { animator ->
                val remaining = 1f - animator.animatedFraction
                var index = 0
                while (index < activeFrameMoves.size) {
                    val move = activeFrameMoves[index]
                    if (move.active) {
                        setMoveTranslation(
                            move.holder,
                            move.startTranslationX * remaining,
                            move.startTranslationY * remaining,
                        )
                    }
                    index++
                }
                onMoveFrame()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    batchAnimator = null
                    finishActiveBatch()
                    if (pendingMoves.isNotEmpty()) runPendingAnimations()
                }
            })
            batchAnimator = this
            start()
        }
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        val removed = pendingMoves.remove(item) ?: activeMoves.remove(item)
        if (removed != null) {
            removed.active = false
            reset(item)
            onMoveFrame()
            dispatchAnimationFinished(item)
        }
        // 只有「活跃与待启动都空了」才拆批动画器。
        //
        // 这里曾经只看 activeMoves。只要**在 180ms 位移动画还没跑完时又来一次差分提交**
        // （装/卸应用就是：刷新先发布一次列表，紧随其后的顺序 / 隐藏集变更再发布一次），
        // 新提交的位移就还躺在 pendingMoves 里，而 animatePersistence 开头对每个 holder 调
        // 的 endAnimation 恰好把上一批最后一个活跃项摘走 —— activeMoves 空了，批动画器被
        // stopBatchAnimator 摘掉监听后 cancel，onAnimationEnd 再也不会跑，pendingMoves 里
        // 那些 holder 就**永远等不到 dispatchAnimationFinished**。
        //
        // 后果正是老师报的那只鬼图标：RecyclerView 认为它还在动画，于是既不回收也不重新
        // 布局，它带着动画**起始**位移停在旧槽位、作为残留子 View 画在新挂上来的图标下面；
        // 触摸照样能落到它身上（按压覆层会亮），但所有命中测试都按 bindingAdapterPosition
        // 过滤，NO_POSITION 的它对点击和拖拽完全隐形。
        // 回归测试：NativeStableKeyItemAnimatorTest。
        if (activeMoves.isEmpty() && pendingMoves.isEmpty() && batchAnimator != null) {
            stopBatchAnimator()
            activeFrameMoves = emptyArray()
        }
        dispatchFinishedWhenIdle()
    }

    override fun endAnimations() {
        pendingMoves.keys.toList().forEach { holder ->
            pendingMoves.remove(holder)
            reset(holder)
            dispatchAnimationFinished(holder)
        }
        stopBatchAnimator()
        activeMoves.keys.toList().forEach { holder ->
            activeMoves.remove(holder)?.active = false
            reset(holder)
            dispatchAnimationFinished(holder)
        }
        activeFrameMoves = emptyArray()
        onMoveFrame()
        dispatchFinishedWhenIdle()
    }

    override fun isRunning(): Boolean =
        pendingMoves.isNotEmpty() || activeMoves.isNotEmpty() || batchAnimator != null

    override fun canReuseUpdatedViewHolder(viewHolder: RecyclerView.ViewHolder): Boolean = true

    private fun finishActiveBatch() {
        activeMoves.keys.toList().forEach { holder ->
            activeMoves.remove(holder)?.active = false
            reset(holder)
            dispatchAnimationFinished(holder)
        }
        activeFrameMoves = emptyArray()
        onMoveFrame()
        dispatchFinishedWhenIdle()
    }

    private fun stopBatchAnimator() {
        batchAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        batchAnimator = null
        // 兜底：摘掉监听之后 onAnimationEnd 不会再跑，谁也不会来接 pendingMoves 了。
        // 现在的两个调用点都保证此时它是空的（endAnimation 会检查、endAnimations 先冲
        // 干净），但这一族 bug 的代价是「一只永远点不动的鬼图标」，所以宁可在这里再收
        // 一次尾：任何以后新增的调用点都不会重新打开这个洞。
        finishPendingMoves()
    }

    /** 把待启动的位移直接当作完成上报。少一次位移动画是小事，漏一次上报是鬼图标。 */
    private fun finishPendingMoves() {
        if (pendingMoves.isEmpty()) return
        pendingMoves.keys.toList().forEach { holder ->
            pendingMoves.remove(holder)?.active = false
            reset(holder)
            dispatchAnimationFinished(holder)
        }
        onMoveFrame()
    }

    private fun reset(holder: RecyclerView.ViewHolder) {
        setMoveTranslation(holder, 0f, 0f)
    }

    private fun setMoveTranslation(holder: RecyclerView.ViewHolder, x: Float, y: Float) {
        val view = holder.itemView
        if (view is NativeAppListRowView) {
            view.setMoveTranslation(x, y)
        } else {
            view.translationX = x
            view.translationY = y
        }
    }

    private fun dispatchFinishedWhenIdle() {
        if (!isRunning) dispatchAnimationsFinished()
    }
}
