package com.flue.launcher.aod

import org.junit.Assert.*
import org.junit.Test

class AodPolicyTest {
    @Test fun defaultsAreOptInAndBrightnessUsesPercent() {
        val config = AodConfig()
        assertFalse(config.active(false))
        assertFalse(config.active(true))
        assertEquals(30, config.timeoutSeconds)
        assertEquals(0.10f, config.brightnessPercent / 100f, 0.0001f)
        assertEquals(5, config.lockDelaySeconds)
    }

    @Test fun chargingOverrideEndsWhenPowerIsRemoved() {
        val config = AodConfig(alwaysWhenCharging = true)
        assertTrue(config.active(true))
        assertFalse(config.active(false))
        assertTrue(config.copy(enabled = true).active(false))
    }

    @Test fun screenOffTransitionsRespectSettingsAndForegroundCapability() {
        val config = AodConfig(enabled = true)
        assertEquals(AodScreenOffAction.ENTER_AMBIENT, aodScreenOffAction(config, false, false, true))
        assertEquals(AodScreenOffAction.EXIT_AMBIENT, aodScreenOffAction(config, false, true, true))
        assertEquals(AodScreenOffAction.KEEP_AMBIENT, aodScreenOffAction(config.copy(powerButtonExit = false), false, true, true))
        assertEquals(AodScreenOffAction.NONE, aodScreenOffAction(config, false, false, false))
        assertEquals(AodScreenOffAction.NONE, aodScreenOffAction(config.copy(reawaken = false), false, true, true))
        assertEquals(AodScreenOffAction.NONE, aodScreenOffAction(AodConfig(), false, true, true))
    }

    @Test fun delayedWorkCannotReturnAfterDisableAndReenable() {
        val generation = AodGeneration()
        val pending = generation.value
        assertTrue(generation.accepts(pending, true))
        assertFalse(generation.accepts(pending, false))
        generation.invalidate()
        assertFalse(generation.accepts(pending, true))
        assertTrue(generation.accepts(generation.value, true))
    }
}
