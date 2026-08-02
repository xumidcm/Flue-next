package com.flue.launcher.watchface.editor

import java.io.File
import java.io.IOException
import java.util.UUID

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

data class WatchEditorLayer(
    val key: WatchEditorLayerKey,
    val id: String?,
    val type: String,
    val parentId: String?,
    val attributes: Map<String, String>,
    val childElementNames: List<String>,
    val order: Int,
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

open class WatchEditorException(message: String, cause: Throwable? = null) : IOException(message, cause)
