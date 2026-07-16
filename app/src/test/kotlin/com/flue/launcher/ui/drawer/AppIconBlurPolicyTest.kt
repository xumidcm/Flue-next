package com.flue.launcher.ui.drawer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconBlurPolicyTest {

    @Test
    fun `pre rendered icon blur is limited to enabled api 27 through 30 edge items`() {
        assertTrue(
            usePreRenderedIconBlur(
                apiLevel = 30,
                blurEnabled = true,
                edgeBlurEnabled = true,
                blurRadius = 0.51f,
            ),
        )

        assertFalse(usePreRenderedIconBlur(31, true, true, 4f))
        assertFalse(usePreRenderedIconBlur(30, false, true, 4f))
        assertFalse(usePreRenderedIconBlur(30, true, false, 4f))
        assertFalse(usePreRenderedIconBlur(30, true, true, 0.5f))
    }
}
