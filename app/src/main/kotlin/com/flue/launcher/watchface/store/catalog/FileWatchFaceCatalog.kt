package com.flue.launcher.watchface.store.catalog

import com.flue.launcher.watchface.store.toJsonObject
import com.flue.launcher.watchface.store.toWatchStoreCompatibilityReport
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, process-safe catalog for installed watch faces.
 *
 * Every mutation re-reads under a file lock and publishes through a same-directory
 * atomic replacement. A malformed catalog is never silently overwritten: an
 * immutable diagnostic copy is retained before the next valid write.
 */
class FileWatchFaceCatalog(
    private val catalogFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lockFile = File(catalogFile.parentFile, "${catalogFile.name}.lock")
    private val processLock = processLocks.getOrPut(catalogFile.absoluteFile.normalize().path) { Any() }

    fun read(): WatchFaceCatalogSnapshot = withCatalogLock { readLocked() }

    fun upsert(record: WatchFaceRecord): WatchFaceCatalogSnapshot = upsertDetailed(record).snapshot

    fun upsertDetailed(record: WatchFaceRecord): WatchFaceCatalogUpsertResult = withCatalogLock {
        val before = readLocked()
        val current = before.records
        val matchingMarket = record.source as? WatchFaceInstallSource.Market
        val matches = current.filter { existing ->
                existing.logicalId == record.logicalId ||
                (matchingMarket != null && existing.source == matchingMarket) ||
                (existing.activeLauncherId != null &&
                    existing.activeLauncherId == record.activeLauncherId) ||
                (existing.activeLauncherId?.let(record.legacyIds::contains) == true) ||
                (record.activeLauncherId?.let(existing.legacyIds::contains) == true) ||
                existing.legacyIds.any(record.legacyIds::contains)
        }
        val replaced = current - matches.toSet()
        val inheritedAliases = matches.asSequence()
            .flatMap { it.legacyIds.asSequence() }
            .toSet()
        val inheritedName = matches.asSequence()
            .mapNotNull { it.displayNameOverride?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
        val inheritedInstalledAt = matches.asSequence()
            .map(WatchFaceRecord::installedAtEpochMs)
            .filter { it > 0L }
            .maxOrNull()
        val inheritedCompatibilityReport = matches.asSequence()
            .mapNotNull(WatchFaceRecord::compatibilityReport)
            .firstOrNull()
        val merged = record.copy(
            installedAtEpochMs = maxOf(record.installedAtEpochMs, inheritedInstalledAt ?: 0L),
            displayNameOverride = record.displayNameOverride?.trim()?.takeIf(String::isNotEmpty)
                ?: inheritedName,
            legacyIds = inheritedAliases + record.legacyIds,
            compatibilityReport = record.compatibilityReport ?: inheritedCompatibilityReport,
        )
        val records = normalize(replaced + merged)
        writeLocked(records)
        val snapshot = WatchFaceCatalogSnapshot(records = records, diagnostic = before.diagnostic)
        val persisted = snapshot.resolveAlias(merged.logicalId)
            ?: error("Upserted watch face record is missing")
        val activeLauncherId = persisted.activeLauncherIdOrNull()
        val supersededLauncherIds = matches.asSequence()
            .flatMap { existing ->
                sequenceOf(existing.activeLauncherId).filterNotNull() + existing.legacyIds.asSequence()
            }
            .filter { it != activeLauncherId }
            .toSet()
        WatchFaceCatalogUpsertResult(snapshot, persisted, supersededLauncherIds)
    }

    fun removeMarketInstall(storeId: String, remoteId: String): WatchFaceCatalogSnapshot = mutate { current ->
        current.filterNot { record ->
            val source = record.source as? WatchFaceInstallSource.Market
            source?.storeId == storeId && source.remoteId == remoteId
        }
    }

    fun rename(logicalId: String, displayNameOverride: String?): WatchFaceCatalogSnapshot = mutate { current ->
        val normalized = displayNameOverride?.trim()?.takeIf(String::isNotEmpty)
        current.map { record ->
            if (record.logicalId == logicalId) record.copy(displayNameOverride = normalized) else record
        }
    }

    fun migrateLegacyWatchmakerId(
        legacyId: String,
        installedAtEpochMs: Long = clock(),
    ): WatchFaceRecord {
        read().resolveAlias(legacyId)?.let { return it }
        val record = legacyRecord(legacyId, installedAtEpochMs)
            ?: throw IllegalArgumentException("Not a legacy WatchMaker id")
        return upsert(record).resolveAlias(legacyId) ?: record
    }

    fun migrateLegacyWatchmakerIds(installs: Map<String, Long>): WatchFaceCatalogSnapshot = withCatalogLock {
        val before = readLocked()
        val knownAliases = before.records.flatMapTo(HashSet()) { it.legacyIds }
        val additions = installs.mapNotNull { (legacyId, installedAtEpochMs) ->
            if (legacyId in knownAliases) null else legacyRecord(legacyId, installedAtEpochMs)
        }
        if (additions.isEmpty()) return@withCatalogLock before
        val records = normalize(before.records + additions)
        writeLocked(records)
        WatchFaceCatalogSnapshot(records = records, diagnostic = before.diagnostic)
    }

    /**
     * Reconciles durable state against the authoritative standard-watch scan.
     * Market records are kept only when their active revision descriptor still
     * exists; historical aliases must not make a deleted active revision look installed.
     */
    fun reconcileInstalledDescriptors(
        installed: List<InstalledWatchFaceIdentity>,
        repairCorruptCatalog: Boolean = false,
        scanComplete: Boolean = true,
    ): WatchFaceCatalogSnapshot = withCatalogLock {
        val before = readLocked()
        if (before.diagnostic != null && !repairCorruptCatalog) {
            return@withCatalogLock before
        }
        val installedById = installed.associateBy { it.launcherId.lowercase() }
        val retained = before.records.mapNotNull { record ->
            val declaredActiveId = record.activeLauncherId
            val identity = if (declaredActiveId != null) {
                installedById[declaredActiveId.lowercase()]
            } else {
                // Schema 1 did not distinguish the active descriptor from old
                // aliases. Resolve it once against the authoritative disk scan
                // and persist the concrete id in schema 2.
                record.revisionLauncherIdOrNull()?.let { installedById[it.lowercase()] }
                    ?: record.activeLauncherIdOrNull()?.let { installedById[it.lowercase()] }
                    ?: record.legacyIds.asSequence()
                        .mapNotNull { installedById[it.lowercase()] }
                        .distinctBy { it.launcherId.lowercase() }
                        .maxWithOrNull(
                            compareBy<InstalledWatchFaceIdentity> { it.installedAtEpochMs }
                                .thenBy { it.launcherId.lowercase() },
                        )
            }
            if (identity == null) {
                // A parser/repair failure is not evidence that the installed
                // directory was deleted. Keep durable market identity until a
                // complete scan proves the active launcher id is absent.
                return@mapNotNull record.takeUnless { scanComplete }
            }
            record.copy(
                installedDisplayName = identity.displayName.normalizedDisplayName(),
                legacyIds = record.legacyIds + identity.launcherId,
                activeLauncherId = identity.launcherId,
            )
        }
        val knownAliases = retained.asSequence()
            .flatMap {
                listOfNotNull(it.activeLauncherId).asSequence() + it.legacyIds.asSequence()
            }
            .mapTo(HashSet()) { it.lowercase() }
        val additions = installed.mapNotNull { identity ->
            if (identity.launcherId.lowercase() in knownAliases) null else legacyRecord(identity)
        }
        val records = normalize(retained + additions)
        if (records != before.records || repairCorruptCatalog) writeLocked(records)
        WatchFaceCatalogSnapshot(records = records, diagnostic = before.diagnostic)
    }

    /** Converts only exact one-to-one legacy names into durable market identities. */
    fun associateUniqueMarketNames(
        installed: List<InstalledWatchFaceIdentity>,
        candidates: List<WatchFaceMarketCandidate>,
        storeId: String,
    ): WatchFaceCatalogSnapshot = withCatalogLock {
        val before = readLocked()
        val installedById = installed.associateBy { it.launcherId.lowercase() }
        val installedByName = installed
            .filter { canonicalWatchFaceName(it.displayName).isNotEmpty() }
            .groupBy { canonicalWatchFaceName(it.displayName) }
        val candidatesByName = candidates
            .filter { canonicalWatchFaceName(it.displayName).isNotEmpty() }
            .groupBy { canonicalWatchFaceName(it.displayName) }
        val usedRemoteIds = before.records.mapNotNullTo(HashSet()) { record ->
            (record.source as? WatchFaceInstallSource.Market)
                ?.takeIf { it.storeId == storeId }
                ?.remoteId
        }
        var changed = false
        val associated = before.records.map { record ->
            if (record.source !is WatchFaceInstallSource.Legacy) return@map record
            val identity = record.activeLauncherIdOrNull()?.let { installedById[it.lowercase()] }
                ?: record.revisionLauncherIdOrNull()?.let { installedById[it.lowercase()] }
                ?: record.legacyIds.asSequence()
                    .mapNotNull { installedById[it.lowercase()] }
                    .distinctBy { it.launcherId.lowercase() }
                    .maxWithOrNull(
                        compareBy<InstalledWatchFaceIdentity> { it.installedAtEpochMs }
                            .thenBy { it.launcherId.lowercase() },
                    )
                ?: return@map record
            val nameKey = canonicalWatchFaceName(identity.displayName)
            if (installedByName[nameKey]?.size != 1) return@map record
            val candidate = candidatesByName[nameKey]?.singleOrNull() ?: return@map record
            if (!usedRemoteIds.add(candidate.remoteId)) return@map record
            changed = true
            record.copy(
                logicalId = WatchFaceLogicalIds.market(storeId, candidate.remoteId),
                installedDisplayName = identity.displayName.normalizedDisplayName(),
                source = WatchFaceInstallSource.Market(storeId, candidate.remoteId),
                activeLauncherId = identity.launcherId,
            )
        }
        val records = normalize(associated)
        if (changed) writeLocked(records)
        WatchFaceCatalogSnapshot(records = records, diagnostic = before.diagnostic)
    }

    private fun mutate(transform: (List<WatchFaceRecord>) -> List<WatchFaceRecord>): WatchFaceCatalogSnapshot =
        withCatalogLock {
            val before = readLocked()
            val records = normalize(transform(before.records))
            writeLocked(records)
            WatchFaceCatalogSnapshot(records = records, diagnostic = before.diagnostic)
        }

    private fun readLocked(): WatchFaceCatalogSnapshot {
        if (!catalogFile.isFile) return WatchFaceCatalogSnapshot()
        return try {
            val length = catalogFile.length()
            require(length in 1..MAX_CATALOG_BYTES) { "Catalog size is outside the allowed range" }
            val root = JSONObject(catalogFile.readText(Charsets.UTF_8))
            val schemaVersion = root.requireInt("schemaVersion")
            require(schemaVersion in 1..WatchFaceCatalogSnapshot.CURRENT_SCHEMA_VERSION) {
                "Unsupported catalog schema $schemaVersion"
            }
            val values = root.optJSONArray("records") ?: error("Catalog records are missing")
            require(values.length() <= MAX_RECORDS) { "Catalog contains too many records" }
            val parsed = buildList(values.length()) {
                for (index in 0 until values.length()) {
                    add(parseRecord(values.optJSONObject(index) ?: error("Invalid catalog record $index")))
                }
            }
            WatchFaceCatalogSnapshot(
                schemaVersion = WatchFaceCatalogSnapshot.CURRENT_SCHEMA_VERSION,
                records = normalize(parsed),
            )
        } catch (error: Exception) {
            val preserved = preserveCorruptCatalog()
            WatchFaceCatalogSnapshot(
                diagnostic = WatchFaceCatalogDiagnostic(
                    message = error.message?.take(240) ?: error.javaClass.simpleName,
                    preservedCopyPath = preserved?.absolutePath,
                ),
            )
        }
    }

    private fun parseRecord(json: JSONObject): WatchFaceRecord {
        val sourceJson = json.optJSONObject("source") ?: error("Catalog source is missing")
        val source = when (val kind = sourceJson.requireString("kind")) {
            WatchFaceInstallSource.Market.KIND -> WatchFaceInstallSource.Market(
                storeId = sourceJson.requireString("storeId"),
                remoteId = sourceJson.requireString("remoteId"),
            )
            WatchFaceInstallSource.LocalImport.KIND -> WatchFaceInstallSource.LocalImport(
                sourceLabel = sourceJson.optionalString("sourceLabel"),
            )
            WatchFaceInstallSource.Legacy.kind -> WatchFaceInstallSource.Legacy
            else -> error("Unsupported watch source: $kind")
        }
        val aliases = json.optJSONArray("legacyIds") ?: JSONArray()
        require(aliases.length() <= WatchFaceRecord.MAX_LEGACY_IDS) { "Too many legacy aliases" }
        return WatchFaceRecord(
            logicalId = json.requireString("logicalId"),
            activeRevisionSha256 = json.requireString("activeRevisionSha256").lowercase(),
            installedAtEpochMs = json.requireLong("installedAtEpochMs"),
            installedDisplayName = json.optionalString("installedDisplayName"),
            displayNameOverride = json.optionalString("displayNameOverride"),
            source = source,
            legacyIds = buildSet(aliases.length()) {
                for (index in 0 until aliases.length()) {
                    add(aliases.optString(index, "").takeIf(String::isNotBlank) ?: error("Invalid legacy alias"))
                }
            },
            activeLauncherId = json.optionalString("activeLauncherId"),
            compatibilityReport = json.optJSONObject("compatibilityReport")?.toWatchStoreCompatibilityReport(),
        )
    }

    private fun writeLocked(records: List<WatchFaceRecord>) {
        ensureParentDirectory()
        val root = JSONObject()
            .put("schemaVersion", WatchFaceCatalogSnapshot.CURRENT_SCHEMA_VERSION)
            .put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
        val payload = root.toString()
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_CATALOG_BYTES) { "Catalog is too large" }
        val temporary = File(catalogFile.parentFile, ".${catalogFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    catalogFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), catalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun WatchFaceRecord.toJson(): JSONObject = JSONObject()
        .put("logicalId", logicalId)
        .put("activeRevisionSha256", activeRevisionSha256.lowercase())
        .put("installedAtEpochMs", installedAtEpochMs)
        .put("installedDisplayName", installedDisplayName ?: JSONObject.NULL)
        .put("displayNameOverride", displayNameOverride ?: JSONObject.NULL)
        .put("source", source.toJson())
        .put("legacyIds", JSONArray().apply { legacyIds.sorted().forEach(::put) })
        .put("activeLauncherId", activeLauncherId ?: JSONObject.NULL)
        .put("compatibilityReport", compatibilityReport?.toJsonObject() ?: JSONObject.NULL)


    private fun WatchFaceInstallSource.toJson(): JSONObject = when (this) {
        is WatchFaceInstallSource.Market -> JSONObject()
            .put("kind", kind)
            .put("storeId", storeId)
            .put("remoteId", remoteId)
        is WatchFaceInstallSource.LocalImport -> JSONObject()
            .put("kind", kind)
            .put("sourceLabel", sourceLabel ?: JSONObject.NULL)
        WatchFaceInstallSource.Legacy -> JSONObject().put("kind", kind)
    }

    private fun normalize(records: List<WatchFaceRecord>): List<WatchFaceRecord> {
        val preferred = records.sortedWith(
            compareByDescending<WatchFaceRecord> { it.source is WatchFaceInstallSource.Market }
                .thenByDescending { it.installedAtEpochMs }
                .thenBy { it.logicalId },
        )
        return preferred
            .let { sorted ->
            val logicalIds = HashSet<String>()
            val marketIds = HashSet<String>()
            val aliases = HashSet<String>()
            sorted.filter { record ->
                val marketKey = (record.source as? WatchFaceInstallSource.Market)
                    ?.let { "${it.storeId}\n${it.remoteId}" }
                val allAliases = buildSet {
                    addAll(record.legacyIds)
                    record.activeLauncherId?.let(::add)
                }
                logicalIds.add(record.logicalId) &&
                    (marketKey == null || marketIds.add(marketKey)) &&
                    allAliases.none(aliases::contains).also { unique ->
                        if (unique) aliases += allAliases
                    }
            }
        }
            .sortedWith(compareByDescending<WatchFaceRecord> { it.installedAtEpochMs }.thenBy { it.logicalId })
    }

    private fun legacyRecord(legacyId: String, installedAtEpochMs: Long): WatchFaceRecord? {
        val match = LEGACY_WATCHMAKER_ID.matchEntire(legacyId) ?: return null
        val hashPrefix = match.groupValues[1].lowercase()
        return WatchFaceRecord(
            logicalId = "legacy:watchmaker:$hashPrefix",
            activeRevisionSha256 = hashPrefix,
            installedAtEpochMs = installedAtEpochMs.coerceAtLeast(0L),
            installedDisplayName = null,
            displayNameOverride = null,
            source = WatchFaceInstallSource.Legacy,
            legacyIds = setOf(legacyId),
            activeLauncherId = legacyId,
        )
    }

    private fun legacyRecord(identity: InstalledWatchFaceIdentity): WatchFaceRecord? {
        val match = LEGACY_WATCHMAKER_ID.matchEntire(identity.launcherId) ?: return null
        val hashPrefix = match.groupValues[1].lowercase()
        return WatchFaceRecord(
            logicalId = "legacy:watchmaker:$hashPrefix",
            activeRevisionSha256 = hashPrefix,
            installedAtEpochMs = identity.installedAtEpochMs.coerceAtLeast(0L),
            installedDisplayName = identity.displayName.normalizedDisplayName(),
            displayNameOverride = null,
            source = WatchFaceInstallSource.Legacy,
            legacyIds = setOf(identity.launcherId),
            activeLauncherId = identity.launcherId,
        )
    }

    private fun String.normalizedDisplayName(): String? = trim().takeIf(String::isNotEmpty)
        ?.take(WatchFaceRecord.MAX_DISPLAY_NAME_LENGTH)

    /**
     * Older WatchMaker installs commonly used the first 32 SHA characters as
     * their descriptor id. Prefer that deterministic alias before choosing by
     * mtime when a catalog record has multiple historical ids.
     */
    private fun WatchFaceRecord.revisionLauncherIdOrNull(): String? =
        activeRevisionSha256
            .takeIf { it.length == 32 || it.length == 64 }
            ?.take(32)
            ?.let { "watchmaker:watch_$it" }

    private fun preserveCorruptCatalog(): File? {
        if (!catalogFile.isFile) return null
        return runCatching {
            val suffix = sha256(catalogFile).take(12)
            val destination = File(catalogFile.parentFile, "${catalogFile.name}.corrupt-$suffix")
            if (!destination.exists()) catalogFile.copyTo(destination, overwrite = false)
            destination
        }.getOrNull()
    }

    private fun <T> withCatalogLock(block: () -> T): T = synchronized(processLock) {
        ensureParentDirectory()
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun ensureParentDirectory() {
        val parent = catalogFile.parentFile ?: error("Catalog must have a parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            error("Cannot create watch catalog directory")
        }
        require(parent.isDirectory) { "Watch catalog parent is not a directory" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JSONObject.requireString(name: String): String {
        require(has(name) && !isNull(name)) { "Missing string: $name" }
        return getString(name).also { require(it.isNotBlank()) { "Blank string: $name" } }
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.requireLong(name: String): Long {
        require(has(name) && !isNull(name)) { "Missing number: $name" }
        return getLong(name)
    }

    private fun JSONObject.requireInt(name: String): Int {
        require(has(name) && !isNull(name)) { "Missing number: $name" }
        return getInt(name)
    }

    private companion object {
        const val MAX_CATALOG_BYTES = 2L * 1024L * 1024L
        const val MAX_RECORDS = 4_096
        val LEGACY_WATCHMAKER_ID = Regex("watchmaker:watch_([0-9a-fA-F]{32}|[0-9a-fA-F]{64})")
        val processLocks = ConcurrentHashMap<String, Any>()
    }
}
