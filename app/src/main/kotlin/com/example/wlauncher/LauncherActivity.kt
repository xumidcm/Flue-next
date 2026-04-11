package com.flue.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.ui.anim.LayerAnimValues
import com.flue.launcher.ui.anim.appListLayerValues
import com.flue.launcher.ui.anim.faceLayerValues
import com.flue.launcher.ui.anim.notificationLayerValues
import com.flue.launcher.ui.anim.scaleBlurAlpha
import com.flue.launcher.ui.anim.stackLayerValues
import com.flue.launcher.ui.drawer.HoneycombScreen
import com.flue.launcher.ui.drawer.ListDrawerScreen
import com.flue.launcher.ui.home.WatchFaceLayer
import com.flue.launcher.ui.navigation.GestureHost
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.navigation.ScreenState
import com.flue.launcher.ui.notification.NotificationLayer
import com.flue.launcher.ui.settings.LauncherSettingsSheet
import com.flue.launcher.ui.smartstack.SmartStackLayer
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.LunchWatchFaceHost
import com.flue.launcher.watchface.LunchWatchFaceRuntime
import kotlinx.coroutines.delay

private const val BASE_LAUNCH_MASK_DELAY_MS = 180L

class LauncherActivity : ComponentActivity() {

    private lateinit var vm: LauncherViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler(applicationContext).install()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        onBackPressedDispatcher.addCallback(this) { vm.handleBackPress() }
        setContent {
            WatchLauncherTheme {
                val viewModel: LauncherViewModel = viewModel()
                vm = viewModel
                LauncherScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::vm.isInitialized) vm.handleHomePress()
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized && vm.animationOverrideEnabled.value) {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, R.anim.launcher_return_cupertino_exit)
        }
        if (::vm.isInitialized) {
            vm.onReturnToLauncher()
            vm.refreshWatchFaces()
            vm.refreshNotificationAccess()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::vm.isInitialized && vm.animationOverrideEnabled.value) {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}

@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val context = LocalContext.current
    val screenState by vm.screenState.collectAsState()
    val layoutMode by vm.layoutMode.collectAsState()
    val sideScreenEnabled by vm.sideScreenEnabled.collectAsState()
    val sideScreenShortcuts by vm.sideScreenShortcuts.collectAsState()
    val sideScreenPreviewGroups by vm.sideScreenPreviewGroups.collectAsState()
    val blurEnabled by vm.blurEnabled.collectAsState()
    val edgeBlurEnabled by vm.edgeBlurEnabled.collectAsState()
    val legacyCircularIcons by vm.legacyCircularIcons.collectAsState()
    val animationOverrideEnabled by vm.animationOverrideEnabled.collectAsState()
    val apps by vm.apps.collectAsState()
    val appOpenOrigin by vm.appOpenOrigin.collectAsState()
    val splashIcon by vm.splashIcon.collectAsState()
    val splashDelay by vm.splashDelay.collectAsState()
    val currentApp by vm.currentApp.collectAsState()
    val currentLaunchIcon by vm.currentLaunchIcon.collectAsState()
    val honeycombCols by vm.honeycombCols.collectAsState()
    val honeycombTopBlur by vm.honeycombTopBlur.collectAsState()
    val honeycombBottomBlur by vm.honeycombBottomBlur.collectAsState()
    val honeycombTopFade by vm.honeycombTopFade.collectAsState()
    val honeycombBottomFade by vm.honeycombBottomFade.collectAsState()
    val honeycombFastScrollOptimization by vm.honeycombFastScrollOptimization.collectAsState()
    val showNotification by vm.showNotification.collectAsState()
    val notificationAccessGranted by vm.notificationAccessGranted.collectAsState()
    val notificationGroups by vm.notificationGroups.collectAsState()
    val revealedNotificationTarget by vm.revealedNotificationTarget.collectAsState()
    val launchSourceState by vm.launchSourceState.collectAsState()
    val watchFaces by vm.availableWatchFaces.collectAsState()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsState()
    val selectedWatchFace by vm.selectedWatchFace.collectAsState()
    val watchFaceSelectionReady by vm.watchFaceSelectionReady.collectAsState()
    val watchFaceRefreshToken by vm.watchFaceRefreshToken.collectAsState()
    val watchFaceLastError by vm.watchFaceLastError.collectAsState()
    val builtInPhotoPath by vm.builtInPhotoPath.collectAsState()
    val builtInVideoPath by vm.builtInVideoPath.collectAsState()
    val builtInPhotoClockPosition by vm.builtInPhotoClockPosition.collectAsState()
    val builtInVideoClockPosition by vm.builtInVideoClockPosition.collectAsState()
    val builtInPhotoClockSize by vm.builtInPhotoClockSize.collectAsState()
    val builtInVideoClockSize by vm.builtInVideoClockSize.collectAsState()
    val builtInPhotoClockBold by vm.builtInPhotoClockBold.collectAsState()
    val builtInVideoClockBold by vm.builtInVideoClockBold.collectAsState()
    val builtInVideoFillScreen by vm.builtInVideoFillScreen.collectAsState()
    val builtInVideoClockColorMode by vm.builtInVideoClockColorMode.collectAsState()
    val layerBlurEnabled = blurEnabled && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || screenState != ScreenState.App)
    val reduceLegacyDrawerEffects = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && screenState == ScreenState.App
    val notificationsLayerEnabled = showNotification
    val openWatchFaceChooser = remember(context) {
        {
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_WATCH_FACES)
                    .putExtra(EXTRA_SETTINGS_RETURN_TO_FACE, true)
            )
            (context as? Activity)?.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
    var prevState by remember { mutableStateOf(screenState) }
    val isReturningFromApp = prevState == ScreenState.App && screenState == ScreenState.Apps
    LaunchedEffect(screenState) { prevState = screenState }

    val useOrigin = screenState == ScreenState.App || isReturningFromApp

    LaunchedEffect(sideScreenEnabled, screenState) {
        if (!sideScreenEnabled && (screenState == ScreenState.Stack || screenState == ScreenState.Notifications)) {
            vm.setState(ScreenState.Face)
        }
    }

    val showLaunchBackdrop = screenState == ScreenState.App && currentLaunchIcon != null
    var showSplash by remember { mutableStateOf(false) }
    LaunchedEffect(screenState, splashIcon, splashDelay, currentLaunchIcon) {
        if (screenState == ScreenState.App && splashIcon && currentLaunchIcon != null) {
            showSplash = false
            delay(BASE_LAUNCH_MASK_DELAY_MS)
            showSplash = true
        } else {
            showSplash = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val launchingFromStack = screenState == ScreenState.App && launchSourceState == ScreenState.Stack
        val launchingFromNotifications = screenState == ScreenState.App && launchSourceState == ScreenState.Notifications
        val stackTargetValues = when {
            launchingFromStack -> LayerAnimValues(scale = 4f, blur = 10f, alpha = 0f)
            else -> stackLayerValues(screenState)
        }
        val notificationTargetValues = when {
            launchingFromNotifications -> LayerAnimValues(scale = 4f, blur = 10f, alpha = 0f, translationY = 0f)
            else -> notificationLayerValues(screenState)
        }

        GestureHost(
            screenState = screenState,
            onStateChange = { vm.setState(it) },
            sideScreenEnabled = sideScreenEnabled,
            showNotification = notificationsLayerEnabled,
            showControlCenter = false,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .scaleBlurAlpha(
                        targetValues = faceLayerValues(screenState),
                        screenWidth = screenWidthPx,
                        screenHeight = screenHeightPx,
                        blurEnabled = layerBlurEnabled
                    )
            ) {
                AnimatedContent(
                    targetState = if (watchFaceSelectionReady || selectedWatchFace.isBuiltin || selectedWatchFaceId == BUILT_IN_WATCHFACE_ID) {
                        selectedWatchFace.stableKey
                    } else {
                        "loading"
                    },
                    transitionSpec = {
                        fadeIn(animationSpec = androidx.compose.animation.core.tween(220, delayMillis = 70)) togetherWith
                            fadeOut(animationSpec = androidx.compose.animation.core.tween(180))
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
                            isFaceVisible = screenState == ScreenState.Face,
                            photoOptions = BuiltInWatchFaceOptions(
                                clockPosition = builtInPhotoClockPosition,
                                clockSizeSp = builtInPhotoClockSize,
                                boldClock = builtInPhotoClockBold
                            ),
                            videoOptions = BuiltInWatchFaceOptions(
                                clockPosition = builtInVideoClockPosition,
                                clockSizeSp = builtInVideoClockSize,
                                boldClock = builtInVideoClockBold,
                                cropToFill = builtInVideoFillScreen,
                                clockColorMode = builtInVideoClockColorMode
                            ),
                            onLongPress = null
                        )
                    } else {
                        LunchWatchFaceHost(
                            descriptor = selectedWatchFace,
                            isFaceVisible = screenState == ScreenState.Face,
                            refreshToken = watchFaceRefreshToken,
                            onLongPress = null,
                            onLoadFailure = { descriptor, error ->
                                val rootCause = generateSequence(error) { it.cause }.last()
                                vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                            }
                        )
                    }
                }

                if (screenState == ScreenState.Face) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(openWatchFaceChooser) {
                                detectTapGestures(onLongPress = { openWatchFaceChooser() })
                            }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .scaleBlurAlpha(
                        targetValues = appListLayerValues(screenState),
                        screenWidth = screenWidthPx,
                        screenHeight = screenHeightPx,
                        blurEnabled = layerBlurEnabled,
                        origin = if (useOrigin) appOpenOrigin else null
                    )
            ) {
                when (layoutMode) {
                    LayoutMode.Honeycomb -> HoneycombScreen(
                        apps = apps,
                        blurEnabled = blurEnabled,
                        edgeBlurEnabled = edgeBlurEnabled,
                        suppressHeavyEffects = reduceLegacyDrawerEffects,
                        narrowCols = honeycombCols,
                        topBlurRadiusDp = honeycombTopBlur,
                        bottomBlurRadiusDp = honeycombBottomBlur,
                        topFadeRangeDp = honeycombTopFade,
                        bottomFadeRangeDp = honeycombBottomFade,
                        fastScrollOptimizationEnabled = honeycombFastScrollOptimization,
                        onAppClick = { appInfo, origin ->
                            val launchDelay = BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                            vm.openApp(appInfo, origin, launchDelay, ScreenState.Apps)
                        },
                        onReorder = { from, to -> vm.swapApps(from, to) },
                        onScrollToTop = { vm.setState(ScreenState.Face) }
                    )
                    LayoutMode.List -> ListDrawerScreen(
                        apps = apps,
                        blurEnabled = blurEnabled,
                        edgeBlurEnabled = edgeBlurEnabled,
                        suppressHeavyEffects = reduceLegacyDrawerEffects,
                        topFadeRangeDp = honeycombTopFade,
                        bottomFadeRangeDp = honeycombBottomFade,
                        onAppClick = { appInfo, origin ->
                            val launchDelay = BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                            vm.openApp(appInfo, origin, launchDelay, ScreenState.Apps)
                        },
                        onReorder = { from, to -> vm.swapApps(from, to) },
                        onScrollToTop = { vm.setState(ScreenState.Face) }
                    )
                }
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

            AnimatedVisibility(
                visible = showLaunchBackdrop,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedVisibility(
                        visible = showSplash && splashIcon && currentLaunchIcon != null,
                        enter = fadeIn() + scaleIn(initialScale = 0.5f),
                        exit = fadeOut() + scaleOut(targetScale = 0.3f)
                    ) {
                        currentLaunchIcon?.let { icon ->
                            Image(
                                bitmap = icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            /* legacy launch overlay animation kept for icon stage only */
            /* removed duplicate full-screen backdrop composition */

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(
                        when (screenState) {
                            ScreenState.Stack -> 6f
                            ScreenState.Notifications -> 5f
                            else -> 2f
                        }
                    )
                    .scaleBlurAlpha(
                        targetValues = stackTargetValues,
                        screenWidth = screenWidthPx,
                        screenHeight = screenHeightPx,
                        blurEnabled = layerBlurEnabled,
                        origin = if (launchingFromStack) appOpenOrigin else null
                    )
            ) {
                if (screenState == ScreenState.Stack || screenState == ScreenState.Notifications || launchingFromStack) {
                    SmartStackLayer(
                        apps = apps,
                        sideScreenShortcuts = sideScreenShortcuts,
                        previewGroups = sideScreenPreviewGroups,
                        notificationsEnabled = notificationsLayerEnabled,
                        notificationAccessGranted = notificationAccessGranted,
                        revealedNotificationTarget = revealedNotificationTarget,
                        onRevealTargetChange = vm::setRevealedNotificationTarget,
                        onOpenNotifications = { vm.setState(ScreenState.Notifications) },
                        onLaunchApp = { appInfo, origin ->
                            val launchDelay = BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                            vm.openApp(appInfo, origin, launchDelay, ScreenState.Stack)
                        },
                        onSetShortcut = vm::setSideScreenShortcut,
                        onRemoveShortcut = vm::removeSideScreenShortcut,
                        onDismissGroup = vm::dismissNotificationGroup,
                        onDismissNotification = vm::dismissNotification,
                        onDismissToFace = { vm.setState(ScreenState.Face) }
                    )
                }
            }

            if (notificationsLayerEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (screenState == ScreenState.Notifications) 7f else 3f)
                        .scaleBlurAlpha(
                            targetValues = notificationTargetValues,
                            screenWidth = screenWidthPx,
                            screenHeight = screenHeightPx,
                            blurEnabled = layerBlurEnabled,
                            origin = if (launchingFromNotifications) appOpenOrigin else null
                        )
                ) {
                    if (screenState == ScreenState.Notifications || launchingFromNotifications) {
                        NotificationLayer(
                            notificationGroups = notificationGroups,
                            notificationAccessGranted = notificationAccessGranted,
                            revealedNotificationTarget = revealedNotificationTarget,
                            onRevealTargetChange = vm::setRevealedNotificationTarget,
                            onDismissToStack = { vm.setState(ScreenState.Stack) },
                            onToggleGroup = vm::toggleNotificationGroup,
                            onDismissGroup = vm::dismissNotificationGroup,
                            onDismissNotification = vm::dismissNotification,
                            onOpenNotification = { key, origin -> vm.openNotification(key, origin, ScreenState.Notifications) }
                        )
                    }
                }
            }

            if (screenState == ScreenState.Settings) {
                LauncherSettingsSheet(
                    currentLayout = layoutMode,
                    sideScreenEnabled = sideScreenEnabled,
                    blurEnabled = blurEnabled,
                    edgeBlurEnabled = edgeBlurEnabled,
                    lowResIcons = vm.lowResIcons.collectAsState().value,
                    legacyCircularIcons = legacyCircularIcons,
                    animationOverrideEnabled = animationOverrideEnabled,
                    splashIcon = splashIcon,
                    splashDelay = splashDelay,
                    honeycombCols = honeycombCols,
                    honeycombTopBlur = honeycombTopBlur,
                    honeycombBottomBlur = honeycombBottomBlur,
                    honeycombTopFade = honeycombTopFade,
                    honeycombBottomFade = honeycombBottomFade,
                    honeycombFastScrollOptimization = honeycombFastScrollOptimization,
                    showNotification = showNotification,
                    watchFaces = watchFaces,
                    selectedWatchFaceId = selectedWatchFaceId,
                    watchFaceLastError = watchFaceLastError,
                    onLayoutChange = { vm.setLayoutMode(it) },
                    onSideScreenToggle = { vm.setSideScreenEnabled(it) },
                    onBlurToggle = { vm.setBlurEnabled(it) },
                    onEdgeBlurToggle = { vm.setEdgeBlurEnabled(it) },
                    onLowResToggle = { vm.setLowResIcons(it) },
                    onLegacyCircularIconsToggle = { vm.setLegacyCircularIconsEnabled(it) },
                    onAnimationOverrideToggle = { vm.setAnimationOverrideEnabled(it) },
                    onSplashToggle = { vm.setSplashIcon(it) },
                    onSplashDelayChange = { vm.setSplashDelay(it) },
                    onHoneycombColsChange = { vm.setHoneycombCols(it) },
                    onHoneycombTopBlurChange = { vm.setHoneycombTopBlur(it) },
                    onHoneycombBottomBlurChange = { vm.setHoneycombBottomBlur(it) },
                    onHoneycombTopFadeChange = { vm.setHoneycombTopFade(it) },
                    onHoneycombBottomFadeChange = { vm.setHoneycombBottomFade(it) },
                    onHoneycombFastScrollOptimizationChange = { vm.setHoneycombFastScrollOptimization(it) },
                    onShowNotificationChange = { vm.setShowNotification(it) },
                    onWatchFaceSelect = { vm.selectWatchFace(it) },
                    onOpenWatchFaceSettings = { descriptor ->
                        if (descriptor.isBuiltin && descriptor.id in setOf(BUILT_IN_PHOTO_WATCHFACE_ID, BUILT_IN_VIDEO_WATCHFACE_ID)) {
                            context.startActivity(
                                Intent(context, InternalWatchFaceConfigActivity::class.java)
                                    .putExtra(EXTRA_INTERNAL_WATCHFACE_ID, descriptor.id)
                            )
                        } else if (!LunchWatchFaceRuntime.openSettings(context, descriptor)) {
                            Toast.makeText(context, "\u6CA1\u6709\u53EF\u7528\u7684\u8868\u76D8\u8BBE\u7F6E", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRefreshWatchFaces = { vm.refreshWatchFaces(force = true) },
                    onClearWatchFaceError = { vm.clearWatchFaceError() },
                    builtInPhotoPath = builtInPhotoPath,
                    builtInVideoPath = builtInVideoPath,
                    onResetDefaults = { vm.resetSettings() },
                    onDismiss = { vm.setState(ScreenState.Apps) }
                )
            }
        }

    }
}
