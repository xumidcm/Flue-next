package com.flue.launcher.watchface.store.catalog

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class FileWatchFaceInstallJournalTest {
    @Test
    fun `pending market install survives restart until catalog commit removes it`() = withJournal { file, journal ->
        val revision = "a".repeat(64)
        val launcherId = "watchmaker:watch_${revision.take(32)}"
        journal.begin(STORE_ID, "wf-1", revision, launcherId)
        journal.markInstalled(STORE_ID, "wf-1", launcherId, "学生时光", 2_000L)

        val restored = FileWatchFaceInstallJournal(file).read().single()

        assertEquals("wf-1", restored.remoteId)
        assertEquals(revision, restored.revisionSha256)
        assertEquals(launcherId, restored.launcherId)
        assertEquals("学生时光", restored.displayName)
        assertEquals(2_000L, restored.installedAtEpochMs)
        assertTrue(FileWatchFaceInstallJournal(file).remove(STORE_ID, "wf-1").isEmpty())
        assertTrue(FileWatchFaceInstallJournal(file).read().isEmpty())
    }

    @Test
    fun `cleanup retains an old transaction when its package directory exists`() {
        val root = Files.createTempDirectory("flue-watch-journal-").toFile()
        try {
            val file = root.resolve("install-journal.json")
            val journal = FileWatchFaceInstallJournal(file, clock = { 10_000L })
            val revision = "b".repeat(64)
            val launcherId = "watchmaker:watch_${revision.take(32)}"
            journal.begin(STORE_ID, "wf-installed", revision, launcherId)

            val remaining = FileWatchFaceInstallJournal(file, clock = { 100_000L })
                .removeExpiredMissing(setOf(launcherId), maxAgeMs = 1L)

            assertEquals(listOf("wf-installed"), remaining.map { it.remoteId })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cleanup drops an expired transaction with no installed package`() {
        val root = Files.createTempDirectory("flue-watch-journal-").toFile()
        try {
            val file = root.resolve("install-journal.json")
            val revision = "c".repeat(64)
            FileWatchFaceInstallJournal(file, clock = { 1_000L })
                .begin(STORE_ID, "wf-missing", revision, "watchmaker:watch_${revision.take(32)}")

            val remaining = FileWatchFaceInstallJournal(file, clock = { 100_000L })
                .removeExpiredMissing(emptySet(), maxAgeMs = 1L)

            assertTrue(remaining.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed unpublished install is discarded only after a complete missing-package scan`() =
        withJournal { _, journal ->
            val revision = "d".repeat(64)
            val launcherId = "watchmaker:watch_${revision.take(32)}"
            journal.begin(STORE_ID, "wf-failed", revision, launcherId)

            val incomplete = journal.discardAbandoned(
                storeId = STORE_ID,
                remoteId = "wf-failed",
                installedLauncherIds = emptySet(),
                scanComplete = false,
            )
            assertEquals(listOf("wf-failed"), incomplete.map { it.remoteId })

            val complete = journal.discardAbandoned(
                storeId = STORE_ID,
                remoteId = "wf-failed",
                installedLauncherIds = emptySet(),
                scanComplete = true,
            )
            assertTrue(complete.isEmpty())
        }

    @Test
    fun `published package journal remains recoverable when install coroutine is cancelled`() =
        withJournal { _, journal ->
            val revision = "e".repeat(64)
            val launcherId = "watchmaker:watch_${revision.take(32)}"
            journal.begin(STORE_ID, "wf-published", revision, launcherId)
            journal.markInstalled(STORE_ID, "wf-published", launcherId, "已发布", 2_000L)

            val remaining = journal.discardAbandoned(
                storeId = STORE_ID,
                remoteId = "wf-published",
                installedLauncherIds = emptySet(),
                scanComplete = true,
            )

            assertEquals(listOf("wf-published"), remaining.map { it.remoteId })
        }

    private inline fun withJournal(
        block: (java.io.File, FileWatchFaceInstallJournal) -> Unit,
    ) {
        val root = Files.createTempDirectory("flue-watch-journal-").toFile()
        try {
            val file = root.resolve("install-journal.json")
            block(file, FileWatchFaceInstallJournal(file, clock = { 1_000L }))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val STORE_ID = "watchmaker-community-store"
    }
}
