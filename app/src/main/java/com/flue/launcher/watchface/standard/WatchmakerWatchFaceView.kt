package com.flue.launcher.watchface.standard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
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
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var bindGeneration = 0L
    private var disposed = false
    private var source: PreparedSource? = null
    private var descriptorKey: String? = null
    private var faceVisible = false
    private var refreshToken = 0
    private var longPressAction: (() -> Unit)? = null
    private var doubleTapAction: (() -> Unit)? = null
    private var tickPosted = false

    private val bitmapCache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var bitmapCacheBytes = 0L
    /** Asset key -> bind generation which owns the in-flight decode. */
    private val bitmapLoading = HashMap<String, Long>()
    /** Failed assets are retried only after a cooldown, never once per frame. */
    private val bitmapFailures = HashMap<String, BitmapFailure>()
    /** Typeface key -> bind generation which owns the in-flight load. */
    private val typefaceLoading = HashMap<String, Long>()
    private val typefaceCache = HashMap<String, Typeface>()
    private val cacheLock = Any()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pathScratch = Path()
    private val rectScratch = RectF()
    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("EEE", Locale.getDefault())
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = faceVisible

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (faceVisible) doubleTapAction?.invoke()
                return faceVisible
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean = faceVisible

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
    ) {
        if (disposed) return
        require(descriptor.isWatchmaker) { "Watchmaker view requires a standard .watch descriptor" }
        this.faceVisible = isFaceVisible
        this.longPressAction = onLongPress
        this.doubleTapAction = onDoubleTap
        if (descriptorKey == descriptor.stableKey && this.refreshToken == refreshToken) {
            if (faceVisible) scheduleTicks() else cancelTicks()
            invalidate()
            return
        }
        descriptorKey = descriptor.stableKey
        this.refreshToken = refreshToken
        val generation = ++bindGeneration
        loadJob?.cancel()
        source = null
        cancelTicks()
        clearAssetCaches()
        invalidate()
        val root = descriptor.sourceDirPath?.let(::File)
        if (root == null) {
            descriptorKey = null
            onLoadFailure(descriptor, IllegalArgumentException(".watch 表盘没有安装目录"))
            return
        }
        loadJob = mainScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    prepareSource(root)
                }
                if (generation != bindGeneration || disposed) return@launch
                source = prepared
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

    fun dispose() {
        if (disposed) return
        disposed = true
        bindGeneration++
        descriptorKey = null
        faceVisible = false
        loadJob?.cancel()
        loadJob = null
        mainScope.cancel()
        cancelTicks()
        synchronized(cacheLock) {
            bitmapCache.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            bitmapCache.clear()
            bitmapCacheBytes = 0L
            bitmapLoading.clear()
            bitmapFailures.clear()
            typefaceLoading.clear()
            typefaceCache.clear()
        }
        longPressAction = null
        doubleTapAction = null
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
        val scale = min(width, height) / DESIGN_SIZE
        val left = (width - DESIGN_SIZE * scale) / 2f
        val top = (height - DESIGN_SIZE * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        // Watchmaker coordinates are centered on (0, 0), unlike Android's
        // top-left canvas coordinates. Keeping this translation at the single
        // renderer boundary makes every primitive (including groups and
        // rings) use the same coordinate contract.
        canvas.translate(DESIGN_SIZE / 2f, DESIGN_SIZE / 2f)
        canvas.clipRect(-DESIGN_SIZE / 2f, -DESIGN_SIZE / 2f, DESIGN_SIZE / 2f, DESIGN_SIZE / 2f)
        if (prepared.definition.shape.equals("R", ignoreCase = true)) {
            pathScratch.reset()
            pathScratch.addCircle(0f, 0f, DESIGN_SIZE / 2f, Path.Direction.CW)
            canvas.clipPath(pathScratch)
        }
        calendar.timeInMillis = System.currentTimeMillis()
        backgroundPaint.color = resolveColor(prepared.definition.rootAttributes["bg_color"], Color.BLACK, prepared)
        canvas.drawRect(-DESIGN_SIZE / 2f, -DESIGN_SIZE / 2f, DESIGN_SIZE / 2f, DESIGN_SIZE / 2f, backgroundPaint)
        prepared.roots.forEach { drawNode(canvas, it, 1f, prepared) }
        canvas.restore()
    }

    private fun drawNode(canvas: Canvas, layer: WatchmakerLayerNode, inheritedAlpha: Float, source: PreparedSource) {
        val attributes = layer.layer.attributes
        val opacity = number(attributes["opacity"], 100f).coerceIn(0f, 100f) / 100f
        val alpha = inheritedAlpha * opacity
        if (alpha <= 0f || !isDisplayed(attributes["display"])) return
        canvas.save()
        applyTransform(canvas, attributes, source)
        if (layer.layer.isGroup) {
            layer.children.forEach { child -> drawNode(canvas, child, alpha, source) }
        } else {
            drawPrimitive(canvas, layer.layer, alpha, source)
            layer.children.forEach { child -> drawNode(canvas, child, alpha, source) }
        }
        canvas.restore()
    }

    private fun applyTransform(canvas: Canvas, attrs: Map<String, String>, source: PreparedSource) {
        canvas.translate(number(attrs["x"], 0f), number(attrs["y"], 0f))
        val rotation = number(resolveDynamic(attrs["rotation"], source), 0f)
        if (rotation != 0f) canvas.rotate(rotation)
        val sx = number(attrs["anim_scale_x"], 100f).coerceIn(-500f, 500f) / 100f
        val sy = number(attrs["anim_scale_y"], 100f).coerceIn(-500f, 500f) / 100f
        if (sx != 1f || sy != 1f) canvas.scale(sx, sy)
        val skewX = number(attrs["skew_x"], 0f)
        val skewY = number(attrs["skew_y"], 0f)
        if (skewX != 0f || skewY != 0f) {
            canvas.skew(kotlin.math.tan(Math.toRadians(skewX.toDouble())).toFloat(), kotlin.math.tan(Math.toRadians(skewY.toDouble())).toFloat())
        }
    }

    private fun drawPrimitive(canvas: Canvas, layer: WatchmakerLayer, alpha: Float, source: PreparedSource) {
        val attrs = layer.attributes
        when (layer.type.lowercase(Locale.ROOT)) {
            "image", "gif", "image_gif", "image_tap" -> drawImage(canvas, attrs, alpha, source)
            "image_cond" -> drawImage(canvas, attrs, alpha, source, sprite = true)
            "text", "series", "calendar" -> drawText(canvas, attrs, alpha, source)
            "ring" -> drawRing(canvas, attrs, alpha, source)
            "progress" -> drawProgress(canvas, attrs, alpha, source)
            "shape" -> drawShape(canvas, attrs, alpha, source)
            "rounded" -> drawRounded(canvas, attrs, alpha, source)
            "markers" -> drawMarkers(canvas, attrs, alpha, source)
            "markers_hm" -> drawHourMinuteMarkers(canvas, attrs, alpha, source)
            "text_curved" -> drawCurvedText(canvas, attrs, alpha, source)
            "text_ring" -> drawTextRing(canvas, attrs, alpha, source)
        }
    }

    private fun drawImage(
        canvas: Canvas,
        attrs: Map<String, String>,
        alpha: Float,
        source: PreparedSource,
        sprite: Boolean = false,
    ) {
        val path = attrs["path"]?.substringBefore('`')?.trim().orEmpty()
        val bitmap = requestBitmap(path, source) ?: return
        val width = number(attrs["width"], bitmap.width.toFloat()).coerceAtLeast(1f)
        val height = number(attrs["height"], bitmap.height.toFloat()).coerceAtLeast(1f)
        val grid = if (sprite) parseGrid(attrs["cond_grid"]) else 1 to 1
        val index = if (sprite) resolveSpriteIndex(attrs["cond_value"], source) else 0
        val cellWidth = bitmap.width / grid.first.coerceAtLeast(1)
        val cellHeight = bitmap.height / grid.second.coerceAtLeast(1)
        val col = (index % grid.first).coerceIn(0, grid.first - 1)
        val row = (index / grid.first).coerceIn(0, grid.second - 1)
        val src = Rect(
            col * cellWidth,
            row * cellHeight,
            (col + 1) * cellWidth,
            (row + 1) * cellHeight,
        )
        val dst = anchoredRect(width, height, attrs["alignment"])
        imagePaint.alpha = paintAlpha(alpha)
        imagePaint.colorFilter = tintFilter(resolveColor(attrs["color"], Color.WHITE, source))
        canvas.drawBitmap(bitmap, src, dst, imagePaint)
        imagePaint.colorFilter = null
    }

    private fun drawText(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val raw = attrs["text"].orEmpty()
        if (raw.isBlank()) return
        val value = evaluateText(raw, source)
        val size = number(attrs["text_size"], 24f).coerceIn(1f, 512f)
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
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawRing(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val outer = number(attrs["radius_outer"], number(attrs["radius"], 100f)).coerceAtLeast(1f)
        val inner = number(attrs["radius_inner"], outer - 8f).coerceIn(0f, outer - 0.5f)
        val stroke = (outer - inner).coerceAtLeast(1f)
        val radius = inner + stroke / 2f
        val total = number(attrs["angle_total"], 360f).coerceAtLeast(1f)
        val sweep = number(resolveDynamic(attrs["angle"], source), total).coerceIn(0f, total)
        val clockwise = !attrs["is_clockwise"].equals("N", ignoreCase = true)
        strokePaint.strokeWidth = stroke
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = if (attrs["end_style"].equals("round", true)) Paint.Cap.ROUND else Paint.Cap.BUTT
        strokePaint.color = resolveColor(attrs["color3"], Color.TRANSPARENT, source)
        strokePaint.alpha = paintAlpha(alpha * number(attrs["outside_opacity"], 100f) / 100f)
        if (strokePaint.alpha > 0) canvas.drawCircle(0f, 0f, radius, strokePaint)
        strokePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        strokePaint.alpha = paintAlpha(alpha)
        rectScratch.set(-radius, -radius, radius, radius)
        canvas.drawArc(rectScratch, -90f, if (clockwise) sweep else -sweep, false, strokePaint)
    }

    private fun drawProgress(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val width = number(attrs["width"], 100f).coerceAtLeast(1f)
        val height = number(attrs["height"], 20f).coerceAtLeast(1f)
        val pct = number(resolveDynamic(attrs["pct_complete"], source), 0f).coerceIn(0f, 100f) / 100f
        val radius = number(attrs["radius"], height / 2f).coerceAtLeast(0f).coerceAtMost(height / 2f)
        val left = -width / 2f
        val top = -height / 2f
        shapePaint.color = resolveColor(attrs["color3"], Color.DKGRAY, source)
        shapePaint.alpha = paintAlpha(alpha * number(attrs["outside_opacity"], 100f) / 100f)
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, shapePaint)
        shapePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        shapePaint.alpha = paintAlpha(alpha)
        canvas.drawRoundRect(left, top, left + width * pct, top + height, radius, radius, shapePaint)
    }

    private fun drawShape(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val width = number(attrs["width"], 64f).coerceAtLeast(1f)
        val height = number(attrs["height"], width).coerceAtLeast(1f)
        val shape = attrs["shape"].orEmpty().lowercase(Locale.ROOT)
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

    private fun drawRounded(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val width = number(attrs["width"], 64f).coerceAtLeast(1f)
        val height = number(attrs["height"], 64f).coerceAtLeast(1f)
        val radius = number(attrs["radius"], min(width, height) / 4f).coerceAtLeast(0f)
        shapePaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        shapePaint.alpha = paintAlpha(alpha)
        canvas.drawRoundRect(-width / 2f, -height / 2f, width / 2f, height / 2f, radius, radius, shapePaint)
    }

    private fun drawMarkers(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val count = number(attrs["m_count"], 12f).roundToInt().coerceIn(1, 120)
        val radius = number(attrs["radius"], 220f)
        val markWidth = number(attrs["m_width"], 6f).coerceAtLeast(1f)
        val markHeight = number(attrs["m_height"], 20f).coerceAtLeast(1f)
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
        val radius = number(attrs["radius"], 220f)
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
        val radius = number(attrs["radius"], 180f).coerceAtLeast(1f)
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        textPaint.textSize = number(attrs["text_size"], 24f).coerceAtLeast(1f)
        textPaint.color = resolveColor(attrs["color"], Color.WHITE, source)
        textPaint.alpha = paintAlpha(alpha)
        textPaint.textAlign = Paint.Align.CENTER
        pathScratch.reset()
        val top = -radius
        val direction = if (attrs["curve_dir"].equals("Down", true)) 180f else 0f
        pathScratch.addArc(-radius, top, radius, radius, direction, 180f)
        canvas.drawTextOnPath(text, pathScratch, 0f, 0f, textPaint)
    }

    private fun drawTextRing(canvas: Canvas, attrs: Map<String, String>, alpha: Float, source: PreparedSource) {
        val radius = number(attrs["radius"], 180f).coerceAtLeast(1f)
        val start = number(attrs["angle_start"], 0f)
        val end = number(attrs["angle_end"], 360f)
        textPaint.typeface = resolveTypeface(attrs["font"], source)
        textPaint.textSize = number(attrs["text_size"], 24f).coerceAtLeast(1f)
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

    private fun prepareSource(root: File): PreparedSource {
        require(root.isDirectory) { ".watch 表盘安装目录不存在" }
        val watchXml = File(root, "watch.xml").takeIf(File::isFile)
            ?: root.walkTopDown().firstOrNull { it.isFile && it.name.equals("watch.xml", true) }
            ?: error(".watch 表盘缺少 watch.xml")
        val definition = WatchmakerDefinitionParser().parse(watchXml)
        val watchDirectoryKey = (watchXml.parentFile ?: root)
            .relativeTo(root)
            .invariantSeparatorsPath
            .takeUnless { it == "." }
            .orEmpty()
            .lowercase(Locale.ROOT)
        val files = root.walkTopDown().filter(File::isFile).toList()
        val exact = HashMap<String, File>(files.size)
        val basename = HashMap<String, File?>()
        files.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath.lowercase(Locale.ROOT)
            exact[relative] = file
            val key = file.name.lowercase(Locale.ROOT)
            basename[key] = if (basename.containsKey(key)) null else file
        }
        val roots = buildWatchTree(definition.layers)
        return PreparedSource(
            root = root,
            watchDirectoryKey = watchDirectoryKey,
            definition = definition,
            exactFiles = exact,
            uniqueBasenames = basename,
            roots = roots,
            needsFastTicks = definition.layers.any { layer ->
                layer.attributes.values.any { value -> value.contains("{drss}") || value.contains("{dss}") }
            },
        )
    }

    private fun buildWatchTree(layers: List<WatchmakerLayer>): List<WatchmakerLayerNode> {
        val nodes = layers.map(::WatchmakerLayerNode)
        val byId = nodes.mapNotNull { node -> node.layer.id?.let { it to node } }.toMap()
        val roots = ArrayList<WatchmakerLayerNode>()
        nodes.forEach { node ->
            val parent = node.layer.parentId?.let(byId::get)
            if (parent == null) roots += node else parent.children += node
        }
        return roots
    }

    private fun requestBitmap(path: String, source: PreparedSource): Bitmap? {
        if (path.isBlank()) return null
        val resolved = resolveAsset(path, source, conventionalDirectory = "images") ?: return null
        val key = resolved.key
        synchronized(cacheLock) {
            bitmapCache[key]?.let { return it }
            bitmapFailures[key]?.let { failure ->
                val inCooldown = failure.generation == bindGeneration &&
                    SystemClock.uptimeMillis() - failure.failedAtUptimeMs < BITMAP_FAILURE_RETRY_MS
                if (inCooldown) return null
                bitmapFailures.remove(key)
            }
            if (bitmapLoading[key] == null) {
                val generation = bindGeneration
                bitmapLoading[key] = generation
                mainScope.launch(Dispatchers.IO) {
                    var decoded: Bitmap? = null
                    try {
                        decoded = decodeBitmapSafely(resolved.file)
                        withContext(Dispatchers.Main.immediate) {
                            val result = decoded
                            var currentRequest = false
                            synchronized(cacheLock) {
                                currentRequest = bitmapLoading[key] == generation
                                if (currentRequest) bitmapLoading.remove(key)
                                if (currentRequest && generation == bindGeneration && !disposed) {
                                    if (result != null) {
                                        putBitmapLocked(key, result)
                                        bitmapFailures.remove(key)
                                        decoded = null // Ownership transferred to the LRU cache.
                                    } else {
                                        bitmapFailures[key] = BitmapFailure(
                                            generation = generation,
                                            failedAtUptimeMs = SystemClock.uptimeMillis(),
                                        )
                                    }
                                }
                            }
                            if (currentRequest && generation == bindGeneration && !disposed) invalidate()
                        }
                    } finally {
                        // Cancellation can happen after decode but before the main-thread
                        // hand-off. In that case the bitmap never entered the cache.
                        decoded?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                    }
                }
            }
        }
        return null
    }

    private fun resolveTypeface(name: String?, source: PreparedSource): Typeface {
        val normalized = name.orEmpty().removePrefix("bm:").trim()
        if (normalized.isBlank() || normalized.equals("Roboto-Regular", true)) return Typeface.DEFAULT
        val key = normalized.lowercase(Locale.ROOT)
        synchronized(cacheLock) { typefaceCache[key]?.let { return it } }
        val file = source.exactFiles[key]
            ?: source.exactFiles["fonts/$key"]
            ?: source.uniqueBasenames[key]
        if (file == null) {
            synchronized(cacheLock) { typefaceCache[key] = Typeface.DEFAULT }
            return Typeface.DEFAULT
        }
        synchronized(cacheLock) {
            if (typefaceLoading[key] == null) {
                val generation = bindGeneration
                typefaceLoading[key] = generation
                mainScope.launch(Dispatchers.IO) {
                    val loaded = runCatching { Typeface.createFromFile(file) }.getOrNull()
                    withContext(Dispatchers.Main.immediate) {
                        var currentRequest = false
                        synchronized(cacheLock) {
                            currentRequest = typefaceLoading[key] == generation
                            if (currentRequest) typefaceLoading.remove(key)
                            if (currentRequest && generation == bindGeneration && !disposed) {
                                typefaceCache[key] = loaded ?: Typeface.DEFAULT
                            }
                        }
                        if (currentRequest && generation == bindGeneration && !disposed) invalidate()
                    }
                }
            }
        }
        return Typeface.DEFAULT
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

    private fun Bitmap.safeAllocationByteCount(): Long =
        runCatching { allocationByteCount.toLong() }
            .getOrElse { runCatching { byteCount.toLong() }.getOrDefault(0L) }

    private fun clearAssetCaches() {
        synchronized(cacheLock) {
            bitmapCache.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            bitmapCache.clear()
            bitmapCacheBytes = 0L
            bitmapLoading.clear()
            bitmapFailures.clear()
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
        var value = raw.replace("&quot;", "\"").replace("&apos;", "'")
        value = value.replace(DYNAMIC_TAG_PATTERN) { match ->
            resolveTag(match.groupValues[1], calendar, source)
        }
        // Cover the most common Studio expression emitted for forecast hours.
        value = STUDIO_FORMAT_PATTERN.replace(value) {
            it.groupValues[1].toIntOrNull()?.toString()?.padStart(2, '0') ?: value
        }
        return value.takeIf { !it.contains("string.format") } ?: "--"
    }

    private fun resolveDynamic(raw: String?, source: PreparedSource): String =
        raw?.let { evaluateText(it, source) }.orEmpty()

    private fun resolveTag(tag: String, now: Calendar, source: PreparedSource): String {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        val hour24 = now.get(Calendar.HOUR_OF_DAY)
        val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
        return when (normalized) {
            "dh" -> hour12.toString()
            "dhz" -> hour12.toString().padStart(2, '0')
            "dh23" -> hour24.toString()
            "dh23z" -> hour24.toString().padStart(2, '0')
            "dm" -> now.get(Calendar.MINUTE).toString()
            "dmz" -> now.get(Calendar.MINUTE).toString().padStart(2, '0')
            "ds" -> now.get(Calendar.SECOND).toString()
            "dss" -> now.get(Calendar.SECOND).toString().padStart(2, '0')
            "drss" -> ((now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f) * 6f).toString()
            "drm" -> ((now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f) * 6f).toString()
            "drh" -> ((hour24 % 12 + now.get(Calendar.MINUTE) / 60f) * 30f).toString()
            "dd" -> now.get(Calendar.DAY_OF_MONTH).toString()
            "ddz" -> now.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
            "dnn", "dnnn" -> (now.get(Calendar.DAY_OF_MONTH)).toString()
            "dnnnn" -> dateFormat.format(Date(now.timeInMillis)).take(3)
            "dy" -> now.get(Calendar.YEAR).toString()
            "blp", "batt", "battery" -> "--"
            "ucolor" -> source.definition.rootAttributes["ucolor_default"].orEmpty()
            else -> "--"
        }
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

    private fun isDisplayed(display: String?): Boolean =
        display.isNullOrBlank() || display.equals("b", true) || display.equals("bd", true)

    private fun number(value: String?, fallback: Float): Float =
        value?.toFloatOrNull()?.takeIf(Float::isFinite) ?: fallback

    private fun parseGrid(value: String?): Pair<Int, Int> {
        val parts = value.orEmpty().lowercase(Locale.ROOT).split('x')
        return (parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 32) ?: 1) to
            (parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 32) ?: 1)
    }

    private fun resolveSpriteIndex(value: String?, source: PreparedSource): Int {
        val resolved = evaluateText(value.orEmpty(), source)
        return resolved.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
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
        val reference = normalizeAssetReference(rawPath) ?: return null
        val isRootRelative = rawPath.trim().replace('\\', '/').startsWith('/')
        val cacheKey = if (isRootRelative) "/$reference" else reference
        if (source.assetResolutionCache.containsKey(cacheKey)) {
            return source.assetResolutionCache[cacheKey]
        }

        val candidates = LinkedHashSet<String>()
        fun addCandidate(base: String, value: String) {
            normalizeRelativeAsset(base, value)?.let(candidates::add)
        }
        val base = if (isRootRelative) "" else source.watchDirectoryKey
        addCandidate(base, reference)
        if (!reference.startsWith("$conventionalDirectory/", ignoreCase = true)) {
            addCandidate(base, "$conventionalDirectory/$reference")
        }
        // A few 1.0 exporters deliberately use root-relative paths even when
        // watch.xml is nested. Keep this fallback after the local interpretation.
        addCandidate("", reference)
        if (!reference.startsWith("$conventionalDirectory/", ignoreCase = true)) {
            addCandidate("", "$conventionalDirectory/$reference")
        }

        val resolved = candidates.firstNotNullOfOrNull { key ->
            source.exactFiles[key]?.let { file -> ResolvedAsset(key, file) }
        } ?: reference.substringAfterLast('/').let { basename ->
            source.uniqueBasenames[basename]?.let { file ->
                val key = file.relativeTo(source.root).invariantSeparatorsPath.lowercase(Locale.ROOT)
                ResolvedAsset(key, file)
            }
        }
        source.assetResolutionCache[cacheKey] = resolved
        return resolved
    }

    private fun normalizeAssetReference(raw: String): String? {
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
        val root: File,
        val watchDirectoryKey: String,
        val definition: WatchmakerDefinition,
        val exactFiles: Map<String, File>,
        val uniqueBasenames: Map<String, File?>,
        val roots: List<WatchmakerLayerNode>,
        val needsFastTicks: Boolean,
    ) {
        /** Main-thread-only memoization; the installed tree is immutable. */
        val assetResolutionCache: MutableMap<String, ResolvedAsset?> = HashMap()
    }

    private data class ResolvedAsset(val key: String, val file: File)

    private data class BitmapFailure(val generation: Long, val failedAtUptimeMs: Long)

    private class WatchmakerLayerNode(val layer: WatchmakerLayer) {
        val children: MutableList<WatchmakerLayerNode> = ArrayList()
    }

    companion object {
        private const val DESIGN_SIZE = 512f
        private const val MAX_BITMAP_CACHE_ENTRIES = 24
        private const val MAX_BITMAP_CACHE_BYTES = 24L * 1024L * 1024L
        private const val MAX_DECODE_DIMENSION_PX = 1_024
        private const val BITMAP_FAILURE_RETRY_MS = 30_000L
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[a-zA-Z]:[/\\\\].*")
        private val TEXT_RING_LABELS = (1..12).map(Int::toString)
        private val DYNAMIC_TAG_PATTERN = Regex("\\{([^{}]+)\\}")
        private val STUDIO_FORMAT_PATTERN =
            Regex("string\\.format\\(\\\"%02d\\\",\\s*\\(?(-?\\d+)\\)?\\)")
    }
}

@Composable
fun WatchmakerWatchFaceHost(
    descriptor: LunchWatchFaceDescriptor,
    isFaceVisible: Boolean,
    refreshToken: Int,
    onLongPress: (() -> Unit)?,
    onDoubleTap: (() -> Unit)?,
    onLoadFailure: (LunchWatchFaceDescriptor, Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> WatchmakerWatchFaceView(context) },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            view.bind(
                descriptor = descriptor,
                refreshToken = refreshToken,
                isFaceVisible = isFaceVisible,
                onLongPress = onLongPress,
                onDoubleTap = onDoubleTap,
                onLoadFailure = onLoadFailure,
            )
        },
        onRelease = WatchmakerWatchFaceView::dispose,
    )
}
