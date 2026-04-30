package com.flue.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dudu.wearlauncher.utils.ILog
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.common.bottomFisheyeScale
import com.flue.launcher.ui.input.flueRotaryScrollable
import com.flue.launcher.ui.input.requestFocusAfterFirstFrame
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.settings.WatchFaceSettingCard
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ThemeMode
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.util.applyFadeCloseTransition
import com.flue.launcher.util.applyFadeOpenTransition
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.InternalWatchFaceStorage
import com.flue.launcher.watchface.LunchWatchFaceRuntime
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

private const val ABOUT_VERSION = "beta1.4"
private const val COMMUNITY_GROUP_NUMBER = "1097162313"
private const val DIAGNOSTIC_LOG_DURATION_MS = 5 * 60 * 1000L
private const val COMMUNITY_GROUP_URL = "https://qun.qq.com/universal-share/share?ac=1&authKey=5CJC0tNLWsy3YWzGlPbqt%2F5kv%2BYZuJ8y8IVj%2B1UnIeLyR2DWR6QjWtM%2B4HXxH%2BKJ&busi_data=eyJncm91cENvZGUiOiIxMDk3MTYyMzEzIiwidG9rZW4iOiJlMGFBc1VpRXRROE1mS3J5SHgxRjFudmxXY0FuSnRQd2hOWWl6WllFMmlxNTErNWdadXJ5U1ozMmdzUSszaGNYIiwidWluIjoiMzUxMzkwMzA1NSJ9&data=YK52b80xUVwmcLWmSneKQMdVZodE4vTpsqmSb60ykXudbVCfa6AskMHxvqjhAjervNeE4exll-kQw5w-EifYOg&svctype=4&tempid=h5_group_info"
const val EXTRA_SETTINGS_DESTINATION = "settings_destination"
const val EXTRA_SETTINGS_RETURN_TO_FACE = "settings_return_to_face"
const val SETTINGS_DESTINATION_WATCH_FACES = "watch_faces"

enum class SettingsDestination {
    ROOT,
    WATCH_FACES,
    HIDDEN_APPS,
    ICON_PACKS,
    APPEARANCE,
    PERFORMANCE,
    TOOLS,
    ABOUT,
    DONATE
}

private data class SavedScrollPosition(
    val index: Int = 0,
    val offset: Int = 0
)

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecentsVisibility.apply(this)
        val initialDestination = when (intent.getStringExtra(EXTRA_SETTINGS_DESTINATION)) {
            SETTINGS_DESTINATION_WATCH_FACES -> SettingsDestination.WATCH_FACES
            else -> SettingsDestination.ROOT
        }
        val returnToFaceOnBack = intent.getBooleanExtra(EXTRA_SETTINGS_RETURN_TO_FACE, false)
        setContent {
            val viewModel: LauncherViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val uiStyle by viewModel.uiStyle.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle) {
                SettingsRootScreen(
                    onFinish = { finish() },
                    initialDestination = initialDestination,
                    returnToFaceOnBack = returnToFaceOnBack
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        applyFadeCloseTransition()
    }
}

@Composable
private fun SettingsRootScreen(
    onFinish: () -> Unit,
    initialDestination: SettingsDestination = SettingsDestination.ROOT,
    returnToFaceOnBack: Boolean = false
) {
    val context = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    val vm: LauncherViewModel = viewModel()
    val watchFaces by vm.availableWatchFaces.collectAsStateWithLifecycle()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsStateWithLifecycle()
    val selectedWatchFace by vm.selectedWatchFace.collectAsStateWithLifecycle()
    val allApps by vm.allApps.collectAsStateWithLifecycle()
    val hiddenApps by vm.hiddenApps.collectAsStateWithLifecycle()
    val availableIconPacks by vm.availableIconPacks.collectAsStateWithLifecycle()
    val selectedIconPackPackage by vm.selectedIconPackPackage.collectAsStateWithLifecycle()
    val watchFaceLastError by vm.watchFaceLastError.collectAsStateWithLifecycle()
    val layoutMode by vm.layoutMode.collectAsStateWithLifecycle()
    val sideScreenEnabled by vm.sideScreenEnabled.collectAsStateWithLifecycle()
    val sideScreenShortcutRows by vm.sideScreenShortcutRows.collectAsStateWithLifecycle()
    val sideScreenShortcutCols by vm.sideScreenShortcutCols.collectAsStateWithLifecycle()
    val blurEnabled by vm.blurEnabled.collectAsStateWithLifecycle()
    val edgeBlurEnabled by vm.edgeBlurEnabled.collectAsStateWithLifecycle()
    val lowResIcons by vm.lowResIcons.collectAsStateWithLifecycle()
    val animationOverrideEnabled by vm.animationOverrideEnabled.collectAsStateWithLifecycle()
    val splashIcon by vm.splashIcon.collectAsStateWithLifecycle()
    val splashDelay by vm.splashDelay.collectAsStateWithLifecycle()
    val honeycombCols by vm.honeycombCols.collectAsStateWithLifecycle()
    val honeycombTopBlur by vm.honeycombTopBlur.collectAsStateWithLifecycle()
    val honeycombBottomBlur by vm.honeycombBottomBlur.collectAsStateWithLifecycle()
    val honeycombTopFade by vm.honeycombTopFade.collectAsStateWithLifecycle()
    val honeycombBottomFade by vm.honeycombBottomFade.collectAsStateWithLifecycle()
    val honeycombFastScrollOptimization by vm.honeycombFastScrollOptimization.collectAsStateWithLifecycle()
    val appListFisheyeEnabled by vm.appListFisheyeEnabled.collectAsStateWithLifecycle()
    val fastFlowAnimationEnabled by vm.fastFlowAnimationEnabled.collectAsStateWithLifecycle()
    val twoToneIconsEnabled by vm.twoToneIconsEnabled.collectAsStateWithLifecycle()
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
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
    val launcherStyle = LauncherTheme.style
    val showNotification by vm.showNotification.collectAsStateWithLifecycle()
    val headerTime = rememberSettingsHeaderTime()
    val isZh = remember(context.resources.configuration) {
        context.resources.configuration.locales[0]?.language?.startsWith("zh") == true
    }
    val fontPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            val savedPath = InternalWatchFaceStorage.copyFont(context, uri)
            if (savedPath.isNullOrBlank()) {
                Toast.makeText(context, "保存字体失败", Toast.LENGTH_SHORT).show()
            } else {
                vm.setBuiltInWatchFaceFontPath(savedPath)
                Toast.makeText(context, "表盘字体已更新", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var destination by remember(initialDestination) { mutableStateOf(initialDestination) }
    var hiddenAppsDraft by remember { mutableStateOf(hiddenApps) }
    var hiddenAppsDirty by remember { mutableStateOf(false) }
    var donatePreviewResId by remember { mutableStateOf<Int?>(null) }
    var diagnosticLoggingEnabled by remember { mutableStateOf(ILog.isDiagnosticLoggingEnabled()) }
    val pageScrollPositions = remember { mutableStateMapOf<SettingsDestination, SavedScrollPosition>() }

    LaunchedEffect(hiddenApps) {
        if (!hiddenAppsDirty) {
            hiddenAppsDraft = hiddenApps
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshWatchFaces()
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

    val navigateTo: (SettingsDestination) -> Unit = { next ->
        if (destination == SettingsDestination.HIDDEN_APPS && next != SettingsDestination.HIDDEN_APPS) {
            commitHiddenAppsDraft()
        }
        destination = next
    }

    val handleBack: () -> Unit = {
        when (destination) {
            SettingsDestination.HIDDEN_APPS -> {
                commitHiddenAppsDraft()
                if (returnToFaceOnBack) onFinish() else navigateTo(SettingsDestination.ROOT)
            }
            SettingsDestination.DONATE -> navigateTo(SettingsDestination.ABOUT)
            SettingsDestination.ROOT -> onFinish()
            else -> if (returnToFaceOnBack) onFinish() else navigateTo(SettingsDestination.ROOT)
        }
    }

    BackHandler(enabled = destination != SettingsDestination.ROOT || returnToFaceOnBack) {
        handleBack()
    }

    val selectedIconPackLabel = availableIconPacks.firstOrNull { it.packageName == selectedIconPackPackage }?.label
    val scrollFor: (SettingsDestination) -> SavedScrollPosition = { target ->
        if (target == SettingsDestination.ROOT) {
            pageScrollPositions[target] ?: SavedScrollPosition()
        } else {
            SavedScrollPosition()
        }
    }
    val updateScroll: (SettingsDestination, Int, Int) -> Unit = { target, index, offset ->
        if (target == SettingsDestination.ROOT) {
            pageScrollPositions[target] = SavedScrollPosition(index = index, offset = offset)
        }
    }

    AnimatedContent(
        targetState = destination,
        transitionSpec = {
            (fadeIn(animationSpec = launcherStyle.pageMotionSpec) +
                scaleIn(
                    initialScale = if (uiStyle == UiStyle.MATERIAL_3) 0.98f else 0.985f,
                    animationSpec = launcherStyle.pageMotionSpec
                )) togetherWith
                (fadeOut(animationSpec = tween(140)) +
                    scaleOut(
                        targetScale = if (uiStyle == UiStyle.MATERIAL_3) 0.995f else 0.985f,
                        animationSpec = tween(140)
                    ))
        },
        label = "settings_destination"
    ) { currentDestination ->
        when (currentDestination) {
            SettingsDestination.ROOT -> SettingsPageScaffold(
                title = "Flue璁剧疆",
                onBack = {
                    commitHiddenAppsDraft()
                    onFinish()
                },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.ROOT).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.ROOT).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.ROOT, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                item("watchfaces") {
                    SettingsCategoryCard(
                        title = "表盘",
                        subtitle = if (uiStyle == UiStyle.MATERIAL_3) "MD3 主页与表盘图形已联动 · ${selectedWatchFace.displayName}" else selectedWatchFace.displayName,
                        onClick = { navigateTo(SettingsDestination.WATCH_FACES) },
                        scale = itemFisheye(listState, "watchfaces", screenCenterY, screenHeightPx)
                    )
                }
                item("appearance") {
                    SettingsCategoryCard(
                        title = "显示与外观",
                        subtitle = if (uiStyle == UiStyle.MATERIAL_3) "布局、材质层次与 MD3 动效" else "布局、模糊与启动图标",
                        onClick = { navigateTo(SettingsDestination.APPEARANCE) },
                        scale = itemFisheye(listState, "appearance", screenCenterY, screenHeightPx)
                    )
                }
                item("hidden_apps") {
                    SettingsCategoryCard(
                        title = "隐藏应用",
                        subtitle = "已隐藏 ${hiddenAppsDraft.size} 个应用",
                        onClick = { navigateTo(SettingsDestination.HIDDEN_APPS) },
                        scale = itemFisheye(listState, "hidden_apps", screenCenterY, screenHeightPx)
                    )
                }
                item("icon_packs") {
                    SettingsCategoryCard(
                        title = "图标包",
                        subtitle = selectedIconPackLabel ?: "系统默认图标",
                        onClick = { navigateTo(SettingsDestination.ICON_PACKS) },
                        scale = itemFisheye(listState, "icon_packs", screenCenterY, screenHeightPx)
                    )
                }
                item("performance") {
                    SettingsCategoryCard(
                        title = "性能与动画",
                        subtitle = "图标质量与动画控制",
                        onClick = { navigateTo(SettingsDestination.PERFORMANCE) },
                        scale = itemFisheye(listState, "performance", screenCenterY, screenHeightPx)
                    )
                }
                item("tools") {
                    SettingsCategoryCard(
                        title = "宸ュ叿",
                        subtitle = "瀵煎嚭鏃ュ織涓庢仮澶嶉粯璁?",
                        onClick = { navigateTo(SettingsDestination.TOOLS) },
                        scale = itemFisheye(listState, "tools", screenCenterY, screenHeightPx)
                    )
                }
                item("about") {
                    SettingsCategoryCard(
                        title = "鍏充簬",
                        subtitle = "Flue  $ABOUT_VERSION",
                        onClick = { navigateTo(SettingsDestination.ABOUT) },
                        scale = itemFisheye(listState, "about", screenCenterY, screenHeightPx)
                    )
                }
            }

            SettingsDestination.HIDDEN_APPS -> SettingsPageScaffold(
                title = "闅愯棌搴旂敤",
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.HIDDEN_APPS).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.HIDDEN_APPS).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.HIDDEN_APPS, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, visibleItemKeys ->
                item("hidden_summary") {
                    MessageCard(
                        text = "已隐藏 ${hiddenAppsDraft.size} 个应用",
                        background = WatchColors.SurfaceGlass,
                        onClick = {}
                    )
                }
                items(allApps.filterNot(AppInfo::isBuiltInSettingsEntry), key = { "app_${it.componentKey}" }) { app ->
                    SettingsSwitchRow(
                        title = app.label,
                        subtitle = app.packageName,
                        checked = hiddenAppsDraft.contains(app.componentKey) || hiddenAppsDraft.contains(app.packageName),
                        onToggle = {
                            hiddenAppsDraft = hiddenAppsDraft.toMutableSet().apply {
                                if (it) add(app.componentKey) else remove(app.componentKey)
                            }
                            hiddenAppsDirty = true
                        },
                        scale = itemFisheye(listState, "app_${app.componentKey}", screenCenterY, screenHeightPx),
                        leadingIcon = app.cachedIcon.takeIf { visibleItemKeys.contains("app_${app.componentKey}") },
                        reserveLeadingIconSpace = true
                    )
                }
            }

            SettingsDestination.ICON_PACKS -> SettingsPageScaffold(
                title = "图标包",
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.ICON_PACKS).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.ICON_PACKS).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.ICON_PACKS, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                item("icon_pack_default") {
                    SettingsChoiceRow(
                        title = "系统默认",
                        subtitle = "使用当前系统默认图标包",
                        selected = selectedIconPackPackage.isNullOrBlank(),
                        onClick = { vm.setIconPackPackage(null) },
                        scale = itemFisheye(listState, "icon_pack_default", screenCenterY, screenHeightPx)
                    )
                }
                items(availableIconPacks, key = { "iconpack_${it.packageName}" }) { pack ->
                    SettingsChoiceRow(
                        title = pack.label,
                        subtitle = "ADW 图标包",
                        selected = pack.packageName == selectedIconPackPackage,
                        onClick = { vm.setIconPackPackage(pack.packageName) },
                        scale = itemFisheye(listState, "iconpack_${pack.packageName}", screenCenterY, screenHeightPx)
                    )
                }
                item("icon_pack_refresh") {
                    ActionCard(
                        title = "刷新图标包",
                        subtitle = "重新扫描已安装的 ADW 图标包",
                        icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = WatchColors.ActiveCyan) },
                        onClick = { vm.refreshIconPacks() },
                        scale = itemFisheye(listState, "icon_pack_refresh", screenCenterY, screenHeightPx)
                    )
                }
            }

            SettingsDestination.WATCH_FACES -> SettingsPageScaffold(
            title = "琛ㄧ洏",
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.WATCH_FACES).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.WATCH_FACES).offset,
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
            item("watchface_font") {
                ActionCard(
                    title = "琛ㄧ洏瀛椾綋",
                    subtitle = builtInWatchFaceFontPath?.let { File(it).name } ?: "绯荤粺榛樿瀛椾綋",
                    scale = itemFisheye(listState, "watchface_font", screenCenterY, screenHeightPx),
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = WatchColors.ActiveCyan) },
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
                        title = "鎭㈠榛樿瀛椾綋",
                        subtitle = "娓呴櫎宸蹭繚瀛樼殑琛ㄧ洏瀛椾綋",
                        scale = itemFisheye(listState, "watchface_font_clear", screenCenterY, screenHeightPx),
                        icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = WatchColors.ActiveCyan) },
                        onClick = { vm.setBuiltInWatchFaceFontPath(null) }
                    )
                }
            }
            items(watchFaces, key = { it.id }) { descriptor ->
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
                    onOpenSettings = if (descriptor.supportsSettings) {
                        {
                            if (descriptor.isBuiltin && descriptor.id in setOf(BUILT_IN_PHOTO_WATCHFACE_ID, BUILT_IN_VIDEO_WATCHFACE_ID)) {
                                context.startActivity(
                                    Intent(context, InternalWatchFaceConfigActivity::class.java)
                                        .putExtra(EXTRA_INTERNAL_WATCHFACE_ID, descriptor.id)
                                )
                                (context as? Activity)?.applyFadeOpenTransition()
                            } else if (!LunchWatchFaceRuntime.openSettings(context, descriptor)) {
                                Toast.makeText(context, "娌℃湁鍙敤鐨勮〃鐩樿缃?", Toast.LENGTH_SHORT).show()
                            } else {
                                (context as? Activity)?.applyFadeOpenTransition()
                            }
                        }
                    } else {
                        null
                    },
                    scale = itemFisheye(listState, descriptor.id, screenCenterY, screenHeightPx)
                )
            }
            item("watchface_refresh") {
                ActionCard(
                    title = "閲嶆柊鎵弿琛ㄧ洏",
                    subtitle = "鍒锋柊宸插畨瑁呯殑 Lunch 鍏煎琛ㄧ洏",
                    scale = itemFisheye(listState, "watchface_refresh", screenCenterY, screenHeightPx),
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = WatchColors.ActiveCyan) },
                    onClick = { vm.refreshWatchFaces(force = true) }
                )
            }
        }

            SettingsDestination.APPEARANCE -> SettingsPageScaffold(
            title = "显示与外观",
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.APPEARANCE).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.APPEARANCE).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.APPEARANCE, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            item("layout_header") { SectionTitle("甯冨眬", itemFisheye(listState, "layout_header", screenCenterY, screenHeightPx)) }
            item("layout_honeycomb") {
                SettingsChoiceRow(
                    title = "铚傜獫甯冨眬",
                    subtitle = "Apple Watch 椋庢牸",
                    selected = layoutMode == LayoutMode.Honeycomb,
                    onClick = { vm.setLayoutMode(LayoutMode.Honeycomb) },
                    scale = itemFisheye(listState, "layout_honeycomb", screenCenterY, screenHeightPx)
                )
            }
            item("layout_list") {
                SettingsChoiceRow(
                    title = "鍒楄〃甯冨眬",
                    subtitle = "缁忓吀绾靛悜鍒楄〃",
                    selected = layoutMode == LayoutMode.List,
                    onClick = { vm.setLayoutMode(LayoutMode.List) },
                    scale = itemFisheye(listState, "layout_list", screenCenterY, screenHeightPx)
                )
            }
            item("display_header") {
                SectionTitle(if (isZh) "鏄剧ず璁剧疆" else "Display", itemFisheye(listState, "display_header", screenCenterY, screenHeightPx))
            }
            item("theme_mode_system") {
                SettingsChoiceRow(
                    title = if (isZh) "璺熼殢绯荤粺" else "System",
                    subtitle = if (isZh) "璺熼殢绯荤粺涓婚璁剧疆" else "Follow system theme setting",
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { vm.setThemeMode(ThemeMode.SYSTEM) },
                    scale = itemFisheye(listState, "theme_mode_system", screenCenterY, screenHeightPx)
                )
            }
            item("theme_mode_dark") {
                SettingsChoiceRow(
                    title = if (isZh) "娣辫壊妯″紡" else "Dark",
                    subtitle = if (isZh) "寮哄埗浣跨敤娣辫壊涓婚" else "Force dark theme",
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { vm.setThemeMode(ThemeMode.DARK) },
                    scale = itemFisheye(listState, "theme_mode_dark", screenCenterY, screenHeightPx)
                )
            }
            item("theme_mode_light") {
                SettingsChoiceRow(
                    title = if (isZh) "娴呰壊妯″紡" else "Light",
                    subtitle = if (isZh) "寮哄埗浣跨敤娴呰壊涓婚" else "Force light theme",
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { vm.setThemeMode(ThemeMode.LIGHT) },
                    scale = itemFisheye(listState, "theme_mode_light", screenCenterY, screenHeightPx)
                )
            }
            item("ui_style_header") {
                SectionTitle(if (isZh) "鐣岄潰椋庢牸" else "Interface Style", itemFisheye(listState, "ui_style_header", screenCenterY, screenHeightPx))
            }
            item("ui_style_apple_watch") {
                SettingsChoiceRow(
                    title = if (isZh) "Apple Watch 椋庢牸" else "Apple Watch",
                    subtitle = if (isZh) "鐜荤拑璐ㄦ劅銆佺揣鍑戝崱鐗囥€佸渾褰㈡壙杞戒笌鏇磋交宸х殑缂╂斁鍔ㄦ晥" else "Glass layers, compact cards, circular carriers, and tighter motion",
                    selected = uiStyle == UiStyle.APPLE_WATCH,
                    onClick = { vm.setUiStyle(UiStyle.APPLE_WATCH) },
                    scale = itemFisheye(listState, "ui_style_apple_watch", screenCenterY, screenHeightPx)
                )
            }
            item("ui_style_material_3") {
                SettingsChoiceRow(
                    title = if (isZh) "Material 3 椋庢牸" else "Material 3",
                    subtitle = if (isZh) "澶ц壊鍧楀钩閾恒€丮D3E 鍥惧舰銆佸垎灞傛洿鏄庣‘鐨勯椤典笌鍒楄〃" else "Bigger blocks, MD3E geometry, and stronger layered surfaces",
                    selected = uiStyle == UiStyle.MATERIAL_3,
                    onClick = { vm.setUiStyle(UiStyle.MATERIAL_3) },
                    scale = itemFisheye(listState, "ui_style_material_3", screenCenterY, screenHeightPx)
                )
            }
            item("side_screen_toggle") {
                SettingsSwitchRow(
                    title = if (isZh) "鍓竴灞?" else "Side Screen",
                    subtitle = if (isZh) "浠庤〃鐩樺彸婊戣繘鍏ュ揩鎹峰惎鍔ㄣ€佸皬缁勪欢鍜岄€氱煡娴?" else "Swipe right from the watch face to open quick launch, widgets, and notifications",
                    checked = sideScreenEnabled,
                    onToggle = vm::setSideScreenEnabled,
                    scale = itemFisheye(listState, "side_screen_toggle", screenCenterY, screenHeightPx)
                )
            }
            item("notification_center_toggle") {
                SettingsSwitchRow(
                    title = if (isZh) "鏄剧ず閫氱煡" else "Show Notifications",
                    subtitle = if (showNotification) {
                        if (isZh) "鎵€鏈夐€氱煡浼氱洿鎺ユ樉绀哄湪鍓竴灞忓皬缁勪欢涓嬫柟" else "Show all notifications directly below the widget area on the side screen"
                    } else {
                        if (isZh) "闅愯棌鍓竴灞忛噷鐨勯€氱煡鍒楄〃" else "Hide notifications from the side screen"
                    },
                    checked = showNotification,
                    enabled = sideScreenEnabled,
                    onToggle = vm::setShowNotification,
                    scale = itemFisheye(listState, "notification_center_toggle", screenCenterY, screenHeightPx)
                )
            }
            item("side_screen_shortcut_rows") {
                SettingsSliderRow(
                    title = if (isZh) "蹇嵎鍚姩琛屾暟" else "Quick Launch Rows",
                    value = sideScreenShortcutRows.toFloat(),
                    valueText = if (isZh) "$sideScreenShortcutRows 行" else "$sideScreenShortcutRows rows",
                    range = 1f..3f,
                    steps = 1,
                    onValueChange = { vm.setSideScreenShortcutRows(it.toInt()) },
                    scale = itemFisheye(listState, "side_screen_shortcut_rows", screenCenterY, screenHeightPx),
                    enabled = sideScreenEnabled
                )
            }
            item("side_screen_shortcut_cols") {
                SettingsSliderRow(
                    title = if (isZh) "蹇嵎鍚姩鍒楁暟" else "Quick Launch Columns",
                    value = sideScreenShortcutCols.toFloat(),
                    valueText = if (isZh) "$sideScreenShortcutCols 列" else "$sideScreenShortcutCols columns",
                    range = 2f..4f,
                    steps = 1,
                    onValueChange = { vm.setSideScreenShortcutCols(it.toInt()) },
                    scale = itemFisheye(listState, "side_screen_shortcut_cols", screenCenterY, screenHeightPx),
                    enabled = sideScreenEnabled
                )
            }
            item("blur_toggle") {
                SettingsSwitchRow(
                    title = if (isZh) "楂樼骇鏉愯川" else "Blur",
                    subtitle = if (isZh) "建议 Android 12+ 设备开启" else "Enable blur on supported Android versions",
                    checked = blurEnabled,
                    onToggle = vm::setBlurEnabled,
                    scale = itemFisheye(listState, "blur_toggle", screenCenterY, screenHeightPx)
                )
            }
            item("edge_blur_toggle") {
                SettingsSwitchRow(
                    title = if (isZh) "杈圭紭妯＄硦锛堝疄楠屾€э級" else "Edge Blur (Experimental)",
                    subtitle = if (isZh) "鍦ㄩ《閮ㄥ拰搴曢儴杈圭紭澧炲姞妯＄硦锛屽苟澧炲姞灏戦噺bug" else "Apply extra blur near the top and bottom edges",
                    checked = edgeBlurEnabled,
                    onToggle = vm::setEdgeBlurEnabled,
                    scale = itemFisheye(listState, "edge_blur_toggle", screenCenterY, screenHeightPx)
                )
            }
            if (destination == SettingsDestination.PERFORMANCE) item("app_list_fisheye") {
                SettingsSwitchRow(
                    title = if (isZh) "楸肩溂妯″紡" else "Fisheye Mode",
                    subtitle = if (isZh) "开启后距离屏幕中心较远的图标会缩小并降低透明度" else "Scale and fade icons farther from the screen center",
                    checked = appListFisheyeEnabled,
                    onToggle = vm::setAppListFisheyeEnabled,
                    scale = itemFisheye(listState, "app_list_fisheye", screenCenterY, screenHeightPx)
                )
            }
            item("honeycomb_cols") {
                SettingsSliderRow(
                    title = "铚傜獫鍒楁暟",
                    value = honeycombCols.toFloat(),
                    valueText = "$honeycombCols 列",
                    range = 3f..6f,
                    steps = 2,
                    onValueChange = { vm.setHoneycombCols(it.toInt()) },
                    scale = itemFisheye(listState, "honeycomb_cols", screenCenterY, screenHeightPx)
                )
            }
            item("top_blur") {
                SettingsSliderRow(
                    title = "椤堕儴妯＄硦鍗婂緞",
                    value = honeycombTopBlur.toFloat(),
                    valueText = "$honeycombTopBlur dp",
                    range = 0f..48f,
                    steps = 11,
                    onValueChange = { vm.setHoneycombTopBlur(it.toInt()) },
                    enabled = blurEnabled,
                    scale = itemFisheye(listState, "top_blur", screenCenterY, screenHeightPx)
                )
            }
            item("bottom_blur") {
                SettingsSliderRow(
                    title = "搴曢儴妯＄硦鍗婂緞",
                    value = honeycombBottomBlur.toFloat(),
                    valueText = "$honeycombBottomBlur dp",
                    range = 0f..48f,
                    steps = 11,
                    onValueChange = { vm.setHoneycombBottomBlur(it.toInt()) },
                    enabled = blurEnabled,
                    scale = itemFisheye(listState, "bottom_blur", screenCenterY, screenHeightPx)
                )
            }
            item("top_fade") {
                SettingsSliderRow(
                    title = "椤堕儴娓愰殣鑼冨洿",
                    value = honeycombTopFade.toFloat(),
                    valueText = "$honeycombTopFade dp",
                    range = 0f..160f,
                    steps = 15,
                    onValueChange = { vm.setHoneycombTopFade(it.toInt()) },
                    scale = itemFisheye(listState, "top_fade", screenCenterY, screenHeightPx)
                )
            }
            item("bottom_fade") {
                SettingsSliderRow(
                    title = "搴曢儴娓愰殣鑼冨洿",
                    value = honeycombBottomFade.toFloat(),
                    valueText = "$honeycombBottomFade dp",
                    range = 0f..160f,
                    steps = 15,
                    onValueChange = { vm.setHoneycombBottomFade(it.toInt()) },
                    scale = itemFisheye(listState, "bottom_fade", screenCenterY, screenHeightPx)
                )
            }
            item("launch_header") { SectionTitle("鍚姩", itemFisheye(listState, "launch_header", screenCenterY, screenHeightPx)) }
            item("splash_toggle") {
                SettingsSwitchRow(
                    title = "鍚姩閬僵",
                    subtitle = "打开应用时显示图标过渡",
                    checked = splashIcon,
                    onToggle = { vm.setSplashIcon(it) },
                    scale = itemFisheye(listState, "splash_toggle", screenCenterY, screenHeightPx)
                )
            }
            if (splashIcon) {
                item("splash_delay") {
                    SettingsSliderRow(
                        title = "閬僵鏃堕暱",
                        value = splashDelay.toFloat(),
                        valueText = "${splashDelay} ms",
                        range = 300f..1500f,
                        steps = 11,
                        onValueChange = { vm.setSplashDelay(it.toInt()) },
                        scale = itemFisheye(listState, "splash_delay", screenCenterY, screenHeightPx)
                    )
                }
            }
        }

            SettingsDestination.PERFORMANCE -> SettingsPageScaffold(
            title = "性能与动画",
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.PERFORMANCE).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.PERFORMANCE).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.PERFORMANCE, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            item("low_res") {
                SettingsSwitchRow(
                    title = "浣庡垎杈ㄧ巼鍥炬爣",
                    subtitle = "闄嶄綆鍥炬爣寮€閿€浠ユ彁鍗囨祦鐣呭害",
                    checked = lowResIcons,
                    onToggle = { vm.setLowResIcons(it) },
                    scale = itemFisheye(listState, "low_res", screenCenterY, screenHeightPx)
                )
            }
            item("honeycomb_fast_scroll_opt") {
                SettingsSwitchRow(
                    title = "滑动流畅度优化",
                    subtitle = "开启后大幅改善滑动流畅度",
                    checked = honeycombFastScrollOptimization,
                    onToggle = { vm.setHoneycombFastScrollOptimization(it) },
                    scale = itemFisheye(listState, "honeycomb_fast_scroll_opt", screenCenterY, screenHeightPx)
                )
            }
            item("app_list_fisheye") {
                SettingsSwitchRow(
                    title = if (isZh) "鱼眼模式" else "Fisheye Mode",
                    subtitle = if (isZh) "开启后距离屏幕中心较远的图标会缩小并降低透明度" else "Scale and fade icons farther from the screen center",
                    checked = appListFisheyeEnabled,
                    onToggle = vm::setAppListFisheyeEnabled,
                    scale = itemFisheye(listState, "app_list_fisheye", screenCenterY, screenHeightPx)
                )
            }
            item("fast_flow_animation") {
                SettingsSwitchRow(
                    title = if (isZh) "快速流动动画" else "Fast Flow Animation",
                    subtitle = if (isZh) "寮€鍚悗瓒婅繙绂诲睆骞曚腑蹇冿紝婊氬姩閫熷害瓒婂揩" else "Icons farther from center move faster while scrolling",
                    checked = fastFlowAnimationEnabled,
                    onToggle = vm::setFastFlowAnimationEnabled,
                    scale = itemFisheye(listState, "fast_flow_animation", screenCenterY, screenHeightPx)
                )
            }
            item("two_tone_icons") {
                SettingsSwitchRow(
                    title = if (isZh) "鍙岃壊鍥炬爣" else "Two-Tone Icons",
                    subtitle = if (isZh) "AdaptiveIcon 浣跨敤鍗曡壊灞傦紝鍏朵粬鍥炬爣杞伆搴﹀悗濉壊" else "Use adaptive monochrome when available, otherwise grayscale + tint",
                    checked = twoToneIconsEnabled,
                    onToggle = vm::setTwoToneIconsEnabled,
                    scale = itemFisheye(listState, "two_tone_icons", screenCenterY, screenHeightPx)
                )
            }
            item("show_step_count") {
                SettingsSwitchRow(
                    title = if (isZh) "鏄剧ず姝ユ暟" else "Show Step Count",
                    subtitle = if (isZh) "在侧屏底部电池右侧显示步数" else "Show steps to the right of battery on side screen",
                    checked = showStepCount,
                    onToggle = vm::setShowStepCountEnabled,
                    scale = itemFisheye(listState, "show_step_count", screenCenterY, screenHeightPx)
                )
            }
            item("anim_override") {
                SettingsSwitchRow(
                    title = "妗岄潰杩斿洖鍔ㄧ敾",
                    subtitle = "鍚敤娓愰殣杩囨浮",
                    checked = animationOverrideEnabled,
                    onToggle = { vm.setAnimationOverrideEnabled(it) },
                    scale = itemFisheye(listState, "anim_override", screenCenterY, screenHeightPx)
                )
            }
            item("hide_from_recents") {
                SettingsSwitchRow(
                    title = if (isZh) "闅愯棌鍚庡彴鍗＄墖" else "Hide Recents Card",
                    subtitle = if (isZh) "从最近任务中隐藏 Flue 的后台卡片" else "Hide Flue from the system recents screen",
                    checked = hideFromRecents,
                    onToggle = vm::setHideFromRecents,
                    scale = itemFisheye(listState, "hide_from_recents", screenCenterY, screenHeightPx)
                )
            }
            item("builtin_manager_thumbnails") {
                SettingsSwitchRow(
                    title = "鍐呯疆绠＄悊鍣ㄧ缉鐣ュ浘",
                    subtitle = "在图片/视频列表左侧显示预览图",
                    checked = builtInManagerThumbnails,
                    onToggle = { vm.setBuiltInManagerThumbnails(it) },
                    scale = itemFisheye(listState, "builtin_manager_thumbnails", screenCenterY, screenHeightPx)
                )
            }
        }

            SettingsDestination.TOOLS -> SettingsPageScaffold(
            title = "宸ュ叿",
            onBack = { handleBack() },
            headerTime = headerTime,
            initialFirstVisibleItemIndex = scrollFor(SettingsDestination.TOOLS).index,
            initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.TOOLS).offset,
            onScrollChanged = { index, offset -> updateScroll(SettingsDestination.TOOLS, index, offset) }
        ) { listState, screenCenterY, screenHeightPx, _ ->
            item("export_log") {
                ActionCard(
                    title = "瀵煎嚭鏃ュ織",
                    subtitle = "导出 Flue 摘要、崩溃文件和最近 500 行系统日志",
                    onClick = { settingsScope.launch { exportLog(context) } },
                    scale = itemFisheye(listState, "export_log", screenCenterY, screenHeightPx)
                )
            }
            item("diagnostic_logging") {
                SettingsSwitchRow(
                    title = "诊断日志模式",
                    subtitle = if (diagnosticLoggingEnabled) "已开启，5 分钟后自动关闭" else "默认关闭，开启后临时允许 release 输出诊断日志",
                    checked = diagnosticLoggingEnabled,
                    onToggle = { enabled ->
                        diagnosticLoggingEnabled = enabled
                        ILog.setDiagnosticLoggingEnabled(context, enabled, DIAGNOSTIC_LOG_DURATION_MS)
                    },
                    scale = itemFisheye(listState, "diagnostic_logging", screenCenterY, screenHeightPx)
                )
            }
            item("reset_defaults") {
                ActionCard(
                    title = "鎭㈠榛樿璁剧疆",
                    subtitle = "閲嶇疆妗岄潰澶栬涓庢€ц兘閫夐」",
                    onClick = { vm.resetSettings() },
                    scale = itemFisheye(listState, "reset_defaults", screenCenterY, screenHeightPx)
                )
            }
            }

            SettingsDestination.ABOUT -> SettingsPageScaffold(
                title = "鍏充簬",
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.ABOUT).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.ABOUT).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.ABOUT, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                item("about_card") {
                    AboutCard(
                        onDonateClick = { navigateTo(SettingsDestination.DONATE) },
                        scale = itemFisheye(listState, "about_card", screenCenterY, screenHeightPx)
                    )
                }
            }
            SettingsDestination.DONATE -> SettingsPageScaffold(
                title = "鎹愯禒鏀寔",
                onBack = { handleBack() },
                headerTime = headerTime,
                initialFirstVisibleItemIndex = scrollFor(SettingsDestination.DONATE).index,
                initialFirstVisibleItemScrollOffset = scrollFor(SettingsDestination.DONATE).offset,
                onScrollChanged = { index, offset -> updateScroll(SettingsDestination.DONATE, index, offset) }
            ) { listState, screenCenterY, screenHeightPx, _ ->
                item("donate_tip") {
                    MessageCard(
                        text = "鐪熺殑寰堟劅璋綘鑳芥敮鎸丗lue寮€鍙戯紒鐐瑰紑涓嬫柟浜岀淮鐮佸彲鍏ㄥ睆鏌ョ湅",
                        background = Color(0xFF1A2233),
                        onClick = {}
                    )
                }
                item("donate_wechat") {
                    DonateMethodCard(
                        title = "寰俊",
                        subtitle = "点击预览图全屏查看",
                        resId = R.drawable.donate_wechat,
                        scale = itemFisheye(listState, "donate_wechat", screenCenterY, screenHeightPx),
                        onPreviewClick = { donatePreviewResId = R.drawable.donate_wechat }
                    )
                }
                item("donate_alipay") {
                    DonateMethodCard(
                        title = "支付宝",
                        subtitle = "点击预览图全屏查看",
                        resId = R.drawable.donate_alipay,
                        scale = itemFisheye(listState, "donate_alipay", screenCenterY, screenHeightPx),
                        onPreviewClick = { donatePreviewResId = R.drawable.donate_alipay }
                    )
                }
            }
        }
    }

    donatePreviewResId?.let { resId ->
        DonatePreviewDialog(resId = resId, onDismiss = { donatePreviewResId = null })
    }
}

@Composable
private fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    headerTime: String,
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    onScrollChanged: (Int, Int) -> Unit = { _, _ -> },
    content: LazyListScope.(LazyListState, Float, Float, Set<Any>) -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset
    )
    val focusRequester = remember { FocusRequester() }
    val overscroll = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val visibleItemKeys by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key }.toSet()
        }
    }

    val nestedScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source != NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                val atBottom = lastVisible != null &&
                    lastVisible.index >= listState.layoutInfo.totalItemsCount - 1 &&
                    lastVisible.offset + lastVisible.size <= listState.layoutInfo.viewportEndOffset
                if (available.y > 0f && atTop) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y * 0.35f).coerceAtMost(140f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (available.y < 0f && atBottom) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y * 0.35f).coerceAtLeast(-140f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (overscroll.value > 0f && available.y < 0f) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y).coerceAtLeast(0f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (overscroll.value < 0f && available.y > 0f) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y).coerceAtMost(0f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll.value != 0f) {
                    overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> onScrollChanged(index, offset) }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocusAfterFirstFrame()
    }

    val rotaryScrollMultiplier = 1.18f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(launcherStyle.screenBackground)
    ) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        val screenCenterY = screenHeightPx / 2f

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .flueRotaryScrollable(focusRequester, rotaryScrollMultiplier) { rotaryDelta ->
                    scope.launch {
                        listState.scrollBy(-rotaryDelta)
                    }
                }
                .nestedScroll(nestedScrollConnection)
                .graphicsLayer { translationY = overscroll.value }
                .padding(horizontal = 16.dp, vertical = 18.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HeaderBackButton(onClick = onBack)
                    Text(
                        text = headerTime,
                        color = launcherStyle.topBarTextColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            item {
                Text(
                    text = title,
                    color = launcherStyle.accentColor,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            content(listState, screenCenterY, screenHeightPx, visibleItemKeys)
            item("tail_spacer") {
                Spacer(modifier = Modifier.height(56.dp))
            }
        }
    }
}

@Composable
private fun HeaderBackButton(onClick: () -> Unit) {
    val launcherStyle = LauncherTheme.style
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "header_back_scale")
    Box(
        modifier = Modifier
            .size(42.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(launcherStyle.topBarChipColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = launcherStyle.topBarTextColor)
    }
}

@Composable
private fun SettingsCategoryCard(title: String, subtitle: String, onClick: () -> Unit, scale: Float) {
    val launcherStyle = LauncherTheme.style
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale by animateFloatAsState(
        if (pressed) 0.958f else 1f,
        animationSpec = launcherStyle.pressScaleSpec,
        label = "settings_category_scale"
    )
    val background by animateColorAsState(
        if (pressed) launcherStyle.pressedCardColor else launcherStyle.cardColor,
        label = "settings_category_bg"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale
                scaleY = scale * pressedScale
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clip(launcherStyle.cardShape)
            .background(background)
            .border(1.dp, launcherStyle.outlineColor, launcherStyle.cardShape)
            .instantPressGesture(pressedState, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp)
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
    Text(
        text = text,
        color = launcherStyle.secondaryTextColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(top = 4.dp, start = 4.dp, bottom = 2.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale.coerceIn(0.55f, 1f)
            }
    )
}

@Composable
private fun rememberPressedState(): MutableState<Boolean> = remember { mutableStateOf(false) }

private fun Modifier.instantPressGesture(
    pressedState: MutableState<Boolean>,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    if (!enabled) return this
    return pointerInput(onClick, enabled) {
        detectTapGestures(
            onPress = {
                pressedState.value = true
                val released = tryAwaitRelease()
                pressedState.value = false
                if (released) onClick()
            }
        )
    }
}

@Composable
private fun MessageCard(text: String, background: Color, onClick: () -> Unit) {
    val launcherStyle = LauncherTheme.style
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(launcherStyle.compactShape)
            .background(background)
            .clickable(onClick = onClick)
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
    scale: Float
) {
    val launcherStyle = LauncherTheme.style
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale by animateFloatAsState(
        if (pressed) 0.964f else 1f,
        animationSpec = launcherStyle.pressScaleSpec,
        label = "choice_row_scale"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale
                scaleY = scale * pressedScale
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clip(launcherStyle.compactShape)
            .background(
                when {
                    pressed && selected -> launcherStyle.selectedCardColor.copy(alpha = 0.80f)
                    pressed -> launcherStyle.pressedCardColor
                    selected -> launcherStyle.selectedCardColor
                    else -> launcherStyle.cardColor
                }
            )
            .border(1.dp, launcherStyle.outlineColor, launcherStyle.compactShape)
            .instantPressGesture(pressedState, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
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
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = launcherStyle.accentColor)
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
    reserveLeadingIconSpace: Boolean = false
) {
    val launcherStyle = LauncherTheme.style
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale by animateFloatAsState(
        if (pressed) 0.958f else 1f,
        animationSpec = launcherStyle.pressScaleSpec,
        label = "switch_row_scale"
    )
    val trackColor by animateColorAsState(
        when {
            !enabled -> Color(0xFF2A2A2A)
            checked -> launcherStyle.positiveColor
            else -> launcherStyle.secondaryTextColor.copy(alpha = 0.45f)
        },
        label = "switch_track_color"
    )
    val knobOffset by animateDpAsState(
        if (checked) launcherStyle.switchThumbTravel.dp else 2.dp,
        animationSpec = spring(stiffness = 760f, dampingRatio = 0.82f),
        label = "switch_knob_offset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale
                scaleY = scale * pressedScale
                alpha = if (enabled) scale.coerceIn(0.55f, 1f) else 0.5f
            }
            .clip(launcherStyle.compactShape)
            .background(if (pressed) launcherStyle.pressedCardColor else launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, launcherStyle.compactShape)
            .instantPressGesture(pressedState, enabled = enabled) { onToggle(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null || reserveLeadingIconSpace) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (leadingIcon != null) 0f else 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (leadingIcon != null) {
                            Image(
                                bitmap = leadingIcon,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
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
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(trackColor)
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = knobOffset, top = 3.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun SettingsSliderRow(
    title: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    scale: Float,
    enabled: Boolean = true
) {
    val launcherStyle = LauncherTheme.style
    var localValue by remember(title) { mutableFloatStateOf(value) }
    LaunchedEffect(value) { localValue = value }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) scale.coerceIn(0.55f, 1f) else 0.5f
            }
            .clip(launcherStyle.compactShape)
            .background(launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, launcherStyle.compactShape)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            Text(title, color = launcherStyle.titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(valueText, color = launcherStyle.secondaryTextColor, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = localValue,
                onValueChange = {
                    localValue = it
                    onValueChange(it)
                },
                valueRange = range,
                steps = steps,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = launcherStyle.accentColor,
                    activeTrackColor = launcherStyle.accentColor
                )
            )
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    scale: Float
) {
    val launcherStyle = LauncherTheme.style
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        animationSpec = launcherStyle.pressScaleSpec,
        label = "action_card_scale"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale
                scaleY = scale * pressedScale
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clip(launcherStyle.compactShape)
            .background(if (pressed) launcherStyle.pressedCardColor else launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, launcherStyle.compactShape)
            .instantPressGesture(pressedState, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
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
private fun AboutCard(scale: Float, onDonateClick: () -> Unit) {
    val launcherStyle = LauncherTheme.style
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clip(launcherStyle.cardShape)
            .background(launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, launcherStyle.cardShape)
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(98.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_round),
                    contentDescription = null,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(22.dp))
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text("Flue", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(ABOUT_VERSION, color = WatchColors.TextTertiary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.author_avatar),
                    contentDescription = null,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("柚子柚子皮", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("QQ：513903055", color = WatchColors.TextTertiary, fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("C", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("cjryrx", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("QQ：124018406", color = WatchColors.TextTertiary, fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            ActionCard(
                title = "加入交流群获取更新",
                subtitle = "缇ゅ彿 $COMMUNITY_GROUP_NUMBER",
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse(COMMUNITY_GROUP_URL)
                        )
                    )
                },
                scale = 1f
            )
            Spacer(modifier = Modifier.height(10.dp))
            ActionCard(
                title = "鎹愯禒鏀寔",
                subtitle = "微信 / 支付宝",
                onClick = onDonateClick,
                scale = 1f
            )
            Spacer(modifier = Modifier.height(10.dp))
            ActionCard(
                title = "感谢以下开源项目",
                subtitle = "dudu-Dev0/Lunch",
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/dudu-Dev0/Lunch")
                        )
                    )
                },
                scale = 1f
            )
        }
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
    val pressedScale by animateFloatAsState(
        if (pressed) 0.962f else 1f,
        animationSpec = spring(stiffness = 820f, dampingRatio = 0.74f),
        label = "donate_card_scale"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressedScale
                scaleY = scale * pressedScale
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clip(RoundedCornerShape(24.dp))
            .background(launcherStyle.cardColor)
            .border(1.dp, launcherStyle.outlineColor, RoundedCornerShape(24.dp))
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
                    .clip(RoundedCornerShape(22.dp))
                    .background(launcherStyle.pressedCardColor)
            )
        }
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
            usePlatformDefaultWidth = false
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

@Composable
private fun itemFisheye(
    listState: LazyListState,
    key: String,
    screenCenterY: Float,
    screenHeight: Float
): Float {
    return bottomFisheyeScale(listState, key, screenCenterY, screenHeight)
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
                        appendLine("diagnosticLoggingEnabled=${ILog.isDiagnosticLoggingEnabled()}")
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
                "瀵煎嚭鏃ュ織"
            )
        )
    } catch (_: Exception) {
        Toast.makeText(context, "瀵煎嚭澶辫触", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun rememberSettingsHeaderTime(): String {
    var time by remember { mutableStateOf("--:--") }
    LaunchedEffect(Unit) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            time = formatter.format(Date())
            delay(30_000)
        }
    }
    return time
}
