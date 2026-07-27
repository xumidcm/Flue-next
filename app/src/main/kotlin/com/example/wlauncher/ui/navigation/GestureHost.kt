package com.flue.launcher.ui.navigation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/** 表盘上可跟手拉出的覆层。 */
enum class OverlayGestureTarget { Stack, Widgets, ControlCenter }

/**
 * 全局导航手势宿主。
 *
 * 覆层打开侧是全程跟手的：手指一动就通过 [onOverlayDrag] 逐帧上报归一化
 * 进度（0..1），抬手经 [onOverlayRelease] 上报终点进度与投影速度，由宿主
 * 决定开/关并带初速度收尾。以前的「80px 裸阈值 → 切状态 → 播固定 tween」
 * 只保留给进应用列表这一条（应用列表有自己的转场机）。
 */
@Composable
fun GestureHost(
    screenState: ScreenState,
    onStateChange: (ScreenState) -> Unit,
    modifier: Modifier = Modifier,
    sideScreenEnabled: Boolean = false,
    showNotification: Boolean = true,
    showWidgetPage: Boolean = true,
    showControlCenter: Boolean = false,
    widgetsBackGestureLocked: Boolean = false,
    onOverlayDrag: ((OverlayGestureTarget, Float) -> Unit)? = null,
    onOverlayRelease: ((OverlayGestureTarget, Float, Float) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var totalDx by remember { mutableFloatStateOf(0f) }
    var totalDy by remember { mutableFloatStateOf(0f) }
    var consumed by remember { mutableStateOf(false) }
    var overlayTarget by remember { mutableStateOf<OverlayGestureTarget?>(null) }
    val velocityTracker = remember { VelocityTracker() }

    // 宿主手势只在真正需要它的状态挂载。detectDragGestures 跨过 touch-slop
    // 时会消费事件，Compose 一消费，底下 AndroidView（原生应用列表）立刻
    // 收到 ACTION_CANCEL——列表在边缘 overscroll 时没有消费滚动量、
    // requestDisallowIntercept 保护不生效，手势被偷走就是「到顶继续滑没有
    // 反馈且卡死 / 偶发滑不动」的第二个独立病灶（诊断日志里成串的 a=3）。
    val hostGestureNeeded = when (screenState) {
        ScreenState.Face, ScreenState.Widgets, ScreenState.Stack -> true
        // 控制中心以前不在这份名单里：覆层一打开手势宿主就整体卸载，
        // 于是「拉出跟手、返回不跟手」。它的关闭方向要走同一条跟手链路。
        ScreenState.ControlCenter -> true
        else -> false
    }
    // 覆层已经打开时，这一次拖拽是「往回收」：进度基准是 1，手指反向移动
    // 让它递减到 0，抬手仍由 onOverlayRelease 带初速度收尾。
    val closingOverlay: OverlayGestureTarget? = when (screenState) {
        ScreenState.ControlCenter -> OverlayGestureTarget.ControlCenter
        else -> null
    }
    Box(
        modifier = modifier
            .pointerInput(hostGestureNeeded, screenState, sideScreenEnabled, showWidgetPage, showControlCenter, widgetsBackGestureLocked, closingOverlay) {
                if (!hostGestureNeeded) return@pointerInput
                val widthPx = size.width.coerceAtLeast(1).toFloat()
                val heightPx = size.height.coerceAtLeast(1).toFloat()
                // 轴向判定门槛：只用来确定方向，不再是「动画开始」的门槛。
                val axisLockPx = 18f

                fun overlayProgress(target: OverlayGestureTarget): Float {
                    val delta = when (target) {
                        OverlayGestureTarget.Stack -> totalDx / (widthPx * 0.8f)
                        OverlayGestureTarget.Widgets -> -totalDx / (widthPx * 0.8f)
                        OverlayGestureTarget.ControlCenter -> totalDy / (heightPx * 0.8f)
                    }
                    // 关闭侧从 1 起算：手指反向（上滑）时 delta 为负，进度递减。
                    val base = if (closingOverlay == target) 1f else 0f
                    return (base + delta).coerceIn(0f, 1f)
                }

                fun overlayVelocity(target: OverlayGestureTarget): Float {
                    val velocity = velocityTracker.calculateVelocity()
                    return when (target) {
                        OverlayGestureTarget.Stack -> velocity.x / (widthPx * 0.8f)
                        OverlayGestureTarget.Widgets -> -velocity.x / (widthPx * 0.8f)
                        OverlayGestureTarget.ControlCenter -> velocity.y / (heightPx * 0.8f)
                    }
                }

                detectDragGestures(
                    onDragStart = {
                        totalDx = 0f
                        totalDy = 0f
                        consumed = false
                        overlayTarget = null
                        velocityTracker.resetTracking()
                    },
                    onDrag = { change, dragAmount ->
                        totalDx += dragAmount.x
                        totalDy += dragAmount.y
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        val activeOverlay = overlayTarget
                        if (activeOverlay != null) {
                            onOverlayDrag?.invoke(activeOverlay, overlayProgress(activeOverlay))
                            change.consume()
                            return@detectDragGestures
                        }

                        // 覆层已打开：认领关闭方向的跟手。控制中心是下拉打开，
                        // 所以只有向上的位移才算「往回收」。
                        if (closingOverlay != null && !consumed &&
                            abs(totalDy) > axisLockPx && abs(totalDy) > abs(totalDx) &&
                            totalDy < 0f && onOverlayDrag != null
                        ) {
                            overlayTarget = closingOverlay
                            consumed = true
                            onOverlayDrag.invoke(closingOverlay, overlayProgress(closingOverlay))
                            change.consume()
                            return@detectDragGestures
                        }

                        if (screenState == ScreenState.Face && !consumed &&
                            (abs(totalDx) > axisLockPx || abs(totalDy) > axisLockPx)
                        ) {
                            val isVertical = abs(totalDy) > abs(totalDx)
                            val candidate = when {
                                !isVertical && totalDx > 0f && sideScreenEnabled ->
                                    OverlayGestureTarget.Stack
                                !isVertical && totalDx < 0f && showWidgetPage ->
                                    OverlayGestureTarget.Widgets
                                isVertical && totalDy > 0f && showControlCenter ->
                                    OverlayGestureTarget.ControlCenter
                                else -> null
                            }
                            if (candidate != null && onOverlayDrag != null) {
                                overlayTarget = candidate
                                consumed = true
                                onOverlayDrag.invoke(candidate, overlayProgress(candidate))
                                change.consume()
                                return@detectDragGestures
                            }
                        }

                        if (!consumed && (abs(totalDx) > 80 || abs(totalDy) > 80)) {
                            consumed = true
                            val isVertical = abs(totalDy) > abs(totalDx)
                            val isHorizontal = !isVertical

                            when (screenState) {
                                ScreenState.Face -> {
                                    if (isVertical && totalDy < -80) {
                                        onStateChange(ScreenState.Apps)
                                        change.consume()
                                    } else if (isHorizontal && totalDx > 80 && sideScreenEnabled) {
                                        // 无跟手回调时的兜底：维持旧的阈值切换。
                                        onStateChange(ScreenState.Stack)
                                        change.consume()
                                    } else if (isHorizontal && totalDx < -80 && showWidgetPage) {
                                        onStateChange(ScreenState.Widgets)
                                        change.consume()
                                    } else if (isVertical && totalDy > 80 && showControlCenter) {
                                        onStateChange(ScreenState.ControlCenter)
                                        change.consume()
                                    }
                                }

                                ScreenState.Notifications -> Unit

                                ScreenState.Widgets -> {
                                    if (!widgetsBackGestureLocked && isHorizontal && totalDx > 80) {
                                        onStateChange(ScreenState.Face)
                                        change.consume()
                                    }
                                }

                                ScreenState.ControlCenter -> Unit

                                ScreenState.Apps -> Unit
                                ScreenState.App -> Unit
                                ScreenState.Settings -> Unit
                                ScreenState.Stack -> {
                                    if (isHorizontal && totalDx < -80) {
                                        onStateChange(ScreenState.Face)
                                        change.consume()
                                    }
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        overlayTarget?.let { target ->
                            onOverlayRelease?.invoke(
                                target,
                                overlayProgress(target),
                                overlayVelocity(target),
                            )
                        }
                        overlayTarget = null
                    },
                    onDragCancel = {
                        overlayTarget?.let { target ->
                            onOverlayRelease?.invoke(target, overlayProgress(target), 0f)
                        }
                        overlayTarget = null
                    },
                )
            }
    ) {
        content()
    }
}
