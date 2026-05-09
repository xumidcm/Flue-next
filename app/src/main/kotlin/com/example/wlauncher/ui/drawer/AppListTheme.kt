package com.flue.launcher.ui.drawer

import androidx.compose.ui.graphics.Color
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.isMaterial

data class AppListPalette(
    val background: Color,
    val item: Color,
    val itemPressedOverlay: Color,
    val text: Color,
    val fadeEdge: Color
)

fun appListBackgroundColor(darkMode: Boolean): Color =
    if (darkMode) Color.Black else Color.White

fun appListEntryMaskColor(darkMode: Boolean): Color =
    appListBackgroundColor(darkMode)

fun appListNeutralItemMaskColor(): Color =
    Color.Black.copy(alpha = 0.36f)

fun appLaunchBackdropColor(
    seed: Color,
    darkMode: Boolean,
    uiStyle: UiStyle,
    useWatchFaceColor: Boolean = true
): Color {
    if (!useWatchFaceColor) return appListBackgroundColor(darkMode)
    val normalizedSeed = normalizeAppListSeed(seed)
    return when {
        uiStyle.isMaterial && darkMode -> mixAppListColor(normalizedSeed, Color(0xFF0E131A), 0.78f)
        uiStyle.isMaterial -> mixAppListColor(normalizedSeed, Color(0xFFF5F1EA), 0.84f)
        darkMode -> mixAppListColor(normalizedSeed, Color.Black, 0.82f)
        else -> mixAppListColor(normalizedSeed, Color.White, 0.90f)
    }
}

fun appListPalette(
    seed: Color,
    darkMode: Boolean,
    uiStyle: UiStyle,
    useWatchFaceColor: Boolean = true
): AppListPalette {
    val background = appListBackgroundColor(darkMode)
    if (!useWatchFaceColor) {
        if (uiStyle.isMaterial) {
            return if (darkMode) {
                AppListPalette(
                    background = background,
                    item = appListNeutralItemMaskColor(),
                    itemPressedOverlay = Color.White.copy(alpha = 0.18f),
                    text = Color.White,
                    fadeEdge = background
                )
            } else {
                AppListPalette(
                    background = background,
                    item = appListNeutralItemMaskColor(),
                    itemPressedOverlay = Color.Black.copy(alpha = 0.14f),
                    text = Color(0xFF101010),
                    fadeEdge = background
                )
            }
        }
        return if (darkMode) {
            AppListPalette(
                background = background,
                item = appListNeutralItemMaskColor(),
                itemPressedOverlay = Color.White.copy(alpha = 0.18f),
                text = Color.White,
                fadeEdge = background
            )
        } else {
            AppListPalette(
                background = background,
                item = appListNeutralItemMaskColor(),
                itemPressedOverlay = Color.Black.copy(alpha = 0.12f),
                text = Color(0xFF101010),
                fadeEdge = background
            )
        }
    }
    val normalizedSeed = normalizeAppListSeed(seed)
    if (uiStyle.isMaterial) {
        return if (darkMode) {
            AppListPalette(
                background = background,
                item = mixAppListColor(normalizedSeed, Color(0xFF1A2430), 0.72f),
                itemPressedOverlay = mixAppListColor(normalizedSeed, Color.White, 0.10f).copy(alpha = 0.16f),
                text = Color(0xFFF4F7FB),
                fadeEdge = background
            )
        } else {
            AppListPalette(
                background = background,
                item = mixAppListColor(normalizedSeed, Color(0xFFE9E2D5), 0.80f),
                itemPressedOverlay = mixAppListColor(normalizedSeed, Color.Black, 0.16f).copy(alpha = 0.08f),
                text = Color(0xFF202833),
                fadeEdge = background
            )
        }
    }
    return if (darkMode) {
        AppListPalette(
            background = background,
            item = mixAppListColor(normalizedSeed, Color.Black, 0.62f).copy(alpha = 0.32f),
            itemPressedOverlay = mixAppListColor(normalizedSeed, Color.White, 0.22f).copy(alpha = 0.20f),
            text = Color.White,
            fadeEdge = background
        )
    } else {
        AppListPalette(
            background = background,
            item = mixAppListColor(normalizedSeed, Color.White, 0.64f).copy(alpha = 0.62f),
            itemPressedOverlay = mixAppListColor(normalizedSeed, Color.Black, 0.18f).copy(alpha = 0.14f),
            text = Color(0xFF14171C),
            fadeEdge = background
        )
    }
}

private fun normalizeAppListSeed(seed: Color): Color =
    if (seed.alpha <= 0.01f) Color(0xFF7BE8FF) else seed.copy(alpha = 1f)

private fun mixAppListColor(from: Color, to: Color, toFraction: Float): Color {
    val t = toFraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = from.alpha + (to.alpha - from.alpha) * t
    )
}
