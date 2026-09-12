package com.flue.launcher.watchface.agent.workspace

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

enum class RootfsArchiveFormat { TAR_GZ, TAR_XZ }

fun interface RootfsArchiveSource {
    fun open(): InputStream
}

class HttpRootfsArchiveSource(private val url: String) : RootfsArchiveSource {
    override fun open(): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Flue-Rootfs-Installer/1")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("Rootfs download failed with HTTP $code")
        }
        return object : InputStream() {
            private val delegate = connection.inputStream
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                delegate.read(buffer, offset, length)
            override fun close() {
                try {
                    delegate.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}

enum class RootfsInstallStage {
    COPYING,
    VERIFYING,
    EXTRACTING,
    PATCHING,
    SELF_CHECKING,
    COMMITTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesCopied: Long = 0L,
    val totalBytes: Long? = null,
    val currentEntry: String? = null,
)

data class RootfsSelfCheck(
    val bashAvailable: Boolean,
    val pythonAvailable: Boolean,
    val failure: RootfsSelfCheckFailure? = null,
)

data class RootfsSelfCheckFailure(
    val stage: RootfsInstallStage,
    val command: String,
    val exitCode: Int?,
    val output: String,
    val truncated: Boolean,
)

fun interface RootfsSelfChecker {
    fun check(linux: Path): RootfsSelfCheck
}

enum class RootfsInstallError {
    WORKSPACE_NOT_FOUND,
    ABI_MISMATCH,
    SIZE_MISMATCH,
    SHA256_MISMATCH,
    SIGNATURE_INVALID,
    INVALID_ARCHIVE,
    BASH_UNAVAILABLE,
    IO_ERROR,
}

sealed interface RootfsInstallResult {
    data class Installed(val workspace: AgentWorkspace) : RootfsInstallResult
    data class Invalid(val reason: RootfsInstallError) : RootfsInstallResult
    data class Failed(val message: String) : RootfsInstallResult
}

data class OnlineRootfsPackage(
    val url: String,
    val manifest: RootfsManifest,
    val format: RootfsArchiveFormat,
)

/** The hash is copied from Ubuntu's signed SHA256SUMS and then pinned in the signed APK. */
object RootfsCatalog {
    fun forAbi(abi: String): OnlineRootfsPackage? = when (abi) {
        "arm64-v8a" -> OnlineRootfsPackage(
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
            manifest = RootfsManifest(
                architecture = "arm64-v8a",
                version = "24.04.5",
                archiveSha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                archiveSizeBytes = 29_936_675L,
                expandedSizeBytes = 512L * 1024L * 1024L,
                signature = "apk-pinned:ubuntu-sha256sums",
                requiredPaths = setOf("/bin/bash"),
            ),
            format = RootfsArchiveFormat.TAR_GZ,
        )
        else -> null
    }
}

class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val currentAbi: () -> String,
    private val selfChecker: RootfsSelfChecker = RootfsSelfChecker { linux ->
        RootfsSelfCheck(
            bashAvailable = Files.isRegularFile(linux.resolve("bin/bash"), LinkOption.NOFOLLOW_LINKS),
            pythonAvailable = Files.isRegularFile(linux.resolve("usr/bin/python3"), LinkOption.NOFOLLOW_LINKS),
        )
    },
) {
    fun install(
        workspaceId: String,
        manifest: RootfsManifest,
        format: RootfsArchiveFormat,
        source: RootfsArchiveSource,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): RootfsInstallResult {
        val workspace = manager.get(workspaceId)
            ?: return RootfsInstallResult.Invalid(RootfsInstallError.WORKSPACE_NOT_FOUND)
        if (manifest.architecture != currentAbi()) {
            return RootfsInstallResult.Invalid(RootfsInstallError.ABI_MISMATCH)
        }
        if (manifest.archiveSizeBytes <= 0L || manifest.expandedSizeBytes <= 0L ||
            !SHA256.matches(manifest.archiveSha256) || manifest.signature.isBlank()
        ) {
            return RootfsInstallResult.Invalid(RootfsInstallError.SIGNATURE_INVALID)
        }

        val suffix = if (format == RootfsArchiveFormat.TAR_GZ) ".tar.gz" else ".tar.xz"
        val archive = workspace.paths.root.resolve(".rootfs-download-${UUID.randomUUID()}$suffix")
        val staging = workspace.paths.root.resolve(".rootfs-install-${UUID.randomUUID()}")
        return try {
            onProgress(RootfsInstallProgress(RootfsInstallStage.COPYING, totalBytes = manifest.archiveSizeBytes))
            val digest = copyAndDigest(source, archive, manifest.archiveSizeBytes, onProgress)
            onProgress(RootfsInstallProgress(RootfsInstallStage.VERIFYING, manifest.archiveSizeBytes, manifest.archiveSizeBytes))
            if (Files.size(archive) != manifest.archiveSizeBytes) {
                return RootfsInstallResult.Invalid(RootfsInstallError.SIZE_MISMATCH)
            }
            if (!digest.equals(manifest.archiveSha256, ignoreCase = true)) {
                return RootfsInstallResult.Invalid(RootfsInstallError.SHA256_MISMATCH)
            }

            Files.createDirectory(staging)
            onProgress(RootfsInstallProgress(RootfsInstallStage.EXTRACTING, totalBytes = manifest.expandedSizeBytes))
            SafeTarExtractor.extract(archive, staging, format, manifest.expandedSizeBytes) { entry, extracted ->
                onProgress(
                    RootfsInstallProgress(
                        stage = RootfsInstallStage.EXTRACTING,
                        bytesCopied = extracted,
                        totalBytes = manifest.expandedSizeBytes,
                        currentEntry = entry,
                    ),
                )
            }
            onProgress(RootfsInstallProgress(RootfsInstallStage.PATCHING))
            patchRootfs(staging)
            if (manifest.requiredPaths.any { !existsInside(staging, it) }) {
                return RootfsInstallResult.Invalid(RootfsInstallError.INVALID_ARCHIVE)
            }
            onProgress(RootfsInstallProgress(RootfsInstallStage.SELF_CHECKING))
            val check = selfChecker.check(staging)
            if (!check.bashAvailable) {
                return RootfsInstallResult.Failed(check.failureMessage())
            }
            onProgress(RootfsInstallProgress(RootfsInstallStage.COMMITTING))
            val installed = manager.replaceRootfs(
                workspaceId = workspaceId,
                stagingRootfs = staging,
                bashAvailable = true,
                pythonAvailable = check.pythonAvailable,
                architecture = manifest.architecture,
                version = manifest.version,
                archiveSha256 = manifest.archiveSha256.lowercase(),
            )
            onProgress(RootfsInstallProgress(RootfsInstallStage.INSTALLED))
            RootfsInstallResult.Installed(installed)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            RootfsInstallResult.Failed("Rootfs installation was cancelled")
        } catch (failure: RootfsArchiveException) {
            RootfsInstallResult.Invalid(failure.reason)
        } catch (failure: IOException) {
            RootfsInstallResult.Failed(failure.message ?: "Rootfs I/O failure")
        } catch (failure: RuntimeException) {
            RootfsInstallResult.Failed(failure.message ?: "Rootfs installation failed")
        } finally {
            deleteExactRootfsPath(archive)
            deleteExactRootfsPath(staging)
        }
    }

    private fun copyAndDigest(
        source: RootfsArchiveSource,
        target: Path,
        expectedBytes: Long,
        onProgress: (RootfsInstallProgress) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        source.open().use { input ->
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    if (copied > expectedBytes) throw RootfsArchiveException(RootfsInstallError.SIZE_MISMATCH)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    onProgress(RootfsInstallProgress(RootfsInstallStage.COPYING, copied, expectedBytes))
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun patchRootfs(root: Path) {
        writeSafe(root, "etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 223.5.5.5\n")
        writeSafe(root, "etc/hosts", "127.0.0.1 localhost\n::1 localhost\n")
        writeSafe(root, "etc/hostname", "localhost\n")
        writeSafe(root, "etc/default/locale", "LANG=C.UTF-8\n")
        listOf("proc", "sys", "dev", "tmp", "var/tmp", "root").forEach { relative ->
            val target = safeTarget(root, relative)
            ensureSafeParents(root, target.parent)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
                }
            } else {
                Files.createDirectory(target)
            }
            if (relative == "tmp" || relative == "var/tmp") {
                target.toFile().setReadable(true, false)
                target.toFile().setWritable(true, false)
                target.toFile().setExecutable(true, false)
            }
        }
    }

    private fun writeSafe(root: Path, relative: String, value: String) {
        val target = safeTarget(root, relative)
        ensureSafeParents(root, target.parent)
        if (Files.isSymbolicLink(target)) Files.delete(target)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
        }
        Files.write(
            target,
            value.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun existsInside(root: Path, raw: String): Boolean {
        val target = safeTarget(root, raw.removePrefix("/"))
        return Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    }

    companion object {
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}

private fun RootfsSelfCheck.failureMessage(): String {
    val detail = failure ?: RootfsSelfCheckFailure(
        stage = RootfsInstallStage.SELF_CHECKING,
        command = ROOTFS_SELF_CHECK_COMMAND_LABEL,
        exitCode = null,
        output = "",
        truncated = false,
    )
    return buildString {
        append("stage=")
        append(detail.stage.name)
        append("; command=")
        append(detail.command)
        append("; exit=")
        append(detail.exitCode ?: "unavailable")
        append("; truncated=")
        append(detail.truncated)
        append("; output=")
        append(detail.output.ifBlank { "<none>" })
    }
}

internal const val ROOTFS_SELF_CHECK_COMMAND_LABEL = "proot /bin/bash -lc <self-check>"

private class RootfsArchiveException(val reason: RootfsInstallError) : IOException(reason.name)

/**
 * Materialises a tar hard-link without assuming that the app data filesystem
 * implements link(2). Some Android filesystems reject [Files.createLink] even
 * though both paths are inside the same app-owned directory. A byte-for-byte
 * copy preserves the rootfs file semantics needed by proot; the archive path
 * checks are completed before this helper is called.
 */
internal fun materializeRootfsHardLink(
    source: Path,
    target: Path,
    createHardLink: (Path, Path) -> Unit = { link, existing -> Files.createLink(link, existing) },
) {
    try {
        createHardLink(target, source)
        return
    } catch (failure: Exception) {
        if (failure !is IOException && failure !is UnsupportedOperationException && failure !is SecurityException) {
            throw failure
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw failure
    }
    Files.copy(source, target)
    val sourceFile = source.toFile()
    val targetFile = target.toFile()
    targetFile.setReadable(sourceFile.canRead(), false)
    targetFile.setWritable(sourceFile.canWrite(), false)
    targetFile.setExecutable(sourceFile.canExecute(), false)
}

private object SafeTarExtractor {
    private const val BLOCK_SIZE = 512

    fun extract(
        archive: Path,
        root: Path,
        format: RootfsArchiveFormat,
        maxExpandedBytes: Long,
        onEntry: (String, Long) -> Unit,
    ) {
        val compressed = BufferedInputStream(Files.newInputStream(archive), 64 * 1024)
        val decoded: InputStream = when (format) {
            RootfsArchiveFormat.TAR_GZ -> GZIPInputStream(compressed, 64 * 1024)
            RootfsArchiveFormat.TAR_XZ -> XZInputStream(compressed)
        }
        decoded.use { tar -> extractTar(tar, root.toAbsolutePath().normalize(), maxExpandedBytes, onEntry) }
    }

    private fun extractTar(
        input: InputStream,
        root: Path,
        maxExpandedBytes: Long,
        onEntry: (String, Long) -> Unit,
    ) {
        var expanded = 0L
        var localPax = emptyMap<String, String>()
        val globalPax = mutableMapOf<String, String>()
        var longName: String? = null
        var longLink: String? = null
        val deferredLinks = mutableListOf<Pair<Path, String>>()
        val header = ByteArray(BLOCK_SIZE)
        while (readBlock(input, header)) {
            if (header.all { it == 0.toByte() }) break
            verifyChecksum(header)
            val headerSize = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val baseName = combineName(header)
            val baseLink = readString(header, 157, 100)

            if (type == 'x' || type == 'g') {
                val payload = readPayload(input, headerSize, maxExpandedBytes)
                val attributes = parsePax(payload)
                if (type == 'g') globalPax.putAll(attributes) else localPax = attributes
                skipPadding(input, headerSize)
                continue
            }
            if (type == 'L' || type == 'K') {
                val value = String(readPayload(input, headerSize, maxExpandedBytes), Charsets.UTF_8)
                    .trimEnd('\u0000', '\n')
                if (type == 'L') longName = value else longLink = value
                skipPadding(input, headerSize)
                continue
            }

            val attributes = globalPax + localPax
            val name = attributes["path"] ?: longName ?: baseName
            val linkName = attributes["linkpath"] ?: longLink ?: baseLink
            val size = attributes["size"]?.toLongOrNull() ?: headerSize
            localPax = emptyMap()
            longName = null
            longLink = null
            if (size < 0L || expanded > maxExpandedBytes - size) {
                throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
            }
            if (name == "." || name == "./") {
                if (type != '5') throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
                skipExact(input, size)
                skipPadding(input, size)
                onEntry(name, expanded)
                continue
            }
            val target = safeTarget(root, name)
            when (type) {
                '\u0000', '0', '7' -> {
                    ensureSafeParents(root, target.parent)
                    if (Files.isSymbolicLink(target) || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
                    }
                    Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    ).use { output -> copyExact(input, output, size) }
                    expanded += size
                    applyExecutable(target, parseOctal(header, 100, 8))
                }
                '5' -> {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
                    }
                    ensureSafeParents(root, target)
                }
                '2' -> {
                    ensureSafeParents(root, target.parent)
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
                    }
                    if (linkName.isBlank() || linkName.indexOf('\u0000') >= 0) {
                        throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
                    }
                    Files.createSymbolicLink(target, target.fileSystem.getPath(linkName))
                }
                '1' -> deferredLinks += target to linkName
                '3', '4', '6' -> Unit // Device nodes are supplied by the selected Android bind mounts.
                else -> throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
            }
            if (type != '\u0000' && type != '0' && type != '7') skipExact(input, size)
            skipPadding(input, size)
            onEntry(name, expanded)
        }
        deferredLinks.forEach { (target, rawSource) ->
            ensureSafeParents(root, target.parent)
            val source = safeTarget(root, rawSource)
            ensureSafeParents(root, source.parent)
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
            }
            val materializedSize = Files.size(source)
            if (expanded > maxExpandedBytes - materializedSize) {
                throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
            }
            materializeRootfsHardLink(source, target)
            // Count pessimistically even when a real hard link succeeds. On Android the
            // fallback is a copy, so this keeps the expanded-size limit meaningful.
            expanded += materializedSize
        }
    }

    private fun verifyChecksum(header: ByteArray) {
        val stored = parseOctal(header, 148, 8)
        var actual = 0L
        header.forEachIndexed { index, byte ->
            actual += if (index in 148 until 156) 32 else byte.toInt() and 0xff
        }
        if (stored != actual) throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
    }

    private fun combineName(header: ByteArray): String {
        val name = readString(header, 0, 100)
        val prefix = readString(header, 345, 155)
        return if (prefix.isBlank()) name else "$prefix/$name"
    }

    private fun parseOctal(bytes: ByteArray, offset: Int, length: Int): Long {
        if (bytes[offset].toInt() and 0x80 != 0) {
            var value = (bytes[offset].toInt() and 0x7f).toLong()
            for (index in offset + 1 until offset + length) value = (value shl 8) or (bytes[index].toInt() and 0xff).toLong()
            return value
        }
        val raw = readString(bytes, offset, length).trim()
        return if (raw.isEmpty()) 0L else raw.toLongOrNull(8)
            ?: throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
    }

    private fun readString(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: offset + length
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 0
        while (offset < bytes.size) {
            var space = offset
            while (space < bytes.size && bytes[space] != ' '.code.toByte()) space++
            if (space <= offset) throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
            val length = String(bytes, offset, space - offset, Charsets.US_ASCII).toIntOrNull()
                ?: throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
            if (length <= 0 || offset + length > bytes.size) {
                throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
            }
            val recordEnd = offset + length
            val record = String(bytes, space + 1, recordEnd - space - 1, Charsets.UTF_8).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) result[record.substring(0, equals)] = record.substring(equals + 1)
            offset = recordEnd
        }
        return result
    }

    private fun readPayload(input: InputStream, size: Long, maximum: Long): ByteArray {
        if (size < 0L || size > maximum || size > Int.MAX_VALUE) {
            throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
        }
        return ByteArray(size.toInt()).also { readExact(input, it, 0, it.size) }
    }

    private fun readBlock(input: InputStream, target: ByteArray): Boolean {
        val first = input.read()
        if (first < 0) return false
        target[0] = first.toByte()
        readExact(input, target, 1, target.size - 1)
        return true
    }

    private fun readExact(input: InputStream, target: ByteArray, offset: Int, length: Int) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = input.read(target, position, end - position)
            if (count < 0) throw EOFException("Truncated tar archive")
            if (count > 0) position += count
        }
    }

    private fun copyExact(input: InputStream, output: java.io.OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Truncated tar entry")
            if (count > 0) {
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        skipExact(input, (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE)
    }

    private fun skipExact(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Truncated tar archive")
            if (count > 0) remaining -= count
        }
    }

    private fun applyExecutable(target: Path, mode: Long) {
        if (mode and 0b001_001_001L != 0L) target.toFile().setExecutable(true, false)
        target.toFile().setReadable(true, false)
    }
}

private fun safeTarget(root: Path, rawName: String): Path {
    if (rawName.isBlank() || rawName.indexOf('\u0000') >= 0 || rawName.contains('\\')) {
        throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
    }
    var name = rawName
    while (name.startsWith("./")) name = name.removePrefix("./")
    if (name.isBlank() || name.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(name)) {
        throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
    }
    val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty() || segments.any { it == ".." }) {
        throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
    }
    val target = segments.fold(root) { path, segment -> path.resolve(segment) }.normalize()
    if (!target.startsWith(root) || target == root) {
        throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
    }
    return target
}

private fun ensureSafeParents(root: Path, directory: Path) {
    if (!directory.normalize().startsWith(root)) throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
    var cursor = root
    root.relativize(directory.normalize()).forEach { segment ->
        cursor = cursor.resolve(segment)
        if (Files.isSymbolicLink(cursor)) throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
        if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw RootfsArchiveException(RootfsInstallError.INVALID_ARCHIVE)
            }
        } else {
            Files.createDirectory(cursor)
        }
    }
}

private fun deleteExactRootfsPath(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(path)
        return
    }
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
