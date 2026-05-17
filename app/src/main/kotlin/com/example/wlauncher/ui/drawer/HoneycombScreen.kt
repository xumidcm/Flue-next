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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.iconForDisplay
import com.flue.launcher.data.model.plainIconForDisplay
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val HONEYCOMB_MENU_TRIGGER_MS = 620L
private const val HONEYCOMB_MENU_DRAG_START_DP = 20f
private const val HONEYCOMB_FOLDER_HOVER_MS = 500L
private const val HONEYCOMB_FOLDER_HOVER_MAX_SPEED_DP_PER_MS = 0.72f
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
    materialTopFisheyeEnabled: Boolean = true,
    fisheyeRangeRows: Int = 4,
    fisheyeStrengthPercent: Int = 100,
    edgeSpacingCompressionEnabled: Boolean = true,
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
    rotaryHapticsEnabled: Boolean = true,
    active: Boolean = true,
    initialScrollResetKey: Int = 0,
    leftSafeInsetPercent: Int = 0,
    appListScalePercent: Int = 100,
    entryProgress: Float = 1f,
    folderOpen: Boolean = false,
    useWatchFaceColors: Boolean = false,
    allowFolderCreation: Boolean = true,
    onAppClick: (AppInfo, Offset) -> Unit,
    onReorder: (Int, Int) -> Unit = { _, _ -> },
    onCreateFolder: (Int, Int) -> Unit = { _, _ -> },
    onLongClick: (AppInfo) -> Unit = {},
    onExcludeApp: (AppInfo) -> Unit = {},
    onRemoveShortcut: (AppInfo) -> Unit = {},
    onRenameFolder: (AppInfo, String) -> Unit = { _, _ -> },
    onDissolveFolder: (AppInfo) -> Unit = {},
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (uiStyle == UiStyle.MATERIAL_3) {
        MaterialDenseGridScreen(
            apps = apps,
            blurEnabled = blurEnabled,
            edgeBlurEnabled = edgeBlurEnabled && !suppressHeavyEffects,
            twoToneIconsEnabled = twoToneIconsEnabled,
            iconShadowEnabled = iconShadowEnabled,
            themeColor = themeColor,
            darkMode = darkMode,
            columns = narrowCols.coerceIn(1, 5),
            topBlurRadiusDp = topBlurRadiusDp,
            bottomBlurRadiusDp = bottomBlurRadiusDp,
            topFadeRangeDp = topFadeRangeDp,
            bottomFadeRangeDp = bottomFadeRangeDp,
            active = active,
            initialScrollResetKey = initialScrollResetKey,
            leftSafeInsetPercent = leftSafeInsetPercent,
            appListScalePercent = appListScalePercent,
            entryProgress = entryProgress,
            folderOpen = folderOpen,
            allowFolderCreation = allowFolderCreation,
            fisheyeEnabled = fisheyeEnabled,
            topFisheyeEnabled = materialTopFisheyeEnabled,
            fisheyeRangeRows = fisheyeRangeRows,
            fisheyeStrengthPercent = fisheyeStrengthPercent,
            edgeSpacingCompressionEnabled = edgeSpacingCompressionEnabled,
            useWatchFaceColors = useWatchFaceColors,
            fastScrollOptimizationEnabled = fastScrollOptimizationEnabled,
            rotaryHapticsEnabled = rotaryHapticsEnabled,
            onAppClick = onAppClick,
            onReorder = onReorder,
            onCreateFolder = onCreateFolder,
            onLongClick = onLongClick,
            onExcludeApp = onExcludeApp,
            onRemoveShortcut = onRemoveShortcut,
            onRenameFolder = onRenameFolder,
            onDissolveFolder = onDissolveFolder,
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
    var folderHoverIndex by remember { mutableStateOf<Int?>(null) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var dragVisualPointer by remember { mutableStateOf<Offset?>(null) }
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
    var directLaunchTopLockObservedKey by remember { mutableStateOf(0) }
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
        val entryVisuals = appListEntryVisuals(entryProgress)
        val edgeFadesVisible = active || entryProgress > 0.001f
        val containerMaxWidth = maxWidth
        val containerMaxHeight = maxHeight
        val fisheyeRangeMultiplier = fisheyeRangeRows.coerceIn(1, 8) * 0.42f
        val fisheyeMaxDistance = screenRadius * fisheyeRangeMultiplier.coerceAtLeast(0.42f)
        val fisheyeMinScale = fisheyeMinScale(fisheyeStrengthPercent)

        val positions = remember(apps.size, narrowCols, cellSize) {
            generateHoneycombRows(apps.size, narrowCols, cellSize)
        }
        val rowInfo = remember(positions) { buildHoneycombRowInfo(positions) }
        val appIndexByKey = remember(apps) {
            apps.mapIndexed { index, app -> app.componentKey to index }.toMap()
        }
        val appLaunchCenters = remember { mutableMapOf<String, Offset>() }

        val minGridY = positions.minOfOrNull { it.y } ?: 0f
        val maxGridY = positions.maxOfOrNull { it.y } ?: 0f
        val maxScroll = -minGridY
        val minScroll = -maxGridY

        val scrollOffset = remember { Animatable(maxScroll) }
        val scope = rememberCoroutineScope()
        val overlayBlurActive = (longPressedApp != null || folderOpen) && blurEnabled && !suppressHeavyEffects
        val honeycombAutoScrollEdgePx = with(density) { HONEYCOMB_AUTO_SCROLL_EDGE_DP.dp.toPx() }
        val fastDragThresholdPx = with(density) { 10.dp.toPx() }
        val dragOverscrollLimitPx = with(density) { 72.dp.toPx() }
        LaunchedEffect(apps) {
            val validKeys = apps.mapTo(mutableSetOf()) { it.componentKey }
            appLaunchCenters.keys
                .filterNot { it in validKeys }
                .forEach { appLaunchCenters.remove(it) }
        }
        fun currentScrollOffsetValue(): Float {
            if (!initialScrollPositionResolved && apps.isNotEmpty() && directScrollOffset.isNaN()) {
                return maxScroll
            }
            return resolveHoneycombScrollOffset(
                directScrollOffset = directScrollOffset,
                animatedScrollOffset = scrollOffset.value
            )
        }
        fun reducedMotionPhase(): Boolean =
            fastScrollOptimizationEnabled && fastScrollActive
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
        LaunchedEffect(initialScrollResetKey, active, apps.size, minScroll, maxScroll) {
            if (
                initialScrollResetKey > 0 &&
                initialScrollResetKey != directLaunchTopLockObservedKey &&
                active &&
                apps.isNotEmpty() &&
                dragFromIndex == null &&
                longPressedApp == null
            ) {
                directLaunchTopLockObservedKey = initialScrollResetKey
                wheelMomentumJob?.cancel()
                directScrollOffset = Float.NaN
                scrollOffset.stop()
                scrollOffset.snapTo(maxScroll)
                initialScrollPositionResolved = true
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
                folderHoverIndex = null
                dragPointer = null
                dragVisualPointer = null
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
                        val visualPointer = dragVisualPointer ?: pointer
                        val displayPointer = clampHoneycombDisplayPointer(
                            pointer = visualPointer,
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
                        if (!allowFolderCreation && folderHoverIndex == null) {
                            dragCurrentIndex = findNearestHoneycombIndex(
                                pointer = displayPointer,
                                positions = positions,
                                screenCenterX = screenCenterX,
                                screenCenterY = screenCenterY + currentScrollOffsetValue(),
                                maxDistance = cellSize * 0.95f
                            ) ?: dragCurrentIndex
                        }
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
                .flueDrawerRotaryScrollable(
                    focusRequester,
                    DrawerInputMode.Honeycomb,
                    onRotaryScroll = { if (rotaryHapticsEnabled) vibrateHaptic(context) }
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
                    val folderHoverMaxSpeedPxPerMs = with(density) {
                        HONEYCOMB_FOLDER_HOVER_MAX_SPEED_DP_PER_MS.dp.toPx()
                    }
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
                                timeoutMillis = HONEYCOMB_MENU_TRIGGER_MS,
                                moveTolerancePx = menuDragStartPx
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
                            val dragStartSlot = positions.getOrNull(startIndex)
                            val dragStartCenter = if (dragStartSlot != null) {
                                Offset(
                                    x = screenCenterX + dragStartSlot.x,
                                    y = screenCenterY + dragStartSlot.y + currentScrollOffsetValue()
                                )
                            } else {
                                dragOrigin
                            }
                            var dragVisualOffset = Offset.Zero
                            var previousVisualPointer = dragStartCenter
                            var previousMoveUptime = longPress.uptimeMillis
                            var dragActive = false
                            var hasDragged = false
                            var folderHoverStartedAt = 0L
                            var folderDropIndex: Int? = null

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
                                    dragVisualOffset = dragStartCenter - pointer
                                    dragPointer = pointer
                                    dragVisualPointer = pointer + dragVisualOffset
                                    dragApp = app
                                    glidePressedKey = null
                                    vibrateHaptic(context)
                                }

                                val fromIndex = dragFromIndex
                                if (dragActive && fromIndex != null) {
                                    dragPointer = pointer
                                    dragVisualPointer = pointer + dragVisualOffset
                                    if ((pointer - dragOrigin).getDistance() > menuDragStartPx * 0.35f) {
                                        hasDragged = true
                                    }
                                    val visualPointer = pointer + dragVisualOffset
                                    val elapsedMs = (change.uptimeMillis - previousMoveUptime).coerceAtLeast(1L)
                                    val visualSpeed = (visualPointer - previousVisualPointer).getDistance() / elapsedMs.toFloat()
                                    val displayPointer = clampHoneycombDisplayPointer(
                                        pointer = visualPointer,
                                        screenWidthPx = screenWidthPx,
                                        screenHeightPx = screenHeightPx,
                                        iconSizePx = iconSizePx
                                    )
                                    val reorderTarget = if (!allowFolderCreation) {
                                        findNearestHoneycombIndex(
                                            pointer = displayPointer,
                                            positions = positions,
                                            screenCenterX = screenCenterX,
                                            screenCenterY = screenCenterY + currentScrollOffsetValue(),
                                            maxDistance = cellSize * 0.95f
                                        )
                                    } else {
                                        null
                                    }
                                    val folderPointer = clampHoneycombDisplayPointer(
                                        pointer = visualPointer,
                                        screenWidthPx = screenWidthPx,
                                        screenHeightPx = screenHeightPx,
                                        iconSizePx = iconSizePx
                                    )
                                    val folderCandidate = if (allowFolderCreation) {
                                        findNearestHoneycombIndex(
                                            pointer = folderPointer,
                                            positions = positions,
                                            screenCenterX = screenCenterX,
                                            screenCenterY = screenCenterY + currentScrollOffsetValue(),
                                            maxDistance = iconSizePx * 0.56f
                                        )?.takeIf { it != fromIndex && visualSpeed <= folderHoverMaxSpeedPxPerMs }
                                    } else {
                                        null
                                    }
                                    if (folderCandidate != null) {
                                        dragCurrentIndex = fromIndex
                                        if (folderHoverIndex != folderCandidate) {
                                            folderHoverIndex = folderCandidate
                                            folderHoverStartedAt = change.uptimeMillis
                                            folderDropIndex = null
                                        } else if (change.uptimeMillis - folderHoverStartedAt >= HONEYCOMB_FOLDER_HOVER_MS) {
                                            folderDropIndex = folderCandidate
                                        }
                                    } else {
                                        folderHoverIndex = null
                                        folderHoverStartedAt = 0L
                                        folderDropIndex = null
                                        if (!allowFolderCreation && reorderTarget != null) {
                                            dragCurrentIndex = reorderTarget
                                        } else {
                                            dragCurrentIndex = fromIndex
                                        }
                                    }
                                    previousVisualPointer = visualPointer
                                    previousMoveUptime = change.uptimeMillis
                                    change.consume()
                                }
                            }

                            val from = dragFromIndex
                            val to = dragCurrentIndex
                            val folderTarget = folderDropIndex
                            val releasePointer = dragPointer
                            val releaseVisualPointer = dragVisualPointer ?: releasePointer
                            val releaseScroll = currentScrollOffsetValue()
                            if (dragActive && from != null && folderTarget != null && from != folderTarget && hasDragged) {
                                dragFromIndex = null
                                dragCurrentIndex = null
                                folderHoverIndex = null
                                dragPointer = null
                                dragVisualPointer = null
                                dragApp = null
                                glidePressedKey = null
                                onCreateFolder(from, folderTarget)
                            } else if (!allowFolderCreation && dragActive && from != null && to != null && from != to && hasDragged) {
                                val droppedApp = apps.getOrNull(from)
                                val targetSlot = positions.getOrNull(to)
                                dragFromIndex = null
                                dragCurrentIndex = null
                                folderHoverIndex = null
                                dragPointer = null
                                dragVisualPointer = null
                                dragApp = null
                                glidePressedKey = null
                                if (droppedApp != null && releaseVisualPointer != null && targetSlot != null) {
                                    settlingApp = droppedApp
                                    settlingKey = droppedApp.componentKey
                                    scope.launch {
                                        settlingX.snapTo(releaseVisualPointer.x.coerceIn(iconSizePx * 0.5f, screenWidthPx - iconSizePx * 0.5f))
                                        settlingY.snapTo(releaseVisualPointer.y.coerceIn(iconSizePx * 0.5f, screenHeightPx - iconSizePx * 0.5f))
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
                                folderHoverIndex = null
                                dragPointer = null
                                dragVisualPointer = null
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
                        dragVisualPointer = null
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
                                directScrollOffset = nextScroll.coerceIn(
                                    minScroll - dragOverscrollLimitPx,
                                    maxScroll + dragOverscrollLimitPx
                                )
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
                                if (current >= maxScroll + iconSizePx * 0.28f) {
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
            AppListEntryBackground(
                maxWidth = containerMaxWidth,
                maxHeight = containerMaxHeight,
                visuals = entryVisuals,
                color = appPalette.background
            )

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
                dragCurrentIndex,
                folderHoverIndex
            ) {
                computeHoneycombRenderIndexes(
                    rows = rowInfo,
                    currentScroll = currentScroll,
                    screenCenterY = screenCenterY,
                    screenHeightPx = screenHeightPx,
                    iconSizePx = iconSizePx,
                    bufferRows = 3,
                    pinnedIndexes = listOfNotNull(menuPressedIndex, dragFromIndex, dragCurrentIndex, folderHoverIndex)
                )
            }
            val dragOverlayPointer = (dragVisualPointer ?: dragPointer)?.let {
                clampHoneycombDisplayPointer(
                    pointer = it,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    iconSizePx = iconSizePx
                )
            }
            val dragScalePointer = dragVisualPointer ?: dragPointer

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
                        pointer = dragVisualPointer ?: dragPointer ?: Offset(
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
                val useFisheyeEffects = fisheyeEnabled
                val useFastFlowEffects = fastFlowAnimationEnabled && !reduceVisualLoad
                val itemBlur = if (effectiveEdgeBlur && !reduceVisualLoad) {
                    computeHoneycombEdgeBlur(
                        centerY = visibilityY,
                        screenHeight = screenHeightPx,
                        topBlurZonePx = topFadePx,
                        bottomBlurZonePx = bottomFadePx,
                        topBlurDp = topBlurRadiusDp,
                        bottomBlurDp = bottomBlurRadiusDp
                    )
                } else {
                    0f
                }

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
                    val edgeSpacingOffsetX = edgeSpacingCompressionHorizontalOffset(
                        centerX = screenCenterX + slotX,
                        rowCenterY = screenCenterY + slotY + currentScroll,
                        screenCenterX = screenCenterX,
                        screenCenterY = screenCenterY,
                        screenHeight = screenHeightPx,
                        itemSize = iconSizePx,
                        strengthPercent = fisheyeStrengthPercent,
                        enabled = useFisheyeEffects && edgeSpacingCompressionEnabled
                    )
                    val effectiveItemBlur = reduceHoneycombBlurForFastScroll(itemBlur, reduceVisualLoad)
                    val clickCenterX = screenCenterX + slotX
                    val clickCenterY = screenCenterY + slotY + currentScroll
                    val baseFlowOffset = honeycombFlowOffset(
                        enabled = useFastFlowEffects,
                        rowCenterY = clickCenterY,
                        screenCenterY = screenCenterY,
                        slowTopY = fastFlowSlowTopY,
                        slowBottomY = fastFlowSlowBottomY
                    )
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
                                val sx = clickCenterX + edgeSpacingOffsetX
                                val syPos = clickCenterY + baseFlowOffset
                                onAppClick(
                                    app,
                                    Offset(
                                        (sx / screenWidthPx).coerceIn(0f, 1f),
                                        (syPos / screenHeightPx).coerceIn(0f, 1f)
                                    )
                                )
                            }
                        },
                        onLongClick = null,
                        forcePressed = isGlidePressed || menuPressedKey == appKey || folderHoverIndex == index,
                        pressScaleTarget = 0.9f,
                        pressAnimationDelayMillis = 0,
                        pressAnimationDurationMillis = HONEYCOMB_PRESS_DURATION_MS,
                        onPressedChange = {},
                        modifier = Modifier
                            .zIndex(0f)
                            .graphicsLayer {
                                val baseX = slotX
                                val baseY = slotY
                                val posX = screenCenterX + baseX + edgeSpacingOffsetX
                                val pY = screenCenterY + baseY + currentScroll + baseFlowOffset
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
                                val scale = if (useFisheyeEffects) {
                                    fisheyeScale(dist, fisheyeMaxDistance, minScale = fisheyeMinScale)
                                } else {
                                    1f
                                }
                                scaleX = scale * neighborScale
                                scaleY = scale * neighborScale
                                val itemAlpha = when {
                                    isDragged -> 0f
                                    settlingKey == app.componentKey -> 0f
                                    else -> if (useFisheyeEffects) scale.coerceIn(0.24f, 1f) else 1f
                                }
                                alpha = itemAlpha
                                shadowElevation = if (itemAlpha > 0.001f) {
                                    if (reduceVisualLoad) iconShadowElevationPx * 0.62f else iconShadowElevationPx
                                } else {
                                    0f
                                }
                                shape = CircleShape
                            }
                            .onGloballyPositioned { coords ->
                                appLaunchCenters[appKey] = coords.boundsInRoot().center
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
                    fisheyeScale(dragDist, fisheyeMaxDistance, minScale = fisheyeMinScale)
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
                    forcePressed = true,
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
                            fisheyeScale(dist, fisheyeMaxDistance, minScale = fisheyeMinScale)
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

        if (edgeFadesVisible && topFadeRangeDp > 0) {
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
                    .platformBlur(topBlurRadiusDp, blurEnabled && effectiveEdgeBlur && !reducedMotionPhase())
            )
        }
        if (edgeFadesVisible && bottomFadeRangeDp > 0) {
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
                    .platformBlur(bottomBlurRadiusDp, blurEnabled && effectiveEdgeBlur && !reducedMotionPhase())
            )
        }
    }

    longPressedApp?.let { app ->
        AppShortcutOverlay(
            app = app,
            blurEnabled = blurEnabled,
            onExcludeApp = { onExcludeApp(app) },
            onRemoveShortcut = if (app.isAppListShortcut) { { onRemoveShortcut(app) } } else null,
            onRenameFolder = if (app.isFolder) { name -> onRenameFolder(app, name) } else null,
            onDissolveFolder = if (app.isFolder) { { onDissolveFolder(app) } } else null,
            onDismiss = { longPressedApp = null }
        )
    }
}
@Composable
private fun MaterialDenseGridScreen(
    apps: List<AppInfo>,
    blurEnabled: Boolean,
    edgeBlurEnabled: Boolean,
    twoToneIconsEnabled: Boolean,
    iconShadowEnabled: Boolean,
    themeColor: Color,
    darkMode: Boolean,
    columns: Int,
    topBlurRadiusDp: Float,
    bottomBlurRadiusDp: Float,
    topFadeRangeDp: Int,
    bottomFadeRangeDp: Int,
    active: Boolean,
    initialScrollResetKey: Int,
    leftSafeInsetPercent: Int,
    appListScalePercent: Int,
    entryProgress: Float,
    folderOpen: Boolean,
    allowFolderCreation: Boolean,
    fisheyeEnabled: Boolean,
    topFisheyeEnabled: Boolean,
    fisheyeRangeRows: Int,
    fisheyeStrengthPercent: Int,
    edgeSpacingCompressionEnabled: Boolean,
    useWatchFaceColors: Boolean,
    fastScrollOptimizationEnabled: Boolean,
    rotaryHapticsEnabled: Boolean,
    onAppClick: (AppInfo, Offset) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onCreateFolder: (Int, Int) -> Unit,
    onLongClick: (AppInfo) -> Unit,
    onExcludeApp: (AppInfo) -> Unit,
    onRemoveShortcut: (AppInfo) -> Unit,
    onRenameFolder: (AppInfo, String) -> Unit,
    onDissolveFolder: (AppInfo) -> Unit,
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val gridState = remember { LazyGridState() }
    val scope = rememberCoroutineScope()
    val appPalette = appListPalette(themeColor, darkMode, UiStyle.MATERIAL_3, useWatchFaceColors)
    val entryVisuals = appListEntryVisuals(entryProgress)
    val edgeFadesVisible = active || entryProgress > 0.001f
    val iconShadowElevationPx by animateFloatAsState(
        targetValue = if (iconShadowEnabled && active && entryProgress >= 0.985f) {
            with(density) { 8.dp.toPx() }
        } else {
            0f
        },
        animationSpec = tween(durationMillis = if (iconShadowEnabled) 120 else 0),
        label = "material_grid_icon_shadow"
    )
    val appListScale = appListScalePercent.coerceIn(50, 200) / 100f
    val effectiveColumns = columns.coerceIn(1, 5)
    var focusReady by remember { mutableStateOf(false) }
    var longPressedApp by remember { mutableStateOf<AppInfo?>(null) }
    var dragFromIndex by remember { mutableStateOf<Int?>(null) }
    var dragCurrentIndex by remember { mutableStateOf<Int?>(null) }
    var folderHoverIndex by remember { mutableStateOf<Int?>(null) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var dragVisualPointer by remember { mutableStateOf<Offset?>(null) }
    var dragStartBounds by remember { mutableStateOf<Rect?>(null) }
    var returnTriggered by remember { mutableStateOf(false) }
    var directLaunchTopLockObservedKey by remember { mutableStateOf(0) }
    var materialContainerRoot by remember { mutableStateOf(Offset.Zero) }
    val materialItemBounds = remember { mutableMapOf<Int, Rect>() }
    val overscroll = remember { Animatable(0f) }
    val topReturnThresholdPx = with(density) { 72.dp.toPx() }
    val overscrollLimitPx = with(density) { 140.dp.toPx() }
    var materialFastScrollActive by remember { mutableStateOf(false) }
    var materialFastScrollResetJob by remember { mutableStateOf<Job?>(null) }
    val materialScrollOptimizationActive =
        fastScrollOptimizationEnabled &&
            materialFastScrollActive &&
            dragFromIndex == null &&
            longPressedApp == null

    LaunchedEffect(gridState, fastScrollOptimizationEnabled) {
        if (!fastScrollOptimizationEnabled) {
            materialFastScrollResetJob?.cancel()
            materialFastScrollActive = false
            return@LaunchedEffect
        }
        var lastIndex = gridState.firstVisibleItemIndex
        var lastOffset = gridState.firstVisibleItemScrollOffset
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val jumpedRows = abs(index - lastIndex)
                val jumpedPixels = abs(offset - lastOffset)
                lastIndex = index
                lastOffset = offset
                if (jumpedRows >= 2 || jumpedPixels >= 220) {
                    materialFastScrollActive = true
                    materialFastScrollResetJob?.cancel()
                    materialFastScrollResetJob = scope.launch {
                        delay(180L)
                        materialFastScrollActive = false
                    }
                }
            }
    }

    LaunchedEffect(active) {
        if (!active) {
            longPressedApp = null
            dragFromIndex = null
            dragCurrentIndex = null
            folderHoverIndex = null
            dragPointer = null
            dragVisualPointer = null
            dragStartBounds = null
            if (!returnTriggered) {
                overscroll.snapTo(0f)
            }
        } else {
            returnTriggered = false
            focusRequester.requestFocusAfterFirstFrame()
        }
    }
    LaunchedEffect(initialScrollResetKey, active, apps.size) {
        if (
            initialScrollResetKey > 0 &&
            initialScrollResetKey != directLaunchTopLockObservedKey &&
            active &&
            apps.isNotEmpty() &&
            dragFromIndex == null &&
            longPressedApp == null
        ) {
            directLaunchTopLockObservedKey = initialScrollResetKey
            overscroll.stop()
            overscroll.snapTo(0f)
            gridState.scrollToItem(0, 0)
        }
    }
    LaunchedEffect(apps.size) {
        val validIndexes = apps.indices.toSet()
        materialItemBounds.keys
            .filterNot { it in validIndexes }
            .forEach { materialItemBounds.remove(it) }
    }
    LaunchedEffect(gridState, apps.size, dragFromIndex, dragCurrentIndex, folderHoverIndex) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }
            .collect { visibleIndexes ->
                val pinned = setOfNotNull(dragFromIndex, dragCurrentIndex, folderHoverIndex)
                materialItemBounds.keys
                    .filterNot { it in visibleIndexes || it in pinned }
                    .forEach { materialItemBounds.remove(it) }
            }
    }

    val nestedScrollConnection = remember(gridState, dragFromIndex, longPressedApp, onScrollToTop) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || dragFromIndex != null || longPressedApp != null) return Offset.Zero
                val atTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop) {
                    val next = (overscroll.value + available.y).coerceAtMost(overscrollLimitPx)
                    scope.launch {
                        overscroll.stop()
                        overscroll.snapTo(next)
                    }
                    return Offset(0f, available.y)
                }
                val atBottom = !gridState.canScrollForward
                if (available.y < 0f && atBottom) {
                    val next = (overscroll.value + available.y).coerceAtLeast(-overscrollLimitPx)
                    scope.launch {
                        overscroll.stop()
                        overscroll.snapTo(next)
                    }
                    return Offset(0f, available.y)
                }
                if (overscroll.value > 0f && available.y < 0f) {
                    scope.launch {
                        overscroll.stop()
                        overscroll.snapTo((overscroll.value + available.y).coerceAtLeast(0f))
                    }
                    return Offset(0f, available.y)
                }
                if (overscroll.value < 0f && available.y > 0f) {
                    scope.launch {
                        overscroll.stop()
                        overscroll.snapTo((overscroll.value + available.y).coerceAtMost(0f))
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput || dragFromIndex != null || longPressedApp != null) return Offset.Zero
                val atTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop) {
                    val next = (overscroll.value + available.y).coerceAtMost(overscrollLimitPx)
                    scope.launch {
                        overscroll.stop()
                        overscroll.snapTo(next)
                    }
                    return Offset(0f, available.y)
                }
                val atBottom = !gridState.canScrollForward
                if (available.y < 0f && atBottom) {
                    val next = (overscroll.value + available.y).coerceAtLeast(-overscrollLimitPx)
                    scope.launch {
                        overscroll.stop()
                        overscroll.snapTo(next)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragFromIndex != null || longPressedApp != null) return Velocity.Zero
                if (overscroll.value != 0f) {
                    val shouldReturnToFace = overscroll.value >= topReturnThresholdPx
                    if (shouldReturnToFace && !returnTriggered) {
                        returnTriggered = true
                        onScrollToTop()
                    }
                    overscroll.stop()
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
            .flueDrawerRotaryScrollable(
                focusRequester,
                DrawerInputMode.Honeycomb,
                onRotaryScroll = { if (rotaryHapticsEnabled) vibrateHaptic(context) }
            ) { rotaryDelta ->
                scope.launch { gridState.scrollBy(-rotaryDelta) }
            }
            .nestedScroll(nestedScrollConnection)
            .onGloballyPositioned {
                materialContainerRoot = it.positionInRoot()
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
                    awaitPrimaryDown()
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
                            overscroll.stop()
                            overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                            returnTriggered = false
                        }
                    }
                }
            }
            .pointerInput(apps) {
                val menuDragStartPx = with(density) { HONEYCOMB_MENU_DRAG_START_DP.dp.toPx() }
                val folderHoverMaxSpeedPxPerMs = with(density) {
                    HONEYCOMB_FOLDER_HOVER_MAX_SPEED_DP_PER_MS.dp.toPx()
                }
                awaitEachGesture {
                    val down = awaitPrimaryDown()
                    val startIndex = findMaterialGridIndexAt(
                        pointer = down.position,
                        itemBounds = materialItemBounds
                    ) ?: return@awaitEachGesture
                    val app = apps.getOrNull(startIndex) ?: return@awaitEachGesture
                    val longPress = awaitLongPressByTimeoutOrCancel(
                        pointerId = down.id,
                        downPosition = down.position,
                        timeoutMillis = HONEYCOMB_MENU_TRIGGER_MS,
                        moveTolerancePx = menuDragStartPx
                    ) ?: return@awaitEachGesture

                    onLongClick(app)
                    longPressedApp = app
                    vibrateHaptic(context)

                    val dragOrigin = longPress.position
                    val dragStartCenter = materialItemBounds[startIndex]?.center ?: dragOrigin
                    var dragVisualOffset = Offset.Zero
                    var previousVisualPointer = dragStartCenter
                    var previousMoveUptime = longPress.uptimeMillis
                    var dragActive = false
                    var hasDragged = false
                    var folderHoverStartedAt = 0L
                    var folderDropIndex: Int? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!dragActive) change.consume()
                            break
                        }

                        val pointer = change.position
                        val movedDistance = (pointer - dragOrigin).getDistance()
                        if (!dragActive && movedDistance > menuDragStartPx) {
                            longPressedApp = null
                            dragActive = true
                            dragFromIndex = startIndex
                            dragCurrentIndex = startIndex
                            dragVisualOffset = dragStartCenter - pointer
                            dragPointer = pointer
                            dragVisualPointer = pointer + dragVisualOffset
                            dragStartBounds = materialItemBounds[startIndex]
                            vibrateHaptic(context)
                        }

                        if (dragActive) {
                            dragPointer = pointer
                            dragVisualPointer = pointer + dragVisualOffset
                            if (movedDistance > menuDragStartPx * 0.35f) {
                                hasDragged = true
                            }
                            val visualPointer = pointer + dragVisualOffset
                            val elapsedMs = (change.uptimeMillis - previousMoveUptime).coerceAtLeast(1L)
                            val visualSpeed = (visualPointer - previousVisualPointer).getDistance() / elapsedMs.toFloat()
                            val reorderTarget = if (!allowFolderCreation) {
                                findNearestMaterialGridIndex(
                                    pointer = visualPointer,
                                    itemBounds = materialItemBounds
                                )
                            } else {
                                null
                            }
                            val folderPointer = visualPointer
                            val folderCandidate = if (allowFolderCreation) {
                                findMaterialGridCenterIndex(
                                    pointer = folderPointer,
                                    itemBounds = materialItemBounds,
                                    excludedIndex = startIndex,
                                    centerFraction = 0.64f
                                )?.takeIf { visualSpeed <= folderHoverMaxSpeedPxPerMs }
                            } else {
                                null
                            }
                            if (folderCandidate != null) {
                                dragCurrentIndex = startIndex
                                if (folderHoverIndex != folderCandidate) {
                                    folderHoverIndex = folderCandidate
                                    folderHoverStartedAt = change.uptimeMillis
                                    folderDropIndex = null
                                } else if (change.uptimeMillis - folderHoverStartedAt >= HONEYCOMB_FOLDER_HOVER_MS) {
                                    folderDropIndex = folderCandidate
                                }
                            } else {
                                folderHoverIndex = null
                                folderHoverStartedAt = 0L
                                folderDropIndex = null
                                if (!allowFolderCreation && reorderTarget != null && reorderTarget in apps.indices) {
                                    dragCurrentIndex = reorderTarget
                                }
                            }
                            previousVisualPointer = visualPointer
                            previousMoveUptime = change.uptimeMillis
                            change.consume()
                        }
                    }

                    val from = dragFromIndex
                    val to = dragCurrentIndex
                    val folderTarget = folderDropIndex
                    if (allowFolderCreation && dragActive && from != null && folderTarget != null && from != folderTarget && hasDragged) {
                        dragFromIndex = null
                        dragCurrentIndex = null
                        folderHoverIndex = null
                        dragPointer = null
                        dragVisualPointer = null
                        dragStartBounds = null
                        longPressedApp = null
                        onCreateFolder(from, folderTarget)
                    } else if (!allowFolderCreation && dragActive && from != null && to != null && from != to && hasDragged) {
                        materialItemBounds[to]?.center?.let { targetCenter ->
                            dragPointer = targetCenter
                            dragVisualPointer = targetCenter
                        }
                        scope.launch {
                            delay(150)
                            onReorder(from, to)
                            dragFromIndex = null
                            dragCurrentIndex = null
                            dragPointer = null
                            dragVisualPointer = null
                            dragStartBounds = null
                        }
                        longPressedApp = null
                    } else if (dragActive) {
                        dragFromIndex = null
                        dragCurrentIndex = null
                        dragPointer = null
                        dragVisualPointer = null
                        dragStartBounds = null
                        longPressedApp = null
                    }
                }
            }
    ) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val containerMaxWidth = maxWidth
        val containerMaxHeight = maxHeight
        val entryOffsetY = (1f - entryProgress.coerceIn(0f, 1f)) * screenHeightPx * 0.08f
        val topFadePx = with(density) { topFadeRangeDp.dp.toPx() }
        val bottomFadePx = with(density) { bottomFadeRangeDp.dp.toPx() }
        val leftSafeInset = maxWidth * (leftSafeInsetPercent.coerceIn(0, 50) / 100f)
        val leftSafeInsetPx = with(density) { leftSafeInset.toPx() }
        val materialContentCenterX = leftSafeInsetPx + (screenWidthPx - leftSafeInsetPx) / 2f
        val gridHorizontalPadding = 8.dp
        val gridItemSpacing = 4.dp
        val gridAvailableWidth = (
            maxWidth -
                leftSafeInset -
                gridHorizontalPadding * 2f -
                gridItemSpacing * (effectiveColumns - 1).coerceAtLeast(0).toFloat()
            ).coerceAtLeast(48.dp)
        val estimatedGridItemHeight = (gridAvailableWidth / effectiveColumns.coerceAtLeast(1).toFloat() / 0.92f)
            .coerceAtLeast(48.dp)
        val materialTopPadding = maxOf(16.dp, topFadeRangeDp.dp * 0.42f)
        val materialBottomPadding = maxOf(
            ((maxHeight - estimatedGridItemHeight) / 2f).coerceAtLeast(16.dp),
            bottomFadeRangeDp.dp * 0.95f
        )
        val gridHorizontalPaddingPx = with(density) { gridHorizontalPadding.toPx() }
        val gridItemSpacingPx = with(density) { gridItemSpacing.toPx() }
        val gridAvailableWidthPx = with(density) { gridAvailableWidth.toPx() }
        val estimatedGridItemWidthPx = gridAvailableWidthPx / effectiveColumns.coerceAtLeast(1).toFloat()
        val estimatedGridItemHeightPx = with(density) { estimatedGridItemHeight.toPx() }
        val materialTopPaddingPx = with(density) { materialTopPadding.toPx() }
        val materialFisheyeCenterY = screenHeightPx / 2f
        val fisheyeDeadZonePx = estimatedGridItemHeightPx * 0.14f
        val fisheyeRangePx = (estimatedGridItemHeightPx * fisheyeRangeRows.coerceIn(1, 8))
            .coerceIn(estimatedGridItemHeightPx * 1.2f, screenHeightPx)
        val fisheyeMinScale = fisheyeMinScale(fisheyeStrengthPercent)
        val autoScrollEdgePx = with(density) { HONEYCOMB_AUTO_SCROLL_EDGE_DP.dp.toPx() }
        val materialIconShape = RoundedCornerShape((18.dp * appListScale).coerceIn(12.dp, 24.dp))
        val iconInnerPadding = (3.dp * appListScale).coerceIn(2.dp, 6.dp)
        val estimatedGridRowStridePx = estimatedGridItemHeightPx + gridItemSpacingPx
        val firstVisibleEstimatedRow = gridState.firstVisibleItemIndex / effectiveColumns.coerceAtLeast(1)
        val estimatedGridScrollY =
            firstVisibleEstimatedRow * estimatedGridRowStridePx + gridState.firstVisibleItemScrollOffset
        val materialEdgeOverlayAlpha = entryProgress.coerceIn(0f, 1f)
        val materialVisibleItemsByIndex by remember(gridState) {
            derivedStateOf {
                gridState.layoutInfo.visibleItemsInfo.associateBy { it.index }
            }
        }
        LaunchedEffect(dragFromIndex, screenHeightPx) {
            var previousFrameNanos = 0L
            while (dragFromIndex != null) {
                var pendingScrollDelta = 0f
                withFrameNanos { frameTimeNanos ->
                    val frameDeltaSeconds = if (previousFrameNanos == 0L) {
                        1f / 60f
                    } else {
                        ((frameTimeNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(1f / 144f, 0.05f)
                    }
                    previousFrameNanos = frameTimeNanos
                    val pointer = dragPointer
                    if (pointer != null) {
                        pendingScrollDelta = when {
                            pointer.y < autoScrollEdgePx ->
                                -HONEYCOMB_AUTO_SCROLL_MAX_PX * 60f * ((autoScrollEdgePx - pointer.y) / autoScrollEdgePx).coerceIn(0f, 1.2f)
                            pointer.y > screenHeightPx - autoScrollEdgePx ->
                                HONEYCOMB_AUTO_SCROLL_MAX_PX * 60f * ((pointer.y - (screenHeightPx - autoScrollEdgePx)) / autoScrollEdgePx).coerceIn(0f, 1.2f)
                            else -> 0f
                        } * frameDeltaSeconds
                    }
                }
                if (pendingScrollDelta != 0f) {
                    gridState.scrollBy(pendingScrollDelta)
                    dragPointer?.let { pointer ->
                        if (!allowFolderCreation && folderHoverIndex == null) {
                            val visualPointer = dragVisualPointer ?: pointer
                            findNearestMaterialGridIndex(
                                pointer = visualPointer,
                                itemBounds = materialItemBounds
                            )?.let { target ->
                                if (target in apps.indices) dragCurrentIndex = target
                            }
                        }
                    }
                }
            }
        }
        AppListEntryBackground(
            maxWidth = containerMaxWidth,
            maxHeight = containerMaxHeight,
            visuals = entryVisuals,
            color = appPalette.background
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(effectiveColumns),
            state = gridState,
            userScrollEnabled = dragFromIndex == null && longPressedApp == null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = overscroll.value + entryOffsetY }
                .platformBlur(16f, (longPressedApp != null || folderOpen) && blurEnabled),
            contentPadding = PaddingValues(
                start = gridHorizontalPadding + leftSafeInset,
                top = materialTopPadding,
                end = gridHorizontalPadding,
                bottom = materialBottomPadding
            ),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gridItemSpacing),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gridItemSpacing)
        ) {
            itemsIndexed(
                items = apps,
                key = { _, app -> app.componentKey },
            contentType = { _, _ -> "material_dense_app" }
            ) { index, app ->
                val interactionSource = remember(app.componentKey) { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val itemInfo = materialVisibleItemsByIndex[index]
                val itemBounds = materialItemBounds[index]
                val estimatedRow = index / effectiveColumns.coerceAtLeast(1)
                val estimatedColumn = index % effectiveColumns.coerceAtLeast(1)
                val estimatedCenterX =
                    leftSafeInsetPx +
                        gridHorizontalPaddingPx +
                        estimatedColumn * (estimatedGridItemWidthPx + gridItemSpacingPx) +
                        estimatedGridItemWidthPx / 2f
                val estimatedCenterY =
                    materialTopPaddingPx +
                        estimatedRow * estimatedGridRowStridePx +
                        estimatedGridItemHeightPx / 2f -
                        estimatedGridScrollY
                val itemCenterY = materialGridItemCenterY(
                    itemInfo = itemInfo,
                    fallbackBounds = itemBounds,
                    fallback = estimatedCenterY
                )
                val visualCenterY = itemCenterY + overscroll.value + entryOffsetY
                val materialFisheyeActive =
                    fisheyeEnabled &&
                        (active || entryProgress > 0.001f)
                val itemBlur = if (edgeBlurEnabled) {
                    val rawBlur = computeHoneycombEdgeBlur(
                        centerY = visualCenterY,
                        screenHeight = screenHeightPx,
                        topBlurZonePx = topFadePx,
                        bottomBlurZonePx = bottomFadePx,
                        topBlurDp = topBlurRadiusDp,
                        bottomBlurDp = bottomBlurRadiusDp
                    )
                    reduceHoneycombBlurForFastScroll(rawBlur, materialScrollOptimizationActive)
                } else {
                    0f
                }
                val itemScale = computeMaterialItemScale(
                    centerY = visualCenterY,
                    screenCenterY = materialFisheyeCenterY,
                    deadZonePx = fisheyeDeadZonePx,
                    rangePx = fisheyeRangePx,
                    minScale = fisheyeMinScale,
                    fisheyeEnabled = materialFisheyeActive
                )
                val itemAlpha = if (materialFisheyeActive) itemScale.coerceIn(0.30f, 1f) else 1f
                val itemHeightPx = itemInfo?.size?.height?.toFloat()
                    ?: itemBounds?.height
                    ?: estimatedGridItemHeightPx
                val itemCenterX = materialGridItemCenterX(
                    itemInfo = itemInfo,
                    fallbackBounds = itemBounds,
                    fallback = estimatedCenterX
                )
                val edgeSpacingOffsetX = if (
                    materialFisheyeActive &&
                    edgeSpacingCompressionEnabled &&
                    (topFisheyeEnabled || visualCenterY >= materialFisheyeCenterY) &&
                    dragFromIndex == null &&
                    longPressedApp == null
                ) {
                    edgeSpacingCompressionHorizontalOffset(
                        centerX = itemCenterX,
                        rowCenterY = visualCenterY,
                        screenCenterX = materialContentCenterX,
                        screenCenterY = materialFisheyeCenterY,
                        screenHeight = screenHeightPx,
                        itemSize = itemHeightPx,
                        strengthPercent = fisheyeStrengthPercent,
                        enabled = true
                    )
                } else {
                    0f
                }
                val isDragged = dragFromIndex == index
                val isFolderHoverTarget = folderHoverIndex == index
                val itemShadowElevationPx = when {
                    entryProgress < 0.985f || !active -> 0f
                    materialScrollOptimizationActive -> iconShadowElevationPx * 0.62f
                    else -> iconShadowElevationPx
                }
                val dragTarget = materialDraggedTargetOffset(
                    index = index,
                    dragFromIndex = dragFromIndex,
                    dragCurrentIndex = dragCurrentIndex,
                    bounds = itemBounds,
                    itemBounds = materialItemBounds
                )
                val animatedDragX by animateFloatAsState(
                    targetValue = dragTarget.x + edgeSpacingOffsetX,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                    label = "material_drag_x"
                )
                val animatedDragY by animateFloatAsState(
                    targetValue = dragTarget.y,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                    label = "material_drag_y"
                )
                val displayIcon = remember(
                    app.componentKey,
                    app.cachedPlainIcon,
                    app.cachedPlainTwoToneIcon,
                    twoToneIconsEnabled
                ) {
                    app.plainIconForDisplay(
                        useTwoTone = twoToneIconsEnabled,
                        blurred = blurEnabled &&
                            edgeBlurEnabled &&
                            itemBlur > 0.25f &&
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    )
                }
                val pressedScale by animateFloatAsState(
                    targetValue = when {
                        isDragged -> 0.965f
                        isFolderHoverTarget -> 0.90f
                        isPressed -> 0.96f
                        else -> 1f
                    },
                    animationSpec = tween(durationMillis = 170),
                    label = "material_press_scale"
                )
                val materialIconShape = RoundedCornerShape((18.dp * appListScale).coerceIn(12.dp, 24.dp))
                val iconInnerPadding = (3.dp * appListScale).coerceIn(2.dp, 6.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.92f)
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot() - materialContainerRoot
                            val bounds = Rect(
                                left = pos.x,
                                top = pos.y,
                                right = pos.x + coords.size.width.toFloat(),
                                bottom = pos.y + coords.size.height.toFloat()
                            )
                            val previousBounds = materialItemBounds[index]
                            if (
                                previousBounds == null ||
                                boundsChangedMeaningfully(
                                    previousBounds,
                                    bounds,
                                    tolerancePx = 1f
                                )
                            ) {
                                materialItemBounds[index] = bounds
                            }
                        }
                        .graphicsLayer {
                            shadowElevation = itemShadowElevationPx
                            shape = materialIconShape
                            translationX = animatedDragX
                            translationY = animatedDragY
                            scaleX = itemScale * pressedScale
                            scaleY = itemScale * pressedScale
                            alpha = if (isDragged) 0f else itemAlpha
                        }
                        .platformBlur(
                            itemBlur,
                            blurEnabled && edgeBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && itemBlur > 0.25f
                        )
                        .clip(materialIconShape)
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                if (longPressedApp != null || dragFromIndex != null) return@combinedClickable
                                val launchCenter = Offset(
                                    x = itemCenterX + materialContainerRoot.x + animatedDragX,
                                    y = itemCenterY + materialContainerRoot.y + animatedDragY + overscroll.value + entryOffsetY
                                )
                                onAppClick(
                                    app,
                                    Offset(
                                        x = (launchCenter.x / screenWidthPx).coerceIn(0f, 1f),
                                        y = (launchCenter.y / screenHeightPx).coerceIn(0f, 1f)
                                    )
                                )
                            }
                        )
                        .padding(iconInnerPadding)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = displayIcon,
                            contentDescription = app.label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(materialIconShape)
                                .then(
                                    if (app.isBuiltInSettingsEntry) {
                                        Modifier
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }
        }

        val dragOverlayIndex = dragFromIndex
        val dragOverlayApp = dragOverlayIndex?.let { apps.getOrNull(it) }
        val dragOverlayPointer = dragVisualPointer ?: dragPointer
        val dragOverlayBounds = dragOverlayIndex?.let { materialItemBounds[it] ?: dragStartBounds }
        if (dragOverlayApp != null && dragOverlayPointer != null && dragOverlayBounds != null) {
            val overlayIcon = remember(
                dragOverlayApp.componentKey,
                dragOverlayApp.cachedPlainIcon,
                dragOverlayApp.cachedPlainTwoToneIcon,
                twoToneIconsEnabled
            ) {
                dragOverlayApp.plainIconForDisplay(
                    useTwoTone = twoToneIconsEnabled,
                    blurred = false
                )
            }
            val overlayWidth = with(density) { dragOverlayBounds.width.toDp() }
            val overlayHeight = with(density) { dragOverlayBounds.height.toDp() }
            Box(
                modifier = Modifier
                    .size(overlayWidth, overlayHeight)
                    .graphicsLayer {
                        translationX = dragOverlayPointer.x - dragOverlayBounds.width / 2f
                        translationY = dragOverlayPointer.y - dragOverlayBounds.height / 2f
                        shadowElevation = iconShadowElevationPx
                        shape = materialIconShape
                        clip = true
                        scaleX = 1.04f
                        scaleY = 1.04f
                    }
                    .padding(iconInnerPadding)
            ) {
                Image(
                    bitmap = overlayIcon,
                    contentDescription = dragOverlayApp.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(materialIconShape)
                )
            }
        }

        if (edgeFadesVisible && topFadeRangeDp > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(topFadeRangeDp.dp)
                    .graphicsLayer { alpha = materialEdgeOverlayAlpha }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                appPalette.fadeEdge,
                                Color.Transparent
                            )
                        )
                    )
                    .platformBlur(
                        topBlurRadiusDp,
                        blurEnabled &&
                            edgeBlurEnabled &&
                            materialEdgeOverlayAlpha > 0.001f
                    )
            )
        }
        if (edgeFadesVisible && bottomFadeRangeDp > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomFadeRangeDp.dp)
                    .graphicsLayer { alpha = materialEdgeOverlayAlpha }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                appPalette.fadeEdge
                            )
                        )
                    )
                    .platformBlur(
                        bottomBlurRadiusDp,
                        blurEnabled &&
                            edgeBlurEnabled &&
                            materialEdgeOverlayAlpha > 0.001f
                    )
            )
        }

        longPressedApp?.let { app ->
        AppShortcutOverlay(
            app = app,
            blurEnabled = blurEnabled,
            onExcludeApp = { onExcludeApp(app) },
            onRemoveShortcut = if (app.isAppListShortcut) { { onRemoveShortcut(app) } } else null,
            onRenameFolder = if (app.isFolder) { name -> onRenameFolder(app, name) } else null,
            onDissolveFolder = if (app.isFolder) { { onDissolveFolder(app) } } else null,
            onDismiss = { longPressedApp = null }
        )
        }
    }
}

private fun findMaterialGridIndexAt(
    pointer: Offset,
    itemBounds: Map<Int, Rect>,
    excludedIndex: Int? = null
): Int? {
    return itemBounds.entries.firstOrNull { (index, bounds) ->
        index != excludedIndex &&
        pointer.x in bounds.left..bounds.right && pointer.y in bounds.top..bounds.bottom
    }?.key
}

private fun findMaterialGridCenterIndex(
    pointer: Offset,
    itemBounds: Map<Int, Rect>,
    excludedIndex: Int? = null,
    centerFraction: Float = 0.42f
): Int? {
    val fraction = centerFraction.coerceIn(0.18f, 0.8f)
    return itemBounds.entries.firstOrNull { (index, bounds) ->
        if (index == excludedIndex) return@firstOrNull false
        val insetX = bounds.width * (1f - fraction) / 2f
        val insetY = bounds.height * (1f - fraction) / 2f
        val centerBounds = Rect(
            left = bounds.left + insetX,
            top = bounds.top + insetY,
            right = bounds.right - insetX,
            bottom = bounds.bottom - insetY
        )
        pointer.x in centerBounds.left..centerBounds.right &&
            pointer.y in centerBounds.top..centerBounds.bottom
    }?.key
}

private fun materialGridItemCenterX(
    itemInfo: LazyGridItemInfo?,
    fallbackBounds: Rect?,
    fallback: Float
): Float {
    return itemInfo?.let { it.offset.x + it.size.width / 2f }
        ?: fallbackBounds?.center?.x
        ?: fallback
}

private fun materialGridItemCenterY(
    itemInfo: LazyGridItemInfo?,
    fallbackBounds: Rect?,
    fallback: Float
): Float {
    return itemInfo?.let { it.offset.y + it.size.height / 2f }
        ?: fallbackBounds?.center?.y
        ?: fallback
}

private fun findNearestMaterialGridIndex(
    pointer: Offset,
    itemBounds: Map<Int, Rect>,
    excludedIndex: Int? = null,
    maxDistance: Float = Float.MAX_VALUE
): Int? {
    return itemBounds.entries
        .asSequence()
        .filter { (index, _) -> index != excludedIndex }
        .map { (index, bounds) ->
            val distance = (pointer - bounds.center).getDistance()
            index to distance
        }
        .filter { (_, distance) -> distance <= maxDistance }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

private fun materialDraggedTargetOffset(
    index: Int,
    dragFromIndex: Int?,
    dragCurrentIndex: Int?,
    bounds: Rect?,
    itemBounds: Map<Int, Rect>
): Offset {
    if (bounds == null || dragFromIndex == null || dragCurrentIndex == null) return Offset.Zero
    if (index == dragFromIndex) {
        return Offset.Zero
    }
    if (dragFromIndex == dragCurrentIndex) return Offset.Zero
    val targetIndex = materialVisualSlotIndex(index, dragFromIndex, dragCurrentIndex)
    if (targetIndex == index) return Offset.Zero
    val targetBounds = itemBounds[targetIndex] ?: return Offset.Zero
    return targetBounds.center - bounds.center
}

private fun computeMaterialItemScale(
    centerY: Float,
    screenCenterY: Float,
    deadZonePx: Float,
    rangePx: Float,
    minScale: Float,
    fisheyeEnabled: Boolean
): Float {
    if (!fisheyeEnabled) return 1f
    val distance = max(0f, abs(centerY - screenCenterY) - deadZonePx)
    return fisheyeScale(distance, rangePx, minScale = minScale)
}

private fun materialVisualSlotIndex(
    index: Int,
    dragFromIndex: Int,
    dragCurrentIndex: Int
): Int {
    if (dragFromIndex == dragCurrentIndex) return index
    if (index == dragFromIndex) return dragFromIndex
    return when {
        dragCurrentIndex > dragFromIndex && index in (dragFromIndex + 1)..dragCurrentIndex -> index - 1
        dragCurrentIndex < dragFromIndex && index in dragCurrentIndex until dragFromIndex -> index + 1
        else -> index
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
        val filtered = pinnedIndexes.filter { it >= 0 }.sorted()
        return filtered.toIntArray()
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
    val maxIndex = rows.last().endIndex.coerceAtLeast(0)
    val included = BooleanArray(maxIndex + 1)
    if (firstVisibleRow >= 0 && lastVisibleRow >= 0) {
        val startRow = (firstVisibleRow - bufferRows).coerceAtLeast(0)
        val endRow = (lastVisibleRow + bufferRows).coerceAtMost(rows.lastIndex)
        for (rowIndex in startRow..endRow) {
            val row = rows[rowIndex]
            for (index in row.startIndex..row.endIndex) {
                if (index in included.indices) {
                    included[index] = true
                }
            }
        }
    }
    pinnedIndexes.forEach { index ->
        if (index in included.indices) {
            included[index] = true
        }
    }
    val rendered = IntArray(included.count { it })
    var cursor = 0
    for (index in included.indices) {
        if (included[index]) {
            rendered[cursor++] = index
        }
    }
    return rendered
}

private fun resolveHoneycombScrollOffset(
    directScrollOffset: Float,
    animatedScrollOffset: Float
): Float = if (directScrollOffset.isNaN()) animatedScrollOffset else directScrollOffset

private fun boundsChangedMeaningfully(
    previous: Rect,
    next: Rect,
    tolerancePx: Float
): Boolean {
    return abs(previous.left - next.left) > tolerancePx ||
        abs(previous.top - next.top) > tolerancePx ||
        abs(previous.right - next.right) > tolerancePx ||
        abs(previous.bottom - next.bottom) > tolerancePx
}

private fun reduceHoneycombBlurForFastScroll(
    blurDp: Float,
    reduced: Boolean
): Float {
    if (!reduced) return blurDp
    return (blurDp * 0.32f).coerceAtMost(1.6f)
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
    timeoutMillis: Long,
    moveTolerancePx: Float = viewConfiguration.touchSlop
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
            if ((change.position - downPosition).getDistance() > moveTolerancePx) {
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
