package com.flue.launcher.ui.anim

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

private val Beta12LaunchMaskMotionPolicy = LaunchMaskMotionPolicy(
    enterDurationMillis = 140,
    exitDurationMillis = 180,
    exitSlideDurationMillis = 220,
    exitSlideFraction = 1f / 6f,
    opaqueBlack = true,
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
