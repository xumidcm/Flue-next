package com.flue.launcher.watchface.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStoreInstallStateTest {
    @Test
    fun storeInstallUsesRoundShapeOnlyForExplicitRoundLabel() {
        assertEquals(true, WatchStoreScreenType.ROUND.installAsRound())
        assertEquals(false, WatchStoreScreenType.SQUARE.installAsRound())
        assertEquals(false, WatchStoreScreenType.RECTANGULAR.installAsRound())
        assertEquals(false, WatchStoreScreenType.UNKNOWN.installAsRound())
    }

    @Test
    fun `failed install keeps retry context and retry starts a new download attempt`() {
        val failure = WatchStoreInstallState.Failed(
            message = "网络断开",
            failedAtEpochMs = 1_000L,
            downloadedBytes = 256L,
            attempt = 1,
        )

        val retry = failure.retry()

        assertEquals(WatchStoreInstallState.Downloading(bytes = 0L, totalBytes = null, attempt = 2), retry)
    }

    @Test
    fun `download progress throttle emits at most ten intermediate updates per second`() {
        var nowNanos = 0L
        val throttle = WatchStoreProgressThrottle(clockNanos = { nowNanos })

        assertTrue(throttle.shouldEmit(bytes = 1L, totalBytes = 100L))
        nowNanos = 50_000_000L
        assertFalse(throttle.shouldEmit(bytes = 2L, totalBytes = 100L))
        nowNanos = 100_000_000L
        assertTrue(throttle.shouldEmit(bytes = 3L, totalBytes = 100L))
        nowNanos = 101_000_000L
        assertTrue(throttle.shouldEmit(bytes = 100L, totalBytes = 100L))
    }
}
