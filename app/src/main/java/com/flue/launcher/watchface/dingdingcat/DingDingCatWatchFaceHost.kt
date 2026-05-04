package com.flue.launcher.watchface.dingdingcat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    val stepCount = rememberDingDingCatStepCount(context)
    val triggeredAnimations = remember(descriptor.stableKey, refreshToken) { mutableStateMapOf<Int, Long>() }
    val gravityBodies = remember(descriptor.stableKey, refreshToken) { mutableStateMapOf<Int, DingDingCatGravityBody>() }
    var draggingGravitySource by remember(descriptor.stableKey, refreshToken) { mutableStateOf<Int?>(null) }
    var canvasSize by remember(descriptor.stableKey, refreshToken) { mutableStateOf(IntSize.Zero) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val activeDial = dial
    val gravitySpecs = remember(activeDial, refreshToken) { activeDial?.gravitySpecs().orEmpty() }
    val hasAnimatedLayers = activeDial?.drawPoints?.any { it.pieces.size > 1 } == true
    val hasCompassLayers = activeDial?.drawPoints?.any { it.pointType == "PICTURE_COMPASS" } == true
    val animationFrameDelayMs = remember(activeDial) { activeDial?.animationFrameDelayMs() ?: 1_000L }
    val hasGravityLayers = gravitySpecs.isNotEmpty()
    val tiltOffset = rememberDingDingCatTiltOffset(context, isFaceVisible && hasGravityLayers)
    val compassAzimuth = rememberDingDingCatCompassAzimuth(context, isFaceVisible && hasCompassLayers)
    val currentTilt by rememberUpdatedState(tiltOffset)
    val currentDraggingGravitySource by rememberUpdatedState(draggingGravitySource)
    val renderTransform = remember(activeDial, canvasSize) {
        activeDial?.let { dialValue -> dingDingCatRenderTransform(dialValue, canvasSize) }
    }
    LaunchedEffect(gravitySpecs) {
        syncDingDingCatGravityBodies(gravityBodies, gravitySpecs)
    }
    LaunchedEffect(isFaceVisible, hasGravityLayers, descriptor.stableKey, refreshToken, gravitySpecs) {
        if (!isFaceVisible || !hasGravityLayers) return@LaunchedEffect
        syncDingDingCatGravityBodies(gravityBodies, gravitySpecs)
        var lastFrameNanos = withFrameNanos { it }
        while (isFaceVisible) {
            val frameNanos = withFrameNanos { it }
            val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameNanos
            stepDingDingCatGravityBodies(
                bodies = gravityBodies,
                specs = gravitySpecs,
                tilt = currentTilt,
                draggingSource = currentDraggingGravitySource,
                dt = dt
            )
        }
    }
    LaunchedEffect(isFaceVisible, hasAnimatedLayers, animationFrameDelayMs, descriptor.stableKey) {
        while (isFaceVisible) {
            nowMillis = System.currentTimeMillis()
            delay(if (hasAnimatedLayers) animationFrameDelayMs else 1_000L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .background(Color.Black)
            .then(
                if (hasGravityLayers) {
                    Modifier.pointerInput(descriptor.stableKey, refreshToken, gravitySpecs, renderTransform) {
                        var lastDragAt = 0L
                        detectDragGestures(
                            onDragStart = { screenPoint ->
                                lastDragAt = SystemClock.uptimeMillis()
                                val transform = renderTransform
                                val designPoint = transform?.screenToDesign(screenPoint) ?: screenPoint
                                draggingGravitySource = findNearestDingDingCatGravitySource(
                                    point = designPoint,
                                    specs = gravitySpecs,
                                    bodies = gravityBodies
                                )
                            },
                            onDragEnd = { draggingGravitySource = null },
                            onDragCancel = { draggingGravitySource = null }
                        ) { change, dragAmount ->
                            val source = draggingGravitySource ?: return@detectDragGestures
                            change.consume()
                            val spec = gravitySpecs.firstOrNull { it.sourceIndex == source }
                            if (source != null && spec != null) {
                                val transform = renderTransform
                                val designDelta = transform?.screenDeltaToDesign(dragAmount) ?: dragAmount
                                val now = SystemClock.uptimeMillis()
                                val dt = ((now - lastDragAt).coerceAtLeast(1L) / 1_000f).coerceAtMost(0.05f)
                                lastDragAt = now
                                val body = gravityBodies[source] ?: DingDingCatGravityBody(spec.defaultCenter, Offset.Zero)
                                val nextCenter = coerceDingDingCatGravityCenter(body.center + designDelta, spec)
                                gravityBodies[source] = DingDingCatGravityBody(
                                    center = nextCenter,
                                    velocity = offsetScale(nextCenter - body.center, 1f / dt)
                                )
                            }
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
                            onTap = { screenPoint ->
                                handleDingDingCatTap(
                                    context = context,
                                    dial = dial,
                                    renderTransform = renderTransform,
                                    screenPoint = screenPoint,
                                    triggeredAnimations = triggeredAnimations
                                )
                            },
                            onLongPress = onLongPress?.let { handler -> { _ -> handler() } },
                            onDoubleTap = onDoubleTap?.let { handler -> { _ -> handler() } }
                        )
                    }
                } else {
                    Modifier.pointerInput(dial) {
                        detectTapGestures(
                            onTap = { screenPoint ->
                                handleDingDingCatTap(
                                    context = context,
                                    dial = dial,
                                    renderTransform = renderTransform,
                                    screenPoint = screenPoint,
                                    triggeredAnimations = triggeredAnimations
                                )
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
                                stepCount = stepCount,
                                compassAzimuthDegrees = compassAzimuth,
                                triggeredAnimations = triggeredAnimations,
                                gravityBodies = gravityBodies,
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

@Composable
private fun rememberDingDingCatStepCount(context: Context): Int? {
    var steps by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            steps = null
            return@DisposableEffect onDispose { }
        }
        val sensorManager = ContextCompat.getSystemService(context, SensorManager::class.java)
        val stepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensorManager == null || stepCounter == null) {
            steps = null
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                steps = event?.values?.firstOrNull()?.toInt()?.coerceAtLeast(0)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return steps
}

private fun readBatteryLevel(intent: Intent): Int? {
    val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (rawLevel < 0 || scale <= 0) return null
    return (rawLevel * 100 / scale).coerceIn(0, 100)
}

@Composable
private fun rememberDingDingCatCompassAzimuth(context: Context, enabled: Boolean): Float {
    var azimuth by remember { mutableStateOf(0f) }
    DisposableEffect(context, enabled) {
        if (!enabled) {
            azimuth = 0f
            return@DisposableEffect onDispose { }
        }
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensorManager == null || rotationSensor == null) {
            azimuth = 0f
            return@DisposableEffect onDispose { }
        }
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val target = normalizeDegrees(Math.toDegrees(orientation[0].toDouble()).toFloat())
                val delta = shortestDegreeDelta(azimuth, target)
                azimuth = normalizeDegrees(azimuth + delta * 0.22f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return azimuth
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

private fun handleDingDingCatTap(
    context: Context,
    dial: DingDingCatDial?,
    renderTransform: DingDingCatRenderTransform?,
    screenPoint: Offset,
    triggeredAnimations: MutableMap<Int, Long>
) {
    val activeDial = dial ?: return
    val designPoint = renderTransform?.screenToDesign(screenPoint) ?: screenPoint
    activeDial.drawPoints
        .asReversed()
        .firstOrNull { point ->
            point.visible &&
                !point.launchAction.isNullOrBlank() &&
                point.containsDesignPoint(activeDial, designPoint)
        }
        ?.launchAction
        ?.let { action -> launchDingDingCatAction(context, action) }

    val now = System.currentTimeMillis()
    activeDial.drawPoints
        .filter(::isTapTriggeredAnimation)
        .forEach { point -> triggeredAnimations[point.sourceIndex] = now }
}

private fun launchDingDingCatAction(context: Context, action: String): Boolean {
    val packageManager = context.packageManager
    val packageIntent = packageManager.getLaunchIntentForPackage(action)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launchedByPackage = packageIntent?.let { intent ->
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    } == true
    if (launchedByPackage) return true

    val actionIntent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        if (actionIntent.resolveActivity(packageManager) == null) {
            false
        } else {
            context.startActivity(actionIntent)
            true
        }
    }.getOrDefault(false)
}

private fun drawDingDingCatPoint(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    nowMillis: Long,
    batteryLevel: Int,
    stepCount: Int?,
    compassAzimuthDegrees: Float,
    triggeredAnimations: Map<Int, Long>,
    gravityBodies: Map<Int, DingDingCatGravityBody>,
    fillScreenBackgroundViewport: RectF?
) {
    when (point.pointType) {
        "PICTURE_LOOP", "PICTRUE_EFFECT", "PICTURE_NORMAL", "PIICTURE_NORMAL", "PICTURE_COMPASS" -> drawImageLayer(
            canvas,
            dial,
            point,
            nowMillis,
            compassAzimuthDegrees,
            triggeredAnimations[point.sourceIndex],
            fillScreenBackgroundViewport
        )
        "PICTURE_CHARGE" -> drawProgressImageLayer(canvas, dial, point, batteryLevel, fillScreenBackgroundViewport)
        "PICTURE_STEPS" -> drawProgressImageLayer(
            canvas,
            dial,
            point,
            stepCount?.let { (it.coerceAtMost(10_000) / 100f).roundToInt() } ?: 0,
            fillScreenBackgroundViewport
        )
        "PICTURE_WEATHER" -> drawProgressImageLayer(canvas, dial, point, 0, fillScreenBackgroundViewport)
        "PICTRUE_EFFECT_GRAVITY" -> drawGravityLayer(canvas, dial, point, gravityBodies[point.sourceIndex])
        "PICTURE_NUM_TIME_HHMM" -> drawNumberStrip(canvas, dial, point, formatTime("HH:mm", nowMillis))
        "PICTURE_NUM_TIME_HHMMSS" -> drawNumberStrip(canvas, dial, point, formatTime("HH:mm:ss", nowMillis))
        "PICTURE_NUM_TIME_HH" -> drawNumberStrip(canvas, dial, point, formatTime("HH", nowMillis))
        "PICTURE_NUM_TIME_MINUTE" -> drawNumberStrip(canvas, dial, point, formatTime("mm", nowMillis))
        "PICTURE_NUM_TIME_SECOND" -> drawNumberStrip(canvas, dial, point, formatTime("ss", nowMillis))
        "PICTURE_NUM_TIME_MONTH" -> drawNumberStrip(canvas, dial, point, formatTime("MM", nowMillis))
        "PICTURE_NUM_TIME_DD" -> drawNumberStrip(canvas, dial, point, formatTime("dd", nowMillis))
        "PICTURE_NUM_CHARGE" -> drawNumberStrip(canvas, dial, point, "$batteryLevel%")
        "PICTURE_NUM_STEP" -> drawNumberStrip(canvas, dial, point, "${stepCount ?: point.text.toIntOrNull() ?: 0}")
        "PICTURE_NUM_CALORIE" -> drawNumberStrip(
            canvas,
            dial,
            point,
            "${stepCount?.let { (it * 0.04f).roundToInt() } ?: point.text.toIntOrNull() ?: 0}"
        )
        "PICTURE_NUM_HEARTRATE" -> drawNumberStrip(canvas, dial, point, point.text.ifBlank { "0" })
        "PICTURE_NUM_WEATHER" -> drawNumberStrip(canvas, dial, point, point.text.ifBlank { "25" })
        "PICTURE_AMPM" -> drawAmPm(canvas, dial, point, nowMillis)
        "PICTURE_NUM_WEEK" -> drawWeek(canvas, dial, point, nowMillis)
        "PICTURE_POINTER_HOUR" -> drawPointerLayer(canvas, dial, point, nowMillis, DingDingCatPointerHand.Hour)
        "PICTURE_POINTER_MINUTE" -> drawPointerLayer(canvas, dial, point, nowMillis, DingDingCatPointerHand.Minute)
        "PICTURE_POINTER_SEC" -> drawPointerLayer(canvas, dial, point, nowMillis, DingDingCatPointerHand.Second)
        "TEXT_TIME_HHMMSS" -> drawTextLayer(canvas, dial, point, formatTime("HH:mm:ss", nowMillis))
        "TEXT_DATE" -> drawTextLayer(canvas, dial, point, formatDate(nowMillis))
    }
}

private fun drawImageLayer(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    nowMillis: Long,
    compassAzimuthDegrees: Float,
    triggeredAt: Long?,
    fillScreenBackgroundViewport: RectF?
) {
    val piece = selectAnimatedPiece(point, nowMillis, triggeredAt) ?: return
    val displayScale = if (
        point.pointType in setOf("PICTURE_LOOP", "PICTURE_NORMAL") &&
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
        point.destinationRect(
            dial = dial,
            contentWidth = targetWidth * displayScale,
            contentHeight = targetHeight * displayScale
        )
    }
    val paint = pointPaint(point)
    if (point.pointType == "PICTURE_COMPASS") {
        canvas.save()
        canvas.rotate(-compassAzimuthDegrees - point.startValue, dst.centerX(), dst.centerY())
        canvas.drawBitmap(bitmap, null, dst, paint)
        canvas.restore()
    } else {
        canvas.drawBitmap(bitmap, null, dst, paint)
    }
}

private fun drawProgressImageLayer(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    progressPercent: Int,
    fillScreenBackgroundViewport: RectF?
) {
    val pieces = point.pieces
    if (pieces.isEmpty()) return
    val index = ((progressPercent.coerceIn(0, 100) / 100f) * (pieces.size - 1).coerceAtLeast(0)).roundToInt()
    val piece = pieces.getOrNull(index) ?: pieces.firstOrNull() ?: return
    val bitmap = DingDingCatBitmapCache.get(File(dial.rootDir, piece), allowSample = true) ?: return
    val targetWidth = point.width.takeIf { it > 0f } ?: bitmap.width.toFloat()
    val targetHeight = point.height.takeIf { it > 0f } ?: bitmap.height.toFloat()
    val dst = if (fillScreenBackgroundViewport != null && isDingDingCatBackgroundLayer(point, dial)) {
        centerCropDestination(
            viewport = fillScreenBackgroundViewport,
            contentWidth = targetWidth * point.scale,
            contentHeight = targetHeight * point.scale
        )
    } else {
        point.destinationRect(
            dial = dial,
            contentWidth = targetWidth * point.scale,
            contentHeight = targetHeight * point.scale
        )
    }
    canvas.drawBitmap(bitmap, null, dst, pointPaint(point))
}

private enum class DingDingCatPointerHand {
    Hour,
    Minute,
    Second
}

private fun drawPointerLayer(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    nowMillis: Long,
    hand: DingDingCatPointerHand
) {
    val piece = point.pieces.firstOrNull() ?: return
    val bitmap = DingDingCatBitmapCache.get(File(dial.rootDir, piece), allowSample = true) ?: return
    val width = (point.width.takeIf { it > 0f } ?: bitmap.width.toFloat()) * point.scale
    val height = (point.height.takeIf { it > 0f } ?: bitmap.height.toFloat()) * point.scale
    val dst = point.destinationRect(
        dial = dial,
        contentWidth = width,
        contentHeight = height
    )
    val centerX = dst.centerX()
    val centerY = dst.centerY()
    val targetRotation = when (hand) {
        DingDingCatPointerHand.Hour -> {
            val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
            (calendar.get(Calendar.HOUR) % 12) * 30f + calendar.get(Calendar.MINUTE) * 0.5f
        }
        DingDingCatPointerHand.Minute -> {
            val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
            calendar.get(Calendar.MINUTE) * 6f + calendar.get(Calendar.SECOND) * 0.1f
        }
        DingDingCatPointerHand.Second -> {
            val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
            calendar.get(Calendar.SECOND) * 6f + calendar.get(Calendar.MILLISECOND) * 0.006f
        }
    }
    val rotation = targetRotation - point.startValue
    canvas.save()
    canvas.rotate(rotation, centerX, centerY)
    canvas.drawBitmap(bitmap, null, dst, pointPaint(point))
    canvas.restore()
}

private fun drawGravityLayer(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    body: DingDingCatGravityBody?
) {
    val piece = point.pieces.firstOrNull() ?: return
    val bitmap = DingDingCatBitmapCache.get(File(dial.rootDir, piece), allowSample = true) ?: return
    val spec = gravitySpecFor(dial, point, bitmap.width, bitmap.height) ?: return
    val center = body?.center?.let { coerceDingDingCatGravityCenter(it, spec) } ?: spec.defaultCenter
    canvas.drawBitmap(
        bitmap,
        null,
        RectF(
            center.x - spec.halfWidth,
            center.y - spec.halfHeight,
            center.x + spec.halfWidth,
            center.y + spec.halfHeight
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
    val area = point.destinationRect(
        dial = dial,
        contentWidth = point.width.takeIf { it > 0f } ?: totalWidth,
        contentHeight = point.height.takeIf { it > 0f } ?: dstHeight
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
    val dst = point.destinationRect(
        dial = dial,
        contentWidth = width,
        contentHeight = height
    )
    canvas.drawBitmap(
        bitmap,
        null,
        dst,
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
    val area = point.destinationRect(
        dial = dial,
        contentWidth = point.width.takeIf { it > 0f } ?: dstWidth,
        contentHeight = point.height.takeIf { it > 0f } ?: dstHeight
    )
    val left = when (point.gravity) {
        17 -> area.centerX() - dstWidth / 2f
        5, 8388613 -> area.right - dstWidth
        else -> area.left
    }
    val top = when (point.gravity) {
        17 -> area.centerY() - dstHeight / 2f
        80 -> area.bottom - dstHeight
        else -> area.top
    }
    canvas.drawBitmap(
        bitmap,
        Rect(srcLeft, 0, srcRight, bitmap.height),
        RectF(left, top, left + dstWidth, top + dstHeight),
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
    val area = point.destinationRect(
        dial = dial,
        contentWidth = point.width.takeIf { it > 0f } ?: dial.width.toFloat(),
        contentHeight = point.height.takeIf { it > 0f } ?: dial.height.toFloat()
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
        val frameMs = point.durationMs.toLong().coerceAtLeast(16L)
        val elapsed = nowMillis - start
        val frameIndex = (elapsed / frameMs).toInt()
        if (frameIndex >= point.pieces.size) {
            return when {
                point.animator?.endWithStatusGone == true -> null
                point.animator?.animEndSaveData == true -> point.pieces.last()
                else -> point.pieces.first()
            }
        }
        return point.pieces[frameIndex.coerceIn(0, point.pieces.lastIndex)]
    }
    val frameMs = point.durationMs.toLong().coerceAtLeast(66L)
    val index = ((nowMillis / frameMs) % point.pieces.size).toInt()
    return point.pieces[index]
}

private fun isTapTriggeredAnimation(point: DingDingCatDrawPoint): Boolean {
    val animator = point.animator ?: return false
    return point.pieces.size > 1 &&
        animator.triggerType == 1024 &&
        !animator.startWithStatusPlay
}

private fun DingDingCatDrawPoint.containsDesignPoint(dial: DingDingCatDial, point: Offset): Boolean {
    val bounds = drawBounds(dial)
    return point.x in bounds.left..bounds.right && point.y in bounds.top..bounds.bottom
}

private fun DingDingCatDrawPoint.drawBounds(dial: DingDingCatDial): RectF {
    val width = (this.width.takeIf { it > 0f } ?: dial.width.toFloat()) * this.scale
    val height = (this.height.takeIf { it > 0f } ?: dial.height.toFloat()) * this.scale
    return destinationRect(dial, width, height)
}

private fun DingDingCatDrawPoint.destinationRect(
    dial: DingDingCatDial,
    contentWidth: Float,
    contentHeight: Float
): RectF {
    val width = contentWidth.coerceAtLeast(0f)
    val height = contentHeight.coerceAtLeast(0f)
    val left = when {
        hasStartX -> startX
        gravity == 17 -> (dial.width - width) / 2f
        gravity == 5 || gravity == 8388613 -> dial.width - width
        else -> 0f
    }
    val top = when {
        hasStartY -> startY
        shouldPinTopWhenPositionMissing(dial, width, height) -> 0f
        gravity == 17 -> (dial.height - height) / 2f
        gravity == 80 -> dial.height - height
        else -> 0f
    }
    return RectF(left, top, left + width, top + height)
}

private fun isDingDingCatBackgroundLayer(point: DingDingCatDrawPoint, dial: DingDingCatDial): Boolean {
    if (point.pointType !in setOf("PICTURE_LOOP", "PICTURE_NORMAL", "PIICTURE_NORMAL")) return false
    if (isTapTriggeredAnimation(point)) return false
    val pointWidth = point.width.takeIf { it > 0f } ?: dial.width.toFloat()
    val pointHeight = point.height.takeIf { it > 0f } ?: dial.height.toFloat()
    val coversCanvas = pointWidth >= dial.width * 0.72f && pointHeight >= dial.height * 0.72f
    val firstImageLayer = dial.drawPoints.firstOrNull { it.pointType in setOf("PICTURE_LOOP", "PICTURE_NORMAL") }?.sourceIndex == point.sourceIndex
    return coversCanvas && firstImageLayer
}

private fun DingDingCatDrawPoint.shouldPinTopWhenPositionMissing(
    dial: DingDingCatDial,
    contentWidth: Float,
    contentHeight: Float
): Boolean {
    return pointType in setOf("PICTURE_NORMAL", "PIICTURE_NORMAL", "PICTRUE_EFFECT") &&
        contentWidth >= dial.width * 0.95f &&
        contentHeight < dial.height * 0.75f
}

private fun DingDingCatDial.animationFrameDelayMs(): Long {
    return drawPoints
        .asSequence()
        .filter { it.pieces.size > 1 }
        .map { point ->
            if (isTapTriggeredAnimation(point)) {
                point.durationMs.toLong().coerceAtLeast(16L)
            } else {
                point.durationMs.toLong().coerceAtLeast(66L)
            }
        }
        .minOrNull()
        ?: 1_000L
}

private data class DingDingCatRenderTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
) {
    fun screenToDesign(point: Offset): Offset {
        if (scale <= 0f) return point
        return Offset((point.x - offsetX) / scale, (point.y - offsetY) / scale)
    }

    fun screenDeltaToDesign(delta: Offset): Offset {
        if (scale <= 0f) return delta
        return Offset(delta.x / scale, delta.y / scale)
    }
}

private fun dingDingCatRenderTransform(dial: DingDingCatDial, size: IntSize): DingDingCatRenderTransform? {
    if (size.width <= 0 || size.height <= 0) return null
    val designWidth = dial.width.toFloat().coerceAtLeast(1f)
    val designHeight = dial.height.toFloat().coerceAtLeast(1f)
    val scale = min(size.width / designWidth, size.height / designHeight)
    if (scale <= 0f) return null
    return DingDingCatRenderTransform(
        scale = scale,
        offsetX = (size.width - designWidth * scale) / 2f,
        offsetY = (size.height - designHeight * scale) / 2f
    )
}

private data class DingDingCatGravitySpec(
    val sourceIndex: Int,
    val area: RectF,
    val halfWidth: Float,
    val halfHeight: Float
) {
    val defaultCenter: Offset = Offset(area.centerX(), area.centerY())
}

private data class DingDingCatGravityBody(
    val center: Offset,
    val velocity: Offset
)

private fun DingDingCatDial.gravitySpecs(): List<DingDingCatGravitySpec> {
    return drawPoints.mapNotNull { point ->
        if (point.pointType != "PICTRUE_EFFECT_GRAVITY") return@mapNotNull null
        val piece = point.pieces.firstOrNull() ?: return@mapNotNull null
        val bounds = DingDingCatBitmapCache.bounds(File(rootDir, piece)) ?: return@mapNotNull null
        gravitySpecFor(this, point, bounds.width, bounds.height)
    }
}

private fun gravitySpecFor(
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    bitmapWidth: Int,
    bitmapHeight: Int
): DingDingCatGravitySpec? {
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
    val left = if (point.hasStartX) point.startX else 0f
    val top = if (point.hasStartY) point.startY else 0f
    val width = point.width.takeIf { it > 0f } ?: dial.width.toFloat()
    val height = point.height.takeIf { it > 0f } ?: dial.height.toFloat()
    return DingDingCatGravitySpec(
        sourceIndex = point.sourceIndex,
        area = RectF(left, top, left + width, top + height),
        halfWidth = bitmapWidth * point.scale / 2f,
        halfHeight = bitmapHeight * point.scale / 2f
    )
}

private fun syncDingDingCatGravityBodies(
    bodies: MutableMap<Int, DingDingCatGravityBody>,
    specs: List<DingDingCatGravitySpec>
) {
    val liveSources = specs.map { it.sourceIndex }.toSet()
    bodies.keys.toList().filterNot(liveSources::contains).forEach(bodies::remove)
    specs.forEach { spec ->
        val body = bodies[spec.sourceIndex]
        bodies[spec.sourceIndex] = if (body == null) {
            DingDingCatGravityBody(spec.defaultCenter, Offset.Zero)
        } else {
            body.copy(center = coerceDingDingCatGravityCenter(body.center, spec))
        }
    }
}

private fun stepDingDingCatGravityBodies(
    bodies: MutableMap<Int, DingDingCatGravityBody>,
    specs: List<DingDingCatGravitySpec>,
    tilt: Offset,
    draggingSource: Int?,
    dt: Float
) {
    val acceleration = Offset(tilt.x * 1_300f, tilt.y * 1_300f)
    specs.forEach { spec ->
        if (spec.sourceIndex == draggingSource) return@forEach
        val body = bodies[spec.sourceIndex] ?: DingDingCatGravityBody(spec.defaultCenter, Offset.Zero)
        var velocity = body.velocity + offsetScale(acceleration, dt)
        velocity = offsetScale(velocity, 0.992f)
        var nextCenter = body.center + offsetScale(velocity, dt)

        val minX = spec.area.left + spec.halfWidth
        val maxX = spec.area.right - spec.halfWidth
        val minY = spec.area.top + spec.halfHeight
        val maxY = spec.area.bottom - spec.halfHeight
        if (minX <= maxX) {
            when {
                nextCenter.x < minX -> {
                    nextCenter = Offset(minX, nextCenter.y)
                    velocity = Offset(abs(velocity.x) * 0.58f, velocity.y)
                }
                nextCenter.x > maxX -> {
                    nextCenter = Offset(maxX, nextCenter.y)
                    velocity = Offset(-abs(velocity.x) * 0.58f, velocity.y)
                }
            }
        } else {
            nextCenter = Offset(spec.area.centerX(), nextCenter.y)
            velocity = Offset(0f, velocity.y)
        }
        if (minY <= maxY) {
            when {
                nextCenter.y < minY -> {
                    nextCenter = Offset(nextCenter.x, minY)
                    velocity = Offset(velocity.x, abs(velocity.y) * 0.58f)
                }
                nextCenter.y > maxY -> {
                    nextCenter = Offset(nextCenter.x, maxY)
                    velocity = Offset(velocity.x, -abs(velocity.y) * 0.58f)
                }
            }
        } else {
            nextCenter = Offset(nextCenter.x, spec.area.centerY())
            velocity = Offset(velocity.x, 0f)
        }

        if (abs(velocity.x) < 2f) velocity = Offset(0f, velocity.y)
        if (abs(velocity.y) < 2f) velocity = Offset(velocity.x, 0f)
        bodies[spec.sourceIndex] = DingDingCatGravityBody(nextCenter, velocity)
    }
}

private fun findNearestDingDingCatGravitySource(
    point: Offset,
    specs: List<DingDingCatGravitySpec>,
    bodies: Map<Int, DingDingCatGravityBody>
): Int? {
    return specs
        .filter { spec ->
            val center = bodies[spec.sourceIndex]?.center ?: spec.defaultCenter
            point.x in (center.x - spec.halfWidth)..(center.x + spec.halfWidth) &&
                point.y in (center.y - spec.halfHeight)..(center.y + spec.halfHeight)
        }
        .minByOrNull { spec ->
            val center = bodies[spec.sourceIndex]?.center ?: spec.defaultCenter
            val dx = point.x - center.x
            val dy = point.y - center.y
            dx * dx + dy * dy
        }
        ?.sourceIndex
}

private fun coerceDingDingCatGravityCenter(center: Offset, spec: DingDingCatGravitySpec): Offset {
    val minX = spec.area.left + spec.halfWidth
    val maxX = spec.area.right - spec.halfWidth
    val minY = spec.area.top + spec.halfHeight
    val maxY = spec.area.bottom - spec.halfHeight
    return Offset(
        if (minX <= maxX) center.x.coerceIn(minX, maxX) else spec.area.centerX(),
        if (minY <= maxY) center.y.coerceIn(minY, maxY) else spec.area.centerY()
    )
}

private fun offsetScale(offset: Offset, factor: Float): Offset = Offset(offset.x * factor, offset.y * factor)

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

private fun normalizeDegrees(value: Float): Float {
    val normalized = value % 360f
    return if (normalized < 0f) normalized + 360f else normalized
}

private fun shortestDegreeDelta(from: Float, to: Float): Float {
    return ((to - from + 540f) % 360f) - 180f
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
    private val boundsCache = mutableMapOf<String, DingDingCatBitmapBounds>()

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

    fun bounds(file: File): DingDingCatBitmapBounds? {
        if (!file.isFile) return null
        val key = "${file.absolutePath}:${file.lastModified()}"
        synchronized(boundsCache) {
            boundsCache[key]?.let { return it }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val result = DingDingCatBitmapBounds(bounds.outWidth, bounds.outHeight).takeIf {
            it.width > 0 && it.height > 0
        } ?: return null
        synchronized(boundsCache) {
            boundsCache[key] = result
        }
        return result
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

private data class DingDingCatBitmapBounds(val width: Int, val height: Int)

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
