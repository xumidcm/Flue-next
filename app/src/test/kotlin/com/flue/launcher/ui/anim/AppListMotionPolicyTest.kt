package com.flue.launcher.ui.anim

import com.flue.launcher.launchsession.ReturnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListMotionPolicyTest {
    @Test
    fun appListFaceShrinksAndSinksForBothStandardMotionModes() {
        for (motion in FlueTransitionMotion.entries) {
            val frame = faceLayerValues(com.flue.launcher.ui.navigation.ScreenState.Apps, motion)
            assertEquals(0.92f, frame.scale, 0.0001f)
            assertEquals(0.03f, frame.translationY, 0.0001f)
        }
    }

    @Test
    fun appListFaceStillRespectsDisabledShrinkAndLegacyMotion() {
        val apps = com.flue.launcher.ui.navigation.ScreenState.Apps
        val disabled = faceLayerValues(apps, FlueTransitionMotion.SCALE, shrinkOnFaceReturn = false)
        assertEquals(1f, disabled.scale, 0f)
        assertEquals(0f, disabled.translationY, 0f)
        val legacy = faceLayerValues(apps, FlueTransitionMotion.SCALE, legacyTransition = true)
        assertEquals(2.5f, legacy.scale, 0f)
    }

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
    fun ownSessionReturnsReuseTheLaunchOriginAndForeignOnesDoNot() {
        assertTrue(returnUsesLaunchOrigin(ReturnKind.TargetReturn))
        // An unverified return is still *our* session: it was dispatched from
        // this icon and was confirmed backgrounded, only the accessibility
        // cross-check is missing. That is the normal case whenever the
        // accessibility service is not granted, or when a system window (Wear's
        // swipe-to-dismiss, a toast, the IME) briefly stole the foreground.
        // Excluding it here meant those devices lost the return animation
        // entirely and the icon simply blinked out of existence.
        assertTrue(returnUsesLaunchOrigin(ReturnKind.UnverifiedReturn))

        // These genuinely are not our session, so they must keep a neutral
        // pivot — an unrelated app must never animate out of the old icon.
        assertFalse(returnUsesLaunchOrigin(ReturnKind.ForeignReturn))
        assertFalse(returnUsesLaunchOrigin(ReturnKind.HomeReentry))
        assertFalse(returnUsesLaunchOrigin(null))
    }
}
