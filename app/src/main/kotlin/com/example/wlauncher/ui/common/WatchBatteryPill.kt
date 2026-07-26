package com.flue.launcher.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.WatchColors

/**
 * 等宽数字 + 去掉字体内边距：电量 9% → 10% 时整行宽度不再跳动。
 */
private val BatteryLabelStyle = TextStyle(
    fontFeatureSettings = "tnum",
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * iOS / MIUIX 读法的电池胶囊：描边外壳 + 实心填充 + 正极凸起，数字在壳外。
 *
 * 旧实现的三个问题这里都不允许回归：
 *  * 外壳曾是不透明色块（充电时整块变墨绿），贴纯黑背景上边界靠猜 ——
 *    现在只描边，填充承载全部状态色。
 *  * 填充曾是矩形按比例裁剪，低于 20% 时被裁成畸形尖角 —— 现在填充宽度
 *    永远不小于自身高度，低电量是一颗完整的小胶囊。
 *  * 闪电曾 `offset(x = 10.dp)` 顶出壳外（圆屏上被表框吃掉）—— 现在压在
 *    填充内部用深色 tint。
 *
 * 尺寸通过 [width]/[height] 直接传入。旧的 `sizeScale`（graphicsLayer 缩放）
 * 已删除：缩放渲染会让 11sp 文字落在非整数栅格上发糊。
 */
@Composable
fun WatchBatteryPill(
    level: Int,
    charging: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = 30.dp,
    height: Dp = 15.dp,
    showLabel: Boolean = true,
) {
    val style = LauncherTheme.style
    val clamped = level.coerceIn(0, 100)
    val fill by animateColorAsState(
        targetValue = when {
            charging -> WatchColors.ActiveGreen
            clamped <= 10 -> WatchColors.ActiveRed
            clamped <= 20 -> WatchColors.ActiveOrange
            else -> style.titleColor
        },
        animationSpec = spring(dampingRatio = 0.92f, stiffness = 700f),
        label = "battery_fill_color",
    )
    val animLevel by animateFloatAsState(
        targetValue = clamped / 100f,
        // 无回弹：电量是测量值，不该弹跳。
        animationSpec = spring(dampingRatio = 1f, stiffness = 260f),
        label = "battery_fill_level",
    )
    val shellColor = style.titleColor.copy(alpha = 0.35f)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (showLabel) {
            Text(
                text = "$clamped",
                fontSize = 13.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                color = style.titleColor,
                style = BatteryLabelStyle,
            )
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.size(width, height), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(1.2.dp, shellColor, CircleShape)
            )
            val innerMax = width - 4.dp
            val innerHeight = height - 4.dp
            Box(
                Modifier
                    .padding(2.dp)
                    .fillMaxHeight()
                    // 填充宽度不小于自身高度：低电量永远是完整胶囊而不是尖角。
                    .width((innerMax * animLevel).coerceAtLeast(innerHeight))
                    .clip(CircleShape)
                    .background(fill)
            )
            if (charging) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(innerHeight),
                )
            }
        }
        // 正极凸起
        Spacer(Modifier.width(1.5.dp))
        Box(
            Modifier
                .size(2.dp, height * 0.38f)
                .clip(RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp))
                .background(shellColor)
        )
    }
}
