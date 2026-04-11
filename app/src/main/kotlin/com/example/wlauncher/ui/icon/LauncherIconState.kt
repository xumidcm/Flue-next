package com.flue.launcher.ui.icon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap

@Composable
fun rememberLauncherIcon(
    componentKey: String,
    blurred: Boolean,
    iconVersion: Long,
    iconProvider: (String, Boolean) -> ImageBitmap?
): ImageBitmap? {
    return remember(componentKey, blurred, iconVersion) {
        iconProvider(componentKey, blurred)
    }
}
