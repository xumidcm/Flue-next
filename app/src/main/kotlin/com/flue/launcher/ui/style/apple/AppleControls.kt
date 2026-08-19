package com.flue.launcher.ui.style.apple

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.style.FlueDampedDrag
import com.flue.launcher.ui.style.FlueHaptics
import com.flue.launcher.ui.style.FlueDampedDragState
import com.flue.launcher.ui.style.rememberFlueDampedDragState
import com.flue.launcher.ui.theme.LauncherTheme

/**
 * Apple Watch 档控件：照 Kyant `LiquidToggle` / `LiquidSlider` 的简化重做。
 *
 * 状态机与全部取值在 [FlueDampedDrag]，那里记着出处与「刻意没带过来的东西」。
 * 这个文件只管几何与绘制。
 *
 * ## 形状是横胶囊，不是圆
 *
 * iOS 26 的开关滑块是 40×24 的**横胶囊**（旧版才是圆的）。滑块按下去放大 1.5 倍到
 * 60×36，会溢出 64×28 的轨道 —— iOS 就是这么做的，所以父级不能 clip、行高要留够。
 *
 * ## 圆屏给一档更小的基线
 *
 * 64dp 轨道在窄圆屏上占屏宽约 28%，一行里再放标题就挤了。所以圆屏取 56×26 轨道 /
 * 36×22 滑块，方屏取全尺寸；两档都再乘 `switchVisualScale`（全局 UI 大小那条）。
 */

internal data class AppleSwitchGeometry(
    val trackWidth: Dp,
    val trackHeight: Dp,
    val thumbWidth: Dp,
    val thumbHeight: Dp,
    val padding: Dp,
) {
    /** 滑块从最左到最右要走的距离。 */
    val travel: Dp get() = trackWidth - thumbWidth - padding * 2
}

@Composable
private fun appleSwitchGeometry(): AppleSwitchGeometry {
    val scale = LauncherTheme.style.switchVisualScale
    val base = if (isFlueScreenRound()) {
        AppleSwitchGeometry(56.dp, 26.dp, 36.dp, 22.dp, 2.dp)
    } else {
        AppleSwitchGeometry(64.dp, 28.dp, 40.dp, 24.dp, 2.dp)
    }
    if (scale == 1f) return base
    return AppleSwitchGeometry(
        trackWidth = base.trackWidth * scale,
        trackHeight = base.trackHeight * scale,
        thumbWidth = base.thumbWidth * scale,
        thumbHeight = base.thumbHeight * scale,
        padding = base.padding * scale,
    )
}

@Composable
internal fun AppleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val style = LauncherTheme.style
    val haptics = LocalHapticFeedback.current
    val geometry = appleSwitchGeometry()
    val scope = rememberCoroutineScope()
    val state = rememberFlueDampedDragState(
        initialFraction = if (checked) 1f else 0f,
        velocityDivisor = FlueDampedDrag.SWITCH_VELOCITY_DIVISOR,
    )
    // 手势块里要读的「当前值」。用 rememberUpdatedState 而不是把 checked 放进
    // pointerInput 的键里 —— 后者会在每次切换时重建手势块。
    val checkedState by rememberUpdatedState(checked)
    // 回调同样要防过期：手势协程握着第一次组合时的 lambda（见 [AppleSlider] 里那段长注释）。
    val checkedChange by rememberUpdatedState(onCheckedChange)
    // 上游状态变了（比如别处改了同一个偏好）也要动过去。
    LaunchedEffect(checked) {
        if (!FlueDampedDrag.isSettled(state.fraction.value, if (checked) 1f else 0f)) {
            state.animateValue(scope, if (checked) 1f else 0f)
        }
    }
    val trackColor by animateColorAsState(
        when {
            !enabled -> style.secondaryTextColor.copy(alpha = 0.20f)
            checked -> style.positiveColor
            else -> style.secondaryTextColor.copy(alpha = 0.45f)
        },
        label = "apple_switch_track",
    )
    Box(
        modifier = modifier
            // 视觉可以缩，可点区域不缩：轨道只有 28dp 高，触摸目标补到令牌给的 48dp。
            // 手势挂在这层（外层）而不是轨道上，所以轨道上下那一圈也吃点击。
            //
            // **本控件不为「按下放大」预留横向余量**：滑块按下涨到 1.5 倍，两端各多出
            // `thumbWidth * 0.25 - padding`（全尺寸 8dp），Box 不 clip，这点溢出画到设置行
            // 自己的横向内边距里（方屏 16dp / 圆屏 24dp，都大于 8dp），不会被行卡的 clip 切到。
            // 预留余量的话开关会比同一列表里其他行的尾部控件内缩 8dp，反而看着没对齐。
            .defaultMinSize(minWidth = geometry.trackWidth, minHeight = style.switchTouchTarget)
            // 手势块**不以 checked 为键**：拖动过程中值会变，重建 pointerInput 会把正在
            // 进行的手势连同「松手复位」一起取消，滑块就胖着不回来了。当前值改从
            // checkedState 读。
            .pointerInput(enabled, geometry) {
                if (!enabled) return@pointerInput
                val travelPx = geometry.travel.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // **不在这里消费 down**。
                    //
                    // 以前一落手就 `down.consume()`，父级 scrollable 拿不到这一串事件 ——
                    // 于是在开关上**竖着划**也会被当成操作开关，列表滚不动、还容易误切
                    // （老师报「AW 开关太敏感，在它上面竖向刷动都会触发」）。
                    //
                    // 改成 miuix 那套判据：先看方向。横向先过阈值 → 这串手势归开关，从那一刻
                    // 起才消费；竖向先过阈值 → 整串让给列表滚动，开关一点都不动；一直没过阈值
                    // 就抬手 → 当点按切换。判据放在 [FlueDampedDrag] 里，三档共用一个数。
                    state.press(scope)
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    val startFraction = state.fraction.value
                    val wasChecked = checkedState
                    var total = 0f
                    var completed = false
                    var owned = false
                    var abandoned = false
                    try {
                        // ── 第一阶段：认方向 ────────────────────────────────────
                        var accumulatedX = 0f
                        var accumulatedY = 0f
                        val slopPx = FlueDampedDrag.DIRECTION_SLOP_DP.toPx()
                        while (!owned && !abandoned) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            // 别人（父级滚动）已经把它消费了：这串不归我们。
                            if (change.isConsumed) {
                                abandoned = true
                                break
                            }
                            accumulatedX += change.positionChange().x
                            accumulatedY += change.positionChange().y
                            tracker.addPointerInputChange(change)
                            if (kotlin.math.abs(accumulatedY) > slopPx &&
                                kotlin.math.abs(accumulatedY) > kotlin.math.abs(accumulatedX)
                            ) {
                                abandoned = true
                            } else if (kotlin.math.abs(accumulatedX) > slopPx) {
                                owned = true
                                total = accumulatedX
                                change.consume()
                            }
                        }
                        if (abandoned) {
                            // 交给列表滚动：把放大收回去，值不动。
                            state.cancelPress(scope)
                            completed = true
                            return@awaitEachGesture
                        }
                        // ── 第二阶段：横向拖动（只有认领之后才走）─────────────────
                        if (owned) {
                            state.dragTo(
                                scope = scope,
                                target = startFraction +
                                    (if (travelPx == 0f) 0f else total / travelPx),
                                velocityPxPerSecond = tracker.calculateVelocity().x,
                            )
                            drag(down.id) { change: PointerInputChange ->
                                tracker.addPointerInputChange(change)
                                total += change.positionChange().x
                                change.consume()
                                state.dragTo(
                                    scope = scope,
                                    target = startFraction +
                                        (if (travelPx == 0f) 0f else total / travelPx),
                                    velocityPxPerSecond = tracker.calculateVelocity().x,
                                )
                            }
                        }
                        val target = if (owned && FlueDampedDrag.isDrag(total)) {
                            FlueDampedDrag.snapToOn(state.fraction.value)
                        } else {
                            // 没认领过（原地抬手）→ 当点按用。
                            !wasChecked
                        }
                        state.release(scope, if (target) 1f else 0f)
                        completed = true
                        if (target != wasChecked) {
                            haptics.performHapticFeedback(FlueHaptics.toggle(style.uiStyle, target))
                            checkedChange(target)
                        }
                    } finally {
                        // 任何异常路径（协程被取消、控件离屏）都要把放大收回去。
                        if (!completed) state.cancelPress(scope)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(geometry.trackWidth)
                .height(geometry.trackHeight)
                // **不能 clip**：clip 会把放大后溢出轨道的滑块切掉一块（iOS 的开关就是
                // 让滑块溢出轨道的）。所以轨道自己画成圆角矩形，不靠 clip 出形状。
                .drawBehind {
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            AppleThumb(
                state = state,
                geometry = geometry,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun AppleThumb(
    state: FlueDampedDragState,
    geometry: AppleSwitchGeometry,
    enabled: Boolean,
) {
    Box(
        modifier = Modifier
            .padding(start = geometry.padding)
            .size(width = geometry.thumbWidth, height = geometry.thumbHeight)
            .graphicsLayer {
                translationX = geometry.travel.toPx() * state.fraction.value
                scaleX = state.scaleX
                scaleY = state.scaleY
                alpha = if (enabled) 1f else 0.6f
            }
            .shadow(4.dp, CircleShape)
            .flueThumbSurface { state.pressProgress },
    )
}

/**
 * 滑块 / 拇指的表面材质。开关与拉杆共用一份。
 *
 * 静止是一枚不透明白胶囊；按下去按 [FlueDampedDrag.PRESSED_ALPHA] 变半透明，并叠一条顶缘
 * 高光与一圈边缘暗描边 —— 老师要的「单纯透明加上边缘阴影描边和高光，**不要模糊**」。
 *
 * 三件事都在**一个 drawBehind 里**画，而不是叠 `background` + `border`：
 *
 * - 背景与描边挂成两个 modifier 的话，透明度得各写一遍，按压时两者的插值容易不同步；
 * - 更重要的是**不引入任何 blur / backdrop 采样**。原版 Kyant 的折射玻璃就是靠采样背板做的，
 *   那正是当初点名太吃性能而砍掉的部分；这里只有三次纯色 / 渐变绘制，代价可以忽略。
 *
 * 按压进度用 lambda 传进来（而不是直接读 Float）：`drawBehind` 的 lambda 每帧重跑，
 * 传值会把这一帧的按压进度**固化**进 modifier，动画就只在重组时才动。
 */
private fun Modifier.flueThumbSurface(pressProgress: () -> Float): Modifier = drawBehind {
    val press = pressProgress().coerceIn(0f, 1f)
    val radius = CornerRadius(size.height / 2f, size.height / 2f)
    val fillAlpha = 1f - (1f - FlueDampedDrag.PRESSED_ALPHA) * press
    drawRoundRect(color = Color.White.copy(alpha = fillAlpha), cornerRadius = radius)
    if (press <= 0.001f) return@drawBehind
    // 顶缘高光：只覆顶部一小截，才像一条受光的边而不是一层白纱。
    val highlightHeight = size.height * FlueDampedDrag.PRESSED_HIGHLIGHT_FRACTION
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = FlueDampedDrag.PRESSED_HIGHLIGHT_ALPHA * press),
                Color.Transparent,
            ),
            startY = 0f,
            endY = highlightHeight,
        ),
        cornerRadius = radius,
    )
    // 边缘暗描边：把半透明的滑块从轨道上「切」出来，不然透出底色之后轮廓会发糊。
    drawRoundRect(
        color = Color.Black.copy(alpha = FlueDampedDrag.PRESSED_EDGE_ALPHA * press),
        cornerRadius = radius,
        style = Stroke(width = THUMB_EDGE_STROKE_PX),
    )
}

/** 描边宽度（px）。1.2px 在任何 DPI 下都是「一条细边」，不随密度变粗。 */
private const val THUMB_EDGE_STROKE_PX = 1.2f

/**
 * 纯展示版：不收手势、不放大，只把值画出来。
 *
 * 用在「整行可点 + 开关纯展示」的长列表里（隐藏应用页那种每个 fling 帧都新组合一行的）。
 * 几何与交互版一致，所以同一档风格下两者看起来是同一个开关。
 */
@Composable
internal fun AppleSwitchIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val style = LauncherTheme.style
    val geometry = appleSwitchGeometry()
    val trackColor by animateColorAsState(
        when {
            !enabled -> style.secondaryTextColor.copy(alpha = 0.20f)
            checked -> style.positiveColor
            else -> style.secondaryTextColor.copy(alpha = 0.45f)
        },
        label = "apple_switch_indicator_track",
    )
    val progress by animateFloatAsState(
        if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1000f),
        label = "apple_switch_indicator_thumb",
    )
    Box(
        modifier = modifier
            .width(geometry.trackWidth)
            .height(geometry.trackHeight)
            .clip(CircleShape)
            .drawBehind { drawRect(trackColor) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = geometry.padding)
                .size(width = geometry.thumbWidth, height = geometry.thumbHeight)
                .graphicsLayer { translationX = geometry.travel.toPx() * progress }
                .background(Color.White, CircleShape),
        )
    }
}

/** 滑块几何：6dp 细胶囊轨道 + 40×24 横胶囊拇指（源码取值）。 */
private val SLIDER_TRACK_HEIGHT = 6.dp
private val SLIDER_THUMB_WIDTH = 40.dp
private val SLIDER_THUMB_HEIGHT = 24.dp

/** 整体高度留到 48dp：与 `material3.Slider` 一致，滑块行的名义几何常量不用跟着改。 */
private val SLIDER_ROW_HEIGHT = 48.dp

@Composable
internal fun AppleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val style = LauncherTheme.style
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val incomingFraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val state = rememberFlueDampedDragState(
        initialFraction = incomingFraction,
        velocityDivisor = FlueDampedDrag.SLIDER_VELOCITY_DIVISOR,
    )
    var widthPx by remember { mutableIntStateOf(0) }
    var lastTickIndex by remember { mutableIntStateOf(-1) }
    // **回调必须走 rememberUpdatedState**。`pointerInput` 只在 key 变化时重启协程，而 key 里
    // 没有回调，所以正在运行的手势协程一直握着**第一次组合**时的那个 lambda。
    //
    // 画布调节页的行把回调写成 `{ onCommit(settings.copy(zoomX = it)) }` —— `settings` 是组合期
    // 捕获的值。滑杆一直不重启的话，第二次拖它用的还是第一次组合时的旧 settings，于是中间调好的
    // 另一个参数被旧值覆盖回去：老师报的「调完一个数值，再调第二个，回头再动第一个，
    // 第二个数值有概率被强制拉回」。State 对象跨重组是同一个，读的时候才取值，所以不会过期。
    val valueChange by rememberUpdatedState(onValueChange)
    val valueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    // 拖动中不要被上游回写打断（回写值来自我们自己），只在差得明显时才纠。
    LaunchedEffect(incomingFraction) {
        if (!FlueDampedDrag.isSettled(state.fraction.value, incomingFraction)) {
            state.animateValue(scope, incomingFraction)
        }
    }

    fun emit(fraction: Float) {
        valueChange(valueRange.start + fraction * span)
    }

    fun tickIfCrossed(fraction: Float) {
        val stepped = steps > 0
        val index = if (stepped) Math.round(fraction * (steps + 1)) else if (fraction <= 0f || fraction >= 1f) 1 else 0
        if (index != lastTickIndex) {
            lastTickIndex = index
            // 无刻度的连续滑块只在端点给一次，否则整条拖动会变成一串持续震动。
            if (stepped || index == 1) {
                haptics.performHapticFeedback(FlueHaptics.sliderTick(style.uiStyle, stepped))
            }
        }
    }

    Box(
        modifier = modifier
            // **必须自己铺满**：调用点给的 modifier 不一定带宽度约束，不铺满时这个 Box
            // 会按内容（40dp 的拇指）收宽 —— 那就是老师看到的「拉杆只有一个格子、
            // 拖不动」：可用行程 = 宽度 − 拇指宽 = 0。
            .fillMaxWidth()
            .height(SLIDER_ROW_HEIGHT)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled, steps, valueRange) {
                if (!enabled) return@pointerInput
                val thumbPx = SLIDER_THUMB_WIDTH.toPx()
                // **拖动期间不量化**：老师要的是「滑动起来无极，但会自动吸附」——
                // HyperOS 的滑块就是这样。以前这里当场 quantize，于是拖动是一格一格跳的。
                fun rawFractionAt(x: Float): Float {
                    val usable = (size.width - thumbPx).coerceAtLeast(1f)
                    return ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 与开关同理：手指落在滑块上就消费，页面不跟着滚。
                    down.consume()
                    state.press(scope)
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    var fraction = rawFractionAt(down.position.x)
                    state.dragTo(scope, fraction, 0f)
                    tickIfCrossed(fraction)
                    emit(fraction)
                    var completed = false
                    try {
                        drag(down.id) { change: PointerInputChange ->
                            tracker.addPointerInputChange(change)
                            fraction = rawFractionAt(change.position.x)
                            change.consume()
                            state.dragTo(scope, fraction, tracker.calculateVelocity().x)
                            // 越过刻度线时给一下震动 —— 那是「吸附点在这里」的提示，
                            // 也是无极拖动下唯一能感知刻度的方式。
                            tickIfCrossed(fraction)
                            emit(fraction)
                        }
                        // 松手才吸附：动到最近一格并把**吸附后的值**报出去。
                        // 落盘值必须是吸附后的，否则设置里会存下 37.4% 这种中间值。
                        val snapped = FlueDampedDrag.quantize(fraction, steps)
                        state.release(scope, snapped)
                        emit(snapped)
                        completed = true
                        haptics.performHapticFeedback(FlueHaptics.sliderRelease(style.uiStyle))
                        valueChangeFinished?.invoke()
                    } finally {
                        if (!completed) state.cancelPress(scope)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val fraction = state.fraction.value
        // 轨道
        Box(
            modifier = Modifier
                .height(SLIDER_TRACK_HEIGHT)
                .width(with(androidx.compose.ui.platform.LocalDensity.current) { widthPx.toDp() })
                .clip(CircleShape)
                .background(style.secondaryTextColor.copy(alpha = if (enabled) 0.30f else 0.18f)),
        ) {
            Box(
                modifier = Modifier
                    .height(SLIDER_TRACK_HEIGHT)
                    .width(
                        with(androidx.compose.ui.platform.LocalDensity.current) {
                            (widthPx * fraction).toInt().toDp()
                        },
                    )
                    .clip(CircleShape)
                    .background(if (enabled) style.accentColor else style.secondaryTextColor),
            )
        }
        // 拇指
        Box(
            modifier = Modifier
                .size(width = SLIDER_THUMB_WIDTH, height = SLIDER_THUMB_HEIGHT)
                .graphicsLayer {
                    val usable = (widthPx - SLIDER_THUMB_WIDTH.toPx()).coerceAtLeast(0f)
                    translationX = usable * fraction
                    scaleX = state.scaleX
                    scaleY = state.scaleY
                    alpha = if (enabled) 1f else 0.6f
                }
                .shadow(4.dp, CircleShape)
                .flueThumbSurface { state.pressProgress },
        )
    }
}
