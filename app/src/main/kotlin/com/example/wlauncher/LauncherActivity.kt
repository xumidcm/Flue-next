package com.flue.launcher

import com.flue.launcher.i18n.FlueComponentActivity

import com.flue.launcher.aod.AodController
import android.view.MotionEvent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.launchsession.ExternalLaunchState
import com.flue.launcher.launchsession.ReturnKind
import com.flue.launcher.service.FlueAccessibilityService
import com.flue.launcher.service.FlueDeviceAdminController
import com.flue.launcher.service.ScreenLockControlMode
import com.flue.launcher.service.screenLockControlMode
import com.flue.launcher.watchface.standard.WatchLocationPermissionPolicy
import com.flue.launcher.watchface.standard.WatchWeatherRepository
import com.flue.launcher.ui.anim.appListLaunchPlateValues
import com.flue.launcher.ui.anim.appListLaunchValues
import com.flue.launcher.ui.anim.appListLayerValues
import com.flue.launcher.ui.anim.FlueTransitionMotion
import com.flue.launcher.ui.anim.faceLayerValues
import com.flue.launcher.ui.anim.returnUsesLaunchOrigin
import com.flue.launcher.ui.anim.scaleBlurAlpha
import com.flue.launcher.ui.anim.LayerPivot
import com.flue.launcher.motion.asFloatAnimationSpec
import com.flue.launcher.motion.LocalFlueMotionProfile
import com.flue.launcher.ui.controlcenter.ControlCenterLayer
import com.flue.launcher.ui.controlcenter.controlCenterCardPlacement
import com.flue.launcher.ui.drawer.AppFolderOverlay
import com.flue.launcher.ui.drawer.NativeListDrawerScreen
import com.flue.launcher.ui.drawer.NativeAppListEdgeFadeOverlay
import com.flue.launcher.applist.background.appListBackground
import com.flue.launcher.applist.background.appListBackgroundSolidColor
import com.flue.launcher.applist.background.rememberAppListBackgroundPainting
import com.flue.launcher.applist.nativeview.NativeAppListPresentation
import com.flue.launcher.ui.drawer.AppListBackend
import com.flue.launcher.ui.drawer.appListBackend
import com.flue.launcher.ui.drawer.appListPalette
import com.flue.launcher.ui.drawer.vibrateHaptic
import com.flue.launcher.ui.common.flueOverlayMaskShape
import com.flue.launcher.ui.home.WatchFaceLayer
import com.flue.launcher.ui.home.WatchFaceLoadingMode
import com.flue.launcher.ui.home.WatchFaceLoadingScreen
import com.flue.launcher.ui.home.WatchFaceBottomFadeOverlay
import com.flue.launcher.ui.home.WatchFaceStatusIndicatorOverlay
import com.flue.launcher.ui.home.WatchFaceCardSelector
import com.flue.launcher.ui.home.rememberAppListSeedColor
import com.flue.launcher.ui.navigation.GestureHost
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.navigation.OverlayGestureTarget
import com.flue.launcher.ui.navigation.ScreenState
import com.flue.launcher.ui.navigation.resolveOverlaySettleOpen
import com.flue.launcher.ui.navigation.resolveAppListSettleOpen
import com.flue.launcher.ui.anim.LayerAnimValues
import com.flue.launcher.ui.anim.LayerFrameSnapshot
import com.flue.launcher.ui.anim.lerpLayerAnimValues
import com.flue.launcher.ui.anim.flueBackdropFill
import com.flue.launcher.ui.anim.flueLegacyBlur
import com.flue.launcher.ui.anim.rememberLegacyOverlayBlurBackdrop
import com.flue.launcher.ui.anim.rememberLegacyWatchFaceBackdropFrames
import com.flue.launcher.ui.anim.rememberLegacyWatchFacePixelCopyFrame
import com.flue.launcher.ui.anim.shouldRenderLayerBlur
import com.flue.launcher.ui.anim.systemBlurEffectForRadiusPx
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
import com.flue.launcher.ui.style.LocalFlueOverlayActive
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.selection.WatchFaceLongPressMode
import com.flue.launcher.watchface.selection.WatchFaceChooserDestination
import com.flue.launcher.watchface.selection.WatchFaceChooserEntryPoint
import com.flue.launcher.watchface.selection.resolveWatchFaceChooserDestination
import com.flue.launcher.watchface.jbwatch.JbWatchFaceHost
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceHost
import com.flue.launcher.watchface.standard.rememberWatchmakerWatchDataProvider
import com.flue.launcher.watchface.standard.shouldFreezeWatchmakerForLauncherBackdrop
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val SIDE_SCREEN_TRANSITION_MS = 260
private const val NOTIFICATION_TRANSITION_MS = 240
private const val APP_RETURN_MAINTENANCE_DELAY_MS = 260L
private const val WATCH_FACE_LONG_PRESS_TIMEOUT_MS = 450L
private const val WIDGET_HOST_LOG_TAG = "FlueWidgetHost"

/**
 * 加载页出现后多久开始预热负一屏。
 *
 * 冷启动前几百毫秒 IO 全被表盘扫描和偏好读取占着，这时候去膨胀 RemoteViews
 * 只会互相抢资源、把加载本身拖得更慢。让它们先跑一段。
 */
private const val SIDE_PAGE_PREWARM_DELAY_MS = 300L

/**
 * 渲染等待覆层的硬揭开时限。
 *
 * 渲染器自己已经有一道保底（`WATCH_RENDER_READY_MAX_HOLD_MS`，4 秒），这里比它宽
 * 一点当第二道网：就绪信号万一因为某条没想到的路径永远不来（比如表盘还没被布局
 * 就被切走），也绝不能让用户一直盯着转圈。
 */
private const val FACE_RENDER_GATE_MAX_HOLD_MS = 6_000L

internal fun notificationUnderlayBlurProgress(
    notificationReturnState: ScreenState,
    underlayState: ScreenState,
    transitionProgress: Float,
): Float {
    if (
        underlayState != ScreenState.Stack &&
        underlayState != ScreenState.ControlCenter
    ) return 0f
    if (notificationReturnState != underlayState) return 0f
    return transitionProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
}

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

class LauncherActivity : FlueComponentActivity() {

    private val vm: LauncherViewModel by viewModels()
    private val widgetHostLifecycle by lazy {
        FlueApplication.repositories(this).widgetHostLifecycle
    }
    private var deferredResumeMaintenance: Runnable? = null
    private lateinit var aodController: AodController
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::aodController.isInitialized && aodController.onTouchEvent(event)) return true
        return super.dispatchTouchEvent(event)
    }
    override fun onDestroy() {
        if (::aodController.isInitialized) aodController.stop()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.onLauncherCreated(
            restored = savedInstanceState != null,
            homeIntent = intent.isLauncherHomeIntent(),
        )
        aodController = AodController(this, onCollapseOverlays = { vm.setState(ScreenState.Face) }, onWillForeground = vm::onLauncherAodForeground)
        aodController.start()
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
                    LauncherScreen(vm, aodController)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != WatchLocationPermissionPolicy.REQUEST_CODE) return
        if (grantResults.none { it == PackageManager.PERMISSION_GRANTED }) return

        // The repository may have just recorded a permission-less refresh attempt. Force a new
        // location/weather pass now instead of waiting for its 15-minute soft TTL.
        WatchWeatherRepository.default(this).requestRefresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(AodController.EXTRA_AOD_FOREGROUND, false)) {
            vm.onLauncherAodForeground()
        } else if (intent.isLauncherHomeIntent()) {
            vm.onLauncherHomeIntent()
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgetHostLifecycle.acquire(this) }
            .onFailure { error ->
                Log.e(WIDGET_HOST_LOG_TAG, "Unable to start AppWidgetHost listening", error)
            }
    }

    override fun onResume() {
        super.onResume()
        RecentsVisibility.apply(this)
        aodController.onResume()
        vm.setLauncherInteractive(true)
        vm.onLauncherResumed()
        // 从表盘编辑器返回时，磁盘上的 watch.xml / 资产已更新，但 ViewModel
        // 缓存的 descriptor 和 refreshToken 还是旧值，导致 WatchmakerWatchFaceView
        // 命中"same key + same token"早返回，不重载。
        // force = true 绕过25s节流，重扫并 bump token；view 走 KEEP_CURRENT_UNTIL_READY
        // 路径（同 face ID），旧帧持续显示直到新内容就绪，不会出现黑帧。
        vm.refreshWatchFaces(force = true)
        scheduleDeferredResumeMaintenance()
    }

    override fun onPause() {
        super.onPause()
        deferredResumeMaintenance?.let(window.decorView::removeCallbacks)
        deferredResumeMaintenance = null
        aodController.onPause()
        vm.setLauncherInteractive(false)
        vm.onLauncherPaused()
    }

    override fun onStop() {
        runCatching { widgetHostLifecycle.release(this) }
            .onFailure { error ->
                Log.e(WIDGET_HOST_LOG_TAG, "Unable to stop AppWidgetHost listening", error)
            }
        vm.onLauncherStopped()
        super.onStop()
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
fun LauncherScreen(vm: LauncherViewModel, aodController: AodController? = null) {
    val ambientMode = aodController?.ambientMode == true
    val aodGestureLocked = aodController?.gesturesLocked == true
    // 转场动画风格：**独立于界面风格**的一项设置（平移 / 缩放）。
    val transitionMotion by vm.transitionMotion.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val screenState by vm.screenState.collectAsStateWithLifecycle()
    val launcherInteractive by vm.launcherInteractive.collectAsStateWithLifecycle()
    val layoutMode by vm.layoutMode.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val sideScreenEnabled by vm.sideScreenEnabled.collectAsStateWithLifecycle()
    val sideScreenShortcuts by vm.sideScreenShortcuts.collectAsStateWithLifecycle()
    val sideScreenShortcutRows by vm.sideScreenShortcutRows.collectAsStateWithLifecycle()
    val sideScreenShortcutCols by vm.sideScreenShortcutCols.collectAsStateWithLifecycle()
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
    val appListFlowCrescentBulgePercent by vm.appListFlowCrescentBulgePercent.collectAsStateWithLifecycle()
    val appListFlowCrescentToRight by vm.appListFlowCrescentToRight.collectAsStateWithLifecycle()
    val activeLayoutProfile by vm.activeLayoutProfile.collectAsStateWithLifecycle()
    val appListScalePercent by vm.appListScalePercent.collectAsStateWithLifecycle()
    val overlayMaskCornerRadiusDp by vm.overlayMaskCornerRadiusDp.collectAsStateWithLifecycle()
    val appListRowBorderEnabled by vm.appListRowBorderEnabled.collectAsStateWithLifecycle()
    val appListGridIconPlateEnabled by vm.appListGridIconPlateEnabled.collectAsStateWithLifecycle()
    val appListFoldersEnabled by vm.appListFoldersEnabled.collectAsStateWithLifecycle()
    val appListScrollIndicatorEnabled by
        vm.appListScrollIndicatorEnabled.collectAsStateWithLifecycle()
    val iconShadowEnabled by vm.iconShadowEnabled.collectAsStateWithLifecycle()
    val appListReturnShrinkEnabled by vm.appListReturnShrinkEnabled.collectAsStateWithLifecycle()
    val legacyAppListTransitionEnabled by vm.legacyAppListTransitionEnabled.collectAsStateWithLifecycle()
    val appListReturnIconFloatEnabled by vm.appListReturnIconFloatEnabled.collectAsStateWithLifecycle()
    val reduceDynamicEffects by vm.reduceDynamicEffects.collectAsStateWithLifecycle()
    val appListStaggerEnabled by vm.appListStaggerEnabled.collectAsStateWithLifecycle()
    // 「减弱动态效果」与「表盘收缩动画」是两个独立开关，不再互相拉扯。
    // 以前这里是 raw || reduceDynamicEffects：开了减弱动态效果就强制收缩，
    // 用户关不掉。减弱动态效果现在只负责它自己那件事（去掉越界回弹，
    // 见 appListMotionSpec 的 allowOvershoot）。
    val faceReturnShrinkEnabled by vm.faceReturnShrinkEnabled.collectAsStateWithLifecycle()
    val activeMotionProfile by vm.activeMotionProfile.collectAsStateWithLifecycle()
    val showStepCount by vm.showStepCount.collectAsStateWithLifecycle()
    val showNotification by vm.showNotification.collectAsStateWithLifecycle()
    val rotaryHapticsEnabled by vm.rotaryHapticsEnabled.collectAsStateWithLifecycle()
    val showWidgetPage by vm.showWidgetPage.collectAsStateWithLifecycle()
    val widgetGlassHighlightEnabled by vm.widgetGlassHighlightEnabled.collectAsStateWithLifecycle()
    val showControlCenter by vm.showControlCenter.collectAsStateWithLifecycle()
    val watchFaceChargingPowerText by vm.watchFaceChargingPowerText.collectAsStateWithLifecycle()
    val watchFaceTextEntryAnimation by vm.watchFaceTextEntryAnimation.collectAsStateWithLifecycle()
    val watchFaceTextExitAnimation by vm.watchFaceTextExitAnimation.collectAsStateWithLifecycle()
    val watchFaceStatusIndicators by vm.watchFaceStatusIndicators.collectAsStateWithLifecycle()
    val watchFaceDndIndicator by vm.watchFaceDndIndicator.collectAsStateWithLifecycle()
    val watchFaceBottomFadeEnabled by vm.watchFaceBottomFadeEnabled.collectAsStateWithLifecycle()
    val watchfaceGravityParallaxEnabled by vm.watchfaceGravityParallaxEnabled.collectAsStateWithLifecycle()
    val watchfaceGravityBackdropBlurEnabled by
        vm.watchfaceGravityBackdropBlurEnabled.collectAsStateWithLifecycle()
    val doubleTapLockScreenEnabled by vm.doubleTapLockScreenEnabled.collectAsStateWithLifecycle()
    val faceIgnoresBackKey by vm.faceIgnoresBackKey.collectAsStateWithLifecycle()
    val powerMenuButtonEnabled by vm.powerMenuButtonEnabled.collectAsStateWithLifecycle()
    val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
    val notificationAccessGranted by vm.notificationAccessGranted.collectAsStateWithLifecycle()
    val sideScreenPreviewGroups by vm.sideScreenPreviewGroups.collectAsStateWithLifecycle()
    val notificationGroups by vm.notificationGroups.collectAsStateWithLifecycle()
    val revealedNotificationTarget by vm.revealedNotificationTarget.collectAsStateWithLifecycle()
    val showMusicControls by vm.showMusicControls.collectAsStateWithLifecycle()
    val showMediaCustomActions by vm.showMediaCustomActions.collectAsStateWithLifecycle()
    val swapMusicNotificationComponents by vm.swapMusicNotificationComponents.collectAsStateWithLifecycle()
    val cardPlacement = controlCenterCardPlacement(
        showMusic = showMusicControls,
        showNotifications = showNotification,
        swapped = swapMusicNotificationComponents,
    )
    val musicTextSwitchAnimation by vm.musicTextSwitchAnimation.collectAsStateWithLifecycle()
    val glassHighlightEnabled by vm.glassHighlightEnabled.collectAsStateWithLifecycle()
    val launchSourceState by vm.launchSourceState.collectAsStateWithLifecycle()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsStateWithLifecycle()
    val selectedWatchFace by vm.selectedWatchFace.collectAsStateWithLifecycle()
    val availableWatchFaces by vm.availableWatchFaces.collectAsStateWithLifecycle()
    val watchFaceLongPressMode by vm.watchFaceLongPressMode.collectAsStateWithLifecycle()
    var watchFaceCardsVisible by rememberSaveable { mutableStateOf(false) }
    val promptOverlayActive = LocalFlueOverlayActive.current
    val watchfaceCanvasDefault by vm.watchfaceCanvasDefault.collectAsStateWithLifecycle()
    val watchfaceCanvasOverrides by vm.watchfaceCanvasOverrides.collectAsStateWithLifecycle()
    // 三层继承在这里落地：每表盘覆盖优先，缺省用 App 默认。
    val watchfaceCanvasSettings = watchfaceCanvasOverrides[selectedWatchFaceId] ?: watchfaceCanvasDefault
    val watchFaceSelectionReady by vm.watchFaceSelectionReady.collectAsStateWithLifecycle()
    val watchFaceRefreshToken by vm.watchFaceRefreshToken.collectAsStateWithLifecycle()
    val watchmakerDataProvider = rememberWatchmakerWatchDataProvider(
        active = selectedWatchFace.isWatchmaker && launcherInteractive &&
            !watchFaceCardsVisible && !promptOverlayActive,
        ambientMode = ambientMode,
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
    // PixelCopy 的缓存身份必须包含所有会改变桌面实际合成帧的设置。只使用
    // descriptor/refreshToken 会让内置表盘选项变化后仍沿用上一张预制模糊。
    val legacyWatchFaceVisualKey = listOf(
        ambientMode,
        selectedWatchFace.stableKey,
        selectedWatchFaceId,
        watchFaceRefreshToken,
        builtInPhotoPath,
        builtInVideoPath,
        photoWatchOptions,
        videoWatchOptions,
        uiStyle,
        effectiveDarkMode,
        watchFaceChargingPowerText,
        watchFaceTextEntryAnimation,
        watchFaceStatusIndicators,
        watchFaceBottomFadeEnabled,
        watchfaceCanvasSettings,
        watchfaceGravityParallaxEnabled,
        // 模糊底图开关直接改合成帧（关掉后底下变纯背景色），必须进缓存身份，
        // 否则切换后桌面还在用上一张预制模糊。
        watchfaceGravityBackdropBlurEnabled,
        honeycombBottomFade,
        honeycombEdgeBlurRadius,
        edgeBlurEnabled,
    ).joinToString(separator = "|")
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
    val appListBackgroundConfig by vm.appListBackground.collectAsStateWithLifecycle()
    /** 背景的纯色底。启动遮罩与直启底板都读它，自定义纯色档才不会在转场里闪一下系统黑白。 */
    val appListSolidBackgroundColor =
        appListBackgroundSolidColor(appListBackgroundConfig, appListColors.background)
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
        ScreenState.Apps -> appListSolidBackgroundColor
        else -> launcherStyle.screenBackground
    }.copy(alpha = 1f)
    val layerBlurEnabled =
        blurEnabled && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || screenState != ScreenState.App)
    val reduceLegacyDrawerEffects = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && screenState == ScreenState.App
    val notificationsEnabled = showNotification
    val notificationSceneActive = screenState == ScreenState.Notifications
    val openWatchFaceChooser: () -> Unit = remember(context, watchFaceLongPressMode) {
        {
            vibrateHaptic(context)
            if (
                resolveWatchFaceChooserDestination(
                    WatchFaceChooserEntryPoint.LONG_PRESS,
                    watchFaceLongPressMode,
                ) == WatchFaceChooserDestination.CARDS
            ) {
                watchFaceCardsVisible = true
            } else {
                context.startActivity(
                    Intent(context, SettingsActivity::class.java)
                        .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_WATCH_FACES)
                        .putExtra(EXTRA_SETTINGS_RETURN_TO_FACE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            Unit
        }
    }
    val openAccessibilitySettings: () -> Unit = remember(context) {
        {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Unit
        }
    }
    val openDeviceAdminSettings: () -> Unit = remember(context) {
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
        }
    }
    val lockScreenWithPlatform: () -> Unit = remember(
        context,
        openAccessibilitySettings,
        openDeviceAdminSettings,
    ) {
        {
            if (aodController?.enterAodNow() == true) {
                vibrateHaptic(context)
            } else if (screenLockControlMode(Build.VERSION.SDK_INT) == ScreenLockControlMode.DEVICE_ADMIN) {
                if (FlueDeviceAdminController.lockScreen(context)) {
                    vibrateHaptic(context)
                } else {
                    openDeviceAdminSettings()
                }
            } else {
                if (FlueAccessibilityService.lockScreen()) {
                    vibrateHaptic(context)
                } else {
                    if (FlueAccessibilityService.isEnabled(context)) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_accessibility_connecting),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_grant_accessibility),
                            Toast.LENGTH_SHORT,
                        ).show()
                        openAccessibilitySettings()
                    }
                }
            }
        }
    }
    val openPowerDialogWithAccessibility: () -> Unit = remember(context, openAccessibilitySettings) {
        {
            if (!FlueAccessibilityService.openPowerDialog()) {
                if (FlueAccessibilityService.isEnabled(context)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_accessibility_connecting),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_grant_accessibility),
                        Toast.LENGTH_SHORT,
                    ).show()
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
    var suppressNextAppListEntryAnimation by remember { mutableStateOf(false) }
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
    // 同步版的「上一帧屏幕状态」。
    //
    // prevState 由释放协程在动画 settle 之后才回写（见下面那个 LaunchedEffect
    // 的 finally），所以它天生滞后。zIndex 判定不能只读它：进列表途中反向
    // 滑回表盘的那一帧，screenState 已经是 Face，而 prevState 还停在 Face，
    // isOpeningAppList 与 isClosingAppListToFace 同时为假、appListLayerReleaseHold
    // 也还没置位，appListLayerRaised 于是整个塌成 false——列表层 zIndex 从
    // 3f 掉到 0f 钻到表盘背后，下一帧又抬回来，这就是「进入应用列表时打断
    // 会闪一下」。
    //
    // SideEffect 在每次成功组合之后才回写，所以组合期读到的恒为上一帧的值，
    // 正好补上协程回写之前那一帧的空档；从第二帧起由 appListLayerReleaseHold
    // 接手。
    var lastComposedState by remember { mutableStateOf(screenState) }
    SideEffect { lastComposedState = screenState }
    val appListTouchedLastFrame = lastComposedState == ScreenState.Apps
    var appListReturnOriginActive by remember {
        mutableStateOf(
            screenState == ScreenState.App &&
                launchSourceState == ScreenState.Apps &&
                activeLaunchUsesOrigin,
        )
    }
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
        // screenState 是委托属性，协程里读它拿的是「当前实时值」而不是本次
        // 启动时的那个状态。这里必须先把本协程负责的状态钉死成局部量。
        //
        // 不钉的后果（进列表途中反向滑回表盘就会踩到）：
        //   协程 A 在 Face→Apps 时启动，挂起等 settle；
        //   用户打断，screenState 变 Face，A 被取消，finally 里
        //   `prevState = screenState` 读到的是实时值 Face（本该是 Apps）；
        //   协程 B 随即启动，previousState 也读到 Face，于是在
        //   `previousState == thisState` 这一步直接提前返回——
        //   appListLayerReleaseHold = true 永远执行不到，
        //   isClosingAppListToFace 也永远为假。
        // 结果是退场全程 appListLayerRaised 塌成 false，列表层 zIndex 掉到
        // 表盘背后，就是「进入应用列表时打断会闪一下」。
        val thisState = screenState
        val previousState = prevState
        if (previousState == thisState) return@LaunchedEffect
        if (previousState != ScreenState.Apps && thisState != ScreenState.Apps) {
            // The app-list layer is not part of this transition; nothing to hold.
            appListLayerReleaseHold = false
            prevState = thisState
            return@LaunchedEffect
        }
        appListLayerReleaseHold =
            previousState == ScreenState.Apps && thisState != ScreenState.Apps
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
            prevState = thisState
        }
    }
    // 独立记录上一个状态：prevState 由释放协程在 settle 后才更新，直接读它
    // 判断入场来源会踩到时序竞态。
    var entryTokenLastState by remember { mutableStateOf(screenState) }
    LaunchedEffect(screenState) {
        val cameFrom = entryTokenLastState
        entryTokenLastState = screenState
        if (screenState != ScreenState.Apps) return@LaunchedEffect
        if (suppressNextAppListEntryAnimation) {
            // 手势阶段列表本体已经随手指完整显现；抬手后再把原生列表重置到
            // 逐行入场第 0 帧，会造成一次消失/重播。
            suppressNextAppListEntryAnimation = false
            return@LaunchedEffect
        }
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
    // 跟手期间的通知进度走这个同步状态，不走 Animatable。
    //
    // Animatable.snapTo 是挂起函数，必须包在 launcherScope.launch 里，主线程
    // 调度天生差一帧：手指已经按下并移动、notificationGestureActive 也已置
    // true，但 notificationTransition.value 还停在上一帧的值。通知层这一帧
    // 按旧位置画、下一帧才跳到手指位置——就是「刚拉下来那一刻突然跳跃下来」。
    // 同步 MutableFloatState 没有这个延迟，Animatable 只负责抬手后的收尾。
    var notificationGestureProgress by remember {
        mutableFloatStateOf(if (notificationSceneActive) 1f else 0f)
    }
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
    val activeAppListBackend = appListBackend(layoutMode)
    val appListMotionPhase = when {
        isReturningFromApp || appListIconReturnActive -> activeMotionProfile.appReturn
        // 关闭回表盘就是进入动画的倒放：同一条 appListEnter 谱反向跑，
        // 不再用独立的 pullToFace 档（老师指定，不额外做一套）。
        isClosingAppListToFace -> activeMotionProfile.appListEnter
        else -> activeMotionProfile.appListEnter
    }
    // 越界回弹除了「减弱动态效果」能关，开着「还原老式转场动画」时也一律不给：
    // 老动画本身是硬切风格（表盘 2.5 倍冲脸），收尾再荡一下与它的性格不符，
    // 而且老师点名要去掉。这里跟 reduceDynamicEffects 取并集，两者任一成立就不回弹。
    val appListOvershootAllowed = !reduceDynamicEffects && !legacyAppListTransitionEnabled
    val appListMotionSpec = remember(appListMotionPhase, appListOvershootAllowed) {
        appListMotionPhase.asFloatAnimationSpec(allowOvershoot = appListOvershootAllowed)
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
    // Keep platform elevation stable for the whole transition. Row alpha already
    // gates elevation while an icon is invisible, so a delayed global switch only
    // creates a discontinuity between the return animation and its settled frame.
    val effectiveIconShadowEnabled = iconShadowEnabled
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
            ) {
                // 收尾动画逐帧把值回写到跟手状态——所有读取方只认 notificationGestureProgress，
                // 不用再区分「手势中」还是「弹簧收尾中」两种路径。
                notificationGestureProgress = value.coerceIn(0f, 1f)
            }
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
    // 把布尔用 mutableStateOf 装一次，给 derivedStateOf 提供稳定的依赖。
    val launchMotionRequestedState = remember { mutableStateOf(launchMotionRequested) }
    launchMotionRequestedState.value = launchMotionRequested
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
    // 逐帧读 launchMotionProgress.value 会把整个 LauncherActivity 作用域拖进
    // 每帧重组（这里有上百个 collectAsStateWithLifecycle，作用域极大）。挂载
    // 判定只关心「是否 > 0.001」这个布尔，用 derivedStateOf 收敛成两次失效
    // （起、落各一次），中间的几十帧不再触发重组。
    //
    // 这正是「列表停在中间时打开/返回掉帧、停在顶部或底部反而正常」的根因：
    // 边界处可见行少、重组代价低，中间位置可见行最多、代价最高。
    val showLaunchBackdrop by remember {
        derivedStateOf {
            launchMotionRequestedState.value || launchMotionProgress.value > 0.001f
        }
    }
    // 启动/返回期间遮罩、列表层、底色板、边缘罩共用 launchMotionProgress 这
    // 一个时钟（graphicsLayer 逐帧读，零重组）。p=0 时 provider 精确回到
    // Apps 静止态，外部时钟撤走零跳变。
    // 文件夹内启动应用时，背后那层应用列表不参与转场（老师指定：「打开文件夹内的
    // 应用，后面的应用列表不需要一起动，播放动画」）。
    //
    // openFolder 在启动与返回的**全程**都不清空（见下面 folderHeldForLaunch 那段的
    // 注释），所以它非空就等价于「这趟启动是从文件夹里发起的」，不必另记一个标志。
    val launchStartedInsideFolder = openFolder != null
    val appListLaunchDriven =
        showLaunchBackdrop && launchSourceState == ScreenState.Apps && !launchStartedInsideFolder
    /**
     * 喂给列表层求目标值的状态。
     *
     * 单独撤走 externallyDriven 是不够的：那只是把共享时钟拿掉，`targetValues` 仍按
     * 真实的 `screenState`（此刻已是 App）算出「缩小 + 淡出」，于是列表改用自己的
     * 弹簧照样缩下去 —— 只是不再跟遮罩同步，反而更难看。要让它站住，目标值本身
     * 就得按静止的 Apps 态算。
     */
    val appListMotionState = if (launchStartedInsideFolder && showLaunchBackdrop) {
        ScreenState.Apps
    } else {
        screenState
    }
    val appListLaunchValuesProvider = remember(transitionMotion, appListReturnShrinkEnabled) {
        {
            appListLaunchValues(
                progress = launchMotionProgress.value,
                motion = transitionMotion,
                shrinkOnAppReturn = appListReturnShrinkEnabled,
            )
        }
    }
    val appListLaunchPlateProvider = remember {
        { appListLaunchPlateValues(launchMotionProgress.value) }
    }
    // 边缘渐隐遮罩的 provider：只跟 alpha 走，不跟 scale / translation 走。
    // 遮罩不参与缩放，它必须始终铺满屏幕顶底边界——否则缩小时遮罩一起收，
    // 顶底露出未覆盖的区域，屏幕外的行内容就会从边缘钻进来。
    val appListLaunchFadeProvider = remember(transitionMotion, appListReturnShrinkEnabled) {
        {
            appListLaunchValues(
                progress = launchMotionProgress.value,
                motion = transitionMotion,
                shrinkOnAppReturn = appListReturnShrinkEnabled,
            ).copy(scale = 1f, translationX = 0f, translationY = 0f)
        }
    }
    // The launch dispatch delay keeps the splash on screen long enough; it
    // must not also delay the source icon until the last few frames.
    val iconRevealFraction = 0.06f
    // 同 showLaunchBackdrop：这里曾在 composable 作用域直接算，等于每帧重组
    // 整个 Activity。改成 provider 交给 LaunchBackdropContent 逐帧读，
    // 计算完全不变，只是求值位置从组合期挪到绘制期。
    val splashIconState = remember { mutableStateOf(splashIcon) }
    splashIconState.value = splashIcon
    val launchMotionEnteringState = remember { mutableStateOf(launchMotionTarget > 0.5f) }
    launchMotionEnteringState.value = launchMotionTarget > 0.5f
    val iconProgressProvider = remember {
        {
            if (launchMotionEnteringState.value) {
                if (splashIconState.value) {
                    ((launchMotionProgress.value - iconRevealFraction) /
                        (1f - iconRevealFraction)).coerceIn(0f, 1f)
                } else {
                    0f
                }
            } else {
                launchMotionProgress.value.coerceIn(0f, 1f)
            }
        }
    }
    val launchProgressProvider = remember { { launchMotionProgress.value } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(launcherStyle.screenBackground)
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        // 应用列表背景：解析图源 + 解码 + （30 及以下）预烘焙模糊只做一次，两个绘制点
        // （下面那块不缩放的背板、以及 NativeListDrawerScreen 的层内底色）共用同一份
        // 描述。分头各算一份必然出现「缩放中途看到两种背景的边界」。
        val appListBackgroundPainting = rememberAppListBackgroundPainting(
            config = appListBackgroundConfig,
            fallbackColor = appListColors.background,
            darkMode = effectiveDarkMode,
            viewportWidthPx = screenWidthPx.toInt(),
            viewportHeightPx = screenHeightPx.toInt(),
            watchFaceId = selectedWatchFaceId,
            watchFaceStableKey = selectedWatchFace.stableKey,
            watchFacePreviewPath = selectedWatchFace.previewFilePath,
            builtInPhotoPath = builtInPhotoPath,
            builtInVideoPath = builtInVideoPath,
            watchFaceRefreshToken = watchFaceRefreshToken,
        )
        val notificationProgress = notificationGestureProgress.coerceIn(0f, 1f)
        val keepNotificationLayer = notificationProgress > 0.001f || notificationSceneActive || notificationGestureActive
        // 拖动阶段直接写一个同步 FloatState，不再为每个 pointer frame 启动
        // snapTo 协程。旧设备上那些排队协程会在手指后面追赶，既卡顿又让
        // 预制模糊混合滞后；Animatable 只负责抬手后的单次 spring 收尾。
        val sidePageAnim = remember { Animatable(0f) }
        val widgetPageAnim = remember { Animatable(0f) }
        val controlCenterAnim = remember { Animatable(0f) }
        // 表盘上滑进入应用列表的单一跟手时钟。拖动阶段直接写 FloatState，
        // 抬手后才交给 Animatable；列表、表盘、底板和边缘遮罩都读取它。
        val appListGestureProgress = remember { mutableFloatStateOf(0f) }
        val appListGestureActiveState = remember { mutableStateOf(false) }
        var appListGestureActive by appListGestureActiveState
        val appListGestureSettlingState = remember { mutableStateOf(false) }
        var appListGestureSettling by appListGestureSettlingState
        val appListGestureAnim = remember { Animatable(0f) }
        var appListGestureGeneration by remember { mutableStateOf(0L) }
        var appListGestureSettleJob by remember { mutableStateOf<Job?>(null) }
        val appListGestureProgressProvider = remember {
            { appListGestureProgress.floatValue.coerceIn(0f, 1f) }
        }
        val appListGestureDriven = appListGestureActive || appListGestureSettling
        val watchFaceCaptureRequestToken = remember { mutableStateOf(0L) }
        val requestCurrentWatchFaceFrame = remember {
            {
                watchFaceCaptureRequestToken.value =
                    if (watchFaceCaptureRequestToken.value == Long.MAX_VALUE) 0L
                    else watchFaceCaptureRequestToken.value + 1L
            }
        }
        // Mount latches change only at gesture/settle boundaries. Reading the
        // per-frame progress in composition caused the entire Launcher scene
        // to recompose for every pointer event on API 27-30; layer blocks below
        // now sample progress directly on the render path instead.
        var sidePageMounted by remember { mutableStateOf(false) }
        var widgetPageMounted by remember { mutableStateOf(false) }
        var controlCenterMounted by remember { mutableStateOf(false) }
        val overlayGestureTargetState = remember { mutableStateOf<OverlayGestureTarget?>(null) }
        var overlayGestureTarget by overlayGestureTargetState
        val overlayGestureProgress = remember { mutableFloatStateOf(0f) }
        var overlayGestureGeneration by remember { mutableStateOf(0L) }
        // 覆层越界位移（像素，正数 = 内容往右）。进度必须留在 0..1，越界是叠在开态
        // 之上的额外位移，只喂给 graphicsLayer 的 translationX，不参与布局。
        // 同一时刻只有一个覆层开着，所以三层共用这一份状态。
        val overlayOvershootPx = remember { mutableFloatStateOf(0f) }
        val overlayOvershootAnim = remember { Animatable(0f) }
        var overlayOvershootJob by remember { mutableStateOf<Job?>(null) }
        // 抬手初速度必须按目标分开存。以前是一个共享变量：控制中心下拉松手
        // 写进速度后，三个 settle 的 LaunchedEffect 都因 overlayGestureTarget
        // 归 null 而重启，谁先跑谁就把这个速度抢走灌进自己的 Animatable——
        // 负一屏/小组件于是被一股不属于它的初速度甩出来一截，且下拉越猛
        // 弹得越厉害（就是「拉控制中心时负一屏快捷启动会弹出来」）。
        val overlayReleaseVelocities = remember {
            mutableStateMapOf<OverlayGestureTarget, Float>()
        }
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
                if (sidePageTarget > 0f || sidePageAnim.value > 0.001f) {
                    sidePageMounted = true
                }
                val initialVelocity =
                    overlayReleaseVelocities.remove(OverlayGestureTarget.Stack) ?: 0f
                sidePageAnim.animateTo(sidePageTarget, overlaySettleSpec, initialVelocity)
                if (sidePageTarget <= 0f && overlayGestureTarget != OverlayGestureTarget.Stack) {
                    sidePageMounted = false
                }
            }
        }
        LaunchedEffect(widgetPageTarget, overlayGestureTarget) {
            if (overlayGestureTarget != OverlayGestureTarget.Widgets) {
                if (widgetPageTarget > 0f || widgetPageAnim.value > 0.001f) {
                    widgetPageMounted = true
                }
                val initialVelocity =
                    overlayReleaseVelocities.remove(OverlayGestureTarget.Widgets) ?: 0f
                widgetPageAnim.animateTo(widgetPageTarget, overlaySettleSpec, initialVelocity)
                if (widgetPageTarget <= 0f && overlayGestureTarget != OverlayGestureTarget.Widgets) {
                    widgetPageMounted = false
                }
            }
        }
        LaunchedEffect(controlCenterTarget, overlayGestureTarget) {
            if (overlayGestureTarget != OverlayGestureTarget.ControlCenter) {
                if (controlCenterTarget > 0f || controlCenterAnim.value > 0.001f) {
                    controlCenterMounted = true
                }
                val initialVelocity =
                    overlayReleaseVelocities.remove(OverlayGestureTarget.ControlCenter) ?: 0f
                controlCenterAnim.animateTo(controlCenterTarget, overlaySettleSpec, initialVelocity)
                if (
                    controlCenterTarget <= 0f &&
                    overlayGestureTarget != OverlayGestureTarget.ControlCenter
                ) {
                    controlCenterMounted = false
                }
            }
        }
        val sidePageProgressProvider = remember {
            {
                if (overlayGestureTargetState.value == OverlayGestureTarget.Stack) {
                    overlayGestureProgress.floatValue
                } else {
                    sidePageAnim.value
                }
            }
        }
        val widgetPageProgressProvider = remember {
            {
                if (overlayGestureTargetState.value == OverlayGestureTarget.Widgets) {
                    overlayGestureProgress.floatValue
                } else {
                    widgetPageAnim.value
                }
            }
        }
        val controlCenterProgressProvider = remember {
            {
                if (overlayGestureTargetState.value == OverlayGestureTarget.ControlCenter) {
                    overlayGestureProgress.floatValue
                } else {
                    controlCenterAnim.value
                }
            }
        }
        // 表盘背衬统一进度：四个覆层里最强的一个驱动表盘下压与镜像外扩。
        val overlayBackdropProgress = remember {
            {
                maxOf(
                    sidePageProgressProvider(),
                    widgetPageProgressProvider(),
                    controlCenterProgressProvider(),
                    notificationGestureProgress,
                ).coerceIn(0f, 1f)
            }
        }
        // One draw-phase snapshot shared by the face transform and backdrop.
        // It captures the actual spring/external-clock frame, so Apps/Settings
        // and all side overlays use the same reversible edge-fill geometry.
        val faceLayerFrame = remember { LayerFrameSnapshot() }
        val faceBackdropFillProgress = remember(appListGestureDriven) {
            {
                // 直接读取真实动画帧，返回时不能在 screenState 切回 Face 的
                // 那一刻截断模糊。关闭收缩后也保留原有模糊行程。
                maxOf(
                    overlayBackdropProgress(),
                    if (appListGestureDriven) appListGestureProgressProvider() else 0f,
                    maxOf(faceLayerFrame.backdropProgress(), faceLayerFrame.blur / 24f),
                ).coerceIn(0f, 1f)
            }
        }
        val overlayBackdropActive by remember {
            derivedStateOf {
                maxOf(
                    sidePageProgressProvider(),
                    widgetPageProgressProvider(),
                    controlCenterProgressProvider(),
                    notificationGestureProgress,
                ) > 0.001f
            }
        }
        // Keep the slow-bucket watch face in its normal cadence while a side
        // gesture is only starting. Text exit animation is enabled only once
        // the overlay is nearly settled, so the animation remains visible
        // without turning a 1fps face into a 30fps full repaint path for the
        // entire drag.
        val textExitAnimationActive by remember {
            derivedStateOf {
                false
            }
        }
        // 通知中心压在负一屏上时，负一屏自己的模糊进度。只跟通知的进度走，
        // 不跟 sidePageAnim——负一屏滑入本身不该把自己糊掉。
        val sidePageNotificationBlurProgress = remember {
            {
                notificationUnderlayBlurProgress(
                    notificationReturnState = notificationReturnState,
                    underlayState = ScreenState.Stack,
                    transitionProgress = notificationGestureProgress,
                )
            }
        }
        val controlCenterNotificationBlurProgress = remember {
            {
                notificationUnderlayBlurProgress(
                    notificationReturnState = notificationReturnState,
                    underlayState = ScreenState.ControlCenter,
                    transitionProgress = notificationGestureProgress,
                )
            }
        }
        // 「表盘收缩动画」关掉后，覆层拉出时表盘也不再缩放下沉——只留模糊
        // 与轻微压暗。这个 provider 是覆层拖动期间真正生效的逐帧值（外部时钟），
        // faceLayerValues 那边的静态目标只在非拖动路径上用，两处都得门控，
        // 否则开关看起来"对负一屏没用"。
        val faceBackdropProvider = remember(faceReturnShrinkEnabled) {
            {
                val p = maxOf(
                    sidePageProgressProvider(),
                    widgetPageProgressProvider(),
                    controlCenterProgressProvider(),
                    notificationGestureProgress,
                ).coerceIn(0f, 1f)
                // p=0 必须精确等于表盘静止态（外部时钟撤走零跳变）。
                // alpha 只轻降：表盘变透明会露出纯黑底、还削弱镜像背衬的
                // 亮度；压暗交给覆层玻璃自己的底色。Android 12+ 的三个
                // 页面背衬使用实际 20dp；通知页维持原来的 16dp 观感。
                // 缩放收到 0.08：下压幅度太大时表盘明显「缩成一小块」，
                // 边缘让出的像素全靠背衬副本硬撑。让位靠模糊 + 轻微下压表达，
                // 让出的那一圈由 flueBackdropFill 向外延展填充。
                val pageProgress = maxOf(
                    sidePageProgressProvider(),
                    widgetPageProgressProvider(),
                    controlCenterProgressProvider(),
                ).coerceIn(0f, 1f)
                val notificationProgress = notificationGestureProgress.coerceIn(0f, 1f)
                LayerAnimValues(
                    scale = if (faceReturnShrinkEnabled) 1f - 0.08f * p else 1f,
                    blur = maxOf(20f * pageProgress, 16f * notificationProgress),
                    alpha = 1f - 0.15f * p,
                    translationY = if (faceReturnShrinkEnabled) 0.030f * p else 0f,
                )
            }
        }
        val appListGestureFaceProvider = remember(
            uiStyle,
            faceReturnShrinkEnabled,
            legacyAppListTransitionEnabled,
        ) {
            val start = faceLayerValues(
                ScreenState.Face,
                transitionMotion,
                shrinkOnFaceReturn = faceReturnShrinkEnabled,
                legacyTransition = legacyAppListTransitionEnabled,
            )
            val end = faceLayerValues(
                ScreenState.Apps,
                transitionMotion,
                shrinkOnFaceReturn = faceReturnShrinkEnabled,
                legacyTransition = legacyAppListTransitionEnabled,
            )
            val provider: () -> LayerAnimValues = {
                lerpLayerAnimValues(start, end, appListGestureProgressProvider())
            }
            provider
        }
        val faceTransitionProvider = remember(
            faceBackdropProvider,
            appListGestureFaceProvider,
            appListGestureActiveState,
            appListGestureSettlingState,
            screenState,
        ) {
            {
                if (
                    appListGestureActiveState.value ||
                    appListGestureSettlingState.value ||
                    (screenState == ScreenState.Apps && appListGestureProgressProvider() >= 0.999f)
                ) {
                    appListGestureFaceProvider()
                } else {
                    faceBackdropProvider()
                }
            }
        }
        val faceExternallyDriven = overlayBackdropActive || appListGestureDriven
        val appListGestureLayerProvider = remember(
            uiStyle,
            appListReturnShrinkEnabled,
            faceReturnShrinkEnabled,
            legacyAppListTransitionEnabled,
        ) {
            val start = appListLayerValues(
                ScreenState.Face,
                transitionMotion,
                shrinkOnAppReturn = appListReturnShrinkEnabled,
                shrinkOnFaceReturn = faceReturnShrinkEnabled,
                legacyTransition = legacyAppListTransitionEnabled,
            )
            val end = appListLayerValues(
                ScreenState.Apps,
                transitionMotion,
                shrinkOnAppReturn = appListReturnShrinkEnabled,
                shrinkOnFaceReturn = faceReturnShrinkEnabled,
                legacyTransition = legacyAppListTransitionEnabled,
            )
            val provider: () -> LayerAnimValues = {
                lerpLayerAnimValues(start, end, appListGestureProgressProvider())
            }
            provider
        }
        val appListGesturePlateProvider = remember(appListGestureLayerProvider) {
            {
                appListGestureLayerProvider().copy(
                    scale = 1f,
                    blur = 0f,
                    translationX = 0f,
                    translationY = 0f,
                )
            }
        }
        val appListGestureFadeProvider = remember(appListGestureLayerProvider) {
            {
                appListGestureLayerProvider().copy(
                    scale = 1f,
                    translationX = 0f,
                    translationY = 0f,
                )
            }
        }
        val faceAnimState = screenState
        val updateNotificationTransitionProgress: (Float) -> Unit = { progress ->
            // 同步写状态，不经过协程——协程调度那一帧的延迟就是首帧跳跃的来源。
            notificationGestureProgress = progress.coerceIn(0f, 1f)
            notificationGestureActive = true
        }
        val settleNotificationTransition: (Float) -> Unit = { target ->
            // 把 Animatable 播种到跟手的最后一帧再交给弹簧，否则收尾会从
            // 一个过期的值起跑，抬手瞬间又跳一下。
            val handoff = notificationGestureProgress
            launcherScope.launch { notificationTransition.snapTo(handoff) }
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
        // 加载页的逃生出口：落盘退回内置表盘，然后重启进程。
        //
        // 必须用 await 版本落盘 —— fallbackToBuiltIn 走的是 fire-and-forget 的
        // persist，紧接着杀进程会把写入丢掉，重启完读到的还是那块加载不出来的
        // 表盘，按钮等于没用。
        //
        // 杀进程而不是 recreate()：卡住的往往是后台那条扫描/解析协程，重建
        // Activity 会把它留在进程里继续卡着。本应用是 Launcher，exit 后系统会
        // 用 HOME intent 立刻把它拉回来。
        val fallbackToDefaultWatchFaceAndRestart: () -> Unit = {
            launcherScope.launch {
                vm.fallbackToBuiltInAndAwaitPersist(
                    context.getString(R.string.watch_face_loading_fallback),
                )
                val restartIntent = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                if (restartIntent != null) {
                    runCatching { context.startActivity(restartIntent) }
                }
                Runtime.getRuntime().exit(0)
            }
        }

        // ---- 表盘「渲染就绪」门控（加载页什么时候能撤）-------------------------
        //
        // watchFaceSelectionReady 只表示「知道该显示哪块表盘」。Watchmaker 的
        // prepareSource() 一张位图都不解码，素材是绘制期异步来的，所以选中就绪
        // 之后画面往往还是半成品 —— 老师报的「冷启动加载 UI 直接消失、过一会儿
        // 表盘才好」就是这段缝，切换表盘同理。
        //
        // 键同时含 stableKey 与 refreshToken：切表盘 / 编辑器保存返回的瞬间它天然
        // 不匹配，加载覆层自动回来，不会闪一下上一块表盘的残帧。
        val faceRenderReadyKey = "${selectedWatchFace.stableKey}#$watchFaceRefreshToken"
        var reportedFaceRenderReadyKey by remember { mutableStateOf<String?>(null) }
        // 只有原生渲染器那两种需要等；内置 Compose 表盘与兜底分支立即算就绪。
        val faceNeedsRenderGate = selectedWatchFace.isWatchmaker || selectedWatchFace.isJbWatch
        var faceRenderGateExpired by remember(faceRenderReadyKey) { mutableStateOf(false) }
        LaunchedEffect(faceRenderReadyKey, faceNeedsRenderGate) {
            if (!faceNeedsRenderGate) return@LaunchedEffect
            // 第二道兜底（渲染器自己那道见 WATCH_RENDER_READY_MAX_HOLD_MS）：
            // 就绪信号万一有 bug，也绝不能让用户一直盯着转圈。
            delay(FACE_RENDER_GATE_MAX_HOLD_MS)
            faceRenderGateExpired = true
        }
        val faceRenderReady = !faceNeedsRenderGate ||
            faceRenderGateExpired ||
            reportedFaceRenderReadyKey == faceRenderReadyKey
        val markFaceRenderReady: (Int) -> Unit = { reportedFaceRenderReadyKey = faceRenderReadyKey }

        LaunchedEffect(backPressRequests) {
            if (backPressRequests > 0L) {
                // 米兔适配：这台机器把「从左往右滑」直接映射成系统返回键，而那正是
                // 拉开负一屏的手势 —— 于是负一屏刚划出来就被同一个动作发出的返回给
                // 关掉了。手势与按键是同一个物理动作，应用层分不开，只能让表盘侧
                // 忽略这记返回。
                //
                // 吞掉的范围刻意收窄到「表盘上」与「负一屏手势正在飞」这两种情形：
                // 负一屏完全落定之后的返回仍然有效（那是用户真的想退出），不然开了
                // 这个开关就再也退不出负一屏了。
                val swallowForMituGesture = faceIgnoresBackKey &&
                    (screenState == ScreenState.Face ||
                        overlayGestureTarget == OverlayGestureTarget.Stack)
                if (swallowForMituGesture) {
                    return@LaunchedEffect
                }
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
        // 加载页期间预热三个侧向覆层。
        //
        // 首次划开任一覆层要在同一帧里完成：该层首次组合、AppWidgetHostView
        // 逐个膨胀 RemoteViews、AppWidgetHost.startListening 注册 —— 全挤一帧
        // 必掉帧（老师报的）。趁加载页顶着屏幕先把这些做完。
        //
        // 小组件页和控制中心以前**没有**接进来，只有负一屏预热了，所以冷启动后
        // 第一次划开小组件页照旧顿一下：那一帧要现做 WidgetPageLayer 组合 +
        // startListening + 每个 createView 的跨进程 RemoteViews 膨胀（最贵的
        // 一项），三件事都在主线程上（老师实机反馈）。
        //
        // 首次划开任一覆层要在同一帧里完成：该层首次组合、AppWidgetHostView
        // 逐个膨胀 RemoteViews、AppWidgetHost.startListening 注册 —— 全挤一帧
        // 必掉帧（老师报的）。趁加载页顶着屏幕先把这些做完。
        //
        // 小组件页和控制中心以前**没有**接进来，只有负一屏预热了，所以冷启动后
        // 第一次划开小组件页照旧顿一下：那一帧要现做 WidgetPageLayer 组合 +
        // startListening + 每个 createView 的跨进程 RemoteViews 膨胀（最贵的
        // 一项），三件事都在主线程上（老师实机反馈）。
        //
        // 延后一拍再挂：冷启动本来就是 IO 密集的，让表盘扫描先抢到 IO，
        // 否则预热反而拖慢加载本身。
        //
        // ⚠️ 预热必须在表盘加载完成后**常驻挂载**，不能表盘一好就撤。
        // 以前 `sidePagePrewarmed && !watchFaceSelectionReady` 在加载完成的
        // 那一帧就把三页整体 unmount 掉，widget 的 RemoteViews 全部释放 ——
        // 于是「往哪滑都卡」：之后每一次手势的第一帧都要在主线程上重新
        // 组合整套页面 + 重走一遍跨进程膨胀（负一屏最贵，widget 次之），
        // 正好复现「滑向哪个方向都卡、只卡第一帧、一次只卡一次」。
        // 一直挂着 = 首帧零成本；唯一代价是 widget 常驻占用 RemoteViews
        // 预算与一点内存，但换取的手势首帧流畅是加载页唯一愿意预热的理由。
        var sidePagePrewarmed by remember { mutableStateOf(false) }
        // key 必须是 Unit，**不能**是 watchFaceSelectionReady。以前挂在它上面
        // 且开头就 `if (watchFaceSelectionReady) return`：热启动（Activity 重建
        // 时表盘已选好）第一次组合就命中这条 return，预热于是**永远不会发生**，
        // 三页全程冷挂载。冷启动能预热、热启动不能，正好是「有时候特别卡」的
        // 那一半。改成只延后一拍、不看加载状态，两种启动都预热到。
        LaunchedEffect(Unit) {
            delay(SIDE_PAGE_PREWARM_DELAY_MS)
            sidePagePrewarmed = true
        }
        // 预热单独一条判据，**不并进 sideSceneOverlayActive**：那个标志被表盘
        // 捕获、模糊降档等一大票逻辑当作「有覆层活跃」用，混进来会让冷启动
        // 期间那些路径全部误判。
        // 注意这里**不再要求 !watchFaceSelectionReady** —— 见上面的常驻挂载
        // 说明，预热挂上就挂着。
        val overlayPrewarming = sidePagePrewarmed
        val sideScreenPrewarming = sideScreenEnabled && overlayPrewarming
        val widgetPagePrewarming = showWidgetPage && overlayPrewarming
        val controlCenterPrewarming = showControlCenter && overlayPrewarming
        val sideScreenVisible = sidePageMounted ||
            overlayGestureTarget == OverlayGestureTarget.Stack ||
            screenState == ScreenState.Stack ||
            screenState == ScreenState.Notifications
        val widgetPageVisible = widgetPageMounted ||
            overlayGestureTarget == OverlayGestureTarget.Widgets ||
            screenState == ScreenState.Widgets
        val controlCenterVisible = controlCenterMounted ||
            overlayGestureTarget == OverlayGestureTarget.ControlCenter ||
            screenState == ScreenState.ControlCenter
        val sideSceneOverlayActive = sideScreenVisible || widgetPageVisible || controlCenterVisible
        val appListLayerRaised = screenState == ScreenState.Apps ||
            appListGestureDriven ||
            // 打断转场时补位：prevState 尚未回写的那一帧靠它顶住 zIndex，
            // 否则列表层会瞬间掉到表盘背后闪一下。
            appListTouchedLastFrame ||
            isOpeningAppList ||
            isClosingAppListToFace ||
            isReturningFromApp ||
            appListLayerReleaseHold ||
            (screenState == ScreenState.App && launchSourceState == ScreenState.Apps && showLaunchBackdrop)
        // Keep Watchmaker paused while the app-list backdrop is resident, not
        // only during the opening gesture. Heavy gyro/Lua faces otherwise
        // resume their tick after settle and compete with RecyclerView while
        // remaining fully hidden behind the opaque list.
        val watchFaceOverlayTransitionActive =
            watchFaceCardsVisible || promptOverlayActive ||
                shouldFreezeWatchmakerForLauncherBackdrop(
                    sideSceneOverlayActive = sideSceneOverlayActive,
                    appListBackdropActive = appListLayerRaised,
                )
        var faceTransformSettled by remember { mutableStateOf(false) }
        LaunchedEffect(faceAnimState, faceExternallyDriven) {
            faceTransformSettled = false
        }
        var legacyWatchFaceBounds by remember(legacyWatchFaceVisualKey) {
            mutableStateOf<android.graphics.Rect?>(null)
        }
        val legacyCaptureAllowed =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                launcherInteractive &&
                screenState == ScreenState.Face &&
                watchFaceSelectionReady && faceRenderReady && faceTransformSettled &&
                openFolder == null &&
                !sideSceneOverlayActive &&
                !overlayBackdropActive &&
                !notificationGestureActive &&
                !appListLayerRaised &&
                !showLaunchBackdrop &&
                !watchFaceCardsVisible &&
                !promptOverlayActive &&
                !directLaunchBackdropVisible
        LaunchedEffect(legacyCaptureAllowed, legacyWatchFaceVisualKey) {
            if (legacyCaptureAllowed) requestCurrentWatchFaceFrame()
        }
        val latestLegacyCaptureAllowed = rememberUpdatedState(legacyCaptureAllowed)
        val requestCleanWatchFaceFrame = remember(
            requestCurrentWatchFaceFrame,
            faceBackdropFillProgress,
            overlayGestureTargetState,
            appListGestureActiveState,
            appListGestureSettlingState,
        ) {
            {
                // 第二次手势可能在上一次覆层的 settle 尚未完全归零时按下。
                // 此时绝不能再触发 PixelCopy，否则会把那一点残影连同表盘一起
                // 捕进下一轮模糊；复用上一张已确认干净的静止帧更稳定。
                if (
                    latestLegacyCaptureAllowed.value &&
                    faceBackdropFillProgress() <= 0.001f &&
                    overlayGestureTargetState.value == null &&
                    !appListGestureActiveState.value &&
                    !appListGestureSettlingState.value
                ) {
                    requestCurrentWatchFaceFrame()
                }
            }
        }
        val legacyCapturedWatchFace = rememberLegacyWatchFacePixelCopyFrame(
            visualKey = legacyWatchFaceVisualKey,
            sourceBoundsInWindow = legacyWatchFaceBounds,
            captureAllowed = legacyCaptureAllowed,
            captureRequestToken = watchFaceCaptureRequestToken.value,
        )
        val legacyWatchFaceBackdropFrames = rememberLegacyWatchFaceBackdropFrames(
            descriptor = selectedWatchFace,
            builtInPhotoPath = builtInPhotoPath,
            builtInVideoPath = builtInVideoPath,
            refreshToken = watchFaceRefreshToken,
            fallbackColor = launcherStyle.screenBackground,
            builtInMaterialStyle = uiStyle.isMaterial,
            visualKey = legacyWatchFaceVisualKey,
            capturedFrame = legacyCapturedWatchFace,
            viewportSize = legacyWatchFaceBounds?.let { bounds ->
                IntSize(bounds.width(), bounds.height())
            },
            advancedMaterialEnabled = blurEnabled,
        )
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

        val updateOverlayGesture: (OverlayGestureTarget, Float) -> Unit = { target, progress ->
            overlayGestureGeneration++
            overlayGestureTarget = target
            overlayGestureProgress.floatValue = progress.coerceIn(0f, 1f)
            when (target) {
                OverlayGestureTarget.Stack -> sidePageMounted = true
                OverlayGestureTarget.Widgets -> widgetPageMounted = true
                OverlayGestureTarget.ControlCenter -> controlCenterMounted = true
            }
        }
        // 越界只改绘制位移，不动进度、不动挂载、不动 generation —— 它不是一次转场。
        val updateOverlayOvershoot: (Float) -> Unit = { overshootPx ->
            overlayOvershootJob?.cancel()
            overlayOvershootJob = null
            overlayOvershootPx.floatValue = overshootPx.takeIf(Float::isFinite) ?: 0f
        }
        val releaseOverlayOvershoot: () -> Unit = {
            val from = overlayOvershootPx.floatValue
            overlayOvershootJob?.cancel()
            overlayOvershootJob = if (from == 0f) {
                null
            } else {
                launcherScope.launch {
                    try {
                        overlayOvershootAnim.snapTo(from)
                        overlayOvershootAnim.animateTo(
                            targetValue = 0f,
                            animationSpec = activeMotionProfile.edgeBounce.asFloatAnimationSpec(),
                        ) {
                            overlayOvershootPx.floatValue = value
                        }
                    } finally {
                        overlayOvershootPx.floatValue = 0f
                    }
                }
            }
        }
        val updateAppListGesture: (Float) -> Unit = { progress ->
            if (!appListGestureActive) {
                appListGestureGeneration++
                appListGestureSettleJob?.cancel()
                appListGestureSettling = false
                appListGestureActive = true
            }
            appListGestureProgress.floatValue = progress.coerceIn(0f, 1f)
        }
        val releaseAppListGesture: (Float, Float, Boolean) -> Unit =
            { progress, velocity, cancelled ->
                val handoff = progress.coerceIn(0f, 1f)
                val safeVelocity = velocity.takeIf(Float::isFinite) ?: 0f
                val open = resolveAppListSettleOpen(handoff, safeVelocity, cancelled)
                val target = if (open) 1f else 0f
                val generation = ++appListGestureGeneration
                appListGestureSettleJob?.cancel()
                appListGestureProgress.floatValue = handoff
                appListGestureActive = false
                appListGestureSettling = true
                if (open) {
                    suppressNextAppListEntryAnimation = true
                    vm.setState(ScreenState.Apps)
                }
                appListGestureSettleJob = launcherScope.launch {
                    try {
                        appListGestureAnim.snapTo(handoff)
                        appListGestureAnim.animateTo(
                            targetValue = target,
                            animationSpec = appListMotionSpec,
                            initialVelocity = safeVelocity,
                        ) {
                            appListGestureProgress.floatValue = value.coerceIn(0f, 1f)
                        }
                    } finally {
                        if (generation == appListGestureGeneration) {
                            appListGestureProgress.floatValue = target
                            appListGestureSettling = false
                        }
                    }
                }
            }
        val releaseOverlayGesture: (OverlayGestureTarget, Float, Float, Boolean) -> Unit =
            { target, progress, velocity, cancelled ->
                val overlayState = when (target) {
                    OverlayGestureTarget.Stack -> ScreenState.Stack
                    OverlayGestureTarget.Widgets -> ScreenState.Widgets
                    OverlayGestureTarget.ControlCenter -> ScreenState.ControlCenter
                }
                val wasOpen = screenState == overlayState
                val open = resolveOverlaySettleOpen(
                    progress = progress,
                    velocity = velocity,
                    currentlyOpen = wasOpen,
                    cancelled = cancelled,
                )
                overlayReleaseVelocities[target] = if (cancelled) 0f else velocity
                val handoff = progress.coerceIn(0f, 1f)
                val handoffGeneration = ++overlayGestureGeneration
                // 松手统一收越界位移：无论这一次是 dismiss 还是纯越界，两条路都走这里。
                releaseOverlayOvershoot()
                if (open) {
                    vm.setState(overlayState)
                } else if (wasOpen) {
                    vm.setState(ScreenState.Face)
                }
                launcherScope.launch {
                    when (target) {
                        OverlayGestureTarget.Stack -> sidePageAnim.snapTo(handoff)
                        OverlayGestureTarget.Widgets -> widgetPageAnim.snapTo(handoff)
                        OverlayGestureTarget.ControlCenter -> controlCenterAnim.snapTo(handoff)
                    }
                    if (handoffGeneration == overlayGestureGeneration) {
                        overlayGestureTarget = null
                    }
                }
            }

        GestureHost(
            screenState = screenState,
            onStateChange = { vm.setState(it) },
            sideScreenEnabled = sideScreenEnabled,
            showNotification = false,
            showWidgetPage = showWidgetPage,
            showControlCenter = showControlCenter,
            widgetsBackGestureLocked = widgetPageEditGestureLocked,
            stackDismissHandledByContent = true,
            controlCenterDismissHandledByContent = true,
            onOverlayDrag = updateOverlayGesture,
            onOverlayRelease = releaseOverlayGesture,
            onOverlayOvershoot = updateOverlayOvershoot,
            onAppListDrag = updateAppListGesture,
            onAppListRelease = releaseAppListGesture,
            onBackdropCaptureRequested = requestCleanWatchFaceFrame,
            // 加载页顶着的时候上滑 / 侧滑一律不响应：这些识别器挂在加载页外层，
            // 加载页自己的 pointerInput 拦不到，只能从这里整个停掉。
            gesturesLocked = !watchFaceSelectionReady || aodGestureLocked ||
                watchFaceCardsVisible || promptOverlayActive,
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
                        //
                        // 自定义背景开着时这块底也要跟着走 —— 不然直启列表会先闪一下
                        // 系统黑白再换成老师选的背景。
                        .background(
                            if (screenState == ScreenState.Apps) {
                                appListSolidBackgroundColor
                            } else {
                                launcherStyle.screenBackground
                            },
                        )
                )
            }
            // watchOS 三段式的 B 段：表盘压暗（A）之后由这层渐变到纯黑，图标
            // 再从黑底上飞入（C）。它只在应用列表转场期间存在。
            val appListScrimEnabled = !uiStyle.isMaterial && !legacyAppListTransitionEnabled
            val appListScrimTarget = if (
                appListScrimEnabled &&
                (screenState == ScreenState.Apps ||
                    (screenState == ScreenState.App && launchSourceState == ScreenState.Apps))
            ) 1f else 0f
            val appListGestureScrimProvider = remember(
                appListScrimEnabled,
                appListGestureProgressProvider,
            ) {
                {
                    LayerAnimValues(
                        alpha = if (appListScrimEnabled) {
                            appListGestureProgressProvider()
                        } else {
                            0f
                        },
                    )
                }
            }
            // 黑底也读取和列表、底板、边缘遮罩完全相同的手势时钟。
            // 条件挂载或独立 animateFloat 会在取消/抬手交接的一帧补闪完整黑底。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .scaleBlurAlpha(
                        targetValues = LayerAnimValues(alpha = appListScrimTarget),
                        blurEnabled = false,
                        scaleSpec = appListMotionSpec,
                        alphaSpec = appListAlphaSpec,
                        blurSpec = appListBlurSpec,
                        translationSpec = appListMotionSpec,
                        valuesProvider = appListGestureScrimProvider,
                        externallyDriven = appListGestureDriven,
                    )
                    .background(appListColors.background),
            )
            var folderOverlayProgress by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(openFolder?.componentKey) {
                if (openFolder == null) folderOverlayProgress = 0f
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        if (bounds.width > 1f && bounds.height > 1f) {
                            val resolved = android.graphics.Rect(
                                bounds.left.roundToInt(),
                                bounds.top.roundToInt(),
                                bounds.right.roundToInt(),
                                bounds.bottom.roundToInt(),
                            )
                            if (legacyWatchFaceBounds != resolved) {
                                legacyWatchFaceBounds = resolved
                            }
                        }
                    }
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
                    // 防黑边是必需职责而不是装饰效果，所以不挂在「高级材质」
                    // 开关上，也不因 API < 31 而整体关掉。
                    .flueBackdropFill(
                        progress = faceBackdropFillProgress,
                        enabled = true,
                        fallbackColor = launcherStyle.screenBackground,
                        legacyFrames = legacyWatchFaceBackdropFrames,
                        visualKey = legacyWatchFaceVisualKey,
                        legacyDarkMode = effectiveDarkMode,
                        // 背衬的中心矩形必须和表盘 graphicsLayer 使用同一帧的
                        // scale/translation；矩形外再由 flueBackdropFill 做四边
                        // 与四角镜像，避免只放大中心副本造成粘滞/黑缝。
                        contentScale = {
                            if (faceExternallyDriven) {
                                faceTransitionProvider().scale
                            } else {
                                faceLayerFrame.scale
                            }
                        },
                        contentTranslationYFraction = {
                            if (faceExternallyDriven) {
                                faceTransitionProvider().translationY
                            } else {
                                faceLayerFrame.translationY
                            }
                        },
                        contentBlurRadiusDp = {
                            if (faceExternallyDriven) {
                                faceTransitionProvider().blur
                            } else {
                                faceLayerFrame.blur
                            }
                        },
                        hardwareCapturedFrame = legacyCapturedWatchFace,
                        // Android 12+ 全部表盘统一使用窗口当前帧。内置 Compose
                        // 表盘的 GraphicsLayer 在部分设备上会录到空层，表现为下拉
                        // 时直接变黑；PixelCopy 同时让内置图片/视频也不再切缩略图。
                        hardwareCaptureRequired =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                (blurEnabled || watchFaceOverlayTransitionActive),
                        freezeLiveContentWhenCaptured = watchFaceOverlayTransitionActive,
                        advancedMaterialEnabled = blurEnabled,
                    )
                    // API 31+ 由 scaleBlurAlpha / RenderEffect 实时模糊；
                    // API 27-30 的清晰/预制模糊帧混合已由 flueBackdropFill 处理。
                    .scaleBlurAlpha(
                        targetValues = faceLayerValues(
                            faceAnimState,
                            transitionMotion,
                            shrinkOnFaceReturn = faceReturnShrinkEnabled,
                            legacyTransition = legacyAppListTransitionEnabled,
                        ),
                        screenHeight = screenHeightPx,
                        // 缓存尚未准备好时，真实表盘仍必须有硬件模糊。
                        // 完整背衬就绪后不会 drawContent，不会重复执行模糊。
                        blurEnabled = faceLayerBlurEnabled,
                        origin = LayerPivot.Center,
                        valuesProvider = faceTransitionProvider,
                        externallyDriven = faceExternallyDriven,
                        frameSnapshot = faceLayerFrame,
                        onSettled = {
                            faceTransformSettled = faceAnimState == ScreenState.Face && !faceExternallyDriven
                        },
                    )
            ) {
                val platformViewConfiguration = LocalViewConfiguration.current
                val watchFaceViewConfiguration = remember(platformViewConfiguration) {
                    object : ViewConfiguration by platformViewConfiguration {
                        override val longPressTimeoutMillis: Long = WATCH_FACE_LONG_PRESS_TIMEOUT_MS
                    }
                }
                CompositionLocalProvider(LocalViewConfiguration provides watchFaceViewConfiguration) {
                AnimatedContent(
                    // 只认 watchFaceSelectionReady。
                    //
                    // 原来这里还并了三个 `||` 旁路，其中 `selectedWatchFace.isBuiltin`
                    // 在冷启动第一帧必然成立（ViewModel 里那个字段的初始值就是内置
                    // 表盘），于是 "loading" 分支成了永远走不到的死代码 —— 冷启动
                    // 先渲染一瞬间默认表盘，偏好读出来后再跳到真表盘。老师看到的
                    // 「未完成加载就显示默认表盘」就是它。
                    //
                    // 收紧后若这个 flow 永远不 true，桌面会一直停在加载页；
                    // 加载页 5 秒后的逃生按钮就是为这种情况准备的，两者必须共存。
                    targetState = if (watchFaceSelectionReady) {
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
                        WatchFaceLoadingScreen(
                            onFallbackToDefault = fallbackToDefaultWatchFaceAndRestart,
                        )
                    } else if (selectedWatchFace.isBuiltin) {
                        WatchFaceLayer(
                            ambientMode = ambientMode,
                            watchFaceId = selectedWatchFace.id,
                            photoPath = builtInPhotoPath,
                            videoPath = builtInVideoPath,
                            isFaceVisible = launcherInteractive && screenState == ScreenState.Face &&
                                !watchFaceOverlayTransitionActive,
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
                            onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive && !watchFaceCardsVisible && !promptOverlayActive) openWatchFaceChooser else null,
                            onDoubleTap = if (
                                doubleTapLockScreenEnabled &&
                                launcherInteractive &&
                                screenState == ScreenState.Face &&
                                !sideSceneOverlayActive
                            ) lockScreenWithPlatform else null
                        )
                    } else if (selectedWatchFace.isWatchmaker) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            WatchmakerWatchFaceHost(
                                descriptor = selectedWatchFace,
                                isFaceVisible = launcherInteractive && screenState == ScreenState.Face &&
                                !watchFaceOverlayTransitionActive,
                                refreshToken = watchFaceRefreshToken,
                                onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive && !watchFaceCardsVisible && !promptOverlayActive) openWatchFaceChooser else null,
                                onDoubleTap = if (
                                    doubleTapLockScreenEnabled &&
                                    launcherInteractive &&
                                    screenState == ScreenState.Face &&
                                    !sideSceneOverlayActive
                                ) lockScreenWithPlatform else null,
                                onLoadFailure = { descriptor, error ->
                                    val rootCause = generateSequence(error) { it.cause }.last()
                                    vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                                },
                                dataProvider = watchmakerDataProvider,
                                onRenderReady = markFaceRenderReady,
                                ambientMode = ambientMode,
                                canvasSettings = watchfaceCanvasSettings,
                                gravityParallaxEnabled = watchfaceGravityParallaxEnabled,
                                gravityBackdropBlurEnabled = watchfaceGravityBackdropBlurEnabled,
                                textEntryAnimationEnabled = watchFaceTextEntryAnimation,
                                textExitAnimationEnabled = watchFaceTextExitAnimation,
                                textExitAnimationActive = textExitAnimationActive,
                                overlayTransitionActive = watchFaceOverlayTransitionActive,
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
                                ambientMode = ambientMode,
                                descriptor = selectedWatchFace,
                                isFaceVisible = launcherInteractive && screenState == ScreenState.Face &&
                                !watchFaceOverlayTransitionActive,
                                refreshToken = watchFaceRefreshToken,
                                onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive && !watchFaceCardsVisible && !promptOverlayActive) openWatchFaceChooser else null,
                                onDoubleTap = if (
                                    doubleTapLockScreenEnabled &&
                                    launcherInteractive &&
                                    screenState == ScreenState.Face &&
                                    !sideSceneOverlayActive
                                ) lockScreenWithPlatform else null,
                                onLoadFailure = { descriptor, error ->
                                    val rootCause = generateSequence(error) { it.cause }.last()
                                    vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                                },
                                onRenderReady = markFaceRenderReady,
                                textEntryAnimationEnabled = watchFaceTextEntryAnimation,
                                textExitAnimationEnabled = watchFaceTextExitAnimation,
                                textExitAnimationActive = textExitAnimationActive,
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
                        // Closed-model defensive fallback; unsupported plugin types no
                        // longer exist and can never reach an external runtime.
                        WatchFaceLayer(
                            ambientMode = ambientMode,
                            watchFaceId = BUILT_IN_WATCHFACE_ID,
                            isFaceVisible = launcherInteractive && screenState == ScreenState.Face &&
                                !watchFaceOverlayTransitionActive,
                            uiStyle = uiStyle,
                            showChargingPowerText = watchFaceChargingPowerText,
                            showStatusIndicators = false,
                            showBottomFade = watchFaceBottomFadeEnabled,
                            bottomFadeColor = watchFaceFadePalette.fadeEdge,
                            bottomFadeHeightDp = honeycombBottomFade,
                            bottomFadeBlurRadiusDp = if (blurEnabled && edgeBlurEnabled) honeycombEdgeBlurRadius else 0f,
                            hasNotifications = showNotification && notificationGroups.isNotEmpty(),
                            onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive && !watchFaceCardsVisible && !promptOverlayActive) openWatchFaceChooser else null,
                            onDoubleTap = if (
                                doubleTapLockScreenEnabled &&
                                launcherInteractive &&
                                screenState == ScreenState.Face &&
                                !sideSceneOverlayActive
                            ) lockScreenWithPlatform else null,
                        )
                    }
                }
                }
                // 状态图标属于表盘本身：与表盘共用缩放、下沉、透明度和模糊，
                // 由上层页面自然遮挡，返回时也不再等待额外窗口/就绪帧。
                WatchFaceStatusIndicatorOverlay(
                    showStatusIndicators = watchFaceStatusIndicators && watchFaceSelectionReady,
                    hasNotifications = showNotification && notificationGroups.isNotEmpty(),
                    showDoNotDisturb = watchFaceDndIndicator,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
                )
                // 渲染等待覆层。刻意是 AnimatedContent 的**兄弟**而不是它的一个分支：
                // 做成分支的话新表盘根本不会被组合，也就永远不会开始加载素材、
                // 永远报不了就绪 —— 直接死锁。放在这里，表盘在底下照常加载，
                // 覆层盖着它，就绪后淡出。
                //
                // 放在这个 Box 内还顺带继承同一套 flueBackdropFill / scaleBlurAlpha，
                // 下拉进应用列表时覆层跟着表盘一起缩放，不会多出一层不动的黑板。
                androidx.compose.animation.AnimatedVisibility(
                    visible = watchFaceSelectionReady && !faceRenderReady,
                    enter = fadeIn(tween(90)),
                    exit = fadeOut(tween(180)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    WatchFaceLoadingScreen(
                        onFallbackToDefault = fallbackToDefaultWatchFaceAndRestart,
                        mode = WatchFaceLoadingMode.Switching,
                    )
                }
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
                            appListMotionState,
                            transitionMotion,
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
                        valuesProvider = if (appListGestureDriven) {
                            appListGesturePlateProvider
                        } else {
                            appListLaunchPlateProvider
                        },
                        externallyDriven = appListLaunchDriven || appListGestureDriven,
                    )
                    .appListBackground(appListBackgroundPainting),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (appListLayerRaised) 3f else 0f)
                    .scaleBlurAlpha(
                        targetValues = appListLayerValues(
                            appListMotionState,
                            transitionMotion,
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
                        valuesProvider = if (appListGestureDriven) {
                            appListGestureLayerProvider
                        } else {
                            appListLaunchValuesProvider
                        },
                        externallyDriven = appListLaunchDriven || appListGestureDriven,
                    )
            ) {
                when (val backend = activeAppListBackend) {
                    AppListBackend.NATIVE_LINEAR,
                    AppListBackend.NATIVE_HONEYCOMB,
                    AppListBackend.NATIVE_DENSE_GRID,
                    AppListBackend.NATIVE_FLOW,
                    AppListBackend.NATIVE_RING -> NativeListDrawerScreen(
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
                        active = screenState == ScreenState.Apps || appListGestureDriven,
                        initialScrollResetKey = directLaunchInitialScrollResetKey,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        appListScalePercent = appListScalePercent,
                        // 转场期间那块不缩放的背板已经在铺同色底，层内底色
                        // 让位，避免黑底跟着图标一起缩。
                        backgroundHandledByPlate = appListLayerRaised,
                        backgroundPainting = appListBackgroundPainting,
                        folderOpen = openFolder != null,
                        folderOverlayProgress = folderOverlayProgress,
                        useWatchFaceColors = useAppListWatchFaceColors,
                        rowBorderEnabled = appListRowBorderEnabled,
                        gridIconPlateEnabled = appListGridIconPlateEnabled,
                        allowFolderCreation = appListFoldersEnabled,
                        scrollIndicatorEnabled = appListScrollIndicatorEnabled,
                        fisheyeEnabled = appListFisheyeEnabled,
                        fisheyeRangeRows = appListFisheyeRangeRows,
                        fisheyeStrengthPercent = appListFisheyeStrengthPercent,
                        honeycombTopFisheyeEnabled = materialHoneycombTopFisheyeEnabled,
                        edgeSpacingCompressionEnabled = appListEdgeSpacingCompressionEnabled,
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
                            AppListBackend.NATIVE_FLOW -> NativeAppListPresentation.FLOW
                            AppListBackend.NATIVE_RING -> NativeAppListPresentation.RING
                        },
                        honeycombNarrowColumns = honeycombCols,
                        flowCrescentBulgePercent = appListFlowCrescentBulgePercent,
                        flowCrescentToRight = appListFlowCrescentToRight,
                        flowShapeCurve = activeLayoutProfile.flowShapeCurve,
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
                    appListGestureDriven ||
                    // 与 appListLayerRaised 用同一个补位项：打断转场那一帧
                    // 上面五项可能同时为假，遮罩会在列表还在淡出时整体卸载，
                    // 正是上面注释说的「提前一帧移除就会闪」。
                    appListTouchedLastFrame ||
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
                        // 遮罩层不参与缩放：targetValues / provider 都去掉
                        // scale 和 translation，只保留 alpha（让遮罩随列表
                        // 淡入淡出而不是随它缩放）。如果遮罩也缩，顶底边缘
                        // 会露出没有渐变覆盖的区域，屏幕外的行内容就会在
                        // 缩小时从顶底钻进来。
                        targetValues = appListLayerValues(
                            appListMotionState,
                            transitionMotion,
                            shrinkOnAppReturn = appListReturnShrinkEnabled,
                            shrinkOnFaceReturn = faceReturnShrinkEnabled,
                            legacyTransition = legacyAppListTransitionEnabled,
                        ).copy(scale = 1f, translationX = 0f, translationY = 0f),
                        blurEnabled = false,
                        origin = appListLayerOrigin,
                        scaleSpec = appListMotionSpec,
                        alphaSpec = appListAlphaSpec,
                        blurSpec = appListBlurSpec,
                        translationSpec = appListMotionSpec,
                        valuesProvider = if (appListGestureDriven) {
                            appListGestureFadeProvider
                        } else {
                            appListLaunchFadeProvider
                        },
                        externallyDriven = appListLaunchDriven || appListGestureDriven,
                    ),
            )

            val visibleFolder = openFolder
            // 从文件夹里点开应用不再关闭文件夹（老师指定）：从应用返回时
            // 仍然停在这个文件夹里，跟 iOS / watchOS 的行为一致。
            //
            // openFolder 全程不清空，所以 Apps 态挂载条件自然成立；
            // Apps→App 的那一帧要靠 folderHeldForLaunch 顶住，否则面板会
            // 在启动遮罩还全透明时（alpha = progress*1.6，p≈0）先卸载，
            // 露出一帧光秃秃的应用列表——就是「打开文件夹里的应用会闪」。
            val folderHeldForLaunch = screenState == ScreenState.App &&
                launchSourceState == ScreenState.Apps &&
                showLaunchBackdrop
            // dismissToken：当 screenState 离开 Apps（非应用启动场景）时自增，
            // 通知 AppFolderOverlay 播放180ms淡出动画，然后由它自己调
            // onDismiss() → closeFolder()，避免直接卸载导致模糊层闪消。
            var folderDismissToken by remember { mutableStateOf(0) }
            LaunchedEffect(screenState) {
                if (screenState != ScreenState.Apps && !folderHeldForLaunch && visibleFolder != null) {
                    folderDismissToken++
                }
            }
            if (visibleFolder != null) {
                // 文件夹内外统一圆形（老师指定）。和 nativeAppListConfig 里的
                // iconShape 同源——那边现在也恒为 CIRCLE，两侧不会再分叉。
                val folderIconShape = CircleShape
                AppFolderOverlay(
                    folder = visibleFolder,
                    items = openFolderItems,
                    availableItems = allSelectableApps,
                    listMode = layoutMode == LayoutMode.List,
                    iconShape = folderIconShape,
                    blurEnabled = blurEnabled,
                    iconShadowEnabled = effectiveIconShadowEnabled,
                    // 面板的玻璃高光与 dock / 通知条共用同一项设置。
                    glassHighlightEnabled = glassHighlightEnabled,
                    onAppClick = { appInfo, origin ->
                        // 不关面板：返回时仍停在文件夹内。
                        launchAppFromLauncher(appInfo, origin, ScreenState.Apps)
                    },
                    onReorderItems = { orderedItems -> vm.reorderFolderItems(visibleFolder, orderedItems) },
                    onMoveItemOut = { item -> vm.moveItemOutOfFolder(visibleFolder, item) },
                    onSetFolderItems = { items -> vm.setFolderItems(visibleFolder, items) },
                    onRenameFolder = { name -> vm.renameFolder(visibleFolder, name) },
                    onExcludeApp = { appInfo -> vm.setAppHidden(appInfo.componentKey, true) },
                    onRemoveShortcut = vm::removeAppListShortcut,
                    onDismiss = vm::closeFolder,
                    onTransitionProgress = { folderOverlayProgress = it },
                    dismissRequestToken = folderDismissToken,
                    // During app-launch mask entry, Back cancels that transition
                    // through LauncherViewModel instead of dismissing the folder.
                    backHandlerEnabled = screenState != ScreenState.App && !showLaunchBackdrop,
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
                    progressProvider = launchProgressProvider,
                    iconProgressProvider = iconProgressProvider,
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

            // sideScreenPrewarming 让这层在加载页期间就挂上来完成组合与
            // RemoteViews 膨胀。不需要额外藏它：下面 graphicsLayer 的
            // alpha 跟 pageProgress 走，此时是 0，translationX 也在屏外。
            if (sideScreenEnabled && (sideScreenVisible || sideScreenPrewarming)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                        // 从负一屏上拉通知中心时，被模糊的应该是负一屏自己。
                        // 这层以前只有位移 + alpha，一件模糊都没挂，所以通知
                        // 叠上来只剩它自己的灰底，底下的负一屏全程清晰。
                        // 27–30 走涂抹近似，31+ 由下面的 RenderEffect 负责；
                        // 两者都不挂在「高级材质」开关上。
                        .flueLegacyBlur(
                            progress = sidePageNotificationBlurProgress,
                            enabled = true,
                            advancedMaterialEnabled = blurEnabled,
                        )
                        .graphicsLayer {
                            // 纯位移进场：图层自身永远不透明也不缩放。以前
                            // alpha 跟进度走，滑入全程能透过负一屏看到表盘
                            // （通知中心叠上时还被压到 0.42）。
                            //
                            // 位移量必须用图层自己的测量宽度：LocalConfiguration
                            // 的屏宽不含 inset，比实际布局窄一点，收起时就会在
                            // 屏幕左侧留下一道边——打开控制中心时看到的「负一屏
                            // 边边」就是它。
                            val offscreen = size.width.coerceAtLeast(screenWidthPx)
                            val pageProgress = sidePageProgressProvider()
                            // 越界位移叠在开态之上（正数 = 往右）。写在 translationX 上
                            // 而不是布局里：越界不该触发重排。
                            translationX = (pageProgress - 1f) * offscreen +
                                overlayOvershootPx.floatValue
                            alpha = if (pageProgress > 0.001f) 1f else 0f
                            val blurP = sidePageNotificationBlurProgress()
                            val radiusPx = with(density) { (24f * blurP).dp.toPx() }
                                .roundToInt()
                                .coerceAtLeast(0)
                            renderEffect = if (
                                shouldRenderLayerBlur(
                                    sdkInt = Build.VERSION.SDK_INT,
                                    blurEnabled = blurEnabled,
                                    alpha = alpha,
                                    radiusPx = radiusPx,
                                )
                            ) {
                                systemBlurEffectForRadiusPx(radiusPx)
                            } else null
                        }
                ) {
                    SmartStackLayer(
                        apps = allApps,
                        sideScreenShortcuts = sideScreenShortcuts,
                        shortcutRows = sideScreenShortcutRows,
                        shortcutColumns = sideScreenShortcutCols,
                        previewGroups = sideScreenPreviewGroups,
                        showMusicCard = cardPlacement.musicOnSideScreen,
                        contentVisible = sideScreenVisible,
                        showMediaCustomActions = showMediaCustomActions,
                        musicTextSwitchAnimation = musicTextSwitchAnimation,
                        glassHighlightEnabled = glassHighlightEnabled,
                        overlayMaskCornerRadiusDp = overlayMaskCornerRadiusDp,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        notificationsEnabled =
                            notificationsEnabled && cardPlacement.notificationOnSideScreen,
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
                        onQueryAppShortcuts = vm::querySideScreenAppShortcuts,
                        onSetAppShortcut = vm::setSideScreenAppShortcut,
                        onRemoveShortcut = vm::removeSideScreenShortcut,
                        onSwapShortcut = vm::swapSideScreenShortcuts,
                        onSetShowNotification = vm::setShowNotification,
                        onDismissGroup = vm::dismissNotificationGroup,
                        onDismissNotification = vm::dismissNotification,
                        onDismissProgressChange = { progress ->
                            updateOverlayGesture(OverlayGestureTarget.Stack, progress)
                        },
                        onOvershootChange = updateOverlayOvershoot,
                        onDismissRelease = { progress, velocity, cancelled ->
                            releaseOverlayGesture(
                                OverlayGestureTarget.Stack,
                                progress,
                                velocity,
                                cancelled,
                            )
                        },
                        showSteps = showStepCount,
                    )
                }
            }

            // widgetPagePrewarming 让这层在加载页期间就挂上来完成组合、
            // startListening 与 RemoteViews 膨胀。不需要额外藏它：下面
            // graphicsLayer 的 alpha 跟 pageProgress 走，此时是 0，
            // translationX 也在屏外。
            if (showWidgetPage && (widgetPageVisible || widgetPagePrewarming)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4.2f)
                        .graphicsLayer {
                            // 同上：用测量宽度而不是 configuration 屏宽，否则
                            // 收起态会在屏幕右侧留下一道小组件页的边。
                            val offscreen = size.width.coerceAtLeast(screenWidthPx)
                            val pageProgress = widgetPageProgressProvider()
                            // 小组件页往外拉是向左，overlayOvershootPx 此时为负值。
                            translationX = (1f - pageProgress) * offscreen +
                                overlayOvershootPx.floatValue
                            alpha = if (pageProgress > 0.001f) 1f else 0f
                        }
                        // Keep the screen mask outside the page translation so an
                        // Android widget cannot expose a square corner while the
                        // widget page is sliding in or out.
                        .clip(flueOverlayMaskShape(overlayMaskCornerRadiusDp))
                ) {
                    val sideScreenWidgetSlots by vm.sideScreenWidgetSlots.collectAsStateWithLifecycle()
                    WidgetPageLayer(
                        apps = allApps,
                        active = screenState == ScreenState.Widgets,
                        sideScreenWidgetSlots = sideScreenWidgetSlots,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        glassHighlightEnabled = glassHighlightEnabled,
                        onSetWidget = vm::setWidgetSlot,
                        onSwapWidget = vm::swapWidgetSlots,
                        onRemoveWidget = vm::removeWidgetSlot,
                        onEditModeChange = { widgetPageEditGestureLocked = it },
                        widgetGlassHighlightEnabled = widgetGlassHighlightEnabled,
                        overlayMaskCornerRadiusDp = overlayMaskCornerRadiusDp,
                    )
                }
            }

            // 同上：controlCenterPrewarming 在加载页期间完成首次组合。
            // 这层的位移是 translationY，收起态同样在屏外且 alpha 为 0。
            if (showControlCenter && (controlCenterVisible || controlCenterPrewarming)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4.4f)
                        .flueLegacyBlur(
                            progress = controlCenterNotificationBlurProgress,
                            enabled = true,
                            advancedMaterialEnabled = blurEnabled,
                        )
                        .graphicsLayer {
                            val offscreen = size.height.coerceAtLeast(screenHeightPx)
                            val pageProgress = controlCenterProgressProvider()
                            translationY = (pageProgress - 1f) * offscreen
                            alpha = if (pageProgress > 0.001f) 1f else 0f
                            val blurP = controlCenterNotificationBlurProgress()
                            val radiusPx = with(density) { (24f * blurP).dp.toPx() }
                                .roundToInt()
                                .coerceAtLeast(0)
                            renderEffect = if (
                                shouldRenderLayerBlur(
                                    sdkInt = Build.VERSION.SDK_INT,
                                    blurEnabled = blurEnabled,
                                    alpha = alpha,
                                    radiusPx = radiusPx,
                                )
                            ) {
                                systemBlurEffectForRadiusPx(radiusPx)
                            } else null
                        }
                ) {
                    ControlCenterLayer(
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        showMusicControls = cardPlacement.musicInControlCenter,
                        notificationAccessGranted = notificationAccessGranted,
                        musicTextSwitchAnimation = musicTextSwitchAnimation,
                        showMediaCustomActions = showMediaCustomActions,
                        showNotificationCard = cardPlacement.notificationInControlCenter,
                        notificationGroups = sideScreenPreviewGroups,
                        onOpenNotifications = { openNotificationsFrom(ScreenState.ControlCenter) },
                        onDismissProgressChange = { progress ->
                            updateOverlayGesture(OverlayGestureTarget.ControlCenter, progress)
                        },
                        onDismissRelease = { progress, velocity, cancelled ->
                            releaseOverlayGesture(
                                OverlayGestureTarget.ControlCenter,
                                progress,
                                velocity,
                                cancelled,
                            )
                        },
                        showPowerButton = powerMenuButtonEnabled,
                        onPowerClick = openPowerDialogWithAccessibility,
                        glassHighlightEnabled = glassHighlightEnabled,
                        overlayMaskCornerRadiusDp = overlayMaskCornerRadiusDp,
                        // 这一层常驻组合，靠位移移出屏外，所以它自己看不出「又被打开了」。
                        // 用打开态喂给它，好让滚动在开场那一帧回到顶部。
                        //
                        // 这里必须用 controlCenterVisible 而不是 controlCenterTarget：后者跟着
                        // **离散的** screenState 走，而下拉是手势驱动的 —— screenState 只在松手
                        // 那一刻才翻成 ControlCenter。于是整段拖动过程里 target 仍是 0、复位不
                        // 触发，内容早就带着上次退出时的滚动位置露在屏幕上了；等松手再复位，
                        // 看见的是内容「跳」回顶部。这就是老师报的「再次下拉是从退出位置进入」。
                        //
                        // controlCenterVisible 在手势第一帧就成立（updateOverlayGesture 会把
                        // controlCenterMounted 置真），复位落在内容还基本在屏外的时候，看不见。
                        contentVisible = controlCenterVisible,
                    )
                }
            }

            // Android 12 以下：通知中心压在负一屏 / 控制中心上时的烘焙模糊背景。
            //
            // 那两层的根底是 alpha = 0.62 的半透明玻璃，底下的表盘和图标是直接
            // 穿透过来的 —— 挂在它们身上的 flueLegacyBlur 只能糊「自己录下来的
            // 内容」，穿透的像素根本不在那个图层里，所以通知列表后面一直是清晰
            // 的图标，可读性极差（老师截图）。这里改成和表盘同一套 PixelCopy
            // 预制帧。
            //
            // 闸门必须两头都卡死：覆层要完全展开（早了采到入场中间态），通知
            // 要一点都还没拉出来（晚了会把通知列表自己采进背景，形成套娃）。
            //
            // 只用**离散状态**判定，绝不读 sidePageAnim / controlCenterAnim 的
            // 逐帧 value —— 那些是 Animatable，在组合期读会让整个 Launcher 场景
            // 跟着每一帧重组（本文件上面那条注释记的正是这个已修掉的坑）。
            // 稳定停在 Stack / ControlCenter 且没有任何手势在飞，就等价于「已完全
            // 展开」；剩下的落定余量由捕获函数自己那段 delay 负责。
            val notificationUnderlaySettled =
                (screenState == ScreenState.Stack || screenState == ScreenState.ControlCenter) &&
                    overlayGestureTarget == null &&
                    !notificationGestureActive &&
                    !notificationSceneActive
            // SDK < 31: 通知中心直接用纯色背景，不再尝试模糊（见 NotificationLayer 的背景 alpha）。
            // PixelCopy + CPU box blur 在弱设备上是真实开销，现在可以完全省掉。
            val legacyNotificationBackdrop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                rememberLegacyOverlayBlurBackdrop(
                    captureAllowed = notificationsEnabled && notificationUnderlaySettled,
                    // 底层内容变了就要重采：换页面、换表盘、小组件增删都算。
                    visualKey = listOf(
                        screenState.name,
                        selectedWatchFace.stableKey,
                        watchFaceRefreshToken.toString(),
                        sideScreenShortcuts.size.toString(),
                        notificationGroups.size.toString(),
                    ).joinToString("|"),
                    advancedMaterialEnabled = blurEnabled,
                )
            } else {
                null
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
                        glassHighlightEnabled = glassHighlightEnabled,
                        legacyBlurBackdrop = legacyNotificationBackdrop,
                        overlayMaskCornerRadiusDp = overlayMaskCornerRadiusDp,
                    )
                    }
                }
            }
            if (watchFaceCardsVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f),
                ) {
                    WatchFaceCardSelector(
                        vm = vm,
                        watchFaces = availableWatchFaces,
                        selectedWatchFaceId = selectedWatchFaceId,
                        refreshToken = watchFaceRefreshToken,
                        uiStyle = uiStyle,
                        backdropColor = legacyWatchFaceBackdropFrames?.fallbackArgb?.let { Color(it) },
                        builtInPhotoPath = builtInPhotoPath,
                        builtInVideoPath = builtInVideoPath,
                        photoOptions = photoWatchOptions,
                        videoOptions = videoWatchOptions,
                        onDismiss = { watchFaceCardsVisible = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchBackdropContent(
    progressProvider: () -> Float,
    iconProgressProvider: () -> Float,
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
    val initialIconScale = if (uiStyle == UiStyle.MATERIAL_3) 0.82f else 0.5f
    val exitIconScale = if (uiStyle == UiStyle.MATERIAL_3) 0.88f else 0.3f
    val normalizedOrigin = if (origin.x.isFinite() && origin.y.isFinite()) {
        Offset(origin.x.coerceIn(0f, 1f), origin.y.coerceIn(0f, 1f))
    } else {
        Offset(0.5f, 0.5f)
    }
    val sourceTranslationX = (normalizedOrigin.x - 0.5f) * screenWidthPx
    val sourceTranslationY = (normalizedOrigin.y - 0.5f) * screenHeightPx
    // 输入拦截跟遮罩自己的可见度走，而不是跟挂载周期走。
    //
    // 旧写法在整个挂载周期无条件 consume，而卸载条件是 progress > 0.001
    // （showLaunchBackdrop），返回弹簧的 visibilityThreshold 又被刻意收紧到
    // 0.0002 来消除「最后一点直接跳过去」。两者相乘：p=0.002 时 alpha 只剩
    // 0.3%，肉眼已是纯透明，遮罩却还挂在 zIndex(6f) 上一帧不漏地吃掉每个
    // 触摸，弹簧还要再爬几百毫秒才卸载 —— 就是「退出动画看起来做完了却
    // 还不能滑动或点其他图标」。
    //
    // entering 必须无条件拦截：入场时 progress 从 0 起步，此时遮罩虽薄但
    // 启动已在飞行中，放行会让第二次点击穿透到下面再启动一个应用。
    //
    // 拦截阈值用 derivedStateOf 而不是每帧直读：布尔只在跨过 0.03 时翻转，
    // 中间几十帧不再重组。1.6 倍斜率与下面 graphicsLayer 里的 alpha 必须
    // 同源，否则改一侧会让「看得见」和「挡得住」悄悄错位。
    val consumesInput by remember(entering) {
        derivedStateOf {
            entering || (progressProvider().coerceIn(0f, 1f) * 1.6f) > 0.03f
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (consumesInput) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                // 1.6 倍斜率：p≈0.62 就完全不透明。列表层的 alpha 用 1.8 倍
                // 斜率提前淡出（appListLaunchValues），两条曲线共用同一个
                // progress —— 返回方向遮罩变薄时，底下列表已按同一时钟就位，
                // 不再出现「透过半透明遮罩看见 4 倍巨图标收缩然后闪现」。
                alpha = (progressProvider().coerceIn(0f, 1f) * 1.6f).coerceIn(0f, 1f)
            }
            .background(backgroundColor.copy(alpha = 1f)),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        // 图标的四个通道全在绘制期求值。原先 iconScale /
                        // visibleIconProgress 在组合期算，把每帧动画变成一次
                        // 重组；这里只读同一个 Animatable，视觉完全不变。
                        val iconProgress = iconProgressProvider().coerceIn(0f, 1f)
                        val base = if (entering) initialIconScale else exitIconScale
                        val scale = base + (1f - base) * iconProgress
                        val offsetFraction = 1f - progressProvider().coerceIn(0f, 1f)
                        scaleX = scale
                        scaleY = scale
                        alpha = iconProgress
                        translationX = sourceTranslationX * offsetFraction
                        translationY = sourceTranslationY * offsetFraction
                    }
                    .clip(if (uiStyle == UiStyle.MATERIAL_3) launcherStyle.compactShape else CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}
