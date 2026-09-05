package com.flue.launcher.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flue.launcher.FlueApplication
import com.flue.launcher.applist.integration.toNativeAppListEntry
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppListReducer
import com.flue.launcher.applist.model.AppListSnapshot
import com.flue.launcher.applist.model.NormalizedPoint
import com.flue.launcher.R
import com.flue.launcher.applist.persistence.AppOrderWriteCoordinator
import com.flue.launcher.backup.FlueBackupExportSummary
import com.flue.launcher.backup.FlueBackupManager
import com.flue.launcher.backup.FlueBackupOptions
import com.flue.launcher.backup.FlueBackupPreview
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.iconForDisplay
import com.flue.launcher.iconpack.IconPackDescriptor
import com.flue.launcher.iconpack.IconPackScanner
import com.flue.launcher.i18n.persistLanguageSelection
import com.flue.launcher.launchsession.CancelReason
import com.flue.launcher.launchsession.ExternalLaunchRequest
import com.flue.launcher.launchsession.ExternalLaunchSession
import com.flue.launcher.launchsession.ExternalLaunchSessionTracker
import com.flue.launcher.launchsession.ExternalLaunchState
import com.flue.launcher.launchsession.ReturnKind
import com.flue.launcher.launchsession.retainsLaunchIcon
import com.flue.launcher.motion.FlueMotionProfile
import com.flue.launcher.motion.FlueMotionProfileJson
import com.flue.launcher.motion.FlueMotionProfiles
import com.flue.launcher.applist.background.AppListBackgroundConfig
import com.flue.launcher.applist.background.AppListBackgroundJson
import com.flue.launcher.applist.background.AppListBackgroundMode
import com.flue.launcher.layout.FlueLayoutProfile
import com.flue.launcher.layout.FlueLayoutProfileJson
import com.flue.launcher.layout.FlueLayoutProfiles
import com.flue.launcher.layout.flueUniqueProfileName
import com.flue.launcher.layout.migratedLayoutProfiles
import com.flue.launcher.layout.FLUE_LAYOUT_APP_LIST_SCALE_MAX
import com.flue.launcher.layout.FLUE_LAYOUT_APP_LIST_SCALE_MIN
import com.flue.launcher.layout.FLUE_LAYOUT_NARROW_COLUMNS_MAX
import com.flue.launcher.layout.FLUE_LAYOUT_NARROW_COLUMNS_MIN
import com.flue.launcher.util.FlowCrescentDirection
import com.flue.launcher.util.FlowShapeCurve
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.ui.common.FlueScreenShape
import com.flue.launcher.ui.common.DEFAULT_OVERLAY_MASK_CORNER_RADIUS_DP
import com.flue.launcher.ui.common.normalizeOverlayMaskCornerRadiusDp
import com.flue.launcher.motion.MotionEasing
import com.flue.launcher.motion.MotionPhaseKey
import com.flue.launcher.service.FlueAccessibilityService
import com.flue.launcher.ui.anim.FlueTransitionMotion
import com.flue.launcher.ui.theme.ThemeMode
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.DEFAULT_UI_STYLE
import com.flue.launcher.ui.theme.uiStyleFromPreference
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.navigation.ScreenState
import com.flue.launcher.ui.notification.NotificationActionUi
import com.flue.launcher.ui.notification.NotificationEntryUi
import com.flue.launcher.ui.notification.NotificationGroupUi
import com.flue.launcher.ui.notification.NotificationRevealTarget
import com.flue.launcher.ui.notification.alignNotificationPreviewOrder
import com.flue.launcher.ui.notification.stabilizeNotificationGroupOrder
import com.flue.launcher.service.NotifData
import com.flue.launcher.service.WLauncherNotificationListener
import com.flue.launcher.ui.controlcenter.MusicTextSwitchAnimations
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.WatchClockPosition
import com.flue.launcher.watchface.WatchClockColorMode
import com.flue.launcher.watchface.WatchFaceClockStyle
import com.flue.launcher.watchface.WatchFaceMd3eShape
import com.flue.launcher.watchface.InternalWatchFaceStorage
import com.flue.launcher.watchface.WatchFacePhotoCache
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceDescriptors
import com.flue.launcher.watchface.jbwatch.JBWATCH_ID_PREFIX
import com.flue.launcher.watchface.jbwatch.JbWatchFaceStorage
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.standard.WATCHMAKER_ID_PREFIX
import com.flue.launcher.watchface.standard.WatchCanvasPreset
import com.flue.launcher.watchface.standard.WatchCanvasSettings
import com.flue.launcher.watchface.standard.WatchDateLocale
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.standard.watchArchiveDeclaresProtection
import com.flue.launcher.watchface.store.FlueDownloadTuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.math.roundToInt

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_settings")

private const val EXTERNAL_LAUNCH_BACKGROUND_TIMEOUT_MS = 1_500L
private const val APP_ORDER_WRITE_DEBOUNCE_MS = 120L
private const val EXTERNAL_RETURN_EVIDENCE_WAIT_MS = 300L
private const val MAX_GENERIC_WATCH_ARCHIVE_BYTES = 500L * 1024L * 1024L

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private var cachedWatchFaces: List<WatchFaceDescriptor>? = null

        /**
         * 进程级刷新令牌：所有 Activity 共享同一个实例，避免跨 Activity 的
         * bump 因 ViewModel 隔离而失效（见 _watchFaceRefreshToken 注释）。
         */
        @JvmField
        val sharedWatchFaceRefreshToken = MutableStateFlow(0)

        val KEY_LAYOUT = stringPreferencesKey("layout_mode")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        /** 转场动画风格（平移 / 缩放）。与界面风格无关，见 FlueTransitionMotion。 */
        val KEY_TRANSITION_MOTION = stringPreferencesKey("transition_motion")
        val KEY_UI_STYLE = stringPreferencesKey("ui_style")
        val KEY_SIDE_SCREEN_ENABLED = booleanPreferencesKey("side_screen_enabled")
        val KEY_SIDE_SCREEN_SHORTCUTS = stringPreferencesKey("side_screen_shortcuts")
        val KEY_BLUR = booleanPreferencesKey("blur_enabled")
        val KEY_EDGE_BLUR = booleanPreferencesKey("edge_blur_enabled")
        val KEY_LOW_RES = booleanPreferencesKey("low_res_icons")
        val KEY_LEGACY_CIRCULAR_ICONS = booleanPreferencesKey("legacy_circular_icons")
        val KEY_ANIMATION_OVERRIDE = booleanPreferencesKey("animation_override_enabled")
        val KEY_SPLASH_ICON = booleanPreferencesKey("splash_icon")
        val KEY_SPLASH_DELAY = intPreferencesKey("splash_delay")
        val KEY_DIRECT_LAUNCH_APP_LIST = booleanPreferencesKey("direct_launch_app_list")
        val KEY_APP_ORDER = stringPreferencesKey("app_order")
        val KEY_HONEYCOMB_COLS = intPreferencesKey("honeycomb_cols")
        val KEY_HONEYCOMB_TOP_BLUR = intPreferencesKey("honeycomb_top_blur")
        val KEY_HONEYCOMB_BOTTOM_BLUR = intPreferencesKey("honeycomb_bottom_blur")
        val KEY_HONEYCOMB_EDGE_BLUR_TENTHS = intPreferencesKey("honeycomb_edge_blur_tenths")
        val KEY_HONEYCOMB_TOP_FADE = intPreferencesKey("honeycomb_top_fade")
        val KEY_HONEYCOMB_BOTTOM_FADE = intPreferencesKey("honeycomb_bottom_fade")
        val KEY_APP_LIST_FISHEYE = booleanPreferencesKey("app_list_fisheye_enabled")
        val KEY_MATERIAL_HONEYCOMB_TOP_FISHEYE = booleanPreferencesKey("material_honeycomb_top_fisheye_enabled")
        val KEY_APP_LIST_FISHEYE_RANGE_ROWS = intPreferencesKey("app_list_fisheye_range_rows")
        val KEY_APP_LIST_FISHEYE_STRENGTH_PERCENT = intPreferencesKey("app_list_fisheye_strength_percent")
        val KEY_APP_LIST_EDGE_SPACING_COMPRESSION = booleanPreferencesKey("app_list_edge_spacing_compression_enabled")
        val KEY_APP_LIST_LEFT_SAFE_INSET_PERCENT = intPreferencesKey("app_list_left_safe_inset_percent")
        val KEY_APP_LIST_SCALE_PERCENT = intPreferencesKey("app_list_scale_percent")
        val KEY_GLOBAL_UI_SCALE_PERCENT = intPreferencesKey("global_ui_scale_percent")
        val KEY_OVERLAY_MASK_CORNER_RADIUS_DP = intPreferencesKey("overlay_mask_corner_radius_dp")
        val KEY_APP_LIST_WATCHFACE_COLORS = booleanPreferencesKey("app_list_watchface_colors")
        val KEY_APP_LIST_ROW_BORDER = booleanPreferencesKey("app_list_row_border_enabled")
        val KEY_APP_LIST_GRID_ICON_PLATE = booleanPreferencesKey("app_list_grid_icon_plate_enabled")
        val KEY_APP_LIST_FOLDERS_ENABLED = booleanPreferencesKey("app_list_folders_enabled")
        val KEY_APP_LIST_SCROLL_INDICATOR = booleanPreferencesKey("app_list_scroll_indicator_enabled")
        private val KEY_REMOVED_FAST_FLOW_ANIMATION = booleanPreferencesKey("fast_flow_animation_enabled")
        val KEY_MUSIC_TEXT_SWITCH_ANIMATION = stringPreferencesKey("music_text_switch_animation")
        private val KEY_REMOVED_TWO_TONE_ICONS = booleanPreferencesKey("two_tone_icons_enabled")
        val KEY_ICON_SHADOW = booleanPreferencesKey("app_icon_shadow_enabled")
        val KEY_APP_LIST_RETURN_SHRINK = booleanPreferencesKey("app_list_return_shrink_enabled")
        val KEY_FACE_RETURN_SHRINK = booleanPreferencesKey("face_return_shrink_enabled")
        val KEY_LEGACY_APP_LIST_TRANSITION = booleanPreferencesKey("legacy_app_list_transition_enabled")
        val KEY_APP_LIST_RETURN_ICON_FLOAT = booleanPreferencesKey("app_list_return_icon_float_enabled")
        val KEY_REDUCE_DYNAMIC_EFFECTS = booleanPreferencesKey("reduce_dynamic_effects")
        val KEY_SCREEN_SHAPE_OVERRIDE = stringPreferencesKey("screen_shape_override")
        val KEY_APP_LIST_STAGGER = booleanPreferencesKey("app_list_stagger_enabled")
        val KEY_MOTION_PROFILE_ID = stringPreferencesKey("motion_profile_id")
        val KEY_CUSTOM_MOTION_PROFILES = stringPreferencesKey("custom_motion_profiles")
        /**
         * 布局档案库（含四种布局的默认档 + 用户副本）。
         *
         * 与动效那边只存「自定义档案」不同：布局的默认档是**可编辑**的（鱼眼滑块历来
         * 拖了就生效），所以整库都要落盘，不能只存自定义那几份。
         */
        val KEY_LAYOUT_PROFILES = stringPreferencesKey("layout_profiles")
        /** 每种布局当前选中的档案 id，JSON 对象：{"honeycomb":"preset:honeycomb", …}。 */
        val KEY_ACTIVE_LAYOUT_PROFILE_IDS = stringPreferencesKey("active_layout_profile_ids")
        /**
         * 应用列表背景配置，单个 JSON（见 [com.flue.launcher.applist.background.AppListBackgroundJson]）。
         *
         * 存成一个 JSON 而不是五个扁平 key：这五项（模式 / 自定义色 / 模糊 / 遮罩 / 图片路径）
         * 只有整体才有意义 —— 单独一个「模糊 70%」在纯色档下毫无含义，分开存必然出现
         * 「模式已经切走了、旧模糊值还在」的半截状态。
         */
        val KEY_APP_LIST_BACKGROUND = stringPreferencesKey("app_list_background")
        val KEY_SHOW_STEP_COUNT = booleanPreferencesKey("show_step_count")
        val KEY_SHOW_NOTIFICATION = booleanPreferencesKey("show_notification")
        val KEY_SHOW_ONGOING_NOTIFICATIONS = booleanPreferencesKey("show_ongoing_notifications")
        val KEY_ROTARY_HAPTICS_ENABLED = booleanPreferencesKey("rotary_haptics_enabled")
        val KEY_NOTIFICATION_SETTING_MIGRATED = booleanPreferencesKey("notification_setting_migrated")
        val KEY_SHOW_WIDGET_PAGE = booleanPreferencesKey("show_widget_page")
        val KEY_SHOW_CONTROL_CENTER = booleanPreferencesKey("show_control_center")
        val KEY_SHOW_MUSIC_CONTROLS = booleanPreferencesKey("show_music_controls")
        val KEY_SHOW_MEDIA_CUSTOM_ACTIONS = booleanPreferencesKey("show_media_custom_actions")
        val KEY_SWAP_MUSIC_NOTIFICATION_COMPONENTS = booleanPreferencesKey("swap_music_notification_components")
        val KEY_DOUBLE_TAP_LOCK_SCREEN = booleanPreferencesKey("double_tap_lock_screen_enabled")
        val KEY_FACE_IGNORES_BACK_KEY = booleanPreferencesKey("face_ignores_back_key")
        val KEY_POWER_MENU_BUTTON = booleanPreferencesKey("power_menu_button_enabled")
        val KEY_WATCHFACE_CHARGING_POWER_TEXT = booleanPreferencesKey("watchface_charging_power_text")
        val KEY_WATCHFACE_TEXT_ENTRY_ANIMATION = booleanPreferencesKey("watchface_text_entry_animation")
        val KEY_WATCHFACE_TEXT_EXIT_ANIMATION = booleanPreferencesKey("watchface_text_exit_animation")
        val KEY_WATCHFACE_STATUS_INDICATORS = booleanPreferencesKey("watchface_status_indicators")
        val KEY_WATCHFACE_BOTTOM_FADE = booleanPreferencesKey("watchface_bottom_fade_enabled")
        val KEY_WATCHMAKER_FORCE_FILL = booleanPreferencesKey("watchmaker_force_fill")

        /**
         * App 级画布默认配置（[WatchCanvasSettings.encode] 的输出）。
         *
         * 缺省时从存量的 [KEY_WATCHMAKER_FORCE_FILL] 推导，所以升级用户看到的画面不变。
         */
        val KEY_WATCHFACE_CANVAS_DEFAULT = stringPreferencesKey("watchface_canvas_default")

        /**
         * 每表盘覆盖，`id=编码` 用换行分隔的多行文本。
         *
         * **表里有条目 = 该表盘不跟随全局默认**；「跟随」不额外存一个布尔，免得
         * 出现「followGlobal=true 却又存着一份覆盖值」这种自相矛盾的状态。
         */
        val KEY_WATCHFACE_CANVAS_OVERRIDES = stringPreferencesKey("watchface_canvas_overrides")
        val KEY_WATCHFACE_GRAVITY_PARALLAX = booleanPreferencesKey("watchface_gravity_parallax_enabled")
        /**
         * 重力景深表盘底部那层模糊背景图的开关，默认开（保持现有观感）。
         *
         * 关掉之后底下只留根 `bg_color`，和 clox 一致 —— 作者本来就叠了背景素材
         * 的表盘，两层背景叠一起会打架。
         */
        val KEY_WATCHFACE_GRAVITY_BACKDROP_BLUR =
            booleanPreferencesKey("watchface_gravity_backdrop_blur_enabled")
        val KEY_WATCHFACE_DATE_LOCALE = stringPreferencesKey("watchface_date_locale")
        val KEY_DEVICE_PRESET_APPLIED = booleanPreferencesKey("device_preset_applied")
        val KEY_HIDDEN_APPS = stringPreferencesKey("hidden_apps")
        val KEY_ICON_PACK_PACKAGE = stringPreferencesKey("icon_pack_package")
        val KEY_SELECTED_WATCHFACE_ID = stringPreferencesKey("selected_watchface_id")
        val KEY_LAST_WATCHFACE_ERROR = stringPreferencesKey("last_watchface_error")
        val KEY_BUILTIN_PHOTO_PATH = stringPreferencesKey("builtin_photo_path")
        val KEY_BUILTIN_VIDEO_PATH = stringPreferencesKey("builtin_video_path")
        val KEY_PHOTO_CLOCK_POSITION = stringPreferencesKey("photo_clock_position")
        val KEY_VIDEO_CLOCK_POSITION = stringPreferencesKey("video_clock_position")
        val KEY_PHOTO_CLOCK_SIZE = intPreferencesKey("photo_clock_size")
        val KEY_VIDEO_CLOCK_SIZE = intPreferencesKey("video_clock_size")
        val KEY_PHOTO_CLOCK_BOLD = booleanPreferencesKey("photo_clock_bold")
        val KEY_VIDEO_CLOCK_BOLD = booleanPreferencesKey("video_clock_bold")
        val KEY_VIDEO_FILL_SCREEN = booleanPreferencesKey("video_fill_screen")
        val KEY_VIDEO_CLOCK_COLOR_MODE = stringPreferencesKey("video_clock_color_mode")
        val KEY_WATCHFACE_FONT_PATH = stringPreferencesKey("watchface_font_path")
        val KEY_PHOTO_CLOCK_STYLE = stringPreferencesKey("photo_clock_style")
        val KEY_VIDEO_CLOCK_STYLE = stringPreferencesKey("video_clock_style")
        val KEY_PHOTO_MD3E_SHAPE = stringPreferencesKey("photo_md3e_shape")
        val KEY_VIDEO_MD3E_SHAPE = stringPreferencesKey("video_md3e_shape")
        val KEY_PHOTO_USE_THEME_TEXT_COLOR = booleanPreferencesKey("photo_use_theme_text_color")
        val KEY_VIDEO_USE_THEME_TEXT_COLOR = booleanPreferencesKey("video_use_theme_text_color")
        val KEY_PHOTO_TEXT_COLOR = intPreferencesKey("photo_text_color")
        val KEY_VIDEO_TEXT_COLOR = intPreferencesKey("video_text_color")
        val KEY_PHOTO_MD3E_AUTO_COLORS = booleanPreferencesKey("photo_md3e_auto_colors")
        val KEY_VIDEO_MD3E_AUTO_COLORS = booleanPreferencesKey("video_md3e_auto_colors")
        val KEY_PHOTO_MD3E_TEXT_COLOR = intPreferencesKey("photo_md3e_text_color")
        val KEY_VIDEO_MD3E_TEXT_COLOR = intPreferencesKey("video_md3e_text_color")
        val KEY_PHOTO_MD3E_FACE_COLOR = intPreferencesKey("photo_md3e_face_color")
        val KEY_VIDEO_MD3E_FACE_COLOR = intPreferencesKey("video_md3e_face_color")
        val KEY_PHOTO_MD3E_HOUR_COLOR = intPreferencesKey("photo_md3e_hour_color")
        val KEY_VIDEO_MD3E_HOUR_COLOR = intPreferencesKey("video_md3e_hour_color")
        val KEY_PHOTO_MD3E_MINUTE_COLOR = intPreferencesKey("photo_md3e_minute_color")
        val KEY_VIDEO_MD3E_MINUTE_COLOR = intPreferencesKey("video_md3e_minute_color")
        val KEY_PHOTO_MD3E_SECOND_COLOR = intPreferencesKey("photo_md3e_second_color")
        val KEY_VIDEO_MD3E_SECOND_COLOR = intPreferencesKey("video_md3e_second_color")
        val KEY_PHOTO_SHOW_SECONDS = booleanPreferencesKey("photo_show_seconds")
        val KEY_VIDEO_SHOW_SECONDS = booleanPreferencesKey("video_show_seconds")
        val KEY_PHOTO_CUSTOM_TEXT = stringPreferencesKey("photo_custom_text")
        val KEY_VIDEO_CUSTOM_TEXT = stringPreferencesKey("video_custom_text")
        val KEY_BUILTIN_MANAGER_THUMBNAILS = booleanPreferencesKey("builtin_manager_thumbnails")
        val KEY_HIDE_FROM_RECENTS = booleanPreferencesKey("hide_from_recents")
        val KEY_WATCH_STORE_SEGMENTED_DOWNLOAD = booleanPreferencesKey("watch_store_segmented_download")
        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_GLASS_HIGHLIGHT = booleanPreferencesKey("glass_highlight_enabled")
        val KEY_WIDGET_GLASS_HIGHLIGHT = booleanPreferencesKey("widget_glass_highlight_enabled")
        val KEY_SIDE_SCREEN_WIDGETS = stringPreferencesKey("side_screen_widgets")
        val KEY_SIDE_SCREEN_SHORTCUT_ROWS = intPreferencesKey("side_screen_shortcut_rows")
        val KEY_SIDE_SCREEN_SHORTCUT_COLS = intPreferencesKey("side_screen_shortcut_cols")
        private const val SIDE_SCREEN_SHORTCUT_SLOT_CAPACITY = 12
        private const val APP_LIST_LEFT_SAFE_INSET_MAX_PERCENT = 50

        private fun normalizeEdgeBlurTenths(value: Int): Int {
            return ((value.coerceIn(5, 50) / 5f).roundToInt() * 5).coerceIn(5, 50)
        }

        private fun normalizeEdgeBlurTenths(value: Float): Int {
            return ((value.coerceIn(0.5f, 5f) * 2f).roundToInt() * 5).coerceIn(5, 50)
        }

        private fun normalizeLeftSafeInsetPercent(value: Int): Int {
            return ((value.coerceIn(0, APP_LIST_LEFT_SAFE_INSET_MAX_PERCENT) / 5f).roundToInt() * 5)
                .coerceIn(0, APP_LIST_LEFT_SAFE_INSET_MAX_PERCENT)
        }

        private fun normalizeAppListScalePercent(value: Int): Int {
            val clamped = value.coerceIn(50, 200)
            if (clamped == 200) return 200
            return ((clamped / 10) * 10).coerceIn(50, 200)
        }

        private fun normalizeGlobalUiScalePercent(value: Int): Int {
            return ((value.coerceIn(50, 150) / 5f).roundToInt() * 5).coerceIn(50, 150)
        }

    }

    private val store = application.dataStore
    private val repositories = FlueApplication.repositories(application)
    private val appRepository = repositories.appRepository

    val allApps: StateFlow<List<AppInfo>> = appRepository.allApps
    val allSelectableApps: StateFlow<List<AppInfo>> = appRepository.allSelectableApps
    val apps: StateFlow<List<AppInfo>> = appRepository.apps
    private val _openFolder = MutableStateFlow<AppInfo?>(null)
    val openFolder: StateFlow<AppInfo?> = _openFolder.asStateFlow()
    private val _openFolderItems = MutableStateFlow<List<AppInfo>>(emptyList())
    val openFolderItems: StateFlow<List<AppInfo>> = _openFolderItems.asStateFlow()
    private val _screenState = MutableStateFlow(ScreenState.Face)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()
    private val _launcherInteractive = MutableStateFlow(true)
    val launcherInteractive: StateFlow<Boolean> = _launcherInteractive.asStateFlow()

    private val _layoutMode = MutableStateFlow(LayoutMode.Honeycomb)
    val layoutMode: StateFlow<LayoutMode> = _layoutMode.asStateFlow()
    private val _themeMode = repositories.sharedState.themeMode
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    private val _transitionMotion = MutableStateFlow(FlueTransitionMotion.Default)
    val transitionMotion: StateFlow<FlueTransitionMotion> = _transitionMotion.asStateFlow()
    private val _uiStyle = repositories.sharedState.uiStyle
    val uiStyle: StateFlow<UiStyle> = _uiStyle.asStateFlow()
    private val _sideScreenEnabled = MutableStateFlow(true)
    val sideScreenEnabled: StateFlow<Boolean> = _sideScreenEnabled.asStateFlow()
    private val _sideScreenShortcutRows = MutableStateFlow(2)
    val sideScreenShortcutRows: StateFlow<Int> = _sideScreenShortcutRows.asStateFlow()
    private val _sideScreenShortcutCols = MutableStateFlow(4)
    val sideScreenShortcutCols: StateFlow<Int> = _sideScreenShortcutCols.asStateFlow()
    private val _sideScreenShortcuts = MutableStateFlow(List(SIDE_SCREEN_SHORTCUT_SLOT_CAPACITY) { null as String? })
    val sideScreenShortcuts: StateFlow<List<String?>> = _sideScreenShortcuts.asStateFlow()

    private val _sideScreenWidgetSlots = MutableStateFlow(emptyList<String?>())
    val sideScreenWidgetSlots: StateFlow<List<String?>> = _sideScreenWidgetSlots.asStateFlow()

    fun setWidgetSlot(slotIndex: Int, slotValue: String?) {
        if (slotIndex < 0) return
        val normalizedValue = slotValue?.takeIf { it.isNotBlank() }
        val next = _sideScreenWidgetSlots.value.toMutableList()
        when {
            slotIndex < next.size && normalizedValue == null -> next.removeAt(slotIndex)
            slotIndex < next.size -> next[slotIndex] = normalizedValue
            slotIndex == next.size && normalizedValue != null -> next.add(normalizedValue)
            else -> return
        }
        updateSideScreenWidgets(next)
    }

    fun removeWidgetSlot(slotIndex: Int) {
        if (slotIndex !in _sideScreenWidgetSlots.value.indices) return
        val next = _sideScreenWidgetSlots.value.toMutableList().apply { removeAt(slotIndex) }
        updateSideScreenWidgets(next)
    }

    fun swapWidgetSlots(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = _sideScreenWidgetSlots.value
        if (fromIndex !in current.indices || toIndex !in 0..current.size) return
        val next = current.toMutableList()
        val item = next.removeAt(fromIndex)
        val insertIndex = if (toIndex > fromIndex) toIndex - 1 else toIndex
        next.add(insertIndex.coerceIn(0, next.size), item)
        updateSideScreenWidgets(next)
    }

    private fun updateSideScreenWidgets(widgets: List<String?>) {
        val normalized = widgets.mapNotNull { value -> value?.takeIf { it.isNotBlank() } }
        _sideScreenWidgetSlots.value = normalized
        persist {
            store.edit { prefs ->
                val serialized = serializeSideScreenWidgets(normalized)
                if (serialized.isEmpty()) {
                    prefs.remove(KEY_SIDE_SCREEN_WIDGETS)
                } else {
                    prefs[KEY_SIDE_SCREEN_WIDGETS] = serialized
                }
            }
        }
    }

    fun setSideScreenShortcutRows(rows: Int) {
        _sideScreenShortcutRows.value = rows.coerceIn(1, 3)
        val savedValue = _sideScreenShortcutRows.value
        persistDebounced("side_screen_shortcut_rows") { store.edit { it[KEY_SIDE_SCREEN_SHORTCUT_ROWS] = savedValue } }
    }

    fun setSideScreenShortcutCols(cols: Int) {
        _sideScreenShortcutCols.value = cols.coerceIn(2, 4)
        val savedValue = _sideScreenShortcutCols.value
        persistDebounced("side_screen_shortcut_cols") { store.edit { it[KEY_SIDE_SCREEN_SHORTCUT_COLS] = savedValue } }
    }

    private val _blurEnabled = MutableStateFlow(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    val blurEnabled: StateFlow<Boolean> = _blurEnabled.asStateFlow()

    private val _edgeBlurEnabled = MutableStateFlow(false)
    val edgeBlurEnabled: StateFlow<Boolean> = _edgeBlurEnabled.asStateFlow()

    private val _lowResIcons = MutableStateFlow(false)
    val lowResIcons: StateFlow<Boolean> = _lowResIcons.asStateFlow()

    private val _animationOverrideEnabled = MutableStateFlow(true)
    val animationOverrideEnabled: StateFlow<Boolean> = _animationOverrideEnabled.asStateFlow()

    private val _splashDelay = MutableStateFlow(500)
    val splashDelay: StateFlow<Int> = _splashDelay.asStateFlow()
    private val _directLaunchAppListEnabled = MutableStateFlow(false)
    val directLaunchAppListEnabled: StateFlow<Boolean> = _directLaunchAppListEnabled.asStateFlow()
    private val _directLaunchAppListPreferenceLoaded = MutableStateFlow(false)
    val directLaunchAppListPreferenceLoaded: StateFlow<Boolean> =
        _directLaunchAppListPreferenceLoaded.asStateFlow()

    private val _appOrder = MutableStateFlow<List<String>>(emptyList())
    val appOrder: StateFlow<List<String>> = _appOrder.asStateFlow()

    private val _honeycombCols = MutableStateFlow(3)
    val honeycombCols: StateFlow<Int> = _honeycombCols.asStateFlow()
    private val _legacyCircularIcons = MutableStateFlow(true)
    val legacyCircularIcons: StateFlow<Boolean> = _legacyCircularIcons.asStateFlow()

    private val _honeycombTopBlur = MutableStateFlow(4)
    val honeycombTopBlur: StateFlow<Int> = _honeycombTopBlur.asStateFlow()

    private val _honeycombBottomBlur = MutableStateFlow(4)
    val honeycombBottomBlur: StateFlow<Int> = _honeycombBottomBlur.asStateFlow()
    private val _honeycombEdgeBlurRadius = MutableStateFlow(4f)
    val honeycombEdgeBlurRadius: StateFlow<Float> = _honeycombEdgeBlurRadius.asStateFlow()

    private val _honeycombTopFade = MutableStateFlow(30)
    val honeycombTopFade: StateFlow<Int> = _honeycombTopFade.asStateFlow()

    private val _honeycombBottomFade = MutableStateFlow(30)
    val honeycombBottomFade: StateFlow<Int> = _honeycombBottomFade.asStateFlow()

    private val _appListFisheyeEnabled = MutableStateFlow(true)
    val appListFisheyeEnabled: StateFlow<Boolean> = _appListFisheyeEnabled.asStateFlow()
    // MD3 顶部鱼眼是额外效果，默认关闭；底部鱼眼仍由总开关控制。
    private val _materialHoneycombTopFisheyeEnabled = MutableStateFlow(false)
    val materialHoneycombTopFisheyeEnabled: StateFlow<Boolean> = _materialHoneycombTopFisheyeEnabled.asStateFlow()
    private val _appListFisheyeRangeRows = MutableStateFlow(4)
    val appListFisheyeRangeRows: StateFlow<Int> = _appListFisheyeRangeRows.asStateFlow()
    private val _appListFisheyeStrengthPercent = MutableStateFlow(100)
    val appListFisheyeStrengthPercent: StateFlow<Int> = _appListFisheyeStrengthPercent.asStateFlow()
    private val _appListEdgeSpacingCompressionEnabled = MutableStateFlow(true)
    val appListEdgeSpacingCompressionEnabled: StateFlow<Boolean> =
        _appListEdgeSpacingCompressionEnabled.asStateFlow()
    private val _appListLeftSafeInsetPercent = MutableStateFlow(0)
    val appListLeftSafeInsetPercent: StateFlow<Int> = _appListLeftSafeInsetPercent.asStateFlow()

    // ---- 布局档案（鱼眼六项 + 心流两项，按布局分开存）------------------------
    //
    // 上面那六个 flow **不再是**真值所在，它们现在是「当前生效档案」的投影：
    // 读取点有一百多处（LauncherActivity / SettingsActivity / 原生列表配置），
    // 全改成读档案对象要动一大片，而投影让它们一行都不用改。
    // 写入一律走 updateActiveLayoutProfile()，然后由 applyActiveLayoutProfile()
    // 把值推回投影 —— 单向流，不存在两份真值互相打架。
    private val _layoutProfiles = MutableStateFlow(FlueLayoutProfiles.builtIns)
    val layoutProfiles: StateFlow<List<FlueLayoutProfile>> = _layoutProfiles.asStateFlow()
    private var activeLayoutProfileIds: Map<LayoutMode, String> =
        FlueLayoutProfiles.builtIns.associate { it.layoutMode to it.id }
    private val _activeLayoutProfile = MutableStateFlow(FlueLayoutProfiles.Honeycomb)
    val activeLayoutProfile: StateFlow<FlueLayoutProfile> = _activeLayoutProfile.asStateFlow()
    /** 心流月牙弧度（占上限鼓出量的百分比）。投影自当前档案。 */
    private val _appListFlowCrescentBulgePercent =
        MutableStateFlow(FlueLayoutProfiles.Flow.crescentBulgePercent)
    val appListFlowCrescentBulgePercent: StateFlow<Int> =
        _appListFlowCrescentBulgePercent.asStateFlow()
    /** 心流月牙是否朝右。投影自当前档案。 */
    private val _appListFlowCrescentToRight = MutableStateFlow(true)
    val appListFlowCrescentToRight: StateFlow<Boolean> =
        _appListFlowCrescentToRight.asStateFlow()
    /**
     * 应用列表背景配置。整份一起存、一起读（见 [KEY_APP_LIST_BACKGROUND]）。
     *
     * 默认 [AppListBackgroundConfig.Default] 就是「纯色跟随系统」，与这个功能加进来之前
     * 逐位相同 —— 加选项不改默认观感。
     */
    private val _appListBackground = MutableStateFlow(AppListBackgroundConfig.Default)
    val appListBackground: StateFlow<AppListBackgroundConfig> = _appListBackground.asStateFlow()
    private val _appListScalePercent = MutableStateFlow(100)
    val appListScalePercent: StateFlow<Int> = _appListScalePercent.asStateFlow()
    private val _globalUiScalePercent = MutableStateFlow(100)
    val globalUiScalePercent: StateFlow<Int> = _globalUiScalePercent.asStateFlow()
    private val _overlayMaskCornerRadiusDp = MutableStateFlow(DEFAULT_OVERLAY_MASK_CORNER_RADIUS_DP)
    val overlayMaskCornerRadiusDp: StateFlow<Int> = _overlayMaskCornerRadiusDp.asStateFlow()
    private val _appListWatchFaceColors = MutableStateFlow(false)
    val appListWatchFaceColors: StateFlow<Boolean> = _appListWatchFaceColors.asStateFlow()
    private val _appListRowBorderEnabled = MutableStateFlow(false)
    val appListRowBorderEnabled: StateFlow<Boolean> = _appListRowBorderEnabled.asStateFlow()
    // 默认 true = 保持网格布局一直以来的样子（圆图标底下那块底板）。
    // 这个开关是新增的「能关掉」，不是新的默认外观，所以老用户升级后画面不变。
    private val _appListGridIconPlateEnabled = MutableStateFlow(true)
    val appListGridIconPlateEnabled: StateFlow<Boolean> = _appListGridIconPlateEnabled.asStateFlow()
    private val _appListFoldersEnabled = MutableStateFlow(true)
    val appListFoldersEnabled: StateFlow<Boolean> = _appListFoldersEnabled.asStateFlow()
    /** 屏幕右侧滑动进度指示器。老师指定默认开。 */
    private val _appListScrollIndicatorEnabled = MutableStateFlow(true)
    val appListScrollIndicatorEnabled: StateFlow<Boolean> =
        _appListScrollIndicatorEnabled.asStateFlow()
    private val _musicTextSwitchAnimation = MutableStateFlow(MusicTextSwitchAnimations.DEFAULT_ID)
    val musicTextSwitchAnimation: StateFlow<String> = _musicTextSwitchAnimation.asStateFlow()
    private val _iconShadowEnabled = MutableStateFlow(true)
    val iconShadowEnabled: StateFlow<Boolean> = _iconShadowEnabled.asStateFlow()
    private val _appListReturnShrinkEnabled = MutableStateFlow(true)
    val appListReturnShrinkEnabled: StateFlow<Boolean> = _appListReturnShrinkEnabled.asStateFlow()
    // 表盘收缩效果默认开启；用户仍可在显示设置中独立关闭。
    private val _faceReturnShrinkEnabled = MutableStateFlow(true)
    val faceReturnShrinkEnabled: StateFlow<Boolean> = _faceReturnShrinkEnabled.asStateFlow()
    // 还原 watchOS 三段式之前的老转场（表盘 2.5 倍冲脸 / 列表缩点退场）。
    private val _legacyAppListTransitionEnabled = MutableStateFlow(false)
    val legacyAppListTransitionEnabled: StateFlow<Boolean> = _legacyAppListTransitionEnabled.asStateFlow()
    // 从应用返回应用列表时重播逐行上浮入场。默认关；仅在关闭
    // 「应用列表收缩动画」时在设置里展示（老师指定的联动规则）。
    private val _appListReturnIconFloatEnabled = MutableStateFlow(false)
    val appListReturnIconFloatEnabled: StateFlow<Boolean> = _appListReturnIconFloatEnabled.asStateFlow()
    private val _reduceDynamicEffects = MutableStateFlow(false)
    val reduceDynamicEffects: StateFlow<Boolean> = _reduceDynamicEffects.asStateFlow()
    private val _screenShapeOverride = MutableStateFlow(FlueScreenShape.AUTO)
    val screenShapeOverride: StateFlow<FlueScreenShape> = _screenShapeOverride.asStateFlow()
    // 甩尾入场默认**开**：图标从上排到下排依次向上浮出，这是老师点名要的观感。
    // 以前默认关，机制虽然做好了却没人看得见（老师问「你那个做了吗」）。
    private val _appListStaggerEnabled = MutableStateFlow(true)
    val appListStaggerEnabled: StateFlow<Boolean> = _appListStaggerEnabled.asStateFlow()
    // 默认档是「柔和」（Gentle），所有 UI 风格共用同一条曲线 —— 以前这里是「跟随风格」，
    // 初值要看当前风格，那一档已按老师要求删掉。
    private val _motionProfiles = MutableStateFlow(FlueMotionProfiles.builtIns)
    val motionProfiles: StateFlow<List<FlueMotionProfile>> = _motionProfiles.asStateFlow()
    private val _activeMotionProfile = MutableStateFlow(FlueMotionProfiles.Gentle)
    val activeMotionProfile: StateFlow<FlueMotionProfile> = _activeMotionProfile.asStateFlow()
    // 老师指定：步数显示默认关。
    private val _showStepCount = MutableStateFlow(false)
    val showStepCount: StateFlow<Boolean> = _showStepCount.asStateFlow()

    private val _splashIcon = MutableStateFlow(true)
    val splashIcon: StateFlow<Boolean> = _splashIcon.asStateFlow()

    private val _showNotification = MutableStateFlow(true)
    val showNotification: StateFlow<Boolean> = _showNotification.asStateFlow()
    private val _showOngoingNotifications = MutableStateFlow(false)
    val showOngoingNotifications: StateFlow<Boolean> = _showOngoingNotifications.asStateFlow()
    private val _rotaryHapticsEnabled = MutableStateFlow(true)
    val rotaryHapticsEnabled: StateFlow<Boolean> = _rotaryHapticsEnabled.asStateFlow()
    private val _showWidgetPage = MutableStateFlow(true)
    val showWidgetPage: StateFlow<Boolean> = _showWidgetPage.asStateFlow()
    private val _showControlCenter = MutableStateFlow(true)
    val showControlCenter: StateFlow<Boolean> = _showControlCenter.asStateFlow()
    private val _showMusicControls = MutableStateFlow(true)
    val showMusicControls: StateFlow<Boolean> = _showMusicControls.asStateFlow()
    private val _showMediaCustomActions = MutableStateFlow(true)
    val showMediaCustomActions: StateFlow<Boolean> = _showMediaCustomActions.asStateFlow()
    private val _swapMusicNotificationComponents = MutableStateFlow(false)
    val swapMusicNotificationComponents: StateFlow<Boolean> = _swapMusicNotificationComponents.asStateFlow()
    private val _doubleTapLockScreenEnabled = MutableStateFlow(false)
    val doubleTapLockScreenEnabled: StateFlow<Boolean> = _doubleTapLockScreenEnabled.asStateFlow()

    /**
     * 表盘不响应返回键（米兔手表适配）。
     *
     * 米兔手表把「从左往右滑」这个手势直接映射成系统返回键。而这正是 Flue 拉开负一屏
     * 的手势，于是每次划开负一屏，刚拉出来就被那一记返回给关掉了 —— 手势和按键是同一
     * 个物理动作，在应用层分不开。唯一能做的是让表盘侧忽略这记返回。
     *
     * 默认关：只有米兔这类会做手势→按键映射的机型需要，其他机型开了反而让返回键失灵。
     */
    private val _faceIgnoresBackKey = MutableStateFlow(false)
    val faceIgnoresBackKey: StateFlow<Boolean> = _faceIgnoresBackKey.asStateFlow()
    private val _powerMenuButtonEnabled = MutableStateFlow(false)
    val powerMenuButtonEnabled: StateFlow<Boolean> = _powerMenuButtonEnabled.asStateFlow()
    private val _watchFaceChargingPowerText = MutableStateFlow(true)
    val watchFaceChargingPowerText: StateFlow<Boolean> = _watchFaceChargingPowerText.asStateFlow()
    private val _watchFaceTextEntryAnimation = MutableStateFlow(true)
    val watchFaceTextEntryAnimation: StateFlow<Boolean> = _watchFaceTextEntryAnimation.asStateFlow()
    private val _watchFaceTextExitAnimation = MutableStateFlow(true)
    val watchFaceTextExitAnimation: StateFlow<Boolean> = _watchFaceTextExitAnimation.asStateFlow()
    private val _watchFaceStatusIndicators = MutableStateFlow(true)
    val watchFaceStatusIndicators: StateFlow<Boolean> = _watchFaceStatusIndicators.asStateFlow()
    // 表盘底部渐隐遮罩默认关闭；用户可在“更多表盘选项”里手动开启。
    private val _watchFaceBottomFadeEnabled = MutableStateFlow(false)
    val watchFaceBottomFadeEnabled: StateFlow<Boolean> = _watchFaceBottomFadeEnabled.asStateFlow()
    /** App 级画布默认。没配过每表盘覆盖的表盘都用它。 */
    private val _watchfaceCanvasDefault = MutableStateFlow(WatchCanvasSettings.GLOBAL_DEFAULT)
    val watchfaceCanvasDefault: StateFlow<WatchCanvasSettings> = _watchfaceCanvasDefault.asStateFlow()

    /** 每表盘覆盖表。**键存在即代表该表盘不跟随全局默认**。 */
    private val _watchfaceCanvasOverrides = MutableStateFlow<Map<String, WatchCanvasSettings>>(emptyMap())
    val watchfaceCanvasOverrides: StateFlow<Map<String, WatchCanvasSettings>> =
        _watchfaceCanvasOverrides.asStateFlow()
    /** 重力景深默认开启（老师要求）：装了就能看到晃动效果，不用先进设置翻开关。 */
    private val _watchfaceGravityParallaxEnabled = MutableStateFlow(true)
    val watchfaceGravityParallaxEnabled: StateFlow<Boolean> = _watchfaceGravityParallaxEnabled.asStateFlow()
    /** 底部模糊背景层默认关闭；已有设置继续按用户选择读取。 */
    private val _watchfaceGravityBackdropBlurEnabled = MutableStateFlow(false)
    val watchfaceGravityBackdropBlurEnabled: StateFlow<Boolean> =
        _watchfaceGravityBackdropBlurEnabled.asStateFlow()
    // 表盘日期语言：system/zh/en/ja 四档，默认跟随系统。
    private val _watchfaceDateLocale = MutableStateFlow(WatchDateLocale.PREFERENCE_SYSTEM)
    val watchfaceDateLocale: StateFlow<String> = _watchfaceDateLocale.asStateFlow()
    private val _notificationAccessGranted = MutableStateFlow(false)
    val notificationAccessGranted: StateFlow<Boolean> = _notificationAccessGranted.asStateFlow()
    private val _expandedNotificationGroups = MutableStateFlow<Set<String>>(emptySet())
    private val _pendingDismissedNotificationKeys = MutableStateFlow<Set<String>>(emptySet())
    private val _sideScreenPreviewGroups = MutableStateFlow<List<NotificationGroupUi>>(emptyList())
    val sideScreenPreviewGroups: StateFlow<List<NotificationGroupUi>> = _sideScreenPreviewGroups.asStateFlow()
    private val _notificationGroups = MutableStateFlow<List<NotificationGroupUi>>(emptyList())
    val notificationGroups: StateFlow<List<NotificationGroupUi>> = _notificationGroups.asStateFlow()
    private val _revealedNotificationTarget = MutableStateFlow<NotificationRevealTarget?>(null)
    val revealedNotificationTarget: StateFlow<NotificationRevealTarget?> = _revealedNotificationTarget.asStateFlow()
    private val _backPressRequests = MutableStateFlow(0L)
    val backPressRequests: StateFlow<Long> = _backPressRequests.asStateFlow()
    private val _homePressRequests = MutableStateFlow(0L)
    val homePressRequests: StateFlow<Long> = _homePressRequests.asStateFlow()

    private val _hiddenApps = MutableStateFlow<Set<String>>(emptySet())
    val hiddenApps: StateFlow<Set<String>> = _hiddenApps.asStateFlow()

    private val _availableIconPacks = MutableStateFlow<List<IconPackDescriptor>>(emptyList())
    val availableIconPacks: StateFlow<List<IconPackDescriptor>> = _availableIconPacks.asStateFlow()

    private val _selectedIconPackPackage = MutableStateFlow<String?>(null)
    val selectedIconPackPackage: StateFlow<String?> = _selectedIconPackPackage.asStateFlow()

    private val _availableWatchFaces = MutableStateFlow(
        cachedWatchFaces?.let { WatchFaceDescriptors.localizeBuiltIns(application, it) }
            ?: WatchFaceDescriptors.builtInDescriptors(application),
    )
    val availableWatchFaces: StateFlow<List<WatchFaceDescriptor>> = _availableWatchFaces.asStateFlow()

    private val _selectedWatchFaceId = MutableStateFlow(BUILT_IN_WATCHFACE_ID)
    val selectedWatchFaceId: StateFlow<String> = _selectedWatchFaceId.asStateFlow()

    private val _selectedWatchFace = MutableStateFlow(
        WatchFaceDescriptors.builtInDescriptor(application, BUILT_IN_WATCHFACE_ID),
    )
    val selectedWatchFace: StateFlow<WatchFaceDescriptor> = _selectedWatchFace.asStateFlow()

    private val _watchFaceSelectionReady = MutableStateFlow(false)
    val watchFaceSelectionReady: StateFlow<Boolean> = _watchFaceSelectionReady.asStateFlow()

    /**
     * 表盘重载令牌——**进程级共享**，不是 per-ViewModel。
     *
     * ViewModel 按 Activity 作用域各存一份，编辑器（WatchEditorActivity）里
     * bump 自己那份，桌面（LauncherActivity）那份纹丝不动，
     * WatchmakerWatchFaceView.bind() 的
     * `descriptorKey 相同 && refreshToken 相同` 短路就永远命中：改完保存返回
     * 看到的还是旧渲染，必须切到别的表盘再切回来（换了 descriptorKey 才绕过
     * 短路）才显示新效果——老师报的就是这个。挂到 companion 上让同进程内
     * 所有实例共享同一个流。
     */
    private val _watchFaceRefreshToken = sharedWatchFaceRefreshToken
    val watchFaceRefreshToken: StateFlow<Int> = _watchFaceRefreshToken.asStateFlow()

    private val _watchFaceLastError = MutableStateFlow<String?>(null)
    val watchFaceLastError: StateFlow<String?> = _watchFaceLastError.asStateFlow()

    private val _builtInPhotoPath = MutableStateFlow<String?>(null)
    val builtInPhotoPath: StateFlow<String?> = _builtInPhotoPath.asStateFlow()

    private val _builtInVideoPath = MutableStateFlow<String?>(null)
    val builtInVideoPath: StateFlow<String?> = _builtInVideoPath.asStateFlow()

    private val _builtInPhotoClockPosition = MutableStateFlow(WatchClockPosition.CENTER)
    val builtInPhotoClockPosition: StateFlow<WatchClockPosition> = _builtInPhotoClockPosition.asStateFlow()

    private val _builtInVideoClockPosition = MutableStateFlow(WatchClockPosition.CENTER)
    val builtInVideoClockPosition: StateFlow<WatchClockPosition> = _builtInVideoClockPosition.asStateFlow()

    private val _builtInPhotoClockSize = MutableStateFlow(64)
    val builtInPhotoClockSize: StateFlow<Int> = _builtInPhotoClockSize.asStateFlow()

    private val _builtInVideoClockSize = MutableStateFlow(64)
    val builtInVideoClockSize: StateFlow<Int> = _builtInVideoClockSize.asStateFlow()

    private val _builtInPhotoClockBold = MutableStateFlow(false)
    val builtInPhotoClockBold: StateFlow<Boolean> = _builtInPhotoClockBold.asStateFlow()

    private val _builtInVideoClockBold = MutableStateFlow(false)
    val builtInVideoClockBold: StateFlow<Boolean> = _builtInVideoClockBold.asStateFlow()

    private val _builtInVideoFillScreen = MutableStateFlow(true)
    val builtInVideoFillScreen: StateFlow<Boolean> = _builtInVideoFillScreen.asStateFlow()
    private val _builtInVideoClockColorMode = MutableStateFlow(WatchClockColorMode.AUTO)
    val builtInVideoClockColorMode: StateFlow<WatchClockColorMode> = _builtInVideoClockColorMode.asStateFlow()

    private val _builtInWatchFaceFontPath = MutableStateFlow<String?>(null)
    val builtInWatchFaceFontPath: StateFlow<String?> = _builtInWatchFaceFontPath.asStateFlow()

    private val _builtInPhotoClockStyle = MutableStateFlow(WatchFaceClockStyle.DIGITAL)
    val builtInPhotoClockStyle: StateFlow<WatchFaceClockStyle> = _builtInPhotoClockStyle.asStateFlow()

    private val _builtInVideoClockStyle = MutableStateFlow(WatchFaceClockStyle.DIGITAL)
    val builtInVideoClockStyle: StateFlow<WatchFaceClockStyle> = _builtInVideoClockStyle.asStateFlow()

    private val _builtInPhotoMd3eShape = MutableStateFlow(WatchFaceMd3eShape.COOKIE)
    val builtInPhotoMd3eShape: StateFlow<WatchFaceMd3eShape> = _builtInPhotoMd3eShape.asStateFlow()

    private val _builtInVideoMd3eShape = MutableStateFlow(WatchFaceMd3eShape.COOKIE)
    val builtInVideoMd3eShape: StateFlow<WatchFaceMd3eShape> = _builtInVideoMd3eShape.asStateFlow()

    private val _builtInPhotoUseThemeTextColor = MutableStateFlow(true)
    val builtInPhotoUseThemeTextColor: StateFlow<Boolean> = _builtInPhotoUseThemeTextColor.asStateFlow()

    private val _builtInVideoUseThemeTextColor = MutableStateFlow(true)
    val builtInVideoUseThemeTextColor: StateFlow<Boolean> = _builtInVideoUseThemeTextColor.asStateFlow()

    private val _builtInPhotoTextColorArgb = MutableStateFlow(0xFFFFFFFF.toInt())
    val builtInPhotoTextColorArgb: StateFlow<Int> = _builtInPhotoTextColorArgb.asStateFlow()

    private val _builtInVideoTextColorArgb = MutableStateFlow(0xFFFFFFFF.toInt())
    val builtInVideoTextColorArgb: StateFlow<Int> = _builtInVideoTextColorArgb.asStateFlow()

    private val _builtInPhotoMd3eAutoColors = MutableStateFlow(true)
    val builtInPhotoMd3eAutoColors: StateFlow<Boolean> = _builtInPhotoMd3eAutoColors.asStateFlow()

    private val _builtInVideoMd3eAutoColors = MutableStateFlow(true)
    val builtInVideoMd3eAutoColors: StateFlow<Boolean> = _builtInVideoMd3eAutoColors.asStateFlow()

    private val _builtInPhotoMd3eTextColorArgb = MutableStateFlow(0xFF202938.toInt())
    val builtInPhotoMd3eTextColorArgb: StateFlow<Int> = _builtInPhotoMd3eTextColorArgb.asStateFlow()

    private val _builtInVideoMd3eTextColorArgb = MutableStateFlow(0xFF202938.toInt())
    val builtInVideoMd3eTextColorArgb: StateFlow<Int> = _builtInVideoMd3eTextColorArgb.asStateFlow()

    private val _builtInPhotoMd3eFaceColorArgb = MutableStateFlow(0xFFEAF1FF.toInt())
    val builtInPhotoMd3eFaceColorArgb: StateFlow<Int> = _builtInPhotoMd3eFaceColorArgb.asStateFlow()

    private val _builtInVideoMd3eFaceColorArgb = MutableStateFlow(0xFFEAF1FF.toInt())
    val builtInVideoMd3eFaceColorArgb: StateFlow<Int> = _builtInVideoMd3eFaceColorArgb.asStateFlow()

    private val _builtInPhotoMd3eHourColorArgb = MutableStateFlow(0xFF334155.toInt())
    val builtInPhotoMd3eHourColorArgb: StateFlow<Int> = _builtInPhotoMd3eHourColorArgb.asStateFlow()

    private val _builtInVideoMd3eHourColorArgb = MutableStateFlow(0xFF334155.toInt())
    val builtInVideoMd3eHourColorArgb: StateFlow<Int> = _builtInVideoMd3eHourColorArgb.asStateFlow()

    private val _builtInPhotoMd3eMinuteColorArgb = MutableStateFlow(0xFF5F84B6.toInt())
    val builtInPhotoMd3eMinuteColorArgb: StateFlow<Int> = _builtInPhotoMd3eMinuteColorArgb.asStateFlow()

    private val _builtInVideoMd3eMinuteColorArgb = MutableStateFlow(0xFF5F84B6.toInt())
    val builtInVideoMd3eMinuteColorArgb: StateFlow<Int> = _builtInVideoMd3eMinuteColorArgb.asStateFlow()

    private val _builtInPhotoMd3eSecondColorArgb = MutableStateFlow(0xFF806EA4.toInt())
    val builtInPhotoMd3eSecondColorArgb: StateFlow<Int> = _builtInPhotoMd3eSecondColorArgb.asStateFlow()

    private val _builtInVideoMd3eSecondColorArgb = MutableStateFlow(0xFF806EA4.toInt())
    val builtInVideoMd3eSecondColorArgb: StateFlow<Int> = _builtInVideoMd3eSecondColorArgb.asStateFlow()

    private val _builtInPhotoShowSeconds = MutableStateFlow(false)
    val builtInPhotoShowSeconds: StateFlow<Boolean> = _builtInPhotoShowSeconds.asStateFlow()

    private val _builtInVideoShowSeconds = MutableStateFlow(false)
    val builtInVideoShowSeconds: StateFlow<Boolean> = _builtInVideoShowSeconds.asStateFlow()

    private val _builtInPhotoCustomText = MutableStateFlow("")
    val builtInPhotoCustomText: StateFlow<String> = _builtInPhotoCustomText.asStateFlow()

    private val _builtInVideoCustomText = MutableStateFlow("")
    val builtInVideoCustomText: StateFlow<String> = _builtInVideoCustomText.asStateFlow()

    private val _builtInManagerThumbnails = MutableStateFlow(true)
    val builtInManagerThumbnails: StateFlow<Boolean> = _builtInManagerThumbnails.asStateFlow()
    private val _hideFromRecents = MutableStateFlow(true)
    val hideFromRecents: StateFlow<Boolean> = _hideFromRecents.asStateFlow()
    private val _watchStoreSegmentedDownload = MutableStateFlow(true)
    val watchStoreSegmentedDownload: StateFlow<Boolean> = _watchStoreSegmentedDownload.asStateFlow()
    // 启动语言真源是 SharedPreferences；DataStore 只保存镜像，不能回灌旧值。
    private val _appLanguage = MutableStateFlow(
        com.flue.launcher.i18n.FlueLocaleManager.getSavedLanguage(application)
    )
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()
    private val _glassHighlightEnabled = MutableStateFlow(true)
    val glassHighlightEnabled: StateFlow<Boolean> = _glassHighlightEnabled.asStateFlow()
    private val _widgetGlassHighlightEnabled = MutableStateFlow(true)
    val widgetGlassHighlightEnabled: StateFlow<Boolean> = _widgetGlassHighlightEnabled.asStateFlow()

    private val _appOpenOrigin = MutableStateFlow(Offset(0.5f, 0.5f))
    val appOpenOrigin: StateFlow<Offset> = _appOpenOrigin.asStateFlow()

    private val _currentApp = MutableStateFlow<AppInfo?>(null)
    val currentApp: StateFlow<AppInfo?> = _currentApp.asStateFlow()
    private val _currentLaunchIcon = MutableStateFlow<ImageBitmap?>(null)
    val currentLaunchIcon: StateFlow<ImageBitmap?> = _currentLaunchIcon.asStateFlow()
    private val _launchSourceState = MutableStateFlow(ScreenState.Apps)
    val launchSourceState: StateFlow<ScreenState> = _launchSourceState.asStateFlow()

    private val externalLaunchTracker = ExternalLaunchSessionTracker(SystemClock::elapsedRealtime)
    private val _externalLaunchState =
        MutableStateFlow<ExternalLaunchState>(ExternalLaunchState.Idle)
    val externalLaunchState: StateFlow<ExternalLaunchState> = _externalLaunchState.asStateFlow()
    private var launchJob: Job? = null
    private var returnResolutionJob: Job? = null
    private var watchFacePrefsHydrated = false
    private var watchFaceScanHydrated = false
    private var lastWatchFaceRefreshAt = 0L

    /** 最近一次全量表盘扫描的墙钟时刻；配合 [watchFaceDiskChangedSince] 做廉价变化探测。 */
    private var lastWatchFaceScanWallClockMs = 0L
    private var watchFaceRefreshJob: Job? = null
    private val pendingWriteJobs = ConcurrentHashMap<String, Job>()
    private val appOrderWriteCoordinator = AppOrderWriteCoordinator()
    private val pendingAppOrder =
        AtomicReference<AppOrderWriteCoordinator.Ticket?>(null)
    private var appOrderWriteJob: Job? = null
    private var pendingNotificationClearJob: Job? = null
    private var refreshIconsJob: Job? = null
    init {
        viewModelScope.launch {
            FlueAccessibilityService.lastExternalForegroundEvidence.collect { evidence ->
                evidence ?: return@collect
                externalLaunchTracker.recordEvidence(evidence)
                publishExternalLaunchState()
            }
        }
        refreshNotificationAccess()
        viewModelScope.launch {
            combine(
                WLauncherNotificationListener.notifications,
                _expandedNotificationGroups,
                _pendingDismissedNotificationKeys,
                _showOngoingNotifications
            ) { notifications, expandedPackages, pendingDismissedKeys, showOngoing ->
                val visibleNotifications = notifications.filter { shouldShowNotification(it, showOngoing) }
                val filteredNotifications = visibleNotifications.filterNot { it.key in pendingDismissedKeys }
                val activeNotificationKeys = notifications.asSequence().map { it.key }.toSet()
                Triple(
                    buildNotificationGroups(filteredNotifications, emptySet()),
                    buildNotificationGroups(filteredNotifications, expandedPackages),
                    pendingDismissedKeys.intersect(activeNotificationKeys)
                )
            }.collect { (previewGroups, groups, remainingPendingDismissedKeys) ->
                updateStableNotificationGroups(previewGroups, groups)
                if (_pendingDismissedNotificationKeys.value != remainingPendingDismissedKeys) {
                    _pendingDismissedNotificationKeys.value = remainingPendingDismissedKeys
                }
                _expandedNotificationGroups.value =
                    _expandedNotificationGroups.value.intersect(groups.map(NotificationGroupUi::packageName).toSet())
                val currentRevealTarget = _revealedNotificationTarget.value
                val validRevealTarget = when (currentRevealTarget) {
                    is NotificationRevealTarget.Group -> groups.any { it.packageName == currentRevealTarget.packageName }
                    is NotificationRevealTarget.Entry -> groups.any { group -> group.entries.any { it.key == currentRevealTarget.key } }
                    null -> true
                }
                if (!validRevealTarget) {
                    _revealedNotificationTarget.value = null
                }
            }
        }
        refreshIconPacks()
        viewModelScope.launch(Dispatchers.IO) {
            store.data.collect { prefs ->
                val loadedLayout = prefs[KEY_LAYOUT]?.let {
                    try {
                        LayoutMode.valueOf(it)
                    } catch (_: Exception) {
                        LayoutMode.Honeycomb
                    }
                } ?: LayoutMode.Honeycomb
                if (_layoutMode.value != loadedLayout) _layoutMode.value = loadedLayout

                // 默认深色（老师定）：手表多为 OLED，深色既省电又是三套风格的基准观感
                // （watchOS 纯黑、HyperOS 深灰、MD3 深色都是各自的"正脸"）。存过值的
                // 老用户不受影响 —— 只有**没存过**才落到这个默认。
                val loadedThemeMode = prefs[KEY_THEME_MODE]?.let {
                    try {
                        ThemeMode.valueOf(it)
                    } catch (_: Exception) {
                        ThemeMode.DARK
                    }
                } ?: ThemeMode.DARK
                if (_themeMode.value != loadedThemeMode) _themeMode.value = loadedThemeMode

                val loadedMotion = FlueTransitionMotion.fromPreference(prefs[KEY_TRANSITION_MOTION]?.toString())
                if (_transitionMotion.value != loadedMotion) _transitionMotion.value = loadedMotion

                val loadedUiStyle = uiStyleFromPreference(prefs[KEY_UI_STYLE])
                if (_uiStyle.value != loadedUiStyle) _uiStyle.value = loadedUiStyle

                val loadedSideScreenEnabled = prefs[KEY_SIDE_SCREEN_ENABLED] ?: true
                if (_sideScreenEnabled.value != loadedSideScreenEnabled) _sideScreenEnabled.value = loadedSideScreenEnabled

                val loadedShortcutRows = (prefs[KEY_SIDE_SCREEN_SHORTCUT_ROWS] ?: 2).coerceIn(1, 3)
                if (_sideScreenShortcutRows.value != loadedShortcutRows) {
                    _sideScreenShortcutRows.value = loadedShortcutRows
                }

                val loadedShortcutCols = (prefs[KEY_SIDE_SCREEN_SHORTCUT_COLS] ?: 4).coerceIn(2, 4)
                if (_sideScreenShortcutCols.value != loadedShortcutCols) {
                    _sideScreenShortcutCols.value = loadedShortcutCols
                }

                val loadedSideScreenShortcuts = parseSideScreenShortcuts(prefs[KEY_SIDE_SCREEN_SHORTCUTS])
                if (_sideScreenShortcuts.value != loadedSideScreenShortcuts) {
                    _sideScreenShortcuts.value = loadedSideScreenShortcuts
                }

                val loadedWidgets = parseSideScreenWidgets(prefs[KEY_SIDE_SCREEN_WIDGETS])
                if (_sideScreenWidgetSlots.value != loadedWidgets) {
                    _sideScreenWidgetSlots.value = loadedWidgets
                }

                val loadedBlur = prefs[KEY_BLUR] ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                if (_blurEnabled.value != loadedBlur) _blurEnabled.value = loadedBlur

                val loadedEdgeBlur = prefs[KEY_EDGE_BLUR] ?: false
                if (_edgeBlurEnabled.value != loadedEdgeBlur) _edgeBlurEnabled.value = loadedEdgeBlur

                val loadedLowRes = prefs[KEY_LOW_RES] ?: false
                if (_lowResIcons.value != loadedLowRes) {
                    _lowResIcons.value = loadedLowRes
                    // 首次加载而非用户切换：初始扫描还在延迟等待时只改参数，
                    // 避免冷启动连跑两轮全量扫描（图标包加载同理，见下文）。
                    appRepository.setIconRenderingOptionsDeferrable(
                        iconSize = if (loadedLowRes) 64 else 128
                    )
                }

                val loadedLegacyCircularIcons = true
                if (_legacyCircularIcons.value != loadedLegacyCircularIcons) {
                    _legacyCircularIcons.value = loadedLegacyCircularIcons
                    appRepository.setLegacyCircularIconsEnabled(loadedLegacyCircularIcons)
                }
                if (prefs[KEY_LEGACY_CIRCULAR_ICONS] != true) {
                    store.edit { it[KEY_LEGACY_CIRCULAR_ICONS] = true }
                }

                val loadedAnimationOverride = prefs[KEY_ANIMATION_OVERRIDE] ?: true
                if (_animationOverrideEnabled.value != loadedAnimationOverride) {
                    _animationOverrideEnabled.value = loadedAnimationOverride
                }

                val loadedSplashIcon = prefs[KEY_SPLASH_ICON] ?: true
                if (_splashIcon.value != loadedSplashIcon) _splashIcon.value = loadedSplashIcon

                val loadedSplashDelay = (prefs[KEY_SPLASH_DELAY] ?: 500).coerceIn(300, 1500)
                if (_splashDelay.value != loadedSplashDelay) _splashDelay.value = loadedSplashDelay
                val loadedDirectLaunchAppList = prefs[KEY_DIRECT_LAUNCH_APP_LIST] ?: false
                if (_directLaunchAppListEnabled.value != loadedDirectLaunchAppList) {
                    _directLaunchAppListEnabled.value = loadedDirectLaunchAppList
                }
                if (!_directLaunchAppListPreferenceLoaded.value) {
                    _directLaunchAppListPreferenceLoaded.value = true
                }

                val loadedOrder = prefs[KEY_APP_ORDER]
                    ?.takeIf { it.isNotEmpty() }
                    ?.split(",")
                    ?: emptyList()
                val pendingOrderTicket = pendingAppOrder.get()
                if (pendingOrderTicket != null) {
                    if (loadedOrder == pendingOrderTicket.order) {
                        pendingAppOrder.compareAndSet(pendingOrderTicket, null)
                    }
                } else if (_appOrder.value != loadedOrder) {
                    _appOrder.value = loadedOrder
                    appRepository.setCustomOrder(loadedOrder)
                }

                val devicePresetApplied = prefs[KEY_DEVICE_PRESET_APPLIED] ?: false
                val shouldApplyDevicePreset = !devicePresetApplied && isXiaomi17ProSeriesDevice()
                val hasSavedHoneycombCols = prefs[KEY_HONEYCOMB_COLS] != null
                val honeycombColsDefault = if (shouldApplyDevicePreset && !hasSavedHoneycombCols) 2 else 3

                val loadedHoneycombCols = (prefs[KEY_HONEYCOMB_COLS] ?: honeycombColsDefault).coerceIn(1, 5)
                if (_honeycombCols.value != loadedHoneycombCols) _honeycombCols.value = loadedHoneycombCols
                val loadedAppListScale = normalizeAppListScalePercent(prefs[KEY_APP_LIST_SCALE_PERCENT] ?: 100)
                if (_appListScalePercent.value != loadedAppListScale) {
                    _appListScalePercent.value = loadedAppListScale
                }

                val loadedTopBlur = (prefs[KEY_HONEYCOMB_TOP_BLUR] ?: 4).coerceIn(0, 48)
                if (_honeycombTopBlur.value != loadedTopBlur) _honeycombTopBlur.value = loadedTopBlur

                val loadedBottomBlur = (prefs[KEY_HONEYCOMB_BOTTOM_BLUR] ?: 4).coerceIn(0, 48)
                if (_honeycombBottomBlur.value != loadedBottomBlur) _honeycombBottomBlur.value = loadedBottomBlur

                val loadedEdgeBlurTenths = (prefs[KEY_HONEYCOMB_EDGE_BLUR_TENTHS]
                    ?: (((loadedTopBlur + loadedBottomBlur) / 2f) * 10f).roundToInt())
                    .let(::normalizeEdgeBlurTenths)
                val loadedEdgeBlurRadius = loadedEdgeBlurTenths / 10f
                if (_honeycombEdgeBlurRadius.value != loadedEdgeBlurRadius) {
                    _honeycombEdgeBlurRadius.value = loadedEdgeBlurRadius
                }

                val loadedTopFade = (prefs[KEY_HONEYCOMB_TOP_FADE] ?: 30).coerceIn(0, 160)
                if (_honeycombTopFade.value != loadedTopFade) _honeycombTopFade.value = loadedTopFade

                val loadedBottomFade = (prefs[KEY_HONEYCOMB_BOTTOM_FADE] ?: 30).coerceIn(0, 160)
                if (_honeycombBottomFade.value != loadedBottomFade) _honeycombBottomFade.value = loadedBottomFade

                // ---- 布局档案 ----------------------------------------------
                // 先读档案库；读不到（首次升级上来）就从旧的六个扁平偏好迁一次。
                // 扁平键此后**只读不写**，保留一个版本周期 —— 万一降级回旧版，
                // 旧版仍能读到最后一次旧值，不至于一片默认。
                val hasSavedLeftSafeInset = prefs[KEY_APP_LIST_LEFT_SAFE_INSET_PERCENT] != null
                val flatLeftSafeInsetPercent = prefs[KEY_APP_LIST_LEFT_SAFE_INSET_PERCENT]
                    ?: if (
                        shouldApplyDevicePreset &&
                        !hasSavedLeftSafeInset &&
                        isXiaomi17ProSeriesDevice()
                    ) {
                        20
                    } else {
                        null
                    }
                val storedLayoutProfiles = prefs[KEY_LAYOUT_PROFILES]?.let { payload ->
                    decodeLayoutProfileLibraryCached(payload)
                }
                val loadedLayoutProfiles = FlueLayoutProfiles.withMissingBuiltIns(
                    storedLayoutProfiles ?: migratedLayoutProfiles(
                        fisheyeEnabled = prefs[KEY_APP_LIST_FISHEYE],
                        topRowExtraFisheyeEnabled = prefs[KEY_MATERIAL_HONEYCOMB_TOP_FISHEYE],
                        edgeSpacingCompressionEnabled =
                            prefs[KEY_APP_LIST_EDGE_SPACING_COMPRESSION],
                        fisheyeRangeRows = prefs[KEY_APP_LIST_FISHEYE_RANGE_ROWS],
                        fisheyeStrengthPercent = prefs[KEY_APP_LIST_FISHEYE_STRENGTH_PERCENT],
                        leftSafeInsetPercent =
                            flatLeftSafeInsetPercent?.let(::normalizeLeftSafeInsetPercent),
                        narrowColumns = loadedHoneycombCols,
                        appListScalePercent = loadedAppListScale,
                    ),
                )
                if (_layoutProfiles.value != loadedLayoutProfiles) {
                    _layoutProfiles.value = loadedLayoutProfiles
                }
                activeLayoutProfileIds =
                    decodeActiveLayoutProfileIds(prefs[KEY_ACTIVE_LAYOUT_PROFILE_IDS])
                applyActiveLayoutProfile()
                if (storedLayoutProfiles == null) {
                    // 迁移结果立刻落盘，否则每次冷启动都要重算一遍迁移。
                    persistLayoutProfiles(loadedLayoutProfiles)
                }

                val loadedAppListBackground =
                    AppListBackgroundJson.decodeOrDefault(prefs[KEY_APP_LIST_BACKGROUND])
                if (_appListBackground.value != loadedAppListBackground) {
                    _appListBackground.value = loadedAppListBackground
                }

                val loadedGlobalUiScale = normalizeGlobalUiScalePercent(prefs[KEY_GLOBAL_UI_SCALE_PERCENT] ?: 100)
                if (_globalUiScalePercent.value != loadedGlobalUiScale) {
                    _globalUiScalePercent.value = loadedGlobalUiScale
                }

                val loadedOverlayMaskCornerRadius = normalizeOverlayMaskCornerRadiusDp(
                    prefs[KEY_OVERLAY_MASK_CORNER_RADIUS_DP] ?: DEFAULT_OVERLAY_MASK_CORNER_RADIUS_DP,
                )
                if (_overlayMaskCornerRadiusDp.value != loadedOverlayMaskCornerRadius) {
                    _overlayMaskCornerRadiusDp.value = loadedOverlayMaskCornerRadius
                }

                val loadedAppListWatchFaceColors = prefs[KEY_APP_LIST_WATCHFACE_COLORS] ?: false
                if (_appListWatchFaceColors.value != loadedAppListWatchFaceColors) {
                    _appListWatchFaceColors.value = loadedAppListWatchFaceColors
                }

                val loadedAppListRowBorder = prefs[KEY_APP_LIST_ROW_BORDER] ?: false
                if (_appListRowBorderEnabled.value != loadedAppListRowBorder) {
                    _appListRowBorderEnabled.value = loadedAppListRowBorder
                }

                // 缺键按 true 读：这块底板在开关存在之前就一直画着，缺省值必须复现旧行为。
                val loadedAppListGridIconPlate = prefs[KEY_APP_LIST_GRID_ICON_PLATE] ?: true
                if (_appListGridIconPlateEnabled.value != loadedAppListGridIconPlate) {
                    _appListGridIconPlateEnabled.value = loadedAppListGridIconPlate
                }

                // 合成文件夹默认**开**（老师指定）。老用户库里已经写过这个键的照旧读自己的值，
                // 只有从没碰过这项的（含全新安装）才吃到新默认。
                val loadedAppListFoldersEnabled = prefs[KEY_APP_LIST_FOLDERS_ENABLED] ?: true
                if (_appListFoldersEnabled.value != loadedAppListFoldersEnabled) {
                    _appListFoldersEnabled.value = loadedAppListFoldersEnabled
                }

                val loadedAppListScrollIndicator = prefs[KEY_APP_LIST_SCROLL_INDICATOR] ?: true
                if (_appListScrollIndicatorEnabled.value != loadedAppListScrollIndicator) {
                    _appListScrollIndicatorEnabled.value = loadedAppListScrollIndicator
                }

                // One-way migration: old builds may have persisted this key,
                // but fast-flow no longer has a runtime state or UI surface.
                if (prefs[KEY_REMOVED_FAST_FLOW_ANIMATION] != null) {
                    persist { store.edit { it.remove(KEY_REMOVED_FAST_FLOW_ANIMATION) } }
                }

                val loadedMusicTextSwitchAnimation = MusicTextSwitchAnimations.normalizeId(
                    prefs[KEY_MUSIC_TEXT_SWITCH_ANIMATION]
                )
                if (_musicTextSwitchAnimation.value != loadedMusicTextSwitchAnimation) {
                    _musicTextSwitchAnimation.value = loadedMusicTextSwitchAnimation
                }

                // 同上，双色图标已经整体下架，旧库里残留的开关值一并清掉。
                if (prefs[KEY_REMOVED_TWO_TONE_ICONS] != null) {
                    persist { store.edit { it.remove(KEY_REMOVED_TWO_TONE_ICONS) } }
                }

                val loadedIconShadow = prefs[KEY_ICON_SHADOW] ?: true
                if (_iconShadowEnabled.value != loadedIconShadow) {
                    _iconShadowEnabled.value = loadedIconShadow
                }

                val loadedAppListReturnShrink = prefs[KEY_APP_LIST_RETURN_SHRINK] ?: true
                if (_appListReturnShrinkEnabled.value != loadedAppListReturnShrink) {
                    _appListReturnShrinkEnabled.value = loadedAppListReturnShrink
                }

                val loadedLegacyAppListTransition = prefs[KEY_LEGACY_APP_LIST_TRANSITION] ?: false
                if (_legacyAppListTransitionEnabled.value != loadedLegacyAppListTransition) {
                    _legacyAppListTransitionEnabled.value = loadedLegacyAppListTransition
                }

                val loadedAppListReturnIconFloat = prefs[KEY_APP_LIST_RETURN_ICON_FLOAT] ?: false
                if (_appListReturnIconFloatEnabled.value != loadedAppListReturnIconFloat) {
                    _appListReturnIconFloatEnabled.value = loadedAppListReturnIconFloat
                }

                val loadedFaceReturnShrink = prefs[KEY_FACE_RETURN_SHRINK] ?: true
                if (_faceReturnShrinkEnabled.value != loadedFaceReturnShrink) {
                    _faceReturnShrinkEnabled.value = loadedFaceReturnShrink
                }

                val loadedReduceDynamic = prefs[KEY_REDUCE_DYNAMIC_EFFECTS] ?: false
                if (_reduceDynamicEffects.value != loadedReduceDynamic) {
                    _reduceDynamicEffects.value = loadedReduceDynamic
                }

                val loadedScreenShape = prefs[KEY_SCREEN_SHAPE_OVERRIDE]?.let { raw ->
                    runCatching { FlueScreenShape.valueOf(raw) }.getOrNull()
                } ?: FlueScreenShape.AUTO
                if (_screenShapeOverride.value != loadedScreenShape) {
                    _screenShapeOverride.value = loadedScreenShape
                }

                val loadedStagger = prefs[KEY_APP_LIST_STAGGER] ?: true
                if (_appListStaggerEnabled.value != loadedStagger) {
                    _appListStaggerEnabled.value = loadedStagger
                }

                val customMotionProfiles = prefs[KEY_CUSTOM_MOTION_PROFILES]
                    ?.let { payload -> decodeMotionProfileLibraryCached(payload) }
                    .orEmpty()
                    .filterNot { FlueMotionProfiles.isBuiltIn(it.id) }
                val loadedMotionProfiles =
                    FlueMotionProfiles.builtIns + customMotionProfiles
                if (_motionProfiles.value != loadedMotionProfiles) {
                    _motionProfiles.value = loadedMotionProfiles
                }
                val loadedMotionProfile = FlueMotionProfiles.resolve(
                    id = prefs[KEY_MOTION_PROFILE_ID],
                    customProfiles = customMotionProfiles,
                )
                if (_activeMotionProfile.value != loadedMotionProfile) {
                    _activeMotionProfile.value = loadedMotionProfile
                }

                val loadedShowStepCount = prefs[KEY_SHOW_STEP_COUNT] ?: false
                if (_showStepCount.value != loadedShowStepCount) {
                    _showStepCount.value = loadedShowStepCount
                }

                val loadedWatchFaceChargingPowerText = prefs[KEY_WATCHFACE_CHARGING_POWER_TEXT] ?: true
                if (_watchFaceChargingPowerText.value != loadedWatchFaceChargingPowerText) {
                    _watchFaceChargingPowerText.value = loadedWatchFaceChargingPowerText
                }

                val loadedWatchFaceTextEntryAnimation = prefs[KEY_WATCHFACE_TEXT_ENTRY_ANIMATION] ?: true
                if (_watchFaceTextEntryAnimation.value != loadedWatchFaceTextEntryAnimation) {
                    _watchFaceTextEntryAnimation.value = loadedWatchFaceTextEntryAnimation
                }
                val loadedWatchFaceTextExitAnimation = prefs[KEY_WATCHFACE_TEXT_EXIT_ANIMATION] ?: true
                if (_watchFaceTextExitAnimation.value != loadedWatchFaceTextExitAnimation) {
                    _watchFaceTextExitAnimation.value = loadedWatchFaceTextExitAnimation
                }

                val loadedWatchFaceStatusIndicators = prefs[KEY_WATCHFACE_STATUS_INDICATORS] ?: true
                if (_watchFaceStatusIndicators.value != loadedWatchFaceStatusIndicators) {
                    _watchFaceStatusIndicators.value = loadedWatchFaceStatusIndicators
                }

                val loadedWatchFaceBottomFade = prefs[KEY_WATCHFACE_BOTTOM_FADE] ?: false
                if (_watchFaceBottomFadeEnabled.value != loadedWatchFaceBottomFade) {
                    _watchFaceBottomFadeEnabled.value = loadedWatchFaceBottomFade
                }

                // 画布默认优先级：新键 → 存量 force_fill 开关（用户**显式**动过才算）
                // → 出厂默认（铺满）。中间那一层保住升级用户自己的选择；
                // 从没配置过的（含全新安装）才吃出厂值。
                // 不在这里回写新键 —— 读路径每次都能推导出同一个值，回写只会让
                // 一次纯读取变成一次写盘。
                val loadedCanvasDefault = prefs[KEY_WATCHFACE_CANVAS_DEFAULT]
                    ?.let(WatchCanvasSettings::decode)
                    ?: prefs[KEY_WATCHMAKER_FORCE_FILL]?.let(WatchCanvasSettings::fromForceFill)
                    ?: WatchCanvasSettings.GLOBAL_DEFAULT
                if (_watchfaceCanvasDefault.value != loadedCanvasDefault) {
                    _watchfaceCanvasDefault.value = loadedCanvasDefault
                }

                val loadedCanvasOverrides =
                    decodeWatchfaceCanvasOverrides(prefs[KEY_WATCHFACE_CANVAS_OVERRIDES])
                if (_watchfaceCanvasOverrides.value != loadedCanvasOverrides) {
                    _watchfaceCanvasOverrides.value = loadedCanvasOverrides
                }
                val loadedGravityParallax = prefs[KEY_WATCHFACE_GRAVITY_PARALLAX] ?: true
                if (_watchfaceGravityParallaxEnabled.value != loadedGravityParallax) {
                    _watchfaceGravityParallaxEnabled.value = loadedGravityParallax
                }
                val loadedGravityBackdropBlur =
                    prefs[KEY_WATCHFACE_GRAVITY_BACKDROP_BLUR] ?: false
                if (_watchfaceGravityBackdropBlurEnabled.value != loadedGravityBackdropBlur) {
                    _watchfaceGravityBackdropBlurEnabled.value = loadedGravityBackdropBlur
                }

                val loadedWatchfaceDateLocale =
                    prefs[KEY_WATCHFACE_DATE_LOCALE] ?: WatchDateLocale.PREFERENCE_SYSTEM
                if (_watchfaceDateLocale.value != loadedWatchfaceDateLocale) {
                    _watchfaceDateLocale.value = loadedWatchfaceDateLocale
                }
                // 渲染热路径读的是进程级 override，偏好每次落地都同步一次。
                WatchDateLocale.override = WatchDateLocale.fromPreference(loadedWatchfaceDateLocale)

                val notificationSettingMigrated = prefs[KEY_NOTIFICATION_SETTING_MIGRATED] ?: false
                val loadedShowNotification = if (notificationSettingMigrated) {
                    prefs[KEY_SHOW_NOTIFICATION] ?: true
                } else {
                    true
                }
                if (_showNotification.value != loadedShowNotification) _showNotification.value = loadedShowNotification
                if (!notificationSettingMigrated) {
                    store.edit {
                        it[KEY_SHOW_NOTIFICATION] = true
                        it[KEY_NOTIFICATION_SETTING_MIGRATED] = true
                    }
                }

                val loadedShowOngoingNotifications = prefs[KEY_SHOW_ONGOING_NOTIFICATIONS] ?: false
                if (_showOngoingNotifications.value != loadedShowOngoingNotifications) {
                    _showOngoingNotifications.value = loadedShowOngoingNotifications
                }

                val loadedRotaryHaptics = prefs[KEY_ROTARY_HAPTICS_ENABLED] ?: true
                if (_rotaryHapticsEnabled.value != loadedRotaryHaptics) {
                    _rotaryHapticsEnabled.value = loadedRotaryHaptics
                }

                val loadedShowWidgetPage = prefs[KEY_SHOW_WIDGET_PAGE] ?: true
                if (_showWidgetPage.value != loadedShowWidgetPage) {
                    _showWidgetPage.value = loadedShowWidgetPage
                }

                val loadedShowControlCenter = prefs[KEY_SHOW_CONTROL_CENTER] ?: true
                if (_showControlCenter.value != loadedShowControlCenter) {
                    _showControlCenter.value = loadedShowControlCenter
                }

                val loadedShowMusicControls = prefs[KEY_SHOW_MUSIC_CONTROLS] ?: true
                if (_showMusicControls.value != loadedShowMusicControls) {
                    _showMusicControls.value = loadedShowMusicControls
                }

                val loadedShowMediaCustomActions = prefs[KEY_SHOW_MEDIA_CUSTOM_ACTIONS] ?: true
                if (_showMediaCustomActions.value != loadedShowMediaCustomActions) {
                    _showMediaCustomActions.value = loadedShowMediaCustomActions
                }

                val loadedSwapMusicNotificationComponents = prefs[KEY_SWAP_MUSIC_NOTIFICATION_COMPONENTS] ?: false
                if (_swapMusicNotificationComponents.value != loadedSwapMusicNotificationComponents) {
                    _swapMusicNotificationComponents.value = loadedSwapMusicNotificationComponents
                }

                val loadedDoubleTapLockScreen = prefs[KEY_DOUBLE_TAP_LOCK_SCREEN] ?: false
                if (_doubleTapLockScreenEnabled.value != loadedDoubleTapLockScreen) {
                    _doubleTapLockScreenEnabled.value = loadedDoubleTapLockScreen
                }

                val loadedFaceIgnoresBackKey = prefs[KEY_FACE_IGNORES_BACK_KEY] ?: false
                if (_faceIgnoresBackKey.value != loadedFaceIgnoresBackKey) {
                    _faceIgnoresBackKey.value = loadedFaceIgnoresBackKey
                }

                val loadedPowerMenuButton = prefs[KEY_POWER_MENU_BUTTON] ?: false
                if (_powerMenuButtonEnabled.value != loadedPowerMenuButton) {
                    _powerMenuButtonEnabled.value = loadedPowerMenuButton
                }

                if (!devicePresetApplied) {
                    store.edit {
                        if (shouldApplyDevicePreset && !hasSavedHoneycombCols) {
                            it[KEY_HONEYCOMB_COLS] = 2
                        }
                        if (shouldApplyDevicePreset && !hasSavedLeftSafeInset) {
                            it[KEY_APP_LIST_LEFT_SAFE_INSET_PERCENT] = 20
                        }
                        it[KEY_DEVICE_PRESET_APPLIED] = true
                    }
                }

                val hidden = prefs[KEY_HIDDEN_APPS]
                    ?.split(",")
                    ?.filter(String::isNotBlank)
                    ?.toSet()
                    ?: emptySet()
                if (_hiddenApps.value != hidden) {
                    _hiddenApps.value = hidden
                    appRepository.setHiddenComponents(hidden)
                }

                val selectedPack = prefs[KEY_ICON_PACK_PACKAGE]
                if (_selectedIconPackPackage.value != selectedPack) {
                    _selectedIconPackPackage.value = selectedPack
                    // 首次加载走可延迟路径：若初始扫描还没开跑，把图标包并入
                    // 那一轮，不单独再扫一次（用户运行时切换仍走即时重扫）。
                    appRepository.setIconRenderingOptionsDeferrable(packageName = selectedPack)
                }

                val loadedWatchFaceId = prefs[KEY_SELECTED_WATCHFACE_ID] ?: BUILT_IN_WATCHFACE_ID
                if (_selectedWatchFaceId.value != loadedWatchFaceId) _selectedWatchFaceId.value = loadedWatchFaceId

                val loadedWatchFaceError = prefs[KEY_LAST_WATCHFACE_ERROR]
                if (_watchFaceLastError.value != loadedWatchFaceError) _watchFaceLastError.value = loadedWatchFaceError

                val loadedPhotoPath = prefs[KEY_BUILTIN_PHOTO_PATH]
                if (_builtInPhotoPath.value != loadedPhotoPath) _builtInPhotoPath.value = loadedPhotoPath

                val loadedVideoPath = prefs[KEY_BUILTIN_VIDEO_PATH]
                if (_builtInVideoPath.value != loadedVideoPath) _builtInVideoPath.value = loadedVideoPath

                val loadedPhotoClockPosition = prefs[KEY_PHOTO_CLOCK_POSITION]
                    ?.let(::parseClockPosition)
                    ?: WatchClockPosition.CENTER
                if (_builtInPhotoClockPosition.value != loadedPhotoClockPosition) {
                    _builtInPhotoClockPosition.value = loadedPhotoClockPosition
                }

                val loadedVideoClockPosition = prefs[KEY_VIDEO_CLOCK_POSITION]
                    ?.let(::parseClockPosition)
                    ?: WatchClockPosition.CENTER
                if (_builtInVideoClockPosition.value != loadedVideoClockPosition) {
                    _builtInVideoClockPosition.value = loadedVideoClockPosition
                }

                val loadedPhotoClockSize = (prefs[KEY_PHOTO_CLOCK_SIZE] ?: 64).coerceIn(28, 92)
                if (_builtInPhotoClockSize.value != loadedPhotoClockSize) _builtInPhotoClockSize.value = loadedPhotoClockSize

                val loadedVideoClockSize = (prefs[KEY_VIDEO_CLOCK_SIZE] ?: 64).coerceIn(28, 92)
                if (_builtInVideoClockSize.value != loadedVideoClockSize) _builtInVideoClockSize.value = loadedVideoClockSize

                val loadedPhotoClockBold = prefs[KEY_PHOTO_CLOCK_BOLD] ?: false
                if (_builtInPhotoClockBold.value != loadedPhotoClockBold) _builtInPhotoClockBold.value = loadedPhotoClockBold

                val loadedVideoClockBold = prefs[KEY_VIDEO_CLOCK_BOLD] ?: false
                if (_builtInVideoClockBold.value != loadedVideoClockBold) _builtInVideoClockBold.value = loadedVideoClockBold

                val loadedVideoFillScreen = prefs[KEY_VIDEO_FILL_SCREEN] ?: true
                if (_builtInVideoFillScreen.value != loadedVideoFillScreen) _builtInVideoFillScreen.value = loadedVideoFillScreen
                val loadedVideoClockColorMode = prefs[KEY_VIDEO_CLOCK_COLOR_MODE]
                    ?.let(::parseClockColorMode)
                    ?: WatchClockColorMode.AUTO
                if (_builtInVideoClockColorMode.value != loadedVideoClockColorMode) {
                    _builtInVideoClockColorMode.value = loadedVideoClockColorMode
                }

                val loadedWatchFaceFontPath = prefs[KEY_WATCHFACE_FONT_PATH]
                if (_builtInWatchFaceFontPath.value != loadedWatchFaceFontPath) {
                    _builtInWatchFaceFontPath.value = loadedWatchFaceFontPath
                }

                val loadedPhotoClockStyle = prefs[KEY_PHOTO_CLOCK_STYLE]
                    ?.let(::parseClockStyle)
                    ?: WatchFaceClockStyle.DIGITAL
                if (_builtInPhotoClockStyle.value != loadedPhotoClockStyle) {
                    _builtInPhotoClockStyle.value = loadedPhotoClockStyle
                }

                val loadedVideoClockStyle = prefs[KEY_VIDEO_CLOCK_STYLE]
                    ?.let(::parseClockStyle)
                    ?: WatchFaceClockStyle.DIGITAL
                if (_builtInVideoClockStyle.value != loadedVideoClockStyle) {
                    _builtInVideoClockStyle.value = loadedVideoClockStyle
                }

                val loadedPhotoMd3eShape = prefs[KEY_PHOTO_MD3E_SHAPE]
                    ?.let(::parseMd3eShape)
                    ?: WatchFaceMd3eShape.COOKIE
                if (_builtInPhotoMd3eShape.value != loadedPhotoMd3eShape) {
                    _builtInPhotoMd3eShape.value = loadedPhotoMd3eShape
                }

                val loadedVideoMd3eShape = prefs[KEY_VIDEO_MD3E_SHAPE]
                    ?.let(::parseMd3eShape)
                    ?: WatchFaceMd3eShape.COOKIE
                if (_builtInVideoMd3eShape.value != loadedVideoMd3eShape) {
                    _builtInVideoMd3eShape.value = loadedVideoMd3eShape
                }

                val loadedPhotoUseThemeTextColor = prefs[KEY_PHOTO_USE_THEME_TEXT_COLOR] ?: true
                if (_builtInPhotoUseThemeTextColor.value != loadedPhotoUseThemeTextColor) {
                    _builtInPhotoUseThemeTextColor.value = loadedPhotoUseThemeTextColor
                }

                val loadedVideoUseThemeTextColor = prefs[KEY_VIDEO_USE_THEME_TEXT_COLOR] ?: true
                if (_builtInVideoUseThemeTextColor.value != loadedVideoUseThemeTextColor) {
                    _builtInVideoUseThemeTextColor.value = loadedVideoUseThemeTextColor
                }

                val loadedPhotoTextColor = prefs[KEY_PHOTO_TEXT_COLOR] ?: 0xFFFFFFFF.toInt()
                if (_builtInPhotoTextColorArgb.value != loadedPhotoTextColor) {
                    _builtInPhotoTextColorArgb.value = loadedPhotoTextColor
                }

                val loadedVideoTextColor = prefs[KEY_VIDEO_TEXT_COLOR] ?: 0xFFFFFFFF.toInt()
                if (_builtInVideoTextColorArgb.value != loadedVideoTextColor) {
                    _builtInVideoTextColorArgb.value = loadedVideoTextColor
                }

                val loadedPhotoMd3eAutoColors = prefs[KEY_PHOTO_MD3E_AUTO_COLORS] ?: true
                if (_builtInPhotoMd3eAutoColors.value != loadedPhotoMd3eAutoColors) {
                    _builtInPhotoMd3eAutoColors.value = loadedPhotoMd3eAutoColors
                }

                val loadedVideoMd3eAutoColors = prefs[KEY_VIDEO_MD3E_AUTO_COLORS] ?: true
                if (_builtInVideoMd3eAutoColors.value != loadedVideoMd3eAutoColors) {
                    _builtInVideoMd3eAutoColors.value = loadedVideoMd3eAutoColors
                }

                val loadedPhotoMd3eTextColor = prefs[KEY_PHOTO_MD3E_TEXT_COLOR] ?: 0xFF202938.toInt()
                if (_builtInPhotoMd3eTextColorArgb.value != loadedPhotoMd3eTextColor) {
                    _builtInPhotoMd3eTextColorArgb.value = loadedPhotoMd3eTextColor
                }

                val loadedVideoMd3eTextColor = prefs[KEY_VIDEO_MD3E_TEXT_COLOR] ?: 0xFF202938.toInt()
                if (_builtInVideoMd3eTextColorArgb.value != loadedVideoMd3eTextColor) {
                    _builtInVideoMd3eTextColorArgb.value = loadedVideoMd3eTextColor
                }

                val loadedPhotoMd3eFaceColor = prefs[KEY_PHOTO_MD3E_FACE_COLOR] ?: 0xFFEAF1FF.toInt()
                if (_builtInPhotoMd3eFaceColorArgb.value != loadedPhotoMd3eFaceColor) {
                    _builtInPhotoMd3eFaceColorArgb.value = loadedPhotoMd3eFaceColor
                }

                val loadedVideoMd3eFaceColor = prefs[KEY_VIDEO_MD3E_FACE_COLOR] ?: 0xFFEAF1FF.toInt()
                if (_builtInVideoMd3eFaceColorArgb.value != loadedVideoMd3eFaceColor) {
                    _builtInVideoMd3eFaceColorArgb.value = loadedVideoMd3eFaceColor
                }

                val loadedPhotoMd3eHourColor = prefs[KEY_PHOTO_MD3E_HOUR_COLOR] ?: 0xFF334155.toInt()
                if (_builtInPhotoMd3eHourColorArgb.value != loadedPhotoMd3eHourColor) {
                    _builtInPhotoMd3eHourColorArgb.value = loadedPhotoMd3eHourColor
                }

                val loadedVideoMd3eHourColor = prefs[KEY_VIDEO_MD3E_HOUR_COLOR] ?: 0xFF334155.toInt()
                if (_builtInVideoMd3eHourColorArgb.value != loadedVideoMd3eHourColor) {
                    _builtInVideoMd3eHourColorArgb.value = loadedVideoMd3eHourColor
                }

                val loadedPhotoMd3eMinuteColor = prefs[KEY_PHOTO_MD3E_MINUTE_COLOR] ?: 0xFF5F84B6.toInt()
                if (_builtInPhotoMd3eMinuteColorArgb.value != loadedPhotoMd3eMinuteColor) {
                    _builtInPhotoMd3eMinuteColorArgb.value = loadedPhotoMd3eMinuteColor
                }

                val loadedVideoMd3eMinuteColor = prefs[KEY_VIDEO_MD3E_MINUTE_COLOR] ?: 0xFF5F84B6.toInt()
                if (_builtInVideoMd3eMinuteColorArgb.value != loadedVideoMd3eMinuteColor) {
                    _builtInVideoMd3eMinuteColorArgb.value = loadedVideoMd3eMinuteColor
                }

                val loadedPhotoMd3eSecondColor = prefs[KEY_PHOTO_MD3E_SECOND_COLOR] ?: 0xFF806EA4.toInt()
                if (_builtInPhotoMd3eSecondColorArgb.value != loadedPhotoMd3eSecondColor) {
                    _builtInPhotoMd3eSecondColorArgb.value = loadedPhotoMd3eSecondColor
                }

                val loadedVideoMd3eSecondColor = prefs[KEY_VIDEO_MD3E_SECOND_COLOR] ?: 0xFF806EA4.toInt()
                if (_builtInVideoMd3eSecondColorArgb.value != loadedVideoMd3eSecondColor) {
                    _builtInVideoMd3eSecondColorArgb.value = loadedVideoMd3eSecondColor
                }

                val loadedPhotoShowSeconds = prefs[KEY_PHOTO_SHOW_SECONDS] ?: false
                if (_builtInPhotoShowSeconds.value != loadedPhotoShowSeconds) {
                    _builtInPhotoShowSeconds.value = loadedPhotoShowSeconds
                }

                val loadedVideoShowSeconds = prefs[KEY_VIDEO_SHOW_SECONDS] ?: false
                if (_builtInVideoShowSeconds.value != loadedVideoShowSeconds) {
                    _builtInVideoShowSeconds.value = loadedVideoShowSeconds
                }

                val loadedPhotoCustomText = prefs[KEY_PHOTO_CUSTOM_TEXT].orEmpty()
                if (_builtInPhotoCustomText.value != loadedPhotoCustomText) {
                    _builtInPhotoCustomText.value = loadedPhotoCustomText
                }

                val loadedVideoCustomText = prefs[KEY_VIDEO_CUSTOM_TEXT].orEmpty()
                if (_builtInVideoCustomText.value != loadedVideoCustomText) {
                    _builtInVideoCustomText.value = loadedVideoCustomText
                }

                val loadedManagerThumbnails = prefs[KEY_BUILTIN_MANAGER_THUMBNAILS] ?: true
                if (_builtInManagerThumbnails.value != loadedManagerThumbnails) _builtInManagerThumbnails.value = loadedManagerThumbnails

                val loadedHideFromRecents = prefs[KEY_HIDE_FROM_RECENTS] ?: true
                if (_hideFromRecents.value != loadedHideFromRecents) _hideFromRecents.value = loadedHideFromRecents
                // RecentsVisibility 的读方遍布各 Activity 的 onCreate/onResume，
                // 靠这条收集器推送保持缓存与 DataStore 一致，读方就不必阻塞读盘。
                RecentsVisibility.updateCache(loadedHideFromRecents)

                val loadedSegmentedDownload = prefs[KEY_WATCH_STORE_SEGMENTED_DOWNLOAD] ?: true
                if (_watchStoreSegmentedDownload.value != loadedSegmentedDownload) {
                    _watchStoreSegmentedDownload.value = loadedSegmentedDownload
                }
                // 传输层是在 Composable 的 remember 里 new 出来的，拿不到 ViewModel，
                // 只能靠这个进程级引用把开关递过去。
                FlueDownloadTuning.segmentedDownloads = loadedSegmentedDownload

                // Locale is needed synchronously from Application/Activity.attachBaseContext,
                // so SharedPreferences is the sole authority. DataStore keeps a mirror for
                // backup/settings export only and must never overwrite the boot value.
                val loadedAppLanguage = com.flue.launcher.i18n.FlueLocaleManager
                    .getSavedLanguage(getApplication())
                if (_appLanguage.value != loadedAppLanguage) _appLanguage.value = loadedAppLanguage

                val loadedGlassHighlight = prefs[KEY_GLASS_HIGHLIGHT] ?: true
                if (_glassHighlightEnabled.value != loadedGlassHighlight) _glassHighlightEnabled.value = loadedGlassHighlight

                val loadedWidgetGlassHighlight = prefs[KEY_WIDGET_GLASS_HIGHLIGHT] ?: true
                if (_widgetGlassHighlightEnabled.value != loadedWidgetGlassHighlight) {
                    _widgetGlassHighlightEnabled.value = loadedWidgetGlassHighlight
                }

                watchFacePrefsHydrated = true
                syncSelectedWatchFace()
            }
        }
        refreshWatchFaces()
    }

    fun setState(state: ScreenState) {
        _screenState.value = when {
            state == ScreenState.Notifications && !_sideScreenEnabled.value -> ScreenState.Face
            state == ScreenState.Notifications && !_showNotification.value -> ScreenState.Stack
            state == ScreenState.Widgets && !_showWidgetPage.value -> ScreenState.Face
            state == ScreenState.ControlCenter && !_showControlCenter.value -> ScreenState.Face
            else -> state
        }
        // 不再在这里直接 closeFolder()。
        // LauncherActivity 观测到 screenState 离开 Apps 后，会通过
        // dismissRequestToken 通知 AppFolderOverlay 播放190ms淡出动画，
        // 动画结束时 overlay 自己调 onDismiss() → closeFolder()。
        // 保留：如果压根没有 folder 打开，就是个 noop，不影响其他逻辑。
        _revealedNotificationTarget.value = null
    }

    fun setLauncherInteractive(interactive: Boolean) {
        _launcherInteractive.value = interactive
    }

    fun openApp(
        appInfo: AppInfo,
        origin: Offset = Offset(0.5f, 0.5f),
        launchDelayMs: Long = _splashDelay.value.toLong(),
        returnState: ScreenState = ScreenState.Apps
    ) {
        if (appInfo.isFolder) {
            openFolder(appInfo)
            return
        }
        if (!appInfo.isAppListShortcut && appInfo.packageName == getApplication<Application>().packageName) {
            Toast.makeText(getApplication(), "已阻止启动自身应用", Toast.LENGTH_SHORT).show()
            return
        }
        cancelPendingReturnResolution()
        val sessionId = externalLaunchTracker.begin(
            ExternalLaunchRequest(
                targetKey = appInfo.componentKey,
                expectedPackage = appInfo.packageName.takeIf {
                    !appInfo.isBuiltInSettingsEntry && it.isNotBlank()
                },
                sourceState = returnState,
                origin = origin.toNormalizedPoint(),
            ),
        )
        publishExternalLaunchState()
        _launchSourceState.value = returnState
        _currentApp.value = appInfo
        val useFlueTransition = _animationOverrideEnabled.value
        _currentLaunchIcon.value = if (useFlueTransition) {
            appInfo.iconForDisplay()
        } else {
            null
        }
        _appOpenOrigin.value = origin
        _screenState.value = if (useFlueTransition) ScreenState.App else returnState
        _revealedNotificationTarget.value = null

        launchJob?.cancel()
        launchJob = viewModelScope.launch {
            delay(launchDelayMs)
            val launched = appRepository.launchApp(appInfo)
            externalLaunchTracker.onDispatchResult(sessionId, launched)
            publishExternalLaunchState()
            applyExternalLaunchState(showFailureToast = !launched)
            if (launched) {
                delay(EXTERNAL_LAUNCH_BACKGROUND_TIMEOUT_MS)
                externalLaunchTracker.onBackgroundConfirmationTimedOut(sessionId)
                publishExternalLaunchState()
                applyExternalLaunchState()
            }
        }
    }

    fun openNotification(
        key: String,
        origin: Offset = Offset(0.5f, 0.5f),
        returnState: ScreenState = ScreenState.Stack,
        launchDelayMs: Long = _splashDelay.value.toLong()
    ): Boolean {
        val targetEntry = _notificationGroups.value
            .asSequence()
            .flatMap { it.entries.asSequence() }
            .firstOrNull { it.key == key }
            ?: return false
        cancelPendingReturnResolution()
        val sessionId = externalLaunchTracker.begin(
            ExternalLaunchRequest(
                targetKey = "notification:$key",
                expectedPackage = targetEntry.packageName.takeIf(String::isNotBlank),
                sourceState = returnState,
                origin = origin.toNormalizedPoint(),
            ),
        )
        publishExternalLaunchState()
        _launchSourceState.value = returnState
        _currentApp.value = null
        val useFlueTransition = _animationOverrideEnabled.value
        _currentLaunchIcon.value = targetEntry.icon.takeIf { useFlueTransition }
        _appOpenOrigin.value = origin
        _screenState.value = if (useFlueTransition) ScreenState.App else returnState
        _revealedNotificationTarget.value = null

        launchJob?.cancel()
        launchJob = viewModelScope.launch {
            delay(launchDelayMs)
            val opened = WLauncherNotificationListener.openNotification(key)
            externalLaunchTracker.onDispatchResult(sessionId, opened)
            publishExternalLaunchState()
            applyExternalLaunchState(showFailureToast = !opened)
            if (opened) {
                delay(EXTERNAL_LAUNCH_BACKGROUND_TIMEOUT_MS)
                externalLaunchTracker.onBackgroundConfirmationTimedOut(sessionId)
                publishExternalLaunchState()
                applyExternalLaunchState()
            }
        }
        return true
    }

    fun onLauncherCreated(restored: Boolean, homeIntent: Boolean) {
        if (restored && externalLaunchTracker.state !is ExternalLaunchState.Idle) {
            externalLaunchTracker.invalidateProcessRestore()
            publishExternalLaunchState()
            applyExternalLaunchState()
        }
        if (homeIntent && externalLaunchTracker.state !is ExternalLaunchState.Idle) {
            onLauncherHomeIntent()
        }
    }

    fun onLauncherPaused() {
        cancelPendingReturnResolution()
        externalLaunchTracker.onPaused()
        publishExternalLaunchState()
        applyExternalLaunchState()
    }

    fun onLauncherStopped() {
        externalLaunchTracker.onStopped()
        publishExternalLaunchState()
    }

    fun onLauncherResumed() {
        cancelPendingReturnResolution()
        val backgrounded = externalLaunchTracker.state as? ExternalLaunchState.Backgrounded
        if (
            backgrounded != null &&
            !backgrounded.session.homeIntentPending &&
            backgrounded.session.lastExternalEvidence == null &&
            FlueAccessibilityService.isConnected()
        ) {
            val session = backgrounded.session
            returnResolutionJob = viewModelScope.launch {
                val dispatchTime = session.dispatchedAtElapsedMs ?: Long.MAX_VALUE
                val baselineSequence = session.lastExternalEvidence?.sequence ?: -1L
                val evidence = withTimeoutOrNull(EXTERNAL_RETURN_EVIDENCE_WAIT_MS) {
                    FlueAccessibilityService.lastExternalForegroundEvidence
                        .filterNotNull()
                        .first { candidate ->
                            candidate.observedAtElapsedMs > dispatchTime &&
                                candidate.sequence > baselineSequence
                        }
                }
                evidence?.let(externalLaunchTracker::recordEvidence)
                val latestState = externalLaunchTracker.state
                if (
                    latestState is ExternalLaunchState.Backgrounded &&
                    latestState.session.id == session.id
                ) {
                    externalLaunchTracker.onResumed()
                    publishExternalLaunchState()
                    applyExternalLaunchState()
                }
                returnResolutionJob = null
            }
            return
        }

        FlueAccessibilityService.latestExternalForegroundEvidence()?.let(
            externalLaunchTracker::recordEvidence,
        )
        externalLaunchTracker.onResumed()
        publishExternalLaunchState()
        applyExternalLaunchState()
    }

    fun onLauncherHomeIntent() {
        if (externalLaunchTracker.state is ExternalLaunchState.Idle) {
            requestHomePress()
            return
        }
        if (
            (externalLaunchTracker.state as? ExternalLaunchState.Invalidated)
                ?.session == null
        ) {
            cancelPendingReturnResolution()
            _currentApp.value = null
            _currentLaunchIcon.value = null
            _screenState.value = ScreenState.Face
            _revealedNotificationTarget.value = null
            return
        }
        resolveHomeIntent()
    }

    private fun resolveHomeIntent() {
        cancelPendingReturnResolution()
        externalLaunchTracker.onHomeIntent()
        if (
            externalLaunchTracker.state is ExternalLaunchState.LaunchDispatched ||
            externalLaunchTracker.state is ExternalLaunchState.Backgrounded
        ) {
            externalLaunchTracker.onResumed()
        }
        publishExternalLaunchState()
        applyExternalLaunchState()
    }

    private fun cancelPendingReturnResolution() {
        returnResolutionJob?.cancel()
        returnResolutionJob = null
    }

    fun acknowledgeExternalLaunch(sessionId: Long) {
        externalLaunchTracker.acknowledge(sessionId)
        publishExternalLaunchState()
        if (externalLaunchTracker.state is ExternalLaunchState.Idle) {
            _currentApp.value = null
            _currentLaunchIcon.value = null
        }
    }

    private fun publishExternalLaunchState() {
        _externalLaunchState.value = externalLaunchTracker.state
    }

    private fun applyExternalLaunchState(showFailureToast: Boolean = false) {
        when (val state = externalLaunchTracker.state) {
            is ExternalLaunchState.Returning -> {
                launchJob = null
                _currentApp.value = null
                if (!state.retainsLaunchIcon) {
                    _currentLaunchIcon.value = null
                }
                _screenState.value = if (state.kind == ReturnKind.HomeReentry) {
                    ScreenState.Face
                } else {
                    state.session.sourceState
                }
                _revealedNotificationTarget.value = null
            }

            is ExternalLaunchState.Failed -> {
                launchJob = null
                _currentApp.value = null
                _currentLaunchIcon.value = null
                _screenState.value = state.session.sourceState
                _revealedNotificationTarget.value = null
                if (showFailureToast) {
                    Toast.makeText(
                        getApplication(),
                        "应用无法启动，已刷新列表",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            is ExternalLaunchState.Cancelled -> {
                launchJob?.cancel()
                launchJob = null
                _currentApp.value = null
                _currentLaunchIcon.value = null
                _screenState.value = state.session.sourceState
                _revealedNotificationTarget.value = null
            }

            is ExternalLaunchState.Invalidated -> {
                launchJob?.cancel()
                launchJob = null
                _currentApp.value = null
                _currentLaunchIcon.value = null
                if (_screenState.value == ScreenState.App) {
                    _screenState.value = state.session?.sourceState ?: _launchSourceState.value
                }
                _revealedNotificationTarget.value = null
            }

            ExternalLaunchState.Idle,
            is ExternalLaunchState.MaskEntering,
            is ExternalLaunchState.LaunchDispatched,
            is ExternalLaunchState.Backgrounded,
            -> Unit
        }
    }

    private fun cancelCurrentExternalLaunch(reason: CancelReason) {
        val session = externalLaunchTracker.state.sessionOrNull() ?: return
        externalLaunchTracker.cancel(session.id, reason)
        publishExternalLaunchState()
        applyExternalLaunchState()
    }

    fun handleHomePress() {
        when (_screenState.value) {
            ScreenState.Face -> _screenState.value = ScreenState.Apps
            ScreenState.Apps -> _screenState.value = ScreenState.Face
            ScreenState.App -> {
                cancelCurrentExternalLaunch(CancelReason.HomeBeforeDispatch)
            }
            else -> _screenState.value = ScreenState.Face
        }
    }

    fun requestHomePress() {
        _homePressRequests.value = _homePressRequests.value + 1L
    }

    fun requestBackPress() {
        _backPressRequests.value = _backPressRequests.value + 1L
    }

    fun handleBackPress() {
        when (_screenState.value) {
            ScreenState.Face -> Unit
            ScreenState.Apps -> {
                if (_openFolder.value != null) {
                    // 文件夹由 AppFolderOverlay 自己的 BackHandler 关闭，
                    // 携带190ms 退出动画；这里不直接清状态，否则会跳过动画。
                    // （BackHandler 注册在 Compose 树里，按 LIFO 顺序先于
                    //  Activity 层的 requestBackPress 回调触发；只有当 Compose
                    //  的 BackHandler 彻底不响应时这条分支才会被执行，所以
                    //  什么都不做比强制清状态更安全。）
                } else {
                    _screenState.value = ScreenState.Face
                }
            }
            ScreenState.App -> {
                cancelCurrentExternalLaunch(CancelReason.UserBack)
            }
            ScreenState.Settings -> _screenState.value = ScreenState.Apps
            ScreenState.Stack -> _screenState.value = ScreenState.Face
            ScreenState.Notifications -> _screenState.value = if (_sideScreenEnabled.value) ScreenState.Stack else ScreenState.Face
            ScreenState.Widgets -> _screenState.value = ScreenState.Face
            ScreenState.ControlCenter -> _screenState.value = ScreenState.Face
        }
    }

    fun setLayoutMode(mode: LayoutMode) {
        _layoutMode.value = mode
        // 鱼眼参数按布局分开存，所以换布局要立刻把对应档案的值推进投影，
        // 否则新布局会先用上一种布局的参数渲染一帧。
        applyActiveLayoutProfile()
        persist { store.edit { it[KEY_LAYOUT] = mode.name } }
    }

    fun setThemeMode(mode: ThemeMode) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        repositories.applicationScope.launch { store.edit { it[KEY_THEME_MODE] = mode.name } }
    }

    fun setTransitionMotion(motion: FlueTransitionMotion) {
        if (_transitionMotion.value == motion) return
        _transitionMotion.value = motion
        repositories.applicationScope.launch { store.edit { it[KEY_TRANSITION_MOTION] = motion.name } }
    }

    fun setUiStyle(style: UiStyle) {
        if (_uiStyle.value == style) return
        _uiStyle.value = style
        repositories.applicationScope.launch { store.edit { it[KEY_UI_STYLE] = style.name } }
    }

    fun setSideScreenEnabled(enabled: Boolean) {
        _sideScreenEnabled.value = enabled
        if (!enabled && (_screenState.value == ScreenState.Stack || _screenState.value == ScreenState.Notifications)) {
            _screenState.value = ScreenState.Face
        }
        persist { store.edit { it[KEY_SIDE_SCREEN_ENABLED] = enabled } }
    }

    fun setBlurEnabled(enabled: Boolean) {
        _blurEnabled.value = enabled
        persist {
            store.edit {
                it[KEY_BLUR] = enabled
            }
        }
    }

    fun setEdgeBlurEnabled(enabled: Boolean) {
        _edgeBlurEnabled.value = enabled
        persist { store.edit { it[KEY_EDGE_BLUR] = enabled } }
    }

    fun setLowResIcons(enabled: Boolean) {
        _lowResIcons.value = enabled
        refreshIcons()
        persist { store.edit { it[KEY_LOW_RES] = enabled } }
    }

    fun setLegacyCircularIconsEnabled(enabled: Boolean) {
        _legacyCircularIcons.value = enabled
        appRepository.setLegacyCircularIconsEnabled(enabled)
        persist { store.edit { it[KEY_LEGACY_CIRCULAR_ICONS] = enabled } }
    }

    fun setAnimationOverrideEnabled(enabled: Boolean) {
        _animationOverrideEnabled.value = enabled
        persist { store.edit { it[KEY_ANIMATION_OVERRIDE] = enabled } }
    }

    fun setSplashIcon(enabled: Boolean) {
        _splashIcon.value = enabled
        persist { store.edit { it[KEY_SPLASH_ICON] = enabled } }
    }

    fun setSplashDelay(ms: Int) {
        _splashDelay.value = ms.coerceIn(300, 1500)
        val value = _splashDelay.value
        persistDebounced("splash_delay") { store.edit { it[KEY_SPLASH_DELAY] = value } }
    }

    fun setDirectLaunchAppListEnabled(enabled: Boolean) {
        _directLaunchAppListEnabled.value = enabled
        _directLaunchAppListPreferenceLoaded.value = true
        persist { store.edit { it[KEY_DIRECT_LAUNCH_APP_LIST] = enabled } }
    }

    fun setAppOrder(order: List<String>) {
        val orderSnapshot = order.toList()
        val ticket = appOrderWriteCoordinator.issue(orderSnapshot)
        pendingAppOrder.set(ticket)
        _appOrder.value = orderSnapshot
        // All UI calls arrive on the main thread, so the in-memory repository is updated
        // synchronously and can never be overwritten later by an older IO coroutine.
        appRepository.setCustomOrder(orderSnapshot)
        appOrderWriteJob?.cancel()
        appOrderWriteJob = viewModelScope.launch(Dispatchers.IO) {
            delay(APP_ORDER_WRITE_DEBOUNCE_MS)
            runCatching {
                appOrderWriteCoordinator.commit(
                    ticket = ticket,
                    updateRepository = {},
                    persist = { latestOrder ->
                        store.edit { preferences ->
                            preferences[KEY_APP_ORDER] = latestOrder.joinToString(",")
                        }
                    },
                )
            }
            pendingAppOrder.compareAndSet(ticket, null)
        }
    }

    fun openFolder(folder: AppInfo) {
        if (!folder.isFolder) return
        _openFolder.value = folder
        _openFolderItems.value = appRepository.folderItems(folder.componentKey)
    }

    fun closeFolder() {
        _openFolder.value = null
        _openFolderItems.value = emptyList()
    }

    fun createFolder(command: AppListCommand.CreateFolder): String? {
        if (command.source == command.target) return null
        val current = apps.value
        if (current.map(AppInfo::componentKey).distinct().size != current.size) return null
        val sourceIndex = current.indexOfFirst { it.componentKey == command.source.value }
        val targetIndex = current.indexOfFirst { it.componentKey == command.target.value }
        if (sourceIndex == -1 || targetIndex == -1 || sourceIndex == targetIndex) return null
        val source = current[sourceIndex]
        val target = current[targetIndex]
        val folderKey = appRepository.createFolder(source.componentKey, target.componentKey) ?: return null
        val insertIndex = minOf(sourceIndex, targetIndex)
        val nextOrder = current
            .filterIndexed { index, _ -> index != sourceIndex && index != targetIndex }
            .map { it.componentKey }
            .toMutableList()
        nextOrder.add(insertIndex.coerceIn(0, nextOrder.size), folderKey)
        setAppOrder(nextOrder)
        return folderKey
    }

    fun removeAppListShortcut(shortcut: AppInfo) {
        if (!shortcut.isAppListShortcut) return
        if (appRepository.removeShortcut(shortcut.componentKey)) {
            setAppOrder(_appOrder.value.filterNot { it == shortcut.componentKey })
            closeFolder()
        }
    }

    fun renameFolder(folder: AppInfo, name: String) {
        if (!folder.isFolder) return
        if (appRepository.renameFolder(folder.componentKey, name)) {
            val renamed = folder.copy(
                label = name.ifBlank { getApplication<Application>().getString(R.string.app_list_folder_default_name) },
            )
            _openFolder.value = if (_openFolder.value?.componentKey == folder.componentKey) renamed else _openFolder.value
        }
    }

    fun reorderFolderItems(folder: AppInfo, orderedItems: List<AppInfo>) {
        if (!folder.isFolder) return
        val mutation = appRepository.reorderFolderItems(
            folder.componentKey,
            orderedItems.map { it.componentKey }
        )
        if (mutation.succeeded) {
            _openFolderItems.value = appRepository.itemsForKeys(mutation.itemKeys)
        }
    }

    fun dissolveFolder(folder: AppInfo) {
        if (!folder.isFolder) return
        val childKeys = appRepository.dissolveFolder(folder.componentKey)
        if (childKeys.isEmpty()) return
        val currentOrder = apps.value.map { it.componentKey }.toMutableList()
        val folderIndex = currentOrder.indexOf(folder.componentKey)
        currentOrder.remove(folder.componentKey)
        currentOrder.addAll(folderIndex.coerceIn(0, currentOrder.size), childKeys)
        setAppOrder(currentOrder)
        closeFolder()
    }

    fun moveItemOutOfFolder(folder: AppInfo, item: AppInfo) {
        if (!folder.isFolder) return
        // Capture the authoritative pre-mutation position. The repository now
        // publishes the moved item synchronously so the closing overlay cannot
        // reveal the stale folder preview underneath; reading apps afterwards
        // would lose the old folder slot and append the item in the wrong place.
        val currentOrder = apps.value.map { it.componentKey }.toMutableList()
        val folderIndex = currentOrder.indexOf(folder.componentKey).takeIf { it >= 0 }
            ?: currentOrder.size
        val mutation = appRepository.moveItemOutOfFolder(folder.componentKey, item.componentKey)
        if (!mutation.succeeded) return
        val remainingKeys = mutation.itemKeys
        val remainingItems = appRepository.itemsForKeys(remainingKeys)
        val dissolvesFolder = remainingKeys.size <= 1
        if (dissolvesFolder) {
            currentOrder.remove(folder.componentKey)
            val insertAt = folderIndex.coerceIn(0, currentOrder.size)
            remainingItems.forEachIndexed { offset, remaining ->
                if (remaining.componentKey !in currentOrder) {
                    currentOrder.add((insertAt + offset).coerceIn(0, currentOrder.size), remaining.componentKey)
                }
            }
        }
        val insertAt = folderIndex.coerceIn(0, currentOrder.size)
        if (item.componentKey !in currentOrder) {
            val offset = if (dissolvesFolder) remainingItems.size else 1
            currentOrder.add((insertAt + offset).coerceIn(0, currentOrder.size), item.componentKey)
        }
        setAppOrder(currentOrder)
        // The overlay owns its 180 ms exit animation and calls closeFolder()
        // afterwards. Clearing openFolder here unmounted it in the same frame,
        // leaving the stale composed folder preview visible in the backdrop.
        _openFolderItems.value = remainingItems
    }

    fun addItemsToFolder(folder: AppInfo, items: List<AppInfo>) {
        if (!folder.isFolder || items.isEmpty()) return
        val addedKeys = items
            .asSequence()
            .filterNot { it.isFolder }
            .map { it.componentKey }
            .filter { it != folder.componentKey }
            .distinct()
            .toList()
        if (addedKeys.isEmpty()) return
        val mutation = appRepository.addItemsToFolder(folder.componentKey, addedKeys)
        if (!mutation.succeeded) return
        val nextKeys = mutation.itemKeys
        val addedKeySet = addedKeys.toSet()
        val currentOrder = apps.value.map { it.componentKey }.toMutableList()
        // A source folder with one remaining item is dissolved atomically by the
        // storage layer. Restore its surviving child at the old folder position
        // before removing the newly transferred keys from top-level order.
        mutation.dissolvedFolders.forEach { (dissolvedFolderKey, keptKeys) ->
            val folderIndex = currentOrder.indexOf(dissolvedFolderKey)
            if (folderIndex < 0) return@forEach
            currentOrder.removeAt(folderIndex)
            keptKeys.filterNot { it in addedKeySet }.forEachIndexed { offset, key ->
                if (key !in currentOrder) {
                    currentOrder.add((folderIndex + offset).coerceIn(0, currentOrder.size), key)
                }
            }
        }
        currentOrder.removeAll(addedKeySet)
        setAppOrder(currentOrder)
        _openFolderItems.value = appRepository.itemsForKeys(nextKeys)
    }

    fun setFolderItems(folder: AppInfo, items: List<AppInfo>) {
        if (!folder.isFolder) return
        val originalKeys = appRepository.folderItemKeys(folder.componentKey)
        val folderSnapshot = appRepository.folderMembershipSnapshot()
        val selectedKeys = items
            .asSequence()
            .filterNot { it.isFolder }
            .map { it.componentKey }
            .filter { it != folder.componentKey }
            .distinct()
            .toList()
        val selectedKeySet = selectedKeys.toSet()
        val dissolvedOtherFolderItems = folderSnapshot
            .filterKeys { it != folder.componentKey }
            .mapNotNull { (otherFolderKey, otherKeys) ->
                val keptKeys = otherKeys.filterNot { it in selectedKeySet }
                if (keptKeys != otherKeys && keptKeys.size <= 1) {
                    otherFolderKey to keptKeys.filter { appRepository.itemForKey(it) != null }
                } else {
                    null
                }
            }
        val mutation = appRepository.setFolderItems(folder.componentKey, selectedKeys)
        if (!mutation.succeeded) return
        val nextKeys = mutation.itemKeys
        val currentTopLevelKeys = apps.value.map { it.componentKey }.toMutableList()
        val folderIndex = currentTopLevelKeys.indexOf(folder.componentKey).takeIf { it >= 0 }
        val removedKeys = originalKeys
            .filter { it !in selectedKeySet }
            .filter { appRepository.itemForKey(it) != null }
        currentTopLevelKeys.removeAll(selectedKeySet)
        dissolvedOtherFolderItems.forEach { (otherFolderKey, keptKeys) ->
            val otherFolderIndex = currentTopLevelKeys.indexOf(otherFolderKey).takeIf { it >= 0 }
            val otherInsertAt = (otherFolderIndex ?: currentTopLevelKeys.size).coerceIn(0, currentTopLevelKeys.size)
            currentTopLevelKeys.remove(otherFolderKey)
            keptKeys.forEachIndexed { offset, key ->
                if (key !in currentTopLevelKeys) {
                    currentTopLevelKeys.add((otherInsertAt + offset).coerceIn(0, currentTopLevelKeys.size), key)
                }
            }
        }
        val insertAt = currentTopLevelKeys.indexOf(folder.componentKey).takeIf { it >= 0 }
            ?: (folderIndex ?: currentTopLevelKeys.size).coerceIn(0, currentTopLevelKeys.size)
        if (nextKeys.size <= 1) {
            currentTopLevelKeys.remove(folder.componentKey)
            val restoredKeys = nextKeys + removedKeys.filter { it !in nextKeys }
            restoredKeys.forEachIndexed { offset, key ->
                if (key !in currentTopLevelKeys) {
                    currentTopLevelKeys.add((insertAt + offset).coerceIn(0, currentTopLevelKeys.size), key)
                }
            }
            setAppOrder(currentTopLevelKeys)
            closeFolder()
            return
        }
        removedKeys.forEachIndexed { offset, key ->
            if (key !in currentTopLevelKeys) {
                currentTopLevelKeys.add((insertAt + 1 + offset).coerceIn(0, currentTopLevelKeys.size), key)
            }
        }
        if (folder.componentKey !in currentTopLevelKeys) {
            currentTopLevelKeys.add(insertAt, folder.componentKey)
        }
        setAppOrder(currentTopLevelKeys)
        _openFolderItems.value = appRepository.itemsForKeys(nextKeys)
    }

    fun setHoneycombCols(cols: Int) {
        updateActiveLayoutProfile {
            it.copy(narrowColumns = cols.coerceIn(FLUE_LAYOUT_NARROW_COLUMNS_MIN, FLUE_LAYOUT_NARROW_COLUMNS_MAX))
        }
    }

    fun setHoneycombTopBlur(value: Int) {
        _honeycombTopBlur.value = value.coerceIn(0, 48)
        val savedValue = _honeycombTopBlur.value
        persistDebounced("key_honeycomb_top_blur") { store.edit { it[KEY_HONEYCOMB_TOP_BLUR] = savedValue } }
    }

    fun setHoneycombBottomBlur(value: Int) {
        _honeycombBottomBlur.value = value.coerceIn(0, 48)
        val savedValue = _honeycombBottomBlur.value
        persistDebounced("key_honeycomb_bottom_blur") { store.edit { it[KEY_HONEYCOMB_BOTTOM_BLUR] = savedValue } }
    }

    fun setHoneycombEdgeBlurRadius(value: Float) {
        val savedTenths = normalizeEdgeBlurTenths(value)
        _honeycombEdgeBlurRadius.value = savedTenths / 10f
        persistDebounced("key_honeycomb_edge_blur_tenths") {
            store.edit { it[KEY_HONEYCOMB_EDGE_BLUR_TENTHS] = savedTenths }
        }
    }

    fun setHoneycombTopFade(value: Int) {
        _honeycombTopFade.value = value.coerceIn(0, 160)
        val savedValue = _honeycombTopFade.value
        persistDebounced("key_honeycomb_top_fade") { store.edit { it[KEY_HONEYCOMB_TOP_FADE] = savedValue } }
    }

    fun setHoneycombBottomFade(value: Int) {
        _honeycombBottomFade.value = value.coerceIn(0, 160)
        val savedValue = _honeycombBottomFade.value
        persistDebounced("key_honeycomb_bottom_fade") { store.edit { it[KEY_HONEYCOMB_BOTTOM_FADE] = savedValue } }
    }

    fun setAppListFisheyeEnabled(enabled: Boolean) {
        updateActiveLayoutProfile { it.copy(fisheyeEnabled = enabled) }
    }

    fun setMaterialHoneycombTopFisheyeEnabled(enabled: Boolean) {
        updateActiveLayoutProfile { it.copy(topRowExtraFisheyeEnabled = enabled) }
    }

    fun setAppListFisheyeRangeRows(value: Int) {
        updateActiveLayoutProfile { it.copy(fisheyeRangeRows = value) }
    }

    fun setAppListFisheyeStrengthPercent(value: Int) {
        updateActiveLayoutProfile { it.copy(fisheyeStrengthPercent = value) }
    }

    fun setAppListEdgeSpacingCompressionEnabled(enabled: Boolean) {
        updateActiveLayoutProfile { it.copy(edgeSpacingCompressionEnabled = enabled) }
    }

    fun setAppListLeftSafeInsetPercent(value: Int) {
        updateActiveLayoutProfile {
            it.copy(leftSafeInsetPercent = normalizeLeftSafeInsetPercent(value))
        }
    }

    /** 心流月牙弧度：占上限鼓出量的百分比，0 = 不鼓。 */
    fun setAppListFlowCrescentBulgePercent(value: Int) {
        updateActiveLayoutProfile { it.copy(crescentBulgePercent = value) }
    }

    /** 心流月牙朝向。 */
    fun setAppListFlowCrescentToRight(toRight: Boolean) {
        updateActiveLayoutProfile {
            it.copy(
                crescentDirection = if (toRight) {
                    FlowCrescentDirection.Right
                } else {
                    FlowCrescentDirection.Left
                },
            )
        }
    }

    fun setAppListScalePercent(value: Int) {
        updateActiveLayoutProfile {
            it.copy(appListScalePercent = value.coerceIn(FLUE_LAYOUT_APP_LIST_SCALE_MIN, FLUE_LAYOUT_APP_LIST_SCALE_MAX))
        }
    }

    fun setGlobalUiScalePercent(value: Int) {
        _globalUiScalePercent.value = normalizeGlobalUiScalePercent(value)
        val savedValue = _globalUiScalePercent.value
        persistDebounced("key_global_ui_scale_percent") {
            store.edit { it[KEY_GLOBAL_UI_SCALE_PERCENT] = savedValue }
        }
    }

    fun setOverlayMaskCornerRadiusDp(value: Int) {
        _overlayMaskCornerRadiusDp.value = normalizeOverlayMaskCornerRadiusDp(value)
        val savedValue = _overlayMaskCornerRadiusDp.value
        persistDebounced("key_overlay_mask_corner_radius_dp") {
            store.edit { it[KEY_OVERLAY_MASK_CORNER_RADIUS_DP] = savedValue }
        }
    }

    fun setAppListWatchFaceColors(enabled: Boolean) {
        _appListWatchFaceColors.value = enabled
        persist { store.edit { it[KEY_APP_LIST_WATCHFACE_COLORS] = enabled } }
    }

    fun setAppListRowBorderEnabled(enabled: Boolean) {
        _appListRowBorderEnabled.value = enabled
        persist { store.edit { it[KEY_APP_LIST_ROW_BORDER] = enabled } }
    }

    fun setAppListGridIconPlateEnabled(enabled: Boolean) {
        _appListGridIconPlateEnabled.value = enabled
        persist { store.edit { it[KEY_APP_LIST_GRID_ICON_PLATE] = enabled } }
    }

    fun setAppListFoldersEnabled(enabled: Boolean) {
        _appListFoldersEnabled.value = enabled
        persist { store.edit { it[KEY_APP_LIST_FOLDERS_ENABLED] = enabled } }
    }

    fun setAppListScrollIndicatorEnabled(enabled: Boolean) {
        _appListScrollIndicatorEnabled.value = enabled
        persist { store.edit { it[KEY_APP_LIST_SCROLL_INDICATOR] = enabled } }
    }

    fun setMusicTextSwitchAnimation(id: String) {
        val normalized = MusicTextSwitchAnimations.normalizeId(id)
        _musicTextSwitchAnimation.value = normalized
        persist { store.edit { it[KEY_MUSIC_TEXT_SWITCH_ANIMATION] = normalized } }
    }

    fun setIconShadowEnabled(enabled: Boolean) {
        _iconShadowEnabled.value = enabled
        persist { store.edit { it[KEY_ICON_SHADOW] = enabled } }
    }

    fun setAppListReturnShrinkEnabled(enabled: Boolean) {
        _appListReturnShrinkEnabled.value = enabled
        persist { store.edit { it[KEY_APP_LIST_RETURN_SHRINK] = enabled } }
    }

    fun setLegacyAppListTransitionEnabled(enabled: Boolean) {
        _legacyAppListTransitionEnabled.value = enabled
        persist { store.edit { it[KEY_LEGACY_APP_LIST_TRANSITION] = enabled } }
    }

    fun setAppListReturnIconFloatEnabled(enabled: Boolean) {
        _appListReturnIconFloatEnabled.value = enabled
        persist { store.edit { it[KEY_APP_LIST_RETURN_ICON_FLOAT] = enabled } }
    }

    fun setFaceReturnShrinkEnabled(enabled: Boolean) {
        _faceReturnShrinkEnabled.value = enabled
        persist { store.edit { it[KEY_FACE_RETURN_SHRINK] = enabled } }
    }

    fun setReduceDynamicEffects(enabled: Boolean) {
        _reduceDynamicEffects.value = enabled
        persist { store.edit { it[KEY_REDUCE_DYNAMIC_EFFECTS] = enabled } }
    }

    fun setScreenShapeOverride(shape: FlueScreenShape) {
        _screenShapeOverride.value = shape
        persist { store.edit { it[KEY_SCREEN_SHAPE_OVERRIDE] = shape.name } }
    }

    fun setAppListStaggerEnabled(enabled: Boolean) {
        _appListStaggerEnabled.value = enabled
        persist { store.edit { it[KEY_APP_LIST_STAGGER] = enabled } }
    }

    fun setActiveMotionProfile(id: String): Boolean {
        val profile = _motionProfiles.value.firstOrNull { it.id == id } ?: return false
        _activeMotionProfile.value = profile
        persist { store.edit { it[KEY_MOTION_PROFILE_ID] = profile.id } }
        return true
    }

    fun copyMotionProfile(
        sourceId: String = _activeMotionProfile.value.id,
        name: String? = null,
    ): FlueMotionProfile? {
        val source = _motionProfiles.value.firstOrNull { it.id == sourceId } ?: return null
        // 「副本 副本 副本」是老师报的问题：改成 `名字 2` / `名字 3`，并且避开已占用的名字。
        val name = name ?: flueUniqueProfileName(
            sourceName = source.name,
            existingNames = _motionProfiles.value.map { it.name },
        )
        val copy = runCatching { source.customCopy(customName = name) }.getOrNull() ?: return null
        val next = _motionProfiles.value + copy
        _motionProfiles.value = next
        _activeMotionProfile.value = copy
        persistMotionProfiles(next, copy.id)
        return copy
    }

    fun renameActiveMotionProfile(name: String): Boolean =
        updateActiveMotionProfile { it.copy(name = name.trim().take(48)) }

    fun setMotionBounceEnabled(enabled: Boolean): Boolean =
        updateActiveMotionProfile { it.copy(bounceEnabled = enabled) }

    fun setMotionEdgeStrength(value: Int): Boolean =
        updateActiveMotionProfile { it.copy(edgeStrength = value) }

    fun setMotionPhaseDuration(key: MotionPhaseKey, durationMillis: Int): Boolean =
        updateActiveMotionProfile { profile ->
            profile.withPhase(
                key,
                profile.phase(key).copy(durationMillis = durationMillis),
            )
        }

    fun setMotionPhaseHandoff(key: MotionPhaseKey, handoffMillis: Int): Boolean =
        updateActiveMotionProfile { profile ->
            profile.withPhase(
                key,
                profile.phase(key).copy(handoffMillis = handoffMillis),
            )
        }

    fun setMotionPhaseEasing(key: MotionPhaseKey, easing: MotionEasing): Boolean =
        updateActiveMotionProfile { profile ->
            profile.withPhase(key, profile.phase(key).copy(easing = easing))
        }

    fun deleteMotionProfile(id: String): Boolean {
        if (FlueMotionProfiles.isBuiltIn(id)) return false
        val current = _motionProfiles.value
        if (current.none { it.id == id }) return false
        val next = current.filterNot { it.id == id }
        val nextActive = if (_activeMotionProfile.value.id == id) {
            FlueMotionProfiles.Gentle
        } else {
            _activeMotionProfile.value
        }
        _motionProfiles.value = next
        _activeMotionProfile.value = nextActive
        persistMotionProfiles(next, nextActive.id)
        return true
    }

    fun exportMotionProfileJson(id: String = _activeMotionProfile.value.id): Result<String> =
        runCatching {
            val profile = _motionProfiles.value.firstOrNull { it.id == id }
                ?: error("找不到动画配置")
            FlueMotionProfileJson.encodeProfile(profile)
        }

    fun exportMotionProfileLibraryJson(): Result<String> = runCatching {
        FlueMotionProfileJson.encodeLibrary(
            _motionProfiles.value.filterNot { FlueMotionProfiles.isBuiltIn(it.id) },
        )
    }

    fun importMotionProfileJson(payload: String): Result<List<FlueMotionProfile>> = runCatching {
        val decoded = runCatching { FlueMotionProfileJson.decodeLibrary(payload) }
            .getOrElse { listOf(FlueMotionProfileJson.decodeProfile(payload)) }
        require(decoded.isNotEmpty()) { "配置文件中没有动画曲线" }
        val imported = decoded.map { profile ->
            profile.customCopy(
                customName = profile.name,
            )
        }
        val next = _motionProfiles.value + imported
        val nextActive = imported.last()
        _motionProfiles.value = next
        _activeMotionProfile.value = nextActive
        persistMotionProfiles(next, nextActive.id)
        imported
    }

    private fun updateActiveMotionProfile(
        transform: (FlueMotionProfile) -> FlueMotionProfile,
    ): Boolean {
        val current = _activeMotionProfile.value
        if (FlueMotionProfiles.isBuiltIn(current.id)) return false
        val updated = runCatching { transform(current).requireValid() }.getOrNull() ?: return false
        val next = _motionProfiles.value.map { profile ->
            if (profile.id == current.id) updated else profile
        }
        if (next.none { it.id == updated.id }) return false
        _motionProfiles.value = next
        _activeMotionProfile.value = updated
        persistMotionProfiles(next, updated.id)
        return true
    }

    private fun persistMotionProfiles(
        profiles: List<FlueMotionProfile>,
        activeId: String,
    ) {
        val customPayload = FlueMotionProfileJson.encodeLibrary(
            profiles.filterNot { FlueMotionProfiles.isBuiltIn(it.id) },
        )
        persist {
            store.edit {
                it[KEY_CUSTOM_MOTION_PROFILES] = customPayload
                it[KEY_MOTION_PROFILE_ID] = activeId
            }
        }
    }

    // ---- 布局档案的读写机制 -------------------------------------------------

    /**
     * 把「当前布局 + 当前选中档案」解析出来，推进所有投影 flow。
     *
     * 每次改布局、改档案、切档案之后都要调一次。这是投影的**唯一**写入口 ——
     * 别的地方直接写 `_appListFisheyeEnabled` 就会出现两份真值。
     */
    private fun applyActiveLayoutProfile() {
        val mode = _layoutMode.value
        val profile = FlueLayoutProfiles.resolve(
            id = activeLayoutProfileIds[mode],
            layoutMode = mode,
            profiles = _layoutProfiles.value,
        )
        if (_activeLayoutProfile.value != profile) _activeLayoutProfile.value = profile
        if (_appListFisheyeEnabled.value != profile.fisheyeEnabled) {
            _appListFisheyeEnabled.value = profile.fisheyeEnabled
        }
        if (_materialHoneycombTopFisheyeEnabled.value != profile.topRowExtraFisheyeEnabled) {
            _materialHoneycombTopFisheyeEnabled.value = profile.topRowExtraFisheyeEnabled
        }
        if (_appListEdgeSpacingCompressionEnabled.value != profile.edgeSpacingCompressionEnabled) {
            _appListEdgeSpacingCompressionEnabled.value = profile.edgeSpacingCompressionEnabled
        }
        if (_appListFisheyeRangeRows.value != profile.fisheyeRangeRows) {
            _appListFisheyeRangeRows.value = profile.fisheyeRangeRows
        }
        if (_appListFisheyeStrengthPercent.value != profile.fisheyeStrengthPercent) {
            _appListFisheyeStrengthPercent.value = profile.fisheyeStrengthPercent
        }
        if (_appListLeftSafeInsetPercent.value != profile.leftSafeInsetPercent) {
            _appListLeftSafeInsetPercent.value = profile.leftSafeInsetPercent
        }
        if (_honeycombCols.value != profile.narrowColumns) {
            _honeycombCols.value = profile.narrowColumns
        }
        if (_appListScalePercent.value != profile.appListScalePercent) {
            _appListScalePercent.value = profile.appListScalePercent
        }
        if (_appListFlowCrescentBulgePercent.value != profile.crescentBulgePercent) {
            _appListFlowCrescentBulgePercent.value = profile.crescentBulgePercent
        }
        val toRight = profile.crescentDirection == FlowCrescentDirection.Right
        if (_appListFlowCrescentToRight.value != toRight) {
            _appListFlowCrescentToRight.value = toRight
        }
    }

    /**
     * 改当前生效档案的一个字段。
     *
     * 与动效档案的 `updateActiveMotionProfile` 有意不同：这里**不挡内置档案** ——
     * 鱼眼滑块历来是拖了就生效，要求先复制一份才能调等于把现有交互推翻。
     * 越界值走 [FlueLayoutProfile.sanitized] 夹回来而不是整次修改失败。
     */
    private fun updateActiveLayoutProfile(
        transform: (FlueLayoutProfile) -> FlueLayoutProfile,
    ): Boolean {
        val current = _activeLayoutProfile.value
        val updated = runCatching { transform(current).sanitized().requireValid() }.getOrNull()
            ?: return false
        if (updated == current) return true
        val next = _layoutProfiles.value.map { profile ->
            if (profile.id == current.id) updated else profile
        }
        if (next.none { it.id == updated.id }) return false
        _layoutProfiles.value = next
        applyActiveLayoutProfile()
        persistLayoutProfiles(next)
        return true
    }

    /**
     * 落盘整库 + 每种布局的选中 id。
     *
     * 走防抖：鱼眼那几个滑块拖动时每帧都会调进来，`persist` 会把 DataStore 写成
     * 每帧一次。key 与档案 id 无关（整库一起写），所以只用一个防抖槽。
     */
    private fun persistLayoutProfiles(profiles: List<FlueLayoutProfile>) {
        val payload = runCatching { FlueLayoutProfileJson.encodeLibrary(profiles) }.getOrNull()
            ?: return
        val activeIdsPayload = encodeActiveLayoutProfileIds()
        persistDebounced("key_layout_profiles") {
            store.edit {
                it[KEY_LAYOUT_PROFILES] = payload
                it[KEY_ACTIVE_LAYOUT_PROFILE_IDS] = activeIdsPayload
            }
        }
    }

    private fun encodeActiveLayoutProfileIds(): String {
        val json = org.json.JSONObject()
        activeLayoutProfileIds.forEach { (mode, id) -> json.put(mode.name, id) }
        return json.toString()
    }

    /**
     * 解析每种布局的选中 id。任何一项坏了 / 缺了就退回该布局的默认档，
     * 不让一条脏记录把四种布局的选择全带走。
     */
    private fun decodeActiveLayoutProfileIds(payload: String?): Map<LayoutMode, String> {
        val parsed = payload
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        return LayoutMode.entries.associateWith { mode ->
            parsed?.optString(mode.name)?.takeIf { it.isNotBlank() }
                ?: FlueLayoutProfiles.builtInFor(mode).id
        }
    }

    // ---- 应用列表背景 -------------------------------------------------------

    /**
     * 改背景配置的**唯一**入口。
     *
     * 所有 setter 都走它：先 `sanitized()` 再落盘，于是「非法值进不了状态」只有一处
     * 需要保证。落盘走去抖 —— 模糊与遮罩两条滑块拖动时每帧都会调进来。
     */
    private fun updateAppListBackground(
        transform: (AppListBackgroundConfig) -> AppListBackgroundConfig,
    ) {
        val next = transform(_appListBackground.value).sanitized()
        if (next == _appListBackground.value) return
        _appListBackground.value = next
        val payload = runCatching { AppListBackgroundJson.encode(next) }.getOrNull() ?: return
        persistDebounced("key_app_list_background") {
            store.edit { it[KEY_APP_LIST_BACKGROUND] = payload }
        }
    }

    fun setAppListBackgroundMode(mode: AppListBackgroundMode) {
        updateAppListBackground { it.copy(mode = mode) }
    }

    fun setAppListBackgroundColor(argb: Int) {
        updateAppListBackground { it.copy(customColorArgb = argb) }
    }

    fun setAppListBackgroundBlurPercent(percent: Int) {
        updateAppListBackground { it.copy(blurPercent = percent) }
    }

    fun setAppListBackgroundScrimPercent(percent: Int) {
        updateAppListBackground { it.copy(scrimPercent = percent) }
    }

    fun setAppListFlowShapeCurve(curve: FlowShapeCurve) {
        updateActiveLayoutProfile { it.copy(flowShapeCurve = curve) }
    }

    fun resetAppListBackgroundEffects() {
        updateAppListBackground {
            it.copy(
                blurPercent = AppListBackgroundConfig.DEFAULT_BLUR_PERCENT,
                scrimPercent = AppListBackgroundConfig.DEFAULT_SCRIM_PERCENT,
            )
        }
    }

    /**
     * 换 / 清自定义背景图。
     *
     * 传 `null` 只清路径、**不动 mode** —— 清了图之后 `effectiveMode` 会自动落回纯色
     * （见 `AppListBackgroundConfig.effectiveMode`），而 mode 留着，老师重新选一张图就
     * 立刻回到自定义图片档，不用再点一次模式行。
     */
    fun setAppListBackgroundImagePath(path: String?) {
        updateAppListBackground { it.copy(imagePath = path) }
    }

    // ---- 布局档案的对外操作 -------------------------------------------------

    /** 给当前布局切一份档案。id 不属于当前布局时不动。 */
    fun setActiveLayoutProfile(id: String): Boolean {
        val mode = _layoutMode.value
        val profile = _layoutProfiles.value
            .firstOrNull { it.id == id && it.layoutMode == mode }
            ?: return false
        activeLayoutProfileIds = activeLayoutProfileIds + (mode to profile.id)
        applyActiveLayoutProfile()
        persistLayoutProfiles(_layoutProfiles.value)
        return true
    }

    /** 复制当前档案并切过去。新档案跟着当前布局。 */
    fun copyLayoutProfile(
        sourceId: String = _activeLayoutProfile.value.id,
        name: String? = null,
    ): FlueLayoutProfile? {
        val source = _layoutProfiles.value.firstOrNull { it.id == sourceId } ?: return null
        // 同上：同一种布局下的档案之间不重名。
        val name = name ?: flueUniqueProfileName(
            sourceName = source.name,
            existingNames = _layoutProfiles.value
                .filter { it.layoutMode == source.layoutMode }
                .map { it.name },
        )
        val copy = runCatching { source.customCopy(customName = name) }.getOrNull() ?: return null
        val next = _layoutProfiles.value + copy
        _layoutProfiles.value = next
        activeLayoutProfileIds = activeLayoutProfileIds + (copy.layoutMode to copy.id)
        applyActiveLayoutProfile()
        persistLayoutProfiles(next)
        return copy
    }

    fun renameActiveLayoutProfile(name: String): Boolean =
        updateActiveLayoutProfile { it.copy(name = name.trim().take(48)) }

    /** 删档案。每种布局的默认档不能删 —— 删了就没有可回落的档。 */
    fun deleteLayoutProfile(id: String): Boolean {
        if (FlueLayoutProfiles.isBuiltIn(id)) return false
        val current = _layoutProfiles.value
        val victim = current.firstOrNull { it.id == id } ?: return false
        val next = current.filterNot { it.id == id }
        _layoutProfiles.value = next
        if (activeLayoutProfileIds[victim.layoutMode] == id) {
            activeLayoutProfileIds = activeLayoutProfileIds +
                (victim.layoutMode to FlueLayoutProfiles.builtInFor(victim.layoutMode).id)
        }
        applyActiveLayoutProfile()
        persistLayoutProfiles(next)
        return true
    }

    /** 把当前档案的参数退回该布局的出厂值（名字与 id 保留）。 */
    fun resetActiveLayoutProfile(): Boolean {
        val current = _activeLayoutProfile.value
        val preset = FlueLayoutProfiles.builtInFor(current.layoutMode)
        return updateActiveLayoutProfile {
            preset.copy(id = current.id, name = current.name, layoutMode = current.layoutMode)
        }
    }

    fun exportLayoutProfileJson(id: String = _activeLayoutProfile.value.id): Result<String> =
        runCatching {
            val profile = _layoutProfiles.value.firstOrNull { it.id == id }
                ?: error("布局档案不存在")
            FlueLayoutProfileJson.encodeProfile(profile)
        }

    fun exportLayoutProfileLibraryJson(): Result<String> = runCatching {
        FlueLayoutProfileJson.encodeLibrary(_layoutProfiles.value)
    }

    /**
     * 导入档案。
     *
     * 与动效导入的一处差别：这里的 id 可能撞上已有的默认档（导出的整库就含默认档），
     * 撞了就**覆盖**同 id 那一份，而不是拒收或者生一个新 id —— 老师的用法是
     * 「导出 → 清数据 → 导入还原」，那条路必须能把默认档的调参也还原回来。
     */
    fun importLayoutProfileJson(payload: String): Result<List<FlueLayoutProfile>> = runCatching {
        val decoded = runCatching { FlueLayoutProfileJson.decodeLibrary(payload) }
            .getOrElse { listOf(FlueLayoutProfileJson.decodeProfile(payload)) }
        require(decoded.isNotEmpty()) { "布局档案为空" }
        val importedById = decoded.associateBy { it.id }
        val merged = _layoutProfiles.value.map { existing ->
            importedById[existing.id] ?: existing
        }
        val existingIds = _layoutProfiles.value.mapTo(mutableSetOf()) { it.id }
        val next = FlueLayoutProfiles.withMissingBuiltIns(
            merged + decoded.filterNot { it.id in existingIds },
        )
        _layoutProfiles.value = next
        decoded.firstOrNull { it.layoutMode == _layoutMode.value }?.let { sameMode ->
            activeLayoutProfileIds = activeLayoutProfileIds + (sameMode.layoutMode to sameMode.id)
        }
        applyActiveLayoutProfile()
        persistLayoutProfiles(next)
        decoded
    }

    fun setShowStepCountEnabled(enabled: Boolean) {
        _showStepCount.value = enabled
        persist { store.edit { it[KEY_SHOW_STEP_COUNT] = enabled } }
    }

    fun setWatchFaceChargingPowerTextEnabled(enabled: Boolean) {
        _watchFaceChargingPowerText.value = enabled
        persist { store.edit { it[KEY_WATCHFACE_CHARGING_POWER_TEXT] = enabled } }
    }

    fun setWatchFaceTextEntryAnimationEnabled(enabled: Boolean) {
        _watchFaceTextEntryAnimation.value = enabled
        persist { store.edit { it[KEY_WATCHFACE_TEXT_ENTRY_ANIMATION] = enabled } }
    }

    fun setWatchFaceTextExitAnimationEnabled(enabled: Boolean) {
        _watchFaceTextExitAnimation.value = enabled
        persist { store.edit { it[KEY_WATCHFACE_TEXT_EXIT_ANIMATION] = enabled } }
    }

    fun setWatchFaceStatusIndicatorsEnabled(enabled: Boolean) {
        _watchFaceStatusIndicators.value = enabled
        persist { store.edit { it[KEY_WATCHFACE_STATUS_INDICATORS] = enabled } }
    }

    fun setWatchFaceBottomFadeEnabled(enabled: Boolean) {
        _watchFaceBottomFadeEnabled.value = enabled
        persist { store.edit { it[KEY_WATCHFACE_BOTTOM_FADE] = enabled } }
    }

    /**
     * 当前生效的画布配置：**每表盘覆盖 → App 默认**。
     *
     * [faceId] 传空串等价于「问全局默认」。
     */
    fun effectiveWatchfaceCanvasSettings(faceId: String): WatchCanvasSettings =
        _watchfaceCanvasOverrides.value[faceId] ?: _watchfaceCanvasDefault.value

    /** 该表盘是否跟随全局默认（即覆盖表里没有它）。 */
    fun watchfaceCanvasFollowsDefault(faceId: String): Boolean =
        faceId !in _watchfaceCanvasOverrides.value

    /** 写 App 级默认。跟随默认的表盘会立刻一起变。 */
    fun setWatchfaceCanvasDefault(settings: WatchCanvasSettings) {
        if (_watchfaceCanvasDefault.value == settings) return
        _watchfaceCanvasDefault.value = settings
        persist {
            store.edit {
                it[KEY_WATCHFACE_CANVAS_DEFAULT] = settings.encode()
                // 存量键跟着同步，方便回滚到旧版 APK 时画面不跳。
                it[KEY_WATCHMAKER_FORCE_FILL] = settings.preset == WatchCanvasPreset.FILL
            }
        }
    }

    /**
     * 写某个表盘的覆盖配置。写入即意味着该表盘**不再**跟随全局默认。
     */
    fun setWatchfaceCanvasOverride(faceId: String, settings: WatchCanvasSettings) {
        if (faceId.isBlank()) return
        val current = _watchfaceCanvasOverrides.value
        if (current[faceId] == settings) return
        val updated = current + (faceId to settings)
        _watchfaceCanvasOverrides.value = updated
        persistWatchfaceCanvasOverrides(updated)
    }

    /**
     * 切换某表盘的「跟随全局默认」。
     *
     * 关掉跟随时把**当前生效值**快照进覆盖表 —— 否则用户一关开关画面就跳到出厂
     * 默认，而他们的意图只是「从现在这个样子开始自己调」。
     */
    fun setWatchfaceCanvasFollowsDefault(faceId: String, follow: Boolean) {
        if (faceId.isBlank()) return
        val current = _watchfaceCanvasOverrides.value
        val updated = if (follow) {
            if (faceId !in current) return
            current - faceId
        } else {
            if (faceId in current) return
            current + (faceId to _watchfaceCanvasDefault.value)
        }
        _watchfaceCanvasOverrides.value = updated
        persistWatchfaceCanvasOverrides(updated)
    }

    /** 丢弃已卸载表盘的覆盖配置，别让它们在偏好里无限堆积。 */
    private fun pruneWatchfaceCanvasOverrides(removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        val current = _watchfaceCanvasOverrides.value
        val updated = current - removedIds
        if (updated.size == current.size) return
        _watchfaceCanvasOverrides.value = updated
        persistWatchfaceCanvasOverrides(updated)
    }

    private fun persistWatchfaceCanvasOverrides(overrides: Map<String, WatchCanvasSettings>) {
        val encoded = encodeWatchfaceCanvasOverrides(overrides)
        persist {
            store.edit {
                if (encoded.isEmpty()) it.remove(KEY_WATCHFACE_CANVAS_OVERRIDES)
                else it[KEY_WATCHFACE_CANVAS_OVERRIDES] = encoded
            }
        }
    }

    fun setWatchfaceGravityParallaxEnabled(enabled: Boolean) {
        _watchfaceGravityParallaxEnabled.value = enabled
        persist { store.edit { it[KEY_WATCHFACE_GRAVITY_PARALLAX] = enabled } }
    }

    fun setWatchfaceGravityBackdropBlurEnabled(enabled: Boolean) {
        _watchfaceGravityBackdropBlurEnabled.value = enabled
        persist { store.edit { it[KEY_WATCHFACE_GRAVITY_BACKDROP_BLUR] = enabled } }
    }

    /** 表盘日期语言四档：system/zh/en/ja；未知值按跟随系统兜底。 */
    fun setWatchfaceDateLocale(value: String) {
        _watchfaceDateLocale.value = value
        // 立即写进程级 override，表盘下一帧就按新语言渲染，不等 DataStore 回流。
        WatchDateLocale.override = WatchDateLocale.fromPreference(value)
        persist { store.edit { it[KEY_WATCHFACE_DATE_LOCALE] = value } }
    }

    /**
     * 切换 UI 语言。
     *
     * 同时写入 DataStore（供下次启动前 DataStore 热更新）和
     * [com.flue.launcher.i18n.FlueLocaleManager] 的 SharedPreferences（供
     * [attachBaseContext] 同步读取）。切换后调用方需重建所有 Activity
     * 才能使新语言生效（[android.app.Activity.recreate]）。
     */
    suspend fun setAppLanguage(context: android.content.Context, language: String): Boolean =
        withContext(Dispatchers.IO) {
            val result = persistLanguageSelection(
                language = language,
                saveStartupLanguage = { canonicalLanguage ->
                    com.flue.launcher.i18n.FlueLocaleManager.saveLanguage(context, canonicalLanguage)
                },
                mirrorLanguage = { canonicalLanguage ->
                    store.edit { it[KEY_APP_LANGUAGE] = canonicalLanguage }
                },
            )
            if (result.isSuccess) {
                _appLanguage.value = result.language
            }
            result.isSuccess
        }

    fun setShowNotification(show: Boolean) {
        _showNotification.value = show
        if (!show && _screenState.value == ScreenState.Notifications) {
            _screenState.value = if (_sideScreenEnabled.value) ScreenState.Stack else ScreenState.Face
        }
        persist {
            store.edit {
                it[KEY_SHOW_NOTIFICATION] = show
                it[KEY_NOTIFICATION_SETTING_MIGRATED] = true
            }
        }
    }

    fun setShowOngoingNotifications(show: Boolean) {
        _showOngoingNotifications.value = show
        persist { store.edit { it[KEY_SHOW_ONGOING_NOTIFICATIONS] = show } }
    }

    fun setRotaryHapticsEnabled(enabled: Boolean) {
        _rotaryHapticsEnabled.value = enabled
        persist { store.edit { it[KEY_ROTARY_HAPTICS_ENABLED] = enabled } }
    }

    fun setShowWidgetPage(show: Boolean) {
        _showWidgetPage.value = show
        if (!show && _screenState.value == ScreenState.Widgets) {
            _screenState.value = ScreenState.Face
        }
        persist { store.edit { it[KEY_SHOW_WIDGET_PAGE] = show } }
    }

    fun setShowControlCenter(show: Boolean) {
        _showControlCenter.value = show
        if (!show && _screenState.value == ScreenState.ControlCenter) {
            _screenState.value = ScreenState.Face
        }
        persist { store.edit { it[KEY_SHOW_CONTROL_CENTER] = show } }
    }

    fun setShowMusicControls(show: Boolean) {
        _showMusicControls.value = show
        persist { store.edit { it[KEY_SHOW_MUSIC_CONTROLS] = show } }
    }

    fun setShowMediaCustomActions(show: Boolean) {
        _showMediaCustomActions.value = show
        persist { store.edit { it[KEY_SHOW_MEDIA_CUSTOM_ACTIONS] = show } }
    }

    fun setSwapMusicNotificationComponents(enabled: Boolean) {
        _swapMusicNotificationComponents.value = enabled
        persist { store.edit { it[KEY_SWAP_MUSIC_NOTIFICATION_COMPONENTS] = enabled } }
    }

    fun setDoubleTapLockScreenEnabled(enabled: Boolean) {
        _doubleTapLockScreenEnabled.value = enabled
        persist { store.edit { it[KEY_DOUBLE_TAP_LOCK_SCREEN] = enabled } }
    }

    fun setFaceIgnoresBackKey(enabled: Boolean) {
        _faceIgnoresBackKey.value = enabled
        persist { store.edit { it[KEY_FACE_IGNORES_BACK_KEY] = enabled } }
    }

    fun setPowerMenuButtonEnabled(enabled: Boolean) {
        _powerMenuButtonEnabled.value = enabled
        persist { store.edit { it[KEY_POWER_MENU_BUTTON] = enabled } }
    }

    fun setAppHidden(componentKey: String, hidden: Boolean) {
        val next = _hiddenApps.value.toMutableSet().apply {
            if (hidden) add(componentKey) else remove(componentKey)
        }
        setHiddenApps(next)
    }

    fun setHiddenApps(components: Set<String>) {
        val next = components.filter(String::isNotBlank).toSet()
        _hiddenApps.value = next
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.setHiddenComponents(next)
        }
        persist {
            store.edit {
                if (next.isEmpty()) it.remove(KEY_HIDDEN_APPS)
                else it[KEY_HIDDEN_APPS] = next.joinToString(",")
            }
        }
    }

    fun refreshIconPacks() {
        viewModelScope.launch {
            val packs = withContext(Dispatchers.IO) {
                IconPackScanner.scanInstalled(getApplication())
            }
            _availableIconPacks.value = packs
        }
    }

    fun setIconPackPackage(packageName: String?) {
        val normalized = packageName?.takeIf { it.isNotBlank() }
        if (_selectedIconPackPackage.value == normalized) return
        _selectedIconPackPackage.value = normalized
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.setIconPackPackage(_selectedIconPackPackage.value)
        }
        persist {
            store.edit {
                if (_selectedIconPackPackage.value.isNullOrBlank()) {
                    it.remove(KEY_ICON_PACK_PACKAGE)
                } else {
                    it[KEY_ICON_PACK_PACKAGE] = _selectedIconPackPackage.value!!
                }
            }
        }
    }

    fun refreshWatchFaces(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        cachedWatchFaces?.let { cached ->
            if (_availableWatchFaces.value != cached) {
                _availableWatchFaces.value = cached
                watchFaceScanHydrated = true
                syncSelectedWatchFace(freshScanCompleted = true)
            }
        }
        if (!force && watchFaceScanHydrated && now - lastWatchFaceRefreshAt < 25_000L) return
        // force 的本意是「编辑器改了表盘，必须立刻重扫」。但从任意应用/锁屏回来
        // 也都会走这条 —— 原来每次 onResume 都付一轮「逐包 ZipFile + XML 解析」
        // 的成本。磁盘探测（纯 stat 走）发现什么都没变时，force 直接落空，
        // cachedWatchFaces 已在上方发布，视图保持现状即可。
        if (
            force && watchFaceScanHydrated &&
            !watchFaceDiskChangedSince(lastWatchFaceScanWallClockMs)
        ) {
            lastWatchFaceRefreshAt = now
            return
        }
        lastWatchFaceRefreshAt = now
        lastWatchFaceScanWallClockMs = System.currentTimeMillis()
        watchFaceRefreshJob?.cancel()
        watchFaceRefreshJob = viewModelScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                scanAllWatchFaces()
            }
            publishWatchFaces(scanned)
            syncSelectedWatchFace(freshScanCompleted = true)
        }
    }

    /**
     * 表盘存储目录里有没有比 [scanWallClockMs] 更晚的改动。只递归 stat（深度封顶），
     * 不读文件内容：覆盖编辑器改 watch.xml/pxml、导入/删除表盘这三类真实变更。
     * mtime 在部分文件系统上是秒级精度，留 2s 余量宁可多扫一次也不错漏编辑结果。
     */
    private fun watchFaceDiskChangedSince(scanWallClockMs: Long): Boolean {
        if (scanWallClockMs <= 0L) return true
        val context = getApplication<Application>()
        val roots = listOf(
            WatchmakerWatchFaceStorage.rootDirectory(context),
            JbWatchFaceStorage.rootDir(context),
        )
        val slackMs = 2_000L
        val newest = roots.maxOfOrNull { root -> newestMtimeOf(root, depth = 0) } ?: 0L
        return newest > scanWallClockMs - slackMs
    }

    private fun newestMtimeOf(file: File, depth: Int): Long {
        var newest = file.lastModified()
        if (depth >= 3) return newest
        val children = runCatching { file.listFiles() }.getOrNull() ?: return newest
        for (child in children) {
            newest = maxOf(newest, newestMtimeOf(child, depth + 1))
        }
        return newest
    }

    suspend fun importJbWatchFace(uri: Uri): Result<WatchFaceDescriptor> {
        return runCatching {
            watchFaceRefreshJob?.cancel()
            val descriptor = withContext(Dispatchers.IO) {
                JbWatchFaceStorage.importArchive(getApplication(), uri)
            }
            finishImportedWatchFaceImport(descriptor)
        }
    }

    suspend fun importWatchmakerWatchFace(uri: Uri): Result<WatchFaceDescriptor> {
        return runCatching {
            watchFaceRefreshJob?.cancel()
            val descriptor = withContext(Dispatchers.IO) {
                WatchmakerWatchFaceStorage.importArchive(getApplication(), uri)
            }
            finishImportedWatchFaceImport(descriptor)
        }
    }

    /**
     * Installs an editor-produced archive through the same validator and
     * content-addressed storage path as a user-imported .watch file.
     * The caller owns the temporary file and may delete it after completion.
     */
    suspend fun importWatchmakerWatchFaceFile(archive: File): Result<WatchFaceDescriptor> {
        return runCatching {
            watchFaceRefreshJob?.cancel()
            require(archive.isFile) { "编辑后的 .watch 文件不存在" }
            val descriptor = withContext(Dispatchers.IO) {
                WatchmakerWatchFaceStorage.importArchiveFile(getApplication(), archive)
            }
            finishImportedWatchFaceImport(descriptor)
        }
    }

    /** Installs a community-store download through the standard .watch path. */
    suspend fun installWatchStoreArchive(
        archive: File,
        installAsRound: Boolean,
    ): Result<WatchFaceDescriptor> {
        return runCatching {
            watchFaceRefreshJob?.cancel()
            require(archive.isFile) { "市场下载的 .watch 文件不存在" }
            val descriptor = withContext(Dispatchers.IO) {
                val imported = WatchmakerWatchFaceStorage.importArchiveFile(getApplication(), archive)
                WatchEditorSession.open(getApplication(), imported).use { editor ->
                    editor.updateRootAttribute("shape", if (installAsRound) "R" else "S")
                    editor.saveCurrentRevision()
                }
                WatchmakerWatchFaceStorage.scan(getApplication())
                    .firstOrNull { it.id == imported.id }
                    ?: imported
            }
            finishImportedWatchFaceImport(descriptor)
        }
    }

    suspend fun importWatchFaceArchive(uri: Uri): Result<WatchFaceDescriptor> {
        return runCatching {
            watchFaceRefreshJob?.cancel()
            val descriptor = withContext(Dispatchers.IO) {
                withTemporaryWatchFaceArchive(uri) { archive ->
                    when (sniffWatchFaceArchive(archive)) {
                        WatchFaceArchiveKind.Watchmaker ->
                            WatchmakerWatchFaceStorage.importArchiveFile(getApplication(), archive)
                        WatchFaceArchiveKind.JbWatch -> JbWatchFaceStorage.importArchiveFile(getApplication(), archive)
                        WatchFaceArchiveKind.Unknown ->
                            error("无法识别标准 .watch/jb_watch 表盘包：需要 watch.xml")
                    }
                }
            }
            finishImportedWatchFaceImport(descriptor)
        }
    }

    private suspend fun finishImportedWatchFaceImport(descriptor: WatchFaceDescriptor): WatchFaceDescriptor {
        val scannedRaw = withContext(Dispatchers.IO) { scanAllWatchFaces() }
        val scanned = if (scannedRaw.any { it.id == descriptor.id }) {
            scannedRaw
        } else {
            scannedRaw + descriptor
        }
        publishWatchFaces(scanned)
        val selected = scanned.firstOrNull { it.id == descriptor.id } ?: descriptor
        _selectedWatchFaceId.value = selected.id
        _watchFaceLastError.value = null
        _selectedWatchFace.value = selected
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
        lastWatchFaceRefreshAt = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            store.edit {
                it[KEY_SELECTED_WATCHFACE_ID] = selected.id
                it.remove(KEY_LAST_WATCHFACE_ERROR)
            }
        }
        syncSelectedWatchFace(freshScanCompleted = true)
        return selected
    }

    fun suggestedBackupFileName(): String = FlueBackupManager.suggestedFileName(getApplication())

    suspend fun exportBackup(uri: Uri, options: FlueBackupOptions): Result<FlueBackupExportSummary> {
        return runCatching {
            withContext(Dispatchers.IO) {
                FlueBackupManager.exportBackup(getApplication(), uri, options)
            }
        }
    }

    suspend fun readBackupPreview(uri: Uri): Result<FlueBackupPreview> {
        return runCatching {
            withContext(Dispatchers.IO) {
                FlueBackupManager.readPreview(getApplication(), uri)
            }
        }
    }

    suspend fun importBackup(uri: Uri, options: FlueBackupOptions): Result<Unit> {
        return runCatching {
            val availableAppKeys = allApps.value.ifEmpty { apps.value }.map { it.componentKey }.toSet()
            withContext(Dispatchers.IO) {
                FlueBackupManager.importBackup(
                    context = getApplication(),
                    sourceUri = uri,
                    options = options,
                    availableAppKeys = availableAppKeys,
                    widgetRepository = repositories.widgetRepository
                )
            }
            refreshWatchFaces(force = true)
            refreshIconPacks()
            requestWatchFaceRefresh()
        }
    }

    suspend fun deleteImportedWatchFace(id: String): Result<Unit> {
        return deleteImportedWatchFaces(listOf(id))
    }

    suspend fun deleteImportedWatchFaces(ids: Collection<String>): Result<Unit> {
        return runCatching {
            val requestedIds = ids.asSequence().filter(String::isNotBlank).toSet()
            require(requestedIds.isNotEmpty()) { "表盘不存在" }
            val descriptors = _availableWatchFaces.value.filter {
                it.id in requestedIds && (it.isJbWatch || it.isWatchmaker)
            }
            if (descriptors.isEmpty()) error("表盘不存在")
            val failedIds = withContext(Dispatchers.IO) {
                descriptors.mapNotNull { descriptor ->
                    val deleted = when {
                        descriptor.isJbWatch -> JbWatchFaceStorage.delete(getApplication(), descriptor)
                        descriptor.isWatchmaker -> WatchmakerWatchFaceStorage.delete(getApplication(), descriptor)
                        else -> false
                    }
                    descriptor.id.takeUnless { deleted }
                }
            }
            val scanned = withContext(Dispatchers.IO) { scanAllWatchFaces() }
            publishWatchFaces(scanned)
            // 只清真正删掉的那些：删除失败的表盘还在，它的画布配置得留着。
            pruneWatchfaceCanvasOverrides(
                descriptors.map(WatchFaceDescriptor::id).toSet() - failedIds.toSet(),
            )
            if (_selectedWatchFaceId.value in requestedIds) {
                _selectedWatchFaceId.value = BUILT_IN_WATCHFACE_ID
                _watchFaceLastError.value = null
                persist {
                    store.edit {
                        it[KEY_SELECTED_WATCHFACE_ID] = BUILT_IN_WATCHFACE_ID
                        it.remove(KEY_LAST_WATCHFACE_ERROR)
                    }
                }
            }
            syncSelectedWatchFace(freshScanCompleted = true)
            _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
            if (failedIds.isNotEmpty()) {
                error("部分表盘删除失败：${failedIds.joinToString()}")
            }
        }
    }

    fun selectWatchFace(id: String) {
        _selectedWatchFaceId.value = id
        _watchFaceLastError.value = null
        if (isImportedArchiveId(id) && _availableWatchFaces.value.none { it.id == id }) {
            viewModelScope.launch {
                val scanned = withContext(Dispatchers.IO) { scanAllWatchFaces() }
                publishWatchFaces(scanned)
                syncSelectedWatchFace(freshScanCompleted = true)
                _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
                store.edit {
                    it[KEY_SELECTED_WATCHFACE_ID] = _selectedWatchFaceId.value
                    it.remove(KEY_LAST_WATCHFACE_ERROR)
                }
            }
            return
        }
        syncSelectedWatchFace()
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
        persist {
            store.edit {
                it[KEY_SELECTED_WATCHFACE_ID] = id
                it.remove(KEY_LAST_WATCHFACE_ERROR)
            }
        }
    }

    private fun scanAllWatchFaces(): List<WatchFaceDescriptor> {
        val context = getApplication<Application>()
        return WatchFaceDescriptors.builtInDescriptors(context) +
            JbWatchFaceStorage.scan(context) +
            WatchmakerWatchFaceStorage.scan(context)
    }

    private fun publishWatchFaces(watchFaces: List<WatchFaceDescriptor>) {
        cachedWatchFaces = watchFaces
        _availableWatchFaces.value = watchFaces
        watchFaceScanHydrated = true
    }

    fun fallbackToBuiltIn(error: String? = null) {
        _selectedWatchFaceId.value = BUILT_IN_WATCHFACE_ID
        _watchFaceLastError.value = error
        syncSelectedWatchFace()
        persist {
            store.edit {
                it[KEY_SELECTED_WATCHFACE_ID] = BUILT_IN_WATCHFACE_ID
                if (error.isNullOrBlank()) {
                    it.remove(KEY_LAST_WATCHFACE_ERROR)
                } else {
                    it[KEY_LAST_WATCHFACE_ERROR] = error
                }
            }
        }
    }

    /**
     * 落盘版的退回内置表盘：**等 DataStore 真的写完才返回**。
     *
     * [fallbackToBuiltIn] 走的 `persist` 是 fire-and-forget，紧接着杀进程会让
     * 写入丢掉，新进程读到的还是那块加载失败的表盘 —— 加载页那个「切换默认
     * 表盘并重启」按钮必须用这条，否则重启完还是卡在同一个地方。
     */
    suspend fun fallbackToBuiltInAndAwaitPersist(error: String? = null) {
        _selectedWatchFaceId.value = BUILT_IN_WATCHFACE_ID
        _watchFaceLastError.value = error
        syncSelectedWatchFace()
        withContext(Dispatchers.IO) {
            store.edit {
                it[KEY_SELECTED_WATCHFACE_ID] = BUILT_IN_WATCHFACE_ID
                if (error.isNullOrBlank()) {
                    it.remove(KEY_LAST_WATCHFACE_ERROR)
                } else {
                    it[KEY_LAST_WATCHFACE_ERROR] = error
                }
            }
        }
    }

    fun clearWatchFaceError() {
        _watchFaceLastError.value = null
        persist { store.edit { it.remove(KEY_LAST_WATCHFACE_ERROR) } }
    }

    fun setBuiltInPhotoPath(path: String?) {
        val previousPath = _builtInPhotoPath.value
        _builtInPhotoPath.value = path
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
        previousPath?.takeIf { it != path }?.let(WatchFacePhotoCache::remove)
        if (path.isNullOrBlank()) InternalWatchFaceStorage.clearPhoto(getApplication())
        persist {
            store.edit {
                if (path.isNullOrBlank()) it.remove(KEY_BUILTIN_PHOTO_PATH)
                else it[KEY_BUILTIN_PHOTO_PATH] = path
            }
        }
    }

    fun setBuiltInVideoPath(path: String?) {
        _builtInVideoPath.value = path
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
        if (path.isNullOrBlank()) InternalWatchFaceStorage.clearVideo(getApplication())
        persist {
            store.edit {
                if (path.isNullOrBlank()) it.remove(KEY_BUILTIN_VIDEO_PATH)
                else it[KEY_BUILTIN_VIDEO_PATH] = path
            }
        }
    }

    fun setBuiltInPhotoClockPosition(position: WatchClockPosition) {
        _builtInPhotoClockPosition.value = position
        persist { store.edit { it[KEY_PHOTO_CLOCK_POSITION] = position.name } }
    }

    fun setBuiltInVideoClockPosition(position: WatchClockPosition) {
        _builtInVideoClockPosition.value = position
        persist { store.edit { it[KEY_VIDEO_CLOCK_POSITION] = position.name } }
    }

    fun setBuiltInPhotoClockSize(sizeSp: Int) {
        _builtInPhotoClockSize.value = sizeSp.coerceIn(28, 92)
        val value = _builtInPhotoClockSize.value
        persistDebounced("photo_clock_size") { store.edit { it[KEY_PHOTO_CLOCK_SIZE] = value } }
    }

    fun setBuiltInVideoClockSize(sizeSp: Int) {
        _builtInVideoClockSize.value = sizeSp.coerceIn(28, 92)
        val value = _builtInVideoClockSize.value
        persistDebounced("video_clock_size") { store.edit { it[KEY_VIDEO_CLOCK_SIZE] = value } }
    }

    fun setBuiltInPhotoClockBold(enabled: Boolean) {
        _builtInPhotoClockBold.value = enabled
        persist { store.edit { it[KEY_PHOTO_CLOCK_BOLD] = enabled } }
    }

    fun setBuiltInVideoClockBold(enabled: Boolean) {
        _builtInVideoClockBold.value = enabled
        persist { store.edit { it[KEY_VIDEO_CLOCK_BOLD] = enabled } }
    }

    fun setBuiltInVideoFillScreen(fillScreen: Boolean) {
        _builtInVideoFillScreen.value = fillScreen
        persist { store.edit { it[KEY_VIDEO_FILL_SCREEN] = fillScreen } }
    }

    fun setBuiltInVideoClockColorMode(mode: WatchClockColorMode) {
        _builtInVideoClockColorMode.value = mode
        persist { store.edit { it[KEY_VIDEO_CLOCK_COLOR_MODE] = mode.name } }
    }

    fun setBuiltInWatchFaceFontPath(path: String?) {
        _builtInWatchFaceFontPath.value = path?.takeIf { it.isNotBlank() }
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
        if (_builtInWatchFaceFontPath.value.isNullOrBlank()) {
            InternalWatchFaceStorage.clearFont(getApplication())
        }
        persist {
            store.edit {
                if (_builtInWatchFaceFontPath.value.isNullOrBlank()) {
                    it.remove(KEY_WATCHFACE_FONT_PATH)
                } else {
                    it[KEY_WATCHFACE_FONT_PATH] = _builtInWatchFaceFontPath.value!!
                }
            }
        }
    }

    fun setBuiltInPhotoClockStyle(style: WatchFaceClockStyle) {
        _builtInPhotoClockStyle.value = style
        persist { store.edit { it[KEY_PHOTO_CLOCK_STYLE] = style.name } }
    }

    fun setBuiltInVideoClockStyle(style: WatchFaceClockStyle) {
        _builtInVideoClockStyle.value = style
        persist { store.edit { it[KEY_VIDEO_CLOCK_STYLE] = style.name } }
    }

    fun setBuiltInPhotoMd3eShape(shape: WatchFaceMd3eShape) {
        _builtInPhotoMd3eShape.value = shape
        persist { store.edit { it[KEY_PHOTO_MD3E_SHAPE] = shape.name } }
    }

    fun setBuiltInVideoMd3eShape(shape: WatchFaceMd3eShape) {
        _builtInVideoMd3eShape.value = shape
        persist { store.edit { it[KEY_VIDEO_MD3E_SHAPE] = shape.name } }
    }

    fun setBuiltInPhotoUseThemeTextColor(enabled: Boolean) {
        _builtInPhotoUseThemeTextColor.value = enabled
        persist { store.edit { it[KEY_PHOTO_USE_THEME_TEXT_COLOR] = enabled } }
    }

    fun setBuiltInVideoUseThemeTextColor(enabled: Boolean) {
        _builtInVideoUseThemeTextColor.value = enabled
        persist { store.edit { it[KEY_VIDEO_USE_THEME_TEXT_COLOR] = enabled } }
    }

    fun setBuiltInPhotoTextColorArgb(argb: Int) {
        _builtInPhotoTextColorArgb.value = argb
        persistDebounced("photo_text_color") { store.edit { it[KEY_PHOTO_TEXT_COLOR] = argb } }
    }

    fun setBuiltInVideoTextColorArgb(argb: Int) {
        _builtInVideoTextColorArgb.value = argb
        persistDebounced("video_text_color") { store.edit { it[KEY_VIDEO_TEXT_COLOR] = argb } }
    }

    fun setBuiltInPhotoMd3eAutoColors(enabled: Boolean) {
        _builtInPhotoMd3eAutoColors.value = enabled
        persist { store.edit { it[KEY_PHOTO_MD3E_AUTO_COLORS] = enabled } }
    }

    fun setBuiltInVideoMd3eAutoColors(enabled: Boolean) {
        _builtInVideoMd3eAutoColors.value = enabled
        persist { store.edit { it[KEY_VIDEO_MD3E_AUTO_COLORS] = enabled } }
    }

    fun setBuiltInPhotoMd3eTextColorArgb(argb: Int) {
        _builtInPhotoMd3eTextColorArgb.value = argb
        persistDebounced("photo_md3e_text_color") { store.edit { it[KEY_PHOTO_MD3E_TEXT_COLOR] = argb } }
    }

    fun setBuiltInVideoMd3eTextColorArgb(argb: Int) {
        _builtInVideoMd3eTextColorArgb.value = argb
        persistDebounced("video_md3e_text_color") { store.edit { it[KEY_VIDEO_MD3E_TEXT_COLOR] = argb } }
    }

    fun setBuiltInPhotoMd3eFaceColorArgb(argb: Int) {
        _builtInPhotoMd3eFaceColorArgb.value = argb
        persistDebounced("photo_md3e_face_color") { store.edit { it[KEY_PHOTO_MD3E_FACE_COLOR] = argb } }
    }

    fun setBuiltInVideoMd3eFaceColorArgb(argb: Int) {
        _builtInVideoMd3eFaceColorArgb.value = argb
        persistDebounced("video_md3e_face_color") { store.edit { it[KEY_VIDEO_MD3E_FACE_COLOR] = argb } }
    }

    fun setBuiltInPhotoMd3eHourColorArgb(argb: Int) {
        _builtInPhotoMd3eHourColorArgb.value = argb
        persistDebounced("photo_md3e_hour_color") { store.edit { it[KEY_PHOTO_MD3E_HOUR_COLOR] = argb } }
    }

    fun setBuiltInVideoMd3eHourColorArgb(argb: Int) {
        _builtInVideoMd3eHourColorArgb.value = argb
        persistDebounced("video_md3e_hour_color") { store.edit { it[KEY_VIDEO_MD3E_HOUR_COLOR] = argb } }
    }

    fun setBuiltInPhotoMd3eMinuteColorArgb(argb: Int) {
        _builtInPhotoMd3eMinuteColorArgb.value = argb
        persistDebounced("photo_md3e_minute_color") { store.edit { it[KEY_PHOTO_MD3E_MINUTE_COLOR] = argb } }
    }

    fun setBuiltInVideoMd3eMinuteColorArgb(argb: Int) {
        _builtInVideoMd3eMinuteColorArgb.value = argb
        persistDebounced("video_md3e_minute_color") { store.edit { it[KEY_VIDEO_MD3E_MINUTE_COLOR] = argb } }
    }

    fun setBuiltInPhotoMd3eSecondColorArgb(argb: Int) {
        _builtInPhotoMd3eSecondColorArgb.value = argb
        persistDebounced("photo_md3e_second_color") { store.edit { it[KEY_PHOTO_MD3E_SECOND_COLOR] = argb } }
    }

    fun setBuiltInVideoMd3eSecondColorArgb(argb: Int) {
        _builtInVideoMd3eSecondColorArgb.value = argb
        persistDebounced("video_md3e_second_color") { store.edit { it[KEY_VIDEO_MD3E_SECOND_COLOR] = argb } }
    }

    fun setBuiltInPhotoShowSeconds(enabled: Boolean) {
        _builtInPhotoShowSeconds.value = enabled
        persist { store.edit { it[KEY_PHOTO_SHOW_SECONDS] = enabled } }
    }

    fun setBuiltInVideoShowSeconds(enabled: Boolean) {
        _builtInVideoShowSeconds.value = enabled
        persist { store.edit { it[KEY_VIDEO_SHOW_SECONDS] = enabled } }
    }

    fun setBuiltInPhotoCustomText(text: String) {
        val normalized = text.take(32)
        _builtInPhotoCustomText.value = normalized
        persistDebounced("photo_custom_text") {
            store.edit {
                if (normalized.isBlank()) it.remove(KEY_PHOTO_CUSTOM_TEXT)
                else it[KEY_PHOTO_CUSTOM_TEXT] = normalized
            }
        }
    }

    fun setBuiltInVideoCustomText(text: String) {
        val normalized = text.take(32)
        _builtInVideoCustomText.value = normalized
        persistDebounced("video_custom_text") {
            store.edit {
                if (normalized.isBlank()) it.remove(KEY_VIDEO_CUSTOM_TEXT)
                else it[KEY_VIDEO_CUSTOM_TEXT] = normalized
            }
        }
    }

    fun setBuiltInManagerThumbnails(enabled: Boolean) {
        _builtInManagerThumbnails.value = enabled
        persist { store.edit { it[KEY_BUILTIN_MANAGER_THUMBNAILS] = enabled } }
    }

    fun setHideFromRecents(enabled: Boolean) {
        _hideFromRecents.value = enabled
        persist { store.edit { it[KEY_HIDE_FROM_RECENTS] = enabled } }
    }

    fun setWatchStoreSegmentedDownload(enabled: Boolean) {
        _watchStoreSegmentedDownload.value = enabled
        // 立刻生效，不等落盘：下一次下载读的是这个字段。
        FlueDownloadTuning.segmentedDownloads = enabled
        persist { store.edit { it[KEY_WATCH_STORE_SEGMENTED_DOWNLOAD] = enabled } }
    }

    fun setGlassHighlightEnabled(enabled: Boolean) {
        _glassHighlightEnabled.value = enabled
        persist { store.edit { it[KEY_GLASS_HIGHLIGHT] = enabled } }
    }

    fun setWidgetGlassHighlightEnabled(enabled: Boolean) {
        _widgetGlassHighlightEnabled.value = enabled
        persist { store.edit { it[KEY_WIDGET_GLASS_HIGHLIGHT] = enabled } }
    }

    fun requestWatchFaceRefresh() {
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
    }

    fun setSideScreenShortcut(slotIndex: Int, componentKey: String?) {
        if (slotIndex !in 0 until SIDE_SCREEN_SHORTCUT_SLOT_CAPACITY) return
        val next = _sideScreenShortcuts.value.toMutableList().apply {
            this[slotIndex] = componentKey?.takeIf { it.isNotBlank() }
        }
        _sideScreenShortcuts.value = next
        persist {
            store.edit { prefs ->
                val serialized = serializeSideScreenShortcuts(next)
                if (serialized.isEmpty()) {
                    prefs.remove(KEY_SIDE_SCREEN_SHORTCUTS)
                } else {
                    prefs[KEY_SIDE_SCREEN_SHORTCUTS] = serialized
                }
            }
        }
    }

    fun removeSideScreenShortcut(slotIndex: Int) {
        setSideScreenShortcut(slotIndex, null)
    }

    fun swapSideScreenShortcuts(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in 0 until SIDE_SCREEN_SHORTCUT_SLOT_CAPACITY || toIndex !in 0 until SIDE_SCREEN_SHORTCUT_SLOT_CAPACITY) return
        val next = _sideScreenShortcuts.value.toMutableList()
        val item = next[fromIndex]
        next[fromIndex] = next[toIndex]
        next[toIndex] = item
        _sideScreenShortcuts.value = next
        persist {
            store.edit { prefs ->
                val serialized = serializeSideScreenShortcuts(next)
                if (serialized.isEmpty()) {
                    prefs.remove(KEY_SIDE_SCREEN_SHORTCUTS)
                } else {
                    prefs[KEY_SIDE_SCREEN_SHORTCUTS] = serialized
                }
            }
        }
    }

    fun toggleNotificationGroup(packageName: String) {
        if (packageName.isBlank()) return
        _expandedNotificationGroups.value = _expandedNotificationGroups.value.toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }
        _revealedNotificationTarget.value = null
    }

    fun collapseNotificationGroups() {
        if (_expandedNotificationGroups.value.isNotEmpty()) {
            _expandedNotificationGroups.value = emptySet()
        }
        _revealedNotificationTarget.value = null
    }

    fun expandAllNotificationGroups() {
        val expandable = _notificationGroups.value
            .filter { it.entries.size > 1 }
            .map(NotificationGroupUi::packageName)
            .toSet()
        if (expandable.isNotEmpty()) {
            _expandedNotificationGroups.value = expandable
        }
        _revealedNotificationTarget.value = null
    }

    fun dismissNotificationGroup(packageName: String) {
        val keys = _notificationGroups.value
            .firstOrNull { it.packageName == packageName }
            ?.entries
            ?.filter(NotificationEntryUi::isClearable)
            ?.map(NotificationEntryUi::key)
            .orEmpty()
        if (keys.isNotEmpty()) {
            markNotificationsDismissed(keys)
            WLauncherNotificationListener.dismissNotifications(keys)
        }
        _expandedNotificationGroups.value = _expandedNotificationGroups.value - packageName
        _revealedNotificationTarget.value = null
    }

    fun dismissNotification(key: String) {
        if (key.isBlank()) return
        markNotificationsDismissed(listOf(key))
        WLauncherNotificationListener.dismissNotification(key)
        _revealedNotificationTarget.value = null
    }

    fun dismissAllNotifications() {
        val keys = _notificationGroups.value
            .flatMap(NotificationGroupUi::entries)
            .filter(NotificationEntryUi::isClearable)
            .map(NotificationEntryUi::key)
            .distinct()
        if (keys.isEmpty()) return
        markNotificationsDismissed(keys)
        WLauncherNotificationListener.dismissNotifications(keys)
        _expandedNotificationGroups.value = emptySet()
        _revealedNotificationTarget.value = null
    }

    fun runNotificationAction(actionKey: String): Boolean {
        if (actionKey.isBlank()) return false
        _revealedNotificationTarget.value = null
        return WLauncherNotificationListener.runNotificationAction(actionKey)
    }

    fun setRevealedNotificationTarget(target: NotificationRevealTarget?) {
        _revealedNotificationTarget.value = target
    }

    fun refreshNotificationAccess() {
        val application = getApplication<Application>()
        val component = ComponentName(application, WLauncherNotificationListener::class.java)
        val enabledListeners = Settings.Secure.getString(
            application.contentResolver,
            "enabled_notification_listeners"
        )
        val granted = enabledListeners
            ?.split(':')
            ?.mapNotNull { ComponentName.unflattenFromString(it) }
            ?.any { it.packageName == component.packageName && it.className == component.className }
            ?: false
        _notificationAccessGranted.value = granted || WLauncherNotificationListener.isConnected()
        if (granted) {
            WLauncherNotificationListener.requestRebindIfNeeded(application)
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val application = getApplication<Application>()
        return com.flue.launcher.service.FlueAccessibilityService.isEnabled(application) ||
            com.flue.launcher.service.FlueAccessibilityService.isConnected()
    }

    private fun markNotificationsDismissed(keys: Collection<String>) {
        if (keys.isEmpty()) return
        _pendingDismissedNotificationKeys.value = _pendingDismissedNotificationKeys.value + keys
    }

    fun moveApp(command: AppListCommand.Move): Boolean {
        val snapshot = runCatching {
            AppListSnapshot.create(
                generation = SystemClock.elapsedRealtime(),
                entries = apps.value.map { it.toNativeAppListEntry() },
            )
        }.getOrNull() ?: return false
        val reordered = AppListReducer.reduce(snapshot.entries, command)
        if (reordered === snapshot.entries) return false
        setAppOrder(reordered.map { it.key.value })
        return true
    }

    fun resetSettings() {
        val defaultBlurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        _layoutMode.value = LayoutMode.Honeycomb
        _uiStyle.value = DEFAULT_UI_STYLE
        // 重置回默认档 = 柔和。
        _activeMotionProfile.value = FlueMotionProfiles.Gentle
        _motionProfiles.value = FlueMotionProfiles.builtIns
        _sideScreenEnabled.value = true
        _sideScreenShortcutRows.value = 2
        _sideScreenShortcutCols.value = 4
        _sideScreenShortcuts.value = List(SIDE_SCREEN_SHORTCUT_SLOT_CAPACITY) { null }
        _sideScreenWidgetSlots.value = emptyList()
        _blurEnabled.value = defaultBlurEnabled
        _edgeBlurEnabled.value = false
        _lowResIcons.value = false
        _animationOverrideEnabled.value = true
        _splashIcon.value = true
        _splashDelay.value = 500
        _directLaunchAppListEnabled.value = false
        _honeycombCols.value = 3
        _legacyCircularIcons.value = true
        _honeycombTopBlur.value = 4
        _honeycombBottomBlur.value = 4
        _honeycombEdgeBlurRadius.value = 4f
        _honeycombTopFade.value = 30
        _honeycombBottomFade.value = 30
        // 布局档案整库退回出厂，四种布局各一份；投影由 applyActiveLayoutProfile 推。
        _layoutProfiles.value = FlueLayoutProfiles.builtIns
        activeLayoutProfileIds =
            FlueLayoutProfiles.builtIns.associate { it.layoutMode to it.id }
        applyActiveLayoutProfile()
        // 背景整份退回「纯色跟随系统」。落盘的自定义图片故意**不删**：恢复默认是改设置，
        // 不是清用户文件；重新选到自定义图片档时那张图还在。
        _appListBackground.value = AppListBackgroundConfig.Default
        _appListScalePercent.value = 100
        _globalUiScalePercent.value = 100
        _overlayMaskCornerRadiusDp.value = DEFAULT_OVERLAY_MASK_CORNER_RADIUS_DP
        _widgetGlassHighlightEnabled.value = true
        _appListWatchFaceColors.value = false
        _appListRowBorderEnabled.value = false
        _appListGridIconPlateEnabled.value = true
        _appListFoldersEnabled.value = true
        _appListScrollIndicatorEnabled.value = true
        _musicTextSwitchAnimation.value = MusicTextSwitchAnimations.DEFAULT_ID
        _iconShadowEnabled.value = true
        _appListReturnShrinkEnabled.value = true
        _faceReturnShrinkEnabled.value = true
        _legacyAppListTransitionEnabled.value = false
        _appListReturnIconFloatEnabled.value = false
        _reduceDynamicEffects.value = false
        _screenShapeOverride.value = FlueScreenShape.AUTO
        _appListStaggerEnabled.value = true
        _motionProfiles.value = FlueMotionProfiles.builtIns
        _activeMotionProfile.value = FlueMotionProfiles.Gentle
        _showStepCount.value = false
        _showNotification.value = true
        _showOngoingNotifications.value = false
        _rotaryHapticsEnabled.value = true
        _showWidgetPage.value = true
        _showControlCenter.value = true
        _showMusicControls.value = true
        _showMediaCustomActions.value = true
        _swapMusicNotificationComponents.value = false
        _doubleTapLockScreenEnabled.value = false
        _faceIgnoresBackKey.value = false
        _powerMenuButtonEnabled.value = false
        _watchFaceChargingPowerText.value = true
        _watchFaceTextEntryAnimation.value = true
        _watchFaceTextExitAnimation.value = true
        _watchFaceStatusIndicators.value = true
        _watchFaceBottomFadeEnabled.value = false
        _watchfaceCanvasDefault.value = WatchCanvasSettings.GLOBAL_DEFAULT
        _watchfaceCanvasOverrides.value = emptyMap()
        // 这一项的写盘半区在下面的 store.edit 里，StateFlow 半区必须同步复位，
        // 否则 reset 后 UI 还显示旧值、下一次落盘又把旧值写回去。
        _watchfaceGravityParallaxEnabled.value = true
        _watchfaceGravityBackdropBlurEnabled.value = false
        _watchfaceDateLocale.value = WatchDateLocale.PREFERENCE_SYSTEM
        WatchDateLocale.override = null
        _hiddenApps.value = emptySet()
        _selectedIconPackPackage.value = null
        _selectedWatchFaceId.value = BUILT_IN_WATCHFACE_ID
        _selectedWatchFace.value = WatchFaceDescriptors.builtInDescriptor(
            getApplication<Application>(),
            BUILT_IN_WATCHFACE_ID,
        )
        _watchFaceLastError.value = null
        _builtInPhotoClockPosition.value = WatchClockPosition.CENTER
        _builtInVideoClockPosition.value = WatchClockPosition.CENTER
        _builtInPhotoClockSize.value = 64
        _builtInVideoClockSize.value = 64
        _builtInPhotoClockBold.value = false
        _builtInVideoClockBold.value = false
        _builtInVideoFillScreen.value = true
        _builtInVideoClockColorMode.value = WatchClockColorMode.AUTO
        _builtInWatchFaceFontPath.value = null
        _builtInPhotoClockStyle.value = WatchFaceClockStyle.DIGITAL
        _builtInVideoClockStyle.value = WatchFaceClockStyle.DIGITAL
        _builtInPhotoMd3eShape.value = WatchFaceMd3eShape.COOKIE
        _builtInVideoMd3eShape.value = WatchFaceMd3eShape.COOKIE
        _builtInPhotoUseThemeTextColor.value = true
        _builtInVideoUseThemeTextColor.value = true
        _builtInPhotoTextColorArgb.value = 0xFFFFFFFF.toInt()
        _builtInVideoTextColorArgb.value = 0xFFFFFFFF.toInt()
        _builtInPhotoMd3eAutoColors.value = true
        _builtInVideoMd3eAutoColors.value = true
        _builtInPhotoMd3eTextColorArgb.value = 0xFF202938.toInt()
        _builtInVideoMd3eTextColorArgb.value = 0xFF202938.toInt()
        _builtInPhotoMd3eFaceColorArgb.value = 0xFFEAF1FF.toInt()
        _builtInVideoMd3eFaceColorArgb.value = 0xFFEAF1FF.toInt()
        _builtInPhotoMd3eHourColorArgb.value = 0xFF334155.toInt()
        _builtInVideoMd3eHourColorArgb.value = 0xFF334155.toInt()
        _builtInPhotoMd3eMinuteColorArgb.value = 0xFF5F84B6.toInt()
        _builtInVideoMd3eMinuteColorArgb.value = 0xFF5F84B6.toInt()
        _builtInPhotoMd3eSecondColorArgb.value = 0xFF806EA4.toInt()
        _builtInVideoMd3eSecondColorArgb.value = 0xFF806EA4.toInt()
        _builtInPhotoShowSeconds.value = false
        _builtInVideoShowSeconds.value = false
        _builtInPhotoCustomText.value = ""
        _builtInVideoCustomText.value = ""
        _builtInManagerThumbnails.value = true
        _hideFromRecents.value = true
        _watchStoreSegmentedDownload.value = true
        FlueDownloadTuning.segmentedDownloads = true
        InternalWatchFaceStorage.clearFont(getApplication())
        appRepository.setHiddenComponents(emptySet())
        appRepository.setIconRenderingOptions(
            packageName = null,
            legacyCircular = true,
            iconSize = 128
        )
        persist {
            store.edit {
                it[KEY_LAYOUT] = LayoutMode.Honeycomb.name
                it[KEY_UI_STYLE] = DEFAULT_UI_STYLE.name
                it[KEY_SIDE_SCREEN_ENABLED] = true
                it[KEY_SIDE_SCREEN_SHORTCUT_ROWS] = 2
                it[KEY_SIDE_SCREEN_SHORTCUT_COLS] = 4
                it[KEY_BLUR] = defaultBlurEnabled
                it[KEY_EDGE_BLUR] = false
                it[KEY_LOW_RES] = false
                it[KEY_ANIMATION_OVERRIDE] = true
                it[KEY_SPLASH_ICON] = true
                it[KEY_SPLASH_DELAY] = 500
                it[KEY_DIRECT_LAUNCH_APP_LIST] = false
                it[KEY_HONEYCOMB_COLS] = 3
                it[KEY_HONEYCOMB_TOP_BLUR] = 4
                it[KEY_HONEYCOMB_BOTTOM_BLUR] = 4
                it[KEY_HONEYCOMB_EDGE_BLUR_TENTHS] = 40
                it[KEY_HONEYCOMB_TOP_FADE] = 30
                it[KEY_HONEYCOMB_BOTTOM_FADE] = 30
                it[KEY_APP_LIST_FISHEYE] = true
                it[KEY_MATERIAL_HONEYCOMB_TOP_FISHEYE] = false
                it[KEY_APP_LIST_FISHEYE_RANGE_ROWS] = 4
                it[KEY_APP_LIST_FISHEYE_STRENGTH_PERCENT] = 100
                it[KEY_APP_LIST_EDGE_SPACING_COMPRESSION] = true
                it[KEY_APP_LIST_LEFT_SAFE_INSET_PERCENT] = 0
                // 扁平键上面那六行只为「降级回旧版还能读到默认值」保留；
                // 真正生效的是下面这一库。
                it[KEY_LAYOUT_PROFILES] =
                    FlueLayoutProfileJson.encodeLibrary(FlueLayoutProfiles.builtIns)
                it[KEY_ACTIVE_LAYOUT_PROFILE_IDS] = encodeActiveLayoutProfileIds()
                it[KEY_APP_LIST_BACKGROUND] =
                    AppListBackgroundJson.encode(AppListBackgroundConfig.Default)
                it[KEY_APP_LIST_SCALE_PERCENT] = 100
                it[KEY_GLOBAL_UI_SCALE_PERCENT] = 100
                it[KEY_OVERLAY_MASK_CORNER_RADIUS_DP] = DEFAULT_OVERLAY_MASK_CORNER_RADIUS_DP
                it[KEY_GLASS_HIGHLIGHT] = true
                it[KEY_WIDGET_GLASS_HIGHLIGHT] = true
                it[KEY_APP_LIST_WATCHFACE_COLORS] = false
                it[KEY_APP_LIST_ROW_BORDER] = false
                it[KEY_APP_LIST_GRID_ICON_PLATE] = true
                it[KEY_APP_LIST_FOLDERS_ENABLED] = true
                it[KEY_APP_LIST_SCROLL_INDICATOR] = true
                it.remove(KEY_REMOVED_FAST_FLOW_ANIMATION)
                it[KEY_MUSIC_TEXT_SWITCH_ANIMATION] = MusicTextSwitchAnimations.DEFAULT_ID
                it.remove(KEY_REMOVED_TWO_TONE_ICONS)
                it[KEY_ICON_SHADOW] = true
                it.remove(booleanPreferencesKey("classic_return_animation_enabled"))
                it[KEY_APP_LIST_RETURN_SHRINK] = true
                it[KEY_FACE_RETURN_SHRINK] = false
                it[KEY_LEGACY_APP_LIST_TRANSITION] = false
                it[KEY_APP_LIST_RETURN_ICON_FLOAT] = false
                it[KEY_REDUCE_DYNAMIC_EFFECTS] = false
                it.remove(KEY_SCREEN_SHAPE_OVERRIDE)
                it[KEY_APP_LIST_STAGGER] = true
                it[KEY_MOTION_PROFILE_ID] = FlueMotionProfiles.GENTLE_ID
                it[KEY_CUSTOM_MOTION_PROFILES] = FlueMotionProfileJson.encodeLibrary(emptyList())
                it[KEY_SHOW_STEP_COUNT] = false
                it[KEY_LEGACY_CIRCULAR_ICONS] = true
                it[KEY_SHOW_NOTIFICATION] = true
                it[KEY_SHOW_ONGOING_NOTIFICATIONS] = false
                it[KEY_ROTARY_HAPTICS_ENABLED] = true
                it[KEY_SHOW_WIDGET_PAGE] = true
                it[KEY_SHOW_CONTROL_CENTER] = true
                it[KEY_SHOW_MUSIC_CONTROLS] = true
                it[KEY_SHOW_MEDIA_CUSTOM_ACTIONS] = true
                it[KEY_SWAP_MUSIC_NOTIFICATION_COMPONENTS] = false
                it[KEY_DOUBLE_TAP_LOCK_SCREEN] = false
                it[KEY_FACE_IGNORES_BACK_KEY] = false
                it[KEY_POWER_MENU_BUTTON] = false
                it[KEY_WATCHFACE_CHARGING_POWER_TEXT] = true
                it[KEY_WATCHFACE_TEXT_ENTRY_ANIMATION] = true
                it[KEY_WATCHFACE_TEXT_EXIT_ANIMATION] = true
                it[KEY_WATCHFACE_STATUS_INDICATORS] = true
                it[KEY_WATCHFACE_BOTTOM_FADE] = false
                it[KEY_WATCHMAKER_FORCE_FILL] = false
                it.remove(KEY_WATCHFACE_CANVAS_DEFAULT)
                it.remove(KEY_WATCHFACE_CANVAS_OVERRIDES)
                it[KEY_WATCHFACE_GRAVITY_PARALLAX] = true
                it[KEY_WATCHFACE_GRAVITY_BACKDROP_BLUR] = false
                it.remove(KEY_WATCHFACE_DATE_LOCALE)
                it[KEY_NOTIFICATION_SETTING_MIGRATED] = true
                it.remove(KEY_SIDE_SCREEN_SHORTCUTS)
                it.remove(KEY_SIDE_SCREEN_WIDGETS)
                it.remove(KEY_HIDDEN_APPS)
                it.remove(KEY_ICON_PACK_PACKAGE)
                it[KEY_SELECTED_WATCHFACE_ID] = BUILT_IN_WATCHFACE_ID
                it[KEY_PHOTO_CLOCK_POSITION] = WatchClockPosition.CENTER.name
                it[KEY_VIDEO_CLOCK_POSITION] = WatchClockPosition.CENTER.name
                it[KEY_PHOTO_CLOCK_SIZE] = 64
                it[KEY_VIDEO_CLOCK_SIZE] = 64
                it[KEY_PHOTO_CLOCK_BOLD] = false
                it[KEY_VIDEO_CLOCK_BOLD] = false
                it[KEY_VIDEO_FILL_SCREEN] = true
                it[KEY_VIDEO_CLOCK_COLOR_MODE] = WatchClockColorMode.AUTO.name
                it.remove(KEY_WATCHFACE_FONT_PATH)
                it[KEY_PHOTO_CLOCK_STYLE] = WatchFaceClockStyle.DIGITAL.name
                it[KEY_VIDEO_CLOCK_STYLE] = WatchFaceClockStyle.DIGITAL.name
                it[KEY_PHOTO_MD3E_SHAPE] = WatchFaceMd3eShape.COOKIE.name
                it[KEY_VIDEO_MD3E_SHAPE] = WatchFaceMd3eShape.COOKIE.name
                it[KEY_PHOTO_USE_THEME_TEXT_COLOR] = true
                it[KEY_VIDEO_USE_THEME_TEXT_COLOR] = true
                it[KEY_PHOTO_TEXT_COLOR] = 0xFFFFFFFF.toInt()
                it[KEY_VIDEO_TEXT_COLOR] = 0xFFFFFFFF.toInt()
                it[KEY_PHOTO_MD3E_AUTO_COLORS] = true
                it[KEY_VIDEO_MD3E_AUTO_COLORS] = true
                it[KEY_PHOTO_MD3E_TEXT_COLOR] = 0xFF202938.toInt()
                it[KEY_VIDEO_MD3E_TEXT_COLOR] = 0xFF202938.toInt()
                it[KEY_PHOTO_MD3E_FACE_COLOR] = 0xFFEAF1FF.toInt()
                it[KEY_VIDEO_MD3E_FACE_COLOR] = 0xFFEAF1FF.toInt()
                it[KEY_PHOTO_MD3E_HOUR_COLOR] = 0xFF334155.toInt()
                it[KEY_VIDEO_MD3E_HOUR_COLOR] = 0xFF334155.toInt()
                it[KEY_PHOTO_MD3E_MINUTE_COLOR] = 0xFF5F84B6.toInt()
                it[KEY_VIDEO_MD3E_MINUTE_COLOR] = 0xFF5F84B6.toInt()
                it[KEY_PHOTO_MD3E_SECOND_COLOR] = 0xFF806EA4.toInt()
                it[KEY_VIDEO_MD3E_SECOND_COLOR] = 0xFF806EA4.toInt()
                it[KEY_PHOTO_SHOW_SECONDS] = false
                it[KEY_VIDEO_SHOW_SECONDS] = false
                it.remove(KEY_PHOTO_CUSTOM_TEXT)
                it.remove(KEY_VIDEO_CUSTOM_TEXT)
                it[KEY_BUILTIN_MANAGER_THUMBNAILS] = true
                it[KEY_HIDE_FROM_RECENTS] = true
                it[KEY_WATCH_STORE_SEGMENTED_DOWNLOAD] = true
                it.remove(KEY_LAST_WATCHFACE_ERROR)
            }
        }
    }

    private fun parseClockColorMode(raw: String): WatchClockColorMode {
        return runCatching { WatchClockColorMode.valueOf(raw) }.getOrDefault(WatchClockColorMode.AUTO)
    }

    private fun parseClockStyle(raw: String): WatchFaceClockStyle {
        return runCatching { WatchFaceClockStyle.valueOf(raw) }.getOrDefault(WatchFaceClockStyle.DIGITAL)
    }

    private fun parseMd3eShape(raw: String): WatchFaceMd3eShape {
        return runCatching { WatchFaceMd3eShape.valueOf(raw) }.getOrDefault(WatchFaceMd3eShape.COOKIE)
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            block()
        }
    }

    private fun persistDebounced(tag: String, delayMs: Long = 120, block: suspend () -> Unit) {
        pendingWriteJobs.remove(tag)?.cancel()
        pendingWriteJobs[tag] = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            block()
            pendingWriteJobs.remove(tag)
        }
    }

    private fun parseClockPosition(value: String): WatchClockPosition =
        runCatching { WatchClockPosition.valueOf(value) }.getOrDefault(WatchClockPosition.CENTER)

    private fun syncSelectedWatchFace(freshScanCompleted: Boolean = false) {
        val requestedId = _selectedWatchFaceId.value.ifBlank { BUILT_IN_WATCHFACE_ID }
        val available = _availableWatchFaces.value
        val match = available.firstOrNull { it.id == requestedId }
        var selectionResolved = true

        when {
            match != null -> {
                _selectedWatchFace.value = match
            }
            isImportedArchiveId(requestedId) &&
                _selectedWatchFace.value.id == requestedId &&
                (
                    _selectedWatchFace.value.isJbWatch ||
                        _selectedWatchFace.value.isWatchmaker
                    ) -> Unit
            isImportedArchiveId(requestedId) && !freshScanCompleted -> {
                selectionResolved = false
                if (watchFaceRefreshJob?.isActive != true) {
                    refreshWatchFaces(force = true)
                }
            }
            watchFaceScanHydrated -> {
                val fallback = available.firstOrNull { it.id == BUILT_IN_WATCHFACE_ID }
                    ?: available.firstOrNull()
                    ?: WatchFaceDescriptors.builtInDescriptor(
                        getApplication<Application>(),
                        BUILT_IN_WATCHFACE_ID,
                    )
                _selectedWatchFace.value = fallback
                _selectedWatchFaceId.value = fallback.id
                if (watchFacePrefsHydrated && requestedId != fallback.id) {
                    persist {
                        store.edit { it[KEY_SELECTED_WATCHFACE_ID] = fallback.id }
                    }
                }
            }
            else -> {
                _selectedWatchFace.value = WatchFaceDescriptors.builtInDescriptor(
                    getApplication<Application>(),
                    BUILT_IN_WATCHFACE_ID,
                )
            }
        }

        _watchFaceSelectionReady.value = watchFacePrefsHydrated && watchFaceScanHydrated && selectionResolved
    }

    /**
     * 历史非 .watch 格式 id 不再算「已导入表盘」。旧偏好在扫描完成后会
     * 自动退回内置表盘，避免卡在空白表盘上。
     */
    private fun isImportedArchiveId(id: String): Boolean {
        return id.startsWith(JBWATCH_ID_PREFIX) ||
            id.startsWith(WATCHMAKER_ID_PREFIX)
    }

    private enum class WatchFaceArchiveKind {
        Watchmaker,
        JbWatch,
        Unknown
    }

    private inline fun <T> withTemporaryWatchFaceArchive(uri: Uri, block: (File) -> T): T {
        val context = getApplication<Application>()
        val tempZip = File.createTempFile("watchface_import_", ".zip", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempZip.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count.toLong()
                        if (copied > MAX_GENERIC_WATCH_ARCHIVE_BYTES) {
                            throw IllegalArgumentException(
                                "表盘包超过 ${MAX_GENERIC_WATCH_ARCHIVE_BYTES / (1024L * 1024L)}MB 限制",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取表盘包")
            block(tempZip)
        } finally {
            tempZip.delete()
        }
    }

    private fun sniffWatchFaceArchive(archive: File): WatchFaceArchiveKind {
        return ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT) }
                .toSet()
            when {
                // watch.xml + watch.pxml 这个组合**两套格式共用**：标准受保护表盘和
                // jbwatch 的文件结构一模一样，只按文件名分不开。判据落在
                // watch.xml 根节点的 protection 上，与标准安装器自己的判据同源
                // （见 watchArchiveDeclaresProtection 的注释）。
                //
                // 以前这里无条件判成 JbWatch：Flue 自己导出的受保护表盘（必带
                // protection="y"）从浏览器下载后手动导入就走错渲染器 —— 名字退化成
                // 「.watch」、严重卡顿、不能编辑也不能调画布；同一个包走 Flue 商店
                // 安装却正常，因为商店那条路直接调标准安装器，不经过这里。
                "watch.xml" in names && "watch.pxml" in names ->
                    if (watchArchiveDeclaresProtection(archive)) {
                        WatchFaceArchiveKind.Watchmaker
                    } else {
                        WatchFaceArchiveKind.JbWatch
                    }
                "watch.xml" in names -> WatchFaceArchiveKind.Watchmaker
                else -> WatchFaceArchiveKind.Unknown
            }
        }
    }

    private fun refreshIcons() {
        refreshIconsJob?.cancel()
        refreshIconsJob = viewModelScope.launch(Dispatchers.IO) {
            appRepository.requestRefresh(if (_lowResIcons.value) 64 else 128)
        }
    }

    private fun parseSideScreenShortcuts(raw: String?): List<String?> {
        val parts = raw
            ?.split('|')
            ?.map { it.ifBlank { null } }
            .orEmpty()
        return List(SIDE_SCREEN_SHORTCUT_SLOT_CAPACITY) { index -> parts.getOrNull(index) }
    }

    private fun serializeSideScreenShortcuts(shortcuts: List<String?>): String {
        val normalized = List(SIDE_SCREEN_SHORTCUT_SLOT_CAPACITY) { index ->
            shortcuts.getOrNull(index)?.takeIf { it.isNotBlank() } ?: ""
        }
        return if (normalized.all(String::isEmpty)) "" else normalized.joinToString("|")
    }

    private fun parseSideScreenWidgets(raw: String?): List<String?> {
        return raw
            ?.split(if (raw.contains('|')) '|' else ',')
            ?.mapNotNull { it.takeIf { value -> value.isNotBlank() } }
            .orEmpty()
    }

    private fun serializeSideScreenWidgets(widgets: List<String?>): String {
        return widgets
            .mapNotNull { value -> value?.takeIf { it.isNotBlank() } }
            .joinToString("|")
    }

    private fun isXiaomi17ProSeriesDevice(): Boolean {
        val maker = listOf(Build.MANUFACTURER, Build.BRAND)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        if ("xiaomi" !in maker) return false
        return listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT)
            .map { value ->
                value.lowercase(Locale.ROOT).replace(Regex("[\\s_\\-]+"), "")
            }
            .any { normalized ->
                normalized.contains("25098pn5ac") ||
                    normalized.contains("2509fpn0bc") ||
                    normalized.contains("xiaomi17pro") ||
                    normalized.contains("xiaomi17promax")
            }
    }

    private fun updateStableNotificationGroups(
        previewGroups: List<NotificationGroupUi>,
        groups: List<NotificationGroupUi>
    ) {
        if (previewGroups.isNotEmpty() || groups.isNotEmpty()) {
            pendingNotificationClearJob?.cancel()
            pendingNotificationClearJob = null
            val orderedGroups = stabilizeNotificationGroupOrder(
                previous = _notificationGroups.value,
                incoming = groups
            )
            _sideScreenPreviewGroups.value = alignNotificationPreviewOrder(
                orderedGroups = orderedGroups,
                previews = previewGroups
            )
            _notificationGroups.value = orderedGroups
            return
        }
        if (_sideScreenPreviewGroups.value.isEmpty() && _notificationGroups.value.isEmpty()) {
            return
        }
        pendingNotificationClearJob?.cancel()
        pendingNotificationClearJob = viewModelScope.launch {
            delay(220)
            _sideScreenPreviewGroups.value = emptyList()
            _notificationGroups.value = emptyList()
        }
    }

    private fun buildNotificationGroups(
        notifications: List<com.flue.launcher.service.NotifData>,
        expandedPackages: Set<String>
    ): List<NotificationGroupUi> {
        return notifications
            .groupBy { it.packageName }
            .values
            .map { group ->
                val children = group
                    .sortedByDescending { it.time }
                    .map { item ->
                        NotificationEntryUi(
                            key = item.key,
                            packageName = item.packageName,
                            groupKey = item.groupKey,
                            appLabel = item.appLabel,
                            title = item.title,
                            text = item.text,
                            time = item.time,
                            icon = item.icon,
                            isClearable = item.isClearable,
                            contentIntentAvailable = item.contentIntentAvailable,
                            isOngoing = item.isOngoing,
                            isForegroundService = item.isForegroundService,
                            actions = item.actions.map { action ->
                                NotificationActionUi(
                                    key = action.key,
                                    title = action.title
                                )
                            }
                        )
                    }
                val latest = children.first()
                NotificationGroupUi(
                    packageName = latest.packageName,
                    appLabel = latest.appLabel,
                    headerTitle = latest.appLabel,
                    icon = latest.icon,
                    latestTime = latest.time,
                    latestSummary = latest.text.ifBlank { latest.title.ifBlank { latest.appLabel } },
                    entries = children,
                    visiblePreviewEntries = children.take(1),
                    hiddenPreviewCount = (children.size - 1).coerceAtLeast(0),
                    expanded = latest.packageName in expandedPackages
                )
            }
            .sortedByDescending { it.latestTime }
    }

    private fun shouldShowNotification(item: NotifData, showOngoing: Boolean): Boolean {
        if (item.isSystemHidden) return false
        if (item.isNoisyOngoing) return false
        return showOngoing || (!item.isOngoing && !item.isForegroundService)
    }

    override fun onCleared() {
        launchJob?.cancel()
        cancelPendingReturnResolution()
        refreshIconsJob?.cancel()
        pendingNotificationClearJob?.cancel()
        appOrderWriteJob?.cancel()
        pendingWriteJobs.values.forEach(Job::cancel)
        pendingWriteJobs.clear()
        super.onCleared()
    }
}

private fun Offset.toNormalizedPoint(): NormalizedPoint = NormalizedPoint(
    x = x.coerceIn(0f, 1f),
    y = y.coerceIn(0f, 1f),
)

/**
 * 每表盘画布覆盖的行式编码：每行 `表盘id=配置编码`。
 *
 * 表盘 id 由 `builtin*` 常量或 `watchmaker_watch_<sha>` / `jbwatch_*` 构成，
 * 不含 `=` 与换行；仍然按「首个 `=` 切分」解析，这样 id 里万一混进 `=`
 * 也只会丢那一条而不是错位整张表。
 */
private fun encodeWatchfaceCanvasOverrides(
    overrides: Map<String, WatchCanvasSettings>,
): String = overrides.entries
    .filter { it.key.isNotBlank() && '=' !in it.key && '\n' !in it.key }
    .joinToString("\n") { "${it.key}=${it.value.encode()}" }

private fun decodeWatchfaceCanvasOverrides(raw: String?): Map<String, WatchCanvasSettings> {
    if (raw.isNullOrBlank()) return emptyMap()
    // DataStore 收集器每次发射都整体重跑，而这份文本在两次「真正编辑」之间
    // 几乎不变。按原文做单条记忆化，写入风暴（拖滑杆等连续写盘）期间不再
    // 每次发射都重新逐行解析。收集器在 IO 线程跑，这里无需加锁。
    decodeWatchfaceCanvasOverridesCache?.let { (payload, decoded) ->
        if (payload == raw) return decoded
    }
    val decoded = raw.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val id = line.substring(0, separator)
            id to WatchCanvasSettings.decode(line.substring(separator + 1))
        }
        .toMap()
    decodeWatchfaceCanvasOverridesCache = raw to decoded
    return decoded
}

private var decodeWatchfaceCanvasOverridesCache: Pair<String, Map<String, WatchCanvasSettings>>? = null

/**
 * 大 JSON 库的按 payload 记忆化（布局档案 / 动效档案）。偏好收集器对每次
 * 写盘都全量重跑；没有这份缓存时，拖动任意一个滑杆的每一帧都会把两份
 * JSON 全文重新解析一遍。单条缓存即可：同一个 payload 的解码结果是纯函数。
 */
private var layoutProfileLibraryCache: Pair<String, List<FlueLayoutProfile>?>? = null

/** 与原实现同为「失败返回 null」：null 会走迁移路径，不能与空列表混淆。 */
private fun decodeLayoutProfileLibraryCached(payload: String): List<FlueLayoutProfile>? {
    layoutProfileLibraryCache?.let { (key, value) -> if (key == payload) return value }
    val decoded = runCatching { FlueLayoutProfileJson.decodeLibrary(payload) }.getOrNull()
    layoutProfileLibraryCache = payload to decoded
    return decoded
}

private var motionProfileLibraryCache: Pair<String, List<FlueMotionProfile>>? = null

private fun decodeMotionProfileLibraryCached(payload: String): List<FlueMotionProfile> {
    motionProfileLibraryCache?.let { (key, value) -> if (key == payload) return value }
    val decoded = runCatching { FlueMotionProfileJson.decodeLibrary(payload) }.getOrDefault(emptyList())
    motionProfileLibraryCache = payload to decoded
    return decoded
}

private fun ExternalLaunchState.sessionOrNull(): ExternalLaunchSession? = when (this) {
    ExternalLaunchState.Idle -> null
    is ExternalLaunchState.MaskEntering -> session
    is ExternalLaunchState.LaunchDispatched -> session
    is ExternalLaunchState.Backgrounded -> session
    is ExternalLaunchState.Returning -> session
    is ExternalLaunchState.Failed -> session
    is ExternalLaunchState.Cancelled -> session
    is ExternalLaunchState.Invalidated -> session
}
