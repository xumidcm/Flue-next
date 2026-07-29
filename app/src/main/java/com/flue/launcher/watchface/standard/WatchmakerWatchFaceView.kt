package com.flue.launcher.watchface.standard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.core.graphics.PathParser
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.editor.WatchCanvasSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A deliberately self-contained native renderer for the open Watchmaker
 * `.watch` format.  The view owns the frame clock and asset loading; Compose
 * only hosts this one coarse-grained view.  Unsupported layer types remain
 * invisible instead of being routed through an unsupported plugin renderer.
 *
 * This is the first production seam for the format.  It implements the common
 * static/time layers used by real 1.0 and Studio 2.0 faces (image, text, ring,
 * progress, image_cond, shape, rounded, markers and text curves).  The parser
 * keeps every unknown attribute so additional primitives can be added without
 * changing storage or the launcher contract.
 */
class WatchmakerWatchFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val lifetimeJob = SupervisorJob()
    private val mainScope = CoroutineScope(lifetimeJob + Dispatchers.Main.immediate)
    private var assetGenerationJob: Job = SupervisorJob(lifetimeJob)
    private var assetScope = CoroutineScope(assetGenerationJob + Dispatchers.IO)
    private var loadJob: Job? = null
    private var bindGeneration = 0L
    private var disposed = false
    private var source: PreparedSource? = null
    private var descriptorKey: String? = null
    private var loadedDescriptorKey: String? = null
    private var loadedRefreshToken: Int? = null
    /**
     * Full outgoing frame used for an atomic cross-watch hand-off. The new
     * source is allowed to start decoding underneath it; the snapshot is
     * removed only after that generation has no pending visual asset loads.
     */
    private var sourceTransitionFrame: Bitmap? = null
    private var sourceTransitionGeneration = -1L
    private var sourceTransitionStartedAtUptimeMs = 0L
    private var trackTransitionAssetReadiness = false
    private var transitionFrameHasUnreadyVisualAsset = false
    private var faceVisible = false
    private var textEntryAnimationEnabled = true
    private var textAnimationStartedAtUptimeMs = SystemClock.uptimeMillis()
    /** Editor-only gate: null freezes every animated image layer. */
    private var editorAnimationPlaybackLimited = false
    private var editorAnimatedImageLayerIndex: Int? = null
    private var refreshToken = 0
    private var longPressAction: (() -> Unit)? = null
    private var doubleTapAction: (() -> Unit)? = null
    private var tapActionHandler: ((WatchTapAction) -> Unit)? = null
    private var tickPosted = false
    private var dataProvider: WatchDataProvider = EmptyWatchDataProvider
    private var frameDataSnapshot = WatchDataSnapshot()
    private var forceFill = false
    /** 当前手势是否由本视图消费；决策在 DOWN 时定下、整个手势保持一致。 */
    private var consumeCurrentGesture = false

    // 编辑器画布拖拽的实时变换覆盖（按图层文档序号定位）。
    private var liveTransformLayerIndex = -1
    private var liveTransformX = 0f
    private var liveTransformY = 0f
    private var liveTransformRotation = 0f
    private var liveTransformScaleX = 1f
    private var liveTransformScaleY = 1f
    private val liveTransformHandoff = WatchLiveTransformHandoff()

    /**
     * 编辑器手势期间逐帧调用：被拖图层的表盘像素立即跟随手指渲染，
     * 不等 XML 落盘。scale 传 1.0 基准（内部即画布 scale 因子）。
     */
    fun setLiveLayerTransform(
        layerIndex: Int,
        x: Float,
        y: Float,
        rotationDegrees: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        liveTransformLayerIndex = layerIndex
        liveTransformX = x
        liveTransformY = y
        liveTransformRotation = rotationDegrees
        liveTransformScaleX = scaleX
        liveTransformScaleY = scaleY
        invalidate()
    }

    /** Called when a new pointer gesture takes ownership of the live override. */
    fun cancelLiveLayerTransformHandoff() {
        liveTransformHandoff.cancel()
    }

    /** 手势结束（提交/取消）后清除覆盖，回到以 XML 为准的渲染。 */
    fun clearLiveLayerTransform() {
        liveTransformHandoff.cancel()
        if (clearLiveLayerTransformInternal()) invalidate()
    }

    /**
     * Keeps the release-frame transform visible until [refreshToken] has been
     * parsed and published. The actual clear happens inside [bind], in the same
     * main-thread transaction that swaps [source], so no old-geometry frame can
     * appear between pointer-up and the committed renderer state.
     */
    fun handoffLiveLayerTransformOnSourceReady(refreshToken: Int) {
        if (liveTransformLayerIndex == -1) return
        val key = descriptorKey ?: return
        liveTransformHandoff.arm(key, refreshToken)
        if (
            loadedDescriptorKey == key &&
            loadedRefreshToken == refreshToken &&
            liveTransformHandoff.consumeIfReady(key, refreshToken)
        ) {
            if (clearLiveLayerTransformInternal()) invalidate()
        }
    }

    private fun clearLiveLayerTransformInternal(): Boolean {
        if (liveTransformLayerIndex == -1) return false
        liveTransformLayerIndex = -1
        return true
    }

    private fun completeLiveTransformHandoffIfReady(
        descriptorKey: String,
        refreshToken: Int,
    ) {
        if (liveTransformHandoff.consumeIfReady(descriptorKey, refreshToken)) {
            clearLiveLayerTransformInternal()
        }
    }

    /**
     * 编辑器图层面板缩略图：把单个图层（含其祖先组的变换链）按整表比例
     * 渲染进 [sizePx]² 位图。完全复用 drawRenderItem 主渲染路径与已就绪的
     * 资源缓存，不另建一套图元实现（编辑器侧近似渲染会和真实效果偏差）。
     */
    fun renderLayerThumbnail(layerIndex: Int, sizePx: Int): Bitmap? {
        if (disposed || sizePx <= 0) return null
        val prepared = source ?: return null
        val item = prepared.renderPlan.firstOrNull { it.layerIndex == layerIndex } ?: return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = sizePx / DESIGN_SIZE
        canvas.scale(scale, scale)
        canvas.translate(DESIGN_SIZE / 2f, DESIGN_SIZE / 2f)
        runCatching { drawRenderItem(canvas, item, prepared) }
        return bitmap
    }

    /**
     * Resolves a text layer exactly like the live face, but returns only its
     * current textual content. The editor uses this to build an independent,
     * centered thumbnail without inheriting the layer's x/y/rotation/scale.
     */
    fun resolveTextLayerPreview(layerIndex: Int): String? {
        if (disposed) return null
        val prepared = source ?: return null
        val layer = prepared.definition.layers.getOrNull(layerIndex) ?: return null
        if (!layer.type.lowercase(Locale.ROOT).contains("text")) return null
        val raw = layer.attributes["text"].orEmpty()
            .ifBlank { layer.attributes["value"].orEmpty() }
        if (raw.isBlank()) return null
        calendar.timeInMillis = System.currentTimeMillis()
        frameDataSnapshot = runCatching { dataProvider.snapshot(calendar.timeInMillis) }
            .getOrDefault(WatchDataSnapshot())
        val resolved = evaluateText(raw, prepared)
        return when (layer.attributes["transform"].orEmpty().lowercase(Locale.ROOT)) {
            "uc", "uppercase" -> resolved.uppercase(Locale.getDefault())
            "lc", "lowercase" -> resolved.lowercase(Locale.getDefault())
            else -> resolved
        }.takeIf(String::isNotBlank)
    }

    /**
     * Renders one plain text layer as an independent, tightly-fitted thumbnail.
     *
     * The live renderer supplies the expression result, font (including BMFont),
     * colour, outline and alignment. We intentionally do not apply the layer's
     * x/y/rotation/scale or ancestor transforms: the layer list needs a readable
     * material preview, not a crop of its position on the watch canvas.
     */
    fun renderTextLayerThumbnail(layerIndex: Int, sizePx: Int): Bitmap? {
        if (disposed || sizePx <= 0) return null
        val prepared = source ?: return null
        val layer = prepared.definition.layers.getOrNull(layerIndex) ?: return null
        val normalizedType = layer.type.lowercase(Locale.ROOT)
        if (normalizedType !in setOf("text", "text_curved", "text_ring")) return null
        val raw = layer.attributes["text"].orEmpty()
            .ifBlank { layer.attributes["value"].orEmpty() }
        if (raw.isBlank()) return null
        val thumbnailAttributes = if (layer.attributes["text"].isNullOrBlank()) {
            layer.attributes + ("text" to raw)
        } else {
            layer.attributes
        }

        calendar.timeInMillis = System.currentTimeMillis()
        frameDataSnapshot = runCatching { dataProvider.snapshot(calendar.timeInMillis) }
            .getOrDefault(WatchDataSnapshot())

        // Render once in the canonical design space so even small text retains
        // enough antialiasing detail before it is fitted into the 40dp row tile.
        val workingSize = DESIGN_SIZE.roundToInt().coerceAtLeast(1)
        val working = Bitmap.createBitmap(workingSize, workingSize, Bitmap.Config.ARGB_8888)
        val workingCanvas = Canvas(working)
        val designScale = workingSize / DESIGN_SIZE
        workingCanvas.scale(designScale, designScale)
        workingCanvas.translate(DESIGN_SIZE / 2f, DESIGN_SIZE / 2f)
        runCatching {
            when (normalizedType) {
                "text_curved" -> drawCurvedText(workingCanvas, thumbnailAttributes, 1f, prepared)
                "text_ring" -> drawTextRing(workingCanvas, thumbnailAttributes, 1f, prepared)
                else -> drawText(workingCanvas, thumbnailAttributes, 1f, prepared)
            }
        }

        val pixels = IntArray(workingSize * workingSize)
        working.getPixels(pixels, 0, workingSize, 0, 0, workingSize, workingSize)
        var left = workingSize
        var top = workingSize
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, color ->
            if (color ushr 24 == 0) return@forEachIndexed
            val x = index % workingSize
            val y = index / workingSize
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (right < left || bottom < top) {
            working.recycle()
            return null
        }

        val sourcePadding = 3
        val sourceRect = Rect(
            (left - sourcePadding).coerceAtLeast(0),
            (top - sourcePadding).coerceAtLeast(0),
            (right + sourcePadding + 1).coerceAtMost(workingSize),
            (bottom + sourcePadding + 1).coerceAtMost(workingSize),
        )
        val available = (sizePx - 6).coerceAtLeast(1).toFloat()
        val fitScale = min(
            available / sourceRect.width().coerceAtLeast(1),
            available / sourceRect.height().coerceAtLeast(1),
        )
        val drawnWidth = (sourceRect.width() * fitScale).coerceAtLeast(1f)
        val drawnHeight = (sourceRect.height() * fitScale).coerceAtLeast(1f)
        val destination = RectF(
            (sizePx - drawnWidth) / 2f,
            (sizePx - drawnHeight) / 2f,
            (sizePx + drawnWidth) / 2f,
            (sizePx + drawnHeight) / 2f,
        )
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            working,
            sourceRect,
            destination,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )
        working.recycle()
        return output
    }

    /**
     * 把整块表盘离屏渲染成位图，用于重新生成 preview.jpg。
     *
     * 与 [onDraw] 同一套坐标契约（居中原点 + 方形/圆形裁剪 + 背景色 + 全部
     * renderPlan），但不依赖 View 的宽高，所以未上屏也能出图。编辑器保存时
     * 用它覆盖草稿里作者自带的 preview.jpg —— 否则表盘列表和商店永远显示
     * 原作者那张预览（老师报的「改完保存甚至上传都不换预览图」）。
     */
    fun renderFaceBitmap(sizePx: Int): Bitmap? {
        if (disposed || sizePx <= 0) return null
        val prepared = source ?: return null
        val extentW = prepared.designExtentWidth
        val extentH = prepared.designExtentHeight
        // sizePx 作为长边预算，短边按声明比例收窄：方形表盘仍出 sizePx² 位图，
        // 非方表盘出的 preview.jpg 比例才和实际渲染一致。
        val scale = sizePx / max(extentW, extentH)
        val bitmapWidth = (extentW * scale).roundToInt().coerceAtLeast(1)
        val bitmapHeight = (extentH * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(extentW / 2f, extentH / 2f)
        canvas.clipRect(-extentW / 2f, -extentH / 2f, extentW / 2f, extentH / 2f)
        if (prepared.definition.shape == WatchFaceShape.ROUND) {
            pathScratch.reset()
            pathScratch.addCircle(0f, 0f, min(extentW, extentH) / 2f, Path.Direction.CW)
            canvas.clipPath(pathScratch)
        }
        calendar.timeInMillis = System.currentTimeMillis()
        frameDataSnapshot = runCatching { dataProvider.snapshot(calendar.timeInMillis) }
            .getOrDefault(WatchDataSnapshot())
        backgroundPaint.color =
            resolveColor(prepared.definition.rootAttributes["bg_color"], Color.BLACK, prepared)
        canvas.drawRect(
            -extentW / 2f,
            -extentH / 2f,
            extentW / 2f,
            extentH / 2f,
            backgroundPaint,
        )
        runCatching { prepared.renderPlan.forEach { drawRenderItem(canvas, it, prepared) } }
        canvas.restore()
        return bitmap
    }

    private val bitmapCache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    /** Small transient LRU so long frame sequences cannot evict static face assets. */
    private val animatedBitmapCache = LinkedHashMap<String, Bitmap>(4, 0.75f, true)
    private val movieCache = LinkedHashMap<String, Movie>(4, 0.75f, true)
    /**
     * 帧序列图层上一次成功画出的位图，按图层的完整 path 表达式索引。
     *
     * GIF 帧序列（`a.png`b.png`c.png`）每帧是独立文件、各有各的 cacheRevision，
     * 所以 [requestBitmap] 里那套「同一素材的上一帧」兜底跨不过帧边界：切帧那
     * 一瞬新键必然未命中，整层直接 return 什么都不画 —— 每换一帧黑一下，就是
     * GIF 持续闪烁的根因。这里按图层维度记住最后画成功的那张，解码没跟上时
     * 继续画它，宁可停在旧帧也绝不闪黑。
     *
     * 只在主线程的 onDraw 里读写，不需要 cacheLock。
     */
    private val lastDrawnFrameByLayer = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    /** Raw GIF equivalent of [lastDrawnFrameByLayer], used while a replacement Movie decodes. */
    private val lastDrawnMovieByLayer = LinkedHashMap<String, Movie>(4, 0.75f, true)
    private var bitmapCacheBytes = 0L
    private var animatedBitmapCacheBytes = 0L
    private val activeAssetLoads = HashSet<AssetLoadClaim>(MAX_CONCURRENT_ASSET_LOADS)
    /** Asset key -> bind generation which owns the in-flight decode. */
    private val bitmapLoading = HashMap<String, Long>()
    private val movieLoading = HashMap<String, Long>()
    /** Failed assets are retried only after a cooldown, never once per frame. */
    private val bitmapFailures = LinkedHashMap<String, BitmapFailure>(16, 0.75f, true)
    private val movieFailures = LinkedHashMap<String, BitmapFailure>(8, 0.75f, true)
    /** Typeface key -> bind generation which owns the in-flight load. */
    private val typefaceLoading = HashMap<String, Long>()
    private val typefaceCache = LinkedHashMap<String, Typeface>(8, 0.75f, true)
    private val cacheLock = Any()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sourceTransitionPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** 描边通道专用；绝不复用 textPaint/shapePaint，避免污染其 style/strokeWidth。 */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    /** 位图字体描边副本专用（带 SRC_IN 滤色把字形染成描边色）。 */
    private val bitmapOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pathScratch = Path()
    private val rectScratch = RectF()
    private val bitmapFontSourceRect = Rect()
    private val bitmapFontDestRect = RectF()
    private val ancestorIndexScratch = IntArray(MAX_WATCH_PARENT_DEPTH)
    private val calendar = Calendar.getInstance()
    private var frameStartedAtUptimeMs = SystemClock.uptimeMillis()
    private val tapTargets = ArrayList<TapTarget>(8)
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = faceVisible

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (faceVisible) doubleTapAction?.invoke()
                return faceVisible
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (!faceVisible) return false
                dispatchTap(event.x, event.y)
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                if (faceVisible) longPressAction?.invoke()
            }
        },
    )

    fun bind(
        descriptor: WatchFaceDescriptor,
        refreshToken: Int,
        isFaceVisible: Boolean,
        onLongPress: (() -> Unit)?,
        onDoubleTap: (() -> Unit)?,
        onLoadFailure: (WatchFaceDescriptor, Throwable) -> Unit,
        onTapAction: ((WatchTapAction) -> Unit)? = null,
        onSourceReady: ((Int) -> Unit)? = null,
        textEntryAnimationEnabled: Boolean = true,
    ) {
        if (disposed) return
        require(descriptor.isWatchmaker) { "Watchmaker view requires a standard .watch descriptor" }
        val enteringFace = isFaceVisible && !faceVisible
        val enablingAnimation = isFaceVisible && textEntryAnimationEnabled && !this.textEntryAnimationEnabled
        this.faceVisible = isFaceVisible
        this.textEntryAnimationEnabled = textEntryAnimationEnabled
        if (textEntryAnimationEnabled && (enteringFace || enablingAnimation)) {
            textAnimationStartedAtUptimeMs = SystemClock.uptimeMillis()
        }
        this.longPressAction = onLongPress
        this.doubleTapAction = onDoubleTap
        this.tapActionHandler = onTapAction
        if (descriptorKey == descriptor.stableKey && this.refreshToken == refreshToken) {
            if (faceVisible) scheduleTicks() else cancelTicks()
            if (
                loadedDescriptorKey == descriptor.stableKey &&
                loadedRefreshToken == refreshToken
            ) {
                completeLiveTransformHandoffIfReady(descriptor.stableKey, refreshToken)
            }
            invalidate()
            if (
                loadedDescriptorKey == descriptor.stableKey &&
                loadedRefreshToken == refreshToken
            ) {
                onSourceReady?.invoke(refreshToken)
            }
            return
        }
        val previousDescriptorKey = loadedDescriptorKey
        val reloadMode = watchSourceReloadMode(
            currentDescriptorKey = descriptorKey,
            nextDescriptorKey = descriptor.stableKey,
            hasPreparedSource = source != null,
        )
        descriptorKey = descriptor.stableKey
        this.refreshToken = refreshToken
        val generation = ++bindGeneration
        loadJob?.cancel()
        assetGenerationJob.cancel()
        assetGenerationJob = SupervisorJob(lifetimeJob)
        assetScope = CoroutineScope(assetGenerationJob + Dispatchers.IO)
        if (reloadMode == WatchSourceReloadMode.CLEAR_BEFORE_LOAD) {
            cancelTicks()
            source?.clearRuntimeCaches()
            source = null
            clearAssetCaches()
            invalidate()
        } else {
            synchronized(cacheLock) {
                bitmapLoading.clear()
                movieLoading.clear()
                bitmapFailures.clear()
                movieFailures.clear()
                typefaceLoading.clear()
            }
        }
        val root = descriptor.sourceDirPath?.let(::File)
        if (root == null) {
            descriptorKey = null
            onLoadFailure(descriptor, IllegalArgumentException(".watch 表盘没有安装目录"))
            return
        }
        loadJob = mainScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    prepareSource(root, generation)
                }
                if (
                    generation != bindGeneration ||
                    prepared.sourceGeneration != generation ||
                    disposed
                ) {
                    return@launch
                }
                val current = source
                val replacement = current?.takeIf {
                    it.rootCacheKey == prepared.rootCacheKey &&
                        it.definitionRevision == prepared.definitionRevision
                } ?: prepared
                if (
                    replacement !== current &&
                    current != null &&
                    previousDescriptorKey != null &&
                    previousDescriptorKey != descriptor.stableKey
                ) {
                    installSourceTransitionFrame(
                        frame = capturePreparedFrame(current),
                        generation = generation,
                    )
                }
                if (replacement !== current) current?.clearRuntimeCaches()
                source = replacement
                loadedDescriptorKey = descriptor.stableKey
                loadedRefreshToken = refreshToken
                completeLiveTransformHandoffIfReady(descriptor.stableKey, refreshToken)
                if (replacement !== current && previousDescriptorKey != descriptor.stableKey) {
                    // 不同表盘切换：重置 GIF / 帧序列计时，从第0帧开始播。
                    // 同表盘刷新（reloadMode == KEEP_CURRENT_UNTIL_READY）时保留
                    // 旧计时器，GIF 从中断处继续，不出现疯狂闪烁。
                    frameStartedAtUptimeMs = SystemClock.uptimeMillis()
                    textAnimationStartedAtUptimeMs = SystemClock.uptimeMillis()
                }
                scheduleTicks()
                invalidate()
                onSourceReady?.invoke(refreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == bindGeneration) onLoadFailure(descriptor, error)
            }
        }
    }

    fun setFaceVisible(visible: Boolean) {
        if (disposed) return
        if (faceVisible == visible) return
        faceVisible = visible
        if (visible && textEntryAnimationEnabled) {
            textAnimationStartedAtUptimeMs = SystemClock.uptimeMillis()
        }
        if (visible) scheduleTicks() else cancelTicks()
        invalidate()
    }

    fun setWatchDataProvider(provider: WatchDataProvider?) {
        val resolved = provider ?: EmptyWatchDataProvider
        if (dataProvider === resolved) return
        dataProvider = resolved
        if (!disposed) invalidate()
    }

    fun setForceFill(enabled: Boolean) {
        if (forceFill == enabled) return
        forceFill = enabled
        if (!disposed) invalidate()
    }

    /** Limits animated image playback to the selected editor layer. */
    fun setEditorAnimatedImageLayer(layerIndex: Int?) {
        if (editorAnimationPlaybackLimited && editorAnimatedImageLayerIndex == layerIndex) return
        editorAnimationPlaybackLimited = true
        editorAnimatedImageLayerIndex = layerIndex
        frameStartedAtUptimeMs = SystemClock.uptimeMillis()
        lastDrawnFrameByLayer.clear()
        synchronized(cacheLock) {
            animatedBitmapCache.values.forEach { bitmap ->
                if (!bitmap.isRecycled && bitmap !in bitmapCache.values) bitmap.recycle()
            }
            animatedBitmapCache.clear()
            animatedBitmapCacheBytes = 0L
        }
        cancelTicks()
        if (!disposed) {
            scheduleTicks()
            invalidate()
        }
    }

    private fun shouldAnimateImageLayer(layerIndex: Int): Boolean =
        !editorAnimationPlaybackLimited || editorAnimatedImageLayerIndex == layerIndex

    fun dispose() {
        if (disposed) return
        disposed = true
        bindGeneration++
        descriptorKey = null
        loadedDescriptorKey = null
        loadedRefreshToken = null
        liveTransformHandoff.cancel()
        clearLiveLayerTransformInternal()
        faceVisible = false
        loadJob?.cancel()
        loadJob = null
        assetGenerationJob.cancel()
        mainScope.cancel()
        cancelTicks()
        synchronized(cacheLock) {
            bitmapCache.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            bitmapCache.clear()
            bitmapCacheBytes = 0L
            animatedBitmapCache.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            animatedBitmapCache.clear()
            animatedBitmapCacheBytes = 0L
            movieCache.clear()
            bitmapLoading.clear()
            movieLoading.clear()
            movieFailures.clear()
            bitmapFailures.clear()
            typefaceLoading.clear()
            typefaceCache.clear()
            activeAssetLoads.clear()
        }
        longPressAction = null
        doubleTapAction = null
        tapActionHandler = null
        tapTargets.clear()
        consumeCurrentGesture = false
        source?.clearRuntimeCaches()
        source = null
        sourceTransitionFrame?.let { frame ->
            if (!frame.isRecycled) frame.recycle()
        }
        sourceTransitionFrame = null
        sourceTransitionGeneration = -1L
        sourceTransitionStartedAtUptimeMs = 0L
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!disposed) scheduleTicks()
    }

    override fun onDetachedFromWindow() {
        cancelTicks()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disposed || !faceVisible) return false
        // 整手势消费：长按进编辑器、双击、tap_action 都由本视图的
        // gestureDetector 识别。曾试过"只在命中 tap target 时才消费"，
        // 结果 DOWN 不消费 → 视图收不到后续事件 → 长按进入表盘编辑直接
        // 失效（老师实机反馈）。启动器侧的手势由 Compose 父层自行观察，
        // 不依赖这里放行。
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            consumeCurrentGesture = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val prepared = source
        if (prepared == null || width <= 0 || height <= 0) {
            canvas.drawColor(Color.BLACK)
            return
        }
        val transition = sourceTransitionFrame?.takeIf { !it.isRecycled }
        trackTransitionAssetReadiness =
            transition != null && sourceTransitionGeneration == bindGeneration
        transitionFrameHasUnreadyVisualAsset = false
        drawPreparedSource(canvas, prepared, width.toFloat(), height.toFloat())
        trackTransitionAssetReadiness = false

        if (transition != null) {
            canvas.drawBitmap(transition, null, Rect(0, 0, width, height), sourceTransitionPaint)
            val transitionStillLoading = synchronized(cacheLock) {
                activeAssetLoads.any { claim -> claim.generation == sourceTransitionGeneration }
            }
            val transitionTimedOut =
                SystemClock.uptimeMillis() - sourceTransitionStartedAtUptimeMs >=
                    SOURCE_TRANSITION_MAX_HOLD_MS
            val canRevealReplacement =
                !transitionStillLoading && !transitionFrameHasUnreadyVisualAsset
            if (
                sourceTransitionGeneration != bindGeneration ||
                transitionTimedOut ||
                canRevealReplacement
            ) {
                sourceTransitionFrame = null
                sourceTransitionGeneration = -1L
                sourceTransitionStartedAtUptimeMs = 0L
                // Do not recycle here. A hardware display list may still refer
                // to this bitmap after onDraw returns; dropping our reference
                // lets the runtime reclaim it safely once that frame retires.
                invalidate()
            } else if (!transitionStillLoading) {
                // A cancelled older generation can briefly occupy all decode
                // slots. Retry on the next frame instead of revealing a face
                // whose first image request has not even entered the queue yet.
                postInvalidateOnAnimation()
            }
        }
    }

    private fun capturePreparedFrame(prepared: PreparedSource): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawPreparedSource(Canvas(bitmap), prepared, width.toFloat(), height.toFloat())
            }
        }.getOrNull()
    }

    private fun installSourceTransitionFrame(frame: Bitmap?, generation: Long) {
        val previous = sourceTransitionFrame
        sourceTransitionFrame = frame
        sourceTransitionGeneration = if (frame == null) -1L else generation
        sourceTransitionStartedAtUptimeMs =
            if (frame == null) 0L else SystemClock.uptimeMillis()
        // As above, an outgoing hardware display list may still own [previous].
        // Releasing the Kotlin reference is safer than eager recycle().
    }

    private fun drawPreparedSource(
        canvas: Canvas,
        prepared: PreparedSource,
        targetWidth: Float,
        targetHeight: Float,
    ) {
        // 设计空间跟随表盘自己声明的画布比例（基准边恒 512）。方形声明算出来
        // 就是 512×512，所以这里对存量表盘是恒等变换。
        val extentW = prepared.designExtentWidth
        val extentH = prepared.designExtentHeight
        val transform = watchCanvasTransform(
            viewWidth = targetWidth,
            viewHeight = targetHeight,
            forceFill = forceFill,
            designWidth = extentW,
            designHeight = extentH,
        )
        canvas.save()
        canvas.translate(transform.left, transform.top)
        canvas.scale(transform.scale, transform.scale)
        // Watchmaker coordinates are centered on (0, 0), unlike Android's
        // top-left canvas coordinates. Keeping this translation at the single
        // renderer boundary makes every primitive (including groups and
        // rings) use the same coordinate contract.
        canvas.translate(extentW / 2f, extentH / 2f)
        canvas.clipRect(-extentW / 2f, -extentH / 2f, extentW / 2f, extentH / 2f)
        if (prepared.definition.shape == WatchFaceShape.ROUND) {
            pathScratch.reset()
            // 圆形取内切圆：非方画布上按长边画会把圆裁出画布外。
            pathScratch.addCircle(0f, 0f, min(extentW, extentH) / 2f, Path.Direction.CW)
            canvas.clipPath(pathScratch)
        }
        calendar.timeInMillis = System.currentTimeMillis()
        frameDataSnapshot = runCatching { dataProvider.snapshot(calendar.timeInMillis) }
            .getOrDefault(WatchDataSnapshot())
        tapTargets.clear()
        backgroundPaint.color = resolveColor(prepared.definition.rootAttributes["bg_color"], Color.BLACK, prepared)
        canvas.drawRect(-extentW / 2f, -extentH / 2f, extentW / 2f, extentH / 2f, backgroundPaint)
        prepared.renderPlan.forEach { drawRenderItem(canvas, it, prepared) }
        canvas.restore()
    }

    private fun drawRenderItem(canvas: Canvas, item: WatchLayerRenderItem, source: PreparedSource) {
        val saveCount = canvas.save()
        try {
            var alpha = 1f
            var ancestorCount = 0
            var ancestor = item.ancestorChain
            while (ancestor != null && ancestorCount < ancestorIndexScratch.size) {
                ancestorIndexScratch[ancestorCount++] = ancestor.layerIndex
                ancestor = ancestor.outer
            }
            for (scratchIndex in ancestorCount - 1 downTo 0) {
                val ancestorIndex = ancestorIndexScratch[scratchIndex]
                val attributes = source.definition.layers[ancestorIndex].attributes
                alpha *= dynamicNumber(attributes["opacity"], 100f, source)
                    .coerceIn(0f, 100f) / 100f
                if (alpha <= 0f || !isDisplayed(attributes["display"], source)) return
                applyTransform(canvas, attributes, source, ancestorIndex)
            }
            val layer = source.definition.layers[item.layerIndex]
            val attributes = layer.attributes
            alpha *= dynamicNumber(attributes["opacity"], 100f, source)
                .coerceIn(0f, 100f) / 100f
            if (alpha <= 0f || !isDisplayed(attributes["display"], source)) return
            applyTransform(canvas, attributes, source, item.layerIndex)
            drawPrimitive(canvas, layer, item.layerIndex, alpha, source)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun applyTransform(
        canvas: Canvas,
        attrs: Map<String, String>,
        source: PreparedSource,
        layerIndex: Int = -1,
    ) {
        // 编辑器画布手势期间的实时覆盖：表盘像素立刻跟手，不等落盘。
        val live = layerIndex >= 0 && layerIndex == liveTransformLayerIndex
        canvas.translate(
            if (live) liveTransformX else dynamicNumber(attrs["x"], 0f, source),
            if (live) liveTransformY else dynamicNumber(attrs["y"], 0f, source),
        )
        val rotation = if (live) {
            liveTransformRotation
        } else {
            dynamicNumber(attrs["rotation"], 0f, source)
        }
        if (rotation != 0f) canvas.rotate(rotation)
        val sharedScale = attrs["anim_scale"] ?: attrs["scale"]
        val sx = if (live) {
            liveTransformScaleX
        } else {
            dynamicNumber(
                attrs["anim_scale_x"] ?: attrs["scale_x"] ?: sharedScale,
                100f,
                source,
            ).coerceIn(-500f, 500f) / 100f
        }
        val sy = if (live) {
            liveTransformScaleY
        } else {
            dynamicNumber(
                attrs["anim_scale_y"] ?: attrs["scale_y"] ?: sharedScale,
                100f,
                source,
            ).coerceIn(-500f, 500f) / 100f
        }
        if (sx != 1f || sy != 1f) canvas.scale(sx, sy)
        val skewX = dynamicNumber(attrs["skew_x"], 0f, source)
        val skewY = dynamicNumber(attrs["skew_y"], 0f, source)
        if (skewX != 0f || skewY != 0f) {
            canvas.skew(kotlin.math.tan(Math.toRadians(skewX.toDouble())).toFloat(), kotlin.math.tan(Math.toRadians(skewY.toDouble())).toFloat())
        }
    }

    private fun drawPrimitive(
        canvas: Canvas,
        layer: WatchmakerLayer,
        layerIndex: Int,
        alpha: Float,
        source: PreparedSource,
    ) {
        val attrs = layer.attributes
        val normalizedType = layer.type.lowercase(Locale.ROOT)
        var animatedAlpha = alpha
        if (textEntryAnimationEnabled && normalizedType.startsWith("text") &&
            (!attrs["anim_in"].isNullOrBlank() || !attrs["anim_out"].isNullOrBlank())
        ) {
            val animation = watchTextAnimationFrame(
                animIn = attrs["anim_in"],
                animOut = attrs["anim_out"],
                durationInSeconds = dynamicNumber(attrs["dur_in"], 0.5f, source),
                durationOnSeconds = dynamicNumber(attrs["dur_on"], 0f, source),
                durationOutSeconds = dynamicNumber(attrs["dur_out"], 0.5f, source),
                durationOffSeconds = dynamicNumber(attrs["dur_off"], 0f, source),
                delayStartSeconds = dynamicNumber(attrs["delay_start"], 0f, source),
                repeatCount = dynamicNumber(attrs["repeat_count"], 1f, source).roundToInt(),
                restartOnLoad = attrs["restart_on_load"].equals("Y", ignoreCase = true) ||
                    attrs["restart_on_load"].equals("true", ignoreCase = true),
                elapsedMs = SystemClock.uptimeMillis() - textAnimationStartedAtUptimeMs,
            )
            if (!animation.visible) return
            canvas.translate(animation.translationX, animation.translationY)
            if (animation.scale != 1f) canvas.scale(animation.scale, animation.scale)
            if (animation.rotation != 0f) canvas.rotate(animation.rotation)
            animatedAlpha *= animation.alpha
        }
        val layerCacheKey = buildString {
            append(source.rootCacheKey)
            append('\u0000')
            append(layer.id?.takeIf(String::isNotBlank) ?: layerIndex)
        }
        when (normalizedType) {
            "image", "gif", "image_gif" -> drawImage(
                canvas = canvas,
                attrs = attrs,
                alpha = animatedAlpha,
                source = source,
                layerCacheKey = layerCacheKey,
                animateFrames = shouldAnimateImageLayer(layerIndex),
            )
            "image_tap" -> drawImage(canvas, attrs, animatedAlpha, source, layerCacheKey, tapAction = true)
            "image_cond" -> drawImage(canvas, attrs, animatedAlpha, source, layerCacheKey, sprite = true)
            "gallery", "gallery_2d" -> drawGallery2d(
                canvas = canvas,
                attrs = attrs,
                alpha = animatedAlpha,
                source = source,
                layerCacheKey = layerCacheKey,
                animateFrames = shouldAnimateImageLayer(layerIndex),
            )
            "text" -> drawText(canvas, attrs, animatedAlpha, source)
            "series" -> drawSeries(canvas, attrs, alpha, source)
            "calendar" -> drawCalendar(canvas, attrs, alpha, source)
            "ring" -> drawRing(canvas, attrs, alpha, source)
            "progress" -> drawProgress(canvas, attrs, alpha, source)
            "shape" -> drawShape(canvas, attrs, alpha, source)
            "rounded" -> drawRounded(canvas, attrs, alpha, source)
            "markers" -> drawMarkers(canvas, attrs, alpha, source)
            "markers_hm" -> drawHourMinuteMarkers(canvas, attrs, alpha, source)
            "text_curved" -> drawCurvedText(canvas, attrs, animatedAlpha, source)
            "text_ring" -> drawTextRing(canvas, attrs, animatedAlpha, source)
            "chart" -> drawChart(canvas, attrs, alpha, source)
            "svg" -> drawSvg(canvas, attrs, alpha, source)
            // These families need a MediaPlayer/live map bridge.  If an
            // exporter points them at a static image, drawing that image is a
            // useful bounded fallback; otherwise leave the region transparent
            // and keep the capability in compatibilityReport.
            "video", "map" -> drawStaticMediaFallback(canvas, attrs, alpha, source, layerCacheKey)
        }
    }

    private fun drawGallery2d(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        layerCacheKey: String,
        animateFrames: Boolean,
    ) {
        val rawPaths = sequenceOf(
            attrs["path"],
            attrs["paths"],
            attrs["images"],
            attrs["source"],
        ).firstOrNull { !it.isNullOrBlank() }
        val evaluatedPaths = rawPaths?.let { evaluateText(it, source) }
        val galleryPaths = parseWatchGalleryPaths(evaluatedPaths)
            .ifEmpty { source.galleryAssetPaths }
        if (galleryPaths.isEmpty()) return

        val durationOnSeconds = dynamicNumber(
            attrs["dur_on"],
            DEFAULT_WATCH_GALLERY_DURATION_SECONDS,
            source,
        )
        drawImage(
            canvas = canvas,
            attrs = attrs,
            alpha = alpha,
            source = source,
            layerCacheKey = layerCacheKey,
            pathExpressionOverride = galleryPaths.joinToString("`"),
            frameDelayMsOverride = watchGalleryFrameDurationMs(durationOnSeconds),
            animateFrames = animateFrames,
        )
    }

    private fun drawImage(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        layerCacheKey: String,
        sprite: Boolean = false,
        tapAction: Boolean = false,
        pathExpressionOverride: String? = null,
        frameDelayMsOverride: Long? = null,
        animateFrames: Boolean = true,
    ) {
        val pathExpression = pathExpressionOverride
            ?: evaluateText(attrs["path"].orEmpty(), source)
        val framePaths = source.gifPathCache.getOrPut(pathExpression) {
            parseWatchGifPaths(pathExpression)
        }.orEmpty()
        val frameIndex = if (animateFrames) {
            watchGifFrameIndex(
                framePaths.size,
                SystemClock.uptimeMillis() - frameStartedAtUptimeMs,
                frameDelayMsOverride
                    ?: dynamicNumber(attrs["gif_delay"], 50f, source).roundToInt().toLong(),
            )
        } else {
            0
        }
        val path = framePaths.getOrNull(frameIndex) ?: pathExpression.substringBefore('`').trim()
        val wantsNightWeatherSheet = sprite &&
            attrs["weather_night"].equals("Y", ignoreCase = true) &&
            isWeatherNightFrame()
        val nightPath = if (wantsNightWeatherSheet) watchWeatherNightAssetReference(path) else null
        val nightResolved = nightPath?.let { candidate ->
            resolveAsset(candidate, source, conventionalDirectory = "images")
        }
        val usingNightWeatherSheet = nightResolved != null
        val selectedPath = if (usingNightWeatherSheet) nightPath.orEmpty() else path
        // A dynamic expression can briefly resolve to an empty/missing path at
        // the exact frame where it switches. Do not turn that transient lookup
        // miss into a transparent frame: the per-layer fallback below remains
        // authoritative until a new asset is ready.
        val resolved = nightResolved
            ?: resolveAsset(path, source, conventionalDirectory = "images")
        val sharedSize = attrs["size"]
        val requestedMovie = resolved
            ?.takeIf { it.file.extension.equals("gif", true) }
            ?.let { requestMovie(it, source) }
        var movie = requestedMovie
        if (requestedMovie != null) {
            lastDrawnMovieByLayer[layerCacheKey] = requestedMovie
            while (lastDrawnMovieByLayer.size > MAX_LAST_MOVIE_ENTRIES) {
                val iterator = lastDrawnMovieByLayer.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
        val requestedBitmap = if (requestedMovie == null && resolved != null) {
            requestBitmap(
                path = selectedPath,
                source = source,
                animatedFrame = animateFrames && framePaths.size > 1,
            )
        } else {
            null
        }
        // 帧序列 / gallery_2d 预取：当前帧两侧都提前进入解码队列，向前播放
        // 或时间轴跳变都能直接命中；切换期间仍由每图层最后成功帧托底。
        if (animateFrames && requestedMovie == null && framePaths.size > 1) {
            listOf((frameIndex + 1) % framePaths.size).forEach { adjacentIndex ->
                val rawAdjacentPath = framePaths[adjacentIndex]
                val preferredPath = if (usingNightWeatherSheet) {
                    watchWeatherNightAssetReference(rawAdjacentPath)
                } else {
                    rawAdjacentPath
                }
                if (preferredPath == selectedPath) return@forEach
                val adjacentAsset = resolveAsset(
                    preferredPath,
                    source,
                    conventionalDirectory = "images",
                ) ?: resolveAsset(rawAdjacentPath, source, conventionalDirectory = "images")
                    ?: return@forEach
                if (adjacentAsset.file.extension.equals("gif", ignoreCase = true)) {
                    requestMovie(adjacentAsset, source)
                } else {
                    requestBitmap(adjacentAsset.key, source, animatedFrame = true)
                }
            }
        }
        // 跨帧兜底：新帧还没解码好就继续画这一层上次画成的那张。
        var drawingNightWeatherSheet = usingNightWeatherSheet
        val fallbackKey = "$layerCacheKey\u0000${if (usingNightWeatherSheet) "night" else "day"}"
        val bitmap = if (requestedMovie == null) {
            val usable = requestedBitmap?.takeIf { !it.isRecycled }
            if (usable != null) {
                lastDrawnFrameByLayer[fallbackKey] = usable
                while (lastDrawnFrameByLayer.size > MAX_LAST_FRAME_ENTRIES) {
                    val iterator = lastDrawnFrameByLayer.entries.iterator()
                    if (!iterator.hasNext()) break
                    iterator.next()
                    iterator.remove()
                }
                usable
            } else {
                // 回收过的位图不能画：命中就当成没有，避免画到已释放内存。
                lastDrawnFrameByLayer[fallbackKey]?.takeIf { !it.isRecycled }
                    ?: if (usingNightWeatherSheet) {
                        // 日夜切换时夜图也走异步解码；准备好之前沿用白天最后一帧，
                        // 避免条件图标短暂透明。
                        drawingNightWeatherSheet = false
                        lastDrawnFrameByLayer["$layerCacheKey\u0000day"]
                            ?.takeIf { !it.isRecycled }
                    } else {
                        null
                    }
            }
        } else {
            null
        }
        if (movie == null && bitmap == null) {
            // Switching a dynamic path from/to a raw GIF must retain the old
            // Movie until the new resource is decoded, just like bitmap frames.
            movie = lastDrawnMovieByLayer[layerCacheKey]
        }
        if (
            trackTransitionAssetReadiness &&
            resolved != null &&
            movie == null &&
            bitmap == null
        ) {
            transitionFrameHasUnreadyVisualAsset = true
        }
        val nativeWidth = movie?.width()?.toFloat() ?: bitmap?.width?.toFloat() ?: return
        val nativeHeight = movie?.height()?.toFloat() ?: bitmap?.height?.toFloat() ?: return
        val width = dynamicNumber(attrs["width"] ?: sharedSize, nativeWidth, source).coerceAtLeast(1f)
        val height = dynamicNumber(attrs["height"] ?: sharedSize, nativeHeight, source).coerceAtLeast(1f)
        val grid = if (sprite) {
            parseWatchGrid(
                attrs["cond_grid"]
                    ?: attrs["grid"]
                    ?: attrs["sprite_grid"]
                    ?: buildString {
                        append(attrs["columns"] ?: "1")
                        append('x')
                        append(attrs["rows"] ?: "1")
                    },
            )
        } else {
            1 to 1
        }
        val index = if (sprite) {
            val dayIndex = watchSpriteIndex(
                dynamicNumber(
                    attrs["cond_value"] ?: attrs["condition"] ?: attrs["value"],
                    1f,
                    source,
                ),
                grid.first * grid.second,
            )
            if (drawingNightWeatherSheet && grid.first == 3 && grid.second == 3) {
                watchWeatherNightSpriteIndex(dayIndex)
            } else {
                dayIndex
            }
        } else {
            0
        }
        val dst = anchoredRect(width, height, attrs["alignment"])
        imagePaint.alpha = paintAlpha(alpha)
        imagePaint.colorFilter = tintFilter(resolveColor(attrs["color"], Color.WHITE, source))
        if (movie != null) {
            val elapsed = (SystemClock.uptimeMillis() - frameStartedAtUptimeMs).coerceAtLeast(0L)
            // Movie.duration() is allowed to return 0 for GIFs without a
            // reliable duration header. Treating that as 1 ms pins setTime()
            // to frame zero forever; Android's own Movie sample uses a
            // one-second fallback so the decoder can still advance and loop.
            val durationMs = movie.duration().takeIf { it > 0 } ?: DEFAULT_MOVIE_DURATION_MS
            movie.setTime(if (animateFrames) (elapsed % durationMs).toInt() else 0)
            canvas.save()
            canvas.translate(dst.left, dst.top)
            canvas.scale(width / nativeWidth, height / nativeHeight)
            movie.draw(canvas, 0f, 0f, imagePaint)
            canvas.restore()
        } else {
            val nonNullBitmap = bitmap ?: return
            val cellWidth = (nonNullBitmap.width / grid.first.coerceAtLeast(1)).coerceAtLeast(1)
            val cellHeight = (nonNullBitmap.height / grid.second.coerceAtLeast(1)).coerceAtLeast(1)
            val col = (index % grid.first).coerceIn(0, grid.first - 1)
            val row = (index / grid.first).coerceIn(0, grid.second - 1)
            bitmapFontSourceRect.set(
                col * cellWidth,
                row * cellHeight,
                (col + 1) * cellWidth,
                (row + 1) * cellHeight,
            )
            canvas.drawBitmap(nonNullBitmap, bitmapFontSourceRect, dst, imagePaint)
        }
        // WM_FORMAT_1.0.md §3：tap_action 是任意图层的通用属性，不只属于
        // image_tap 专用图层；带显式 tap_action 的普通 image 也要可点。
        if (tapAction || !attrs["tap_action"].isNullOrBlank()) recordTapTarget(canvas, dst, attrs)
        imagePaint.colorFilter = null
    }

    private fun isWeatherNightFrame(): Boolean {
        val weather = frameDataSnapshot.weather
        weather?.isDaytime?.let { return !it }
        return (weather?.icon ?: frameDataSnapshot.weatherIcon)
            ?.trim()
            ?.endsWith("n", ignoreCase = true) == true
    }

    private fun drawText(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val raw = attrs["text"].orEmpty()
        if (raw.isBlank()) return
        val value = evaluateText(raw, source)
        val designSize = dynamicNumber(attrs["text_size"] ?: attrs["size"], 24f, source)
            .coerceIn(1f, DESIGN_SIZE)
        resolveBitmapFont(attrs["font"], source)?.let { bitmapFont ->
            drawBitmapText(canvas, value, attrs, alpha, designSize, bitmapFont, source)
            return
        }
        val size = WatchTextLayout.ttfTextSize(designSize)
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        textPaint.textSize = size
        textPaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        textPaint.alpha = paintAlpha(alpha)
        val transform = attrs["transform"].orEmpty().lowercase(Locale.ROOT)
        val text = when (transform) {
            "uc", "uppercase" -> value.uppercase(Locale.getDefault())
            "lc", "lowercase" -> value.lowercase(Locale.getDefault())
            else -> value
        }
        val align = attrs["alignment"].orEmpty().lowercase(Locale.ROOT)
        textPaint.textAlign = when {
            align.endsWith('l') -> Paint.Align.LEFT
            align.endsWith('r') -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        val x = when (textPaint.textAlign) {
            Paint.Align.LEFT -> 0f
            Paint.Align.RIGHT -> 0f
            else -> 0f
        }
        val metrics = textPaint.fontMetrics
        val y = when {
            align.startsWith('t') -> -metrics.ascent
            align.startsWith('b') -> -metrics.descent
            else -> -(metrics.ascent + metrics.descent) / 2f
        }
        if (!attrs["tap_action"].isNullOrBlank()) {
            // 文字图层（如心率读数）也可以携带 tap_action；用文字包围盒记录
            // 点按区域，坐标随当前画布矩阵映射回视图空间。
            val textWidth = textPaint.measureText(text)
            val left = when (textPaint.textAlign) {
                Paint.Align.LEFT -> x
                Paint.Align.RIGHT -> x - textWidth
                else -> x - textWidth / 2f
            }
            rectScratch.set(left, y + metrics.ascent, left + textWidth, y + metrics.descent)
            recordTapTarget(canvas, rectScratch, attrs)
        }
        // 描边通道：先 STROKE 后 FILL——Android 描边以字形轮廓为中心线，
        // 内半会被随后的正文填充盖住，因此外观宽度 = o_size 需要
        // strokeWidth = 2×o_size。Glow 用多环 STROKE 由外向内递增透明度。
        resolveOutline(attrs, source)?.let { outline ->
            drawTextOutline(canvas, text, x, y, outline, alpha)
        }
        canvas.drawText(text, x, y, textPaint)
    }

    /**
     * 求值 outline/o_size/o_color/o_opacity 四个属性。没有描边（含尺寸或
     * 不透明度为 0）时返回 null，渲染路径零开销。o_size 经全局封顶
     * [MAX_WATCH_OUTLINE_SIZE] 收口，防恶意表盘拖帧。
     */
    private fun resolveOutline(attrs: Map<String, String>, source: PreparedSource): ResolvedOutline? {
        val mode = watchOutlineMode(attrs["outline"])
        if (mode == WatchOutlineMode.NONE) return null
        val size = watchOutlineSize(
            mode,
            attrs["o_size"]?.let { raw -> dynamicNumber(raw, watchOutlineDefaultSize(mode), source) },
        )
        if (size <= 0f) return null
        val opacity = dynamicNumber(attrs["o_opacity"], 100f, source).coerceIn(0f, 100f) / 100f
        if (opacity <= 0f) return null
        return ResolvedOutline(
            mode = mode,
            size = size,
            color = resolveColor(attrs["o_color"], Color.WHITE, source),
            opacity = opacity,
        )
    }

    /** TTF 文字描边。独立 [outlinePaint]，每次调用重设文字属性与 stroke。 */
    private fun drawTextOutline(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        outline: ResolvedOutline,
        alpha: Float,
    ) {
        outlinePaint.typeface = textPaint.typeface
        outlinePaint.textSize = textPaint.textSize
        outlinePaint.textAlign = textPaint.textAlign
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.color = outline.color
        val rings = if (outline.mode == WatchOutlineMode.GLOW) watchGlowRingCount(outline.size) else 1
        // 由外向内画：外环最淡、内环最实，后画的内环覆盖重叠区。
        for (ring in rings downTo 1) {
            outlinePaint.strokeWidth = outline.size * 2f * ring / rings
            val ringAlpha = if (outline.mode == WatchOutlineMode.GLOW) {
                watchGlowRingAlpha(ring, rings)
            } else {
                1f
            }
            outlinePaint.alpha = paintAlpha(alpha * outline.opacity * ringAlpha)
            canvas.drawText(text, x, y, outlinePaint)
        }
    }

    private fun drawBitmapText(
        canvas: Canvas,
        text: String,
        attrs: Map<String, String>,
        alpha: Float,
        designSize: Float,
        preparedFont: PreparedBitmapFont,
        source: PreparedSource,
    ) {
        val font = preparedFont.definition
        val renderedText = font.normalizeText(text)
        val scale = designSize / font.nominalSize.coerceAtLeast(1).toFloat()
        val align = attrs["alignment"].orEmpty().lowercase(Locale.ROOT)
        val width = font.measureAdvance(renderedText) * scale
        val startX = when {
            align.endsWith('l') -> 0f
            align.endsWith('r') -> -width
            else -> -width / 2f
        }
        val lineHeight = font.lineHeight * scale
        val lineTop = when {
            align.startsWith('t') -> 0f
            align.startsWith('b') -> -lineHeight
            else -> -lineHeight / 2f
        }
        if (!attrs["tap_action"].isNullOrBlank()) {
            rectScratch.set(startX, lineTop, startX + width, lineTop + lineHeight)
            recordTapTarget(canvas, rectScratch, attrs)
        }
        // 描边通道：整串字形按 8 方向偏移重绘成描边色剪影。硬描边 = 一环，
        // Glow = 多环（环数 ceil(o_size/2)，MAX_WATCH_GLOW_RINGS 封顶）×
        // 8 方向、透明度随环衰减。每帧成本上限 = 8 环 × 8 方向 = 64 次
        // 整串重绘，由环数封顶 + o_size 全局封顶（120）双重兜底。
        resolveOutline(attrs, source)?.let { outline ->
            bitmapOutlinePaint.colorFilter = android.graphics.PorterDuffColorFilter(
                outline.color,
                android.graphics.PorterDuff.Mode.SRC_IN,
            )
            val rings = if (outline.mode == WatchOutlineMode.GLOW) watchGlowRingCount(outline.size) else 1
            for (ring in rings downTo 1) {
                val radius = outline.size * ring / rings
                val ringAlpha = if (outline.mode == WatchOutlineMode.GLOW) {
                    watchGlowRingAlpha(ring, rings)
                } else {
                    1f
                }
                bitmapOutlinePaint.alpha = paintAlpha(alpha * outline.opacity * ringAlpha)
                var direction = 0
                while (direction < BITMAP_OUTLINE_DIRECTIONS.size) {
                    drawBitmapGlyphRun(
                        canvas = canvas,
                        text = renderedText,
                        preparedFont = preparedFont,
                        source = source,
                        scale = scale,
                        startX = startX,
                        lineTop = lineTop,
                        offsetX = BITMAP_OUTLINE_DIRECTIONS[direction] * radius,
                        offsetY = BITMAP_OUTLINE_DIRECTIONS[direction + 1] * radius,
                        paint = bitmapOutlinePaint,
                    )
                    direction += 2
                }
            }
            bitmapOutlinePaint.colorFilter = null
        }
        imagePaint.alpha = paintAlpha(alpha)
        imagePaint.colorFilter = tintFilter(resolveColor(attrs["color"], Color.WHITE, source))
        drawBitmapGlyphRun(
            canvas = canvas,
            text = renderedText,
            preparedFont = preparedFont,
            source = source,
            scale = scale,
            startX = startX,
            lineTop = lineTop,
            offsetX = 0f,
            offsetY = 0f,
            paint = imagePaint,
        )
        imagePaint.colorFilter = null
    }

    /** 用 [paint] 在 (offsetX, offsetY) 偏移处绘制整串位图字形（含字距）。 */
    private fun drawBitmapGlyphRun(
        canvas: Canvas,
        text: String,
        preparedFont: PreparedBitmapFont,
        source: PreparedSource,
        scale: Float,
        startX: Float,
        lineTop: Float,
        offsetX: Float,
        offsetY: Float,
        paint: Paint,
    ) {
        val font = preparedFont.definition
        var cursorX = startX + offsetX
        val top = lineTop + offsetY
        var previous: Int? = null
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val glyph = font.glyphs[codePoint]
            if (glyph != null) {
                cursorX += (previous?.let { font.kerning(it, codePoint) } ?: 0) * scale
                val bitmap = preparedFont.pageBitmaps.getOrNull(glyph.page)
                    ?.takeUnless(Bitmap::isRecycled)
                    ?: preparedFont.pageAssetPaths.getOrNull(glyph.page)
                        ?.let { path -> requestBitmap(path, source) }
                        ?.also { loaded ->
                            preparedFont.pageBitmaps[glyph.page] = loaded
                        }
                if (bitmap != null && !bitmap.isRecycled) {
                    val sourceRect = mapWatchBitmapGlyphSourceRect(
                        glyph = glyph,
                        atlasWidth = font.atlasWidth,
                        atlasHeight = font.atlasHeight,
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                    )
                    if (sourceRect != null) {
                        bitmapFontSourceRect.set(
                            sourceRect.left,
                            sourceRect.top,
                            sourceRect.right,
                            sourceRect.bottom,
                        )
                        bitmapFontDestRect.set(
                            cursorX + glyph.xOffset * scale,
                            top + glyph.yOffset * scale,
                            cursorX + (glyph.xOffset + glyph.width) * scale,
                            top + (glyph.yOffset + glyph.height) * scale,
                        )
                        canvas.drawBitmap(bitmap, bitmapFontSourceRect, bitmapFontDestRect, paint)
                    }
                }
                cursorX += glyph.xAdvance * scale
                previous = codePoint
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun drawSeries(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val series = attrs["series"] ?: attrs["name"] ?: attrs["text"] ?: return
        val currentPos = attrs["current_pos"].orEmpty()
        val seriesKey = buildString {
            append(series).append('|').append(currentPos).append('|')
            append(WatchSeries.currentIndex(series, calendar)).append('|')
            append(calendar.get(Calendar.YEAR)).append('|').append(calendar.get(Calendar.MONTH))
        }
        if (source.seriesCache.size >= MAX_SERIES_CACHE_ENTRIES && seriesKey !in source.seriesCache) {
            source.seriesCache.clear()
        }
        val items = source.seriesCache.getOrPut(seriesKey) { WatchSeries.items(series, calendar, currentPos) }
        if (items.isEmpty()) return
        val currentDesignSize = dynamicNumber(attrs["text_size"], 40f, source).coerceIn(1f, DESIGN_SIZE)
        val otherDesignSize = dynamicNumber(attrs["text_size2"], currentDesignSize, source)
            .coerceIn(1f, DESIGN_SIZE)
        val currentSize = WatchTextLayout.ttfTextSize(currentDesignSize)
        val otherSize = WatchTextLayout.ttfTextSize(otherDesignSize)
        val spacing = dynamicNumber(attrs["spacing"], 10f, source)
        val orientation = attrs["orientation"].orEmpty().equals("v", true)
        val currentColor = resolveColor(attrs["color"], Color.WHITE, source)
        val otherColor = resolveColor(attrs["color2"], Color.GRAY, source)
        val currentTypeface = resolveTypeface(attrs["font"], source)
        val otherTypeface = resolveTypeface(attrs["font2"] ?: attrs["font"], source)
        val advance = spacing + currentSize * 1.4f
        items.asSequence()
            .filter { item -> kotlin.math.abs(item.offset * advance) <= DESIGN_SIZE / 2f + currentSize * 2f }
            .forEach { item ->
            val size = if (item.isCurrent) currentSize else otherSize
            val offset = item.offset * advance
            textPaint.typeface = if (item.isCurrent) currentTypeface else otherTypeface
            textPaint.textSize = size
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = if (item.isCurrent) currentColor else otherColor
            textPaint.alpha = paintAlpha(alpha)
            val metrics = textPaint.fontMetrics
            val baseline = -(metrics.ascent + metrics.descent) / 2f
            canvas.save()
            if (orientation) canvas.translate(0f, offset) else canvas.translate(offset, 0f)
            canvas.drawText(item.value, 0f, baseline, textPaint)
            canvas.restore()
        }
    }

    private fun drawCalendar(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val cellWidth = dynamicNumber(attrs["cell_width"], 48f, source).coerceAtLeast(1f)
        val cellHeight = dynamicNumber(attrs["cell_height"], 42f, source).coerceAtLeast(1f)
        val marginX = dynamicNumber(attrs["margin_x"], 8f, source)
        val marginY = dynamicNumber(attrs["margin_y"], 8f, source)
        val headerDesignSize = dynamicNumber(attrs["text_size"], 14f, source).coerceIn(1f, DESIGN_SIZE)
        val dateDesignSize = dynamicNumber(attrs["text_size2"], headerDesignSize, source)
            .coerceIn(1f, DESIGN_SIZE)
        val headerSize = WatchTextLayout.ttfTextSize(headerDesignSize)
        val dateSize = WatchTextLayout.ttfTextSize(dateDesignSize)
        val headerColor = resolveColor(attrs["color"], Color.WHITE, source)
        val dateColor = resolveColor(attrs["color2"], Color.rgb(33, 150, 243), source)
        val start = attrs["dow_start"]?.toIntOrNull()?.coerceIn(0, 6) ?: 1
        val headerLetters = attrs["no_letters_header"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val outsideOpacity = dynamicNumber(attrs["outside_opacity"], 60f, source).coerceIn(0f, 100f) / 100f
        val dayKey = "${calendar.get(Calendar.YEAR)}|${calendar.get(Calendar.MONTH)}|${calendar.get(Calendar.DAY_OF_MONTH)}|$start"
        if (source.calendarCellsCache.size >= MAX_CALENDAR_CACHE_ENTRIES && dayKey !in source.calendarCellsCache) {
            source.calendarCellsCache.clear()
        }
        val grid = source.calendarCellsCache.getOrPut(dayKey) { WatchCalendarGrid.cells(calendar, start) }
        val labelKey = "$start|$headerLetters|${Locale.getDefault().toLanguageTag()}"
        val labels = source.calendarLabelsCache.getOrPut(labelKey) {
            WatchCalendarGrid.weekdayLabels(start, headerLetters)
        }
        val pitchX = cellWidth + marginX
        val pitchY = cellHeight + marginY
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        textPaint.textSize = headerSize
        textPaint.color = headerColor
        textPaint.alpha = paintAlpha(alpha)
        val left = -pitchX * 3.5f
        val top = -((pitchY * 7f) / 2f)
        val metrics = textPaint.fontMetrics
        val headerBaseline = top - marginY - metrics.descent
        labels.forEachIndexed { index, label ->
            canvas.drawText(label, left + pitchX * (index + 0.5f), headerBaseline, textPaint)
        }
        textPaint.typeface = resolveTypeface(attrs["font2"] ?: attrs["font"], source)
        textPaint.textSize = dateSize
        val dateMetrics = textPaint.fontMetrics
        grid.forEach { cell ->
            val x = left + pitchX * (cell.column + 0.5f)
            val y = top + pitchY * (cell.row + 1.5f)
            val valueColor = if (cell.isToday) contrastColor(dateColor) else dateColor
            if (cell.isToday) {
                shapePaint.color = dateColor
                shapePaint.alpha = paintAlpha(alpha)
                canvas.drawCircle(x, y - (dateMetrics.ascent + dateMetrics.descent) / 2f, dateSize * 0.62f, shapePaint)
            }
            textPaint.color = valueColor
            textPaint.alpha = paintAlpha(alpha * if (cell.isOutside) outsideOpacity else 1f)
            canvas.drawText(cell.day.toString(), x, y - (dateMetrics.ascent + dateMetrics.descent) / 2f, textPaint)
        }
    }

    private fun contrastColor(background: Int): Int {
        val luminance = (Color.red(background) * 299 + Color.green(background) * 587 + Color.blue(background) * 114) / 1000
        return if (luminance > 128) Color.BLACK else Color.WHITE
    }

    private fun drawRing(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val outer = dynamicNumber(
            attrs["radius_outer"],
            dynamicNumber(attrs["radius"], 100f, source),
            source,
        ).coerceAtLeast(1f)
        val inner = dynamicNumber(attrs["radius_inner"], outer - 8f, source).coerceIn(0f, outer - 0.5f)
        val stroke = (outer - inner).coerceAtLeast(1f)
        val radius = inner + stroke / 2f
        val total = dynamicNumber(attrs["angle_total"], 360f, source).coerceAtLeast(1f)
        val sweep = dynamicNumber(attrs["angle"], total, source).coerceIn(0f, total)
        val clockwise = !attrs["is_clockwise"].equals("N", ignoreCase = true)
        strokePaint.strokeWidth = stroke
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = if (attrs["end_style"].equals("round", true)) Paint.Cap.ROUND else Paint.Cap.BUTT
        strokePaint.color = resolveColor(attrs["color3"], Color.TRANSPARENT, source)
        strokePaint.alpha = paintAlpha(alpha * dynamicNumber(attrs["outside_opacity"], 100f, source) / 100f)
        if (strokePaint.alpha > 0) canvas.drawCircle(0f, 0f, radius, strokePaint)
        strokePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        strokePaint.alpha = paintAlpha(alpha)
        rectScratch.set(-radius, -radius, radius, radius)
        canvas.drawArc(rectScratch, -90f, if (clockwise) sweep else -sweep, false, strokePaint)
    }

    private fun drawProgress(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val sharedSize = attrs["size"]
        val width = dynamicNumber(attrs["width"] ?: sharedSize, 100f, source).coerceAtLeast(1f)
        val height = dynamicNumber(attrs["height"] ?: sharedSize, 20f, source).coerceAtLeast(1f)
        val pct = dynamicNumber(attrs["pct_complete"], 0f, source).coerceIn(0f, 100f) / 100f
        val radius = dynamicNumber(attrs["radius"], height / 2f, source).coerceAtLeast(0f).coerceAtMost(height / 2f)
        val left = -width / 2f
        val top = -height / 2f
        shapePaint.color = resolveColor(attrs["color3"], Color.DKGRAY, source)
        shapePaint.alpha = paintAlpha(alpha * dynamicNumber(attrs["outside_opacity"], 100f, source) / 100f)
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, shapePaint)
        shapePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        shapePaint.alpha = paintAlpha(alpha)
        canvas.drawRoundRect(left, top, left + width * pct, top + height, radius, radius, shapePaint)
    }

    private fun drawShape(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val sharedSize = attrs["size"]
        val width = dynamicNumber(attrs["width"] ?: sharedSize, 64f, source).coerceAtLeast(1f)
        val height = dynamicNumber(attrs["height"] ?: sharedSize, width, source).coerceAtLeast(1f)
        val shape = attrs["shape"].orEmpty().lowercase(Locale.ROOT)
        // 描边通道：与文字同一规格，先 STROKE 环、后 FILL 本体（内半被填充盖住）。
        resolveOutline(attrs, source)?.let { outline ->
            drawShapeOutline(canvas, shape, width, height, outline, alpha)
        }
        shapePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        shapePaint.alpha = paintAlpha(alpha)
        when {
            shape.contains("circle") -> canvas.drawOval(-width / 2f, -height / 2f, width / 2f, height / 2f, shapePaint)
            shape.contains("triangle") -> {
                pathScratch.reset()
                pathScratch.moveTo(0f, -height / 2f)
                pathScratch.lineTo(width / 2f, height / 2f)
                pathScratch.lineTo(-width / 2f, height / 2f)
                pathScratch.close()
                canvas.drawPath(pathScratch, shapePaint)
            }
            else -> canvas.drawRect(-width / 2f, -height / 2f, width / 2f, height / 2f, shapePaint)
        }
    }

    /** shape 描边：硬描边一环 / Glow 多环，几何与本体完全一致。 */
    private fun drawShapeOutline(
        canvas: Canvas,
        shape: String,
        width: Float,
        height: Float,
        outline: ResolvedOutline,
        alpha: Float,
    ) {
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.color = outline.color
        val rings = if (outline.mode == WatchOutlineMode.GLOW) watchGlowRingCount(outline.size) else 1
        for (ring in rings downTo 1) {
            outlinePaint.strokeWidth = outline.size * 2f * ring / rings
            val ringAlpha = if (outline.mode == WatchOutlineMode.GLOW) {
                watchGlowRingAlpha(ring, rings)
            } else {
                1f
            }
            outlinePaint.alpha = paintAlpha(alpha * outline.opacity * ringAlpha)
            when {
                shape.contains("circle") ->
                    canvas.drawOval(-width / 2f, -height / 2f, width / 2f, height / 2f, outlinePaint)
                shape.contains("triangle") -> {
                    pathScratch.reset()
                    pathScratch.moveTo(0f, -height / 2f)
                    pathScratch.lineTo(width / 2f, height / 2f)
                    pathScratch.lineTo(-width / 2f, height / 2f)
                    pathScratch.close()
                    canvas.drawPath(pathScratch, outlinePaint)
                }
                else -> canvas.drawRect(-width / 2f, -height / 2f, width / 2f, height / 2f, outlinePaint)
            }
        }
    }

    private fun drawRounded(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val sharedSize = attrs["size"]
        val width = dynamicNumber(attrs["width"] ?: sharedSize, 64f, source).coerceAtLeast(1f)
        val height = dynamicNumber(attrs["height"] ?: sharedSize, 64f, source).coerceAtLeast(1f)
        val radius = dynamicNumber(attrs["radius"], min(width, height) / 4f, source).coerceAtLeast(0f)
        shapePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        shapePaint.alpha = paintAlpha(alpha)
        canvas.drawRoundRect(-width / 2f, -height / 2f, width / 2f, height / 2f, radius, radius, shapePaint)
    }

    private fun drawMarkers(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val count = dynamicNumber(attrs["m_count"], 12f, source).roundToInt().coerceIn(1, 120)
        val radius = dynamicNumber(attrs["radius"], 220f, source)
        val markWidth = dynamicNumber(attrs["m_width"], 6f, source).coerceAtLeast(1f)
        val markHeight = dynamicNumber(attrs["m_height"], 20f, source).coerceAtLeast(1f)
        strokePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        strokePaint.alpha = paintAlpha(alpha)
        strokePaint.strokeWidth = markWidth
        strokePaint.strokeCap = if (attrs["shape"].equals("Circle", true)) Paint.Cap.ROUND else Paint.Cap.BUTT
        repeat(count) { index ->
            val angle = index * 360f / count - 90f
            val radians = Math.toRadians(angle.toDouble())
            val x1 = cos(radians).toFloat() * (radius - markHeight / 2f)
            val y1 = sin(radians).toFloat() * (radius - markHeight / 2f)
            val x2 = cos(radians).toFloat() * (radius + markHeight / 2f)
            val y2 = sin(radians).toFloat() * (radius + markHeight / 2f)
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }
    }

    private fun drawHourMinuteMarkers(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val radius = dynamicNumber(attrs["radius"], 220f, source)
        strokePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        strokePaint.alpha = paintAlpha(alpha)
        strokePaint.strokeWidth = 3f
        repeat(60) { index ->
            val major = index % 5 == 0
            val length = if (major) 22f else 10f
            val angle = index * 6f - 90f
            val radians = Math.toRadians(angle.toDouble())
            canvas.drawLine(
                cos(radians).toFloat() * (radius - length),
                sin(radians).toFloat() * (radius - length),
                cos(radians).toFloat() * radius,
                sin(radians).toFloat() * radius,
                strokePaint,
            )
        }
    }

    private fun drawCurvedText(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val text = evaluateText(attrs["text"].orEmpty(), source)
        if (text.isBlank()) return
        val radius = dynamicNumber(attrs["radius"], 180f, source).coerceAtLeast(1f)
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        textPaint.textSize = WatchTextLayout.ttfTextSize(
            dynamicNumber(attrs["text_size"] ?: attrs["size"], 24f, source),
        )
        textPaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        textPaint.alpha = paintAlpha(alpha)
        textPaint.textAlign = Paint.Align.CENTER
        pathScratch.reset()
        val arc = WatchTextLayout.curveArc(attrs["curve_dir"])
        pathScratch.addArc(-radius, -radius, radius, radius, arc.startDegrees, arc.sweepDegrees)
        canvas.drawTextOnPath(text, pathScratch, 0f, 0f, textPaint)
    }

    private fun drawTextRing(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val radius = dynamicNumber(attrs["radius"], 180f, source).coerceAtLeast(1f)
        val start = dynamicNumber(attrs["angle_start"], 0f, source)
        val end = dynamicNumber(attrs["angle_end"], 360f, source)
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        textPaint.textSize = WatchTextLayout.ttfTextSize(
            dynamicNumber(attrs["text_size"] ?: attrs["size"], 24f, source),
        )
        textPaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        textPaint.alpha = paintAlpha(alpha)
        textPaint.textAlign = Paint.Align.CENTER
        TEXT_RING_LABELS.forEachIndexed { index, label ->
            val angle = start + (end - start) * index / TEXT_RING_LABELS.size - 90f
            val radians = Math.toRadians(angle.toDouble())
            canvas.save()
            canvas.rotate(angle + 90f)
            canvas.drawText(label, 0f, -radius, textPaint)
            canvas.restore()
        }
    }

    /**
     * Bounded Canvas chart renderer for the common WM_FORMAT chart family.
     * It intentionally treats missing/unsupported data as an empty series,
     * never as a literal expression or a face-wide failure.
     */
    private fun drawChart(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val width = dynamicNumber(attrs["width"], 300f, source).coerceIn(1f, DESIGN_SIZE)
        val height = dynamicNumber(attrs["height"], 120f, source).coerceIn(1f, DESIGN_SIZE)
        val rawValues = evaluateText(
            attrs["data_points"]
                ?: attrs["values"]
                ?: attrs["data"]
                ?: attrs["points"]
                ?: attrs["lines"]
                ?: "",
            source,
        )
        val values = source.chartValuesCache.getOrPut(rawValues) {
            parseWatchChartValues(rawValues)
        }.orEmpty()
        if (values.isEmpty()) return
        val minMax = parseChartMinMax(attrs["min_max"], values)
        val minValue = minMax.first
        val maxValue = minMax.second
        val range = (maxValue - minValue).takeIf { it.isFinite() && it > 0f } ?: 1f
        val left = -width / 2f
        val top = -height / 2f
        val chartType = attrs["chart_type"]?.lowercase(Locale.ROOT)
            ?: attrs["type"]?.lowercase(Locale.ROOT)
            ?: "line"
        val color = resolveColor(attrs["color"], Color.WHITE, source)
        val markerColor = resolveColor(attrs["color4"], color, source)
        val lineWidth = dynamicNumber(attrs["line_width"], 4f, source).coerceIn(1f, 32f)
        val markerSize = dynamicNumber(attrs["marker_size"], lineWidth * 1.5f, source)
            .coerceIn(0f, 32f)

        fun yFor(value: Float): Float =
            top + height - ((value - minValue) / range).coerceIn(0f, 1f) * height

        // Optional horizontal reference lines.  They are parsed with the
        // same point cap and do not allocate an unbounded shader/label list.
        val rawReferenceLines = evaluateText(attrs["reference_lines"].orEmpty(), source)
        val referenceLines = source.chartValuesCache.getOrPut(rawReferenceLines) {
            parseWatchChartValues(rawReferenceLines)
        }.orEmpty()
        repeat(minOf(referenceLines.size, MAX_CHART_REFERENCE_LINES)) { index ->
            val reference = referenceLines[index]
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = 1f
            strokePaint.color = resolveColor(attrs["color3"], Color.GRAY, source)
            strokePaint.alpha = paintAlpha(alpha * 0.6f)
            canvas.drawLine(left, yFor(reference), left + width, yFor(reference), strokePaint)
        }

        if (chartType.contains("bar")) {
            val spacing = width / values.size.coerceAtLeast(1)
            val thickness = dynamicNumber(attrs["bar_thickness"], 72f, source)
                .coerceIn(5f, 100f) / 100f
            val barWidth = (spacing * thickness).coerceAtLeast(1f)
            values.forEachIndexed { index, value ->
                val cx = left + spacing * (index + 0.5f)
                val y = yFor(value)
                val baseline = yFor(minValue)
                val radius = dynamicNumber(attrs["corner_type"], 0f, source)
                    .coerceIn(0f, min(barWidth, height) / 2f)
                shapePaint.color = color
                shapePaint.alpha = paintAlpha(alpha)
                canvas.drawRoundRect(
                    cx - barWidth / 2f,
                    min(y, baseline),
                    cx + barWidth / 2f,
                    max(y, baseline),
                    radius,
                    radius,
                    shapePaint,
                )
            }
            return
        }

        pathScratch.reset()
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) left + width / 2f else {
                left + width * index / (values.lastIndex.toFloat())
            }
            val y = yFor(value)
            if (index == 0) pathScratch.moveTo(x, y) else pathScratch.lineTo(x, y)
        }
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = lineWidth
        strokePaint.color = color
        strokePaint.alpha = paintAlpha(alpha)
        canvas.drawPath(pathScratch, strokePaint)
        if (markerSize > 0f) {
            shapePaint.color = markerColor
            shapePaint.alpha = paintAlpha(alpha)
            values.forEachIndexed { index, value ->
                val x = if (values.size == 1) left + width / 2f else {
                    left + width * index / (values.lastIndex.toFloat())
                }
                canvas.drawCircle(x, yFor(value), markerSize / 2f, shapePaint)
            }
        }
    }

    private fun parseChartMinMax(raw: String?, values: List<Float>): Pair<Float, Float> {
        val parts = raw.orEmpty().split(Regex("\\s+to\\s+|[,;]"), limit = 2)
        val explicitMin = parts.getOrNull(0)?.trim()?.toFloatOrNull()
        val explicitMax = parts.getOrNull(1)?.trim()?.toFloatOrNull()
        val minValue = explicitMin ?: values.minOrNull() ?: 0f
        val maxValue = explicitMax ?: values.maxOrNull() ?: 1f
        return if (maxValue > minValue) minValue to maxValue else (minValue - 1f) to (maxValue + 1f)
    }

    /**
     * Render the bounded SVG subset prepared off the UI thread. Both XML and
     * native Path parsing finish before source publication, so a frame only
     * reads revision-scoped immutable objects.
     */
    private fun drawSvg(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val path = evaluateText(
            attrs["svg_path"] ?: attrs["svg_asset"] ?: attrs["path"] ?: "",
            source,
        )
        val resolved = resolveAsset(path, source, conventionalDirectory = "images")
            ?: resolveAsset(path, source, conventionalDirectory = "svg")
            ?: return
        val document = source.svgDocuments[resolved.cacheRevision] ?: return
        if (document.paths.isEmpty()) return
        val viewBox = attrs["svg_viewbox"]
            ?.split(Regex("[,\\s]+"))
            ?.mapNotNull(String::toFloatOrNull)
            ?.takeIf { it.size == 4 && it[2] > 0f && it[3] > 0f }
            ?: document.viewBox
        val viewWidth = viewBox[2].coerceAtLeast(1f)
        val viewHeight = viewBox[3].coerceAtLeast(1f)
        val width = dynamicNumber(attrs["width"], document.width ?: viewWidth, source)
            .coerceIn(1f, DESIGN_SIZE)
        val height = dynamicNumber(attrs["height"], document.height ?: viewHeight, source)
            .coerceIn(1f, DESIGN_SIZE)
        val dst = anchoredRect(width, height, attrs["alignment"])
        val scaleX = width / viewWidth
        val scaleY = height / viewHeight
        canvas.save()
        canvas.translate(dst.left, dst.top)
        canvas.scale(scaleX, scaleY)
        canvas.translate(-viewBox[0], -viewBox[1])
        document.paths.forEach { pathData ->
            val fill = pathData.fill?.takeUnless { it.equals("currentColor", true) }
            shapePaint.color = resolveColor(fill ?: attrs["color"], Color.WHITE, source)
            shapePaint.alpha = paintAlpha(alpha)
            canvas.drawPath(pathData.path, shapePaint)
        }
        canvas.restore()
    }

    private fun drawStaticMediaFallback(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        layerCacheKey: String,
    ) {
        val path = evaluateText(attrs["path"] ?: attrs["source"] ?: "", source)
        val resolved = resolveAsset(path, source, conventionalDirectory = "images") ?: return
        val extension = resolved.file.extension.lowercase(Locale.ROOT)
        if (extension in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")) {
            drawImage(canvas, attrs + ("path" to path), alpha, source, layerCacheKey)
        }
    }

    private fun anchoredRect(width: Float, height: Float, alignment: String?): RectF {
        val anchor = alignment.orEmpty().lowercase(Locale.ROOT).ifBlank { "cc" }
        val left = when {
            anchor.endsWith('l') -> 0f
            anchor.endsWith('r') -> -width
            else -> -width / 2f
        }
        val top = when {
            anchor.startsWith('t') -> 0f
            anchor.startsWith('b') -> -height
            else -> -height / 2f
        }
        return rectScratch.apply { set(left, top, left + width, top + height) }
    }

    private fun prepareSource(root: File, sourceGeneration: Long): PreparedSource {
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.isDirectory) { ".watch 表盘安装目录不存在" }
        val watchXml = File(canonicalRoot, "watch.xml").takeIf(File::isFile)
            ?: canonicalRoot.walkTopDown().firstOrNull { it.isFile && it.name.equals("watch.xml", true) }
            ?: error(".watch 表盘缺少 watch.xml")
        val definition = WatchmakerDefinitionParser().parse(watchXml)
        val rootCacheKey = canonicalRoot.absolutePath
        val watchDirectoryKey = (watchXml.parentFile ?: canonicalRoot)
            .relativeTo(canonicalRoot)
            .invariantSeparatorsPath
            .takeUnless { it == "." }
            .orEmpty()
            .lowercase(Locale.ROOT)
        val files = canonicalRoot.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(canonicalRoot).invariantSeparatorsPath.lowercase(Locale.ROOT) }
            .toList()
        val exact = HashMap<String, ResolvedAsset>(files.size)
        val basename = HashMap<String, ResolvedAsset?>()
        val revisionByAbsolutePath = HashMap<String, String>(files.size)
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath.lowercase(Locale.ROOT)
            val cacheRevision = watchAssetCacheRevision(
                rootKey = rootCacheKey,
                relativePath = relative,
                length = file.length(),
                lastModifiedEpochMs = file.lastModified(),
            )
            val resolved = ResolvedAsset(relative, file, cacheRevision)
            exact[relative] = resolved
            revisionByAbsolutePath[file.absolutePath] = cacheRevision
            val key = file.name.lowercase(Locale.ROOT)
            basename[key] = if (basename.containsKey(key)) null else resolved
            sourceDigest.update(cacheRevision.toByteArray(Charsets.UTF_8))
            sourceDigest.update(0.toByte())
        }
        // Editor saves can happen more than once within a filesystem timestamp
        // quantum and may keep the same byte length. Hash only watch.xml (small
        // and already parser-bounded) so definition refreshes cannot be missed
        // without re-reading every image/font asset.
        watchXml.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sourceDigest.update(buffer, 0, count)
            }
        }
        val renderPlanResult = buildWatchLayerRenderPlanResult(definition.layers)
        val initialScript = files.firstOrNull { file ->
            file.relativeTo(canonicalRoot).invariantSeparatorsPath.equals("scripts/script.txt", true)
        }?.takeIf { file -> file.length() in 1..MAX_INITIAL_SCRIPT_BYTES }
            ?.readText(Charsets.UTF_8)
        val bitmapFonts = buildBitmapFontIndex(canonicalRoot, files)
        // SVG XML and native Path construction are both bounded and happen on
        // the IO preparation dispatcher. The generation-gated PreparedSource
        // publishes immutable paths which are only read by the main thread.
        val svgDocuments = HashMap<String, PreparedWatchSvgDocument<Path>>()
        var svgBytes = 0L
        files.asSequence()
            .filter { it.extension.equals("svg", true) }
            .take(MAX_SVG_ASSET_COUNT)
            .forEach { file ->
                if (svgBytes >= MAX_SVG_TOTAL_BYTES) return@forEach
                val remaining = (MAX_SVG_TOTAL_BYTES - svgBytes)
                    .coerceAtMost(MAX_SVG_BYTES.toLong())
                    .toInt()
                val bytes = runCatching {
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(remaining + 1)
                        var offset = 0
                        while (offset < buffer.size) {
                            val count = input.read(buffer, offset, buffer.size - offset)
                            if (count < 0) break
                            if (count == 0) continue
                            offset += count
                        }
                        buffer.copyOf(offset)
                    }
                }.getOrNull() ?: return@forEach
                if (bytes.size > remaining) return@forEach
                svgBytes += bytes.size
                val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath
                    .lowercase(Locale.ROOT)
                val resolved = exact[relative] ?: return@forEach
                // SVG Path objects live inside PreparedSource, so metadata-only
                // revisions are insufficient when an editor rewrites equal-size
                // content inside a coarse filesystem timestamp quantum.
                sourceDigest.update(bytes)
                sourceDigest.update(0.toByte())
                val document = runCatching {
                    parseWatchSvgDocument(bytes.toString(Charsets.UTF_8))
                }.getOrNull()
                prepareWatchSvgDocument(document) { pathData ->
                    runCatching { PathParser.createPathFromPathData(pathData) }.getOrNull()
                }?.let { preparedDocument ->
                    svgDocuments[resolved.cacheRevision] = preparedDocument
                }
            }
        val definitionRevision = sourceDigest.digest().joinToString("") { "%02x".format(it) }
        val galleryAssetPaths = exact.keys.asSequence()
            .filter { relative ->
                val extension = relative.substringAfterLast('.', "").lowercase(Locale.ROOT)
                extension in WATCH_GALLERY_IMAGE_EXTENSIONS && (
                    relative.startsWith("gallery/") ||
                        relative.startsWith("images/gallery/") ||
                        "/gallery/" in relative
                    )
            }
            .sorted()
            .take(MAX_WATCH_GIF_FRAME_PATHS)
            .toList()
        return PreparedSource(
            sourceGeneration = sourceGeneration,
            animationStartedAtUptimeMs = SystemClock.uptimeMillis(),
            root = canonicalRoot,
            rootCacheKey = rootCacheKey,
            definitionRevision = definitionRevision,
            watchDirectoryKey = watchDirectoryKey,
            definition = definition,
            exactFiles = exact,
            uniqueBasenames = basename,
            galleryAssetPaths = galleryAssetPaths,
            revisionByAbsolutePath = revisionByAbsolutePath,
            fontIndex = WatchFontIndex.build(canonicalRoot, files),
            bitmapFonts = bitmapFonts,
            expressionScope = WatchExpressionScope.fromInitialScript(initialScript),
            renderPlan = renderPlanResult.items,
            renderPlanDiagnostics = renderPlanResult.diagnostics,
            svgDocuments = svgDocuments.toMap(),
            needsFastTicks = definition.layers
                .take(MAX_WATCH_LAYER_COUNT)
                .any { layer ->
                    val type = layer.type.lowercase(Locale.ROOT)
                    watchLayerRequiresFrameTicks(type, layer.attributes) ||
                        (type == "chart" && layer.attributes.values.any { value ->
                            DYNAMIC_TAG_PATTERN.containsMatchIn(value)
                        }) ||
                        layer.attributes.values.any { value ->
                            WatchmakerTagResolver.requiresFastTicks(value)
                        }
                },
        )
    }

    private fun buildBitmapFontIndex(
        root: File,
        files: List<File>,
    ): Map<String, PreparedBitmapFont?> {
        val index = HashMap<String, PreparedBitmapFont?>()
        val ambiguous = HashSet<String>()
        fun putUnique(key: String, value: PreparedBitmapFont) {
            if (key.isBlank() || key in ambiguous) return
            val previous = index[key]
            if (previous == null && !index.containsKey(key)) {
                index[key] = value
            } else if (previous?.file != value.file) {
                index[key] = null
                ambiguous += key
            }
        }
        files.asSequence()
            .filter { file ->
                file.extension.equals("fnt", true) && file.length() in 1..MAX_BMFONT_DEFINITION_BYTES
            }
            .forEach { file ->
                val definition = runCatching {
                    parseWatchBitmapFont(file.readText(Charsets.UTF_8))
                }.getOrNull() ?: return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath.lowercase(Locale.ROOT)
                val relativeDirectory = relative.substringBeforeLast('/', "")
                val prepared = PreparedBitmapFont(
                    file = file,
                    relativeDirectory = relativeDirectory,
                    definition = definition,
                    pageAssetPaths = definition.pages.map { page ->
                        if (relativeDirectory.isBlank()) page else "$relativeDirectory/$page"
                    },
                )
                putUnique(relative, prepared)
                putUnique(file.name.lowercase(Locale.ROOT), prepared)
                putUnique(file.nameWithoutExtension.lowercase(Locale.ROOT), prepared)
                definition.face.trim().lowercase(Locale.ROOT)
                    .takeIf(String::isNotBlank)
                    ?.let { face -> putUnique(face, prepared) }
            }
        return index
    }

    private fun resolveBitmapFont(
        reference: String?,
        source: PreparedSource,
    ): PreparedBitmapFont? {
        val raw = reference.orEmpty().trim()
        if (raw.isBlank()) return null
        val explicitlyBitmap = raw.startsWith("bm:", ignoreCase = true)
        if (!explicitlyBitmap && source.fontIndex.resolve(raw) != null) return null
        val normalized = (if (explicitlyBitmap) raw.substring(3) else raw)
            .replace('\\', '/')
            .trimStart('/')
            .lowercase(Locale.ROOT)
        if (normalized.isBlank() || normalized.contains('\u0000')) return null
        val segments = normalized.split('/').filter(String::isNotBlank)
        if (segments.any { it == "." || it == ".." }) return null
        val path = segments.joinToString("/")
        val withExtension = if (path.endsWith(".fnt")) path else "$path.fnt"
        val candidates = linkedSetOf(
            path,
            withExtension,
            path.substringAfterLast('/'),
            withExtension.substringAfterLast('/'),
            "fonts_bm/$withExtension",
            "fonts/$withExtension",
        )
        return candidates.firstNotNullOfOrNull { key -> source.bitmapFonts[key] }
    }

    private fun requestBitmap(
        path: String,
        source: PreparedSource,
        animatedFrame: Boolean = false,
    ): Bitmap? {
        if (path.isBlank()) return null
        val resolved = resolveAsset(path, source, conventionalDirectory = "images") ?: return null
        val assetPrefix = "${source.rootCacheKey}\u0000${resolved.key}\u0000"
        val key = resolved.cacheRevision
        synchronized(cacheLock) {
            val primaryCache = if (animatedFrame) animatedBitmapCache else bitmapCache
            val secondaryCache = if (animatedFrame) bitmapCache else animatedBitmapCache
            primaryCache[key]?.let { return it }
            secondaryCache[key]?.let { return it }
            val previousFrame = primaryCache.entries
                .lastOrNull { (cachedKey, bitmap) -> cachedKey.startsWith(assetPrefix) && !bitmap.isRecycled }
                ?.value
            bitmapFailures[key]?.let { failure ->
                val inCooldown = failure.generation == bindGeneration &&
                    SystemClock.uptimeMillis() - failure.failedAtUptimeMs < BITMAP_FAILURE_RETRY_MS
                if (inCooldown) return previousFrame
                bitmapFailures.remove(key)
            }
            if (bitmapLoading[key] == null) {
                val generation = bindGeneration
                val claim = claimAssetLoadLocked(AssetLoadKind.BITMAP, key, generation)
                if (claim != null) {
                    bitmapLoading[key] = generation
                    val job = assetScope.launch {
                        var decoded: Bitmap? = null
                        try {
                            decoded = decodeBitmapSafely(resolved.file)
                            withContext(Dispatchers.Main.immediate) {
                                val result = decoded
                                var currentRequest = false
                                synchronized(cacheLock) {
                                    currentRequest = bitmapLoading[key] == generation
                                    if (currentRequest) bitmapLoading.remove(key)
                                    // 缓存按内容寻址（cacheRevision），旧 generation 解出的
                                    // 位图对新 generation 一样有效。应用表盘的头一秒里
                                    // refreshToken / 权限 / 可见性会连着触发好几次 bind，
                                    // 每次都推进 generation —— 原来的
                                    // `generation == bindGeneration` 把这些解码结果全部
                                    // 丢弃重来，GIF/图片层在屏上反复消失又出现，
                                    // 就是「应用后剧烈闪烁几次」的来源。
                                    if (currentRequest && !disposed) {
                                        if (result != null) {
                                            val staleKeys = primaryCache.keys
                                                .filter { cachedKey ->
                                                    cachedKey.startsWith(assetPrefix) && cachedKey != key
                                                }
                                            staleKeys.forEach { staleKey ->
                                                primaryCache.remove(staleKey)?.let { staleBitmap ->
                                                    if (animatedFrame) {
                                                        animatedBitmapCacheBytes -= staleBitmap.safeAllocationByteCount()
                                                    } else {
                                                        bitmapCacheBytes -= staleBitmap.safeAllocationByteCount()
                                                    }
                                                    if (staleBitmap !== result && !staleBitmap.isRecycled) {
                                                        staleBitmap.recycle()
                                                    }
                                                }
                                            }
                                            if (animatedFrame) {
                                                putAnimatedBitmapLocked(key, result)
                                            } else {
                                                putBitmapLocked(key, result)
                                            }
                                            bitmapFailures.remove(key)
                                            decoded = null // Ownership transferred to the LRU cache.
                                        } else {
                                            putBoundedLocked(
                                                cache = bitmapFailures,
                                                key = key,
                                                value = BitmapFailure(
                                                    generation = generation,
                                                    failedAtUptimeMs = SystemClock.uptimeMillis(),
                                                ),
                                                maximumEntries = MAX_BITMAP_FAILURE_ENTRIES,
                                            )
                                        }
                                    }
                                }
                                if (currentRequest && !disposed) invalidate()
                            }
                        } finally {
                            // Cancellation can happen after decode but before the main-thread
                            // hand-off. In that case the bitmap never entered the cache.
                            decoded?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                        }
                    }
                    job.invokeOnCompletion { finishAssetLoad(claim) }
                }
            }
            return previousFrame
        }
    }

    private fun requestMovie(resolved: ResolvedAsset, source: PreparedSource): Movie? {
        val key = resolved.cacheRevision
        synchronized(cacheLock) {
            movieCache[key]?.let { return it }
            movieFailures[key]?.let { failure ->
                val inCooldown = failure.generation == bindGeneration &&
                    SystemClock.uptimeMillis() - failure.failedAtUptimeMs < BITMAP_FAILURE_RETRY_MS
                if (inCooldown) return null
                movieFailures.remove(key)
            }
            if (movieLoading[key] == null) {
                val generation = bindGeneration
                val claim = claimAssetLoadLocked(AssetLoadKind.MOVIE, key, generation)
                if (claim != null) {
                    movieLoading[key] = generation
                    val job = assetScope.launch {
                        val loaded = runCatching {
                            Movie.decodeFile(resolved.file.absolutePath)
                        }.getOrNull()
                        withContext(Dispatchers.Main.immediate) {
                            synchronized(cacheLock) {
                                if (movieLoading[key] == generation) movieLoading.remove(key)
                                // 同 requestBitmap：内容寻址缓存不需要 generation 门槛，
                                // 连续 bind 不再把解码好的 Movie 丢掉重来（GIF 闪烁根因）。
                                if (!disposed && loaded != null) {
                                    movieCache[key] = loaded
                                    movieFailures.remove(key)
                                    while (movieCache.size > MAX_MOVIE_CACHE_ENTRIES) {
                                        val iterator = movieCache.entries.iterator()
                                        if (!iterator.hasNext()) break
                                        iterator.next()
                                        iterator.remove()
                                    }
                                } else if (generation == bindGeneration && !disposed) {
                                    putBoundedLocked(
                                        cache = movieFailures,
                                        key = key,
                                        value = BitmapFailure(
                                            generation = generation,
                                            failedAtUptimeMs = SystemClock.uptimeMillis(),
                                        ),
                                        maximumEntries = MAX_MOVIE_FAILURE_ENTRIES,
                                    )
                                }
                            }
                            if (generation == bindGeneration && !disposed) invalidate()
                        }
                    }
                    job.invokeOnCompletion { finishAssetLoad(claim) }
                }
            }
            return null
        }
    }

    private fun resolveTypeface(name: String?, source: PreparedSource): Typeface {
        val normalized = name.orEmpty().removePrefix("bm:").trim()
        if (normalized.isBlank() || normalized.equals("Roboto-Regular", true)) return Typeface.DEFAULT
        val key = normalized.lowercase(Locale.ROOT)
        val file = source.fontIndex.resolve(normalized)
        if (file == null) return Typeface.DEFAULT
        val relative = runCatching {
            file.relativeTo(source.root).invariantSeparatorsPath.lowercase(Locale.ROOT)
        }.getOrDefault(key)
        val fontPrefix = "${source.rootCacheKey}\u0000$relative\u0000"
        val revisionKey = source.revisionByAbsolutePath[file.absolutePath] ?: return Typeface.DEFAULT
        synchronized(cacheLock) {
            typefaceCache[revisionKey]?.let { return it }
            val previousTypeface = typefaceCache.entries
                .lastOrNull { (cachedKey, _) -> cachedKey.startsWith(fontPrefix) }
                ?.value
            if (typefaceLoading[revisionKey] == null) {
                val generation = bindGeneration
                val claim = claimAssetLoadLocked(AssetLoadKind.TYPEFACE, revisionKey, generation)
                if (claim != null) {
                    typefaceLoading[revisionKey] = generation
                    val job = assetScope.launch {
                        val loaded = runCatching { Typeface.createFromFile(file) }.getOrNull()
                        withContext(Dispatchers.Main.immediate) {
                            var currentRequest = false
                            synchronized(cacheLock) {
                                currentRequest = typefaceLoading[revisionKey] == generation
                                if (currentRequest) typefaceLoading.remove(revisionKey)
                                if (currentRequest && generation == bindGeneration && !disposed) {
                                    typefaceCache.keys.removeAll { cachedKey ->
                                        cachedKey.startsWith(fontPrefix) && cachedKey != revisionKey
                                    }
                                    putBoundedLocked(
                                        cache = typefaceCache,
                                        key = revisionKey,
                                        value = loaded ?: Typeface.DEFAULT,
                                        maximumEntries = MAX_TYPEFACE_CACHE_ENTRIES,
                                    )
                                }
                            }
                            if (currentRequest && generation == bindGeneration && !disposed) invalidate()
                        }
                    }
                    job.invokeOnCompletion { finishAssetLoad(claim) }
                }
            }
            return previousTypeface ?: Typeface.DEFAULT
        }
    }

    private fun decodeBitmapSafely(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (
                bounds.outWidth / sample > MAX_DECODE_DIMENSION_PX ||
                bounds.outHeight / sample > MAX_DECODE_DIMENSION_PX
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    /** Caller must hold [cacheLock]. */
    private fun putBitmapLocked(key: String, bitmap: Bitmap) {
        bitmapCache.put(key, bitmap)?.let { previous ->
            bitmapCacheBytes -= previous.safeAllocationByteCount()
            if (previous !== bitmap && !previous.isRecycled) previous.recycle()
        }
        bitmapCacheBytes += bitmap.safeAllocationByteCount()
        while (
            bitmapCache.size > MAX_BITMAP_CACHE_ENTRIES ||
            (bitmapCache.size > 1 && bitmapCacheBytes > MAX_BITMAP_CACHE_BYTES)
        ) {
            val iterator = bitmapCache.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next().value
            iterator.remove()
            bitmapCacheBytes -= eldest.safeAllocationByteCount()
            if (!eldest.isRecycled) eldest.recycle()
        }
    }

    /** Caller must hold [cacheLock]. */
    private fun putAnimatedBitmapLocked(key: String, bitmap: Bitmap) {
        animatedBitmapCache.put(key, bitmap)?.let { previous ->
            animatedBitmapCacheBytes -= previous.safeAllocationByteCount()
            if (previous !== bitmap && !previous.isRecycled) previous.recycle()
        }
        animatedBitmapCacheBytes += bitmap.safeAllocationByteCount()
        while (
            animatedBitmapCache.size > MAX_ANIMATED_BITMAP_CACHE_ENTRIES ||
            (animatedBitmapCache.size > 1 && animatedBitmapCacheBytes > MAX_ANIMATED_BITMAP_CACHE_BYTES)
        ) {
            val iterator = animatedBitmapCache.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next().value
            iterator.remove()
            animatedBitmapCacheBytes -= eldest.safeAllocationByteCount()
            if (!eldest.isRecycled) eldest.recycle()
        }
    }

    /** Caller must hold [cacheLock]. */
    private fun claimAssetLoadLocked(
        kind: AssetLoadKind,
        key: String,
        generation: Long,
    ): AssetLoadClaim? {
        if (activeAssetLoads.size >= MAX_CONCURRENT_ASSET_LOADS) return null
        val claim = AssetLoadClaim(kind = kind, key = key, generation = generation)
        return claim.takeIf(activeAssetLoads::add)
    }

    private fun finishAssetLoad(claim: AssetLoadClaim) {
        synchronized(cacheLock) {
            when (claim.kind) {
                AssetLoadKind.BITMAP -> {
                    if (bitmapLoading[claim.key] == claim.generation) bitmapLoading.remove(claim.key)
                }
                AssetLoadKind.MOVIE -> {
                    if (movieLoading[claim.key] == claim.generation) movieLoading.remove(claim.key)
                }
                AssetLoadKind.TYPEFACE -> {
                    if (typefaceLoading[claim.key] == claim.generation) typefaceLoading.remove(claim.key)
                }
            }
            activeAssetLoads.remove(claim)
        }
        if (!disposed && claim.generation == sourceTransitionGeneration) {
            postInvalidateOnAnimation()
        }
    }

    /** Caller must hold [cacheLock]. */
    private fun <V> putBoundedLocked(
        cache: LinkedHashMap<String, V>,
        key: String,
        value: V,
        maximumEntries: Int,
    ) {
        cache[key] = value
        while (cache.size > maximumEntries) {
            val iterator = cache.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun Bitmap.safeAllocationByteCount(): Long =
        runCatching { allocationByteCount.toLong() }
            .getOrElse { runCatching { byteCount.toLong() }.getOrDefault(0L) }

    private fun clearAssetCaches() {
        // 兜底图持有的就是 bitmapCache 里那批实例，下面要把它们 recycle 掉：
        // 先松手，否则 map 里留着一堆已回收引用（画的时候有 isRecycled 守卫，
        // 但没必要留悬垂引用占着）。
        lastDrawnFrameByLayer.clear()
        lastDrawnMovieByLayer.clear()
        synchronized(cacheLock) {
            bitmapCache.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            bitmapCache.clear()
            bitmapCacheBytes = 0L
            animatedBitmapCache.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            animatedBitmapCache.clear()
            animatedBitmapCacheBytes = 0L
            movieCache.clear()
            bitmapLoading.clear()
            movieLoading.clear()
            bitmapFailures.clear()
            movieFailures.clear()
            typefaceLoading.clear()
            typefaceCache.clear()
        }
    }

    private fun scheduleTicks() {
        if (!faceVisible || !isAttachedToWindow || tickPosted) return
        tickPosted = true
        val needsFastTicks = if (editorAnimationPlaybackLimited) {
            editorAnimatedImageLayerIndex != null
        } else {
            source?.needsFastTicks == true
        }
        val delay = if (needsFastTicks) 33L else 1_000L
        postDelayed(tickRunnable, delay)
    }

    private val tickRunnable = Runnable {
        tickPosted = false
        if (faceVisible) {
            invalidate()
            scheduleTicks()
        }
    }

    private fun cancelTicks() {
        removeCallbacks(tickRunnable)
        tickPosted = false
    }

    private fun evaluateText(raw: String, source: PreparedSource): String {
        val value = raw.replace("&quot;", "\"").replace("&apos;", "'")
        return WatchmakerExpressionEvaluator.evaluateText(value, source.expressionScope) { tag ->
            resolveTagResolution(tag, calendar, source)
        }
    }

    private fun resolveTagResolution(
        tag: String,
        now: Calendar,
        source: PreparedSource,
    ): WatchTagResolution {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        resolveWatchCounterTag(
            rawTag = normalized,
            elapsedMs = SystemClock.uptimeMillis() - source.animationStartedAtUptimeMs,
        )?.let { value -> return WatchTagResolution.Resolved(value) }
        return WatchmakerTagResolver.resolve(
            tag = normalized,
            now = now,
            snapshot = frameDataSnapshot,
            userColor = source.definition.rootAttributes["ucolor_default"],
        )
    }

    private fun resolveColor(raw: String?, fallback: Int, source: PreparedSource): Int {
        val value = evaluateText(raw.orEmpty(), source)
            .replace("#", "")
            .trim()
        if (value.isBlank() || value == "--") return fallback
        return runCatching {
            Color.parseColor(if (value.length == 6 || value.length == 8) "#$value" else value)
        }.getOrDefault(fallback)
    }

    private fun isDisplayed(display: String?, source: PreparedSource): Boolean {
        val value = display?.trim().orEmpty()
        return when {
            value.isBlank() -> true
            value.equals("y", true) || value.equals("yes", true) || value.equals("true", true) -> true
            value.equals("n", true) || value.equals("no", true) || value.equals("false", true) -> false
            value.equals("b", true) || value.equals("bd", true) -> true
            value.equals("a", true) || value.equals("ad", true) || value.equals("d", true) -> false
            else -> WatchmakerExpressionEvaluator.evaluateBoolean(
                value,
                false,
                source.expressionScope,
            ) { tag ->
                resolveTagResolution(tag, calendar, source)
            }
        }
    }

    private fun dynamicNumber(value: String?, fallback: Float, source: PreparedSource): Float =
        WatchmakerExpressionEvaluator.evaluateNumber(
            value,
            fallback,
            source.expressionScope,
        ) { tag ->
            resolveTagResolution(tag, calendar, source)
        }

    /**
     * Resolve an asset reference without touching the filesystem.  Watchmaker
     * exports commonly put watch.xml in a subdirectory while keeping images in
     * a sibling directory, so references are tried relative to watch.xml first
     * and then relative to the installed archive root.  Every candidate is
     * collapsed lexically and is rejected if it escapes the install root.
     */
    private fun resolveAsset(
        rawPath: String,
        source: PreparedSource,
        conventionalDirectory: String,
    ): ResolvedAsset? {
        val cacheKey = buildString {
            append(conventionalDirectory.lowercase(Locale.ROOT))
            append('\u0000')
            append(rawPath)
        }
        return source.assetResolutionCache.getOrPut(cacheKey) {
            val reference = normalizeAssetReference(rawPath) ?: return@getOrPut null
            val isRootRelative = rawPath.trim().replace('\\', '/').startsWith('/')
            val candidates = LinkedHashSet<String>()
            fun addCandidate(base: String, value: String) {
                normalizeRelativeAsset(base, value)?.let(candidates::add)
            }
            val base = if (isRootRelative) "" else source.watchDirectoryKey
            addCandidate(base, reference)
            if (!reference.startsWith("$conventionalDirectory/", ignoreCase = true)) {
                addCandidate(base, "$conventionalDirectory/$reference")
            }
            // A few 1.0 exporters deliberately use root-relative paths even
            // when watch.xml is nested. Keep this after the local interpretation.
            addCandidate("", reference)
            if (!reference.startsWith("$conventionalDirectory/", ignoreCase = true)) {
                addCandidate("", "$conventionalDirectory/$reference")
            }

            candidates.firstNotNullOfOrNull { key ->
                source.exactFiles[key]
            } ?: reference.substringAfterLast('/').let { basename ->
                source.uniqueBasenames[basename]
            }
        }
    }

    private fun recordTapTarget(canvas: Canvas, bounds: RectF, attrs: Map<String, String>) {
        val action = WatchTapActionParser.parse(attrs["tap_action"] ?: attrs["action"])
        val mapped = RectF(bounds)
        canvas.matrix.mapRect(mapped)
        if (!mapped.isEmpty) tapTargets += TapTarget(mapped, action)
    }

    private fun dispatchTap(x: Float, y: Float) {
        val target = tapTargets.asReversed().firstOrNull { it.bounds.contains(x, y) } ?: return
        val handler = tapActionHandler
        if (handler != null) {
            handler(target.action)
        } else {
            routeTapAction(target.action)
        }
    }

    private fun routeTapAction(action: WatchTapAction) {
        if (action === WatchTapAction.HeartRate) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            runCatching { dataProvider.requestRefresh() }
            invalidate()
            return
        }
        val intent = when (action) {
            WatchTapAction.Calendar -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            WatchTapAction.Weather -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_WEATHER)
            WatchTapAction.HeartRate -> null
            is WatchTapAction.AppLaunch -> {
                val act = action.activityClass
                if (act != null) {
                    Intent(Intent.ACTION_MAIN).apply {
                        component = android.content.ComponentName(action.packageName, act)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    context.packageManager.getLaunchIntentForPackage(action.packageName)
                }
            }
            is WatchTapAction.Unsupported -> null
        }
        if (intent == null) {
            val reason = (action as? WatchTapAction.Unsupported)?.reason
                ?: "找不到应用 ${(action as? WatchTapAction.AppLaunch)?.packageName}"
            Toast.makeText(context, "表盘点击动作不可用: $reason", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, "无法打开目标应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalizeAssetReference(raw: String): String? {
        if (raw.length > MAX_ASSET_REFERENCE_CHARS) return null
        val value = raw
            .substringBefore('`')
            .replace('\\', '/')
            .trim()
        if (value.isBlank() || value.indexOf('\u0000') >= 0 || WINDOWS_ABSOLUTE_PATH.matches(value)) {
            return null
        }
        return value.trimStart('/').split('/').filter { it.isNotEmpty() }.joinToString("/")
            .lowercase(Locale.ROOT)
    }

    /** Returns a root-relative key, or null when [value] escapes the root. */
    private fun normalizeRelativeAsset(base: String, value: String): String? {
        val segments = ArrayDeque<String>()
        if (value.trim().replace('\\', '/').startsWith('/')) {
            // An explicit leading slash means archive-root-relative.
        } else {
            base.split('/').filter { it.isNotBlank() }.forEach(segments::addLast)
        }
        value.replace('\\', '/').split('/').forEach { segment ->
            when {
                segment.isBlank() || segment == "." -> Unit
                segment == ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                else -> segments.addLast(segment.lowercase(Locale.ROOT))
            }
        }
        return segments.joinToString("/").takeIf(String::isNotBlank)
    }

    private fun paintAlpha(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()

    private fun tintFilter(color: Int): android.graphics.ColorFilter? =
        if (color == Color.WHITE) null else android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)

    private data class PreparedSource(
        val sourceGeneration: Long,
        val animationStartedAtUptimeMs: Long,
        val root: File,
        val rootCacheKey: String,
        val definitionRevision: String,
        val watchDirectoryKey: String,
        val definition: WatchmakerDefinition,
        val exactFiles: Map<String, ResolvedAsset>,
        val uniqueBasenames: Map<String, ResolvedAsset?>,
        val galleryAssetPaths: List<String>,
        val revisionByAbsolutePath: Map<String, String>,
        val fontIndex: WatchFontIndex,
        val bitmapFonts: Map<String, PreparedBitmapFont?>,
        val expressionScope: WatchExpressionScope,
        val renderPlan: List<WatchLayerRenderItem>,
        val renderPlanDiagnostics: WatchLayerGraphDiagnostics,
        val svgDocuments: Map<String, PreparedWatchSvgDocument<Path>>,
        val needsFastTicks: Boolean,
    ) {
        /**
         * 声明画布换算出的设计空间尺寸，构造时算一次。
         *
         * 根属性解析涉及字符串处理，放到 onDraw 里就是每帧一次；而安装目录在
         * 一个 PreparedSource 的生命周期内是不变的，缓存下来即可。
         * 方形声明（512²/466²/360²，以及不声明 width/height 的老表盘）算出来
         * 仍是 512×512，渲染路径与改动前逐像素一致。
         */
        val canvasSize = WatchCanvasSize.fromRootAttributes(definition.rootAttributes)
        val designExtentWidth: Float = canvasSize.designExtentWidth
        val designExtentHeight: Float = canvasSize.designExtentHeight

        /** Main-thread-only bounded memoization; the installed tree is immutable. */
        val assetResolutionCache = WatchStringLruCache<ResolvedAsset>(
            maximumEntries = MAX_ASSET_RESOLUTION_CACHE_ENTRIES,
            maximumKeyLength = MAX_ASSET_CACHE_KEY_CHARS,
        )
        val gifPathCache = WatchStringLruCache<List<String>>(
            maximumEntries = MAX_GIF_PATH_CACHE_ENTRIES,
            maximumKeyLength = MAX_DYNAMIC_CACHE_KEY_CHARS,
        )
        val chartValuesCache = WatchStringLruCache<List<Float>>(
            maximumEntries = MAX_CHART_VALUE_CACHE_ENTRIES,
            maximumKeyLength = MAX_DYNAMIC_CACHE_KEY_CHARS,
        )
        val seriesCache: MutableMap<String, List<WatchSeriesItem>> = HashMap()
        val calendarCellsCache: MutableMap<String, List<WatchCalendarCell>> = HashMap()
        val calendarLabelsCache: MutableMap<String, List<String>> = HashMap()

        fun clearRuntimeCaches() {
            assetResolutionCache.clear()
            gifPathCache.clear()
            chartValuesCache.clear()
            seriesCache.clear()
            calendarCellsCache.clear()
            calendarLabelsCache.clear()
        }
    }

    private data class ResolvedAsset(
        val key: String,
        val file: File,
        val cacheRevision: String,
    )

    private data class PreparedBitmapFont(
        val file: File,
        val relativeDirectory: String,
        val definition: WatchBitmapFont,
        val pageAssetPaths: List<String>,
        val pageBitmaps: Array<Bitmap?> = arrayOfNulls(pageAssetPaths.size),
    )

    private data class TapTarget(val bounds: RectF, val action: WatchTapAction)

    /** 已求值的描边参数（mode 一定不是 NONE，size/opacity 一定大于 0）。 */
    private data class ResolvedOutline(
        val mode: WatchOutlineMode,
        val size: Float,
        val color: Int,
        val opacity: Float,
    )

    private data class BitmapFailure(val generation: Long, val failedAtUptimeMs: Long)

    private enum class AssetLoadKind {
        BITMAP,
        MOVIE,
        TYPEFACE,
    }

    private data class AssetLoadClaim(
        val kind: AssetLoadKind,
        val key: String,
        val generation: Long,
    )

    companion object {
        private const val DESIGN_SIZE = WatchmakerGeometry.DESIGN_SIZE
        private const val MAX_CONCURRENT_ASSET_LOADS = 4
        private const val MAX_BITMAP_CACHE_ENTRIES = 24
        private const val MAX_BITMAP_CACHE_BYTES = 24L * 1024L * 1024L
        private const val MAX_ANIMATED_BITMAP_CACHE_ENTRIES = 3
        private const val MAX_ANIMATED_BITMAP_CACHE_BYTES = 8L * 1024L * 1024L
        private const val MAX_MOVIE_CACHE_ENTRIES = 4
        /** 帧序列兜底图上限：一块表盘同时有几个 GIF 图层已属极端情况。 */
        private const val MAX_LAST_FRAME_ENTRIES = 8
        private const val MAX_LAST_MOVIE_ENTRIES = 4
        private const val MAX_TYPEFACE_CACHE_ENTRIES = 16
        private const val MAX_BITMAP_FAILURE_ENTRIES = 64
        private const val MAX_MOVIE_FAILURE_ENTRIES = 16
        private const val MAX_ASSET_RESOLUTION_CACHE_ENTRIES = 128
        private const val MAX_GIF_PATH_CACHE_ENTRIES = 32
        private const val MAX_CHART_VALUE_CACHE_ENTRIES = 16
        private const val MAX_DYNAMIC_CACHE_KEY_CHARS = 4 * 1024
        private const val MAX_ASSET_CACHE_KEY_CHARS = 1_024
        private const val MAX_ASSET_REFERENCE_CHARS = 1_000
        private const val MAX_CHART_REFERENCE_LINES = 16
        private const val MAX_SERIES_CACHE_ENTRIES = 32
        private const val MAX_CALENDAR_CACHE_ENTRIES = 8
        private const val MAX_DECODE_DIMENSION_PX = 1_024
        private const val MAX_SVG_BYTES = MAX_WATCH_SVG_BYTES
        private const val MAX_SVG_ASSET_COUNT = 64
        private const val MAX_SVG_TOTAL_BYTES = 8L * 1024L * 1024L
        private const val MAX_INITIAL_SCRIPT_BYTES = 256L * 1024L
        private const val MAX_BMFONT_DEFINITION_BYTES = 1L * 1024L * 1024L
        private const val BITMAP_FAILURE_RETRY_MS = 30_000L
        private const val DEFAULT_MOVIE_DURATION_MS = 1_000
        private const val SOURCE_TRANSITION_MAX_HOLD_MS = 8_000L
        private val WATCH_GALLERY_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[a-zA-Z]:[/\\\\].*")
        private val TEXT_RING_LABELS = (1..12).map(Int::toString)

        /**
         * 位图字体描边的 8 个方向单位向量（dx, dy 成对）。对角线按 1/√2
         * 归一化，保证 8 个副本落在同一半径的圆上。
         */
        private const val OUTLINE_DIAGONAL = 0.70710678f
        private val BITMAP_OUTLINE_DIRECTIONS = floatArrayOf(
            -1f, 0f,
            1f, 0f,
            0f, -1f,
            0f, 1f,
            -OUTLINE_DIAGONAL, -OUTLINE_DIAGONAL,
            OUTLINE_DIAGONAL, -OUTLINE_DIAGONAL,
            -OUTLINE_DIAGONAL, OUTLINE_DIAGONAL,
            OUTLINE_DIAGONAL, OUTLINE_DIAGONAL,
        )
        // ICU (Android 14+ 的 java.util.regex 后端) 要求 '}' 必须转义，且字符组内的
        // '{' 会被当成 UnicodeSet 的多字符串起始符。裸写 "\\{[^{}]+}" 在 SDK 34+ 上
        // 抛 PatternSyntaxException，并因为它是 companion object 的字段而升级成
        // ExceptionInInitializerError —— 整个表盘视图类无法初始化。
        private val DYNAMIC_TAG_PATTERN = Regex("\\{[^\\{\\}]+\\}")
    }
}

@Composable
fun WatchmakerWatchFaceHost(
    descriptor: WatchFaceDescriptor,
    isFaceVisible: Boolean,
    refreshToken: Int,
    onLongPress: (() -> Unit)?,
    onDoubleTap: (() -> Unit)?,
    onLoadFailure: (WatchFaceDescriptor, Throwable) -> Unit,
    dataProvider: WatchDataProvider? = null,
    /** Null selects [WatchWeatherSources.default]; callers may inject a deterministic source. */
    weatherSource: WatchWeatherSource? = null,
    onTapAction: ((WatchTapAction) -> Unit)? = null,
    forceFill: Boolean = false,
    textEntryAnimationEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ownedDataProvider = rememberWatchmakerWatchDataProvider(
        active = dataProvider == null && isFaceVisible,
        weatherSource = weatherSource,
    )
    val resolvedDataProvider = dataProvider ?: ownedDataProvider
    AndroidView(
        factory = { context -> WatchmakerWatchFaceView(context) },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            view.setWatchDataProvider(resolvedDataProvider)
            view.setForceFill(forceFill)
            view.bind(
                descriptor = descriptor,
                refreshToken = refreshToken,
                isFaceVisible = isFaceVisible,
                onLongPress = onLongPress,
                onDoubleTap = onDoubleTap,
                onLoadFailure = onLoadFailure,
                onTapAction = onTapAction,
                textEntryAnimationEnabled = textEntryAnimationEnabled,
            )
        },
        onRelease = WatchmakerWatchFaceView::dispose,
    )
}
