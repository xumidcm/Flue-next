package com.flue.launcher.watchface.dingdingcat

import android.Manifest
import android.content.ContentValues
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
import android.os.Environment
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.core.content.FileProvider
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

@Composable
fun DingDingCatWatchFaceHost(
    descriptor: LunchWatchFaceDescriptor,
    isFaceVisible: Boolean,
    refreshToken: Int,
    fillScreen: Boolean = false,
    playbackSpeedPercent: Int = 100,
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
    val renderTransform = remember(activeDial, canvasSize) {
        activeDial?.let { dialValue -> dingDingCatRenderTransform(dialValue, canvasSize) }
    }
    val gravitySpecs = remember(activeDial, refreshToken) {
        activeDial?.gravitySpecs().orEmpty()
    }
    val hasAnimatedLayers = activeDial?.drawPoints?.any { it.pieces.size > 1 } == true
    val hasCompassLayers = activeDial?.drawPoints?.any { it.pointType == "PICTURE_COMPASS" } == true
    val normalizedPlaybackSpeedPercent = playbackSpeedPercent.coerceIn(80, 400)
    val animationFrameDelayMs = remember(activeDial, normalizedPlaybackSpeedPercent) {
        activeDial?.animationFrameDelayMs(normalizedPlaybackSpeedPercent) ?: 1_000L
    }
    val hasGravityLayers = gravitySpecs.isNotEmpty()
    val tiltOffset = rememberDingDingCatTiltOffset(context, isFaceVisible && hasGravityLayers)
    val compassAzimuth = rememberDingDingCatCompassAzimuth(context, isFaceVisible && hasCompassLayers)
    val currentTilt by rememberUpdatedState(tiltOffset)
    val currentDraggingGravitySource by rememberUpdatedState(draggingGravitySource)
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
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val transform = renderTransform
                            val designPoint = transform?.screenToDesign(down.position) ?: down.position
                            val source = findNearestDingDingCatGravitySource(
                                point = designPoint,
                                specs = gravitySpecs,
                                bodies = gravityBodies
                            ) ?: return@awaitEachGesture
                            val spec = gravitySpecs.firstOrNull { it.sourceIndex == source } ?: return@awaitEachGesture
                            draggingGravitySource = source
                            var lastDragAt = SystemClock.uptimeMillis()
                            var lastPosition = down.position
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val dragAmount = change.position - lastPosition
                                    lastPosition = change.position
                                    if (dragAmount == Offset.Zero) continue
                                    change.consume()
                                    val designDelta = renderTransform?.screenDeltaToDesign(dragAmount) ?: dragAmount
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
                            } finally {
                                draggingGravitySource = null
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
                    val overlayPointTypes = setOf("PICTRUE_EFFECT", "PICTRUE_EFFECT_GRAVITY")
                    activeDial.drawPoints.asSequence()
                        .filter { it.visible && it.pointType !in overlayPointTypes }
                        .forEach { point ->
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
                                playbackSpeedPercent = normalizedPlaybackSpeedPercent,
                                fillScreenBackgroundViewport = if (fillScreen) screenViewportInDesign else null
                            )
                        }
                    activeDial.drawPoints.asSequence()
                        .filter { it.visible && it.pointType in overlayPointTypes }
                        .forEach { point ->
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
                                playbackSpeedPercent = normalizedPlaybackSpeedPercent,
                                fillScreenBackgroundViewport = if (fillScreen) screenViewportInDesign else null
                            )
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
    val mappedIntent = dingDingCatMappedActionIntent(context, action)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launchedByMappedAction = mappedIntent?.let { intent ->
        runCatching {
            if (intent.resolveActivity(packageManager) == null) {
                false
            } else {
                context.startActivity(intent)
                true
            }
        }.getOrDefault(false)
    } == true
    if (launchedByMappedAction) return true

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

private fun dingDingCatMappedActionIntent(context: Context, action: String): Intent? {
    return when (action) {
        "com.action.mgy.camera" -> Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            createDingDingCatCameraOutputUri(context)?.let { outputUri ->
                putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        "com.action.mgy.dialer" -> Intent(Intent.ACTION_DIAL)
        "com.action.mgy.contacts" -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        "com.action.mgy.setting", "com.action.mgy.settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
        else -> null
    }
}

private fun createDingDingCatCameraOutputUri(context: Context): android.net.Uri? {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val displayName = "Flue_$timestamp.jpg"
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Flue")
        }
    }
    val mediaUri = runCatching {
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()
    if (mediaUri != null) return mediaUri

    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.cacheDir
    val outputFile = File(picturesDir, displayName).apply {
        parentFile?.mkdirs()
    }
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile)
    }.getOrNull()
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
    playbackSpeedPercent: Int,
    fillScreenBackgroundViewport: RectF?
) {
    when (point.pointType) {
        "PICTURE_LOOP", "PICTURE_NORMAL", "PIICTURE_NORMAL", "PICTURE_COMPASS" -> drawImageLayer(
            canvas,
            dial,
            point,
            nowMillis,
            compassAzimuthDegrees,
            triggeredAnimations[point.sourceIndex],
            playbackSpeedPercent,
            fillScreenBackgroundViewport
        )
        "PICTRUE_EFFECT" -> drawParticleEffectLayer(canvas, dial, point, nowMillis, playbackSpeedPercent)
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
        "PICTURE_NUM_STEP" -> drawNumberStrip(canvas, dial, point, "${stepCount?.coerceAtMost(9_999) ?: point.text.toIntOrNull() ?: 0}")
        "PICTURE_NUM_CALORIE" -> drawNumberStrip(
            canvas,
            dial,
            point,
            "${stepCount?.let { (it * 0.04f).roundToInt().coerceAtMost(999) } ?: point.text.toIntOrNull() ?: 0}"
        )
        "PICTURE_NUM_HEARTRATE" -> drawNumberStrip(canvas, dial, point, point.text.ifBlank { "0" })
        "PICTURE_NUM_WEATHER" -> drawNumberStrip(canvas, dial, point, point.text.ifBlank { "25" })
        "PICTURE_AMPM" -> drawAmPm(canvas, dial, point, nowMillis)
        "PICTURE_NUM_WEEK" -> drawWeek(canvas, dial, point, nowMillis)
        "GRAPHIC_TIME_SPACE" -> drawTimeSpaceLayer(canvas, dial, point)
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
    playbackSpeedPercent: Int,
    fillScreenBackgroundViewport: RectF?
) {
    val piece = selectAnimatedPiece(point, nowMillis, triggeredAt, playbackSpeedPercent) ?: return
    val file = File(dial.rootDir, piece)
    val bounds = DingDingCatBitmapCache.bounds(file) ?: return
    val area = point.layoutAreaRect(
        dial = dial,
        fallbackWidth = bounds.width.toFloat(),
        fallbackHeight = bounds.height.toFloat()
    )
    val stretchToArea = point.shouldStretchBitmapToArea(dial, area, bounds)
    val displayScale = if (isDingDingCatBackgroundLayer(point, dial)) 1f else point.scale
    val contentWidth = if (stretchToArea) area.width() * displayScale else bounds.width * point.scale
    val contentHeight = if (stretchToArea) area.height() * displayScale else bounds.height * point.scale
    val bitmap = DingDingCatBitmapCache.get(
        file = file,
        allowSample = true,
        targetWidth = contentWidth.toInt().coerceAtLeast(1),
        targetHeight = contentHeight.toInt().coerceAtLeast(1)
    ) ?: return
    val dst = if (fillScreenBackgroundViewport != null && isDingDingCatBackgroundLayer(point, dial)) {
        centerCropDestination(
            viewport = fillScreenBackgroundViewport,
            contentWidth = area.width(),
            contentHeight = area.height()
        )
    } else {
        point.alignedRectInArea(area, contentWidth, contentHeight)
    }
    val paint = pointPaint(point)
    if (point.pointType == "PICTURE_COMPASS") {
        canvas.save()
        canvas.rotate(-compassAzimuthDegrees - point.startValue, area.centerX(), area.centerY())
        canvas.drawBitmap(bitmap, null, dst, paint)
        canvas.restore()
    } else {
        canvas.drawBitmap(bitmap, null, dst, paint)
    }
}

private data class DingDingCatParticleEffectConfig(
    val driftX: Float,
    val driftY: Float,
    val rows: Int,
    val columns: Int,
    val alphaMin: Float,
    val alphaMax: Float
)

private fun drawParticleEffectLayer(
    canvas: AndroidCanvas,
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    nowMillis: Long,
    playbackSpeedPercent: Int
) {
    if (point.pieces.isEmpty()) return
    val config = point.particleEffectConfig()
    val area = point.layoutAreaRect(
        dial = dial,
        fallbackWidth = point.width.takeIf { it > 0f } ?: dial.width.toFloat(),
        fallbackHeight = point.height.takeIf { it > 0f } ?: dial.height.toFloat()
    )
    val cellWidth = area.width() / config.columns.coerceAtLeast(1)
    val cycleBaseMs = point.durationMs.toLong().coerceAtLeast(16L) * 96L
    val cycleMs = (cycleBaseMs / config.driftY.coerceIn(0.5f, 3f)).toLong().coerceAtLeast(800L)
    val animationMillis = scaleDingDingCatAnimationTime(nowMillis, playbackSpeedPercent)
    canvas.save()
    canvas.clipRect(area.left, area.top, area.right, area.bottom)
    try {
        point.pieces.forEachIndexed { index, piece ->
            val bitmap = DingDingCatBitmapCache.get(File(dial.rootDir, piece), allowSample = true) ?: return@forEachIndexed
            val seed = stableDingDingCatSeed(point.sourceIndex, index)
            val gridIndex = seed.floorMod(config.rows * config.columns)
            val column = gridIndex % config.columns
            val row = gridIndex / config.columns
            val phase = ((animationMillis + seed * 37L).floorMod(cycleMs)).toFloat() / cycleMs
            val scale = point.scale * (0.78f + ((seed ushr 8).floorMod(30)) / 100f)
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val baseX = area.left + (column + 0.5f) * cellWidth
            val wave = sin((phase * 6.28318f) + index) * cellWidth * 0.22f * config.driftX.coerceIn(0.5f, 2.5f)
            val x = if (width >= area.width()) {
                area.centerX()
            } else {
                (baseX + wave).coerceIn(area.left + width / 2f, area.right - width / 2f)
            }
            val travel = area.height() + height * 2f
            val rawY = area.top - height + ((phase + row / config.rows.toFloat()) % 1f) * travel
            val y = if (height >= area.height()) {
                area.centerY()
            } else {
                rawY.coerceIn(area.top + height / 2f, area.bottom - height / 2f)
            }
            val alphaMix = config.alphaMin + ((seed ushr 16).floorMod(100) / 99f) * (config.alphaMax - config.alphaMin)
            val paint = pointPaint(point).apply {
                alpha = (pointAlpha(point) * alphaMix.coerceIn(0.05f, 1f)).roundToInt().coerceIn(0, 255)
            }
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f),
                paint
            )
        }
    } finally {
        canvas.restore()
    }
}

private fun DingDingCatDrawPoint.particleEffectConfig(): DingDingCatParticleEffectConfig {
    val parts = aminEffectStr
        ?.split(',')
        ?.mapNotNull { it.trim().toFloatOrNull() }
        .orEmpty()
    return DingDingCatParticleEffectConfig(
        driftX = parts.getOrNull(0) ?: 1f,
        driftY = parts.getOrNull(1) ?: 1f,
        rows = parts.getOrNull(2)?.roundToInt()?.coerceIn(2, 12) ?: 6,
        columns = parts.getOrNull(3)?.roundToInt()?.coerceIn(2, 16) ?: pieces.size.coerceIn(4, 10),
        alphaMin = parts.getOrNull(6)?.coerceIn(0f, 1f) ?: 0.35f,
        alphaMax = parts.getOrNull(7)?.coerceIn(0f, 1f) ?: 0.9f
    )
}

private fun stableDingDingCatSeed(sourceIndex: Int, pieceIndex: Int): Int {
    var value = sourceIndex * 1_103_515_245 + pieceIndex * 12_345 + 0x45D9F3B
    value = value xor (value ushr 16)
    value *= 0x45D9F3B
    value = value xor (value ushr 16)
    return value and Int.MAX_VALUE
}

private fun Int.floorMod(modulus: Int): Int {
    if (modulus <= 0) return 0
    val result = this % modulus
    return if (result < 0) result + modulus else result
}

private fun Long.floorMod(modulus: Long): Long {
    if (modulus <= 0L) return 0L
    val result = this % modulus
    return if (result < 0L) result + modulus else result
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
    val file = File(dial.rootDir, piece)
    val bounds = DingDingCatBitmapCache.bounds(file) ?: return
    val area = point.layoutAreaRect(
        dial = dial,
        fallbackWidth = bounds.width.toFloat(),
        fallbackHeight = bounds.height.toFloat()
    )
    val stretchToArea = point.shouldStretchBitmapToArea(dial, area, bounds)
    val contentWidth = if (stretchToArea) area.width() * point.scale else bounds.width * point.scale
    val contentHeight = if (stretchToArea) area.height() * point.scale else bounds.height * point.scale
    val bitmap = DingDingCatBitmapCache.get(
        file = file,
        allowSample = true,
        targetWidth = contentWidth.toInt().coerceAtLeast(1),
        targetHeight = contentHeight.toInt().coerceAtLeast(1)
    ) ?: return
    val dst = if (fillScreenBackgroundViewport != null && isDingDingCatBackgroundLayer(point, dial)) {
        centerCropDestination(
            viewport = fillScreenBackgroundViewport,
            contentWidth = area.width(),
            contentHeight = area.height()
        )
    } else {
        point.alignedRectInArea(area, contentWidth, contentHeight)
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
    val file = File(dial.rootDir, piece)
    val bounds = DingDingCatBitmapCache.bounds(file) ?: return
    val area = point.layoutAreaRect(
        dial = dial,
        fallbackWidth = bounds.width.toFloat(),
        fallbackHeight = bounds.height.toFloat()
    )
    val width = bounds.width * point.scale
    val height = bounds.height * point.scale
    val dst = point.alignedRectInArea(area, width, height)
    val centerX = area.centerX()
    val centerY = area.centerY()
    val bitmap = DingDingCatBitmapCache.get(
        file = file,
        allowSample = true,
        targetWidth = width.toInt().coerceAtLeast(1),
        targetHeight = height.toInt().coerceAtLeast(1)
    ) ?: return
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
    val rotation = targetRotation
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
    val file = File(dial.rootDir, piece)
    val bitmap = DingDingCatBitmapCache.get(file, allowSample = true) ?: return
    val spec = gravitySpecFor(
        dial = dial,
        point = point,
        bitmapWidth = bitmap.width,
        bitmapHeight = bitmap.height,
        visibleBounds = DingDingCatBitmapCache.visibleBounds(file)
    ) ?: return
    val center = body?.center?.let { coerceDingDingCatGravityCenter(it, spec) }
        ?: coerceDingDingCatGravityCenter(spec.defaultCenter, spec)
    canvas.drawBitmap(
        bitmap,
        null,
        RectF(
            center.x + spec.drawLeftOffset,
            center.y + spec.drawTopOffset,
            center.x + spec.drawRightOffset,
            center.y + spec.drawBottomOffset
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
    val baseRenderScale = point.scale * (
        point.height
            .takeIf { it > 0f }
            ?.let { it / bitmap.height.toFloat() }
            ?: 1f
        )
    val chars = text.mapNotNull { char -> dingDingCatCharIndex(char)?.let { char to it } }
    if (chars.isEmpty()) return
    val area = point.destinationRect(
        dial = dial,
        contentWidth = point.width.takeIf { it > 0f } ?: chars.size * sliceWidth * baseRenderScale,
        contentHeight = point.height.takeIf { it > 0f } ?: bitmap.height * baseRenderScale
    )
    val rawDstWidth = sliceWidth * baseRenderScale
    val rawTotalWidth = chars.size * rawDstWidth + (chars.size - 1).coerceAtLeast(0) * point.splitSpace
    val widthFitScale = if (rawTotalWidth > area.width() && area.width() > 0f) {
        area.width() / rawTotalWidth
    } else {
        1f
    }
    val renderScale = baseRenderScale * widthFitScale
    val splitSpace = point.splitSpace * widthFitScale
    val dstHeight = bitmap.height * renderScale
    val dstWidth = sliceWidth * renderScale
    val totalWidth = chars.size * dstWidth + (chars.size - 1).coerceAtLeast(0) * splitSpace
    val paint = pointPaint(point)
    var cursorX = when (resolveHorizontalGravity(point.gravity)) {
        Gravity.CENTER_HORIZONTAL -> area.centerX() - totalWidth / 2f
        Gravity.RIGHT -> area.right - totalWidth
        else -> area.left
    }
    val top = when (resolveVerticalGravity(point.gravity)) {
        Gravity.CENTER_VERTICAL -> area.centerY() - dstHeight / 2f
        Gravity.BOTTOM -> area.bottom - dstHeight
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
        cursorX += dstWidth + splitSpace
    }
}

private fun drawAmPm(canvas: AndroidCanvas, dial: DingDingCatDial, point: DingDingCatDrawPoint, nowMillis: Long) {
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val index = if (calendar.get(Calendar.AM_PM) == Calendar.PM) 1 else 0
    val piece = point.pieces.getOrNull(index) ?: point.pieces.firstOrNull() ?: return
    val file = File(dial.rootDir, piece)
    val bounds = DingDingCatBitmapCache.bounds(file) ?: return
    val area = point.layoutAreaRect(
        dial = dial,
        fallbackWidth = bounds.width.toFloat(),
        fallbackHeight = bounds.height.toFloat()
    )
    val width = bounds.width * point.scale
    val height = bounds.height * point.scale
    val dst = point.alignedRectInArea(area, width, height)
    val bitmap = DingDingCatBitmapCache.get(
        file = file,
        allowSample = true,
        targetWidth = width.toInt().coerceAtLeast(1),
        targetHeight = height.toInt().coerceAtLeast(1)
    ) ?: return
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
    val area = point.layoutAreaRect(
        dial = dial,
        fallbackWidth = dstWidth,
        fallbackHeight = dstHeight
    )
    val left = when (resolveHorizontalGravity(point.gravity)) {
        Gravity.CENTER_HORIZONTAL -> area.centerX() - dstWidth / 2f
        Gravity.RIGHT -> area.right - dstWidth
        else -> area.left
    }
    val top = when (resolveVerticalGravity(point.gravity)) {
        Gravity.CENTER_VERTICAL -> area.centerY() - dstHeight / 2f
        Gravity.BOTTOM -> area.bottom - dstHeight
        else -> area.top
    }
    canvas.drawBitmap(
        bitmap,
        Rect(srcLeft, 0, srcRight, bitmap.height),
        RectF(left, top, left + dstWidth, top + dstHeight),
        pointPaint(point)
    )
}

private fun drawTimeSpaceLayer(canvas: AndroidCanvas, dial: DingDingCatDial, point: DingDingCatDrawPoint) {
    val area = point.scaledLayerRect(
        dial = dial,
        rawWidth = point.width.takeIf { it > 0f } ?: dial.width.toFloat(),
        rawHeight = point.height.takeIf { it > 0f } ?: dial.height.toFloat()
    )
    val colors = point.text.split(',')
        .mapNotNull { raw ->
            raw.trim().takeIf(String::isNotBlank)?.let { color ->
                runCatching { android.graphics.Color.parseColor(color) }.getOrNull()
            }
        }
    val outerColor = colors.getOrNull(0) ?: point.font.color.takeIf { it != -1 } ?: android.graphics.Color.WHITE
    val innerColor = colors.getOrNull(1) ?: outerColor
    val centerX = area.centerX()
    val centerY = area.centerY()
    val radiusBase = min(area.width(), area.height()) / 2f
    val outerRadius = radiusBase * 0.82f
    val innerRadius = radiusBase * 0.56f
    val tickOuter = radiusBase * 0.72f
    val tickInner = radiusBase * 0.64f
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = (point.font.textSize.takeIf { it > 0f } ?: 24f) * point.scale
        typeface = Typeface.DEFAULT
        alpha = pointAlpha(point)
    }
    val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = outerColor
        strokeWidth = max(1f, 2f * point.scale)
        alpha = (pointAlpha(point) * 0.72f).roundToInt().coerceIn(0, 255)
        strokeCap = Paint.Cap.ROUND
    }
    repeat(60) { minute ->
        val angle = Math.toRadians((minute * 6f - 90f).toDouble())
        val start = Offset(
            centerX + cos(angle).toFloat() * tickInner,
            centerY + sin(angle).toFloat() * tickInner
        )
        val end = Offset(
            centerX + cos(angle).toFloat() * tickOuter,
            centerY + sin(angle).toFloat() * tickOuter
        )
        canvas.drawLine(start.x, start.y, end.x, end.y, tickPaint)
    }
    repeat(12) { index ->
        val minute = index * 5
        val label = if (minute == 0) "60" else minute.toString().padStart(2, '0')
        drawCircularLabel(canvas, labelPaint, label, centerX, centerY, outerRadius, minute * 6f, outerColor)
        drawCircularLabel(canvas, labelPaint, label, centerX, centerY, innerRadius, minute * 6f, innerColor)
    }
}

private fun drawCircularLabel(
    canvas: AndroidCanvas,
    paint: Paint,
    label: String,
    centerX: Float,
    centerY: Float,
    radius: Float,
    degrees: Float,
    color: Int
) {
    canvas.save()
    canvas.rotate(degrees, centerX, centerY)
    paint.color = color
    canvas.drawText(label, centerX, centerY - radius - (paint.descent() + paint.ascent()) / 2f, paint)
    canvas.restore()
}

private fun drawTextLayer(canvas: AndroidCanvas, dial: DingDingCatDial, point: DingDingCatDrawPoint, text: String) {
    val horizontalGravity = resolveHorizontalGravity(point.gravity)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = point.font.color
        textSize = point.font.textSize * point.scale
        textAlign = when (horizontalGravity) {
            Gravity.CENTER_HORIZONTAL -> Paint.Align.CENTER
            Gravity.RIGHT -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
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
    val x = when (horizontalGravity) {
        Gravity.CENTER_HORIZONTAL -> area.centerX()
        Gravity.RIGHT -> area.right
        else -> area.left
    }
    val y = area.centerY() - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(text, x, y, paint)
}

private fun selectAnimatedPiece(
    point: DingDingCatDrawPoint,
    nowMillis: Long,
    triggeredAt: Long?,
    playbackSpeedPercent: Int
): String? {
    if (point.pieces.isEmpty()) return null
    if (point.pieces.size == 1) return point.pieces.first()
    if (isTapTriggeredAnimation(point)) {
        val start = triggeredAt ?: return point.pieces.first()
        val frameMs = point.frameDurationMs(playbackSpeedPercent)
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
    val frameMs = point.frameDurationMs(playbackSpeedPercent)
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
    return layoutAreaRect(dial, this.width.takeIf { it > 0f } ?: dial.width.toFloat(), this.height.takeIf { it > 0f } ?: dial.height.toFloat())
}

private fun DingDingCatDrawPoint.destinationRect(
    dial: DingDingCatDial,
    contentWidth: Float,
    contentHeight: Float
): RectF {
    val area = layoutAreaRect(
        dial = dial,
        fallbackWidth = contentWidth.coerceAtLeast(0f),
        fallbackHeight = contentHeight.coerceAtLeast(0f)
    )
    return alignedRectInArea(area, contentWidth, contentHeight)
}

private fun DingDingCatDrawPoint.scaledLayerRect(
    dial: DingDingCatDial,
    rawWidth: Float,
    rawHeight: Float
): RectF {
    val contentWidth = rawWidth.coerceAtLeast(0f) * scale
    val contentHeight = rawHeight.coerceAtLeast(0f) * scale
    val area = layoutAreaRect(
        dial = dial,
        fallbackWidth = rawWidth.coerceAtLeast(0f),
        fallbackHeight = rawHeight.coerceAtLeast(0f)
    )
    val aligned = alignedRectInArea(area, contentWidth, contentHeight)
    val left = if (hasStartX) startX else aligned.left
    val top = if (hasStartY) startY else aligned.top
    return RectF(left, top, left + contentWidth, top + contentHeight)
}

private fun DingDingCatDrawPoint.layoutAreaRect(
    dial: DingDingCatDial,
    fallbackWidth: Float,
    fallbackHeight: Float
): RectF {
    val width = this.width.takeIf { it > 0f } ?: fallbackWidth
    val height = this.height.takeIf { it > 0f } ?: fallbackHeight
    val left = if (hasStartX) startX else 0f
    val top = if (hasStartY) startY else 0f
    return RectF(left, top, left + width.coerceAtLeast(0f), top + height.coerceAtLeast(0f))
}

private fun DingDingCatDrawPoint.alignedRectInArea(
    area: RectF,
    contentWidth: Float,
    contentHeight: Float
): RectF {
    val width = contentWidth.coerceAtLeast(0f)
    val height = contentHeight.coerceAtLeast(0f)
    val left = when {
        resolveHorizontalGravity(gravity) == Gravity.CENTER_HORIZONTAL -> area.centerX() - width / 2f
        resolveHorizontalGravity(gravity) == Gravity.RIGHT -> area.right - width
        else -> area.left
    }
    val top = when {
        shouldPinTopWhenPositionMissing(area, width, height) -> area.top
        resolveVerticalGravity(gravity) == Gravity.CENTER_VERTICAL -> area.centerY() - height / 2f
        resolveVerticalGravity(gravity) == Gravity.BOTTOM -> area.bottom - height
        else -> area.top
    }
    return RectF(left, top, left + width, top + height)
}

private fun DingDingCatDrawPoint.shouldStretchBitmapToArea(
    dial: DingDingCatDial,
    area: RectF,
    bounds: DingDingCatBitmapBounds
): Boolean {
    if (isDingDingCatBackgroundLayer(this, dial)) return true
    if (bounds.width <= 0 || bounds.height <= 0) return false
    val widthRatio = area.width() / bounds.width.toFloat()
    val heightRatio = area.height() / bounds.height.toFloat()
    return abs(widthRatio - 1f) <= 0.12f && abs(heightRatio - 1f) <= 0.12f
}

private fun isDingDingCatBackgroundLayer(point: DingDingCatDrawPoint, dial: DingDingCatDial): Boolean {
    if (point.pointType !in setOf("PICTURE_LOOP", "PICTURE_NORMAL", "PIICTURE_NORMAL")) return false
    val pointWidth = point.width.takeIf { it > 0f } ?: dial.width.toFloat()
    val pointHeight = point.height.takeIf { it > 0f } ?: dial.height.toFloat()
    val coversCanvas = pointWidth >= dial.width * 0.72f && pointHeight >= dial.height * 0.72f
    val firstImageLayer = dial.drawPoints
        .firstOrNull { it.pointType in setOf("PICTURE_LOOP", "PICTURE_NORMAL", "PIICTURE_NORMAL") }
        ?.sourceIndex == point.sourceIndex
    return coversCanvas && firstImageLayer
}

private fun DingDingCatDrawPoint.shouldPinTopWhenPositionMissing(
    area: RectF,
    contentWidth: Float,
    contentHeight: Float
): Boolean {
    return pointType in setOf("PICTURE_NORMAL", "PIICTURE_NORMAL", "PICTRUE_EFFECT") &&
        contentWidth >= area.width() * 0.95f &&
        contentHeight < area.height() * 0.75f
}

private fun DingDingCatDial.animationFrameDelayMs(playbackSpeedPercent: Int): Long {
    return drawPoints
        .asSequence()
        .filter { it.pieces.size > 1 }
        .map { point -> point.frameDurationMs(playbackSpeedPercent) }
        .minOrNull()
        ?: 1_000L
}

private fun DingDingCatDrawPoint.frameDurationMs(playbackSpeedPercent: Int): Long {
    val speed = playbackSpeedPercent.coerceIn(80, 400) / 100f
    return (frameDurationMs() / speed).roundToLong().coerceIn(8L, 1_000L)
}

private fun DingDingCatDrawPoint.frameDurationMs(): Long {
    val pieceCount = pieces.size.coerceAtLeast(1)
    val totalDuration = animator?.totalDurationMs?.takeIf { it > 0 }
    if (totalDuration != null && pieceCount > 1) {
        return (totalDuration.toLong() / pieceCount).coerceAtLeast(16L)
    }
    return durationMs.toLong().takeIf { it > 0L }?.coerceAtLeast(16L) ?: 66L
}

private fun scaleDingDingCatAnimationTime(nowMillis: Long, playbackSpeedPercent: Int): Long {
    return (nowMillis * (playbackSpeedPercent.coerceIn(80, 400) / 100f)).roundToLong()
}

private fun resolveHorizontalGravity(gravity: Int): Int {
    val absolute = Gravity.getAbsoluteGravity(gravity, android.view.View.LAYOUT_DIRECTION_LTR)
    return absolute and Gravity.HORIZONTAL_GRAVITY_MASK
}

private fun resolveVerticalGravity(gravity: Int): Int {
    return gravity and Gravity.VERTICAL_GRAVITY_MASK
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
    val halfHeight: Float,
    val defaultCenter: Offset,
    val drawLeftOffset: Float,
    val drawTopOffset: Float,
    val drawRightOffset: Float,
    val drawBottomOffset: Float
)

private data class DingDingCatGravityBody(
    val center: Offset,
    val velocity: Offset
)

private fun DingDingCatDial.gravitySpecs(): List<DingDingCatGravitySpec> {
    return drawPoints.mapNotNull { point ->
        if (point.pointType != "PICTRUE_EFFECT_GRAVITY") return@mapNotNull null
        val piece = point.pieces.firstOrNull() ?: return@mapNotNull null
        val file = File(rootDir, piece)
        val bounds = DingDingCatBitmapCache.bounds(file) ?: return@mapNotNull null
        val visibleBounds = DingDingCatBitmapCache.visibleBounds(file)
        gravitySpecFor(this, point, bounds.width, bounds.height, visibleBounds)
    }
}

private fun gravitySpecFor(
    dial: DingDingCatDial,
    point: DingDingCatDrawPoint,
    bitmapWidth: Int,
    bitmapHeight: Int,
    visibleBounds: Rect? = null
): DingDingCatGravitySpec? {
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
    val contentBounds = visibleBounds?.takeIf { !it.isEmpty } ?: Rect(0, 0, bitmapWidth, bitmapHeight)
    val initialArea = point.layoutAreaRect(
        dial = dial,
        fallbackWidth = bitmapWidth.toFloat(),
        fallbackHeight = bitmapHeight.toFloat()
    )
    val movementArea = point.layoutAreaRect(
        dial = dial,
        fallbackWidth = dial.width.toFloat(),
        fallbackHeight = dial.height.toFloat()
    )
    val visibleCenterX = contentBounds.exactCenterX()
    val visibleCenterY = contentBounds.exactCenterY()
    return DingDingCatGravitySpec(
        sourceIndex = point.sourceIndex,
        area = movementArea,
        halfWidth = contentBounds.width() * point.scale / 2f,
        halfHeight = contentBounds.height() * point.scale / 2f,
        defaultCenter = Offset(initialArea.centerX(), initialArea.centerY()),
        drawLeftOffset = -visibleCenterX * point.scale,
        drawTopOffset = -visibleCenterY * point.scale,
        drawRightOffset = (bitmapWidth - visibleCenterX) * point.scale,
        drawBottomOffset = (bitmapHeight - visibleCenterY) * point.scale
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
            DingDingCatGravityBody(coerceDingDingCatGravityCenter(spec.defaultCenter, spec), Offset.Zero)
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
    private val visibleBoundsCache = mutableMapOf<String, Rect>()

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

    fun visibleBounds(file: File): Rect? {
        if (!file.isFile) return null
        val key = "${file.absolutePath}:${file.lastModified()}"
        synchronized(visibleBoundsCache) {
            visibleBoundsCache[key]?.let { return Rect(it) }
        }
        val bitmap = get(file, allowSample = false) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        pixels.forEachIndexed { index, pixel ->
            if (android.graphics.Color.alpha(pixel) <= 8) return@forEachIndexed
            val x = index % width
            val y = index / width
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val result = if (maxX >= minX && maxY >= minY) {
            Rect(minX, minY, maxX + 1, maxY + 1)
        } else {
            Rect(0, 0, width, height)
        }
        synchronized(visibleBoundsCache) {
            visibleBoundsCache[key] = Rect(result)
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
