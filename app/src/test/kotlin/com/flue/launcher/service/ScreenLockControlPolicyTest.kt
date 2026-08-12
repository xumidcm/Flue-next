package com.flue.launcher.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLockControlPolicyTest {
    @Test
    fun `android 8_1 uses device admin while newer systems use accessibility`() {
        assertEquals(ScreenLockControlMode.ACCESSIBILITY, screenLockControlMode(sdkInt = 26))
        assertEquals(ScreenLockControlMode.DEVICE_ADMIN, screenLockControlMode(sdkInt = 27))
        assertEquals(ScreenLockControlMode.ACCESSIBILITY, screenLockControlMode(sdkInt = 28))
        assertEquals(ScreenLockControlMode.ACCESSIBILITY, screenLockControlMode(sdkInt = 36))
    }
}
