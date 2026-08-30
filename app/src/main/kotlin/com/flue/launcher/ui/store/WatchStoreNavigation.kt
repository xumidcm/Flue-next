package com.flue.launcher.ui.store

internal sealed interface WatchStoreDetailOrigin {
    data object Browse : WatchStoreDetailOrigin
    data class Creator(val username: String) : WatchStoreDetailOrigin
}

internal sealed interface WatchStoreDetailBackTarget {
    data object Browse : WatchStoreDetailBackTarget
    data class Creator(val username: String) : WatchStoreDetailBackTarget
}

internal fun watchStoreDetailBackTarget(origin: WatchStoreDetailOrigin): WatchStoreDetailBackTarget =
    when (origin) {
        WatchStoreDetailOrigin.Browse -> WatchStoreDetailBackTarget.Browse
        is WatchStoreDetailOrigin.Creator -> WatchStoreDetailBackTarget.Creator(origin.username)
    }
