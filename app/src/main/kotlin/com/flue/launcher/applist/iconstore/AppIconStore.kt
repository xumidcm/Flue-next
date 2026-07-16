package com.flue.launcher.applist.iconstore

import android.graphics.Bitmap
import com.flue.launcher.applist.model.IconKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface AppIconBitmapDecoder {
    /**
     * Decode or restore one sharp base icon.
     *
     * AppIconStore always invokes this callback on its IO dispatcher.
     */
    fun decode(cacheKey: AppIconCacheKey): Bitmap?
}

class AppIconStore(
    private val decoder: AppIconBitmapDecoder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val memoryCache: SharpIconMemoryCache<Bitmap> = SharpIconMemoryCache(
        maxWeightBytes = DEFAULT_MEMORY_CACHE_BYTES,
        weightOf = { bitmap -> bitmap.cacheWeightBytes() },
    ),
) {
    private data class EpochSnapshot(
        val global: Long,
        val icon: Long,
    )

    private val invalidationMonitor = Any()
    private var globalInvalidationEpoch = 0L
    private val iconInvalidationEpochs = mutableMapOf<IconKey, Long>()

    suspend fun load(request: AppIconRequest): AppIconResult<Bitmap?> {
        val iconKey = request.cacheKey.iconKey
        val loadEpoch = synchronized(invalidationMonitor) {
            val snapshot = currentEpoch(iconKey)
            memoryCache[request.cacheKey]?.let { cached ->
                return AppIconResult(
                    request = request,
                    value = cached,
                    source = AppIconLoadSource.MemoryCache,
                )
            }
            snapshot
        }

        val decoded = withContext(ioDispatcher) {
            decoder.decode(request.cacheKey)
        }
        return synchronized(invalidationMonitor) {
            val isStillValid = currentEpoch(iconKey) == loadEpoch
            if (decoded != null && isStillValid) {
                memoryCache.put(request.cacheKey, decoded)
            }
            AppIconResult(
                request = request,
                value = decoded.takeIf { isStillValid },
                source = AppIconLoadSource.Decoded,
            )
        }
    }

    suspend fun load(
        cacheKey: AppIconCacheKey,
        snapshotGeneration: Long,
    ): AppIconResult<Bitmap?> = load(
        AppIconRequest(
            cacheKey = cacheKey,
            snapshotGeneration = snapshotGeneration,
        ),
    )

    fun invalidate(iconKey: IconKey): Int {
        return synchronized(invalidationMonitor) {
            iconInvalidationEpochs[iconKey] =
                (iconInvalidationEpochs[iconKey] ?: 0L) + 1L
            memoryCache.invalidate(iconKey)
        }
    }

    fun clearMemory() {
        synchronized(invalidationMonitor) {
            globalInvalidationEpoch += 1L
            iconInvalidationEpochs.clear()
            memoryCache.clear()
        }
    }

    private fun currentEpoch(iconKey: IconKey): EpochSnapshot = EpochSnapshot(
        global = globalInvalidationEpoch,
        icon = iconInvalidationEpochs[iconKey] ?: 0L,
    )

    companion object {
        const val DEFAULT_MEMORY_CACHE_BYTES: Long = 24L * 1024L * 1024L
    }
}

private fun Bitmap.cacheWeightBytes(): Long =
    allocationByteCount
        .coerceAtLeast(byteCount)
        .coerceAtLeast(1)
        .toLong()
