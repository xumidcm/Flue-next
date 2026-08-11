package com.flue.launcher.ui.controlcenter

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCenterStatePolicyTest {
    @Test
    fun `disabling vertical scrolling clears the stale header offset`() {
        assertEquals(0f, controlCenterHeaderScrollOffset(false, 84), 0f)
        assertEquals(84f, controlCenterHeaderScrollOffset(true, 84), 0f)
    }

    @Test
    fun `music and notification placement stays stable across swap matrix`() {
        assertEquals(
            ControlCenterCardPlacement(
                musicInControlCenter = true,
                musicOnSideScreen = false,
                notificationInControlCenter = false,
                notificationOnSideScreen = true,
            ),
            controlCenterCardPlacement(showMusic = true, showNotifications = true, swapped = false),
        )
        assertEquals(
            ControlCenterCardPlacement(
                musicInControlCenter = false,
                musicOnSideScreen = true,
                notificationInControlCenter = true,
                notificationOnSideScreen = false,
            ),
            controlCenterCardPlacement(showMusic = true, showNotifications = true, swapped = true),
        )
        assertEquals(
            ControlCenterCardPlacement(
                musicInControlCenter = false,
                musicOnSideScreen = true,
                notificationInControlCenter = false,
                notificationOnSideScreen = false,
            ),
            controlCenterCardPlacement(showMusic = true, showNotifications = false, swapped = true),
        )
    }
}
