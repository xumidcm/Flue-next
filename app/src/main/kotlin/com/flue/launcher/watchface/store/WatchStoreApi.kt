package com.flue.launcher.watchface.store

import java.io.File

/**
 * Blocking transport contract for the public community store.
 *
 * Implementations must be called off the main thread.  The repository below
 * supplies the coroutine boundary used by the launcher.
 */
interface WatchStoreApi {
    fun getCategories(): List<WatchStoreCategory>

    fun listWatchFaces(query: WatchStoreQuery = WatchStoreQuery()): WatchStorePage

    fun getWatchFace(id: String): WatchStoreWatchFaceDetail

    /** Streams the final image into [destination], enforcing the preview limit. */
    fun downloadPreview(id: String, destination: File): File

    /** Streams the final `.watch` archive into [destination], enforcing the archive limit. */
    fun downloadWatchFace(id: String, destination: File): File
}

open class WatchStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class WatchStoreProtocolException(
    message: String,
    cause: Throwable? = null,
) : WatchStoreException(message, cause)

class WatchStoreHttpException(
    val statusCode: Int,
    message: String,
) : WatchStoreException(message)

class WatchStorePayloadTooLargeException(
    val limitBytes: Long,
) : WatchStoreException("Watch store response exceeds the ${limitBytes} byte limit")

class WatchStoreRedirectException(message: String) : WatchStoreException(message)
