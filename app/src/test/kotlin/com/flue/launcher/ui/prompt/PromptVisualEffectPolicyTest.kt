package com.flue.launcher.ui.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptVisualEffectPolicyTest {
    @Test
    fun `api 31 uses render effect and advanced material enables text reveal`() {
        assertEquals(
            PromptVisualEffectPolicy(
                background = PromptBackgroundEffect.SYSTEM_RENDER_EFFECT,
                text = PromptTextEffect.BLUR_RISE,
                enterDurationMillis = 360,
                exitDurationMillis = 240,
            ),
            promptVisualEffectPolicy(sdkInt = 31, advancedMaterial = true, reduceMotion = false),
        )
    }

    @Test
    fun `background blur does not depend on advanced material`() {
        val policy = promptVisualEffectPolicy(35, advancedMaterial = false, reduceMotion = false)
        assertEquals(
            PromptBackgroundEffect.SYSTEM_RENDER_EFFECT,
            policy.background,
        )
        assertEquals(
            PromptTextEffect.FADE,
            policy.text,
        )
        assertEquals(360, policy.enterDurationMillis)
        assertEquals(240, policy.exitDurationMillis)
    }

    @Test
    fun `old android uses pixel copy with a plain fade`() {
        val policy = promptVisualEffectPolicy(30, advancedMaterial = true, reduceMotion = false)
        assertEquals(PromptBackgroundEffect.PIXEL_COPY, policy.background)
        assertEquals(PromptTextEffect.FADE, policy.text)
    }

    @Test
    fun `reduced motion keeps blur and uses a short fade`() {
        val modern = promptVisualEffectPolicy(31, advancedMaterial = true, reduceMotion = true)
        val legacy = promptVisualEffectPolicy(29, advancedMaterial = true, reduceMotion = true)

        assertEquals(PromptBackgroundEffect.SYSTEM_RENDER_EFFECT, modern.background)
        assertEquals(PromptBackgroundEffect.PIXEL_COPY, legacy.background)
        assertEquals(PromptTextEffect.FADE, modern.text)
        assertEquals(100, modern.enterDurationMillis)
        assertEquals(100, legacy.enterDurationMillis)
        assertEquals(100, modern.exitDurationMillis)
        assertEquals(100, legacy.exitDurationMillis)
    }
}
