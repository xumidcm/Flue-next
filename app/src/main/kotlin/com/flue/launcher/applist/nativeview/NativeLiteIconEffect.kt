package com.flue.launcher.applist.nativeview

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

private val LITE_SATURATION_BUCKETS = floatArrayOf(1f, 0.82f, 0.64f, 0.45f)

internal fun quantizeNativeLiteSaturation(value: Float): Int {
    val target = value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
    var bestIndex = 0
    var bestDistance = Float.MAX_VALUE
    for (index in LITE_SATURATION_BUCKETS.indices) {
        val distance = kotlin.math.abs(LITE_SATURATION_BUCKETS[index] - target)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

internal object NativeLiteIconEffectCache {
    private val filters: Array<ColorMatrixColorFilter?> =
        Array(LITE_SATURATION_BUCKETS.size) { index ->
            if (index == 0) {
                null
            } else {
                ColorMatrix().apply {
                    setSaturation(LITE_SATURATION_BUCKETS[index])
                }.let(::ColorMatrixColorFilter)
            }
        }

    fun filterForBucket(bucket: Int): ColorMatrixColorFilter? =
        filters[bucket.coerceIn(filters.indices)]
}

