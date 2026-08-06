package com.flue.launcher.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class UiStyleDefaultsTest {
    @Test
    fun `missing and invalid preferences use miuix`() {
        assertEquals(UiStyle.MIUIX, uiStyleFromPreference(null))
        assertEquals(UiStyle.MIUIX, uiStyleFromPreference("not-a-style"))
    }

    @Test
    fun `valid saved style is preserved`() {
        assertEquals(UiStyle.APPLE_WATCH, uiStyleFromPreference(UiStyle.APPLE_WATCH.name))
        assertEquals(UiStyle.MATERIAL_3, uiStyleFromPreference(UiStyle.MATERIAL_3.name))
        assertEquals(UiStyle.MIUIX, uiStyleFromPreference(UiStyle.MIUIX.name))
    }
}
