package com.flue.launcher.ui.anim

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListMotionPolicyTest {
    @Test
    fun beta12AppleMotionUsesTheGoldenSingleLayerTimings() {
        val policy = appListLayerMotionPolicy(
            appleWatchStyle = true,
            openingFromFace = true,
            closingToFace = false,
            returningFromApp = false,
        )

        assertEquals(AppListScaleMotion.BETA_12_SPRING, policy.scaleMotion)
        assertEquals(400, policy.alphaDurationMillis)
        assertEquals(400, policy.blurDurationMillis)
    }

    @Test
    fun beta12LaunchMaskRestoresOpaqueBlackAndTheExitSlide() {
        val policy = launchMaskMotionPolicy(appleWatchStyle = true)

        assertEquals(140, policy.enterDurationMillis)
        assertEquals(180, policy.exitDurationMillis)
        assertEquals(220, policy.exitSlideDurationMillis)
        assertEquals(1f / 6f, policy.exitSlideFraction, 0f)
        assertEquals(true, policy.opaqueBlack)
    }
}
