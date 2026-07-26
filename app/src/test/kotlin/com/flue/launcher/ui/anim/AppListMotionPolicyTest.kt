package com.flue.launcher.ui.anim

import com.flue.launcher.launchsession.ReturnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun beta12LaunchMaskUsesTheAppListBackdropAndKeepsTheExitSlide() {
        val policy = launchMaskMotionPolicy(appleWatchStyle = true)

        assertEquals(140, policy.enterDurationMillis)
        assertEquals(180, policy.exitDurationMillis)
        assertEquals(220, policy.exitSlideDurationMillis)
        assertEquals(1f / 6f, policy.exitSlideFraction, 0f)
        assertEquals(false, policy.opaqueBlack)
    }

    @Test
    fun reversingAFrameKeepsTheDistanceAndFlipsVelocity() {
        val reversed = reverseLaunchMotion(
            LaunchMotionSnapshot(progress = 0.28f, velocity = 1.75f),
        )

        assertEquals(0.72f, reversed.progress, 0f)
        assertEquals(-1.75f, reversed.velocity, 0f)
    }

    @Test
    fun reversingClampsOnlyTheRenderedProgress() {
        val reversed = reverseLaunchMotion(
            LaunchMotionSnapshot(progress = 1.4f, velocity = -2f),
        )

        assertEquals(0f, reversed.progress, 0f)
        assertEquals(2f, reversed.velocity, 0f)
    }

    @Test
    fun onlyVerifiedTargetReturnMayReuseTheLaunchOrigin() {
        assertTrue(returnUsesLaunchOrigin(ReturnKind.TargetReturn))
        assertFalse(returnUsesLaunchOrigin(ReturnKind.ForeignReturn))
        assertFalse(returnUsesLaunchOrigin(ReturnKind.UnverifiedReturn))
        assertFalse(returnUsesLaunchOrigin(ReturnKind.HomeReentry))
        assertFalse(returnUsesLaunchOrigin(null))
    }
}
