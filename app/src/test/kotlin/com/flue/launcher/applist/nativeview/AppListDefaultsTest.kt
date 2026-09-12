package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertFalse
import org.junit.Test

class AppListDefaultsTest {
    @Test
    fun `staggered entry animation defaults to off`() {
        assertFalse(DEFAULT_APP_LIST_STAGGER_ENABLED)
        assertFalse(NativeAppListConfig().entryMotionEnabled)
    }
}
