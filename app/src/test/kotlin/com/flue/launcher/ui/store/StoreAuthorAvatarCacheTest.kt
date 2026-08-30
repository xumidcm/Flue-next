package com.flue.launcher.ui.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StoreAuthorAvatarCacheTest {

    @Test
    fun sameAuthorIsFetchedOnceUntilTtlExpires() {
        val cache = StoreAuthorAvatarCache(maxAgeMs = 100L)
        var calls = 0

        val first = cache.resolve("Alice", nowEpochMs = 1_000L) {
            calls++
            "/avatars/alice.webp"
        }
        val withinTtl = cache.resolve("alice", nowEpochMs = 1_099L) {
            calls++
            "/avatars/new-alice.webp"
        }
        val afterTtl = cache.resolve("ALICE", nowEpochMs = 1_100L) {
            calls++
            "/avatars/new-alice.webp"
        }

        assertEquals(2, calls)
        assertNotNull(first)
        assertNotNull(withinTtl)
        assertNotNull(afterTtl)
        assertEquals("/avatars/alice.webp", first?.url)
        assertEquals(first?.version, withinTtl?.version)
        assertEquals(2L, afterTtl?.version)
        assertEquals("/avatars/new-alice.webp", afterTtl?.url)
        assertEquals("/avatars/new-alice.webp", cache.cached("alice", nowEpochMs = 1_100L)?.url)
    }

    @Test
    fun invalidatingAnAuthorForcesTheSameUrlToBeFetchedAgain() {
        val cache = StoreAuthorAvatarCache(maxAgeMs = 10_000L)
        var calls = 0

        cache.resolve("alice", nowEpochMs = 1_000L) {
            calls++
            "/avatars/alice.webp"
        }
        cache.invalidate("ALICE")
        val refreshed = cache.resolve("alice", nowEpochMs = 1_001L) {
            calls++
            "/avatars/alice.webp"
        }

        assertEquals(2, calls)
        assertNotNull(refreshed)
        assertEquals(2L, refreshed?.version)
        assertNull(cache.cached("missing"))
    }
}
