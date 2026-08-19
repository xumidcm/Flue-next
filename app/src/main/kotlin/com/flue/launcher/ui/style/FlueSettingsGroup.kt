package com.flue.launcher.ui.style

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.common.FLUE_ITEM_FADE_IN
import com.flue.launcher.ui.common.FLUE_ITEM_FADE_OUT
import com.flue.launcher.ui.common.flueContentSizeSpring
import com.flue.launcher.ui.common.fluePlacementSpring
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.theme.LauncherStyle
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.RoundElementRole
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.roundAwareCardShape

/**
 * 「同类行合并成一张卡」的成组支持。
 *
 * ## 老师报的问题
 *
 * HyperOS 的设置页是**同一分类的行合并成一张大圆角卡**、行间细分隔线。项目里
 * `MiuixSettingsCard` 本身就是正确的成组容器（大圆角 + `MiuixCardDivider` + 12dp 组间距），
 * 但它只被 4 个行容器用到，而那 4 个容器**各自只包一行** —— 结果该合并的同类行全散成了
 * 独立卡片。
 *
 * ## 为什么用「按位取形」而不是真的把多行塞进一个容器
 *
 * 设置页是 `LazyColumn`，每行一个 `item`，而且每行都要单独吃鱼眼缩放
 * （`itemFisheye(listState, key, …)` 按 key 算）。把 n 行塞进一个 `item` 会让整组一起缩放、
 * 一起重组，鱼眼与回收都废掉。
 *
 * 所以保留逐行 `item`，只让每行**知道自己在组里的位置**（[FlueGroupPosition]），
 * 首行圆上两角、末行圆下两角、中间四角全直、组内非末行底部画分隔线。视觉上就是一张卡。
 *
 * APPLE 与 MD3 档、以及**任何风格下的圆屏**，一律按 [FlueGroupPosition.Single] 处理 ——
 * 它们本来就是独立卡 / 独立胶囊，行为不变。
 *
 * ## 间距归属
 *
 * 合并要求组内行之间**一点缝都不能有**，而 `LazyColumn` 的 `verticalArrangement` 是整表
 * 统一的，给不出「这两行贴住、那两行留 12dp」。所以间距改由每一项自己出：
 *
 * - 未成组的行（另两档风格，或组里只有一行）→ [LocalFlueSettingsRowSpacing]，也就是原来
 *   列表统一给的那个值，观感与改动前一致；
 * - 组内非末行 → 0；
 * - 组内末行 → [FlueGroupSpacing]（12dp），组与组之间的空隙。
 *
 * 成组的行由 [flueSettingsGroup] 按位置出，没迁移的 `item {}`（分区标题、表盘卡、各种自定义
 * 内容，占大多数）由 [FlueSettingsListScope] 统一包一层，两边调用点都不用改。
 */

/** 未成组行的行距。由设置页 scaffold 按屏形提供（圆屏 6dp / 方屏 10dp）。 */
val LocalFlueSettingsRowSpacing = staticCompositionLocalOf { 10.dp }

/** 组与组之间的标准空隙。与 `MiuixGroupSpacing` 同值，逐步收敛到这一处。 */
val FlueGroupSpacing: Dp = 12.dp

/**
 * 设置页列表作用域：替 `verticalArrangement` 出行距。
 *
 * `verticalArrangement` 一旦归零，那 150 多个没迁移的 `item {}` 就会全贴在一起。与其逐个
 * 去补内边距（改 150 处、且以后新增的行容易漏），不如在**唯一的出口**这里包一层 —— 于是
 * 「行距」这件事从「列表的属性」变成「行的属性」，成组的行才有机会说「我不要」。
 *
 * [unspacedItem] 就是那个「我不要」：由 [flueSettingsGroup] 用，间距交给行自己按位置出。
 */
class FlueSettingsListScope internal constructor(
    private val delegate: LazyListScope,
    private val spacing: Dp,
) : LazyListScope {

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        val bottom = spacing
        delegate.item(key, contentType) {
            Box(modifier = flueSettingsRowMotion().padding(bottom = bottom)) { content() }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val bottom = spacing
        delegate.items(count, key, contentType) { index ->
            Box(modifier = flueSettingsRowMotion().padding(bottom = bottom)) {
                itemContent(index)
            }
        }
    }

    @androidx.compose.foundation.ExperimentalFoundationApi
    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        val bottom = spacing
        delegate.stickyHeader(key, contentType) { index ->
            // 吸顶项刻意**不挂 animateItem**：它的位置由吸顶逻辑逐帧改写，
            // 再叠一层位移动画会和吸顶打架（表现为标题黏在半路）。
            Box(
                modifier = Modifier
                    .animateContentSize(flueContentSizeSpring)
                    .padding(bottom = bottom),
            ) {
                content(index)
            }
        }
    }

    /** 不包间距的一项：行自己按 [FlueGroupPosition] 出下边距。 */
    internal fun unspacedItem(key: Any, content: @Composable LazyItemScope.() -> Unit) {
        delegate.item(key = key, content = content)
    }
}

/**
 * 设置行的避让动效：**自己长高平滑 + 下面的行跟着平滑挪**。
 *
 * 挂在 [FlueSettingsListScope] 与 [flueSettingsGroup] 这两个唯一出口上，所以一处生效于
 * **全部风格、全部设置页**（老师的要求）—— 200 多个调用点一个都不用改。
 *
 * 两条 modifier 缺一不可：`animateContentSize` 管「这一格自己从 48dp 长到 96dp」，
 * `animateItem` 管「下面那些行跟着往下挪」。只有前者时下面的行仍然瞬移，只有后者时
 * 长高那一格自己是跳变。取值见
 * [com.flue.launcher.ui.common.flueContentSizeSpring] 的注释。
 *
 * 顺序有讲究：`animateItem` 在外（作用于整项的**放置**），`animateContentSize` 在内
 * （改的是这一项的**测量结果**）。反过来写会让位移动画读到正在动画中的尺寸。
 *
 * 与圆屏弦宽缩放的关系：`animateItem` 改的是 `LazyListItemInfo.offset`（布局值），
 * 弦宽缩放正是读它算的，所以行下移时缩放会跟着连续变化 —— 这是想要的效果，
 * 也不违反「不得从变换后的边界反推 scale」那条铁律（graphicsLayer 的结果不回流到布局）。
 */
@Composable
private fun LazyItemScope.flueSettingsRowMotion(): Modifier = Modifier
    .animateItem(
        fadeInSpec = FLUE_ITEM_FADE_IN,
        placementSpec = fluePlacementSpring,
        fadeOutSpec = FLUE_ITEM_FADE_OUT,
    )
    .animateContentSize(flueContentSizeSpring)

/** [flueSettingsGroup] 的收集作用域。收 lambda 用普通 list，非 composable，零成本。 */
class FlueSettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<Pair<Any, @Composable (FlueGroupPosition) -> Unit>>()

    /**
     * 声明组内一行。
     *
     * @param key 与原来 `item(key)` 用的 key 完全一致 —— 迁移时不要改它，否则会丢滚动位置
     *   与动画身份，鱼眼的 per-key 缩放也会错位。
     */
    fun row(key: Any, content: @Composable (FlueGroupPosition) -> Unit) {
        rows += key to content
    }
}

/**
 * 把连续几行声明成一组。
 *
 * 调用点的改动只是把原来连续的几个 `item("key") { … }` 包进来、改成 `row("key") { pos -> … }`，
 * key 与 fisheye 参数都不动。
 *
 * **是否真的合并在行内部判定**（[flueGroupingEnabled]），不在这里 —— 这个函数跑在
 * `LazyListScope` 里，不是 composable，读不到风格。好处是调用点不用逐个把「当前是不是
 * MIUIX 档」传进来，切换风格也不必重建列表结构。
 */
fun LazyListScope.flueSettingsGroup(builder: FlueSettingsGroupScope.() -> Unit) {
    val scope = FlueSettingsGroupScope().apply(builder)
    val total = scope.rows.size
    scope.rows.forEachIndexed { index, (key, content) ->
        val emit: @Composable LazyItemScope.() -> Unit = {
            val position = flueGroupPosition(index, total)
            // 间距在这里出，不在行容器里出：同一个容器既被组内的 row 用、也被没迁移的
            // 普通 item 用，只看 position 分不出「我的间距有没有人给过」。
            Box(
                modifier = flueSettingsRowMotion()
                    .padding(bottom = flueGroupBottomSpacing(position)),
            ) {
                content(position)
            }
        }
        // 走 unspacedItem：组内行的间距由上面那层按位置出。拿不到设置页作用域时（搜索索引
        // 用的 SettingsKeyIndexRecorder）退回普通 item，只为登记 key，不参与绘制。
        if (this is FlueSettingsListScope) unspacedItem(key, emit) else item(key = key, content = emit)
    }
}

/** 这一行在组里的位置。不合并的风格恒为 [FlueGroupPosition.Single]。 */
@Composable
@ReadOnlyComposable
fun flueGroupPosition(index: Int, count: Int): FlueGroupPosition =
    if (flueGroupingEnabled()) flueGroupPositionAt(index, count) else FlueGroupPosition.Single

/**
 * 这一行该留多少下边距。
 *
 * [FlueGroupPosition.Single] 取的就是列表原本统一给的那个值，所以另两档风格、以及组里只有
 * 一行的情形，观感与改动前一致。
 *
 * 施加位置在行的**缩放层之外**：鱼眼缩放只改绘制、不改槽位高度，行距同理不该跟着缩放走，
 * 否则外圈行的间距会一起缩、看起来像挤在一起。
 */
fun flueGroupBottomSpacing(position: FlueGroupPosition, rowSpacing: Dp): Dp = when (position) {
    FlueGroupPosition.Single -> rowSpacing
    FlueGroupPosition.Last -> FlueGroupSpacing
    FlueGroupPosition.First, FlueGroupPosition.Middle -> 0.dp
}

@Composable
@ReadOnlyComposable
fun flueGroupBottomSpacing(position: FlueGroupPosition): Dp =
    flueGroupBottomSpacing(position, LocalFlueSettingsRowSpacing.current)

/**
 * 按位取形：只圆该圆的那两个角。
 *
 * 半径与 [FlueCapsuleGeometry.capsuleRadiusPx] 同一套夹取规则（半高 / 风格上限 / 半宽），
 * MIUIX 得到 22dp 的 HyperOS 大圆角。
 */
internal data class FlueGroupShape(
    private val position: FlueGroupPosition,
    private val maxRadiusPx: Float?,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = FlueCapsuleGeometry.capsuleRadiusPx(
            rowHeightPx = size.height,
            widthPx = size.width,
            maxRadiusPx = maxRadiusPx,
        )
        val top = if (position.roundsTop) radius else 0f
        val bottom = if (position.roundsBottom) radius else 0f
        return Outline.Rounded(
            RoundRect(
                rect = size.toRect(),
                topLeft = CornerRadius(top),
                topRight = CornerRadius(top),
                bottomRight = CornerRadius(bottom),
                bottomLeft = CornerRadius(bottom),
            ),
        )
    }
}

/**
 * 该行卡片背板的形状。
 *
 * [FlueGroupPosition.Single] 走现成的 [roundAwareCardShape]，与改动前逐位相同 ——
 * 未迁移的调用点行为完全不变。
 */
@Composable
@ReadOnlyComposable
fun LauncherStyle.flueGroupShape(
    position: FlueGroupPosition,
    role: RoundElementRole = RoundElementRole.CAPSULE,
): Shape {
    if (position == FlueGroupPosition.Single) return roundAwareCardShape(role)
    // 走到这里必然是方屏 MIUIX（见 flueGroupingEnabled），所以不用再判圆屏：
    // 直接取风格上限，没有上限时给一个方屏兜底圆角。
    val maxRadiusPx = capsuleMaxRadius?.let { radius ->
        with(androidx.compose.ui.platform.LocalDensity.current) { radius.toPx() }
    } ?: DEFAULT_GROUP_RADIUS_PX
    return FlueGroupShape(position = position, maxRadiusPx = maxRadiusPx)
}

/** 方屏且风格没给上限时的兜底圆角（18dp @ density 1 的量级，实际按 density 换算）。 */
private const val DEFAULT_GROUP_RADIUS_PX = 48f

/** 组内非末行底部的细分隔线。左侧缩进与行内文字对齐。 */
@Composable
fun FlueGroupDivider(
    position: FlueGroupPosition,
    startIndent: Dp = 16.dp,
) {
    if (!position.needsDivider) return
    val style = LauncherTheme.style
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.6.dp)
            .background(style.outlineColor),
    )
}

/**
 * 只有 **方屏 MIUIX 档**做行合并。抽出来避免每个调用点各写一次判断。
 *
 * 圆屏刻意不合并（老师定）：圆屏每行都吃鱼眼弦宽缩放，相邻两行的缩放比例本就不同，
 * 合并成一张卡只会让左右边缘出现错茬，还不如各自独立胶囊 —— 那也是圆屏本来的观感。
 */
fun flueGroupingEnabled(uiStyle: UiStyle, isRoundScreen: Boolean): Boolean =
    uiStyle == UiStyle.MIUIX && !isRoundScreen

@Composable
@ReadOnlyComposable
fun flueGroupingEnabled(style: LauncherStyle = LauncherTheme.style): Boolean =
    flueGroupingEnabled(style.uiStyle, isFlueScreenRound())
