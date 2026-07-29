package com.flue.launcher.watchface.standard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Android 16 replaces BODY_SENSORS with the granular heart-rate permission. */
internal const val ANDROID_16_READ_HEART_RATE_PERMISSION =
    "android.permission.health.READ_HEART_RATE"

internal fun requiredHeartRatePermission(): String =
    if (Build.VERSION.SDK_INT >= 36) {
        ANDROID_16_READ_HEART_RATE_PERMISSION
    } else {
        Manifest.permission.BODY_SENSORS
    }

internal fun hasHeartRatePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        requiredHeartRatePermission(),
    ) == PackageManager.PERMISSION_GRANTED
