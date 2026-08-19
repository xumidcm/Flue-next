package com.flue.launcher.applist.nativeview

internal fun nativeOverscrollLimitPx(
    presentation: NativeAppListPresentation,
    viewportHeightPx: Int,
    density: Float,
): Float {
    val safeViewportHeight = viewportHeightPx.coerceAtLeast(0).toFloat()
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    return when (presentation) {
        NativeAppListPresentation.DENSE_GRID -> 140f * safeDensity
        NativeAppListPresentation.LINEAR,
        NativeAppListPresentation.HONEYCOMB,
        // 心流与蜂窝同为绝对定位的图标网格，下拉回表盘的行程按同一档给。
        NativeAppListPresentation.FLOW,
        // 星环同为绝对定位的图标网格。
        NativeAppListPresentation.RING -> safeViewportHeight / 2f
    }
}

internal fun nativeOverscrollDragDelta(
    presentation: NativeAppListPresentation,
    requestedDeltaPx: Float,
): Float = requestedDeltaPx * nativeOverscrollOutwardMultiplier(presentation)

internal fun nativeOverscrollOutwardMultiplier(
    presentation: NativeAppListPresentation,
): Float = when (presentation) {
    NativeAppListPresentation.HONEYCOMB,
    NativeAppListPresentation.FLOW,
    NativeAppListPresentation.RING -> 0.28f
    NativeAppListPresentation.DENSE_GRID,
    NativeAppListPresentation.LINEAR -> 1f
}

internal data class NativePullRelease(
    val triggerFace: Boolean,
    val releaseOffsetPx: Float,
)

internal class NativePullToFaceState(
    private val triggerThresholdPx: Float,
    maxOffsetPx: Float,
) {
    private var lastPointerY = 0f
    private var tracking = false
    private var maxOffsetPx = maxOffsetPx

    var offsetPx: Float = 0f
        private set
    var lastReleaseOffsetPx: Float = 0f
        private set
    val isPulling: Boolean
        get() = tracking && offsetPx != 0f

    fun onDown(pointerY: Float, initialOffsetPx: Float = 0f) {
        lastPointerY = pointerY
        offsetPx = initialOffsetPx
            .takeIf(Float::isFinite)
            ?.coerceIn(-maxOffsetPx.coerceAtLeast(0f), maxOffsetPx.coerceAtLeast(0f))
            ?: 0f
        tracking = pointerY.isFinite()
    }

    fun updateMaximumOffset(maximumPx: Float): Boolean {
        maxOffsetPx = maximumPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val clampedOffset = offsetPx.coerceIn(-maxOffsetPx, maxOffsetPx)
        if (clampedOffset == offsetPx) return false
        offsetPx = clampedOffset
        return true
    }

    fun onMove(
        pointerY: Float,
        atTop: Boolean,
        atBottom: Boolean = false,
        blocked: Boolean,
        outwardDragMultiplier: Float = 1f,
    ): Float {
        if (!tracking || blocked || !pointerY.isFinite()) {
            cancel()
            return 0f
        }
        val delta = pointerY - lastPointerY
        lastPointerY = pointerY
        consumePointerDelta(
            pointerDeltaPx = delta,
            atTop = atTop,
            atBottom = atBottom,
            blocked = false,
            outwardDragMultiplier = outwardDragMultiplier,
        )
        return offsetPx
    }

    /**
     * Consumes pointer-space movement and returns the signed amount actually owned by pull.
     * The caller can forward `pointerDeltaPx - returnedValue` to RecyclerView in the same frame.
     * Outward damping only changes the visual offset; it does not leak pointer movement back into
     * the list. Inward recovery is always one-to-one and may leave a remainder after crossing zero.
     */
    fun consumePointerDelta(
        pointerDeltaPx: Float,
        atTop: Boolean,
        atBottom: Boolean = false,
        blocked: Boolean,
        outwardDragMultiplier: Float = 1f,
    ): Float {
        if (!tracking || blocked || !pointerDeltaPx.isFinite()) {
            cancel()
            return 0f
        }
        val maximum = maxOffsetPx.coerceAtLeast(0f)
        val outwardMultiplier = outwardDragMultiplier
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 1f
        var remaining = pointerDeltaPx
        var consumed = 0f

        if (offsetPx > 0f && remaining < 0f) {
            val recovery = minOf(offsetPx, -remaining)
            offsetPx -= recovery
            remaining += recovery
            consumed -= recovery
        } else if (offsetPx < 0f && remaining > 0f) {
            val recovery = minOf(-offsetPx, remaining)
            offsetPx += recovery
            remaining -= recovery
            consumed += recovery
        }

        when {
            remaining > 0f && (offsetPx > 0f || atTop) -> {
                offsetPx = (offsetPx + remaining * outwardMultiplier).coerceAtMost(maximum)
                consumed += remaining
            }
            remaining < 0f && (offsetPx < 0f || atBottom) -> {
                offsetPx = (offsetPx + remaining * outwardMultiplier).coerceAtLeast(-maximum)
                consumed += remaining
            }
        }
        return consumed
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
