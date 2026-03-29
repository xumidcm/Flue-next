package com.flue.launcher.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.repository.AppRepository
import com.flue.launcher.iconpack.IconPackDescriptor
import com.flue.launcher.iconpack.IconPackScanner
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.navigation.ScreenState
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.WatchClockPosition
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceRegistry
import com.flue.launcher.watchface.LunchWatchFaceScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_settings")

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        val KEY_LAYOUT = stringPreferencesKey("layout_mode")
        val KEY_BLUR = booleanPreferencesKey("blur_enabled")
        val KEY_EDGE_BLUR = booleanPreferencesKey("edge_blur_enabled")
        val KEY_LOW_RES = booleanPreferencesKey("low_res_icons")
        val KEY_ANIMATION_OVERRIDE = booleanPreferencesKey("animation_override_enabled")
        val KEY_SPLASH_ICON = booleanPreferencesKey("splash_icon")
        val KEY_SPLASH_DELAY = intPreferencesKey("splash_delay")
        val KEY_APP_ORDER = stringPreferencesKey("app_order")
        val KEY_HONEYCOMB_COLS = intPreferencesKey("honeycomb_cols")
        val KEY_HONEYCOMB_TOP_BLUR = intPreferencesKey("honeycomb_top_blur")
        val KEY_HONEYCOMB_BOTTOM_BLUR = intPreferencesKey("honeycomb_bottom_blur")
        val KEY_HONEYCOMB_TOP_FADE = intPreferencesKey("honeycomb_top_fade")
        val KEY_HONEYCOMB_BOTTOM_FADE = intPreferencesKey("honeycomb_bottom_fade")
        val KEY_SHOW_NOTIFICATION = booleanPreferencesKey("show_notification")
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
        val KEY_VIDEO_FILL_SCREEN = booleanPreferencesKey("video_fill_screen")
    }

    private val store = application.dataStore
    private val appRepository = AppRepository(application)

    val allApps: StateFlow<List<AppInfo>> = appRepository.allApps
    val apps: StateFlow<List<AppInfo>> = appRepository.apps

    private val _screenState = MutableStateFlow(ScreenState.Face)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    private val _layoutMode = MutableStateFlow(LayoutMode.Honeycomb)
    val layoutMode: StateFlow<LayoutMode> = _layoutMode.asStateFlow()

    private val _blurEnabled = MutableStateFlow(true)
    val blurEnabled: StateFlow<Boolean> = _blurEnabled.asStateFlow()

    private val _edgeBlurEnabled = MutableStateFlow(false)
    val edgeBlurEnabled: StateFlow<Boolean> = _edgeBlurEnabled.asStateFlow()

    private val _lowResIcons = MutableStateFlow(false)
    val lowResIcons: StateFlow<Boolean> = _lowResIcons.asStateFlow()

    private val _animationOverrideEnabled = MutableStateFlow(true)
    val animationOverrideEnabled: StateFlow<Boolean> = _animationOverrideEnabled.asStateFlow()

    private val _splashDelay = MutableStateFlow(500)
    val splashDelay: StateFlow<Int> = _splashDelay.asStateFlow()

    private val _appOrder = MutableStateFlow<List<String>>(emptyList())
    val appOrder: StateFlow<List<String>> = _appOrder.asStateFlow()

    private val _honeycombCols = MutableStateFlow(4)
    val honeycombCols: StateFlow<Int> = _honeycombCols.asStateFlow()

    private val _honeycombTopBlur = MutableStateFlow(4)
    val honeycombTopBlur: StateFlow<Int> = _honeycombTopBlur.asStateFlow()

    private val _honeycombBottomBlur = MutableStateFlow(4)
    val honeycombBottomBlur: StateFlow<Int> = _honeycombBottomBlur.asStateFlow()

    private val _honeycombTopFade = MutableStateFlow(56)
    val honeycombTopFade: StateFlow<Int> = _honeycombTopFade.asStateFlow()

    private val _honeycombBottomFade = MutableStateFlow(56)
    val honeycombBottomFade: StateFlow<Int> = _honeycombBottomFade.asStateFlow()

    private val _splashIcon = MutableStateFlow(true)
    val splashIcon: StateFlow<Boolean> = _splashIcon.asStateFlow()

    private val _showNotification = MutableStateFlow(false)
    val showNotification: StateFlow<Boolean> = _showNotification.asStateFlow()

    private val _hiddenApps = MutableStateFlow<Set<String>>(emptySet())
    val hiddenApps: StateFlow<Set<String>> = _hiddenApps.asStateFlow()

    private val _availableIconPacks = MutableStateFlow<List<IconPackDescriptor>>(emptyList())
    val availableIconPacks: StateFlow<List<IconPackDescriptor>> = _availableIconPacks.asStateFlow()

    private val _selectedIconPackPackage = MutableStateFlow<String?>(null)
    val selectedIconPackPackage: StateFlow<String?> = _selectedIconPackPackage.asStateFlow()

    private val _availableWatchFaces = MutableStateFlow(LunchWatchFaceScanner.builtInDescriptors())
    val availableWatchFaces: StateFlow<List<LunchWatchFaceDescriptor>> = _availableWatchFaces.asStateFlow()

    private val _selectedWatchFaceId = MutableStateFlow(BUILT_IN_WATCHFACE_ID)
    val selectedWatchFaceId: StateFlow<String> = _selectedWatchFaceId.asStateFlow()

    private val _selectedWatchFace = MutableStateFlow(LunchWatchFaceScanner.builtInDescriptor(BUILT_IN_WATCHFACE_ID))
    val selectedWatchFace: StateFlow<LunchWatchFaceDescriptor> = _selectedWatchFace.asStateFlow()

    private val _watchFaceSelectionReady = MutableStateFlow(false)
    val watchFaceSelectionReady: StateFlow<Boolean> = _watchFaceSelectionReady.asStateFlow()

    private val _watchFaceRefreshToken = MutableStateFlow(0)
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

    private val _builtInVideoFillScreen = MutableStateFlow(true)
    val builtInVideoFillScreen: StateFlow<Boolean> = _builtInVideoFillScreen.asStateFlow()

    private val _appOpenOrigin = MutableStateFlow(Offset(0.5f, 0.5f))
    val appOpenOrigin: StateFlow<Offset> = _appOpenOrigin.asStateFlow()

    private val _currentApp = MutableStateFlow<AppInfo?>(null)
    val currentApp: StateFlow<AppInfo?> = _currentApp.asStateFlow()

    private var launchingExternalApp = false
    private var launchJob: Job? = null
    private var watchFacePrefsHydrated = false
    private var watchFaceScanHydrated = false

    init {
        viewModelScope.launch {
            store.data.collect { prefs ->
                var refreshIconsNeeded = false
                prefs[KEY_LAYOUT]?.let {
                    _layoutMode.value = try {
                        LayoutMode.valueOf(it)
                    } catch (_: Exception) {
                        LayoutMode.Honeycomb
                    }
                }
                prefs[KEY_BLUR]?.let { _blurEnabled.value = it }
                prefs[KEY_EDGE_BLUR]?.let { _edgeBlurEnabled.value = it }
                prefs[KEY_LOW_RES]?.let {
                    _lowResIcons.value = it
                    refreshIconsNeeded = true
                }
                prefs[KEY_ANIMATION_OVERRIDE]?.let { _animationOverrideEnabled.value = it }
                prefs[KEY_SPLASH_ICON]?.let { _splashIcon.value = it }
                prefs[KEY_SPLASH_DELAY]?.let { _splashDelay.value = it.coerceIn(300, 1500) }
                prefs[KEY_APP_ORDER]?.let { orderStr ->
                    val order = if (orderStr.isNotEmpty()) orderStr.split(",") else emptyList()
                    _appOrder.value = order
                    appRepository.setCustomOrder(order)
                }
                prefs[KEY_HONEYCOMB_COLS]?.let { _honeycombCols.value = it.coerceIn(3, 6) }
                prefs[KEY_HONEYCOMB_TOP_BLUR]?.let { _honeycombTopBlur.value = it.coerceIn(0, 48) }
                prefs[KEY_HONEYCOMB_BOTTOM_BLUR]?.let { _honeycombBottomBlur.value = it.coerceIn(0, 48) }
                prefs[KEY_HONEYCOMB_TOP_FADE]?.let { _honeycombTopFade.value = it.coerceIn(0, 160) }
                prefs[KEY_HONEYCOMB_BOTTOM_FADE]?.let { _honeycombBottomFade.value = it.coerceIn(0, 160) }
                prefs[KEY_SHOW_NOTIFICATION]?.let { _showNotification.value = it }
                val hidden = prefs[KEY_HIDDEN_APPS]
                    ?.split(",")
                    ?.filter(String::isNotBlank)
                    ?.toSet()
                    ?: emptySet()
                _hiddenApps.value = hidden
                appRepository.setHiddenComponents(hidden)
                _selectedIconPackPackage.value = prefs[KEY_ICON_PACK_PACKAGE]
                appRepository.setIconPackPackage(_selectedIconPackPackage.value)
                prefs[KEY_SELECTED_WATCHFACE_ID]?.let { _selectedWatchFaceId.value = it }
                _watchFaceLastError.value = prefs[KEY_LAST_WATCHFACE_ERROR]
                _builtInPhotoPath.value = prefs[KEY_BUILTIN_PHOTO_PATH]
                _builtInVideoPath.value = prefs[KEY_BUILTIN_VIDEO_PATH]
                _builtInPhotoClockPosition.value = prefs[KEY_PHOTO_CLOCK_POSITION]
                    ?.let(::parseClockPosition)
                    ?: WatchClockPosition.CENTER
                _builtInVideoClockPosition.value = prefs[KEY_VIDEO_CLOCK_POSITION]
                    ?.let(::parseClockPosition)
                    ?: WatchClockPosition.CENTER
                _builtInPhotoClockSize.value = (prefs[KEY_PHOTO_CLOCK_SIZE] ?: 64).coerceIn(28, 92)
                _builtInVideoClockSize.value = (prefs[KEY_VIDEO_CLOCK_SIZE] ?: 64).coerceIn(28, 92)
                _builtInVideoFillScreen.value = prefs[KEY_VIDEO_FILL_SCREEN] ?: true
                _availableIconPacks.value = IconPackScanner.scanInstalled(getApplication())
                if (refreshIconsNeeded) {
                    refreshIcons()
                }
                watchFacePrefsHydrated = true
                syncSelectedWatchFace()
            }
        }
        refreshWatchFaces()
    }

    fun setState(state: ScreenState) {
        _screenState.value = state
    }

    fun openApp(appInfo: AppInfo, origin: Offset = Offset(0.5f, 0.5f), launchDelayMs: Long = _splashDelay.value.toLong()) {
        _currentApp.value = appInfo
        _appOpenOrigin.value = origin
        _screenState.value = ScreenState.App

        launchJob?.cancel()
        launchJob = viewModelScope.launch {
            delay(launchDelayMs)
            launchingExternalApp = true
            appRepository.launchApp(appInfo)
        }
    }

    fun onReturnToLauncher() {
        if (launchingExternalApp) {
            launchingExternalApp = false
            _screenState.value = ScreenState.Apps
        }
    }

    fun handleHomePress() {
        when (_screenState.value) {
            ScreenState.Face -> _screenState.value = ScreenState.Apps
            ScreenState.Apps -> _screenState.value = ScreenState.Face
            ScreenState.App -> {
                launchJob?.cancel()
                launchJob = null
                launchingExternalApp = false
                _screenState.value = ScreenState.Apps
            }
            else -> _screenState.value = ScreenState.Face
        }
    }

    fun handleBackPress() {
        when (_screenState.value) {
            ScreenState.Face -> Unit
            ScreenState.Apps -> _screenState.value = ScreenState.Face
            ScreenState.App -> {
                launchJob?.cancel()
                launchJob = null
                launchingExternalApp = false
                _screenState.value = ScreenState.Apps
            }
            ScreenState.Settings -> _screenState.value = ScreenState.Apps
            ScreenState.Stack -> _screenState.value = ScreenState.Face
            ScreenState.Notifications -> _screenState.value = ScreenState.Face
            ScreenState.ControlCenter -> _screenState.value = ScreenState.Face
        }
    }

    fun setLayoutMode(mode: LayoutMode) {
        _layoutMode.value = mode
        viewModelScope.launch { store.edit { it[KEY_LAYOUT] = mode.name } }
    }

    fun setBlurEnabled(enabled: Boolean) {
        _blurEnabled.value = enabled
        if (!enabled) _edgeBlurEnabled.value = false
        viewModelScope.launch {
            store.edit {
                it[KEY_BLUR] = enabled
                if (!enabled) it[KEY_EDGE_BLUR] = false
            }
        }
    }

    fun setEdgeBlurEnabled(enabled: Boolean) {
        val value = enabled && _blurEnabled.value
        _edgeBlurEnabled.value = value
        viewModelScope.launch { store.edit { it[KEY_EDGE_BLUR] = value } }
    }

    fun setLowResIcons(enabled: Boolean) {
        _lowResIcons.value = enabled
        refreshIcons()
        viewModelScope.launch { store.edit { it[KEY_LOW_RES] = enabled } }
    }

    fun setAnimationOverrideEnabled(enabled: Boolean) {
        _animationOverrideEnabled.value = enabled
        viewModelScope.launch { store.edit { it[KEY_ANIMATION_OVERRIDE] = enabled } }
    }

    fun setSplashIcon(enabled: Boolean) {
        _splashIcon.value = enabled
        viewModelScope.launch { store.edit { it[KEY_SPLASH_ICON] = enabled } }
    }

    fun setSplashDelay(ms: Int) {
        _splashDelay.value = ms.coerceIn(300, 1500)
        viewModelScope.launch { store.edit { it[KEY_SPLASH_DELAY] = _splashDelay.value } }
    }

    fun setAppOrder(order: List<String>) {
        _appOrder.value = order
        appRepository.setCustomOrder(order)
        viewModelScope.launch { store.edit { it[KEY_APP_ORDER] = order.joinToString(",") } }
    }

    fun setHoneycombCols(cols: Int) {
        _honeycombCols.value = cols.coerceIn(3, 6)
        viewModelScope.launch { store.edit { it[KEY_HONEYCOMB_COLS] = _honeycombCols.value } }
    }

    fun setHoneycombTopBlur(value: Int) {
        _honeycombTopBlur.value = value.coerceIn(0, 48)
        viewModelScope.launch { store.edit { it[KEY_HONEYCOMB_TOP_BLUR] = _honeycombTopBlur.value } }
    }

    fun setHoneycombBottomBlur(value: Int) {
        _honeycombBottomBlur.value = value.coerceIn(0, 48)
        viewModelScope.launch { store.edit { it[KEY_HONEYCOMB_BOTTOM_BLUR] = _honeycombBottomBlur.value } }
    }

    fun setHoneycombTopFade(value: Int) {
        _honeycombTopFade.value = value.coerceIn(0, 160)
        viewModelScope.launch { store.edit { it[KEY_HONEYCOMB_TOP_FADE] = _honeycombTopFade.value } }
    }

    fun setHoneycombBottomFade(value: Int) {
        _honeycombBottomFade.value = value.coerceIn(0, 160)
        viewModelScope.launch { store.edit { it[KEY_HONEYCOMB_BOTTOM_FADE] = _honeycombBottomFade.value } }
    }

    fun setShowNotification(show: Boolean) {
        _showNotification.value = show
        viewModelScope.launch { store.edit { it[KEY_SHOW_NOTIFICATION] = show } }
    }

    fun setAppHidden(componentKey: String, hidden: Boolean) {
        val next = _hiddenApps.value.toMutableSet().apply {
            if (hidden) add(componentKey) else remove(componentKey)
        }
        _hiddenApps.value = next
        appRepository.setHiddenComponents(next)
        viewModelScope.launch {
            store.edit {
                if (next.isEmpty()) it.remove(KEY_HIDDEN_APPS)
                else it[KEY_HIDDEN_APPS] = next.joinToString(",")
            }
        }
    }

    fun refreshIconPacks() {
        _availableIconPacks.value = IconPackScanner.scanInstalled(getApplication())
    }

    fun setIconPackPackage(packageName: String?) {
        _selectedIconPackPackage.value = packageName?.takeIf { it.isNotBlank() }
        appRepository.setIconPackPackage(_selectedIconPackPackage.value)
        viewModelScope.launch {
            store.edit {
                if (_selectedIconPackPackage.value.isNullOrBlank()) {
                    it.remove(KEY_ICON_PACK_PACKAGE)
                } else {
                    it[KEY_ICON_PACK_PACKAGE] = _selectedIconPackPackage.value!!
                }
            }
        }
    }

    fun refreshWatchFaces() {
        viewModelScope.launch {
            val scanned = LunchWatchFaceScanner.builtInDescriptors() + LunchWatchFaceScanner.scanInstalled(getApplication())
            _availableWatchFaces.value = scanned
            LunchWatchFaceRegistry.update(scanned)
            watchFaceScanHydrated = true
            syncSelectedWatchFace()
        }
    }

    fun selectWatchFace(id: String) {
        _selectedWatchFaceId.value = id
        syncSelectedWatchFace()
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
        viewModelScope.launch { store.edit { it[KEY_SELECTED_WATCHFACE_ID] = id } }
    }

    fun fallbackToBuiltIn(error: String? = null) {
        _selectedWatchFaceId.value = BUILT_IN_WATCHFACE_ID
        _watchFaceLastError.value = error
        syncSelectedWatchFace()
        viewModelScope.launch {
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
        viewModelScope.launch { store.edit { it.remove(KEY_LAST_WATCHFACE_ERROR) } }
    }

    fun setBuiltInPhotoPath(path: String?) {
        _builtInPhotoPath.value = path
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
        viewModelScope.launch {
            store.edit {
                if (path.isNullOrBlank()) it.remove(KEY_BUILTIN_PHOTO_PATH)
                else it[KEY_BUILTIN_PHOTO_PATH] = path
            }
        }
    }

    fun setBuiltInVideoPath(path: String?) {
        _builtInVideoPath.value = path
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
        viewModelScope.launch {
            store.edit {
                if (path.isNullOrBlank()) it.remove(KEY_BUILTIN_VIDEO_PATH)
                else it[KEY_BUILTIN_VIDEO_PATH] = path
            }
        }
    }

    fun setBuiltInPhotoClockPosition(position: WatchClockPosition) {
        _builtInPhotoClockPosition.value = position
        viewModelScope.launch { store.edit { it[KEY_PHOTO_CLOCK_POSITION] = position.name } }
    }

    fun setBuiltInVideoClockPosition(position: WatchClockPosition) {
        _builtInVideoClockPosition.value = position
        viewModelScope.launch { store.edit { it[KEY_VIDEO_CLOCK_POSITION] = position.name } }
    }

    fun setBuiltInPhotoClockSize(sizeSp: Int) {
        _builtInPhotoClockSize.value = sizeSp.coerceIn(28, 92)
        viewModelScope.launch { store.edit { it[KEY_PHOTO_CLOCK_SIZE] = _builtInPhotoClockSize.value } }
    }

    fun setBuiltInVideoClockSize(sizeSp: Int) {
        _builtInVideoClockSize.value = sizeSp.coerceIn(28, 92)
        viewModelScope.launch { store.edit { it[KEY_VIDEO_CLOCK_SIZE] = _builtInVideoClockSize.value } }
    }

    fun setBuiltInVideoFillScreen(fillScreen: Boolean) {
        _builtInVideoFillScreen.value = fillScreen
        viewModelScope.launch { store.edit { it[KEY_VIDEO_FILL_SCREEN] = fillScreen } }
    }

    fun requestWatchFaceRefresh() {
        _watchFaceRefreshToken.value = _watchFaceRefreshToken.value + 1
    }

    fun swapApps(fromIndex: Int, toIndex: Int) {
        val current = apps.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            setAppOrder(current.map { it.componentKey })
        }
    }

    fun resetSettings() {
        _layoutMode.value = LayoutMode.Honeycomb
        _blurEnabled.value = true
        _edgeBlurEnabled.value = false
        _lowResIcons.value = false
        _animationOverrideEnabled.value = true
        _splashIcon.value = true
        _splashDelay.value = 500
        _honeycombCols.value = 4
        _honeycombTopBlur.value = 4
        _honeycombBottomBlur.value = 4
        _honeycombTopFade.value = 56
        _honeycombBottomFade.value = 56
        _showNotification.value = false
        _hiddenApps.value = emptySet()
        _selectedIconPackPackage.value = null
        _selectedWatchFaceId.value = BUILT_IN_WATCHFACE_ID
        _selectedWatchFace.value = LunchWatchFaceScanner.builtInDescriptor(BUILT_IN_WATCHFACE_ID)
        _watchFaceLastError.value = null
        _builtInPhotoClockPosition.value = WatchClockPosition.CENTER
        _builtInVideoClockPosition.value = WatchClockPosition.CENTER
        _builtInPhotoClockSize.value = 64
        _builtInVideoClockSize.value = 64
        _builtInVideoFillScreen.value = true
        appRepository.setHiddenComponents(emptySet())
        appRepository.setIconPackPackage(null)
        LunchWatchFaceRegistry.setCurrentSelectedId(BUILT_IN_WATCHFACE_ID)
        refreshIcons()
        viewModelScope.launch {
            store.edit {
                it[KEY_LAYOUT] = LayoutMode.Honeycomb.name
                it[KEY_BLUR] = true
                it[KEY_EDGE_BLUR] = false
                it[KEY_LOW_RES] = false
                it[KEY_ANIMATION_OVERRIDE] = true
                it[KEY_SPLASH_ICON] = true
                it[KEY_SPLASH_DELAY] = 500
                it[KEY_HONEYCOMB_COLS] = 4
                it[KEY_HONEYCOMB_TOP_BLUR] = 4
                it[KEY_HONEYCOMB_BOTTOM_BLUR] = 4
                it[KEY_HONEYCOMB_TOP_FADE] = 56
                it[KEY_HONEYCOMB_BOTTOM_FADE] = 56
                it[KEY_SHOW_NOTIFICATION] = false
                it.remove(KEY_HIDDEN_APPS)
                it.remove(KEY_ICON_PACK_PACKAGE)
                it[KEY_SELECTED_WATCHFACE_ID] = BUILT_IN_WATCHFACE_ID
                it[KEY_PHOTO_CLOCK_POSITION] = WatchClockPosition.CENTER.name
                it[KEY_VIDEO_CLOCK_POSITION] = WatchClockPosition.CENTER.name
                it[KEY_PHOTO_CLOCK_SIZE] = 64
                it[KEY_VIDEO_CLOCK_SIZE] = 64
                it[KEY_VIDEO_FILL_SCREEN] = true
                it.remove(KEY_LAST_WATCHFACE_ERROR)
            }
        }
    }

    private fun parseClockPosition(value: String): WatchClockPosition =
        runCatching { WatchClockPosition.valueOf(value) }.getOrDefault(WatchClockPosition.CENTER)

    private fun syncSelectedWatchFace() {
        val requestedId = _selectedWatchFaceId.value.ifBlank { BUILT_IN_WATCHFACE_ID }
        val available = _availableWatchFaces.value
        val match = available.firstOrNull { it.id == requestedId }

        when {
            match != null -> {
                _selectedWatchFace.value = match
                LunchWatchFaceRegistry.setCurrentSelectedId(match.id)
            }
            watchFaceScanHydrated -> {
                val fallback = available.firstOrNull { it.id == BUILT_IN_WATCHFACE_ID }
                    ?: available.firstOrNull()
                    ?: LunchWatchFaceScanner.builtInDescriptor(BUILT_IN_WATCHFACE_ID)
                _selectedWatchFace.value = fallback
                _selectedWatchFaceId.value = fallback.id
                LunchWatchFaceRegistry.setCurrentSelectedId(fallback.id)
                if (watchFacePrefsHydrated && requestedId != fallback.id) {
                    viewModelScope.launch {
                        store.edit { it[KEY_SELECTED_WATCHFACE_ID] = fallback.id }
                    }
                }
            }
            else -> {
                _selectedWatchFace.value = LunchWatchFaceScanner.builtInDescriptor(BUILT_IN_WATCHFACE_ID)
                LunchWatchFaceRegistry.setCurrentSelectedId(requestedId)
            }
        }

        _watchFaceSelectionReady.value = watchFacePrefsHydrated && watchFaceScanHydrated
    }

    private fun refreshIcons() {
        appRepository.refresh(if (_lowResIcons.value) 64 else 128)
    }

    override fun onCleared() {
        super.onCleared()
        appRepository.destroy()
    }
}
