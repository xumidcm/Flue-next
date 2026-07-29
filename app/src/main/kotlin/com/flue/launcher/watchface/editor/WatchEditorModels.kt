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

data class WatchEditorSnapshot(
    val rootName: String,
    val rootAttributes: Map<String, String>,
    val layers: List<WatchEditorLayer>,
    val resourcePaths: List<String>,
    val dirty: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
)

data class WatchEditorExportResult(
    val outputFile: File,
    val bytes: Long,
    val entryCount: Int,
)

class WatchEditorException(message: String, cause: Throwable? = null) : IOException(message, cause)
