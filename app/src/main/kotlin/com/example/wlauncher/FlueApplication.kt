package com.flue.launcher

import android.app.Application
import android.appwidget.AppWidgetHost
import android.content.Context
import com.flue.launcher.logging.FlueLog
import com.flue.launcher.data.repository.AppRepository
import com.flue.launcher.data.repository.WidgetRepository
import com.flue.launcher.i18n.FlueLocaleManager
import com.flue.launcher.ui.theme.ThemeMode
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.DEFAULT_UI_STYLE
import com.flue.launcher.watchface.standard.WatchWeatherRepository
import com.flue.launcher.watchface.standard.WatchWeatherSources
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.widget.SIDE_SCREEN_APP_WIDGET_HOST_ID
import com.flue.launcher.widget.WidgetHostLifecycleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class FlueApplication : Application() {
    val repositories: FlueRepositories by lazy { FlueRepositories(this) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(FlueLocaleManager.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        FlueLog.configureDiagnostics(this)
        com.flue.launcher.status.DoNotDisturbState.initialize(this)
        // Standard, JB, and editor previews all resolve their null weather
        // source through this one application-owned repository. Its cached
        // snapshot and temperature-unit preference therefore survive host
        // recreation and remain identical across those surfaces.
        WatchWeatherSources.applicationSource = WatchWeatherRepository.default(this)
        // 最近任务可见性偏好在多个 Activity 的 onCreate/onResume 同步读取；
        // 在后台先把 DataStore 首读做完，主线程读方就只剩内存取值。
        repositories.applicationScope.launch {
            RecentsVisibility.warmUp(this@FlueApplication)
        }
    }

    companion object {
        fun repositories(context: Context): FlueRepositories =
            (context.applicationContext as? FlueApplication)?.repositories
                ?: FlueRepositories(context.applicationContext)
    }
}

class FlueRepositories(context: Context) {
    private val appContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sharedState = SharedLauncherState()
    val appRepository: AppRepository by lazy { AppRepository(appContext) }
    val widgetRepository: WidgetRepository by lazy { WidgetRepository(appContext) }
    internal val widgetRefreshCoordinator by lazy { com.flue.launcher.widget.WidgetRefreshCoordinator(appContext) }
    val appWidgetHost: AppWidgetHost by lazy {
        AppWidgetHost(appContext, SIDE_SCREEN_APP_WIDGET_HOST_ID)
    }
    internal val widgetHostLifecycle: WidgetHostLifecycleController by lazy {
        WidgetHostLifecycleController(
            startListening = appWidgetHost::startListening,
            stopListening = appWidgetHost::stopListening,
        )
    }
}

class SharedLauncherState {
    /**
     * 默认深色（老师定）。
     *
     * 这里与 `LauncherViewModel` 读偏好时的兜底值**必须一致** —— 不一致的话冷启动会先按
     * 一种主题画一帧、读到偏好再跳成另一种，就是一次刺眼的闪白。
     */
    val themeMode = MutableStateFlow(ThemeMode.DARK)
    val uiStyle = MutableStateFlow(DEFAULT_UI_STYLE)
}
