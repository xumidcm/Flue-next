package com.flue.launcher.watchface.store

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
            // 分段只对「大到值得分」的正片档案开。预览图几十 KB，多开三条连接
            // 净亏握手；关掉开关也直接走单流。
            val allowSegmented = !expectPreview && segmentedDownloads()
            if (allowSegmented) {
                translateTransportErrors {
                    downloadSegmentedOrFallback(url, destination, maxBytes, expectPreview, onProgress)
                }
            } else {
                downloadSingleStream(url, destination, maxBytes, expectPreview, onProgress)
            }
            if (!destination.isFile || destination.length() == 0L) {
                throw WatchStoreProtocolException("Watch store returned an empty file")
            }
            destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    /**
     * 每次下载都重新读一次：[HttpWatchStoreApi] 在 Composable 的 `remember` 里构造，
     * 早于 DataStore 把设置读出来，不能在构造期定值。
     */
    private fun segmentedDownloads(): Boolean = FlueDownloadTuning.segmentedDownloads

    private fun downloadSingleStream(
        url: URI,
        destination: File,
        maxBytes: Long,
        expectPreview: Boolean,
        onProgress: ((bytes: Long, totalBytes: Long?) -> Unit)?,
    ) {
        request(url) { connection ->
            validateContentType(connection, expectPreview = expectPreview, expectJson = false)
            val length = connection.contentLengthLong
            if (length > maxBytes) throw WatchStorePayloadTooLargeException(maxBytes)
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    copyLimited(input, output, maxBytes, length.takeIf { it >= 0L }, onProgress)
                }
            }
        }
    }

    /**
     * 多线程分段下载：先用一个 1 字节的 Range 探针问服务器支不支持，支持就切段并发拉。
     *
     * 探针故意只要 `bytes=0-0`：
     * - 支持 → 206 + `Content-Range: bytes 0-0/<total>`，总长从这里拿，响应体只有 1 字节，
     *   连接干净地还回连接池，下一条分段能复用 socket。
     * - 不支持 → 服务器忽略 Range 直接回 200 和整个文件。这时探针本身就是完整下载，
     *   顺手读完即可，一次请求都不浪费。这就是「静默回退」：调用方看不出区别。
     *
     * 任何一段失败，整次下载失败并删除半成品——分段文件缺一块就是坏包，不能悄悄留下。
     */
    private fun downloadSegmentedOrFallback(
        url: URI,
        destination: File,
        maxBytes: Long,
        expectPreview: Boolean,
        onProgress: ((bytes: Long, totalBytes: Long?) -> Unit)?,
    ) {
        val probe = connect(url, rangeHeader = "bytes=0-0")
        val plan: List<SegmentRange>
        val totalBytes: Long
        val finalUrl: URI
        try {
            validateContentType(probe.connection, expectPreview = expectPreview, expectJson = false)
            if (probe.status != HttpURLConnection.HTTP_PARTIAL) {
                // 没有 206：服务器不认 Range，手上这条连接就是完整响应。
                val length = probe.connection.contentLengthLong
                if (length > maxBytes) throw WatchStorePayloadTooLargeException(maxBytes)
                probe.connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        copyLimited(input, output, maxBytes, length.takeIf { it >= 0L }, onProgress)
                    }
                }
                return
            }
            totalBytes = parseContentRangeTotal(probe.connection.getHeaderField("Content-Range"))
                ?: throw WatchStoreProtocolException("Range response has no total length")
            if (totalBytes > maxBytes) throw WatchStorePayloadTooLargeException(maxBytes)
            if (totalBytes <= 0L) throw WatchStoreProtocolException("Range response declares an empty body")
            finalUrl = probe.finalUrl
            plan = planSegments(totalBytes)
        } finally {
            probe.connection.disconnect()
        }

        if (plan.size <= 1) {
            // 小于分段门槛：并发只会把一次顺序读拆成几次抢带宽的短读。
            downloadSingleStream(url, destination, maxBytes, expectPreview, onProgress)
            return
        }

        // 先把文件撑到最终大小，各段 seek 到自己的偏移直接写。不同 offset 的写
        // 互不重叠，交给内核即可，不需要应用层加锁。
        RandomAccessFile(destination, "rw").use { it.setLength(totalBytes) }

        val fetched = AtomicLong(0L)
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        // 进度回调可能来自任意分段线程，节流器自己 @Synchronized，但状态写入侧
        // 也要串行化，否则 bytes 会前后跳。
        val progressLock = Any()
        val emitProgress: (Long) -> Unit = { delta ->
            val soFar = fetched.addAndGet(delta)
            if (onProgress != null) {
                synchronized(progressLock) { onProgress(soFar, totalBytes) }
            }
        }

        // 第 0 段留给当前线程，只为后面 n-1 段起线程：省一次线程创建，
        // 并且调用线程被阻塞在这里，取消语义与单流版本一致。
        val workers = plan.drop(1).map { segment ->
            Thread {
                try {
                    fetchSegment(finalUrl, destination, segment, aborted, emitProgress)
                } catch (error: Throwable) {
                    aborted.set(true)
                    failure.compareAndSet(null, error)
                }
            }.apply {
                name = "flue-dl-seg-${segment.index}"
                isDaemon = true
                start()
            }
        }
        try {
            fetchSegment(finalUrl, destination, plan.first(), aborted, emitProgress)
        } catch (error: Throwable) {
            aborted.set(true)
            failure.compareAndSet(null, error)
        }
        workers.forEach { worker ->
            // join 不能被跳过：提前返回会让后台线程继续往已被删掉的文件写。
            while (true) {
                try {
                    worker.join()
                    break
                } catch (interrupt: InterruptedException) {
                    aborted.set(true)
                    failure.compareAndSet(null, interrupt)
                    Thread.currentThread().interrupt()
                }
            }
        }
        failure.get()?.let { throw it }

        // 分段下载最容易出的坏包是「少了一段但文件长度对」——setLength 已经把
        // 洞撑成 0 字节了，长度检查看不出来。所以真正的判据是累计写入量。
        val written = fetched.get()
        if (written != totalBytes) {
            throw WatchStoreProtocolException(
                "Segmented download wrote $written of $totalBytes bytes",
            )
        }
        if (destination.length() != totalBytes) {
            throw WatchStoreProtocolException("Segmented download size mismatch")
        }
        onProgress?.invoke(totalBytes, totalBytes)
    }

    private fun fetchSegment(
        url: URI,
        destination: File,
        segment: SegmentRange,
        aborted: java.util.concurrent.atomic.AtomicBoolean,
        onDelta: (Long) -> Unit,
    ) {
        if (aborted.get()) return
        val resolved = connect(url, rangeHeader = "bytes=${segment.start}-${segment.endInclusive}")
        try {
            if (resolved.status != HttpURLConnection.HTTP_PARTIAL) {
                // 探针说支持、真取时又回 200：整个响应是全文件，按分段偏移写进去
                // 就是错的。直接失败，让上层删掉半成品重来。
                throw WatchStoreProtocolException("Server stopped honouring Range mid-download")
            }
            val expected = segment.endInclusive - segment.start + 1
            RandomAccessFile(destination, "rw").use { file ->
                file.seek(segment.start)
                resolved.connection.inputStream.use { input ->
                    val buffer = ByteArray(SEGMENT_BUFFER_BYTES)
                    var remaining = expected
                    while (remaining > 0L) {
                        if (aborted.get()) return
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("Segment ${segment.index} interrupted")
                        }
                        val cap = minOf(remaining, buffer.size.toLong()).toInt()
                        val count = input.read(buffer, 0, cap)
                        if (count < 0) break
                        file.write(buffer, 0, count)
                        remaining -= count
                        onDelta(count.toLong())
                    }
                    if (remaining > 0L && !aborted.get()) {
                        throw WatchStoreProtocolException(
                            "Segment ${segment.index} ended $remaining bytes early",
                        )
                    }
                }
            }
        } finally {
            resolved.connection.disconnect()
        }
    }

    private fun <T> request(url: URI, block: (HttpURLConnection) -> T): T =
        translateTransportErrors {
            val resolved = connect(url, rangeHeader = null)
            try {
                block(resolved.connection)
            } finally {
                resolved.connection.disconnect()
            }
        }

    /**
     * 打开一条已跟完重定向、状态码已校验的连接。
     *
     * 调用方负责 [HttpURLConnection.disconnect]。抽出来是为了让分段下载能拿到
     * 「重定向解析后的最终 URL」——各分段必须都打同一个终点，否则 CDN 轮询会让
     * 不同分段落到不同副本上，拼出来的文件是坏的。
     */
    private fun connect(url: URI, rangeHeader: String?): ResolvedConnection {
        var current = url
        var redirectCount = 0
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
            rangeHeader?.let { connection.setRequestProperty("Range", it) }
            var handOff = false
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
                handOff = true
                return ResolvedConnection(connection, current, status)
            } finally {
                if (!handOff) connection.disconnect()
            }
        }
    }

    private class ResolvedConnection(
        val connection: HttpURLConnection,
        val finalUrl: URI,
        val status: Int,
    )

    private fun <T> translateTransportErrors(block: () -> T): T {
        try {
            return block()
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

        /**
         * 分段门槛。低于这个大小不分：一次顺序读本来就快，切开只会让几条连接
         * 互相抢带宽，还多付 N 次握手。
         */
        internal const val MIN_SEGMENTED_BYTES: Long = 4L * 1024L * 1024L

        /** 每段至少这么大，避免大文件被切成一堆碎块。 */
        private const val MIN_SEGMENT_BYTES: Long = 1L * 1024L * 1024L

        /**
         * 分段并发上限。手表是小核 CPU + 单条 Wi-Fi，超过 4 条基本只增加拥塞
         * 控制的抖动，不再提升吞吐。
         */
        internal const val MAX_SEGMENTS: Int = 4

        private const val SEGMENT_BUFFER_BYTES = 32 * 1024

        /**
         * 把 [totalBytes] 切成不重叠、连续、覆盖全长的分段。
         *
         * 返回单元素表示「不值得分段」，调用方回落单流。
         */
        internal fun planSegments(totalBytes: Long): List<SegmentRange> {
            if (totalBytes < MIN_SEGMENTED_BYTES) {
                return listOf(SegmentRange(0, 0L, totalBytes - 1))
            }
            val byMinSize = (totalBytes / MIN_SEGMENT_BYTES).toInt()
            val count = byMinSize.coerceIn(1, MAX_SEGMENTS)
            if (count <= 1) return listOf(SegmentRange(0, 0L, totalBytes - 1))
            val chunk = totalBytes / count
            return (0 until count).map { index ->
                val start = chunk * index
                // 最后一段吃掉除不尽的余数：用 total-1 而不是 start+chunk-1，
                // 保证分段并集严格等于 [0, total-1]。
                val end = if (index == count - 1) totalBytes - 1 else start + chunk - 1
                SegmentRange(index, start, end)
            }
        }

        /** 从 `Content-Range: bytes 0-0/12345` 里取出总长；`*` 或畸形返回 null。 */
        internal fun parseContentRangeTotal(header: String?): Long? {
            val value = header?.trim() ?: return null
            val total = value.substringAfterLast('/', "").trim()
            if (total.isEmpty() || total == "*") return null
            return total.toLongOrNull()?.takeIf { it > 0L }
        }

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
                // 取消检查放在每个缓冲区边界：阻塞读本身不响应协程取消，
                // 少了这一行，用户点了取消、UI 也回到取消态，网络仍会把整包拉完。
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("Download interrupted after $total bytes")
                }
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
