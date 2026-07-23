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
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.os.Build
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppItemKey
import kotlin.math.abs
import kotlin.math.roundToInt

private const val NATIVE_ENTRY_ANIMATION_DURATION_MS = 400L
private val NATIVE_ENTRY_INTERPOLATOR = PathInterpolator(0.4f, 0f, 0.2f, 1f)

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
            if (key == draggedSourceKey) view.alpha = 0f
        }

        override fun onChildViewDetachedFromWindow(view: View) {
            (view as? NativeAppListRowView)?.setFolderHoverState(false)
        }
    }
    private var released = false
    private var dragTouchOwned = false
    private var interceptedAction = -1
    private var interceptedEventTime = Long.MIN_VALUE
    private var interceptedEndsGesture = false
    /**
     * True when a DOWN interrupts the pull settle animation.
     *
     * Intercepting that DOWN makes this ViewGroup the owner of the complete
     * stream, so later MOVE/UP/CANCEL events are delivered straight to
     * [onTouchEvent] instead of passing through [onInterceptTouchEvent]. Keep
     * this bit until the terminal event so drag, scroll and pull all continue
     * to see the same gesture.
     */
    private var settleTouchOwned = false
    private var draggedSourceKey: AppItemKey? = null
    private var dragOverlayView: NativeAppListRowView? = null
    private var draggedViewTouchOffsetY = 0f
    private var draggedViewTouchOffsetX = 0f
    private var draggedPointerY = 0f
    private var draggedPointerX = 0f
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
    private var pullOutwardDragMultiplier = 1f
    private var pullSettleAnimator: ValueAnimator? = null
    private var pullPhysicalPointerY = 0f
    private var pullForwardedPointerY = 0f
    /** Pointer owning pull/drag tracking; RecyclerView still receives the full event. */
    private var pullActivePointerId = MotionEvent.INVALID_POINTER_ID
    private var preparedPullAction = -1
    private var preparedPullEventTime = Long.MIN_VALUE
    private var preparedPullRawY = Float.NaN
    private var preparedPullPointerId = MotionEvent.INVALID_POINTER_ID
    private var preparedFinalAction = -1
    private var preparedFinalEventTime = Long.MIN_VALUE
    private var preparedFinalPointerId = MotionEvent.INVALID_POINTER_ID
    private var preparedRecyclerPointerDelta = 0f
    private var preparedRecyclerMovePrimed = false
    private var recyclerScrollRemainder = 0f
    /**
     * RecyclerView normally sees the DOWN in [onInterceptTouchEvent] while a
     * child row still owns the stream.  When pull takes over, replay this
     * untouched sample into RecyclerView.onTouchEvent so its velocity tracker
     * receives one coherent DOWN -> MOVE/UP history.
     */
    private var recyclerDownEvent: MotionEvent? = null
    private var recyclerTouchPrimed = false
    private var pullVelocityTracker: VelocityTracker? = null
    private var pullFlingIssued = false
    private var velocitySampleAction = -1
    private var velocitySampleEventTime = Long.MIN_VALUE
    private var velocitySamplePointerId = MotionEvent.INVALID_POINTER_ID
    // RecyclerView's internal tracker can legitimately lose the MOVE history
    // when pull ownership is handed over mid-stream (notably on API 27
    // implementations). Keep a tiny allocation-free terminal sample as a
    // deterministic fallback; it is only consumed for a pull-owned release.
    private var manualVelocityLastY = Float.NaN
    private var manualVelocityLastTime = Long.MIN_VALUE
    private var manualVelocityY = 0f

    var onCommand: ((AppListCommand) -> Unit)? = null
    var onScrollToTop: (() -> Unit)? = null

    private fun onNativeAdapterListCommitted(itemCount: Int) {
        (layoutManager as? NativeSpatialAppListLayoutManager)
            ?.onAdapterItemCountCommitted(itemCount)
    }

    init {
        setHasFixedSize(true)
        overScrollMode = View.OVER_SCROLL_NEVER
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
        val pullOffsetClamped = pullToFaceState.updateMaximumOffset(
            maxOf(
                nativeOverscrollLimitPx(
                    presentation = currentConfig.value.presentation,
                    viewportHeightPx = bottom - top,
                    density = resources.displayMetrics.density,
                ),
                72f * resources.displayMetrics.density,
            ),
        )
        if (pullOffsetClamped) setPullOffset(pullToFaceState.offsetPx)
        updateViewportPadding(bottom - top)
        super.onLayout(changed, left, top, right, bottom)
        if (dragOverlayView != null) dragHost.updateDrag(draggedPointerX, draggedPointerY)
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

    override fun onDetachedFromWindow() {
        cancelActiveDrag()
        cancelPull()
        finishOwnedTouch()
        cancelEntryAnimation(resetProgress = true)
        super.onDetachedFromWindow()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (released || !currentConfig.value.active) return false
        captureRecyclerDown(event)
        preparePointerTransition(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            prepareFinalRecyclerPointerSample(event)
        }
        if (dragTouchOwned) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                rebasePullPointerAfterPointerUp(event)
            }
            return true
        }
        val consume = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPullSettle()
                pullToFaceState.onDown(
                    activePointerY(event),
                    initialOffsetPx = pullOffsetPx,
                )
                beginPullPointer(event)
                clearPreparedPullEvent()
                resetPullVelocityTracker(event)
                pullOutwardDragMultiplier = nativeOverscrollOutwardMultiplier(
                    currentConfig.value.presentation,
                )
                dragController.onDown(activePointerX(event), activePointerY(event), event.eventTime)
                if (pullOffsetPx != 0f) {
                    // A settling pull is rendered on the child transform. Own
                    // the new stream from DOWN so ViewGroup cannot dispatch it
                    // to the stale layout slot underneath the visible row.
                    interceptedAction = event.actionMasked
                    interceptedEventTime = event.eventTime
                    interceptedEndsGesture = false
                    settleTouchOwned = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dragConsumed = dragController.onMove(
                    activePointerX(event),
                    activePointerY(event),
                    event.eventTime,
                )
                if (dragConsumed) {
                    // From this frame onward the native stable-key drag owns
                    // the gesture. End RecyclerView's stream exactly once so
                    // it cannot retain pressed/velocity state behind the drag.
                    dispatchRecyclerCancel(event)
                    pullToFaceState.cancel()
                    setPullOffset(0f)
                    true
                } else {
                    val consumedPointerDelta = preparePullMove(event)
                    if (consumedPointerDelta != 0f || pullToFaceState.offsetPx != 0f) {
                        if (!pullTouchOwned) {
                            primeRecyclerTouchForPull(event)
                        }
                        dragController.onCancel()
                        pullTouchOwned = true
                        setPullOffset(pullToFaceState.offsetPx)
                        true
                    } else {
                        false
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val dragConsumed = dragController.onUp(
                    activePointerX(event),
                    activePointerY(event),
                    event.eventTime,
                )
                if (pullToFaceState.isPulling) {
                    releasePull()
                    true
                } else {
                    dragConsumed
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val dragConsumed = dragController.onCancel()
                if (pullToFaceState.isPulling) {
                    cancelPull()
                    true
                } else {
                    dragConsumed
                }
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
        val forwarded = withForwardedPullPointer(event) {
            super.onInterceptTouchEvent(event)
        }
        if (
            forwarded &&
            !dragTouchOwned &&
            dragController.isActive
        ) {
            // RecyclerView has taken ownership after touch-slop. Cancel the
            // pending long-press recognizer on the exact hand-off frame so its
            // delayed callback cannot open actions during a scroll.
            dragController.onCancel()
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            rebasePullPointerAfterPointerUp(event)
        }
        return forwarded
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        captureRecyclerDown(event)
        preparePointerTransition(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            prepareFinalRecyclerPointerSample(event)
        }
        val alreadyHandled =
            event.actionMasked == interceptedAction &&
                event.eventTime == interceptedEventTime
        if (alreadyHandled) {
            interceptedAction = -1
            interceptedEventTime = Long.MIN_VALUE
            if (settleTouchOwned) {
                return true
            }
            if (pullTouchOwned && preparedRecyclerMovePrimed) {
                // The intercept phase already replayed this exact MOVE into
                // RecyclerView after priming its synthetic DOWN.  ViewGroup
                // delivers the same object to us once more as the handoff
                // event; do not add a duplicate velocity sample.
                preparedRecyclerMovePrimed = false
                return true
            }
            if (!pullTouchOwned) {
                if (interceptedEndsGesture) finishOwnedTouch()
                return true
            }
        }

        if (settleTouchOwned) {
            return handleSettleOwnedTouch(event)
        }

        if (dragTouchOwned && !pullTouchOwned) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    // RecyclerView already received CANCEL on native-drag
                    // takeover. Do not send it an orphaned multi-pointer tail.
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                        rebasePullPointerAfterPointerUp(event)
                    }
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
                    finishOwnedTouch()
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragController.onCancel()
                    finishOwnedTouch()
                }
            }
            return true
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> handleRecyclerMove(event)
            MotionEvent.ACTION_UP -> {
                if (pullTouchOwned) {
                    val shouldFling = pullToFaceState.offsetPx == 0f
                    val handled = if (shouldFling) {
                        // The list remainder was consumed directly in layout
                        // space, so RecyclerView must not receive a second UP
                        // and discover a different velocity from its slop
                        // state.  The host tracker owns this release.
                        if (recyclerTouchPrimed) {
                            dispatchRecyclerCancel(event)
                            recyclerTouchPrimed = false
                        }
                        flingFromPullVelocity()
                        true
                    } else {
                        // Releasing while the face is still displaced must
                        // not turn the pull gesture into a list fling.
                        dispatchRecyclerCancel(event)
                        true
                    }
                    releasePull()
                    finishOwnedTouch()
                    handled
                } else {
                    val handled = withForwardedPullPointer(event) {
                        super.onTouchEvent(event)
                    }
                    pullToFaceState.onUp()
                    clearPullPointerTracking()
                    clearPullVelocityTracker()
                    clearRecyclerTouchStream()
                    handled
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val handled = if (pullTouchOwned) {
                    dispatchRecyclerCancel(event)
                    true
                } else {
                    withForwardedPullPointer(event) {
                        super.onTouchEvent(event)
                    }
                }
                pullToFaceState.cancel()
                if (pullTouchOwned) {
                    animatePullToRest(pullOffsetPx)
                    finishOwnedTouch()
                } else {
                    clearPullPointerTracking()
                    clearPullVelocityTracker()
                    clearRecyclerTouchStream()
                }
                handled
            }
            else -> {
                val handled = withForwardedPullPointer(event) { super.onTouchEvent(event) }
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    rebasePullPointerAfterPointerUp(event)
                }
                handled
            }
        }
    }

    /** Handles the stream whose DOWN interrupted an in-flight pull settle. */
    private fun handleSettleOwnedTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dragConsumed = dragController.onMove(
                    activePointerX(event),
                    activePointerY(event),
                    event.eventTime,
                )
                if (dragConsumed) {
                    // Long-press/drag now owns the gesture. Drop both the
                    // RecyclerView stream and the displaced pull transform so
                    // the overlay starts from the actual visible item.
                    dispatchRecyclerCancel(event)
                    pullToFaceState.cancel()
                    pullTouchOwned = false
                    setPullOffset(0f)
                } else {
                    // A DOWN which interrupted settling is already owned by
                    // this View.  Treat the first MOVE as a pull handoff so
                    // RecyclerView gets the same synthetic DOWN/history as a
                    // pull that starts from rest.
                    pullTouchOwned = true
                    handleRecyclerMove(event)
                }
            }

            MotionEvent.ACTION_UP -> {
                val dragConsumed = dragController.onUp(
                    activePointerX(event),
                    activePointerY(event),
                    event.eventTime,
                )
                val shouldFling = !dragConsumed && pullToFaceState.offsetPx == 0f
                val handled = if (shouldFling) {
                    if (recyclerTouchPrimed) {
                        dispatchRecyclerCancel(event)
                        recyclerTouchPrimed = false
                    }
                    flingFromPullVelocity()
                    true
                } else {
                    dispatchRecyclerCancel(event)
                    true
                }
                releasePull()
                finishOwnedTouch()
                return handled
            }

            MotionEvent.ACTION_CANCEL -> {
                dragController.onCancel()
                dispatchRecyclerCancel(event)
                val releaseOffset = pullOffsetPx
                pullToFaceState.cancel()
                animatePullToRest(releaseOffset)
                finishOwnedTouch()
            }

            MotionEvent.ACTION_POINTER_UP -> rebasePullPointerAfterPointerUp(event)
            MotionEvent.ACTION_POINTER_DOWN -> Unit
        }
        return true
    }

    private fun handleRecyclerMove(event: MotionEvent): Boolean {
        preparePullMove(event)
        val reporter = layoutManager as? NativeTouchScrollConsumptionReporter

        if (pullTouchOwned || recyclerTouchPrimed || settleTouchOwned) {
            // Once pull owns the stream, do not ask RecyclerView to discover
            // touch-slop a second time.  Its first MOVE would subtract the
            // platform slop from the exact remainder that crossed pull offset
            // zero (20px became 4px on API 27).  Consume the remainder in
            // layout space and keep the velocity tracker owned by this host.
            if (preparedRecyclerPointerDelta != 0f && recyclerTouchPrimed) {
                dispatchRecyclerCancel(event)
                recyclerTouchPrimed = false
            }
            scrollPreparedRecyclerPointerDeltaDirectly(reporter)
            preparedRecyclerPointerDelta = 0f
            preparedRecyclerMovePrimed = false
            return pullTouchOwned || settleTouchOwned
        }

        val directHandoff = preparedRecyclerMovePrimed ||
            scrollState != SCROLL_STATE_DRAGGING
        if (directHandoff && !preparedRecyclerMovePrimed) {
            withForwardedPullPointer(event) {
                super.onInterceptTouchEvent(event)
            }
            preparedRecyclerMovePrimed = true
        }
        val handled = if (directHandoff) {
            scrollPreparedRecyclerPointerDeltaDirectly(reporter)
            true
        } else {
            reporter?.beginTouchScrollCapture()
            withForwardedPullPointer(event) {
                super.onTouchEvent(event)
            }
        }
        if (!directHandoff) {
            val unconsumedScrollDeltaPx =
                reporter?.takeUnconsumedTouchScrollDeltaPx() ?: 0
            consumeUnconsumedTouchScroll(unconsumedScrollDeltaPx)
        }
        preparedRecyclerMovePrimed = false
        return handled || pullTouchOwned
    }

    private fun scrollPreparedRecyclerPointerDeltaDirectly(
        reporter: NativeTouchScrollConsumptionReporter?,
    ) {
        val requestedScroll = -preparedRecyclerPointerDelta + recyclerScrollRemainder
        val requestedScrollDelta = requestedScroll.roundToInt()
        recyclerScrollRemainder = requestedScroll - requestedScrollDelta
        preparedRecyclerPointerDelta = 0f
        if (requestedScrollDelta == 0) return
        reporter?.beginTouchScrollCapture()
        scrollBy(0, requestedScrollDelta)
        val unconsumedScrollDeltaPx =
            reporter?.takeUnconsumedTouchScrollDeltaPx() ?: 0
        consumeUnconsumedTouchScroll(unconsumedScrollDeltaPx)
    }

    private fun consumeUnconsumedTouchScroll(unconsumedScrollDeltaPx: Int) {
        if (unconsumedScrollDeltaPx == 0) return
        val consumedPointerDelta = pullToFaceState.consumePointerDelta(
            pointerDeltaPx = -unconsumedScrollDeltaPx.toFloat(),
            atTop = !canScrollVertically(-1),
            atBottom = !canScrollVertically(1),
            blocked = false,
            outwardDragMultiplier = pullOutwardDragMultiplier,
        )
        if (consumedPointerDelta == 0f) return
        dragController.onCancel()
        pullTouchOwned = true
        dragTouchOwned = true
        setPullOffset(pullToFaceState.offsetPx)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun preparePullMove(event: MotionEvent): Float {
        val pointerY = activePointerY(event)
        val pointerId = pullActivePointerId
        if (
            preparedPullAction == event.actionMasked &&
            preparedPullEventTime == event.eventTime &&
            preparedPullRawY == pointerY &&
            preparedPullPointerId == pointerId
        ) {
            return 0f
        }
        val pointerDelta = pointerY - pullPhysicalPointerY
        if (pointerDelta != 0f) setMotionBlurSuppressed(true)
        pullPhysicalPointerY = pointerY
        val consumedPointerDelta = pullToFaceState.consumePointerDelta(
            pointerDeltaPx = pointerDelta,
            atTop = !canScrollVertically(-1),
            atBottom = !canScrollVertically(1),
            blocked = dragController.isDragging,
            outwardDragMultiplier = pullOutwardDragMultiplier,
        )
        preparedRecyclerPointerDelta = pointerDelta - consumedPointerDelta
        pullForwardedPointerY += preparedRecyclerPointerDelta
        preparedPullAction = event.actionMasked
        preparedPullEventTime = event.eventTime
        preparedPullRawY = pointerY
        preparedPullPointerId = pointerId
        recordPullVelocitySample(event)
        if (consumedPointerDelta != 0f) {
            setPullOffset(pullToFaceState.offsetPx)
        }
        return consumedPointerDelta
    }

    private fun primeRecyclerTouchForPull(event: MotionEvent): Boolean {
        if (!recyclerTouchPrimed) {
            recyclerDownEvent?.let { down ->
                withForwardedPullPointer(down) {
                    super.onTouchEvent(down)
                }
            }
            recyclerTouchPrimed = recyclerDownEvent != null
        }
        val handled = withForwardedPullPointer(event) {
            super.onTouchEvent(event)
        }
        preparedRecyclerMovePrimed = true
        return handled
    }

    private fun forwardRecyclerTerminal(event: MotionEvent): Boolean {
        if (!recyclerTouchPrimed) {
            // This can happen when a settling pull receives UP before its first
            // MOVE. Replaying DOWN here still gives RecyclerView a complete,
            // valid stream and avoids an orphan terminal event.
            recyclerDownEvent?.let { down ->
                withForwardedPullPointer(down) {
                    super.onTouchEvent(down)
                }
            }
            recyclerTouchPrimed = recyclerDownEvent != null
        }
        return withForwardedPullPointer(event) {
            super.onTouchEvent(event)
        }
    }

    private fun captureRecyclerDown(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        recyclerDownEvent?.recycle()
        recyclerDownEvent = MotionEvent.obtain(event)
        recyclerTouchPrimed = false
    }

    private fun clearRecyclerTouchStream() {
        recyclerDownEvent?.recycle()
        recyclerDownEvent = null
        recyclerTouchPrimed = false
    }

    private inline fun <T> withForwardedPullPointer(
        event: MotionEvent,
        block: () -> T,
    ): T {
        val sourceY = activePointerY(event)
        val targetY = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> sourceY
            else -> pullForwardedPointerY
        }
        val offsetY = targetY - sourceY
        if (offsetY == 0f) return block()
        event.offsetLocation(0f, offsetY)
        return try {
            block()
        } finally {
            event.offsetLocation(0f, -offsetY)
        }
    }

    private fun dispatchRecyclerCancel(event: MotionEvent) {
        val originalAction = event.action
        event.action = MotionEvent.ACTION_CANCEL
        try {
            withForwardedPullPointer(event) {
                super.onTouchEvent(event)
            }
        } finally {
            event.action = originalAction
        }
    }

    private fun preparePointerTransition(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginPullPointer(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (index in 0 until event.pointerCount) {
                    // Watches do not need multi-finger drag hand-off. Cancelling
                    // is deterministic and prevents a second finger from
                    // triggering a pending long press or jumping the overlay.
                    val customGestureWasActive = dragController.isActive
                    dragController.onCancel()
                    if (customGestureWasActive && !pullTouchOwned) {
                        // Intercept this pointer-down as the terminal owner of
                        // the custom gesture. ViewGroup will send the child a
                        // CANCEL, so its own framework long-click timer cannot
                        // resurrect the menu after our controller cancelled.
                        dragTouchOwned = true
                    }
                    // Match RecyclerView: the newly pressed pointer becomes
                    // the active scroll pointer for subsequent MOVE samples.
                    rebasePullPointerTo(event, index)
                }
            }
        }
    }

    private fun beginPullPointer(event: MotionEvent) {
        if (event.pointerCount <= 0) {
            pullActivePointerId = MotionEvent.INVALID_POINTER_ID
            pullPhysicalPointerY = 0f
            pullForwardedPointerY = 0f
            return
        }
        val index = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            0
        } else {
            activePointerIndex(event).coerceIn(0, event.pointerCount - 1)
        }
        pullActivePointerId = event.getPointerId(index)
        pullPhysicalPointerY = event.getY(index)
        pullForwardedPointerY = pullPhysicalPointerY
    }

    private fun rebasePullPointerAfterPointerUp(event: MotionEvent) {
        if (event.pointerCount <= 1) {
            pullActivePointerId = MotionEvent.INVALID_POINTER_ID
            return
        }
        val actionIndex = event.actionIndex
        if (actionIndex !in 0 until event.pointerCount) return
        val liftedId = event.getPointerId(actionIndex)
        if (liftedId != pullActivePointerId) return
        val nextIndex = (0 until event.pointerCount).firstOrNull { it != actionIndex }
            ?: run {
                pullActivePointerId = MotionEvent.INVALID_POINTER_ID
                return
            }
        val forwardedOffset = pullForwardedPointerY - pullPhysicalPointerY
        rebasePullPointerTo(event, nextIndex, forwardedOffset)
    }

    private fun rebasePullPointerTo(
        event: MotionEvent,
        index: Int,
        forwardedOffset: Float = pullForwardedPointerY - pullPhysicalPointerY,
    ) {
        if (index !in 0 until event.pointerCount) return
        val nextY = event.getY(index)
        pullActivePointerId = event.getPointerId(index)
        pullPhysicalPointerY = nextY
        pullForwardedPointerY = nextY + forwardedOffset
        clearPreparedPullEvent()
    }

    private fun activePointerIndex(event: MotionEvent): Int {
        if (event.pointerCount <= 0) return 0
        val tracked = pullActivePointerId
        if (tracked != MotionEvent.INVALID_POINTER_ID) {
            val index = event.findPointerIndex(tracked)
            if (index >= 0) return index
        }
        return 0
    }

    private fun activePointerX(event: MotionEvent): Float =
        event.getX(activePointerIndex(event))

    private fun activePointerY(event: MotionEvent): Float =
        event.getY(activePointerIndex(event))

    /** Adds the terminal physical delta to the logical gesture before fling. */
    private fun prepareFinalRecyclerPointerSample(event: MotionEvent) {
        if (event.pointerCount <= 0) return
        if (!pullTouchOwned && !settleTouchOwned && !recyclerTouchPrimed) return
        val pointerId = pullActivePointerId
        if (
            preparedFinalAction == event.actionMasked &&
            preparedFinalEventTime == event.eventTime &&
            preparedFinalPointerId == pointerId
        ) {
            return
        }
        val pointerY = activePointerY(event)
        val pointerDelta = pointerY - pullPhysicalPointerY
        pullPhysicalPointerY = pointerY
        pullForwardedPointerY += pointerDelta
        preparedFinalAction = event.actionMasked
        preparedFinalEventTime = event.eventTime
        preparedFinalPointerId = pointerId
        recordPullVelocitySample(event)
    }

    private fun clearPullPointerTracking() {
        pullActivePointerId = MotionEvent.INVALID_POINTER_ID
        pullPhysicalPointerY = 0f
        pullForwardedPointerY = 0f
    }

    private fun resetPullVelocityTracker(event: MotionEvent) {
        clearPullVelocityTracker()
        recyclerScrollRemainder = 0f
        pullVelocityTracker = VelocityTracker.obtain()
        pullFlingIssued = false
        recordPullVelocitySample(event)
    }

    private fun recordPullVelocitySample(event: MotionEvent) {
        val tracker = pullVelocityTracker ?: return
        val pointerId = pullActivePointerId
        if (
            velocitySampleAction == event.actionMasked &&
            velocitySampleEventTime == event.eventTime &&
            velocitySamplePointerId == pointerId
        ) {
            return
        }
        val sourceY = activePointerY(event)
        val targetY = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            sourceY
        } else {
            pullForwardedPointerY
        }
        val previousY = manualVelocityLastY
        val previousTime = manualVelocityLastTime
        if (
            previousY.isFinite() &&
            previousTime != Long.MIN_VALUE &&
            event.eventTime > previousTime &&
            targetY.isFinite()
        ) {
            manualVelocityY = ((targetY - previousY) * 1000f /
                (event.eventTime - previousTime).toFloat())
                .takeIf(Float::isFinite)
                ?: manualVelocityY
        }
        if (targetY.isFinite()) {
            manualVelocityLastY = targetY
            manualVelocityLastTime = event.eventTime
        }
        val offsetY = targetY - sourceY
        val originalAction = event.action
        if (offsetY != 0f) event.offsetLocation(0f, offsetY)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            // VelocityTracker implementations may ignore terminal UP samples.
            // Feed the same terminal coordinate as a tracker-only MOVE while
            // leaving the event delivered to RecyclerView unchanged.
            event.action = MotionEvent.ACTION_MOVE
        }
        try {
            tracker.addMovement(event)
        } finally {
            event.action = originalAction
            if (offsetY != 0f) event.offsetLocation(0f, -offsetY)
        }
        velocitySampleAction = event.actionMasked
        velocitySampleEventTime = event.eventTime
        velocitySamplePointerId = pointerId
    }

    private fun flingFromPullVelocity(): Boolean {
        if (pullFlingIssued) return false
        val pointerId = pullActivePointerId
        if (pointerId == MotionEvent.INVALID_POINTER_ID) return false
        val viewConfiguration = ViewConfiguration.get(context)
        val trackerVelocityY = pullVelocityTracker?.let { tracker ->
            tracker.computeCurrentVelocity(
                1000,
                viewConfiguration.scaledMaximumFlingVelocity.toFloat(),
            )
            tracker.getYVelocity(pointerId)
        } ?: 0f
        val velocityYSource = if (
            trackerVelocityY.isFinite() &&
            abs(trackerVelocityY) >= viewConfiguration.scaledMinimumFlingVelocity
        ) {
            trackerVelocityY
        } else {
            manualVelocityY
        }
        val velocityY = (-velocityYSource).roundToInt()
        if (abs(velocityY) < viewConfiguration.scaledMinimumFlingVelocity) return false
        val started = fling(
            0,
            velocityY.coerceIn(
                -viewConfiguration.scaledMaximumFlingVelocity,
                viewConfiguration.scaledMaximumFlingVelocity,
            ),
        )
        if (started) pullFlingIssued = true
        return started
    }

    private fun clearPullVelocityTracker() {
        pullVelocityTracker?.recycle()
        pullVelocityTracker = null
        velocitySampleAction = -1
        velocitySampleEventTime = Long.MIN_VALUE
        velocitySamplePointerId = MotionEvent.INVALID_POINTER_ID
        manualVelocityLastY = Float.NaN
        manualVelocityLastTime = Long.MIN_VALUE
        manualVelocityY = 0f
        pullFlingIssued = false
    }

    private fun clearPreparedPullEvent() {
        preparedPullAction = -1
        preparedPullEventTime = Long.MIN_VALUE
        preparedPullRawY = Float.NaN
        preparedPullPointerId = MotionEvent.INVALID_POINTER_ID
        preparedFinalAction = -1
        preparedFinalEventTime = Long.MIN_VALUE
        preparedFinalPointerId = MotionEvent.INVALID_POINTER_ID
        preparedRecyclerPointerDelta = 0f
        preparedRecyclerMovePrimed = false
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
            cancelActiveDrag()
            cancelPull()
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
        cancelPull()
        finishOwnedTouch()
        cancelEntryAnimation(resetProgress = true)
        dragController.release()
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
        if (!released) {
            onCommand?.invoke(command)
        }
    }

    private fun finishOwnedTouch() {
        dragTouchOwned = false
        pullTouchOwned = false
        interceptedAction = -1
        interceptedEventTime = Long.MIN_VALUE
        interceptedEndsGesture = false
        settleTouchOwned = false
        clearPreparedPullEvent()
        clearPullPointerTracking()
        clearPullVelocityTracker()
        clearRecyclerTouchStream()
        recyclerScrollRemainder = 0f
        removeCallbacks(restoreMotionBlur)
        postDelayed(restoreMotionBlur, 72L)
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
        if (!dragTouchOwned) {
            clearPullPointerTracking()
            clearPullVelocityTracker()
        }
    }

    private fun animatePullToRest(startOffset: Float) {
        cancelPullSettle()
        if (startOffset == 0f || released) {
            setPullOffset(0f)
            return
        }
        pullSettleAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = 220L
            addUpdateListener { animator -> setPullOffset(animator.animatedValue as Float) }
            start()
        }
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
            duration = NATIVE_ENTRY_ANIMATION_DURATION_MS
            interpolator = NATIVE_ENTRY_INTERPOLATOR
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

    private fun cancelPullSettle() {
        pullSettleAnimator?.cancel()
        pullSettleAnimator = null
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

    private fun holderForKey(key: AppItemKey): NativeAppListAdapter.AppItemViewHolder? {
        for (index in 0 until childCount) {
            val holder = getChildViewHolder(getChildAt(index)) as?
                NativeAppListAdapter.AppItemViewHolder ?: continue
            if (holder.boundKeyOrNull() == key) return holder
        }
        return null
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

        override fun folderKeyAt(x: Float, y: Float): AppItemKey? =
            keyAtPoint(
                x = x,
                y = y,
                centerFraction = if (layoutManager is NativeSpatialAppListLayoutManager) 0.42f else 1f,
            )

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

        override fun dispatch(command: AppListCommand) = dispatchCommand(command)

        override fun suppressLongClick(key: AppItemKey, suppress: Boolean) {
            holderForKey(key)?.itemView?.isLongClickable = !suppress
        }

        override fun beginDrag(key: AppItemKey, pointerX: Float, pointerY: Float) {
            val holder = holderForKey(key) ?: return
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

        override fun endDrag() {
            val sourceKey = draggedSourceKey
            dragOverlayView?.let { dragOverlay ->
                this@NativeAppListView.overlay.remove(dragOverlay)
                dragOverlay.clearBinding()
            }
            dragOverlayView = null
            draggedSourceKey = null
            draggedViewTouchOffsetY = 0f
            draggedViewTouchOffsetX = 0f
            draggedPointerY = 0f
            draggedPointerX = 0f
            autoScrollRemainder = 0f
            sourceKey?.let(::holderForKey)?.itemView?.isPressed = false
            (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
            applyAttachedChildVisuals()
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
        // Fast-flow compensation was intentionally removed: it changed item
        // positions during a fling and was a major source of jank on legacy
        // watches. Keep the visual pass single-owner and position-stable.
        val flowOffset = 0f
        val visual = nativeLinearChildVisual(
            childCenterY = baseCenterY,
            viewportHeight = height,
            config = currentConfig.value,
            output = childVisualScratch,
            edgeCenterY = baseCenterY + flowOffset,
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
                y = safe.translationY + flowOffset + visual.translationY,
            )
        } else {
            rowView?.setFlowTranslationY(flowOffset + visual.translationY)
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
