package com.flue.launcher.ui.controlcenter

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.flue.launcher.service.WLauncherNotificationListener
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.WatchColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class ControlCenterMediaState(
    val controller: MediaController? = null,
    val title: String = "未在播放",
    val artist: String = "打开音乐应用后会显示控制",
    val artwork: ImageBitmap? = null,
    val artworkColor: Color = Color(0xFF2F3B45),
    val playing: Boolean = false,
    val progress: Float = 0f,
    val appPackage: String? = null
)

@Composable
fun ControlCenterLayer(
    leftSafeInsetDp: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val launcherStyle = LauncherTheme.style
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    var mediaVolume by remember(context) {
        mutableFloatStateOf(readMusicVolume(audioManager))
    }
    var brightness by remember(context) {
        mutableFloatStateOf(readBrightness(context))
    }
    var batteryLevel by remember(context) { mutableIntStateOf(0) }
    var timeText by remember { mutableStateOf("--:--") }
    val mediaState = rememberMediaState(context)
    val leftSafeInset = leftSafeInsetDp.coerceIn(0, 128).dp

    LaunchedEffect(context) {
        while (true) {
            batteryLevel = context.getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.coerceIn(0, 100) ?: 0
            timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            mediaVolume = readMusicVolume(audioManager)
            brightness = readBrightness(context)
            delay(1000)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(40.dp))
            .background(launcherStyle.screenBackground)
            .padding(start = leftSafeInset)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BatteryFull, null, tint = launcherStyle.titleColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("$batteryLevel%", color = launcherStyle.titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(timeText, color = launcherStyle.titleColor, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ControlToggle(
                        icon = Icons.Filled.Wifi,
                        label = "WiFi",
                        active = true,
                        onClick = { openInternetPanel(context) },
                        onLongClick = { openSettings(context, Settings.ACTION_WIFI_SETTINGS) }
                    )
                    ControlToggle(
                        icon = Icons.Filled.Bluetooth,
                        label = "蓝牙",
                        active = isBluetoothLikelyEnabled(context),
                        onClick = { toggleBluetoothBestEffort(context) },
                        onLongClick = { openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
                    )
                    ControlToggle(
                        icon = Icons.Filled.NetworkCell,
                        label = "流量",
                        active = true,
                        onClick = { openInternetPanel(context) },
                        onLongClick = { openSettings(context, Settings.ACTION_WIRELESS_SETTINGS) }
                    )
                    ControlToggle(
                        icon = Icons.Filled.DoNotDisturbOn,
                        label = "勿扰",
                        active = isDndEnabled(context),
                        onClick = { toggleDnd(context) },
                        onLongClick = { openSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
                    )
                }

                ControlSliderCard(
                    icon = Icons.Filled.VolumeUp,
                    label = "音量",
                    value = mediaVolume,
                    onValueChange = { value ->
                        mediaVolume = value
                        setMusicVolume(audioManager, value)
                    }
                )
                ControlSliderCard(
                    icon = Icons.Filled.Brightness6,
                    label = "亮度",
                    value = brightness,
                    onValueChange = { value ->
                        brightness = value
                        setBrightness(context, value)
                    }
                )
            }

            MediaControlCard(mediaState = mediaState)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ControlToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val targetScale by animateFloatAsState(if (active) 1f else 0.96f, label = "control_toggle_scale")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .graphicsLayer {
                    scaleX = targetScale
                    scaleY = targetScale
                }
                .clip(CircleShape)
                .background(if (active) Color.White else Color(0xFF2C2C2E))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color.Black else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(label, color = WatchColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ControlSliderCard(
    icon: ImageVector,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val launcherStyle = LauncherTheme.style
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(launcherStyle.cardColor)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = launcherStyle.bodyColor, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = launcherStyle.bodyColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f)
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MediaControlCard(mediaState: ControlCenterMediaState) {
    val bg = mediaState.artworkColor
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(bg.copy(alpha = 0.88f))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                if (mediaState.artwork != null) {
                    Image(
                        bitmap = mediaState.artwork,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    mediaState.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    mediaState.artist,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.22f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(mediaState.progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MediaButton(Icons.Filled.SkipPrevious) {
                        mediaState.controller?.transportControls?.skipToPrevious()
                    }
                    MediaButton(if (mediaState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow) {
                        val controls = mediaState.controller?.transportControls
                        if (mediaState.playing) controls?.pause() else controls?.play()
                    }
                    MediaButton(Icons.Filled.SkipNext) {
                        mediaState.controller?.transportControls?.skipToNext()
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun rememberMediaState(context: Context): ControlCenterMediaState {
    var state by remember(context) { mutableStateOf(ControlCenterMediaState()) }
    LaunchedEffect(context) {
        while (true) {
            state = readMediaState(context)
            delay(1200)
        }
    }
    return state
}

private fun readMediaState(context: Context): ControlCenterMediaState {
    val manager = context.getSystemService(MediaSessionManager::class.java) ?: return ControlCenterMediaState()
    val component = ComponentName(context, WLauncherNotificationListener::class.java)
    val controller = runCatching {
        manager.getActiveSessions(component).firstOrNull()
    }.getOrNull() ?: return ControlCenterMediaState()
    val metadata = controller.metadata
    val playbackState = controller.playbackState
    val artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        ?: loadAppIconBitmap(context, controller.packageName)
    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: 0L
    val position = playbackState?.position?.coerceAtLeast(0L) ?: 0L
    val progress = if (duration > 0L) position.toFloat() / duration else 0f
    return ControlCenterMediaState(
        controller = controller,
        title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "未在播放",
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: controller.packageName.substringAfterLast('.'),
        artwork = artworkBitmap?.asImageBitmap(),
        artworkColor = artworkBitmap?.let(::averageBitmapColor) ?: Color(0xFF2F3B45),
        playing = playbackState?.state == PlaybackState.STATE_PLAYING,
        progress = progress,
        appPackage = controller.packageName
    )
}

private fun loadAppIconBitmap(context: Context, packageName: String): Bitmap? {
    return runCatching {
        context.packageManager.getApplicationIcon(packageName).toBitmap(width = 128, height = 128)
    }.getOrNull()
}

private fun averageBitmapColor(bitmap: Bitmap): Color {
    val sampleWidth = 18
    val sampleHeight = 18
    val scaled = Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    for (x in 0 until sampleWidth) {
        for (y in 0 until sampleHeight) {
            val pixel = scaled.getPixel(x, y)
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            count++
        }
    }
    if (scaled != bitmap) scaled.recycle()
    val r = ((red / count) * 0.72f).roundToInt().coerceIn(0, 255)
    val g = ((green / count) * 0.72f).roundToInt().coerceIn(0, 255)
    val b = ((blue / count) * 0.72f).roundToInt().coerceIn(0, 255)
    return Color(android.graphics.Color.rgb(r, g, b))
}

private fun readMusicVolume(audioManager: AudioManager?): Float {
    if (audioManager == null) return 0f
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun setMusicVolume(audioManager: AudioManager?, value: Float) {
    if (audioManager == null) return
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0f, 1f) * max).roundToInt(), 0)
}

private fun readBrightness(context: Context): Float {
    val value = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return value.coerceIn(0, 255) / 255f
}

private fun setBrightness(context: Context, value: Float) {
    if (!Settings.System.canWrite(context)) {
        Toast.makeText(context, "需要修改系统设置权限", Toast.LENGTH_SHORT).show()
        openSettings(
            context,
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        return
    }
    Settings.System.putInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS,
        (value.coerceIn(0f, 1f) * 255).roundToInt().coerceIn(1, 255)
    )
}

private fun isBluetoothLikelyEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)
}

private fun toggleBluetoothBestEffort(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        Toast.makeText(context, "需要蓝牙权限，已打开蓝牙设置", Toast.LENGTH_SHORT).show()
        openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
        return
    }
    @Suppress("DEPRECATION")
    val changed = runCatching {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@runCatching false
        if (adapter.isEnabled) adapter.disable() else adapter.enable()
    }.getOrDefault(false)
    if (!changed) {
        Toast.makeText(context, "系统限制直接切换蓝牙，已打开设置", Toast.LENGTH_SHORT).show()
        openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
    }
}

private fun isDndEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
}

private fun toggleDnd(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (!manager.isNotificationPolicyAccessGranted) {
        Toast.makeText(context, "需要勿扰模式访问权限", Toast.LENGTH_SHORT).show()
        openSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        return
    }
    val next = if (isDndEnabled(context)) {
        NotificationManager.INTERRUPTION_FILTER_ALL
    } else {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY
    }
    manager.setInterruptionFilter(next)
}

private fun openInternetPanel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        openSettings(context, Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
    } else {
        openSettings(context, Settings.ACTION_WIRELESS_SETTINGS)
    }
}

private fun openSettings(context: Context, action: String, data: Uri? = null) {
    runCatching {
        context.startActivity(
            Intent(action).apply {
                data?.let { setData(it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.onFailure {
        Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
    }
}
