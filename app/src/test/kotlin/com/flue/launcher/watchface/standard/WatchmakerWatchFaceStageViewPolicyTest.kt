package com.flue.launcher.watchface.standard

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
}
