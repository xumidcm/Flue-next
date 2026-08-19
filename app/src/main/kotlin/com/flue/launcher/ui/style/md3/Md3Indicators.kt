package com.flue.launcher.ui.style.md3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.LauncherTheme

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.flue.launcher.ui.style.flueLoadingIndicatorVisualSize
import com.flue.launcher.ui.theme.UiStyle

/**
 * Material 3 档加载指示器：M3 1.5 的真 `LoadingIndicator`。
 *
 * 它用 `androidx.graphics.shapes.RoundedPolygon`（`graphics-shapes` 已是依赖）在
 * `MaterialShapes` 之间形变，是 Expressive 的招牌读法 —— 与 Apple 档的匀速细环、
 * MIUIX 档的 miuix 环三者一眼能分辨，这正是分档的目的。
 *
 * 仍标着 `@ExperimentalMaterial3ExpressiveApi`：material3 提到 1.5.0-alpha14 就是为了
 * 这一族组件，opt-in 是预期成本，不是意外。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Md3LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp,
    color: Color,
) {
    LoadingIndicator(
        modifier = modifier.size(size),
        color = color,
    )
}

/**
 * MD3 档下拉刷新指示器：M3 的 surface 容器 + Material 刷新图标，转起来时换成
 * `LoadingIndicator`（形变那一款）。
 *
 * 容器色取 `colorScheme.surfaceContainerHighest` 而不是我们的卡片令牌 —— 这一档的
 * 用意就是「跟着 Material 走」。
 */
@Composable
internal fun Md3RefreshIndicator(
    progress: Float,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            Md3LoadingIndicator(
                modifier = Modifier,
                size = flueLoadingIndicatorVisualSize(20.dp, UiStyle.MATERIAL_3),
                color = style.secondaryTextColor,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = style.accentColor,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = progress.coerceIn(0f, 1f) * 270f },
            )
        }
    }
}
