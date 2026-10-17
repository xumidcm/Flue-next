package com.flue.launcher.motion

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class GentleLaunchTimingTest {
    private val oldLaunch = MotionPhase(360, 110, MotionEasing.Spring(1.05f, 669f))

    @Test
    fun sampledLaunchTakesAboutAQuarterLongerWithoutOvershooting() {
        fun animation(phase: MotionPhase) = TargetBasedAnimation(
            phase.asFloatAnimationSpec(), Float.VectorConverter, 0f, 1f,
        )
        val old = animation(oldLaunch)
        val current = animation(FlueMotionProfiles.Gentle.appLaunch)
        fun reaches(animation: TargetBasedAnimation<Float, *>, value: Float): Int =
            (0..1500).first { animation.getValueFromNanos(it * 1_000_000L) >= value }
        for (progress in listOf(0.25f, 0.5f, 0.9f, 0.99f)) {
            val ratio = reaches(current, progress).toFloat() / reaches(old, progress)
            assertTrue("progress=$progress ratio=$ratio", ratio in 1.22f..1.28f)
        }
        var previous = 0f
        for (millis in 0..1500 step 8) {
            val value = current.getValueFromNanos(millis * 1_000_000L)
            assertTrue(value >= previous - 0.00001f && value <= 1.00001f)
            previous = value
        }
        assertEquals(1f, previous, 0.001f)
    }

    @Test
    fun defaultAndSelectedGentleUseNewTimingButSavedCustomKeepsItsPhases() {
        for (id in listOf(null, FlueMotionProfiles.GENTLE_ID)) {
            val phase = FlueMotionProfiles.resolve(id, emptyList()).appLaunch
            assertEquals(450, phase.durationMillis)
            assertEquals(140, phase.handoffMillis)
            assertEquals(MotionEasing.Spring(1.05f, 428.16f), phase.easing)
        }
        val custom = FlueMotionProfiles.Gentle.customCopy(customId = "custom:old-gentle")
            .copy(appLaunch = oldLaunch)
        val saved = FlueMotionProfileJson.decodeLibrary(FlueMotionProfileJson.encodeLibrary(listOf(custom)))
        assertEquals(custom, FlueMotionProfiles.resolve(custom.id, saved))
        assertEquals(MotionEasing.Spring(1.05f, 1226.27f), FlueMotionProfiles.Gentle.appReturn.easing)
        assertEquals(380, FlueMotionProfiles.Gentle.appReturn.durationMillis)
        assertEquals(320, FlueMotionProfiles.Gentle.appListEnter.durationMillis)
    }
}
