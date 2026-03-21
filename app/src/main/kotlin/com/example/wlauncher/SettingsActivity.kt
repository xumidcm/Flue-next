package com.example.wlauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wlauncher.ui.settings.LauncherSettingsSheet
import com.example.wlauncher.ui.theme.WatchLauncherTheme
import com.example.wlauncher.viewmodel.LauncherViewModel

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchLauncherTheme {
                val vm: LauncherViewModel = viewModel()
                val layoutMode by vm.layoutMode.collectAsState()
                val blurEnabled by vm.blurEnabled.collectAsState()
                val lowResIcons by vm.lowResIcons.collectAsState()

                LauncherSettingsSheet(
                    currentLayout = layoutMode,
                    blurEnabled = blurEnabled,
                    lowResIcons = lowResIcons,
                    onLayoutChange = { vm.setLayoutMode(it) },
                    onBlurToggle = { vm.setBlurEnabled(it) },
                    onLowResToggle = { vm.setLowResIcons(it) },
                    onDismiss = { finish() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
