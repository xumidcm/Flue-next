package com.flue.launcher.watchface.standard

import java.util.UUID
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
