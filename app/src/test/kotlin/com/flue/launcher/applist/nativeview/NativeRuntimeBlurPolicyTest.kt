package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeRuntimeBlurPolicyTest {
    @Test
    fun systemBlurIsDetachedWhileNativeScrollingIsInMotion() {
        val config = NativeAppListConfig(
            blurTier = NativeBlurTier.SYSTEM,
            contentOverlayBlurRadiusPx = 8f,
        )

        assertEquals(NativeBlurTier.SYSTEM, config.effectiveBlurTier())
        assertEquals(8, nativeContentOverlayBlurBucketPx(config))

        config.runtimeBlurSuppressed = true
        assertEquals(NativeBlurTier.OFF, config.effectiveBlurTier())
        assertEquals(0, nativeContentOverlayBlurBucketPx(config))

        config.runtimeBlurSuppressed = false
        assertEquals(NativeBlurTier.SYSTEM, config.effectiveBlurTier())
        assertEquals(8, nativeContentOverlayBlurBucketPx(config))
    }

    @Test
    fun contentOverlayNeverUsesRenderEffectOutsideSystemTier() {
        assertEquals(
            0,
            nativeContentOverlayBlurBucketPx(
                NativeAppListConfig(
                    blurTier = NativeBlurTier.LITE,
                    contentOverlayBlurRadiusPx = 8f,
                ),
            ),
        )
    }
}
