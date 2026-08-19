package com.flue.launcher.ui.store

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.common.FLUE_ITEM_FADE_IN
import com.flue.launcher.ui.common.FLUE_ITEM_FADE_OUT
import com.flue.launcher.ui.common.LocalChordScreenCenterY
import com.flue.launcher.ui.common.LocalChordViewportWidth
import com.flue.launcher.ui.common.chordScaleForItemGeometry
import com.flue.launcher.ui.common.chordScaleItem
import com.flue.launcher.ui.common.flueContentSizeSpring
import com.flue.launcher.ui.common.fluePlacementSpring
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.common.roundScrollablePadding
import com.flue.launcher.ui.style.flueOverscroll

/**
 * 市场**二级页**的列表容器：圆屏弦宽适配 + 避让动画，一处给全。
 *
 * ## 老师报的问题
 *
 * 「表盘市场二级页面未适配圆屏」。市场首页早就按弦宽逐行缩放了
 * （`RoundStoreChordContainer`），但「我的收藏 / 我的合集 / 我的上传 / 优秀创作者 /
 * 创作者主页 / 个人资料」这些二级页只有一圈静态内边距 —— 圆屏上左对齐的文字仍然
 * 被弧线切掉。
 *
 * ## 为什么做成容器而不是让每个页面各自接
 *
 * 弦宽缩放要三样东西：视口全宽、圆心 y、列表顶边在视口里的位置。让六个二级页各测
 * 一遍必然有人漏、也必然漂移出六种手感。这里把「测量 + 逐项施加」收进一个 drop-in
 * 替代品：调用点只是把 `LazyColumn { … }` 换成 `StoreRoundList { … }`，items 一行不改。
 *
 * 顺带把设置页那套避让动画也挂上（`animateItem` + `animateContentSize`），所以二级页
 * 里删一条收藏、行高变化时下面的行是平滑下移而不是瞬移。
 *
 * 三条铁律（见 `ChordScale.kt`）不破：测量只用 `layoutInfo` 的布局值，施加只走
 * `graphicsLayer`，且等比。
 */
@Composable
internal fun StoreRoundList(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: StoreRoundListScope.() -> Unit,
) {
    val roundScreen = isFlueScreenRound()
    var listTopInWindow by remember { mutableFloatStateOf(0f) }
    var rootTopInWindow by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootTopInWindow = it.positionInWindow().y },
    ) {
        val density = LocalDensity.current
        // 圆的直径取**视口全宽**，圆心 y 取视口垂直中点 —— 与首页 / 设置页同一套坐标系。
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val screenCenterY = viewportHeightPx / 2f
        val listState = rememberLazyListState()
        // 圆屏上首尾两项要能滚到圆心。没有这段留白，第一项最多停在视口顶部 ——
        // 那正好是弧线最窄的地方，读起来最费劲，而且「拉不到屏幕中间」（老师报的）。
        // 与设置页同一套算法（`roundScrollablePadding`），不另编一份。
        val roundPadding = remember(roundScreen, viewportHeightPx, density) {
            if (!roundScreen || viewportHeightPx <= 0f) {
                PaddingValues(0.dp)
            } else {
                val padding = roundScrollablePadding(
                    viewportHeight = viewportHeightPx,
                    contentTop = 0f,
                    itemHeight = with(density) { ESTIMATED_ROW_HEIGHT.toPx() },
                )
                with(density) {
                    PaddingValues(top = padding.top.toDp(), bottom = padding.bottom.toDp())
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // 橡皮筋回弹：列表容器一直缺这一层，二级页拉到底就是硬停
                //（老师报的「没有橡皮筋回弹」）。
                .flueOverscroll(listState)
                .onGloballyPositioned { listTopInWindow = it.positionInWindow().y },
            contentPadding = roundPadding,
            verticalArrangement = verticalArrangement,
        ) {
            StoreRoundListScope(
                delegate = this,
                listState = listState,
                enabled = roundScreen && viewportWidthPx > 0f,
                viewportWidth = viewportWidthPx,
                screenCenterY = screenCenterY,
                // 包成 lambda：列表顶边会随顶栏高度变化，当普通参数传会在组合期读取，
                // 顶边一变就整表重组。这样读取推迟到绘制期，只让图层失效。
                listTopInViewport = { listTopInWindow - rootTopInWindow },
            ).content()
        }
    }
}

/** 首尾留白的行高估值。取偏小的一档：估大了首项会越过圆心。 */
private val ESTIMATED_ROW_HEIGHT = 56.dp

/**
 * 非 Lazy 页面的逐块弦宽缩放。
 *
 * ## 为什么还要这一个
 *
 * [StoreRoundList] 只能救 `LazyColumn` 那几页。市场里最常看的**表盘详情**、上传表单、
 * 个人资料都是 `Column + verticalScroll` 的长表单，压根没有 item 概念，所以它们在圆屏上
 * 一直只有一圈静态内边距 —— 这就是老师反复报的「二级页面还是没有适配圆屏」。
 *
 * ## 测量顺序不能反
 *
 * `onGloballyPositioned` 必须排在 `graphicsLayer` **之前**：modifier 由外向内生效，
 * 放在前面测到的是**未缩放**的布局位置。反过来的话缩放会改变测量值、测量值又改变缩放，
 * 变成自激振荡 —— 这也是 `ChordScale.kt` 那条「测量只用布局值」铁律的由来。
 */
@Composable
internal fun Modifier.storeChordBlock(
    viewportWidth: Float = LocalChordViewportWidth.current,
    screenCenterY: Float = LocalChordScreenCenterY.current,
): Modifier {
    if (viewportWidth <= 0f || screenCenterY.isNaN()) return this
    var centerY by remember { mutableFloatStateOf(Float.NaN) }
    var height by remember { mutableFloatStateOf(0f) }
    return this
        .onGloballyPositioned { coordinates ->
            height = coordinates.size.height.toFloat()
            centerY = coordinates.positionInRoot().y + height / 2f
        }
        .graphicsLayer {
            if (centerY.isNaN() || height <= 0f) return@graphicsLayer
            val scale = chordScaleForItemGeometry(
                itemCenterY = centerY,
                itemHeight = height,
                viewportWidth = viewportWidth,
                screenCenterY = screenCenterY,
            )
            scaleX = scale
            scaleY = scale
        }
}

/**
 * 给非 Lazy 的市场页面下发弦宽坐标系，并提供圆屏首尾留白。
 *
 * 页面自己只要把每个卡片挂上 [storeChordBlock]，坐标测量这件事就集中在这里一处。
 */
@Composable
internal fun StoreChordScope(
    modifier: Modifier = Modifier,
    content: @Composable (roundPadding: PaddingValues) -> Unit,
) {
    val roundScreen = isFlueScreenRound()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val roundPadding = remember(roundScreen, viewportHeightPx, density) {
            if (!roundScreen || viewportHeightPx <= 0f) {
                PaddingValues(0.dp)
            } else {
                val padding = roundScrollablePadding(
                    viewportHeight = viewportHeightPx,
                    contentTop = 0f,
                    itemHeight = with(density) { ESTIMATED_ROW_HEIGHT.toPx() },
                )
                with(density) {
                    PaddingValues(top = padding.top.toDp(), bottom = padding.bottom.toDp())
                }
            }
        }
        CompositionLocalProvider(
            LocalChordViewportWidth provides if (roundScreen) viewportWidthPx else 0f,
            LocalChordScreenCenterY provides viewportHeightPx / 2f,
        ) {
            content(roundPadding)
        }
    }
}

/** [StoreRoundList] 的作用域：逐项挂上弦宽缩放与避让动画。 */
internal class StoreRoundListScope(
    private val delegate: LazyListScope,
    private val listState: androidx.compose.foundation.lazy.LazyListState,
    private val enabled: Boolean,
    private val viewportWidth: Float,
    private val screenCenterY: Float,
    private val listTopInViewport: () -> Float,
) : LazyListScope {

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        val rowKey = key ?: "store_item"
        // 字段先拿到局部变量：下面那个 lambda 的接收者是 LazyItemScope，
        // 在它里面既读本类字段又调 LazyItemScope 的扩展，两个隐式接收者解析不了。
        val state = listState
        val scaleEnabled = enabled
        val width = viewportWidth
        val centerY = screenCenterY
        val listTop = listTopInViewport
        delegate.item(key, contentType) {
            Box(
                modifier = storeRoundRowModifier(
                    key = rowKey,
                    listState = state,
                    enabled = scaleEnabled,
                    viewportWidth = width,
                    screenCenterY = centerY,
                    listTopInViewport = listTop,
                ),
            ) { content() }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val state = listState
        val scaleEnabled = enabled
        val width = viewportWidth
        val centerY = screenCenterY
        val listTop = listTopInViewport
        delegate.items(count, key, contentType) { index ->
            Box(
                modifier = storeRoundRowModifier(
                    key = key?.invoke(index) ?: index,
                    listState = state,
                    enabled = scaleEnabled,
                    viewportWidth = width,
                    screenCenterY = centerY,
                    listTopInViewport = listTop,
                ),
            ) { itemContent(index) }
        }
    }

    @androidx.compose.foundation.ExperimentalFoundationApi
    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        // 吸顶项不挂位移动画：它的位置由吸顶逻辑逐帧改写，两者会打架。
        delegate.stickyHeader(key, contentType) { index ->
            Box(modifier = Modifier.animateContentSize(flueContentSizeSpring)) { content(index) }
        }
    }
}

/**
 * 逐项的 modifier：避让动画 + 圆屏弦宽缩放。
 *
 * 做成**顶层**扩展而不是 [StoreRoundListScope] 的成员：成员版在 item lambda 里会同时
 * 引入「类实例」与「LazyItemScope」两个隐式接收者，Kotlin 解析不了
 * （"cannot be called in this context with an implicit receiver"）。
 */
@Composable
private fun LazyItemScope.storeRoundRowModifier(
    key: Any,
    listState: androidx.compose.foundation.lazy.LazyListState,
    enabled: Boolean,
    viewportWidth: Float,
    screenCenterY: Float,
    listTopInViewport: () -> Float,
): Modifier = Modifier
    .animateItem(
        fadeInSpec = FLUE_ITEM_FADE_IN,
        placementSpec = fluePlacementSpring,
        fadeOutSpec = FLUE_ITEM_FADE_OUT,
    )
    .animateContentSize(flueContentSizeSpring)
    .chordScaleItem(
        listState = listState,
        key = key,
        screenCenterY = screenCenterY,
        viewportWidth = viewportWidth,
        enabled = enabled,
        listTopInViewport = listTopInViewport,
    )
