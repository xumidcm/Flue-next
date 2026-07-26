package com.flue.launcher.watchface.editor

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorLiveAttributeApplicatorTest {
    @Test
    fun flushCoalescesSubmittedEditsIntoOneUndoEntry() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val key = session.addLayer("text", mapOf("text" to "Flue"))
            val applicator = WatchEditorLiveAttributeApplicator(session, debounceMillis = 50L)
            var applied: WatchEditorSnapshot? = null
            runBlocking {
                applicator.submit(
                    scope = this,
                    key = key,
                    changes = mapOf("x" to "12"),
                    onApplied = { applied = it },
                    onError = { throw it },
                )
                applicator.submit(
                    scope = this,
                    key = key,
                    changes = mapOf("y" to "34"),
                    onApplied = { applied = it },
                    onError = { throw it },
                )
                applicator.flush(
                    onApplied = { applied = it },
                    onError = { throw it },
                )
            }
            val snapshot = requireNotNull(applied)
            val attributes = snapshot.layers.single().attributes
            assertEquals("12", attributes["x"])
            assertEquals("34", attributes["y"])
            assertFalse(applicator.hasPendingEdits)

            // Both edits were applied as ONE batch: a single undo removes both.
            assertTrue(session.undo())
            val reverted = session.snapshot().layers.single().attributes
            assertNull(reverted["x"])
            assertNull(reverted["y"])
            // The next undo removes the addLayer itself.
            assertTrue(session.undo())
            assertTrue(session.snapshot().layers.isEmpty())
            assertFalse(session.snapshot().canUndo)
        }
    }

    @Test
    fun submitToADifferentLayerDropsTheStaleBatch() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val first = session.addLayer("text", mapOf("text" to "a"))
            val second = session.addLayer("shape")
            val applicator = WatchEditorLiveAttributeApplicator(session, debounceMillis = 50L)
            var applied: WatchEditorSnapshot? = null
            runBlocking {
                applicator.submit(
                    scope = this,
                    key = first,
                    changes = mapOf("x" to "1"),
                    onApplied = { applied = it },
                    onError = { throw it },
                )
                applicator.submit(
                    scope = this,
                    key = second,
                    changes = mapOf("x" to "2"),
                    onApplied = { applied = it },
                    onError = { throw it },
                )
                applicator.flush(
                    onApplied = { applied = it },
                    onError = { throw it },
                )
            }
            val layers = requireNotNull(applied).layers
            assertNull(layers.first { it.key == first }.attributes["x"])
            assertEquals("2", layers.first { it.key == second }.attributes["x"])
        }
    }

    @Test
    fun consumePendingChangesReturnsBatchWithoutApplying() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val key = session.addLayer("text", mapOf("text" to "a"))
            val applicator = WatchEditorLiveAttributeApplicator(session, debounceMillis = 50L)
            var applied: WatchEditorSnapshot? = null
            runBlocking {
                applicator.submit(
                    scope = this,
                    key = key,
                    changes = mapOf("x" to "9"),
                    onApplied = { applied = it },
                    onError = { throw it },
                )
                val batch = applicator.consumePendingChanges()
                assertEquals(key, batch?.first)
                assertEquals(mapOf<String, String?>("x" to "9"), batch?.second)
                assertFalse(applicator.hasPendingEdits)
                // Nothing left to flush, so nothing is applied.
                applicator.flush(
                    onApplied = { applied = it },
                    onError = { throw it },
                )
            }
            assertNull(applied)
            assertNull(session.snapshot().layers.single().attributes["x"])
        }
    }

    @Test
    fun cancelPendingDropsScheduledWork() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val key = session.addLayer("text", mapOf("text" to "a"))
            val applicator = WatchEditorLiveAttributeApplicator(session, debounceMillis = 50L)
            var applied: WatchEditorSnapshot? = null
            runBlocking {
                applicator.submit(
                    scope = this,
                    key = key,
                    changes = mapOf("x" to "7"),
                    onApplied = { applied = it },
                    onError = { throw it },
                )
                applicator.cancelPending()
            }
            assertNull(applied)
            assertFalse(applicator.hasPendingEdits)
            assertNull(session.snapshot().layers.single().attributes["x"])
        }
    }

    @Test
    fun flushWithoutPendingEditsIsANoop() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val applicator = WatchEditorLiveAttributeApplicator(session, debounceMillis = 50L)
            var applied: WatchEditorSnapshot? = null
            runBlocking {
                applicator.flush(
                    onApplied = { applied = it },
                    onError = { throw it },
                )
            }
            assertNull(applied)
        }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("watch-editor-live-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
