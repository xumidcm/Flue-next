package com.flue.launcher.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import com.flue.launcher.ui.theme.UiStyle
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

    // ── 曲线统一为「柔和」 ──────────────────────────────────────────────────

    /**
     * 内置档只剩三个，默认是柔和。
     *
     * 以前还有一档「跟随风格」，按当前 UI 风格给三套不同参数，并且是默认档。老师定的
     * 方向是**所有 UI 风格的动画曲线都用柔和**，所以那一档整个删掉了 —— 留着它就等于
     * 留一个「让三档风格各自不同」的入口，与「统一柔和」直接矛盾。原来这一族的四条断言
     *（跟随档参数各不相同、id 跨风格不变、未设置时落到跟随档、列表把它排最前）锁的正是
     * 被删掉的行为，所以整族替换成下面这两条。
     */
    @Test
    fun `only three built in profiles remain and gentle is the default`() {
        assertEquals(3, FlueMotionProfiles.builtIns.size)
        assertEquals(FlueMotionProfiles.GENTLE_ID, FlueMotionProfiles.builtIns.first().id)
        FlueMotionProfiles.builtIns.forEach { it.requireValid() }

        // 没存过（首次启动、或刚重置）落到柔和。
        assertEquals(
            FlueMotionProfiles.GENTLE_ID,
            FlueMotionProfiles.resolve(null, emptyList()).id,
        )
        // 明确选过的具体档位不受影响。
        assertEquals(
            FlueMotionProfiles.AGGRESSIVE_ID,
            FlueMotionProfiles.resolve(FlueMotionProfiles.AGGRESSIVE_ID, emptyList()).id,
        )
    }

    /** 曲线不再随 UI 风格分叉：内置档与风格无关，同一 id 永远同一份参数。 */
    @Test
    fun `built in curves no longer vary by ui style`() {
        assertTrue(FlueMotionProfiles.isBuiltIn(FlueMotionProfiles.GENTLE_ID))
        assertTrue(FlueMotionProfiles.isBuiltIn(FlueMotionProfiles.AGGRESSIVE_ID))
        assertTrue(FlueMotionProfiles.isBuiltIn(FlueMotionProfiles.CLASSIC_ID))
        // 被删掉的那一档的 id 不能再被当成内置，否则旧存档会解析成一个不存在的档。
        assertFalse(FlueMotionProfiles.isBuiltIn("preset:follow_style"))

        // 柔和的边缘回弹仍然比其它阶段松 —— 弹得出来才看得见。
        val launch = FlueMotionProfiles.Gentle.appLaunch.easing as MotionEasing.Spring
        val bounce = FlueMotionProfiles.Gentle.edgeBounce.easing as MotionEasing.Spring
        assertTrue(bounce.dampingRatio < launch.dampingRatio)
        assertTrue(bounce.stiffness < launch.stiffness)
    }
}
