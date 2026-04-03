package com.flue.launcher.ui.input

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlin.math.abs
import kotlin.math.sign

suspend fun FocusRequester.requestFocusAfterFirstFrame() {
    withFrameNanos { }
    runCatching { requestFocus() }
}

fun tunedRotaryScrollDelta(verticalScrollPixels: Float, multiplier: Float = 1f): Float {
    val magnitude = abs(verticalScrollPixels)
    if (magnitude < 0.35f) return 0f
    val tunedMagnitude = when {
        magnitude < 4f -> magnitude * 1.9f
        magnitude < 10f -> magnitude * 1.55f
        magnitude < 24f -> magnitude * 1.28f
        else -> magnitude * 1.08f
    }
    return sign(verticalScrollPixels) * tunedMagnitude * multiplier
}
