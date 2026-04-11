package com.flue.launcher.ui.settings

import androidx.compose.foundation.lazy.LazyListScope

internal fun LazyListScope.addDisplaySettingsToggleItems(
    isZh: Boolean,
    sideScreenEnabled: Boolean,
    showNotification: Boolean,
    blurEnabled: Boolean,
    edgeBlurEnabled: Boolean,
    onSideScreenToggle: (Boolean) -> Unit,
    onShowNotificationChange: (Boolean) -> Unit,
    onBlurToggle: (Boolean) -> Unit,
    onEdgeBlurToggle: (Boolean) -> Unit,
    addToggleItem: LazyListScope.(
        key: String,
        label: String,
        description: String,
        checked: Boolean,
        enabled: Boolean,
        onToggle: (Boolean) -> Unit
    ) -> Unit
) {
    addToggleItem(
        "side_screen_toggle",
        displayText(isZh, "副一屏", "Side Screen"),
        displayText(isZh, "从表盘右滑进入快捷启动与通知页", "Swipe right from the watch face to open the side screen"),
        sideScreenEnabled,
        true,
        onSideScreenToggle
    )
    addToggleItem(
        "notification_center_toggle",
        displayText(isZh, "通知中心", "Notification Center"),
        if (showNotification) {
            displayText(
                isZh,
                "显示通知横幅，并允许从副一屏上滑进入通知中心",
                "Show notification banners and allow swipe-up from the side screen into the notification center"
            )
        } else {
            displayText(
                isZh,
                "隐藏通知横幅与通知中心，并把快捷启动扩展为 3×3",
                "Hide notification banners and the notification center, and expand quick launch to 3x3"
            )
        },
        showNotification,
        sideScreenEnabled,
        onShowNotificationChange
    )
    addToggleItem(
        "blur_toggle",
        displayText(isZh, "模糊", "Blur"),
        displayText(isZh, "在支持的 Android 版本上启用模糊", "Enable blur on supported Android versions"),
        blurEnabled,
        true,
        onBlurToggle
    )
    addToggleItem(
        "edge_blur_toggle",
        displayText(isZh, "边缘模糊（实验）", "Edge Blur (Experimental)"),
        if (blurEnabled) {
            displayText(isZh, "在顶部和底部边缘增加模糊", "Apply extra blur near the top and bottom edges")
        } else {
            displayText(isZh, "请先开启模糊", "Enable Blur first")
        },
        edgeBlurEnabled,
        blurEnabled,
        onEdgeBlurToggle
    )
}

private fun displayText(isZh: Boolean, zh: String, en: String): String = if (isZh) zh else en
