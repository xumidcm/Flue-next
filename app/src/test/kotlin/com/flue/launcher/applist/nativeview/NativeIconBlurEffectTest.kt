package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeIconBlurEffectTest {
    @Test
    fun scrollRadiusUsesFixedBucketsInsteadOfCreatingContinuousEffects() {
        assertEquals(0, quantizeNativeBlurRadiusPx(Float.NaN))
        assertEquals(0, quantizeNativeBlurRadiusPx(0.9f))
        assertEquals(4, quantizeNativeBlurRadiusPx(5.9f))
        assertEquals(8, quantizeNativeBlurRadiusPx(9.9f))
        assertEquals(64, quantizeNativeBlurRadiusPx(200f))
    }

    @Test
    fun systemRenderEffectsAreSharedByRadiusBucket() {
        val first = NativeSystemBlurEffectCache.get(8)
        val second = NativeSystemBlurEffectCache.get(8)

        assertSame(first, second)
    }
}
