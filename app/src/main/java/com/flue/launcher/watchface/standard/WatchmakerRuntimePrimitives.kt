package com.flue.launcher.watchface.standard

import java.time.YearMonth
import java.util.Calendar
import java.util.Locale
import java.net.URI
import kotlin.math.ceil
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

/** 渲染器的快 tick 周期。帧序列预取要按它推算「下一拍会落在哪一帧」。 */
const val WATCH_FAST_TICK_INTERVAL_MS = 33L

/**
 * 帧序列该预取哪几帧 —— 按 tick 时钟推算**下几拍真正会落到的帧号**。
 *
 * ⚠️ 不能写成「当前帧 + 1」。渲染 tick 固定 [WATCH_FAST_TICK_INTERVAL_MS]，而帧号
 * 由墙钟除以 `delayMs` 得出，两者不同频：`delayMs=17` 时每拍前进 ⌈33/17⌉ = 2 帧，
 * 于是 `+1` 那帧根本不会被画，而下一拍真正要的 `+2` 永远是冷的。每拍都落回上一帧
 * 再重新发起解码，实机表现就是「拨两下停一下、拨两下停一下」。
 *
 * 反过来 `delayMs` 比 tick 长时（慢速序列），连续几拍会落在同一帧上，这里靠去重
 * 只吐一个目标 —— 解码额度（`MAX_CONCURRENT_ASSET_LOADS`）是全局共享的，抢不到时
 * `requestBitmap` 直接返回上一帧、不排队也不重试，白占额度是有代价的。
 *
 * @param lookaheadTicks 往前看几拍。2 拍 ≈ 66ms，足够盖住一张 480² JPG 的解码，
 *   又不会一次把解码队列占满。
 * @return 去重后的目标帧号，已排除 [currentIndex]；顺序即优先级（近的在前）。
 */
fun frameSequencePrefetchIndices(
    frameCount: Int,
    currentIndex: Int,
    elapsedMs: Long,
    frameDelayMs: Long,
    lookaheadTicks: Int = 2,
): List<Int> {
    if (frameCount <= 1 || lookaheadTicks < 1) return emptyList()
    val targets = ArrayList<Int>(lookaheadTicks)
    for (tick in 1..lookaheadTicks) {
        val index = watchGifFrameIndex(
            frameCount,
            elapsedMs + WATCH_FAST_TICK_INTERVAL_MS * tick,
            frameDelayMs,
        )
        if (index == currentIndex || index in targets) continue
        targets += index
    }
    return targets
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

/** A one-shot exit timeline driven by launcher navigation, independent of dur_on/repeat. */
fun watchTextExitAnimationFrame(
    animOut: String?,
    durationOutSeconds: Float,
    elapsedMs: Long,
): WatchTextAnimationFrame {
    val exitName = animOut.orEmpty().trim()
    if (exitName.isBlank()) return WatchTextAnimationFrame(WatchTextAnimationPhase.VISIBLE)
    val durationMs = ((durationOutSeconds.takeIf(Float::isFinite) ?: 0.5f)
        .coerceIn(0.05f, 60f) * 1_000f).toLong().coerceAtLeast(1L)
    if (elapsedMs >= durationMs) {
        return WatchTextAnimationFrame(WatchTextAnimationPhase.HIDDEN, alpha = 0f)
    }
    return textAnimationTransform(
        rawName = exitName,
        rawProgress = elapsedMs.coerceAtLeast(0L).toFloat() / durationMs,
        entering = false,
    )
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

data class WatchBitmapSourceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Maps a BMFont glyph from the atlas dimensions declared in the `.fnt` file
 * to the bitmap dimensions that Android actually decoded. Large atlas pages
 * may be sampled down to stay within the renderer's bitmap memory limit; the
 * glyph's destination size remains in the original BMFont design space.
 */
fun mapWatchBitmapGlyphSourceRect(
    glyph: WatchBitmapGlyph,
    atlasWidth: Int,
    atlasHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
): WatchBitmapSourceRect? {
    if (atlasWidth <= 0 || atlasHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
    if (glyph.x < 0 || glyph.y < 0 || glyph.width <= 0 || glyph.height <= 0) return null
    val logicalRight = glyph.x.toLong() + glyph.width
    val logicalBottom = glyph.y.toLong() + glyph.height
    if (logicalRight > atlasWidth || logicalBottom > atlasHeight) return null

    val scaleX = bitmapWidth.toDouble() / atlasWidth.toDouble()
    val scaleY = bitmapHeight.toDouble() / atlasHeight.toDouble()
    val left = floor(glyph.x * scaleX).toInt().coerceIn(0, bitmapWidth)
    val top = floor(glyph.y * scaleY).toInt().coerceIn(0, bitmapHeight)
    val right = ceil(logicalRight * scaleX).toInt().coerceIn(0, bitmapWidth)
    val bottom = ceil(logicalBottom * scaleY).toInt().coerceIn(0, bitmapHeight)
    if (right <= left || bottom <= top) return null
    return WatchBitmapSourceRect(left, top, right, bottom)
}

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
    data object OpenSettings : WatchTapAction
    data object BatteryStatus : WatchTapAction

    /** A WatchMaker/CloX action resolved by the user's app mapping. */
    data class Named(val value: String) : WatchTapAction

    /** A validated `w_link:<uri>` action. */
    data class DeepLink(val uri: String) : WatchTapAction

    /**
     * WM_TAP_ACTIONS.md 里 `w_heart_rate` / `widget_health` / `w_fit` 属于
     * "可配置命名动作"：正主指向用户映射的心率/健康应用，未映射时保持
     * 静默但动作仍然"被识别"。Flue 没有 tap action 映射设置，所以运行时
     * 至少要给出触觉反馈并触发一次新的心率测量。
     */
    data object HeartRate : WatchTapAction

    /**
     * `sw_start_stop` / `sw_reset`：表盘内共享秒表（`{sw*}` 一族背后那一个）。
     *
     * 上游把这两个动作放在渲染器层（`WM_TAP_ACTIONS.md` §6：「handled at the
     * RENDERER level (Lua, no Java handler)」），所以它们不走宿主的应用映射，
     * 直接操作 [WatchStopwatch]。
     */
    data object StopwatchStartStop : WatchTapAction

    data object StopwatchReset : WatchTapAction

    /** Renderer-owned WatchMaker accent-color cycling. `select` is the same as `next`. */
    data class ColorSwitch(val direction: Int) : WatchTapAction

    /**
     * 内建媒体控制：`media_play_pause` / `media_play` / `media_pause` /
     * `media_next` / `media_prev`。
     *
     * 老师报「这个表盘有很多不支持的 Action，比如音乐播放」—— 这一族以前**根本没解析**，
     * 于是全部落到 [Unsupported]，点一下只弹一句「动作不可用」。
     */
    data class Media(val key: WatchMediaKey) : WatchTapAction

    /** 内建音量：`vol_up` / `vol_down`（调的是媒体流，并且要显示系统音量条）。 */
    data class Volume(val raise: Boolean) : WatchTapAction

    /** 内建闹钟：`set_alarm_HH:MM`。时间在解析期就校验过，这里恒为合法值。 */
    data class SetAlarm(val hour: Int, val minute: Int) : WatchTapAction

    /** Execute inside the loaded face's shared CloX-compatible Lua sandbox. */
    data class Script(val source: String) : WatchTapAction

    /**
     * w_app:com.example.pkg/.MainActivity 或 m_app:com.example.pkg
     * 或 WatchMaker 社区格式 w_app:label`pkg`activity（backtick 分隔）。
     * 格式：前缀后跟 packageName，可选 /activityClass 或 `分隔的三段式。
     */
    data class AppLaunch(val packageName: String, val activityClass: String?) : WatchTapAction

    data class Unsupported(val reason: String) : WatchTapAction
}

/**
 * 内建媒体控制动作（`WM_TAP_ACTIONS.md` §1）。
 *
 * 上游是直接 dispatch 媒体键，所以**不需要任何权限、也不关心是哪个播放器** ——
 * 系统把键送给当前持有媒体会话的那个应用。这也是为什么它属于「built-in，无需配置」，
 * 而 `w_music`（打开音乐应用）属于要用户映射的那一类。
 */
enum class WatchMediaKey { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS }

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
        if (lower == "open_settings") return WatchTapAction.OpenSettings
        if (lower == "battery_status") return WatchTapAction.BatteryStatus
        // 内建动作优先于可配置命名动作 —— 上游的 dispatch 顺序就是
        // 「built-in first, then configurable」（WM_TAP_ACTIONS.md §「How it works」）。
        MEDIA_ACTIONS[lower]?.let { return WatchTapAction.Media(it) }
        if (lower == "vol_up") return WatchTapAction.Volume(raise = true)
        if (lower == "vol_down") return WatchTapAction.Volume(raise = false)
        if (lower.startsWith(SET_ALARM_PREFIX)) {
            // `set_alarm_07:30`。时间在这里就校验掉：交给宿主之前先保证是合法钟点，
            // 免得把 `set_alarm_99:99` 这种脏值塞进 AlarmClock 的 extra 里。
            val time = lower.removePrefix(SET_ALARM_PREFIX).split(':')
            val hour = time.getOrNull(0)?.trim()?.toIntOrNull()
            val minute = time.getOrNull(1)?.trim()?.toIntOrNull()
            return if (time.size != 2 || hour == null || minute == null ||
                hour !in 0..23 || minute !in 0..59
            ) {
                WatchTapAction.Unsupported("malformed alarm time")
            } else {
                WatchTapAction.SetAlarm(hour = hour, minute = minute)
            }
        }
        if (lower.isBlank()) return WatchTapAction.Unsupported("empty tap action")
        if (lower.startsWith("script:")) {
            val source = value.substringAfter(':').trim()
            return if (source.isBlank()) WatchTapAction.Unsupported("empty face script")
            else WatchTapAction.Script(source)
        }
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
        if (lower.startsWith("w_link:")) {
            val uri = value.substringAfter(':').trim()
            val parsed = runCatching { URI(uri) }.getOrNull()
            return if (uri.isBlank() || parsed?.scheme.isNullOrBlank() || uri.any(Char::isWhitespace)) {
                WatchTapAction.Unsupported("malformed deep link")
            } else {
                WatchTapAction.DeepLink(uri)
            }
        }
        if (lower in NAMED_ACTIONS) return WatchTapAction.Named(lower)
        // 表盘内共享秒表的两个动作。上游把它们归在「渲染器层处理、没有 Java
        // handler」那一类（`WM_TAP_ACTIONS.md` §6），所以这里给它们独立的类型，
        // 由渲染侧直接改 [WatchStopwatch]，不经宿主的应用映射。
        if (lower == "sw_start_stop") return WatchTapAction.StopwatchStartStop
        if (lower == "sw_reset") return WatchTapAction.StopwatchReset
        if (lower == "color_switch_next" || lower == "color_switch_select") {
            return WatchTapAction.ColorSwitch(direction = 1)
        }
        if (lower == "color_switch_prev") return WatchTapAction.ColorSwitch(direction = -1)
        // 包含 / : . 的其他格式（URL、component name 等）暂不支持
        if (lower.contains('/') || lower.contains(':') || lower.contains('.'))
            return WatchTapAction.Unsupported("custom intents are disabled")
        // 带上原始动作名：否则用户只看到一句「动作不支持」，既不知道是哪个按钮、
        // 也没法反馈给我们（老师那张 17 页测试表盘就是这样，实际缺的是 color_switch_*）。
        return WatchTapAction.Unsupported("unsupported action: $lower")
    }

    private val MEDIA_ACTIONS = mapOf(
        "media_play_pause" to WatchMediaKey.PLAY_PAUSE,
        "media_play" to WatchMediaKey.PLAY,
        "media_pause" to WatchMediaKey.PAUSE,
        "media_next" to WatchMediaKey.NEXT,
        "media_prev" to WatchMediaKey.PREVIOUS,
    )

    private const val SET_ALARM_PREFIX = "set_alarm_"

    /**
     * 可配置命名动作。前半段**与上游 `TapActionHandler.CONFIGURABLE_ACTIONS` 逐字对齐**
     * （`WM_TAP_ACTIONS.md` §3），后半段是 Flue 早年自己加的拼法。
     *
     * 认得出来很重要：命名动作是「识别了但可能没映射」，落到 [WatchTapAction.Unsupported]
     * 会当场弹一句「表盘点击动作不可用」。老师报「这个表盘有很多不支持的 Action」，
     * 有一半就是 `w_dialer` / `w_map` / `voice_search` 这些**上游有、这份名单里漏了**的名字。
     */
    /**
     * 设置页「表盘点击动作」按这张表出行 —— 名单只有一份，界面与解析永远同步。
     * 顺序照上游文档的排布，方便逐条对照。
     */
    val CONFIGURABLE_ACTIONS: List<String> get() = NAMED_ACTIONS.toList()

    /**
     * 这个动作名是不是「用户可在设置里映射到具体应用」的那一类。
     *
     * 存在的理由：解析器会把 `widget_weather` / `widget_calendar` / `widget_health` 这些
     * **可配置**名字短路成强类型动作（[WatchTapAction.Weather] 等），于是运行时光看解析
     * 结果分不出「这个动作用户配得了吗」。渲染层据此决定要不要先查映射表 —— 直接查
     * [CONFIGURABLE_ACTIONS] 会每次拷一份 list，这里走同一个 Set，零分配。
     */
    fun isConfigurable(action: String?): Boolean =
        action?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) in NAMED_ACTIONS

    private val NAMED_ACTIONS = setOf(
        // ── 上游 26 个 ──────────────────────────────────────────────────────
        "tap_launcher", "widget_weather", "widget_health", "widget_calendar",
        "w_agenda", "w_fit", "w_heart_rate", "w_compass",
        "w_keep", "m_email", "m_calendar", "voice_search",
        "w_contacts", "w_sports", "w_gallery", "w_messaging",
        "w_camera", "w_dialer", "w_music", "w_clock",
        "w_calculator", "w_payment", "w_map", "browser",
        "flashlight", "socialmedia",
        // ── Flue 旧有别名（社区表盘里确实出现过）────────────────────────────
        "w_email", "w_sms", "w_phone", "w_maps", "w_alarm", "w_timer",
        "w_stopwatch", "w_weather", "w_calendar", "w_recorder", "w_settings",
        "m_camera", "m_clock", "m_music", "m_calculator", "m_weather",
    )
}

/** One already-evaluated candidate in WatchMaker's local design space. */
data class WatchTapHitCandidate(
    val containsPoint: Boolean,
    val centerDistance: Float,
    val opacity: Float,
)

/**
 * Mirrors WatchSkin's hit arbitration: opacity -1 alone disables a zone, an
 * in-bounds or sub-50-unit candidate participates, and the nearest centre wins.
 */
fun selectNearestWatchTapHitIndex(
    candidates: List<WatchTapHitCandidate>,
    graceRadius: Float = 50f,
): Int? {
    var bestIndex = -1
    var bestDistance = Float.POSITIVE_INFINITY
    candidates.forEachIndexed { index, candidate ->
        val distance = candidate.centerDistance
        if (
            candidate.opacity != -1f &&
            distance.isFinite() &&
            (candidate.containsPoint || distance < graceRadius) &&
            distance < bestDistance
        ) {
            bestIndex = index
            bestDistance = distance
        }
    }
    return bestIndex.takeIf { it >= 0 }
}

/** UL/WatchSkin accent palette. Index 0 is intentionally reserved for the face default. */
val WATCH_COLOR_SWITCH_PALETTE: List<String> = listOf(
    "5987b2", "20b2aa", "a10d09", "0bf9ab", "ffa419", "e6f0fa", "f0e6fa",
    "faecf3", "f6f5f0", "e6effa", "e6e6fa", "ffcccc", "306a9f", "fef65b",
    "ffcccc", "e6e6fa", "a1090d", "a10900", "12e6a0", "660066", "cce5cc",
    "004c64", "20b2aa", "40e0d0", "7fffd4", "fff8e7", "f04b23", "d8b2d8",
    "008aa0", "3b444b", "ac8141", "1e90ff", "838da6", "8b0000", "ffcccc",
    "e5f2e5", "cce5cc", "fbe6fb", "ffffcc", "000000", "ffffff",
)

data class WatchColorSwitchSelection(val index: Int, val color: String)

/** Applies a persisted index or advances it, wrapping through default + 41 palette colors. */
fun selectWatchColor(
    defaultColor: String?,
    currentIndex: Int,
    direction: Int,
): WatchColorSwitchSelection {
    val colorCount = WATCH_COLOR_SWITCH_PALETTE.size + 1
    val normalizedCurrent = ((currentIndex % colorCount) + colorCount) % colorCount
    val nextIndex = ((normalizedCurrent + direction) % colorCount + colorCount) % colorCount
    val normalizedDefault = defaultColor.orEmpty().trim().removePrefix("#").ifBlank { "ffffff" }
    return WatchColorSwitchSelection(
        index = nextIndex,
        color = if (nextIndex == 0) normalizedDefault else WATCH_COLOR_SWITCH_PALETTE[nextIndex - 1],
    )
}
