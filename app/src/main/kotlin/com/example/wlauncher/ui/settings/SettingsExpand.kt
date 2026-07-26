package com.flue.launcher.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay

/** 展开 / 收起动画的时长上限，同时用作 [rememberSettingsRowMounted] 的卸载延迟。 */
const val SETTINGS_EXPAND_DURATION_MS: Long = 320L

/**
 * 条件设置项的「挂载窗口」。
 *
 * LazyColumn 的 `verticalArrangement = spacedBy(...)` 对 0 高度的 item 一样会
 * 留出间距，所以折叠完毕的条件项**必须真的从列表里移除**，否则会留下一条
 * 空隙。但直接 `if (cond) item { ... }` 又会让它瞬间消失、没有收起动画。
 *
 * 这里把两者拼起来：`visible` 变 true 立刻挂载（随后由
 * [SettingsExpandableRow] 播放展开动画），变 false 时先留在列表里播收起动画，
 * 等 [exitDelayMillis] 之后再卸载。
 *
 * 用法（注意 `LazyListScope` 不是 composable，判断要在外面算好）：
 * ```
 * val showDelayRow = rememberSettingsRowMounted(splashIcon)
 * // ... LazyColumn 里：
 * if (showDelayRow) item("splash_delay") {
 *     SettingsExpandableRow(visible = splashIcon) { SettingsSliderRow(...) }
 * }
 * ```
 */
@Composable
fun rememberSettingsRowMounted(
    visible: Boolean,
    exitDelayMillis: Long = SETTINGS_EXPAND_DURATION_MS,
): Boolean {
    var mounted by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
        } else {
            delay(exitDelayMillis)
            mounted = false
        }
    }
    return mounted || visible
}

/**
 * 条件设置项的展开 / 收起容器：从上边缘向下长出 + 淡入，收起时反向。
 * 高度是连续动画的，所以列表里它下面的每一项会自动平滑让位，不需要再给
 * 兄弟项加 `Modifier.animateItem()`。
 */
@Composable
fun SettingsExpandableRow(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntSize(1, 1),
            ),
            expandFrom = Alignment.Top,
        ) + fadeIn(animationSpec = spring(dampingRatio = 0.9f, stiffness = 400f)),
        exit = shrinkVertically(
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = Spring.StiffnessMedium,
                visibilityThreshold = IntSize(1, 1),
            ),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(animationSpec = spring(dampingRatio = 1f, stiffness = 700f)),
        content = { content() },
    )
}
