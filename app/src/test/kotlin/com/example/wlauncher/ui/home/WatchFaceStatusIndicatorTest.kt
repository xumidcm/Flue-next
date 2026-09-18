package com.flue.launcher.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchFaceStatusIndicatorTest {
    @Test
    fun chargingHasPriorityOverDoNotDisturbAndNotifications() {
        assertEquals(
            WatchFaceStatusIndicator.CHARGING,
            watchFaceStatusIndicator(charging = true, doNotDisturb = true, hasNotifications = true),
        )
    }

    @Test
    fun doNotDisturbHidesNotifications() {
        assertEquals(
            WatchFaceStatusIndicator.DO_NOT_DISTURB,
            watchFaceStatusIndicator(charging = false, doNotDisturb = true, hasNotifications = true),
        )
    }

    @Test
    fun notificationsShowWhenDoNotDisturbIsOff() {
        assertEquals(
            WatchFaceStatusIndicator.NOTIFICATION,
            watchFaceStatusIndicator(charging = false, doNotDisturb = false, hasNotifications = true),
        )
    }

    @Test
    fun notificationsShowWhenDoNotDisturbMoonIsDisabled() {
        assertEquals(
            WatchFaceStatusIndicator.NOTIFICATION,
            watchFaceStatusIndicator(
                charging = false,
                doNotDisturb = true,
                hasNotifications = true,
                showDoNotDisturb = false,
            ),
        )
    }

    @Test
    fun globalStatusSwitchHidesEveryIndicator() {
        assertEquals(
            WatchFaceStatusIndicator.NONE,
            watchFaceStatusIndicator(
                charging = true,
                doNotDisturb = true,
                hasNotifications = true,
                showStatusIndicators = false,
            ),
        )
    }

    @Test
    fun moonProfileUsesTheReferenceUpperRightOpening() {
        val profile = doNotDisturbMoonProfile()
        assertEquals(0.08f, profile.topTipY, 0f)
        assertEquals(0.49f, profile.rightTipY, 0f)
        assertEquals(0.11f, profile.leftBulgeX, 0f)
        assertEquals(0.98f, profile.bottomBulgeY, 0f)
    }
}
