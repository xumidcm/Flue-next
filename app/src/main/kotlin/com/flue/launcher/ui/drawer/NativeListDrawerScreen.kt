package com.flue.launcher.ui.drawer

import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flue.launcher.applist.background.AppListBackgroundPainting
import com.flue.launcher.applist.background.appListBackground
import com.flue.launcher.applist.integration.rememberNativeAppListProjection
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.nativeview.NativeAppListConfig
import com.flue.launcher.applist.nativeview.NativeFlowGeometry
import com.flue.launcher.layout.FLUE_LAYOUT_CRESCENT_BULGE_MAX
import com.flue.launcher.applist.nativeview.NativeAppListHost
import com.flue.launcher.applist.nativeview.NativeAppListPalette
import com.flue.launcher.applist.nativeview.NativeAppListIconShape
import com.flue.launcher.applist.nativeview.NativeAppListPresentation
import com.flue.launcher.util.FlowCrescentDirection
import com.flue.launcher.util.FlowShapeCurve
import com.flue.launcher.applist.nativeview.HoneycombNativeAppListLayoutStrategy
import com.flue.launcher.applist.nativeview.DenseGridNativeAppListLayoutStrategy
import com.flue.launcher.applist.nativeview.FlowNativeAppListLayoutStrategy
import com.flue.launcher.applist.nativeview.RingNativeAppListLayoutStrategy
import com.flue.launcher.applist.nativeview.LinearNativeAppListLayoutStrategy
import com.flue.launcher.applist.nativeview.nativeRingCellSizePx
import com.flue.launcher.applist.nativeview.nativeRingIconSizePx
import com.flue.launcher.applist.nativeview.nativeScrollIndicatorReservedWidthPx
import com.flue.launcher.applist.nativeview.NativeAppListRowBorder
import com.flue.launcher.applist.nativeview.NativeAppListTheme
import com.flue.launcher.applist.nativeview.NativeDeviceShape
import com.flue.launcher.applist.nativeview.NativeFisheyePreset
import com.flue.launcher.applist.nativeview.quantizeNativeBlurRadiusPx
import com.flue.launcher.applist.nativeview.selectNativeBlurTier
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.theme.UiStyle
import kotlin.math.roundToInt
import kotlin.math.max

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
    /** 仅 MD3 密集蜂窝使用；默认关闭顶部鱼眼。 */
    honeycombTopFisheyeEnabled: Boolean = false,
    edgeSpacingCompressionEnabled: Boolean = true,
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
    entryStaggerEnabled: Boolean = false,
    leftSafeInsetPercent: Int = 0,
    appListScalePercent: Int = 100,
    entryProgress: Float = 1f,
    /**
     * 转场进行中时，列表自己的底色要让位给外层那块「不缩放的背板」。
     * 这层背景画在会缩放的图层里，关列表时它会跟着图标一起缩小——
     * 老师要的是「黑底不动、只有图标缩放、深色背景走透明度渐变」。
     */
    backgroundHandledByPlate: Boolean = false,
    /**
     * 自定义背景（纯色 / 自定义色 / 跟随表盘 / 自定义图片）的完整描述。
     *
     * 由调用方组装（`LauncherActivity`），因为外层那块不缩放的背板要画得**一模一样** ——
     * 两处各算一份必然出现「缩放中途看到两种背景的边界」。`null` = 沿用 [palette] 的纯色，
     * 与这个功能加进来之前逐位相同。
     */
    backgroundPainting: AppListBackgroundPainting? = null,
    folderOpen: Boolean = false,
    folderOverlayProgress: Float = if (folderOpen) 1f else 0f,
    useWatchFaceColors: Boolean = false,
    rowBorderEnabled: Boolean = false,
    /**
     * 网格布局圆图标底下那块底板要不要画。见 [NativeListDrawerConfigRequest.gridIconPlateEnabled]。
     */
    gridIconPlateEnabled: Boolean = false,
    allowFolderCreation: Boolean = false,
    /**
     * 屏幕右侧的滑动进度指示器。四种（以及以后新增的）列表样式共用一条，
     * 因为它读的是 RecyclerView 的滚动量，跟布局怎么排无关。
     */
    scrollIndicatorEnabled: Boolean = true,
    presentation: NativeAppListPresentation = NativeAppListPresentation.LINEAR,
    honeycombNarrowColumns: Int = 3,
    /** 心流月牙鼓出量，占内容宽度的百分比（0 = 不鼓，心流退化成规则网格）。 */
    flowCrescentBulgePercent: Int = 0,
    /** 心流月牙朝右（true）还是朝左。 */
    flowCrescentToRight: Boolean = true,
    flowShapeCurve: FlowShapeCurve = FlowShapeCurve.Crescent,
    onAppClick: (AppInfo, Offset) -> Unit,
    onLongClick: (AppInfo) -> Unit = {},
    onReorder: (AppListCommand.Move) -> Boolean = { false },
    onCreateFolder: (AppListCommand.CreateFolder) -> String? = { null },
    onExcludeApp: (AppInfo) -> Unit = {},
    onRemoveShortcut: (AppInfo) -> Unit = {},
    onRenameFolder: (AppInfo, String) -> Unit = { _, _ -> },
    onDissolveFolder: (AppInfo) -> Unit = {},
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val projection = rememberNativeAppListProjection(apps)
    var actionAppKey by remember { mutableStateOf<String?>(null) }
    val actionApp = actionAppKey?.let { key -> projection.appsByKey[AppItemKey(key)] }
    // 长按菜单的模糊进度由菜单自己的 alpha 反馈，而不是挂在「actionApp 是否为
    // null」上再跑一条独立 tween。挂在挂载状态上时两条时间线是错开的：菜单先
    // 淡完，onDismiss 之后模糊才开始退，看起来像模糊自己延迟化开。文件夹那侧
    // （folderOverlayProgress）一直就是反馈式的，这里对齐它。
    var actionOverlayProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(actionApp == null) {
        if (actionApp == null) actionOverlayProgress = 0f
    }
    val overlayProgress = max(
        folderOverlayProgress.coerceIn(0f, 1f),
        actionOverlayProgress.coerceIn(0f, 1f),
    )
    LaunchedEffect(actionAppKey, projection) {
        if (actionAppKey != null && actionApp == null) actionAppKey = null
    }
    val localDensity = LocalDensity.current
    // 屏形只认 isFlueScreenRound()：部分方屏手表的系统会错误上报圆屏（反之亦然），
    // 用户可以在设置里手动覆盖。以前这里直接读 LocalConfiguration.isScreenRound，
    // 于是「显示适配」里那个形状覆盖对应用列表完全无效 —— 列表的圆屏收边和右侧指示器
    // 该画弧还是画直线，全都要跟着这一个真相来源走。
    val isRoundDevice = isFlueScreenRound()
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
    // 层内底色的不透明度时钟一字不改，只是「画什么」从一个纯色换成可能带底图 + 遮罩的整层。
    val backgroundLayerAlpha = when {
        // 背板接管期间层内底色必须为 0：两层同色叠加时缩放的那一层就是「跟着缩的黑底」。
        backgroundHandledByPlate -> 0f
        uiStyle == UiStyle.APPLE_WATCH -> 1f
        else -> entryProgress.coerceIn(0f, 1f)
    }
    val backgroundModifier = if (backgroundPainting != null) {
        Modifier.appListBackground(backgroundPainting, backgroundLayerAlpha)
    } else {
        Modifier.background(palette.background.copy(alpha = backgroundLayerAlpha))
    }

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
                entryStaggerEnabled = entryStaggerEnabled,
                leftSafeInsetPercent = leftSafeInsetPercent,
                appListScalePercent = appListScalePercent,
                iconSizeDp = iconSize.value,
                appleWatchIconShape = uiStyle == UiStyle.APPLE_WATCH,
                fisheyeEnabled = fisheyeEnabled,
                fisheyeRangeRows = fisheyeRangeRows,
                fisheyeStrengthPercent = fisheyeStrengthPercent,
                honeycombTopFisheyeEnabled = honeycombTopFisheyeEnabled,
                flowCrescentBulgePercent = flowCrescentBulgePercent,
                flowCrescentToRight = flowCrescentToRight,
                flowShapeCurve = flowShapeCurve,
                edgeSpacingCompressionEnabled = edgeSpacingCompressionEnabled,
                topFadeRangeDp = topFadeRangeDp,
                bottomFadeRangeDp = bottomFadeRangeDp,
                topBlurRadiusDp = topBlurRadiusDp,
                bottomBlurRadiusDp = bottomBlurRadiusDp,
                drawEdgeFadeMaskInNativeView = drawEdgeFadeMaskInNativeView,
                overlayOpen = actionApp != null || folderOpen,
                overlayProgress = overlayProgress,
                dragBlocked = folderOpen,
                blurEnabled = blurEnabled,
                edgeBlurEnabled = edgeBlurEnabled,
                suppressHeavyEffects = suppressHeavyEffects,
                sdkInt = Build.VERSION.SDK_INT,
                rotaryHapticsEnabled = rotaryHapticsEnabled,
                darkMode = darkMode,
                rowBorderEnabled = rowBorderEnabled,
                gridIconPlateEnabled = gridIconPlateEnabled,
                allowFolderCreation = allowFolderCreation,
                scrollIndicatorEnabled = scrollIndicatorEnabled,
                iconShadowEnabled = iconShadowEnabled,
                entryProgress = entryProgress,
                rowBackgroundArgb = palette.item.toUnsignedArgbLong(),
                labelArgb = palette.text.toUnsignedArgbLong(),
                pressedArgb = palette.itemPressedOverlay.toUnsignedArgbLong(),
                fadeEdgeArgb = palette.fadeEdge.toUnsignedArgbLong(),
                isRoundDevice = isRoundDevice,
            ),
        )
        // RecyclerView rows and LayoutParams are presentation-specific. Reusing
        // the honeycomb host for a linear list leaves sampled icon transforms
        // and spatial layout params attached until process restart. Recreate the
        // native host atomically whenever the presentation changes.
        key(presentation) {
            NativeAppListHost(
                content = projection.content,
                config = config,
                onCreateFolder = { command ->
                    onCreateFolder(command)?.let(::AppItemKey)
                },
                onCommand = { command ->
                    val app = projection.appsByKey[command.keyOrNull()]
                    when (command) {
                        is AppListCommand.Open -> app?.let {
                            onAppClick(it, Offset(command.origin.x, command.origin.y))
                            true
                        } ?: false
                        is AppListCommand.ShowActions -> app?.let {
                            actionAppKey = command.key.value
                            onLongClick(it)
                            true
                        } ?: false
                        AppListCommand.DismissActions -> {
                            actionAppKey = null
                            true
                        }
                        is AppListCommand.Move -> onReorder(command)
                        // Folder creation uses NativeAppListHost's typed result
                        // callback above so the exact repository stable key can
                        // drive the overlay settle destination.
                        is AppListCommand.CreateFolder -> false
                        is AppListCommand.Hide -> app?.let {
                            onExcludeApp(it)
                            true
                        } ?: false
                        is AppListCommand.RemoveShortcut -> app?.let {
                            onRemoveShortcut(it)
                            true
                        } ?: false
                        is AppListCommand.RenameFolder -> app?.let {
                            onRenameFolder(it, command.name)
                            true
                        } ?: false
                        is AppListCommand.DissolveFolder -> app?.let {
                            onDissolveFolder(it)
                            true
                        } ?: false
                    }
                },
                onScrollToTop = onScrollToTop,
                layoutStrategy = when (presentation) {
                    NativeAppListPresentation.HONEYCOMB -> HoneycombNativeAppListLayoutStrategy
                    NativeAppListPresentation.DENSE_GRID -> DenseGridNativeAppListLayoutStrategy
                    NativeAppListPresentation.FLOW -> FlowNativeAppListLayoutStrategy
                    NativeAppListPresentation.RING -> RingNativeAppListLayoutStrategy
                    NativeAppListPresentation.LINEAR -> LinearNativeAppListLayoutStrategy
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(backgroundModifier),
            )
        }
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
                onTransitionProgress = { actionOverlayProgress = it },
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
    val entryStaggerEnabled: Boolean = false,
    val leftSafeInsetPercent: Int,
    val appListScalePercent: Int,
    val iconSizeDp: Float,
    val appleWatchIconShape: Boolean,
    val fisheyeEnabled: Boolean,
    val fisheyeRangeRows: Int,
    val fisheyeStrengthPercent: Int,
    val honeycombTopFisheyeEnabled: Boolean = false,
    /**
     * 心流月牙的鼓出量百分比（0 = 关掉月牙，退化成规则网格）。
     *
     * 100% = 峰值那几行正好贴住可用区右边界、离中线最远的那几行正好贴住左边界（谁都不越界）；
     * 到 [FLUE_LAYOUT_CRESCENT_BULGE_MAX]% 为止可以更深，代价是上下两端那几行被推出可视区
     * 左侧 —— 圆屏上那块本来就在圆外，行也会随滚动走回中段。换算见
     * [NativeFlowGeometry.MAX_CRESCENT_BULGE_FRACTION]。
     */
    val flowCrescentBulgePercent: Int = 0,
    /** 心流月牙朝右（true）还是朝左。 */
    val flowCrescentToRight: Boolean = true,
    val flowShapeCurve: FlowShapeCurve = FlowShapeCurve.Crescent,
    val edgeSpacingCompressionEnabled: Boolean,
    val topFadeRangeDp: Int,
    val bottomFadeRangeDp: Int,
    val topBlurRadiusDp: Float,
    val bottomBlurRadiusDp: Float,
    val drawEdgeFadeMaskInNativeView: Boolean = true,
    val overlayOpen: Boolean,
    /** Reversible 0..1 progress shared with the overlay panel animation. */
    val overlayProgress: Float = if (overlayOpen) 1f else 0f,
    /**
     * 是否禁止拖动排序。只有文件夹展开时才该为真。
     *
     * 不能复用 [overlayOpen]：那个标志还包含长按弹出的应用详情（actionApp），
     * 而「长按 → 拖动」是排序的唯一入口，用它当闸门会在拖动该开始的那一刻
     * 正好关闭，beginDrag 直接被拒，手指一滑就回落成列表滚动——表现为不管
     * 合成文件夹开关如何，长按只能弹详情、完全拖不动。
     * [overlayOpen] 仍然负责两种覆层共用的内容模糊。
     */
    val dragBlocked: Boolean = false,
    val blurEnabled: Boolean,
    val edgeBlurEnabled: Boolean,
    val suppressHeavyEffects: Boolean,
    val sdkInt: Int,
    val rotaryHapticsEnabled: Boolean,
    val darkMode: Boolean,
    val rowBorderEnabled: Boolean,
    /**
     * 网格布局圆图标底下那块底板要不要画。
     *
     * 这块底板就是 [NativeAppListPalette.rowBackgroundColorArgb]：`NativeAppListRowView.onDraw`
     * 只要看到它的 alpha 非 0，就会把**整个格子**（非线性布局下 rowBounds = 0,0,width,height）
     * 按 `rowBorder.cornerRadiusPx` 填一遍。而网格的格子是「宽高比 0.92 的竖长方形」、图标却是
     * 内切 `min(w,h) - 2*iconInnerPadding` 的圆 —— 于是圆图标底下永远露出一圈方角底板，
     * 正是老师说的「图标是圆形但是底下有个矩形遮罩」。
     *
     * 以前这里没有开关：网格布局是**无条件**吃 `rowBackgroundArgb` 的（`rowBorderEnabled ||
     * presentation == DENSE_GRID`），行边框开关只管线性布局，网格没有任何入口能关掉底板。
     */
    val gridIconPlateEnabled: Boolean = false,
    val allowFolderCreation: Boolean,
    /** 见 [NativeAppListConfig.scrollIndicatorEnabled]。默认开。 */
    val scrollIndicatorEnabled: Boolean = true,
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
    // 滚动指示器要占掉的右侧宽度。开关关掉时为 0（不能留白边）。
    //
    // 这条 inset 让五个布局的可用宽度都缩一点，心流的基准 X 因此自然左移 ——
    // 它的基准被刻意定成「月牙峰值贴住可用区右边界」，所以不减掉指示器就永远压在上面
    // （老师报的「在流心布局开启滚动指示器后，应用列表应该向左移动一点」）。
    val rightSafeInsetPx = nativeScrollIndicatorReservedWidthPx(
        enabled = request.scrollIndicatorEnabled,
        density = density,
    ).roundToInt()
    val honeycombNarrowColumns = request.honeycombNarrowColumns.coerceIn(1, 10)
    // 网格是均匀网格，没有蜂窝那种「窄行 / 宽行」之分，所以列数就是设置里的值，
    // 不能再 +1。以前沿用了蜂窝的 narrow+1 并把下限钳在 2，于是「网格列数」调到
    // 1 时实际渲染出 2 列 —— 设置写 1 却给 2，是老师报的那个对不上。
    //
    // 也**不能再除以 scale**。除一遍的本意是「图标缩小了就多塞几列」，但那会让
    // 一个显式的数字设置被另一个设置悄悄改掉：应用列表缩放调到 50% 时
    // `1 / 0.5 = 2`，「网格列数 1」照样渲染成 2 列 —— 和 narrow+1 那条是同一个
    // 症状的第二个来源。列数是用户直接指定的，缩放只该改图标大小，不该改列数。
    val denseGridColumns = honeycombNarrowColumns.coerceIn(1, 10)
    val denseGridHorizontalPaddingPx = 8f * density
    val denseGridItemSpacingPx = 4f * density
    val denseGridMinimumItemPx = 48f * density
    val denseGridMinimumVerticalPaddingPx = 8f * density
    val denseGridIconInnerPaddingPx = (3f * scale).coerceIn(2f, 6f) * density
    val honeycombIconSizePx = if (
        request.presentation == NativeAppListPresentation.HONEYCOMB ||
        // 心流的格子尺寸算法与蜂窝逐位相同（按可用宽度除列数），
        // 只是列数不再是「窄行 / 宽行」两档。
        request.presentation == NativeAppListPresentation.FLOW
    ) {
        val viewportWidth = request.viewportWidthPx.coerceAtLeast(1)
        val contentWidth = (viewportWidth - leftSafeInsetPx - rightSafeInsetPx).toFloat()
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
    // 星环的尺寸链条与蜂窝 / 心流**反过来**：那两种是先定图标大小、格子跟着图标（×1.02），
    // 星环是先把可用宽度按列数等分成格子（铺满整屏是它的定义），图标再按比例塞进格子里。
    // 沿用蜂窝那条「availableWidth / (maxColumns + 0.35)」会在两侧留下一圈永远填不上的
    // 空白，圆屏上那圈空白正压在弧线最需要图标的地方。
    val ringCellSizePx = if (request.presentation == NativeAppListPresentation.RING) {
        nativeRingCellSizePx(
            viewportWidthPx = request.viewportWidthPx,
            leftSafeInsetPx = leftSafeInsetPx,
            rightSafeInsetPx = rightSafeInsetPx,
            columns = honeycombNarrowColumns,
        )
    } else {
        0f
    }
    val ringIconSizePx = nativeRingIconSizePx(cellSizePx = ringCellSizePx, scale = scale)
    val scaledIconSizeDp = (request.iconSizeDp * scale).coerceIn(24f, 128f)
    val scaledVerticalPaddingDp = (10f * scale).coerceIn(4f, 24f)
    val scaledHorizontalPaddingDp = (16f * scale).coerceIn(10f, 32f)
    val scaledIconTextGapDp = (14f * scale).coerceIn(8f, 28f)
    val scaledTextSizeSp = (16f * scale).coerceIn(10f, 32f)
    val fontScale = request.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val iconSizePx = ringIconSizePx.takeIf { it > 0 }
        ?: honeycombIconSizePx.takeIf { it > 0 }
        ?: (scaledIconSizeDp * density).roundToInt()
        .coerceAtLeast(1)
    val verticalPaddingPx = (scaledVerticalPaddingDp * density).roundToInt().coerceAtLeast(0)
    val horizontalPaddingPx = (scaledHorizontalPaddingDp * density).roundToInt().coerceAtLeast(0)
    val honeycombCellSizePx = if (request.presentation == NativeAppListPresentation.HONEYCOMB) {
        iconSizePx * 1.02f
    } else {
        0f
    }
    val flowCellSizePx = if (request.presentation == NativeAppListPresentation.FLOW) {
        iconSizePx * 1.02f
    } else {
        0f
    }
    // 月牙百分比原样传给几何层，由它换算成像素。
    //
    // 以前在这里按「内容宽度 × 0.75 × 百分比」估一个像素值，几何层再夹到真实余量上 ——
    // 两个上限打架：行宽一大，百分比刚过一半就撞到余量，之后拖到 100% 画面毫无变化
    // （老师报「月牙弧度只能调到 100%」的另一半原因）。现在百分比的含义单一：
    // 100% = 峰值正好贴边，150% = 弧再深一半（上下两端那几行推出可视区）。
    val flowCrescentBulgeFraction = if (request.presentation == NativeAppListPresentation.FLOW) {
        request.flowCrescentBulgePercent
            .coerceIn(0, FLUE_LAYOUT_CRESCENT_BULGE_MAX)
            .toFloat() / 100f
    } else {
        0f
    }
    val rowHeightPx = when (request.presentation) {
        NativeAppListPresentation.HONEYCOMB -> honeycombCellSizePx.roundToInt()
        NativeAppListPresentation.FLOW -> flowCellSizePx.roundToInt()
        // 星环的「行高」就是格边长（正方格），边缘渐变与鱼眼作用带都按它换算。
        NativeAppListPresentation.RING -> ringCellSizePx.roundToInt().coerceAtLeast(1)
        NativeAppListPresentation.DENSE_GRID,
        NativeAppListPresentation.LINEAR -> iconSizePx + verticalPaddingPx * 2
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
    // Folder progress changes every frame. Keep the config itself stable until
    // the RenderEffect radius crosses a cache bucket; otherwise AndroidView's
    // update path reapplies the adapter and every visible child at 60 Hz.
    val contentOverlayBlurBucketPx = if (request.blurEnabled && request.sdkInt >= 31) {
        quantizeNativeBlurRadiusPx(
            16f * density * request.overlayProgress.coerceIn(0f, 1f),
        )
    } else {
        0
    }
    return NativeAppListConfig(
        active = request.active,
        initialResetToken = request.initialResetToken,
        entryAnimationToken = request.entryAnimationToken,
        presentation = request.presentation,
        leftSafeInsetPx = leftSafeInsetPx,
        rightSafeInsetPx = rightSafeInsetPx,
        rowHeightPx = rowHeightPx,
        iconSizePx = iconSizePx,
        rowPaddingStartPx = horizontalPaddingPx + (12f * density).roundToInt(),
        rowPaddingTopPx = verticalPaddingPx,
        rowPaddingEndPx = horizontalPaddingPx + (12f * density).roundToInt(),
        rowPaddingBottomPx = verticalPaddingPx,
        labelTextSizePx = (scaledTextSizeSp * density * fontScale).coerceAtLeast(1f),
        iconLabelGapPx = (scaledIconTextGapDp * density).roundToInt().coerceAtLeast(0),
        // 全部风格统一走圆形（老师指定）：MIUIX / MD3 以前是圆角矩形，
        // 和文件夹里的圆形图标并排看是两套语言。iconCornerRadiusPx 留着
        // 给按压高亮 / 行底色用，图标本体不再读它。
        iconShape = NativeAppListIconShape.CIRCLE,
        iconCornerRadiusPx = if (request.presentation == NativeAppListPresentation.DENSE_GRID) {
            (18f * scale).coerceIn(12f, 24f) * density
        } else {
            (14f * scale).coerceIn(10f, 24f) * density
        },
        honeycombCellSizePx = honeycombCellSizePx,
        honeycombNarrowColumns = honeycombNarrowColumns,
        honeycombFisheyeRangeRows = request.fisheyeRangeRows,
        honeycombTopFisheyeEnabled = request.honeycombTopFisheyeEnabled,
        // 心流沿用同一个「每行列数」设置（密集网格也是这么复用的），等列不加一。
        flowColumns = honeycombNarrowColumns,
        flowCellSizePx = flowCellSizePx,
        flowCrescentBulgeFraction = flowCrescentBulgeFraction,
        flowCrescentDirection = if (request.flowCrescentToRight) {
            FlowCrescentDirection.Right
        } else {
            FlowCrescentDirection.Left
        },
        flowShapeCurve = request.flowShapeCurve.sanitized(),
        // 星环也沿用同一个「每行列数」设置（honeycombNarrowColumns），等列不加一。
        ringCellSizePx = ringCellSizePx,
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
        scrollIndicatorEnabled = request.scrollIndicatorEnabled,
        // 文件夹 / 长按菜单开着时列表不接受起拖。
        dragEnabled = !request.dragBlocked,
        iconShadowEnabled = request.iconShadowEnabled,
        edgeSpacingCompressionEnabled = request.edgeSpacingCompressionEnabled,
        // 与设置项共用同一个开关；默认值为关闭，避免新安装设备悄悄启用甩尾入场动画。
        entryMotionEnabled = request.entryStaggerEnabled,
        entryProgress = request.entryProgress,
        // 0.65：底部行晚约 27% 行程，watchOS 式甩尾但不至于拖沓。
        entryStaggerStrength = if (request.entryStaggerEnabled) 0.65f else 0f,
        palette = NativeAppListPalette(
            // 关掉底板走的是「把这个颜色的 alpha 归零」，而不是往 NativeAppListConfig 里再塞
            // 一个布尔量：渲染侧（NativeAppListRowView.onDraw）判断画不画底板的唯一依据本来
            // 就是这个 alpha，多一个开关就多一个真相来源，两者不同步时会画出半个底板。
            rowBackgroundColorArgb = if (
                request.rowBorderEnabled ||
                (
                    request.presentation == NativeAppListPresentation.DENSE_GRID &&
                        request.gridIconPlateEnabled
                    )
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
        contentOverlayBlurRadiusPx = contentOverlayBlurBucketPx.toFloat(),
        contentOverlayBlurEligible = request.blurEnabled &&
            request.sdkInt >= 31 &&
            contentOverlayBlurBucketPx > 0,
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

/**
 * 心流月牙的鼓出量上限曾经写在这里（占内容宽度的比例，0.40 → 0.75）。
 *
 * 现在删掉了：它和几何层的「夹到可用余量」是**两个互相打架的上限**，行一宽就先撞后者，
 * 于是设置里那条拉杆过半之后毫无变化。百分比现在一路传到 [NativeFlowGeometry]，
 * 由它按余量的倍数换算（100% = 峰值贴边，上限 150%）。
 */

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
