package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar
import java.util.Locale

class WatchmakerRuntimeContractsTest {
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
    fun canvasTransformFitsOrCenterCropsWithoutChangingTheAnchor() {
        val fit = watchCanvasTransform(
            viewWidth = 320f,
            viewHeight = 400f,
            forceFill = false,
        )
        assertEquals(0.625f, fit.scale, 0.0001f)
        assertEquals(0f, fit.left, 0.0001f)
        assertEquals(40f, fit.top, 0.0001f)

        val fill = watchCanvasTransform(
            viewWidth = 320f,
            viewHeight = 400f,
            forceFill = true,
        )
        assertEquals(0.78125f, fill.scale, 0.0001f)
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
        assertEquals(500f / 512f, matched.scale, 0.0001f)
        assertEquals(0f, matched.left, 0.01f)
        assertEquals(0f, matched.top, 0.01f)

        // 视口偏方时按短边等比装入，长边留居中黑边——scale 仍是单一值，
        // 绝不做各轴不同的拉伸。
        val letterboxed = watchCanvasTransform(
            viewWidth = 600f,
            viewHeight = 400f,
            forceFill = false,
            designWidth = 512f,
            designHeight = 616f,
        )
        assertEquals(400f / 616f, letterboxed.scale, 0.0001f)
        assertEquals((600f - 512f * (400f / 616f)) / 2f, letterboxed.left, 0.01f)
        assertEquals(0f, letterboxed.top, 0.01f)
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
        assertEquals("1", WatchmakerTagResolver.resolve("bc", calendar, snapshot).renderedValue)
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
    fun heartRateTagsRenderZeroWithoutDataAndKeepExpressionsAlive() {
        val calendar = Calendar.getInstance()
        val empty = WatchDataSnapshot()

        // Watchmaker/clox 惯例：心率无数据渲染 0，而不是 "--"。
        assertEquals("0", WatchmakerTagResolver.resolve("shr", calendar, empty).renderedValue)
        assertEquals("0", WatchmakerTagResolver.resolve("shrp", calendar, empty).renderedValue)
        // {shr_1..9} 历史心率：Flue 不保存历史，固定渲染 0，且必须是已知标签。
        assertEquals("0", WatchmakerTagResolver.resolve("shr_1", calendar, empty).renderedValue)
        assertEquals("0", WatchmakerTagResolver.resolve("shr_9", calendar, empty).renderedValue)
        assertTrue(WatchmakerTagResolver.isKnown("shr_5"))

        // 表达式里 {shr} 是数值 0，不再被替换成 nil 把整个算式打哑。
        assertEquals(
            5f,
            WatchmakerExpressionEvaluator.evaluateNumber("{shr} + 5", -1f) { tag ->
                WatchmakerTagResolver.resolve(tag, calendar, empty)
            },
            0.001f,
        )
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
        assertEquals(
            42f,
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
        assertFalse(WatchmakerExpressionEvaluator.supports("1 + ".repeat(2_048) + "1"))
    }

    @Test
    fun compatibilityReportAcceptsBoundedBuiltInsButStillReportsScripts() =
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
