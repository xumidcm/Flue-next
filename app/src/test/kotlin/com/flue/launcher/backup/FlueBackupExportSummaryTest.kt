package com.flue.launcher.backup

import com.flue.launcher.watchface.standard.WatchArchiveInstaller
import com.flue.launcher.watchface.standard.WatchArchiveValidator
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「导出完成」页那三行灰字的两块料：分区名拼装的顺序、以及包体字节数的累计。
 * 两者都刻意做成纯 JVM 可测，UI 侧只负责 key → strings 的映射。
 */
class FlueBackupExportSummaryTest {
    @Test
    fun `section keys follow declaration order and skip unselected ones`() {
        assertEquals(
            listOf(
                FlueBackupOptions.SECTION_SETTINGS,
                FlueBackupOptions.SECTION_APP_ORDER,
                FlueBackupOptions.SECTION_WIDGET_ORDER,
                FlueBackupOptions.SECTION_SIDE_SCREEN_ORDER,
                FlueBackupOptions.SECTION_PHOTO_WATCH_FACE,
                FlueBackupOptions.SECTION_VIDEO_WATCH_FACE,
                FlueBackupOptions.SECTION_WATCH_FILES,
            ),
            FlueBackupOptions().selectedSectionKeys(),
        )

        val partial = FlueBackupOptions(
            settings = false,
            appOrder = true,
            widgetOrder = false,
            sideScreenOrder = false,
            photoWatchFace = false,
            videoWatchFace = true,
            watchFiles = false,
        )
        assertEquals(
            listOf(FlueBackupOptions.SECTION_APP_ORDER, FlueBackupOptions.SECTION_VIDEO_WATCH_FACE),
            partial.selectedSectionKeys(),
        )
    }

    @Test
    fun `nothing selected yields no section keys`() {
        val none = FlueBackupOptions(
            settings = false,
            appOrder = false,
            widgetOrder = false,
            sideScreenOrder = false,
            photoWatchFace = false,
            videoWatchFace = false,
            watchFiles = false,
        )
        assertTrue(none.selectedSectionKeys().isEmpty())
        assertTrue(!none.hasAny)
    }

    @Test
    fun `counting stream tallies every write shape and passes bytes through`() {
        val sink = ByteArrayOutputStream()
        val counting = CountingOutputStream(sink)

        counting.write(0x41)
        counting.write(byteArrayOf(0x42, 0x43, 0x44))
        counting.write(byteArrayOf(0x00, 0x45, 0x46, 0x00), 1, 2)
        counting.flush()

        assertEquals(6L, counting.count)
        assertEquals("ABCDEF", sink.toString("US-ASCII"))
    }

    @Test
    fun `counting stream starts at zero and survives close`() {
        val counting = CountingOutputStream(ByteArrayOutputStream())
        assertEquals(0L, counting.count)
        counting.write(byteArrayOf(1, 2, 3, 4, 5))
        counting.close()
        // exportBackup 在 use 之后才读 count（ZipOutputStream 关闭时才写完中央目录）。
        assertEquals(5L, counting.count)
    }

    @Test
    fun `watch backup archive excludes installer metadata and remains importable`() {
        val root = Files.createTempDirectory("flue-watch-backup-test").toFile()
        val face = File(root, "face").apply { mkdirs() }
        val archive = File(root, "restored.watch")
        try {
            File(face, "watch.xml").writeText(
                "<Watch name=\"Backup\" shape=\"R\"><Layer type=\"text\" text=\"12:34\" /></Watch>",
                Charsets.UTF_8,
            )
            File(face, ".flue").mkdirs()
            File(face, ".flue/installed-at").writeText("123", Charsets.US_ASCII)

            archive.outputStream().use { output ->
                writeBackupWatchArchive(face, output)
            }

            val manifest = WatchArchiveValidator().inspect(archive)
            assertTrue(manifest.entries.any { it.normalizedName == "watch.xml" })
            assertFalse(manifest.entries.any { it.normalizedName.startsWith(".flue/") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy backup watch archive is sanitized before restore`() {
        val root = Files.createTempDirectory("flue-watch-backup-legacy-test").toFile()
        val legacy = File(root, "legacy.watch")
        val sanitized = File(root, "sanitized.watch")
        try {
            ZipOutputStream(legacy.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("watch.xml"))
                zip.write(
                    "<Watch name=\"Legacy\" shape=\"R\"><Layer type=\"text\" text=\"12:34\" /></Watch>"
                        .toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(".flue/installed-at"))
                zip.write("123".toByteArray(Charsets.US_ASCII))
                zip.closeEntry()
            }

            sanitizeBackupWatchArchive(legacy, sanitized)

            val manifest = WatchArchiveValidator().inspect(sanitized)
            assertTrue(manifest.entries.any { it.normalizedName == "watch.xml" })
            assertFalse(manifest.entries.any { it.normalizedName.startsWith(".flue/") })
            val installed = WatchArchiveInstaller().install(sanitized, File(root, "installed"))
            assertTrue(installed.watchXml.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
