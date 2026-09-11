package com.flue.launcher.watchface.standard

import org.junit.Assert.*
import org.junit.Test

class WatchTextAnimationTriggerTest {
    @Test fun loadAndBrightFlagsAreIndependentForAllFourCombinations() {
        for (load in listOf(false, true)) for (bright in listOf(false, true)) {
            val attrs = mapOf("restart_on_load" to if (load) "Y" else "N",
                "restart_on_bright" to if (bright) "Y" else "N")
            assertEquals(load, watchTextAnimationRestarts(attrs, WatchTextAnimationTrigger.LOAD))
            assertEquals(bright, watchTextAnimationRestarts(attrs, WatchTextAnimationTrigger.BRIGHT))
        }
    }

    @Test fun rejectedBrightRestartKeepsAnInfiniteLoadTimelineRunning() {
        val attributes = mapOf("restart_on_load" to "Y", "restart_on_bright" to "N")
        val loaded = WatchTextAnimationEpochs().triggered(WatchTextAnimationTrigger.LOAD, 1_000)
        val awakened = loaded.triggered(WatchTextAnimationTrigger.BRIGHT, 8_250)
        assertEquals(1_000L, awakened.epochFor(attributes))
        val frameBeforeWake = frame(loaded.epochFor(attributes), 8_350)
        val frameAfterWake = frame(awakened.epochFor(attributes), 8_350)
        assertEquals(frameBeforeWake, frameAfterWake)
        assertNotEquals(WatchTextAnimationPhase.STATIC, frameAfterWake.phase)
        assertNotEquals(frame(8_250, 8_350), frameAfterWake)
    }

    @Test fun brightOnlyLayerStartsAtFirstBrightAndRestartsOnlyOnLaterBright() {
        val attrs = mapOf("restart_on_load" to "N", "restart_on_bright" to "Y")
        val loaded = WatchTextAnimationEpochs().triggered(WatchTextAnimationTrigger.LOAD, 1_000)
        assertNull(loaded.epochFor(attrs))
        assertEquals(WatchTextAnimationPhase.STATIC, frame(loaded.epochFor(attrs), 2_000).phase)
        val firstWake = loaded.triggered(WatchTextAnimationTrigger.BRIGHT, 3_000)
        assertEquals(3_000L, firstWake.epochFor(attrs))
        assertEquals(WatchTextAnimationPhase.ENTERING, frame(firstWake.epochFor(attrs), 3_100).phase)
        assertEquals(8_000L, firstWake.triggered(WatchTextAnimationTrigger.BRIGHT, 8_000).epochFor(attrs))
    }

    @Test fun sourceReplacementClearsOldBrightWhileBorrowedSegmentsKeepBothEpochs() {
        val brightOnly = mapOf("restart_on_load" to "N", "restart_on_bright" to "Y")
        val both = mapOf("restart_on_load" to "Y", "restart_on_bright" to "Y")
        val primary = WatchTextAnimationEpochs().triggered(WatchTextAnimationTrigger.LOAD, 10)
            .triggered(WatchTextAnimationTrigger.BRIGHT, 40)
        val borrowed = primary.copy()
        assertEquals(primary.epochFor(both), borrowed.epochFor(both))
        val replaced = borrowed.triggered(WatchTextAnimationTrigger.LOAD, 70)
        assertNull(replaced.epochFor(brightOnly))
        assertEquals(70L, replaced.epochFor(both))
    }

    private fun frame(epoch: Long?, now: Long) = watchTextAnimationFrame(
        animIn = "SlideLeft", animOut = "SlideRight", durationInSeconds = 1f, durationOnSeconds = 1f,
        durationOutSeconds = 1f, durationOffSeconds = 1f, delayStartSeconds = 0f,
        repeatCount = -1, restartOnLoad = epoch != null, elapsedMs = epoch?.let { now - it } ?: 0L)
}
