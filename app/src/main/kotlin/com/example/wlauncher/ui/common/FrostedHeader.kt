package com.flue.launcher.ui.common

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.anim.quantizeSystemBlurRadiusDp
import com.flue.launcher.ui.anim.systemBlurEffectForRadiusPx
import com.flue.launcher.ui.theme.LauncherTheme
import kotlin.math.roundToInt

/** 模糊强度走完全程所需的滚动量（对应设计稿的 48px 阈值，这里按 dp 换算）。 */
private const val FROSTED_HEADER_SCROLL_RANGE_DP = 48f

/** 滚动满程时的最大模糊半径；实际下发前会经 [quantizeSystemBlurRadiusDp] 分桶。 */
private const val FROSTED_HEADER_MAX_BLUR_DP = 18f

/** 低于该进度视为「还在顶部」，不做任何离屏合成，保持零开销。 */
private const val FROSTED_HEADER_MIN_PROGRESS = 0.01f

/** 背衬渐变条的最高不透明度（API < 31 的降级表现即为这条渐变）。 */
private const val FROSTED_HEADER_VEIL_MAX_ALPHA = 0.96f

/** 底部渐隐过渡带高度，用于消掉模糊区域的硬边。 */
private val FrostedHeaderFadeBand = 16.dp

/**
 * 顶部渐隐头（设计蓝本 §8）。
 *
 * 滚动量越大，头部区域的背衬越实，滚过去的内容在这一带被压下去；底部 16dp
 * 是渐隐过渡带，不留硬边。停在顶部时完全透明，原布局与手势不受影响。
 * [scrollProvider] 在绘制阶段读取，不触发重组。
 *
 * **关于「模糊」**：Compose 没有背景模糊（backdrop blur）。`graphicsLayer` 上的
 * [RenderEffect] 模糊的是**图层自身内容**而不是它背后的东西 —— 装在这里的
 * 效果是把头部自己的电量数字、时钟糊掉（内容为空的纯覆层则完全是空转），
 * 与需求正好相反。真正的区域背景模糊需要把滚动内容录进
 * `rememberGraphicsLayer()`，再把该图层裁剪+模糊后重绘到头部，代价是每帧
 * 多一次离屏合成；在手表 GPU 上要单独评估，且必须改动调用点结构。
 * 在那之前这里只做渐变背衬 —— 它是这套效果里真正起作用的部分。
 *
 * @param scrollProvider 0 = 顶部，>0 = 已滚动的像素量。
 * @param height 内容区高度；组件总高会再加 16dp 渐隐过渡带。
 */
@Composable
fun FrostedHeader(
    scrollProvider: () -> Float,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val veilColor = LauncherTheme.style.screenBackground
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height + FrostedHeaderFadeBand)
            .drawWithContent {
                val progress = frostedHeaderProgress(
                    scrollPx = scrollProvider(),
                    rangePx = FROSTED_HEADER_SCROLL_RANGE_DP.dp.toPx(),
                )
                if (progress >= FROSTED_HEADER_MIN_PROGRESS) {
                    val bandPx = FrostedHeaderFadeBand.toPx()
                    val solidEnd = (size.height - bandPx).coerceAtLeast(0f) / size.height
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to veilColor.copy(
                                alpha = veilColor.alpha * FROSTED_HEADER_VEIL_MAX_ALPHA * progress
                            ),
                            solidEnd to veilColor.copy(
                                alpha = veilColor.alpha * FROSTED_HEADER_VEIL_MAX_ALPHA * progress
                            ),
                            1f to veilColor.copy(alpha = 0f),
                        ),
                    )
                }
                drawContent()
            },
        content = content,
    )
}

private fun frostedHeaderProgress(scrollPx: Float, rangePx: Float): Float {
    if (rangePx <= 0f || !scrollPx.isFinite()) return 0f
    return (scrollPx / rangePx).coerceIn(0f, 1f)
}
