package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppListPerformancePolicyTest {
    @Test
    fun api27To30NeverRunsPerIconConvolutionInTheScrollPath() {
        assertEquals(
            NativeBlurTier.LITE,
            selectNativeBlurTier(
                sdkInt = 30,
                blurEnabled = true,
                edgeBlurEnabled = true,
                suppressHeavyEffects = false,
            ),
        )
    }

    @Test
    fun api31AndAboveUseCachedSystemBlurBuckets() {
        assertEquals(
            NativeBlurTier.SYSTEM,
            selectNativeBlurTier(
                sdkInt = 31,
                blurEnabled = true,
                edgeBlurEnabled = true,
                suppressHeavyEffects = false,
            ),
        )
    }

    @Test
    fun fastScrollAndDisabledEffectsForceBlurOff() {
        assertEquals(
            NativeBlurTier.OFF,
            selectNativeBlurTier(
                sdkInt = 35,
                blurEnabled = true,
                edgeBlurEnabled = true,
                suppressHeavyEffects = true,
            ),
        )
        assertEquals(
            NativeBlurTier.OFF,
            selectNativeBlurTier(
                sdkInt = 35,
                blurEnabled = false,
                edgeBlurEnabled = true,
                suppressHeavyEffects = false,
            ),
        )
    }

    @Test
    fun configCarriesTheNativeRuntimePolicyWithoutAndroidFrameworkTypes() {
        val config = NativeAppListConfig(
            active = true,
            leftSafeInsetPx = 12,
            appScale = 0.9f,
            fisheyeEnabled = true,
            fisheyeRange = 0.4f,
            fisheyeStrength = 0.75f,
            blurTier = NativeBlurTier.LITE,
            rotaryHapticsEnabled = false,
            theme = NativeAppListTheme.DARK,
            rowBorder = NativeAppListRowBorder(
                enabled = true,
                colorArgb = 0x44ffffff,
                widthPx = 1f,
                cornerRadiusPx = 8f,
            ),
        )

        assertEquals(12, config.leftSafeInsetPx)
        assertEquals(0.9f, config.appScale, 0f)
        assertFalse(config.rotaryHapticsEnabled)
        assertTrue(config.rowBorder.enabled)
    }

    @Test
    fun configLegalizationRemovesInvalidAndNegativeDrawingMetrics() {
        val legalized = NativeAppListConfig(
            initialResetToken = -9L,
            leftSafeInsetPx = -1,
            rowHeightPx = -10,
            iconSizePx = -20,
            rowPaddingStartPx = -3,
            rowPaddingTopPx = -4,
            rowPaddingEndPx = -5,
            rowPaddingBottomPx = -6,
            labelTextSizePx = Float.NaN,
            iconLabelGapPx = -7,
            appScale = Float.NaN,
            fisheyeRange = Float.POSITIVE_INFINITY,
            fisheyeStrength = -1f,
            rowBorder = NativeAppListRowBorder(
                enabled = true,
                widthPx = Float.NaN,
                cornerRadiusPx = -8f,
            ),
        ).legalized()

        assertEquals(0L, legalized.initialResetToken)
        assertEquals(0, legalized.leftSafeInsetPx)
        assertTrue(legalized.rowHeightPx > 0)
        assertTrue(legalized.iconSizePx > 0)
        assertEquals(0, legalized.rowPaddingStartPx)
        assertEquals(0, legalized.rowPaddingTopPx)
        assertEquals(0, legalized.rowPaddingEndPx)
        assertEquals(0, legalized.rowPaddingBottomPx)
        assertTrue(legalized.labelTextSizePx.isFinite())
        assertTrue(legalized.labelTextSizePx > 0f)
        assertEquals(0, legalized.iconLabelGapPx)
        assertTrue(legalized.appScale.isFinite())
        assertTrue(legalized.appScale > 0f)
        assertTrue(legalized.fisheyeRange in 0.01f..1f)
        assertTrue(legalized.fisheyeStrength in 0f..1f)
        assertEquals(0f, legalized.rowBorder.widthPx, 0f)
        assertEquals(0f, legalized.rowBorder.cornerRadiusPx, 0f)
    }

    @Test
    fun nativeChildVisualKeepsTheCenterSharpAndShrinksOnlyAtTheEdge() {
        val config = NativeAppListConfig(
            appScale = 0.9f,
            fisheyeEnabled = true,
            fisheyeRange = 0.4f,
            fisheyeStrength = 1f,
        )

        val center = nativeLinearChildVisual(
            childCenterY = 120f,
            viewportHeight = 240,
            config = config,
        )
        val edge = nativeLinearChildVisual(
            childCenterY = 0f,
            viewportHeight = 240,
            config = config,
        )

        assertEquals(0.9f, center.scale, 0f)
        assertEquals(1f, center.alpha, 0f)
        assertTrue(edge.scale < center.scale)
        assertTrue(edge.alpha < center.alpha)
    }

    @Test
    fun disabledOrInvalidGeometryUsesOnlyTheConfiguredBaseScale() {
        val inactive = NativeAppListConfig(
            active = false,
            appScale = 0.8f,
            fisheyeEnabled = true,
            fisheyeRange = 1f,
            fisheyeStrength = 1f,
        )

        assertEquals(
            NativeLinearChildVisual(scale = 0.8f, alpha = 1f),
            nativeLinearChildVisual(
                childCenterY = Float.NaN,
                viewportHeight = 0,
                config = inactive,
            ),
        )
    }
}
