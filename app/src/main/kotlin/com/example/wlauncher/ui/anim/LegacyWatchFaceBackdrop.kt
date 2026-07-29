package com.flue.launcher.ui.anim

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.PixelCopy
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.WatchFaceDescriptor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal const val LEGACY_BACKDROP_BLUR_DOWNSAMPLE_DIVISOR = 12
internal const val LEGACY_BACKDROP_BLUR_PASSES = 3
internal const val LEGACY_BACKDROP_BLUR_RADIUS = 6

/**
 * API 27-30 的表盘背衬缓存。
 *
 * 两张模糊图都在用户开始拖动之前于 IO 线程生成。清晰层始终由真实表盘
 * 绘制，缓存只用于边缘填充和模糊覆盖；手势阶段不再解码媒体或计算模糊。
 */
data class LegacyWatchFaceBackdropFrames(
    val clear: ImageBitmap,
    val lightBlurred: ImageBitmap,
    val darkBlurred: ImageBitmap,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val overscanX: Int,
    val overscanY: Int,
    val fallbackArgb: Int,
    val allocationBytes: Int,
)

/** A real, already-composited launcher watch-face frame captured while idle. */
internal data class LegacyWatchFaceCapturedFrame(
    val visualKey: String,
    val boundsKey: String,
    val generation: Long,
    val bitmap: Bitmap,
)

/**
 * Captures the actual window pixels for API 27-30. PixelCopy is deliberately
 * kept out of the drag path: a visual configuration gets one settled capture
 * and one delayed replacement, then all transitions reuse the prepared frame.
 */
@Composable
internal fun rememberLegacyWatchFacePixelCopyFrame(
    visualKey: String,
    sourceBoundsInWindow: android.graphics.Rect?,
    captureAllowed: Boolean,
): LegacyWatchFaceCapturedFrame? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ) {
        return null
    }

    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(context, view) {
        (context.findActivity() ?: view.context.findActivity())?.window
    }
    val generation = remember { AtomicLong(0L) }
    var captured by remember { mutableStateOf<LegacyWatchFaceCapturedFrame?>(null) }
    val requestedBounds = sourceBoundsInWindow?.let { android.graphics.Rect(it) }
    val boundsKey = requestedBounds?.let {
        "${it.left},${it.top},${it.right},${it.bottom}"
    }

    LaunchedEffect(visualKey, boundsKey, captureAllowed, window) {
        val requestedWindow = window ?: return@LaunchedEffect
        val requestedRect = requestedBounds ?: return@LaunchedEffect
        val requestedKey = boundsKey ?: return@LaunchedEffect
        if (!captureAllowed || requestedRect.isEmpty) {
            return@LaunchedEffect
        }
        if (captured?.let { it.visualKey == visualKey && it.boundsKey == requestedKey } == true) {
            return@LaunchedEffect
        }

        // Let AndroidView/TextureView and the watch-face switch fade finish.
        // The second capture replaces a possible first-frame media placeholder.
        val delays = longArrayOf(280L, 720L)
        for (settleDelay in delays) {
            delay(settleDelay)
            ensureActive()
            val frame = requestWindowPixelCopy(
                window = requestedWindow,
                requestedBounds = requestedRect,
                windowWidth = view.rootView.width,
                windowHeight = view.rootView.height,
            ) ?: continue
            var frameOwnedByEffect = true
            try {
                ensureActive()
                val previous = captured
                captured = LegacyWatchFaceCapturedFrame(
                    visualKey = visualKey,
                    boundsKey = requestedKey,
                    generation = generation.incrementAndGet(),
                    bitmap = frame,
                )
                frameOwnedByEffect = false
                previous?.bitmap?.let(::recycleCapturedBitmapSafely)
            } finally {
                if (frameOwnedByEffect) recycleCapturedBitmapSafely(frame)
            }
        }
    }
    val latestCaptured by rememberUpdatedState(captured)
    DisposableEffect(Unit) {
        onDispose { latestCaptured?.bitmap?.let(::recycleCapturedBitmapSafely) }
    }

    return captured?.takeIf { it.visualKey == visualKey && it.boundsKey == boundsKey }
}

private fun recycleCapturedBitmapSafely(bitmap: Bitmap) {
    synchronized(bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private suspend fun requestWindowPixelCopy(
    window: Window,
    requestedBounds: android.graphics.Rect,
    windowWidth: Int,
    windowHeight: Int,
): Bitmap? = suspendCancellableCoroutine { continuation ->
    val maxRight = windowWidth.coerceAtLeast(1)
    val maxBottom = windowHeight.coerceAtLeast(1)
    // Rect.intersect() leaves the receiver untouched when there is no
    // intersection. Clamp every edge explicitly so an off-window stale layout
    // bound can never allocate a huge bitmap or be sent to PixelCopy.
    val bounds = android.graphics.Rect(
        requestedBounds.left.coerceIn(0, maxRight),
        requestedBounds.top.coerceIn(0, maxBottom),
        requestedBounds.right.coerceIn(0, maxRight),
        requestedBounds.bottom.coerceIn(0, maxBottom),
    )
    if (bounds.isEmpty) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
    }
    val destination = runCatching {
        Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
    }.getOrNull()
    if (destination == null) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
    }
    val handler = Handler(Looper.getMainLooper())
    runCatching {
        PixelCopy.request(
            window,
            bounds,
            destination,
            { result ->
                if (result == PixelCopy.SUCCESS && continuation.isActive) {
                    continuation.resume(destination)
                } else {
                    if (!destination.isRecycled) destination.recycle()
                    if (continuation.isActive) continuation.resume(null)
                }
            },
            handler,
        )
    }.onFailure {
        if (!destination.isRecycled) destination.recycle()
        if (continuation.isActive) continuation.resume(null)
    }
}

private enum class LegacyBackdropSourceKind {
    IMAGE,
    VIDEO,
    GENERATED,
}

private data class LegacyBackdropSource(
    val key: String,
    val kind: LegacyBackdropSourceKind,
    val path: String?,
    val fallbackArgb: Int,
    val generatedMaterialStyle: Boolean,
)

private object LegacyBackdropFrameCache {
    // Wear 表盘通常只有 360-480px；24 MiB 足够缓存数个三联帧，同时不会让
    // 老设备因为选择过几个表盘就长期背负整张原图。
    private const val MAX_CACHE_KIB = 24 * 1024
    private val cache = object : LruCache<String, LegacyWatchFaceBackdropFrames>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: LegacyWatchFaceBackdropFrames): Int =
            (value.allocationBytes / 1024).coerceAtLeast(1)
    }
    private var lastSuccessful: LegacyWatchFaceBackdropFrames? = null

    fun get(key: String): LegacyWatchFaceBackdropFrames? = cache.get(key)

    fun put(key: String, value: LegacyWatchFaceBackdropFrames) {
        cache.put(key, value)
        lastSuccessful = value
    }

    fun lastSuccessfulFor(width: Int, height: Int): LegacyWatchFaceBackdropFrames? =
        lastSuccessful?.takeIf { it.viewportWidth == width && it.viewportHeight == height }
}

@Composable
internal fun rememberLegacyWatchFaceBackdropFrames(
    descriptor: WatchFaceDescriptor,
    builtInPhotoPath: String?,
    builtInVideoPath: String?,
    refreshToken: Int,
    fallbackColor: Color,
    builtInMaterialStyle: Boolean,
    visualKey: String,
    capturedFrame: LegacyWatchFaceCapturedFrame?,
    viewportSize: IntSize?,
): LegacyWatchFaceBackdropFrames? {
    // AndroidView-backed Watchmaker/JB faces are not reliably captured by a
    // Compose GraphicsLayer on newer Android releases. Keep a prepared clear
    // source for those faces so hardware blur can still fill the shrinking
    // edge instead of exposing the black window background.
    val needsPreparedFrames = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        descriptor.isWatchmaker || descriptor.isJbWatch
    if (!needsPreparedFrames) return null

    val measuredViewport = viewportSize?.takeIf { it.width > 0 && it.height > 0 }
        ?: return null

    val context = LocalContext.current.applicationContext
    // The backdrop is composited into the measured watch-face layer, not the
    // Configuration screen size. Insets, round-screen adaptation and window
    // metrics can all make those differ; caching the latter was what allowed a
    // clear cached frame to be stretched on the first overlay frame.
    val targetWidth = measuredViewport.width
    val targetHeight = measuredViewport.height
    val fallbackArgb = fallbackColor.toArgb() or (0xFF shl 24)
    val source = legacyBackdropSource(
        descriptor = descriptor,
        builtInPhotoPath = builtInPhotoPath,
        builtInVideoPath = builtInVideoPath,
        refreshToken = refreshToken,
        fallbackArgb = fallbackArgb,
        builtInMaterialStyle = builtInMaterialStyle,
        visualKey = visualKey,
    )
    val matchingCapture = capturedFrame?.takeIf { it.visualKey == visualKey && !it.bitmap.isRecycled }
    val captureSignature = matchingCapture?.let {
        "pixel-copy:${it.boundsKey}:${it.generation}"
    } ?: "fallback"
    val cacheKey = "${source.key}|$captureSignature|${targetWidth}x${targetHeight}"

    return produceState(
        // Keep the last complete triplet while a newly selected face is being
        // decoded. A stale blurred cover for a few frames is far less jarring
        // than the white/black fallback that previously flashed on API 27-30.
        initialValue = LegacyBackdropFrameCache.get(cacheKey)
            ?: LegacyBackdropFrameCache.lastSuccessfulFor(targetWidth, targetHeight),
        key1 = cacheKey,
    ) {
        LegacyBackdropFrameCache.get(cacheKey)?.let { cached ->
            value = cached
            return@produceState
        }
        val prepared = withContext(Dispatchers.Default) {
            prepareLegacyBackdropFrames(
                context = context,
                source = source,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                capturedBitmap = matchingCapture?.bitmap,
            )
        }
        if (prepared != null) {
            LegacyBackdropFrameCache.put(cacheKey, prepared)
            value = prepared
        }
    }.value
}

private fun legacyBackdropSource(
    descriptor: WatchFaceDescriptor,
    builtInPhotoPath: String?,
    builtInVideoPath: String?,
    refreshToken: Int,
    fallbackArgb: Int,
    builtInMaterialStyle: Boolean,
    visualKey: String,
): LegacyBackdropSource {
    val (kind, path) = when (descriptor.id) {
        BUILT_IN_PHOTO_WATCHFACE_ID -> LegacyBackdropSourceKind.IMAGE to builtInPhotoPath
        BUILT_IN_VIDEO_WATCHFACE_ID -> LegacyBackdropSourceKind.VIDEO to builtInVideoPath
        BUILT_IN_WATCHFACE_ID -> LegacyBackdropSourceKind.GENERATED to null
        else -> LegacyBackdropSourceKind.IMAGE to descriptor.previewFilePath
    }
    val pathSignature = path?.let(::legacyPathSignature)
        ?: "generated:${if (builtInMaterialStyle) "material" else "watch"}"
    return LegacyBackdropSource(
        key = "${descriptor.stableKey}|$kind|$pathSignature|$refreshToken|$fallbackArgb|$visualKey",
        kind = kind,
        path = path,
        fallbackArgb = fallbackArgb,
        generatedMaterialStyle = builtInMaterialStyle,
    )
}

private fun legacyPathSignature(path: String): String {
    val file = File(path)
    return if (file.isFile) {
        "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    } else {
        path
    }
}

private fun prepareLegacyBackdropFrames(
    context: Context,
    source: LegacyBackdropSource,
    targetWidth: Int,
    targetHeight: Int,
    capturedBitmap: Bitmap? = null,
): LegacyWatchFaceBackdropFrames? {
    val capturedCopy = capturedBitmap?.let { bitmap ->
        synchronized(bitmap) {
            if (bitmap.isRecycled) null else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
    }
    val decoded = capturedCopy ?: when (source.kind) {
        LegacyBackdropSourceKind.IMAGE -> source.path?.let {
            decodeSampledBitmap(context, it, targetWidth, targetHeight)
        }
        LegacyBackdropSourceKind.VIDEO -> source.path?.let {
            extractVideoFrame(context, it)
        }
        LegacyBackdropSourceKind.GENERATED -> null
    }
    val viewportBitmap = if (decoded != null) {
        centerCropOpaque(decoded, targetWidth, targetHeight, source.fallbackArgb)
    } else {
        createGeneratedWatchFaceCover(
            width = targetWidth,
            height = targetHeight,
            fallbackArgb = source.fallbackArgb,
            materialStyle = source.generatedMaterialStyle,
        )
    }
    if (decoded != null && decoded !== viewportBitmap && !decoded.isRecycled) {
        decoded.recycle()
    }

    // About 22% mirrored overscan covers the largest shared overlay geometry
    // (0.85 scale + 0.07h downward translation). It is baked once, before the
    // blur, so convolution never samples transparent/black pixels at an edge.
    val overscanX = (targetWidth * 0.22f).toInt().coerceAtLeast(1)
    val overscanY = (targetHeight * 0.22f).toInt().coerceAtLeast(1)
    val clearBitmap = createMirroredOverscan(viewportBitmap, overscanX, overscanY)
    if (viewportBitmap !== clearBitmap && !viewportBitmap.isRecycled) viewportBitmap.recycle()

    val stronglyBlurred = createStrongBlur(clearBitmap)
    val lightBlurred = tintBitmap(stronglyBlurred, android.graphics.Color.WHITE, 0.12f)
    val darkBlurred = tintBitmap(stronglyBlurred, android.graphics.Color.BLACK, 0.28f)
    if (stronglyBlurred !== lightBlurred && stronglyBlurred !== darkBlurred && !stronglyBlurred.isRecycled) {
        stronglyBlurred.recycle()
    }
    val averageArgb = averageOpaqueColor(darkBlurred)
    return LegacyWatchFaceBackdropFrames(
        clear = clearBitmap.asImageBitmap(),
        lightBlurred = lightBlurred.asImageBitmap(),
        darkBlurred = darkBlurred.asImageBitmap(),
        viewportWidth = targetWidth,
        viewportHeight = targetHeight,
        overscanX = overscanX,
        overscanY = overscanY,
        fallbackArgb = averageArgb,
        allocationBytes = clearBitmap.allocationByteCount +
            lightBlurred.allocationByteCount +
            darkBlurred.allocationByteCount,
    )
}

private fun createMirroredOverscan(source: Bitmap, overscanX: Int, overscanY: Int): Bitmap {
    val output = Bitmap.createBitmap(
        source.width + overscanX * 2,
        source.height + overscanY * 2,
        Bitmap.Config.ARGB_8888,
    )
    val bitmapShader = BitmapShader(source, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
        setLocalMatrix(Matrix().apply { setTranslate(overscanX.toFloat(), overscanY.toFloat()) })
    }
    Canvas(output).drawRect(
        0f,
        0f,
        output.width.toFloat(),
        output.height.toFloat(),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = bitmapShader },
    )
    return output
}

private fun decodeSampledBitmap(
    context: Context,
    path: String,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap? {
    val uri = path.takeIf { it.startsWith("content://") }?.let(Uri::parse)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (uri != null) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        }
    } else {
        BitmapFactory.decodeFile(path, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        bounds.outWidth / (sampleSize * 2) >= targetWidth &&
        bounds.outHeight / (sampleSize * 2) >= targetHeight
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return if (uri != null) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull()
    } else {
        BitmapFactory.decodeFile(path, options)
    }
}

private fun extractVideoFrame(context: Context, path: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (path.startsWith("content://")) {
            retriever.setDataSource(context, Uri.parse(path))
        } else {
            retriever.setDataSource(path)
        }
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun centerCropOpaque(
    source: Bitmap,
    targetWidth: Int,
    targetHeight: Int,
    fallbackArgb: Int,
): Bitmap {
    val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(fallbackArgb)
    val scale = max(
        targetWidth.toFloat() / source.width.coerceAtLeast(1),
        targetHeight.toFloat() / source.height.coerceAtLeast(1),
    )
    val drawnWidth = source.width * scale
    val drawnHeight = source.height * scale
    val destination = RectF(
        (targetWidth - drawnWidth) / 2f,
        (targetHeight - drawnHeight) / 2f,
        (targetWidth + drawnWidth) / 2f,
        (targetHeight + drawnHeight) / 2f,
    )
    canvas.drawBitmap(
        source,
        null,
        destination,
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
    )
    return output
}

private fun createGeneratedWatchFaceCover(
    width: Int,
    height: Int,
    fallbackArgb: Int,
    materialStyle: Boolean,
): Bitmap {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(fallbackArgb)
    val radius = max(width, height) * if (materialStyle) 1.25f else 0.92f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            width * 0.50f,
            if (materialStyle) height * 0.18f else height * 0.50f,
            radius,
            if (materialStyle) {
                intArrayOf(
                    android.graphics.Color.rgb(46, 73, 101),
                    android.graphics.Color.rgb(31, 49, 68),
                    fallbackArgb,
                )
            } else {
                intArrayOf(
                    android.graphics.Color.rgb(22, 48, 74),
                    android.graphics.Color.rgb(11, 19, 34),
                    android.graphics.Color.BLACK,
                )
            },
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

    // The default built-in face has no preview file. Render its dominant live
    // feature into the generated cover so the pre-blurred frame is derived from
    // the actual face rather than an unrelated placeholder gradient.
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = android.graphics.Color.WHITE
        alpha = 235
        textAlign = Paint.Align.CENTER
        textSize = width.coerceAtMost(height) * 0.18f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
    }.also { clockPaint ->
        val baseline = height / 2f - (clockPaint.descent() + clockPaint.ascent()) / 2f
        canvas.drawText(clock, width / 2f, baseline, clockPaint)
    }
    return output
}

/**
 * 先缩到约 1/10，再做三轮可分离 box blur，最后双线性放回屏幕尺寸。
 * 小图半径 6px 约等于原图 60px 以上，明显强于旧版 18dp 八方向叠图，且只
 * 在缓存生成时执行一次。
 */
private fun createStrongBlur(source: Bitmap): Bitmap {
    val smallWidth = (source.width / LEGACY_BACKDROP_BLUR_DOWNSAMPLE_DIVISOR).coerceAtLeast(28)
    val smallHeight = (source.height / LEGACY_BACKDROP_BLUR_DOWNSAMPLE_DIVISOR).coerceAtLeast(28)
    val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
    val pixels = IntArray(smallWidth * smallHeight)
    val scratch = IntArray(pixels.size)
    small.getPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
    // Previous policy used four radius-7 passes. Three radius-6 passes lower
    // the effective blur spread by roughly one fifth while retaining enough
    // diffusion to hide mirrored seams on API 27-30.
    repeat(LEGACY_BACKDROP_BLUR_PASSES) {
        boxBlurHorizontal(
            pixels,
            scratch,
            smallWidth,
            smallHeight,
            radius = LEGACY_BACKDROP_BLUR_RADIUS,
        )
        boxBlurVertical(
            scratch,
            pixels,
            smallWidth,
            smallHeight,
            radius = LEGACY_BACKDROP_BLUR_RADIUS,
        )
    }
    small.setPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
    val enlarged = Bitmap.createScaledBitmap(small, source.width, source.height, true)
    if (small !== enlarged && !small.isRecycled) small.recycle()
    return enlarged
}

private fun boxBlurHorizontal(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val diameter = radius * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = input[row + offset.coerceIn(0, width - 1)]
            red += color shr 16 and 0xFF
            green += color shr 8 and 0xFF
            blue += color and 0xFF
        }
        for (x in 0 until width) {
            output[row + x] = android.graphics.Color.rgb(red / diameter, green / diameter, blue / diameter)
            val outgoing = input[row + (x - radius).coerceIn(0, width - 1)]
            val incoming = input[row + (x + radius + 1).coerceIn(0, width - 1)]
            red += (incoming shr 16 and 0xFF) - (outgoing shr 16 and 0xFF)
            green += (incoming shr 8 and 0xFF) - (outgoing shr 8 and 0xFF)
            blue += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }
}

private fun boxBlurVertical(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val diameter = radius * 2 + 1
    for (x in 0 until width) {
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = input[offset.coerceIn(0, height - 1) * width + x]
            red += color shr 16 and 0xFF
            green += color shr 8 and 0xFF
            blue += color and 0xFF
        }
        for (y in 0 until height) {
            output[y * width + x] = android.graphics.Color.rgb(red / diameter, green / diameter, blue / diameter)
            val outgoing = input[(y - radius).coerceIn(0, height - 1) * width + x]
            val incoming = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            red += (incoming shr 16 and 0xFF) - (outgoing shr 16 and 0xFF)
            green += (incoming shr 8 and 0xFF) - (outgoing shr 8 and 0xFF)
            blue += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }
}

private fun tintBitmap(source: Bitmap, overlayColor: Int, alpha: Float): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val paint = Paint().apply {
        color = overlayColor
        this.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
    }
    Canvas(output).drawRect(0f, 0f, output.width.toFloat(), output.height.toFloat(), paint)
    return output
}

private fun averageOpaqueColor(bitmap: Bitmap): Int {
    val sampleWidth = 8
    val sampleHeight = 8
    val matrix = Matrix().apply {
        setScale(sampleWidth.toFloat() / bitmap.width, sampleHeight.toFloat() / bitmap.height)
    }
    val sample = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    val pixels = IntArray(sampleWidth * sampleHeight)
    sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
    if (sample !== bitmap && !sample.isRecycled) sample.recycle()
    var red = 0L
    var green = 0L
    var blue = 0L
    pixels.forEach { color ->
        red += color shr 16 and 0xFF
        green += color shr 8 and 0xFF
        blue += color and 0xFF
    }
    val count = pixels.size.coerceAtLeast(1)
    return android.graphics.Color.rgb(
        (red / count).toInt(),
        (green / count).toInt(),
        (blue / count).toInt(),
    )
}
