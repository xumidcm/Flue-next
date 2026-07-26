package com.flue.launcher.applist.nativeview

import android.animation.TimeInterpolator
import android.view.animation.PathInterpolator
import com.flue.launcher.motion.FlueMotionProfile
import com.flue.launcher.motion.FlueMotionProfiles
import com.flue.launcher.motion.MotionEasing
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable, validated motion values consumed by the native list.
 *
 * Compose hands the selected [FlueMotionProfile] over once. Per-frame values
 * stay in the View/EdgeEffect clocks and never cross the AndroidView seam.
 */
internal fun FlueMotionProfile.nativeSafeProfile(): FlueMotionProfile =
    runCatching { requireValid() }.getOrDefault(FlueMotionProfiles.Gentle)

internal fun MotionEasing.asNativeTimeInterpolator(): TimeInterpolator = when (this) {
    is MotionEasing.CubicBezier -> PathInterpolator(
        x1.coerceIn(0f, 1f),
        y1.coerceIn(-4f, 4f),
        x2.coerceIn(0f, 1f),
        y2.coerceIn(-4f, 4f),
    )

    is MotionEasing.Spring -> TimeInterpolator { progress ->
        nativeMotionInterpolation(progress, this)
    }
}

/**
 * Deterministic spring response for the pure edge-physics state machine.
 * Cubic curves use a bounded parametric approximation here; entry animations
 * use Android's exact [PathInterpolator].
 */
internal fun nativeMotionInterpolation(
    progress: Float,
    easing: MotionEasing,
): Float {
    val t = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    return when (easing) {
        is MotionEasing.CubicBezier -> {
            // The edge rebound only needs a stable monotonic time response.
            // Solving x(t) would allocate/work harder on every legacy-watch
            // frame, so use the curve's y polynomial with bounded control
            // points. Entry motion still uses PathInterpolator above.
            val oneMinus = 1f - t
            (
                3f * oneMinus * oneMinus * t * easing.y1.coerceIn(-4f, 4f) +
                    3f * oneMinus * t * t * easing.y2.coerceIn(-4f, 4f) +
                    t * t * t
                )
        }

        is MotionEasing.Spring -> {
            val damping = easing.dampingRatio.coerceIn(0.05f, 2f)
            val stiffness = easing.stiffness.coerceIn(10f, 3_000f)
            // Normalize the profile's stiffness to a useful 0..1 phase. The
            // duration remains authoritative; this only shapes that phase.
            val angular = sqrt(stiffness.toDouble()).toFloat()
            val seconds = t * 0.45f
            if (damping < 1f) {
                val damped = angular * sqrt(1f - damping * damping)
                val envelope = exp((-damping * angular * seconds).toDouble()).toFloat()
                val phase = damped * seconds
                val displacement = envelope * (
                    cos(phase.toDouble()).toFloat() +
                        (damping * angular / damped.coerceAtLeast(0.001f)) *
                        sin(phase.toDouble()).toFloat()
                    )
                1f - displacement
            } else {
                // Critically/over-damped profiles should not overshoot.
                val rate = angular / (damping * 1.6f)
                1f - exp((-rate * seconds).toDouble()).toFloat()
            }
        }
    }
}
