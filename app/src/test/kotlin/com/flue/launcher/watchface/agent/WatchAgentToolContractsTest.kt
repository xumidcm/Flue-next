package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.tools.*
import com.flue.launcher.watchface.agent.prompt.WatchAgentPrompt
import com.flue.launcher.watchface.agent.workspace.WorkspacePermissionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class WatchAgentToolContractsTest {
    @Test
    fun `schema exposes document and workspace image tools with writes marked as barriers`() {
        val contracts = WatchAgentToolContracts.all

        assertEquals(
            listOf(
                "list_workspace_files",
                "read_image",
                "inspect_face",
                "read_document",
                "search_knowledge",
                "validate_face",
                "preview_face",
                "edit_document",
                "write_document",
            ),
            contracts.map { it.name.wireName },
        )
        assertEquals(
            setOf("edit_document", "write_document"),
            contracts.filter { it.access == WatchAgentToolAccess.WRITE }.map { it.name.wireName }.toSet(),
        )
        assertTrue(WatchAgentToolName.INSPECT_FACE.isReadOnly)
        assertTrue(WatchAgentToolName.PREVIEW_FACE.isReadOnly)

        val runner = WatchAgentToolRunner(object : WatchAgentToolHost {
            override val currentRevision = 1L
            override fun inspectFace(request: WatchAgentInspectFaceRequest) = WatchAgentInspectFaceResult(1L)
            override fun readDocument(request: WatchAgentReadDocumentRequest) = WatchAgentDocumentResult(request.path, 1L, "")
            override fun searchKnowledge(request: WatchAgentSearchKnowledgeRequest) = WatchAgentKnowledgeResult(request.query, emptyList())
            override fun validateFace(request: WatchAgentValidateFaceRequest) = WatchAgentValidationResult(1L)
            override fun previewFace(request: WatchAgentPreviewRequest) = WatchAgentPreviewResult(1L, "face", "native")
            override fun editDocument(request: WatchAgentEditDocumentRequest) = WatchAgentDocumentMutationResult(request.path, 2L, "")
            override fun writeDocument(request: WatchAgentWriteDocumentRequest) = WatchAgentDocumentMutationResult(request.path, 2L, "")
        })
        assertEquals(WatchAgentPrompt.promptVersion, runner.promptVersion)
        assertEquals(WatchAgentToolContracts.schemaVersion, runner.toolSchemaVersion)
    }

    @Test
    fun `scheduler runs at most four reads in parallel and keeps result order across write barriers`() {
        val startedReads = CountDownLatch(4)
        val releaseReads = CountDownLatch(1)
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val execution = mutableListOf<String>()
        val scheduler = WatchAgentToolScheduler(executor = WatchAgentToolExecutor { call ->
            val now = active.incrementAndGet()
            peak.updateAndGet { old -> maxOf(old, now) }
            synchronized(execution) { execution += call.id }
            if (call.name == "inspect_face") {
                startedReads.countDown()
                releaseReads.await(2, TimeUnit.SECONDS)
            }
            active.decrementAndGet()
            WatchAgentToolResult(call.id, call.name, revision = 1L, content = call.id)
        })
        val calls = (1..6).map { WatchAgentToolCall("read-$it", "inspect_face") } +
            WatchAgentToolCall("write", "write_document") +
            WatchAgentToolCall("after", "validate_face")

        val resultThread = Thread {
            runBlocking { scheduler.execute(calls) }
        }
        resultThread.start()
        assertTrue(startedReads.await(2, TimeUnit.SECONDS))
        releaseReads.countDown()
        resultThread.join(2_000)

        assertTrue(peak.get() <= 4)
        assertEquals(calls.map { it.id }, scheduler.lastResults.map { it.callId })
        assertTrue(execution.indexOf("write") > execution.indexOf("read-6"))
        assertTrue(execution.indexOf("after") > execution.indexOf("write"))
    }

    @Test
    fun `runner rejects stale writes and rolls back one request undo group`() = runBlocking {
        val host = RecordingToolHost()
        val runner = WatchAgentToolRunner(host)

        val results = runner.execute(
            listOf(
                WatchAgentToolCall("read", "inspect_face"),
                WatchAgentToolCall(
                    "write",
                    "write_document",
                    mapOf(
                        "path" to "watch.xml",
                        "content" to "<Watch/> modified",
                        "expected_revision" to 1L,
                    ),
                ),
            ),
        )

        assertEquals(listOf("read", "write"), results.map { it.callId })
        assertEquals("stale_revision", results[1].error?.code)
        assertEquals(1, host.beginCount)
        assertEquals(1, host.rollbackCount)
        assertEquals(0, host.commitCount)
    }

    @Test
    fun `runner hides and rejects reads when workspace read permission is disabled`() = runBlocking {
        val host = RecordingToolHost()
        val runner = WatchAgentToolRunner(
            host = host,
            permissionProvider = {
                WorkspacePermissionSettings(readAllowed = false, writeAllowed = true)
            },
        )

        assertTrue(runner.toolContracts.none { it.access == WatchAgentToolAccess.READ_ONLY })
        val result = runner.execute(WatchAgentToolCall("read", "inspect_face"))

        assertEquals("permission_denied", result.error?.code)
    }

    @Test
    fun `runner hides and rejects writes when workspace write permission is disabled`() = runBlocking {
        val host = InMemoryWatchAgentToolHost("<Watch name=\"Original\"/>")
        val runner = WatchAgentToolRunner(
            host = host,
            permissionProvider = {
                WorkspacePermissionSettings(readAllowed = true, writeAllowed = false)
            },
        )

        assertTrue(runner.toolContracts.none { it.access == WatchAgentToolAccess.WRITE })
        val result = runner.execute(
            WatchAgentToolCall(
                "write",
                "write_document",
                mapOf(
                    "path" to "watch.xml",
                    "content" to "<Watch name=\"Forbidden\"/>",
                    "expected_revision" to 1L,
                ),
            ),
        )

        assertEquals("permission_denied", result.error?.code)
        assertEquals("Original", host.inspectFace(WatchAgentInspectFaceRequest()).name)
    }

    @Test
    fun `classifier sends pure IIFE to Lua and normalizes one images prefix`() {
        val iife = "((function() local mo=tonumber('{dn}') or 9 return mo end)())"

        assertEquals(
            WatchAgentExpressionKind.LUA_EXPRESSION,
            WatchAgentExpressionClassifier.classify(iife, WatchAgentAttributeKind.NUMBER).kind,
        )
        assertEquals(
            WatchAgentExpressionKind.FAST_EXPRESSION,
            WatchAgentExpressionClassifier.classify("{dh23}:{dmz}", WatchAgentAttributeKind.TEXT).kind,
        )
        assertEquals(
            WatchAgentExpressionKind.LUA_EXPRESSION,
            WatchAgentExpressionClassifier.classify("WF.thirdParty.value", WatchAgentAttributeKind.NUMBER).kind,
        )
        assertEquals(
            WatchAgentExpressionKind.LITERAL,
            WatchAgentExpressionClassifier.classify("fish and chips", WatchAgentAttributeKind.TEXT).kind,
        )
        assertEquals(
            WatchAgentExpressionKind.INVALID,
            WatchAgentExpressionClassifier.classify("{dn", WatchAgentAttributeKind.NUMBER).kind,
        )
        assertEquals(
            WatchAgentExpressionKind.INVALID,
            WatchAgentExpressionClassifier.classify("function(", WatchAgentAttributeKind.NUMBER).kind,
        )
        assertEquals(
            "f315.png",
            WatchAgentAssetPathNormalizer.normalizeImagePath("images/f315.png"),
        )
        assertEquals(
            "hands/f315.png",
            WatchAgentAssetPathNormalizer.normalizeImagePath("images/hands/f315.png"),
        )
        assertEquals(
            "<Watch><Layer type=\"image\" path=\"f315.png\"/><Layer type=\"image\" path=\"f316.png\"/></Watch>",
            WatchAgentAssetPathNormalizer.normalizeImageLayerPaths(
                "<Watch><Layer type=\"image\" path=\"images/f315.png\"/><Layer type=\"image\" path=\"f316.png\"/></Watch>",
            ),
        )
    }

    @Test
    fun `in-memory host keeps revision monotonic and restores a failed request`() = runBlocking {
        val host = InMemoryWatchAgentToolHost("<Watch name=\"Demo\"/>")
        val runner = WatchAgentToolRunner(host)

        val first = runner.execute(
            WatchAgentToolCall(
                "write",
                "write_document",
                mapOf("path" to "watch.xml", "content" to "<Watch name=\"Changed\"/>", "expected_revision" to 1L),
            ),
        )
        assertTrue(first.succeeded)
        assertEquals(2L, first.revision)

        val failed = runner.execute(
            listOf(
                WatchAgentToolCall(
                    "write-1",
                    "write_document",
                    mapOf("path" to "watch.xml", "content" to "<Watch name=\"Transient\"/>", "expected_revision" to 2L),
                ),
                WatchAgentToolCall(
                    "write-2",
                    "write_document",
                    mapOf("path" to "watch.xml", "content" to "<Watch name=\"Stale\"/>", "expected_revision" to 2L),
                ),
            ),
        )
        assertEquals("stale_revision", failed[1].error?.code)
        assertEquals("Changed", host.inspectFace(WatchAgentInspectFaceRequest()).name)
    }

    @Test
    fun `in-memory document writes normalize legacy images prefixes`() = runBlocking {
        val host = InMemoryWatchAgentToolHost(
            "<Watch><Layer type=\"image\" path=\"images/f315.png\"/></Watch>",
            assets = listOf(com.flue.launcher.watchface.agent.tools.WatchAgentAssetSummary("images/f315.png")),
        )
        val result = WatchAgentToolRunner(host).execute(
            WatchAgentToolCall(
                "write",
                "write_document",
                mapOf(
                    "path" to "watch.xml",
                    "content" to "<Watch><Layer type=\"image\" path=\"images/images/f315.png\"/></Watch>",
                    "expected_revision" to 1L,
                ),
            ),
        )

        assertTrue(result.succeeded)
        assertTrue((result.content as WatchAgentDocumentMutationResult).content.contains("path=\"images/f315.png\""))
    }

    private class RecordingToolHost : WatchAgentToolHost, WatchAgentUndoHost {
        override val currentRevision: Long = 2L
        var beginCount = 0
        var commitCount = 0
        var rollbackCount = 0

        override fun inspectFace(request: WatchAgentInspectFaceRequest) =
            WatchAgentInspectFaceResult(currentRevision, "Test", "V1")

        override fun readDocument(request: WatchAgentReadDocumentRequest) =
            WatchAgentDocumentResult(request.path, currentRevision, "<Watch/>")

        override fun searchKnowledge(request: WatchAgentSearchKnowledgeRequest) =
            WatchAgentKnowledgeResult(request.query, emptyList())

        override fun validateFace(request: WatchAgentValidateFaceRequest) =
            WatchAgentValidationResult(currentRevision)

        override fun previewFace(request: WatchAgentPreviewRequest) =
            WatchAgentPreviewResult(currentRevision, request.target, "native")

        override fun editDocument(request: WatchAgentEditDocumentRequest) =
            WatchAgentDocumentMutationResult(request.path, currentRevision + 1, "changed")

        override fun writeDocument(request: WatchAgentWriteDocumentRequest) =
            WatchAgentDocumentMutationResult(request.path, currentRevision + 1, request.content)

        override fun beginUndoGroup(): WatchAgentUndoGroup {
            beginCount++
            return object : WatchAgentUndoGroup {
                override fun commit() { commitCount++ }
                override fun rollback() { rollbackCount++ }
            }
        }
    }
}
