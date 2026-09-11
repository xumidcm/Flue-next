package com.flue.launcher

import org.junit.Assert.*
import org.junit.Test

class FeedbackRuntimePolicyTest {
    @Test fun `unknown interruption filter is not do not disturb`() {
        val type = Class.forName("com.flue.launcher.status.DoNotDisturbStateKt")
        val method = type.getMethod("isDoNotDisturbFilter", Int::class.javaPrimitiveType)
        assertEquals(false, method.invoke(null, 0))
        assertEquals(false, method.invoke(null, 1))
        for (filter in 2..4) assertEquals(true, method.invoke(null, filter))
    }

    @Test fun `recording deadline continues while application is backgrounded`() {
        val type = Class.forName("com.flue.launcher.diagnostics.RecordingWindow")
        val window = type.getConstructor(Long::class.javaPrimitiveType).newInstance(1_000L)
        val expired = type.getMethod("isExpired", Long::class.javaPrimitiveType)
        assertEquals(false, expired.invoke(window, 300_999L))
        assertEquals(true, expired.invoke(window, 301_000L))
        assertEquals(true, expired.invoke(window, 900_000L))
    }
}
