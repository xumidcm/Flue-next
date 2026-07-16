package com.flue.launcher.ui.drawer

import com.flue.launcher.ui.navigation.LayoutMode

internal enum class AppListBackend {
    COMPOSE_HONEYCOMB,
    NATIVE_LINEAR,
}

internal fun appListBackend(layoutMode: LayoutMode): AppListBackend = when (layoutMode) {
    LayoutMode.Honeycomb -> AppListBackend.COMPOSE_HONEYCOMB
    LayoutMode.List -> AppListBackend.NATIVE_LINEAR
}
