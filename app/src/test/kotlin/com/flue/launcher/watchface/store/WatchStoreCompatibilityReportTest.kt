package com.flue.launcher.watchface.store

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchStoreCompatibilityReportTest {
    @Test
    fun `inspection reports unknown tags layers and broken preview`() = withTempRoot { root ->
        val watchXml = root.resolve("watch.xml").toFile().apply {
            writeText(
                """
                <Watch name="Remote name">
                  <Layer type="future-layer" text="{future_tag}" />
                </Watch>
                """.trimIndent(),
            )
        }
        val preview = root.resolve("preview.jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val report = WatchStoreCompatibilityInspector.inspect(root.toFile(), preview)

        assertTrue(report.unsupportedLayerTypes.contains("future-layer"))
        assertTrue(report.unsupportedTags.contains("future_tag"))
        assertEquals(WatchStorePreviewIssue.UNSUPPORTED_ENCODING, report.previewIssue)
        assertFalse(report.isFullyCompatible)
        assertTrue(watchXml.isFile)
    }

    @Test
    fun `inspection accepts a valid preview and supported image layer`() = withTempRoot { root ->
        root.resolve("watch.xml").toFile().writeText("<Watch name=\"Remote name\"><Layer type=\"image\" /></Watch>")
        val preview = root.resolve("preview.jpg").toFile().apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))
        }

        val report = WatchStoreCompatibilityInspector.inspect(root.toFile(), preview)

        assertTrue(report.isFullyCompatible)
        assertEquals(null, report.previewIssue)
    }

    @Test
    fun `sandboxed global script is reported as supported metadata`() = withTempRoot { root ->
        root.resolve("watch.xml").toFile().writeText(
            "<Watch name=\"Scripted\"><Layer type=\"shape\" action=\"script:restart()\" /></Watch>",
        )
        root.resolve("scripts/script.txt").toFile().apply {
            parentFile.mkdirs()
            writeText("function restart() var_value = 1 end")
        }
        val preview = root.resolve("preview.jpg").toFile().apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))
        }

        val report = WatchStoreCompatibilityInspector.inspect(root.toFile(), preview)

        assertTrue(report.hasGlobalScript)
        assertTrue(report.unsupportedExpressions.isEmpty())
        assertTrue(report.isFullyCompatible)
        assertEquals(0, report.issueCount)
    }

    private inline fun withTempRoot(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("flue-watch-compat-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
