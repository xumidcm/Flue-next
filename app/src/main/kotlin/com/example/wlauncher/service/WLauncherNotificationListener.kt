package com.flue.launcher.service

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotifData(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
    val icon: ImageBitmap?,
    val isClearable: Boolean,
    val contentIntentAvailable: Boolean
)

class WLauncherNotificationListener : NotificationListenerService() {

    companion object {
        private val _notifications = MutableStateFlow<List<NotifData>>(emptyList())
        val notifications: StateFlow<List<NotifData>> = _notifications.asStateFlow()

        private var instance: WLauncherNotificationListener? = null
        private val pendingIntentMap = mutableMapOf<String, PendingIntent?>()

        fun isConnected() = instance != null

        fun dismissNotification(key: String) {
            instance?.cancelNotification(key)
        }

        fun dismissNotifications(keys: List<String>) {
            val service = instance ?: return
            keys.forEach(service::cancelNotification)
        }

        fun openNotification(key: String): Boolean {
            val pendingIntent = synchronized(pendingIntentMap) { pendingIntentMap[key] } ?: return false
            return runCatching {
                pendingIntent.send()
            }.isSuccess
        }
    }

    override fun onListenerConnected() {
        instance = this
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        instance = null
        _notifications.value = emptyList()
        synchronized(pendingIntentMap) { pendingIntentMap.clear() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    private fun refreshNotifications() {
        try {
            val sbns = activeNotifications ?: return
            val pm = applicationContext.packageManager
            _notifications.value = sbns
                .filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0 }
                .sortedByDescending { it.postTime }
                .take(20)
                .map { sbn ->
                    val n = sbn.notification
                    val extras = n.extras
                    val appLabel = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                    } catch (_: Exception) { sbn.packageName }

                    val iconBitmap = try {
                        val smallIcon = n.smallIcon
                        smallIcon?.loadDrawable(applicationContext)?.toBitmap(48, 48)?.asImageBitmap()
                    } catch (_: Exception) { null }

                    NotifData(
                        key = sbn.key,
                        packageName = sbn.packageName,
                        appLabel = appLabel,
                        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "",
                        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
                        time = sbn.postTime,
                        icon = iconBitmap,
                        isClearable = sbn.isClearable,
                        contentIntentAvailable = n.contentIntent != null
                    )
                }.also { notifications ->
                    synchronized(pendingIntentMap) {
                        pendingIntentMap.clear()
                        sbns.forEach { sbn ->
                            pendingIntentMap[sbn.key] = sbn.notification.contentIntent
                        }
                    }
                }
        } catch (_: Exception) {}
    }
}
