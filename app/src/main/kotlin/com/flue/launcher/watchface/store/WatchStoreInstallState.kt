package com.flue.launcher.watchface.store

import com.flue.launcher.watchface.store.catalog.WatchFaceRecord

sealed interface WatchStoreInstallState {
    data object NotInstalled : WatchStoreInstallState

    data class Downloading(
        val bytes: Long,
        val totalBytes: Long?,
        val attempt: Int,
    ) : WatchStoreInstallState

    data class Validating(val attempt: Int) : WatchStoreInstallState

    data class Installing(val attempt: Int) : WatchStoreInstallState

    data class Installed(val record: WatchFaceRecord) : WatchStoreInstallState

    data class Failed(
        val message: String,
        val failedAtEpochMs: Long,
        val downloadedBytes: Long,
        val attempt: Int,
    ) : WatchStoreInstallState {
        fun retry(): Downloading = Downloading(bytes = 0L, totalBytes = null, attempt = attempt + 1)
    }
}

/** Limits UI progress invalidations to 10Hz while always forwarding completion. */
class WatchStoreProgressThrottle(
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private var lastEmissionNanos: Long? = null

    @Synchronized
    fun shouldEmit(bytes: Long, totalBytes: Long?): Boolean {
        if (totalBytes != null && totalBytes > 0L && bytes >= totalBytes) {
            lastEmissionNanos = clockNanos()
            return true
        }
        val now = clockNanos()
        val previous = lastEmissionNanos
        if (previous == null || now - previous >= MIN_PROGRESS_INTERVAL_NANOS) {
            lastEmissionNanos = now
            return true
        }
        return false
    }

    private companion object {
        const val MIN_PROGRESS_INTERVAL_NANOS = 100_000_000L
    }
}
