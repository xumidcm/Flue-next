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
            ),
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
        )
    }

    private fun parseCategory(root: JSONObject): WatchStoreCategory = WatchStoreCategory(
        id = requiredString(root, "id", MAX_URL_LENGTH),
        name = requiredString(root, "name", MAX_NAME_LENGTH),
        slug = requiredString(root, "slug", MAX_NAME_LENGTH),
    )

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

    private fun fail(message: String, cause: Throwable? = null): Nothing =
        throw WatchStoreProtocolException(message, cause)
}
