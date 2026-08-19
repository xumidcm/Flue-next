package com.flue.launcher.applist.background

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.common.normalizeOverlayMaskCornerRadiusDp

/**
 * 应用列表背景的**实时预览卡**。
 *
 * 老师报的是「应用列表背景应该可以预览现在背景图的效果」—— 原来这一页只有四行选项加两条
 * 滑块，调模糊和遮罩全靠盲猜，得退出设置、上滑打开列表才看得见。
 *
 * ## 为什么是「屏幕的缩微照片」而不是随便一块色卡
 *
 * 底图是 center-crop 铺满全屏的，**裁多少完全由屏幕宽高比决定**。预览卡如果用一个随手定的
 * 比例（比如 16:9），圆屏手表上看到的构图会和真机差一大截，那这个预览就在骗人。所以卡片：
 *
 * - 宽高比取当前屏幕的宽高比；
 * - 形状走 [isFlueScreenRound] / [normalizeOverlayMaskCornerRadiusDp]，圆屏就是个圆；
 * - 圆角半径按缩小倍率 k 一起缩 —— 40dp 的圆角画在三分之一大的卡上还是 40dp 的话，
 *   卡片会圆得像药丸。
 *
 * 同理，**模糊半径也要乘 k**（[Modifier.appListBackground] 的 `blurScale`）：把一张截图缩小
 * 三倍，它的模糊看起来也缩小三倍。不缩的话预览永远比真机糊得多，滑块的手感就是错的。
 * 30 及以下那条路不用管 —— 那边的模糊是烘焙进位图的，位图被画小了模糊自然跟着小。
 *
 * ## 为什么要画几个假图标
 *
 * 遮罩这条滑块的**唯一用途**就是「压暗底图让图标文字读得清」。卡里什么都不画的话，拖遮罩
 * 只是让方块变暗，看不出到底够不够 —— 有了图标底衬和文字条，够不够一眼就知道。
 * 假图标用的是应用列表真实的 `item` / `text` 两个颜色，不是另编一套灰。
 *
 * @param painting 已经组装好的背景描述。调用点自己 [rememberAppListBackgroundPainting]，
 *   这样拖滑块时的临时值（还没落盘）也能直接喂进来。
 * @param iconColor 应用图标底衬色（`AppListPalette.item`）。
 * @param labelColor 应用名文字色（`AppListPalette.text`）。
 * @param squareCornerRadiusDp 方屏的屏幕圆角设置值，与三个覆层共用同一个。
 */
@Composable
fun AppListBackgroundPreviewCard(
    painting: AppListBackgroundPainting,
    iconColor: Color,
    labelColor: Color,
    squareCornerRadiusDp: Int,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat().coerceAtLeast(1f)
    val screenHeightDp = configuration.screenHeightDp.toFloat().coerceAtLeast(1f)
    // 夹一下比例：分屏 / 折叠屏能报出很极端的值，卡片再忠实也不该把这一页撑成一条缝。
    val aspect = (screenWidthDp / screenHeightDp).coerceIn(MIN_ASPECT, MAX_ASPECT)
    val round = isFlueScreenRound()
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // 高度上限按屏幕高的一部分算，而不是写死 dp：小屏上卡片不该把选项全挤下去，
        // 大屏上也不该缩成一枚邮票。宽度由比例反推，所以永远不会溢出这一行。
        val maxCardHeight = (screenHeightDp * MAX_HEIGHT_FRACTION).dp
        val cardWidth = minOf(maxWidth, maxCardHeight * aspect)
        val cardHeight = cardWidth / aspect
        // 缩微倍率：真机屏幕 → 这张卡。模糊半径与屏幕圆角都按它缩。
        val scale = (cardWidth.value / screenWidthDp).coerceIn(0.05f, 1f)
        val shape: Shape = if (round) {
            CircleShape
        } else {
            RoundedCornerShape(
                (normalizeOverlayMaskCornerRadiusDp(squareCornerRadiusDp) * scale).dp,
            )
        }
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clip(shape)
                .appListBackground(painting, blurScale = scale)
                // 描边只是让「这是一块屏幕」读得出来；纯色档下没有它，深色卡片会和
                // 深色页面糊成一片，看不出边界在哪。
                .border(1.dp, labelColor.copy(alpha = OUTLINE_ALPHA), shape),
            contentAlignment = Alignment.Center,
        ) {
            MockAppRows(
                cardWidth = cardWidth,
                iconColor = iconColor,
                labelColor = labelColor,
            )
        }
    }
}

/**
 * 两行假图标（图标底衬 + 文字条）。
 *
 * 尺寸全按卡宽的比例算，卡片缩放时整体等比缩，不会出现「卡变小了图标还是那么大」。
 */
@Composable
private fun MockAppRows(
    cardWidth: Dp,
    iconColor: Color,
    labelColor: Color,
) {
    val iconSize = cardWidth * ICON_SIZE_FRACTION
    val gap = cardWidth * GAP_FRACTION
    val labelHeight = (cardWidth * LABEL_HEIGHT_FRACTION).coerceAtLeast(1.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(MOCK_ROWS) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.Top,
            ) {
                // 第二行少一个：圆屏上下窄，摆满三个会顶到弧边；错开也更像真实的蜂巢排布。
                val count = if (row == 0) MOCK_ROW_ICONS else MOCK_ROW_ICONS - 1
                repeat(count) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(labelHeight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(iconSize)
                                .background(iconColor, RoundedCornerShape(ICON_CORNER_PERCENT)),
                        )
                        Box(
                            modifier = Modifier
                                .width(iconSize * LABEL_WIDTH_FRACTION)
                                .height(labelHeight)
                                .background(
                                    labelColor.copy(alpha = LABEL_ALPHA),
                                    RoundedCornerShape(percent = 50),
                                ),
                        )
                    }
                }
            }
        }
    }
}

private const val MIN_ASPECT = 0.45f
private const val MAX_ASPECT = 2.2f

/** 卡片最多占屏幕高的 38%：下面还要留得下四行模式选项。 */
private const val MAX_HEIGHT_FRACTION = 0.38f

private const val MOCK_ROWS = 2
private const val MOCK_ROW_ICONS = 3
private const val ICON_SIZE_FRACTION = 0.17f
private const val GAP_FRACTION = 0.055f
private const val LABEL_HEIGHT_FRACTION = 0.018f
private const val LABEL_WIDTH_FRACTION = 0.86f
private const val LABEL_ALPHA = 0.72f
private const val ICON_CORNER_PERCENT = 30
private const val OUTLINE_ALPHA = 0.14f
