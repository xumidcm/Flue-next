package com.flue.launcher.applist.nativeview

import kotlin.math.hypot

internal data class NativeDragPolicy(
    val startThresholdPx: Float,
    val autoScrollEdgePx: Float,
    val maxAutoScrollPxPerFrame: Float,
    val folderHoverMillis: Long = 300L,
    /** Stable dwell and target retention both tolerate six physical dp. */
    val folderJitterPx: Float = 6f,
    /** Extra travel required before a reorder can reverse at a slot boundary. */
    val reorderHysteresisPx: Float = 12f,
    val longPressMillis: Long = 620L,
)

internal class NativeDragPolicyProfiles(
    density: Float,
) {
    private val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    private val linear = NativeDragPolicy(
        startThresholdPx = 20f * safeDensity,
        autoScrollEdgePx = 84f * safeDensity,
        maxAutoScrollPxPerFrame = 30f,
        folderHoverMillis = 300L,
        folderJitterPx = 6f * safeDensity,
        reorderHysteresisPx = 4f * safeDensity,
        longPressMillis = 620L,
    )
    private val spatial = NativeDragPolicy(
        startThresholdPx = 20f * safeDensity,
        autoScrollEdgePx = 96f * safeDensity,
        maxAutoScrollPxPerFrame = 26f,
        folderHoverMillis = 300L,
        folderJitterPx = 6f * safeDensity,
        reorderHysteresisPx = 4f * safeDensity,
        longPressMillis = 620L,
    )

    fun forPresentation(presentation: NativeAppListPresentation): NativeDragPolicy =
        when (presentation) {
            NativeAppListPresentation.LINEAR -> linear
            NativeAppListPresentation.HONEYCOMB,
            NativeAppListPresentation.DENSE_GRID,
            NativeAppListPresentation.FLOW,
            NativeAppListPresentation.RING,
            -> spatial
        }
}

internal fun nativeAutoScrollDelta(
    pointerY: Float,
    viewportHeight: Float,
    policy: NativeDragPolicy,
): Float {
    if (viewportHeight <= 0f || policy.autoScrollEdgePx <= 0f) return 0f
    // The raw 84/96dp bands were sized for a phone. On a watch viewport
    // (454px at density 2, or worse on 320px hardware) they covered most or
    // all of the screen — on the smallest watches the two bands overlapped and
    // the top branch won everywhere, so *any* drag position auto-scrolled and
    // the reorder loop ran at frame rate. Clamping keeps a neutral middle of
    // at least ~68% of the viewport on every device.
    val edge = policy.autoScrollEdgePx.coerceAtMost(viewportHeight * 0.16f)
    // Decide by proximity first so a (theoretical) overlap can never let the
    // top branch swallow the bottom half of the screen.
    val nearTop = pointerY < viewportHeight - pointerY
    return when {
        nearTop && pointerY < edge ->
            -policy.maxAutoScrollPxPerFrame *
                ((edge - pointerY) / edge).coerceIn(0f, 1f)
        !nearTop && pointerY > viewportHeight - edge ->
            policy.maxAutoScrollPxPerFrame *
                ((pointerY - (viewportHeight - edge)) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** Spatial dwell: a fast arrival can settle without another MOVE event. */
internal class NativeFolderHoverGate(private val policy: NativeDragPolicy) {
    var targetKey: String? = null
        private set
    private var anchorX = 0f
    private var anchorY = 0f
    private var startedAtMillis = 0L
    private var armed = false

    val deadlineMillis: Long?
        get() = if (targetKey != null && !armed) startedAtMillis + policy.folderHoverMillis else null

    fun update(candidateKey: String?, x: Float, y: Float, nowMillis: Long): String? {
        if (candidateKey == null || !x.isFinite() || !y.isFinite()) {
            reset()
            return null
        }
        if (targetKey != candidateKey || hypot(x - anchorX, y - anchorY) > policy.folderJitterPx) {
            targetKey = candidateKey
            anchorX = x
            anchorY = y
            startedAtMillis = nowMillis
            armed = false
        }
        if (nowMillis - startedAtMillis >= policy.folderHoverMillis) armed = true
        return candidateKey.takeIf { armed }
    }

    fun containsStablePointer(x: Float, y: Float): Boolean =
        targetKey != null && x.isFinite() && y.isFinite() &&
            hypot(x - anchorX, y - anchorY) <= policy.folderJitterPx

    fun reset() {
        targetKey = null
        startedAtMillis = 0L
        armed = false
    }
}

internal fun nativeFolderTargetAllowed(
    source: NativeAppListBoundRow<*>,
    target: NativeAppListBoundRow<*>,
): Boolean =
    source.stableKey != target.stableKey &&
        target.entry.type != com.flue.launcher.applist.model.AppItemType.SHORTCUT
