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
 * 圆角矩形电池图标：描边外壳 + 半透明填充 + 正极凸起，剩余电量数字
 * **半透明居中在图标内部**（老师指定的形态）。充电时数字让位给闪电。
 *
 * 旧实现的问题不允许回归：
 *  * 填充宽度永远不小于自身高度，低电量不是畸形尖角。
 *  * 闪电压在图标内部，不再顶出壳外被表框吃掉。
 *  * 不用 graphicsLayer 缩放渲染（文字会落在非整数栅格上发糊），尺寸由
 *    [width]/[height] 直接决定，数字随高度取字号。
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
            else -> style.titleColor.copy(alpha = 0.5f)
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
    val shellShape = RoundedCornerShape(height * 0.3f)
    val fillShape = RoundedCornerShape(height * 0.3f - 1.dp)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.size(width, height), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(1.2.dp, shellColor, shellShape)
            )
            val innerMax = width - 4.dp
            val innerHeight = height - 4.dp
            Box(
                Modifier
                    .padding(2.dp)
                    .fillMaxHeight()
                    // 填充宽度不小于自身高度：低电量永远是完整圆角块而不是尖角。
                    .width((innerMax * animLevel).coerceAtLeast(innerHeight))
                    .clip(fillShape)
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
            } else if (showLabel) {
                Text(
                    text = "$clamped",
                    fontSize = (height.value * 0.58f).sp,
                    lineHeight = (height.value * 0.58f).sp,
                    fontWeight = FontWeight.SemiBold,
                    // 半透明数字居中在图标内（半透明填充垫底，两种主题下都可读）。
                    color = style.titleColor.copy(alpha = 0.62f),
                    style = BatteryLabelStyle,
                    modifier = Modifier.align(Alignment.Center),
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
