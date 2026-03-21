package com.example.wlauncher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import kotlin.system.exitProcess

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val stackTrace = throwable.stackTraceToString()
            val info = buildString {
                appendLine("Application Config:")
                appendLine("- Build Version: ${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}")
                appendLine("- Build Code: ${BuildConfig.VERSION_CODE}")
                appendLine("- Current Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine()
                appendLine("Device Config:")
                appendLine("- Model: ${Build.MODEL}")
                appendLine("- Manufacturer: ${Build.MANUFACTURER}")
                appendLine("- SDK: ${Build.VERSION.SDK_INT}")
                appendLine("- ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine()
                appendLine("Stack Trace:")
                append(stackTrace)
            }

            val intent = Intent(context, CrashActivity::class.java).apply {
                putExtra("crash_info", info)
                putExtra("crash_brief", "${throwable.javaClass.simpleName}: ${throwable.message}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Process.killProcess(Process.myPid())
        exitProcess(1)
    }
}
