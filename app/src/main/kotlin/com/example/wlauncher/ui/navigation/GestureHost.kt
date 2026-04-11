package com.flue.launcher.ui.navigation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

@Composable
fun GestureHost(
    screenState: ScreenState,
    onStateChange: (ScreenState) -> Unit,
    modifier: Modifier = Modifier,
    sideScreenEnabled: Boolean = false,
    showNotification: Boolean = true,
    showControlCenter: Boolean = false,
    onSideScreenProgressChange: (Float?) -> Unit = {},
    content: @Composable () -> Unit
) {
    var totalDx by remember { mutableFloatStateOf(0f) }
    var totalDy by remember { mutableFloatStateOf(0f) }
    var consumed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(screenState, sideScreenEnabled, showControlCenter, onSideScreenProgressChange) {
                detectDragGestures(
                    onDragStart = {
                        totalDx = 0f
                        totalDy = 0f
                        consumed = false
                        onSideScreenProgressChange(null)
                    },
                    onDrag = { change, dragAmount ->
                        totalDx += dragAmount.x
                        totalDy += dragAmount.y
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val isVertical = abs(totalDy) > abs(totalDx)
                        val isHorizontal = !isVertical

                        when (screenState) {
                            ScreenState.Face -> {
                                if (isHorizontal && sideScreenEnabled && totalDx > 0f) {
                                    onSideScreenProgressChange(((totalDx / width) * 2f).coerceIn(0f, 2f))
                                    change.consume()
                                } else if (!consumed && isVertical && totalDy < -80f) {
                                    consumed = true
                                    onStateChange(ScreenState.Apps)
                                    change.consume()
                                } else if (!consumed && isHorizontal && totalDx < -80f && showControlCenter) {
                                    consumed = true
                                    onStateChange(ScreenState.ControlCenter)
                                    change.consume()
                                }
                            }

                            ScreenState.ControlCenter -> {
                                if (!consumed && isHorizontal && totalDx > 80f) {
                                    consumed = true
                                    onStateChange(ScreenState.Face)
                                    change.consume()
                                }
                            }

                            ScreenState.Stack -> {
                                if (isHorizontal && totalDx < 0f) {
                                    onSideScreenProgressChange((2f + (totalDx / width) * 2f).coerceIn(0f, 2f))
                                    change.consume()
                                }
                            }

                            ScreenState.Apps -> Unit
                            ScreenState.App -> Unit
                            ScreenState.Settings -> Unit
                            ScreenState.Notifications -> Unit
                        }
                    },
                    onDragEnd = {
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        when (screenState) {
                            ScreenState.Face -> {
                                if (sideScreenEnabled && totalDx > 0f && abs(totalDx) > abs(totalDy)) {
                                    if (((totalDx / width) * 2f).coerceIn(0f, 2f) > 1f) {
                                        onStateChange(ScreenState.Stack)
                                    }
                                }
                            }

                            ScreenState.Stack -> {
                                if (totalDx < 0f && abs(totalDx) > abs(totalDy)) {
                                    if ((2f + (totalDx / width) * 2f).coerceIn(0f, 2f) < 1f) {
                                        onStateChange(ScreenState.Face)
                                    }
                                }
                            }

                            else -> Unit
                        }
                        onSideScreenProgressChange(null)
                    },
                    onDragCancel = { onSideScreenProgressChange(null) }
                )
            }
    ) {
        content()
    }
}
