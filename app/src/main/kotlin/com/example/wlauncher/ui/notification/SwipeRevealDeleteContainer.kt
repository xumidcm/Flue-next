package com.flue.launcher.ui.notification

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private const val DISMISS_DISTANCE_RATIO = 0.30f
private const val DISMISS_VELOCITY = 1450f

@Composable
internal fun SwipeRevealDeleteContainer(
    target: NotificationRevealTarget,
    revealedTarget: NotificationRevealTarget?,
    onRevealTargetChange: (NotificationRevealTarget?) -> Unit,
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    actionHeight: androidx.compose.ui.unit.Dp? = null,
    content: @Composable () -> Unit
) {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val offsetX = remember(target) { Animatable(0f) }
    val dismissProgress = remember(target) { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offsetX.value
                scaleX = dismissProgress.value
                scaleY = dismissProgress.value
                alpha = dismissProgress.value.coerceIn(0.75f, 1f)
            }
            .pointerInput(target, enabled, touchSlop) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitPrimaryDown()
                    val pointerId = down.id
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    var totalX = 0f
                    var totalY = 0f
                    var lockedHorizontal = false

                    onRevealTargetChange(null)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed || change.changedToUpIgnoreConsumed()) break

                        val delta = change.positionChange()
                        totalX += delta.x
                        totalY += delta.y

                        if (!lockedHorizontal) {
                            val passedSlop = abs(totalX) > touchSlop || abs(totalY) > touchSlop
                            if (passedSlop) {
                                lockedHorizontal = abs(totalX) > abs(totalY) * 1.15f
                                if (!lockedHorizontal) break
                            }
                        }

                        if (lockedHorizontal) {
                            change.consume()
                            val nextOffset = (offsetX.value + delta.x).coerceIn(-widthPx * 1.15f, widthPx * 1.15f)
                            scope.launch { offsetX.snapTo(nextOffset) }
                        }
                    }

                    val velocityX = tracker.calculateVelocity().x
                    val shouldDismiss = lockedHorizontal && (
                        abs(offsetX.value) > widthPx * DISMISS_DISTANCE_RATIO ||
                            abs(velocityX) > DISMISS_VELOCITY
                        )

                    if (shouldDismiss) {
                        val direction = if (offsetX.value == 0f) sign(velocityX).takeIf { it != 0f } ?: 1f else sign(offsetX.value)
                        scope.launch {
                            dismissProgress.snapTo(1f)
                            launch {
                                dismissProgress.animateTo(
                                    targetValue = 0.92f,
                                    animationSpec = tween(110)
                                )
                            }
                            offsetX.animateTo(
                                targetValue = direction * widthPx * 1.25f,
                                animationSpec = tween(220)
                            )
                            dismissProgress.animateTo(
                                targetValue = 0.88f,
                                animationSpec = tween(120)
                            )
                            onDelete()
                            offsetX.snapTo(0f)
                            dismissProgress.snapTo(1f)
                        }
                    } else {
                        scope.launch {
                            launch {
                                dismissProgress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 560f)
                                )
                            }
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(dampingRatio = 0.72f, stiffness = 560f)
                            )
                        }
                    }
                }
            }
    ) {
        content()
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitPrimaryDown():
    androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.pressed }
        if (change != null) return change
    }
}
