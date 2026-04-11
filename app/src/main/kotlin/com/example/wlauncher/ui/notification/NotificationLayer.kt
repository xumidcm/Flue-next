package com.flue.launcher.ui.notification

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalFoundationApi::class)
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val overscroll = remember { Animatable(0f) }

    val nestedScrollConnection = remember(listState, notificationGroups) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source != NestedScrollSource.Drag) return androidx.compose.ui.geometry.Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y < 0f && atTop) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y * 0.35f).coerceAtLeast(-180f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (overscroll.value < 0f && available.y > 0f) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y).coerceAtMost(0f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll.value < -80f) {
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.64f))
    ) {
        if (!notificationAccessGranted) {
            NotificationPermissionEmptyState(
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            )
        } else if (notificationGroups.isEmpty()) {
            NotificationEmptyState()
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .graphicsLayer { translationY = overscroll.value }
                    .padding(top = 28.dp, start = 14.dp, end = 14.dp, bottom = 22.dp)
            ) {
                val screenHeightPx = constraints.maxHeight.toFloat()
                val screenCenterY = screenHeightPx / 2f
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), state = listState) {
                    item("notifications_title") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "通知中心",
                                fontSize = 14.sp,
                                color = WatchColors.TextSecondary,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }
                    itemsIndexed(notificationGroups, key = { _, group -> group.packageName }) { index, group ->
                        val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == index + 1 }
                        val scale = notificationItemScale(itemInfo, screenCenterY, screenHeightPx)
                        NotificationGroupCard(
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
    }
}

@Composable
private fun NotificationPermissionEmptyState(onOpenSettings: () -> Unit) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.958f else 1f,
        animationSpec = spring(stiffness = 860f, dampingRatio = 0.72f),
        label = "notification_permission_scale"
    )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(24.dp))
                .background(WatchColors.SurfaceGlass)
                .instantPressGesture(pressedState, onClick = onOpenSettings)
                .padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text("开启通知使用权", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("副一屏下滑后，可在这里查看和操作真实通知", color = WatchColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NotificationEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(26.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text("暂无通知", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("有新消息时会按应用堆叠显示在这里", color = WatchColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NotificationGroupCard(
    group: NotificationGroupUi,
    scale: Float,
    onToggleExpand: () -> Unit,
    onDismissGroup: () -> Unit,
    onDismissNotification: (String) -> Unit,
    onOpenNotification: (String) -> Boolean
) {
    val content: @Composable () -> Unit = {
        val pressedState = rememberPressedState()
        val pressed by pressedState
        val pressScale by animateFloatAsState(
            targetValue = if (pressed) 0.97f else 1f,
            animationSpec = spring(stiffness = 820f, dampingRatio = 0.74f),
            label = "notification_group_press_scale"
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale * pressScale
                    scaleY = scale * pressScale
                    alpha = scale.coerceIn(0.35f, 1f)
                }
                .clip(RoundedCornerShape(22.dp))
                .background(WatchColors.SurfaceGlass)
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .instantPressGesture(pressedState, onClick = onToggleExpand)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (group.icon != null) {
                        Image(
                            bitmap = group.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(group.appLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(formatNotificationTime(group.latestTime), color = WatchColors.TextSecondary, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(group.latestSummary, color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp, maxLines = 2)
                    }
                    if (group.count > 1) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.10f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(group.count.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            AnimatedContent(targetState = group.expanded, label = "notification_group_expand") { expanded ->
                if (expanded) {
                    Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.children.forEach { child ->
                            SwipeToDeleteCard(
                                enabled = child.isClearable,
                                onDelete = { onDismissNotification(child.key) }
                            ) {
                                NotificationEntryCard(
                                    entry = child,
                                    onClick = { if (child.contentIntentAvailable) onOpenNotification(child.key) }
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(0.dp))
                }
            }
        }
    }

    if (group.children.any(NotificationEntryUi::isClearable) && !group.expanded) {
        SwipeToDeleteCard(enabled = true, onDelete = onDismissGroup, content = content)
    } else {
        content()
    }
}

@Composable
private fun NotificationEntryCard(
    entry: NotificationEntryUi,
    onClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 820f, dampingRatio = 0.74f),
        label = "notification_entry_press_scale"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.14f))
            .instantPressGesture(pressedState, enabled = entry.contentIntentAvailable, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.icon != null) {
            Image(
                bitmap = entry.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { entry.appLabel },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.text.ifBlank { "打开通知" },
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 12.sp,
                maxLines = 3
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = formatNotificationTime(entry.time),
            color = WatchColors.TextSecondary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SwipeToDeleteCard(
    enabled: Boolean,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFFFF3B30))
                .clickable(enabled = enabled, onClick = onDelete),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(end = 20.dp)
            )
        }

        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            if (abs(dragAmount.x) < abs(dragAmount.y)) return@detectDragGestures
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount.x).coerceIn(-220f, 0f))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value < -110f) {
                                    offsetX.animateTo(-220f, tween(180))
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

private fun notificationItemScale(
    itemInfo: androidx.compose.foundation.lazy.LazyListItemInfo?,
    screenCenterY: Float,
    screenHeight: Float
): Float {
    if (itemInfo == null) return 0.92f
    val itemCenterY = itemInfo.offset + itemInfo.size / 2f
    val dist = abs(itemCenterY - screenCenterY)
    val maxDist = screenHeight / 2f
    val t = (dist / maxDist).coerceIn(0f, 1f)
    return 1f - 0.14f * t
}

private fun formatNotificationTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
