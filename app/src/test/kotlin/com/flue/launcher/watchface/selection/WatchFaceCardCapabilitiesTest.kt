package com.flue.launcher.watchface.selection

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchFaceCardCapabilitiesTest {
    @Test
    fun `built in faces can be edited but never deleted`() {
        assertEquals(
            WatchFaceCardCapabilities(canEdit = true, canDelete = false),
            watchFaceCardCapabilities(
                supportsSettings = true,
                isBuiltIn = true,
                isJbWatch = false,
                isWatchmaker = false,
            ),
        )
    }

    @Test
    fun `editable imported faces expose edit and delete together`() {
        assertEquals(
            WatchFaceCardCapabilities(canEdit = true, canDelete = true),
            watchFaceCardCapabilities(
                supportsSettings = true,
                isBuiltIn = false,
                isJbWatch = false,
                isWatchmaker = true,
            ),
        )
    }
}
