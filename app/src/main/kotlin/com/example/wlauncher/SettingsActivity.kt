package com.example.wlauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import com.example.wlauncher.ui.navigation.LayoutMode
import com.example.wlauncher.ui.settings.LauncherSettingsSheet
import com.example.wlauncher.ui.theme.WatchLauncherTheme
import com.example.wlauncher.viewmodel.LauncherViewModel.Companion.KEY_BLUR
import com.example.wlauncher.viewmodel.LauncherViewModel.Companion.KEY_LAYOUT
import com.example.wlauncher.viewmodel.LauncherViewModel.Companion.KEY_LOW_RES
import com.example.wlauncher.viewmodel.LauncherViewModel.Companion.KEY_SPLASH_ICON
import com.example.wlauncher.viewmodel.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchLauncherTheme {
                val scope = rememberCoroutineScope()
                val prefs by dataStore.data.collectAsState(initial = null)

                val layoutMode = prefs?.get(KEY_LAYOUT)?.let {
                    try { LayoutMode.valueOf(it) } catch (_: Exception) { LayoutMode.Honeycomb }
                } ?: LayoutMode.Honeycomb
                val blurEnabled = prefs?.get(KEY_BLUR) ?: (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                val lowRes = prefs?.get(KEY_LOW_RES) ?: false
                val splash = prefs?.get(KEY_SPLASH_ICON) ?: true

                LauncherSettingsSheet(
                    currentLayout = layoutMode,
                    blurEnabled = blurEnabled,
                    lowResIcons = lowRes,
                    splashIcon = splash,
                    onLayoutChange = { scope.launch { dataStore.edit { p -> p[KEY_LAYOUT] = it.name } } },
                    onBlurToggle = { scope.launch { dataStore.edit { p -> p[KEY_BLUR] = it && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S } } },
                    onLowResToggle = { scope.launch { dataStore.edit { p -> p[KEY_LOW_RES] = it } } },
                    onSplashToggle = { scope.launch { dataStore.edit { p -> p[KEY_SPLASH_ICON] = it } } },
                    onDismiss = { finish() },
                    modifier = Modifier.fillMaxSize().background(Color.Black)
                )
            }
        }
    }
}
