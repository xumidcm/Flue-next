package com.flue.launcher.watchface.jbwatch

import com.flue.launcher.watchface.standard.WatchTextAnimationPhase
import com.flue.launcher.watchface.standard.WatchTextAnimationEpochs
import com.flue.launcher.watchface.standard.WatchTextAnimationTrigger
import com.flue.launcher.watchface.standard.watchmakerTickDelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JbWatchAnimationTest {
    @Test fun ambientCapsHighRefreshJbFacesAndWakePreservesLoadOnlyLoops() {
        val layer = JbWatchLayer(type = "text", animIn = "SlideLeft", animOut = "SlideRight",
            durationInSeconds = 1f, durationOnSeconds = 1f, durationOutSeconds = 1f,
            durationOffSeconds = 1f, repeatCount = -1, restartOnLoad = true, restartOnBright = false)
        assertEquals(1_000L, watchmakerTickDelay(true, jbLayerRequiresFrameTicks(layer)))
        assertEquals(33L, watchmakerTickDelay(false, jbLayerRequiresFrameTicks(layer)))
        val loaded = WatchTextAnimationEpochs(loadAtMs = 1_000)
        val awakened = loaded.triggered(WatchTextAnimationTrigger.BRIGHT, 8_000)
        val epoch = awakened.epochFor(layer.restartOnLoad, layer.restartOnBright)
        assertEquals(1_000L, epoch)
        assertEquals(jbTextAnimationFrame(layer, 7_350),
            jbTextAnimationFrame(layer, 8_350 - requireNotNull(epoch), restartEnabled = true))
        val brightOnly = layer.copy(restartOnLoad = false, restartOnBright = true)
        val brightEpoch = awakened.epochFor(brightOnly.restartOnLoad, brightOnly.restartOnBright)
        assertEquals(WatchTextAnimationPhase.ENTERING,
            jbTextAnimationFrame(brightOnly, 8_100 - requireNotNull(brightEpoch), restartEnabled = true).phase)
    }

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

    // jbLayerCandidatesKey 是绘制路径零磁盘命中的相等判据：它与 resolveJbWatchPath
    // 必须选中同一个候选文件，两边一旦漂移，缓存会命中在错误的图上。这里对齐
    // jbAnimationFrameIndex 的选择规则做锚定。
    @Test
    fun candidatesKeyMatchesResolveRulesForFrameSequences() {
        val frames = listOf("a.png", "b.png", "c.png", "d.png", "e.png")
        val layer = JbWatchLayer(
            type = "image",
            path = "a.png`b.png`c.png`d.png`e.png",
            pathFrames = frames,
            gifDelay = 70,
        )

        for (timelineMs in longArrayOf(0L, 69L, 70L, 349L, 1_774_886_400_350L)) {
            val expected = frames[
                jbAnimationFrameIndex(
                    frameCount = frames.size,
                    timelineMs = timelineMs,
                    delayMs = 70L,
                ),
            ]
            assertEquals(expected, jbLayerCandidatesKey(layer, timelineMs))
        }
    }

    @Test
    fun candidatesKeyFallsBackToDefaultDelayAndRawPath() {
        val frames = listOf("x.png", "y.png")
        val delayed = JbWatchLayer(
            type = "image",
            path = "x.png`y.png",
            pathFrames = frames,
            // gifDelay=0 时与 resolveJbWatchPath 一样退到 50ms。
            gifDelay = 0,
        )
        assertEquals("y.png", jbLayerCandidatesKey(delayed, 50L))
        assertEquals("x.png", jbLayerCandidatesKey(delayed, 49L))

        // pathFrames 为空时按原始路径本身作为唯一候选，单帧层在任何时刻指纹不变。
        val single = JbWatchLayer(type = "image", path = "images/background.png")
        assertEquals("images/background.png", jbLayerCandidatesKey(single, 0L))
        assertEquals("images/background.png", jbLayerCandidatesKey(single, 123_456L))

        assertEquals("", jbLayerCandidatesKey(JbWatchLayer(type = "image"), 0L))
    }
}
