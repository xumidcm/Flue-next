package com.flue.launcher.ui.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal sealed interface WatchStoreRoute {
    data object Browse : WatchStoreRoute
    data object TopCreators : WatchStoreRoute
    data object Favorites : WatchStoreRoute
    data object Collections : WatchStoreRoute
    data object Notifications : WatchStoreRoute
    data object Profile : WatchStoreRoute
    data object Upload : WatchStoreRoute
    data class MyUploads(val startInLoginForm: Boolean = false) : WatchStoreRoute
    data class Creator(val username: String) : WatchStoreRoute
    data class Detail(val remoteId: String) : WatchStoreRoute
}

internal data class WatchStorePageEntry(val id: Long, val route: WatchStoreRoute)

/** Stable entry identities retain each parent's UI state, including repeated visits to one work. */
internal class WatchStoreNavigator {
    private var nextId = 1L
    var entries by mutableStateOf(listOf(WatchStorePageEntry(0L, WatchStoreRoute.Browse)))
        private set
    val current: WatchStorePageEntry get() = entries.last()

    fun push(route: WatchStoreRoute) {
        if (route == current.route || route == WatchStoreRoute.Browse) return
        entries = entries + WatchStorePageEntry(nextId++, route)
    }

    fun pop(): Boolean {
        if (entries.size <= 1) return false
        entries = entries.dropLast(1)
        return true
    }

    fun isCurrent(entry: WatchStorePageEntry): Boolean = entry.id == current.id

    /** Drop an expired authenticated page and its children without affecting unrelated entries. */
    fun dismissFrom(entry: WatchStorePageEntry) {
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index > 0) entries = entries.take(index)
    }

    fun removeAuthenticatedPages() {
        entries = entries.filterNot {
            when (it.route) {
                WatchStoreRoute.Favorites, WatchStoreRoute.Collections,
                WatchStoreRoute.Notifications, WatchStoreRoute.Profile -> true
                else -> false
            }
        }
    }
}
