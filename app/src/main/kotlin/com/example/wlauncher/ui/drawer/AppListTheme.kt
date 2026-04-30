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

fun appListPalette(seed: Color, darkMode: Boolean, uiStyle: UiStyle): AppListPalette {
    val normalizedSeed = if (seed.alpha <= 0.01f) Color(0xFF7BE8FF) else seed.copy(alpha = 1f)
    if (uiStyle.isMaterial) {
        return if (darkMode) {
            AppListPalette(
                background = mixAppListColor(normalizedSeed, Color(0xFF0E131A), 0.78f),
                item = mixAppListColor(normalizedSeed, Color(0xFF1A2430), 0.72f),
                itemPressedOverlay = mixAppListColor(normalizedSeed, Color.White, 0.10f).copy(alpha = 0.16f),
                text = Color(0xFFF4F7FB),
                fadeEdge = mixAppListColor(normalizedSeed, Color(0xFF0E131A), 0.86f)
            )
        } else {
            AppListPalette(
                background = mixAppListColor(normalizedSeed, Color(0xFFF5F1EA), 0.84f),
                item = mixAppListColor(normalizedSeed, Color(0xFFE9E2D5), 0.80f),
                itemPressedOverlay = mixAppListColor(normalizedSeed, Color.Black, 0.16f).copy(alpha = 0.08f),
                text = Color(0xFF202833),
                fadeEdge = mixAppListColor(normalizedSeed, Color(0xFFF5F1EA), 0.90f)
            )
        }
    }
    return if (darkMode) {
        AppListPalette(
            background = mixAppListColor(normalizedSeed, Color.Black, 0.82f),
            item = mixAppListColor(normalizedSeed, Color.Black, 0.62f).copy(alpha = 0.32f),
            itemPressedOverlay = mixAppListColor(normalizedSeed, Color.White, 0.22f).copy(alpha = 0.20f),
            text = Color.White,
            fadeEdge = mixAppListColor(normalizedSeed, Color.Black, 0.72f)
        )
    } else {
        AppListPalette(
            background = mixAppListColor(normalizedSeed, Color.White, 0.90f),
            item = mixAppListColor(normalizedSeed, Color.White, 0.64f).copy(alpha = 0.62f),
            itemPressedOverlay = mixAppListColor(normalizedSeed, Color.Black, 0.18f).copy(alpha = 0.14f),
            text = Color(0xFF14171C),
            fadeEdge = mixAppListColor(normalizedSeed, Color.White, 0.82f)
        )
    }
}

private fun mixAppListColor(from: Color, to: Color, toFraction: Float): Color {
    val t = toFraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = from.alpha + (to.alpha - from.alpha) * t
    )
}
