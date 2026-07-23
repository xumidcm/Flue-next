package com.flue.launcher.watchface.standard

import android.content.Context
import android.net.Uri
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceType
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

const val WATCHMAKER_ID_PREFIX = "watchmaker:"

object WatchmakerWatchFaceStorage {
    private const val ROOT_DIRECTORY = "watchmaker_faces"
    private const val MAXIMUM_IMPORT_BYTES = 32L * 1024L * 1024L

    fun rootDirectory(context: Context): File =
        File(context.filesDir, ROOT_DIRECTORY).apply {
            if ((!exists() && !mkdirs()) || !isDirectory) {
                throw WatchArchiveException(
                    WatchArchiveFailureReason.INSTALL_FAILED,
                    "无法创建 .watch 表盘目录",
                )
            }
        }

    fun importArchive(context: Context, uri: Uri): LunchWatchFaceDescriptor {
        val temporary = File.createTempFile("watchmaker_import_", ".watch", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAXIMUM_IMPORT_BYTES) {
                            throw WatchArchiveException(
                                WatchArchiveFailureReason.ARCHIVE_TOO_LARGE,
                                ".watch 表盘包超过 32MB 限制",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw WatchArchiveException(
                WatchArchiveFailureReason.ARCHIVE_NOT_FOUND,
                "无法读取 .watch 表盘包",
            )
            return importArchiveFile(context, temporary)
        } finally {
            temporary.delete()
        }
    }

    fun importArchiveFile(context: Context, archive: File): LunchWatchFaceDescriptor {
        val installed = WatchArchiveInstaller().install(archive, rootDirectory(context))
        return installed.toDescriptor()
    }

    fun scan(context: Context): List<LunchWatchFaceDescriptor> {
        val parser = WatchmakerDefinitionParser()
        return rootDirectory(context)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { directory ->
                runCatching {
                    val watchXml = File(directory, "watch.xml").takeIf(File::isFile)
                        ?: directory.walkTopDown().firstOrNull {
                            it.isFile && it.name.equals("watch.xml", ignoreCase = true)
                        }
                        ?: return@runCatching null
                    val definition = parser.parse(watchXml)
                    definition.toDescriptor(
                        stableId = directory.name,
                        rootDirectory = directory,
                        preferredPreview = previewNextTo(watchXml),
                    )
                }.getOrNull()
            }
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }
            .toList()
    }

    fun delete(context: Context, descriptor: LunchWatchFaceDescriptor): Boolean {
        if (!descriptor.isWatchmaker) return false
        val root = rootDirectory(context).canonicalFile
        val source = descriptor.sourceDirPath?.let(::File)?.canonicalFile ?: return false
        val installDirectory = generateSequence(source) { it.parentFile }
            .firstOrNull { it.parentFile?.canonicalFile == root }
            ?: return false
        if (!installDirectory.exists() || installDirectory.parentFile?.canonicalFile != root) return false
        return installDirectory.deleteRecursively()
    }
}

private fun InstalledWatchArchive.toDescriptor(): LunchWatchFaceDescriptor =
    definition.toDescriptor(stableId, rootDirectory, preview)

private fun WatchmakerDefinition.toDescriptor(
    stableId: String,
    rootDirectory: File,
    preferredPreview: File? = null,
): LunchWatchFaceDescriptor {
    val preview = preferredPreview ?: listOf("preview.jpg", "preview_dim.jpg")
        .asSequence()
        .map { File(rootDirectory, it) }
        .firstOrNull(File::isFile)
    val versionLabel = when (version) {
        WatchmakerDefinitionVersion.FORMAT_1 -> "Watchmaker 1.0"
        WatchmakerDefinitionVersion.FORMAT_2 -> "Watchmaker Studio 2.0"
    }
    return LunchWatchFaceDescriptor(
        id = "$WATCHMAKER_ID_PREFIX$stableId",
        type = LunchWatchFaceType.WATCHMAKER,
        displayName = name.ifBlank { stableId },
        summary = "$versionLabel · ${layers.size} 个图层",
        previewFilePath = preview?.absolutePath,
        sourceDirPath = rootDirectory.absolutePath,
        watchFaceName = stableId,
        author = rootAttributes["author"]?.takeIf(String::isNotBlank),
    )
}

private fun previewNextTo(watchXml: File): File? =
    listOf("preview.jpg", "preview_dim.jpg")
        .asSequence()
        .map { File(watchXml.parentFile, it) }
        .firstOrNull(File::isFile)
