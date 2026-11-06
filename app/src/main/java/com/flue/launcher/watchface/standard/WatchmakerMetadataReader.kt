package com.flue.launcher.watchface.standard

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Only list metadata is retained; render definitions and Lua belong to the active face. */
internal data class WatchmakerMetadata(
    val name: String,
    val version: WatchmakerDefinitionVersion,
    val layerCount: Int,
    val author: String?,
) {
    companion object {
        fun from(definition: WatchmakerDefinition) = WatchmakerMetadata(
            name = definition.name,
            version = definition.version,
            layerCount = definition.layers.size,
            author = definition.rootAttributes["author"]?.takeIf(String::isNotBlank),
        )
    }
}

internal class WatchmakerMetadataReader(
    private val cacheDirectory: File,
    private val parse: (ByteArray) -> WatchmakerDefinition = { bytes ->
        WatchmakerDefinitionParser().parse(bytes.inputStream(), SecureWatchXmlParser.DEFAULT_MAX_BYTES)
    },
) {
    fun read(watchXml: File): WatchmakerMetadata {
        // Hash the exact bounded bytes passed to the secure parser. Size/mtime
        // alone misses equal-size editor saves on coarse-timestamp filesystems.
        val bytes = readBounded(watchXml, SecureWatchXmlParser.DEFAULT_MAX_BYTES)
        val revision = digest(bytes)
        val cacheFile = File(cacheDirectory, digest(watchXml.canonicalPath.toByteArray()) + ".metadata")
        return WatchPackageTransactionMutex.withTarget(cacheFile) {
            readCached(cacheFile, revision) ?: WatchmakerMetadata.from(parse(bytes)).also { metadata ->
                // Cache loss or storage pressure must never hide a valid install.
                runCatching { writeCached(cacheFile, revision, metadata) }
            }
        }
    }

    private fun readCached(file: File, revision: String): WatchmakerMetadata? = runCatching {
        DataInputStream(readBounded(file, MAX_CACHE_BYTES).inputStream()).use { input ->
            if (input.readUTF() != CACHE_VERSION || input.readUTF() != revision) return null
            val metadata = WatchmakerMetadata(
                name = input.readUTF(),
                version = WatchmakerDefinitionVersion.valueOf(input.readUTF()),
                layerCount = input.readInt(),
                author = if (input.readBoolean()) input.readUTF() else null,
            )
            metadata.takeIf { it.layerCount >= 0 && input.read() == -1 }
        }
    }.getOrNull()

    private fun writeCached(file: File, revision: String, metadata: WatchmakerMetadata) {
        val bytes = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { output ->
                output.writeUTF(CACHE_VERSION)
                output.writeUTF(revision)
                output.writeUTF(metadata.name)
                output.writeUTF(metadata.version.name)
                output.writeInt(metadata.layerCount)
                output.writeBoolean(metadata.author != null)
                metadata.author?.let(output::writeUTF)
            }
        }.toByteArray()
        require(bytes.size <= MAX_CACHE_BYTES)
        cacheDirectory.mkdirs()
        val temporary = File.createTempFile("metadata-", ".tmp", cacheDirectory)
        try {
            temporary.writeBytes(bytes)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun readBounded(file: File, maximumBytes: Long): ByteArray {
        require(file.isFile && file.length() in 1L..maximumBytes) { "Invalid metadata source size" }
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= maximumBytes) { "Metadata source exceeds limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        // Bump when list metadata semantics or secure XML validation changes.
        const val CACHE_VERSION = "flue-watch-metadata-1"
        const val MAX_CACHE_BYTES = 64L * 1024L
    }
}
