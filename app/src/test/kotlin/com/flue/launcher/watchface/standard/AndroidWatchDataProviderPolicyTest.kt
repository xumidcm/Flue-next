package com.flue.launcher.watchface.standard

import android.os.BatteryManager
import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWatchDataProviderPolicyTest {
    @Test
    fun accelerometerIsRegisteredAlongsideGyroscopeForTiltFaces() {
        assertEquals(
            listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER),
            watchMotionSensorTypes(hasGyroscope = true),
        )
        assertEquals(
            listOf(Sensor.TYPE_ACCELEROMETER),
            watchMotionSensorTypes(hasGyroscope = false),
        )
    }

    @Test
    fun revokedHeartRatePermissionMasksPreviouslyMeasuredValue() {
        assertEquals(72, watchHeartRateValue(hasPermission = true, value = 72))
        assertNull(watchHeartRateValue(hasPermission = false, value = 72))
        assertNull(watchHeartRateValue(hasPermission = false, value = null))
    }

    @Test
    fun pluggedPowerSourcesWinWhenLegacyDevicesReportUnknownBatteryStatus() {
        assertTrue(
            watchChargingState(
                status = BatteryManager.BATTERY_STATUS_UNKNOWN,
                plugged = BatteryManager.BATTERY_PLUGGED_AC,
                previous = null,
            ) == true,
        )
        assertTrue(
            watchChargingState(
                status = BatteryManager.BATTERY_STATUS_UNKNOWN,
                plugged = BatteryManager.BATTERY_PLUGGED_USB,
                previous = false,
            ) == true,
        )
        assertTrue(
            watchChargingState(
                status = BatteryManager.BATTERY_STATUS_FULL,
                plugged = 0,
                previous = false,
            ) == true,
        )
    }

    @Test
    fun explicitDischargingAndUnknownUnpluggedStatesKeepTheirExpectedMeaning() {
        assertFalse(
            watchChargingState(
                status = BatteryManager.BATTERY_STATUS_DISCHARGING,
                plugged = 0,
                previous = true,
            ) == true,
        )
        assertEquals(
            true,
            watchChargingState(
                status = BatteryManager.BATTERY_STATUS_UNKNOWN,
                plugged = 0,
                previous = true,
            ),
        )
        assertNull(
            watchChargingState(
                status = BatteryManager.BATTERY_STATUS_UNKNOWN,
                plugged = 0,
                previous = null,
            ),
        )
    }
}
