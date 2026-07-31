package com.flue.launcher.watchface.store

import androidx.compose.runtime.Immutable
import java.util.Locale

/** Public WatchMaker community-store endpoint used by Flue Next. */
const val DEFAULT_WATCH_STORE_BASE_URL: String = "https://jbwatchface.ai-life.xyz/api"

enum class WatchStoreSort(val apiValue: String) {
    LATEST("latest"),
    POPULAR("popular"),
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
) {
    init {
        require(page >= 1) { "page must be at least 1" }
        require(limit in 1..50) { "limit must be between 1 and 50" }
        require(screenType != WatchStoreScreenType.UNKNOWN) { "UNKNOWN is not a valid API filter" }
        require(categorySlug.orEmpty().length <= MAX_FILTER_LENGTH) {
            "categorySlug is too long"
        }
        require(search.orEmpty().length <= MAX_SEARCH_LENGTH) { "search is too long" }
    }

    private companion object {
        const val MAX_FILTER_LENGTH = 128
        const val MAX_SEARCH_LENGTH = 200
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
)

data class WatchStoreUploader(
    val id: String,
    val username: String,
)

/** Result of a successful login/registration. The service issues 7-day JWTs. */
data class WatchStoreAuthResult(
    val token: String,
    val userId: String,
    val username: String,
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
)
