package com.flue.launcher.ui.drawer

import androidx.compose.ui.graphics.Color

data class AppListPalette(
    val background: Color,
    val item: Color,
    val itemPressedOverlay: Color,
    val text: Color,
    val fadeEdge: Color
)

fun appListPalette(seed: Color, darkMode: Boolean): AppListPalette {
    val normalizedSeed = if (seed.alpha <= 0.01f) Color(0xFF7BE8FF) else seed.copy(alpha = 1f)
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
