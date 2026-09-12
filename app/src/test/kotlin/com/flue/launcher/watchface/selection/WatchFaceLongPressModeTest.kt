package com.flue.launcher.watchface.selection

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchFaceLongPressModeTest {
    @Test
    fun `missing and invalid values fall back to cards`() {
        assertEquals(WatchFaceLongPressMode.CARDS, WatchFaceLongPressMode.decode(null))
        assertEquals(WatchFaceLongPressMode.CARDS, WatchFaceLongPressMode.decode(""))
        assertEquals(WatchFaceLongPressMode.CARDS, WatchFaceLongPressMode.decode("grid"))
    }

    @Test
    fun `stored values round trip`() {
        WatchFaceLongPressMode.entries.forEach { mode ->
            assertEquals(mode, WatchFaceLongPressMode.decode(mode.encode()))
        }
    }

    @Test
    fun `settings always opens the list while long press follows the preference`() {
        assertEquals(
            WatchFaceChooserDestination.CARDS,
            resolveWatchFaceChooserDestination(
                WatchFaceChooserEntryPoint.LONG_PRESS,
                WatchFaceLongPressMode.CARDS,
            ),
        )
        assertEquals(
            WatchFaceChooserDestination.LIST,
            resolveWatchFaceChooserDestination(
                WatchFaceChooserEntryPoint.LONG_PRESS,
                WatchFaceLongPressMode.LIST,
            ),
        )
        WatchFaceLongPressMode.entries.forEach { mode ->
            assertEquals(
                WatchFaceChooserDestination.LIST,
                resolveWatchFaceChooserDestination(WatchFaceChooserEntryPoint.SETTINGS, mode),
            )
        }
    }
}
