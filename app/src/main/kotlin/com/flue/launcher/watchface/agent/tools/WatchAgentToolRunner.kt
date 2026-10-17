package com.flue.launcher.watchface.agent.tools

import com.flue.launcher.watchface.agent.prompt.WatchAgentPrompt
import com.flue.launcher.watchface.agent.workspace.WorkspacePermissionSettings
import com.flue.launcher.watchface.agent.workspace.ProotShellRunner
import com.flue.launcher.watchface.agent.workspace.ShellCommandRequest
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import com.flue.launcher.watchface.agent.workspace.WorkspacePathPolicy
import com.flue.launcher.watchface.agent.workspace.AgentWorkspacePathResolver
import com.flue.launcher.watchface.agent.provider.AgentImageGenerationClient
import com.flue.launcher.watchface.agent.provider.ImageGenerationOptions
import com.flue.launcher.watchface.agent.provider.AgentImageInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.zip.ZipInputStream

/** Executes provider tool calls against a host-owned editor session. */
class WatchAgentToolRunner(
    private val host: WatchAgentToolHost,
    maxParallel: Int = WatchAgentToolScheduler.DEFAULT_MAX_PARALLEL,
    private val permissionProvider: () -> WorkspacePermissionSettings = { WorkspacePermissionSettings() },
    private val workspaceImages: AgentWorkspaceImageTools? = null,
    private val shellRunner: ProotShellRunner? = null,
    private val workspaceId: String = "",
    private val workspaceIdProvider: () -> String = { workspaceId },
    private val workspaceManager: WorkspaceManager? = null,
    private val attachmentsDir: File? = null,
    private val imageClient: AgentImageGenerationClient? = null,
    private val webClient: WatchAgentWebResearchClient? = null,
    private val imageDefaults: ImageGenerationOptions = ImageGenerationOptions(),
    private val webMaxResults: Int = 5,
    private val webSearchDepth: String = "basic",
    private val webFetchEnabled: Boolean = true,
) {
    val promptVersion: String get() = WatchAgentPrompt.promptVersion
    val toolSchemaVersion: String get() = WatchAgentToolContracts.schemaVersion
    val toolContracts: List<WatchAgentToolContract>
        get() {
            val permissions = currentPermissions()
            return (WatchAgentToolContracts.all + WatchAgentToolContracts.extended + WatchAgentToolContracts.mirror + WatchAgentToolContracts.optional).filter { it.name != WatchAgentToolName.COMMIT_FACE_MIRROR && permissions.allows(it.access) &&
                (workspaceImages != null || it.name !in setOf(WatchAgentToolName.LIST_WORKSPACE_FILES, WatchAgentToolName.READ_IMAGE)) &&
                (shellRunner?.capability(workspaceIdProvider())?.bashAvailable == true || it.name != WatchAgentToolName.RUN_BASH) &&
                (imageClient != null || it.name !in setOf(WatchAgentToolName.GENERATE_IMAGE, WatchAgentToolName.EDIT_IMAGE)) &&
                (webClient != null || it.name !in setOf(WatchAgentToolName.WEB_SEARCH, WatchAgentToolName.FETCH_WEB_PAGE)) &&
                (webFetchEnabled || it.name != WatchAgentToolName.FETCH_WEB_PAGE) }
        }

    private val scheduler = WatchAgentToolScheduler(::executeOne, maxParallel)

    fun beginRequestUndoGroup(): WatchAgentUndoGroup? =
        (host as? WatchAgentUndoHost)?.beginUndoGroup()

    suspend fun execute(
        calls: List<WatchAgentToolCall>,
        manageUndoGroup: Boolean = true,
    ): List<WatchAgentToolResult> = withContext(Dispatchers.IO) {
        val undoGroup = if (manageUndoGroup) beginRequestUndoGroup() else null
        try {
            val results = scheduler.execute(calls)
            // Keep mutations that completed before a later stale/invalid call;
            // a failed operation has no right to erase visible live edits.
            val wrote = results.any { result ->
                result.succeeded && WatchAgentToolName.fromWireName(result.name)?.isWrite == true
            }
            if (wrote) undoGroup?.commit() else undoGroup?.rollback()
            results
        } catch (error: Throwable) {
            runCatching { undoGroup?.rollback() }
            throw error
        }
    }

    suspend fun execute(call: WatchAgentToolCall): WatchAgentToolResult = execute(listOf(call)).first()

    private fun executeOne(call: WatchAgentToolCall): WatchAgentToolResult {
        val startedAt = System.currentTimeMillis()
        val result = executeOneUntimed(call)
        val completedAt = System.currentTimeMillis()
        return result.copy(
            startedAt = startedAt,
            completedAt = completedAt,
            durationMs = (completedAt - startedAt).coerceAtLeast(0L),
        )
    }

    private fun executeOneUntimed(call: WatchAgentToolCall): WatchAgentToolResult = try {
        val name = call.toolName
        if (name != null && !currentPermissions().allows(name.access)) {
            return failure(call, "permission_denied", "工作区权限不允许执行此工具")
        }
        when (name) {
            WatchAgentToolName.LIST_WORKSPACE_FILES -> success(call, host.currentRevision,
                requireNotNull(workspaceImages) { "未绑定工作区图片工具" }.listWorkspaceFiles())
            WatchAgentToolName.READ_IMAGE -> success(call, host.currentRevision,
                requireNotNull(workspaceImages) { "未绑定工作区图片工具" }.readImage(call.requiredString("path")))
            WatchAgentToolName.RUN_BASH -> {
                val runner = requireNotNull(shellRunner) { "工作区 Bash 尚未初始化" }
                val command = call.requiredString("command")
                host.prepareBashMirror()
                val bashRevision = host.currentRevision
                val runId = UUID.randomUUID().toString()
                val result = try {
                    runner.run(ShellCommandRequest(
                        workspaceId = workspaceIdProvider(),
                        command = command,
                        timeoutSeconds = (call.arguments["timeout_seconds"] as? Number)?.toLong() ?: 30L,
                        maxOutputBytes = 256 * 1024,
                        runId = runId,
                        watchScope = host.boundWatchScope,
                    ))
                } catch (cancelled: Throwable) {
                    runner.cancel(runId)
                    throw cancelled
                }
                when (result) {
                    is com.flue.launcher.watchface.agent.workspace.ShellRunResult.RootfsNotInstalled ->
                        failure(call, "rootfs_not_installed", "工作区 ${result.workspaceId} 的 Rootfs 未完成安装或状态尚未刷新")
                    is com.flue.launcher.watchface.agent.workspace.ShellRunResult.WorkspaceNotFound ->
                        failure(call, "workspace_not_found", "工作区 ${result.workspaceId} 不存在或未绑定当前表盘")
                    is com.flue.launcher.watchface.agent.workspace.ShellRunResult.RuntimeUnavailable ->
                        failure(call, "runtime_unavailable", "工作区 ${result.workspaceId} 的 Bash 运行时不可用，请检查 Rootfs 自检和 Proot native 文件")
                    is com.flue.launcher.watchface.agent.workspace.ShellRunResult.Completed -> {
                        var faceMirror: Any? = null
                        var faceMirrorSyncError: String? = null
                        runCatching { host.commitFaceMirror(bashRevision) }
                            .onSuccess { faceMirror = it }
                            .onFailure { error ->
                                faceMirrorSyncError = error.message ?: error.javaClass.simpleName
                            }
                        success(call, host.currentRevision, buildMap {
                            put("command", command)
                            put("stdout", result.stdout)
                            put("exit_code", result.exitCode)
                            put("truncated", result.truncated)
                            put("run_id", result.runId)
                            faceMirror?.let { put("face_mirror", it) }
                            faceMirrorSyncError?.let { put("face_mirror_sync_error", it) }
                        })
                    }
                    is com.flue.launcher.watchface.agent.workspace.ShellRunResult.DryRun ->
                        success(call, host.currentRevision, mapOf(
                            "command" to command,
                            "stdout" to "",
                            "dry_run" to true,
                            "working_directory" to result.workingDirectory.toString(),
                        ))
                    else -> success(call, host.currentRevision, mapOf(
                        "command" to command,
                        "stdout" to result.toString(),
                    ))
                }
            }
            WatchAgentToolName.SYNC_FACE_MIRROR -> success(call, host.currentRevision, host.syncFaceMirror())
            WatchAgentToolName.VALIDATE_FACE_MIRROR -> success(call, host.currentRevision, host.validateFaceMirror())
            WatchAgentToolName.COMMIT_FACE_MIRROR -> {
                val revision = call.requiredRevision(); checkExpectedRevision(revision)
                success(call, host.currentRevision, host.commitFaceMirror(revision))
            }
            WatchAgentToolName.IMPORT_FACE_RESOURCE -> success(call, host.currentRevision, host.importFaceResource(call.requiredString("path")))
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
            WatchAgentToolName.WEB_SEARCH -> {
                val client = requireNotNull(webClient) { "网络搜索尚未配置" }
                val result = client.search(
                    WatchAgentWebSearchRequest(
                        query = call.requiredString("query"),
                        maxResults = call.arguments["max_results"]?.toLongValue()?.toInt() ?: webMaxResults,
                        searchDepth = call.arguments["search_depth"]?.toString() ?: webSearchDepth,
                    ),
                )
                success(call, host.currentRevision, result)
            }
            WatchAgentToolName.FETCH_WEB_PAGE -> {
                val client = requireNotNull(webClient) { "网页抓取尚未配置" }
                val result = client.fetchPage(
                    call.requiredString("url"),
                    call.arguments["max_chars"]?.toLongValue()?.toInt() ?: WatchAgentWebResearchClient.DEFAULT_MAX_PAGE_CHARS,
                )
                success(call, host.currentRevision, result)
            }
            WatchAgentToolName.GENERATE_IMAGE -> {
                val client = requireNotNull(imageClient) { "图片生成尚未配置" }
                val options = call.imageOptions(imageDefaults)
                val output = client.generate(call.requiredString("prompt"), options)
                val stored = requireNotNull(workspaceImages) { "未绑定工作区图片工具" }
                    .saveGeneratedImage(output.bytes, output.format, "generate", output.size)
                success(call, host.currentRevision, stored)
            }
            WatchAgentToolName.EDIT_IMAGE -> {
                val client = requireNotNull(imageClient) { "图片修改尚未配置" }
                val images = requireNotNull(workspaceImages) { "未绑定工作区图片工具" }
                val input = images.readImage(call.requiredString("input_path"))
                val mask = call.arguments["mask_path"]?.toString()?.takeIf { it.isNotBlank() }?.let { path ->
                    images.readImage(path)
                }
                val options = call.imageOptions(imageDefaults)
                val output = client.edit(
                    prompt = call.requiredString("prompt"),
                    input = java.util.Base64.getDecoder().decode(input.image.base64),
                    inputName = input.path,
                    mask = mask?.let { java.util.Base64.getDecoder().decode(it.image.base64) },
                    maskName = mask?.path,
                    options = options,
                )
                val stored = images.saveGeneratedImage(output.bytes, output.format, "edit", output.size)
                success(call, host.currentRevision, stored)
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
            WatchAgentToolName.READ_FILE -> {
                val path = call.requiredString("path")
                val resolved = resolvePathRead(path)
                    ?: throw IllegalArgumentException("文件不存在或无法读取：$path")
                val bytes = resolved.bytes
                val isBinary = bytes.any { it == 0.toByte() }
                val content = if (isBinary) null else String(bytes, Charsets.UTF_8)
                success(call, host.currentRevision, buildMap {
                    put("path", resolved.path)
                    put("source", resolved.source)
                    put("byte_count", bytes.size)
                    put("is_binary", isBinary)
                    if (content != null) put("content", content)
                })
            }
            WatchAgentToolName.LIST_ARCHIVE -> {
                val path = call.requiredString("path")
                val bytes = resolvePathBytes(path)
                    ?: throw IllegalArgumentException("压缩包不存在或无法读取：$path")
                val entries = run {
                    val list = mutableListOf<Map<String, Any>>()
                    ZipInputStream(bytes.inputStream()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            list += mapOf(
                                "name" to entry.name,
                                "size" to entry.size,
                                "is_directory" to entry.isDirectory,
                            )
                            zip.closeEntry()
                            if (list.size >= 500) break
                        }
                    }
                    list
                }
                success(call, host.currentRevision, mapOf("path" to path, "entries" to entries, "total" to entries.size))
            }
            WatchAgentToolName.EXTRACT_ARCHIVE -> {
                val path = call.requiredString("path")
                val targetDir = AgentWorkspacePathResolver.canonicalPath(call.arguments["target_dir"]?.toString()?.ifBlank { "workspace" } ?: "workspace")
                val manager = requireNotNull(workspaceManager) { "解压需要已绑定的工作区" }
                val wsId = workspaceIdProvider().takeIf { it.isNotBlank() } ?: throw WatchAgentToolException("workspace_required", "请先绑定工作区")
                val bytes = resolvePathBytes(path)
                    ?: throw IllegalArgumentException("压缩包不存在或无法读取：$path")
                val extractedCount = run {
                    val ws = manager.get(wsId) ?: throw WatchAgentToolException("workspace_not_found", "工作区不存在")
                    val targetRoot = AgentWorkspacePathResolver(manager, ws.id, host.boundWatchScope).resolve(targetDir).file
                    Files.createDirectories(targetRoot)
                    var count = 0
                    ZipInputStream(bytes.inputStream()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val out = WorkspacePathPolicy.resolveInside(targetRoot, entry.name)
                            if (entry.isDirectory) {
                                Files.createDirectories(out)
                            } else {
                                Files.createDirectories(out.parent)
                                val data = zip.readBytesLimited(20 * 1024 * 1024)
                                manager.writeAgentFile(wsId, "$targetDir/${entry.name}", data, host.boundWatchScope)
                                count++
                                require(count <= 2000) { "压缩包文件数量超过 2000" }
                            }
                            zip.closeEntry()
                        }
                    }
                    manager.refreshQuota(wsId)
                    count
                }
                success(call, host.currentRevision, mapOf("path" to path, "target_dir" to targetDir, "extracted_files" to extractedCount))
            }
            WatchAgentToolName.CROP_IMAGE -> {
                val inputPath = call.requiredString("input_path")
                val x = (call.arguments["x"] as? Number)?.toInt() ?: throw IllegalArgumentException("缺少 x")
                val y = (call.arguments["y"] as? Number)?.toInt() ?: throw IllegalArgumentException("缺少 y")
                val width = (call.arguments["width"] as? Number)?.toInt() ?: throw IllegalArgumentException("缺少 width")
                val height = (call.arguments["height"] as? Number)?.toInt() ?: throw IllegalArgumentException("缺少 height")
                require(width > 0 && height > 0) { "width 和 height 必须大于 0" }
                val bytes = resolvePathBytes(inputPath)
                    ?: throw IllegalArgumentException("图片不存在或无法读取：$inputPath")
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalArgumentException("无法解码图片：$inputPath")
                require(x >= 0 && y >= 0 && x + width <= bmp.width && y + height <= bmp.height) {
                    "裁剪区域超出图片尺寸 (图宽: ${bmp.width}, 图高: ${bmp.height})"
                }
                val cropped = android.graphics.Bitmap.createBitmap(bmp, x, y, width, height)
                val bos = java.io.ByteArrayOutputStream()
                cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                val croppedBytes = bos.toByteArray()
                val outputPath = call.arguments["output_path"]?.toString()?.takeIf { it.isNotBlank() }
                val stored = if (outputPath != null && workspaceManager != null) {
                    val wsId = workspaceIdProvider().takeIf { it.isNotBlank() } ?: throw WatchAgentToolException("workspace_required", "请先绑定工作区")
                    val ws = workspaceManager.get(wsId) ?: throw WatchAgentToolException("workspace_not_found", "工作区不存在")
                    workspaceManager.writeAgentFile(wsId, outputPath, croppedBytes, host.boundWatchScope)
                    AgentWorkspacePathResolver.canonicalPath(outputPath)
                } else if (workspaceImages != null) {
                    val saved = workspaceImages.saveGeneratedImage(croppedBytes, "png", "crop", "${width}x${height}")
                    saved.path
                } else {
                    throw WatchAgentToolException("workspace_required", "保存裁剪图片需要工作区")
                }
                success(call, host.currentRevision, mapOf("path" to stored, "width" to width, "height" to height, "byte_count" to croppedBytes.size))
            }
            null -> failure(call, "unknown_tool", "未知工具：${call.name}")
        }
    } catch (error: WatchAgentToolException) {
        failure(call, error.code, error.message)
    } catch (error: IllegalArgumentException) {
        failure(call, "invalid_arguments", error.message ?: "工具参数无效")
    } catch (error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
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

    private data class PathRead(val path: String, val source: String, val bytes: ByteArray)

    private fun resolvePathRead(rawPath: String): PathRead? {
        val canonical = AgentWorkspacePathResolver.canonicalPath(rawPath)
        val manager = workspaceManager
        val id = workspaceIdProvider()
        if (manager != null && id.isNotBlank()) {
            val resolved = AgentWorkspacePathResolver(manager, id, host.boundWatchScope).resolve(canonical)
            if (Files.isRegularFile(resolved.file, LinkOption.NOFOLLOW_LINKS)) {
                val bytes = Files.newInputStream(resolved.file).use { it.readBytesLimited(20 * 1024 * 1024) }
                return PathRead(resolved.canonicalPath, resolved.source, bytes)
            }
        }
        if (canonical.startsWith("face/")) {
            host.readHostResource(canonical.removePrefix("face/"))?.let {
                return PathRead(canonical, "editor_draft", it)
            }
        }
        return null
    }

    private fun resolvePathBytes(rawPath: String): ByteArray? = resolvePathRead(rawPath)?.bytes

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
    WatchAgentDocumentPath.fromWirePath(requiredString(name).removePrefix("face/"))
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

private fun WatchAgentToolCall.imageOptions(defaults: ImageGenerationOptions): ImageGenerationOptions {
    fun text(name: String, fallback: String?): String? = arguments[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    fun enum(name: String, allowed: Set<String>, fallback: String?): String? {
        val value = text(name, fallback) ?: return null
        require(value in allowed) { "$name 参数无效" }
        return value
    }
    fun dimension(name: String, fallback: String?): String? {
        val value = text(name, fallback) ?: return null
        if (value == "auto") return value
        val match = Regex("^(\\d{1,5})x(\\d{1,5})$").matchEntire(value)
            ?: throw IllegalArgumentException("$name 必须是 auto 或正整数宽x高")
        val width = match.groupValues[1].toIntOrNull() ?: 0
        val height = match.groupValues[2].toIntOrNull() ?: 0
        require(width > 0 && height > 0 && width % 16 == 0 && height % 16 == 0) {
            "$name 的宽和高必须是正整数且为 16 的倍数"
        }
        require(width <= 16_384 && height <= 16_384) { "$name 尺寸过大" }
        return value
    }
    val compression = arguments["output_compression"]?.toLongValue()?.toInt()?.also { require(it in 0..100) { "output_compression 必须是 0 到 100" } }
    return ImageGenerationOptions(
        size = dimension("size", defaults.size),
        quality = enum("quality", setOf("auto", "low", "medium", "high", "xhigh", "max"), defaults.quality),
        background = enum("background", setOf("auto", "opaque", "transparent"), defaults.background),
        outputFormat = enum("output_format", setOf("png", "jpeg", "webp"), defaults.outputFormat),
        outputCompression = compression ?: defaults.outputCompression,
        inputFidelity = enum("input_fidelity", setOf("low", "high"), defaults.inputFidelity),
        moderation = defaults.moderation,
    )
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "文件超过 ${limit / 1024 / 1024} MiB 读取限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
