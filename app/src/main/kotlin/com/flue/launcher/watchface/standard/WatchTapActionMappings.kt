package com.flue.launcher.watchface.standard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * 表盘「可配置命名动作」到具体应用的映射（`WM_TAP_ACTIONS.md` §3）。
 *
 * 上游 CloX 把这张表放在 **设置 → Tap Actions**，偏好键是 `tap_action_<name>`；表盘只写名字
 * （`w_music`、`w_dialer`…），指到哪个应用由用户决定。Flue 一直缺这张表，于是所有命名动作只能
 * 猜系统默认应用 —— 而手表上 `CATEGORY_APP_MUSIC` / `ACTION_DIAL` 这类入口**经常没人声明**，
 * 结果就是老师报的「大量的动作不可用」。
 *
 * 有了映射之后动作的解析顺序是：**用户映射 → 系统默认 → 明确提示**。
 *
 * 存 SharedPreferences 而不是 launcher 的 DataStore：这张表要在**渲染线程**上同步读（点一下
 * 就要立刻起应用），DataStore 的挂起读在这里只能靠 runBlocking，那是给自己埋帧率坑。
 */
internal class WatchTapActionMappings private constructor(
    private val preferences: SharedPreferences,
) {

    /** 用户给这个动作指定的组件，没设置返回 null。 */
    fun component(action: String): ComponentName? {
        val raw = preferences.getString(key(action), null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val slash = raw.indexOf('/')
        if (slash <= 0 || slash == raw.length - 1) return null
        val packageName = raw.substring(0, slash)
        val activity = raw.substring(slash + 1)
        return ComponentName(packageName, activity)
    }

    /**
     * 直接给出可用的启动 Intent。
     *
     * 映射里的组件可能**已经被卸载**（用户换过应用），所以这里要问一次 PackageManager：
     * 组件没了就当没设置，让调用方继续走系统默认那一档，而不是抛 ActivityNotFound。
     */
    fun launchIntent(context: Context, action: String): Intent? {
        val component = component(action) ?: return null
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
        if (context.packageManager.resolveActivity(intent, 0) != null) return intent
        // 组件失效时顺手清掉，免得设置页一直显示一个已卸载的应用。
        clear(action)
        return context.packageManager.getLaunchIntentForPackage(component.packageName)
    }

    fun write(action: String, packageName: String, activityClass: String) {
        preferences.edit().putString(key(action), "$packageName/$activityClass").apply()
    }

    fun clear(action: String) {
        preferences.edit().remove(key(action)).apply()
    }

    private fun key(action: String): String = KEY_PREFIX + action.trim().lowercase()

    companion object {
        private const val PREFERENCES_NAME = "flue_watch_tap_actions"

        /** 与上游同一个键名前缀，将来做导入导出时不用再翻译一遍。 */
        private const val KEY_PREFIX = "tap_action_"

        @Volatile
        private var instance: WatchTapActionMappings? = null

        fun of(context: Context): WatchTapActionMappings = instance ?: synchronized(this) {
            instance ?: WatchTapActionMappings(
                context.applicationContext
                    .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
