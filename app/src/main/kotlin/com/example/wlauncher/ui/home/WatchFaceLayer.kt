package com.flue.launcher.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface as AndroidTypeface
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.BatteryManager
import android.os.Build
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.WatchFacePhotoCache
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.isMaterial
import com.flue.launcher.watchface.WatchClockColorMode
import com.flue.launcher.watchface.WatchClockPosition
import com.flue.launcher.watchface.WatchFaceClockStyle
import com.flue.launcher.watchface.WatchFaceMd3eShape
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.sin

data class ClockSnapshot(
    val time: String,
    val date: String,
    val hour: Int = 0,
    val minute: Int = 0,
    val second: Int = 0,
    val dayOfMonth: Int = 1,
    val weekdayShort: String = ""
)

val FIXED_PREVIEW_CLOCK = ClockSnapshot(
    time = "12:31",
    date = "\u0031\u0031\u6708\u0031\u0033\u65e5",
    hour = 12,
    minute = 31,
    second = 24,
    dayOfMonth = 27,
    weekdayShort = "\u5468\u4E00"
)

data class ClockPalette(
    val timeColor: Color,
    val dateColor: Color,
    val seedColor: Color = timeColor
)

private data class Md3eColorPalette(
    val faceColor: Color,
    val textColor: Color,
    val hourColor: Color,
    val minuteColor: Color,
    val secondColor: Color
)

private object WatchFaceVideoThumbnailCache {
    private const val MAX_ENTRIES = 3
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun get(path: String): ImageBitmap? = cache[path]

    @Synchronized
    fun put(path: String, bitmap: ImageBitmap) {
        cache[path] = bitmap
    }
}

private object WatchFacePaletteCache {
    private const val MAX_ENTRIES = 8
    private val cache = object : LinkedHashMap<String, ClockPalette>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClockPalette>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun get(key: String): ClockPalette? = cache[key]

    @Synchronized
    fun put(key: String, palette: ClockPalette) {
        cache[key] = palette
    }
}

private data class WatchFaceTypeface(
    val androidTypeface: AndroidTypeface?,
    val fontFamily: FontFamily?
)

private data class ChargingSnapshot(
    val charging: Boolean = false,
    val speedWatts: Float? = null
)

@Composable
fun WatchFaceLayer(
    watchFaceId: String = BUILT_IN_WATCHFACE_ID,
    photoPath: String? = null,
    videoPath: String? = null,
    isFaceVisible: Boolean = true,
    uiStyle: UiStyle = UiStyle.APPLE_WATCH,
    photoOptions: BuiltInWatchFaceOptions = BuiltInWatchFaceOptions(),
    videoOptions: BuiltInWatchFaceOptions = BuiltInWatchFaceOptions(),
    showChargingPowerText: Boolean = true,
    showStatusIndicators: Boolean = true,
    hasNotifications: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BuiltInWatchFaceSurface(
        watchFaceId = watchFaceId,
        photoPath = photoPath,
        videoPath = videoPath,
        isFaceVisible = isFaceVisible,
        uiStyle = uiStyle,
        photoOptions = photoOptions,
        videoOptions = videoOptions,
        showChargingPowerText = showChargingPowerText,
        showStatusIndicators = showStatusIndicators,
        hasNotifications = hasNotifications,
        clockOverride = null,
        onLongPress = onLongPress,
        showClock = true,
        keepVideoAliveWhenHidden = true,
        modifier = modifier
    )
}

@Composable
fun BuiltInWatchFacePreview(
    watchFaceId: String,
    photoPath: String? = null,
    videoPath: String? = null,
    uiStyle: UiStyle = UiStyle.APPLE_WATCH,
    photoOptions: BuiltInWatchFaceOptions = BuiltInWatchFaceOptions(),
    videoOptions: BuiltInWatchFaceOptions = BuiltInWatchFaceOptions(),
    clockOverride: ClockSnapshot? = FIXED_PREVIEW_CLOCK,
    modifier: Modifier = Modifier,
    showClock: Boolean = false,
    playVideo: Boolean = true,
    showChargingPowerText: Boolean = true,
    showStatusIndicators: Boolean = false,
    hasNotifications: Boolean = false
) {
    BuiltInWatchFaceSurface(
        watchFaceId = watchFaceId,
        photoPath = photoPath,
        videoPath = videoPath,
        isFaceVisible = playVideo,
        uiStyle = uiStyle,
        photoOptions = photoOptions,
        videoOptions = videoOptions,
        showChargingPowerText = showChargingPowerText,
        showStatusIndicators = showStatusIndicators,
        hasNotifications = hasNotifications,
        clockOverride = clockOverride,
        onLongPress = null,
        showClock = showClock,
        keepVideoAliveWhenHidden = false,
        modifier = modifier
    )
}

@Composable
private fun BuiltInWatchFaceSurface(
    watchFaceId: String,
    photoPath: String?,
    videoPath: String?,
    isFaceVisible: Boolean,
    uiStyle: UiStyle,
    photoOptions: BuiltInWatchFaceOptions,
    videoOptions: BuiltInWatchFaceOptions,
    showChargingPowerText: Boolean,
    showStatusIndicators: Boolean,
    hasNotifications: Boolean,
    clockOverride: ClockSnapshot?,
    onLongPress: (() -> Unit)?,
    showClock: Boolean,
    keepVideoAliveWhenHidden: Boolean,
    modifier: Modifier = Modifier
) {
    val activeOptions = when (watchFaceId) {
        BUILT_IN_PHOTO_WATCHFACE_ID -> photoOptions
        BUILT_IN_VIDEO_WATCHFACE_ID -> videoOptions
        else -> photoOptions.copy(
            clockPosition = WatchClockPosition.CENTER,
            clockSizeSp = 64,
            boldClock = false,
            clockColorMode = WatchClockColorMode.AUTO
        )
    }
    val liveClock = if (showClock) rememberClockSnapshot(activeOptions.showSeconds) else (clockOverride ?: FIXED_PREVIEW_CLOCK)
    val clock = formatClockForOptions(clockOverride ?: liveClock, activeOptions.showSeconds)
    val clockPalette = if (showClock) {
        rememberClockPalette(
            watchFaceId = watchFaceId,
            photoPath = photoPath,
            videoPath = videoPath,
            clockPosition = activeOptions.clockPosition,
            videoClockColorMode = videoOptions.clockColorMode
        )
    } else {
        defaultClockPalette(watchFaceId)
    }
    val typeface = if (showClock) rememberWatchFaceTypeface(activeOptions.fontPath) else WatchFaceTypeface(null, null)
    val chargingSnapshot = if (showClock) rememberChargingSnapshot() else ChargingSnapshot()
    val launcherStyle = LauncherTheme.style
    val backgroundModifier = modifier
        .fillMaxSize()
        .then(
            if (onLongPress != null) {
                Modifier.pointerInput(onLongPress) {
                    detectTapGestures(onLongPress = { onLongPress() })
                }
            } else {
                Modifier
            }
        )

    Box(
        modifier = backgroundModifier
            .graphicsLayer { clip = true }
            .background(launcherStyle.screenBackground),
        contentAlignment = Alignment.Center
    ) {
        when (watchFaceId) {
            BUILT_IN_PHOTO_WATCHFACE_ID -> {
                MediaImageBackground(photoPath)
            }

            BUILT_IN_VIDEO_WATCHFACE_ID -> {
                MediaVideoBackground(
                    path = videoPath,
                    isFaceVisible = isFaceVisible,
                    fillScreen = videoOptions.cropToFill,
                    keepAliveWhenHidden = keepVideoAliveWhenHidden
                )
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (uiStyle.isMaterial) {
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF1F3144),
                                        Color(0xFF2E4965),
                                        launcherStyle.screenBackground
                                    )
                                )
                            } else {
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF16304A), Color(0xFF0B1322), Color.Black),
                                    radius = 900f
                                )
                            }
                        )
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(launcherStyle.screenScrim)
        )

        if (showClock) {
            ClockOverlay(
                clock = clock,
                compact = false,
                options = activeOptions,
                palette = clockPalette,
                typeface = typeface,
                chargingSnapshot = chargingSnapshot,
                showChargingPowerText = showChargingPowerText,
                fallbackTitle = when (watchFaceId) {
                    BUILT_IN_PHOTO_WATCHFACE_ID -> if (photoPath.isNullOrBlank()) "\u672A\u8BBE\u7F6E\u56FE\u7247" else null
                    BUILT_IN_VIDEO_WATCHFACE_ID -> if (videoPath.isNullOrBlank()) "\u672A\u8BBE\u7F6E\u89C6\u9891" else null
                    else -> null
                },
                fallbackSubtitle = when (watchFaceId) {
                    BUILT_IN_PHOTO_WATCHFACE_ID -> if (photoPath.isNullOrBlank()) "\u5728\u8868\u76D8\u8BBE\u7F6E\u91CC\u9009\u62E9\u4E00\u5F20\u56FE\u7247" else null
                    BUILT_IN_VIDEO_WATCHFACE_ID -> if (videoPath.isNullOrBlank()) "\u5728\u8868\u76D8\u8BBE\u7F6E\u91CC\u9009\u62E9\u4E00\u4E2A\u89C6\u9891" else null
                    else -> null
                }
            )
        }

        if (showClock && showStatusIndicators) {
            WatchFaceStatusIndicators(
                chargingSnapshot = chargingSnapshot,
                hasNotifications = hasNotifications,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp)
            )
        }
    }
}

@Composable
private fun MediaImageBackground(path: String?) {
    val filePath = path?.takeIf { File(it).exists() }
    val photoBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = filePath) {
        value = if (filePath.isNullOrBlank()) {
            null
        } else {
            WatchFacePhotoCache.get(filePath) ?: withContext(Dispatchers.IO) {
                decodeWatchFacePhoto(filePath)
            }?.also { WatchFacePhotoCache.put(filePath, it) }
        }
    }
    if (photoBitmap != null) {
        Image(
            bitmap = photoBitmap!!,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { clip = true }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    }
}

@Composable
private fun MediaVideoBackground(
    path: String?,
    isFaceVisible: Boolean,
    fillScreen: Boolean,
    keepAliveWhenHidden: Boolean
) {
    val filePath = path?.takeIf { File(it).exists() }
    if (!isFaceVisible && !keepAliveWhenHidden) {
        MediaVideoThumbnail(filePath)
        return
    }
    AndroidView(
        factory = { context -> InternalLoopingVideoView(context) },
        update = { view ->
            view.bind(filePath, isFaceVisible || keepAliveWhenHidden, fillScreen)
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun MediaVideoThumbnail(path: String?) {
    val frameBitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            WatchFaceVideoThumbnailCache.get(path) ?: withContext(Dispatchers.IO) {
                extractVideoThumbnail(path)
            }?.also { WatchFaceVideoThumbnailCache.put(path, it) }
        }
    }
    if (frameBitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = frameBitmap!!,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
    }
}

@Composable
fun ClockOverlayPreview(
    clock: ClockSnapshot,
    clockPosition: WatchClockPosition,
    clockSizeSp: Int,
    boldClock: Boolean = false,
    palette: ClockPalette,
    fallbackTitle: String? = null,
    fallbackSubtitle: String? = null
) {
    ClockOverlay(
        clock = clock,
        compact = false,
        options = BuiltInWatchFaceOptions(
            clockPosition = clockPosition,
            clockSizeSp = clockSizeSp,
            boldClock = boldClock
        ),
        palette = palette,
        typeface = WatchFaceTypeface(androidTypeface = null, fontFamily = null),
        chargingSnapshot = ChargingSnapshot(),
        showChargingPowerText = true,
        fallbackTitle = fallbackTitle,
        fallbackSubtitle = fallbackSubtitle
    )
}

@Composable
private fun WatchFaceStatusIndicators(
    chargingSnapshot: ChargingSnapshot,
    hasNotifications: Boolean,
    modifier: Modifier = Modifier
) {
    if (!chargingSnapshot.charging && !hasNotifications) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (chargingSnapshot.charging) {
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(22.dp)
                    .background(Color(0xFF22C55E).copy(alpha = 0.92f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                ChargingBoltGlyph()
            }
        }
        if (hasNotifications) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(Color(0xFFFF3B30), CircleShape)
            )
        }
    }
}

@Composable
private fun ChargingBoltGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.width(12.dp).height(15.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.20f, h * 0.55f)
            lineTo(w * 0.47f, h * 0.55f)
            lineTo(w * 0.34f, h)
            lineTo(w * 0.82f, h * 0.40f)
            lineTo(w * 0.54f, h * 0.40f)
            close()
        }
        drawPath(path = path, color = Color.White)
    }
}

@Composable
private fun ClockOverlay(
    clock: ClockSnapshot,
    compact: Boolean,
    options: BuiltInWatchFaceOptions,
    palette: ClockPalette,
    typeface: WatchFaceTypeface,
    chargingSnapshot: ChargingSnapshot,
    showChargingPowerText: Boolean,
    fallbackTitle: String? = null,
    fallbackSubtitle: String? = null
) {
    val effectivePalette = resolveClockTextPalette(options, palette)
    val chargingTextSnapshot = if (showChargingPowerText) chargingSnapshot else ChargingSnapshot()
    if (options.clockStyle == WatchFaceClockStyle.MD3E_CLOCK && !compact) {
        Md3eClockOverlay(
            clock = clock,
            options = options,
            palette = effectivePalette,
            typeface = typeface,
            chargingSnapshot = chargingTextSnapshot,
            fallbackTitle = fallbackTitle,
            fallbackSubtitle = fallbackSubtitle
        )
        return
    }
    if (options.clockStyle == WatchFaceClockStyle.MD3_ANALOG && !compact) {
        Md3AnalogClockOverlay(
            clock = clock,
            options = options,
            palette = effectivePalette,
            typeface = typeface,
            chargingSnapshot = chargingTextSnapshot,
            fallbackTitle = fallbackTitle,
            fallbackSubtitle = fallbackSubtitle
        )
        return
    }
    if (options.clockStyle == WatchFaceClockStyle.APPLE_WATCH && !compact) {
        AppleWatchClockOverlay(
            clock = clock,
            options = options,
            palette = effectivePalette,
            typeface = typeface,
            chargingSnapshot = chargingTextSnapshot,
            fallbackTitle = fallbackTitle,
            fallbackSubtitle = fallbackSubtitle
        )
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = clockAlignment(options.clockPosition)
    ) {
        Column(horizontalAlignment = horizontalAlignment(options.clockPosition)) {
            Text(
                text = clock.time,
                fontSize = if (compact) 32.sp else options.clockSizeSp.sp,
                fontWeight = if (options.boldClock) FontWeight.Bold else FontWeight.W200,
                fontFamily = typeface.fontFamily,
                color = effectivePalette.timeColor,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(if (compact) 2.dp else 6.dp))
            Text(
                text = clock.date,
                fontSize = if (compact) 10.sp else (options.clockSizeSp * 0.24f).sp,
                fontWeight = FontWeight.W500,
                fontFamily = typeface.fontFamily,
                color = effectivePalette.dateColor
            )
            options.customText.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    fontSize = (options.clockSizeSp * 0.22f).sp,
                    fontWeight = FontWeight.W500,
                    fontFamily = typeface.fontFamily,
                    color = effectivePalette.dateColor
                )
            }
            chargingSpeedLabel(chargingTextSnapshot)?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    fontSize = (options.clockSizeSp * 0.20f).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = typeface.fontFamily,
                    color = Color(0xFFFFD54A)
                )
            }
            if (fallbackTitle != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = fallbackTitle,
                    fontSize = (options.clockSizeSp * 0.28f).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = typeface.fontFamily,
                    color = effectivePalette.timeColor
                )
                fallbackSubtitle?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = it,
                        fontSize = (options.clockSizeSp * 0.18f).sp,
                        fontFamily = typeface.fontFamily,
                        color = effectivePalette.timeColor.copy(alpha = 0.78f)
                    )
                }
            }
        }
    }
}

private fun extractVideoThumbnail(path: String): ImageBitmap? {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return@runCatching null
            val safeFrame = frame.copy(Bitmap.Config.ARGB_8888, false)
            safeFrame.asImageBitmap().also {
                if (!frame.isRecycled) frame.recycle()
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()
}

@Composable
private fun Md3AnalogClockOverlay(
    clock: ClockSnapshot,
    options: BuiltInWatchFaceOptions,
    palette: ClockPalette,
    typeface: WatchFaceTypeface,
    chargingSnapshot: ChargingSnapshot,
    fallbackTitle: String?,
    fallbackSubtitle: String?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            val side = min(size.width, size.height)
            val radius = side * 0.41f
            val center = Offset(size.width / 2f, size.height / 2f - side * 0.02f)
            val primary = palette.timeColor
            val secondary = palette.dateColor
            val strokeWidth = 2.dp.toPx()

            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = radius + 16.dp.toPx(),
                center = center
            )
            drawCircle(
                color = primary.copy(alpha = 0.26f),
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            repeat(60) { index ->
                val angle = Math.toRadians(index * 6.0 - 90.0)
                val tickLength = if (index % 5 == 0) 12.dp.toPx() else 5.dp.toPx()
                val start = Offset(
                    x = center.x + cos(angle).toFloat() * (radius - tickLength),
                    y = center.y + sin(angle).toFloat() * (radius - tickLength)
                )
                val end = Offset(
                    x = center.x + cos(angle).toFloat() * radius,
                    y = center.y + sin(angle).toFloat() * radius
                )
                drawLine(
                    color = if (index % 5 == 0) primary.copy(alpha = 0.78f) else secondary.copy(alpha = 0.30f),
                    start = start,
                    end = end,
                    strokeWidth = if (index % 5 == 0) 2.2.dp.toPx() else 1.dp.toPx()
                )
            }

            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = primary.toArgb()
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = side * 0.075f
                    this.typeface = typeface.androidTypeface ?: AndroidTypeface.create(AndroidTypeface.DEFAULT, AndroidTypeface.NORMAL)
                }
                val textRadius = radius - 34.dp.toPx()
                for (number in 1..12) {
                    val angle = Math.toRadians(number * 30.0 - 90.0)
                    val x = center.x + cos(angle).toFloat() * textRadius
                    val y = center.y + sin(angle).toFloat() * textRadius - (paint.descent() + paint.ascent()) / 2f
                    canvas.nativeCanvas.drawText(number.toString(), x, y, paint)
                }
            }

            val minuteAngle = clock.minute * 6f + clock.second * 0.1f - 90f
            val hourAngle = ((clock.hour % 12) + clock.minute / 60f) * 30f - 90f
            drawHand(center, hourAngle, radius * 0.48f, 7.dp.toPx(), primary)
            drawHand(center, minuteAngle, radius * 0.68f, 4.dp.toPx(), primary)
            if (options.showSeconds) {
                val secondAngle = clock.second * 6f - 90f
                drawHand(center, secondAngle, radius * 0.76f, 2.dp.toPx(), Color(0xFFFFD54A))
            }
            drawCircle(color = primary, radius = 6.dp.toPx(), center = center)
            drawCircle(color = Color.Black.copy(alpha = 0.52f), radius = 2.5.dp.toPx(), center = center)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = clock.date,
                color = palette.dateColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = typeface.fontFamily
            )
            options.customText.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    color = palette.timeColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = typeface.fontFamily
                )
            }
            chargingSpeedLabel(chargingSnapshot)?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    color = Color(0xFFFFD54A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = typeface.fontFamily
                )
            }
            if (fallbackTitle != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = fallbackTitle,
                    color = palette.timeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = typeface.fontFamily
                )
                fallbackSubtitle?.let {
                    Text(
                        text = it,
                        color = palette.timeColor.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                        fontFamily = typeface.fontFamily
                    )
                }
            }
        }
    }
}

@Composable
private fun AppleWatchClockOverlay(
    clock: ClockSnapshot,
    options: BuiltInWatchFaceOptions,
    palette: ClockPalette,
    typeface: WatchFaceTypeface,
    chargingSnapshot: ChargingSnapshot,
    fallbackTitle: String?,
    fallbackSubtitle: String?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val side = min(size.width, size.height)
            val inset = side * 0.075f
            val panelRadius = side * 0.075f
            val accent = palette.seedColor
            val quietText = palette.dateColor
            val ringStroke = side * 0.018f
            val ringSize = side * 0.38f
            val left = inset
            val right = size.width - inset - side * 0.28f
            val top = inset
            val bottom = size.height - inset - side * 0.16f

            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(left, top),
                size = Size(side * 0.30f, side * 0.16f),
                cornerRadius = CornerRadius(panelRadius, panelRadius)
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.24f),
                topLeft = Offset(right, top),
                size = Size(side * 0.28f, side * 0.16f),
                cornerRadius = CornerRadius(panelRadius, panelRadius)
            )
            drawRoundRect(
                color = quietText.copy(alpha = 0.18f),
                topLeft = Offset(left, bottom),
                size = Size(side * 0.36f, side * 0.15f),
                cornerRadius = CornerRadius(panelRadius, panelRadius)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.08f),
                topLeft = Offset(size.width - inset - side * 0.34f, bottom),
                size = Size(side * 0.34f, side * 0.15f),
                cornerRadius = CornerRadius(panelRadius, panelRadius)
            )

            val ringTopLeft = Offset(size.width / 2f - ringSize / 2f, size.height / 2f - ringSize / 2f)
            drawArc(
                color = Color.White.copy(alpha = 0.14f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = ringTopLeft,
                size = Size(ringSize, ringSize),
                style = Stroke(width = ringStroke)
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = ((clock.minute + clock.second / 60f) / 60f) * 360f,
                useCenter = false,
                topLeft = ringTopLeft,
                size = Size(ringSize, ringSize),
                style = Stroke(width = ringStroke, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = clock.time,
                color = palette.timeColor,
                fontSize = (options.clockSizeSp * 0.94f).sp,
                fontWeight = FontWeight.W300,
                fontFamily = typeface.fontFamily,
                letterSpacing = 0.sp
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = clock.date,
                color = palette.dateColor,
                fontSize = (options.clockSizeSp * 0.20f).sp,
                fontWeight = FontWeight.Medium,
                fontFamily = typeface.fontFamily
            )
            options.customText.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = it,
                    color = palette.timeColor,
                    fontSize = (options.clockSizeSp * 0.20f).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = typeface.fontFamily
                )
            }
            chargingSpeedLabel(chargingSnapshot)?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    color = Color(0xFF9BE15D),
                    fontSize = (options.clockSizeSp * 0.18f).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = typeface.fontFamily
                )
            }
            fallbackTitle?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = palette.timeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = typeface.fontFamily
                )
                fallbackSubtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        color = palette.timeColor.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                        fontFamily = typeface.fontFamily
                    )
                }
            }
        }
    }
}

@Composable
private fun Md3eClockOverlay(
    clock: ClockSnapshot,
    options: BuiltInWatchFaceOptions,
    palette: ClockPalette,
    typeface: WatchFaceTypeface,
    chargingSnapshot: ChargingSnapshot,
    fallbackTitle: String?,
    fallbackSubtitle: String?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val side = min(size.width, size.height)
            val radius = side * 0.42f
            val center = Offset(size.width / 2f, size.height / 2f + side * 0.015f)
            val blobPath = md3eShapePath(center, radius, options.md3eShape)
            val colors = resolveMd3ePalette(options, palette)

            drawPath(path = blobPath, color = colors.faceColor)

            drawIntoCanvas { canvas ->
                val selectedTypeface = typeface.androidTypeface
                    ?: AndroidTypeface.create(AndroidTypeface.DEFAULT, AndroidTypeface.BOLD)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = colors.textColor.toArgb()
                    textSize = side * 0.076f
                    textAlign = android.graphics.Paint.Align.CENTER
                    this.typeface = selectedTypeface
                }
                val dateText = "${clock.weekdayShort.ifBlank { "\u5468\u4E00" }} ${clock.dayOfMonth}"
                val x = center.x
                val y = center.y - radius * 0.70f - (paint.descent() + paint.ascent()) / 2f
                canvas.nativeCanvas.drawText(dateText, x, y, paint)
            }

            val hourAngle = ((clock.hour % 12) + clock.minute / 60f) * 30f - 90f
            val minuteAngle = (clock.minute + clock.second / 60f) * 6f - 90f
            drawMd3eHand(center, hourAngle, radius * 0.43f, radius * 0.145f, colors.hourColor)
            drawMd3eHand(center, minuteAngle, radius * 0.58f, radius * 0.145f, colors.minuteColor)

            val dotAngle = Math.toRadians((clock.second * 6f - 90f).toDouble())
            drawCircle(
                color = colors.secondColor,
                radius = radius * 0.095f,
                center = Offset(
                    x = center.x + cos(dotAngle).toFloat() * radius * 0.78f,
                    y = center.y + sin(dotAngle).toFloat() * radius * 0.78f
                )
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            options.customText.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = palette.timeColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = typeface.fontFamily
                )
            }
            chargingSpeedLabel(chargingSnapshot)?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    color = Color(0xFFFFD54A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = typeface.fontFamily
                )
            }
            fallbackTitle?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    color = palette.timeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = typeface.fontFamily
                )
                fallbackSubtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        color = palette.timeColor.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                        fontFamily = typeface.fontFamily
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHand(
    center: Offset,
    angleDegrees: Float,
    length: Float,
    strokeWidth: Float,
    color: Color
) {
    val angle = Math.toRadians(angleDegrees.toDouble())
    drawLine(
        color = color,
        start = center,
        end = Offset(
            x = center.x + cos(angle).toFloat() * length,
            y = center.y + sin(angle).toFloat() * length
        ),
        strokeWidth = strokeWidth
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMd3eHand(
    center: Offset,
    angleDegrees: Float,
    length: Float,
    strokeWidth: Float,
    color: Color
) {
    val angle = Math.toRadians(angleDegrees.toDouble())
    drawLine(
        color = color,
        start = center,
        end = Offset(
            x = center.x + cos(angle).toFloat() * length,
            y = center.y + sin(angle).toFloat() * length
        ),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

private fun md3eShapePath(center: Offset, radius: Float, shape: WatchFaceMd3eShape): Path {
    val polygon = when (shape) {
        WatchFaceMd3eShape.COOKIE -> RoundedPolygon.star(
            numVerticesPerRadius = 12,
            radius = radius,
            innerRadius = radius * 0.92f,
            rounding = CornerRounding(radius * 0.18f, smoothing = 0.45f),
            innerRounding = CornerRounding(radius * 0.18f, smoothing = 0.45f),
            centerX = center.x,
            centerY = center.y
        )
        WatchFaceMd3eShape.SOFT_STAR -> RoundedPolygon.star(
            numVerticesPerRadius = 8,
            radius = radius,
            innerRadius = radius * 0.74f,
            rounding = CornerRounding(radius * 0.22f, smoothing = 0.55f),
            innerRounding = CornerRounding(radius * 0.18f, smoothing = 0.55f),
            centerX = center.x,
            centerY = center.y
        )
        WatchFaceMd3eShape.CIRCLE -> RoundedPolygon.circle(
            numVertices = 18,
            radius = radius,
            centerX = center.x,
            centerY = center.y
        )
    }
    return polygon.toPath().asComposePath()
}

@Composable
private fun rememberClockSnapshot(showSeconds: Boolean): ClockSnapshot {
    var snapshot by remember {
        mutableStateOf(ClockSnapshot(time = "--:--", date = ""))
    }
    LaunchedEffect(showSeconds) {
        val locale = Locale.getDefault()
        val timeFmt = SimpleDateFormat(if (showSeconds) "HH:mm:ss" else "HH:mm", locale)
        val dateFmt = SimpleDateFormat(
            if (locale.language.startsWith("zh")) "M\u6708d\u65E5 EEEE" else "MMM d, EEEE",
            locale
        )
        while (true) {
            val now = Date()
            val calendar = Calendar.getInstance().apply { time = now }
            snapshot = ClockSnapshot(
                time = timeFmt.format(now),
                date = dateFmt.format(now),
                hour = calendar.get(Calendar.HOUR_OF_DAY),
                minute = calendar.get(Calendar.MINUTE),
                second = calendar.get(Calendar.SECOND),
                dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH),
                weekdayShort = weekdayShortLabel(calendar, locale)
            )
            delay(1000)
        }
    }
    return snapshot
}

private fun formatClockForOptions(clock: ClockSnapshot, showSeconds: Boolean): ClockSnapshot {
    val text = if (showSeconds) {
        "%02d:%02d:%02d".format(Locale.US, clock.hour, clock.minute, clock.second)
    } else {
        "%02d:%02d".format(Locale.US, clock.hour, clock.minute)
    }
    return clock.copy(time = text)
}

private fun weekdayShortLabel(calendar: Calendar, locale: Locale): String {
    val day = calendar.get(Calendar.DAY_OF_WEEK)
    if (locale.language.startsWith("zh")) {
        return when (day) {
            Calendar.MONDAY -> "\u5468\u4E00"
            Calendar.TUESDAY -> "\u5468\u4E8C"
            Calendar.WEDNESDAY -> "\u5468\u4E09"
            Calendar.THURSDAY -> "\u5468\u56DB"
            Calendar.FRIDAY -> "\u5468\u4E94"
            Calendar.SATURDAY -> "\u5468\u516D"
            else -> "\u5468\u65E5"
        }
    }
    return calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale).orEmpty()
}

@Composable
private fun rememberWatchFaceTypeface(fontPath: String?): WatchFaceTypeface {
    return remember(fontPath) {
        val file = fontPath?.takeIf { it.isNotBlank() }?.let(::File)
        val androidTypeface = runCatching {
            file?.takeIf { it.exists() }?.let(AndroidTypeface::createFromFile)
        }.getOrNull()
        WatchFaceTypeface(
            androidTypeface = androidTypeface,
            fontFamily = androidTypeface?.let { FontFamily(ComposeTypeface(it)) }
        )
    }
}

@Composable
private fun rememberChargingSnapshot(): ChargingSnapshot {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(ChargingSnapshot()) }

    DisposableEffect(context) {
        fun readSnapshot(intent: Intent?): ChargingSnapshot {
            if (intent == null) return ChargingSnapshot()
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0
            if (!charging) return ChargingSnapshot(charging = false)

            val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val batteryManager = context.getSystemService(BatteryManager::class.java)
            val currentMicroAmp = runCatching {
                batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            }.getOrNull()
            val watts = if (voltageMv > 0 && currentMicroAmp != null && currentMicroAmp != Long.MIN_VALUE) {
                (abs(currentMicroAmp).toFloat() * voltageMv.toFloat() / 1_000_000_000f)
                    .takeIf { it.isFinite() && it >= 0.1f }
            } else {
                null
            }
            return ChargingSnapshot(charging = true, speedWatts = watts)
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        snapshot = readSnapshot(context.registerReceiver(null, filter))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                snapshot = readSnapshot(intent)
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return snapshot
}

private fun chargingSpeedLabel(snapshot: ChargingSnapshot): String? {
    if (!snapshot.charging) return null
    val speed = snapshot.speedWatts ?: return "\u6B63\u5728\u5145\u7535"
    return "\u5145\u7535 %.1fW".format(Locale.US, speed)
}

@Composable
fun rememberClockPaletteForPreview(
    watchFaceId: String,
    photoPath: String?,
    clockPosition: WatchClockPosition
): ClockPalette = rememberClockPalette(
    watchFaceId = watchFaceId,
    photoPath = photoPath,
    videoPath = null,
    clockPosition = clockPosition,
    videoClockColorMode = WatchClockColorMode.AUTO
)

fun appListSeedColorFromOptions(options: BuiltInWatchFaceOptions): Color {
    return when (options.clockStyle) {
        WatchFaceClockStyle.MD3E_CLOCK -> {
            if (options.md3eAutoColors) {
                Color(0xFF806EA4)
            } else {
                Color(options.md3eFaceColorArgb)
            }
        }
        else -> {
            if (options.useThemeTextColor) {
                Color(0xFF7BE8FF)
            } else {
                Color(options.textColorArgb)
            }
        }
    }
}

@Composable
fun rememberAppListSeedColor(
    watchFaceId: String,
    photoPath: String?,
    videoPath: String?,
    photoOptions: BuiltInWatchFaceOptions,
    videoOptions: BuiltInWatchFaceOptions,
    enabled: Boolean = true
): Color {
    if (!enabled) return Color.Transparent
    val activeOptions = when (watchFaceId) {
        BUILT_IN_PHOTO_WATCHFACE_ID -> photoOptions
        BUILT_IN_VIDEO_WATCHFACE_ID -> videoOptions
        else -> BuiltInWatchFaceOptions()
    }
    val needsPalette = when {
        activeOptions.clockStyle == WatchFaceClockStyle.MD3E_CLOCK -> activeOptions.md3eAutoColors
        else -> activeOptions.useThemeTextColor
    }
    if (!needsPalette) return appListSeedColorFromOptions(activeOptions)
    val palette = rememberClockPalette(
        watchFaceId = watchFaceId,
        photoPath = photoPath,
        videoPath = videoPath,
        clockPosition = activeOptions.clockPosition,
        videoClockColorMode = videoOptions.clockColorMode
    )
    return when {
        activeOptions.clockStyle == WatchFaceClockStyle.MD3E_CLOCK && activeOptions.md3eAutoColors -> palette.seedColor
        activeOptions.clockStyle != WatchFaceClockStyle.MD3E_CLOCK && activeOptions.useThemeTextColor -> palette.seedColor
        else -> appListSeedColorFromOptions(activeOptions)
    }
}

private fun resolveClockTextPalette(
    options: BuiltInWatchFaceOptions,
    palette: ClockPalette
): ClockPalette {
    if (options.useThemeTextColor || options.clockStyle == WatchFaceClockStyle.MD3E_CLOCK) {
        return palette
    }
    val textColor = Color(options.textColorArgb)
    return ClockPalette(
        timeColor = textColor,
        dateColor = textColor.copy(alpha = 0.82f),
        seedColor = textColor
    )
}

private fun resolveMd3ePalette(
    options: BuiltInWatchFaceOptions,
    palette: ClockPalette
): Md3eColorPalette {
    if (!options.md3eAutoColors) {
        return Md3eColorPalette(
            faceColor = Color(options.md3eFaceColorArgb),
            textColor = Color(options.md3eTextColorArgb),
            hourColor = Color(options.md3eHourHandColorArgb),
            minuteColor = Color(options.md3eMinuteHandColorArgb),
            secondColor = Color(options.md3eSecondHandColorArgb)
        )
    }
    val seed = palette.seedColor
    val lightFace = mixColors(seed, Color.White, 0.78f)
    val darkText = mixColors(seed, Color(0xFF101318), 0.72f)
    return Md3eColorPalette(
        faceColor = lightFace,
        textColor = darkText,
        hourColor = mixColors(seed, Color(0xFF1F2937), 0.58f),
        minuteColor = mixColors(seed, Color.White, 0.18f),
        secondColor = seed
    )
}

private fun mediaPaletteCacheKey(kind: String, path: String?, variant: String?): String {
    if (path.isNullOrBlank()) return "$kind|empty|${variant.orEmpty()}"
    val file = File(path)
    val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
    val length = runCatching { file.length() }.getOrDefault(0L)
    return "$kind|$path|$lastModified|$length|${variant.orEmpty()}"
}

@Composable
private fun rememberClockPalette(
    watchFaceId: String,
    photoPath: String?,
    videoPath: String?,
    clockPosition: WatchClockPosition,
    videoClockColorMode: WatchClockColorMode
): ClockPalette {
    var palette by remember(watchFaceId, photoPath, videoPath, clockPosition, videoClockColorMode) {
        mutableStateOf(defaultClockPalette(watchFaceId))
    }
    val photoCacheKey = remember(photoPath, clockPosition) { mediaPaletteCacheKey("photo", photoPath, clockPosition.name) }
    val videoCacheKey = remember(videoPath) { mediaPaletteCacheKey("video", videoPath, null) }
    LaunchedEffect(watchFaceId, photoPath, videoPath, clockPosition, videoClockColorMode, photoCacheKey, videoCacheKey) {
        palette = if (watchFaceId == BUILT_IN_PHOTO_WATCHFACE_ID && !photoPath.isNullOrBlank()) {
            val cacheKey = photoCacheKey
            WatchFacePaletteCache.get(cacheKey)
                ?: (sampleClockPalette(photoPath, clockPosition) ?: defaultClockPalette(watchFaceId))
                    .also { WatchFacePaletteCache.put(cacheKey, it) }
        } else if (watchFaceId == BUILT_IN_VIDEO_WATCHFACE_ID) {
            when (videoClockColorMode) {
                WatchClockColorMode.WHITE -> ClockPalette(
                    timeColor = Color.White,
                    dateColor = Color.White.copy(alpha = 0.82f),
                    seedColor = Color(0xFF7BE8FF)
                )
                WatchClockColorMode.BLACK -> ClockPalette(
                    timeColor = Color(0xFF101318),
                    dateColor = Color(0xCC101318),
                    seedColor = Color(0xFF101318)
                )
                WatchClockColorMode.AUTO -> {
                    if (!videoPath.isNullOrBlank()) {
                        val cacheKey = videoCacheKey
                        WatchFacePaletteCache.get(cacheKey)
                            ?: (sampleVideoClockPalette(videoPath) ?: defaultClockPalette(watchFaceId))
                                .also { WatchFacePaletteCache.put(cacheKey, it) }
                    } else {
                        defaultClockPalette(watchFaceId)
                    }
                }
            }
        } else {
            defaultClockPalette(watchFaceId)
        }
    }
    return palette
}

private fun defaultClockPalette(watchFaceId: String): ClockPalette =
    if (watchFaceId == BUILT_IN_PHOTO_WATCHFACE_ID) {
        ClockPalette(
            timeColor = Color.White,
            dateColor = Color.White.copy(alpha = 0.82f),
            seedColor = Color(0xFF7BE8FF)
        )
    } else {
        ClockPalette(
            timeColor = Color.White,
            dateColor = Color(0xFF7BE8FF),
            seedColor = Color(0xFF7BE8FF)
        )
    }

private fun paletteForAverageColor(color: Int): ClockPalette {
    val seed = Color(color)
    val luminance = luminanceOf(color)
    return if (luminance >= 0.58) {
        ClockPalette(
            timeColor = Color(0xFF0F1720),
            dateColor = Color(0xCC0F1720),
            seedColor = seed
        )
    } else {
        ClockPalette(
            timeColor = Color.White,
            dateColor = Color.White.copy(alpha = 0.82f),
            seedColor = seed
        )
    }
}

private fun luminanceOf(color: Int): Double {
    val red = android.graphics.Color.red(color) / 255.0
    val green = android.graphics.Color.green(color) / 255.0
    val blue = android.graphics.Color.blue(color) / 255.0
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

private fun mixColors(from: Color, to: Color, toFraction: Float): Color {
    val t = toFraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = from.alpha + (to.alpha - from.alpha) * t
    )
}

private suspend fun sampleClockPalette(
    path: String,
    position: WatchClockPosition
): ClockPalette? = withContext(Dispatchers.Default) {
    runCatching {
        val file = File(path)
        if (!file.exists()) return@runCatching null
        val options = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
        val xRange = when (position) {
            WatchClockPosition.TOP_LEFT, WatchClockPosition.BOTTOM_LEFT -> 0 until (bitmap.width * 4 / 10).coerceAtLeast(1)
            WatchClockPosition.TOP_RIGHT, WatchClockPosition.BOTTOM_RIGHT -> (bitmap.width * 6 / 10).coerceAtMost(bitmap.width - 1) until bitmap.width
            WatchClockPosition.LEFT_CENTER -> 0 until (bitmap.width * 4 / 10).coerceAtLeast(1)
            WatchClockPosition.RIGHT_CENTER -> (bitmap.width * 6 / 10).coerceAtMost(bitmap.width - 1) until bitmap.width
            WatchClockPosition.CENTER, WatchClockPosition.TOP_CENTER, WatchClockPosition.BOTTOM_CENTER ->
                (bitmap.width * 3 / 10).coerceAtMost(bitmap.width - 1) until (bitmap.width * 7 / 10).coerceAtLeast(1)
        }
        val yRange = when (position) {
            WatchClockPosition.TOP_LEFT, WatchClockPosition.TOP_RIGHT, WatchClockPosition.TOP_CENTER ->
                0 until (bitmap.height * 4 / 10).coerceAtLeast(1)
            WatchClockPosition.BOTTOM_LEFT, WatchClockPosition.BOTTOM_RIGHT, WatchClockPosition.BOTTOM_CENTER ->
                (bitmap.height * 6 / 10).coerceAtMost(bitmap.height - 1) until bitmap.height
            WatchClockPosition.LEFT_CENTER, WatchClockPosition.RIGHT_CENTER -> (bitmap.height * 3 / 10).coerceAtMost(bitmap.height - 1) until (bitmap.height * 7 / 10).coerceAtLeast(1)
            WatchClockPosition.CENTER -> (bitmap.height * 3 / 10).coerceAtMost(bitmap.height - 1) until (bitmap.height * 7 / 10).coerceAtLeast(1)
        }
        var luminanceSum = 0.0
        var count = 0
        for (x in xRange step 2) {
            for (y in yRange step 2) {
                val color = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
                val red = android.graphics.Color.red(color) / 255.0
                val green = android.graphics.Color.green(color) / 255.0
                val blue = android.graphics.Color.blue(color) / 255.0
                luminanceSum += 0.2126 * red + 0.7152 * green + 0.0722 * blue
                count++
            }
        }
        val seedColor = averageBitmapColor(bitmap, xRange, yRange)
        bitmap.recycle()
        val luminance = if (count == 0) 0.0 else luminanceSum / count
        if (luminance >= 0.58) {
            ClockPalette(
                timeColor = Color(0xFF0F1720),
                dateColor = Color(0xCC0F1720),
                seedColor = Color(seedColor)
            )
        } else {
            ClockPalette(
                timeColor = Color.White,
                dateColor = Color.White.copy(alpha = 0.82f),
                seedColor = Color(seedColor)
            )
        }
    }.getOrNull()
}

private suspend fun sampleVideoClockPalette(path: String): ClockPalette? = withContext(Dispatchers.Default) {
    runCatching {
        val file = File(path)
        if (!file.exists()) return@runCatching null
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(path)
            retriever.getScaledFrameAtTime(
                0,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                144,
                144
            )
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } finally {
            runCatching { retriever.release() }
        } ?: return@runCatching null
        try {
            val xRange = 0 until frame.width
            val yRange = 0 until frame.height
            paletteForAverageColor(averageBitmapColor(frame, xRange, yRange))
        } finally {
            frame.recycle()
        }
    }.getOrNull()
}

private fun averageBitmapColor(bitmap: Bitmap, xRange: IntRange, yRange: IntRange): Int {
    var redSum = 0L
    var greenSum = 0L
    var blueSum = 0L
    var count = 0L
    val xStep = maxOf(1, xRange.count() / 36)
    val yStep = maxOf(1, yRange.count() / 36)
    for (x in xRange step xStep) {
        for (y in yRange step yStep) {
            val color = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            val alpha = android.graphics.Color.alpha(color)
            if (alpha < 24) continue
            redSum += android.graphics.Color.red(color).toLong()
            greenSum += android.graphics.Color.green(color).toLong()
            blueSum += android.graphics.Color.blue(color).toLong()
            count++
        }
    }
    if (count == 0L) return 0xFF7BE8FF.toInt()
    return android.graphics.Color.rgb(
        (redSum / count).toInt().coerceIn(0, 255),
        (greenSum / count).toInt().coerceIn(0, 255),
        (blueSum / count).toInt().coerceIn(0, 255)
    )
}

private fun decodeWatchFacePhoto(path: String): ImageBitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize >= 1024 && bounds.outHeight / sampleSize >= 1024) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    }.getOrNull()
}

private fun clockAlignment(position: WatchClockPosition): Alignment = when (position) {
    WatchClockPosition.CENTER -> Alignment.Center
    WatchClockPosition.TOP_CENTER -> Alignment.TopCenter
    WatchClockPosition.BOTTOM_CENTER -> Alignment.BottomCenter
    WatchClockPosition.LEFT_CENTER -> Alignment.CenterStart
    WatchClockPosition.RIGHT_CENTER -> Alignment.CenterEnd
    WatchClockPosition.TOP_LEFT -> Alignment.TopStart
    WatchClockPosition.TOP_RIGHT -> Alignment.TopEnd
    WatchClockPosition.BOTTOM_LEFT -> Alignment.BottomStart
    WatchClockPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
}

private fun horizontalAlignment(position: WatchClockPosition): Alignment.Horizontal = when (position) {
    WatchClockPosition.CENTER, WatchClockPosition.TOP_CENTER, WatchClockPosition.BOTTOM_CENTER -> Alignment.CenterHorizontally
    WatchClockPosition.RIGHT_CENTER -> Alignment.End
    WatchClockPosition.TOP_RIGHT, WatchClockPosition.BOTTOM_RIGHT -> Alignment.End
    else -> Alignment.Start
}

private class InternalLoopingVideoView(context: Context) : FrameLayout(context) {
    private val videoView = VideoView(context)
    private val placeholder = TextView(context).apply {
        text = ""
        setTextColor(android.graphics.Color.WHITE)
        textSize = 15f
        gravity = android.view.Gravity.CENTER
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
    private var currentPath: String? = null
    private var shouldPlay: Boolean = true
    private var fillScreen: Boolean = true
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            videoView.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        }
        addView(
            videoView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            }
        )
        addView(
            placeholder,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        videoView.setOnPreparedListener { mediaPlayer ->
            videoWidth = mediaPlayer.videoWidth
            videoHeight = mediaPlayer.videoHeight
            mediaPlayer.isLooping = true
            mediaPlayer.setVolume(0f, 0f)
            updateScale()
            if (shouldPlay) {
                videoView.start()
            }
        }
        videoView.setOnErrorListener { _, _, _ -> true }
    }

    fun bind(path: String?, play: Boolean, fillScreen: Boolean) {
        val pathChanged = path != currentPath
        val playChanged = shouldPlay != play
        val fillChanged = this.fillScreen != fillScreen
        shouldPlay = play
        this.fillScreen = fillScreen
        placeholder.visibility = if (path == null) VISIBLE else GONE
        videoView.visibility = if (path == null) INVISIBLE else VISIBLE
        if (pathChanged) {
            currentPath = path
            videoWidth = 0
            videoHeight = 0
            if (path == null) {
                videoView.stopPlayback()
                runCatching { videoView.setVideoURI(null) }
            } else {
                videoView.setVideoPath(path)
            }
        }
        if (pathChanged || fillChanged) {
            updateScale()
        }
        if (path == null) return
        if (playChanged && play) {
            if (!videoView.isPlaying) videoView.start()
        } else if (playChanged && videoView.isPlaying) {
            videoView.pause()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScale()
    }

    private fun updateScale() {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) {
            videoView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            }
            videoView.scaleX = 1f
            videoView.scaleY = 1f
            videoView.translationX = 0f
            videoView.translationY = 0f
            return
        }

        val containerWidth = width.toFloat()
        val containerHeight = height.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val containerAspect = containerWidth / containerHeight

        val (targetWidth, targetHeight) = if (fillScreen) {
            if (videoAspect > containerAspect) {
                (containerHeight * videoAspect) to containerHeight
            } else {
                containerWidth to (containerWidth / videoAspect)
            }
        } else {
            if (videoAspect > containerAspect) {
                containerWidth to (containerWidth / videoAspect)
            } else {
                (containerHeight * videoAspect) to containerHeight
            }
        }

        val widthPx = targetWidth.toInt().coerceAtLeast(1)
        val heightPx = targetHeight.toInt().coerceAtLeast(1)
        val layoutParams = (videoView.layoutParams as? LayoutParams)
            ?: LayoutParams(widthPx, heightPx)
        if (layoutParams.width != widthPx || layoutParams.height != heightPx || layoutParams.gravity != android.view.Gravity.CENTER) {
            layoutParams.width = widthPx
            layoutParams.height = heightPx
            layoutParams.gravity = android.view.Gravity.CENTER
            videoView.layoutParams = layoutParams
        }
        videoView.scaleX = 1f
        videoView.scaleY = 1f
        videoView.translationX = 0f
        videoView.translationY = 0f
    }

    override fun onDetachedFromWindow() {
        runCatching { videoView.stopPlayback() }
        super.onDetachedFromWindow()
    }
}
