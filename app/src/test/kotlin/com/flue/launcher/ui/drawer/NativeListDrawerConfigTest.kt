package com.flue.launcher.ui.drawer

import android.os.Build
import com.flue.launcher.applist.nativeview.NativeAppListTheme
import com.flue.launcher.applist.nativeview.NativeBlurTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeListDrawerConfigTest {
    @Test
    fun mapsComposeListGeometryAndSettingsWithoutDoubleScaling() {
        val config = nativeListDrawerConfig(
            request(
                density = 2f,
                viewportWidthPx = 400,
                viewportHeightPx = 800,
                leftSafeInsetPercent = 10,
                appListScalePercent = 125,
                fisheyeRangeRows = 2,
                fisheyeStrengthPercent = 75,
                rowBorderEnabled = true,
            ),
        )

        assertEquals(40, config.leftSafeInsetPx)
        assertEquals(120, config.iconSizePx)
        assertEquals(170, config.rowHeightPx)
        assertEquals(64, config.rowPaddingStartPx)
        assertEquals(25, config.rowPaddingTopPx)
        assertEquals(35, config.iconLabelGapPx)
        assertEquals(1f, config.appScale)
        assertEquals(0.85f, config.fisheyeRange)
        assertEquals(0.75f, config.fisheyeStrength)
        assertTrue(config.centerEdgeItems)
        assertTrue(config.rowBorder.enabled)
        assertEquals(NativeAppListTheme.DARK, config.theme)
    }

    @Test
    fun choosesVersionedBlurTierAndHonorsTheHeavyEffectGate() {
        assertEquals(
            NativeBlurTier.SYSTEM,
            nativeListDrawerConfig(request(sdkInt = Build.VERSION_CODES.S)).blurTier,
        )
        assertEquals(
            NativeBlurTier.LITE,
            nativeListDrawerConfig(request(sdkInt = Build.VERSION_CODES.R)).blurTier,
        )
        val suppressed = nativeListDrawerConfig(
            request(sdkInt = Build.VERSION_CODES.S, suppressHeavyEffects = true),
        )
        assertEquals(NativeBlurTier.OFF, suppressed.blurTier)
        assertFalse(suppressed.rowBorder.enabled)
    }

    @Test
    fun preservesTheFullLegacyFisheyeStrengthRange() {
        val config = nativeListDrawerConfig(request(fisheyeStrengthPercent = 175))

        assertEquals(1.75f, config.fisheyeStrength)
    }

    @Test
    fun preservesLegacyMinimumGeometryAndUsesSystemFontScale() {
        val config = nativeListDrawerConfig(
            request(density = 2f, fontScale = 1.25f, appListScalePercent = 50),
        )

        assertEquals(48, config.iconSizePx)
        assertEquals(68, config.rowHeightPx)
        assertEquals(44, config.rowPaddingStartPx)
        assertEquals(10, config.rowPaddingTopPx)
        assertEquals(16, config.iconLabelGapPx)
        assertEquals(25f, config.labelTextSizePx)
    }

    private fun request(
        density: Float = 1f,
        fontScale: Float = 1f,
        viewportWidthPx: Int = 400,
        viewportHeightPx: Int = 400,
        leftSafeInsetPercent: Int = 0,
        appListScalePercent: Int = 100,
        fisheyeRangeRows: Int = 4,
        fisheyeStrengthPercent: Int = 100,
        sdkInt: Int = Build.VERSION_CODES.S,
        suppressHeavyEffects: Boolean = false,
        rowBorderEnabled: Boolean = false,
    ) = NativeListDrawerConfigRequest(
        density = density,
        fontScale = fontScale,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        active = true,
        initialResetToken = 3L,
        leftSafeInsetPercent = leftSafeInsetPercent,
        appListScalePercent = appListScalePercent,
        iconSizeDp = 48f,
        fisheyeEnabled = true,
        fisheyeRangeRows = fisheyeRangeRows,
        fisheyeStrengthPercent = fisheyeStrengthPercent,
        topFadeRangeDp = 56,
        bottomFadeRangeDp = 56,
        topBlurRadiusDp = 4f,
        bottomBlurRadiusDp = 4f,
        blurEnabled = true,
        edgeBlurEnabled = true,
        suppressHeavyEffects = suppressHeavyEffects,
        sdkInt = sdkInt,
        rotaryHapticsEnabled = true,
        darkMode = true,
        rowBorderEnabled = rowBorderEnabled,
        rowBackgroundArgb = 0xff112233L,
        labelArgb = 0xffffffffL,
        pressedArgb = 0x22ffffffL,
    )
}
