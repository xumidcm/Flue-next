package com.flue.launcher.ui.navigation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

private enum class PageDragTarget {
    OpenStack,
    CloseStack,
    OpenWidgets,
    CloseWidgets,
    OpenControlCenter,
    OpenApps
}

@Composable
fun GestureHost(
    screenState: ScreenState,
    onStateChange: (ScreenState) -> Unit,
    modifier: Modifier = Modifier,
    sideScreenEnabled: Boolean = false,
    showNotification: Boolean = true,
    showWidgetPage: Boolean = true,
    showControlCenter: Boolean = false,
    onSideProgressChange: (Float) -> Unit = {},
    onSideRelease: (Boolean) -> Unit = {},
    onWidgetProgressChange: (Float) -> Unit = {},
    onWidgetRelease: (Boolean) -> Unit = {},
    onControlCenterProgressChange: (Float) -> Unit = {},
    onControlCenterRelease: (Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    var totalDx by remember { mutableFloatStateOf(0f) }
    var totalDy by remember { mutableFloatStateOf(0f) }
    var dragTarget by remember { mutableStateOf<PageDragTarget?>(null) }
    var pageProgress by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .pointerInput(screenState, sideScreenEnabled, showWidgetPage, showControlCenter) {
                detectDragGestures(
                    onDragStart = {
                        totalDx = 0f
                        totalDy = 0f
                        dragTarget = null
                        pageProgress = 0f
                    },
                    onDrag = { change, dragAmount ->
                        totalDx += dragAmount.x
                        totalDy += dragAmount.y

                        if (dragTarget == null && (abs(totalDx) > 12 || abs(totalDy) > 12)) {
                            val isVertical = abs(totalDy) > abs(totalDx)
                            val isHorizontal = !isVertical
                            dragTarget = when (screenState) {
                                ScreenState.Face -> {
                                    when {
                                        isVertical && totalDy < -12 -> PageDragTarget.OpenApps
                                        isHorizontal && totalDx > 12 && sideScreenEnabled -> PageDragTarget.OpenStack
                                        isHorizontal && totalDx < -12 && showWidgetPage -> PageDragTarget.OpenWidgets
                                        isVertical && totalDy > 12 && showControlCenter -> PageDragTarget.OpenControlCenter
                                        else -> null
                                    }
                                }

                                ScreenState.Notifications -> null

                                ScreenState.Widgets -> {
                                    if (isHorizontal && totalDx > 12) PageDragTarget.CloseWidgets else null
                                }

                                ScreenState.ControlCenter -> null

                                ScreenState.Apps -> null
                                ScreenState.App -> null
                                ScreenState.Settings -> null
                                ScreenState.Stack -> {
                                    if (isHorizontal && totalDx < -12) PageDragTarget.CloseStack else null
                                }
                            }
                        }

                        val target = dragTarget ?: return@detectDragGestures
                        val horizontalRange = (size.width * 0.42f).coerceAtLeast(120f)
                        val verticalRange = (size.height * 0.36f).coerceAtLeast(120f)
                        pageProgress = when (target) {
                            PageDragTarget.OpenStack -> (totalDx / horizontalRange).coerceIn(0f, 1f)
                            PageDragTarget.CloseStack -> (1f + totalDx / horizontalRange).coerceIn(0f, 1f)
                            PageDragTarget.OpenWidgets -> (-totalDx / horizontalRange).coerceIn(0f, 1f)
                            PageDragTarget.CloseWidgets -> (1f - totalDx / horizontalRange).coerceIn(0f, 1f)
                            PageDragTarget.OpenControlCenter -> (totalDy / verticalRange).coerceIn(0f, 1f)
                            PageDragTarget.OpenApps -> 0f
                        }
                        when (target) {
                            PageDragTarget.OpenStack,
                            PageDragTarget.CloseStack -> onSideProgressChange(pageProgress)
                            PageDragTarget.OpenWidgets,
                            PageDragTarget.CloseWidgets -> onWidgetProgressChange(pageProgress)
                            PageDragTarget.OpenControlCenter -> onControlCenterProgressChange(pageProgress)
                            PageDragTarget.OpenApps -> {
                                if (totalDy < -80) {
                                    onStateChange(ScreenState.Apps)
                                    dragTarget = null
                                }
                            }
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        when (dragTarget) {
                            PageDragTarget.OpenStack -> onSideRelease(pageProgress > 0.45f)
                            PageDragTarget.CloseStack -> onSideRelease(pageProgress > 0.55f)
                            PageDragTarget.OpenWidgets -> onWidgetRelease(pageProgress > 0.45f)
                            PageDragTarget.CloseWidgets -> onWidgetRelease(pageProgress > 0.55f)
                            PageDragTarget.OpenControlCenter -> onControlCenterRelease(pageProgress > 0.45f)
                            PageDragTarget.OpenApps,
                            null -> Unit
                        }
                        dragTarget = null
                        pageProgress = 0f
                    },
                    onDragCancel = {
                        when (dragTarget) {
                            PageDragTarget.OpenStack -> onSideRelease(false)
                            PageDragTarget.CloseStack -> onSideRelease(true)
                            PageDragTarget.OpenWidgets -> onWidgetRelease(false)
                            PageDragTarget.CloseWidgets -> onWidgetRelease(true)
                            PageDragTarget.OpenControlCenter -> onControlCenterRelease(false)
                            PageDragTarget.OpenApps,
                            null -> Unit
                        }
                        dragTarget = null
                        pageProgress = 0f
                    }
                )
            }
    ) {
        content()
    }
}
