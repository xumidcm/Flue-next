package com.flue.launcher.ui.style

import com.flue.launcher.ui.theme.UiStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlueExpressiveComponentsTest {
    @Test
    fun `material 3 is the only style that opts into expressive components`() {
        assertTrue(useMaterial3ExpressiveComponents(UiStyle.MATERIAL_3))
        assertFalse(useMaterial3ExpressiveComponents(UiStyle.MIUIX))
        assertFalse(useMaterial3ExpressiveComponents(UiStyle.APPLE_WATCH))
    }
}
