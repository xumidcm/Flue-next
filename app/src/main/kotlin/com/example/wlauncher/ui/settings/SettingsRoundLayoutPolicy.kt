package com.flue.launcher

internal data class SettingsInitialPosition(
    val index: Int,
    val offset: Int,
    val centerOnScreen: Boolean,
)

internal fun settingsInitialPosition(
    isRound: Boolean,
    savedIndex: Int,
    savedOffset: Int,
    firstActionIndex: Int,
): SettingsInitialPosition {
    val hasSavedPosition = savedIndex != 0 || savedOffset != 0
    return if (isRound && !hasSavedPosition) {
        SettingsInitialPosition(
            index = firstActionIndex.coerceAtLeast(1),
            offset = 0,
            centerOnScreen = true,
        )
    } else {
        SettingsInitialPosition(
            index = savedIndex,
            offset = savedOffset,
            centerOnScreen = false,
        )
    }
}
