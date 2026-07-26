package com.flue.launcher.watchface.standard

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal object WatchInstallMetadata {
    private const val DIRECTORY_NAME = ".flue"
    private const val INSTALLED_AT_FILE_NAME = "installed-at"

    fun read(directory: File): Long? {
        val file = metadataFile(directory)
        if (!file.isFile || file.length() !in 1L..32L) return null
        return runCatching { file.readText(Charsets.US_ASCII).trim().toLong() }
            .getOrNull()
            ?.takeIf { it > 0L }
    }

    /** First writer wins so an idempotent reinstall never changes ordering. */
    @Synchronized
    fun readOrCreate(directory: File, proposedEpochMs: Long): Long {
        read(directory)?.let { return it }
        val value = proposedEpochMs.takeIf { it > 0L } ?: 1L
        val metadataDirectory = File(directory, DIRECTORY_NAME)
        if ((!metadataDirectory.exists() && !metadataDirectory.mkdirs()) || !metadataDirectory.isDirectory) {
            return value
        }
        read(directory)?.let { return it }
        val target = metadataFile(directory)
        val temporary = File(metadataDirectory, ".installed-at-${UUID.randomUUID()}")
        try {
            temporary.writeText(value.toString(), Charsets.US_ASCII)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                try {
                    Files.move(temporary.toPath(), target.toPath())
                } catch (_: FileAlreadyExistsException) {
                    // A concurrent import already persisted the authoritative value.
                }
            } catch (_: FileAlreadyExistsException) {
                // A concurrent import already persisted the authoritative value.
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return read(directory) ?: value
    }

    fun isReservedArchivePath(normalizedName: String): Boolean =
        normalizedName.trimStart('/').substringBefore('/').equals(DIRECTORY_NAME, true)

    private fun metadataFile(directory: File): File =
        File(File(directory, DIRECTORY_NAME), INSTALLED_AT_FILE_NAME)
}

/**
 * Durable commit marker for a prepared watch package.
 *
 * The marker is written only after XML and protected assets have been
 * validated. A current marker lets scans jump directly to the known
 * watch.xml; a missing or malformed marker deliberately falls back to the
 * one-time legacy repair path.
 */
internal object WatchPreparedMarker {
    // Version 2 adds full protected-image decoding before a package is marked
    // prepared. Version 1 packages are deliberately repaired and revalidated.
    const val CURRENT_VERSION = 2

    private const val DIRECTORY_NAME = ".flue"
    private const val FILE_NAME = "prepared"
    private const val MAGIC = "flue-watch-prepared"
    private const val MAX_BYTES = 2_048L

    fun read(root: File): File? {
        val marker = File(File(root, DIRECTORY_NAME), FILE_NAME)
        if (!marker.isFile || marker.length() !in 1L..MAX_BYTES) return null
        val lines = runCatching {
            marker.readLines(StandardCharsets.US_ASCII)
        }.getOrNull() ?: return null
        if (lines.size != 3 || lines[0] != MAGIC || lines[1] != "version=$CURRENT_VERSION") {
            return null
        }
        val encoded = lines[2].removePrefix("watchXml=")
        if (encoded.isBlank() || encoded == lines[2]) return null
        val relative = runCatching {
            Base64.getUrlDecoder().decode(encoded).toString(StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        if (!isSafeRelativePath(relative)) return null

        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val watchXml = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
            ?: return null
        if (!isWithin(canonicalRoot, watchXml) || !watchXml.isFile) return null
        return watchXml
    }

    /** Atomically publishes the marker after all package preparation work. */
    fun write(root: File, watchXml: File) {
        val canonicalRoot = root.canonicalFile
        val canonicalWatchXml = watchXml.canonicalFile
        require(isWithin(canonicalRoot, canonicalWatchXml)) {
            "Prepared watch.xml must remain inside its install root"
        }
        val relative = canonicalWatchXml.relativeTo(canonicalRoot)
            .invariantSeparatorsPath
        require(isSafeRelativePath(relative)) { "Prepared watch.xml path is unsafe" }

        val directory = File(canonicalRoot, DIRECTORY_NAME)
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IllegalStateException("Unable to create prepared marker directory")
        }
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(relative.toByteArray(StandardCharsets.UTF_8))
        val payload = "$MAGIC\nversion=$CURRENT_VERSION\nwatchXml=$encoded\n"
            .toByteArray(StandardCharsets.US_ASCII)
        val temporary = File(directory, ".prepared-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                // Ensure a process crash cannot publish a zero-byte marker.
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: FileAlreadyExistsException) {
                Files.move(
                    temporary.toPath(),
                    File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun isSafeRelativePath(value: String): Boolean {
        val normalized = value.replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith('/') || normalized.contains('\u0000')) {
            return false
        }
        return normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun isWithin(root: File, child: File): Boolean =
        child.path == root.path || child.path.startsWith(root.path + File.separator)
}

internal data class WatchInstallOrder<T>(
    val value: T,
    val installedAtEpochMs: Long,
    val displayName: String,
    val stableId: String,
)

internal fun <T> orderWatchInstalls(items: Iterable<WatchInstallOrder<T>>): List<T> =
    items.sortedWith(
        compareByDescending<WatchInstallOrder<T>> { it.installedAtEpochMs }
            .thenBy { it.displayName.lowercase(Locale.ROOT) }
            .thenBy { it.stableId },
    ).map(WatchInstallOrder<T>::value)
