package com.flue.launcher.util

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object RecentsVisibility {
    // 每个activity的 onCreate/onResume 都会问一次这个偏好；直接 runBlocking 读
    // DataStore 会把主线程钉死到磁盘 IO（进程首读最明显）。偏好只在设置页变更，
    // 用进程级缓存兜住：启动时后台预热一次，之后读全是内存取值。
    @Volatile
    private var cachedHideFromRecents: Boolean? = null

    /** 后台预热缓存，避免首个 Activity 的主线程等待 DataStore 首读。 */
    fun warmUp(context: Context) {
        readPreference(context)
    }

    /** 偏好变更时由持有 DataStore 收集器的一方推送，保持缓存不过期。 */
    fun updateCache(hideFromRecents: Boolean) {
        cachedHideFromRecents = hideFromRecents
    }

    @JvmStatic
    fun readPreference(context: Context): Boolean {
        cachedHideFromRecents?.let { return it }
        val value = runBlocking {
            context.applicationContext.dataStore.data.first()[LauncherViewModel.KEY_HIDE_FROM_RECENTS] ?: true
        }
        cachedHideFromRecents = value
        return value
    }

    @JvmStatic
    fun apply(activity: Activity) {
        apply(activity, readPreference(activity.applicationContext))
    }

    @JvmStatic
    fun apply(activity: Activity, enabled: Boolean) {
        val activityManager = activity.getSystemService(ActivityManager::class.java) ?: return
        activityManager.appTasks.forEach { task ->
            runCatching { task.setExcludeFromRecents(enabled) }
        }
    }
}
