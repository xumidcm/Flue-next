package com.flue.launcher.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.iconForDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AppFolderOverlay(
    folder: AppInfo,
    items: List<AppInfo>,
    listMode: Boolean,
    blurEnabled: Boolean,
    twoToneIconsEnabled: Boolean,
    onAppClick: (AppInfo, Offset) -> Unit,
    onMoveItemOut: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var showing by remember(folder.componentKey) { mutableStateOf(false) }
    var panelBounds by remember { mutableStateOf(Rect.Zero) }
    val dismissInteraction = remember { MutableInteractionSource() }
    val blockInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(folder.componentKey) { showing = true }
    BackHandler(enabled = true) { onDismiss() }

    val alpha by animateFloatAsState(
        targetValue = if (showing) 1f else 0f,
        animationSpec = tween(180),
        label = "folder_overlay_alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (showing) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "folder_overlay_scale"
    )
    val dragThresholdPx = with(density) { 22.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black.copy(alpha = if (blurEnabled) 0.58f else 0.46f))
            .clickable(indication = null, interactionSource = dismissInteraction) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xE62A2A2C))
                .clickable(indication = null, interactionSource = blockInteraction) { }
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (listMode) {
                Text(
                    text = folder.label,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W700
                )
                Spacer(modifier = Modifier.height(14.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.height(180.dp)
            ) {
                items(items, key = { it.componentKey }) { app ->
                    var itemRoot by remember(app.componentKey) { mutableStateOf(Offset.Zero) }
                    val iconShape = RoundedCornerShape(18.dp)
                    Column(
                        modifier = Modifier
                            .onGloballyPositioned { itemRoot = it.positionInRoot() }
                            .pointerInput(app.componentKey, panelBounds) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var dragMoved = false
                                    var lastPosition = down.position
                                    var longPressCancelled = false
                                    withTimeoutOrNull(360L) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: run {
                                                    longPressCancelled = true
                                                    return@withTimeoutOrNull
                                                }
                                            if (!change.pressed) {
                                                longPressCancelled = true
                                                return@withTimeoutOrNull
                                            }
                                            if ((change.position - down.position).getDistance() > dragThresholdPx) {
                                                longPressCancelled = true
                                                return@withTimeoutOrNull
                                            }
                                        }
                                    }
                                    if (longPressCancelled) return@awaitEachGesture
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        lastPosition = change.position
                                        if ((change.position - down.position).getDistance() > dragThresholdPx) {
                                            dragMoved = true
                                            change.consume()
                                        }
                                    }
                                    if (dragMoved) {
                                        val releaseInRoot = itemRoot + lastPosition
                                        if (!panelBounds.contains(releaseInRoot)) {
                                            onMoveItemOut(app)
                                        }
                                    }
                                }
                            }
                            .clickable {
                                onAppClick(app, Offset(0.5f, 0.5f))
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            bitmap = app.iconForDisplay(twoToneIconsEnabled),
                            contentDescription = app.label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(iconShape)
                        )
                        if (listMode) {
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = app.label,
                                color = Color.White.copy(alpha = 0.86f),
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
