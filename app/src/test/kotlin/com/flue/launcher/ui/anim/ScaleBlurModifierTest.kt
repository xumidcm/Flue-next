package com.flue.launcher.ui.anim

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleBlurModifierTest {
    @Test
    fun backdropBlurProgressUsesStableTwoPixelBuckets() {
        assertEquals(0, quantizeBackdropBlurRadiusPx(Float.NaN))
        assertEquals(0, quantizeBackdropBlurRadiusPx(0f))
        assertEquals(2, quantizeBackdropBlurRadiusPx(0.01f))
        assertEquals(16, quantizeBackdropBlurRadiusPx(0.25f))
        assertEquals(32, quantizeBackdropBlurRadiusPx(0.50f))
        assertEquals(64, quantizeBackdropBlurRadiusPx(1f))
        assertEquals(64, quantizeBackdropBlurRadiusPx(2f))
    }

    @Test
    fun legacyBackdropCrossfadesClearAndPreblurredFramesWithoutAlphaJump() {
        assertEquals(LegacyBackdropBlend(1f, 0f), legacyBackdropBlend(Float.NaN))
        assertEquals(LegacyBackdropBlend(1f, 0f), legacyBackdropBlend(0f))
        assertEquals(LegacyBackdropBlend(1f, 0.25f), legacyBackdropBlend(0.25f))
        assertEquals(LegacyBackdropBlend(1f, 1f), legacyBackdropBlend(1f))
        assertEquals(LegacyBackdropBlend(1f, 1f), legacyBackdropBlend(2f))

        for (step in 0..20) {
            val blend = legacyBackdropBlend(step / 20f)
            assertEquals(1f, blend.clearAlpha, 0.0001f)
            assertEquals(step / 20f, blend.blurredAlpha, 0.0001f)
        }
    }

    @Test
    fun explicitCenterAndLaunchIconResolveDeterministically() {
        assertEquals(Offset(0.5f, 0.5f), LayerPivot.Center.resolve())
        assertEquals(
            Offset(0.2f, 0.7f),
            LayerPivot.LaunchIcon(Offset(0.2f, 0.7f)).resolve(),
        )
        assertEquals(Offset(0.5f, 0.5f), LayerPivot.Center.resolve())
    }

    @Test
    fun transientMissingOriginKeepsTheLaunchPivotUntilAnExplicitReset() {
        val latch = LayerPivotLatch()
        val launchOrigin = Offset(0.2f, 0.7f)

        assertEquals(launchOrigin, latch.resolve(LayerPivot.LaunchIcon(launchOrigin)))
        assertEquals(launchOrigin, latch.resolve(null))
        assertEquals(Offset(0.5f, 0.5f), latch.resolve(LayerPivot.Center))
    }

    @Test
    fun invalidLaunchIconOriginFallsBackToCenter() {
        assertEquals(
            Offset(0.5f, 0.5f),
            LayerPivot.LaunchIcon(Offset(Float.NaN, 0.4f)).resolve(),
        )
    }

    @Test
    fun invisibleOrUnsupportedLayersDoNotRetainFullScreenBlur() {
        assertFalse(shouldRenderLayerBlur(31, true, 0f, 8))
        assertFalse(shouldRenderLayerBlur(31, true, 0.01f, 8))
        assertFalse(shouldRenderLayerBlur(31, true, Float.NaN, 8))
        assertFalse(shouldRenderLayerBlur(30, true, 1f, 8))
        assertFalse(shouldRenderLayerBlur(31, false, 1f, 8))
        assertFalse(shouldRenderLayerBlur(31, true, 1f, 0))
        assertTrue(shouldRenderLayerBlur(31, true, 0.011f, 8))
    }

    @Test
    fun backdropFrameActivatesForRealShrinkOrTranslationOnly() {
        val frame = LayerFrameSnapshot()
        frame.update(
            scale = 1f,
            blur = 24f,
            alpha = 0.2f,
            translationX = 0f,
            translationY = 0f,
        )
        assertEquals(0f, frame.backdropProgress(), 0f)

        frame.update(
            scale = 0.925f,
            blur = 12f,
            alpha = 0.5f,
            translationX = 0f,
            translationY = 0.035f,
        )
        assertEquals(0.5f, frame.backdropProgress(), 0.0001f)

        frame.update(
            scale = 0.85f,
            blur = 24f,
            alpha = 0f,
            translationX = 0f,
            translationY = 0.07f,
        )
        assertEquals(1f, frame.backdropProgress(), 0f)
    }
}
