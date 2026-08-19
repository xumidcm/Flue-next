package com.flue.launcher.ui.style.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.LauncherTheme
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults

/**
 * MIUIX（HyperOS）档控件：用 miuix 库的真组件。
 *
 * 项目里 miuix 依赖躺着只被用了图标，真组件一个没用 —— 于是「MIUIX 风格」实际上只是
 * 一套配色 + 手搓外观。这里换成 `basic.Switch` / `basic.Slider` 之后，HyperOS 的动效
 * 曲线、拖动手势、无障碍语义、以及**库自带的触觉**全部白拿。
 *
 * ## 触觉刻意不叠我们自己的
 *
 * miuix 的 `Switch` 内部就调 `performHapticFeedback`（点按与拖动两条路径都有），`Slider`
 * 则由 `hapticEffect` 参数控制。再套一层 `FlueHaptics` 会变成一次操作震两下，比不震更糟。
 * 所以这一档的触觉**交给库**，`FlueHaptics` 只服务另外两档 —— 这也正是「用库预设、不要
 * 自己搓」的意思。
 *
 * 命名带 `Style` 前缀是为了跟项目自己的 `MiuixSwitch` / `MiuixSlider` 区分，避免同名歧义。
 */

@Composable
internal fun MiuixStyleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val style = LauncherTheme.style
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        // 强调色是用户在设置里选的，三套风格共用同一个来源；其余留库的默认值。
        colors = SwitchDefaults.switchColors(
            checkedTrackColor = style.accentColor,
            uncheckedTrackColor = style.secondaryTextColor.copy(alpha = 0.36f),
        ),
    )
}

/**
 * 纯展示版：**不能**直接用库的 `Switch(onCheckedChange = null)` —— 那份实现内部仍然挂着
 * `MutableInteractionSource`、拖动手势与按压态收集，在隐藏应用页那种「每个 fling 帧都新组合
 * 一行」的长列表里，多出来的交互节点正是当初被优化掉的开销。
 *
 * 所以这里保留一份**只绘制**的实现（44×26 轨道、21dp 圆滑块、18dp 行程、spring(0.9, 600)，
 * 与 HyperOS 观感一致），按压态由调用方从整行的 interactionSource 传进来。
 */
@Composable
internal fun MiuixStyleSwitchIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressed: Boolean = false,
) {
    val style = LauncherTheme.style
    val trackColor by animateColorAsState(
        when {
            !enabled -> style.secondaryTextColor.copy(alpha = 0.20f)
            checked -> style.accentColor
            else -> style.secondaryTextColor.copy(alpha = 0.36f)
        },
        label = "miuix_switch_indicator_track",
    )
    val progress by animateFloatAsState(
        if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
        label = "miuix_switch_indicator_thumb",
    )
    // 拖影只缩放绘制层，不改 thumb 宽度 —— 改宽度会每帧重新测量整行。
    val stretch by animateFloatAsState(
        if (pressed && enabled) 1.18f else 1f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
        label = "miuix_switch_indicator_stretch",
    )
    Box(
        modifier = modifier
            .width(44.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .drawBehind { drawRect(trackColor) },
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.5.dp, top = 2.5.dp)
                .size(21.dp)
                .graphicsLayer {
                    translationX = 18.dp.toPx() * progress
                    scaleX = stretch
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                        pivotFractionX = if (checked) 1f else 0f,
                        pivotFractionY = 0.5f,
                    )
                }
                .background(Color.White, CircleShape),
        )
    }
}

/** HyperOS 的滑块是 24dp 高的粗胶囊、无可见拇指：填充即值。 */
private val MIUIX_TRACK_HEIGHT: Dp = 24.dp

@Composable
internal fun MiuixStyleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val style = LauncherTheme.style
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        // 库这个参数是非空 `() -> Unit`，没有回调时给个空实现。
        onValueChangeFinished = { onValueChangeFinished?.invoke() },
        height = MIUIX_TRACK_HEIGHT,
        colors = SliderDefaults.sliderColors(
            foregroundColor = style.accentColor,
            backgroundColor = style.pressedCardColor,
        ),
        // 有刻度逐格给、无刻度只在端点给 —— 库自己的预设，不用我们再算。
        hapticEffect = if (steps > 0) {
            SliderDefaults.SliderHapticEffect.Step
        } else {
            SliderDefaults.SliderHapticEffect.Edge
        },
    )
}
