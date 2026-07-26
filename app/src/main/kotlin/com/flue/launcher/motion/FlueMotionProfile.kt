package com.flue.launcher.motion

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.UUID

const val FLUE_MOTION_SCHEMA_VERSION: Int = 1
const val FLUE_MOTION_DURATION_MIN_MS: Int = 80
const val FLUE_MOTION_DURATION_MAX_MS: Int = 1_200
const val FLUE_MOTION_HANDOFF_MIN_MS: Int = 0
const val FLUE_MOTION_HANDOFF_MAX_MS: Int = 500

sealed interface MotionEasing {
    data class CubicBezier(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    ) : MotionEasing

    data class Spring(
        val dampingRatio: Float,
        val stiffness: Float,
    ) : MotionEasing
}

data class MotionPhase(
    val durationMillis: Int,
    val handoffMillis: Int,
    val easing: MotionEasing,
)

enum class MotionPhaseKey {
    APP_LIST_ENTER,
    APP_LAUNCH,
    APP_RETURN,
    PULL_TO_FACE,
    EDGE_BOUNCE,
}

data class FlueMotionProfile(
    val schemaVersion: Int = FLUE_MOTION_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val bounceEnabled: Boolean,
    val edgeStrength: Int,
    val appListEnter: MotionPhase,
    val appLaunch: MotionPhase,
    val appReturn: MotionPhase,
    val pullToFace: MotionPhase,
    val edgeBounce: MotionPhase,
) {
    fun phase(key: MotionPhaseKey): MotionPhase = when (key) {
        MotionPhaseKey.APP_LIST_ENTER -> appListEnter
        MotionPhaseKey.APP_LAUNCH -> appLaunch
        MotionPhaseKey.APP_RETURN -> appReturn
        MotionPhaseKey.PULL_TO_FACE -> pullToFace
        MotionPhaseKey.EDGE_BOUNCE -> edgeBounce
    }

    fun withPhase(key: MotionPhaseKey, phase: MotionPhase): FlueMotionProfile = when (key) {
        MotionPhaseKey.APP_LIST_ENTER -> copy(appListEnter = phase)
        MotionPhaseKey.APP_LAUNCH -> copy(appLaunch = phase)
        MotionPhaseKey.APP_RETURN -> copy(appReturn = phase)
        MotionPhaseKey.PULL_TO_FACE -> copy(pullToFace = phase)
        MotionPhaseKey.EDGE_BOUNCE -> copy(edgeBounce = phase)
    }

    fun requireValid(): FlueMotionProfile {
        require(schemaVersion == FLUE_MOTION_SCHEMA_VERSION) {
            "Unsupported motion profile schema: $schemaVersion"
        }
        require(id.isNotBlank() && id.length <= 96) { "Motion profile id is invalid" }
        require(name.isNotBlank() && name.length <= 48) { "Motion profile name is invalid" }
        require(edgeStrength in 0..10) { "edgeStrength must be in 0..10" }
        MotionPhaseKey.entries.forEach { key -> phase(key).requireValid(key) }
        return this
    }

    fun customCopy(
        customName: String = "$name 副本",
        customId: String = "custom:${UUID.randomUUID()}",
    ): FlueMotionProfile = copy(
        id = customId,
        name = customName.trim().take(48),
    ).requireValid()
}

private fun MotionPhase.requireValid(key: MotionPhaseKey) {
    require(durationMillis in FLUE_MOTION_DURATION_MIN_MS..FLUE_MOTION_DURATION_MAX_MS) {
        "${key.name}.durationMillis must be in " +
            "$FLUE_MOTION_DURATION_MIN_MS..$FLUE_MOTION_DURATION_MAX_MS"
    }
    require(handoffMillis in FLUE_MOTION_HANDOFF_MIN_MS..FLUE_MOTION_HANDOFF_MAX_MS) {
        "${key.name}.handoffMillis must be in " +
            "$FLUE_MOTION_HANDOFF_MIN_MS..$FLUE_MOTION_HANDOFF_MAX_MS"
    }
    when (val value = easing) {
        is MotionEasing.CubicBezier -> {
            require(value.x1.isFinite() && value.x1 in 0f..1f)
            require(value.x2.isFinite() && value.x2 in 0f..1f)
            require(value.y1.isFinite() && value.y1 in -4f..4f)
            require(value.y2.isFinite() && value.y2 in -4f..4f)
        }

        is MotionEasing.Spring -> {
            require(value.dampingRatio.isFinite() && value.dampingRatio in 0.05f..2f)
            require(value.stiffness.isFinite() && value.stiffness in 10f..3_000f)
        }
    }
}

object FlueMotionProfiles {
    const val GENTLE_ID: String = "preset:gentle"
    const val AGGRESSIVE_ID: String = "preset:aggressive"
    const val CLASSIC_ID: String = "preset:classic"

    /**
     * The curve Flue shipped before motion profiles existed.
     *
     * It came from two constants that every layer transition shared:
     * `BlurTransition.TransitionSpring = spring(dampingRatio = 0.75f,
     * stiffness = Spring.StiffnessMediumLow)` (400f) for scale/translation, and
     * `AlphaSpec = tween(400)` for alpha and blur. Reproducing it as a profile
     * keeps one damping ratio and one stiffness across every phase — that
     * uniformity is exactly what made it feel "classic", so resist the urge to
     * tune the phases apart.
     */
    val Classic: FlueMotionProfile = FlueMotionProfile(
        id = CLASSIC_ID,
        name = "经典",
        bounceEnabled = true,
        edgeStrength = 5,
        appListEnter = MotionPhase(
            durationMillis = 400,
            handoffMillis = 80,
            easing = MotionEasing.Spring(dampingRatio = 0.75f, stiffness = 400f),
        ),
        appLaunch = MotionPhase(
            durationMillis = 400,
            handoffMillis = 120,
            easing = MotionEasing.Spring(dampingRatio = 0.75f, stiffness = 400f),
        ),
        appReturn = MotionPhase(
            durationMillis = 400,
            handoffMillis = 110,
            easing = MotionEasing.Spring(dampingRatio = 0.75f, stiffness = 400f),
        ),
        pullToFace = MotionPhase(
            durationMillis = 400,
            handoffMillis = 80,
            easing = MotionEasing.Spring(dampingRatio = 0.75f, stiffness = 400f),
        ),
        edgeBounce = MotionPhase(
            durationMillis = 400,
            handoffMillis = 90,
            easing = MotionEasing.Spring(dampingRatio = 0.66f, stiffness = 360f),
        ),
    ).requireValid()

    val Gentle: FlueMotionProfile = FlueMotionProfile(
        id = GENTLE_ID,
        name = "柔和",
        bounceEnabled = true,
        edgeStrength = 5,
        appListEnter = MotionPhase(
            durationMillis = 320,
            handoffMillis = 60,
            easing = MotionEasing.CubicBezier(0.22f, 1f, 0.36f, 1f),
        ),
        appLaunch = MotionPhase(
            durationMillis = 360,
            handoffMillis = 110,
            easing = MotionEasing.CubicBezier(0.18f, 0.88f, 0.22f, 1f),
        ),
        appReturn = MotionPhase(
            durationMillis = 380,
            handoffMillis = 100,
            easing = MotionEasing.Spring(dampingRatio = 0.78f, stiffness = 420f),
        ),
        pullToFace = MotionPhase(
            durationMillis = 340,
            handoffMillis = 60,
            easing = MotionEasing.Spring(dampingRatio = 0.78f, stiffness = 420f),
        ),
        edgeBounce = MotionPhase(
            durationMillis = 350,
            handoffMillis = 80,
            easing = MotionEasing.Spring(dampingRatio = 0.66f, stiffness = 360f),
        ),
    ).requireValid()

    val Aggressive: FlueMotionProfile = FlueMotionProfile(
        id = AGGRESSIVE_ID,
        name = "激进",
        bounceEnabled = true,
        edgeStrength = 7,
        appListEnter = MotionPhase(
            durationMillis = 250,
            handoffMillis = 45,
            easing = MotionEasing.CubicBezier(0.16f, 1f, 0.30f, 1f),
        ),
        appLaunch = MotionPhase(
            durationMillis = 280,
            handoffMillis = 85,
            easing = MotionEasing.CubicBezier(0.12f, 0.92f, 0.18f, 1f),
        ),
        appReturn = MotionPhase(
            durationMillis = 296,
            handoffMillis = 78,
            easing = MotionEasing.Spring(dampingRatio = 0.72f, stiffness = 620f),
        ),
        pullToFace = MotionPhase(
            durationMillis = 265,
            handoffMillis = 45,
            easing = MotionEasing.Spring(dampingRatio = 0.72f, stiffness = 620f),
        ),
        edgeBounce = MotionPhase(
            durationMillis = 273,
            handoffMillis = 62,
            easing = MotionEasing.Spring(dampingRatio = 0.60f, stiffness = 560f),
        ),
    ).requireValid()

    val builtIns: List<FlueMotionProfile> = listOf(Gentle, Aggressive, Classic)

    fun isBuiltIn(id: String): Boolean =
        id == GENTLE_ID || id == AGGRESSIVE_ID || id == CLASSIC_ID

    fun resolve(id: String?, customProfiles: List<FlueMotionProfile>): FlueMotionProfile =
        (builtIns + customProfiles).firstOrNull { it.id == id } ?: Gentle
}

val LocalFlueMotionProfile = staticCompositionLocalOf { FlueMotionProfiles.Gentle }
