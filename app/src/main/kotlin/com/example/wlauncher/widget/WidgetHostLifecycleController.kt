package com.flue.launcher.widget

internal const val SIDE_SCREEN_APP_WIDGET_HOST_ID = 1024

/**
 * Keeps one application-owned AppWidgetHost listener alive while a launcher
 * activity is started. Owners are tracked separately so a retiring activity
 * cannot stop listening after its replacement has already started.
 */
internal class WidgetHostLifecycleController(
    private val startListening: () -> Unit,
    private val stopListening: () -> Unit,
) {
    private val owners = mutableSetOf<Any>()

    @Synchronized
    fun acquire(owner: Any) {
        if (!owners.add(owner)) return
        if (owners.size != 1) return
        try {
            startListening()
        } catch (error: Exception) {
            owners.remove(owner)
            throw error
        }
    }

    @Synchronized
    fun release(owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        stopListening()
    }
}
