package com.flue.launcher.ui.notification

import androidx.compose.ui.graphics.ImageBitmap

data class NotificationEntryUi(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
    val icon: ImageBitmap?,
    val isClearable: Boolean,
    val contentIntentAvailable: Boolean
)

data class NotificationGroupUi(
    val packageName: String,
    val appLabel: String,
    val icon: ImageBitmap?,
    val latestTime: Long,
    val latestSummary: String,
    val count: Int,
    val children: List<NotificationEntryUi>,
    val expanded: Boolean
)
