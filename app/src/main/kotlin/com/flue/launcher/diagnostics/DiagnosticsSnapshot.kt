package com.flue.launcher.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.flue.launcher.BuildConfig
import com.flue.launcher.aod.AodPreferences
import com.flue.launcher.service.FlueAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Only these fields are exported. No preference dump, serial, Android ID, URI or account data. */
object DiagnosticsSnapshot {
    @Suppress("DEPRECATION")
    suspend fun collect(context: Context, configuration: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val display = app.getSystemService(WindowManager::class.java)?.defaultDisplay
        val metrics = DisplayMetrics().also { result ->
            if (display != null) display.getRealMetrics(result) else result.setTo(app.resources.displayMetrics)
        }
        val activityManager = app.getSystemService(ActivityManager::class.java)
        val system = JSONObject()
            .put("android_release", Build.VERSION.RELEASE)
            .put("api", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("low_ram", activityManager?.isLowRamDevice ?: false)
            .put("memory_class_mb", activityManager?.memoryClass ?: 0)
            .put("screen_width_px", metrics.widthPixels)
            .put("screen_height_px", metrics.heightPixels)
            .put("density_dpi", metrics.densityDpi)
            .put("xdpi", metrics.xdpi.toDouble())
            .put("ydpi", metrics.ydpi.toDouble())
            .put("refresh_rate_hz", (display?.refreshRate ?: 0f).toDouble())
            .put("round_screen", app.resources.configuration.isScreenRound)
        val permissions = JSONObject()
        listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.BLUETOOTH_CONNECT,
            "android.permission.health.READ_HEART_RATE",
        ).forEach { permission ->
            permissions.put(permission.substringAfterLast('.'),
                ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED)
        }
        permissions.put("draw_overlays", Settings.canDrawOverlays(app))
            .put("write_settings", Settings.System.canWrite(app))
            .put("notification_listener", app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app))
            .put("notification_policy", app.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted ?: false)
            .put("accessibility", FlueAccessibilityService.isEnabled(app))
        val aod = AodPreferences.read(app)
        val aodInfo = JSONObject().put("enabled", aod.enabled)
            .put("timeout_seconds", aod.timeoutSeconds).put("brightness_percent", aod.brightnessPercent)
            .put("lock_gestures", aod.lockGestures).put("lock_delay_seconds", aod.lockDelaySeconds)
            .put("reawaken", aod.reawaken).put("power_button_exit", aod.powerButtonExit)
            .put("shallow_sleep", aod.shallowSleep).put("always_when_charging", aod.alwaysWhenCharging)
        JSONObject()
            .put("schema_version", 1)
            .put("app", JSONObject().put("package", BuildConfig.APPLICATION_ID)
                .put("version_name", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
                .put("build_type", BuildConfig.BUILD_TYPE).put("debug_build", BuildConfig.DEBUG))
            .put("system", system).put("permissions", permissions)
            .put("configuration", JSONObject(allowlistedDiagnosticConfig(configuration))).put("aod", aodInfo)
            .put("measurement", JSONObject()
                .put("source", "Window.FrameMetrics.TOTAL_DURATION")
                .put("interval_ms", 1000)
                .put("jank", "total duration exceeds current display frame budget")
                .put("fps", "observed rendered frames divided by elapsed interval; idle is zero")
                .put("scope", "resumed Flue Activity windows; includes diagnostics UI rendering overhead")
                .put("background", "sampling paused; five minute elapsedRealtime deadline continues"))
            .toString(2)
    }
}
