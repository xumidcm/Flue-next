package com.flue.launcher.ui.controlcenter

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import com.flue.launcher.FlueApplication
import com.flue.launcher.R
import com.flue.launcher.service.WLauncherNotificationListener
import com.flue.launcher.ui.common.FrostedHeader
import com.flue.launcher.ui.common.DEFAULT_OVERLAY_MASK_CORNER_RADIUS_DP
import com.flue.launcher.ui.common.WatchBatteryPill
import com.flue.launcher.ui.common.flueOverlayMaskShape
import com.flue.launcher.ui.common.glassSurface
import com.flue.launcher.ui.common.instantPressGesture
import com.flue.launcher.ui.common.rememberFlueElasticScrollState
import com.flue.launcher.ui.common.rememberPressedState
import com.flue.launcher.ui.notification.NotificationGroupUi
import com.flue.launcher.ui.notification.notificationStackBackColor
import com.flue.launcher.ui.settings.miuix.MiuixIcons as FlueMiuixIcons
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.WatchColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val CONTROL_CENTER_OVERSCROLL_LIMIT = 96f
private const val CONTROL_CENTER_OVERSCROLL_RESISTANCE = 0.35f
private const val CONTROL_CENTER_MIN_PRESS_MS = 140L
private const val MEDIA_PROGRESS_COLLAPSED_HEIGHT_DP = 4f
private const val MEDIA_PROGRESS_EXPANDED_HEIGHT_DP = 8f
private const val MEDIA_PROGRESS_HIT_HEIGHT_DP = 24f
private const val MEDIA_PROGRESS_SEEK_SYNC_HOLD_MS = 1200L
private const val MUSIC_TEXT_MARQUEE_INITIAL_DELAY_MS = 650L
private const val MUSIC_TEXT_MARQUEE_END_HOLD_MS = 3_000L
private const val MUSIC_TEXT_MARQUEE_SPEED_DP_PER_SECOND = 34f
private const val CONTROL_NOTIFICATION_STACK_TRANSLATION_DP = 7f
private const val CONTROL_NOTIFICATION_STACK_HORIZONTAL_INSET_DP = 8f
private const val MUSIC_TEXT_FADE_EDGE_DP = 18f
private const val MUSIC_TEXT_FADE_TRANSITION_MS = 80
// Keep a readable gap between the tail of one copy and the head of the next.
// The previous 8dp gap made the copies visually touch on long titles.
private const val MUSIC_TEXT_MARQUEE_SIDE_PADDING_DP = 20f
internal const val CONTROL_TOGGLE_LABEL_MARQUEE_SPEED_DP_PER_SECOND = 14f

private data class ControlCenterMediaState(
    val controller: MediaController? = null,
    val title: String = "",
    val artist: String = "",
    val artwork: ImageBitmap? = null,
    /** Stable metadata identity; prevents a new ImageBitmap wrapper every poll from restarting Crossfade. */
    val artworkIdentity: String? = null,
    val appIcon: ImageBitmap? = null,
    val customActions: List<ControlCenterMediaAction> = emptyList(),
    val artworkColor: Color = Color(0xFF2F3B45),
    val playing: Boolean = false,
    val progress: Float = 0f,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val appPackage: String? = null
)

internal data class ControlCenterCardPlacement(
    val musicInControlCenter: Boolean,
    val musicOnSideScreen: Boolean,
    val notificationInControlCenter: Boolean,
    val notificationOnSideScreen: Boolean,
)

internal fun controlCenterCardPlacement(
    showMusic: Boolean,
    showNotifications: Boolean,
    swapped: Boolean,
): ControlCenterCardPlacement = if (swapped) {
    ControlCenterCardPlacement(
        musicInControlCenter = false,
        musicOnSideScreen = showMusic,
        notificationInControlCenter = showNotifications,
        notificationOnSideScreen = false,
    )
} else {
    ControlCenterCardPlacement(
        musicInControlCenter = showMusic,
        musicOnSideScreen = false,
        notificationInControlCenter = false,
        notificationOnSideScreen = showNotifications,
    )
}

internal fun controlCenterHeaderScrollOffset(
    verticalScrollEnabled: Boolean,
    scrollOffsetPx: Int,
): Float = if (verticalScrollEnabled) scrollOffsetPx.toFloat() else 0f

internal data class MediaCardLayoutSpec(
    val cardHeightDp: Int,
    val paddingDp: Int,
    val artworkSizeDp: Int,
    val artworkGapDp: Int,
    val titleFontSizeSp: Int,
    val artistFontSizeSp: Int,
    val controlButtonDp: Int,
)

internal fun mediaCardLayoutSpec(compact: Boolean): MediaCardLayoutSpec = if (compact) {
    MediaCardLayoutSpec(
        cardHeightDp = 116,
        paddingDp = 10,
        artworkSizeDp = 92,
        artworkGapDp = 8,
        titleFontSizeSp = 13,
        artistFontSizeSp = 10,
        controlButtonDp = 28,
    )
} else {
    MediaCardLayoutSpec(
        cardHeightDp = 156,
        paddingDp = 12,
        artworkSizeDp = 132,
        artworkGapDp = 12,
        titleFontSizeSp = 15,
        artistFontSizeSp = 11,
        controlButtonDp = 36,
    )
}

internal fun sideScreenMediaCardLayoutSpec(): MediaCardLayoutSpec =
    mediaCardLayoutSpec(compact = false)

internal fun sideScreenContentAllowsVerticalScroll(
    showMusicCard: Boolean,
    showNotificationPreview: Boolean,
    expandedShortcutGrid: Boolean = false,
): Boolean = showMusicCard || showNotificationPreview || expandedShortcutGrid

/**
 * 负一屏挂不挂弹性回弹。
 *
 * **必须与 [sideScreenContentAllowsVerticalScroll] 同一个判据。** 这两个判据原先各写
 * 一份（可滚 = `showMusicCard || showNotificationPreview`，回弹 = `showMusicCard`），
 * 差集恰好是「有通知横幅、没有音乐卡」—— 也就是大多数人的默认状态。那种状态下内容
 * 能滚却拿不到 `Modifier.flueOverscroll`，上下滑到边界硬停，没有任何反馈，正是老师报的
 * 「负一屏上下滑动橡皮筋丢失」。
 *
 * 所以这里直接委托，不再留第二份条件可以走偏。
 */
internal fun sideScreenContentUsesElasticOverscroll(
    showMusicCard: Boolean,
    showNotificationPreview: Boolean,
    expandedShortcutGrid: Boolean = false,
): Boolean = sideScreenContentAllowsVerticalScroll(
    showMusicCard = showMusicCard,
    showNotificationPreview = showNotificationPreview,
    expandedShortcutGrid = expandedShortcutGrid,
)

internal data class MediaMarqueeSpec(
    val cycleDistancePx: Int,
    val nextCopyEntryOffsetPx: Int,
    val previousCopyVisiblePxAtEntry: Int,
    val contentWidthPx: Int,
)

internal fun mediaMarqueeSpec(
    textWidthPx: Int,
    viewportWidthPx: Int,
    gapPx: Int,
): MediaMarqueeSpec {
    val textWidth = textWidthPx.coerceAtLeast(0)
    val viewportWidth = viewportWidthPx.coerceAtLeast(0)
    val gap = gapPx.coerceAtLeast(0)
    val cycleDistance = textWidth + gap
    val nextCopyEntryOffset = (cycleDistance - viewportWidth).coerceAtLeast(0)
    return MediaMarqueeSpec(
        cycleDistancePx = cycleDistance,
        nextCopyEntryOffsetPx = nextCopyEntryOffset,
        previousCopyVisiblePxAtEntry = (textWidth - nextCopyEntryOffset).coerceAtLeast(0),
        contentWidthPx = cycleDistance * 2 + viewportWidth,
    )
}

internal fun controlToggleLabelOverflows(slotWidthPx: Float, textWidthPx: Float): Boolean =
    slotWidthPx.isFinite() && textWidthPx.isFinite() && textWidthPx > slotWidthPx

internal fun controlToggleLabelSlotWidthPx(
    buttonWidthPx: Float,
    actualSpacingPx: Float,
    edge: Boolean,
): Float = buttonWidthPx.coerceAtLeast(0f) +
    actualSpacingPx.coerceAtLeast(0f) * if (edge) 0.5f else 1f

private data class ControlCenterMediaAction(
    val action: String,
    val title: String,
    val extras: Bundle? = null,
    val icon: ImageBitmap? = null
)

private data class ConnectivitySnapshot(
    val wifiActive: Boolean = false,
    val cellularActive: Boolean = false
)

private data class BatteryStatusSnapshot(
    val level: Int = 0,
    val charging: Boolean = false
)

@Composable
fun ControlCenterLayer(
    leftSafeInsetPercent: Int,
    showMusicControls: Boolean,
    notificationAccessGranted: Boolean,
    musicTextSwitchAnimation: String = MusicTextSwitchAnimations.DEFAULT_ID,
    showMediaCustomActions: Boolean = true,
    showNotificationCard: Boolean = false,
    notificationGroups: List<NotificationGroupUi> = emptyList(),
    onOpenNotifications: () -> Unit = {},
    onDismissProgressChange: (Float) -> Unit,
    onDismissRelease: (Float, Float, Boolean) -> Unit,
    showPowerButton: Boolean = false,
    onPowerClick: () -> Unit = {},
    glassHighlightEnabled: Boolean = true,
    overlayMaskCornerRadiusDp: Int = DEFAULT_OVERLAY_MASK_CORNER_RADIUS_DP,
    /**
     * 控制中心此刻是否处于打开态。
     *
     * 这一层是**常驻组合**的（靠位移移出屏外，不是条件挂载），所以 `rememberScrollState`
     * 会一直活着 —— 上滑退出后再次下拉，内容还停在上次退出时的滚动位置（老师报的
     * 「应该重新回到控制中心顶上，而不是从退出位置进入」）。拿这个标志在
     * 「不可见 → 可见」那条边上把滚动复位。
     *
     * 刻意选开场而不是收场：收场时退出动画还在跑，那会儿 scrollTo(0) 会让内容一边
     * 滑走一边跳回顶部；开场时这一层还在屏外，复位落在第一帧之前，看不见。
     */
    contentVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnDismissProgressChange by rememberUpdatedState(onDismissProgressChange)
    val currentOnDismissRelease by rememberUpdatedState(onDismissRelease)
    val launcherStyle = LauncherTheme.style
    val roundScreen = com.flue.launcher.ui.common.isFlueScreenRound()
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    var mediaVolume by remember(context) {
        mutableFloatStateOf(readMusicVolume(audioManager))
    }
    var pendingRequestedMediaVolume by remember(context) { mutableStateOf<Float?>(null) }
    var brightness by remember(context) {
        mutableFloatStateOf(readBrightness(context))
    }
    var batteryStatus by remember(context) { mutableStateOf(readBatteryStatus(context)) }
    var timeText by remember { mutableStateOf("--:--") }
    var connectivity by remember(context) { mutableStateOf(readConnectivitySnapshot(context)) }
    var bluetoothEnabled by remember(context) { mutableStateOf(isBluetoothLikelyEnabled(context)) }
    var dndEnabled by remember(context) { mutableStateOf(isDndEnabled(context)) }
    val mediaState = rememberMediaState(context, active = contentVisible)
    val density = LocalDensity.current
    val verticalScrollState = rememberScrollState()
    val toggleScrollState = rememberScrollState()
    val verticalOverscroll = remember { Animatable(0f) }
    val toggleOverscroll = remember { Animatable(0f) }
    val overscrollScope = androidx.compose.runtime.rememberCoroutineScope()
    // Scrollable content uses the shared CloX-style edge controller. The
    // separate Animatable remains for the full-screen (non-scrollable)
    // dismiss gesture, where there is no child ScrollState to report
    // unconsumed deltas.
    val sharedVerticalElasticState = rememberFlueElasticScrollState(verticalScrollState)
    // 见 contentVisible 的注释：打开那一刻把两条滚动都拉回顶部。
    LaunchedEffect(contentVisible) {
        if (contentVisible) {
            verticalScrollState.scrollTo(0)
            toggleScrollState.scrollTo(0)
        }
    }
    var controlCenterGestureLocked by remember { mutableStateOf(false) }
    var scrollDismissDragPx by remember { mutableFloatStateOf(0f) }
    var scrollDismissActive by remember { mutableStateOf(false) }
    var fullScreenDragX by remember { mutableFloatStateOf(0f) }
    var fullScreenDragY by remember { mutableFloatStateOf(0f) }
    var fullScreenVelocityY by remember { mutableFloatStateOf(0f) }
    var fullScreenLastEventUptime by remember { mutableStateOf(0L) }
    var fullScreenDismissActive by remember { mutableStateOf(false) }
    var controlCenterViewportHeightPx by remember { mutableIntStateOf(1) }
    val lockControlCenterGesture: (Boolean) -> Unit = remember {
        { locked ->
            controlCenterGestureLocked = locked
            if (locked) {
                scrollDismissDragPx = 0f
                scrollDismissActive = false
                fullScreenDismissActive = false
            }
        }
    }
    val verticalElasticScroll = remember(
        verticalScrollState,
        sharedVerticalElasticState,
        controlCenterGestureLocked,
        controlCenterViewportHeightPx,
    ) {
        object : NestedScrollConnection {
            fun dismissProgress(): Float =
                (1f + scrollDismissDragPx /
                    (controlCenterViewportHeightPx.coerceAtLeast(1) * 0.8f))
                    .coerceIn(0f, 1f)

            /**
             * 现在允不允许开始累计 dismiss 位移。
             *
             * 老师的要求是「页面能滚动时，先滑到底、触发越界回弹，再向上滑才能关闭」。
             * 所以判据是**底边越界已经被拉过触发阈值** —— 内层没到底时共享回弹不会
             * owns 底边，位移一直是 0，自然过不了阈值。
             *
             * 两条必须留的旁路：
             * - 内容其实不溢出（`maxValue == 0`）：没有「滑到底」这回事，退回一段式，
             *   否则短内容也要先拉橡皮筋才能关。这里用**实测**的溢出量，不用估算高度 ——
             *   估算漏算了开关下面那行文字，估低了就会走错路径。
             * - 动效档把回弹关掉：最大越界位移恒为 0，阈值永远过不去，控制中心就再也
             *   关不掉了。
             */
            fun dismissGateOpen(): Boolean =
                verticalScrollState.maxValue <= 0 ||
                    !sharedVerticalElasticState.isElasticEnabled ||
                    sharedVerticalElasticState.isBottomBeyondTrigger

            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (source != NestedScrollSource.UserInput) {
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                if (controlCenterGestureLocked) {
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (scrollDismissActive) {
                    // 只独占「真的落在 dismiss 位移上」的那部分。反向拖回原位后要把闩
                    // 解开并把剩下的量交还内层，否则一旦闩住内层列表就再也滚不动了。
                    val next = (scrollDismissDragPx + available.y).coerceAtMost(0f)
                    val used = next - scrollDismissDragPx
                    scrollDismissDragPx = next
                    currentOnDismissProgressChange(dismissProgress())
                    if (next >= 0f && available.y > 0f) {
                        scrollDismissActive = false
                    }
                    if (used != 0f) {
                        return androidx.compose.ui.geometry.Offset(0f, used)
                    }
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                // Let the shared controller first consume a previously
                // stretched edge. This keeps a reversal continuous instead
                // of jumping back to the ScrollState position.
                return sharedVerticalElasticState.connection.onPreScroll(available, source)
            }

            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (controlCenterGestureLocked) {
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                if (
                    source == NestedScrollSource.UserInput &&
                    available.y < 0f &&
                    (scrollDismissActive || dismissGateOpen())
                ) {
                    scrollDismissActive = true
                    scrollDismissDragPx = (scrollDismissDragPx + available.y).coerceAtMost(0f)
                    currentOnDismissProgressChange(dismissProgress())
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                // 闸门还没开：未消费的量先交给共享回弹，让底边拉出橡皮筋。
                // 这条分支原来是死代码 —— dismiss 无条件抢在前面全量消费手势。
                return sharedVerticalElasticState.connection.onPostScroll(
                    consumed = consumed,
                    available = available,
                    source = source,
                )
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (controlCenterGestureLocked) return available
                if (scrollDismissActive) {
                    val progress = dismissProgress()
                    val velocity = available.y /
                        (controlCenterViewportHeightPx.coerceAtLeast(1) * 0.8f)
                    scrollDismissActive = false
                    scrollDismissDragPx = 0f
                    // dismiss 是在底边被拉过阈值之后才接手的，松手时那段越界位移还挂着。
                    // 不收就会留一个不回弹的偏移（关闭时看不出来，但取消关闭时内容会歪着）。
                    if (sharedVerticalElasticState.offsetPx != 0f) {
                        sharedVerticalElasticState.connection.onPreFling(Velocity.Zero)
                    }
                    currentOnDismissRelease(progress, velocity, false)
                    return available
                }
                if (sharedVerticalElasticState.offsetPx != 0f) {
                    return sharedVerticalElasticState.connection.onPreFling(available)
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (controlCenterGestureLocked) return available
                return sharedVerticalElasticState.connection.onPostFling(consumed, available)
            }
        }
    }
    val toggleElasticScroll = remember(toggleScrollState) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                if (source != NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                val atStart = toggleScrollState.value <= 0
                val atEnd = toggleScrollState.value >= toggleScrollState.maxValue
                if (available.x > 0f && atStart) {
                    overscrollScope.launch {
                        toggleOverscroll.snapTo(
                            (toggleOverscroll.value + available.x * CONTROL_CENTER_OVERSCROLL_RESISTANCE)
                                .coerceAtMost(CONTROL_CENTER_OVERSCROLL_LIMIT)
                        )
                    }
                    return androidx.compose.ui.geometry.Offset(available.x, 0f)
                }
                if (available.x < 0f && atEnd) {
                    overscrollScope.launch {
                        toggleOverscroll.snapTo(
                            (toggleOverscroll.value + available.x * CONTROL_CENTER_OVERSCROLL_RESISTANCE)
                                .coerceAtLeast(-CONTROL_CENTER_OVERSCROLL_LIMIT)
                        )
                    }
                    return androidx.compose.ui.geometry.Offset(available.x, 0f)
                }
                if (toggleOverscroll.value > 0f && available.x < 0f) {
                    overscrollScope.launch {
                        toggleOverscroll.snapTo((toggleOverscroll.value + available.x).coerceAtLeast(0f))
                    }
                    return androidx.compose.ui.geometry.Offset(available.x, 0f)
                }
                if (toggleOverscroll.value < 0f && available.x > 0f) {
                    overscrollScope.launch {
                        toggleOverscroll.snapTo((toggleOverscroll.value + available.x).coerceAtMost(0f))
                    }
                    return androidx.compose.ui.geometry.Offset(available.x, 0f)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (toggleOverscroll.value != 0f) {
                    toggleOverscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    val internetPanelLauncher = rememberLauncherForActivityResult(StartActivityForResult()) {
        connectivity = readConnectivitySnapshot(context)
    }
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(StartActivityForResult()) {
        bluetoothEnabled = isBluetoothLikelyEnabled(context)
    }
    var pendingBluetoothEnable by remember(context) { mutableStateOf(false) }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted && pendingBluetoothEnable) {
            requestBluetoothEnable(bluetoothEnableLauncher)
        }
        pendingBluetoothEnable = false
        bluetoothEnabled = isBluetoothLikelyEnabled(context)
    }

    // 这一层常驻组合（关闭后只是移出屏外），轮询必须用 contentVisible 门控，
    // 否则表盘界面上也在每 250ms 查一遍系统状态。打开期间才轮询：
    // 音量/亮度跟随滑杆交互保 250ms；电池/网络/蓝牙/DND 与手势无关，降到 1s。
    LaunchedEffect(context, contentVisible) {
        if (!contentVisible) return@LaunchedEffect
        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        var slowPollCounter = 0
        while (true) {
            timeText = timeFormatter.format(Date())
            val polledMediaVolume = readMusicVolume(audioManager)
            val requestedMediaVolume = pendingRequestedMediaVolume
            when {
                requestedMediaVolume == null -> mediaVolume = polledMediaVolume
                abs(polledMediaVolume - quantizeMusicVolume(audioManager, requestedMediaVolume)) > 0.001f -> {
                    pendingRequestedMediaVolume = null
                    mediaVolume = polledMediaVolume
                }
            }
            brightness = readBrightness(context)
            if (slowPollCounter == 0) {
                batteryStatus = readBatteryStatus(context)
                connectivity = readConnectivitySnapshot(context)
                bluetoothEnabled = isBluetoothLikelyEnabled(context)
                dndEnabled = isDndEnabled(context)
            }
            slowPollCounter = (slowPollCounter + 1) % 4
            delay(250)
        }
    }
    val greetingText = controlCenterGreeting()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 圆屏整圆裁剪 + 半透明玻璃根底（表盘背衬透出即毛玻璃）。
            .clip(flueOverlayMaskShape(overlayMaskCornerRadiusDp))
            .background(launcherStyle.screenBackground.copy(alpha = 0.62f))
    ) {
        val leftSafeInset = maxWidth * (leftSafeInsetPercent.coerceIn(0, 50) / 100f)
        val verticalPadding = 16.dp
        val extraCardsVisible = showMusicControls || showNotificationCard
        val outerSpacing = if (extraCardsVisible) 14.dp else 18.dp
        val controlsSpacing = if (extraCardsVisible) 14.dp else 20.dp
        val footerTopGap = 4.dp
        val toggleRowTopPadding = 6.dp
        val toggleSize = 58.dp
        val toggleIconSize = 26.dp
        val toggleLabelGap = 7.dp
        val sliderHeight = 58.dp
        val sliderHorizontalPadding = 14.dp
        val topStatusHorizontalInset = 20.dp
        val contentHorizontalPadding = 18.dp
        val toggleRowSpacing = 14.dp
        val availableToggleRowWidth = (maxWidth - leftSafeInset - contentHorizontalPadding * 2f).coerceAtLeast(0.dp)
        val toggleRowLayout = controlToggleRowLayout(
            availableWidthPx = with(density) { availableToggleRowWidth.toPx() },
            buttonWidthPx = with(density) { toggleSize.toPx() },
            preferredSpacingPx = with(density) { toggleRowSpacing.toPx() },
        )
        val toggleRowFits = !toggleRowLayout.scrollable
        val fittedToggleRowSpacing = with(density) { toggleRowLayout.spacingPx.toDp() }
        val innerToggleLabelSlotWidth = with(density) {
            controlToggleLabelSlotWidthPx(
                buttonWidthPx = toggleSize.toPx(),
                actualSpacingPx = toggleRowLayout.actualSpacingPx,
                edge = false,
            ).toDp()
        }
        val edgeToggleLabelSlotWidth = with(density) {
            controlToggleLabelSlotWidthPx(
                buttonWidthPx = toggleSize.toPx(),
                actualSpacingPx = toggleRowLayout.actualSpacingPx,
                edge = true,
            ).toDp()
        }
        val topStatusHeaderHeight = if (roundScreen) 96.dp else 32.dp
        // 顶部状态行 = 内容区 + 16dp 渐隐过渡带。圆屏需要两行，不能再用
        // 方屏的单行高度，否则电池与时间会被压进同一基线。
        // 始终挂载滚动容器，让 Compose 测量真实 contentHeight。是否存在有效滚动范围
        // 直接读 maxValue，不再依赖卡片估高；内容不足一屏时 maxValue 为 0。
        val verticalScrollEnabled = verticalScrollState.maxValue > 0
        LaunchedEffect(verticalScrollEnabled, toggleRowFits) {
            if (!verticalScrollEnabled) {
                verticalScrollState.scrollTo(0)
            }
            verticalOverscroll.snapTo(0f)
            toggleOverscroll.snapTo(0f)
            sharedVerticalElasticState.reset()
            scrollDismissDragPx = 0f
            scrollDismissActive = false
            fullScreenDismissActive = false
        }
        val verticalElasticModifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                controlCenterViewportHeightPx = size.height.coerceAtLeast(1)
                sharedVerticalElasticState.setViewportHeight(size.height)
            }
            .graphicsLayer {
                translationY = sharedVerticalElasticState.offsetPx
            }
            .padding(start = leftSafeInset)
        val contentModifier = verticalElasticModifier
            .nestedScroll(verticalElasticScroll)
            .verticalScroll(verticalScrollState)
        Column(
            modifier = Modifier
                .then(contentModifier)
                .padding(horizontal = contentHorizontalPadding, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(outerSpacing)
        ) {
            // 顶部模糊头（§8）：电池 + 时间状态行的模糊背衬。原行布局与手势原样
            // 保留在 content 里；滚动量取自 verticalScrollState（内容不足一屏、
            // 滚动关闭时恒为 0，头部保持全透明）。
            FrostedHeader(
                scrollProvider = {
                    controlCenterHeaderScrollOffset(
                        verticalScrollEnabled = verticalScrollEnabled,
                        scrollOffsetPx = verticalScrollState.value,
                    )
                },
                height = topStatusHeaderHeight
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topStatusHeaderHeight)
                        .padding(horizontal = topStatusHorizontalInset)
                ) {
                    WatchBatteryPill(
                        level = batteryStatus.level,
                        charging = batteryStatus.charging,
                        // 尺寸不再各写一套：与负一屏共用同一组默认值，
                        // 同一块表上两个电池必须一样大（老师实机反馈）。
                        modifier = Modifier.align(
                            if (roundScreen) Alignment.TopCenter else Alignment.CenterStart
                        )
                    )
                    if (roundScreen) {
                        // 圆屏纵向三段：电池在上、操作键居中、时间在下。
                        // 单独设置键严格居中；开启电源后两键组成以中线对称的 Row，
                        // 不再与电池或时间争同一基线。
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SystemSettingsButton(
                                icon = MiuixIcons.Settings,
                                contentDescription = stringResource(R.string.cc_system_settings),
                                size = 28.dp,
                                iconSize = 17.dp,
                                onGestureLockChange = lockControlCenterGesture,
                                onClick = { openSettings(context, Settings.ACTION_SETTINGS) },
                            )
                            if (showPowerButton) {
                                SystemSettingsButton(
                                    icon = MiuixIcons.Reset,
                                    contentDescription = stringResource(R.string.cc_power_options),
                                    size = 28.dp,
                                    iconSize = 17.dp,
                                    onGestureLockChange = lockControlCenterGesture,
                                    onClick = onPowerClick,
                                )
                            }
                        }
                        Text(
                            timeText,
                            color = launcherStyle.titleColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    } else {
                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (showPowerButton) {
                                SystemSettingsButton(
                                    icon = MiuixIcons.Reset,
                                    contentDescription = stringResource(R.string.cc_power_options),
                                    size = 28.dp,
                                    iconSize = 17.dp,
                                    onGestureLockChange = lockControlCenterGesture,
                                    onClick = onPowerClick,
                                )
                            }
                            SystemSettingsButton(
                                icon = MiuixIcons.Settings,
                                contentDescription = stringResource(R.string.cc_system_settings),
                                size = 28.dp,
                                iconSize = 17.dp,
                                onGestureLockChange = lockControlCenterGesture,
                                onClick = { openSettings(context, Settings.ACTION_SETTINGS) },
                            )
                            Text(
                                timeText,
                                color = launcherStyle.titleColor,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(controlsSpacing)) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = toggleRowTopPadding)
                ) {
                    val toggleViewportWidth = maxWidth
                    if (toggleRowFits) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            // Preserve the original edge alignment while the preferred
                            // gap fits. Only a genuinely narrow viewport switches to a
                            // measured fixed gap; this keeps the buttons aligned with
                            // the slider cards below without compressing their icons.
                            horizontalArrangement = if (toggleRowLayout.useSpaceBetween) {
                                Arrangement.SpaceBetween
                            } else {
                                Arrangement.spacedBy(
                                    fittedToggleRowSpacing,
                                    Alignment.CenterHorizontally,
                                )
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ControlToggle(
                                icon = FlueMiuixIcons.Wifi,
                                label = stringResource(R.string.cc_wifi),
                                active = connectivity.wifiActive,
                                size = toggleSize,
                                iconSize = toggleIconSize,
                                labelGap = toggleLabelGap,
                                labelSlotWidth = edgeToggleLabelSlotWidth,
                                onGestureLockChange = lockControlCenterGesture,
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        openInternetConnectivityPanel(context, internetPanelLauncher)
                                    } else if (!toggleWifiLegacy(context)) {
                                        openSettings(context, Settings.ACTION_WIFI_SETTINGS)
                                    }
                                    connectivity = readConnectivitySnapshot(context)
                                },
                                onLongClick = { openSettings(context, Settings.ACTION_WIFI_SETTINGS) }
                            )
                            ControlToggle(
                                icon = FlueMiuixIcons.Bluetooth,
                                label = stringResource(R.string.cc_bluetooth),
                                active = bluetoothEnabled,
                                size = toggleSize,
                                iconSize = toggleIconSize,
                                labelGap = toggleLabelGap,
                                labelSlotWidth = innerToggleLabelSlotWidth,
                                onGestureLockChange = lockControlCenterGesture,
                                onClick = {
                                    requestBluetoothToggle(
                                        context = context,
                                        enableLauncher = bluetoothEnableLauncher,
                                        permissionLauncher = bluetoothPermissionLauncher,
                                        onPermissionPending = { pendingBluetoothEnable = true }
                                    )
                                    bluetoothEnabled = isBluetoothLikelyEnabled(context)
                                },
                                onLongClick = { openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
                            )
                            ControlToggle(
                                icon = FlueMiuixIcons.MobileData,
                                label = stringResource(R.string.cc_mobile_data),
                                active = connectivity.cellularActive,
                                size = toggleSize,
                                iconSize = toggleIconSize,
                                labelGap = toggleLabelGap,
                                labelSlotWidth = innerToggleLabelSlotWidth,
                                onGestureLockChange = lockControlCenterGesture,
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        openInternetConnectivityPanel(context, internetPanelLauncher)
                                    } else {
                                        openSettings(context, Settings.ACTION_WIRELESS_SETTINGS)
                                    }
                                    connectivity = readConnectivitySnapshot(context)
                                },
                                onLongClick = { openSettings(context, Settings.ACTION_WIRELESS_SETTINGS) }
                            )
                            ControlToggle(
                                icon = FlueMiuixIcons.DoNotDisturb,
                                label = stringResource(R.string.cc_dnd),
                                active = dndEnabled,
                                size = toggleSize,
                                iconSize = toggleIconSize,
                                labelGap = toggleLabelGap,
                                labelSlotWidth = edgeToggleLabelSlotWidth,
                                activeColor = Color(0xFFBF5AF2),
                                onGestureLockChange = lockControlCenterGesture,
                                onClick = {
                                    dndEnabled = !dndEnabled
                                    toggleDnd(context)
                                    dndEnabled = isDndEnabled(context)
                                },
                                onLongClick = { openDndSettings(context) }
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .nestedScroll(toggleElasticScroll)
                                .horizontalScroll(toggleScrollState)
                        ) {
                            Row(
                                modifier = Modifier
                                    .widthIn(min = toggleViewportWidth)
                                    .graphicsLayer { translationX = toggleOverscroll.value },
                                horizontalArrangement = Arrangement.spacedBy(
                                    toggleRowSpacing.coerceAtLeast(fittedToggleRowSpacing),
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ControlToggle(
                                    icon = FlueMiuixIcons.Wifi,
                                    label = stringResource(R.string.cc_wifi),
                                    active = connectivity.wifiActive,
                                    size = toggleSize,
                                    iconSize = toggleIconSize,
                                    labelGap = toggleLabelGap,
                                    labelSlotWidth = edgeToggleLabelSlotWidth,
                                    onGestureLockChange = lockControlCenterGesture,
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            openInternetConnectivityPanel(context, internetPanelLauncher)
                                        } else if (!toggleWifiLegacy(context)) {
                                            openSettings(context, Settings.ACTION_WIFI_SETTINGS)
                                        }
                                        connectivity = readConnectivitySnapshot(context)
                                    },
                                    onLongClick = { openSettings(context, Settings.ACTION_WIFI_SETTINGS) }
                                )
                                ControlToggle(
                                    icon = FlueMiuixIcons.Bluetooth,
                                    label = stringResource(R.string.cc_bluetooth),
                                    active = bluetoothEnabled,
                                    size = toggleSize,
                                    iconSize = toggleIconSize,
                                    labelGap = toggleLabelGap,
                                    labelSlotWidth = innerToggleLabelSlotWidth,
                                    onGestureLockChange = lockControlCenterGesture,
                                    onClick = {
                                        requestBluetoothToggle(
                                            context = context,
                                            enableLauncher = bluetoothEnableLauncher,
                                            permissionLauncher = bluetoothPermissionLauncher,
                                            onPermissionPending = { pendingBluetoothEnable = true }
                                        )
                                        bluetoothEnabled = isBluetoothLikelyEnabled(context)
                                    },
                                    onLongClick = { openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
                                )
                                ControlToggle(
                                    icon = FlueMiuixIcons.MobileData,
                                    label = stringResource(R.string.cc_mobile_data),
                                    active = connectivity.cellularActive,
                                    size = toggleSize,
                                    iconSize = toggleIconSize,
                                    labelGap = toggleLabelGap,
                                    labelSlotWidth = innerToggleLabelSlotWidth,
                                    onGestureLockChange = lockControlCenterGesture,
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            openInternetConnectivityPanel(context, internetPanelLauncher)
                                        } else {
                                            openSettings(context, Settings.ACTION_WIRELESS_SETTINGS)
                                        }
                                        connectivity = readConnectivitySnapshot(context)
                                    },
                                    onLongClick = { openSettings(context, Settings.ACTION_WIRELESS_SETTINGS) }
                                )
                                ControlToggle(
                                    icon = FlueMiuixIcons.DoNotDisturb,
                                    label = stringResource(R.string.cc_dnd),
                                    active = dndEnabled,
                                    size = toggleSize,
                                    iconSize = toggleIconSize,
                                    labelGap = toggleLabelGap,
                                    labelSlotWidth = edgeToggleLabelSlotWidth,
                                    activeColor = Color(0xFFBF5AF2),
                                    onGestureLockChange = lockControlCenterGesture,
                                    onClick = {
                                        dndEnabled = !dndEnabled
                                        toggleDnd(context)
                                        dndEnabled = isDndEnabled(context)
                                    },
                                    onLongClick = { openDndSettings(context) }
                                )
                            }
                        }
                    }
                }

                ControlSliderCard(
                    icon = MiuixIcons.VolumeUp,
                    label = stringResource(R.string.cc_volume),
                    value = mediaVolume,
                    height = sliderHeight,
                    horizontalPadding = sliderHorizontalPadding,
                    glassHighlightEnabled = glassHighlightEnabled,
                    onValueChange = { value ->
                        mediaVolume = value
                        pendingRequestedMediaVolume = value
                        setMusicVolume(audioManager, value)
                    }
                )
                ControlSliderCard(
                    icon = Icons.Filled.BrightnessHigh,
                    label = stringResource(R.string.cc_brightness),
                    value = brightness,
                    height = sliderHeight,
                    horizontalPadding = sliderHorizontalPadding,
                    glassHighlightEnabled = glassHighlightEnabled,
                    onValueChange = { value ->
                        brightness = value
                        setBrightness(context, value)
                    }
                )

                if (showMusicControls) {
                    MediaControlCard(
                        mediaState = mediaState,
                        notificationAccessGranted = notificationAccessGranted,
                        glassHighlightEnabled = glassHighlightEnabled,
                        musicTextSwitchAnimation = musicTextSwitchAnimation,
                        showCustomActions = showMediaCustomActions,
                        onGestureLockChange = lockControlCenterGesture,
                        onOpenApp = { openMusicApp(context, mediaState.appPackage) },
                        onOpenNotificationAccess = { openNotificationAccessSettings(context) },
                        onOpenOutputSwitcherOrApp = {
                            if (!openMediaOutputSwitcher(context)) {
                                openMusicApp(context, mediaState.appPackage)
                            }
                        }
                    )
                }
                if (showNotificationCard) {
                    ControlCenterNotificationPreviewCard(
                        notificationGroups = notificationGroups,
                        notificationAccessGranted = notificationAccessGranted,
                        glassHighlightEnabled = glassHighlightEnabled,
                        onClick = onOpenNotifications
                    )
                }
            }
            if (verticalScrollEnabled) {
                Spacer(Modifier.height(4.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = greetingText,
                color = WatchColors.TextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = footerTopGap)
            )
        }
    }
}

@Composable
fun SideScreenControlCenterMusicCard(
    notificationAccessGranted: Boolean,
    musicTextSwitchAnimation: String = MusicTextSwitchAnimations.DEFAULT_ID,
    showMediaCustomActions: Boolean = true,
    glassHighlightEnabled: Boolean = true,
    // 负一屏同样常驻组合；只在它可见时才轮询媒体状态。
    active: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaState = rememberMediaState(context, active = active)
    MediaControlCard(
        mediaState = mediaState,
        notificationAccessGranted = notificationAccessGranted,
        glassHighlightEnabled = glassHighlightEnabled,
        musicTextSwitchAnimation = musicTextSwitchAnimation,
        showCustomActions = showMediaCustomActions,
        onGestureLockChange = {},
        onOpenApp = { openMusicApp(context, mediaState.appPackage) },
        onOpenNotificationAccess = { openNotificationAccessSettings(context) },
        onOpenOutputSwitcherOrApp = {
            if (!openMediaOutputSwitcher(context)) {
                openMusicApp(context, mediaState.appPackage)
            }
        },
        compact = false,
        modifier = modifier
    )
}

@Composable
private fun ControlCenterNotificationPreviewCard(
    notificationGroups: List<NotificationGroupUi>,
    notificationAccessGranted: Boolean,
    glassHighlightEnabled: Boolean = true,
    onGestureLockChange: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.968f else 1f,
        animationSpec = spring(stiffness = 860f, dampingRatio = 0.72f),
        label = "control_notification_press"
    )
    val leadGroup = notificationGroups.firstOrNull()
    val leadEntry = leadGroup?.visiblePreviewEntries?.firstOrNull() ?: leadGroup?.entries?.firstOrNull()
    val count = notificationGroups.sumOf { it.entries.size }
    val title = when {
        !notificationAccessGranted -> stringResource(R.string.cc_notification_permission_title)
        leadEntry != null -> leadEntry.title.ifBlank { leadGroup?.headerTitle.orEmpty() }.ifBlank { leadEntry.appLabel }
        else -> stringResource(R.string.cc_notification_empty_title)
    }
    val subtitle = when {
        !notificationAccessGranted -> stringResource(R.string.cc_notification_permission_subtitle)
        leadEntry != null -> leadEntry.text.ifBlank {
            stringResource(R.string.cc_notification_count_subtitle, count.coerceAtLeast(1))
        }
        else -> stringResource(R.string.cc_notification_empty_subtitle)
    }
    val stackStrength by animateFloatAsState(
        targetValue = if (count > 1) 1f else 0f,
        animationSpec = spring(stiffness = 620f, dampingRatio = 0.82f),
        label = "control_notification_stack"
    )
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(102.dp)
    ) {
        repeat((count - 1).coerceIn(0, 2)) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .graphicsLayer {
                        translationY = with(density) {
                            (CONTROL_NOTIFICATION_STACK_TRANSLATION_DP * (index + 1)).dp.toPx()
                        } * stackStrength
                        scaleX = 1f - (index + 1) * 0.012f * stackStrength
                        scaleY = 1f - (index + 1) * 0.012f * stackStrength
                        alpha = 0.54f - index * 0.16f
                    }
                    .padding(horizontal = (CONTROL_NOTIFICATION_STACK_HORIZONTAL_INSET_DP * (index + 1)).dp)
                    .clip(launcherStyle.cardShape)
                    .background(notificationStackBackColor(launcherStyle.glass.baseColor, index))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                }
                // 与通知中心的通知卡、负一屏的通知条同一份 glass 令牌。
                .glassSurface(
                    launcherStyle.cardShape,
                    launcherStyle.glass,
                    highlightEnabled = glassHighlightEnabled,
                )
                .controlCenterPressGesture(
                    pressedState = pressedState,
                    onGestureLockChange = onGestureLockChange,
                    onClick = onClick
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(launcherStyle.pressedCardColor),
                contentAlignment = Alignment.Center
            ) {
                val icon = leadGroup?.icon ?: leadEntry?.icon
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(7.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(MiuixIcons.Messages, null, tint = launcherStyle.titleColor.copy(alpha = 0.74f))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = launcherStyle.titleColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = subtitle,
                    color = launcherStyle.secondaryTextColor,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (count > 1) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(launcherStyle.accentColor.copy(alpha = 0.18f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = count.toString(),
                        color = launcherStyle.accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemSettingsButton(
    icon: ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onGestureLockChange: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val launcherStyle = LauncherTheme.style
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "system_settings_scale")
    val iconTint = if (pressed) {
        launcherStyle.titleColor.copy(alpha = 0.56f)
    } else {
        launcherStyle.titleColor
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .controlCenterPressGesture(
                pressedState = pressedState,
                onGestureLockChange = onGestureLockChange,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ControlToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    labelGap: androidx.compose.ui.unit.Dp,
    labelSlotWidth: androidx.compose.ui.unit.Dp,
    // watchOS 语义：连接类开关亮蓝，勿扰亮紫 —— 而不是四个开关同一个纯白。
    activeColor: Color = Color(0xFF0A84FF),
    onGestureLockChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val density = LocalDensity.current
    val labelStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val textMeasurer = rememberTextMeasurer()
    val labelTextWidthPx = remember(label, labelStyle, textMeasurer) {
        textMeasurer.measure(
            text = label,
            style = labelStyle,
            maxLines = 1,
            softWrap = false,
        ).size.width.toFloat()
    }
    val labelOverflows = controlToggleLabelOverflows(
        slotWidthPx = with(density) { labelSlotWidth.toPx() },
        textWidthPx = labelTextWidthPx,
    )
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val darkMode = WatchColors.isDark
    // 旧实现里未激活态带一个 0.96f 的静态缩放，四个按钮永远大小不齐。
    val targetScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 900f),
        label = "control_toggle_scale"
    )
    // 未激活态取 glass 的等效底色，与旁边的玻璃卡同一份令牌（原先是手写的
    // 白 0.12 / 黑 0.08，跟卡片各写一套，改配方时必然漏一处）。
    val inactiveColor = LauncherTheme.style.glass.baseColor
    val pressedOverlay = if (darkMode) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)
    // 状态色渐变过去而不是瞬间反转 —— 这里曾是全页最生硬的跳变。
    val buttonColor by animateColorAsState(
        targetValue = if (active) activeColor else inactiveColor,
        animationSpec = spring(dampingRatio = 0.92f, stiffness = 700f),
        label = "control_toggle_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            active -> Color.White
            darkMode -> Color.White
            else -> Color(0xFF16181D)
        },
        animationSpec = spring(dampingRatio = 0.92f, stiffness = 700f),
        label = "control_toggle_tint"
    )
    Column(
        modifier = Modifier.width(size),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = targetScale
                    scaleY = targetScale
                }
                .clip(CircleShape)
                .background(buttonColor)
                .then(if (pressed) Modifier.background(pressedOverlay) else Modifier)
                .controlCenterPressGesture(
                    pressedState = pressedState,
                    onGestureLockChange = onGestureLockChange,
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(Modifier.height(labelGap))
        Text(
            text = label,
            color = WatchColors.TextSecondary,
            style = labelStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .width(labelSlotWidth)
                .then(
                    if (labelOverflows) {
                        Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1_200,
                            velocity = CONTROL_TOGGLE_LABEL_MARQUEE_SPEED_DP_PER_SECOND.dp,
                        )
                    } else {
                        Modifier
                    }
                ),
        )
    }
}

internal data class ControlToggleRowLayout(
    val scrollable: Boolean,
    val spacingPx: Float,
    val actualSpacingPx: Float,
    val useSpaceBetween: Boolean,
)

/**
 * Four control-center buttons keep their physical size. Only the inter-button
 * gap may shrink, down to two physical pixels; below that boundary the row
 * remains at its preferred spacing and becomes horizontally scrollable.
 */
internal fun controlToggleRowLayout(
    availableWidthPx: Float,
    buttonWidthPx: Float,
    preferredSpacingPx: Float,
    buttonCount: Int = 4,
    minimumSpacingPx: Float = 2f,
): ControlToggleRowLayout {
    val count = buttonCount.coerceAtLeast(1)
    val gapCount = (count - 1).coerceAtLeast(0)
    val safeButtonWidth = buttonWidthPx.coerceAtLeast(0f)
    val safePreferredSpacing = preferredSpacingPx.coerceAtLeast(minimumSpacingPx)
    if (gapCount == 0) {
        return ControlToggleRowLayout(
            scrollable = availableWidthPx < safeButtonWidth,
            spacingPx = 0f,
            actualSpacingPx = 0f,
            useSpaceBetween = false,
        )
    }
    val availableForGaps = availableWidthPx.coerceAtLeast(0f) - safeButtonWidth * count
    val minimumRequired = minimumSpacingPx * gapCount
    if (availableForGaps < minimumRequired) {
        return ControlToggleRowLayout(
            scrollable = true,
            spacingPx = safePreferredSpacing,
            actualSpacingPx = safePreferredSpacing,
            useSpaceBetween = false,
        )
    }
    val exactSpacing = availableForGaps / gapCount
    val useSpaceBetween = exactSpacing >= safePreferredSpacing
    return ControlToggleRowLayout(
        scrollable = false,
        spacingPx = if (useSpaceBetween) {
            safePreferredSpacing
        } else {
            exactSpacing.coerceAtLeast(minimumSpacingPx)
        },
        actualSpacingPx = exactSpacing.coerceAtLeast(minimumSpacingPx),
        useSpaceBetween = useSpaceBetween,
    )
}


@Composable
private fun ControlSliderCard(
    icon: ImageVector,
    label: String,
    value: Float,
    height: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    glassHighlightEnabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    val launcherStyle = LauncherTheme.style
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            // 滑块卡原来是 cardColor 平铺（MD3/MIUIX 下就是不透明实色板）。
            .glassSurface(
                launcherStyle.cardShape,
                launcherStyle.glass,
                highlightEnabled = glassHighlightEnabled,
            )
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = launcherStyle.bodyColor, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(9.dp))
        Text(label, color = launcherStyle.bodyColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        RelativeDragSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 相对拖动滑块：按下点只决定手势从哪里开始，数值始终以按下瞬间的原值为
 * 起点。这样手指按到轨道任意位置时不会瞬移，只有后续位移才改变音量/亮度。
 */
@Composable
private fun RelativeDragSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentValue by rememberUpdatedState(value.coerceIn(0f, 1f))
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val startX = down.position.x
                val startValue = currentValue
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    change.consume()
                    currentOnValueChange(
                        (startValue + (change.position.x - startX) / size.width.coerceAtLeast(1))
                            .coerceIn(0f, 1f),
                    )
                }
            }
        },
    ) {
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = {},
            enabled = false,
            colors = SliderDefaults.colors(
                disabledThumbColor = Color.White,
                disabledActiveTrackColor = Color.White,
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.22f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MediaControlCard(
    mediaState: ControlCenterMediaState,
    notificationAccessGranted: Boolean,
    glassHighlightEnabled: Boolean,
    musicTextSwitchAnimation: String,
    showCustomActions: Boolean,
    onGestureLockChange: (Boolean) -> Unit,
    onOpenApp: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenOutputSwitcherOrApp: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val layout = mediaCardLayoutSpec(compact)
    val launcherStyle = LauncherTheme.style
    val resolvedMediaTitle = mediaState.title.ifBlank {
        stringResource(R.string.cc_media_not_playing)
    }
    val resolvedMediaArtist = mediaState.artist.ifBlank {
        stringResource(R.string.cc_media_open_app_hint)
    }
    val cardShape = launcherStyle.panelShape
    val artworkSurfaceActive = notificationAccessGranted &&
        mediaState.playing &&
        mediaState.artwork != null
    val primaryTarget = if (artworkSurfaceActive) Color.White else launcherStyle.titleColor
    val secondaryTarget = if (artworkSurfaceActive) {
        Color.White.copy(alpha = 0.72f)
    } else {
        launcherStyle.secondaryTextColor
    }
    val controlTarget = if (artworkSurfaceActive) Color.White else launcherStyle.bodyColor
    val primaryContentColor by animateColorAsState(
        targetValue = primaryTarget,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "media_primary_content",
    )
    val secondaryContentColor by animateColorAsState(
        targetValue = secondaryTarget,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "media_secondary_content",
    )
    val controlContentColor by animateColorAsState(
        targetValue = controlTarget,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "media_control_content",
    )
    val blurredArtwork = rememberBlurredMediaArtwork(
        mediaState.artwork.takeIf { notificationAccessGranted },
    )
    val artworkSurfaceAlpha by animateFloatAsState(
        targetValue = if (artworkSurfaceActive && blurredArtwork != null) 1f else 0f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "media_artwork_surface",
    )
    val bgTarget = if (notificationAccessGranted) mediaState.artworkColor else Color(0xFF2F3B45)
    // 切歌时底色渐变过去，而不是硬跳 —— 让它的 420ms 盖住文字动画的中段，
    // 视觉上就是「同一件事」。
    val bg by animateColorAsState(
        targetValue = bgTarget,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "media_card_bg",
    )
    val playingEdgeAlpha by animateFloatAsState(
        targetValue = if (artworkSurfaceActive && glassHighlightEnabled) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "media_card_playing_edge",
    )
    // 与通知预览卡 / 控制中心按钮同款的按压反馈：整卡按下缩到 0.968 再弹回。
    // 以前是 Material 的 clickable 默认 ripple —— 一块 156dp 高的玻璃卡上
    // 铺开一圈水波纹，和同屏其他控件完全不是一套语言（老师实机反馈）。
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.968f else 1f,
        animationSpec = spring(stiffness = 860f, dampingRatio = 0.72f),
        label = "media_card_press",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(layout.cardHeightDp.dp)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            // panelShape(30dp)：媒体卡是大块表面。内层封面 18dp = 30 − 12 padding，
            // 同心圆角关系（内 = 外 − 间距）；旧的 32/24 组合会让封面的角
            // 「胀」出外壳弧线约 4dp。
            .glassSurface(
                cardShape,
                launcherStyle.glass,
                highlightEnabled = glassHighlightEnabled,
            )
            .instantPressGesture(
                pressedState,
                onClick = if (notificationAccessGranted) onOpenApp else onOpenNotificationAccess,
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = artworkSurfaceAlpha }
                .background(
                    Brush.linearGradient(
                        listOf(
                            bg.copy(alpha = 0.92f),
                            Color(bg.red * 0.86f, bg.green * 0.86f, bg.blue * 0.86f, 0.94f),
                        )
                    )
                ),
        )
        Crossfade(
            targetState = blurredArtwork,
            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
            label = "media_blurred_artwork_crossfade",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.08f
                    scaleY = 1.08f
                    alpha = artworkSurfaceAlpha * 0.68f
                },
        ) { artwork ->
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = artworkSurfaceAlpha }
                .background(Color.Black.copy(alpha = 0.10f)),
        )
        if (playingEdgeAlpha > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = playingEdgeAlpha }
                    .border(
                        width = 1.25.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.58f),
                                Color.White.copy(alpha = 0.10f),
                            )
                        ),
                        shape = cardShape,
                    )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(layout.paddingDp.dp)
        ) {
            if (!notificationAccessGranted) {
                MediaNotificationAccessPrompt(
                    primaryColor = primaryContentColor,
                    secondaryColor = secondaryContentColor,
                    controlColor = controlContentColor,
                    compact = compact,
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val artworkGap = layout.artworkGapDp.dp
            val preferredArtworkSize = layout.artworkSizeDp.dp
            val minArtworkSize = if (compact) 68.dp else 96.dp
            val preferredControlsWidth = layout.controlButtonDp.dp * 3f +
                (if (compact) 5.dp else 8.dp) * 2f
            val artworkSize = if (maxWidth >= preferredArtworkSize + artworkGap + preferredControlsWidth) {
                preferredArtworkSize
            } else {
                (maxWidth - artworkGap - preferredControlsWidth).coerceIn(minArtworkSize, preferredArtworkSize)
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 18dp = panel 30dp − padding 12dp（同心圆角）。
                val artworkShape = RoundedCornerShape(18.dp)
                Box(
                    modifier = Modifier
                        .size(artworkSize)
                        .clip(artworkShape)
                        .background(controlContentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = mediaState.artwork,
                        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                        label = "media_cover_crossfade",
                        modifier = Modifier.fillMaxSize(),
                    ) { artwork ->
                        if (artwork != null) {
                            Image(
                                bitmap = artwork,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(artworkShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = MiuixIcons.Music,
                                    contentDescription = null,
                                    tint = controlContentColor,
                                    modifier = Modifier
                                        .size(if (compact) 26.dp else 34.dp)
                                        .offset(x = (-2).dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(artworkGap))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val titleEndPadding = if (mediaState.appIcon != null) 34.dp else 0.dp
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = titleEndPadding)
                            ) {
                                AnimatedMusicText(
                                    text = resolvedMediaTitle,
                                    color = primaryContentColor,
                                    fontSize = layout.titleFontSizeSp.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    animationStyleId = musicTextSwitchAnimation
                                )
                                AnimatedMusicText(
                                    text = resolvedMediaArtist,
                                    color = secondaryContentColor,
                                    fontSize = layout.artistFontSizeSp.sp,
                                    fontWeight = FontWeight.Normal,
                                    animationStyleId = musicTextSwitchAnimation
                                )
                            }
                            if (mediaState.appIcon != null) {
                                MediaAppIconButton(
                                    icon = mediaState.appIcon,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(30.dp),
                                    onGestureLockChange = onGestureLockChange,
                                    onClick = onOpenOutputSwitcherOrApp
                                )
                            }
                        }
                    }
                    MediaProgressSection(
                        mediaState = mediaState,
                        onGestureLockChange = onGestureLockChange,
                        foregroundColor = controlContentColor,
                        secondaryTextColor = secondaryContentColor,
                        compact = compact,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MediaControlsRow(
                        mediaState = mediaState,
                        showCustomActions = showCustomActions,
                        onGestureLockChange = onGestureLockChange,
                        foregroundColor = controlContentColor,
                        compact = compact,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                }
            }
        }
    }
}

}

@Composable
private fun rememberBlurredMediaArtwork(artwork: ImageBitmap?): ImageBitmap? {
    var blurred by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artwork) {
        if (artwork == null) {
            blurred = null
        } else {
            // Keep the previous field visible while the next cover is processed,
            // then Crossfade swaps the two. This avoids a blank flash between songs.
            blurred = withContext(Dispatchers.Default) {
                createMediumBlurredMediaArtwork(artwork)
            }
        }
    }
    return blurred
}

/**
 * Cross-version medium blur for the music card. A software box blur is used on
 * every API level so Android 27-30 and Android 12+ keep the same visual weight.
 * Working at a 160px long edge is smooth enough for a card without turning the
 * cover into the blocky 24-48px enlarged thumbnail used by the old path.
 */
private fun createMediumBlurredMediaArtwork(sourceImage: ImageBitmap): ImageBitmap {
    val source = sourceImage.asAndroidBitmap()
    val sourceLongEdge = maxOf(source.width, source.height).coerceAtLeast(1)
    val targetLongEdge = sourceLongEdge.coerceIn(64, 160)
    val scale = targetLongEdge.toFloat() / sourceLongEdge
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)
    val working = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(working).drawBitmap(
        source,
        null,
        android.graphics.Rect(0, 0, width, height),
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    val pixels = IntArray(width * height)
    val scratch = IntArray(pixels.size)
    working.getPixels(pixels, 0, width, 0, 0, width, height)
    repeat(3) {
        mediaBoxBlurHorizontal(pixels, scratch, width, height, radius = 5)
        mediaBoxBlurVertical(scratch, pixels, width, height, radius = 5)
    }
    working.setPixels(pixels, 0, width, 0, 0, width, height)
    return working.asImageBitmap()
}

private fun mediaBoxBlurHorizontal(
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

private fun mediaBoxBlurVertical(
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

@Composable
private fun MediaNotificationAccessPrompt(
    primaryColor: Color,
    secondaryColor: Color,
    controlColor: Color,
    compact: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 92.dp else 132.dp)
                .clip(RoundedCornerShape(if (compact) 18.dp else 24.dp))
                .background(controlColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = MiuixIcons.Music,
                contentDescription = null,
                tint = controlColor,
                modifier = Modifier.size(if (compact) 26.dp else 34.dp),
            )
        }
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.cc_notification_permission_title),
                color = primaryColor,
                fontSize = if (compact) 13.sp else 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(if (compact) 2.dp else 5.dp))
            Text(
                text = stringResource(R.string.cc_media_permission_subtitle),
                color = secondaryColor,
                fontSize = if (compact) 9.sp else 11.sp,
                lineHeight = if (compact) 12.sp else 15.sp,
                maxLines = if (compact) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(if (compact) 5.dp else 12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(controlColor.copy(alpha = 0.14f))
                    .padding(
                        horizontal = if (compact) 8.dp else 12.dp,
                        vertical = if (compact) 4.dp else 6.dp,
                    )
            ) {
                Text(
                    text = stringResource(R.string.cc_media_permission_action),
                    color = controlColor,
                    fontSize = if (compact) 9.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun MediaAppIconButton(
    icon: ImageBitmap,
    modifier: Modifier = Modifier,
    onGestureLockChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "media_app_icon_scale")
    val iconAlpha by animateFloatAsState(if (pressed) 0.72f else 1f, label = "media_app_icon_alpha")
    val iconShape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(iconShape)
            .controlCenterPressGesture(
                pressedState = pressedState,
                onGestureLockChange = onGestureLockChange,
                onClick = onClick
            )
            .padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .clip(iconShape)
                .graphicsLayer { alpha = iconAlpha },
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun MediaControlsRow(
    mediaState: ControlCenterMediaState,
    showCustomActions: Boolean,
    onGestureLockChange: (Boolean) -> Unit,
    foregroundColor: Color,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val customActions = if (showCustomActions) mediaState.customActions.take(2) else emptyList()
        val preferredButtonSize = if (compact) 28.dp else 36.dp
        val minButtonSize = if (compact) 22.dp else 24.dp
        val customButtonScale = if (compact) 1.05f else 1.12f
        val preferredSpacing = if (compact) 5.dp else 8.dp
        val minSpacing = if (compact) 3.dp else 4.dp
        val customCountFloat = customActions.size.toFloat()
        val buttonCount = 3 + customActions.size
        val gapCountFloat = (buttonCount - 1).coerceAtLeast(1).toFloat()
        val buttonWidthUnits = 3f + customCountFloat * customButtonScale
        val preferredWidth = preferredButtonSize * buttonWidthUnits + preferredSpacing * gapCountFloat
        val minSpacingWidth = minSpacing * gapCountFloat
        val buttonSize = if (maxWidth >= preferredWidth) {
            preferredButtonSize
        } else {
            ((maxWidth - minSpacingWidth) / buttonWidthUnits).coerceIn(minButtonSize, preferredButtonSize)
        }
        val customButtonSize = (buttonSize * customButtonScale).coerceAtLeast(buttonSize)
        val occupiedButtonWidth = buttonSize * 3f + customButtonSize * customCountFloat
        val buttonSpacing = ((maxWidth - occupiedButtonWidth) / gapCountFloat)
            .coerceIn(minSpacing, preferredSpacing)
        val iconSize = (buttonSize * 0.56f).coerceIn(16.dp, 20.dp)
        val customIconSize = (customButtonSize * 0.60f).coerceIn(iconSize, 23.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            customActions.getOrNull(0)?.let { customAction ->
                MediaCustomActionButton(
                    action = customAction,
                    size = customButtonSize,
                    iconSize = customIconSize,
                    foregroundColor = foregroundColor,
                    onGestureLockChange = onGestureLockChange
                ) {
                    mediaState.controller?.transportControls?.sendCustomAction(
                        customAction.action,
                        customAction.extras
                    )
                }
            }
            MediaButton(
                icon = FlueMiuixIcons.SkipPrevious,
                size = buttonSize,
                iconSize = iconSize,
                foregroundColor = foregroundColor,
                onGestureLockChange = onGestureLockChange
            ) {
                mediaState.controller?.transportControls?.skipToPrevious()
            }
            MediaButton(
                icon = if (mediaState.playing) MiuixIcons.Pause else MiuixIcons.Play,
                size = buttonSize,
                iconSize = iconSize,
                // The requested optical alignment nudges only the play triangle right;
                // pause remains mathematically centred.
                iconOffsetX = if (mediaState.playing) 0.dp else 0.8.dp,
                foregroundColor = foregroundColor,
                onGestureLockChange = onGestureLockChange
            ) {
                val controls = mediaState.controller?.transportControls
                if (mediaState.playing) controls?.pause() else controls?.play()
            }
            MediaButton(
                icon = FlueMiuixIcons.SkipNext,
                size = buttonSize,
                iconSize = iconSize,
                foregroundColor = foregroundColor,
                onGestureLockChange = onGestureLockChange
            ) {
                mediaState.controller?.transportControls?.skipToNext()
            }
            customActions.getOrNull(1)?.let { customAction ->
                MediaCustomActionButton(
                    action = customAction,
                    size = customButtonSize,
                    iconSize = customIconSize,
                    foregroundColor = foregroundColor,
                    onGestureLockChange = onGestureLockChange
                ) {
                    mediaState.controller?.transportControls?.sendCustomAction(
                        customAction.action,
                        customAction.extras
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaCustomActionButton(
    action: ControlCenterMediaAction,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    foregroundColor: Color,
    onGestureLockChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "media_custom_button_scale")
    val contentAlpha by animateFloatAsState(if (pressed) 0.64f else 1f, label = "media_custom_button_alpha")
    Box(
        modifier = Modifier
            .requiredSize(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .controlCenterPressGesture(
                pressedState = pressedState,
                onGestureLockChange = onGestureLockChange,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (action.icon != null) {
            Image(
                bitmap = action.icon,
                contentDescription = action.title,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { alpha = contentAlpha },
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = action.title.take(2).ifBlank { "..." },
                color = foregroundColor.copy(alpha = contentAlpha),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AnimatedMusicText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    animationStyleId: String
) {
    val density = LocalDensity.current
    val preset = remember(animationStyleId) { MusicTextSwitchAnimations.byId(animationStyleId) }
    var displayedText by remember { mutableStateOf(text) }
    var phase by remember { mutableStateOf(MusicTextSwitchPhase.Idle) }
    val progress = remember { Animatable(1f) }
    val distancePx = with(density) { 18.dp.toPx() }

    LaunchedEffect(text, animationStyleId) {
        val currentPreset = MusicTextSwitchAnimations.byId(animationStyleId)
        if (displayedText == text) {
            phase = MusicTextSwitchPhase.Idle
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        phase = MusicTextSwitchPhase.Out
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            tween(
                durationMillis = currentPreset.outMotion.durationMs,
                easing = currentPreset.outMotion.easing
            )
        )
        displayedText = text
        phase = MusicTextSwitchPhase.In
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            tween(
                durationMillis = currentPreset.inMotion.durationMs,
                easing = currentPreset.inMotion.easing
            )
        )
        phase = MusicTextSwitchPhase.Idle
        progress.snapTo(1f)
    }

    // transform 本来在组合期逐帧求值（读 progress.value → 每帧重组整个组件）。
    // 把求值移进 graphicsLayer 的逐帧 lambda：动画帧只重画这一层，不触发重组。
    Box(
        modifier = Modifier
            .clipToBounds()
            .graphicsLayer {
                val transform = preset.transform(phase, progress.value, distancePx)
                alpha = transform.alpha
                translationX = transform.translationX
                translationY = transform.translationY
                scaleX = transform.scale
                scaleY = transform.scale
                rotationX = transform.rotationX
                rotationY = transform.rotationY
                rotationZ = transform.rotationZ
                cameraDistance = 12f * density.density
            }
    ) {
        OneShotMarqueeText(
            text = displayedText,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight
        )
    }
}

@Composable
private fun OneShotMarqueeText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight
) {
    val density = LocalDensity.current
    val sidePadding = MUSIC_TEXT_MARQUEE_SIDE_PADDING_DP.dp
    val sidePaddingPx = with(density) { sidePadding.toPx() }
    val fadeEdgePx = with(density) { MUSIC_TEXT_FADE_EDGE_DP.dp.toPx() }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var rawTextWidthPx by remember(text) { mutableIntStateOf(0) }
    var contentWidthPx by remember { mutableIntStateOf(0) }
    val hasOverflow = containerWidthPx > 0 &&
        rawTextWidthPx > 0 &&
        rawTextWidthPx + sidePaddingPx > containerWidthPx.toFloat()
    val marqueeSpec = mediaMarqueeSpec(
        textWidthPx = rawTextWidthPx,
        viewportWidthPx = containerWidthPx,
        gapPx = sidePaddingPx.roundToInt(),
    )
    val contentLayoutReady = !hasOverflow || contentWidthPx >= marqueeSpec.contentWidthPx - 1
    val marqueeScrollState = rememberScrollState()
    val restingTargetPx = if (hasOverflow && contentLayoutReady) {
        marqueeSpec.nextCopyEntryOffsetPx
    } else {
        0
    }
    val exitTargetPx = if (hasOverflow && contentLayoutReady) {
        marqueeSpec.cycleDistancePx
    } else {
        0
    }

    LaunchedEffect(
        text,
        hasOverflow,
        restingTargetPx,
        exitTargetPx,
        density.density,
    ) {
        marqueeScrollState.scrollTo(0)
        if (
            hasOverflow &&
            restingTargetPx > 0 &&
            exitTargetPx > restingTargetPx
        ) {
            delay(MUSIC_TEXT_MARQUEE_INITIAL_DELAY_MS)
            val firstDurationMs = mediaMarqueeDurationMs(restingTargetPx, density.density, minimumMs = 900)
            marqueeScrollState.animateScrollTo(
                value = restingTargetPx,
                animationSpec = tween(durationMillis = firstDurationMs, easing = LinearEasing),
            )
            delay(MUSIC_TEXT_MARQUEE_END_HOLD_MS)
            val exitDurationMs = mediaMarqueeDurationMs(
                exitTargetPx - restingTargetPx,
                density.density,
                minimumMs = 420,
            )
            marqueeScrollState.animateScrollTo(
                value = exitTargetPx,
                animationSpec = tween(durationMillis = exitDurationMs, easing = LinearEasing),
            )

            while (true) {
                // At the end of one cycle the second copy is exactly where the first copy
                // started. Rewinding by one cycle is therefore visually identical and avoids
                // inserting a blank viewport between copies.
                marqueeScrollState.scrollTo(0)
                val repeatScrollDurationMs = mediaMarqueeDurationMs(restingTargetPx, density.density, minimumMs = 900)
                marqueeScrollState.animateScrollTo(
                    value = restingTargetPx,
                    animationSpec = tween(durationMillis = repeatScrollDurationMs, easing = LinearEasing),
                )
                delay(MUSIC_TEXT_MARQUEE_END_HOLD_MS)
                val repeatExitDurationMs = mediaMarqueeDurationMs(
                    exitTargetPx - restingTargetPx,
                    density.density,
                    minimumMs = 420,
                )
                marqueeScrollState.animateScrollTo(
                    value = exitTargetPx,
                    animationSpec = tween(durationMillis = repeatExitDurationMs, easing = LinearEasing),
                )
            }
        }
    }

    val scrollOffsetPx = marqueeScrollState.value.toFloat()
    val currentRestingTargetPx = restingTargetPx
    val remainingScrollPx = (currentRestingTargetPx - marqueeScrollState.value)
        .coerceAtLeast(0)
        .toFloat()
    val atCycleStart = scrollOffsetPx <= 0.5f
    val leftFadeTarget = if (hasOverflow && fadeEdgePx > 0f) {
        if (!atCycleStart) 1f else 0f
    } else {
        0f
    }
    val rightFadeTarget = if (hasOverflow && fadeEdgePx > 0f) {
        (remainingScrollPx / fadeEdgePx).coerceIn(0f, 1f)
    } else {
        0f
    }
    val leftFadeAlpha by animateFloatAsState(
        targetValue = leftFadeTarget,
        animationSpec = tween(durationMillis = MUSIC_TEXT_FADE_TRANSITION_MS, easing = FastOutSlowInEasing),
        label = "music_text_left_edge_fade"
    )
    val rightFadeAlpha by animateFloatAsState(
        targetValue = rightFadeTarget,
        animationSpec = tween(durationMillis = MUSIC_TEXT_FADE_TRANSITION_MS, easing = FastOutSlowInEasing),
        label = "music_text_right_edge_fade"
    )
    val fadeModifier = if (hasOverflow || leftFadeAlpha > 0.001f || rightFadeAlpha > 0.001f) {
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val fadeWidthPx = MUSIC_TEXT_FADE_EDGE_DP.dp.toPx().coerceAtMost(size.width / 2f)
                val leftStop = if (size.width == 0f) 0f else (fadeWidthPx / size.width).coerceIn(0f, 1f)
                val rightStart = if (size.width == 0f) 1f else ((size.width - fadeWidthPx) / size.width).coerceIn(0f, 1f)
                val fadeBrush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 1f - leftFadeAlpha.coerceIn(0f, 1f)),
                        leftStop to Color.Black,
                        rightStart to Color.Black,
                        1f to Color.Black.copy(alpha = 1f - rightFadeAlpha.coerceIn(0f, 1f))
                    )
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
                }
            }
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { containerWidthPx = it.width.coerceAtLeast(0) }
            .then(fadeModifier)
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(marqueeScrollState, enabled = false)
                .onSizeChanged { contentWidthPx = it.width.coerceAtLeast(0) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { rawTextWidthPx = it.size.width.coerceAtLeast(0) }
            )
            if (hasOverflow) {
                Spacer(Modifier.width(sidePadding))
                Text(
                    text = text,
                    color = color,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
                Spacer(Modifier.width(sidePadding))
                // Keep a trailing viewport so the scroll range can finish one full copy cycle.
                Spacer(Modifier.width((containerWidthPx / density.density).dp))
            }
        }
    }
}

internal fun mediaMarqueeDurationMs(distancePx: Int, density: Float, minimumMs: Int): Int =
    ((distancePx.coerceAtLeast(1) / density.coerceAtLeast(0.1f)) /
        MUSIC_TEXT_MARQUEE_SPEED_DP_PER_SECOND * 1_000f)
        .roundToInt()
        .coerceAtLeast(minimumMs)

@Composable
private fun MediaProgressSection(
    mediaState: ControlCenterMediaState,
    onGestureLockChange: (Boolean) -> Unit,
    foregroundColor: Color,
    secondaryTextColor: Color,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val currentMediaState by rememberUpdatedState(mediaState)
    val currentGestureLockChange by rememberUpdatedState(onGestureLockChange)
    var widthPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var skipNextProgressSync by remember { mutableStateOf(false) }
    var seekSyncHoldUntilMs by remember { mutableStateOf(0L) }
    var activeMediaIdentity by remember {
        mutableStateOf(mediaState.appPackage to mediaState.title)
    }
    var localProgress by remember {
        mutableFloatStateOf(mediaState.progress.coerceIn(0f, 1f))
    }
    LaunchedEffect(mediaState.appPackage, mediaState.title, dragging) {
        val mediaIdentity = mediaState.appPackage to mediaState.title
        if (!dragging && activeMediaIdentity != mediaIdentity) {
            activeMediaIdentity = mediaIdentity
            skipNextProgressSync = false
            seekSyncHoldUntilMs = 0L
            localProgress = mediaState.progress.coerceIn(0f, 1f)
        }
    }
    LaunchedEffect(mediaState.progress, dragging) {
        if (!dragging) {
            val now = SystemClock.uptimeMillis()
            if (now < seekSyncHoldUntilMs) {
                return@LaunchedEffect
            }
            if (skipNextProgressSync) {
                skipNextProgressSync = false
                seekSyncHoldUntilMs = now + MEDIA_PROGRESS_SEEK_SYNC_HOLD_MS
                return@LaunchedEffect
            }
            localProgress = mediaState.progress.coerceIn(0f, 1f)
        }
    }
    val hitHeightDp = if (compact) 16f else MEDIA_PROGRESS_HIT_HEIGHT_DP
    val timeFontSize = if (compact) 8.sp else 10.sp
    val trackHeight by animateFloatAsState(
        if (dragging) MEDIA_PROGRESS_EXPANDED_HEIGHT_DP else MEDIA_PROGRESS_COLLAPSED_HEIGHT_DP,
        label = "media_progress_height"
    )
    fun seekToProgress(progress: Float) {
        val duration = currentMediaState.durationMs
        if (duration <= 0L) return
        currentMediaState.controller?.transportControls?.seekTo((progress.coerceIn(0f, 1f) * duration).toLong())
    }
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(hitHeightDp.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    val dragHitHeightPx = hitHeightDp.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val centerY = size.height / 2f
                        val withinTrack = abs(down.position.y - centerY) <= dragHitHeightPx / 2f
                        if (!withinTrack || currentMediaState.durationMs <= 0L) {
                            return@awaitEachGesture
                        }
                        down.consume()
                        val startX = down.position.x
                        val startProgress = localProgress.coerceIn(0f, 1f)
                        fun updateProgressFromDelta(deltaX: Float) {
                            localProgress = (startProgress + deltaX / widthPx).coerceIn(0f, 1f)
                        }
                        try {
                            currentGestureLockChange(true)
                            dragging = true
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                                if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                    break
                                }
                                updateProgressFromDelta(change.position.x - startX)
                                change.consume()
                            }
                            skipNextProgressSync = true
                            seekToProgress(localProgress)
                        } finally {
                            dragging = false
                            currentGestureLockChange(false)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight.dp)
            ) {
                val radius = size.height / 2f
                val corners = CornerRadius(radius, radius)
                drawRoundRect(
                    color = foregroundColor.copy(alpha = 0.22f),
                    size = size,
                    cornerRadius = corners,
                )
                val progress = localProgress.coerceIn(0f, 1f)
                if (progress > 0f) {
                    // A 1-2px layout produced by fillMaxWidth(progress) loses
                    // its corner radius on old renderers. Keep the first visible
                    // segment at least one track diameter so it is always a dot.
                    val activeWidth = (size.width * progress)
                        .coerceAtLeast(size.height)
                        .coerceAtMost(size.width)
                    drawRoundRect(
                        color = foregroundColor,
                        size = Size(activeWidth, size.height),
                        cornerRadius = corners,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMediaTime((localProgress.coerceIn(0f, 1f) * mediaState.durationMs).toLong()),
                color = secondaryTextColor,
                fontSize = timeFontSize
            )
            Text(
                text = formatMediaTime(mediaState.durationMs),
                color = secondaryTextColor,
                fontSize = timeFontSize
            )
        }
    }
}

@Composable
private fun MediaButton(
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    iconOffsetX: androidx.compose.ui.unit.Dp = 0.dp,
    foregroundColor: Color,
    onGestureLockChange: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "media_button_scale")
    val backgroundAlpha by animateFloatAsState(if (pressed) 0.28f else 0.18f, label = "media_button_alpha")
    Box(
        modifier = Modifier
            .requiredSize(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(foregroundColor.copy(alpha = backgroundAlpha))
            .border(
                width = 0.8.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (pressed) 0.52f else 0.38f),
                        Color.White.copy(alpha = 0.08f),
                    )
                ),
                shape = CircleShape,
            )
            .controlCenterPressGesture(
                pressedState = pressedState,
                onGestureLockChange = onGestureLockChange,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foregroundColor,
            modifier = Modifier
                .size(iconSize)
                .offset(x = iconOffsetX),
        )
    }
}

private fun Modifier.controlCenterPressGesture(
    pressedState: MutableState<Boolean>,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onGestureLockChange: (Boolean) -> Unit = {},
    onClick: () -> Unit
): Modifier {
    if (!enabled) return this
    return pointerInput(onClick, onLongClick, onGestureLockChange, enabled) {
        detectTapGestures(
            onPress = {
                val startTime = SystemClock.elapsedRealtime()
                onGestureLockChange(true)
                pressedState.value = true
                try {
                    tryAwaitRelease()
                    val remaining = CONTROL_CENTER_MIN_PRESS_MS - (SystemClock.elapsedRealtime() - startTime)
                    if (remaining > 0L) delay(remaining)
                } finally {
                    pressedState.value = false
                    onGestureLockChange(false)
                }
            },
            onTap = {
                onClick()
            },
            onLongPress = {
                onLongClick?.invoke()
            }
        )
    }
}

@Composable
private fun controlCenterGreeting(now: Calendar = Calendar.getInstance()): String {
    val hour = now.get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..10 -> stringResource(R.string.cc_greeting_morning)
        in 11..16 -> stringResource(R.string.cc_greeting_afternoon)
        else -> stringResource(R.string.cc_greeting_evening)
    }
}

@Composable
private fun rememberMediaState(context: Context, active: Boolean = true): ControlCenterMediaState {
    var state by remember(context) { mutableStateOf(ControlCenterMediaState()) }
    var retainedArtwork by remember(context) { mutableStateOf<ImageBitmap?>(null) }
    var retainedArtworkColor by remember(context) { mutableStateOf(Color(0xFF2F3B45)) }
    var retainedArtworkPackage by remember(context) { mutableStateOf<String?>(null) }
    var retainedArtworkIdentity by remember(context) { mutableStateOf<String?>(null) }
    var retainedAppIcon by remember(context) { mutableStateOf<ImageBitmap?>(null) }
    var retainedAppIconPackage by remember(context) { mutableStateOf<String?>(null) }
    // 这一层（控制中心本体和负一屏音乐卡片）都是常驻组合、靠位移移出屏外；
    // 不加门控的话轮询在表盘界面上也一直跑。active=false 时整段停掉，
    // 重新可见的第一帧立即刷新一次。
    LaunchedEffect(context, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            // 读取含 binder IPC（getActiveSessions）、图标包 mapping 与
            // 数百次 getPixel 取色/指纹，不允许钉在主线程上做。
            val next = withContext(Dispatchers.Default) { readMediaState(context) }
            state = when {
                next.controller == null -> {
                    retainedArtwork = null
                    retainedArtworkPackage = null
                    retainedArtworkIdentity = null
                    retainedAppIcon = null
                    retainedAppIconPackage = null
                    next
                }
                next.artwork != null &&
                    next.appPackage == retainedArtworkPackage &&
                    next.artworkIdentity == retainedArtworkIdentity &&
                    retainedArtwork != null -> {
                    // MediaSession frequently returns a new ImageBitmap wrapper for the
                    // same cover on every 500 ms progress poll. Keep the stable instance,
                    // otherwise both cover and blurred-background Crossfade restart forever.
                    next.copy(
                        artwork = retainedArtwork,
                        artworkColor = retainedArtworkColor,
                        appIcon = stableMediaAppIcon(
                            next = next,
                            retained = retainedAppIcon,
                            retainedPackage = retainedAppIconPackage,
                        ),
                    )
                }
                next.artwork != null -> {
                    retainedArtwork = next.artwork
                    retainedArtworkColor = next.artworkColor
                    retainedArtworkPackage = next.appPackage
                    retainedArtworkIdentity = next.artworkIdentity
                    val stableIcon = stableMediaAppIcon(
                        next = next,
                        retained = retainedAppIcon,
                        retainedPackage = retainedAppIconPackage,
                    )
                    if (next.appPackage != retainedAppIconPackage || retainedAppIcon == null) {
                        retainedAppIcon = next.appIcon
                        retainedAppIconPackage = next.appPackage
                    }
                    next.copy(appIcon = stableIcon)
                }
                retainedArtwork != null && next.appPackage == retainedArtworkPackage -> {
                    // 切歌时 metadata 往往先更新文字、稍后才补封面。保持上一张
                    // 封面，待新封面到达后再由 Crossfade 交换。
                    next.copy(
                        artwork = retainedArtwork,
                        artworkIdentity = retainedArtworkIdentity,
                        artworkColor = retainedArtworkColor,
                        appIcon = stableMediaAppIcon(
                            next = next,
                            retained = retainedAppIcon,
                            retainedPackage = retainedAppIconPackage,
                        ),
                    )
                }
                else -> {
                    retainedAppIcon = next.appIcon
                    retainedAppIconPackage = next.appPackage
                    next
                }
            }
            if (next.appPackage != retainedAppIconPackage || retainedAppIcon == null) {
                retainedAppIcon = next.appIcon
                retainedAppIconPackage = next.appPackage
            }
            delay(500)
        }
    }
    return state
}

private fun stableMediaAppIcon(
    next: ControlCenterMediaState,
    retained: ImageBitmap?,
    retainedPackage: String?,
): ImageBitmap? = if (next.appPackage == retainedPackage && retained != null) retained else next.appIcon

private fun readMediaState(context: Context): ControlCenterMediaState {
    val manager = context.getSystemService(MediaSessionManager::class.java) ?: return ControlCenterMediaState()
    val component = ComponentName(context, WLauncherNotificationListener::class.java)
    val controller = runCatching {
        manager.getActiveSessions(component).firstOrNull()
    }.getOrNull() ?: return ControlCenterMediaState()
    val metadata = controller.metadata
    val playbackState = controller.playbackState
    val appIconBitmap = loadMediaAppIconBitmap(context, controller.packageName)
    val artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    // Bluetooth relays may continuously rewrite title/artist while the actual
    // cover stays unchanged. Animation identity therefore comes from bitmap
    // content (with URI fallback), never from textual metadata.
    val artworkIdentity = artworkBitmap?.let { bitmap ->
        listOf(
            controller.packageName,
            mediaArtworkContentFingerprint(bitmap),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI),
            metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
        ).joinToString("\u0000")
    }
    val customActions = playbackState?.customActions.orEmpty().mapNotNull { action ->
        val title = action.name?.toString().orEmpty()
        val actionIcon = loadCustomActionIconBitmap(context, controller.packageName, action.icon)
        if (action.action.isBlank() && title.isBlank()) return@mapNotNull null
        ControlCenterMediaAction(
            action = action.action,
            title = title.ifBlank { action.action },
            extras = action.extras,
            icon = actionIcon?.asImageBitmap()
        )
    }.take(2)
    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: 0L
    val rawPosition = playbackState?.position?.coerceAtLeast(0L) ?: 0L
    val position = if (
        playbackState?.state == PlaybackState.STATE_PLAYING &&
        duration > 0L &&
        playbackState.lastPositionUpdateTime > 0L
    ) {
        val elapsed = SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime
        (rawPosition + elapsed).coerceIn(0L, duration)
    } else {
        rawPosition.coerceAtMost(duration.takeIf { it > 0L } ?: rawPosition)
    }
    val progress = if (duration > 0L) position.toFloat() / duration else 0f
    return ControlCenterMediaState(
        controller = controller,
        title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: context.getString(R.string.cc_media_not_playing),
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: controller.packageName.substringAfterLast('.'),
        // 应用图标只保留为右上角的来源按钮，不能冒充音乐封面。没有任何
        // 历史封面时让 UI 显示默认“未在播放”图标。
        artwork = artworkBitmap?.asImageBitmap(),
        artworkIdentity = artworkIdentity,
        appIcon = appIconBitmap?.asImageBitmap(),
        customActions = customActions,
        artworkColor = artworkBitmap?.let(::averageBitmapColor) ?: Color(0xFF2F3B45),
        playing = playbackState?.state == PlaybackState.STATE_PLAYING,
        progress = progress,
        positionMs = position,
        durationMs = duration,
        appPackage = controller.packageName
    )
}

private fun loadMediaAppIconBitmap(context: Context, packageName: String): Bitmap? {
    val repositoryIcon = runCatching {
        FlueApplication.repositories(context).appRepository.loadDisplayIconForPackage(
            packageName = packageName,
            iconSize = 128,
            preferPlainIcon = true
        )
    }.getOrNull()?.asAndroidBitmap()
    if (repositoryIcon != null) return repositoryIcon
    return runCatching {
        context.packageManager.getApplicationIcon(packageName).toBitmap(width = 128, height = 128)
    }.getOrNull()
}

private fun loadCustomActionIconBitmap(
    context: Context,
    packageName: String,
    iconResId: Int
): Bitmap? {
    if (iconResId == 0) return null
    return runCatching {
        val packContext = context.createPackageContext(
            packageName,
            Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
        )
        packContext.resources.getDrawable(iconResId, packContext.theme)
            .toBitmap(width = 96, height = 96)
    }.getOrNull()
}

private fun openMusicApp(context: Context, packageName: String?) {
    if (packageName.isNullOrBlank()) return
    runCatching {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openMediaOutputSwitcher(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return runCatching {
        SystemOutputSwitcherDialogController.showDialog(context)
    }.getOrDefault(false)
}

private fun openNotificationAccessSettings(context: Context) {
    openSettings(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}

private fun formatMediaTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(Locale.US, minutes, seconds)
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

private fun mediaArtworkContentFingerprint(bitmap: Bitmap): String {
    val width = bitmap.width.coerceAtLeast(1)
    val height = bitmap.height.coerceAtLeast(1)
    return runCatching {
        var hash = 0xcbf29ce484222325UL
        // A stable 7 x 7 grid is cheap enough for the 500 ms metadata poll and
        // distinguishes real artwork changes without hashing full-size covers.
        repeat(7) { row ->
            repeat(7) { column ->
                val x = (column * (width - 1) / 6).coerceIn(0, width - 1)
                val y = (row * (height - 1) / 6).coerceIn(0, height - 1)
                hash = (hash xor bitmap.getPixel(x, y).toUInt().toULong()) * 0x100000001b3UL
            }
        }
        "${width}x$height:${hash.toString(16)}"
    }.getOrElse {
        // Hardware bitmaps may reject getPixel on a few vendor builds. URI
        // metadata in the caller still participates; dimensions are the safe
        // deterministic fallback and never include title/artist.
        "${width}x$height"
    }
}

private fun readBatteryStatus(context: Context): BatteryStatusSnapshot {
    val batteryManager = context.getSystemService(BatteryManager::class.java)
    val level = batteryManager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.coerceIn(0, 100)
        ?: 0
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL ||
        plugged != 0
    return BatteryStatusSnapshot(level = level, charging = charging)
}

private fun readMusicVolume(audioManager: AudioManager?): Float {
    if (audioManager == null) return 0f
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun quantizeMusicVolume(audioManager: AudioManager?, value: Float): Float {
    if (audioManager == null) return value.coerceIn(0f, 1f)
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return ((value.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)).toFloat() / max
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
        Toast.makeText(
            context,
            context.getString(R.string.toast_write_settings_permission_required),
            Toast.LENGTH_SHORT,
        ).show()
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

private fun readConnectivitySnapshot(context: Context): ConnectivitySnapshot {
    return ConnectivitySnapshot(
        wifiActive = isWifiEnabled(context),
        cellularActive = isCellularDataEnabled(context)
    )
}

private fun isBluetoothLikelyEnabled(context: Context): Boolean {
    if (!hasBluetoothAccessPermission(context)) {
        return false
    }
    return runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)
}

private fun requestBluetoothToggle(
    context: Context,
    enableLauncher: ActivityResultLauncher<Intent>,
    permissionLauncher: ActivityResultLauncher<String>,
    onPermissionPending: () -> Unit
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothAccessPermission(context)) {
        onPermissionPending()
        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        return
    }
    val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return
    val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
    if (!enabled) {
        requestBluetoothEnable(enableLauncher)
    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        @Suppress("DEPRECATION")
        runCatching { adapter.disable() }
    } else {
        openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
    }
}

private fun requestBluetoothEnable(enableLauncher: ActivityResultLauncher<Intent>) {
    runCatching {
        enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
}

private fun isDndEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
}

private fun toggleDnd(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (!manager.isNotificationPolicyAccessGranted) {
        return
    }
    val next = if (isDndEnabled(context)) {
        NotificationManager.INTERRUPTION_FILTER_ALL
    } else {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY
    }
    manager.setInterruptionFilter(next)
}

private fun openDndSettings(context: Context) {
    val actions = listOf(
        "android.settings.ZEN_MODE_SETTINGS",
        "android.settings.ZEN_MODE_PRIORITY_SETTINGS",
        Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
    )
    val action = actions.firstOrNull { Intent(it).resolveActivity(context.packageManager) != null }
        ?: Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
    openSettings(context, action)
}

private fun openSettings(context: Context, action: String, data: Uri? = null) {
    runCatching {
        context.startActivity(
            Intent(action).apply {
                data?.let { setData(it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

private fun hasBluetoothAccessPermission(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_CONNECT
    } else {
        Manifest.permission.BLUETOOTH
    }
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun openInternetConnectivityPanel(
    context: Context,
    launcher: ActivityResultLauncher<Intent>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        val resolved = intent.resolveActivity(context.packageManager)
        if (resolved != null) {
            runCatching { launcher.launch(intent) }.onSuccess { return }
        }
    }
    openSettings(context, Settings.ACTION_WIRELESS_SETTINGS)
}

private fun toggleWifiLegacy(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CHANGE_WIFI_STATE
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
    return runCatching {
        @Suppress("DEPRECATION")
        wifiManager.setWifiEnabled(!isWifiEnabled(context))
    }.getOrDefault(false)
}

private fun isWifiEnabled(context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_WIFI_STATE
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (wifiManager.wifiState) {
                WifiManager.WIFI_STATE_ENABLED,
                WifiManager.WIFI_STATE_ENABLING -> true
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled
        }
    }.getOrDefault(false)
}

private fun isCellularDataEnabled(context: Context): Boolean {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
        return false
    }
    val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return false
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            telephonyManager.isDataEnabled
        } else {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
    }.getOrDefault(false)
}
