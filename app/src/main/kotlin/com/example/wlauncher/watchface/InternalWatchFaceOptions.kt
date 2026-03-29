package com.flue.launcher.watchface

enum class WatchClockPosition {
    CENTER,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

data class BuiltInWatchFaceOptions(
    val clockPosition: WatchClockPosition = WatchClockPosition.CENTER,
    val clockSizeSp: Int = 64,
    val cropToFill: Boolean = true
)
