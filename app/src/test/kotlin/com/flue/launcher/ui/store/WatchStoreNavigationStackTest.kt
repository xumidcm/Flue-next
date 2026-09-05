package com.flue.launcher.ui.store

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStoreNavigationStackTest {
    @Test
    fun `creator detail chain pops back to creator then parent detail`() {
        val navigation = WatchStoreNavigator()
        val first = WatchStoreRoute.Detail("first")
        val creator = WatchStoreRoute.Creator("alice")
        navigation.push(first)
        navigation.push(creator)
        navigation.push(WatchStoreRoute.Detail("second"))

        navigation.pop()
        assertEquals(creator, navigation.current.route)
        navigation.pop()
        assertEquals(first, navigation.current.route)
        navigation.pop()
        assertEquals(WatchStoreRoute.Browse, navigation.current.route)
    }
}
