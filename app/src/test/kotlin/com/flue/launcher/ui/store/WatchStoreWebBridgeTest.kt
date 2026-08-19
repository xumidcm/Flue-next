package com.flue.launcher.ui.store

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStoreWebBridgeTest {
    private val id = "11111111-2222-3333-4444-555555555555"

    @Test
    fun `preview url becomes a direct watch face page`() {
        val base = URI("https://jbwatchface.ai-life.xyz/api")

        assertEquals(
            "https://jbwatchface.ai-life.xyz/watchfaces/$id",
            WatchStoreWebBridge.watchFacePageFromPreviewUrl(
                base,
                "https://jbwatchface.ai-life.xyz/api/watchfaces/$id/preview",
            )?.toASCIIString(),
        )
        assertNull(
            WatchStoreWebBridge.watchFacePageFromPreviewUrl(
                base,
                "https://evil.example/api/watchfaces/$id/preview",
            ),
        )
    }

    @Test
    fun `direct page keeps a custom market subpath`() {
        val base = URI("https://market.example/community/api")

        assertEquals(
            "https://market.example/community/watchfaces/$id",
            WatchStoreWebBridge.watchFacePageFromPreviewUrl(
                base,
                "/community/api/watchfaces/$id/preview",
            )?.toASCIIString(),
        )
    }

    @Test
    fun `bridge intercepts grid cards before React opens its modal`() {
        val script = WatchStoreWebBridge.installScript(
            URI("https://market.example/community/api"),
        )

        assertTrue(script.contains(".grid .card"))
        assertTrue(script.contains("/api/watchfaces/"))
        assertTrue(script.contains("/preview"))
        assertTrue(script.contains("capture: true"))
        assertTrue(script.contains("preventDefault"))
        assertTrue(script.contains("stopImmediatePropagation"))
        assertTrue(script, script.contains("/community/watchfaces/"))
    }
}
