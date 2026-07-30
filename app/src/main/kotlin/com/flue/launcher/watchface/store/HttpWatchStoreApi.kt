package com.flue.launcher.watchface.store

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

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

    /** Authenticates against the community store. The password only lives inside this call. */
    fun login(username: String, password: String): WatchStoreAuthResult =
        authenticate(WatchStoreUrlPolicy.authLogin(base), username, password)

    /** Registers a new community-store account and returns the fresh session. */
    fun register(username: String, password: String): WatchStoreAuthResult =
        authenticate(WatchStoreUrlPolicy.authRegister(base), username, password)

    /**
     * Uploads a `.watch` archive together with the required metadata fields.
     * The service derives the preview image from the archive, so no image part
     * is sent. Progress callbacks run on the transport thread and must stay
     * cheap/non-blocking.
     */
    fun uploadWatchFace(
        token: String,
        file: File,
        name: String,
        description: String,
        screenType: WatchStoreScreenType,
        categoryId: String,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val safeToken = token.trim()
        require(safeToken.isNotEmpty() && safeToken.none { it.isWhitespace() || it.isISOControl() }) {
            "登录令牌无效，请重新登录"
        }
        require(file.isFile) { "找不到要上传的 .watch 文件" }
        val totalFileBytes = file.length()
        if (totalFileBytes <= 0L) throw WatchStoreProtocolException("要上传的 .watch 文件是空的")
        if (totalFileBytes > MAX_UPLOAD_BYTES) throw WatchStorePayloadTooLargeException(MAX_UPLOAD_BYTES)
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty() && trimmedName.length <= MAX_UPLOAD_NAME_LENGTH) {
            "表盘名称需要 1-$MAX_UPLOAD_NAME_LENGTH 个字符"
        }
        val trimmedDescription = description.trim()
        require(trimmedDescription.isNotEmpty()) { "表盘描述不能为空" }
        require(screenType != WatchStoreScreenType.UNKNOWN) { "屏幕形状无效" }
        val normalizedCategoryId = categoryId.trim().lowercase(Locale.ROOT)
        require(UPLOAD_CATEGORY_ID_PATTERN.matches(normalizedCategoryId)) { "分类无效，请重新选择" }

        val boundary = "FlueWatchStore${UUID.randomUUID().toString().replace("-", "")}"
        val head = buildString {
            appendFormField(boundary, "name", trimmedName)
            appendFormField(boundary, "description", trimmedDescription)
            appendFormField(boundary, "screen_type", screenType.apiValue)
            appendFormField(boundary, "category_id", normalizedCategoryId)
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"upload.watch\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        post(
            url = WatchStoreUrlPolicy.upload(base),
            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = head.size.toLong() + totalFileBytes + tail.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $safeToken"),
            readTimeoutMs = maxOf(config.readTimeoutMs, UPLOAD_READ_TIMEOUT_MS),
            expectJson = false,
            writeBody = { output ->
                output.write(head)
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var sent = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sent += count
                        if (sent > totalFileBytes) {
                            throw WatchStoreProtocolException("上传期间 .watch 文件发生了变化")
                        }
                        output.write(buffer, 0, count)
                        onProgress?.invoke(sent, totalFileBytes)
                    }
                    if (sent != totalFileBytes) {
                        throw WatchStoreProtocolException("上传期间 .watch 文件发生了变化")
                    }
                }
                output.write(tail)
            },
        ) { }
    }

    /**
     * Lists all watch faces uploaded by the current user.
     * Requires a valid Bearer token from [WatchStoreAuthStore].
     */
    fun listMyWatchFaces(token: String): List<MyWatchFaceEntry> {
        val safeToken = requireBearerToken(token)
        return requestJsonWithAuth(
            WatchStoreUrlPolicy.myWatchFacesList(base),
            safeToken,
            MAX_JSON_BYTES,
            WatchStoreJsonParser::myWatchFaces,
        )
    }

    /**
     * Permanently deletes an uploaded watch face. The server returns 204 on success.
     * Throws [WatchStoreHttpException] (403) if the caller does not own the face.
     */
    fun deleteMyWatchFace(token: String, id: String) {
        val safeToken = requireBearerToken(token)
        deleteWithAuth(WatchStoreUrlPolicy.myWatchFaceDelete(base, id), safeToken)
    }

    /**
     * Updates metadata for one of the current user's uploads.  [replacementFile]
     * is optional: when absent, the service keeps the existing archive and
     * preview; when present, it atomically replaces both on the service.
     */
    fun updateMyWatchFace(
        token: String,
        id: String,
        name: String,
        description: String,
        screenType: WatchStoreScreenType,
        categoryId: String,
        replacementFile: File? = null,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val safeToken = requireBearerToken(token)
        val normalizedId = WatchStoreUrlPolicy.requireId(id)
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty() && trimmedName.length <= MAX_UPLOAD_NAME_LENGTH) {
            "表盘名称需要 1-$MAX_UPLOAD_NAME_LENGTH 个字符"
        }
        val trimmedDescription = description.trim()
        require(trimmedDescription.isNotEmpty()) { "表盘描述不能为空" }
        require(screenType != WatchStoreScreenType.UNKNOWN) { "屏幕形状无效" }
        val normalizedCategoryId = categoryId.trim().lowercase(Locale.ROOT)
        require(UPLOAD_CATEGORY_ID_PATTERN.matches(normalizedCategoryId)) { "分类无效，请重新选择" }

        val archive = replacementFile
        val totalFileBytes = archive?.let { file ->
            require(file.isFile) { "找不到要替换的 .watch 文件" }
            file.length().also { length ->
                if (length <= 0L) throw WatchStoreProtocolException("要替换的 .watch 文件是空的")
                if (length > MAX_UPLOAD_BYTES) throw WatchStorePayloadTooLargeException(MAX_UPLOAD_BYTES)
            }
        }
        val boundary = "FlueWatchStore${UUID.randomUUID().toString().replace("-", "")}"
        val head = buildString {
            appendFormField(boundary, "name", trimmedName)
            appendFormField(boundary, "description", trimmedDescription)
            appendFormField(boundary, "screen_type", screenType.apiValue)
            appendFormField(boundary, "category_id", normalizedCategoryId)
            if (archive != null) {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"upload.watch\"\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
            }
        }.toByteArray(Charsets.UTF_8)
        val tail = if (archive != null) {
            "\r\n--$boundary--\r\n"
        } else {
            "--$boundary--\r\n"
        }.toByteArray(Charsets.UTF_8)
        put<Unit>(
            url = WatchStoreUrlPolicy.myWatchFaceUpdate(base, normalizedId),
            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = head.size.toLong() + (totalFileBytes ?: 0L) + tail.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $safeToken"),
            readTimeoutMs = if (archive != null) maxOf(config.readTimeoutMs, UPLOAD_READ_TIMEOUT_MS) else config.readTimeoutMs,
            expectJson = false,
            writeBody = { output ->
                output.write(head)
                if (archive != null && totalFileBytes != null) {
                    archive.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var sent = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            sent += count
                            if (sent > totalFileBytes) {
                                throw WatchStoreProtocolException("更新期间 .watch 文件发生了变化")
                            }
                            output.write(buffer, 0, count)
                            onProgress?.invoke(sent, totalFileBytes)
                        }
                        if (sent != totalFileBytes) {
                            throw WatchStoreProtocolException("更新期间 .watch 文件发生了变化")
                        }
                    }
                }
                output.write(tail)
            },
        ) { }
    }

    private fun requireBearerToken(token: String): String {
        val t = token.trim()
        require(t.isNotEmpty() && t.none { it.isWhitespace() || it.isISOControl() }) {
            "登录令牌无效，请重新登录"
        }
        return t
    }

    /**
     * GET with an Authorization: Bearer header. Unlike [request], redirects are
     * hard failures — we must not replay the token against a third-party Location.
     */
    private fun <T> requestJsonWithAuth(url: URI, token: String, maxBytes: Long, parser: (String) -> T): T {
        try {
            try {
                WatchStoreUrlPolicy.requirePublicHttps(url)
            } catch (error: IllegalArgumentException) {
                throw WatchStoreRedirectException(error.message ?: "Watch store connection target is unsafe")
            }
            val connection = connectionFactory(url.toURL())
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = config.connectTimeoutMs
                connection.readTimeout = config.readTimeoutMs
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Flue-Next-WatchStore/1")
                connection.setRequestProperty("Authorization", "Bearer $token")
                val status = connection.responseCode
                if (status in REDIRECT_STATUSES) throw WatchStoreProtocolException("Authenticated request was redirected")
                if (status == 401) throw WatchStoreHttpException(401, "登录已过期，请重新登录")
                if (status !in 200..299) throw writeFailure(status, connection)
                validateContentType(connection, expectPreview = false, expectJson = true)
                val body = readLimited(connection.inputStream, maxBytes).toString(Charsets.UTF_8)
                return parser(body)
            } finally {
                connection.disconnect()
            }
        } catch (error: WatchStoreException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw WatchStoreException("Watch store request failed", error)
        }
    }

    /** DELETE with Bearer auth; expects 204 No Content. */
    private fun deleteWithAuth(url: URI, token: String) {
        try {
            try {
                WatchStoreUrlPolicy.requirePublicHttps(url)
            } catch (error: IllegalArgumentException) {
                throw WatchStoreRedirectException(error.message ?: "Watch store connection target is unsafe")
            }
            val connection = connectionFactory(url.toURL())
            try {
                connection.requestMethod = "DELETE"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = config.connectTimeoutMs
                connection.readTimeout = config.readTimeoutMs
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Flue-Next-WatchStore/1")
                connection.setRequestProperty("Authorization", "Bearer $token")
                val status = connection.responseCode
                if (status in REDIRECT_STATUSES) throw WatchStoreProtocolException("Delete request was redirected")
                if (status == 401) throw WatchStoreHttpException(401, "登录已过期，请重新登录")
                if (status == 403) throw WatchStoreHttpException(403, "没有权限删除这个表盘")
                if (status == 404) throw WatchStoreHttpException(404, "表盘不存在或已被删除")
                if (status !in 200..299 && status != 204) throw writeFailure(status, connection)
            } finally {
                connection.disconnect()
            }
        } catch (error: WatchStoreException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw WatchStoreException("Watch store request failed", error)
        }
    }

    private fun authenticate(url: URI, username: String, password: String): WatchStoreAuthResult {
        val cleanUsername = username.trim()
        require(USERNAME_PATTERN.matches(cleanUsername)) { "用户名需要 3-50 位字母、数字或下划线" }
        require(password.length >= MIN_PASSWORD_LENGTH) { "密码至少需要 $MIN_PASSWORD_LENGTH 位" }
        require(password.length <= MAX_PASSWORD_LENGTH) { "密码过长" }
        val body = JSONObject()
            .put("username", cleanUsername)
            .put("password", password)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return post(
            url = url,
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            writeBody = { output -> output.write(body) },
        ) { payload -> WatchStoreJsonParser.auth(payload) }
    }

    private fun StringBuilder.appendFormField(boundary: String, name: String, value: String) {
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n")
        append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
        append(value).append("\r\n")
    }

    /**
     * Single-shot write channel for request-body endpoints. Unlike
     * [request], a redirect is a hard failure here: replaying a request body
     * (or a bearer token) against a Location chosen by the network is never
     * acceptable.
     */
    private fun <T> write(
        method: String,
        url: URI,
        contentType: String,
        contentLength: Long,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = config.readTimeoutMs,
        expectJson: Boolean = true,
        writeBody: (OutputStream) -> Unit,
        parse: (String) -> T,
    ): T {
        try {
            try {
                WatchStoreUrlPolicy.requirePublicHttps(url)
            } catch (error: IllegalArgumentException) {
                throw WatchStoreRedirectException(
                    error.message ?: "Watch store connection target is unsafe",
                )
            }
            val connection = connectionFactory(url.toURL())
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = config.connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.useCaches = false
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Flue-Next-WatchStore/1")
                connection.setRequestProperty("Content-Type", contentType)
                headers.forEach { (headerName, value) -> connection.setRequestProperty(headerName, value) }
                connection.setFixedLengthStreamingMode(contentLength)
                connection.outputStream.use { output ->
                    writeBody(output)
                    output.flush()
                }
                val status = connection.responseCode
                if (status in REDIRECT_STATUSES) {
                    throw WatchStoreProtocolException("Watch store $method request was redirected")
                }
                if (status !in 200..299) {
                    throw writeFailure(status, connection)
                }
                if (expectJson) validateContentType(connection, expectPreview = false, expectJson = true)
                val payload = readLimited(connection.inputStream, MAX_JSON_BYTES).toString(Charsets.UTF_8)
                return parse(payload)
            } finally {
                connection.disconnect()
            }
        } catch (error: WatchStoreException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw WatchStoreException("Watch store request failed", error)
        }
    }

    private fun <T> post(
        url: URI,
        contentType: String,
        contentLength: Long,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = config.readTimeoutMs,
        expectJson: Boolean = true,
        writeBody: (OutputStream) -> Unit,
        parse: (String) -> T,
    ): T = write(
        method = "POST",
        url = url,
        contentType = contentType,
        contentLength = contentLength,
        headers = headers,
        readTimeoutMs = readTimeoutMs,
        expectJson = expectJson,
        writeBody = writeBody,
        parse = parse,
    )

    private fun <T> put(
        url: URI,
        contentType: String,
        contentLength: Long,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = config.readTimeoutMs,
        expectJson: Boolean = true,
        writeBody: (OutputStream) -> Unit,
        parse: (String) -> T,
    ): T = write(
        method = "PUT",
        url = url,
        contentType = contentType,
        contentLength = contentLength,
        headers = headers,
        readTimeoutMs = readTimeoutMs,
        expectJson = expectJson,
        writeBody = writeBody,
        parse = parse,
    )

    private fun writeFailure(status: Int, connection: HttpURLConnection): WatchStoreHttpException {
        val body = connection.errorStream?.let { stream ->
            runCatching { readLimited(stream, MAX_ERROR_BYTES).toString(Charsets.UTF_8) }.getOrNull()
        }.orEmpty()
        val detail = WatchStoreJsonParser.errorDetail(body)
        val suffix = detail?.let { ": $it" }.orEmpty()
        return WatchStoreHttpException(status, "Watch store request failed ($status)$suffix", detail)
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
        // Keep public-store downloads aligned with WatchArchiveInstaller. A
        // package accepted from local storage must not be rejected solely
        // because it came through the community server.
        const val MAX_WATCH_BYTES: Long = 500L * 1024L * 1024L

        /** Server-side cap for one uploaded `.watch` archive. */
        const val MAX_UPLOAD_BYTES: Long = 50L * 1024L * 1024L
        private const val MAX_UPLOAD_NAME_LENGTH = 100
        private const val MIN_PASSWORD_LENGTH = 6
        private const val MAX_PASSWORD_LENGTH = 128

        /** Preview parsing happens server-side during upload; give it room. */
        private const val UPLOAD_READ_TIMEOUT_MS = 120_000
        private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,50}$")
        private val UPLOAD_CATEGORY_ID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
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
