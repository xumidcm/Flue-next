package com.flue.launcher.logging

import android.content.Context

object FlueLog {
    private const val PREFS_NAME = "flue_diagnostics"
    private const val KEY_DIAGNOSTIC_UNTIL = "diagnostic_logging_until"

    @Volatile
    private var diagnosticLoggingUntil = 0L

    fun configureDiagnostics(context: Context?) {
        if (context == null) return
        diagnosticLoggingUntil = 0L
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DIAGNOSTIC_UNTIL)
            .apply()
    }

    fun isDiagnosticLoggingEnabled(): Boolean =
        diagnosticLoggingUntil > System.currentTimeMillis()

    fun setDiagnosticLoggingEnabled(context: Context?, enabled: Boolean, durationMs: Long) {
        val until = if (enabled) {
            System.currentTimeMillis() + durationMs.coerceAtLeast(0L)
        } else {
            0L
        }
        diagnosticLoggingUntil = until
        context?.applicationContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putLong(KEY_DIAGNOSTIC_UNTIL, until)
            ?.apply()
    }
}
