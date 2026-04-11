package com.flue.launcher.ui.notification

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val RevealActionWidth = 74.dp
private val RevealShape = RoundedCornerShape(28.dp)

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
    val revealWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { RevealActionWidth.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val offsetX = remember(target) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(revealedTarget, revealWidthPx) {
        val targetOffset = if (enabled && revealedTarget == target) -revealWidthPx else 0f
        if (offsetX.targetValue != targetOffset || offsetX.value != targetOffset) {
            offsetX.animateTo(targetOffset, tween(180))
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(if (actionHeight == null) Alignment.CenterEnd else Alignment.TopEnd)
                .width(RevealActionWidth)
                .then(if (actionHeight == null) Modifier.fillMaxHeight() else Modifier.height(actionHeight))
                .clip(RevealShape)
                .background(Color(0xFF8E4F64))
                .padding(end = 18.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = Color(0xFFF7E7ED),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(enabled = enabled) {
                        onRevealTargetChange(null)
                        onDelete()
                    }
                    .padding(10.dp)
            )
        }

        Box(
            modifier = Modifier
                .clip(RevealShape)
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(target, enabled, revealWidthPx, touchSlop) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitPrimaryDown()
                        val pointerId = down.id
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var totalX = 0f
                        var totalY = 0f
                        var horizontalLock = offsetX.value < 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull()
                                ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                break
                            }

                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y

                            if (!horizontalLock) {
                                val pastSlop = abs(totalX) > touchSlop || abs(totalY) > touchSlop
                                if (pastSlop) {
                                    val horizontalDominant = abs(totalX) > abs(totalY) * 1.15f
                                    val revealDirection = totalX < 0f || offsetX.value < 0f
                                    if (horizontalDominant && revealDirection) {
                                        horizontalLock = true
                                    } else {
                                        break
                                    }
                                }
                            }

                            if (horizontalLock) {
                                change.consume()
                                val nextOffset = (offsetX.value + delta.x).coerceIn(-revealWidthPx, 0f)
                                scope.launch { offsetX.snapTo(nextOffset) }
                            }
                        }

                        val velocityX = tracker.calculateVelocity().x
                        val shouldReveal = horizontalLock && (
                            offsetX.value <= -revealWidthPx * 0.42f ||
                                velocityX < -1200f
                            )
                        val finalOffset = if (shouldReveal) -revealWidthPx else 0f
                        scope.launch { offsetX.animateTo(finalOffset, tween(180)) }
                        onRevealTargetChange(if (shouldReveal) target else null)
                    }
                }
        ) {
            content()
        }
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
