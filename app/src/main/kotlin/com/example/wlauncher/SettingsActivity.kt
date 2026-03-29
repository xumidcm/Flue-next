package com.flue.launcher

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.settings.WatchFaceSettingCard
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.LunchWatchFaceRuntime
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import java.util.Locale

private const val ABOUT_VERSION = "beta0.5"

enum class SettingsDestination {
    ROOT,
    WATCH_FACES,
    HIDDEN_APPS,
    ICON_PACKS,
    APPEARANCE,
    PERFORMANCE,
    TOOLS,
    ABOUT
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchLauncherTheme {
                SettingsRootScreen(onFinish = { finish() })
            }
        }
    }
}

@Composable
private fun SettingsRootScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val vm: LauncherViewModel = viewModel()
    val watchFaces by vm.availableWatchFaces.collectAsState()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsState()
    val selectedWatchFace by vm.selectedWatchFace.collectAsState()
    val allApps by vm.allApps.collectAsState()
    val hiddenApps by vm.hiddenApps.collectAsState()
    val availableIconPacks by vm.availableIconPacks.collectAsState()
    val selectedIconPackPackage by vm.selectedIconPackPackage.collectAsState()
    val watchFaceLastError by vm.watchFaceLastError.collectAsState()
    val layoutMode by vm.layoutMode.collectAsState()
    val blurEnabled by vm.blurEnabled.collectAsState()
    val edgeBlurEnabled by vm.edgeBlurEnabled.collectAsState()
    val lowResIcons by vm.lowResIcons.collectAsState()
    val animationOverrideEnabled by vm.animationOverrideEnabled.collectAsState()
    val splashIcon by vm.splashIcon.collectAsState()
    val splashDelay by vm.splashDelay.collectAsState()
    val honeycombCols by vm.honeycombCols.collectAsState()
    val honeycombTopBlur by vm.honeycombTopBlur.collectAsState()
    val honeycombBottomBlur by vm.honeycombBottomBlur.collectAsState()
    val honeycombTopFade by vm.honeycombTopFade.collectAsState()
    val honeycombBottomFade by vm.honeycombBottomFade.collectAsState()
    val builtInPhotoPath by vm.builtInPhotoPath.collectAsState()
    val builtInVideoPath by vm.builtInVideoPath.collectAsState()
    val builtInPhotoClockPosition by vm.builtInPhotoClockPosition.collectAsState()
    val builtInVideoClockPosition by vm.builtInVideoClockPosition.collectAsState()
    val builtInPhotoClockSize by vm.builtInPhotoClockSize.collectAsState()
    val builtInVideoClockSize by vm.builtInVideoClockSize.collectAsState()
    val builtInVideoFillScreen by vm.builtInVideoFillScreen.collectAsState()
    val headerTime = rememberSettingsHeaderTime()

    var destination by remember { mutableStateOf(SettingsDestination.ROOT) }

    LaunchedEffect(Unit) {
        vm.refreshWatchFaces()
    }

    BackHandler(enabled = destination != SettingsDestination.ROOT) {
        destination = SettingsDestination.ROOT
    }

    val selectedIconPackLabel = availableIconPacks.firstOrNull { it.packageName == selectedIconPackPackage }?.label

    AnimatedContent(
        targetState = destination,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.985f, animationSpec = tween(220))) togetherWith
                (fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.985f, animationSpec = tween(140)))
        },
        label = "settings_destination"
    ) { currentDestination ->
        when (currentDestination) {
            SettingsDestination.ROOT -> SettingsPageScaffold(
                title = "\u684c\u9762\u8bbe\u7f6e",
                onBack = onFinish,
                headerTime = headerTime
            ) { listState, screenCenterY, screenHeightPx ->
                item("watchfaces") {
                    SettingsCategoryCard(
                        title = "\u8868\u76d8",
                        subtitle = selectedWatchFace.displayName,
                        onClick = { destination = SettingsDestination.WATCH_FACES },
                        scale = itemFisheye(listState, "watchfaces", screenCenterY, screenHeightPx)
                    )
                }
                item("appearance") {
                    SettingsCategoryCard(
                        title = "\u663e\u793a\u4e0e\u5916\u89c2",
                        subtitle = "\u5e03\u5c40\u3001\u6a21\u7cca\u4e0e\u542f\u52a8\u56fe\u6807",
                        onClick = { destination = SettingsDestination.APPEARANCE },
                        scale = itemFisheye(listState, "appearance", screenCenterY, screenHeightPx)
                    )
                }
                item("hidden_apps") {
                    SettingsCategoryCard(
                        title = "\u9690\u85cf\u5e94\u7528",
                        subtitle = "\u5df2\u9690\u85cf ${hiddenApps.size} \u4e2a\u5e94\u7528",
                        onClick = { destination = SettingsDestination.HIDDEN_APPS },
                        scale = itemFisheye(listState, "hidden_apps", screenCenterY, screenHeightPx)
                    )
                }
                item("icon_packs") {
                    SettingsCategoryCard(
                        title = "\u56fe\u6807\u5305",
                        subtitle = selectedIconPackLabel ?: "\u7cfb\u7edf\u9ed8\u8ba4\u56fe\u6807",
                        onClick = { destination = SettingsDestination.ICON_PACKS },
                        scale = itemFisheye(listState, "icon_packs", screenCenterY, screenHeightPx)
                    )
                }
                item("performance") {
                    SettingsCategoryCard(
                        title = "\u6027\u80fd\u4e0e\u52a8\u753b",
                        subtitle = "\u56fe\u6807\u8d28\u91cf\u4e0e\u52a8\u753b\u63a7\u5236",
                        onClick = { destination = SettingsDestination.PERFORMANCE },
                        scale = itemFisheye(listState, "performance", screenCenterY, screenHeightPx)
                    )
                }
                item("tools") {
                    SettingsCategoryCard(
                        title = "\u5de5\u5177",
                        subtitle = "\u5bfc\u51fa\u65e5\u5fd7\u4e0e\u6062\u590d\u9ed8\u8ba4",
                        onClick = { destination = SettingsDestination.TOOLS },
                        scale = itemFisheye(listState, "tools", screenCenterY, screenHeightPx)
                    )
                }
                item("about") {
                    SettingsCategoryCard(
                        title = "\u5173\u4e8e",
                        subtitle = "Flue  $ABOUT_VERSION",
                        onClick = { destination = SettingsDestination.ABOUT },
                        scale = itemFisheye(listState, "about", screenCenterY, screenHeightPx)
                    )
                }
            }

            SettingsDestination.HIDDEN_APPS -> SettingsPageScaffold(
                title = "\u9690\u85cf\u5e94\u7528",
                onBack = { destination = SettingsDestination.ROOT },
                headerTime = headerTime
            ) { listState, screenCenterY, screenHeightPx ->
                item("hidden_summary") {
                    MessageCard(
                        text = "\u5df2\u9690\u85cf ${hiddenApps.size} \u4e2a\u5e94\u7528",
                        background = WatchColors.SurfaceGlass,
                        onClick = {}
                    )
                }
                items(allApps, key = { "app_${it.componentKey}" }) { app ->
                    SettingsSwitchRow(
                        title = app.label,
                        subtitle = app.packageName,
                        checked = hiddenApps.contains(app.componentKey) || hiddenApps.contains(app.packageName),
                        onToggle = { vm.setAppHidden(app.componentKey, it) },
                        scale = itemFisheye(listState, "app_${app.componentKey}", screenCenterY, screenHeightPx),
                        leadingIcon = app.cachedIcon
                    )
                }
            }

            SettingsDestination.ICON_PACKS -> SettingsPageScaffold(
                title = "\u56fe\u6807\u5305",
                onBack = { destination = SettingsDestination.ROOT },
                headerTime = headerTime
            ) { listState, screenCenterY, screenHeightPx ->
                item("icon_pack_default") {
                    SettingsChoiceRow(
                        title = "\u7cfb\u7edf\u9ed8\u8ba4",
                        subtitle = "\u4f7f\u7528 Flue \u5f53\u524d\u5e94\u7528\u56fe\u6807",
                        selected = selectedIconPackPackage.isNullOrBlank(),
                        onClick = { vm.setIconPackPackage(null) },
                        scale = itemFisheye(listState, "icon_pack_default", screenCenterY, screenHeightPx)
                    )
                }
                items(availableIconPacks, key = { "iconpack_${it.packageName}" }) { pack ->
                    SettingsChoiceRow(
                        title = pack.label,
                        subtitle = "ADW Icon Pack Standard",
                        selected = pack.packageName == selectedIconPackPackage,
                        onClick = { vm.setIconPackPackage(pack.packageName) },
                        scale = itemFisheye(listState, "iconpack_${pack.packageName}", screenCenterY, screenHeightPx)
                    )
                }
                item("icon_pack_refresh") {
                    ActionCard(
                        title = "\u5237\u65b0\u56fe\u6807\u5305",
                        subtitle = "\u91cd\u65b0\u626b\u63cf\u5df2\u5b89\u88c5\u7684 ADW \u56fe\u6807\u5305",
                        icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = WatchColors.ActiveCyan) },
                        onClick = { vm.refreshIconPacks() },
                        scale = itemFisheye(listState, "icon_pack_refresh", screenCenterY, screenHeightPx)
                    )
                }
            }

            SettingsDestination.WATCH_FACES -> SettingsPageScaffold(
            title = "\u8868\u76d8",
            onBack = { destination = SettingsDestination.ROOT },
            headerTime = headerTime
        ) { _, _, _ ->
            if (!watchFaceLastError.isNullOrBlank()) {
                item("watchface_error") {
                    MessageCard(
                        text = watchFaceLastError!!,
                        background = Color(0x33FF6B6B),
                        onClick = { vm.clearWatchFaceError() }
                    )
                }
            }
            items(watchFaces, key = { it.id }) { descriptor ->
                WatchFaceSettingCard(
                    descriptor = descriptor,
                    selected = descriptor.id == selectedWatchFaceId,
                    scale = 1f,
                    builtInPhotoPath = builtInPhotoPath,
                    builtInVideoPath = builtInVideoPath,
                    photoOptions = BuiltInWatchFaceOptions(
                        clockPosition = builtInPhotoClockPosition,
                        clockSizeSp = builtInPhotoClockSize
                    ),
                    videoOptions = BuiltInWatchFaceOptions(
                        clockPosition = builtInVideoClockPosition,
                        clockSizeSp = builtInVideoClockSize,
                        cropToFill = builtInVideoFillScreen
                    ),
                    onSelect = { vm.selectWatchFace(descriptor.id) },
                    onOpenSettings = if (descriptor.supportsSettings) {
                        {
                            if (descriptor.isBuiltin && descriptor.id in setOf(BUILT_IN_PHOTO_WATCHFACE_ID, BUILT_IN_VIDEO_WATCHFACE_ID)) {
                                context.startActivity(
                                    Intent(context, InternalWatchFaceConfigActivity::class.java)
                                        .putExtra(EXTRA_INTERNAL_WATCHFACE_ID, descriptor.id)
                                )
                            } else if (!LunchWatchFaceRuntime.openSettings(context, descriptor)) {
                                Toast.makeText(context, "\u6CA1\u6709\u53EF\u7528\u7684\u8868\u76D8\u8BBE\u7F6E", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        null
                    }
                )
            }
            item("watchface_refresh") {
                ActionCard(
                    title = "\u91cd\u65b0\u626b\u63cf\u8868\u76d8",
                    subtitle = "\u5237\u65b0\u5df2\u5b89\u88c5\u7684 Lunch \u517c\u5bb9\u8868\u76d8",
                    scale = 1f,
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = WatchColors.ActiveCyan) },
                    onClick = { vm.refreshWatchFaces() }
                )
            }
        }

            SettingsDestination.APPEARANCE -> SettingsPageScaffold(
            title = "\u663e\u793a\u4e0e\u5916\u89c2",
            onBack = { destination = SettingsDestination.ROOT },
            headerTime = headerTime
        ) { listState, screenCenterY, screenHeightPx ->
            item("layout_header") { SectionTitle("\u5e03\u5c40", itemFisheye(listState, "layout_header", screenCenterY, screenHeightPx)) }
            item("layout_honeycomb") {
                SettingsChoiceRow(
                    title = "\u8702\u7a9d\u5e03\u5c40",
                    subtitle = "Apple Watch \u98ce\u683c",
                    selected = layoutMode == LayoutMode.Honeycomb,
                    onClick = { vm.setLayoutMode(LayoutMode.Honeycomb) },
                    scale = itemFisheye(listState, "layout_honeycomb", screenCenterY, screenHeightPx)
                )
            }
            item("layout_list") {
                SettingsChoiceRow(
                    title = "\u5217\u8868\u5e03\u5c40",
                    subtitle = "\u7ecf\u5178\u7eb5\u5411\u5217\u8868",
                    selected = layoutMode == LayoutMode.List,
                    onClick = { vm.setLayoutMode(LayoutMode.List) },
                    scale = itemFisheye(listState, "layout_list", screenCenterY, screenHeightPx)
                )
            }
            item("visual_header") { SectionTitle("\u89c6\u89c9", itemFisheye(listState, "visual_header", screenCenterY, screenHeightPx)) }
            item("blur_enabled") {
                SettingsSwitchRow(
                    title = "\u80cc\u666f\u6a21\u7cca",
                    subtitle = "\u5728\u62bd\u5c49\u4e0e\u5c42\u7ea7\u5207\u6362\u4e2d\u542f\u7528\u6a21\u7cca",
                    checked = blurEnabled,
                    onToggle = { vm.setBlurEnabled(it) },
                    scale = itemFisheye(listState, "blur_enabled", screenCenterY, screenHeightPx)
                )
            }
            item("edge_blur") {
                SettingsSwitchRow(
                    title = "\u8fb9\u7f18\u6a21\u7cca",
                    subtitle = if (blurEnabled) "\u4e3a\u9876\u90e8\u548c\u5e95\u90e8\u6dfb\u52a0\u6e10\u53d8\u6a21\u7cca" else "\u5f00\u542f\u80cc\u666f\u6a21\u7cca\u540e\u53ef\u7528",
                    checked = edgeBlurEnabled,
                    enabled = blurEnabled,
                    onToggle = { vm.setEdgeBlurEnabled(it) },
                    scale = itemFisheye(listState, "edge_blur", screenCenterY, screenHeightPx)
                )
            }
            item("honeycomb_cols") {
                SettingsSliderRow(
                    title = "\u8702\u7a9d\u5217\u6570",
                    value = honeycombCols.toFloat(),
                    valueText = "$honeycombCols \u5217",
                    range = 3f..6f,
                    steps = 2,
                    onValueChange = { vm.setHoneycombCols(it.toInt()) },
                    scale = itemFisheye(listState, "honeycomb_cols", screenCenterY, screenHeightPx)
                )
            }
            item("top_blur") {
                SettingsSliderRow(
                    title = "\u9876\u90e8\u6a21\u7cca\u534a\u5f84",
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
                    title = "\u5e95\u90e8\u6a21\u7cca\u534a\u5f84",
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
                    title = "\u9876\u90e8\u6e10\u9690\u8303\u56f4",
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
                    title = "\u5e95\u90e8\u6e10\u9690\u8303\u56f4",
                    value = honeycombBottomFade.toFloat(),
                    valueText = "$honeycombBottomFade dp",
                    range = 0f..160f,
                    steps = 15,
                    onValueChange = { vm.setHoneycombBottomFade(it.toInt()) },
                    scale = itemFisheye(listState, "bottom_fade", screenCenterY, screenHeightPx)
                )
            }
            item("launch_header") { SectionTitle("\u542f\u52a8", itemFisheye(listState, "launch_header", screenCenterY, screenHeightPx)) }
            item("splash_toggle") {
                SettingsSwitchRow(
                    title = "\u542f\u52a8\u906e\u7f69",
                    subtitle = "\u6253\u5f00\u5e94\u7528\u65f6\u663e\u793a\u56fe\u6807\u8fc7\u6e21",
                    checked = splashIcon,
                    onToggle = { vm.setSplashIcon(it) },
                    scale = itemFisheye(listState, "splash_toggle", screenCenterY, screenHeightPx)
                )
            }
            if (splashIcon) {
                item("splash_delay") {
                    SettingsSliderRow(
                        title = "\u906e\u7f69\u65f6\u957f",
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
            title = "\u6027\u80fd\u4e0e\u52a8\u753b",
            onBack = { destination = SettingsDestination.ROOT },
            headerTime = headerTime
        ) { listState, screenCenterY, screenHeightPx ->
            item("low_res") {
                SettingsSwitchRow(
                    title = "\u4f4e\u5206\u8fa8\u7387\u56fe\u6807",
                    subtitle = "\u964d\u4f4e\u56fe\u6807\u5f00\u9500\u4ee5\u63d0\u5347\u6d41\u7545\u5ea6",
                    checked = lowResIcons,
                    onToggle = { vm.setLowResIcons(it) },
                    scale = itemFisheye(listState, "low_res", screenCenterY, screenHeightPx)
                )
            }
            item("anim_override") {
                SettingsSwitchRow(
                    title = "\u684c\u9762\u8fd4\u56de\u52a8\u753b",
                    subtitle = "\u542f\u7528\u7c7b watchOS \u7684\u8fd4\u56de\u8fc7\u6e21",
                    checked = animationOverrideEnabled,
                    onToggle = { vm.setAnimationOverrideEnabled(it) },
                    scale = itemFisheye(listState, "anim_override", screenCenterY, screenHeightPx)
                )
            }
        }

            SettingsDestination.TOOLS -> SettingsPageScaffold(
            title = "\u5de5\u5177",
            onBack = { destination = SettingsDestination.ROOT },
            headerTime = headerTime
        ) { listState, screenCenterY, screenHeightPx ->
            item("export_log") {
                ActionCard(
                    title = "\u5bfc\u51fa\u65e5\u5fd7",
                    subtitle = "\u5bfc\u51fa\u6700\u8fd1 500 \u884c\u7cfb\u7edf\u65e5\u5fd7",
                    onClick = { exportLog(context) },
                    scale = itemFisheye(listState, "export_log", screenCenterY, screenHeightPx)
                )
            }
            item("reset_defaults") {
                ActionCard(
                    title = "\u6062\u590d\u9ed8\u8ba4\u8bbe\u7f6e",
                    subtitle = "\u91cd\u7f6e\u684c\u9762\u5916\u89c2\u4e0e\u6027\u80fd\u9009\u9879",
                    onClick = { vm.resetSettings() },
                    scale = itemFisheye(listState, "reset_defaults", screenCenterY, screenHeightPx)
                )
            }
            }

            SettingsDestination.ABOUT -> SettingsPageScaffold(
                title = "\u5173\u4e8e",
                onBack = { destination = SettingsDestination.ROOT },
                headerTime = headerTime
            ) { listState, screenCenterY, screenHeightPx ->
                item("about_card") {
                    AboutCard(
                        scale = itemFisheye(listState, "about_card", screenCenterY, screenHeightPx)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    headerTime: String,
    content: LazyListScope.(LazyListState, Float, Float) -> Unit
) {
    val listState = rememberLazyListState()
    val overscroll = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()

    val nestedScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source != NestedScrollSource.Drag) return androidx.compose.ui.geometry.Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y * 0.35f).coerceAtMost(140f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (overscroll.value > 0f && available.y < 0f) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y).coerceAtLeast(0f)) }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll.value > 0f) {
                    overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        val screenCenterY = screenHeightPx / 2f

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
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
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            item {
                Text(
                    text = title,
                    color = WatchColors.ActiveCyan,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            content(listState, screenCenterY, screenHeightPx)
        }
    }
}

@Composable
private fun HeaderBackButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "header_back_scale")
    Box(
        modifier = Modifier
            .size(42.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
    }
}

@Composable
private fun SettingsCategoryCard(title: String, subtitle: String, onClick: () -> Unit, scale: Float) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale by animateFloatAsState(
        if (pressed) 0.958f else 1f,
        animationSpec = spring(stiffness = 780f, dampingRatio = 0.72f),
        label = "settings_category_scale"
    )
    val background by animateColorAsState(
        if (pressed) WatchColors.SurfaceGlass.copy(alpha = 0.82f) else WatchColors.SurfaceGlass,
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
            .clip(RoundedCornerShape(24.dp))
            .background(background)
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
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, color = WatchColors.TextTertiary, fontSize = 13.sp)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = WatchColors.ActiveCyan)
        }
    }
}

@Composable
private fun SectionTitle(text: String, scale: Float) {
    Text(
        text = text,
        color = WatchColors.TextTertiary,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 12.sp)
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
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale by animateFloatAsState(
        if (pressed) 0.964f else 1f,
        animationSpec = spring(stiffness = 820f, dampingRatio = 0.74f),
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
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    pressed && selected -> WatchColors.ActiveCyan.copy(alpha = 0.22f)
                    pressed -> WatchColors.SurfaceGlass.copy(alpha = 0.82f)
                    selected -> WatchColors.ActiveCyan.copy(alpha = 0.16f)
                    else -> WatchColors.SurfaceGlass
                }
            )
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
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(3.dp))
                Text(subtitle, color = WatchColors.TextTertiary, fontSize = 12.sp)
            }
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = WatchColors.ActiveCyan)
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
    leadingIcon: ImageBitmap? = null
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale by animateFloatAsState(
        if (pressed) 0.958f else 1f,
        animationSpec = spring(stiffness = 860f, dampingRatio = 0.72f),
        label = "switch_row_scale"
    )
    val trackColor by animateColorAsState(
        when {
            !enabled -> Color(0xFF2A2A2A)
            checked -> WatchColors.ActiveGreen
            else -> Color(0xFF555555)
        },
        label = "switch_track_color"
    )
    val knobOffset by animateDpAsState(
        if (checked) 22.dp else 2.dp,
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
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) WatchColors.SurfaceGlass.copy(alpha = 0.82f) else WatchColors.SurfaceGlass)
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
                if (leadingIcon != null) {
                    Image(
                        bitmap = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column {
                    Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(subtitle, color = WatchColors.TextTertiary, fontSize = 12.sp)
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
            .clip(RoundedCornerShape(18.dp))
            .background(WatchColors.SurfaceGlass)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(valueText, color = WatchColors.TextTertiary, fontSize = 12.sp)
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
                    thumbColor = WatchColors.ActiveCyan,
                    activeTrackColor = WatchColors.ActiveCyan
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
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val pressedScale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 820f, dampingRatio = 0.74f),
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
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) WatchColors.SurfaceGlass.copy(alpha = 0.82f) else WatchColors.SurfaceGlass)
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
                Text(title, color = WatchColors.ActiveCyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(subtitle, color = WatchColors.TextTertiary, fontSize = 12.sp)
                }
            }
            icon?.invoke()
        }
    }
}

@Composable
private fun AboutCard(scale: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale.coerceIn(0.55f, 1f)
            }
            .clip(RoundedCornerShape(28.dp))
            .background(WatchColors.SurfaceGlass)
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
                    modifier = Modifier.size(76.dp)
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
                Text("\u67da\u5b50\u67da\u5b50\u76ae", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun itemFisheye(
    listState: LazyListState,
    key: String,
    screenCenterY: Float,
    screenHeight: Float
): Float {
    val info = listState.layoutInfo.visibleItemsInfo.find { it.key == key } ?: return 0.92f
    val itemCenterY = info.offset + info.size / 2f
    if (itemCenterY <= screenCenterY) return 1f
    val distance = kotlin.math.abs(itemCenterY - screenCenterY)
    val normalized = (distance / (screenHeight / 2f)).coerceIn(0f, 1f)
    return 1f - 0.14f * normalized
}

private fun exportLog(context: android.content.Context) {
    try {
        val log = Runtime.getRuntime().exec("logcat -d -t 500").inputStream.bufferedReader().readText()
        val file = File(context.cacheDir, "wlauncher_log.txt")
        file.writeText(log)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "\u5bfc\u51fa\u65e5\u5fd7"
            )
        )
    } catch (_: Exception) {
        Toast.makeText(context, "\u5bfc\u51fa\u5931\u8d25", Toast.LENGTH_SHORT).show()
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
