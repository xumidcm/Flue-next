package com.flue.launcher.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun rememberPressedState(): MutableState<Boolean> = remember { mutableStateOf(false) }

fun Modifier.instantPressGesture(
    pressedState: MutableState<Boolean>,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    if (!enabled) return this
    return pointerInput(onClick, enabled) {
        detectTapGestures(
            onPress = {
                runInstantPressGesture(
                    setPressed = { pressedState.value = it },
                    awaitRelease = { tryAwaitRelease() },
                    onClick = onClick,
                )
            }
        )
    }
}

internal suspend fun runInstantPressGesture(
    setPressed: (Boolean) -> Unit,
    awaitRelease: suspend () -> Boolean,
    onClick: () -> Unit,
) {
    setPressed(true)
    val released = try {
        awaitRelease()
    } finally {
        setPressed(false)
    }
    if (released) onClick()
}

@Composable
fun rememberFluePressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    return androidx.compose.animation.core.animateFloatAsState(
        targetValue = fluePressScaleTarget(pressed, pressedScale),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 120),
        label = "flue_press_scale",
    ).value
}

/**
 * Pure press-state policy shared by cards and icon buttons.
 *
 * Keeping the clamp outside Compose makes it possible to verify that a
 * malformed profile cannot invert or over-scale a control, and avoids every
 * call site inventing a subtly different pressed range.
 */
internal fun fluePressScaleTarget(
    pressed: Boolean,
    pressedScale: Float,
): Float = if (pressed) pressedScale.coerceIn(0.8f, 1f) else 1f

fun Modifier.fluePressScale(scale: Float): Modifier =
    if (scale >= 0.9999f) {
        this
    } else {
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }
