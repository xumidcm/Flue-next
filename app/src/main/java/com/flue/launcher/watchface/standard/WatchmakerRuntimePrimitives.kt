package com.flue.launcher.watchface.standard

import java.time.YearMonth
import java.util.Calendar
import java.util.Locale
import kotlin.math.floor
import kotlin.math.round

enum class WatchTextAnimationPhase { STATIC, HIDDEN, ENTERING, VISIBLE, EXITING }

data class WatchTextAnimationFrame(
    val phase: WatchTextAnimationPhase,
    val alpha: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
) {
    val visible: Boolean get() = phase != WatchTextAnimationPhase.HIDDEN && alpha > 0f
}

/** Deterministic frame selection for WatchMaker's backtick-separated GIF paths. */
data class WatchGifFrame(val path: String, val index: Int)

// Keep long editor-produced video frame sequences intact. The archive
// installer already caps a package at 2,048 entries, so matching that ceiling
// here stays bounded while avoiding the old 128-frame/4 KiB silent truncation.
const val MAX_WATCH_GIF_PATH_INPUT_CHARS: Int = 256 * 1024
const val MAX_WATCH_GIF_FRAME_PATHS: Int = 2_048
const val DEFAULT_WATCH_GALLERY_DURATION_SECONDS: Float = 5f

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

fun watchGifFrameIndex(frameCount: Int, elapsedMs: Long, delayMs: Long = 50L): Int {
    if (frameCount <= 0) return -1
    val safeDelay = delayMs.coerceAtLeast(1L)
    return floor((elapsedMs.coerceAtLeast(0L) / safeDelay).toDouble())
        .toLong()
        .mod(frameCount)
        .toInt()
}

fun selectWatchGifFrame(pathExpression: String?, elapsedMs: Long, delayMs: Long = 50L): WatchGifFrame? {
    val paths = parseWatchGifPaths(pathExpression)
    if (paths.isEmpty()) return null
    val index = watchGifFrameIndex(paths.size, elapsedMs, delayMs)
    return WatchGifFrame(paths[index], index)
}

/**
 * GalleryProvider uses a pipe-delimited list, while Flue's editor stores the
 * same image sequence with WatchMaker's backtick delimiter. Accept both forms
 * so imported gallery_2d layers and newly edited faces share one renderer.
 */
fun parseWatchGalleryPaths(pathExpression: String?): List<String> =
    parseWatchGifPaths(pathExpression?.replace('|', '`'))

/** Reference gallery_2d timing: dur_on is seconds per photo and always loops. */
fun watchGalleryFrameDurationMs(durationOnSeconds: Float): Long {
    val seconds = durationOnSeconds
        .takeIf { it.isFinite() && it > 0f }
        ?: DEFAULT_WATCH_GALLERY_DURATION_SECONDS
    return (seconds.coerceIn(0.05f, 3_600f) * 1_000f).toLong().coerceAtLeast(1L)
}

fun watchGalleryFrameIndex(
    frameCount: Int,
    elapsedMs: Long,
    durationOnSeconds: Float = DEFAULT_WATCH_GALLERY_DURATION_SECONDS,
): Int = watchGifFrameIndex(
    frameCount = frameCount,
    elapsedMs = elapsedMs,
    delayMs = watchGalleryFrameDurationMs(durationOnSeconds),
)

/** Detects animated image/text layers even when an old face labels them as plain `image`. */
fun watchLayerRequiresFrameTicks(type: String?, attributes: Map<String, String>): Boolean {
    val normalizedType = type.orEmpty().trim().lowercase(Locale.ROOT)
    val path = attributes["path"].orEmpty().trim().lowercase(Locale.ROOT)
    val animatedImage = normalizedType == "gif" ||
        normalizedType == "image_gif" ||
        normalizedType == "gallery" ||
        normalizedType == "gallery_2d" ||
        (normalizedType in setOf("image", "image_tap", "image_cond") && (
            '`' in path || path.substringBefore('?').endsWith(".gif")
        ))
    val animatedText = normalizedType.startsWith("text") && (
        !attributes["anim_in"].isNullOrBlank() || !attributes["anim_out"].isNullOrBlank()
    )
    return animatedImage || animatedText
}

private data class WatchCounterSpec(
    val from: Double,
    val to: Double,
    val durationSeconds: Double,
    val mode: String,
    val delaySeconds: Double,
)

private val WATCH_COUNTER_TAG = Regex(
    "^c_(-?\\d+(?:\\.\\d+)?)_(-?\\d+(?:\\.\\d+)?)_(\\d+(?:\\.\\d+)?)_(st|sp|rt|rp|rv)(?:_(\\d+(?:\\.\\d+)?))?$",
    RegexOption.IGNORE_CASE,
)
private val WATCH_COUNTER_REFERENCE = Regex(
    "\\{\\s*c_(?:elapsed|-?\\d+(?:\\.\\d+)?_-?\\d+(?:\\.\\d+)?_\\d+(?:\\.\\d+)?_(?:st|sp|rt|rp|rv)(?:_\\d+(?:\\.\\d+)?)?)\\s*\\}",
    RegexOption.IGNORE_CASE,
)

/** True for WatchMaker's load-relative animated counter vocabulary. */
fun isWatchCounterTag(rawTag: String?): Boolean {
    val tag = rawTag.orEmpty().trim()
    return tag.equals("c_elapsed", ignoreCase = true) || WATCH_COUNTER_TAG.matches(tag)
}

/** Counter attributes must remain on the frame clock even when the layer itself is static. */
fun watchCounterRequiresFrameTicks(raw: String?): Boolean =
    WATCH_COUNTER_REFERENCE.containsMatchIn(raw.orEmpty())

/**
 * Resolve legacy WatchMaker counters against time elapsed since the face was
 * loaded. `rp` repeats from start, `rv` ping-pongs forever, and stop modes hold
 * their final value. Values are rounded to three decimal places like Studio.
 */
fun resolveWatchCounterTag(rawTag: String?, elapsedMs: Long): String? {
    val tag = rawTag.orEmpty().trim().lowercase(Locale.ROOT)
    if (tag == "c_elapsed") {
        return renderWatchCounterNumber(elapsedMs.coerceAtLeast(0L) / 1_000.0)
    }
    val match = WATCH_COUNTER_TAG.matchEntire(tag) ?: return null
    val spec = WatchCounterSpec(
        from = match.groupValues[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null,
        to = match.groupValues[2].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null,
        durationSeconds = match.groupValues[3].toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?.coerceIn(0.001, 3_600.0)
            ?: return null,
        mode = match.groupValues[4].lowercase(Locale.ROOT),
        delaySeconds = match.groupValues.getOrNull(5)
            ?.takeIf(String::isNotBlank)
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?.coerceIn(0.0, 86_400.0)
            ?: 0.0,
    )
    val elapsedSeconds = elapsedMs.coerceAtLeast(0L) / 1_000.0
    if (elapsedSeconds <= spec.delaySeconds) return renderWatchCounterNumber(spec.from)
    val activeSeconds = elapsedSeconds - spec.delaySeconds
    val progress = when (spec.mode) {
        "rp" -> (activeSeconds % spec.durationSeconds) / spec.durationSeconds
        "rv" -> {
            val cycleSeconds = spec.durationSeconds * 2.0
            val cycleTime = activeSeconds % cycleSeconds
            if (cycleTime <= spec.durationSeconds) {
                cycleTime / spec.durationSeconds
            } else {
                1.0 - (cycleTime - spec.durationSeconds) / spec.durationSeconds
            }
        }
        else -> (activeSeconds / spec.durationSeconds).coerceIn(0.0, 1.0)
    }
    return renderWatchCounterNumber(spec.from + (spec.to - spec.from) * progress)
}

private fun renderWatchCounterNumber(value: Double): String {
    val safe = value.takeIf(Double::isFinite) ?: 0.0
    val rounded = round(safe * 1_000.0) / 1_000.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

/**
 * Stateless WatchMaker text-animation timeline. The final requested cycle
 * deliberately remains visible, matching the reference renderer; `-1` loops.
 */
fun watchTextAnimationFrame(
    animIn: String?,
    animOut: String?,
    durationInSeconds: Float,
    durationOnSeconds: Float,
    durationOutSeconds: Float,
    durationOffSeconds: Float,
    delayStartSeconds: Float,
    repeatCount: Int,
    restartOnLoad: Boolean,
    elapsedMs: Long,
): WatchTextAnimationFrame {
    val entryName = animIn.orEmpty().trim()
    val exitName = animOut.orEmpty().trim()
    if (!restartOnLoad || (entryName.isBlank() && exitName.isBlank())) {
        return WatchTextAnimationFrame(WatchTextAnimationPhase.STATIC)
    }

    fun secondsToMs(value: Float): Long =
        (value.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 60f).times(1_000f).toLong()

    val delayMs = secondsToMs(delayStartSeconds)
    var timelineMs = elapsedMs.coerceAtLeast(0L)
    if (timelineMs < delayMs) return WatchTextAnimationFrame(WatchTextAnimationPhase.HIDDEN, alpha = 0f)
    timelineMs -= delayMs

    val inMs = if (entryName.isBlank()) 0L else secondsToMs(durationInSeconds).coerceAtLeast(1L)
    val onMs = secondsToMs(durationOnSeconds)
    val outMs = if (exitName.isBlank()) 0L else secondsToMs(durationOutSeconds).coerceAtLeast(1L)
    val offMs = secondsToMs(durationOffSeconds)

    // WatchMaker treats dur_on=0 as "stay forever" and never enters the exit phase.
    if (timelineMs < inMs) {
        return textAnimationTransform(entryName, timelineMs.toFloat() / inMs.coerceAtLeast(1L), entering = true)
    }
    if (onMs == 0L) return WatchTextAnimationFrame(WatchTextAnimationPhase.VISIBLE)

    val cycleMs = (inMs + onMs + outMs + offMs).coerceAtLeast(1L)
    val infinite = repeatCount == -1
    val finiteCycles = repeatCount.coerceIn(1, 10_000)
    if (!infinite) {
        val finalCycleStart = cycleMs * (finiteCycles - 1L)
        if (timelineMs >= finalCycleStart) {
            val finalTime = timelineMs - finalCycleStart
            if (finalTime < inMs) {
                return textAnimationTransform(
                    entryName,
                    finalTime.toFloat() / inMs.coerceAtLeast(1L),
                    entering = true,
                )
            }
            return WatchTextAnimationFrame(WatchTextAnimationPhase.VISIBLE)
        }
    }

    val cycleTime = timelineMs % cycleMs
    if (cycleTime < inMs) {
        return textAnimationTransform(entryName, cycleTime.toFloat() / inMs.coerceAtLeast(1L), entering = true)
    }
    if (cycleTime < inMs + onMs) return WatchTextAnimationFrame(WatchTextAnimationPhase.VISIBLE)
    if (cycleTime < inMs + onMs + outMs) {
        val progress = (cycleTime - inMs - onMs).toFloat() / outMs.coerceAtLeast(1L)
        return textAnimationTransform(exitName, progress, entering = false)
    }
    return WatchTextAnimationFrame(WatchTextAnimationPhase.HIDDEN, alpha = 0f)
}

private fun textAnimationTransform(
    rawName: String,
    rawProgress: Float,
    entering: Boolean,
): WatchTextAnimationFrame {
    val name = rawName.trim().lowercase(Locale.ROOT)
    val progress = rawProgress.coerceIn(0f, 1f)
    val eased = if (entering) 1f - (1f - progress) * (1f - progress) else progress * progress
    val phase = if (entering) WatchTextAnimationPhase.ENTERING else WatchTextAnimationPhase.EXITING
    val alpha = if (entering) (progress * 2f).coerceAtMost(1f) else (1f - progress * 2f).coerceAtLeast(0f)
    val distance = 200f
    return when (name) {
        "slideleft" -> WatchTextAnimationFrame(
            phase,
            alpha,
            translationX = if (entering) distance * (1f - eased) else -distance * eased,
        )
        "slideright" -> WatchTextAnimationFrame(
            phase,
            alpha,
            translationX = if (entering) -distance * (1f - eased) else distance * eased,
        )
        "slideup" -> WatchTextAnimationFrame(
            phase,
            alpha,
            translationY = if (entering) distance * (1f - eased) else -distance * eased,
        )
        "slidedown" -> WatchTextAnimationFrame(
            phase,
            alpha,
            translationY = if (entering) -distance * (1f - eased) else distance * eased,
        )
        "zoomin" -> WatchTextAnimationFrame(
            phase,
            alpha,
            scale = if (entering) 0.25f + 0.75f * eased else 1f + eased,
        )
        "zoomout" -> WatchTextAnimationFrame(
            phase,
            alpha,
            scale = if (entering) 2f - eased else 1f - 0.75f * eased,
        )
        "spin" -> WatchTextAnimationFrame(
            phase,
            alpha,
            rotation = if (entering) -360f * (1f - eased) else 360f * eased,
        )
        "fade", "" -> WatchTextAnimationFrame(phase, if (entering) progress else 1f - progress)
        else -> WatchTextAnimationFrame(phase, if (entering) progress else 1f - progress)
    }
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
     * "可配置命名动作"：正主指向用户映射的心率/健康应用，未映射时保持
     * 静默但动作仍然"被识别"。Flue 没有 tap action 映射设置，所以运行时
     * 至少要给出触觉反馈并触发一次新的心率测量。
     */
    data object HeartRate : WatchTapAction

    /**
     * w_app:com.example.pkg/.MainActivity 或 m_app:com.example.pkg
     * 或 WatchMaker 社区格式 w_app:label`pkg`activity（backtick 分隔）。
     * 格式：前缀后跟 packageName，可选 /activityClass 或 `分隔的三段式。
     */
    data class AppLaunch(val packageName: String, val activityClass: String?) : WatchTapAction

    data class Unsupported(val reason: String) : WatchTapAction
}

object WatchTapActionParser {
    fun parse(raw: String?): WatchTapAction = parseInternal(raw.orEmpty().trim())

    private fun parseInternal(value: String): WatchTapAction {
        val lower = value.lowercase(Locale.ROOT)
        if (lower.startsWith("widget_calendar")) return WatchTapAction.Calendar
        if (lower.startsWith("widget_weather") || lower.startsWith("m_update_weather"))
            return WatchTapAction.Weather
        if (lower.startsWith("w_heart_rate") || lower.startsWith("widget_health") ||
            lower.startsWith("w_fit"))
            return WatchTapAction.HeartRate
        if (lower.isBlank()) return WatchTapAction.Unsupported("empty tap action")
        if (lower.startsWith("script:")) return WatchTapAction.Unsupported("scripts are disabled")
        if (lower.startsWith("w_app:") || lower.startsWith("m_app:")) {
            val payload = value.substringAfter(':')
            // WatchMaker 社区格式：w_app:label`pkg`activity（反引号分隔）
            if (payload.contains('`')) {
                val parts = payload.split('`')
                if (parts.size >= 2) {
                    val pkg = parts[1].trim()
                    val act = if (parts.size >= 3) parts[2].trim().ifBlank { null } else null
                    return if (pkg.isBlank()) {
                        WatchTapAction.Unsupported("empty package name")
                    } else {
                        WatchTapAction.AppLaunch(packageName = pkg, activityClass = act)
                    }
                }
            }
            // 传统格式：w_app:pkg/.Activity 或 w_app:pkg
            val slash = payload.indexOf('/')
            val pkg = if (slash >= 0) payload.substring(0, slash) else payload
            val act: String? = if (slash < 0) null else {
                val cls = payload.substring(slash + 1)
                // 相对类名 (.Activity → com.pkg.Activity)
                if (cls.startsWith('.')) pkg + cls else cls.ifBlank { null }
            }
            return if (pkg.isBlank()) {
                WatchTapAction.Unsupported("empty package name")
            } else {
                WatchTapAction.AppLaunch(packageName = pkg.trim(), activityClass = act?.trim())
            }
        }
        // 包含 / : . 的其他格式（URL、component name 等）暂不支持
        if (lower.contains('/') || lower.contains(':') || lower.contains('.'))
            return WatchTapAction.Unsupported("custom intents are disabled")
        return WatchTapAction.Unsupported("unsupported action")
    }
}
