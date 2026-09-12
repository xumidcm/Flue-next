package com.flue.launcher.watchface.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WatchEditorAgentCandidateTest {
    @Test
    fun `agent candidate revision advances for edits and undo`() {
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val initial = session.agentCandidate()
                session.updateRootAttribute("name", "Agent face")
                val edited = session.agentCandidate()

                assertTrue(edited.revision > initial.revision)
                assertTrue(edited.watchXml.contains("Agent face"))
                assertEquals(edited.revision, session.agentRevision())

                assertTrue(session.undo())
                assertTrue(session.agentCandidate().revision > edited.revision)
                assertTrue(!session.agentCandidate().watchXml.contains("Agent face"))
            }
        }
    }

    @Test
    fun `canvas transform commit advances agent revision`() {
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val key = session.addLayer("image", mapOf("x" to "0", "y" to "0"))
                val before = session.agentRevision()
                session.beginLayerTransformGesture(key)
                session.updateLayerTransformPreview(key, WatchEditorTransform(x = 20f, y = 12f))
                assertTrue(session.commitLayerTransformGesture(key))
                assertTrue(session.agentRevision() > before)
            }
        }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("flue-agent-session").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
