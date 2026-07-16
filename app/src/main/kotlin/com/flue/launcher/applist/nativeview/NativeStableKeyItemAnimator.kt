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
        viewHolder.itemView.translationX = deltaX
        viewHolder.itemView.translationY = deltaY
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
                        move.holder.itemView.translationX = move.startTranslationX * remaining
                        move.holder.itemView.translationY = move.startTranslationY * remaining
                    }
                    index++
                }
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
            dispatchAnimationFinished(item)
        }
        if (activeMoves.isEmpty() && batchAnimator != null) {
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
        dispatchFinishedWhenIdle()
    }

    private fun stopBatchAnimator() {
        batchAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        batchAnimator = null
    }

    private fun reset(holder: RecyclerView.ViewHolder) {
        holder.itemView.translationX = 0f
        holder.itemView.translationY = 0f
    }

    private fun dispatchFinishedWhenIdle() {
        if (!isRunning) dispatchAnimationsFinished()
    }
}
