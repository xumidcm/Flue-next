package com.flue.launcher.watchface.editor

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** Physical preview dimensions; these never enter watch.xml or design-canvas limits. */
data class WatchEditorViewport(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    val aspectRatio: Float get() = width.toFloat() / height
    val label: String get() = "$width × $height"
}

/** Keep the extension so two sounds with the same stem remain distinguishable. */
fun watchEditorAudioTapAction(resourcePath: String): String =
    "play_sound:${resourcePath.replace('\\', '/').substringAfterLast('/')}"

/** A selected parent carries its descendants for both row controls and drag reordering. */
fun watchEditorLayerSelectionBlock(
    layers: List<WatchEditorLayer>,
    keys: Set<WatchEditorLayerKey>,
): Set<WatchEditorLayerKey> {
    val geometries = buildWatchEditorLayerGeometries(layers)
    val block = keys.intersect(layers.map { it.key }.toSet()).toMutableSet()
    geometries.forEach { geometry ->
        var ancestor = geometry.parentIndex
        val visited = mutableSetOf<Int>()
        while (ancestor != null && visited.add(ancestor)) {
            val parent = geometries.getOrNull(ancestor) ?: break
            if (parent.key in keys) { block.add(geometry.key); break }
            ancestor = parent.parentIndex
        }
    }
    return block
}

/**
 * Hard limits shared by source copying, XML mutation and resource imports so
 * the editor cannot accidentally become an unbounded file-copy surface.
 */
data class WatchEditorLimits(
    val maxTotalBytes: Long = 500L * 1024L * 1024L,
    val maxFileBytes: Long = 450L * 1024L * 1024L,
    val maxHistoryBytes: Long = 32L * 1024L * 1024L,
    val maxXmlDepth: Int = 128,
) {
    init {
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(maxFileBytes <= maxTotalBytes) { "maxFileBytes cannot exceed maxTotalBytes" }
        require(maxHistoryBytes > 0) { "maxHistoryBytes must be positive" }
        require(maxXmlDepth >= 8) { "maxXmlDepth is too small" }
    }

    companion object {
        val DEFAULT = WatchEditorLimits()
    }
}

@JvmInline
value class WatchEditorLayerKey(val value: String) {
    init {
        require(value.isNotBlank()) { "layer key must not be blank" }
    }

    companion object {
        fun create(): WatchEditorLayerKey = WatchEditorLayerKey("layer-${UUID.randomUUID()}")
    }
}

/**
 * 把草稿内的相对路径转成**写进 watch.xml 的 `path` 值**。
 *
 * ## 为什么必须转
 *
 * 规范里 `path` 是**约定目录内的裸文件名**：`WM_FORMAT_1.0.md` §7.1 image 写的是
 * 「Filename inside `images/`」、§7.14 video 是「Filename inside `videos/`」；
 * `WM_FORMAT_2.0.md` §7/§9/§17 的例子全是 `path="img_5qyxcg.png"`，上游两个真实样本包
 * （`share2.watch` / `Untitled.watch`）也都是裸名。
 *
 * 而 Flue 的 `importResource` 返回的是**草稿相对路径**（`images/foo.png`）—— 直接写进
 * `path` 就变成 `images/images/foo.png` 在别的渲染器里去找。Flue 自己看不出来：它的
 * `resolveAsset` 会先按原样相对 watch.xml 解一次，正好命中。所以这个 bug 只在**把表盘
 * 拿到官方 WatchMaker / CloX / wftools 里**才暴露成「图片、帧序列、视频全是空层」。
 *
 * 字体那条路一直是对的（调用点自己 `substringAfterLast('/')`），图片与视频漏了这一步。
 *
 * ## 为什么只剥一层约定目录
 *
 * 作者可能在 `images/` 下再建子目录（`images/hands/big.png`）。规范只规定「在 images/
 * 里面」，没禁止子目录，所以这里**只剥掉约定目录那一段**，子路径原样保留 ——
 * 一律取 basename 会让两个不同子目录里的同名文件撞车。
 */
fun watchXmlAssetPath(draftRelativePath: String, conventionalDirectory: String): String {
    val normalized = draftRelativePath.trim().replace('\\', '/').removePrefix("/")
    val prefix = "$conventionalDirectory/"
    return if (normalized.startsWith(prefix, ignoreCase = true)) {
        normalized.substring(prefix.length)
    } else {
        normalized
    }
}

data class WatchEditorLayer(
    val key: WatchEditorLayerKey,
    val id: String?,
    val type: String,
    val parentId: String?,
    val attributes: Map<String, String>,
    val childElementNames: List<String>,
    val order: Int,
    /**
     * 同一图层在渲染器 `WatchmakerDefinition.layers` 里的下标。
     *
     * 与 [order] 的区别只有一个、但很致命：[order] 数的是**顶层**图层，渲染器那张表
     * 是**含嵌套**的扁平前序表。带分组的表盘上两者不相等，凡是要告诉渲染器「我说的是
     * 哪一层」的接口（实时变换 `setLiveLayerTransform`、序列帧预览
     * `setEditorAnimatedImageLayer`）都必须用这个值，不能用 [order]。
     */
    val definitionIndex: Int = order,
    /** Studio 2.0 overrides remain a child node in the source document. */
    val ambientAttributes: Map<String, String> = emptyMap(),
)

/**
 * WatchMaker 表盘格式版本。
 *
 * 判据只有一条：根元素有没有 `build_source="studio"`。有就是 Studio 导出的 2.0，
 * 没有就是 1.0。规范没给显式版本号字段，这是官方唯一的区分方式。
 *
 * 两版的实际差异（已核对 `definition/WM_FORMAT_{1.0,2.0}.md` 与真实样本）：
 *  * `video` 图层是 1.0 专属，2.0 序列化器的白名单里没有它。
 *  * Lua 脚本**两版都支持**，2.0 §15 明文保留 WatchMaker 的脚本模型。
 *    只有导出到 Google WFF 时跑不了 Lua，那是导出目标的限制，不是格式的。
 */
enum class WatchFormatVersion {
    V1,
    V2,
    ;

    companion object {
        private const val BUILD_SOURCE_ATTRIBUTE = "build_source"
        private const val STUDIO = "studio"

        /** 从根元素属性表判定版本。缺失或非 studio 都算 1.0。 */
        fun fromRootAttributes(attributes: Map<String, String>): WatchFormatVersion =
            if (attributes[BUILD_SOURCE_ATTRIBUTE]?.equals(STUDIO, ignoreCase = true) == true) {
                V2
            } else {
                V1
            }
    }
}

data class WatchEditorSnapshot(
    val rootName: String,
    val rootAttributes: Map<String, String>,
    val layers: List<WatchEditorLayer>,
    val resourcePaths: List<String>,
    val dirty: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    /** Changes whenever scripts/script.txt content changes, including undo/redo. */
    val globalScriptRevision: String,
) {
    /**
     * 文档的格式版本，从 [rootAttributes] 推出。
     *
     * UI 的版本门禁读这里，不要各自去解 `build_source` 字符串 —— 核心层对图层
     * 类型不设白名单（`addLayer` 只校验非空），门禁只能做在 UI，那就至少让
     * 判据只有一份。
     */
    val formatVersion: WatchFormatVersion
        get() = WatchFormatVersion.fromRootAttributes(rootAttributes)
}

data class WatchEditorExportResult(
    val outputFile: File,
    val bytes: Long,
    val entryCount: Int,
)

/**
 * 草稿里的一份素材。资源管理器逐行显示它。
 *
 * [referenced] 为 false 表示「文档里没有任何图层用到它」——那就是可以安全删掉的孤儿。
 * 字体、脚本、着色器、预览图一律算已引用：它们不是按 `path` 引用的，静态分析看不出来，
 * 误删的代价是一块渲染不出来的表盘。
 */
/**
 * 素材的大类。资源管理器按它决定缩略图怎么出。
 *
 * 按**扩展名**判而不是按所在目录：老表盘把素材堆在包根目录是常见写法（见
 * `draftRelativeAssetPath` 的注释），只看 `images/` 会把这些全归成「其它」。
 */
enum class WatchEditorResourceKind {
    IMAGE,
    VIDEO,
    AUDIO,
    /** 可被 `Typeface` 加载的矢量字体（ttf / otf）。 */
    FONT,
    /** 位图字体描述文件（`.fnt`）—— 是文本，出不了缩略图。 */
    BITMAP_FONT,
    SCRIPT,
    OTHER,
}

data class WatchEditorResourceEntry(
    val relativePath: String,
    val bytes: Long,
    val referenced: Boolean,
) {
    /** 归档里的一级目录（`images` / `videos` / `fonts`…），根目录下的文件归 `""`。 */
    val directory: String get() = relativePath.substringBeforeLast('/', "")

    val fileName: String get() = relativePath.substringAfterLast('/')

    val extension: String get() = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val kind: WatchEditorResourceKind
        get() = when (extension) {
            in IMAGE_EXTENSIONS -> WatchEditorResourceKind.IMAGE
            in VIDEO_EXTENSIONS -> WatchEditorResourceKind.VIDEO
            in AUDIO_EXTENSIONS -> WatchEditorResourceKind.AUDIO
            in FONT_EXTENSIONS -> WatchEditorResourceKind.FONT
            "fnt" -> WatchEditorResourceKind.BITMAP_FONT
            in SCRIPT_EXTENSIONS -> WatchEditorResourceKind.SCRIPT
            else -> WatchEditorResourceKind.OTHER
        }

    private companion object {
        // ppng 是受保护表盘里的加密孪生图，解不开也就出不了缩略图 —— 不列进 IMAGE。
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp")
        val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "wav", "m4a", "aac", "flac")
        val FONT_EXTENSIONS = setOf("ttf", "otf")
        val SCRIPT_EXTENSIONS = setOf("txt", "lua")
    }
}

open class WatchEditorException(message: String, cause: Throwable? = null) : IOException(message, cause)
