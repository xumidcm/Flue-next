package com.flue.launcher.watchface.editor

import java.util.Locale

/** Image containers accepted by the editor's lossless import path. */
enum class WatchEditorImageFormat(
    val preferredExtension: String?,
    val acceptedExtensions: Set<String>,
) {
    GIF("gif", setOf("gif")),
    PNG("png", setOf("png")),
    JPEG("jpg", setOf("jpg", "jpeg")),
    WEBP("webp", setOf("webp")),
    BMP("bmp", setOf("bmp")),
    UNKNOWN(null, emptySet()),
}

data class WatchEditorImageSourceInfo(
    val displayName: String,
    val mimeType: String?,
    val format: WatchEditorImageFormat,
) {
    val isGif: Boolean get() = format == WatchEditorImageFormat.GIF
    val importName: String get() = watchEditorImageImportName(displayName, format)
}

/**
 * Detects the real image container. Header signatures win over provider MIME
 * and filename because document providers frequently report GIFs as a generic
 * image (or even as PNG), which would otherwise destroy the animation during
 * the crop/re-encode step.
 */
fun detectWatchEditorImageFormat(
    displayName: String?,
    mimeType: String?,
    header: ByteArray,
): WatchEditorImageFormat {
    if (header.startsWithAscii("GIF87a") || header.startsWithAscii("GIF89a")) {
        return WatchEditorImageFormat.GIF
    }
    if (header.startsWithBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
        return WatchEditorImageFormat.PNG
    }
    if (header.startsWithBytes(0xFF, 0xD8, 0xFF)) return WatchEditorImageFormat.JPEG
    if (header.startsWithAscii("RIFF") && header.asciiAt(8, "WEBP")) {
        return WatchEditorImageFormat.WEBP
    }
    if (header.startsWithAscii("BM")) return WatchEditorImageFormat.BMP

    return when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "image/gif" -> WatchEditorImageFormat.GIF
        "image/png" -> WatchEditorImageFormat.PNG
        "image/jpeg", "image/jpg" -> WatchEditorImageFormat.JPEG
        "image/webp" -> WatchEditorImageFormat.WEBP
        "image/bmp", "image/x-ms-bmp" -> WatchEditorImageFormat.BMP
        else -> when (displayName.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "gif" -> WatchEditorImageFormat.GIF
            "png" -> WatchEditorImageFormat.PNG
            "jpg", "jpeg" -> WatchEditorImageFormat.JPEG
            "webp" -> WatchEditorImageFormat.WEBP
            "bmp" -> WatchEditorImageFormat.BMP
            else -> WatchEditorImageFormat.UNKNOWN
        }
    }
}

/** Keeps the original name while ensuring its extension matches the detected container. */
fun watchEditorImageImportName(
    displayName: String?,
    format: WatchEditorImageFormat,
): String {
    val leaf = displayName.orEmpty().replace('\\', '/').substringAfterLast('/').ifBlank { "image" }
    val extension = leaf.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (format == WatchEditorImageFormat.UNKNOWN) {
        return if (extension.isBlank()) "$leaf.bin" else leaf
    }
    if (extension in format.acceptedExtensions) return leaf
    val stem = leaf.substringBeforeLast('.', leaf).ifBlank { "image" }
    return "$stem.${format.preferredExtension}"
}

private fun ByteArray.startsWithAscii(value: String): Boolean = asciiAt(0, value)

private fun ByteArray.asciiAt(offset: Int, value: String): Boolean {
    if (offset < 0 || size - offset < value.length) return false
    return value.indices.all { index -> this[offset + index].toInt() and 0xFF == value[index].code }
}

private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
    if (size < values.size) return false
    return values.indices.all { index -> this[index].toInt() and 0xFF == values[index] }
}
