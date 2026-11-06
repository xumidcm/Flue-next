package com.flue.launcher.watchface.standard

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WatchmakerWatchFaceStorageTest {
    @Test
    fun `cached scan updates edited metadata and preview without changing identity or order`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = WatchmakerWatchFaceStorage.rootDirectory(context)
            .resolve("watch_cached_${UUID.randomUUID()}")
        try {
            val nested = directory.resolve("nested").apply { assertTrue(mkdirs()) }
            val xml = nested.resolve("watch.xml")
            xml.writeText("<Watch name=\"Before\"><Layer type=\"text\" /></Watch>")
            WatchInstallMetadata.readOrCreate(directory, 1234L)
            fun scan() = WatchmakerWatchFaceStorage.scanWithDiagnostics(context)
                .descriptors.single { it.sourceDirPath == directory.absolutePath }
            val first = scan()
            assertEquals(first, scan())
            val modified = xml.lastModified()
            xml.writeText("<Watch name=\"After!\"><Layer type=\"text\" /></Watch>")
            assertTrue(xml.setLastModified(modified))
            val preview = nested.resolve("preview.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            val updated = scan()

            assertEquals("After!", updated.displayName)
            assertEquals(first.id, updated.id)
            assertEquals(preview.absolutePath, updated.previewFilePath)
            assertEquals(1234L, WatchInstallMetadata.read(directory))
            xml.writeText("<Watch><Layer")
            val broken = WatchmakerWatchFaceStorage.scanWithDiagnostics(context)
            assertTrue(broken.failures.any { it.directoryName == directory.name })
            assertTrue(broken.descriptors.none { it.id == first.id })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `standard watch descriptors expose the editor settings entry`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = WatchmakerWatchFaceStorage.rootDirectory(context)
            .resolve("watch_settings_${UUID.randomUUID().toString().replace("-", "")}")
        try {
            assertTrue(directory.mkdirs())
            directory.resolve("watch.xml").writeText(
                "<Watch name=\"Editable\" shape=\"S\"><Layer type=\"text\" text=\"Flue\" /></Watch>",
                Charsets.UTF_8,
            )

            val descriptor = WatchmakerWatchFaceStorage.scanWithDiagnostics(context)
                .descriptors
                .single { it.sourceDirPath == directory.absolutePath }

            assertTrue(descriptor.supportsSettings)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `scan reports an unreadable install instead of silently treating it as deleted`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = WatchmakerWatchFaceStorage.rootDirectory(context)
            .resolve("watch_broken_${UUID.randomUUID().toString().replace("-", "")}")
        try {
            assertTrue(directory.mkdirs())
            directory.resolve("watch.xml").writeText("<Watch><Layer", Charsets.UTF_8)

            val result = WatchmakerWatchFaceStorage.scanWithDiagnostics(context)

            assertTrue(result.failures.any { it.directoryName == directory.name })
            assertTrue(!result.isComplete)
        } finally {
            directory.deleteRecursively()
        }
    }
}
