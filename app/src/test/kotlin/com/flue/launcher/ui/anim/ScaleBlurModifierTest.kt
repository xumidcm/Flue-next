package com.flue.launcher.ui.anim

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleBlurModifierTest {
    @Test
    fun backdropKeepsTheCompleteFrameObjectUntilTheOverlayCloses() {
        val session = BackdropFrameSession<Any>()
        val first = Any()
        val late = Any()
        org.junit.Assert.assertSame(first, session.select(false, first))
        org.junit.Assert.assertSame(first, session.select(true, first))
        org.junit.Assert.assertSame(first, session.select(true, late))
        org.junit.Assert.assertSame(late, session.select(false, late))
        org.junit.Assert.assertSame(late, session.select(true, late))
    }

    @Test
    fun backdropWithoutACompleteFrameKeepsLiveContentForTheWholeGesture() {
        val session = BackdropFrameSession<Any>()
        org.junit.Assert.assertNull(session.select(true, null))
        org.junit.Assert.assertNull(session.select(true, Any()))
        val ready = Any()
        org.junit.Assert.assertSame(ready, session.select(false, ready))
    }

    @Test
    fun resizingAnOpenOverlayDiscardsItsFrameUntilTheNextSession() {
        val session = BackdropFrameSession<IntSize>()
        val original = IntSize(408, 502)
        val resized = IntSize(502, 408)
        assertEquals(original, session.select(true, original) { it == original })
        org.junit.Assert.assertNull(session.select(true, resized) { it == resized })
        org.junit.Assert.assertNull(session.select(true, original) { it == original })
        assertEquals(resized, session.select(false, resized) { it == resized })
    }

    @Test
    fun frozenContentStillSamplesTheCurrentAnimationFrame() {
        val frame = LayerFrameSnapshot()
        var current = LayerAnimValues()
        frame.bind { current }
        current = LayerAnimValues(scale = 0.92f, translationY = 0.03f)
        // No graphicsLayer draw/update occurs while its content is frozen.
        assertEquals(0.92f, frame.scale, 0f)
        assertEquals(0.03f, frame.translationY, 0f)
        assertTrue(frame.backdropProgress() > 0f)
        current = LayerAnimValues()
        assertEquals(0f, frame.backdropProgress(), 0f)
    }

    @Test
    fun preparedBackdropRecordingIsReusedUntilItsVisualInputsChange() {
        val state = PreparedBackdropRecordState()

        assertTrue(
            state.shouldRecord(
                width = 408,
                height = 502,
                frameGeneration = 7L,
                scale = 1.22f,
                translationYPx = 0f,
            ),
        )
        assertFalse(
            state.shouldRecord(
                width = 408,
                height = 502,
                frameGeneration = 7L,
                scale = 1.22f,
                translationYPx = 0f,
            ),
        )
        assertTrue(
            state.shouldRecord(
                width = 408,
                height = 502,
                frameGeneration = 8L,
                scale = 1.22f,
                translationYPx = 0f,
            ),
        )
        assertTrue(
            state.shouldRecord(
                width = 408,
                height = 502,
                frameGeneration = 8L,
                scale = 1.20f,
                translationYPx = 3f,
            ),
        )
    }

    @Test
    fun liveBackdropContentIsSkippedOnlyWhenOverlayHasCapturedFrame() {
        assertFalse(
            shouldDrawLiveBackdropContent(
                overlayActive = true,
                capturedFrameAvailable = true,
            ),
        )
        assertTrue(
            shouldDrawLiveBackdropContent(
                overlayActive = true,
                capturedFrameAvailable = false,
            ),
        )
        assertTrue(
            shouldDrawLiveBackdropContent(
                overlayActive = false,
                capturedFrameAvailable = true,
            ),
        )
    }

    @Test
    fun hardwareCaptureKeepsThePausedFaceVisibleWhileTheFrozenFrameIsPending() {
        assertTrue(
            shouldDrawLiveBackdropContent(
                overlayActive = true,
                capturedFrameAvailable = false,
            ),
        )
    }

    @Test
    fun frozenOverlayKeepsUsingTheCapturedFrameWhenGeometryProgressReturnsToRest() {
        assertEquals(
            BackdropContentSource.CAPTURED,
            resolveBackdropContentSource(
                overlayActive = true,
                hardwareCaptureRequired = true,
                capturedFrameAvailable = true,
                cachedFrameAvailable = true,
            ),
        )
        assertEquals(
            BackdropContentSource.LIVE,
            resolveBackdropContentSource(
                overlayActive = false,
                hardwareCaptureRequired = false,
                capturedFrameAvailable = true,
                cachedFrameAvailable = true,
            ),
        )
    }

    @Test
    fun pixelCopyResultIsRejectedAfterAnOverlayHasStartedDrawing() {
        assertTrue(shouldAcceptWatchFaceCapture(captureAllowedNow = true))
        assertFalse(shouldAcceptWatchFaceCapture(captureAllowedNow = false))
    }

    @Test
    fun android12FallsBackToLegacyBlurWhenAdvancedMaterialIsDisabled() {
        assertEquals(OverlayBlurBackend.LEGACY, overlayBlurBackend(30, advancedMaterialEnabled = true))
        assertEquals(OverlayBlurBackend.SYSTEM, overlayBlurBackend(31, advancedMaterialEnabled = true))
        assertEquals(OverlayBlurBackend.LEGACY, overlayBlurBackend(31, advancedMaterialEnabled = false))
        assertEquals(OverlayBlurBackend.LEGACY, overlayBlurBackend(35, advancedMaterialEnabled = false))
    }

    @Test
    fun legacyPreparedBlurUsesReducedThreePassPolicy() {
        assertEquals(12, LEGACY_BACKDROP_BLUR_DOWNSAMPLE_DIVISOR)
        assertEquals(3, LEGACY_BACKDROP_BLUR_PASSES)
        assertEquals(6, LEGACY_BACKDROP_BLUR_RADIUS)
    }

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
        assertEquals(0.28375f, legacyBackdropBlend(0.25f).blurredAlpha, 0.0001f)
        assertEquals(0.545f, legacyBackdropBlend(0.5f).blurredAlpha, 0.0001f)
        assertEquals(LegacyBackdropBlend(1f, 1f), legacyBackdropBlend(1f))
        assertEquals(LegacyBackdropBlend(1f, 1f), legacyBackdropBlend(2f))

        for (step in 0..20) {
            val blend = legacyBackdropBlend(step / 20f)
            assertEquals(1f, blend.clearAlpha, 0.0001f)
            val p = step / 20f
            val expected = p * (1.18f - 0.18f * p)
            assertEquals(expected, blend.blurredAlpha, 0.0001f)
            if (p in 0.0001f..0.9999f) {
                assertTrue(blend.blurredAlpha > p)
            }
        }
    }

    @Test
    fun legacyBackdropRejectsCacheFromDifferentMeasuredViewport() {
        assertTrue(legacyBackdropViewportMatches(454, 454, IntSize(454, 454)))
        assertFalse(legacyBackdropViewportMatches(454, 454, IntSize(450, 454)))
        assertFalse(legacyBackdropViewportMatches(454, 454, IntSize(454, 450)))
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
