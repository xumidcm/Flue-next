package com.flue.launcher.ui.drawer

import com.flue.launcher.ui.navigation.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppListBackendPolicyTest {
    @Test
    fun `layout mode alone selects the native presentation`() {
        assertEquals(
            AppListBackend.NATIVE_LINEAR,
            appListBackend(LayoutMode.List),
        )
        assertEquals(
            AppListBackend.NATIVE_HONEYCOMB,
            appListBackend(LayoutMode.Honeycomb),
        )
        assertEquals(
            AppListBackend.NATIVE_DENSE_GRID,
            appListBackend(LayoutMode.Grid),
        )
    }
}
