package com.flue.launcher.watchface.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Coroutine and cache boundary for the public store.
 *
 * Returned preview bitmaps are shared read-only cache entries. Callers must not
 * mutate or recycle them. Downloaded `.watch` files are temporary and should be
 * handed to the installer promptly; stale files are removed automatically.
 */
class WatchStoreRepository(
    private val api: WatchStoreApi,
    private val cacheRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        api: WatchStoreApi = HttpWatchStoreApi(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        api = api,
        cacheRoot = File(context.cacheDir, CACHE_DIRECTORY_NAME),
        ioDispatcher = ioDispatcher,
    )

    private val previewDirectory = File(cacheRoot, "previews")
    private val downloadDirectory = File(cacheRoot, "downloads")
    private val previewLocks = ConcurrentHashMap<String, Any>()
    private val memoryLock = Any()
    private val previewMemoryCache = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    private var previewMemoryBytes = 0L
    private val cleanupLock = Any()
    @Volatile private var lastCleanupAtMs = 0L

    suspend fun getCategories(): List<WatchStoreCategory> = withContext(ioDispatcher) {
        api.getCategories()
    }

    suspend fun listWatchFaces(query: WatchStoreQuery = WatchStoreQuery()): WatchStorePage =
        withContext(ioDispatcher) { api.listWatchFaces(query) }

    suspend fun getWatchFace(id: String): WatchStoreWatchFaceDetail = withContext(ioDispatcher) {
        api.getWatchFace(WatchStoreUrlPolicy.requireId(id))
    }

    suspend fun loadPreview(
        id: String,
        targetWidth: Int = DEFAULT_PREVIEW_SIZE,
        targetHeight: Int = DEFAULT_PREVIEW_SIZE,
    ): Bitmap = withContext(ioDispatcher) {
        require(targetWidth in 1..MAX_PREVIEW_DIMENSION) { "targetWidth is out of range" }
        require(targetHeight in 1..MAX_PREVIEW_DIMENSION) { "targetHeight is out of range" }
        val normalizedId = WatchStoreUrlPolicy.requireId(id)
        val memoryKey = "$normalizedId:${targetWidth}x$targetHeight"
        getMemoryPreview(memoryKey)?.let { return@withContext it }
        ensureDirectories()
        cleanupIfDue()
        val itemLock = previewLocks.getOrPut(normalizedId) { Any() }
        try {
            synchronized(itemLock) {
                getMemoryPreview(memoryKey)?.let { return@synchronized it }
                val cached = File(previewDirectory, "$normalizedId.img")
                if (isUsablePreviewFile(cached)) {
                    decodePreview(cached, targetWidth, targetHeight)?.let { bitmap ->
                        cached.setLastModified(System.currentTimeMillis())
                        putMemoryPreview(memoryKey, bitmap)
                        return@synchronized bitmap
                    }
                    cached.delete()
                }

                val partial = File.createTempFile("preview_", ".part", previewDirectory)
                try {
                    api.downloadPreview(normalizedId, partial)
                    if (!isUsablePreviewFile(partial)) {
                        throw WatchStoreProtocolException("Downloaded preview is empty or too large")
                    }
                    moveReplacing(partial, cached)
                    val bitmap = decodePreview(cached, targetWidth, targetHeight)
                        ?: throw WatchStoreProtocolException("Downloaded preview cannot be decoded")
                    putMemoryPreview(memoryKey, bitmap)
                    bitmap
                } catch (error: Throwable) {
                    partial.delete()
                    cached.delete()
                    throw error
                }
            }
        } finally {
            previewLocks.remove(normalizedId, itemLock)
        }
    }

    suspend fun downloadWatchFace(id: String): File = withContext(ioDispatcher) {
        val normalizedId = WatchStoreUrlPolicy.requireId(id)
        ensureDirectories()
        cleanupIfDue()
        val partial = File.createTempFile("flue_watch_", ".watch.part", downloadDirectory)
        val completed = File(downloadDirectory, partial.name.removeSuffix(".part"))
        try {
            api.downloadWatchFace(normalizedId, partial)
            if (!partial.isFile || partial.length() !in 1..HttpWatchStoreApi.MAX_WATCH_BYTES) {
                throw WatchStoreProtocolException("Downloaded watchface is empty or too large")
            }
            moveReplacing(partial, completed)
            completed.setLastModified(System.currentTimeMillis())
            completed
        } catch (error: Throwable) {
            partial.delete()
            completed.delete()
            throw error
        }
    }

    /** Removes stale partial/download files and bounds the on-disk preview cache. */
    suspend fun cleanupTemporaryFiles(): Int = withContext(ioDispatcher) {
        ensureDirectories()
        val now = System.currentTimeMillis()
        synchronized(cleanupLock) {
            val removed = cleanupBlocking(now)
            lastCleanupAtMs = now
            removed
        }
    }

    fun clearPreviewMemoryCache() {
        synchronized(memoryLock) {
            // Do not recycle: a visible UI may still hold a shared bitmap.
            previewMemoryCache.clear()
            previewMemoryBytes = 0L
        }
    }

    private fun ensureDirectories() {
        listOf(cacheRoot, previewDirectory, downloadDirectory).forEach { directory ->
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                throw WatchStoreException("Cannot create watch store cache directory")
            }
            if (!directory.isDirectory) throw WatchStoreException("Watch store cache path is not a directory")
        }
    }

    private fun cleanupIfDue(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastCleanupAtMs < CLEANUP_INTERVAL_MS) return
        synchronized(cleanupLock) {
            if (nowMs - lastCleanupAtMs < CLEANUP_INTERVAL_MS) return
            cleanupBlocking(nowMs)
            lastCleanupAtMs = nowMs
        }
    }

    private fun cleanupBlocking(nowMs: Long): Int {
        var removed = 0
        downloadDirectory.listFiles().orEmpty().forEach { file ->
            val maxAge = if (file.name.endsWith(".part")) PARTIAL_MAX_AGE_MS else DOWNLOAD_MAX_AGE_MS
            if (file.isFile && isOlderThan(file, nowMs, maxAge) && file.delete()) removed++
        }
        previewDirectory.listFiles().orEmpty().forEach { file ->
            val maxAge = if (file.name.endsWith(".part")) PARTIAL_MAX_AGE_MS else PREVIEW_MAX_AGE_MS
            if (file.isFile && isOlderThan(file, nowMs, maxAge) && file.delete()) removed++
        }

        val previews = previewDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sortedBy(File::lastModified)
        var bytes = previews.sumOf(File::length)
        for (file in previews) {
            if (bytes <= MAX_DISK_PREVIEW_BYTES) break
            val length = file.length()
            if (file.delete()) {
                bytes -= length
                removed++
            }
        }
        return removed
    }

    private fun getMemoryPreview(key: String): Bitmap? = synchronized(memoryLock) {
        val bitmap = previewMemoryCache[key] ?: return@synchronized null
        if (bitmap.isRecycled) {
            previewMemoryCache.remove(key)
            previewMemoryBytes = previewMemoryCache.values.sumOf { it.safeByteCount() }
            null
        } else {
            bitmap
        }
    }

    private fun putMemoryPreview(key: String, bitmap: Bitmap) = synchronized(memoryLock) {
        previewMemoryCache.put(key, bitmap)?.let { previous ->
            previewMemoryBytes -= previous.safeByteCount()
        }
        previewMemoryBytes += bitmap.safeByteCount()
        while (
            previewMemoryCache.size > MAX_MEMORY_PREVIEWS ||
            (previewMemoryCache.size > 1 && previewMemoryBytes > MAX_MEMORY_PREVIEW_BYTES)
        ) {
            val iterator = previewMemoryCache.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next().value
            iterator.remove()
            previewMemoryBytes -= eldest.safeByteCount()
        }
    }

    private fun decodePreview(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetWidth ||
            bounds.outHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun isUsablePreviewFile(file: File): Boolean =
        file.isFile && file.length() in 1..HttpWatchStoreApi.MAX_PREVIEW_BYTES

    private fun isOlderThan(file: File, nowMs: Long, maxAgeMs: Long): Boolean {
        val modified = file.lastModified()
        return modified <= 0L || nowMs - modified > maxAgeMs
    }

    private fun moveReplacing(source: File, target: File) {
        if (target.exists() && !target.delete()) throw WatchStoreException("Cannot replace cached file")
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            // Some Android filesystems/providers do not implement NIO atomic move.
            try {
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                source.delete()
            } catch (fallback: Exception) {
                target.delete()
                throw WatchStoreException("Cannot finalize cached file", fallback)
            }
        }
    }

    private fun Bitmap.safeByteCount(): Long =
        runCatching { allocationByteCount.toLong() }
            .getOrElse { runCatching { byteCount.toLong() }.getOrDefault(0L) }

    companion object {
        private const val CACHE_DIRECTORY_NAME = "flue_watch_store"
        private const val DEFAULT_PREVIEW_SIZE = 512
        private const val MAX_PREVIEW_DIMENSION = 2_048
        private const val MAX_MEMORY_PREVIEWS = 16
        private const val MAX_MEMORY_PREVIEW_BYTES = 12L * 1024L * 1024L
        private const val MAX_DISK_PREVIEW_BYTES = 64L * 1024L * 1024L
        private const val CLEANUP_INTERVAL_MS = 6L * 60L * 60L * 1_000L
        private const val PARTIAL_MAX_AGE_MS = 60L * 60L * 1_000L
        private const val DOWNLOAD_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val PREVIEW_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
