package com.flue.launcher.watchface.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorImageImportModelsTest {
    @Test
    fun gifHeaderOverridesIncorrectProviderMetadata() {
        val header = "GIF89a".toByteArray(Charsets.US_ASCII)

        assertEquals(
            WatchEditorImageFormat.GIF,
            detectWatchEditorImageFormat("animation.png", "image/png", header),
        )
        assertEquals("animation.gif", watchEditorImageImportName("animation.png", WatchEditorImageFormat.GIF))
    }

    @Test
    fun gifExtensionSurvivesGenericDocumentProviderMime() {
        val format = detectWatchEditorImageFormat(
            displayName = "weather.GIF",
            mimeType = "application/octet-stream",
            header = byteArrayOf(),
        )

        assertEquals(WatchEditorImageFormat.GIF, format)
        assertEquals("weather.GIF", watchEditorImageImportName("weather.GIF", format))
    }

    @Test
    fun commonStaticHeadersAreDetectedWithoutMime() {
        assertEquals(
            WatchEditorImageFormat.PNG,
            detectWatchEditorImageFormat(null, null, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        )
        assertEquals(
            WatchEditorImageFormat.JPEG,
            detectWatchEditorImageFormat(null, null, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        )
        assertTrue(watchEditorImageImportName("photo", WatchEditorImageFormat.JPEG).endsWith(".jpg"))
    }
}
