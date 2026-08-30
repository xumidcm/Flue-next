package com.flue.launcher.watchface.standard

import java.util.Locale

/**
 * Reuses tag resolutions within one render snapshot.
 *
 * A complex face can ask for the same time or sensor tag from hundreds of
 * attributes in one frame. The cache deliberately has no cross-frame policy:
 * callers advance the frame key whenever the input snapshot changes.
 */
internal class WatchmakerFrameTagResolutionCache {
    private var frameKey = Long.MIN_VALUE
    private val values = HashMap<String, WatchTagResolution>(32)

    fun beginFrame(nextFrameKey: Long) {
        if (frameKey == nextFrameKey) return
        frameKey = nextFrameKey
        values.clear()
    }

    fun resolve(
        rawTag: String,
        resolver: (String) -> WatchTagResolution,
    ): WatchTagResolution {
        val tag = rawTag.trim().lowercase(Locale.ROOT)
        return values.getOrPut(tag) { resolver(tag) }
    }

    fun clear() {
        frameKey = Long.MIN_VALUE
        values.clear()
    }
}
