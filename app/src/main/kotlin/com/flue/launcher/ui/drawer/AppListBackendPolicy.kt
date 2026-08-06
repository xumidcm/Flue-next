package com.flue.launcher.ui.drawer

import com.flue.launcher.ui.navigation.LayoutMode

internal enum class AppListBackend {
    NATIVE_LINEAR,
    NATIVE_HONEYCOMB,
    NATIVE_DENSE_GRID,
}

internal fun appListBackend(layoutMode: LayoutMode): AppListBackend = when (layoutMode) {
    LayoutMode.List -> AppListBackend.NATIVE_LINEAR
    LayoutMode.Honeycomb -> AppListBackend.NATIVE_HONEYCOMB
    LayoutMode.Grid -> AppListBackend.NATIVE_DENSE_GRID
}
