package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppListPerformancePolicyTest {
    @Test
    fun spatialScrollPathDoesNotExposePerFrameGeometryKeyFactories() {
        assertFalse(
            NativeAppGridLayoutManager::class.java.declaredMethods.any {
                it.name == "geometryKey"
            },
        )
        assertFalse(
            NativeHoneycombLayoutManager::class.java.declaredClasses.any {
                it.simpleName == "GeometryKey"
            },
        )
        assertFalse(
            NativeDenseGridLayoutManager::class.java.declaredClasses.any {
                it.simpleName == "GeometryKey"
            },
        )
    }

    @Test
    fun spatialVisibilityLookupDoesNotWalkEveryItemBeforeTheVisibleWindow() {
        val itemCount = 10_000
        val denseBase = NativeDenseGridGeometry.create(
            itemCount = itemCount,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            columns = 5,
            horizontalPaddingPx = 8f,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemHeightPx = 48f,
        )
        val densePlacements = CountingAccessList(denseBase.placements)
        val dense = NativeDenseGridGeometry::class.java.declaredConstructors
            .single { it.parameterCount == 8 }
            .apply { isAccessible = true }
            .newInstance(
                denseBase.viewportHeightPx,
                denseBase.itemWidthPx,
                denseBase.itemHeightPx,
                denseBase.itemSpacingPx,
                denseBase.verticalEdgePaddingPx,
                densePlacements,
                denseBase.minimumScrollOffsetPx,
                denseBase.maximumScrollOffsetPx,
            ) as NativeDenseGridGeometry
        val denseBounds = IntArray(2)

        dense.visiblePositionBounds(
            scrollOffsetPx = dense.minimumScrollOffsetPx,
            extraLayoutSpacePx = dense.itemHeightPx,
            output = denseBounds,
        )

        assertEquals(itemCount - 1, denseBounds[1])
        assertTrue(
            "dense lookup read ${densePlacements.readCount} placements",
            densePlacements.readCount <= 64,
        )

        val honeycombBase = NativeHoneycombGeometry.create(
            itemCount = itemCount,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            narrowColumns = 3,
            iconSizePx = 64f,
        )
        val honeycombPlacements = CountingAccessList(honeycombBase.placements)
        val honeycomb = NativeHoneycombGeometry::class.java.declaredConstructors
            .single { it.parameterCount == 5 }
            .apply { isAccessible = true }
            .newInstance(
                honeycombBase.viewportHeightPx,
                honeycombBase.iconSizePx,
                honeycombPlacements,
                honeycombBase.minimumScrollOffsetPx,
                honeycombBase.maximumScrollOffsetPx,
            ) as NativeHoneycombGeometry
        val honeycombBounds = IntArray(2)

        honeycomb.visiblePositionBounds(
            scrollOffsetPx = honeycomb.minimumScrollOffsetPx,
            extraLayoutSpacePx = honeycomb.iconSizePx,
            output = honeycombBounds,
        )

        assertEquals(itemCount - 1, honeycombBounds[1])
        assertTrue(
            "honeycomb lookup read ${honeycombPlacements.readCount} placements",
            honeycombPlacements.readCount <= 64,
        )
    }

    @Test
    fun spatialVisibilityBoundsMatchLinearOracleAtTopMiddleAndTail() {
        val dense = NativeDenseGridGeometry.create(
            itemCount = 137,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            columns = 5,
            horizontalPaddingPx = 8f,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemHeightPx = 48f,
        )
        val honeycomb = NativeHoneycombGeometry.create(
            itemCount = 137,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            narrowColumns = 3,
            iconSizePx = 64f,
        )

        assertVisibilityBoundsMatchLinearOracle(
            geometry = dense,
            positions = dense.placements.indices,
            offsets = listOf(
                dense.maximumScrollOffsetPx,
                (dense.minimumScrollOffsetPx + dense.maximumScrollOffsetPx) / 2f,
                dense.minimumScrollOffsetPx,
            ),
            extraLayoutSpacePx = dense.itemHeightPx,
        )
        assertVisibilityBoundsMatchLinearOracle(
            geometry = honeycomb,
            positions = honeycomb.placements.indices,
            offsets = listOf(
                honeycomb.maximumScrollOffsetPx,
                (honeycomb.minimumScrollOffsetPx + honeycomb.maximumScrollOffsetPx) / 2f,
                honeycomb.minimumScrollOffsetPx,
            ),
            extraLayoutSpacePx = honeycomb.iconSizePx,
        )
    }

    @Test
    fun onlyLinearLayoutUsesTheRecyclerHostChildVisualPass() {
        assertTrue(usesHostChildVisualPass(LinearNativeAppListLayoutStrategy))
        assertFalse(usesHostChildVisualPass(HoneycombNativeAppListLayoutStrategy))
        assertFalse(usesHostChildVisualPass(DenseGridNativeAppListLayoutStrategy))
    }

    @Test
    fun honeycombVisualDoesNotApplyTheRemovedFastFlowDisplacement() {
        val config = NativeAppListConfig(
            presentation = NativeAppListPresentation.HONEYCOMB,
        )

        val upper = nativeHoneycombChildVisual(200f, 100f, 400f, 400f, config)
        val lower = nativeHoneycombChildVisual(200f, 300f, 400f, 400f, config)

        assertEquals(0f, upper.translationY, 0.001f)
        assertEquals(0f, lower.translationY, 0.001f)
    }

    @Test
    fun interactionScaleKeepsTheLegacyPriorityAndTargets() {
        assertEquals(1f, nativeInteractionScale(false, false, false), 0f)
        assertEquals(0.97f, nativeInteractionScale(true, false, false), 0f)
        assertEquals(1f,    nativeInteractionScale(true, false, true), 0f)  // folderHover 不再缩放
        assertEquals(0.965f, nativeInteractionScale(true, true, true), 0f)
    }

    @Test
    fun honeycombVisualCombinesRadialFisheyeAndEdgeCompression() {
        val config = NativeAppListConfig(
            presentation = NativeAppListPresentation.HONEYCOMB,
            fisheyeEnabled = true,
            fisheyeStrength = 1f,
            honeycombFisheyeRangeRows = 2,
            edgeSpacingCompressionEnabled = true,
        )
        val center = nativeHoneycombChildVisual(160f, 160f, 320f, 320f, config)
        val edge = nativeHoneycombChildVisual(260f, 20f, 320f, 320f, config)

        assertEquals(1f, center.scale, 0f)
        assertEquals(1f, center.alpha, 0f)
        assertTrue(edge.scale < 1f)
        assertTrue(edge.alpha < 1f)
        assertTrue(edge.translationX < 0f)
        // Vertical position is owned by the layout manager. Fisheye only
        // compresses the radial spacing; entry motion is the sole Y transform.
        assertEquals(0f, edge.translationY, 0f)
    }

    @Test
    fun honeycombHotPathReusesTheCallerOwnedVisualResult() {
        val scratch = NativeSpatialChildVisual()

        val result = nativeHoneycombChildVisual(
            centerX = 160f,
            centerY = 24f,
            viewportWidth = 320f,
            viewportHeight = 320f,
            config = NativeAppListConfig(
                presentation = NativeAppListPresentation.HONEYCOMB,
                fisheyeEnabled = true,
            ),
            output = scratch,
        )

        assertSame(scratch, result)
        assertTrue(result.scale < 1f)
    }

    @Test
    fun spatialReorderRadiusKeepsAppleHoneycombBoundedButDenseGridNearest() {
        val honeycomb = NativeAppListConfig(
            presentation = NativeAppListPresentation.HONEYCOMB,
            honeycombCellSizePx = 72f,
        )
        val dense = NativeAppListConfig(
            presentation = NativeAppListPresentation.DENSE_GRID,
        )

        assertEquals(68.4f, nativeSpatialReorderRadiusPx(honeycomb), 0.001f)
        // Unbounded on purpose: distances are translation-inclusive, so the
        // latched target follows the pointer and stays de-duped. A bounded
        // radius regressed drops whose pointer sat between cells.
        assertEquals(Float.MAX_VALUE, nativeSpatialReorderRadiusPx(dense), 0f)
    }

    @Test
    fun denseGridFisheyeStartsOnlyBelowTheLegacyFiftyTwoPercentLine() {
        val config = NativeAppListConfig(
            presentation = NativeAppListPresentation.DENSE_GRID,
            denseGridColumns = 5,
            fisheyeEnabled = true,
            fisheyeStrength = 1f,
            honeycombFisheyeRangeRows = 4,
            edgeSpacingCompressionEnabled = true,
        )

        val upper = nativeDenseGridChildVisual(260f, 160f, 320f, 320f, config)
        val lower = nativeDenseGridChildVisual(260f, 300f, 320f, 320f, config)

        assertEquals(1f, upper.scale, 0f)
        assertEquals(1f, upper.alpha, 0f)
        assertEquals(0f, upper.translationX, 0f)
        assertTrue(lower.scale < 1f)
        assertEquals(lower.scale.coerceIn(0.24f, 1f), lower.alpha, 0f)
        assertTrue(lower.translationX < 0f)
    }

    @Test
    fun denseGridEntryMotionAndHotPathReuseOneSpatialScratch() {
        val scratch = NativeSpatialChildVisual()
        val result = nativeDenseGridChildVisual(
            centerX = 160f,
            centerY = 160f,
            viewportWidth = 320f,
            viewportHeight = 320f,
            config = NativeAppListConfig(
                presentation = NativeAppListPresentation.DENSE_GRID,
                denseGridColumns = 5,
                entryProgress = 0.5f,
            ),
            output = scratch,
        )

        assertSame(scratch, result)
        assertEquals(0f, result.translationX, 0f)
        assertEquals(12.8f, result.translationY, 0.001f)
        assertEquals(0.5f, result.alpha, 0f)
    }

    @Test
    fun classicEntryKeepsNativeChildrenStableWhileNextUsesEntryProgress() {
        val classic = NativeAppListConfig(
            presentation = NativeAppListPresentation.DENSE_GRID,
            denseGridColumns = 5,
            entryMotionEnabled = false,
            entryProgress = 0f,
        )
        val next = classic.copy(entryMotionEnabled = true)

        val classicVisual = nativeDenseGridChildVisual(160f, 160f, 320f, 320f, classic)
        val nextVisual = nativeDenseGridChildVisual(160f, 160f, 320f, 320f, next)

        assertEquals(0f, classicVisual.translationY, 0f)
        assertEquals(1f, classicVisual.alpha, 0f)
        assertTrue(nextVisual.translationY > 0f)
        assertEquals(0f, nextVisual.alpha, 0f)
    }

    @Test
    fun api27To30NeverRunsPerIconConvolutionInTheScrollPath() {
        assertEquals(
            NativeBlurTier.LITE,
            selectNativeBlurTier(
                sdkInt = 30,
                blurEnabled = true,
                edgeBlurEnabled = true,
                suppressHeavyEffects = false,
            ),
        )
    }

    @Test
    fun api31AndAboveUseCachedSystemBlurBuckets() {
        assertEquals(
            NativeBlurTier.SYSTEM,
            selectNativeBlurTier(
                sdkInt = 31,
                blurEnabled = true,
                edgeBlurEnabled = true,
                suppressHeavyEffects = false,
            ),
        )
    }

    @Test
    fun suppressedOrDisabledEffectsForceBlurOff() {
        assertEquals(
            NativeBlurTier.OFF,
            selectNativeBlurTier(
                sdkInt = 35,
                blurEnabled = true,
                edgeBlurEnabled = true,
                suppressHeavyEffects = true,
            ),
        )
        assertEquals(
            NativeBlurTier.OFF,
            selectNativeBlurTier(
                sdkInt = 35,
                blurEnabled = false,
                edgeBlurEnabled = true,
                suppressHeavyEffects = false,
            ),
        )
    }

    @Test
    fun configCarriesTheNativeRuntimePolicyWithoutAndroidFrameworkTypes() {
        val config = NativeAppListConfig(
            active = true,
            leftSafeInsetPx = 12,
            appScale = 0.9f,
            fisheyeEnabled = true,
            fisheyeRange = 0.4f,
            fisheyeStrength = 0.75f,
            blurTier = NativeBlurTier.LITE,
            rotaryHapticsEnabled = false,
            theme = NativeAppListTheme.DARK,
            rowBorder = NativeAppListRowBorder(
                enabled = true,
                colorArgb = 0x44ffffff,
                widthPx = 1f,
                cornerRadiusPx = 8f,
            ),
        )

        assertEquals(12, config.leftSafeInsetPx)
        assertEquals(0.9f, config.appScale, 0f)
        assertFalse(config.rotaryHapticsEnabled)
        assertTrue(config.rowBorder.enabled)
    }

    @Test
    fun configLegalizationRemovesInvalidAndNegativeDrawingMetrics() {
        val legalized = NativeAppListConfig(
            initialResetToken = -9L,
            leftSafeInsetPx = -1,
            rowHeightPx = -10,
            iconSizePx = -20,
            rowPaddingStartPx = -3,
            rowPaddingTopPx = -4,
            rowPaddingEndPx = -5,
            rowPaddingBottomPx = -6,
            labelTextSizePx = Float.NaN,
            iconLabelGapPx = -7,
            appScale = Float.NaN,
            fisheyeRange = Float.POSITIVE_INFINITY,
            fisheyeStrength = -1f,
            rowBorder = NativeAppListRowBorder(
                enabled = true,
                widthPx = Float.NaN,
                cornerRadiusPx = -8f,
            ),
        ).legalized()

        assertEquals(0L, legalized.initialResetToken)
        assertEquals(0, legalized.leftSafeInsetPx)
        assertTrue(legalized.rowHeightPx > 0)
        assertTrue(legalized.iconSizePx > 0)
        assertEquals(0, legalized.rowPaddingStartPx)
        assertEquals(0, legalized.rowPaddingTopPx)
        assertEquals(0, legalized.rowPaddingEndPx)
        assertEquals(0, legalized.rowPaddingBottomPx)
        assertTrue(legalized.labelTextSizePx.isFinite())
        assertTrue(legalized.labelTextSizePx > 0f)
        assertEquals(0, legalized.iconLabelGapPx)
        assertTrue(legalized.appScale.isFinite())
        assertTrue(legalized.appScale > 0f)
        assertTrue(legalized.fisheyeRange in 0.01f..1f)
        assertTrue(legalized.fisheyeStrength in 0f..2f)
        assertEquals(0f, legalized.rowBorder.widthPx, 0f)
        assertEquals(0f, legalized.rowBorder.cornerRadiusPx, 0f)
    }

    @Test
    fun resolvedConfigLegalizesAtTheLowFrequencyBoundary() {
        val resolved = NativeAppListResolvedConfig.from(
            NativeAppListConfig(
                rowHeightPx = -1,
                iconSizePx = -1,
                labelTextSizePx = Float.NaN,
            ),
        )

        assertTrue(resolved.value.rowHeightPx > 0)
        assertTrue(resolved.value.iconSizePx > 0)
        assertTrue(resolved.value.labelTextSizePx.isFinite())
    }

    @Test
    fun visualOnlyAnimationConfigDoesNotInvalidateNativeRowDrawingContent() {
        val baseline = NativeAppListConfig()
        val animated = baseline.copy(
            entryProgress = 0.4f,
            fisheyeStrength = 1.5f,
            topBlurRadiusPx = 8f,
        )

        assertFalse(
            hasDifferentNativeRowRendering(
                baseline,
                animated,
            ),
        )
        assertFalse(hasDifferentNativeEdgeFade(baseline, animated))
        assertFalse(hasDifferentNativeContentOverlayEffect(baseline, animated))
        assertTrue(
            hasDifferentNativeEdgeFade(
                baseline,
                baseline.copy(topFadeRangePx = baseline.topFadeRangePx + 1f),
            ),
        )
        assertTrue(
            hasDifferentNativeContentOverlayEffect(
                baseline,
                baseline.copy(contentOverlayBlurRadiusPx = 16f),
            ),
        )
        assertTrue(
            hasDifferentNativeRowRendering(
                baseline,
                baseline.copy(
                    palette = baseline.palette.copy(labelColorArgb = 0xff112233L),
                ),
            ),
        )
        assertTrue(
            hasDifferentNativeRowRendering(
                baseline,
                baseline.copy(
                    presentation = NativeAppListPresentation.HONEYCOMB,
                    honeycombCellSizePx = 72f,
                ),
            ),
        )
    }

    @Test
    fun nativeChildVisualKeepsTheCenterSharpAndShrinksOnlyAtTheEdge() {
        val config = NativeAppListConfig(
            appScale = 0.9f,
            fisheyeEnabled = true,
            fisheyeRange = 0.4f,
            fisheyeStrength = 1f,
        )

        val center = nativeLinearChildVisual(
            childCenterY = 120f,
            viewportHeight = 240,
            config = config,
        )
        val edge = nativeLinearChildVisual(
            childCenterY = 0f,
            viewportHeight = 240,
            config = config,
        )

        assertEquals(0.9f, center.scale, 0f)
        assertEquals(1f, center.alpha, 0f)
        assertTrue(edge.scale < center.scale)
        assertTrue(edge.alpha < center.alpha)
    }

    @Test
    fun inactiveLinearHostKeepsItsSettledFisheyeWhileTheParentAnimatesAway() {
        val active = NativeAppListConfig(
            active = true,
            fisheyeEnabled = true,
            fisheyeRange = 0.4f,
            fisheyeStrength = 1f,
        )
        val inactive = active.copy(active = false)

        val activeEdge = nativeLinearChildVisual(
            childCenterY = 0f,
            viewportHeight = 240,
            config = active,
        )
        val inactiveEdge = nativeLinearChildVisual(
            childCenterY = 0f,
            viewportHeight = 240,
            config = inactive,
        )

        assertEquals(activeEdge.scale, inactiveEdge.scale, 0f)
        assertEquals(activeEdge.alpha, inactiveEdge.alpha, 0f)
        assertEquals(activeEdge.blurRadiusPx, inactiveEdge.blurRadiusPx, 0f)
    }

    @Test
    fun disabledOrInvalidGeometryUsesOnlyTheConfiguredBaseScale() {
        val inactive = NativeAppListConfig(
            active = false,
            appScale = 0.8f,
            fisheyeEnabled = true,
            fisheyeRange = 1f,
            fisheyeStrength = 1f,
        )

        assertEquals(
            NativeLinearChildVisual(scale = 0.8f, alpha = 1f),
            nativeLinearChildVisual(
                childCenterY = Float.NaN,
                viewportHeight = 0,
                config = inactive,
            ),
        )
    }

    @Test
    fun nativeChildVisualCanReuseOutputInTheScrollHotPath() {
        val output = NativeLinearChildVisual(scale = 0f, alpha = 0f)

        val result = nativeLinearChildVisual(
            childCenterY = 120f,
            viewportHeight = 240,
            config = NativeAppListConfig(fisheyeEnabled = true),
            output = output,
        )

        assertSame(output, result)
        assertEquals(1f, result.scale, 0f)
        assertEquals(1f, result.alpha, 0f)
    }

    @Test
    fun linearEntryMotionIsAppliedInsideNativeVisualPass() {
        val config = NativeAppListConfig(entryMotionEnabled = true).also {
            it.runtimeEntryProgress = 0.5f
        }

        val visual = nativeLinearChildVisual(
            childCenterY = 120f,
            viewportHeight = 240,
            config = config,
        )

        assertEquals(0.95f, visual.scale, 0.0001f)
        assertEquals(0.5f, visual.alpha, 0.0001f)
        assertEquals(9.6f, visual.translationY, 0.0001f)
    }

    @Test
    fun edgeFadeAndBlurAreComputedInTheReusableChildVisual() {
        val config = NativeAppListConfig(
            fisheyeEnabled = false,
            topFadeRangePx = 60f,
            bottomFadeRangePx = 60f,
            topBlurRadiusPx = 8f,
            bottomBlurRadiusPx = 8f,
            blurTier = NativeBlurTier.SYSTEM,
        )

        val center = nativeLinearChildVisual(120f, 240, config)
        val topEdge = nativeLinearChildVisual(0f, 240, config)
        val bottomEdge = nativeLinearChildVisual(240f, 240, config)

        assertEquals(1f, center.alpha, 0f)
        assertEquals(0f, center.blurRadiusPx, 0f)
        assertEquals(1f, topEdge.alpha, 0f)
        assertEquals(8f, topEdge.blurRadiusPx, 0f)
        assertEquals(1f, bottomEdge.alpha, 0f)
        assertEquals(8f, bottomEdge.blurRadiusPx, 0f)

        val liteEdge = nativeLinearChildVisual(
            0f,
            240,
            config.copy(blurTier = NativeBlurTier.LITE),
        )
        assertEquals(0.62f, liteEdge.alpha, 0.0001f)
        assertEquals(0.45f, liteEdge.saturation, 0.0001f)
        assertEquals(1f, center.saturation, 0f)
    }

    @Test
    fun legacySaturationUsesAStableSmallBucketSet() {
        assertEquals(0, quantizeNativeLiteSaturation(1f))
        assertEquals(1, quantizeNativeLiteSaturation(0.82f))
        assertEquals(2, quantizeNativeLiteSaturation(0.64f))
        assertEquals(3, quantizeNativeLiteSaturation(0.45f))
        assertEquals(0, quantizeNativeLiteSaturation(Float.NaN))
    }
}

private fun assertVisibilityBoundsMatchLinearOracle(
    geometry: NativeAppGridGeometry,
    positions: IntRange,
    offsets: List<Float>,
    extraLayoutSpacePx: Float,
) {
    val halfItem = geometry.itemHeightPx / 2f
    val minimumCenterY = -extraLayoutSpacePx - halfItem
    val maximumCenterY = geometry.viewportHeightPx + extraLayoutSpacePx + halfItem
    val actual = IntArray(2)
    offsets.forEach { scrollOffsetPx ->
        val expected = positions.filter { position ->
            geometry.screenCenterY(position, scrollOffsetPx) in
                minimumCenterY..maximumCenterY
        }

        geometry.visiblePositionBounds(
            scrollOffsetPx = scrollOffsetPx,
            extraLayoutSpacePx = extraLayoutSpacePx,
            output = actual,
        )

        assertEquals(expected.firstOrNull() ?: -1, actual[0])
        assertEquals(expected.lastOrNull() ?: -1, actual[1])
    }
}

private class CountingAccessList<T>(
    private val delegate: List<T>,
) : AbstractList<T>() {
    var readCount: Int = 0
        private set

    override val size: Int
        get() = delegate.size

    override fun get(index: Int): T {
        readCount++
        return delegate[index]
    }
}
