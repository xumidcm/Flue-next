package com.flue.launcher.ui.drawer

import com.flue.launcher.ui.navigation.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppListBackendPolicyTest {
    @Test
    fun `ordinary list production route uses native while honeycomb stays legacy`() {
        assertEquals(AppListBackend.NATIVE_LINEAR, appListBackend(LayoutMode.List))
        assertEquals(AppListBackend.COMPOSE_HONEYCOMB, appListBackend(LayoutMode.Honeycomb))
    }
}
