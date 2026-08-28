package com.flue.launcher.ui.drawer

import android.os.Build
import com.flue.launcher.applist.nativeview.NativeAppListTheme
import com.flue.launcher.applist.nativeview.NativeAppListPresentation
import com.flue.launcher.applist.nativeview.NativeBlurTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeListDrawerConfigTest {
    @Test
    fun mapsComposeListGeometryAndSettingsWithoutDoubleScaling() {
        val config = nativeListDrawerConfig(
            request(
                density = 2f,
                viewportWidthPx = 400,
                viewportHeightPx = 800,
                leftSafeInsetPercent = 10,
                appListScalePercent = 125,
                fisheyeRangeRows = 2,
                fisheyeStrengthPercent = 75,
                rowBorderEnabled = true,
            ),
        )

        assertEquals(40, config.leftSafeInsetPx)
        assertEquals(120, config.iconSizePx)
        assertEquals(170, config.rowHeightPx)
        assertEquals(64, config.rowPaddingStartPx)
        assertEquals(25, config.rowPaddingTopPx)
        assertEquals(35, config.iconLabelGapPx)
        assertEquals(1f, config.appScale)
        assertEquals(0.85f, config.fisheyeRange)
        assertEquals(0.75f, config.fisheyeStrength)
        assertTrue(config.centerEdgeItems)
        assertTrue(config.rowBorder.enabled)
        assertEquals(NativeAppListTheme.DARK, config.theme)
    }

    @Test
    fun choosesVersionedBlurTierAndHonorsTheHeavyEffectGate() {
        assertEquals(
            NativeBlurTier.SYSTEM,
            nativeListDrawerConfig(request(sdkInt = Build.VERSION_CODES.S)).blurTier,
        )
        assertEquals(
            NativeBlurTier.LITE,
            nativeListDrawerConfig(request(sdkInt = Build.VERSION_CODES.R)).blurTier,
        )
        val suppressed = nativeListDrawerConfig(
            request(sdkInt = Build.VERSION_CODES.S, suppressHeavyEffects = true),
        )
        assertEquals(NativeBlurTier.OFF, suppressed.blurTier)
        assertFalse(suppressed.rowBorder.enabled)
    }

    @Test
    fun preservesTheFullLegacyFisheyeStrengthRange() {
        val config = nativeListDrawerConfig(request(fisheyeStrengthPercent = 175))

        assertEquals(1.75f, config.fisheyeStrength)
    }

    @Test
    fun preservesLegacyMinimumGeometryAndUsesSystemFontScale() {
        val config = nativeListDrawerConfig(
            request(density = 2f, fontScale = 1.25f, appListScalePercent = 50),
        )

        assertEquals(48, config.iconSizePx)
        assertEquals(68, config.rowHeightPx)
        assertEquals(44, config.rowPaddingStartPx)
        assertEquals(10, config.rowPaddingTopPx)
        assertEquals(16, config.iconLabelGapPx)
        assertEquals(25f, config.labelTextSizePx)
    }

    @Test
    fun actionOverlayRequestsTheLegacySixteenDpContentBlur() {
        val config = nativeListDrawerConfig(
            request(density = 2f, overlayOpen = true),
        )

        assertEquals(32f, config.contentOverlayBlurRadiusPx)
    }

    @Test
    fun folderOverlayBlurRadiusTracksReversibleTransitionProgress() {
        val opening = nativeListDrawerConfig(
            request(density = 2f, overlayOpen = true, overlayProgress = 0.25f),
        )
        val closing = nativeListDrawerConfig(
            request(density = 2f, overlayOpen = true, overlayProgress = 0.75f),
        )

        assertEquals(8f, opening.contentOverlayBlurRadiusPx)
        assertEquals(24f, closing.contentOverlayBlurRadiusPx)
    }

    @Test
    fun folderOverlayBlurKeepsConfigStableInsideOneRenderEffectBucket() {
        val first = nativeListDrawerConfig(
            request(density = 2f, overlayOpen = true, overlayProgress = 0.26f),
        )
        val second = nativeListDrawerConfig(
            request(density = 2f, overlayOpen = true, overlayProgress = 0.30f),
        )

        assertEquals(8f, first.contentOverlayBlurRadiusPx)
        assertEquals(first, second)
    }

    @Test
    fun legacyDevicesDoNotReconfigureForUnsupportedOverlayBlurProgress() {
        val first = nativeListDrawerConfig(
            request(
                density = 2f,
                sdkInt = Build.VERSION_CODES.R,
                overlayOpen = true,
                overlayProgress = 0.20f,
            ),
        )
        val second = nativeListDrawerConfig(
            request(
                density = 2f,
                sdkInt = Build.VERSION_CODES.R,
                overlayOpen = true,
                overlayProgress = 0.80f,
            ),
        )

        assertEquals(0f, first.contentOverlayBlurRadiusPx)
        assertEquals(first, second)
    }

    @Test
    fun carriesEveryLegacyBehaviorSwitchIntoTheNativeConfig() {
        val config = nativeListDrawerConfig(
            request(
                allowFolderCreation = false,
                iconShadowEnabled = false,
                edgeSpacingCompressionEnabled = false,
                entryProgress = 0.35f,
            ),
        )

        assertFalse(config.allowFolderCreation)
        assertFalse(config.iconShadowEnabled)
        assertFalse(config.edgeSpacingCompressionEnabled)
        assertEquals(0.35f, config.entryProgress)
    }

    /**
     * 滑动进度指示器默认开（老师指定），并且对**每一种**列表样式都下发。
     *
     * 指示器读的是 RecyclerView 的滚动量，跟布局怎么排无关，所以配置映射里不该出现
     * 任何按 presentation 分支的写法 —— 这条枚举遍历就是把这件事钉住：以后新增布局
     * 样式时，如果有人在这条链路上加了 `when (presentation)` 分支并漏掉新样式，
     * 这个测试会直接失败。
     */
    @Test
    fun scrollIndicatorDefaultsOnForEveryPresentation() {
        for (presentation in NativeAppListPresentation.entries) {
            assertTrue(
                "指示器在 $presentation 下必须默认开",
                nativeListDrawerConfig(request(presentation = presentation))
                    .scrollIndicatorEnabled,
            )
            assertFalse(
                "指示器在 $presentation 下必须能被关掉",
                nativeListDrawerConfig(
                    request(presentation = presentation, scrollIndicatorEnabled = false),
                ).scrollIndicatorEnabled,
            )
        }
    }

    @Test
    fun mapsAppleHoneycombColumnsAndCellGeometryWithoutLinearPaddingRules() {
        val config = nativeListDrawerConfig(
            request(
                viewportWidthPx = 320,
                viewportHeightPx = 320,
                presentation = NativeAppListPresentation.HONEYCOMB,
                honeycombNarrowColumns = 3,
            ),
        )

        assertEquals(NativeAppListPresentation.HONEYCOMB, config.presentation)
        // 指示器默认开启后会从可用宽度让出右侧占位，图标尺寸随之缩小。
        assertEquals(66, config.iconSizePx)
        assertEquals(3, config.honeycombNarrowColumns)
        assertEquals(66f * 1.02f, config.honeycombCellSizePx, 0.001f)
    }

    @Test
    fun preservesLegacyHoneycombMinimumContentWidthForLargeSafeInsets() {
        val config = nativeListDrawerConfig(
            request(
                viewportWidthPx = 800,
                leftSafeInsetPercent = 50,
                presentation = NativeAppListPresentation.HONEYCOMB,
                honeycombNarrowColumns = 4,
            ),
        )

        assertEquals(79, config.iconSizePx)
    }

    @Test
    fun legalizesHoneycombColumnsBeforeUsingAndPublishingThem() {
        val config = nativeListDrawerConfig(
            request(
                viewportWidthPx = 320,
                presentation = NativeAppListPresentation.HONEYCOMB,
                honeycombNarrowColumns = 0,
            ),
        )

        assertEquals(1, config.honeycombNarrowColumns)
        assertEquals(84, config.iconSizePx)
    }

    /**
     * 「网格列数」调到 1 就必须真的是 1 列。
     *
     * 这条盯的是老师报的「蜂窝列数调成一列网格，实际上还有两列」：网格曾经沿用
     * 蜂窝的 narrow+1 并把下限钳在 2，设置值 1 于是变成 2 列。均匀网格没有
     * 窄行/宽行的区分，列数就该等于设置值。
     */
    @Test
    fun denseGridHonoursASingleColumnSetting() {
        val config = nativeListDrawerConfig(
            request(
                presentation = NativeAppListPresentation.DENSE_GRID,
                honeycombNarrowColumns = 1,
            ),
        )

        assertEquals(1, config.denseGridColumns)
    }

    @Test
    fun mapsLegacyMaterialDenseGridGeometry() {
        val config = nativeListDrawerConfig(
            request(
                density = 2f,
                viewportWidthPx = 640,
                viewportHeightPx = 640,
                appListScalePercent = 200,
                presentation = NativeAppListPresentation.DENSE_GRID,
                honeycombNarrowColumns = 4,
                appleWatchIconShape = false,
            ),
        )

        // 网格列数**就是设置值本身**，不做任何换算。
        //
        // 这里原来断言的是 `(narrow / scale)` 的结果（narrow=4、scale=2.0 → 2）。除以
        // scale 的本意是「图标缩小了就多塞几列」，但那让一个显式的数字设置被另一个设置
        // 悄悄改掉：缩放调到 50% 时「网格列数 1」会渲染成 2 列 —— 和更早那条 narrow+1
        // 是同一症状的第二个来源（老师报的「网格列数调成 1 列实际还有两列」）。列数由
        // 用户直接指定，缩放只该改图标大小。所以 narrow=4 就是 4，与 scale 无关。
        assertEquals(4, config.denseGridColumns)
        assertEquals(16f, config.denseGridHorizontalPaddingPx, 0f)
        assertEquals(8f, config.denseGridItemSpacingPx, 0f)
        assertEquals(0.92f, config.denseGridItemAspectRatio, 0f)
        assertEquals(96f, config.denseGridMinimumItemWidthPx, 0f)
        assertEquals(96f, config.denseGridMinimumItemHeightPx, 0f)
        assertEquals(16f, config.denseGridMinimumVerticalPaddingPx, 0f)
        assertEquals(12f, config.denseGridIconInnerPaddingPx, 0f)
    }

    /**
     * 网格布局圆图标底下那块底板，是 `palette.rowBackgroundColorArgb` 铺满整个格子画出来的
     * （`NativeAppListRowView.onDraw` 只看这个颜色的 alpha 决定画不画）。
     *
     * 这条盯的是老师报的「网格布局图标是圆形但是底下有个矩形遮罩」：以前网格是**无条件**
     * 吃 `rowBackgroundArgb` 的，行边框开关只管线性布局，网格没有任何入口能关掉它。
     */
    @Test
    fun denseGridIconPlateFollowsItsOwnSwitch() {
        val withPlate = nativeListDrawerConfig(
            request(
                presentation = NativeAppListPresentation.DENSE_GRID,
                gridIconPlateEnabled = true,
            ),
        )
        val withoutPlate = nativeListDrawerConfig(
            request(
                presentation = NativeAppListPresentation.DENSE_GRID,
                gridIconPlateEnabled = false,
            ),
        )

        assertEquals(0xff112233L, withPlate.palette.rowBackgroundColorArgb)
        assertEquals(0L, withoutPlate.palette.rowBackgroundColorArgb)
    }

    /**
     * 关掉底板不能顺手把「行底色」这条老路一起关掉：线性布局的底色由
     * `rowBorderEnabled` 管，两个开关各管一种布局。
     */
    @Test
    fun linearRowBackgroundIgnoresTheGridIconPlateSwitch() {
        val config = nativeListDrawerConfig(
            request(rowBorderEnabled = true, gridIconPlateEnabled = false),
        )

        assertEquals(0xff112233L, config.palette.rowBackgroundColorArgb)
    }

    /** 其余布局（蜂窝 / 心流 / 线性无边框）本来就没有底板，开关开着也不该凭空长出来。 */
    @Test
    fun honeycombNeverGetsAnIconPlate() {
        val config = nativeListDrawerConfig(
            request(
                presentation = NativeAppListPresentation.HONEYCOMB,
                gridIconPlateEnabled = true,
            ),
        )

        assertEquals(0L, config.palette.rowBackgroundColorArgb)
    }

    /** 占位图标的底色跟底板开关无关：它画在图标裁形里面，不是格子背景。 */
    @Test
    fun fallbackIconBackgroundSurvivesADisabledIconPlate() {
        val config = nativeListDrawerConfig(
            request(
                presentation = NativeAppListPresentation.DENSE_GRID,
                gridIconPlateEnabled = false,
            ),
        )

        assertEquals(0xff112233L, config.palette.fallbackIconBackgroundColorArgb)
    }

    private fun request(
        density: Float = 1f,
        fontScale: Float = 1f,
        viewportWidthPx: Int = 400,
        viewportHeightPx: Int = 400,
        leftSafeInsetPercent: Int = 0,
        appListScalePercent: Int = 100,
        fisheyeRangeRows: Int = 4,
        fisheyeStrengthPercent: Int = 100,
        sdkInt: Int = Build.VERSION_CODES.S,
        suppressHeavyEffects: Boolean = false,
        overlayOpen: Boolean = false,
        overlayProgress: Float = if (overlayOpen) 1f else 0f,
        rowBorderEnabled: Boolean = false,
        gridIconPlateEnabled: Boolean = true,
        allowFolderCreation: Boolean = true,
        scrollIndicatorEnabled: Boolean = true,
        iconShadowEnabled: Boolean = true,
        edgeSpacingCompressionEnabled: Boolean = true,
        entryProgress: Float = 1f,
        presentation: NativeAppListPresentation = NativeAppListPresentation.LINEAR,
        honeycombNarrowColumns: Int = 4,
        appleWatchIconShape: Boolean = true,
    ) = NativeListDrawerConfigRequest(
        density = density,
        fontScale = fontScale,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        presentation = presentation,
        honeycombNarrowColumns = honeycombNarrowColumns,
        active = true,
        initialResetToken = 3L,
        leftSafeInsetPercent = leftSafeInsetPercent,
        appListScalePercent = appListScalePercent,
        iconSizeDp = 48f,
        appleWatchIconShape = appleWatchIconShape,
        fisheyeEnabled = true,
        fisheyeRangeRows = fisheyeRangeRows,
        fisheyeStrengthPercent = fisheyeStrengthPercent,
        edgeSpacingCompressionEnabled = edgeSpacingCompressionEnabled,
        topFadeRangeDp = 56,
        bottomFadeRangeDp = 56,
        topBlurRadiusDp = 4f,
        bottomBlurRadiusDp = 4f,
        overlayOpen = overlayOpen,
        overlayProgress = overlayProgress,
        blurEnabled = true,
        edgeBlurEnabled = true,
        suppressHeavyEffects = suppressHeavyEffects,
        sdkInt = sdkInt,
        rotaryHapticsEnabled = true,
        darkMode = true,
        rowBorderEnabled = rowBorderEnabled,
        gridIconPlateEnabled = gridIconPlateEnabled,
        allowFolderCreation = allowFolderCreation,
        scrollIndicatorEnabled = scrollIndicatorEnabled,
        iconShadowEnabled = iconShadowEnabled,
        entryProgress = entryProgress,
        rowBackgroundArgb = 0xff112233L,
        labelArgb = 0xffffffffL,
        pressedArgb = 0x22ffffffL,
        fadeEdgeArgb = 0xff000000L,
    )
}
