package com.flue.launcher.ui.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.theme.LauncherStyle
import com.flue.launcher.ui.theme.LauncherTheme
import kotlin.math.roundToInt

/**
 * 圆屏行内水平内边距。取代散在各处的写死表达式 `if (round) 22.dp else 16.dp`。
 *
 * 见 [FlueCapsuleGeometry] 顶部对「为什么写死值一定会溢出」的说明：基线要跟屏宽走
 * （官方 5.2%），弧深要跟行高走。
 *
 * ## 两个入口，按调用点能不能拿到高度来选
 *
 * - [flueRowHorizontalPadding]：**组合期**就要一个 `Dp`（绝大多数调用点是这样，它们把
 *   内边距直接交给 `Modifier.padding`）。行高按声明的 `rowHeight` 查表 —— 设置行的高度
 *   只有 2–3 档，查表足够准，而且零测量开销。
 * - [flueCapsuleInset]：行高**要等测量才知道**时用。在 `Modifier.layout` 里读
 *   `minIntrinsicHeight` 拿内容自然高度，单趟测量、不引入 `SubcomposeLayout`。
 */
@Composable
@ReadOnlyComposable
fun flueRowHorizontalPadding(
    style: LauncherStyle = LauncherTheme.style,
    /** 该行的名义高度。设置开关行 56dp、滑块行约 100dp、紧凑行 48dp。 */
    rowHeight: Dp = 56.dp,
    /** 行内内容的自然高度（文字块）。默认按行高的 0.62 估 —— 上下各留内边距。 */
    contentHeight: Dp = rowHeight * 0.62f,
    /**
     * 内容在行内占据的纵向带（距行顶的上沿 / 下沿）。给了它就忽略 [contentHeight]。
     *
     * **不居中的内容必须用这个**。滑块行的轨道在行的下部（高 24dp、底沿距行底 14dp），
     * 按「居中」去算会得出 0 弧深，而它真实位置正好压在下圆角的弧上 —— 老师看到的
     * 「滑轨顶到胶囊边缘」就是这么来的。
     */
    contentBand: Pair<Dp, Dp>? = null,
    /** 方屏基线，保持改动前的 16dp。 */
    squareMargin: Dp = 16.dp,
    /** 下限，防极窄屏算出贴边值。 */
    minimum: Dp = 12.dp,
): Dp {
    val round = isFlueScreenRound()
    if (!round) return squareMargin
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    with(density) {
        val screenWidthPx = screenWidthDp.dp.toPx()
        val radiusPx = FlueCapsuleGeometry.capsuleRadiusPx(
            rowHeightPx = rowHeight.toPx(),
            widthPx = screenWidthPx,
            maxRadiusPx = style.capsuleMaxRadius?.toPx(),
        )
        val paddingPx = FlueCapsuleGeometry.horizontalPaddingPx(
            round = true,
            screenWidthPx = screenWidthPx,
            radiusPx = radiusPx,
            rowHeightPx = rowHeight.toPx(),
            contentHeightPx = contentHeight.toPx(),
            squareMarginPx = squareMargin.toPx(),
            minimumPx = minimum.toPx(),
            contentBandPx = contentBand?.let { (top, bottom) -> top.toPx() to bottom.toPx() },
        )
        return paddingPx.toDp()
    }
}

/**
 * 按**实测**内容高度收窄约束的水平内边距。
 *
 * 用 `measurable.minIntrinsicHeight(width)` 取内容自然高度 —— 内在测量在这里只查一次，
 * 不消耗测量通道，仍然是单趟测量，不引入 `SubcomposeLayout`。
 *
 * **注意**：内在测量并非对所有子树都安全。miuix 的 `BasicComponent` 在自己的 measure
 * policy 里拿无界宽度做 `constraints.copy`，被内在测量一问就抛
 * 「Can't represent a width of 2147483555 and height of 0」（表盘市场三点菜单已经踩过
 * 一次）。所以**不要**把这个 modifier 套在包含 miuix 组件的子树上；那种地方用
 * [flueRowHorizontalPadding] 查表。
 */
@Composable
fun Modifier.flueCapsuleInset(
    style: LauncherStyle = LauncherTheme.style,
    squareMargin: Dp = 16.dp,
    minimum: Dp = 12.dp,
): Modifier {
    val round = isFlueScreenRound()
    val maxRadiusPx = style.capsuleMaxRadius?.let { radius ->
        with(LocalDensity.current) { radius.toPx() }
    }
    val squareMarginPx = with(LocalDensity.current) { squareMargin.toPx() }
    val minimumPx = with(LocalDensity.current) { minimum.toPx() }
    return this.layout { measurable, constraints ->
        val availableWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val contentHeight = measurable.minIntrinsicHeight(availableWidth).toFloat()
        val rowHeight = contentHeight
        val radiusPx = FlueCapsuleGeometry.capsuleRadiusPx(
            rowHeightPx = rowHeight,
            widthPx = availableWidth.toFloat(),
            maxRadiusPx = maxRadiusPx,
        )
        val paddingPx = FlueCapsuleGeometry.horizontalPaddingPx(
            round = round,
            screenWidthPx = availableWidth.toFloat(),
            radiusPx = radiusPx,
            rowHeightPx = rowHeight,
            contentHeightPx = contentHeight,
            squareMarginPx = squareMarginPx,
            minimumPx = minimumPx,
        ).roundToInt()
        val horizontal = (paddingPx * 2).coerceAtMost(
            (availableWidth - 1).coerceAtLeast(0),
        )
        val placeable = measurable.measure(constraints.offset(horizontal = -horizontal))
        layout(
            width = (placeable.width + horizontal).coerceAtMost(
                if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE,
            ),
            height = placeable.height,
        ) {
            placeable.place(horizontal / 2, 0)
        }
    }
}

private fun androidx.compose.ui.unit.Constraints.offset(horizontal: Int): androidx.compose.ui.unit.Constraints =
    androidx.compose.ui.unit.Constraints(
        minWidth = (minWidth + horizontal).coerceAtLeast(0),
        maxWidth = if (hasBoundedWidth) (maxWidth + horizontal).coerceAtLeast(0) else maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight,
    )
