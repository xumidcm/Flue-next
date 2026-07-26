package com.flue.launcher.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.flue.launcher.ui.common.isFlueScreenRound
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 圆屏下列表内容区左右各让出的比例（相对屏宽）。 */
const val ROUND_SETTINGS_HORIZONTAL_INSET = 0.10f

/** 圆屏下列表内容区上下各让出的比例（相对屏高）。约合 227dp 屏上的 36dp。 */
const val ROUND_SETTINGS_VERTICAL_INSET = 0.16f

/** 内容区占屏宽的比例（两侧 inset 之外剩下的部分）。 */
private const val CONTENT_WIDTH_FRACTION = 1f - 2f * ROUND_SETTINGS_HORIZONTAL_INSET

/** 弦宽之外再留一点余量，免得卡片圆角正好啃在表框上。 */
private const val CHORD_SAFETY_MARGIN = 0.06f

/** 收窄下限：再靠边也不至于窄到放不下一行字。 */
private const val MIN_WIDTH_FRACTION = 0.55f

/**
 * 圆屏按弦宽收窄。
 *
 * 方屏（或用户手动把形状覆盖成方屏）时等价于 [Modifier.fillMaxWidth]；
 * 圆屏时按控件在屏幕上的**归一化纵向位置** dy 求圆的半弦长
 * `sqrt(1 - dy²)`，越靠近顶部 / 底部可用宽度越小，于是行会自动变窄，
 * 不会被表框切掉两个角。
 *
 * 位置通过 [onGloballyPositioned] 读取（列表是纵向滚动的，宽度变化不会
 * 反过来影响纵向位置，所以不存在测量震荡）；比例做了 0.02 的量化 +
 * 阈值判断，避免每帧都触发重排。
 *
 * 使用前提：所在的 LazyColumn / Column 需要 `horizontalAlignment =
 * Alignment.CenterHorizontally`，否则收窄后的行会贴左边而不是居中。
 */
@Composable
fun Modifier.roundScreenChordWidth(
    minFraction: Float = MIN_WIDTH_FRACTION,
): Modifier {
    if (!isFlueScreenRound()) return this.fillMaxWidth()
    var fraction by remember { mutableFloatStateOf(1f) }
    return this
        .onGloballyPositioned { coordinates ->
            val screenHeight = coordinates.findRootCoordinates().size.height.toFloat()
            if (screenHeight <= 0f) return@onGloballyPositioned
            val centerY = coordinates.positionInRoot().y + coordinates.size.height / 2f
            val dy = ((centerY - screenHeight / 2f) / (screenHeight / 2f)).coerceIn(-1f, 1f)
            val halfChord = sqrt((1f - dy * dy).coerceAtLeast(0f))
            val raw = (halfChord - CHORD_SAFETY_MARGIN) / CONTENT_WIDTH_FRACTION
            // 量化到 0.02 一档，避免滚动时每帧都是一个新宽度
            val quantized = ((raw * 50f).roundToInt() / 50f).coerceIn(minFraction, 1f)
            if (abs(quantized - fraction) > 0.015f) {
                fraction = quantized
            }
        }
        .fillMaxWidth(fraction)
}
