package com.flue.launcher.watchface.standard

import org.junit.Assert.*
import org.junit.Test

class WatchmakerDisplayModeTest {
    @Test fun ambientOverridesAllAttributesWithoutMutatingBrightSource() {
        val bright = mapOf("color" to "ffffff", "color_dim" to "444444", "x" to "3", "text_size" to "20")
        val ambient = mapOf("color" to "777777", "x" to "{ds}", "fontSize" to "14", "opacity" to "60")
        val dim = watchmakerDisplayAttributes(bright, ambient, true)
        assertEquals("777777", dim["color"])
        assertEquals("{ds}", dim["x"])
        assertEquals("14", dim["text_size"])
        assertEquals("60", dim["opacity"])
        assertEquals("20", bright["text_size"])
        assertEquals(bright, watchmakerDisplayAttributes(bright, ambient, false))
    }

    @Test fun legacyDimColorAndStudioChildRoundTripThroughDefinition() {
        val layer = WatchmakerLayer("a", "text", null, mapOf("color" to "ffffff", "color_dim" to "333333"),
            listOf(WatchmakerXmlElement("Ambient", mapOf("fontSize" to "16", "rotation" to "90"), null, emptyList())))
        assertEquals("333333", watchmakerDisplayAttributes(layer, true)["color"])
        assertEquals("16", watchmakerDisplayAttributes(layer, true)["text_size"])
        assertFalse(watchmakerDisplayAttributes(layer, false).containsKey("rotation"))
    }

    @Test fun ambientOnlyExpressionsParticipateInCacheDependencies() {
        val layer = WatchmakerLayer(null, "text", null, mapOf("text" to "Bright"), listOf(
            WatchmakerXmlElement("Ambient", mapOf("text" to "{ds}", "color" to "{abright}"), null, emptyList())))
        assertTrue(watchmakerLayerAttributeValues(layer).containsAll(listOf("Bright", "{ds}", "{abright}")))
    }

    @Test fun displayModesAndExpressionCaseStayIndependent() {
        for (both in listOf(null, "", "bd", "ab", "both", "Y")) {
            assertTrue(watchmakerDisplayVisible(both, false) { false })
            assertTrue(watchmakerDisplayVisible(both, true) { false })
        }
        for (dim in listOf("a", "ad", "d", "Ambient")) {
            assertFalse(watchmakerDisplayVisible(dim, false) { true })
            assertTrue(watchmakerDisplayVisible(dim, true) { false })
        }
        assertTrue(watchmakerDisplayVisible("b", false) { false })
        assertFalse(watchmakerDisplayVisible("b", true) { true })
        var observed = ""
        assertTrue(watchmakerDisplayVisible("Var_Case > 2", true) { observed = it; true })
        assertEquals("Var_Case > 2", observed)
    }

    @Test fun ambientCapsEvenLuaAndGyroFacesAtOneTickPerSecond() {
        assertEquals(1_000L, watchmakerTickDelay(true, true))
        assertEquals(1_000L, watchmakerTickDelay(true, false))
        assertEquals(33L, watchmakerTickDelay(false, true))
    }

    @Test fun captureRejectsAnEntireOverlayRoundTripBeforeLateCallback() {
        val window = WatchFaceCaptureEpoch()
        window.observe(true)
        val request = window.epoch
        assertTrue(window.accepts(request))
        window.observe(false)
        window.observe(true)
        assertFalse(window.accepts(request))
        assertTrue(window.accepts(window.epoch))
    }

    @Test fun soundActionsSearchSupportedFilesOnlyInsideSfx() {
        assertEquals(WatchTapAction.PlaySound("Bell"), WatchTapActionParser.parse("play_sound:Bell"))
        assertEquals(listOf("sfx/Bell.mp3", "sfx/Bell.ogg", "sfx/Bell.wav", "sfx/Bell.m4a", "sfx/Bell.aac", "sfx/Bell.flac"), watchSoundAssetCandidates("Bell"))
        assertEquals(listOf("sfx/bell.ogg"), watchSoundAssetCandidates("bell.ogg"))
        for (path in listOf("../bell", "sfx/bell", "C:\\bell", "https://host/sound", "bell\u0000")) {
            assertTrue(path, WatchTapActionParser.parse("play_sound:$path") is WatchTapAction.Unsupported)
        }
    }
}
