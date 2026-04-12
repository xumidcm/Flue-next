package com.flue.launcher.ui.notification

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

private const val NOTIFICATION_CARD_WIDTH_RATIO = 0.884f

@Composable
fun NotificationLayer(
    notificationGroups: List<NotificationGroupUi>,
    notificationAccessGranted: Boolean,
    revealedNotificationTarget: NotificationRevealTarget?,
    onRevealTargetChange: (NotificationRevealTarget?) -> Unit,
    onDismissToStack: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onDismissGroup: (String) -> Unit,
    onDismissNotification: (String) -> Unit,
    onOpenNotification: (String, Offset) -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val batteryLevel = rememberBatteryLevel()
    val listState = rememberLazyListState()
    val overscroll = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dismissTransitionInFlight by remember { mutableStateOf(false) }
    val closeIfPulled: suspend () -> Unit = {
        if (!dismissTransitionInFlight) {
            if (overscroll.value > 80f) {
                dismissTransitionInFlight = true
                onRevealTargetChange(null)
                onDismissToStack()
            } else if (overscroll.value != 0f) {
                overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
            }
        }
    }
    val staticContentDismissModifier = Modifier.pointerInput(notificationAccessGranted, notificationGroups.size, dismissTransitionInFlight) {
        if (dismissTransitionInFlight) return@pointerInput
        detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
                if (dragAmount > 0f || overscroll.value > 0f) {
                    change.consume()
                    scope.launch {
                        overscroll.snapTo((overscroll.value + dragAmount * 0.35f).coerceIn(0f, 180f))
                    }
                }
            },
            onDragEnd = {
                scope.launch { closeIfPulled() }
            },
            onDragCancel = {
                scope.launch {
                    if (overscroll.value != 0f) {
                        overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                    }
                }
            }
        )
    }

    val nestedScroll = remember(listState, dismissTransitionInFlight) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (dismissTransitionInFlight) return androidx.compose.ui.geometry.Offset.Zero
                if (source != NestedScrollSource.Drag) return androidx.compose.ui.geometry.Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y * 0.35f).coerceAtMost(180f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (overscroll.value > 0f && available.y < 0f) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y).coerceAtLeast(0f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dismissTransitionInFlight) return available
                if (overscroll.value > 80f) {
                    closeIfPulled()
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
            .clip(RoundedCornerShape(40.dp))
            .background(WatchColors.Black)
            .nestedScroll(nestedScroll)
    ) {
        val contentWidth = maxWidth * NOTIFICATION_CARD_WIDTH_RATIO
        val bottomScrollPadding = ((maxHeight / 2) - 64.dp).coerceAtLeast(120.dp)
        val rows = remember(notificationGroups) { buildNotificationRows(notificationGroups) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = overscroll.value }
                .padding(top = 14.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                !notificationAccessGranted -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(staticContentDismissModifier),
                    contentAlignment = Alignment.Center
                ) {
                    PermissionCard(contentWidth) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                }

                notificationGroups.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(staticContentDismissModifier),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyCard(contentWidth)
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = bottomScrollPadding)
                ) {
                    items(
                        items = rows,
                        key = { it.key },
                        contentType = { it.contentType }
                    ) { row ->
                        NotificationRowContainer {
                            when (row) {
                                is NotificationListRow.GroupHeader -> Box(Modifier.width(contentWidth)) {
                                    GroupHeader(
                                        title = row.group.headerTitle,
                                        count = row.group.entries.size,
                                        showMeta = row.group.entries.size > 1 && row.group.expanded,
                                        expanded = row.group.expanded,
                                        onClick = { onToggleGroup(row.group.packageName) }
                                    )
                                }

                                is NotificationListRow.GroupBody -> NotificationGroupBody(
                                    width = contentWidth,
                                    group = row.group,
                                    revealedTarget = revealedNotificationTarget,
                                    onRevealTargetChange = onRevealTargetChange,
                                    onToggleGroup = { onToggleGroup(row.group.packageName) },
                                    onDismissGroup = { onDismissGroup(row.group.packageName) },
                                    onDismissNotification = onDismissNotification,
                                    onOpenNotification = onOpenNotification
                                )
                            }
                        }
                    }
                }
            }
            BatteryPill(batteryLevel, Modifier.padding(bottom = 4.dp))
        }
    }
}

private sealed interface NotificationListRow {
    val key: String
    val contentType: String

    data class GroupHeader(val group: NotificationGroupUi) : NotificationListRow {
        override val key: String = "header_${group.packageName}"
        override val contentType: String = "header"
    }

    data class GroupBody(val group: NotificationGroupUi) : NotificationListRow {
        override val key: String = "body_${group.packageName}"
        override val contentType: String = "body"
    }
}

private fun buildNotificationRows(groups: List<NotificationGroupUi>): List<NotificationListRow> {
    return buildList(groups.size * 2) {
        groups.forEach { group ->
            add(NotificationListRow.GroupHeader(group))
            add(NotificationListRow.GroupBody(group))
        }
    }
}

@Composable
private fun NotificationRowContainer(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun NotificationGroupBody(
    width: androidx.compose.ui.unit.Dp,
    group: NotificationGroupUi,
    revealedTarget: NotificationRevealTarget?,
    onRevealTargetChange: (NotificationRevealTarget?) -> Unit,
    onToggleGroup: () -> Unit,
    onDismissGroup: () -> Unit,
    onDismissNotification: (String) -> Unit,
    onOpenNotification: (String, Offset) -> Boolean
) {
    val hasMultipleEntries = group.entries.size > 1
    AnimatedContent(
        targetState = group.expanded,
        transitionSpec = {
            if (targetState) {
                (fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = tween(220)) { it / 4 }) togetherWith
                    (fadeOut(animationSpec = tween(140)) + slideOutVertically(animationSpec = tween(140)) { -it / 10 })
            } else {
                (fadeIn(animationSpec = tween(180)) + slideInVertically(animationSpec = tween(180)) { -it / 8 }) togetherWith
                    (fadeOut(animationSpec = tween(210)) + slideOutVertically(animationSpec = tween(210)) { it / 5 })
            }
        },
        label = "notification_group_body"
    ) { expanded ->
        if (expanded && hasMultipleEntries) {
            Column(
                modifier = Modifier.width(width),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                group.entries.forEach { entry ->
                    SwipeRevealDeleteContainer(
                        target = NotificationRevealTarget.Entry(entry.key),
                        revealedTarget = revealedTarget,
                        onRevealTargetChange = onRevealTargetChange,
                        enabled = entry.isClearable,
                        onDelete = { onDismissNotification(entry.key) },
                        modifier = Modifier.width(width)
                    ) {
                        NotificationCard(
                            width = width,
                            entry = entry,
                            subtitle = entry.text.ifBlank { entry.title.ifBlank { entry.appLabel } },
                            trailing = formatNotificationTime(entry.time),
                            onClick = { onOpenNotification(entry.key, Offset(0.5f, 0.5f)) }
                        )
                    }
                }
            }
        } else if (hasMultipleEntries) {
            SwipeRevealDeleteContainer(
                target = NotificationRevealTarget.Group(group.packageName),
                revealedTarget = revealedTarget,
                onRevealTargetChange = onRevealTargetChange,
                enabled = group.entries.any(NotificationEntryUi::isClearable),
                onDelete = onDismissGroup,
                modifier = Modifier.width(width),
                actionHeight = 72.dp
            ) {
                CollapsedStackCard(
                    width = width,
                    leadEntry = group.entries.first(),
                    hiddenCount = group.entries.size - 1,
                    onClick = onToggleGroup
                )
            }
        } else {
            val entry = group.entries.first()
            SwipeRevealDeleteContainer(
                target = NotificationRevealTarget.Entry(entry.key),
                revealedTarget = revealedTarget,
                onRevealTargetChange = onRevealTargetChange,
                enabled = entry.isClearable,
                onDelete = { onDismissNotification(entry.key) },
                modifier = Modifier.width(width)
            ) {
                NotificationCard(
                    width = width,
                    entry = entry,
                    subtitle = entry.text.ifBlank { entry.title.ifBlank { entry.appLabel } },
                    trailing = formatNotificationTime(entry.time),
                    onClick = { onOpenNotification(entry.key, Offset(0.5f, 0.5f)) }
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val fake = NotificationEntryUi("perm", "", null, "开启通知访问", "开启通知访问", "完成授权后这里会显示真实通知", 0L, null, false, false, false, false)
    NotificationCard(width, fake, "完成授权后这里会显示真实通知", "", onClick = { onClick() })
}

@Composable
private fun EmptyCard(width: androidx.compose.ui.unit.Dp) {
    val fake = NotificationEntryUi("empty", "", null, "暂无通知", "暂无通知", "有新消息时会显示在这里", 0L, null, false, false, false, false)
    NotificationCard(width, fake, "有新消息时会显示在这里", "", onClick = {})
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    showMeta: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, spring(stiffness = 840f, dampingRatio = 0.74f), label = "group_header")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (showMeta) {
                    Modifier.instantPressGesture(pressed, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (showMeta) {
            Spacer(Modifier.weight(1f))
            Text("${count}条通知", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f })
            }
        }
    }
}

@Composable
private fun CollapsedStackCard(width: androidx.compose.ui.unit.Dp, leadEntry: NotificationEntryUi, hiddenCount: Int, onClick: () -> Unit) {
    val stackStrength by animateFloatAsState(
        targetValue = if (hiddenCount > 0) 1f else 0f,
        animationSpec = spring(stiffness = 620f, dampingRatio = 0.82f),
        label = "collapsed_stack_strength"
    )
    Box(Modifier.width(width)) {
        repeat(minOf(hiddenCount, 2)) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .graphicsLayer {
                        translationY = (index + 1) * 40f * stackStrength
                        scaleX = 1f - (index + 1) * 0.012f * stackStrength
                        scaleY = 1f - (index + 1) * 0.012f * stackStrength
                        alpha = 0.42f + (0.18f / (index + 1))
                    }
                    .padding(start = 4.dp, end = 4.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (index == 0) Color(0xFF404040) else Color(0xFF2E2E2E))
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 2.dp)) {
            NotificationCard(width, leadEntry, leadEntry.text.ifBlank { leadEntry.title.ifBlank { leadEntry.appLabel } }, formatNotificationTime(leadEntry.time), onClick = { onClick() })
            Text("+${hiddenCount}条新消息", color = WatchColors.TextTertiary, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun NotificationCard(width: androidx.compose.ui.unit.Dp, entry: NotificationEntryUi, subtitle: String, trailing: String, onClick: () -> Unit) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    val scale by animateFloatAsState(if (isPressed) 0.968f else 1f, spring(stiffness = 860f, dampingRatio = 0.72f), label = "notif_card")
    Box(
        Modifier.width(width).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(28.dp)).background(Color(0xFF353535))
            .instantPressGesture(
                pressed,
                enabled = entry.key == "perm" || entry.key == "empty" || entry.contentIntentAvailable,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NotificationIcon(entry.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title.ifBlank { entry.appLabel }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = WatchColors.TextSecondary, fontSize = 13.sp, maxLines = 2)
            }
            if (trailing.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(trailing, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun NotificationIcon(icon: ImageBitmap?) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFD9D9D9)), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, modifier = Modifier.fillMaxSize().clip(CircleShape), filterQuality = FilterQuality.Medium, contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Notifications, null, tint = Color(0xFF2B2B2B), modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun rememberBatteryLevel(): Int {
    val context = LocalContext.current
    var level by remember(context) { mutableIntStateOf(0) }
    DisposableEffect(context) {
        fun readLevel(): Int = (context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0).coerceIn(0, 100)
        level = readLevel()
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: android.content.Context?, intent: Intent?) { level = readLevel() } }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return level
}

@Composable
private fun BatteryPill(level: Int, modifier: Modifier = Modifier) {
    val tint = when { level <= 10 -> WatchColors.ActiveRed; level <= 20 -> WatchColors.ActiveOrange; else -> WatchColors.ActiveGreen }
    Box(modifier.width(56.dp).height(24.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF141414)).padding(2.dp)) {
        Box(Modifier.fillMaxWidth((level / 100f).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.24f)))
        Text("${level}%", color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
    }
}

private fun formatNotificationTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
