package com.flue.launcher.watchface.store

import com.flue.launcher.watchface.standard.WatchmakerDefinitionParser
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable compatibility snapshot produced after a community-store package is installed.
 *
 * The report deliberately stores values instead of a parser/runtime object so it can be
 * persisted in the market catalog and shown again after process restart. The remote API id
 * remains the catalog identity; values read from watch.xml are diagnostics only.
 */
data class WatchStoreCompatibilityReport(
    val unsupportedTags: Set<String> = emptySet(),
    val unsupportedLayerTypes: Set<String> = emptySet(),
    val unsupportedExpressions: Set<String> = emptySet(),
    val previewIssue: WatchStorePreviewIssue? = null,
    val inspectionError: String? = null,
    /**
     * True when the package ships a WatchMaker Global Script (`scripts/script.txt`).
     * Flue executes this script inside the same bounded Lua sandbox used by the renderer.
     */
    val hasGlobalScript: Boolean = false,
) {
    init {
        require(unsupportedTags.size <= MAX_ITEMS) { "Too many unsupported watch tags" }
        require(unsupportedLayerTypes.size <= MAX_ITEMS) { "Too many unsupported watch layer types" }
        require(unsupportedExpressions.size <= MAX_ITEMS) { "Too many unsupported watch expressions" }
        require(
            (unsupportedTags + unsupportedLayerTypes + unsupportedExpressions).all {
                it.isNotBlank() && it.length <= MAX_VALUE_LENGTH
            },
        ) { "Invalid watch compatibility value" }
        require(inspectionError == null || inspectionError.length <= MAX_ERROR_LENGTH) {
            "Watch compatibility inspection error is too long"
        }
    }

    val isFullyCompatible: Boolean
        get() = unsupportedTags.isEmpty() &&
            unsupportedLayerTypes.isEmpty() &&
            unsupportedExpressions.isEmpty() &&
            previewIssue == null &&
            inspectionError == null

    val issueCount: Int
        get() = unsupportedTags.size +
            unsupportedLayerTypes.size +
            unsupportedExpressions.size +
            (if (previewIssue != null) 1 else 0) +
            (if (inspectionError != null) 1 else 0)

    internal companion object {
        const val MAX_ITEMS = 128
        const val MAX_VALUE_LENGTH = 512
        const val MAX_ERROR_LENGTH = 512
    }
}

enum class WatchStorePreviewIssue {
    MISSING,
    EMPTY,
    UNSUPPORTED_ENCODING,
}

/** Performs bounded, read-only inspection of a validated installed .watch package. */
object WatchStoreCompatibilityInspector {
    fun inspect(
        rootDirectory: File?,
        previewFile: File?,
    ): WatchStoreCompatibilityReport {
        val previewIssue = inspectPreview(previewFile)
        val root = rootDirectory?.takeIf(File::isDirectory)
            ?: return WatchStoreCompatibilityReport(
                previewIssue = previewIssue,
                inspectionError = "已安装表盘目录不可用",
            )
        val watchXml = findWatchXml(root)
            ?: return WatchStoreCompatibilityReport(
                previewIssue = previewIssue,
                inspectionError = "已安装表盘缺少 watch.xml",
                hasGlobalScript = hasGlobalScript(root, watchXml = null),
            )
        // Detected before parsing so a watch.xml parser failure still reports the
        // Lua incompatibility instead of collapsing into a generic inspection error.
        val hasGlobalScript = hasGlobalScript(root, watchXml)
        return runCatching {
            val runtimeReport = WatchmakerDefinitionParser().parse(watchXml).compatibilityReport
            WatchStoreCompatibilityReport(
                unsupportedTags = runtimeReport.unsupportedTags.boundedValues(),
                unsupportedLayerTypes = runtimeReport.unsupportedLayerTypes.boundedValues(),
                unsupportedExpressions = runtimeReport.unsupportedExpressions.boundedValues(),
                previewIssue = previewIssue,
                hasGlobalScript = hasGlobalScript,
            )
        }.getOrElse { error ->
            WatchStoreCompatibilityReport(
                previewIssue = previewIssue,
                inspectionError = (error.message ?: error.javaClass.simpleName)
                    .trim()
                    .take(WatchStoreCompatibilityReport.MAX_ERROR_LENGTH)
                    .ifBlank { "兼容性检查失败" },
                hasGlobalScript = hasGlobalScript,
            )
        }
    }

    /**
     * Mirrors the renderer's Global Script lookup in
     * `WatchmakerWatchFaceView.prepareSource`: the relative path must equal
     * `scripts/script.txt` (case-insensitive) and the file must be non-empty.
     *
     * The renderer anchors that lookup at the install root only. A package whose
     * watch.xml lives in a subdirectory is still checked against that
     * subdirectory as well: such a script is one WatchMaker would run and Flue
     * would not, which is exactly what the warning is about.
     */
    private fun hasGlobalScript(root: File, watchXml: File?): Boolean = runCatching {
        val anchors = listOfNotNull(root, watchXml?.parentFile)
            .distinctBy { anchor -> runCatching { anchor.canonicalPath }.getOrElse { anchor.absolutePath } }
        anchors.any { anchor ->
            anchor.isDirectory && anchor.walkTopDown()
                .maxDepth(GLOBAL_SCRIPT_DEPTH)
                .filter(File::isFile)
                .any { file ->
                    file.relativeTo(anchor).invariantSeparatorsPath
                        .equals(GLOBAL_SCRIPT_RELATIVE_PATH, ignoreCase = true) &&
                        file.length() > 0L
                }
        }
    }.getOrDefault(false)

    private fun findWatchXml(root: File): File? {
        val direct = File(root, "watch.xml")
        if (direct.isFile) return direct
        return root.walkTopDown()
            .maxDepth(MAX_WATCH_XML_DEPTH)
            .filter(File::isFile)
            .firstOrNull { it.name.equals("watch.xml", ignoreCase = true) }
    }

    private fun inspectPreview(previewFile: File?): WatchStorePreviewIssue? {
        val preview = previewFile?.takeIf(File::isFile) ?: return WatchStorePreviewIssue.MISSING
        if (preview.length() <= 0L) return WatchStorePreviewIssue.EMPTY
        val header = runCatching {
            preview.inputStream().buffered().use { input ->
                ByteArray(PREVIEW_HEADER_BYTES).also { buffer ->
                    val count = input.read(buffer)
                    if (count <= 0) return WatchStorePreviewIssue.EMPTY
                }
            }
        }.getOrNull() ?: return WatchStorePreviewIssue.UNSUPPORTED_ENCODING
        return if (header.isSupportedImageHeader()) null else WatchStorePreviewIssue.UNSUPPORTED_ENCODING
    }

    private fun ByteArray.isSupportedImageHeader(): Boolean {
        val jpeg = size >= 3 &&
            this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()
        val png = size >= 8 &&
            this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
            this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
        val gif = size >= 6 &&
            this[0] == 0x47.toByte() && this[1] == 0x49.toByte() && this[2] == 0x46.toByte()
        val webp = size >= 12 &&
            String(this, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(this, 8, 4, Charsets.US_ASCII) == "WEBP"
        return jpeg || png || gif || webp
    }

    private fun Set<String>.boundedValues(): Set<String> = asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { it.take(WatchStoreCompatibilityReport.MAX_VALUE_LENGTH) }
        .distinct()
        .take(WatchStoreCompatibilityReport.MAX_ITEMS)
        .toCollection(linkedSetOf())

    private const val MAX_WATCH_XML_DEPTH = 8
    private const val PREVIEW_HEADER_BYTES = 12

    /** `scripts/` 只有一层，深度 2 足够；放宽只会白扫整个包。 */
    private const val GLOBAL_SCRIPT_DEPTH = 2
    private const val GLOBAL_SCRIPT_RELATIVE_PATH = "scripts/script.txt"
}

/** Shared bounded JSON codec used by the catalog and crash-recovery journal. */
internal fun WatchStoreCompatibilityReport.toJsonObject(): JSONObject = JSONObject()
    .put("unsupportedTags", unsupportedTags.toJsonArray())
    .put("unsupportedLayerTypes", unsupportedLayerTypes.toJsonArray())
    .put("unsupportedExpressions", unsupportedExpressions.toJsonArray())
    .put("previewIssue", previewIssue?.name ?: JSONObject.NULL)
    .put("inspectionError", inspectionError ?: JSONObject.NULL)
    .put("hasGlobalScript", hasGlobalScript)

internal fun JSONObject.toWatchStoreCompatibilityReport(): WatchStoreCompatibilityReport =
    WatchStoreCompatibilityReport(
        unsupportedTags = readCompatibilityValues("unsupportedTags"),
        unsupportedLayerTypes = readCompatibilityValues("unsupportedLayerTypes"),
        unsupportedExpressions = readCompatibilityValues("unsupportedExpressions"),
        previewIssue = optionalString("previewIssue")?.let { value ->
            WatchStorePreviewIssue.values().firstOrNull { it.name == value }
                ?: error("Unsupported preview compatibility issue: $value")
        },
        inspectionError = optionalString("inspectionError")
            ?.take(WatchStoreCompatibilityReport.MAX_ERROR_LENGTH),
        // 旧目录记录没有这个键，缺省按 false 读 —— 不能让老用户的 catalog 解析失败。
        hasGlobalScript = optBoolean("hasGlobalScript", false),
    )

private fun JSONObject.readCompatibilityValues(key: String): Set<String> {
    val values = optJSONArray(key) ?: return emptySet()
    require(values.length() <= WatchStoreCompatibilityReport.MAX_ITEMS) {
        "Too many watch compatibility values"
    }
    return buildSet(values.length()) {
        for (index in 0 until values.length()) {
            val value = values.optString(index, "").trim()
            require(value.isNotEmpty() && value.length <= WatchStoreCompatibilityReport.MAX_VALUE_LENGTH) {
                "Invalid watch compatibility value"
            }
            add(value)
        }
    }
}

private fun Set<String>.toJsonArray(): JSONArray = JSONArray().apply { sorted().forEach(::put) }

private fun JSONObject.optionalString(key: String): String? =
    optString(key).takeUnless { isNull(key) || it.isBlank() }
