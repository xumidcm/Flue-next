package com.flue.launcher.watchface

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

object InternalWatchFaceStorage {
    private const val PHOTO_DIR = "photo"
    private const val VIDEO_DIR = "video"

    fun copyPhoto(context: Context, uri: Uri): String? = copyMedia(context, uri, PHOTO_DIR, "image")

    fun copyVideo(context: Context, uri: Uri): String? = copyMedia(context, uri, VIDEO_DIR, "video")

    private fun copyMedia(
        context: Context,
        uri: Uri,
        folderName: String,
        fallbackExtension: String
    ): String? {
        return runCatching {
            val root = File(context.filesDir, "internal_watchfaces/$folderName").apply { mkdirs() }
            root.listFiles()?.forEach { existing ->
                if (existing.isFile) existing.delete()
            }

            val extension = resolveExtension(context, uri, fallbackExtension)
            val target = File(root, "current.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.absolutePath
        }.getOrNull()
    }

    private fun resolveExtension(context: Context, uri: Uri, fallbackExtension: String): String {
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        if (!fromMime.isNullOrBlank()) return fromMime
        val fromPath = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return fromPath.ifBlank { fallbackExtension }
    }
}
