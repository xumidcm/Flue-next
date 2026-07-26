package com.flue.launcher.watchface.store

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException

data class WatchStoreHttpConfig(
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 30_000,
    val maxRedirects: Int = 5,
) {
    init {
        require(connectTimeoutMs in 1..120_000) { "connectTimeoutMs is out of range" }
        require(readTimeoutMs in 1..300_000) { "readTimeoutMs is out of range" }
        require(maxRedirects in 0..10) { "maxRedirects is out of range" }
    }
}

/** Minimal HTTPS transport; deliberately does not add an HTTP client dependency. */
class HttpWatchStoreApi(
    baseUrl: String = DEFAULT_WATCH_STORE_BASE_URL,
    private val config: WatchStoreHttpConfig = WatchStoreHttpConfig(),
    /** Injectable for URL/redirect tests; production uses [URL.openConnection]. */
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : WatchStoreApi {
    private val base = WatchStoreUrlPolicy.normalizeBaseUrl(baseUrl)

    override fun getCategories(): List<WatchStoreCategory> = requestJson(
        WatchStoreUrlPolicy.categories(base),
        MAX_JSON_BYTES,
        WatchStoreJsonParser::categories,
    )

    override fun listWatchFaces(query: WatchStoreQuery): WatchStorePage = requestJson(
        WatchStoreUrlPolicy.list(base, query),
        MAX_JSON_BYTES,
        WatchStoreJsonParser::page,
    )

    override fun getWatchFace(id: String): WatchStoreWatchFaceDetail = requestJson(
        WatchStoreUrlPolicy.detail(base, id),
        MAX_JSON_BYTES,
        WatchStoreJsonParser::detail,
    )

    override fun downloadPreview(id: String, destination: File): File {
        val url = WatchStoreUrlPolicy.preview(base, id)
        return download(url, destination, MAX_PREVIEW_BYTES, expectPreview = true, onProgress = null)
    }

    override fun downloadWatchFace(id: String, destination: File): File {
        val url = WatchStoreUrlPolicy.download(base, id)
        return download(url, destination, MAX_WATCH_BYTES, expectPreview = false, onProgress = null)
    }

    override fun downloadWatchFace(
        id: String,
        destination: File,
        onProgress: (bytes: Long, totalBytes: Long?) -> Unit,
    ): File {
        val url = WatchStoreUrlPolicy.download(base, id)
        return download(url, destination, MAX_WATCH_BYTES, expectPreview = false, onProgress = onProgress)
    }

    private fun <T> requestJson(
        url: URI,
        maxBytes: Long,
        parser: (String) -> T,
    ): T = request(url) { connection ->
        validateContentType(connection, expectPreview = false, expectJson = true)
        if (connection.contentLengthLong > maxBytes) {
            throw WatchStorePayloadTooLargeException(maxBytes)
        }
        val body = readLimited(connection.inputStream, maxBytes).toString(Charsets.UTF_8)
        parser(body)
    }

    private fun download(
        url: URI,
        destination: File,
        maxBytes: Long,
        expectPreview: Boolean,
        onProgress: ((bytes: Long, totalBytes: Long?) -> Unit)?,
    ): File {
        if (destination.isDirectory) throw IllegalArgumentException("Download destination is a directory")
        destination.parentFile?.takeIf { !it.exists() }?.let { parent ->
            if (!parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Cannot create download directory")
            }
        }
        return try {
            request(url) { connection ->
                validateContentType(connection, expectPreview = expectPreview, expectJson = false)
                val length = connection.contentLengthLong
                if (length > maxBytes) throw WatchStorePayloadTooLargeException(maxBytes)
                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        copyLimited(input, output, maxBytes, connection.contentLengthLong.takeIf { it >= 0L }, onProgress)
                    }
                }
                if (!destination.isFile || destination.length() == 0L) {
                    throw WatchStoreProtocolException("Watch store returned an empty file")
                }
                destination
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun <T> request(url: URI, block: (HttpURLConnection) -> T): T {
        var current = url
        var redirectCount = 0
        try {
            while (true) {
                // The endpoint and every redirect are validated before a socket is opened.
                try {
                    WatchStoreUrlPolicy.requirePublicHttps(current)
                } catch (error: IllegalArgumentException) {
                    throw WatchStoreRedirectException(
                        error.message ?: "Watch store connection target is unsafe",
                    )
                }
                val connection = connectionFactory(current.toURL())
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = config.connectTimeoutMs
                connection.readTimeout = config.readTimeoutMs
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Flue-Next-WatchStore/1")
                try {
                    val status = connection.responseCode
                    if (status in REDIRECT_STATUSES) {
                        if (redirectCount++ >= config.maxRedirects) {
                            throw WatchStoreRedirectException("Too many watch store redirects")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw WatchStoreRedirectException("Redirect response has no Location")
                        current = WatchStoreUrlPolicy.resolveRedirect(current, location)
                        continue
                    }
                    if (status !in 200..299) {
                        val detail = connection.errorStream?.let {
                            runCatching { readLimited(it, MAX_ERROR_BYTES).toString(Charsets.UTF_8) }.getOrNull()
                        }.orEmpty().trim().take(160)
                        val suffix = if (detail.isBlank()) "" else ": $detail"
                        throw WatchStoreHttpException(status, "Watch store request failed ($status)$suffix")
                    }
                    return block(connection)
                } finally {
                    connection.disconnect()
                }
            }
        } catch (error: WatchStoreException) {
            throw error
        } catch (cancelled: CancellationException) {
            // Cancellation is control flow. Wrapping it as a store failure
            // makes the UI retry an operation the user explicitly cancelled.
            throw cancelled
        } catch (error: Throwable) {
            throw WatchStoreException("Watch store request failed", error)
        }
    }

    private fun validateContentType(
        connection: HttpURLConnection,
        expectPreview: Boolean,
        expectJson: Boolean,
    ) {
        val type = connection.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return
        if (expectJson) {
            if (type != "application/json" && !type.endsWith("+json")) {
                throw WatchStoreProtocolException("Watch store response is not JSON")
            }
        } else if (expectPreview) {
            if (!type.startsWith("image/") && type != "application/octet-stream") {
                throw WatchStoreProtocolException("Preview response is not an image")
            }
        } else if (!expectJson && (type == "text/html" || type == "application/json" || type == "text/plain")) {
            throw WatchStoreProtocolException("Watch store returned an unexpected content type")
        }
    }

    companion object {
        const val MAX_JSON_BYTES: Long = 2L * 1024L * 1024L
        const val MAX_PREVIEW_BYTES: Long = 6L * 1024L * 1024L
        // Keep transport and installer budgets aligned. A lower HTTP limit
        // would reject valid font/media-heavy packages before validation.
        const val MAX_WATCH_BYTES: Long = 96L * 1024L * 1024L
        private const val MAX_ERROR_BYTES = 16L * 1024L
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        internal fun readLimited(input: java.io.InputStream, maxBytes: Long): ByteArray {
            val output = ByteArrayOutputStream(minOf(maxBytes, 8_192L).toInt())
            val buffer = ByteArray(8_192)
            var total = 0L
            input.use {
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw WatchStorePayloadTooLargeException(maxBytes)
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }

        internal fun copyLimited(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            maxBytes: Long,
            totalBytes: Long? = null,
            onProgress: ((bytes: Long, totalBytes: Long?) -> Unit)? = null,
        ) {
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw WatchStorePayloadTooLargeException(maxBytes)
                output.write(buffer, 0, count)
                onProgress?.invoke(total, totalBytes)
            }
        }
    }
}
