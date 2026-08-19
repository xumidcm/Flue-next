package com.flue.launcher.watchface.store

import org.json.JSONArray
import org.json.JSONObject

/** JSON parsing kept free of Android UI types so it can be exercised by JVM/Robolectric tests. */
internal object WatchStoreJsonParser {
    private const val MAX_CATEGORY_ITEMS = 500
    private const val MAX_PAGE_ITEMS = 100
    private const val MAX_NAME_LENGTH = 512
    private const val MAX_DESCRIPTION_LENGTH = 32_768
    private const val MAX_URL_LENGTH = 4_096
    private const val MAX_TOKEN_LENGTH = 8_192
    private const val MAX_ERROR_ITEMS = 3
    private const val MAX_ERROR_DETAIL_LENGTH = 300
    private const val MAX_COMMENT_ITEMS = 500
    private const val MAX_COMMENT_LENGTH = 1_000
    private const val MAX_NOTIFICATION_ITEMS = 100
    private const val MAX_CREATOR_ITEMS = 50
    private const val MAX_COLLECTION_ITEMS = 200
    private const val MAX_TAG_ITEMS = 50

    fun categories(payload: String): List<WatchStoreCategory> {
        val root = parseObject(payload)
        val data = requiredArray(root, "data")
        if (data.length() > MAX_CATEGORY_ITEMS) fail("Too many categories")
        return buildList(data.length()) {
            for (index in 0 until data.length()) {
                add(parseCategory(data.optJSONObject(index) ?: fail("categories[$index] is not an object")))
            }
        }
    }

    fun page(payload: String): WatchStorePage {
        val root = parseObject(payload)
        val data = requiredArray(root, "data")
        if (data.length() > MAX_PAGE_ITEMS) fail("Too many watchfaces in one page")
        val pagination = requiredObject(root, "pagination")
        val page = requiredInt(pagination, "page", minimum = 1)
        val limit = requiredInt(pagination, "limit", minimum = 1, maximum = 50)
        val total = requiredLong(pagination, "total", minimum = 0)
        val totalPages = requiredInt(pagination, "total_pages", minimum = 0)
        return WatchStorePage(
            data = buildList(data.length()) {
                for (index in 0 until data.length()) {
                    add(parseSummary(data.optJSONObject(index) ?: fail("data[$index] is not an object")))
                }
            },
            pagination = WatchStorePagination(page, limit, total, totalPages),
        )
    }

    fun detail(payload: String): WatchStoreWatchFaceDetail {
        val root = parseObject(payload)
        val category = parseCategory(requiredObject(root, "category"))
        val uploaderObject = requiredObject(root, "uploader")
        return WatchStoreWatchFaceDetail(
            id = requiredString(root, "id", MAX_URL_LENGTH),
            name = requiredString(root, "name", MAX_NAME_LENGTH),
            description = optionalString(root, "description", MAX_DESCRIPTION_LENGTH),
            screenType = WatchStoreScreenType.fromApi(requiredString(root, "screen_type", 32)),
            category = category,
            previewUrl = requiredString(root, "preview_url", MAX_URL_LENGTH),
            downloadUrl = requiredString(root, "download_url", MAX_URL_LENGTH),
            downloadCount = requiredLong(root, "download_count", minimum = 0),
            createdAt = requiredString(root, "created_at", MAX_URL_LENGTH),
            uploader = WatchStoreUploader(
                id = requiredString(uploaderObject, "id", MAX_URL_LENGTH),
                username = requiredString(uploaderObject, "username", MAX_NAME_LENGTH),
                avatarUrl = optionalStringOrNull(uploaderObject, "avatar_url", MAX_URL_LENGTH),
            ),
            favoriteCount = optionalLong(root, "favorite_count"),
            commentCount = optionalLong(root, "comment_count"),
            views = optionalLong(root, "views"),
            tags = optionalStringArray(root, "tags"),
            wmVersion = optionalStringOrNull(root, "wm_version", 32),
            wmProtected = optionalProtectionFlag(root),
            layerCount = optionalLong(root, "layer_count").toInt(),
            status = WatchStoreReviewStatus.fromApi(optionalString(root, "status", 32)),
            featured = optionalBoolean(root, "featured"),
            rejectionReason = optionalStringOrNull(root, "rejection_reason", MAX_DESCRIPTION_LENGTH),
        )
    }

    /** Parses the `{token, user:{id, username}}` body shared by login and register. */
    fun auth(payload: String): WatchStoreAuthResult {
        val root = parseObject(payload)
        val user = requiredObject(root, "user")
        return WatchStoreAuthResult(
            token = requiredString(root, "token", MAX_TOKEN_LENGTH),
            userId = requiredString(user, "id", MAX_URL_LENGTH),
            username = requiredString(user, "username", MAX_NAME_LENGTH),
        )
    }

    /** Parses the metadata-only response from `POST /my/watchfaces/parse`. */
    fun uploadParse(payload: String): WatchStoreUploadParseResult {
        val root = parseObject(payload)
        val parsed = root.optJSONObject("parsed") ?: JSONObject()
        val scan = root.optJSONObject("scan")
        val rawShape = optionalStringOrNull(parsed, "screen_type", 32)
            ?: optionalStringOrNull(parsed, "shape", 32)
        return WatchStoreUploadParseResult(
            title = optionalString(parsed, "title", MAX_NAME_LENGTH),
            description = optionalString(parsed, "description", MAX_DESCRIPTION_LENGTH),
            tags = optionalStringArray(parsed, "tags"),
            author = optionalString(parsed, "author", MAX_NAME_LENGTH),
            screenType = uploadScreenType(rawShape),
            width = optionalLong(parsed, "width", 512).coerceIn(1L, 16_384L).toInt(),
            height = optionalLong(parsed, "height", 512).coerceIn(1L, 16_384L).toInt(),
            wmVersion = optionalStringOrNull(parsed, "wm_version", 32),
            wmProtected = optionalProtectionFlag(parsed),
            layerCount = optionalLong(parsed, "layer_count").coerceIn(0L, 100_000L).toInt(),
            scanSuccess = scan?.let { optionalBoolean(it, "success") } ?: false,
        )
    }

    /** Parses `GET /api/my/watchfaces` → `{data:[...]}`. */
    fun myWatchFaces(payload: String): List<MyWatchFaceEntry> {
        val root = parseObject(payload)
        val data = requiredArray(root, "data")
        if (data.length() > MAX_PAGE_ITEMS) fail("Too many uploaded watchfaces")
        return buildList(data.length()) {
            for (index in 0 until data.length()) {
                val obj = data.optJSONObject(index) ?: fail("data[$index] is not an object")
                val category = parseCategory(requiredObject(obj, "category"))
                add(
                    MyWatchFaceEntry(
                        id = requiredString(obj, "id", MAX_URL_LENGTH),
                        name = requiredString(obj, "name", MAX_NAME_LENGTH),
                        description = optionalString(obj, "description", MAX_DESCRIPTION_LENGTH),
                        screenType = WatchStoreScreenType.fromApi(
                            requiredString(obj, "screen_type", 32),
                        ),
                        category = category,
                        previewUrl = requiredString(obj, "preview_url", MAX_URL_LENGTH),
                        downloadCount = requiredLong(obj, "download_count", minimum = 0),
                        createdAt = requiredString(obj, "created_at", MAX_URL_LENGTH),
                        status = WatchStoreReviewStatus.fromApi(optionalString(obj, "status", 32)),
                        rejectionReason = optionalStringOrNull(
                            obj,
                            "rejection_reason",
                            MAX_DESCRIPTION_LENGTH,
                        ),
                        favoriteCount = optionalLong(obj, "favorite_count"),
                        commentCount = optionalLong(obj, "comment_count"),
                    ),
                )
            }
        }
    }

    /**
     * Lenient error-body reader for the write endpoints. Understands the
     * service's express-validator shape `{errors:[{msg, path|param}]}` plus
     * plain `{error}` / `{message}` bodies; anything else yields null.
     */
    fun errorDetail(payload: String): String? {
        if (payload.isBlank()) return null
        val root = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return null
        }
        root.optJSONArray("errors")?.let { array ->
            val messages = buildList {
                for (index in 0 until minOf(array.length(), MAX_ERROR_ITEMS)) {
                    val entry = array.optJSONObject(index) ?: continue
                    val message = entry.optString("msg").trim().takeIf(String::isNotEmpty) ?: continue
                    val field = entry.optString("path").trim().ifEmpty { entry.optString("param").trim() }
                    add(if (field.isEmpty()) message else "$field: $message")
                }
            }
            if (messages.isNotEmpty()) {
                return messages.joinToString("；").take(MAX_ERROR_DETAIL_LENGTH)
            }
        }
        return sequenceOf("error", "message")
            .map { key -> root.optString(key).trim() }
            .firstOrNull(String::isNotEmpty)
            ?.take(MAX_ERROR_DETAIL_LENGTH)
    }

    private fun parseSummary(root: JSONObject): WatchStoreWatchFace {
        return WatchStoreWatchFace(
            id = requiredString(root, "id", MAX_URL_LENGTH),
            name = requiredString(root, "name", MAX_NAME_LENGTH),
            screenType = WatchStoreScreenType.fromApi(requiredString(root, "screen_type", 32)),
            category = parseCategory(requiredObject(root, "category")),
            previewUrl = requiredString(root, "preview_url", MAX_URL_LENGTH),
            downloadCount = requiredLong(root, "download_count", minimum = 0),
            createdAt = requiredString(root, "created_at", MAX_URL_LENGTH),
            // 以下都走 optional：老服务端不返回时按 0 / 空处理，不要让一条新字段
            // 把整页列表解析成失败。
            favoriteCount = optionalLong(root, "favorite_count"),
            commentCount = optionalLong(root, "comment_count"),
            views = optionalLong(root, "views"),
            tags = optionalStringArray(root, "tags"),
            wmVersion = optionalStringOrNull(root, "wm_version", 32),
            wmProtected = optionalProtectionFlag(root),
            layerCount = optionalLong(root, "layer_count").toInt(),
        )
    }

    // ── B1 新增解析 ──────────────────────────────────────────────────────────

    /** `GET /my/me`。 */
    fun account(payload: String): WatchStoreAccount {
        val root = parseObject(payload)
        return WatchStoreAccount(
            id = requiredString(root, "id", MAX_URL_LENGTH),
            username = requiredString(root, "username", MAX_NAME_LENGTH),
            role = optionalString(root, "role", MAX_NAME_LENGTH).ifEmpty { "user" },
            disabled = optionalBoolean(root, "disabled"),
            createdAt = optionalString(root, "created_at", MAX_URL_LENGTH),
            description = optionalString(root, "description", MAX_DESCRIPTION_LENGTH),
            timezone = optionalString(root, "timezone", MAX_NAME_LENGTH),
            language = optionalString(root, "language", MAX_NAME_LENGTH),
            avatarUrl = optionalStringOrNull(root, "avatar_url", MAX_URL_LENGTH),
        )
    }

    /** `POST /my/profile/avatar` → `{ok, avatar_url}`。返回头像相对路径。 */
    fun avatarUrl(payload: String): String? =
        optionalStringOrNull(parseObject(payload), "avatar_url", MAX_URL_LENGTH)

    /** `GET /watchfaces/:id/comments` → `{data:[…]}`，最旧在前。 */
    fun comments(payload: String): List<WatchStoreComment> {
        val root = parseObject(payload)
        val data = requiredArray(root, "data")
        if (data.length() > MAX_COMMENT_ITEMS) fail("Too many comments in one response")
        return buildList(data.length()) {
            for (index in 0 until data.length()) {
                add(parseComment(data.optJSONObject(index) ?: fail("data[$index] is not an object")))
            }
        }
    }

    /** 发表 / 编辑评论的响应。上游有时包一层 `data`，有时直接给对象，两种都吃。 */
    fun comment(payload: String): WatchStoreComment {
        val root = parseObject(payload)
        return parseComment(root.optJSONObject("data") ?: root)
    }

    /** `GET /watchfaces/:id/rating` → `{average, count}`。 */
    fun ratingSummary(payload: String): WatchStoreRatingSummary {
        val root = parseObject(payload)
        return WatchStoreRatingSummary(
            average = optionalDouble(root, "average"),
            count = optionalLong(root, "count"),
            mine = optionalIntOrNull(root, "mine", 1, 5),
        )
    }

    /** `GET /my/watchfaces/:id/rating` → `{mine: 4}`，没评过是 null。 */
    fun myRating(payload: String): Int? = optionalIntOrNull(parseObject(payload), "mine", 1, 5)

    /** `POST|DELETE /watchfaces/:id/favorite` → `{ok, favorited, favorite_count}`。 */
    fun favoriteState(payload: String): Boolean = optionalBoolean(parseObject(payload), "favorited")

    fun favoriteCount(payload: String): Long = optionalLong(parseObject(payload), "favorite_count")

    /** `GET /my/notifications` → `{data:[…], unread}`。 */
    fun notifications(payload: String): WatchStoreNotificationPage {
        val root = parseObject(payload)
        val data = requiredArray(root, "data")
        if (data.length() > MAX_NOTIFICATION_ITEMS) fail("Too many notifications in one response")
        return WatchStoreNotificationPage(
            data = buildList(data.length()) {
                for (index in 0 until data.length()) {
                    val obj = data.optJSONObject(index) ?: fail("data[$index] is not an object")
                    add(
                        WatchStoreNotification(
                            id = requiredString(obj, "id", MAX_URL_LENGTH),
                            type = WatchStoreNotificationType.fromApi(
                                optionalString(obj, "type", MAX_NAME_LENGTH),
                            ),
                            title = optionalString(obj, "title", MAX_NAME_LENGTH),
                            message = optionalString(obj, "message", MAX_DESCRIPTION_LENGTH),
                            targetType = optionalStringOrNull(obj, "target_type", MAX_NAME_LENGTH),
                            targetId = optionalStringOrNull(obj, "target_id", MAX_URL_LENGTH),
                            readAt = optionalStringOrNull(obj, "read_at", MAX_URL_LENGTH),
                            createdAt = optionalString(obj, "created_at", MAX_URL_LENGTH),
                        ),
                    )
                }
            },
            unread = optionalLong(root, "unread").toInt(),
        )
    }

    /** `GET /my/notifications/unread-count` → `{unread: 3}`。 */
    fun unreadCount(payload: String): Int = optionalLong(parseObject(payload), "unread").toInt()

    /** `GET /creators/top` → `{data:[…]}`。 */
    fun creators(payload: String): List<WatchStoreCreator> {
        val root = parseObject(payload)
        val data = requiredArray(root, "data")
        if (data.length() > MAX_CREATOR_ITEMS) fail("Too many creators in one response")
        return buildList(data.length()) {
            for (index in 0 until data.length()) {
                add(parseCreator(data.optJSONObject(index) ?: fail("data[$index] is not an object")))
            }
        }
    }

    /** `GET /creators/:username`。 */
    fun creator(payload: String): WatchStoreCreator = parseCreator(parseObject(payload))

    /**
     * `GET /my/favorites` → `{data:[…]}`。
     *
     * **不能复用 [page] 的那份解析**：上游这条查询 select 了 `screen_type` 却没放进响应
     * （`my.js` 的 favorites 分支），必填会直接解析失败。缺了就当 UNKNOWN —— 收藏列表
     * 只用来跳详情，屏形在详情页会拿到。
     */
    fun favorites(payload: String): List<WatchStoreWatchFace> {
        val root = parseObject(payload)
        val data = requiredArray(root, "data")
        if (data.length() > MAX_PAGE_ITEMS) fail("Too many favorites in one response")
        return buildList(data.length()) {
            for (index in 0 until data.length()) {
                val obj = data.optJSONObject(index) ?: fail("data[$index] is not an object")
                add(
                    WatchStoreWatchFace(
                        id = requiredString(obj, "id", MAX_URL_LENGTH),
                        name = requiredString(obj, "name", MAX_NAME_LENGTH),
                        screenType = WatchStoreScreenType.fromApi(
                            optionalString(obj, "screen_type", 32),
                        ),
                        category = parseCategory(requiredObject(obj, "category")),
                        previewUrl = requiredString(obj, "preview_url", MAX_URL_LENGTH),
                        downloadCount = optionalLong(obj, "download_count"),
                        createdAt = optionalString(obj, "created_at", MAX_URL_LENGTH),
                    ),
                )
            }
        }
    }

    /** `GET /my/collections` → `{data:[{id,name,item_count}]}`。 */
    fun collections(payload: String): List<WatchStoreCollection> {
        val root = parseObject(payload)
        val data = requiredArray(root, "data")
        if (data.length() > MAX_COLLECTION_ITEMS) fail("Too many collections in one response")
        return buildList(data.length()) {
            for (index in 0 until data.length()) {
                val obj = data.optJSONObject(index) ?: fail("data[$index] is not an object")
                add(
                    WatchStoreCollection(
                        id = requiredString(obj, "id", MAX_URL_LENGTH),
                        name = requiredString(obj, "name", MAX_NAME_LENGTH),
                        itemCount = optionalLong(obj, "item_count").toInt(),
                    ),
                )
            }
        }
    }

    /** `GET /my/collections/:id/items` → `{data:[{id,name,preview_url}]}`。 */
    fun collectionItems(payload: String): List<WatchStoreCollectionItem> {
        val root = parseObject(payload)
        val data = requiredArray(root, "data")
        if (data.length() > MAX_PAGE_ITEMS) fail("Too many collection items in one response")
        return buildList(data.length()) {
            for (index in 0 until data.length()) {
                val obj = data.optJSONObject(index) ?: fail("data[$index] is not an object")
                add(
                    WatchStoreCollectionItem(
                        id = requiredString(obj, "id", MAX_URL_LENGTH),
                        name = requiredString(obj, "name", MAX_NAME_LENGTH),
                        previewUrl = optionalString(obj, "preview_url", MAX_URL_LENGTH),
                    ),
                )
            }
        }
    }

    /**
     * `GET /config` → `{features:{…}}`。
     *
     * 缺字段按**开启**处理：拿不到配置时不该把界面砍掉，真被禁会在调用时收到 403。
     */
    fun featureFlags(payload: String): WatchStoreFeatureFlags {
        val root = parseObject(payload)
        val features = root.optJSONObject("features") ?: root
        return WatchStoreFeatureFlags(
            socialEnabled = optionalBoolean(features, "social_enabled", default = true),
            commentsEnabled = optionalBoolean(features, "comments_enabled", default = true),
            reviewsEnabled = optionalBoolean(features, "reviews_enabled", default = true),
            registrationEnabled = optionalBoolean(features, "registration_enabled", default = true),
        )
    }

    private fun parseComment(root: JSONObject): WatchStoreComment {
        val user = root.optJSONObject("user")
        return WatchStoreComment(
            id = requiredString(root, "id", MAX_URL_LENGTH),
            content = requiredString(root, "content", MAX_COMMENT_LENGTH),
            parentCommentId = optionalStringOrNull(root, "parent_comment_id", MAX_URL_LENGTH),
            createdAt = optionalString(root, "created_at", MAX_URL_LENGTH),
            authorId = user?.let { optionalString(it, "id", MAX_URL_LENGTH) }.orEmpty(),
            authorUsername = user?.let { optionalString(it, "username", MAX_NAME_LENGTH) }.orEmpty(),
            authorAvatarUrl = user?.let { optionalStringOrNull(it, "avatar_url", MAX_URL_LENGTH) },
        )
    }

    /** Upstream deployments have used both the descriptive and short field names. */
    private fun optionalProtectionFlag(root: JSONObject): Boolean =
        optionalBoolean(root, "wm_protected") ||
            optionalBoolean(root, "protected") ||
            optionalBoolean(root, "is_protected")

    private fun parseCreator(root: JSONObject): WatchStoreCreator = WatchStoreCreator(
        id = optionalString(root, "id", MAX_URL_LENGTH),
        username = requiredString(root, "username", MAX_NAME_LENGTH),
        description = optionalString(root, "description", MAX_DESCRIPTION_LENGTH),
        avatarUrl = optionalStringOrNull(root, "avatar_url", MAX_URL_LENGTH),
        watchFaceCount = optionalLong(root, "watchface_count").toInt(),
        totalDownloads = optionalLong(root, "total_downloads"),
    )

    private fun parseCategory(root: JSONObject): WatchStoreCategory = WatchStoreCategory(
        id = requiredString(root, "id", MAX_URL_LENGTH),
        name = requiredString(root, "name", MAX_NAME_LENGTH),
        slug = requiredString(root, "slug", MAX_NAME_LENGTH),
    )

    private fun uploadScreenType(raw: String?): WatchStoreScreenType {
        val value = raw?.trim()?.lowercase() ?: return WatchStoreScreenType.UNKNOWN
        return when {
            value == "r" || value == "round" -> WatchStoreScreenType.ROUND
            value == "s" || value == "square" -> WatchStoreScreenType.SQUARE
            value == "a" || value.startsWith("a") || value == "rectangular" ->
                WatchStoreScreenType.RECTANGULAR
            else -> WatchStoreScreenType.UNKNOWN
        }
    }

    private fun parseObject(payload: String): JSONObject {
        if (payload.isBlank()) fail("Empty JSON response")
        return try {
            JSONObject(payload)
        } catch (error: Exception) {
            fail("Malformed JSON response", error)
        }
    }

    private fun requiredObject(root: JSONObject, key: String): JSONObject =
        root.optJSONObject(key) ?: fail("Missing object: $key")

    private fun requiredArray(root: JSONObject, key: String): JSONArray =
        root.optJSONArray(key) ?: fail("Missing array: $key")

    private fun requiredString(root: JSONObject, key: String, maximumLength: Int): String {
        if (!root.has(key) || root.isNull(key)) fail("Missing string: $key")
        val value = root.optString(key, "").trim()
        if (value.isEmpty() || value.length > maximumLength || value.indexOf('\u0000') >= 0) {
            fail("Invalid string: $key")
        }
        return value
    }

    private fun optionalString(root: JSONObject, key: String, maximumLength: Int): String {
        if (!root.has(key) || root.isNull(key)) return ""
        val value = root.optString(key, "").trim()
        if (value.length > maximumLength || value.indexOf('\u0000') >= 0) fail("Invalid string: $key")
        return value
    }

    private fun requiredLong(
        root: JSONObject,
        key: String,
        minimum: Long,
    ): Long {
        if (!root.has(key) || root.isNull(key)) fail("Missing number: $key")
        val value = when (val raw = root.opt(key)) {
            is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
            is Float -> raw.takeIf { it.isFinite() && it == it.toLong().toFloat() }?.toLong()
            is Double -> raw.takeIf { it.isFinite() && it == it.toLong().toDouble() }?.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: fail("Invalid number: $key")
        if (value < minimum) fail("Number out of range: $key")
        return value
    }

    private fun requiredInt(
        root: JSONObject,
        key: String,
        minimum: Int,
        maximum: Int = Int.MAX_VALUE,
    ): Int {
        val value = requiredLong(root, key, minimum.toLong())
        if (value > maximum) fail("Number out of range: $key")
        return value.toInt()
    }

    /** 缺字段 / null 给 null，而不是空串 —— 「没有头像」与「头像地址是空串」不是一回事。 */
    private fun optionalStringOrNull(root: JSONObject, key: String, maximumLength: Int): String? {
        if (!root.has(key) || root.isNull(key)) return null
        val value = root.optString(key, "").trim()
        if (value.isEmpty()) return null
        if (value.length > maximumLength || value.indexOf('\u0000') >= 0) fail("Invalid string: $key")
        return value
    }

    /** 缺字段给 0。新增的计数类字段一律走这个，老服务端不返回时不该解析失败。 */
    private fun optionalLong(root: JSONObject, key: String, default: Long = 0): Long {
        if (!root.has(key) || root.isNull(key)) return default
        return when (val raw = root.opt(key)) {
            is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
            is Float -> if (raw.isFinite()) raw.toLong() else default
            is Double -> if (raw.isFinite()) raw.toLong() else default
            is String -> raw.trim().toLongOrNull() ?: default
            else -> default
        }
    }

    private fun optionalDouble(root: JSONObject, key: String, default: Double = 0.0): Double {
        if (!root.has(key) || root.isNull(key)) return default
        return when (val raw = root.opt(key)) {
            is Number -> raw.toDouble().takeIf { it.isFinite() } ?: default
            is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: default
            else -> default
        }
    }

    /** 范围外的值当「没有」，而不是硬塞一个非法评分进 UI。 */
    private fun optionalIntOrNull(root: JSONObject, key: String, minimum: Int, maximum: Int): Int? {
        if (!root.has(key) || root.isNull(key)) return null
        val value = optionalLong(root, key, Long.MIN_VALUE)
        if (value == Long.MIN_VALUE) return null
        return value.toInt().takeIf { it in minimum..maximum }
    }

    private fun optionalBoolean(root: JSONObject, key: String, default: Boolean = false): Boolean {
        if (!root.has(key) || root.isNull(key)) return default
        return when (val raw = root.opt(key)) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().equals("true", ignoreCase = true) || raw.trim() == "1"
            else -> default
        }
    }

    /** 标签数组。非字符串项直接跳过，不因为一个脏元素废掉整条记录。 */
    private fun optionalStringArray(root: JSONObject, key: String): List<String> {
        val array = root.optJSONArray(key) ?: return emptyList()
        if (array.length() > MAX_TAG_ITEMS) fail("Too many tags")
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.optString(index, "").trim()
                if (value.isEmpty() || value.length > MAX_NAME_LENGTH) continue
                if (value.indexOf('\u0000') >= 0) continue
                add(value)
            }
        }
    }

    private fun fail(message: String, cause: Throwable? = null): Nothing =
        throw WatchStoreProtocolException(message, cause)
}
