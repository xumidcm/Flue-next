package com.flue.launcher.watchface.agent.workspace

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.flue.launcher.watchface.agent.provider.AgentJsonParser
import com.flue.launcher.watchface.agent.provider.AgentJsonValue
import com.flue.launcher.watchface.agent.provider.AgentJsonWriter

enum class ImportResult {
    IMPORTED,
    QUOTA_EXCEEDED,
    SOURCE_NOT_FOUND,
    INVALID_SOURCE,
    IO_ERROR,
}

enum class ExportResult {
    EXPORTED,
    WORKSPACE_NOT_FOUND,
    INVALID_TARGET,
    UNSUPPORTED_SYMLINK,
    IO_ERROR,
}

/** Owns only managed workspace paths; it never invokes a shell or follows external SAF paths. */
class WorkspaceManager private constructor(
    root: Path,
    private val defaultQuotaBytes: Long,
    private val rootfsBackupCleaner: ((Path) -> Unit)?,
) {
    data class DirectoryEntry(val relativePath: String, val name: String, val isDirectory: Boolean, val sizeBytes: Long)
    constructor(
        root: Path,
        defaultQuotaBytes: Long = DEFAULT_QUOTA_BYTES,
    ) : this(root, defaultQuotaBytes, rootfsBackupCleaner = null)

    internal constructor(
        root: Path,
        rootfsBackupCleaner: (Path) -> Unit,
    ) : this(root, DEFAULT_QUOTA_BYTES, rootfsBackupCleaner)

    val runtimeRoot: Path = root.toAbsolutePath().normalize()
    private val workspacesRoot: Path = runtimeRoot.resolve("workspaces")
    private val bindingsFile: Path = runtimeRoot.resolve("bindings.json")

    init {
        require(defaultQuotaBytes >= 0L) { "Default workspace quota cannot be negative" }
        Files.createDirectories(workspacesRoot)
    }

    @Synchronized
    fun create(
        displayName: String,
        quotaBytes: Long = defaultQuotaBytes,
        watchScope: String? = null,
    ): AgentWorkspace {
        require(displayName.isNotBlank()) { "Workspace name cannot be blank" }
        require(quotaBytes >= 0L) { "Workspace quota cannot be negative" }
        val id = UUID.randomUUID().toString()
        val paths = WorkspacePaths(workspacesRoot.resolve(id))
        Files.createDirectories(paths.root)
        listOf(paths.linux, paths.home, paths.workspace, paths.generated, paths.previews, paths.face)
            .forEach(Files::createDirectories)
        val workspace = AgentWorkspace(
            id = id,
            displayName = displayName.trim(),
            watchScope = watchScope?.takeIf { it.isNotBlank() },
            quota = WorkspaceQuota(quotaBytes),
            paths = paths,
        )
        writeMetadata(workspace)
        if (!watchScope.isNullOrBlank()) bind(watchScope, id)
        return get(id) ?: error("Created workspace disappeared")
    }

    fun createWorkspace(displayName: String, quotaBytes: Long = defaultQuotaBytes): AgentWorkspace =
        create(displayName, quotaBytes)

    @Synchronized
    fun rename(workspaceId: String, displayName: String): AgentWorkspace {
        require(displayName.isNotBlank()) { "Workspace name cannot be blank" }
        val current = get(workspaceId) ?: error("Unknown workspace: $workspaceId")
        writeMetadata(current.copy(displayName = displayName.trim()))
        return get(workspaceId) ?: error("Renamed workspace disappeared")
    }

    @Synchronized
    fun delete(workspaceId: String): Boolean {
        val paths = pathsFor(workspaceId) ?: return false
        if (!Files.isDirectory(paths.root)) return false
        val bindings = readBindings().filterValues { it != workspaceId }
        writeBindings(bindings)
        deleteRecursively(paths.root)
        return !Files.exists(paths.root)
    }

    @Synchronized
    fun get(workspaceId: String): AgentWorkspace? {
        val paths = pathsFor(workspaceId) ?: return null
        if (!Files.isRegularFile(paths.metadata)) return null
        return runCatching {
            val workspace = decodeMetadata(String(Files.readAllBytes(paths.metadata), Charsets.UTF_8), paths)
            workspace.copy(
                binding = firstBindingFor(workspace.id),
            )
        }.getOrNull()
    }

    fun workspace(workspaceId: String): AgentWorkspace? = get(workspaceId)

    @Synchronized
    fun list(): List<AgentWorkspace> {
        val result = mutableListOf<AgentWorkspace>()
        Files.list(workspacesRoot).use { stream ->
            stream.filter(Files::isDirectory).forEach { directory ->
                get(directory.fileName.toString())?.let(result::add)
            }
        }
        return result.sortedBy { it.displayName.lowercase() }
    }

    @Synchronized
    fun bind(watchScope: String, workspaceId: String): FaceWorkspaceBinding {
        require(watchScope.isNotBlank()) { "Watch scope cannot be blank" }
        require(get(workspaceId) != null) { "Unknown workspace: $workspaceId" }
        val binding = FaceWorkspaceBinding(watchScope.trim(), workspaceId)
        val bindings = readBindings().toMutableMap()
        bindings[binding.watchScope] = binding.workspaceId
        writeBindings(bindings)
        return binding
    }

    fun bindWatchScope(watchScope: String, workspaceId: String): FaceWorkspaceBinding =
        bind(watchScope, workspaceId)

    @Synchronized
    fun unbind(watchScope: String): Boolean {
        val bindings = readBindings().toMutableMap()
        val removed = bindings.remove(watchScope) != null
        if (removed) writeBindings(bindings)
        return removed
    }

    @Synchronized
    fun bindingFor(watchScope: String): FaceWorkspaceBinding? {
        val workspaceId = readBindings()[watchScope]?.takeIf { it.isNotBlank() } ?: return null
        return if (get(workspaceId) != null) FaceWorkspaceBinding(watchScope, workspaceId) else null
    }

    @Synchronized
    fun capability(workspaceId: String): WorkspaceCapability {
        val workspace = get(workspaceId)
            ?: return WorkspaceCapability(workspaceId, false, false, false, CapabilityReason.WORKSPACE_NOT_FOUND)
        val rootfsInstalled = workspace.rootfs.status == RootfsStatus.INSTALLED
        val reason = when {
            !rootfsInstalled -> CapabilityReason.ROOTFS_NOT_INSTALLED
            !workspace.rootfs.bashAvailable -> CapabilityReason.BASH_NOT_AVAILABLE
            !workspace.rootfs.pythonAvailable -> CapabilityReason.PYTHON_NOT_AVAILABLE
            else -> CapabilityReason.READY
        }
        return WorkspaceCapability(
            workspaceId = workspace.id,
            rootfsInstalled = rootfsInstalled,
            bashAvailable = rootfsInstalled && workspace.rootfs.bashAvailable,
            pythonAvailable = rootfsInstalled && workspace.rootfs.pythonAvailable,
            reason = reason,
            aptAvailable = rootfsInstalled && workspace.rootfs.aptAvailable,
            yumAvailable = rootfsInstalled && workspace.rootfs.yumAvailable,
            pipAvailable = rootfsInstalled && workspace.rootfs.pipAvailable,
        )
    }

    /** Used by the installer adapter only after its own atomic extraction and self-check succeed. */
    @Synchronized
    fun markRootfsInstalled(
        workspaceId: String,
        bashAvailable: Boolean,
        pythonAvailable: Boolean,
        aptAvailable: Boolean = false,
        yumAvailable: Boolean = false,
        pipAvailable: Boolean = false,
        architecture: String? = null,
        version: String? = null,
        archiveSha256: String? = null,
    ): AgentWorkspace {
        val current = get(workspaceId) ?: error("Unknown workspace: $workspaceId")
        val updated = current.copy(
            rootfs = RootfsState(
                status = RootfsStatus.INSTALLED,
                architecture = architecture,
                version = version,
                archiveSha256 = archiveSha256,
                bashAvailable = bashAvailable,
                pythonAvailable = pythonAvailable,
                aptAvailable = aptAvailable,
                yumAvailable = yumAvailable,
                pipAvailable = pipAvailable,
            ),
        )
        writeMetadata(updated)
        return get(workspaceId) ?: updated
    }

    /**
     * Commits a fully extracted and checked Rootfs from a sibling staging directory.
     * The previous Rootfs stays restorable until both the directory swap and metadata
     * update have completed.
     */
    @Synchronized
    fun replaceRootfs(
        workspaceId: String,
        stagingRootfs: Path,
        bashAvailable: Boolean,
        pythonAvailable: Boolean,
        aptAvailable: Boolean = false,
        yumAvailable: Boolean = false,
        pipAvailable: Boolean = false,
        architecture: String,
        version: String,
        archiveSha256: String,
    ): AgentWorkspace {
        val current = get(workspaceId) ?: error("Unknown workspace: $workspaceId")
        val workspaceRoot = current.paths.root.toAbsolutePath().normalize()
        val staging = stagingRootfs.toAbsolutePath().normalize()
        require(staging.parent == workspaceRoot && staging.fileName.toString().startsWith(".rootfs-install-")) {
            "Rootfs staging directory is not owned by this workspace"
        }
        require(Files.isDirectory(staging)) { "Rootfs staging directory does not exist" }

        val installed = current.paths.linux.toAbsolutePath().normalize()
        val backup = workspaceRoot.resolve(".linux-backup-${UUID.randomUUID()}")
        var previousMoved = false
        var candidateMoved = false
        val updated = try {
            if (Files.exists(installed)) {
                moveAtomically(installed, backup)
                previousMoved = true
            }
            moveAtomically(staging, installed)
            candidateMoved = true
            val updated = current.copy(
                rootfs = RootfsState(
                    status = RootfsStatus.INSTALLED,
                    architecture = architecture,
                    version = version,
                    archiveSha256 = archiveSha256,
                    bashAvailable = bashAvailable,
                    pythonAvailable = pythonAvailable,
                    aptAvailable = aptAvailable,
                    yumAvailable = yumAvailable,
                    pipAvailable = pipAvailable,
                ),
            )
            writeMetadata(updated)
            updated
        } catch (failure: Throwable) {
            if (candidateMoved && Files.exists(installed)) deleteRecursively(installed)
            if (previousMoved && Files.exists(backup)) moveAtomically(backup, installed)
            throw failure
        }

        // Metadata publication is the commit point. Once it succeeds, cleanup is
        // best-effort and must never turn a valid new Rootfs into a partial rollback.
        if (previousMoved) {
            try {
                rootfsBackupCleaner?.invoke(backup) ?: deleteRecursively(backup)
            } catch (_: IOException) {
                // A later install or workspace deletion can reclaim the orphaned backup.
            }
        }
        return get(workspaceId) ?: updated
    }

    @Synchronized
    fun refreshQuota(workspaceId: String): WorkspaceQuota {
        val workspace = get(workspaceId) ?: error("Unknown workspace: $workspaceId")
        val quota = workspace.quota.withUsage(calculateUsage(workspace.paths.root))
        writeMetadata(workspace.copy(quota = quota))
        return quota
    }

    /** Lists one managed workspace directory without exposing metadata or Rootfs. */
    @Synchronized
    fun listDirectory(workspaceId: String, relativeDirectory: String = "workspace"): List<DirectoryEntry> {
        val workspace = get(workspaceId) ?: return emptyList()
        val clean = relativeDirectory.trim().replace('\\', '/').trim('/')
        val directory = if (clean.isEmpty()) {
            workspace.paths.root
        } else {
            val sanitized = WorkspacePathPolicy.sanitizeRelative(clean)
            WorkspacePathPolicy.resolveInside(workspace.paths.root, sanitized)
        }
        if (!Files.isDirectory(directory)) return emptyList()
        Files.list(directory).use { stream ->
            return stream.filter { path ->
                path.fileName.toString() != "workspace.json" && !path.fileName.toString().startsWith(".linux-backup-")
            }.map { path ->
                val childRelative = if (path.startsWith(workspace.paths.workspace)) {
                    workspace.paths.workspace.relativize(path).toString().replace('\\', '/')
                } else {
                    workspace.paths.root.relativize(path).toString().replace('\\', '/')
                }
                DirectoryEntry(childRelative, path.fileName.toString(), Files.isDirectory(path), if (Files.isRegularFile(path)) runCatching { Files.size(path) }.getOrDefault(0L) else 0L)
            }.sorted(compareBy<DirectoryEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }).toList()
        }
    }

    /** Deletes a file or directory inside manageable workspace paths, never protected runtime paths. */
    @Synchronized
    fun deleteWorkspaceEntry(workspaceId: String, relativePath: String): Boolean {
        val workspace = get(workspaceId) ?: return false
        val clean = WorkspacePathPolicy.sanitizeRelative(relativePath)
        val rootName = clean.substringBefore('/')
        require(rootName in setOf("workspace", "generated", "previews", "home", "faces") && clean != rootName) { "只能删除工作区可变目录内的文件" }
        val target = WorkspacePathPolicy.resolveInside(workspace.paths.root, clean)
        require(Files.exists(target)) { "Invalid workspace entry" }
        require(!Files.isSymbolicLink(target)) { "Symbolic links cannot be deleted" }
        deleteRecursively(target)
        refreshQuota(workspaceId)
        return !Files.exists(target)
    }

    @Synchronized
    fun importFile(workspaceId: String, source: Path, targetRelativePath: String): ImportResult {
        val workspace = get(workspaceId) ?: return ImportResult.INVALID_SOURCE
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) return ImportResult.SOURCE_NOT_FOUND
        val target = WorkspacePathPolicy.resolveInside(workspace.paths.root, targetRelativePath)
        val oldSize = if (Files.isRegularFile(target)) Files.size(target) else 0L
        val newSize = Files.size(source)
        val usage = calculateUsage(workspace.paths.root)
        val projected = usage - oldSize + newSize
        if (projected > workspace.quota.limitBytes) return ImportResult.QUOTA_EXCEEDED
        return try {
            Files.createDirectories(target.parent)
            val staging = target.parent.resolve(".${target.fileName}.import-${UUID.randomUUID()}.tmp")
            Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING)
            moveAtomically(staging, target)
            refreshQuota(workspaceId)
            ImportResult.IMPORTED
        } catch (_: WorkspacePathViolation) {
            throw WorkspacePathViolation("Import target escapes workspace")
        } catch (_: IOException) {
            ImportResult.IO_ERROR
        }
    }

    fun importWorkspaceFile(workspaceId: String, source: Path, targetRelativePath: String): ImportResult =
        importFile(workspaceId, source, targetRelativePath)

    @Synchronized
    fun exportWorkspace(workspaceId: String, targetArchive: Path): ExportResult {
        val workspace = get(workspaceId) ?: return ExportResult.WORKSPACE_NOT_FOUND
        val target = targetArchive.toAbsolutePath().normalize()
        if (target.startsWith(workspace.paths.root.toAbsolutePath().normalize())) return ExportResult.INVALID_TARGET
        return try {
            Files.createDirectories(target.parent)
            val staging = target.resolveSibling(".${target.fileName}.export-${UUID.randomUUID()}.tmp")
            ZipOutputStream(Files.newOutputStream(staging)).use { zip ->
                Files.walk(workspace.paths.root).use { stream ->
                    stream.filter { it != workspace.paths.root }.forEach { path ->
                        if (Files.isSymbolicLink(path)) throw SymlinkExportException()
                        val entryName = workspace.paths.root.relativize(path).toString().replace('\\', '/')
                        if (Files.isDirectory(path)) zip.putNextEntry(ZipEntry("$entryName/"))
                        else {
                            zip.putNextEntry(ZipEntry(entryName))
                            Files.newInputStream(path).use { it.copyTo(zip) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            moveAtomically(staging, target)
            ExportResult.EXPORTED
        } catch (_: SymlinkExportException) {
            ExportResult.UNSUPPORTED_SYMLINK
        } catch (_: IOException) {
            ExportResult.IO_ERROR
        }
    }

    fun exportWorkspaceArchive(workspaceId: String, targetArchive: Path): ExportResult =
        exportWorkspace(workspaceId, targetArchive)

    /** Returns the isolated mirror directory for the face bound to this workspace. */
    @Synchronized
    fun faceMirrorDirectory(workspaceId: String, watchScope: String? = null): Path? {
        val workspace = get(workspaceId) ?: return null
        val target = if (!watchScope.isNullOrBlank()) {
            // A digest prevents scopes such as face:a and face/a sharing one mirror.
            val safeScope = UUID.nameUUIDFromBytes(watchScope.toByteArray(Charsets.UTF_8)).toString()
            val scoped = WorkspacePathPolicy.resolveInside(workspace.paths.root, "faces/$safeScope")
            if (!Files.exists(scoped)) {
                Files.createDirectories(scoped)
                // Only migrate a legacy mirror whose owner is known. Other scopes
                // are rebuilt from their own editor candidate, never from this face.
                if (workspace.watchScope == watchScope) {
                    val oldName = watchScope.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val oldScoped = WorkspacePathPolicy.resolveInside(workspace.paths.root, "faces/$oldName")
                    val legacy = if (Files.isDirectory(oldScoped)) oldScoped else workspace.paths.face
                    if (Files.isDirectory(legacy) && !Files.isSymbolicLink(legacy)) {
                        Files.walk(legacy).use { stream ->
                            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { source ->
                                val relative = legacy.relativize(source).toString()
                                val checkedSource = WorkspacePathPolicy.resolveInside(legacy, relative)
                                val targetFile = WorkspacePathPolicy.resolveInside(scoped, relative)
                                Files.createDirectories(targetFile.parent)
                                Files.copy(checkedSource, targetFile, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
            }
            scoped
        } else {
            workspace.paths.face.also { Files.createDirectories(it) }
        }
        return target
    }

    data class FaceMirrorSnapshot(
        val watchXml: String,
        val globalScript: String,
        val resources: List<String>,
    )

    /** Atomically refreshes the face mirror documents and resources used by Bash tooling. */
    @Synchronized
    fun syncFaceMirror(
        workspaceId: String,
        watchXml: String,
        globalScript: String,
        resources: Map<String, ByteArray> = emptyMap(),
        watchScope: String? = null,
    ): Boolean {
        val face = faceMirrorDirectory(workspaceId, watchScope) ?: return false
        require(watchXml.toByteArray(Charsets.UTF_8).size <= MAX_FACE_MIRROR_FILE_BYTES) {
            "watch.xml exceeds mirror file limit"
        }
        require(globalScript.toByteArray(Charsets.UTF_8).size <= MAX_FACE_MIRROR_FILE_BYTES) {
            "script.txt exceeds mirror file limit"
        }
        writeAtomically(face.resolve("watch.xml"), watchXml)
        val scripts = face.resolve("scripts"); Files.createDirectories(scripts)
        writeAtomically(scripts.resolve("script.txt"), globalScript)
        resources.forEach { (relativePath, bytes) ->
            val clean = WorkspacePathPolicy.sanitizeRelative(relativePath)
            require(isAllowedFaceResource(clean)) {
                "Face resource path is not allowed: $relativePath"
            }
            require(bytes.size <= MAX_FACE_MIRROR_FILE_BYTES) { "Face resource exceeds mirror file limit: $relativePath" }
            val target = WorkspacePathPolicy.resolveInside(face, clean)
            Files.createDirectories(target.parent)
            writeAtomically(target, bytes)
        }
        refreshQuota(workspaceId)
        return true
    }

    @Synchronized
    fun readFaceMirror(workspaceId: String, watchScope: String? = null): FaceMirrorSnapshot? {
        val face = faceMirrorDirectory(workspaceId, watchScope) ?: return null
        val xml = face.resolve("watch.xml")
        val script = face.resolve("scripts/script.txt")
        val watchXml = if (Files.isRegularFile(xml, LinkOption.NOFOLLOW_LINKS)) String(Files.readAllBytes(xml), Charsets.UTF_8) else ""
        val globalScript = if (Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)) String(Files.readAllBytes(script), Charsets.UTF_8) else ""
        val resources = mutableListOf<String>()
        Files.walk(face).use { stream ->
            stream.filter { it != face && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .forEach { path ->
                    val relative = face.relativize(path).toString().replace('\\', '/')
                    if (relative != "watch.xml" && relative != "scripts/script.txt") resources += relative
                }
        }
        return FaceMirrorSnapshot(watchXml, globalScript, resources.sorted())
    }

    /** Resolves only explicitly managed workspace directories for Bash/import tools. */
    @Synchronized
    fun resolveWorkspaceFile(workspaceId: String, relativePath: String, watchScope: String? = null): Path? {
        val workspace = get(workspaceId) ?: return null
        return AgentWorkspacePathResolver(this, workspaceId, watchScope ?: workspace.watchScope)
            .resolve(relativePath).file
    }

    @Synchronized
    fun writeAgentFile(workspaceId: String, relativePath: String, bytes: ByteArray, watchScope: String?): String {
        val workspace = requireNotNull(get(workspaceId)) { "工作区不存在" }
        val resolved = AgentWorkspacePathResolver(this, workspaceId, watchScope).resolve(relativePath)
        val oldSize = if (Files.isRegularFile(resolved.file, LinkOption.NOFOLLOW_LINKS)) Files.size(resolved.file) else 0L
        require(calculateUsage(workspace.paths.root) - oldSize <= workspace.quota.limitBytes - bytes.size) { "工作区配额不足" }
        Files.createDirectories(resolved.file.parent)
        writeAtomically(resolved.file, bytes)
        refreshQuota(workspaceId)
        return resolved.canonicalPath
    }

    /** Imports an archive into a new UUID after validating every entry as a relative path. */
    @Synchronized
    fun importWorkspaceArchive(
        sourceArchive: Path,
        displayName: String,
        quotaBytes: Long = defaultQuotaBytes,
    ): AgentWorkspace {
        require(Files.isRegularFile(sourceArchive)) { "Workspace archive not found" }
        val staging = runtimeRoot.resolve(".archive-import-${UUID.randomUUID()}")
        Files.createDirectories(staging)
        return try {
            var extractedBytes = 0L
            ZipInputStream(Files.newInputStream(sourceArchive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = WorkspacePathPolicy.resolveInside(staging, entry.name)
                    if (entry.isDirectory) Files.createDirectories(target)
                    else {
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                extractedBytes += count
                                require(extractedBytes <= quotaBytes) { "Workspace archive exceeds quota" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val workspace = create(displayName, quotaBytes)
            Files.walk(staging).use { stream ->
                stream.filter { it != staging }.forEach { path ->
                    val destination = WorkspacePathPolicy.resolveInside(
                        workspace.paths.root,
                        staging.relativize(path).toString(),
                    )
                    if (Files.isDirectory(path)) Files.createDirectories(destination)
                    else {
                        Files.createDirectories(destination.parent)
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            refreshQuota(workspace.id)
            get(workspace.id) ?: workspace
        } finally {
            deleteRecursively(staging)
        }
    }

    private fun pathsFor(workspaceId: String): WorkspacePaths? {
        if (!Regex("^[0-9a-fA-F-]{36}$").matches(workspaceId)) return null
        val path = workspacesRoot.resolve(workspaceId).normalize()
        if (!path.startsWith(workspacesRoot)) return null
        return WorkspacePaths(path)
    }

    private fun writeMetadata(workspace: AgentWorkspace) {
        val rootfs = mapOf(
            "status" to workspace.rootfs.status.name,
            "architecture" to workspace.rootfs.architecture,
            "version" to workspace.rootfs.version,
            "archiveSha256" to workspace.rootfs.archiveSha256,
            "bashAvailable" to workspace.rootfs.bashAvailable,
            "pythonAvailable" to workspace.rootfs.pythonAvailable,
            "aptAvailable" to workspace.rootfs.aptAvailable,
            "yumAvailable" to workspace.rootfs.yumAvailable,
            "pipAvailable" to workspace.rootfs.pipAvailable,
        )
        val json = mapOf(
            "id" to workspace.id,
            "displayName" to workspace.displayName,
            "watchScope" to workspace.watchScope,
            "rootfs" to rootfs,
            "quotaLimitBytes" to workspace.quota.limitBytes,
            "quotaUsedBytes" to workspace.quota.usedBytes,
        )
        writeAtomically(workspace.paths.metadata, AgentJsonWriter.objectValue(json))
    }

    private fun decodeMetadata(content: String, paths: WorkspacePaths): AgentWorkspace {
        val json = AgentJsonParser.parse(content) as? AgentJsonValue.Obj ?: error("Malformed workspace metadata")
        val rootfs = json.obj("rootfs")
        val status = runCatching { RootfsStatus.valueOf(rootfs?.string("status") ?: RootfsStatus.NOT_INSTALLED.name) }
            .getOrDefault(RootfsStatus.NOT_INSTALLED)
        return AgentWorkspace(
            id = json.string("id") ?: error("Workspace metadata missing id"),
            displayName = json.string("displayName") ?: json.string("id").orEmpty(),
            watchScope = json.string("watchScope"),
            rootfs = RootfsState(
                status = status,
                architecture = rootfs?.string("architecture"),
                version = rootfs?.string("version"),
                archiveSha256 = rootfs?.string("archiveSha256"),
                bashAvailable = rootfs?.bool("bashAvailable") ?: false,
                pythonAvailable = rootfs?.bool("pythonAvailable") ?: false,
                aptAvailable = rootfs?.bool("aptAvailable") ?: false,
                yumAvailable = rootfs?.bool("yumAvailable") ?: false,
                pipAvailable = rootfs?.bool("pipAvailable") ?: false,
            ),
            quota = WorkspaceQuota(
                limitBytes = json.long("quotaLimitBytes") ?: defaultQuotaBytes,
                usedBytes = json.long("quotaUsedBytes") ?: 0L,
            ),
            paths = paths,
        )
    }

    private fun readBindings(): Map<String, String> = if (!Files.isRegularFile(bindingsFile)) emptyMap() else runCatching {
        val root = AgentJsonParser.parse(String(Files.readAllBytes(bindingsFile), Charsets.UTF_8)) as? AgentJsonValue.Obj
            ?: return@runCatching emptyMap()
        root.fields.mapNotNull { (key, value) -> (value as? AgentJsonValue.Str)?.value?.let { key to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeBindings(bindings: Map<String, String>) = writeAtomically(bindingsFile, AgentJsonWriter.objectValue(bindings))

    private fun firstBindingFor(workspaceId: String): FaceWorkspaceBinding? {
        val bindings = readBindings()
        bindings.entries.firstOrNull { it.value == workspaceId }?.let { return FaceWorkspaceBinding(it.key, workspaceId) }
        return null
    }

    private fun calculateUsage(root: Path): Long {
        if (!Files.exists(root)) return 0L
        return runCatching {
            Files.walk(root).use { stream ->
                stream.filter { path ->
                    runCatching { Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false) &&
                        path.fileName.toString() != "workspace.json"
                }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
            }
        }.getOrDefault(0L)
    }

    private fun writeAtomically(target: Path, content: String) {
        writeAtomically(target, content.toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val staging = target.resolveSibling(".${target.fileName}.tmp-${UUID.randomUUID()}")
        Files.write(staging, bytes)
        moveAtomically(staging, target)
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private class SymlinkExportException : IOException()

    companion object {
        /** Workspaces are storage-managed; the app no longer imposes an artificial quota. */
        const val DEFAULT_QUOTA_BYTES: Long = Long.MAX_VALUE
        private const val MAX_FACE_MIRROR_FILE_BYTES: Int = 20 * 1024 * 1024
        private val FACE_RESOURCE_PREFIXES = listOf(
            "images/", "fonts/", "fonts_bm/", "videos/", "sfx/", "sounds/",
            "audio/", "shaders/", "scripts/", "assets/",
        )
        private val FACE_ROOT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif")
        private val ALLOWED_WORKSPACE_MOUNTS = setOf("workspace", "generated", "previews", "home", "face")

        private fun isAllowedFaceResource(path: String): Boolean {
            if (FACE_RESOURCE_PREFIXES.any { path.startsWith(it) }) return true
            // Legacy .watch archives may keep bitmap assets beside watch.xml.
            // Allow those files while refusing arbitrary root-level metadata.
            return '/' !in path && path.substringAfterLast('.', "").lowercase() in FACE_ROOT_IMAGE_EXTENSIONS
        }
    }
}
