package com.flue.launcher.ui.drawer

import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.theme.UiStyle

internal enum class AppListBackend {
    NATIVE_LINEAR,
    NATIVE_HONEYCOMB,
    NATIVE_DENSE_GRID,
}

internal fun appListBackend(layoutMode: LayoutMode, uiStyle: UiStyle): AppListBackend = when {
    layoutMode == LayoutMode.Honeycomb && uiStyle == UiStyle.MATERIAL_3 ->
        AppListBackend.NATIVE_DENSE_GRID
    layoutMode == LayoutMode.Honeycomb -> AppListBackend.NATIVE_HONEYCOMB
    layoutMode == LayoutMode.List -> AppListBackend.NATIVE_LINEAR
    else -> AppListBackend.NATIVE_LINEAR
}
