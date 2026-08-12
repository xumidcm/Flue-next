package com.flue.launcher.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlueAccessibilityServiceTest {
    private val expected = "com.flue.launcher/com.flue.launcher.service.FlueAccessibilityService"

    @Test
    fun `manager reported service counts as enabled when secure setting is empty`() {
        assertTrue(
            containsFlueAccessibilityService(
                enabledServiceSetting = null,
                managerEnabledComponents = listOf(expected),
                expectedComponent = expected,
            )
        )
    }

    @Test
    fun `unrelated manager service does not satisfy the accessibility check`() {
        assertFalse(
            containsFlueAccessibilityService(
                enabledServiceSetting = null,
                managerEnabledComponents = listOf(
                    "com.example.other/com.example.other.OtherService"
                ),
                expectedComponent = expected,
            )
        )
    }

    @Test
    fun `short class notation and whitespace are normalized`() {
        assertTrue(
            containsFlueAccessibilityService(
                enabledServiceSetting = " other/pkg.Other: com.flue.launcher/.service.FlueAccessibilityService ",
                managerEnabledComponents = emptyList(),
                expectedComponent = expected,
            )
        )
    }
}
