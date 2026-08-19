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
    val maximumArchiveBytes: Long = 500L * 1024L * 1024L,
    val maximumEntryCount: Int = 2_048,
    val maximumEntryNameLength: Int = 240,
    val maximumXmlOrScriptBytes: Long = 4L * 1024L * 1024L,
    val maximumFontBytes: Long = 450L * 1024L * 1024L,
    val maximumImageBytes: Long = 450L * 1024L * 1024L,
    val maximumMediaBytes: Long = 450L * 1024L * 1024L,
    val maximumOtherFileBytes: Long = 450L * 1024L * 1024L,
    val maximumExpandedBytes: Long = 500L * 1024L * 1024L,
    val maximumCompressionRatio: Double = 250.0,
) {
    init {
        require(maximumArchiveBytes > 0L)
        require(maximumEntryCount > 0)
        require(maximumEntryNameLength > 0)
        require(maximumXmlOrScriptBytes > 0L)
        require(maximumFontBytes > 0L)
        require(maximumImageBytes > 0L)
        require(maximumMediaBytes > 0L)
        require(maximumOtherFileBytes > 0L)
        require(maximumExpandedBytes > 0L)
        require(maximumCompressionRatio > 0.0 && maximumCompressionRatio.isFinite())
    }

    fun maximumBytesFor(kind: WatchArchiveEntryKind): Long = when (kind) {
        WatchArchiveEntryKind.XML_OR_SCRIPT -> maximumXmlOrScriptBytes
        WatchArchiveEntryKind.FONT -> maximumFontBytes
        WatchArchiveEntryKind.IMAGE -> maximumImageBytes
        WatchArchiveEntryKind.MEDIA -> maximumMediaBytes
        WatchArchiveEntryKind.OTHER -> maximumOtherFileBytes
    }
}

enum class WatchArchiveEntryKind {
    XML_OR_SCRIPT,
    FONT,
    IMAGE,
    MEDIA,
    OTHER,
}

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
    MISSING_PROTECTED_DEFINITION,
    INVALID_WATCH_XML,
    INVALID_PROTECTED_ASSET,
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
    val kind: WatchArchiveEntryKind,
)

data class WatchArchiveManifest(
    val archiveSha256: String,
    val entries: List<WatchArchiveEntry>,
    val watchXmlEntry: WatchArchiveEntry,
    val protectedDefinitionEntry: WatchArchiveEntry?,
    val protected: Boolean,
    val previewEntry: WatchArchiveEntry?,
)

data class InstalledWatchArchive(
    val stableId: String,
    val archiveSha256: String,
    val rootDirectory: File,
    val watchXml: File,
    val preview: File?,
    val definition: WatchmakerDefinition,
    val installedAtEpochMs: Long,
)

interface WatchPackageLoader {
    fun inspect(source: File): WatchArchiveManifest
    fun prepare(source: File, installRoot: File): InstalledWatchArchive
}

/**
 * Fail-closed validation for user- and store-supplied WatchMaker archives.
 *
 * Validation is deliberately independent from Android so local imports, store
 * downloads, backup restore and tests all share the exact same trust boundary.
 */
class WatchArchiveValidator(
    private val limits: WatchArchiveLimits = WatchArchiveLimits(),
    private val parser: WatchmakerDefinitionParser = WatchmakerDefinitionParser(),
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
                    if (WatchInstallMetadata.isReservedArchivePath(normalized)) {
                        throw WatchArchiveException(
                            WatchArchiveFailureReason.INVALID_ENTRY_NAME,
                            "Watch archive entry uses a reserved Flue metadata path",
                        )
                    }
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
                    val directory = entry.isDirectory || normalized.endsWith('/')
                    if (!directory) {
                        if (collisionKey in impliedDirectories) {
                            throw WatchArchiveException(
                                WatchArchiveFailureReason.DUPLICATE_ENTRY,
                                "Watch archive file conflicts with a directory: $normalized",
                            )
                        }
                        fileEntries += collisionKey
                    }
                    val kind = classifyEntry(normalized)
                    validateDeclaredSize(entry, normalized, kind)
                    if (!directory && entry.size >= 0L) {
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
                        directory = directory,
                        declaredSizeBytes = entry.size,
                        compressedSizeBytes = entry.compressedSize,
                        kind = kind,
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
        val definitionInspection = inspectDefinition(archive, entries, watchXml, watchParent)
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
            protectedDefinitionEntry = definitionInspection.protectedDefinition,
            protected = definitionInspection.protected,
            previewEntry = preview,
        )
    }

    private fun inspectDefinition(
        archive: File,
        entries: List<WatchArchiveEntry>,
        watchXml: WatchArchiveEntry,
        watchParent: String,
    ): DefinitionInspection {
        return try {
            ZipFile(archive).use { zip ->
                val shellEntry = zip.getEntry(watchXml.zipName)
                    ?: throw WatchArchiveException(
                        WatchArchiveFailureReason.INVALID_ZIP,
                        "watch.xml disappeared while the archive was inspected",
                    )
                val shellDefinition = zip.getInputStream(shellEntry).use { input ->
                    parser.parse(input, limits.maximumXmlOrScriptBytes)
                }
                val protected = shellDefinition.rootAttributes.valueForIgnoreCase("protection")
                    .isWatchmakerProtectionEnabled()
                if (!protected) return DefinitionInspection(false, null)

                val localProtectedDefinitions = entries.filter { entry ->
                    !entry.directory &&
                        entry.normalizedName.substringBeforeLast('/', "") == watchParent &&
                        entry.normalizedName.substringAfterLast('/')
                            .equals(PROTECTED_XML_FILE_NAME, ignoreCase = true)
                }
                val allProtectedDefinitions = entries.filter { entry ->
                        !entry.directory &&
                            entry.normalizedName.substringAfterLast('/')
                                .equals(PROTECTED_XML_FILE_NAME, ignoreCase = true)
                    }
                val protectedDefinition = when {
                    localProtectedDefinitions.size == 1 -> localProtectedDefinitions.single()
                    localProtectedDefinitions.size > 1 -> throw WatchArchiveException(
                        WatchArchiveFailureReason.MISSING_PROTECTED_DEFINITION,
                        "Protected .watch archive contains multiple watch.pxml files",
                    )
                    allProtectedDefinitions.size == 1 -> allProtectedDefinitions.single()
                    allProtectedDefinitions.size > 1 -> throw WatchArchiveException(
                        WatchArchiveFailureReason.MISSING_PROTECTED_DEFINITION,
                        "Protected .watch archive contains multiple watch.pxml files",
                    )
                    shellDefinition.layers.isNotEmpty() -> return DefinitionInspection(false, null)
                    else -> throw WatchArchiveException(
                        WatchArchiveFailureReason.MISSING_PROTECTED_DEFINITION,
                        "Protected .watch archive is missing watch.pxml",
                    )
                }
                val zipEntry = zip.getEntry(protectedDefinition.zipName)
                    ?: throw WatchArchiveException(
                        WatchArchiveFailureReason.INVALID_ZIP,
                        "watch.pxml disappeared while the archive was inspected",
                    )
                zip.getInputStream(zipEntry).use { input ->
                    WatchmakerProtection.openDecodedXml(input).use { decoded ->
                        parser.parse(decoded, limits.maximumXmlOrScriptBytes)
                    }
                }
                DefinitionInspection(true, protectedDefinition)
            }
        } catch (error: WatchArchiveException) {
            throw error
        } catch (error: Exception) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INVALID_WATCH_XML,
                "watch.xml could not be parsed: ${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
    }

    private fun validateDeclaredSize(
        entry: ZipEntry,
        normalizedName: String,
        kind: WatchArchiveEntryKind,
    ) {
        if (entry.isDirectory) return
        if (entry.size < -1L || entry.compressedSize < -1L) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INVALID_ZIP,
                "Watch archive entry has an invalid size: $normalizedName",
            )
        }
        val maximumBytes = limits.maximumBytesFor(kind)
        if (entry.size > maximumBytes) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.ENTRY_TOO_LARGE,
                "Watch archive ${kind.name.lowercase(Locale.ROOT)} entry is too large: $normalizedName",
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

    private data class DefinitionInspection(
        val protected: Boolean,
        val protectedDefinition: WatchArchiveEntry?,
    )
}

/** Installs a validated archive into a content-addressed, immutable directory. */
class WatchArchiveInstaller(
    private val limits: WatchArchiveLimits = WatchArchiveLimits(),
    private val validator: WatchArchiveValidator = WatchArchiveValidator(limits),
    private val parser: WatchmakerDefinitionParser = WatchmakerDefinitionParser(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : WatchPackageLoader {
    override fun inspect(source: File): WatchArchiveManifest = validator.inspect(source)

    override fun prepare(source: File, installRoot: File): InstalledWatchArchive =
        install(source, installRoot)

    fun install(archive: File, installRoot: File): InstalledWatchArchive {
        val manifest = validator.inspect(archive)
        // mkdirs() returns false when another installer won the race and
        // created the root between exists() and mkdirs(). Only reject when
        // the path still is not a directory after the race.
        if (!installRoot.exists() && !installRoot.mkdirs() && !installRoot.isDirectory) {
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
        return WatchPackageTransactionMutex.withTarget(target) {
            if (target.exists()) return@withTarget loadExisting(target, stableId, manifest.archiveSha256)

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
                val stagedWatchXml = prepareExtractedPackage(
                    root = staging,
                    watchXml = File(staging, manifest.watchXmlEntry.normalizedName),
                    protectedDefinition = manifest.protectedDefinitionEntry?.let { entry ->
                        File(staging, entry.normalizedName)
                    },
                    protected = manifest.protected,
                )
                val definition = try {
                    parser.parse(stagedWatchXml)
                } catch (error: Exception) {
                    throw WatchArchiveException(
                        WatchArchiveFailureReason.INVALID_WATCH_XML,
                        "watch.xml could not be parsed: ${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
                val installedAtEpochMs = WatchInstallMetadata.readOrCreate(staging, nowEpochMillis())
                // This is the durable commit point. A scan will only trust a
                // marker written after every asset and the definition parsed.
                WatchPreparedMarker.write(staging, stagedWatchXml)
                // The mutex closes the normal check/move race. Keep the
                // collision fallback for a package created by another process.
                try {
                    // Move the complete validated archive tree. Moving only the
                    // watch.xml parent silently discarded shared assets when a
                    // valid archive used a wrapper directory plus root-level resources.
                    moveDirectory(staging, target)
                } catch (error: java.nio.file.FileAlreadyExistsException) {
                    if (target.isDirectory) {
                        return@withTarget loadExisting(target, stableId, manifest.archiveSha256)
                    }
                    throw error
                }
                val installedWatchXml = File(target, manifest.watchXmlEntry.normalizedName)
                    .takeIf(File::isFile)
                    ?: throw WatchArchiveException(
                        WatchArchiveFailureReason.INSTALL_FAILED,
                        "Installed watch.xml is missing",
                    )
                val installedPreview = manifest.previewEntry?.let {
                    File(target, it.normalizedName).takeIf(File::isFile)
                }
                return@withTarget InstalledWatchArchive(
                    stableId = stableId,
                    archiveSha256 = manifest.archiveSha256,
                    rootDirectory = target,
                    watchXml = installedWatchXml,
                    preview = installedPreview,
                    definition = definition,
                    installedAtEpochMs = installedAtEpochMs,
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
    }

    /** Repairs a package installed by a previous parser version and returns its real watch.xml. */
    fun repairInstalledPackage(root: File): File {
        require(root.isDirectory) { "Watch package directory does not exist" }
        return WatchPackageTransactionMutex.withTarget(root) {
            prepareExistingPackage(root)
        }
    }

    private fun loadExisting(
        target: File,
        stableId: String,
        archiveSha256: String,
    ): InstalledWatchArchive {
        val legacyInstalledAt = target.lastModified().takeIf { it > 0L } ?: nowEpochMillis()
        val installedAtEpochMs = WatchInstallMetadata.readOrCreate(target, legacyInstalledAt)
        val preparedWatchXml = try {
            prepareExistingPackage(target)
        } catch (error: Exception) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INSTALL_COLLISION,
                "The existing protected watch package could not be repaired",
                error,
            )
        }
        val definition = try {
            parser.parse(preparedWatchXml)
        } catch (error: Exception) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INSTALL_COLLISION,
                "The content-addressed watch directory has an invalid watch.xml",
                error,
            )
        }
        val preview = File(preparedWatchXml.parentFile, PREVIEW_FILE_NAME).takeIf(File::isFile)
        return InstalledWatchArchive(
            stableId = stableId,
            archiveSha256 = archiveSha256,
            rootDirectory = target,
            watchXml = preparedWatchXml,
            preview = preview,
            definition = definition,
            installedAtEpochMs = installedAtEpochMs,
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
                                if (fileBytes > limits.maximumBytesFor(validated.kind)) {
                                    throw WatchArchiveException(
                                        WatchArchiveFailureReason.ENTRY_TOO_LARGE,
                                        "Expanded ${validated.kind.name.lowercase(Locale.ROOT)} entry exceeds its file budget",
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

    /**
     * Converts a protected package in the staging tree before it becomes
     * visible under its content-addressed install id. Every decoded XML is
     * parsed through the same secure parser before the shell is replaced.
     */
    private fun prepareExtractedPackage(
        root: File,
        watchXml: File,
        protectedDefinition: File?,
        protected: Boolean,
    ): File {
        require(watchXml.isFile) { "Extracted watch.xml is missing" }
        if (protected) {
            val pxml = protectedDefinition
                ?: throw WatchArchiveException(
                    WatchArchiveFailureReason.MISSING_PROTECTED_DEFINITION,
                    "Protected .watch archive is missing watch.pxml",
                )
            if (!pxml.isFile) {
                throw WatchArchiveException(
                    WatchArchiveFailureReason.MISSING_PROTECTED_DEFINITION,
                    "Extracted watch.pxml is missing",
                )
            }
            val decoded = File(watchXml.parentFile, ".watch.xml.decoded-${UUID.randomUUID()}")
            try {
                WatchmakerProtection.decodeXmlToFile(
                    source = pxml,
                    destination = decoded,
                    maximumDecodedBytes = limits.maximumXmlOrScriptBytes,
                )
                try {
                    parser.parse(decoded)
                } catch (error: Exception) {
                    throw WatchArchiveException(
                        WatchArchiveFailureReason.INVALID_WATCH_XML,
                        "Decoded watch.pxml could not be parsed: ${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
                replaceFile(decoded, watchXml)
            } finally {
                if (decoded.exists()) decoded.delete()
            }
        }
        decodeProtectedAssets(root)
        return watchXml
    }

    /** Repairs packages installed by the old parser (shell watch.xml + pxml). */
    private fun prepareExistingPackage(root: File): File {
        // A current marker is the only hot-path lookup. Do not walk the
        // package or inspect every protected image on each launcher scan.
        WatchPreparedMarker.read(root)?.let { return it }

        val watchXmlCandidates = root.walkTopDown()
            .filter { it.isFile && it.name.equals(WATCH_XML_FILE_NAME, ignoreCase = true) }
            .toList()
        val watchXml = when (watchXmlCandidates.size) {
            0 -> throw WatchArchiveException(
                WatchArchiveFailureReason.MISSING_WATCH_XML,
                "Installed watch package is missing watch.xml",
            )
            1 -> watchXmlCandidates.single()
            else -> throw WatchArchiveException(
                WatchArchiveFailureReason.AMBIGUOUS_WATCH_XML,
                "Installed watch package contains multiple watch.xml files",
            )
        }
        val shell = runCatching { parser.parse(watchXml) }.getOrElse { error ->
            throw error
        }
        // A current package has a marker and returned above. A unique pxml is
        // authoritative even when the shell contains placeholder layers. Some
        // already-decoded legacy definitions retain protection="y" after the
        // pxml was removed, so populated XML without pxml remains valid.
        val protectionDeclared = shell.rootAttributes.valueForIgnoreCase("protection")
            .isWatchmakerProtectionEnabled()
        val pxml = if (protectionDeclared) {
            val local = watchXml.parentFile?.listFiles().orEmpty()
                .filter { it.isFile && it.name.equals(PROTECTED_XML_FILE_NAME, true) }
            val all = root.walkTopDown()
                    .filter { it.isFile && it.name.equals(PROTECTED_XML_FILE_NAME, true) }
                    .toList()
            when {
                local.size == 1 -> local.single()
                local.size > 1 -> throw WatchArchiveException(
                    WatchArchiveFailureReason.MISSING_PROTECTED_DEFINITION,
                    "Installed protected package contains multiple watch.pxml files",
                )
                all.size == 1 -> all.single()
                all.size > 1 -> throw WatchArchiveException(
                    WatchArchiveFailureReason.MISSING_PROTECTED_DEFINITION,
                    "Installed protected package contains multiple watch.pxml files",
                )
                shell.layers.isNotEmpty() -> null
                else -> throw WatchArchiveException(
                    WatchArchiveFailureReason.MISSING_PROTECTED_DEFINITION,
                    "Installed protected package is missing watch.pxml",
                )
            }
        } else {
            null
        }
        val protected = pxml != null
        val prepared = prepareExtractedPackage(root, watchXml, pxml, protected)
        // Parse once more after replacement and only then publish the marker;
        // a crash before this point leaves a repairable legacy package.
        try {
            parser.parse(prepared)
        } catch (error: Exception) {
            throw WatchArchiveException(
                WatchArchiveFailureReason.INVALID_WATCH_XML,
                "Prepared watch.xml could not be parsed: ${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
        WatchPreparedMarker.write(root, prepared)
        return prepared
    }

    private fun decodeProtectedAssets(root: File) {
        root.walkTopDown().filter(File::isFile).forEach { file ->
            val extension = file.extension.lowercase(Locale.ROOT)
            if (extension != "ppng" && extension != "pjpg" && extension != "pjpeg") return@forEach
            try {
                WatchmakerProtection.decodeProtectedAssetInPlace(
                    file,
                    limits.maximumBytesFor(WatchArchiveEntryKind.IMAGE),
                )
            } catch (error: Exception) {
                throw WatchArchiveException(
                    WatchArchiveFailureReason.INVALID_PROTECTED_ASSET,
                    "Protected image could not be decoded: ${file.name}",
                    error,
                )
            }
        }
    }

    private fun replaceFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

private fun classifyEntry(normalizedName: String): WatchArchiveEntryKind {
    val path = normalizedName.trimEnd('/').lowercase(Locale.ROOT)
    val extension = path.substringAfterLast('.', "")
    return when {
        path.startsWith("scripts/") ||
            extension in setOf("xml", "pxml", "lua", "js", "json") ->
            WatchArchiveEntryKind.XML_OR_SCRIPT
        path.startsWith("fonts/") || extension in setOf("ttf", "otf", "ttc") ->
            WatchArchiveEntryKind.FONT
        path.startsWith("images/") ||
            extension in setOf("png", "ppng", "jpg", "jpeg", "pjpg", "pjpeg", "gif", "webp", "bmp", "svg") ->
            WatchArchiveEntryKind.IMAGE
        path.startsWith("sfx/") || path.startsWith("media/") || path.startsWith("video/") ||
            extension in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "mp4", "webm") ->
            WatchArchiveEntryKind.MEDIA
        else -> WatchArchiveEntryKind.OTHER
    }
}

private fun String?.isWatchmakerProtectionEnabled(): Boolean = when (this?.trim()?.lowercase(Locale.ROOT)) {
    "y", "yes", "true", "1" -> true
    else -> false
}

/**
 * 归档里的 `watch.xml` 有没有声明 WatchMaker 的 `protection`。
 *
 * ## 为什么需要这个判据
 *
 * 「标准受保护表盘」和 Flue 里另一套 `jbwatch` 渲染路径在**文件结构上完全一样**：
 * 都是一个被剥空的 `watch.xml` 骨架 ＋ 一个编码过的 `watch.pxml`。所以按文件名判断
 * 归档类型必然分不开这两者。
 *
 * 真正的分界线是这个属性：[WatchArchiveInstaller] 只有看到 `protection` 才会去解
 * `watch.pxml`（见上面 `inspectDefinition`）。声明了就交给标准渲染器——那条路支持
 * 全部图层类型、能编辑、能调画布；没声明的包标准安装器只会读到一个零图层的骨架，
 * 那才该落回 jbwatch。
 *
 * 曾经的症状：Flue 自己导出的受保护表盘（一定带 `protection="y"`）经浏览器下载后
 * 手动导入，被按文件名判成 jbwatch，于是名字退化成文件名「.watch」、卡顿、既不能
 * 编辑也不能调画布；同一个包走 Flue 商店安装却完全正常 —— 因为商店那条路直接调
 * [WatchArchiveInstaller]，根本不经过按文件名的归档嗅探。
 *
 * 读不出来（坏 zip、超大 XML、解析失败）时返回 false：这是个「往哪条路走」的提示，
 * 不是安全闸门，真正的校验在安装器里。
 */
fun watchArchiveDeclaresProtection(
    archive: File,
    limits: WatchArchiveLimits = WatchArchiveLimits(),
): Boolean = runCatching {
    ZipFile(archive).use { zip ->
        val entry = zip.entries().asSequence().firstOrNull { candidate ->
            !candidate.isDirectory &&
                candidate.name.replace('\\', '/').substringAfterLast('/')
                    .equals(WATCH_XML_FILE_NAME, ignoreCase = true)
        } ?: return@use false
        val definition = zip.getInputStream(entry).use { input ->
            WatchmakerDefinitionParser().parse(input, limits.maximumXmlOrScriptBytes)
        }
        definition.rootAttributes.valueForIgnoreCase("protection").isWatchmakerProtectionEnabled()
    }
}.getOrDefault(false)

private fun Map<String, String>.valueForIgnoreCase(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

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
private const val PROTECTED_XML_FILE_NAME = "watch.pxml"
private const val PREVIEW_FILE_NAME = "preview.jpg"
