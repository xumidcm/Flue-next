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
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import android.view.animation.PathInterpolator
import android.os.Build
import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppItemKey
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val NATIVE_DRAG_SETTLE_DURATION_MS = 130L
private const val NATIVE_DRAG_PREVIEW_DURATION_MS = 240L
private const val NATIVE_FOLDER_SETTLE_LAYOUT_WAIT_FRAMES = 4
private const val NATIVE_FOLDER_SETTLE_MAX_WAIT_FRAMES = 60
private val NATIVE_DRAG_SETTLE_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
// Neighbours follow the finger with a critically-damped, non-overshooting
// curve. The old underdamped approximation could cross the target and snap
// back, which looked like a second release animation and made a busy frame
// appear frozen until the next MOVE event.
private val NATIVE_DRAG_PREVIEW_INTERPOLATOR = PathInterpolator(0.18f, 0.75f, 0.25f, 1f)

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
    init {
        // Wear OS sends crown/rotary generic-motion events to the focused View.
        // AndroidView can keep Compose focus after a page transition, so make the
        // native list an explicit focus owner whenever it is active.
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    private val contentVersionGate = NativeAppListContentVersionGate()
    private var currentConfig =
        NativeAppListResolvedConfig.from(NativeAppListConfig())
    private var lastSubmittedConfig: NativeAppListConfig? = null
    private var transitionRasterCacheRequested = false

    /** Cache GPU rasterization during a parent transform; real child changes still invalidate it. */
    fun setTransitionRasterCacheEnabled(enabled: Boolean) {
        transitionRasterCacheRequested = enabled && !released
        updateTransitionRasterCache()
    }

    private fun updateTransitionRasterCache() {
        val cache = transitionRasterCacheRequested && !released && isAttachedToWindow &&
            isShown && windowVisibility == VISIBLE
        val target = if (cache) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE
        if (layerType != target) setLayerType(target, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTransitionRasterCache()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateTransitionRasterCache()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateTransitionRasterCache()
    }
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
        dragEnabled = { currentConfig.value.dragEnabled },
    )
    private var activeLayoutStrategy: NativeAppListLayoutStrategy =
        LinearNativeAppListLayoutStrategy
    private val childVisualScratch = NativeLinearChildVisual(scale = 1f, alpha = 1f)
    private val roundGeometryScratch = NativeSpatialChildVisual()
    private val roundGeometryAdapter = NativeDrawerGeometryAdapter()
    private var entryAnimator: ValueAnimator? = null
    private var entryAnimationGeneration = 0L
    private var motionBlurSuppressed = false
    /**
     * Bucket currently attached to this RecyclerView's full-content layer.
     * Folder blur progress is sampled every frame, but the effect itself only
     * needs to change when quantization crosses a bucket boundary.
     */
    private var installedContentOverlayBlurBucketPx = Int.MIN_VALUE
    private val restoreMotionBlur = Runnable {
        if (!released && scrollState == SCROLL_STATE_IDLE) {
            setMotionBlurSuppressed(false)
        }
    }
    private val childVisualScrollListener = object : OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            applyAttachedChildVisuals()
            // 滚动中让滑块跟上内容：只失效指示器那一条脏区（[invalidateScrollIndicatorStrip]）
            // 就够 —— 滚动时列表本来每帧都在重绘，脏区搭车零成本；整屏 invalidate 会让每行
            // 重走一遍鱼眼/模糊绘制，滚动期间每帧一次是纯浪费。
            if (currentConfig.value.scrollIndicatorEnabled) invalidateScrollIndicatorStrip()
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == SCROLL_STATE_IDLE) {
                com.flue.launcher.diagnostics.TransitionPerformance.endScene(
                    com.flue.launcher.diagnostics.TransitionPerformance.Scene.SCROLL,
                )
            } else {
                com.flue.launcher.diagnostics.TransitionPerformance.markScroll()
            }
            removeCallbacks(restoreMotionBlur)
            if (newState != SCROLL_STATE_DRAGGING) clearGlidePressed()
            if (newState == SCROLL_STATE_IDLE) {
                // Avoid reattaching multiple RenderEffects on the exact frame
                // a fling settles; let the final composite frame land first.
                postDelayed(restoreMotionBlur, MOTION_BLUR_RESTORE_DELAY_MS)
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
            (view as? NativeAppListRowView)?.let { row ->
                val shouldShowFolderHover = key == folderHoverKey
                row.setFolderHoverState(shouldShowFolderHover)
                if (shouldShowFolderHover) {
                    folderHoverView
                        ?.takeIf { it !== row }
                        ?.setFolderHoverState(false)
                    folderHoverView = row
                }
                row.setGlidePressedState(key == glidePressedKey)
            }
            if (key == draggedSourceKey) view.alpha = 0f
            if (dragPreviewOrder.isNotEmpty()) {
                // Only place the row that just attached. Re-running the full
                // applyDragPreviewTargets(animate = false) here bumped the
                // animation generation and cancelled the in-flight 150ms
                // settle for every visible row — during auto-scroll children
                // attach every couple of frames, so all the reorder motion
                // degraded into 60Hz hard snaps. That was the main visible
                // "whole list convulses" driver.
                postOnAnimation { applyDragPreviewTargetForChild(view) }
            }
        }

        override fun onChildViewDetachedFromWindow(view: View) {
            (view as? NativeAppListRowView)?.let { row ->
                cancelDragPreviewAnimation(row)
                row.setPreviewTranslation(0f, 0f)
                row.setFolderHoverState(false)
                if (folderHoverView === row) folderHoverView = null
                row.setGlidePressedState(false)
            }
        }
    }
    private var released = false
    private var dragTouchOwned = false
    private var interceptedAction = -1
    private var interceptedEventTime = Long.MIN_VALUE
    /** True until RecyclerView sees the real UP/CANCEL after a drag handoff. */
    private var recyclerGestureNeedsTerminal = false
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
    /** The actual row carrying the hover transform, including remove-animation children. */
    private var folderHoverView: NativeAppListRowView? = null
    /** Exact rows blanked before a possibly synchronous folder repository update. */
    private val pendingFolderRemovalViews = linkedMapOf<AppItemKey, NativeAppListRowView>()
    private var glidePressedKey: AppItemKey? = null
    private var autoScrollRemainder = 0f
    private var folderSettleCenterX = Float.NaN
    private var folderSettleCenterY = Float.NaN
    /**
     * 文件夹落位的权威 folder 行 key。
     *
     * 不能只存坐标：previewFolderCommit 采样坐标之后紧接着 submitList 把
     * source/target 行都会在 app + app 合并后被移除，旧 key 和预采坐标都
     * 不能作为落点。必须持有 ViewModel/仓库同步返回的新 folder stable key，
     * 等权威 adapter/layout 提交后重新查坐标。
     */
    private var folderSettleTargetKey: AppItemKey? = null
    private var dragSettleGeneration = 0L
    private data class PreviewAnimation(
        val key: AppItemKey,
        val targetX: Float,
        val targetY: Float,
        val animator: ValueAnimator,
    )
    private val dragPreviewAnimations = mutableMapOf<NativeAppListRowView, PreviewAnimation>()
    private var folderCandidateKey: AppItemKey? = null
    private var dragPreviewVisualFrameUptimeMs = Long.MIN_VALUE
    private var dragPreviewOriginalOrder: List<AppItemKey> = emptyList()
    private var dragPreviewOrder: List<AppItemKey> = emptyList()
    private var hasLastReorderProbe = false
    private var lastReorderProbeX = 0f
    private var lastReorderProbeY = 0f
    private val topFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var topFadeHeight = 0f
    private var bottomFadeHeight = 0f
    /**
     * 指示器的两支笔触。圆屏画弧、方屏画线段都靠同一支 STROKE + ROUND cap 的笔：
     * 圆头描边天然就是「细长胶囊」，不必再走一遍 RoundRect / Path，也避免了圆屏那侧
     * 为了画胶囊状的弧去构造 Path（每帧一次分配，手表上是实打实的成本）。
     */
    private val scrollIndicatorTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scrollIndicatorThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    /** 圆屏 drawArc 的外接矩形。复用一个实例：dispatchDraw 里不能每帧 new RectF。 */
    private val scrollIndicatorArcBounds = android.graphics.RectF()

    /**
     * 淡入淡出的状态。时序逻辑在 [nativeScrollIndicatorAdvanceAlpha]（纯函数、可单测），
     * 这里只存「当前到哪了」和两个时间戳。
     *
     * 基准 alpha 单独记一份，是因为笔触颜色里已经烘进了轨道/滑块各自的固定透明度
     * （0.16 / 0.72）。淡出时要按比例缩这两个值，直接写 paint.alpha 会把基准冲掉，
     * 下一帧就没有还原的依据了。
     */
    private var scrollIndicatorAlpha = 0f
    private var scrollIndicatorTrackBaseAlpha = 0
    private var scrollIndicatorThumbBaseAlpha = 0
    private val scrollIndicatorController = NativeScrollIndicatorController()
    private var indicatorFocusLost = false
    private var scrollIndicatorLoopScheduled = false
    private val scrollIndicatorHoldEnd = Runnable {
        if (indicatorCanRun()) {
            applyScrollIndicatorResult(scrollIndicatorController.onFrame(SystemClock.uptimeMillis()))
        } else {
            resetScrollIndicatorState()
        }
    }
    private var pullOffsetPx = 0f
    private val springEdgeEffects = FlueSpringEdgeEffectFactory(
        owner = this,
        onOffsetChanged = ::setPullOffset,
        onEdgeReached = { performEdgeHaptic() },
    )

    /**
     * 触顶 / 触底的一次反馈。
     *
     * 30+ 用 `GESTURE_THRESHOLD_ACTIVATE` —— 它就是为「越过一个边界」设计的短促一下；
     * 老系统退回 `CLOCK_TICK`（本文件的滚动与长按也在用同一族，手感一致）。
     */
    private fun performEdgeHaptic() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        performHapticFeedback(constant)
    }

    /** Returns true when the command owner accepted the request. */
    var onCommand: ((AppListCommand) -> Boolean)? = null
    /**
     * Folder creation is the one drag command that needs a return payload:
     * the repository-owned stable key of the resulting folder row.
     */
    var onCreateFolder: ((AppListCommand.CreateFolder) -> AppItemKey?)? = null
    var onScrollToTop: (() -> Unit)? = null

    private fun onNativeAdapterListCommitted(itemCount: Int) {
        (layoutManager as? NativeSpatialAppListLayoutManager)
            ?.onAdapterItemCountCommitted(itemCount)
        refreshAttachedCompositedContent()
        invalidate()
        postOnAnimation {
            if (released) return@postOnAnimation
            refreshAttachedCompositedContent()
            invalidate()
        }
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
        // 高程阴影画在子 View 边界之外；父容器默认裁剪会把衰减尾巴切平。
        // Compose 版的阴影自然是因为 AndroidComposeView 不裁剪子项。
        clipChildren = false
        adapter = nativeAdapter
        addOnScrollListener(childVisualScrollListener)
        addOnChildAttachStateChangeListener(childVisualAttachListener)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val timing = com.flue.launcher.diagnostics.TransitionPerformance.start()
        try {
        springEdgeEffects.updateViewport(bottom - top)
        updateViewportPadding(bottom - top)
        super.onLayout(changed, left, top, right, bottom)
        if (dragOverlayView != null && dragController.isDragging) {
            dragHost.updateDrag(draggedPointerX, draggedPointerY)
        }
        applyAttachedChildVisuals()
        } finally {
            com.flue.launcher.diagnostics.TransitionPerformance.finish(
                com.flue.launcher.diagnostics.TransitionPerformance.Stage.NATIVE_LAYOUT, timing,
            )
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateEdgeFadeShaders()
        // 笔触本身只跟密度和主题色有关，挂在这里是为了拿到「一定在首帧绘制之前」的时机：
        // applyConfig 只在检测到差异时才刷笔触，而首次 applyConfig 的 previous 是默认
        // 配置，颜色恰好相同时就一次都不刷，指示器会带着零宽描边画出空白。
        updateScrollIndicatorPaints()
        // 进入列表（以及尺寸变化）时闪一下指示器再淡出：watchOS 就是这个行为，
        // 而且它保住了「刚进来就知道自己在整列的哪里」这一份信息 —— 纯粹等用户滚
        // 才出现的话，进场那一眼是没有位置感的。
        markScrollIndicatorActivity()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val timing = com.flue.launcher.diagnostics.TransitionPerformance.start()
        try {
        super.dispatchDraw(canvas)
        drawEdgeFadeMask(canvas)
        // 指示器画在遮罩**之后**：遮罩是贴着上下边缘的渐变，而条的两端正好伸进那两条带子，
        // 顺序反过来会被渐变洗掉一截，看起来像条被咬了两口。
        //
        // 现在的默认接线里遮罩其实是 Compose 侧的兄弟层（见 LauncherActivity 的
        // `drawEdgeFadeMaskInNativeView = false`），所以这个顺序只在遮罩被切回视图内时
        // 才真正生效；仍然按正确顺序摆着，免得哪天切回来又要重新踩一遍。
        drawScrollIndicator(canvas)
        } finally {
            com.flue.launcher.diagnostics.TransitionPerformance.finish(
                com.flue.launcher.diagnostics.TransitionPerformance.Stage.NATIVE_DRAW, timing,
            )
        }
    }

    private fun drawEdgeFadeMask(canvas: Canvas) {
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

    /**
     * 屏幕右侧的滑动进度指示器。
     *
     * ## 为什么滚动量取自 `computeVerticalScroll*` 而不是子 View 的位置
     *
     * 五种布局的行都带着鱼眼缩放、入场位移、圆屏收边位移，变换后的边界不是布局值；从那里
     * 反推进度就是「缩放 → 边界变 → 进度变 → 重画 → 缩放」的帧率级回路（和
     * `ChordScale.kt` 铁律 1 同源的坑）。五个 LayoutManager 都把这三个量重写成了精确的
     * 布局空间值（星环 / 心流继承网格基类那份），所以这条路对**所有** presentation
     * 一视同仁 —— 这里没有 `when (presentation)`，新增布局样式不需要改这里。
     *
     * ## 滚动时淡入、停下淡出
     *
     * 对齐 watchOS 那条指示器的行为（老师要求「和 Apple Watch 一样」）。时序在
     * [nativeScrollIndicatorAdvanceAlpha] 里，是纯函数所以能单测；这里只负责把算出来的
     * 不透明度乘到两支笔触的基准 alpha 上。完全透明时直接 return，省掉一次 drawArc/drawLine。
     *
     * 内容不足一屏时本来就不画（见 [nativeScrollIndicatorState]），不会出现一条永远满格的杠。
     */
    private fun drawScrollIndicator(canvas: Canvas) {
        val config = currentConfig.value
        if (!config.scrollIndicatorEnabled) {
            // 开关关掉时把淡出状态一起清零，否则重新打开会接着上次的半透明往下淡。
            return
        }
        val state = nativeScrollIndicatorState(
            scrollOffsetPx = computeVerticalScrollOffset(),
            scrollRangePx = computeVerticalScrollRange(),
            viewportHeightPx = computeVerticalScrollExtent(),
        )
        if (!state.visible) {
            return
        }
        if (scrollIndicatorThumbPaint.strokeWidth <= 0f) return
        val fade = scrollIndicatorAlpha
        if (fade <= 0f) return
        scrollIndicatorTrackPaint.alpha = (scrollIndicatorTrackBaseAlpha * fade).roundToInt()
        scrollIndicatorThumbPaint.alpha = (scrollIndicatorThumbBaseAlpha * fade).roundToInt()
        val density = resources.displayMetrics.density
        val thicknessPx = NATIVE_SCROLL_INDICATOR_THICKNESS_DP * density
        val edgeMarginPx = NATIVE_SCROLL_INDICATOR_EDGE_MARGIN_DP * density
        when (config.deviceShape) {
            NativeDeviceShape.ROUND -> {
                val radius = nativeScrollIndicatorArcRadiusPx(
                    viewportWidthPx = width.toFloat(),
                    viewportHeightPx = height.toFloat(),
                    edgeMarginPx = edgeMarginPx,
                    thicknessPx = thicknessPx,
                )
                if (radius <= 0f) return
                val centerX = width / 2f
                val centerY = height / 2f
                scrollIndicatorArcBounds.set(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius,
                )
                val track = nativeScrollIndicatorArcTrack(NATIVE_SCROLL_INDICATOR_ARC_SWEEP_DEG)
                val thumb = nativeScrollIndicatorThumbSpan(
                    track = track,
                    visibleFraction = state.visibleFraction,
                    progress = state.progress,
                    minimumExtent = NATIVE_SCROLL_INDICATOR_MIN_THUMB_DEG,
                )
                canvas.drawArc(
                    scrollIndicatorArcBounds,
                    track.start,
                    track.extent,
                    false,
                    scrollIndicatorTrackPaint,
                )
                canvas.drawArc(
                    scrollIndicatorArcBounds,
                    thumb.start,
                    thumb.extent,
                    false,
                    scrollIndicatorThumbPaint,
                )
            }

            NativeDeviceShape.RECTANGULAR -> {
                val centerX = nativeScrollIndicatorBarCenterXPx(
                    viewportWidthPx = width.toFloat(),
                    edgeMarginPx = edgeMarginPx,
                    thicknessPx = thicknessPx,
                )
                if (centerX <= 0f) return
                val track = nativeScrollIndicatorBarTrack(
                    viewportHeightPx = height.toFloat(),
                    verticalInsetFraction = NATIVE_SCROLL_INDICATOR_BAR_INSET_FRACTION,
                )
                if (track.extent <= 0f) return
                val thumb = nativeScrollIndicatorThumbSpan(
                    track = track,
                    visibleFraction = state.visibleFraction,
                    progress = state.progress,
                    minimumExtent = NATIVE_SCROLL_INDICATOR_MIN_THUMB_DP * density,
                )
                // 圆头描边的两端各鼓出半个线宽，所以线段端点要往内收半宽，否则胶囊的
                // 圆头会超出轨道范围（滚到底时表现为滑块比轨道长一点）。
                val capInset = thicknessPx / 2f
                canvas.drawLine(
                    centerX,
                    track.start + capInset,
                    centerX,
                    track.end - capInset,
                    scrollIndicatorTrackPaint,
                )
                canvas.drawLine(
                    centerX,
                    thumb.start + capInset,
                    centerX,
                    thumb.end - capInset,
                    scrollIndicatorThumbPaint,
                )
            }
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
        if (released) return super.dispatchTouchEvent(event)
        if (!currentConfig.value.active) return handleInactiveTouchStream(event)
        updateGesturePointer(event)
        prepareLegacyEdgePointer(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The pointer window opens here — dispatchTouchEvent is the only
                // funnel guaranteed to see every sample of every stream.
                // onInterceptTouchEvent early-returns while inactive and
                // onTouchEvent only runs for self-owned streams, so both used to
                // drop terminal samples and leak the window forward.
                springEdgeEffects.beginGesture()
                // 指示器按下即淡入 —— 不再等「内容真的移动」。已经到顶/到底还在拖的
                // 时候内容不动，原来那条 onScrolled 触发条件就一次都不成立。
                if (indicatorCanRun()) applyScrollIndicatorResult(
                    scrollIndicatorController.onPointerDown(SystemClock.uptimeMillis()),
                )
            }
            MotionEvent.ACTION_POINTER_DOWN ->
                // A pointer-down can be delivered to the currently touched row
                // without another parent interception pass. Cancel at the outer
                // boundary so the delayed long-press callback cannot fire later.
                dragController.onAdditionalPointer()
        }
        // Sampled before super: the terminal dispatch below must judge the
        // pull-to-face commit against the state the gesture *arrived* with,
        // not against flags the drag controller just reset while handling it.
        val ownedBeforeTerminal = dragTouchOwned || dragController.isDragging
        val handled = super.dispatchTouchEvent(event)
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            val canCommitFace =
                event.actionMasked == MotionEvent.ACTION_UP && !ownedBeforeTerminal
            if (springEdgeEffects.endGesture(terminalUp = canCommitFace)) {
                onScrollToTop?.invoke()
            }
            // 松手把指示器的保持窗口重新起算：延迟是「松手之后」的，不是「内容最后
            // 一次移动之后」的。
            val indicatorResult = scrollIndicatorController.onPointerUp(SystemClock.uptimeMillis())
            if (indicatorCanRun()) applyScrollIndicatorResult(indicatorResult) else resetScrollIndicatorState()
            dragController.finishAdditionalPointer()
            resetLegacyEdgeGesture()
        }
        return handled
    }

    /**
     * Stream handling while the scene is inactive (screen transitions).
     *
     * Children must not see any of it — an invisible list that still owns
     * zIndex during the closing transition was clickable and scrollable for up
     * to 2.5s ("watch face on screen, finger scrolls the hidden list"). The
     * events are also left unconsumed so the Compose navigation layer above
     * can act on them. RecyclerView's interceptor still observes DOWN and the
     * terminal so its touch anchors (scroll pointer id, last touch Y) stay
     * fresh: a DOWN landing mid-transition used to leave stale anchors behind,
     * and the first real gesture after reactivation settled against them —
     * "interrupt the open animation and the list refuses to scroll".
     */
    private fun handleInactiveTouchStream(event: MotionEvent): Boolean {
        updateGesturePointer(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopScroll()
                super.onInterceptTouchEvent(event)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                super.onInterceptTouchEvent(event)
                springEdgeEffects.endGesture(terminalUp = false)
                dragController.finishAdditionalPointer()
                resetLegacyEdgeGesture()
                clearGlidePressed()
                clearGesturePointer()
                // 手势被转场截断时 UP 走的是这里而不是上面 active 的分支 —— 不复位
                // 的话触摸状态会残留，目标不透明度恒 1，
                // 指示器再也淡不出去（老师报的「经常不自动隐藏、有时又正常」正是
                // 取决于这次手势有没有被截断）。
                resetScrollIndicatorState()
            }
        }
        return false
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        indicatorFocusLost = !hasWindowFocus
        if (!hasWindowFocus) {
            cancelActiveDrag()
            finishOwnedTouch(scheduleMotionBlurRestore = false)
            clearGesturePointer()
            clearGlidePressed()
            springEdgeEffects.endGesture(terminalUp = false)
            resetScrollIndicatorState()
        }
    }

    override fun onDetachedFromWindow() {
        if (layerType != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null)
        // A synchronous owner update can detach the list before the folder
        // dispatch completion returns. Never leave rows blank across a later
        // re-attach; the stable-key guard prevents touching a recycled View.
        completePendingFolderRemovalVisuals(accepted = false)
        cancelActiveDrag()
        cancelDragPreviewAnimations()
        clearDragPreviewTranslations(animate = false)
        folderCandidateKey = null
        cancelDragSettle(removeOverlay = true)
        springEdgeEffects.endGesture(terminalUp = false)
        springEdgeEffects.finish()
        finishOwnedTouch(scheduleMotionBlurRestore = false)
        removeCallbacks(restoreMotionBlur)
        // 摘出窗口时事件流随之断掉，UP 不会再来了；不清标志的话重挂后指示器
        // 会带着恒 1 的目标一直亮着。
        resetScrollIndicatorState()
        cancelEntryAnimation(resetProgress = true)
        super.onDetachedFromWindow()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (released || !currentConfig.value.active) return false
        updateGesturePointer(event)
        prepareLegacyEdgePointer(event)
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
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                // A consumed *terminal* sample must never latch dragTouchOwned:
                // the ViewGroup only sends the child a CANCEL for an intercepted
                // terminal and never routes it back through onTouchEvent, so
                // there is no later event to clear the latch — the entire next
                // gesture used to be swallowed by the owned branch ("long-press
                // an icon and the list stalls for one full swipe"). Return true
                // so the child still sees CANCEL instead of a click, but finish
                // the gesture accounting in place.
                finishRecyclerGesture(event)
                dragController.finishAdditionalPointer()
                finishOwnedTouch()
                return true
            }
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
        // 这条链路里 RecyclerView 从没拿到过真正的 UP/CANCEL（拦截在上面就返回了），
        // mScrollState 会一直停在 DRAGGING。强制回 IDLE，否则一切「按滚动状态判断」的
        // 逻辑都会以为列表还在被拖着。此时不可能有惯性在跑 —— 拖拽被消费的那一帧
        // 已经 stopScroll() 过了，所以这里不会掐掉正常的甩动。
        stopScroll()
        recyclerGestureNeedsTerminal = false
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
        // The repository snapshot is authoritative. Blank attached rows whose
        // keys disappeared before AsyncListDiffer starts its layout hand-off;
        // otherwise their old display lists can survive in RecyclerView's
        // disappearing/hardware layer (notably a dissolved folder icon).
        val nextKeys = content.rows.asSequence().map { it.stableKey }.toHashSet()
        nativeAdapter.currentKeysView()
            .asSequence()
            .filterNot(nextKeys::contains)
            .forEach { removedKey ->
                (holderForKey(removedKey)?.itemView as? NativeAppListRowView)
                    ?.commitFolderRemoval()
            }
        invalidate()
        nativeAdapter.submitContent(content, dragController::reconcileCurrentKeys)
        return true
    }

    fun setConfig(config: NativeAppListConfig) {
        if (released) return
        // Compose can resubmit the same immutable inputs on an unrelated update.
        // Skip normalization/allocation before touching row or layout state.
        if (lastSubmittedConfig == config) return
        lastSubmittedConfig = config
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
        val activeChanged = previous.active != next.value.active
        val edgeFadeChanged = hasDifferentNativeEdgeFade(previous, next.value)
        val contentOverlayEffectChanged =
            hasDifferentNativeContentOverlayEffect(previous, next.value)
        val scrollIndicatorChanged = hasDifferentNativeScrollIndicator(previous, next.value)
        val motionProfileChanged = previous.motionProfile != next.value.motionProfile
        val resetInitialScroll =
            previous.initialResetToken != next.value.initialResetToken
        // 闸门中途关闭（长按弹出菜单/打开文件夹）时手指往往还没抬起：
        // 不在这里终止，overlay 底下的拖拽会继续走，图标会粘在背景上。
        val dragGateClosed = previous.dragEnabled && !next.value.dragEnabled
        if (dragGateClosed && dragController.isActive) {
            cancelActiveDrag(externalChange = true)
        }
        if ((geometryChanged || resetInitialScroll) && dragController.isActive) {
            // Geometry or presentation changed under a live gesture. The finger
            // may still be down: restoring the row's own long-click here would
            // let its CheckForTap re-arm and pop the actions menu at stale
            // coordinates, so keep the suppression until the real terminal.
            cancelActiveDrag(externalChange = true)
        }
        if (geometryChanged) {
            (layoutManager as? NativeSpatialAppListLayoutManager)?.markGeometryDirty()
        }
        currentConfig = next
        if (next.value.active && (!previous.active || !hasFocus())) {
            post {
                if (!released && currentConfig.value.active) requestFocus()
            }
        }
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
                // 入场动画还在飞的时候被打断（进列表途中反向滑回表盘，active
                // 立刻变 false）不能把进度直接掐到 1：每一行会从半空瞬移到
                // 终点位置与不透明度——这就是「进入应用列表时打断会闪一下」。
                // 下面那条 entryAnimationWasRunning 分支本来就是为了保护
                // 飞行中的动画，但 !active 在 when 里排在它前面，打断时永远
                // 先命中，保护形同虚设。
                //
                // 冻结在当前进度即可：整层此刻正在淡出 + 缩小，冻结看不出来，
                // 瞬移看得出来。再次进入 Apps 会 bump entryAnimationToken 从
                // 0 重播；从应用返回那条不 bump 的路径会落到下面的 else，
                // 用宿主的 entryProgress（恒为 1f）恢复，不会卡在半途。
                next.value.runtimeEntryProgress = if (entryAnimationWasRunning) {
                    previousEntryProgress
                } else {
                    1f
                }
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
            // here; a later UP from the old stream must never navigate. The
            // finger may still be down, so keep the source row's long-click
            // suppressed until the real terminal sample.
            springEdgeEffects.endGesture(terminalUp = false)
            resetLegacyEdgeGesture()
            clearGlidePressed()
            cancelActiveDrag(externalChange = true)
            // UP 完全没送达的兜底（有的转场会把整条事件流截走）：列表一旦转
            // inactive，按压标志、延迟任务和 Choreographer 回调都必须一起作废。
            resetScrollIndicatorState()
        }
        nativeAdapter.setConfig(next)
        if (activeChanged || shouldStartEntryAnimation) {
            // A move animator can be paused while the Apps screen is outside
            // the composition. Reset it before reusing attached stable-id rows;
            // otherwise an entire translated row may reappear on the backdrop.
            // The same reset is needed for a fresh entry token while active
            // remains true: Compose keeps this host mounted between transitions.
            itemAnimator?.endAnimations()
            resetAttachedChildVisuals()
            refreshAttachedCompositedContent()
            invalidate()
        }
        if (rowRenderingChanged) {
            applyConfigToAttachedRows(next)
            dragOverlayView?.applyConfig(next)
        }
        if (edgeFadeChanged) updateEdgeFadeShaders()
        if (scrollIndicatorChanged) {
            if (!next.value.scrollIndicatorEnabled) resetScrollIndicatorState()
            updateScrollIndicatorPaints()
        }
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
        resetScrollIndicatorState()
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
        setTransitionRasterCacheEnabled(false)
        if (released) return
        completePendingFolderRemovalVisuals(accepted = false)
        cancelActiveDrag()
        cancelDragSettle(removeOverlay = true)
        cancelDragPreviewAnimations()
        dragPreviewOriginalOrder = emptyList()
        dragPreviewOrder = emptyList()
        springEdgeEffects.releaseOwner()
        finishOwnedTouch()
        cancelEntryAnimation(resetProgress = true)
        dragController.release()
        cancelDragPreviewAnimations()
        released = true
        resetScrollIndicatorState()
        removeCallbacks(restoreMotionBlur)
        stopScroll()
        removeOnScrollListener(childVisualScrollListener)
        removeOnChildAttachStateChangeListener(childVisualAttachListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(null)
            installedContentOverlayBlurBucketPx = 0
        }
        onCommand = null
        onCreateFolder = null
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
        if (scheduleMotionBlurRestore) postDelayed(restoreMotionBlur, MOTION_BLUR_RESTORE_DELAY_MS)
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun cancelActiveDrag(externalChange: Boolean = false) {
        if (externalChange) {
            dragController.invalidateForExternalChange()
        } else {
            dragController.onCancel()
        }
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
        if (usesHostChildVisualPass(activeLayoutStrategy)) {
            // 单遍完成「写 contentTranslationY + 重算行视觉」：鱼眼/弦宽都是
            // 行心 y（含 contentTranslationY）的函数，必须先写后算。原来是
            // 三遍全 child 扫描（写位移、LM 重放、host 重放）+ 双份失效，
            // 回弹动画期间每帧白跑两遍。
            for (index in 0 until childCount) {
                val child = getChildAt(index) ?: continue
                (child as? NativeAppListRowView)?.setContentTranslationY(next)
                applyChildVisual(child)
            }
            draggedSourceKey?.let { key ->
                holderForKey(key)?.itemView?.alpha = 0f
            }
        } else {
            // 空间布局的行视觉由 LayoutManager 重放；contentTranslationY 先行写入。
            for (index in 0 until childCount) {
                (getChildAt(index) as? NativeAppListRowView)
                    ?.setContentTranslationY(next)
            }
            (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
        }
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
                centerX = child.iconCenterXInParentForHitTest(),
                centerY = child.iconCenterYInParentForHitTest(),
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
                row.iconCenterXInParentForHitTest(),
                row.iconCenterYInParentForHitTest(),
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

    /**
     * Snap-places one newly attached row into its preview slot without
     * touching the shared animator or its generation counter, so the settle
     * animation of every other row keeps running.
     */
    private fun applyDragPreviewTargetForChild(view: View) {
        if (released) return
        val original = dragPreviewOriginalOrder
        val preview = dragPreviewOrder
        if (original.isEmpty() || preview.size != original.size) return
        val row = view as? NativeAppListRowView ?: return
        if (row.parent !== this) return
        val holder = getChildViewHolder(row) as?
            NativeAppListAdapter.AppItemViewHolder ?: return
        val key = holder.boundKeyOrNull() ?: return
        if (dragPreviewAnimations[row]?.key == key || folderCandidateKey == key) return
        val originalPosition = original.indexOf(key)
        val previewPosition = preview.indexOf(key)
        if (originalPosition < 0 || previewPosition < 0) return
        if (originalPosition == previewPosition) {
            row.setPreviewTranslation(0f, 0f)
            return
        }
        val origin = NativeDragPreviewSlot(
            row.iconCenterXInParentForHitTest(),
            row.iconCenterYInParentForHitTest(),
        )
        var destination: NativeDragPreviewSlot? = null
        for (index in 0 until childCount) {
            val other = getChildAt(index) as? NativeAppListRowView ?: continue
            val otherHolder = getChildViewHolder(other) as?
                NativeAppListAdapter.AppItemViewHolder ?: continue
            if (otherHolder.bindingAdapterPosition == previewPosition) {
                destination = NativeDragPreviewSlot(
                    other.iconCenterXInParentForHitTest(),
                    other.iconCenterYInParentForHitTest(),
                )
                break
            }
        }
        val target = destination ?: fallbackDragPreviewSlot(
            origin = origin,
            positionDelta = previewPosition - originalPosition,
        )
        row.setPreviewTranslation(
            target.centerX - origin.centerX,
            target.centerY - origin.centerY,
        )
        applyChildVisual(row)
    }

    private fun fallbackDragPreviewSlot(
        origin: NativeDragPreviewSlot,
        positionDelta: Int,
    ): NativeDragPreviewSlot {
        val config = currentConfig.value
        val distance = when (config.presentation) {
            NativeAppListPresentation.LINEAR -> config.rowHeightPx.toFloat()
            NativeAppListPresentation.HONEYCOMB ->
                config.honeycombCellSizePx.takeIf { it > 0f }
                    ?: config.iconSizePx.toFloat()
            NativeAppListPresentation.FLOW ->
                config.flowCellSizePx.takeIf { it > 0f }
                    ?: config.iconSizePx.toFloat()
            NativeAppListPresentation.RING ->
                config.ringCellSizePx.takeIf { it > 0f }
                    ?: config.iconSizePx.toFloat()
            NativeAppListPresentation.DENSE_GRID ->
                config.denseGridMinimumItemHeightPx
                    .coerceAtLeast(config.iconSizePx.toFloat())
        }
        // 目标格没挂到窗口上（在屏幕外）时只能按索引差估算落点。网格布局的索引差
        // 同时含行与列：以前一律折算成纯纵向位移，星环/网格上「往右挪一格」的目标
        // 会被摆到正下方一格 —— 预览位移方向错了，观感就是图标乱跳、赖在原地。
        // 按列数拆成 行差×列差，列差走横向；LINEAR 单列，行为与从前一致。
        // （蜂窝的宽窄行不是严格网格，这里的拆法是近似，但恒优于只走纵向。）
        val columns = when (config.presentation) {
            NativeAppListPresentation.LINEAR -> 1
            NativeAppListPresentation.HONEYCOMB ->
                config.honeycombNarrowColumns.coerceAtLeast(1)
            NativeAppListPresentation.FLOW -> config.flowColumns.coerceAtLeast(1)
            NativeAppListPresentation.RING -> config.honeycombNarrowColumns.coerceAtLeast(1)
            NativeAppListPresentation.DENSE_GRID -> config.denseGridColumns.coerceAtLeast(1)
        }
        val rowDelta = positionDelta.floorDiv(columns)
        val columnDelta = positionDelta.mod(columns)
        val horizontalDistance = when (config.presentation) {
            NativeAppListPresentation.DENSE_GRID ->
                config.denseGridItemSpacingPx + config.denseGridMinimumItemWidthPx
            else -> distance
        }
        return NativeDragPreviewSlot(
            centerX = origin.centerX + columnDelta * horizontalDistance,
            centerY = origin.centerY + rowDelta * distance,
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

    private fun cancelDragPreviewAnimation(row: NativeAppListRowView) {
        dragPreviewAnimations.remove(row)?.animator?.let {
            it.removeAllUpdateListeners()
            it.removeAllListeners()
            it.cancel()
        }
    }

    private fun cancelDragPreviewAnimations() {
        dragPreviewAnimations.keys.toList().forEach(::cancelDragPreviewAnimation)
    }

    private fun refreshDragPreviewFrame() {
        val now = AnimationUtils.currentAnimationTimeMillis()
        if (now != dragPreviewVisualFrameUptimeMs) {
            dragPreviewVisualFrameUptimeMs = now
            applyMoveFrameVisuals()
        }
        invalidate()
    }

    private fun runDragPreviewAnimation(moves: List<NativeDragPreviewMove>, animate: Boolean) {
        moves.forEach { move ->
            val row = move.row
            val key = row.boundStableKeyOrNull() ?: return@forEach
            val running = dragPreviewAnimations[row]
            if (animate && running?.key == key &&
                abs(running.targetX - move.targetX) <= 0.01f &&
                abs(running.targetY - move.targetY) <= 0.01f
            ) return@forEach
            cancelDragPreviewAnimation(row)
            if (!animate || (abs(move.targetX - move.startX) <= 0.01f &&
                    abs(move.targetY - move.startY) <= 0.01f)) {
                row.setPreviewTranslation(move.targetX, move.targetY)
                return@forEach
            }
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = NATIVE_DRAG_PREVIEW_DURATION_MS
                interpolator = NATIVE_DRAG_PREVIEW_INTERPOLATOR
                addUpdateListener {
                    if (released || row.boundStableKeyOrNull() != key) return@addUpdateListener
                    val fraction = it.animatedFraction
                    row.setPreviewTranslation(
                        move.startX + (move.targetX - move.startX) * fraction,
                        move.startY + (move.targetY - move.startY) * fraction,
                    )
                    refreshDragPreviewFrame()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (dragPreviewAnimations[row]?.animator !== animation) return
                        dragPreviewAnimations.remove(row)
                        if (row.boundStableKeyOrNull() == key) {
                            row.setPreviewTranslation(move.targetX, move.targetY)
                            refreshDragPreviewFrame()
                        }
                    }
                })
            }
            dragPreviewAnimations[row] = PreviewAnimation(key, move.targetX, move.targetY, animator)
            animator.start()
            if (key == folderCandidateKey) animator.pause()
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

        override fun beginDrag(key: AppItemKey, pointerX: Float, pointerY: Float) {
            beginDrag(
                key = key,
                pointerX = pointerX,
                pointerY = pointerY,
                downX = pointerX,
                downY = pointerY,
            )
        }

        override fun keyAt(x: Float, y: Float): AppItemKey? {
            return keyAtPoint(x, y)
        }

        override fun reorderKeyAt(x: Float, y: Float): AppItemKey? {
            // Reorder follows the current pointer sample against fixed layout
            // slots. The overlay intentionally starts without a visual jump on
            // the threshold-crossing MOVE, so using its center here would keep
            // the hit probe in the source slot until a later MOVE and make
            // adjacent icons stick together at the midpoint.
            val probeX = x
            val probeY = y
            val directionX = if (hasLastReorderProbe) probeX - lastReorderProbeX else 0f
            val directionY = if (hasLastReorderProbe) probeY - lastReorderProbeY else 0f
            hasLastReorderProbe = true
            lastReorderProbeX = probeX
            lastReorderProbeY = probeY
            if (dragPreviewOrder.isNotEmpty()) {
                // During a drag the hit test must run against the *fixed layout
                // slots* (preview-exclusive coordinates), then map slot → the
                // key currently displayed there. Both keyAtPoint and
                // findNearestVisiblePosition read translation-inclusive view
                // coordinates, so a reorder's own preview displacement became
                // the next hit test's input — a frame-rate feedback loop that
                // read as the whole list convulsing while dragging.
                return dragPreviewOrder.getOrNull(
                    slotIndexAtPoint(
                        x = probeX,
                        y = probeY,
                        directionX = directionX,
                        directionY = directionY,
                    ),
                )
            }
            keyAtPoint(probeX, probeY)?.let { return it }
            val manager = layoutManager as? NativeSpatialAppListLayoutManager ?: return null
            val excludedPosition = draggedSourceKey
                ?.let(::holderForKey)
                ?.bindingAdapterPosition
                ?: -1
            val position = manager.findNearestVisiblePosition(
                x = probeX,
                y = probeY,
                maximumDistancePx = nativeSpatialReorderRadiusPx(currentConfig.value),
                excludedPosition = excludedPosition,
            )
            return nativeAdapter.keyAtPositionOrNull(position)
        }

        /**
         * Nearest attached adapter slot by its *layout* centre — deliberately
         * ignoring preview/flow translations so the answer is stable while the
         * preview animates. Returns -1 when no adapter child is attached.
         */
        private fun slotIndexAtPoint(
            x: Float,
            y: Float,
            directionX: Float,
            directionY: Float,
        ): Int {
            var bestPosition = -1
            var bestDistance = Float.MAX_VALUE
            var bestCenterX = 0f
            var bestCenterY = 0f
            var sourcePosition = -1
            var sourceDistance = Float.MAX_VALUE
            var sourceCenterX = 0f
            var sourceCenterY = 0f

            fun isPreferredCandidate(
                candidateDistance: Float,
                currentDistance: Float,
                candidateCenterX: Float,
                candidateCenterY: Float,
                currentCenterX: Float,
                currentCenterY: Float,
            ): Boolean = candidateDistance < currentDistance ||
                nativeReorderTiePrefersForwardCandidate(
                    candidateDistance = candidateDistance,
                    bestDistance = currentDistance,
                    candidateCenterX = candidateCenterX,
                    candidateCenterY = candidateCenterY,
                    bestCenterX = currentCenterX,
                    bestCenterY = currentCenterY,
                    directionX = directionX,
                    directionY = directionY,
                )

            for (index in 0 until childCount) {
                val row = getChildAt(index) as? NativeAppListRowView ?: continue
                val holder = getChildViewHolder(row) as?
                    NativeAppListAdapter.AppItemViewHolder ?: continue
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) continue
                val centerX = row.iconCenterXInParentForHitTest()
                val centerY = row.iconCenterYInParentForHitTest()
                val distance = hypot(
                    x - centerX,
                    y - centerY,
                )
                val isSourceSlot = dragPreviewOrder.getOrNull(position) == draggedSourceKey
                if (isSourceSlot) {
                    if (isPreferredCandidate(
                            candidateDistance = distance,
                            currentDistance = sourceDistance,
                            candidateCenterX = centerX,
                            candidateCenterY = centerY,
                            currentCenterX = sourceCenterX,
                            currentCenterY = sourceCenterY,
                        )
                    ) {
                        sourceDistance = distance
                        sourcePosition = position
                        sourceCenterX = centerX
                        sourceCenterY = centerY
                    }
                } else if (isPreferredCandidate(
                        candidateDistance = distance,
                        currentDistance = bestDistance,
                        candidateCenterX = centerX,
                        candidateCenterY = centerY,
                        currentCenterX = bestCenterX,
                        currentCenterY = bestCenterY,
                    )
                ) {
                    bestDistance = distance
                    bestPosition = position
                    bestCenterX = centerX
                    bestCenterY = centerY
                }
            }

            if (
                sourcePosition >= 0 &&
                bestPosition >= 0 &&
                sourceDistance <= bestDistance +
                    currentConfig.value.iconSizePx.toFloat().coerceAtLeast(1f) * 0.18f
            ) {
                val towardNeighbour =
                    directionX * (bestCenterX - sourceCenterX) +
                        directionY * (bestCenterY - sourceCenterY)
                // Once the source occupies a virtual slot, keep it there while
                // the pointer is stationary or moving deeper into that slot.
                // As soon as the pointer starts back toward the nearest real
                // neighbour, select that neighbour before the pointer crosses
                // the whole gap. This removes the sticky two-icon boundary
                // without making a stationary sample flip the preview back.
                if (towardNeighbour > 0.0001f) return bestPosition
                return sourcePosition
            }
            return bestPosition
        }

        override fun folderKeyAt(x: Float, y: Float): AppItemKey? {
            val overlay = dragOverlayView
            val probeX = overlay?.iconCenterXInParentForDisplay() ?: x
            val probeY = overlay?.iconCenterYInParentForDisplay() ?: y
            val source = draggedSourceKey?.let(nativeAdapter::rowForKey)
                ?: overlay?.boundRowForOverlay()
            fun allowed(row: NativeAppListRowView): Boolean {
                val target = row.boundRowForOverlay() ?: return false
                return target.stableKey != draggedSourceKey &&
                    (source?.let { nativeFolderTargetAllowed(it, target) }
                        ?: (target.entry.type != com.flue.launcher.applist.model.AppItemType.SHORTCUT))
            }
            // Retain the same visible identity through harmless edge jitter.
            folderCandidateKey?.let(::holderForKey)?.itemView
                ?.let { it as? NativeAppListRowView }?.let { row ->
                    if (allowed(row) && row.containsTransformedIconPoint(
                            probeX, probeY, 1f,
                            dragPolicyProfiles.forPresentation(currentConfig.value.presentation).folderJitterPx,
                        )) return row.boundStableKeyOrNull()
                }
            var bestKey: AppItemKey? = null
            var bestDistance = Float.MAX_VALUE
            for (index in childCount - 1 downTo 0) {
                val row = getChildAt(index) as? NativeAppListRowView ?: continue
                if (!allowed(row) || !row.containsTransformedIconPoint(probeX, probeY, 1f)) continue
                val distance = hypot(probeX - row.iconCenterXInParentForDisplay(),
                    probeY - row.iconCenterYInParentForDisplay())
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestKey = row.boundStableKeyOrNull()
                }
            }
            return bestKey
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
            folderKey: AppItemKey?,
        ): Boolean {
            // 只记权威返回的 folder key，不猜时间/名字。旧 host 没有返回
            // key 时，只有现存 folder 行可安全作为兼容迁移代理。
            folderSettleTargetKey = folderKey ?: when {
                nativeAdapter.rowForKey(target)?.entry?.type ==
                    com.flue.launcher.applist.model.AppItemType.FOLDER -> target
                nativeAdapter.rowForKey(source)?.entry?.type ==
                    com.flue.launcher.applist.model.AppItemType.FOLDER -> source
                else -> null
            }
            // Keep a last-resort visual destination for a legacy host or an
            // off-screen authoritative folder.  The exact key is always
            // attempted first after the adapter/layout commit.
            val targetRow = holderForKey(target)?.itemView as? NativeAppListRowView
            folderSettleCenterX = targetRow?.iconCenterXInParentWithoutFlow() ?: Float.NaN
            folderSettleCenterY = targetRow?.iconCenterYInParentWithoutFlow() ?: Float.NaN
            return nativeAdapter.previewFolderCommit(source, target).also { accepted ->
                if (!accepted) {
                    folderSettleTargetKey = null
                    folderSettleCenterX = Float.NaN
                    folderSettleCenterY = Float.NaN
                }
            }
        }

        override fun prepareFolderDispatchVisuals(source: AppItemKey, target: AppItemKey) {
            completePendingFolderRemovalVisuals(accepted = false)
            val sourceIsFolder = nativeAdapter.rowForKey(source)?.entry?.type ==
                com.flue.launcher.applist.model.AppItemType.FOLDER
            val targetIsFolder = nativeAdapter.rowForKey(target)?.entry?.type ==
                com.flue.launcher.applist.model.AppItemType.FOLDER
            val removedKeys = buildList {
                if (!sourceIsFolder || targetIsFolder) add(source)
                if (!targetIsFolder) add(target)
            }
            removedKeys.forEach { key ->
                val row = holderForKey(key)?.itemView as? NativeAppListRowView
                    ?: return@forEach
                pendingFolderRemovalViews[key] = row
                row.setFolderRemovalPending(true)
            }
        }

        override fun completeFolderDispatchVisuals(accepted: Boolean) {
            completePendingFolderRemovalVisuals(accepted)
        }

        override fun dispatchFolder(
            command: AppListCommand.CreateFolder,
        ): NativeFolderDispatchResult {
            // onCreateFolder may synchronously submit a Diff that moves the target
            // holder into RecyclerView's remove-animation layer. Clear the actual
            // transformed View before that happens, not only its old stable key.
            clearFolderHoverVisual()
            val createFolder = onCreateFolder
            if (createFolder != null) {
                val folderKey = createFolder(command)
                return NativeFolderDispatchResult(
                    accepted = folderKey != null,
                    folderKey = folderKey,
                )
            }
            // Direct NativeAppListView users compiled before the typed result
            // callback retain their old boolean behavior.  They intentionally
            // have no guessed folder key, so only the normal cleanup path is
            // available until they migrate to onCreateFolder.
            return NativeFolderDispatchResult(
                accepted = dispatchCommandResult(command),
                folderKey = null,
            )
        }

        override fun discardDragPreviewAwaitingAuthoritative() =
            nativeAdapter.discardDragPreviewAwaitingAuthoritative()

        override fun dispatch(command: AppListCommand): Boolean =
            dispatchCommandResult(command)

        override fun suppressLongClick(key: AppItemKey, suppress: Boolean) {
            holderForKey(key)?.itemView?.isLongClickable = !suppress
        }

        override fun beginDrag(
            key: AppItemKey,
            pointerX: Float,
            pointerY: Float,
            downX: Float,
            downY: Float,
        ) {
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
            hasLastReorderProbe = false
            // The overlay is created on the first threshold-crossing MOVE.
            // Seed the grab offset from that actual pointer sample; using the
            // original DOWN position would apply the threshold delta twice and
            // make the overlay jump before reorder hit testing starts.
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
                // 提交后直接交还给稳定槽位，不再播放归位回弹；RecyclerView
                // 的重排已经提供了最终位置，叠加第二段动画会造成闪回和卡顿。
                dragOverlay.setDragVisualState(false)
                removeDragOverlay(generation, sourceKey, dragOverlay)
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

        override fun setFolderCandidate(key: AppItemKey?) {
            if (folderCandidateKey == key) return
            val previous = folderCandidateKey
            folderCandidateKey = key
            dragPreviewAnimations.values.forEach { motion ->
                if (motion.key == previous) motion.animator.resume()
                if (motion.key == key) motion.animator.pause()
            }
            // A cached row may have become a candidate before its deferred
            // placement ran. Rebuild that missing animation when it is released.
            val previousRow = previous?.let(::holderForKey)?.itemView as? NativeAppListRowView
            if (previousRow != null && dragPreviewAnimations[previousRow] == null &&
                dragPreviewOrder.isNotEmpty()
            ) applyDragPreviewTargets(animate = true)
        }

        override fun setFolderHover(key: AppItemKey?) {
            if (folderHoverKey == key) {
                if (key == null) {
                    clearFolderHoverVisual()
                } else {
                    val currentRow = holderForKey(key)?.itemView as? NativeAppListRowView
                    if (folderHoverView !== currentRow) {
                        folderHoverView?.setFolderHoverState(false)
                        folderHoverView = currentRow
                        currentRow?.setFolderHoverState(true)
                    }
                }
                return
            }
            clearFolderHoverVisual()
            folderHoverKey = key
            key?.let { next ->
                (holderForKey(next)?.itemView as? NativeAppListRowView)?.let { row ->
                    folderHoverView = row
                    row.setFolderHoverState(true)
                }
            }
        }

        override fun scrollBy(deltaPx: Float): Float {
            autoScrollRemainder += deltaPx
            val wholePixels = autoScrollRemainder.roundToInt()
            if (wholePixels == 0) return 0f
            autoScrollRemainder -= wholePixels
            val before = computeVerticalScrollOffset()
            this@NativeAppListView.scrollBy(0, wholePixels)
            return (computeVerticalScrollOffset() - before).toFloat()
        }

        override fun performFolderHaptic() {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        override fun performDragHaptic() {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * Settles one drag generation into the post-command row.  A folder
     * command removes the source/target rows optimistically, then publishes a
     * new folder row asynchronously.  Keep this generation's overlay alive
     * until that stable key has a laid-out holder; never sample the removed
     * target position and never let a stale callback touch the next session.
     */
    private fun settleDragOverlay(
        generation: Long,
        sourceKey: AppItemKey,
        dragOverlay: NativeAppListRowView,
        waitFrame: Int,
        committedWaitFrames: Int,
    ) {
        if (
            released ||
            generation != dragSettleGeneration ||
            dragOverlayView !== dragOverlay
        ) {
            val ownsCurrentSession =
                generation == dragSettleGeneration && dragOverlayView === dragOverlay
            detachDragOverlay(dragOverlay)
            if (ownsCurrentSession) finishDragVisualState(sourceKey)
            return
        }

        val folderKey = folderSettleTargetKey
        val folderKeyCommitted = folderKey != null &&
            nativeAdapter.currentList.any { it.stableKey == folderKey }
        val folderTargetRow = folderKey
            ?.let { holderForKey(it)?.itemView as? NativeAppListRowView }
        // The authoritative content may be in the adapter snapshot one frame
        // before RecyclerView lays out its holder. Give it a few frames before
        // falling back to the last known target geometry.
        if (
            folderKey != null &&
            folderTargetRow == null &&
            (!folderKeyCommitted || committedWaitFrames < NATIVE_FOLDER_SETTLE_LAYOUT_WAIT_FRAMES) &&
            waitFrame < NATIVE_FOLDER_SETTLE_MAX_WAIT_FRAMES
        ) {
            postOnAnimation {
                settleDragOverlay(
                    generation = generation,
                    sourceKey = sourceKey,
                    dragOverlay = dragOverlay,
                    waitFrame = waitFrame + 1,
                    committedWaitFrames = if (folderKeyCommitted) {
                        committedWaitFrames + 1
                    } else {
                        0
                    },
                )
            }
            return
        }

        // For legacy hosts without a typed folder result, sourceKey may be the
        // surviving folder row.  Typed hosts use the exact new folder key
        // above; only if no holder is attached do we use the captured center.
        val destination = holderForKey(sourceKey)?.itemView as? NativeAppListRowView
        val settleRow = folderTargetRow ?: destination
        val destinationCenterX = when {
            settleRow != null -> settleRow.iconCenterXInParentWithoutFlow()
            folderSettleCenterX.isFinite() -> folderSettleCenterX
            else -> Float.NaN
        }
        val destinationCenterY = when {
            settleRow != null -> settleRow.iconCenterYInParentWithoutFlow()
            folderSettleCenterY.isFinite() -> folderSettleCenterY
            else -> Float.NaN
        }
        if (!destinationCenterX.isFinite() || !destinationCenterY.isFinite()) {
            removeDragOverlay(generation, sourceKey, dragOverlay)
            return
        }
        val currentCenterX = dragOverlay.iconCenterXInParentWithoutFlow()
        val currentCenterY = dragOverlay.iconCenterYInParentWithoutFlow()
        var settled = false
        fun settleOnce() {
            if (settled) return
            settled = true
            removeDragOverlay(generation, sourceKey, dragOverlay)
        }
        dragOverlay.animate()
            .translationX(dragOverlay.translationX + destinationCenterX - currentCenterX)
            .translationY(dragOverlay.translationY + destinationCenterY - currentCenterY)
            .setDuration(NATIVE_DRAG_SETTLE_DURATION_MS)
            .setInterpolator(NATIVE_DRAG_SETTLE_INTERPOLATOR)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = settleOnce()

                override fun onAnimationCancel(animation: android.animation.Animator) = settleOnce()
            })
            .start()
    }

    private fun removeDragOverlay(
        generation: Long,
        sourceKey: AppItemKey?,
        dragOverlay: NativeAppListRowView,
    ) {
        if (generation != dragSettleGeneration || dragOverlayView !== dragOverlay) {
            // 这一代已被更新的手势接管：落位动画不该再跑，但浮层本身必须
            // 拆掉。以前直接 return 会把它永久留在 ViewOverlay 上——那层画在
            // 所有内容之上且不随列表滚动，就是「图标粘在背景上」。
            detachDragOverlay(dragOverlay)
            return
        }
        detachDragOverlay(dragOverlay)
        dragOverlayView = null
        finishDragVisualState(sourceKey)
    }

    /**
     * 无条件把拖动浮层从 ViewOverlay 上摘下来。
     *
     * 不看 generation、不看 dragOverlayView 身份：任何路径下这个 View 都不该
     * 留在 overlay 里。幂等，重复调用安全。
     */
    private fun detachDragOverlay(dragOverlay: NativeAppListRowView) {
        // 先摘 listener 再 cancel：落位监听器挂在 ViewPropertyAnimator 上，
        // 留着会在这个 View 被回收复用后的动画上误触发。
        dragOverlay.animate().setListener(null)
        dragOverlay.animate().cancel()
        overlay.remove(dragOverlay)
        dragOverlay.clearBinding()
        if (dragOverlayView === dragOverlay) dragOverlayView = null
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
        hasLastReorderProbe = false
        lastReorderProbeX = 0f
        lastReorderProbeY = 0f
        folderSettleCenterX = Float.NaN
        folderSettleCenterY = Float.NaN
        folderSettleTargetKey = null
        clearFolderHoverVisual()
        sourceKey?.let(::holderForKey)?.itemView?.isPressed = false
        (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
        applyAttachedChildVisuals()
    }

    /** Clears both the indexed holder and any View already moved to the remove layer. */
    private fun clearFolderHoverVisual() {
        val previousView = folderHoverView
        val previousKey = folderHoverKey
        previousView?.setFolderHoverState(false)
        previousKey?.let { key ->
            (holderForKey(key)?.itemView as? NativeAppListRowView)
                ?.takeIf { it !== previousView }
                ?.setFolderHoverState(false)
        }
        folderHoverView = null
        folderHoverKey = null
    }

    private fun completePendingFolderRemovalVisuals(accepted: Boolean) {
        pendingFolderRemovalViews.forEach { (expectedKey, row) ->
            // RecyclerView may synchronously reuse the old source/target View
            // for the newly-created folder before this callback runs. Clearing
            // by View identity alone would then erase the new folder and leave
            // the old icon looking pasted into the remove-animation layer.
            if (row.boundStableKeyOrNull() != expectedKey) return@forEach
            if (accepted) row.commitFolderRemoval() else row.setFolderRemovalPending(false)
        }
        pendingFolderRemovalViews.clear()
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
        // 不含重排预览：圆屏列表的弦宽缩放与安全轮廓收边都是行心 y 的函数，读了预览
        // 位移就成了「预览 → 收边/缩放 → 命中坐标 → 新预览」的帧率级自反馈环，与心流
        // 那条同源（见 NativeAppListRowView.positionTranslationYWithoutPreviewOrFlow）。
        val positionTranslationY =
            rowView?.positionTranslationYWithoutPreviewOrFlow() ?: child.translationY
        val baseCenterY = child.top + child.height / 2f + positionTranslationY
        val visual = nativeLinearChildVisual(
            childCenterY = baseCenterY,
            viewportHeight = height,
            config = currentConfig.value,
            output = childVisualScratch,
            edgeCenterY = baseCenterY,
        )
        // 圆屏列表的「曲线」：整行再乘一次弦宽缩放，行两端于是沿内切圆收进去。
        // 乘而不是取代鱼眼缩放 —— 两者管的是不同的事（鱼眼是焦点强调，弦宽是圆屏裁切），
        // 谁替换谁都会让另一项在圆屏上凭空失效。方屏时 nativeRoundChordScale 恒返回 1f。
        //
        val roundLinearList = currentConfig.value.deviceShape == NativeDeviceShape.ROUND &&
            currentConfig.value.presentation == NativeAppListPresentation.LINEAR
        // A LINEAR round-screen row keeps its layout slot; only the rendered
        // content is scaled to the inscribed chord at that row's centre.
        val chordScale = if (roundLinearList) {
            nativeRoundChordScale(
                childCenterY = baseCenterY,
                viewportWidthPx = width.toFloat(),
                viewportHeightPx = height.toFloat(),
                deviceShape = currentConfig.value.deviceShape,
            )
        } else {
            1f
        }
        val childScale = visual.scale * chordScale
        if (
            rowView != null &&
            currentConfig.value.deviceShape == NativeDeviceShape.ROUND &&
            !roundLinearList
        ) {
            val safe = roundGeometryAdapter.resolve(
                centerX = rowView.iconCenterXInParentWithoutPreviewOrFlow(),
                centerY = rowView.iconCenterYInParentWithoutPreviewOrFlow(),
                viewportWidth = width.toFloat(),
                viewportHeight = height.toFloat(),
                itemWidth = rowView.iconWidthForSafeGeometry(),
                itemHeight = rowView.iconHeightForSafeGeometry(),
                config = currentConfig.value,
                output = roundGeometryScratch,
                // 图标尺寸的两个 getter 只给布局值；鱼眼 / 弦宽把边缘行画小之后，
                // 轮廓仍按 1.0 倍留位会把边缘行多推进来，正是圆屏顶/底重叠的成因之一。
                visualScale = childScale,
            )
            rowView.setFlowTranslation(
                x = safe.translationX,
                y = safe.translationY + visual.translationY,
            )
        } else {
            // LINEAR round-screen rows are already constrained horizontally by
            // the chord scale. Feeding their vertical slot through the spatial
            // safe-outline solver would pull edge rows toward the circle's
            // centre and collapse adjacent list slots during a drag.
            rowView?.setFlowTranslationY(visual.translationY)
        }
        val centerPivotX = child.width / 2f
        val centerPivotY = child.height / 2f
        if (child.pivotX != centerPivotX) child.pivotX = centerPivotX
        if (child.pivotY != centerPivotY) child.pivotY = centerPivotY
        if (rowView != null) {
            rowView.setVisualScale(childScale)
        } else {
            if (child.scaleX != childScale) child.scaleX = childScale
            if (child.scaleY != childScale) child.scaleY = childScale
            if (child.translationY != visual.translationY) {
                child.translationY = visual.translationY
            }
        }
        if (rowView != null) {
            rowView.setHostVisualAlpha(visual.alpha)
        } else if (child.alpha != visual.alpha) {
            child.alpha = visual.alpha
        }
        rowView?.setIconBlurRadiusPx(
            radiusPx = visual.blurRadiusPx,
            tier = effectiveIconBlurTier(),
        )
        rowView?.setIconSaturation(visual.saturation)
    }

    private fun effectiveIconBlurTier(): NativeBlurTier =
        currentConfig.value.effectiveBlurTier()

    private fun setMotionBlurSuppressed(suppressed: Boolean) {
        // On API 31+ the RenderEffect pipeline is fully hardware accelerated
        // and cheap enough to stay attached during scroll. Toggling it there
        // bought nothing and produced both reported symptoms at once: "the
        // edge icons only blur after I stop" (suppression during motion) and
        // "the icons flicker after scrolling" (the whole pipeline flipping
        // back on at fling-settle). Suppression stays available for the
        // legacy capture path below S, where re-blurring per frame is real
        // work.
        if (suppressed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (motionBlurSuppressed == suppressed) return
        motionBlurSuppressed = suppressed
        currentConfig.value.runtimeBlurSuppressed = suppressed
        if (suppressed) {
            // Dropping the effects has to be immediate: a stale blurred parent
            // layer surviving into a fling frame is the whole reason motion
            // suppression exists.
            applyContentOverlayBlur()
            (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
            applyAttachedChildVisuals()
            return
        }
        // Restoring is not urgent, and doing all three in one frame rebuilt the
        // full-screen offscreen layer *and* every per-row RenderEffect at once.
        // On a watch that single frame ran long enough to swallow the next
        // touch stream, which reads as "the edge icons only blur once I stop,
        // and then it won't scroll". Spread the work over two frames and bail
        // out if a new scroll re-suppressed it in the meantime.
        postOnAnimation {
            if (released || motionBlurSuppressed) return@postOnAnimation
            applyContentOverlayBlur()
            postOnAnimation {
                if (released || motionBlurSuppressed) return@postOnAnimation
                (layoutManager as? NativeSpatialAppListLayoutManager)?.reapplyChildVisuals()
                applyAttachedChildVisuals()
            }
        }
    }

    private fun resetAttachedChildVisuals() {
        for (index in 0 until childCount) {
            resetChildVisual(getChildAt(index))
        }
    }

    private fun resetChildVisual(child: View) {
        if (child.scaleX != 1f) child.scaleX = 1f
        if (child.scaleY != 1f) child.scaleY = 1f
        val row = child as? NativeAppListRowView
        if (row != null) row.setHostVisualAlpha(1f) else if (child.alpha != 1f) child.alpha = 1f
        row?.apply {
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
            if (currentConfig.value.edgeAlignEnabled) {
                // 贴边对齐：LIST 不走几何接口（用 LinearLayoutManager 的
                // paddingTop 做首末留白），所以这里直接把半屏留白换成一条贴边留白。
                // 滚动位置 0 就是内容顶部 —— 天然首行贴顶、末行贴底，不需要再算边界。
                currentConfig.value.edgeAlignPaddingPx.roundToInt().coerceAtLeast(0)
            } else {
                ((viewportHeight - currentConfig.value.rowHeightPx) / 2).coerceAtLeast(0)
            }
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
        if (installedContentOverlayBlurBucketPx == bucket) return
        installedContentOverlayBlurBucketPx = bucket
        setRenderEffect(bucket.takeIf { it > 0 }?.let(NativeSystemBlurEffectCache::get))
    }

    private fun refreshAttachedCompositedContent() {
        for (index in 0 until childCount) {
            (getChildAt(index) as? NativeAppListRowView)?.refreshCompositedContent()
        }
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

    /** 指示器使用灰白前景色，不跟随表盘主题色。 */
    private fun updateScrollIndicatorPaints() {
        val color = currentConfig.value.palette.labelColorArgb.toInt()
        val thicknessPx = NATIVE_SCROLL_INDICATOR_THICKNESS_DP * resources.displayMetrics.density
        scrollIndicatorTrackPaint.strokeWidth = thicknessPx
        scrollIndicatorTrackPaint.color =
            color.withAlphaFraction(NATIVE_SCROLL_INDICATOR_TRACK_ALPHA)
        scrollIndicatorThumbPaint.strokeWidth = thicknessPx
        scrollIndicatorThumbPaint.color =
            color.withAlphaFraction(NATIVE_SCROLL_INDICATOR_THUMB_ALPHA)
        // 颜色刚写进去，此刻的 alpha 就是「全不透明状态下该有的那一档」，记作基准。
        scrollIndicatorTrackBaseAlpha = scrollIndicatorTrackPaint.alpha
        scrollIndicatorThumbBaseAlpha = scrollIndicatorThumbPaint.alpha
        invalidate()
    }

    /**
     * 记一次「刚刚滚过」。
     *
     * [onScrolled] 是 RecyclerView 专门留给子类的钩子（默认空实现），比挂
     * `OnScrollListener` 干净：监听器是一份可被外部清掉的列表，而这条指示器不该依赖
     * 谁有没有替我们注册。
     */
    override fun onScrolled(deltaX: Int, deltaY: Int) {
        super.onScrolled(deltaX, deltaY)
        if (deltaX != 0 || deltaY != 0) markScrollIndicatorActivity()
    }

    private fun indicatorCanRun(): Boolean = !released && isAttachedToWindow &&
        !indicatorFocusLost && currentConfig.value.active && currentConfig.value.scrollIndicatorEnabled &&
        computeVerticalScrollRange() > computeVerticalScrollExtent()

    private fun markScrollIndicatorActivity() {
        if (indicatorCanRun()) applyScrollIndicatorResult(
            scrollIndicatorController.onScroll(SystemClock.uptimeMillis()),
        )
    }

    private fun applyScrollIndicatorResult(result: NativeScrollIndicatorController.FrameResult) {
        removeCallbacks(scrollIndicatorHoldEnd)
        if (scrollIndicatorAlpha != result.alpha) {
            scrollIndicatorAlpha = result.alpha
            // Fade frames happen on an otherwise idle list. Invalidate the full
            // display list, including the final frame that erases the indicator.
            invalidate()
        }
        if (result.needsNextFrame) startScrollIndicatorLoop() else cancelScrollIndicatorLoop()
        result.nextWakeUptimeMillis?.let { deadline ->
            postDelayed(scrollIndicatorHoldEnd, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L))
        }
    }

    private fun resetScrollIndicatorState() {
        removeCallbacks(scrollIndicatorHoldEnd)
        cancelScrollIndicatorLoop()
        scrollIndicatorController.reset()
        scrollIndicatorAlpha = 0f
        invalidate()
    }

    /**
     * 滚动中让滑块跟上内容：只失效指示器那一条脏区。
     *
     * 这条路径只配滚动中使用 —— 滚动时列表本来每帧都在重绘，脏区搭车零成本；
     * 静止列表上不要指望它（见 [scrollIndicatorFrameLoop] 的注释）。
     */
    private fun invalidateScrollIndicatorStrip() {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        // 右侧那条的横向占用：边距 + 线宽，再宽松留一个线宽给圆头和抗锯齿。
        val stripWidth = (
            NATIVE_SCROLL_INDICATOR_EDGE_MARGIN_DP + NATIVE_SCROLL_INDICATOR_THICKNESS_DP * 2f
            ) * density
        // 圆屏的弧向左鼓进来最多一个半径的 (1 - cos(sweep/2))，比直条宽；一并算进去
        // 免得弧的中段被裁在脏区之外，表现为淡出时弧“从中间缺一块”。
        val arcBulge = if (currentConfig.value.deviceShape == NativeDeviceShape.ROUND) {
            nativeScrollIndicatorArcRadiusPx(
                viewportWidthPx = width.toFloat(),
                viewportHeightPx = height.toFloat(),
                edgeMarginPx = NATIVE_SCROLL_INDICATOR_EDGE_MARGIN_DP * density,
                thicknessPx = NATIVE_SCROLL_INDICATOR_THICKNESS_DP * density,
            ) * (1f - cos(Math.toRadians((NATIVE_SCROLL_INDICATOR_ARC_SWEEP_DEG / 2f).toDouble())).toFloat())
        } else {
            0f
        }
        val left = (width - stripWidth - arcBulge).toInt().coerceAtLeast(0)
        postInvalidateOnAnimation(left, 0, width, height)
    }

    private fun startScrollIndicatorLoop() {
        if (scrollIndicatorLoopScheduled || !indicatorCanRun()) return
        scrollIndicatorLoopScheduled = true
        Choreographer.getInstance().postFrameCallback(scrollIndicatorFrameLoop)
    }

    private fun cancelScrollIndicatorLoop() {
        if (scrollIndicatorLoopScheduled) {
            Choreographer.getInstance().removeFrameCallback(scrollIndicatorFrameLoop)
            scrollIndicatorLoopScheduled = false
        }
    }

    private val scrollIndicatorFrameLoop = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scrollIndicatorLoopScheduled = false
            if (!indicatorCanRun()) {
                resetScrollIndicatorState()
                return
            }
            applyScrollIndicatorResult(scrollIndicatorController.onFrame(SystemClock.uptimeMillis()))
        }
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
        current.centerEdgeItems != next.centerEdgeItems ||
        current.edgeAlignEnabled != next.edgeAlignEnabled ||
        current.edgeAlignPaddingPx != next.edgeAlignPaddingPx ||
        ((current.edgeAlignEnabled || next.edgeAlignEnabled) &&
            next.presentation != NativeAppListPresentation.LINEAR &&
            (current.edgeVisibilityKey() != next.edgeVisibilityKey() ||
                current.flowCrescentBulgeFraction != next.flowCrescentBulgeFraction ||
                current.flowCrescentDirection != next.flowCrescentDirection))

private fun Int.withAlphaFraction(alpha: Float): Int =
    (this and 0x00ffffff) or ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)

/**
 * How long the list must stay idle before the motion blur comes back.
 *
 * 72ms was short enough that a sequence of short flicks toggled the whole
 * blur pipeline on and off between them, which is what made the icons appear
 * to flicker after scrolling. Restoration is purely cosmetic, so waiting a
 * little longer costs nothing and keeps quick successive flicks on one
 * uninterrupted unblurred pass.
 */
private const val MOTION_BLUR_RESTORE_DELAY_MS = 160L
