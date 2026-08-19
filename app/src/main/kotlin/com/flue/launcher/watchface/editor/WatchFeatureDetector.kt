package com.flue.launcher.watchface.editor

import java.util.Locale

/**
 * 表盘根属性 `features` 里的一项。
 *
 * 两个格式版本写法不同（`README.md` 的对照表、`WM_FORMAT_2.0.md` §2.2）：
 *  * 1.0 写人类可读名，例：`features="Calendar,Date,GIF,Weather"`。
 *  * 2.0 写短代码，字母序逗号连接，例：`features="24,an,gif,w"`。
 *
 * 所以一个枚举项带两种写法，由 [WatchFeatureDetector.format] 按版本挑。
 */
enum class WatchFeature(val code: String, val label: String) {
    DATE("dt", "Date"),
    TIME_12("12", "12h Time"),
    TIME_24("24", "24h Time"),
    THEMEABLE("u", "Themeable"),
    ANALOG("an", "Analog"),
    BATTERY("bt", "Battery"),
    PHONE_BATTERY("pbt", "Phone Battery"),
    WEATHER("w", "Weather"),
    MOON("mn", "Moon Phase"),
    HEALTH("st", "Steps"),
    HEART_RATE("hr", "Heart Rate"),
    STOPWATCH("sw", "Stopwatch"),
    TIME_ZONE("zne", "Time Zone"),
    GYRO("gyr", "Gyroscope"),
    COMPASS("cps", "Compass"),
    COMPLICATION("cpn", "Complications"),
    SOUND("sfx", "Sound"),
    GIF("gif", "GIF"),
    ANIMATION("ani", "Animation"),
    TAP("tap", "Tap"),
    COUNTDOWN("cd", "Countdown"),
    CALENDAR("cal", "Calendar"),
}

/**
 * 从写好的 watch.xml 文本重算 `features`。
 *
 * ## 为什么必须重算
 *
 * `features` 是启动器和表盘市场用来筛选、分类、显示「这块表盘用了什么」的字段。
 * 编辑器改完图层却不更新它，导出的包就带着**上一位作者的**特性表：加了天气的表盘
 * 搜「天气」搜不到，删干净心率的表盘还挂着心率标签。`WM_STUDIO_2.0_EDITOR.md` §2.3
 * 和 `WM_FORMAT_2.0.md` §2.2 都写明这个值 **每次导出都重算并覆盖**，手改无效。
 *
 * ## 口径
 *
 * 官方 `detectFeatures` 的做法是「扫成品 XML 字符串」，我们照抄这条：拿最终序列化
 * 出来的文本做输入，而不是遍历 DOM —— 这样 Lua 脚本里、表达式里、任何我们还没
 * 建模的属性里用到的标签都能被算进去。
 *
 * 标签表按 `WM_TAGS.md` 的分节抄录（Sensor / Calendar / Health / Weather /
 * Stopwatch / Location / Battery / Color Switcher / Counters / Date & Time）。
 * 这是**近似**而不是逐位复刻：官方检测器把约 240 个标签映到这些名字上，映射细节
 * 在闭源 JS 里，没有公开。取舍原则是「宁可多标一项，不要漏标」——多一项只是搜索
 * 结果宽一点，漏一项等于这块表盘在市场里查不到。
 */
object WatchFeatureDetector {

    /**
     * @param xml 已经序列化好的 watch.xml 文本（含 Lua 脚本时也一起传进来更准）。
     */
    fun detect(xml: String): Set<WatchFeature> {
        val features = linkedSetOf<WatchFeature>()
        tagIdentifiers(xml).forEach { identifier ->
            featureForTag(identifier)?.let(features::addAll)
        }
        detectConstructs(xml, features)
        // 12 与 24 同时命中时丢掉 12（`WM_FORMAT_2.0.md` §2.2 末尾）。
        if (WatchFeature.TIME_24 in features) features.remove(WatchFeature.TIME_12)
        return features
    }

    /** 按版本拼成根属性值：都是字母序、逗号连接，与官方序列化一致。 */
    fun format(features: Set<WatchFeature>, version: WatchFormatVersion): String =
        features
            .map { if (version == WatchFormatVersion.V2) it.code else it.label }
            .sorted()
            .joinToString(",")

    /**
     * 取出 `{...}` 里所有可能是标签的标识符。
     *
     * 不能只认「整个花括号就是一个标签」：表达式里标签是混在算式中的
     * （`rotation="{drss*6}"`、`{floor(bl/10)}`），所以进到花括号里再逐个抓标识符。
     */
    private fun tagIdentifiers(xml: String): Set<String> {
        val result = linkedSetOf<String>()
        BRACES.findAll(xml).forEach { brace ->
            IDENTIFIER.findAll(brace.groupValues[1]).forEach { result += it.value }
        }
        return result
    }

    private fun featureForTag(tag: String): Set<WatchFeature>? {
        val lower = tag.lowercase(Locale.ROOT)
        return when {
            lower in DATE_TAGS || FUTURE_DAY_PATTERN.matches(lower) -> setOf(WatchFeature.DATE)
            lower in TIME_12_TAGS -> setOf(WatchFeature.TIME_12)
            lower in TIME_24_TAGS -> setOf(WatchFeature.TIME_24)
            lower in ANALOG_TAGS -> setOf(WatchFeature.ANALOG)
            lower in THEME_TAGS -> setOf(WatchFeature.THEMEABLE)
            lower in PHONE_BATTERY_TAGS -> setOf(WatchFeature.PHONE_BATTERY)
            lower in BATTERY_TAGS -> setOf(WatchFeature.BATTERY)
            lower in HEART_RATE_TAGS -> setOf(WatchFeature.HEART_RATE)
            lower in HEALTH_TAGS -> setOf(WatchFeature.HEALTH)
            lower in COMPASS_TAGS -> setOf(WatchFeature.COMPASS)
            lower in MOTION_TAGS -> setOf(WatchFeature.GYRO)
            lower == MOON_TAG -> setOf(WatchFeature.MOON)
            // 天气整节都以 w 开头，别的分节没有 w 前缀的标签，所以前缀判据是安全的
            // （月相 {wmp} 已经在上一条截走）。预报家族 {wf0dt}/{wf2hh} 也一并覆盖。
            WEATHER_PATTERN.matches(lower) -> setOf(WatchFeature.WEATHER)
            STOPWATCH_PATTERN.matches(lower) -> setOf(WatchFeature.STOPWATCH)
            TIME_ZONE_PATTERN.matches(lower) || lower in LOCATION_TAGS ->
                setOf(WatchFeature.TIME_ZONE)
            COUNTDOWN_PATTERN.matches(lower) -> setOf(WatchFeature.COUNTDOWN)
            COUNTER_PATTERN.matches(lower) -> setOf(WatchFeature.ANIMATION)
            // 日历事件是 {cNt}/{cNb}/… 这种带事件号的家族，外加一个 {cex}。
            lower == "cex" || CALENDAR_PATTERN.matches(lower) -> setOf(WatchFeature.CALENDAR)
            // 剩下的标签故意不产出任何 feature：分钟、秒、毫秒、时区缩写、Unix 时间戳
            // 这些不是官方表里的「特性」，硬塞进去只会让市场筛选变噪音。
            else -> null
        }
    }

    /** 不靠标签、靠 XML 结构判定的那几项（`WM_FORMAT_2.0.md` §2.2 的 regex extras）。 */
    private fun detectConstructs(xml: String, into: MutableSet<WatchFeature>) {
        val lower = xml.lowercase(Locale.ROOT)
        if ("tweens." in lower) into += WatchFeature.ANIMATION
        if ("image_gif" in lower || "type=\"gif\"" in lower) into += WatchFeature.GIF
        if ("tap_action=" in lower) into += WatchFeature.TAP
        if ("<sound" in lower) into += WatchFeature.SOUND
        if ("type=\"complication\"" in lower || "linked_component_id=" in lower) {
            into += WatchFeature.COMPLICATION
        }
        // gyro="0" 是「关闭」，不算用到陀螺仪。
        if (NON_ZERO_GYRO.containsMatchIn(lower)) into += WatchFeature.GYRO
        if ("type=\"video\"" in lower) into += WatchFeature.ANIMATION
    }

    private val BRACES = Regex("""\{([^\}]*)\}""")
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /**
     * `WM_TAGS.md` §Date Components。
     *
     * 这张表最容易抄错，因为时间和日期的标签长得几乎一样：`{dmo}` 是**分钟的个位**
     * 不是月份，`{dn}` 才是月份；`{dmat}`/`{dsat}` 是分/秒的英文读法，不是日期。
     * 分错的后果是天天显示分钟的表盘被标成「Date」。
     */
    private val DATE_TAGS = setOf(
        "dd", "ddz", "ddy", "ddiy", "ddim", "ddw", "ddw0", "ddw1", "ddw2", "ddww",
        "dn", "dnn", "dnnn", "dnnnn", "dy", "dyy", "dw", "dwm",
    )

    /** §Future Days：`{ddw_1}` / `{ddw1_3}` / `{ddww_6}` 这种带天数后缀的家族。 */
    private val FUTURE_DAY_PATTERN = Regex("""^ddw(w|[12])?_\d$""")

    /** 12 小时制家族，外加 AM/PM 指示 `{da}`。 */
    private val TIME_12_TAGS = setOf(
        "dh", "dhz", "dhtt", "dhto",
        "dh11", "dh11z", "dh11tt", "dh11to",
        "dhutc12", "dhutc12z", "da",
    )

    private val TIME_24_TAGS = setOf(
        "dh23", "dh23z", "dh23i", "dh23tt", "dh23to",
        "dh24", "dh24z", "dh24tt", "dh24to",
        "dhutc24", "dhutc24z", "d24h",
    )

    /** §Rotation Helpers：指针角度。有这些就是模拟表盘。 */
    private val ANALOG_TAGS = setOf("drh", "drh0", "drh24", "drm", "drms", "drs", "drss")

    private val THEME_TAGS = setOf("ucolor", "ucolor2", "ucolor3", "ucolor_b")

    private val BATTERY_TAGS = setOf("bc", "bl", "blp", "br", "btc", "btcd", "btf", "btfd", "bv", "bi")

    private val PHONE_BATTERY_TAGS =
        setOf("pbc", "pbl", "pblp", "pbr", "pbtc", "pbtcd", "pbtf", "pbtfd")

    private val HEART_RATE_TAGS = setOf("shr", "shr_1", "shr_2", "shrp", "sthr")

    private val HEALTH_TAGS = setOf(
        "ssc", "sscp", "stsc", "sdst", "sdstp", "sdstu", "stdst",
        "scal", "scalp", "stcal", "hfc", "ham", "htam", "hae", "htae", "has", "htas",
    )

    private val COMPASS_TAGS = setOf("scb", "scbb", "scr", "sct", "sctd", "sctdb", "sctdbb")

    /** 加速度计 / 陀螺仪 / 气压计都归 gyr —— 官方表里它们共用一个代码。 */
    private val MOTION_TAGS = setOf("sax", "say", "saz", "sgx", "sgy", "sgz", "sprs")

    private const val MOON_TAG = "wmp"

    private val LOCATION_TAGS = setOf(
        "aalt", "alat", "alatd", "alatdd", "alon", "alond", "alondd",
        "aw3w", "aw3w1", "aw3w2", "aw3w3",
    )

    private val WEATHER_PATTERN = Regex("""^w[a-z0-9_]*$""")
    private val STOPWATCH_PATTERN = Regex("""^t?sw[a-z0-9_]*$""")
    private val TIME_ZONE_PATTERN = Regex("""^tz\d[a-z0-9_]*$""")
    private val COUNTDOWN_PATTERN = Regex("""^cd_[a-z0-9_]*$""")
    private val COUNTER_PATTERN = Regex("""^c_[a-z0-9_]*$""")
    private val CALENDAR_PATTERN = Regex("""^c\d+[a-z]*$""")
    private val NON_ZERO_GYRO = Regex("""gyro="(?!0*"|0*\.0*")[^"]+"""")
}
