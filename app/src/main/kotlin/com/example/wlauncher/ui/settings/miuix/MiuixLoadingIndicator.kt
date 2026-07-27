package com.flue.launcher.ui.settings.miuix

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
import com.flue.launcher.ui.theme.LauncherTheme

/**
 * HyperOS 式细环加载指示器：2dp 细描边、低透明度底环 + 定长亮弧匀速旋转。
 * 全局所有「转圈」（设置 / 商店 / 编辑器 / 文件管理器）统一用这一个，
 * 替换各处的 M3 CircularProgressIndicator（粗环 + 弹性弧长，与 HyperOS
 * 视觉不合）。
 */
@Composable
fun MiuixLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    strokeWidth: Dp = 2.dp,
    color: Color = LauncherTheme.style.accentColor,
) {
    val transition = rememberInfiniteTransition(label = "miuix_loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "miuix_loading_rotation",
    )
    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Butt)
        val inset = stroke.width / 2f
        val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        val topLeft = Offset(inset, inset)
        // 低透明度底环。
        drawArc(
            color = color.copy(alpha = 0.16f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        // 定长亮弧匀速旋转（HyperOS 是恒定弧长，不做 M3 的弧长弹性）。
        drawArc(
            color = color,
            startAngle = rotation - 90f,
            sweepAngle = 84f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
    }
}
