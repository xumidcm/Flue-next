package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.NormalizedPoint

private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
private const val RECYCLER_VIEW_NO_ID = -1L

internal fun stableIdFor(key: AppItemKey): Long {
    var hash = FNV_64_OFFSET_BASIS
    key.value.encodeToByteArray().forEach { byte ->
        hash = (hash xor (byte.toLong() and 0xffL)) * FNV_64_PRIME
    }
    return if (hash == RECYCLER_VIEW_NO_ID) Long.MIN_VALUE else hash
}

internal class StableIdRegistry(
    private val seedFor: (AppItemKey) -> Long = ::stableIdFor,
) {
    private val idsByKey = mutableMapOf<AppItemKey, Long>()
    private val keysById = mutableMapOf<Long, AppItemKey>()

    fun idFor(key: AppItemKey): Long {
        idsByKey[key]?.let { return it }

        var candidate = seedFor(key).skipRecyclerViewNoId()
        while (keysById[candidate]?.let { it != key } == true) {
            candidate = (candidate + 1L).skipRecyclerViewNoId()
        }
        idsByKey[key] = candidate
        keysById[candidate] = key
        return candidate
    }

    private fun Long.skipRecyclerViewNoId(): Long =
        if (this == RECYCLER_VIEW_NO_ID) 0L else this
}

internal fun shouldReplaceLayoutStrategy(
    currentSemanticKey: Any,
    nextSemanticKey: Any,
): Boolean = currentSemanticKey != nextSemanticKey

internal fun normalizedChildCenter(
    childLeft: Float,
    childTop: Float,
    childWidth: Int,
    childHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
): NormalizedPoint {
    val centerX = childLeft + childWidth / 2f
    val centerY = childTop + childHeight / 2f
    return NormalizedPoint(
        x = centerX.normalizedWithin(viewportWidth),
        y = centerY.normalizedWithin(viewportHeight),
    )
}

internal fun normalizedDescendantCenterInWindow(
    descendantWindowLeft: Float,
    descendantWindowTop: Float,
    descendantWidth: Int,
    descendantHeight: Int,
    viewportWindowLeft: Float,
    viewportWindowTop: Float,
    viewportWidth: Int,
    viewportHeight: Int,
): NormalizedPoint {
    if (
        !descendantWindowLeft.isFinite() ||
        !descendantWindowTop.isFinite() ||
        !viewportWindowLeft.isFinite() ||
        !viewportWindowTop.isFinite() ||
        descendantWidth <= 0 ||
        descendantHeight <= 0 ||
        viewportWidth <= 0 ||
        viewportHeight <= 0
    ) {
        return NormalizedPoint(x = 0.5f, y = 0.5f)
    }

    return normalizedChildCenter(
        childLeft = descendantWindowLeft - viewportWindowLeft,
        childTop = descendantWindowTop - viewportWindowTop,
        childWidth = descendantWidth,
        childHeight = descendantHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    )
}

internal data class NativeAppListIconBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun normalizedIconCenterAfterChildTransform(
    iconBounds: NativeAppListIconBounds,
    childLeft: Float,
    childTop: Float,
    childTranslationX: Float,
    childTranslationY: Float,
    childScaleX: Float,
    childScaleY: Float,
    childPivotX: Float,
    childPivotY: Float,
    viewportWidth: Int,
    viewportHeight: Int,
): NormalizedPoint {
    val geometry = floatArrayOf(
        iconBounds.left,
        iconBounds.top,
        iconBounds.right,
        iconBounds.bottom,
        childLeft,
        childTop,
        childTranslationX,
        childTranslationY,
        childScaleX,
        childScaleY,
        childPivotX,
        childPivotY,
    )
    if (
        geometry.any { !it.isFinite() } ||
        iconBounds.right <= iconBounds.left ||
        iconBounds.bottom <= iconBounds.top ||
        viewportWidth <= 0 ||
        viewportHeight <= 0
    ) {
        return NormalizedPoint(x = 0.5f, y = 0.5f)
    }

    val localCenterX = (iconBounds.left + iconBounds.right) / 2f
    val localCenterY = (iconBounds.top + iconBounds.bottom) / 2f
    val transformedCenterX =
        childLeft + childTranslationX + childPivotX +
            (localCenterX - childPivotX) * childScaleX
    val transformedCenterY =
        childTop + childTranslationY + childPivotY +
            (localCenterY - childPivotY) * childScaleY
    return NormalizedPoint(
        x = transformedCenterX.normalizedWithin(viewportWidth),
        y = transformedCenterY.normalizedWithin(viewportHeight),
    )
}

private fun Float.normalizedWithin(viewportSize: Int): Float {
    if (!isFinite() || viewportSize <= 0) return 0.5f
    return (this / viewportSize.toFloat()).coerceIn(0f, 1f)
}

internal class SnapshotGenerationGate {
    var latestGeneration: Long = -1L
        private set

    fun tryAccept(generation: Long): Boolean {
        require(generation >= 0L) {
            "Snapshot generation must not be negative"
        }
        if (generation <= latestGeneration) return false
        latestGeneration = generation
        return true
    }
}
