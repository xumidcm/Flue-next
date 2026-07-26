package com.flue.launcher.ui.anim

import com.flue.launcher.launchsession.ReturnKind

internal enum class AppListScaleMotion {
    BETA_12_SPRING,
    STANDARD_SPRING,
    TWEEN_220,
    MATERIAL_RETURN_260,
}

internal data class AppListLayerMotionPolicy(
    val scaleMotion: AppListScaleMotion,
    val alphaDurationMillis: Int,
    val blurDurationMillis: Int,
)

private val Beta12AppListLayerMotionPolicy = AppListLayerMotionPolicy(
    scaleMotion = AppListScaleMotion.BETA_12_SPRING,
    alphaDurationMillis = 400,
    blurDurationMillis = 400,
)
private val MaterialOpeningAppListLayerMotionPolicy = AppListLayerMotionPolicy(
    scaleMotion = AppListScaleMotion.TWEEN_220,
    alphaDurationMillis = 220,
    blurDurationMillis = 220,
)
private val MaterialClosingAppListLayerMotionPolicy = AppListLayerMotionPolicy(
    scaleMotion = AppListScaleMotion.TWEEN_220,
    alphaDurationMillis = 400,
    blurDurationMillis = 400,
)
private val MaterialReturningAppListLayerMotionPolicy = AppListLayerMotionPolicy(
    scaleMotion = AppListScaleMotion.MATERIAL_RETURN_260,
    alphaDurationMillis = 400,
    blurDurationMillis = 400,
)
private val MaterialDefaultAppListLayerMotionPolicy = AppListLayerMotionPolicy(
    scaleMotion = AppListScaleMotion.STANDARD_SPRING,
    alphaDurationMillis = 400,
    blurDurationMillis = 400,
)

internal fun appListLayerMotionPolicy(
    appleWatchStyle: Boolean,
    openingFromFace: Boolean,
    closingToFace: Boolean,
    returningFromApp: Boolean,
): AppListLayerMotionPolicy {
    if (appleWatchStyle) {
        return Beta12AppListLayerMotionPolicy
    }

    return when {
        openingFromFace -> MaterialOpeningAppListLayerMotionPolicy
        closingToFace -> MaterialClosingAppListLayerMotionPolicy
        returningFromApp -> MaterialReturningAppListLayerMotionPolicy
        else -> MaterialDefaultAppListLayerMotionPolicy
    }
}

internal data class LaunchMaskMotionPolicy(
    val enterDurationMillis: Int,
    val exitDurationMillis: Int,
    val exitSlideDurationMillis: Int,
    val exitSlideFraction: Float,
    val opaqueBlack: Boolean,
)

/**
 * A single scalar clock is shared by the launch backdrop and its source icon.
 *
 * The old implementation used two AnimatedVisibility instances plus delayed
 * booleans.  When a return was interrupted those clocks restarted from their
 * target values, which is why the icon vanished before the mask and why a
 * black seam could be seen.  Keeping the value and velocity together lets the
 * caller reverse the same motion without inventing a second transition.
 */
internal data class LaunchMotionSnapshot(
    val progress: Float,
    val velocity: Float,
) {
    init {
        require(progress.isFinite()) { "Launch progress must be finite" }
        require(velocity.isFinite()) { "Launch velocity must be finite" }
    }

    val clampedProgress: Float
        get() = progress.coerceIn(0f, 1f)
}

internal fun reverseLaunchMotion(snapshot: LaunchMotionSnapshot): LaunchMotionSnapshot =
    LaunchMotionSnapshot(
        progress = 1f - snapshot.clampedProgress,
        velocity = -snapshot.velocity,
    )

/**
 * A return may reuse the icon's source point when the session we launched is
 * the one coming back.  `UnverifiedReturn` qualifies: the session was
 * dispatched from that icon and was confirmed backgrounded, only the
 * accessibility cross-check is missing — which is the normal case whenever the
 * accessibility service is not granted or a system window (Wear's
 * swipe-to-dismiss, a toast, the IME) briefly stole the foreground.
 *
 * Foreign and home returns still need a neutral pivot so an unrelated app is
 * never animated out of the old icon.
 */
internal fun returnUsesLaunchOrigin(kind: ReturnKind?): Boolean = when (kind) {
    ReturnKind.TargetReturn, ReturnKind.UnverifiedReturn -> true
    ReturnKind.ForeignReturn, ReturnKind.HomeReentry, null -> false
}

private val Beta12LaunchMaskMotionPolicy = LaunchMaskMotionPolicy(
    enterDurationMillis = 140,
    exitDurationMillis = 180,
    exitSlideDurationMillis = 220,
    exitSlideFraction = 1f / 6f,
    // The launch backdrop is already sourced from the current app-list
    // palette. Forcing black here produced a visible seam and a black flash in
    // light mode, especially while a return transition was interrupted.
    opaqueBlack = false,
)
private val MaterialLaunchMaskMotionPolicy = LaunchMaskMotionPolicy(
    enterDurationMillis = 220,
    exitDurationMillis = 220,
    exitSlideDurationMillis = 0,
    exitSlideFraction = 0f,
    opaqueBlack = false,
)

internal fun launchMaskMotionPolicy(appleWatchStyle: Boolean): LaunchMaskMotionPolicy =
    if (appleWatchStyle) {
        Beta12LaunchMaskMotionPolicy
    } else {
        MaterialLaunchMaskMotionPolicy
    }
