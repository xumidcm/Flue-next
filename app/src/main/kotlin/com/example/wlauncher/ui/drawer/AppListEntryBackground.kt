package com.flue.launcher.ui.drawer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun BoxScope.AppListEntryBackground(
    maxWidth: Dp,
    maxHeight: Dp,
    visuals: AppListEntryVisuals,
    color: Color
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (visuals.backgroundProgress > 0.001f) {
            val maxSide = max(size.width, size.height)
            val radius = maxSide * (0.1344f + visuals.backgroundProgress * 0.8736f)
            drawCircle(
                color = color.copy(alpha = visuals.backgroundProgress),
                radius = radius,
                center = center
            )
        }
        if (visuals.backgroundRoundRectProgress > 0.001f) {
            val minSide = min(size.width, size.height)
            val corner = minSide * (0.50f - visuals.backgroundRoundRectProgress * 0.40f)
                .coerceAtLeast(minSide * 0.10f)
            drawRoundRect(
                color = color.copy(alpha = visuals.backgroundRoundRectProgress),
                cornerRadius = CornerRadius(corner, corner)
            )
        }
        if (visuals.backgroundFillProgress > 0.001f) {
            drawRect(
                color = color.copy(alpha = visuals.backgroundFillProgress)
            )
        }
    }
}
