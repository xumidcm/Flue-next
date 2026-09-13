package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.tools.WatchAgentToolCall
import com.flue.launcher.watchface.agent.tools.WatchAgentValidateFaceRequest
import com.flue.launcher.watchface.agent.tools.InMemoryWatchAgentToolHost
import com.flue.launcher.watchface.agent.tools.WatchEditorSessionAgentToolHost
import com.flue.launcher.watchface.editor.WatchEditorSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

class WatchEditorSessionAgentToolHostTest {
    @Test
    fun `validation reports malformed global script to the agent`() {
        val host = InMemoryWatchAgentToolHost(
            initialWatchXml = "<Watch/>",
            initialGlobalScript = "function broken(\n",
        )

        val result = host.validateFace(WatchAgentValidateFaceRequest())

        assertTrue(result.diagnostics.any { it.code == "invalid_global_script" })
    }

    @Test
    fun `adapter reads and writes session documents with stale revision protection`() = runBlocking {
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val host = WatchEditorSessionAgentToolHost(session)
                val runner = com.flue.launcher.watchface.agent.tools.WatchAgentToolRunner(host)
                val initial = runner.execute(WatchAgentToolCall("inspect", "inspect_face"))
                val revision = initial.revision

                val write = runner.execute(
                    WatchAgentToolCall(
                        "write",
                        "write_document",
                        mapOf(
                            "path" to "watch.xml",
                            "content" to "<Watch name=\"CloX\"/> ",
                            "expected_revision" to revision,
                        ),
                    ),
                )
                assertTrue(write.succeeded)
                assertEquals("CloX", host.inspectFace(com.flue.launcher.watchface.agent.tools.WatchAgentInspectFaceRequest()).name)

                val stale = runner.execute(
                    WatchAgentToolCall(
                        "stale",
                        "write_document",
                        mapOf("path" to "watch.xml", "content" to "<Watch/>", "expected_revision" to revision),
                    ),
                )
                assertEquals("stale_revision", stale.error?.code)
            }
        }
    }

    @Test
    fun `adapter keeps successful live writes when a later request fails`() = runBlocking {
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val host = WatchEditorSessionAgentToolHost(session)
                val runner = com.flue.launcher.watchface.agent.tools.WatchAgentToolRunner(host)
                val revision = session.agentRevision()
                val results = runner.execute(
                    listOf(
                        WatchAgentToolCall(
                            "one",
                            "write_document",
                            mapOf("path" to "watch.xml", "content" to "<Watch name=\"Transient\"/>", "expected_revision" to revision),
                        ),
                        WatchAgentToolCall(
                            "two",
                            "write_document",
                            mapOf("path" to "watch.xml", "content" to "<Watch name=\"Stale\"/>", "expected_revision" to revision),
                        ),
                    ),
                )
                assertEquals("stale_revision", results[1].error?.code)
                assertTrue(session.agentCandidate().watchXml.contains("Transient"))

                val unavailable = runner.execute(WatchAgentToolCall("preview", "preview_face"))
                assertEquals("preview_unavailable", unavailable.error?.code)
            }
        }
    }

    @Test
    fun `adapter delegates preview to the host renderer`() = runBlocking {
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val host = WatchEditorSessionAgentToolHost(
                    session,
                    nativePreview = WatchEditorSessionAgentToolHost.NativePreviewRenderer { _, request, revision ->
                        com.flue.launcher.watchface.agent.tools.WatchAgentPreviewResult(
                            revision = revision.revision,
                            target = request.target,
                            renderer = "flue-native-test",
                            imageBytes = byteArrayOf(1, 2, 3),
                        )
                    },
                )
                val result = com.flue.launcher.watchface.agent.tools.WatchAgentToolRunner(host)
                    .execute(WatchAgentToolCall("preview", "preview_face"))
                val preview = result.content as com.flue.launcher.watchface.agent.tools.WatchAgentPreviewResult
                assertEquals("flue-native-test", preview.renderer)
                assertTrue(preview.imageBytes!!.contentEquals(byteArrayOf(1, 2, 3)))
            }
        }
    }

    private suspend fun withTemporaryDirectory(block: suspend (File) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("flue-agent-host").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
