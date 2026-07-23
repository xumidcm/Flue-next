package com.flue.launcher.ui.drawer

import android.os.Build
import com.flue.launcher.applist.nativeview.NativeAppListTheme
import com.flue.launcher.applist.nativeview.NativeAppListPresentation
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

    @Test
    fun actionOverlayRequestsTheLegacySixteenDpContentBlur() {
        val config = nativeListDrawerConfig(
            request(density = 2f, overlayOpen = true),
        )

        assertEquals(32f, config.contentOverlayBlurRadiusPx)
    }

    @Test
    fun carriesEveryLegacyBehaviorSwitchIntoTheNativeConfig() {
        val config = nativeListDrawerConfig(
            request(
                allowFolderCreation = false,
                iconShadowEnabled = false,
                fastFlowAnimationEnabled = true,
                edgeSpacingCompressionEnabled = false,
                entryProgress = 0.35f,
            ),
        )

        assertFalse(config.allowFolderCreation)
        assertFalse(config.iconShadowEnabled)
        assertTrue(config.fastFlowAnimationEnabled)
        assertFalse(config.edgeSpacingCompressionEnabled)
        assertEquals(0.35f, config.entryProgress)
    }

    @Test
    fun mapsAppleHoneycombColumnsAndCellGeometryWithoutLinearPaddingRules() {
        val config = nativeListDrawerConfig(
            request(
                viewportWidthPx = 320,
                viewportHeightPx = 320,
                presentation = NativeAppListPresentation.HONEYCOMB,
                honeycombNarrowColumns = 3,
            ),
        )

        assertEquals(NativeAppListPresentation.HONEYCOMB, config.presentation)
        assertEquals(69, config.iconSizePx)
        assertEquals(3, config.honeycombNarrowColumns)
        assertEquals(69f * 1.02f, config.honeycombCellSizePx, 0.001f)
    }

    @Test
    fun preservesLegacyHoneycombMinimumContentWidthForLargeSafeInsets() {
        val config = nativeListDrawerConfig(
            request(
                viewportWidthPx = 800,
                leftSafeInsetPercent = 50,
                presentation = NativeAppListPresentation.HONEYCOMB,
                honeycombNarrowColumns = 4,
            ),
        )

        assertEquals(79, config.iconSizePx)
    }

    @Test
    fun legalizesHoneycombColumnsBeforeUsingAndPublishingThem() {
        val config = nativeListDrawerConfig(
            request(
                viewportWidthPx = 320,
                presentation = NativeAppListPresentation.HONEYCOMB,
                honeycombNarrowColumns = 0,
            ),
        )

        assertEquals(1, config.honeycombNarrowColumns)
        assertEquals(84, config.iconSizePx)
    }

    @Test
    fun mapsLegacyMaterialDenseGridGeometryWithoutFastFlow() {
        val config = nativeListDrawerConfig(
            request(
                density = 2f,
                viewportWidthPx = 640,
                viewportHeightPx = 640,
                appListScalePercent = 200,
                presentation = NativeAppListPresentation.DENSE_GRID,
                honeycombNarrowColumns = 4,
                fastFlowAnimationEnabled = true,
                appleWatchIconShape = false,
            ),
        )

        // Legacy Material: ((narrow + 1) / scale).roundToInt().coerceIn(1, 10).
        assertEquals(3, config.denseGridColumns)
        assertEquals(16f, config.denseGridHorizontalPaddingPx, 0f)
        assertEquals(8f, config.denseGridItemSpacingPx, 0f)
        assertEquals(0.92f, config.denseGridItemAspectRatio, 0f)
        assertEquals(96f, config.denseGridMinimumItemWidthPx, 0f)
        assertEquals(96f, config.denseGridMinimumItemHeightPx, 0f)
        assertEquals(16f, config.denseGridMinimumVerticalPaddingPx, 0f)
        assertEquals(12f, config.denseGridIconInnerPaddingPx, 0f)
        assertFalse(config.fastFlowAnimationEnabled)
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
        overlayOpen: Boolean = false,
        rowBorderEnabled: Boolean = false,
        allowFolderCreation: Boolean = true,
        iconShadowEnabled: Boolean = true,
        fastFlowAnimationEnabled: Boolean = false,
        edgeSpacingCompressionEnabled: Boolean = true,
        entryProgress: Float = 1f,
        presentation: NativeAppListPresentation = NativeAppListPresentation.LINEAR,
        honeycombNarrowColumns: Int = 4,
        appleWatchIconShape: Boolean = true,
    ) = NativeListDrawerConfigRequest(
        density = density,
        fontScale = fontScale,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        presentation = presentation,
        honeycombNarrowColumns = honeycombNarrowColumns,
        active = true,
        initialResetToken = 3L,
        leftSafeInsetPercent = leftSafeInsetPercent,
        appListScalePercent = appListScalePercent,
        iconSizeDp = 48f,
        appleWatchIconShape = appleWatchIconShape,
        fisheyeEnabled = true,
        fisheyeRangeRows = fisheyeRangeRows,
        fisheyeStrengthPercent = fisheyeStrengthPercent,
        edgeSpacingCompressionEnabled = edgeSpacingCompressionEnabled,
        fastFlowAnimationEnabled = fastFlowAnimationEnabled,
        topFadeRangeDp = 56,
        bottomFadeRangeDp = 56,
        topBlurRadiusDp = 4f,
        bottomBlurRadiusDp = 4f,
        overlayOpen = overlayOpen,
        blurEnabled = true,
        edgeBlurEnabled = true,
        suppressHeavyEffects = suppressHeavyEffects,
        sdkInt = sdkInt,
        rotaryHapticsEnabled = true,
        darkMode = true,
        rowBorderEnabled = rowBorderEnabled,
        allowFolderCreation = allowFolderCreation,
        iconShadowEnabled = iconShadowEnabled,
        entryProgress = entryProgress,
        rowBackgroundArgb = 0xff112233L,
        labelArgb = 0xffffffffL,
        pressedArgb = 0x22ffffffL,
        fadeEdgeArgb = 0xff000000L,
    )
}
