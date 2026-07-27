package com.flue.launcher.watchface.standard

import java.time.YearMonth
import java.util.Calendar
import java.util.Locale
import kotlin.math.floor

/** Deterministic frame selection for WatchMaker's backtick-separated GIF paths. */
data class WatchGifFrame(val path: String, val index: Int)

const val MAX_WATCH_GIF_PATH_INPUT_CHARS: Int = 4 * 1024
const val MAX_WATCH_GIF_FRAME_PATHS: Int = 128

fun parseWatchGifPaths(
    pathExpression: String?,
    maxPaths: Int = MAX_WATCH_GIF_FRAME_PATHS,
    maxInputChars: Int = MAX_WATCH_GIF_PATH_INPUT_CHARS,
): List<String> {
    val source = pathExpression.orEmpty()
    val pathLimit = maxPaths.coerceIn(1, MAX_WATCH_GIF_FRAME_PATHS)
    val scanLimit = minOf(
        source.length,
        maxInputChars.coerceIn(1, MAX_WATCH_GIF_PATH_INPUT_CHARS),
    )
    if (scanLimit == 0) return emptyList()
    val paths = ArrayList<String>(minOf(pathLimit, 8))

    fun appendPath(start: Int, endExclusive: Int): Boolean {
        source.substring(start, endExclusive).trim()
            .takeIf(String::isNotEmpty)
            ?.let(paths::add)
        return paths.size >= pathLimit
    }

    var pathStart = 0
    var index = 0
    while (index < scanLimit) {
        if (source[index] == '`') {
            if (appendPath(pathStart, index)) return paths
            pathStart = index + 1
        }
        index++
    }
    if (scanLimit == source.length) appendPath(pathStart, scanLimit)
    return paths
}

fun watchGifFrameIndex(frameCount: Int, elapsedMs: Long, delayMs: Long = 100L): Int {
    if (frameCount <= 0) return -1
    val safeDelay = delayMs.coerceAtLeast(1L)
    return floor((elapsedMs.coerceAtLeast(0L) / safeDelay).toDouble())
        .toLong()
        .mod(frameCount)
        .toInt()
}

fun selectWatchGifFrame(pathExpression: String?, elapsedMs: Long, delayMs: Long = 100L): WatchGifFrame? {
    val paths = parseWatchGifPaths(pathExpression)
    if (paths.isEmpty()) return null
    val index = watchGifFrameIndex(paths.size, elapsedMs, delayMs)
    return WatchGifFrame(paths[index], index)
}

/**
 * Parses the compact grid notation emitted by WatchMaker for image_cond
 * layers.  A malformed or missing value intentionally falls back to one cell;
 * callers can therefore render a normal image instead of crashing the face.
 */
fun parseWatchGrid(value: String?): Pair<Int, Int> {
    val parts = value.orEmpty().trim().lowercase(Locale.ROOT).split('x', limit = 2)
    val columns = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(1, 32) ?: 1
    val rows = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(1, 32) ?: 1
    return columns to rows
}

/**
 * Converts WatchMaker's one-based conditional image value to a safe zero-based
 * sprite index.  Values outside the available sheet are clamped by the
 * renderer, while a missing value selects the first cell.
 */
fun watchSpriteIndex(value: Float?, cellCount: Int = Int.MAX_VALUE): Int {
    val safe = value?.takeIf(Float::isFinite)?.toInt() ?: 1
    val zeroBased = (safe - 1).coerceAtLeast(0)
    return if (cellCount == Int.MAX_VALUE) zeroBased else zeroBased.coerceAtMost((cellCount - 1).coerceAtLeast(0))
}

/**
 * Parse chart values used by both WM_FORMAT_1.0 and Studio 2.0.  Exporters
 * disagree on separators (comma, semicolon, pipe and whitespace), so all are
 * accepted.  The hard cap is deliberate: chart data is untrusted package
 * input and must never create an unbounded per-frame path.
 */
const val MAX_WATCH_CHART_INPUT_CHARS: Int = 16 * 1024
const val MAX_WATCH_CHART_TOKEN_CHARS: Int = 64

fun parseWatchChartValues(
    raw: CharSequence?,
    maxPoints: Int = 256,
    maxInputChars: Int = MAX_WATCH_CHART_INPUT_CHARS,
): List<Float> {
    val limit = maxPoints.coerceIn(1, 256)
    val source = raw ?: return emptyList()
    val scanLimit = minOf(source.length, maxInputChars.coerceIn(1, MAX_WATCH_CHART_INPUT_CHARS))
    if (scanLimit == 0) return emptyList()
    val result = ArrayList<Float>(minOf(limit, 32))

    var tokenStart = -1
    var tokenTooLong = false

    fun flushToken(endExclusive: Int): Boolean {
        if (tokenStart >= 0 && !tokenTooLong) {
            source.subSequence(tokenStart, endExclusive)
                .toString()
                .toFloatOrNull()
                ?.takeIf(Float::isFinite)
                ?.let(result::add)
        }
        tokenStart = -1
        tokenTooLong = false
        return result.size >= limit
    }

    var index = 0
    while (index < scanLimit) {
        val character = source[index]
        if (character.isWatchChartSeparator()) {
            if (flushToken(index)) return result
        } else if (tokenStart < 0) {
            tokenStart = index
        } else if (index - tokenStart + 1 > MAX_WATCH_CHART_TOKEN_CHARS) {
            tokenTooLong = true
        }
        index++
    }
    // A prefix cut by the input budget may end in the middle of a number. Do
    // not turn that partial token into a value.
    if (scanLimit == source.length) flushToken(scanLimit)
    return result
}

private fun Char.isWatchChartSeparator(): Boolean =
    isWhitespace() || this == ',' || this == ';' || this == '|' ||
        this == '[' || this == ']' || this == '(' || this == ')'

/**
 * Small main-thread cache for evaluated watch strings. Both hits and misses
 * count towards the same LRU bound, while oversized dynamic keys are evaluated
 * but never retained.
 */
internal class WatchStringLruCache<V : Any>(
    private val maximumEntries: Int,
    private val maximumKeyLength: Int,
) {
    private data class Entry<V : Any>(val value: V?)

    private val entries = LinkedHashMap<String, Entry<V>>(
        minOf(maximumEntries.coerceAtLeast(1), 16) + 1,
        0.75f,
        true,
    )

    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
        require(maximumKeyLength > 0) { "maximumKeyLength must be positive" }
    }

    val size: Int
        get() = entries.size

    fun containsKey(key: String): Boolean =
        key.length <= maximumKeyLength && entries.containsKey(key)

    fun getOrPut(key: String, valueFactory: () -> V?): V? {
        if (key.length > maximumKeyLength) return valueFactory()
        entries[key]?.let { cached -> return cached.value }
        val value = valueFactory()
        entries[key] = Entry(value)
        trimToSize()
        return value
    }

    fun clear() {
        entries.clear()
    }

    private fun trimToSize() {
        while (entries.size > maximumEntries) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }
}

data class WatchBitmapGlyph(
    val id: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val xOffset: Int,
    val yOffset: Int,
    val xAdvance: Int,
    val page: Int,
)

data class WatchBitmapFont(
    val face: String,
    val nominalSize: Int,
    val lineHeight: Int,
    val baseline: Int,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val pages: List<String>,
    val glyphs: Map<Int, WatchBitmapGlyph>,
    private val kernings: Map<Long, Int>,
) {
    fun kerning(first: Int, second: Int): Int = kernings[kerningKey(first, second)] ?: 0

    fun normalizeText(text: String): String = text.uppercase(Locale.ROOT)

    fun measureAdvance(text: String): Int {
        val normalizedText = normalizeText(text)
        var total = 0L
        var previous: Int? = null
        var index = 0
        while (index < normalizedText.length) {
            val codePoint = Character.codePointAt(normalizedText, index)
            val glyph = glyphs[codePoint]
            if (glyph != null) {
                total += previous?.let { kerning(it, codePoint) } ?: 0
                total += glyph.xAdvance
                previous = codePoint
            }
            index += Character.charCount(codePoint)
        }
        return total.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}

private fun kerningKey(first: Int, second: Int): Long =
    (first.toLong() shl 32) or (second.toLong() and 0xffff_ffffL)

/** Parse AngelCode text BMFont files used by WatchMaker `bm:` fonts. */
fun parseWatchBitmapFont(
    raw: String,
    maxGlyphs: Int = 4_096,
    maxKernings: Int = 8_192,
): WatchBitmapFont? {
    if (raw.toByteArray(Charsets.UTF_8).size > MAX_WATCH_BMFONT_BYTES) return null
    val glyphLimit = maxGlyphs.coerceIn(1, 4_096)
    val kerningLimit = maxKernings.coerceIn(0, 8_192)
    var face = ""
    var nominalSize = 0
    var lineHeight = 0
    var baseline = 0
    var atlasWidth = 0
    var atlasHeight = 0
    val pages = HashMap<Int, String>()
    val glyphs = LinkedHashMap<Int, WatchBitmapGlyph>()
    val kernings = HashMap<Long, Int>()

    raw.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEach
        val type = line.substringBefore(' ')
        val params = BMFONT_PARAMETER_PATTERN.findAll(line).associate { match ->
            match.groupValues[1] to match.groupValues[2].trim('"')
        }
        when (type) {
            "info" -> {
                face = params["face"].orEmpty().take(256)
                nominalSize = params["size"].boundedInt(1, MAX_BMFONT_DIMENSION) ?: nominalSize
            }
            "common" -> {
                lineHeight = params["lineHeight"].boundedInt(1, MAX_BMFONT_DIMENSION) ?: lineHeight
                baseline = params["base"].boundedInt(0, MAX_BMFONT_DIMENSION) ?: baseline
                atlasWidth = params["scaleW"].boundedInt(1, MAX_BMFONT_DIMENSION) ?: atlasWidth
                atlasHeight = params["scaleH"].boundedInt(1, MAX_BMFONT_DIMENSION) ?: atlasHeight
            }
            "page" -> {
                if (pages.size >= MAX_BMFONT_PAGES) return@forEach
                val id = params["id"].boundedInt(0, MAX_BMFONT_PAGES - 1) ?: return@forEach
                val path = normalizeBitmapFontPage(params["file"]) ?: return@forEach
                pages.putIfAbsent(id, path)
            }
            "char" -> {
                if (glyphs.size >= glyphLimit) return@forEach
                val id = params["id"].boundedInt(0, Character.MAX_CODE_POINT) ?: return@forEach
                fun bounded(name: String, minimum: Int = -MAX_BMFONT_DIMENSION): Int? =
                    params[name].boundedInt(minimum, MAX_BMFONT_DIMENSION)
                val glyph = WatchBitmapGlyph(
                    id = id,
                    x = bounded("x", 0) ?: return@forEach,
                    y = bounded("y", 0) ?: return@forEach,
                    width = bounded("width", 0) ?: return@forEach,
                    height = bounded("height", 0) ?: return@forEach,
                    xOffset = bounded("xoffset") ?: 0,
                    yOffset = bounded("yoffset") ?: 0,
                    xAdvance = bounded("xadvance") ?: return@forEach,
                    page = params["page"].boundedInt(0, MAX_BMFONT_PAGES - 1) ?: 0,
                )
                glyphs.putIfAbsent(id, glyph)
            }
            "kerning" -> {
                if (kernings.size >= kerningLimit) return@forEach
                val first = params["first"].boundedInt(0, Character.MAX_CODE_POINT) ?: return@forEach
                val second = params["second"].boundedInt(0, Character.MAX_CODE_POINT) ?: return@forEach
                val amount = params["amount"].boundedInt(-MAX_BMFONT_DIMENSION, MAX_BMFONT_DIMENSION)
                    ?: return@forEach
                kernings.putIfAbsent(kerningKey(first, second), amount)
            }
        }
    }

    if (nominalSize <= 0 || lineHeight <= 0 || atlasWidth <= 0 || atlasHeight <= 0 ||
        pages.isEmpty() || glyphs.isEmpty()
    ) {
        return null
    }
    val orderedPages = (0..(pages.keys.maxOrNull() ?: return null)).map { id ->
        pages[id] ?: return null
    }
    if (glyphs.values.any { glyph -> glyph.page !in orderedPages.indices }) return null
    return WatchBitmapFont(
        face = face,
        nominalSize = nominalSize,
        lineHeight = lineHeight,
        baseline = baseline,
        atlasWidth = atlasWidth,
        atlasHeight = atlasHeight,
        pages = orderedPages,
        glyphs = glyphs,
        kernings = kernings,
    )
}

private fun String?.boundedInt(minimum: Int, maximum: Int): Int? =
    this?.toIntOrNull()?.takeIf { it in minimum..maximum }

private fun normalizeBitmapFontPage(raw: String?): String? {
    val value = raw.orEmpty().trim().replace('\\', '/').trimStart('/')
    if (value.isBlank() || value.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(value)) return null
    val segments = value.split('/').filter(String::isNotBlank)
    if (segments.any { it == "." || it == ".." }) return null
    return segments.joinToString("/").takeIf { it.length <= 512 }
}

private val BMFONT_PARAMETER_PATTERN = Regex("(\\w+)=([^\\s\"]+|\"[^\"]*\")")
private const val MAX_WATCH_BMFONT_BYTES = 1 * 1024 * 1024
private const val MAX_BMFONT_PAGES = 8
private const val MAX_BMFONT_DIMENSION = 16_384

/**
 * A deliberately small, non-XML SVG representation.  The archive parser
 * rejects external entities/DOCTYPEs before this routine is called; this
 * parser only accepts path data and ignores unsupported SVG nodes rather than
 * exposing markup as watch-face text.
 */
data class WatchSvgPathData(
    val pathData: String,
    val fill: String?,
)

data class WatchSvgDocument(
    val viewBox: List<Float>,
    val width: Float?,
    val height: Float?,
    val paths: List<WatchSvgPathData>,
)

data class PreparedWatchSvgPath<P : Any>(
    val path: P,
    val fill: String?,
)

data class PreparedWatchSvgDocument<P : Any>(
    val viewBox: List<Float>,
    val width: Float?,
    val height: Float?,
    val paths: List<PreparedWatchSvgPath<P>>,
)

/**
 * Converts bounded SVG path strings into renderer-native objects before the
 * document is published to a drawing thread. Failed individual paths are
 * omitted without invalidating the rest of the asset.
 */
fun <P : Any> prepareWatchSvgDocument(
    document: WatchSvgDocument?,
    pathFactory: (String) -> P?,
): PreparedWatchSvgDocument<P>? {
    document ?: return null
    val paths = document.paths.mapNotNull { source ->
        pathFactory(source.pathData)?.let { path ->
            PreparedWatchSvgPath(path = path, fill = source.fill)
        }
    }
    return PreparedWatchSvgDocument(
        viewBox = document.viewBox,
        width = document.width,
        height = document.height,
        paths = paths,
    )
}

const val MAX_WATCH_SVG_BYTES: Int = 1 * 1024 * 1024
const val MAX_WATCH_SVG_PATHS: Int = 128
const val MAX_WATCH_SVG_PATH_DATA: Int = 64 * 1024

/**
 * Bounded SVG subset used by WM_FORMAT_2.0 `svg` layers.  It is intentionally
 * regex based instead of a second XML stack: only the already-known `<svg>`
 * and `<path>` attributes are consumed, and potentially dangerous XML
 * constructs are rejected up front.
 */
fun parseWatchSvgDocument(
    raw: String,
    maxBytes: Int = MAX_WATCH_SVG_BYTES,
    maxPaths: Int = MAX_WATCH_SVG_PATHS,
): WatchSvgDocument? {
    if (maxBytes <= 0 || maxPaths <= 0) return null
    if (raw.toByteArray(Charsets.UTF_8).size > maxBytes) return null
    val lower = raw.lowercase(Locale.ROOT)
    if (
        "<!doctype" in lower ||
        "<!entity" in lower ||
        "<script" in lower ||
        "xlink:href" in lower ||
        Regex("""\bhref\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
    ) {
        return null
    }
    val root = SVG_ROOT_PATTERN.find(raw) ?: return null
    val rootAttributes = parseSvgAttributes(root.groupValues[1])
    val viewBox = rootAttributes["viewbox"]
        ?.split(Regex("[,\\s]+"))
        ?.mapNotNull(String::toFloatOrNull)
        ?.takeIf { it.size == 4 && it.all(Float::isFinite) && it[2] > 0f && it[3] > 0f }
        ?: listOf(0f, 0f, 512f, 512f)
    val width = parseSvgDimension(rootAttributes["width"])
    val height = parseSvgDimension(rootAttributes["height"])
    val inheritedFill = rootAttributes["fill"]
    val paths = ArrayList<WatchSvgPathData>(minOf(maxPaths, 16))
    SVG_PATH_PATTERN.findAll(raw).take(maxPaths).forEach { match ->
        val attributes = parseSvgAttributes(match.groupValues[1])
        val data = attributes["d"]
            ?.replace("&quot;", "\"", ignoreCase = true)
            ?.replace("&#34;", "\"", ignoreCase = true)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_WATCH_SVG_PATH_DATA }
            ?: return@forEach
        val fill = attributes["fill"]?.trim()?.takeUnless { it.equals("none", true) }
            ?: inheritedFill?.trim()?.takeUnless { it.equals("none", true) }
        paths += WatchSvgPathData(data, fill)
    }
    return WatchSvgDocument(viewBox, width, height, paths)
}

private val SVG_ROOT_PATTERN = Regex(
    """<svg\b([^>]*)>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val SVG_PATH_PATTERN = Regex(
    """<path\b([^>]*)/?>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val SVG_ATTRIBUTE_PATTERN = Regex(
    """([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*["']([^"']*)["']""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private fun parseSvgAttributes(raw: String): Map<String, String> = buildMap {
    SVG_ATTRIBUTE_PATTERN.findAll(raw).forEach { match ->
        put(match.groupValues[1].lowercase(Locale.ROOT), match.groupValues[2])
    }
}

private fun parseSvgDimension(raw: String?): Float? =
    raw?.trim()?.removeSuffix("px")?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

data class WatchSeriesItem(
    val value: String,
    val offset: Int,
    val isCurrent: Boolean,
)

/**
 * WatchMaker scrolling series used by dh24z/dh12z/dmz/dsz/ddz and weekday/month
 * variants. The returned offsets are item slots around the current item.
 */
object WatchSeries {
    fun values(series: String, now: Calendar): List<String> {
        val key = series.trim().lowercase(Locale.ROOT)
        return when {
            key.startsWith("dh24") -> (0..23).map { it.toString().padStart(if (key.endsWith('z')) 2 else 1, '0') }
            key.startsWith("dh12") -> (1..12).map { it.toString().padStart(if (key.endsWith('z')) 2 else 1, '0') }
            key.startsWith("dm") -> (0..59).map { it.toString().padStart(if (key.endsWith('z')) 2 else 1, '0') }
            key.startsWith("ds") -> (0..59).map { it.toString().padStart(if (key.endsWith('s')) 2 else 1, '0') }
            key.startsWith("dd") && !key.startsWith("ddw") -> {
                val max = now.getActualMaximum(Calendar.DAY_OF_MONTH)
                (1..max).map { it.toString().padStart(if (key.endsWith('z')) 2 else 1, '0') }
            }
            key.startsWith("dnnnn") -> (1..12).map {
                now.clone().let { clone ->
                    (clone as Calendar).apply { set(Calendar.MONTH, it - 1) }
                        .getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())
                        .orEmpty()
                }
            }
            key.startsWith("dnnn") -> (1..12).map {
                now.clone().let { clone ->
                    (clone as Calendar).apply { set(Calendar.MONTH, it - 1) }
                        .getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault())
                        .orEmpty()
                }
            }
            key.startsWith("dnn") -> (1..12).map { it.toString().padStart(2, '0') }
            key.startsWith("ddww") -> weekdayValues(now, long = true)
            key.startsWith("ddw") -> weekdayValues(now, long = false)
            else -> emptyList()
        }
    }

    fun currentIndex(series: String, now: Calendar): Int {
        val key = series.trim().lowercase(Locale.ROOT)
        return when {
            key.startsWith("dh24") -> now.get(Calendar.HOUR_OF_DAY)
            key.startsWith("dh12") -> (now.get(Calendar.HOUR).let { if (it == 0) 12 else it }) - 1
            key.startsWith("dm") -> now.get(Calendar.MINUTE)
            key.startsWith("ds") -> now.get(Calendar.SECOND)
            key.startsWith("dd") && !key.startsWith("ddw") -> now.get(Calendar.DAY_OF_MONTH) - 1
            key.startsWith("dnn") -> now.get(Calendar.MONTH)
            key.startsWith("ddw") -> (now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
            else -> 0
        }
    }

    fun items(series: String, now: Calendar, currentPos: String): List<WatchSeriesItem> {
        val values = values(series, now)
        if (values.isEmpty()) return emptyList()
        val current = currentIndex(series, now).coerceIn(0, values.lastIndex)
        val hideBefore = currentPos.equals("f", true)
        val hideAfter = currentPos.equals("l", true)
        return values.mapIndexedNotNull { index, value ->
            if (hideBefore && index < current || hideAfter && index > current) return@mapIndexedNotNull null
            WatchSeriesItem(value, index - current, index == current)
        }
    }

    private fun weekdayValues(now: Calendar, long: Boolean): List<String> =
        (0..6).map { offset ->
            val calendar = (now.clone() as Calendar).apply {
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY + offset)
            }
            // 不少 locale 的星期缩写数据自带尾句号（es "lun."、de "Mo."），
            // 直接落到表盘上就是「周一.」式的拖尾，统一 trimEnd('.')。
            calendar.getDisplayName(
                Calendar.DAY_OF_WEEK,
                if (long) Calendar.LONG else Calendar.SHORT,
                Locale.getDefault(),
            ).orEmpty().trimEnd('.')
        }
}

data class WatchCalendarCell(
    val day: Int,
    val column: Int,
    val row: Int,
    val isToday: Boolean,
    val isOutside: Boolean,
)

object WatchCalendarGrid {
    fun cells(now: Calendar, dowStart: Int = 1): List<WatchCalendarCell> {
        val yearMonth = YearMonth.of(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
        val first = (now.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val javaDay = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val start = dowStart.coerceIn(0, 6)
        val firstColumn = (javaDay - start + 7) % 7
        val today = now.get(Calendar.DAY_OF_MONTH)
        return (1..yearMonth.lengthOfMonth()).map { day ->
            val slot = firstColumn + day - 1
            WatchCalendarCell(
                day = day,
                column = slot % 7,
                row = slot / 7,
                isToday = day == today,
                isOutside = day < today,
            )
        }
    }

    fun weekdayLabels(dowStart: Int = 1, count: Int = 1, locale: Locale = Locale.getDefault()): List<String> {
        val names = (0..6).map { index ->
            val calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY + index) }
            // trimEnd('.') 必须在 take(count) 之前：否则 "lun." 截两位会留下 "lu"
            // 之外的尾点变体，且单字符场景会把句号本身当成首字母。
            calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale)
                .orEmpty()
                .trimEnd('.')
        }
        val start = dowStart.coerceIn(0, 6)
        return (0..6).map { index -> names[(start + index) % 7].take(count.coerceAtLeast(1)) }
    }
}

sealed interface WatchTapAction {
    data object Calendar : WatchTapAction
    data object Weather : WatchTapAction

    /**
     * WM_TAP_ACTIONS.md 里 `w_heart_rate` / `widget_health` / `w_fit` 属于
     * “可配置命名动作”：正主指向用户映射的心率/健康应用，未映射时保持
     * 静默但动作仍然“被识别”。Flue 没有 tap action 映射设置，所以运行时
     * 至少要给出触觉反馈并触发一次新的心率测量。
     */
    data object HeartRate : WatchTapAction
    data class Unsupported(val reason: String) : WatchTapAction
}

object WatchTapActionParser {
    fun parse(raw: String?): WatchTapAction {
        val value = raw.orEmpty().trim().lowercase(Locale.ROOT)
        return when {
            value.startsWith("widget_calendar") -> WatchTapAction.Calendar
            value.startsWith("widget_weather") || value.startsWith("m_update_weather") -> WatchTapAction.Weather
            value.startsWith("w_heart_rate") || value.startsWith("widget_health") || value.startsWith("w_fit") ->
                WatchTapAction.HeartRate
            value.isBlank() -> WatchTapAction.Unsupported("empty tap action")
            value.startsWith("script:") -> WatchTapAction.Unsupported("scripts are disabled")
            value.startsWith("w_app:") || value.startsWith("m_app:") -> WatchTapAction.Unsupported("app launch actions are disabled")
            value.contains('/') || value.contains(':') || value.contains('.') -> WatchTapAction.Unsupported("custom intents are disabled")
            else -> WatchTapAction.Unsupported("unsupported action")
        }
    }
}
