package com.flue.launcher.ui.notification

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.common.FrostedHeader
import com.flue.launcher.ui.common.LocalOverlayBackdrop
import com.flue.launcher.ui.common.OverlayBackdropReporter
import com.flue.launcher.ui.common.OverlayBackdropSource
import com.flue.launcher.ui.common.instantPressGesture
import com.flue.launcher.ui.common.overlayClipShape
import com.flue.launcher.ui.common.overlayGlassSurface
import com.flue.launcher.ui.common.overlayReleaseTarget
import com.flue.launcher.ui.common.overlayVelocityToProgress
import com.flue.launcher.ui.common.rememberPressedState
import com.flue.launcher.ui.common.FlueElasticOffsetController
import com.flue.launcher.R
import com.flue.launcher.motion.LocalFlueMotionProfile
import com.flue.launcher.motion.asFloatAnimationSpec
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.WatchColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

private const val NOTIFICATION_CARD_WIDTH_RATIO = 0.88f
private const val NOTIFICATION_TOP_SAFE_PADDING_DP = 22
private const val NOTIFICATION_TOP_OVERSCROLL_LIMIT = 72f
private const val NOTIFICATION_BOTTOM_OVERSCROLL_LIMIT = -96f
private const val NOTIFICATION_OVERSCROLL_RESISTANCE = 0.18f
private const val NOTIFICATION_DISMISS_DRAG_RANGE_RATIO = 0.78f
private const val NOTIFICATION_DISMISS_DRAG_RANGE_MIN = 300f
private const val NOTIFICATION_DISMISS_RELEASE_PROGRESS = 0.6f
private const val NOTIFICATION_DISMISS_FLING_VELOCITY = 1350f
private const val COLLAPSED_STACK_TRANSLATION_DP = 14
private const val COLLAPSED_STACK_HORIZONTAL_INSET_DP = 5
private val NOTIFICATION_TIME_FORMAT = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationLayer(
    isActive: Boolean,
    transitionProgress: Float,
    notificationGroups: List<NotificationGroupUi>,
    notificationAccessGranted: Boolean,
    revealedNotificationTarget: NotificationRevealTarget?,
    onRevealTargetChange: (NotificationRevealTarget?) -> Unit,
    onDismissToStack: () -> Unit,
    onTransitionProgressChange: (Float) -> Unit,
    onTransitionRelease: (Boolean) -> Unit,
    onToggleGroup: (String) -> Unit,
    onDismissGroup: (String) -> Unit,
    onDismissNotification: (String) -> Unit,
    onDismissAllNotifications: () -> Unit,
    onRunNotificationAction: (String) -> Boolean,
    onOpenNotification: (String, Offset) -> Boolean,
    stackCardColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val motionProfile = LocalFlueMotionProfile.current
    val listState = rememberLazyListState()
    val dismissDragRangePx = remember(configuration.screenHeightDp, density) {
        maxOf(
            with(density) { configuration.screenHeightDp.dp.toPx() } * NOTIFICATION_DISMISS_DRAG_RANGE_RATIO,
            NOTIFICATION_DISMISS_DRAG_RANGE_MIN
        )
    }
    val dismissThresholdPx = dismissDragRangePx * NOTIFICATION_DISMISS_RELEASE_PROGRESS
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val overscroll = remember(scope) { FlueElasticOffsetController(scope) }
    val latestMotionProfile = rememberUpdatedState(motionProfile)
    val launcherStyle = LauncherTheme.style
    val hasClearableNotifications = notificationGroups.any { group ->
        group.entries.any(NotificationEntryUi::isClearable)
    }
    val groupEntryTracker = remember { NotificationGroupEntryTracker() }
    val currentGroupKeys = notificationGroups.asSequence()
        .map(NotificationGroupUi::packageName)
        .toSet()
    val enteringGroupKeys = groupEntryTracker.entering(currentGroupKeys)
    SideEffect { groupEntryTracker.commit(currentGroupKeys) }
    var dismissTransitionInFlight by remember { mutableStateOf(false) }
    var dismissDragDistance by remember { mutableFloatStateOf(0f) }
    var dismissDragVelocityY by remember { mutableFloatStateOf(0f) }
    var lastDismissDragEventUptime by remember { mutableStateOf(0L) }
    // 表盘下压：通知中心可见期间把进度写进共享 controller，手势期间 1:1 跟手。
    val backdrop = LocalOverlayBackdrop.current
    OverlayBackdropReporter(OverlayBackdropSource.Notification, visible = isActive)

    fun applyOverscroll(delta: Float): Float {
        val profile = latestMotionProfile.value
        if (!profile.bounceEnabled || profile.edgeStrength <= 0) {
            if (overscroll.value != 0f) overscroll.reset()
            return 0f
        }
        val next = when {
            overscroll.value > 0f && delta < 0f -> (overscroll.value + delta).coerceAtLeast(0f)
            overscroll.value < 0f && delta > 0f -> (overscroll.value + delta).coerceAtMost(0f)
            delta > 0f -> (overscroll.value + delta * NOTIFICATION_OVERSCROLL_RESISTANCE)
                .coerceIn(0f, NOTIFICATION_TOP_OVERSCROLL_LIMIT)
            delta < 0f -> (overscroll.value + delta * NOTIFICATION_OVERSCROLL_RESISTANCE)
                .coerceIn(NOTIFICATION_BOTTOM_OVERSCROLL_LIMIT, 0f)
            else -> overscroll.value
        }
        overscroll.setImmediate(next)
        return next
    }

    fun releaseOverscroll() {
        val profile = latestMotionProfile.value
        if (overscroll.value == 0f) return
        if (profile.bounceEnabled && profile.edgeStrength > 0) {
            overscroll.settle(profile.edgeBounce.asFloatAnimationSpec())
        } else {
            overscroll.reset()
        }
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            dismissTransitionInFlight = false
            dismissDragDistance = 0f
            dismissDragVelocityY = 0f
            lastDismissDragEventUptime = 0L
            overscroll.reset()
        }
    }
    fun listIsAtTop(): Boolean =
        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    val updateDismissDrag: (Float) -> Unit = { delta ->
        dismissDragDistance = (dismissDragDistance + delta).coerceIn(0f, dismissDragRangePx)
        val openProgress = (1f - dismissDragDistance / dismissDragRangePx).coerceIn(0f, 1f)
        // 1:1 跟手：位移直接写进度，不加阻尼。
        onTransitionProgressChange(openProgress)
        backdrop.reportGesture(OverlayBackdropSource.Notification, openProgress)
    }
    val releaseDismissDrag: () -> Unit = {
        // 抬手定向：位置 + 初速度外推，统一走 overlayReleaseTarget，不再用裸阈值。
        // progress 语义 = 「通知中心还剩多少」，1 = 全开，0 = 已收回。
        val openProgress = (1f - dismissDragDistance / dismissDragRangePx).coerceIn(0f, 1f)
        val progressVelocity = overlayVelocityToProgress(
            velocityPxPerSecond = -dismissDragVelocityY,
            rangePx = dismissDragRangePx,
        )
        val shouldDismiss = dismissDragDistance > 0f &&
            overlayReleaseTarget(openProgress, progressVelocity) < 0.5f
        backdrop.reportGesture(
            OverlayBackdropSource.Notification,
            if (shouldDismiss) 0f else 1f,
        )
        backdrop.endGesture(OverlayBackdropSource.Notification)
        dismissDragDistance = 0f
        dismissDragVelocityY = 0f
        lastDismissDragEventUptime = 0L
        releaseOverscroll()
        onTransitionRelease(shouldDismiss)
    }
    val closeIfPulled: suspend () -> Unit = {
        if (!dismissTransitionInFlight) {
            if (dismissDragDistance > dismissThresholdPx) {
                dismissTransitionInFlight = true
                onRevealTargetChange(null)
                releaseDismissDrag()
            } else if (overscroll.value != 0f) {
                releaseOverscroll()
            }
        }
    }
    val staticContentDismissModifier = Modifier.pointerInput(notificationAccessGranted, notificationGroups.size, dismissTransitionInFlight) {
        if (dismissTransitionInFlight) return@pointerInput
        detectVerticalDragGestures(
            onDragStart = {
                dismissDragVelocityY = 0f
                lastDismissDragEventUptime = 0L
                backdrop.beginGesture(OverlayBackdropSource.Notification, 1f)
            },
            onVerticalDrag = { change, dragAmount ->
                if (lastDismissDragEventUptime != 0L) {
                    val deltaMs = (change.uptimeMillis - lastDismissDragEventUptime).coerceAtLeast(1L)
                    val instantVelocityY = dragAmount / deltaMs * 1000f
                    dismissDragVelocityY = if (dismissDragVelocityY == 0f) {
                        instantVelocityY
                    } else {
                        dismissDragVelocityY * 0.35f + instantVelocityY * 0.65f
                    }
                }
                lastDismissDragEventUptime = change.uptimeMillis
                if ((dragAmount > 0f && listIsAtTop()) || dismissDragDistance > 0f) {
                    change.consume()
                    if (dismissDragDistance > 0f) {
                        val currentDrag = dismissDragDistance
                        updateDismissDrag(dragAmount)
                        if (dragAmount < 0f && dragAmount.absoluteValue > currentDrag) {
                            applyOverscroll(dragAmount + currentDrag)
                        }
                    } else {
                        applyOverscroll(dragAmount)
                        updateDismissDrag(dragAmount)
                    }
                } else if (dragAmount < 0f || overscroll.value != 0f) {
                    change.consume()
                    applyOverscroll(dragAmount)
                }
            },
            onDragEnd = {
                if (dismissDragDistance > 0f) {
                    releaseDismissDrag()
                } else {
                    scope.launch { closeIfPulled() }
                }
            },
            onDragCancel = {
                if (dismissDragDistance > 0f || transitionProgress < 1f) {
                    dismissDragDistance = 0f
                    dismissDragVelocityY = 0f
                    lastDismissDragEventUptime = 0L
                    onTransitionRelease(false)
                }
                backdrop.endGesture(OverlayBackdropSource.Notification)
                releaseOverscroll()
            }
        )
    }
    val nestedScroll = remember(listState, dismissTransitionInFlight) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (dismissTransitionInFlight) return androidx.compose.ui.geometry.Offset.Zero
                if (source != NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                val profile = latestMotionProfile.value
                val bounceEnabled = profile.bounceEnabled && profile.edgeStrength > 0
                if (!bounceEnabled && overscroll.value != 0f) overscroll.reset()
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                val layoutInfo = listState.layoutInfo
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                val atBottom = layoutInfo.totalItemsCount > 0 &&
                    lastVisibleItem != null &&
                    lastVisibleItem.index == layoutInfo.totalItemsCount - 1 &&
                    lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset
                if (available.y > 0f && atTop) {
                    if (dismissDragDistance > 0f) {
                        updateDismissDrag(available.y)
                    } else {
                        applyOverscroll(available.y)
                        updateDismissDrag(available.y)
                    }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (available.y < 0f && atBottom) {
                    if (!bounceEnabled) return androidx.compose.ui.geometry.Offset.Zero
                    applyOverscroll(available.y)
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (dismissDragDistance > 0f && available.y < 0f) {
                    val currentDrag = dismissDragDistance
                    val consumedY = available.y.coerceAtLeast(-currentDrag)
                    updateDismissDrag(consumedY)
                    return androidx.compose.ui.geometry.Offset(0f, consumedY)
                }
                if (overscroll.value != 0f && ((overscroll.value > 0f && available.y < 0f) || (overscroll.value < 0f && available.y > 0f))) {
                    val current = overscroll.value
                    val next = when {
                        current > 0f -> (current + available.y).coerceAtLeast(0f)
                        else -> (current + available.y).coerceAtMost(0f)
                    }
                    overscroll.setImmediate(next)
                    return androidx.compose.ui.geometry.Offset(0f, next - current)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                if (dismissTransitionInFlight) return androidx.compose.ui.geometry.Offset.Zero
                if (source != NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop) {
                    if (dismissDragDistance > 0f) {
                        updateDismissDrag(available.y)
                    } else {
                        applyOverscroll(available.y)
                        updateDismissDrag(available.y)
                    }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dismissTransitionInFlight) return available
                if (dismissDragDistance > 0f) {
                    dismissDragVelocityY = available.y
                    releaseDismissDrag()
                    return Velocity.Zero
                }
                if (overscroll.value != 0f) {
                    releaseOverscroll()
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val profile = latestMotionProfile.value
                if (!profile.bounceEnabled || profile.edgeStrength <= 0) {
                    if (overscroll.value != 0f) overscroll.reset()
                    return Velocity.Zero
                }
                val layoutInfo = listState.layoutInfo
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                val atBottom = layoutInfo.totalItemsCount > 0 &&
                    lastVisibleItem != null &&
                    lastVisibleItem.index >= layoutInfo.totalItemsCount - 1 &&
                    lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset
                val atBoundary = (available.y > 0f && listIsAtTop()) ||
                    (available.y < 0f && atBottom)
                if (!atBoundary && overscroll.value == 0f) return Velocity.Zero
                if (available.y != 0f) {
                    val kick = (available.y * 0.008f).coerceIn(-40f, 40f)
                    overscroll.setImmediate(
                        (overscroll.value + kick).coerceIn(
                            NOTIFICATION_BOTTOM_OVERSCROLL_LIMIT,
                            NOTIFICATION_TOP_OVERSCROLL_LIMIT,
                        ),
                    )
                }
                releaseOverscroll()
                return available
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 覆层不再画不透明底：毛玻璃背衬由表盘层的 OverlayBackdropHost 提供。
            .clip(overlayClipShape())
            .overlayGlassSurface(backdrop)
            .nestedScroll(nestedScroll)
    ) {
        val contentWidth = maxWidth * NOTIFICATION_CARD_WIDTH_RATIO
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = overscroll.value }
                .padding(top = NOTIFICATION_TOP_SAFE_PADDING_DP.dp),
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)
                ) {
                    notificationGroups.forEach { group ->
                        val isEnteringGroup = group.packageName in enteringGroupKeys
                        item(
                            key = "header_${group.packageName}",
                            contentType = "group_header"
                        ) {
                            NotificationRowContainer(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(190),
                                    placementSpec = spring(stiffness = 500f, dampingRatio = 0.86f),
                                    fadeOutSpec = tween(130)
                                )
                            ) {
                                NewNotificationGroupEntrance(
                                    groupKey = group.packageName,
                                    entering = isEnteringGroup
                                ) {
                                    Box(Modifier.width(contentWidth)) {
                                        GroupHeader(
                                            title = group.headerTitle,
                                            count = group.entries.size,
                                            showMeta = group.entries.size > 1,
                                            expanded = group.expanded,
                                            onClick = { onToggleGroup(group.packageName) }
                                        )
                                    }
                                }
                            }
                        }
                        item(
                            key = "body_${group.packageName}",
                            contentType = "group_body"
                        ) {
                            NotificationRowContainer(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(190),
                                    placementSpec = spring(stiffness = 500f, dampingRatio = 0.86f),
                                    fadeOutSpec = tween(130)
                                )
                            ) {
                                NewNotificationGroupEntrance(
                                    groupKey = group.packageName,
                                    entering = isEnteringGroup
                                ) {
                                    AnimatedContent(
                                        targetState = group.entries.size > 1,
                                        transitionSpec = {
                                            fadeIn(tween(durationMillis = 180)) togetherWith
                                                fadeOut(tween(durationMillis = 140))
                                        },
                                        label = "notification_group_cardinality"
                                    ) { hasMultipleEntries ->
                                        if (hasMultipleEntries) {
                                            AnimatedContent(
                                                targetState = group.expanded,
                                                transitionSpec = {
                                                    fadeIn(tween(durationMillis = 160)) togetherWith
                                                        fadeOut(tween(durationMillis = 120))
                                                },
                                                label = "notification_group_body"
                                            ) { expanded ->
                                                if (expanded) {
                                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        group.entries.forEach { entry ->
                                                            key(entry.key) {
                                                                SwipeRevealDeleteContainer(
                                                                    target = NotificationRevealTarget.Entry(entry.key),
                                                                    revealedTarget = revealedNotificationTarget,
                                                                    onRevealTargetChange = onRevealTargetChange,
                                                                    enabled = entry.isClearable,
                                                                    onDelete = { onDismissNotification(entry.key) },
                                                                    modifier = Modifier.width(contentWidth)
                                                                ) {
                                                                    NotificationCardReplacement(
                                                                        width = contentWidth,
                                                                        entry = entry,
                                                                        onOpenNotification = onOpenNotification,
                                                                        onRunNotificationAction = onRunNotificationAction
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    SwipeRevealDeleteContainer(
                                                        target = NotificationRevealTarget.Group(group.packageName),
                                                        revealedTarget = revealedNotificationTarget,
                                                        onRevealTargetChange = onRevealTargetChange,
                                                        enabled = group.entries.any(NotificationEntryUi::isClearable),
                                                        onDelete = { onDismissGroup(group.packageName) },
                                                        modifier = Modifier.width(contentWidth),
                                                        actionHeight = 72.dp
                                                    ) {
                                                        AnimatedContent(
                                                            targetState = group.entries.first(),
                                                            transitionSpec = {
                                                                fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                                                            },
                                                            label = "notification_group_replacement"
                                                        ) { leadEntry ->
                                                            CollapsedStackCard(
                                                                width = contentWidth,
                                                                leadEntry = leadEntry,
                                                                hiddenCount = group.entries.size - 1,
                                                                stackCardColor = stackCardColor,
                                                                onClick = { onToggleGroup(group.packageName) }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            val entry = group.entries.first()
                                            SwipeRevealDeleteContainer(
                                                target = NotificationRevealTarget.Entry(entry.key),
                                                revealedTarget = revealedNotificationTarget,
                                                onRevealTargetChange = onRevealTargetChange,
                                                enabled = entry.isClearable,
                                                onDelete = { onDismissNotification(entry.key) },
                                                modifier = Modifier.width(contentWidth)
                                            ) {
                                                NotificationCardReplacement(
                                                    width = contentWidth,
                                                    entry = entry,
                                                    onOpenNotification = onOpenNotification,
                                                    onRunNotificationAction = onRunNotificationAction
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (hasClearableNotifications) {
                        item(
                            key = "notifications_clear_all",
                            contentType = "clear_all"
                        ) {
                            NotificationRowContainer(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(180),
                                    placementSpec = spring(stiffness = 520f, dampingRatio = 0.86f),
                                    fadeOutSpec = tween(140)
                                )
                            ) {
                                ActionDock {
                                    DockActionButton(
                                        text = "全部清除",
                                        onClick = onDismissAllNotifications
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // 顶部模糊头（§8）：通知列表顶部行的模糊背衬。作为覆层叠在列表上方，
        // 不参与布局与手势（无 pointerInput，命中测试直接穿透到列表）；
        // 滚动量取自 listState —— 列表离开顶部后，滚过的卡片在头部区域被
        // 模糊 + 渐隐「压下去」。
        FrostedHeader(
            scrollProvider = {
                if (listState.firstVisibleItemIndex > 0) {
                    Float.MAX_VALUE
                } else {
                    listState.firstVisibleItemScrollOffset.toFloat()
                }
            },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {}
    }
}

private class NotificationGroupEntryTracker {
    private var initialized = false
    private var previousKeys: Set<String> = emptySet()

    fun entering(currentKeys: Set<String>): Set<String> {
        return if (initialized) currentKeys - previousKeys else emptySet()
    }

    fun commit(currentKeys: Set<String>) {
        initialized = true
        previousKeys = currentKeys
    }
}

@Composable
private fun NewNotificationGroupEntrance(
    groupKey: String,
    entering: Boolean,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val offsetPx = with(density) { 24.dp.toPx() }
    val progress = remember(groupKey) { Animatable(if (entering) 0f else 1f) }
    LaunchedEffect(groupKey) {
        if (entering) {
            progress.animateTo(1f, tween(durationMillis = 220))
        }
    }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * offsetPx
        }
    ) {
        content()
    }
}

@Composable
private fun NotificationCardReplacement(
    width: androidx.compose.ui.unit.Dp,
    entry: NotificationEntryUi,
    onOpenNotification: (String, Offset) -> Boolean,
    onRunNotificationAction: (String) -> Boolean
) {
    AnimatedContent(
        targetState = entry,
        transitionSpec = {
            fadeIn(tween(durationMillis = 180)) togetherWith
                fadeOut(tween(durationMillis = 140))
        },
        label = "notification_content_replacement"
    ) { visibleEntry ->
        NotificationCard(
            width = width,
            entry = visibleEntry,
            subtitle = visibleEntry.text.ifBlank {
                visibleEntry.title.ifBlank { visibleEntry.appLabel }
            },
            trailing = formatNotificationTime(visibleEntry.time),
            onClick = { origin -> onOpenNotification(visibleEntry.key, origin) },
            onRunNotificationAction = onRunNotificationAction
        )
    }
}

@Composable
private fun ActionDock(content: @Composable () -> Unit) {
    val launcherStyle = LauncherTheme.style
    val dockColor = if (launcherStyle.cardColor.alpha < 0.35f) {
        if (launcherStyle.titleColor == Color.White) {
            LauncherTheme.style.pressedCardColor.copy(alpha = 0.82f)
        } else {
            LauncherTheme.style.pressedCardColor.copy(alpha = 0.88f)
        }
    } else {
        launcherStyle.cardColor.copy(alpha = launcherStyle.cardColor.alpha.coerceAtLeast(0.72f))
    }
    Box(
        modifier = Modifier
            .clip(LauncherTheme.style.chipShape)
            .background(dockColor)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun NotificationRowContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        content()
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
    val launcherStyle = LauncherTheme.style
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
        Text(title, color = launcherStyle.titleColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (showMeta) {
            Spacer(Modifier.weight(1f))
            Text("${count}条通知", color = launcherStyle.titleColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            // 箭头跟着容器高度动画一起转，不再抢跑瞬翻。
            val arrowRotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                label = "notification_group_arrow",
            )
            Box(Modifier.size(28.dp).clip(CircleShape).background(launcherStyle.topBarChipColor), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = launcherStyle.topBarTextColor, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = arrowRotation })
            }
        }
    }
}

@Composable
private fun CollapsedStackCard(
    width: androidx.compose.ui.unit.Dp,
    leadEntry: NotificationEntryUi,
    hiddenCount: Int,
    stackCardColor: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    val stackStrength by animateFloatAsState(
        targetValue = if (hiddenCount > 0) 1f else 0f,
        animationSpec = spring(stiffness = 620f, dampingRatio = 0.82f),
        label = "collapsed_stack_strength"
    )
    var frontCardHeight by remember { mutableIntStateOf(72) }
    val density = LocalDensity.current
    val stackCardHeight: androidx.compose.ui.unit.Dp = with(density) { frontCardHeight.toDp() }
    Box(Modifier.width(width)) {
        repeat(hiddenCount.coerceIn(0, 2)) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stackCardHeight)
                    .graphicsLayer {
                        translationY = with(density) { (COLLAPSED_STACK_TRANSLATION_DP * (index + 1)).dp.toPx() } * stackStrength
                        scaleX = 1f - (index + 1) * 0.012f * stackStrength
                        scaleY = 1f - (index + 1) * 0.012f * stackStrength
                        alpha = 0.42f + (0.18f / (index + 1))
                    }
                    .padding(horizontal = COLLAPSED_STACK_HORIZONTAL_INSET_DP.dp)
                    .clip(LauncherTheme.style.panelShape)
                    .background(
                        notificationStackBackColor(
                            base = stackCardColor,
                            index = index,
                            fallback = LauncherTheme.style.titleColor,
                        )
                    )
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 2.dp)) {
            NotificationCard(
                width = width,
                entry = leadEntry,
                subtitle = leadEntry.text.ifBlank { leadEntry.title.ifBlank { leadEntry.appLabel } },
                trailing = formatNotificationTime(leadEntry.time),
                onClick = { onClick() },
                onMeasuredHeight = { heightPx ->
                    if (heightPx > 0 && frontCardHeight != heightPx) {
                        frontCardHeight = heightPx
                    }
                }
            )
            Text("+${hiddenCount}条新消息", color = WatchColors.TextTertiary, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun NotificationCard(
    width: androidx.compose.ui.unit.Dp,
    entry: NotificationEntryUi,
    subtitle: String,
    trailing: String,
    onClick: (Offset) -> Unit,
    onRunNotificationAction: (String) -> Boolean = { false },
    onMeasuredHeight: ((Int) -> Unit)? = null
) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    val launcherStyle = LauncherTheme.style
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.coerceAtLeast(1f)
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.coerceAtLeast(1f)
    var launchOrigin by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val darkCard = launcherStyle.titleColor == Color.White
    val cardColor = if (launcherStyle.cardColor.alpha < 0.35f) {
        launcherStyle.pressedCardColor
    } else {
        launcherStyle.cardColor
    }
    // watchOS 的玻璃感三件套之二：顶亮底暗的渐变 + 极淡描边。纯色贴纯黑时
    // 边界只有 12% 亮度差，28dp 转角处会因抗锯齿发虚。
    val cardBrush = remember(cardColor, darkCard) {
        if (darkCard) {
            Brush.verticalGradient(
                listOf(
                    cardColor,
                    cardColor.copy(alpha = cardColor.alpha * 0.86f),
                )
            )
        } else {
            Brush.verticalGradient(listOf(cardColor, cardColor))
        }
    }
    val cardBorder = if (darkCard) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.06f)
    val scale by animateFloatAsState(if (isPressed) 0.968f else 1f, spring(stiffness = 860f, dampingRatio = 0.72f), label = "notif_card")
    Box(
        Modifier
            .width(width)
            .onGloballyPositioned { coordinates ->
                onMeasuredHeight?.invoke(coordinates.size.height)
                val position = coordinates.positionInRoot()
                launchOrigin = Offset(
                    x = ((position.x + coordinates.size.width / 2f) / screenWidthPx).coerceIn(0f, 1f),
                    y = ((position.y + coordinates.size.height / 2f) / screenHeightPx).coerceIn(0f, 1f)
                )
            }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(launcherStyle.cardShape)
            .background(cardBrush)
            .border(0.8.dp, cardBorder, launcherStyle.cardShape)
            .instantPressGesture(
                pressed,
                enabled = entry.key == "perm" || entry.key == "empty" || entry.contentIntentAvailable,
                onClick = { onClick(launchOrigin) }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NotificationIcon(entry.icon)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title.ifBlank { entry.appLabel }, color = launcherStyle.titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = WatchColors.TextSecondary, fontSize = 13.sp, maxLines = 2)
                }
                if (trailing.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(trailing, color = launcherStyle.bodyColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (entry.actions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.actions.take(3).forEach { action ->
                        NotificationActionButton(
                            title = action.title,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onRunNotificationAction(action.key)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationActionButton(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val pressed = rememberPressedState()
    val isPressed by pressed
    val scale by animateFloatAsState(if (isPressed) 0.955f else 1f, spring(stiffness = 820f, dampingRatio = 0.76f), label = "notif_action")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(LauncherTheme.style.chipShape)
            .background(launcherStyle.topBarChipColor)
            .instantPressGesture(pressed, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = launcherStyle.topBarTextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DockActionButton(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val pressed = rememberPressedState()
    val isPressed by pressed
    val scale by animateFloatAsState(if (isPressed) 0.955f else 1f, spring(stiffness = 820f, dampingRatio = 0.76f), label = "notif_clear_all")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(LauncherTheme.style.chipShape)
            .background(launcherStyle.topBarChipColor)
            .instantPressGesture(pressed, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = launcherStyle.topBarTextColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun NotificationIcon(icon: ImageBitmap?) {
    val launcherStyle = LauncherTheme.style
    Box(Modifier.size(46.dp).clip(CircleShape).background(launcherStyle.titleColor.copy(alpha = 0.86f)), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, modifier = Modifier.fillMaxSize().clip(CircleShape), filterQuality = FilterQuality.Low, contentScale = ContentScale.Crop)
        else Icon(
            painter = painterResource(R.drawable.ic_flue_notification_round),
            contentDescription = null,
            tint = launcherStyle.screenBackground,
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun notificationStackBackColor(base: Color, index: Int, fallback: Color): Color {
    return when {
        base != Color.Unspecified && base.alpha > 0f -> base.copy(alpha = (0.40f - index * 0.08f).coerceIn(0.18f, 0.42f))
        else -> fallback.copy(alpha = (0.40f - index * 0.08f).coerceIn(0.18f, 0.42f))
    }
}

private data class BatteryStatus(
    val level: Int,
    val charging: Boolean
)

@Composable
private fun rememberBatteryStatus(): BatteryStatus {
    val context = LocalContext.current
    var level by remember(context) { mutableIntStateOf(0) }
    var charging by remember(context) { mutableStateOf(false) }
    DisposableEffect(context) {
        fun readLevel(): Int = (context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0).coerceIn(0, 100)
        fun readCharging(intent: Intent?): Boolean {
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        level = readLevel()
        val stickyIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        charging = readCharging(stickyIntent)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                level = readLevel()
                charging = readCharging(intent)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return BatteryStatus(level = level, charging = charging)
}


private fun formatNotificationTime(timestamp: Long): String {
    val formatter = NOTIFICATION_TIME_FORMAT.get() ?: SimpleDateFormat("HH:mm", Locale.getDefault()).also {
        NOTIFICATION_TIME_FORMAT.set(it)
    }
    return formatter.format(Date(timestamp))
}
