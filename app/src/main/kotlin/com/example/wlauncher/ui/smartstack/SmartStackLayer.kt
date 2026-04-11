package com.flue.launcher.ui.smartstack

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.common.instantPressGesture
import com.flue.launcher.ui.common.rememberPressedState
import com.flue.launcher.ui.drawer.AppBubble
import com.flue.launcher.ui.notification.NotificationGroupUi
import com.flue.launcher.ui.theme.WatchColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val SIDE_SCREEN_CONTENT_WIDTH_RATIO = 0.884f
private const val SIDE_SCREEN_DISMISS_THRESHOLD = 86f
private const val SIDE_SCREEN_NOTIFICATION_PULL_THRESHOLD = 110f

private val SideScreenFrameShape = RoundedCornerShape(40.dp)
private val SideScreenPanelShape = RoundedCornerShape(28.dp)
private val SideScreenCardShape = RoundedCornerShape(28.dp)

private data class SideScreenClockSnapshot(
    val time: String,
    val date: String
)

private data class BatterySnapshot(
    val level: Int = 0
) {
    val isLow: Boolean
        get() = level <= 20
}

@Composable
fun SmartStackLayer(
    apps: List<AppInfo>,
    sideScreenShortcuts: List<String?>,
    latestNotificationGroup: NotificationGroupUi?,
    notificationAccessGranted: Boolean,
    onOpenNotifications: () -> Unit,
    onLaunchApp: (AppInfo, Offset) -> Unit,
    onSetShortcut: (Int, String?) -> Unit,
    onRemoveShortcut: (Int) -> Unit,
    onDismissToFace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clock = rememberSideScreenClockSnapshot()
    val battery = rememberBatterySnapshot()
    val slotCenters = remember { mutableStateMapOf<Int, Offset>() }
    val shortcutApps = remember(apps, sideScreenShortcuts) {
        List(sideScreenShortcuts.size) { index ->
            sideScreenShortcuts.getOrNull(index)?.let { key ->
                apps.firstOrNull { it.componentKey == key }
            }
        }
    }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var overscrollTarget by remember { mutableFloatStateOf(0f) }
    val overscrollY by animateFloatAsState(
        targetValue = overscrollTarget,
        animationSpec = spring(stiffness = 440f, dampingRatio = 0.78f),
        label = "side_screen_overscroll"
    )
    var pickerSlot by remember { mutableStateOf<Int?>(null) }
    var removeSlot by remember { mutableStateOf<Int?>(null) }
    val modalVisible = pickerSlot != null || removeSlot != null

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(SideScreenFrameShape)
            .background(WatchColors.Black)
            .pointerInput(notificationAccessGranted, modalVisible) {
                if (modalVisible) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        dragDx = 0f
                        dragDy = 0f
                    },
                    onDrag = { change, dragAmount ->
                        dragDx += dragAmount.x
                        dragDy += dragAmount.y
                        val horizontalDominant = abs(dragDx) > abs(dragDy)
                        if (horizontalDominant) {
                            if (dragDx < -SIDE_SCREEN_DISMISS_THRESHOLD) {
                                change.consume()
                                onDismissToFace()
                            }
                        } else {
                            change.consume()
                            overscrollTarget = (overscrollTarget + dragAmount.y * 0.35f).coerceIn(-180f, 120f)
                        }
                    },
                    onDragEnd = {
                        if (dragDy < -SIDE_SCREEN_NOTIFICATION_PULL_THRESHOLD) {
                            overscrollTarget = 0f
                            onOpenNotifications()
                        } else {
                            overscrollTarget = 0f
                        }
                    },
                    onDragCancel = { overscrollTarget = 0f }
                )
            }
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val screenHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val contentWidth = maxWidth * SIDE_SCREEN_CONTENT_WIDTH_RATIO
        val quickPanelHeight = (contentWidth * 0.58f).coerceAtLeast(152.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = overscrollY }
                .padding(top = 10.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = clock.time,
                color = Color.White,
                fontSize = 31.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = clock.date,
                color = WatchColors.TextSecondary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(14.dp))

            QuickLaunchPanel(
                modifier = Modifier
                    .width(contentWidth)
                    .height(quickPanelHeight),
                apps = shortcutApps,
                slotCenters = slotCenters,
                onAddShortcut = { pickerSlot = it },
                onLongPressShortcut = { removeSlot = it },
                onLaunchShortcut = { slotIndex, app ->
                    val center = slotCenters[slotIndex] ?: Offset(screenWidthPx / 2f, screenHeightPx / 2f)
                    onLaunchApp(
                        app,
                        Offset(
                            x = center.x / screenWidthPx,
                            y = center.y / screenHeightPx
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            SideScreenNotificationPreview(
                modifier = Modifier.width(contentWidth),
                latestNotificationGroup = latestNotificationGroup,
                notificationAccessGranted = notificationAccessGranted,
                onClick = onOpenNotifications
            )

            Spacer(modifier = Modifier.weight(1f))

            BatteryPill(
                battery = battery,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }

    pickerSlot?.let { activeSlot ->
        SideScreenPickerOverlay(
            apps = apps,
            onSelect = { app ->
                onSetShortcut(activeSlot, app.componentKey)
            },
            onDismiss = { pickerSlot = null }
        )
    }

    removeSlot?.let { slotIndex ->
        shortcutApps.getOrNull(slotIndex)?.let { app ->
            SideScreenRemoveOverlay(
                app = app,
                onRemove = {
                    onRemoveShortcut(slotIndex)
                },
                onDismiss = { removeSlot = null }
            )
        }
    }
}

@Composable
private fun QuickLaunchPanel(
    modifier: Modifier,
    apps: List<AppInfo?>,
    slotCenters: MutableMap<Int, Offset>,
    onAddShortcut: (Int) -> Unit,
    onLongPressShortcut: (Int) -> Unit,
    onLaunchShortcut: (Int, AppInfo) -> Unit
) {
    Box(
        modifier = modifier
            .clip(SideScreenPanelShape)
            .background(Color(0xFF353535))
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            apps.chunked(3).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEachIndexed { columnIndex, app ->
                        val slotIndex = rowIndex * 3 + columnIndex
                        Box(
                            modifier = Modifier.onGloballyPositioned { coords ->
                                val pos = coords.positionInRoot()
                                slotCenters[slotIndex] = Offset(
                                    x = pos.x + coords.size.width / 2f,
                                    y = pos.y + coords.size.height / 2f
                                )
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            if (app == null) {
                                AddShortcutBubble { onAddShortcut(slotIndex) }
                            } else {
                                AppBubble(
                                    icon = app.cachedIcon,
                                    size = 58.dp,
                                    onClick = { onLaunchShortcut(slotIndex, app) },
                                    onLongClick = { onLongPressShortcut(slotIndex) },
                                    pressScaleTarget = 0.9f,
                                    pressAnimationDurationMillis = 200
                                )
                            }
                        }
                    }
                    repeat(3 - row.size) {
                        Spacer(modifier = Modifier.size(58.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SideScreenNotificationPreview(
    modifier: Modifier,
    latestNotificationGroup: NotificationGroupUi?,
    notificationAccessGranted: Boolean,
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.964f else 1f,
        animationSpec = spring(stiffness = 860f, dampingRatio = 0.72f),
        label = "side_preview_press_scale"
    )
    val title = when {
        !notificationAccessGranted -> "开启通知访问"
        latestNotificationGroup == null -> "暂无通知"
        else -> latestNotificationGroup.appLabel
    }
    val subtitle = when {
        !notificationAccessGranted -> "下滑进入通知中心后完成授权"
        latestNotificationGroup == null -> "副一屏上滑可打开通知中心"
        else -> latestNotificationGroup.latestSummary
    }
    val trailing = latestNotificationGroup?.let { formatClockTime(it.latestTime) }.orEmpty()

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .instantPressGesture(pressedState, onClick = onClick)
    ) {
        NotificationPreviewBubble(
            modifier = Modifier.fillMaxWidth(),
            icon = latestNotificationGroup?.icon,
            title = title,
            subtitle = subtitle,
            trailing = trailing
        )
    }
}

@Composable
private fun NotificationPreviewBubble(
    modifier: Modifier,
    icon: ImageBitmap?,
    title: String,
    subtitle: String,
    trailing: String
) {
    Box(
        modifier = modifier
            .heightIn(min = 74.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp)
                .clip(SideScreenCardShape)
                .background(Color(0xFF353535))
                .padding(start = 36.dp, end = 18.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = WatchColors.TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 2
                    )
                }
                if (trailing.isNotBlank()) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = trailing,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFFD9D9D9)),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = Color(0xFF2B2B2B),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun AddShortcutBubble(onClick: () -> Unit) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.958f else 1f,
        animationSpec = spring(stiffness = 860f, dampingRatio = 0.72f),
        label = "side_add_press_scale"
    )
    Box(
        modifier = Modifier
            .size(58.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .instantPressGesture(pressedState, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun SideScreenPickerOverlay(
    apps: List<AppInfo>,
    onSelect: (AppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    SideScreenModal(
        onDismissRequest = onDismiss
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1E1E1E))
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = "添加快捷启动",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(apps, key = { it.componentKey }) { app ->
                    SideScreenPickerRow(
                        app = app,
                        onClick = {
                            onSelect(app)
                            dismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SideScreenPickerRow(
    app: AppInfo,
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 840f, dampingRatio = 0.74f),
        label = "side_picker_item_scale"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) Color(0xFF303030) else Color.Transparent)
            .instantPressGesture(pressedState, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = app.cachedIcon,
            contentDescription = null,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = app.label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = app.packageName,
                color = WatchColors.TextTertiary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SideScreenRemoveOverlay(
    app: AppInfo,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    SideScreenModal(
        onDismissRequest = onDismiss
    ) { dismiss ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2C2C2E))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onRemove()
                            dismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "移除",
                        color = Color(0xFFFF453A),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.W500
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Image(
                bitmap = app.cachedIcon,
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = app.label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.W600
            )
        }
    }
}

@Composable
private fun SideScreenModal(
    onDismissRequest: () -> Unit,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    val dismissInteraction = remember { MutableInteractionSource() }
    val blockInteraction = remember { MutableInteractionSource() }
    var visible by remember { mutableStateOf(false) }

    fun dismiss() {
        visible = false
    }

    LaunchedEffect(Unit) {
        visible = true
    }

    BackHandler {
        dismiss()
    }

    LaunchedEffect(visible) {
        if (!visible) {
            delay(220)
            onDismissRequest()
        }
    }

    val modalAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(stiffness = 720f, dampingRatio = 0.85f),
        label = "side_modal_alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.84f,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.8f),
        label = "side_modal_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = modalAlpha }
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                indication = null,
                interactionSource = dismissInteraction,
                onClick = { dismiss() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(
                    indication = null,
                    interactionSource = blockInteraction,
                    onClick = {}
                )
        ) {
            content(::dismiss)
        }
    }
}

@Composable
private fun rememberSideScreenClockSnapshot(): SideScreenClockSnapshot {
    var snapshot by remember {
        mutableStateOf(SideScreenClockSnapshot(time = "--:--", date = "--"))
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            val locale = Locale.getDefault()
            val timeFormatter = SimpleDateFormat("HH:mm", locale)
            val dateFormatter = if (locale.language.startsWith("zh")) {
                SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
            } else {
                SimpleDateFormat("MMM d, EEEE", locale)
            }
            snapshot = SideScreenClockSnapshot(
                time = timeFormatter.format(now),
                date = dateFormatter.format(now)
            )
            delay(1000)
        }
    }
    return snapshot
}

@Composable
private fun rememberBatterySnapshot(): BatterySnapshot {
    val context = LocalContext.current
    var batteryLevel by remember(context) { mutableIntStateOf(0) }
    DisposableEffect(context) {
        fun readBatteryLevel(): Int {
            val batteryManager = context.getSystemService(BatteryManager::class.java)
            return (batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0).coerceIn(0, 100)
        }

        batteryLevel = readBatteryLevel()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                batteryLevel = readBatteryLevel()
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return BatterySnapshot(batteryLevel)
}

@Composable
private fun BatteryPill(
    battery: BatterySnapshot,
    modifier: Modifier = Modifier
) {
    val tint = if (battery.isLow) WatchColors.ActiveRed else WatchColors.ActiveGreen
    Box(
        modifier = modifier
            .width(56.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141414))
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((battery.level / 100f).coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.24f))
        )
        Text(
            text = "${battery.level}%",
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

private fun formatClockTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
