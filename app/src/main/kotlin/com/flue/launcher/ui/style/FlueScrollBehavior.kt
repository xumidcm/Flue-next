package com.flue.launcher.ui.style

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs
import com.flue.launcher.motion.FlueMotionProfile
import com.flue.launcher.motion.LocalFlueMotionProfile
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 越界回弹与滚动触觉的风格分发。
 *
 * 三套风格的回弹手感本来就该不同，而现状是 27 处调用点全走同一个
 * `flueElasticNestedScroll`（CloX 系数那套）——那是 watchOS 的手感，套在 HyperOS 与
 * Material 上都不对：
 *
 * - **MIUIX** → miuix 库的 `overScrollVertical()`，HyperOS 自己的阻尼曲线；
 * - **MD3** → 平台 stretch overscroll（Android 12+ 官方拉伸，Material 的正解）。
 *   不加任何 modifier 就是它 —— 系统默认行为，`OverscrollEffect` 由平台提供；
 * - **APPLE** → 保留现有 `flueElasticNestedScroll`（位移 + 弹簧，正是 Apple 手感），
 *   并继续吃动效档案里的弹簧参数。
 *
 * Apple 档刻意仍走 placement-phase 位移而不是 graphicsLayer：原实现注释写明了理由 ——
 * 把整个滚动视口变成常驻图层，在 1200×1920 的表盘上每次 LazyColumn 失效都要透过那层
 * 重绘，代价很高。
 */
@Composable
fun Modifier.flueOverscroll(
    listState: LazyListState,
    motionProfile: FlueMotionProfile = LocalFlueMotionProfile.current,
    uiStyle: UiStyle = LauncherTheme.style.uiStyle,
): Modifier = when (uiStyle) {
    UiStyle.MIUIX -> this.overScrollVertical()
    // 平台 stretch 是系统默认，不需要也不应该再叠一层自定义回弹。
    UiStyle.MATERIAL_3 -> this
    UiStyle.APPLE_WATCH -> this.flueElasticNestedScroll(listState, motionProfile)
}

@Composable
fun Modifier.flueOverscroll(
    scrollState: ScrollState,
    motionProfile: FlueMotionProfile = LocalFlueMotionProfile.current,
    uiStyle: UiStyle = LauncherTheme.style.uiStyle,
): Modifier = when (uiStyle) {
    UiStyle.MIUIX -> this.overScrollVertical()
    UiStyle.MATERIAL_3 -> this
    UiStyle.APPLE_WATCH -> this.flueElasticNestedScroll(scrollState, motionProfile)
}

/** 判定「还在推但已经推不动了」的阈值。1px 以下当抖动，不给反馈。 */
private const val SCROLL_EDGE_EPSILON = 1f

/**
 * 滚动触底反馈（老师要的「触底震动」）。
 *
 * - **MIUIX** → miuix 库自己的 `scrollEndHaptic()`，与 HyperOS 一致；
 * - **另两档** → 自接：列表已经滚不动、但手指还在往同方向推的那一刻给一次
 *   [FlueHaptics.scrollEnd]（Apple 用「撞到了」的手势阈值型，MD3 用手势结束型）。
 *
 * 「一次」靠一个闩：只有等到列表**真的又滚动过**（有被消费的位移）才允许再响，
 * 否则一直顶着边缘会变成连续震动。
 *
 * **挂的顺序有讲究**：要挂在 [flueOverscroll] **之后**（也就是更靠内层）。嵌套滚动是
 * 内层连接先拿到事件，Apple 档的回弹会把边缘余量消费掉当位移用；反过来挂就永远收不到
 * 余量、一次都不会响。
 */
@Composable
fun Modifier.flueScrollEndHaptic(
    uiStyle: UiStyle = LauncherTheme.style.uiStyle,
): Modifier {
    if (uiStyle == UiStyle.MIUIX) return this.scrollEndHaptic()
    val haptics = LocalHapticFeedback.current
    val feedback = FlueHaptics.scrollEnd(uiStyle)
    val connection = remember(haptics, feedback) {
        object : NestedScrollConnection {
            private var fired = false

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                when {
                    abs(available.y) > SCROLL_EDGE_EPSILON -> {
                        if (!fired) {
                            fired = true
                            haptics.performHapticFeedback(feedback)
                        }
                    }
                    abs(consumed.y) > SCROLL_EDGE_EPSILON -> fired = false
                }
                // 只观察，不消费 —— 余量还要留给回弹那一层。
                return Offset.Zero
            }
        }
    }
    return this.nestedScroll(connection)
}
