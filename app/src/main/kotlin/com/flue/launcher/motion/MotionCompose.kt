package com.flue.launcher.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

fun MotionPhase.asFloatAnimationSpec(): FiniteAnimationSpec<Float> = when (val curve = easing) {
    is MotionEasing.CubicBezier -> tween(
        durationMillis = durationMillis,
        easing = CubicBezierEasing(curve.x1, curve.y1, curve.x2, curve.y2),
    )

    is MotionEasing.Spring -> spring(
        dampingRatio = curve.dampingRatio,
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
