package com.flue.launcher.watchface.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.standard.WatchInstallMetadata
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceScanFailure
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceScanResult
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.store.catalog.FileWatchFaceInstallJournal
import com.flue.launcher.watchface.store.catalog.FileWatchFaceCatalog
import com.flue.launcher.watchface.store.catalog.InstalledWatchFaceIdentity
import com.flue.launcher.watchface.store.catalog.WATCHMAKER_COMMUNITY_STORE_ID
import com.flue.launcher.watchface.store.catalog.WatchFaceCatalogSnapshot
import com.flue.launcher.watchface.store.catalog.WatchFaceInstallSource
import com.flue.launcher.watchface.store.catalog.WatchFaceLogicalIds
import com.flue.launcher.watchface.store.catalog.WatchFaceMarketCandidate
import com.flue.launcher.watchface.store.catalog.WatchFaceRecord
import com.flue.launcher.watchface.store.catalog.WatchFaceCatalogUpsertResult
import com.flue.launcher.watchface.store.catalog.canonicalWatchFaceName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
    private val catalog: FileWatchFaceCatalog = FileWatchFaceCatalog(File(cacheRoot, "watch-face-catalog.json")),
    private val installedWatchFacesProvider: () -> List<WatchFaceDescriptor> = { emptyList() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val installJournal: FileWatchFaceInstallJournal =
        FileWatchFaceInstallJournal(File(cacheRoot, "watch-face-install-journal.json")),
    private val installedWatchFaceScanProvider: (() -> WatchmakerWatchFaceScanResult)? = null,
) {
    constructor(
        context: Context,
        api: WatchStoreApi = HttpWatchStoreApi(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        api = api,
        cacheRoot = File(context.cacheDir, CACHE_DIRECTORY_NAME),
        catalog = FileWatchFaceCatalog(File(context.filesDir, CATALOG_RELATIVE_PATH)),
        installJournal = FileWatchFaceInstallJournal(File(context.filesDir, INSTALL_JOURNAL_RELATIVE_PATH)),
        installedWatchFacesProvider = { WatchmakerWatchFaceStorage.scan(context) },
        ioDispatcher = ioDispatcher,
        installedWatchFaceScanProvider = { WatchmakerWatchFaceStorage.scanWithDiagnostics(context) },
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
        // Keep one stable lock per remote id for the process lifetime. Removing
        // it after one caller exits lets a queued waiter and a third caller use
        // different locks and concurrently replace the same cache file.
        val itemLock = previewLocks.computeIfAbsent(normalizedId) { Any() }
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
    }

    suspend fun downloadWatchFace(
        id: String,
        onProgress: (bytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): File = withContext(ioDispatcher) {
        val normalizedId = WatchStoreUrlPolicy.requireId(id)
        ensureDirectories()
        cleanupIfDue()
        val partial = File.createTempFile("flue_watch_", ".watch.part", downloadDirectory)
        val completed = File(downloadDirectory, partial.name.removeSuffix(".part"))
        val throttle = WatchStoreProgressThrottle()
        try {
            api.downloadWatchFace(normalizedId, partial) { bytes, totalBytes ->
                if (throttle.shouldEmit(bytes, totalBytes)) onProgress(bytes, totalBytes)
            }
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

    suspend fun readCatalog(): WatchFaceCatalogSnapshot = withContext(ioDispatcher) { catalog.read() }

    suspend fun detachEditedMarketInstall(launcherId: String): WatchFaceCatalogSnapshot =
        withContext(ioDispatcher) { catalog.detachEditedMarketInstall(launcherId) }

    suspend fun restoreCatalogFromInstalledFaces(
        repairCorruptCatalog: Boolean = false,
    ): WatchStoreCatalogRestore = withContext(ioDispatcher) {
        val scan = readInstalledScan()
        val installedFaces = scan.descriptors
        val identities = installedFaces.map(WatchFaceDescriptor::toInstalledIdentity)
        val initial = catalog.read()
        if (initial.diagnostic != null && repairCorruptCatalog) {
            catalog.reconcileInstalledDescriptors(
                installed = identities,
                repairCorruptCatalog = true,
                scanComplete = scan.isComplete,
            )
        }
        if (catalog.read().diagnostic == null) {
            recoverPendingMarketInstalls(identities, scanComplete = scan.isComplete)
        }
        WatchStoreCatalogRestore(
            snapshot = catalog.reconcileInstalledDescriptors(
                installed = identities,
                scanComplete = scan.isComplete,
            ),
            installedFaces = installedFaces,
            scanFailures = scan.failures,
            scanComplete = scan.isComplete,
        )
    }

    /**
     * Publishes the durable intent before the package installer can expose a
     * new content-addressed directory to scans.
     */
    suspend fun beginMarketInstallTransaction(
        remoteId: String,
        revisionSha256: String,
    ) = withContext(ioDispatcher + NonCancellable) {
        val normalizedRemoteId = WatchStoreUrlPolicy.requireId(remoteId)
        val revision = revisionSha256.lowercase()
        require(revision.matches(Regex("[0-9a-f]{64}"))) { "Invalid market archive revision" }
        installJournal.begin(
            storeId = WATCHMAKER_COMMUNITY_STORE_ID,
            remoteId = normalizedRemoteId,
            revisionSha256 = revision,
            launcherId = "watchmaker:watch_${revision.take(32)}",
        )
    }

    suspend fun markMarketInstallPackagePublished(
        remoteId: String,
        installedDescriptorId: String,
        installedDisplayName: String?,
        installedAtEpochMs: Long,
    ) = withContext(ioDispatcher + NonCancellable) {
        installJournal.markInstalled(
            storeId = WATCHMAKER_COMMUNITY_STORE_ID,
            remoteId = WatchStoreUrlPolicy.requireId(remoteId),
            launcherId = installedDescriptorId,
            displayName = installedDisplayName,
            installedAtEpochMs = installedAtEpochMs,
        )
    }

    suspend fun discardMarketInstallTransaction(remoteId: String) =
        withContext(ioDispatcher + NonCancellable) {
            installJournal.remove(
                WATCHMAKER_COMMUNITY_STORE_ID,
                WatchStoreUrlPolicy.requireId(remoteId),
            )
        }

    /**
     * Failure/cancellation cleanup that preserves the journal whenever package
     * publication is known or the installed-directory scan is incomplete.
     */
    suspend fun discardAbandonedMarketInstallTransaction(remoteId: String) =
        withContext(ioDispatcher + NonCancellable) {
            val normalizedRemoteId = WatchStoreUrlPolicy.requireId(remoteId)
            val scan = try {
                readInstalledScan()
            } catch (error: Exception) {
                WatchmakerWatchFaceScanResult(
                    descriptors = emptyList(),
                    failures = listOf(
                        WatchmakerWatchFaceScanFailure(
                            directoryName = "",
                            message = error.message ?: error.javaClass.simpleName,
                        ),
                    ),
                )
            }
            installJournal.discardAbandoned(
                storeId = WATCHMAKER_COMMUNITY_STORE_ID,
                remoteId = normalizedRemoteId,
                installedLauncherIds = scan.descriptors.mapTo(HashSet()) { it.id },
                scanComplete = scan.isComplete,
            )
        }

    /**
     * Upgrade-only conservative association. The service search must report one
     * global result and its name must match exactly; any ambiguity remains Legacy.
     */
    suspend fun associateLegacyMarketInstalls(
        installedFaces: List<WatchFaceDescriptor>,
    ): WatchFaceCatalogSnapshot = withContext(ioDispatcher) {
        val identities = installedFaces.map(WatchFaceDescriptor::toInstalledIdentity)
        val snapshot = catalog.read()
        if (snapshot.diagnostic != null) return@withContext snapshot
        val uniqueLocalNames = snapshot.records.asSequence()
            .filter { it.source is WatchFaceInstallSource.Legacy }
            .mapNotNull { record -> record.installedDisplayName?.trim()?.takeIf(String::isNotEmpty) }
            .groupBy(::canonicalWatchFaceName)
            .filterValues { it.size == 1 }
            .values
            .map { it.single() }
            .sorted()
            .take(MAX_LEGACY_MARKET_PROBES)
        val candidates = uniqueLocalNames.mapNotNull { exactName ->
            val page = try {
                api.listWatchFaces(
                    WatchStoreQuery(
                        page = 1,
                        limit = 2,
                        sort = WatchStoreSort.LATEST,
                        search = exactName,
                    ),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                null
            }
            page?.takeIf { result ->
                result.pagination.total == 1L &&
                    result.data.size == 1 &&
                    canonicalWatchFaceName(result.data.single().name) == canonicalWatchFaceName(exactName)
            }?.data?.single()?.let { WatchFaceMarketCandidate(it.id, it.name.trim()) }
        }
        if (candidates.isEmpty()) snapshot else catalog.associateUniqueMarketNames(
            installed = identities,
            candidates = candidates,
            storeId = WATCHMAKER_COMMUNITY_STORE_ID,
        )
    }

    suspend fun migrateLegacyWatchmakerInstalls(installedDirectories: Map<String, String?>): WatchFaceCatalogSnapshot =
        withContext(ioDispatcher) {
            val now = System.currentTimeMillis()
            val installs = installedDirectories.mapValues { (_, path) ->
                path?.let(::File)?.takeIf(File::exists)?.lastModified()?.takeIf { it > 0L } ?: now
            }
            catalog.migrateLegacyWatchmakerIds(installs)
        }

    suspend fun computeRevisionSha256(archive: File): String = withContext(ioDispatcher) {
        require(archive.isFile) { "Downloaded watch archive is missing" }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun recordMarketInstallation(
        remoteId: String,
        activeRevisionSha256: String,
        installedDescriptorId: String,
        installedDisplayName: String? = null,
        installedAtEpochMs: Long = System.currentTimeMillis(),
        compatibilityReport: WatchStoreCompatibilityReport? = null,
    ): WatchFaceRecord = recordMarketInstallationDetailed(
        remoteId = remoteId,
        activeRevisionSha256 = activeRevisionSha256,
        installedDescriptorId = installedDescriptorId,
        installedDisplayName = installedDisplayName,
        installedAtEpochMs = installedAtEpochMs,
        compatibilityReport = compatibilityReport,
    ).record

    suspend fun recordMarketInstallationDetailed(
        remoteId: String,
        activeRevisionSha256: String,
        installedDescriptorId: String,
        installedDisplayName: String? = null,
        installedAtEpochMs: Long = System.currentTimeMillis(),
        compatibilityReport: WatchStoreCompatibilityReport? = null,
    ): WatchFaceCatalogUpsertResult = withContext(ioDispatcher + NonCancellable) {
        val normalizedRemoteId = WatchStoreUrlPolicy.requireId(remoteId)
        val record = WatchFaceRecord(
            logicalId = WatchFaceLogicalIds.market(WATCHMAKER_COMMUNITY_STORE_ID, normalizedRemoteId),
            activeRevisionSha256 = activeRevisionSha256.lowercase(),
            installedAtEpochMs = installedAtEpochMs,
            installedDisplayName = installedDisplayName?.trim()?.take(512)?.takeIf(String::isNotEmpty),
            displayNameOverride = null,
            source = WatchFaceInstallSource.Market(
                storeId = WATCHMAKER_COMMUNITY_STORE_ID,
                remoteId = normalizedRemoteId,
            ),
            legacyIds = setOf(installedDescriptorId),
            activeLauncherId = installedDescriptorId,
            compatibilityReport = compatibilityReport,
        )
        catalog.upsertDetailed(record).also {
            // A stale journal entry is harmless but would be replayed at every
            // launch. Catalog commit is already durable, so cleanup failure
            // must not roll back an otherwise successful install.
            runCatching {
                installJournal.remove(WATCHMAKER_COMMUNITY_STORE_ID, normalizedRemoteId)
            }
        }
    }

    suspend fun removeMarketInstallation(remoteId: String): WatchFaceCatalogSnapshot = withContext(ioDispatcher) {
        catalog.removeMarketInstall(WATCHMAKER_COMMUNITY_STORE_ID, WatchStoreUrlPolicy.requireId(remoteId))
    }

    suspend fun renameInstalledWatchFace(logicalId: String, displayName: String?): WatchFaceCatalogSnapshot =
        withContext(ioDispatcher) { catalog.rename(logicalId, displayName) }

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

    /** Clears only public-store cover images, leaving downloads and installed faces untouched. */
    suspend fun clearPreviewCache(): Int = withContext(ioDispatcher) {
        clearPreviewMemoryCache()
        synchronized(cleanupLock) {
            var removed = 0
            previewDirectory.listFiles().orEmpty().forEach { file ->
                if (file.isFile && file.delete()) removed++
            }
            removed
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

    private fun recoverPendingMarketInstalls(
        installed: List<InstalledWatchFaceIdentity>,
        scanComplete: Boolean,
    ) {
        val installedById = installed.associateBy { it.launcherId.lowercase() }
        installJournal.read().forEach { pending ->
            if (pending.storeId != WATCHMAKER_COMMUNITY_STORE_ID) return@forEach
            val identity = installedById[pending.launcherId.lowercase()] ?: return@forEach
            val compatibilityReport = identity.sourceDirPath?.let { sourceDirPath ->
                WatchStoreCompatibilityInspector.inspect(
                    rootDirectory = File(sourceDirPath),
                    previewFile = identity.previewFilePath?.let(::File),
                )
            }
            catalog.upsertDetailed(
                WatchFaceRecord(
                    logicalId = WatchFaceLogicalIds.market(pending.storeId, pending.remoteId),
                    activeRevisionSha256 = pending.revisionSha256,
                    installedAtEpochMs = pending.installedAtEpochMs
                        ?: identity.installedAtEpochMs.coerceAtLeast(1L),
                    installedDisplayName = pending.displayName
                        ?.trim()
                        ?.take(512)
                        ?.takeIf(String::isNotEmpty)
                        ?: identity.displayName.trim().take(512).takeIf(String::isNotEmpty),
                    displayNameOverride = null,
                    source = WatchFaceInstallSource.Market(pending.storeId, pending.remoteId),
                    legacyIds = setOf(identity.launcherId),
                    activeLauncherId = identity.launcherId,
                    compatibilityReport = compatibilityReport,
                ),
            )
            installJournal.remove(pending.storeId, pending.remoteId)
        }
        if (scanComplete) {
            installJournal.removeExpiredMissing(installedById.values.mapTo(HashSet()) { it.launcherId })
        }
    }

    private fun readInstalledScan(): WatchmakerWatchFaceScanResult =
        installedWatchFaceScanProvider?.invoke()
            ?: WatchmakerWatchFaceScanResult(
                descriptors = installedWatchFacesProvider(),
                failures = emptyList(),
            )

    companion object {
        private const val CACHE_DIRECTORY_NAME = "flue_watch_store"
        private const val CATALOG_RELATIVE_PATH = "watchface/watch-face-catalog.json"
        private const val INSTALL_JOURNAL_RELATIVE_PATH = "watchface/watch-face-install-journal.json"
        private const val DEFAULT_PREVIEW_SIZE = 512
        private const val MAX_PREVIEW_DIMENSION = 2_048
        private const val MAX_MEMORY_PREVIEWS = 16
        private const val MAX_MEMORY_PREVIEW_BYTES = 12L * 1024L * 1024L
        private const val MAX_DISK_PREVIEW_BYTES = 64L * 1024L * 1024L
        private const val CLEANUP_INTERVAL_MS = 6L * 60L * 60L * 1_000L
        private const val PARTIAL_MAX_AGE_MS = 60L * 60L * 1_000L
        private const val DOWNLOAD_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val PREVIEW_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val MAX_LEGACY_MARKET_PROBES = 24
    }
}

data class WatchStoreCatalogRestore(
    val snapshot: WatchFaceCatalogSnapshot,
    val installedFaces: List<WatchFaceDescriptor>,
    val scanFailures: List<WatchmakerWatchFaceScanFailure> = emptyList(),
    val scanComplete: Boolean = scanFailures.isEmpty(),
)

private fun WatchFaceDescriptor.toInstalledIdentity(): InstalledWatchFaceIdentity =
    InstalledWatchFaceIdentity(
        launcherId = id,
        displayName = displayName,
        installedAtEpochMs = sourceDirPath
            ?.let(::File)
            ?.let { directory ->
                WatchInstallMetadata.read(directory)
                    ?: directory.lastModified().takeIf { it > 0L }
            }
            ?: 1L,
        sourceDirPath = sourceDirPath,
        previewFilePath = previewFilePath,
    )
