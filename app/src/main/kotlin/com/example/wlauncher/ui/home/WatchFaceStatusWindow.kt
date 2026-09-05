package com.flue.launcher.ui.home

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** A late draw from an interrupted return must not reveal the status window. */
internal class WatchFacePresentationGate {
    private var eligible = false
    private var generation = 0
    private var presentedGeneration by mutableIntStateOf(-1)

    val visible: Boolean get() = eligible && presentedGeneration == generation

    fun update(canPresent: Boolean): Int {
        if (eligible != canPresent) {
            eligible = canPresent
            generation++
        }
        return generation
    }

    fun onFrameDrawn(token: Int) {
        if (eligible && generation == token) presentedGeneration = token
    }
}

/** PixelCopy of the Activity surface excludes this separate, untouchable window. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WatchFaceStatusWindow(visible: Boolean, hasNotifications: Boolean) {
    if (!visible) return
    val topOffset = with(LocalDensity.current) { 18.dp.roundToPx() }
    val properties = remember {
        PopupProperties(
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            inheritSecurePolicy = true,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            excludeFromSystemGesture = false,
            usePlatformDefaultWidth = false,
        )
    }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, topOffset),
        properties = properties,
    ) {
        WatchFaceStatusIndicatorOverlay(
            showStatusIndicators = true,
            hasNotifications = hasNotifications,
        )
    }
}
