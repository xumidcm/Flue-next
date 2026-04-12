package com.flue.launcher.ui.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

@Composable
fun Modifier.scaleBlurAlpha(
    targetValues: LayerAnimValues,
    screenWidth: Float = 0f,
    screenHeight: Float = 0f,
    blurEnabled: Boolean = true,
    origin: Offset? = null,
    animate: Boolean = true
): Modifier {
    if (!animate) {
        return this
            .graphicsLayer {
                scaleX = targetValues.scale
                scaleY = targetValues.scale
                alpha = targetValues.alpha
                translationX = targetValues.translationX * screenWidth
                translationY = targetValues.translationY * screenHeight
                if (origin != null) {
                    transformOrigin = TransformOrigin(origin.x, origin.y)
                }
            }
            .platformBlur(
                blurRadiusDp = targetValues.blur,
                enabled = blurEnabled
            )
    }

    val animScale = remember { Animatable(targetValues.scale) }
    val animAlpha = remember { Animatable(targetValues.alpha) }
    val animBlur = remember { Animatable(targetValues.blur) }
    val animTransX = remember { Animatable(targetValues.translationX) }
    val animTransY = remember { Animatable(targetValues.translationY) }

    LaunchedEffect(targetValues) {
        launch { animScale.animateTo(targetValues.scale, TransitionSpring) }
        launch { animAlpha.animateTo(targetValues.alpha, AlphaSpec) }
        launch { animBlur.animateTo(targetValues.blur, AlphaSpec) }
        launch { animTransX.animateTo(targetValues.translationX, TransitionSpring) }
        launch { animTransY.animateTo(targetValues.translationY, TransitionSpring) }
    }

    return this
        .graphicsLayer {
            scaleX = animScale.value
            scaleY = animScale.value
            alpha = animAlpha.value
            translationX = animTransX.value * screenWidth
            translationY = animTransY.value * screenHeight
            if (origin != null) {
                transformOrigin = TransformOrigin(origin.x, origin.y)
            }
        }
        .platformBlur(
            blurRadiusDp = animBlur.value,
            enabled = blurEnabled
        )
}
