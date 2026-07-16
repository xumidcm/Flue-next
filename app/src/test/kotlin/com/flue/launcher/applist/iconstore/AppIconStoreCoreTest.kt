package com.flue.launcher.applist.iconstore

import com.flue.launcher.applist.model.IconKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconStoreCoreTest {

    @Test
    fun `requested pixels round up into a deterministic size bucket`() {
        assertEquals(64, IconSizeBucket.forPixels(64).pixels)
        assertEquals(72, IconSizeBucket.forPixels(65).pixels)
        assertEquals(80, IconSizeBucket.forPixels(73).pixels)
        assertEquals(96, IconSizeBucket.forPixels(81, quantumPixels = 16).pixels)
    }

    @Test
    fun `size bucket rejects invalid dimensions and quantum`() {
        assertThrows(IllegalArgumentException::class.java) {
            IconSizeBucket.forPixels(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IconSizeBucket.forPixels(48, quantumPixels = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IconSizeBucket.forPixels(Int.MAX_VALUE, quantumPixels = 8)
        }
    }

    @Test
    fun `source revision participates in cache identity`() {
        val first = cacheKey(revision = "app=100|pack=2|pipeline=1")
        val updatedApp = cacheKey(revision = "app=101|pack=2|pipeline=1")
        val updatedPipeline = cacheKey(revision = "app=100|pack=2|pipeline=2")

        assertNotEquals(first, updatedApp)
        assertNotEquals(first, updatedPipeline)
    }

    @Test
    fun `request result exposes snapshot generation validation`() {
        val request = AppIconRequest(
            cacheKey = cacheKey(),
            snapshotGeneration = 42L,
        )
        val result = AppIconResult(
            request = request,
            value = "bitmap",
            source = AppIconLoadSource.Decoded,
        )

        assertTrue(result.isCurrentFor(snapshotGeneration = 42L))
        assertFalse(result.isCurrentFor(snapshotGeneration = 43L))
    }

    @Test
    fun `sharp memory cache evicts the least recently used entry by weight`() {
        val cache = SharpIconMemoryCache<String>(
            maxWeightBytes = 3L,
            weightOf = { 1L },
        )
        val first = cacheKey("first")
        val second = cacheKey("second")
        val third = cacheKey("third")
        val fourth = cacheKey("fourth")

        cache.put(first, "1")
        cache.put(second, "2")
        cache.put(third, "3")
        assertEquals("1", cache[first])

        cache.put(fourth, "4")

        assertEquals("1", cache[first])
        assertNull(cache[second])
        assertEquals("3", cache[third])
        assertEquals("4", cache[fourth])
        assertEquals(3L, cache.currentWeightBytes)
    }

    @Test
    fun `replacing an entry updates its weight without double counting`() {
        val cache = SharpIconMemoryCache<String>(
            maxWeightBytes = 10L,
            weightOf = { it.length.toLong() },
        )
        val key = cacheKey()

        cache.put(key, "12")
        cache.put(key, "12345")

        assertEquals("12345", cache[key])
        assertEquals(5L, cache.currentWeightBytes)
        assertEquals(1, cache.size)
    }

    @Test
    fun `icon invalidation removes every size and revision for that icon only`() {
        val cache = SharpIconMemoryCache<String>(
            maxWeightBytes = 10L,
            weightOf = { 1L },
        )
        val calendar = IconKey("calendar")
        val music = IconKey("music")
        val calendarSmall = cacheKey(calendar.value, pixels = 48, revision = "1")
        val calendarLarge = cacheKey(calendar.value, pixels = 96, revision = "2")
        val musicKey = cacheKey(music.value, pixels = 48, revision = "1")
        cache.put(calendarSmall, "calendar-small")
        cache.put(calendarLarge, "calendar-large")
        cache.put(musicKey, "music")

        val removed = cache.invalidate(calendar)

        assertEquals(2, removed)
        assertNull(cache[calendarSmall])
        assertNull(cache[calendarLarge])
        assertEquals("music", cache[musicKey])
    }

    @Test
    fun `entry larger than cache capacity is returned to caller but not retained`() {
        val cache = SharpIconMemoryCache<String>(
            maxWeightBytes = 3L,
            weightOf = { it.length.toLong() },
        )
        val key = cacheKey()

        assertFalse(cache.put(key, "oversized"))

        assertNull(cache[key])
        assertEquals(0L, cache.currentWeightBytes)
        assertEquals(0, cache.size)
    }

    @Test
    fun `invalid revision generation and cache weights are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IconSourceRevision(" ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppIconRequest(cacheKey(), snapshotGeneration = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SharpIconMemoryCache<String>(
                maxWeightBytes = 0L,
                weightOf = { it.length.toLong() },
            )
        }
    }

    private fun cacheKey(
        icon: String = "calendar",
        pixels: Int = 64,
        revision: String = "app=100|pack=2|pipeline=1",
    ) = AppIconCacheKey(
        iconKey = IconKey(icon),
        sizeBucket = IconSizeBucket.forPixels(pixels),
        sourceRevision = IconSourceRevision(revision),
    )
}
