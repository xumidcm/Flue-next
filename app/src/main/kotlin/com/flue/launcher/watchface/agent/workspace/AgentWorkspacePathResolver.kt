package com.flue.launcher.watchface.agent.workspace

import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

data class AgentResolvedPath(val canonicalPath: String, val file: Path, val source: String)
data class AgentWorkspaceListing(val files: List<String>, val warnings: List<String>, val truncated: Boolean)

/** One namespace for text, images, imports, previews and the corresponding Bash mounts. */
class AgentWorkspacePathResolver(
    private val manager: WorkspaceManager,
    private val workspaceId: String,
    private val watchScope: String?,
    private val openDirectory: (Path) -> DirectoryStream<Path> = { Files.newDirectoryStream(it) },
) {
    private val workspace by lazy { requireNotNull(manager.get(workspaceId)) { "工作区不存在" } }
    private val face by lazy { manager.faceMirrorDirectory(workspaceId, watchScope) }

    private fun root(name: String): Path = requireNotNull(when (name) {
        "workspace" -> workspace.paths.workspace
        "generated" -> workspace.paths.generated
        "previews" -> workspace.paths.previews
        "home" -> workspace.paths.home
        "attachments" -> workspace.paths.workspace.resolve("attachments")
        "face" -> face
        else -> null
    }) { "目录不可用：$name" }

    fun resolve(rawPath: String): AgentResolvedPath {
        val clean = WorkspacePathPolicy.sanitizeRelative(rawPath.trim())
        val canonical = canonicalPath(clean)
        val mount = canonical.substringBefore('/')
        val root = root(mount)
        if (Files.isSymbolicLink(root)) throw WorkspacePathViolation("Symbolic mount roots are not allowed")
        val child = canonical.substringAfter('/', "")
        val target = if (child.isEmpty()) root else WorkspacePathPolicy.resolveInside(root, child)
        if (Files.isSymbolicLink(target)) throw WorkspacePathViolation("Symbolic links are not allowed")
        return AgentResolvedPath(canonical, target, if (mount == "face") "face_mirror" else mount)
    }

    fun listFiles(maxFiles: Int = 300, maxDepth: Int = 8): AgentWorkspaceListing {
        require(maxFiles > 0 && maxDepth > 0)
        val files = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        var truncated = false
        fun walk(directory: Path, label: String, depth: Int) {
            if (files.size >= maxFiles) { truncated = true; return }
            if (Files.isSymbolicLink(directory)) { warnings += "$label: symlink_skipped"; return }
            if (!Files.exists(directory, NOFOLLOW_LINKS)) return
            if (depth > maxDepth) { truncated = true; warnings += "$label: depth_limit"; return }
            try {
                openDirectory(directory).use { children ->
                    for (path in children) {
                        if (files.size >= maxFiles) { truncated = true; break }
                        val name = path.fileName.toString()
                        // These are Proot mount points, never another copy of a face.
                        if (label == "workspace" && name in setOf("face", "previews", "generated", "home", "attachments")) continue
                        val childLabel = "$label/$name"
                        try {
                            if (Files.isSymbolicLink(path)) { warnings += "$childLabel: symlink_skipped"; continue }
                            val safe = resolve(childLabel).file
                            if (Files.isDirectory(safe, NOFOLLOW_LINKS)) walk(safe, childLabel, depth + 1)
                            else if (Files.isRegularFile(safe, NOFOLLOW_LINKS)) files += childLabel
                        } catch (failure: Exception) {
                            warnings += "$childLabel: ${failure.javaClass.simpleName}"
                        }
                    }
                }
            } catch (failure: Exception) {
                // A denied mounted subdirectory must not discard unrelated readable files.
                if (failure !is IOException && failure !is SecurityException && failure !is java.nio.file.DirectoryIteratorException) throw failure
                warnings += "$label: ${failure.javaClass.simpleName}"
            }
        }
        for (name in listOf("workspace", "generated", "previews", "home", "attachments", "face")) {
            try {
                walk(root(name), name, 0)
            } catch (failure: Exception) {
                warnings += "$name: ${failure.javaClass.simpleName}"
            }
        }
        return AgentWorkspaceListing(files.sorted(), warnings.distinct().take(100), truncated)
    }

    companion object {
        fun canonicalPath(rawPath: String): String {
            val clean = WorkspacePathPolicy.sanitizeRelative(rawPath.trim())
            val root = clean.substringBefore('/')
            return when (root) {
                "workspace", "generated", "previews", "home", "attachments", "face" -> clean
                "images" -> "face/$clean"
                "linux", "faces" -> throw WorkspacePathViolation("Runtime directories are not exposed")
                else -> "workspace/$clean"
            }
        }
    }
}
