package com.flue.launcher.ui.drawer

import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flue.launcher.applist.integration.rememberNativeAppListProjection
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.nativeview.NativeAppListConfig
import com.flue.launcher.applist.nativeview.NativeAppListHost
import com.flue.launcher.applist.nativeview.NativeAppListPalette
import com.flue.launcher.applist.nativeview.NativeAppListIconShape
import com.flue.launcher.applist.nativeview.NativeAppListPresentation
import com.flue.launcher.applist.nativeview.HoneycombNativeAppListLayoutStrategy
import com.flue.launcher.applist.nativeview.DenseGridNativeAppListLayoutStrategy
import com.flue.launcher.applist.nativeview.LinearNativeAppListLayoutStrategy
import com.flue.launcher.applist.nativeview.NativeAppListRowBorder
import com.flue.launcher.applist.nativeview.NativeAppListTheme
import com.flue.launcher.applist.nativeview.NativeDeviceShape
import com.flue.launcher.applist.nativeview.NativeFisheyePreset
import com.flue.launcher.applist.nativeview.selectNativeBlurTier
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.theme.UiStyle
import kotlin.math.roundToInt

/**
 * Edge mask owned by a sibling of the blurred app-list layer. Keeping this
 * tiny paint pass outside the list RenderNode prevents RenderEffect from
 * blurring the mask itself and removes the one-frame flash on app return.
 */
@Composable
internal fun NativeAppListEdgeFadeOverlay(
    visible: Boolean,
    topFadeRangeDp: Int,
    bottomFadeRangeDp: Int,
    edgeColor: Color,
    modifier: Modifier = Modifier,
) {
    // This mask is deliberately outside the blurred RecyclerView layer. A
    // second Compose animation clock here used to outlive the app-list return
    // transition by one or two frames, which produced the reported top-edge
    // flash. The parent layer owns the transition; the mask only follows its
    // committed visibility state.
    val overlayAlpha = if (visible) 1f else 0f
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val topHeight = topFadeRangeDp.dp.toPx().coerceIn(0f, size.height)
                val bottomHeight = bottomFadeRangeDp.dp.toPx().coerceIn(0f, size.height)
                val topBrush = if (topHeight > 0f) {
                    Brush.verticalGradient(
                        colors = listOf(
                            edgeColor.copy(alpha = 0.38f),
                            edgeColor.copy(alpha = 0.14f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = topHeight,
                    )
                } else null
                val bottomBrush = if (bottomHeight > 0f) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            edgeColor.copy(alpha = 0.14f),
                            edgeColor.copy(alpha = 0.42f),
                        ),
                        startY = size.height - bottomHeight,
                        endY = size.height,
                    )
                } else null
                onDrawBehind {
                    topBrush?.let { drawRect(it, size = size.copy(height = topHeight)) }
                    bottomBrush?.let {
                        drawRect(
                            it,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - bottomHeight),
                            size = size.copy(height = bottomHeight),
                        )
                    }
                }
            }
            .graphicsLayer { alpha = overlayAlpha },
    )
}

@Composable
fun NativeListDrawerScreen(
    apps: List<AppInfo>,
    blurEnabled: Boolean = true,
    edgeBlurEnabled: Boolean = false,
    suppressHeavyEffects: Boolean = false,
    fisheyeEnabled: Boolean = true,
    fisheyeRangeRows: Int = 4,
    fisheyeStrengthPercent: Int = 100,
    edgeSpacingCompressionEnabled: Boolean = true,
    fastFlowAnimationEnabled: Boolean = false,
    twoToneIconsEnabled: Boolean = false,
    iconShadowEnabled: Boolean = true,
    themeColor: Color = Color(0xFF7BE8FF),
    uiStyle: UiStyle = UiStyle.APPLE_WATCH,
    darkMode: Boolean = true,
    iconSize: Dp = 48.dp,
    topFadeRangeDp: Int = 56,
    bottomFadeRangeDp: Int = 56,
    topBlurRadiusDp: Float = 4f,
    bottomBlurRadiusDp: Float = 4f,
    drawEdgeFadeMaskInNativeView: Boolean = true,
    rotaryHapticsEnabled: Boolean = true,
    active: Boolean = true,
    initialScrollResetKey: Int = 0,
    entryAnimationToken: Long = 0L,
    leftSafeInsetPercent: Int = 0,
    appListScalePercent: Int = 100,
    entryProgress: Float = 1f,
    folderOpen: Boolean = false,
    useWatchFaceColors: Boolean = false,
    rowBorderEnabled: Boolean = false,
    allowFolderCreation: Boolean = true,
    presentation: NativeAppListPresentation = NativeAppListPresentation.LINEAR,
    honeycombNarrowColumns: Int = 4,
    onAppClick: (AppInfo, Offset) -> Unit,
    onLongClick: (AppInfo) -> Unit = {},
    onReorder: (AppListCommand.Move) -> Unit = {},
    onCreateFolder: (AppListCommand.CreateFolder) -> Unit = {},
    onExcludeApp: (AppInfo) -> Unit = {},
    onRemoveShortcut: (AppInfo) -> Unit = {},
    onRenameFolder: (AppInfo, String) -> Unit = { _, _ -> },
    onDissolveFolder: (AppInfo) -> Unit = {},
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val projection = rememberNativeAppListProjection(apps, twoToneIconsEnabled)
    var actionAppKey by remember { mutableStateOf<String?>(null) }
    val actionApp = actionAppKey?.let { key -> projection.appsByKey[AppItemKey(key)] }
    LaunchedEffect(actionAppKey, projection) {
        if (actionAppKey != null && actionApp == null) actionAppKey = null
    }
    val localDensity = LocalDensity.current
    val isRoundDevice = LocalConfiguration.current.isScreenRound
    val density = localDensity.density
    val paletteStyle = if (
        uiStyle == UiStyle.MATERIAL_3 &&
        presentation != NativeAppListPresentation.DENSE_GRID
    ) {
        UiStyle.APPLE_WATCH
    } else {
        uiStyle
    }
    val palette = appListPalette(themeColor, darkMode, paletteStyle, useWatchFaceColors)

    BoxWithConstraints(modifier) {
        val viewportWidthPx = (maxWidth.value * density).roundToInt()
        val viewportHeightPx = (maxHeight.value * density).roundToInt()
        val config = nativeListDrawerConfig(
            NativeListDrawerConfigRequest(
                density = density,
                fontScale = localDensity.fontScale,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                presentation = presentation,
                honeycombNarrowColumns = honeycombNarrowColumns,
                active = active,
                initialResetToken = initialScrollResetKey.toLong().coerceAtLeast(0L),
                entryAnimationToken = entryAnimationToken,
                leftSafeInsetPercent = leftSafeInsetPercent,
                appListScalePercent = appListScalePercent,
                iconSizeDp = iconSize.value,
                appleWatchIconShape = uiStyle == UiStyle.APPLE_WATCH,
                fisheyeEnabled = fisheyeEnabled,
                fisheyeRangeRows = fisheyeRangeRows,
                fisheyeStrengthPercent = fisheyeStrengthPercent,
                edgeSpacingCompressionEnabled = edgeSpacingCompressionEnabled,
                fastFlowAnimationEnabled = fastFlowAnimationEnabled,
                topFadeRangeDp = topFadeRangeDp,
                bottomFadeRangeDp = bottomFadeRangeDp,
                topBlurRadiusDp = topBlurRadiusDp,
                bottomBlurRadiusDp = bottomBlurRadiusDp,
                drawEdgeFadeMaskInNativeView = drawEdgeFadeMaskInNativeView,
                overlayOpen = actionApp != null || folderOpen,
                blurEnabled = blurEnabled,
                edgeBlurEnabled = edgeBlurEnabled,
                suppressHeavyEffects = suppressHeavyEffects,
                sdkInt = Build.VERSION.SDK_INT,
                rotaryHapticsEnabled = rotaryHapticsEnabled,
                darkMode = darkMode,
                rowBorderEnabled = rowBorderEnabled,
                allowFolderCreation = allowFolderCreation,
                iconShadowEnabled = iconShadowEnabled,
                entryProgress = entryProgress,
                rowBackgroundArgb = palette.item.toUnsignedArgbLong(),
                labelArgb = palette.text.toUnsignedArgbLong(),
                pressedArgb = palette.itemPressedOverlay.toUnsignedArgbLong(),
                fadeEdgeArgb = palette.fadeEdge.toUnsignedArgbLong(),
                isRoundDevice = isRoundDevice,
            ),
        )
        NativeAppListHost(
            content = projection.content,
            config = config,
            onCommand = { command ->
                val app = projection.appsByKey[command.keyOrNull()]
                when (command) {
                    is AppListCommand.Open -> if (app != null) {
                        onAppClick(app, Offset(command.origin.x, command.origin.y))
                    }
                    is AppListCommand.ShowActions -> if (app != null) {
                        actionAppKey = command.key.value
                        onLongClick(app)
                    }
                    AppListCommand.DismissActions -> actionAppKey = null
                    is AppListCommand.Move -> onReorder(command)
                    is AppListCommand.CreateFolder -> onCreateFolder(command)
                    is AppListCommand.Hide -> if (app != null) onExcludeApp(app)
                    is AppListCommand.RemoveShortcut -> if (app != null) onRemoveShortcut(app)
                    is AppListCommand.RenameFolder -> if (app != null) onRenameFolder(app, command.name)
                    is AppListCommand.DissolveFolder -> if (app != null) onDissolveFolder(app)
                }
            },
            onScrollToTop = onScrollToTop,
            layoutStrategy = when (presentation) {
                NativeAppListPresentation.HONEYCOMB -> HoneycombNativeAppListLayoutStrategy
                NativeAppListPresentation.DENSE_GRID -> DenseGridNativeAppListLayoutStrategy
                NativeAppListPresentation.LINEAR -> LinearNativeAppListLayoutStrategy
            },
            modifier = Modifier
                .fillMaxSize()
                .background(
                    palette.background.copy(
                        alpha = if (uiStyle == UiStyle.APPLE_WATCH) {
                            1f
                        } else {
                            entryProgress.coerceIn(0f, 1f)
                        },
                    ),
                ),
        )
        actionApp?.let { app ->
            AppShortcutOverlay(
                app = app,
                blurEnabled = blurEnabled,
                onExcludeApp = { onExcludeApp(app) },
                onRemoveShortcut = if (app.isAppListShortcut) {
                    { onRemoveShortcut(app) }
                } else {
                    null
                },
                onRenameFolder = if (app.isFolder) {
                    { name -> onRenameFolder(app, name) }
                } else {
                    null
                },
                onDissolveFolder = if (app.isFolder) {
                    { onDissolveFolder(app) }
                } else {
                    null
                },
                onDismiss = { actionAppKey = null },
            )
        }
    }
}

internal data class NativeListDrawerConfigRequest(
    val density: Float,
    val fontScale: Float,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val presentation: NativeAppListPresentation,
    val honeycombNarrowColumns: Int,
    val active: Boolean,
    val initialResetToken: Long,
    val entryAnimationToken: Long = 0L,
    val leftSafeInsetPercent: Int,
    val appListScalePercent: Int,
    val iconSizeDp: Float,
    val appleWatchIconShape: Boolean,
    val fisheyeEnabled: Boolean,
    val fisheyeRangeRows: Int,
    val fisheyeStrengthPercent: Int,
    val edgeSpacingCompressionEnabled: Boolean,
    val fastFlowAnimationEnabled: Boolean,
    val topFadeRangeDp: Int,
    val bottomFadeRangeDp: Int,
    val topBlurRadiusDp: Float,
    val bottomBlurRadiusDp: Float,
    val drawEdgeFadeMaskInNativeView: Boolean = true,
    val overlayOpen: Boolean,
    val blurEnabled: Boolean,
    val edgeBlurEnabled: Boolean,
    val suppressHeavyEffects: Boolean,
    val sdkInt: Int,
    val rotaryHapticsEnabled: Boolean,
    val darkMode: Boolean,
    val rowBorderEnabled: Boolean,
    val allowFolderCreation: Boolean,
    val iconShadowEnabled: Boolean,
    val entryProgress: Float,
    val rowBackgroundArgb: Long,
    val labelArgb: Long,
    val pressedArgb: Long,
    val fadeEdgeArgb: Long,
    val isRoundDevice: Boolean = false,
)

internal fun nativeListDrawerConfig(request: NativeListDrawerConfigRequest): NativeAppListConfig {
    val density = request.density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val scale = request.appListScalePercent.coerceIn(50, 200) / 100f
    val leftSafeInsetPx = (
        request.viewportWidthPx.coerceAtLeast(0) *
            request.leftSafeInsetPercent.coerceIn(0, 50) / 100f
        ).roundToInt()
    val honeycombNarrowColumns = request.honeycombNarrowColumns.coerceIn(1, 10)
    val denseGridBaseColumns = (honeycombNarrowColumns + 1).coerceIn(2, 6)
    val denseGridColumns = (denseGridBaseColumns / scale).roundToInt().coerceIn(1, 10)
    val denseGridHorizontalPaddingPx = 8f * density
    val denseGridItemSpacingPx = 4f * density
    val denseGridMinimumItemPx = 48f * density
    val denseGridMinimumVerticalPaddingPx = 8f * density
    val denseGridIconInnerPaddingPx = (3f * scale).coerceIn(2f, 6f) * density
    val honeycombIconSizePx = if (request.presentation == NativeAppListPresentation.HONEYCOMB) {
        val viewportWidth = request.viewportWidthPx.coerceAtLeast(1)
        val contentWidth = (viewportWidth - leftSafeInsetPx).toFloat()
            .coerceAtLeast(viewportWidth * 0.55f)
        val availableWidth = (contentWidth - 20f * density).coerceAtLeast(1f)
        val maxColumns = honeycombNarrowColumns + 1
        val base = (availableWidth / (maxColumns + 0.35f)).coerceIn(
            54f * density,
            84f * density,
        )
        (base * scale).coerceIn(28f * density, 148f * density).roundToInt()
    } else {
        0
    }
    val scaledIconSizeDp = (request.iconSizeDp * scale).coerceIn(24f, 128f)
    val scaledVerticalPaddingDp = (10f * scale).coerceIn(4f, 24f)
    val scaledHorizontalPaddingDp = (16f * scale).coerceIn(10f, 32f)
    val scaledIconTextGapDp = (14f * scale).coerceIn(8f, 28f)
    val scaledTextSizeSp = (16f * scale).coerceIn(10f, 32f)
    val fontScale = request.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val iconSizePx = honeycombIconSizePx.takeIf { it > 0 }
        ?: (scaledIconSizeDp * density).roundToInt()
        .coerceAtLeast(1)
    val verticalPaddingPx = (scaledVerticalPaddingDp * density).roundToInt().coerceAtLeast(0)
    val horizontalPaddingPx = (scaledHorizontalPaddingDp * density).roundToInt().coerceAtLeast(0)
    val honeycombCellSizePx = if (request.presentation == NativeAppListPresentation.HONEYCOMB) {
        iconSizePx * 1.02f
    } else {
        0f
    }
    val rowHeightPx = if (request.presentation == NativeAppListPresentation.HONEYCOMB) {
        honeycombCellSizePx.roundToInt()
    } else {
        iconSizePx + verticalPaddingPx * 2
    }
    val halfViewport = request.viewportHeightPx.coerceAtLeast(1) / 2f
    val edgeBand = (
        rowHeightPx * request.fisheyeRangeRows.coerceIn(1, 8) / halfViewport
        ).coerceIn(0.01f, 1f)
    val blurTier = selectNativeBlurTier(
        sdkInt = request.sdkInt,
        blurEnabled = request.blurEnabled,
        edgeBlurEnabled = request.edgeBlurEnabled,
        suppressHeavyEffects = request.suppressHeavyEffects,
    )
    return NativeAppListConfig(
        active = request.active,
        initialResetToken = request.initialResetToken,
        entryAnimationToken = request.entryAnimationToken,
        presentation = request.presentation,
        leftSafeInsetPx = leftSafeInsetPx,
        rowHeightPx = rowHeightPx,
        iconSizePx = iconSizePx,
        rowPaddingStartPx = horizontalPaddingPx + (12f * density).roundToInt(),
        rowPaddingTopPx = verticalPaddingPx,
        rowPaddingEndPx = horizontalPaddingPx + (12f * density).roundToInt(),
        rowPaddingBottomPx = verticalPaddingPx,
        labelTextSizePx = (scaledTextSizeSp * density * fontScale).coerceAtLeast(1f),
        iconLabelGapPx = (scaledIconTextGapDp * density).roundToInt().coerceAtLeast(0),
        iconShape = if (request.appleWatchIconShape) {
            NativeAppListIconShape.CIRCLE
        } else {
            NativeAppListIconShape.ROUNDED_RECT
        },
        iconCornerRadiusPx = if (request.presentation == NativeAppListPresentation.DENSE_GRID) {
            (18f * scale).coerceIn(12f, 24f) * density
        } else {
            (14f * scale).coerceIn(10f, 24f) * density
        },
        honeycombCellSizePx = honeycombCellSizePx,
        honeycombNarrowColumns = honeycombNarrowColumns,
        honeycombFisheyeRangeRows = request.fisheyeRangeRows,
        denseGridColumns = denseGridColumns,
        denseGridHorizontalPaddingPx = denseGridHorizontalPaddingPx,
        denseGridItemSpacingPx = denseGridItemSpacingPx,
        denseGridItemAspectRatio = 0.92f,
        denseGridMinimumItemWidthPx = denseGridMinimumItemPx,
        denseGridMinimumItemHeightPx = denseGridMinimumItemPx,
        denseGridMinimumVerticalPaddingPx = denseGridMinimumVerticalPaddingPx,
        denseGridIconInnerPaddingPx = denseGridIconInnerPaddingPx,
        centerEdgeItems = request.presentation == NativeAppListPresentation.LINEAR,
        allowFolderCreation = request.allowFolderCreation,
        iconShadowEnabled = request.iconShadowEnabled,
        fastFlowAnimationEnabled = request.fastFlowAnimationEnabled &&
            request.presentation != NativeAppListPresentation.DENSE_GRID,
        edgeSpacingCompressionEnabled = request.edgeSpacingCompressionEnabled,
        entryMotionEnabled = !request.appleWatchIconShape,
        entryProgress = request.entryProgress,
        palette = NativeAppListPalette(
            rowBackgroundColorArgb = if (
                request.rowBorderEnabled ||
                request.presentation == NativeAppListPresentation.DENSE_GRID
            ) {
                request.rowBackgroundArgb
            } else {
                0L
            },
            labelColorArgb = request.labelArgb,
            pressedOverlayColorArgb = request.pressedArgb,
            fallbackIconBackgroundColorArgb = request.rowBackgroundArgb,
            fallbackIconForegroundColorArgb = request.labelArgb,
            fadeEdgeColorArgb = request.fadeEdgeArgb,
        ),
        // Geometry is already scaled above; applying the scale again as a
        // child transform would shrink/grow hit regions and launch origins twice.
        appScale = 1f,
        fisheyeEnabled = request.fisheyeEnabled,
        fisheyeRange = edgeBand,
        fisheyeStrength = request.fisheyeStrengthPercent.coerceIn(0, 200) / 100f,
        fisheyePreset = if (request.appleWatchIconShape) {
            NativeFisheyePreset.CLASSIC
        } else {
            NativeFisheyePreset.NEXT
        },
        deviceShape = if (request.isRoundDevice) {
            NativeDeviceShape.ROUND
        } else {
            NativeDeviceShape.RECTANGULAR
        },
        topFadeRangePx = request.topFadeRangeDp.coerceAtLeast(0) * density,
        bottomFadeRangePx = request.bottomFadeRangeDp.coerceAtLeast(0) * density,
        topBlurRadiusPx = request.topBlurRadiusDp
            .takeIf { it.isFinite() }
            ?.coerceAtLeast(0f)
            ?.times(density)
            ?: 0f,
        bottomBlurRadiusPx = request.bottomBlurRadiusDp
            .takeIf { it.isFinite() }
            ?.coerceAtLeast(0f)
            ?.times(density)
            ?: 0f,
        drawEdgeFadeMask = request.drawEdgeFadeMaskInNativeView,
        contentOverlayBlurRadiusPx = if (request.overlayOpen) 16f * density else 0f,
        blurTier = blurTier,
        rotaryHapticsEnabled = request.rotaryHapticsEnabled,
        theme = if (request.darkMode) NativeAppListTheme.DARK else NativeAppListTheme.LIGHT,
        rowBorder = NativeAppListRowBorder(
            enabled = request.rowBorderEnabled,
            colorArgb = request.labelArgb and 0x33ffffffL,
            widthPx = if (request.rowBorderEnabled) density else 0f,
            cornerRadiusPx = if (request.presentation == NativeAppListPresentation.DENSE_GRID) {
                (18f * scale).coerceIn(12f, 24f) * density
            } else {
                18f * density
            },
        ),
    )
}

private fun AppListCommand.keyOrNull() = when (this) {
    is AppListCommand.Open -> key
    is AppListCommand.ShowActions -> key
    AppListCommand.DismissActions -> null
    is AppListCommand.Move -> key
    is AppListCommand.CreateFolder -> source
    is AppListCommand.Hide -> key
    is AppListCommand.RemoveShortcut -> key
    is AppListCommand.RenameFolder -> key
    is AppListCommand.DissolveFolder -> key
}

private fun Color.toUnsignedArgbLong(): Long = toArgb().toLong() and 0xffffffffL
