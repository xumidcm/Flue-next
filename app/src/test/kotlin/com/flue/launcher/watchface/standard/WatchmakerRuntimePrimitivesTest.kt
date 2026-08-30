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

class WatchmakerRuntimePrimitivesTest {

    @Test
    fun `community tap actions are recognized`() {
        assertTrue(WatchTapActionParser.parse("battery_status") is WatchTapAction.BatteryStatus)
        assertTrue(WatchTapActionParser.parse("open_settings") is WatchTapAction.OpenSettings)
        listOf("w_camera", "w_clock", "browser", "w_music", "w_calculator").forEach { raw ->
            assertEquals(WatchTapAction.Named(raw), WatchTapActionParser.parse(raw))
        }
        assertEquals(
            WatchTapAction.DeepLink("https://example.com/watch"),
            WatchTapActionParser.parse("w_link:https://example.com/watch"),
        )
    }

    @Test
    fun `the upstream configurable action names are all recognized`() {
        // WM_TAP_ACTIONS.md §3 那 26 个名字。漏一个就会掉进 Unsupported，
        // 点一下弹「表盘点击动作不可用」—— 老师报的「很多 Action 不支持」有一半是这个。
        // widget_* / w_fit / w_heart_rate 三个在 Flue 里有更具体的实现，所以单列。
        listOf(
            "tap_launcher", "w_agenda", "w_compass", "w_keep", "m_email", "m_calendar",
            "voice_search", "w_contacts", "w_sports", "w_gallery", "w_messaging",
            "w_camera", "w_dialer", "w_music", "w_clock", "w_calculator",
            "w_payment", "w_map", "browser", "flashlight", "socialmedia",
        ).forEach { raw ->
            assertEquals(raw, WatchTapAction.Named(raw), WatchTapActionParser.parse(raw))
        }
        assertEquals(WatchTapAction.Calendar, WatchTapActionParser.parse("widget_calendar"))
        assertEquals(WatchTapAction.Weather, WatchTapActionParser.parse("widget_weather"))
        assertEquals(WatchTapAction.HeartRate, WatchTapActionParser.parse("widget_health"))
        assertEquals(WatchTapAction.HeartRate, WatchTapActionParser.parse("w_fit"))
    }

    /**
     * 被短路成强类型的那几个名字，**仍然必须**算「用户可配置」。
     *
     * 这是老师报的「点右上角天气组件提示无法打开目标应用」的判据：`widget_weather`
     * 会被解析器短路成 [WatchTapAction.Weather]，运行时如果只看解析结果，就会直接去拼
     * `CATEGORY_APP_WEATHER` —— 手表上没人声明这个类目，于是设置里明明指了应用也打不开。
     * 渲染层靠 [WatchTapActionParser.isConfigurable] 判断「要不要先查用户映射表」，
     * 所以这几个名字一旦从可配置名单里掉出去，那个 bug 就会原样复发。
     */
    @Test
    fun `short circuited widget actions stay user configurable`() {
        listOf(
            "widget_weather", "widget_calendar", "widget_health",
            "w_fit", "w_heart_rate", "w_weather", "m_weather",
        ).forEach { raw ->
            assertTrue(raw, WatchTapActionParser.isConfigurable(raw))
            // 大小写与空白都要吃得下：属性值直接来自表盘 XML，作者怎么写的都有。
            assertTrue(raw, WatchTapActionParser.isConfigurable("  ${raw.uppercase(Locale.ROOT)} "))
        }
        // 内建动作不可配置：它们不该被「先查映射表」那一步截走
        // （上游的 dispatch 顺序是 built-in first, then configurable）。
        listOf("media_play_pause", "vol_up", "vol_down", "battery_status", "open_settings")
            .forEach { raw -> assertFalse(raw, WatchTapActionParser.isConfigurable(raw)) }
        assertFalse(WatchTapActionParser.isConfigurable(null))
        assertFalse(WatchTapActionParser.isConfigurable("   "))
    }

    @Test
    fun `built in media and volume actions parse`() {
        assertEquals(
            WatchTapAction.Media(WatchMediaKey.PLAY_PAUSE),
            WatchTapActionParser.parse("media_play_pause"),
        )
        assertEquals(
            WatchTapAction.Media(WatchMediaKey.PLAY),
            WatchTapActionParser.parse("MEDIA_PLAY"),
        )
        assertEquals(
            WatchTapAction.Media(WatchMediaKey.PAUSE),
            WatchTapActionParser.parse("media_pause"),
        )
        assertEquals(
            WatchTapAction.Media(WatchMediaKey.NEXT),
            WatchTapActionParser.parse(" media_next "),
        )
        assertEquals(
            WatchTapAction.Media(WatchMediaKey.PREVIOUS),
            WatchTapActionParser.parse("media_prev"),
        )
        assertEquals(WatchTapAction.Volume(raise = true), WatchTapActionParser.parse("vol_up"))
        assertEquals(WatchTapAction.Volume(raise = false), WatchTapActionParser.parse("vol_down"))
    }

    @Test
    fun `set alarm carries a validated clock time`() {
        assertEquals(WatchTapAction.SetAlarm(7, 30), WatchTapActionParser.parse("set_alarm_07:30"))
        assertEquals(WatchTapAction.SetAlarm(23, 59), WatchTapActionParser.parse("set_alarm_23:59"))
        assertEquals(WatchTapAction.SetAlarm(0, 0), WatchTapActionParser.parse("set_alarm_0:0"))
        // 脏钟点不能透传给 AlarmClock 的 extra —— 解析期就打回。
        listOf("set_alarm_24:00", "set_alarm_07:60", "set_alarm_", "set_alarm_7", "set_alarm_a:b")
            .forEach { raw ->
                assertTrue(raw, WatchTapActionParser.parse(raw) is WatchTapAction.Unsupported)
            }
    }

    @Test
    fun `color switch actions parse with upstream directions`() {
        assertEquals(
            WatchTapAction.ColorSwitch(direction = 1),
            WatchTapActionParser.parse("color_switch_next"),
        )
        assertEquals(
            WatchTapAction.ColorSwitch(direction = -1),
            WatchTapActionParser.parse(" COLOR_SWITCH_PREV "),
        )
        assertEquals(
            WatchTapAction.ColorSwitch(direction = 1),
            WatchTapActionParser.parse("color_switch_select"),
        )
    }

    @Test
    fun `tap selection uses the nearest eligible centre instead of layer order`() {
        val candidates = listOf(
            WatchTapHitCandidate(containsPoint = true, centerDistance = 2f, opacity = 0f),
            WatchTapHitCandidate(containsPoint = true, centerDistance = 5f, opacity = 0f),
        )

        assertEquals(0, selectNearestWatchTapHitIndex(candidates))
    }

    @Test
    fun `tap selection excludes only opacity minus one and keeps the 50 unit grace radius`() {
        val candidates = listOf(
            WatchTapHitCandidate(containsPoint = true, centerDistance = 1f, opacity = -1f),
            WatchTapHitCandidate(containsPoint = false, centerDistance = 49.999f, opacity = 0f),
            WatchTapHitCandidate(containsPoint = false, centerDistance = 50f, opacity = 0f),
        )

        assertEquals(1, selectNearestWatchTapHitIndex(candidates))
        assertNull(
            selectNearestWatchTapHitIndex(
                listOf(
                    WatchTapHitCandidate(false, 1f, -1f),
                    WatchTapHitCandidate(false, 50f, 0f),
                ),
            ),
        )
    }

    @Test
    fun `color switch palette cycles through 41 colors and restores a persisted index`() {
        assertEquals(41, WATCH_COLOR_SWITCH_PALETTE.size)
        assertEquals(
            WatchColorSwitchSelection(index = 1, color = "5987b2"),
            selectWatchColor("abcdef", currentIndex = 0, direction = 1),
        )
        assertEquals(
            WatchColorSwitchSelection(index = 41, color = "ffffff"),
            selectWatchColor("abcdef", currentIndex = 0, direction = -1),
        )
        assertEquals(
            WatchColorSwitchSelection(index = 0, color = "abcdef"),
            selectWatchColor("abcdef", currentIndex = 41, direction = 1),
        )
        assertEquals(
            WatchColorSwitchSelection(index = 13, color = "306a9f"),
            selectWatchColor("abcdef", currentIndex = 13, direction = 0),
        )
    }
    @Test
    fun animatedImageFallbackIdentityChangesWhenReusedLayerGetsNewMaterial() {
        val imported = WatchImageFallbackKey(
            layerCacheKey = "draft\u00000",
            materialExpression = "images/frame_0001.jpg`images/frame_0002.jpg",
            variant = "day",
        )
        val blankReplacement = WatchImageFallbackKey(
            layerCacheKey = "draft\u00000",
            materialExpression = "",
            variant = "day",
        )

        assertNotEquals(imported, blankReplacement)
        assertEquals(imported, imported.copy())
    }

    @Test
    fun gifFramesRespectDelayBoundaryAndLoop() {
        val paths = "images/a.png`images/b.png`images/c.png"

        assertEquals(0, selectWatchGifFrame(paths, 99, 100)?.index)
        assertEquals("images/b.png", selectWatchGifFrame(paths, 100, 100)?.path)
        assertEquals(0, selectWatchGifFrame(paths, 300, 100)?.index)
        assertEquals(1, selectWatchGifFrame(paths, 1, 0)?.index)
        assertEquals(0, selectWatchGifFrame(paths, 49)?.index)
        assertEquals(1, selectWatchGifFrame(paths, 50)?.index)
        assertEquals(
            MAX_WATCH_GIF_FRAME_PATHS,
            parseWatchGifPaths(
                (0..MAX_WATCH_GIF_FRAME_PATHS + 10).joinToString("`") { "images/$it.png" },
            ).size,
        )
    }

    @Test
    fun editorVideoSequencesAreNotTruncatedAtTheLegacy128FrameLimit() {
        val paths = (0 until 256).joinToString("`") { index ->
            "images/frame_${index.toString().padStart(5, '0')}.jpg"
        }

        val parsed = parseWatchGifPaths(paths)

        assertEquals(256, parsed.size)
        assertEquals("images/frame_00255.jpg", parsed.last())
        assertEquals(255, watchGifFrameIndex(parsed.size, 25_500L, 100L))
        assertEquals(0, watchGifFrameIndex(parsed.size, 25_600L, 100L))
    }

    @Test
    fun galleryFramesAcceptProviderAndEditorListsAndLoopByDurOnSeconds() {
        assertEquals(
            listOf("gallery/a.jpg", "gallery/b.jpg", "gallery/c.jpg"),
            parseWatchGalleryPaths("gallery/a.jpg|gallery/b.jpg`gallery/c.jpg"),
        )
        assertEquals(5_000L, watchGalleryFrameDurationMs(0f))
        assertEquals(250L, watchGalleryFrameDurationMs(0.25f))
        assertEquals(0, watchGalleryFrameIndex(3, elapsedMs = 4_999L, durationOnSeconds = 5f))
        assertEquals(1, watchGalleryFrameIndex(3, elapsedMs = 5_000L, durationOnSeconds = 5f))
        assertEquals(0, watchGalleryFrameIndex(3, elapsedMs = 15_000L, durationOnSeconds = 5f))
        assertTrue(watchLayerRequiresFrameTicks("gallery_2d", emptyMap()))
    }

    @Test
    fun ordinaryImageLayersWithGifContentStillRequestFrameTicks() {
        assertTrue(watchLayerRequiresFrameTicks("image", mapOf("path" to "images/background.gif")))
        assertTrue(watchLayerRequiresFrameTicks("image", mapOf("path" to "a.png`b.png")))
        assertTrue(watchLayerRequiresFrameTicks("image_gif", mapOf("path" to "frame.png")))
        assertFalse(watchLayerRequiresFrameTicks("image", mapOf("path" to "background.png")))
    }

    @Test
    fun legacyCounterTagsRepeatReverseAndRespectDelay() {
        assertEquals("50", resolveWatchCounterTag("c_0_100_2_st", 1_000L))
        assertEquals("100", resolveWatchCounterTag("c_0_100_2_st", 8_000L))
        assertEquals("25", resolveWatchCounterTag("c_0_100_2_rp", 2_500L))
        assertEquals("75", resolveWatchCounterTag("c_0_100_2_rv", 2_500L))
        assertEquals("0", resolveWatchCounterTag("c_0_100_2_rv_2", 1_000L))
        assertEquals("50", resolveWatchCounterTag("c_0_100_2_rv_2", 3_000L))
        assertEquals("1.235", resolveWatchCounterTag("c_elapsed", 1_235L))
        assertNull(resolveWatchCounterTag("c_bad", 1_000L))

        assertTrue(isWatchCounterTag("c_-20.5_20.5_3_rv"))
        assertTrue(watchCounterRequiresFrameTicks("{c_-20.5_20.5_3_rv}"))
        assertTrue(WatchmakerTagResolver.requiresFastTicks("{c_0_100_2_rp}"))
        assertFalse(watchCounterRequiresFrameTicks("{dh24}:{dmz}"))
    }

    @Test
    fun slideTextAnimationMovesInAndLoopsWithoutAHiddenBlackFrame() {
        val entering = watchTextAnimationFrame(
            animIn = "SlideLeft",
            animOut = "SlideLeft",
            durationInSeconds = 1f,
            durationOnSeconds = 1f,
            durationOutSeconds = 1f,
            durationOffSeconds = 0.5f,
            delayStartSeconds = 0f,
            repeatCount = -1,
            restartOnLoad = true,
            elapsedMs = 0L,
        )
        assertEquals(WatchTextAnimationPhase.ENTERING, entering.phase)
        assertTrue(entering.translationX > 0f)

        val visible = watchTextAnimationFrame(
            "SlideLeft", "SlideLeft", 1f, 1f, 1f, 0.5f, 0f, -1, true, 1_500L,
        )
        assertEquals(WatchTextAnimationPhase.VISIBLE, visible.phase)

        val nextCycle = watchTextAnimationFrame(
            "SlideLeft", "SlideLeft", 1f, 1f, 1f, 0.5f, 0f, -1, true, 3_500L,
        )
        assertEquals(WatchTextAnimationPhase.ENTERING, nextCycle.phase)
    }

    @Test
    fun navigationExitAnimationIsOneShotAndAllowsNone() {
        val exiting = watchTextExitAnimationFrame("SlideRight", 1f, 250L)
        assertEquals(WatchTextAnimationPhase.EXITING, exiting.phase)
        assertTrue(exiting.translationX > 0f)

        val finished = watchTextExitAnimationFrame("SlideRight", 1f, 1_000L)
        assertEquals(WatchTextAnimationPhase.HIDDEN, finished.phase)

        val disabled = watchTextExitAnimationFrame("", 1f, 1_000L)
        assertEquals(WatchTextAnimationPhase.VISIBLE, disabled.phase)
    }

    @Test
    fun conditionalImageAndChartInputsAreBoundedAndDeterministic() {
        assertEquals(3 to 2, parseWatchGrid("3x2"))
        assertEquals(15 to 15, parseWatchGrid("15x15"))
        assertEquals(1 to 1, parseWatchGrid("garbage"))
        assertEquals(0, watchSpriteIndex(1f, cellCount = 6))
        assertEquals(5, watchSpriteIndex(99f, cellCount = 6))
        assertEquals(0, watchSpriteIndex(null, cellCount = 6))
        assertEquals(213, watchSpriteIndex(214f, cellCount = 225))

        assertEquals(
            listOf(1f, 2.5f, 3f, 4f),
            parseWatchChartValues("[1; 2.5, 3|4]"),
        )
        assertEquals(256, parseWatchChartValues((1..300).joinToString(",")).size)
    }

    @Test
    fun chartParserStopsAsSoonAsThePointLimitIsReached() {
        val guarded = object : CharSequence {
            private val readablePrefix = "1,2,"

            override val length: Int = 1_000_000

            override fun get(index: Int): Char {
                check(index < readablePrefix.length) {
                    "parser read beyond the two requested points at index $index"
                }
                return readablePrefix[index]
            }

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                readablePrefix.subSequence(startIndex, endIndex)
        }

        assertEquals(
            listOf(1f, 2f),
            parseWatchChartValues(guarded, maxPoints = 2),
        )
    }

    @Test
    fun chartParserCapsInputAndSkipsUnboundedTokens() {
        assertEquals(
            listOf(1f, 2f),
            parseWatchChartValues("1,2,999999", maxPoints = 8, maxInputChars = 4),
        )
        assertEquals(
            listOf(3f),
            parseWatchChartValues(
                "${"9".repeat(MAX_WATCH_CHART_TOKEN_CHARS + 1)},3",
                maxPoints = 8,
            ),
        )
    }

    @Test
    fun boundedStringCacheUsesLruAndNeverStoresOversizedKeys() {
        val cache = WatchStringLruCache<String>(
            maximumEntries = 2,
            maximumKeyLength = 8,
        )
        cache.getOrPut("a") { "A" }
        cache.getOrPut("b") { "B" }
        assertEquals("A", cache.getOrPut("a") { error("cached value was not reused") })
        cache.getOrPut("c") { "C" }

        assertTrue(cache.containsKey("a"))
        assertFalse(cache.containsKey("b"))
        assertTrue(cache.containsKey("c"))
        assertEquals(2, cache.size)

        var oversizedFactoryCalls = 0
        repeat(2) {
            assertEquals(
                "oversized",
                cache.getOrPut("key-is-too-long") {
                    oversizedFactoryCalls++
                    "oversized"
                },
            )
        }
        assertEquals(2, oversizedFactoryCalls)
        assertFalse(cache.containsKey("key-is-too-long"))
        assertEquals(2, cache.size)
    }

    @Test
    fun boundedStringCacheKeepsMissesBoundedAndClearsOnReset() {
        val cache = WatchStringLruCache<String>(
            maximumEntries = 2,
            maximumKeyLength = 32,
        )
        var missFactoryCalls = 0

        repeat(2) {
            assertNull(
                cache.getOrPut("missing") {
                    missFactoryCalls++
                    null
                },
            )
        }
        assertEquals(1, missFactoryCalls)
        assertEquals(1, cache.size)

        cache.getOrPut("hit") { "value" }
        cache.getOrPut("newer") { "value-2" }
        assertFalse(cache.containsKey("missing"))
        assertEquals(2, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun bitmapFontParserKeepsPagesGlyphsAndKerningWithinBounds() {
        val font = parseWatchBitmapFont(
            """
            info face="Digits" size=32
            common lineHeight=36 base=29 scaleW=256 scaleH=256 pages=1
            page id=0 file="digits.png"
            chars count=2
            char id=65 x=0 y=0 width=10 height=20 xoffset=1 yoffset=3 xadvance=12 page=0 chnl=15
            char id=86 x=10 y=0 width=9 height=20 xoffset=0 yoffset=3 xadvance=11 page=0 chnl=15
            kernings count=1
            kerning first=65 second=86 amount=-2
            """.trimIndent(),
        )!!

        assertEquals("Digits", font.face)
        assertEquals("digits.png", font.pages.single())
        assertEquals(10, font.glyphs.getValue(65).width)
        assertEquals(-2, font.kerning(65, 86))
        assertEquals(21, font.measureAdvance("AV"))
    }

    @Test
    fun bitmapFontTextUsesTheUppercaseGlyphContract() {
        val font = parseWatchBitmapFont(
            """
            info face="Uppercase" size=32
            common lineHeight=36 base=29 scaleW=256 scaleH=256 pages=1
            page id=0 file="uppercase.png"
            chars count=2
            char id=65 x=0 y=0 width=10 height=20 xoffset=1 yoffset=3 xadvance=12 page=0 chnl=15
            char id=86 x=10 y=0 width=9 height=20 xoffset=0 yoffset=3 xadvance=11 page=0 chnl=15
            kernings count=1
            kerning first=65 second=86 amount=-2
            """.trimIndent(),
        )!!

        assertEquals(21, font.measureAdvance("av"))
    }

    @Test
    fun bitmapFontGlyphSourceRectTracksDecodedAtlasSampling() {
        val finalGlyph = WatchBitmapGlyph(
            id = 45,
            x = 1_860,
            y = 0,
            width = 124,
            height = 180,
            xOffset = 0,
            yOffset = 0,
            xAdvance = 124,
            page = 0,
        )

        assertEquals(
            WatchBitmapSourceRect(left = 930, top = 0, right = 992, bottom = 90),
            mapWatchBitmapGlyphSourceRect(
                glyph = finalGlyph,
                atlasWidth = 1_984,
                atlasHeight = 180,
                bitmapWidth = 992,
                bitmapHeight = 90,
            ),
        )
        assertEquals(
            WatchBitmapSourceRect(left = 1_860, top = 0, right = 1_984, bottom = 180),
            mapWatchBitmapGlyphSourceRect(
                glyph = finalGlyph,
                atlasWidth = 1_984,
                atlasHeight = 180,
                bitmapWidth = 1_984,
                bitmapHeight = 180,
            ),
        )
    }

    @Test
    fun bitmapFontGlyphSourceRectRejectsInvalidAtlasBounds() {
        val invalidGlyph = WatchBitmapGlyph(
            id = 48,
            x = 1_900,
            y = 0,
            width = 124,
            height = 180,
            xOffset = 0,
            yOffset = 0,
            xAdvance = 124,
            page = 0,
        )

        assertNull(
            mapWatchBitmapGlyphSourceRect(
                glyph = invalidGlyph,
                atlasWidth = 1_984,
                atlasHeight = 180,
                bitmapWidth = 992,
                bitmapHeight = 90,
            ),
        )
    }

    @Test
    fun svgSubsetRejectsActiveContentAndCapsPaths() {
        val safe = parseWatchSvgDocument(
            """
            <svg viewBox="0 0 10 20" width="10" height="20">
              <path d="M0 0 L10 20" fill="#fff"/>
            </svg>
            """.trimIndent(),
        )
        assertEquals(listOf(0f, 0f, 10f, 20f), safe?.viewBox)
        assertEquals(1, safe?.paths?.size)

        assertTrue(
            parseWatchSvgDocument(
                """<!DOCTYPE svg><svg><path d="M0 0"/></svg>""",
            ) == null,
        )
        val tooMany = buildString {
            append("<svg>")
            repeat(MAX_WATCH_SVG_PATHS + 10) { append("<path d=\"M0 0\"/>") }
            append("</svg>")
        }
        assertEquals(MAX_WATCH_SVG_PATHS, parseWatchSvgDocument(tooMany)?.paths?.size)
    }

    @Test
    fun svgPathsAreMaterializedOnceDuringPreparation() {
        val document = WatchSvgDocument(
            viewBox = listOf(0f, 0f, 10f, 20f),
            width = 10f,
            height = 20f,
            paths = listOf(
                WatchSvgPathData(pathData = "M0 0", fill = "#fff"),
                WatchSvgPathData(pathData = "invalid", fill = null),
            ),
        )
        var parserCalls = 0

        val prepared = prepareWatchSvgDocument(document) { pathData ->
            parserCalls++
            pathData.takeUnless { it == "invalid" }?.let { "parsed:$it" }
        }

        assertEquals(2, parserCalls)
        assertEquals(listOf("parsed:M0 0"), prepared?.paths?.map { it.path })
        assertEquals("#fff", prepared?.paths?.single()?.fill)
        assertEquals(2, parserCalls)
    }

    @Test
    fun seriesBuildsStableValuesAndCurrentOffset() {
        val now = calendar(2026, Calendar.JULY, 24, 18, 5, 9)

        val hour = WatchSeries.items("dh24z", now, "m")
        val minute = WatchSeries.items("dmz", now, "f")

        assertEquals("18", hour.single { it.isCurrent }.value)
        assertEquals(-1, hour.first { it.value == "17" }.offset)
        assertEquals("05", minute.first().value)
        assertTrue(minute.all { it.offset >= 0 })
    }

    @Test
    fun calendarUsesMondayStartAndHandlesLeapMonth() {
        val july = calendar(2026, Calendar.JULY, 24, 12, 0, 0)
        val leapFebruary = calendar(2024, Calendar.FEBRUARY, 29, 12, 0, 0)

        val julyCells = WatchCalendarGrid.cells(july, dowStart = 1)
        val leapCells = WatchCalendarGrid.cells(leapFebruary, dowStart = 1)

        assertEquals(2, julyCells.first().column)
        assertEquals(31, julyCells.size)
        assertTrue(julyCells.single { it.day == 24 }.isToday)
        assertTrue(julyCells.single { it.day == 23 }.isOutside)
        assertEquals(29, leapCells.size)
        assertEquals(7, WatchCalendarGrid.weekdayLabels(1, 1, Locale.ENGLISH).size)
    }

    @Test
    fun tapActionsRecognizeSandboxedFaceScriptsAndBuiltInFamilies() {
        assertEquals(WatchTapAction.Calendar, WatchTapActionParser.parse("widget_calendar_month"))
        assertEquals(WatchTapAction.Weather, WatchTapActionParser.parse("m_update_weather_now"))
        assertEquals(WatchTapAction.HeartRate, WatchTapActionParser.parse("w_heart_rate"))
        assertEquals(WatchTapAction.HeartRate, WatchTapActionParser.parse("widget_health"))
        assertEquals(WatchTapAction.HeartRate, WatchTapActionParser.parse("w_fit"))
        assertEquals(
            WatchTapAction.Script("do_anything()"),
            WatchTapActionParser.parse("script:do_anything()"),
        )
        // w_app: 现在解析为 AppLaunch 而非 Unsupported
        val appLaunch = WatchTapActionParser.parse("w_app:com.example/.MainActivity")
        assertTrue(appLaunch is WatchTapAction.AppLaunch)
        assertEquals("com.example", (appLaunch as WatchTapAction.AppLaunch).packageName)
        assertEquals("com.example.MainActivity", appLaunch.activityClass)
        val pkgOnly = WatchTapActionParser.parse("m_app:com.example.pkg")
        assertTrue(pkgOnly is WatchTapAction.AppLaunch)
        assertEquals("com.example.pkg", (pkgOnly as WatchTapAction.AppLaunch).packageName)
        assertNull(pkgOnly.activityClass)
        assertTrue(WatchTapActionParser.parse("https://example.com") is WatchTapAction.Unsupported)
    }

    @Test
    fun weatherSnapshotResolvesSupportedFieldsAndKeepsMissingDataUnavailable() {
        val now = calendar(2026, Calendar.JULY, 24, 12, 0, 0)
        val snapshot = WatchDataSnapshot(
            weatherTemperature = "26",
            weatherCondition = "Cloudy",
            weatherIcon = "cloudy",
            weatherHumidity = 72,
        )

        assertEquals("26", WatchmakerTagResolver.resolve("wtemp", now, snapshot).renderedValue)
        assertEquals("cloudy", WatchmakerTagResolver.resolve("wicon", now, snapshot).renderedValue)
        assertEquals("72", WatchmakerTagResolver.resolve("whum", now, snapshot).renderedValue)
        assertTrue(WatchmakerTagResolver.resolve("dnnn", now, snapshot) is WatchTagResolution.Resolved)
        assertTrue(WatchmakerTagResolver.resolve("ddw", now, snapshot) is WatchTagResolution.Resolved)
        assertTrue(WatchmakerTagResolver.resolve("wloc", now, snapshot) is WatchTagResolution.Unavailable)
    }

    @Test
    fun parserReportsOnlyStillUnimplementedLayerFamilies() {
        val root = Files.createTempDirectory("flue-runtime-primitives").toFile()
        try {
            val xml = File(root, "watch.xml").apply {
                writeText(
                    """
                    <Watch name="Runtime primitives">
                      <Layer type="image_gif" path="a.png`b.png" />
                      <Layer type="gif" path="animated.gif" />
                      <Layer type="image_tap" tap_action="widget_calendar" />
                      <Layer type="series" series="dh24z" />
                      <Layer type="calendar" />
                      <Layer type="video" />
                      <Layer type="map" />
                    </Watch>
                    """.trimIndent(),
                )
            }

            // video 已由 Stage 的 TextureView 分段合成真正播放，只剩 map 仍需
            // 一个实时地图桥。
            assertEquals(setOf("map"), WatchmakerDefinitionParser().parse(xml).unsupportedLayerTypes)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun calendar(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Calendar = Calendar.getInstance().apply {
        clear()
        set(year, month, day, hour, minute, second)
    }
}

/**
 * 帧序列预取目标 —— 「按 tick 时钟推算，而不是当前帧 +1」。
 *
 * 这一组守的是 `-_1.zip`（600 帧 JPG、`gif_delay=17`）「拨两下停一下」的根因：
 * tick 固定 33ms，帧号却按 17ms 走，预取写死 +1 就永远暖错帧。
 */
class FrameSequencePrefetchTest {

    private fun prefetch(
        frameCount: Int,
        elapsedMs: Long,
        frameDelayMs: Long,
        lookaheadTicks: Int = 2,
    ): List<Int> = frameSequencePrefetchIndices(
        frameCount = frameCount,
        currentIndex = watchGifFrameIndex(frameCount, elapsedMs, frameDelayMs),
        elapsedMs = elapsedMs,
        frameDelayMs = frameDelayMs,
        lookaheadTicks = lookaheadTicks,
    )

    @Test
    fun `delay 小于 tick 时预取真正会落到的帧而不是相邻帧`() {
        // 600 帧 / 17ms：一拍前进 ⌈33/17⌉ ≈ 2 帧。
        // elapsed=0 时下两拍分别落在 33/17=1、66/17=3，旧的「+1」实现永远暖不到 3。
        assertEquals(listOf(1, 3), prefetch(frameCount = 600, elapsedMs = 0L, frameDelayMs = 17L))
        // 稳态下两拍是 +2 / +4，跟「当前帧 +1」彻底错开：这里第 6 帧根本不会被画。
        assertEquals(listOf(7, 9), prefetch(frameCount = 600, elapsedMs = 100L, frameDelayMs = 17L))
    }

    @Test
    fun `delay 与 tick 同频时逐帧前进`() {
        assertEquals(listOf(1, 2), prefetch(frameCount = 600, elapsedMs = 0L, frameDelayMs = 33L))
    }

    @Test
    fun `慢速序列连续几拍落同一帧时只吐一个目标`() {
        // delay=500ms：两拍之后仍在第 0 帧，预取只需要一个目标，不能白占解码额度。
        assertEquals(listOf(1), prefetch(frameCount = 10, elapsedMs = 480L, frameDelayMs = 500L))
        assertTrue(prefetch(frameCount = 10, elapsedMs = 0L, frameDelayMs = 500L).isEmpty())
    }

    @Test
    fun `预取目标从不包含当前帧`() {
        for (elapsed in 0L until 200L step 7L) {
            val current = watchGifFrameIndex(600, elapsed, 17L)
            assertFalse(
                "elapsed=$elapsed",
                current in prefetch(frameCount = 600, elapsedMs = elapsed, frameDelayMs = 17L),
            )
        }
    }

    @Test
    fun `预取目标去重且不超过 lookahead 拍数`() {
        for (delay in longArrayOf(1L, 5L, 17L, 33L, 100L)) {
            val targets = prefetch(frameCount = 600, elapsedMs = 0L, frameDelayMs = delay)
            assertEquals("delay=$delay", targets.distinct(), targets)
            assertTrue("delay=$delay", targets.size <= 2)
        }
    }

    @Test
    fun `绕回序列末尾时环绕到开头`() {
        // 10 帧 / 33ms，当前停在第 9 帧：下两拍应该回到 0 和 1。
        assertEquals(listOf(0, 1), prefetch(frameCount = 10, elapsedMs = 9 * 33L, frameDelayMs = 33L))
    }

    @Test
    fun `单帧或空序列不预取`() {
        assertTrue(prefetch(frameCount = 1, elapsedMs = 0L, frameDelayMs = 17L).isEmpty())
        assertTrue(prefetch(frameCount = 0, elapsedMs = 0L, frameDelayMs = 17L).isEmpty())
    }

    @Test
    fun `lookahead 非正数时不预取`() {
        assertTrue(
            prefetch(frameCount = 600, elapsedMs = 0L, frameDelayMs = 17L, lookaheadTicks = 0)
                .isEmpty(),
        )
    }

    @Test
    fun `delay 为零按最小 1ms 处理不会崩`() {
        // dynamicNumber 解析失败时 gif_delay 可能落成 0。
        val targets = prefetch(frameCount = 600, elapsedMs = 0L, frameDelayMs = 0L)
        assertEquals(targets.distinct(), targets)
        assertTrue(targets.size <= 2)
    }
}
