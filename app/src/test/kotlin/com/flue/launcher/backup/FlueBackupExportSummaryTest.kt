package com.flue.launcher.backup

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
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
}
