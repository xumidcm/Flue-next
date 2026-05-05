package com.flue.launcher.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

@Composable
internal fun BoxScope.AppListEntryBackground(
    maxWidth: Dp,
    maxHeight: Dp,
    visuals: AppListEntryVisuals,
    color: Color
) {
    val backgroundDiameter = (if (maxWidth > maxHeight) maxWidth else maxHeight) * 1.68f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = visuals.backgroundFillProgress }
            .background(color)
    )
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(backgroundDiameter)
            .graphicsLayer {
                val backgroundScale = 0.16f + visuals.backgroundProgress * 1.04f
                scaleX = backgroundScale
                scaleY = backgroundScale
                alpha = visuals.backgroundProgress
                clip = true
                shape = CircleShape
            }
            .background(color, CircleShape)
    )
}
