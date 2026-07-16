package com.flue.launcher.ui.anim

import org.junit.Assert.assertEquals
import org.junit.Test

class BlurSupportPolicyTest {
    @Test
    fun systemBlurUsesStableFloorBucketsInsteadOfPerFrameTenths() {
        assertEquals(0f, quantizeSystemBlurRadiusDp(Float.NaN))
        assertEquals(0f, quantizeSystemBlurRadiusDp(0.4f))
        assertEquals(0.5f, quantizeSystemBlurRadiusDp(0.9f))
        assertEquals(1.5f, quantizeSystemBlurRadiusDp(1.9f))
        assertEquals(5f, quantizeSystemBlurRadiusDp(5f))
        assertEquals(8f, quantizeSystemBlurRadiusDp(9.9f))
        assertEquals(16f, quantizeSystemBlurRadiusDp(100f))
    }

    @Test
    fun legacyCaptureBlurIsOptInAndNeverTheDefaultHotPath() {
        assertEquals(
            PlatformBlurBackend.NONE,
            selectPlatformBlurBackend(
                sdkInt = 30,
                enabled = true,
                allowLegacyCaptureBlur = false,
            ),
        )
        assertEquals(
            PlatformBlurBackend.LEGACY_CAPTURE,
            selectPlatformBlurBackend(
                sdkInt = 30,
                enabled = true,
                allowLegacyCaptureBlur = true,
            ),
        )
        assertEquals(
            PlatformBlurBackend.SYSTEM_RENDER_EFFECT,
            selectPlatformBlurBackend(
                sdkInt = 31,
                enabled = true,
                allowLegacyCaptureBlur = false,
            ),
        )
        assertEquals(
            PlatformBlurBackend.NONE,
            selectPlatformBlurBackend(
                sdkInt = 36,
                enabled = false,
                allowLegacyCaptureBlur = true,
            ),
        )
    }
}
