package com.flue.launcher.applist.nativeview

import kotlin.math.abs

internal data class NativeDragPolicy(
    val startThresholdPx: Float,
    val autoScrollEdgePx: Float,
    val maxAutoScrollPxPerFrame: Float,
    val folderHoverMillis: Long = 500L,
    val folderHoverMaxSpeedPxPerMs: Float,
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
        folderHoverMillis = 500L,
        folderHoverMaxSpeedPxPerMs = 0.55f,
        longPressMillis = 620L,
    )
    private val spatial = NativeDragPolicy(
        startThresholdPx = 20f * safeDensity,
        autoScrollEdgePx = 96f * safeDensity,
        maxAutoScrollPxPerFrame = 26f,
        folderHoverMillis = 700L,
        folderHoverMaxSpeedPxPerMs = 0.55f,
        longPressMillis = 620L,
    )

    fun forPresentation(presentation: NativeAppListPresentation): NativeDragPolicy =
        when (presentation) {
            NativeAppListPresentation.LINEAR -> linear
            NativeAppListPresentation.HONEYCOMB,
            NativeAppListPresentation.DENSE_GRID,
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

internal class NativeFolderHoverGate(
    private val policy: NativeDragPolicy,
) {
    private var targetKey: String? = null
    private var startedAtMillis = 0L

    fun update(candidateKey: String?, speedPxPerMs: Float, nowMillis: Long): String? {
        val validCandidate = candidateKey?.takeIf {
            speedPxPerMs.isFinite() && abs(speedPxPerMs) <= policy.folderHoverMaxSpeedPxPerMs
        }
        if (validCandidate == null) {
            reset()
            return null
        }
        if (targetKey != validCandidate) {
            targetKey = validCandidate
            startedAtMillis = nowMillis
            return null
        }
        return validCandidate.takeIf {
            nowMillis - startedAtMillis >= policy.folderHoverMillis
        }
    }

    fun reset() {
        targetKey = null
        startedAtMillis = 0L
    }
}

internal fun nativeFolderTargetAllowed(
    source: NativeAppListBoundRow<*>,
    target: NativeAppListBoundRow<*>,
): Boolean =
    source.stableKey != target.stableKey &&
        target.entry.type != com.flue.launcher.applist.model.AppItemType.SHORTCUT
