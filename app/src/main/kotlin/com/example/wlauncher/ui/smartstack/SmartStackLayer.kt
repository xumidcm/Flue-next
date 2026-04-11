package com.flue.launcher.ui.smartstack

import android.content.Intent
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

private const val SIDE_SCREEN_NOTIFICATION_PULL_THRESHOLD = 110f

private data class SideScreenClockSnapshot(
    val date: String,
    val time: String,
    val greeting: String
)

private data class BatterySnapshot(
    val level: Int = 0
) {
    val isLow: Boolean
        get() = level <= 20
}

@OptIn(ExperimentalFoundationApi::class)
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
    val context = LocalContext.current
    val clock = rememberSideScreenClockSnapshot()
    val battery = rememberBatterySnapshot()
    val slotCenters = remember { mutableStateMapOf<Int, Offset>() }
    val quickApps = remember(apps, sideScreenShortcuts) {
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
        animationSpec = spring(stiffness = 430f, dampingRatio = 0.78f),
        label = "side_screen_overscroll"
    )
    var pickerSlot by remember { mutableStateOf<Int?>(null) }
    var removeSlot by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(notificationAccessGranted) {
                detectDragGestures(
                    onDragStart = {
                        dragDx = 0f
                        dragDy = 0f
                    },
                    onDrag = { change, dragAmount ->
                        dragDx += dragAmount.x
                        dragDy += dragAmount.y
                        val horizontalDominant = kotlin.math.abs(dragDx) > kotlin.math.abs(dragDy)
                        if (horizontalDominant) {
                            if (dragDx < -86f) {
                                change.consume()
                                onDismissToFace()
                            }
                        } else {
                            change.consume()
                            overscrollTarget = (overscrollTarget + dragAmount.y * 0.34f).coerceIn(-140f, 180f)
                        }
                    },
                    onDragEnd = {
                        if (dragDy > SIDE_SCREEN_NOTIFICATION_PULL_THRESHOLD && notificationAccessGranted) {
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = overscrollY }
                .padding(top = 24.dp, start = 18.dp, end = 18.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = clock.date,
                color = WatchColors.TextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = clock.time,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(12.dp))
                BatteryPill(battery = battery)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = clock.greeting,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF232338).copy(alpha = 0.96f),
                                Color(0xFF191A27).copy(alpha = 0.98f)
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    quickApps.chunked(3).forEachIndexed { rowIndex, row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            row.forEachIndexed { colIndex, app ->
                                val slotIndex = rowIndex * 3 + colIndex
                                Box(
                                    modifier = Modifier
                                        .onGloballyPositioned { coords ->
                                            val pos = coords.positionInRoot()
                                            slotCenters[slotIndex] = Offset(
                                                x = pos.x + coords.size.width / 2f,
                                                y = pos.y + coords.size.height / 2f
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (app == null) {
                                        AddShortcutBubble(onClick = { pickerSlot = slotIndex })
                                    } else {
                                        AppBubble(
                                            icon = app.cachedIcon,
                                            size = 58.dp,
                                            onClick = {
                                                val center = slotCenters[slotIndex]
                                                    ?: Offset(screenWidthPx / 2f, screenHeightPx / 2f)
                                                onLaunchApp(
                                                    app,
                                                    Offset(
                                                        center.x / screenWidthPx,
                                                        center.y / screenHeightPx
                                                    )
                                                )
                                            },
                                            onLongClick = { removeSlot = slotIndex },
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

            Spacer(modifier = Modifier.height(18.dp))

            NotificationPreviewCard(
                latestNotificationGroup = latestNotificationGroup,
                notificationAccessGranted = notificationAccessGranted,
                onOpenNotifications = onOpenNotifications,
                onOpenNotificationSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            )

            Spacer(modifier = Modifier.weight(1f))
        }

        AnimatedVisibility(visible = pickerSlot != null) {
            val activeSlot = pickerSlot
            if (activeSlot != null) {
                SideScreenAppPickerOverlay(
                    apps = apps,
                    onSelect = { app ->
                        onSetShortcut(activeSlot, app.componentKey)
                        pickerSlot = null
                    },
                    onDismiss = { pickerSlot = null }
                )
            }
        }

        AnimatedVisibility(visible = removeSlot != null) {
            val slot = removeSlot
            val selectedApp = slot?.let { quickApps.getOrNull(it) }
            if (slot != null && selectedApp != null) {
                SideScreenRemoveOverlay(
                    app = selectedApp,
                    onRemove = {
                        onRemoveShortcut(slot)
                        removeSlot = null
                    },
                    onDismiss = { removeSlot = null }
                )
            }
        }
    }
}

@Composable
private fun rememberSideScreenClockSnapshot(): SideScreenClockSnapshot {
    var snapshot by remember {
        mutableStateOf(SideScreenClockSnapshot(date = "--", time = "--:--", greeting = "早好~"))
    }
    LaunchedEffect(Unit) {
        while (true) {
            val locale = Locale.getDefault()
            val now = Date()
            val hour = java.util.Calendar.getInstance().apply { time = now }.get(java.util.Calendar.HOUR_OF_DAY)
            val greeting = when (hour) {
                in 5..10 -> "早好~"
                in 11..17 -> "午好~"
                else -> "晚好~"
            }
            val timeFmt = SimpleDateFormat("HH:mm", locale)
            val dateFmt = if (locale.language.startsWith("zh")) {
                SimpleDateFormat("M月d日 EEE", Locale.CHINA)
            } else {
                SimpleDateFormat("MMM d, EEE", locale)
            }
            snapshot = SideScreenClockSnapshot(
                date = dateFmt.format(now),
                time = timeFmt.format(now),
                greeting = greeting
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
        fun loadBattery(): Int {
            val batteryManager = context.getSystemService(BatteryManager::class.java)
            return (batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0).coerceIn(0, 100)
        }

        batteryLevel = loadBattery()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                batteryLevel = loadBattery()
            }
        }
        val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return BatterySnapshot(batteryLevel)
}

@Composable
private fun BatteryPill(battery: BatterySnapshot) {
    val fillColor = if (battery.isLow) Color(0xFFFF4D4F) else Color(0xFF30D158)
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2B2D34))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth((battery.level / 100f).coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(fillColor)
        )
        Text(
            text = "${battery.level}%",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun NotificationPreviewCard(
    latestNotificationGroup: NotificationGroupUi?,
    notificationAccessGranted: Boolean,
    onOpenNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.958f else 1f,
        animationSpec = spring(stiffness = 860f, dampingRatio = 0.72f),
        label = "side_preview_press_scale"
    )
    val title = when {
        !notificationAccessGranted -> "开启通知访问"
        latestNotificationGroup == null -> "暂无通知"
        else -> latestNotificationGroup.appLabel
    }
    val subtitle = when {
        !notificationAccessGranted -> "下滑通知中心前，先授予通知使用权"
        latestNotificationGroup == null -> "副一屏下滑可打开通知中心"
        else -> latestNotificationGroup.latestSummary
    }
    val trailing = latestNotificationGroup?.let { formatNotificationTime(it.latestTime) }
        ?: if (notificationAccessGranted) "" else "去开启"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFF111827).copy(alpha = 0.96f),
                        Color(0xFF362156).copy(alpha = 0.92f)
                    )
                )
            )
            .instantPressGesture(pressedState) {
                if (notificationAccessGranted) onOpenNotifications() else onOpenNotificationSettings()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (trailing.isNotBlank()) {
                    Text(
                        text = trailing,
                        color = WatchColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.84f),
                fontSize = 13.sp,
                maxLines = 2
            )
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
            tint = Color.White.copy(alpha = 0.86f),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun SideScreenAppPickerOverlay(
    apps: List<AppInfo>,
    onSelect: (AppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val dismissInteraction = remember { MutableInteractionSource() }
    val blockInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.74f))
            .clickable(indication = null, interactionSource = dismissInteraction, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1B1C28))
                .clickable(indication = null, interactionSource = blockInteraction) {}
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
                            .background(if (pressed) Color(0xFF232534) else Color.Transparent)
                            .instantPressGesture(pressedState) { onSelect(app) }
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
                            Text(app.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(app.packageName, color = WatchColors.TextTertiary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SideScreenRemoveOverlay(
    app: AppInfo,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val dismissInteraction = remember { MutableInteractionSource() }
    val blockInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(indication = null, interactionSource = dismissInteraction, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val panelWidth = (maxWidth * 0.74f).coerceIn(180.dp, 312.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(panelWidth)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(indication = null, interactionSource = blockInteraction) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2C2C2E))
                ) {
                    SideScreenMenuItem("移除", Color(0xFFFF453A), onClick = onRemove)
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
                Text(app.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W600)
            }
        }
    }
}

@Composable
private fun SideScreenMenuItem(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.W500)
    }
}

private fun formatNotificationTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
