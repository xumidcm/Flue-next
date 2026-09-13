package com.flue.launcher.watchface.agent.tools

import com.flue.launcher.watchface.agent.provider.AgentImageInput
import com.flue.launcher.watchface.agent.provider.AgentRequestImage
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import com.flue.launcher.watchface.agent.workspace.WorkspacePathPolicy
import com.flue.launcher.watchface.agent.workspace.ImportResult
import java.nio.file.Files
import java.util.UUID

data class WatchAgentImageResult(val path: String, val image: AgentRequestImage)

data class WatchAgentGeneratedImageResult(
    val path: String,
    val image: AgentRequestImage,
    val action: String,
    val format: String,
    val size: String? = null,
    val byteCount: Int = 0,
)

/** Image access needs a binding, but does not require an installed Linux runtime. */
class AgentWorkspaceImageTools(private val manager: WorkspaceManager, private val watchScope: String) {
    private fun workspace() = manager.bindingFor(watchScope)?.let { manager.get(it.workspaceId) }
        ?: throw WatchAgentToolException("workspace_required", "请先在对话顶部选择工作区")

    /** Lists every regular file in the managed workspace and bound face mirror.
     * Image filtering belongs to [readImage]; this browser must also expose
     * watch.xml, scripts, fonts and other text/resources so the model can pick
     * the right follow-up tool.
     */
    fun listFiles(): List<String> {
        val workspace = workspace()
        return buildList {
            for (directory in listOf("workspace", "generated", "previews", "home", "face")) {
                val root = WorkspacePathPolicy.resolveInside(workspace.paths.root, directory)
                if (!Files.isDirectory(root)) continue
                Files.walk(root, 8).use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext() && size < 300) {
                        val path = iterator.next()
                        if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            val relative = workspace.paths.root.relativize(path).toString().replace('\\', '/')
                            runCatching { WorkspacePathPolicy.resolveInside(workspace.paths.root, relative) }.onSuccess { add(relative) }
                        }
                    }
                }
            }
        }.distinct().sorted()
    }

    fun readImage(path: String): WatchAgentImageResult {
        val workspace = workspace()
        val requested = WorkspacePathPolicy.sanitizeRelative(path)
        val candidates = buildList {
            // inspect_face reports archive-relative paths such as images/foo.png;
            // resolve those against the bound face mirror first. Explicit
            // face/ paths remain accepted for list_workspace_files round trips.
            if (!requested.startsWith("face/")) add("face/$requested")
            add(requested)
        }.distinct()
        val resolved = candidates.firstNotNullOfOrNull { candidate ->
            val root = candidate.substringBefore('/')
            val allowedWorkspace = root in setOf("workspace", "generated", "previews", "home")
            val allowedFace = candidate.startsWith("face/")
            if ((!allowedWorkspace && !allowedFace) || !isImagePath(candidate)) return@firstNotNullOfOrNull null
            val file = WorkspacePathPolicy.resolveInside(workspace.paths.root, candidate)
            if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) candidate to file else null
        } ?: throw IllegalArgumentException("图片不存在或不属于当前工作区/表盘包：$path")
        return WatchAgentImageResult(resolved.first, AgentImageInput.fromFile(resolved.second.toFile()))
    }

    /** Stores an API result atomically through WorkspaceManager quota and path checks. */
    fun saveGeneratedImage(bytes: ByteArray, format: String, action: String, size: String? = null): WatchAgentGeneratedImageResult {
        require(bytes.isNotEmpty()) { "图片结果为空" }
        val cleanFormat = format.lowercase().let { if (it in setOf("png", "jpeg", "webp")) it else "png" }
        val extension = if (cleanFormat == "jpeg") "jpg" else cleanFormat
        val workspace = workspace()
        val temporary = Files.createTempFile("flue-agent-image-", ".$extension")
        return try {
            Files.write(temporary, bytes)
            val relative = "generated/agent-${UUID.randomUUID()}.$extension"
            when (manager.importFile(workspace.id, temporary, relative)) {
                ImportResult.IMPORTED -> {
                    val image = AgentImageInput.fromFile(WorkspacePathPolicy.resolveInside(workspace.paths.root, relative).toFile())
                    WatchAgentGeneratedImageResult(relative, image, action, cleanFormat, size, bytes.size)
                }
                ImportResult.QUOTA_EXCEEDED -> throw WatchAgentToolException("quota_exceeded", "生成图片会超过工作区配额")
                ImportResult.SOURCE_NOT_FOUND -> throw WatchAgentToolException("source_not_found", "生成图片临时文件不存在")
                else -> throw WatchAgentToolException("workspace_write_failed", "无法保存生成图片")
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun isImagePath(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif")
    }
}
