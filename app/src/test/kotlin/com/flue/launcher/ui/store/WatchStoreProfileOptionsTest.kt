package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreAuthSession
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStoreProfileOptionsTest {
    @Test
    fun `profile choices match the website`() {
        assertEquals(
            listOf(
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
            ),
            WATCH_STORE_PROFILE_TIMEZONES,
        )
        assertEquals(
            listOf("en", "zh-CN", "zh-TW"),
            WATCH_STORE_PROFILE_LANGUAGES,
        )
    }

    @Test
    fun `cached profile is immediately usable as display data`() {
        val cached = WatchStoreAuthSession(
            token = "token",
            username = "ailife",
            expiresAtEpochMs = Long.MAX_VALUE,
            description = "Designer",
            timezone = "Asia/Shanghai",
            language = "zh-CN",
            avatarUrl = "/api/creators/ailife/avatar",
        )

        val account = cached.toProfileAccountSnapshot()

        assertEquals("ailife", account.username)
        assertEquals("Designer", account.description)
        assertEquals("Asia/Shanghai", account.timezone)
        assertEquals("zh-CN", account.language)
        assertEquals("/api/creators/ailife/avatar", account.avatarUrl)
    }
}
