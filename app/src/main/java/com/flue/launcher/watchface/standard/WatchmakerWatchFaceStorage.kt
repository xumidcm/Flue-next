package com.flue.launcher.watchface.standard

import android.content.Context
import android.net.Uri
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceType
import java.io.File
import java.io.FileOutputStream

const val WATCHMAKER_ID_PREFIX = "watchmaker:"

data class WatchmakerWatchFaceScanFailure(
    val directoryName: String,
    val message: String,
)

data class WatchmakerWatchFaceScanResult(
    val descriptors: List<WatchFaceDescriptor>,
    val failures: List<WatchmakerWatchFaceScanFailure>,
) {
    val isComplete: Boolean get() = failures.isEmpty()
}

object WatchmakerWatchFaceStorage {
    private const val ROOT_DIRECTORY = "watchmaker_faces"
    private const val MAXIMUM_IMPORT_BYTES = 500L * 1024L * 1024L

    fun rootDirectory(context: Context): File =
        File(context.filesDir, ROOT_DIRECTORY).apply {
            if ((!exists() && !mkdirs()) || !isDirectory) {
                throw WatchArchiveException(
                    WatchArchiveFailureReason.INSTALL_FAILED,
                    "无法创建 .watch 表盘目录",
                )
            }
        }

    fun importArchive(context: Context, uri: Uri): WatchFaceDescriptor {
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
                                ".watch 表盘包超过 500MB 限制",
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

    fun importArchiveFile(
        context: Context,
        archive: File,
        forceNewInstall: Boolean = false,
    ): WatchFaceDescriptor {
        val installed = WatchArchiveInstaller().install(
            archive,
            rootDirectory(context),
            forceNewInstall = forceNewInstall,
        )
        return installed.toDescriptor()
    }

    fun scan(context: Context): List<WatchFaceDescriptor> =
        scanWithDiagnostics(context).descriptors

    fun scanWithDiagnostics(context: Context): WatchmakerWatchFaceScanResult {
        val parser = WatchmakerDefinitionParser()
        val installer = WatchArchiveInstaller()
        val ordered = ArrayList<WatchInstallOrder<WatchFaceDescriptor>>()
        val failures = ArrayList<WatchmakerWatchFaceScanFailure>()
        rootDirectory(context)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .forEach { directory ->
                try {
                    val legacyInstalledAt = directory.lastModified()
                        .takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                    // Persist before repair creates decoded files so migration
                    // cannot move a legacy face to the top of the list.
                    val installedAt = WatchInstallMetadata.readOrCreate(directory, legacyInstalledAt)
                    val watchXml = installer.repairInstalledPackage(directory)
                    val definition = parser.parse(watchXml)
                    val descriptor = definition.toDescriptor(
                        stableId = directory.name,
                        rootDirectory = directory,
                        preferredPreview = previewNextTo(watchXml),
                    )
                    ordered += WatchInstallOrder(
                        value = descriptor,
                        installedAtEpochMs = installedAt,
                        displayName = descriptor.displayName,
                        stableId = descriptor.id,
                    )
                } catch (error: Exception) {
                    failures += WatchmakerWatchFaceScanFailure(
                        directoryName = directory.name.take(128),
                        message = error.message
                            ?.trim()
                            ?.take(512)
                            ?.takeIf(String::isNotEmpty)
                            ?: error.javaClass.simpleName,
                    )
                }
            }
        return WatchmakerWatchFaceScanResult(
            descriptors = orderWatchInstalls(ordered),
            failures = failures,
        )
    }

    fun delete(context: Context, descriptor: WatchFaceDescriptor): Boolean {
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

private fun InstalledWatchArchive.toDescriptor(): WatchFaceDescriptor =
    definition.toDescriptor(stableId, rootDirectory, preview)

private fun WatchmakerDefinition.toDescriptor(
    stableId: String,
    rootDirectory: File,
    preferredPreview: File? = null,
): WatchFaceDescriptor {
    val preview = preferredPreview ?: listOf("preview.jpg", "preview_dim.jpg")
        .asSequence()
        .map { File(rootDirectory, it) }
        .firstOrNull(File::isFile)
    val versionLabel = when (version) {
        WatchmakerDefinitionVersion.FORMAT_1 -> "Watchmaker 1.0"
        WatchmakerDefinitionVersion.FORMAT_2 -> "Watchmaker Studio 2.0"
    }
    return WatchFaceDescriptor(
        id = "$WATCHMAKER_ID_PREFIX$stableId",
        type = WatchFaceType.WATCHMAKER,
        displayName = name.ifBlank { stableId },
        summary = "$versionLabel · ${layers.size} 个图层",
        previewFilePath = preview?.absolutePath,
        sourceDirPath = rootDirectory.absolutePath,
        author = rootAttributes["author"]?.takeIf(String::isNotBlank),
        supportsSettings = true,
    )
}

private fun previewNextTo(watchXml: File): File? =
    listOf("preview.jpg", "preview_dim.jpg")
        .asSequence()
        .map { File(watchXml.parentFile, it) }
        .firstOrNull(File::isFile)
