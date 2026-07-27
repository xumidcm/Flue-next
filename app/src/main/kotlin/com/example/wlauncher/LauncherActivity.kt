package com.flue.launcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlin.math.roundToInt
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.launchsession.ExternalLaunchState
import com.flue.launcher.launchsession.ReturnKind
import com.flue.launcher.service.FlueAccessibilityService
import com.flue.launcher.ui.anim.appListLaunchPlateValues
import com.flue.launcher.ui.anim.appListLaunchValues
import com.flue.launcher.ui.anim.appListLayerValues
import com.flue.launcher.ui.anim.faceLayerValues
import com.flue.launcher.ui.anim.returnUsesLaunchOrigin
import com.flue.launcher.ui.anim.scaleBlurAlpha
import com.flue.launcher.ui.anim.LayerPivot
import com.flue.launcher.motion.asFloatAnimationSpec
import com.flue.launcher.motion.LocalFlueMotionProfile
import com.flue.launcher.ui.controlcenter.ControlCenterLayer
import com.flue.launcher.ui.drawer.AppFolderOverlay
import com.flue.launcher.ui.drawer.NativeListDrawerScreen
import com.flue.launcher.ui.drawer.NativeAppListEdgeFadeOverlay
import com.flue.launcher.applist.nativeview.NativeAppListPresentation
import com.flue.launcher.ui.drawer.AppListBackend
import com.flue.launcher.ui.drawer.appListBackend
import com.flue.launcher.ui.drawer.appListPalette
import com.flue.launcher.ui.drawer.vibrateHaptic
import com.flue.launcher.ui.home.WatchFaceLayer
import com.flue.launcher.ui.home.WatchFaceBottomFadeOverlay
import com.flue.launcher.ui.home.WatchFaceStatusIndicatorOverlay
import com.flue.launcher.ui.home.rememberAppListSeedColor
import com.flue.launcher.ui.navigation.GestureHost
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.navigation.OverlayGestureTarget
import com.flue.launcher.ui.navigation.ScreenState
import com.flue.launcher.ui.anim.LayerAnimValues
import com.flue.launcher.ui.anim.flueBackdropFill
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.spring
import com.flue.launcher.ui.notification.NotificationLayer
import com.flue.launcher.ui.smartstack.SmartStackLayer
import com.flue.launcher.ui.smartstack.WidgetPageLayer
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.ThemeMode
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.ui.theme.isMaterial
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.LunchWatchFaceHost
import com.flue.launcher.watchface.dingdingcat.DingDingCatWatchFaceHost
import com.flue.launcher.watchface.jbwatch.JbWatchFaceHost
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceHost
import com.flue.launcher.watchface.standard.rememberWatchmakerWatchDataProvider
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val SIDE_SCREEN_TRANSITION_MS = 260
private const val NOTIFICATION_TRANSITION_MS = 240
private const val APP_RETURN_MAINTENANCE_DELAY_MS = 260L

/**
 * The tracker is the source of truth for a launch transition.  Keeping these
 * projections here prevents the UI from falling back to the last mutable
 * ViewModel origin while a return is being classified.
 */
private fun ExternalLaunchState.sessionOriginOrNull(): Offset? = when (this) {
    is ExternalLaunchState.MaskEntering -> Offset(session.origin.x, session.origin.y)
    is ExternalLaunchState.LaunchDispatched -> Offset(session.origin.x, session.origin.y)
    is ExternalLaunchState.Backgrounded -> Offset(session.origin.x, session.origin.y)
    is ExternalLaunchState.Returning -> Offset(session.origin.x, session.origin.y)
    is ExternalLaunchState.Failed -> Offset(session.origin.x, session.origin.y)
    is ExternalLaunchState.Cancelled -> Offset(session.origin.x, session.origin.y)
    is ExternalLaunchState.Invalidated -> session?.let { Offset(it.origin.x, it.origin.y) }
    ExternalLaunchState.Idle -> null
}

private fun ExternalLaunchState.sessionSourceStateOrNull(): ScreenState? = when (this) {
    is ExternalLaunchState.MaskEntering -> session.sourceState
    is ExternalLaunchState.LaunchDispatched -> session.sourceState
    is ExternalLaunchState.Backgrounded -> session.sourceState
    is ExternalLaunchState.Returning -> session.sourceState
    is ExternalLaunchState.Failed -> session.sourceState
    is ExternalLaunchState.Cancelled -> session.sourceState
    is ExternalLaunchState.Invalidated -> session?.sourceState
    ExternalLaunchState.Idle -> null
}

class LauncherActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()
    private var deferredResumeMaintenance: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.onLauncherCreated(
            restored = savedInstanceState != null,
            homeIntent = intent.isLauncherHomeIntent(),
        )
        RecentsVisibility.apply(this)
        CrashHandler(applicationContext).install()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        onBackPressedDispatcher.addCallback(this) { vm.requestBackPress() }
        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    LauncherScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.isLauncherHomeIntent()) {
            vm.onLauncherHomeIntent()
        }
    }

    override fun onResume() {
        super.onResume()
        RecentsVisibility.apply(this)
        vm.setLauncherInteractive(true)
        vm.onLauncherResumed()
        scheduleDeferredResumeMaintenance()
    }

    override fun onPause() {
        super.onPause()
        deferredResumeMaintenance?.let(window.decorView::removeCallbacks)
        deferredResumeMaintenance = null
        vm.setLauncherInteractive(false)
        vm.onLauncherPaused()
    }

    override fun onStop() {
        super.onStop()
        vm.onLauncherStopped()
    }

    private fun scheduleDeferredResumeMaintenance() {
        deferredResumeMaintenance?.let(window.decorView::removeCallbacks)
        val task = Runnable {
            deferredResumeMaintenance = null
            vm.refreshWatchFaces()
            vm.refreshNotificationAccess()
        }
        deferredResumeMaintenance = task
        window.decorView.postDelayed(task, APP_RETURN_MAINTENANCE_DELAY_MS)
    }
}

private fun Intent.isLauncherHomeIntent(): Boolean {
    return action == Intent.ACTION_MAIN &&
        hasCategory(Intent.CATEGORY_HOME)
}

@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val context = LocalContext.current
    val screenState by vm.screenState.collectAsStateWithLifecycle()
    val launcherInteractive by vm.launcherInteractive.collectAsStateWithLifecycle()
    val layoutMode by vm.layoutMode.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val sideScreenEnabled by vm.sideScreenEnabled.collectAsStateWithLifecycle()
    val sideScreenShortcuts by vm.sideScreenShortcuts.collectAsStateWithLifecycle()
    val blurEnabled by vm.blurEnabled.collectAsStateWithLifecycle()
    val edgeBlurEnabled by vm.edgeBlurEnabled.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val allApps by vm.allApps.collectAsStateWithLifecycle()
    val allSelectableApps by vm.allSelectableApps.collectAsStateWithLifecycle()
    val openFolder by vm.openFolder.collectAsStateWithLifecycle()
    val openFolderItems by vm.openFolderItems.collectAsStateWithLifecycle()
    val appOpenOrigin by vm.appOpenOrigin.collectAsStateWithLifecycle()
    val splashIcon by vm.splashIcon.collectAsStateWithLifecycle()
    val splashDelay by vm.splashDelay.collectAsStateWithLifecycle()
    val animationOverrideEnabled by vm.animationOverrideEnabled.collectAsStateWithLifecycle()
    val directLaunchAppListEnabled by vm.directLaunchAppListEnabled.collectAsStateWithLifecycle()
    val directLaunchAppListPreferenceLoaded by vm.directLaunchAppListPreferenceLoaded.collectAsStateWithLifecycle()
    val currentLaunchIcon by vm.currentLaunchIcon.collectAsStateWithLifecycle()
    val externalLaunchState by vm.externalLaunchState.collectAsStateWithLifecycle()
    val honeycombCols by vm.honeycombCols.collectAsStateWithLifecycle()
    val honeycombEdgeBlurRadius by vm.honeycombEdgeBlurRadius.collectAsStateWithLifecycle()
    val honeycombTopFade by vm.honeycombTopFade.collectAsStateWithLifecycle()
    val honeycombBottomFade by vm.honeycombBottomFade.collectAsStateWithLifecycle()
    val appListFisheyeEnabled by vm.appListFisheyeEnabled.collectAsStateWithLifecycle()
    val materialHoneycombTopFisheyeEnabled by vm.materialHoneycombTopFisheyeEnabled.collectAsStateWithLifecycle()
    val appListLeftSafeInsetPercent by vm.appListLeftSafeInsetPercent.collectAsStateWithLifecycle()
    val appListFisheyeRangeRows by vm.appListFisheyeRangeRows.collectAsStateWithLifecycle()
    val appListFisheyeStrengthPercent by vm.appListFisheyeStrengthPercent.collectAsStateWithLifecycle()
    val appListEdgeSpacingCompressionEnabled by vm.appListEdgeSpacingCompressionEnabled.collectAsStateWithLifecycle()
    val appListScalePercent by vm.appListScalePercent.collectAsStateWithLifecycle()
    val appListRowBorderEnabled by vm.appListRowBorderEnabled.collectAsStateWithLifecycle()
    val appListFoldersEnabled by vm.appListFoldersEnabled.collectAsStateWithLifecycle()
    val twoToneIconsEnabled by vm.twoToneIconsEnabled.collectAsStateWithLifecycle()
    val iconShadowEnabled by vm.iconShadowEnabled.collectAsStateWithLifecycle()
    val appListReturnShrinkEnabled by vm.appListReturnShrinkEnabled.collectAsStateWithLifecycle()
    val legacyAppListTransitionEnabled by vm.legacyAppListTransitionEnabled.collectAsStateWithLifecycle()
    val appListReturnIconFloatEnabled by vm.appListReturnIconFloatEnabled.collectAsStateWithLifecycle()
    val rawFaceReturnShrinkEnabled by vm.faceReturnShrinkEnabled.collectAsStateWithLifecycle()
    val reduceDynamicEffects by vm.reduceDynamicEffects.collectAsStateWithLifecycle()
    val appListStaggerEnabled by vm.appListStaggerEnabled.collectAsStateWithLifecycle()
    // 减弱动态效果开启时表盘收缩强制开启：去掉回弹后需要收缩行程承载转场层次。
    val faceReturnShrinkEnabled = rawFaceReturnShrinkEnabled || reduceDynamicEffects
    val activeMotionProfile by vm.activeMotionProfile.collectAsStateWithLifecycle()
    val showStepCount by vm.showStepCount.collectAsStateWithLifecycle()
    val showNotification by vm.showNotification.collectAsStateWithLifecycle()
    val rotaryHapticsEnabled by vm.rotaryHapticsEnabled.collectAsStateWithLifecycle()
    val showWidgetPage by vm.showWidgetPage.collectAsStateWithLifecycle()
    val showControlCenter by vm.showControlCenter.collectAsStateWithLifecycle()
    val watchFaceChargingPowerText by vm.watchFaceChargingPowerText.collectAsStateWithLifecycle()
    val watchFaceStatusIndicators by vm.watchFaceStatusIndicators.collectAsStateWithLifecycle()
    val watchFaceBottomFadeEnabled by vm.watchFaceBottomFadeEnabled.collectAsStateWithLifecycle()
    val watchmakerForceFill by vm.watchmakerForceFill.collectAsStateWithLifecycle()
    val dingDingCatFillScreen by vm.dingDingCatFillScreen.collectAsStateWithLifecycle()
    val dingDingCatPlaybackSpeedPercent by vm.dingDingCatPlaybackSpeedPercent.collectAsStateWithLifecycle()
    val doubleTapLockScreenEnabled by vm.doubleTapLockScreenEnabled.collectAsStateWithLifecycle()
    val powerMenuButtonEnabled by vm.powerMenuButtonEnabled.collectAsStateWithLifecycle()
    val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
    val notificationAccessGranted by vm.notificationAccessGranted.collectAsStateWithLifecycle()
    val sideScreenPreviewGroups by vm.sideScreenPreviewGroups.collectAsStateWithLifecycle()
    val notificationGroups by vm.notificationGroups.collectAsStateWithLifecycle()
    val revealedNotificationTarget by vm.revealedNotificationTarget.collectAsStateWithLifecycle()
    val showMusicControls by vm.showMusicControls.collectAsStateWithLifecycle()
    val showMediaCustomActions by vm.showMediaCustomActions.collectAsStateWithLifecycle()
    val swapMusicNotificationComponents by vm.swapMusicNotificationComponents.collectAsStateWithLifecycle()
    val musicTextSwitchAnimation by vm.musicTextSwitchAnimation.collectAsStateWithLifecycle()
    val launchSourceState by vm.launchSourceState.collectAsStateWithLifecycle()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsStateWithLifecycle()
    val selectedWatchFace by vm.selectedWatchFace.collectAsStateWithLifecycle()
    val watchFaceSelectionReady by vm.watchFaceSelectionReady.collectAsStateWithLifecycle()
    val watchFaceRefreshToken by vm.watchFaceRefreshToken.collectAsStateWithLifecycle()
    val watchmakerDataProvider = rememberWatchmakerWatchDataProvider(
        active = selectedWatchFace.isWatchmaker && launcherInteractive,
    )
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
    val effectiveDarkMode = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val launcherStyle = LauncherTheme.style
    val photoWatchOptions = BuiltInWatchFaceOptions(
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
    )
    val videoWatchOptions = BuiltInWatchFaceOptions(
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
    )
    val appListThemeColor = rememberAppListSeedColor(
        watchFaceId = selectedWatchFaceId,
        photoPath = builtInPhotoPath,
        videoPath = builtInVideoPath,
        photoOptions = photoWatchOptions,
        videoOptions = videoWatchOptions,
        watchFaceDescriptor = selectedWatchFace,
        enabled = false
    )
    val useAppListWatchFaceColors = false
    val appListColors = appListPalette(
        seed = appListThemeColor,
        darkMode = effectiveDarkMode,
        uiStyle = uiStyle,
        useWatchFaceColor = useAppListWatchFaceColors
    )
    val watchFaceFadeSeed = if (uiStyle == UiStyle.MATERIAL_3 && appListThemeColor.alpha <= 0.01f) {
        launcherStyle.accentColor
    } else {
        appListThemeColor
    }
    val watchFaceFadePalette = appListPalette(
        seed = watchFaceFadeSeed,
        darkMode = effectiveDarkMode,
        uiStyle = uiStyle,
        useWatchFaceColor = if (uiStyle == UiStyle.MATERIAL_3) true else useAppListWatchFaceColors
    )
    val launchBackdropSourceState =
        externalLaunchState.sessionSourceStateOrNull() ?: launchSourceState
    // The mask must sample the same palette as the layer it covers.  Using a
    // translucent black here made light-mode launches flash black and exposed
    // a second colour boundary while the list was shrinking on return.
    val launchBackdropColor = when (launchBackdropSourceState) {
        ScreenState.Apps -> appListColors.background
        else -> launcherStyle.screenBackground
    }.copy(alpha = 1f)
    val layerBlurEnabled =
        blurEnabled && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || screenState != ScreenState.App)
    val reduceLegacyDrawerEffects = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && screenState == ScreenState.App
    val notificationsEnabled = showNotification
    val notificationSceneActive = screenState == ScreenState.Notifications
    val notificationStackTint = appListColors.item
    val openWatchFaceChooser: () -> Unit = remember(context) {
        {
            vibrateHaptic(context)
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_WATCH_FACES)
                    .putExtra(EXTRA_SETTINGS_RETURN_TO_FACE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            Unit
        }
    }
    val openAccessibilitySettings: () -> Unit = remember(context) {
        {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Unit
        }
    }
    val lockScreenWithAccessibility: () -> Unit = remember(context, openAccessibilitySettings) {
        {
            if (FlueAccessibilityService.lockScreen()) {
                vibrateHaptic(context)
            } else {
                if (FlueAccessibilityService.isEnabled(context)) {
                    Toast.makeText(context, "Flue 无障碍服务连接中，请稍后重试", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "请先授权 Flue 无障碍服务", Toast.LENGTH_SHORT).show()
                    openAccessibilitySettings()
                }
            }
        }
    }
    val openPowerDialogWithAccessibility: () -> Unit = remember(context, openAccessibilitySettings) {
        {
            if (!FlueAccessibilityService.openPowerDialog()) {
                if (FlueAccessibilityService.isEnabled(context)) {
                    Toast.makeText(context, "Flue 无障碍服务连接中，请稍后重试", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "请先授权 Flue 无障碍服务", Toast.LENGTH_SHORT).show()
                    openAccessibilitySettings()
                }
            }
        }
    }
    val launchAppFromLauncher: (AppInfo, Offset, ScreenState) -> Unit = { appInfo, origin, returnState ->
        val launchDelay = if (animationOverrideEnabled) {
            activeMotionProfile.appLaunch.handoffMillis.toLong() +
                if (splashIcon) splashDelay.toLong() else 0L
        } else {
            0L
        }
        vm.openApp(appInfo, origin, launchDelay, returnState)
    }
    var prevState by remember { mutableStateOf(screenState) }
    // One low-frequency token per transition into Apps. NativeAppListView
    // owns all per-frame entry motion so Compose never drives AndroidView
    // updates at animation cadence.
    var appListEntryAnimationToken by remember { mutableStateOf(0L) }
    var appListLayerReleaseHold by remember { mutableStateOf(false) }
    // Bumped by the app-list layer's scaleBlurAlpha once its springs actually
    // reach their targets; the release effect below suspends on it.
    var appListLayerSettleCount by remember { mutableStateOf(0L) }
    var directLaunchAppListConsumed by rememberSaveable { mutableStateOf(false) }
    var directLaunchStartupWindowOpen by rememberSaveable { mutableStateOf(true) }
    var directLaunchInitialScrollResetKey by rememberSaveable { mutableStateOf(0) }
    var directLaunchInitialScrollResetPosted by rememberSaveable { mutableStateOf(false) }
    var widgetPageEditGestureLocked by rememberSaveable { mutableStateOf(false) }
    val isDirectLaunchEnteringAppList =
        directLaunchStartupWindowOpen && directLaunchAppListConsumed && screenState == ScreenState.Apps
    val isReturningFromApp = prevState == ScreenState.App && screenState == ScreenState.Apps
    val classifiedReturn =
        (externalLaunchState as? ExternalLaunchState.Returning)?.kind
    // Same predicate the icon/backdrop use, so the pivot and the icon can never
    // disagree about whether this return owns the launch origin.
    val targetReturn =
        animationOverrideEnabled && returnUsesLaunchOrigin(classifiedReturn)
    val activeLaunchUsesOrigin = animationOverrideEnabled && when (externalLaunchState) {
        is ExternalLaunchState.MaskEntering,
        is ExternalLaunchState.LaunchDispatched,
        is ExternalLaunchState.Backgrounded,
        -> true

        is ExternalLaunchState.Returning -> targetReturn
        else -> false
    }
    val isOpeningAppList =
        (prevState == ScreenState.Face && screenState == ScreenState.Apps) || isDirectLaunchEnteringAppList
    val isClosingAppListToFace = prevState == ScreenState.Apps && screenState == ScreenState.Face
    val isEnteringAppList = prevState != ScreenState.Apps && screenState == ScreenState.Apps
    var appListReturnOriginActive by remember {
        mutableStateOf(
            screenState == ScreenState.App &&
                launchSourceState == ScreenState.Apps &&
                activeLaunchUsesOrigin,
        )
    }
    var appListShadowsReady by remember { mutableStateOf(screenState == ScreenState.Apps) }
    // 启动/返回转场的唯一进度时钟。提前声明：pivot 保持与 acknowledge 都要
    // 等它物理收敛，而不是各自跑一个 380ms 壁钟——壁钟在 ~650ms 的 spring
    // 中途翻转 transformOrigin / externalLaunchState，就是「缩放中途直接跳到
    // 完成态」的根因。
    val launchMotionProgress = remember { Animatable(0f) }
    LaunchedEffect(screenState, launchSourceState, targetReturn, activeLaunchUsesOrigin) {
        if (launchSourceState != ScreenState.Apps) {
            appListReturnOriginActive = false
            return@LaunchedEffect
        }
        when (screenState) {
            ScreenState.App -> appListReturnOriginActive = activeLaunchUsesOrigin
            ScreenState.Apps -> {
                if (targetReturn && (appListReturnOriginActive || isReturningFromApp)) {
                    appListReturnOriginActive = true
                    // 等共享进度真正落地再放开 LaunchIcon pivot；兜底超时只为
                    // 进度信号意外丢失（新启动会取消本协程，不受影响）。
                    withTimeoutOrNull(2_000L) {
                        snapshotFlow { launchMotionProgress.value }
                            .first { it <= 0.001f }
                    }
                    appListReturnOriginActive = false
                } else {
                    appListReturnOriginActive = false
                }
            }
            else -> appListReturnOriginActive = false
        }
    }
    LaunchedEffect(
        directLaunchAppListPreferenceLoaded,
        directLaunchAppListEnabled,
        directLaunchAppListConsumed,
        screenState
    ) {
        if (!directLaunchStartupWindowOpen || !directLaunchAppListPreferenceLoaded) return@LaunchedEffect
        when {
            !directLaunchAppListEnabled -> directLaunchStartupWindowOpen = false
            directLaunchAppListConsumed && screenState == ScreenState.Apps -> {
                delay(360)
                directLaunchStartupWindowOpen = false
            }
        }
    }
    LaunchedEffect(
        directLaunchAppListPreferenceLoaded,
        directLaunchAppListEnabled,
        directLaunchStartupWindowOpen,
        screenState
    ) {
        if (
            !directLaunchAppListConsumed &&
            directLaunchStartupWindowOpen &&
            directLaunchAppListPreferenceLoaded &&
            directLaunchAppListEnabled &&
            screenState == ScreenState.Face
        ) {
            vm.setState(ScreenState.Apps)
            directLaunchAppListConsumed = true
        }
    }
    LaunchedEffect(directLaunchAppListConsumed, screenState, apps.size) {
        if (
            directLaunchAppListConsumed &&
            !directLaunchInitialScrollResetPosted &&
            screenState == ScreenState.Apps
        ) {
            // Let Apps become active before issuing the one-shot top reset; otherwise
            // direct launch can sample fish-eye positions from the hidden first frame.
            delay(96)
            directLaunchInitialScrollResetKey += 1
            directLaunchInitialScrollResetPosted = true
        }
    }
    // Layer release keys off the *actual* animation settle, not a wall-clock
    // guess. The old `delay(durationMillis)` release was wrong twice over:
    // Spring phases ignore durationMillis entirely (a 340ms guess against a
    // ~650ms spring demoted the layer's z-order while the motion was ~90%
    // done — the "snaps into place just before it lands" artifact), and the
    // enter direction had no suspension at all, so isOpeningAppList flipped on
    // the second frame and forced a full native re-style mid-transition.
    LaunchedEffect(screenState) {
        val previousState = prevState
        if (previousState == screenState) return@LaunchedEffect
        if (previousState != ScreenState.Apps && screenState != ScreenState.Apps) {
            // The app-list layer is not part of this transition; nothing to hold.
            appListLayerReleaseHold = false
            prevState = screenState
            return@LaunchedEffect
        }
        appListLayerReleaseHold =
            previousState == ScreenState.Apps && screenState != ScreenState.Apps
        val settlesSeen = appListLayerSettleCount
        try {
            // The 2.5s cap is only a safety net for a settle signal that never
            // arrives (e.g. the layer being disposed mid-flight); in the normal
            // case the snapshotFlow completes as soon as the springs land.
            withTimeoutOrNull(2_500L) {
                snapshotFlow { appListLayerSettleCount }.first { it != settlesSeen }
            }
        } finally {
            appListLayerReleaseHold = false
            prevState = screenState
        }
    }
    // 独立记录上一个状态：prevState 由释放协程在 settle 后才更新，直接读它
    // 判断入场来源会踩到时序竞态。
    var entryTokenLastState by remember { mutableStateOf(screenState) }
    LaunchedEffect(screenState) {
        val cameFrom = entryTokenLastState
        entryTokenLastState = screenState
        if (screenState != ScreenState.Apps) return@LaunchedEffect
        // 从应用返回时默认不重播逐行上浮入场——重播会把 runtimeEntryProgress
        // 打回 0，所有行先整体消失再逐行浮起（「刚退回时上下的图标直接不见」）。
        // 只有用户显式打开「返回时图标上浮」开关才重播。
        if (cameFrom == ScreenState.App && !appListReturnIconFloatEnabled) {
            return@LaunchedEffect
        }
        appListEntryAnimationToken = if (appListEntryAnimationToken == Long.MAX_VALUE) {
            0L
        } else {
            appListEntryAnimationToken + 1L
        }
    }
    LaunchedEffect(screenState, iconShadowEnabled) {
        if (!iconShadowEnabled || screenState != ScreenState.Apps) {
            appListShadowsReady = false
            return@LaunchedEffect
        }
        appListShadowsReady = false
        val shadowDelayMs = when {
            isReturningFromApp -> activeMotionProfile.appReturn.handoffMillis.toLong()
            isEnteringAppList -> activeMotionProfile.appListEnter.handoffMillis.toLong()
            else -> 0L
        }
        if (shadowDelayMs > 0L) {
            delay(shadowDelayMs)
        }
        appListShadowsReady = true
    }
    val launcherScope = rememberCoroutineScope()
    var retainedLaunchIcon by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var retainedLaunchOrigin by remember { mutableStateOf<Offset?>(null) }
    // Capture the source before the ViewModel clears currentLaunchIcon while
    // resolving a return.  The visual layer owns this short-lived snapshot;
    // it is never used as evidence for return classification.
    LaunchedEffect(currentLaunchIcon, appOpenOrigin) {
        if (currentLaunchIcon != null) {
            retainedLaunchIcon = currentLaunchIcon
            retainedLaunchOrigin = appOpenOrigin
        }
    }
    LaunchedEffect(externalLaunchState) {
        val terminalSessionId = when (val state = externalLaunchState) {
            is ExternalLaunchState.Returning -> state.session.id
            is ExternalLaunchState.Failed -> state.session.id
            is ExternalLaunchState.Cancelled -> state.session.id
            is ExternalLaunchState.Invalidated -> state.session?.id
            else -> null
        } ?: return@LaunchedEffect

        val keepIconForReverse = when (val state = externalLaunchState) {
            is ExternalLaunchState.Returning -> returnUsesLaunchOrigin(state.kind)
            // A launch cancelled before dispatch can still be visibly part way
            // through the handoff.  Let the same clock reverse instead of
            // dropping the icon on the first cancelled frame.  Stack-sourced
            // launches (负一屏 dock) reverse on the same clock — only accepting
            // Apps here made the icon blink out when a dock launch was
            // interrupted.
            is ExternalLaunchState.Cancelled ->
                state.session.sourceState == ScreenState.Apps ||
                    state.session.sourceState == ScreenState.Stack
            // A transparent/dialog-style target only pauses us, so the
            // background confirmation times out while the mask is still fully
            // drawn.  Dropping the icon there made it blink out and leave a bare
            // colour plate; reverse the same clock instead.
            is ExternalLaunchState.Invalidated ->
                state.session?.sourceState == ScreenState.Apps ||
                    state.session?.sourceState == ScreenState.Stack
            is ExternalLaunchState.Failed ->
                state.session.sourceState == ScreenState.Apps ||
                    state.session.sourceState == ScreenState.Stack
            else -> false
        }
        if (!keepIconForReverse) {
            retainedLaunchIcon = null
            retainedLaunchOrigin = null
        } else {
            // acknowledge 一旦发出，externalLaunchState → Idle 会立刻拔掉
            // targetReturn / showLaunchBackdrop。必须等共享进度物理归零，
            // 否则遮罩在 spring 中途被硬删——返回动画「最后一帧抽帧」。
            withTimeoutOrNull(2_500L) {
                snapshotFlow { launchMotionProgress.value }.first { it <= 0.001f }
            }
        }
        vm.acknowledgeExternalLaunch(terminalSessionId)
    }
    val sessionLaunchOrigin = externalLaunchState.sessionOriginOrNull()
    val effectiveLaunchOrigin =
        sessionLaunchOrigin ?: retainedLaunchOrigin ?: appOpenOrigin
    val notificationTransition = remember { Animatable(if (notificationSceneActive) 1f else 0f) }
    var notificationGestureActive by remember { mutableStateOf(false) }
    var notificationTargetProgress by remember { mutableFloatStateOf(if (notificationSceneActive) 1f else 0f) }
    var notificationReturnState by remember { mutableStateOf(ScreenState.Stack) }
    val backPressRequests by vm.backPressRequests.collectAsStateWithLifecycle()
    val homePressRequests by vm.homePressRequests.collectAsStateWithLifecycle()

    val appListTransformOrigin = when {
        launchSourceState != ScreenState.Apps -> LayerPivot.Center
        screenState == ScreenState.App && activeLaunchUsesOrigin ->
            LayerPivot.LaunchIcon(effectiveLaunchOrigin)
        targetReturn && appListReturnOriginActive ->
            LayerPivot.LaunchIcon(effectiveLaunchOrigin)
        else -> LayerPivot.Center
    }
    val appListIconReturnActive =
        targetReturn &&
        launchSourceState == ScreenState.Apps &&
            screenState == ScreenState.Apps &&
            (isReturningFromApp || appListReturnOriginActive)
    val appListLayerOrigin = if (isOpeningAppList || isClosingAppListToFace) {
        LayerPivot.Center
    } else if (appListIconReturnActive) {
        LayerPivot.LaunchIcon(effectiveLaunchOrigin)
    } else {
        appListTransformOrigin
    }
    val activeAppListBackend = appListBackend(layoutMode, uiStyle)
    val appListMotionPhase = when {
        isReturningFromApp || appListIconReturnActive -> activeMotionProfile.appReturn
        // 关闭回表盘就是进入动画的倒放：同一条 appListEnter 谱反向跑，
        // 不再用独立的 pullToFace 档（老师指定，不额外做一套）。
        isClosingAppListToFace -> activeMotionProfile.appListEnter
        else -> activeMotionProfile.appListEnter
    }
    val appListMotionSpec = remember(appListMotionPhase, reduceDynamicEffects) {
        appListMotionPhase.asFloatAnimationSpec(allowOvershoot = !reduceDynamicEffects)
    }
    // alpha / blur 比位移慢一档，让「表盘先暗下让位、列表再模糊浮现」有先后
    // 而不是所有量一起冲完——三者共用同一条 spec 的观感就是又快又硬。
    // （老师实测点名要这版进场手感。）
    val appListAlphaSpec = remember(appListMotionPhase, reduceDynamicEffects) {
        tween<Float>(
            durationMillis = (appListMotionPhase.durationMillis * 1.35f).roundToInt(),
            easing = FastOutSlowInEasing,
        )
    }
    val appListBlurSpec = remember(appListMotionPhase, reduceDynamicEffects) {
        tween<Float>(
            durationMillis = (appListMotionPhase.durationMillis * 1.5f).roundToInt(),
            easing = FastOutSlowInEasing,
        )
    }
    // The launch mask now has one scalar clock.  Keeping it independent from
    // AnimatedVisibility means a return can be cancelled without snapping the
    // icon or backdrop to a fresh enter transition.
    val launchTransitionUnderlying = false
    val nativeListTransitionInMotion =
        isOpeningAppList ||
            isClosingAppListToFace ||
            isReturningFromApp ||
            appListIconReturnActive ||
            appListReturnOriginActive
    // API 31+ 的 RenderEffect 走硬件混合，转场期间保持模糊正是 watchOS 式
    // 「表盘压暗模糊让位」的主体；只在老设备上为省 GPU 关闭。以前一刀切
    // 关闭的结果是：转场里逻辑模糊只剩时钟、画面上完全没有模糊，settle 帧
    // 又把 RenderEffect 全量装回——「最后一帧集中弹入」的来源之一。
    val transitionBlurSuppressed =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            (launchTransitionUnderlying || nativeListTransitionInMotion)
    val faceLayerBlurEnabled = layerBlurEnabled && !transitionBlurSuppressed
    val appListLayerBlurEnabled = layerBlurEnabled && !transitionBlurSuppressed
    val effectiveIconShadowEnabled = iconShadowEnabled && appListShadowsReady
    val fadeLaunch = launchSourceState == ScreenState.Stack || launchSourceState == ScreenState.Notifications
    val iconLaunchAvailable = currentLaunchIcon != null || retainedLaunchIcon != null
    val iconlessFadeLaunch = fadeLaunch && !iconLaunchAvailable
    val effectiveLaunchIcon = currentLaunchIcon ?: retainedLaunchIcon
    val directLaunchBackdropVisible = directLaunchStartupWindowOpen && (
        !directLaunchAppListPreferenceLoaded ||
            (!directLaunchAppListConsumed && directLaunchAppListEnabled && screenState == ScreenState.Face) ||
            (directLaunchAppListConsumed && screenState == ScreenState.Apps)
        )

    LaunchedEffect(notificationSceneActive) {
        if (!notificationGestureActive) {
            notificationTargetProgress = if (notificationSceneActive) 1f else 0f
        }
    }

    LaunchedEffect(notificationTargetProgress, notificationGestureActive) {
        if (!notificationGestureActive) {
            notificationTransition.animateTo(
                notificationTargetProgress,
                tween(durationMillis = NOTIFICATION_TRANSITION_MS)
            )
        }
    }

    LaunchedEffect(sideScreenEnabled, screenState) {
        if (!sideScreenEnabled && (screenState == ScreenState.Stack || screenState == ScreenState.Notifications)) {
            vm.setState(ScreenState.Face)
        }
    }

    val launchMotionEntering =
        animationOverrideEnabled &&
            screenState == ScreenState.App &&
            (iconlessFadeLaunch || iconLaunchAvailable)
    val launchMotionReturnVisual =
        animationOverrideEnabled &&
            !launchMotionEntering &&
            effectiveLaunchIcon != null &&
            when (val state = externalLaunchState) {
                is ExternalLaunchState.Returning ->
                    returnUsesLaunchOrigin(state.kind)
                is ExternalLaunchState.Cancelled ->
                    state.session.sourceState == ScreenState.Apps ||
                        state.session.sourceState == ScreenState.Stack
                else -> false
            }
    val launchMotionRequested = launchMotionEntering || launchMotionReturnVisual
    val launchMotionTarget = if (launchMotionEntering) 1f else 0f
    LaunchedEffect(launchMotionTarget, activeMotionProfile) {
        val current = launchMotionProgress.value
        val currentVelocity = launchMotionProgress.velocity
        val directedVelocity = when {
            launchMotionTarget > current -> abs(currentVelocity)
            launchMotionTarget < current -> -abs(currentVelocity)
            else -> 0f
        }
        val spec = if (launchMotionTarget > current) {
            activeMotionProfile.appLaunch.asFloatAnimationSpec()
        } else {
            activeMotionProfile.appReturn.asFloatAnimationSpec()
        }
        // spring 默认 1% 可见阈值会在终点直接吸附：列表 App 态 4 倍缩放
        // 行程下，1% 进度 = 0.03 的 scale 突变——「差最后一点直接跳过去」，
        // 动画越慢越明显。收紧到 0.02% 让物理真正走完。
        val preciseSpec = (spec as? androidx.compose.animation.core.SpringSpec<Float>)?.let {
            spring(
                dampingRatio = it.dampingRatio,
                stiffness = it.stiffness,
                visibilityThreshold = 0.0002f,
            )
        } ?: spec
        launchMotionProgress.animateTo(
            targetValue = launchMotionTarget,
            animationSpec = preciseSpec,
            initialVelocity = directedVelocity,
        )
        if (launchMotionTarget <= 0f && !launchMotionEntering) {
            // A new launch cancels this coroutine before the snapshot is
            // released, so an interrupted return can immediately reverse.
            retainedLaunchIcon = null
            retainedLaunchOrigin = null
        }
    }
    val showLaunchBackdrop = launchMotionRequested || launchMotionProgress.value > 0.001f
    // 启动/返回期间遮罩、列表层、底色板、边缘罩共用 launchMotionProgress 这
    // 一个时钟（graphicsLayer 逐帧读，零重组）。p=0 时 provider 精确回到
    // Apps 静止态，外部时钟撤走零跳变。
    val appListLaunchDriven =
        showLaunchBackdrop && launchSourceState == ScreenState.Apps
    val appListLaunchValuesProvider = remember(uiStyle, appListReturnShrinkEnabled) {
        {
            appListLaunchValues(
                progress = launchMotionProgress.value,
                uiStyle = uiStyle,
                shrinkOnAppReturn = appListReturnShrinkEnabled,
            )
        }
    }
    val appListLaunchPlateProvider = remember {
        { appListLaunchPlateValues(launchMotionProgress.value) }
    }
    // The launch dispatch delay keeps the splash on screen long enough; it
    // must not also delay the source icon until the last few frames.
    val iconRevealFraction = 0.06f
    val iconProgress = if (launchMotionTarget > 0.5f) {
        if (splashIcon) {
            ((launchMotionProgress.value - iconRevealFraction) /
                (1f - iconRevealFraction)).coerceIn(0f, 1f)
        } else {
            0f
        }
    } else {
        launchMotionProgress.value.coerceIn(0f, 1f)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(launcherStyle.screenBackground)
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val notificationProgress = notificationTransition.value.coerceIn(0f, 1f)
        val keepNotificationLayer = notificationProgress > 0.001f || notificationSceneActive || notificationGestureActive
        // 覆层进度改 Animatable：打开侧由 GestureHost 逐帧 snapTo（全程跟手），
        // 抬手按位置+投影速度定向，settle 用带初速度的 spring 收尾。以前的
        // 「80px 阈值 → 固定 tween」既不跟手、拖一半松手还会弹到底。
        val sidePageAnim = remember { Animatable(0f) }
        val widgetPageAnim = remember { Animatable(0f) }
        val controlCenterAnim = remember { Animatable(0f) }
        var overlayGestureTarget by remember { mutableStateOf<OverlayGestureTarget?>(null) }
        var overlayReleaseVelocity by remember { mutableFloatStateOf(0f) }
        val overlaySettleSpec = remember {
            spring<Float>(dampingRatio = 0.88f, stiffness = 380f, visibilityThreshold = 0.001f)
        }
        val sidePageTarget = if (
            sideScreenEnabled &&
            (screenState == ScreenState.Stack ||
                (screenState == ScreenState.Notifications && notificationReturnState != ScreenState.ControlCenter))
        ) 1f else 0f
        val widgetPageTarget = if (showWidgetPage && screenState == ScreenState.Widgets) 1f else 0f
        val controlCenterTarget = if (
            showControlCenter &&
            (screenState == ScreenState.ControlCenter ||
                (screenState == ScreenState.Notifications && notificationReturnState == ScreenState.ControlCenter))
        ) 1f else 0f
        LaunchedEffect(sidePageTarget, overlayGestureTarget) {
            if (overlayGestureTarget != OverlayGestureTarget.Stack) {
                val initialVelocity = overlayReleaseVelocity.also { overlayReleaseVelocity = 0f }
                sidePageAnim.animateTo(sidePageTarget, overlaySettleSpec, initialVelocity)
            }
        }
        LaunchedEffect(widgetPageTarget, overlayGestureTarget) {
            if (overlayGestureTarget != OverlayGestureTarget.Widgets) {
                val initialVelocity = overlayReleaseVelocity.also { overlayReleaseVelocity = 0f }
                widgetPageAnim.animateTo(widgetPageTarget, overlaySettleSpec, initialVelocity)
            }
        }
        LaunchedEffect(controlCenterTarget, overlayGestureTarget) {
            if (overlayGestureTarget != OverlayGestureTarget.ControlCenter) {
                val initialVelocity = overlayReleaseVelocity.also { overlayReleaseVelocity = 0f }
                controlCenterAnim.animateTo(controlCenterTarget, overlaySettleSpec, initialVelocity)
            }
        }
        val sidePageProgress = sidePageAnim.value
        val widgetPageProgress = widgetPageAnim.value
        val controlCenterProgress = controlCenterAnim.value
        // 表盘背衬统一进度：四个覆层里最强的一个驱动表盘下压与镜像外扩。
        val overlayBackdropProgress = remember {
            {
                maxOf(
                    sidePageAnim.value,
                    widgetPageAnim.value,
                    controlCenterAnim.value,
                    notificationTransition.value,
                ).coerceIn(0f, 1f)
            }
        }
        val overlayBackdropActive by remember {
            derivedStateOf {
                maxOf(
                    sidePageAnim.value,
                    widgetPageAnim.value,
                    controlCenterAnim.value,
                    notificationTransition.value,
                ) > 0.001f
            }
        }
        val faceBackdropProvider = remember {
            {
                val p = maxOf(
                    sidePageAnim.value,
                    widgetPageAnim.value,
                    controlCenterAnim.value,
                    notificationTransition.value,
                ).coerceIn(0f, 1f)
                // p=0 必须精确等于表盘静止态（外部时钟撤走零跳变）。
                // alpha 只轻降：表盘变透明会露出纯黑底、还削弱镜像背衬的
                // 亮度；压暗交给覆层玻璃自己的底色。模糊加深到 24dp。
                LayerAnimValues(
                    scale = 1f - 0.16f * p,
                    blur = 24f * p,
                    alpha = 1f - 0.15f * p,
                    translationY = 0.055f * p,
                )
            }
        }
        val faceAnimState = screenState
        val updateNotificationTransitionProgress: (Float) -> Unit = { progress ->
            notificationGestureActive = true
            launcherScope.launch {
                notificationTransition.snapTo(progress.coerceIn(0f, 1f))
            }
        }
        val settleNotificationTransition: (Float) -> Unit = { target ->
            notificationGestureActive = false
            notificationTargetProgress = target.coerceIn(0f, 1f)
        }
        val openNotificationsFrom: (ScreenState) -> Unit = { origin ->
            notificationReturnState = origin
            vm.collapseNotificationGroups()
            vm.setRevealedNotificationTarget(null)
            vm.setState(ScreenState.Notifications)
            settleNotificationTransition(1f)
        }
        val closeNotificationsToOrigin: () -> Unit = {
            vm.setState(
                when (notificationReturnState) {
                    ScreenState.ControlCenter -> ScreenState.ControlCenter
                    ScreenState.Stack,
                    ScreenState.Notifications -> if (sideScreenEnabled) ScreenState.Stack else ScreenState.Face
                    else -> notificationReturnState
                }
            )
            settleNotificationTransition(0f)
        }
        LaunchedEffect(backPressRequests) {
            if (backPressRequests > 0L) {
                if (screenState == ScreenState.Notifications) {
                    closeNotificationsToOrigin()
                } else {
                    vm.handleBackPress()
                }
            }
        }
        LaunchedEffect(homePressRequests) {
            if (homePressRequests > 0L) {
                if (screenState == ScreenState.Notifications) {
                    closeNotificationsToOrigin()
                } else {
                    vm.handleHomePress()
                }
            }
        }
        val sideScreenVisible = sidePageProgress > 0.001f || screenState == ScreenState.Stack || screenState == ScreenState.Notifications
        val widgetPageVisible = widgetPageProgress > 0.001f || screenState == ScreenState.Widgets
        val controlCenterVisible = controlCenterProgress > 0.001f || screenState == ScreenState.ControlCenter
        val sideSceneOverlayActive = sideScreenVisible || widgetPageVisible || controlCenterVisible
        val appListLayerRaised = screenState == ScreenState.Apps ||
            isOpeningAppList ||
            isClosingAppListToFace ||
            isReturningFromApp ||
            appListLayerReleaseHold ||
            (screenState == ScreenState.App && launchSourceState == ScreenState.Apps && showLaunchBackdrop)
        // API 31+ 不再在转场期间降档行级模糊：settle 帧把 OFF→SYSTEM 的
        // RenderEffect 全量装回（applyConfigToAttachedRows 逐行 reapply）正是
        // 「打开/返回最后一帧图标模糊集中弹入 + 抽帧」的主力。老设备保留降档。
        val suppressAppListHeavyEffects =
            reduceLegacyDrawerEffects ||
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S && (
                        isOpeningAppList ||
                            isClosingAppListToFace ||
                            isReturningFromApp ||
                            appListIconReturnActive ||
                            appListReturnOriginActive ||
                            appListLayerReleaseHold
                        )
                    )

        GestureHost(
            screenState = screenState,
            onStateChange = { vm.setState(it) },
            sideScreenEnabled = sideScreenEnabled,
            showNotification = false,
            showWidgetPage = showWidgetPage,
            showControlCenter = showControlCenter,
            widgetsBackGestureLocked = widgetPageEditGestureLocked,
            onOverlayDrag = { target, progress ->
                overlayGestureTarget = target
                launcherScope.launch {
                    when (target) {
                        OverlayGestureTarget.Stack -> sidePageAnim.snapTo(progress)
                        OverlayGestureTarget.Widgets -> widgetPageAnim.snapTo(progress)
                        OverlayGestureTarget.ControlCenter -> controlCenterAnim.snapTo(progress)
                    }
                }
            },
            onOverlayRelease = { target, progress, velocity ->
                // 位置 + 投影速度共同定向：拖过 42% 或快甩都算开。
                val open = progress > 0.42f || velocity > 1.6f
                overlayReleaseVelocity = velocity
                overlayGestureTarget = null
                if (open) {
                    when (target) {
                        OverlayGestureTarget.Stack -> vm.setState(ScreenState.Stack)
                        OverlayGestureTarget.Widgets -> vm.setState(ScreenState.Widgets)
                        OverlayGestureTarget.ControlCenter -> vm.setState(ScreenState.ControlCenter)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            if (directLaunchBackdropVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (screenState == ScreenState.Face) 4f else 2f)
                        // Startup preference hydration used to paint a hard
                        // black window even in light mode.  Keep the same
                        // palette as the layer that is about to be shown.
                        .background(
                            if (screenState == ScreenState.Apps) {
                                appListColors.background
                            } else {
                                launcherStyle.screenBackground
                            },
                        )
                )
            }
            // watchOS 三段式的 B 段：表盘压暗（A）之后由这层渐变到纯黑，图标
            // 再从黑底上飞入（C）。它只在应用列表转场期间存在。
            val appListScrimTarget = if (
                !uiStyle.isMaterial &&
                !legacyAppListTransitionEnabled &&
                (screenState == ScreenState.Apps ||
                    (screenState == ScreenState.App && launchSourceState == ScreenState.Apps))
            ) 1f else 0f
            val appListScrimAlpha by animateFloatAsState(
                targetValue = appListScrimTarget,
                animationSpec = appListAlphaSpec,
                label = "app_list_scrim",
            )
            if (appListScrimAlpha > 0.001f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f)
                        .graphicsLayer { alpha = appListScrimAlpha }
                        .background(appListColors.background)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 转场未收尾（appListLayerRaised）期间表盘必须让位：立刻抢回
                    // 3f 会把还在收尾的列表层压在身下——「最后一帧闪现」的另一半。
                    .zIndex(
                        if (
                            screenState == ScreenState.Face &&
                            !sideSceneOverlayActive &&
                            !appListLayerRaised
                        ) 3f else 1f
                    )
                    .graphicsLayer {
                        clip = true
                    }
                    // clox 式背衬：覆层拉出时表盘下压缩小，让出的边缘用放大
                    // 模糊副本填充（防黑边）。
                    .flueBackdropFill(
                        progress = overlayBackdropProgress,
                        enabled = layerBlurEnabled,
                    )
                    .scaleBlurAlpha(
                        targetValues = faceLayerValues(
                            faceAnimState,
                            uiStyle,
                            shrinkOnFaceReturn = faceReturnShrinkEnabled,
                            legacyTransition = legacyAppListTransitionEnabled,
                        ),
                        screenHeight = screenHeightPx,
                        blurEnabled = faceLayerBlurEnabled,
                        origin = LayerPivot.Center,
                        valuesProvider = faceBackdropProvider,
                        externallyDriven = overlayBackdropActive,
                    )
            ) {
                AnimatedContent(
                    targetState = if (
                        watchFaceSelectionReady ||
                        selectedWatchFace.isBuiltin ||
                        selectedWatchFaceId == BUILT_IN_WATCHFACE_ID ||
                        (
                            selectedWatchFace.id == selectedWatchFaceId &&
                                (
                                    selectedWatchFace.isDingDingCat ||
                                        selectedWatchFace.isJbWatch ||
                                        selectedWatchFace.isWatchmaker
                                    ) &&
                                !selectedWatchFace.sourceDirPath.isNullOrBlank()
                            )
                    ) {
                        selectedWatchFace.stableKey
                    } else {
                        "loading"
                    },
                    transitionSpec = {
                        fadeIn(animationSpec = launcherStyle.faceSwitchEnterSpec) togetherWith
                            fadeOut(animationSpec = launcherStyle.faceSwitchExitSpec)
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "watchface_switch"
                ) { targetKey ->
                    if (targetKey == "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        )
                    } else if (selectedWatchFace.isBuiltin) {
                        WatchFaceLayer(
                            watchFaceId = selectedWatchFace.id,
                            photoPath = builtInPhotoPath,
                            videoPath = builtInVideoPath,
                            isFaceVisible = launcherInteractive && screenState == ScreenState.Face,
                            uiStyle = uiStyle,
                            photoOptions = photoWatchOptions,
                            videoOptions = videoWatchOptions,
                            showChargingPowerText = watchFaceChargingPowerText,
                            showStatusIndicators = false,
                            showBottomFade = watchFaceBottomFadeEnabled,
                            bottomFadeColor = watchFaceFadePalette.fadeEdge,
                            bottomFadeHeightDp = honeycombBottomFade,
                            bottomFadeBlurRadiusDp = if (blurEnabled && edgeBlurEnabled) honeycombEdgeBlurRadius else 0f,
                            hasNotifications = showNotification && notificationGroups.isNotEmpty(),
                            onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive) openWatchFaceChooser else null,
                            onDoubleTap = if (
                                doubleTapLockScreenEnabled &&
                                launcherInteractive &&
                                screenState == ScreenState.Face &&
                                !sideSceneOverlayActive
                            ) lockScreenWithAccessibility else null
                        )
                    } else if (selectedWatchFace.isWatchmaker) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            WatchmakerWatchFaceHost(
                                descriptor = selectedWatchFace,
                                isFaceVisible = launcherInteractive && screenState == ScreenState.Face,
                                refreshToken = watchFaceRefreshToken,
                                onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive) openWatchFaceChooser else null,
                                onDoubleTap = if (
                                    doubleTapLockScreenEnabled &&
                                    launcherInteractive &&
                                    screenState == ScreenState.Face &&
                                    !sideSceneOverlayActive
                                ) lockScreenWithAccessibility else null,
                                onLoadFailure = { descriptor, error ->
                                    val rootCause = generateSequence(error) { it.cause }.last()
                                    vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                                },
                                dataProvider = watchmakerDataProvider,
                                forceFill = watchmakerForceFill,
                            )
                            if (watchFaceBottomFadeEnabled) {
                                WatchFaceBottomFadeOverlay(
                                    color = watchFaceFadePalette.fadeEdge,
                                    heightDp = honeycombBottomFade,
                                    blurRadiusDp = if (blurEnabled && edgeBlurEnabled) honeycombEdgeBlurRadius else 0f
                                )
                            }
                        }
                    } else if (selectedWatchFace.isDingDingCat) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            DingDingCatWatchFaceHost(
                                descriptor = selectedWatchFace,
                                isFaceVisible = launcherInteractive && screenState == ScreenState.Face,
                                refreshToken = watchFaceRefreshToken,
                                fillScreen = dingDingCatFillScreen,
                                playbackSpeedPercent = dingDingCatPlaybackSpeedPercent,
                                onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive) openWatchFaceChooser else null,
                                onDoubleTap = if (
                                    doubleTapLockScreenEnabled &&
                                    launcherInteractive &&
                                    screenState == ScreenState.Face &&
                                    !sideSceneOverlayActive
                                ) lockScreenWithAccessibility else null,
                                onLoadFailure = { descriptor, error ->
                                    val rootCause = generateSequence(error) { it.cause }.last()
                                    vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                                }
                            )
                            if (watchFaceBottomFadeEnabled) {
                                WatchFaceBottomFadeOverlay(
                                    color = watchFaceFadePalette.fadeEdge,
                                    heightDp = honeycombBottomFade,
                                    blurRadiusDp = if (blurEnabled && edgeBlurEnabled) honeycombEdgeBlurRadius else 0f
                                )
                            }
                        }
                    } else if (selectedWatchFace.isJbWatch) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            JbWatchFaceHost(
                                descriptor = selectedWatchFace,
                                isFaceVisible = launcherInteractive && screenState == ScreenState.Face,
                                refreshToken = watchFaceRefreshToken,
                                onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive) openWatchFaceChooser else null,
                                onDoubleTap = if (
                                    doubleTapLockScreenEnabled &&
                                    launcherInteractive &&
                                    screenState == ScreenState.Face &&
                                    !sideSceneOverlayActive
                                ) lockScreenWithAccessibility else null,
                                onLoadFailure = { descriptor, error ->
                                    val rootCause = generateSequence(error) { it.cause }.last()
                                    vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                                }
                            )
                            if (watchFaceBottomFadeEnabled) {
                                WatchFaceBottomFadeOverlay(
                                    color = watchFaceFadePalette.fadeEdge,
                                    heightDp = honeycombBottomFade,
                                    blurRadiusDp = if (blurEnabled && edgeBlurEnabled) honeycombEdgeBlurRadius else 0f
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LunchWatchFaceHost(
                                descriptor = selectedWatchFace,
                                isFaceVisible = launcherInteractive && screenState == ScreenState.Face,
                                refreshToken = watchFaceRefreshToken,
                                onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive) openWatchFaceChooser else null,
                                onDoubleTap = if (
                                    doubleTapLockScreenEnabled &&
                                    launcherInteractive &&
                                    screenState == ScreenState.Face &&
                                    !sideSceneOverlayActive
                                ) lockScreenWithAccessibility else null,
                                onLoadFailure = { descriptor, error ->
                                    val rootCause = generateSequence(error) { it.cause }.last()
                                    vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                                }
                            )
                            if (watchFaceBottomFadeEnabled) {
                                WatchFaceBottomFadeOverlay(
                                    color = watchFaceFadePalette.fadeEdge,
                                    heightDp = honeycombBottomFade,
                                    blurRadiusDp = if (blurEnabled && edgeBlurEnabled) honeycombEdgeBlurRadius else 0f
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (screenState == ScreenState.Face && !sideSceneOverlayActive) 3.5f else 0.5f),
                contentAlignment = Alignment.TopCenter,
            ) {
                // This sibling stays above every watch-face implementation,
                // including the native AndroidView, so face clipping/effects
                // can never cover charging and notification status.
                WatchFaceStatusIndicatorOverlay(
                    showStatusIndicators = watchFaceStatusIndicators,
                    hasNotifications = showNotification && notificationGroups.isNotEmpty(),
                    modifier = Modifier.padding(top = 18.dp),
                )
            }

            // The app list paints its own background *inside* the layer that
            // scales, so every frame where that layer is smaller than the screen
            // (Material closes to 0.88, and its App state slides in from the
            // left) used to reveal `launcherStyle.screenBackground` around it —
            // a different colour from the list's own `appListColors.background`
            // in three of the four style/theme combinations, and always
            // different once watch-face colours are on.
            //
            // This plate carries the same alpha clock but never scales or
            // translates, so whatever the shrinking list uncovers is its own
            // background rather than the launcher's.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (appListLayerRaised) 2.9f else -0.1f)
                    .scaleBlurAlpha(
                        targetValues = appListLayerValues(
                            screenState,
                            uiStyle,
                            shrinkOnAppReturn = appListReturnShrinkEnabled,
                            shrinkOnFaceReturn = faceReturnShrinkEnabled,
                            legacyTransition = legacyAppListTransitionEnabled,
                        ).copy(scale = 1f, blur = 0f, translationX = 0f, translationY = 0f),
                        blurEnabled = false,
                        origin = LayerPivot.Center,
                        scaleSpec = appListMotionSpec,
                        alphaSpec = appListAlphaSpec,
                        blurSpec = appListBlurSpec,
                        translationSpec = appListMotionSpec,
                        valuesProvider = appListLaunchPlateProvider,
                        externallyDriven = appListLaunchDriven,
                    )
                    .background(appListColors.background),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (appListLayerRaised) 3f else 0f)
                    .scaleBlurAlpha(
                        targetValues = appListLayerValues(
                            screenState,
                            uiStyle,
                            shrinkOnAppReturn = appListReturnShrinkEnabled,
                            shrinkOnFaceReturn = faceReturnShrinkEnabled,
                            legacyTransition = legacyAppListTransitionEnabled,
                        ),
                        screenWidth = screenWidthPx,
                        screenHeight = screenHeightPx,
                        blurEnabled = appListLayerBlurEnabled,
                        origin = appListLayerOrigin,
                        scaleSpec = appListMotionSpec,
                        alphaSpec = appListAlphaSpec,
                        blurSpec = appListBlurSpec,
                        translationSpec = appListMotionSpec,
                        onSettled = { appListLayerSettleCount += 1 },
                        valuesProvider = appListLaunchValuesProvider,
                        externallyDriven = appListLaunchDriven,
                    )
            ) {
                when (val backend = activeAppListBackend) {
                    AppListBackend.NATIVE_LINEAR,
                    AppListBackend.NATIVE_HONEYCOMB,
                    AppListBackend.NATIVE_DENSE_GRID -> NativeListDrawerScreen(
                        apps = apps,
                        blurEnabled = blurEnabled,
                        edgeBlurEnabled = edgeBlurEnabled,
                        suppressHeavyEffects = suppressAppListHeavyEffects,
                        topFadeRangeDp = honeycombTopFade,
                        bottomFadeRangeDp = honeycombBottomFade,
                        topBlurRadiusDp = honeycombEdgeBlurRadius,
                        bottomBlurRadiusDp = honeycombEdgeBlurRadius,
                        drawEdgeFadeMaskInNativeView = false,
                        rotaryHapticsEnabled = rotaryHapticsEnabled,
                        active = screenState == ScreenState.Apps,
                        initialScrollResetKey = directLaunchInitialScrollResetKey,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        appListScalePercent = appListScalePercent,
                        folderOpen = openFolder != null,
                        useWatchFaceColors = useAppListWatchFaceColors,
                        rowBorderEnabled = appListRowBorderEnabled,
                        allowFolderCreation = appListFoldersEnabled,
                        fisheyeEnabled = appListFisheyeEnabled,
                        fisheyeRangeRows = appListFisheyeRangeRows,
                        fisheyeStrengthPercent = appListFisheyeStrengthPercent,
                        edgeSpacingCompressionEnabled = appListEdgeSpacingCompressionEnabled,
                        twoToneIconsEnabled = twoToneIconsEnabled,
                        iconShadowEnabled = effectiveIconShadowEnabled,
                        themeColor = appListThemeColor,
                        uiStyle = uiStyle,
                        darkMode = effectiveDarkMode,
                        entryAnimationToken = appListEntryAnimationToken,
                        entryStaggerEnabled = appListStaggerEnabled,
                        presentation = when (backend) {
                            AppListBackend.NATIVE_LINEAR -> NativeAppListPresentation.LINEAR
                            AppListBackend.NATIVE_HONEYCOMB -> NativeAppListPresentation.HONEYCOMB
                            AppListBackend.NATIVE_DENSE_GRID -> NativeAppListPresentation.DENSE_GRID
                        },
                        honeycombNarrowColumns = honeycombCols,
                        onAppClick = { appInfo, origin ->
                            launchAppFromLauncher(appInfo, origin, ScreenState.Apps)
                        },
                        onReorder = vm::moveApp,
                        onCreateFolder = vm::createFolder,
                        onExcludeApp = { appInfo -> vm.setAppHidden(appInfo.componentKey, true) },
                        onRemoveShortcut = vm::removeAppListShortcut,
                        onRenameFolder = vm::renameFolder,
                        onDissolveFolder = vm::dissolveFolder,
                        onScrollToTop = { vm.setState(ScreenState.Face) }
                    )
                }
            }

            // The edge mask is deliberately a sibling of the blurred list
            // layer. Drawing it inside NativeAppListView made the parent
            // RenderEffect blur the mask and caused a flash during launch and
            // return. Keep the host mounted and animate only its alpha so a
            // state switch cannot remove it one frame too early.
            val nativeEdgeFadeVisible =
                screenState == ScreenState.Apps ||
                    isOpeningAppList ||
                    isClosingAppListToFace ||
                    appListLayerReleaseHold ||
                    (screenState == ScreenState.App &&
                        launchSourceState == ScreenState.Apps &&
                        showLaunchBackdrop)
            NativeAppListEdgeFadeOverlay(
                visible = nativeEdgeFadeVisible,
                topFadeRangeDp = honeycombTopFade,
                bottomFadeRangeDp = honeycombBottomFade,
                edgeColor = appListColors.fadeEdge,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(3.1f)
                    .scaleBlurAlpha(
                        targetValues = appListLayerValues(
                            screenState,
                            uiStyle,
                            shrinkOnAppReturn = appListReturnShrinkEnabled,
                            shrinkOnFaceReturn = faceReturnShrinkEnabled,
                            legacyTransition = legacyAppListTransitionEnabled,
                        ),
                        screenWidth = screenWidthPx,
                        screenHeight = screenHeightPx,
                        blurEnabled = false,
                        origin = appListLayerOrigin,
                        scaleSpec = appListMotionSpec,
                        alphaSpec = appListAlphaSpec,
                        blurSpec = appListBlurSpec,
                        translationSpec = appListMotionSpec,
                        valuesProvider = appListLaunchValuesProvider,
                        externallyDriven = appListLaunchDriven,
                    ),
            )

            val visibleFolder = openFolder
            if (screenState == ScreenState.Apps && visibleFolder != null) {
                val appListScale = appListScalePercent.coerceIn(50, 200) / 100f
                val folderIconShape = when {
                    layoutMode == LayoutMode.List -> RoundedCornerShape((14.dp * appListScale).coerceIn(10.dp, 24.dp))
                    uiStyle == UiStyle.MATERIAL_3 -> RoundedCornerShape((18.dp * appListScale).coerceIn(12.dp, 24.dp))
                    else -> launcherStyle.bubbleShape
                }
                AppFolderOverlay(
                    folder = visibleFolder,
                    items = openFolderItems,
                    availableItems = allSelectableApps,
                    listMode = layoutMode == LayoutMode.List,
                    iconShape = folderIconShape,
                    blurEnabled = blurEnabled,
                    twoToneIconsEnabled = twoToneIconsEnabled,
                    onAppClick = { appInfo, origin ->
                        vm.closeFolder()
                        launchAppFromLauncher(appInfo, origin, ScreenState.Apps)
                    },
                    onReorderItems = { orderedItems -> vm.reorderFolderItems(visibleFolder, orderedItems) },
                    onMoveItemOut = { item -> vm.moveItemOutOfFolder(visibleFolder, item) },
                    onSetFolderItems = { items -> vm.setFolderItems(visibleFolder, items) },
                    onRenameFolder = { name -> vm.renameFolder(visibleFolder, name) },
                    onExcludeApp = { appInfo -> vm.setAppHidden(appInfo.componentKey, true) },
                    onRemoveShortcut = vm::removeAppListShortcut,
                    onDismiss = vm::closeFolder,
                    modifier = Modifier.zIndex(5.4f)
                )
            }

            if (screenState == ScreenState.App) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        }
                )
            }

            if (showLaunchBackdrop) {
                LaunchBackdropContent(
                    progress = launchMotionProgress.value,
                    iconProgress = iconProgress,
                    entering = launchMotionTarget > 0.5f,
                    icon = effectiveLaunchIcon,
                    origin = effectiveLaunchOrigin,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    uiStyle = uiStyle,
                    backgroundColor = launchBackdropColor,
                    modifier = Modifier.zIndex(6f),
                )
            }

            if (sideScreenEnabled && sideScreenVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                        .graphicsLayer {
                            // 纯位移进场：图层自身永远不透明也不缩放。以前
                            // alpha 跟进度走，滑入全程能透过负一屏看到表盘
                            // （通知中心叠上时还被压到 0.42）。
                            translationX = (sidePageProgress - 1f) * screenWidthPx
                            alpha = if (sidePageProgress > 0.001f) 1f else 0f
                        }
                ) {
                    SmartStackLayer(
                        apps = allApps,
                        sideScreenShortcuts = sideScreenShortcuts,
                        previewGroups = sideScreenPreviewGroups,
                        showMusicCard = showMusicControls && swapMusicNotificationComponents,
                        showMediaCustomActions = showMediaCustomActions,
                        musicTextSwitchAnimation = musicTextSwitchAnimation,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        notificationsEnabled = notificationsEnabled,
                        notificationAccessGranted = notificationAccessGranted,
                        notificationsSceneActive = notificationSceneActive,
                        notificationTransitionProgress = notificationProgress,
                        revealedNotificationTarget = revealedNotificationTarget,
                        onRevealTargetChange = vm::setRevealedNotificationTarget,
                        onOpenNotifications = { openNotificationsFrom(ScreenState.Stack) },
                        onNotificationTransitionProgressChange = updateNotificationTransitionProgress,
                        onNotificationTransitionRelease = { open ->
                            if (open) {
                                openNotificationsFrom(ScreenState.Stack)
                            } else {
                                settleNotificationTransition(0f)
                            }
                        },
                        onOpenNotification = { key, origin ->
                            vm.openNotification(
                                key,
                                origin,
                                ScreenState.Stack,
                                if (animationOverrideEnabled) {
                                    activeMotionProfile.appLaunch.handoffMillis.toLong() +
                                        if (splashIcon) splashDelay.toLong() else 0L
                                } else {
                                    0L
                                }
                            )
                        },
                        onLaunchApp = { appInfo, origin ->
                            launchAppFromLauncher(appInfo, origin, ScreenState.Stack)
                        },
                        onSetShortcut = vm::setSideScreenShortcut,
                        onRemoveShortcut = vm::removeSideScreenShortcut,
                        onSwapShortcut = vm::swapSideScreenShortcuts,
                        onSetShowNotification = vm::setShowNotification,
                        onDismissGroup = vm::dismissNotificationGroup,
                        onDismissNotification = vm::dismissNotification,
                        onDismissToFace = { vm.setState(ScreenState.Face) },
                        showSteps = showStepCount,
                        stackCardColor = notificationStackTint
                    )
                }
            }

            if (showWidgetPage && widgetPageVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4.2f)
                        .graphicsLayer {
                            translationX = (1f - widgetPageProgress) * screenWidthPx
                            alpha = if (widgetPageProgress > 0.001f) 1f else 0f
                        }
                ) {
                    val sideScreenWidgetSlots by vm.sideScreenWidgetSlots.collectAsStateWithLifecycle()
                    WidgetPageLayer(
                        apps = allApps,
                        sideScreenWidgetSlots = sideScreenWidgetSlots,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        onSetWidget = vm::setWidgetSlot,
                        onSwapWidget = vm::swapWidgetSlots,
                        onRemoveWidget = vm::removeWidgetSlot,
                        onEditModeChange = { widgetPageEditGestureLocked = it }
                    )
                }
            }

            if (showControlCenter && controlCenterVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4.4f)
                        .graphicsLayer {
                            translationY = (controlCenterProgress - 1f) * screenHeightPx
                            alpha = if (controlCenterProgress > 0.001f) 1f else 0f
                        }
                ) {
                    ControlCenterLayer(
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        showMusicControls = showMusicControls && !swapMusicNotificationComponents,
                        notificationAccessGranted = notificationAccessGranted,
                        musicTextSwitchAnimation = musicTextSwitchAnimation,
                        showMediaCustomActions = showMediaCustomActions,
                        showNotificationCard = showNotification && swapMusicNotificationComponents,
                        notificationGroups = sideScreenPreviewGroups,
                        onOpenNotifications = { openNotificationsFrom(ScreenState.ControlCenter) },
                        onDismissToFace = { vm.setState(ScreenState.Face) },
                        showPowerButton = powerMenuButtonEnabled,
                        onPowerClick = openPowerDialogWithAccessibility
                    )
                }
            }

            if (notificationsEnabled && keepNotificationLayer) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(5f)
                        .graphicsLayer {
                            alpha = notificationProgress
                            translationY = (1f - notificationProgress) * screenHeightPx
                        }
                ) {
                    CompositionLocalProvider(LocalFlueMotionProfile provides activeMotionProfile) {
                    NotificationLayer(
                        isActive = notificationSceneActive || notificationGestureActive,
                        transitionProgress = notificationProgress,
                        notificationGroups = notificationGroups,
                        notificationAccessGranted = notificationAccessGranted,
                        revealedNotificationTarget = revealedNotificationTarget,
                        onRevealTargetChange = vm::setRevealedNotificationTarget,
                        onDismissToStack = closeNotificationsToOrigin,
                        onTransitionProgressChange = updateNotificationTransitionProgress,
                        onTransitionRelease = { dismiss ->
                            if (dismiss) {
                                closeNotificationsToOrigin()
                            } else {
                                settleNotificationTransition(1f)
                            }
                        },
                        onToggleGroup = vm::toggleNotificationGroup,
                        onDismissGroup = vm::dismissNotificationGroup,
                        onDismissNotification = vm::dismissNotification,
                        onDismissAllNotifications = vm::dismissAllNotifications,
                        onRunNotificationAction = vm::runNotificationAction,
                        onOpenNotification = { key, origin ->
                            vm.openNotification(
                                key,
                                origin,
                                notificationReturnState,
                                if (animationOverrideEnabled) {
                                    activeMotionProfile.appLaunch.handoffMillis.toLong() +
                                        if (splashIcon) splashDelay.toLong() else 0L
                                } else {
                                    0L
                                }
                            )
                        },
                        stackCardColor = notificationStackTint
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchBackdropContent(
    progress: Float,
    iconProgress: Float,
    entering: Boolean,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    origin: Offset,
    screenWidthPx: Float,
    screenHeightPx: Float,
    uiStyle: UiStyle,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val launcherStyle = LauncherTheme.style
    val motionProgress = progress.coerceIn(0f, 1f)
    val visibleIconProgress = iconProgress.coerceIn(0f, 1f)
    val initialIconScale = if (uiStyle == UiStyle.MATERIAL_3) 0.82f else 0.5f
    val exitIconScale = if (uiStyle == UiStyle.MATERIAL_3) 0.88f else 0.3f
    val iconScale = if (entering) {
        initialIconScale + (1f - initialIconScale) * visibleIconProgress
    } else {
        exitIconScale + (1f - exitIconScale) * visibleIconProgress
    }
    val normalizedOrigin = if (origin.x.isFinite() && origin.y.isFinite()) {
        Offset(origin.x.coerceIn(0f, 1f), origin.y.coerceIn(0f, 1f))
    } else {
        Offset(0.5f, 0.5f)
    }
    val sourceTranslationX = (normalizedOrigin.x - 0.5f) * screenWidthPx
    val sourceTranslationY = (normalizedOrigin.y - 0.5f) * screenHeightPx
    val sourceOffsetFraction = 1f - motionProgress
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // 1.6 倍斜率：p≈0.62 就完全不透明。列表层的 alpha 用 1.8 倍
                // 斜率提前淡出（appListLaunchValues），两条曲线共用同一个
                // progress——返回方向遮罩变薄时，底下列表已按同一时钟就位，
                // 不再出现「透过半透明遮罩看见 4 倍巨图标收缩然后闪现」。
                alpha = (motionProgress * 1.6f).coerceIn(0f, 1f)
            }
            .background(backgroundColor.copy(alpha = 1f)),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null && visibleIconProgress > 0.001f) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = visibleIconProgress
                        translationX = sourceTranslationX * sourceOffsetFraction
                        translationY = sourceTranslationY * sourceOffsetFraction
                    }
                    .clip(if (uiStyle == UiStyle.MATERIAL_3) launcherStyle.compactShape else CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}
