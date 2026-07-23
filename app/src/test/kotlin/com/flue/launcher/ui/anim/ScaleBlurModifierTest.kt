package com.flue.launcher.ui.anim

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleBlurModifierTest {
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
}
