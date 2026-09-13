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

    fun listFiles(): List<String> {
        val workspace = workspace()
        return buildList {
            // `face/images` is the read-only view of the currently bound watch face;
            // all other mounts remain scoped to this workspace.
            for (directory in listOf("workspace", "generated", "previews", "home", "face/images")) {
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
        }
    }

    fun readImage(path: String): WatchAgentImageResult {
        val workspace = workspace()
        val relative = WorkspacePathPolicy.sanitizeRelative(path)
        require(relative.substringBefore('/') in setOf("workspace", "generated", "previews", "home") || relative.startsWith("face/images/")) { "只能读取当前工作区或绑定表盘素材目录" }
        val file = WorkspacePathPolicy.resolveInside(workspace.paths.root, relative).toFile()
        return WatchAgentImageResult(relative, AgentImageInput.fromFile(file))
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
}
