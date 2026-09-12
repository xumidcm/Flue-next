package com.flue.launcher.watchface.selection

enum class WatchFaceLongPressMode {
    CARDS,
    LIST;

    fun encode(): String = name.lowercase()

    companion object {
        fun decode(raw: String?): WatchFaceLongPressMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CARDS
    }
}

enum class WatchFaceChooserEntryPoint {
    LONG_PRESS,
    SETTINGS,
}

enum class WatchFaceChooserDestination {
    CARDS,
    LIST,
}

fun resolveWatchFaceChooserDestination(
    entryPoint: WatchFaceChooserEntryPoint,
    mode: WatchFaceLongPressMode,
): WatchFaceChooserDestination = when (entryPoint) {
    WatchFaceChooserEntryPoint.SETTINGS -> WatchFaceChooserDestination.LIST
    WatchFaceChooserEntryPoint.LONG_PRESS -> when (mode) {
        WatchFaceLongPressMode.CARDS -> WatchFaceChooserDestination.CARDS
        WatchFaceLongPressMode.LIST -> WatchFaceChooserDestination.LIST
    }
}
