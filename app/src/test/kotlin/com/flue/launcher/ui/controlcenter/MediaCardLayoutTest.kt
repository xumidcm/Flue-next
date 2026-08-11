package com.flue.launcher.ui.controlcenter

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCardLayoutTest {
    @Test
    fun `side-screen music card matches the full control-center layout`() {
        val sideScreen = sideScreenMediaCardLayoutSpec()
        val full = mediaCardLayoutSpec(compact = false)

        assertEquals(full, sideScreen)
        assertEquals(156, sideScreen.cardHeightDp)
        assertEquals(132, sideScreen.artworkSizeDp)
    }

    @Test
    fun `side screen allows vertical scrolling when a card can extend past the viewport`() {
        assertEquals(true, sideScreenContentAllowsVerticalScroll(showMusicCard = true, showNotificationPreview = false))
        assertEquals(true, sideScreenContentAllowsVerticalScroll(showMusicCard = false, showNotificationPreview = true))
        assertEquals(false, sideScreenContentAllowsVerticalScroll(showMusicCard = false, showNotificationPreview = false))
    }
}
