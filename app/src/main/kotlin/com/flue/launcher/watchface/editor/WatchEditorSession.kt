package com.flue.launcher.watchface.editor

import android.content.Context
import android.net.Uri
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.standard.SecureWatchXmlParser
import com.flue.launcher.watchface.standard.WatchInstallMetadata
import com.flue.launcher.watchface.standard.WatchArchiveValidator
import com.flue.launcher.watchface.standard.WatchmakerLayer
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.standard.resolveWatchLayerParentIndices
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A cache-backed, lossless editing session for a standard Watchmaker .watch
 * directory. The installed directory is copied before this object is created;
 * ordinary mutations target only the draft. [saveCurrentRevision] is the
 * explicit, transactional opt-in for updating an existing logical ID.
 */
class WatchEditorSession private constructor(
    val draftDirectory: File,
    private val watchXmlRelativePath: String,
    private val limits: WatchEditorLimits,
    private val removeDraftOnClose: Boolean,
    private var document: Document,
    initialLayerKeys: List<WatchEditorLayerKey>?,
    val logicalId: String? = null,
    private val installedDirectory: File? = null,
) : Closeable {
    private data class EditorState(
        val xml: ByteArray,
        val layerKeys: List<WatchEditorLayerKey>,
        val resourcePaths: Set<String>,
    )

    private val undoStack = ArrayDeque<EditorState>()
    private val redoStack = ArrayDeque<EditorState>()
    private var undoBytes = 0L
    private var redoBytes = 0L
    private val resourceStash = mutableMapOf<String, File>()
    private val historyDirectory = File(
        draftDirectory.parentFile,
        ".${draftDirectory.name}-history",
    )
    private var layerKeys = mutableListOf<WatchEditorLayerKey>()
    private var savedXml: ByteArray
    private var savedResourcePaths: Set<String>
    private var closed = false
    private var activeTransformGesture: TransformGesture? = null

    private data class TransformGesture(
        val key: WatchEditorLayerKey,
        val before: EditorState,
    )

    init {
        rebindLayerKeys(initialLayerKeys)
        validateDocument()
        savedXml = serializeDocument(document)
        savedResourcePaths = resourcePathsInternal().toSet()
    }

    val watchXmlFile: File
        get() = resolveDraftPath(watchXmlRelativePath)

    val isDirty: Boolean
        @Synchronized get() {
            ensureOpen()
            return !serializeDocument(document).contentEquals(savedXml) ||
                resourcePathsInternal().toSet() != savedResourcePaths
        }

    /**
     * A session opened from an installed face can commit a revision back to
     * the same logical directory. Blank/import drafts deliberately return
     * false so the UI presents "另存为" instead of pretending to update an
     * existing face.
     */
    val canSaveCurrentRevision: Boolean
        get() = logicalId != null && installedDirectory != null

    /** Returns a UI-neutral snapshot; DOM nodes never escape the session. */
    @Synchronized
    fun snapshot(): WatchEditorSnapshot {
        ensureOpen()
        val root = document.documentElement
        val layers = topLevelLayerElements().mapIndexed { index, element ->
            layerSnapshot(layerKeys[index], element, index)
        }
        return WatchEditorSnapshot(
            rootName = root.tagName,
            rootAttributes = attributesOf(root),
            layers = layers,
            resourcePaths = resourcePathsInternal().sorted(),
            dirty = isDirty,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
        )
    }

    @Synchronized
    fun updateRootAttributes(changes: Map<String, String?>) {
        mutate {
            val root = document.documentElement
            changes.forEach { (name, value) -> setOrRemoveAttribute(root, name, value) }
        }
    }

    fun updateRootAttribute(name: String, value: String?) =
        updateRootAttributes(mapOf(name to value))

    @Synchronized
    fun updateLayerAttributes(key: WatchEditorLayerKey, changes: Map<String, String?>) {
        mutate {
            val layer = findLayer(key)
            changes.forEach { (name, value) -> setOrRemoveAttribute(layer, name, value) }
        }
    }

    fun updateLayerAttribute(key: WatchEditorLayerKey, name: String, value: String?) =
        updateLayerAttributes(key, mapOf(name to value))

    /**
     * Reads the transform attributes used by the editor canvas. Missing or
     * malformed values intentionally fall back to the neutral transform so a
     * vendor-specific layer can still be selected and edited.
     */
    @Synchronized
    fun layerTransform(key: WatchEditorLayerKey): WatchEditorTransform {
        ensureOpen()
        val layer = findLayer(key)
        val sharedScale = layer.attributeValue("anim_scale")
            ?: layer.attributeValue("scale")
        return WatchEditorTransform(
            x = layer.getAttribute("x").toFloatOrNull() ?: 0f,
            y = layer.getAttribute("y").toFloatOrNull() ?: 0f,
            scaleX = percentScale(
                layer.attributeValue("anim_scale_x")
                    ?: layer.attributeValue("scale_x")
                    ?: sharedScale,
            ),
            scaleY = percentScale(
                layer.attributeValue("anim_scale_y")
                    ?: layer.attributeValue("scale_y")
                    ?: sharedScale,
            ),
            rotation = layer.getAttribute("rotation").toFloatOrNull() ?: 0f,
        )
    }

    /**
     * Starts an in-memory transform transaction. Every MOVE updates the DOM
     * without touching disk or the undo stack; [commitLayerTransformGesture]
     * creates exactly one undo entry when the pointer is released.
     */
    @Synchronized
    fun beginLayerTransformGesture(key: WatchEditorLayerKey): WatchEditorTransform {
        ensureOpen()
        activeTransformGesture?.let { active ->
            if (active.key == key) return layerTransform(key)
            activeTransformGesture = null
            restoreState(active.before)
        }
        val before = captureState()
        val initial = layerTransform(key)
        activeTransformGesture = TransformGesture(key, before)
        return initial
    }

    @Synchronized
    fun updateLayerTransformPreview(
        key: WatchEditorLayerKey,
        transform: WatchEditorTransform,
    ) {
        ensureOpen()
        val active = activeTransformGesture
            ?: throw WatchEditorException("没有活动的图层变换手势")
        require(active.key == key) { "图层变换手势目标已改变" }
        val before = captureState()
        try {
            setLayerTransformDirect(findLayer(key), transform.sanitized())
            validateDocument()
        } catch (error: Exception) {
            restoreState(before)
            throw if (error is WatchEditorException) {
                error
            } else {
                WatchEditorException(error.message ?: "更新图层变换失败", error)
            }
        }
    }

    @Synchronized
    fun commitLayerTransformGesture(key: WatchEditorLayerKey): Boolean {
        ensureOpen()
        val active = activeTransformGesture ?: return false
        require(active.key == key) { "图层变换手势目标已改变" }
        activeTransformGesture = null
        return try {
            val after = captureState()
            if (sameState(active.before, after)) {
                false
            } else {
                pushUndo(active.before)
                clearRedo()
                pruneResourceStash()
                true
            }
        } catch (error: Exception) {
            runCatching { restoreState(active.before) }
            throw if (error is WatchEditorException) {
                error
            } else {
                WatchEditorException(error.message ?: "提交图层变换失败", error)
            }
        }
    }

    @Synchronized
    fun cancelLayerTransformGesture(key: WatchEditorLayerKey): Boolean {
        ensureOpen()
        val active = activeTransformGesture ?: return false
        require(active.key == key) { "图层变换手势目标已改变" }
        activeTransformGesture = null
        restoreState(active.before)
        pruneResourceStash()
        return true
    }

    /**
     * Adds a render Layer while leaving every unknown root child untouched.
     * WatchMaker exporters commonly wrap layers in a `<Layers>` element, so a
     * new layer follows that container instead of changing the document shape.
     */
    @Synchronized
    fun addLayer(
        type: String,
        attributes: Map<String, String> = emptyMap(),
        index: Int = Int.MAX_VALUE,
    ): WatchEditorLayerKey {
        require(type.isNotBlank()) { "layer type must not be blank" }
        return mutate {
            val root = document.documentElement
            val element = document.createElement("Layer")
            element.setAttribute("type", type)
            attributes.forEach { (name, value) -> setOrRemoveAttribute(element, name, value) }
            val existing = topLevelLayerElements()
            val targetIndex = index.coerceIn(0, existing.size)
            insertLayerAtDocumentIndex(
                element = element,
                targetIndex = targetIndex,
                existing = existing,
                emptyContainer = preferredLayerContainer(root),
            )
            val key = WatchEditorLayerKey.create()
            layerKeys.add(targetIndex, key)
            key
        }
    }

    @Synchronized
    fun deleteLayer(key: WatchEditorLayerKey): Boolean {
        val position = layerKeys.indexOf(key)
        if (position < 0) return false
        mutate {
            val element = topLevelLayerElements()[position]
            element.parentNode?.removeChild(element)
            layerKeys.removeAt(position)
            true
        }
        return true
    }

    fun removeLayer(key: WatchEditorLayerKey): Boolean = deleteLayer(key)

    /** Duplicates a layer and all of its unknown descendants. */
    @Synchronized
    fun duplicateLayer(
        key: WatchEditorLayerKey,
        index: Int = Int.MAX_VALUE,
    ): WatchEditorLayerKey {
        return mutate {
            val sourcePosition = layerKeys.indexOf(key)
            require(sourcePosition >= 0) { "unknown layer key: ${key.value}" }
            val source = topLevelLayerElements()[sourcePosition]
            val copy = source.cloneNode(true) as Element
            if (isStudioDefinition() && copy.hasAttribute("id")) {
                copy.setAttribute("id", uniqueLayerId(copy.getAttribute("id"), key))
            }
            val existing = topLevelLayerElements()
            val targetIndex = index.coerceIn(0, existing.size)
            insertLayerAtDocumentIndex(
                element = copy,
                targetIndex = targetIndex,
                existing = existing,
                emptyContainer = preferredLayerContainer(document.documentElement),
            )
            val newKey = WatchEditorLayerKey.create()
            layerKeys.add(targetIndex, newKey)
            newKey
        }
    }

    @Synchronized
    fun reorderLayer(key: WatchEditorLayerKey, targetIndex: Int): Boolean {
        val sourceIndex = layerKeys.indexOf(key)
        if (sourceIndex < 0) return false
        val existingCount = layerKeys.size
        val bounded = targetIndex.coerceIn(0, existingCount - 1)
        if (sourceIndex == bounded) return false
        mutate {
            val element = topLevelLayerElements()[sourceIndex]
            element.parentNode?.removeChild(element)
            val remaining = topLevelLayerElements()
            insertLayerAtDocumentIndex(
                element = element,
                targetIndex = bounded,
                existing = remaining,
                emptyContainer = preferredLayerContainer(document.documentElement),
            )
            val moved = layerKeys.removeAt(sourceIndex)
            layerKeys.add(bounded, moved)
            true
        }
        return true
    }

    fun moveLayer(key: WatchEditorLayerKey, targetIndex: Int): Boolean =
        reorderLayer(key, targetIndex)

    @Synchronized
    fun undo(): Boolean {
        ensureOpen()
        cancelActiveTransformGesture()
        if (undoStack.isEmpty()) return false
        val current = captureState()
        val target = popUndo()
        pushRedo(current)
        restoreState(target)
        pruneResourceStash()
        return true
    }

    @Synchronized
    fun redo(): Boolean {
        ensureOpen()
        cancelActiveTransformGesture()
        if (redoStack.isEmpty()) return false
        val current = captureState()
        val target = popRedo()
        pushUndo(current)
        restoreState(target)
        pruneResourceStash()
        return true
    }

    /**
     * Imports an arbitrary image/resource into the draft. The stream is
     * consumed and closed by this method. The returned path is safe to place
     * in a watch.xml image/source attribute.
     */
    @Synchronized
    fun importResource(
        input: InputStream,
        requestedName: String,
        directory: String = "images",
    ): String {
        ensureOpen()
        val relativePath = uniqueResourcePath(requestedName, directory)
        return mutate {
            val destination = resolveDraftPath(relativePath)
            destination.parentFile?.mkdirs()
            val temporaryDirectory = draftDirectory.parentFile ?: draftDirectory
            val temporary = File.createTempFile(".resource-", ".tmp", temporaryDirectory)
            try {
                input.use { source ->
                    copyBounded(source, temporary, limits.maxFileBytes, remainingBudget())
                }
                atomicMove(temporary, destination)
                relativePath
            } catch (error: Exception) {
                temporary.delete()
                throw WatchEditorException("无法导入资源：${error.message ?: error.javaClass.simpleName}", error)
            } finally {
                temporary.delete()
            }
        }
    }

    fun importImage(context: Context, uri: Uri, requestedName: String? = null): String {
        val name = requestedName?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "image.bin"
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw WatchEditorException("无法读取资源 URI")
        return importResource(stream, name)
    }

    /** Atomically writes only the draft watch.xml; installed files are never touched. */
    @Synchronized
    fun save(): File {
        ensureOpen()
        cancelActiveTransformGesture()
        validateDocument()
        val bytes = serializeDocument(document)
        writeAtomically(watchXmlFile, bytes)
        savedXml = bytes
        savedResourcePaths = resourcePathsInternal().toSet()
        return watchXmlFile
    }

    /**
     * Commits a revision to the installed directory while preserving its
     * logical ID and installation metadata. The draft remains the editing
     * source, so a failed swap cannot leave a half-written watch face.
     */
    @Synchronized
    fun saveCurrentRevision(): File {
        ensureOpen()
        cancelActiveTransformGesture()
        val target = installedDirectory?.canonicalFile
            ?: throw WatchEditorException("当前草稿没有可更新的 logicalId")
        require(target.isDirectory) { "原表盘安装目录不存在" }
        val parent = target.parentFile
            ?: throw WatchEditorException("原表盘安装目录无有效父目录")
        validateDocument()
        val xmlBytes = serializeDocument(document)
        val staged = File(parent, ".${target.name}.revision-${UUID.randomUUID()}")
        val backup = File(parent, ".${target.name}.backup-${UUID.randomUUID()}")
        if (!staged.mkdirs()) throw WatchEditorException("无法创建表盘修订暂存目录")
        var originalMoved = false
        var targetPublished = false
        var backupRestored = false
        try {
            writeAtomically(watchXmlFile, xmlBytes)
            copyTreeBounded(draftDirectory, staged, limits)
            // Installation metadata is intentionally excluded from drafts; it
            // belongs to the logical ID and must survive a revision update.
            val metadata = File(target, ".flue")
            if (metadata.isDirectory) {
                copyTreeBounded(metadata, File(staged, ".flue"), limits)
            }
            writeAtomically(resolveFile(staged, watchXmlRelativePath), xmlBytes)
            atomicMove(target, backup)
            originalMoved = true
            atomicMove(staged, target)
            targetPublished = true
            savedXml = xmlBytes
            savedResourcePaths = resourcePathsInternal().toSet()
            return target
        } catch (error: Exception) {
            if (originalMoved && !targetPublished && !target.exists() && backup.exists()) {
                backupRestored = runCatching {
                    atomicMove(backup, target)
                    true
                }.getOrDefault(false)
            }
            val retainedBackup = backup
                .takeIf {
                    !shouldDeleteWatchRevisionBackup(
                        originalMoved = originalMoved,
                        targetPublished = targetPublished,
                        backupRestored = backupRestored,
                    ) && it.exists()
                }
                ?.absolutePath
            if (error is WatchEditorException && retainedBackup == null) throw error
            throw WatchEditorException(
                buildString {
                    append("保存当前 logicalId 修订失败：")
                    append(error.message ?: error.javaClass.simpleName)
                    retainedBackup?.let {
                        append("；原表盘备份保留在：")
                        append(it)
                    }
                },
                error,
            )
        } finally {
            staged.deleteRecursively()
            if (
                shouldDeleteWatchRevisionBackup(
                    originalMoved = originalMoved,
                    targetPublished = targetPublished,
                    backupRestored = backupRestored,
                )
            ) {
                backup.deleteRecursively()
            }
        }
    }

    /**
     * Writes a preview snapshot without committing the editor history. This is
     * intended for a live preview renderer: callers can discard the session or
     * call [save] later and [isDirty] remains truthful in the meantime.
     */
    @Synchronized
    fun flushDraftForPreview(): File {
        ensureOpen()
        cancelActiveTransformGesture()
        validateDocument()
        writeAtomically(watchXmlFile, serializeDocument(document))
        return watchXmlFile
    }

    /** Saves the current XML and atomically exports the complete draft as .watch. */
    @Synchronized
    fun exportWatch(outputFile: File): WatchEditorExportResult {
        ensureOpen()
        cancelActiveTransformGesture()
        require(!isWithin(outputFile.canonicalFile, draftDirectory.canonicalFile)) {
            "导出文件不能位于编辑草稿目录内"
        }
        validateDocument()
        val xmlBytes = serializeDocument(document)
        writeAtomically(watchXmlFile, xmlBytes)
        val output = outputFile.canonicalFile
        output.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("无法创建导出目录") }
        val temporary = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.tmp")
        var entryCount = 0
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                for (file in draftFiles()) {
                    val relative = relativePath(file)
                    validateRelativePath(relative)
                    val entry = ZipEntry(relative)
                    zip.putNextEntry(entry)
                    FileInputStream(file).use { input -> input.copyTo(zip, DEFAULT_BUFFER_SIZE) }
                    zip.closeEntry()
                    entryCount++
                }
            }
            // Keep editor output on the same trust boundary as imports. If this
            // succeeds, WatchArchiveInstaller can consume the archive directly.
            WatchArchiveValidator().inspect(temporary)
            atomicMove(temporary, output)
            savedXml = xmlBytes
            savedResourcePaths = resourcePathsInternal().toSet()
            return WatchEditorExportResult(output, output.length(), entryCount)
        } catch (error: Exception) {
            temporary.delete()
            throw WatchEditorException("导出 .watch 失败：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            temporary.delete()
        }
    }

    /** Deletes the cache draft immediately. It is safe to call more than once. */
    @Synchronized
    fun discard() {
        if (closed) return
        closed = true
        if (removeDraftOnClose) draftDirectory.deleteRecursively()
        historyDirectory.deleteRecursively()
    }

    override fun close() = discard()

    private fun <T> mutate(block: () -> T): T {
        ensureOpen()
        cancelActiveTransformGesture()
        val before = captureState()
        return try {
            val result = block()
            validateDocument()
            val after = captureState()
            if (!sameState(before, after)) {
                pushUndo(before)
                clearRedo()
                pruneResourceStash()
            }
            result
        } catch (error: Exception) {
            runCatching { restoreState(before) }
            pruneResourceStash()
            if (error is WatchEditorException) throw error
            throw WatchEditorException(error.message ?: "编辑操作失败", error)
        }
    }

    private fun captureState(): EditorState = EditorState(
        xml = serializeDocument(document),
        layerKeys = layerKeys.toList(),
        resourcePaths = resourcePathsInternal().toSet(),
    )

    private fun restoreState(state: EditorState) {
        val currentResources = resourcePathsInternal().toSet()
        (currentResources - state.resourcePaths).forEach { path ->
            val source = resolveDraftPath(path)
            if (source.isFile) {
                if (!historyDirectory.exists() && !historyDirectory.mkdirs()) {
                    throw WatchEditorException("无法创建撤销资源缓存")
                }
                val stash = File(historyDirectory, UUID.randomUUID().toString())
                atomicMove(source, stash)
                resourceStash.remove(path)?.delete()
                resourceStash[path] = stash
            }
        }
        (state.resourcePaths - currentResources).forEach { path ->
            val stash = resourceStash.remove(path)
                ?: throw WatchEditorException("无法恢复撤销资源：$path")
            val destination = resolveDraftPath(path)
            destination.parentFile?.mkdirs()
            atomicMove(stash, destination)
        }
        document = parseXml(ByteArrayInputStream(state.xml), limits)
        rebindLayerKeys(state.layerKeys)
    }

    private fun sameState(first: EditorState, second: EditorState): Boolean =
        first.layerKeys == second.layerKeys &&
            first.resourcePaths == second.resourcePaths &&
            first.xml.contentEquals(second.xml)

    private fun cancelActiveTransformGesture() {
        val active = activeTransformGesture ?: return
        activeTransformGesture = null
        restoreState(active.before)
        pruneResourceStash()
    }

    private fun pushUndo(state: EditorState) {
        undoStack.addLast(state)
        undoBytes += state.xml.size
        trimHistory(undoStack, HistorySide.UNDO)
    }

    private fun pushRedo(state: EditorState) {
        redoStack.addLast(state)
        redoBytes += state.xml.size
        trimHistory(redoStack, HistorySide.REDO)
    }

    private fun popUndo(): EditorState {
        val state = undoStack.removeLast()
        undoBytes -= state.xml.size
        return state
    }

    private fun popRedo(): EditorState {
        val state = redoStack.removeLast()
        redoBytes -= state.xml.size
        return state
    }

    private fun clearRedo() {
        redoStack.clear()
        redoBytes = 0L
    }

    private fun pruneResourceStash() {
        if (resourceStash.isEmpty()) return
        val referenced = buildSet {
            undoStack.forEach { addAll(it.resourcePaths) }
            redoStack.forEach { addAll(it.resourcePaths) }
        }
        resourceStash.entries.removeAll { (path, file) ->
            if (path in referenced) false else {
                file.delete()
                true
            }
        }
        if (resourceStash.isEmpty()) historyDirectory.delete()
    }

    private enum class HistorySide { UNDO, REDO }

    private fun trimHistory(stack: ArrayDeque<EditorState>, side: HistorySide) {
        fun bytes(): Long = if (side == HistorySide.UNDO) undoBytes else redoBytes
        while (stack.size > MAX_HISTORY_STEPS || bytes() > limits.maxHistoryBytes) {
            val removed = stack.removeFirst()
            if (side == HistorySide.UNDO) undoBytes -= removed.xml.size else redoBytes -= removed.xml.size
        }
    }

    private fun rebindLayerKeys(preferred: List<WatchEditorLayerKey>?) {
        val elements = topLevelLayerElements()
        val keys = preferred?.takeIf { it.size == elements.size }?.toMutableList()
            ?: elements.map { WatchEditorLayerKey.create() }.toMutableList()
        layerKeys = keys
    }

    /**
     * Returns editable layers in document order, including layers inside
     * exporter wrapper nodes such as `<Layers>` or vendor folders. Descendant
     * elements inside an actual Layer stay owned by that layer so unknown
     * extension payloads are not flattened by ordinary editor operations.
     */
    private fun topLevelLayerElements(): List<Element> = buildList {
        fun collect(container: Element) {
            container.childNodes.elements().forEach { child ->
                if (child.tagName.equals("Layer", ignoreCase = true)) {
                    add(child)
                } else {
                    collect(child)
                }
            }
        }
        collect(document.documentElement)
    }

    private fun insertLayerAtDocumentIndex(
        element: Element,
        targetIndex: Int,
        existing: List<Element>,
        emptyContainer: Element,
    ) {
        existing.getOrNull(targetIndex)?.let { target ->
            target.parentNode.insertBefore(element, target)
            return
        }
        existing.lastOrNull()?.let { last ->
            last.parentNode.insertBefore(element, last.nextSibling)
            return
        }
        emptyContainer.appendChild(element)
    }

    private fun preferredLayerContainer(root: Element): Element {
        val pending = ArrayDeque<Element>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            if (candidate.tagName.equals("Layers", ignoreCase = true)) return candidate
            if (candidate.tagName.equals("Layer", ignoreCase = true)) continue

            val children = candidate.childNodes.elements().toList()
            for (index in children.indices.reversed()) {
                pending.addFirst(children[index])
            }
        }
        return root
    }

    private fun findLayer(key: WatchEditorLayerKey): Element {
        val index = layerKeys.indexOf(key)
        require(index >= 0) { "unknown layer key: ${key.value}" }
        return topLevelLayerElements()[index]
    }

    private fun setLayerTransformDirect(
        layer: Element,
        transform: WatchEditorTransform,
    ) {
        val safe = transform.sanitized()
        setOrRemoveAttribute(layer, "x", formatTransformNumber(safe.x))
        setOrRemoveAttribute(layer, "y", formatTransformNumber(safe.y))
        setOrRemoveAttribute(
            layer,
            "anim_scale_x",
            formatTransformNumber(safe.scaleX * WATCHMAKER_SCALE_NORMAL),
        )
        setOrRemoveAttribute(
            layer,
            "anim_scale_y",
            formatTransformNumber(safe.scaleY * WATCHMAKER_SCALE_NORMAL),
        )
        setOrRemoveAttribute(layer, "rotation", formatTransformNumber(safe.rotation))
    }

    private fun percentScale(raw: String?): Float =
        ((raw?.toFloatOrNull() ?: WATCHMAKER_SCALE_NORMAL) / WATCHMAKER_SCALE_NORMAL)
            .coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)

    private fun layerSnapshot(key: WatchEditorLayerKey, element: Element, order: Int) =
        WatchEditorLayer(
            key = key,
            id = element.getAttribute("id").takeIf(String::isNotBlank),
            type = element.getAttribute("type").ifBlank { "unknown" },
            parentId = element.getAttribute("parentId").takeIf(String::isNotBlank),
            attributes = attributesOf(element),
            childElementNames = element.childNodes.elements()
                .map { it.tagName }
                .toList(),
            order = order,
        )

    private fun validateDocument() {
        val root = document.documentElement
        require(root.tagName.equals("Watch", ignoreCase = true)) { "watch.xml root must be <Watch>" }
        checkDepth(root, 0)
        val xmlBytes = serializeDocument(document).size.toLong()
        require(xmlBytes <= limits.maxFileBytes) { "watch.xml 超过 ${limits.maxFileBytes} 字节限制" }
        val otherBytes = draftFiles()
            .filterNot { relativePath(it) == watchXmlRelativePath }
            .sumOf { it.length() }
        require(otherBytes + xmlBytes <= limits.maxTotalBytes) {
            "表盘资源总大小超过 ${limits.maxTotalBytes} 字节限制"
        }
        // Real WatchMaker packages can contain duplicate, missing, self, or
        // cyclic parent links. The runtime deterministically promotes invalid
        // links to roots, so the editor preserves the document instead of
        // rejecting the complete face before the user can repair it.
    }

    private fun checkDepth(node: Node, depth: Int) {
        require(depth <= limits.maxXmlDepth) { "watch.xml 嵌套深度超过限制" }
        val children = node.childNodes
        for (index in 0 until children.length) checkDepth(children.item(index), depth + 1)
    }

    private fun isStudioDefinition(): Boolean =
        document.documentElement.getAttribute("build_source").equals("studio", ignoreCase = true)

    private fun uniqueLayerId(base: String, excludedKey: WatchEditorLayerKey): String {
        val elements = topLevelLayerElements()
        val used = elements.withIndex()
            .filter { (index, _) -> layerKeys[index] != excludedKey }
            .mapNotNull { (_, layer) -> layer.getAttribute("id").takeIf(String::isNotBlank) }
            .toHashSet()
        var candidate = "${base}_copy"
        var suffix = 2
        while (!used.add(candidate)) candidate = "${base}_copy${suffix++}"
        return candidate
    }

    private fun uniqueResourcePath(
        requestedName: String,
        directory: String = "images",
    ): String {
        val clean = sanitizeFileName(requestedName)
        var candidate = "$directory/$clean"
        var suffix = 2
        while (resolveDraftPath(candidate).exists() || candidate in resourceStash) {
            val dot = clean.lastIndexOf('.')
            val stem = if (dot > 0) clean.substring(0, dot) else clean
            val extension = if (dot > 0) clean.substring(dot) else ""
            candidate = "$directory/${stem}_$suffix$extension"
            suffix++
        }
        return candidate
    }

    private fun remainingBudget(): Long = limits.maxTotalBytes - draftByteCount()

    private fun draftByteCount(): Long = draftFiles().sumOf { it.length() }

    private fun resourcePathsInternal(): List<String> = draftFiles()
        .map(::relativePath)
        .filterNot { it == watchXmlRelativePath }

    private fun draftFiles(): List<File> {
        val result = mutableListOf<File>()
        collectFiles(draftDirectory, result)
        require(result.sumOf { it.length() } <= limits.maxTotalBytes) {
            "表盘资源总大小超过 ${limits.maxTotalBytes} 字节限制"
        }
        return result
    }

    private fun collectFiles(directory: File, result: MutableList<File>) {
        directory.listFiles().orEmpty().forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) {
                throw WatchEditorException("草稿包含不允许的符号链接：${child.name}")
            }
            val relative = relativePath(child)
            if (WatchInstallMetadata.isReservedArchivePath(relative)) return@forEach
            if (child.isDirectory) collectFiles(child, result)
            else if (child.isFile) {
                validateRelativePath(relative)
                if (child.length() > limits.maxFileBytes) {
                    throw WatchEditorException("文件超过 ${limits.maxFileBytes} 字节限制：$relative")
                }
                result += child
            }
        }
    }

    private fun relativePath(file: File): String = file.canonicalFile
        .relativeTo(draftDirectory.canonicalFile)
        .invariantSeparatorsPath
        .trimStart('/')

    private fun resolveDraftPath(relative: String): File {
        val safe = validateRelativePath(relative)
        val root = draftDirectory.canonicalFile
        val target = File(root, safe).canonicalFile
        require(isWithin(target, root)) { "路径越界：$relative" }
        return target
    }

    private fun validateRelativePath(path: String): String {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.contains('\u0000')) { "非法空路径" }
        require(!normalized.startsWith('/') && !normalized.matches(Regex("^[A-Za-z]:.*"))) {
            "不允许绝对路径：$path"
        }
        val parts = normalized.split('/')
        require(parts.none { it == ".." }) { "不允许父目录路径：$path" }
        return parts.filter { it.isNotBlank() && it != "." }.joinToString("/")
            .also { require(it.isNotBlank()) { "非法空路径" } }
    }

    private fun ensureOpen() {
        check(!closed) { "编辑会话已关闭" }
    }

    companion object {
        private const val DRAFT_DIRECTORY_NAME = "flue-watch-editor"
        private const val WATCHMAKER_SCALE_NORMAL = 100f
        private const val MIN_TRANSFORM_SCALE = 0.05f
        private const val MAX_TRANSFORM_SCALE = 8f

        /** Keeps memory and disk-history bounded while still allowing a useful edit chain. */
        const val MAX_HISTORY_STEPS: Int = 32

        /** Opens an installed standard descriptor by copying it into cache first. */
        fun open(
            context: Context,
            descriptor: LunchWatchFaceDescriptor,
            limits: WatchEditorLimits = WatchEditorLimits.DEFAULT,
        ): WatchEditorSession {
            require(descriptor.isWatchmaker) { "只有标准 .watch 表盘可在编辑器中打开" }
            val source = descriptor.sourceDirPath?.let(::File)
                ?: throw WatchEditorException("表盘没有可编辑的源目录")
            val sourceCanonical = source.canonicalFile
            val installedRoot = WatchmakerWatchFaceStorage.rootDirectory(context).canonicalFile
            require(sourceCanonical.parentFile?.canonicalFile == installedRoot) {
                "表盘源目录不是有效的 Flue 标准表盘安装目录"
            }
            return openInstalled(
                sourceDirectory = sourceCanonical,
                cacheDirectory = File(context.cacheDir, DRAFT_DIRECTORY_NAME),
                limits = limits,
                logicalId = descriptor.id,
                installedDirectory = sourceCanonical,
            )
        }

        /** File-based overload keeps the core testable without a UI or ViewModel. */
        fun openInstalled(
            sourceDirectory: File,
            cacheDirectory: File,
            limits: WatchEditorLimits = WatchEditorLimits.DEFAULT,
            removeDraftOnClose: Boolean = true,
            logicalId: String? = null,
            installedDirectory: File? = null,
        ): WatchEditorSession {
            val source = sourceDirectory.canonicalFile
            require(source.isDirectory) { "表盘源目录不存在" }
            val cache = prepareCacheDirectory(cacheDirectory)
            require(!isWithin(cache, source) && !isWithin(source, cache)) {
                "源目录与草稿目录不能相互嵌套"
            }
            val draft = createDraftDirectory(cache)
            try {
                copyTreeBounded(source, draft, limits)
                val watchXml = findWatchXml(draft)
                    ?: throw WatchEditorException("表盘目录缺少 watch.xml")
                val document = parseXml(resolveFile(draft, watchXml), limits)
                return WatchEditorSession(
                    draftDirectory = draft,
                    watchXmlRelativePath = watchXml,
                    limits = limits,
                    removeDraftOnClose = removeDraftOnClose,
                    document = document,
                    initialLayerKeys = null,
                    logicalId = logicalId,
                    installedDirectory = installedDirectory?.canonicalFile,
                )
            } catch (error: Exception) {
                draft.deleteRecursively()
                if (error is WatchEditorException) throw error
                throw WatchEditorException("无法创建表盘编辑草稿", error)
            }
        }

        fun createBlank(
            context: Context,
            name: String = "New Watch",
            limits: WatchEditorLimits = WatchEditorLimits.DEFAULT,
            roundShape: Boolean = false,
        ): WatchEditorSession =
            createBlank(File(context.cacheDir, DRAFT_DIRECTORY_NAME), name, limits, roundShape = roundShape)

        fun createBlank(
            cacheDirectory: File,
            name: String = "New Watch",
            limits: WatchEditorLimits = WatchEditorLimits.DEFAULT,
            removeDraftOnClose: Boolean = true,
            roundShape: Boolean = false,
        ): WatchEditorSession {
            val cache = prepareCacheDirectory(cacheDirectory)
            val draft = createDraftDirectory(cache)
            val watchXml = "watch.xml"
            try {
                val safeName = name.replace('\u0000', ' ').trim().ifBlank { "New Watch" }
                // shape 曾硬编码 "R"：编辑器建出的每块表盘都被渲染器按
                // definition.shape == ROUND 罩上圆形裁剪 —— 方屏表盘删光元素后
                // 仍有一圈圆形遮罩就是它。现在跟随调用方传入的屏幕形状。
                val shapeCode = if (roundShape) "R" else "S"
                val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Watch name=\"${escapeXmlAttribute(safeName)}\" build_source=\"studio\" shape=\"$shapeCode\"/>"
                writeAtomically(resolveFile(draft, watchXml), xml.toByteArray(StandardCharsets.UTF_8))
                val document = parseXml(resolveFile(draft, watchXml), limits)
                return WatchEditorSession(
                    draftDirectory = draft,
                    watchXmlRelativePath = watchXml,
                    limits = limits,
                    removeDraftOnClose = removeDraftOnClose,
                    document = document,
                    initialLayerKeys = emptyList(),
                    logicalId = null,
                    installedDirectory = null,
                )
            } catch (error: Exception) {
                draft.deleteRecursively()
                if (error is WatchEditorException) throw error
                throw WatchEditorException("无法创建空白表盘草稿", error)
            }
        }

        private fun prepareCacheDirectory(cacheDirectory: File): File {
            val cache = cacheDirectory.canonicalFile
            if (!cache.exists() && !cache.mkdirs()) throw WatchEditorException("无法创建编辑缓存目录")
            require(cache.isDirectory) { "编辑缓存路径不是目录" }
            return cache
        }

        private fun createDraftDirectory(cache: File): File {
            val draft = File(cache, "draft-${UUID.randomUUID()}").canonicalFile
            if (!draft.mkdirs()) throw WatchEditorException("无法创建编辑草稿目录")
            return draft
        }

        private fun findWatchXml(root: File): String? {
            val matches = mutableListOf<String>()
            fun visit(directory: File) {
                directory.listFiles().orEmpty().forEach { file ->
                    if (Files.isSymbolicLink(file.toPath())) {
                        throw WatchEditorException("源表盘包含不允许的符号链接：${file.name}")
                    }
                    if (file.isDirectory) visit(file)
                    else if (file.isFile && file.name.equals("watch.xml", ignoreCase = true)) {
                        matches += file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
                    }
                }
            }
            visit(root)
            require(matches.size <= 1) { "表盘目录包含多个 watch.xml" }
            return matches.singleOrNull()
        }

        private fun copyTreeBounded(source: File, destination: File, limits: WatchEditorLimits) {
            var total = 0L
            val sourceCanonical = source.canonicalFile
            fun copy(directory: File, target: File) {
                directory.listFiles().orEmpty().forEach { child ->
                    if (Files.isSymbolicLink(child.toPath())) {
                        throw WatchEditorException("源表盘包含不允许的符号链接：${child.name}")
                    }
                    val canonical = child.canonicalFile
                    require(isWithin(canonical, sourceCanonical)) { "源表盘路径越界：${child.name}" }
                    val relative = canonical.relativeTo(sourceCanonical).invariantSeparatorsPath
                    if (WatchInstallMetadata.isReservedArchivePath(relative)) return@forEach
                    validateRelativePath(relative)
                    if (child.isDirectory) {
                        val childTarget = resolveFile(destination, relative)
                        if (!childTarget.exists() && !childTarget.mkdirs()) error("无法创建草稿目录")
                        copy(child, childTarget)
                    } else if (child.isFile) {
                        val size = child.length()
                        require(size <= limits.maxFileBytes) {
                            "文件超过 ${limits.maxFileBytes} 字节限制：$relative"
                        }
                        require(total + size <= limits.maxTotalBytes) {
                            "表盘资源总大小超过 ${limits.maxTotalBytes} 字节限制"
                        }
                        val target = resolveFile(destination, relative)
                        target.parentFile?.mkdirs()
                        FileInputStream(child).use { input ->
                            total += copyBounded(
                                input = input,
                                target = target,
                                fileLimit = limits.maxFileBytes,
                                totalBudget = limits.maxTotalBytes - total,
                            )
                        }
                    }
                }
            }
            copy(sourceCanonical, destination)
        }

        private fun parseXml(file: File, limits: WatchEditorLimits): Document =
            FileInputStream(file).use { parseXml(it, limits) }

        private fun parseXml(input: InputStream, limits: WatchEditorLimits): Document {
            val document = SecureWatchXmlParser.parse(input, limits.maxFileBytes) {
                isNamespaceAware = true
                isCoalescing = false
            }
            val root = document.documentElement
                ?: throw WatchEditorException("watch.xml 没有根元素")
            require(root.tagName.equals("Watch", ignoreCase = true)) { "watch.xml root must be <Watch>" }
            checkDepth(root, 0, limits.maxXmlDepth)
            return document
        }

        private fun checkDepth(node: Node, depth: Int, maximum: Int) {
            require(depth <= maximum) { "watch.xml 嵌套深度超过限制" }
            val children = node.childNodes
            for (index in 0 until children.length) checkDepth(children.item(index), depth + 1, maximum)
        }

        private fun resolveFile(root: File, relative: String): File {
            val normalized = validateRelativePath(relative)
            val canonicalRoot = root.canonicalFile
            val result = File(canonicalRoot, normalized).canonicalFile
            require(isWithin(result, canonicalRoot)) { "路径越界：$relative" }
            return result
        }

        private fun writeAtomically(target: File, bytes: ByteArray) {
            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("无法创建目标目录") }
            val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.flush()
                    runCatching { output.fd.sync() }
                }
                atomicMove(temporary, target)
            } finally {
                temporary.delete()
            }
        }

        private fun atomicMove(source: File, target: File) {
            try {
                Files.move(
                    source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun copyBounded(input: InputStream, target: File, fileLimit: Long, totalBudget: Long): Long {
            require(totalBudget > 0) { "草稿资源总大小已达到上限" }
            val limit = minOf(fileLimit, totalBudget)
            var copied = 0L
            BufferedInputStream(input).use { source ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        require(copied <= limit) { "资源超过单文件或总大小限制" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return copied
        }

        private fun sanitizeFileName(requested: String): String {
            val leaf = requested.replace('\\', '/').substringAfterLast('/')
            require(leaf.isNotBlank() && leaf != "." && leaf != "..") { "资源文件名非法" }
            val sanitized = leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.')
            require(sanitized.isNotBlank() && sanitized != "." && sanitized != "..") { "资源文件名非法" }
            return sanitized.take(120)
        }

        private fun escapeXmlAttribute(value: String): String = value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&apos;")

        private fun isWithin(child: File, root: File): Boolean {
            val childPath = child.canonicalPath
            val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
            return childPath == root.canonicalPath || childPath.startsWith(rootPath)
        }

        private fun validateRelativePath(path: String): String {
            val normalized = path.replace('\\', '/')
            require(normalized.isNotBlank() && !normalized.contains('\u0000')) { "非法空路径" }
            require(!normalized.startsWith('/') && !normalized.matches(Regex("^[A-Za-z]:.*"))) {
                "不允许绝对路径：$path"
            }
            val parts = normalized.split('/')
            require(parts.none { it == ".." }) { "不允许父目录路径：$path" }
            return parts.filter { it.isNotBlank() && it != "." }.joinToString("/")
                .also { require(it.isNotBlank()) { "非法空路径" } }
        }
    }
}

internal fun shouldDeleteWatchRevisionBackup(
    originalMoved: Boolean,
    targetPublished: Boolean,
    backupRestored: Boolean,
): Boolean = !originalMoved || targetPublished || backupRestored

data class WatchEditorTransform(
    val x: Float = 0f,
    val y: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
) {
    fun sanitized(): WatchEditorTransform = copy(
        x = if (x.isFinite()) x.coerceIn(-4096f, 4096f) else 0f,
        y = if (y.isFinite()) y.coerceIn(-4096f, 4096f) else 0f,
        scaleX = if (scaleX.isFinite()) scaleX.coerceIn(0.05f, 8f) else 1f,
        scaleY = if (scaleY.isFinite()) scaleY.coerceIn(0.05f, 8f) else 1f,
        rotation = if (rotation.isFinite()) normalizeRotation(rotation) else 0f,
    )

    private fun normalizeRotation(value: Float): Float {
        var normalized = value % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }
}

data class WatchEditorTreeRow(
    val layer: WatchEditorLayer,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

/**
 * Builds a stable parent/children projection from the flat Watchmaker layer
 * order.  The XML order remains authoritative for siblings and unknown
 * elements are untouched; this projection only controls how the editor list
 * presents them.
 */
fun buildWatchEditorTreeRows(
    layers: List<WatchEditorLayer>,
    collapsedKeys: Set<String> = emptySet(),
): List<WatchEditorTreeRow> {
    if (layers.isEmpty()) return emptyList()
    val parentIndices = resolveWatchLayerParentIndices(
        layers.map { layer ->
            WatchmakerLayer(
                id = layer.id,
                type = layer.type,
                parentId = layer.parentId,
                attributes = layer.attributes,
                children = emptyList(),
            )
        },
    )
    val children = Array(layers.size) { mutableListOf<Int>() }
    val roots = mutableListOf<Int>()
    parentIndices.forEachIndexed { index, parentIndex ->
        if (parentIndex == null) {
            roots += index
        } else {
            children[parentIndex] += index
        }
    }
    val result = mutableListOf<WatchEditorTreeRow>()
    val visited = BooleanArray(layers.size)
    data class PendingRow(val index: Int, val depth: Int)
    val pending = ArrayDeque<PendingRow>()
    roots.asReversed().forEach { pending.addLast(PendingRow(it, 0)) }

    while (pending.isNotEmpty()) {
        val (index, depth) = pending.removeLast()
        if (visited[index]) continue
        visited[index] = true
        val layer = layers[index]
        val descendants = children[index]
        val expanded = layer.key.value !in collapsedKeys
        result += WatchEditorTreeRow(
            layer = layer,
            depth = depth,
            hasChildren = descendants.isNotEmpty(),
            expanded = expanded,
        )
        if (expanded) {
            descendants.asReversed().forEach { childIndex ->
                pending.addLast(PendingRow(childIndex, depth + 1))
            }
        }
    }
    return result
}

private fun formatTransformNumber(value: Float): String =
    if (value == 0f) {
        "0"
    } else {
        java.lang.String.format(java.util.Locale.US, "%.4f", value)
            .trimEnd('0')
            .trimEnd('.')
    }

private fun attributesOf(element: Element): Map<String, String> = buildMap {
    val attributes = element.attributes
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        put(attribute.nodeName, attribute.nodeValue.orEmpty())
    }
}

private fun NodeList.elements(): Sequence<Element> = sequence {
    for (index in 0 until length) {
        val node = item(index)
        if (node.nodeType == Node.ELEMENT_NODE) yield(node as Element)
    }
}

private fun Element.attributeValue(name: String): String? =
    getAttribute(name).takeIf(String::isNotBlank)

private fun setOrRemoveAttribute(element: Element, name: String, value: String?) {
    require(name.isNotBlank() && !name.contains(Regex("\\s"))) { "XML 属性名非法" }
    if (value == null) element.removeAttribute(name) else element.setAttribute(name, value)
}

private fun serializeDocument(document: Document): ByteArray {
    val factory = TransformerFactory.newInstance()
    runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
    runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "") }
    val transformer = factory.newTransformer().apply {
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        setOutputProperty(OutputKeys.INDENT, "no")
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
    }
    return ByteArrayOutputStream().use { output ->
        transformer.transform(DOMSource(document), StreamResult(output))
        output.toByteArray()
    }
}

private fun isWithin(child: File, root: File): Boolean {
    val childPath = child.canonicalPath
    val rootCanonical = root.canonicalPath
    val rootPath = rootCanonical.trimEnd(File.separatorChar) + File.separator
    return childPath == rootCanonical || childPath.startsWith(rootPath)
}
