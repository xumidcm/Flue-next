package com.flue.launcher.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.settings.miuix.MiuixIcons
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle
import kotlin.math.roundToInt

/**
 * 统一设置与 Agent 可折叠标题脚手架。
 *
 * 遵循设计规范：
 * 1. 标题接近顶部时，返回按钮和标题连续移动、缩小到左上角并固定；反向滚动沿同一路径展开；
 * 2. 同一组元素随滚动变换，避免大小标题切换闪烁；
 * 3. 紧凑态标题默认 18sp，返回按钮视觉缩至约 80%，点击区域至少 48dp；
 * 4. 标题和返回按钮始终可见；背景从顶部实色向下渐隐；
 * 5. 圆屏按安全区域与弦宽定位，避免被屏幕边缘裁切。
 */
@Composable
fun CollapsibleHeaderScaffold(
    title: String,
    onBack: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    isRound: Boolean = isFlueScreenRound(),
    collapseDistance: Dp = 72.dp,
    content: @Composable (headerPlaceholderHeight: Dp) -> Unit,
) {
    val style = LauncherTheme.style
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()

    val collapseDistancePx = with(density) { collapseDistance.toPx() }

    val collapseProgress by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(style.screenBackground)) {
        val topSafeInset = safeDrawing.calculateTopPadding()
        val horizontalSafeInset = if (isRound) 24.dp else 16.dp

        // 展开态大标题与紧凑态尺寸
        val largeTitleSp = if (isRound) 24f else 26f
        val compactTitleSp = 18f
        val titleScale = 1f + (largeTitleSp / compactTitleSp - 1f) * (1f - collapseProgress)

        // 展开态高度与占位高度：必须包含状态栏/屏幕安全区以及副标题的高度，防止与列表卡片重叠
        val expandedHeaderHeight = topSafeInset + (if (isRound) 76.dp else 68.dp) + (if (!subtitle.isNullOrBlank()) 26.dp else 0.dp)

        // 列表主体
        content(expandedHeaderHeight)

        // 顶部实色向下渐隐遮罩
        val gradientHeight = topSafeInset + if (!subtitle.isNullOrBlank()) 88.dp else 64.dp
        val gradientAlpha = (0.75f + 0.25f * collapseProgress).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(gradientHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            style.screenBackground.copy(alpha = gradientAlpha),
                            style.screenBackground.copy(alpha = gradientAlpha * 0.9f),
                            style.screenBackground.copy(alpha = gradientAlpha * 0.4f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // 返回按钮与标题的平滑插值移动与缩放
        val compactTop = topSafeInset + if (isRound) 4.dp else 2.dp
        val expandedTop = topSafeInset + if (isRound) 20.dp else 16.dp
        val currentTop = lerp(expandedTop, compactTop, collapseProgress)

        val backVisualScale = 1f - 0.20f * collapseProgress // 视觉缩至约 80%

        val backInteractionSource = remember { MutableInteractionSource() }
        val backPressed by backInteractionSource.collectIsPressedAsState()
        val backTouchScale by animateFloatAsState(
            targetValue = if (backPressed) 0.92f else 1f,
            animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
            label = "collapsible_back_scale",
        )

        // 圆屏：展开态与收起态都保持**整组水平居中**（返回键 + 标题作为一个整体
        // 居中），不再像方屏那样收进左上角。
        //
        // 为什么圆屏必须这样：圆屏左右两侧是弧线，左上角那块其实已经在可视弧外，
        // 收起时若把标题推到左上角，文字会被屏幕边缘切掉一截（老师报的「圆屏标题栏
        // 应该也是缩到中间而不是左上角」）。方形屏没有这个问题，所以两者走不同分支。
        //
        // 实现上整组居中由 Row 的 horizontalArrangement 负责，不需要再靠
        // translationX 去模拟「居中 → 左对齐」的位移；方屏那条位移逻辑保留原样。
        var headerInnerWidthPx by remember { mutableFloatStateOf(0f) }
        var headerTitleWidthPx by remember { mutableFloatStateOf(0f) }
        // 仅方屏使用：把标题从居中位置连续推到左对齐位置。
        val titleDriftPx = ((headerInnerWidthPx - headerTitleWidthPx) / 2f).coerceAtLeast(0f)
        val titleDrift = if (isRound) 0f else titleDriftPx * collapseProgress

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, with(density) { currentTop.toPx() }.roundToInt()) }
                .padding(start = horizontalSafeInset, end = horizontalSafeInset),
            // 圆屏：整组（返回键 + 标题）水平居中，展开/收起都不贴左。
            // 方屏：保持 Start，标题靠左侧，收起时继续往左上角收。
            horizontalArrangement = if (isRound) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回按钮：点击区域至少 48dp，内部圆形视觉缩小
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = backInteractionSource,
                        indication = null,
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            scaleX = backVisualScale * backTouchScale
                            scaleY = backVisualScale * backTouchScale
                        }
                        .clip(if (style.uiStyle == UiStyle.APPLE_WATCH) CircleShape else style.smallControlShape)
                        .background(style.topBarChipColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.common_back),
                        tint = style.titleColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.size(8.dp))

            // 标题与副标题（同一组元素随滚动变换，紧凑态固定 18sp）
            Column(
                modifier = Modifier
                    // 圆屏整组居中时不能再 weight(1f)：那会把 Column 拉满剩余宽度，
                    // 「返回键 + Column」的总宽就等于整行了，Arrangement.Center 也就
                    // 失去了居中对象。改成 wrapContent，让整组按内容宽度居中。
                    .then(if (isRound) Modifier else Modifier.weight(1f))
                    .onSizeChanged { headerInnerWidthPx = it.width.toFloat() }
                    // 副标题跟着标题一起走：两者是同一组元素，分开动会看出错位。
                    // 圆屏的 titleDrift 恒为 0，不会产生任何位移。
                    .graphicsLayer { translationX = -titleDrift },
                horizontalAlignment = if (isRound) {
                    Alignment.CenterHorizontally
                } else {
                    Alignment.Start
                },
            ) {
                Text(
                    text = title,
                    color = style.titleColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 圆屏展开态居中（此时位移为 0，Center 落在屏幕中线上）；
                    // 随着滚动，位移把文字连续推向左边，收缩到底正好贴上返回键。
                    textAlign = if (isRound) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier
                        // 不 fillMaxWidth：只有量到**文字自身**宽度，
                        // (inner - text)/2 才正好是「居中 → 左对齐」的位移量。
                        .onSizeChanged { headerTitleWidthPx = it.width.toFloat() }
                        .graphicsLayer {
                            scaleX = titleScale
                            scaleY = titleScale
                            // 展开态从中心缩放（贴合居中观感），收缩态改为从左边
                            // 缩放（贴合左上角紧凑观感），按进度插值。
                            transformOrigin = TransformOrigin(
                                if (isRound) 0.5f - 0.5f * collapseProgress else 0f,
                                0.5f,
                            )
                        },
                )
                if (!subtitle.isNullOrBlank() && collapseProgress < 0.6f) {
                    val subAlpha = (1f - collapseProgress / 0.6f).coerceIn(0f, 1f)
                    Text(
                        text = subtitle,
                        color = style.secondaryTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = subAlpha },
                    )
                }
            }
        }
    }
}
