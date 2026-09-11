package com.flue.launcher.aod

import android.content.Context
import android.content.SharedPreferences

object AodPreferences {
    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("flue_aod", Context.MODE_PRIVATE)

    fun read(context: Context): AodConfig = read(preferences(context))
    fun read(prefs: SharedPreferences): AodConfig = AodConfig(
        enabled = prefs.getBoolean("enabled", false),
        timeoutSeconds = prefs.getInt("timeout_seconds", 30).coerceIn(5, 300),
        brightnessPercent = prefs.getInt("brightness_percent", 10).coerceIn(1, 100),
        lockGestures = prefs.getBoolean("lock_gestures", true),
        lockDelaySeconds = prefs.getInt("lock_delay_seconds", 5).coerceIn(0, 30),
        reawaken = prefs.getBoolean("reawaken", true),
        powerButtonExit = prefs.getBoolean("power_button_exit", true),
        shallowSleep = prefs.getBoolean("shallow_sleep", false),
        alwaysWhenCharging = prefs.getBoolean("always_when_charging", false),
    )
}
