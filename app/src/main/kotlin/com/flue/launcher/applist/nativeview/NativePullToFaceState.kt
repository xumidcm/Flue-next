package com.flue.launcher.applist.nativeview

internal data class NativePullRelease(
    val triggerFace: Boolean,
    val releaseOffsetPx: Float,
)

internal class NativePullToFaceState(
    private val triggerThresholdPx: Float,
    private val maxOffsetPx: Float,
) {
    private var downY = 0f
    private var tracking = false

    var offsetPx: Float = 0f
        private set
    var lastReleaseOffsetPx: Float = 0f
        private set
    val isPulling: Boolean
        get() = tracking && offsetPx > 0f

    fun onDown(pointerY: Float) {
        downY = pointerY
        offsetPx = 0f
        tracking = pointerY.isFinite()
    }

    fun onMove(pointerY: Float, atTop: Boolean, blocked: Boolean): Float {
        if (!tracking || blocked || !pointerY.isFinite()) {
            cancel()
            return 0f
        }
        if (!atTop) return offsetPx
        offsetPx = (pointerY - downY).coerceIn(0f, maxOffsetPx.coerceAtLeast(0f))
        return offsetPx
    }

    fun onUp(): NativePullRelease {
        val release = offsetPx
        lastReleaseOffsetPx = release
        val result = NativePullRelease(
            triggerFace = release >= triggerThresholdPx.coerceAtLeast(0f),
            releaseOffsetPx = release,
        )
        tracking = false
        offsetPx = 0f
        return result
    }

    fun cancel() {
        tracking = false
        offsetPx = 0f
    }
}
