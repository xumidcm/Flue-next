package com.flue.launcher.ui.notification

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.common.instantPressGesture
import com.flue.launcher.ui.common.rememberPressedState
import com.flue.launcher.ui.theme.WatchColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val NOTIFICATION_CARD_WIDTH_RATIO = 0.884f
private const val NOTIFICATION_DISMISS_THRESHOLD = 88f

private val NotificationFrameShape = RoundedCornerShape(40.dp)
private val NotificationCardShape = RoundedCornerShape(28.dp)

private data class NotificationBatterySnapshot(
    val level: Int = 0
) {
    val isLow: Boolean
        get() = level <= 20
}

@Composable
fun NotificationLayer(
    notificationGroups: List<NotificationGroupUi>,
    notificationAccessGranted: Boolean,
    onDismissToStack: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onDismissGroup: (String) -> Unit,
    onDismissNotification: (String) -> Unit,
    onOpenNotification: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val battery = rememberNotificationBatterySnapshot()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val overscroll = remember { Animatable(0f) }

    val nestedScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                if (source != NestedScrollSource.Drag) return androidx.compose.ui.geometry.Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop) {
                    scope.launch {
                        overscroll.snapTo((overscroll.value + available.y * 0.35f).coerceAtMost(180f))
                    }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (overscroll.value > 0f && available.y < 0f) {
                    scope.launch {
                        overscroll.snapTo((overscroll.value + available.y).coerceAtLeast(0f))
                    }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll.value > 80f) {
                    overscroll.snapTo(0f)
                    onDismissToStack()
                    return available
                }
                if (overscroll.value != 0f) {
                    overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(NotificationFrameShape)
            .background(WatchColors.Black)
            .nestedScroll(nestedScrollConnection)
    ) {
        val contentWidth = maxWidth * NOTIFICATION_CARD_WIDTH_RATIO
        val screenHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val screenCenterY = screenHeightPx / 2f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = overscroll.value }
                .padding(top = 12.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!notificationAccessGranted) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    NotificationPermissionCard(
                        modifier = Modifier.width(contentWidth),
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    )
                }
            } else if (notificationGroups.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    NotificationEmptyCard(modifier = Modifier.width(contentWidth))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 8.dp)
                ) {
                    itemsIndexed(notificationGroups, key = { _, group -> group.packageName }) { index, group ->
                        val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == index }
                        val scale = notificationItemScale(itemInfo, screenCenterY, screenHeightPx)
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            NotificationGroupSection(
                                modifier = Modifier.width(contentWidth),
                                group = group,
                                scale = scale,
                                onToggleExpand = { onToggleGroup(group.packageName) },
                                onDismissGroup = { onDismissGroup(group.packageName) },
                                onDismissNotification = onDismissNotification,
                                onOpenNotification = onOpenNotification
                            )
                        }
                    }
                }
            }

            BatteryPill(
                battery = battery,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun NotificationPermissionCard(
    modifier: Modifier,
    onOpenSettings: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.964f else 1f,
        animationSpec = spring(stiffness = 860f, dampingRatio = 0.72f),
        label = "notification_permission_scale"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(NotificationCardShape)
            .background(Color(0xFF353535))
            .instantPressGesture(pressedState, onClick = onOpenSettings)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD9D9D9)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = Color(0xFF2B2B2B),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "开启通知访问",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "完成授权后，这里会按应用分组显示通知",
                    color = WatchColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun NotificationEmptyCard(modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(NotificationCardShape)
            .background(Color(0xFF353535))
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD9D9D9)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = Color(0xFF2B2B2B),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "暂无通知",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "有新消息时会按应用堆叠显示在这里",
                    color = WatchColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun NotificationGroupSection(
    modifier: Modifier,
    group: NotificationGroupUi,
    scale: Float,
    onToggleExpand: () -> Unit,
    onDismissGroup: () -> Unit,
    onDismissNotification: (String) -> Unit,
    onOpenNotification: (String) -> Boolean
) {
    AnimatedContent(
        targetState = group.expanded,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = tween(220)) { it / 4 }) togetherWith
                (fadeOut(animationSpec = tween(160)) + slideOutVertically(animationSpec = tween(160)) { -it / 6 })
        },
        label = "notification_group_expand"
    ) { expanded ->
        if (expanded) {
            ExpandedNotificationGroup(
                modifier = modifier,
                group = group,
                scale = scale,
                onCollapse = onToggleExpand,
                onDismissNotification = onDismissNotification,
                onOpenNotification = onOpenNotification
            )
        } else {
            CollapsedNotificationGroup(
                modifier = modifier,
                group = group,
                scale = scale,
                onExpand = onToggleExpand,
                onDismissGroup = onDismissGroup
            )
        }
    }
}

@Composable
private fun CollapsedNotificationGroup(
    modifier: Modifier,
    group: NotificationGroupUi,
    scale: Float,
    onExpand: () -> Unit,
    onDismissGroup: () -> Unit
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = scale.coerceIn(0.35f, 1f)
                }
        ) {
            NotificationSummaryBubble(
                modifier = Modifier.fillMaxWidth(),
                icon = group.icon,
                title = group.appLabel,
                subtitle = group.latestSummary,
                trailing = formatNotificationTime(group.latestTime),
                onClick = onExpand
            )
            if (group.count > 1) {
                Text(
                    text = "+${group.count}条新消息",
                    color = WatchColors.TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp)
                )
            }
        }
    }

    if (group.children.any(NotificationEntryUi::isClearable)) {
        SwipeRevealDelete(
            modifier = Modifier.fillMaxWidth(),
            enabled = true,
            onDelete = onDismissGroup,
            content = content
        )
    } else {
        content()
    }
}

@Composable
private fun ExpandedNotificationGroup(
    modifier: Modifier,
    group: NotificationGroupUi,
    scale: Float,
    onCollapse: () -> Unit,
    onDismissNotification: (String) -> Unit,
    onOpenNotification: (String) -> Boolean
) {
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale.coerceIn(0.35f, 1f)
            }
            .animateContentSize()
    ) {
        NotificationExpandedHeader(
            appLabel = group.appLabel,
            count = group.count,
            onClick = onCollapse
        )
        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            group.children.forEach { entry ->
                SwipeRevealDelete(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = entry.isClearable,
                    onDelete = { onDismissNotification(entry.key) }
                ) {
                    NotificationEntryBubble(
                        modifier = Modifier.fillMaxWidth(),
                        entry = entry,
                        onClick = { onOpenNotification(entry.key) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationExpandedHeader(
    appLabel: String,
    count: Int,
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 840f, dampingRatio = 0.74f),
        label = "notification_header_press_scale"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .instantPressGesture(pressedState, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = appLabel,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "${groupCountLabel(count)}",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun NotificationSummaryBubble(
    modifier: Modifier,
    icon: ImageBitmap?,
    title: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 840f, dampingRatio = 0.74f),
        label = "notification_summary_press_scale"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .instantPressGesture(pressedState, onClick = onClick)
    ) {
        NotificationBubbleChrome(
            modifier = Modifier.fillMaxWidth(),
            icon = icon,
            title = title,
            subtitle = subtitle,
            trailing = trailing
        )
    }
}

@Composable
private fun NotificationEntryBubble(
    modifier: Modifier,
    entry: NotificationEntryUi,
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 840f, dampingRatio = 0.74f),
        label = "notification_entry_press_scale"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .instantPressGesture(
                pressedState,
                enabled = entry.contentIntentAvailable,
                onClick = onClick
            )
    ) {
        NotificationBubbleChrome(
            modifier = Modifier.fillMaxWidth(),
            icon = entry.icon,
            title = entry.title.ifBlank { entry.appLabel },
            subtitle = entry.text.ifBlank { "打开通知" },
            trailing = formatNotificationTime(entry.time)
        )
    }
}

@Composable
private fun NotificationBubbleChrome(
    modifier: Modifier,
    icon: ImageBitmap?,
    title: String,
    subtitle: String,
    trailing: String,
    badgeCount: Int? = null
) {
    Box(
        modifier = modifier
            .heightIn(min = 74.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp)
                .clip(NotificationCardShape)
                .background(Color(0xFF353535))
                .padding(start = 36.dp, end = 18.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        badgeCount?.let {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.10f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = it.toString(),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
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
private fun SwipeRevealDelete(
    modifier: Modifier,
    enabled: Boolean,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .size(62.dp)
                .clip(CircleShape)
                .background(Color(0xFF8E4F64))
                .clickable(enabled = enabled, onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }

        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            if (abs(dragAmount.x) <= abs(dragAmount.y)) return@detectDragGestures
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount.x).coerceIn(-124f, 0f))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value < -NOTIFICATION_DISMISS_THRESHOLD) {
                                    offsetX.animateTo(-124f, tween(180))
                                    onDelete()
                                } else {
                                    offsetX.animateTo(0f, tween(180))
                                }
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}

@Composable
private fun rememberNotificationBatterySnapshot(): NotificationBatterySnapshot {
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
    return NotificationBatterySnapshot(batteryLevel)
}

@Composable
private fun BatteryPill(
    battery: NotificationBatterySnapshot,
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

private fun notificationItemScale(
    itemInfo: androidx.compose.foundation.lazy.LazyListItemInfo?,
    screenCenterY: Float,
    screenHeight: Float
): Float {
    if (itemInfo == null) return 0.92f
    val itemCenterY = itemInfo.offset + itemInfo.size / 2f
    val distance = abs(itemCenterY - screenCenterY)
    val maxDistance = screenHeight / 2f
    val progress = (distance / maxDistance).coerceIn(0f, 1f)
    return 1f - 0.14f * progress
}

private fun formatNotificationTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun groupCountLabel(count: Int): String = "${count}条通知"
