package com.flue.launcher.ui.settings.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 图标按风格取值：MIUIX 风格下用 [MiuixIcons] 里的线性图标，其它风格保持
 * 原来的 Material 图标。所有设置页图标使用点都必须走这里，不要再直接写
 * `Icons.Filled.*`，否则 MIUIX 风格下就会漏掉一个 Material 实心图标。
 */
@Composable
fun settingsIcon(material: ImageVector, miuix: ImageVector): ImageVector =
    if (LauncherTheme.style.uiStyle == UiStyle.MIUIX) miuix else material

/**
 * 设置页统一图标：自动按风格换资产、按风格换默认尺寸。
 * MIUIX 是 1.5 线宽的线性图标，画到 22dp 时的视觉重量刚好对得上
 * Material 24dp 的实心图标；[size] 传值可覆盖。
 */
@Composable
fun SettingsIcon(
    material: ImageVector,
    miuix: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    contentDescription: String? = null,
) {
    val isMiuix = LauncherTheme.style.uiStyle == UiStyle.MIUIX
    Icon(
        imageVector = if (isMiuix) miuix else material,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size ?: (if (isMiuix) 22.dp else 24.dp)),
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
    val background by animateColorAsState(
        if (pressed && onClick != null) style.pressedCardColor else Color.Transparent,
        label = "miuix_row_bg"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .background(background)
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
 * 胶囊开关：44x26dp 视觉（HyperOS 实际比例），滑块 22dp，
 * 拖影动画 spring(0.9, 600)。按压时滑块横向拉伸，模拟 HyperOS 的「拖影」质感。
 *
 * 注意：这里**故意不做任何触感反馈** —— 不调 `performHapticFeedback`，
 * `clickable` 也传 `indication = null`，点开关不应该震手表。
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

    val thumbPadding = 2.dp
    val thumbSize = trackHeight - thumbPadding * 2
    // 拖影：按压时滑块拉长
    val thumbWidth by animateDpAsState(
        if (pressed && enabled) thumbSize + 5.dp else thumbSize,
        animationSpec = miuixSpring(),
        label = "miuix_switch_thumb_width"
    )
    val thumbOffset by animateDpAsState(
        if (checked) trackWidth - thumbPadding - thumbWidth else thumbPadding,
        animationSpec = miuixSpring(),
        label = "miuix_switch_thumb_offset"
    )
    val trackColor by animateColorAsState(
        when {
            !enabled && checked -> style.accentColor.copy(alpha = 0.38f)
            !enabled -> style.switchTrackOffColor.copy(alpha = style.switchTrackOffColor.alpha * 0.6f)
            checked -> style.accentColor
            else -> style.switchTrackOffColor
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
            .background(trackColor)
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = { onCheckedChange(!checked) }
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .width(thumbWidth)
                .height(thumbSize)
                .clip(RoundedCornerShape(999.dp))
                .background(thumbColor)
        )
    }
}

/**
 * HyperOS 风格滑块：24dp 高的粗胶囊轨道、无独立 thumb，填充即值。
 *
 * 实现上**直接用 Material3 的 [Slider]**，只通过 `track` / `thumb` 两个 slot
 * 换掉外观 —— 拖动手感、点按定位、steps 吸附、无障碍语义、RTL 全部由 M3 保证，
 * 这里不再手写 `pointerInput`（之前那版自搓的没有 steps 也没有语义）。
 * `thumb` 传空 composable，于是轨道能占满整宽。
 *
 * 带 `track` / `thumb` slot 的那个 [Slider] 重载在 material3 1.4.0 上仍标着
 * `@ExperimentalMaterial3Api`，所以这里必须显式 OptIn。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    trackHeight: Dp = 24.dp
) {
    val style = LauncherTheme.style
    val interactionSource = remember { MutableInteractionSource() }
    val dragged by interactionSource.collectIsDraggedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val active = dragged || pressed
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val fillColor by animateColorAsState(
        when {
            !enabled -> style.secondaryTextColor.copy(alpha = 0.30f)
            active -> style.accentColor
            else -> style.accentColor.copy(alpha = 0.92f)
        },
        label = "miuix_slider_fill"
    )
    val trackShape = RoundedCornerShape(999.dp)

    Slider(
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
        // HyperOS 的滑块没有可见把手，填充边缘就是把手
        thumb = { },
        track = { _ ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(trackShape)
                    .background(
                        if (enabled) style.switchTrackOffColor else style.switchTrackOffColor.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(trackShape)
                        .background(fillColor)
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
            MiuixSettingsRow(title = "关于", subtitle = "Flue beta1.5", onClick = {})
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
