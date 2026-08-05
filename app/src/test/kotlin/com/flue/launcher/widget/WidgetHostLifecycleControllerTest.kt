package com.flue.launcher.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetHostLifecycleControllerTest {
    @Test
    fun `cold launcher start begins widget listening immediately`() {
        var starts = 0
        var stops = 0
        val controller = WidgetHostLifecycleController(
            startListening = { starts += 1 },
            stopListening = { stops += 1 },
        )

        controller.acquire("launcher")

        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test
    fun `overlapping launcher recreation cannot stop the replacement listener`() {
        var starts = 0
        var stops = 0
        val controller = WidgetHostLifecycleController(
            startListening = { starts += 1 },
            stopListening = { stops += 1 },
        )
        val oldActivity = Any()
        val newActivity = Any()

        controller.acquire(oldActivity)
        controller.acquire(newActivity)
        controller.release(oldActivity)

        assertEquals(1, starts)
        assertEquals(0, stops)

        controller.release(newActivity)

        assertEquals(1, stops)
    }

    @Test
    fun `failed start remains retryable`() {
        var attempts = 0
        val owner = Any()
        val controller = WidgetHostLifecycleController(
            startListening = {
                attempts += 1
                if (attempts == 1) error("first start failed")
            },
            stopListening = {},
        )

        runCatching { controller.acquire(owner) }
        controller.acquire(owner)

        assertEquals(2, attempts)
    }
}
