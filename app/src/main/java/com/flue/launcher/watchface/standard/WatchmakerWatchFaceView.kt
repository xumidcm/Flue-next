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
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A deliberately self-contained native renderer for the open Watchmaker
 * `.watch` format.  The view owns the frame clock and asset loading; Compose
 * only hosts this one coarse-grained view.  Unsupported layer types remain
 * invisible instead of being routed through the legacy Lunch renderer.
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
    private var faceVisible = false
    private var refreshToken = 0
    private var longPressAction: (() -> Unit)? = null
    private var doubleTapAction: (() -> Unit)? = null
    private var tapActionHandler: ((WatchTapAction) -> Unit)? = null
    private var tickPosted = false
    private var dataProvider: WatchDataProvider = EmptyWatchDataProvider
    private var frameDataSnapshot = WatchDataSnapshot()
    private var forceFill = false

    private val bitmapCache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private val movieCache = LinkedHashMap<String, Movie>(4, 0.75f, true)
    private var bitmapCacheBytes = 0L
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
    // WM_EFFECTS.md §2 的 outline/glow 描边通道。与 textPaint 分开保存，避免
    // 描边时改动的 style/strokeWidth 泄漏到后面的填充绘制。
    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var liveTransformOverride: WatchLiveLayerTransform? = null
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
        descriptor: LunchWatchFaceDescriptor,
        refreshToken: Int,
        isFaceVisible: Boolean,
        onLongPress: (() -> Unit)?,
        onDoubleTap: (() -> Unit)?,
        onLoadFailure: (LunchWatchFaceDescriptor, Throwable) -> Unit,
        onTapAction: ((WatchTapAction) -> Unit)? = null,
    ) {
        if (disposed) return
        require(descriptor.isWatchmaker) { "Watchmaker view requires a standard .watch descriptor" }
        this.faceVisible = isFaceVisible
        this.longPressAction = onLongPress
        this.doubleTapAction = onDoubleTap
        this.tapActionHandler = onTapAction
        if (descriptorKey == descriptor.stableKey && this.refreshToken == refreshToken) {
            if (faceVisible) scheduleTicks() else cancelTicks()
            invalidate()
            return
        }
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
                if (replacement !== current) current?.clearRuntimeCaches()
                source = replacement
                if (replacement !== current) {
                    frameStartedAtUptimeMs = SystemClock.uptimeMillis()
                }
                scheduleTicks()
                invalidate()
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
        if (visible) scheduleTicks() else cancelTicks()
        invalidate()
    }

    fun setWatchDataProvider(provider: WatchDataProvider?) {
        val resolved = provider ?: EmptyWatchDataProvider
        if (dataProvider === resolved) return
        dataProvider = resolved
        if (!disposed) invalidate()
    }

    /**
     * 编辑器画布手势期间的实时变换覆盖。手势中 watch.xml 只在内存 DOM 里被
     * 改写，磁盘上的草稿不会重写、[bind] 也不会重新解析，所以渲染出来的元素
     * 会停在原位，只有 Compose 侧的选择框跟着手指走。把手势中的变换直接喂给
     * 渲染器，元素和选择框才会一起动。传 null 结束覆盖。
     */
    fun setLiveLayerTransform(override: WatchLiveLayerTransform?) {
        if (disposed) return
        if (liveTransformOverride == override) return
        liveTransformOverride = override
        invalidate()
    }

    fun setForceFill(enabled: Boolean) {
        if (forceFill == enabled) return
        forceFill = enabled
        if (!disposed) invalidate()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        bindGeneration++
        descriptorKey = null
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
        source?.clearRuntimeCaches()
        source = null
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
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val prepared = source
        if (prepared == null || width <= 0 || height <= 0) {
            canvas.drawColor(Color.BLACK)
            return
        }
        val transform = watchCanvasTransform(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            forceFill = forceFill,
        )
        canvas.save()
        canvas.translate(transform.left, transform.top)
        canvas.scale(transform.scale, transform.scale)
        // Watchmaker coordinates are centered on (0, 0), unlike Android's
        // top-left canvas coordinates. Keeping this translation at the single
        // renderer boundary makes every primitive (including groups and
        // rings) use the same coordinate contract.
        canvas.translate(DESIGN_SIZE / 2f, DESIGN_SIZE / 2f)
        canvas.clipRect(-DESIGN_SIZE / 2f, -DESIGN_SIZE / 2f, DESIGN_SIZE / 2f, DESIGN_SIZE / 2f)
        if (prepared.definition.shape == WatchFaceShape.ROUND) {
            pathScratch.reset()
            pathScratch.addCircle(0f, 0f, DESIGN_SIZE / 2f, Path.Direction.CW)
            canvas.clipPath(pathScratch)
        }
        calendar.timeInMillis = System.currentTimeMillis()
        frameDataSnapshot = runCatching { dataProvider.snapshot(calendar.timeInMillis) }
            .getOrDefault(WatchDataSnapshot())
        tapTargets.clear()
        backgroundPaint.color = resolveColor(prepared.definition.rootAttributes["bg_color"], Color.BLACK, prepared)
        canvas.drawRect(-DESIGN_SIZE / 2f, -DESIGN_SIZE / 2f, DESIGN_SIZE / 2f, DESIGN_SIZE / 2f, backgroundPaint)
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
                // 拖动一个组时，它的子图层也必须跟着走，所以祖先链同样查覆盖。
                applyTransform(canvas, attributes, source, liveTransformFor(ancestorIndex, source))
            }
            val layer = source.definition.layers[item.layerIndex]
            val attributes = layer.attributes
            alpha *= dynamicNumber(attributes["opacity"], 100f, source)
                .coerceIn(0f, 100f) / 100f
            if (alpha <= 0f || !isDisplayed(attributes["display"], source)) return
            applyTransform(canvas, attributes, source, liveTransformFor(item.layerIndex, source))
            drawPrimitive(canvas, layer, alpha, source)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    /**
     * 返回该图层当前生效的实时变换覆盖，没有则 null。
     *
     * 索引连接本身是脆弱的：编辑器的 `topLevelLayerElements()` 在遇到 `<Layer>`
     * 之后就不再下钻，而 [WatchmakerDefinitionParser] 会把嵌套 `<Layer>` 也收进
     * 同一个扁平列表，两边对 DOM 嵌套的表盘会错位。因此额外用 id + type 校验，
     * 一旦对不上就静默退回属性里的静态变换（最坏情况只是拖动时不跟手，
     * 松手提交后仍然正确）。
     */
    private fun liveTransformFor(layerIndex: Int, source: PreparedSource): WatchLiveLayerTransform? {
        val override = liveTransformOverride ?: return null
        if (override.layerIndex != layerIndex) return null
        val layer = source.definition.layers.getOrNull(layerIndex) ?: return null
        if (layer.id != override.layerId) return null
        if (!layer.type.equals(override.layerType, ignoreCase = true)) return null
        return override
    }

    private fun applyTransform(
        canvas: Canvas,
        attrs: Map<String, String>,
        source: PreparedSource,
        override: WatchLiveLayerTransform? = null,
    ) {
        if (override != null) {
            canvas.translate(override.x, override.y)
            if (override.rotation != 0f) canvas.rotate(override.rotation)
            if (override.scaleX != 1f || override.scaleY != 1f) {
                canvas.scale(override.scaleX, override.scaleY)
            }
            applySkew(canvas, attrs, source)
            return
        }
        canvas.translate(
            dynamicNumber(attrs["x"], 0f, source),
            dynamicNumber(attrs["y"], 0f, source),
        )
        val rotation = dynamicNumber(attrs["rotation"], 0f, source)
        if (rotation != 0f) canvas.rotate(rotation)
        val sharedScale = attrs["anim_scale"] ?: attrs["scale"]
        val sx = dynamicNumber(
            attrs["anim_scale_x"] ?: attrs["scale_x"] ?: sharedScale,
            100f,
            source,
        ).coerceIn(-500f, 500f) / 100f
        val sy = dynamicNumber(
            attrs["anim_scale_y"] ?: attrs["scale_y"] ?: sharedScale,
            100f,
            source,
        ).coerceIn(-500f, 500f) / 100f
        if (sx != 1f || sy != 1f) canvas.scale(sx, sy)
        applySkew(canvas, attrs, source)
    }

    private fun applySkew(canvas: Canvas, attrs: Map<String, String>, source: PreparedSource) {
        val skewX = dynamicNumber(attrs["skew_x"], 0f, source)
        val skewY = dynamicNumber(attrs["skew_y"], 0f, source)
        if (skewX != 0f || skewY != 0f) {
            canvas.skew(kotlin.math.tan(Math.toRadians(skewX.toDouble())).toFloat(), kotlin.math.tan(Math.toRadians(skewY.toDouble())).toFloat())
        }
    }

    private fun drawPrimitive(canvas: Canvas, layer: WatchmakerLayer, alpha: Float, source: PreparedSource) {
        val attrs = layer.attributes
        when (layer.type.lowercase(Locale.ROOT)) {
            "image", "gif", "image_gif" -> drawImage(canvas, attrs, alpha, source)
            "image_tap" -> drawImage(canvas, attrs, alpha, source, tapAction = true)
            "image_cond" -> drawImage(canvas, attrs, alpha, source, sprite = true)
            "text" -> drawText(canvas, attrs, alpha, source)
            "series" -> drawSeries(canvas, attrs, alpha, source)
            "calendar" -> drawCalendar(canvas, attrs, alpha, source)
            "ring" -> drawRing(canvas, attrs, alpha, source)
            "progress" -> drawProgress(canvas, attrs, alpha, source)
            "shape" -> drawShape(canvas, attrs, alpha, source)
            "rounded" -> drawRounded(canvas, attrs, alpha, source)
            "markers" -> drawMarkers(canvas, attrs, alpha, source)
            "markers_hm" -> drawHourMinuteMarkers(canvas, attrs, alpha, source)
            "text_curved" -> drawCurvedText(canvas, attrs, alpha, source)
            "text_ring" -> drawTextRing(canvas, attrs, alpha, source)
            "chart" -> drawChart(canvas, attrs, alpha, source)
            "svg" -> drawSvg(canvas, attrs, alpha, source)
            // These families need a MediaPlayer/live map bridge.  If an
            // exporter points them at a static image, drawing that image is a
            // useful bounded fallback; otherwise leave the region transparent
            // and keep the capability in compatibilityReport.
            "video", "map" -> drawStaticMediaFallback(canvas, attrs, alpha, source)
        }
    }

    private fun drawImage(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        sprite: Boolean = false,
        tapAction: Boolean = false,
    ) {
        val pathExpression = evaluateText(attrs["path"].orEmpty(), source)
        val framePaths = source.gifPathCache.getOrPut(pathExpression) {
            parseWatchGifPaths(pathExpression)
        }.orEmpty()
        val frameIndex = watchGifFrameIndex(
            framePaths.size,
            SystemClock.uptimeMillis() - frameStartedAtUptimeMs,
            dynamicNumber(attrs["gif_delay"], 100f, source).roundToInt().toLong(),
        )
        val path = framePaths.getOrNull(frameIndex) ?: pathExpression.substringBefore('`').trim()
        val resolved = resolveAsset(path, source, conventionalDirectory = "images") ?: return
        val sharedSize = attrs["size"]
        val movie = if (resolved.file.extension.equals("gif", true)) requestMovie(resolved, source) else null
        val bitmap = if (movie == null) requestBitmap(path, source) else null
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
            watchSpriteIndex(
                dynamicNumber(
                    attrs["cond_value"] ?: attrs["condition"] ?: attrs["value"],
                    1f,
                    source,
                ),
                grid.first * grid.second,
            )
        } else {
            0
        }
        val dst = anchoredRect(width, height, attrs["alignment"])
        imagePaint.alpha = paintAlpha(alpha)
        imagePaint.colorFilter = tintFilter(resolveColor(attrs["color"], Color.WHITE, source))
        if (movie != null) {
            val elapsed = (SystemClock.uptimeMillis() - frameStartedAtUptimeMs).coerceAtLeast(0L)
            movie.setTime((elapsed % movie.duration().coerceAtLeast(1)).toInt())
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
        // WM_EFFECTS.md §2：描边先画、填充后画（clox 把 outline 副本挂在
        // holder 的 z=-2，正文在 z=0）。先 STROKE 一遍再 FILL 一遍。
        resolveOutlineSpec(attrs, source)?.let { outline ->
            drawTextOutline(canvas, text, x, y, outline, alpha)
        }
        canvas.drawText(text, x, y, textPaint)
    }

    /**
     * WM_EFFECTS.md §2 «Outline & Glow»：
     * `outline` = `"Outline"` | `"Glow"`，`o_color` 默认 `ffffff`，
     * `o_size` 默认 4（Outline）/ 10（Glow），`o_opacity` 0–100 默认 100。
     * 缺失 `outline` 属性即表示没有效果。
     */
    private fun resolveOutlineSpec(
        attrs: Map<String, String>,
        source: PreparedSource,
    ): WatchOutlineSpec? {
        val raw = attrs["outline"] ?: return null
        val mode = evaluateText(raw, source).trim()
        if (mode.isBlank()) return null
        val glow = mode.equals("Glow", ignoreCase = true)
        if (!glow && !mode.equals("Outline", ignoreCase = true)) return null
        val size = dynamicNumber(attrs["o_size"], if (glow) 10f else 4f, source)
        if (!size.isFinite() || size <= 0f) return null
        val opacity = dynamicNumber(attrs["o_opacity"], 100f, source).coerceIn(0f, 100f)
        if (opacity <= 0f) return null
        return WatchOutlineSpec(
            glow = glow,
            color = resolveColor(attrs["o_color"], Color.WHITE, source),
            size = size.coerceAtMost(MAX_OUTLINE_DESIGN_SIZE),
            opacity = opacity / 100f,
        )
    }

    private fun drawTextOutline(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        outline: WatchOutlineSpec,
        alpha: Float,
    ) {
        textOutlinePaint.set(textPaint)
        textOutlinePaint.style = Paint.Style.STROKE
        textOutlinePaint.strokeJoin = Paint.Join.ROUND
        textOutlinePaint.strokeCap = Paint.Cap.ROUND
        textOutlinePaint.color = outline.color
        if (!outline.glow) {
            // Android 的描边以字形轮廓为中心线，内侧那一半会被随后的填充盖住，
            // 所以宽度取 2 * o_size 才能在外侧留下规范要求的 o_size 像素。
            textOutlinePaint.strokeWidth = outline.size * 2f
            textOutlinePaint.alpha = paintAlpha(alpha * outline.opacity)
            canvas.drawText(text, x, y, textOutlinePaint)
            return
        }
        // Glow：WM_EFFECTS.md 规定 rings = ceil(o_size / 2)，每环透明度
        // (o_opacity / rings) * (1 - (ring - 1) / rings)。clox 同样把环数封顶在
        // 8（wf_render.lua:794-795）。这里用逐环加宽的描边近似同心副本，
        // 避免 BlurMaskFilter 在硬件加速画布上的兼容性问题。
        val rings = ceil(outline.size / 2f).toInt().coerceIn(1, MAX_GLOW_RINGS)
        for (ring in rings downTo 1) {
            val ringAlpha = alpha * (outline.opacity / rings) * (1f - (ring - 1f) / rings)
            if (ringAlpha <= 0f) continue
            textOutlinePaint.color = outline.color
            textOutlinePaint.strokeWidth = outline.size * 2f * ring / rings
            textOutlinePaint.alpha = paintAlpha(ringAlpha)
            canvas.drawText(text, x, y, textOutlinePaint)
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
        val cursorX = when {
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
            rectScratch.set(cursorX, lineTop, cursorX + width, lineTop + lineHeight)
            recordTapTarget(canvas, rectScratch, attrs)
        }
        // 一次「排版 + 绘制」遍历，可整体平移。描边就是把同一遍以不同偏移和
        // 颜色重画若干次（clox wf_render.lua:822-838 的 8 副本硬描边）。
        val originX = cursorX
        fun renderGlyphs(offsetX: Float, offsetY: Float) {
            var penX = originX + offsetX
            val penTop = lineTop + offsetY
            var previous: Int? = null
            var index = 0
            while (index < renderedText.length) {
                val codePoint = Character.codePointAt(renderedText, index)
                val glyph = font.glyphs[codePoint]
                if (glyph != null) {
                    penX += (previous?.let { font.kerning(it, codePoint) } ?: 0) * scale
                    val bitmap = preparedFont.pageBitmaps.getOrNull(glyph.page)
                        ?.takeUnless(Bitmap::isRecycled)
                        ?: preparedFont.pageAssetPaths.getOrNull(glyph.page)
                            ?.let { path -> requestBitmap(path, source) }
                            ?.also { loaded ->
                                preparedFont.pageBitmaps[glyph.page] = loaded
                            }
                    if (bitmap != null && !bitmap.isRecycled) {
                        val right = glyph.x + glyph.width
                        val bottom = glyph.y + glyph.height
                        if (glyph.x >= 0 && glyph.y >= 0 && right <= bitmap.width && bottom <= bitmap.height) {
                            bitmapFontSourceRect.set(glyph.x, glyph.y, right, bottom)
                            bitmapFontDestRect.set(
                                penX + glyph.xOffset * scale,
                                penTop + glyph.yOffset * scale,
                                penX + (glyph.xOffset + glyph.width) * scale,
                                penTop + (glyph.yOffset + glyph.height) * scale,
                            )
                            canvas.drawBitmap(bitmap, bitmapFontSourceRect, bitmapFontDestRect, imagePaint)
                        }
                    }
                    penX += glyph.xAdvance * scale
                    previous = codePoint
                }
                index += Character.charCount(codePoint)
            }
        }
        // 位图字体没有矢量轮廓可以 STROKE，按 clox 的做法用 8 方向偏移副本
        // 铺出硬描边；描边整体在正文之前绘制，所以正文永远压在最上层。
        resolveOutlineSpec(attrs, source)?.let { outline ->
            imagePaint.colorFilter = tintFilter(outline.color)
            val rings = if (outline.glow) {
                ceil(outline.size / 2f).toInt().coerceIn(1, MAX_GLOW_RINGS)
            } else {
                1
            }
            for (ring in rings downTo 1) {
                val ringAlpha = if (outline.glow) {
                    alpha * (outline.opacity / rings) * (1f - (ring - 1f) / rings)
                } else {
                    alpha * outline.opacity
                }
                if (ringAlpha <= 0f) continue
                imagePaint.alpha = paintAlpha(ringAlpha)
                val distance = outline.size * ring / rings
                for (step in 0 until OUTLINE_COPY_DIRECTIONS) {
                    val angle = Math.toRadians(step * 360.0 / OUTLINE_COPY_DIRECTIONS)
                    renderGlyphs(
                        distance * cos(angle).toFloat(),
                        distance * sin(angle).toFloat(),
                    )
                }
            }
        }
        imagePaint.alpha = paintAlpha(alpha)
        imagePaint.colorFilter = tintFilter(resolveColor(attrs["color"], Color.WHITE, source))
        renderGlyphs(0f, 0f)
        imagePaint.colorFilter = null
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
        fun paintShape(paint: Paint) {
            when {
                shape.contains("circle") ->
                    canvas.drawOval(-width / 2f, -height / 2f, width / 2f, height / 2f, paint)
                shape.contains("triangle") -> {
                    pathScratch.reset()
                    pathScratch.moveTo(0f, -height / 2f)
                    pathScratch.lineTo(width / 2f, height / 2f)
                    pathScratch.lineTo(-width / 2f, height / 2f)
                    pathScratch.close()
                    canvas.drawPath(pathScratch, paint)
                }
                else -> canvas.drawRect(-width / 2f, -height / 2f, width / 2f, height / 2f, paint)
            }
        }
        // WM_EFFECTS.md:42 —— outline/glow 的支持范围是 text 和 shape 两族。
        // 与文字一致：先 STROKE 描边，再 FILL 填充压在上面。
        resolveOutlineSpec(attrs, source)?.let { outline ->
            textOutlinePaint.reset()
            textOutlinePaint.isAntiAlias = true
            textOutlinePaint.style = Paint.Style.STROKE
            textOutlinePaint.strokeJoin = Paint.Join.ROUND
            textOutlinePaint.strokeCap = Paint.Cap.ROUND
            val rings = if (outline.glow) {
                ceil(outline.size / 2f).toInt().coerceIn(1, MAX_GLOW_RINGS)
            } else {
                1
            }
            for (ring in rings downTo 1) {
                val ringAlpha = if (outline.glow) {
                    alpha * (outline.opacity / rings) * (1f - (ring - 1f) / rings)
                } else {
                    alpha * outline.opacity
                }
                if (ringAlpha <= 0f) continue
                textOutlinePaint.color = outline.color
                textOutlinePaint.strokeWidth = outline.size * 2f * ring / rings
                textOutlinePaint.alpha = paintAlpha(ringAlpha)
                paintShape(textOutlinePaint)
            }
        }
        shapePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        shapePaint.alpha = paintAlpha(alpha)
        paintShape(shapePaint)
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
    ) {
        val path = evaluateText(attrs["path"] ?: attrs["source"] ?: "", source)
        val resolved = resolveAsset(path, source, conventionalDirectory = "images") ?: return
        val extension = resolved.file.extension.lowercase(Locale.ROOT)
        if (extension in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")) {
            drawImage(canvas, attrs + ("path" to path), alpha, source)
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
        return PreparedSource(
            sourceGeneration = sourceGeneration,
            root = canonicalRoot,
            rootCacheKey = rootCacheKey,
            definitionRevision = definitionRevision,
            watchDirectoryKey = watchDirectoryKey,
            definition = definition,
            exactFiles = exact,
            uniqueBasenames = basename,
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
                    type == "gif" || type == "image_gif" ||
                        (type == "chart" && layer.attributes.values.any { value ->
                            DYNAMIC_TAG_PATTERN.containsMatchIn(value)
                        }) ||
                        layer.attributes.values.any { value ->
                            value.contains("{drss}") || value.contains("{dss}")
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

    private fun requestBitmap(path: String, source: PreparedSource): Bitmap? {
        if (path.isBlank()) return null
        val resolved = resolveAsset(path, source, conventionalDirectory = "images") ?: return null
        val assetPrefix = "${source.rootCacheKey}\u0000${resolved.key}\u0000"
        val key = resolved.cacheRevision
        synchronized(cacheLock) {
            bitmapCache[key]?.let { return it }
            val previousFrame = bitmapCache.entries
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
                                            val staleKeys = bitmapCache.keys
                                                .filter { cachedKey ->
                                                    cachedKey.startsWith(assetPrefix) && cachedKey != key
                                                }
                                            staleKeys.forEach { staleKey ->
                                                bitmapCache.remove(staleKey)?.let { staleBitmap ->
                                                    bitmapCacheBytes -= staleBitmap.safeAllocationByteCount()
                                                    if (staleBitmap !== result && !staleBitmap.isRecycled) {
                                                        staleBitmap.recycle()
                                                    }
                                                }
                                            }
                                            putBitmapLocked(key, result)
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
        synchronized(cacheLock) {
            bitmapCache.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            bitmapCache.clear()
            bitmapCacheBytes = 0L
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
        val delay = if (source?.needsFastTicks == true) 33L else 1_000L
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
            // 规范（WM_TAP_ACTIONS.md）里 w_heart_rate/widget_health 是可配置
            // 命名动作：未映射目标应用时保持静默，但动作本身被识别。Flue 没有
            // 映射设置，所以最小行为是触觉反馈 + 触发一次新的心率测量并重绘。
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            runCatching { dataProvider.requestRefresh() }
            invalidate()
            return
        }
        val intent = when (action) {
            WatchTapAction.Calendar -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            WatchTapAction.Weather -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_WEATHER)
            WatchTapAction.HeartRate -> null
            is WatchTapAction.Unsupported -> null
        }
        if (intent == null) {
            val reason = (action as? WatchTapAction.Unsupported)?.reason ?: "unsupported action"
            Toast.makeText(context, "表盘点击动作不可用: $reason", Toast.LENGTH_SHORT).show()
            return
        }
        val resolved = runCatching { intent.resolveActivity(context.packageManager) }.getOrNull()
        if (resolved == null) {
            Toast.makeText(context, "设备没有可用的目标应用", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            context.startActivity(intent)
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
        val root: File,
        val rootCacheKey: String,
        val definitionRevision: String,
        val watchDirectoryKey: String,
        val definition: WatchmakerDefinition,
        val exactFiles: Map<String, ResolvedAsset>,
        val uniqueBasenames: Map<String, ResolvedAsset?>,
        val revisionByAbsolutePath: Map<String, String>,
        val fontIndex: WatchFontIndex,
        val bitmapFonts: Map<String, PreparedBitmapFont?>,
        val expressionScope: WatchExpressionScope,
        val renderPlan: List<WatchLayerRenderItem>,
        val renderPlanDiagnostics: WatchLayerGraphDiagnostics,
        val svgDocuments: Map<String, PreparedWatchSvgDocument<Path>>,
        val needsFastTicks: Boolean,
    ) {
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

    /**
     * WM_EFFECTS.md §2 的已解析描边参数。[size] 与 [opacity] 都已经归一到渲染
     * 用的单位（设计像素 / 0–1），[glow] 区分硬描边和柔光晕。
     */
    private data class WatchOutlineSpec(
        val glow: Boolean,
        val color: Int,
        val size: Float,
        val opacity: Float,
    )

    private data class TapTarget(val bounds: RectF, val action: WatchTapAction)

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
        private const val MAX_MOVIE_CACHE_ENTRIES = 4
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
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[a-zA-Z]:[/\\\\].*")
        private val TEXT_RING_LABELS = (1..12).map(Int::toString)
        // ICU (Android 14+ 的 java.util.regex 后端) 要求 '}' 必须转义，且字符组内的
        // '{' 会被当成 UnicodeSet 的多字符串起始符。裸写 "\\{[^{}]+}" 在 SDK 34+ 上
        // 抛 PatternSyntaxException，并因为它是 companion object 的字段而升级成
        // ExceptionInInitializerError —— 整个表盘视图类无法初始化。
        private val DYNAMIC_TAG_PATTERN = Regex("\\{[^\\{\\}]+\\}")

        /**
         * `o_size` 的上限。规范没有给硬上限，但 Studio 编辑器把它夹到 100
         * (WM_STUDIO_2.0_EDITOR.md:303)，这里留一点余量并防止恶意表盘用一个
         * 巨大的描边宽度把每帧拖垮。
         */
        private const val MAX_OUTLINE_DESIGN_SIZE = 120f

        /** clox 同样把 glow 环数封顶在 8（wf_render.lua:794-795）。 */
        private const val MAX_GLOW_RINGS = 8

        /** clox 的硬描边用 8 个方向的偏移副本（wf_render.lua:824）。 */
        private const val OUTLINE_COPY_DIRECTIONS = 8
    }
}

/**
 * 编辑器画布手势期间推给渲染器的实时图层变换。
 *
 * [layerIndex] 是图层在 `WatchmakerDefinition.layers` 里的下标；[layerId] 和
 * [layerType] 只用于校验下标没有错位（见 `liveTransformFor`）。
 * [scaleX] / [scaleY] 已经是倍率（不是 Watchmaker 的百分比），[rotation] 是角度。
 */
data class WatchLiveLayerTransform(
    val layerIndex: Int,
    val layerId: String?,
    val layerType: String,
    val x: Float,
    val y: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotation: Float,
)

@Composable
fun WatchmakerWatchFaceHost(
    descriptor: LunchWatchFaceDescriptor,
    isFaceVisible: Boolean,
    refreshToken: Int,
    onLongPress: (() -> Unit)?,
    onDoubleTap: (() -> Unit)?,
    onLoadFailure: (LunchWatchFaceDescriptor, Throwable) -> Unit,
    dataProvider: WatchDataProvider? = null,
    onTapAction: ((WatchTapAction) -> Unit)? = null,
    forceFill: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ownedDataProvider = rememberWatchmakerWatchDataProvider(
        active = dataProvider == null && isFaceVisible,
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
            )
        },
        onRelease = WatchmakerWatchFaceView::dispose,
    )
}
