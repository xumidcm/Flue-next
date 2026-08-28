package com.flue.launcher.ui.navigation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.flue.launcher.motion.LocalFlueMotionProfile
import com.flue.launcher.ui.common.flueOverlayOvershootPx
import kotlin.math.abs

/** 表盘上可跟手拉出的覆层。 */
enum class OverlayGestureTarget { Stack, Widgets, ControlCenter }

internal fun resolveOverlaySettleOpen(
    progress: Float,
    velocity: Float,
    currentlyOpen: Boolean,
    cancelled: Boolean,
): Boolean {
    if (cancelled) return currentlyOpen
    val safeVelocity = velocity.takeIf(Float::isFinite) ?: 0f
    return when {
        safeVelocity >= 1.6f -> true
        safeVelocity <= -1.6f -> false
        else -> (progress.takeIf(Float::isFinite) ?: if (currentlyOpen) 1f else 0f) > 0.42f
    }
}

internal fun resolveAppListSettleOpen(
    progress: Float,
    velocity: Float,
    cancelled: Boolean,
): Boolean = resolveOverlaySettleOpen(
    progress = progress,
    velocity = velocity,
    currentlyOpen = false,
    cancelled = cancelled,
)

/**
 * 全局导航手势宿主。
 *
 * 覆层打开侧是全程跟手的：手指一动就通过 [onOverlayDrag] 逐帧上报归一化
 * 进度（0..1），抬手经 [onOverlayRelease] 上报终点进度与投影速度，由宿主
 * 决定开/关并带初速度收尾。以前的「80px 裸阈值 → 切状态 → 播固定 tween」
 * 应用列表也使用独立的逐帧回调，但仍由它自己的转场机负责抬手收尾。
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
    stackDismissHandledByContent: Boolean = false,
    controlCenterDismissHandledByContent: Boolean = false,
    onOverlayDrag: ((OverlayGestureTarget, Float) -> Unit)? = null,
    onOverlayRelease: ((OverlayGestureTarget, Float, Float, Boolean) -> Unit)? = null,
    /**
     * 覆层已经打开、手指继续「往外」拉时的越界位移（像素，正数 = 内容往右）。
     *
     * 刻意与 [onOverlayDrag] 分成两条通道：进度必须留在 0..1（三处 `coerceIn` 都靠它
     * 表达「开/关」），越界是**叠在**开态之上的额外位移，交给 graphicsLayer 的
     * translation 而不是布局，避免重排。
     */
    onOverlayOvershoot: ((Float) -> Unit)? = null,
    onAppListDrag: ((Float) -> Unit)? = null,
    onAppListRelease: ((Float, Float, Boolean) -> Unit)? = null,
    onBackdropCaptureRequested: (() -> Unit)? = null,
    /**
     * 全局锁死。表盘加载页显示期间为 true —— 加载没完成就不允许上滑应用列表
     * 或侧滑负一屏，否则用户会划到一个还没准备好的界面上。
     *
     * 锁在这里而不是靠加载页自己的 pointerInput：这些识别器挂在加载页的**外层**
     * 容器上，加载页拦不住它们。
     */
    gesturesLocked: Boolean = false,
    content: @Composable () -> Unit
) {
    var totalDx by remember { mutableFloatStateOf(0f) }
    var totalDy by remember { mutableFloatStateOf(0f) }
    var consumed by remember { mutableStateOf(false) }
    var overlayTarget by remember { mutableStateOf<OverlayGestureTarget?>(null) }
    // 「已打开的覆层还在被往外拉」这条独立通道。它不改进度，只报越界位移。
    var overshootTarget by remember { mutableStateOf<OverlayGestureTarget?>(null) }
    var appListGestureActive by remember { mutableStateOf(false) }
    val velocityTracker = remember { VelocityTracker() }
    val motionProfile = LocalFlueMotionProfile.current

    // 宿主手势只在真正需要它的状态挂载。detectDragGestures 跨过 touch-slop
    // 时会消费事件，Compose 一消费，底下 AndroidView（原生应用列表）立刻
    // 收到 ACTION_CANCEL——列表在边缘 overscroll 时没有消费滚动量、
    // requestDisallowIntercept 保护不生效，手势被偷走就是「到顶继续滑没有
    // 反馈且卡死 / 偶发滑不动」的第二个独立病灶（诊断日志里成串的 a=3）。
    val hostGestureNeeded = if (gesturesLocked) false else when (screenState) {
        ScreenState.Face, ScreenState.Widgets -> true
        ScreenState.Stack -> !stackDismissHandledByContent
        // 控制中心以前不在这份名单里：覆层一打开手势宿主就整体卸载，
        // 于是「拉出跟手、返回不跟手」。它的关闭方向要走同一条跟手链路。
        ScreenState.ControlCenter -> !controlCenterDismissHandledByContent
        else -> false
    }
    // 覆层已经打开时，这一次拖拽是「往回收」：进度基准是 1，手指反向移动
    // 让它递减到 0，抬手仍由 onOverlayRelease 带初速度收尾。
    // 三个覆层都要认领关闭方向，不能只有控制中心：负一屏 / 小组件页此前
    // 一律返回 null，关闭方向没人认领，于是「拉出跟手、退出不跟手」。
    // 小组件页被编辑手势锁住时不认领，把关闭交还给下面的 80px 兜底分支。
    val closingOverlay: OverlayGestureTarget? = when (screenState) {
        ScreenState.ControlCenter -> OverlayGestureTarget.ControlCenter
            .takeIf { !controlCenterDismissHandledByContent }
        ScreenState.Stack -> OverlayGestureTarget.Stack
            .takeIf { !stackDismissHandledByContent }
        ScreenState.Widgets -> OverlayGestureTarget.Widgets.takeIf { !widgetsBackGestureLocked }
        else -> null
    }
    Box(
        modifier = modifier
            // 在 touch-slop 之前先取一张真实窗口合成帧。这个观察器不消费
            // 指针；方向识别与原来的拖动宿主并行，横/纵手势都能复用该帧。
            .pointerInput(screenState, onBackdropCaptureRequested, gesturesLocked) {
                if (
                    gesturesLocked ||
                    screenState != ScreenState.Face ||
                    onBackdropCaptureRequested == null
                ) {
                    return@pointerInput
                }
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onBackdropCaptureRequested.invoke()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .pointerInput(
                hostGestureNeeded,
                screenState,
                sideScreenEnabled,
                showWidgetPage,
                showControlCenter,
                widgetsBackGestureLocked,
                closingOverlay,
                // 解锁后必须重建识别器，否则加载完成了手势还是死的。
                gesturesLocked,
                motionProfile,
            ) {
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

                /**
                 * 已打开覆层「继续往外拉」的越界位移，签名与 translationX 同向
                 * （正数 = 内容往右）。
                 *
                 * 竖向的控制中心不走这条路：它有自己的一段式 dismiss 物理（见
                 * ControlCenterLayer），在那里叠越界会和 dismiss 进度打架。
                 */
                fun overlayOvershoot(target: OverlayGestureTarget): Float {
                    val outward = when (target) {
                        OverlayGestureTarget.Stack -> totalDx
                        OverlayGestureTarget.Widgets -> -totalDx
                        OverlayGestureTarget.ControlCenter -> return 0f
                    }
                    val magnitude = flueOverlayOvershootPx(
                        outwardTravelPx = outward,
                        viewportPx = size.width,
                        density = density,
                        motionProfile = motionProfile,
                    )
                    return if (target == OverlayGestureTarget.Widgets) -magnitude else magnitude
                }

                fun overlayVelocity(target: OverlayGestureTarget): Float {
                    val velocity = velocityTracker.calculateVelocity()
                    return when (target) {
                        OverlayGestureTarget.Stack -> velocity.x / (widthPx * 0.8f)
                        OverlayGestureTarget.Widgets -> -velocity.x / (widthPx * 0.8f)
                        OverlayGestureTarget.ControlCenter -> velocity.y / (heightPx * 0.8f)
                    }
                }

                fun appListProgress(): Float =
                    (-totalDy / (heightPx * 0.8f)).coerceIn(0f, 1f)

                fun appListVelocity(): Float =
                    -velocityTracker.calculateVelocity().y / (heightPx * 0.8f)

                detectDragGestures(
                    onDragStart = {
                        totalDx = 0f
                        totalDy = 0f
                        consumed = false
                        overlayTarget = null
                        overshootTarget = null
                        appListGestureActive = false
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

                        val activeOvershoot = overshootTarget
                        if (activeOvershoot != null) {
                            onOverlayOvershoot?.invoke(overlayOvershoot(activeOvershoot))
                            change.consume()
                            return@detectDragGestures
                        }

                        if (appListGestureActive) {
                            onAppListDrag?.invoke(appListProgress())
                            change.consume()
                            return@detectDragGestures
                        }

                        // 覆层已打开：认领关闭方向的跟手。方向门必须按目标
                        // 分派——以前这里把轴向硬编码成竖向且只认 totalDy<0，
                        // 那是控制中心（下拉打开、上滑收回）的规则。负一屏是
                        // 右滑打开、关闭方向向左；小组件页相反；两者都得走横轴，
                        // 所以光把它们加进 closingOverlay 是不够的。
                        val closingClaimed = closingOverlay != null && when (closingOverlay) {
                            OverlayGestureTarget.ControlCenter ->
                                abs(totalDy) > axisLockPx &&
                                    abs(totalDy) > abs(totalDx) &&
                                    totalDy < 0f
                            OverlayGestureTarget.Stack ->
                                abs(totalDx) > axisLockPx &&
                                    abs(totalDx) > abs(totalDy) &&
                                    totalDx < 0f
                            OverlayGestureTarget.Widgets ->
                                abs(totalDx) > axisLockPx &&
                                    abs(totalDx) > abs(totalDy) &&
                                    totalDx > 0f
                        }
                        if (closingOverlay != null && !consumed &&
                            closingClaimed && onOverlayDrag != null
                        ) {
                            overlayTarget = closingOverlay
                            consumed = true
                            onOverlayDrag.invoke(closingOverlay, overlayProgress(closingOverlay))
                            change.consume()
                            return@detectDragGestures
                        }

                        // 覆层已打开、手指继续**往外**拉：以前这里没有任何 case ——
                        // 横向进度被 coerceIn(0,1) 夹死，走到下面 80px 兜底时又只有
                        // 「往回收」那一个方向，于是 consumed 被置为真而什么都不做，
                        // 手势整条死掉（老师报的「到边界硬停」）。
                        val outwardClaimed = closingOverlay != null &&
                            onOverlayOvershoot != null &&
                            when (closingOverlay) {
                                OverlayGestureTarget.Stack ->
                                    abs(totalDx) > axisLockPx &&
                                        abs(totalDx) > abs(totalDy) &&
                                        totalDx > 0f
                                OverlayGestureTarget.Widgets ->
                                    abs(totalDx) > axisLockPx &&
                                        abs(totalDx) > abs(totalDy) &&
                                        totalDx < 0f
                                // 控制中心不走越界，见 overlayOvershoot 的注释。
                                OverlayGestureTarget.ControlCenter -> false
                            }
                        if (closingOverlay != null && !consumed && outwardClaimed) {
                            overshootTarget = closingOverlay
                            consumed = true
                            onOverlayOvershoot?.invoke(overlayOvershoot(closingOverlay))
                            change.consume()
                            return@detectDragGestures
                        }

                        if (screenState == ScreenState.Face && !consumed &&
                            (abs(totalDx) > axisLockPx || abs(totalDy) > axisLockPx)
                        ) {
                            val isVertical = abs(totalDy) > abs(totalDx)
                            if (isVertical && totalDy < 0f && onAppListDrag != null) {
                                appListGestureActive = true
                                consumed = true
                                onAppListDrag.invoke(appListProgress())
                                change.consume()
                                return@detectDragGestures
                            }
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
                                false,
                            )
                        }
                        // 越界只回弹，不改开/关：cancelled = true 让宿主保持当前状态，
                        // 由它把越界位移弹回 0。
                        overshootTarget?.let { target ->
                            onOverlayRelease?.invoke(target, overlayProgress(target), 0f, true)
                        }
                        if (appListGestureActive) {
                            onAppListRelease?.invoke(
                                appListProgress(),
                                appListVelocity(),
                                false,
                            )
                        }
                        overlayTarget = null
                        overshootTarget = null
                        appListGestureActive = false
                    },
                    onDragCancel = {
                        overlayTarget?.let { target ->
                            onOverlayRelease?.invoke(target, overlayProgress(target), 0f, true)
                        }
                        overshootTarget?.let { target ->
                            onOverlayRelease?.invoke(target, overlayProgress(target), 0f, true)
                        }
                        if (appListGestureActive) {
                            onAppListRelease?.invoke(appListProgress(), 0f, true)
                        }
                        overlayTarget = null
                        overshootTarget = null
                        appListGestureActive = false
                    },
                )
            }
    ) {
        content()
    }
}
