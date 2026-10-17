package com.flue.launcher.watchface.agent.tools

import com.flue.launcher.watchface.agent.provider.AgentImageInput
import com.flue.launcher.watchface.agent.provider.AgentRequestImage
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import com.flue.launcher.watchface.agent.workspace.WorkspacePathPolicy
import com.flue.launcher.watchface.agent.workspace.ImportResult
import com.flue.launcher.watchface.agent.workspace.AgentWorkspacePathResolver
import com.flue.launcher.watchface.agent.workspace.AgentWorkspaceListing
import java.nio.file.Files
import java.util.UUID

data class WatchAgentImageResult(val path: String, val image: AgentRequestImage, val source: String = "workspace")

data class WatchAgentGeneratedImageResult(
    val path: String,
    val image: AgentRequestImage,
    val action: String,
    val format: String,
    val size: String? = null,
    val byteCount: Int = 0,
)

/** Image access needs a binding, but does not require an installed Linux runtime. */
class AgentWorkspaceImageTools(
    private val manager: WorkspaceManager,
    private val watchScope: String,
    private val host: WatchAgentToolHost? = null,
) {
    private fun workspaceOrNull() = manager.bindingFor(watchScope)?.let { manager.get(it.workspaceId) }
    private fun workspace() = workspaceOrNull()
        ?: throw WatchAgentToolException("workspace_required", "请先在对话顶部选择工作区")

    fun listFiles(): List<String> = listWorkspaceFiles().files

    fun listWorkspaceFiles(): AgentWorkspaceListing {
        val workspace = workspaceOrNull()
        if (workspace != null) return AgentWorkspacePathResolver(manager, workspace.id, watchScope).listFiles()
        val inspection = host?.inspectFace(WatchAgentInspectFaceRequest())
        val files = buildList {
            if (inspection != null) {
                add("face/watch.xml")
                if (inspection.hasGlobalScript) add("face/scripts/script.txt")
                inspection.assets.forEach { add("face/${it.path.removePrefix("face/")}") }
            }
        }
        return AgentWorkspaceListing(files.sorted(), emptyList(), false)
    }

    fun readImage(path: String): WatchAgentImageResult {
        val canonical = AgentWorkspacePathResolver.canonicalPath(path)
        require(isImagePath(canonical)) { "路径不是支持的图片：$path" }
        workspaceOrNull()?.let { workspace ->
            val resolved = AgentWorkspacePathResolver(manager, workspace.id, watchScope).resolve(path)
            if (Files.isRegularFile(resolved.file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return WatchAgentImageResult(resolved.canonicalPath, AgentImageInput.fromFile(resolved.file.toFile()), resolved.source)
            }
        }
        // Only an explicit face path (including the documented images/ alias)
        // can use the current editor draft. Never search it by basename.
        if (canonical.startsWith("face/")) {
            host?.readHostResource(canonical.removePrefix("face/"))?.let { bytes ->
                return WatchAgentImageResult(canonical, AgentImageInput.fromBytes(bytes), "editor_draft")
            }
        }
        throw IllegalArgumentException("图片不存在或不属于当前工作区/表盘包：$path")
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
