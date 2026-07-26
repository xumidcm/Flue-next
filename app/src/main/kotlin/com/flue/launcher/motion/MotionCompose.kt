package com.flue.launcher.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

fun MotionPhase.asFloatAnimationSpec(): FiniteAnimationSpec<Float> =
    asFloatAnimationSpec(allowOvershoot = true)

/**
 * [allowOvershoot] = false 是「减弱动态效果」的实现：欠阻尼 spring 提到临界
 * 阻尼、贝塞尔的越界控制点钳回 0..1 —— 动画到位即停，不再弹过头。
 * 曲线的其余性格（刚度 / 时长 / 前段节奏）保持不变。
 */
fun MotionPhase.asFloatAnimationSpec(allowOvershoot: Boolean): FiniteAnimationSpec<Float> =
    when (val curve = easing) {
        is MotionEasing.CubicBezier -> tween(
            durationMillis = durationMillis,
            easing = if (allowOvershoot) {
                CubicBezierEasing(curve.x1, curve.y1, curve.x2, curve.y2)
            } else {
                CubicBezierEasing(
                    curve.x1,
                    curve.y1.coerceIn(0f, 1f),
                    curve.x2,
                    curve.y2.coerceIn(0f, 1f),
                )
            },
        )

        is MotionEasing.Spring -> spring(
            dampingRatio = if (allowOvershoot) {
                curve.dampingRatio
            } else {
                curve.dampingRatio.coerceAtLeast(1f)
            },
            stiffness = curve.stiffness,
        )
    }

/**
 * Enter/exit transition APIs animate Int offsets. A spring profile still uses
 * its phase duration here because Compose has no physically equivalent
 * IntOffset spring carrying a Float stiffness contract.
 */
fun MotionPhase.asIntOffsetAnimationSpec(): FiniteAnimationSpec<IntOffset> = when (val curve = easing) {
    is MotionEasing.CubicBezier -> tween(
        durationMillis = durationMillis,
        easing = CubicBezierEasing(curve.x1, curve.y1, curve.x2, curve.y2),
    )

    is MotionEasing.Spring -> tween(durationMillis = durationMillis)
}
