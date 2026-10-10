package com.flue.launcher

import androidx.compose.ui.res.stringResource
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.i18n.FlueLocaleManager


import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import com.flue.launcher.ui.settings.miuix.SettingsIcon
import com.flue.launcher.ui.settings.SettingsSearchEntry
import com.flue.launcher.ui.settings.SettingsSearchField
import com.flue.launcher.ui.settings.searchSettingsEntries
import com.flue.launcher.ui.store.WatchStoreBaseUrlDialog
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueSingleChoiceDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.logging.FlueLog
import com.flue.launcher.backup.FlueBackupExportSummary
import com.flue.launcher.backup.FlueBackupOptions
import com.flue.launcher.backup.FlueBackupPreview
import com.flue.launcher.service.FlueAccessibilityService
import com.flue.launcher.service.FlueDeviceAdminController
import com.flue.launcher.service.ScreenLockControlMode
import com.flue.launcher.service.screenLockControlMode
import com.flue.launcher.ui.common.FlueScreenShape
import com.flue.launcher.ui.common.FlueColorPickerDialog
import com.flue.launcher.ui.common.flueColorToHex
import com.flue.launcher.applist.background.AppListBackgroundConfig
import com.flue.launcher.ui.common.FlueImageCropOverlay
import com.flue.launcher.applist.background.AppListBackgroundMode
import com.flue.launcher.applist.background.AppListBackgroundPreviewCard
import com.flue.launcher.applist.background.cropAndStoreAppListBackground
import com.flue.launcher.applist.background.rememberAppListBackgroundPainting
import com.flue.launcher.ui.common.MAX_OVERLAY_MASK_CORNER_RADIUS_DP
import com.flue.launcher.ui.common.bottomFisheyeScale
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.common.normalizeOverlayMaskCornerRadiusDp
import com.flue.launcher.ui.style.flueOverscroll
import com.flue.launcher.ui.style.flueScrollEndHaptic
import com.flue.launcher.ui.common.InternalFileBrowserHost
import com.flue.launcher.ui.common.InternalFileBrowserRequest
import com.flue.launcher.motion.LocalFlueMotionProfile
import com.flue.launcher.motion.FlueMotionProfile
import com.flue.launcher.motion.FlueMotionProfiles
import com.flue.launcher.layout.FLUE_LAYOUT_CRESCENT_BULGE_MAX
import com.flue.launcher.layout.FLUE_LAYOUT_PROFILE_NAME_MAX_LENGTH
import com.flue.launcher.layout.FlueLayoutProfiles
import com.flue.launcher.ui.style.FlueGroupDivider
import com.flue.launcher.ui.style.FlueGroupPosition
import com.flue.launcher.ui.style.FlueSlider
import com.flue.launcher.ui.style.FlueSwitch
import com.flue.launcher.ui.style.FlueSwitchIndicator
import com.flue.launcher.ui.style.flueGroupShape
import com.flue.launcher.ui.style.flueRowHorizontalPadding
import com.flue.launcher.ui.style.flueSettingsGroup
import com.flue.launcher.motion.MotionEasing
import com.flue.launcher.motion.MotionPhaseKey
import com.flue.launcher.ui.controlcenter.MusicTextSwitchAnimations
import com.flue.launcher.ui.drawer.appListPalette
import com.flue.launcher.ui.drawer.vibrateHaptic
import com.flue.launcher.ui.input.flueRotaryScrollable
import com.flue.launcher.ui.input.ROTARY_SCROLL_MULTIPLIER
import com.flue.launcher.ui.input.RotaryHapticGate
import com.flue.launcher.ui.input.requestFocusAfterFirstFrame
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.settings.WatchFaceSettingCard
import com.flue.launcher.ui.settings.agent.AgentSettingsPage
import com.flue.launcher.ui.settings.miuix.MiuixIcons
import com.flue.launcher.ui.settings.miuix.MiuixSettingsCard
import com.flue.launcher.ui.settings.miuix.MiuixSettingsRow
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideDialogUiScale
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.anim.FlueTransitionMotion
import com.flue.launcher.ui.theme.ThemeMode
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.ui.theme.RoundElementRole
import com.flue.launcher.ui.theme.roundAwareCardShape
import com.flue.launcher.ui.theme.roundAwareCompactShape
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.util.FlowCrescentDirection
import com.flue.launcher.util.flowCrescentOffsetPx
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.InternalWatchFaceStorage
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.standard.WatchDateLocale
import com.flue.launcher.watchface.standard.WatchTemperatureUnit
import com.flue.launcher.watchface.standard.WatchWeatherLocation
import com.flue.launcher.watchface.standard.WatchWeatherRepository
import com.flue.launcher.watchface.standard.WatchLocationPermissionPolicy
import com.flue.launcher.watchface.standard.hasHeartRatePermission
import com.flue.launcher.watchface.standard.requiredHeartRatePermission
import com.flue.launcher.watchface.store.WatchStoreRepository
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.flue.launcher.ui.settings.WatchTapActionAppPickerDialog
import com.flue.launcher.ui.settings.WatchTapActionBindingCard
import com.flue.launcher.ui.settings.WatchTapActionCatalog
import com.flue.launcher.ui.settings.WatchTapActionDefinition
import com.flue.launcher.ui.settings.DebugTapState
import com.flue.launcher.diagnostics.DiagnosticsSettingsSection
import com.flue.launcher.diagnostics.DiagnosticsInformationSection
import com.flue.launcher.aod.AodSupplementalNotice
import com.flue.launcher.aod.rememberAodSettingsState
import com.flue.launcher.ui.store.clearFlueWebViewData
import com.flue.launcher.watchface.store.MAX_WEB_ZOOM_PERCENT
import com.flue.launcher.watchface.store.MIN_WEB_ZOOM_PERCENT
import com.flue.launcher.watchface.store.WatchStoreUiPreferences
import com.flue.launcher.ui.style.FlueLoadingIndicator
import com.flue.launcher.watchface.selection.WatchFaceLongPressMode
import top.yukonga.miuix.kmp.icon.MiuixIcons as OfficialMiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add as OfficialAdd
import top.yukonga.miuix.kmp.icon.extended.Back as OfficialBack
import top.yukonga.miuix.kmp.icon.extended.Carrier as OfficialCarrier
import top.yukonga.miuix.kmp.icon.extended.Folder as OfficialFolder
import top.yukonga.miuix.kmp.icon.extended.Refresh as OfficialRefresh
import top.yukonga.miuix.kmp.icon.extended.Settings as OfficialSettings
import top.yukonga.miuix.kmp.icon.extended.Ok as OfficialOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.abs

private val ABOUT_VERSION = BuildConfig.VERSION_NAME
private data class OpenSourceProject(val name: String, val url: String)
private val OPEN_SOURCE_PROJECTS = listOf(
    OpenSourceProject("compose-miuix-ui/miuix", "https://github.com/compose-miuix-ui/miuix"),
    OpenSourceProject("ailife8881/wftools", "https://gitlab.com/ailife8881/wftools"),
    OpenSourceProject("ailife8881/clox", "https://gitlab.com/ailife8881/clox"),
    OpenSourceProject(
        "ailife8881/watchmaker-community-store",
        "https://gitlab.com/ailife8881/watchmaker-community-store",
    ),
    OpenSourceProject(
        "ailife8881/watchmaker-definition",
        "https://gitlab.com/ailife8881/watchmaker-definition",
    ),
    OpenSourceProject("FFmpeg", "https://ffmpeg.org"),
)
// 两个交流群。第二个是后来开的，容量满了才会用到，所以入口做成「点一下问你进哪个」
// 而不是并排两张卡 —— 关于页在 230dp 圆屏上已经很挤，多一张卡就要多滚一屏。
private const val COMMUNITY_GROUP_NUMBER = "1097162313"
private const val COMMUNITY_GROUP_2_NUMBER = "318567840"
private const val AUTHOR_QQ_NUMBER = "3513903055"
private const val AUTHOR_BILIBILI_URL = "https://m.bilibili.com/space/1609437970"
private const val DIAGNOSTIC_LOG_DURATION_MS = 5 * 60 * 1000L
private const val COMMUNITY_GROUP_URL = "https://qun.qq.com/universal-share/share?ac=1&authKey=5CJC0tNLWsy3YWzGlPbqt%2F5kv%2BYZuJ8y8IVj%2B1UnIeLyR2DWR6QjWtM%2B4HXxH%2BKJ&busi_data=eyJncm91cENvZGUiOiIxMDk3MTYyMzEzIiwidG9rZW4iOiJlMGFBc1VpRXRROE1mS3J5SHgxRjFudmxXY0FuSnRQd2hOWWl6WllFMmlxNTErNWdadXJ5U1ozMmdzUSszaGNYIiwidWluIjoiMzUxMzkwMzA1NSJ9&data=YK52b80xUVwmcLWmSneKQMdVZodE4vTpsqmSb60ykXudbVCfa6AskMHxvqjhAjervNeE4exll-kQw5w-EifYOg&svctype=4&tempid=h5_group_info"
private const val COMMUNITY_GROUP_2_URL = "https://qun.qq.com/universal-share/share?ac=1&authKey=spcEQr%2BR5%2FAmPOgZLwr7KBY8lUvLLbmtdM5S3R0%2B5XQxXstNLi9Mva0iYjSEb9EF&busi_data=eyJncm91cENvZGUiOiIzMTg1Njc4NDAiLCJ0b2tlbiI6IklKWS9Oem5rTnprYzNGRk11b2Y2enJsYndmcnBSbkxKR3VCTG5iZU5hT002dUgwY2UrVWtjNko1eENqQUc2eVYiLCJ1aW4iOiIzNTEzOTAzMDU1In0%3D&data=BznFA0OAqJXrk3P5fs7aS_sBabcZjAqFIwZhPfvGtg2uwsc1DBW5f13txNH0dG_mOJBpV3N40r1Bw6vL3wBr7g&svctype=4&tempid=h5_group_info"
const val EXTRA_SETTINGS_DESTINATION = "settings_destination"
const val EXTRA_SETTINGS_RETURN_TO_FACE = "settings_return_to_face"
const val SETTINGS_DESTINATION_WATCH_FACES = "watch_faces"
const val SETTINGS_DESTINATION_TOOLS = "tools"
const val SETTINGS_DESTINATION_AGENT = "agent"
private val LocalSettingsLeftSafeInsetPercent = compositionLocalOf { 0 }
private val LocalSettingsRotaryHapticsEnabled = compositionLocalOf { true }

/**
 * 搜索跳转目标。[token] 每次点击自增，用来让同一个 key 也能重新触发定位。
 * [onConsumed] 由 scaffold 在定位完成后回调，避免返回上一页时又跳一次。
 */
private data class SettingsSearchTarget(
    val itemKey: String,
    val token: Int,
    val onConsumed: () -> Unit,
)

private val LocalSettingsSearchTarget = compositionLocalOf<SettingsSearchTarget?> { null }

enum class SettingsDestination {
    ROOT,
    WATCH_FACES,
    AGENT,
    WATCH_FACE_MORE,
    TAP_ACTION_CATEGORIES,
    TAP_ACTION_CATEGORY,
    APP_LIST_BACKGROUND,
    HIDDEN_APPS,
    ICON_PACKS,
    APPEARANCE,
    DISPLAY_SIZE,
    AOD,
    FEATURE_TOGGLES,
    SIDE_SCREEN,
    NOTIFICATION_SETTINGS,
    CONTROL_CENTER_SETTINGS,
    GESTURE_SETTINGS,
    APP_LIST_BEHAVIOR,
    PERFORMANCE,
    PERFORMANCE_RENDERING,
    PERFORMANCE_MOTION,
    PERFORMANCE_SYSTEM,
    MOTION_PROFILE,
    MUSIC_TEXT_ANIMATION,
    TOOLS,
    BACKUP,
    BACKUP_EXPORT,
    BACKUP_IMPORT,
    LANGUAGE,
    ABOUT,
    DEBUG,
    DONATE
}

/**
 * 内置文件选择器给背景图用的扩展名白名单。
 *
 * 与 `InternalFileBrowser` 自己那份 `INTERNAL_IMAGE_EXTENSIONS`（private）刻意分开写：
 * 那份是「能出缩略图的类型」，这份是「`BitmapRegionDecoder` 能区域裁剪的类型」。gif 不在
 * 这里 —— 它能出缩略图，但裁出来只会是第一帧，当背景是个意外结果。
 */
private val APP_LIST_BACKGROUND_IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif",
)

/** 「更多表盘选项」里那一行的副标题：当前生效的是哪一档。 */
@Composable
private fun appListBackgroundModeLabel(mode: AppListBackgroundMode): String = stringResource(
    when (mode) {
        AppListBackgroundMode.SOLID_SYSTEM -> R.string.settings_app_list_background_solid_system
        AppListBackgroundMode.SOLID_CUSTOM -> R.string.settings_app_list_background_solid_custom
        AppListBackgroundMode.WATCH_FACE -> R.string.settings_app_list_background_watch_face
        AppListBackgroundMode.CUSTOM_IMAGE -> R.string.settings_app_list_background_custom_image
    },
)

private data class SettingsLaunchConfig(
    val initialDestination: SettingsDestination = SettingsDestination.ROOT,
    val returnToFaceOnBack: Boolean = false,
    val requestId: Long = 0L
)

private data class BlockingProgressState(
    val title: String,
    /** 副标题。为空时只显示标题（导出完成不再回显文件路径）。 */
    val detail: String? = null,
    val completed: Boolean = false,
    val completionAction: BlockingProgressCompletionAction = BlockingProgressCompletionAction.None,
    val shareUri: Uri? = null
)

private enum class BlockingProgressCompletionAction {
    None,
    ExportBackup,
    ImportBackup,
    WatchFaceArchiveImport
}


class SettingsActivity : FlueComponentActivity() {
    private var launchRequestId = 0L
    private var launchConfig by mutableStateOf(SettingsLaunchConfig())
    private var appliedWindowBackgroundArgb: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySettingsEdgeToEdge(
            darkTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES,
        )
        RecentsVisibility.apply(this)
        launchConfig = intent.readSettingsLaunchConfig()
        setContent {
            val viewModel: LauncherViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val uiStyle by viewModel.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by viewModel.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by viewModel.screenShapeOverride.collectAsStateWithLifecycle()
            val systemInDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemInDarkTheme
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                val windowBackgroundArgb = LauncherTheme.style.screenBackground.toArgb()
                SideEffect {
                    applySettingsEdgeToEdge(
                        darkTheme = darkTheme,
                        windowBackgroundArgb = windowBackgroundArgb,
                    )
                }
                ProvideGlobalUiScale(globalUiScalePercent) {
                    SettingsRootScreen(
                        onFinish = { finish() },
                        launchConfig = launchConfig
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applySettingsEdgeToEdge(
        darkTheme: Boolean,
        windowBackgroundArgb: Int = if (darkTheme) AndroidColor.BLACK else 0xFFF7F7F7.toInt(),
    ) {
        // Settings draws its own opaque theme background. Keep the window edge-to-edge so
        // transparent status/navigation bars reveal that background instead of the theme's
        // black windowBackground. WindowCompat also works for gesture and three-button nav.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (appliedWindowBackgroundArgb != windowBackgroundArgb) {
            window.setBackgroundDrawable(ColorDrawable(windowBackgroundArgb))
            appliedWindowBackgroundArgb = windowBackgroundArgb
        }
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = AndroidColor.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchConfig = intent.readSettingsLaunchConfig()
    }

    private fun Intent.readSettingsLaunchConfig(): SettingsLaunchConfig {
        launchRequestId += 1
        val initialDestination = when (getStringExtra(EXTRA_SETTINGS_DESTINATION)) {
            SETTINGS_DESTINATION_WATCH_FACES -> SettingsDestination.WATCH_FACES
            SETTINGS_DESTINATION_TOOLS -> SettingsDestination.TOOLS
            SETTINGS_DESTINATION_AGENT -> SettingsDestination.AGENT
            else -> SettingsDestination.ROOT
        }
        return SettingsLaunchConfig(
            initialDestination = initialDestination,
            returnToFaceOnBack = getBooleanExtra(EXTRA_SETTINGS_RETURN_TO_FACE, false),
            requestId = launchRequestId
        )
    }
}

private fun initialSettingsBackStack(
    initialDestination: SettingsDestination,
    returnToFaceOnBack: Boolean
): List<SettingsDestination> {
    return if (initialDestination != SettingsDestination.ROOT && !returnToFaceOnBack) {
        listOf(SettingsDestination.ROOT)
    } else {
        emptyList()
    }
}

@Composable
private fun SettingsRootScreen(
    onFinish: () -> Unit,
    launchConfig: SettingsLaunchConfig = SettingsLaunchConfig()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsScope = rememberCoroutineScope()
    val vm: LauncherViewModel = viewModel()
    val weatherRepository = remember(context) { WatchWeatherRepository.default(context) }
    var weatherLocationLabel by remember(weatherRepository) {
        mutableStateOf(weatherRepository.activeLocationLabel().orEmpty())
    }
    var weatherUsingSystemLocation by remember(weatherRepository) {
        mutableStateOf(weatherRepository.isUsingSystemLocation())
    }
    var weatherTemperatureUnit by remember(weatherRepository) {
        mutableStateOf(weatherRepository.temperatureUnit())
    }
    var weatherLocationSelectionGeneration by remember { mutableStateOf(0L) }
    var weatherCityDialogVisible by remember { mutableStateOf(false) }
    var watchFaceLongPressModeDialogVisible by remember { mutableStateOf(false) }
    var weatherCityDraft by remember { mutableStateOf("") }
    var weatherCityCandidates by remember { mutableStateOf<List<WatchWeatherLocation>>(emptyList()) }
    var weatherCitySearching by remember { mutableStateOf(false) }
    var weatherCitySearchError by remember { mutableStateOf<String?>(null) }
    var debugTapState by remember { mutableStateOf(DebugTapState()) }
    var internalFileBrowserRequest by remember { mutableStateOf<InternalFileBrowserRequest?>(null) }
    val rawWatchFaces by vm.availableWatchFaces.collectAsStateWithLifecycle()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsStateWithLifecycle()
    val rawSelectedWatchFace by vm.selectedWatchFace.collectAsStateWithLifecycle()
    val watchCatalogRepository = remember(context) { WatchStoreRepository(context) }
    var watchCatalogReloadToken by remember { mutableStateOf(0) }
    val watchStoreUiPreferences = remember(context) { WatchStoreUiPreferences(context) }
    var customWatchStoreUrlDialogVisible by remember { mutableStateOf(false) }
    // H6：缩放值存在商店那座 UI 偏好小岛里（它本来就是「商店本地 UI 偏好」的归属），
    // 这里只留一份界面镜像；网页宿主 onResume 会自己回读并对当前页 reload。
    var webZoomPercent by remember { mutableStateOf(watchStoreUiPreferences.readWebZoomPercent()) }
    var webZoomDialogVisible by remember { mutableStateOf(false) }
    var tapActionCategoryId by remember { mutableStateOf<String?>(null) }
    var tapActionPicker by remember { mutableStateOf<WatchTapActionDefinition?>(null) }
    var tapActionRevision by remember { mutableStateOf(0) }
    var clearWebDataDialogVisible by remember { mutableStateOf(false) }
    var watchCatalogDisplayNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(watchCatalogRepository, watchCatalogReloadToken) {
        val snapshot = runCatching { watchCatalogRepository.readCatalog() }.getOrNull()
        watchCatalogDisplayNames = if (snapshot?.diagnostic == null) {
            snapshot?.records.orEmpty().mapNotNull { record ->
                val launcherId = record.activeLauncherIdOrNull() ?: return@mapNotNull null
                val displayName = record.resolvedDisplayName() ?: return@mapNotNull null
                launcherId to displayName
            }.toMap()
        } else {
            emptyMap()
        }
    }
    val watchFaces = remember(rawWatchFaces, watchCatalogDisplayNames) {
        rawWatchFaces.map { descriptor ->
            watchCatalogDisplayNames[descriptor.id]
                ?.takeIf { it != descriptor.displayName }
                ?.let { descriptor.copy(displayName = it) }
                ?: descriptor
        }
    }
    val selectedWatchFace = remember(rawSelectedWatchFace, watchCatalogDisplayNames) {
        watchCatalogDisplayNames[rawSelectedWatchFace.id]
            ?.takeIf { it != rawSelectedWatchFace.displayName }
            ?.let { rawSelectedWatchFace.copy(displayName = it) }
            ?: rawSelectedWatchFace
    }
    val allSelectableApps by vm.allSelectableApps.collectAsStateWithLifecycle()
    val hiddenApps by vm.hiddenApps.collectAsStateWithLifecycle()
    val availableIconPacks by vm.availableIconPacks.collectAsStateWithLifecycle()
    val selectedIconPackPackage by vm.selectedIconPackPackage.collectAsStateWithLifecycle()


    val watchFaceLastError by vm.watchFaceLastError.collectAsStateWithLifecycle()
    val layoutMode by vm.layoutMode.collectAsStateWithLifecycle()
    val sideScreenEnabled by vm.sideScreenEnabled.collectAsStateWithLifecycle()
    val blurEnabled by vm.blurEnabled.collectAsStateWithLifecycle()
    val edgeBlurEnabled by vm.edgeBlurEnabled.collectAsStateWithLifecycle()
    val lowResIcons by vm.lowResIcons.collectAsStateWithLifecycle()
    val animationOverrideEnabled by vm.animationOverrideEnabled.collectAsStateWithLifecycle()
    val splashIcon by vm.splashIcon.collectAsStateWithLifecycle()
    val splashDelay by vm.splashDelay.collectAsStateWithLifecycle()
    val directLaunchAppListEnabled by vm.directLaunchAppListEnabled.collectAsStateWithLifecycle()
    val honeycombCols by vm.honeycombCols.collectAsStateWithLifecycle()
    val honeycombEdgeBlurRadius by vm.honeycombEdgeBlurRadius.collectAsStateWithLifecycle()
    val honeycombTopFade by vm.honeycombTopFade.collectAsStateWithLifecycle()
    val honeycombBottomFade by vm.honeycombBottomFade.collectAsStateWithLifecycle()
    val appListFisheyeEnabled by vm.appListFisheyeEnabled.collectAsStateWithLifecycle()
    val materialHoneycombTopFisheyeEnabled by vm.materialHoneycombTopFisheyeEnabled.collectAsStateWithLifecycle()
    val appListFisheyeRangeRows by vm.appListFisheyeRangeRows.collectAsStateWithLifecycle()
    val appListFisheyeStrengthPercent by vm.appListFisheyeStrengthPercent.collectAsStateWithLifecycle()
    val appListEdgeSpacingCompressionEnabled by vm.appListEdgeSpacingCompressionEnabled.collectAsStateWithLifecycle()
    val appListLeftSafeInsetPercent by vm.appListLeftSafeInsetPercent.collectAsStateWithLifecycle()
    val appListFlowCrescentBulgePercent by vm.appListFlowCrescentBulgePercent.collectAsStateWithLifecycle()
    val appListFlowCrescentToRight by vm.appListFlowCrescentToRight.collectAsStateWithLifecycle()
    val layoutProfiles by vm.layoutProfiles.collectAsStateWithLifecycle()
    val activeLayoutProfile by vm.activeLayoutProfile.collectAsStateWithLifecycle()
    val appListScalePercent by vm.appListScalePercent.collectAsStateWithLifecycle()
    val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
    val overlayMaskCornerRadiusDp by vm.overlayMaskCornerRadiusDp.collectAsStateWithLifecycle()
    val appListWatchFaceColors by vm.appListWatchFaceColors.collectAsStateWithLifecycle()
    val appListRowBorderEnabled by vm.appListRowBorderEnabled.collectAsStateWithLifecycle()
    val appListGridIconPlateEnabled by vm.appListGridIconPlateEnabled.collectAsStateWithLifecycle()
    val appListFoldersEnabled by vm.appListFoldersEnabled.collectAsStateWithLifecycle()
    val appListScrollIndicatorEnabled by
        vm.appListScrollIndicatorEnabled.collectAsStateWithLifecycle()
    val musicTextSwitchAnimation by vm.musicTextSwitchAnimation.collectAsStateWithLifecycle()
    val iconShadowEnabled by vm.iconShadowEnabled.collectAsStateWithLifecycle()
    val appListReturnShrinkEnabled by vm.appListReturnShrinkEnabled.collectAsStateWithLifecycle()
    val legacyAppListTransitionEnabled by vm.legacyAppListTransitionEnabled.collectAsStateWithLifecycle()
    val appListReturnIconFloatEnabled by vm.appListReturnIconFloatEnabled.collectAsStateWithLifecycle()
    val reduceDynamicEffects by vm.reduceDynamicEffects.collectAsStateWithLifecycle()
    val screenShapeOverride by vm.screenShapeOverride.collectAsStateWithLifecycle()
    val appListStaggerEnabled by vm.appListStaggerEnabled.collectAsStateWithLifecycle()
    val faceReturnShrinkEnabled by vm.faceReturnShrinkEnabled.collectAsStateWithLifecycle()
    val activeMotionProfile by vm.activeMotionProfile.collectAsStateWithLifecycle()
    // 档案重命名弹窗（老师报：复制出来全叫副本，而且没法改名）。
    var renameLayoutProfileVisible by remember { mutableStateOf(false) }
    var renameMotionProfileVisible by remember { mutableStateOf(false) }
    val motionProfiles by vm.motionProfiles.collectAsStateWithLifecycle()
    val showStepCount by vm.showStepCount.collectAsStateWithLifecycle()
    val builtInPhotoPath by vm.builtInPhotoPath.collectAsStateWithLifecycle()
    val builtInVideoPath by vm.builtInVideoPath.collectAsStateWithLifecycle()
    val builtInPhotoClockPosition by vm.builtInPhotoClockPosition.collectAsStateWithLifecycle()
    val builtInVideoClockPosition by vm.builtInVideoClockPosition.collectAsStateWithLifecycle()
    val builtInPhotoClockSize by vm.builtInPhotoClockSize.collectAsStateWithLifecycle()
    val builtInVideoClockSize by vm.builtInVideoClockSize.collectAsStateWithLifecycle()
    val builtInPhotoClockBold by vm.builtInPhotoClockBold.collectAsStateWithLifecycle()
    val builtInVideoClockBold by vm.builtInVideoClockBold.collectAsStateWithLifecycle()
    val builtInVideoFillScreen by vm.builtInVideoFillScreen.collectAsStateWithLifecycle()
    val builtInVideoClockColorMode by vm.builtInVideoClockColorMode.collectAsStateWithLifecycle()
    val builtInWatchFaceFontPath by vm.builtInWatchFaceFontPath.collectAsStateWithLifecycle()
    val builtInPhotoClockStyle by vm.builtInPhotoClockStyle.collectAsStateWithLifecycle()
    val builtInVideoClockStyle by vm.builtInVideoClockStyle.collectAsStateWithLifecycle()
    val builtInPhotoMd3eShape by vm.builtInPhotoMd3eShape.collectAsStateWithLifecycle()
    val builtInVideoMd3eShape by vm.builtInVideoMd3eShape.collectAsStateWithLifecycle()
    val builtInPhotoUseThemeTextColor by vm.builtInPhotoUseThemeTextColor.collectAsStateWithLifecycle()
    val builtInVideoUseThemeTextColor by vm.builtInVideoUseThemeTextColor.collectAsStateWithLifecycle()
    val builtInPhotoTextColorArgb by vm.builtInPhotoTextColorArgb.collectAsStateWithLifecycle()
    val builtInVideoTextColorArgb by vm.builtInVideoTextColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eAutoColors by vm.builtInPhotoMd3eAutoColors.collectAsStateWithLifecycle()
    val builtInVideoMd3eAutoColors by vm.builtInVideoMd3eAutoColors.collectAsStateWithLifecycle()
    val builtInPhotoMd3eTextColorArgb by vm.builtInPhotoMd3eTextColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eTextColorArgb by vm.builtInVideoMd3eTextColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eFaceColorArgb by vm.builtInPhotoMd3eFaceColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eFaceColorArgb by vm.builtInVideoMd3eFaceColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eHourColorArgb by vm.builtInPhotoMd3eHourColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eHourColorArgb by vm.builtInVideoMd3eHourColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eMinuteColorArgb by vm.builtInPhotoMd3eMinuteColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eMinuteColorArgb by vm.builtInVideoMd3eMinuteColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eSecondColorArgb by vm.builtInPhotoMd3eSecondColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eSecondColorArgb by vm.builtInVideoMd3eSecondColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoShowSeconds by vm.builtInPhotoShowSeconds.collectAsStateWithLifecycle()
    val builtInVideoShowSeconds by vm.builtInVideoShowSeconds.collectAsStateWithLifecycle()
    val builtInPhotoCustomText by vm.builtInPhotoCustomText.collectAsStateWithLifecycle()
    val builtInVideoCustomText by vm.builtInVideoCustomText.collectAsStateWithLifecycle()
    val builtInManagerThumbnails by vm.builtInManagerThumbnails.collectAsStateWithLifecycle()
    val hideFromRecents by vm.hideFromRecents.collectAsStateWithLifecycle()
    val watchStoreSegmentedDownload by vm.watchStoreSegmentedDownload.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
    // 转场动画风格独立于界面风格（老师定）：这一页要同时读两者。
    val transitionMotion by vm.transitionMotion.collectAsStateWithLifecycle()
    val launcherStyle = LauncherTheme.style
    val flueScreenRound = isFlueScreenRound()
    val showNotification by vm.showNotification.collectAsStateWithLifecycle()
    val showOngoingNotifications by vm.showOngoingNotifications.collectAsStateWithLifecycle()
    val watchFaceTapHapticsEnabled by vm.watchFaceTapHapticsEnabled.collectAsStateWithLifecycle()
    val rotaryHapticsEnabled by vm.rotaryHapticsEnabled.collectAsStateWithLifecycle()
    val showWidgetPage by vm.showWidgetPage.collectAsStateWithLifecycle()
    val glassHighlightEnabled by vm.glassHighlightEnabled.collectAsStateWithLifecycle()
    val widgetGlassHighlightEnabled by vm.widgetGlassHighlightEnabled.collectAsStateWithLifecycle()
    val showControlCenter by vm.showControlCenter.collectAsStateWithLifecycle()
    val showMusicControls by vm.showMusicControls.collectAsStateWithLifecycle()
    val showMediaCustomActions by vm.showMediaCustomActions.collectAsStateWithLifecycle()
    val swapMusicNotificationComponents by vm.swapMusicNotificationComponents.collectAsStateWithLifecycle()
    val doubleTapLockScreenEnabled by vm.doubleTapLockScreenEnabled.collectAsStateWithLifecycle()
    val faceIgnoresBackKey by vm.faceIgnoresBackKey.collectAsStateWithLifecycle()
    val powerMenuButtonEnabled by vm.powerMenuButtonEnabled.collectAsStateWithLifecycle()
    val watchFaceChargingPowerText by vm.watchFaceChargingPowerText.collectAsStateWithLifecycle()
    val watchFaceTextEntryAnimation by vm.watchFaceTextEntryAnimation.collectAsStateWithLifecycle()
    val watchFaceTextExitAnimation by vm.watchFaceTextExitAnimation.collectAsStateWithLifecycle()
    val watchFaceStatusIndicators by vm.watchFaceStatusIndicators.collectAsStateWithLifecycle()
    val watchFaceDndIndicator by vm.watchFaceDndIndicator.collectAsStateWithLifecycle()
    val watchFaceBottomFadeEnabled by vm.watchFaceBottomFadeEnabled.collectAsStateWithLifecycle()
    val watchFaceLongPressMode by vm.watchFaceLongPressMode.collectAsStateWithLifecycle()
    val watchfaceGravityParallaxEnabled by vm.watchfaceGravityParallaxEnabled.collectAsStateWithLifecycle()
    val watchfaceGravityBackdropBlurEnabled by
        vm.watchfaceGravityBackdropBlurEnabled.collectAsStateWithLifecycle()
    val watchfaceDateLocale by vm.watchfaceDateLocale.collectAsStateWithLifecycle()
    val appLanguage by vm.appLanguage.collectAsStateWithLifecycle()
    // 表盘保存会原地覆盖 preview.jpg：令牌自增时列表缩略图重读文件。
    val watchFaceRefreshToken by vm.watchFaceRefreshToken.collectAsStateWithLifecycle()
    val accessibilityServiceConnected by FlueAccessibilityService.connected.collectAsStateWithLifecycle()
    var accessibilityServiceEnabled by remember {
        mutableStateOf(FlueAccessibilityService.isEnabled(context) || accessibilityServiceConnected)
    }
    val doubleTapLockMode = remember { screenLockControlMode(Build.VERSION.SDK_INT) }
    val doubleTapUsesDeviceAdmin = doubleTapLockMode == ScreenLockControlMode.DEVICE_ADMIN
    var deviceAdminEnabled by remember {
        mutableStateOf(FlueDeviceAdminController.isActive(context))
    }
    // The current settings header no longer renders a clock. Keeping a ticking
    // state here used to invalidate the whole active page every 30 seconds.
    val headerTime = ""
    var backupExportOptions by remember { mutableStateOf(FlueBackupOptions()) }
    var pendingBackupExportOptions by remember { mutableStateOf<FlueBackupOptions?>(null) }
    var backupImportUri by remember { mutableStateOf<Uri?>(null) }
    var backupImportPreview by remember { mutableStateOf<FlueBackupPreview?>(null) }
    var backupImportOptions by remember { mutableStateOf(FlueBackupOptions()) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupProgress by remember { mutableStateOf<BlockingProgressState?>(null) }
    var bodySensorsPermissionGranted by remember(context) {
        mutableStateOf(hasBodySensorsPermission(context))
    }
    val bodySensorsPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        bodySensorsPermissionGranted = granted || hasBodySensorsPermission(context)
        if (!bodySensorsPermissionGranted) {
            val canAskAgain = (context as? Activity)?.let { activity ->
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    requiredHeartRatePermission(),
                )
            } == true
            if (canAskAgain) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_body_sensors_permission_denied),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_body_sensors_permission_settings),
                    Toast.LENGTH_LONG,
                ).show()
                openHeartRatePermissionSettings(context)
            }
        }
    }
    var locationPermissionGranted by remember(context) {
        mutableStateOf(WatchLocationPermissionPolicy.isGranted(context))
    }
    // 表盘日程（{cex} / {cNxx}）。与心率完全同一套：只有用户主动点这一行才申请
    // READ_CALENDAR，没授权时那些标签渲染 `--`，一次查询都不会发。
    var calendarPermissionGranted by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        calendarPermissionGranted = granted ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (!calendarPermissionGranted) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_calendar_permission_denied),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    fun resolveAndUseSystemWeatherLocation() {
        val selectionGeneration = ++weatherLocationSelectionGeneration
        settingsScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { weatherRepository.resolveSystemLocation() }.getOrNull()
            }
            if (selectionGeneration != weatherLocationSelectionGeneration) return@launch
            if (resolved != null) {
                if (!weatherRepository.useSystemLocation(resolved)) return@launch
                weatherLocationLabel = resolved.city.label.orEmpty()
                weatherUsingSystemLocation = true
                Toast.makeText(
                    context,
                    resolved.city.label?.takeIf(String::isNotBlank)?.let { label ->
                        context.getString(R.string.toast_weather_system_location_resolved_fmt, label)
                    } ?: context.getString(R.string.toast_weather_system_location),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_weather_system_location_unavailable),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        locationPermissionGranted = WatchLocationPermissionPolicy.isGranted(context)
        if (locationPermissionGranted) {
            resolveAndUseSystemWeatherLocation()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.toast_weather_location_denied),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val effectiveBackupExportOptions = backupExportOptions
    val effectiveBackupImportPreview = backupImportPreview
    val effectiveBackupImportOptions = remember(backupImportOptions, effectiveBackupImportPreview) {
        val available = effectiveBackupImportPreview?.availableOptions ?: FlueBackupOptions()
        backupImportOptions.constrainedBy(available)
    }
    val fontPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            val savedPath = InternalWatchFaceStorage.copyFont(context, uri)
            if (savedPath.isNullOrBlank()) {
                Toast.makeText(context, context.getString(R.string.toast_font_save_failed), Toast.LENGTH_SHORT).show()
            } else {
                vm.setBuiltInWatchFaceFontPath(savedPath)
                Toast.makeText(context, context.getString(R.string.toast_font_updated), Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun handleWatchFaceArchiveUri(uri: Uri?) {
        if (uri != null) {
            settingsScope.launch {
                backupBusy = true
                backupProgress = BlockingProgressState(
                    title = context.getString(R.string.progress_import_watchface),
                    detail = context.getString(R.string.progress_import_watchface_detail)
                )
                val result = vm.importWatchFaceArchive(uri = uri)
                result
                    .onSuccess { descriptor ->
                        backupProgress = BlockingProgressState(
                            title = context.getString(R.string.progress_import_done),
                            detail = descriptor.displayName,
                            completed = true,
                            completionAction = BlockingProgressCompletionAction.WatchFaceArchiveImport
                        )
                    }
                    .onFailure { error ->
                        backupProgress = null
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.toast_import_failed_fmt,
                                error.message ?: error.javaClass.simpleName
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                backupBusy = false
            }
        }
    }
    val watchFaceArchivePicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        handleWatchFaceArchiveUri(uri)
    }
    // ---- 应用列表背景 ------------------------------------------------------
    val appListBackground by vm.appListBackground.collectAsStateWithLifecycle()
    var appListBackgroundColorPickerVisible by remember { mutableStateOf(false) }
    /**
     * 拖动中的模糊 / 遮罩百分比。
     *
     * 两条滑块都是**抬手才落盘**（每帧提交等于拖动时连烘十几张模糊图），可预览卡必须跟手 ——
     * 所以拖动值单独存一份，只喂给预览。非空即「正在拖」。
     */
    var appListBackgroundBlurDrag by remember { mutableStateOf<Int?>(null) }
    var appListBackgroundScrimDrag by remember { mutableStateOf<Int?>(null) }
    // 落盘值追上拖动值之后就把覆盖清掉。不能在 onValueChangeFinished 里直接清：StateFlow 的
    // 那一跳要等一次协程调度，中间会有一帧读到旧值，预览就闪一下。
    LaunchedEffect(appListBackground.blurPercent, appListBackgroundBlurDrag) {
        if (appListBackgroundBlurDrag == appListBackground.blurPercent) {
            appListBackgroundBlurDrag = null
        }
    }
    LaunchedEffect(appListBackground.scrimPercent, appListBackgroundScrimDrag) {
        if (appListBackgroundScrimDrag == appListBackground.scrimPercent) {
            appListBackgroundScrimDrag = null
        }
    }
    /** 预览用的配置：落盘值叠上拖动中的临时值。 */
    val appListBackgroundPreviewConfig = remember(
        appListBackground,
        appListBackgroundBlurDrag,
        appListBackgroundScrimDrag,
    ) {
        appListBackground.copy(
            blurPercent = appListBackgroundBlurDrag ?: appListBackground.blurPercent,
            scrimPercent = appListBackgroundScrimDrag ?: appListBackground.scrimPercent,
        )
    }
    /** 选好、还没裁剪的图。非空即裁剪覆层可见。 */
    var appListBackgroundCropUri by remember { mutableStateOf<Uri?>(null) }
    val appListBackgroundImagePicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        // 选完先进裁剪，不直接落盘：这层图要铺满全屏，不裁就必然是「center-crop 切掉一半」。
        if (uri != null) appListBackgroundCropUri = uri
    }
    // 预览卡要和真机一模一样，所以颜色也走应用列表自己的调色板，而不是设置页的主题色。
    // `useWatchFaceColor = false` 与 LauncherActivity 里那个调用点一致（那边写死 false），
    // 因此 seed 这一参完全不参与运算，给什么都行。
    val appListPreviewDarkMode = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val appListPreviewPalette = appListPalette(
        seed = Color.Transparent,
        darkMode = appListPreviewDarkMode,
        uiStyle = uiStyle,
        useWatchFaceColor = false,
    )
    // 按**屏幕**尺寸解码而不是按卡片尺寸：缓存键里带着目标尺寸，用屏幕尺寸就能和启动器那份
    // 现成的缓存对上（老师是从列表上滑进设置的，那张图基本一定是热的），一次多余解码都不用。
    val appListPreviewViewport = with(LocalDensity.current) {
        LocalConfiguration.current.let { config ->
            config.screenWidthDp.dp.roundToPx() to config.screenHeightDp.dp.roundToPx()
        }
    }
    val backupCreatePicker = rememberLauncherForActivityResult(CreateDocument("application/zip")) { uri ->
        val options = pendingBackupExportOptions
        pendingBackupExportOptions = null
        if (uri != null && options != null) {
            settingsScope.launch {
                backupBusy = true
                backupProgress = BlockingProgressState(
                    title = context.getString(R.string.progress_export_config),
                    detail = context.getString(R.string.progress_export_config_detail)
                )
                val result = vm.exportBackup(uri, options)
                result
                    .onSuccess { summary ->
                        backupProgress = BlockingProgressState(
                            title = context.getString(R.string.progress_export_done),
                            detail = summary.exportDoneDetailText(context),
                            completed = true,
                            completionAction = BlockingProgressCompletionAction.ExportBackup,
                            shareUri = uri
                        )
                    }
                    .onFailure { error ->
                        backupProgress = null
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.toast_export_failed_fmt,
                                error.message ?: error.javaClass.simpleName
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                backupBusy = false
            }
        }
    }
    val backupOpenPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            settingsScope.launch {
                backupBusy = true
                backupProgress = BlockingProgressState(
                    title = context.getString(R.string.progress_read_config),
                    detail = context.getString(R.string.progress_read_config_detail)
                )
                val result = vm.readBackupPreview(uri)
                result
                    .onSuccess { preview ->
                        backupImportUri = uri
                        backupImportPreview = preview
                        backupImportOptions = preview.availableOptions
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.toast_read_failed_fmt,
                                error.message ?: error.javaClass.simpleName
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                backupBusy = false
                backupProgress = null
            }
        }
    }
    val openAccessibilitySettings = remember(context) {
        {
            Toast.makeText(context, context.getString(R.string.toast_grant_accessibility), Toast.LENGTH_SHORT).show()
            context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            Unit
        }
    }
    val openDeviceAdminSettings = remember(context) {
        {
            Toast.makeText(
                context,
                context.getString(R.string.toast_grant_device_admin),
                Toast.LENGTH_SHORT,
            ).show()
            if (!FlueDeviceAdminController.requestActivation(context)) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_open_device_admin_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            Unit
        }
    }

    var destination by remember { mutableStateOf(launchConfig.initialDestination) }
    LaunchedEffect(destination) {
        if (destination != SettingsDestination.ABOUT) debugTapState = DebugTapState()
    }
    // 背景预览卡的底图。**只在背景页才真的解码**：不在那一页时喂一份默认配置（纯色档 →
    // 图源为 null），于是进任何别的设置页都不会白解一张全屏位图出来。
    val appListBackgroundPreviewPainting = rememberAppListBackgroundPainting(
        config = if (destination == SettingsDestination.APP_LIST_BACKGROUND) {
            appListBackgroundPreviewConfig
        } else {
            AppListBackgroundConfig.Default
        },
        fallbackColor = appListPreviewPalette.background,
        darkMode = appListPreviewDarkMode,
        viewportWidthPx = appListPreviewViewport.first,
        viewportHeightPx = appListPreviewViewport.second,
        watchFaceId = selectedWatchFaceId,
        watchFaceStableKey = selectedWatchFace.stableKey,
        watchFacePreviewPath = selectedWatchFace.previewFilePath,
        builtInPhotoPath = builtInPhotoPath,
        builtInVideoPath = builtInVideoPath,
        watchFaceRefreshToken = watchFaceRefreshToken,
        blurPercentOverride = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            appListBackgroundBlurDrag != null
        ) 0 else null,
    )
    var backStack by remember {
        mutableStateOf(initialSettingsBackStack(launchConfig.initialDestination, launchConfig.returnToFaceOnBack))
    }
    // 层级转场方向。静态 depth 表描述不了双入口页面（FEATURE_TOGGLES 从 ROOT
    // 进是二级、从 APPEARANCE 进是三级），所以方向只认导航意图：navigateTo
    // 置 false、handleBack 置 true，transitionSpec 直接读。
    var transitionIsBack by remember { mutableStateOf(false) }
    var hiddenAppsDraft by remember { mutableStateOf(hiddenApps) }
    var hiddenAppsDirty by remember { mutableStateOf(false) }
    var donatePreviewResId by remember { mutableStateOf<Int?>(null) }
    // 语言切换需要整进程重启：仅 recreate() 只重建设置页，桌面那边的
    // Activity 仍持有旧 locale 的 Configuration。存待生效的语言码，
    // 用户确认后杀掉进程并冷启。
    var pendingLanguageRestart by remember { mutableStateOf<String?>(null) }
    var languageSaveInProgress by remember { mutableStateOf(false) }
    var diagnosticLoggingEnabled by remember { mutableStateOf(FlueLog.isDiagnosticLoggingEnabled()) }
    var importedWatchFaceDeleteTarget by remember { mutableStateOf<WatchFaceDescriptor?>(null) }
    var showOverlayMaskCornerDialog by remember { mutableStateOf(false) }
    // Scroll positions are restoration bookkeeping, not UI state. A snapshot
    // state map here invalidated the entire settings destination on every
    // scroll pixel because SettingsPageScaffold continuously wrote offsets.
    val pageScrollPositions = remember { SettingsScrollMemory() }
    val pageScrollResetVersions = remember { mutableStateMapOf<SettingsDestination, Int>() }
    // 搜索结果点进去要落在具体那一行上，而不是目标页的页顶。
    var searchTargetItemKey by remember { mutableStateOf<String?>(null) }
    var searchTargetToken by remember { mutableStateOf(0) }

    fun resetPageScroll(target: SettingsDestination) {
        pageScrollPositions.forget(target)
        pageScrollResetVersions[target] = (pageScrollResetVersions[target] ?: 0) + 1
    }

    fun closeBlockingProgressPage() {
        val state = backupProgress
        backupProgress = null
        backupBusy = false
        if (state?.completionAction == BlockingProgressCompletionAction.WatchFaceArchiveImport) {
            // 用 resetPageScroll 而不是只 forget：进度页是叠在内容之上的兄弟组件，
            // 底下那页的 scaffold 一直活着。只清账本时它的 LazyListState 没人动，
            // 页面还停在原处，等它真被销毁时又把旧位置写回来 —— 等于什么都没做。
            // 顺带把 reset 版本号 +1 才会换掉 key，页面重新挂载并落回列表顶部。
            resetPageScroll(SettingsDestination.WATCH_FACES)
            // 导入完成后直接把用户送进表盘列表看结果，算进入而非回退。
            // 不显式写就会沿用上一次导航残留的方向。
            transitionIsBack = false
            destination = SettingsDestination.WATCH_FACES
            if (backStack.isEmpty() && !launchConfig.returnToFaceOnBack) {
                backStack = listOf(SettingsDestination.ROOT)
            }
            settingsScope.launch {
                vm.refreshWatchFaces(force = true)
            }
        }
    }

    importedWatchFaceDeleteTarget?.let { target ->
        FlueAlertDialog(
            onDismissRequest = { importedWatchFaceDeleteTarget = null },
            title = { Text(stringResource(R.string.dialog_delete_watchface_title)) },
            text = {
                Text(
                    when {
                        target.isWatchmaker ->
                            stringResource(R.string.dialog_delete_watchmaker_fmt, target.displayName)
                        target.isJbWatch ->
                            stringResource(R.string.dialog_delete_jbwatch_fmt, target.displayName)
                        else ->
                            stringResource(R.string.dialog_delete_imported_fmt, target.displayName)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importedWatchFaceDeleteTarget = null
                        settingsScope.launch {
                            val result = vm.deleteImportedWatchFace(target.id)
                            result
                                .onSuccess {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_deleted_fmt, target.displayName),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.toast_delete_failed_fmt,
                                            error.message ?: error.javaClass.simpleName
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { importedWatchFaceDeleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (renameLayoutProfileVisible) {
        ProfileRenameDialog(
            currentName = activeLayoutProfile.name,
            onDismiss = { renameLayoutProfileVisible = false },
            onConfirm = { name ->
                vm.renameActiveLayoutProfile(name)
                renameLayoutProfileVisible = false
            },
        )
    }
    if (renameMotionProfileVisible) {
        ProfileRenameDialog(
            currentName = activeMotionProfile.name,
            onDismiss = { renameMotionProfileVisible = false },
            onConfirm = { name ->
                vm.renameActiveMotionProfile(name)
                renameMotionProfileVisible = false
            },
        )
    }
    if (showOverlayMaskCornerDialog && !flueScreenRound) {
        OverlayMaskCornerRadiusDialog(
            currentRadiusDp = overlayMaskCornerRadiusDp,
            onDismiss = { showOverlayMaskCornerDialog = false },
            onSave = { radiusDp ->
                vm.setOverlayMaskCornerRadiusDp(radiusDp)
                showOverlayMaskCornerDialog = false
            },
        )
    }

    backupProgress?.let { state ->
        BlockingProgressDialog(
            state = state,
            onDismiss = { closeBlockingProgressPage() },
            onShare = { state.shareUri?.let { shareBackupUri(context, it) } }
        )
    }

    InternalFileBrowserHost(
        request = internalFileBrowserRequest,
        onDismiss = { internalFileBrowserRequest = null },
    )

    if (appListBackgroundColorPickerVisible) {
        FlueColorPickerDialog(
            initial = Color(appListBackground.customColorArgb),
            title = stringResource(R.string.settings_app_list_background_color),
            onDismiss = { appListBackgroundColorPickerVisible = false },
            onConfirm = { color ->
                appListBackgroundColorPickerVisible = false
                vm.setAppListBackgroundColor(color.toArgb())
                // 顺手切到自定义色档：老师刚挑完颜色，最不该发生的事是「选了没变化」。
                vm.setAppListBackgroundMode(AppListBackgroundMode.SOLID_CUSTOM)
            },
        )
    }

    appListBackgroundCropUri?.let { pendingUri ->
        FlueImageCropOverlay(
            uri = pendingUri,
            title = stringResource(R.string.app_list_background_crop_title),
            hint = stringResource(R.string.app_list_background_crop_hint),
            // 背景图铺满全屏：裁剪框锁屏幕比例（传 null 就是这个意思）。
            aspectRatio = null,
            onCancel = { appListBackgroundCropUri = null },
            onConfirm = { sourceRect ->
                appListBackgroundCropUri = null
                settingsScope.launch {
                    val savedPath = cropAndStoreAppListBackground(
                        context = context,
                        uri = pendingUri,
                        sourceRect = sourceRect,
                        previousPath = appListBackground.imagePath,
                    )
                    if (savedPath.isNullOrBlank()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_app_list_background_save_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        vm.setAppListBackgroundImagePath(savedPath)
                        vm.setAppListBackgroundMode(AppListBackgroundMode.CUSTOM_IMAGE)
                    }
                }
            },
        )
    }

    LaunchedEffect(hiddenApps) {
        if (!hiddenAppsDirty) {
            hiddenAppsDraft = hiddenApps
        }
    }
    val hideableApps = remember(allSelectableApps) {
        allSelectableApps.filter { app ->
            !app.isBuiltInSettingsEntry && !app.isFolder
        }
    }
    val hiddenHideableCount = remember(hiddenAppsDraft, hideableApps) {
        hideableApps.count { app ->
            hiddenAppsDraft.contains(app.componentKey) || hiddenAppsDraft.contains(app.packageName)
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshWatchFaces()
    }
    val motionExportPicker = rememberLauncherForActivityResult(
        CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val payload = vm.exportMotionProfileLibraryJson().getOrNull()
            if (payload == null) {
                Toast.makeText(context, context.getString(R.string.toast_no_custom_curves), Toast.LENGTH_SHORT).show()
            } else {
                settingsScope.launch(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(payload.toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开导出文件")
                    }.onSuccess {
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.toast_curves_exported), Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure { error ->
                        launch(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.toast_export_failed_fmt,
                                    error.message ?: error.javaClass.simpleName
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }
    val motionImportPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            settingsScope.launch(Dispatchers.IO) {
                val payloadResult = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取动画曲线文件")
                }
                val result = payloadResult.fold(
                    onSuccess = vm::importMotionProfileJson,
                    onFailure = { Result.failure(it) },
                )
                launch(Dispatchers.Main) {
                    result.onSuccess { imported ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_curves_imported_fmt, imported.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.toast_import_failed_fmt,
                                error.message ?: error.javaClass.simpleName
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }
    // 布局档案的导入导出：与上面动效档案那两个 picker 同一套写法（同一个 SAF
    // 契约、同一组 Toast 文案模板），只是换成布局档案库的编解码。
    val layoutExportPicker = rememberLauncherForActivityResult(
        CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val payload = vm.exportLayoutProfileLibraryJson().getOrNull()
            if (payload == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_layout_profiles_export_empty),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                settingsScope.launch(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(payload.toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开导出文件")
                    }.onSuccess {
                        launch(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_layout_profiles_exported),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }.onFailure { error ->
                        launch(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.toast_export_failed_fmt,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }
    val layoutImportPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            settingsScope.launch(Dispatchers.IO) {
                val payloadResult = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取布局档案文件")
                }
                val result = payloadResult.fold(
                    onSuccess = vm::importLayoutProfileJson,
                    onFailure = { Result.failure(it) },
                )
                launch(Dispatchers.Main) {
                    result.onSuccess { imported ->
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.toast_layout_profiles_imported_fmt,
                                imported.size,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.toast_import_failed_fmt,
                                error.message ?: error.javaClass.simpleName,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }
    LaunchedEffect(accessibilityServiceConnected) {
        accessibilityServiceEnabled = FlueAccessibilityService.isEnabled(context) || accessibilityServiceConnected
    }

    DisposableEffect(lifecycleOwner, context, accessibilityServiceConnected, weatherRepository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityServiceEnabled = FlueAccessibilityService.isEnabled(context) || accessibilityServiceConnected
                deviceAdminEnabled = FlueDeviceAdminController.isActive(context)
                bodySensorsPermissionGranted = hasBodySensorsPermission(context)
                locationPermissionGranted = WatchLocationPermissionPolicy.isGranted(context)
                weatherLocationLabel = weatherRepository.activeLocationLabel().orEmpty()
                weatherUsingSystemLocation = weatherRepository.isUsingSystemLocation()
                weatherTemperatureUnit = weatherRepository.temperatureUnit()
                // Market/editor activities can install a content-addressed .watch
                // archive while this SettingsActivity remains alive. Refresh the
                // shared registry when returning instead of waiting for a cold start.
                vm.refreshWatchFaces(force = true)
                watchCatalogReloadToken += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hideFromRecents, context) {
        (context as? Activity)?.let { RecentsVisibility.apply(it, hideFromRecents) }
    }

    val commitHiddenAppsDraft = {
        if (hiddenAppsDirty) {
            vm.setHiddenApps(hiddenAppsDraft)
            hiddenAppsDirty = false
        }
    }

    LaunchedEffect(launchConfig.requestId) {
        if (destination == SettingsDestination.HIDDEN_APPS) {
            commitHiddenAppsDraft()
        }
        destination = launchConfig.initialDestination
        backStack = initialSettingsBackStack(launchConfig.initialDestination, launchConfig.returnToFaceOnBack)
        donatePreviewResId = null
    }

    fun leaveDestination(next: SettingsDestination) {
        if (destination == SettingsDestination.HIDDEN_APPS && next != SettingsDestination.HIDDEN_APPS) {
            commitHiddenAppsDraft()
        }
    }

    val navigateTo: (SettingsDestination) -> Unit = { next ->
        if (next != destination) {
            // backStack 末尾就是 next：这是「往回走一格」，方向按返回算，
            // 否则会把用户熟悉的回退演成前进。
            val poppingBack = backStack.lastOrNull() == next
            // 位置只在「往里进」时清。poppingBack 这一格必须先算出来再判：
            // 退回去的那一页正是用户滑到某处才离开的，清掉就是把他扔回页顶。
            if (settingsScrollResetsOnEnter(destination, next, poppingBack)) {
                resetPageScroll(next)
            }
            leaveDestination(next)
            transitionIsBack = poppingBack
            backStack = if (poppingBack) {
                backStack.dropLast(1)
            } else {
                backStack + destination
            }
            destination = next
        }
    }

    val handleBack: () -> Unit = handleBack@{
        if (backupProgress?.completed == true) {
            closeBlockingProgressPage()
            return@handleBack
        }
        if (backupBusy) {
            return@handleBack
        }
        if (donatePreviewResId != null) {
            donatePreviewResId = null
            return@handleBack
        }
        val previous = backStack.lastOrNull()
        if (previous != null) {
            leaveDestination(previous)
            transitionIsBack = true
            backStack = backStack.dropLast(1)
            destination = previous
        } else {
            if (destination == SettingsDestination.HIDDEN_APPS) {
                commitHiddenAppsDraft()
            }
            onFinish()
        }
    }

    BackHandler(enabled = true) { handleBack() }

    var settingsSearchQuery by remember { mutableStateOf("") }
    // 索引文案走 strings.xml，语言切换会重建 Activity，remember(context) 足够。
    val searchIndex = remember(context) { buildSettingsSearchIndex(context) }
    val selectedIconPackLabel = availableIconPacks.firstOrNull { it.packageName == selectedIconPackPackage }?.label


    val scrollFor: (SettingsDestination) -> SavedScrollPosition = { target ->
        pageScrollPositions.positionOf(target)
    }
    // 一律照记，不看页面身份。以前这里短路过 `if (!target.resetsScrollOnEnter)`，
    // 于是 FEATURE_TOGGLES / DISPLAY_SIZE 和三级以下的页面根本没位置可恢复 ——
    // 连「进子页再退回来」都回到页顶。「进页面回到顶部」已经由 navigateTo 那边的
    // settingsScrollResetsOnEnter + resetPageScroll 负责，这里再拦一次就是重复且有害。
    val updateScroll: (SettingsDestination, Int, Int) -> Unit = { target, index, offset ->
        pageScrollPositions.record(target, index, offset)
    }

    val settingsSearchTarget = searchTargetItemKey?.let { key ->
        SettingsSearchTarget(
            itemKey = key,
            token = searchTargetToken,
            onConsumed = { searchTargetItemKey = null },
        )
    }
    // This plate is deliberately outside AnimatedContent: destination transitions may scale,
    // translate, or fade the interactive page, but must never expose the black window theme.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(launcherStyle.screenBackground),
    ) {
        val destinationTransition = updateTransition(
            targetState = destination,
            label = "settings_destination_transition",
        )
        CompositionLocalProvider(
            LocalSettingsLeftSafeInsetPercent provides appListLeftSafeInsetPercent,
            LocalSettingsRotaryHapticsEnabled provides rotaryHapticsEnabled,
            LocalFlueMotionProfile provides activeMotionProfile,
            LocalSettingsSearchTarget provides settingsSearchTarget,
        ) {
            destinationTransition.AnimatedContent(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                transitionSpec = {
                    // 所有界面风格统一使用 MIUIX / HyperOS 式层级推拉：进子页
                    // 新页从右 1/4 滑入、旧页左移 1/6；返回时方向相反。
                    //
                    // 方向必须由「导航意图」决定，不能由静态 depth 表推导。
                    // depth 是硬编码的，而 FEATURE_TOGGLES 这类页面有两个入口
                    // （ROOT 进是二级、APPEARANCE 进是三级），单个 depth 值在
                    // 原理上就描述不了两条路径 —— 同深度跳转返回时会播成前进。
                    // navigateTo/handleBack 已经维护了真实的 backStack，直接用
                    // 它记下的意图即可。
                    val forward = !transitionIsBack
                    (
                        slideInHorizontally(
                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                        ) { fullWidth -> if (forward) fullWidth / 4 else -fullWidth / 6 } +
                            fadeIn(tween(200))
                        ) togetherWith (
                        slideOutHorizontally(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                        ) { fullWidth -> if (forward) -fullWidth / 6 else fullWidth / 4 } +
                            fadeOut(tween(140))
                        )
                },
            ) { currentDestination ->
                // AnimatedContent keeps both destinations alive while they slide. A
                // transparent destination lets every outgoing card remain visible
                // through the incoming page, which looks like several navigations
                // have piled up. Give each destination its own opaque plate; the
                // uncovered edge still reveals the outgoing page naturally.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(launcherStyle.screenBackground)
                        .clipToBounds(),
                ) {
        key(currentDestination, pageScrollResetVersions[currentDestination] ?: 0) {
        when (currentDestination) {
            SettingsDestination.ROOT -> SettingsPageScaffold(
                title = stringResource(R.string.settings_root_title),
                onBack = {
                    commitHiddenAppsDraft()
                    onFinish()
                },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.ROOT).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.ROOT).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.ROOT, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                item("settings_search") {
                    SettingsSearchField(
                        query = settingsSearchQuery,
                        onQueryChange = { settingsSearchQuery = it },
                        modifier = Modifier.padding(bottom = 4.dp),
                        scale = itemFisheye(listState, "settings_search", screenCenterY, screenHeightPx),
                    )
                }
                if (settingsSearchQuery.isNotBlank()) {
                    val searchResults = searchSettingsEntries(settingsSearchQuery, searchIndex)
                        .filterNot { layoutMode == LayoutMode.Flow && it.itemKey == "app_list_edge_spacing_compression" }
                    if (searchResults.isEmpty()) {
                        item("search_empty") {
                            Text(
                                stringResource(R.string.settings_search_empty),
                                color = LauncherTheme.style.secondaryTextColor,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    searchResults.forEachIndexed { resultIndex, entry ->
                        item("search_result_$resultIndex") {
                            SettingsCategoryCard(
                                title = entry.title,
                                subtitle = entry.description,
                                onClick = {
                                    settingsSearchQuery = ""
                                    // 先登记要定位的行，再跳页：目标页首次
                                    // 组合时就能读到，不用等第二帧。
                                    searchTargetItemKey = entry.itemKey
                                    searchTargetToken += 1
                                    runCatching { SettingsDestination.valueOf(entry.destinationName) }
                                        .getOrNull()
                                        ?.let(navigateTo)
                                },
                                scale = itemFisheye(
                                    listState,
                                    "search_result_$resultIndex",
                                    screenCenterY,
                                    screenHeightPx,
                                )
                            )
                        }
                    }
                } else {
                // 顶部预览头图已按需求移除，列表直接从「外观」分组开始。
                // 分组顺序对照 SettingsHierarchy：外观 → 表盘 → 应用列表 →
                // 动画 → 手势与输入 → 系统与工具 → 关于。
                item("section_appearance") {
                    SectionTitle(stringResource(R.string.settings_section_appearance), itemFisheye(listState, "section_appearance", screenCenterY, screenHeightPx))
                }
                item("appearance") {
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_appearance),
                        subtitle = if (uiStyle == UiStyle.MATERIAL_3) {
                            stringResource(R.string.settings_appearance_sub_md3)
                        } else {
                            stringResource(R.string.settings_appearance_sub)
                        },
                        onClick = { navigateTo(SettingsDestination.APPEARANCE) },
                        scale = itemFisheye(listState, "appearance", screenCenterY, screenHeightPx)
                    )
                }
                item("section_watchface") {
                    SectionTitle(stringResource(R.string.settings_section_watchface), itemFisheye(listState, "section_watchface", screenCenterY, screenHeightPx))
                }
                item("watchfaces") {
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_watchfaces),
                        // 副标题只写当前表盘名。MD3 档以前多一句「主页与表盘图形已联动」，
                        // 老师要求去掉 —— 它讲的是实现细节，用户在这一行想看的是「现在用的是哪块表」。
                        subtitle = selectedWatchFace.displayName,
                        onClick = { navigateTo(SettingsDestination.WATCH_FACES) },
                        scale = itemFisheye(listState, "watchfaces", screenCenterY, screenHeightPx)
                    )
                }
                item("agent_settings") {
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_agent_title),
                        subtitle = stringResource(R.string.settings_agent_subtitle),
                        onClick = { navigateTo(SettingsDestination.AGENT) },
                        scale = itemFisheye(listState, "agent_settings", screenCenterY, screenHeightPx),
                    )
                }
                item("section_app_list") {
                    SectionTitle(stringResource(R.string.settings_section_app_list), itemFisheye(listState, "section_app_list", screenCenterY, screenHeightPx))
                }
                flueSettingsGroup {
                    row("icon_packs") { position ->
                        SettingsCategoryCard(
                            title = stringResource(R.string.settings_icon_packs),
                            subtitle = selectedIconPackLabel ?: stringResource(R.string.settings_icon_pack_system_default),
                            onClick = { navigateTo(SettingsDestination.ICON_PACKS) },
                            scale = itemFisheye(listState, "icon_packs", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                    row("hidden_apps") { position ->
                        SettingsCategoryCard(
                            title = stringResource(R.string.settings_hidden_apps),
                            subtitle = stringResource(R.string.settings_hidden_apps_count_fmt, hiddenHideableCount),
                            onClick = { navigateTo(SettingsDestination.HIDDEN_APPS) },
                            scale = itemFisheye(listState, "hidden_apps", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                }
                item("section_animation") {
                    SectionTitle(stringResource(R.string.settings_section_animation), itemFisheye(listState, "section_animation", screenCenterY, screenHeightPx))
                }
                item("performance") {
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_performance),
                        subtitle = stringResource(R.string.settings_performance_sub),
                        onClick = { navigateTo(SettingsDestination.PERFORMANCE) },
                        scale = itemFisheye(listState, "performance", screenCenterY, screenHeightPx)
                    )
                }
                item("section_input") {
                    SectionTitle(stringResource(R.string.settings_section_input), itemFisheye(listState, "section_input", screenCenterY, screenHeightPx))
                }
                item("feature_toggles") {
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_feature_toggles),
                        subtitle = stringResource(R.string.settings_feature_toggles_sub),
                        onClick = { navigateTo(SettingsDestination.FEATURE_TOGGLES) },
                        scale = itemFisheye(listState, "feature_toggles", screenCenterY, screenHeightPx)
                    )
                }
                item("section_system") {
                    SectionTitle(stringResource(R.string.settings_section_system), itemFisheye(listState, "section_system", screenCenterY, screenHeightPx))
                }
                // 老师报「系统与工具这两处选项还没合并」：语言与工具是同一分类下的两行，
                // MIUIX 档要并成一张卡。
                flueSettingsGroup {
                    row("language") { position ->
                        val langLabel = when (appLanguage) {
                            "zh" -> stringResource(R.string.settings_language_zh)
                            "en" -> stringResource(R.string.settings_language_en)
                            "ja" -> stringResource(R.string.settings_language_ja)
                            else -> stringResource(R.string.settings_language_follow_system)
                        }
                        SettingsCategoryCard(
                            title = stringResource(R.string.settings_language),
                            subtitle = langLabel,
                            onClick = { navigateTo(SettingsDestination.LANGUAGE) },
                            scale = itemFisheye(listState, "language", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                    row("tools") { position ->
                        SettingsCategoryCard(
                            title = stringResource(R.string.settings_tools),
                            subtitle = stringResource(R.string.settings_tools_sub),
                            onClick = { navigateTo(SettingsDestination.TOOLS) },
                            scale = itemFisheye(listState, "tools", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                }
                item("section_about") {
                    SectionTitle(stringResource(R.string.settings_section_about), itemFisheye(listState, "section_about", screenCenterY, screenHeightPx))
                }
                item("about") {
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_about),
                        subtitle = stringResource(R.string.settings_about_sub_fmt, ABOUT_VERSION),
                        onClick = { navigateTo(SettingsDestination.ABOUT) },
                        scale = itemFisheye(listState, "about", screenCenterY, screenHeightPx)
                    )
                }
                }
            }

            SettingsDestination.HIDDEN_APPS -> SettingsPageScaffold(
                title = stringResource(R.string.settings_hidden_apps),
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.HIDDEN_APPS).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.HIDDEN_APPS).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.HIDDEN_APPS, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                item("hidden_summary") {
                    MessageCard(
                        text = stringResource(R.string.settings_hidden_apps_count_fmt, hiddenHideableCount),
                        background = WatchColors.SurfaceGlass,
                        onClick = {}
                    )
                }
                items(
                    items = hideableApps,
                    key = { it.componentKey },
                    contentType = { "hidden_app" },
                ) { app ->
                    SettingsSwitchRow(
                        title = app.label,
                        subtitle = app.packageName,
                        checked = hiddenAppsDraft.contains(app.componentKey) || hiddenAppsDraft.contains(app.packageName),
                        onToggle = {
                            hiddenAppsDraft = hiddenAppsDraft.toMutableSet().apply {
                                if (it) {
                                    add(app.componentKey)
                                } else {
                                    remove(app.componentKey)
                                    remove(app.packageName)
                                }
                            }
                            hiddenAppsDirty = true
                        },
                        scale = itemFisheye(listState, app.componentKey, screenCenterY, screenHeightPx),
                        // LazyColumn already composes only visible/prefetched rows. Do not
                        // drive a second visibility state that unloads/reloads icons while
                        // crossing every item boundary during a fling.
                        leadingIcon = app.cachedIcon,
                        reserveLeadingIconSpace = true,
                        // 隐藏应用页是那条性能敏感的长列表：开关只画不收手势，交互仍由整行
                        // 承担。真组件自带 interactionSource 与手势链，塞进这里会推翻既有修复。
                        indicatorOnly = true,
                    )
                }
            }

            SettingsDestination.ICON_PACKS -> SettingsPageScaffold(
                title = stringResource(R.string.settings_icon_packs),
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.ICON_PACKS).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.ICON_PACKS).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.ICON_PACKS, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                item("icon_pack_default") {
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_icon_pack_default),
                        subtitle = stringResource(R.string.settings_icon_pack_default_sub),
                        selected = selectedIconPackPackage.isNullOrBlank(),
                        onClick = { vm.setIconPackPackage(null) },
                        scale = itemFisheye(listState, "icon_pack_default", screenCenterY, screenHeightPx)
                    )
                }
                items(
                    items = availableIconPacks,
                    key = { "iconpack_${it.packageName}" },
                    contentType = { "icon_pack" },
                ) { pack ->
                    SettingsChoiceRow(
                        title = pack.label,
                        subtitle = stringResource(R.string.settings_icon_pack_adw),
                        selected = pack.packageName == selectedIconPackPackage,
                        onClick = { vm.setIconPackPackage(pack.packageName) },
                        scale = itemFisheye(listState, "iconpack_${pack.packageName}", screenCenterY, screenHeightPx)
                    )
                }
                item("icon_pack_refresh") {
                    ActionCard(
                        title = stringResource(R.string.settings_icon_pack_refresh),
                        subtitle = stringResource(R.string.settings_icon_pack_refresh_sub),
                        icon = { SettingsIcon(material = Icons.Filled.Refresh, miuix = MiuixIcons.Refresh) },
                        onClick = { vm.refreshIconPacks() },
                        scale = itemFisheye(listState, "icon_pack_refresh", screenCenterY, screenHeightPx)
                    )
                }
        }

            SettingsDestination.AGENT -> AgentSettingsPage(onBack = { handleBack() })
            SettingsDestination.WATCH_FACES -> SettingsPageScaffold(
            title = stringResource(R.string.settings_watchfaces),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.WATCH_FACES).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.WATCH_FACES).offset,
            roundFirstActionHalfHeight = 40.dp,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.WATCH_FACES, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            if (!watchFaceLastError.isNullOrBlank()) {
                item("watchface_error") {
                    MessageCard(
                        text = watchFaceLastError!!,
                        background = Color(0x33FF6B6B),
                        onClick = { vm.clearWatchFaceError() }
                    )
                }
            }
            items(
                items = watchFaces,
                key = { it.id },
                contentType = { "watch_face_setting" },
            ) { descriptor ->
                WatchFaceSettingCard(
                    descriptor = descriptor,
                    selected = descriptor.id == selectedWatchFaceId,
                    uiStyle = uiStyle,
                    builtInPhotoPath = builtInPhotoPath,
                    builtInVideoPath = builtInVideoPath,
                    photoOptions = BuiltInWatchFaceOptions(
                        clockPosition = builtInPhotoClockPosition,
                        clockSizeSp = builtInPhotoClockSize,
                        boldClock = builtInPhotoClockBold,
                        clockStyle = builtInPhotoClockStyle,
                        showSeconds = builtInPhotoShowSeconds,
                        customText = builtInPhotoCustomText,
                        fontPath = builtInWatchFaceFontPath,
                        md3eShape = builtInPhotoMd3eShape,
                        useThemeTextColor = builtInPhotoUseThemeTextColor,
                        textColorArgb = builtInPhotoTextColorArgb,
                        md3eAutoColors = builtInPhotoMd3eAutoColors,
                        md3eTextColorArgb = builtInPhotoMd3eTextColorArgb,
                        md3eFaceColorArgb = builtInPhotoMd3eFaceColorArgb,
                        md3eHourHandColorArgb = builtInPhotoMd3eHourColorArgb,
                        md3eMinuteHandColorArgb = builtInPhotoMd3eMinuteColorArgb,
                        md3eSecondHandColorArgb = builtInPhotoMd3eSecondColorArgb
                    ),
                    videoOptions = BuiltInWatchFaceOptions(
                        clockPosition = builtInVideoClockPosition,
                        clockSizeSp = builtInVideoClockSize,
                        boldClock = builtInVideoClockBold,
                        cropToFill = builtInVideoFillScreen,
                        clockColorMode = builtInVideoClockColorMode,
                        clockStyle = builtInVideoClockStyle,
                        showSeconds = builtInVideoShowSeconds,
                        customText = builtInVideoCustomText,
                        fontPath = builtInWatchFaceFontPath,
                        md3eShape = builtInVideoMd3eShape,
                        useThemeTextColor = builtInVideoUseThemeTextColor,
                        textColorArgb = builtInVideoTextColorArgb,
                        md3eAutoColors = builtInVideoMd3eAutoColors,
                        md3eTextColorArgb = builtInVideoMd3eTextColorArgb,
                        md3eFaceColorArgb = builtInVideoMd3eFaceColorArgb,
                        md3eHourHandColorArgb = builtInVideoMd3eHourColorArgb,
                        md3eMinuteHandColorArgb = builtInVideoMd3eMinuteColorArgb,
                        md3eSecondHandColorArgb = builtInVideoMd3eSecondColorArgb
                    ),
                    onSelect = { vm.selectWatchFace(descriptor.id) },
                    onDelete = if (descriptor.isJbWatch || descriptor.isWatchmaker) {
                        { importedWatchFaceDeleteTarget = descriptor }
                    } else {
                        null
                    },
                    scale = itemFisheye(listState, descriptor.id, screenCenterY, screenHeightPx),
                    // 保存表盘会原地覆盖 preview.jpg，令牌驱动缩略图重读。
                    refreshToken = watchFaceRefreshToken,
                )
            }
            if (selectedWatchFace.isWatchmaker) {
                item("watchface_editor_current") {
                    ActionCard(
                        title = stringResource(R.string.settings_watchface_editor_current),
                        subtitle = stringResource(
                            R.string.settings_watchface_editor_current_sub_fmt,
                            selectedWatchFace.displayName
                        ),
                        scale = itemFisheye(listState, "watchface_editor_current", screenCenterY, screenHeightPx),
                        icon = { SettingsIcon(material = OfficialMiuixIcons.OfficialSettings, miuix = OfficialMiuixIcons.OfficialSettings) },
                        onClick = {
                            context.startActivity(
                                Intent(context, WatchEditorActivity::class.java)
                                    .putExtra(WatchEditorActivity.EXTRA_WATCH_FACE_ID, selectedWatchFace.id)
                            )
                        }
                    )
                }
            }
            // 新建紧跟在「编辑当前表盘」之后：两个都是编辑器入口，放一起。
            // 注意它必须在上面那个 isWatchmaker 判断之外——没有选中 .watch
            // 表盘时也要能新建。
            flueSettingsGroup {
                row("watchface_editor_new") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_watchface_editor_new),
                        subtitle = stringResource(R.string.settings_watchface_editor_new_sub),
                        scale = itemFisheye(listState, "watchface_editor_new", screenCenterY, screenHeightPx),
                        icon = { SettingsIcon(material = OfficialMiuixIcons.OfficialAdd, miuix = OfficialMiuixIcons.OfficialAdd) },
                        onClick = {
                            context.startActivity(Intent(context, WatchEditorActivity::class.java))
                        },
                        groupPosition = position,
                    )
                }
                row("watchface_import_archive") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_watchface_import),
                        subtitle = stringResource(R.string.settings_watchface_import_sub),
                        scale = itemFisheye(listState, "watchface_import_archive", screenCenterY, screenHeightPx),
                        icon = {
                            SettingsIcon(
                                material = OfficialMiuixIcons.OfficialFolder,
                                miuix = OfficialMiuixIcons.OfficialFolder,
                            )
                        },
                        onClick = {
                            watchFaceArchivePicker.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "application/*",
                                    "*/*"
                                )
                            )
                        },
                        onLongPress = {
                            internalFileBrowserRequest = InternalFileBrowserRequest(
                                title = context.getString(R.string.internal_file_browser_pick_watch),
                                extensions = setOf("watch"),
                                onSelected = { uris -> handleWatchFaceArchiveUri(uris.firstOrNull()) },
                            )
                        },
                        groupPosition = position,
                    )
                }
                row("watchface_store") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_watchface_store),
                        subtitle = stringResource(R.string.settings_watchface_store_sub),
                        scale = itemFisheye(listState, "watchface_store", screenCenterY, screenHeightPx),
                        icon = {
                            SettingsIcon(
                                material = OfficialMiuixIcons.OfficialCarrier,
                                miuix = OfficialMiuixIcons.OfficialCarrier,
                            )
                        },
                        onClick = {
                            context.startActivity(Intent(context, WatchStoreActivity::class.java))
                        },
                        groupPosition = position,
                    )
                }
                row("watchface_refresh") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_watchface_refresh),
                        subtitle = stringResource(R.string.settings_watchface_refresh_sub),
                        scale = itemFisheye(listState, "watchface_refresh", screenCenterY, screenHeightPx),
                        icon = { SettingsIcon(material = OfficialMiuixIcons.OfficialRefresh, miuix = OfficialMiuixIcons.OfficialRefresh) },
                        onClick = { vm.refreshWatchFaces(force = true) },
                        groupPosition = position,
                    )
                }
                row("watchface_more") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_watchface_more),
                        subtitle = stringResource(R.string.settings_watchface_more_sub),
                        onClick = { navigateTo(SettingsDestination.WATCH_FACE_MORE) },
                        scale = itemFisheye(listState, "watchface_more", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.WATCH_FACE_MORE -> SettingsPageScaffold(
            title = stringResource(R.string.settings_watchface_more),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.WATCH_FACE_MORE).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.WATCH_FACE_MORE).offset,
            roundFirstActionIndex = 2,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.WATCH_FACE_MORE, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("watchface_long_press_mode") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_watchface_long_press_mode),
                        subtitle = stringResource(
                            if (watchFaceLongPressMode == WatchFaceLongPressMode.CARDS) {
                                R.string.settings_watchface_long_press_mode_cards_sub
                            } else {
                                R.string.settings_watchface_long_press_mode_list_sub
                            },
                        ),
                        scale = itemFisheye(
                            listState,
                            "watchface_long_press_mode",
                            screenCenterY,
                            screenHeightPx,
                        ),
                        icon = {
                            SettingsIcon(
                                material = OfficialMiuixIcons.OfficialSettings,
                                miuix = OfficialMiuixIcons.OfficialSettings,
                            )
                        },
                        onClick = { watchFaceLongPressModeDialogVisible = true },
                        groupPosition = position,
                    )
                }
            }
            item("watch_weather_header") {
                SectionTitle(
                    stringResource(R.string.settings_watch_weather),
                    itemFisheye(listState, "watch_weather_header", screenCenterY, screenHeightPx),
                )
            }
            flueSettingsGroup {
                row("watch_weather_manual_city") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_watch_weather_city),
                        subtitle = weatherLocationLabel.takeIf(String::isNotBlank)?.let { label ->
                            stringResource(R.string.settings_watch_weather_city_selected_fmt, label)
                        } ?: if (locationPermissionGranted && weatherUsingSystemLocation) {
                            stringResource(R.string.settings_watch_weather_city_system)
                        } else {
                            stringResource(R.string.settings_watch_weather_city_unset)
                        },
                        scale = itemFisheye(
                            listState,
                            "watch_weather_manual_city",
                            screenCenterY,
                            screenHeightPx,
                        ),
                        icon = {
                            SettingsIcon(material = OfficialMiuixIcons.OfficialSettings, miuix = OfficialMiuixIcons.OfficialSettings)
                        },
                        onClick = {
                            weatherCityDraft = ""
                            weatherCityCandidates = emptyList()
                            weatherCitySearchError = null
                            weatherCityDialogVisible = true
                        },
                        groupPosition = position,
                    )
                }
                row("watch_weather_system_location") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_watch_weather_use_system),
                        subtitle = if (locationPermissionGranted && weatherUsingSystemLocation) {
                            weatherLocationLabel.takeIf(String::isNotBlank)?.let { label ->
                                stringResource(R.string.settings_watch_weather_use_system_active_fmt, label)
                            } ?: stringResource(R.string.settings_watch_weather_use_system_active)
                        } else {
                            stringResource(R.string.settings_watch_weather_use_system_sub)
                        },
                        scale = itemFisheye(
                            listState,
                            "watch_weather_system_location",
                            screenCenterY,
                            screenHeightPx,
                        ),
                        icon = {
                            SettingsIcon(material = OfficialMiuixIcons.OfficialRefresh, miuix = OfficialMiuixIcons.OfficialRefresh)
                        },
                        onClick = {
                            if (locationPermissionGranted) {
                                resolveAndUseSystemWeatherLocation()
                            } else {
                                locationPermissionLauncher.launch(WatchLocationPermissionPolicy.PERMISSIONS)
                            }
                        },
                        groupPosition = position,
                    )
                }
            }
            item("watch_weather_unit_header") {
                SectionTitle(
                    stringResource(R.string.settings_watch_weather_unit),
                    itemFisheye(listState, "watch_weather_unit_header", screenCenterY, screenHeightPx),
                )
            }
            item("watch_weather_unit_celsius") {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_watch_weather_celsius),
                    subtitle = stringResource(R.string.settings_watch_weather_celsius_sub),
                    selected = weatherTemperatureUnit == WatchTemperatureUnit.CELSIUS,
                    onClick = {
                        weatherRepository.setTemperatureUnit(WatchTemperatureUnit.CELSIUS)
                        weatherTemperatureUnit = WatchTemperatureUnit.CELSIUS
                    },
                    scale = itemFisheye(
                        listState,
                        "watch_weather_unit_celsius",
                        screenCenterY,
                        screenHeightPx,
                    ),
                )
            }
            item("watch_weather_unit_fahrenheit") {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_watch_weather_fahrenheit),
                    subtitle = stringResource(R.string.settings_watch_weather_fahrenheit_sub),
                    selected = weatherTemperatureUnit == WatchTemperatureUnit.FAHRENHEIT,
                    onClick = {
                        weatherRepository.setTemperatureUnit(WatchTemperatureUnit.FAHRENHEIT)
                        weatherTemperatureUnit = WatchTemperatureUnit.FAHRENHEIT
                    },
                    scale = itemFisheye(
                        listState,
                        "watch_weather_unit_fahrenheit",
                        screenCenterY,
                        screenHeightPx,
                    ),
                )
            }
            item("watch_weather_refresh") {
                ActionCard(
                    title = stringResource(R.string.settings_watch_weather_refresh),
                    subtitle = stringResource(R.string.settings_watch_weather_refresh_sub),
                    scale = itemFisheye(
                        listState,
                        "watch_weather_refresh",
                        screenCenterY,
                        screenHeightPx,
                    ),
                    icon = {
                        SettingsIcon(material = OfficialMiuixIcons.OfficialRefresh, miuix = OfficialMiuixIcons.OfficialRefresh)
                    },
                    onClick = {
                        weatherRepository.requestRefresh()
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_weather_refresh_requested),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
            // 点击动作映射对**所有**表盘格式都有意义（.watch 与 JBWatch 都有 tap_action），
            // 所以放在 isWatchmaker 判断之外。老师指定它在「更多表盘设置」里。
            flueSettingsGroup {
                row("tap_actions") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_tap_actions),
                        subtitle = stringResource(R.string.settings_tap_actions_sub),
                        scale = itemFisheye(listState, "tap_actions", screenCenterY, screenHeightPx),
                        icon = { SettingsIcon(material = OfficialMiuixIcons.OfficialSettings, miuix = OfficialMiuixIcons.OfficialSettings) },
                        onClick = { navigateTo(SettingsDestination.TAP_ACTION_CATEGORIES) },
                        groupPosition = position,
                    )
                }
                row("watchface_tap_haptics") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_watchface_tap_haptics),
                        subtitle = stringResource(R.string.settings_watchface_tap_haptics_sub),
                        checked = watchFaceTapHapticsEnabled,
                        onToggle = vm::setWatchFaceTapHapticsEnabled,
                        scale = itemFisheye(listState, "watchface_tap_haptics", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            if (selectedWatchFace.isWatchmaker) {
                // 画布调节只留这一个入口：预设、四条滑杆、跟随开关、重置全都搬进
                // CanvasAdjustActivity —— 在设置页里调，效果在另一个页面上，用户
                // 只能靠脑补；搬过去之后底下就是真实表盘，拖滑杆当场看到结果。
                flueSettingsGroup {
                    row("watchface_canvas_entry") { position ->
                        ActionCard(
                            title = stringResource(R.string.settings_watchface_canvas_section),
                            subtitle = stringResource(R.string.settings_watchface_canvas_entry_sub),
                            scale = itemFisheye(
                                listState,
                                "watchface_canvas_entry",
                                screenCenterY,
                                screenHeightPx,
                            ),
                            icon = { SettingsIcon(material = OfficialMiuixIcons.OfficialSettings, miuix = OfficialMiuixIcons.OfficialSettings) },
                            onClick = {
                                CanvasAdjustActivity.start(context, selectedWatchFaceId)
                            },
                            groupPosition = position,
                        )
                    }
                    row("watchface_gravity_parallax") { position ->
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_watchface_gravity_parallax),
                            subtitle = if (watchfaceGravityParallaxEnabled) {
                                stringResource(R.string.settings_watchface_gravity_parallax_on)
                            } else {
                                stringResource(R.string.settings_watchface_gravity_parallax_off)
                            },
                            checked = watchfaceGravityParallaxEnabled,
                            onToggle = vm::setWatchfaceGravityParallaxEnabled,
                            scale = itemFisheye(
                                listState,
                                "watchface_gravity_parallax",
                                screenCenterY,
                                screenHeightPx,
                            ),
                            groupPosition = position,
                        )
                    }
                }
                // 紧跟景深开关：这一项只对景深表盘有意义，放一起才看得懂它管什么。
                flueSettingsGroup {
                    row("watchface_gravity_backdrop_blur") { position ->
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_watchface_gravity_backdrop_blur),
                            subtitle = if (watchfaceGravityBackdropBlurEnabled) {
                                stringResource(R.string.settings_watchface_gravity_backdrop_blur_on)
                            } else {
                                stringResource(R.string.settings_watchface_gravity_backdrop_blur_off)
                            },
                            checked = watchfaceGravityBackdropBlurEnabled,
                            onToggle = vm::setWatchfaceGravityBackdropBlurEnabled,
                            scale = itemFisheye(
                                listState,
                                "watchface_gravity_backdrop_blur",
                                screenCenterY,
                                screenHeightPx,
                            ),
                            groupPosition = position,
                        )
                    }
                    row("watchmaker_body_sensors") { position ->
                        ActionCard(
                            title = stringResource(R.string.settings_watchface_heart_rate),
                            subtitle = if (bodySensorsPermissionGranted) {
                                stringResource(R.string.settings_watchface_heart_rate_on)
                            } else {
                                stringResource(R.string.settings_watchface_heart_rate_off)
                            },
                            scale = itemFisheye(
                                listState,
                                "watchmaker_body_sensors",
                                screenCenterY,
                                screenHeightPx,
                            ),
                            icon = {
                                val statusIcon = if (bodySensorsPermissionGranted) {
                                        OfficialMiuixIcons.OfficialOk
                                    } else {
                                        OfficialMiuixIcons.OfficialSettings
                                    }
                                SettingsIcon(
                                    material = statusIcon,
                                    miuix = statusIcon,
                                )
                            },
                            onClick = {
                                if (bodySensorsPermissionGranted) {
                                    Toast.makeText(context, context.getString(R.string.toast_body_sensors_granted), Toast.LENGTH_SHORT).show()
                                } else {
                                    bodySensorsPermissionLauncher.launch(requiredHeartRatePermission())
                                }
                            },
                            groupPosition = position,
                        )
                    }
                }
                // 紧跟心率那一行：两者都是「表盘要读一类受保护数据」，放一起用户
                // 才看得懂这一段在管什么。
                item("watchmaker_calendar") {
                    ActionCard(
                        title = stringResource(R.string.settings_watchface_calendar),
                        subtitle = if (calendarPermissionGranted) {
                            stringResource(R.string.settings_watchface_calendar_on)
                        } else {
                            stringResource(R.string.settings_watchface_calendar_off)
                        },
                        scale = itemFisheye(
                            listState,
                            "watchmaker_calendar",
                            screenCenterY,
                            screenHeightPx,
                        ),
                        icon = {
                            val statusIcon = if (calendarPermissionGranted) {
                                OfficialMiuixIcons.OfficialOk
                            } else {
                                OfficialMiuixIcons.OfficialSettings
                            }
                            SettingsIcon(
                                material = statusIcon,
                                miuix = statusIcon,
                            )
                        },
                        onClick = {
                            if (calendarPermissionGranted) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_calendar_granted),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                            }
                        },
                    )
                }
            }
            if (selectedWatchFace.isBuiltin) {
                item("watchface_font") {
                    ActionCard(
                        title = stringResource(R.string.settings_watchface_font),
                        subtitle = builtInWatchFaceFontPath?.let { File(it).name }
                            ?: stringResource(R.string.settings_watchface_font_default),
                        scale = itemFisheye(listState, "watchface_font", screenCenterY, screenHeightPx),
                        icon = { SettingsIcon(material = OfficialMiuixIcons.OfficialSettings, miuix = OfficialMiuixIcons.OfficialSettings) },
                        onClick = {
                            fontPicker.launch(
                                arrayOf(
                                    "font/*",
                                    "application/x-font-ttf",
                                    "application/x-font-otf",
                                    "application/vnd.ms-opentype",
                                    "application/octet-stream"
                                )
                            )
                        }
                    )
                }
                if (!builtInWatchFaceFontPath.isNullOrBlank()) {
                    item("watchface_font_clear") {
                        ActionCard(
                            title = stringResource(R.string.settings_watchface_font_clear),
                            subtitle = stringResource(R.string.settings_watchface_font_clear_sub),
                            scale = itemFisheye(listState, "watchface_font_clear", screenCenterY, screenHeightPx),
                            icon = { SettingsIcon(material = OfficialMiuixIcons.OfficialRefresh, miuix = OfficialMiuixIcons.OfficialRefresh) },
                            onClick = { vm.setBuiltInWatchFaceFontPath(null) }
                        )
                    }
                }
            }
            flueSettingsGroup {
                row("watchface_bottom_fade") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_watchface_bottom_fade),
                        subtitle = if (watchFaceBottomFadeEnabled) {
                            stringResource(R.string.settings_watchface_bottom_fade_on)
                        } else {
                            stringResource(R.string.settings_watchface_bottom_fade_off)
                        },
                        checked = watchFaceBottomFadeEnabled,
                        onToggle = vm::setWatchFaceBottomFadeEnabled,
                        scale = itemFisheye(listState, "watchface_bottom_fade", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("watchface_text_entry_animation") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_watchface_text_entry_animation),
                        subtitle = stringResource(R.string.settings_watchface_text_entry_animation_sub),
                        checked = watchFaceTextEntryAnimation,
                        onToggle = vm::setWatchFaceTextEntryAnimationEnabled,
                        scale = itemFisheye(listState, "watchface_text_entry_animation", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("watchface_text_exit_animation") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_watchface_text_exit_animation),
                        subtitle = stringResource(R.string.settings_watchface_text_exit_animation_sub),
                        checked = watchFaceTextExitAnimation,
                        onToggle = vm::setWatchFaceTextExitAnimationEnabled,
                        scale = itemFisheye(listState, "watchface_text_exit_animation", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            item("watchface_date_locale_header") {
                SectionTitle(
                    stringResource(R.string.settings_watchface_date_language),
                    itemFisheye(listState, "watchface_date_locale_header", screenCenterY, screenHeightPx)
                )
            }
            item("watchface_date_locale_system") {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_theme_system),
                    subtitle = stringResource(R.string.settings_watchface_date_system_sub),
                    selected = watchfaceDateLocale == WatchDateLocale.PREFERENCE_SYSTEM,
                    onClick = { vm.setWatchfaceDateLocale(WatchDateLocale.PREFERENCE_SYSTEM) },
                    scale = itemFisheye(listState, "watchface_date_locale_system", screenCenterY, screenHeightPx)
                )
            }
            item("watchface_date_locale_zh") {
                SettingsChoiceRow(
                    title = "中文",
                    subtitle = stringResource(R.string.settings_watchface_date_zh_sub),
                    selected = watchfaceDateLocale == WatchDateLocale.PREFERENCE_CHINESE,
                    onClick = { vm.setWatchfaceDateLocale(WatchDateLocale.PREFERENCE_CHINESE) },
                    scale = itemFisheye(listState, "watchface_date_locale_zh", screenCenterY, screenHeightPx)
                )
            }
            item("watchface_date_locale_en") {
                SettingsChoiceRow(
                    title = "English",
                    subtitle = stringResource(R.string.settings_watchface_date_en_sub),
                    selected = watchfaceDateLocale == WatchDateLocale.PREFERENCE_ENGLISH,
                    onClick = { vm.setWatchfaceDateLocale(WatchDateLocale.PREFERENCE_ENGLISH) },
                    scale = itemFisheye(listState, "watchface_date_locale_en", screenCenterY, screenHeightPx)
                )
            }
            item("watchface_date_locale_ja") {
                SettingsChoiceRow(
                    title = "日本語",
                    subtitle = stringResource(R.string.settings_watchface_date_ja_sub),
                    selected = watchfaceDateLocale == WatchDateLocale.PREFERENCE_JAPANESE,
                    onClick = { vm.setWatchfaceDateLocale(WatchDateLocale.PREFERENCE_JAPANESE) },
                    scale = itemFisheye(listState, "watchface_date_locale_ja", screenCenterY, screenHeightPx)
                )
            }
            flueSettingsGroup {
                row("watchface_status_indicators") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_watchface_status),
                        subtitle = stringResource(R.string.settings_watchface_status_sub),
                        checked = watchFaceStatusIndicators,
                        onToggle = vm::setWatchFaceStatusIndicatorsEnabled,
                        scale = itemFisheye(listState, "watchface_status_indicators", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("watchface_dnd_indicator") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_watchface_dnd_indicator),
                        subtitle = stringResource(R.string.settings_watchface_dnd_indicator_sub),
                        checked = watchFaceDndIndicator,
                        onToggle = vm::setWatchFaceDndIndicatorEnabled,
                        scale = itemFisheye(listState, "watchface_dnd_indicator", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("watchface_charging_power_text") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_watchface_charging_power),
                        subtitle = stringResource(R.string.settings_watchface_charging_power_sub),
                        checked = watchFaceChargingPowerText,
                        onToggle = vm::setWatchFaceChargingPowerTextEnabled,
                        scale = itemFisheye(listState, "watchface_charging_power_text", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }

        }

            SettingsDestination.TAP_ACTION_CATEGORIES -> SettingsPageScaffold(
            title = stringResource(R.string.settings_tap_actions),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.TAP_ACTION_CATEGORIES).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.TAP_ACTION_CATEGORIES).offset,
            roundFirstActionIndex = 1,
            onScrollChanged = { index, offset ->
                updateScroll(SettingsDestination.TAP_ACTION_CATEGORIES, index, offset)
            },
        ) { listState, screenCenterY, screenHeightPx, _ ->
            WatchTapActionCatalog.categories.forEach { category ->
                item("tap_action_category_${category.id}") {
                    SettingsCategoryCard(
                        title = stringResource(category.titleRes),
                        subtitle = stringResource(category.descriptionRes),
                        onClick = {
                            tapActionCategoryId = category.id
                            navigateTo(SettingsDestination.TAP_ACTION_CATEGORY)
                        },
                        scale = itemFisheye(
                            listState,
                            "tap_action_category_${category.id}",
                            screenCenterY,
                            screenHeightPx,
                        ),
                    )
                }
            }
        }

            SettingsDestination.TAP_ACTION_CATEGORY -> {
            val category = WatchTapActionCatalog.category(tapActionCategoryId)
                ?: WatchTapActionCatalog.categories.first()
            SettingsPageScaffold(
                title = stringResource(category.titleRes),
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.TAP_ACTION_CATEGORY).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.TAP_ACTION_CATEGORY).offset,
                roundFirstActionIndex = 1,
                onScrollChanged = { index, offset ->
                    updateScroll(SettingsDestination.TAP_ACTION_CATEGORY, index, offset)
                },
            ) { listState, screenCenterY, screenHeightPx, _ ->
                category.actions.forEach { action ->
                    item("tap_action_${action.id}") {
                        WatchTapActionBindingCard(
                            action = action,
                            revision = tapActionRevision,
                            scale = itemFisheye(
                                listState,
                                "tap_action_${action.id}",
                                screenCenterY,
                                screenHeightPx,
                            ),
                            onClick = { tapActionPicker = action },
                        )
                    }
                }
            }
        }

            SettingsDestination.APP_LIST_BACKGROUND -> SettingsPageScaffold(
            title = stringResource(R.string.settings_app_list_background),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.APP_LIST_BACKGROUND).index,
            initialFirstVisibleItemScrollOffset =
                scrollFor(SettingsDestination.APP_LIST_BACKGROUND).offset,
            // 预览卡插在最前面之后整体后移一位。圆屏仍旧把「背景模式」这行摆在屏幕正中 ——
            // 卡片就在它上面，居中位置上方的半屏空间装得下（卡高不超过屏高 38%），
            // 所以进页面时预览与选项都看得见。
            roundFirstActionIndex = 2,
            onScrollChanged = { index, offset ->
                updateScroll(SettingsDestination.APP_LIST_BACKGROUND, index, offset)
            }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            val backgroundMode = appListBackground.mode
            // 实时预览：模式、颜色、图片、模糊、遮罩改哪一项都立刻反映在这张卡上，
            // 拖滑块时也跟手（拖动值单独存了一份，见 appListBackgroundPreviewConfig）。
            item("app_list_background_preview") {
                val previewScale = itemFisheye(
                    listState,
                    "app_list_background_preview",
                    screenCenterY,
                    screenHeightPx,
                )
                AppListBackgroundPreviewCard(
                    painting = appListBackgroundPreviewPainting,
                    iconColor = appListPreviewPalette.item,
                    labelColor = appListPreviewPalette.text,
                    squareCornerRadiusDp = overlayMaskCornerRadiusDp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = previewScale
                        scaleY = previewScale
                        alpha = previewScale.coerceIn(0.55f, 1f)
                    },
                )
            }
            item("app_list_background_mode_header") {
                SectionTitle(
                    stringResource(R.string.settings_app_list_background_mode),
                    itemFisheye(
                        listState,
                        "app_list_background_mode_header",
                        screenCenterY,
                        screenHeightPx,
                    ),
                )
            }
            item("app_list_background_solid_system") {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_app_list_background_solid_system),
                    subtitle = stringResource(
                        R.string.settings_app_list_background_solid_system_sub,
                    ),
                    selected = backgroundMode == AppListBackgroundMode.SOLID_SYSTEM,
                    onClick = {
                        vm.setAppListBackgroundMode(AppListBackgroundMode.SOLID_SYSTEM)
                    },
                    scale = itemFisheye(
                        listState,
                        "app_list_background_solid_system",
                        screenCenterY,
                        screenHeightPx,
                    ),
                )
            }
            item("app_list_background_solid_custom") {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_app_list_background_solid_custom),
                    subtitle = stringResource(
                        R.string.settings_app_list_background_solid_custom_sub,
                    ),
                    selected = backgroundMode == AppListBackgroundMode.SOLID_CUSTOM,
                    onClick = {
                        vm.setAppListBackgroundMode(AppListBackgroundMode.SOLID_CUSTOM)
                    },
                    scale = itemFisheye(
                        listState,
                        "app_list_background_solid_custom",
                        screenCenterY,
                        screenHeightPx,
                    ),
                )
            }
            item("app_list_background_watch_face") {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_app_list_background_watch_face),
                    subtitle = stringResource(R.string.settings_app_list_background_watch_face_sub),
                    selected = backgroundMode == AppListBackgroundMode.WATCH_FACE,
                    onClick = { vm.setAppListBackgroundMode(AppListBackgroundMode.WATCH_FACE) },
                    scale = itemFisheye(
                        listState,
                        "app_list_background_watch_face",
                        screenCenterY,
                        screenHeightPx,
                    ),
                )
            }
            item("app_list_background_custom_image") {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_app_list_background_custom_image),
                    subtitle = stringResource(
                        R.string.settings_app_list_background_custom_image_sub,
                    ),
                    selected = backgroundMode == AppListBackgroundMode.CUSTOM_IMAGE,
                    onClick = { vm.setAppListBackgroundMode(AppListBackgroundMode.CUSTOM_IMAGE) },
                    scale = itemFisheye(
                        listState,
                        "app_list_background_custom_image",
                        screenCenterY,
                        screenHeightPx,
                    ),
                )
            }
            // 自定义颜色：只在那一档露出来。选完颜色会自动切到这一档（见弹窗回调）。
            if (backgroundMode == AppListBackgroundMode.SOLID_CUSTOM) {
                item("app_list_background_pick_color") {
                    ActionCard(
                        title = stringResource(R.string.settings_app_list_background_color),
                        subtitle = flueColorToHex(Color(appListBackground.customColorArgb)),
                        scale = itemFisheye(
                            listState,
                            "app_list_background_pick_color",
                            screenCenterY,
                            screenHeightPx,
                        ),
                        icon = {
                            SettingsIcon(
                                material = OfficialMiuixIcons.OfficialSettings,
                                miuix = OfficialMiuixIcons.OfficialSettings,
                                tint = Color(appListBackground.customColorArgb),
                            )
                        },
                        onClick = { appListBackgroundColorPickerVisible = true },
                    )
                }
            }
            // 自定义图片：点击走系统选择器，长按拉起内置文件选择器
            // （与「导入表盘」同一套约定，老师已经熟悉这个手势）。
            if (backgroundMode == AppListBackgroundMode.CUSTOM_IMAGE) {
                item("app_list_background_pick_image") {
                    ActionCard(
                        title = if (appListBackground.imagePath.isNullOrBlank()) {
                            stringResource(R.string.settings_app_list_background_pick_image)
                        } else {
                            stringResource(R.string.settings_app_list_background_change_image)
                        },
                        subtitle = stringResource(
                            R.string.settings_app_list_background_pick_image_sub,
                        ),
                        scale = itemFisheye(
                            listState,
                            "app_list_background_pick_image",
                            screenCenterY,
                            screenHeightPx,
                        ),
                        icon = {
                            SettingsIcon(
                                material = OfficialMiuixIcons.OfficialFolder,
                                miuix = OfficialMiuixIcons.OfficialFolder,
                            )
                        },
                        onClick = { appListBackgroundImagePicker.launch(arrayOf("image/*")) },
                        onLongPress = {
                            internalFileBrowserRequest = InternalFileBrowserRequest(
                                title = context.getString(
                                    R.string.settings_app_list_background_pick_image,
                                ),
                                extensions = APP_LIST_BACKGROUND_IMAGE_EXTENSIONS,
                                onSelected = { uris ->
                                    appListBackgroundCropUri = uris.firstOrNull()
                                },
                            )
                        },
                    )
                }
                if (!appListBackground.imagePath.isNullOrBlank()) {
                    item("app_list_background_clear_image") {
                        ActionCard(
                            title = stringResource(
                                R.string.settings_app_list_background_clear_image,
                            ),
                            subtitle = stringResource(
                                R.string.settings_app_list_background_clear_image_sub,
                            ),
                            scale = itemFisheye(
                                listState,
                                "app_list_background_clear_image",
                                screenCenterY,
                                screenHeightPx,
                            ),
                            icon = {
                                SettingsIcon(
                                    material = OfficialMiuixIcons.OfficialRefresh,
                                    miuix = OfficialMiuixIcons.OfficialRefresh,
                                )
                            },
                            onClick = { vm.setAppListBackgroundImagePath(null) },
                        )
                    }
                }
            }
            // 模糊与遮罩只对「有底图」的两档有意义 —— 纯色档下它们无处施加，
            // 露出来只会让人以为是坏的。
            if (backgroundMode.usesImage) {
                item("app_list_background_effect_header") {
                    SectionTitle(
                        stringResource(R.string.settings_app_list_background_effects),
                        itemFisheye(
                            listState,
                            "app_list_background_effect_header",
                            screenCenterY,
                            screenHeightPx,
                        ),
                    )
                }
                flueSettingsGroup {
                    row("app_list_background_blur") { position ->
                        SettingsSliderRow(
                            title = stringResource(R.string.settings_app_list_background_blur),
                            value = appListBackground.blurPercent.toFloat(),
                            valueText = "${appListBackground.blurPercent}%",
                            localValueText = { "${it.roundToInt()}%" },
                            range = 0f..100f,
                            steps = 0,
                            // 拖动值只喂预览卡，不落盘。
                            onValueChange = {
                                appListBackgroundBlurDrag = it.roundToInt()
                            },
                            // 只在抬手时落盘：模糊要重新烘焙（30 及以下）或换一档 RenderEffect，
                            // 每帧都提交等于拖动时连烘十几张。
                            onValueChangeFinished = {
                                vm.setAppListBackgroundBlurPercent(it.roundToInt())
                                appListBackgroundBlurDrag = null
                            },
                            supportingText = stringResource(
                                R.string.settings_app_list_background_blur_sub,
                            ),
                            scale = itemFisheye(
                                listState,
                                "app_list_background_blur",
                                screenCenterY,
                                screenHeightPx,
                            ),
                            groupPosition = position,
                            footer = {
                                FlueButton(
                                    onClick = {
                                        appListBackgroundBlurDrag = null
                                        appListBackgroundScrimDrag = null
                                        vm.resetAppListBackgroundEffects()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.settings_app_list_background_reset))
                                }
                            },
                        )
                    }
                    row("app_list_background_scrim") { position ->
                        SettingsSliderRow(
                            title = stringResource(R.string.settings_app_list_background_scrim),
                            value = appListBackground.scrimPercent.toFloat(),
                            valueText = "${appListBackground.scrimPercent}%",
                            localValueText = { "${it.roundToInt()}%" },
                            range = 0f..100f,
                            steps = 0,
                            onValueChange = {
                                appListBackgroundScrimDrag = it.roundToInt()
                            },
                            onValueChangeFinished = {
                                vm.setAppListBackgroundScrimPercent(it.roundToInt())
                                appListBackgroundScrimDrag = null
                            },
                            supportingText = stringResource(
                                R.string.settings_app_list_background_scrim_sub,
                            ),
                            scale = itemFisheye(
                                listState,
                                "app_list_background_scrim",
                                screenCenterY,
                                screenHeightPx,
                            ),
                            groupPosition = position,
                        )
                    }
                }
            }
        }

            SettingsDestination.APPEARANCE -> SettingsPageScaffold(
            title = stringResource(R.string.settings_appearance),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.APPEARANCE).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.APPEARANCE).offset,
            roundFirstActionIndex = 2,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.APPEARANCE, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            item("layout_header") { SectionTitle(stringResource(R.string.settings_section_layout), itemFisheye(listState, "layout_header", screenCenterY, screenHeightPx)) }
            // 布局那几项是同一组单选，必须合并成一张卡。以前是几个独立 item，于是
            // 「布局」这一节在 MIUIX 下是分开的胶囊，而紧邻的界面风格 / 界面动画 /
            // 屏幕形状都已经并好了，摆在一起就很割裂（老师报的「布局那里还是分开的」）。
            flueSettingsGroup {
                row("layout_honeycomb") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_layout_honeycomb),
                        subtitle = stringResource(R.string.settings_layout_honeycomb_sub),
                        selected = layoutMode == LayoutMode.Honeycomb,
                        onClick = { vm.setLayoutMode(LayoutMode.Honeycomb) },
                        scale = itemFisheye(listState, "layout_honeycomb", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("layout_list") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_layout_list),
                        subtitle = stringResource(R.string.settings_layout_list_sub),
                        selected = layoutMode == LayoutMode.List,
                        onClick = { vm.setLayoutMode(LayoutMode.List) },
                        scale = itemFisheye(listState, "layout_list", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("layout_grid") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_layout_grid),
                        subtitle = stringResource(R.string.settings_layout_grid_sub),
                        selected = layoutMode == LayoutMode.Grid,
                        onClick = { vm.setLayoutMode(LayoutMode.Grid) },
                        scale = itemFisheye(listState, "layout_grid", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("layout_flow") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_layout_flow),
                        subtitle = stringResource(R.string.settings_layout_flow_sub),
                        selected = layoutMode == LayoutMode.Flow,
                        onClick = { vm.setLayoutMode(LayoutMode.Flow) },
                        scale = itemFisheye(listState, "layout_flow", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("layout_ring") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_layout_ring),
                        subtitle = stringResource(R.string.settings_layout_ring_sub),
                        selected = layoutMode == LayoutMode.Ring,
                        onClick = { vm.setLayoutMode(LayoutMode.Ring) },
                        scale = itemFisheye(listState, "layout_ring", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            item("display_header") {
                SectionTitle(stringResource(R.string.settings_display_header), itemFisheye(listState, "display_header", screenCenterY, screenHeightPx))
            }
            item("app_list_background_entry") {
                SettingsCategoryCard(
                    title = stringResource(R.string.settings_app_list_background),
                    subtitle = appListBackgroundModeLabel(appListBackground.effectiveMode),
                    onClick = { navigateTo(SettingsDestination.APP_LIST_BACKGROUND) },
                    scale = itemFisheye(
                        listState,
                        "app_list_background_entry",
                        screenCenterY,
                        screenHeightPx,
                    ),
                )
            }
            item("display_size_menu") {
                SettingsCategoryCard(
                    title = stringResource(R.string.settings_display_fit),
                    subtitle = stringResource(R.string.settings_display_fit_sub),
                    onClick = { navigateTo(SettingsDestination.DISPLAY_SIZE) },
                    scale = itemFisheye(listState, "display_size_menu", screenCenterY, screenHeightPx)
                )
            }
            item("aod_settings") {
                SettingsCategoryCard(
                    title = stringResource(R.string.aod_title),
                    subtitle = stringResource(R.string.aod_description),
                    onClick = { navigateTo(SettingsDestination.AOD) },
                    scale = itemFisheye(listState, "aod_settings", screenCenterY, screenHeightPx),
                )
            }
            if (!flueScreenRound) {
                item("overlay_mask_corner_radius") {
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_overlay_mask_corner_radius),
                        subtitle = stringResource(
                            R.string.settings_overlay_mask_corner_radius_sub_fmt,
                            overlayMaskCornerRadiusDp,
                        ),
                        onClick = { showOverlayMaskCornerDialog = true },
                        scale = itemFisheye(
                            listState,
                            "overlay_mask_corner_radius",
                            screenCenterY,
                            screenHeightPx,
                        ),
                    )
                }
            }
            item("feature_toggles_menu") {
                SettingsCategoryCard(
                    title = stringResource(R.string.settings_feature_toggles),
                    subtitle = stringResource(R.string.settings_feature_toggles_sub),
                    onClick = { navigateTo(SettingsDestination.FEATURE_TOGGLES) },
                    scale = itemFisheye(listState, "feature_toggles_menu", screenCenterY, screenHeightPx)
                )
            }
            // 深浅色三档也是一组单选，同上并卡。
            flueSettingsGroup {
                row("theme_mode_system") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_theme_system),
                        subtitle = stringResource(R.string.settings_theme_system_sub),
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = { vm.setThemeMode(ThemeMode.SYSTEM) },
                        scale = itemFisheye(listState, "theme_mode_system", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("theme_mode_dark") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_theme_dark),
                        subtitle = stringResource(R.string.settings_theme_dark_sub),
                        selected = themeMode == ThemeMode.DARK,
                        onClick = { vm.setThemeMode(ThemeMode.DARK) },
                        scale = itemFisheye(listState, "theme_mode_dark", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("theme_mode_light") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_theme_light),
                        subtitle = stringResource(R.string.settings_theme_light_sub),
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = { vm.setThemeMode(ThemeMode.LIGHT) },
                        scale = itemFisheye(listState, "theme_mode_light", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            item("ui_style_header") {
                SectionTitle(stringResource(R.string.settings_ui_style_header), itemFisheye(listState, "ui_style_header", screenCenterY, screenHeightPx))
            }
            // 单选组并成一张卡：界面风格 / 界面动画 / 屏幕形状都是「一个类型的几个
            // 选项」，MIUIX 档不该一行一张独立胶囊卡（老师报的）。
            flueSettingsGroup {
                row("ui_style_apple_watch") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_ui_aw),
                        subtitle = stringResource(R.string.settings_ui_aw_sub),
                        selected = uiStyle == UiStyle.APPLE_WATCH,
                        onClick = { vm.setUiStyle(UiStyle.APPLE_WATCH) },
                        scale = itemFisheye(listState, "ui_style_apple_watch", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("ui_style_material_3") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_ui_md3),
                        subtitle = stringResource(R.string.settings_ui_md3_sub),
                        selected = uiStyle == UiStyle.MATERIAL_3,
                        onClick = { vm.setUiStyle(UiStyle.MATERIAL_3) },
                        scale = itemFisheye(listState, "ui_style_material_3", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("ui_style_miuix") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_ui_miuix),
                        subtitle = stringResource(R.string.settings_ui_miuix_sub),
                        selected = uiStyle == UiStyle.MIUIX,
                        onClick = { vm.setUiStyle(UiStyle.MIUIX) },
                        scale = itemFisheye(listState, "ui_style_miuix", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            item("transition_motion_header") {
                SectionTitle(stringResource(R.string.settings_transition_motion_header), itemFisheye(listState, "transition_motion_header", screenCenterY, screenHeightPx))
            }
            flueSettingsGroup {
                row("transition_motion_scale") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_transition_motion_scale),
                        subtitle = stringResource(R.string.settings_transition_motion_scale_sub),
                        selected = transitionMotion == FlueTransitionMotion.SCALE,
                        onClick = { vm.setTransitionMotion(FlueTransitionMotion.SCALE) },
                        scale = itemFisheye(listState, "transition_motion_scale", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("transition_motion_slide") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_transition_motion_slide),
                        subtitle = stringResource(R.string.settings_transition_motion_slide_sub),
                        selected = transitionMotion == FlueTransitionMotion.SLIDE,
                        onClick = { vm.setTransitionMotion(FlueTransitionMotion.SLIDE) },
                        scale = itemFisheye(listState, "transition_motion_slide", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            item("screen_shape_header") {
                SectionTitle(stringResource(R.string.settings_screen_shape_header), itemFisheye(listState, "screen_shape_header", screenCenterY, screenHeightPx))
            }
            flueSettingsGroup {
                row("screen_shape_auto") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_screen_shape_auto),
                        subtitle = stringResource(R.string.settings_screen_shape_auto_sub),
                        selected = screenShapeOverride == FlueScreenShape.AUTO,
                        onClick = { vm.setScreenShapeOverride(FlueScreenShape.AUTO) },
                        scale = itemFisheye(listState, "screen_shape_auto", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("screen_shape_round") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_screen_shape_round),
                        subtitle = stringResource(R.string.settings_screen_shape_override_sub),
                        selected = screenShapeOverride == FlueScreenShape.ROUND,
                        onClick = { vm.setScreenShapeOverride(FlueScreenShape.ROUND) },
                        scale = itemFisheye(listState, "screen_shape_round", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("screen_shape_square") { position ->
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_screen_shape_square),
                        subtitle = stringResource(R.string.settings_screen_shape_override_sub),
                        selected = screenShapeOverride == FlueScreenShape.SQUARE,
                        onClick = { vm.setScreenShapeOverride(FlueScreenShape.SQUARE) },
                        scale = itemFisheye(listState, "screen_shape_square", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            item("visual_effects_header") {
                SectionTitle(stringResource(R.string.settings_visual_effects_header), itemFisheye(listState, "visual_effects_header", screenCenterY, screenHeightPx))
            }
            flueSettingsGroup {
                row("blur_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_blur),
                        subtitle = stringResource(R.string.settings_blur_sub),
                        checked = blurEnabled,
                        onToggle = vm::setBlurEnabled,
                        scale = itemFisheye(listState, "blur_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("edge_blur_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_edge_blur),
                        subtitle = stringResource(R.string.settings_edge_blur_sub),
                        checked = edgeBlurEnabled,
                        onToggle = vm::setEdgeBlurEnabled,
                        scale = itemFisheye(listState, "edge_blur_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("edge_blur_radius") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_edge_blur_radius),
                        value = honeycombEdgeBlurRadius,
                        valueText = "%.1f dp".format(java.util.Locale.US, honeycombEdgeBlurRadius),
                        localValueText = { "%.1f dp".format(java.util.Locale.US, it) },
                        range = 0.5f..5f,
                        steps = 8,
                        onValueChange = vm::setHoneycombEdgeBlurRadius,
                        enabled = blurEnabled && edgeBlurEnabled,
                        scale = itemFisheye(listState, "edge_blur_radius", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("top_fade") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_top_fade),
                        value = honeycombTopFade.toFloat(),
                        valueText = "$honeycombTopFade dp",
                        localValueText = { "${it.roundToInt()} dp" },
                        range = 0f..160f,
                        steps = 15,
                        onValueChange = { vm.setHoneycombTopFade(it.toInt()) },
                        scale = itemFisheye(listState, "top_fade", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("bottom_fade") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_bottom_fade),
                        value = honeycombBottomFade.toFloat(),
                        valueText = "$honeycombBottomFade dp",
                        localValueText = { "${it.roundToInt()} dp" },
                        range = 0f..160f,
                        steps = 15,
                        onValueChange = { vm.setHoneycombBottomFade(it.toInt()) },
                        scale = itemFisheye(listState, "bottom_fade", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            item("launch_header") { SectionTitle(stringResource(R.string.settings_section_launch), itemFisheye(listState, "launch_header", screenCenterY, screenHeightPx)) }
            flueSettingsGroup {
                row("splash_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_splash),
                        subtitle = stringResource(R.string.settings_splash_sub),
                        checked = splashIcon,
                        onToggle = { vm.setSplashIcon(it) },
                        scale = itemFisheye(listState, "splash_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("direct_launch_app_list") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_direct_launch_app_list),
                        subtitle = if (directLaunchAppListEnabled) {
                            stringResource(R.string.settings_direct_launch_app_list_on)
                        } else {
                            stringResource(R.string.settings_direct_launch_app_list_off)
                        },
                        checked = directLaunchAppListEnabled,
                        onToggle = vm::setDirectLaunchAppListEnabled,
                        scale = itemFisheye(listState, "direct_launch_app_list", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            if (splashIcon) {
                item("splash_delay") {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_splash_delay),
                        value = splashDelay.toFloat(),
                        valueText = "${splashDelay} ms",
                        localValueText = { "${it.roundToInt()} ms" },
                        range = 300f..1500f,
                        steps = 11,
                        onValueChange = { vm.setSplashDelay(it.toInt()) },
                        scale = itemFisheye(listState, "splash_delay", screenCenterY, screenHeightPx)
                    )
                }
            }
        }

            SettingsDestination.DISPLAY_SIZE -> SettingsPageScaffold(
            title = stringResource(R.string.settings_display_fit),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.DISPLAY_SIZE).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.DISPLAY_SIZE).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.DISPLAY_SIZE, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("global_ui_scale") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_global_ui_size),
                        value = globalUiScalePercent.toFloat(),
                        valueText = "${globalUiScalePercent}%",
                        localValueText = { "${((it / 5f).roundToInt() * 5).coerceIn(50, 150)}%" },
                        range = 50f..150f,
                        steps = 19,
                        onValueChange = {},
                        onValueChangeFinished = { vm.setGlobalUiScalePercent(((it / 5f).roundToInt() * 5).coerceIn(50, 150)) },
                        scale = itemFisheye(listState, "global_ui_scale", screenCenterY, screenHeightPx),
                        supportingText = stringResource(R.string.settings_global_ui_restart_hint),
                        groupPosition = position,
                        // 重启按钮挨着滑杆放在同一张卡里：这一项只有重启后才全量生效，
                        // 动作离它太远会读成「另一个功能」。
                        footer = {
                            FlueButton(
                                onClick = { restartFlueProcess(context) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.settings_global_ui_apply_restart),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        },
                    )
                }
                row("honeycomb_cols") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_honeycomb_cols),
                        value = honeycombCols.toFloat(),
                        valueText = stringResource(R.string.settings_honeycomb_cols_fmt, honeycombCols),
                        localValueText = { context.resources.getString(R.string.settings_honeycomb_cols_fmt, it.roundToInt()) },
                        range = 1f..5f,
                        steps = 3,
                        onValueChange = { vm.setHoneycombCols(it.roundToInt()) },
                        scale = itemFisheye(listState, "honeycomb_cols", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("app_list_scale") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_app_list_size),
                        value = appListScalePercent.toFloat(),
                        valueText = "${appListScalePercent}%",
                        range = 50f..200f,
                        steps = 14,
                        onValueChange = { vm.setAppListScalePercent(it.toInt()) },
                        scale = itemFisheye(listState, "app_list_scale", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                // 指示器和这三项同卡：它们都是「整块屏幕怎么摆」这一层，与布局样式无关。
                // 不放进下面的鱼眼组是因为鱼眼那一卡里每一项都被 `enabled = appListFisheyeEnabled`
                // 串着，指示器跟鱼眼毫无关系，混进去会被读成「关掉鱼眼这条也就没了」。
                row("app_list_scroll_indicator") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_scroll_indicator),
                        subtitle = if (appListScrollIndicatorEnabled) {
                            stringResource(R.string.settings_scroll_indicator_on)
                        } else {
                            stringResource(R.string.settings_scroll_indicator_off)
                        },
                        checked = appListScrollIndicatorEnabled,
                        onToggle = vm::setAppListScrollIndicatorEnabled,
                        scale = itemFisheye(listState, "app_list_scroll_indicator", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }

            // 鱼眼三段：总开关 → 子开关组 → 数值组。
            // 以前是开关与滑块交替（开关 → 滑块 → 开关 → 滑块 → 开关 → 滑块），
            // 读起来找不到主次。网格 / 心流各自专属那几项只在对应布局显示。
            flueSettingsGroup {
                row("app_list_fisheye") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_fisheye),
                        subtitle = stringResource(R.string.settings_fisheye_sub),
                        checked = appListFisheyeEnabled,
                        onToggle = vm::setAppListFisheyeEnabled,
                        scale = itemFisheye(listState, "app_list_fisheye", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                if (layoutMode != LayoutMode.Flow) {
                row("app_list_edge_spacing_compression") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_edge_spacing),
                        subtitle = stringResource(R.string.settings_edge_spacing_sub),
                        checked = appListEdgeSpacingCompressionEnabled,
                        enabled = appListFisheyeEnabled,
                        onToggle = vm::setAppListEdgeSpacingCompressionEnabled,
                        scale = itemFisheye(listState, "app_list_edge_spacing_compression", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                }
                row("app_list_fisheye_range_rows") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_fisheye_range),
                        supportingText = stringResource(R.string.settings_fisheye_range_sub),
                        value = appListFisheyeRangeRows.toFloat(),                    valueText = stringResource(R.string.settings_fisheye_range_fmt, appListFisheyeRangeRows),
                        localValueText = {
                            val rows = it.roundToInt()
                            context.resources.getString(R.string.settings_fisheye_range_fmt, rows)
                        },
                        range = 1f..8f,
                        steps = 6,
                        onValueChange = { vm.setAppListFisheyeRangeRows(it.roundToInt()) },
                        enabled = appListFisheyeEnabled,
                        scale = itemFisheye(listState, "app_list_fisheye_range_rows", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("app_list_fisheye_strength") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_fisheye_strength),
                        supportingText = stringResource(R.string.settings_fisheye_strength_sub),
                        value = appListFisheyeStrengthPercent.toFloat(),
                        valueText = "${appListFisheyeStrengthPercent}%",
                        localValueText = { "${it.roundToInt()}%" },
                        range = 0f..200f,
                        steps = 19,
                        onValueChange = { vm.setAppListFisheyeStrengthPercent(it.roundToInt()) },
                        enabled = appListFisheyeEnabled,
                        scale = itemFisheye(listState, "app_list_fisheye_strength", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("app_list_left_safe_inset") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_left_safe_area),
                        supportingText = stringResource(R.string.settings_left_safe_area_sub),
                        value = appListLeftSafeInsetPercent.toFloat(),
                        valueText = "${appListLeftSafeInsetPercent}%",
                        localValueText = { "${((it / 5f).roundToInt() * 5).coerceIn(0, 50)}%" },
                        range = 0f..50f,
                        steps = 9,
                        onValueChange = { vm.setAppListLeftSafeInsetPercent((it / 5f).roundToInt() * 5) },
                        scale = itemFisheye(listState, "app_list_left_safe_inset", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            // 网格布局专属那一组。
            //
            // 这两项的实现都只看密集网格那条路：「首行额外缩放」只被
            // `nativeDenseGridChildVisual` 读（其余布局连读都不读），底板则是网格格子
            // 独有的那块 rowBackground。摆在鱼眼组里时，用列表 / 蜂窝 / 心流的人也会看到
            // 一个拨了完全没反应的开关 —— 所以按布局收起来，与下面心流那一组同一个规矩。
            if (layoutMode == LayoutMode.Grid) {
                flueSettingsGroup {
                    row("material_honeycomb_top_fisheye") { position ->
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_fisheye_top_row),
                            subtitle = stringResource(R.string.settings_fisheye_top_row_sub),
                            checked = materialHoneycombTopFisheyeEnabled,
                            // 顶部鱼眼是鱼眼的子项：`nativeDenseGridChildVisual` 里
                            // 顶部带要 `fisheyeEnabled && honeycombTopFisheyeEnabled` 同时成立。
                            enabled = appListFisheyeEnabled,
                            onToggle = vm::setMaterialHoneycombTopFisheyeEnabled,
                            scale = itemFisheye(listState, "material_honeycomb_top_fisheye", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                    row("app_list_grid_icon_plate") { position ->
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_grid_icon_plate),
                            subtitle = if (appListGridIconPlateEnabled) {
                                stringResource(R.string.settings_grid_icon_plate_on)
                            } else {
                                stringResource(R.string.settings_grid_icon_plate_off)
                            },
                            checked = appListGridIconPlateEnabled,
                            onToggle = vm::setAppListGridIconPlateEnabled,
                            scale = itemFisheye(listState, "app_list_grid_icon_plate", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                }
            }
            if (layoutMode == LayoutMode.Flow) {
                item("app_list_flow_shape_preview") {
                    val previewScale = itemFisheye(
                        listState,
                        "app_list_flow_shape_preview",
                        screenCenterY,
                        screenHeightPx,
                    )
                    AppListFlowShapePreview(
                        bulgePercent = appListFlowCrescentBulgePercent,
                        toRight = appListFlowCrescentToRight,
                        curve = activeLayoutProfile.flowShapeCurve,
                        modifier = Modifier.graphicsLayer {
                            scaleX = previewScale
                            scaleY = previewScale
                            alpha = previewScale.coerceIn(0.55f, 1f)
                        },
                    )
                }
                flueSettingsGroup {
                    row("app_list_flow_crescent_direction") { position ->
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_flow_crescent_direction),
                            subtitle = if (appListFlowCrescentToRight) {
                                stringResource(R.string.settings_flow_crescent_direction_right)
                            } else {
                                stringResource(R.string.settings_flow_crescent_direction_left)
                            },
                            checked = appListFlowCrescentToRight,
                            onToggle = vm::setAppListFlowCrescentToRight,
                            scale = itemFisheye(listState, "app_list_flow_crescent_direction", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                    row("app_list_flow_crescent_bulge") { position ->
                        SettingsSliderRow(
                            title = stringResource(R.string.settings_flow_crescent_bulge),
                            supportingText = stringResource(R.string.settings_flow_crescent_bulge_sub),
                            value = appListFlowCrescentBulgePercent.toFloat(),
                            valueText = "${appListFlowCrescentBulgePercent}%",
                            localValueText = { "${it.roundToInt()}%" },
                            // 100% = 峰值正好贴边；100~150% 是老师要的「更深的弧」，
                            // 代价是上下两端那几行会被推出可视区左侧（滚上来就回来了）。
                            range = 0f..FLUE_LAYOUT_CRESCENT_BULGE_MAX.toFloat(),
                            // 5% 一格：0..150 共 30 段，中间刻度 29 个。
                            steps = 29,
                            onValueChange = { vm.setAppListFlowCrescentBulgePercent(it.roundToInt()) },
                            scale = itemFisheye(listState, "app_list_flow_crescent_bulge", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                    listOf(
                        "X1" to activeLayoutProfile.flowShapeCurve.x1,
                        "Y1" to activeLayoutProfile.flowShapeCurve.y1,
                        "X2" to activeLayoutProfile.flowShapeCurve.x2,
                        "Y2" to activeLayoutProfile.flowShapeCurve.y2,
                    ).forEach { (parameter, value) ->
                        row("app_list_flow_curve_$parameter") { position ->
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_flow_curve_parameter_fmt, parameter),
                                value = value,
                                valueText = "%.2f".format(Locale.US, value),
                                localValueText = { "%.2f".format(Locale.US, it) },
                                range = 0f..1f,
                                steps = 19,
                                onValueChange = { next ->
                                    val current = activeLayoutProfile.flowShapeCurve
                                    vm.setAppListFlowShapeCurve(
                                        when (parameter) {
                                            "X1" -> current.copy(x1 = next)
                                            "Y1" -> current.copy(y1 = next)
                                            "X2" -> current.copy(x2 = next)
                                            else -> current.copy(y2 = next)
                                        },
                                    )
                                },
                                scale = itemFisheye(
                                    listState,
                                    "app_list_flow_curve_$parameter",
                                    screenCenterY,
                                    screenHeightPx,
                                ),
                                groupPosition = position,
                            )
                        }
                    }
                }
            }
            // 布局档案：鱼眼那几项是「当前档案」的内容，所以档案管理紧跟在它们后面，
            // 而不是另开一页 —— 换档案与调参数分两页的话，改完这边看不见那边变化。
            item("layout_profile_header") {
                SectionTitle(
                    stringResource(R.string.settings_layout_profile_header),
                    itemFisheye(listState, "layout_profile_header", screenCenterY, screenHeightPx),
                )
            }
            items(
                items = layoutProfiles.filter { it.layoutMode == layoutMode },
                key = { "layout_profile_${it.id}" },
                contentType = { "layout_profile" },
            ) { profile ->
                SettingsChoiceRow(
                    title = profile.name,
                    subtitle = if (FlueLayoutProfiles.isBuiltIn(profile.id)) {
                        stringResource(R.string.settings_layout_profile_preset)
                    } else {
                        stringResource(R.string.settings_layout_profile_custom)
                    },
                    selected = profile.id == activeLayoutProfile.id,
                    onClick = { vm.setActiveLayoutProfile(profile.id) },
                    scale = itemFisheye(
                        listState,
                        "layout_profile_${profile.id}",
                        screenCenterY,
                        screenHeightPx,
                    ),
                )
            }
            flueSettingsGroup {
                row("layout_profile_copy") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_layout_profile_copy),
                        subtitle = stringResource(R.string.settings_layout_profile_copy_sub),
                        onClick = { vm.copyLayoutProfile() },
                        scale = itemFisheye(listState, "layout_profile_copy", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("layout_profile_rename") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_profile_rename),
                        // 内置档不给改名：它们的名字是「哪种布局的默认档」这一层含义，
                        // 改掉之后档案列表就读不出来了。副本随便改。
                        subtitle = if (FlueLayoutProfiles.isBuiltIn(activeLayoutProfile.id)) {
                            stringResource(R.string.settings_profile_rename_builtin_sub)
                        } else {
                            activeLayoutProfile.name
                        },
                        onClick = {
                            if (!FlueLayoutProfiles.isBuiltIn(activeLayoutProfile.id)) {
                                renameLayoutProfileVisible = true
                            }
                        },
                        scale = itemFisheye(listState, "layout_profile_rename", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("layout_profile_reset") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_layout_profile_reset),
                        subtitle = stringResource(R.string.settings_layout_profile_reset_sub),
                        onClick = { vm.resetActiveLayoutProfile() },
                        scale = itemFisheye(listState, "layout_profile_reset", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                // 删除也是「布局方案管理」这一类，要和复制/重命名/重置同卡。
                //
                // 以前它是组外一个独立 item，因为带条件（内置方案不能删）。但 row 只是个
                // 普通收集器、不是 composable，条件直接写在这里就行 —— 组的首尾位置是按
                // 实际收进来的行数算的，少一行不会错位。摆在组外的后果是把一张卡劈成两半，
                // 中间夹一个孤零零的胶囊（老师报的「布局那里 miuix 还是分开的」）。
                if (!FlueLayoutProfiles.isBuiltIn(activeLayoutProfile.id)) {
                    row("layout_profile_delete") { position ->
                        ActionCard(
                            title = stringResource(R.string.settings_layout_profile_delete),
                            subtitle = stringResource(R.string.settings_layout_profile_delete_sub),
                            onClick = { vm.deleteLayoutProfile(activeLayoutProfile.id) },
                            scale = itemFisheye(listState, "layout_profile_delete", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                }
            }
            flueSettingsGroup {
                row("layout_profile_export") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_layout_profile_export),
                        subtitle = stringResource(R.string.settings_layout_profile_export_sub),
                        onClick = { layoutExportPicker.launch("flue-layout-profiles.json") },
                        scale = itemFisheye(listState, "layout_profile_export", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("layout_profile_import") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_layout_profile_import),
                        subtitle = stringResource(R.string.settings_layout_profile_import_sub),
                        onClick = {
                            layoutImportPicker.launch(arrayOf("application/json", "text/plain"))
                        },
                        scale = itemFisheye(listState, "layout_profile_import", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("rotary_haptics") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_rotary_haptics),
                        subtitle = if (rotaryHapticsEnabled) {
                            stringResource(R.string.settings_rotary_haptics_on)
                        } else {
                            stringResource(R.string.settings_rotary_haptics_off)
                        },
                        checked = rotaryHapticsEnabled,
                        onToggle = vm::setRotaryHapticsEnabled,
                        scale = itemFisheye(listState, "rotary_haptics", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            // 「功能开关」从 16 项杂烩改为路牌页：五个按领域归类的三级页。
            SettingsDestination.AOD -> {
                val aodState = rememberAodSettingsState()
                val aod = aodState.config
                SettingsPageScaffold(
                    title = stringResource(R.string.aod_title),
                    onBack = { handleBack() },
                    headerTime = headerTime,
                    initialFirstVisibleItemIndex = scrollFor(SettingsDestination.AOD).index,
                    initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.AOD).offset,
                    onScrollChanged = { index, offset ->
                        updateScroll(SettingsDestination.AOD, index, offset)
                    },
                ) { listState, screenCenterY, screenHeightPx, _ ->
                    item("aod_description") {
                        Text(
                            text = stringResource(R.string.aod_page_description),
                            color = LauncherTheme.style.secondaryTextColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item("aod_enabled") {
                        SettingsSwitchRow(
                            title = stringResource(R.string.aod_enabled),
                            subtitle = stringResource(
                                if (aod.enabled) R.string.aod_state_on else R.string.aod_state_off,
                            ),
                            checked = aod.enabled,
                            onToggle = aodState::setEnabled,
                            scale = itemFisheye(listState, "aod_enabled", screenCenterY, screenHeightPx),
                        )
                    }
                    item("aod_charging") {
                        SettingsSwitchRow(
                            title = stringResource(R.string.aod_charging),
                            subtitle = stringResource(
                                if (aod.alwaysWhenCharging) R.string.aod_state_on else R.string.aod_state_off,
                            ),
                            checked = aod.alwaysWhenCharging,
                            onToggle = aodState::setAlwaysWhenCharging,
                            scale = itemFisheye(listState, "aod_charging", screenCenterY, screenHeightPx),
                        )
                    }
                    item("aod_timeout") {
                        SettingsSliderRow(
                            title = stringResource(R.string.aod_timeout),
                            value = aod.timeoutSeconds.toFloat(),
                            valueText = "${aod.timeoutSeconds} s",
                            localValueText = { "${it.roundToInt()} s" },
                            range = 5f..300f,
                            steps = 58,
                            onValueChange = {},
                            onValueChangeFinished = { aodState.setTimeoutSeconds(it.roundToInt()) },
                            enabled = aodState.controlsEnabled,
                            scale = itemFisheye(listState, "aod_timeout", screenCenterY, screenHeightPx),
                        )
                    }
                    item("aod_brightness") {
                        SettingsSliderRow(
                            title = stringResource(R.string.aod_brightness),
                            value = aod.brightnessPercent.toFloat(),
                            valueText = "${aod.brightnessPercent}%",
                            localValueText = { "${it.roundToInt()}%" },
                            range = 1f..100f,
                            steps = 98,
                            onValueChange = {},
                            onValueChangeFinished = { aodState.setBrightnessPercent(it.roundToInt()) },
                            enabled = aodState.controlsEnabled,
                            scale = itemFisheye(listState, "aod_brightness", screenCenterY, screenHeightPx),
                        )
                    }
                    item("aod_reawaken") {
                        SettingsSwitchRow(
                            title = stringResource(R.string.aod_reawaken),
                            subtitle = stringResource(
                                if (aod.reawaken) R.string.aod_state_on else R.string.aod_state_off,
                            ),
                            checked = aod.reawaken,
                            enabled = aodState.controlsEnabled,
                            onToggle = aodState::setReawaken,
                            scale = itemFisheye(listState, "aod_reawaken", screenCenterY, screenHeightPx),
                        )
                    }
                    item("aod_lock") {
                        SettingsSwitchRow(
                            title = stringResource(R.string.aod_lock),
                            subtitle = stringResource(
                                if (aod.lockGestures) R.string.aod_state_on else R.string.aod_state_off,
                            ),
                            checked = aod.lockGestures,
                            enabled = aodState.controlsEnabled,
                            onToggle = aodState::setLockGestures,
                            scale = itemFisheye(listState, "aod_lock", screenCenterY, screenHeightPx),
                        )
                    }
                    item("aod_lock_delay") {
                        SettingsSliderRow(
                            title = stringResource(R.string.aod_lock_delay),
                            value = aod.lockDelaySeconds.toFloat(),
                            valueText = "${aod.lockDelaySeconds} s",
                            localValueText = { "${it.roundToInt()} s" },
                            range = 0f..30f,
                            steps = 29,
                            onValueChange = {},
                            onValueChangeFinished = { aodState.setLockDelaySeconds(it.roundToInt()) },
                            enabled = aodState.controlsEnabled && aod.lockGestures,
                            scale = itemFisheye(listState, "aod_lock_delay", screenCenterY, screenHeightPx),
                        )
                    }
                    item("aod_shallow") {
                        SettingsSwitchRow(
                            title = stringResource(R.string.aod_shallow),
                            subtitle = stringResource(
                                if (aod.shallowSleep) R.string.aod_state_on else R.string.aod_state_off,
                            ),
                            checked = aod.shallowSleep,
                            enabled = aodState.controlsEnabled && aod.lockGestures,
                            onToggle = aodState::setShallowSleep,
                            scale = itemFisheye(listState, "aod_shallow", screenCenterY, screenHeightPx),
                        )
                    }
                    item("aod_power_exit") {
                        SettingsSwitchRow(
                            title = stringResource(R.string.aod_power_exit),
                            subtitle = stringResource(
                                if (aod.powerButtonExit) R.string.aod_state_on else R.string.aod_state_off,
                            ),
                            checked = aod.powerButtonExit,
                            enabled = aodState.controlsEnabled && aod.reawaken,
                            onToggle = aodState::setPowerButtonExit,
                            scale = itemFisheye(listState, "aod_power_exit", screenCenterY, screenHeightPx),
                        )
                    }
                    item("aod_supplemental_notice") {
                        AodSupplementalNotice(aodState)
                    }
                }
            }

            SettingsDestination.FEATURE_TOGGLES -> SettingsPageScaffold(
            title = stringResource(R.string.settings_feature_toggles),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.FEATURE_TOGGLES).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.FEATURE_TOGGLES).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.FEATURE_TOGGLES, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("cat_side_screen") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_side_screen_title),
                        subtitle = stringResource(R.string.settings_side_screen_card_sub),
                        onClick = { navigateTo(SettingsDestination.SIDE_SCREEN) },
                        scale = itemFisheye(listState, "cat_side_screen", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("cat_notifications") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_notifications_title),
                        subtitle = stringResource(R.string.settings_notifications_card_sub),
                        onClick = { navigateTo(SettingsDestination.NOTIFICATION_SETTINGS) },
                        scale = itemFisheye(listState, "cat_notifications", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("cat_control_center") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_control_center_title),
                        subtitle = stringResource(R.string.settings_control_center_card_sub),
                        onClick = { navigateTo(SettingsDestination.CONTROL_CENTER_SETTINGS) },
                        scale = itemFisheye(listState, "cat_control_center", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("cat_gestures") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_gestures_title),
                        subtitle = stringResource(R.string.settings_gestures_card_sub),
                        onClick = { navigateTo(SettingsDestination.GESTURE_SETTINGS) },
                        scale = itemFisheye(listState, "cat_gestures", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("cat_app_list_behavior") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_app_list_behavior_title),
                        subtitle = stringResource(R.string.settings_app_list_behavior_card_sub),
                        onClick = { navigateTo(SettingsDestination.APP_LIST_BEHAVIOR) },
                        scale = itemFisheye(listState, "cat_app_list_behavior", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("glass_highlight_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_glass_highlight),
                        subtitle = if (glassHighlightEnabled) {
                            stringResource(R.string.settings_glass_highlight_on)
                        } else {
                            stringResource(R.string.settings_glass_highlight_off)
                        },
                        checked = glassHighlightEnabled,
                        onToggle = vm::setGlassHighlightEnabled,
                        scale = itemFisheye(listState, "glass_highlight_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.SIDE_SCREEN -> SettingsPageScaffold(
            title = stringResource(R.string.settings_side_screen_title),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.SIDE_SCREEN).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.SIDE_SCREEN).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.SIDE_SCREEN, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("side_screen_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_side_screen),
                        subtitle = stringResource(R.string.settings_side_screen_sub),
                        checked = sideScreenEnabled,
                        onToggle = vm::setSideScreenEnabled,
                        scale = itemFisheye(listState, "side_screen_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("widget_center_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_widget_page),
                        subtitle = if (showWidgetPage) {
                            stringResource(R.string.settings_widget_page_on)
                        } else {
                            stringResource(R.string.settings_widget_page_off)
                        },
                        checked = showWidgetPage,
                        onToggle = vm::setShowWidgetPage,
                        scale = itemFisheye(listState, "widget_center_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("widget_glass_highlight") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_widget_glass_highlight),
                        subtitle = if (widgetGlassHighlightEnabled) {
                            stringResource(R.string.settings_widget_glass_highlight_on)
                        } else {
                            stringResource(R.string.settings_widget_glass_highlight_off)
                        },
                        checked = widgetGlassHighlightEnabled,
                        enabled = sideScreenEnabled && showWidgetPage,
                        onToggle = vm::setWidgetGlassHighlightEnabled,
                        scale = itemFisheye(listState, "widget_glass_highlight", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("music_notification_swap_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_swap_music_notifications),
                        subtitle = if (swapMusicNotificationComponents) {
                            stringResource(R.string.settings_swap_music_notifications_on)
                        } else {
                            stringResource(R.string.settings_swap_music_notifications_off)
                        },
                        checked = swapMusicNotificationComponents,
                        enabled = sideScreenEnabled && showControlCenter,
                        onToggle = vm::setSwapMusicNotificationComponents,
                        scale = itemFisheye(listState, "music_notification_swap_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("show_step_count") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_steps),
                        subtitle = stringResource(R.string.settings_show_steps_sub),
                        checked = showStepCount,
                        onToggle = vm::setShowStepCountEnabled,
                        scale = itemFisheye(listState, "show_step_count", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.NOTIFICATION_SETTINGS -> SettingsPageScaffold(
            title = stringResource(R.string.settings_notifications_title),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.NOTIFICATION_SETTINGS).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.NOTIFICATION_SETTINGS).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.NOTIFICATION_SETTINGS, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("notification_center_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_notifications),
                        subtitle = if (showNotification) {
                            stringResource(R.string.settings_show_notifications_on)
                        } else {
                            stringResource(R.string.settings_show_notifications_off)
                        },
                        checked = showNotification,
                        enabled = sideScreenEnabled,
                        onToggle = vm::setShowNotification,
                        scale = itemFisheye(listState, "notification_center_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("ongoing_notification_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_ongoing),
                        subtitle = if (showOngoingNotifications) {
                            stringResource(R.string.settings_show_ongoing_on)
                        } else {
                            stringResource(R.string.settings_show_ongoing_off)
                        },
                        checked = showOngoingNotifications,
                        enabled = sideScreenEnabled && showNotification,
                        onToggle = vm::setShowOngoingNotifications,
                        scale = itemFisheye(listState, "ongoing_notification_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.CONTROL_CENTER_SETTINGS -> SettingsPageScaffold(
            title = stringResource(R.string.settings_control_center_title),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.CONTROL_CENTER_SETTINGS).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.CONTROL_CENTER_SETTINGS).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.CONTROL_CENTER_SETTINGS, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("control_center_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_cc),
                        subtitle = if (showControlCenter) {
                            stringResource(R.string.settings_show_cc_on)
                        } else {
                            stringResource(R.string.settings_show_cc_off)
                        },
                        checked = showControlCenter,
                        onToggle = vm::setShowControlCenter,
                        scale = itemFisheye(listState, "control_center_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("music_controls_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_music),
                        subtitle = if (showMusicControls) {
                            stringResource(R.string.settings_show_music_on)
                        } else {
                            stringResource(R.string.settings_show_music_off)
                        },
                        checked = showMusicControls,
                        enabled = showControlCenter,
                        onToggle = vm::setShowMusicControls,
                        scale = itemFisheye(listState, "music_controls_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("media_custom_actions_toggle") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_media_actions),
                        subtitle = if (showMediaCustomActions) {
                            stringResource(R.string.settings_media_actions_on)
                        } else {
                            stringResource(R.string.settings_media_actions_off)
                        },
                        checked = showMediaCustomActions,
                        enabled = showControlCenter && showMusicControls,
                        onToggle = vm::setShowMediaCustomActions,
                        scale = itemFisheye(listState, "media_custom_actions_toggle", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("power_menu_button") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_cc_power),
                        subtitle = if (accessibilityServiceEnabled) {
                            stringResource(R.string.settings_cc_power_accessible)
                        } else {
                            stringResource(R.string.settings_cc_power_require)
                        },
                        checked = powerMenuButtonEnabled,
                        enabled = true,
                        onToggle = { enabled ->
                            vm.setPowerMenuButtonEnabled(enabled)
                            accessibilityServiceEnabled = FlueAccessibilityService.isEnabled(context) || accessibilityServiceConnected
                            if (enabled && !accessibilityServiceEnabled) {
                                openAccessibilitySettings()
                            }
                        },
                        scale = itemFisheye(listState, "power_menu_button", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.GESTURE_SETTINGS -> SettingsPageScaffold(
            title = stringResource(R.string.settings_gestures_title),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.GESTURE_SETTINGS).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.GESTURE_SETTINGS).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.GESTURE_SETTINGS, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("double_tap_lock_screen") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_double_tap_lock),
                        subtitle = if (doubleTapUsesDeviceAdmin && deviceAdminEnabled) {
                            stringResource(R.string.settings_double_tap_device_admin_on)
                        } else if (doubleTapUsesDeviceAdmin) {
                            stringResource(R.string.settings_double_tap_device_admin_require)
                        } else if (accessibilityServiceEnabled) {
                            stringResource(R.string.settings_double_tap_on)
                        } else {
                            stringResource(R.string.settings_double_tap_require)
                        },
                        checked = doubleTapLockScreenEnabled,
                        onToggle = { enabled ->
                            vm.setDoubleTapLockScreenEnabled(enabled)
                            if (doubleTapUsesDeviceAdmin) {
                                deviceAdminEnabled = FlueDeviceAdminController.isActive(context)
                                if (enabled && !deviceAdminEnabled) {
                                    openDeviceAdminSettings()
                                }
                            } else {
                                accessibilityServiceEnabled = FlueAccessibilityService.isEnabled(context) || accessibilityServiceConnected
                                if (enabled && !accessibilityServiceEnabled) {
                                    openAccessibilitySettings()
                                }
                            }
                        },
                        scale = itemFisheye(listState, "double_tap_lock_screen", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("face_ignores_back_key") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_face_ignores_back),
                        subtitle = stringResource(R.string.settings_face_ignores_back_sub),
                        checked = faceIgnoresBackKey,
                        onToggle = vm::setFaceIgnoresBackKey,
                        scale = itemFisheye(listState, "face_ignores_back_key", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.APP_LIST_BEHAVIOR -> SettingsPageScaffold(
            title = stringResource(R.string.settings_app_list_behavior_title),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.APP_LIST_BEHAVIOR).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.APP_LIST_BEHAVIOR).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.APP_LIST_BEHAVIOR, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("app_list_watchface_colors") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_watchface_launch_mask),
                        subtitle = if (appListWatchFaceColors) {
                            stringResource(R.string.settings_watchface_launch_mask_on)
                        } else {
                            stringResource(R.string.settings_watchface_launch_mask_off)
                        },
                        checked = appListWatchFaceColors,
                        onToggle = vm::setAppListWatchFaceColors,
                        scale = itemFisheye(listState, "app_list_watchface_colors", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("app_list_row_border") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_list_row_bg),
                        subtitle = if (appListRowBorderEnabled) {
                            stringResource(R.string.settings_list_row_bg_on)
                        } else {
                            stringResource(R.string.settings_list_row_bg_off)
                        },
                        checked = appListRowBorderEnabled,
                        enabled = layoutMode == LayoutMode.List,
                        onToggle = vm::setAppListRowBorderEnabled,
                        scale = itemFisheye(listState, "app_list_row_border", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("app_list_folders_enabled") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_allow_folders),
                        subtitle = if (appListFoldersEnabled) {
                            stringResource(R.string.settings_allow_folders_on)
                        } else {
                            stringResource(R.string.settings_allow_folders_off)
                        },
                        checked = appListFoldersEnabled,
                        onToggle = vm::setAppListFoldersEnabled,
                        scale = itemFisheye(listState, "app_list_folders_enabled", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.PERFORMANCE -> SettingsPageScaffold(
            title = stringResource(R.string.settings_performance_title),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.PERFORMANCE).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.PERFORMANCE).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.PERFORMANCE, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            // 性能页拆成三组：这一层只做目录，具体开关全在子页。
            // 原来 13 项平铺在一页，滑到底要转很久，且「图标怎么画」「动画怎么
            // 走」「系统与网络行为」三类东西混在一起没有边界。
            flueSettingsGroup {
                row("perf_group_rendering") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_perf_group_rendering),
                        subtitle = stringResource(R.string.settings_perf_group_rendering_sub),
                        onClick = { navigateTo(SettingsDestination.PERFORMANCE_RENDERING) },
                        scale = itemFisheye(listState, "perf_group_rendering", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("perf_group_motion") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_perf_group_motion),
                        subtitle = stringResource(R.string.settings_perf_group_motion_sub),
                        onClick = { navigateTo(SettingsDestination.PERFORMANCE_MOTION) },
                        scale = itemFisheye(listState, "perf_group_motion", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("perf_group_system") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_perf_group_system),
                        subtitle = stringResource(R.string.settings_perf_group_system_sub),
                        onClick = { navigateTo(SettingsDestination.PERFORMANCE_SYSTEM) },
                        scale = itemFisheye(listState, "perf_group_system", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.PERFORMANCE_RENDERING -> SettingsPageScaffold(
            title = stringResource(R.string.settings_perf_group_rendering),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.PERFORMANCE_RENDERING).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.PERFORMANCE_RENDERING).offset,
            onScrollChanged = { index, offset ->
                updateScroll(SettingsDestination.PERFORMANCE_RENDERING, index, offset)
            }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("low_res") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_low_res_icons),
                        subtitle = stringResource(R.string.settings_low_res_icons_sub),
                        checked = lowResIcons,
                        onToggle = { vm.setLowResIcons(it) },
                        scale = itemFisheye(listState, "low_res", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("icon_shadow") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_icon_shadow),
                        subtitle = stringResource(R.string.settings_icon_shadow_sub),
                        checked = iconShadowEnabled,
                        onToggle = vm::setIconShadowEnabled,
                        scale = itemFisheye(listState, "icon_shadow", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("builtin_manager_thumbnails") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_builtin_thumbnails),
                        subtitle = stringResource(R.string.settings_builtin_thumbnails_sub),
                        checked = builtInManagerThumbnails,
                        onToggle = { vm.setBuiltInManagerThumbnails(it) },
                        scale = itemFisheye(listState, "builtin_manager_thumbnails", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.PERFORMANCE_MOTION -> SettingsPageScaffold(
            title = stringResource(R.string.settings_perf_group_motion),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.PERFORMANCE_MOTION).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.PERFORMANCE_MOTION).offset,
            onScrollChanged = { index, offset ->
                updateScroll(SettingsDestination.PERFORMANCE_MOTION, index, offset)
            }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("anim_override") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_launch_animation),
                        subtitle = stringResource(R.string.settings_launch_animation_sub),
                        checked = animationOverrideEnabled,
                        onToggle = { vm.setAnimationOverrideEnabled(it) },
                        scale = itemFisheye(listState, "anim_override", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("app_list_return_shrink") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_app_list_shrink),
                        subtitle = stringResource(R.string.settings_app_list_shrink_sub),
                        checked = appListReturnShrinkEnabled,
                        onToggle = vm::setAppListReturnShrinkEnabled,
                        scale = itemFisheye(listState, "app_list_return_shrink", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            if (!appListReturnShrinkEnabled) {
                item("app_list_return_icon_float") {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_icon_float),
                        subtitle = stringResource(R.string.settings_icon_float_sub),
                        checked = appListReturnIconFloatEnabled,
                        onToggle = vm::setAppListReturnIconFloatEnabled,
                        scale = itemFisheye(
                            listState,
                            "app_list_return_icon_float",
                            screenCenterY,
                            screenHeightPx,
                        )
                    )
                }
            }
            item("face_return_shrink") {
                // 与「减弱动态效果」已解绑：那个开关只管去掉越界回弹，不再
                // 反过来把收缩强制打开、把这一行锁成灰的。
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_face_shrink),
                    subtitle = stringResource(R.string.settings_face_shrink_sub),
                    checked = faceReturnShrinkEnabled,
                    onToggle = vm::setFaceReturnShrinkEnabled,
                    scale = itemFisheye(listState, "face_return_shrink", screenCenterY, screenHeightPx)
                )
            }
            flueSettingsGroup {
                row("legacy_app_list_transition") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_legacy_transition),
                        subtitle = stringResource(R.string.settings_legacy_transition_sub),
                        checked = legacyAppListTransitionEnabled,
                        onToggle = vm::setLegacyAppListTransitionEnabled,
                        scale = itemFisheye(
                            listState,
                            "legacy_app_list_transition",
                            screenCenterY,
                            screenHeightPx,
                        ),
                        groupPosition = position,
                    )
                }
                row("app_list_stagger") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_stagger_entry),
                        subtitle = stringResource(R.string.settings_stagger_entry_sub),
                        checked = appListStaggerEnabled,
                        onToggle = vm::setAppListStaggerEnabled,
                        scale = itemFisheye(listState, "app_list_stagger", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("reduce_dynamic_effects") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_reduce_motion),
                        subtitle = stringResource(R.string.settings_reduce_motion_sub),
                        checked = reduceDynamicEffects,
                        // 不再顺手把表盘收缩打开：两个开关互不干涉。
                        onToggle = vm::setReduceDynamicEffects,
                        scale = itemFisheye(listState, "reduce_dynamic_effects", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            item("music_text_animation") {
                val selectedPreset = MusicTextSwitchAnimations.byId(musicTextSwitchAnimation)
                SettingsCategoryCard(
                    title = stringResource(R.string.settings_music_text_animation),
                    subtitle = stringResource(selectedPreset.labelRes),
                    onClick = { navigateTo(SettingsDestination.MUSIC_TEXT_ANIMATION) },
                    scale = itemFisheye(listState, "music_text_animation", screenCenterY, screenHeightPx)
                )
            }
            item("motion_profile") {
                SettingsCategoryCard(
                    title = stringResource(R.string.settings_motion_profile),
                    subtitle = stringResource(
                        R.string.settings_motion_profile_sub_fmt,
                        activeMotionProfile.name,
                        if (activeMotionProfile.bounceEnabled) {
                            stringResource(R.string.settings_motion_bounce_on)
                        } else {
                            stringResource(R.string.settings_motion_bounce_off)
                        }
                    ),
                    onClick = { navigateTo(SettingsDestination.MOTION_PROFILE) },
                    scale = itemFisheye(listState, "motion_profile", screenCenterY, screenHeightPx)
                )
            }
        }

            SettingsDestination.PERFORMANCE_SYSTEM -> SettingsPageScaffold(
            title = stringResource(R.string.settings_perf_group_system),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.PERFORMANCE_SYSTEM).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.PERFORMANCE_SYSTEM).offset,
            onScrollChanged = { index, offset ->
                updateScroll(SettingsDestination.PERFORMANCE_SYSTEM, index, offset)
            }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("hide_from_recents") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_hide_recents),
                        subtitle = stringResource(R.string.settings_hide_recents_sub),
                        checked = hideFromRecents,
                        onToggle = vm::setHideFromRecents,
                        scale = itemFisheye(listState, "hide_from_recents", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("watch_store_segmented_download") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_watch_store_segmented_download),
                        subtitle = stringResource(R.string.settings_watch_store_segmented_download_sub),
                        checked = watchStoreSegmentedDownload,
                        onToggle = vm::setWatchStoreSegmentedDownload,
                        scale = itemFisheye(listState, "watch_store_segmented_download", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.MOTION_PROFILE -> SettingsPageScaffold(
            title = stringResource(R.string.settings_motion_profile_title),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.MOTION_PROFILE).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.MOTION_PROFILE).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.MOTION_PROFILE, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            val motionProfileEditable = !FlueMotionProfiles.isBuiltIn(activeMotionProfile.id)
            item("motion_intro") {
                MessageCard(
                    text = stringResource(R.string.settings_motion_profile_hint),
                    background = WatchColors.SurfaceGlass,
                    onClick = {}
                )
            }
            items(
                items = activeMotionProfileOptions(motionProfiles),
                key = { "motion_profile_${it.id}" },
                contentType = { "motion_profile" },
            ) { profile ->
                SettingsChoiceRow(
                    title = profile.name,
                    subtitle = if (FlueMotionProfiles.isBuiltIn(profile.id)) {
                        stringResource(R.string.settings_motion_profile_preset)
                    } else {
                        stringResource(R.string.settings_motion_profile_custom)
                    },
                    selected = profile.id == activeMotionProfile.id,
                    onClick = { vm.setActiveMotionProfile(profile.id) },
                    scale = itemFisheye(listState, "motion_profile_${profile.id}", screenCenterY, screenHeightPx)
                )
            }
            flueSettingsGroup {
                row("motion_copy") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_motion_profile_copy),
                        subtitle = stringResource(R.string.settings_motion_profile_copy_sub),
                        onClick = { vm.copyMotionProfile() },
                        scale = itemFisheye(listState, "motion_copy", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("motion_profile_rename") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_profile_rename),
                        subtitle = if (FlueMotionProfiles.isBuiltIn(activeMotionProfile.id)) {
                            stringResource(R.string.settings_profile_rename_builtin_sub)
                        } else {
                            activeMotionProfile.name
                        },
                        onClick = {
                            if (!FlueMotionProfiles.isBuiltIn(activeMotionProfile.id)) {
                                renameMotionProfileVisible = true
                            }
                        },
                        scale = itemFisheye(listState, "motion_profile_rename", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("motion_delete") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_motion_profile_delete),
                        subtitle = stringResource(R.string.settings_motion_profile_delete_sub),
                        onClick = { vm.deleteMotionProfile(activeMotionProfile.id) },
                        scale = itemFisheye(listState, "motion_delete", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("motion_bounce") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_motion_bounce),
                        subtitle = stringResource(R.string.settings_motion_bounce_sub),
                        checked = activeMotionProfile.bounceEnabled,
                        enabled = motionProfileEditable,
                        onToggle = { vm.setMotionBounceEnabled(it) },
                        scale = itemFisheye(listState, "motion_bounce", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("motion_edge_strength") { position ->
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_motion_bounce_strength),
                        value = activeMotionProfile.edgeStrength.toFloat(),
                        valueText = "${activeMotionProfile.edgeStrength}/10",
                        localValueText = { "${it.roundToInt()}/10" },
                        range = 0f..10f,
                        steps = 9,
                        enabled = motionProfileEditable,
                        onValueChange = { vm.setMotionEdgeStrength(it.roundToInt()) },
                        scale = itemFisheye(listState, "motion_edge_strength", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
            MotionPhaseKey.entries.forEach { key ->
                val phase = activeMotionProfile.phase(key)
                val keyName = key.motionPhaseLabel(context)
                item("motion_section_$key") {
                    SectionTitle(
                        text = "$keyName · ${phase.easing.motionEasingSummary(context)}",
                        scale = itemFisheye(listState, "motion_section_$key", screenCenterY, screenHeightPx),
                    )
                }
                flueSettingsGroup {
                    row("motion_duration_$key") { position ->
                        SettingsSliderRow(
                            title = stringResource(R.string.settings_motion_phase_duration_fmt, keyName),
                            value = phase.durationMillis.toFloat(),
                            valueText = "${phase.durationMillis} ms",
                            localValueText = { "${it.roundToInt()} ms" },
                            range = 80f..1200f,
                            steps = 55,
                            enabled = motionProfileEditable,
                            onValueChangeFinished = { vm.setMotionPhaseDuration(key, it.roundToInt()) },
                            onValueChange = { },
                            scale = itemFisheye(listState, "motion_duration_$key", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                    row("motion_handoff_$key") { position ->
                        SettingsSliderRow(
                            title = stringResource(R.string.settings_motion_phase_delay_fmt, keyName),
                            value = phase.handoffMillis.toFloat(),
                            valueText = "${phase.handoffMillis} ms",
                            localValueText = { "${it.roundToInt()} ms" },
                            range = 0f..500f,
                            steps = 49,
                            enabled = motionProfileEditable,
                            onValueChangeFinished = { vm.setMotionPhaseHandoff(key, it.roundToInt()) },
                            onValueChange = { },
                            scale = itemFisheye(listState, "motion_handoff_$key", screenCenterY, screenHeightPx),
                            groupPosition = position,
                        )
                    }
                }
                item("motion_easing_cubic_$key") {
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_motion_bezier),
                        subtitle = stringResource(R.string.settings_motion_bezier_sub),
                        selected = phase.easing is MotionEasing.CubicBezier,
                        enabled = motionProfileEditable,
                        onClick = {
                            vm.setMotionPhaseEasing(
                                key,
                                MotionEasing.CubicBezier(0.22f, 1f, 0.36f, 1f),
                            )
                        },
                        scale = itemFisheye(listState, "motion_easing_cubic_$key", screenCenterY, screenHeightPx)
                    )
                }
                item("motion_easing_spring_$key") {
                    SettingsChoiceRow(
                        title = stringResource(R.string.settings_motion_spring),
                        subtitle = stringResource(R.string.settings_motion_spring_sub),
                        selected = phase.easing is MotionEasing.Spring,
                        enabled = motionProfileEditable,
                        onClick = {
                            vm.setMotionPhaseEasing(
                                key,
                                MotionEasing.Spring(dampingRatio = 0.78f, stiffness = 420f),
                            )
                        },
                        scale = itemFisheye(listState, "motion_easing_spring_$key", screenCenterY, screenHeightPx)
                    )
                }
                when (val easing = phase.easing) {
                    is MotionEasing.CubicBezier -> {
                        listOf(
                            "x1" to easing.x1,
                            "y1" to easing.y1,
                            "x2" to easing.x2,
                            "y2" to easing.y2,
                        ).forEach { (parameter, parameterValue) ->
                            val isX = parameter.startsWith("x")
                            item("motion_${key}_$parameter") {
                                SettingsSliderRow(
                                    title = "$keyName $parameter",
                                    value = parameterValue,
                                    valueText = "%.2f".format(Locale.US, parameterValue),
                                    localValueText = { "%.2f".format(Locale.US, it) },
                                    range = if (isX) 0f..1f else -2f..2f,
                                    steps = if (isX) 19 else 39,
                                    enabled = motionProfileEditable,
                                    onValueChange = { },
                                    onValueChangeFinished = { nextValue ->
                                        val next = when (parameter) {
                                            "x1" -> easing.copy(x1 = nextValue)
                                            "y1" -> easing.copy(y1 = nextValue)
                                            "x2" -> easing.copy(x2 = nextValue)
                                            else -> easing.copy(y2 = nextValue)
                                        }
                                        vm.setMotionPhaseEasing(key, next)
                                    },
                                    scale = itemFisheye(
                                        listState,
                                        "motion_${key}_$parameter",
                                        screenCenterY,
                                        screenHeightPx,
                                    )
                                )
                            }
                        }
                    }

                    is MotionEasing.Spring -> {
                        flueSettingsGroup {
                            row("motion_${key}_damping") { position ->
                                SettingsSliderRow(
                                    title = stringResource(R.string.settings_motion_damping_fmt, keyName),
                                    value = easing.dampingRatio,
                                    valueText = "%.2f".format(Locale.US, easing.dampingRatio),
                                    localValueText = { "%.2f".format(Locale.US, it) },
                                    range = 0.05f..2f,
                                    steps = 38,
                                    enabled = motionProfileEditable,
                                    onValueChange = { },
                                    onValueChangeFinished = { nextValue ->
                                        vm.setMotionPhaseEasing(
                                            key,
                                            easing.copy(dampingRatio = nextValue),
                                        )
                                    },
                                    scale = itemFisheye(
                                        listState,
                                        "motion_${key}_damping",
                                        screenCenterY,
                                        screenHeightPx,
                                    ),
                                    groupPosition = position,
                                )
                            }
                            row("motion_${key}_stiffness") { position ->
                                SettingsSliderRow(
                                    title = stringResource(R.string.settings_motion_stiffness_fmt, keyName),
                                    value = easing.stiffness,
                                    valueText = "${easing.stiffness.roundToInt()}",
                                    localValueText = { "${it.roundToInt()}" },
                                    range = 10f..3000f,
                                    steps = 58,
                                    enabled = motionProfileEditable,
                                    onValueChange = { },
                                    onValueChangeFinished = { nextValue ->
                                        vm.setMotionPhaseEasing(
                                            key,
                                            easing.copy(stiffness = nextValue),
                                        )
                                    },
                                    scale = itemFisheye(
                                        listState,
                                        "motion_${key}_stiffness",
                                        screenCenterY,
                                        screenHeightPx,
                                    ),
                                    groupPosition = position,
                                )
                            }
                        }
                    }
                }
            }
            flueSettingsGroup {
                row("motion_export") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_motion_export_curves),
                        subtitle = stringResource(R.string.settings_motion_export_curves_sub),
                        onClick = { motionExportPicker.launch("flue-motion-profiles.json") },
                        scale = itemFisheye(listState, "motion_export", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("motion_import") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_motion_import_curves),
                        subtitle = stringResource(R.string.settings_motion_import_curves_sub),
                        onClick = { motionImportPicker.launch(arrayOf("application/json", "text/plain")) },
                        scale = itemFisheye(listState, "motion_import", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.MUSIC_TEXT_ANIMATION -> SettingsPageScaffold(
            title = stringResource(R.string.settings_music_text_animation),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.MUSIC_TEXT_ANIMATION).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.MUSIC_TEXT_ANIMATION).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.MUSIC_TEXT_ANIMATION, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            items(
                items = MusicTextSwitchAnimations.presets,
                key = { "music_text_anim_${it.id}" },
                contentType = { "music_text_animation" },
            ) { preset ->
                SettingsChoiceRow(
                    title = stringResource(preset.labelRes),
                    subtitle = stringResource(preset.subtitleRes),
                    selected = MusicTextSwitchAnimations.normalizeId(musicTextSwitchAnimation) == preset.id,
                    onClick = { vm.setMusicTextSwitchAnimation(preset.id) },
                    scale = itemFisheye(listState, "music_text_anim_${preset.id}", screenCenterY, screenHeightPx)
                )
            }
        }

            SettingsDestination.TOOLS -> SettingsPageScaffold(
            title = stringResource(R.string.settings_tools_title),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.TOOLS).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.TOOLS).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.TOOLS, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("backup_menu") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_backup_menu),
                        subtitle = stringResource(R.string.settings_backup_menu_sub),
                        onClick = { navigateTo(SettingsDestination.BACKUP) },
                        scale = itemFisheye(listState, "backup_menu", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("export_log") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_export_log),
                        subtitle = stringResource(R.string.settings_export_log_sub),
                        onClick = { settingsScope.launch { exportLog(context) } },
                        scale = itemFisheye(listState, "export_log", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("watch_store_preview_cache") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_clear_watch_store_cache),
                        subtitle = stringResource(R.string.settings_clear_watch_store_cache_sub),
                        onClick = {
                            settingsScope.launch {
                                val message = runCatching { watchCatalogRepository.clearPreviewCache() }
                                    .fold(
                                        onSuccess = { removed ->
                                            context.getString(R.string.settings_clear_watch_store_cache_done, removed)
                                        },
                                        onFailure = {
                                            context.getString(R.string.settings_clear_watch_store_cache_failed)
                                        },
                                    )
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        scale = itemFisheye(
                            listState,
                            "watch_store_preview_cache",
                            screenCenterY,
                            screenHeightPx,
                        ),
                        groupPosition = position,
                    )
                }
                row("watch_store_market_url") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_watch_store_market_url),
                        subtitle = watchStoreUiPreferences.readBaseUrl(),
                        onClick = { customWatchStoreUrlDialogVisible = true },
                        scale = itemFisheye(
                            listState,
                            "watch_store_market_url",
                            screenCenterY,
                            screenHeightPx,
                        ),
                        groupPosition = position,
                    )
                }
                row("web_zoom") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_web_zoom),
                        subtitle = "$webZoomPercent%",
                        onClick = { webZoomDialogVisible = true },
                        scale = itemFisheye(listState, "web_zoom", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("clear_web_data") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_clear_web_data),
                        subtitle = stringResource(R.string.settings_clear_web_data_sub),
                        // 二次确认：这一项清的是**网页版的登录态**，而它和应用内市场账号是
                        // 两套凭据 —— 误触之后「我明明登录过」的困惑代价很高。
                        onClick = { clearWebDataDialogVisible = true },
                        scale = itemFisheye(listState, "clear_web_data", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("diagnostic_logging") { position ->
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_diagnostic_logging),
                        subtitle = if (diagnosticLoggingEnabled) {
                            stringResource(R.string.settings_diagnostic_logging_on)
                        } else {
                            stringResource(R.string.settings_diagnostic_logging_off)
                        },
                        checked = diagnosticLoggingEnabled,
                        onToggle = { enabled ->
                            diagnosticLoggingEnabled = enabled
                            FlueLog.setDiagnosticLoggingEnabled(context, enabled, DIAGNOSTIC_LOG_DURATION_MS)
                        },
                        scale = itemFisheye(listState, "diagnostic_logging", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("reset_defaults") { position ->
                    ActionCard(
                        title = stringResource(R.string.settings_reset_defaults),
                        subtitle = stringResource(R.string.settings_reset_defaults_sub),
                        onClick = { vm.resetSettings() },
                        scale = itemFisheye(listState, "reset_defaults", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.BACKUP -> SettingsPageScaffold(
            title = stringResource(R.string.settings_backup_menu),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.BACKUP).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.BACKUP).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.BACKUP, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            flueSettingsGroup {
                row("backup_export_page") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_backup_export_page),
                        subtitle = stringResource(R.string.settings_backup_export_page_sub),
                        onClick = { navigateTo(SettingsDestination.BACKUP_EXPORT) },
                        scale = itemFisheye(listState, "backup_export_page", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
                row("backup_import_page") { position ->
                    SettingsCategoryCard(
                        title = stringResource(R.string.settings_backup_import_page),
                        subtitle = stringResource(R.string.settings_backup_import_page_sub),
                        onClick = { navigateTo(SettingsDestination.BACKUP_IMPORT) },
                        scale = itemFisheye(listState, "backup_import_page", screenCenterY, screenHeightPx),
                        groupPosition = position,
                    )
                }
            }
        }

            SettingsDestination.BACKUP_EXPORT -> SettingsPageScaffold(
            title = stringResource(R.string.settings_backup_export_page),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.BACKUP_EXPORT).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.BACKUP_EXPORT).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.BACKUP_EXPORT, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            backupOptionRows(
                listState = listState,
                screenCenterY = screenCenterY,
                screenHeightPx = screenHeightPx,
                options = effectiveBackupExportOptions,
                availableOptions = FlueBackupOptions(),
                onOptionsChange = { backupExportOptions = it }
            )
            item("export_backup_start") {
                ActionCard(
                    title = stringResource(R.string.settings_backup_export_start),
                    subtitle = stringResource(R.string.settings_backup_export_start_sub),
                    onClick = {
                        if (!effectiveBackupExportOptions.hasAny) {
                            Toast.makeText(context, context.getString(R.string.toast_select_at_least_one), Toast.LENGTH_SHORT).show()
                        } else if (!backupBusy) {
                            pendingBackupExportOptions = effectiveBackupExportOptions
                            backupCreatePicker.launch(vm.suggestedBackupFileName())
                        }
                    },
                    scale = itemFisheye(listState, "export_backup_start", screenCenterY, screenHeightPx)
                )
            }
        }

            SettingsDestination.BACKUP_IMPORT -> SettingsPageScaffold(
            title = stringResource(R.string.settings_backup_import_page),
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.BACKUP_IMPORT).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.BACKUP_IMPORT).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.BACKUP_IMPORT, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            item("import_backup_pick") {
                ActionCard(
                    title = stringResource(R.string.settings_backup_import_pick),
                    subtitle = run {
                        val unknownTime = stringResource(R.string.settings_backup_unknown_time)
                        effectiveBackupImportPreview?.let { preview ->
                            stringResource(
                                R.string.settings_backup_preview_fmt,
                                preview.appVersion,
                                preview.createdAt.ifBlank { unknownTime }
                            )
                        } ?: stringResource(R.string.settings_backup_import_pick_sub)
                    },
                    onClick = {
                        if (!backupBusy) {
                            backupOpenPicker.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        }
                    },
                    scale = itemFisheye(listState, "import_backup_pick", screenCenterY, screenHeightPx)
                )
            }
            effectiveBackupImportPreview?.let { preview ->
                backupOptionRows(
                    listState = listState,
                    screenCenterY = screenCenterY,
                    screenHeightPx = screenHeightPx,
                    options = effectiveBackupImportOptions,
                    availableOptions = preview.availableOptions,
                    onOptionsChange = { backupImportOptions = it.constrainedBy(preview.availableOptions) }
                )
                item("import_backup_start") {
                    ActionCard(
                        title = stringResource(R.string.settings_backup_import_start),
                        subtitle = stringResource(R.string.settings_backup_import_start_sub),
                        onClick = {
                            val uri = backupImportUri
                            if (uri == null) {
                                Toast.makeText(context, context.getString(R.string.toast_pick_backup_first), Toast.LENGTH_SHORT).show()
                            } else if (!effectiveBackupImportOptions.hasAny) {
                                Toast.makeText(context, context.getString(R.string.toast_select_at_least_one), Toast.LENGTH_SHORT).show()
                            } else if (!backupBusy) {
                                val options = effectiveBackupImportOptions
                                settingsScope.launch {
                                    backupBusy = true
                                    backupProgress = BlockingProgressState(
                                        title = context.getString(R.string.progress_import_config),
                                        detail = context.getString(R.string.progress_import_config_detail)
                                    )
                                    val result = vm.importBackup(uri, options)
                                    result
                                        .onSuccess {
                                            backupProgress = BlockingProgressState(
                                                title = context.getString(R.string.progress_import_done),
                                                detail = options.backupSummaryText(context),
                                                completed = true,
                                                completionAction = BlockingProgressCompletionAction.ImportBackup
                                            )
                                            backupImportPreview = null
                                            backupImportUri = null
                                        }
                                        .onFailure { error ->
                                            backupProgress = null
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.toast_import_failed_fmt,
                                                    error.message ?: error.javaClass.simpleName
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    backupBusy = false
                                }
                            }
                        },
                        scale = itemFisheye(listState, "import_backup_start", screenCenterY, screenHeightPx)
                    )
                }
            }
        }

            SettingsDestination.LANGUAGE -> SettingsPageScaffold(
                title = stringResource(R.string.settings_language),
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.LANGUAGE).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.LANGUAGE).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.LANGUAGE, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                com.flue.launcher.i18n.FlueLocaleManager.options.forEach { option ->
                    val key = "lang_${option.code.ifEmpty { "system" }}"
                    item(key) {
                        val label = when {
                            option.code.isEmpty() -> stringResource(R.string.settings_language_follow_system)
                            option.code == "zh"    -> "中文"
                            option.code == "en"    -> "English"
                            option.code == "ja"    -> "日本語"
                            else                   -> option.labelEn
                        }
                        SettingsChoiceRow(
                            title = label,
                            subtitle = "",
                            selected = appLanguage == option.code,
                            onClick = {
                                // 已经是当前语言就不弹重启确认，避免误触白跑一次冷启。
                                if (appLanguage == option.code) return@SettingsChoiceRow
                                pendingLanguageRestart = option.code
                            },
                            scale = itemFisheye(listState, key, screenCenterY, screenHeightPx)
                        )
                    }
                }
                // 英文 / 日文 由 AI 翻译，提示准确性可能不足
                if (appLanguage == "en" || appLanguage == "ja") {
                    item("lang_ai_disclaimer") {
                        MessageCard(
                            text = if (appLanguage == "ja")
                                "英語・日本語の翻訳は AI によって生成されており、完全に正確ではない場合があります。"
                            else
                                "English and Japanese translations are AI-generated and may not be fully accurate.",
                            background = WatchColors.SurfaceGlass,
                            onClick = {}
                        )
                    }
                }
            }

            SettingsDestination.ABOUT -> SettingsPageScaffold(
                title = stringResource(R.string.settings_about_title),
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.ABOUT).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.ABOUT).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.ABOUT, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                item("about_card") {
                    AboutCard(
                        onDonateClick = { navigateTo(SettingsDestination.DONATE) },
                        onVersionClick = {
                            val next = debugTapState.tap(SystemClock.elapsedRealtime())
                            debugTapState = next
                            if (next.openDebug) navigateTo(SettingsDestination.DEBUG)
                        },
                        scale = itemFisheye(listState, "about_card", screenCenterY, screenHeightPx)
                    )
                }
            }
            SettingsDestination.DEBUG -> DebugPage(
                vm = vm,
                weatherRepository = weatherRepository,
                watchCatalogRepository = watchCatalogRepository,
                onBack = { handleBack() },
                onCrash = { error("Flue debug crash") },
            )
            SettingsDestination.DONATE -> SettingsPageScaffold(
                title = stringResource(R.string.settings_donate_title),
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.DONATE).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.DONATE).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.DONATE, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                item("donate_tip") {
                    MessageCard(
                        text = stringResource(R.string.settings_donate_tip),
                        background = Color(0xFF1A2233),
                        onClick = {}
                    )
                }
                item("donate_wechat") {
                    DonateMethodCard(
                        title = stringResource(R.string.settings_donate_wechat),
                        subtitle = stringResource(R.string.settings_donate_preview_tap),
                        resId = R.drawable.donate_wechat,
                        scale = itemFisheye(listState, "donate_wechat", screenCenterY, screenHeightPx),
                        onPreviewClick = { donatePreviewResId = R.drawable.donate_wechat }
                    )
                }
                item("donate_alipay") {
                    DonateMethodCard(
                        title = stringResource(R.string.settings_donate_alipay),
                        subtitle = stringResource(R.string.settings_donate_preview_tap),
                        resId = R.drawable.donate_alipay,
                        scale = itemFisheye(listState, "donate_alipay", screenCenterY, screenHeightPx),
                        onPreviewClick = { donatePreviewResId = R.drawable.donate_alipay }
                    )
                }
            }
            }
            }
            }
            }
            if (destinationTransition.isRunning) {
                // 新旧设置页在推拉期间会同时留在树上；用最上层命中面锁住
                // 这 260ms，避免快速二次点击落到正在滑出的旧页面。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(100f)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false).consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) break
                                }
                            }
                        },
                )
            }
        }

        donatePreviewResId?.let { resId ->
            DonatePreviewDialog(resId = resId, onDismiss = { donatePreviewResId = null })
        }

        if (watchFaceLongPressModeDialogVisible) {
            FlueSingleChoiceDialog(
                onDismissRequest = { watchFaceLongPressModeDialogVisible = false },
                title = stringResource(R.string.settings_watchface_long_press_mode),
                options = listOf(
                    WatchFaceLongPressMode.CARDS to
                        stringResource(R.string.settings_watchface_long_press_mode_cards),
                    WatchFaceLongPressMode.LIST to
                        stringResource(R.string.settings_watchface_long_press_mode_list),
                ),
                selected = watchFaceLongPressMode,
                onSelect = vm::setWatchFaceLongPressMode,
                cancelText = stringResource(android.R.string.cancel),
            )
        }

        if (weatherCityDialogVisible) {
            FlueAlertDialog(
                onDismissRequest = { weatherCityDialogVisible = false },
                title = { Text(stringResource(R.string.settings_watch_weather_city_dialog_title)) },
                // T-06：关掉外壳整块滚动，「搜索 / 取消」才钉得住卡底。
                // 候选城市能出七八条，原来叠着两层滚动（外壳 + 这里的 verticalScroll），
                // 内层吞掉纵向拖动、外层滚不动，按钮就摸不到了。
                scrollableContent = false,
                text = {
                    Column(
                        // 高度不再写死 360dp：`scrollableContent = false` 之后这里拿到的是
                        // 「卡高减掉标题与按钮」的剩余约束，内容超出时由这一层自己滚。
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.settings_watch_weather_city_dialog_message))
                        OutlinedTextField(
                            value = weatherCityDraft,
                            onValueChange = {
                                weatherCityDraft = it.take(120)
                                weatherCityCandidates = emptyList()
                                weatherCitySearchError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_watch_weather_city)) },
                            placeholder = {
                                Text(stringResource(R.string.settings_watch_weather_city_hint))
                            },
                            singleLine = true,
                        )
                        if (weatherCitySearching) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FlueLoadingIndicator(size = 18.dp)
                                Text(stringResource(R.string.settings_watch_weather_city_searching))
                            }
                        }
                        weatherCitySearchError?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                        weatherCityCandidates.forEach { candidate ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    weatherLocationSelectionGeneration++
                                    weatherRepository.setManualLocation(candidate)
                                    weatherLocationLabel = candidate.label.orEmpty()
                                    weatherUsingSystemLocation = false
                                    weatherCityDialogVisible = false
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.toast_weather_city_saved_fmt,
                                            candidate.label.orEmpty(),
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) {
                                Text(
                                    text = buildString {
                                        append(candidate.label.orEmpty())
                                        append('\n')
                                        append(String.format(Locale.US, "%.4f, %.4f", candidate.latitude, candidate.longitude))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = weatherCityDraft.isNotBlank() && !weatherCitySearching,
                        onClick = {
                            val query = weatherCityDraft.trim().take(120)
                            if (query.isEmpty()) return@TextButton
                            weatherCitySearching = true
                            weatherCityCandidates = emptyList()
                            weatherCitySearchError = null
                            settingsScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { weatherRepository.searchLocations(query) }
                                }
                                weatherCitySearching = false
                                weatherCityCandidates = result.getOrDefault(emptyList())
                                weatherCitySearchError = when {
                                    result.isFailure -> context.getString(R.string.settings_watch_weather_city_search_failed)
                                    weatherCityCandidates.isEmpty() -> context.getString(R.string.settings_watch_weather_city_no_results)
                                    else -> null
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.action_search))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { weatherCityDialogVisible = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        tapActionPicker?.let { action ->
            WatchTapActionAppPickerDialog(
                action = action,
                onDismiss = { tapActionPicker = null },
                onChanged = { tapActionRevision++ },
            )
        }
    if (webZoomDialogVisible) {
        WebZoomInputDialog(
            current = webZoomPercent,
            onDismiss = { webZoomDialogVisible = false },
            onConfirm = { value ->
                webZoomPercent = watchStoreUiPreferences.writeWebZoomPercent(value)
                webZoomDialogVisible = false
            },
        )
    }
    if (clearWebDataDialogVisible) {
        FlueAlertDialog(
            onDismissRequest = { clearWebDataDialogVisible = false },
            title = stringResource(R.string.settings_clear_web_data),
            text = stringResource(R.string.settings_clear_web_data_confirm),
            confirmText = stringResource(android.R.string.ok),
            dismissText = stringResource(android.R.string.cancel),
            onConfirm = {
                clearWebDataDialogVisible = false
                clearFlueWebViewData(context)
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_clear_web_data_done),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }
    if (customWatchStoreUrlDialogVisible) {
            // 与表盘市场共用同一份弹窗（市场拉取失败时要就地弹它，不再跳工具页）。
            WatchStoreBaseUrlDialog(
                preferences = watchStoreUiPreferences,
                onSaved = {
                    customWatchStoreUrlDialogVisible = false
                    settingsScope.launch {
                        runCatching { watchCatalogRepository.clearPreviewCache() }
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_watch_store_market_url_saved),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onDismiss = { customWatchStoreUrlDialogVisible = false },
            )
        }

        pendingLanguageRestart?.let { languageCode ->
            val dialogContext = remember(context, languageCode) {
                FlueLocaleManager.contextForLanguageSelection(context, languageCode)
            }
            FlueAlertDialog(
                onDismissRequest = {
                    if (!languageSaveInProgress) pendingLanguageRestart = null
                },
                title = { Text(dialogContext.getString(R.string.settings_language_restart_title)) },
                text = { Text(dialogContext.getString(R.string.settings_language_restart_message)) },
                confirmButton = {
                    TextButton(
                        enabled = !languageSaveInProgress,
                        onClick = {
                            if (languageSaveInProgress) return@TextButton
                            languageSaveInProgress = true
                            settingsScope.launch {
                                val saved = vm.setAppLanguage(context, languageCode)
                                languageSaveInProgress = false
                                if (saved) {
                                    pendingLanguageRestart = null
                                    restartFlueProcess(context)
                                } else {
                                    Toast.makeText(
                                        context,
                                        dialogContext.getString(R.string.toast_language_save_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    ) { Text(dialogContext.getString(R.string.settings_language_restart_confirm)) }
                },
                dismissButton = {
                    TextButton(
                        enabled = !languageSaveInProgress,
                        onClick = { pendingLanguageRestart = null },
                    ) {
                        Text(dialogContext.getString(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun AppListFlowShapePreview(
    bulgePercent: Int,
    toRight: Boolean,
    curve: com.flue.launcher.util.FlowShapeCurve,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(style.compactShape)
            .background(style.pressedCardColor),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp)) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val rowGap = size.height / 4.5f
            val direction = if (toRight) FlowCrescentDirection.Right else FlowCrescentDirection.Left
            repeat(5) { index ->
                val y = centerY + (index - 2) * rowGap
                val offset = flowCrescentOffsetPx(
                    rowCenterY = y,
                    viewportCenterY = centerY,
                    viewportHeight = size.height,
                    bulgePx = size.width * 0.24f * bulgePercent / 100f,
                    direction = direction,
                    curve = curve,
                )
                drawCircle(
                    color = style.accentColor,
                    radius = 8.dp.toPx(),
                    center = Offset(centerX + offset, y),
                )
                drawLine(
                    color = style.secondaryTextColor.copy(alpha = 0.28f),
                    start = Offset(centerX, y),
                    end = Offset(centerX + offset, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

/**
 * 整进程冷启，让每个 Activity 的 attachBaseContext 重新走一遍
 * FlueLocaleManager.applyLocale。
 *
 * 只 recreate() 当前 Activity 是不够的：桌面那边的 LauncherActivity 还活着，
 * 它持有的 Configuration 仍是旧 locale，切回去就露出没翻译的旧文案。
 * 直接用 CLEAR_TASK 拉起新的 LauncherActivity，再结束旧设置页。
 * 新 Activity 的 attachBaseContext 会重新读取 SharedPreferences 语言真源，
 * 不依赖可能被系统批处理的 AlarmManager，也不需要杀进程留下黑屏窗口。
 */
private fun restartFlueProcess(context: android.content.Context) {
    // 包内同时声明了 LauncherActivity 与 SettingsActivity 两个 LAUNCHER
    // 入口；getLaunchIntentForPackage() 在部分 ROM 上会解析回当前设置页，
    // CLEAR_TASK 后等于又打开旧入口。显式指定桌面 Activity，确保新的
    // attachBaseContext 一定从 SharedPreferences 启动真源读取语言。
    val launchIntent = android.content.Intent(context, LauncherActivity::class.java).apply {
        addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
        )
    }

    runCatching {
        context.startActivity(launchIntent)
        // CLEAR_TASK already removes this Activity in the normal path; finish is
        // an idempotent fallback for OEM task managers that keep the old record.
        (context as? android.app.Activity)?.finish()
    }.onFailure {
        // OEM task managers can reject a task replacement while the Activity is
        // stopping. Keep the language saved and at least refresh this page.
        (context as? android.app.Activity)?.recreate()
    }
}

/**
 * 只登记 key 顺序的影子 [LazyListScope]。
 *
 * 搜索结果要跳到具体某一行需要 key→index，但 LazyListState 只暴露「当前
 * 可见项」的 key，屏幕外的拿不到。把页面的 content 往这个影子 scope 里跑
 * 一遍就能拿到完整顺序——item 的 @Composable 内容一行都不会执行，只登记
 * key，所以没有额外开销也没有副作用（content 本身不是 @Composable）。
 */
/**
 * 脚手架顶栏（返回按钮 + 大标题）那一项的 key。
 *
 * 原先是匿名 `item {}`，圆屏下 `chordScaleForListItem` 靠
 * `visibleItemsInfo.firstOrNull { it.key == key }` 找行，没有 key 就永远找不到，
 * 顶栏因此不跟随缩放（老师报的「设置顶栏不会适配圆屏」）。
 *
 * [SettingsKeyIndexRecorder] 那边同步登记这个 key：它只关心「登记了几项」来对齐
 * index，用具名 key 替换原来的 null 不影响计数，还顺带让搜索跳转不会误命中。
 */
private const val SETTINGS_HEADER_ITEM_KEY = "settings_page_header"

/**
 * 顶栏项高度的一半（估值），圆屏首项居中要用。
 *
 * 顶栏 ≈ 42dp 的返回按钮 + 8dp 底距 ≈ 50dp（26sp 单行标题比按钮矮，撑不高这一项）。
 *
 * 这里刻意取**静态估值**而不是实测高度：把实测高度回头喂给 `contentPadding`
 * 就是「布局 → 内边距 → 重新布局」的反馈环，正是圆屏抽搐的根因。标题换成两行
 * 时首项会比正中略高一点点，可接受。
 */
private val SETTINGS_HEADER_HALF_HEIGHT = 25.dp

/**
 * 普通设置行高度的一半（估值），圆屏底部留白要用。
 *
 * 设置行的 `defaultMinSize(minHeight = 56.dp)` 是这套页面里最常见的行高，取半即
 * 28dp。底部留 `半屏 − 28dp` 之后，末项滑到底时中心大致落在屏幕正中。
 * 同样是静态估值，理由见 [SETTINGS_HEADER_HALF_HEIGHT]。
 */
private val SETTINGS_ROW_HALF_HEIGHT = 28.dp

private class SettingsKeyIndexRecorder : LazyListScope {
    private val keys = ArrayList<Any?>()

    fun indexOf(key: String): Int = keys.indexOfFirst { it == key }

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        keys += key
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        repeat(count) { index -> keys += key?.invoke(index) }
    }

    @ExperimentalFoundationApi
    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        keys += key
    }
}

@Composable
private fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    headerTime: String,
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    roundFirstActionIndex: Int = 1,
    roundFirstActionHalfHeight: Dp = SETTINGS_ROW_HALF_HEIGHT,
    onScrollChanged: (Int, Int) -> Unit = { _, _ -> },
    content: LazyListScope.(LazyListState, Float, Float, Set<Any>) -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val context = LocalContext.current
    val density = LocalDensity.current
    val settingsRoundScreen = com.flue.launcher.ui.common.isFlueScreenRound()
    val leftSafeInsetPercent = LocalSettingsLeftSafeInsetPercent.current
    val rotaryHapticsEnabled = LocalSettingsRotaryHapticsEnabled.current
    val initialPosition = remember(
        settingsRoundScreen,
        initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset,
        roundFirstActionIndex,
    ) {
        settingsInitialPosition(
            isRound = settingsRoundScreen,
            savedIndex = initialFirstVisibleItemIndex,
            savedOffset = initialFirstVisibleItemScrollOffset,
            firstActionIndex = roundFirstActionIndex,
        )
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPosition.index,
        initialFirstVisibleItemScrollOffset = initialPosition.offset
    )
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val rotaryHapticGate = remember { RotaryHapticGate() }
    val latestOnScrollChanged = rememberUpdatedState(onScrollChanged)

    LaunchedEffect(listState) {
        // Persist only when motion settles. Writing every pixel into caller state was the
        // dominant settings-scroll recomposition path, especially in the icon-heavy hidden
        // apps page. onDispose below covers navigation while a gesture is still settling.
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    latestOnScrollChanged.value(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                    )
                }
            }
    }

    DisposableEffect(listState) {
        onDispose {
            latestOnScrollChanged.value(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocusAfterFirstFrame()
    }

    val rotaryScrollMultiplier = ROTARY_SCROLL_MULTIPLIER

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        val screenCenterY = screenHeightPx / 2f
        // 圆屏弦宽适配要用视口全宽当直径。靠 CompositionLocal 下发而不是加
        // 参数：itemFisheye 有 200+ 调用点，加参数等于逐个改一遍。
        val screenWidthPx = constraints.maxWidth.toFloat()
        val leftSafeInset = maxWidth * (leftSafeInsetPercent.coerceIn(0, 50) / 100f)
        val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
        var chordListTopInRoot by remember { mutableFloatStateOf(0f) }
        var chordScreenCenterY by remember { mutableFloatStateOf(screenCenterY) }
        val horizontalContentPadding = if (settingsRoundScreen) 26.dp else 16.dp
        val verticalContentPadding = if (settingsRoundScreen) 30.dp else 18.dp
        // 圆屏上下各留半屏（蜂窝列表观感）：首项在进页面时就落在屏幕正中，
        // 末项也能一直滑到正中。取 `maxHeight / 2 − 半个首项高度`。
        //
        // 这是**静态**值：只看屏幕尺寸，不随滚动变化，所以不违反「禁止动态
        // padding」那条铁律（禁的是「每帧按缩放结果改布局属性」的反馈环）。
        //
        // 另外靠 padding 让首项居中，而不是进页面再 scrollToItem 纠一次：
        // 偏移量为 0 时首项本来就在正中，既省掉一次首帧跳动，也不会跟
        // initialFirstVisibleItemScrollOffset 的滚动位置恢复打架。
        val halfScreenPadding = maxHeight / 2f
        val topContentPadding = if (settingsRoundScreen) {
            (halfScreenPadding - SETTINGS_HEADER_HALF_HEIGHT).coerceAtLeast(46.dp)
        } else {
            verticalContentPadding
        }
        val bottomContentPadding = if (settingsRoundScreen) {
            // 用**本页声明的**行半高，而不是写死的设置行常量。
            //
            // 「末项能滑到屏幕正中」要的是 `半屏 − 半个末项高度`。这一项一直用
            // SETTINGS_ROW_HALF_HEIGHT（28dp，普通设置行），可表盘选择页的条目是
            // 高得多的表盘卡，它自己已经通过 roundFirstActionHalfHeight 把真实半高
            // （40dp）报上来了 —— 只是以前只有居中那条 effect 用它，底部留白没用，
            // 于是这一页的末项落不到正中（老师报的「最后一项到屏幕中间」做不到）。
            //
            // 其余页面该参数取默认值，正是 SETTINGS_ROW_HALF_HEIGHT，行为不变。
            (halfScreenPadding - roundFirstActionHalfHeight).coerceAtLeast(30.dp)
        } else {
            verticalContentPadding
        }
        // 圆屏行距收窄：外圈的项缩到 0.62，槽位高度不变（graphicsLayer 的固有
        // 代价），10dp 槽位间距叠上收缩留白就是老师说的「间隔太大」。槽位间距
        // 压到 6dp 抵消一部分，居中那几行也还不至于挤在一起。
        val itemSpacing = if (settingsRoundScreen) 6.dp else 10.dp

        // 依赖里必须带上 density。
        //
        // 居中偏移是按**当前 density** 把 dp 折成 px 算的，而 density 由
        // `ProvideGlobalUiScale` 按设置里的全局缩放改写。缩放值来自 DataStore 的 Flow，
        // 首帧拿到的是默认 100%，真值随后才到 —— 那一刻 initialPosition 和
        // screenHeightPx 都没变，这个 effect 不会重跑，于是首项一直停在「按 100% 算
        // 出来的」那个偏移上：页面顶/底拉不到屏幕中间，进一次二级页再回来（scaffold
        // 重新组合、listState 重建）才正常，重进设置又坏（老师报的）。
        //
        // screenCenterY 是 px、与 density 无关，但一并列出来更能说明这条 effect
        // 依赖的是「屏幕几何 + 折算比例」这一整套。
        //
        // roundFirstActionHalfHeight 同理必须在列表里：它被读进 itemHalfHeight，
        // 各页取值不同（表盘选择页是 40dp，其余走默认）。漏掉它就是 R10 那个
        // 「读了却没进 key」的同一族错误，只是换了个量。
        LaunchedEffect(
            initialPosition,
            screenHeightPx,
            screenCenterY,
            density,
            roundFirstActionHalfHeight,
        ) {
            if (initialPosition.centerOnScreen) {
                val centerOffset = settingsCenteredItemScrollOffset(
                    screenCenterY = screenCenterY,
                    itemHalfHeight = with(density) { roundFirstActionHalfHeight.toPx() },
                )
                listState.scrollToItem(initialPosition.index, centerOffset)
            }
        }

        // 搜索结果跳转定位。目标 key 不属于本页时 indexOf 返回 -1，本页
        // 什么都不做（转场期间新旧两页同时在树上，靠这条区分）。
        val searchTarget = LocalSettingsSearchTarget.current
        LaunchedEffect(searchTarget?.token, searchTarget?.itemKey) {
            val target = searchTarget ?: return@LaunchedEffect
            val recorder = SettingsKeyIndexRecorder()
            // 标题项也要登记，否则算出来的 index 会整体偏移。
            recorder.item(SETTINGS_HEADER_ITEM_KEY, null) {}
            with(recorder) {
                content(listState, screenCenterY, screenHeightPx, emptySet())
            }
            val index = recorder.indexOf(target.itemKey)
            if (index < 0) return@LaunchedEffect
            // 目标行滚到靠上位置（留一点上文），而不是贴着顶边。
            // 圆屏改成滚到屏幕正中：那里弦最宽、缩放为 1，行本身也最清楚；
            // 靠上 18% 的位置在圆屏上恰好是被弧线切得最狠的一段。
            val jumpOffset = if (settingsRoundScreen) {
                -(screenCenterY - with(density) { SETTINGS_ROW_HALF_HEIGHT.toPx() })
            } else {
                -(screenHeightPx * 0.18f)
            }
            listState.animateScrollToItem(index, scrollOffset = jumpOffset.toInt())
            target.onConsumed()
        }

        CompositionLocalProvider(
            com.flue.launcher.ui.common.LocalChordViewportWidth provides screenWidthPx,
            com.flue.launcher.ui.common.LocalChordListTopInViewport provides chordListTopInRoot,
            com.flue.launcher.ui.common.LocalChordScreenCenterY provides chordScreenCenterY,
            com.flue.launcher.ui.style.LocalFlueSettingsRowSpacing provides itemSpacing,
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    chordListTopInRoot = coordinates.positionInRoot().y
                    chordScreenCenterY = coordinates.findRootCoordinates().size.height / 2f
                }
                .flueRotaryScrollable(
                    focusRequester,
                    rotaryScrollMultiplier,
                    onRotaryDelta = { rotaryDelta ->
                        scope.launch {
                            val consumed = listState.scrollBy(-rotaryDelta)
                            if (
                                rotaryHapticsEnabled &&
                                abs(consumed) > 0.01f &&
                                rotaryHapticGate.tryAcquire(SystemClock.elapsedRealtime())
                            ) {
                                vibrateHaptic(context)
                            }
                        }
                    },
                ) { rotaryDelta ->
                    scope.launch {
                        listState.scrollBy(-rotaryDelta)
                    }
                }
                .flueOverscroll(listState)
                // 触底反馈挂在回弹之后（更靠内层）才收得到边缘余量，见 flueScrollEndHaptic。
                .flueScrollEndHaptic(),
            // The list owns the whole edge-to-edge viewport. Insets only move its items, so
            // the static background still paints behind round corners and transparent bars.
            // Keep a single scrollable top/bottom inset; the old modifier padding + bottom
            // contentPadding + tail spacer stacked into permanent empty bands.
            contentPadding = PaddingValues(
                start = horizontalContentPadding + leftSafeInset,
                // 圆屏那条分支单独取值、不再叠 safeDrawing：半屏留白本来就远大于
                // 任何系统 inset，叠上去只会把首项顶得偏离屏幕中心。
                top = if (settingsRoundScreen) {
                    topContentPadding
                } else {
                    safeDrawingPadding.calculateTopPadding() + topContentPadding
                },
                end = horizontalContentPadding,
                // 行距改由每行自己出（见 FlueSettingsListScope）之后，末行也带着一份，
                // 所以这里扣掉同样的一份 —— 底部总留白与改动前一致。
                bottom = (
                    if (settingsRoundScreen) {
                        bottomContentPadding
                    } else {
                        safeDrawingPadding.calculateBottomPadding() + bottomContentPadding
                    } - itemSpacing
                    ).coerceAtLeast(0.dp),
            ),
            // 间距归零：交给 FlueSettingsListScope 逐行给。verticalArrangement 是整表统一的，
            // 给不出「这两行贴住成一张卡、那两行留 12dp」，而那正是 MIUIX 档要的观感。
            verticalArrangement = Arrangement.Top,
        ) {
            val spacedScope = com.flue.launcher.ui.style.FlueSettingsListScope(this, itemSpacing)
            spacedScope.item(SETTINGS_HEADER_ITEM_KEY) {
                // 顶栏整体（返回按钮 + 大标题）吃弦宽缩放。缩放施加在最外层 Box，
                // 里面的对齐/内边距全是静态值，不会被卷进布局反馈环。
                val headerScale = itemFisheye(
                    listState,
                    SETTINGS_HEADER_ITEM_KEY,
                    screenCenterY,
                    screenHeightPx,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .chordScaled(headerScale)
                        .padding(bottom = 8.dp)
                ) {
                    HeaderBackButton(onClick = onBack)
                    Text(
                        text = title,
                        color = launcherStyle.accentColor,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = if (settingsRoundScreen) TextAlign.Center else TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (settingsRoundScreen) {
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .padding(horizontal = 50.dp)
                        } else {
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth()
                                .padding(start = 54.dp, end = 16.dp)
                        },
                    )
                }
            }
            with(spacedScope) {
                content(listState, screenCenterY, screenHeightPx, emptySet())
            }
        }
        }
    }
}

@Composable
private fun HeaderBackButton(onClick: () -> Unit) {
    val launcherStyle = LauncherTheme.style
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale = animateFloatAsState(if (pressed) 0.94f else 1f, label = "header_back_scale")
    Box(
        modifier = Modifier
            .size(42.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(CircleShape)
            .background(launcherStyle.topBarChipColor)
            .instantPressGesture(pressedState, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 返回图标统一用 miuix 官方的 Back（细尖角箭头），与表盘市场 / 编辑器 /
        // 文件浏览器的顶栏一致。Material 那枚自动镜像返回箭头是带横杆的实心造型，
        // 混在 MIUIX 档里线宽和体量都对不上，所以设置页不再引用它。
        Icon(
            OfficialMiuixIcons.OfficialBack,
            contentDescription = stringResource(R.string.common_back),
            tint = launcherStyle.topBarTextColor,
        )
    }
}

@Composable
private fun SettingsCategoryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    scale: Float,
    groupPosition: FlueGroupPosition = FlueGroupPosition.Single,
) {
    val launcherStyle = LauncherTheme.style
    val horizontalPadding = if (com.flue.launcher.ui.common.isFlueScreenRound()) 24.dp else 18.dp
    if (launcherStyle.uiStyle == UiStyle.MIUIX) {
        // MIUIX：成组大圆角卡片包一行设置行 + Forward 箭头。
        MiuixSettingsCard(
            modifier = Modifier.chordScaled(scale),
            roundRole = RoundElementRole.CAPSULE,
            groupPosition = groupPosition,
        ) {
            MiuixSettingsRow(
                title = title,
                subtitle = subtitle.takeIf { it.isNotBlank() },
                onClick = onClick,
                trailing = {
                    Icon(
                        imageVector = MiuixIcons.Forward,
                        contentDescription = null,
                        tint = launcherStyle.secondaryTextColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
        return
    }
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale = animateFloatAsState(
        if (pressed) 0.958f else 1f,
        animationSpec = launcherStyle.pressScaleSpec,
        label = "settings_category_scale"
    )
    val background = animateColorAsState(
        if (pressed) launcherStyle.pressedCardColor else launcherStyle.cardColor,
        label = "settings_category_bg"
    )
    // 另两档风格不做行合并（flueGroupingEnabled 只对 MIUIX 为真），所以这里的
    // groupPosition 必定是 Single，形状与改动前逐位相同。
    val cardShape = launcherStyle.roundAwareCardShape(RoundElementRole.CAPSULE)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale.value
                scaleY = scale * pressedScale.value
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clip(cardShape)
            .drawBehind { drawRect(background.value) }
            .border(1.dp, launcherStyle.outlineColor, cardShape)
            .instantPressGesture(pressedState, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .padding(end = 12.dp)
            ) {
                Text(title, color = launcherStyle.titleColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, color = launcherStyle.secondaryTextColor, fontSize = 13.sp)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = launcherStyle.accentColor)
        }
    }
}

@Composable
private fun SectionTitle(text: String, scale: Float) {
    val launcherStyle = LauncherTheme.style
    // 圆屏：组标题改居中并去掉左内边距。左对齐的标题贴着弧线，实测「系统设置」
    // 会被切成「⼄设置」。这是静态判断（只看屏幕形状，不随滚动变化），不属于
    // 「动态 padding」那条禁令。
    val roundScreen = com.flue.launcher.ui.common.isFlueScreenRound()
    // 标题与分组名同样吃弦宽缩放；alpha 跟着收一点，让贴近弧线的标题自然淡出。
    val scaleModifier = Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = scale.coerceIn(0.55f, 1f)
    }
    if (launcherStyle.uiStyle == UiStyle.MIUIX) {
        // MIUIX：组标题小灰字，对齐 MiuixSettingsCard 的 title 风格。
        Text(
            text = text,
            color = launcherStyle.secondaryTextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = if (roundScreen) TextAlign.Center else null,
            modifier = Modifier
                .then(if (roundScreen) Modifier.fillMaxWidth() else Modifier)
                .padding(top = 8.dp, start = if (roundScreen) 0.dp else 14.dp)
                .then(scaleModifier)
        )
        return
    }
    Text(
        text = text,
        color = launcherStyle.secondaryTextColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        textAlign = if (roundScreen) TextAlign.Center else null,
        modifier = Modifier
            .then(if (roundScreen) Modifier.fillMaxWidth() else Modifier)
            .padding(top = 4.dp, start = if (roundScreen) 0.dp else 4.dp, bottom = 2.dp)
            .then(scaleModifier)
    )
}

@Composable
private fun rememberPressedState(): MutableState<Boolean> {
    val state = remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { state.value = false }
    }
    return state
}

private fun Modifier.instantPressGesture(
    pressedState: MutableState<Boolean>,
    enabled: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    longPressDurationMs: Long? = null,
    onClick: () -> Unit
): Modifier {
    if (!enabled) return this
    if (onLongPress != null && longPressDurationMs != null) {
        return pointerInput(onClick, onLongPress, enabled, longPressDurationMs) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressedState.value = true
                var completedBeforeTimeout = false
                val up = withTimeoutOrNull(longPressDurationMs) {
                    waitForUpOrCancellation().also { completedBeforeTimeout = true }
                }
                if (completedBeforeTimeout) {
                    pressedState.value = false
                    if (up != null) onClick()
                } else {
                    pressedState.value = false
                    onLongPress()
                    waitForUpOrCancellation()
                }
            }
        }
    }
    return pointerInput(onClick, onLongPress, enabled) {
        var longPressConsumed = false
        detectTapGestures(
            onLongPress = onLongPress?.let { handler ->
                {
                    longPressConsumed = true
                    pressedState.value = false
                    handler()
                }
            },
            onPress = {
                longPressConsumed = false
                pressedState.value = true
                val released = tryAwaitRelease()
                pressedState.value = false
                if (released && !longPressConsumed) onClick()
            }
        )
    }
}

// 层级深度表与「进入必回顶部」标记搬到 SettingsScrollMemory.kt：它们只服务滚动
// 位置记忆这一件事，和它的账本、判定函数放在一起才能被单测覆盖。

private fun activeMotionProfileOptions(
    profiles: List<FlueMotionProfile>,
): List<FlueMotionProfile> = profiles.sortedWith(
    compareBy<FlueMotionProfile> { if (FlueMotionProfiles.isBuiltIn(it.id)) 0 else 1 }
        .thenBy { it.name },
)

private fun MotionPhaseKey.motionPhaseLabel(context: Context): String = when (this) {
    MotionPhaseKey.APP_LIST_ENTER -> context.getString(R.string.settings_motion_phase_app_list_enter)
    MotionPhaseKey.APP_LAUNCH -> context.getString(R.string.settings_motion_phase_app_launch)
    MotionPhaseKey.APP_RETURN -> context.getString(R.string.settings_motion_phase_app_return)
    MotionPhaseKey.PULL_TO_FACE -> context.getString(R.string.settings_motion_phase_pull_to_face)
    MotionPhaseKey.EDGE_BOUNCE -> context.getString(R.string.settings_motion_phase_edge_bounce)
}

private fun MotionEasing.motionEasingSummary(context: Context): String = when (this) {
    is MotionEasing.CubicBezier ->
        "Bezier(%.2f, %.2f, %.2f, %.2f)".format(Locale.US, x1, y1, x2, y2)

    is MotionEasing.Spring ->
        context.getString(R.string.settings_motion_spring_fmt)
            .format(Locale.US, dampingRatio, stiffness.roundToInt())
}

@Composable
private fun MessageCard(text: String, background: Color, onClick: () -> Unit) {
    val launcherStyle = LauncherTheme.style
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale = animateFloatAsState(
        if (pressed) 0.964f else 1f,
        animationSpec = launcherStyle.pressScaleSpec,
        label = "message_card_scale"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressedScale.value
                scaleY = pressedScale.value
            }
            .clip(launcherStyle.roundAwareCompactShape())
            .background(background)
            .instantPressGesture(pressedState, onClick = onClick)
            .padding(14.dp)
    ) {
        Text(text = text, color = launcherStyle.bodyColor, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    scale: Float,
    enabled: Boolean = true,
    /**
     * 在成组卡里的位置。
     *
     * 单选组（界面风格 / 界面动画 / 屏幕形状）本来就是「一个类型的几个选项」，MIUIX 档
     * 要并成一张卡；以前这里收不到位置，于是三行各自一张独立胶囊卡，和旁边已经并好的
     * 分组摆在一起就很割裂（老师报的「一个类型的应该合并起来」）。
     */
    groupPosition: FlueGroupPosition = FlueGroupPosition.Single,
) {
    val launcherStyle = LauncherTheme.style
    val horizontalPadding = flueRowHorizontalPadding(launcherStyle, rowHeight = 56.dp)
    if (launcherStyle.uiStyle == UiStyle.MIUIX) {
        // MIUIX：56dp 设置行，选中态在右侧显示 Check 图标。
        MiuixSettingsCard(
            modifier = Modifier.chordScaled(scale, enabled),
            roundRole = RoundElementRole.CAPSULE,
            groupPosition = groupPosition,
        ) {
            MiuixSettingsRow(
                title = title,
                subtitle = subtitle.takeIf { it.isNotBlank() },
                enabled = enabled,
                onClick = onClick,
                trailing = {
                    if (selected) {
                        Icon(
                            imageVector = MiuixIcons.Check,
                            contentDescription = null,
                            tint = launcherStyle.accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedScale = animateFloatAsState(
        if (pressed) 0.964f else 1f,
        animationSpec = launcherStyle.pressScaleSpec,
        label = "choice_row_scale"
    )
    val rowShape = launcherStyle.roundAwareCompactShape(RoundElementRole.CAPSULE)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale.value
                scaleY = scale * pressedScale.value
                alpha = if (enabled) scale.coerceIn(0.55f, 1f) else 0.48f
            }
            .clip(rowShape)
            .background(
                when {
                    pressed && selected -> launcherStyle.selectedCardColor.copy(alpha = 0.80f)
                    pressed -> launcherStyle.pressedCardColor
                    selected -> launcherStyle.selectedCardColor
                    else -> launcherStyle.cardColor
                }
            )
            .border(1.dp, launcherStyle.outlineColor, rowShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = horizontalPadding, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .padding(end = 12.dp)
            ) {
                Text(title, color = launcherStyle.titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(3.dp))
                Text(subtitle, color = launcherStyle.secondaryTextColor, fontSize = 12.sp)
            }
            if (selected) {
                Icon(OfficialMiuixIcons.OfficialOk, contentDescription = null, tint = launcherStyle.accentColor)
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
    scale: Float,
    leadingIcon: ImageBitmap? = null,
    reserveLeadingIconSpace: Boolean = false,
    groupPosition: FlueGroupPosition = FlueGroupPosition.Single,
    indicatorOnly: Boolean = false,
) {
    val launcherStyle = LauncherTheme.style
    val horizontalPadding = flueRowHorizontalPadding(launcherStyle, rowHeight = 56.dp)
    if (launcherStyle.uiStyle == UiStyle.MIUIX) {
        // The hidden-app page can compose a new row on nearly every fling frame.
        // Keep this common MIUIX switch row to one clickable node and one small
        // animated layer instead of nesting Card -> Row -> independently clickable
        // Switch (four animation states and two pointer-input chains per app).
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val rowShape = launcherStyle.flueGroupShape(groupPosition, RoundElementRole.CAPSULE)
        val rowBackground = if (pressed) launcherStyle.pressedCardColor else launcherStyle.cardColor
        if (groupPosition.needsDivider) {
            // 组内非末行：分隔线要画在**卡内**，所以背景上移到外层 Column，行本身只留
            // 点击与内边距。多这一层只发生在成组的设置页上；上面那条 fling 路径
            // （隐藏应用页，永远是 Single）走的还是原来的单节点结构。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .chordScaled(scale, enabled)
                    .clip(rowShape)
                    .background(rowBackground),
            ) {
                MiuixSwitchRowBody(
                    modifier = Modifier,
                    title = title,
                    subtitle = subtitle,
                    enabled = enabled,
                    checked = checked,
                    onToggle = onToggle,
                    leadingIcon = leadingIcon,
                    reserveLeadingIconSpace = reserveLeadingIconSpace,
                    horizontalPadding = horizontalPadding,
                    interactionSource = interactionSource,
                    pressed = pressed,
                    indicatorOnly = indicatorOnly,
                )
                FlueGroupDivider(groupPosition)
            }
        } else {
            MiuixSwitchRowBody(
                modifier = Modifier
                    .chordScaled(scale, enabled)
                    .background(color = rowBackground, shape = rowShape),
                title = title,
                subtitle = subtitle,
                enabled = enabled,
                checked = checked,
                onToggle = onToggle,
                leadingIcon = leadingIcon,
                reserveLeadingIconSpace = reserveLeadingIconSpace,
                horizontalPadding = horizontalPadding,
                interactionSource = interactionSource,
                pressed = pressed,
                indicatorOnly = indicatorOnly,
            )
        }
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedScale = animateFloatAsState(
        if (pressed) 0.958f else 1f,
        animationSpec = launcherStyle.pressScaleSpec,
        label = "switch_row_scale"
    )
    val switchRowShape = launcherStyle.roundAwareCompactShape(RoundElementRole.CAPSULE)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale.value
                scaleY = scale * pressedScale.value
                alpha = if (enabled) scale.coerceIn(0.55f, 1f) else 0.5f
            }
            .clip(switchRowShape)
            .background(if (pressed) launcherStyle.pressedCardColor else launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, switchRowShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = { onToggle(!checked) }
            )
            .padding(horizontal = horizontalPadding, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                // **weight 而不是 fillMaxWidth(0.82f)**：定分数把开关挤到只剩 18% 行宽，
                // 而 AW 档的轨道是 64dp —— 窄屏上 18% 装不下，`Modifier.width(64.dp)`
                // 被上游约束夹小，轨道画短了、滑块反而溢出来。那就是老师看到的
                // 「开关右边显示不完全」。weight 让文字吃掉**剩下**的空间，
                // 尾部控件永远拿自己的自然宽度。
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null || reserveLeadingIconSpace) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                launcherStyle.titleColor.copy(
                                    alpha = if (leadingIcon != null) 0f else 0.05f,
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (leadingIcon != null) {
                            Image(
                                bitmap = leadingIcon,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column {
                    Text(title, color = launcherStyle.titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(subtitle, color = launcherStyle.secondaryTextColor, fontSize = 12.sp)
                }
            }
            if (indicatorOnly) {
                FlueSwitchIndicator(checked = checked, enabled = enabled, pressed = pressed)
            } else {
                FlueSwitch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
            }
        }
    }
}

/**
 * MIUIX 开关行的行体。
 *
 * 抽出来是为了让「成组（外层 Column 出背景 + 卡内分隔线）」与「独立卡（行自己出背景）」
 * 复用同一份内容，而不是把 60 行复制两遍 —— 抽的是 composable 函数，不引入布局节点，
 * 所以隐藏应用页那条 fling 路径的节点数与改动前一致。
 */
@Composable
private fun MiuixSwitchRowBody(
    modifier: Modifier,
    title: String,
    subtitle: String,
    enabled: Boolean,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    leadingIcon: ImageBitmap?,
    reserveLeadingIconSpace: Boolean,
    horizontalPadding: Dp,
    interactionSource: MutableInteractionSource,
    pressed: Boolean,
    indicatorOnly: Boolean,
) {
    val launcherStyle = LauncherTheme.style
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = { onToggle(!checked) },
            )
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null || reserveLeadingIconSpace) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (leadingIcon != null) Color.Transparent
                        else launcherStyle.pressedCardColor.copy(alpha = 0.6f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (leadingIcon != null) {
                    Image(
                        bitmap = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        ) {
            Text(
                title,
                color = if (enabled) launcherStyle.titleColor else launcherStyle.secondaryTextColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = launcherStyle.secondaryTextColor, fontSize = 12.sp)
            }
        }
        if (indicatorOnly) {
            FlueSwitchIndicator(checked = checked, enabled = enabled, pressed = pressed)
        } else {
            FlueSwitch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

/**
 * 滑块行的**名义纵向几何**。内边距在组合期就要交给 `Modifier.padding`，那时行还没测量，
 * 所以带区只能按声明值推 —— 而三档风格的 `capsuleMaxRadius` 都有上限（APPLE 32dp /
 * MD3 28dp / MIUIX 22dp），弧深不会随行高继续增长，名义值足够准。
 *
 * MIUIX 档：`MiuixSettingsRow`（最小 56dp）+ 24dp 轨道 + 14dp 底部留白。
 */
private val MIUIX_SLIDER_ROW_HEIGHT = 94.dp
private val MIUIX_SLIDER_TEXT_BAND_BOTTOM = 56.dp
private val MIUIX_SLIDER_TRACK_BOTTOM_GAP = 14.dp

/**
 * 通用档（APPLE / MD3）：14dp 上下留白 + 标题 18dp + 4dp + 数值 15dp + 8dp + M3 滑块 48dp。
 */
private val GENERIC_SLIDER_ROW_HEIGHT = 121.dp
private val GENERIC_SLIDER_VERTICAL_PADDING = 14.dp
private val GENERIC_SLIDER_TEXT_BAND_BOTTOM = 51.dp
private val GENERIC_SLIDER_TRACK_BAND_TOP = 59.dp

@Composable
private fun SettingsSliderRow(
    title: String,
    value: Float,
    valueText: String,
    localValueText: ((Float) -> String)? = null,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: ((Float) -> Unit)? = null,
    scale: Float,
    enabled: Boolean = true,
    supportingText: String? = null,
    groupPosition: FlueGroupPosition = FlueGroupPosition.Single,
    /**
     * 滑杆下方、**同一张卡内**的附加内容（按钮等）。
     *
     * 存在的理由：像「整体缩放」这种改完要重启才生效的项，动作必须挨着那根滑杆 ——
     * 单独摆一行会读成「与上面无关的功能」。所以它属于这一行，不是下一行。
     */
    footer: (@Composable () -> Unit)? = null,
) {
    val launcherStyle = LauncherTheme.style
    // 滑块行有**两块内容，纵向位置完全不同**，所以要算两个内边距：
    //
    // - 「标题 + 数值」那块文字在行的上部；
    // - 轨道在行的**下部**（MIUIX 档 24dp 高、底沿距行底 14dp）。
    //
    // 以前两块共用一个按「44dp 内容居中于 100dp 行」算出来的值 —— 居中假设对轨道不成立，
    // 算出 0 弧深，而轨道真实位置正好压在下圆角的弧上。这就是老师报的「滑轨顶到胶囊边缘」。
    // 现在轨道走带区式弧深（见 FlueCapsuleGeometry.contentArcInsetForBandPx）。
    //
    // MIUIX 档的上部文字不用在这里算：`MiuixSettingsRow` 自己按 56dp 行查表。
    val trackPadding = flueRowHorizontalPadding(
        launcherStyle,
        rowHeight = MIUIX_SLIDER_ROW_HEIGHT,
        contentBand = MIUIX_SLIDER_TEXT_BAND_BOTTOM to
            (MIUIX_SLIDER_ROW_HEIGHT - MIUIX_SLIDER_TRACK_BOTTOM_GAP),
    )
    var localValue by remember(title) { mutableFloatStateOf(value) }
    LaunchedEffect(value) { localValue = value }
    if (launcherStyle.uiStyle == UiStyle.MIUIX) {
        // MIUIX：设置行显示标题与当前值，下面接胶囊滑块（FlueSlider 分发到 miuix 真组件）。
        MiuixSettingsCard(
            modifier = Modifier.chordScaled(scale, enabled),
            roundRole = RoundElementRole.CAPSULE,
            groupPosition = groupPosition,
        ) {
            MiuixSettingsRow(
                title = title,
                subtitle = localValueText?.invoke(localValue) ?: valueText,
                enabled = enabled
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = trackPadding)
                    .padding(bottom = MIUIX_SLIDER_TRACK_BOTTOM_GAP)
            ) {
                FlueSlider(
                    value = localValue,
                    onValueChange = {
                        localValue = it
                        onValueChange(it)
                    },
                    valueRange = range,
                    steps = steps,
                    enabled = enabled,
                    onValueChangeFinished = {
                        onValueChangeFinished?.invoke(localValue)
                    }
                )
            }
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    color = launcherStyle.secondaryTextColor,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(
                        start = trackPadding,
                        end = trackPadding,
                        bottom = 12.dp,
                    ),
                )
            }
            footer?.let { content ->
                Box(
                    modifier = Modifier.padding(
                        start = trackPadding,
                        end = trackPadding,
                        bottom = 12.dp,
                    ),
                ) {
                    content()
                }
            }
        }
        return
    }
    val sliderShape = launcherStyle.roundAwareCompactShape(RoundElementRole.CAPSULE)
    // 通用档（APPLE / MD3）：整块自己画卡片，所以外层内边距按上部文字带给，
    // 轨道再单独补上它那条带比文字带多吃的那一截。
    val genericTextPadding = flueRowHorizontalPadding(
        launcherStyle,
        rowHeight = GENERIC_SLIDER_ROW_HEIGHT,
        contentBand = GENERIC_SLIDER_VERTICAL_PADDING to GENERIC_SLIDER_TEXT_BAND_BOTTOM,
    )
    val genericTrackPadding = flueRowHorizontalPadding(
        launcherStyle,
        rowHeight = GENERIC_SLIDER_ROW_HEIGHT,
        contentBand = GENERIC_SLIDER_TRACK_BAND_TOP to
            (GENERIC_SLIDER_ROW_HEIGHT - GENERIC_SLIDER_VERTICAL_PADDING),
    )
    val genericTrackExtraPadding = (genericTrackPadding - genericTextPadding)
        .coerceAtLeast(0.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) scale.coerceIn(0.55f, 1f) else 0.5f
            }
            .clip(sliderShape)
            .background(launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, sliderShape)
            .padding(
                horizontal = genericTextPadding,
                vertical = GENERIC_SLIDER_VERTICAL_PADDING,
            )
    ) {
        Column {
            Text(title, color = launcherStyle.titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(localValueText?.invoke(localValue) ?: valueText, color = launcherStyle.secondaryTextColor, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            FlueSlider(
                value = localValue,
                onValueChange = {
                    localValue = it
                    onValueChange(it)
                },
                onValueChangeFinished = {
                    onValueChangeFinished?.invoke(localValue)
                },
                valueRange = range,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.padding(horizontal = genericTrackExtraPadding),
            )
            if (!supportingText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = supportingText,
                    color = launcherStyle.secondaryTextColor,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = genericTrackExtraPadding),
                )
            }
            footer?.let { content ->
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.padding(horizontal = genericTrackExtraPadding)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    longPressDurationMs: Long? = null,
    scale: Float,
    groupPosition: FlueGroupPosition = FlueGroupPosition.Single,
) {
    val launcherStyle = LauncherTheme.style
    val horizontalPadding = flueRowHorizontalPadding(launcherStyle, rowHeight = 56.dp)
    val pressedState = rememberPressedState()
    val pressed by pressedState
    if (launcherStyle.uiStyle == UiStyle.MIUIX) {
        // MIUIX：动作行走强调色标题；保留长按支持（导入表盘彩蛋）。
        MiuixSettingsCard(
            modifier = Modifier.chordScaled(scale),
            roundRole = RoundElementRole.CAPSULE,
            groupPosition = groupPosition,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .background(if (pressed) launcherStyle.pressedCardColor else Color.Transparent)
                    .instantPressGesture(
                        pressedState,
                        onLongPress = onLongPress,
                        longPressDurationMs = longPressDurationMs,
                        onClick = onClick
                    )
                    .padding(horizontal = horizontalPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text(title, color = launcherStyle.accentColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(subtitle, color = launcherStyle.secondaryTextColor, fontSize = 12.sp)
                    }
                }
                icon?.invoke()
            }
        }
        return
    }
    val pressedScale = animateFloatAsState(
        if (pressed) 0.96f else 1f,
        animationSpec = launcherStyle.pressScaleSpec,
        label = "action_card_scale"
    )
    val actionShape = launcherStyle.roundAwareCompactShape(RoundElementRole.CAPSULE)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale.value
                scaleY = scale * pressedScale.value
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clip(actionShape)
            .background(if (pressed) launcherStyle.pressedCardColor else launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, actionShape)
            .instantPressGesture(
                pressedState,
                onLongPress = onLongPress,
                longPressDurationMs = longPressDurationMs,
                onClick = onClick
            )
            .padding(horizontal = horizontalPadding, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .padding(end = 12.dp)
            ) {
                Text(title, color = launcherStyle.accentColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(subtitle, color = launcherStyle.secondaryTextColor, fontSize = 12.sp)
                }
            }
            icon?.invoke()
        }
    }
}

@Composable
private fun AboutCard(
    scale: Float,
    onDonateClick: () -> Unit,
    onVersionClick: () -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val context = LocalContext.current
    // 两个交流群的选择弹窗。做成弹窗而不是并排两张卡：关于页在 230dp 圆屏上已经很挤，
    // 多一张卡就要多滚一屏，而「进哪个群」是个二选一的一次性决定，弹窗更合适。
    var groupPickerVisible by remember { mutableStateOf(false) }
    fun openGroup(url: String) {
        groupPickerVisible = false
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }
    if (groupPickerVisible) {
        FlueAlertDialog(
            onDismissRequest = { groupPickerVisible = false },
            title = { Text(stringResource(R.string.about_pick_group_title)) },
            confirmButton = {
                TextButton(onClick = { openGroup(COMMUNITY_GROUP_URL) }) {
                    Text(stringResource(R.string.about_pick_group_1_fmt, COMMUNITY_GROUP_NUMBER))
                }
            },
            dismissButton = {
                TextButton(onClick = { openGroup(COMMUNITY_GROUP_2_URL) }) {
                    Text(stringResource(R.string.about_pick_group_2_fmt, COMMUNITY_GROUP_2_NUMBER))
                }
            },
        )
    }
    fun openAuthorProfile() {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse(AUTHOR_BILIBILI_URL)
            )
        )
    }
    fun copyAuthorQq() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("QQ", AUTHOR_QQ_NUMBER))
        Toast.makeText(context, context.getString(R.string.about_qq_copy_fmt, AUTHOR_QQ_NUMBER), Toast.LENGTH_SHORT).show()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale.coerceIn(0.55f, 1f)
            }
            // 关于页这张卡是整块竖内容（图标 + 版本 + 三个操作），宽高比远小于
            // RowCapsuleShape 的阈值，圆屏下会自动退回 cardShape 的大圆角。挂着是为了
            // 「圆屏形状取值统一走这个入口」，不是指望它变胶囊。
            .clip(launcherStyle.roundAwareCardShape())
            .background(launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, launcherStyle.roundAwareCardShape())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // painterResource 不支持 AdaptiveIconDrawable：启动图标是自适应
            // 图标时（mipmap-anydpi-v26），进入关于页会直接抛
            // IllegalArgumentException 闪退。走 PackageManager 拿 Drawable
            // 再栅格化，任何图标类型都安全。
            val appIconBitmap = remember {
                runCatching {
                    context.packageManager.getApplicationIcon(context.packageName)
                        .toBitmap(width = 192, height = 192)
                        .asImageBitmap()
                }.getOrNull()
            }
            Box(
                modifier = Modifier
                    .size(98.dp)
                    .clip(launcherStyle.cardShape)
                    .background(launcherStyle.titleColor.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                if (appIconBitmap != null) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(76.dp),
                        // PackageManager 已按当前系统遮罩渲染 AdaptiveIconDrawable，
                        // 这里不再二次裁圆角，避免前景被重复缩小或截边。
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(stringResource(R.string.app_name), color = launcherStyle.titleColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(stringResource(R.string.app_name), color = launcherStyle.titleColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                ABOUT_VERSION,
                color = WatchColors.TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onVersionClick
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.author_avatar),
                    contentDescription = null,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(onClick = ::openAuthorProfile)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        stringResource(R.string.about_author_name),
                        color = launcherStyle.titleColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = ::openAuthorProfile)
                            .padding(horizontal = 2.dp, vertical = 1.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "QQ：$AUTHOR_QQ_NUMBER",
                        color = WatchColors.TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = ::copyAuthorQq)
                            .padding(horizontal = 2.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            ActionCard(
                title = stringResource(R.string.about_join_group),
                subtitle = stringResource(
                    R.string.about_community_groups_fmt,
                    COMMUNITY_GROUP_NUMBER,
                    COMMUNITY_GROUP_2_NUMBER,
                ),
                // 不再直接跳 1 群：两个群都要能进，点击先问一句（见 groupPickerVisible）。
                onClick = { groupPickerVisible = true },
                scale = 1f
            )
            Spacer(modifier = Modifier.height(10.dp))
            ActionCard(
                title = stringResource(R.string.about_donate),
                subtitle = stringResource(R.string.about_donate_methods),
                onClick = onDonateClick,
                scale = 1f
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.about_open_source),
                color = launcherStyle.titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OPEN_SOURCE_PROJECTS.forEachIndexed { index, project ->
                ActionCard(
                    title = project.name,
                    subtitle = project.url,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(project.url))
                        )
                    },
                    scale = 1f,
                )
                if (index != OPEN_SOURCE_PROJECTS.lastIndex) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun DebugPage(
    vm: LauncherViewModel,
    weatherRepository: WatchWeatherRepository,
    watchCatalogRepository: WatchStoreRepository,
    onBack: () -> Unit,
    onCrash: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var cacheStatus by remember { mutableStateOf(context.getString(R.string.diagnostics_cache_idle)) }
    SettingsPageScaffold(
        title = stringResource(R.string.diagnostics_title),
        onBack = onBack,
        headerTime = "",
        initialFirstVisibleItemIndex = 0,
        initialFirstVisibleItemScrollOffset = 0,
        onScrollChanged = { _, _ -> },
    ) { listState, screenCenterY, screenHeightPx, _ ->
        flueSettingsGroup {
            row("debug_crash") { position ->
                ActionCard(
                    title = stringResource(R.string.diagnostics_crash),
                    subtitle = stringResource(R.string.diagnostics_crash_description),
                    onClick = onCrash,
                    scale = itemFisheye(listState, "debug_crash", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
            row("debug_missing_watchface") { position ->
                ActionCard(
                    title = stringResource(R.string.diagnostics_missing_face),
                    subtitle = stringResource(R.string.diagnostics_missing_face_description),
                    onClick = vm::selectMissingWatchFaceForDebug,
                    scale = itemFisheye(listState, "debug_missing_watchface", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
            row("debug_restore_watchface") { position ->
                ActionCard(
                    title = stringResource(R.string.diagnostics_restore_face),
                    subtitle = stringResource(R.string.diagnostics_restore_face_description),
                    onClick = { vm.fallbackToBuiltIn("debug restore") },
                    scale = itemFisheye(listState, "debug_restore_watchface", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
            row("debug_refresh_cache") { position ->
                ActionCard(
                    title = stringResource(R.string.diagnostics_refresh_cache),
                    subtitle = cacheStatus,
                    onClick = {
                        scope.launch {
                            cacheStatus = runCatching {
                                val removed = watchCatalogRepository.clearPreviewCache()
                                vm.refreshWatchFaces(force = true)
                                weatherRepository.requestRefresh()
                                context.getString(R.string.diagnostics_cache_refreshed, removed)
                            }.getOrElse { context.getString(R.string.diagnostics_cache_failed) }
                        }
                    },
                    scale = itemFisheye(listState, "debug_refresh_cache", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
        }
        item("debug_performance") { DiagnosticsSettingsSection(vm) }
        item("debug_information") { DiagnosticsInformationSection(vm) }
    }
}

@Composable
private fun DonateMethodCard(
    title: String,
    subtitle: String,
    resId: Int,
    scale: Float,
    onPreviewClick: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val launcherStyle = LauncherTheme.style
    val pressedScale = animateFloatAsState(
        if (pressed) 0.962f else 1f,
        animationSpec = spring(stiffness = 820f, dampingRatio = 0.74f),
        label = "donate_card_scale"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale.value
                scaleY = scale * pressedScale.value
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clip(launcherStyle.cardShape)
            .background(launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, launcherStyle.cardShape)
            .instantPressGesture(pressedState, onClick = onPreviewClick)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = launcherStyle.titleColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, color = launcherStyle.secondaryTextColor, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Image(
                painter = painterResource(id = resId),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    // 卡内嵌套的表面比卡本身小一档，用 compactShape 而不是又编一个数字。
                    .clip(launcherStyle.compactShape)
                    .background(launcherStyle.pressedCardColor)
            )
        }
    }
}

/**
 * 档案重命名弹窗（布局档案与动效档案共用）。
 *
 * 后端早就有 `renameActiveLayoutProfile` / `renameActiveMotionProfile`，缺的只是入口 ——
 * 所以这里只做一件事：收一个名字交给它们。空名不提交（改成空白等于把档案变成无名）。
 */
@Composable
private fun ProfileRenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val style = LauncherTheme.style
    var text by remember(currentName) { mutableStateOf(currentName) }
    val trimmed = text.trim()
    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_profile_rename), color = style.titleColor) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(FLUE_LAYOUT_PROFILE_NAME_MAX_LENGTH) },
                singleLine = true,
                shape = style.rowShape,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (trimmed.isNotEmpty()) onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) {
                Text(stringResource(android.R.string.ok), color = style.accentColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel), color = style.secondaryTextColor)
            }
        },
    )
}

/**
 * H6 的缩放输入。
 *
 * 这一项**要限范围**（与 I1 的画布数值输入那条「不限量程」相反）：WebView 的缩放超出
 * 25–250 会直接排版崩坏，而 25 / 250 正是老师给的界。所以越界时把「确定」灰掉，
 * 而不是收下之后偷偷夹一遍 —— 用户得知道自己填的数没被采用。
 */
@Composable
private fun WebZoomInputDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val style = LauncherTheme.style
    var text by remember(current) { mutableStateOf(current.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in MIN_WEB_ZOOM_PERCENT..MAX_WEB_ZOOM_PERCENT
    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_web_zoom_dialog_title), color = style.titleColor)
        },
        text = {
            OutlinedTextField(
                value = text,
                // 只收数字：手表键盘上切数字面板很费劲，直接把非数字过滤掉。
                onValueChange = { raw -> text = raw.filter(Char::isDigit).take(3) },
                singleLine = true,
                shape = style.rowShape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        stringResource(R.string.settings_web_zoom_sub),
                        color = style.secondaryTextColor,
                        fontSize = 11.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) {
                Text(stringResource(android.R.string.ok), color = style.accentColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel), color = style.secondaryTextColor)
            }
        },
    )
}

@Composable
private fun OverlayMaskCornerRadiusDialog(
    currentRadiusDp: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val launcherStyle = LauncherTheme.style
    val dialogSurface = MaterialTheme.colorScheme.surface
    val dialogOnSurface = MaterialTheme.colorScheme.onSurface
    val dialogOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    var localRadiusDp by remember(currentRadiusDp) {
        mutableFloatStateOf(currentRadiusDp.toFloat())
    }
    // 全屏提示不再用外层卡片表达圆角，因此用独立样块实时预览滑杆值。
    val previewRadiusDp = localRadiusDp
        .coerceIn(0f, MAX_OVERLAY_MASK_CORNER_RADIUS_DP.toFloat())
        .dp
    val dialogShape = RoundedCornerShape(previewRadiusDp)
    val interactionSource = remember { MutableInteractionSource() }

    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.dialog_overlay_mask_corner_title),
        text = stringResource(R.string.dialog_overlay_mask_corner_subtitle),
        confirmText = stringResource(R.string.action_done),
        // This compact sheet has no scrollable body. Leaving a vertical-scroll
        // ancestor here lets it win the pointer sequence from MIUIX's horizontal slider.
        scrollableContent = false,
        onConfirm = {
            onSave(normalizeOverlayMaskCornerRadiusDp(localRadiusDp.roundToInt()))
        },
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(dialogShape)
                .background(dialogOnSurface.copy(alpha = 0.08f))
                .border(2.dp, launcherStyle.accentColor.copy(alpha = 0.72f), dialogShape),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(12.dp))
                    .background(launcherStyle.accentColor),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(12.dp))
                    .background(launcherStyle.accentColor)
                    .border(3.dp, dialogSurface, RoundedCornerShape(12.dp)),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(launcherStyle.compactShape)
                .background(dialogOnSurface.copy(alpha = 0.055f))
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) {
                        localRadiusDp = (localRadiusDp - 1f).coerceAtLeast(0f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("−", color = dialogOnSurfaceVariant, fontSize = 30.sp)
            }
            // 弹窗里的滑块也走抽象入口（A8 的要求）：Apple 档就是 Kyant 简化版，
            // 颜色从风格令牌取，不再在这里写死。
            FlueSlider(
                value = localRadiusDp,
                onValueChange = {
                    localRadiusDp = it.coerceIn(
                        0f,
                        MAX_OVERLAY_MASK_CORNER_RADIUS_DP.toFloat(),
                    )
                },
                valueRange = 0f..MAX_OVERLAY_MASK_CORNER_RADIUS_DP.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) {
                        localRadiusDp = (localRadiusDp + 1f)
                            .coerceAtMost(MAX_OVERLAY_MASK_CORNER_RADIUS_DP.toFloat())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = dialogOnSurfaceVariant, fontSize = 30.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.dialog_overlay_mask_corner_value_fmt,
                localRadiusDp.roundToInt(),
            ),
            color = dialogOnSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.dialog_overlay_mask_corner_hint),
            color = dialogOnSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DonatePreviewDialog(resId: Int, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 4f)
        scale = newScale
        offset = if (newScale <= 1.02f) {
            Offset.Zero
        } else {
            offset + panChange
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            // 全屏看图要真的顶到屏幕边，否则顶部一条不被 98% 黑覆盖。
            decorFitsSystemWindows = false,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.98f)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = resId),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformState)
                    .pointerInput(scale) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.2f
                                    offset = Offset.Zero
                                }
                            }
                        )
                    }
                    .pointerInput(scale) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, dragAmount ->
                                if (scale > 1.02f) {
                                    change.consume()
                                    offset += Offset(dragAmount.x, dragAmount.y)
                                }
                            }
                        )
                    }
            )
        }
    }
}

/**
 * 设置页逐条缩放通道。名字沿用历史（曾经是底部鱼眼），现在承载的是**圆屏
 * 弦宽适配**：
 *
 * - 方屏：恒返回 1f，全部平铺，与改动前逐像素一致。
 * - 圆屏：按元素所在高度的弦宽等比缩放，避免左对齐文字被弧线切掉。
 *
 * 200+ 个调用点已经把返回值接进了 `graphicsLayer { scaleX/scaleY }`，正好是
 * 弦宽适配要求的形状（布局值测量 + 只改合成属性），所以调用点一处都不用改。
 * 算法与三条铁律见 [com.flue.launcher.ui.common.chordScaleForPosition]。
 *
 * @param screenHeight 保留参数：视口高度对弦宽计算不是必需的（圆心 y 由
 *   `screenCenterY` 给出），但签名要和 200+ 调用点保持一致。
 */
@Composable
private fun itemFisheye(
    listState: LazyListState,
    key: String,
    screenCenterY: Float,
    screenHeight: Float
): Float {
    if (!com.flue.launcher.ui.common.isFlueScreenRound()) return 1f
    val rootCenterY = com.flue.launcher.ui.common.LocalChordScreenCenterY.current
    return com.flue.launcher.ui.common.chordScale(
        listState = listState,
        key = key,
        screenCenterY = rootCenterY.takeIf(Float::isFinite) ?: screenCenterY,
        viewportWidth = com.flue.launcher.ui.common.LocalChordViewportWidth.current,
        listTopInViewport = com.flue.launcher.ui.common.LocalChordListTopInViewport.current,
    )
}

/**
 * 把弦宽缩放施加到某个组件上的统一入口。
 *
 * 几个设置行组件在 MIUIX 分支里提前 `return`，各自的 `scale` 参数一次都没用到，
 * 圆屏下那条分支就会整页平铺（老师报的「miuix 还是平铺」）。通用路径里那段
 * `graphicsLayer { scaleX/scaleY/alpha }` 抄五遍不现实，抽成这个 modifier。
 *
 * 只改合成属性，不碰任何布局属性 —— 弦宽适配的第三条铁律。
 */
private fun Modifier.chordScaled(scale: Float, enabled: Boolean = true): Modifier =
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = if (enabled) scale.coerceIn(0.55f, 1f) else 0.48f
    }

@Composable
private fun BackupOptionsDialog(
    title: String,
    subtitle: String,
    options: FlueBackupOptions,
    availableOptions: FlueBackupOptions,
    confirmText: String,
    onOptionsChange: (FlueBackupOptions) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val listState = rememberLazyListState()
    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(subtitle, color = LauncherTheme.style.secondaryTextColor, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .height(320.dp)
                        .flueOverscroll(listState),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (availableOptions.settings) {
                        item("backup_settings") {
                            BackupOptionSwitch(
                                title = stringResource(R.string.backup_item_settings),
                                subtitle = stringResource(R.string.backup_item_settings_sub),
                                checked = options.settings,
                                onToggle = { onOptionsChange(options.copy(settings = it)) }
                            )
                        }
                    }
                    if (availableOptions.appOrder) {
                        item("backup_app_order") {
                            BackupOptionSwitch(
                                title = stringResource(R.string.backup_item_app_order),
                                subtitle = stringResource(R.string.backup_item_app_order_sub),
                                checked = options.appOrder,
                                onToggle = { onOptionsChange(options.copy(appOrder = it)) }
                            )
                        }
                    }
                    if (availableOptions.widgetOrder) {
                        item("backup_widget_order") {
                            BackupOptionSwitch(
                                title = stringResource(R.string.backup_item_widget_order),
                                subtitle = stringResource(R.string.backup_item_widget_order_sub),
                                checked = options.widgetOrder,
                                onToggle = { onOptionsChange(options.copy(widgetOrder = it)) }
                            )
                        }
                    }
                    if (availableOptions.sideScreenOrder) {
                        item("backup_side_screen_order") {
                            BackupOptionSwitch(
                                title = stringResource(R.string.backup_item_side_screen_order),
                                subtitle = stringResource(R.string.backup_item_side_screen_order_sub),
                                checked = options.sideScreenOrder,
                                onToggle = { onOptionsChange(options.copy(sideScreenOrder = it)) }
                            )
                        }
                    }
                    if (availableOptions.photoWatchFace) {
                        item("backup_photo_watchface") {
                            BackupOptionSwitch(
                                title = stringResource(R.string.backup_item_photo_watchface),
                                subtitle = stringResource(R.string.backup_item_photo_watchface_sub),
                                checked = options.photoWatchFace,
                                onToggle = { onOptionsChange(options.copy(photoWatchFace = it)) }
                            )
                        }
                    }
                    if (availableOptions.videoWatchFace) {
                        item("backup_video_watchface") {
                            BackupOptionSwitch(
                                title = stringResource(R.string.backup_item_video_watchface),
                                subtitle = stringResource(R.string.backup_item_video_watchface_sub),
                                checked = options.videoWatchFace,
                                onToggle = { onOptionsChange(options.copy(videoWatchFace = it)) }
                            )
                        }
                    }
                    if (availableOptions.watchFiles) {
                        item("backup_watch_files") {
                            BackupOptionSwitch(
                                title = stringResource(R.string.backup_item_watch_files),
                                subtitle = stringResource(R.string.backup_item_watch_files_sub),
                                checked = options.watchFiles,
                                onToggle = { onOptionsChange(options.copy(watchFiles = it)) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun BackupOptionSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    scale: Float = 1f,
    groupPosition: FlueGroupPosition = FlueGroupPosition.Single,
) {
    SettingsSwitchRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onToggle = onToggle,
        scale = scale,
        groupPosition = groupPosition,
    )
}

private fun LazyListScope.backupOptionRows(
    listState: LazyListState,
    screenCenterY: Float,
    screenHeightPx: Float,
    options: FlueBackupOptions,
    availableOptions: FlueBackupOptions,
    onOptionsChange: (FlueBackupOptions) -> Unit
) {
    // 老师报「导出配置这一页的选项还没合并」：七个开关原来各自一张卡。
    flueSettingsGroup {
        if (availableOptions.settings) {
            row("backup_settings") { position ->
                BackupOptionSwitch(
                    title = stringResource(R.string.backup_item_settings),
                    subtitle = stringResource(R.string.backup_item_settings_sub),
                    checked = options.settings,
                    onToggle = { onOptionsChange(options.copy(settings = it)) },
                    scale = itemFisheye(listState, "backup_settings", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
        }
        if (availableOptions.appOrder) {
            row("backup_app_order") { position ->
                BackupOptionSwitch(
                    title = stringResource(R.string.backup_item_app_order),
                    subtitle = stringResource(R.string.backup_item_app_order_sub),
                    checked = options.appOrder,
                    onToggle = { onOptionsChange(options.copy(appOrder = it)) },
                    scale = itemFisheye(listState, "backup_app_order", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
        }
        if (availableOptions.widgetOrder) {
            row("backup_widget_order") { position ->
                BackupOptionSwitch(
                    title = stringResource(R.string.backup_item_widget_order),
                    subtitle = stringResource(R.string.backup_item_widget_order_sub),
                    checked = options.widgetOrder,
                    onToggle = { onOptionsChange(options.copy(widgetOrder = it)) },
                    scale = itemFisheye(listState, "backup_widget_order", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
        }
        if (availableOptions.sideScreenOrder) {
            row("backup_side_screen_order") { position ->
                BackupOptionSwitch(
                    title = stringResource(R.string.backup_item_side_screen_order),
                    subtitle = stringResource(R.string.backup_item_side_screen_order_sub),
                    checked = options.sideScreenOrder,
                    onToggle = { onOptionsChange(options.copy(sideScreenOrder = it)) },
                    scale = itemFisheye(listState, "backup_side_screen_order", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
        }
        if (availableOptions.photoWatchFace) {
            row("backup_photo_watchface") { position ->
                BackupOptionSwitch(
                    title = stringResource(R.string.backup_item_photo_watchface),
                    subtitle = stringResource(R.string.backup_item_photo_watchface_sub),
                    checked = options.photoWatchFace,
                    onToggle = { onOptionsChange(options.copy(photoWatchFace = it)) },
                    scale = itemFisheye(listState, "backup_photo_watchface", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
        }
        if (availableOptions.videoWatchFace) {
            row("backup_video_watchface") { position ->
                BackupOptionSwitch(
                    title = stringResource(R.string.backup_item_video_watchface),
                    subtitle = stringResource(R.string.backup_item_video_watchface_sub),
                    checked = options.videoWatchFace,
                    onToggle = { onOptionsChange(options.copy(videoWatchFace = it)) },
                    scale = itemFisheye(listState, "backup_video_watchface", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
        }
        if (availableOptions.watchFiles) {
            row("backup_watch_files") { position ->
                BackupOptionSwitch(
                    title = stringResource(R.string.backup_item_watch_files),
                    subtitle = stringResource(R.string.backup_item_watch_files_sub),
                    checked = options.watchFiles,
                    onToggle = { onOptionsChange(options.copy(watchFiles = it)) },
                    scale = itemFisheye(listState, "backup_watch_files", screenCenterY, screenHeightPx),
                    groupPosition = position,
                )
            }
        }
    }

}

private fun FlueBackupOptions.backupSummaryText(context: Context): String {
    val labels = selectedSectionKeys().map { key -> context.getString(backupSectionLabelRes(key)) }
    return labels.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: context.getString(R.string.backup_summary_none)
}

/**
 * 分区 key → 展示名。顺序由 [FlueBackupOptions.selectedSectionKeys] 定义，
 * 名称只在这里映射一次，导出摘要与勾选列表共用。
 */
@StringRes
private fun backupSectionLabelRes(key: String): Int = when (key) {
    FlueBackupOptions.SECTION_SETTINGS -> R.string.backup_item_settings
    FlueBackupOptions.SECTION_APP_ORDER -> R.string.backup_item_app_order
    FlueBackupOptions.SECTION_WIDGET_ORDER -> R.string.backup_item_widget_order
    FlueBackupOptions.SECTION_SIDE_SCREEN_ORDER -> R.string.backup_item_side_screen_order
    FlueBackupOptions.SECTION_PHOTO_WATCH_FACE -> R.string.backup_item_photo_watchface
    FlueBackupOptions.SECTION_VIDEO_WATCH_FACE -> R.string.backup_item_video_watchface
    FlueBackupOptions.SECTION_WATCH_FILES -> R.string.backup_item_watch_files
    else -> R.string.backup_summary_none
}

/** 「导出完成」页 ✅ 下面那三行灰字：内容 / 大小 / 时间。 */
private fun FlueBackupExportSummary.exportDoneDetailText(context: Context): String = listOf(
    context.getString(R.string.backup_export_detail_sections_fmt, sections.backupSummaryText(context)),
    context.getString(
        R.string.backup_export_detail_size_fmt,
        android.text.format.Formatter.formatShortFileSize(context, bytes),
    ),
    context.getString(R.string.backup_export_detail_time_fmt, createdAt),
).joinToString("\n")

@Composable
private fun BlockingProgressDialog(
    state: BlockingProgressState,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val flueScreenRound = isFlueScreenRound()
    Dialog(
        onDismissRequest = { if (state.completed) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = state.completed,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            // 全屏 Dialog 必须自己接管 insets：默认 true 时 Dialog 的 Window 会去避让
            // 状态栏，窗口顶边被系统下移，让出的那一条露出 Dialog 默认遮罩（看起来像
            // 顶部空了一条半透明黑）。下面 Column 已经用 safeDrawing 自己让位了。
            decorFitsSystemWindows = false,
        )
    ) {
        BackHandler(enabled = state.completed) { onDismiss() }
        ProvideDialogUiScale {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(launcherStyle.screenBackground),
        ) {
            // 二级页面结构：顶部标题栏，内容居中，底部胶囊按钮。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = if (flueScreenRound) 26.dp else 16.dp),
            ) {
                Spacer(modifier = Modifier.height(if (flueScreenRound) 30.dp else 18.dp))
                // 标题栏：完成态左对齐「返回」按钮，进行中无按钮。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    if (state.completed) {
                        val pressedState = rememberPressedState()
                        val pressed by pressedState
                        val scale = animateFloatAsState(
                            if (pressed) 0.94f else 1f,
                            label = "progress_back_scale"
                        )
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                                .clip(CircleShape)
                                .background(launcherStyle.topBarChipColor)
                                .instantPressGesture(pressedState, onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 和 HeaderBackButton 用同一个 miuix 官方 Back：这颗胶囊
                            // 就是设置页顶栏返回键的复刻，两处必须是同一枚箭头。
                            Icon(
                                OfficialMiuixIcons.OfficialBack,
                                contentDescription = null,
                                tint = launcherStyle.topBarTextColor
                            )
                        }
                    }
                    Text(
                        text = state.title,
                        color = launcherStyle.accentColor,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = if (flueScreenRound) TextAlign.Center else TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (flueScreenRound) {
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .padding(horizontal = 50.dp)
                        } else {
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth()
                                .padding(start = 54.dp, end = 16.dp)
                        },
                    )
                }

                // 内容区：居中转圈 / 完成图标 + 副标题。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (state.completed) {
                            Icon(
                                imageVector = OfficialMiuixIcons.OfficialOk,
                                contentDescription = null,
                                tint = launcherStyle.accentColor,
                                modifier = Modifier.size(64.dp)
                            )
                        } else {
                            // 与表盘加载页同一个抽象入口，三档各转各自的圈。
                            FlueLoadingIndicator(
                                size = 32.dp,
                            )
                        }
                        if (!state.detail.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = state.detail,
                                color = launcherStyle.secondaryTextColor,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                // 底部按钮区：完成态显示「分享」+「完成」胶囊按钮。
                if (state.completed) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.shareUri != null) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(launcherStyle.cardColor)
                                    .clickable(onClick = onShare),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.action_share),
                                    color = launcherStyle.accentColor,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(launcherStyle.accentColor)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.action_done),
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(if (flueScreenRound) 30.dp else 18.dp))
            }
        }
        } // ProvideDialogUiScale
    }
}

private fun shareBackupUri(context: android.content.Context, uri: Uri) {
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                context.getString(R.string.backup_share_title)
            )
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_share_failed), Toast.LENGTH_SHORT).show()
    }
}

private suspend fun exportLog(context: android.content.Context) {
    try {
        val file = withContext(Dispatchers.IO) {
            val rawLog = runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-t", "500"))
                    .inputStream
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrElse { error -> "logcat export failed: ${error.message.orEmpty()}" }
            val crashSummary = listOf("crash_brief.txt", "crash_info.txt", "crash_app_info.txt")
                .mapNotNull { name ->
                    val crashFile = File(context.cacheDir, name)
                    if (!crashFile.exists()) return@mapNotNull null
                    "===== $name =====\n${crashFile.readText()}"
                }
                .joinToString("\n\n")
                .ifBlank { "No private crash files found." }
            File(context.cacheDir, "wlauncher_log.txt").apply {
                writeText(
                    buildString {
                        appendLine("===== Flue Diagnostics =====")
                        appendLine("diagnosticLoggingEnabled=${FlueLog.isDiagnosticLoggingEnabled()}")
                        appendLine()
                        appendLine("===== Private Crash Files =====")
                        appendLine(crashSummary)
                        appendLine()
                        appendLine("===== Raw logcat tail =====")
                        append(rawLog)
                    }
                )
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                context.getString(R.string.settings_export_log)
            )
        )
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
    }
}

private fun hasBodySensorsPermission(context: Context): Boolean =
    hasHeartRatePermission(context)

private fun openHeartRatePermissionSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }
}

/**
 * 设置搜索索引：标题 / 描述 / 关键词（含拼音首字母与英文别名）。
 * destinationName 用字符串与枚举解耦；命中跳转到对应页面。
 */
/**
 * 设置搜索索引。
 *
 * itemKey 必须和目标页 LazyColumn 里 `item("...")` 的 key 逐字一致——搜索
 * 结果就是靠它跳到具体那一行的（见 SettingsPageScaffold 的 searchTarget
 * 定位逻辑）。以前这里有几个 key 是凭印象写的（advanced_material /
 * edge_blur / ui_style / screen_shape / watchface_date_locale），页面里
 * 并不存在，点进去只能落在页顶。
 *
 * 覆盖原则：每一个开关 / 滑块都要有自己的条目，不能只索引到分组页。
 */
internal fun buildSettingsSearchIndex(context: Context): List<SettingsSearchEntry> {
    fun s(id: Int): String = context.getString(id)
    return listOf(
    SettingsSearchEntry("APPEARANCE", null, s(R.string.settings_appearance), s(R.string.search_d_appearance), listOf("xsywg", "appearance", "theme", "zhuti", "fengge")),
    SettingsSearchEntry("APPEARANCE", "layout_honeycomb", s(R.string.settings_layout_honeycomb), s(R.string.search_d_layout_honeycomb), listOf("fwbj", "honeycomb", "fengwo")),
    SettingsSearchEntry("APPEARANCE", "layout_list", s(R.string.settings_layout_list), s(R.string.search_d_layout_list), listOf("lbbj", "list", "liebiao")),
    SettingsSearchEntry("APPEARANCE", "layout_grid", s(R.string.settings_layout_grid), s(R.string.settings_layout_grid_sub), listOf("wgbj", "grid", "wangge", "miji")),
    SettingsSearchEntry("APPEARANCE", "layout_flow", s(R.string.settings_layout_flow), s(R.string.settings_layout_flow_sub), listOf("xlbj", "flow", "xinliu", "yueya", "crescent")),
    SettingsSearchEntry("APPEARANCE", "layout_ring", s(R.string.settings_layout_ring), s(R.string.settings_layout_ring_sub), listOf("xhbj", "ring", "xinghuan", "huanxing", "shoulong", "dome")),
    SettingsSearchEntry("APPEARANCE", "ui_style_apple_watch", s(R.string.settings_ui_aw), s(R.string.settings_ui_aw_sub), listOf("pgbx", "apple watch", "pingguo", "watchos")),
    SettingsSearchEntry("APPEARANCE", "ui_style_material_3", s(R.string.settings_ui_md3), s(R.string.settings_ui_md3_sub), listOf("md3", "material", "expressive")),
    SettingsSearchEntry("APPEARANCE", "ui_style_miuix", s(R.string.settings_ui_miuix), s(R.string.settings_ui_miuix_sub), listOf("miuix", "hyperos", "xiaomi", "aoshi")),
    SettingsSearchEntry("APPEARANCE", "transition_motion_scale", s(R.string.settings_transition_motion_scale), s(R.string.search_d_transition_motion), listOf("sfdh", "zoom", "suofang", "donghua", "animation")),
    SettingsSearchEntry("APPEARANCE", "transition_motion_slide", s(R.string.settings_transition_motion_slide), s(R.string.search_d_transition_motion), listOf("pydh", "slide", "pingyi", "donghua", "animation")),
    SettingsSearchEntry("APPEARANCE", "screen_shape_auto", s(R.string.settings_screen_shape_auto), s(R.string.settings_screen_shape_auto_sub), listOf("zdsb", "auto", "zidong")),
    SettingsSearchEntry("APPEARANCE", "screen_shape_round", s(R.string.settings_screen_shape_round), s(R.string.settings_screen_shape_override_sub), listOf("yp", "round", "yuanping")),
    SettingsSearchEntry("APPEARANCE", "screen_shape_square", s(R.string.settings_screen_shape_square), s(R.string.settings_screen_shape_override_sub), listOf("fp", "square", "fangping")),
    SettingsSearchEntry("APPEARANCE", "theme_mode_system", s(R.string.search_t_theme_system), s(R.string.search_d_theme_mode), listOf("gssx", "system theme", "shenqian")),
    SettingsSearchEntry("APPEARANCE", "theme_mode_dark", s(R.string.settings_theme_dark), s(R.string.search_d_theme_mode), listOf("ssms", "dark", "shense")),
    SettingsSearchEntry("APPEARANCE", "theme_mode_light", s(R.string.settings_theme_light), s(R.string.search_d_theme_mode), listOf("qsms", "light", "qianse")),
    SettingsSearchEntry("APPEARANCE", "ui_style_header", s(R.string.settings_ui_style_header), s(R.string.search_d_ui_style), listOf("jmfg", "style", "miuix", "md3")),
    SettingsSearchEntry("APPEARANCE", "screen_shape_header", s(R.string.settings_screen_shape_header), s(R.string.search_d_screen_shape), listOf("pmxz", "round", "yuanping", "fangping")),
    SettingsSearchEntry("APPEARANCE", "overlay_mask_corner_radius", s(R.string.settings_overlay_mask_corner_radius), s(R.string.search_d_overlay_mask_corner_radius), listOf("zzj", "zhezhao yuanjiao", "mask corner", "corner radius", "yuanjiao")),
    SettingsSearchEntry("APPEARANCE", "blur_toggle", s(R.string.settings_blur), s(R.string.search_d_blur), listOf("gjcz", "blur", "mohu", "boli")),
    SettingsSearchEntry("APPEARANCE", "edge_blur_toggle", s(R.string.search_t_edge_blur), s(R.string.search_d_edge_blur), listOf("bymh", "edge blur")),
    SettingsSearchEntry("APPEARANCE", "edge_blur_radius", s(R.string.settings_edge_blur_radius), s(R.string.search_d_edge_blur_radius), listOf("bymhbj", "blur radius", "banjing")),
    SettingsSearchEntry("APPEARANCE", "top_fade", s(R.string.settings_top_fade), s(R.string.search_d_top_fade), listOf("dbjy", "top fade", "jianyin")),
    SettingsSearchEntry("APPEARANCE", "bottom_fade", s(R.string.settings_bottom_fade), s(R.string.search_d_bottom_fade), listOf("dbjy", "bottom fade")),
    SettingsSearchEntry("APPEARANCE", "splash_toggle", s(R.string.settings_splash), s(R.string.settings_splash_sub), listOf("qdzz", "splash", "zhezhao")),
    SettingsSearchEntry("APPEARANCE", "direct_launch_app_list", s(R.string.settings_direct_launch_app_list), s(R.string.search_d_direct_launch), listOf("zjjr", "direct launch")),
    SettingsSearchEntry("APPEARANCE", "splash_delay", s(R.string.search_t_splash_delay), s(R.string.search_d_splash_delay), listOf("qdyc", "splash delay", "yanchi")),
    SettingsSearchEntry("DISPLAY_SIZE", null, s(R.string.settings_display_fit), s(R.string.search_d_display_size), listOf("xssp", "size", "daxiao", "fisheye", "yuyan")),
    SettingsSearchEntry("AOD", null, s(R.string.aod_title), s(R.string.aod_description), listOf("aod", "xiping", "ambient", "息屏")),
    SettingsSearchEntry("AOD", "aod_enabled", s(R.string.aod_title), s(R.string.aod_description), listOf("aod", "brightness", "timeout", "息屏", "亮度")),
    SettingsSearchEntry("DISPLAY_SIZE", "global_ui_scale", s(R.string.settings_global_ui_size), s(R.string.search_d_global_ui_scale), listOf("qjdx", "scale", "suofang")),
    SettingsSearchEntry("DISPLAY_SIZE", "honeycomb_cols", s(R.string.settings_honeycomb_cols), s(R.string.search_d_honeycomb_cols), listOf("fwls", "columns", "lieshu")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_scale", s(R.string.settings_app_list_size), s(R.string.search_d_app_list_scale), listOf("lbdx", "app list size")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_scroll_indicator", s(R.string.settings_scroll_indicator), s(R.string.settings_scroll_indicator_on), listOf("hdjdzsq", "scroll indicator", "gundong", "jindutiao", "scrollbar")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_fisheye", s(R.string.settings_fisheye), s(R.string.search_d_fisheye), listOf("yyxg", "fisheye", "yuyan")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_fisheye_range_rows", s(R.string.settings_fisheye_range), s(R.string.search_d_fisheye_range), listOf("yysx", "fisheye range")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_fisheye_strength", s(R.string.settings_fisheye_strength), s(R.string.search_d_fisheye_strength), listOf("yyqd", "fisheye strength")),
    SettingsSearchEntry("DISPLAY_SIZE", "material_honeycomb_top_fisheye", s(R.string.settings_fisheye_top_row), s(R.string.settings_fisheye_top_row_sub), listOf("dyphy", "top row fisheye", "shouhang")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_grid_icon_plate", s(R.string.settings_grid_icon_plate), s(R.string.settings_grid_icon_plate_on), listOf("dbtbzz", "icon plate", "icon backplate", "zhezhao", "dise", "wangge")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_flow_crescent_direction", s(R.string.settings_flow_crescent_direction), s(R.string.settings_flow_crescent_direction_right), listOf("yyfx", "crescent", "yueya", "flow direction")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_flow_crescent_bulge", s(R.string.settings_flow_crescent_bulge), s(R.string.settings_flow_crescent_bulge_sub), listOf("yyhd", "crescent bulge", "yueya", "gubao")),
    SettingsSearchEntry("DISPLAY_SIZE", "layout_profile_header", s(R.string.settings_layout_profile_header), s(R.string.settings_layout_profile_preset), listOf("bjda", "layout profile", "dangan", "buju")),
    SettingsSearchEntry("DISPLAY_SIZE", "layout_profile_copy", s(R.string.settings_layout_profile_copy), s(R.string.settings_layout_profile_copy_sub), listOf("fzda", "copy profile", "fuzhi")),
    SettingsSearchEntry("DISPLAY_SIZE", "layout_profile_reset", s(R.string.settings_layout_profile_reset), s(R.string.settings_layout_profile_reset_sub), listOf("czda", "reset profile", "chongzhi")),
    SettingsSearchEntry("DISPLAY_SIZE", "layout_profile_delete", s(R.string.settings_layout_profile_delete), s(R.string.settings_layout_profile_delete_sub), listOf("scda", "delete profile", "shanchu")),
    SettingsSearchEntry("DISPLAY_SIZE", "layout_profile_export", s(R.string.settings_layout_profile_export), s(R.string.settings_layout_profile_export_sub), listOf("dcda", "export profile", "daochu")),
    SettingsSearchEntry("DISPLAY_SIZE", "layout_profile_import", s(R.string.settings_layout_profile_import), s(R.string.settings_layout_profile_import_sub), listOf("drda", "import profile", "daoru")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_edge_spacing_compression", s(R.string.settings_edge_spacing), s(R.string.search_d_edge_spacing), listOf("byjj", "edge spacing")),
    SettingsSearchEntry("DISPLAY_SIZE", "app_list_left_safe_inset", s(R.string.settings_left_safe_area), s(R.string.search_d_left_safe_area), listOf("zcaqq", "safe area", "anquanqu")),
    SettingsSearchEntry("DISPLAY_SIZE", "rotary_haptics", s(R.string.settings_rotary_haptics), s(R.string.search_d_rotary_haptics), listOf("bgzd", "rotary haptics", "zhendong")),
    SettingsSearchEntry("WATCH_FACES", null, s(R.string.settings_watchfaces), s(R.string.search_d_watchfaces), listOf("bp", "watchface", "biaopan")),
    SettingsSearchEntry("WATCH_FACES", "watchface_store", s(R.string.search_t_watchface_store), s(R.string.search_d_watchface_store), listOf("bpsd", "store", "shangdian")),
    SettingsSearchEntry("WATCH_FACES", "watchface_editor_new", s(R.string.search_t_watchface_editor_new), s(R.string.search_d_watchface_editor_new), listOf("xjbp", "editor", "bianjiqi")),
    SettingsSearchEntry("WATCH_FACES", "watchface_import_archive", s(R.string.search_t_watchface_import), s(R.string.search_d_watchface_import), listOf("drbp", "import", "daoru", "watch")),
    SettingsSearchEntry("WATCH_FACES", "watchface_editor_current", s(R.string.settings_watchface_editor_current), s(R.string.search_t_watchface_editor_new), listOf("bjbp", "edit face", "bianji", "editor")),
    SettingsSearchEntry("WATCH_FACES", "watchface_refresh", s(R.string.settings_watchface_refresh), s(R.string.settings_watchface_refresh_sub), listOf("smbp", "rescan", "shuaxin", "refresh")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_tap_haptics", s(R.string.settings_watchface_tap_haptics), s(R.string.settings_watchface_tap_haptics_sub), listOf("djzd", "zhendong", "tap haptics", "vibration", "振动", "震动")),
    SettingsSearchEntry("WATCH_FACE_MORE", null, s(R.string.search_t_watchface_more), s(R.string.search_d_watchface_more), listOf("bpgd", "xinlv", "riqi", "date")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_long_press_mode", s(R.string.settings_watchface_long_press_mode), s(R.string.settings_watchface_long_press_mode_cards_sub), listOf("chang an", "long press", "cards", "list", "kapian", "liebiao")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watch_weather_header", s(R.string.settings_watch_weather), s(R.string.settings_watch_weather_sub), listOf("tq", "weather", "tianqi")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watch_weather_manual_city", s(R.string.settings_watch_weather_city), s(R.string.settings_watch_weather_city_unset), listOf("cs", "city", "chengshi", "location")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watch_weather_system_location", s(R.string.settings_watch_weather_use_system), s(R.string.settings_watch_weather_use_system_sub), listOf("xtdw", "system location", "dingwei", "gps")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watch_weather_refresh", s(R.string.settings_watch_weather_refresh), s(R.string.settings_watch_weather_refresh_sub), listOf("sxtq", "refresh weather", "shuaxin")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watch_weather_unit_header", s(R.string.settings_watch_weather_unit), s(R.string.settings_watch_weather_celsius_sub), listOf("wd", "temperature", "celsius", "fahrenheit", "sheshi", "huashi")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_date_locale_header", s(R.string.settings_watchface_date_language), s(R.string.search_d_watchface_date_locale), listOf("rqyy", "date locale", "yuyan")),
    // 「强制铺满」这一项已经搬进 CanvasAdjustActivity（设置页里那个 item key 早就没了，
    // 旧索引指着它只能落在页顶）。改指向通往那一页的入口行 —— 搜得到、点进去也真能改到。
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_canvas_entry", s(R.string.settings_watchmaker_force_fill), s(R.string.search_d_force_fill), listOf("qzpm", "force fill", "puman")),
    // 画布调节：整块搬进 CanvasAdjustActivity 之后，设置页只剩这一个入口行。
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_canvas_entry", s(R.string.settings_watchface_canvas_section), s(R.string.settings_watchface_canvas_entry_sub), listOf("hbtj", "canvas", "huabu", "zoom", "offset")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_gravity_parallax", s(R.string.settings_watchface_gravity_parallax), s(R.string.settings_watchface_gravity_parallax_on), listOf("景深", "gravity", "gyro", "parallax", "jing shen")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_gravity_backdrop_blur", s(R.string.settings_watchface_gravity_backdrop_blur), s(R.string.settings_watchface_gravity_backdrop_blur_on), listOf("模糊", "背景", "blur", "backdrop", "mo hu", "bei jing")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchmaker_body_sensors", s(R.string.settings_watchface_heart_rate), s(R.string.search_d_heart_rate), listOf("bpxl", "heart rate", "xinlv")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchmaker_calendar", s(R.string.settings_watchface_calendar), s(R.string.search_d_watchface_calendar), listOf("calendar", "event", "richeng", "rili")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_font", s(R.string.settings_watchface_font), s(R.string.search_d_watchface_font), listOf("bpzt", "font", "ziti")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_font_clear", s(R.string.settings_watchface_font_clear), s(R.string.settings_watchface_font_clear_sub), listOf("qcbpzt", "reset font", "qingchu", "ziti")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_bottom_fade", s(R.string.settings_watchface_bottom_fade), s(R.string.search_d_watchface_bottom_fade), listOf("bpdb", "bottom fade")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_text_entry_animation", s(R.string.settings_watchface_text_entry_animation), s(R.string.settings_watchface_text_entry_animation_sub), listOf("bpwz", "text animation", "fly in")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_text_exit_animation", s(R.string.settings_watchface_text_exit_animation), s(R.string.settings_watchface_text_exit_animation_sub), listOf("bpwz", "text exit animation", "fly out")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_status_indicators", s(R.string.settings_watchface_status), s(R.string.search_d_watchface_status), listOf("ztts", "indicator")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_dnd_indicator", s(R.string.settings_watchface_dnd_indicator), s(R.string.search_d_watchface_dnd_indicator), listOf("勿扰", "moon", "dnd", "do not disturb", "wurao")),
    SettingsSearchEntry("WATCH_FACE_MORE", "watchface_charging_power_text", s(R.string.settings_watchface_charging_power), s(R.string.search_d_charging_power), listOf("cdgl", "charging", "wattage")),
    SettingsSearchEntry("WATCH_FACE_MORE", "app_list_background_entry", s(R.string.settings_app_list_background), s(R.string.search_d_app_list_background), listOf("yylbbj", "app list background", "beijing", "bizhi", "wallpaper")),
    SettingsSearchEntry("APP_LIST_BACKGROUND", null, s(R.string.settings_app_list_background), s(R.string.search_d_app_list_background), listOf("yylbbj", "app list background", "beijing", "bizhi", "wallpaper")),
    SettingsSearchEntry("APP_LIST_BACKGROUND", "app_list_background_solid_system", s(R.string.settings_app_list_background_solid_system), s(R.string.settings_app_list_background_solid_system_sub), listOf("cs", "solid", "chunse", "heibai")),
    SettingsSearchEntry("APP_LIST_BACKGROUND", "app_list_background_solid_custom", s(R.string.settings_app_list_background_solid_custom), s(R.string.settings_app_list_background_solid_custom_sub), listOf("zdyys", "custom colour", "yanse", "quse")),
    SettingsSearchEntry("APP_LIST_BACKGROUND", "app_list_background_pick_color", s(R.string.settings_app_list_background_color), s(R.string.settings_app_list_background_solid_custom_sub), listOf("bjys", "background colour", "yanse", "quse")),
    SettingsSearchEntry("APP_LIST_BACKGROUND", "app_list_background_watch_face", s(R.string.settings_app_list_background_watch_face), s(R.string.settings_app_list_background_watch_face_sub), listOf("gsbp", "follow watch face", "genshui", "biaopan")),
    SettingsSearchEntry("APP_LIST_BACKGROUND", "app_list_background_custom_image", s(R.string.settings_app_list_background_custom_image), s(R.string.settings_app_list_background_custom_image_sub), listOf("zdytp", "custom image", "tupian", "bizhi")),
    SettingsSearchEntry("APP_LIST_BACKGROUND", "app_list_background_pick_image", s(R.string.settings_app_list_background_pick_image), s(R.string.settings_app_list_background_pick_image_sub), listOf("xztp", "pick image", "xuanze", "tupian", "caijian", "crop")),
    SettingsSearchEntry("APP_LIST_BACKGROUND", "app_list_background_blur", s(R.string.settings_app_list_background_blur), s(R.string.settings_app_list_background_blur_sub), listOf("mhcd", "blur", "mohu")),
    SettingsSearchEntry("APP_LIST_BACKGROUND", "app_list_background_scrim", s(R.string.settings_app_list_background_scrim), s(R.string.settings_app_list_background_scrim_sub), listOf("zzqd", "scrim", "zhezhao", "yadang")),
    SettingsSearchEntry("ICON_PACKS", null, s(R.string.settings_icon_packs), s(R.string.search_d_icon_packs), listOf("tbb", "icon pack")),
    SettingsSearchEntry("ICON_PACKS", "icon_pack_default", s(R.string.settings_icon_pack_default), s(R.string.settings_icon_pack_default_sub), listOf("mrtb", "default icons", "moren")),
    SettingsSearchEntry("ICON_PACKS", "icon_pack_refresh", s(R.string.settings_icon_pack_refresh), s(R.string.settings_icon_pack_refresh_sub), listOf("sxtbb", "rescan icon packs", "shuaxin")),
    SettingsSearchEntry("HIDDEN_APPS", null, s(R.string.settings_hidden_apps), s(R.string.search_d_hidden_apps), listOf("ycyy", "hidden", "yincang")),
    SettingsSearchEntry("FEATURE_TOGGLES", null, s(R.string.settings_feature_toggles), s(R.string.search_d_feature_toggles), listOf("gnkg", "toggles")),
    SettingsSearchEntry("FEATURE_TOGGLES", "glass_highlight_toggle", s(R.string.settings_glass_highlight), s(R.string.settings_glass_highlight_on), listOf("gaoguang", "glass", "highlight", "bianyuan")),
    SettingsSearchEntry("SIDE_SCREEN", null, s(R.string.settings_side_screen_title), s(R.string.search_d_side_screen), listOf("fyp", "side screen", "fuyiping", "xiaozujian")),
    SettingsSearchEntry("SIDE_SCREEN", "side_screen_toggle", s(R.string.settings_side_screen), s(R.string.search_d_side_screen_toggle), listOf("fyp", "side screen")),
    SettingsSearchEntry("SIDE_SCREEN", "widget_center_toggle", s(R.string.search_t_widget_page), s(R.string.search_d_widget_page), listOf("xzjy", "widget")),
    SettingsSearchEntry("SIDE_SCREEN", "widget_glass_highlight", s(R.string.settings_widget_glass_highlight), s(R.string.search_d_widget_glass_highlight), listOf("xzjy", "widget", "glass", "highlight", "gaoguang")),
    SettingsSearchEntry("SIDE_SCREEN", "show_step_count", s(R.string.settings_show_steps), s(R.string.search_d_show_steps), listOf("xsbs", "steps", "bushu")),
    SettingsSearchEntry("SIDE_SCREEN", "music_notification_swap_toggle", s(R.string.settings_swap_music_notifications), s(R.string.search_d_swap), listOf("hhzj", "swap")),
    SettingsSearchEntry("NOTIFICATION_SETTINGS", null, s(R.string.settings_notifications_title), s(R.string.search_d_notifications), listOf("tzzx", "notification", "tongzhi")),
    SettingsSearchEntry("NOTIFICATION_SETTINGS", "notification_center_toggle", s(R.string.settings_show_notifications), s(R.string.search_d_show_notifications), listOf("xstz", "notifications")),
    SettingsSearchEntry("NOTIFICATION_SETTINGS", "ongoing_notification_toggle", s(R.string.settings_show_ongoing), s(R.string.search_d_show_ongoing), listOf("czts", "ongoing")),
    SettingsSearchEntry("CONTROL_CENTER_SETTINGS", null, s(R.string.settings_control_center_title), s(R.string.search_d_control_center), listOf("kzzx", "control center", "kongzhi")),
    SettingsSearchEntry("CONTROL_CENTER_SETTINGS", "control_center_toggle", s(R.string.settings_show_cc), s(R.string.search_d_show_cc), listOf("xskzzx", "show control center")),
    SettingsSearchEntry("CONTROL_CENTER_SETTINGS", "music_controls_toggle", s(R.string.settings_show_music), s(R.string.search_d_show_music), listOf("yykz", "music", "yinyue")),
    SettingsSearchEntry("CONTROL_CENTER_SETTINGS", "media_custom_actions_toggle", s(R.string.settings_media_actions), s(R.string.search_d_media_actions), listOf("zdyaj", "media actions")),
    SettingsSearchEntry("CONTROL_CENTER_SETTINGS", "power_menu_button", s(R.string.settings_cc_power), s(R.string.search_d_cc_power), listOf("dyaj", "power")),
    SettingsSearchEntry("GESTURE_SETTINGS", null, s(R.string.settings_gestures_title), s(R.string.search_d_gestures), listOf("ssaj", "gesture", "shoushi")),
    SettingsSearchEntry("GESTURE_SETTINGS", "double_tap_lock_screen", s(R.string.settings_double_tap_lock), s(R.string.search_d_double_tap), listOf("sjxp", "double tap", "xiping")),
    SettingsSearchEntry("GESTURE_SETTINGS", "face_ignores_back_key", s(R.string.settings_face_ignores_back), s(R.string.settings_face_ignores_back_sub), listOf("bpbxyfhj", "back key", "mitu", "mitou", "fanhui")),
    SettingsSearchEntry("APP_LIST_BEHAVIOR", null, s(R.string.settings_app_list_behavior_title), s(R.string.search_d_app_list_behavior), listOf("yylb", "app list")),
    SettingsSearchEntry("APP_LIST_BEHAVIOR", "app_list_folders_enabled", s(R.string.settings_allow_folders), s(R.string.search_d_folders), listOf("wjj", "folder", "wenjianjia")),
    SettingsSearchEntry("APP_LIST_BEHAVIOR", "app_list_watchface_colors", s(R.string.settings_watchface_launch_mask), s(R.string.search_d_launch_mask), listOf("zzqs", "mask color")),
    SettingsSearchEntry("APP_LIST_BEHAVIOR", "app_list_row_border", s(R.string.settings_list_row_bg), s(R.string.search_d_row_bg), listOf("dsmz", "row background")),
    SettingsSearchEntry("PERFORMANCE", null, s(R.string.settings_performance), s(R.string.search_d_performance), listOf("xnydh", "performance", "donghua")),
    // 性能页拆成三个二级子页后，锚点的 destinationName 必须跟着搬家。
    // SettingsDestination.valueOf 外面裹着 runCatching，写错名字不会报错，
    // 只会静默跳不过去——所以这里的名字与上面的 enum 必须逐条对齐。
    SettingsSearchEntry("PERFORMANCE_RENDERING", null, s(R.string.settings_perf_group_rendering), s(R.string.settings_perf_group_rendering_sub), listOf("xrzl", "rendering", "zhiliang")),
    SettingsSearchEntry("PERFORMANCE_MOTION", null, s(R.string.settings_perf_group_motion), s(R.string.settings_perf_group_motion_sub), listOf("dhzc", "motion", "transition", "zhuanchang")),
    SettingsSearchEntry("PERFORMANCE_SYSTEM", null, s(R.string.settings_perf_group_system), s(R.string.settings_perf_group_system_sub), listOf("xtwl", "system", "network", "wangluo")),
    SettingsSearchEntry("PERFORMANCE_MOTION", "app_list_stagger", s(R.string.settings_stagger_entry), s(R.string.search_d_stagger), listOf("swrc", "stagger")),
    SettingsSearchEntry("PERFORMANCE_MOTION", "legacy_app_list_transition", s(R.string.settings_legacy_transition), s(R.string.search_d_legacy), listOf("lzcd", "legacy")),
    SettingsSearchEntry("PERFORMANCE_MOTION", "app_list_return_shrink", s(R.string.settings_app_list_shrink), s(R.string.search_d_app_list_shrink), listOf("sswd", "shrink")),
    SettingsSearchEntry("PERFORMANCE_MOTION", "app_list_return_icon_float", s(R.string.settings_icon_float), s(R.string.search_d_icon_float), listOf("tbsf", "icon float")),
    SettingsSearchEntry("PERFORMANCE_MOTION", "face_return_shrink", s(R.string.settings_face_shrink), s(R.string.search_d_face_shrink), listOf("bpss", "face shrink", "shousuo", "biaopan")),
    SettingsSearchEntry("PERFORMANCE_MOTION", "reduce_dynamic_effects", s(R.string.settings_reduce_motion), s(R.string.search_d_reduce_motion), listOf("jrdt", "reduce motion", "jianruo", "huitan")),
    SettingsSearchEntry("PERFORMANCE_MOTION", "anim_override", s(R.string.settings_launch_animation), s(R.string.search_d_launch_animation), listOf("qdfh", "launch animation")),
    SettingsSearchEntry("PERFORMANCE_RENDERING", "icon_shadow", s(R.string.settings_icon_shadow), s(R.string.search_d_icon_shadow), listOf("tbyy", "shadow", "yinying")),
    SettingsSearchEntry("PERFORMANCE_RENDERING", "low_res", s(R.string.settings_low_res_icons), s(R.string.search_d_low_res), listOf("dfbl", "low res", "fenbianlv")),
    SettingsSearchEntry("PERFORMANCE_MOTION", "music_text_animation", s(R.string.settings_music_text_animation), s(R.string.search_d_music_text), listOf("yywz", "music text")),
    SettingsSearchEntry("PERFORMANCE_SYSTEM", "hide_from_recents", s(R.string.settings_hide_recents), s(R.string.search_d_hide_recents), listOf("ychtkp", "recents", "houtai")),
    SettingsSearchEntry("PERFORMANCE_SYSTEM", "watch_store_segmented_download", s(R.string.settings_watch_store_segmented_download), s(R.string.search_d_segmented_download), listOf("dxcfdxz", "segmented", "download", "xiazai", "duoxiancheng")),
    SettingsSearchEntry("PERFORMANCE_RENDERING", "builtin_manager_thumbnails", s(R.string.settings_builtin_thumbnails), s(R.string.search_d_thumbnails), listOf("sltu", "thumbnails", "suoluetu")),
    SettingsSearchEntry("PERFORMANCE_MOTION", "motion_profile", s(R.string.search_t_motion_profile_entry), s(R.string.search_d_motion_profile_entry), listOf("dhqx", "motion profile")),
    SettingsSearchEntry("MOTION_PROFILE", null, s(R.string.settings_motion_profile_title), s(R.string.search_d_motion_profile), listOf("dhqx", "motion", "quxian", "spring")),
    SettingsSearchEntry("MOTION_PROFILE", "motion_bounce", s(R.string.settings_motion_bounce), s(R.string.search_d_motion_bounce), listOf("cbht", "bounce", "huitan")),
    SettingsSearchEntry("MOTION_PROFILE", "motion_edge_strength", s(R.string.settings_motion_bounce_strength), s(R.string.search_d_edge_strength), listOf("bjqd", "edge strength")),
    SettingsSearchEntry("MOTION_PROFILE", "motion_copy", s(R.string.settings_motion_profile_copy), s(R.string.settings_motion_profile_copy_sub), listOf("fzqx", "copy curve", "fuzhi")),
    SettingsSearchEntry("MOTION_PROFILE", "motion_delete", s(R.string.settings_motion_profile_delete), s(R.string.settings_motion_profile_delete_sub), listOf("scqx", "delete curve", "shanchu")),
    SettingsSearchEntry("MOTION_PROFILE", "motion_export", s(R.string.settings_motion_export_curves), s(R.string.settings_motion_export_curves_sub), listOf("dcqx", "export curves", "daochu")),
    SettingsSearchEntry("MOTION_PROFILE", "motion_import", s(R.string.settings_motion_import_curves), s(R.string.settings_motion_import_curves_sub), listOf("drqx", "import curves", "daoru")),
    SettingsSearchEntry("MUSIC_TEXT_ANIMATION", null, s(R.string.search_t_music_text_anim), s(R.string.search_d_music_text), listOf("yywz", "music text")),
    SettingsSearchEntry("TOOLS", null, s(R.string.settings_tools), s(R.string.search_d_tools), listOf("gj", "tools", "beifen")),
    SettingsSearchEntry("TOOLS", "export_log", s(R.string.settings_export_log), s(R.string.search_d_export_log), listOf("dcrz", "export log", "rizhi")),
    SettingsSearchEntry("TOOLS", "watch_store_preview_cache", s(R.string.settings_clear_watch_store_cache), s(R.string.settings_clear_watch_store_cache_sub), listOf("qlhc", "cache", "fengmian", "biaopan shichang")),
    SettingsSearchEntry("TOOLS", "watch_store_market_url", s(R.string.settings_watch_store_market_url), s(R.string.search_d_watchface_store), listOf("zdysc", "market url", "wangzhi", "shichang")),
    SettingsSearchEntry("WATCH_FACE_MORE", "tap_actions", s(R.string.settings_tap_actions), s(R.string.settings_tap_actions_sub), listOf("bpdj", "tap action", "dianji dongzuo", "yinle", "music")),
    SettingsSearchEntry("TOOLS", "web_zoom", s(R.string.settings_web_zoom), s(R.string.settings_web_zoom_sub), listOf("wysf", "web zoom", "suofang", "wangyeban")),
    SettingsSearchEntry("TOOLS", "clear_web_data", s(R.string.settings_clear_web_data), s(R.string.settings_clear_web_data_sub), listOf("qlwy", "clear web", "cookie", "denglu")),
    SettingsSearchEntry("TOOLS", "diagnostic_logging", s(R.string.settings_diagnostic_logging), s(R.string.search_d_diagnostic), listOf("zdrz", "diagnostic")),
    SettingsSearchEntry("TOOLS", "reset_defaults", s(R.string.settings_reset_defaults), s(R.string.search_d_reset), listOf("hfmr", "reset", "moren")),
    SettingsSearchEntry("BACKUP", null, s(R.string.search_t_backup), s(R.string.search_d_backup), listOf("bfyf", "backup")),
    SettingsSearchEntry("BACKUP_EXPORT", null, s(R.string.settings_backup_export_page), s(R.string.settings_backup_export_page_sub), listOf("dcpz", "export config", "daochu", "beifen")),
    SettingsSearchEntry("BACKUP_IMPORT", null, s(R.string.settings_backup_import_page), s(R.string.settings_backup_import_page_sub), listOf("drpz", "import config", "daoru", "huifu", "restore")),
    SettingsSearchEntry("LANGUAGE", null, s(R.string.settings_language), s(R.string.search_d_language), listOf("yy", "language", "yuyan", "zhongwen", "english")),
    SettingsSearchEntry("ABOUT", null, s(R.string.settings_about), s(R.string.search_d_about), listOf("gy", "about", "banben")),
    SettingsSearchEntry("DONATE", null, s(R.string.search_t_donate), s(R.string.search_d_donate), listOf("jz", "donate", "juanzeng")),
    )
}
