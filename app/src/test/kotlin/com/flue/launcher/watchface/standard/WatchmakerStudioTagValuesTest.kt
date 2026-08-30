package com.flue.launcher.watchface.standard

import com.flue.launcher.watchface.editor.WatchFeatureDetector
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本次按 WM_TAGS.md 补齐的那批变量，值也要对得上上游给的示例。
 *
 * 词表测试（`WatchmakerStudioTagVocabularyTest`）只保证「认识」—— 认识但算错，表盘
 * 上就是一个安静的错数字，比不认识更难发现。所以凡是上游文档给了具体示例的，这里
 * 逐个钉住。
 */
class WatchmakerStudioTagValuesTest {

    private fun calendarAt(
        zoneId: String,
        year: Int = 2026,
        month: Int = Calendar.JULY,
        day: Int = 23,
        hour: Int = 18,
        minute: Int = 18,
        second: Int = 40,
    ): Calendar = Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply {
        clear()
        set(year, month, day, hour, minute, second)
    }

    private fun value(
        tag: String,
        now: Calendar,
        snapshot: WatchDataSnapshot = WatchDataSnapshot(),
        userColor: String? = null,
    ): String? = (
        WatchmakerTagResolver.resolve(tag, now, snapshot, userColor, Locale.US)
            as? WatchTagResolution.Resolved
        )?.renderedValue

    @Test
    fun `digit extraction splits tens and ones`() {
        // WM_TAGS.md §Digit Extraction 的示例就是 18:18 这一刻。
        val now = calendarAt("UTC", hour = 18, minute = 18, second = 40)
        assertEquals("1", value("dh23tt", now))
        assertEquals("8", value("dh23to", now))
        assertEquals("0", value("dhtt", now))
        assertEquals("6", value("dhto", now))
        assertEquals("1", value("dmt", now))
        assertEquals("8", value("dmo", now))
        assertEquals("4", value("dst", now))
        assertEquals("0", value("dso", now))
    }

    @Test
    fun `word clock follows the o'clock and teen special cases`() {
        // WM_TAGS.md §Text Representations 那张表，四种区间各取一个。
        fun minuteWords(minute: Int): Triple<String?, String?, String?> {
            val now = calendarAt("UTC", minute = minute)
            return Triple(value("dmtt", now), value("dmot", now), value("dmat", now))
        }

        assertEquals(Triple("o'", "clock", "o'clock"), minuteWords(0))
        // 1–9：`o'` 后面**不加**空格。
        assertEquals(Triple("o'", "three", "o'three"), minuteWords(3))
        // 10–19：整个 teen 词算十位部分，个位为空。
        assertEquals(Triple("eleven", "", "eleven"), minuteWords(11))
        // 20+：空格分隔；正好整十时个位为空。
        assertEquals(Triple("forty", "six", "forty six"), minuteWords(46))
        assertEquals(Triple("twenty", "", "twenty"), minuteWords(20))
    }

    @Test
    fun `hour text covers both the 12 and 24 hour readings`() {
        assertEquals("six", value("dht", calendarAt("UTC", hour = 18)))
        // {dh24t} 的复合数用空格分隔。
        assertEquals("twenty one", value("dh24t", calendarAt("UTC", hour = 21)))
        // 午夜在 24 小时读法里是 24，不是 0。
        assertEquals("twenty four", value("dh24t", calendarAt("UTC", hour = 0)))
    }

    @Test
    fun `utc offset uses two different formats on purpose`() {
        val shanghai = calendarAt("Asia/Shanghai")
        // {dutcoff} 无冒号、小时补零（WM_TAGS.md:455 示例 +0800）。
        assertEquals("+0800", value("dutcoff", shanghai))
        // {tz1o} 有冒号、小时不补零（WM_TAGS.md:365 示例 -7:00）。
        assertEquals("+8:00", value("tz1o", shanghai))
        assertEquals("480", value("tz1om", shanghai))

        val pacific = calendarAt("America/Los_Angeles")
        assertEquals("-0700", value("dutcoff", pacific))
        assertEquals("-7:00", value("tz1o", pacific))
        assertEquals("-420", value("tz1om", pacific))
    }

    @Test
    fun `timezone one reports the device zone and hand rotations`() {
        // WM_TAGS.md §Time Zone 1 的示例时刻：03:20。
        val now = calendarAt("America/Los_Angeles", hour = 3, minute = 20, second = 42)
        assertEquals("Los Angeles", value("tz1l", now))
        assertEquals("America/Los_Angeles", value("tz1ll", now))
        assertEquals("03:20", value("tz1t", now))
        assertEquals("100", value("tz1rh", now))
        assertEquals("50", value("tz1rh24", now))
        // 分针带秒的小数部分：(20 + 42/60) * 6 = 124.2。
        assertEquals(124.2f, value("tz1rm", now)!!.toFloat(), 0.01f)
    }

    @Test
    fun `days in year and week of month`() {
        assertEquals("365", value("ddiy", calendarAt("UTC", year = 2026, month = Calendar.MARCH, day = 1)))
        assertEquals("366", value("ddiy", calendarAt("UTC", year = 2028, month = Calendar.MARCH, day = 1)))
        assertEquals("1", value("dwm", calendarAt("UTC", year = 2026, month = Calendar.JULY, day = 1)))
    }

    @Test
    fun `epoch seconds match the calendar instant`() {
        val now = calendarAt("UTC")
        assertEquals((now.timeInMillis / 1_000L).toString(), value("depoch", now))
    }

    @Test
    fun `battery rotation is three point six degrees per percent`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(batteryPercent = 38)
        // 与既有的 {pbr} 同一算法、同一格式 —— 两者不一致会让同一块表盘的两根
        // 指针对不上。
        assertEquals(value("pbr", now, snapshot), value("br", now, snapshot))
        assertEquals(136.8f, value("br", now, snapshot)!!.toFloat(), 0.001f)
    }

    @Test
    fun `sunrise and sunset become day fractions`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(
            weather = WatchWeatherSnapshot(sunriseText = "06:00", sunsetText = "18:00"),
        )
        assertEquals(0.25f, value("wsrp", now, snapshot)!!.toFloat(), 0.0001f)
        assertEquals(0.75f, value("wssp", now, snapshot)!!.toFloat(), 0.0001f)
    }

    @Test
    fun `every colour switcher slot yields a usable colour`() {
        val now = calendarAt("UTC")
        // 这三个标签几乎只出现在 color 属性上：拿到 `--` 整个图层的颜色就崩了。
        // 我们只有一个用户强调色，所以 2/3 档落回它，_b 档提亮。
        assertEquals("3482ff", value("ucolor2", now, userColor = "3482ff"))
        assertEquals("3482ff", value("ucolor3", now, userColor = "3482ff"))
        val brighter = value("ucolor_b", now, userColor = "000000")
        // 纯黑向白混 35%：0 + 255 × 0.35 = 89.25 → 89 = 0x59。形状与 {ucolor} 一致，
        // 同样不带 `#`。
        assertEquals("595959", brighter)
        // 已经是白的就还是白的，不会溢出。
        assertEquals("ffffff", value("ucolor_b", now, userColor = "ffffff"))
    }

    @Test
    fun `families without a data source stay known so faces still load`() {
        val now = calendarAt("UTC")
        // 认识 → Unavailable → 渲染 `--`，表达式里取中性 0。不认识才会让含它的
        // 表达式整条失败、属性落回默认值。
        listOf("c1t", "c10cal", "c4bp", "m1value", "m4max", "tz2ll", "tz3rh24", "cex")
            .forEach { tag ->
                val resolution = WatchmakerTagResolver.resolve(tag, now, WatchDataSnapshot())
                assertEquals(
                    "$tag 应该是已知但无数据",
                    WatchTagResolution.Unavailable(tag).renderedValue,
                    resolution.renderedValue,
                )
                assertEquals("$tag 不该是 Unsupported", false, resolution is WatchTagResolution.Unsupported)
            }
    }

    @Test
    fun `a genuinely unknown tag is still reported as unsupported`() {
        // 词表补全不等于放弃诊断能力：真正不认识的名字仍要能被 compatibilityReport 挑出来。
        val resolution = WatchmakerTagResolver.resolve("definitely_not_a_tag", calendarAt("UTC"), WatchDataSnapshot())
        assertEquals(true, resolution is WatchTagResolution.Unsupported)
    }

    // ── N5：接上数据源之后的那批 ───────────────────────────────────────────────

    @Test
    fun `memory and storage render decimal gigabytes with a one decimal percent`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(
            ramTotalBytes = 8_000_000_000L,
            ramFreeBytes = 3_300_000_000L,
            storageTotalBytes = 106_400_000_000L,
            storageFreeBytes = 90_900_000_000L,
        )
        // GB 按**十进制** 1e9 —— 规范示例 106.4 对得上 128GB 机型的数据分区，
        // 按 GiB 算会是 99 左右。
        assertEquals("8", value("amt", now, snapshot))
        assertEquals("8 GB", value("amtf", now, snapshot))
        assertEquals("3.3", value("amf", now, snapshot))
        assertEquals("3.3 GB", value("amff", now, snapshot))
        assertEquals("4.7", value("amu", now, snapshot))
        assertEquals("58.8", value("amup", now, snapshot))
        assertEquals("41.3", value("amfp", now, snapshot))
        assertEquals("106.4", value("adst", now, snapshot))
        assertEquals("90.9 GB", value("adsff", now, snapshot))
        assertEquals("15.5", value("adsu", now, snapshot))
    }

    @Test
    fun `a missing total makes the percentage unavailable instead of zero`() {
        val now = calendarAt("UTC")
        // 分母不知道时说「不知道」，不能说「0%」—— 后者会让环形进度画成空的。
        assertNull(value("amup", now, WatchDataSnapshot(ramUsedBytes = 4_000_000_000L)))
    }

    @Test
    fun `battery temperature is rounded and offered in both units`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(batteryTemperatureCelsius = 34.2f)
        assertEquals("34", value("btc", now, snapshot))
        assertEquals("34°C", value("btcd", now, snapshot))
        // 34.2°C = 93.56°F → 94。
        assertEquals("94", value("btf", now, snapshot))
        assertEquals("94°F", value("btfd", now, snapshot))
    }

    @Test
    fun `compass exposes a negative rotation and both bearing resolutions`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(compassHeadingDegrees = 334.581f)
        // {scr} 是**负的**且保留小数（表盘直接拿它当 rotation），{sct} 是取整的正值。
        assertEquals(-334.581f, value("scr", now, snapshot)!!.toFloat(), 0.001f)
        assertEquals("335", value("sct", now, snapshot))
        assertEquals("335°", value("sctd", now, snapshot))
        assertEquals("NW", value("scb", now, snapshot))
        assertEquals("NNW", value("scbb", now, snapshot))
        // 度数 + 空格 + 方位（2026-07-07 对着 WM 截图核过）。
        assertEquals("335° NW", value("sctdb", now, snapshot))
        assertEquals("335° NNW", value("sctdbb", now, snapshot))
    }

    @Test
    fun `bearings wrap around north instead of falling off the end`() {
        val now = calendarAt("UTC")
        listOf(0f, 359.9f, 360f, 720f, -0.4f).forEach { heading ->
            assertEquals(
                "heading $heading",
                "N",
                value("scb", now, WatchDataSnapshot(compassHeadingDegrees = heading)),
            )
        }
    }

    @Test
    fun `coordinates keep precision while the degree forms round and carry a hemisphere`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(latitude = 51.5074, longitude = -0.1278)
        assertEquals(51.5074f, value("alat", now, snapshot)!!.toFloat(), 0.0001f)
        assertEquals("51.5", value("alatd", now, snapshot))
        assertEquals("51.5°N", value("alatdd", now, snapshot))
        // 西经取绝对值 + W，而不是显示成负数。
        assertEquals("0.1", value("alond", now, snapshot))
        assertEquals("0.1°W", value("alondd", now, snapshot))
        assertEquals(
            "51.5°S",
            value("alatdd", now, WatchDataSnapshot(latitude = -51.5074)),
        )
    }

    @Test
    fun `location tags fall back to clox zeroes instead of double dashes`() {
        val now = calendarAt("UTC")
        // 没有任何定位数据时**必须给 0，不能给 `--`**：clox 的
        // `WF.location.latitude or 0`（`wf_tagdefs.lua:483-495`）在这种情况下就是 0，
        // 于是同一张表盘在 clox 上显示 `0`、在 Flue 上显示 `--`，用户报「经纬度变量
        // 读不出来」。而 `--` 还和「不认识这个 tag」撞成同一个外观，连排查方向都指错。
        val empty = WatchDataSnapshot()
        assertEquals("0", value("alat", now, empty))
        assertEquals("0", value("alon", now, empty))
        assertEquals("0.0", value("alatd", now, empty))
        assertEquals("0.0", value("alond", now, empty))
        assertEquals("0.0°N", value("alatdd", now, empty))
        assertEquals("0.0°E", value("alondd", now, empty))
        // {aalt} 同理：clox 是 `WF.location.elevation or 0`。
        assertEquals("0", value("aalt", now, empty))
    }

    @Test
    fun `degree forms keep a fixed decimal place even on whole degrees`() {
        val now = calendarAt("UTC")
        // 52.0 要给 "52.0" 而不是 "52"：上游是 `fmt("%.1f", …)`，位数固定。走
        // renderOneDecimal → renderCompactNumber 会把 `.0` 吃掉，而这一族常被
        // 位图字体按固定宽度排版（一个字符一个槽），少一位整块都会错位。
        val whole = WatchDataSnapshot(latitude = 52.0, longitude = -13.0)
        assertEquals("52.0", value("alatd", now, whole))
        assertEquals("52.0°N", value("alatdd", now, whole))
        assertEquals("13.0", value("alond", now, whole))
        assertEquals("13.0°W", value("alondd", now, whole))
    }

    @Test
    fun `coordinates are not narrowed to float`() {
        val now = calendarAt("UTC")
        // Float 只有约 7 位有效十进制位，116.397428 会被截成 116.39743。经度小数
        // 第 6 位大约是 0.1 米量级 —— 表盘上直接显示出来的坐标被我们改了值。
        val beijing = WatchDataSnapshot(latitude = 39.904211, longitude = 116.397428)
        assertEquals("39.904211", value("alat", now, beijing))
        assertEquals("116.397428", value("alon", now, beijing))
    }

    @Test
    fun `what3words renders empty instead of the unavailable dashes`() {
        val now = calendarAt("UTC")
        // 没有 what3words 的 API key，永远拿不到三词地址。给空串而不是 `--`：
        // 上游引擎把没有值的变量登记成静态 ""（`wf_expr.lua` 的 `engine:activate`），
        // 而表盘上一个居中的 `--` 看着像坏了，空白才读作「这项没配」。
        assertEquals("", value("aw3w", now))
        assertEquals("", value("aw3w1", now))
        assertEquals("", value("aw3w2", now))
        assertEquals("", value("aw3w3", now))
    }

    @Test
    fun `location tag usage is detected inside expressions too`() {
        // 渲染层靠这个信号决定要不要做一次位置权限引导。只认「整个花括号就是一个
        // 标签」不够 —— 表盘里坐标常混在算式里（`rotation="{alat*2}"`）。
        assertTrue(WatchmakerTagResolver.usesLocationTags("{alat}"))
        assertTrue(WatchmakerTagResolver.usesLocationTags("{alat*2+10}"))
        assertTrue(WatchmakerTagResolver.usesLocationTags("{floor(aalt/100)}"))
        assertTrue(WatchmakerTagResolver.usesLocationTags("{ALATDD}"))
        assertTrue(WatchmakerTagResolver.usesLocationTags("{aw3w1}"))
        // 没用到坐标就不该打扰用户授权。{alangcode} 以 `ala` 开头，是子串陷阱。
        assertTrue(!WatchmakerTagResolver.usesLocationTags("{dh23}:{dm}"))
        assertTrue(!WatchmakerTagResolver.usesLocationTags("{alangcode}"))
        // 花括号外的字面文本不算。
        assertTrue(!WatchmakerTagResolver.usesLocationTags("alat"))
    }

    @Test
    fun `the renderer and the feature detector share one location tag list`() {
        // 两份名单必须逐字相同：detector 用它算 `features`，渲染层用它决定要不要
        // 引导授权。漂移的后果是「市场上标着用了定位、但运行时从不申请权限」。
        assertEquals(
            WatchFeatureDetector.LOCATION_TAGS.sorted(),
            WatchmakerTagResolver.LOCATION_TAGS.sorted(),
        )
    }

    @Test
    fun `wind reuses the same bearing table as the compass`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(
            weather = WatchWeatherSnapshot(
                windSpeedMetersPerSecond = 3.46,
                windDirectionDegrees = 22.5,
            ),
        )
        assertEquals("3.5", value("wws", now, snapshot))
        assertEquals("23", value("wwd", now, snapshot))
        // 22.5° 正好在 N 与 NE 之间：8 向就近到 NE，16 向落在 NNE。
        assertEquals("NE", value("wwdb", now, snapshot))
        assertEquals("NNE", value("wwdbb", now, snapshot))
    }

    @Test
    fun `stopwatch fields are zero padded exactly like clox`() {
        val now = calendarAt("UTC")
        // 1 小时 2 分 5 秒 430 毫秒。
        val snapshot = WatchDataSnapshot(
            stopwatchRunning = true,
            stopwatchElapsedMs = 3_725_430L,
        )
        assertEquals("01", value("swh", now, snapshot))
        assertEquals("02", value("swm", now, snapshot))
        assertEquals("05", value("sws", now, snapshot))
        // 厘秒两位、毫秒三位。
        assertEquals("43", value("swss", now, snapshot))
        assertEquals("430", value("swsss", now, snapshot))
        assertEquals("3725430", value("swsst", now, snapshot))
        assertEquals("true", value("swr", now, snapshot))
        // 秒针 5.43 秒 → 32.58°。
        assertEquals(32.58f, value("swrss", now, snapshot)!!.toFloat(), 0.01f)
        assertEquals("30", value("swrs", now, snapshot))
    }

    @Test
    fun `a running stopwatch asks for sub second ticks`() {
        // {swss} / {swsss} 不进快节奏词表的话，秒表跑起来是一秒一跳的。
        assertTrue(WatchmakerTagResolver.requiresFastTicks("{swss}"))
        assertTrue(WatchmakerTagResolver.requiresFastTicks("{swsss}"))
        assertTrue(WatchmakerTagResolver.requiresFastTicks("{swrss}"))
        // 秒及以上不需要 —— 常规 tick 就够，别白烧电。
        assertTrue(!WatchmakerTagResolver.requiresFastTicks("{sws}"))
        assertTrue(!WatchmakerTagResolver.requiresFastTicks("{swm}"))
    }

    @Test
    fun `time since bright reports minus one only when we know the screen is off`() {
        val now = calendarAt("UTC")
        val bright = WatchDataSnapshot(brightMode = true, millisSinceBright = 13_608L)
        assertEquals("13608", value("abss", now, bright))
        // {abssl} 是秒、且上限 30。
        assertEquals("13.608", value("abssl", now, bright))
        assertEquals("30", value("abssl", now, bright.copy(millisSinceBright = 45_000L)))
        // 确知息屏 → 规范要求的 -1。
        val dim = WatchDataSnapshot(brightMode = false)
        assertEquals("-1", value("abss", now, dim))
        assertEquals("-1", value("abssl", now, dim))
        // 连亮屏状态都不知道 → 说「不知道」，不能谎报 -1（表盘会以为在息屏）。
        assertNull(value("abss", now, WatchDataSnapshot()))
    }

    @Test
    fun `health extras match clox rather than inventing numbers`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(steps = 8_000)
        // clox 的 health_steps 默认 distance/calories 都是 0，目标是硬编码的
        // 8.0 km / 350 kcal。**刻意不按步数折算距离** —— 那才是编数字。
        assertEquals("0", value("sdst", now, snapshot))
        assertEquals("8.0", value("stdst", now, snapshot))
        assertEquals("km", value("sdstu", now, snapshot))
        assertEquals("0", value("scal", now, snapshot))
        assertEquals("350", value("stcal", now, snapshot))
        listOf("hae", "ham", "has", "hfc", "htae", "htam", "htas").forEach { tag ->
            assertEquals(tag, "0", value(tag, now, snapshot))
        }
    }

    @Test
    fun `device facts come straight from the snapshot`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(
            osVersion = "15",
            deviceModel = "crDroid Treble",
            deviceManufacturer = "google",
            watchName = "Xiaomi Mi/Lite",
            screenRound = true,
            screenFlatTyre = false,
            volumePercent = 50,
            screenBrightness = 128,
            lowPowerMode = false,
            darkMode = true,
        )
        assertEquals("15", value("aosv", now, snapshot))
        assertEquals("crDroid Treble", value("amodel", now, snapshot))
        assertEquals("google", value("aman", now, snapshot))
        assertEquals("Xiaomi Mi/Lite", value("awname", now, snapshot))
        assertEquals("true", value("around", now, snapshot))
        assertEquals("false", value("atyre", now, snapshot))
        assertEquals("50", value("avol", now, snapshot))
        assertEquals("128", value("abrt", now, snapshot))
        assertEquals("false", value("alowpw", now, snapshot))
        assertEquals("true", value("adark", now, snapshot))
        // {aos} 是常量，没有数据源可缺。
        assertEquals("Android", value("aos", now, WatchDataSnapshot()))
    }

    @Test
    fun `the families that still have no source stay known`() {
        val now = calendarAt("UTC")
        // what3words 与手机电池温度仍然只是「认识」，重点是**不能是 Unsupported** ——
        // 那才会让含它的表达式整条失败、属性落回默认值。
        // 两者的空值外观刻意不同：{pbtc} 是 `--`（有数据源、这台设备没有配对手机），
        // {aw3w} 是空串（永远不会有 API key，画个 `--` 只会像坏了）。
        listOf("aw3w", "pbtc").forEach { tag ->
            val resolution = WatchmakerTagResolver.resolve(tag, now, WatchDataSnapshot())
            assertEquals("$tag 不该是 Unsupported", false, resolution is WatchTagResolution.Unsupported)
        }
        assertEquals("--", WatchmakerTagResolver.resolve("pbtc", now, WatchDataSnapshot()).renderedValue)
        assertEquals("", WatchmakerTagResolver.resolve("aw3w", now, WatchDataSnapshot()).renderedValue)
    }

    // ── 日程 ─────────────────────────────────────────────────────────────────

    private fun eventAt(zoneId: String, hour: Int, minute: Int, endHour: Int): Long =
        calendarAt(zoneId, hour = hour, minute = minute, second = 0).timeInMillis

    @Test
    fun `calendar fields cover text time date rotation and percent`() {
        val zone = "Asia/Shanghai"
        val now = calendarAt(zone, hour = 9, minute = 0)
        val begin = eventAt(zone, hour = 14, minute = 30, endHour = 0)
        val end = eventAt(zone, hour = 15, minute = 0, endHour = 0)
        val snapshot = WatchDataSnapshot(
            calendarEvents = listOf(
                WatchCalendarEvent(
                    id = "42",
                    title = "站会",
                    location = "3 号会议室",
                    calendarName = "工作",
                    colorRgb = "3482ff",
                    allDay = false,
                    beginEpochMs = begin,
                    endEpochMs = end,
                ),
            ),
        )
        assertEquals("true", value("cex", now, snapshot))
        assertEquals("true", value("c1ex", now, snapshot))
        assertEquals("站会", value("c1t", now, snapshot))
        assertEquals("3 号会议室", value("c1l", now, snapshot))
        assertEquals("工作", value("c1cal", now, snapshot))
        assertEquals("3482ff", value("c1c", now, snapshot))
        assertEquals("42", value("c1i", now, snapshot))
        assertEquals("false", value("c1ad", now, snapshot))
        assertEquals("14:30", value("c1b", now, snapshot))
        assertEquals("15:00", value("c1e", now, snapshot))
        assertEquals("2026-07-23", value("c1bd", now, snapshot))
        // 14:30 在一天中的位置 = 870/1440 = 0.604166… → 旋转 217.5°、百分比 60.4…
        assertEquals(217.5f, value("c1br", now, snapshot)!!.toFloat(), 0.01f)
        assertEquals(60.4f, value("c1bp", now, snapshot)!!.toFloat(), 0.1f)
        assertEquals(225f, value("c1er", now, snapshot)!!.toFloat(), 0.01f)
    }

    @Test
    fun `an absent event answers exists with false but leaves the rest unavailable`() {
        val now = calendarAt("UTC")
        val snapshot = WatchDataSnapshot(calendarEvents = emptyList())
        // 能读到日程、只是没有第 3 条 —— {c3ex} 必须答 false，而不是 `--`：
        // 表盘正是靠它决定「这一槽要不要画」。
        assertEquals("false", value("c3ex", now, snapshot))
        assertEquals("false", value("cex", now, snapshot))
        // 其余字段没有内容可给，走 Unavailable。
        assertNull(value("c3t", now, snapshot))
    }

    @Test
    fun `no calendar permission is not the same as an empty calendar`() {
        val now = calendarAt("UTC")
        // calendarEvents = null 表示读不到（没授权）。这时连 {cex} 都不能答 "false" ——
        // 那会把「没授权」显示成「今天没有安排」，是在骗用户。
        val denied = WatchDataSnapshot(calendarEvents = null)
        assertNull(value("cex", now, denied))
        assertNull(value("c1ex", now, denied))
        val resolution = WatchmakerTagResolver.resolve("cex", now, denied)
        assertEquals(false, resolution is WatchTagResolution.Unsupported)
    }

    @Test
    fun `event indices run to ten and no further`() {
        val now = calendarAt("UTC")
        assertTrue(WatchmakerTagResolver.isKnown("c10t"))
        // 上游只定义到 10 条，{c11t} 不是这一族的名字。
        assertTrue(!WatchmakerTagResolver.isKnown("c11t"))
    }
}
