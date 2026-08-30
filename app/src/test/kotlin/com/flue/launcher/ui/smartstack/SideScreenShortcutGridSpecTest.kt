package com.flue.launcher.ui.smartstack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SideScreenShortcutGridSpecTest {
    @Test
    fun `expanded 4 by 3 grid uses slightly larger icons and spacing`() {
        val compact = sideScreenShortcutGridSpec(rows = 2)
        val expanded = sideScreenShortcutGridSpec(rows = 3)

        assertEquals(64, compact.maxIconSizeDp)
        assertEquals(14, compact.spacingDp)
        assertEquals(68, expanded.maxIconSizeDp)
        assertEquals(18, expanded.spacingDp)
        assertTrue(expanded.maxIconSizeDp > compact.maxIconSizeDp)
        assertTrue(expanded.spacingDp > compact.spacingDp)
    }
}
