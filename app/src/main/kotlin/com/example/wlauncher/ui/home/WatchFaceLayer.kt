package com.flue.launcher.ui.home

import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ClockSnapshot(
    val time: String,
    val date: String
)

@Composable
fun WatchFaceLayer(
    watchFaceId: String = BUILT_IN_WATCHFACE_ID,
    photoPath: String? = null,
    videoPath: String? = null,
    isFaceVisible: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BuiltInWatchFaceSurface(
        watchFaceId = watchFaceId,
        photoPath = photoPath,
        videoPath = videoPath,
        isFaceVisible = isFaceVisible,
        onLongPress = onLongPress,
        showClock = true,
        modifier = modifier
    )
}

@Composable
fun BuiltInWatchFacePreview(
    watchFaceId: String,
    photoPath: String? = null,
    videoPath: String? = null,
    modifier: Modifier = Modifier,
    showClock: Boolean = false,
    playVideo: Boolean = true
) {
    BuiltInWatchFaceSurface(
        watchFaceId = watchFaceId,
        photoPath = photoPath,
        videoPath = videoPath,
        isFaceVisible = playVideo,
        onLongPress = null,
        showClock = showClock,
        modifier = modifier
    )
}

@Composable
private fun BuiltInWatchFaceSurface(
    watchFaceId: String,
    photoPath: String?,
    videoPath: String?,
    isFaceVisible: Boolean,
    onLongPress: (() -> Unit)?,
    showClock: Boolean,
    modifier: Modifier = Modifier
) {
    val clock = rememberClockSnapshot()
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
        modifier = backgroundModifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (watchFaceId) {
            BUILT_IN_PHOTO_WATCHFACE_ID -> {
                MediaImageBackground(photoPath)
                MediaFallbackOverlay(
                    visible = photoPath.isNullOrBlank(),
                    title = "\u672A\u8BBE\u7F6E\u56FE\u7247",
                    subtitle = "\u5728\u8868\u76D8\u8BBE\u7F6E\u91CC\u9009\u62E9\u4E00\u5F20\u56FE\u7247"
                )
            }

            BUILT_IN_VIDEO_WATCHFACE_ID -> {
                MediaVideoBackground(videoPath, isFaceVisible)
                MediaFallbackOverlay(
                    visible = videoPath.isNullOrBlank(),
                    title = "\u672A\u8BBE\u7F6E\u89C6\u9891",
                    subtitle = "\u5728\u8868\u76D8\u8BBE\u7F6E\u91CC\u9009\u62E9\u4E00\u4E2A\u89C6\u9891"
                )
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF16304A), Color(0xFF0B1322), Color.Black),
                                radius = 900f
                            )
                        )
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.10f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.42f)
                        )
                    )
                )
        )

        if (showClock) {
            ClockOverlay(clock = clock, compact = false)
        }
    }
}

@Composable
private fun MediaImageBackground(path: String?) {
    val filePath = path?.takeIf { File(it).exists() }
    AndroidView(
        factory = { imageContext ->
            ImageView(imageContext).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        update = { imageView ->
            if (filePath == null) {
                imageView.setImageDrawable(null)
            } else {
                imageView.setImageURI(File(filePath).toUri())
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun MediaVideoBackground(path: String?, isFaceVisible: Boolean) {
    val filePath = path?.takeIf { File(it).exists() }
    AndroidView(
        factory = { context -> InternalLoopingVideoView(context) },
        update = { view ->
            view.bind(filePath, isFaceVisible)
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun MediaFallbackOverlay(
    visible: Boolean,
    title: String,
    subtitle: String
) {
    if (!visible) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 28.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ClockOverlay(
    clock: ClockSnapshot,
    compact: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = clock.time,
            fontSize = if (compact) 32.sp else 64.sp,
            fontWeight = FontWeight.W200,
            color = Color.White,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(if (compact) 2.dp else 6.dp))
        Text(
            text = clock.date,
            fontSize = if (compact) 10.sp else 15.sp,
            fontWeight = FontWeight.W500,
            color = Color(0xFF7BE8FF)
        )
    }
}

@Composable
private fun rememberClockSnapshot(): ClockSnapshot {
    var snapshot by remember {
        mutableStateOf(ClockSnapshot(time = "--:--", date = ""))
    }
    LaunchedEffect(Unit) {
        val locale = Locale.getDefault()
        val timeFmt = SimpleDateFormat("HH:mm", locale)
        val dateFmt = SimpleDateFormat(
            if (locale.language.startsWith("zh")) "M\u6708d\u65E5 EEEE" else "MMM d, EEEE",
            locale
        )
        while (true) {
            val now = Date()
            snapshot = ClockSnapshot(
                time = timeFmt.format(now),
                date = dateFmt.format(now)
            )
            delay(1000)
        }
    }
    return snapshot
}

private class InternalLoopingVideoView(context: Context) : FrameLayout(context) {
    private val videoView = VideoView(context)
    private val placeholder = TextView(context).apply {
        text = "\u672A\u8BBE\u7F6E\u89C6\u9891"
        setTextColor(android.graphics.Color.WHITE)
        textSize = 15f
        gravity = android.view.Gravity.CENTER
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
    private var currentPath: String? = null
    private var shouldPlay: Boolean = true

    init {
        addView(
            videoView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(
            placeholder,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        videoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = true
            mediaPlayer.setVolume(0f, 0f)
            if (shouldPlay) {
                videoView.start()
            }
        }
        videoView.setOnErrorListener { _, _, _ -> true }
    }

    fun bind(path: String?, play: Boolean) {
        shouldPlay = play
        placeholder.visibility = if (path == null) VISIBLE else GONE
        if (path != currentPath) {
            currentPath = path
            if (path == null) {
                videoView.stopPlayback()
            } else {
                videoView.setVideoPath(path)
            }
        }
        if (path == null) return
        if (play) {
            if (!videoView.isPlaying) videoView.start()
        } else if (videoView.isPlaying) {
            videoView.pause()
        }
    }

    override fun onDetachedFromWindow() {
        runCatching { videoView.stopPlayback() }
        super.onDetachedFromWindow()
    }
}
