package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreAccount
import com.flue.launcher.watchface.store.WatchStoreAuthSession

/** These are the choices exposed by the store website's profile form. */
internal val WATCH_STORE_PROFILE_TIMEZONES = listOf(
    "UTC",
    "Asia/Taipei",
    "Asia/Tokyo",
    "Asia/Hong_Kong",
    "Asia/Shanghai",
    "Asia/Kolkata",
    "Europe/London",
    "Europe/Paris",
    "America/Los_Angeles",
    "America/New_York",
)

internal val WATCH_STORE_PROFILE_LANGUAGES = listOf("en", "zh-CN", "zh-TW")

/** Lets the profile screen render every cached field while `/my/me` refreshes in the background. */
internal fun WatchStoreAuthSession.toProfileAccountSnapshot(): WatchStoreAccount = WatchStoreAccount(
    id = "",
    username = username,
    role = "user",
    disabled = false,
    createdAt = "",
    description = description,
    timezone = timezone,
    language = language,
    avatarUrl = avatarUrl,
)
