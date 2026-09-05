package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕右侧滑动进度指示器的几何。
 *
 * 指示器本体画在 `NativeAppListView.dispatchDraw` 里，那是绘制期回调、单测碰不到，
 * 所以「滚动量 → 轨道上的一段」整段都抽成了纯函数，这里逐条锁住。
 */
class NativeScrollIndicatorGeometryTest {

    @Test
    fun mapsScrollOffsetToProgressAndViewportFraction() {
        val state = nativeScrollIndicatorState(
            scrollOffsetPx = 200,
            scrollRangePx = 1000,
            viewportHeightPx = 400,
        )

        assertTrue(state.visible)
        // 可滚动行程 = 1000 - 400 = 600，滚了 200 → 1/3。
        assertEquals(1f / 3f, state.progress, 0.0001f)
        assertEquals(0.4f, state.visibleFraction, 0.0001f)
    }

    /**
     * 内容不比视口长时必须判不可见。
     *
     * 这条盯的是「一条永远满格的杠」：`range <= extent` 时滑块长度算出来就是整条轨道，
     * 画出来是个不动的装饰，纯噪声。
     */
    @Test
    fun hidesItselfWhenContentFitsTheViewport() {
        assertFalse(
            nativeScrollIndicatorState(
                scrollOffsetPx = 0,
                scrollRangePx = 400,
                viewportHeightPx = 400,
            ).visible,
        )
        assertFalse(
            nativeScrollIndicatorState(
                scrollOffsetPx = 0,
                scrollRangePx = 120,
                viewportHeightPx = 400,
            ).visible,
        )
    }

    /** 视口高度或内容长度还没测出来时（首帧 / 空列表）也不该画。 */
    @Test
    fun hidesItselfBeforeTheViewportHasBeenMeasured() {
        assertFalse(
            nativeScrollIndicatorState(
                scrollOffsetPx = 0,
                scrollRangePx = 1000,
                viewportHeightPx = 0,
            ).visible,
        )
        assertFalse(
            nativeScrollIndicatorState(
                scrollOffsetPx = 0,
                scrollRangePx = 0,
                viewportHeightPx = 400,
            ).visible,
        )
    }

    /** RecyclerView 越界回弹时偏移量会超出行程（甚至为负），进度必须夹在 0..1。 */
    @Test
    fun clampsProgressAcrossOverscroll() {
        assertEquals(
            0f,
            nativeScrollIndicatorState(-90, 1000, 400).progress,
            0f,
        )
        assertEquals(
            1f,
            nativeScrollIndicatorState(5000, 1000, 400).progress,
            0f,
        )
    }

    /**
     * 端点契约：progress 0 时滑块**起点**贴轨道起点，progress 1 时滑块**终点**贴轨道终点。
     *
     * 行程是「轨道长 − 滑块长」而不是轨道长 —— 后者会让列表滚到底时滑块整段跑出轨道尾端。
     */
    @Test
    fun anchorsBothEndsOfTheTrack() {
        val track = NativeScrollIndicatorSpan(start = 10f, extent = 100f)

        val atTop = nativeScrollIndicatorThumbSpan(
            track = track,
            visibleFraction = 0.25f,
            progress = 0f,
            minimumExtent = 0f,
        )
        val atBottom = nativeScrollIndicatorThumbSpan(
            track = track,
            visibleFraction = 0.25f,
            progress = 1f,
            minimumExtent = 0f,
        )

        assertEquals(10f, atTop.start, 0.0001f)
        assertEquals(25f, atTop.extent, 0.0001f)
        assertEquals(110f, atBottom.end, 0.0001f)
        assertEquals(25f, atBottom.extent, 0.0001f)
    }

    @Test
    fun placesTheThumbHalfwayAtHalfProgress() {
        val thumb = nativeScrollIndicatorThumbSpan(
            track = NativeScrollIndicatorSpan(start = 0f, extent = 200f),
            visibleFraction = 0.5f,
            progress = 0.5f,
            minimumExtent = 0f,
        )

        // 滑块 100 长，行程 100，走到一半 → 起点 50。
        assertEquals(50f, thumb.start, 0.0001f)
        assertEquals(100f, thumb.extent, 0.0001f)
    }

    /** 应用极多时按比例算出的滑块只有几个像素 / 一两度，圆头描边画出来是个点。 */
    @Test
    fun enforcesAMinimumThumbLengthWithoutLeavingTheTrack() {
        val track = NativeScrollIndicatorSpan(start = 0f, extent = 60f)
        val thumb = nativeScrollIndicatorThumbSpan(
            track = track,
            visibleFraction = 0.005f,
            progress = 1f,
            minimumExtent = 7f,
        )

        assertEquals(7f, thumb.extent, 0.0001f)
        assertEquals(60f, thumb.end, 0.0001f)
    }

    /** 最短长度比轨道还长（畸形视口）时，滑块最多占满轨道，不能溢出。 */
    @Test
    fun neverLetsTheMinimumOvershootTheTrack() {
        val thumb = nativeScrollIndicatorThumbSpan(
            track = NativeScrollIndicatorSpan(start = 4f, extent = 5f),
            visibleFraction = 0.1f,
            progress = 1f,
            minimumExtent = 40f,
        )

        assertEquals(5f, thumb.extent, 0.0001f)
        assertEquals(4f, thumb.start, 0.0001f)
    }

    /** 非有限输入不能把 NaN 传进 Canvas —— 那会让整帧丢掉，表现为列表突然不画了。 */
    @Test
    fun survivesNonFiniteInput() {
        val thumb = nativeScrollIndicatorThumbSpan(
            track = NativeScrollIndicatorSpan(start = 0f, extent = Float.NaN),
            visibleFraction = Float.NaN,
            progress = Float.POSITIVE_INFINITY,
            minimumExtent = Float.NaN,
        )

        assertEquals(0f, thumb.start, 0f)
        assertEquals(0f, thumb.extent, 0f)
    }

    /**
     * 圆屏轨道以 3 点方向为中点对称张开。
     *
     * `drawArc` 的 0° = 3 点方向、正角顺时针（屏幕上向下），所以起始角取 `-sweep/2`
     * 才能让弧的中点落在正右方；「进度增大 → 起始角增大 → 滑块下移」也因此与列表往下滚同向。
     */
    @Test
    fun centersTheArcTrackOnTheThreeOClockDirection() {
        val track = nativeScrollIndicatorArcTrack(60f)

        assertEquals(-30f, track.start, 0f)
        assertEquals(60f, track.extent, 0f)
        assertEquals(30f, track.end, 0f)
        // 中点必须正好是 0°（正右方）。
        assertEquals(0f, track.start + track.extent / 2f, 0.0001f)
    }

    @Test
    fun clampsTheArcSweepToOneFullTurn() {
        assertEquals(360f, nativeScrollIndicatorArcTrack(720f).extent, 0f)
        assertEquals(0f, nativeScrollIndicatorArcTrack(-5f).extent, 0f)
    }

    /**
     * 圆屏半径按屏幕**内切圆**取，再减边距和半个描边宽。
     *
     * 减半个描边宽这一步是必须的：STROKE 以路径为中心向两侧各扩一半，只减边距的话
     * 描边外沿会吃掉留白甚至越过圆边缘。
     */
    @Test
    fun keepsTheArcStrokeInsideTheInscribedCircle() {
        val radius = nativeScrollIndicatorArcRadiusPx(
            viewportWidthPx = 400f,
            viewportHeightPx = 400f,
            edgeMarginPx = 8f,
            thicknessPx = 6f,
        )

        assertEquals(200f - 8f - 3f, radius, 0.0001f)
    }

    /** 非正方视口按短边定内切圆，与列表把图标收进圆里用的是同一个圆。 */
    @Test
    fun takesTheInscribedCircleFromTheShorterSide() {
        assertEquals(
            150f,
            nativeScrollIndicatorArcRadiusPx(
                viewportWidthPx = 400f,
                viewportHeightPx = 300f,
                edgeMarginPx = 0f,
                thicknessPx = 0f,
            ),
            0.0001f,
        )
    }

    @Test
    fun collapsesTheArcRadiusInsteadOfGoingNegative() {
        assertEquals(
            0f,
            nativeScrollIndicatorArcRadiusPx(
                viewportWidthPx = 20f,
                viewportHeightPx = 20f,
                edgeMarginPx = 40f,
                thicknessPx = 6f,
            ),
            0f,
        )
    }

    /**
     * 方屏是圆屏半径趋于无穷的退化情形：弧拉直成竖线，角度换成像素，
     * [nativeScrollIndicatorThumbSpan] 那套映射一字不改地共用。
     */
    @Test
    fun degradesToAVerticalBarOnRectangularScreens() {
        val track = nativeScrollIndicatorBarTrack(
            viewportHeightPx = 400f,
            verticalInsetFraction = 0.12f,
        )

        assertEquals(48f, track.start, 0.0001f)
        assertEquals(304f, track.extent, 0.0001f)
        assertEquals(352f, track.end, 0.0001f)
    }

    /** 留白比例夹在 0..0.49，避免两端留白相加把轨道压成负数。 */
    @Test
    fun clampsTheBarInsetSoTheTrackNeverInverts() {
        val track = nativeScrollIndicatorBarTrack(
            viewportHeightPx = 400f,
            verticalInsetFraction = 0.9f,
        )

        assertTrue(track.extent >= 0f)
        assertEquals(196f, track.start, 0.0001f)
        assertEquals(8f, track.extent, 0.0001f)
    }

    @Test
    fun keepsTheBarStrokeInsideTheRightEdge() {
        val centerX = nativeScrollIndicatorBarCenterXPx(
            viewportWidthPx = 320f,
            edgeMarginPx = 8f,
            thicknessPx = 6f,
        )

        assertEquals(320f - 8f - 3f, centerX, 0.0001f)
    }

    /**
     * 圆屏弧必须落在与列表内容同一个内切圆之内。
     *
     * 圆屏列表的行是按**视口宽度**为直径的那个圆做弦宽收缩的（见 `NativeRoundChordCurve`），
     * 指示器如果用了另一个半径，就会出现「图标沿一条弧、指示器沿另一条弧」这种两套圆屏适配。
     * 这条把两者的半径关系钉死：指示器只能在那个圆**之内**，且 3 点方向的最右点不越过留白。
     */
    @Test
    fun keepsTheArcInsideTheSameCircleTheRowsShrinkAgainst() {
        val viewport = 400f
        val margin = 6f
        val thickness = 4f
        val radius = nativeScrollIndicatorArcRadiusPx(
            viewportWidthPx = viewport,
            viewportHeightPx = viewport,
            edgeMarginPx = margin,
            thicknessPx = thickness,
        )

        // 行做弦宽收缩用的圆半径就是 viewportWidth / 2。
        assertTrue(radius < viewport / 2f)
        // 3 点方向（弧的中点）描边外沿刚好停在留白线上，不越过。
        assertEquals(viewport - margin, viewport / 2f + radius + thickness / 2f, 0.0001f)
    }

    /**
     * 圆屏 60° 与方屏 12% 留白这两个常数各自的含义。
     *
     * 60° 的弦长是 `2R·sin(30°) = R`，正好半个屏幕高 —— 手算这一档是为了确认它是按
     * 「弧要占多少屏高」定的，而不是随手取的整数；哪天有人把它改大，这条会提醒他弧的两端
     * 会压向 12 点 / 6 点方向，那两处离右边缘已经很远，看起来不再是「右侧的条」。
     */
    @Test
    fun sizesTheArcSweepToHalfTheScreenHeight() {
        val radius = 200f
        val halfSweepRad =
            Math.toRadians((NATIVE_SCROLL_INDICATOR_ARC_SWEEP_DEG / 2f).toDouble())
        val chord = 2f * radius * kotlin.math.sin(halfSweepRad).toFloat()

        assertEquals(radius, chord, 0.001f)
        // 方屏那侧刻意更长（76% 屏高）：直线没有「两端拐到正上正下」的问题。
        assertEquals(
            0.76f,
            1f - NATIVE_SCROLL_INDICATOR_BAR_INSET_FRACTION * 2f,
            0.0001f,
        )
    }

    /**
     * 滑块最短长度必须大于描边粗细。
     *
     * 方屏那条线的两端各往内收半个线宽（圆头描边会各鼓出半个线宽），滑块一旦短于线宽，
     * 收完之后两个端点就交叉了 —— `drawLine` 照样画，画出来是个比轨道还宽的圆点，
     * 表现为「滚到底时滑块鼓出轨道」。圆屏那侧同理：7° 的弧长在任何手表半径上都远大于 3dp。
     */
    @Test
    fun keepsTheMinimumThumbLongerThanTheStrokeIsThick() {
        assertTrue(
            NATIVE_SCROLL_INDICATOR_MIN_THUMB_DP > NATIVE_SCROLL_INDICATOR_THICKNESS_DP,
        )
        // 最短滑块也不能长到把轨道占满 —— 那就退化成「一条永远满格的杠」。
        assertTrue(
            NATIVE_SCROLL_INDICATOR_MIN_THUMB_DEG < NATIVE_SCROLL_INDICATOR_ARC_SWEEP_DEG,
        )
    }

    /** 轨道只是给滑块一个参照，不该跟内容抢注意力：它必须比滑块淡得多。 */
    @Test
    fun keepsTheTrackFainterThanTheThumb() {
        assertTrue(NATIVE_SCROLL_INDICATOR_TRACK_ALPHA < NATIVE_SCROLL_INDICATOR_THUMB_ALPHA)
        assertTrue(NATIVE_SCROLL_INDICATOR_TRACK_ALPHA > 0f)
        assertTrue(NATIVE_SCROLL_INDICATOR_THUMB_ALPHA <= 1f)
    }

    /**
     * 整条行程上滑块都不出轨道，且起点随进度单调不减。
     *
     * 单点采样（0 / 0.5 / 1）挡不住「中段算错但两端凑巧对」的实现；这里连续扫一遍，
     * 顺便把「进度增大 → 滑块下移」这个方向性也锁住 —— 方向反了在圆屏上尤其难发现，
     * 因为弧是对称的。
     */
    @Test
    fun containsTheThumbAndMovesForwardAcrossTheWholeTravel() {
        val tracks = listOf(
            nativeScrollIndicatorArcTrack(NATIVE_SCROLL_INDICATOR_ARC_SWEEP_DEG),
            nativeScrollIndicatorBarTrack(400f, NATIVE_SCROLL_INDICATOR_BAR_INSET_FRACTION),
        )
        for (track in tracks) {
            var previousStart = Float.NEGATIVE_INFINITY
            for (step in 0..40) {
                val thumb = nativeScrollIndicatorThumbSpan(
                    track = track,
                    visibleFraction = 0.18f,
                    progress = step / 40f,
                    minimumExtent = 0f,
                )
                assertTrue(thumb.start >= track.start - 0.0001f)
                assertTrue(thumb.end <= track.end + 0.0001f)
                assertTrue(thumb.start >= previousStart)
                previousStart = thumb.start
            }
        }
    }

    /**
     * 五种布局样式报出来的滚动量都能正确映射。
     *
     * 指示器这条链路上**没有** `when (presentation)`：四个 LayoutManager 都把
     * `computeVerticalScrollOffset/Range/Extent` 重写成了精确的布局空间值（线性一份、
     * 网格基类一份，蜂窝 / 心流 / 星环继承网格基类那份），所以这里用各布局典型的三元组
     * 直接喂同一个映射。这条测的是「同一份映射对每种样式都成立」，而不是某个布局的几何 ——
     * 新增布局样式不需要改这里，改这里说明有人在链路上加了分支。
     */
    @Test
    fun mapsEveryLayoutStyleScrollRangeThroughTheSameFunction() {
        // (说明, 内容总长, 视口高) —— 列表行少、密集网格与星环行多，蜂窝 / 心流居中。
        val layoutRanges = listOf(
            Triple("列表", 2400, 400),
            Triple("密集网格", 1600, 400),
            Triple("蜂窝", 1200, 400),
            Triple("心流", 1100, 400),
            Triple("星环", 3200, 400),
        )
        for ((label, range, extent) in layoutRanges) {
            val top = nativeScrollIndicatorState(0, range, extent)
            val bottom = nativeScrollIndicatorState(range - extent, range, extent)

            assertTrue("$label 下内容比视口长，必须可见", top.visible)
            assertEquals("$label 顶端进度必须是 0", 0f, top.progress, 0f)
            assertEquals("$label 底端进度必须是 1", 1f, bottom.progress, 0f)
            assertEquals(
                "$label 的滑块长度必须是视口占比",
                extent.toFloat() / range,
                top.visibleFraction,
                0.0001f,
            )

            val arc = nativeScrollIndicatorArcTrack(NATIVE_SCROLL_INDICATOR_ARC_SWEEP_DEG)
            val arcThumb = nativeScrollIndicatorThumbSpan(
                track = arc,
                visibleFraction = bottom.visibleFraction,
                progress = bottom.progress,
                minimumExtent = NATIVE_SCROLL_INDICATOR_MIN_THUMB_DEG,
            )
            assertEquals("$label 滚到底时滑块尾端必须贴轨道尾端", arc.end, arcThumb.end, 0.0001f)
        }
    }

    /**
     * 应用少到不足一屏时，每种布局都必须不画。
     *
     * 蜂窝 / 星环这类铺满屏幕的样式尤其容易踩：格子少的时候内容根本不到一屏，
     * 画出来就是一条永远满格的杠。
     */
    @Test
    fun hidesItselfOnEveryLayoutStyleWhenTheContentFits() {
        for (range in listOf(120, 320, 400)) {
            assertFalse(
                "内容 $range 不长于视口 400 时不该画",
                nativeScrollIndicatorState(0, range, 400).visible,
            )
        }
    }

    /**
     * 换主题色要刷笔触，换布局样式不用。
     *
     * 判据单列一条的意义就在后半句：它一旦返回 true，宿主会走 `updateScrollIndicatorPaints`；
     * 如果把 `presentation` 也算进来，每次切布局都会白刷一遍笔触。而
     * `palette.labelColorArgb` 必须算进来 —— 漏了就会出现「主题已经切成浅色、指示器还是白的」。
     */
    @Test
    fun refreshesThePaintsOnlyForThingsThePaintsDependOn() {
        val base = NativeAppListConfig()

        assertTrue(
            hasDifferentNativeScrollIndicator(base, base.copy(scrollIndicatorEnabled = false)),
        )
        // 默认是方屏，所以这里翻到圆屏才算「换了投影方式」（弧 ↔ 直线）。
        assertTrue(
            hasDifferentNativeScrollIndicator(
                base,
                base.copy(deviceShape = NativeDeviceShape.ROUND),
            ),
        )
        assertTrue(
            hasDifferentNativeScrollIndicator(
                base,
                base.copy(palette = base.palette.copy(labelColorArgb = 0xff000000L)),
            ),
        )
        assertFalse(hasDifferentNativeScrollIndicator(base, base))
        for (presentation in NativeAppListPresentation.entries) {
            assertFalse(
                "切到 $presentation 不该重刷指示器笔触",
                hasDifferentNativeScrollIndicator(base, base.copy(presentation = presentation)),
            )
        }
    }

    /** 滚动指示器默认开启；允许创建文件夹默认关闭。 */
    @Test
    fun defaultsIndicatorOnAndFolderMergingOff() {
        val defaults = NativeAppListConfig()

        assertTrue(defaults.scrollIndicatorEnabled)
        assertFalse(defaults.allowFolderCreation)
    }

    /**
     * 圆屏与方屏在同一份滚动状态下必须给出同一个**相对**位置。
     *
     * 两条路只在最后一步投影上不同（角度 → 弧 / 像素 → 线段），映射本身共用一份，
     * 所以不可能漂移成两种手感。这条就是把「共用」这件事钉住。
     */
    @Test
    fun roundAndRectangularAgreeOnTheRelativePosition() {
        val state = nativeScrollIndicatorState(
            scrollOffsetPx = 300,
            scrollRangePx = 1200,
            viewportHeightPx = 400,
        )
        val arc = nativeScrollIndicatorArcTrack(60f)
        val bar = nativeScrollIndicatorBarTrack(400f, 0.12f)

        val arcThumb = nativeScrollIndicatorThumbSpan(arc, state.visibleFraction, state.progress, 0f)
        val barThumb = nativeScrollIndicatorThumbSpan(bar, state.visibleFraction, state.progress, 0f)

        fun relativeStart(track: NativeScrollIndicatorSpan, thumb: NativeScrollIndicatorSpan) =
            (thumb.start - track.start) / (track.extent - thumb.extent)

        assertEquals(
            relativeStart(arc, arcThumb),
            relativeStart(bar, barThumb),
            0.0001f,
        )
        assertEquals(
            arcThumb.extent / arc.extent,
            barThumb.extent / bar.extent,
            0.0001f,
        )
    }
}
