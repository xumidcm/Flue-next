package com.flue.launcher.watchface.store.catalog

import com.flue.launcher.watchface.store.WatchStoreCompatibilityReport
import com.flue.launcher.watchface.store.WatchStorePreviewIssue
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchFaceCatalogTest {
    @Test
    fun `updated market record launches active revision instead of inherited alias`() {
        val oldRevision = "1".repeat(64)
        val activeRevision = "2".repeat(64)
        val oldId = "watchmaker:watch_${oldRevision.take(32)}"
        val activeId = "watchmaker:watch_${activeRevision.take(32)}"
        val record = marketRecord(
            logicalId = "market:updated",
            revision = activeRevision,
            installedAt = 2_000L,
            remoteId = "remote-updated",
        ).copy(
            activeLauncherId = activeId,
            legacyIds = linkedSetOf(oldId, activeId),
        )

        assertEquals(activeId, record.launcherIdOrNull())
        assertEquals(record, WatchFaceCatalogSnapshot(records = listOf(record)).resolveAlias(activeId))
    }

    @Test
    fun `catalog round trips every durable record field`() = withCatalog { file, catalog ->
        val record = marketRecord(
            logicalId = "market:community:wf-42",
            revision = "a".repeat(64),
            installedAt = 42_000L,
            remoteId = "wf-42",
        ).copy(
            installedDisplayName = "安装包名称",
            displayNameOverride = "我的表盘",
            legacyIds = setOf("watchmaker:watch_${"a".repeat(32)}", "old:42"),
            activeLauncherId = "watchmaker:watch_${"a".repeat(32)}",
            compatibilityReport = WatchStoreCompatibilityReport(
                unsupportedTags = setOf("{future_tag}"),
                unsupportedLayerTypes = setOf("future-layer"),
                unsupportedExpressions = setOf("future expression"),
                previewIssue = WatchStorePreviewIssue.EMPTY,
                inspectionError = "diagnostic",
            ),
        )

        catalog.upsert(record)

        val reloaded = FileWatchFaceCatalog(file).read()
        assertEquals(WatchFaceCatalogSnapshot.CURRENT_SCHEMA_VERSION, reloaded.schemaVersion)
        assertEquals(record, reloaded.records.single())
        assertNull(reloaded.diagnostic)
    }

    @Test
    fun `legacy watchmaker id migrates to logical identity without breaking alias`() =
        withCatalog { _, catalog ->
            val legacyId = "watchmaker:watch_${"1a".repeat(16)}"

            val migrated = catalog.migrateLegacyWatchmakerId(
                legacyId = legacyId,
                installedAtEpochMs = 9_000L,
            )

            assertTrue(migrated.logicalId != legacyId)
            assertEquals("1a".repeat(16), migrated.activeRevisionSha256)
            assertEquals(migrated, catalog.read().resolveAlias(legacyId))
            assertTrue(legacyId in migrated.legacyIds)
        }

    @Test
    fun `catalog sorts newest installation first and matches market by remote identity`() =
        withCatalog { _, catalog ->
            val older = marketRecord("market:older", "b".repeat(64), 100L, "same-name-1")
            val newer = marketRecord("market:newer", "c".repeat(64), 300L, "same-name-2")
            val middle = marketRecord("market:middle", "d".repeat(64), 200L, "same-name-3")
            catalog.upsert(older)
            catalog.upsert(newer)
            catalog.upsert(middle)

            val snapshot = catalog.read()
            assertEquals(listOf(newer.logicalId, middle.logicalId, older.logicalId), snapshot.records.map { it.logicalId })
            assertEquals(newer, snapshot.findMarketInstall(STORE_ID, "same-name-2"))
            assertNull(snapshot.findMarketInstall(STORE_ID, "missing"))
        }

    @Test
    fun `xml names never merge distinct market api identities`() = withCatalog { _, catalog ->
        val firstId = "watchmaker:watch_${"1".repeat(32)}"
        val secondId = "watchmaker:watch_${"2".repeat(32)}"
        catalog.upsert(
            marketRecord("market:api-a", "a".repeat(64), 100L, "api-a")
                .copy(installedDisplayName = "同一个 XML 名称", activeLauncherId = firstId, legacyIds = setOf(firstId)),
        )
        catalog.upsert(
            marketRecord("market:api-b", "b".repeat(64), 200L, "api-b")
                .copy(installedDisplayName = "同一个 XML 名称", activeLauncherId = secondId, legacyIds = setOf(secondId)),
        )

        val snapshot = catalog.read()
        assertEquals(2, snapshot.records.size)
        assertEquals("market:api-a", snapshot.findMarketInstall(STORE_ID, "api-a")?.logicalId)
        assertEquals("market:api-b", snapshot.findMarketInstall(STORE_ID, "api-b")?.logicalId)
    }

    @Test
    fun `updating one market remote replaces its active revision instead of duplicating it`() =
        withCatalog { _, catalog ->
            val oldLauncherId = "watchmaker:watch_old-directory"
            catalog.upsert(
                marketRecord("market:first", "e".repeat(64), 100L, "wf-7")
                    .copy(activeLauncherId = oldLauncherId, legacyIds = setOf(oldLauncherId)),
            )
            val newLauncherId = "watchmaker:watch_new-directory"
            val replacement = marketRecord("market:stable", "f".repeat(64), 500L, "wf-7")
                .copy(activeLauncherId = newLauncherId, legacyIds = setOf(newLauncherId))

            catalog.upsert(replacement)

            val snapshot = catalog.read()
            assertEquals(1, snapshot.records.size)
            val current = snapshot.findMarketInstall(STORE_ID, "wf-7")!!
            assertEquals(newLauncherId, current.activeLauncherId)
            assertEquals(newLauncherId, current.activeLauncherIdOrNull())
            assertTrue(oldLauncherId in current.legacyIds)
            assertTrue(newLauncherId in current.legacyIds)
        }

    @Test
    fun `market update preserves custom name and reports every superseded launcher directory`() =
        withCatalog { _, catalog ->
            val firstId = "watchmaker:watch_${"1".repeat(32)}"
            val secondId = "watchmaker:watch_${"2".repeat(32)}"
            val activeId = "watchmaker:watch_${"3".repeat(32)}"
            catalog.upsert(
                marketRecord("market:preserve-name", "2".repeat(64), 200L, "wf-preserve-name")
                    .copy(
                        displayNameOverride = "我的学生时光",
                        activeLauncherId = secondId,
                        legacyIds = linkedSetOf(firstId, secondId),
                    ),
            )

            val update = catalog.upsertDetailed(
                marketRecord("market:preserve-name", "3".repeat(64), 300L, "wf-preserve-name")
                    .copy(activeLauncherId = activeId, legacyIds = setOf(activeId)),
            )

            assertEquals("我的学生时光", update.record.displayNameOverride)
            assertEquals(setOf(firstId, secondId), update.supersededLauncherIds)
            assertEquals(activeId, update.record.activeLauncherId)
        }

    @Test
    fun `market update preserves the user display name override`() = withCatalog { _, catalog ->
        catalog.upsert(
            marketRecord("market:rename", "e1".repeat(32), 100L, "wf-rename")
                .copy(displayNameOverride = "我的学生时光"),
        )

        catalog.upsert(
            marketRecord("market:rename", "e2".repeat(32), 200L, "wf-rename"),
        )

        assertEquals(
            "我的学生时光",
            catalog.read().findMarketInstall(STORE_ID, "wf-rename")?.displayNameOverride,
        )
    }

    @Test
    fun `catalog reconciliation cannot move an installed face backwards in time`() =
        withCatalog { _, catalog ->
            catalog.upsert(marketRecord("market:time", "a".repeat(64), 9_000L, "wf-time"))

            catalog.upsert(marketRecord("market:time", "b".repeat(64), 1_000L, "wf-time"))

            assertEquals(
                9_000L,
                catalog.read().findMarketInstall(STORE_ID, "wf-time")?.installedAtEpochMs,
            )
        }

    @Test
    fun `removing market install restores not-installed lookup`() = withCatalog { _, catalog ->
        catalog.upsert(marketRecord("market:remove", "9".repeat(64), 700L, "wf-remove"))

        val afterRemoval = catalog.removeMarketInstall(STORE_ID, "wf-remove")

        assertNull(afterRemoval.findMarketInstall(STORE_ID, "wf-remove"))
        assertTrue(afterRemoval.records.isEmpty())
    }

    @Test
    fun `corrupt catalog degrades to empty snapshot and preserves diagnostic copy`() =
        withCatalog { file, catalog ->
            file.writeText("{not-json", Charsets.UTF_8)

            val snapshot = catalog.read()

            assertTrue(snapshot.records.isEmpty())
            assertNotNull(snapshot.diagnostic)
            assertTrue(file.parentFile.listFiles().orEmpty().any { it.name.startsWith("${file.name}.corrupt-") })
        }

    @Test
    fun `reconcile removes market record whose active descriptor was deleted outside store`() =
        withCatalog { _, catalog ->
            val revision = "7".repeat(64)
            val launcherId = "watchmaker:watch_${revision.take(32)}"
            catalog.upsert(
                marketRecord("market:stale", revision, 800L, "wf-stale")
                    .copy(installedDisplayName = "Stale", legacyIds = setOf(launcherId)),
            )

            val reconciled = catalog.reconcileInstalledDescriptors(emptyList())

            assertNull(reconciled.findMarketInstall(STORE_ID, "wf-stale"))
            assertTrue(reconciled.records.isEmpty())
        }

    @Test
    fun `incomplete installed scan preserves records that could not be parsed`() =
        withCatalog { _, catalog ->
            val revision = "71".repeat(32)
            val launcherId = "watchmaker:watch_${revision.take(32)}"
            catalog.upsert(
                marketRecord("market:temporarily-unreadable", revision, 800L, "wf-stale")
                    .copy(
                        installedDisplayName = "暂时无法解析",
                        activeLauncherId = launcherId,
                        legacyIds = setOf(launcherId),
                    ),
            )

            val reconciled = catalog.reconcileInstalledDescriptors(
                installed = emptyList(),
                scanComplete = false,
            )

            assertEquals(
                launcherId,
                reconciled.findMarketInstall(STORE_ID, "wf-stale")?.activeLauncherId,
            )
        }

    @Test
    fun `reconcile keeps installed record and refreshes discovered display name`() =
        withCatalog { _, catalog ->
            val revision = "8".repeat(64)
            val launcherId = "watchmaker:watch_${revision.take(32)}"
            catalog.upsert(
                marketRecord("market:present", revision, 900L, "wf-present")
                    .copy(legacyIds = setOf(launcherId)),
            )

            val reconciled = catalog.reconcileInstalledDescriptors(
                listOf(InstalledWatchFaceIdentity(launcherId, "学生时光", 850L)),
            )

            val record = reconciled.findMarketInstall(STORE_ID, "wf-present")!!
            assertEquals("学生时光", record.installedDisplayName)
            assertEquals("学生时光", record.resolvedDisplayName())
        }

    @Test
    fun `reconcile uses active launcher directory when archive revision differs`() = withCatalog { _, catalog ->
        val archiveRevision = "a".repeat(64)
        val launcherId = "watchmaker:watch_legacy-directory"
        catalog.upsert(
            marketRecord("market:directory-id", archiveRevision, 1_000L, "wf-directory")
                .copy(
                    activeLauncherId = launcherId,
                    legacyIds = setOf(launcherId),
                ),
        )

        val present = catalog.reconcileInstalledDescriptors(
            listOf(InstalledWatchFaceIdentity(launcherId, "目录表盘", 1_000L)),
        )
        val installed = present.findMarketInstall(STORE_ID, "wf-directory")!!
        assertEquals(launcherId, installed.activeLauncherId)
        assertEquals(launcherId, installed.activeLauncherIdOrNull())
        assertEquals(archiveRevision, installed.activeRevisionSha256)

        val afterDirectoryDeletion = catalog.reconcileInstalledDescriptors(emptyList())
        assertNull(afterDirectoryDeletion.findMarketInstall(STORE_ID, "wf-directory"))
    }

    @Test
    fun `schema one alias is promoted to active launcher id during reconciliation`() = withCatalog { file, catalog ->
        val launcherId = "watchmaker:watch_manual-directory"
        val revision = "b".repeat(64)
        writeSchemaOneMarketRecord(
            file = file,
            logicalId = "market:legacy-schema",
            revision = revision,
            remoteId = "wf-legacy-schema",
            aliases = listOf(launcherId),
        )

        val reconciled = catalog.reconcileInstalledDescriptors(
            listOf(InstalledWatchFaceIdentity(launcherId, "旧目录表盘", 1_100L)),
        )
        val record = reconciled.findMarketInstall(STORE_ID, "wf-legacy-schema")!!
        assertEquals(launcherId, record.activeLauncherId)
        assertEquals(launcherId, record.activeLauncherIdOrNull())
    }

    @Test
    fun `schema one multiple aliases promote only the unique installed directory`() = withCatalog { file, catalog ->
        val staleAlias = "watchmaker:watch_old-directory"
        val installedAlias = "watchmaker:watch_current-directory"
        writeSchemaOneMarketRecord(
            file = file,
            logicalId = "market:legacy-aliases",
            revision = "c".repeat(64),
            remoteId = "wf-legacy-aliases",
            aliases = listOf(staleAlias, installedAlias),
        )

        assertNull(catalog.read().records.single().activeLauncherIdOrNull())

        val reconciled = catalog.reconcileInstalledDescriptors(
            listOf(InstalledWatchFaceIdentity(installedAlias, "当前表盘", 1_200L)),
        )
        val record = reconciled.findMarketInstall(STORE_ID, "wf-legacy-aliases")!!
        assertEquals(installedAlias, record.activeLauncherId)
        assertTrue(staleAlias in record.legacyIds)
        assertTrue(installedAlias in record.legacyIds)
    }

    @Test
    fun `schema one multiple installed revisions keeps market identity by matching active revision hash`() =
        withCatalog { file, catalog ->
            val oldRevision = "d".repeat(64)
            val activeRevision = "e".repeat(64)
            val oldLauncherId = "watchmaker:watch_${oldRevision.take(32)}"
            val activeLauncherId = "watchmaker:watch_${activeRevision.take(32)}"
            writeSchemaOneMarketRecord(
                file = file,
                logicalId = "market:multi-revision",
                revision = activeRevision,
                remoteId = "wf-multi-revision",
                aliases = listOf(oldLauncherId, activeLauncherId),
            )

            val reconciled = catalog.reconcileInstalledDescriptors(
                listOf(
                    InstalledWatchFaceIdentity(oldLauncherId, "旧版本", 1_000L),
                    InstalledWatchFaceIdentity(activeLauncherId, "当前版本", 2_000L),
                ),
            )

            val record = reconciled.findMarketInstall(STORE_ID, "wf-multi-revision")
            assertNotNull(record)
            assertEquals(activeLauncherId, record!!.activeLauncherId)
            assertTrue(oldLauncherId in record.legacyIds)
            assertTrue(activeLauncherId in record.legacyIds)
            assertEquals(1, reconciled.records.size)
        }

    @Test
    fun `obsolete market launcher ids exclude active revision but include installed historical aliases`() =
        withCatalog { _, catalog ->
            val oldId = "watchmaker:watch_${"4".repeat(32)}"
            val activeId = "watchmaker:watch_${"5".repeat(32)}"
            catalog.upsert(
                marketRecord("market:cleanup", "5".repeat(64), 500L, "wf-cleanup")
                    .copy(activeLauncherId = activeId, legacyIds = setOf(oldId, activeId)),
            )

            val obsolete = catalog.read().obsoleteMarketLauncherIds(setOf(oldId, activeId, "unrelated"))

            assertEquals(setOf(oldId), obsolete)
        }

    @Test
    fun `schema one multiple installed aliases prefer the revision-derived directory`() =
        withCatalog { file, catalog ->
            val revision = "cd".repeat(32)
            val revisionAlias = "watchmaker:watch_${revision.take(32)}"
            val newerHistoricalAlias = "watchmaker:watch_historical-directory"
            writeSchemaOneMarketRecord(
                file = file,
                logicalId = "market:revision-alias",
                revision = revision,
                remoteId = "wf-revision-alias",
                aliases = listOf(newerHistoricalAlias, revisionAlias),
            )

            val reconciled = catalog.reconcileInstalledDescriptors(
                listOf(
                    InstalledWatchFaceIdentity(revisionAlias, "当前版本", 1_000L),
                    InstalledWatchFaceIdentity(newerHistoricalAlias, "旧版本", 9_000L),
                ),
            )

            assertEquals(
                revisionAlias,
                reconciled.findMarketInstall(STORE_ID, "wf-revision-alias")?.activeLauncherId,
            )
        }

    @Test
    fun `unique exact legacy name can migrate to market identity`() = withCatalog { _, catalog ->
        val launcherId = "watchmaker:watch_${"a1".repeat(16)}"
        catalog.reconcileInstalledDescriptors(
            listOf(InstalledWatchFaceIdentity(launcherId, "学生时光", 1_000L)),
        )

        val migrated = catalog.associateUniqueMarketNames(
            installed = listOf(InstalledWatchFaceIdentity(launcherId, "学生时光", 1_000L)),
            candidates = listOf(WatchFaceMarketCandidate("remote-student", "学生时光")),
            storeId = STORE_ID,
        )

        val record = migrated.findMarketInstall(STORE_ID, "remote-student")!!
        assertEquals(launcherId, record.activeLauncherIdOrNull())
        assertTrue(launcherId in record.legacyIds)
    }

    @Test
    fun `legacy association refuses canonical duplicate local or remote names`() = withCatalog { _, catalog ->
        val first = InstalledWatchFaceIdentity("watchmaker:watch_${"b1".repeat(16)}", "Same Name", 1_000L)
        val second = InstalledWatchFaceIdentity("watchmaker:watch_${"b2".repeat(16)}", " same   name ", 2_000L)
        catalog.reconcileInstalledDescriptors(listOf(first, second))

        val duplicateLocal = catalog.associateUniqueMarketNames(
            installed = listOf(first, second),
            candidates = listOf(WatchFaceMarketCandidate("remote-one", "SAME NAME")),
            storeId = STORE_ID,
        )
        assertNull(duplicateLocal.findMarketInstall(STORE_ID, "remote-one"))

        val uniqueLocal = InstalledWatchFaceIdentity("watchmaker:watch_${"c1".repeat(16)}", "Unique", 3_000L)
        catalog.reconcileInstalledDescriptors(listOf(uniqueLocal))
        val duplicateRemote = catalog.associateUniqueMarketNames(
            installed = listOf(uniqueLocal),
            candidates = listOf(
                WatchFaceMarketCandidate("remote-two", "Unique"),
                WatchFaceMarketCandidate("remote-three", "Unique"),
            ),
            storeId = STORE_ID,
        )
        assertNull(duplicateRemote.findMarketInstall(STORE_ID, "remote-two"))
        assertNull(duplicateRemote.findMarketInstall(STORE_ID, "remote-three"))
    }

    @Test
    fun `display name override wins over installed and market names`() {
        val record = marketRecord("market:name", "d1".repeat(32), 4_000L, "remote-name")
            .copy(installedDisplayName = "Installed", displayNameOverride = "Renamed")

        assertEquals("Renamed", record.resolvedDisplayName("Market"))
    }

    @Test
    fun `reconcile repairs corrupt active catalog but returns diagnostic for recovery UI`() =
        withCatalog { file, catalog ->
            file.writeText("{bad", Charsets.UTF_8)

            val detected = catalog.reconcileInstalledDescriptors(emptyList())

            assertNotNull(detected.diagnostic)
            assertNotNull(catalog.read().diagnostic)

            val recovered = catalog.reconcileInstalledDescriptors(
                installed = emptyList(),
                repairCorruptCatalog = true,
            )

            assertNotNull(recovered.diagnostic)
            assertTrue(recovered.records.isEmpty())
            assertNull(catalog.read().diagnostic)
        }

    private fun marketRecord(
        logicalId: String,
        revision: String,
        installedAt: Long,
        remoteId: String,
    ): WatchFaceRecord = WatchFaceRecord(
        logicalId = logicalId,
        activeRevisionSha256 = revision,
        installedAtEpochMs = installedAt,
        displayNameOverride = null,
        source = WatchFaceInstallSource.Market(
            storeId = STORE_ID,
            remoteId = remoteId,
        ),
        legacyIds = emptySet(),
    )

    private fun writeSchemaOneMarketRecord(
        file: java.io.File,
        logicalId: String,
        revision: String,
        remoteId: String,
        aliases: List<String>,
    ) {
        val legacyIds = JSONArray().apply { aliases.forEach { put(it) } }
        file.writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put(
                    "records",
                    JSONArray().put(
                        JSONObject()
                            .put("logicalId", logicalId)
                            .put("activeRevisionSha256", revision)
                            .put("installedAtEpochMs", 1_100L)
                            .put("displayNameOverride", JSONObject.NULL)
                            .put(
                                "source",
                                JSONObject()
                                    .put("kind", WatchFaceInstallSource.Market.KIND)
                                    .put("storeId", STORE_ID)
                                    .put("remoteId", remoteId),
                            )
                            .put("legacyIds", legacyIds),
                    ),
                )
                .toString(),
            Charsets.UTF_8,
        )
    }

    private inline fun withCatalog(block: (java.io.File, FileWatchFaceCatalog) -> Unit) {
        val root = Files.createTempDirectory("flue-watch-catalog-").toFile()
        try {
            val file = root.resolve("watch-face-catalog.json")
            block(file, FileWatchFaceCatalog(file))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val STORE_ID = "watchmaker-community-store"
    }
}
