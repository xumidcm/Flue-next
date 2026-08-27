package com.flue.launcher.ui.style

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.LauncherTheme

/**
 * 卡片默认描边。
 *
 * 在设置那种「一屏十几行、卡挨着卡」的地方它是必要的：不描边就分不清行边界。
 * 但在内容密度低、卡本身就有底色差的页面（表盘详情、创作者主页）它反而是噪音 ——
 * 深色档下 `outlineColor` 接近白色，一圈细白线把每张卡框起来，老师的原话是「好丑」。
 * 所以留成可关的参数，而不是两套卡片组件。
 */
@Composable
fun flueCardBorder(): BorderStroke =
    BorderStroke(1.dp, LauncherTheme.style.outlineColor.copy(alpha = 0.55f))

/** Shared surface entry points for store/editor/social pages. */
@Composable
fun FlueSurface(
    modifier: Modifier = Modifier,
    shape: Shape = LauncherTheme.style.cardShape,
    color: Color = LauncherTheme.style.cardColor,
    border: BorderStroke? = flueCardBorder(),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        contentColor = LauncherTheme.style.bodyColor,
        border = border,
        content = content,
    )
}

@Composable
fun FlueCard(
    modifier: Modifier = Modifier,
    border: BorderStroke? = flueCardBorder(),
    content: @Composable () -> Unit,
) = FlueSurface(modifier = modifier, border = border, content = content)

@Composable
fun FlueSettingsRow(
    modifier: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    // 默认值保持 flueCardBorder()，所有现有调用点行为不变；只有确实不想要那圈
    // 白线的地方（我的资料页那些纯文字行）传 null。
    border: BorderStroke? = flueCardBorder(),
    content: @Composable RowScope.() -> Unit,
) {
    FlueSurface(modifier = modifier.fillMaxWidth(), border = border) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

@Composable
fun FlueGroupHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        color = LauncherTheme.style.secondaryTextColor,
    )
}

@Composable
fun FlueButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = LauncherTheme.style.rowShape,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = LauncherTheme.style.accentColor,
            contentColor = if (LauncherTheme.style.accentColor.luminance() > 0.55f) {
                Color.Black
            } else {
                Color.White
            },
        ),
        content = content,
    )
}

@Composable
fun FlueOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = LauncherTheme.style.rowShape,
    /**
     * 描边色。默认是**按钮强度**的描边，不是卡片那条发丝线。
     *
     * 这两个不能共用一个令牌。`style.outlineColor` 是「卡片上的发丝分隔线」，MIUIX 档
     * 只有白 6%、AW 档 10% —— 画在卡边上刚好，画在按钮上等于没有边。把原生
     * `OutlinedButton` 换成这个组件时默认描边从 Material 的 `colorScheme.outline`
     * 掉到了那个发丝令牌，结果就是老师报的「上传到商店那几个按钮的白框怎么被去掉了」。
     *
     * 按钮的边不是装饰而是**可点边界**：它告诉用户这一块整体可以按。所以默认取
     * `colorScheme.outline`（也正是改动前那些 `OutlinedButton` 用的值，等于把观感原样
     * 恢复），需要更淡的地方由调用点自己传。
     */
    borderColor: Color = MaterialTheme.colorScheme.outline,
    /** 内容色。null 表示沿用 Material 的默认（跟随风格主色）。 */
    contentColor: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        border = BorderStroke(1.dp, borderColor),
        colors = if (contentColor != null) {
            ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
        content = content,
    )
}
