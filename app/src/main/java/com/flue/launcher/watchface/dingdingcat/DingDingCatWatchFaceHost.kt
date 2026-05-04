package com.flue.launcher.watchface.dingdingcat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Composable
fun DingDingCatWatchFaceHost(
    descriptor: LunchWatchFaceDescriptor,
    isFaceVisible: Boolean,
    refreshToken: Int,
    fillScreen: Boolean = false,
    onLoadFailure: (LunchWatchFaceDescriptor, Throwable) -> Unit,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var loadError by remember(descriptor.stableKey, refreshToken) { mutableStateOf<Throwable?>(null) }
    val dial by produceState<DingDingCatDial?>(initialValue = null, descriptor.stableKey, refreshToken) {
        value = null
        loadError = null
        val sourceDir = descriptor.sourceDirPath?.let(::File)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                require(sourceDir != null && sourceDir.isDirectory) { "叮叮猫表盘目录不存在" }
                DingDingCatDialParser.parse(sourceDir)
            }
        }
        result.onSuccess { value = it }.onFailure { loadError = it }
    }
    LaunchedEffect(loadError) {
        loadError?.let { onLoadFailure(descriptor, it) }
    }

    val batteryLevel = rememberDingDingCatBatteryLevel(context)
    val triggeredAnimations = remember(descriptor.stableKey, refreshToken) { mutableStateMapOf<Int, Long>() }
    var gravityDragOffset by remember(descriptor.stableKey, refreshToken) { mutableStateOf(Offset.Zero) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val hasAnimatedLayers = dial?.drawPoints?.any { it.pieces.size > 1 } == true
    val hasGravityLayers = dial?.drawPoints?.any { it.pointType == "PICTRUE_EFFECT_GRAVITY" } == true
    val tiltOffset = rememberDingDingCatTiltOffset(context, isFaceVisible && hasGravityLayers)
    LaunchedEffect(isFaceVisible, hasAnimatedLayers, descriptor.stableKey) {
        while (isFaceVisible) {
            nowMillis = System.currentTimeMillis()
            delay(if (hasAnimatedLayers) 66L else 1_000L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (hasGravityLayers) {
                    Modifier.pointerInput(descriptor.stableKey, refreshToken) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            gravityDragOffset += dragAmount
                        }
                    }
                } else {
                    Modifier
                }
            )
            .then(
                if (onLongPress != null || onDoubleTap != null) {
                    Modifier.pointerInput(onLongPress, onDoubleTap, dial) {
                        detectTapGestures(
                            onTap = {
                                dial?.drawPoints
                                    ?.filter(::isTapTriggeredAnimation)
                                    ?.forEach { point -> triggeredAnimations[point.sourceIndex] = System.currentTimeMillis() }
                            },
                            onLongPress = onLongPress?.let { handler -> { _ -> handler() } },
                            onDoubleTap = onDoubleTap?.let { handler -> { _ -> handler() } }
                        )
                    }
                } else {
                    Modifier.pointerInput(dial) {
                        detectTapGestures(
                            onTap = {
                                dial?.drawPoints
                                    ?.filter(::isTapTriggeredAnimation)
                                    ?.forEach { point -> triggeredAnimations[point.sourceIndex] = System.currentTimeMillis() }
                            }
                        )
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val activeDial = dial
        if (activeDial == null && loadError == null) {
            Text("Loading", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
        } else if (activeDial != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val designWidth = activeDial.width.toFloat().coerceAtLeast(1f)
                val designHeight = activeDial.height.toFloat().coerceAtLeast(1f)
                val fitScale = min(size.width / designWidth, size.height / designHeight)
                val offsetX = (size.width - designWidth * fitScale) / 2f
                val offsetY = (size.height - designHeight * fitScale) / 2f
                val screenViewportInDesign = RectF(
                    -offsetX / fitScale,
                    -offsetY / fitScale,
                    (size.width - offsetX) / fitScale,
                    (size.height - offsetY) / fitScale
                )
                val gravityOffset = Offset(
                    x = tiltOffset.x * designWidth * 0.08f + gravityDragOffset.x / fitScale,
                    y = tiltOffset.y * designHeight * 0.08f + gravityDragOffset.y / fitScale
                )
                drawIntoCanvas { canvas ->
                    val nativeCanvas = canvas.nativeCanvas
                    nativeCanvas.save()
                    nativeCanvas.translate(offsetX, offsetY)
                    nativeCanvas.scale(fitScale, fitScale)
                    activeDial.drawPoints.forEach { point ->
                        if (point.visible) {
                            drawDingDingCatPoint(
                                canvas = nativeCanvas,
                                dial = activeDial,
                                point = point,
                                nowMillis = nowMillis,
                                batteryLevel = batteryLevel,
                                triggeredAnimations = triggeredAnimations,
                                gravityOffset = gravityOffset,
                                fillScreenBackgroundViewport = if (fillScreen) screenViewportInDesign else null
                            )
                        }
                    }
                    nativeCanvas.restore()
                }
            }
        }
    }
}

@Composable
private fun rememberDingDingCatBatteryLevel(context: Context): Int {
    var level by remember { mutableStateOf(100) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                level = readBatteryLevel(intent) ?: level
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        context.registerReceiver(null, filter)?.let { intent ->
            level = readBatteryLevel(intent) ?: level
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return level
}

private fun readBatteryLevel(intent: Intent): Int? {
    val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (rawLevel < 0 || scale <= 0) return null
    return (rawLevel * 100 / scale).coerceIn(0, 100)
}

@Composable
private fun rememberDingDingCatTiltOffset(context: Context, enabled: Boolean): Offset {
    var offset by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context, enabled) {
        if (!enabled) {
            offset = Offset.Zero
            return@DisposableEffect onDispose { }
        }
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensorManager == null || accelerometer == null) {
            offset = Offset.Zero
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            private var smoothX = 0f
            private var smoothY = 0f

            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                val targetX = (-values.getOrElse(0) { 0f } / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                val targetY = (values.getOrElse(1) { 0f } / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                smoothX += (targetX - smoothX) * 0.18f
                smoothY += (targetY - smoothY) * 0.18f
                offset = Offset(smoothX, smoothY)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return offset
}

private fun drawDingDingCatPoint(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    nowMillis: Long,
    batteryLevel: Int,
    triggeredAnimations: Map<Int, Long>,
    gravityOffset: Offset,
    fillScreenBackgroundViewport: RectF?
) {
    when (point.pointType) {
        "PICTURE_LOOP", "PICTRUE_EFFECT" -> drawImageLayer(
            canvas,
            dial,
            point,
            nowMillis,
            triggeredAnimations[point.sourceIndex],
            fillScreenBackgroundViewport
        )
        "PICTRUE_EFFECT_GRAVITY" -> drawGravityLayer(canvas, dial, point, gravityOffset)
        "PICTURE_NUM_TIME_HHMM" -> drawNumberStrip(canvas, dial, point, formatTime("HH:mm", nowMillis))
        "PICTURE_NUM_TIME_HHMMSS" -> drawNumberStrip(canvas, dial, point, formatTime("HH:mm:ss", nowMillis))
        "PICTURE_NUM_TIME_HH" -> drawNumberStrip(canvas, dial, point, formatTime("HH", nowMillis))
        "PICTURE_NUM_TIME_MINUTE" -> drawNumberStrip(canvas, dial, point, formatTime("mm", nowMillis))
        "PICTURE_NUM_CHARGE" -> drawNumberStrip(canvas, dial, point, "$batteryLevel%")
        "PICTURE_AMPM" -> drawAmPm(canvas, dial, point, nowMillis)
        "PICTURE_NUM_WEEK" -> drawWeek(canvas, dial, point, nowMillis)
        "TEXT_TIME_HHMMSS" -> drawTextLayer(canvas, dial, point, formatTime("HH:mm:ss", nowMillis))
        "TEXT_DATE" -> drawTextLayer(canvas, dial, point, formatDate(nowMillis))
    }
}

private fun drawImageLayer(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    nowMillis: Long,
    triggeredAt: Long?,
    fillScreenBackgroundViewport: RectF?
) {
    val piece = selectAnimatedPiece(point, nowMillis, triggeredAt) ?: return
    val displayScale = if (
        point.pointType == "PICTURE_LOOP" &&
        point.width >= dial.width * 0.8f &&
        point.height >= dial.height * 0.8f
    ) {
        1f
    } else {
        point.scale
    }
    val targetWidth = point.width.takeIf { it > 0f } ?: dial.width.toFloat()
    val targetHeight = point.height.takeIf { it > 0f } ?: dial.height.toFloat()
    val bitmap = DingDingCatBitmapCache.get(
        file = File(dial.rootDir, piece),
        allowSample = true,
        targetWidth = (targetWidth * displayScale).toInt(),
        targetHeight = (targetHeight * displayScale).toInt()
    ) ?: return
    val dst = if (fillScreenBackgroundViewport != null && isDingDingCatBackgroundLayer(point, dial)) {
        centerCropDestination(
            viewport = fillScreenBackgroundViewport,
            contentWidth = targetWidth * displayScale,
            contentHeight = targetHeight * displayScale
        )
    } else {
        RectF(
            point.startX,
            point.startY,
            point.startX + targetWidth * displayScale,
            point.startY + targetHeight * displayScale
        )
    }
    val paint = pointPaint(point)
    canvas.drawBitmap(bitmap, null, dst, paint)
}

private fun drawGravityLayer(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    gravityOffset: Offset
) {
    val piece = point.pieces.firstOrNull() ?: return
    val bitmap = DingDingCatBitmapCache.get(File(dial.rootDir, piece), allowSample = true) ?: return
    val area = RectF(
        if (point.hasStartX) point.startX else 0f,
        if (point.hasStartY) point.startY else 0f,
        (if (point.hasStartX) point.startX else 0f) + (point.width.takeIf { it > 0f } ?: dial.width.toFloat()),
        (if (point.hasStartY) point.startY else 0f) + (point.height.takeIf { it > 0f } ?: dial.height.toFloat())
    )
    val dstWidth = bitmap.width * point.scale
    val dstHeight = bitmap.height * point.scale
    val centerX = coerceDingDingCatCenter(
        value = area.centerX() + gravityOffset.x,
        minimum = area.left + dstWidth * 0.25f,
        maximum = area.right - dstWidth * 0.25f
    )
    val centerY = coerceDingDingCatCenter(
        value = area.centerY() + gravityOffset.y,
        minimum = area.top + dstHeight * 0.25f,
        maximum = area.bottom - dstHeight * 0.25f
    )
    canvas.drawBitmap(
        bitmap,
        null,
        RectF(
            centerX - dstWidth / 2f,
            centerY - dstHeight / 2f,
            centerX + dstWidth / 2f,
            centerY + dstHeight / 2f
        ),
        pointPaint(point)
    )
}

private fun drawNumberStrip(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    text: String
) {
    val strip = point.pieces.firstOrNull()?.let { File(dial.rootDir, it) } ?: return
    val bitmap = DingDingCatBitmapCache.get(strip, allowSample = false) ?: return
    val sliceWidth = point.splitWidth.takeIf { it > 0f } ?: return
    val renderScale = point.scale * (
        point.height
            .takeIf { it > 0f }
            ?.let { it / bitmap.height.toFloat() }
            ?: 1f
        )
    val dstHeight = bitmap.height * renderScale
    val dstWidth = sliceWidth * renderScale
    val chars = text.mapNotNull { char -> dingDingCatCharIndex(char)?.let { char to it } }
    if (chars.isEmpty()) return
    val totalWidth = chars.size * dstWidth + (chars.size - 1).coerceAtLeast(0) * point.splitSpace
    val area = RectF(
        point.startX,
        point.startY,
        point.startX + (point.width.takeIf { it > 0f } ?: totalWidth),
        point.startY + (point.height.takeIf { it > 0f } ?: dstHeight)
    )
    val paint = pointPaint(point)
    var cursorX = when (point.gravity) {
        17 -> area.centerX() - totalWidth / 2f
        5, 8388613 -> area.right - totalWidth
        else -> area.left
    }
    val top = when (point.gravity) {
        17 -> area.centerY() - dstHeight / 2f
        80 -> area.bottom - dstHeight
        else -> area.top
    }
    chars.forEach { (_, index) ->
        val srcLeft = (index * sliceWidth).toInt().coerceAtMost(bitmap.width)
        val srcRight = ((index + 1) * sliceWidth).toInt().coerceAtMost(bitmap.width)
        if (srcRight <= srcLeft) return@forEach
        canvas.drawBitmap(
            bitmap,
            Rect(srcLeft, 0, srcRight, bitmap.height),
            RectF(cursorX, top, cursorX + dstWidth, top + dstHeight),
            paint
        )
        cursorX += dstWidth + point.splitSpace
    }
}

private fun drawAmPm(canvas: AndroidCanvas, dial: DingDingCatDial, point: DingDingCatDrawPoint, nowMillis: Long) {
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val index = if (calendar.get(Calendar.AM_PM) == Calendar.PM) 1 else 0
    val piece = point.pieces.getOrNull(index) ?: point.pieces.firstOrNull() ?: return
    val bitmap = DingDingCatBitmapCache.get(File(dial.rootDir, piece), allowSample = true) ?: return
    val width = (point.width.takeIf { it > 0f } ?: bitmap.width.toFloat()) * point.scale
    val height = (point.height.takeIf { it > 0f } ?: bitmap.height.toFloat()) * point.scale
    canvas.drawBitmap(
        bitmap,
        null,
        RectF(point.startX, point.startY, point.startX + width, point.startY + height),
        pointPaint(point)
    )
}

private fun drawWeek(canvas: AndroidCanvas, dial: DingDingCatDial, point: DingDingCatDrawPoint, nowMillis: Long) {
    val strip = point.pieces.firstOrNull()?.let { File(dial.rootDir, it) } ?: return
    val bitmap = DingDingCatBitmapCache.get(strip, allowSample = false) ?: return
    val sliceWidth = point.splitWidth.takeIf { it > 0f } ?: return
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val index = (calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
    val srcLeft = (index * sliceWidth).toInt().coerceAtMost(bitmap.width)
    val srcRight = ((index + 1) * sliceWidth).toInt().coerceAtMost(bitmap.width)
    if (srcRight <= srcLeft) return
    val dstWidth = sliceWidth * point.scale
    val dstHeight = bitmap.height * point.scale
    canvas.drawBitmap(
        bitmap,
        Rect(srcLeft, 0, srcRight, bitmap.height),
        RectF(point.startX, point.startY, point.startX + dstWidth, point.startY + dstHeight),
        pointPaint(point)
    )
}

private fun drawTextLayer(canvas: AndroidCanvas, dial: DingDingCatDial, point: DingDingCatDrawPoint, text: String) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = point.font.color
        textSize = point.font.textSize * point.scale
        textAlign = if (point.gravity == 17) Paint.Align.CENTER else Paint.Align.LEFT
        typeface = point.font.fontType
            ?.let { File(dial.rootDir, it) }
            ?.let(DingDingCatTypefaceCache::get)
            ?: Typeface.DEFAULT
        alpha = pointAlpha(point)
    }
    val area = RectF(
        point.startX,
        point.startY,
        point.startX + (point.width.takeIf { it > 0f } ?: dial.width.toFloat()),
        point.startY + (point.height.takeIf { it > 0f } ?: dial.height.toFloat())
    )
    val x = if (point.gravity == 17) area.centerX() else area.left
    val y = area.centerY() - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(text, x, y, paint)
}

private fun selectAnimatedPiece(point: DingDingCatDrawPoint, nowMillis: Long, triggeredAt: Long?): String? {
    if (point.pieces.isEmpty()) return null
    if (point.pieces.size == 1) return point.pieces.first()
    if (isTapTriggeredAnimation(point)) {
        val start = triggeredAt ?: return point.pieces.first()
        val durationMs = point.animator
            ?.totalDurationMs
            ?.takeIf { it > 0 }
            ?: (point.pieces.size * point.durationMs).coerceAtLeast(point.pieces.size * 16)
        val elapsed = nowMillis - start
        if (elapsed >= durationMs) {
            return when {
                point.animator?.endWithStatusGone == true -> null
                point.animator?.animEndSaveData == true -> point.pieces.last()
                else -> point.pieces.first()
            }
        }
        val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 0.999f)
        return point.pieces[(progress * point.pieces.size).toInt().coerceIn(0, point.pieces.lastIndex)]
    }
    val frameMs = max(point.durationMs.toLong(), 66L)
    val index = ((nowMillis / frameMs) % point.pieces.size).toInt()
    return point.pieces[index]
}

private fun isTapTriggeredAnimation(point: DingDingCatDrawPoint): Boolean {
    val animator = point.animator ?: return false
    return point.pieces.size > 1 &&
        animator.triggerType == 1024 &&
        !animator.startWithStatusPlay
}

private fun isDingDingCatBackgroundLayer(point: DingDingCatDrawPoint, dial: DingDingCatDial): Boolean {
    return point.pointType == "PICTURE_LOOP" &&
        point.width >= dial.width * 0.8f &&
        point.height >= dial.height * 0.8f &&
        !point.hasStartX &&
        !point.hasStartY
}

private fun centerCropDestination(viewport: RectF, contentWidth: Float, contentHeight: Float): RectF {
    if (contentWidth <= 0f || contentHeight <= 0f) return viewport
    val scale = max(viewport.width() / contentWidth, viewport.height() / contentHeight)
    val width = contentWidth * scale
    val height = contentHeight * scale
    val centerX = viewport.centerX()
    val centerY = viewport.centerY()
    return RectF(
        centerX - width / 2f,
        centerY - height / 2f,
        centerX + width / 2f,
        centerY + height / 2f
    )
}

private fun coerceDingDingCatCenter(value: Float, minimum: Float, maximum: Float): Float {
    return if (minimum <= maximum) value.coerceIn(minimum, maximum) else (minimum + maximum) / 2f
}

private fun pointPaint(point: DingDingCatDrawPoint): Paint {
    return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = pointAlpha(point)
        if (point.tint != -1) {
            colorFilter = PorterDuffColorFilter(point.tint, PorterDuff.Mode.SRC_IN)
        }
    }
}

private fun pointAlpha(point: DingDingCatDrawPoint): Int {
    return if (point.alpha in 0..255) point.alpha else 255
}

private fun dingDingCatCharIndex(char: Char): Int? {
    return when (char) {
        in '0'..'9' -> char - '0'
        ',' -> 10
        '.' -> 11
        ':' -> 12
        '%' -> 13
        '/' -> 14
        '-' -> 15
        else -> null
    }
}

private fun formatTime(pattern: String, nowMillis: Long): String {
    return SimpleDateFormat(pattern, Locale.getDefault()).format(nowMillis)
}

private fun formatDate(nowMillis: Long): String {
    return SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(nowMillis)
}

private object DingDingCatBitmapCache {
    private const val MAX_CACHE_KB = 24 * 1024
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(file: File, allowSample: Boolean, targetWidth: Int = 0, targetHeight: Int = 0): Bitmap? {
        if (!file.isFile) return null
        val sampleSize = if (allowSample) calculateSampleSize(file, targetWidth, targetHeight) else 1
        val key = "${file.absolutePath}:${file.lastModified()}:$sampleSize"
        cache.get(key)?.let { return it }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun calculateSampleSize(file: File, targetWidth: Int, targetHeight: Int): Int {
        if (targetWidth <= 0 || targetHeight <= 0) return 1
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= targetWidth * 2 || sourceHeight <= targetHeight * 2) return 1
        var sampleSize = 1
        while (sourceWidth / (sampleSize * 2) >= targetWidth && sourceHeight / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }
}

private object DingDingCatTypefaceCache {
    private val cache = mutableMapOf<String, Typeface?>()

    fun get(file: File): Typeface? {
        if (!file.isFile) return null
        val key = "${file.absolutePath}:${file.lastModified()}"
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
            val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull()
            cache[key] = typeface
            return typeface
        }
    }
}
