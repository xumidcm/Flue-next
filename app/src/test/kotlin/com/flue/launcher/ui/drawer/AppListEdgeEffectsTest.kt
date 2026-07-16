package com.flue.launcher.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListEdgeEffectsTest {
    @Test
    fun edgeBlurUsesIndependentTopAndBottomZonesAndRadii() {
        assertEquals(
            5f,
            computeAppListEdgeBlur(
                centerY = 0f,
                screenHeight = 400f,
                topBlurZonePx = 100f,
                bottomBlurZonePx = 80f,
                topBlurDp = 5f,
                bottomBlurDp = 3f,
            ),
        )
        assertEquals(
            0f,
            computeAppListEdgeBlur(
                centerY = 200f,
                screenHeight = 400f,
                topBlurZonePx = 100f,
                bottomBlurZonePx = 80f,
                topBlurDp = 5f,
                bottomBlurDp = 3f,
            ),
        )
        assertEquals(
            1.5f,
            computeAppListEdgeBlur(
                centerY = 360f,
                screenHeight = 400f,
                topBlurZonePx = 100f,
                bottomBlurZonePx = 80f,
                topBlurDp = 5f,
                bottomBlurDp = 3f,
            ),
        )
    }
}
