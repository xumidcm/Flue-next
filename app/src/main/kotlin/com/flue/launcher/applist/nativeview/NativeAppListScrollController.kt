package com.flue.launcher.applist.nativeview

import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

private const val ROTARY_HAPTIC_MIN_INTERVAL_MS = 90L

internal enum class NativeAppListScrollSource {
    RotaryEncoder,
    MouseOrTouchpad,
}

internal fun shouldPerformNativeAppListHaptic(
    source: NativeAppListScrollSource,
    enabled: Boolean,
    gateAccepted: Boolean,
): Boolean =
    enabled &&
        gateAccepted &&
        source == NativeAppListScrollSource.RotaryEncoder

internal fun nativeAppListScrollDeltaPixels(
    source: NativeAppListScrollSource,
    axisValue: Float,
    verticalScrollFactor: Float,
): Int {
    if (
        !axisValue.isFinite() ||
        !verticalScrollFactor.isFinite() ||
        verticalScrollFactor <= 0f
    ) {
        return 0
    }

    val pixelDelta = when (source) {
        NativeAppListScrollSource.RotaryEncoder,
        NativeAppListScrollSource.MouseOrTouchpad -> -axisValue * verticalScrollFactor
    }
    return if (pixelDelta.isFinite()) pixelDelta.roundToInt() else 0
}

internal class NativeAppListHapticGate(
    private val minIntervalMs: Long = ROTARY_HAPTIC_MIN_INTERVAL_MS,
) {
    private var lastAcceptedAtMs: Long? = null

    init {
        require(minIntervalMs >= 0L) {
            "minIntervalMs must be non-negative"
        }
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

internal class NativeAppListScrollController(
    private val recyclerView: RecyclerView,
) {
    private val verticalScrollFactor =
        ViewConfiguration.get(recyclerView.context).scaledVerticalScrollFactor
    private val rotaryHapticGate = NativeAppListHapticGate()

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false

        val source = event.nativeAppListScrollSource() ?: return false
        val axisValue = when (source) {
            NativeAppListScrollSource.RotaryEncoder ->
                event.getAxisValue(MotionEvent.AXIS_SCROLL)

            NativeAppListScrollSource.MouseOrTouchpad ->
                event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        }
        val deltaPixels = nativeAppListScrollDeltaPixels(
            source = source,
            axisValue = axisValue,
            verticalScrollFactor = verticalScrollFactor,
        )
        if (deltaPixels == 0) return false

        recyclerView.scrollBy(0, deltaPixels)
        if (
            source == NativeAppListScrollSource.RotaryEncoder &&
            rotaryHapticGate.tryAcquire(event.eventTime)
        ) {
            recyclerView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return true
    }
}

private fun MotionEvent.nativeAppListScrollSource(): NativeAppListScrollSource? =
    when {
        isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) ->
            NativeAppListScrollSource.RotaryEncoder

        isFromSource(InputDevice.SOURCE_MOUSE) ||
            isFromSource(InputDevice.SOURCE_TOUCHPAD) ->
            NativeAppListScrollSource.MouseOrTouchpad

        else -> null
    }
