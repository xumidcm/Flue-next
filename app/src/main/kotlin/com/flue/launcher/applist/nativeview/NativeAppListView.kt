package com.flue.launcher.applist.nativeview

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.os.Build
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppItemKey
import kotlin.math.abs
import kotlin.math.roundToInt

private const val NATIVE_DRAG_SETTLE_DURATION_MS = 130L
private const val NATIVE_DRAG_PREVIEW_DURATION_MS = 150L
private val NATIVE_DRAG_SETTLE_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)

private data class NativeDragPreviewMove(
    val row: NativeAppListRowView,
    val startX: Float,
    val startY: Float,
    val targetX: Float,
    val targetY: Float,
)

private data class NativeDragPreviewSlot(
    val centerX: Float,
    val centerY: Float,
)

class NativeAppListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private val contentVersionGate = NativeAppListContentVersionGate()
    private var currentConfig =
        NativeAppListResolvedConfig.from(NativeAppListConfig())
    private val nativeAdapter = NativeAppListAdapter(
        commandSink = ::dispatchCommand,
        initialConfig = currentConfig,
        onListCommitted = ::onNativeAdapterListCommitted,
        onDragPreviewOrderChanged = ::onDragPreviewOrderChanged,
        onDragPreviewFinished = ::onDragPreviewFinished,
    )
    private val scrollController = NativeAppListScrollController(this) {
        currentConfig.value
    }
    private val dragScheduler = AndroidNativeDragScheduler(this)
    private val dragHost = NativeDragHost()
    private val dragPolicyProfiles = NativeDragPolicyProfiles(resources.displayMetrics.density)
    private val dragController = NativeAppListDragController(
        host = dragHost,
        scheduler = dragScheduler,
        policyProvider = {
            dragPolicyProfiles.forPresentation(currentConfig.value.presentation)
        },
        allowFolderCreation = { currentConfig.value.allowFolderCreation },
    )
    private var activeLayoutStrategy: NativeAppListLayoutStrategy =
        LinearNativeAppListLayoutStrategy
    private val childVisualScratch = NativeLinearChildVisual(scale = 1f, alpha = 1f)
    private val roundGeometryScratch = NativeSpatialChildVisual()
    private val roundGeometryAdapter = NativeDrawerGeometryAdapter()
    private var entryAnimator: ValueAnimator? = null
    private var entryAnimationGeneration = 0L
    private var motionBlurSuppressed = false
    private val restoreMotionBlur = Runnable {
        if (!released && scrollState == SCROLL_STATE_IDLE) {
            setMotionBlurSuppressed(false)
        }
    }
    private val childVisualScrollListener = object : OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            applyAttachedChildVisuals()
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            removeCallbacks(restoreMotionBlur)
            if (newState != SCROLL_STATE_DRAGGING) clearGlidePressed()
            if (newState == SCROLL_STATE_IDLE) {
                // Avoid reattaching multiple RenderEffects on the exact frame
                // a fling settles; let the final composite frame land first.
                postDelayed(restoreMotionBlur, 72L)
            } else {
                setMotionBlurSuppressed(true)
            }
        }
    }
    private val childVisualAttachListener = object : OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            (view as? NativeAppListRowView)?.applyConfig(currentConfig)
            (view as? NativeAppListRowView)?.setContentTranslationY(pullOffsetPx)
            applyChildVisual(view)
            val key = (getChildViewHolder(view) as?
                NativeAppListAdapter.AppItemViewHolder)?.boundKeyOrNull()
            (view as? NativeAppListRowView)?.setFolderHoverState(key == folderHoverKey)
            (view as? NativeAppListRowView)?.setGlidePressedState(key == glidePressedKey)
            if (key == draggedSourceKey) view.alpha = 0f
            if (dragPreviewOrder.isNotEmpty()) {
                postOnAnimation { applyDragPreviewTargets(animate = false) }
            }
        }

        override fun onChildViewDetachedFromWindow(view: View) {
            (view as? NativeAppListRowView)?.setFolderHoverState(false)
            (view as? NativeAppListRowView)?.setGlidePressedState(false)
        }
    }
    private var released = false
    private var dragTouchOwned = false
    private var interceptedAction = -1
    private var interceptedEventTime = Long.MIN_VALUE
    /** True until RecyclerView sees the real UP/CANCEL after a drag handoff. */
    private var recyclerGestureNeedsTerminal = false
    private var edgeTerminalAction = -1
    private var edgeTerminalEventTime = Long.MIN_VALUE
    private var gesturePointerId = MotionEvent.INVALID_POINTER_ID
    private val legacyEdgeHandoffEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    private val legacyEdgePointer = NativeLegacyEdgePointerState()
    private val viewConfiguration = ViewConfiguration.get(context)
    private var legacyPreparedAction = -1
    private var legacyPreparedEventTime = Long.MIN_VALUE
    private var legacyPreparedPointerId = MotionEvent.INVALID_POINTER_ID
    private var legacyPreparedPhysicalY = Float.NaN
    private var legacyPreparedOffsetY = 0f
    private var legacyEdgeHandoffOccurred = false
    private var legacyEdgeRemainderForwarded = false
    private val legacyPointerVelocity = NativeLegacyPointerVelocityState()
    private var legacyPendingFlingVelocityY: Int? = null
    private var legacyFlingObserved = false
    private var draggedSourceKey: AppItemKey? = null
    private var dragOverlayView: NativeAppListRowView? = null
    private var draggedViewTouchOffsetY = 0f
    private var draggedViewTouchOffsetX = 0f
    private var draggedPointerY = 0f
    private var draggedPointerX = 0f
    private var folderHoverKey: AppItemKey? = null
    private var glidePressedKey: AppItemKey? = null
    private var autoScrollRemainder = 0f
    private var folderSettleCenterX = Float.NaN
    private var folderSettleCenterY = Float.NaN
    private var dragSettleGeneration = 0L
    private var dragPreviewAnimator: ValueAnimator? = null
    private var dragPreviewAnimationGeneration = 0L
    private var dragPreviewOriginalOrder: List<AppItemKey> = emptyList()
    private var dragPreviewOrder: List<AppItemKey> = emptyList()
    private val topFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var topFadeHeight = 0f
    private var bottomFadeHeight = 0f
    private var pullOffsetPx = 0f
    private val springEdgeEffects = FlueSpringEdgeEffectFactory(
        owner = this,
        onOffsetChanged = ::setPullOffset,
    )

    /** Returns true when the command owner accepted the request. */
    var onCommand: ((AppListCommand) -> Boolean)? = null
    var onScrollToTop: (() -> Unit)? = null

    private fun onNativeAdapterListCommitted(itemCount: Int) {
        (layoutManager as? NativeSpatialAppListLayoutManager)
            ?.onAdapterItemCountCommitted(itemCount)
    }

    private fun onDragPreviewOrderChanged(
        originalOrder: List<AppItemKey>,
        previewOrder: List<AppItemKey>,
    ) {
        if (released) return
        dragPreviewOriginalOrder = originalOrder.toList()
        dragPreviewOrder = previewOrder.toList()
        applyDragPreviewTargets(animate = true)
    }

    private fun onDragPreviewFinished(committed: Boolean) {
        if (released) return
        dragPreviewOriginalOrder = emptyList()
        dragPreviewOrder = emptyList()
        if (committed) {
            // Diff dispatch and layout share the upcoming frame. Clearing the
            // virtual offsets there lets the real stable-key slots take over
            // without drawing an intermediate reverse jump.
            postOnAnimation { clearDragPreviewTranslations(animate = false) }
        } else {
            clearDragPreviewTranslations(animate = true)
        }
    }

    init {
        setHasFixedSize(true)
        // RecyclerView owns scroll, velocity and boundary consumption.  Its
        // unconsumed distance/velocity is handed to our EdgeEffect in the same
        // frame instead of being replayed through a second gesture state
        // machine.
        overScrollMode = View.OVER_SCROLL_ALWAYS
        edgeEffectFactory = springEdgeEffects
        isNestedScrollingEnabled = false
        layoutManager = activeLayoutStrategy.createLayoutManager(context) { currentConfig.value }
        itemAnimator = NativeStableKeyItemAnimator(
            moveDurationMillis = 180L,
            shouldAnimateHolder = { holder ->
                (holder as? NativeAppListAdapter.AppItemViewHolder)
                    ?.boundKeyOrNull() != draggedSourceKey
            },
            onMoveFrame = ::applyMoveFrameVisuals,
        )
        clipToPadding = false
        adapter = nativeAdapter
        addOnScrollListener(childVisualScrollListener)
        addOnChildAttachStateChangeListener(childVisualAttachListener)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        springEdgeEffects.updateViewport(bottom - top)
        updateViewportPadding(bottom - top)
        super.onLayout(changed, left, top, right, bottom)
        if (dragOverlayView != null && dragController.isDragging) {
            dragHost.updateDrag(draggedPointerX, draggedPointerY)
        }
        applyAttachedChildVisuals()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateEdgeFadeShaders()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!currentConfig.value.drawEdgeFadeMask) return
        // Keep the mask in the RecyclerView display list even while the scene
        // is inactive. The parent layer owns transition alpha/scale; toggling
        // these rectangles with `active` made the top edge flash on launch and
        // return because the mask disappeared one frame before its children.
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

    override fun fling(velocityX: Int, velocityY: Int): Boolean {
        val legacyVelocityY = legacyPendingFlingVelocityY
        if (legacyVelocityY != null) {
            legacyFlingObserved = true
            return super.fling(velocityX, legacyVelocityY)
        }
        return super.fling(velocityX, velocityY)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        updateGesturePointer(event)
        prepareLegacyEdgePointer(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // A pointer-down can be delivered to the currently touched row
            // without another parent interception pass. Cancel at the outer
            // boundary so the delayed long-press callback cannot fire later.
            dragController.onAdditionalPointer()
        }
        val handled = super.dispatchTouchEvent(event)
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            dragController.finishAdditionalPointer()
            resetLegacyEdgeGesture()
        }
        return handled
    }

    override fun onDetachedFromWindow() {
        cancelActiveDrag()
        cancelDragSettle(removeOverlay = true)
        springEdgeEffects.finish()
        finishOwnedTouch(scheduleMotionBlurRestore = false)
        removeCallbacks(restoreMotionBlur)
        cancelEntryAnimation(resetProgress = true)
        super.onDetachedFromWindow()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (released || !currentConfig.value.active) return false
        updateGesturePointer(event)
        prepareLegacyEdgePointer(event)
        observeEdgeGesture(event)
        if (dragTouchOwned) return true

        val dragConsumed = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Interrupt a running rebound at its current visual position.
                // RecyclerView still receives this original DOWN and remains
                // the sole owner of touch-slop and velocity tracking.
                recyclerGestureNeedsTerminal = true
                clearGlidePressed()
                dragController.onDown(activePointerX(event), activePointerY(event), event.eventTime)
                false
            }
            MotionEvent.ACTION_MOVE -> {
                dragController.onMove(
                    activePointerX(event),
                    activePointerY(event),
                    event.eventTime,
                )
            }
            MotionEvent.ACTION_UP -> dragController.onUp(
                    activePointerX(event),
                    activePointerY(event),
                    event.eventTime,
                )
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A watch drag never hands ownership to a second finger.
                dragController.onAdditionalPointer()
                false
            }
            MotionEvent.ACTION_CANCEL -> dragController.onCancel()
            else -> false
        }

        if (dragConsumed) {
            stopScroll()
            clearGlidePressed()
            dragTouchOwned = true
            interceptedAction = event.actionMasked
            interceptedEventTime = event.eventTime
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        val forwarded = withLegacyForwardedPointer(event) {
            super.onInterceptTouchEvent(event)
        }
        if (forwarded && event.actionMasked == MotionEvent.ACTION_MOVE) {
            updateGlidePressed(activePointerX(event), activePointerY(event))
        }
        if (
            forwarded &&
            dragController.isActive
        ) {
            // RecyclerView won touch-slop, so the delayed long-press recognizer
            // must be cancelled on the exact hand-off frame.
            dragController.onCancel()
        }
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            clearGlidePressed()
            recyclerGestureNeedsTerminal = false
            clearGesturePointer()
        }
        return forwarded
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        prepareLegacyEdgePointer(event)
        observeEdgeGesture(event)
        val alreadyHandled =
            event.actionMasked == interceptedAction &&
                event.eventTime == interceptedEventTime
        if (alreadyHandled) {
            interceptedAction = -1
            interceptedEventTime = Long.MIN_VALUE
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                finishRecyclerGesture(event)
                finishOwnedTouch()
            }
            return true
        }

        if (dragTouchOwned) {
            clearGlidePressed()
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Once this ViewGroup has intercepted the stream,
                    // subsequent pointer-down events are delivered directly
                    // here rather than through onInterceptTouchEvent. Cancel
                    // the stable-key drag but keep owning the stream until
                    // its real UP/CANCEL closes RecyclerView's touch state.
                    dragController.onAdditionalPointer()
                }
                MotionEvent.ACTION_MOVE ->
                    dragController.onMove(
                        activePointerX(event),
                        activePointerY(event),
                        event.eventTime,
                    )

                MotionEvent.ACTION_UP -> {
                    dragController.onUp(
                        activePointerX(event),
                        activePointerY(event),
                        event.eventTime,
                    )
                    finishRecyclerGesture(event)
                    dragController.finishAdditionalPointer()
                    finishOwnedTouch()
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragController.onCancel()
                    finishRecyclerGesture(event)
                    dragController.finishAdditionalPointer()
                    finishOwnedTouch()
                }
                MotionEvent.ACTION_POINTER_UP -> updateGesturePointer(event)
            }
            return true
        }

        val handled = withLegacyForwardedPointer(event) {
            super.onTouchEvent(event)
        }
        finishLegacyTerminalFling(event)
        if (
            event.actionMasked == MotionEvent.ACTION_MOVE &&
            scrollState == SCROLL_STATE_DRAGGING
        ) {
            updateGlidePressed(activePointerX(event), activePointerY(event))
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // RecyclerView may keep the stream after its child has received a
            // second pointer. Cancel the pending long-press recognizer here as
            // well as in onInterceptTouchEvent so a two-finger touch can never
            // open the actions menu on a later looper tick.
            dragController.onCancel()
        }
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            clearGlidePressed()
            dragController.finishAdditionalPointer()
            recyclerGestureNeedsTerminal = false
            dragController.onCancel()
            clearGesturePointer()
        }
        return handled
    }

    private fun updateGesturePointer(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gesturePointerId = event.getPointerId(0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (index in 0 until event.pointerCount) {
                    gesturePointerId = event.getPointerId(index)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val lifted = event.getPointerId(event.actionIndex)
                if (lifted == gesturePointerId) {
                    gesturePointerId = (0 until event.pointerCount)
                        .firstOrNull { it != event.actionIndex }
                        ?.let(event::getPointerId)
                        ?: MotionEvent.INVALID_POINTER_ID
                }
            }
        }
    }

    private fun clearGesturePointer() {
        gesturePointerId = MotionEvent.INVALID_POINTER_ID
        interceptedAction = -1
        interceptedEventTime = Long.MIN_VALUE
    }

    /**
     * Keeps RecyclerView's own touch state coherent without synthesizing or
     * mutating MotionEvents. The real terminal sample is handed to the parent
     * interceptor exactly once after a native drag takes ownership.
     */
    private fun finishRecyclerGesture(event: MotionEvent) {
        if (!recyclerGestureNeedsTerminal) return
        if (
            event.actionMasked != MotionEvent.ACTION_UP &&
            event.actionMasked != MotionEvent.ACTION_CANCEL
        ) {
            return
        }
        withLegacyForwardedPointer(event) {
            super.onInterceptTouchEvent(event)
        }
        recyclerGestureNeedsTerminal = false
    }

    private fun observeEdgeGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeTerminalAction = -1
                edgeTerminalEventTime = Long.MIN_VALUE
                springEdgeEffects.beginGesture()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (
                    edgeTerminalAction == event.actionMasked &&
                    edgeTerminalEventTime == event.eventTime
                ) {
                    return
                }
                edgeTerminalAction = event.actionMasked
                edgeTerminalEventTime = event.eventTime
                val canCommitFace =
                    event.actionMasked == MotionEvent.ACTION_UP &&
                        !dragTouchOwned &&
                        !dragController.isDragging
                if (springEdgeEffects.confirmTopRelease(canCommitFace)) {
                    onScrollToTop?.invoke()
                }
            }
        }
    }

    private fun activePointerIndex(event: MotionEvent): Int {
        if (event.pointerCount <= 0) return 0
        val tracked = gesturePointerId
        val index = if (tracked != MotionEvent.INVALID_POINTER_ID) event.findPointerIndex(tracked) else -1
        return if (index >= 0) index else 0
    }

    private fun activePointerX(event: MotionEvent): Float = event.getX(activePointerIndex(event))

    private fun activePointerY(event: MotionEvent): Float = event.getY(activePointerIndex(event))

    private fun prepareLegacyEdgePointer(event: MotionEvent) {
        if (!legacyEdgeHandoffEnabled || event.pointerCount <= 0) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetLegacyEdgeGesture()
        }
        val pointerIndex = activePointerIndex(event)
        val pointerId = event.getPointerId(pointerIndex)
        val pointerY = event.getY(pointerIndex)
        if (
            legacyPreparedAction == event.actionMasked &&
            legacyPreparedEventTime == event.eventTime &&
            legacyPreparedPointerId == pointerId &&
            legacyPreparedPhysicalY == pointerY
        ) {
            return
        }
        legacyPreparedAction = event.actionMasked
        legacyPreparedEventTime = event.eventTime
        legacyPreparedPointerId = pointerId
        legacyPreparedPhysicalY = pointerY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                legacyEdgePointer.begin(pointerY)
                legacyPointerVelocity.begin(pointerY, event.eventTime)
                legacyPreparedOffsetY = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                legacyEdgePointer.rebase(pointerY)
                legacyPointerVelocity.begin(pointerY, event.eventTime)
                legacyPreparedOffsetY = legacyEdgePointer.currentOffset()
            }

            MotionEvent.ACTION_MOVE -> {
                legacyPointerVelocity.record(
                    pointerY = pointerY,
                    eventTime = event.eventTime,
                    preserveWhenStationary = false,
                )
                val physicalDelta = legacyEdgePointer.physicalDeltaTo(pointerY)
                val consumed = springEdgeEffects.consumeLegacyInwardPointerDelta(physicalDelta)
                legacyPreparedOffsetY = legacyEdgePointer.advanceTo(pointerY, consumed)
                if (consumed != 0f) legacyEdgeHandoffOccurred = true
                val forwardedDelta = physicalDelta - consumed
                if (
                    legacyEdgeHandoffOccurred &&
                    abs(forwardedDelta) > 0.001f &&
                    !springEdgeEffects.hasActiveEdge
                ) {
                    legacyEdgeRemainderForwarded = true
                }
            }

            MotionEvent.ACTION_UP -> {
                legacyPointerVelocity.record(
                    pointerY = pointerY,
                    eventTime = event.eventTime,
                    preserveWhenStationary = true,
                )
                legacyPreparedOffsetY = legacyEdgePointer.advanceTo(pointerY, 0f)
                val terminalVelocityY = (-legacyPointerVelocity.pointerVelocityY)
                    .roundToInt()
                    .coerceIn(
                        -viewConfiguration.scaledMaximumFlingVelocity,
                        viewConfiguration.scaledMaximumFlingVelocity,
                    )
                legacyPendingFlingVelocityY = if (
                    legacyEdgeHandoffOccurred &&
                    abs(terminalVelocityY) >= viewConfiguration.scaledMinimumFlingVelocity
                ) {
                    terminalVelocityY
                } else {
                    null
                }
                legacyFlingObserved = false
            }

            MotionEvent.ACTION_CANCEL -> {
                legacyPreparedOffsetY = legacyEdgePointer.advanceTo(pointerY, 0f)
                legacyPendingFlingVelocityY = null
                legacyFlingObserved = false
            }

            else -> legacyPreparedOffsetY = legacyEdgePointer.currentOffset()
        }
    }

    private inline fun <T> withLegacyForwardedPointer(
        event: MotionEvent,
        block: () -> T,
    ): T {
        val offsetY = if (legacyEdgeHandoffEnabled) legacyPreparedOffsetY else 0f
        if (offsetY == 0f) return block()
        event.offsetLocation(0f, offsetY)
        return try {
            block()
        } finally {
            event.offsetLocation(0f, -offsetY)
        }
    }

    private fun finishLegacyTerminalFling(event: MotionEvent) {
        if (!legacyEdgeHandoffEnabled) return
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            legacyPendingFlingVelocityY = null
            legacyFlingObserved = false
            return
        }
        if (event.actionMasked != MotionEvent.ACTION_UP) return
        val velocityY = legacyPendingFlingVelocityY
        if (
            velocityY != null &&
            !legacyFlingObserved &&
            legacyEdgeRemainderForwarded &&
            !springEdgeEffects.hasActiveEdge &&
            abs(velocityY) >= viewConfiguration.scaledMinimumFlingVelocity
        ) {
            super.fling(0, velocityY)
        }
        legacyPendingFlingVelocityY = null
        legacyFlingObserved = false
    }

    private fun resetLegacyEdgeGesture() {
        legacyEdgePointer.reset()
        legacyPreparedAction = -1
        legacyPreparedEventTime = Long.MIN_VALUE
        legacyPreparedPointerId = MotionEvent.INVALID_POINTER_ID
        legacyPreparedPhysicalY = Float.NaN
        legacyPreparedOffsetY = 0f
        legacyEdgeHandoffOccurred = false
        legacyEdgeRemainderForwarded = false
        legacyPointerVelocity.reset()
        legacyPendingFlingVelocityY = null
        legacyFlingObserved = false
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
        val previous = currentConfig.value
        val previousEntryProgress = previous.runtimeEntryProgress
        val entryTokenChanged = previous.entryAnimationToken != next.value.entryAnimationToken
        val shouldStartEntryAnimation =
            entryTokenChanged && next.value.active && next.value.entryMotionEnabled
        val entryAnimationWasRunning = entryAnimator?.isRunning == true
        val geometryChanged = hasDifferentLayoutGeometry(
            current = previous,
            next = next.value,
        )
        val rowRenderingChanged = hasDifferentNativeRowRendering(previous, next.value)
        val edgeFadeChanged = hasDifferentNativeEdgeFade(previous, next.value)
        val contentOverlayEffectChanged =
            hasDifferentNativeContentOverlayEffect(previous, next.value)
        val motionProfileChanged = previous.motionProfile != next.value.motionProfile
        val resetInitialScroll =
            previous.initialResetToken != next.value.initialResetToken
        if ((geometryChanged || resetInitialScroll) && dragController.isActive) {
            cancelActiveDrag()
        }
        if (geometryChanged) {
            (layoutManager as? NativeSpatialAppListLayoutManager)?.markGeometryDirty()
        }
        currentConfig = next
        next.value.runtimeBlurSuppressed = motionBlurSuppressed
        if (motionProfileChanged) {
            springEdgeEffects.updateMotionProfile(next.value.motionProfile)
        }
        when {
            shouldStartEntryAnimation -> {
                cancelEntryAnimation(resetProgress = false)
                next.value.runtimeEntryProgress = 0f
            }
            !next.value.active || !next.value.entryMotionEnabled -> {
                cancelEntryAnimation(resetProgress = false)
                next.value.runtimeEntryProgress = 1f
            }
            entryAnimationWasRunning -> {
                // Configuration updates (palette, blur, geometry) must not
                // snap an in-flight native animation back to its initial
                // progress. The clock is independent of this snapshot.
                next.value.runtimeEntryProgress = previousEntryProgress
            }
            else -> next.value.runtimeEntryProgress = next.value.entryProgress
        }
        if (!next.value.active) {
            // A screen transition can remove the list before the terminal
            // pointer sample arrives.  Drop a pending pull-to-face candidate
            // here; a later UP from the old stream must never navigate.
            springEdgeEffects.confirmTopRelease(terminalUp = false)
            resetLegacyEdgeGesture()
            clearGlidePressed()
            cancelActiveDrag()
        }
        nativeAdapter.setConfig(next)
        if (rowRenderingChanged) {
            applyConfigToAttachedRows(next)
            dragOverlayView?.applyConfig(next)
        }
        if (edgeFadeChanged) updateEdgeFadeShaders()
        if (contentOverlayEffectChanged) applyContentOverlayBlur()
        if (geometryChanged) updateViewportPadding(height)
        (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
        applyAttachedChildVisuals()
        if (geometryChanged) requestLayout()
        if (resetInitialScroll) {
            stopScroll()
            scrollToPosition(0)
        }
        if (shouldStartEntryAnimation) startEntryAnimation()
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
        val nextLayoutManager = strategy.createLayoutManager(context) { currentConfig.value }
        activeLayoutStrategy = strategy
        layoutManager = nextLayoutManager
        updateViewportPadding(height)
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
        cancelActiveDrag()
        cancelDragSettle(removeOverlay = true)
        dragPreviewAnimationGeneration++
        dragPreviewAnimator?.cancel()
        dragPreviewAnimator = null
        dragPreviewOriginalOrder = emptyList()
        dragPreviewOrder = emptyList()
        springEdgeEffects.releaseOwner()
        finishOwnedTouch()
        cancelEntryAnimation(resetProgress = true)
        dragController.release()
        dragPreviewAnimationGeneration++
        dragPreviewAnimator?.cancel()
        dragPreviewAnimator = null
        released = true
        removeCallbacks(restoreMotionBlur)
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
        dispatchCommandResult(command)
    }

    private fun dispatchCommandResult(command: AppListCommand): Boolean {
        if (released) return false
        return onCommand?.invoke(command) ?: false
    }

    private fun finishOwnedTouch(scheduleMotionBlurRestore: Boolean = true) {
        dragTouchOwned = false
        clearGlidePressed()
        recyclerGestureNeedsTerminal = false
        clearGesturePointer()
        resetLegacyEdgeGesture()
        removeCallbacks(restoreMotionBlur)
        if (scheduleMotionBlurRestore) postDelayed(restoreMotionBlur, 72L)
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun cancelActiveDrag() {
        dragController.onCancel()
        if (dragTouchOwned) finishOwnedTouch()
    }

    /**
     * Drives the Material/Flue Next entry motion on the View clock. Compose
     * only changes [NativeAppListConfig.entryAnimationToken] once per entry;
     * no animated value crosses the AndroidView boundary.
     */
    private fun startEntryAnimation() {
        if (released || !currentConfig.value.active || !currentConfig.value.entryMotionEnabled) {
            return
        }
        val generation = ++entryAnimationGeneration
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            val phase = currentConfig.value.motionProfile.appListEnter
            duration = phase.durationMillis.toLong()
            interpolator = phase.easing.asNativeTimeInterpolator()
            addUpdateListener { update ->
                if (released || generation != entryAnimationGeneration) return@addUpdateListener
                currentConfig.value.runtimeEntryProgress =
                    (update.animatedValue as? Float)?.coerceIn(0f, 1f) ?: 1f
                // Layout/child transforms are the only per-frame work. The
                // RecyclerView data snapshot and Compose tree remain untouched.
                (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
                applyAttachedChildVisuals()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != entryAnimationGeneration || released) return
                    currentConfig.value.runtimeEntryProgress = 1f
                    entryAnimator = null
                    (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
                    applyAttachedChildVisuals()
                    invalidate()
                }
            })
        }
        entryAnimator = animator
        animator.start()
    }

    private fun cancelEntryAnimation(resetProgress: Boolean) {
        entryAnimationGeneration++
        entryAnimator?.cancel()
        entryAnimator = null
        if (resetProgress && !released) {
            currentConfig.value.runtimeEntryProgress = 1f
        }
    }

    private fun setPullOffset(offset: Float) {
        val next = offset.takeIf(Float::isFinite) ?: 0f
        if (pullOffsetPx == next) return
        pullOffsetPx = next
        for (index in 0 until childCount) {
            (getChildAt(index) as? NativeAppListRowView)
                ?.setContentTranslationY(next)
        }
        applyMoveFrameVisuals()
        invalidate()
    }

    private fun applyDragPreviewTargets(animate: Boolean) {
        val original = dragPreviewOriginalOrder
        val preview = dragPreviewOrder
        if (original.isEmpty() || preview.size != original.size) {
            clearDragPreviewTranslations(animate)
            return
        }
        val originalPositions = HashMap<AppItemKey, Int>(original.size)
        val previewPositions = HashMap<AppItemKey, Int>(preview.size)
        original.forEachIndexed { index, key -> originalPositions[key] = index }
        preview.forEachIndexed { index, key -> previewPositions[key] = index }

        val slots = HashMap<Int, NativeDragPreviewSlot>(childCount)
        for (index in 0 until childCount) {
            val child = getChildAt(index) as? NativeAppListRowView ?: continue
            val holder = getChildViewHolder(child) as?
                NativeAppListAdapter.AppItemViewHolder ?: continue
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition == RecyclerView.NO_POSITION) continue
            slots[adapterPosition] = NativeDragPreviewSlot(
                centerX = child.iconCenterXInParentWithoutPreviewOrFlow(),
                centerY = child.iconCenterYInParentWithoutPreviewOrFlow(),
            )
        }

        val moves = ArrayList<NativeDragPreviewMove>(childCount)
        for (index in 0 until childCount) {
            val row = getChildAt(index) as? NativeAppListRowView ?: continue
            val holder = getChildViewHolder(row) as?
                NativeAppListAdapter.AppItemViewHolder ?: continue
            val key = holder.boundKeyOrNull() ?: continue
            val originalPosition = originalPositions[key] ?: continue
            val previewPosition = previewPositions[key] ?: continue
            val origin = slots[originalPosition] ?: NativeDragPreviewSlot(
                row.iconCenterXInParentWithoutPreviewOrFlow(),
                row.iconCenterYInParentWithoutPreviewOrFlow(),
            )
            val destination = slots[previewPosition] ?: fallbackDragPreviewSlot(
                origin = origin,
                positionDelta = previewPosition - originalPosition,
            )
            moves += NativeDragPreviewMove(
                row = row,
                startX = row.previewTranslationX(),
                startY = row.previewTranslationY(),
                targetX = destination.centerX - origin.centerX,
                targetY = destination.centerY - origin.centerY,
            )
        }
        runDragPreviewAnimation(moves, animate)
    }

    private fun fallbackDragPreviewSlot(
        origin: NativeDragPreviewSlot,
        positionDelta: Int,
    ): NativeDragPreviewSlot {
        val distance = when (currentConfig.value.presentation) {
            NativeAppListPresentation.LINEAR -> currentConfig.value.rowHeightPx.toFloat()
            NativeAppListPresentation.HONEYCOMB ->
                currentConfig.value.honeycombCellSizePx.takeIf { it > 0f }
                    ?: currentConfig.value.iconSizePx.toFloat()
            NativeAppListPresentation.DENSE_GRID ->
                currentConfig.value.denseGridMinimumItemHeightPx
                    .coerceAtLeast(currentConfig.value.iconSizePx.toFloat())
        }
        return NativeDragPreviewSlot(
            centerX = origin.centerX,
            centerY = origin.centerY + positionDelta * distance,
        )
    }

    private fun clearDragPreviewTranslations(animate: Boolean) {
        val moves = ArrayList<NativeDragPreviewMove>(childCount)
        for (index in 0 until childCount) {
            val row = getChildAt(index) as? NativeAppListRowView ?: continue
            moves += NativeDragPreviewMove(
                row = row,
                startX = row.previewTranslationX(),
                startY = row.previewTranslationY(),
                targetX = 0f,
                targetY = 0f,
            )
        }
        runDragPreviewAnimation(moves, animate)
    }

    private fun runDragPreviewAnimation(
        moves: List<NativeDragPreviewMove>,
        animate: Boolean,
    ) {
        val generation = ++dragPreviewAnimationGeneration
        dragPreviewAnimator?.cancel()
        dragPreviewAnimator = null
        if (moves.isEmpty()) return
        if (
            !animate ||
            moves.none { move ->
                abs(move.targetX - move.startX) > 0.01f ||
                    abs(move.targetY - move.startY) > 0.01f
            }
        ) {
            moves.forEach { move ->
                move.row.setPreviewTranslation(move.targetX, move.targetY)
            }
            applyMoveFrameVisuals()
            return
        }
        dragPreviewAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NATIVE_DRAG_PREVIEW_DURATION_MS
            interpolator = NATIVE_DRAG_SETTLE_INTERPOLATOR
            addUpdateListener { animator ->
                if (released || generation != dragPreviewAnimationGeneration) {
                    return@addUpdateListener
                }
                val progress = animator.animatedFraction
                moves.forEach { move ->
                    move.row.setPreviewTranslation(
                        move.startX + (move.targetX - move.startX) * progress,
                        move.startY + (move.targetY - move.startY) * progress,
                    )
                }
                applyMoveFrameVisuals()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != dragPreviewAnimationGeneration) return
                    dragPreviewAnimator = null
                    moves.forEach { move ->
                        move.row.setPreviewTranslation(move.targetX, move.targetY)
                    }
                    applyMoveFrameVisuals()
                }
            })
            start()
        }
    }

    private fun holderForKey(key: AppItemKey): NativeAppListAdapter.AppItemViewHolder? {
        for (index in 0 until childCount) {
            val holder = getChildViewHolder(getChildAt(index)) as?
                NativeAppListAdapter.AppItemViewHolder ?: continue
            if (holder.boundKeyOrNull() == key) return holder
        }
        return null
    }

    private fun updateGlidePressed(x: Float, y: Float) {
        setGlidePressedKey(keyAtPoint(x, y))
    }

    private fun clearGlidePressed() {
        setGlidePressedKey(null)
    }

    private fun setGlidePressedKey(key: AppItemKey?) {
        if (glidePressedKey == key) return
        glidePressedKey?.let { previous ->
            (holderForKey(previous)?.itemView as? NativeAppListRowView)
                ?.setGlidePressedState(false)
        }
        glidePressedKey = key
        key?.let { next ->
            (holderForKey(next)?.itemView as? NativeAppListRowView)
                ?.setGlidePressedState(true)
        }
    }

    private fun keyAtPoint(x: Float, y: Float): AppItemKey? {
        return keyAtPoint(x, y, centerFraction = 1f)
    }

    private fun keyAtPoint(x: Float, y: Float, centerFraction: Float): AppItemKey? {
        val fraction = centerFraction.coerceIn(0f, 1f)
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index)
            val holder = getChildViewHolder(child) as?
                NativeAppListAdapter.AppItemViewHolder ?: continue
            if (holder.boundKeyOrNull() == draggedSourceKey) continue
            val pivotX = child.left + child.pivotX + child.translationX
            val pivotY = child.top + child.pivotY + child.translationY
            val left = pivotX + (child.left - (child.left + child.pivotX)) * child.scaleX
            val right = pivotX + (child.right - (child.left + child.pivotX)) * child.scaleX
            val top = pivotY + (child.top - (child.top + child.pivotY)) * child.scaleY
            val bottom = pivotY + (child.bottom - (child.top + child.pivotY)) * child.scaleY
            val hitHalfWidth = (right - left) * fraction / 2f
            val hitHalfHeight = (bottom - top) * fraction / 2f
            if (
                x in (pivotX - hitHalfWidth)..(pivotX + hitHalfWidth) &&
                y in (pivotY - hitHalfHeight)..(pivotY + hitHalfHeight)
            ) {
                return holder.boundKeyOrNull()
            }
        }
        return null
    }

    private inner class NativeDragHost : NativeAppListDragHost {
        override val viewportHeightPx: Float
            get() = height.toFloat()

        override fun keyAt(x: Float, y: Float): AppItemKey? {
            return keyAtPoint(x, y)
        }

        override fun reorderKeyAt(x: Float, y: Float): AppItemKey? {
            keyAtPoint(x, y)?.let { return it }
            val manager = layoutManager as? NativeSpatialAppListLayoutManager ?: return null
            val excludedPosition = draggedSourceKey
                ?.let(::holderForKey)
                ?.bindingAdapterPosition
                ?: -1
            val position = manager.findNearestVisiblePosition(
                x = x,
                y = y,
                maximumDistancePx = nativeSpatialReorderRadiusPx(currentConfig.value),
                excludedPosition = excludedPosition,
            )
            return nativeAdapter.keyAtPositionOrNull(position)
        }

        override fun folderKeyAt(x: Float, y: Float): AppItemKey? {
            for (index in childCount - 1 downTo 0) {
                val child = getChildAt(index) as? NativeAppListRowView ?: continue
                val holder = getChildViewHolder(child) as?
                    NativeAppListAdapter.AppItemViewHolder ?: continue
                val key = holder.boundKeyOrNull() ?: continue
                if (key == draggedSourceKey) continue
                val targetRow = holder.boundRowOrNull() ?: continue
                val sourceRow = draggedSourceKey
                    ?.let(nativeAdapter::rowForKey)
                    ?: dragOverlayView?.boundRowForOverlay()
                val targetAllowed = sourceRow?.let { nativeFolderTargetAllowed(it, targetRow) }
                    ?: (targetRow.entry.type != com.flue.launcher.applist.model.AppItemType.SHORTCUT)
                if (!targetAllowed) continue
                if (child.containsTransformedIconPoint(x, y, centerFraction = 0.42f)) {
                    return key
                }
            }
            return null
        }

        override fun currentKeys(): List<AppItemKey> = nativeAdapter.currentKeysView()

        override fun planMove(
            source: AppItemKey,
            target: AppItemKey,
        ): AppListCommand.Move? = nativeAdapter.planMove(source, target)

        override fun planFolder(
            source: AppItemKey,
            target: AppItemKey,
        ): AppListCommand.CreateFolder? = nativeAdapter.planFolder(source, target)

        override fun beginDragPreview(source: AppItemKey): Boolean =
            nativeAdapter.beginDragPreview(source)

        override fun previewMove(source: AppItemKey, target: AppItemKey): Boolean =
            nativeAdapter.previewMove(source, target)

        override fun finishDragPreview(commit: Boolean): AppListCommand.Move? =
            nativeAdapter.finishDragPreview(commit)

        override fun completeDragPreview(accepted: Boolean) =
            nativeAdapter.completeDragPreview(accepted)

        override fun previewFolderCommit(
            source: AppItemKey,
            target: AppItemKey,
        ): Boolean {
            val targetRow = holderForKey(target)?.itemView as? NativeAppListRowView
            folderSettleCenterX = targetRow?.iconCenterXInParentWithoutFlow() ?: Float.NaN
            folderSettleCenterY = targetRow?.iconCenterYInParentWithoutFlow() ?: Float.NaN
            return nativeAdapter.previewFolderCommit(source, target).also { accepted ->
                if (!accepted) {
                    folderSettleCenterX = Float.NaN
                    folderSettleCenterY = Float.NaN
                }
            }
        }

        override fun discardDragPreviewAwaitingAuthoritative() =
            nativeAdapter.discardDragPreviewAwaitingAuthoritative()

        override fun dispatch(command: AppListCommand): Boolean =
            dispatchCommandResult(command)

        override fun suppressLongClick(key: AppItemKey, suppress: Boolean) {
            holderForKey(key)?.itemView?.isLongClickable = !suppress
        }

        override fun beginDrag(key: AppItemKey, pointerX: Float, pointerY: Float) {
            cancelDragSettle(removeOverlay = true)
            clearGlidePressed()
            val holder = holderForKey(key) ?: return
            // A drag overlay must start from the item's settled list geometry.
            // Keeping an interrupted edge translation here offsets both the
            // overlay and its hit region, then snaps when the edge completes.
            springEdgeEffects.finish()
            itemAnimator?.endAnimation(holder)
            val source = holder.itemView as? NativeAppListRowView ?: return
            val row = holder.boundRowOrNull() ?: return
            draggedSourceKey = key
            draggedViewTouchOffsetX = pointerX - (source.left + source.translationX)
            draggedViewTouchOffsetY = pointerY - (source.top + source.translationY)
            draggedPointerX = pointerX
            draggedPointerY = pointerY
            val dragOverlay = NativeAppListRowView(context).apply {
                bind(row, currentConfig)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false
                isClickable = false
                alpha = source.alpha
                setVisualScale(source.visualScaleForOverlay())
                setIconBlurRadiusPx(
                    source.iconBlurRadiusForOverlay(),
                    effectiveIconBlurTier(),
                )
            }
            dragOverlay.measure(
                View.MeasureSpec.makeMeasureSpec(source.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(source.height, View.MeasureSpec.EXACTLY),
            )
            dragOverlay.layout(
                source.left,
                source.top,
                source.left + source.width,
                source.top + source.height,
            )
            this@NativeAppListView.overlay.add(dragOverlay)
            ViewCompat.setElevation(
                dragOverlay,
                if (currentConfig.value.iconShadowEnabled) {
                    resources.displayMetrics.density * 18f
                } else {
                    0f
                },
            )
            dragOverlayView = dragOverlay
            source.isPressed = false
            source.alpha = 0f
            dragOverlay.setDragVisualState(true)
        }

        override fun updateDrag(pointerX: Float, pointerY: Float) {
            val dragOverlay = dragOverlayView ?: return
            draggedPointerX = pointerX
            draggedPointerY = pointerY
            val offsetX = if (layoutManager is NativeSpatialAppListLayoutManager) {
                pointerX - draggedViewTouchOffsetX - dragOverlay.left
            } else {
                0f
            }
            val offsetY = pointerY - draggedViewTouchOffsetY - dragOverlay.top
            dragOverlay.setDragTranslation(offsetX, offsetY)
        }

        override fun endDrag(committed: Boolean) {
            val sourceKey = draggedSourceKey
            val dragOverlay = dragOverlayView
            val generation = ++dragSettleGeneration
            if (committed && sourceKey != null && dragOverlay != null) {
                dragOverlay.setDragVisualState(false)
                postOnAnimation {
                    if (
                        released ||
                        generation != dragSettleGeneration ||
                        dragOverlayView !== dragOverlay
                    ) {
                        return@postOnAnimation
                    }
                    val destination = holderForKey(sourceKey)?.itemView as? NativeAppListRowView
                    val destinationCenterX = when {
                        folderSettleCenterX.isFinite() -> folderSettleCenterX
                        destination != null -> destination.iconCenterXInParentWithoutFlow()
                        else -> Float.NaN
                    }
                    val destinationCenterY = when {
                        folderSettleCenterY.isFinite() -> folderSettleCenterY
                        destination != null -> destination.iconCenterYInParentWithoutFlow()
                        else -> Float.NaN
                    }
                    if (!destinationCenterX.isFinite() || !destinationCenterY.isFinite()) {
                        removeDragOverlay(generation, sourceKey, dragOverlay)
                        return@postOnAnimation
                    }
                    val currentCenterX = dragOverlay.iconCenterXInParentWithoutFlow()
                    val currentCenterY = dragOverlay.iconCenterYInParentWithoutFlow()
                    dragOverlay.animate()
                        .translationX(dragOverlay.translationX + destinationCenterX - currentCenterX)
                        .translationY(dragOverlay.translationY + destinationCenterY - currentCenterY)
                        .setDuration(NATIVE_DRAG_SETTLE_DURATION_MS)
                        .setInterpolator(NATIVE_DRAG_SETTLE_INTERPOLATOR)
                        .withEndAction {
                            removeDragOverlay(generation, sourceKey, dragOverlay)
                        }
                        .start()
                }
            } else if (dragOverlay != null) {
                removeDragOverlay(generation, sourceKey, dragOverlay)
            } else {
                finishDragVisualState(sourceKey)
            }
            draggedViewTouchOffsetY = 0f
            draggedViewTouchOffsetX = 0f
            draggedPointerY = 0f
            draggedPointerX = 0f
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

    private fun removeDragOverlay(
        generation: Long,
        sourceKey: AppItemKey?,
        dragOverlay: NativeAppListRowView,
    ) {
        if (generation != dragSettleGeneration || dragOverlayView !== dragOverlay) return
        dragOverlay.animate().cancel()
        overlay.remove(dragOverlay)
        dragOverlay.clearBinding()
        dragOverlayView = null
        finishDragVisualState(sourceKey)
    }

    private fun cancelDragSettle(removeOverlay: Boolean) {
        dragSettleGeneration++
        val dragOverlay = dragOverlayView ?: return
        dragOverlay.animate().cancel()
        if (removeOverlay) {
            val sourceKey = draggedSourceKey
            overlay.remove(dragOverlay)
            dragOverlay.clearBinding()
            dragOverlayView = null
            finishDragVisualState(sourceKey)
        }
    }

    private fun finishDragVisualState(sourceKey: AppItemKey?) {
        draggedSourceKey = null
        folderSettleCenterX = Float.NaN
        folderSettleCenterY = Float.NaN
        sourceKey?.let(::holderForKey)?.itemView?.isPressed = false
        (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
        applyAttachedChildVisuals()
    }

    private fun applyAttachedChildVisuals() {
        if (released || height <= 0) return
        if (usesHostChildVisualPass(activeLayoutStrategy)) {
            for (index in 0 until childCount) {
                applyChildVisual(getChildAt(index))
            }
        }
        draggedSourceKey?.let { key ->
            holderForKey(key)?.itemView?.alpha = 0f
        }
    }

    private fun applyMoveFrameVisuals() {
        if (released) return
        (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
        applyAttachedChildVisuals()
    }

    private fun applyConfigToAttachedRows(config: NativeAppListResolvedConfig) {
        for (index in 0 until childCount) {
            (getChildAt(index) as? NativeAppListRowView)?.applyConfig(config)
        }
    }

    private fun applyChildVisual(child: View) {
        if (!usesHostChildVisualPass(activeLayoutStrategy)) return
        val rowView = child as? NativeAppListRowView
        val positionTranslationY =
            rowView?.positionTranslationYWithoutFlow() ?: child.translationY
        val baseCenterY = child.top + child.height / 2f + positionTranslationY
        val visual = nativeLinearChildVisual(
            childCenterY = baseCenterY,
            viewportHeight = height,
            config = currentConfig.value,
            output = childVisualScratch,
            edgeCenterY = baseCenterY,
        )
        if (rowView != null && currentConfig.value.deviceShape == NativeDeviceShape.ROUND) {
            val safe = roundGeometryAdapter.resolve(
                centerX = rowView.iconCenterXInParentWithoutFlow(),
                centerY = rowView.iconCenterYInParentWithoutFlow(),
                viewportWidth = width.toFloat(),
                viewportHeight = height.toFloat(),
                itemWidth = rowView.iconWidthForSafeGeometry(),
                itemHeight = rowView.iconHeightForSafeGeometry(),
                config = currentConfig.value,
                output = roundGeometryScratch,
            )
            rowView.setFlowTranslation(
                x = safe.translationX,
                y = safe.translationY + visual.translationY,
            )
        } else {
            rowView?.setFlowTranslationY(visual.translationY)
        }
        val centerPivotX = child.width / 2f
        val centerPivotY = child.height / 2f
        if (child.pivotX != centerPivotX) child.pivotX = centerPivotX
        if (child.pivotY != centerPivotY) child.pivotY = centerPivotY
        if (rowView != null) {
            rowView.setVisualScale(visual.scale)
        } else {
            if (child.scaleX != visual.scale) child.scaleX = visual.scale
            if (child.scaleY != visual.scale) child.scaleY = visual.scale
            if (child.translationY != visual.translationY) {
                child.translationY = visual.translationY
            }
        }
        if (child.alpha != visual.alpha) child.alpha = visual.alpha
        rowView?.setIconBlurRadiusPx(
            radiusPx = visual.blurRadiusPx,
            tier = effectiveIconBlurTier(),
        )
        rowView?.setIconSaturation(visual.saturation)
    }

    private fun effectiveIconBlurTier(): NativeBlurTier =
        currentConfig.value.effectiveBlurTier()

    private fun setMotionBlurSuppressed(suppressed: Boolean) {
        if (motionBlurSuppressed == suppressed) return
        motionBlurSuppressed = suppressed
        currentConfig.value.runtimeBlurSuppressed = suppressed
        // The RecyclerView itself may carry the large content-overlay effect.
        // Update it in the same state transition as the per-row effects so no
        // blurred parent layer survives into a drag/fling frame.
        applyContentOverlayBlur()
        (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
        applyAttachedChildVisuals()
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
            setFlowTranslation(0f, 0f)
            setContentTranslationY(pullOffsetPx)
            setIconBlurRadiusPx(0f, NativeBlurTier.OFF)
            setIconSaturation(1f)
        }
    }

    private fun updateViewportPadding(viewportHeight: Int) {
        val verticalPadding = if (
            activeLayoutStrategy.semanticKey == LinearNativeAppListLayoutStrategy.semanticKey &&
            currentConfig.value.centerEdgeItems
        ) {
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
        val bucket = nativeContentOverlayBlurBucketPx(currentConfig.value)
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
                intArrayOf(
                    color.withAlphaFraction(0.38f),
                    color.withAlphaFraction(0.14f),
                    color.withAlphaFraction(0f),
                ),
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
                intArrayOf(
                    color.withAlphaFraction(0f),
                    color.withAlphaFraction(0.14f),
                    color.withAlphaFraction(0.42f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else null
        invalidate()
    }
}

private fun hasDifferentLayoutGeometry(
    current: NativeAppListConfig,
    next: NativeAppListConfig,
): Boolean =
    current.presentation != next.presentation ||
        current.leftSafeInsetPx != next.leftSafeInsetPx ||
        current.rowHeightPx != next.rowHeightPx ||
        current.iconSizePx != next.iconSizePx ||
        current.rowPaddingStartPx != next.rowPaddingStartPx ||
        current.rowPaddingTopPx != next.rowPaddingTopPx ||
        current.rowPaddingEndPx != next.rowPaddingEndPx ||
        current.rowPaddingBottomPx != next.rowPaddingBottomPx ||
        current.honeycombCellSizePx != next.honeycombCellSizePx ||
        current.honeycombNarrowColumns != next.honeycombNarrowColumns ||
        current.denseGridColumns != next.denseGridColumns ||
        current.denseGridHorizontalPaddingPx != next.denseGridHorizontalPaddingPx ||
        current.denseGridItemSpacingPx != next.denseGridItemSpacingPx ||
        current.denseGridItemAspectRatio != next.denseGridItemAspectRatio ||
        current.denseGridMinimumItemWidthPx != next.denseGridMinimumItemWidthPx ||
        current.denseGridMinimumItemHeightPx != next.denseGridMinimumItemHeightPx ||
        current.denseGridMinimumVerticalPaddingPx != next.denseGridMinimumVerticalPaddingPx ||
        current.centerEdgeItems != next.centerEdgeItems

private fun Int.withAlphaFraction(alpha: Float): Int =
    (this and 0x00ffffff) or ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)
