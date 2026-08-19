package com.flue.launcher.ui.style

import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.flue.launcher.ui.style.apple.AppleSlider
import com.flue.launcher.ui.style.apple.AppleSwitch
import com.flue.launcher.ui.style.apple.AppleSwitchIndicator
import com.flue.launcher.ui.style.md3.Md3Slider
import com.flue.launcher.ui.style.md3.Md3Switch
import com.flue.launcher.ui.style.md3.Md3SwitchIndicator
import com.flue.launcher.ui.style.miuix.MiuixStyleSlider
import com.flue.launcher.ui.style.miuix.MiuixStyleSwitch
import com.flue.launcher.ui.style.miuix.MiuixStyleSwitchIndicator
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle

/**
 * 业务侧唯一的控件入口。
 *
 * ## 为什么要这一层
 *
 * `LauncherStyle` 只是**一袋令牌**（颜色 / 圆角 / 动效 spec），106 个调用点读它然后
 * **各自手搓外观** —— 没有组件抽象层。于是「三套 UI 风格」实际上只是三套配色：
 * miuix 依赖躺在项目里只被用了图标，真组件（`basic.Switch` / `basic.Slider` /
 * `utils.overScrollVertical`）一个没用；MD3 侧接了 `MaterialExpressiveTheme` 但组件层
 * 零 Expressive，开关是 `Box + drawRect` 手画的。
 *
 * 这一层把「长什么样」从调用点收进来：入口读现成的 `LauncherTheme.style.uiStyle`，
 * `when` 三分支转到 `apple/` `md3/` `miuix/`。**不新增 CompositionLocal** ——
 * `LocalLauncherStyle` 已经在传 `uiStyle`。
 *
 * 分发落在**组件级而不是整屏**：整屏 fork 只适合屏很小的项目，Flue 的商店 / 编辑器
 * 各五六千行，fork 三份等于三份都要维护。
 *
 * ## 本阶段（A1）的边界
 *
 * 三套实现现在**各自转发到改动前那份外观**，所以行为一个像素不变、零调用点改动。
 * 真正换成库组件（miuix `basic.Switch`、`material3.Switch`）、加震动、Apple 档照
 * Kyant 重做，是 A3 的事；那时只改这一层的实现文件，业务侧不用再动。
 */

/** 完整交互版开关：自带手势与（A3 起）触觉，用于绝大多数设置行。 */
@Composable
fun FlueSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (LauncherTheme.style.uiStyle) {
        UiStyle.MIUIX -> MiuixStyleSwitch(checked, onCheckedChange, modifier, enabled)
        UiStyle.MATERIAL_3 -> Md3Switch(checked, onCheckedChange, modifier, enabled)
        UiStyle.APPLE_WATCH -> AppleSwitch(checked, onCheckedChange, modifier, enabled)
    }
}

/**
 * 只绘制、**不收手势**的开关。
 *
 * 交互由外层整行的 `clickable` 承担。存在的理由是一条已经修过的性能问题：隐藏应用页
 * 几乎每个 fling 帧都会新组合一行，所以那里刻意压成「一个 clickable 节点 + 一个小动画层」，
 * 而不是 `Card → Row → 各自可点的 Switch`。库的真 `Switch` 自带 interactionSource 与
 * 手势链，直接塞进去等于推翻那个修复，而且子节点先拿到指针事件会抢掉整行点击。
 *
 * 迁移时逐点判定归属：默认用 [FlueSwitch]，原本就是「整行可点 + 开关纯展示」的用这个。
 */
@Composable
fun FlueSwitchIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressed: Boolean = false,
) {
    when (LauncherTheme.style.uiStyle) {
        UiStyle.MIUIX -> MiuixStyleSwitchIndicator(checked, modifier, enabled, pressed)
        UiStyle.MATERIAL_3 -> Md3SwitchIndicator(checked, modifier, enabled)
        UiStyle.APPLE_WATCH -> AppleSwitchIndicator(checked, modifier, enabled)
    }
}

@Composable
fun FlueSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    when (LauncherTheme.style.uiStyle) {
        UiStyle.MIUIX -> MiuixStyleSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
        UiStyle.MATERIAL_3 -> Md3Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
        UiStyle.APPLE_WATCH -> AppleSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

/**
 * Store/editor forms use one input entry point so borders, cursor and text
 * tokens do not drift between login, profile and upload overlays.
 *
 * The field keeps Material's semantics and keyboard behavior; the surface
 * colors come from the active launcher style. This is intentionally a small
 * adapter, so pages can migrate incrementally without changing their state
 * or validation logic.
 */
@Composable
fun FlueTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val style = LauncherTheme.style
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        label = label?.let { { Text(it) } },
        shape = style.rowShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = style.accentColor,
            unfocusedBorderColor = style.outlineColor,
            focusedContainerColor = style.screenBackground,
            unfocusedContainerColor = style.screenBackground,
            cursorColor = style.accentColor,
            focusedTextColor = style.bodyColor,
            unfocusedTextColor = style.bodyColor,
        ),
    )
}
