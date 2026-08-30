package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchmakerFrameTagResolutionCacheTest {

    @Test
    fun `same tag is resolved once per frame and refreshed for the next frame`() {
        val cache = WatchmakerFrameTagResolutionCache()
        var resolveCount = 0

        cache.beginFrame(10L)
        assertEquals(
            WatchTagResolution.Resolved("12"),
            cache.resolve(" dh23 ") {
                resolveCount += 1
                WatchTagResolution.Resolved("12")
            },
        )
        assertEquals(
            WatchTagResolution.Resolved("12"),
            cache.resolve("DH23") {
                resolveCount += 1
                WatchTagResolution.Resolved("12")
            },
        )
        assertEquals(1, resolveCount)

        cache.beginFrame(11L)
        cache.resolve("dh23") {
            resolveCount += 1
            WatchTagResolution.Resolved("13")
        }
        assertEquals(2, resolveCount)
    }

    @Test
    fun `explicit invalidation removes cached values`() {
        val cache = WatchmakerFrameTagResolutionCache()
        var resolveCount = 0

        cache.beginFrame(10L)
        repeat(2) {
            cache.resolve("dm") {
                resolveCount += 1
                WatchTagResolution.Resolved("1")
            }
        }
        cache.clear()
        cache.resolve("dm") {
            resolveCount += 1
            WatchTagResolution.Resolved("2")
        }

        assertEquals(2, resolveCount)
    }
}
