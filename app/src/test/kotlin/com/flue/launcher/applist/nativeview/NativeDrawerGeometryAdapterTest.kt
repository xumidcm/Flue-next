package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDrawerGeometryAdapterTest {
    @Test
    fun roundScreenWithOversizedIconClampsInsteadOfThrowing() {
        val adapter = NativeDrawerGeometryAdapter()
        val output = NativeSpatialChildVisual()

        val result = adapter.resolve(
            centerX = 40f,
            centerY = 40f,
            viewportWidth = 80f,
            viewportHeight = 80f,
            itemWidth = 148f,
            itemHeight = 148f,
            config = NativeAppListConfig(
                deviceShape = NativeDeviceShape.ROUND,
                fisheyeEnabled = true,
                fisheyePreset = NativeFisheyePreset.NEXT,
            ),
            output = output,
        )

        assertTrue(result.translationX.isFinite())
        assertTrue(result.translationY.isFinite())
        assertTrue(result.scale.isFinite())
    }

    @Test
    fun impossibleSafeInsetsAreReducedToAValidViewport() {
        val adapter = NativeDrawerGeometryAdapter()
        val result = adapter.resolve(
            centerX = 30f,
            centerY = 30f,
            viewportWidth = 60f,
            viewportHeight = 60f,
            itemWidth = 32f,
            itemHeight = 32f,
            config = NativeAppListConfig(
                deviceShape = NativeDeviceShape.ROUND,
                leftSafeInsetPx = 4096,
                rightSafeInsetPx = 4096,
                topSafeInsetPx = 4096,
                bottomSafeInsetPx = 4096,
            ),
            output = NativeSpatialChildVisual(),
        )

        assertTrue(result.translationX.isFinite())
        assertTrue(result.translationY.isFinite())
    }
}
