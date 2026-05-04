package com.flue.launcher.ui.drawer

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.iconForDisplay
import com.flue.launcher.ui.anim.platformBlur
import com.flue.launcher.ui.input.DrawerInputMode
import com.flue.launcher.ui.input.DrawerInputSource
import com.flue.launcher.ui.input.flueDrawerRotaryScrollable
import com.flue.launcher.ui.input.normalizeDrawerScrollDelta
import com.flue.launcher.ui.input.requestFocusAfterFirstFrame
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.util.fisheyeScale
import com.flue.launcher.util.generateHoneycombRows
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val HONEYCOMB_MENU_TRIGGER_MS = 410L
private const val HONEYCOMB_MENU_DRAG_START_DP = 20f
private const val HONEYCOMB_PRESS_DURATION_MS = 200
private const val HONEYCOMB_AUTO_SCROLL_EDGE_DP = 96f
private const val HONEYCOMB_AUTO_SCROLL_MAX_PX = 26f
private const val FAST_FLOW_SLOW_TOP_FRACTION = 0.24f
private const val FAST_FLOW_SLOW_BOTTOM_FRACTION = 0.76f
private const val FAST_FLOW_SLOW_SCALE = 0.56f
private const val FAST_FLOW_FAST_SCALE = 1.85f
private const val FAST_FLOW_MAX_OFFSET_PX = 260f

@Composable
fun HoneycombScreen(
    apps: List<AppInfo>,
    blurEnabled: Boolean = true,
    edgeBlurEnabled: Boolean = false,
    suppressHeavyEffects: Boolean = false,
    fisheyeEnabled: Boolean = true,
    fastFlowAnimationEnabled: Boolean = false,
    twoToneIconsEnabled: Boolean = false,
    iconShadowEnabled: Boolean = true,
    themeColor: Color = Color(0xFF7BE8FF),
    uiStyle: UiStyle = UiStyle.APPLE_WATCH,
    darkMode: Boolean = true,
    narrowCols: Int = 4,
    topBlurRadiusDp: Float = 4f,
    bottomBlurRadiusDp: Float = 4f,
    topFadeRangeDp: Int = 56,
    bottomFadeRangeDp: Int = 56,
    fastScrollOptimizationEnabled: Boolean = true,
    active: Boolean = true,
    leftSafeInsetPercent: Int = 0,
    appListScalePercent: Int = 100,
    useWatchFaceColors: Boolean = true,
    onAppClick: (AppInfo, Offset) -> Unit,
    onReorder: (Int, Int) -> Unit = { _, _ -> },
    onLongClick: (AppInfo) -> Unit = {},
    onExcludeApp: (AppInfo) -> Unit = {},
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (uiStyle == UiStyle.MATERIAL_3) {
        MaterialDenseGridScreen(
            apps = apps,
            blurEnabled = blurEnabled,
            twoToneIconsEnabled = twoToneIconsEnabled,
            iconShadowEnabled = iconShadowEnabled,
            themeColor = themeColor,
            darkMode = darkMode,
            columns = (narrowCols + 1).coerceIn(2, 6),
            active = active,
            leftSafeInsetPercent = leftSafeInsetPercent,
            appListScalePercent = appListScalePercent,
            useWatchFaceColors = useWatchFaceColors,
            onAppClick = onAppClick,
            onLongClick = onLongClick,
            onExcludeApp = onExcludeApp,
            onScrollToTop = onScrollToTop,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val launcherStyle = LauncherTheme.style
    val focusRequester = remember { FocusRequester() }
    var longPressedApp by remember { mutableStateOf<AppInfo?>(null) }
    var glidePressedKey by remember { mutableStateOf<String?>(null) }
    var dragFromIndex by remember { mutableStateOf<Int?>(null) }
    var dragCurrentIndex by remember { mutableStateOf<Int?>(null) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var dragApp by remember { mutableStateOf<AppInfo?>(null) }
    var settlingApp by remember { mutableStateOf<AppInfo?>(null) }
    var settlingKey by remember { mutableStateOf<String?>(null) }
    val settlingX = remember { Animatable(0f) }
    val settlingY = remember { Animatable(0f) }
    val effectiveEdgeBlur = edgeBlurEnabled && !suppressHeavyEffects
    var focusReady by remember { mutableStateOf(false) }
    var wheelMomentumJob by remember { mutableStateOf<Job?>(null) }
    var initialScrollPositionResolved by remember { mutableStateOf(false) }
    var directScrollOffset by remember { mutableFloatStateOf(Float.NaN) }
    var dragScrollActive by remember { mutableStateOf(false) }
    var returnTriggered by remember { mutableStateOf(false) }
    var fastScrollActive by remember { mutableStateOf(false) }
    var fastScrollResetJob by remember { mutableStateOf<Job?>(null) }
    val appPalette = appListPalette(themeColor, darkMode, uiStyle, useWatchFaceColors)

    LaunchedEffect(focusReady) {
        if (focusReady) {
            focusRequester.requestFocusAfterFirstFrame()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val leftSafeInsetPx = screenWidthPx * (leftSafeInsetPercent.coerceIn(0, 50) / 100f)
        val iconShadowElevationPx by animateFloatAsState(
            targetValue = if (iconShadowEnabled) with(density) { 8.dp.toPx() } else 0f,
            animationSpec = tween(durationMillis = if (iconShadowEnabled) 120 else 0),
            label = "honeycomb_icon_shadow"
        )
        val overlayShadowElevationPx by animateFloatAsState(
            targetValue = if (iconShadowEnabled) with(density) { 12.dp.toPx() } else 0f,
            animationSpec = tween(durationMillis = if (iconShadowEnabled) 120 else 0),
            label = "honeycomb_overlay_shadow"
        )
        val contentWidthPx = (screenWidthPx - leftSafeInsetPx).coerceAtLeast(screenWidthPx * 0.55f)
        val screenCenterX = leftSafeInsetPx + contentWidthPx / 2f
        val screenCenterY = screenHeightPx / 2f
        val screenRadius = minOf(contentWidthPx, screenHeightPx) / 2f
        val appListScale = appListScalePercent.coerceIn(50, 200) / 100f

        val maxCols = narrowCols + 1
        val availableWidth = contentWidthPx - with(density) { 20.dp.toPx() }
        val baseIconSizePx = (availableWidth / (maxCols + 0.35f)).coerceIn(
            with(density) { 54.dp.toPx() },
            with(density) { 84.dp.toPx() }
        )
        val iconSizePx = (baseIconSizePx * appListScale).coerceIn(
            with(density) { 28.dp.toPx() },
            with(density) { 148.dp.toPx() }
        )
        val iconSizeDp = with(density) { iconSizePx.toDp() }
        val cellSize = iconSizePx * 1.02f
        val topFadePx = with(density) { topFadeRangeDp.dp.toPx() }
        val bottomFadePx = with(density) { bottomFadeRangeDp.dp.toPx() }

        val positions = remember(apps.size, narrowCols, cellSize) {
            generateHoneycombRows(apps.size, narrowCols, cellSize)
        }
        val rowInfo = remember(positions) { buildHoneycombRowInfo(positions) }
        val appIndexByKey = remember(apps) {
            apps.mapIndexed { index, app -> app.componentKey to index }.toMap()
        }

        val minGridY = positions.minOfOrNull { it.y } ?: 0f
        val maxGridY = positions.maxOfOrNull { it.y } ?: 0f
        val maxScroll = -minGridY
        val minScroll = -maxGridY

        val scrollOffset = remember { Animatable(maxScroll) }
        val scope = rememberCoroutineScope()
        val overlayBlurActive = longPressedApp != null && blurEnabled && !suppressHeavyEffects
        val honeycombAutoScrollEdgePx = with(density) { HONEYCOMB_AUTO_SCROLL_EDGE_DP.dp.toPx() }
        val fastDragThresholdPx = with(density) { 10.dp.toPx() }
        fun currentScrollOffsetValue(): Float = resolveHoneycombScrollOffset(
            directScrollOffset = directScrollOffset,
            animatedScrollOffset = scrollOffset.value
        )
        fun reducedMotionPhase(): Boolean =
            fastScrollOptimizationEnabled && (fastScrollActive || scrollOffset.isRunning)
        fun markFastScrollActive(durationMs: Long = 180L) {
            if (!fastScrollOptimizationEnabled) return
            fastScrollActive = true
            fastScrollResetJob?.cancel()
            fastScrollResetJob = scope.launch {
                delay(durationMs)
                fastScrollActive = false
            }
        }
        fun launchWheelScroll(delta: Float) {
            if (delta == 0f) return
            wheelMomentumJob?.cancel()
            wheelMomentumJob = scope.launch {
                if (scrollOffset.isRunning) {
                    scrollOffset.stop()
                }
                markFastScrollActive(220L)
                val start = currentScrollOffsetValue()
                scrollOffset.snapTo((start + delta).coerceIn(minScroll, maxScroll))
                directScrollOffset = Float.NaN
                var tail = delta * 0.55f
                repeat(6) {
                    withFrameNanos { }
                    if (abs(tail) < 0.5f) return@launch
                    val next = (scrollOffset.value + tail).coerceIn(minScroll, maxScroll)
                    scrollOffset.snapTo(next)
                    if (next == minScroll || next == maxScroll) return@launch
                    tail *= 0.55f
                }
            }
        }
        LaunchedEffect(apps.size, minScroll, maxScroll, dragScrollActive) {
            if (apps.isEmpty()) {
                initialScrollPositionResolved = false
            } else if (!initialScrollPositionResolved) {
                initialScrollPositionResolved = true
                if (dragScrollActive || !directScrollOffset.isNaN()) {
                    if (!directScrollOffset.isNaN()) {
                        directScrollOffset = directScrollOffset.coerceIn(minScroll, maxScroll)
                    }
                } else {
                    scrollOffset.snapTo(maxScroll)
                }
            }
        }
        LaunchedEffect(minScroll, maxScroll) {
            val clampedDirect = if (directScrollOffset.isNaN()) Float.NaN else directScrollOffset.coerceIn(minScroll, maxScroll)
            if (!clampedDirect.isNaN() && clampedDirect != directScrollOffset) {
                directScrollOffset = clampedDirect
            }
            val clampedAnimated = scrollOffset.value.coerceIn(minScroll, maxScroll)
            if (clampedAnimated != scrollOffset.value) {
                scrollOffset.snapTo(clampedAnimated)
            }
        }
        LaunchedEffect(active, minScroll, maxScroll) {
            if (!active) {
                longPressedApp = null
                glidePressedKey = null
                dragFromIndex = null
                dragCurrentIndex = null
                dragPointer = null
                dragApp = null
                if (!returnTriggered) {
                    directScrollOffset = Float.NaN
                    scrollOffset.snapTo(scrollOffset.value.coerceIn(minScroll, maxScroll))
                }
            } else {
                returnTriggered = false
                focusRequester.requestFocusAfterFirstFrame()
            }
        }
        LaunchedEffect(fastScrollOptimizationEnabled) {
            if (!fastScrollOptimizationEnabled) {
                fastScrollResetJob?.cancel()
                fastScrollActive = false
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
                        val displayPointer = clampHoneycombDisplayPointer(
                            pointer = pointer,
                            screenWidthPx = screenWidthPx,
                            screenHeightPx = screenHeightPx,
                            iconSizePx = iconSizePx
                        )
                        val autoScrollVelocity = when {
                            pointer.y in (-iconSizePx)..honeycombAutoScrollEdgePx -> {
                                HONEYCOMB_AUTO_SCROLL_MAX_PX * 60f * ((honeycombAutoScrollEdgePx - pointer.y) / honeycombAutoScrollEdgePx).coerceIn(0f, 1.15f)
                            }
                            pointer.y in (screenHeightPx - honeycombAutoScrollEdgePx)..(screenHeightPx + iconSizePx) -> {
                                -HONEYCOMB_AUTO_SCROLL_MAX_PX * 60f * ((pointer.y - (screenHeightPx - honeycombAutoScrollEdgePx)) / honeycombAutoScrollEdgePx).coerceIn(0f, 1.15f)
                            }
                            else -> 0f
                        }
                        if (autoScrollVelocity != 0f) {
                            val current = currentScrollOffsetValue()
                            val next = (current + autoScrollVelocity * frameDeltaSeconds).coerceIn(minScroll, maxScroll)
                            if (next != current) {
                                pendingScrollTarget = next
                            }
                        }
                        dragCurrentIndex = findNearestHoneycombIndex(
                            pointer = displayPointer,
                            positions = positions,
                            screenCenterX = screenCenterX,
                            screenCenterY = screenCenterY + currentScrollOffsetValue(),
                            maxDistance = cellSize * 0.95f
                        ) ?: dragCurrentIndex
                    }
                }
                pendingScrollTarget?.let { directScrollOffset = it }
            }
        }

        val currentScroll = currentScrollOffsetValue()
        val fastFlowSlowTopY = screenHeightPx * FAST_FLOW_SLOW_TOP_FRACTION
        val fastFlowSlowBottomY = screenHeightPx * FAST_FLOW_SLOW_BOTTOM_FRACTION

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(appPalette.background)
                .flueDrawerRotaryScrollable(
                    focusRequester,
                    DrawerInputMode.Honeycomb,
                    onRotaryScroll = { vibrateHaptic(context) }
                ) { rotaryDelta ->
                    launchWheelScroll(-rotaryDelta)
                }
                .onGloballyPositioned {
                    if (!focusReady) focusReady = true
                }
                .pointerInput(minScroll, maxScroll) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val delta = normalizeDrawerScrollDelta(
                                    verticalScrollPixels = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f,
                                    source = DrawerInputSource.MouseWheel,
                                    mode = DrawerInputMode.Honeycomb
                                )
                                if (delta != 0f) {
                                    launchWheelScroll(delta)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
                .platformBlur(16f, overlayBlurActive)
                .pointerInput(apps, positions) {
                    val menuDragStartPx = with(density) { HONEYCOMB_MENU_DRAG_START_DP.dp.toPx() }
                    try {
                        awaitEachGesture {
                            val down = awaitPrimaryDown()
                            val startIndex = findNearestHoneycombIndex(
                                pointer = down.position,
                                positions = positions,
                                screenCenterX = screenCenterX,
                                screenCenterY = screenCenterY + currentScrollOffsetValue(),
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
                                if (!change.pressed) {
                                    if (!dragActive) {
                                        change.consume()
                                    }
                                    break
                                }

                                val pointer = change.position
                                val movedDistance = (pointer - dragOrigin).getDistance()
                                if (!dragActive && movedDistance > menuDragStartPx) {
                                    longPressedApp = null
                                    dragActive = true
                                    dragFromIndex = startIndex
                                    dragCurrentIndex = startIndex
                                    dragPointer = pointer
                                    dragApp = app
                                    glidePressedKey = null
                                    vibrateHaptic(context)
                                }

                                val fromIndex = dragFromIndex
                                if (dragActive && fromIndex != null) {
                                    dragPointer = pointer
                                    if ((pointer - dragOrigin).getDistance() > menuDragStartPx * 0.35f) {
                                        hasDragged = true
                                    }
                                    val displayPointer = clampHoneycombDisplayPointer(
                                        pointer = pointer,
                                        screenWidthPx = screenWidthPx,
                                        screenHeightPx = screenHeightPx,
                                        iconSizePx = iconSizePx
                                    )
                                    val dragTarget = findNearestHoneycombIndex(
                                        pointer = displayPointer,
                                        positions = positions,
                                        screenCenterX = screenCenterX,
                                        screenCenterY = screenCenterY + currentScrollOffsetValue(),
                                        maxDistance = cellSize * 0.95f
                                    )
                                    dragCurrentIndex = dragTarget ?: fromIndex
                                    change.consume()
                                }
                            }

                            val from = dragFromIndex
                            val to = dragCurrentIndex
                            val releasePointer = dragPointer
                            val releaseScroll = currentScrollOffsetValue()
                            if (dragActive && from != null && to != null && from != to && hasDragged) {
                                val droppedApp = apps.getOrNull(from)
                                val targetSlot = positions.getOrNull(to)
                                dragFromIndex = null
                                dragCurrentIndex = null
                                dragPointer = null
                                dragApp = null
                                glidePressedKey = null
                                if (droppedApp != null && releasePointer != null && targetSlot != null) {
                                    settlingApp = droppedApp
                                    settlingKey = droppedApp.componentKey
                                    scope.launch {
                                        settlingX.snapTo(releasePointer.x.coerceIn(iconSizePx * 0.5f, screenWidthPx - iconSizePx * 0.5f))
                                        settlingY.snapTo(releasePointer.y.coerceIn(iconSizePx * 0.5f, screenHeightPx - iconSizePx * 0.5f))
                                        launch {
                                            settlingX.animateTo(screenCenterX + targetSlot.x, tween(durationMillis = 120))
                                        }
                                        launch {
                                            settlingY.animateTo(
                                                (screenCenterY + targetSlot.y + releaseScroll).coerceIn(
                                                    iconSizePx * 0.5f,
                                                    screenHeightPx - iconSizePx * 0.5f
                                                ),
                                                tween(durationMillis = 120)
                                            )
                                        }
                                        delay(180)
                                        settlingApp = null
                                        settlingKey = null
                                        settlingX.snapTo(0f)
                                        settlingY.snapTo(0f)
                                    }
                                }
                                onReorder(from, to)
                            } else {
                                dragFromIndex = null
                                dragCurrentIndex = null
                                dragPointer = null
                                dragApp = null
                                glidePressedKey = null
                                settlingApp = null
                                settlingKey = null
                            }
                        }
                    } finally {
                        dragFromIndex = null
                        dragCurrentIndex = null
                        dragPointer = null
                        dragApp = null
                        glidePressedKey = null
                    }
                }
                .pointerInput(apps, positions, minScroll, maxScroll) {
                    val velocityTracker = VelocityTracker()
                    try {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                if (dragFromIndex != null || longPressedApp != null) return@detectDragGestures
                                dragScrollActive = true
                                wheelMomentumJob?.cancel()
                                scope.launch { scrollOffset.stop() }
                                directScrollOffset = currentScrollOffsetValue()
                                velocityTracker.resetTracking()
                                val hoverIndex = findNearestHoneycombIndex(
                                    pointer = startOffset,
                                    positions = positions,
                                    screenCenterX = screenCenterX,
                                    screenCenterY = screenCenterY + currentScrollOffsetValue(),
                                    maxDistance = iconSizePx * 0.9f
                                )
                                glidePressedKey = hoverIndex?.let { apps.getOrNull(it)?.componentKey }
                            },
                            onDrag = { change, dragAmount ->
                                if (dragFromIndex != null || longPressedApp != null) return@detectDragGestures
                                if (returnTriggered) {
                                    change.consume()
                                    return@detectDragGestures
                                }
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val hoverIndex = findNearestHoneycombIndex(
                                    pointer = change.position,
                                    positions = positions,
                                    screenCenterX = screenCenterX,
                                    screenCenterY = screenCenterY + currentScrollOffsetValue(),
                                    maxDistance = iconSizePx * 0.9f
                                )
                                glidePressedKey = hoverIndex?.let { apps.getOrNull(it)?.componentKey }
                                val current = currentScrollOffsetValue()
                                val next = current + dragAmount.y
                                val overscroll = when {
                                    next > maxScroll -> next - maxScroll
                                    next < minScroll -> next - minScroll
                                    else -> 0f
                                }
                                if (abs(dragAmount.y) >= fastDragThresholdPx) {
                                    markFastScrollActive(180L)
                                }
                                val dampedDrag = if (overscroll != 0f) dragAmount.y * 0.28f else dragAmount.y
                                val nextScroll = current + dampedDrag
                                directScrollOffset = nextScroll
                                if (nextScroll >= maxScroll + iconSizePx * 0.28f) {
                                    returnTriggered = true
                                    onScrollToTop()
                                    scope.launch {
                                        scrollOffset.snapTo(nextScroll)
                                        directScrollOffset = Float.NaN
                                        scrollOffset.animateTo(
                                            maxScroll,
                                            spring(dampingRatio = 0.64f, stiffness = 360f)
                                        )
                                        returnTriggered = false
                                        dragScrollActive = false
                                    }
                                }
                            },
                            onDragEnd = {
                                if (returnTriggered) {
                                    glidePressedKey = null
                                    dragScrollActive = false
                                    return@detectDragGestures
                                }
                                val velocity = velocityTracker.calculateVelocity().y
                                val current = currentScrollOffsetValue()
                                glidePressedKey = null
                                if (abs(velocity) > 1600f) {
                                    markFastScrollActive(260L)
                                }
                                directScrollOffset = current
                                if (
                                    current >= maxScroll + iconSizePx * 0.28f ||
                                    (current >= maxScroll - iconSizePx * 0.35f && velocity > 700f)
                                ) {
                                    val returnStart = current
                                    returnTriggered = true
                                    onScrollToTop()
                                    scope.launch {
                                        scrollOffset.snapTo(returnStart)
                                        directScrollOffset = Float.NaN
                                        scrollOffset.animateTo(
                                            maxScroll,
                                            spring(dampingRatio = 0.64f, stiffness = 360f)
                                        )
                                        returnTriggered = false
                                        dragScrollActive = false
                                    }
                                    return@detectDragGestures
                                }
                                scope.launch {
                                    try {
                                        scrollOffset.snapTo(current)
                                        directScrollOffset = Float.NaN
                                        if (current < minScroll || current > maxScroll) {
                                            scrollOffset.animateTo(
                                                current.coerceIn(minScroll, maxScroll),
                                                spring(dampingRatio = 0.64f, stiffness = 360f)
                                            )
                                        } else {
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
                                    } finally {
                                        dragScrollActive = false
                                    }
                                }
                            },
                            onDragCancel = {
                                glidePressedKey = null
                                val current = currentScrollOffsetValue()
                                directScrollOffset = current
                                scope.launch {
                                    try {
                                        scrollOffset.snapTo(current)
                                        directScrollOffset = Float.NaN
                                    } finally {
                                        dragScrollActive = false
                                    }
                                }
                            }
                        )
                    } finally {
                        if (dragScrollActive && !returnTriggered) {
                            val current = currentScrollOffsetValue().coerceIn(minScroll, maxScroll)
                            scope.launch {
                                scrollOffset.snapTo(current)
                                directScrollOffset = Float.NaN
                                dragScrollActive = false
                            }
                        }
                    }
                }
        ) {
            val visibleTop = -iconSizePx * 1.5f
            val visibleBottom = screenHeightPx + iconSizePx * 1.5f
            val dragOverlayApp = dragApp
            val menuPressedKey = longPressedApp?.componentKey
            val menuPressedIndex = menuPressedKey?.let(appIndexByKey::get)
            val pressedAnchor = when {
                dragFromIndex == null && menuPressedIndex != null && menuPressedIndex in positions.indices -> positions[menuPressedIndex]
                else -> null
            }
            val renderIndexes = remember(
                rowInfo,
                currentScroll,
                screenCenterY,
                screenHeightPx,
                iconSizePx,
                menuPressedIndex,
                dragFromIndex,
                dragCurrentIndex
            ) {
                computeHoneycombRenderIndexes(
                    rows = rowInfo,
                    currentScroll = currentScroll,
                    screenCenterY = screenCenterY,
                    screenHeightPx = screenHeightPx,
                    iconSizePx = iconSizePx,
                    bufferRows = if (reducedMotionPhase()) 1 else 2,
                    pinnedIndexes = listOfNotNull(menuPressedIndex, dragFromIndex, dragCurrentIndex)
                )
            }
            val dragOverlayPointer = dragPointer?.let {
                clampHoneycombDisplayPointer(
                    pointer = it,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    iconSizePx = iconSizePx
                )
            }
            val dragScalePointer = dragPointer

            renderIndexes.forEach { index ->
                val app = apps.getOrNull(index) ?: return@forEach
                val visualSlotIndex = honeycombVisualSlotIndex(index, dragFromIndex, dragCurrentIndex)
                val gridPos = positions[index]
                val visualPos = positions.getOrNull(visualSlotIndex) ?: gridPos
                val appKey = app.componentKey
                val isDragged = dragFromIndex == index
                val slotCenter = Offset(
                    x = screenCenterX + visualPos.x,
                    y = screenCenterY + visualPos.y + currentScroll
                )
                val dragDisplayPointer = if (isDragged) {
                    clampHoneycombDisplayPointer(
                        pointer = dragPointer ?: Offset(
                            screenCenterX + gridPos.x,
                            screenCenterY + gridPos.y + currentScroll
                        ),
                        screenWidthPx = screenWidthPx,
                        screenHeightPx = screenHeightPx,
                        iconSizePx = iconSizePx
                    )
                } else {
                    null
                }
                val currentDragScalePointer = if (isDragged) {
                    dragScalePointer ?: dragDisplayPointer ?: slotCenter
                } else {
                    null
                }
                val visibilityY = dragDisplayPointer?.y ?: slotCenter.y
                if (!isDragged && index != menuPressedIndex && (visibilityY < visibleTop || visibilityY > visibleBottom)) {
                    return@forEach
                }
                val itemBlur = computeHoneycombEdgeBlur(
                    centerY = visibilityY,
                    screenHeight = screenHeightPx,
                    topBlurZonePx = topFadePx,
                    bottomBlurZonePx = bottomFadePx,
                    topBlurDp = topBlurRadiusDp,
                    bottomBlurDp = bottomBlurRadiusDp
                )
                val isGlidePressed = glidePressedKey == appKey
                val motion = neighborPressMotion(
                    current = visualPos,
                    pressedAnchor = pressedAnchor,
                    iconSizePx = iconSizePx,
                    cellSize = cellSize
                )
                val reduceVisualLoad = reducedMotionPhase() &&
                    !isDragged &&
                    settlingKey != app.componentKey &&
                    menuPressedKey != appKey &&
                    !isGlidePressed

                key(appKey) {
                    val neighborScale = animateOrSnapFloat(
                        animate = !reduceVisualLoad,
                        targetValue = 1f - motion.scaleReduction,
                        animationSpec = tween(
                            durationMillis = 260,
                            delayMillis = if (motion.scaleReduction > 0f) 180 else 0
                        ),
                        label = "neighbor_scale"
                    )
                    val neighborShiftX = animateOrSnapFloat(
                        animate = !reduceVisualLoad,
                        targetValue = motion.shiftX,
                        animationSpec = tween(
                            durationMillis = 280,
                            delayMillis = if (motion.shiftX != 0f) 180 else 0
                        ),
                        label = "neighbor_shift_x"
                    )
                    val neighborShiftY = animateOrSnapFloat(
                        animate = !reduceVisualLoad,
                        targetValue = motion.shiftY,
                        animationSpec = tween(
                            durationMillis = 280,
                            delayMillis = if (motion.shiftY != 0f) 180 else 0
                        ),
                        label = "neighbor_shift_y"
                    )
                    val slotX = animateOrSnapFloat(
                        animate = !reduceVisualLoad,
                        targetValue = visualPos.x,
                        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
                        label = "honeycomb_slot_x"
                    )
                    val slotY = animateOrSnapFloat(
                        animate = !reduceVisualLoad,
                        targetValue = visualPos.y,
                        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
                        label = "honeycomb_slot_y"
                    )
                    val effectiveItemBlur = reduceHoneycombBlurForFastScroll(itemBlur, reduceVisualLoad)
                    AppBubble(
                        icon = app.iconForDisplay(
                            useTwoTone = twoToneIconsEnabled,
                            blurred = blurEnabled &&
                                effectiveEdgeBlur &&
                                effectiveItemBlur > 0.5f &&
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        ),
                        size = iconSizeDp,
                        tintColor = Color.Transparent,
                        shape = launcherStyle.bubbleShape,
                        onClick = {
                            if (longPressedApp == null && dragFromIndex == null) {
                                val clickPos = if (isDragged) gridPos else visualPos
                                val flowOffset = honeycombFlowOffset(
                                    enabled = fastFlowAnimationEnabled,
                                    rowCenterY = screenCenterY + clickPos.y + currentScroll,
                                    screenCenterY = screenCenterY,
                                    slowTopY = fastFlowSlowTopY,
                                    slowBottomY = fastFlowSlowBottomY
                                )
                                val sx = screenCenterX + clickPos.x
                                val syPos = screenCenterY + clickPos.y + currentScroll + flowOffset
                                onAppClick(app, Offset(sx / screenWidthPx, syPos / screenHeightPx))
                            }
                        },
                        onLongClick = null,
                        forcePressed = isGlidePressed || menuPressedKey == appKey,
                        pressScaleTarget = 0.9f,
                        pressAnimationDelayMillis = 0,
                        pressAnimationDurationMillis = HONEYCOMB_PRESS_DURATION_MS,
                        onPressedChange = {},
                        modifier = Modifier
                            .zIndex(0f)
                            .graphicsLayer {
                                val baseX = slotX
                                val baseY = slotY
                                val flowOffset = honeycombFlowOffset(
                                    enabled = fastFlowAnimationEnabled,
                                    rowCenterY = screenCenterY + baseY + currentScroll,
                                    screenCenterY = screenCenterY,
                                    slowTopY = fastFlowSlowTopY,
                                    slowBottomY = fastFlowSlowBottomY
                                )
                                val posX = screenCenterX + baseX
                                val pY = screenCenterY + baseY + currentScroll + flowOffset
                                var actualCenterX = posX
                                var actualCenterY = pY
                                translationX = posX - iconSizePx / 2f
                                translationY = pY - iconSizePx / 2f
                                if (!isDragged) {
                                    translationX += neighborShiftX
                                    translationY += neighborShiftY
                                    actualCenterX += neighborShiftX
                                    actualCenterY += neighborShiftY
                                } else {
                                    val scalePointer = currentDragScalePointer ?: dragDisplayPointer ?: Offset(posX, pY)
                                    actualCenterX = scalePointer.x
                                    actualCenterY = scalePointer.y
                                }

                                val dx = actualCenterX - screenCenterX
                                val dy = actualCenterY - screenCenterY
                                val dist = sqrt(dx * dx + dy * dy)
                                val scale = if (fisheyeEnabled) {
                                    fisheyeScale(dist, screenRadius * 1.65f, minScale = 0.58f)
                                } else {
                                    1f
                                }
                                scaleX = scale * neighborScale
                                scaleY = scale * neighborScale
                                val itemAlpha = when {
                                    isDragged -> 0f
                                    settlingKey == app.componentKey -> 0f
                                    else -> if (fisheyeEnabled) scale.coerceIn(0.24f, 1f) else 1f
                                }
                                alpha = itemAlpha
                                shadowElevation = if (itemAlpha > 0.001f) iconShadowElevationPx else 0f
                                shape = CircleShape
                            }
                            .platformBlur(
                                effectiveItemBlur,
                                blurEnabled && effectiveEdgeBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            )
                    )
                }
            }

            if (dragOverlayApp != null && dragOverlayPointer != null) {
                val dragOverlayBlur = computeHoneycombEdgeBlur(
                    centerY = dragOverlayPointer.y,
                    screenHeight = screenHeightPx,
                    topBlurZonePx = topFadePx,
                    bottomBlurZonePx = bottomFadePx,
                    topBlurDp = topBlurRadiusDp,
                    bottomBlurDp = bottomBlurRadiusDp
                )
                val scalePointer = dragPointer ?: dragOverlayPointer
                val dragDx = scalePointer.x - screenCenterX
                val dragDy = scalePointer.y - screenCenterY
                val dragDist = sqrt(dragDx * dragDx + dragDy * dragDy)
                val dragScale = if (fisheyeEnabled) {
                    fisheyeScale(dragDist, screenRadius * 1.65f, minScale = 0.58f)
                } else {
                    1f
                }
                AppBubble(
                    icon = dragOverlayApp.iconForDisplay(
                        useTwoTone = twoToneIconsEnabled,
                        blurred = blurEnabled && effectiveEdgeBlur && dragOverlayBlur > 0.5f && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    ),
                    size = iconSizeDp,
                    tintColor = Color.Transparent,
                    shape = launcherStyle.bubbleShape,
                    onClick = {},
                    onLongClick = null,
                    forcePressed = false,
                    pressScaleTarget = 1f,
                    pressAnimationDelayMillis = 0,
                    pressAnimationDurationMillis = HONEYCOMB_PRESS_DURATION_MS,
                    onPressedChange = {},
                    modifier = (if (
                        blurEnabled &&
                        effectiveEdgeBlur &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        dragOverlayBlur > 0.5f
                    ) {
                        Modifier.platformBlur(dragOverlayBlur, true)
                    } else {
                        Modifier
                    }).zIndex(13f)
                        .graphicsLayer {
                            translationX = dragOverlayPointer.x - iconSizePx / 2f
                            translationY = dragOverlayPointer.y - iconSizePx / 2f
                            scaleX = dragScale
                            scaleY = dragScale
                            alpha = if (fisheyeEnabled) dragScale.coerceIn(0.24f, 1f) else 1f
                            shadowElevation = overlayShadowElevationPx
                            shape = CircleShape
                        }
                )
            }
        }

        val settlingOverlayApp = settlingApp
        if (settlingOverlayApp != null) {
            val settlingBlur = computeHoneycombEdgeBlur(
                centerY = settlingY.value,
                screenHeight = screenHeightPx,
                topBlurZonePx = topFadePx,
                bottomBlurZonePx = bottomFadePx,
                topBlurDp = topBlurRadiusDp,
                bottomBlurDp = bottomBlurRadiusDp
            )
            AppBubble(
                icon = settlingOverlayApp.iconForDisplay(
                    useTwoTone = twoToneIconsEnabled,
                    blurred = blurEnabled && effectiveEdgeBlur && settlingBlur > 0.5f && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ),
                size = iconSizeDp,
                tintColor = Color.Transparent,
                shape = launcherStyle.bubbleShape,
                onClick = {},
                forcePressed = false,
                pressScaleTarget = 1f,
                pressAnimationDelayMillis = 0,
                pressAnimationDurationMillis = HONEYCOMB_PRESS_DURATION_MS,
                onPressedChange = {},
                modifier = (if (
                    blurEnabled &&
                    effectiveEdgeBlur &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    settlingBlur > 0.5f
                ) {
                    Modifier.platformBlur(settlingBlur, true)
                } else {
                    Modifier
                }).zIndex(14f)
                    .graphicsLayer {
                        translationX = settlingX.value - iconSizePx / 2f
                        translationY = settlingY.value - iconSizePx / 2f
                        val dx = settlingX.value - screenCenterX
                        val dy = settlingY.value - screenCenterY
                        val dist = sqrt(dx * dx + dy * dy)
                        val scale = if (fisheyeEnabled) {
                            fisheyeScale(dist, screenRadius * 1.65f, minScale = 0.58f)
                        } else {
                            1f
                        }
                        scaleX = scale
                        scaleY = scale
                        alpha = if (fisheyeEnabled) scale.coerceIn(0.24f, 1f) else 1f
                        shadowElevation = overlayShadowElevationPx
                        shape = CircleShape
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
                                appPalette.fadeEdge,
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
                                appPalette.fadeEdge
                            )
                        )
                    )
            )
        }
    }

    longPressedApp?.let { app ->
        AppShortcutOverlay(
            app = app,
            blurEnabled = blurEnabled,
            onExcludeApp = { onExcludeApp(app) },
            onDismiss = { longPressedApp = null }
        )
    }
}
@Composable
private fun MaterialDenseGridScreen(
    apps: List<AppInfo>,
    blurEnabled: Boolean,
    twoToneIconsEnabled: Boolean,
    iconShadowEnabled: Boolean,
    themeColor: Color,
    darkMode: Boolean,
    columns: Int,
    active: Boolean,
    leftSafeInsetPercent: Int,
    appListScalePercent: Int,
    useWatchFaceColors: Boolean,
    onAppClick: (AppInfo, Offset) -> Unit,
    onLongClick: (AppInfo) -> Unit,
    onExcludeApp: (AppInfo) -> Unit,
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val launcherStyle = LauncherTheme.style
    val appPalette = appListPalette(themeColor, darkMode, UiStyle.MATERIAL_3, useWatchFaceColors)
    val iconShadowElevationPx by animateFloatAsState(
        targetValue = if (iconShadowEnabled) with(density) { 8.dp.toPx() } else 0f,
        animationSpec = tween(durationMillis = if (iconShadowEnabled) 120 else 0),
        label = "material_grid_icon_shadow"
    )
    val appListScale = appListScalePercent.coerceIn(50, 200) / 100f
    val effectiveColumns = (columns / appListScale).roundToInt().coerceIn(1, 10)
    var focusReady by remember { mutableStateOf(false) }
    var longPressedApp by remember { mutableStateOf<AppInfo?>(null) }
    var returnTriggered by remember { mutableStateOf(false) }
    val overscroll = remember { Animatable(0f) }
    val topReturnThresholdPx = with(density) { 72.dp.toPx() }
    val overscrollLimitPx = with(density) { 140.dp.toPx() }

    LaunchedEffect(active) {
        if (!active) {
            longPressedApp = null
            if (!returnTriggered) {
                overscroll.snapTo(0f)
            }
        } else {
            returnTriggered = false
            focusRequester.requestFocusAfterFirstFrame()
        }
    }

    val nestedScrollConnection = remember(gridState, onScrollToTop) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val atTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                val atBottom = lastVisible != null &&
                    lastVisible.index >= gridState.layoutInfo.totalItemsCount - 1 &&
                    lastVisible.offset.y + lastVisible.size.height <= gridState.layoutInfo.viewportEndOffset
                if (available.y > 0f && atTop) {
                    val next = (overscroll.value + available.y).coerceAtMost(overscrollLimitPx)
                    if (!returnTriggered && next >= topReturnThresholdPx) {
                        returnTriggered = true
                        onScrollToTop()
                        scope.launch {
                            overscroll.snapTo(next)
                            overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                            returnTriggered = false
                        }
                        return Offset(0f, available.y)
                    }
                    scope.launch {
                        overscroll.snapTo(next)
                    }
                    return Offset(0f, available.y)
                }
                if (available.y < 0f && atBottom) {
                    scope.launch {
                        overscroll.snapTo((overscroll.value + available.y).coerceAtLeast(-overscrollLimitPx))
                    }
                    return Offset(0f, available.y)
                }
                if (overscroll.value > 0f && available.y < 0f) {
                    scope.launch {
                        overscroll.snapTo((overscroll.value + available.y).coerceAtLeast(0f))
                    }
                    return Offset(0f, available.y)
                }
                if (overscroll.value < 0f && available.y > 0f) {
                    scope.launch {
                        overscroll.snapTo((overscroll.value + available.y).coerceAtMost(0f))
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll.value != 0f) {
                    val shouldReturnToFace = overscroll.value >= topReturnThresholdPx
                    if (shouldReturnToFace && !returnTriggered) {
                        returnTriggered = true
                        onScrollToTop()
                    }
                    overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                    returnTriggered = false
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(focusReady) {
        if (focusReady) {
            focusRequester.requestFocusAfterFirstFrame()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(appPalette.background)
            .flueDrawerRotaryScrollable(
                focusRequester,
                DrawerInputMode.Honeycomb,
                onRotaryScroll = { vibrateHaptic(context) }
            ) { rotaryDelta ->
                scope.launch { gridState.scrollBy(-rotaryDelta) }
            }
            .nestedScroll(nestedScrollConnection)
            .graphicsLayer { translationY = overscroll.value }
            .onGloballyPositioned {
                if (!focusReady) focusReady = true
            }
            .pointerInput(gridState) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = normalizeDrawerScrollDelta(
                                verticalScrollPixels = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f,
                                source = DrawerInputSource.MouseWheel,
                                mode = DrawerInputMode.Honeycomb
                            )
                            if (delta != 0f) {
                                scope.launch { gridState.scrollBy(delta) }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            .pointerInput(onScrollToTop) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    if (overscroll.value != 0f) {
                        val shouldReturnToFace = overscroll.value >= topReturnThresholdPx
                        scope.launch {
                            if (shouldReturnToFace && !returnTriggered) {
                                returnTriggered = true
                                onScrollToTop()
                            }
                            overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                            returnTriggered = false
                        }
                    }
                }
            }
    ) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val leftSafeInset = maxWidth * (leftSafeInsetPercent.coerceIn(0, 50) / 100f)
        val horizontalPaddingPx = with(density) { 8.dp.toPx() }
        val leftPaddingPx = with(density) { (8.dp + leftSafeInset).toPx() }
        val itemGapPx = with(density) { 4.dp.toPx() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(effectiveColumns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp + leftSafeInset, top = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(
                items = apps,
                key = { _, app -> app.componentKey },
                contentType = { _, _ -> "material_dense_app" }
            ) { index, app ->
                val interactionSource = remember(app.componentKey) { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val displayIcon = remember(
                    app.componentKey,
                    app.cachedIcon,
                    app.cachedTwoToneIcon,
                    twoToneIconsEnabled
                ) {
                    app.iconForDisplay(
                        useTwoTone = twoToneIconsEnabled,
                        blurred = false
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.92f)
                        .graphicsLayer {
                            shadowElevation = iconShadowElevationPx
                            shape = launcherStyle.bubbleShape
                        }
                        .clip(launcherStyle.bubbleShape)
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                val col = index % effectiveColumns
                                val usableWidth = screenWidthPx - leftPaddingPx - horizontalPaddingPx - itemGapPx * (effectiveColumns - 1)
                                val cellWidth = usableWidth / effectiveColumns
                                val centerX = leftPaddingPx + col * (cellWidth + itemGapPx) + cellWidth / 2f
                                onAppClick(
                                    app,
                                    Offset(
                                        x = centerX / screenWidthPx,
                                        y = 0.5f
                                    )
                                )
                            },
                            onLongClick = {
                                onLongClick(app)
                                longPressedApp = app
                            }
                        )
                        .background(
                            color = if (isPressed) launcherStyle.pressedCardColor else appPalette.item,
                            shape = launcherStyle.bubbleShape
                        )
                        .padding(0.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = displayIcon,
                            contentDescription = app.label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                        )
                    }
                }
            }
        }

        longPressedApp?.let { app ->
            AppShortcutOverlay(
                app = app,
                blurEnabled = blurEnabled,
                onExcludeApp = { onExcludeApp(app) },
                onDismiss = { longPressedApp = null }
            )
        }
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
    val maxDistanceSq = maxDistance * maxDistance
    positions.forEachIndexed { index, position ->
        val dx = pointer.x - (screenCenterX + position.x)
        val dy = pointer.y - (screenCenterY + position.y)
        val distanceSq = dx * dx + dy * dy
        if (distanceSq < bestDistance && distanceSq <= maxDistanceSq) {
            bestDistance = distanceSq
            bestIndex = index
        }
    }
    return bestIndex
}

private fun clampHoneycombDisplayPointer(
    pointer: Offset,
    screenWidthPx: Float,
    screenHeightPx: Float,
    iconSizePx: Float
): Offset {
    val horizontalOverflow = iconSizePx * 0.3f
    val verticalOverflow = iconSizePx * 1.15f
    return Offset(
        x = pointer.x.coerceIn(-horizontalOverflow, screenWidthPx + horizontalOverflow),
        y = pointer.y.coerceIn(-verticalOverflow, screenHeightPx + verticalOverflow)
    )
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

@Composable
private fun animateOrSnapFloat(
    animate: Boolean,
    targetValue: Float,
    animationSpec: AnimationSpec<Float>,
    label: String
): Float {
    return if (animate) {
        animateFloatAsState(
            targetValue = targetValue,
            animationSpec = animationSpec,
            label = label
        ).value
    } else {
        targetValue
    }
}

private data class HoneycombRowInfo(
    val startIndex: Int,
    val endIndex: Int,
    val centerY: Float
)

private fun buildHoneycombRowInfo(positions: List<Offset>): List<HoneycombRowInfo> {
    if (positions.isEmpty()) return emptyList()
    val rows = mutableListOf<HoneycombRowInfo>()
    var rowStart = 0
    var currentY = positions.first().y
    for (index in 1 until positions.size) {
        val y = positions[index].y
        if (abs(y - currentY) > 0.001f) {
            rows += HoneycombRowInfo(
                startIndex = rowStart,
                endIndex = index - 1,
                centerY = currentY
            )
            rowStart = index
            currentY = y
        }
    }
    rows += HoneycombRowInfo(
        startIndex = rowStart,
        endIndex = positions.lastIndex,
        centerY = currentY
    )
    return rows
}

private fun computeHoneycombRenderIndexes(
    rows: List<HoneycombRowInfo>,
    currentScroll: Float,
    screenCenterY: Float,
    screenHeightPx: Float,
    iconSizePx: Float,
    bufferRows: Int,
    pinnedIndexes: List<Int>
): IntArray {
    if (rows.isEmpty()) {
        return pinnedIndexes
            .filter { it >= 0 }
            .distinct()
            .sorted()
            .toIntArray()
    }
    val visibleTop = -iconSizePx * 1.5f
    val visibleBottom = screenHeightPx + iconSizePx * 1.5f
    var firstVisibleRow = -1
    var lastVisibleRow = -1
    rows.forEachIndexed { rowIndex, row ->
        val rowScreenY = screenCenterY + row.centerY + currentScroll
        if (rowScreenY in visibleTop..visibleBottom) {
            if (firstVisibleRow < 0) firstVisibleRow = rowIndex
            lastVisibleRow = rowIndex
        }
    }
    val rendered = mutableListOf<Int>()
    if (firstVisibleRow >= 0 && lastVisibleRow >= 0) {
        val startRow = (firstVisibleRow - bufferRows).coerceAtLeast(0)
        val endRow = (lastVisibleRow + bufferRows).coerceAtMost(rows.lastIndex)
        for (rowIndex in startRow..endRow) {
            val row = rows[rowIndex]
            for (index in row.startIndex..row.endIndex) {
                rendered += index
            }
        }
    }
    rendered += pinnedIndexes.filter { it >= 0 }
    return rendered.distinct().sorted().toIntArray()
}

private fun resolveHoneycombScrollOffset(
    directScrollOffset: Float,
    animatedScrollOffset: Float
): Float = if (directScrollOffset.isNaN()) animatedScrollOffset else directScrollOffset

private fun reduceHoneycombBlurForFastScroll(
    blurDp: Float,
    reduced: Boolean
): Float {
    if (!reduced) return blurDp
    return 0f
}

private fun honeycombFlowOffset(
    enabled: Boolean,
    rowCenterY: Float,
    screenCenterY: Float,
    slowTopY: Float,
    slowBottomY: Float
): Float {
    if (!enabled) return 0f
    val transformedY = when {
        rowCenterY < slowTopY -> {
            screenCenterY + (slowTopY - screenCenterY) * FAST_FLOW_SLOW_SCALE +
                (rowCenterY - slowTopY) * FAST_FLOW_FAST_SCALE
        }
        rowCenterY > slowBottomY -> {
            screenCenterY + (slowBottomY - screenCenterY) * FAST_FLOW_SLOW_SCALE +
                (rowCenterY - slowBottomY) * FAST_FLOW_FAST_SCALE
        }
        else -> screenCenterY + (rowCenterY - screenCenterY) * FAST_FLOW_SLOW_SCALE
    }
    return (transformedY - rowCenterY).coerceIn(-FAST_FLOW_MAX_OFFSET_PX, FAST_FLOW_MAX_OFFSET_PX)
}

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
