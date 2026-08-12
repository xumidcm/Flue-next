package com.flue.launcher.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.flue.launcher.R

/**
 * Android 8.1 does not expose the accessibility global lock-screen action.
 * Keep that one platform exception explicit so all newer releases continue to
 * use the existing accessibility service path.
 */
enum class ScreenLockControlMode {
    DEVICE_ADMIN,
    ACCESSIBILITY,
}

internal fun screenLockControlMode(sdkInt: Int): ScreenLockControlMode =
    if (sdkInt == Build.VERSION_CODES.O_MR1) {
        ScreenLockControlMode.DEVICE_ADMIN
    } else {
        ScreenLockControlMode.ACCESSIBILITY
    }

class FlueDeviceAdminReceiver : DeviceAdminReceiver()

object FlueDeviceAdminController {
    private fun component(context: Context): ComponentName =
        ComponentName(context, FlueDeviceAdminReceiver::class.java)

    fun isActive(context: Context): Boolean {
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return manager.isAdminActive(component(context))
    }

    fun requestActivation(context: Context): Boolean {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.flue_device_admin_description),
            )
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun lockScreen(context: Context): Boolean {
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!manager.isAdminActive(component(context))) return false
        return runCatching {
            manager.lockNow()
            true
        }.getOrDefault(false)
    }
}
