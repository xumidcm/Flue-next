package com.flue.launcher.ui.icon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

@Composable
fun rememberLauncherIcon(
    componentKey: String,
    blurred: Boolean,
    iconFlowProvider: (String, Boolean) -> StateFlow<ImageBitmap?>
): ImageBitmap? {
    val flow = remember(componentKey, blurred) {
        iconFlowProvider(componentKey, blurred)
    }
    val image by flow.collectAsState()
    return image
}
