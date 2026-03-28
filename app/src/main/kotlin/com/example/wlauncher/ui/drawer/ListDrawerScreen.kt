package com.flue.launcher.ui.drawer

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.anim.platformBlur
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val LIST_MENU_TRIGGER_MS = 560L
private const val LIST_MENU_DRAG_START_DP = 20f
private const val LIST_AUTO_SCROLL_EDGE_DP = 84f
private const val LIST_AUTO_SCROLL_MAX_PX = 30f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListDrawerScreen(
    apps: List<AppInfo>,
    blurEnabled: Boolean = true,
    edgeBlurEnabled: Boolean = false,
    suppressHeavyEffects: Boolean = false,
    iconSize: Dp = 48.dp,
    onAppClick: (AppInfo, Offset) -> Unit,
    onReorder: (Int, Int) -> Unit = { _, _ -> },
    onLongClick: (AppInfo) -> Unit = {},
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val effectiveEdgeBlur = edgeBlurEnabled && !suppressHeavyEffects

    var longPressedApp by remember { mutableStateOf<AppInfo?>(null) }
    val itemCenters = remember { mutableMapOf<Int, Float>() }
    val itemHeights = remember { mutableMapOf<Int, Float>() }
    val overscroll = remember { Animatable(0f) }
    var dragFromIndex by remember { mutableStateOf<Int?>(null) }
    var dragCurrentIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var glidePressedIndex by remember { mutableStateOf<Int?>(null) }
    var initializedAtTop by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    LaunchedEffect(apps.size) {
        if (!initializedAtTop && apps.isNotEmpty()) {
            listState.scrollToItem(0)
            initializedAtTop = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent {
                    scope.launch { listState.scrollBy(-it.verticalScrollPixels) }
                    true
                }
                .pointerInput(listState) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) {
                                    scope.launch { listState.scrollBy(delta * 30f) }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
                .nestedScroll(
                    remember(listState, dragFromIndex, longPressedApp) {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                if (source != NestedScrollSource.Drag || dragFromIndex != null || longPressedApp != null) return Offset.Zero
                                return consumeListOverscroll(
                                    availableY = available.y,
                                    listState = listState,
                                    overscroll = overscroll,
                                    scope = scope
                                )
                            }

                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource
                            ): Offset {
                                if (source != NestedScrollSource.Drag || dragFromIndex != null || longPressedApp != null) return Offset.Zero
                                return consumeListOverscroll(
                                    availableY = available.y,
                                    listState = listState,
                                    overscroll = overscroll,
                                    scope = scope
                                )
                            }

                            override suspend fun onPreFling(available: Velocity): Velocity {
                                if (dragFromIndex != null || longPressedApp != null) return Velocity.Zero
                                val atTop = !listState.canScrollBackward
                                if ((overscroll.value > 80f || available.y > 1200f) && atTop) {
                                    overscroll.snapTo(0f)
                                    onScrollToTop()
                                    return available
                                }
                                if (overscroll.value != 0f) {
                                    overscroll.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 460f))
                                    return available
                                }
                                return Velocity.Zero
                            }
                        }
                    }
                )
                .platformBlur(16f, longPressedApp != null && blurEnabled && !suppressHeavyEffects)
                .pointerInput(apps) {
                    val maxDistancePx = with(density) { 72.dp.toPx() }
                    val menuDragStartPx = with(density) { LIST_MENU_DRAG_START_DP.dp.toPx() }
                    val autoScrollEdgePx = with(density) { LIST_AUTO_SCROLL_EDGE_DP.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitPrimaryDown()
                        val startIndex = findNearestListIndex(
                            pointerY = down.position.y,
                            itemCenters = itemCenters,
                            maxDistance = maxDistancePx
                        ) ?: return@awaitEachGesture
                        val app = apps.getOrNull(startIndex) ?: return@awaitEachGesture
                        glidePressedIndex = startIndex
                        val longPress = awaitLongPressByTimeoutOrCancel(
                            pointerId = down.id,
                            downPosition = down.position,
                            timeoutMillis = LIST_MENU_TRIGGER_MS
                        )
                        if (longPress == null) {
                            glidePressedIndex = null
                            return@awaitEachGesture
                        }

                        onLongClick(app)
                        longPressedApp = app
                        glidePressedIndex = null
                        vibrateHaptic(context)

                        val dragAnchorY = longPress.position.y
                        var dragActive = false
                        var hasDragged = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break

                            val pointerY = change.position.y
                            val deltaFromAnchor = abs(pointerY - dragAnchorY)
                            if (!dragActive && deltaFromAnchor > menuDragStartPx) {
                                longPressedApp = null
                                dragActive = true
                                dragFromIndex = startIndex
                                dragCurrentIndex = startIndex
                                glidePressedIndex = null
                                vibrateHaptic(context)
                            }

                            val activeFromIndex = dragFromIndex
                            if (dragActive && activeFromIndex != null) {
                                val anchorCenter = itemCenters[activeFromIndex] ?: pointerY
                                dragOffsetY = pointerY - anchorCenter
                                if (abs(dragOffsetY) > menuDragStartPx * 0.35f) {
                                    hasDragged = true
                                }

                                val autoScrollDelta = when {
                                    pointerY < autoScrollEdgePx -> {
                                        -LIST_AUTO_SCROLL_MAX_PX * ((autoScrollEdgePx - pointerY) / autoScrollEdgePx)
                                    }
                                    pointerY > size.height - autoScrollEdgePx -> {
                                        LIST_AUTO_SCROLL_MAX_PX * ((pointerY - (size.height - autoScrollEdgePx)) / autoScrollEdgePx)
                                    }
                                    else -> 0f
                                }
                                if (autoScrollDelta != 0f) {
                                    scope.launch { listState.scrollBy(autoScrollDelta) }
                                }

                                dragCurrentIndex = findNearestListIndex(
                                    pointerY = pointerY,
                                    itemCenters = itemCenters,
                                    maxDistance = Float.MAX_VALUE
                                ) ?: dragCurrentIndex
                                if (change.position != change.previousPosition) {
                                    change.consume()
                                }
                            }
                        }

                        val from = dragFromIndex
                        val to = dragCurrentIndex
                        if (dragActive && from != null && to != null && from != to && hasDragged) {
                            onReorder(from, to)
                        }
                        dragFromIndex = null
                        dragCurrentIndex = null
                        dragOffsetY = 0f
                        glidePressedIndex = null
                    }
                }
        ) {
            val screenHeightPx = with(density) { maxHeight.toPx() }
            val screenCenterY = screenHeightPx / 2f
            val estimatedItemHeight = iconSize.coerceAtLeast(48.dp) + 20.dp
            val centeredPadding = 8.dp
            val dragRowShift = dragFromIndex?.let { itemHeights[it] } ?: with(density) { estimatedItemHeight.toPx() }

            LazyColumn(
                state = listState,
                userScrollEnabled = dragFromIndex == null && longPressedApp == null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = overscroll.value }
                    .background(Color.Black),
                contentPadding = PaddingValues(
                    top = centeredPadding,
                    bottom = centeredPadding,
                    start = 12.dp,
                    end = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(apps, key = { _, app -> app.componentKey }) { index, app ->
                    val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == index }
                    val itemScale = computeItemScale(itemInfo, screenCenterY, screenHeightPx)
                    val interactionSource = remember(app.componentKey) { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val isDragged = dragFromIndex == index
                    val isGlidePressed = glidePressedIndex == index
                    val displacedTarget = listDisplacementForIndex(
                        index = index,
                        dragFromIndex = dragFromIndex,
                        dragCurrentIndex = dragCurrentIndex,
                        dragRowShift = dragRowShift
                    )
                    val animatedDisplacement by animateFloatAsState(
                        targetValue = displacedTarget,
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                        label = "list_drag_displacement"
                    )
                    val displayDisplacement = if (isDragged) dragOffsetY else animatedDisplacement
                    val pressedScale by animateFloatAsState(
                        targetValue = when {
                            isDragged -> 0.965f
                            isGlidePressed -> 0.992f
                            isPressed -> 0.97f
                            else -> 1f
                        },
                        animationSpec = tween(durationMillis = 170),
                        label = "list_press_scale"
                    )
                    val pressedOverlay by animateFloatAsState(
                        targetValue = when {
                            isDragged -> 0.10f
                            isGlidePressed -> 0.05f
                            isPressed -> 0.12f
                            else -> 0f
                        },
                        animationSpec = tween(durationMillis = 170),
                        label = "list_press_overlay"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                val posY = coords.positionInRoot().y
                                itemCenters[index] = posY + coords.size.height / 2f
                                itemHeights[index] = coords.size.height.toFloat()
                            }
                            .graphicsLayer {
                                val targetScale = itemScale * pressedScale
                                translationY = displayDisplacement
                                scaleX = targetScale
                                scaleY = targetScale
                                alpha = if (isDragged) 0.96f else itemScale.coerceIn(0.3f, 1f)
                            }
                            .background(Color.Black.copy(alpha = pressedOverlay), RoundedCornerShape(18.dp))
                            .combinedClickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = {
                                    val centerY = itemCenters[index] ?: screenCenterY
                                    onAppClick(app, Offset(0.15f, centerY / screenHeightPx))
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = app.cachedIcon,
                            contentDescription = app.label,
                            modifier = Modifier
                                .size(iconSize)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = app.label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.W500,
                            color = Color.White
                        )
                    }
                }
            }
        }

        if (blurEnabled && effectiveEdgeBlur) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.38f),
                                Color.Black.copy(alpha = 0.14f),
                                Color.Transparent
                            )
                        )
                    )
                    .platformBlur(14f, true)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(78.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.14f),
                                Color.Black.copy(alpha = 0.42f)
                            )
                        )
                    )
                    .platformBlur(14f, true)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(56.dp)
                .background(Brush.verticalGradient(listOf(Color.Black, Color.Transparent)))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(60.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        )
    }

    longPressedApp?.let { app ->
        AppShortcutOverlay(app = app, blurEnabled = blurEnabled, onDismiss = { longPressedApp = null })
    }
}

private fun consumeListOverscroll(
    availableY: Float,
    listState: androidx.compose.foundation.lazy.LazyListState,
    overscroll: Animatable<Float, AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope
): Offset {
    val atTop = !listState.canScrollBackward
    val atBottom = !listState.canScrollForward
    val current = overscroll.value
    val next = when {
        availableY > 0f && atTop -> (current + availableY * 0.35f).coerceAtMost(180f)
        availableY < 0f && atBottom -> (current + availableY * 0.35f).coerceAtLeast(-180f)
        current > 0f && availableY < 0f -> (current + availableY).coerceAtLeast(0f)
        current < 0f && availableY > 0f -> (current + availableY).coerceAtMost(0f)
        else -> current
    }
    if (next == current) return Offset.Zero
    scope.launch { overscroll.snapTo(next) }
    return Offset(0f, availableY)
}

private fun listDisplacementForIndex(
    index: Int,
    dragFromIndex: Int?,
    dragCurrentIndex: Int?,
    dragRowShift: Float
): Float {
    if (dragFromIndex == null || dragCurrentIndex == null || dragFromIndex == dragCurrentIndex) return 0f
    return when {
        dragCurrentIndex > dragFromIndex && index in (dragFromIndex + 1)..dragCurrentIndex -> -dragRowShift
        dragCurrentIndex < dragFromIndex && index in dragCurrentIndex until dragFromIndex -> dragRowShift
        else -> 0f
    }
}

private fun findNearestListIndex(
    pointerY: Float,
    itemCenters: Map<Int, Float>,
    maxDistance: Float
): Int? {
    var bestIndex: Int? = null
    var bestDistance = Float.MAX_VALUE
    itemCenters.forEach { (index, centerY) ->
        val distance = abs(centerY - pointerY)
        if (distance < bestDistance && distance <= maxDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

private fun computeItemScale(
    itemInfo: androidx.compose.foundation.lazy.LazyListItemInfo?,
    screenCenterY: Float,
    screenHeight: Float
): Float {
    if (itemInfo == null) return 0.85f
    val itemCenterY = itemInfo.offset + itemInfo.size / 2f
    val dist = abs(itemCenterY - screenCenterY)
    val maxDist = screenHeight / 2f
    val t = (dist / maxDist).coerceIn(0f, 1f)
    return 1f - 0.2f * t
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitLongPressByTimeoutOrCancel(
    pointerId: androidx.compose.ui.input.pointer.PointerId,
    downPosition: Offset,
    timeoutMillis: Long
): androidx.compose.ui.input.pointer.PointerInputChange? {
    val cancelled = withTimeoutOrNull<Boolean>(timeoutMillis) {
        var cancelledByGesture = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: run {
                cancelledByGesture = true
                break
            }
            if (!change.pressed) {
                cancelledByGesture = true
                break
            }
            if ((change.position - downPosition).getDistance() > viewConfiguration.touchSlop) {
                cancelledByGesture = true
                break
            }
        }
        cancelledByGesture
    } ?: false
    if (cancelled) return null
    val current = currentEvent.changes.firstOrNull { it.id == pointerId }
    return current?.takeIf { it.pressed }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitPrimaryDown():
    androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.pressed }
        if (change != null) return change
    }
}
