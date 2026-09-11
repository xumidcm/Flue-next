package com.flue.launcher.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.flue.launcher.data.model.WidgetInfo

internal class WidgetRefreshGate(private val intervalMs: Long = 1_000L) {
    private val lastRequests = mutableMapOf<String, Long>()
    fun acquire(key: String, now: Long, force: Boolean = false): Boolean {
        val previous = lastRequests[key]
        if (!force && previous != null && now - previous in 0 until intervalMs) return false
        lastRequests[key] = now
        return true
    }
    fun failed(key: String) { lastRequests.remove(key) }
}

internal class WidgetRefreshCoordinator(context: Context) {
    private val app = context.applicationContext
    private val appWidgetManager = AppWidgetManager.getInstance(app)
    private val gate = WidgetRefreshGate()

    /**
     * Ask each provider once with its bound ids. This never opens the provider's Activity or
     * rebinds an existing id.
     *
     * Calling a provider receiver directly is not sufficient: some widget receivers only accept
     * system dispatches, so an explicit broadcast from Flue can be rejected while the provider
     * process stays dead. Updating the bound id's options routes through the system AppWidget
     * service and delivers `onAppWidgetOptionsChanged`, which gives Android a legitimate reason
     * to start that process. The traditional update broadcast remains as a compatibility path for
     * providers which only refresh from `onUpdate`.
     */
    @Synchronized
    fun refresh(widgets: List<WidgetInfo>, force: Boolean = false): Boolean {
        var success = true
        widgets.filter { it.widgetId > 0 }.groupBy { it.componentName }.forEach { (provider, entries) ->
            val ids = entries.map { it.widgetId }.distinct().sorted().toIntArray()
            val key = provider.flattenToString() + ids.joinToString(",")
            if (gate.acquire(key, SystemClock.elapsedRealtime(), force)) {
                // This value must survive Flue process recreation without repeating the last
                // persisted option. elapsedRealtimeNanos is monotonic for the whole boot, unlike
                // an in-process counter which would start from one again after every cold launch.
                val sequence = SystemClock.elapsedRealtimeNanos()
                var systemDispatched = false
                ids.forEach { widgetId ->
                    runCatching {
                        // updateAppWidgetOptions merges this bundle into the existing options, so
                        // only the wake token is needed and provider sizing remains untouched.
                        val options = Bundle().apply {
                            putLong(HOST_REFRESH_SEQUENCE_OPTION, sequence)
                        }
                        appWidgetManager.updateAppWidgetOptions(widgetId, options)
                    }.onSuccess {
                        systemDispatched = true
                    }.onFailure { error ->
                        Log.w(
                            WIDGET_REFRESH_LOG_TAG,
                            "Unable to request system widget callback for $provider id=$widgetId",
                            error,
                        )
                    }
                }
                val broadcastDispatched = runCatching {
                    app.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                        component = provider
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    })
                }.onFailure { error ->
                    Log.w(
                        WIDGET_REFRESH_LOG_TAG,
                        "Unable to send compatibility widget update to $provider",
                        error,
                    )
                }.isSuccess
                if (!systemDispatched && !broadcastDispatched) {
                    gate.failed(key)
                    success = false
                }
            }
        }
        return success
    }

    private companion object {
        const val WIDGET_REFRESH_LOG_TAG = "FlueWidgetRefresh"
        const val HOST_REFRESH_SEQUENCE_OPTION =
            "com.flue.launcher.appwidget.HOST_REFRESH_SEQUENCE"
    }
}
