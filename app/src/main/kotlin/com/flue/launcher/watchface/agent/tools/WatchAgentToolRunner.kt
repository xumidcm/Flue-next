package com.flue.launcher.watchface.agent.tools

import com.flue.launcher.watchface.agent.prompt.WatchAgentPrompt
import com.flue.launcher.watchface.agent.workspace.WorkspacePermissionSettings
import com.flue.launcher.watchface.agent.workspace.ProotShellRunner
import com.flue.launcher.watchface.agent.workspace.ShellCommandRequest

/** Executes provider tool calls against a host-owned editor session. */
class WatchAgentToolRunner(
    private val host: WatchAgentToolHost,
    maxParallel: Int = WatchAgentToolScheduler.DEFAULT_MAX_PARALLEL,
    private val permissionProvider: () -> WorkspacePermissionSettings = { WorkspacePermissionSettings() },
    private val workspaceImages: AgentWorkspaceImageTools? = null,
    private val shellRunner: ProotShellRunner? = null,
    private val workspaceId: String = "",
) {
    val promptVersion: String get() = WatchAgentPrompt.promptVersion
    val toolSchemaVersion: String get() = WatchAgentToolContracts.schemaVersion
    val toolContracts: List<WatchAgentToolContract>
        get() {
            val permissions = currentPermissions()
            return WatchAgentToolContracts.all.filter { permissions.allows(it.access) &&
                (workspaceImages != null || it.name !in setOf(WatchAgentToolName.LIST_WORKSPACE_FILES, WatchAgentToolName.READ_IMAGE)) &&
                (shellRunner != null || it.name != WatchAgentToolName.RUN_BASH) }
        }

    private val scheduler = WatchAgentToolScheduler(::executeOne, maxParallel)

    fun beginRequestUndoGroup(): WatchAgentUndoGroup? =
        (host as? WatchAgentUndoHost)?.beginUndoGroup()

    suspend fun execute(
        calls: List<WatchAgentToolCall>,
        manageUndoGroup: Boolean = true,
    ): List<WatchAgentToolResult> {
        val undoGroup = if (manageUndoGroup) beginRequestUndoGroup() else null
        return try {
            val results = scheduler.execute(calls)
            if (results.any { !it.succeeded }) undoGroup?.rollback() else undoGroup?.commit()
            results
        } catch (error: Throwable) {
            runCatching { undoGroup?.rollback() }
            throw error
        }
    }

    suspend fun execute(call: WatchAgentToolCall): WatchAgentToolResult = execute(listOf(call)).first()

    private fun executeOne(call: WatchAgentToolCall): WatchAgentToolResult = try {
        val name = call.toolName
        if (name != null && !currentPermissions().allows(name.access)) {
            return failure(call, "permission_denied", "工作区权限不允许执行此工具")
        }
        when (name) {
            WatchAgentToolName.LIST_WORKSPACE_FILES -> success(call, host.currentRevision,
                requireNotNull(workspaceImages) { "未绑定工作区图片工具" }.listFiles())
            WatchAgentToolName.READ_IMAGE -> success(call, host.currentRevision,
                requireNotNull(workspaceImages) { "未绑定工作区图片工具" }.readImage(call.requiredString("path")))
            WatchAgentToolName.RUN_BASH -> {
                val runner = requireNotNull(shellRunner) { "工作区 Bash 尚未初始化" }
                val result = runner.run(ShellCommandRequest(
                    workspaceId = workspaceId,
                    command = call.requiredString("command"),
                    timeoutSeconds = (call.arguments["timeout_seconds"] as? Number)?.toLong() ?: 30L,
                    maxOutputBytes = 256 * 1024,
                ))
                success(call, host.currentRevision, result)
            }
            WatchAgentToolName.INSPECT_FACE -> {
                val result = host.inspectFace(WatchAgentInspectFaceRequest(call.optionalRevision()))
                success(call, result.revision, result)
            }
            WatchAgentToolName.READ_DOCUMENT -> {
                val path = call.documentPath("path")
                val result = host.readDocument(WatchAgentReadDocumentRequest(path, call.optionalRevision()))
                success(call, result.revision, result)
            }
            WatchAgentToolName.SEARCH_KNOWLEDGE -> {
                val result = host.searchKnowledge(
                    WatchAgentSearchKnowledgeRequest(call.requiredString("query")),
                )
                success(call, host.currentRevision, result)
            }
            WatchAgentToolName.VALIDATE_FACE -> {
                val result = host.validateFace(WatchAgentValidateFaceRequest(call.optionalRevision()))
                success(call, result.revision, result)
            }
            WatchAgentToolName.PREVIEW_FACE -> {
                val result = host.previewFace(call.previewRequest())
                success(call, result.revision, result)
            }
            WatchAgentToolName.EDIT_DOCUMENT -> {
                val request = call.editRequest()
                checkExpectedRevision(request.expectedRevision)
                val result = host.editDocument(request)
                success(call, result.revision, result)
            }
            WatchAgentToolName.WRITE_DOCUMENT -> {
                val request = call.writeRequest()
                checkExpectedRevision(request.expectedRevision)
                val result = host.writeDocument(request)
                success(call, result.revision, result)
            }
            null -> failure(call, "unknown_tool", "未知工具：${call.name}")
        }
    } catch (error: WatchAgentToolException) {
        failure(call, error.code, error.message)
    } catch (error: IllegalArgumentException) {
        failure(call, "invalid_arguments", error.message ?: "工具参数无效")
    } catch (error: Throwable) {
        failure(call, "tool_failed", error.displayableMessage())
    }

    private fun checkExpectedRevision(expected: Long) {
        val actual = host.currentRevision
        if (expected != actual) throw staleRevision(expected, actual)
    }

    private fun currentPermissions(): WorkspacePermissionSettings =
        runCatching(permissionProvider).getOrDefault(DENY_ALL_PERMISSIONS)

    private fun success(call: WatchAgentToolCall, revision: Long, content: Any?) =
        WatchAgentToolResult(call.id, call.name, revision, content = content)

    private fun failure(call: WatchAgentToolCall, code: String, message: String) =
        WatchAgentToolResult(
            callId = call.id,
            name = call.name,
            revision = host.currentRevision,
            error = WatchAgentToolError(code, message),
        )
}

private val DENY_ALL_PERMISSIONS = WorkspacePermissionSettings(
    readAllowed = false,
    writeAllowed = false,
    attachmentImportAllowed = false,
)

private fun WorkspacePermissionSettings.allows(access: WatchAgentToolAccess): Boolean = when (access) {
    WatchAgentToolAccess.READ_ONLY -> readAllowed
    WatchAgentToolAccess.WRITE -> writeAllowed
}

private fun Throwable.displayableMessage(): String {
    val root = generateSequence(this) { it.cause }.lastOrNull() ?: this
    return when {
        this is ExceptionInInitializerError || root is ExceptionInInitializerError ->
            "工具初始化失败，已保护当前会话。请重试；若持续出现，请检查表盘预览和 Provider 配置。"
        !message.isNullOrBlank() -> message.orEmpty().take(500)
        !root.message.isNullOrBlank() -> root.message.orEmpty().take(500)
        else -> root.javaClass.simpleName
    }
}

private fun WatchAgentToolCall.requiredString(name: String): String =
    arguments[name]?.toString()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("缺少参数：$name")

private fun WatchAgentToolCall.optionalRevision(): Long? =
    arguments["expected_revision"]?.toLongValue()
        ?: arguments["base_revision"]?.toLongValue()

private fun WatchAgentToolCall.requiredRevision(): Long =
    optionalRevision() ?: throw IllegalArgumentException("缺少参数：expected_revision")

private fun Any.toLongValue(): Long = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull() ?: throw IllegalArgumentException("revision 必须是整数")
    else -> throw IllegalArgumentException("revision 必须是整数")
}

private fun WatchAgentToolCall.documentPath(name: String): WatchAgentDocumentPath =
    WatchAgentDocumentPath.fromWirePath(requiredString(name))
        ?: throw IllegalArgumentException("不支持的文档路径：${arguments[name]}")

private fun WatchAgentToolCall.editRequest() = WatchAgentEditDocumentRequest(
    path = documentPath("path"),
    oldText = requiredString("old_text"),
    newText = arguments["new_text"]?.toString() ?: "",
    expectedRevision = requiredRevision(),
)

private fun WatchAgentToolCall.writeRequest() = WatchAgentWriteDocumentRequest(
    path = documentPath("path"),
    content = arguments["content"]?.toString() ?: throw IllegalArgumentException("缺少参数：content"),
    expectedRevision = requiredRevision(),
)

private fun WatchAgentToolCall.previewRequest() = WatchAgentPreviewRequest(
    target = arguments["target"]?.toString()?.ifBlank { "face" } ?: "face",
    mode = when (arguments["mode"]?.toString()) {
        null, "in_context" -> WatchAgentPreviewMode.IN_CONTEXT
        "isolated" -> WatchAgentPreviewMode.ISOLATED
        else -> throw IllegalArgumentException("mode 必须是 in_context 或 isolated")
    },
    time = arguments["time"]?.toString(),
    battery = arguments["battery"]?.let { it.toLongValue().toInt() },
    display = when (arguments["display"]?.toString()) {
        null -> null
        "bright" -> WatchAgentDisplayMode.BRIGHT
        "dim" -> WatchAgentDisplayMode.DIM
        else -> throw IllegalArgumentException("display 必须是 bright 或 dim")
    },
    expectedRevision = optionalRevision(),
)
