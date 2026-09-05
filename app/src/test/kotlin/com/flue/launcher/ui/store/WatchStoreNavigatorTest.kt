package com.flue.launcher.ui.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStoreNavigatorTest {
    @Test
    fun `signing out removes protected ancestors without losing the public detail or login page`() {
        val navigation = WatchStoreNavigator()
        navigation.push(WatchStoreRoute.Favorites)
        navigation.push(WatchStoreRoute.Detail("work"))
        val detail = navigation.current
        navigation.removeAuthenticatedPages()
        assertEquals(listOf(WatchStoreRoute.Browse, detail.route), navigation.entries.map { it.route })
        assertEquals(detail, navigation.current)
        navigation.pop()
        assertEquals(WatchStoreRoute.Browse, navigation.current.route)

        for (route in listOf(WatchStoreRoute.Collections, WatchStoreRoute.Notifications, WatchStoreRoute.Profile)) {
            navigation.push(route)
            navigation.removeAuthenticatedPages()
            assertEquals(WatchStoreRoute.Browse, navigation.current.route)
        }
        navigation.push(WatchStoreRoute.MyUploads(true))
        val login = navigation.current
        navigation.removeAuthenticatedPages()
        assertEquals(login, navigation.current)
    }

    @Test
    fun `nested creators and details return through every original page`() {
        val navigation = WatchStoreNavigator()
        val path = listOf(
            WatchStoreRoute.Detail("a"), WatchStoreRoute.Creator("alice"),
            WatchStoreRoute.Detail("b"), WatchStoreRoute.Creator("bob"),
            WatchStoreRoute.Detail("c"),
        )
        path.forEach(navigation::push)

        for (expected in path.asReversed()) {
            assertEquals(expected, navigation.current.route)
            assertTrue(navigation.pop())
        }
        assertEquals(WatchStoreRoute.Browse, navigation.current.route)
        assertFalse(navigation.pop())
    }

    @Test
    fun `login is the top page above a creator work and returns to that same detail`() {
        val navigation = WatchStoreNavigator()
        navigation.push(WatchStoreRoute.TopCreators)
        navigation.push(WatchStoreRoute.Creator("alice"))
        navigation.push(WatchStoreRoute.Detail("work"))
        val detail = navigation.current
        navigation.push(WatchStoreRoute.MyUploads(startInLoginForm = true))

        assertTrue(navigation.isCurrent(navigation.current))
        assertFalse(navigation.isCurrent(detail))
        assertEquals(detail, navigation.entries[navigation.entries.lastIndex - 1])
        navigation.pop()
        assertEquals(detail, navigation.current)
    }

    @Test
    fun `favorites and collections retain their page identity while viewing a work`() {
        for (origin in listOf(WatchStoreRoute.Favorites, WatchStoreRoute.Collections)) {
            val navigation = WatchStoreNavigator()
            navigation.push(origin)
            val library = navigation.current
            navigation.push(WatchStoreRoute.Detail("work"))
            navigation.push(WatchStoreRoute.Creator("alice"))
            navigation.pop()
            navigation.pop()
            assertEquals(library, navigation.current)
        }
    }

    @Test
    fun `covered detail is inactive until returning from creator or login`() {
        val navigation = WatchStoreNavigator()
        navigation.push(WatchStoreRoute.Detail("comments"))
        val detailWithComments = navigation.current
        for (child in listOf(WatchStoreRoute.Creator("commenter"), WatchStoreRoute.MyUploads(true))) {
            navigation.push(child)
            assertFalse(navigation.isCurrent(detailWithComments))
            assertTrue(navigation.entries.contains(detailWithComments))
            navigation.pop()
            assertTrue(navigation.isCurrent(detailWithComments))
        }
    }

    @Test
    fun `revisiting a detail creates a separate page without replacing its ancestor`() {
        val navigation = WatchStoreNavigator()
        navigation.push(WatchStoreRoute.Detail("same"))
        val first = navigation.current
        navigation.push(WatchStoreRoute.Creator("alice"))
        navigation.push(WatchStoreRoute.Detail("same"))
        assertNotEquals(first.id, navigation.current.id)
        navigation.pop()
        navigation.pop()
        assertEquals(first, navigation.current)
    }

    @Test
    fun `double tapping the same destination does not push a duplicate page`() {
        val navigation = WatchStoreNavigator()
        navigation.push(WatchStoreRoute.Detail("work"))
        val detail = navigation.current
        navigation.push(WatchStoreRoute.Detail("work"))
        assertEquals(detail, navigation.current)
        assertEquals(2, navigation.entries.size)
    }

    @Test
    fun `upload returns to my uploads before leaving the market`() {
        val navigation = WatchStoreNavigator()
        navigation.push(WatchStoreRoute.MyUploads())
        val uploads = navigation.current
        navigation.push(WatchStoreRoute.Upload)
        assertFalse(navigation.isCurrent(uploads))
        navigation.pop()
        assertEquals(uploads, navigation.current)
    }

    @Test
    fun `expired library closes its descendants while stale callbacks leave a new page alone`() {
        val navigation = WatchStoreNavigator()
        navigation.push(WatchStoreRoute.Favorites)
        val favorites = navigation.current
        navigation.push(WatchStoreRoute.Detail("work"))
        navigation.dismissFrom(favorites)
        assertEquals(WatchStoreRoute.Browse, navigation.current.route)

        navigation.push(WatchStoreRoute.Detail("another"))
        val another = navigation.current
        navigation.dismissFrom(favorites)
        assertEquals(another, navigation.current)
        navigation.dismissFrom(navigation.entries.first())
        assertEquals(another, navigation.current)
    }
}
