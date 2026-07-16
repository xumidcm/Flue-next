package com.flue.launcher.ui.anim

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleBlurModifierTest {
    @Test
    fun transformOriginStaysAtTheLaunchIconUntilAnExplicitOriginReplacesIt() {
        val latch = LayerTransformOriginLatch()

        assertEquals(Offset(0.5f, 0.5f), latch.resolve(null))
        assertEquals(Offset(0.2f, 0.7f), latch.resolve(Offset(0.2f, 0.7f)))
        assertEquals(Offset(0.2f, 0.7f), latch.resolve(null))
        assertEquals(Offset(0.5f, 0.5f), latch.resolve(Offset(0.5f, 0.5f)))
    }

    @Test
    fun invalidTransformOriginCannotPoisonTheRetainedPivot() {
        val latch = LayerTransformOriginLatch()

        latch.resolve(Offset(0.3f, 0.6f))

        assertEquals(Offset(0.3f, 0.6f), latch.resolve(Offset(Float.NaN, 0.4f)))
    }
}
