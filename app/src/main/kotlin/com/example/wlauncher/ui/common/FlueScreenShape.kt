package com.flue.launcher.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

const val DEFAULT_OVERLAY_MASK_CORNER_RADIUS_DP = 40
const val MAX_OVERLAY_MASK_CORNER_RADIUS_DP = 120

/**
 * 只做范围夹取，**不要再 snap 到 5 的倍数**。
 *
 * 旧写法是 `((value / 5f).roundToInt() * 5)`，于是 56 会被算成 55（56/5=11.2→11→55）、
 * 57 也是 55、58 跳到 60 —— 用户在滑块上停在 56 点完成，重开弹窗却显示 55。
 * 滑块本身一直是连续的，是这里把值吃掉的。
 */
fun normalizeOverlayMaskCornerRadiusDp(value: Int): Int =
    value.coerceIn(0, MAX_OVERLAY_MASK_CORNER_RADIUS_DP)

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

/** Shared root mask for Smart Stack, Control Center and the widget page. */
@Composable
@ReadOnlyComposable
fun flueOverlayMaskShape(squareCornerRadiusDp: Int): Shape =
    if (isFlueScreenRound()) {
        CircleShape
    } else {
        RoundedCornerShape(normalizeOverlayMaskCornerRadiusDp(squareCornerRadiusDp).dp)
    }
