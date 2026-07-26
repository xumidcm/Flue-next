package com.flue.launcher.watchface.store.catalog

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable bridge between publishing an installed package directory and
 * publishing its catalog record.
 *
 * Android may kill the process after the content-addressed directory is
 * installed but before the catalog is committed. Without this journal the next
 * scan can only classify that directory as Legacy, which makes a downloaded
 * market face look downloadable again. The journal is written before package
 * installation and removed only after the catalog commit succeeds.
 */
class FileWatchFaceInstallJournal(
    private val journalFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lockFile = File(journalFile.parentFile, "${journalFile.name}.lock")
    private val processLock = processLocks.getOrPut(journalFile.absoluteFile.normalize().path) { Any() }

    fun read(): List<PendingMarketInstallRecord> = withLock { readLocked() }

    fun begin(
        storeId: String,
        remoteId: String,
        revisionSha256: String,
        launcherId: String,
    ): PendingMarketInstallRecord = mutate { records ->
        val existing = records.firstOrNull { it.storeId == storeId && it.remoteId == remoteId }
        val next = PendingMarketInstallRecord(
            storeId = storeId,
            remoteId = remoteId,
            revisionSha256 = revisionSha256.lowercase(),
            launcherId = launcherId,
            displayName = existing?.displayName,
            installedAtEpochMs = existing?.installedAtEpochMs,
            startedAtEpochMs = existing?.startedAtEpochMs?.takeIf { it > 0L } ?: clock().coerceAtLeast(1L),
        )
        records.filterNot { it.storeId == storeId && it.remoteId == remoteId } + next
    }.first { it.storeId == storeId && it.remoteId == remoteId }

    fun markInstalled(
        storeId: String,
        remoteId: String,
        launcherId: String,
        displayName: String?,
        installedAtEpochMs: Long,
    ): PendingMarketInstallRecord = mutate { records ->
        val current = records.firstOrNull { it.storeId == storeId && it.remoteId == remoteId }
            ?: throw IllegalStateException("Market install journal entry is missing")
        val next = current.copy(
            launcherId = launcherId,
            displayName = displayName?.trim()?.take(MAX_DISPLAY_NAME_LENGTH)?.takeIf(String::isNotEmpty),
            installedAtEpochMs = installedAtEpochMs.coerceAtLeast(1L),
        )
        records.filterNot { it.storeId == storeId && it.remoteId == remoteId } + next
    }.first { it.storeId == storeId && it.remoteId == remoteId }

    fun remove(storeId: String, remoteId: String): List<PendingMarketInstallRecord> = mutate { records ->
        records.filterNot { it.storeId == storeId && it.remoteId == remoteId }
    }

    /**
     * Clears a failed/cancelled transaction only when a complete disk scan
     * proves that no package was published. Marked-installed entries and
     * uncertain scans remain available for restart recovery.
     */
    fun discardAbandoned(
        storeId: String,
        remoteId: String,
        installedLauncherIds: Set<String>,
        scanComplete: Boolean,
    ): List<PendingMarketInstallRecord> {
        val installed = installedLauncherIds.mapTo(HashSet()) { it.lowercase() }
        return mutate { records ->
            records.filterNot { record ->
                record.storeId == storeId &&
                    record.remoteId == remoteId &&
                    record.installedAtEpochMs == null &&
                    scanComplete &&
                    record.launcherId.lowercase() !in installed
            }
        }
    }

    fun removeExpiredMissing(
        installedLauncherIds: Set<String>,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): List<PendingMarketInstallRecord> = mutate { records ->
        val now = clock()
        records.filter { record ->
            record.launcherId in installedLauncherIds ||
                now - record.startedAtEpochMs.coerceAtMost(now) <= maxAgeMs
        }
    }

    private fun mutate(
        transform: (List<PendingMarketInstallRecord>) -> List<PendingMarketInstallRecord>,
    ): List<PendingMarketInstallRecord> = withLock {
        val next = normalize(transform(readLocked()))
        writeLocked(next)
        next
    }

    private fun readLocked(): List<PendingMarketInstallRecord> {
        if (!journalFile.isFile) return emptyList()
        return try {
            require(journalFile.length() in 1L..MAX_JOURNAL_BYTES) { "Install journal is too large" }
            val root = JSONObject(journalFile.readText(Charsets.UTF_8))
            require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported install journal schema" }
            val entries = root.getJSONArray("entries")
            require(entries.length() <= MAX_ENTRIES) { "Install journal has too many entries" }
            buildList(entries.length()) {
                for (index in 0 until entries.length()) {
                    val value = entries.getJSONObject(index)
                    add(
                        PendingMarketInstallRecord(
                            storeId = value.getString("storeId"),
                            remoteId = value.getString("remoteId"),
                            revisionSha256 = value.getString("revisionSha256").lowercase(),
                            launcherId = value.getString("launcherId"),
                            displayName = value.optString("displayName")
                                .takeUnless { value.isNull("displayName") }
                                ?.take(MAX_DISPLAY_NAME_LENGTH),
                            installedAtEpochMs = value.optLong("installedAtEpochMs")
                                .takeIf { value.has("installedAtEpochMs") && !value.isNull("installedAtEpochMs") },
                            startedAtEpochMs = value.getLong("startedAtEpochMs"),
                        ),
                    )
                }
            }.let(::normalize)
        } catch (_: Exception) {
            // A corrupt pending journal must not break the market or catalog.
            // Keep an immutable diagnostic copy and start a fresh transaction.
            preserveCorruptJournal()
            emptyList()
        }
    }

    private fun writeLocked(records: List<PendingMarketInstallRecord>) {
        ensureParentDirectory()
        if (records.isEmpty()) {
            if (journalFile.exists() && !journalFile.delete()) {
                throw IllegalStateException("Cannot clear watch install journal")
            }
            return
        }
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put(
                "entries",
                JSONArray().apply {
                    records.forEach { record ->
                        put(
                            JSONObject()
                                .put("storeId", record.storeId)
                                .put("remoteId", record.remoteId)
                                .put("revisionSha256", record.revisionSha256)
                                .put("launcherId", record.launcherId)
                                .put("displayName", record.displayName ?: JSONObject.NULL)
                                .put("installedAtEpochMs", record.installedAtEpochMs ?: JSONObject.NULL)
                                .put("startedAtEpochMs", record.startedAtEpochMs),
                        )
                    }
                },
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_JOURNAL_BYTES) { "Install journal is too large" }
        val temporary = File(journalFile.parentFile, ".${journalFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    journalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    journalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun normalize(records: List<PendingMarketInstallRecord>): List<PendingMarketInstallRecord> {
        val keys = HashSet<String>()
        return records.asReversed()
            .filter { keys.add("${it.storeId}\n${it.remoteId}") }
            .asReversed()
            .sortedWith(compareBy<PendingMarketInstallRecord> { it.startedAtEpochMs }.thenBy { it.remoteId })
    }

    private fun preserveCorruptJournal() {
        if (!journalFile.isFile) return
        runCatching {
            val destination = File(
                journalFile.parentFile,
                "${journalFile.name}.corrupt-${clock().coerceAtLeast(1L)}",
            )
            if (!destination.exists()) journalFile.copyTo(destination, overwrite = false)
        }
    }

    private fun <T> withLock(block: () -> T): T = synchronized(processLock) {
        ensureParentDirectory()
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun ensureParentDirectory() {
        val parent = journalFile.parentFile ?: error("Install journal must have a parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            error("Cannot create watch install journal directory")
        }
        require(parent.isDirectory) { "Install journal parent is not a directory" }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_ENTRIES = 128
        const val MAX_JOURNAL_BYTES = 256 * 1024
        const val MAX_DISPLAY_NAME_LENGTH = 512
        const val DEFAULT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
        val processLocks = ConcurrentHashMap<String, Any>()
    }
}

data class PendingMarketInstallRecord(
    val storeId: String,
    val remoteId: String,
    val revisionSha256: String,
    val launcherId: String,
    val displayName: String?,
    val installedAtEpochMs: Long?,
    val startedAtEpochMs: Long,
) {
    init {
        require(storeId.isNotBlank() && storeId.length <= 512) { "Invalid store id" }
        require(remoteId.isNotBlank() && remoteId.length <= 512) { "Invalid remote id" }
        require(revisionSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid revision SHA-256" }
        require(launcherId.isNotBlank() && launcherId.length <= 1_024) { "Invalid launcher id" }
        require(displayName == null || displayName.length <= 512) { "Display name is too long" }
        require(installedAtEpochMs == null || installedAtEpochMs > 0L) { "Invalid installation time" }
        require(startedAtEpochMs > 0L) { "Invalid transaction start time" }
    }
}
