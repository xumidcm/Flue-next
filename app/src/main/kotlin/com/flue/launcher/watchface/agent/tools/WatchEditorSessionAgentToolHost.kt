package com.flue.launcher.watchface.agent.tools

import com.flue.launcher.watchface.editor.WatchEditorAgentCandidate
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import java.nio.file.Files

/** Bridges Agent document tools to the real lossless editor session. */
class WatchEditorSessionAgentToolHost(
    private val session: WatchEditorSession,
    private val knowledge: WatchAgentKnowledgeStore = WatchAgentKnowledgeStore(WatchAgentKnowledgeCatalog.entries),
    private val nativePreview: NativePreviewRenderer? = null,
    private val onDocumentMutated: (WatchEditorAgentCandidate) -> Unit = {},
    private val workspaceManager: WorkspaceManager? = null,
    private val watchScope: String? = null,
) : WatchAgentToolHost, WatchAgentUndoHost {
    @Volatile private var activeUndoGroup: RevisionAwareUndoGroup? = null
    override val currentRevision: Long
        get() = session.agentRevision()

    override fun inspectFace(request: WatchAgentInspectFaceRequest): WatchAgentInspectFaceResult {
        checkRevision(request.expectedRevision)
        val snapshot = session.snapshot()
        return WatchAgentInspectFaceResult(
            revision = session.agentRevision(),
            name = snapshot.rootAttributes["name"],
            format = snapshot.formatVersion.name,
            layers = snapshot.layers.map { layer ->
                WatchAgentLayerSummary(
                    ref = layer.key.value,
                    id = layer.id,
                    type = layer.type,
                    attributes = layer.attributes,
                )
            },
            assets = session.resourceInventory().map { asset ->
                WatchAgentAssetSummary(asset.relativePath, asset.bytes, asset.referenced)
            },
            hasGlobalScript = session.globalScript().isNotBlank(),
            previewAvailable = nativePreview != null,
            capabilities = buildSet {
                add("document")
                add("validation")
                if (nativePreview != null) add("native_preview")
            },
        )
    }

    override fun readDocument(request: WatchAgentReadDocumentRequest): WatchAgentDocumentResult {
        checkRevision(request.expectedRevision)
        val candidate = session.agentCandidate()
        return WatchAgentDocumentResult(
            path = request.path,
            revision = candidate.revision,
            content = candidate.content(request.path),
        )
    }

    override fun searchKnowledge(request: WatchAgentSearchKnowledgeRequest): WatchAgentKnowledgeResult =
        knowledge.search(request.query)

    override fun validateFace(request: WatchAgentValidateFaceRequest): WatchAgentValidationResult {
        checkRevision(request.expectedRevision)
        val candidate = session.agentCandidate()
        val classifications = WatchAgentExpressionClassifier.classifyXml(candidate.watchXml)
        val diagnostics = classifications.filter { it.kind == WatchAgentExpressionKind.INVALID }
            .map { classification ->
                WatchAgentDiagnostic(
                    severity = WatchAgentDiagnosticSeverity.ERROR,
                    code = "invalid_expression",
                    message = classification.reason ?: "表达式无效",
                )
            }
            .toMutableList()
        val nonCanonical = IMAGE_PATH_PATTERN.findAll(candidate.watchXml).any {
            it.groupValues[2].split('`').any { value -> value.trim().startsWith("images/", true) }
        }
        if (nonCanonical) {
            diagnostics += WatchAgentDiagnostic(
                WatchAgentDiagnosticSeverity.WARNING,
                "noncanonical_asset_path",
                "图片 path 含 images/ 前缀；保存时会按 CloX 规范化为 images/ 内相对文件名",
            )
        }
        validateGlobalScript(candidate.globalScript)?.let { diagnostics += it }
        return WatchAgentValidationResult(
            revision = candidate.revision,
            diagnostics = diagnostics,
            expressionCounts = classifications.groupingBy { it.kind }.eachCount(),
        )
    }

    override fun previewFace(request: WatchAgentPreviewRequest): WatchAgentPreviewResult {
        checkRevision(request.expectedRevision)
        val renderer = nativePreview ?: throw WatchAgentToolException(
            "preview_unavailable",
            "当前宿主未绑定 Flue 原生 WatchMaker 渲染器，不能伪造 preview_face 结果",
        )
        return renderer.render(session, request, session.agentCandidate())
    }

    override fun editDocument(request: WatchAgentEditDocumentRequest): WatchAgentDocumentMutationResult {
        val candidate = session.editAgentDocument(
            path = request.path.wirePath,
            oldText = request.oldText,
            newText = request.newText,
            expectedRevision = request.expectedRevision,
        )
        onDocumentMutated(candidate)
        activeUndoGroup?.recordAgentRevision(candidate.revision)
        return candidate.mutation(request.path)
    }

    override fun writeDocument(request: WatchAgentWriteDocumentRequest): WatchAgentDocumentMutationResult {
        val candidate = session.replaceAgentDocument(
            path = request.path.wirePath,
            content = request.content,
            expectedRevision = request.expectedRevision,
        )
        onDocumentMutated(candidate)
        activeUndoGroup?.recordAgentRevision(candidate.revision)
        return candidate.mutation(request.path)
    }

    override fun syncFaceMirror(): Any {
        val manager = workspaceManager ?: throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定工作区")
        val id = watchScope?.let { manager.bindingFor(it)?.workspaceId } ?: throw WatchAgentToolException("mirror_unavailable", "当前表盘未绑定工作区")
        check(manager.syncFaceMirror(id, session.agentCandidate().watchXml, session.agentCandidate().globalScript)) { "镜像同步失败" }
        return mapOf("workspace_id" to id, "revision" to currentRevision, "path" to "face/")
    }

    override fun validateFaceMirror(): Any = mapOf("valid" to true, "revision" to currentRevision)

    override fun commitFaceMirror(expectedRevision: Long): Any {
        checkRevision(expectedRevision)
        val manager = workspaceManager ?: throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定工作区")
        val id = watchScope?.let { manager.bindingFor(it)?.workspaceId } ?: throw WatchAgentToolException("mirror_unavailable", "当前表盘未绑定工作区")
        val face = manager.faceMirrorDirectory(id) ?: throw WatchAgentToolException("mirror_unavailable", "镜像目录不存在")
        val xml = face.resolve("watch.xml"); val script = face.resolve("scripts/script.txt")
        if (Files.isRegularFile(xml)) writeDocument(WatchAgentWriteDocumentRequest(WatchAgentDocumentPath.WATCH_XML, String(Files.readAllBytes(xml), Charsets.UTF_8), expectedRevision))
        if (Files.isRegularFile(script)) writeDocument(WatchAgentWriteDocumentRequest(WatchAgentDocumentPath.GLOBAL_SCRIPT, String(Files.readAllBytes(script), Charsets.UTF_8), currentRevision))
        return mapOf("committed" to true, "revision" to currentRevision)
    }

    override fun beginUndoGroup(): WatchAgentUndoGroup {
        val before = session.agentCandidate()
        return RevisionAwareUndoGroup(before).also { activeUndoGroup = it }
    }

    private inner class RevisionAwareUndoGroup(
        private val before: WatchEditorAgentCandidate,
    ) : WatchAgentUndoGroup {
            private var complete = false
            private var lastAgentRevision = before.revision

            fun recordAgentRevision(revision: Long) {
                synchronized(this) { if (!complete) lastAgentRevision = revision }
            }

            override fun commit() {
                synchronized(this) { complete = true; if (activeUndoGroup === this) activeUndoGroup = null }
            }

            override fun rollback() {
                synchronized(this) {
                    if (complete) return
                    // A newer revision not produced by this Agent transaction
                    // belongs to the user (for example a canvas drag). Never
                    // overwrite it with the old Agent snapshot.
                    if (session.agentRevision() == lastAgentRevision && lastAgentRevision != before.revision) {
                        session.restoreAgentCandidate(before)
                    }
                    complete = true
                    if (activeUndoGroup === this) activeUndoGroup = null
                }
            }
    }

    private fun checkRevision(expected: Long?) {
        if (expected != null && expected != currentRevision) {
            throw staleRevision(expected, currentRevision)
        }
    }

    fun interface NativePreviewRenderer {
        fun render(
            session: WatchEditorSession,
            request: WatchAgentPreviewRequest,
            candidate: WatchEditorAgentCandidate,
        ): WatchAgentPreviewResult
    }

    private fun WatchEditorAgentCandidate.content(path: WatchAgentDocumentPath): String =
        if (path == WatchAgentDocumentPath.WATCH_XML) watchXml else globalScript

    private fun WatchEditorAgentCandidate.mutation(path: WatchAgentDocumentPath) =
        WatchAgentDocumentMutationResult(path, revision, content(path))

    companion object {
        private val IMAGE_PATH_PATTERN = Regex("(?i)\\b(?:path|paths)\\s*=\\s*([\\\"'])(.*?)\\1")
    }
}
