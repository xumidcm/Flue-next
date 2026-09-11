package com.flue.launcher.watchface.standard

internal enum class WatchTextAnimationTrigger { LOAD, BRIGHT }

/** Two event epochs encode each layer's most recent permitted start without per-frame mutation. */
internal data class WatchTextAnimationEpochs(
    val loadAtMs: Long? = null,
    val brightAtMs: Long? = null,
) {
    fun triggered(trigger: WatchTextAnimationTrigger, nowMs: Long): WatchTextAnimationEpochs = when (trigger) {
        WatchTextAnimationTrigger.LOAD -> WatchTextAnimationEpochs(loadAtMs = nowMs)
        WatchTextAnimationTrigger.BRIGHT -> copy(brightAtMs = nowMs)
    }

    fun epochFor(attributes: Map<String, String>): Long? {
        return epochFor(watchTextAnimationRestarts(attributes, WatchTextAnimationTrigger.LOAD),
            watchTextAnimationRestarts(attributes, WatchTextAnimationTrigger.BRIGHT))
    }

    fun epochFor(restartOnLoad: Boolean, restartOnBright: Boolean): Long? {
        val load = loadAtMs?.takeIf { restartOnLoad }
        val bright = brightAtMs?.takeIf { restartOnBright }
        return when {
            load == null -> bright
            bright == null -> load
            else -> maxOf(load, bright)
        }
    }
}

internal fun watchTextAnimationRestarts(
    attributes: Map<String, String>,
    trigger: WatchTextAnimationTrigger,
): Boolean {
    val key = if (trigger == WatchTextAnimationTrigger.LOAD) "restart_on_load" else "restart_on_bright"
    return when (attributes[key].orEmpty().trim().lowercase(java.util.Locale.ROOT)) {
        "y", "yes", "true", "1" -> true
        else -> false
    }
}
