package com.flue.launcher.applist.nativeview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.snapshots.Snapshot
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.diagnostics.TransitionPerformance
import eightbitlab.com.blurview.BlurAlgorithm
import eightbitlab.com.blurview.BlurController
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.BlurViewCanvas
import eightbitlab.com.blurview.BlurViewFacade
import eightbitlab.com.blurview.FlueBlurControllerAccess
import kotlin.math.ceil
import kotlin.math.roundToInt

// The list transition tops out around 8-10dp. After the 4x downsample that is
// roughly 2px on mdpi watches; use that as the stable kernel floor so a close
// transition that starts at radius zero still has a useful cached blur without
// making the legacy result visibly softer than the existing animation.
private const val LEGACY_LIST_MIN_BLUR_RADIUS_PX = 2.0
private const val LEGACY_LIST_MAX_BLUR_RADIUS_DP = 10f

/** Legacy foreground blur; the outer Compose layer alone owns transition alpha. */
class NativeAppListBlurHost internal constructor(
    context: Context,
    private val algorithmFactory: (Context) -> BlurAlgorithm,
) : FrameLayout(context) {
    constructor(context: Context) : this(context, ::NativeAppListRenderScriptBlur)

    val contentView = NativeAppListView(context)
    private val target = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        CapturingBlurTarget(context)
    } else {
        null
    }
    // Keep the hierarchy stable during draw, including failure cleanup.
    private val blurView = target?.let { BlurView(context) }
    private var controller: ManualBlurController? = null
    private var radiusDp = 0f
    private var active = false
    private var requested = false
    private var frameReady = false
    private var contentDirty = true
    private var radiusDirty = true
    private var transitionFailed = false
    private var released = false
    private var detached = false
    private var radiusSource: (() -> Float)? = null
    private var alphaSource: (() -> Float)? = null
    private var sourceEnabled = false
    private var sourceVisible = false
    private var observedTree: ViewTreeObserver? = null
    private var scrollListening = false
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        // This prepares memory only. Draw samples the same sources again for the
        // actual frame, without putting spring state into Compose recomposition.
        Snapshot.withoutReadObservation {
            radiusSource?.let { source ->
                updateBlurFrame(source(), alphaSource?.invoke() ?: 1f, sourceEnabled, sourceVisible)
            }
        }
        if (canRun() && !transitionFailed && (active || controller != null)) prepareBlur()
        true
    }
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            markContentDirty()
        }
    }
    private val clearPaint = Paint()
    private val addPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private var blurWeightAlpha = 255

    init {
        if (target != null) {
            target.addView(contentView, matchParent())
            addView(target, matchParent())
            requireNotNull(blurView).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOverlayColor(Color.TRANSPARENT)
                setBlurAutoUpdate(false)
                isClickable = false
                isFocusable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(blurView, matchParent())
        } else {
            // API 31+ must not allocate BlurView or RenderScript resources.
            addView(contentView, matchParent())
        }
    }

    fun setBlurFrameSource(
        radiusDp: () -> Float,
        alpha: () -> Float,
        enabled: Boolean,
        visible: Boolean,
    ) {
        if (released) return
        radiusSource = radiusDp
        alphaSource = alpha
        sourceEnabled = enabled
        sourceVisible = visible
        Snapshot.withoutReadObservation { updateBlurFrame(radiusDp(), alpha(), enabled, visible) }
        // AndroidView.update runs outside drawing. Pre-draw also covers a new
        // gesture which starts without a composition or layout pass.
        if (canRun() && !transitionFailed && (active || controller != null)) prepareBlur()
    }

    fun updateBlurFrame(radiusDp: Float, alpha: Float, enabled: Boolean, visible: Boolean) {
        if (released) return
        val nextRadius = radiusDp.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        requested = target != null && enabled && visible
        val nextActive = canRun() && alpha.isFinite() && alpha > 0f && nextRadius > 0f
        val changed = this.radiusDp != nextRadius || active != nextActive
        radiusDirty = radiusDirty || this.radiusDp != nextRadius
        this.radiusDp = nextRadius
        active = nextActive
        // Keep the small-radius contract used by direct host callers (0..1 is
        // already a blend fraction), while normal transition values use the
        // 0..10dp list blur range.
        val blendFraction = if (nextRadius <= 1f) {
            nextRadius
        } else {
            nextRadius / LEGACY_LIST_MAX_BLUR_RADIUS_DP
        }
        blurWeightAlpha = blendFraction
            .coerceIn(0f, 1f)
            .times(255f)
            .roundToInt()
        if (!nextActive) {
            frameReady = false
            transitionFailed = false
        }
        if (!canRun() || !alpha.isFinite() || alpha <= 0f) destroyBlur()
        syncListeners()
        // A changed frame invalidates the native display list once; the listener
        // never advances time or requests another unchanged frame.
        if (changed) invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) contentDirty = true
        if (canRun() && !transitionFailed && (active || controller != null)) prepareBlur()
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        if (isContentDescendant(target)) markContentDirty()
        super.onDescendantInvalidated(child, target)
    }

    private fun isContentDescendant(view: View): Boolean {
        var candidate: View? = view
        while (candidate != null && candidate !== this) {
            if (candidate === contentView) return true
            candidate = candidate.parent as? View
        }
        return false
    }

    private fun markContentDirty() {
        contentDirty = true
        if (active && !released && !transitionFailed) invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        frameReady = false
        if (active && canRun() && !transitionFailed) {
            val prepared = controller
            if (prepared != null && prepared.matchesSize(width, height)) {
                try {
                    if (contentDirty || !prepared.hasCapture) {
                        // Invalidations raised while capturing belong to the next
                        // frame; there is still only one capture in this draw.
                        contentDirty = false
                        prepared.capture()
                        radiusDirty = true
                    }
                    if (radiusDirty || !prepared.hasResult) {
                        prepared.blur(radiusDp)
                        radiusDirty = false
                    }
                    frameReady = prepared.hasResult
                } catch (failure: Throwable) {
                    failTransition(prepared.stage, failure)
                }
            }
        }
        // ManualBlurController pre-composes the clear and blurred captures in
        // its quarter-size bitmap. Drawing that one bitmap avoids a full-size
        // saveLayer plus two child display-list traversals on every radius
        // sample. The fallback path remains available before composition.
        super.dispatchDraw(canvas)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child === target && frameReady) {
            val prepared = controller
            // The composite is drawn by the overlay child below. Skipping the
            // target here prevents the live RecyclerView from being painted a
            // second time on every animation frame.
            if (prepared?.hasComposite == true) return true
            if (blurWeightAlpha == 255) return true
            clearPaint.alpha = 255 - blurWeightAlpha
            // The capture already contains the clear list at the same frame as
            // the blur input. Replaying RecyclerView here on every radius
            // sample defeats capture reuse and redraws every icon/shadow on
            // API 27-30. Keep the live target for touch/layout, but paint the
            // cached clear bitmap until content (or scroll) invalidates it.
            if (prepared != null && prepared.drawClear(canvas, clearPaint.alpha)) {
                return true
            }
            return drawWeightedChild(canvas, child, drawingTime, clearPaint)
        }
        if (child === blurView) {
            if (frameReady && controller?.drawComposite(canvas) == true) return true
            if (!frameReady || blurWeightAlpha == 0) return true
            if (blurWeightAlpha < 255) {
                addPaint.alpha = blurWeightAlpha
                return drawWeightedChild(canvas, child, drawingTime, addPaint)
            }
        }
        return super.drawChild(canvas, child, drawingTime)
    }

    private fun drawWeightedChild(canvas: Canvas, child: View, time: Long, paint: Paint): Boolean {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return try {
            super.drawChild(canvas, child, time)
        } finally {
            canvas.restoreToCount(layer)
        }
    }

    private fun prepareBlur() {
        if (width <= 0 || height <= 0) return
        try {
            val prepared = controller ?: ManualBlurController(algorithmFactory(context)).also {
                controller = it
                FlueBlurControllerAccess.install(requireNotNull(blurView), it)
            }
            if (!prepared.matchesSize(width, height)) {
                prepared.prepare(width, height)
                contentDirty = true
                radiusDirty = true
            }
        } catch (failure: Throwable) {
            failTransition("prepare", failure)
        }
    }

    private fun failTransition(stage: String, failure: Throwable) {
        transitionFailed = true
        Log.w("FlueListBlur", "Legacy list blur failed at $stage (API ${Build.VERSION.SDK_INT}, ${width}x$height); showing live list", failure)
        destroyBlur()
    }

    private fun destroyBlur() {
        frameReady = false
        controller = null
        runCatching { blurView?.let(FlueBlurControllerAccess::clear) }
        contentDirty = true
        radiusDirty = true
    }

    private fun canRun() = requested && !released && !detached &&
        visibility == VISIBLE && windowVisibility == VISIBLE

    private fun syncListeners() {
        if (canRun()) {
            val tree = viewTreeObserver
            if (observedTree !== tree) {
                observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
                tree.addOnPreDrawListener(preDrawListener)
                observedTree = tree
            }
            if (!scrollListening) {
                contentView.addOnScrollListener(scrollListener)
                scrollListening = true
            }
        } else {
            stopListeners()
        }
    }

    private fun stopListeners() {
        observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        observedTree = null
        if (scrollListening) {
            contentView.removeOnScrollListener(scrollListener)
            scrollListening = false
        }
    }

    fun release() {
        if (released) return
        released = true
        active = false
        requested = false
        radiusSource = null
        alphaSource = null
        stopListeners()
        destroyBlur()
        contentView.release()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detached = false
        syncListeners()
    }

    override fun onDetachedFromWindow() {
        detached = true
        stopListeners()
        destroyBlur()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            stopListeners()
            destroyBlur()
        } else if (!released) {
            syncListeners()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            stopListeners()
            destroyBlur()
        } else if (!released) {
            syncListeners()
        }
    }

    private inner class CapturingBlurTarget(context: Context) : BlurTarget(context) {
        override fun onDescendantInvalidated(child: View, target: View) {
            markContentDirty()
            super.onDescendantInvalidated(child, target)
        }

        @Suppress("DEPRECATION")
        override fun invalidateChildInParent(location: IntArray, dirty: Rect): ViewParent? {
            markContentDirty()
            return super.invalidateChildInParent(location, dirty)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            contentDirty = true
        }
    }

    /** Owns a pristine capture and a separate reusable kernel input/output bitmap. */
    private inner class ManualBlurController(private val algorithm: BlurAlgorithm) : BlurController {
        private var source: Bitmap? = null
        private var working: Bitmap? = null
        private var result: Bitmap? = null
        private var composite: Bitmap? = null
        private var captureCanvas: BlurViewCanvas? = null
        private var workingCanvas: Canvas? = null
        private var compositeCanvas: Canvas? = null
        private val clearDestination = Rect()
        private val compositePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        private val compositeBlurPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        private var viewportWidth = 0
        private var viewportHeight = 0
        private var destroyed = false
        /**
         * RenderScript is synchronous on the UI thread. For the production
         * legacy kernel, bake one stable blur per captured list and animate
         * only the clear/blur weight afterwards, matching Clox's pre-31 path.
         * Test algorithms keep the exact-radius contract so pixel tests can
         * still exercise radius invalidation independently.
         */
        private val cacheExpensiveKernel = algorithm is NativeAppListRenderScriptBlur
        private var kernelReady = false
        var hasCapture = false
            private set
        var hasResult = false
            private set
        var hasComposite = false
            private set
        var stage = "prepare"
            private set

        fun matchesSize(width: Int, height: Int) = !destroyed && source != null &&
            viewportWidth == width && viewportHeight == height

        fun prepare(width: Int, height: Int) {
            recycleBitmaps()
            viewportWidth = width
            viewportHeight = height
            clearDestination.set(0, 0, width, height)
            val scaledWidth = ceil(width / 4.0).toInt().coerceAtLeast(1)
            val scaledHeight = ceil(height / 4.0).toInt().coerceAtLeast(1)
            source = Bitmap.createBitmap(scaledWidth, scaledHeight, algorithm.supportedBitmapConfig)
            working = Bitmap.createBitmap(scaledWidth, scaledHeight, algorithm.supportedBitmapConfig)
            composite = Bitmap.createBitmap(scaledWidth, scaledHeight, algorithm.supportedBitmapConfig)
            captureCanvas = BlurViewCanvas(requireNotNull(source))
            workingCanvas = Canvas(requireNotNull(working))
            compositeCanvas = Canvas(requireNotNull(composite))
            (algorithm as? NativeAppListRenderScriptBlur)?.prepare(requireNotNull(working))
        }

        fun capture() {
            stage = "capture"
            val started = TransitionPerformance.start()
            try {
                hasCapture = false
                hasResult = false
                hasComposite = false
                kernelReady = false
                val bitmap = requireNotNull(source)
                val canvas = requireNotNull(captureCanvas)
                bitmap.eraseColor(Color.TRANSPARENT)
                val checkpoint = canvas.save()
                try {
                    canvas.scale(bitmap.width.toFloat() / viewportWidth, bitmap.height.toFloat() / viewportHeight)
                    requireNotNull(target).draw(canvas)
                    hasCapture = true
                } finally {
                    canvas.restoreToCount(checkpoint)
                }
            } finally {
                TransitionPerformance.finish(TransitionPerformance.Stage.LIST_CAPTURE, started)
            }
        }

        fun drawClear(canvas: Canvas, alpha: Int): Boolean {
            if (!hasCapture || destroyed) return false
            val bitmap = source ?: return false
            clearPaint.alpha = alpha.coerceIn(0, 255)
            canvas.drawBitmap(
                bitmap,
                null,
                clearDestination,
                clearPaint,
            )
            return true
        }

        fun drawComposite(canvas: Canvas): Boolean {
            if (!hasComposite || destroyed) return false
            val bitmap = composite ?: return false
            canvas.drawBitmap(bitmap, null, clearDestination, compositePaint)
            return true
        }

        fun blur(radiusDp: Float) {
            stage = "blur"
            val started = TransitionPerformance.start()
            try {
                if (!cacheExpensiveKernel || !kernelReady) {
                    hasResult = false
                    val bitmap = requireNotNull(working)
                    bitmap.eraseColor(Color.TRANSPARENT)
                    requireNotNull(workingCanvas).drawBitmap(requireNotNull(source), 0f, 0f, null)
                    val requestedRadius = radiusDp.toDouble() * resources.displayMetrics.density *
                        bitmap.width / viewportWidth
                    // A close transition starts at radius ~= 0 and ramps up.
                    // Bake a useful kernel immediately, then use alpha to follow
                    // the real frame. This avoids a first-frame sharp flash and
                    // removes synchronous RenderScript work from every VSYNC.
                    val effectiveRadius = requestedRadius
                        .coerceAtLeast(if (cacheExpensiveKernel) LEGACY_LIST_MIN_BLUR_RADIUS_PX else 0.001)
                        .coerceIn(0.001, 25.0)
                        .toFloat()
                    val previous = result
                    val next = algorithm.blur(bitmap, effectiveRadius)
                    if (previous !== next && previous !== working && previous !== source) previous?.recycle()
                    result = next
                    kernelReady = true
                }
                compose(blurWeightAlpha)
                hasResult = true
            } finally {
                TransitionPerformance.finish(TransitionPerformance.Stage.LIST_BLUR, started)
            }
        }

        private fun compose(alpha: Int) {
            val destination = compositeCanvas ?: return
            val clear = source ?: return
            val blurred = result ?: return
            destination.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // Preserve the old two-layer semantics in one bitmap: the live
            // clear capture fades out while the blurred capture fades in.
            // SRC followed by ADD interpolates premultiplied colors and alpha
            // without the double-alpha accumulation caused by SRC_OVER.
            val blurAlpha = alpha.coerceIn(0, 255)
            compositePaint.alpha = 255 - blurAlpha
            destination.drawBitmap(clear, 0f, 0f, compositePaint)
            compositeBlurPaint.alpha = blurAlpha
            destination.drawBitmap(blurred, 0f, 0f, compositeBlurPaint)
            compositeBlurPaint.alpha = 255
            compositePaint.alpha = 255
            hasComposite = true
        }

        override fun draw(canvas: Canvas): Boolean {
            if (canvas is BlurViewCanvas) return false
            if (!frameReady || destroyed) return true
            val bitmap = result ?: return true
            val checkpoint = canvas.save()
            try {
                canvas.clipRect(0, 0, viewportWidth, viewportHeight)
                canvas.scale(viewportWidth.toFloat() / bitmap.width, viewportHeight.toFloat() / bitmap.height)
                algorithm.render(canvas, bitmap)
            } finally {
                canvas.restoreToCount(checkpoint)
            }
            return true
        }

        override fun updateBlurViewSize() {
            contentDirty = true
        }

        private fun recycleBitmaps() {
            if (result !== source && result !== working) result?.let { if (!it.isRecycled) it.recycle() }
            if (composite !== source && composite !== working && composite !== result) {
                composite?.let { if (!it.isRecycled) it.recycle() }
            }
            source?.let { if (!it.isRecycled) it.recycle() }
            working?.let { if (!it.isRecycled) it.recycle() }
            source = null
            working = null
            result = null
            composite = null
            captureCanvas = null
            workingCanvas = null
            compositeCanvas = null
            clearDestination.setEmpty()
            hasCapture = false
            hasResult = false
            hasComposite = false
        }

        override fun destroy() {
            if (destroyed) return
            destroyed = true
            try {
                algorithm.destroy()
            } finally {
                recycleBitmaps()
            }
        }

        // The host owns scheduling, radius and transparent compositing. In
        // particular, BlurView attach/resize must never install an auto clock.
        override fun setBlurAutoUpdate(enabled: Boolean): BlurViewFacade = this
        override fun setBlurEnabled(enabled: Boolean): BlurViewFacade = this
        override fun setBlurRadius(radius: Float): BlurViewFacade = this
        override fun setOverlayColor(color: Int): BlurViewFacade = this
        override fun setFrameClearDrawable(drawable: Drawable?): BlurViewFacade = this
    }

    private fun matchParent() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
}
