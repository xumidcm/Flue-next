package com.flue.launcher.ui.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LegacyLayerBlurTest {
    @Test
    fun oldAndroidKeepsTheLogicalBlurClockForOpeningAndReturning() {
        val blur = Animatable(0f)
        val samples = mutableListOf<Float>()
        val clock = object : MonotonicFrameClock {
            private var nanos = 0L
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                nanos += 16_666_667L
                samples += blur.value
                return onFrame(nanos)
            }
        }
        runBlocking(clock) {
            animateLayerBlur(blur, 7f, tween(160))
            assertEquals(7f, blur.value, 0.001f)
            assertTrue(samples.any { it > 0f && it < 7f })
            samples.clear()
            animateLayerBlur(blur, 0f, tween(160))
            assertEquals(0f, blur.value, 0.001f)
            assertTrue(samples.any { it > 0f && it < 7f })
        }
    }
}
