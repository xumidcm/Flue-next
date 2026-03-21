package com.example.wlauncher.ui.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.wlauncher.data.model.AppInfo
import com.example.wlauncher.ui.theme.WatchColors
import com.example.wlauncher.util.fisheyeScale
import com.example.wlauncher.util.generateHexSpiral
import com.example.wlauncher.util.hexToPixel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HoneycombScreen(
    apps: List<AppInfo>,
    onAppClick: (AppInfo, Offset) -> Unit,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val screenCenter = Offset(screenWidthPx / 2f, screenHeightPx / 2f)
        val screenRadius = minOf(screenWidthPx, screenHeightPx) / 2f

        val baseCellSize = with(density) { 46.dp.toPx() }
        val iconSizeDp = 54.dp

        val hexPositions = remember(apps.size) { generateHexSpiral(apps.size) }

        // 仅 Y 轴平移
        var panOffsetY by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        val animY = remember { Animatable(0f) }

        // 计算 Y 轴滚动边界
        val maxScrollY = remember(hexPositions, baseCellSize) {
            if (hexPositions.isEmpty()) 0f
            else {
                val maxAbsY = hexPositions.maxOf { abs(hexToPixel(it, baseCellSize).y) }
                maxAbsY + baseCellSize * 2
            }
        }

        fun clampY(y: Float): Float = y.coerceIn(-maxScrollY, maxScrollY)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val velocityTracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            scope.launch { animY.stop() }
                            velocityTracker.resetTracking()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(
                                change.uptimeMillis,
                                change.position
                            )
                            panOffsetY = clampY(panOffsetY + dragAmount.y)
                        },
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity()
                            scope.launch {
                                animY.snapTo(panOffsetY)
                                animY.animateDecay(velocity.y, exponentialDecay())
                            }
                        }
                    )
                }
        ) {
            LaunchedEffect(Unit) {
                snapshotFlow { animY.value }
                    .collect { panOffsetY = clampY(it) }
            }

            apps.forEachIndexed { index, app ->
                if (index >= hexPositions.size) return@forEachIndexed

                val hexPixel = hexToPixel(hexPositions[index], baseCellSize)
                val worldPos = Offset(hexPixel.x, hexPixel.y + panOffsetY)
                val screenPos = screenCenter + worldPos
                val distFromCenter = worldPos.getDistance()

                // 视口裁剪
                if (screenPos.x < -100 || screenPos.x > screenWidthPx + 100 ||
                    screenPos.y < -100 || screenPos.y > screenHeightPx + 100
                ) return@forEachIndexed

                val scale = fisheyeScale(distFromCenter, screenRadius * 1.2f)
                val iconSizePx = with(density) { iconSizeDp.toPx() }

                AppBubble(
                    icon = app.icon,
                    size = iconSizeDp,
                    onClick = {
                        val normalizedOrigin = Offset(
                            screenPos.x / screenWidthPx,
                            screenPos.y / screenHeightPx
                        )
                        onAppClick(app, normalizedOrigin)
                    },
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (screenPos.x - iconSizePx / 2).roundToInt(),
                                (screenPos.y - iconSizePx / 2).roundToInt()
                            )
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = scale.coerceIn(0.2f, 1f)
                        }
                )
            }
        }

        // 设置按钮 - 悬浮在右下角
        FloatingActionButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 24.dp, end = 16.dp)
                .size(40.dp),
            shape = CircleShape,
            containerColor = WatchColors.SurfaceGlass,
            elevation = FloatingActionButtonDefaults.elevation(0.dp)
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
