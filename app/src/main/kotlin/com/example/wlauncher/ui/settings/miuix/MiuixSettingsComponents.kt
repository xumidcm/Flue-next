package com.flue.launcher.ui.settings.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideLauncherStyle
import com.flue.launcher.ui.theme.UiStyle

/**
 * MIUIX（HyperOS 视觉语言）设置组件。
 *
 * 全部颜色 / 圆角来自 [LauncherTheme.style] 令牌，深浅色自动适配；
 * 组件本身不依赖任何 MIUIX 三方库。
 */

/** MIUIX 拖影弹簧：dampingRatio 0.9 / stiffness 600。 */
private fun <T> miuixSpring() = spring<T>(dampingRatio = 0.9f, stiffness = 600f)

/**
 * 风格分发图标：MIUIX 风格用 HyperOS 线性图标（MiuixIcons），其余风格用
 * 调用方给的 Material 图标。tint 默认 accent（MIUIX 下即 #3482FF），不再
 * 让每个 ActionCard 各自硬编码 Material 实心图标 + 青色。
 */
@Composable
fun SettingsIcon(
    material: androidx.compose.ui.graphics.vector.ImageVector,
    miuix: androidx.compose.ui.graphics.vector.ImageVector? = null,
    contentDescription: String? = null,
    tint: Color = LauncherTheme.style.accentColor,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    androidx.compose.material3.Icon(
        imageVector = if (style.uiStyle == UiStyle.MIUIX && miuix != null) miuix else material,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}

/**
 * 组容器：大圆角卡片，可选组标题（左对齐加粗小号灰字）。
 * 组内行之间用 [MiuixCardDivider] 画细分隔线；组与组之间留 12dp 空隙
 * （由调用方通过 `Arrangement.spacedBy(12.dp)` 或 [MiuixGroupSpacing] 控制）。
 */
@Composable
fun MiuixSettingsCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val style = LauncherTheme.style
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                color = style.secondaryTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(style.cardShape)
                .background(style.cardColor),
            content = content
        )
    }
}

/** 组与组之间的标准空隙。 */
val MiuixGroupSpacing: Dp = 12.dp

/** 卡片内部行间细分隔线（左侧与行内文字对齐留白）。 */
@Composable
fun MiuixCardDivider(startIndent: Dp = 16.dp) {
    val style = LauncherTheme.style
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.6.dp)
            .background(style.outlineColor)
    )
}

/**
 * 设置行：标题 15sp + 可选 subtitle 12sp 灰色，左侧可选 leading slot
 * （应用图标等），右侧任意 slot。行高不低于 56dp。
 */
@Composable
fun MiuixSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val style = LauncherTheme.style
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = animateColorAsState(
        if (pressed && onClick != null) style.pressedCardColor else Color.Transparent,
        label = "miuix_row_bg"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            // Observe the animation in draw rather than composition: beginning a scroll
            // cancels the pressed interaction, and the old background modifier otherwise
            // recomposed the whole row for every color-animation frame.
            .drawBehind { drawRect(background.value) }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (leading != null) {
            Box(modifier = Modifier.padding(end = 12.dp)) { leading() }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                color = if (enabled) style.titleColor else style.secondaryTextColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = style.secondaryTextColor,
                    fontSize = 12.sp
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 胶囊开关：44x26dp 视觉（HyperOS 实机尺寸；旧 26x15 在手表上小到难点），
 * 拖影动画 spring(0.9, 600)，按压时滑块横向拉伸模拟 HyperOS 的「拖影」。
 * 点按经系统触觉 ToggleOn/ToggleOff 给确认震动。
 */
@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackWidth: Dp = 44.dp,
    trackHeight: Dp = 26.dp
) {
    val style = LauncherTheme.style
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    val thumbPadding = 2.5.dp
    val thumbSize = trackHeight - thumbPadding * 2
    val thumbProgress = animateFloatAsState(
        if (checked) 1f else 0f,
        animationSpec = miuixSpring(),
        label = "miuix_switch_thumb_progress"
    )
    // 拖影只缩放绘制层，不再每帧改变 thumb 宽度并重新测量整行。
    val thumbStretch = animateFloatAsState(
        if (pressed && enabled) 1.18f else 1f,
        animationSpec = miuixSpring(),
        label = "miuix_switch_thumb_stretch"
    )
    val trackColor = animateColorAsState(
        when {
            !enabled -> style.secondaryTextColor.copy(alpha = 0.20f)
            checked -> style.accentColor
            else -> style.secondaryTextColor.copy(alpha = 0.36f)
        },
        label = "miuix_switch_track"
    )
    // 滑块取「最亮的表面令牌」：浅色 = 白卡片，深色 = 近白标题色
    val thumbColor = remember(style) {
        if (style.cardColor.luminance() >= style.titleColor.luminance()) style.cardColor else style.titleColor
    }

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(999.dp))
            .drawBehind { drawRect(trackColor.value) }
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = {
                            haptics.performHapticFeedback(
                                if (checked) {
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff
                                } else {
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
                                },
                            )
                            onCheckedChange(!checked)
                        }
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbPadding)
                .size(thumbSize)
                .graphicsLayer {
                    translationX = (trackWidth - thumbSize - thumbPadding * 2).toPx() *
                        thumbProgress.value
                    scaleX = thumbStretch.value
                    transformOrigin = TransformOrigin(if (checked) 1f else 0f, 0.5f)
                }
                .clip(RoundedCornerShape(999.dp))
                .background(thumbColor)
        )
    }
}

/**
 * HyperOS 风格滑块：24dp 高的粗胶囊轨道、无 thumb，填充即值。
 *
 * 骨架用官方 M3 [androidx.compose.material3.Slider]（track/thumb slot 换皮）
 * 而不是手搓 pointerInput：steps 吸附、无障碍语义、RTL、键盘/表冠支持
 * 全部白拿——「不要自己搓、用预设」。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    trackHeight: Dp = 24.dp,
    steps: Int = 0
) {
    val style = LauncherTheme.style
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val fillColor = animateColorAsState(
        when {
            !enabled -> style.secondaryTextColor.copy(alpha = 0.30f)
            pressed -> style.accentColor
            else -> style.accentColor.copy(alpha = 0.92f)
        },
        label = "miuix_slider_fill"
    )
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f

    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        // HyperOS 没有可见 thumb：填充即值。
        thumb = {},
        track = { sliderState ->
            val fraction = ((sliderState.value - valueRange.start) / span).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(999.dp))
                    .background(style.pressedCardColor),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .drawBehind { drawRect(fillColor.value) }
                )
            }
        }
    )
}

// ------------------------------------------------------------------ Preview

@Composable
private fun MiuixPreviewContent() {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier
            .background(style.screenBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(MiuixGroupSpacing)
    ) {
        var switchA by remember { mutableStateOf(true) }
        var switchB by remember { mutableStateOf(false) }
        var slider by remember { mutableStateOf(0.6f) }
        MiuixSettingsCard(title = "外观") {
            MiuixSettingsRow(
                title = "高级材质",
                subtitle = "建议 Android 12+ 设备开启",
                onClick = { switchA = !switchA },
                trailing = { MiuixSwitch(checked = switchA, onCheckedChange = { switchA = it }) }
            )
            MiuixCardDivider()
            MiuixSettingsRow(
                title = "边缘模糊",
                subtitle = "实验性",
                onClick = { switchB = !switchB },
                trailing = { MiuixSwitch(checked = switchB, onCheckedChange = { switchB = it }) }
            )
            MiuixCardDivider()
            MiuixSettingsRow(title = "全局 UI 大小", subtitle = "${(slider * 100).toInt()}%")
            Box(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
                MiuixSlider(value = slider, onValueChange = { slider = it })
            }
        }
        MiuixSettingsCard(title = "系统") {
            MiuixSettingsRow(title = "导出日志", subtitle = "导出诊断信息", onClick = {})
            MiuixCardDivider()
            MiuixSettingsRow(title = "关于", subtitle = "Flue Next 2.0.0 (20)", onClick = {})
        }
    }
}

@Preview(name = "MIUIX Dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun MiuixSettingsPreviewDark() {
    ProvideLauncherStyle(uiStyle = UiStyle.MIUIX, darkMode = true) {
        MiuixPreviewContent()
    }
}

@Preview(name = "MIUIX Light", showBackground = true, backgroundColor = 0xFFF7F7F7)
@Composable
private fun MiuixSettingsPreviewLight() {
    ProvideLauncherStyle(uiStyle = UiStyle.MIUIX, darkMode = false) {
        MiuixPreviewContent()
    }
}
