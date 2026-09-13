package com.flue.launcher.watchface.agent.tools

import com.flue.launcher.watchface.editor.WatchEditorAgentCandidate
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import java.nio.file.Files
import java.nio.file.LinkOption

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
    @Volatile private var lastSyncedWatchXml: String? = null
    @Volatile private var lastSyncedGlobalScript: String? = null
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
        syncMirrorFromEditor(candidate)
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
        syncMirrorFromEditor(candidate)
        onDocumentMutated(candidate)
        activeUndoGroup?.recordAgentRevision(candidate.revision)
        return candidate.mutation(request.path)
    }

    override fun syncFaceMirror(): Any {
        val manager = workspaceManager ?: throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定工作区")
        val id = watchScope?.let { manager.bindingFor(it)?.workspaceId } ?: throw WatchAgentToolException("mirror_unavailable", "当前表盘未绑定工作区")
        val candidate = session.agentCandidate()
        val resources = syncMirrorFromEditor(candidate) ?: throw WatchAgentToolException("mirror_unavailable", "镜像同步失败")
        return mapOf("workspace_id" to id, "revision" to currentRevision, "path" to "face/", "resources" to resources.keys.sorted())
    }

    override fun prepareBashMirror() {
        // Bash runs against the mirror and may edit it in place. Refresh the
        // baseline first so the post-command auto-sync can distinguish those
        // edits from an older copy and never roll the live editor backwards.
        val manager = workspaceManager ?: return
        val id = watchScope?.let { manager.bindingFor(it)?.workspaceId } ?: return
        check(syncMirrorFromEditor(session.agentCandidate()) != null) {
            "无法在 Bash 执行前同步表盘镜像"
        }
        check(manager.get(id) != null) { "当前表盘绑定工作区不存在" }
    }

    override fun validateFaceMirror(): Any {
        val manager = workspaceManager ?: throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定工作区")
        val id = watchScope?.let { manager.bindingFor(it)?.workspaceId } ?: throw WatchAgentToolException("mirror_unavailable", "当前表盘未绑定工作区")
        val mirror = manager.readFaceMirror(id) ?: throw WatchAgentToolException("mirror_unavailable", "镜像目录不存在")
        val diagnostics = mutableListOf<WatchAgentDiagnostic>()
        val classifications = WatchAgentExpressionClassifier.classifyXml(mirror.watchXml)
        classifications.filter { it.kind == WatchAgentExpressionKind.INVALID }.forEach { classification ->
            diagnostics += WatchAgentDiagnostic(WatchAgentDiagnosticSeverity.ERROR, "invalid_expression", classification.reason ?: "表达式无效")
        }
        validateGlobalScript(mirror.globalScript)?.let { diagnostics += it }
        IMAGE_PATH_PATTERN.findAll(mirror.watchXml).forEach { match ->
            match.groupValues[2].split('`').map(String::trim).filter { it.isNotBlank() }.forEach { raw ->
                val clean = raw.removePrefix("/")
                val canonical = clean.let { if (it.startsWith("images/", true)) it else "images/$it" }
                if (clean !in mirror.resources && canonical !in mirror.resources) {
                    diagnostics += WatchAgentDiagnostic(WatchAgentDiagnosticSeverity.ERROR, "missing_resource", "镜像缺少资源：$clean")
                }
            }
        }
        return mapOf(
            "valid" to diagnostics.none { it.severity == WatchAgentDiagnosticSeverity.ERROR },
            "revision" to currentRevision,
            "resources" to mirror.resources,
            "diagnostics" to diagnostics.map { mapOf("severity" to it.severity.name, "code" to it.code, "message" to it.message) },
        )
    }

    override fun commitFaceMirror(expectedRevision: Long): Any {
        checkRevision(expectedRevision)
        val manager = workspaceManager ?: throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定工作区")
        val id = watchScope?.let { manager.bindingFor(it)?.workspaceId } ?: throw WatchAgentToolException("mirror_unavailable", "当前表盘未绑定工作区")
        val current = session.agentCandidate()
        val mirror = manager.readFaceMirror(id) ?: throw WatchAgentToolException("mirror_unavailable", "镜像目录不存在")
        // The mirror is a Bash working copy. If the editor changed since the last
        // editor-to-mirror sync, an older mirror must never overwrite that newer
        // document. Refresh it and report a safe no-op instead.
        if (lastSyncedWatchXml == null || lastSyncedWatchXml != current.watchXml || lastSyncedGlobalScript != current.globalScript) {
            syncFaceMirror()
            return mapOf("committed" to false, "skipped" to "stale_mirror", "revision" to currentRevision)
        }
        val validation = validateFaceMirror() as Map<*, *>
        if (validation["valid"] != true) throw WatchAgentToolException("mirror_invalid", "镜像校验失败，拒绝写回编辑器")
        var revision = expectedRevision
        val existing = session.resourceInventory().map { it.relativePath }.toSet()
        mirror.resources.forEach { relative ->
            if (relative in existing) return@forEach
            val source = manager.resolveWorkspaceFile(id, "face/$relative")
                ?: throw WatchAgentToolException("mirror_unavailable", "镜像资源不存在：$relative")
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw WatchAgentToolException("invalid_resource", "镜像资源不是普通文件：$relative")
            val directory = relative.substringBefore('/')
            val name = relative.substringAfterLast('/')
            session.importResource(Files.newInputStream(source), name, directory)
            revision = currentRevision
        }
        if (mirror.watchXml != current.watchXml) {
            writeDocument(WatchAgentWriteDocumentRequest(WatchAgentDocumentPath.WATCH_XML, mirror.watchXml, revision))
            revision = currentRevision
        }
        if (mirror.globalScript != session.globalScript()) {
            writeDocument(WatchAgentWriteDocumentRequest(WatchAgentDocumentPath.GLOBAL_SCRIPT, mirror.globalScript, revision))
            revision = currentRevision
        }
        return mapOf("committed" to true, "revision" to revision, "resources" to mirror.resources)
    }

    override fun importFaceResource(path: String): Any {
        val manager = workspaceManager ?: throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定工作区")
        val id = watchScope?.let { manager.bindingFor(it)?.workspaceId } ?: throw WatchAgentToolException("mirror_unavailable", "当前表盘未绑定工作区")
        val clean = path.trim().replace('\\', '/').removePrefix("face/")
        val source = manager.resolveWorkspaceFile(id, "face/$clean")
            ?: throw WatchAgentToolException("source_not_found", "镜像资源不存在：$clean")
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw WatchAgentToolException("invalid_resource", "镜像资源不是普通文件")
        val directory = clean.substringBefore('/')
        val imported = session.importResource(Files.newInputStream(source), clean.substringAfterLast('/'), directory)
        syncMirrorFromEditor(session.agentCandidate())
        onDocumentMutated(session.agentCandidate())
        return mapOf("imported" to imported, "revision" to currentRevision)
    }

    private fun syncMirrorFromEditor(candidate: WatchEditorAgentCandidate): Map<String, ByteArray>? {
        val manager = workspaceManager ?: return emptyMap()
        val id = watchScope?.let { manager.bindingFor(it)?.workspaceId } ?: return emptyMap()
        return runCatching {
            val resources = session.resourceInventory().associate { asset ->
                asset.relativePath to session.draftDirectory.toPath().resolve(asset.relativePath).let(Files::readAllBytes)
            }
            check(manager.syncFaceMirror(id, candidate.watchXml, candidate.globalScript, resources)) { "镜像同步失败" }
            lastSyncedWatchXml = candidate.watchXml
            lastSyncedGlobalScript = candidate.globalScript
            resources
        }.getOrNull()
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
