package com.flue.launcher.watchface.standard

internal object WatchmakerFontAtlasCachePolicy {
    const val MAX_ENTRIES = 16
    const val MAX_BYTES = 24L * 1024L * 1024L

    fun shouldEvict(entryCount: Int, allocationBytes: Long): Boolean =
        entryCount > MAX_ENTRIES || (entryCount > 1 && allocationBytes > MAX_BYTES)
}
