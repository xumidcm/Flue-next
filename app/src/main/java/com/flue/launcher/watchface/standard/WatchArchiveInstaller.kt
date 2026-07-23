package com.flue.launcher.watchface.standard

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class WatchArchiveLimits(
    val maximumArchiveBytes: Long = 32L * 1024L * 1024L,
    val maximumEntryCount: Int = 2_048,
    val maximumEntryNameLength: Int = 240,
    val maximumSingleFileBytes: Long = 24L * 1024L * 1024L,
    val maximumExpandedBytes: Long = 96L * 1024L * 1024L,
    val maximumCompressionRatio: Double = 250.0,
)

enum class WatchArchiveFailureReason {
    ARCHIVE_NOT_FOUND,
    ARCHIVE_TOO_LARGE,
    INVALID_ZIP,
    TOO_MANY_ENTRIES,
    INVALID_ENTRY_NAME,
    DUPLICATE_ENTRY,
    ENTRY_TOO_LARGE,
    EXPANDED_SIZE_TOO_LARGE,
    SUSPICIOUS_COMPRESSION_RATIO,
    MISSING_WATCH_XML,
    AMBIGUOUS_WATCH_XML,
    INVALID_WATCH_XML,
    INSTALL_COLLISION,
    INSTALL_FAILED,
}

class WatchArchiveException(
    val reason: WatchArchiveFailureReason,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

data class WatchArchiveEntry(
    val zipName: String,
    val normalizedName: String,
    val directory: Boolean,
    val declaredSizeBytes: Long,
    val compressedSizeBytes: Long,
)

data class WatchArchiveManifest(
    val archiveSha256: String,
    val entries: List<WatchArchiveEntry>,
    val watchXmlEntry: WatchArchiveEntry,
    val previewEntry: WatchArchiveEntry?,
)

data class InstalledWatchArchive(
    val stableId: String,
    val archiveSha256: String,
    val rootDirectory: File,
    val watchXml: File,
    val preview: File?,
    val definition: WatchmakerDefinition,
)

/**
 * Fail-closed validation for user- and store-supplied WatchMaker archives.
 *
 * Validation is deliberately independent from Android so local imports, store
 * downloads, backup restore and tests all share the exact same trust boundary.
 */
class WatchArchiveValidator(
    private val limits: WatchArchiveLimits = WatchArchiveLimits(),
) {
    fun inspect(archive: File): WatchArchiveManifest {
        if (!archive.isFile) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.ARCHIVE_NOT_FOUND,
                "Watch archive does not exist: ${archive.name}",
            )
        }
        val archiveBytes = archive.length()
        if (archiveBytes <= 0L || archiveBytes > limits.maximumArchiveBytes) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.ARCHIVE_TOO_LARGE,
                "Watch archive size $archiveBytes is outside the allowed budget",
            )
        }

        val entries = try {
            ZipFile(archive).use { zip ->
                val seen = HashSet<String>()
                val fileEntries = HashSet<String>()
                val impliedDirectories = HashSet<String>()
                var declaredExpandedBytes = 0L
                zip.entries().asSequence().mapIndexed { index, entry ->
                    if (index >= limits.maximumEntryCount) {
                        throw WatchArchiveException(
                            WatchArchiveFailureReason.TOO_MANY_ENTRIES,
                            "Watch archive contains more than ${limits.maximumEntryCount} entries",
                        )
                    }
                    val normalized = normalizeEntryName(entry.name, limits.maximumEntryNameLength)
                    // A directory entry and a file entry with the same logical
                    // path ("images/" versus "images") must collide as well.
                    // Letting them through makes extraction order-dependent.
                    val collisionKey = normalized.trimEnd('/').lowercase(Locale.ROOT)
                    if (!seen.add(collisionKey)) {
                        throw WatchArchiveException(
                            WatchArchiveFailureReason.DUPLICATE_ENTRY,
                            "Duplicate watch archive entry: $normalized",
                        )
                    }
                    val segments = collisionKey.split('/')
                    var ancestor = ""
                    for (segmentIndex in 0 until segments.lastIndex) {
                        ancestor = if (ancestor.isEmpty()) {
                            segments[segmentIndex]
                        } else {
                            "$ancestor/${segments[segmentIndex]}"
                        }
                        if (ancestor in fileEntries) {
                            throw WatchArchiveException(
                                WatchArchiveFailureReason.DUPLICATE_ENTRY,
                                "Watch archive file conflicts with child entry: $normalized",
                            )
                        }
                        impliedDirectories += ancestor
                    }
                    if (!entry.isDirectory) {
                        if (collisionKey in impliedDirectories) {
                            throw WatchArchiveException(
                                WatchArchiveFailureReason.DUPLICATE_ENTRY,
                                "Watch archive file conflicts with a directory: $normalized",
                            )
                        }
                        fileEntries += collisionKey
                    }
                    validateDeclaredSize(entry, normalized)
                    if (!entry.isDirectory && entry.size >= 0L) {
                        declaredExpandedBytes = checkedAdd(declaredExpandedBytes, entry.size)
                        if (declaredExpandedBytes > limits.maximumExpandedBytes) {
                            throw WatchArchiveException(
                                WatchArchiveFailureReason.EXPANDED_SIZE_TOO_LARGE,
                                "Watch archive exceeds the expanded-size budget",
                            )
                        }
                    }
                    WatchArchiveEntry(
                        zipName = entry.name,
                        normalizedName = normalized,
                        directory = entry.isDirectory,
                        declaredSizeBytes = entry.size,
                        compressedSizeBytes = entry.compressedSize,
                    )
                }.toList()
            }
        } catch (error: WatchArchiveException) {
            throw error
        } catch (error: Exception) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INVALID_ZIP,
                "Invalid .watch ZIP archive",
                error,
            )
        }

        if (entries.size > limits.maximumEntryCount) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.TOO_MANY_ENTRIES,
                "Watch archive contains too many entries",
            )
        }
        val watchXmlEntries = entries.filter { entry ->
            !entry.directory && entry.normalizedName.substringAfterLast('/')
                .equals(WATCH_XML_FILE_NAME, ignoreCase = true)
        }
        val watchXml = when (watchXmlEntries.size) {
            0 -> throw WatchArchiveException(
                WatchArchiveFailureReason.MISSING_WATCH_XML,
                "The .watch archive does not contain watch.xml",
            )
            1 -> watchXmlEntries.single()
            else -> throw WatchArchiveException(
                WatchArchiveFailureReason.AMBIGUOUS_WATCH_XML,
                "The .watch archive contains multiple watch.xml files",
            )
        }
        val watchParent = watchXml.normalizedName.substringBeforeLast('/', "")
        val preview = entries.firstOrNull { entry ->
            if (entry.directory) return@firstOrNull false
            val parent = entry.normalizedName.substringBeforeLast('/', "")
            val name = entry.normalizedName.substringAfterLast('/')
            parent == watchParent && name.equals(PREVIEW_FILE_NAME, ignoreCase = true)
        }

        return WatchArchiveManifest(
            archiveSha256 = sha256(archive),
            entries = entries,
            watchXmlEntry = watchXml,
            previewEntry = preview,
        )
    }

    private fun validateDeclaredSize(entry: ZipEntry, normalizedName: String) {
        if (entry.isDirectory) return
        if (entry.size > limits.maximumSingleFileBytes) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.ENTRY_TOO_LARGE,
                "Watch archive entry is too large: $normalizedName",
            )
        }
        if (entry.size > 0L && entry.compressedSize == 0L) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.SUSPICIOUS_COMPRESSION_RATIO,
                "Watch archive entry has an invalid compression ratio: $normalizedName",
            )
        }
        if (entry.size > 0L && entry.compressedSize > 0L) {
            val ratio = entry.size.toDouble() / entry.compressedSize.toDouble()
            if (!ratio.isFinite() || ratio > limits.maximumCompressionRatio) {
                throw WatchArchiveException(
                    WatchArchiveFailureReason.SUSPICIOUS_COMPRESSION_RATIO,
                    "Watch archive entry is suspiciously compressed: $normalizedName",
                )
            }
        }
    }
}

/** Installs a validated archive into a content-addressed, immutable directory. */
class WatchArchiveInstaller(
    private val limits: WatchArchiveLimits = WatchArchiveLimits(),
    private val validator: WatchArchiveValidator = WatchArchiveValidator(limits),
    private val parser: WatchmakerDefinitionParser = WatchmakerDefinitionParser(),
) {
    fun install(archive: File, installRoot: File): InstalledWatchArchive {
        val manifest = validator.inspect(archive)
        if (!installRoot.exists() && !installRoot.mkdirs()) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INSTALL_FAILED,
                "Unable to create the watch archive install directory",
            )
        }
        if (!installRoot.isDirectory) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INSTALL_FAILED,
                "Watch archive install root is not a directory",
            )
        }
        val stableId = "watch_${manifest.archiveSha256.take(32)}"
        val target = File(installRoot, stableId)
        if (target.exists()) return loadExisting(target, stableId, manifest.archiveSha256)

        val staging = File(installRoot, ".install-${UUID.randomUUID()}")
        if (!staging.mkdirs()) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INSTALL_FAILED,
                "Unable to create watch archive staging directory",
            )
        }
        try {
            // The import URI is normally copied to an app-private temp file,
            // but install(File, ...) is also a public seam. Recheck the
            // content-address hash before and after extraction so a caller
            // cannot swap the archive beneath the validator and install a
            // payload under the wrong stable id.
            if (sha256(archive) != manifest.archiveSha256) {
                throw WatchArchiveException(
                    WatchArchiveFailureReason.INVALID_ZIP,
                    "Watch archive changed after validation",
                )
            }
            extractValidated(archive, manifest, staging)
            if (sha256(archive) != manifest.archiveSha256) {
                throw WatchArchiveException(
                    WatchArchiveFailureReason.INVALID_ZIP,
                    "Watch archive changed during installation",
                )
            }
            val stagedWatchXml = File(staging, manifest.watchXmlEntry.normalizedName)
            val definition = try {
                parser.parse(stagedWatchXml)
            } catch (error: Exception) {
                throw WatchArchiveException(
                    WatchArchiveFailureReason.INVALID_WATCH_XML,
                    "watch.xml could not be parsed: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }
            if (target.exists()) return loadExisting(target, stableId, manifest.archiveSha256)
            // Move the complete validated archive tree. Moving only the
            // watch.xml parent silently discarded shared assets when a valid
            // archive used a wrapper directory plus root-level resources.
            moveDirectory(staging, target)
            val installedWatchXml = File(target, manifest.watchXmlEntry.normalizedName)
                .takeIf(File::isFile)
                ?: throw WatchArchiveException(
                    WatchArchiveFailureReason.INSTALL_FAILED,
                    "Installed watch.xml is missing",
                )
            val installedPreview = manifest.previewEntry?.let {
                File(target, it.normalizedName).takeIf(File::isFile)
            }
            return InstalledWatchArchive(
                stableId = stableId,
                archiveSha256 = manifest.archiveSha256,
                rootDirectory = target,
                watchXml = installedWatchXml,
                preview = installedPreview,
                definition = definition,
            )
        } catch (error: WatchArchiveException) {
            throw error
        } catch (error: Exception) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INSTALL_FAILED,
                "Unable to install .watch archive",
                error,
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun loadExisting(
        target: File,
        stableId: String,
        archiveSha256: String,
    ): InstalledWatchArchive {
        val watchXml = target.walkTopDown().firstOrNull {
            it.isFile && it.name.equals(WATCH_XML_FILE_NAME, ignoreCase = true)
        } ?: throw WatchArchiveException(
            WatchArchiveFailureReason.INSTALL_COLLISION,
            "The content-addressed watch directory is incomplete",
        )
        val definition = try {
            parser.parse(watchXml)
        } catch (error: Exception) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INSTALL_COLLISION,
                "The content-addressed watch directory has an invalid watch.xml",
                error,
            )
        }
        val preview = File(watchXml.parentFile, PREVIEW_FILE_NAME).takeIf(File::isFile)
        return InstalledWatchArchive(
            stableId = stableId,
            archiveSha256 = archiveSha256,
            rootDirectory = target,
            watchXml = watchXml,
            preview = preview,
            definition = definition,
        )
    }

    private fun extractValidated(
        archive: File,
        manifest: WatchArchiveManifest,
        staging: File,
    ) {
        val expected = manifest.entries.associateBy { it.zipName }
        val extracted = HashSet<String>(expected.size)
        var expandedBytes = 0L
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val validated = expected[entry.name] ?: throw WatchArchiveException(
                    WatchArchiveFailureReason.INVALID_ZIP,
                    "Archive changed while it was being installed",
                )
                if (!extracted.add(entry.name) ||
                    validated.declaredSizeBytes != entry.size ||
                    validated.compressedSizeBytes != entry.compressedSize
                ) {
                    throw WatchArchiveException(
                        WatchArchiveFailureReason.INVALID_ZIP,
                        "Archive entry changed while it was being installed: ${entry.name}",
                    )
                }
                val output = File(staging, validated.normalizedName).canonicalFile
                val canonicalStaging = staging.canonicalFile
                val insideStaging = output.path == canonicalStaging.path ||
                    output.path.startsWith(canonicalStaging.path + File.separator)
                if (!insideStaging) {
                    throw WatchArchiveException(
                        WatchArchiveFailureReason.INVALID_ENTRY_NAME,
                        "Archive entry escapes the staging directory",
                    )
                }
                if (validated.directory) {
                    if (!output.exists() && !output.mkdirs()) {
                        throw WatchArchiveException(
                            WatchArchiveFailureReason.INSTALL_FAILED,
                            "Unable to create archive directory",
                        )
                    }
                    return@forEach
                }
                output.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw WatchArchiveException(
                            WatchArchiveFailureReason.INSTALL_FAILED,
                            "Unable to create archive parent directory",
                        )
                    }
                }
                zip.getInputStream(entry).use { rawInput ->
                    BufferedInputStream(rawInput).use { input ->
                        BufferedOutputStream(FileOutputStream(output)).use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var fileBytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                fileBytes = checkedAdd(fileBytes, count.toLong())
                                expandedBytes = checkedAdd(expandedBytes, count.toLong())
                                if (fileBytes > limits.maximumSingleFileBytes) {
                                    throw WatchArchiveException(
                                        WatchArchiveFailureReason.ENTRY_TOO_LARGE,
                                        "Expanded archive entry exceeds the file budget",
                                    )
                                }
                                if (expandedBytes > limits.maximumExpandedBytes) {
                                    throw WatchArchiveException(
                                        WatchArchiveFailureReason.EXPANDED_SIZE_TOO_LARGE,
                                        "Expanded watch archive exceeds the total budget",
                                    )
                                }
                                sink.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
        }
        if (extracted.size != expected.size) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INVALID_ZIP,
                "Archive entries changed while it was being installed",
            )
        }
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }
}

private fun normalizeEntryName(rawName: String, maximumLength: Int): String {
    val slashName = rawName.replace('\\', '/').trim()
    if (
        slashName.isBlank() ||
        slashName.length > maximumLength ||
        slashName.startsWith('/') ||
        slashName.startsWith("//") ||
        slashName.contains('\u0000') ||
        DRIVE_PREFIX.matches(slashName)
    ) {
        throw WatchArchiveException(
            WatchArchiveFailureReason.INVALID_ENTRY_NAME,
            "Invalid watch archive entry name",
        )
    }
    val directorySuffix = slashName.endsWith('/')
    val segments = slashName.split('/').filter(String::isNotEmpty)
    if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
        throw WatchArchiveException(
            WatchArchiveFailureReason.INVALID_ENTRY_NAME,
            "Watch archive entry contains an unsafe path segment",
        )
    }
    val normalized = segments.joinToString("/") + if (directorySuffix) "/" else ""
    if (normalized.length > maximumLength) {
        throw WatchArchiveException(
            WatchArchiveFailureReason.INVALID_ENTRY_NAME,
            "Watch archive entry name is too long",
        )
    }
    return normalized
}

private fun checkedAdd(left: Long, right: Long): Long {
    if (right > 0L && left > Long.MAX_VALUE - right) {
        throw WatchArchiveException(
            WatchArchiveFailureReason.EXPANDED_SIZE_TOO_LARGE,
            "Watch archive size overflow",
        )
    }
    return left + right
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private val DRIVE_PREFIX = Regex("^[A-Za-z]:.*")
private const val WATCH_XML_FILE_NAME = "watch.xml"
private const val PREVIEW_FILE_NAME = "preview.jpg"
