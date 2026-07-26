package com.flue.launcher.watchface.standard

import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/**
 * WatchMaker protection transport only; trust validation remains in
 * SecureWatchXmlParser. The byte rules are verified against CloX
 * `assets/lua/wf_protect.lua` at ea4ba4e and WFTools
 * `WatchmakerExtractor.kt` at 4c61b94.
 */
internal object WatchmakerProtection {
    private val XOR_KEY = "SWHn-".toByteArray(Charsets.US_ASCII)
    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )

    fun openDecodedXml(input: InputStream): InputStream =
        Base64.getDecoder().wrap(WatchmakerBase64InputStream(BufferedInputStream(input)))

    fun decodeXmlToFile(source: File, destination: File, maximumDecodedBytes: Long) {
        source.inputStream().use { raw ->
            openDecodedXml(raw).use { decoded ->
                FileOutputStream(destination).use { output ->
                    copyBounded(decoded, output, maximumDecodedBytes, "Protected watch.xml")
                }
            }
        }
    }

    fun decodeProtectedAssetInPlace(file: File, maximumBytes: Long) {
        val extension = file.extension.lowercase()
        val expected = when (extension) {
            "ppng" -> ProtectedAssetType.PNG
            "pjpg", "pjpeg" -> ProtectedAssetType.JPEG
            else -> return
        }
        if (hasExpectedHeader(file, expected)) {
            validateDecodedImage(file, expected)
            return
        }
        val temporary = File(file.parentFile, ".${file.name}.decode-${UUID.randomUUID()}")
        try {
            file.inputStream().buffered().use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    xorCopyBounded(input, output, maximumBytes)
                }
            }
            if (!hasExpectedHeader(temporary, expected)) {
                throw IllegalArgumentException("Protected ${file.name} has an invalid decoded header")
            }
            validateDecodedImage(temporary, expected)
            replaceFile(temporary, file)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun xorCopyBounded(input: InputStream, output: OutputStream, maximumBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var keyOffset = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = checkedProtectionSize(total, count.toLong(), maximumBytes)
            for (index in 0 until count) {
                val source = buffer[index].toInt() and 0xff
                val key = XOR_KEY[keyOffset].toInt() and 0xff
                buffer[index] = (source xor key).toByte()
                keyOffset = (keyOffset + 1) % XOR_KEY.size
            }
            output.write(buffer, 0, count)
        }
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        maximumBytes: Long,
        label: String,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = checkedProtectionSize(total, count.toLong(), maximumBytes, label)
            output.write(buffer, 0, count)
        }
    }

    private fun checkedProtectionSize(
        current: Long,
        added: Long,
        maximumBytes: Long,
        label: String = "Protected asset",
    ): Long {
        if (added < 0L || current > maximumBytes - added) {
            throw IllegalArgumentException("$label exceeds the $maximumBytes-byte limit")
        }
        return current + added
    }

    private fun hasExpectedHeader(file: File, type: ProtectedAssetType): Boolean {
        if (!file.isFile) return false
        return runCatching {
            file.inputStream().buffered().use { input ->
                when (type) {
                    ProtectedAssetType.PNG -> readHeader(input, PNG_HEADER.size).contentEquals(PNG_HEADER)
                    ProtectedAssetType.JPEG -> {
                        val header = readHeader(input, 3)
                        header.size == 3 &&
                            (header[0].toInt() and 0xff) == 0xff &&
                            (header[1].toInt() and 0xff) == 0xd8 &&
                            (header[2].toInt() and 0xff) == 0xff
                    }
                }
            }
        }.getOrDefault(false)
    }

    /**
     * A valid signature is not enough: truncated images with a copied PNG or
     * JPEG header previously reached the renderer and failed as a silent black
     * layer. Decode bounds first to reject dimension bombs, then decode every
     * pixel before the installation transaction is committed.
     */
    private fun validateDecodedImage(file: File, type: ProtectedAssetType) {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Protected ${file.name} has invalid image bounds")
        }
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount <= 0L || pixelCount > MAXIMUM_DECODED_PIXELS) {
            throw IllegalArgumentException(
                "Protected ${file.name} exceeds the decoded-pixel budget",
            )
        }

        val bitmap = try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inScaled = false },
            )
        } catch (error: OutOfMemoryError) {
            throw IllegalArgumentException(
                "Protected ${file.name} could not be decoded within the memory budget",
                error,
            )
        } ?: throw IllegalArgumentException("Protected ${file.name} has invalid image data")
        try {
            if (bitmap.width != width || bitmap.height != height) {
                throw IllegalArgumentException(
                    "Protected ${file.name} decoded with inconsistent dimensions",
                )
            }
            val expectedMime = when (type) {
                ProtectedAssetType.PNG -> "image/png"
                ProtectedAssetType.JPEG -> "image/jpeg"
            }
            bounds.outMimeType?.let { actualMime ->
                if (!actualMime.equals(expectedMime, ignoreCase = true)) {
                    throw IllegalArgumentException(
                        "Protected ${file.name} does not contain the expected image type",
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun readHeader(input: InputStream, size: Int): ByteArray {
        val output = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(output, offset, size - offset)
            if (count < 0) break
            if (count == 0) continue
            offset += count
        }
        return if (offset == size) output else output.copyOf(offset)
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

    private enum class ProtectedAssetType { PNG, JPEG }

    private const val MAXIMUM_DECODED_PIXELS = 16_777_216L

    /**
     * WatchMaker swaps D/g and 4/L after Base64 encoding. The strict decoder
     * receives only transformed Base64 characters and ASCII whitespace is the
     * only ignored input.
     */
    private class WatchmakerBase64InputStream(input: InputStream) : FilterInputStream(input) {
        override fun read(): Int {
            while (true) {
                val value = super.read()
                if (value < 0) return value
                if (value.isAsciiWhitespace()) continue
                return restore(value)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            if (length == 0) return 0
            var written = 0
            while (written == 0) {
                val count = super.read(buffer, offset, length)
                if (count < 0) return count
                for (index in offset until offset + count) {
                    val value = buffer[index].toInt() and 0xff
                    if (value.isAsciiWhitespace()) continue
                    buffer[offset + written] = restore(value).toByte()
                    written++
                }
            }
            return written
        }

        private fun restore(value: Int): Int = when (value.toChar()) {
            'D' -> 'g'.code
            'g' -> 'D'.code
            '4' -> 'L'.code
            'L' -> '4'.code
            else -> value
        }

        private fun Int.isAsciiWhitespace(): Boolean =
            this == 0x09 || this == 0x0a || this == 0x0d || this == 0x20
    }
}
