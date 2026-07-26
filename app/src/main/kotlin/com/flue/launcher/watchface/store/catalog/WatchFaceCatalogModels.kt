package com.flue.launcher.watchface.store.catalog

import java.security.MessageDigest
import java.util.Locale

/** Durable origin metadata. Market identity is never inferred from a display name. */
sealed interface WatchFaceInstallSource {
    val kind: String

    data class Market(
        val storeId: String,
        val remoteId: String,
    ) : WatchFaceInstallSource {
        override val kind: String = KIND

        init {
            require(storeId.isNotBlank() && storeId.length <= MAX_ID_LENGTH) { "Invalid watch store id" }
            require(remoteId.isNotBlank() && remoteId.length <= MAX_ID_LENGTH) { "Invalid remote watch id" }
        }

        companion object {
            const val KIND = "market"
        }
    }

    data class LocalImport(
        val sourceLabel: String? = null,
    ) : WatchFaceInstallSource {
        override val kind: String = KIND

        init {
            require(sourceLabel == null || sourceLabel.length <= MAX_LABEL_LENGTH) { "Source label is too long" }
        }

        companion object {
            const val KIND = "local_import"
        }
    }

    /** A pre-catalog install discovered through its historical launcher id. */
    data object Legacy : WatchFaceInstallSource {
        override val kind: String = "legacy"
    }

    companion object {
        internal const val MAX_ID_LENGTH = 512
        internal const val MAX_LABEL_LENGTH = 1_024
    }
}

data class WatchFaceRecord(
    /** Stable identity of this face across package revisions. */
    val logicalId: String,
    /** Full SHA-256, or the historical 32-hex prefix when migrated from an old launcher id. */
    val activeRevisionSha256: String,
    val installedAtEpochMs: Long,
    /** Name parsed from the installed package; refreshed by local reconciliation. */
    val installedDisplayName: String? = null,
    val displayNameOverride: String?,
    val source: WatchFaceInstallSource,
    /** Old ids remain resolvable so the user's selected-watch preference does not break. */
    val legacyIds: Set<String>,
    /**
     * The descriptor id currently present on disk.  This is deliberately kept
     * separate from [activeRevisionSha256]: old installs may have a directory
     * id that is not derived from the downloaded archive hash.
     */
    val activeLauncherId: String? = null,
) {
    init {
        require(logicalId.isNotBlank() && logicalId.length <= MAX_LOGICAL_ID_LENGTH) {
            "Invalid logical watch id"
        }
        require(activeRevisionSha256.matches(REVISION_PATTERN)) {
            "activeRevisionSha256 must be a SHA-256 value or a legacy 32-hex prefix"
        }
        require(installedAtEpochMs >= 0L) { "installedAtEpochMs cannot be negative" }
        require(displayNameOverride == null || displayNameOverride.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Display name is too long"
        }
        require(installedDisplayName == null || installedDisplayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Installed display name is too long"
        }
        require(activeLauncherId == null ||
            activeLauncherId.isNotBlank() && activeLauncherId.length <= MAX_LOGICAL_ID_LENGTH) {
            "Invalid active launcher id"
        }
        require(legacyIds.size <= MAX_LEGACY_IDS) { "Too many legacy watch ids" }
        require(legacyIds.all { it.isNotBlank() && it.length <= MAX_LOGICAL_ID_LENGTH }) {
            "Invalid legacy watch id"
        }
    }

    /**
     * Current runtime still launches the installed descriptor by its historical id.
     * A market update retains older aliases for selection migration, so collection
     * order cannot decide which revision is active.
     */
    fun activeLauncherIdOrNull(): String? {
        activeLauncherId?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        // Schema 1 had no active field. A single alias is unambiguous; multiple
        // aliases must be resolved against the authoritative installed scan.
        return legacyIds.singleOrNull()
    }

    fun launcherIdOrNull(): String? {
        return activeLauncherIdOrNull()
            ?: legacyIds.firstOrNull { it.startsWith("watchmaker:watch_") }
            ?: legacyIds.firstOrNull()
    }

    fun resolvedDisplayName(marketName: String? = null): String? =
        displayNameOverride?.trim()?.takeIf(String::isNotEmpty)
            ?: installedDisplayName?.trim()?.takeIf(String::isNotEmpty)
            ?: marketName?.trim()?.takeIf(String::isNotEmpty)

    companion object {
        private val REVISION_PATTERN = Regex("(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{64})")
        internal const val MAX_LOGICAL_ID_LENGTH = 1_024
        internal const val MAX_DISPLAY_NAME_LENGTH = 512
        internal const val MAX_LEGACY_IDS = 64
    }
}

data class InstalledWatchFaceIdentity(
    val launcherId: String,
    val displayName: String,
    val installedAtEpochMs: Long,
)

data class WatchFaceMarketCandidate(
    val remoteId: String,
    val displayName: String,
)

internal fun canonicalWatchFaceName(value: String): String = value
    .trim()
    .replace(Regex("\\s+"), " ")
    .lowercase(Locale.ROOT)

data class WatchFaceCatalogDiagnostic(
    val message: String,
    val preservedCopyPath: String?,
)

data class WatchFaceCatalogSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val records: List<WatchFaceRecord> = emptyList(),
    val diagnostic: WatchFaceCatalogDiagnostic? = null,
) {
    fun findMarketInstall(storeId: String, remoteId: String): WatchFaceRecord? =
        records.firstOrNull { record ->
            val market = record.source as? WatchFaceInstallSource.Market
            market?.storeId == storeId && market.remoteId == remoteId
        }

    fun resolveAlias(id: String): WatchFaceRecord? = records.firstOrNull { record ->
        record.logicalId == id ||
            record.activeLauncherId == id ||
            id in record.legacyIds
    }

    /** Installed historical revisions that may be removed after a market update. */
    fun obsoleteMarketLauncherIds(installedLauncherIds: Set<String>): Set<String> {
        if (installedLauncherIds.isEmpty()) return emptySet()
        val installedByLowercase = installedLauncherIds.associateBy { it.lowercase() }
        return records.asSequence()
            .filter { it.source is WatchFaceInstallSource.Market }
            .flatMap { record ->
                val active = record.activeLauncherIdOrNull()?.lowercase()
                record.legacyIds.asSequence()
                    .filter { it.lowercase() != active }
                    .mapNotNull { installedByLowercase[it.lowercase()] }
            }
            .toSet()
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

data class WatchFaceCatalogUpsertResult(
    val snapshot: WatchFaceCatalogSnapshot,
    val record: WatchFaceRecord,
    val supersededLauncherIds: Set<String>,
)

const val WATCHMAKER_COMMUNITY_STORE_ID: String = "watchmaker-community-store"

object WatchFaceLogicalIds {
    fun market(storeId: String, remoteId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$storeId\n$remoteId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "market:${digest.take(32)}"
    }
}
