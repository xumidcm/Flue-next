package com.flue.launcher.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class FlueMotionProfileTest {
    @Test
    fun `built in profiles satisfy the versioned motion contract`() {
        assertEquals(3, FlueMotionProfiles.builtIns.size)
        FlueMotionProfiles.builtIns.forEach { profile ->
            assertEquals(FLUE_MOTION_SCHEMA_VERSION, profile.requireValid().schemaVersion)
            assertTrue(profile.bounceEnabled)
            assertTrue(profile.edgeStrength in 0..10)
            MotionPhaseKey.entries.forEach { key ->
                assertTrue(profile.phase(key).durationMillis in 80..1_200)
                assertTrue(profile.phase(key).handoffMillis in 0..500)
            }
        }
        assertTrue(
            FlueMotionProfiles.Aggressive.appLaunch.durationMillis <
                FlueMotionProfiles.Gentle.appLaunch.durationMillis,
        )
    }

    @Test
    fun `copying a preset creates an editable custom identity`() {
        val copy = FlueMotionProfiles.Gentle.customCopy(
            customName = "我的曲线",
            customId = "custom:test",
        )

        assertEquals("custom:test", copy.id)
        assertEquals("我的曲线", copy.name)
        assertNotEquals(FlueMotionProfiles.Gentle.id, copy.id)
        assertFalse(FlueMotionProfiles.isBuiltIn(copy.id))
    }

    @Test
    fun `json library round trip preserves phases and easing`() {
        val custom = FlueMotionProfiles.Aggressive.customCopy(
            customName = "赛道",
            customId = "custom:track",
        ).copy(
            edgeStrength = 9,
            edgeBounce = MotionPhase(
                durationMillis = 444,
                handoffMillis = 123,
                easing = MotionEasing.CubicBezier(0.1f, 1.1f, 0.2f, 1f),
            ),
        )

        val decoded = FlueMotionProfileJson.decodeLibrary(
            FlueMotionProfileJson.encodeLibrary(listOf(custom)),
        )

        assertEquals(listOf(custom), decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid duration is rejected instead of silently clamped`() {
        FlueMotionProfileJson.decodeProfile(
            FlueMotionProfileJson.encodeProfile(FlueMotionProfiles.Gentle)
                .replace("\"durationMillis\": 320", "\"durationMillis\": 79"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `future schema is rejected`() {
        FlueMotionProfileJson.decodeProfile(
            FlueMotionProfileJson.encodeProfile(FlueMotionProfiles.Gentle)
                .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate imported ids are rejected`() {
        val profile = FlueMotionProfiles.Gentle.customCopy(customId = "custom:same")
        FlueMotionProfileJson.decodeLibrary(
            FlueMotionProfileJson.encodeLibrary(listOf(profile, profile.copy(name = "另一个"))),
        )
    }
}
