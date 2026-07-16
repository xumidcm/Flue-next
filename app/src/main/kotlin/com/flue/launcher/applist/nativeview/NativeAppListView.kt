package com.flue.launcher.applist.nativeview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
    private var draggedViewOriginalElevation = 0f
    private var folderHoverKey: AppItemKey? = null
    private var autoScrollRemainder = 0f

    var onCommand: ((AppListCommand) -> Unit)? = null

    init {
        setHasFixedSize(true)
        layoutManager = activeLayoutStrategy.createLayoutManager(context)
        itemAnimator = null
        clipToPadding = false
        adapter = nativeAdapter
        addOnScrollListener(childVisualScrollListener)
        addOnChildAttachStateChangeListener(childVisualAttachListener)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        updateViewportPadding(bottom - top)
        super.onLayout(changed, left, top, right, bottom)
        applyAttachedChildVisuals()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (dragController.isDragging) return true
        return scrollController.onGenericMotionEvent(event) ||
            super.onGenericMotionEvent(event)
    }

    override fun onDetachedFromWindow() {
        cancelActiveDrag()
        super.onDetachedFromWindow()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (released) return false
        if (dragTouchOwned) return true
        val consume = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragController.onDown(event.x, event.y, event.eventTime)
                false
            }
            MotionEvent.ACTION_MOVE ->
                dragController.onMove(event.x, event.y, event.eventTime)
            MotionEvent.ACTION_UP ->
                dragController.onUp(event.x, event.y, event.eventTime)
            MotionEvent.ACTION_CANCEL -> dragController.onCancel()
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
            MotionEvent.ACTION_MOVE ->
                dragController.onMove(event.x, event.y, event.eventTime)
            MotionEvent.ACTION_UP -> {
                dragController.onUp(event.x, event.y, event.eventTime)
                finishOwnedTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragController.onCancel()
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
        nativeAdapter.setConfig(next)
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
        dragController.release()
        stopScroll()
        removeOnScrollListener(childVisualScrollListener)
        removeOnChildAttachStateChangeListener(childVisualAttachListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
        onCommand = null
        nativeAdapter.clearSynchronously()
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
        interceptedEndsGesture = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun cancelActiveDrag() {
        dragController.onCancel()
        if (dragTouchOwned) finishOwnedTouch()
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
            val child = holderForKey(key)?.itemView ?: return
            draggedView = child
            draggedViewTouchOffsetY = pointerY - (child.top + child.translationY)
            draggedViewOriginalElevation = ViewCompat.getElevation(child)
            ViewCompat.setElevation(child, resources.displayMetrics.density * 12f)
            child.isPressed = false
        }

        override fun updateDrag(pointerY: Float) {
            val child = draggedView ?: return
            child.translationY = pointerY - draggedViewTouchOffsetY - child.top
        }

        override fun endDrag() {
            draggedView?.let { child ->
                child.translationY = 0f
                ViewCompat.setElevation(child, draggedViewOriginalElevation)
                applyChildVisual(child)
            }
            draggedView = null
            draggedViewTouchOffsetY = 0f
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
        val visual = nativeLinearChildVisual(
            childCenterY = child.top + child.height / 2f + child.translationY,
            viewportHeight = height,
            config = currentConfig.value,
            output = childVisualScratch,
        )
        val centerPivotX = child.width / 2f
        val centerPivotY = child.height / 2f
        if (child.pivotX != centerPivotX) child.pivotX = centerPivotX
        if (child.pivotY != centerPivotY) child.pivotY = centerPivotY
        if (child.scaleX != visual.scale) child.scaleX = visual.scale
        if (child.scaleY != visual.scale) child.scaleY = visual.scale
        if (child.alpha != visual.alpha) child.alpha = visual.alpha
        (child as? NativeAppListRowView)?.setIconBlurRadiusPx(
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
        (child as? NativeAppListRowView)?.setIconBlurRadiusPx(0f, NativeBlurTier.OFF)
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
}
