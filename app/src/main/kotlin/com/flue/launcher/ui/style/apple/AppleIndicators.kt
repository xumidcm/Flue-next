package com.flue.launcher.ui.style.apple

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Apple Watch 档加载指示器：2dp 细描边、低透明度底环 + 定长亮弧匀速旋转。
 *
 * 这就是项目里那份手搓实现（原名 `MiuixLoadingIndicator`，被全局统一用掉 24 处）。
 * 它的形本来就是 watchOS 的读法 —— 细环、匀速、弧长恒定，不像 M3 那样弧长弹性伸缩。
 * 所以 A4 把它**搬到 Apple 档**而不是删掉；MIUIX 与 MD3 各自换回自己库里的真组件。
 */
@Composable
internal fun AppleLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = Color.White,
    strokeWidth: Dp = 2.dp,
) {
    val transition = rememberInfiniteTransition(label = "apple_loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "apple_loading_rotation",
    )
    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = rotation,
            // 弧长恒定 —— 与 M3 那种弹性弧长的读法刻意不同。
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * Apple Watch 档下拉刷新指示器：**没有容器**，只有一段随下拉长出来的细弧。
 *
 * watchOS 本身没有「下拉转出一个圆盘」那种 chrome，所以这一档刻意不画卡片背板 ——
 * 与 MIUIX 的圆盘、MD3 的 surface 容器一眼能分辨，这正是分档的目的。
 */
@Composable
internal fun AppleRefreshIndicator(
    progress: Float,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = Color.White,
    strokeWidth: Dp = 2.dp,
) {
    if (refreshing) {
        AppleLoadingIndicator(modifier = modifier, size = size, color = color, strokeWidth = strokeWidth)
        return
    }
    val clamped = progress.coerceIn(0f, 1f)
    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        drawArc(
            color = color.copy(alpha = 0.25f + 0.75f * clamped),
            startAngle = -90f,
            // 拉满也只画 300 度，留一段缺口 —— 画满一圈会与「已经在转」混淆。
            sweepAngle = 300f * clamped,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke.width, this.size.height - stroke.width),
            style = stroke,
        )
    }
}
