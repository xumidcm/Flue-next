package com.flue.launcher.ui.style.md3

import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import com.flue.launcher.ui.style.FlueHaptics
import com.flue.launcher.ui.theme.LauncherTheme

/**
 * Material 3 档控件：直接用 M3 真组件。
 *
 * MD3 侧的主题层早就接了 `MaterialExpressiveTheme` + `MotionScheme.expressive()`，
 * 但**组件层一个 Expressive 都没用到** —— 开关是手画的 `Box + drawRect`。换成真
 * `material3.Switch` 之后，Expressive 的动效曲线、按压态、无障碍语义全部白拿。
 *
 * 颜色仍从 `LauncherTheme.style` 取（而不是纯 `MaterialTheme.colorScheme`）：
 * 强调色是用户在设置里选的，三套风格共用同一个来源。
 *
 * 触觉要**我们自己给**：M3 的 `Switch` / `Slider` 到 1.5 仍然不发触觉反馈（miuix 那档是
 * 库自带，所以那边刻意不叠）。给的类型走 [FlueHaptics] 的 MD3 分支，也就是系统语义常量
 * `ToggleOn`/`ToggleOff`/`SegmentTick`，跟着平台走。
 */

@Composable
internal fun Md3Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val style = LauncherTheme.style
    val haptics = LocalHapticFeedback.current
    Switch(
        checked = checked,
        onCheckedChange = { next ->
            haptics.performHapticFeedback(FlueHaptics.toggle(style.uiStyle, next))
            onCheckedChange(next)
        },
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = style.accentColor,
            checkedThumbColor = style.screenBackground,
        ),
    )
}

/**
 * 纯展示版：`onCheckedChange = null` 就是 M3 官方表达「不可交互、由外层承担」的写法，
 * 它同时会把语义节点让给父级，正好符合「整行可点」的用法。
 */
@Composable
internal fun Md3SwitchIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val style = LauncherTheme.style
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = style.accentColor,
            checkedThumbColor = style.screenBackground,
        ),
    )
}

@Composable
internal fun Md3Slider(
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
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    // 跨过一格才震一次。不记上一格的话，连续拖动会每帧都发一次触觉。
    val lastTick = remember { mutableIntStateOf(Int.MIN_VALUE) }
    Slider(
        value = value,
        onValueChange = { next ->
            val fraction = ((next - valueRange.start) / span).coerceIn(0f, 1f)
            val stepped = steps > 0
            val index = if (stepped) {
                Math.round(fraction * (steps + 1))
            } else {
                // 无刻度时只在两端给一次。
                if (fraction <= 0f || fraction >= 1f) 1 else 0
            }
            if (index != lastTick.value) {
                lastTick.value = index
                if (stepped || index == 1) {
                    haptics.performHapticFeedback(FlueHaptics.sliderTick(style.uiStyle, stepped))
                }
            }
            onValueChange(next)
        },
        onValueChangeFinished = {
            haptics.performHapticFeedback(FlueHaptics.sliderRelease(style.uiStyle))
            onValueChangeFinished?.invoke()
        },
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = style.accentColor,
            activeTrackColor = style.accentColor,
        ),
    )
}
