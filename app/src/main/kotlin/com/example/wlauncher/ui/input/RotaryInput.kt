package com.flue.launcher.ui.input

import androidx.compose.foundation.focusable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import kotlin.math.abs
import kotlin.math.sign

suspend fun FocusRequester.requestFocusAfterFirstFrame() {
    withFrameNanos { }
    runCatching { requestFocus() }
}

fun tunedRotaryScrollDelta(verticalScrollPixels: Float, multiplier: Float = 1f): Float {
    val magnitude = abs(verticalScrollPixels)
    if (magnitude < 0.01f) return 0f

    // Wear OS crown/bezel hardware differs a lot. Watch4-style bezel pulses are often
    // extremely small, so we give tiny deltas a minimum usable step instead of treating
    // them as noise.
    val tunedMagnitude = when {
        magnitude < 0.12f -> 18f
        magnitude < 0.28f -> 24f
        magnitude < 0.75f -> magnitude * 42f
        magnitude < 2.5f -> magnitude * 18f
        magnitude < 8f -> magnitude * 6f
        magnitude < 24f -> magnitude * 2.4f
        else -> magnitude * 1.15f
    }
    return sign(verticalScrollPixels) * tunedMagnitude * multiplier
}

fun Modifier.flueRotaryScrollable(
    focusRequester: FocusRequester,
    multiplier: Float = 1f,
    onScroll: (Float) -> Unit
): Modifier {
    fun consume(deltaPixels: Float): Boolean {
        val tuned = tunedRotaryScrollDelta(deltaPixels, multiplier)
        if (tuned == 0f) return false
        onScroll(tuned)
        return true
    }

    return this
        .focusRequester(focusRequester)
        .focusable()
        .onPreRotaryScrollEvent { consume(it.verticalScrollPixels) }
        .onRotaryScrollEvent { consume(it.verticalScrollPixels) }
}
