package com.flue.launcher.applist.nativeview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.os.Build
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppItemKey
import kotlin.math.roundToInt

class NativeAppListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private val contentVersionGate = NativeAppListContentVersionGate()
    private var currentConfig =
        NativeAppListResolvedConfig.from(NativeAppListConfig())
    private val nativeAdapter = NativeAppListAdapter(::dispatchCommand, currentConfig)
    private val scrollController = NativeAppListScrollController(this) {
        currentConfig.value
    }
    private val dragScheduler = AndroidNativeDragScheduler(this)
    private val dragHost = NativeDragHost()
    private val dragController = NativeAppListDragController(
        host = dragHost,
        scheduler = dragScheduler,
        policy = NativeDragPolicy(
            startThresholdPx = 20f * resources.displayMetrics.density,
            autoScrollEdgePx = 84f * resources.displayMetrics.density,
            maxAutoScrollPxPerFrame = 30f,
            folderHoverMillis = 500L,
            folderHoverMaxSpeedPxPerMs = 0.55f,
            longPressMillis = 620L,
        ),
        allowFolderCreation = { currentConfig.value.allowFolderCreation },
    )
    private var activeLayoutStrategy: NativeAppListLayoutStrategy =
        LinearNativeAppListLayoutStrategy
    private val childVisualScratch = NativeLinearChildVisual(scale = 1f, alpha = 1f)
    private val childVisualScrollListener = object : OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            applyAttachedChildVisuals()
        }
    }
    private val childVisualAttachListener = object : OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            applyChildVisual(view)
            val key = (getChildViewHolder(view) as?
                NativeAppListAdapter.AppItemViewHolder)?.boundKeyOrNull()
            (view as? NativeAppListRowView)?.setFolderHoverState(key == folderHoverKey)
        }

        override fun onChildViewDetachedFromWindow(view: View) {
            if (view === draggedView) cancelActiveDrag()
            (view as? NativeAppListRowView)?.setFolderHoverState(false)
        }
    }
    private var released = false
    private var dragTouchOwned = false
    private var interceptedAction = -1
    private var interceptedEventTime = Long.MIN_VALUE
    private var interceptedEndsGesture = false
    private var draggedView: View? = null
    private var draggedViewTouchOffsetY = 0f
    private var draggedPointerY = 0f
    private var draggedViewOriginalElevation = 0f
    private var folderHoverKey: AppItemKey? = null
    private var autoScrollRemainder = 0f
    private val topFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var topFadeHeight = 0f
    private var bottomFadeHeight = 0f
    private val pullToFaceState = NativePullToFaceState(
        triggerThresholdPx = 72f * resources.displayMetrics.density,
        maxOffsetPx = resources.displayMetrics.heightPixels.coerceAtLeast(1) / 2f,
    )
    private var pullOffsetPx = 0f
    private var pullTouchOwned = false
    private var pullSettleAnimator: ValueAnimator? = null

    var onCommand: ((AppListCommand) -> Unit)? = null
    var onScrollToTop: (() -> Unit)? = null

    init {
        setHasFixedSize(true)
        layoutManager = activeLayoutStrategy.createLayoutManager(context)
        itemAnimator = NativeStableKeyItemAnimator(
            moveDurationMillis = 180L,
            shouldAnimateHolder = { holder -> holder.itemView !== draggedView },
        )
        clipToPadding = false
        adapter = nativeAdapter
        addOnScrollListener(childVisualScrollListener)
        addOnChildAttachStateChangeListener(childVisualAttachListener)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        updateViewportPadding(bottom - top)
        super.onLayout(changed, left, top, right, bottom)
        if (draggedView != null) dragHost.updateDrag(draggedPointerY)
        applyAttachedChildVisuals()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateEdgeFadeShaders()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val saveCount = canvas.save()
        if (pullOffsetPx != 0f) canvas.translate(0f, pullOffsetPx)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saveCount)
        if (!currentConfig.value.active && currentConfig.value.entryProgress <= 0.001f) return
        if (topFadeHeight > 0f) canvas.drawRect(0f, 0f, width.toFloat(), topFadeHeight, topFadePaint)
        if (bottomFadeHeight > 0f) {
            canvas.drawRect(
                0f,
                height - bottomFadeHeight,
                width.toFloat(),
                height.toFloat(),
                bottomFadePaint,
            )
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!currentConfig.value.active) return false
        if (dragController.isDragging) return true
        return scrollController.onGenericMotionEvent(event) ||
            super.onGenericMotionEvent(event)
    }

    override fun onDetachedFromWindow() {
        cancelActiveDrag()
        cancelPull()
        super.onDetachedFromWindow()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (released || !currentConfig.value.active) return false
        if (dragTouchOwned) return true
        val consume = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPullSettle()
                pullToFaceState.onDown(event.y)
                dragController.onDown(event.x, event.y, event.eventTime)
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val dragConsumed = dragController.onMove(event.x, event.y, event.eventTime)
                if (dragConsumed) {
                    pullToFaceState.cancel()
                    true
                } else {
                    val offset = pullToFaceState.onMove(
                        pointerY = event.y,
                        atTop = !canScrollVertically(-1),
                        blocked = dragController.isDragging,
                    )
                    if (offset > 0f) {
                        dragController.onCancel()
                        pullTouchOwned = true
                        setPullOffset(offset)
                        stopScroll()
                        true
                    } else {
                        false
                    }
                }
            }
            MotionEvent.ACTION_UP -> if (pullToFaceState.isPulling) {
                releasePull()
                true
            } else {
                dragController.onUp(event.x, event.y, event.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> if (pullToFaceState.isPulling) {
                cancelPull()
                true
            } else {
                dragController.onCancel()
            }
            else -> false
        }
        if (consume) {
            dragTouchOwned = true
            interceptedAction = event.actionMasked
            interceptedEventTime = event.eventTime
            interceptedEndsGesture =
                event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragTouchOwned) return super.onTouchEvent(event)
        val alreadyHandled =
            event.actionMasked == interceptedAction &&
                event.eventTime == interceptedEventTime
        if (alreadyHandled) {
            interceptedAction = -1
            interceptedEventTime = Long.MIN_VALUE
            if (interceptedEndsGesture) finishOwnedTouch()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (pullTouchOwned) {
                setPullOffset(
                    pullToFaceState.onMove(
                        pointerY = event.y,
                        atTop = !canScrollVertically(-1),
                        blocked = false,
                    ),
                )
            } else {
                dragController.onMove(event.x, event.y, event.eventTime)
            }
            MotionEvent.ACTION_UP -> {
                if (pullTouchOwned) releasePull()
                else dragController.onUp(event.x, event.y, event.eventTime)
                finishOwnedTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pullTouchOwned) cancelPull()
                else dragController.onCancel()
                finishOwnedTouch()
            }
        }
        return true
    }

    fun submit(content: NativeAppListContent): Boolean {
        if (
            released ||
            !contentVersionGate.tryAccept(
                snapshotGeneration = content.snapshot.generation,
                iconRevision = content.iconRevision,
            )
        ) {
            return false
        }
        nativeAdapter.submitContent(content, dragController::reconcileCurrentKeys)
        return true
    }

    fun setConfig(config: NativeAppListConfig) {
        if (released) return
        val next = NativeAppListResolvedConfig.from(config)
        if (currentConfig.value == next.value) return
        val resetInitialScroll =
            currentConfig.value.initialResetToken != next.value.initialResetToken
        if (resetInitialScroll) cancelActiveDrag()
        currentConfig = next
        if (!next.value.active) {
            cancelActiveDrag()
            cancelPull()
        }
        nativeAdapter.setConfig(next)
        updateEdgeFadeShaders()
        applyContentOverlayBlur()
        updateViewportPadding(height)
        applyAttachedChildVisuals()
        if (resetInitialScroll) {
            stopScroll()
            scrollToPosition(0)
        }
    }

    fun setLayoutStrategy(strategy: NativeAppListLayoutStrategy) {
        if (
            released ||
            !shouldReplaceLayoutStrategy(
                currentSemanticKey = activeLayoutStrategy.semanticKey,
                nextSemanticKey = strategy.semanticKey,
            )
        ) {
            return
        }
        cancelActiveDrag()
        stopScroll()
        itemAnimator?.endAnimations()
        resetAttachedChildVisuals()
        val previousLayoutManager = layoutManager
        val savedLayoutState = previousLayoutManager?.onSaveInstanceState()
        val nextLayoutManager = strategy.createLayoutManager(context)
        activeLayoutStrategy = strategy
        layoutManager = nextLayoutManager
        if (
            savedLayoutState != null &&
            previousLayoutManager.javaClass == nextLayoutManager.javaClass
        ) {
            nextLayoutManager.onRestoreInstanceState(savedLayoutState)
        }
        applyAttachedChildVisuals()
    }

    fun release() {
        if (released) return
        released = true
        cancelPull()
        dragController.release()
        stopScroll()
        removeOnScrollListener(childVisualScrollListener)
        removeOnChildAttachStateChangeListener(childVisualAttachListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
        onCommand = null
        onScrollToTop = null
        nativeAdapter.clearSynchronously()
        itemAnimator?.endAnimations()
        itemAnimator = null
        adapter = null
        layoutManager = null
        recycledViewPool.clear()
        clearOnScrollListeners()
        clearOnChildAttachStateChangeListeners()
    }

    private fun dispatchCommand(command: AppListCommand) {
        if (!released) {
            onCommand?.invoke(command)
        }
    }

    private fun finishOwnedTouch() {
        dragTouchOwned = false
        pullTouchOwned = false
        interceptedEndsGesture = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun cancelActiveDrag() {
        dragController.onCancel()
        if (dragTouchOwned) finishOwnedTouch()
    }

    private fun releasePull() {
        val release = pullToFaceState.onUp()
        if (release.triggerFace) onScrollToTop?.invoke()
        animatePullToRest(release.releaseOffsetPx)
    }

    private fun cancelPull() {
        pullToFaceState.cancel()
        pullTouchOwned = false
        cancelPullSettle()
        setPullOffset(0f)
    }

    private fun animatePullToRest(startOffset: Float) {
        cancelPullSettle()
        if (startOffset <= 0f || released) {
            setPullOffset(0f)
            return
        }
        pullSettleAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = 220L
            addUpdateListener { animator -> setPullOffset(animator.animatedValue as Float) }
            start()
        }
    }

    private fun cancelPullSettle() {
        pullSettleAnimator?.cancel()
        pullSettleAnimator = null
    }

    private fun setPullOffset(offset: Float) {
        val next = offset.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        if (pullOffsetPx == next) return
        pullOffsetPx = next
        invalidate()
    }

    private fun holderForKey(key: AppItemKey): NativeAppListAdapter.AppItemViewHolder? {
        for (index in 0 until childCount) {
            val holder = getChildViewHolder(getChildAt(index)) as?
                NativeAppListAdapter.AppItemViewHolder ?: continue
            if (holder.boundKeyOrNull() == key) return holder
        }
        return null
    }

    private fun keyAtPoint(x: Float, y: Float): AppItemKey? {
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index)
            if (child === draggedView) continue
            val pivotX = child.left + child.pivotX + child.translationX
            val pivotY = child.top + child.pivotY + child.translationY
            val left = pivotX + (child.left - (child.left + child.pivotX)) * child.scaleX
            val right = pivotX + (child.right - (child.left + child.pivotX)) * child.scaleX
            val top = pivotY + (child.top - (child.top + child.pivotY)) * child.scaleY
            val bottom = pivotY + (child.bottom - (child.top + child.pivotY)) * child.scaleY
            if (x in left..right && y in top..bottom) {
                return (getChildViewHolder(child) as?
                    NativeAppListAdapter.AppItemViewHolder)?.boundKeyOrNull()
            }
        }
        return null
    }

    private inner class NativeDragHost : NativeAppListDragHost {
        override val viewportHeightPx: Float
            get() = height.toFloat()

        override fun keyAt(x: Float, y: Float): AppItemKey? = keyAtPoint(x, y)

        override fun currentKeys(): List<AppItemKey> = nativeAdapter.currentKeysSnapshot()

        override fun dispatch(command: AppListCommand) = dispatchCommand(command)

        override fun suppressLongClick(key: AppItemKey, suppress: Boolean) {
            holderForKey(key)?.itemView?.isLongClickable = !suppress
        }

        override fun beginDrag(key: AppItemKey, pointerY: Float) {
            val holder = holderForKey(key) ?: return
            itemAnimator?.endAnimation(holder)
            val child = holder.itemView
            draggedView = child
            draggedViewTouchOffsetY = pointerY - (child.top + child.translationY)
            draggedPointerY = pointerY
            draggedViewOriginalElevation = ViewCompat.getElevation(child)
            ViewCompat.setElevation(
                child,
                if (currentConfig.value.iconShadowEnabled) {
                    resources.displayMetrics.density * 18f
                } else {
                    0f
                },
            )
            child.isPressed = false
            (child as? NativeAppListRowView)?.setDragVisualState(true)
        }

        override fun updateDrag(pointerY: Float) {
            val child = draggedView ?: return
            draggedPointerY = pointerY
            val offset = pointerY - draggedViewTouchOffsetY - child.top
            if (child is NativeAppListRowView) {
                child.setDragTranslationY(offset)
                child.setFlowTranslationY(0f)
            } else {
                child.translationY = offset
            }
        }

        override fun endDrag() {
            draggedView?.let { child ->
                if (child is NativeAppListRowView) {
                    child.setDragTranslationY(0f)
                    child.setDragVisualState(false)
                } else {
                    child.translationY = 0f
                }
                ViewCompat.setElevation(child, draggedViewOriginalElevation)
                applyChildVisual(child)
            }
            draggedView = null
            draggedViewTouchOffsetY = 0f
            draggedPointerY = 0f
            draggedViewOriginalElevation = 0f
            autoScrollRemainder = 0f
        }

        override fun setFolderHover(key: AppItemKey?) {
            if (folderHoverKey == key) return
            folderHoverKey?.let { previous ->
                (holderForKey(previous)?.itemView as? NativeAppListRowView)
                    ?.setFolderHoverState(false)
            }
            folderHoverKey = key
            key?.let { next ->
                (holderForKey(next)?.itemView as? NativeAppListRowView)
                    ?.setFolderHoverState(true)
            }
        }

        override fun scrollBy(deltaPx: Float) {
            autoScrollRemainder += deltaPx
            val wholePixels = autoScrollRemainder.roundToInt()
            if (wholePixels == 0) return
            autoScrollRemainder -= wholePixels
            this@NativeAppListView.scrollBy(0, wholePixels)
        }

        override fun performDragHaptic() {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun applyAttachedChildVisuals() {
        if (released || height <= 0) return
        for (index in 0 until childCount) {
            applyChildVisual(getChildAt(index))
        }
    }

    private fun applyChildVisual(child: View) {
        if (activeLayoutStrategy.semanticKey != LinearNativeAppListLayoutStrategy.semanticKey) {
            return
        }
        val rowView = child as? NativeAppListRowView
        val positionTranslationY =
            rowView?.positionTranslationYWithoutFlow() ?: child.translationY
        val baseCenterY = child.top + child.height / 2f + positionTranslationY
        val flowOffset = nativeFastFlowOffset(
            rowCenterY = baseCenterY,
            viewportHeight = height.toFloat(),
            enabled = currentConfig.value.fastFlowAnimationEnabled && !dragController.isDragging,
        )
        rowView?.setFlowTranslationY(flowOffset)
        val visual = nativeLinearChildVisual(
            childCenterY = baseCenterY,
            viewportHeight = height,
            config = currentConfig.value,
            output = childVisualScratch,
            edgeCenterY = baseCenterY + flowOffset,
        )
        val centerPivotX = child.width / 2f
        val centerPivotY = child.height / 2f
        if (child.pivotX != centerPivotX) child.pivotX = centerPivotX
        if (child.pivotY != centerPivotY) child.pivotY = centerPivotY
        if (rowView != null) {
            rowView.setVisualScale(visual.scale)
        } else {
            if (child.scaleX != visual.scale) child.scaleX = visual.scale
            if (child.scaleY != visual.scale) child.scaleY = visual.scale
        }
        if (child.alpha != visual.alpha) child.alpha = visual.alpha
        rowView?.setIconBlurRadiusPx(
            radiusPx = visual.blurRadiusPx,
            tier = currentConfig.value.blurTier,
        )
    }

    private fun resetAttachedChildVisuals() {
        for (index in 0 until childCount) {
            resetChildVisual(getChildAt(index))
        }
    }

    private fun resetChildVisual(child: View) {
        if (child.scaleX != 1f) child.scaleX = 1f
        if (child.scaleY != 1f) child.scaleY = 1f
        if (child.alpha != 1f) child.alpha = 1f
        (child as? NativeAppListRowView)?.apply {
            setVisualScale(1f)
            setFlowTranslationY(0f)
            setIconBlurRadiusPx(0f, NativeBlurTier.OFF)
        }
    }

    private fun updateViewportPadding(viewportHeight: Int) {
        val verticalPadding = if (currentConfig.value.centerEdgeItems) {
            ((viewportHeight - currentConfig.value.rowHeightPx) / 2).coerceAtLeast(0)
        } else {
            0
        }
        if (paddingTop != verticalPadding || paddingBottom != verticalPadding) {
            setPadding(0, verticalPadding, 0, verticalPadding)
        }
    }

    private fun applyContentOverlayBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val bucket = if (currentConfig.value.blurTier == NativeBlurTier.SYSTEM) {
            quantizeNativeBlurRadiusPx(currentConfig.value.contentOverlayBlurRadiusPx)
        } else {
            0
        }
        setRenderEffect(bucket.takeIf { it > 0 }?.let(NativeSystemBlurEffectCache::get))
    }

    private fun updateEdgeFadeShaders() {
        val viewportHeight = height.toFloat().coerceAtLeast(0f)
        topFadeHeight = currentConfig.value.topFadeRangePx.coerceIn(0f, viewportHeight)
        bottomFadeHeight = currentConfig.value.bottomFadeRangePx.coerceIn(0f, viewportHeight)
        val color = currentConfig.value.palette.fadeEdgeColorArgb.toInt()
        topFadePaint.shader = if (topFadeHeight > 0f) {
            LinearGradient(
                0f,
                0f,
                0f,
                topFadeHeight,
                intArrayOf(color.withAlphaFraction(0.78f), color.withAlphaFraction(0.28f), color.withAlphaFraction(0f)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else null
        bottomFadePaint.shader = if (bottomFadeHeight > 0f) {
            LinearGradient(
                0f,
                viewportHeight - bottomFadeHeight,
                0f,
                viewportHeight,
                intArrayOf(color.withAlphaFraction(0f), color.withAlphaFraction(0.28f), color.withAlphaFraction(0.82f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else null
        invalidate()
    }
}

private fun Int.withAlphaFraction(alpha: Float): Int =
    (this and 0x00ffffff) or ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)
