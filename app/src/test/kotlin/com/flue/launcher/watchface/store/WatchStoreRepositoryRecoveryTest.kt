package com.flue.launcher.watchface.store

import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceType
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceScanFailure
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceScanResult
import com.flue.launcher.watchface.store.catalog.FileWatchFaceCatalog
import com.flue.launcher.watchface.store.catalog.FileWatchFaceInstallJournal
import com.flue.launcher.watchface.store.catalog.WATCHMAKER_COMMUNITY_STORE_ID
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchStoreRepositoryRecoveryTest {
    private val firstRemoteId = "00000000-0000-0000-0000-000000000001"
    private val updateRemoteId = "00000000-0000-0000-0000-000000000002"
    @Test
    fun `restart replays installed package journal before legacy reconciliation`() = runBlocking {
        val root = Files.createTempDirectory("flue-watch-store-recovery-").toFile()
        try {
            val revision = "d".repeat(64)
            val launcherId = "watchmaker:watch_${revision.take(32)}"
            val catalogFile = root.resolve("watch-face-catalog.json")
            val journalFile = root.resolve("watch-face-install-journal.json")
            val descriptor = WatchFaceDescriptor(
                id = launcherId,
                type = WatchFaceType.WATCHMAKER,
                displayName = "学生时光",
            )
            val first = repository(root, catalogFile, journalFile, listOf(descriptor))
            first.beginMarketInstallTransaction(firstRemoteId, revision)
            first.markMarketInstallPackagePublished(
                remoteId = firstRemoteId,
                installedDescriptorId = launcherId,
                installedDisplayName = descriptor.displayName,
                installedAtEpochMs = 7_000L,
            )

            val restarted = repository(root, catalogFile, journalFile, listOf(descriptor))
            val restored = restarted.restoreCatalogFromInstalledFaces()
            val record = restored.snapshot.findMarketInstall(
                WATCHMAKER_COMMUNITY_STORE_ID,
                firstRemoteId,
            )

            assertEquals(launcherId, record?.activeLauncherId)
            assertEquals("学生时光", record?.installedDisplayName)
            assertEquals(7_000L, record?.installedAtEpochMs)
            assertTrue(restored.snapshot.records.none { it.logicalId.startsWith("legacy:") })
            assertTrue(FileWatchFaceInstallJournal(journalFile).read().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `replayed update preserves custom name and marks old launcher revision obsolete`() = runBlocking {
        val root = Files.createTempDirectory("flue-watch-store-update-").toFile()
        try {
            val oldRevision = "e".repeat(64)
            val newRevision = "f".repeat(64)
            val oldId = "watchmaker:watch_${oldRevision.take(32)}"
            val newId = "watchmaker:watch_${newRevision.take(32)}"
            val catalogFile = root.resolve("watch-face-catalog.json")
            val journalFile = root.resolve("watch-face-install-journal.json")
            val catalog = FileWatchFaceCatalog(catalogFile)
            val first = repository(
                root,
                catalogFile,
                journalFile,
                listOf(descriptor(oldId, "旧版本")),
            )
            first.recordMarketInstallationDetailed(
                remoteId = updateRemoteId,
                activeRevisionSha256 = oldRevision,
                installedDescriptorId = oldId,
                installedDisplayName = "旧版本",
                installedAtEpochMs = 1_000L,
            )
            catalog.rename(
                catalog.read().findMarketInstall(WATCHMAKER_COMMUNITY_STORE_ID, updateRemoteId)!!.logicalId,
                "我的表盘",
            )

            val update = repository(
                root,
                catalogFile,
                journalFile,
                listOf(descriptor(oldId, "旧版本"), descriptor(newId, "新版本")),
            )
            update.beginMarketInstallTransaction(updateRemoteId, newRevision)
            update.markMarketInstallPackagePublished(updateRemoteId, newId, "新版本", 2_000L)

            val restored = update.restoreCatalogFromInstalledFaces()
            val record = restored.snapshot.findMarketInstall(
                WATCHMAKER_COMMUNITY_STORE_ID,
                updateRemoteId,
            )!!

            assertEquals(newId, record.activeLauncherId)
            assertEquals("我的表盘", record.displayNameOverride)
            assertEquals(setOf(oldId), restored.snapshot.obsoleteMarketLauncherIds(setOf(oldId, newId)))
            assertTrue(restored.snapshot.records.none { it.logicalId.startsWith("legacy:") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `partial disk scan reports the failure without erasing installed market state`() = runBlocking {
        val root = Files.createTempDirectory("flue-watch-store-partial-scan-").toFile()
        try {
            val revision = "a1".repeat(32)
            val launcherId = "watchmaker:watch_${revision.take(32)}"
            val catalogFile = root.resolve("watch-face-catalog.json")
            val journalFile = root.resolve("watch-face-install-journal.json")
            repository(
                root,
                catalogFile,
                journalFile,
                listOf(descriptor(launcherId, "可用版本")),
            ).recordMarketInstallationDetailed(
                remoteId = firstRemoteId,
                activeRevisionSha256 = revision,
                installedDescriptorId = launcherId,
                installedDisplayName = "可用版本",
                installedAtEpochMs = 1_000L,
            )
            val failing = WatchStoreRepository(
                api = NoOpWatchStoreApi,
                cacheRoot = root.resolve("cache"),
                catalog = FileWatchFaceCatalog(catalogFile),
                installJournal = FileWatchFaceInstallJournal(journalFile),
                installedWatchFacesProvider = { emptyList() },
                ioDispatcher = Dispatchers.Unconfined,
                installedWatchFaceScanProvider = {
                    WatchmakerWatchFaceScanResult(
                        descriptors = emptyList(),
                        failures = listOf(
                            WatchmakerWatchFaceScanFailure("watch_${revision.take(32)}", "watch.xml 损坏"),
                        ),
                    )
                },
            )

            val restored = failing.restoreCatalogFromInstalledFaces()

            assertEquals(false, restored.scanComplete)
            assertEquals(1, restored.scanFailures.size)
            assertEquals(
                launcherId,
                restored.snapshot.findMarketInstall(
                    WATCHMAKER_COMMUNITY_STORE_ID,
                    firstRemoteId,
                )?.activeLauncherId,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun repository(
        root: File,
        catalogFile: File,
        journalFile: File,
        installed: List<WatchFaceDescriptor>,
    ) = WatchStoreRepository(
        api = NoOpWatchStoreApi,
        cacheRoot = root.resolve("cache"),
        catalog = FileWatchFaceCatalog(catalogFile),
        installJournal = FileWatchFaceInstallJournal(journalFile),
        installedWatchFacesProvider = { installed },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun descriptor(id: String, name: String) = WatchFaceDescriptor(
        id = id,
        type = WatchFaceType.WATCHMAKER,
        displayName = name,
    )

    private object NoOpWatchStoreApi : WatchStoreApi {
        override fun getCategories(): List<WatchStoreCategory> = error("not used")
        override fun listWatchFaces(query: WatchStoreQuery): WatchStorePage = error("not used")
        override fun getWatchFace(id: String): WatchStoreWatchFaceDetail = error("not used")
        override fun downloadPreview(id: String, destination: File): File = error("not used")
        override fun downloadWatchFace(id: String, destination: File): File = error("not used")
    }
}
