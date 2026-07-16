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
    scrollOffsetChanged: Boolean,
): Boolean =
    enabled &&
        gateAccepted &&
        scrollOffsetChanged &&
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

internal interface NativeAppListScrollTarget {
    fun verticalScrollOffset(): Int

    fun scrollBy(deltaPixels: Int)

    fun performClockTick()
}

internal class NativeAppListConsumedScrollOwner(
    private val target: NativeAppListScrollTarget,
    private val configProvider: () -> NativeAppListConfig,
    private val hapticGate: NativeAppListHapticGate = NativeAppListHapticGate(),
) {
    fun scrollBy(
        source: NativeAppListScrollSource,
        deltaPixels: Int,
        eventTimeMs: Long,
    ): Boolean {
        if (deltaPixels == 0) return false

        val offsetBeforeScroll = target.verticalScrollOffset()
        target.scrollBy(deltaPixels)
        val scrollOffsetChanged =
            target.verticalScrollOffset() != offsetBeforeScroll
        val hapticsEnabled = configProvider().rotaryHapticsEnabled
        val gateAccepted =
            source == NativeAppListScrollSource.RotaryEncoder &&
                hapticsEnabled &&
                scrollOffsetChanged &&
                hapticGate.tryAcquire(eventTimeMs)
        if (
            shouldPerformNativeAppListHaptic(
                source = source,
                enabled = hapticsEnabled,
                gateAccepted = gateAccepted,
                scrollOffsetChanged = scrollOffsetChanged,
            )
        ) {
            target.performClockTick()
        }
        return scrollOffsetChanged
    }
}

internal class NativeAppListScrollController(
    private val recyclerView: RecyclerView,
    private val configProvider: () -> NativeAppListConfig = { NativeAppListConfig() },
) {
    private val verticalScrollFactor =
        ViewConfiguration.get(recyclerView.context).scaledVerticalScrollFactor
    private val consumedScrollOwner = NativeAppListConsumedScrollOwner(
        target = RecyclerViewNativeAppListScrollTarget(recyclerView),
        configProvider = configProvider,
    )

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

        return consumedScrollOwner.scrollBy(
            source = source,
            deltaPixels = deltaPixels,
            eventTimeMs = event.eventTime,
        )
    }
}

private class RecyclerViewNativeAppListScrollTarget(
    private val recyclerView: RecyclerView,
) : NativeAppListScrollTarget {
    override fun verticalScrollOffset(): Int =
        recyclerView.computeVerticalScrollOffset()

    override fun scrollBy(deltaPixels: Int) {
        recyclerView.scrollBy(0, deltaPixels)
    }

    override fun performClockTick() {
        recyclerView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
