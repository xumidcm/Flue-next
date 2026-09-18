package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar
import java.util.Locale

class WatchmakerRuntimeContractsTest {
    @Test
    fun conditionalSpriteDecodeKeepsEnoughPixelsForEachGridCell() {
        // Tilt_Interactive uses a 15x15 sheet with a 512px authored cell. The
        // whole texture must not be reduced to the ordinary 768px editor cap.
        assertEquals(
            4_096,
            watchSpriteDecodeDimensionPx(
                targetWidth = 512f,
                targetHeight = 512f,
                columns = 15,
                rows = 15,
            ),
        )
        assertEquals(
            1_024,
            watchSpriteDecodeDimensionPx(
                targetWidth = 512f,
                targetHeight = 512f,
                columns = 2,
                rows = 2,
            ),
        )
        assertEquals(
            null,
            watchSpriteDecodeDimensionPx(
                targetWidth = null,
                targetHeight = null,
                columns = 15,
                rows = 15,
            ),
        )
    }

    @Test
    fun fontIndexResolvesFullPathBasenameStemAndMissingExtension() =
        withTemporaryDirectory { root ->
            val font = File(root, "fonts/ChironGoRoundTC-Bold.ttf").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(SFNT_HEADER + ByteArray(12))
            }

            val index = WatchFontIndex.build(root, listOf(font))

            assertEquals(font, index.resolve("fonts/ChironGoRoundTC-Bold.ttf"))
            assertEquals(font, index.resolve("ChironGoRoundTC-Bold.ttf"))
            assertEquals(font, index.resolve("ChironGoRoundTC-Bold"))
            assertEquals(font, index.resolve("fonts/ChironGoRoundTC-Bold"))
        }

    @Test
    fun fontIndexRejectsFilesWithoutAnSfntHeader() = withTemporaryDirectory { root ->
        val fake = File(root, "fonts/not-a-font.ttf").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("not a font")
        }

        val index = WatchFontIndex.build(root, listOf(fake))

        assertNull(index.resolve("not-a-font"))
        assertTrue(index.rejectedFiles.single().endsWith("fonts/not-a-font.ttf"))
    }

    @Test
    fun coordinateAndTextSizeConversionUseOneBoundedContract() {
        assertEquals(-128f, WatchmakerGeometry.coordinate("-128", 0f))
        assertEquals(18f, WatchmakerGeometry.textSize("18", 24f))
        assertEquals(1f, WatchmakerGeometry.textSize("-99", 24f))
        assertEquals(512f, WatchmakerGeometry.textSize("9999", 24f))
        assertEquals(7f, WatchmakerGeometry.coordinate("not-a-number", 7f))
    }

    @Test
    fun watchMakerTtfScaleAndCurvedTextDirectionsMatchTheRuntimeContract() {
        assertEquals(128f, WatchTextLayout.ttfTextSize(100f), 0.0001f)
        assertEquals(
            WatchCurveArc(startDegrees = 180f, sweepDegrees = 180f),
            WatchTextLayout.curveArc("Up"),
        )
        assertEquals(
            WatchCurveArc(startDegrees = 180f, sweepDegrees = -180f),
            WatchTextLayout.curveArc("Down"),
        )
    }

    @Test
    fun curvedTextUsesPerGlyphSixtyDegreeSlots() {
        val slots = WatchTextLayout.curvedGlyphSlots(
            glyphWidths = listOf(10f, 20f, 30f),
            isSpace = listOf(false, false, false),
            fontPx = 40f,
            radius = 100f,
        )

        assertEquals(listOf(6f, 12f, 18f), slots)
        assertEquals((10f + 20f + 30f) * 60f / 100f, slots.sum(), 0.0001f)
    }

    @Test
    fun curvedTextSpacesUseThirtyPercentOfTheFontSize() {
        val slots = watchCurvedTextSlots(
            glyphWidths = listOf(10f, 999f, 30f),
            isSpace = listOf(false, true, false),
            fontPx = 40f,
            radius = 100f,
        )

        assertEquals(12f * 60f / 100f, slots[1], 0.0001f)
    }

    @Test
    fun editorNewlinesProduceSeparateCenteredTextBaselines() {
        assertEquals(listOf("First", "Second"), WatchTextLayout.lines("First\nSecond"))
        assertEquals(
            listOf(-8f, 12f),
            WatchTextLayout.baselines(
                lineCount = 2,
                ascent = -8f,
                descent = 4f,
                lineHeight = 20f,
                verticalAlignment = "c",
            ),
        )
    }

    @Test
    fun watchMakerExportedWhitespaceRunRestoresTheAuthoredLineBreak() {
        val exported =
            "\"Rome wasn't built in a day,                                    " +
                "but they didn't have Claude code or codex\" XD"

        assertEquals(
            listOf(
                "\"Rome wasn't built in a day,",
                "but they didn't have Claude code or codex\" XD",
            ),
            WatchTextLayout.lines(exported),
        )
        assertEquals(
            listOf("keep", "ordinary  spacing"),
            WatchTextLayout.lines("keep\nordinary  spacing"),
        )
    }

    @Test
    fun multiLineTextUsesTheFirstLineAsTheSharedLeftAlignedAnchor() {
        assertEquals(
            listOf(-24f, -24f),
            WatchTextLayout.lineStartXs(
                lineWidths = listOf(48f, 120f),
                horizontalAlignment = "cc",
            ),
        )
        assertEquals(
            listOf(0f, 0f),
            WatchTextLayout.lineStartXs(
                lineWidths = listOf(48f, 120f),
                horizontalAlignment = "cl",
            ),
        )
        assertEquals(
            listOf(-48f, -48f),
            WatchTextLayout.lineStartXs(
                lineWidths = listOf(48f, 120f),
                horizontalAlignment = "cr",
            ),
        )
    }

    @Test
    fun hexadecimalColorLiteralsAreNotEvaluatedAsScientificNotation() {
        assertEquals("#0066e6", normalizeWatchColorLiteral("0066e6"))
        assertEquals("#ff0066e6", normalizeWatchColorLiteral("#ff0066e6"))
        assertNull(normalizeWatchColorLiteral("var_T_col(0, var_TP)"))
        assertNull(normalizeWatchColorLiteral("blue"))
    }

    @Test
    fun cloxMixedExpressionPrefixConcatenatesWithStressAndChargingTemplates() {
        val values = mapOf("ssc" to "8543", "sstr" to "35", "bc" to "0")
        val resolver: (String) -> WatchTagResolution = { tag ->
            values[tag]?.let(WatchTagResolution::Resolved) ?: WatchTagResolution.Unsupported(tag)
        }
        assertEquals(
            "8543 35",
            WatchmakerExpressionEvaluator.evaluateText(
                "((((({ssc}~=false)) and ({ssc}) or (0)))) {sstr}",
                resolver,
            ),
        )
        val unavailableSteps: (String) -> WatchTagResolution = { tag ->
            when (tag) {
                "ssc" -> WatchTagResolution.Unavailable(tag, expressionFallback = "0")
                "sstr" -> WatchTagResolution.Resolved("0")
                else -> WatchTagResolution.Unsupported(tag)
            }
        }
        assertEquals(
            "0 0",
            WatchmakerExpressionEvaluator.evaluateText(
                "((((({ssc}~=false)) and ({ssc}) or (0)))) {sstr}",
                unavailableSteps,
            ),
        )
        assertEquals("Battery 0", WatchmakerExpressionEvaluator.evaluateText("Battery {bc}", resolver))
        assertEquals(
            "off",
            WatchmakerExpressionEvaluator.evaluateText(
                "({bc} == 0) and 'off' or 'on'",
                resolver,
            ),
        )
    }

    @Test
    fun chargingStateUsesWatchMakerTextWhileNumericAliasesStayExpressionSafe() {
        val calendar = Calendar.getInstance()
        assertEquals("0", WatchmakerTagResolver.resolve("sstr", calendar, WatchDataSnapshot()).renderedValue)
        assertEquals(
            "discharging",
            WatchmakerTagResolver.resolve("bc", calendar, WatchDataSnapshot()).renderedValue.lowercase(),
        )
        assertEquals(
            "charging",
            WatchmakerTagResolver.resolve(
                "bc",
                calendar,
                WatchDataSnapshot(isCharging = true),
            ).renderedValue.lowercase(),
        )
        assertEquals("0", WatchmakerTagResolver.resolve("charging", calendar, WatchDataSnapshot()).renderedValue)
        assertEquals(
            "1",
            WatchmakerTagResolver.resolve(
                "is_charging",
                calendar,
                WatchDataSnapshot(isCharging = true),
            ).renderedValue,
        )
    }

    @Test
    fun textRingUsesAuthoredRangeFilteringAndOneBasedAngles() {
        assertEquals("1-12", watchTextRingType("1-12"))
        assertEquals("1-60", watchTextRingType(" 1-60 "))
        assertEquals("1-12", watchTextRingType(null))

        val coarse = watchTextRingItems(
            ringType = "1-60",
            showEvery = 5,
            hiddenValues = emptySet(),
            startDegrees = 0f,
            endDegrees = 360f,
        )
        assertEquals((5..60 step 5).map(Int::toString), coarse.map(WatchTextRingItem::label))
        assertEquals(30f, coarse.first().angleDegrees, 0.0001f)
        assertEquals(360f, coarse.last().angleDegrees, 0.0001f)

        val precise = watchTextRingItems(
            ringType = "1-12",
            showEvery = 1,
            hiddenValues = setOf(1, 12),
            startDegrees = 0f,
            endDegrees = 360f,
        )
        assertEquals((2..11).map(Int::toString), precise.map(WatchTextRingItem::label))
        assertEquals(60f, precise.first().angleDegrees, 0.0001f)

        assertEquals(
            listOf("I", "II", "III", "IIII", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"),
            watchTextRingLabels("I-XII"),
        )
    }

    @Test
    fun markerCountKeepsAuthoredPrecisionWithinTheRuntimeSafetyBound() {
        assertEquals(60, watchMarkerCount(60f))
        assertEquals(600, watchMarkerCount(600f))
        assertEquals(1_200, watchMarkerCount(50_000f))
    }

    @Test
    fun studioImageRingUsesBoundedPercentageAndAuthoredSweep() {
        assertEquals(90f, watchRingImageSweep(25f, 360f), 0.0001f)
        assertEquals(180f, watchRingImageSweep(50f, 360f), 0.0001f)
        assertEquals(270f, watchRingImageSweep(75f, 360f), 0.0001f)
        assertEquals(120f, watchRingImageSweep(100f, 120f), 0.0001f)
        assertEquals(0f, watchRingImageSweep(-10f, 360f), 0.0001f)
        assertEquals(360f, watchRingImageSweep(140f, 360f), 0.0001f)
    }

    @Test
    fun structuredStudioLayersUseBundledAssetNamesOnly() {
        assertEquals(listOf("sunarc_classic.webp"), watchStructuredAssetCandidates("sunarc", "classic"))
        assertEquals(listOf("daylight_map2.webp"), watchStructuredAssetCandidates("daylight", "map2"))
        assertEquals(
            listOf(
                "progress_arrow1.webp",
                "progress_arrow1.png",
                "shapes/progress_arrow1.webp",
                "shapes/progress_arrow1.png",
            ),
            watchStructuredAssetCandidates("progress_image", "arrow1"),
        )
        assertTrue(watchStructuredAssetCandidates("horizon", "classic").isEmpty())
    }

    @Test
    fun linearRasterOnlyForAnisotropicScaleComposedFromCanvasAncestorsAndLayer() {
        // 等比缩放（含画布基变换的非 1 缩放）一律不触发：312 的 181 个 text 图层
        // 全靠字形缓存，误开 LINEAR_TEXT_FLAG 会让它们每帧重新栅格化、拖慢整帧。
        assertFalse(watchTextNeedsLinearRaster(1f, 1f))
        assertFalse(watchTextNeedsLinearRaster(0.625f, 0.625f))
        assertFalse(watchTextNeedsLinearRaster(1.28f, 1.28f))

        // wf528 的五个手写体图层：anim_scale_x 80/85/92/95，画布基变换等比 → 合成
        // 后两轴不等，必须开。
        assertTrue(watchTextNeedsLinearRaster(0.8f, 1f))
        assertTrue(watchTextNeedsLinearRaster(0.85f, 1f))
        assertTrue(watchTextNeedsLinearRaster(0.92f, 1f))
        assertTrue(watchTextNeedsLinearRaster(0.95f, 1f))

        // 画布基变换本身非等比（STRETCH / zoomX≠zoomY）× 图层等比，合成后同样不等。
        assertTrue(watchTextNeedsLinearRaster(0.7f, 0.9f))
        assertTrue(watchTextNeedsLinearRaster(1.2f, 0.8f))

        // 镜像缩放不改变笔画粗细，比绝对值。
        assertFalse(watchTextNeedsLinearRaster(-1f, -1f))
        assertTrue(watchTextNeedsLinearRaster(-0.8f, 1f))

        // 两轴同时放大/缩小但保持比例 → 仍不算非等比。
        assertFalse(watchTextNeedsLinearRaster(0.5f, 0.5f))
        assertFalse(watchTextNeedsLinearRaster(2f, 2f))
    }

    @Test
    fun linearRasterToleratesFloatingPointRatiosButNotRealAnisotropy() {
        // 画布变换算出来的比例带浮点尾数：1.2800001 与 1.28 的差值远小于 1% 容差。
        assertFalse(watchTextNeedsLinearRaster(1.2800001f, 1.28f))
        assertFalse(watchTextNeedsLinearRaster(1.28f, 1.2800001f))

        // 1% 容差按较大轴算：1f 与 0.99f 差 1% 整，应判为等比（不开）。
        assertFalse(watchTextNeedsLinearRaster(1f, 0.99f))

        // 实打实的非等比：0.985 与 1f 差 1.5% > 1% 容差，应开。
        assertTrue(watchTextNeedsLinearRaster(0.985f, 1f))

        // 边界保护：零 / 负数轴（不可渲染）与 NaN/无穷不参与判定。
        assertFalse(watchTextNeedsLinearRaster(0f, 0f))
        assertFalse(watchTextNeedsLinearRaster(1f, 0f))
        assertFalse(watchTextNeedsLinearRaster(Float.NaN, 1f))
        assertFalse(watchTextNeedsLinearRaster(Float.POSITIVE_INFINITY, 1f))
    }

    @Test
    fun bitmapFontsUseTheOnePointSixVisibleHeightRule() {
        // clox `wf_render.lua:1940` / wftools `WmPreview.kt:1501`:
        //   scale = 1.6 * text_size / fontH,  fontH = info.size ?: lineHeight
        // 「墨水电子表」f2: text_size=88, nominal=lineHeight=124
        assertEquals(1.6f * 88f / 124f, watchBitmapGlyphScale(88f, 124, 124), 0.0001f)
        // 103 号字体（f1/f5）与 f6 同样按 info.size 取值。
        assertEquals(1.6f * 42f / 41f, watchBitmapGlyphScale(42f, 41, 41), 0.0001f)
        assertEquals(1.6f * 46f / 46f, watchBitmapGlyphScale(46f, 46, 46), 0.0001f)
    }

    @Test
    fun bitmapFontScaleFallsBackToLineHeightThenToTheBareFactor() {
        // info.size 缺失时退回 common.lineHeight（wftools 三处实现的优先级）。
        assertEquals(1.6f * 30f / 60f, watchBitmapGlyphScale(30f, 0, 60), 0.0001f)
        // 两者都拿不到时不除零，退回纯 1.6 倍。
        assertEquals(1.6f, watchBitmapGlyphScale(30f, 0, 0), 0.0001f)
        assertEquals(1.6f, watchBitmapGlyphScale(30f, -1, -1), 0.0001f)
    }

    @Test
    fun bitmapFontScaleClampsTheDesignSizeIntoTheCanvas() {
        // 越界/非有限的 text_size 被夹进 1..512，避免负字号与无穷放大。
        assertEquals(1.6f * 512f / 100f, watchBitmapGlyphScale(9_999f, 100, 100), 0.0001f)
        assertEquals(1.6f * 1f / 100f, watchBitmapGlyphScale(-5f, 100, 100), 0.0001f)
        assertEquals(1.6f * 1f / 100f, watchBitmapGlyphScale(Float.NaN, 100, 100), 0.0001f)
    }

    @Test
    fun hscBitmapFontsUseTheConvertedLayerHeightWithoutChangingRegularPackages() {
        // HSC conversion sample: original text_size is still 60/128/122 while
        // height has already been converted into the 512 design coordinate space.
        assertEquals(86f, watchBitmapTextDesignSize(60f, 86f, 45f, hscGenerated = true))
        assertEquals(183f, watchBitmapTextDesignSize(128f, 183f, 180f, hscGenerated = true))
        assertEquals(180f, watchBitmapTextDesignSize(122f, 174f, 180f, hscGenerated = true))
        assertEquals(60f, watchBitmapTextDesignSize(60f, 86f, 45f, hscGenerated = false))
        assertEquals(60f, watchBitmapTextDesignSize(60f, null, null, hscGenerated = true))
    }

    @Test
    fun canvasTransformFitsOrCenterCropsWithoutChangingTheAnchor() {
        val fit = watchCanvasTransform(
            viewWidth = 320f,
            viewHeight = 400f,
            forceFill = false,
        )
        assertEquals(0.625f, fit.scaleX, 0.0001f)
        assertEquals(0.625f, fit.scaleY, 0.0001f)
        assertEquals(0f, fit.left, 0.0001f)
        assertEquals(40f, fit.top, 0.0001f)

        val fill = watchCanvasTransform(
            viewWidth = 320f,
            viewHeight = 400f,
            forceFill = true,
        )
        assertEquals(0.78125f, fill.scaleX, 0.0001f)
        assertEquals(0.78125f, fill.scaleY, 0.0001f)
        assertEquals(-40f, fill.left, 0.0001f)
        assertEquals(0f, fill.top, 0.0001f)
    }

    @Test
    fun canvasTransformKeepsUniformScaleForNonSquareDesignExtents() {
        // 320×385 声明 → 设计空间 512×616。视口按同比例给（500×601.6）时
        // 两轴 scale 相等，没有 letterbox 边。
        val matched = watchCanvasTransform(
            viewWidth = 500f,
            viewHeight = 601.5625f,
            forceFill = false,
            designWidth = 512f,
            designHeight = 616f,
        )
        assertEquals(500f / 512f, matched.scaleX, 0.0001f)
        assertEquals(500f / 512f, matched.scaleY, 0.0001f)
        assertEquals(0f, matched.left, 0.01f)
        assertEquals(0f, matched.top, 0.01f)

        // 视口偏方时按短边等比装入，长边留居中黑边——FIT 下两轴 scale 必须相等，
        // 绝不做各轴不同的拉伸（那是 STRETCH 的职责）。
        val letterboxed = watchCanvasTransform(
            viewWidth = 600f,
            viewHeight = 400f,
            forceFill = false,
            designWidth = 512f,
            designHeight = 616f,
        )
        assertEquals(400f / 616f, letterboxed.scaleX, 0.0001f)
        assertEquals(400f / 616f, letterboxed.scaleY, 0.0001f)
        assertEquals((600f - 512f * (400f / 616f)) / 2f, letterboxed.left, 0.01f)
        assertEquals(0f, letterboxed.top, 0.01f)
    }

    /**
     * 可调画布上线后**存量表盘位置一个像素都不许动**。
     *
     * 这条比单独验各预设更重要：它钉住「不传新参数 = 旧行为」。默认值哪天被人
     * 顺手改一下，这里立刻红。
     */
    @Test
    fun defaultCanvasSettingsMatchTheLegacyForceFillOffPathBitForBit() {
        listOf(320f to 400f, 400f to 320f, 454f to 454f).forEach { (viewWidth, viewHeight) ->
            val legacy = watchCanvasTransform(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                forceFill = false,
            )
            val viaSettings = watchCanvasTransform(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                settings = WatchCanvasSettings.DEFAULT,
            )
            // 逐位相同，不给容差 —— 这不是数值近似问题，是同一条代码路径。
            assertEquals(legacy, viaSettings)

            val legacyFill = watchCanvasTransform(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                forceFill = true,
            )
            val viaFillSettings = watchCanvasTransform(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                settings = WatchCanvasSettings.fromForceFill(true),
            )
            assertEquals(legacyFill, viaFillSettings)
        }
    }

    @Test
    fun canvasPresetsPickTheirDeclaredBaseScale() {
        // 320×400 视口、512² 设计空间：fitX = 0.625、fitY = 0.78125，两轴不等，
        // 六个预设的差别才显现得出来。
        fun transformFor(preset: WatchCanvasPreset) = watchCanvasTransform(
            viewWidth = 320f,
            viewHeight = 400f,
            settings = WatchCanvasSettings(preset = preset),
        )

        transformFor(WatchCanvasPreset.FIT).let {
            assertEquals(0.625f, it.scaleX, 0.0001f)
            assertEquals(0.625f, it.scaleY, 0.0001f)
            assertEquals(0f, it.left, 0.0001f)
            assertEquals(40f, it.top, 0.0001f)
        }
        transformFor(WatchCanvasPreset.FILL).let {
            assertEquals(0.78125f, it.scaleX, 0.0001f)
            assertEquals(0.78125f, it.scaleY, 0.0001f)
            assertEquals(-40f, it.left, 0.0001f)
            assertEquals(0f, it.top, 0.0001f)
        }
        // 竖直贴合按高度：这个视口下与 FILL 同值，但语义不同（视口更宽时会分岔）。
        transformFor(WatchCanvasPreset.VERTICAL).let {
            assertEquals(0.78125f, it.scaleX, 0.0001f)
            assertEquals(0.78125f, it.scaleY, 0.0001f)
        }
        transformFor(WatchCanvasPreset.HORIZONTAL).let {
            assertEquals(0.625f, it.scaleX, 0.0001f)
            assertEquals(0.625f, it.scaleY, 0.0001f)
        }
        // 唯一允许两轴不等的预设，且两个方向都正好铺满、不留边。
        transformFor(WatchCanvasPreset.STRETCH).let {
            assertEquals(0.625f, it.scaleX, 0.0001f)
            assertEquals(0.78125f, it.scaleY, 0.0001f)
            assertEquals(0f, it.left, 0.0001f)
            assertEquals(0f, it.top, 0.0001f)
        }
        // CUSTOM 基准恒为 1：设计空间比视口大，所以居中后 left/top 为负。
        transformFor(WatchCanvasPreset.CUSTOM).let {
            assertEquals(1f, it.scaleX, 0.0001f)
            assertEquals(1f, it.scaleY, 0.0001f)
            assertEquals(-96f, it.left, 0.0001f)
            assertEquals(-56f, it.top, 0.0001f)
        }
    }

    @Test
    fun canvasPresetsDivergeWhenTheViewportIsWiderThanTheDesign() {
        // 600×400 视口、512² 设计：fitX = 1.171875 > fitY = 0.78125。
        // VERTICAL 取高度、HORIZONTAL 取宽度，此时两者与 FIT/FILL 的对应关系翻转。
        fun scaleFor(preset: WatchCanvasPreset) = watchCanvasTransform(
            viewWidth = 600f,
            viewHeight = 400f,
            settings = WatchCanvasSettings(preset = preset),
        ).scaleX

        assertEquals(0.78125f, scaleFor(WatchCanvasPreset.FIT), 0.0001f)
        assertEquals(1.171875f, scaleFor(WatchCanvasPreset.FILL), 0.0001f)
        // 竖直贴合此刻等于 FIT，水平贴合等于 FILL —— 与上一条测试恰好相反，
        // 这就是「方屏上四预设部分等价」不能当成实现正确的证据的原因。
        assertEquals(0.78125f, scaleFor(WatchCanvasPreset.VERTICAL), 0.0001f)
        assertEquals(1.171875f, scaleFor(WatchCanvasPreset.HORIZONTAL), 0.0001f)
    }

    @Test
    fun canvasZoomAndOffsetApplyOnTopOfThePresetBaseline() {
        val zoomed = watchCanvasTransform(
            viewWidth = 320f,
            viewHeight = 400f,
            settings = WatchCanvasSettings(
                preset = WatchCanvasPreset.FIT,
                zoomX = 2f,
                zoomY = 1f,
            ),
        )
        // zoom 只乘自己那一轴，另一轴保持基准。
        assertEquals(1.25f, zoomed.scaleX, 0.0001f)
        assertEquals(0.625f, zoomed.scaleY, 0.0001f)
        // 放大后仍以视口中心为锚点：left 跟着缩放重算，不是原样保留。
        assertEquals((320f - 512f * 1.25f) / 2f, zoomed.left, 0.0001f)
        assertEquals(40f, zoomed.top, 0.0001f)

        val shifted = watchCanvasTransform(
            viewWidth = 320f,
            viewHeight = 400f,
            settings = WatchCanvasSettings(offsetX = 12f, offsetY = -8f),
        )
        assertEquals(12f, shifted.left, 0.0001f)
        assertEquals(32f, shifted.top, 0.0001f)
    }

    /**
     * 脏配置不能把画布弄没。
     *
     * zoom 取 0 会让整块表盘塌成一个点、偏移取无穷会把它推出屏幕，两种情况下
     * 用户都点不中「重置」——所以兜底是可用性问题，不只是数值卫生。
     */
    @Test
    fun canvasTransformClampsHostileZoomAndOffsetValues() {
        fun transformWith(settings: WatchCanvasSettings) = watchCanvasTransform(
            viewWidth = 320f,
            viewHeight = 400f,
            settings = settings,
        )

        // 0 与负数被夹到下限 0.01，画布仍然可见、可交互。
        assertEquals(
            0.625f * 0.01f,
            transformWith(WatchCanvasSettings(zoomX = 0f)).scaleX,
            0.0001f,
        )
        assertEquals(
            0.625f * 0.01f,
            transformWith(WatchCanvasSettings(zoomX = -3f)).scaleX,
            0.0001f,
        )
        // 上限 20，防止一个滑到底的滑杆把位图放大到 OOM。区间刻意比滑块量程宽，
        // 因为手输框只校验 isFinite()（见 canvasInputValueOrNull）。
        assertEquals(
            0.625f * 20f,
            transformWith(WatchCanvasSettings(zoomX = 100f)).scaleX,
            0.0001f,
        )
        // 手输能给到的中间值不该被削：8 在滑块量程外但在渲染区间内，原样通过。
        assertEquals(
            0.625f * 8f,
            transformWith(WatchCanvasSettings(zoomX = 8f)).scaleX,
            0.0001f,
        )
        // NaN / 无穷回落到中性值，而不是继续污染整条矩阵。
        assertEquals(
            0.625f,
            transformWith(WatchCanvasSettings(zoomY = Float.NaN)).scaleY,
            0.0001f,
        )
        assertEquals(
            0.625f,
            transformWith(WatchCanvasSettings(zoomY = Float.POSITIVE_INFINITY)).scaleY,
            0.0001f,
        )
        assertEquals(
            0f,
            transformWith(WatchCanvasSettings(offsetX = Float.NaN)).left,
            0.0001f,
        )
        assertEquals(
            16_384f,
            transformWith(WatchCanvasSettings(offsetX = 1_000_000f)).left,
            0.0001f,
        )
        // 负方向同样夹住：40 是 FIT 在这个视口下的居中留边，兜底只削偏移那一项。
        assertEquals(
            40f - 16_384f,
            transformWith(WatchCanvasSettings(offsetY = -1_000_000f)).top,
            0.0001f,
        )
    }

    @Test
    fun canvasSettingsCodecRoundTripsAndSurvivesGarbage() {
        val settings = WatchCanvasSettings(
            preset = WatchCanvasPreset.STRETCH,
            zoomX = 1.5f,
            zoomY = 0.5f,
            offsetX = 12f,
            offsetY = -8f,
        )
        assertEquals(settings, WatchCanvasSettings.decode(settings.encode()))
        // 六个预设都要能往返，别让某个后加的枚举值悄悄退化成 FIT。
        WatchCanvasPreset.entries.forEach { preset ->
            val single = WatchCanvasSettings(preset = preset)
            assertEquals(single, WatchCanvasSettings.decode(single.encode()))
        }

        assertEquals(WatchCanvasSettings.DEFAULT, WatchCanvasSettings.decode(null))
        assertEquals(WatchCanvasSettings.DEFAULT, WatchCanvasSettings.decode(""))
        assertEquals(WatchCanvasSettings.DEFAULT, WatchCanvasSettings.decode("   "))
        // 整串无法识别时退回默认，而不是抛异常把整个偏好加载打断。
        assertEquals(WatchCanvasSettings.DEFAULT, WatchCanvasSettings.decode("nonsense"))
        // 单个坏字段只丢那一项：预设留住，坏掉的 zoomX 回落 1。
        assertEquals(
            WatchCanvasSettings(preset = WatchCanvasPreset.FILL),
            WatchCanvasSettings.decode("FILL|abc"),
        )
        // 字段比预期少也不越界。
        assertEquals(
            WatchCanvasSettings(preset = WatchCanvasPreset.CUSTOM, zoomX = 2f),
            WatchCanvasSettings.decode("CUSTOM|2.0"),
        )
        assertTrue(WatchCanvasSettings.DEFAULT.isDefault)
        assertFalse(settings.isDefault)
    }

    /**
     * 出厂全局默认是「铺满」，而解码兜底仍是中性 FIT —— 两者必须分开：
     * 合成一个的话，改默认铺法会顺手改掉所有坏数据的回落值。
     */
    @Test
    fun globalCanvasDefaultFillsWhileDecodeFallbackStaysNeutral() {
        assertEquals(WatchCanvasPreset.FILL, WatchCanvasSettings.GLOBAL_DEFAULT.preset)
        assertEquals(WatchCanvasPreset.FIT, WatchCanvasSettings.DEFAULT.preset)
        assertNotEquals(WatchCanvasSettings.GLOBAL_DEFAULT, WatchCanvasSettings.DEFAULT)
        // 出厂默认只换铺法，缩放与偏移仍是中性值。
        assertEquals(1f, WatchCanvasSettings.GLOBAL_DEFAULT.zoomX, 0f)
        assertEquals(1f, WatchCanvasSettings.GLOBAL_DEFAULT.zoomY, 0f)
        assertEquals(0f, WatchCanvasSettings.GLOBAL_DEFAULT.offsetX, 0f)
        assertEquals(0f, WatchCanvasSettings.GLOBAL_DEFAULT.offsetY, 0f)
        // 存量 force_fill 开关的两种取值仍各自映射，升级用户的显式选择不被顶掉。
        assertEquals(
            WatchCanvasSettings.GLOBAL_DEFAULT,
            WatchCanvasSettings.fromForceFill(true),
        )
        assertEquals(
            WatchCanvasSettings.DEFAULT,
            WatchCanvasSettings.fromForceFill(false),
        )
        // 出厂默认也要能编码往返，否则第一次落盘就丢铺法。
        assertEquals(
            WatchCanvasSettings.GLOBAL_DEFAULT,
            WatchCanvasSettings.decode(WatchCanvasSettings.GLOBAL_DEFAULT.encode()),
        )
    }

    @Test
    fun dynamicTagResolverDistinguishesUnavailableAndUnsupportedTags() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 23, 18, 5, 9)
        }
        val snapshot = WatchDataSnapshot(
            batteryPercent = 87,
            isCharging = true,
            steps = 12_345,
            heartRateBpm = 76,
        )

        assertEquals("18", WatchmakerTagResolver.resolve("dh23", calendar, snapshot).renderedValue)
        assertEquals("87%", WatchmakerTagResolver.resolve("blp", calendar, snapshot).renderedValue)
        assertEquals("87", WatchmakerTagResolver.resolve("bl", calendar, snapshot).renderedValue)
        assertEquals("charging", WatchmakerTagResolver.resolve("bc", calendar, snapshot).renderedValue.lowercase())
        assertEquals("12345", WatchmakerTagResolver.resolve("ssc", calendar, snapshot).renderedValue)
        assertEquals("76", WatchmakerTagResolver.resolve("shr", calendar, snapshot).renderedValue)
        // WM_TAGS.md: {sthr} = 最大心率（默认 200），{shrp} = 心率占最大值百分比。
        assertEquals("200", WatchmakerTagResolver.resolve("sthr", calendar, snapshot).renderedValue)
        assertEquals("38", WatchmakerTagResolver.resolve("shrp", calendar, snapshot).renderedValue)

        val unavailable = WatchmakerTagResolver.resolve("wtemp", calendar, snapshot)
        assertTrue(unavailable is WatchTagResolution.Unavailable)
        assertEquals("--", unavailable.renderedValue)

        val unsupported = WatchmakerTagResolver.resolve("future_vendor_metric", calendar, snapshot)
        assertTrue(unsupported is WatchTagResolution.Unsupported)
        assertEquals("--", unsupported.renderedValue)
    }

    @Test
    fun heartRateTagsShowUnavailableWithoutDataButKeepExpressionsAlive() {
        val calendar = Calendar.getInstance()
        val empty = WatchDataSnapshot()

        assertEquals("--", WatchmakerTagResolver.resolve("shr", calendar, empty).renderedValue)
        assertEquals("--", WatchmakerTagResolver.resolve("shrp", calendar, empty).renderedValue)
        assertEquals("--", WatchmakerExpressionEvaluator.evaluateText("{shr}") { tag ->
            WatchmakerTagResolver.resolve(tag, calendar, empty)
        })
        // {shr_1..9} 历史心率：Flue 不保存历史，必须明确显示为不可用。
        assertEquals("--", WatchmakerTagResolver.resolve("shr_1", calendar, empty).renderedValue)
        assertEquals("--", WatchmakerTagResolver.resolve("shr_9", calendar, empty).renderedValue)
        assertTrue(WatchmakerTagResolver.isKnown("shr_5"))

        // 表达式/脚本内部仍使用数值 0，避免图表坐标和门控表达式整体失效。
        assertEquals(
            5f,
            WatchmakerExpressionEvaluator.evaluateNumber("{shr} + 5", -1f) { tag ->
                WatchmakerTagResolver.resolve(tag, calendar, empty)
            },
            0.001f,
        )
    }

    @Test
    fun tiltAccelerometerTagsExposeRawAxesAndDefaultToZero() {
        val calendar = Calendar.getInstance()
        val sample = WatchDataSnapshot(
            accelerometerX = -4.25f,
            accelerometerY = 1.5f,
            accelerometerZ = 8.75f,
        )

        assertEquals("-4.25", WatchmakerTagResolver.resolve("sax", calendar, sample).renderedValue)
        assertEquals("1.5", WatchmakerTagResolver.resolve("say", calendar, sample).renderedValue)
        assertEquals("8.75", WatchmakerTagResolver.resolve("saz", calendar, sample).renderedValue)
        assertEquals("--", WatchmakerTagResolver.resolve("sax", calendar, WatchDataSnapshot()).renderedValue)
    }

    @Test
    fun outlineContractParsesModesAppliesDefaultsAndCapsSize() {
        // outline 属性只认 "Outline"（硬描边）与 "Glow"（发光），大小写不敏感。
        assertEquals(WatchOutlineMode.OUTLINE, watchOutlineMode("Outline"))
        assertEquals(WatchOutlineMode.GLOW, watchOutlineMode("glow"))
        assertEquals(WatchOutlineMode.NONE, watchOutlineMode(null))
        assertEquals(WatchOutlineMode.NONE, watchOutlineMode("Shadow"))

        // o_size 缺省：Outline 默认 4、Glow 默认 10。
        assertEquals(4f, watchOutlineSize(WatchOutlineMode.OUTLINE, null))
        assertEquals(10f, watchOutlineSize(WatchOutlineMode.GLOW, null))
        // 显式取值透传；全局封顶 ~120 防恶意表盘拖帧，负数收敛为 0。
        assertEquals(18f, watchOutlineSize(WatchOutlineMode.OUTLINE, 18f))
        assertEquals(MAX_WATCH_OUTLINE_SIZE, watchOutlineSize(WatchOutlineMode.GLOW, 9_999f))
        assertEquals(0f, watchOutlineSize(WatchOutlineMode.OUTLINE, -3f))
    }

    @Test
    fun glowRingCountAndAlphaDecayStayBounded() {
        // 环数 = ceil(o_size / 2)，封顶 MAX_WATCH_GLOW_RINGS（8）。
        assertEquals(1, watchGlowRingCount(1f))
        assertEquals(5, watchGlowRingCount(10f))
        assertEquals(MAX_WATCH_GLOW_RINGS, watchGlowRingCount(MAX_WATCH_OUTLINE_SIZE))

        // 透明度由内向外单调衰减，最外环仍大于 0（不会白画）。
        val rings = watchGlowRingCount(10f)
        var previous = Float.MAX_VALUE
        for (ring in 1..rings) {
            val fraction = watchGlowRingAlpha(ring, rings)
            assertTrue(fraction > 0f)
            assertTrue(fraction < previous)
            previous = fraction
        }
    }

    @Test
    fun shadowContractParsesModesAppliesDefaultsAndCapsDistance() {
        // shadow 只认 "Drop"（单份）与 "Flat"（堆叠），大小写不敏感。
        assertEquals(WatchShadowMode.DROP, watchShadowMode("Drop"))
        assertEquals(WatchShadowMode.FLAT, watchShadowMode("flat"))
        assertEquals(WatchShadowMode.NONE, watchShadowMode(null))
        assertEquals(WatchShadowMode.NONE, watchShadowMode("Outline"))

        // w_distance 缺省 5（对齐 clox evalNum("w_distance", 5)），负数收 0。
        assertEquals(5f, watchShadowDistance(null))
        assertEquals(10f, watchShadowDistance(10f))
        assertEquals(MAX_WATCH_SHADOW_DISTANCE, watchShadowDistance(9_999f))
        assertEquals(0f, watchShadowDistance(-3f))

        // w_angle 缺省 135°（光照方向）。
        assertEquals(135f, watchShadowAngleDegrees(null))
        assertEquals(45f, watchShadowAngleDegrees(45f))
    }

    @Test
    fun shadowCopyCountAndDistanceFollowCloxDropVersusFlat() {
        // Drop 恒一份，偏移就是 w_distance 本身。
        assertEquals(1, watchShadowCopyCount(WatchShadowMode.DROP, 10f))
        assertEquals(10f, watchShadowCopyDistance(WatchShadowMode.DROP, 10f, 1))

        // Flat = floor(w_distance) 份，第 i 份偏移 i —— 堆成实心拖影。
        assertEquals(10, watchShadowCopyCount(WatchShadowMode.FLAT, 10f))
        assertEquals(3, watchShadowCopyCount(WatchShadowMode.FLAT, 3.9f))
        assertEquals(1f, watchShadowCopyDistance(WatchShadowMode.FLAT, 10f, 1))
        assertEquals(7f, watchShadowCopyDistance(WatchShadowMode.FLAT, 10f, 7))

        // 距离不足 1px 的 Flat 一份都不画；NONE 恒 0 份。
        assertEquals(0, watchShadowCopyCount(WatchShadowMode.FLAT, 0.5f))
        assertEquals(0, watchShadowCopyCount(WatchShadowMode.NONE, 10f))
        // 封顶同时约束 Flat 的拷贝份数，恶意表盘不能靠 w_distance 拖帧。
        assertEquals(
            MAX_WATCH_SHADOW_DISTANCE.toInt(),
            watchShadowCopyCount(WatchShadowMode.FLAT, watchShadowDistance(9_999f)),
        )
    }

    @Test
    fun shadowOffsetFallsOppositeTheLightAndFlipsYForCanvas() {
        // w_angle 是光照方向，影子朝反方向落。
        // 0° 光从右照来 → 影子落在左边（-x），y 不动。
        assertEquals(-10f, watchShadowOffsetX(10f, 0f), 0.0001f)
        assertEquals(0f, watchShadowOffsetY(10f, 0f), 0.0001f)

        // 90°：clox 在 y-up 空间算 -d·sin = -10（往下）。Android Canvas y 向下，
        // 同一个视觉方向必须是 +10；这里翻符号翻对了才不会上下颠倒。
        assertEquals(0f, watchShadowOffsetX(10f, 90f), 0.0001f)
        assertEquals(10f, watchShadowOffsetY(10f, 90f), 0.0001f)

        // 默认 135°（左上打光）→ 影子落右下：+x、+y（Canvas 坐标系）。
        val d = 10f
        val diagonal = d * kotlin.math.sqrt(2f) / 2f
        assertEquals(diagonal, watchShadowOffsetX(d, 135f), 0.0001f)
        assertEquals(diagonal, watchShadowOffsetY(d, 135f), 0.0001f)

        // 距离 0 时两轴都不偏移（画在本体正下方，等于看不见影子）。
        assertEquals(0f, watchShadowOffsetX(0f, 135f), 0.0001f)
        assertEquals(0f, watchShadowOffsetY(0f, 135f), 0.0001f)
    }

    /**
     * 图片定尺跟着 clox `setBaseScale`：只有「非方图 + 方形框」才等比，
     * 其余一律按框拉伸。宽度在两个分支里都等于作者写的 width，所以只验高度。
     */
    @Test
    fun nonSquareImageInSquareBoxKeepsAspectInsteadOfSquashing() {
        // 老师报的第 4 项实测素材：MOON 的 1284×1482 声明成 463×512（非方框）
        // → 走 else 分支，高度就是作者写的 512，和改动前一致。
        assertEquals(512f, watchImageDrawHeight(1284f, 1482f, 463f, 512f), 0.0001f)

        // 同一张图塞进方形框 512×512 → 等比：512/1284 × 1482 ≈ 590.85，
        // 比框更高，不再被压成正方形。
        assertEquals(
            1482f * (512f / 1284f),
            watchImageDrawHeight(1284f, 1482f, 512f, 512f),
            0.0001f,
        )

        // 横图进方形框：高度反而小于框。
        assertEquals(256f, watchImageDrawHeight(1024f, 512f, 512f, 512f), 0.0001f)

        // 方图 + 方框（Android13 的 1080×1080 声明 512×512）→ 不进等比分支，
        // 高度照框走；数值上和等比一致，但走的是 else，行为定义清楚。
        assertEquals(512f, watchImageDrawHeight(1080f, 1080f, 512f, 512f), 0.0001f)

        // 方图 + 非方框 → 按框拉伸（作者显式要求变形，clox 也照做）。
        assertEquals(200f, watchImageDrawHeight(1024f, 1024f, 512f, 200f), 0.0001f)

        // 纹理尺寸拿不到（0 或负）时不猜，退回框高。
        assertEquals(300f, watchImageDrawHeight(0f, 0f, 512f, 300f), 0.0001f)
        assertEquals(300f, watchImageDrawHeight(-1f, 512f, 512f, 300f), 0.0001f)
    }

    @Test
    fun canonicalWeatherAliasesUseSnapshotFieldsWithoutLeakingRawTags() {
        val calendar = Calendar.getInstance()
        val snapshot = WatchDataSnapshot(
            weatherTemperature = "26",
            weatherCondition = "Cloudy",
            weatherIcon = "03d",
            weatherHigh = "30",
            weatherLow = "20",
            weatherHumidity = 72,
        )

        assertEquals("26°C", WatchmakerTagResolver.resolve("wtd", calendar, snapshot).renderedValue)
        assertEquals("Cloudy", WatchmakerTagResolver.resolve("wct", calendar, snapshot).renderedValue)
        assertEquals("03d", WatchmakerTagResolver.resolve("wci", calendar, snapshot).renderedValue)
        assertEquals("30", WatchmakerTagResolver.resolve("wth", calendar, snapshot).renderedValue)
        assertEquals("20", WatchmakerTagResolver.resolve("wtl", calendar, snapshot).renderedValue)
        assertEquals("72%", WatchmakerTagResolver.resolve("whp", calendar, snapshot).renderedValue)
        assertEquals("--", WatchmakerTagResolver.resolve("wcl", calendar, snapshot).renderedValue)
    }

    @Test
    fun canonicalWeatherSnapshotRendersCelsiusAndFahrenheitWithoutDoubleConversion() {
        assertEquals(WatchTemperatureUnit.CELSIUS, WatchTemperatureUnit.fromRaw(null))
        assertEquals(WatchTemperatureUnit.FAHRENHEIT, WatchTemperatureUnit.fromRaw("°F"))
        assertEquals(WatchTemperatureUnit.FAHRENHEIT, WatchTemperatureUnit.fromRaw("FAHRENHEIT"))
        val calendar = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JULY, 23, 18, 5, 9)
        }
        val celsius = WatchWeatherSnapshot(
            temperatureCelsius = 25.0,
            highCelsius = 30.0,
            lowCelsius = 20.0,
            displayUnit = WatchTemperatureUnit.CELSIUS,
            icon = "3d",
            condition = "Cloudy",
            humidity = 72,
            location = "Shanghai",
            isDaytime = true,
            cloudCoverFraction = 0.42,
        )
        val celsiusSnapshot = WatchDataSnapshot(weather = celsius)
        assertEquals("C", WatchmakerTagResolver.resolve("wm", calendar, celsiusSnapshot).renderedValue)
        assertEquals("25", WatchmakerTagResolver.resolve("wt", calendar, celsiusSnapshot).renderedValue)
        assertEquals("25°C", WatchmakerTagResolver.resolve("wtd", calendar, celsiusSnapshot).renderedValue)
        assertEquals("30°C", WatchmakerTagResolver.resolve("wthd", calendar, celsiusSnapshot).renderedValue)
        assertEquals("20°C", WatchmakerTagResolver.resolve("wtld", calendar, celsiusSnapshot).renderedValue)
        assertEquals("03d", WatchmakerTagResolver.resolve("wci", calendar, celsiusSnapshot).renderedValue)
        assertEquals("Cloudy", WatchmakerTagResolver.resolve("wcct", calendar, celsiusSnapshot).renderedValue)
        assertEquals("0.42", WatchmakerTagResolver.resolve("wcl", calendar, celsiusSnapshot).renderedValue)
        assertEquals("Shanghai", WatchmakerTagResolver.resolve("wl", calendar, celsiusSnapshot).renderedValue)
        assertEquals("Shanghai", WatchmakerTagResolver.resolve("wml", calendar, celsiusSnapshot).renderedValue)
        assertEquals("72", WatchmakerTagResolver.resolve("wchn", calendar, celsiusSnapshot).renderedValue)
        assertEquals("72%", WatchmakerTagResolver.resolve("whp", calendar, celsiusSnapshot).renderedValue)
        assertEquals("72%", WatchmakerTagResolver.resolve("wchp", calendar, celsiusSnapshot).renderedValue)
        assertEquals("true", WatchmakerTagResolver.resolve("wisday", calendar, celsiusSnapshot).renderedValue)

        val nightSnapshot = WatchDataSnapshot(weather = celsius.copy(icon = "10n", isDaytime = false))
        assertEquals("10d", WatchmakerTagResolver.resolve("wci", calendar, nightSnapshot).renderedValue)
        assertEquals("false", WatchmakerTagResolver.resolve("wisday", calendar, nightSnapshot).renderedValue)

        val fahrenheitSnapshot = WatchDataSnapshot(
            weather = celsius.copy(displayUnit = WatchTemperatureUnit.FAHRENHEIT),
        )
        assertEquals("F", WatchmakerTagResolver.resolve("wm", calendar, fahrenheitSnapshot).renderedValue)
        assertEquals("77", WatchmakerTagResolver.resolve("wt", calendar, fahrenheitSnapshot).renderedValue)
        assertEquals("77°F", WatchmakerTagResolver.resolve("wtd", calendar, fahrenheitSnapshot).renderedValue)
        assertEquals("86°F", WatchmakerTagResolver.resolve("wthd", calendar, fahrenheitSnapshot).renderedValue)
        assertEquals("68°F", WatchmakerTagResolver.resolve("wtld", calendar, fahrenheitSnapshot).renderedValue)

        // A legacy F string is converted to Celsius exactly once, then rendered
        // back in the requested F presentation unit.
        val legacyFahrenheit = WatchDataSnapshot(
            weatherTemperature = "77",
            weatherTemperatureUnit = "F",
        )
        assertEquals("77°F", WatchmakerTagResolver.resolve("wtd", calendar, legacyFahrenheit).renderedValue)
    }

    @Test
    fun weatherTemperatureTagsRoundToWholeDegrees() {
        assertEquals("24", weatherTemperatureNumber(23.6, WatchTemperatureUnit.CELSIUS))
        assertEquals("23", weatherTemperatureNumber(23.4, WatchTemperatureUnit.CELSIUS))
        assertEquals("74", weatherTemperatureNumber(23.6, WatchTemperatureUnit.FAHRENHEIT))
        assertEquals("-4", weatherTemperatureNumber(-4.4, WatchTemperatureUnit.CELSIUS))
    }

    @Test
    fun weatherUpdateAndForecastAliasesStayDeterministic() {
        val now = Calendar.getInstance().apply {
            clear()
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, Calendar.JULY, 23, 18, 5, 9)
        }
        val observedAt = now.timeInMillis
        val snapshot = WatchDataSnapshot(
            weather = WatchWeatherSnapshot(
                observedAtEpochMs = observedAt,
                lastUpdatedText = null,
                displayUnit = WatchTemperatureUnit.CELSIUS,
                forecast = listOf(
                    WatchWeatherForecast(
                        dayOffset = 1,
                        temperatureCelsius = 23.0,
                        highCelsius = 28.0,
                        lowCelsius = 19.0,
                        condition = "Rain",
                        icon = "10n",
                    ),
                    WatchWeatherForecast(
                        dayOffset = 2,
                        temperatureCelsius = 24.0,
                        highCelsius = 29.0,
                        lowCelsius = 20.0,
                        condition = "Cloudy",
                        icon = "4d",
                    ),
                ),
                hourlyForecast = listOf(
                    WatchWeatherHourlyForecast(
                        hourOffset = 1,
                        hourOfDay = 19,
                        temperatureCelsius = 24.0,
                        condition = "Rain",
                        icon = "10n",
                    ),
                ),
            ),
        )
        assertEquals("18:05:09", WatchmakerTagResolver.resolve("wlu", now, snapshot).renderedValue)
        assertEquals("28", WatchmakerTagResolver.resolve("wfah", now, snapshot).renderedValue)
        assertEquals("19", WatchmakerTagResolver.resolve("wfal", now, snapshot).renderedValue)
        assertEquals("Rain", WatchmakerTagResolver.resolve("wfact", now, snapshot).renderedValue)
        assertEquals("10d", WatchmakerTagResolver.resolve("wfaci", now, snapshot).renderedValue)
        assertEquals("28", WatchmakerTagResolver.resolve("wth1", now, snapshot).renderedValue)
        assertEquals("28°C", WatchmakerTagResolver.resolve("wthd1", now, snapshot).renderedValue)
        assertEquals("20", WatchmakerTagResolver.resolve("wtl2", now, snapshot).renderedValue)
        assertEquals("04d", WatchmakerTagResolver.resolve("wci2", now, snapshot).renderedValue)
        assertEquals("23", WatchmakerTagResolver.resolve("wf0dt", now, snapshot).renderedValue)
        assertEquals("28", WatchmakerTagResolver.resolve("wf0dth", now, snapshot).renderedValue)
        assertEquals("20", WatchmakerTagResolver.resolve("wf1dtl", now, snapshot).renderedValue)
        assertEquals("Rain", WatchmakerTagResolver.resolve("wf0dct", now, snapshot).renderedValue)
        assertEquals("10d", WatchmakerTagResolver.resolve("wf0dci", now, snapshot).renderedValue)
        assertEquals("24", WatchmakerTagResolver.resolve("wf1ht", now, snapshot).renderedValue)
        assertEquals("19", WatchmakerTagResolver.resolve("wf1hh", now, snapshot).renderedValue)
        assertEquals("Rain", WatchmakerTagResolver.resolve("wf1hct", now, snapshot).renderedValue)
        assertEquals("10n", WatchmakerTagResolver.resolve("wf1hci", now, snapshot).renderedValue)
    }

    @Test
    fun timeAliasesUseMillisecondsAndCorrectHourRangesIncludingUtc() {
        val calendar = Calendar.getInstance().apply {
            clear()
            timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            set(2026, Calendar.JULY, 23, 0, 5, 9)
            set(Calendar.MILLISECOND, 42)
        }
        val snapshot = WatchDataSnapshot()
        assertEquals("09", WatchmakerTagResolver.resolve("dsz", calendar, snapshot).renderedValue)
        assertEquals("42", WatchmakerTagResolver.resolve("dss", calendar, snapshot).renderedValue)
        assertEquals("042", WatchmakerTagResolver.resolve("dssz", calendar, snapshot).renderedValue)
        assertEquals("9042", WatchmakerTagResolver.resolve("dsps", calendar, snapshot).renderedValue)
        assertEquals("0", WatchmakerTagResolver.resolve("dh11", calendar, snapshot).renderedValue)
        assertEquals("24", WatchmakerTagResolver.resolve("dh24", calendar, snapshot).renderedValue)
        assertEquals("24", WatchmakerTagResolver.resolve("dh24z", calendar, snapshot).renderedValue)
        assertEquals("16", WatchmakerTagResolver.resolve("dh23utc", calendar, snapshot).renderedValue)
        assertEquals("16", WatchmakerTagResolver.resolve("dhutc24", calendar, snapshot).renderedValue)
        assertEquals("04", WatchmakerTagResolver.resolve("dhutc12z", calendar, snapshot).renderedValue)
        assertEquals("AM", WatchmakerTagResolver.resolve("da", calendar, snapshot).renderedValue)
        assertEquals("54", WatchmakerTagResolver.resolve("drs", calendar, snapshot).renderedValue)
        assertEquals(54.252f, WatchmakerTagResolver.resolve("drss", calendar, snapshot).renderedValue.toFloat(), 0.01f)
        assertEquals(15.12f, WatchmakerTagResolver.resolve("drms", calendar, snapshot).renderedValue.toFloat(), 0.01f)
        assertTrue(WatchmakerTagResolver.isKnown("dh11utc"))
        assertTrue(WatchmakerTagResolver.isKnown("dhutc24"))
        assertTrue(WatchmakerTagResolver.requiresFastTicks("rotate({drss})"))
        assertTrue(WatchmakerTagResolver.requiresFastTicks("rotate({drm})"))
        assertTrue(WatchmakerTagResolver.requiresFastTicks("progress({dtp})"))
        assertTrue(WatchmakerTagResolver.requiresFastTicks("text {dssz}"))
    }

    @Test
    fun weatherSnapshotCacheReturnsFreshEntriesOnly() {
        val cache = WatchWeatherSnapshotCache(maxAgeMs = 1_000L)
        val snapshot = WatchWeatherSnapshot(temperatureCelsius = 21.0)
        cache.update(snapshot, receivedAtEpochMs = 10_000L)
        assertEquals(snapshot, cache.snapshot(10_999L))
        assertNull(cache.snapshot(11_001L))
    }

    @Test
    fun weatherNightSpritesUseTheCompanionSheetAndWatchMakerCellRemap() {
        assertEquals("images/weather_n.png", watchWeatherNightAssetReference("images/weather.png"))
        assertEquals("weather_n", watchWeatherNightAssetReference("weather"))
        assertEquals(3, watchWeatherNightSpriteIndex(0))
        assertEquals(2, watchWeatherNightSpriteIndex(1))
        assertEquals(2, watchWeatherNightSpriteIndex(2))
        assertEquals(8, watchWeatherNightSpriteIndex(3))
        assertEquals(5, watchWeatherNightSpriteIndex(5))
    }

    @Test
    fun corpusTagsResolveWeekdayBatteryWeatherAndOptionalDeviceFamilies() {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JULY, 23, 18, 5, 9)
        }
        val snapshot = WatchDataSnapshot(
            batteryPercent = 87,
            weatherHigh = "30",
            weatherLow = "20",
            weatherTemperatureUnit = "C",
        )

        assertEquals("4", WatchmakerTagResolver.resolve("ddw0", calendar, snapshot).renderedValue)
        assertEquals(
            calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault())
                .orEmpty()
                .take(2),
            WatchmakerTagResolver.resolve("ddw2", calendar, snapshot).renderedValue,
        )
        assertEquals("26", WatchmakerTagResolver.resolve("dy", calendar, snapshot).renderedValue)
        assertEquals("2026", WatchmakerTagResolver.resolve("dyyyy", calendar, snapshot).renderedValue)
        assertEquals("87%", WatchmakerTagResolver.resolve("pblp", calendar, snapshot).renderedValue)
        assertEquals("30°C", WatchmakerTagResolver.resolve("wthd", calendar, snapshot).renderedValue)
        assertEquals("20°C", WatchmakerTagResolver.resolve("wtld", calendar, snapshot).renderedValue)
        assertEquals("0", WatchmakerTagResolver.resolve("sgx", calendar, snapshot).renderedValue)
        assertEquals("0", WatchmakerTagResolver.resolve("pws", calendar, snapshot).renderedValue)
        assertEquals("false", WatchmakerTagResolver.resolve("nbc", calendar, snapshot).renderedValue)

        assertEquals(
            "THU",
            WatchmakerExpressionEvaluator.evaluateText(
                "{ddw0} == 4 and 'THU' or 'OTHER'",
            ) { tag -> WatchmakerTagResolver.resolve(tag, calendar, snapshot) },
        )
    }

    @Test
    fun weekdayLabelsTrimTrailingAbbreviationPeriodInEveryLocale() {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JULY, 20, 10, 0, 0) // 周一
        }
        val snapshot = WatchDataSnapshot()

        // 某些 locale 的缩写数据自带尾句号（es "lun."、de "Mo." 等），
        // 落到表盘上就是「周一.」式拖尾——所有星期标签统一裁掉。
        for (locale in listOf(Locale.forLanguageTag("es"), Locale.GERMAN, Locale.getDefault())) {
            for (tag in listOf("ddw", "ddw1", "ddw2", "ddww")) {
                val value = WatchmakerTagResolver
                    .resolve(tag, calendar, snapshot, locale = locale)
                    .renderedValue
                assertFalse("$tag@$locale => $value", value.endsWith("."))
            }
        }
    }

    @Test
    fun weekdayLabelsHonorTheRequestedDateLocale() {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JULY, 20, 10, 0, 0) // 周一
        }
        val snapshot = WatchDataSnapshot()

        // zh/en 两档下星期输出必须确定，不再受运行机器的系统语言影响。
        assertEquals(
            "周一",
            WatchmakerTagResolver
                .resolve("ddw", calendar, snapshot, locale = Locale.SIMPLIFIED_CHINESE)
                .renderedValue,
        )
        assertEquals(
            "Mon",
            WatchmakerTagResolver
                .resolve("ddw", calendar, snapshot, locale = Locale.ENGLISH)
                .renderedValue,
        )
        assertEquals(
            "Monday",
            WatchmakerTagResolver
                .resolve("ddww", calendar, snapshot, locale = Locale.ENGLISH)
                .renderedValue,
        )
    }

    @Test
    fun dateLocalePreferenceMapsFourValuesAndFallsBackToSystem() {
        // 四档取值：system/zh/en/ja；system 与未知值都映射为 null（跟随系统）。
        assertEquals(Locale.SIMPLIFIED_CHINESE, WatchDateLocale.fromPreference("zh"))
        assertEquals(Locale.ENGLISH, WatchDateLocale.fromPreference("en"))
        assertEquals(Locale.JAPANESE, WatchDateLocale.fromPreference("ja"))
        assertNull(WatchDateLocale.fromPreference(WatchDateLocale.PREFERENCE_SYSTEM))
        assertNull(WatchDateLocale.fromPreference(null))
        assertNull(WatchDateLocale.fromPreference("weird-value"))
    }

    @Test
    fun expressionEvaluatorHandlesDynamicGeometryOpacityAndDisplayWithoutScripts() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 23, 18, 5, 9)
        }
        val snapshot = WatchDataSnapshot(
            batteryPercent = 80,
            isCharging = true,
        )
        val resolver: (String) -> WatchTagResolution = { tag ->
            WatchmakerTagResolver.resolve(tag, calendar, snapshot)
        }

        assertEquals(
            288f,
            WatchmakerExpressionEvaluator.evaluateNumber("{bl} * 36 / 10", -1f, resolver),
            0.001f,
        )
        assertEquals(
            100f,
            WatchmakerExpressionEvaluator.evaluateNumber(
                "{charging} == 1 and 100 or 15",
                -1f,
                resolver,
            ),
            0.001f,
        )
        assertTrue(
            WatchmakerExpressionEvaluator.evaluateBoolean("{charging} == 1", false, resolver),
        )
        assertFalse(
            WatchmakerExpressionEvaluator.evaluateBoolean(
                "{charging}",
                true,
            ) { WatchTagResolution.Resolved("0") },
        )
        // 没有天气源时 {wtemp} 取中性 0 —— 于是这条式子算得完（0 + 1），不再整条
        // 失败落回 42。区别在真实表盘上很大：`opacity="{wtemp} > 30 and 100 or 0"`
        // 旧行为整条废掉 → opacity 落回默认 100 → 该藏起来的图层反而常亮。
        assertEquals(
            1f,
            WatchmakerExpressionEvaluator.evaluateNumber("{wtemp} + 1", 42f, resolver),
            0.001f,
        )
        assertTrue(WatchmakerExpressionEvaluator.supports("{charging} == 1 and 100 or 15"))
        assertTrue(WatchmakerExpressionEvaluator.supports("math.sin({drss})"))
    }

    @Test
    fun expressionEvaluatorSupportsCommonWatchMakerMathStringAndConcatForms() {
        val tags = mapOf(
            "blp" to "80",
            "dhz" to "06",
            "dmz" to "05",
            "da" to "PM",
        )
        val resolver: (String) -> WatchTagResolution = { tag ->
            tags[tag]?.let(WatchTagResolution::Resolved)
                ?: WatchTagResolution.Unsupported(tag)
        }

        assertEquals(
            711f,
            WatchmakerExpressionEvaluator.evaluateNumber(
                "math.floor(({blp} / 3) ^ 2)",
                -1f,
                resolver,
            ),
            0.001f,
        )
        assertEquals(
            -4f,
            WatchmakerExpressionEvaluator.evaluateNumber("-2 ^ 2", -1f, resolver),
            0.001f,
        )
        assertFalse(
            WatchmakerExpressionEvaluator.evaluateBoolean("not 1 < 2", true, resolver),
        )
        assertEquals(
            "06:05",
            WatchmakerExpressionEvaluator.evaluateText(
                "\"{dhz}\" .. ':' .. \"{dmz}\"",
                resolver,
            ),
        )
        assertEquals(
            "EVENING",
            WatchmakerExpressionEvaluator.evaluateText(
                "'{da}' == 'PM' and string.upper('evening') or 'day'",
                resolver,
            ),
        )
        assertEquals(
            "05",
            WatchmakerExpressionEvaluator.evaluateText(
                "string.format('%02d', {dmz})",
                resolver,
            ),
        )
    }

    @Test
    fun taggedTextArithmeticEvaluatesWhilePlainTemplatesStillFallback() {
        val resolver: (String) -> WatchTagResolution = { tag ->
            when (tag) {
                "wws" -> WatchTagResolution.Resolved("5")
                "sc" -> WatchTagResolution.Resolved("1234")
                else -> WatchTagResolution.Unsupported(tag)
            }
        }

        assertEquals(
            "8",
            WatchmakerExpressionEvaluator.evaluateText("{wws}*1.6", resolver),
        )
        assertEquals(
            "Steps 1234",
            WatchmakerExpressionEvaluator.evaluateText("Steps {sc}", resolver),
        )
        assertEquals(
            "1 == 2",
            WatchmakerExpressionEvaluator.evaluateText("1 == 2", resolver),
        )
    }

    @Test
    fun expressionEvaluatorKeepsTemplatesAndRejectsUnsafeOrUnboundedCode() {
        val resolver: (String) -> WatchTagResolution = { tag ->
            when (tag) {
                "dhz" -> WatchTagResolution.Resolved("06")
                "dmz" -> WatchTagResolution.Resolved("05")
                else -> WatchTagResolution.Unsupported(tag)
            }
        }

        assertEquals(
            "Time 06:05",
            WatchmakerExpressionEvaluator.evaluateText("Time {dhz}:{dmz}", resolver),
        )
        assertFalse(WatchmakerExpressionEvaluator.supports("os.execute('boom')"))
        assertFalse(WatchmakerExpressionEvaluator.supports("var_mode + 1"))
        // 源文本上限（32K）仍然拦得住无边界输入。这个数字比原来的 4K 大，是为了
        // 容纳 WFF 导出的巨型链式三元式（实测 7.6K），护栏本身没有取消。
        assertFalse(WatchmakerExpressionEvaluator.supports("1 + ".repeat(9_000) + "1"))
        // 深度嵌套单独限：token 数没超但栈会爆，所以在解析入口按层数拦。
        assertFalse(
            WatchmakerExpressionEvaluator.supports("(".repeat(400) + "1" + ")".repeat(400)),
        )
        // 放宽后必须真的能吃下那批表达式：7.6K 字符 / 约 2500 token 的链式三元式。
        assertTrue(
            WatchmakerExpressionEvaluator.supports(
                (1..300).joinToString(" or ") { "('{wci}' == '${it}d' and $it)" },
            ),
        )
    }

    @Test
    fun compatibilityReportAcceptsLuaVariablesHandledByTheFaceSandbox() =
        withTemporaryDirectory { root ->
            val definition = WatchmakerDefinitionParser().parse(
                File(root, "watch.xml").apply {
                    writeText(
                        """
                        <Watch name="Expressions">
                          <Layer type="text"
                            text="string.format('%02d', {dm})"
                            rotation="math.floor({drss})" />
                          <Layer type="image" opacity="var_mode == 1 and 100 or 0" />
                        </Watch>
                        """.trimIndent(),
                    )
                },
            )

            assertEquals(
                setOf("var_mode == 1 and 100 or 0"),
                definition.compatibilityReport.unsupportedExpressions,
            )
        }

    @Test
    fun studioStructuredLayersAreDispatchedAsSupportedStandardPrimitives() =
        withTemporaryDirectory { root ->
            val definition = WatchmakerDefinitionParser().parse(
                File(root, "watch.xml").apply {
                    writeText(
                        """
                        <Watch name="Studio structured">
                          <Layer type="ring_image" path="ring.webp" />
                          <Layer type="daylight" daylight_map="city" />
                          <Layer type="horizon" width="512" />
                          <Layer type="sunarc" sun_preset="classic" />
                          <Layer type="progress_image" progress_shape="arrow1" />
                        </Watch>
                        """.trimIndent(),
                    )
                },
            )

            assertTrue(definition.compatibilityReport.unsupportedLayerTypes.isEmpty())
        }

    @Test
    fun initialScriptVariablesFeedExpressionsWithoutExecutingFunctions() {
        val scope = WatchExpressionScope.fromInitialScript(
            """
            var_chime = 0
            var_label = 'READY'
            function on_minute()
              var_chime = 1
            end
            """.trimIndent(),
        )
        val resolver: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) }

        assertEquals(mapOf("var_chime" to "0", "var_label" to "READY"), scope.variables)
        assertEquals(
            15f,
            WatchmakerExpressionEvaluator.evaluateNumber(
                "var_chime == 0 and 15 or 100",
                -1f,
                scope,
                resolver,
            ),
            0.001f,
        )
        assertEquals(
            "READY",
            WatchmakerExpressionEvaluator.evaluateText("var_label", scope, resolver),
        )
        assertTrue(WatchmakerExpressionEvaluator.supports("var_chime == 0", scope))
        assertFalse(WatchmakerExpressionEvaluator.supports("var_missing == 0", scope))
    }

    @Test
    fun anyPreparedFaceStaysVisibleUntilItsReplacementIsReady() {
        assertEquals(
            WatchSourceReloadMode.KEEP_CURRENT_UNTIL_READY,
            watchSourceReloadMode("watch:one", "watch:one", hasPreparedSource = true),
        )
        assertEquals(
            WatchSourceReloadMode.KEEP_CURRENT_UNTIL_READY,
            watchSourceReloadMode("watch:one", "watch:two", hasPreparedSource = true),
        )
        assertEquals(
            WatchSourceReloadMode.CLEAR_BEFORE_LOAD,
            watchSourceReloadMode("watch:one", "watch:one", hasPreparedSource = false),
        )
    }

    @Test
    fun liveTransformHandoffOnlyReleasesForTheArmedSourceIdentity() {
        val handoff = WatchLiveTransformHandoff()

        handoff.arm(descriptorKey = "watch:one", refreshToken = 8)

        assertFalse(handoff.consumeIfReady(descriptorKey = "watch:one", refreshToken = 7))
        assertFalse(handoff.consumeIfReady(descriptorKey = "watch:two", refreshToken = 8))
        assertTrue(handoff.consumeIfReady(descriptorKey = "watch:one", refreshToken = 8))
        assertFalse(handoff.consumeIfReady(descriptorKey = "watch:one", refreshToken = 8))
    }

    @Test
    fun startingAnotherGestureCancelsThePreviousSourceHandoff() {
        val handoff = WatchLiveTransformHandoff()

        handoff.arm(descriptorKey = "watch:one", refreshToken = 3)
        handoff.cancel()

        assertFalse(handoff.consumeIfReady(descriptorKey = "watch:one", refreshToken = 3))
    }

    @Test
    fun assetCacheRevisionIsStableAcrossRefreshGenerationsAndChangesWithTheFile() {
        val first = watchAssetCacheRevision(
            rootKey = "/faces/watch-a",
            relativePath = "images/dial.png",
            length = 1_024L,
            lastModifiedEpochMs = 2_000L,
        )
        val sameRevision = watchAssetCacheRevision(
            rootKey = "/faces/watch-a",
            relativePath = "images/dial.png",
            length = 1_024L,
            lastModifiedEpochMs = 2_000L,
        )
        val changedFile = watchAssetCacheRevision(
            rootKey = "/faces/watch-a",
            relativePath = "images/dial.png",
            length = 1_025L,
            lastModifiedEpochMs = 2_001L,
        )

        assertEquals(first, sameRevision)
        assertFalse(first == changedFile)
    }

    @Test
    fun executableRevisionChangesWhenSameSizeSameTimestampLuaContentChanges() {
        val metadata = watchAssetCacheRevision(
            rootKey = "/faces/watch-a",
            relativePath = "scripts/script.txt",
            length = 13L,
            lastModifiedEpochMs = 2_000L,
        )

        val first = watchExecutableContentRevision(metadata, "var_value = 1")
        val changedScript = watchExecutableContentRevision(metadata, "var_value = 2")

        assertFalse(first == changedScript)
        assertEquals(first, watchExecutableContentRevision(metadata, "var_value = 1"))
    }

    @Test
    fun layerForestRejectsSelfParentsAndCyclesInsteadOfDroppingTheWholeFace() {
        val validParent = layer(id = "group", parentId = null, type = "group")
        val validChild = layer(id = "child", parentId = "group")
        val selfParent = layer(id = "self", parentId = "self")
        val cycleA = layer(id = "a", parentId = "b", type = "group")
        val cycleB = layer(id = "b", parentId = "a", type = "group")

        val parents = resolveWatchLayerParentIndices(
            listOf(validParent, validChild, selfParent, cycleA, cycleB),
        )

        assertEquals(listOf(null, 0, null, null, null), parents)
    }

    /**
     * wf528 的黑帧根因：opacity 表达式门控的图片层在翻真之前从没被画过，也就从没
     * 发起过解码。预暖清单要挑出的正是这些层。
     */
    @Test
    fun opacityWarmupPlanPicksDynamicallyGatedStillImagesOnly() {
        val plan = planOpacityGatedImageWarmup(
            parentIndices = listOf(null, null, null, null, null, null),
            layerTypes = listOf("image", "image", "image", "text", "shape", "image_cond"),
            rawOpacityValues = listOf(
                "(math.floor({ds}/4) % 6 == 1) and 100 or 0 ", // 门控轮换层 → 要暖
                "100", // 常驻可见 → 一直是热的，不必暖
                "0", // 作者关掉的层 → 暖它纯属白解码
                "{dm} > 30 and 100 or 0", // 动态但不是图片
                "{dm} > 30 and 100 or 0", // 同上
                "({shr} == 3) and 100 or 0", // 天气条件图也门控 → 要暖
            ),
            rawPathValues = listOf("a.png", "b.png", "c.png", "", "", "cond.png"),
        )

        assertEquals(listOf(0, 5), plan)
    }

    /** 门控写在 group 上、图片层自己是常量 opacity —— wf528/312 都有这种写法。 */
    @Test
    fun opacityWarmupPlanInheritsGatingFromAncestorGroups() {
        val plan = planOpacityGatedImageWarmup(
            parentIndices = listOf(null, 0, 1, null, 3),
            layerTypes = listOf("group", "group", "image", "group", "image"),
            rawOpacityValues = listOf("{ds} % 2 == 0 and 100 or 0", "100", "100", "100", "100"),
            rawPathValues = listOf("", "", "nested.png", "", "static.png"),
        )

        assertEquals(listOf(2), plan)
    }

    /**
     * 反引号帧序列与动态路径必须排除：翻真那一拍要哪一帧取决于届时的动画时钟，
     * 现在暖的不一定是要的；312 有 19 个这样的层，全暖一遍就是自造卡顿。
     */
    @Test
    fun opacityWarmupPlanSkipsFrameSequencesDynamicPathsAndPreparsedSvg() {
        val gate = "({ds} % 4 == 0) and 100 or 0"
        val plan = planOpacityGatedImageWarmup(
            parentIndices = listOf(null, null, null, null),
            layerTypes = listOf("image_gif", "image", "svg", "image"),
            rawOpacityValues = listOf(gate, gate, gate, gate),
            rawPathValues = listOf(
                "f0.png`f1.png`f2.png", // 帧序列
                "icons/{whi}.png", // 动态路径
                "vector.svg", // SVG 在准备阶段就解析好了，没有异步解码
                "  ", // 空路径
            ),
        )

        assertTrue(plan.isEmpty())
    }

    /** 损坏的 XML 可能让父链成环，预暖清单不能因此死循环。 */
    @Test
    fun opacityWarmupPlanTerminatesOnCyclicParentChains() {
        val plan = planOpacityGatedImageWarmup(
            parentIndices = listOf(1, 0, 99),
            layerTypes = listOf("image", "group", "image"),
            rawOpacityValues = listOf("100", "100", "100"),
            rawPathValues = listOf("a.png", "", "b.png"),
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun renderPlanKeepsDocumentZOrderAndAppliesAncestorsOuterToInner() {
        val child = layer(id = "child", parentId = "inner")
        val overlay = layer(id = "overlay", parentId = null)
        val inner = layer(id = "inner", parentId = "outer", type = "group")
        val outer = layer(id = "outer", parentId = null, type = "group")

        val plan = buildWatchLayerRenderPlan(listOf(child, overlay, inner, outer))

        assertEquals(listOf(0, 1), plan.map(WatchLayerRenderItem::layerIndex))
        assertEquals(listOf(3, 2), plan.first().ancestorIndices)
        assertTrue(plan.last().ancestorIndices.isEmpty())
    }

    @Test
    fun layerGraphUsesFirstDuplicateIdAndReportsBoundedDepthDegradation() {
        val duplicateFirst = layer(id = "duplicate", parentId = null, type = "group")
        val duplicateSecond = layer(id = "duplicate", parentId = null, type = "group")
        val duplicateChild = layer(id = "duplicate-child", parentId = "duplicate")
        val deepGroups = (0..MAX_WATCH_PARENT_DEPTH + 2).map { index ->
            layer(
                id = "deep-$index",
                parentId = if (index == 0) null else "deep-${index - 1}",
                type = "group",
            )
        }
        val deepChild = layer(
            id = "deep-child",
            parentId = "deep-${deepGroups.lastIndex}",
        )
        val layers = listOf(duplicateFirst, duplicateSecond, duplicateChild) + deepGroups + deepChild

        val resolution = resolveWatchLayerGraph(layers)
        val plan = buildWatchLayerRenderPlanResult(layers)

        assertEquals(0, resolution.parentIndices[2])
        assertEquals(1, resolution.diagnostics.duplicateIdCount)
        assertTrue(resolution.diagnostics.depthLimitBreakCount > 0)
        assertTrue(
            plan.items
                .single { it.layerIndex == layers.lastIndex }
                .ancestorIndices.size <= MAX_WATCH_PARENT_DEPTH,
        )
        assertEquals(resolution.diagnostics, plan.diagnostics)
    }

    @Test
    fun renderPlanTruncatesExcessLayersWithAnExplicitDiagnostic() {
        val layers = List(MAX_WATCH_LAYER_COUNT + 5) { index ->
            layer(id = "layer-$index", parentId = null)
        }

        val plan = buildWatchLayerRenderPlanResult(layers)

        assertEquals(MAX_WATCH_LAYER_COUNT, plan.items.size)
        assertEquals(MAX_WATCH_LAYER_COUNT, plan.diagnostics.acceptedLayerCount)
        assertEquals(5, plan.diagnostics.truncatedLayerCount)
        assertTrue(plan.diagnostics.isDegraded)
    }

    @Test
    fun expressionTextPreservesZeroPaddingAndFallsBackAfterParseFailure() {
        val resolver: (String) -> WatchTagResolution = { tag ->
            if (tag == "dmz") WatchTagResolution.Resolved("05") else WatchTagResolution.Unsupported(tag)
        }

        assertEquals("05", WatchmakerExpressionEvaluator.evaluateText("{dmz}", resolver))
        assertEquals("(05 +", WatchmakerExpressionEvaluator.evaluateText("({dmz} +", resolver))
    }

    /**
     * 求值缓存不得改变语义。
     *
     * 缓存键是「tag 代入之后」的文本，所以 tag 变了就必须重算 —— 如果误按原始
     * 文本缓存，秒针、心率这类每帧变化的图层会永久停在第一帧的值上。
     */
    @Test
    fun expressionCacheStillRecomputesWhenTagValuesChange() {
        var level = 20
        val resolver: (String) -> WatchTagResolution = { tag ->
            if (tag == "bl") WatchTagResolution.Resolved(level.toString()) else WatchTagResolution.Unsupported(tag)
        }
        val expression = "{bl} * 2"

        assertEquals(40f, WatchmakerExpressionEvaluator.evaluateNumber(expression, -1f, resolver))
        level = 35
        assertEquals(70f, WatchmakerExpressionEvaluator.evaluateNumber(expression, -1f, resolver))
        // 回到旧值要能命中缓存，且拿到的仍是正确结果而不是陈旧的另一个值。
        level = 20
        assertEquals(40f, WatchmakerExpressionEvaluator.evaluateNumber(expression, -1f, resolver))
    }

    /**
     * 同一段文本在不同 scope 下必须各算各的。
     *
     * 缓存条目带着 scope 一起存并按引用校验；漏掉这层校验时，带脚本变量的表盘
     * 会读到另一块表盘的变量值。
     */
    @Test
    fun expressionCacheKeepsScopesApart() {
        val resolver: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) }
        val twelve = WatchExpressionScope.fromInitialScript("var_tipo_hora = 12")
        val twentyFour = WatchExpressionScope.fromInitialScript("var_tipo_hora = 24")
        val expression = "var_tipo_hora + 0"

        assertEquals(12f, WatchmakerExpressionEvaluator.evaluateNumber(expression, -1f, twelve, resolver))
        assertEquals(24f, WatchmakerExpressionEvaluator.evaluateNumber(expression, -1f, twentyFour, resolver))
        assertEquals(12f, WatchmakerExpressionEvaluator.evaluateNumber(expression, -1f, twelve, resolver))
    }

    /**
     * WFF 导出的天气图标链：靠 opacity 表达式在 N 个叠放的图标里只点亮一个。
     *
     * 这条链有 7.6K 字符 / 约 2500 token，旧的 4K + 512 上限直接把它判为不可
     * 求值，opacity 落回默认 100 —— 结果所有天气图标同时全亮叠在一起（老师
     * 实机反馈）。这里用同样的结构验证「只有匹配那条给出 100，其余给 0」。
     */
    @Test
    fun oversizedWeatherIconChainSelectsExactlyOneIcon() {
        val resolver: (String) -> WatchTagResolution = { tag ->
            if (tag == "wci") WatchTagResolution.Resolved("04d") else WatchTagResolution.Unsupported(tag)
        }
        // 与真实表盘同构：先把天气代码映射成序号，再判等点亮对应图标。
        val codes = listOf("01d", "01n", "02d", "02n", "03d", "03n", "04d", "04n", "09d", "10d")
        val selector = codes
            .mapIndexed { index, code -> "('{wci}' == '$code' and $index)" }
            .joinToString(" or ") + " or 0"

        codes.forEachIndexed { index, _ ->
            val opacity = "((($selector) == $index) and 100 or 0)"
            assertEquals(
                if (index == 6) 100f else 0f,
                WatchmakerExpressionEvaluator.evaluateNumber(opacity, -1f, resolver),
            )
        }
    }

    @Test
    fun dailyStepAccumulatorUsesDeltasResetsAtMidnightAndSurvivesSensorReset() {
        val first = accumulateDailySteps(previous = null, dayKey = 100L, rawCounter = 8_000)
        val walked = accumulateDailySteps(first, dayKey = 100L, rawCounter = 8_125)
        val afterReboot = accumulateDailySteps(walked, dayKey = 100L, rawCounter = 12)
        val afterRebootWalk = accumulateDailySteps(afterReboot, dayKey = 100L, rawCounter = 42)
        val nextDay = accumulateDailySteps(afterRebootWalk, dayKey = 101L, rawCounter = 80)

        assertEquals(0, first.steps)
        assertEquals(125, walked.steps)
        assertEquals(125, afterReboot.steps)
        assertEquals(155, afterRebootWalk.steps)
        assertEquals(0, nextDay.steps)
    }

    @Test
    fun installMetadataPreservesTheFirstInstallAndSortsNewestFirst() =
        withTemporaryDirectory { root ->
            val first = File(root, "first").apply { mkdirs() }
            val second = File(root, "second").apply { mkdirs() }

            assertEquals(1_000L, WatchInstallMetadata.readOrCreate(first, 1_000L))
            assertEquals(1_000L, WatchInstallMetadata.readOrCreate(first, 9_000L))
            assertEquals(2_000L, WatchInstallMetadata.readOrCreate(second, 2_000L))

            val order = orderWatchInstalls(
                listOf(
                    WatchInstallOrder("first", 1_000L, "A", "first"),
                    WatchInstallOrder("second", 2_000L, "B", "second"),
                ),
            )
            assertEquals(listOf("second", "first"), order)
        }

    /**
     * `{dw}` = 一年中的第几周，**不是**星期几。对齐 clox
     * `wf_tagdefs.lua:252` 的 `os.date("%W") + 1`：strftime 的 `%W` 以周一为
     * 周首、首个周一之前算第 00 周。期望值取自 GNU `date +%W` 再 +1。
     */
    @Test
    fun weekOfYearTagFollowsStrftimePercentWSemanticsNotCalendarLocale() {
        val snapshot = WatchDataSnapshot()
        val cases = listOf(
            // 2026-01-01 是周四，在首个周一之前 → %W=00 → {dw}=1
            Triple(Calendar.JANUARY, 1, "1"),
            // 2026-01-04 周日，仍属第 00 周
            Triple(Calendar.JANUARY, 4, "1"),
            // 2026-01-05 是当年第一个周一 → %W=01
            Triple(Calendar.JANUARY, 5, "2"),
            Triple(Calendar.JULY, 20, "30"),
            Triple(Calendar.AUGUST, 2, "31"),
            Triple(Calendar.DECEMBER, 31, "53"),
        )
        for ((month, day, expected) in cases) {
            val calendar = Calendar.getInstance().apply { clear(); set(2026, month, day, 12, 0, 0) }
            assertEquals(
                "dw@2026-${month + 1}-$day",
                expected,
                WatchmakerTagResolver.resolve("dw", calendar, snapshot).renderedValue,
            )
        }
        // locale 不能改变周序号：{dw} 是 C 库定义，不走 Calendar.WEEK_OF_YEAR。
        val august = Calendar.getInstance().apply { clear(); set(2026, Calendar.AUGUST, 2, 12, 0, 0) }
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.SIMPLIFIED_CHINESE)) {
            assertEquals(
                "31",
                WatchmakerTagResolver.resolve("dw", august, snapshot, locale = locale).renderedValue,
            )
        }
    }

    @Test
    fun daysInMonthTagCoversShortMonthsAndLeapFebruary() {
        val snapshot = WatchDataSnapshot()
        fun daysIn(year: Int, month: Int): String {
            val calendar = Calendar.getInstance().apply { clear(); set(year, month, 1, 8, 0, 0) }
            return WatchmakerTagResolver.resolve("ddim", calendar, snapshot).renderedValue
        }

        assertEquals("31", daysIn(2026, Calendar.AUGUST))
        assertEquals("30", daysIn(2026, Calendar.APRIL))
        assertEquals("28", daysIn(2026, Calendar.FEBRUARY))
        assertEquals("29", daysIn(2024, Calendar.FEBRUARY))
    }

    /**
     * `{ddw1_N}` 家族 = N 天后的星期名（clox `wf_tagdefs.lua:800-811`）。
     * 312 用满了 `{ddw1_1}`~`{ddw1_6}` 做未来一周预报行。
     */
    @Test
    fun futureWeekdayTagsShiftByDaysAndRejectOutOfRangeOffsets() {
        val monday = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JULY, 20, 10, 0, 0) // 周一
        }
        val snapshot = WatchDataSnapshot()

        // ddw = 短名，ddww = 全名，与不带 _N 的同名标签取名方式逐项一致。
        assertEquals(
            "Tue",
            WatchmakerTagResolver.resolve("ddw_1", monday, snapshot, locale = Locale.ENGLISH).renderedValue,
        )
        assertEquals(
            "Sunday",
            WatchmakerTagResolver.resolve("ddww_6", monday, snapshot, locale = Locale.ENGLISH).renderedValue,
        )
        // ddw2 = 短名截前两个字符，周一 + 5 天 = 周六 → "Sat".take(2)。
        assertEquals(
            "Sa",
            WatchmakerTagResolver.resolve("ddw2_5", monday, snapshot, locale = Locale.ENGLISH).renderedValue,
        )
        // 312 实际用到的六个：从周二一路推到周日。
        val expected = listOf("Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        for ((index, label) in expected.withIndex()) {
            val tag = "ddw1_${index + 1}"
            assertTrue(tag, WatchmakerTagResolver.isKnown(tag))
            // ddw1 是 NARROW_FORMAT，英文下只有首字母；这里断言它确实跟着日期走。
            val narrow = WatchmakerTagResolver
                .resolve(tag, monday, snapshot, locale = Locale.ENGLISH)
                .renderedValue
            assertTrue("$tag => $narrow", label.startsWith(narrow))
        }

        // 家族正则限 1..6（与 clox 的显式范围检查一致），越界仍是未知标签。
        assertFalse(WatchmakerTagResolver.isKnown("ddw1_0"))
        assertFalse(WatchmakerTagResolver.isKnown("ddw1_7"))
        assertTrue(
            WatchmakerTagResolver.resolve("ddw1_7", monday, snapshot) is WatchTagResolution.Unsupported,
        )
    }

    @Test
    fun deviceLocaleAndClockFormatTagsFollowSnapshotAndRequestedLocale() {
        val calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.AUGUST, 2, 9, 0, 0) }

        // {alangcode}/{alangreg} 跟随 resolve 的 locale 形参，与日期标签同源。
        assertEquals(
            "en",
            WatchmakerTagResolver
                .resolve("alangcode", calendar, WatchDataSnapshot(), locale = Locale.US)
                .renderedValue,
        )
        assertEquals(
            "US",
            WatchmakerTagResolver
                .resolve("alangreg", calendar, WatchDataSnapshot(), locale = Locale.US)
                .renderedValue,
        )

        // {d24h} 由 provider 侧读 DateFormat.is24HourFormat 塞进快照，渲染成 true/false。
        assertEquals(
            "true",
            WatchmakerTagResolver
                .resolve("d24h", calendar, WatchDataSnapshot(is24Hour = true))
                .renderedValue,
        )
        assertEquals(
            "false",
            WatchmakerTagResolver
                .resolve("d24h", calendar, WatchDataSnapshot(is24Hour = false))
                .renderedValue,
        )
    }

    /**
     * `{cnc}`（通知数）与 `{stsc}`/`{sscp}`（步数目标与完成度）必须在无数据时
     * 给出**数值**而不是 nil：表达式里 `{cnc} > 0` 代入 nil 会让整条算式失效，
     * opacity 回落默认 100（历史事故：九个天气图标同时全亮）。
     */
    @Test
    fun notificationAndStepGoalTagsStayNumericWithoutHostData() {
        val calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.AUGUST, 2, 9, 0, 0) }

        assertEquals(
            "0",
            WatchmakerTagResolver.resolve("cnc", calendar, WatchDataSnapshot()).renderedValue,
        )
        assertEquals(
            "3",
            WatchmakerTagResolver
                .resolve("cnc", calendar, WatchDataSnapshot(notificationCount = 3))
                .renderedValue,
        )

        // 没有用户档案时用规范默认目标 10000（clox watchface_bridge.lua:127 同值）。
        assertEquals(
            "10000",
            WatchmakerTagResolver.resolve("stsc", calendar, WatchDataSnapshot()).renderedValue,
        )
        assertEquals(
            "0",
            WatchmakerTagResolver.resolve("sscp", calendar, WatchDataSnapshot()).renderedValue,
        )
        val walking = WatchDataSnapshot(steps = 4_500, stepGoal = 9_000)
        assertEquals("9000", WatchmakerTagResolver.resolve("stsc", calendar, walking).renderedValue)
        assertEquals("50", WatchmakerTagResolver.resolve("sscp", calendar, walking).renderedValue)
        // 目标为 0 / 负数时不能除零，回落到默认目标。
        assertEquals(
            "10000",
            WatchmakerTagResolver
                .resolve("stsc", calendar, WatchDataSnapshot(steps = 100, stepGoal = 0))
                .renderedValue,
        )

        // 表达式里参与算术，不被替换成 nil。
        assertEquals(
            5f,
            WatchmakerExpressionEvaluator.evaluateNumber("{cnc} + 5", -1f) { tag ->
                WatchmakerTagResolver.resolve(tag, calendar, WatchDataSnapshot())
            },
            0.001f,
        )
    }

    @Test
    fun sunriseSunsetTagsRenderWeatherClockTimes() {
        val calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.AUGUST, 2, 9, 0, 0) }
        val snapshot = WatchDataSnapshot(
            weather = WatchWeatherSnapshot(sunriseText = "05:41", sunsetText = "19:12"),
        )

        assertEquals("05:41", WatchmakerTagResolver.resolve("wsr", calendar, snapshot).renderedValue)
        assertEquals("19:12", WatchmakerTagResolver.resolve("wss", calendar, snapshot).renderedValue)
        // 没有天气数据时回落到 Unavailable（渲染 "--"），而不是空串。
        val missing = WatchmakerTagResolver.resolve("wsr", calendar, WatchDataSnapshot())
        assertTrue(missing is WatchTagResolution.Unavailable)
        assertEquals("--", missing.renderedValue)
    }

    /**
     * `{wmp}` 必须是 0..9 的**整数**相位序号：表盘写 `{wmp} == 5` 判满月，
     * 推小数永远不匹配（clox `wf_tagdefs.lua:329-334` 记了这个坑）。
     * 基准取 2000-01-06 新月、朔望月 29.53058867 天。
     */
    @Test
    fun moonPhaseTagIsAnIntegerIndexDrivenOnlyByTheDate() {
        val snapshot = WatchDataSnapshot()
        fun phase(year: Int, month: Int, day: Int): String {
            val calendar = Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0, 0) }
            return WatchmakerTagResolver.resolve("wmp", calendar, snapshot).renderedValue
        }

        assertEquals("0", phase(2000, Calendar.JANUARY, 6)) // 基准新月
        assertEquals("2", phase(2026, Calendar.JULY, 20))
        assertEquals("6", phase(2026, Calendar.AUGUST, 2))
        assertEquals("0", phase(2026, Calendar.AUGUST, 13)) // 下一次朔回到 0

        // 全年每一天都必须落在 0..9 且是整数，不能出现小数或越界。
        val walker = Calendar.getInstance().apply { clear(); set(2026, Calendar.JANUARY, 1, 12, 0, 0) }
        repeat(365) {
            val value = WatchmakerTagResolver.resolve("wmp", walker, snapshot).renderedValue
            val index = requireNotNull(value.toIntOrNull()) { "wmp 不是整数: $value" }
            assertTrue("wmp 越界: $index", index in 0..9)
            walker.add(Calendar.DAY_OF_MONTH, 1)
        }

        // 同一天的不同时刻必须给出同一个相位（纯日期函数，不随时钟走）。
        assertEquals(phase(2026, Calendar.AUGUST, 2), run {
            val night = Calendar.getInstance().apply { clear(); set(2026, Calendar.AUGUST, 2, 23, 59, 0) }
            WatchmakerTagResolver.resolve("wmp", night, snapshot).renderedValue
        })
    }

    @Test
    fun dayOfYearTagMatchesCloxOneBasedCalendarDay() {
        val snapshot = WatchDataSnapshot()
        fun dayOfYear(year: Int, month: Int, day: Int): String {
            val calendar = Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0, 0) }
            return WatchmakerTagResolver.resolve("ddy", calendar, snapshot).renderedValue
        }

        assertEquals("1", dayOfYear(2026, Calendar.JANUARY, 1))
        assertEquals("225", dayOfYear(2026, Calendar.AUGUST, 13))
        assertEquals("366", dayOfYear(2024, Calendar.DECEMBER, 31))
    }

    /** 新补的这批 tag 必须全部进 builtIn 词表，否则编译期就被判 Unsupported。 */
    @Test
    fun newlyAddedTagsAreRecognisedAsBuiltIn() {
        val tags = listOf(
            "ddy", "dw", "ddim", "alangcode", "alangreg", "d24h", "cnc",
            "stsc", "sscp", "wsr", "wss", "wmp",
            "ddw_1", "ddww_2", "ddw1_3", "ddw2_6",
        )
        for (tag in tags) {
            assertTrue(tag, WatchmakerTagResolver.isKnown(tag))
        }
    }

    private inline fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("flue-watch-runtime-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val SFNT_HEADER = byteArrayOf(0, 1, 0, 0)

        fun layer(id: String, parentId: String?, type: String = "image") = WatchmakerLayer(
            id = id,
            type = type,
            parentId = parentId,
            attributes = emptyMap(),
            children = emptyList(),
        )
    }
}
