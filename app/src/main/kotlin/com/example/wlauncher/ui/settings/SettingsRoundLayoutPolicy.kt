package com.flue.launcher

internal data class SettingsInitialPosition(
    val index: Int,
    val offset: Int,
    val centerOnScreen: Boolean,
)

/** 将首项的中心定位到圆屏圆心；半高按具体卡片类型传入，避免拿大行高估算。 */
internal fun settingsCenteredItemScrollOffset(
    screenCenterY: Float,
    itemHalfHeight: Float,
): Int = -(screenCenterY - itemHalfHeight.coerceAtLeast(0f)).toInt()

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
