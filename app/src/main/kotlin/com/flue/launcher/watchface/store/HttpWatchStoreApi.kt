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

/** Minimal HTTP(S) transport; deliberately does not add an HTTP client dependency. */
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

    /**
     * H2 / H3 / H5 — 头像这类站内图片。
     *
     * 地址是**服务端 JSON 里的字符串**，所以先过 [WatchStoreUrlPolicy.siteImageUrlOrNull]：
     * 解析不出来、或者解析出来落在站外，一律拒绝。跟着远端数据往任意主机发请求，等于让
     * 服务端指挥客户端出站 —— 这条防线比「少显示一张头像」重要得多。
     *
     * 体积与内容类型按预览图那一档收（`expectPreview = true` 会校验响应是图片），头像比
     * 预览图小得多，这个上限只是兜底。
     */
    override fun downloadSiteImage(rawUrl: String?, destination: File): File {
        val url = WatchStoreUrlPolicy.siteImageUrlOrNull(base, rawUrl)
            ?: throw WatchStoreProtocolException("图片地址不在市场站点内")
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
        tags: List<String> = emptyList(),
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
        val normalizedTags = tags.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_UPLOAD_TAGS)
            .onEach { require(it.length <= MAX_UPLOAD_TAG_LENGTH) { "标签过长" } }
            .toList()
        // 按商店规则先在本地查一遍。服务端对创建请求**永远返回 201**，扫描不通过只是
        // 悄悄转成待审 + 站内通知，所以不在这里拦住的话，作者只会看到「上传成功」。
        // 两个上传入口（编辑器与商店页）都经这里，查一次两边都覆盖。
        WatchStoreUploadPreflight.requireCompliant(file)

        fun uploadMultipart(url: URI, includeMetadata: Boolean, progress: ((Long, Long) -> Unit)?) {
            val boundary = "FlueWatchStore${UUID.randomUUID().toString().replace("-", "")}"
            val head = buildString {
                if (includeMetadata) {
                    appendFormField(boundary, "name", trimmedName)
                    appendFormField(boundary, "description", trimmedDescription)
                    appendFormField(boundary, "screen_type", screenType.apiValue)
                    appendFormField(boundary, "category_id", normalizedCategoryId)
                    if (normalizedTags.isNotEmpty()) {
                        appendFormField(boundary, "tags", normalizedTags.joinToString(","))
                    }
                }
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"upload.watch\"\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            post(
                url = url,
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
                            progress?.invoke(sent, totalFileBytes)
                        }
                        if (sent != totalFileBytes) {
                            throw WatchStoreProtocolException("上传期间 .watch 文件发生了变化")
                        }
                    }
                    output.write(tail)
                },
            ) { }
        }

        // The current community-store API first parses/scans the archive, then
        // creates the authenticated record with metadata.
        uploadMultipart(WatchStoreUrlPolicy.myWatchFacesParse(base), includeMetadata = false, progress = null)
        uploadMultipart(WatchStoreUrlPolicy.myWatchFacesList(base), includeMetadata = true, progress = onProgress)
    }

    /**
     * Parses an archive without storing it. The response is deliberately exposed to the UI so
     * server-extracted metadata can prefill the editable upload form.
     */
    fun parseWatchFace(token: String, file: File): WatchStoreUploadParseResult {
        val safeToken = token.trim()
        require(safeToken.isNotEmpty() && safeToken.none { it.isWhitespace() || it.isISOControl() }) {
            "登录令牌无效，请重新登录"
        }
        require(file.isFile) { "找不到要解析的 .watch 文件" }
        val totalFileBytes = file.length()
        if (totalFileBytes <= 0L) throw WatchStoreProtocolException("要解析的 .watch 文件是空的")
        if (totalFileBytes > MAX_UPLOAD_BYTES) {
            throw WatchStorePayloadTooLargeException(MAX_UPLOAD_BYTES)
        }
        val boundary = "FlueWatchStoreParse${UUID.randomUUID().toString().replace("-", "")}"
        val head = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"upload.watch\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return post(
            url = WatchStoreUrlPolicy.myWatchFacesParse(base),
            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = head.size.toLong() + totalFileBytes + tail.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $safeToken"),
            readTimeoutMs = maxOf(config.readTimeoutMs, UPLOAD_READ_TIMEOUT_MS),
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
                            throw WatchStoreProtocolException("解析期间 .watch 文件发生了变化")
                        }
                        output.write(buffer, 0, count)
                    }
                    if (sent != totalFileBytes) {
                        throw WatchStoreProtocolException("解析期间 .watch 文件发生了变化")
                    }
                }
                output.write(tail)
            },
            parse = WatchStoreJsonParser::uploadParse,
        )
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
            // 换包也要过同一道预检 —— 否则「改一版再传」正好绕开上传时的检查。
            WatchStoreUploadPreflight.requireCompliant(file)
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

    // ── B1：个人中心 ─────────────────────────────────────────────────────────

    fun getAccount(token: String): WatchStoreAccount = requestJsonWithAuth(
        url = WatchStoreUrlPolicy.myAccount(base),
        token = requireBearerToken(token),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::account,
    )

    /**
     * 改资料。只提交非 null 的字段（上游按「任意子集」处理）。
     *
     * 用的是 **PATCH**：Android 的 `HttpURLConnection` 由 OkHttp 支撑、允许 PATCH，
     * 桌面 JDK 的实现不允许 —— 测试里用的是假的连接工厂，所以不受影响。
     */
    fun updateProfile(token: String, update: WatchStoreProfileUpdate): WatchStoreAccount {
        val bearer = requireBearerToken(token)
        val body = JSONObject().apply {
            update.description?.let { put("description", it) }
            update.timezone?.let { put("timezone", it) }
            update.language?.let { put("language", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        return write(
            method = "PATCH",
            url = WatchStoreUrlPolicy.myProfile(base),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            writeBody = { output -> output.write(body) },
            parse = WatchStoreJsonParser::account,
        )
    }

    /**
     * 上传头像。
     *
     * 上游按 **magic bytes** 校验类型（不看扩展名）、限 2MB，所以这里只做尺寸预检，
     * 类型交给服务端 —— 客户端猜类型猜错了反而会拦下合法文件。
     */
    fun uploadAvatar(token: String, avatar: File): String? {
        val bearer = requireBearerToken(token)
        require(avatar.isFile) { "头像文件不存在" }
        val length = avatar.length()
        require(length in 1..MAX_AVATAR_BYTES) { "头像需要小于 2MB" }
        val boundary = "----FlueAvatar${System.nanoTime().toString(16)}"
        val header = StringBuilder()
            .append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"file\"; filename=\"avatar\"\r\n")
            .append("Content-Type: application/octet-stream\r\n\r\n")
            .toString()
            .toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return post(
            url = WatchStoreUrlPolicy.myProfileAvatar(base),
            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = header.size + length + footer.size,
            headers = mapOf("Authorization" to "Bearer $bearer"),
            writeBody = { output ->
                output.write(header)
                avatar.inputStream().use { input -> input.copyTo(output, DEFAULT_COPY_BUFFER) }
                output.write(footer)
            },
            parse = WatchStoreJsonParser::avatarUrl,
        )
    }

    fun changePassword(token: String, currentPassword: String, newPassword: String) {
        val bearer = requireBearerToken(token)
        require(newPassword.length >= MIN_PASSWORD_LENGTH) { "密码至少需要 $MIN_PASSWORD_LENGTH 位" }
        require(newPassword.length <= MAX_PASSWORD_LENGTH) { "密码过长" }
        require(newPassword != currentPassword) { "新密码不能与当前密码相同" }
        val body = JSONObject()
            .put("currentPassword", currentPassword)
            .put("newPassword", newPassword)
            .toString()
            .toByteArray(Charsets.UTF_8)
        post(
            url = WatchStoreUrlPolicy.myPassword(base),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            expectJson = false,
            writeBody = { output -> output.write(body) },
            parse = { },
        )
    }

    // ── B1：上架状态 ─────────────────────────────────────────────────────────

    fun unlistMyWatchFace(token: String, id: String) =
        postEmptyWithAuth(WatchStoreUrlPolicy.myWatchFaceUnlist(base, id), token)

    fun republishMyWatchFace(token: String, id: String) =
        postEmptyWithAuth(WatchStoreUrlPolicy.myWatchFaceRepublish(base, id), token)

    // ── B1：评论 ─────────────────────────────────────────────────────────────

    fun getComments(watchFaceId: String): List<WatchStoreComment> = requestJson(
        url = WatchStoreUrlPolicy.comments(base, watchFaceId),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::comments,
    )

    fun postComment(
        token: String,
        watchFaceId: String,
        content: String,
        parentCommentId: String? = null,
    ): WatchStoreComment {
        val bearer = requireBearerToken(token)
        val trimmed = content.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_COMMENT_LENGTH) {
            "评论需要 1-$MAX_COMMENT_LENGTH 个字符"
        }
        val body = JSONObject().apply {
            put("content", trimmed)
            // 上游只做一级回复，所以这里也只透传一层 parentId，不做嵌套。
            parentCommentId?.let { put("parentId", WatchStoreUrlPolicy.requireId(it)) }
        }.toString().toByteArray(Charsets.UTF_8)
        return post(
            url = WatchStoreUrlPolicy.comments(base, watchFaceId),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            writeBody = { output -> output.write(body) },
            parse = WatchStoreJsonParser::comment,
        )
    }

    fun editMyComment(token: String, commentId: String, content: String): WatchStoreComment {
        val bearer = requireBearerToken(token)
        val trimmed = content.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_COMMENT_LENGTH) {
            "评论需要 1-$MAX_COMMENT_LENGTH 个字符"
        }
        val body = JSONObject().put("content", trimmed).toString().toByteArray(Charsets.UTF_8)
        return put(
            url = WatchStoreUrlPolicy.myComment(base, commentId),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            writeBody = { output -> output.write(body) },
            parse = WatchStoreJsonParser::comment,
        )
    }

    fun deleteMyComment(token: String, commentId: String) = deleteWithAuth(
        WatchStoreUrlPolicy.myComment(base, commentId),
        requireBearerToken(token),
    )

    // ── B1：喜欢 ─────────────────────────────────────────────────────────────

    /** @return 操作后是否已收藏。 */
    fun addFavorite(token: String, watchFaceId: String): Boolean = postEmptyWithAuth(
        url = WatchStoreUrlPolicy.favorite(base, watchFaceId),
        token = token,
        parse = WatchStoreJsonParser::favoriteState,
    )

    fun removeFavorite(token: String, watchFaceId: String) = deleteWithAuth(
        WatchStoreUrlPolicy.favorite(base, watchFaceId),
        requireBearerToken(token),
    )

    fun listMyFavorites(token: String): List<WatchStoreWatchFace> = requestJsonWithAuth(
        url = WatchStoreUrlPolicy.myFavorites(base),
        token = requireBearerToken(token),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::favorites,
    )

    fun isFavorited(token: String, watchFaceId: String): Boolean = requestJsonWithAuth(
        url = WatchStoreUrlPolicy.myFavoriteState(base, watchFaceId),
        token = requireBearerToken(token),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::favoriteState,
    )

    // ── B1：评分 ─────────────────────────────────────────────────────────────

    fun getRating(watchFaceId: String): WatchStoreRatingSummary = requestJson(
        url = WatchStoreUrlPolicy.rating(base, watchFaceId),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::ratingSummary,
    )

    fun setRating(token: String, watchFaceId: String, rating: Int): WatchStoreRatingSummary {
        val bearer = requireBearerToken(token)
        require(rating in 1..5) { "评分需要在 1-5 之间" }
        val body = JSONObject().put("rating", rating).toString().toByteArray(Charsets.UTF_8)
        return post(
            url = WatchStoreUrlPolicy.rating(base, watchFaceId),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            writeBody = { output -> output.write(body) },
            parse = WatchStoreJsonParser::ratingSummary,
        )
    }

    fun getMyRating(token: String, watchFaceId: String): Int? = requestJsonWithAuth(
        url = WatchStoreUrlPolicy.myRating(base, watchFaceId),
        token = requireBearerToken(token),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::myRating,
    )

    // ── B1：通知 ─────────────────────────────────────────────────────────────

    fun listNotifications(token: String, limit: Int = 20): WatchStoreNotificationPage =
        requestJsonWithAuth(
            url = WatchStoreUrlPolicy.myNotifications(base, limit),
            token = requireBearerToken(token),
            maxBytes = MAX_JSON_BYTES,
            parser = WatchStoreJsonParser::notifications,
        )

    fun getUnreadNotificationCount(token: String): Int = requestJsonWithAuth(
        url = WatchStoreUrlPolicy.myNotificationsUnreadCount(base),
        token = requireBearerToken(token),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::unreadCount,
    )

    fun markNotificationRead(token: String, notificationId: String) =
        postEmptyWithAuth(
            WatchStoreUrlPolicy.myNotificationRead(base, notificationId),
            token,
        )

    fun markAllNotificationsRead(token: String) =
        postEmptyWithAuth(WatchStoreUrlPolicy.myNotificationsReadAll(base), token)

    // ── B1：创作者 ───────────────────────────────────────────────────────────

    fun getTopCreators(limit: Int = 10): List<WatchStoreCreator> = requestJson(
        url = WatchStoreUrlPolicy.topCreators(base, limit),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::creators,
    )

    fun getCreator(username: String): WatchStoreCreator = requestJson(
        url = WatchStoreUrlPolicy.creator(base, username),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::creator,
    )

    fun listCreatorWatchFaces(username: String, page: Int = 1, limit: Int = 20): WatchStorePage =
        requestJson(
            url = WatchStoreUrlPolicy.creatorWatchFaces(base, username, page, limit),
            maxBytes = MAX_JSON_BYTES,
            parser = WatchStoreJsonParser::page,
        )

    // ── B1：合集 ─────────────────────────────────────────────────────────────

    fun listMyCollections(token: String): List<WatchStoreCollection> = requestJsonWithAuth(
        url = WatchStoreUrlPolicy.myCollections(base),
        token = requireBearerToken(token),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::collections,
    )

    fun createCollection(token: String, name: String) {
        val bearer = requireBearerToken(token)
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_COLLECTION_NAME_LENGTH) {
            "合集名称需要 1-$MAX_COLLECTION_NAME_LENGTH 个字符"
        }
        val body = JSONObject().put("name", trimmed).toString().toByteArray(Charsets.UTF_8)
        post(
            url = WatchStoreUrlPolicy.myCollections(base),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            expectJson = false,
            writeBody = { output -> output.write(body) },
            parse = { },
        )
    }

    fun renameCollection(token: String, collectionId: String, name: String) {
        val bearer = requireBearerToken(token)
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_COLLECTION_NAME_LENGTH) {
            "合集名称需要 1-$MAX_COLLECTION_NAME_LENGTH 个字符"
        }
        val body = JSONObject().put("name", trimmed).toString().toByteArray(Charsets.UTF_8)
        write(
            method = "PATCH",
            url = WatchStoreUrlPolicy.myCollection(base, collectionId),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            expectJson = false,
            writeBody = { output -> output.write(body) },
            parse = { },
        )
    }

    fun deleteCollection(token: String, collectionId: String) = deleteWithAuth(
        WatchStoreUrlPolicy.myCollection(base, collectionId),
        requireBearerToken(token),
    )

    fun listCollectionItems(token: String, collectionId: String): List<WatchStoreCollectionItem> =
        requestJsonWithAuth(
            url = WatchStoreUrlPolicy.myCollectionItems(base, collectionId),
            token = requireBearerToken(token),
            maxBytes = MAX_JSON_BYTES,
            parser = WatchStoreJsonParser::collectionItems,
        )

    fun addCollectionItem(token: String, collectionId: String, watchFaceId: String) {
        val bearer = requireBearerToken(token)
        val body = JSONObject()
            .put("watchface_id", WatchStoreUrlPolicy.requireId(watchFaceId))
            .toString()
            .toByteArray(Charsets.UTF_8)
        post(
            url = WatchStoreUrlPolicy.myCollectionItems(base, collectionId),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            expectJson = false,
            writeBody = { output -> output.write(body) },
            parse = { },
        )
    }

    fun removeCollectionItem(token: String, collectionId: String, watchFaceId: String) =
        deleteWithAuth(
            WatchStoreUrlPolicy.myCollectionItem(base, collectionId, watchFaceId),
            requireBearerToken(token),
        )

    // ── B1：举报与功能开关 ───────────────────────────────────────────────────

    fun reportWatchFace(
        token: String,
        watchFaceId: String,
        reason: WatchStoreReportReason,
        message: String? = null,
    ) {
        val bearer = requireBearerToken(token)
        val body = JSONObject().apply {
            put("reason", reason.apiValue)
            message?.trim()?.takeIf(String::isNotEmpty)?.let { put("message", it.take(MAX_REPORT_MESSAGE_LENGTH)) }
        }.toString().toByteArray(Charsets.UTF_8)
        post(
            url = WatchStoreUrlPolicy.report(base, watchFaceId),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            expectJson = false,
            writeBody = { output -> output.write(body) },
            parse = { },
        )
    }

    /** 公开开关，不带 token。UI 必须尊重它 —— 禁用的功能隐藏入口，别让用户点了才吃 403。 */
    fun getFeatureFlags(): WatchStoreFeatureFlags = requestJson(
        url = WatchStoreUrlPolicy.config(base),
        maxBytes = MAX_JSON_BYTES,
        parser = WatchStoreJsonParser::featureFlags,
    )

    /** 无请求体的鉴权 POST（unlist / republish / 标记已读 / 收藏）。 */
    private fun <T> postEmptyWithAuth(url: URI, token: String, parse: (String) -> T): T = post(
        url = url,
        contentType = "application/json; charset=utf-8",
        contentLength = EMPTY_JSON_BODY.size.toLong(),
        headers = mapOf("Authorization" to "Bearer ${requireBearerToken(token)}"),
        writeBody = { output -> output.write(EMPTY_JSON_BODY) },
        parse = parse,
    )

    private fun postEmptyWithAuth(url: URI, token: String) {
        post(
            url = url,
            contentType = "application/json; charset=utf-8",
            contentLength = EMPTY_JSON_BODY.size.toLong(),
            headers = mapOf("Authorization" to "Bearer ${requireBearerToken(token)}"),
            expectJson = false,
            writeBody = { output -> output.write(EMPTY_JSON_BODY) },
            parse = { },
        )
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
        const val MAX_UPLOAD_BYTES: Long = 20L * 1024L * 1024L
        private const val MAX_UPLOAD_NAME_LENGTH = 100
        private const val MAX_UPLOAD_TAGS = 10
        private const val MAX_UPLOAD_TAG_LENGTH = 50

        /** 界面侧的密码框也要按它拦，不然只有 `require` 抛异常一条路（H2 的改密码）。 */
        const val MIN_PASSWORD_LENGTH = 6

        // ── B1 ───────────────────────────────────────────────────────────────
        /** 上游按 magic bytes 校验类型、限 2MB。与 [WatchStoreAvatarRules.MAX_BYTES] 是同一个数。 */
        const val MAX_AVATAR_BYTES: Long = 2L * 1024L * 1024L
        private const val MAX_COMMENT_LENGTH = 1_000

        /** 界面侧的输入框也要按它截断，不然只有 `require` 抛异常一条路。 */
        const val MAX_COLLECTION_NAME_LENGTH = 100
        private const val MAX_REPORT_MESSAGE_LENGTH = 1_000
        private const val DEFAULT_COPY_BUFFER = 32 * 1024
        /** 无请求体的鉴权 POST 也要给一个合法 JSON —— 空体有些反代会直接 400。 */
        private val EMPTY_JSON_BODY = "{}".toByteArray(Charsets.UTF_8)
        const val MAX_PASSWORD_LENGTH = 128

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
