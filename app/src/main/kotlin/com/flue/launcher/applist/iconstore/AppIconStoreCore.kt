package com.flue.launcher.applist.iconstore

import com.flue.launcher.applist.model.IconKey
import java.util.LinkedHashMap

@JvmInline
value class IconSizeBucket private constructor(
    val pixels: Int,
) {
    companion object {
        private const val DEFAULT_QUANTUM_PIXELS = 8

        fun forPixels(
            requestedPixels: Int,
            quantumPixels: Int = DEFAULT_QUANTUM_PIXELS,
        ): IconSizeBucket {
            require(requestedPixels > 0) {
                "Requested icon size must be greater than zero"
            }
            require(quantumPixels > 0) {
                "Icon size quantum must be greater than zero"
            }

            val requested = requestedPixels.toLong()
            val quantum = quantumPixels.toLong()
            val roundedPixels = ((requested + quantum - 1L) / quantum) * quantum
            require(roundedPixels <= Int.MAX_VALUE.toLong()) {
                "Rounded icon size exceeds the supported integer range"
            }
            return IconSizeBucket(roundedPixels.toInt())
        }
    }
}

@JvmInline
value class IconSourceRevision(
    val value: String,
) {
    init {
        require(value.isNotBlank()) {
            "Icon source revision must not be empty or blank"
        }
    }
}

data class AppIconCacheKey(
    val iconKey: IconKey,
    val sizeBucket: IconSizeBucket,
    val sourceRevision: IconSourceRevision,
)

data class AppIconRequest(
    val cacheKey: AppIconCacheKey,
    val snapshotGeneration: Long,
) {
    init {
        require(snapshotGeneration >= 0L) {
            "Snapshot generation must not be negative"
        }
    }
}

enum class AppIconLoadSource {
    MemoryCache,
    Decoded,
}

data class AppIconResult<T>(
    val request: AppIconRequest,
    val value: T,
    val source: AppIconLoadSource,
) {
    fun isCurrentFor(snapshotGeneration: Long): Boolean =
        request.snapshotGeneration == snapshotGeneration
}

/**
 * A small, thread-safe weighted LRU that retains sharp source icons only.
 *
 * Values are deliberately not recycled when evicted because a bound View may
 * still be drawing the same Bitmap. Android owns the final lifetime after all
 * strong references disappear.
 */
class SharpIconMemoryCache<T>(
    private val maxWeightBytes: Long,
    private val weightOf: (T) -> Long,
) {
    private data class WeightedValue<T>(
        val value: T,
        val weightBytes: Long,
    )

    private val entries = LinkedHashMap<AppIconCacheKey, WeightedValue<T>>(
        16,
        0.75f,
        true,
    )
    private var storedWeightBytes = 0L

    init {
        require(maxWeightBytes > 0L) {
            "Maximum cache weight must be greater than zero"
        }
    }

    val currentWeightBytes: Long
        @Synchronized get() = storedWeightBytes

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    operator fun get(key: AppIconCacheKey): T? = entries[key]?.value

    /**
     * Returns true only when the value is retained by the cache.
     */
    @Synchronized
    fun put(
        key: AppIconCacheKey,
        value: T,
    ): Boolean {
        val weightBytes = weightOf(value)
        require(weightBytes > 0L) {
            "Cached icon weight must be greater than zero"
        }

        entries.remove(key)?.let { previous ->
            storedWeightBytes -= previous.weightBytes
        }

        if (weightBytes > maxWeightBytes) {
            return false
        }

        trimForAdditionalWeight(weightBytes)
        entries[key] = WeightedValue(
            value = value,
            weightBytes = weightBytes,
        )
        storedWeightBytes += weightBytes
        return true
    }

    @Synchronized
    fun remove(key: AppIconCacheKey): T? {
        val removed = entries.remove(key) ?: return null
        storedWeightBytes -= removed.weightBytes
        return removed.value
    }

    @Synchronized
    fun invalidate(iconKey: IconKey): Int {
        var removedCount = 0
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.iconKey == iconKey) {
                storedWeightBytes -= entry.value.weightBytes
                iterator.remove()
                removedCount += 1
            }
        }
        return removedCount
    }

    @Synchronized
    fun clear() {
        entries.clear()
        storedWeightBytes = 0L
    }

    private fun trimForAdditionalWeight(additionalWeightBytes: Long) {
        val iterator = entries.entries.iterator()
        while (
            storedWeightBytes > maxWeightBytes - additionalWeightBytes &&
            iterator.hasNext()
        ) {
            val eldest = iterator.next()
            storedWeightBytes -= eldest.value.weightBytes
            iterator.remove()
        }
    }
}
