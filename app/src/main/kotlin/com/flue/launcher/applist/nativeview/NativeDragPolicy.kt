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
): Float = when {
    viewportHeight <= 0f || policy.autoScrollEdgePx <= 0f -> 0f
    pointerY < policy.autoScrollEdgePx ->
        -policy.maxAutoScrollPxPerFrame *
            ((policy.autoScrollEdgePx - pointerY) / policy.autoScrollEdgePx).coerceIn(0f, 1f)
    pointerY > viewportHeight - policy.autoScrollEdgePx ->
        policy.maxAutoScrollPxPerFrame *
            ((pointerY - (viewportHeight - policy.autoScrollEdgePx)) /
                policy.autoScrollEdgePx).coerceIn(0f, 1f)
    else -> 0f
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
