package com.flue.launcher.ui.style.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.LauncherTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator as MiuixInfiniteProgressIndicator

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.flue.launcher.ui.style.flueLoadingIndicatorVisualSize
import com.flue.launcher.ui.theme.UiStyle

/** MIUIX loading uses the official Miuix orbiting-dot progress indicator. */
@Composable
internal fun MiuixStyleLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp,
    color: Color,
) {
    MiuixInfiniteProgressIndicator(
        modifier = modifier,
        color = color,
        size = size,
    )
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
