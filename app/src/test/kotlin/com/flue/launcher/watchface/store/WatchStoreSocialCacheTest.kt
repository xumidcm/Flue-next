package com.flue.launcher.watchface.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B2：功能开关缓存与未读数节流。两者都要在网络出问题时**退化得体面**。 */
class WatchStoreSocialCacheTest {

    @Test
    fun `flags are cached inside the ttl and refetched after it`() = runBlocking {
        var calls = 0
        val cache = WatchStoreFeatureFlagCache(
            fetch = {
                calls++
                WatchStoreFeatureFlags(commentsEnabled = false)
            },
            ttlMs = 1_000L,
        )

        assertEquals(false, cache.flags(nowMs = 0L).commentsEnabled)
        cache.flags(nowMs = 999L)
        assertEquals("TTL 内不该再问服务端", 1, calls)
        cache.flags(nowMs = 1_000L)
        assertEquals(2, calls)
    }

    @Test
    fun `a failed fetch keeps the last answer, or defaults to enabled`() = runBlocking {
        var fail = true
        val cache = WatchStoreFeatureFlagCache(
            fetch = {
                if (fail) throw WatchStoreException("offline")
                WatchStoreFeatureFlags(commentsEnabled = false, reviewsEnabled = false)
            },
            ttlMs = 1_000L,
        )

        // 一上来就拿不到配置：按「功能可用」处理，不该把社交入口全藏掉。
        val offline = cache.flags(nowMs = 0L)
        assertTrue(offline.commentsEnabled && offline.reviewsEnabled && offline.registrationEnabled)

        fail = false
        assertEquals(false, cache.flags(nowMs = 1L).commentsEnabled)

        // 拿到过之后再断网：TTL 过期也保留上一次的答案，而不是突然全开
        // —— 管理员关掉的功能不该因为一次网络抖动又冒出来。
        fail = true
        assertEquals(false, cache.flags(nowMs = 10_000L).commentsEnabled)
    }

    @Test
    fun `invalidate really forgets, so a later failure falls back to enabled`() = runBlocking {
        var fail = false
        val cache = WatchStoreFeatureFlagCache(
            fetch = {
                if (fail) throw WatchStoreException("offline")
                WatchStoreFeatureFlags(commentsEnabled = false)
            },
        )
        assertEquals(false, cache.flags(nowMs = 0L).commentsEnabled)

        // 换市场地址 / 退出登录会调 invalidate：上一台服务端的开关**不能**留给下一台，
        // 所以这之后拿不到配置就回到「全开」这个安全默认，而不是继续沿用旧答案。
        cache.invalidate()
        fail = true
        assertTrue(cache.flags(nowMs = 1L).commentsEnabled)
    }

    @Test
    fun `unread count is throttled and a failure does not stall the window`() = runBlocking {
        var calls = 0
        var nextValue = 3
        var fail = false
        val counter = WatchStoreUnreadCounter(
            fetch = {
                calls++
                if (fail) throw WatchStoreException("offline") else nextValue
            },
            throttleMs = 30_000L,
        )

        assertEquals(3, counter.count("token", nowMs = 0L))
        nextValue = 7
        assertEquals("节流窗口内给上次的数", 3, counter.count("token", nowMs = 29_999L))
        assertEquals(1, calls)
        assertEquals(7, counter.count("token", nowMs = 30_000L))

        // 请求失败不推进时间戳：否则一次网络抖动会让红点静默停摆整个窗口。
        fail = true
        assertEquals(7, counter.count("token", nowMs = 60_000L))
        fail = false
        nextValue = 9
        assertEquals(9, counter.count("token", nowMs = 60_001L))
    }

    @Test
    fun `marking all read zeroes the badge without waiting for a fetch`() = runBlocking {
        val counter = WatchStoreUnreadCounter(fetch = { 5 }, throttleMs = 30_000L)
        assertEquals(5, counter.count("token", nowMs = 0L))
        counter.markAllRead(nowMs = 1L)
        assertEquals(0, counter.count("token", nowMs = 2L))
        // 换号之后第一次问就该真发请求。
        counter.reset()
        assertEquals(5, counter.count("token", nowMs = 3L))
    }
}
