package com.flue.launcher.watchface.standard

import org.w3c.dom.Document
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Shared XML trust boundary for imported and edited .watch definitions.
 *
 * Android XML providers are not uniform across API levels. In particular,
 * some providers reject the optional `disallow-doctype-decl` feature instead
 * of simply reporting it as unsupported. Security therefore has two layers:
 * a bounded scan of the exact bytes that will be parsed, followed by every
 * parser hardening switch the provider accepts. The resolver remains a final
 * fail-closed guard for providers that silently ignore a switch.
 */
internal object SecureWatchXmlParser {
    private const val DISALLOW_DOCTYPE_FEATURE =
        "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL_ENTITIES_FEATURE =
        "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER_ENTITIES_FEATURE =
        "http://xml.org/sax/features/external-parameter-entities"
    private const val LOAD_EXTERNAL_DTD_FEATURE =
        "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    private const val SECURE_PROCESSING_FEATURE =
        "http://javax.xml.XMLConstants/feature/secure-processing"
    private const val ACCESS_EXTERNAL_DTD_PROPERTY =
        "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA_PROPERTY =
        "http://javax.xml.XMLConstants/property/accessExternalSchema"

    /** The archive validator is stricter; this bound also protects direct reads. */
    const val DEFAULT_MAX_BYTES: Long = 24L * 1024L * 1024L

    fun parse(
        file: File,
        maximumBytes: Long = DEFAULT_MAX_BYTES,
        configureFactory: (DocumentBuilderFactory.() -> Unit)? = null,
    ): Document {
        require(file.isFile) { "watch.xml does not exist" }
        return FileInputStream(file).use { input ->
            parse(input, maximumBytes, configureFactory)
        }
    }

    fun parse(
        input: InputStream,
        maximumBytes: Long,
        configureFactory: (DocumentBuilderFactory.() -> Unit)? = null,
    ): Document {
        val bytes = readBounded(input, maximumBytes)
        rejectUnsafeDeclarations(bytes)

        val factory = DocumentBuilderFactory.newInstance().apply {
            configureFactory?.invoke(this)
            harden(this)
        }
        return factory.newDocumentBuilder().apply {
            // This must remain installed even when a provider ignores one or
            // more of the feature names above.
            setEntityResolver { _, _ ->
                throw SAXException("External XML entities are not allowed")
            }
        }.parse(ByteArrayInputStream(bytes))
    }

    private fun harden(factory: DocumentBuilderFactory) {
        // These are intentionally best effort. A provider that does not know
        // one optional feature must not make ordinary, entity-free XML
        // unusable; the bounded pre-scan and resolver provide the fallback.
        bestEffort { factory.setFeature(SECURE_PROCESSING_FEATURE, true) }
        bestEffort { factory.setFeature(DISALLOW_DOCTYPE_FEATURE, true) }
        bestEffort { factory.setFeature(EXTERNAL_GENERAL_ENTITIES_FEATURE, false) }
        bestEffort { factory.setFeature(EXTERNAL_PARAMETER_ENTITIES_FEATURE, false) }
        bestEffort { factory.setFeature(LOAD_EXTERNAL_DTD_FEATURE, false) }
        bestEffort { factory.isValidating = false }
        bestEffort { factory.setXIncludeAware(false) }
        bestEffort { factory.isExpandEntityReferences = false }
        bestEffort { factory.setAttribute(ACCESS_EXTERNAL_DTD_PROPERTY, "") }
        bestEffort { factory.setAttribute(ACCESS_EXTERNAL_SCHEMA_PROPERTY, "") }
    }

    private fun readBounded(input: InputStream, maximumBytes: Long): ByteArray {
        require(maximumBytes in 1L..Int.MAX_VALUE.toLong()) {
            "watch.xml byte limit is outside the supported range"
        }
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8_192L).toInt())
        val buffer = ByteArray(8_192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maximumBytes) {
                "watch.xml exceeds the ${maximumBytes}-byte limit"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /**
     * XML declaration keywords are ASCII in UTF-8, UTF-16 and UTF-32. By
     * ignoring NUL bytes this bounded byte scan covers both UTF-16 byte orders
     * (and UTF-32) without decoding attacker-controlled text into an
     * additional large String. Non-ASCII bytes reset the token matcher, so a
     * malformed stream is still left to the XML parser to reject.
     */
    private fun rejectUnsafeDeclarations(bytes: ByteArray) {
        // XML 1.0 defines this EBCDIC prefix for automatic encoding
        // detection. EBCDIC does not preserve ASCII declaration bytes, so the
        // fallback scanner below cannot safely inspect it. Watchmaker exports
        // use UTF-8/UTF-16; fail closed instead of allowing an entity bypass
        // on providers that lack the DOCTYPE feature.
        if (bytes.startsWith(EBCDIC_XML_SIGNATURE)) {
            throw IllegalArgumentException("watch.xml uses an unsupported XML encoding")
        }
        FORBIDDEN_DECLARATIONS.forEach { (token, label) ->
            if (containsAsciiToken(bytes, token)) {
                throw IllegalArgumentException("watch.xml contains a forbidden $label declaration")
            }
        }
    }

    private fun containsAsciiToken(bytes: ByteArray, token: ByteArray): Boolean {
        var matched = 0
        bytes.forEach { raw ->
            val value = raw.toInt() and 0xff
            if (value == 0) return@forEach
            val upper = if (value in 'a'.code..'z'.code) value - ('a'.code - 'A'.code) else value
            if (upper == token[matched].toInt()) {
                matched++
                if (matched == token.size) return true
            } else {
                matched = if (upper == token[0].toInt()) 1 else 0
            }
        }
        return false
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Optional feature is unavailable on this provider.
        } catch (_: LinkageError) {
            // Some old Android providers fail with an AbstractMethodError.
        }
    }

    private val FORBIDDEN_DECLARATIONS = listOf(
        "<!DOCTYPE".encodeToByteArray() to "DOCTYPE",
        "<!ENTITY".encodeToByteArray() to "ENTITY",
    )

    private val EBCDIC_XML_SIGNATURE = byteArrayOf(
        0x4c,
        0x6f,
        0xa7.toByte(),
        0x94.toByte(),
    )
}
