package com.flue.launcher.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
    private val gate = WidgetRefreshGate()

    /** Ask each provider once with its bound ids. Does not open its Activity or rebind valid ids. */
    @Synchronized
    fun refresh(widgets: List<WidgetInfo>, force: Boolean = false): Boolean {
        var success = true
        widgets.filter { it.widgetId > 0 }.groupBy { it.componentName }.forEach { (provider, entries) ->
            val ids = entries.map { it.widgetId }.distinct().sorted().toIntArray()
            val key = provider.flattenToString() + ids.joinToString(",")
            if (gate.acquire(key, SystemClock.elapsedRealtime(), force)) {
                try {
                    app.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                        component = provider
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    })
                } catch (_: RuntimeException) {
                    gate.failed(key)
                    success = false
                }
            }
        }
        return success
    }
}
