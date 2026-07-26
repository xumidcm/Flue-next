package com.flue.launcher.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 屏幕形状来源的唯一入口。
 *
 * 所有页面（商店 / 编辑器 / 设置 / 三个覆层）判断圆屏一律走
 * [isFlueScreenRound]，禁止直接读 `LocalConfiguration.current.isScreenRound` ——
 * 用户可以在设置里手动覆盖形状（部分方屏手表的系统会错误上报圆屏，反之亦然），
 * 覆盖值由 Launcher 根组件通过 [LocalFlueScreenShapeOverride] 下发。
 */
enum class FlueScreenShape {
    /** 跟随系统上报（默认）。 */
    AUTO,
    ROUND,
    SQUARE,
}

val LocalFlueScreenShapeOverride = compositionLocalOf { FlueScreenShape.AUTO }

@Composable
@ReadOnlyComposable
fun isFlueScreenRound(): Boolean = when (LocalFlueScreenShapeOverride.current) {
    FlueScreenShape.ROUND -> true
    FlueScreenShape.SQUARE -> false
    FlueScreenShape.AUTO -> LocalConfiguration.current.isScreenRound
}
