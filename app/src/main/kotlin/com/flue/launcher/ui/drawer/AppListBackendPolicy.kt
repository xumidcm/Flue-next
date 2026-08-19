package com.flue.launcher.ui.drawer

import com.flue.launcher.ui.navigation.LayoutMode

internal enum class AppListBackend {
    NATIVE_LINEAR,
    NATIVE_HONEYCOMB,
    NATIVE_DENSE_GRID,
    NATIVE_FLOW,
    NATIVE_RING,
}

internal fun appListBackend(layoutMode: LayoutMode): AppListBackend = when (layoutMode) {
    LayoutMode.List -> AppListBackend.NATIVE_LINEAR
    LayoutMode.Honeycomb -> AppListBackend.NATIVE_HONEYCOMB
    LayoutMode.Grid -> AppListBackend.NATIVE_DENSE_GRID
    LayoutMode.Flow -> AppListBackend.NATIVE_FLOW
    LayoutMode.Ring -> AppListBackend.NATIVE_RING
}
