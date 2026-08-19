package com.flue.launcher.ui.style.miuix

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.LauncherTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.flue.launcher.ui.style.flueLoadingIndicatorVisualSize
import com.flue.launcher.ui.theme.UiStyle

/**
 * MIUIX 档加载指示器：自绘的单段旋转细弧。
 *
 * ## 为什么不再用 miuix 的 `InfiniteProgressIndicator`
 *
 * 上游那个组件在手表这种小尺寸下画出来是「一圈细环 + 正中一个实心点」，读起来像个
 * **靶心而不是转圈** —— 环是满圈的、看不出缺口，于是动没动都分不出来（老师报的
 * 「miuix 的转圈动画也很怪」，见上传到商店登录页那张截图）。它是按手机尺度设计的，
 * 缩到 13–17dp 之后内部的缺口与描边比例就失效了。
 *
 * HyperOS 真机上的不定态转圈本身就是**一段短弧在转**，没有底环、没有中心点。所以这里
 * 自绘一份：与 [com.flue.launcher.ui.style.apple.AppleLoadingIndicator] 同一套画法，
 * 但按 HyperOS 的读法调参 —— 不画底环（Apple 档画 22% 的底环）、弧更短、转得更快。
 * 三档的差异仍然落在观感上，而不是「谁覆盖谁」。
 */
@Composable
internal fun MiuixStyleLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp,
    color: Color,
) {
    val transition = rememberInfiniteTransition(label = "miuix_loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            // 比 Apple 档（900ms）快一档：HyperOS 的转圈节奏更急。
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "miuix_loading_rotation",
    )
    Canvas(modifier = modifier.size(size)) {
        // 描边按边长取比例，不写死 dp：这个组件从 13dp（表单里）到 28dp（表盘加载页）
        // 都在用，固定描边在小尺寸下会把圆心糊住 —— 那正是上游组件看起来像靶心的原因。
        val stroke = (this.size.minDimension / 9f).coerceAtLeast(1.5f)
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(
            color = color,
            startAngle = rotation,
            // 短弧：太长就接近满圈，又会变回「看不出在转」。
            sweepAngle = 72f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * MIUIX 档下拉刷新指示器：卡片色圆盘 + HyperOS 线性刷新图标随进度旋转。
 *
 * 这是商店页原本那份观感，保留在 MIUIX 档；位移与手势仍由 `FluePullToRefreshState` 管，
 * 这里只负责「长什么样」。
 */
@Composable
internal fun MiuixStyleRefreshIndicator(
    progress: Float,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(style.cardColor),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            MiuixStyleLoadingIndicator(
                modifier = Modifier,
                size = flueLoadingIndicatorVisualSize(17.dp, UiStyle.MIUIX),
                color = style.secondaryTextColor,
            )
        } else {
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = null,
                tint = style.accentColor,
                modifier = Modifier
                    .size(17.dp)
                    .graphicsLayer { rotationZ = progress.coerceIn(0f, 1f) * 270f },
            )
        }
    }
}
