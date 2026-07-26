package com.flue.launcher.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

@Composable
internal fun bottomFisheyeScale(
    listState: LazyListState,
    key: Any,
    screenCenterY: Float,
    screenHeight: Float
): Float {
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val visibleIndex = visibleItems.indexOfFirst { it.key == key }
    if (visibleIndex < 0 || visibleItems.lastIndex - visibleIndex > 1) return 1f
    val info = visibleItems[visibleIndex]
    val itemCenterY = info.offset + info.size / 2f
    return bottomFisheyeScaleForPosition(itemCenterY, screenHeight)
}

internal fun bottomFisheyeScaleForPosition(
    itemCenterY: Float,
    screenHeight: Float,
): Float {
    val height = screenHeight.coerceAtLeast(1f)
    val effectStartY = height * 0.62f
    if (itemCenterY <= effectStartY) return 1f
    val linear = ((itemCenterY - effectStartY) / (height - effectStartY)).coerceIn(0f, 1f)
    val smooth = linear * linear * (3f - 2f * linear)
    return (1f - 0.14f * smooth).coerceIn(0.86f, 1f)
}
