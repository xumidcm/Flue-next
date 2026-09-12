package com.flue.launcher.watchface.editor

import android.content.Context
import android.net.Uri
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.standard.SecureWatchXmlParser
import com.flue.launcher.watchface.standard.WatchInstallMetadata
import com.flue.launcher.watchface.standard.WatchArchiveValidator
import com.flue.launcher.watchface.standard.WatchmakerLayer
import com.flue.launcher.watchface.standard.WatchmakerProtection
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.standard.normalizeWatchColorLiteral
import com.flue.launcher.watchface.standard.resolveWatchLayerParentIndices
import com.flue.launcher.watchface.standard.WatchTapAction
import com.flue.launcher.watchface.standard.WatchTapActionParser
import com.flue.launcher.watchface.standard.watchSoundAssetCandidates
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
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.min
import kotlin.math.roundToInt

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
        val scriptBytes: ByteArray?,
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
    private var savedScriptBytes: ByteArray?
    private var closed = false
    /** Monotonic content revision consumed by the Agent document adapter. */
    private var agentRevisionCounter = 1L
    private var activeTransformGesture: TransformGesture? = null
    private var activeTranslationGesture: TranslationGesture? = null

    private data class TranslationTarget(
        val key: WatchEditorLayerKey,
        val initial: WatchEditorTransform,
        val x: String?,
        val y: String?,
        val scaleX: String?,
        val scaleY: String?,
        val scaleXBound: Boolean,
        val scaleYBound: Boolean,
        val inverseParent: WatchEditorAffine,
    )

    private data class TranslationGesture(
        val before: EditorState,
        val targets: List<TranslationTarget>,
        val ambient: Boolean,
    )

    private data class TransformGesture(
        val key: WatchEditorLayerKey,
        val before: EditorState,
    )

    init {
        rebindLayerKeys(initialLayerKeys)
        validateDocument()
        savedXml = serializeDocument(document)
        savedResourcePaths = resourcePathsInternal().toSet()
        savedScriptBytes = scriptBytesInternal()
    }

    val watchXmlFile: File
        get() = resolveDraftPath(watchXmlRelativePath)

    val isDirty: Boolean
        @Synchronized get() {
            ensureOpen()
            return !serializeDocument(document).contentEquals(savedXml) ||
                resourcePathsInternal().toSet() != savedResourcePaths ||
                !sameNullableBytes(scriptBytesInternal(), savedScriptBytes)
        }

    /**
     * A session opened from an installed face can commit a revision back to
     * the same logical directory. Blank/import drafts deliberately return
     * false so the UI presents "另存为" instead of pretending to update an
     * existing face.
     */
    val canSaveCurrentRevision: Boolean
        get() = logicalId != null && installedDirectory != null

    /** Whether the current draft originated from a WatchMaker protected face. */
    val isProtected: Boolean
        @Synchronized get() {
            ensureOpen()
            return document.documentElement.attributes.let { attributes ->
                (0 until attributes.length).any { index ->
                    val attribute = attributes.item(index)
                    attribute.nodeName.equals("protection", ignoreCase = true) &&
                        attribute.nodeValue.trim().lowercase(Locale.ROOT) in
                        setOf("y", "yes", "true", "1")
                }
            }
        }

    /** Returns a UI-neutral snapshot; DOM nodes never escape the session. */
    @Synchronized
    fun snapshot(): WatchEditorSnapshot {
        ensureOpen()
        val root = document.documentElement
        // 两个序号并存，因为两边的消费者要的东西不一样：
        //  * order          —— 顶层图层在**编辑器列表**里的行号（图层面板、拖拽排序用）；
        //  * definitionIndex —— 同一个图层在**渲染器** definition.layers 里的下标。
        // 渲染器的解析器（WatchmakerDefinitionParser.collectLayers）是前序递归、把
        // 嵌套在分组里的 <Layer> 也收进同一张扁平表，而 topLevelLayerElements() 碰到
        // <Layer> 就停手不下钻。所以表盘里只要有一个带子图层的分组，两个序号就开始错位。
        // 把这个换算做在这里、只做一次：实时变换与序列帧预览都按 definitionIndex 找层，
        // 否则「拖文字只有选中框跟手、像素不动」（文字层通常没有 id，只能按下标匹配）。
        var definitionIndex = 0
        val layers = topLevelLayerElements().mapIndexed { index, element ->
            layerSnapshot(layerKeys[index], element, index, definitionIndex).also {
                definitionIndex += 1 + element.nestedLayerCount()
            }
        }
        return WatchEditorSnapshot(
            rootName = root.tagName,
            rootAttributes = attributesOf(root),
            layers = layers,
            resourcePaths = resourcePathsInternal().sorted(),
            dirty = isDirty,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            globalScriptRevision = scriptRevision(scriptBytesInternal()),
        )
    }

    /**
     * Returns the smallest session-owned candidate needed by the Agent runner.
     * The runner still needs a host adapter for writes and native previews; this
     * method deliberately does not expose DOM nodes or invent a renderer.
     */
    @Synchronized
    fun agentCandidate(): WatchEditorAgentCandidate {
        ensureOpen()
        return WatchEditorAgentCandidate(
            revision = agentRevisionCounter,
            watchXml = serializeDocument(document).toString(StandardCharsets.UTF_8),
            globalScript = scriptBytesInternal()?.toString(StandardCharsets.UTF_8).orEmpty(),
            resourcePaths = resourcePathsInternal().sorted(),
            dirty = isDirty,
        )
    }

    @Synchronized
    fun agentRevision(): Long {
        ensureOpen()
        return agentRevisionCounter
    }

    /** Replaces one of the two documents after an Agent revision check. */
    @Synchronized
    fun replaceAgentDocument(
        path: String,
        content: String,
        expectedRevision: Long,
    ): WatchEditorAgentCandidate {
        ensureOpen()
        check(agentRevisionCounter == expectedRevision) {
            "stale_revision: expected_revision=$expectedRevision current_revision=$agentRevisionCounter"
        }
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_AGENT_DOCUMENT_BYTES) {
            "Agent 文档超过 ${MAX_AGENT_DOCUMENT_BYTES / 1024 / 1024} MiB UTF-8 限制"
        }
        when (path) {
            "watch.xml" -> mutate {
                val normalized = normalizeAgentWatchXmlPaths(content)
                document = parseXml(ByteArrayInputStream(normalized.toByteArray(StandardCharsets.UTF_8)), limits)
                rebindLayerKeys(layerKeys.takeIf { it.size == topLevelLayerElements().size })
            }
            GLOBAL_SCRIPT_RELATIVE_PATH -> updateGlobalScript(content)
            else -> throw WatchEditorException("Agent 只支持 watch.xml 或 scripts/script.txt")
        }
        return agentCandidate()
    }

    /** Performs a unique text replacement through the same transactional path. */
    @Synchronized
    fun editAgentDocument(
        path: String,
        oldText: String,
        newText: String,
        expectedRevision: Long,
    ): WatchEditorAgentCandidate {
        require(oldText.isNotEmpty()) { "old_text 不能为空" }
        val current = when (path) {
            "watch.xml" -> agentCandidate().watchXml
            GLOBAL_SCRIPT_RELATIVE_PATH -> globalScript()
            else -> throw WatchEditorException("Agent 只支持 watch.xml 或 scripts/script.txt")
        }
        val first = current.indexOf(oldText)
        require(first >= 0 && current.indexOf(oldText, first + oldText.length) < 0) {
            "原文必须唯一匹配"
        }
        return replaceAgentDocument(
            path = path,
            content = current.replaceRange(first, first + oldText.length, newText),
            expectedRevision = expectedRevision,
        )
    }

    /** Restores a candidate as one editor mutation for an Agent undo group. */
    @Synchronized
    fun restoreAgentCandidate(candidate: WatchEditorAgentCandidate): WatchEditorAgentCandidate {
        ensureOpen()
        mutate {
            document = parseXml(
                ByteArrayInputStream(normalizeAgentWatchXmlPaths(candidate.watchXml).toByteArray(StandardCharsets.UTF_8)),
                limits,
            )
            rebindLayerKeys(layerKeys.takeIf { it.size == topLevelLayerElements().size })
            val scriptFile = globalScriptFile()
            if (candidate.globalScript.isEmpty()) {
                if (scriptFile.exists() && !scriptFile.delete()) throw WatchEditorException("无法恢复全局脚本")
            } else {
                writeAtomically(scriptFile, candidate.globalScript.toByteArray(StandardCharsets.UTF_8))
            }
        }
        return agentCandidate()
    }

    /** Returns scripts/script.txt as UTF-8, or an empty string when it is absent. */
    @Synchronized
    fun globalScript(): String {
        ensureOpen()
        return scriptBytesInternal()?.toString(StandardCharsets.UTF_8).orEmpty()
    }

    /**
     * Replaces the WatchMaker global script as one undoable editor mutation.
     * An exactly empty value removes scripts/script.txt instead of retaining an
     * empty archive entry.
     */
    @Synchronized
    fun updateGlobalScript(script: String) {
        val bytes = script.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_WATCH_SCRIPT_BYTES) {
            throw WatchEditorException("全局脚本超过 $MAX_WATCH_SCRIPT_BYTES 字节限制")
        }
        mutate {
            val scriptFile = globalScriptFile()
            if (bytes.isEmpty()) {
                if (scriptFile.exists() && !scriptFile.delete()) {
                    throw WatchEditorException("无法删除全局脚本")
                }
            } else {
                writeAtomically(scriptFile, bytes)
            }
        }
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
            // 改 `path` 就是「换素材」：旧文件要跟着走，否则替换一次就在包里留一份
            // 再也不会被引用的图（老师报「替换图片的时候也是」）。
            //
            // 候选要在写入**之前**取，写完就查不到旧值了；真正删不删仍由
            // stashUnreferencedResources 按全文档引用集判定，所以别的图层还在用
            // 同一张图时不会被误删。走 stash 也保证撤销能原子地还原回来。
            val obsolete = if (changes.containsKey("path")) {
                layerOwnedResources(layer)
            } else {
                emptySet()
            }
            changes.filterKeys { !it.startsWith("Ambient.") }
                .forEach { (name, value) -> setOrRemoveAttribute(layer, name, value) }
            val ambientChanges = changes.filterKeys { it.startsWith("Ambient.") }
                .mapKeys { it.key.removePrefix("Ambient.") }
            if (ambientChanges.isNotEmpty()) setAmbientAttributesDirect(layer, ambientChanges)
            stashUnreferencedResources(obsolete)
        }
    }

    fun updateLayerAttribute(key: WatchEditorLayerKey, name: String, value: String?) =
        updateLayerAttributes(key, mapOf(name to value))

    fun updateAmbientAttributes(key: WatchEditorLayerKey, changes: Map<String, String?>) =
        updateLayerAttributes(key, changes.mapKeys { "Ambient.${it.key}" })

    private fun setAmbientAttributesDirect(layer: Element, changes: Map<String, String?>) {
        require(changes.keys.all {
            it in setOf(
                "color", "opacity", "x", "y", "width", "height", "rotation", "fontSize",
                TRANSFORM_SCALE_X, TRANSFORM_SCALE_Y,
            )
        })
        var ambient = layer.childNodes.elements().firstOrNull { it.tagName.equals("Ambient", true) }
        if (ambient == null && changes.values.any { it != null }) {
            ambient = document.createElement("Ambient").also(layer::appendChild)
        }
        ambient?.let { node ->
            changes.forEach { (name, value) -> setOrRemoveAttribute(node, name, value) }
            if (node.attributes.length == 0 && !node.hasChildNodes()) layer.removeChild(node)
        }
    }

    /** World-space translation, with one transaction and no double movement through parents. */
    @Synchronized
    @JvmOverloads
    fun beginLayerTranslationGesture(
        keys: Set<WatchEditorLayerKey>,
        resolvedTransforms: Map<WatchEditorLayerKey, WatchEditorTransform> = emptyMap(),
        ambientMode: Boolean = false,
    ): Map<WatchEditorLayerKey, WatchEditorTransform> {
        ensureOpen()
        cancelActiveTransformGesture()
        val layers = snapshot().layers
        val geometries = buildWatchEditorLayerGeometries(layers, transformOverrides = resolvedTransforms)
        val matrices = buildWatchEditorWorldMatrices(geometries)
        val targets = geometries.mapIndexedNotNull { index, geometry ->
            if (geometry.key !in keys) return@mapIndexedNotNull null
            var ancestor = geometry.parentIndex
            val visited = mutableSetOf<Int>()
            while (ancestor != null && visited.add(ancestor)) {
                val parent = geometries.getOrNull(ancestor) ?: break
                if (parent.key in keys) return@mapIndexedNotNull null
                ancestor = parent.parentIndex
            }
            val layer = layers[index]
            val overrides = if (ambientMode && isStudioDefinition()) layer.ambientAttributes else emptyMap()
            fun firstScaleValue(chain: List<String>): String? = chain.firstNotNullOfOrNull { name ->
                overrides[name]?.takeIf(String::isNotBlank)
                    ?: layer.attributes[name]?.takeIf(String::isNotBlank)
            }
            TranslationTarget(
                geometry.key, geometry.transform,
                overrides["x"] ?: layer.attributes["x"], overrides["y"] ?: layer.attributes["y"],
                overrides[TRANSFORM_SCALE_X] ?: layer.attributes[TRANSFORM_SCALE_X],
                overrides[TRANSFORM_SCALE_Y] ?: layer.attributes[TRANSFORM_SCALE_Y],
                isBoundTransformValue(firstScaleValue(SCALE_X_CHAIN)),
                isBoundTransformValue(firstScaleValue(SCALE_Y_CHAIN)),
                geometry.parentIndex?.let { matrices.getOrNull(it)?.inverted() } ?: WatchEditorAffine.IDENTITY,
            )
        }
        require(targets.isNotEmpty()) { "No selected layers" }
        activeTranslationGesture = TranslationGesture(captureState(), targets, ambientMode && isStudioDefinition())
        return targets.associate { it.key to it.initial }
    }

    @Synchronized
    fun updateLayerTranslationPreview(deltaX: Float, deltaY: Float): Map<WatchEditorLayerKey, WatchEditorTransform> {
        ensureOpen()
        require(deltaX.isFinite() && deltaY.isFinite())
        val gesture = activeTranslationGesture ?: throw WatchEditorException("没有活动的多选移动手势")
        return gesture.targets.associate { target ->
            val delta = target.inverseParent.applyVector(deltaX, deltaY)
            val changes = mapOf("x" to translatedExpression(target.x, delta.x), "y" to translatedExpression(target.y, delta.y))
            val layer = findLayer(target.key)
            if (gesture.ambient) setAmbientAttributesDirect(layer, changes)
            else changes.forEach { (name, value) -> setOrRemoveAttribute(layer, name, value) }
            target.key to target.initial.copy(x = target.initial.x + delta.x, y = target.initial.y + delta.y)
        }
    }

    /** Uniformly scales the active multi-selection/AOD selection in the current gesture transaction. */
    @Synchronized
    fun updateLayerSelectionScalePreview(factor: Float): Map<WatchEditorLayerKey, WatchEditorTransform> {
        ensureOpen()
        require(factor.isFinite() && factor > 0f)
        val gesture = activeTranslationGesture ?: throw WatchEditorException("没有活动的多选缩放手势")
        return gesture.targets.associate { target ->
            val nextScaleX = if (target.scaleXBound) target.initial.scaleX else {
                (target.initial.scaleX * factor).coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)
            }
            val nextScaleY = if (target.scaleYBound) target.initial.scaleY else {
                (target.initial.scaleY * factor).coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)
            }
            val changes = buildMap<String, String?> {
                if (!target.scaleXBound) {
                    put(
                        TRANSFORM_SCALE_X,
                        if (factor == 1f) target.scaleX
                        else formatTransformNumber(nextScaleX * WATCHMAKER_SCALE_NORMAL),
                    )
                }
                if (!target.scaleYBound) {
                    put(
                        TRANSFORM_SCALE_Y,
                        if (factor == 1f) target.scaleY
                        else formatTransformNumber(nextScaleY * WATCHMAKER_SCALE_NORMAL),
                    )
                }
            }
            val layer = findLayer(target.key)
            if (gesture.ambient) setAmbientAttributesDirect(layer, changes)
            else changes.forEach { (name, value) -> setOrRemoveAttribute(layer, name, value) }
            target.key to target.initial.copy(scaleX = nextScaleX, scaleY = nextScaleY)
        }
    }

    private fun translatedExpression(original: String?, delta: Float): String? = when {
        delta == 0f -> original
        original.isNullOrBlank() -> formatTransformNumber(delta)
        original.toFloatOrNull() != null -> formatTransformNumber(original.toFloat() + delta)
        else -> "($original)+(${formatTransformNumber(delta)})"
    }

    @Synchronized
    fun commitLayerTranslationGesture(): Boolean {
        ensureOpen()
        val gesture = activeTranslationGesture ?: return false
        activeTranslationGesture = null
        return try {
            validateDocument()
            if (sameState(gesture.before, captureState())) false else {
                pushUndo(gesture.before)
                clearRedo()
                pruneResourceStash()
                agentRevisionCounter = agentRevisionCounter.inc()
                true
            }
        } catch (error: Exception) {
            restoreState(gesture.before)
            throw error
        }
    }

    @Synchronized
    fun cancelLayerTranslationGesture(): Boolean {
        // Cleanup can race Activity disposal; the synchronized closed check also avoids TOCTOU.
        if (closed) return false
        val gesture = activeTranslationGesture ?: return false
        activeTranslationGesture = null
        restoreState(gesture.before)
        pruneResourceStash()
        return true
    }

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
        cancelLayerTranslationGesture()
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
        // MOVE 是逐帧热路径。captureState() 会序列化整份 watch.xml；循环背景的
        // path 往往包含上千个帧文件名，因此拖动任意元素都会在每一帧复制数十万
        // 字符并校验整棵 DOM。这里只保存目标图层的 5 个变换值，完整快照和文档
        // 校验留到 begin/commit 各执行一次。
        val layer = findLayer(key)
        val before = layerTransform(key)
        try {
            setLayerTransformDirect(layer, transform.sanitized())
        } catch (error: Exception) {
            runCatching { setLayerTransformDirect(layer, before) }
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
            // 手势期间只做局部数值写入；在形成唯一撤销点之前统一做一次完整校验。
            validateDocument()
            val after = captureState()
            if (sameState(active.before, after)) {
                false
            } else {
                pushUndo(active.before)
                clearRedo()
                pruneResourceStash()
                agentRevisionCounter = agentRevisionCounter.inc()
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
            assignStudioLayerIdentity(element, type)
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
            val obsoleteLoopResources = layerOwnedResources(element)
            element.parentNode?.removeChild(element)
            layerKeys.removeAt(position)
            stashUnreferencedResources(obsoleteLoopResources)
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
            // 源层本来就没有 id（比如 Flue 早期版本建的 2.0 文档）时补一个：
            // 副本和原件必须能被 parentId 分别引用。
            assignStudioLayerIdentity(copy, copy.getAttribute("type"))
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

    /** Moves the selected block in document order; IDs, parent links and nested payloads survive. */
    @Synchronized
    fun reorderLayers(keys: Set<WatchEditorLayerKey>, targetIndex: Int): Boolean {
        ensureOpen()
        cancelActiveTransformGesture()
        val block = watchEditorLayerSelectionBlock(snapshot().layers, keys)
        if (layerKeys.getOrNull(targetIndex) in block) return false
        return reorderLayerBlock(block, targetIndex)
    }

    /** Step the entire block across one adjacent unselected row, independent of drop indices. */
    @Synchronized
    fun moveLayersByOne(keys: Set<WatchEditorLayerKey>, direction: Int): Boolean {
        require(direction == -1 || direction == 1)
        ensureOpen()
        cancelActiveTransformGesture()
        val block = watchEditorLayerSelectionBlock(snapshot().layers, keys)
        val indices = layerKeys.indices.filter { layerKeys[it] in block }
        if (indices.isEmpty()) return false
        val neighborIndex = if (direction < 0) indices.first() - 1 else indices.last() + 1
        val neighbor = layerKeys.getOrNull(neighborIndex) ?: return false
        val remaining = layerKeys.filterNot { it in block }
        val insertion = remaining.indexOf(neighbor) + if (direction > 0) 1 else 0
        return reorderLayerBlock(block, insertion)
    }

    private fun reorderLayerBlock(block: Set<WatchEditorLayerKey>, targetIndex: Int): Boolean {
        val movedKeys = layerKeys.filter { it in block }
        if (movedKeys.isEmpty()) return false
        val remainingKeys = layerKeys.filterNot { it in block }.toMutableList()
        val destination = targetIndex.coerceIn(0, remainingKeys.size)
        remainingKeys.addAll(destination, movedKeys)
        if (remainingKeys == layerKeys) return false
        return mutate {
            val byKey = layerKeys.zip(topLevelLayerElements()).toMap()
            movedKeys.forEach { key -> byKey.getValue(key).let { it.parentNode.removeChild(it) } }
            movedKeys.forEachIndexed { index, key ->
                insertLayerAtDocumentIndex(byKey.getValue(key), destination + index,
                    topLevelLayerElements(), preferredLayerContainer(document.documentElement))
            }
            layerKeys = remainingKeys
            true
        }
    }

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
        agentRevisionCounter = agentRevisionCounter.inc()
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
        agentRevisionCounter = agentRevisionCounter.inc()
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
        return mutate { importResourceDirect(input, requestedName, directory) }
    }

    /** Imports one AngelCode BMFont definition and all selected page images atomically. */
    @Synchronized
    fun importBitmapFont(
        files: List<Pair<String, ByteArray>>,
        selectedLayer: WatchEditorLayerKey,
    ): String {
        require(files.isNotEmpty()) { "位图字体资源不能为空" }
        return mutate {
            val normalized = files.associate { (name, bytes) ->
                sanitizeFileName(name) to bytes
            }
            val definition = normalized.entries.firstOrNull { it.key.endsWith(".fnt", true) }
                ?: throw WatchEditorException("请选择 .fnt 位图字体定义")
            val referencedPages = BITMAP_FONT_PAGE_PATTERN.findAll(
                definition.value.toString(StandardCharsets.UTF_8),
            ).map { it.groupValues[1].substringAfterLast('/').substringAfterLast('\\') }.toSet()
            val available = normalized.keys.map { it.lowercase(Locale.ROOT) }.toSet() +
                draftDirectory.resolve("fonts_bm").listFiles().orEmpty()
                    .filter(File::isFile).map { it.name.lowercase(Locale.ROOT) }
            val missing = referencedPages.filter { it.lowercase(Locale.ROOT) !in available }
            if (missing.isNotEmpty()) {
                throw WatchEditorException("位图字体缺少图片：${missing.joinToString()}")
            }
            val importedPageNames = linkedMapOf<String, String>()
            normalized.filterKeys { !it.endsWith(".fnt", true) }.forEach { (name, bytes) ->
                val path = importResourceDirect(bytes.inputStream(), name, "fonts_bm")
                importedPageNames[name.lowercase(Locale.ROOT)] = path.substringAfterLast('/')
            }
            val rewrittenDefinition = BITMAP_FONT_PAGE_PATTERN.replace(
                definition.value.toString(StandardCharsets.UTF_8),
            ) { match ->
                val original = match.groupValues[1].substringAfterLast('/').substringAfterLast('\\')
                val imported = importedPageNames[original.lowercase(Locale.ROOT)] ?: original
                match.value.replace(match.groupValues[1], imported)
            }
            val definitionPath = importResourceDirect(
                rewrittenDefinition.byteInputStream(), definition.key, "fonts_bm",
            )
            val value = "bm:${definitionPath.substringAfterLast('/').substringBeforeLast('.')}"
            setOrRemoveAttribute(findLayer(selectedLayer), "font", value)
            value
        }
    }

    /**
     * Imports an FFmpeg-produced frame set and updates the target layer in one
     * editor transaction. A failed frame copy rolls the whole sequence back,
     * so the layer can never point at a partially imported video.
     */
    @Synchronized
    fun importVideoFrameSequence(
        key: WatchEditorLayerKey,
        frameFiles: List<File>,
        frameDelayMs: Int,
    ): List<String> {
        require(frameFiles.isNotEmpty()) { "视频帧序列不能为空" }
        require(frameDelayMs > 0) { "视频帧间隔必须大于零" }
        return mutate {
            val layer = findLayer(key)
            val obsoleteLoopResources = layerOwnedResources(layer)
            // Remove the old reference first so its files can leave the draft
            // before the replacement batch is budgeted. Undo keeps them in the
            // bounded resource stash and restores them atomically if needed.
            setOrRemoveAttribute(layer, "path", null)
            stashUnreferencedResources(obsoleteLoopResources)
            var remainingBytes = remainingBudget()
            val importedPaths = mutableListOf<String>()
            val paths = try {
                frameFiles.map { file ->
                    require(file.isFile && file.length() > 0L) { "视频帧不可用：${file.name}" }
                    val path = importResourceFileDirect(
                        sourceFile = file,
                        requestedName = file.name,
                        directory = "images",
                        totalBudget = remainingBytes,
                    )
                    importedPaths += path
                    remainingBytes -= resolveDraftPath(path).length()
                    path
                }
            } catch (error: Exception) {
                // XML 尚未引用这批文件，restoreState 看不到它们；在把异常交给
                // mutate 回滚旧素材之前先清掉已经搬入草稿的孤儿帧。
                importedPaths.forEach { path -> runCatching { resolveDraftPath(path).delete() } }
                throw error
            }
            setOrRemoveAttribute(layer, "type", "image_gif")
            // 写裸文件名：规范的 path 是「约定目录内的文件名」，
            // 见 watchXmlAssetPath 的注释。
            setOrRemoveAttribute(
                layer,
                "path",
                paths.joinToString("`") { watchXmlAssetPath(it, "images") },
            )
            setOrRemoveAttribute(layer, "gif_delay", frameDelayMs.toString())
            paths
        }
    }

    /**
     * 把已完成 H.264 MP4 重编码的视频导入为 1.0 的 `video` 图层。
     *
     * 与 [importVideoFrameSequence] 是同一位置的两条路：那条把视频烘焙成
     * `image_gif` + 反引号帧路径，这条引用 `videos/` 下经过标准化的 MP4 文件。
     *
     * 仍然复用相同的资源预算与回滚约定 —— 旧素材先解引用再入库，异常时把已
     * 搬入草稿的孤儿文件清掉，剩下的交给 `mutate` 回滚。
     *
     * [videoWidth] / [videoHeight] 是视频的显示朝向像素尺寸。给了就按 contain
     * 写 `width`/`height`：不写的话渲染器缺省取整个设计空间，方画布下视频会被
     * 拉成 1:1（老师实机反馈）。
     */
    fun importVideoFile(
        key: WatchEditorLayerKey,
        videoFile: File,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        /** false 时保留图层已有的 width/height，不用导入的比例覆盖手工尺寸。 */
        overwriteSize: Boolean = true,
    ): String {
        require(videoFile.isFile && videoFile.length() > 0L) { "视频文件不可用：${videoFile.name}" }
        return mutate {
            val layer = findLayer(key)
            val obsoleteLoopResources = layerOwnedResources(layer)
            setOrRemoveAttribute(layer, "path", null)
            stashUnreferencedResources(obsoleteLoopResources)
            val path = try {
                importResourceFileDirect(
                    sourceFile = videoFile,
                    requestedName = videoFile.name,
                    directory = "videos",
                    totalBudget = remainingBudget(),
                )
            } catch (error: Exception) {
                throw error
            }
            setOrRemoveAttribute(layer, "type", "video")
            // 裸文件名，理由同 image_gif（见 watchXmlAssetPath）。
            setOrRemoveAttribute(layer, "path", watchXmlAssetPath(path, "videos"))
            // 帧序列的属性在这条路上没有意义；留着会让渲染器同时看到两套
            // 播放参数。gif_delay 是 image_gif 专属，一并清掉。
            setOrRemoveAttribute(layer, "gif_delay", null)
            applyContainedVideoSize(layer, videoWidth, videoHeight, overwriteSize)
            path
        }
    }

    /**
     * 按 contain 语义写图层盒子：等比缩放到刚好装进设计空间，长边贴边、
     * 短边留白。
     *
     * 基准是**设计空间**（[WatchCanvasSize.designExtentWidth]）而不是根节点上
     * 的像素声明 —— 图层坐标全部活在设计空间里，拿 466 这类像素值去算会得到
     * 完全错误的盒子。
     */
    private fun applyContainedVideoSize(
        layer: Element,
        videoWidth: Int,
        videoHeight: Int,
        overwriteSize: Boolean,
    ) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        if (!overwriteSize &&
            (layer.hasAttribute("width") || layer.hasAttribute("height"))
        ) {
            return
        }
        val canvas = WatchCanvasSize.fromRootAttributes(attributesOf(document.documentElement))
        val scale = min(
            canvas.designExtentWidth / videoWidth,
            canvas.designExtentHeight / videoHeight,
        )
        setOrRemoveAttribute(
            layer,
            "width",
            (videoWidth * scale).roundToInt().coerceAtLeast(1).toString(),
        )
        setOrRemoveAttribute(
            layer,
            "height",
            (videoHeight * scale).roundToInt().coerceAtLeast(1).toString(),
        )
        // size 是 width/height 的共享回退值，留着会和刚写的两条打架。
        setOrRemoveAttribute(layer, "size", null)
    }

    /**
     * 图片素材导入完成后写显示尺寸。
     *
     * `width` / `height` 对 image 与 image_gif 都是**必填**（1.0 §7.1，商店的
     * `WatchStoreUploadRules.requiredAttributes` 同样这么查）。默认属性表里不能预填
     * 它们 —— 那会让「没有尺寸 = 按素材比例自动适配」这条约定失效，导入的图被钉死在
     * 默认值上。所以正确的时机是**在这里**：素材落盘之后，真实像素尺寸已知。
     *
     * 尺寸算法与视频完全一致（contain），像素值交由调用方读取：解码要 BitmapFactory，
     * 那是 Android 的东西，核心层不碰。
     */
    @Synchronized
    fun applyImportedImageSize(
        key: WatchEditorLayerKey,
        pixelWidth: Int,
        pixelHeight: Int,
        overwriteSize: Boolean = false,
    ) {
        if (pixelWidth <= 0 || pixelHeight <= 0) return
        mutate {
            applyContainedVideoSize(findLayer(key), pixelWidth, pixelHeight, overwriteSize)
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
        savedScriptBytes = scriptBytesInternal()
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
            savedScriptBytes = scriptBytesInternal()
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

    /**
     * 用新渲染的截图覆盖草稿里的 preview.jpg。
     *
     * 表盘列表和商店都读包内 watch.xml 旁边的 preview.jpg（见
     * WatchmakerWatchFaceStorage.previewNextTo），而那张图是原作者打包时带的。
     * 编辑保存不重写它，列表和商店就永远显示旧预览。这里在 save /
     * saveCurrentRevision / exportWatch 之前调，让三条路径都带上新图。
     *
     * 同时写 preview.jpg 和 preview_dim.jpg：扫描器按这个顺序找，只覆盖前者
     * 会让存在 preview_dim.jpg 的老包在暗色档位下仍显示旧图。
     */
    @Synchronized
    fun writePreviewImage(bytes: ByteArray, dimBytes: ByteArray = bytes) {
        ensureOpen()
        require(bytes.isNotEmpty()) { "预览图内容为空" }
        require(dimBytes.isNotEmpty()) { "暗色预览图内容为空" }
        val parent = watchXmlFile.parentFile ?: throw WatchEditorException("草稿目录结构异常")
        writeAtomically(File(parent, "preview.jpg"), bytes)
        writeAtomically(File(parent, "preview_dim.jpg"), dimBytes)
    }

    /**
     * Saves the draft and atomically exports it as `.watch`.
     * [rootAttributeOverrides] affect only the exported archive, which lets the
     * store attach upload-only author metadata without mutating the installed face.
     */
    @Synchronized
    fun exportWatch(
        outputFile: File,
        rootAttributeOverrides: Map<String, String?> = emptyMap(),
        protectedExport: Boolean = false,
    ): WatchEditorExportResult {
        ensureOpen()
        cancelActiveTransformGesture()
        require(!isWithin(outputFile.canonicalFile, draftDirectory.canonicalFile)) {
            "导出文件不能位于编辑草稿目录内"
        }
        validateDocument()
        val draftXmlBytes = serializeDocument(document)
        writeAtomically(watchXmlFile, draftXmlBytes)
        // Android's DOM implementation cannot clone a DOCUMENT_NODE and throws
        // "Cannot copy node of type 9". Build a fresh document and import only
        // the root element so upload-only metadata never mutates the live draft.
        val exportDocument = createExportDocument(rootAttributeOverrides)
        setProtectionAttribute(exportDocument.documentElement, enabled = protectedExport)
        applyExportRootMetadata(exportDocument, rootAttributeOverrides)
        val fullXmlBytes = normalizeExportXmlPaths(
            serializeDocument(exportDocument),
            protectedExport = protectedExport,
        )
        val shellXmlBytes = if (protectedExport) {
            val shellDocument = createExportDocument(rootAttributeOverrides)
            setProtectionAttribute(shellDocument.documentElement, enabled = true)
            val root = shellDocument.documentElement
            while (root.firstChild != null) root.removeChild(root.firstChild)
            serializeDocument(shellDocument)
        } else {
            fullXmlBytes
        }
        val pxmlRelativePath = protectedXmlRelativePath()
        val output = outputFile.canonicalFile
        output.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("无法创建导出目录") }
        val temporary = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.tmp")
        var entryCount = 0
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                val entries = buildExportEntries(
                    protectedExport = protectedExport,
                    shellXmlBytes = shellXmlBytes,
                    fullXmlBytes = fullXmlBytes,
                    pxmlRelativePath = pxmlRelativePath,
                )
                for (archiveEntry in entries) {
                    val relative = archiveEntry.relativePath
                    validateRelativePath(relative)
                    val entry = ZipEntry(relative)
                    zip.putNextEntry(entry)
                    if (archiveEntry.bytes != null) {
                        zip.write(archiveEntry.bytes)
                    } else {
                        val file = requireNotNull(archiveEntry.sourceFile)
                        val bytes = file.inputStream().use { it.readBytes() }
                        zip.write(
                            when {
                                archiveEntry.protectedAsset ->
                                    WatchmakerProtection.prepareProtectedAssetBytes(bytes, file.extension)
                                archiveEntry.plainAsset ->
                                    WatchmakerProtection.preparePlainAssetBytes(bytes, file.extension)
                                else -> bytes
                            },
                        )
                    }
                    zip.closeEntry()
                    entryCount++
                }
            }
            // Keep editor output on the same trust boundary as imports. If this
            // succeeds, WatchArchiveInstaller can consume the archive directly.
            WatchArchiveValidator().inspect(temporary)
            atomicMove(temporary, output)
            savedXml = draftXmlBytes
            savedResourcePaths = resourcePathsInternal().toSet()
            savedScriptBytes = scriptBytesInternal()
            return WatchEditorExportResult(output, output.length(), entryCount)
        } catch (error: Exception) {
            temporary.delete()
            throw WatchEditorException("导出 .watch 失败：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            temporary.delete()
        }
    }

    private data class ExportArchiveEntry(
        val relativePath: String,
        val sourceFile: File? = null,
        val bytes: ByteArray? = null,
        val protectedAsset: Boolean = false,
        val plainAsset: Boolean = false,
    )

    private fun createExportDocument(rootAttributeOverrides: Map<String, String?>): Document =
        document.implementation.createDocument(null, null, null).also { exportDocument ->
            exportDocument.appendChild(exportDocument.importNode(document.documentElement, true))
            rootAttributeOverrides.forEach { (name, value) ->
                setOrRemoveAttribute(exportDocument.documentElement, name, value)
            }
        }

    /**
     * 导出前把两个「必须由打包方算出来」的根属性写好。
     *
     * ### `features`
     *
     * 这是启动器和表盘市场用来筛选、分类的字段。编辑器改完图层不重算，导出的包就
     * 带着**上一位作者的**特性表：加了天气搜不到，删干净心率还挂着心率标签。
     * `WM_STUDIO_2.0_EDITOR.md` §2.3 与 `WM_FORMAT_2.0.md` §2.2 都写明它**每次导出
     * 都重算并覆盖**，手改无效 —— 所以这里照做。
     *
     * 算的时候把全局 Lua 脚本一起喂进去：脚本里用的标签同样是这块表盘的特性，只扫
     * XML 会漏掉纯脚本驱动的表盘。
     *
     * 调用方在 [rootAttributeOverrides] 里显式给了 `features` 时不覆盖 —— 那是上传
     * 流程刻意附加的元数据，明确的调用意图优先于自动推断。
     *
     * ### `ucolor_default`
     *
     * `{ucolor}` 系列标签的默认取色（1.0 §3、2.0 §2.1）。缺这一项时用了 `{ucolor}`
     * 的图层在别的渲染器里拿不到颜色，通常画成透明或全黑 —— 表盘看起来「少了一块」。
     * 只在缺失时补规范默认白，已有值一律不动。
     */
    private fun applyExportRootMetadata(
        exportDocument: Document,
        rootAttributeOverrides: Map<String, String?>,
    ) {
        val root = exportDocument.documentElement
        if (!rootAttributeOverrides.containsKey("features")) {
            val scanned = buildString {
                append(serializeDocument(exportDocument).toString(StandardCharsets.UTF_8))
                globalScriptText()?.let { append('\n').append(it) }
            }
            val version = WatchFormatVersion.fromRootAttributes(attributesOf(root))
            val features = WatchFeatureDetector.format(
                WatchFeatureDetector.detect(scanned),
                version,
            )
            setOrRemoveAttribute(root, "features", features.takeIf(String::isNotEmpty))
        }
        if (root.getAttribute("ucolor_default").isBlank()) {
            root.setAttribute("ucolor_default", SPEC_DEFAULT_UCOLOR)
        }
    }

    /** 全局脚本正文；没有脚本文件时返回 null。 */
    private fun globalScriptText(): String? =
        resolveDraftPath(GLOBAL_SCRIPT_RELATIVE_PATH)
            .takeIf(File::isFile)
            ?.let { runCatching { it.readText(StandardCharsets.UTF_8) }.getOrNull() }

    private fun setProtectionAttribute(root: Element, enabled: Boolean) {
        val protectionAttributes = (0 until root.attributes.length)
            .map { root.attributes.item(it) }
            .filter { it.nodeName.equals("protection", ignoreCase = true) }
        protectionAttributes.forEach { root.removeAttributeNode(it as org.w3c.dom.Attr) }
        if (enabled) root.setAttribute("protection", "y")
    }

    private fun normalizeExportXmlPaths(bytes: ByteArray, protectedExport: Boolean): ByteArray {
        var xml = bytes.toString(StandardCharsets.UTF_8)
        xml = if (protectedExport) {
            xml.replace(".jpeg", ".pjpg", ignoreCase = true)
                .replace(".png", ".ppng", ignoreCase = true)
                .replace(".jpg", ".pjpg", ignoreCase = true)
        } else {
            xml.replace(".ppng", ".png", ignoreCase = true)
                .replace(".pjpeg", ".jpeg", ignoreCase = true)
                .replace(".pjpg", ".jpg", ignoreCase = true)
        }
        return xml.toByteArray(StandardCharsets.UTF_8)
    }

    private fun protectedXmlRelativePath(): String {
        val parent = watchXmlRelativePath.substringBeforeLast('/', "")
        return if (parent.isBlank()) "watch.pxml" else "$parent/watch.pxml"
    }

    private fun buildExportEntries(
        protectedExport: Boolean,
        shellXmlBytes: ByteArray,
        fullXmlBytes: ByteArray,
        pxmlRelativePath: String,
    ): List<ExportArchiveEntry> {
        val result = linkedMapOf<String, ExportArchiveEntry>()
        result[watchXmlRelativePath] = ExportArchiveEntry(
            relativePath = watchXmlRelativePath,
            bytes = shellXmlBytes,
        )
        if (protectedExport) {
            result[pxmlRelativePath] = ExportArchiveEntry(
                relativePath = pxmlRelativePath,
                bytes = WatchmakerProtection.encodeXml(fullXmlBytes),
            )
        }

        val files = draftFiles()
            .filter { file ->
                val relative = relativePath(file)
                relative != watchXmlRelativePath &&
                    !file.name.equals("watch.pxml", ignoreCase = true)
            }
            .sortedWith(compareBy { file ->
                // Prefer the working-copy extension when a protected draft has
                // both `face.png` and an old `face.ppng` twin.
                if (file.extension.equals("ppng", true) ||
                    file.extension.equals("pjpg", true) ||
                    file.extension.equals("pjpeg", true)
                ) 1 else 0
            })

        val referencedStems = referencedAssetStems(fullXmlBytes)
        files.forEach { file ->
            val sourceRelative = relativePath(file)
            if (isPrunableAsset(sourceRelative) && file.nameWithoutExtension.lowercase(Locale.ROOT) !in referencedStems) {
                return@forEach
            }
            val imageResource = isImageResourcePath(sourceRelative)
            val targetRelative = when {
                !imageResource -> sourceRelative
                protectedExport -> protectedImagePath(sourceRelative)
                else -> plainImagePath(sourceRelative)
            }
            if (targetRelative == watchXmlRelativePath || targetRelative == pxmlRelativePath) return@forEach
            result.putIfAbsent(
                targetRelative,
                ExportArchiveEntry(
                    relativePath = targetRelative,
                    sourceFile = file,
                    protectedAsset = protectedExport && imageResource,
                    plainAsset = !protectedExport && imageResource,
                ),
            )
        }
        ensurePreviewEntry(result)
        return result.values.toList()
    }

    /**
     * 只有 `images/` 与 `videos/` 下的素材允许被裁掉。
     *
     * 字体（`fonts/`、`fonts_bm/`）是按**名字**引用的，`font="Bevel"` 要同时留下
     * `bevel.fnt` 和 `bevel.png`，位图字体的分页图名字还写在 `.fnt` 内部；着色器、
     * 脚本、预览图更是完全不出现在图层属性里。这些一律原样打包 —— 少打一个就是
     * 一块渲染不出来的表盘，省下的那几十 KB 不值这个风险。
     */
    private fun isPrunableAsset(relative: String): Boolean =
        relative.startsWith("images/", ignoreCase = true) ||
            relative.startsWith("videos/", ignoreCase = true)

    /**
     * 成品 XML（含脚本）里出现过的素材名主干，小写。
     *
     * 判据故意只比**主干**、还用 contains 而不是精确匹配：
     *  * 受保护导出会把 `face.png` 改名成 `face.ppng`，扩展名对不上，主干才对得上。
     *  * 序列帧的几百个文件名挤在一个 `path` 属性里用反引号分隔，contains 天然覆盖。
     *  * Lua 脚本可能拼字符串，`"frame_" .. i .. ".png"` 这种拼法我们本来就无法静态
     *    解析 —— 所以只要主干在文本里出现过就保留，宁可多留不可错删。
     */
    private fun referencedAssetStems(fullXmlBytes: ByteArray): Set<String> {
        val haystack = buildString {
            append(fullXmlBytes.toString(StandardCharsets.UTF_8))
            globalScriptText()?.let { append('\n').append(it) }
        }.lowercase(Locale.ROOT)
        return draftFiles()
            .asSequence()
            .filter { isPrunableAsset(relativePath(it)) }
            .map { it.nameWithoutExtension.lowercase(Locale.ROOT) }
            .filter { stem -> stem.isNotEmpty() && haystack.contains(stem) }
            .toHashSet()
    }

    /**
     * 保证包里有 `preview.jpg`。
     *
     * `WM_FORMAT_1.0.md` §1 把它列为 **required**，商店的上传预检也直接查这条
     * （`WatchStoreUploadRules.requiredFiles`）。空白草稿从来没有这张图，用户建完新
     * 表盘直接导出就得到一个不合规的包 —— 装到别的启动器里连缩略图都没有。
     *
     * 顺序：已有 `preview.jpg` 就用它；只有暗色变体就拿暗色顶上；两者都没有才用
     * 背景色现画一张。现画的那张会在下一次保存时被真正的画面截图覆盖
     * （[writePreviewImage]），所以它只是「不合规」和「暂时难看」之间的取舍。
     *
     * 画图要 Android 的 Bitmap，纯 JVM 单测里拿不到 —— 拿不到就跳过，退回原来的
     * 行为，不让预览图这件事把导出本身弄失败。
     */
    private fun ensurePreviewEntry(result: MutableMap<String, ExportArchiveEntry>) {
        if (result.keys.any { it.equals(PREVIEW_RELATIVE_PATH, ignoreCase = true) }) return
        val dim = result.entries.firstOrNull {
            it.key.equals(PREVIEW_DIM_RELATIVE_PATH, ignoreCase = true)
        }
        if (dim != null) {
            result[PREVIEW_RELATIVE_PATH] = dim.value.copy(relativePath = PREVIEW_RELATIVE_PATH)
            return
        }
        placeholderPreviewBytes()?.let { bytes ->
            result[PREVIEW_RELATIVE_PATH] = ExportArchiveEntry(
                relativePath = PREVIEW_RELATIVE_PATH,
                bytes = bytes,
            )
        }
    }

    /** 用根节点 `bg_color` 画一张纯色缩略图。取不到 Bitmap（单测环境）时返回 null。 */
    private fun placeholderPreviewBytes(): ByteArray? = runCatching {
        val background = normalizeWatchColorLiteral(document.documentElement.getAttribute("bg_color"))
            ?.let { android.graphics.Color.parseColor(it) }
            ?: android.graphics.Color.BLACK
        val bitmap = android.graphics.Bitmap.createBitmap(
            PLACEHOLDER_PREVIEW_PX,
            PLACEHOLDER_PREVIEW_PX,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        try {
            bitmap.eraseColor(background)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun isImageResourcePath(relative: String): Boolean {
        if (!relative.startsWith("images/", ignoreCase = true)) return false
        return relative.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("png", "jpg", "jpeg", "ppng", "pjpg", "pjpeg")
    }

    private fun protectedImagePath(relative: String): String = when {
        relative.endsWith(".jpeg", ignoreCase = true) -> relative.dropLast(5) + ".pjpg"
        relative.endsWith(".jpg", ignoreCase = true) -> relative.dropLast(4) + ".pjpg"
        relative.endsWith(".png", ignoreCase = true) -> relative.dropLast(4) + ".ppng"
        else -> relative
    }

    private fun plainImagePath(relative: String): String = when {
        relative.endsWith(".pjpeg", ignoreCase = true) -> relative.dropLast(6) + ".jpeg"
        relative.endsWith(".pjpg", ignoreCase = true) -> relative.dropLast(5) + ".jpg"
        relative.endsWith(".ppng", ignoreCase = true) -> relative.dropLast(5) + ".png"
        else -> relative
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
                agentRevisionCounter = agentRevisionCounter.inc()
            }
            result
        } catch (error: Exception) {
            runCatching { restoreState(before) }
            pruneResourceStash()
            if (error is WatchEditorException) throw error
            throw WatchEditorException(error.message ?: "编辑操作失败", error)
        }
    }

    private fun sanitizeFileName(name: String): String = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120)
        .ifBlank { "bitmap_font.bin" }

    private fun captureState(): EditorState = EditorState(
        xml = serializeDocument(document),
        layerKeys = layerKeys.toList(),
        resourcePaths = resourcePathsInternal().toSet(),
        scriptBytes = scriptBytesInternal(),
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
        val scriptFile = globalScriptFile()
        val scriptBytes = state.scriptBytes
        if (scriptBytes == null) {
            if (scriptFile.exists() && !scriptFile.delete()) {
                throw WatchEditorException("无法恢复全局脚本删除状态")
            }
        } else {
            writeAtomically(scriptFile, scriptBytes)
        }
        document = parseXml(ByteArrayInputStream(state.xml), limits)
        rebindLayerKeys(state.layerKeys)
    }

    private fun sameState(first: EditorState, second: EditorState): Boolean =
        first.layerKeys == second.layerKeys &&
            first.resourcePaths == second.resourcePaths &&
            sameNullableBytes(first.scriptBytes, second.scriptBytes) &&
            first.xml.contentEquals(second.xml)

    private fun cancelActiveTransformGesture() {
        cancelLayerTranslationGesture()
        val active = activeTransformGesture ?: return
        activeTransformGesture = null
        restoreState(active.before)
        pruneResourceStash()
    }

    private fun pushUndo(state: EditorState) {
        undoStack.addLast(state)
        undoBytes += state.historyBytes
        trimHistory(undoStack, HistorySide.UNDO)
    }

    private fun pushRedo(state: EditorState) {
        redoStack.addLast(state)
        redoBytes += state.historyBytes
        trimHistory(redoStack, HistorySide.REDO)
    }

    private fun popUndo(): EditorState {
        val state = undoStack.removeLast()
        undoBytes -= state.historyBytes
        return state
    }

    private fun popRedo(): EditorState {
        val state = redoStack.removeLast()
        redoBytes -= state.historyBytes
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
            if (side == HistorySide.UNDO) undoBytes -= removed.historyBytes else redoBytes -= removed.historyBytes
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

    /**
     * 这个图层底下嵌套了多少个 `<Layer>`（含各级后代）。
     *
     * 必须与渲染器解析器的 `collectLayers` **逐层同构**：它是前序递归，且会穿过
     * 非 `Layer` 的中间容器继续找 `<Layer>`，所以这里也要穿过。两边一旦不同构，
     * [WatchEditorLayer.definitionIndex] 就会指错层 —— 那正是「拖文字只有框跟手」
     * 的根因。改这个函数前先回头看一眼 `WatchmakerDefinitionParser.collectLayers`。
     */
    private fun Element.nestedLayerCount(): Int {
        var count = 0
        childNodes.elements().forEach { child ->
            if (child.tagName.equals("Layer", ignoreCase = true)) count++
            count += child.nestedLayerCount()
        }
        return count
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
        val bound = boundTransformAttributes(layer)
        if (TRANSFORM_X !in bound) {
            setOrRemoveAttribute(layer, TRANSFORM_X, formatTransformNumber(safe.x))
        }
        if (TRANSFORM_Y !in bound) {
            setOrRemoveAttribute(layer, TRANSFORM_Y, formatTransformNumber(safe.y))
        }
        if (TRANSFORM_SCALE_X !in bound) {
            setOrRemoveAttribute(
                layer,
                TRANSFORM_SCALE_X,
                formatTransformNumber(safe.scaleX * WATCHMAKER_SCALE_NORMAL),
            )
        }
        if (TRANSFORM_SCALE_Y !in bound) {
            setOrRemoveAttribute(
                layer,
                TRANSFORM_SCALE_Y,
                formatTransformNumber(safe.scaleY * WATCHMAKER_SCALE_NORMAL),
            )
        }
        if (TRANSFORM_ROTATION !in bound) {
            setOrRemoveAttribute(layer, TRANSFORM_ROTATION, formatTransformNumber(safe.rotation))
        }
    }

    /**
     * 这一层上「值是动态表达式、编辑器不许用数值覆盖」的变换属性名。
     *
     * WatchMaker 的变换属性可以直接写表达式：秒针是 `rotation="{drss}"`、电量环是
     * `anim_scale_x="{bl}"`。而 [layerTransform] 读这些属性用的是 `toFloatOrNull() ?: 0f`，
     * 表达式一律读成 0/100%；如果放手时无条件写回，`{drss}` 就变成 `0` ——
     * **绑定被永久删掉**，秒针从此不动。用户只是拖了一下位置，丢的却是整条动画。
     *
     * 所以写回前先问一遍这里：凡是现值不是纯数字的属性，跳过不写。其余轴照常写，
     * 拖动仍然有效（比如给绑了 `rotation` 的指针挪个位置，x/y 会写，rotation 保留）。
     *
     * 缩放两轴要连着回退链一起判：渲染器与 [layerTransform] 的取值顺序都是
     * `anim_scale_x → scale_x → anim_scale → scale`（见 WatchmakerWatchFaceView 的
     * `sharedScale`）。只有 `anim_scale="{bl}"` 时，写 `anim_scale_x` 会**遮蔽**那条
     * 绑定，效果等同于删除，所以链上第一个有值的属性是表达式就整轴锁定。
     */
    private fun boundTransformAttributes(layer: Element): Set<String> = buildSet {
        if (isBoundTransformValue(layer.attributeValue(TRANSFORM_X))) add(TRANSFORM_X)
        if (isBoundTransformValue(layer.attributeValue(TRANSFORM_Y))) add(TRANSFORM_Y)
        if (isBoundTransformValue(layer.attributeValue(TRANSFORM_ROTATION))) add(TRANSFORM_ROTATION)
        if (isBoundTransformValue(layer.firstPresentAttribute(SCALE_X_CHAIN))) add(TRANSFORM_SCALE_X)
        if (isBoundTransformValue(layer.firstPresentAttribute(SCALE_Y_CHAIN))) add(TRANSFORM_SCALE_Y)
    }

    /**
     * 供 UI 提示用：拖动这一层时哪些变换属性会被保留而不写入。
     *
     * 没有这个查询，编辑器就只能「静默不动」——用户拖了指针、松手后角度弹回，
     * 看起来像 bug。有了它，调用方可以在手势开始时说明「rotation 绑定了表达式」。
     */
    @Synchronized
    fun layerBoundTransformAttributes(key: WatchEditorLayerKey): Set<String> {
        ensureOpen()
        return boundTransformAttributes(findLayer(key))
    }

    private fun percentScale(raw: String?): Float =
        ((raw?.toFloatOrNull() ?: WATCHMAKER_SCALE_NORMAL) / WATCHMAKER_SCALE_NORMAL)
            .coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)

    private fun layerSnapshot(
        key: WatchEditorLayerKey,
        element: Element,
        order: Int,
        definitionIndex: Int,
    ) =
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
            definitionIndex = definitionIndex,
            ambientAttributes = element.childNodes.elements()
                .firstOrNull { it.tagName.equals("Ambient", true) }?.let(::attributesOf).orEmpty(),
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
        val excluded = elements.withIndex()
            .filter { (index, _) -> layerKeys[index] == excludedKey }
            .map { (_, layer) -> layer }
            .toSet()
        // 唯一性要对**整份文档**成立，不只是顶层：2.0 的 parentId 引用是全局的，
        // 群组里的子层撞了 id，别的渲染器建树时就会把子层挂错父亲。
        val used = allLayerElements()
            .filterNot { it in excluded }
            .mapNotNull { it.getAttribute("id").takeIf(String::isNotBlank) }
            .toHashSet()
        var candidate = "${base}_copy"
        var suffix = 2
        while (!used.add(candidate)) candidate = "${base}_copy${suffix++}"
        return candidate
    }

    /** 文档内所有 `Layer` 元素，含嵌套。大小写不敏感：手写 XML 里出现过 `<layer>`。 */
    private fun allLayerElements(): List<Element> = buildList {
        fun visit(parent: Element) {
            parent.childNodes.elements().forEach { child ->
                if (child.tagName.equals("Layer", ignoreCase = true)) add(child)
                visit(child)
            }
        }
        visit(document.documentElement)
    }

    /**
     * 给 2.0 文档里新建的图层补上 `id` 与 `name`。
     *
     * `WM_FORMAT_2.0.md` §6 把序列化顺序列得很死，第一个就是 `id`；§6.1 明确
     * 「`id` 唯一，Studio 格式 `layer-{timestamp_ms}-{random}-{counter}`」，`name` 是
     * 编辑器显示名（自动取 `"<Type> N"`）。1.0 没这两项，所以只对 studio 文档做。
     *
     * 为什么不能省：2.0 的层级关系**靠 `parentId` 引用**而不是嵌套（§6.1、§7）。
     * 没有 `id` 的图层永远不能被当成父层，Studio 里也就没法把别的层拖进去；打开
     * Flue 建的 2.0 表盘时那些层还会全部显示成同一个空名字。
     */
    private fun assignStudioLayerIdentity(element: Element, type: String) {
        if (!isStudioDefinition()) return
        val used = allLayerElements()
            .filterNot { it === element }
            .mapNotNull { it.getAttribute("id").takeIf(String::isNotBlank) }
            .toHashSet()
        if (element.getAttribute("id").isBlank()) {
            element.setAttribute("id", freshStudioLayerId(used))
        }
        if (element.getAttribute("name").isBlank()) {
            val names = allLayerElements()
                .filterNot { it === element }
                .mapNotNull { it.getAttribute("name").takeIf(String::isNotBlank) }
                .toHashSet()
            element.setAttribute("name", freshStudioLayerName(type, names))
        }
    }

    /** Studio 的 id 形状：`layer-{timestamp_ms}-{random}-{counter}`。 */
    private fun freshStudioLayerId(used: Set<String>): String {
        val stamp = System.currentTimeMillis()
        val random = UUID.randomUUID().toString().substringBefore('-')
        var counter = 1
        while (true) {
            val candidate = "layer-$stamp-$random-$counter"
            if (candidate !in used) return candidate
            counter++
        }
    }

    /** Studio 的显示名形状：`"<Type> N"`。 */
    private fun freshStudioLayerName(type: String, used: Set<String>): String {
        val label = type.trim().ifBlank { "Layer" }
            .replaceFirstChar { it.titlecase(Locale.ROOT) }
        var index = 1
        while ("$label $index" in used) index++
        return "$label $index"
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

    private fun importResourceDirect(
        input: InputStream,
        requestedName: String,
        directory: String,
        totalBudget: Long? = null,
    ): String {
        val relativePath = uniqueResourcePath(requestedName, directory)
        val destination = resolveDraftPath(relativePath)
        destination.parentFile?.mkdirs()
        val temporaryDirectory = draftDirectory.parentFile ?: draftDirectory
        val temporary = File.createTempFile(".resource-", ".tmp", temporaryDirectory)
        try {
            input.use { source ->
                copyBounded(
                    source,
                    temporary,
                    limits.maxFileBytes,
                    totalBudget ?: remainingBudget(),
                )
            }
            atomicMove(temporary, destination)
            return relativePath
        } catch (error: Exception) {
            temporary.delete()
            throw WatchEditorException("无法导入资源：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            temporary.delete()
        }
    }

    /**
     * FFmpeg 缓存与编辑草稿通常位于同一应用数据卷。优先直接移动帧文件，
     * 避免高帧率视频逐帧经历“输入流 -> 临时文件 -> 再移动”的双倍 IO；
     * 跨文件系统或厂商文件系统拒绝移动时仍回退到原有的有界原子复制。
     */
    private fun importResourceFileDirect(
        sourceFile: File,
        requestedName: String,
        directory: String,
        totalBudget: Long,
    ): String {
        val sourceBytes = sourceFile.length()
        require(sourceFile.isFile && sourceBytes > 0L) { "资源文件不可用：${sourceFile.name}" }
        require(sourceBytes <= limits.maxFileBytes) { "单个资源超过大小限制" }
        require(totalBudget > 0L && sourceBytes <= totalBudget) { "草稿资源总大小已达到上限" }

        val relativePath = uniqueResourcePath(requestedName, directory)
        val destination = resolveDraftPath(relativePath)
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw WatchEditorException("无法创建资源目录")
            }
        }
        try {
            atomicMove(sourceFile, destination)
            return relativePath
        } catch (_: Exception) {
            destination.delete()
        }

        val temporaryDirectory = draftDirectory.parentFile ?: draftDirectory
        val temporary = File.createTempFile(".resource-", ".tmp", temporaryDirectory)
        try {
            sourceFile.inputStream().use { source ->
                copyBounded(source, temporary, limits.maxFileBytes, totalBudget)
            }
            atomicMove(temporary, destination)
            return relativePath
        } catch (error: Exception) {
            destination.delete()
            throw WatchEditorException("无法导入资源：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            temporary.delete()
        }
    }

    private fun remainingBudget(): Long = limits.maxTotalBytes - draftByteCount()

    /**
     * 一个图层「自己占着」的素材文件（草稿相对路径）。
     *
     * 覆盖两类：
     *  * 序列帧 / GIF：`path` 里用反引号串起来的一整串帧，都在 `images/` 下。
     *  * **视频**：`videos/` 下那一个文件。
     *
     * 视频这一支以前是漏的：给 video 图层换一段素材、或者把它整个删掉，旧的 mp4 会一直
     * 留在包里（老师报的「上一个视频还会存在表盘文件里不会删除」）。视频动辄几 MB，攒
     * 几次就把 20MB 的上传上限吃满，而作者在编辑器里完全看不到这些孤儿文件。
     *
     * 返回的只是**候选**：真正删不删由 [stashUnreferencedResources] 再查一遍全文档引用，
     * 所以「换成同一个文件」这种情况不会被误删。
     */
    private fun layerOwnedResources(layer: Element): Set<String> {
        val type = layer.getAttribute("type").lowercase(Locale.ROOT)
        val rawPath = layer.getAttribute("path")
        if (type == "video") {
            return setOfNotNull(draftRelativeAssetPath(rawPath, "videos"))
        }
        if (type in setOf("gif", "image_gif") || '`' in rawPath) {
            return rawPath.split('`')
                .asSequence()
                .map { it.trim().replace('\\', '/') }
                .filter(String::isNotBlank)
                // XML 里存的是**裸文件名**（规范要求，见 watchXmlAssetPath），而文件操作要的是
                // 草稿相对路径 —— 少了这一步换算，删图层时找不到 images/ 下的帧，孤儿文件
                // 会一直留在包里（曾经把 deletingLoopingBackground… 那条测试打红）。
                .mapNotNull { path -> draftRelativeAssetPath(path, "images") }
                .toSet()
        }
        // 单张图片也要回收（老师报「删除图片图层、替换图片时，旧图片也该像视频一样
        // 从表盘资源里移除」）。
        //
        // 以前这里只认视频和多帧序列，单张 image 直接返回空集，于是替换十次背景就在包里
        // 攒下十张再也不会被引用的图 —— 表盘体积一路涨，导出还得连它们一起压。
        //
        // **共用图片不会被误删**：候选只是「这个图层引用过的文件」，真正决定删不删的是
        // stashUnreferencedResources —— 它先算一遍全文档的引用集再过滤，另一个图层还在
        // 用同一张图时这条候选会被直接跳过。所以这里可以放心地把候选算宽一点。
        return setOfNotNull(draftRelativeAssetPath(rawPath, "images"))
    }

    /**
     * 把 XML 的 `path` 值还原成草稿相对路径（[watchXmlAssetPath] 的逆）。
     *
     * 与渲染器 `resolveAsset` 同一套候选顺序：先按约定目录找，再按原样找 ——
     * 老表盘里确实有把素材放在包根目录的写法。返回 null 表示这个值不是合法相对路径
     * （`..` 逃逸之类），调用方当它不存在。
     */
    private fun draftRelativeAssetPath(xmlPath: String, conventionalDirectory: String): String? {
        val normalized = xmlPath.trim().replace('\\', '/').removePrefix("/")
        if (normalized.isBlank()) return null
        val candidates = if (normalized.startsWith("$conventionalDirectory/", ignoreCase = true)) {
            listOf(normalized)
        } else {
            listOf("$conventionalDirectory/$normalized", normalized)
        }
        val valid = candidates.filter { runCatching { validateRelativePath(it) }.isSuccess }
        return valid.firstOrNull { resolveDraftPath(it).exists() } ?: valid.firstOrNull()
    }

    /**
     * 文档里通过 `path` 引用到的**所有**草稿相对路径。
     *
     * 一个 XML 值可能指向 `images/`、`videos/` 或包根，所以把**每一种解读**都算成
     * 「已被引用」。宁可多留一个孤儿文件，也不能误删还在用的素材。
     */
    private fun referencedDraftPaths(): Set<String> = allLayerElements()
        .asSequence()
        .flatMap { layer ->
            layer.getAttribute("path")
                .split('`')
                .asSequence()
                .map { it.trim().replace('\\', '/') }
        }
        .filter(String::isNotBlank)
        .flatMap { value ->
            sequenceOf(
                value,
                draftRelativeAssetPath(value, "images"),
                draftRelativeAssetPath(value, "videos"),
            ).filterNotNull()
        }
        .toSet() + referencedAudioPaths()

    private fun referencedAudioPaths(): Set<String> {
        val values = allLayerElements().flatMap { attributesOf(it).values }
        val actions = values.map(WatchTapActionParser::parse)
        val names = actions.filterIsInstance<WatchTapAction.PlaySound>().map { it.name }
        val raw = values.joinToString("\n") + "\n" + globalScriptText().orEmpty()
        val scripts = actions.filterIsInstance<WatchTapAction.Script>().joinToString("\n") { it.source } +
            "\n" + globalScriptText().orEmpty()
        val scripted = raw.contains("wm_sfx", true) || raw.contains("wm_sound", true) ||
            scripts.contains("play_sound:", true) || names.any { '{' in it }
        val candidates = names.flatMap(::watchSoundAssetCandidates).map { it.lowercase(Locale.ROOT) }.toSet()
        return resourcePathsInternal().filter { path ->
            path.startsWith("sfx/", true) && (scripted || path.lowercase(Locale.ROOT) in candidates)
        }.toSet()
    }

    /**
     * 草稿里全部素材的清单：路径、字节数、有没有被图层引用。
     *
     * 编辑器的「资源管理器」读这里。作者需要看得到包里到底有什么：视频换过几版、
     * 序列帧删剩多少、字体是不是还留着 —— 这些以前完全不可见，只能等上传被 20MB
     * 上限拦下来才发现（老师要的「表盘内部资源管理器」）。
     *
     * `watch.xml` 与全局脚本不算素材，不列出来：它们不是「可以删的东西」。
     * 字体按名字引用、位图字体的分页图名字写在 `.fnt` 内部，静态分析看不出来，
     * 所以 `fonts/` 与 `fonts_bm/` 一律标成已引用，避免作者误删。
     */
    @Synchronized
    fun resourceInventory(): List<WatchEditorResourceEntry> {
        ensureOpen()
        val referenced = referencedDraftPaths()
        return resourcePathsInternal()
            .sorted()
            .map { path ->
                val lower = path.lowercase(Locale.ROOT)
                val alwaysKeep = lower.startsWith("fonts/") ||
                    lower.startsWith("fonts_bm/") ||
                    lower.startsWith("scripts/") ||
                    lower.startsWith("shaders/") ||
                    lower.startsWith("preview")
                WatchEditorResourceEntry(
                    relativePath = path,
                    bytes = resolveDraftPath(path).length(),
                    referenced = alwaysKeep || path in referenced,
                )
            }
    }

    /**
     * 从草稿里移除一个素材。
     *
     * 走的是同一套 stash（可撤销），不是硬删。仍被引用的素材直接拒绝 —— 资源管理器
     * 不该成为「把表盘删坏」的快捷通道。
     */
    @Synchronized
    fun deleteResource(relativePath: String): Boolean {
        ensureOpen()
        val normalized = relativePath.trim().replace('\\', '/')
        if (normalized.isBlank()) return false
        if (normalized == watchXmlRelativePath || normalized == GLOBAL_SCRIPT_RELATIVE_PATH) return false
        if (!resolveDraftPath(normalized).isFile) return false
        if (resourceInventory().firstOrNull { it.relativePath == normalized }?.referenced != false) {
            return false
        }
        var removed = false
        mutate {
            stashUnreferencedResources(setOf(normalized))
            removed = !resolveDraftPath(normalized).exists()
        }
        return removed
    }

    private fun stashUnreferencedResources(candidates: Set<String>) {
        if (candidates.isEmpty()) return
        val referenced = referencedDraftPaths()
        candidates.filterNot(referenced::contains).forEach { path ->
            val source = resolveDraftPath(path)
            if (!source.isFile) return@forEach
            if (!historyDirectory.exists() && !historyDirectory.mkdirs()) {
                throw WatchEditorException("无法创建撤销资源缓存")
            }
            val stash = File(historyDirectory, UUID.randomUUID().toString())
            atomicMove(source, stash)
            resourceStash.remove(path)?.delete()
            resourceStash[path] = stash
            var parent = source.parentFile
            while (parent != null && parent != draftDirectory && parent.list().isNullOrEmpty()) {
                if (!parent.delete()) break
                parent = parent.parentFile
            }
        }
    }

    private fun draftByteCount(): Long = draftFiles().sumOf { it.length() }

    private fun resourcePathsInternal(): List<String> = draftFiles()
        .map(::relativePath)
        .filterNot { it == watchXmlRelativePath || it == GLOBAL_SCRIPT_RELATIVE_PATH }

    private fun globalScriptFile(): File = resolveDraftPath(GLOBAL_SCRIPT_RELATIVE_PATH)

    private fun scriptBytesInternal(): ByteArray? {
        val script = globalScriptFile()
        if (!script.isFile) return null
        require(script.length() <= MAX_WATCH_SCRIPT_BYTES) {
            "全局脚本超过 $MAX_WATCH_SCRIPT_BYTES 字节限制"
        }
        return script.readBytes()
    }

    private fun scriptRevision(bytes: ByteArray?): String {
        if (bytes == null) return "absent"
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun sameNullableBytes(first: ByteArray?, second: ByteArray?): Boolean = when {
        first == null -> second == null
        second == null -> false
        else -> first.contentEquals(second)
    }

    private val EditorState.historyBytes: Long
        get() = xml.size.toLong() + (scriptBytes?.size ?: 0)

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
        private val BITMAP_FONT_PAGE_PATTERN =
            Regex(
                """page\s+[^\r\n]*file="([^"]+)"""",
                RegexOption.IGNORE_CASE,
            )
        private const val DRAFT_DIRECTORY_NAME = "flue-watch-editor"
        private const val WATCHMAKER_SCALE_NORMAL = 100f
        private const val MIN_TRANSFORM_SCALE = 0.05f
        private const val MAX_TRANSFORM_SCALE = 8f
        private const val GLOBAL_SCRIPT_RELATIVE_PATH = "scripts/script.txt"

        /** 编辑器画布会写回的五个变换属性名。 */
        const val TRANSFORM_X: String = "x"
        const val TRANSFORM_Y: String = "y"
        const val TRANSFORM_ROTATION: String = "rotation"
        const val TRANSFORM_SCALE_X: String = "anim_scale_x"
        const val TRANSFORM_SCALE_Y: String = "anim_scale_y"

        /** 缩放取值回退链，顺序必须与渲染器一致，见 [boundTransformAttributes]。 */
        private val SCALE_X_CHAIN = listOf(TRANSFORM_SCALE_X, "scale_x", "anim_scale", "scale")
        private val SCALE_Y_CHAIN = listOf(TRANSFORM_SCALE_Y, "scale_y", "anim_scale", "scale")

        /** 缺 `ucolor_default` 时补的规范默认值（1.0 §3 的示例就是 `ffffff`）。 */
        private const val SPEC_DEFAULT_UCOLOR = "ffffff"
        private const val PREVIEW_RELATIVE_PATH = "preview.jpg"
        private const val PREVIEW_DIM_RELATIVE_PATH = "preview_dim.jpg"
        private const val PLACEHOLDER_PREVIEW_PX = 512

        const val MAX_WATCH_SCRIPT_BYTES: Int = 256 * 1024
        private const val MAX_AGENT_DOCUMENT_BYTES: Int = 2 * 1024 * 1024

        /** Keeps memory and disk-history bounded while still allowing a useful edit chain. */
        const val MAX_HISTORY_STEPS: Int = 32

        /** Opens an installed standard descriptor by copying it into cache first. */
        fun open(
            context: Context,
            descriptor: WatchFaceDescriptor,
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
            canvasSize: WatchCanvasSize = WatchCanvasSize.DEFAULT,
            formatVersion: WatchFormatVersion = WatchFormatVersion.V1,
        ): WatchEditorSession =
            createBlank(
                File(context.cacheDir, DRAFT_DIRECTORY_NAME),
                name,
                limits,
                roundShape = roundShape,
                canvasSize = canvasSize,
                formatVersion = formatVersion,
            )

        fun createBlank(
            cacheDirectory: File,
            name: String = "New Watch",
            limits: WatchEditorLimits = WatchEditorLimits.DEFAULT,
            removeDraftOnClose: Boolean = true,
            roundShape: Boolean = false,
            canvasSize: WatchCanvasSize = WatchCanvasSize.DEFAULT,
            formatVersion: WatchFormatVersion = WatchFormatVersion.V1,
        ): WatchEditorSession {
            val cache = prepareCacheDirectory(cacheDirectory)
            val draft = createDraftDirectory(cache)
            val watchXml = "watch.xml"
            try {
                val safeName = name.replace('\u0000', ' ').trim().ifBlank { "New Watch" }
                // shape 曾硬编码 "R"：编辑器建出的每块表盘都被渲染器按
                // definition.shape == ROUND 罩上圆形裁剪 —— 方屏表盘删光元素后
                // 仍有一圈圆形遮罩就是它。现在跟随调用方传入的屏幕形状。
                // WatchMaker 表盘固定使用原生 512×512 画布（1.0 §4）。canvasSize 参数
                // 保留只是为了兼容已有调用方，新建结果永远是原生规格，所以根节点
                // 也就不需要声明尺寸。
                val shapeCode = if (roundShape) "R" else "S"
                // 版本判据就是有没有 build_source="studio"：写了算 2.0，不写算 1.0。
                // 这里曾无条件写 studio，于是新建出来的每一块都是 2.0，
                // 用不了 1.0 专属的 video 图层。
                val buildSourceAttribute = when (formatVersion) {
                    WatchFormatVersion.V2 -> " build_source=\"studio\""
                    WatchFormatVersion.V1 -> ""
                }
                // 不写 width / height：规范的根属性表（1.0 §3）里没有这两项，§4 把画布
                // 固定成 512×512，而 `WatchCanvasSize.fromRootAttributes` 在两者都缺失时
                // 正好回落 512×512 —— 所以省掉它们对 Flue 是零行为变化，对别的渲染器
                // 少两个读不懂的属性。
                val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Watch name=\"${escapeXmlAttribute(safeName)}\"$buildSourceAttribute" +
                    " shape=\"$shapeCode\"/>"
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

data class WatchEditorAgentCandidate(
    val revision: Long,
    val watchXml: String,
    val globalScript: String,
    val resourcePaths: List<String>,
    val dirty: Boolean,
)

private fun normalizeAgentWatchXmlPaths(xml: String): String =
    Regex("(?is)<Layer\\b[^>]*>").replace(xml) { match ->
        val layer = match.value
        val path = Regex("(?i)(\\bpath\\s*=\\s*)([\\\"'])(.*?)\\2").find(layer)
            ?: return@replace layer
        val raw = path.groupValues[3]
        if (!raw.contains("images/", ignoreCase = true)) return@replace layer
        val normalized = raw.split('`').joinToString("`") { segment ->
            watchXmlAssetPath(segment, "images")
        }
        layer.replaceRange(
            path.range,
            "${path.groupValues[1]}${path.groupValues[2]}$normalized${path.groupValues[2]}",
        )
    }

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

/** 按回退链取第一个真的写了的属性值；全都没写返回 null。 */
private fun Element.firstPresentAttribute(names: List<String>): String? =
    names.firstNotNullOfOrNull(::attributeValue)

/**
 * 现值是不是「动态绑定」——即编辑器不能用一个数字替换掉的东西。
 *
 * 判据故意放得很宽：**不是纯数字就算绑定**。WatchMaker 的表达式语法不止 `{tag}`
 * 一种（还有 `{floor(dh*5)}`、`{$vb$}`、纯 Lua 变量引用等等，2.0 又加了新的插值
 * 形式），白名单式的识别一定会漏，而漏判的代价是**静默删掉作者的动画**。反过来
 * 误判的代价只是「这一轴拖不动」，可见、可解释、可撤销。空值不算绑定，那只是
 * 没写过，编辑器当然可以写。
 */
private fun isBoundTransformValue(raw: String?): Boolean {
    val value = raw?.trim().orEmpty()
    return value.isNotEmpty() && value.toFloatOrNull() == null
}

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
