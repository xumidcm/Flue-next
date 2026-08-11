package com.flue.launcher.ui.input

import androidx.compose.foundation.focusable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.sign

internal const val ROTARY_HAPTIC_MIN_INTERVAL_MS = 140L
internal const val ROTARY_SCROLL_MULTIPLIER = 0.65f
internal const val ROTARY_SCROLL_MAX_STEP_PX = 48f

internal class RotaryHapticGate(
    private val minIntervalMs: Long = ROTARY_HAPTIC_MIN_INTERVAL_MS
) {
    private var lastAcceptedAtMs: Long? = null

    init {
        require(minIntervalMs >= 0L) { "minIntervalMs must be non-negative" }
    }

    fun tryAcquire(nowMs: Long): Boolean {
        val lastAccepted = lastAcceptedAtMs
        val shouldAccept = when {
            lastAccepted == null -> true
            nowMs < lastAccepted -> true
            else -> nowMs - lastAccepted >= minIntervalMs
        }
        if (shouldAccept) {
            lastAcceptedAtMs = nowMs
        }
        return shouldAccept
    }
}

enum class DrawerInputMode {
    Honeycomb,
    List
}

enum class DrawerInputSource {
    Rotary,
    MouseWheel
}

suspend fun FocusRequester.requestFocusAfterFirstFrame() {
    withFrameNanos { }
    runCatching { requestFocus() }
}

fun tunedRotaryScrollDelta(
    verticalScrollPixels: Float,
    multiplier: Float = ROTARY_SCROLL_MULTIPLIER,
): Float {
    val magnitude = abs(verticalScrollPixels)
    if (magnitude < 0.01f) return 0f

    // Rotary hardware reports very different units across Wear OS and Android watches.
    // Keep tiny pulses usable, but cap large pulses so one crown tick cannot jump pages.
    val tunedMagnitude = when {
        magnitude < 0.12f -> 10f
        magnitude < 0.28f -> 14f
        magnitude < 0.75f -> magnitude * 24f
        magnitude < 2.5f -> magnitude * 12f
        magnitude < 8f -> magnitude * 5f
        magnitude < 24f -> magnitude * 2.2f
        else -> 64f
    }
    return sign(verticalScrollPixels) *
        (tunedMagnitude * multiplier.coerceIn(0.5f, 1.25f))
            .coerceAtMost(ROTARY_SCROLL_MAX_STEP_PX)
}

fun normalizeDrawerScrollDelta(
    verticalScrollPixels: Float,
    source: DrawerInputSource,
    mode: DrawerInputMode
): Float {
    val signedInput = when (source) {
        DrawerInputSource.Rotary -> verticalScrollPixels
        DrawerInputSource.MouseWheel -> verticalScrollPixels
    }
    val magnitude = abs(signedInput)
    if (magnitude < 0.01f) return 0f

    val tunedMagnitude = when (source) {
        DrawerInputSource.Rotary -> when {
            magnitude < 0.10f -> 10f
            magnitude < 0.24f -> 14f
            magnitude < 0.75f -> magnitude * 24f
            magnitude < 2.5f -> magnitude * 12f
            magnitude < 8f -> magnitude * 5f
            magnitude < 24f -> magnitude * 2f
            else -> 58f
        }
        DrawerInputSource.MouseWheel -> when {
            magnitude < 0.24f -> 18f
            magnitude < 1.2f -> magnitude * 34f
            magnitude < 4f -> magnitude * 28f
            else -> 76f
        }
    }

    val modeMultiplier = when (mode) {
        DrawerInputMode.Honeycomb -> when (source) {
            DrawerInputSource.Rotary -> ROTARY_SCROLL_MULTIPLIER
            DrawerInputSource.MouseWheel -> 0.76f
        }
        DrawerInputMode.List -> when (source) {
            DrawerInputSource.Rotary -> ROTARY_SCROLL_MULTIPLIER
            DrawerInputSource.MouseWheel -> 0.92f
        }
    }
    val maxStep = when (mode) {
        DrawerInputMode.Honeycomb -> when (source) {
            DrawerInputSource.Rotary -> ROTARY_SCROLL_MAX_STEP_PX
            DrawerInputSource.MouseWheel -> 72f
        }
        DrawerInputMode.List -> when (source) {
            DrawerInputSource.Rotary -> ROTARY_SCROLL_MAX_STEP_PX
            DrawerInputSource.MouseWheel -> 82f
        }
    }

    return sign(signedInput) * (tunedMagnitude * modeMultiplier).coerceAtMost(maxStep)
}

private fun Modifier.restoreRotaryFocusOnTouch(focusRequester: FocusRequester): Modifier =
    pointerInput(focusRequester) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press) {
                    runCatching { focusRequester.requestFocus() }
                }
            }
        }
    }

private fun Modifier.tunedPointerWheelScrollable(
    multiplier: Float,
    onScroll: (Float) -> Unit
): Modifier =
    pointerInput(multiplier) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) {
                    val rawDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    val tuned = tunedRotaryScrollDelta(rawDelta, multiplier)
                    if (tuned != 0f) {
                        onScroll(-tuned)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }

fun Modifier.flueRotaryScrollable(
    focusRequester: FocusRequester,
    multiplier: Float = ROTARY_SCROLL_MULTIPLIER,
    onRotaryScroll: (() -> Unit)? = null,
    onRotaryDelta: ((Float) -> Unit)? = null,
    onScroll: (Float) -> Unit
): Modifier = composed {
    val hapticGate = remember { RotaryHapticGate() }

    fun consume(deltaPixels: Float): Boolean {
        val tuned = tunedRotaryScrollDelta(-deltaPixels, multiplier)
        if (tuned == 0f) return false
        if (onRotaryDelta != null) {
            onRotaryDelta(tuned)
        } else if (onRotaryScroll != null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (hapticGate.tryAcquire(now)) {
                onRotaryScroll()
            }
            onScroll(tuned)
        } else {
            onScroll(tuned)
        }
        return true
    }

    this
        .onPreRotaryScrollEvent { consume(it.verticalScrollPixels) }
        .onRotaryScrollEvent { consume(it.verticalScrollPixels) }
        .tunedPointerWheelScrollable(multiplier, onScroll)
        .restoreRotaryFocusOnTouch(focusRequester)
        .focusRequester(focusRequester)
        .focusable()
}

fun Modifier.flueDrawerRotaryScrollable(
    focusRequester: FocusRequester,
    mode: DrawerInputMode,
    onRotaryScroll: (() -> Unit)? = null,
    onRotaryDelta: ((Float) -> Unit)? = null,
    onScroll: (Float) -> Unit
): Modifier = composed {
    val hapticGate = remember { RotaryHapticGate() }

    fun consume(deltaPixels: Float): Boolean {
        val tuned = normalizeDrawerScrollDelta(
            verticalScrollPixels = -deltaPixels,
            source = DrawerInputSource.Rotary,
            mode = mode
        )
        if (tuned == 0f) return false
        if (onRotaryDelta != null) {
            onRotaryDelta(tuned)
        } else if (onRotaryScroll != null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (hapticGate.tryAcquire(now)) {
                onRotaryScroll()
            }
            onScroll(tuned)
        } else {
            onScroll(tuned)
        }
        return true
    }

    this
        .onPreRotaryScrollEvent { consume(it.verticalScrollPixels) }
        .onRotaryScrollEvent { consume(it.verticalScrollPixels) }
        .restoreRotaryFocusOnTouch(focusRequester)
        .focusRequester(focusRequester)
        .focusable()
}
