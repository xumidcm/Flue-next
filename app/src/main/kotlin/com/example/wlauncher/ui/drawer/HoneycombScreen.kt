package com.flue.launcher.ui.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.anim.platformBlur
import com.flue.launcher.util.fisheyeScale
import com.flue.launcher.util.generateHoneycombRows
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

private const val HONEYCOMB_MENU_TRIGGER_MS = 410L
private const val HONEYCOMB_MENU_DRAG_START_DP = 20f
private const val HONEYCOMB_PRESS_DURATION_MS = 200
private const val HONEYCOMB_AUTO_SCROLL_EDGE_DP = 96f
private const val HONEYCOMB_AUTO_SCROLL_MAX_PX = 26f

@Composable
fun HoneycombScreen(
    apps: List<AppInfo>,
    blurEnabled: Boolean = true,
    edgeBlurEnabled: Boolean = false,
    suppressHeavyEffects: Boolean = false,
    narrowCols: Int = 4,
    topBlurRadiusDp: Int = 12,
    bottomBlurRadiusDp: Int = 12,
    topFadeRangeDp: Int = 56,
    bottomFadeRangeDp: Int = 56,
    onAppClick: (AppInfo, Offset) -> Unit,
    onReorder: (Int, Int) -> Unit = { _, _ -> },
    onLongClick: (AppInfo) -> Unit = {},
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var longPressedApp by remember { mutableStateOf<AppInfo?>(null) }
    var glidePressedKey by remember { mutableStateOf<String?>(null) }
    var dragFromIndex by remember { mutableStateOf<Int?>(null) }
    var dragCurrentIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var initializedAtTop by remember { mutableStateOf(false) }
    var settlingApp by remember { mutableStateOf<AppInfo?>(null) }
    var settlingKey by remember { mutableStateOf<String?>(null) }
    var dropPressedIndex by remember { mutableStateOf<Int?>(null) }
    val settlingX = remember { Animatable(0f) }
    val settlingY = remember { Animatable(0f) }
    val effectiveEdgeBlur = edgeBlurEnabled && !suppressHeavyEffects

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val screenCenterX = screenWidthPx / 2f
        val screenCenterY = screenHeightPx / 2f
        val screenRadius = minOf(screenWidthPx, screenHeightPx) / 2f

        val maxCols = narrowCols + 1
        val availableWidth = screenWidthPx - with(density) { 20.dp.toPx() }
        val iconSizePx = (availableWidth / (maxCols + 0.35f)).coerceIn(
            with(density) { 54.dp.toPx() },
            with(density) { 84.dp.toPx() }
        )
        val iconSizeDp = with(density) { iconSizePx.toDp() }
        val cellSize = iconSizePx * 1.02f
        val topFadePx = with(density) { topFadeRangeDp.dp.toPx() }
        val bottomFadePx = with(density) { bottomFadeRangeDp.dp.toPx() }

        val positions = remember(apps.size, narrowCols, cellSize) {
            generateHoneycombRows(apps.size, narrowCols, cellSize)
        }

        val minGridY = positions.minOfOrNull { it.y } ?: 0f
        val maxGridY = positions.maxOfOrNull { it.y } ?: 0f
        val maxScroll = -minGridY
        val minScroll = -maxGridY

        val scrollOffset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val overlayBlurActive = longPressedApp != null && blurEnabled && !suppressHeavyEffects
        val honeycombAutoScrollEdgePx = with(density) { HONEYCOMB_AUTO_SCROLL_EDGE_DP.dp.toPx() }
        LaunchedEffect(maxScroll, apps.size) {
            if (!initializedAtTop && apps.isNotEmpty()) {
                scrollOffset.snapTo(maxScroll)
                initializedAtTop = true
            }
        }
        LaunchedEffect(dragFromIndex, minScroll, maxScroll, screenHeightPx) {
            var previousFrameNanos = 0L
            while (dragFromIndex != null) {
                var pendingScrollTarget: Float? = null
                withFrameNanos { frameTimeNanos ->
                    val frameDeltaSeconds = if (previousFrameNanos == 0L) {
                        1f / 60f
                    } else {
                        ((frameTimeNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(1f / 144f, 0.05f)
                    }
                    previousFrameNanos = frameTimeNanos
                    val pointer = dragPointer
                    if (pointer != null) {
                        val clampedPointer = Offset(
                            x = pointer.x.coerceIn(iconSizePx * 0.5f, screenWidthPx - iconSizePx * 0.5f),
                            y = pointer.y.coerceIn(iconSizePx * 0.5f, screenHeightPx - iconSizePx * 0.5f)
                        )
                        val autoScrollVelocity = when {
                            clampedPointer.y < honeycombAutoScrollEdgePx -> {
                                HONEYCOMB_AUTO_SCROLL_MAX_PX * 60f * ((honeycombAutoScrollEdgePx - clampedPointer.y) / honeycombAutoScrollEdgePx)
                            }
                            clampedPointer.y > screenHeightPx - honeycombAutoScrollEdgePx -> {
                                -HONEYCOMB_AUTO_SCROLL_MAX_PX * 60f * ((clampedPointer.y - (screenHeightPx - honeycombAutoScrollEdgePx)) / honeycombAutoScrollEdgePx)
                            }
                            else -> 0f
                        }
                        if (autoScrollVelocity != 0f) {
                            val next = (scrollOffset.value + autoScrollVelocity * frameDeltaSeconds).coerceIn(minScroll, maxScroll)
                            if (next != scrollOffset.value) {
                                pendingScrollTarget = next
                            }
                        }
                        dragCurrentIndex = findNearestHoneycombIndex(
                            pointer = clampedPointer,
                            positions = positions,
                            screenCenterX = screenCenterX,
                            screenCenterY = screenCenterY + scrollOffset.value,
                            maxDistance = cellSize * 0.95f
                        ) ?: dragCurrentIndex
                    }
                }
                pendingScrollTarget?.let { scrollOffset.snapTo(it) }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent {
                    val next = (scrollOffset.value - it.verticalScrollPixels).coerceIn(minScroll, maxScroll)
                    scope.launch { scrollOffset.snapTo(next) }
                    true
                }
                .pointerInput(minScroll, maxScroll) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) {
                                    val next = (scrollOffset.value + delta * 24f).coerceIn(minScroll, maxScroll)
                                    scope.launch { scrollOffset.snapTo(next) }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
                .platformBlur(16f, overlayBlurActive)
                .pointerInput(apps, positions, scrollOffset.value) {
                    val menuDragStartPx = with(density) { HONEYCOMB_MENU_DRAG_START_DP.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitPrimaryDown()
                        val startIndex = findNearestHoneycombIndex(
                            pointer = down.position,
                            positions = positions,
                            screenCenterX = screenCenterX,
                            screenCenterY = screenCenterY + scrollOffset.value,
                            maxDistance = iconSizePx * 0.7f
                        ) ?: return@awaitEachGesture
                        val app = apps.getOrNull(startIndex) ?: return@awaitEachGesture
                        val longPress = awaitLongPressByTimeoutOrCancel(
                            pointerId = down.id,
                            downPosition = down.position,
                            timeoutMillis = HONEYCOMB_MENU_TRIGGER_MS
                        )
                        if (longPress == null) {
                            glidePressedKey = null
                            return@awaitEachGesture
                        }

                        onLongClick(app)
                        longPressedApp = app
                        glidePressedKey = null
                        vibrateHaptic(context)

                        val dragOrigin = longPress.position
                        var dragActive = false
                        var hasDragged = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break

                            val pointer = change.position
                            val movedDistance = (pointer - dragOrigin).getDistance()
                            if (!dragActive && movedDistance > menuDragStartPx) {
                                longPressedApp = null
                                dragActive = true
                                dragFromIndex = startIndex
                                dragCurrentIndex = startIndex
                                dragOffset = Offset.Zero
                                dragPointer = pointer
                                glidePressedKey = null
                                vibrateHaptic(context)
                            }

                            val fromIndex = dragFromIndex
                            if (dragActive && fromIndex != null) {
                                dragPointer = pointer
                                dragOffset = pointer - dragOrigin
                                if (dragOffset.getDistance() > menuDragStartPx * 0.35f) {
                                    hasDragged = true
                                }
                                val dragTarget = findNearestHoneycombIndex(
                                    pointer = Offset(
                                        x = pointer.x.coerceIn(iconSizePx * 0.5f, screenWidthPx - iconSizePx * 0.5f),
                                        y = pointer.y.coerceIn(iconSizePx * 0.5f, screenHeightPx - iconSizePx * 0.5f)
                                    ),
                                    positions = positions,
                                    screenCenterX = screenCenterX,
                                    screenCenterY = screenCenterY + scrollOffset.value,
                                    maxDistance = cellSize * 0.95f
                                )
                                dragCurrentIndex = dragTarget ?: fromIndex
                                change.consume()
                            }
                        }

                        val from = dragFromIndex
                        val to = dragCurrentIndex
                        if (dragActive && from != null && to != null && from != to && hasDragged) {
                            val droppedApp = apps.getOrNull(from)
                            val currentPointer = dragPointer
                            if (droppedApp != null && currentPointer != null) {
                                val targetSlot = positions.getOrNull(to)
                                if (targetSlot != null) {
                                    settlingApp = droppedApp
                                    settlingKey = droppedApp.componentKey
                                    scope.launch {
                                        settlingX.snapTo(currentPointer.x.coerceIn(iconSizePx * 0.5f, screenWidthPx - iconSizePx * 0.5f))
                                        settlingY.snapTo(currentPointer.y.coerceIn(iconSizePx * 0.5f, screenHeightPx - iconSizePx * 0.5f))
                                        launch {
                                            settlingX.animateTo(
                                                screenCenterX + targetSlot.x,
                                                tween(durationMillis = 120)
                                            )
                                        }
                                        launch {
                                            settlingY.animateTo(
                                                (screenCenterY + targetSlot.y + scrollOffset.value).coerceIn(
                                                    iconSizePx * 0.5f,
                                                    screenHeightPx - iconSizePx * 0.5f
                                                ),
                                                tween(durationMillis = 120)
                                            )
                                        }
                                    }
                                }
                            }
                            onReorder(from, to)
                            dropPressedIndex = to
                            scope.launch {
                                kotlinx.coroutines.delay(180)
                                dropPressedIndex = null
                                settlingApp = null
                                settlingKey = null
                                settlingX.snapTo(0f)
                                settlingY.snapTo(0f)
                            }
                        } else {
                            settlingApp = null
                            settlingKey = null
                            scope.launch {
                                settlingX.snapTo(0f)
                                settlingY.snapTo(0f)
                            }
                        }
                        dragFromIndex = null
                        dragCurrentIndex = null
                        dragOffset = Offset.Zero
                        dragPointer = null
                        glidePressedKey = null
                    }
                }
                .pointerInput(apps, positions, minScroll, maxScroll) {
                    val velocityTracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            if (dragFromIndex != null || longPressedApp != null) return@detectDragGestures
                            scope.launch { scrollOffset.stop() }
                            velocityTracker.resetTracking()
                            val hoverIndex = findNearestHoneycombIndex(
                                pointer = startOffset,
                                positions = positions,
                                screenCenterX = screenCenterX,
                                screenCenterY = screenCenterY + scrollOffset.value,
                                maxDistance = iconSizePx * 0.9f
                            )
                            glidePressedKey = hoverIndex?.let { apps.getOrNull(it)?.componentKey }
                        },
                        onDrag = { change, dragAmount ->
                            if (dragFromIndex != null || longPressedApp != null) return@detectDragGestures
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val hoverIndex = findNearestHoneycombIndex(
                                pointer = change.position,
                                positions = positions,
                                screenCenterX = screenCenterX,
                                screenCenterY = screenCenterY + scrollOffset.value,
                                maxDistance = iconSizePx * 0.9f
                            )
                            glidePressedKey = hoverIndex?.let { apps.getOrNull(it)?.componentKey }
                            val current = scrollOffset.value
                            val next = current + dragAmount.y
                            val overscroll = when {
                                next > maxScroll -> next - maxScroll
                                next < minScroll -> next - minScroll
                                else -> 0f
                            }
                            val dampedDrag = if (overscroll != 0f) dragAmount.y * 0.28f else dragAmount.y
                            scope.launch { scrollOffset.snapTo(current + dampedDrag) }
                        },
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity().y
                            val current = scrollOffset.value
                            glidePressedKey = null
                            if (current >= maxScroll - iconSizePx * 0.45f && velocity > 800f) {
                                onScrollToTop()
                                return@detectDragGestures
                            }
                            if (current < minScroll || current > maxScroll) {
                                scope.launch {
                                    scrollOffset.animateTo(
                                        current.coerceIn(minScroll, maxScroll),
                                        spring(dampingRatio = 0.64f, stiffness = 360f)
                                    )
                                }
                            } else {
                                scope.launch {
                                    scrollOffset.animateDecay(velocity, exponentialDecay()) {
                                        if (value < minScroll || value > maxScroll) {
                                            scope.launch {
                                                scrollOffset.animateTo(
                                                    value.coerceIn(minScroll, maxScroll),
                                                    spring(dampingRatio = 0.64f, stiffness = 360f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            glidePressedKey = null
                        }
                    )
                }
        ) {
            val currentScroll = scrollOffset.value
            val visibleTop = -iconSizePx * 1.5f
            val visibleBottom = screenHeightPx + iconSizePx * 1.5f

            apps.forEachIndexed { index, app ->
                if (index >= positions.size) return@forEachIndexed
                val visualSlotIndex = honeycombVisualSlotIndex(index, dragFromIndex, dragCurrentIndex)
                val gridPos = positions[index]
                val visualPos = positions.getOrNull(visualSlotIndex) ?: gridPos
                val appKey = app.componentKey
                val isDragged = dragFromIndex == index
                val visibilityY = if (isDragged) {
                    screenCenterY + gridPos.y + currentScroll + dragOffset.y
                } else {
                    screenCenterY + visualPos.y + currentScroll
                }
                if (!isDragged && (visibilityY < visibleTop || visibilityY > visibleBottom)) return@forEachIndexed
                val itemBlur = computeHoneycombEdgeBlur(
                    centerY = visibilityY,
                    screenHeight = screenHeightPx,
                    topBlurZonePx = topFadePx,
                    bottomBlurZonePx = bottomFadePx,
                    topBlurDp = topBlurRadiusDp.toFloat(),
                    bottomBlurDp = bottomBlurRadiusDp.toFloat()
                )
                val isGlidePressed = glidePressedKey == appKey
                val menuPressedKey = longPressedApp?.componentKey
                val menuPressedIndex = menuPressedKey?.let { key ->
                    apps.indexOfFirst { it.componentKey == key }.takeIf { it >= 0 }
                }
                val droppedPressedSlotIndex = dropPressedIndex
                val pressedAnchor = when {
                    dragFromIndex == null && menuPressedIndex != null && menuPressedIndex in positions.indices -> positions[menuPressedIndex]
                    dragFromIndex == null && droppedPressedSlotIndex != null && droppedPressedSlotIndex in positions.indices -> positions[droppedPressedSlotIndex]
                    else -> null
                }
                val motion = neighborPressMotion(
                    current = visualPos,
                    pressedAnchor = pressedAnchor,
                    iconSizePx = iconSizePx,
                    cellSize = cellSize
                )

                key(appKey) {
                    val animatedNeighborScale by animateFloatAsState(
                        targetValue = 1f - motion.scaleReduction,
                        animationSpec = tween(
                            durationMillis = 260,
                            delayMillis = if (motion.scaleReduction > 0f) 180 else 0
                        ),
                        label = "neighbor_scale"
                    )
                    val animatedNeighborShiftX by animateFloatAsState(
                        targetValue = motion.shiftX,
                        animationSpec = tween(
                            durationMillis = 280,
                            delayMillis = if (motion.shiftX != 0f) 180 else 0
                        ),
                        label = "neighbor_shift_x"
                    )
                    val animatedNeighborShiftY by animateFloatAsState(
                        targetValue = motion.shiftY,
                        animationSpec = tween(
                            durationMillis = 280,
                            delayMillis = if (motion.shiftY != 0f) 180 else 0
                        ),
                        label = "neighbor_shift_y"
                    )
                    val animatedSlotX by animateFloatAsState(
                        targetValue = visualPos.x,
                        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
                        label = "honeycomb_slot_x"
                    )
                    val animatedSlotY by animateFloatAsState(
                        targetValue = visualPos.y,
                        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
                        label = "honeycomb_slot_y"
                    )
                    AppBubble(
                        icon = if (
                            blurEnabled &&
                            effectiveEdgeBlur &&
                            itemBlur > 0.5f
                        ) {
                            app.cachedBlurredIcon
                        } else {
                            app.cachedIcon
                        },
                        size = iconSizeDp,
                        onClick = {
                            val sy = scrollOffset.value
                            val clickPos = if (isDragged) gridPos else visualPos
                            val sx = screenCenterX + clickPos.x
                            val syPos = screenCenterY + clickPos.y + sy
                            onAppClick(app, Offset(sx / screenWidthPx, syPos / screenHeightPx))
                        },
                        onLongClick = null,
                        forcePressed = isDragged || isGlidePressed || menuPressedKey == appKey,
                        pressScaleTarget = if (isDragged) 1.12f else 0.9f,
                        pressAnimationDelayMillis = 0,
                        pressAnimationDurationMillis = HONEYCOMB_PRESS_DURATION_MS,
                        onPressedChange = {},
                        modifier = Modifier
                            .zIndex(if (isDragged) 12f else 0f)
                            .graphicsLayer {
                                val sy = scrollOffset.value
                                val baseX = if (isDragged) gridPos.x else animatedSlotX
                                val baseY = if (isDragged) gridPos.y else animatedSlotY
                                val posX = screenCenterX + baseX
                                val pY = screenCenterY + baseY + sy
                                translationX = posX - iconSizePx / 2f
                                translationY = pY - iconSizePx / 2f
                                if (isDragged) {
                                    val pointer = dragPointer
                                    translationX = ((pointer?.x ?: posX).coerceIn(
                                        iconSizePx * 0.5f,
                                        screenWidthPx - iconSizePx * 0.5f
                                    )) - iconSizePx / 2f
                                    translationY = ((pointer?.y ?: pY).coerceIn(
                                        iconSizePx * 0.5f,
                                        screenHeightPx - iconSizePx * 0.5f
                                    )) - iconSizePx / 2f
                                } else {
                                    translationX += animatedNeighborShiftX
                                    translationY += animatedNeighborShiftY
                                }

                                val dx = posX - screenCenterX
                                val dy = pY - screenCenterY
                                val dist = sqrt(dx * dx + dy * dy)
                                val scale = fisheyeScale(dist, screenRadius * 1.65f, minScale = 0.58f)
                                scaleX = scale * animatedNeighborScale
                                scaleY = scale * animatedNeighborScale
                                alpha = if (settlingKey == app.componentKey) 0f else scale.coerceIn(0.24f, 1f)
                            }
                    )
                }
            }
        }

        val settlingOverlayApp = settlingApp
        if (settlingOverlayApp != null) {
            val settlingBlur = computeHoneycombEdgeBlur(
                centerY = settlingY.value,
                screenHeight = screenHeightPx,
                topBlurZonePx = topFadePx,
                bottomBlurZonePx = bottomFadePx,
                topBlurDp = topBlurRadiusDp.toFloat(),
                bottomBlurDp = bottomBlurRadiusDp.toFloat()
            )
            AppBubble(
                icon = if (blurEnabled && effectiveEdgeBlur && settlingBlur > 0.5f) {
                    settlingOverlayApp.cachedBlurredIcon
                } else {
                    settlingOverlayApp.cachedIcon
                },
                size = iconSizeDp,
                onClick = {},
                forcePressed = true,
                pressScaleTarget = 1.12f,
                pressAnimationDelayMillis = 0,
                pressAnimationDurationMillis = HONEYCOMB_PRESS_DURATION_MS,
                onPressedChange = {},
                modifier = Modifier
                    .zIndex(14f)
                    .graphicsLayer {
                        translationX = settlingX.value - iconSizePx / 2f
                        translationY = settlingY.value - iconSizePx / 2f
                    }
            )
        }

        if (topFadeRangeDp > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(topFadeRangeDp.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.42f),
                                Color.Black.copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        if (bottomFadeRangeDp > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomFadeRangeDp.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.18f),
                                Color.Black.copy(alpha = 0.46f)
                            )
                        )
                    )
            )
        }
    }

    longPressedApp?.let { app ->
        AppShortcutOverlay(app = app, blurEnabled = blurEnabled, onDismiss = { longPressedApp = null })
    }
}

private fun neighborPressMotion(
    current: Offset,
    pressedAnchor: Offset?,
    iconSizePx: Float,
    cellSize: Float
): HoneycombNeighborMotion {
    if (pressedAnchor == null) {
        return HoneycombNeighborMotion()
    }
    val dx = pressedAnchor.x - current.x
    val dy = pressedAnchor.y - current.y
    val distance = sqrt(dx * dx + dy * dy)
    if (distance <= 0.001f) return HoneycombNeighborMotion()
    val range = cellSize * 1.9f
    val progress = (1f - distance / range).coerceIn(0f, 1f)
    if (progress <= 0f) return HoneycombNeighborMotion()

    val pullDistance = iconSizePx * 0.18f * progress
    val sinkDistance = iconSizePx * 0.11f * progress
    return HoneycombNeighborMotion(
        scaleReduction = 0.08f * progress,
        shiftX = dx / distance * pullDistance,
        shiftY = dy / distance * pullDistance + sinkDistance
    )
}

private fun findNearestHoneycombIndex(
    pointer: Offset,
    positions: List<Offset>,
    screenCenterX: Float,
    screenCenterY: Float,
    maxDistance: Float
): Int? {
    var bestIndex: Int? = null
    var bestDistance = Float.MAX_VALUE
    positions.forEachIndexed { index, position ->
        val dx = pointer.x - (screenCenterX + position.x)
        val dy = pointer.y - (screenCenterY + position.y)
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < bestDistance && distance <= maxDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

private fun honeycombVisualSlotIndex(
    index: Int,
    dragFromIndex: Int?,
    dragCurrentIndex: Int?
): Int {
    if (dragFromIndex == null || dragCurrentIndex == null || dragFromIndex == dragCurrentIndex) return index
    if (index == dragFromIndex) return dragFromIndex
    return when {
        dragCurrentIndex > dragFromIndex && index in (dragFromIndex + 1)..dragCurrentIndex -> index - 1
        dragCurrentIndex < dragFromIndex && index in dragCurrentIndex until dragFromIndex -> index + 1
        else -> index
    }
}

private data class HoneycombNeighborMotion(
    val scaleReduction: Float = 0f,
    val shiftX: Float = 0f,
    val shiftY: Float = 0f
)

private fun computeHoneycombEdgeBlur(
    centerY: Float,
    screenHeight: Float,
    topBlurZonePx: Float,
    bottomBlurZonePx: Float,
    topBlurDp: Float,
    bottomBlurDp: Float
): Float {
    val topStrength = if (topBlurZonePx <= 0f) {
        0f
    } else {
        (1f - (centerY / topBlurZonePx)).coerceIn(0f, 1f)
    }
    val bottomDistance = screenHeight - centerY
    val bottomStrength = if (bottomBlurZonePx <= 0f) {
        0f
    } else {
        (1f - (bottomDistance / bottomBlurZonePx)).coerceIn(0f, 1f)
    }
    return maxOf(topStrength * topBlurDp, bottomStrength * bottomBlurDp)
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
            val change = event.changes.firstOrNull { it.id == pointerId }
            if (change == null) {
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
