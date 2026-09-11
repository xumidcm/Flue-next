package com.flue.launcher.status

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun isDoNotDisturbFilter(filter: Int): Boolean = when (filter) {
    NotificationManager.INTERRUPTION_FILTER_PRIORITY,
    NotificationManager.INTERRUPTION_FILTER_NONE,
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
    else -> false
}

/** One live source for the control centre and face, including ADB-granted listener access. */
object DoNotDisturbState {
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    private var installed = false
    private var listener: (() -> Int)? = null

    fun initialize(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh(context) }
        }, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        refresh(app)
    }

    fun setListener(context: Context, readFilter: (() -> Int)?) {
        listener = readFilter
        refresh(context)
    }

    fun refresh(context: Context): Boolean {
        val fromListener = runCatching { listener?.invoke() }.getOrNull()
            ?.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        val filter = fromListener ?: runCatching {
            context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        }.getOrNull() ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        mutableEnabled.value = isDoNotDisturbFilter(filter)
        return mutableEnabled.value
    }
}
