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
 * MIUIX（HyperOS）风格加载指示器。
 *
 * 与 Material3 的 [androidx.compose.material3.CircularProgressIndicator] 的区别：
 * - 细环：默认 2dp 线宽，不随尺寸变粗；
 * - 端点是平口（[StrokeCap.Butt]），不做圆角 —— HyperOS 的环是「切出来」的；
 * - 匀速旋转（[LinearEasing]），不做 Material 那种「头尾伸缩」的双段缓动；
 * - 颜色默认走 accent。
 *
 * 底环用 accent 的低透明度画一圈，避免深色底上只剩一段孤零零的弧。
 */
@Composable
fun MiuixLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    strokeWidth: Dp = 2.dp,
    color: Color = LauncherTheme.style.accentColor,
    trackColor: Color = color.copy(alpha = 0.16f),
    sweepAngle: Float = 96f,
    periodMillis: Int = 900,
) {
    val transition = rememberInfiniteTransition(label = "miuix_loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "miuix_loading_rotation",
    )
    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Butt)
        val inset = strokeWidth.toPx() / 2f
        val arcSize = Size(
            width = this.size.width - strokeWidth.toPx(),
            height = this.size.height - strokeWidth.toPx(),
        )
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = color,
            startAngle = rotation - 90f,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}
