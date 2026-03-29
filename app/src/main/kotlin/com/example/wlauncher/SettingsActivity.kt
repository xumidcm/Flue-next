package com.flue.launcher

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.settings.LauncherSettingsSheet
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_BLUR
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_EDGE_BLUR
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_HONEYCOMB_BOTTOM_BLUR
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_HONEYCOMB_BOTTOM_FADE
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_HONEYCOMB_COLS
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_HONEYCOMB_TOP_BLUR
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_HONEYCOMB_TOP_FADE
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_ANIMATION_OVERRIDE
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_LAYOUT
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_LOW_RES
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_SELECTED_WATCHFACE_ID
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_SPLASH_DELAY
import com.flue.launcher.viewmodel.LauncherViewModel.Companion.KEY_SPLASH_ICON
import com.flue.launcher.viewmodel.dataStore
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.LunchWatchFaceRuntime
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchLauncherTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val prefs by dataStore.data.collectAsState(initial = null)
                val vm: LauncherViewModel = viewModel()
                val watchFaces by vm.availableWatchFaces.collectAsState()
                val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsState()
                val watchFaceLastError by vm.watchFaceLastError.collectAsState()
                val builtInPhotoPath by vm.builtInPhotoPath.collectAsState()
                val builtInVideoPath by vm.builtInVideoPath.collectAsState()

                LaunchedEffect(Unit) {
                    vm.refreshWatchFaces()
                }

                val layoutMode = prefs?.get(KEY_LAYOUT)?.let {
                    try {
                        LayoutMode.valueOf(it)
                    } catch (_: Exception) {
                        LayoutMode.Honeycomb
                    }
                } ?: LayoutMode.Honeycomb
                val blurEnabled = prefs?.get(KEY_BLUR) ?: true
                val edgeBlurEnabled = prefs?.get(KEY_EDGE_BLUR) ?: false
                val lowRes = prefs?.get(KEY_LOW_RES) ?: false
                val animationOverrideEnabled = prefs?.get(KEY_ANIMATION_OVERRIDE) ?: true
                val splash = prefs?.get(KEY_SPLASH_ICON) ?: true
                val delay = prefs?.get(KEY_SPLASH_DELAY) ?: 500
                val honeycombCols = prefs?.get(KEY_HONEYCOMB_COLS) ?: 4
                val topBlur = prefs?.get(KEY_HONEYCOMB_TOP_BLUR) ?: 4
                val bottomBlur = prefs?.get(KEY_HONEYCOMB_BOTTOM_BLUR) ?: 4
                val topFade = prefs?.get(KEY_HONEYCOMB_TOP_FADE) ?: 56
                val bottomFade = prefs?.get(KEY_HONEYCOMB_BOTTOM_FADE) ?: 56
                val persistedSelectedWatchFaceId = prefs?.get(KEY_SELECTED_WATCHFACE_ID) ?: selectedWatchFaceId

                LauncherSettingsSheet(
                    currentLayout = layoutMode,
                    blurEnabled = blurEnabled,
                    edgeBlurEnabled = edgeBlurEnabled,
                    lowResIcons = lowRes,
                    animationOverrideEnabled = animationOverrideEnabled,
                    splashIcon = splash,
                    splashDelay = delay,
                    honeycombCols = honeycombCols,
                    honeycombTopBlur = topBlur,
                    honeycombBottomBlur = bottomBlur,
                    honeycombTopFade = topFade,
                    honeycombBottomFade = bottomFade,
                    watchFaces = watchFaces,
                    selectedWatchFaceId = persistedSelectedWatchFaceId,
                    watchFaceLastError = watchFaceLastError,
                    builtInPhotoPath = builtInPhotoPath,
                    builtInVideoPath = builtInVideoPath,
                    onLayoutChange = { scope.launch { dataStore.edit { p -> p[KEY_LAYOUT] = it.name } } },
                    onBlurToggle = {
                        scope.launch {
                            dataStore.edit { p ->
                                p[KEY_BLUR] = it
                                if (!it) {
                                    p[KEY_EDGE_BLUR] = false
                                }
                            }
                        }
                    },
                    onEdgeBlurToggle = { scope.launch { dataStore.edit { p -> p[KEY_EDGE_BLUR] = it && blurEnabled } } },
                    onLowResToggle = { scope.launch { dataStore.edit { p -> p[KEY_LOW_RES] = it } } },
                    onAnimationOverrideToggle = { scope.launch { dataStore.edit { p -> p[KEY_ANIMATION_OVERRIDE] = it } } },
                    onSplashToggle = { scope.launch { dataStore.edit { p -> p[KEY_SPLASH_ICON] = it } } },
                    onSplashDelayChange = { scope.launch { dataStore.edit { p -> p[KEY_SPLASH_DELAY] = it } } },
                    onHoneycombColsChange = { scope.launch { dataStore.edit { p -> p[KEY_HONEYCOMB_COLS] = it } } },
                    onHoneycombTopBlurChange = { scope.launch { dataStore.edit { p -> p[KEY_HONEYCOMB_TOP_BLUR] = it } } },
                    onHoneycombBottomBlurChange = { scope.launch { dataStore.edit { p -> p[KEY_HONEYCOMB_BOTTOM_BLUR] = it } } },
                    onHoneycombTopFadeChange = { scope.launch { dataStore.edit { p -> p[KEY_HONEYCOMB_TOP_FADE] = it } } },
                    onHoneycombBottomFadeChange = { scope.launch { dataStore.edit { p -> p[KEY_HONEYCOMB_BOTTOM_FADE] = it } } },
                    onWatchFaceSelect = { vm.selectWatchFace(it) },
                    onOpenWatchFaceSettings = { descriptor ->
                        if (descriptor.isBuiltin && descriptor.id in setOf(BUILT_IN_PHOTO_WATCHFACE_ID, BUILT_IN_VIDEO_WATCHFACE_ID)) {
                            context.startActivity(
                                Intent(context, InternalWatchFaceConfigActivity::class.java)
                                    .putExtra(EXTRA_INTERNAL_WATCHFACE_ID, descriptor.id)
                            )
                        } else if (!LunchWatchFaceRuntime.openSettings(context, descriptor)) {
                            Toast.makeText(context, "娌℃湁鍙敤鐨勮〃鐩樿缃?, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRefreshWatchFaces = { vm.refreshWatchFaces() },
                    onClearWatchFaceError = { vm.clearWatchFaceError() },
                    onResetDefaults = { vm.resetSettings() },
                    onDismiss = { finish() },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }
        }
    }
}
