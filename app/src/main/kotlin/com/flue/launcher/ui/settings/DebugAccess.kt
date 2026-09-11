package com.flue.launcher.ui.settings

internal const val DEBUG_TAP_TIMEOUT_MS: Long = 1_500L
private const val DEBUG_TAP_TARGET: Int = 5

internal data class DebugTapState(
    val tapCount: Int = 0,
    val lastTapAtMillis: Long = Long.MIN_VALUE,
    val openDebug: Boolean = false,
    val showToast: Boolean = false,
) {
    fun tap(nowMillis: Long): DebugTapState {
        val continues = lastTapAtMillis != Long.MIN_VALUE &&
            nowMillis >= lastTapAtMillis &&
            nowMillis - lastTapAtMillis <= DEBUG_TAP_TIMEOUT_MS
        val nextCount = if (continues) tapCount + 1 else 1
        return if (nextCount >= DEBUG_TAP_TARGET) {
            DebugTapState(openDebug = true)
        } else {
            copy(
                tapCount = nextCount,
                lastTapAtMillis = nowMillis,
                openDebug = false,
                showToast = false,
            )
        }
    }
}
