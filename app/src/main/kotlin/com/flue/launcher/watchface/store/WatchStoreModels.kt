package com.flue.launcher.watchface.store

import androidx.compose.runtime.Immutable
import java.util.Locale

/** Public WatchMaker community-store endpoint used by Flue Next. */
const val DEFAULT_WATCH_STORE_BASE_URL: String = "https://jbwatchface.ai-life.xyz/api"

enum class WatchStoreSort(val apiValue: String) {
    LATEST("latest"),
    POPULAR("popular"),

    /**
     * 上游新增的排序（`services/trending.js` 的日活聚合）。
     *
     * 聚合表为空或不可用时上游自己会退回 `latest`，所以客户端不需要兜底。
     */
    TRENDING("trending"),
}

enum class WatchStoreScreenType(val apiValue: String) {
    ROUND("round"),
    SQUARE("square"),
    RECTANGULAR("rectangular"),
    UNKNOWN("unknown");

    companion object {
        internal fun fromApi(value: String): WatchStoreScreenType =
            entries.firstOrNull { it.apiValue == value.lowercase(Locale.ROOT) } ?: UNKNOWN
    }
}

/** Store installs are square unless the server explicitly labels them round. */
fun WatchStoreScreenType.installAsRound(): Boolean = this == WatchStoreScreenType.ROUND

data class WatchStoreQuery(
    val page: Int = 1,
    val limit: Int = 20,
    val sort: WatchStoreSort = WatchStoreSort.LATEST,
    val screenType: WatchStoreScreenType? = null,
    val categorySlug: String? = null,
    val search: String? = null,
    /** 标签筛选（上游 migration 005）。上游限 1–50 字符。 */
    val tag: String? = null,
    /** 表盘格式筛选（migration 006）。上游只认 `1.0` / `2.0`。 */
    val wmVersion: String? = null,
) {
    init {
        require(page >= 1) { "page must be at least 1" }
        require(limit in 1..50) { "limit must be between 1 and 50" }
        require(screenType != WatchStoreScreenType.UNKNOWN) { "UNKNOWN is not a valid API filter" }
        require(categorySlug.orEmpty().length <= MAX_FILTER_LENGTH) {
            "categorySlug is too long"
        }
        require(search.orEmpty().length <= MAX_SEARCH_LENGTH) { "search is too long" }
        require(tag.orEmpty().length <= MAX_TAG_LENGTH) { "tag is too long" }
        // 上游把 wm_version 限死在两个值上，传别的会被 400 顶回来。与其让用户看一条
        // 服务端报错，不如在构造时就说清楚。
        require(wmVersion == null || wmVersion in ALLOWED_WM_VERSIONS) {
            "wm_version must be 1.0 or 2.0"
        }
    }

    private companion object {
        const val MAX_FILTER_LENGTH = 128
        const val MAX_SEARCH_LENGTH = 200
        const val MAX_TAG_LENGTH = 50
        val ALLOWED_WM_VERSIONS = setOf("1.0", "2.0")
    }
}

@Immutable
data class WatchStoreCategory(
    val id: String,
    val name: String,
    val slug: String,
)

@Immutable
data class WatchStoreWatchFace(
    val id: String,
    val name: String,
    val screenType: WatchStoreScreenType,
    val category: WatchStoreCategory,
    /** Relative API path in the current service; never fetch this without URL validation. */
    val previewUrl: String,
    val downloadCount: Long,
    val createdAt: String,
    // 以下是上游 `formatWatchface` 一直在返回、而我们一直丢掉的字段。全部给默认值：
    // 老服务端不返回时不至于解析失败，现有构造点也不用逐个改。
    val favoriteCount: Long = 0,
    val commentCount: Long = 0,
    val views: Long = 0,
    val tags: List<String> = emptyList(),
    /** `1.0` / `2.0`，老表盘可能没有。 */
    val wmVersion: String? = null,
    /** 上游标记的「受保护」表盘。 */
    val wmProtected: Boolean = false,
    val layerCount: Int = 0,
)

data class WatchStoreUploader(
    val id: String,
    val username: String,
    /** 相对 API 路径；没上传过头像时上游给 null。取用前照样要过 URL 校验。 */
    val avatarUrl: String? = null,
)

/**
 * 上架状态。
 *
 * `UNKNOWN` 是刻意留的：上游以后加新状态时，客户端应当照样能显示这一项，而不是整条列表
 * 解析失败。
 */
enum class WatchStoreReviewStatus(val apiValue: String) {
    PUBLISHED("published"),
    PENDING("pending"),
    REJECTED("rejected"),
    DELISTED("delisted"),
    UNKNOWN("unknown");

    companion object {
        internal fun fromApi(value: String): WatchStoreReviewStatus =
            entries.firstOrNull { it.apiValue == value.trim().lowercase(Locale.ROOT) } ?: UNKNOWN
    }
}

/** Result of a successful login/registration. The service issues 7-day JWTs. */
data class WatchStoreAuthResult(
    val token: String,
    val userId: String,
    val username: String,
)

/** Metadata extracted by `POST /my/watchfaces/parse` before an upload is stored. */
@Immutable
data class WatchStoreUploadParseResult(
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val author: String = "",
    val screenType: WatchStoreScreenType = WatchStoreScreenType.UNKNOWN,
    val width: Int = 512,
    val height: Int = 512,
    val wmVersion: String? = null,
    val wmProtected: Boolean = false,
    val layerCount: Int = 0,
    val scanSuccess: Boolean = false,
)

@Immutable
data class WatchStoreWatchFaceDetail(
    val id: String,
    val name: String,
    val description: String,
    val screenType: WatchStoreScreenType,
    val category: WatchStoreCategory,
    val previewUrl: String,
    val downloadUrl: String,
    val downloadCount: Long,
    val createdAt: String,
    val uploader: WatchStoreUploader,
    val favoriteCount: Long = 0,
    val commentCount: Long = 0,
    val views: Long = 0,
    val tags: List<String> = emptyList(),
    val wmVersion: String? = null,
    val wmProtected: Boolean = false,
    val layerCount: Int = 0,
    val status: WatchStoreReviewStatus = WatchStoreReviewStatus.PUBLISHED,
    val featured: Boolean = false,
    /** 只有被驳回时才有内容。 */
    val rejectionReason: String? = null,
)

data class WatchStorePagination(
    val page: Int,
    val limit: Int,
    val total: Long,
    val totalPages: Int,
) {
    val hasNextPage: Boolean get() = page < totalPages
}

data class WatchStorePage(
    val data: List<WatchStoreWatchFace>,
    val pagination: WatchStorePagination,
)

/** One entry in the current user's uploaded watch-face list (`GET /api/my/watchfaces`). */
@Immutable
data class MyWatchFaceEntry(
    val id: String,
    val name: String,
    val description: String,
    val screenType: WatchStoreScreenType,
    val category: WatchStoreCategory,
    /** Relative API path; validate before fetching. */
    val previewUrl: String,
    val downloadCount: Long,
    val createdAt: String,
    /**
     * 审核状态与驳回原因。
     *
     * 这两个是老师那条反馈的落点：上传**永远返回 201**（上游 `my.js` 明写
     * 「A scanner failure NEVER blocks the upload」），被拦只体现在这里。我的上传列表
     * 必须把它们显示出来，否则用户只看到「上传成功」，然后表盘一直不出现。
     */
    val status: WatchStoreReviewStatus = WatchStoreReviewStatus.PUBLISHED,
    val rejectionReason: String? = null,
    val favoriteCount: Long = 0,
    val commentCount: Long = 0,
)

// ── B1 新增模型 ──────────────────────────────────────────────────────────────

/** `GET /my/me`。 */
@Immutable
data class WatchStoreAccount(
    val id: String,
    val username: String,
    val role: String,
    val disabled: Boolean,
    val createdAt: String,
    val description: String,
    val timezone: String,
    val language: String,
    val avatarUrl: String?,
)

/**
 * `PATCH /my/profile` 的请求体。三项都可选，只提交非 null 的。
 *
 * `language` 必须在上游白名单里，否则 400。
 */
data class WatchStoreProfileUpdate(
    val description: String? = null,
    val timezone: String? = null,
    val language: String? = null,
) {
    init {
        require(description == null || description.length <= MAX_DESCRIPTION) {
            "description must be at most $MAX_DESCRIPTION characters"
        }
        require(language == null || language in ALLOWED_LANGUAGES) { "language is not supported" }
        require(description != null || timezone != null || language != null) {
            "profile update must change at least one field"
        }
    }

    companion object {
        const val MAX_DESCRIPTION = 1_000
        val ALLOWED_LANGUAGES =
            setOf("en", "zh-TW", "zh-CN", "ja", "ko", "de", "fr", "es", "pt", "ru")
    }
}

/** 一条评论。上游只做**一级**回复，`parentCommentId` 非空即为回复。 */
@Immutable
data class WatchStoreComment(
    val id: String,
    val content: String,
    val parentCommentId: String?,
    val createdAt: String,
    val authorId: String,
    val authorUsername: String,
    /** Relative site image path; validate through the shared site-image loader before fetching. */
    val authorAvatarUrl: String? = null,
)

/** `GET /watchfaces/:id/rating` 的公开汇总 + 自己那一票（`GET /my/watchfaces/:id/rating`）。 */
data class WatchStoreRatingSummary(
    val average: Double,
    val count: Long,
    /** 自己的评分，没评过是 null。 */
    val mine: Int? = null,
)

enum class WatchStoreNotificationType(val apiValue: String) {
    WATCHFACE_PUBLISHED("WATCHFACE_PUBLISHED"),
    WATCHFACE_REJECTED("WATCHFACE_REJECTED"),
    WATCHFACE_DELISTED("WATCHFACE_DELISTED"),
    WATCHFACE_PENDING_REVIEW("WATCHFACE_PENDING_REVIEW"),
    WATCHFACE_SCAN_FAILED("WATCHFACE_SCAN_FAILED"),
    COMMENT_RECEIVED("COMMENT_RECEIVED"),
    COMMENT_REPLY("COMMENT_REPLY"),
    COMMENT_REMOVED("COMMENT_REMOVED"),

    /** 上游以后加新类型时照样显示，而不是整条列表解析失败。 */
    UNKNOWN("UNKNOWN");

    companion object {
        internal fun fromApi(value: String): WatchStoreNotificationType =
            entries.firstOrNull { it.apiValue.equals(value.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

@Immutable
data class WatchStoreNotification(
    val id: String,
    val type: WatchStoreNotificationType,
    val title: String,
    val message: String,
    val targetType: String?,
    val targetId: String?,
    /** null 表示未读。 */
    val readAt: String?,
    val createdAt: String,
) {
    val unread: Boolean get() = readAt == null
}

data class WatchStoreNotificationPage(
    val data: List<WatchStoreNotification>,
    val unread: Int,
)

/** 创作者。`/creators/top` 与 `/creators/:username` 共用这一个模型。 */
@Immutable
data class WatchStoreCreator(
    val id: String,
    val username: String,
    val description: String,
    val avatarUrl: String?,
    val watchFaceCount: Int,
    val totalDownloads: Long,
)

@Immutable
data class WatchStoreCollection(
    val id: String,
    val name: String,
    val itemCount: Int,
)

@Immutable
data class WatchStoreCollectionItem(
    val id: String,
    val name: String,
    val previewUrl: String,
)

/**
 * `GET /config` 的公开功能开关。
 *
 * **UI 必须尊重它**：禁用的功能隐藏入口，而不是让用户点了才吃 403。默认全开 ——
 * 拿不到配置时按「可用」处理，真被禁会在调用时收到 403，比一上来就把界面砍掉更好。
 */
data class WatchStoreFeatureFlags(
    val socialEnabled: Boolean = true,
    val commentsEnabled: Boolean = true,
    val reviewsEnabled: Boolean = true,
    val registrationEnabled: Boolean = true,
)

/** 举报原因。取值必须与上游一致，写错会 400。 */
enum class WatchStoreReportReason(val apiValue: String) {
    BROKEN("broken watch face"),
    COPYRIGHT("copyright"),
    MALWARE("malware"),
    WRONG_PREVIEW("wrong preview"),
    SPAM("spam"),
}

/** 网页版固定页面（决策 9）。带参数的详情页 / 创作者页各有专门的构造函数。 */
enum class WatchStoreWebPage(val path: String) {
    HOME("/"),
    LOGIN("/login"),
    DASHBOARD("/dashboard"),
    PROFILE("/profile"),
    CREATORS("/creators"),
}
