package com.flue.launcher.ui.drawer

import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.theme.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class AppListBackendPolicyTest {
    @Test
    fun `all app-list production routes use the native host with style-specific presentation`() {
        assertEquals(
            AppListBackend.NATIVE_LINEAR,
            appListBackend(LayoutMode.List, UiStyle.APPLE_WATCH),
        )
        assertEquals(
            AppListBackend.NATIVE_HONEYCOMB,
            appListBackend(LayoutMode.Honeycomb, UiStyle.APPLE_WATCH),
        )
        assertEquals(
            AppListBackend.NATIVE_DENSE_GRID,
            appListBackend(LayoutMode.Honeycomb, UiStyle.MATERIAL_3),
        )
    }
}
