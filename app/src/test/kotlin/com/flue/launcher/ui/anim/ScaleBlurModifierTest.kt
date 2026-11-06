package com.flue.launcher.ui.anim

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleBlurModifierTest {
    @Test
    fun legacyOverlayCanAddItsFirstPreparedBlurWithoutReplacingTheClearFace() {
        val session = BackdropFrameSession<Any>()
        org.junit.Assert.assertNull(session.select(true, null, allowLateFrame = true))
        val ready = Any()
        org.junit.Assert.assertSame(ready, session.select(true, ready, allowLateFrame = true))
        org.junit.Assert.assertSame(ready, session.select(true, Any(), allowLateFrame = true))
    }

    @Test
    fun switchingOverlaysRestartsTheSessionSoTheNewPageGetsAFreshSnapshot() {
        val session = BackdropFrameSession<Any>()
        val first = Any()
        val second = Any()

        // 进入第一个覆层：取到 first 并保留整段手势。
        org.junit.Assert.assertSame(first, session.select(true, first))
        org.junit.Assert.assertSame(first, session.select(true, first))

        // 老师报的「快速退出进入切换其他三个页面时不刷新快照」：接连切换中间
        // 没有回到静止态，若此时不 restart()，新覆层会一直拿到 first。
        org.junit.Assert.assertSame(first, session.select(true, second))

        // restart() 之后必须重新取帧。
        session.restart()
        org.junit.Assert.assertSame(second, session.select(true, second))
        org.junit.Assert.assertSame(second, session.select(true, second))
    }

    @Test
    fun legacyOverlayReusesItsCapturedFrameWithoutRenderingTheFaceAgain() {
        assertFalse(shouldDrawLiveBackdropContent(true, true, OverlayBlurBackend.LEGACY))
        assertTrue(shouldDrawLiveBackdropContent(true, false, OverlayBlurBackend.LEGACY))
        assertTrue(shouldDrawLiveBackdropContent(false, true, OverlayBlurBackend.LEGACY))
    }

    @Test
    fun backdropKeepsTheCompleteFrameObjectUntilTheOverlayCloses() {
        val session = BackdropFrameSession<Any>()
        val first = Any()
        val late = Any()
        org.junit.Assert.assertSame(first, session.select(false, first))
        org.junit.Assert.assertSame(first, session.select(true, first))
        org.junit.Assert.assertSame(first, session.select(true, late))
        org.junit.Assert.assertSame(late, session.select(false, late))
        org.junit.Assert.assertSame(late, session.select(true, late))
    }

    @Test
    fun backdropWithoutACompleteFrameKeepsLiveContentForTheWholeGesture() {
        val session = BackdropFrameSession<Any>()
        org.junit.Assert.assertNull(session.select(true, null))
        org.junit.Assert.assertNull(session.select(true, Any()))
        val ready = Any()
        org.junit.Assert.assertSame(ready, session.select(false, ready))
    }

    @Test
    fun resizingAnOpenOverlayDiscardsItsFrameUntilTheNextSession() {
        val session = BackdropFrameSession<IntSize>()
        val original = IntSize(408, 502)
        val resized = IntSize(502, 408)
        assertEquals(original, session.select(true, original) { it == original })
        org.junit.Assert.assertNull(session.select(true, resized) { it == resized })
        org.junit.Assert.assertNull(session.select(true, original) { it == original })
        assertEquals(resized, session.select(false, resized) { it == resized })
    }

    @Test
    fun frozenContentStillSamplesTheCurrentAnimationFrame() {
        val frame = LayerFrameSnapshot()
        var current = LayerAnimValues()
        frame.bind { current }
        current = LayerAnimValues(scale = 0.92f, translationY = 0.03f)
        // No graphicsLayer draw/update occurs while its content is frozen.
        assertEquals(0.92f, frame.scale, 0f)
        assertEquals(0.03f, frame.translationY, 0f)
        assertTrue(frame.backdropProgress() > 0f)
        current = LayerAnimValues()
        assertEquals(0f, frame.backdropProgress(), 0f)
    }

    @Test
    fun preparedBackdropRecordingIsReusedUntilItsVisualInputsChange() {
        val state = PreparedBackdropRecordState()

        assertTrue(
            state.shouldRecord(
                width = 408,
                height = 502,
                frameGeneration = 7L,
                scale = 1.22f,
                translationYPx = 0f,
            ),
        )
        assertFalse(
            state.shouldRecord(
                width = 408,
                height = 502,
                frameGeneration = 7L,
                scale = 1.22f,
                translationYPx = 0f,
            ),
        )
        assertTrue(
            state.shouldRecord(
                width = 408,
                height = 502,
                frameGeneration = 8L,
                scale = 1.22f,
                translationYPx = 0f,
            ),
        )
        // 几何变化必须触发重录：这是历史版本的行为，改成「按固定比例烘一次」
        // 会改变「表盘 ↔ 应用列表」的实际观感（动画很怪、画面跳跃、闪烁）。
        assertTrue(
            state.shouldRecord(
                width = 408,
                height = 502,
                frameGeneration = 8L,
                scale = 1.20f,
                translationYPx = 3f,
            ),
        )
    }

    @Test
    fun liveBackdropContentIsSkippedOnlyWhenOverlayHasCapturedFrame() {
        assertFalse(
            shouldDrawLiveBackdropContent(
                overlayActive = true,
                capturedFrameAvailable = true,
            ),
        )
        assertTrue(
            shouldDrawLiveBackdropContent(
                overlayActive = true,
                capturedFrameAvailable = false,
            ),
        )
        assertTrue(
            shouldDrawLiveBackdropContent(
                overlayActive = false,
                capturedFrameAvailable = true,
            ),
        )
    }

    @Test
    fun hardwareCaptureKeepsThePausedFaceVisibleWhileTheFrozenFrameIsPending() {
        assertTrue(
            shouldDrawLiveBackdropContent(
                overlayActive = true,
                capturedFrameAvailable = false,
            ),
        )
    }

    @Test
    fun frozenOverlayKeepsUsingTheCapturedFrameWhenGeometryProgressReturnsToRest() {
        assertEquals(
            BackdropContentSource.CAPTURED,
            resolveBackdropContentSource(
                overlayActive = true,
                hardwareCaptureRequired = true,
                capturedFrameAvailable = true,
                cachedFrameAvailable = true,
            ),
        )
        assertEquals(
            BackdropContentSource.LIVE,
            resolveBackdropContentSource(
                overlayActive = false,
                hardwareCaptureRequired = false,
                capturedFrameAvailable = true,
                cachedFrameAvailable = true,
            ),
        )
    }

    @Test
    fun frozenBackdropDoesNotHandTheLiveFaceBackBeforeTheGestureEnds() {
        // 老师报的「往回滑、还没松手就闪出实时表盘」：progress 触到 0 但冻结仍在。
        assertTrue(
            shouldHoldFrozenBackdropAtRest(
                freezeLiveContentWhenCaptured = true,
                progress = 0f,
                hardwareBlurAvailable = true,
            ),
        )
        assertTrue(
            shouldHoldFrozenBackdropAtRest(
                freezeLiveContentWhenCaptured = true,
                progress = 0.001f,
                hardwareBlurAvailable = true,
            ),
        )
        // 覆层真正收尾（冻结落回 false）后必须交还实时表盘，否则表盘永远是张旧帧。
        assertFalse(
            shouldHoldFrozenBackdropAtRest(
                freezeLiveContentWhenCaptured = false,
                progress = 0f,
                hardwareBlurAvailable = true,
            ),
        )
        // 已经开始下沉时不走这条捷径，几何推进由既有镜像逻辑处理。
        assertFalse(
            shouldHoldFrozenBackdropAtRest(
                freezeLiveContentWhenCaptured = true,
                progress = 0.5f,
                hardwareBlurAvailable = true,
            ),
        )
        // API 27-30 的背衬中心本来就是活的表盘，不存在硬切，不能跟着冻成静止帧。
        assertFalse(
            shouldHoldFrozenBackdropAtRest(
                freezeLiveContentWhenCaptured = true,
                progress = 0f,
                hardwareBlurAvailable = false,
            ),
        )
    }

    @Test
    fun pixelCopyResultIsRejectedAfterAnOverlayHasStartedDrawing() {
        assertTrue(shouldAcceptWatchFaceCapture(captureAllowedNow = true))
        assertFalse(shouldAcceptWatchFaceCapture(captureAllowedNow = false))
    }

    @Test
    fun idleFrameRefreshRunsOnlyWhereTheFrozenFrameIsVisibleInTheCentre() {
        // API 31+：背衬中心画的就是这张帧，必须定期刷新，否则会显示几十秒前的时间。
        assertTrue(shouldRefreshWatchFaceFrameOnIdle(31))
        assertTrue(shouldRefreshWatchFaceFrameOnIdle(35))
        // API 27-30：中心始终是实时表盘，刷新只会白跑一次 CPU 三遍盒模糊。
        for (api in listOf(27, 28, 29, 30)) {
            assertFalse("API $api 不该在空闲期重烘背衬", shouldRefreshWatchFaceFrameOnIdle(api))
        }
        // 刷新周期必须显著短于「一分钟前的静态帧」这个可感知阈值，且不能太密
        // （每次刷新都会换 generation，进而在后台重烘一次背衬三联帧）。
        assertTrue(LEGACY_BACKDROP_IDLE_REFRESH_INTERVAL_MS in 5_000L..30_000L)
    }

    @Test
    fun disablingAdvancedMaterialNeverSelectsACpuBlurBackend() {
        assertEquals(OverlayBlurBackend.LEGACY, overlayBlurBackend(30, advancedMaterialEnabled = true))
        assertEquals(OverlayBlurBackend.SYSTEM, overlayBlurBackend(31, advancedMaterialEnabled = true))
        for (api in listOf(27, 28, 30, 31, 35)) {
            assertEquals("NONE", overlayBlurBackend(api, advancedMaterialEnabled = false).name)
        }
    }

    @Test
    fun legacyPreparedBlurUsesReducedThreePassPolicy() {
        assertEquals(12, LEGACY_BACKDROP_BLUR_DOWNSAMPLE_DIVISOR)
        assertEquals(3, LEGACY_BACKDROP_BLUR_PASSES)
        assertEquals(6, LEGACY_BACKDROP_BLUR_RADIUS)
    }

    @Test
    fun backdropBlurProgressUsesStableTwoPixelBuckets() {
        assertEquals(0, quantizeBackdropBlurRadiusPx(Float.NaN))
        assertEquals(0, quantizeBackdropBlurRadiusPx(0f))
        assertEquals(2, quantizeBackdropBlurRadiusPx(0.01f))
        assertEquals(16, quantizeBackdropBlurRadiusPx(0.25f))
        assertEquals(32, quantizeBackdropBlurRadiusPx(0.50f))
        assertEquals(64, quantizeBackdropBlurRadiusPx(1f))
        assertEquals(64, quantizeBackdropBlurRadiusPx(2f))
    }

    @Test
    fun legacyBackdropCrossfadesClearAndPreblurredFramesWithoutAlphaJump() {
        assertEquals(LegacyBackdropBlend(1f, 0f), legacyBackdropBlend(Float.NaN))
        assertEquals(LegacyBackdropBlend(1f, 0f), legacyBackdropBlend(0f))
        assertEquals(0.28375f, legacyBackdropBlend(0.25f).blurredAlpha, 0.0001f)
        assertEquals(0.545f, legacyBackdropBlend(0.5f).blurredAlpha, 0.0001f)
        assertEquals(LegacyBackdropBlend(1f, 1f), legacyBackdropBlend(1f))
        assertEquals(LegacyBackdropBlend(1f, 1f), legacyBackdropBlend(2f))

        for (step in 0..20) {
            val blend = legacyBackdropBlend(step / 20f)
            assertEquals(1f, blend.clearAlpha, 0.0001f)
            val p = step / 20f
            val expected = p * (1.18f - 0.18f * p)
            assertEquals(expected, blend.blurredAlpha, 0.0001f)
            if (p in 0.0001f..0.9999f) {
                assertTrue(blend.blurredAlpha > p)
            }
        }
    }

    @Test
    fun legacyBackdropRejectsCacheFromDifferentMeasuredViewport() {
        assertTrue(legacyBackdropViewportMatches(454, 454, IntSize(454, 454)))
        assertFalse(legacyBackdropViewportMatches(454, 454, IntSize(450, 454)))
        assertFalse(legacyBackdropViewportMatches(454, 454, IntSize(454, 450)))
    }

    @Test
    fun explicitCenterAndLaunchIconResolveDeterministically() {
        assertEquals(Offset(0.5f, 0.5f), LayerPivot.Center.resolve())
        assertEquals(
            Offset(0.2f, 0.7f),
            LayerPivot.LaunchIcon(Offset(0.2f, 0.7f)).resolve(),
        )
        assertEquals(Offset(0.5f, 0.5f), LayerPivot.Center.resolve())
    }

    @Test
    fun transientMissingOriginKeepsTheLaunchPivotUntilAnExplicitReset() {
        val latch = LayerPivotLatch()
        val launchOrigin = Offset(0.2f, 0.7f)

        assertEquals(launchOrigin, latch.resolve(LayerPivot.LaunchIcon(launchOrigin)))
        assertEquals(launchOrigin, latch.resolve(null))
        assertEquals(Offset(0.5f, 0.5f), latch.resolve(LayerPivot.Center))
    }

    @Test
    fun invalidLaunchIconOriginFallsBackToCenter() {
        assertEquals(
            Offset(0.5f, 0.5f),
            LayerPivot.LaunchIcon(Offset(Float.NaN, 0.4f)).resolve(),
        )
    }

    @Test
    fun invisibleOrUnsupportedLayersDoNotRetainFullScreenBlur() {
        assertFalse(shouldRenderLayerBlur(31, true, 0f, 8))
        assertFalse(shouldRenderLayerBlur(31, true, 0.01f, 8))
        assertFalse(shouldRenderLayerBlur(31, true, Float.NaN, 8))
        assertFalse(shouldRenderLayerBlur(30, true, 1f, 8))
        assertFalse(shouldRenderLayerBlur(31, false, 1f, 8))
        assertFalse(shouldRenderLayerBlur(31, true, 1f, 0))
        assertTrue(shouldRenderLayerBlur(31, true, 0.011f, 8))
    }

    @Test
    fun backdropFrameActivatesForRealShrinkOrTranslationOnly() {
        val frame = LayerFrameSnapshot()
        frame.update(
            scale = 1f,
            blur = 24f,
            alpha = 0.2f,
            translationX = 0f,
            translationY = 0f,
        )
        assertEquals(0f, frame.backdropProgress(), 0f)

        frame.update(
            scale = 0.925f,
            blur = 12f,
            alpha = 0.5f,
            translationX = 0f,
            translationY = 0.035f,
        )
        assertEquals(0.5f, frame.backdropProgress(), 0.0001f)

        frame.update(
            scale = 0.85f,
            blur = 24f,
            alpha = 0f,
            translationX = 0f,
            translationY = 0.07f,
        )
        assertEquals(1f, frame.backdropProgress(), 0f)
    }
}
