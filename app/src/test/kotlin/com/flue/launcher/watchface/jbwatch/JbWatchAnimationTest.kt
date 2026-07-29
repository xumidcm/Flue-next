package com.flue.launcher.watchface.jbwatch

import com.flue.launcher.watchface.standard.WatchTextAnimationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JbWatchAnimationTest {
    @Test
    fun epochSizedTimelineDoesNotOverflowAndLockTheFirstFrame() {
        val delayMs = 70L
        val firstTimeline = 1_774_886_400_000L
        val firstIndex = jbAnimationFrameIndex(
            frameCount = 5,
            timelineMs = firstTimeline,
            delayMs = delayMs,
        )

        assertEquals((firstTimeline / delayMs % 5L).toInt(), firstIndex)
        assertEquals(
            (firstIndex + 1) % 5,
            jbAnimationFrameIndex(
                frameCount = 5,
                timelineMs = firstTimeline + delayMs,
                delayMs = delayMs,
            ),
        )
    }

    @Test
    fun plainImageWithMultipleFramesStillRequestsFastTicks() {
        assertTrue(
            jbLayerRequiresFrameTicks(
                JbWatchLayer(
                    type = "image",
                    path = "a.png`b.png",
                    pathFrames = listOf("a.png", "b.png"),
                ),
            ),
        )
        assertTrue(
            jbLayerRequiresFrameTicks(
                JbWatchLayer(type = "image", path = "background.gif"),
            ),
        )
        assertTrue(
            jbLayerRequiresFrameTicks(
                JbWatchLayer(
                    type = "text",
                    xExpression = "{c_-120_120_4_rv}",
                    text = "loop",
                ),
            ),
        )
        assertFalse(
            jbLayerRequiresFrameTicks(
                JbWatchLayer(
                    type = "image",
                    path = "background.png",
                    pathFrames = listOf("background.png"),
                ),
            ),
        )
    }

    @Test
    fun jbTextAnimationUsesSharedTimelineAndMinusOneRepeatsForever() {
        val layer = JbWatchLayer(
            type = "text",
            text = "loop",
            animIn = "SlideLeft",
            animOut = "SlideLeft",
            durationInSeconds = 1f,
            durationOnSeconds = 1f,
            durationOutSeconds = 1f,
            durationOffSeconds = 0.5f,
            repeatCount = -1,
            restartOnLoad = true,
        )

        val firstCycle = jbTextAnimationFrame(layer, elapsedMs = 0L)
        val secondCycle = jbTextAnimationFrame(layer, elapsedMs = 3_500L)

        assertEquals(WatchTextAnimationPhase.ENTERING, firstCycle.phase)
        assertTrue(firstCycle.translationX > 0f)
        assertEquals(WatchTextAnimationPhase.ENTERING, secondCycle.phase)
        assertTrue(jbLayerRequiresFrameTicks(layer))
    }
}
