package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchmakerWatchFaceStageViewPolicyTest {
    @Test
    fun `video layers route stage touches to the primary gesture view`() {
        assertTrue(shouldRouteStageTouchToPrimary(videoLayerCount = 1))
        assertTrue(shouldRouteStageTouchToPrimary(videoLayerCount = 2))
    }

    @Test
    fun `plain watch face keeps normal child dispatch`() {
        assertFalse(shouldRouteStageTouchToPrimary(videoLayerCount = 0))
    }

    @Test
    fun `unchanged video signature still refreshes borrowed renderer state`() {
        assertTrue(
            shouldRefreshBorrowedSegmentState(
                appliedSignature = "4:C:/face/video.mp4",
                nextSignature = "4:C:/face/video.mp4",
                videoLayerCount = 1,
            ),
        )
    }

    @Test
    fun `transparent scripted shape is still rendered for hit registration`() {
        assertTrue(
            shouldRenderWatchLayer(
                alpha = 0f,
                isDisplayed = true,
                hasTapAction = true,
            ),
        )
        assertFalse(
            shouldRenderWatchLayer(
                alpha = 0f,
                isDisplayed = true,
                hasTapAction = false,
            ),
        )
        assertFalse(
            shouldRenderWatchLayer(
                alpha = 1f,
                isDisplayed = false,
                hasTapAction = true,
            ),
        )
    }

    @Test
    fun `lua callback cannot skip slow tag polling`() {
        var slowTagPollCount = 0

        val shouldRedraw = evaluateWatchFaceTickRedraw(luaCallbackRan = true) {
            slowTagPollCount += 1
            false
        }

        assertTrue(shouldRedraw)
        assertEquals(1, slowTagPollCount)
    }

    @Test
    fun `script tap creates a face sandbox even without a global script file`() {
        assertTrue(
            watchFaceNeedsLuaRuntime(
                initialScript = null,
                layers = listOf(
                    WatchmakerLayer(
                        id = "tap",
                        type = "shape",
                        parentId = null,
                        attributes = mapOf("action" to "script:var_tapped = 1"),
                        children = emptyList(),
                    ),
                ),
            ),
        )
        assertFalse(
            watchFaceNeedsLuaRuntime(
                initialScript = null,
                layers = listOf(
                    WatchmakerLayer(
                        id = "plain",
                        type = "shape",
                        parentId = null,
                        attributes = emptyMap(),
                        children = emptyList(),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `rounded buttons and text rings expose rectangular tap bounds`() {
        assertEquals(
            WatchTapBox(-42f, -17f, 42f, 17f),
            watchLayerTapBox("rounded", width = 84f, height = 34f),
        )
        assertEquals(
            WatchTapBox(-215f, -215f, 215f, 215f),
            watchLayerTapBox("text_ring", width = 430f, height = 430f),
        )
    }
}
