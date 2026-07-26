package com.flue.launcher.watchface.standard

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** The canvas contract used by WatchMaker exports. Values are design pixels. */
object WatchmakerGeometry {
    const val DESIGN_SIZE = 512f

    fun coordinate(raw: String?, fallback: Float): Float =
        raw?.trim()?.toFloatOrNull()?.takeIf(Float::isFinite) ?: fallback

    fun dimension(raw: String?, fallback: Float): Float =
        coordinate(raw, fallback).coerceAtLeast(0f)

    fun textSize(raw: String?, fallback: Float): Float =
        coordinate(raw, fallback).coerceIn(1f, DESIGN_SIZE)
}

data class WatchCurveArc(
    val startDegrees: Float,
    val sweepDegrees: Float,
)

/** Text metrics shared by runtime and editor preview. */
object WatchTextLayout {
    const val TTF_FONT_SCALE = 1.28f

    fun ttfTextSize(designSize: Float): Float =
        (designSize.coerceIn(1f, WatchmakerGeometry.DESIGN_SIZE) * TTF_FONT_SCALE)
            .coerceAtMost(WatchmakerGeometry.DESIGN_SIZE * TTF_FONT_SCALE)

    fun curveArc(direction: String?): WatchCurveArc =
        if (direction.equals("Down", ignoreCase = true)) {
            WatchCurveArc(startDegrees = 180f, sweepDegrees = -180f)
        } else {
            WatchCurveArc(startDegrees = 180f, sweepDegrees = 180f)
        }
}

internal data class WatchCanvasTransform(
    val scale: Float,
    val left: Float,
    val top: Float,
)

/**
 * Maps WatchMaker's square design canvas into the current viewport.
 *
 * The default mode fits the complete canvas and keeps the definition's shape
 * clipping semantics. Force-fill uses a centered crop, so rectangular screens
 * never anchor the face to one edge.
 */
internal fun watchCanvasTransform(
    viewWidth: Float,
    viewHeight: Float,
    forceFill: Boolean,
): WatchCanvasTransform {
    require(viewWidth.isFinite() && viewWidth > 0f) { "viewWidth must be positive" }
    require(viewHeight.isFinite() && viewHeight > 0f) { "viewHeight must be positive" }
    val designSize = WatchmakerGeometry.DESIGN_SIZE
    val scale = (if (forceFill) max(viewWidth, viewHeight) else min(viewWidth, viewHeight)) / designSize
    return WatchCanvasTransform(
        scale = scale,
        left = (viewWidth - designSize * scale) / 2f,
        top = (viewHeight - designSize * scale) / 2f,
    )
}

enum class WatchFaceShape {
    ROUND,
    SQUARE,
    RECTANGULAR,
    UNKNOWN,
}

fun watchFaceShape(raw: String?): WatchFaceShape {
    return when (raw.orEmpty().trim().lowercase(Locale.ROOT)) {
        "r", "round", "circle", "circular" -> WatchFaceShape.ROUND
        "s", "square" -> WatchFaceShape.SQUARE
        "rect", "rectangle", "rectangular", "w" -> WatchFaceShape.RECTANGULAR
        else -> WatchFaceShape.UNKNOWN
    }
}

/**
 * A validated, read-only font index. Building the index performs all file I/O
 * and is intended to run from a worker dispatcher. Rendering only performs
 * map lookups and Typeface loading on its existing worker job.
 */
class WatchFontIndex private constructor(
    private val byPath: Map<String, File>,
    private val byName: Map<String, File>,
    private val byStem: Map<String, File>,
    val rejectedFiles: List<String>,
) {
    fun resolve(reference: String?): File? {
        val normalized = normalizeFontReference(reference) ?: return null
        val candidates = LinkedHashSet<String>()
        fun add(value: String) {
            if (value.isBlank()) return
            candidates += value
            if (fontExtension(value) == null) {
                FONT_EXTENSIONS.forEach { extension -> candidates += "$value.$extension" }
            }
        }

        add(normalized)
        add(normalized.substringAfterLast('/'))
        if (!normalized.startsWith("fonts/")) add("fonts/$normalized")
        candidates.forEach { candidate ->
            byPath[candidate]?.let { return it }
            byName[candidate.substringAfterLast('/')]
                ?.let { return it }
            byStem[candidate.substringAfterLast('/').substringBeforeLast('.')]
                ?.let { return it }
        }
        return null
    }

    companion object {
        private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

        fun build(root: File, files: Iterable<File> = root.walkTopDown().filter(File::isFile).asIterable()): WatchFontIndex {
            val byPath = HashMap<String, File>()
            val byName = HashMap<String, File>()
            val byStem = HashMap<String, File>()
            val ambiguousNames = HashSet<String>()
            val ambiguousStems = HashSet<String>()
            val rejected = ArrayList<String>()
            files.forEach { file ->
                if (!file.isFile || file.extension.lowercase(Locale.ROOT) !in FONT_EXTENSIONS) return@forEach
                val relative = runCatching {
                    file.relativeTo(root).invariantSeparatorsPath.lowercase(Locale.ROOT)
                }.getOrNull() ?: return@forEach
                if (!isSfnt(file)) {
                    rejected += relative
                    return@forEach
                }
                byPath.putIfAbsent(relative, file)
                val name = file.name.lowercase(Locale.ROOT)
                val stem = file.nameWithoutExtension.lowercase(Locale.ROOT)
                // Ambiguous basenames/stems are deliberately removed rather
                // than chosen by filesystem traversal order.
                putUnique(byName, ambiguousNames, name, file)
                putUnique(byStem, ambiguousStems, stem, file)
            }
            return WatchFontIndex(byPath, byName, byStem, rejected)
        }

        private fun putUnique(
            map: MutableMap<String, File>,
            ambiguous: MutableSet<String>,
            key: String,
            value: File,
        ) {
            if (key in ambiguous) return
            val previous = map[key]
            when {
                previous == null -> map[key] = value
                previous !== value -> {
                    map.remove(key)
                    ambiguous += key
                }
            }
        }

        private fun isSfnt(file: File): Boolean {
            return runCatching {
                FileInputStream(file).use { input ->
                    val header = ByteArray(4)
                    readFully(input, header) == 4 && isSfntHeader(header)
                }
            }.getOrDefault(false)
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Int {
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                if (count < 0) break
                if (count == 0) continue
                offset += count
            }
            return offset
        }

        private fun isSfntHeader(header: ByteArray): Boolean {
            if (header.size < 4) return false
            return (header[0].toInt() and 0xff) == 0x00 &&
                (header[1].toInt() and 0xff) == 0x01 &&
                (header[2].toInt() and 0xff) == 0x00 &&
                (header[3].toInt() and 0xff) == 0x00 ||
                header.contentEquals(byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte())) ||
                header.contentEquals(byteArrayOf('t'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(), 'f'.code.toByte())) ||
                header.contentEquals(byteArrayOf('t'.code.toByte(), 'r'.code.toByte(), 'u'.code.toByte(), 'e'.code.toByte())) ||
                header.contentEquals(byteArrayOf('t'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), '1'.code.toByte()))
        }

        private fun normalizeFontReference(reference: String?): String? {
            val raw = reference.orEmpty().trim()
            val value = (if (raw.startsWith("bm:", ignoreCase = true)) raw.substring(3) else raw)
                .replace('\\', '/')
                .trimStart('/')
                .lowercase(Locale.ROOT)
            if (value.isBlank() || value.contains('\u0000')) return null
            val segments = value.split('/').filter { it.isNotBlank() }
            if (segments.any { it == "." || it == ".." }) return null
            return segments.joinToString("/")
        }

        private fun fontExtension(value: String): String? =
            value.substringAfterLast('.', "").takeIf { it in FONT_EXTENSIONS }
    }
}

data class WatchDataSnapshot(
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val steps: Int? = null,
    val heartRateBpm: Int? = null,
    val gyroscopeX: Float? = null,
    val gyroscopeY: Float? = null,
    val gyroscopeZ: Float? = null,
    val wifiSignalFraction: Float? = null,
    val bluetoothEnabled: Boolean? = null,
    val weatherTemperature: String? = null,
    val weatherCondition: String? = null,
    val weatherIcon: String? = null,
    val weatherHigh: String? = null,
    val weatherLow: String? = null,
    val weatherTemperatureUnit: String? = null,
    val weatherHumidity: Int? = null,
    val weatherLocation: String? = null,
)

fun interface WatchDataProvider {
    fun snapshot(nowEpochMs: Long): WatchDataSnapshot
}

/** Optional, application-owned weather source. No location/network permission is requested here. */
fun interface WatchWeatherSource {
    fun snapshot(nowEpochMs: Long): WatchWeatherSnapshot?
}

data class WatchWeatherSnapshot(
    val temperature: String? = null,
    val condition: String? = null,
    val icon: String? = null,
    val high: String? = null,
    val low: String? = null,
    val temperatureUnit: String? = null,
    val humidity: Int? = null,
    val location: String? = null,
)

object EmptyWatchDataProvider : WatchDataProvider {
    private val empty = WatchDataSnapshot()
    override fun snapshot(nowEpochMs: Long): WatchDataSnapshot = empty
}

enum class WatchSourceReloadMode {
    CLEAR_BEFORE_LOAD,
    KEEP_CURRENT_UNTIL_READY,
}

/**
 * Refreshing the same installed face must not expose an empty renderer while
 * watch.xml is parsed again. A different descriptor cannot reuse the previous
 * frame because it belongs to another package.
 */
fun watchSourceReloadMode(
    currentDescriptorKey: String?,
    nextDescriptorKey: String,
    hasPreparedSource: Boolean,
): WatchSourceReloadMode =
    if (hasPreparedSource && currentDescriptorKey == nextDescriptorKey) {
        WatchSourceReloadMode.KEEP_CURRENT_UNTIL_READY
    } else {
        WatchSourceReloadMode.CLEAR_BEFORE_LOAD
    }

/**
 * Revision key for a single immutable watch asset. It deliberately excludes the
 * UI refresh token: refreshing watch.xml must not invalidate every decoded
 * bitmap/font/movie when the underlying file is unchanged.
 */
internal fun watchAssetCacheRevision(
    rootKey: String,
    relativePath: String,
    length: Long,
    lastModifiedEpochMs: Long,
): String = buildString(rootKey.length + relativePath.length + 48) {
    append(rootKey)
    append('\u0000')
    append(relativePath)
    append('\u0000')
    append(length.coerceAtLeast(0L))
    append('\u0000')
    append(lastModifiedEpochMs.coerceAtLeast(0L))
}

/**
 * Resolves parent links without allowing malformed self/cyclic relationships
 * to detach every layer from the render roots. Invalid links are promoted to
 * roots while valid group relationships are preserved.
 */
internal const val MAX_WATCH_LAYER_COUNT = 2_048
internal const val MAX_WATCH_PARENT_DEPTH = 64

internal data class WatchLayerGraphDiagnostics(
    val inputLayerCount: Int,
    val acceptedLayerCount: Int,
    val duplicateIdCount: Int,
    val invalidParentCount: Int,
    val cycleLayerCount: Int,
    val depthLimitBreakCount: Int,
) {
    val truncatedLayerCount: Int
        get() = (inputLayerCount - acceptedLayerCount).coerceAtLeast(0)

    val isDegraded: Boolean
        get() = truncatedLayerCount > 0 ||
            duplicateIdCount > 0 ||
            invalidParentCount > 0 ||
            cycleLayerCount > 0 ||
            depthLimitBreakCount > 0
}

internal data class WatchLayerGraphResolution(
    val parentIndices: List<Int?>,
    val diagnostics: WatchLayerGraphDiagnostics,
)

/**
 * Resolve the functional parent graph in linear time. The accepted prefix and
 * depth cap are deliberate compatibility fallbacks for hostile or malformed
 * archives: later layers are reported as truncated and a too-deep link starts
 * a new root instead of making preparation or rendering quadratic.
 */
internal fun resolveWatchLayerGraph(layers: List<WatchmakerLayer>): WatchLayerGraphResolution {
    val acceptedLayerCount = minOf(layers.size, MAX_WATCH_LAYER_COUNT)
    val firstIndexById = HashMap<String, Int>(acceptedLayerCount)
    var duplicateIdCount = 0
    repeat(acceptedLayerCount) { index ->
        val layer = layers[index]
        layer.id?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
            if (firstIndexById.putIfAbsent(id, index) != null) duplicateIdCount++
        }
    }

    val parents = IntArray(acceptedLayerCount) { NO_PARENT_INDEX }
    var invalidParentCount = 0
    repeat(acceptedLayerCount) { index ->
        val parentId = layers[index].parentId?.trim()?.takeIf(String::isNotEmpty)
            ?: return@repeat
        val requestedParent = firstIndexById[parentId]
        if (
            requestedParent == null ||
            requestedParent == index ||
            !layers[requestedParent].isGroup
        ) {
            invalidParentCount++
        } else {
            parents[index] = requestedParent
        }
    }

    // Every node has at most one outgoing parent edge, so a three-state walk
    // finds all cycles without allocating a HashSet for each layer.
    val visitState = ByteArray(acceptedLayerCount)
    val pathPosition = IntArray(acceptedLayerCount) { NO_PARENT_INDEX }
    val walkPath = ArrayList<Int>(minOf(acceptedLayerCount, MAX_WATCH_PARENT_DEPTH + 1))
    var cycleLayerCount = 0
    repeat(acceptedLayerCount) { start ->
        if (visitState[start].toInt() != VISIT_UNSEEN) return@repeat
        walkPath.clear()
        var cursor = start
        while (cursor != NO_PARENT_INDEX && visitState[cursor].toInt() == VISIT_UNSEEN) {
            visitState[cursor] = VISIT_ACTIVE.toByte()
            pathPosition[cursor] = walkPath.size
            walkPath += cursor
            cursor = parents[cursor]
        }
        if (cursor != NO_PARENT_INDEX && visitState[cursor].toInt() == VISIT_ACTIVE) {
            val cycleStart = pathPosition[cursor]
            if (cycleStart >= 0) {
                for (pathIndex in cycleStart..walkPath.lastIndex) {
                    val cycleLayer = walkPath[pathIndex]
                    parents[cycleLayer] = NO_PARENT_INDEX
                    cycleLayerCount++
                }
            }
        }
        walkPath.forEach { layerIndex ->
            visitState[layerIndex] = VISIT_DONE.toByte()
            pathPosition[layerIndex] = NO_PARENT_INDEX
        }
    }

    // Memoized depths make shared ancestors O(1) after their first walk.
    val resolvedDepth = IntArray(acceptedLayerCount) { UNKNOWN_DEPTH }
    val depthPath = ArrayList<Int>(minOf(acceptedLayerCount, MAX_WATCH_PARENT_DEPTH + 1))
    var depthLimitBreakCount = 0
    repeat(acceptedLayerCount) { start ->
        if (resolvedDepth[start] != UNKNOWN_DEPTH) return@repeat
        depthPath.clear()
        var cursor = start
        while (cursor != NO_PARENT_INDEX && resolvedDepth[cursor] == UNKNOWN_DEPTH) {
            depthPath += cursor
            cursor = parents[cursor]
        }
        for (pathIndex in depthPath.lastIndex downTo 0) {
            val layerIndex = depthPath[pathIndex]
            val parentIndex = parents[layerIndex]
            val depth = if (parentIndex == NO_PARENT_INDEX) {
                0
            } else {
                resolvedDepth[parentIndex] + 1
            }
            if (depth > MAX_WATCH_PARENT_DEPTH) {
                parents[layerIndex] = NO_PARENT_INDEX
                resolvedDepth[layerIndex] = 0
                depthLimitBreakCount++
            } else {
                resolvedDepth[layerIndex] = depth
            }
        }
    }

    return WatchLayerGraphResolution(
        parentIndices = List(acceptedLayerCount) { index ->
            parents[index].takeUnless { it == NO_PARENT_INDEX }
        },
        diagnostics = WatchLayerGraphDiagnostics(
            inputLayerCount = layers.size,
            acceptedLayerCount = acceptedLayerCount,
            duplicateIdCount = duplicateIdCount,
            invalidParentCount = invalidParentCount,
            cycleLayerCount = cycleLayerCount,
            depthLimitBreakCount = depthLimitBreakCount,
        ),
    )
}

internal fun resolveWatchLayerParentIndices(layers: List<WatchmakerLayer>): List<Int?> =
    resolveWatchLayerGraph(layers).parentIndices

internal data class WatchLayerAncestorChain(
    val layerIndex: Int,
    val outer: WatchLayerAncestorChain?,
)

internal data class WatchLayerRenderItem(
    val layerIndex: Int,
    val ancestorChain: WatchLayerAncestorChain?,
) {
    /**
     * Compatibility/debug view of the chain. Production drawing walks the
     * shared nodes directly and never materializes one list per drawable.
     */
    val ancestorIndices: List<Int>
        get() {
            val innerToOuter = ArrayList<Int>()
            var cursor = ancestorChain
            while (cursor != null) {
                innerToOuter += cursor.layerIndex
                cursor = cursor.outer
            }
            innerToOuter.reverse()
            return innerToOuter
        }
}

internal data class WatchLayerRenderPlanResult(
    val items: List<WatchLayerRenderItem>,
    val diagnostics: WatchLayerGraphDiagnostics,
)

/**
 * Keeps WatchMaker's global document z-order while carrying the group chain
 * needed to reproduce inherited transforms for each drawable layer.
 */
internal fun buildWatchLayerRenderPlanResult(layers: List<WatchmakerLayer>): WatchLayerRenderPlanResult {
    val resolution = resolveWatchLayerGraph(layers)
    val parentIndices = resolution.parentIndices
    val chainsByGroup = arrayOfNulls<WatchLayerAncestorChain>(parentIndices.size)

    fun chainFor(parentIndex: Int?): WatchLayerAncestorChain? {
        parentIndex ?: return null
        chainsByGroup[parentIndex]?.let { return it }
        val unresolved = ArrayList<Int>()
        var cursor: Int? = parentIndex
        var outer: WatchLayerAncestorChain? = null
        while (cursor != null) {
            val cached = chainsByGroup[cursor]
            if (cached != null) {
                outer = cached
                break
            }
            unresolved += cursor
            cursor = parentIndices[cursor]
        }
        for (index in unresolved.lastIndex downTo 0) {
            val layerIndex = unresolved[index]
            outer = WatchLayerAncestorChain(layerIndex = layerIndex, outer = outer)
            chainsByGroup[layerIndex] = outer
        }
        return chainsByGroup[parentIndex]
    }

    val items = ArrayList<WatchLayerRenderItem>(parentIndices.size)
    parentIndices.indices.forEach { layerIndex ->
        if (!layers[layerIndex].isGroup) {
            items += WatchLayerRenderItem(
                layerIndex = layerIndex,
                ancestorChain = chainFor(parentIndices[layerIndex]),
            )
        }
    }
    return WatchLayerRenderPlanResult(items = items, diagnostics = resolution.diagnostics)
}

internal fun buildWatchLayerRenderPlan(layers: List<WatchmakerLayer>): List<WatchLayerRenderItem> =
    buildWatchLayerRenderPlanResult(layers).items

private const val NO_PARENT_INDEX = -1
private const val UNKNOWN_DEPTH = -1
private const val VISIT_UNSEEN = 0
private const val VISIT_ACTIVE = 1
private const val VISIT_DONE = 2

internal data class DailyStepState(
    val dayKey: Long,
    val rawCounter: Int,
    val steps: Int,
)

/**
 * TYPE_STEP_COUNTER is cumulative since boot, not "today's steps". Convert it
 * to a persisted daily delta while retaining the accumulated day total across
 * a sensor reset/reboot.
 */
internal fun accumulateDailySteps(
    previous: DailyStepState?,
    dayKey: Long,
    rawCounter: Int,
): DailyStepState {
    val raw = rawCounter.coerceAtLeast(0)
    if (previous == null || previous.dayKey != dayKey) {
        return DailyStepState(dayKey = dayKey, rawCounter = raw, steps = 0)
    }
    val delta = if (raw >= previous.rawCounter) raw.toLong() - previous.rawCounter else 0L
    return DailyStepState(
        dayKey = dayKey,
        rawCounter = raw,
        steps = (previous.steps.toLong() + delta).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
    )
}

sealed class WatchTagResolution(open val renderedValue: String) {
    data class Resolved(override val renderedValue: String) : WatchTagResolution(renderedValue)
    data class Unavailable(val tag: String) : WatchTagResolution("--")
    data class Unsupported(val tag: String) : WatchTagResolution("--")
}

/**
 * Bounded, immutable variables visible to attribute expressions. Only simple
 * top-level `var_* = literal` declarations are imported; function bodies and
 * executable Lua statements are never run by the Canvas fallback runtime.
 */
class WatchExpressionScope private constructor(
    val variables: Map<String, String>,
) {
    fun resolve(name: String): String? = variables[name.lowercase(Locale.ROOT)]

    companion object {
        val EMPTY = WatchExpressionScope(emptyMap())
        private const val MAX_SCRIPT_BYTES = 256 * 1024
        private const val MAX_VARIABLES = 128
        private const val MAX_VALUE_LENGTH = 1_024
        private val ASSIGNMENT = Regex(
            "^\\s*(var_[A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*(?:--.*)?$",
        )

        fun fromInitialScript(script: String?): WatchExpressionScope {
            val source = script.orEmpty()
            if (source.isBlank() || source.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) return EMPTY
            val values = LinkedHashMap<String, String>()
            var functionDepth = 0
            source.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (functionDepth > 0) {
                    when {
                        line == "end" || line.startsWith("end ") || line.startsWith("until ") ->
                            functionDepth--
                        opensLuaBlock(line) -> functionDepth++
                    }
                    return@forEach
                }
                if (line.startsWith("function ") || line.startsWith("local function ")) {
                    functionDepth = 1
                    return@forEach
                }
                if (values.size >= MAX_VARIABLES) return@forEach
                val match = ASSIGNMENT.matchEntire(rawLine) ?: return@forEach
                val name = match.groupValues[1].lowercase(Locale.ROOT)
                val literal = parseLiteral(match.groupValues[2]) ?: return@forEach
                if (literal.length <= MAX_VALUE_LENGTH) values.putIfAbsent(name, literal)
            }
            return if (values.isEmpty()) EMPTY else WatchExpressionScope(values.toMap())
        }

        private fun opensLuaBlock(line: String): Boolean =
            line.startsWith("function ") || line.startsWith("local function ") ||
                Regex("^if\\b.*\\bthen\\s*$").containsMatchIn(line) ||
                Regex("^(?:for|while)\\b.*\\bdo\\s*$").containsMatchIn(line) ||
                line == "repeat"

        private fun parseLiteral(raw: String): String? {
            val value = raw.trim()
            if (value.length > MAX_VALUE_LENGTH || value.isEmpty()) return null
            if (value.equals("true", true) || value.equals("false", true) || value == "nil") {
                return value.lowercase(Locale.ROOT)
            }
            if (value.toDoubleOrNull()?.isFinite() == true) return value
            val quote = value.firstOrNull()
            if ((quote == '\'' || quote == '"') && value.lastOrNull() == quote && value.length >= 2) {
                return value.substring(1, value.lastIndex)
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\$quote", quote.toString())
                    .replace("\\\\", "\\")
            }
            return null
        }
    }
}

/**
 * Stable tag vocabulary shared by preview/runtime/editor. Unknown tags are
 * explicitly marked unsupported, never returned as raw `{tag}` text.
 */
object WatchmakerTagResolver {
    private val knownUnavailableTags = setOf(
        // Weather/calendar/location providers are optional on Android.  They
        // stay known so a missing provider renders "--" and never leaks the
        // literal "{tag}" into a face.
        "weather", "wcl", "wct", "wth", "wci", "whp", "wp", "wr",
        "wsr", "wss", "wisday", "wloc",
        "cal", "calendar", "next_event",
        "phone_battery", "pbl", "pblp", "pbc",
        "bv", "bi", "btc", "btf", "sscp", "stsc", "sthr", "shrp",
        "alat", "alon",
        "sax", "say", "saz", "sprs", "pwc", "nwip", "nc", "ncc", "ncs", "ngc",
    )

    private val builtInTags = setOf(
        "dh", "dhz", "dh23", "dh24", "dh23z", "dh24z",
        "dm", "dmz", "ds", "dss", "drss", "drs", "drm", "drh",
        "dd", "ddz", "ddw0", "ddw", "ddw1", "ddw2", "ddww",
        "dnn", "dnnn", "dnnnn", "dy", "dyyyy",
        "bl", "blp", "batt", "battery", "battery_level",
        "pbl", "pblp", "pbr",
        "bc", "charging", "is_charging",
        "ssc", "steps", "step_count", "shr", "heart", "heart_rate", "heart_rate_bpm",
        "sgx", "sgy", "sgz", "pws", "nbc",
        "wt", "wtemp", "weather_temperature",
        "wcond", "weather_condition", "wct",
        "wicon", "weather_icon", "wci",
        "whigh", "weather_high", "wth", "wthd",
        "wlow", "weather_low", "wtl", "wtld", "wtd",
        "whum", "weather_humidity", "wh",
        "wloc", "weather_location",
        "ucolor",
    )

    fun isKnown(tag: String): Boolean {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        return normalized in builtInTags || normalized in knownUnavailableTags
    }

    fun resolve(
        tag: String,
        now: Calendar,
        snapshot: WatchDataSnapshot,
        userColor: String? = null,
    ): WatchTagResolution {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        val hour24 = now.get(Calendar.HOUR_OF_DAY)
        val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
        val value = when (normalized) {
            "dh" -> hour12.toString()
            "dhz" -> hour12.toString().padStart(2, '0')
            "dh23", "dh24" -> hour24.toString()
            "dh23z", "dh24z" -> hour24.toString().padStart(2, '0')
            "dm" -> now.get(Calendar.MINUTE).toString()
            "dmz" -> now.get(Calendar.MINUTE).toString().padStart(2, '0')
            "ds" -> now.get(Calendar.SECOND).toString()
            "dss" -> now.get(Calendar.SECOND).toString().padStart(2, '0')
            "drss", "drs" ->
                ((now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1_000f) * 6f).toString()
            "drm" ->
                ((now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f) * 6f).toString()
            "drh" ->
                ((hour24 % 12 + now.get(Calendar.MINUTE) / 60f) * 30f).toString()
            "dd" -> now.get(Calendar.DAY_OF_MONTH).toString()
            "ddz" -> now.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
            "ddw0" -> (now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).toString()
            "ddw" -> now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())
            "ddw1" -> now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.NARROW_FORMAT, Locale.getDefault())
            "ddw2" -> now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())
                ?.take(2)
            "ddww" -> now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())
            "dnn" -> (now.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
            "dnnn" -> now.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault())
            "dnnnn" -> now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())
            "dy" -> (now.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
            "dyyyy" -> now.get(Calendar.YEAR).toString()
            "bl", "batt", "battery", "battery_level" -> snapshot.batteryPercent?.toString()
            "blp" -> snapshot.batteryPercent?.let { "$it%" }
            "pbl" -> snapshot.batteryPercent?.toString()
            "pblp" -> snapshot.batteryPercent?.let { "$it%" }
            "pbr" -> snapshot.batteryPercent?.let { (it * 3.6f).toString() }
            "bc", "charging", "is_charging" -> snapshot.isCharging?.let { if (it) "1" else "0" }
            "ssc", "steps", "step_count" -> snapshot.steps?.toString()
            "shr", "heart", "heart_rate", "heart_rate_bpm" -> snapshot.heartRateBpm?.toString()
            "sgx" -> renderCompactNumber(snapshot.gyroscopeX ?: 0f)
            "sgy" -> renderCompactNumber(snapshot.gyroscopeY ?: 0f)
            "sgz" -> renderCompactNumber(snapshot.gyroscopeZ ?: 0f)
            "pws" -> renderCompactNumber(
                (snapshot.wifiSignalFraction ?: 0f).coerceIn(0f, 1f),
            )
            "nbc" -> (snapshot.bluetoothEnabled ?: false).toString()
            "wt", "wtemp", "weather_temperature" -> snapshot.weatherTemperature
            "wtd" -> temperatureWithUnit(snapshot.weatherTemperature, snapshot.weatherTemperatureUnit)
            "wcond", "weather_condition", "wct" -> snapshot.weatherCondition
            "wicon", "weather_icon", "wci" -> snapshot.weatherIcon
            "whigh", "weather_high", "wth" -> snapshot.weatherHigh
            "wthd" -> temperatureWithUnit(snapshot.weatherHigh, snapshot.weatherTemperatureUnit)
            "wlow", "weather_low", "wtl" -> snapshot.weatherLow
            "wtld" -> temperatureWithUnit(snapshot.weatherLow, snapshot.weatherTemperatureUnit)
            "whum", "weather_humidity", "wh", "whp" -> snapshot.weatherHumidity?.toString()
            "wloc", "weather_location" -> snapshot.weatherLocation
            "ucolor" -> userColor
            else -> null
        }
        if (value != null) return WatchTagResolution.Resolved(value)
        return if (isKnown(normalized)) {
            WatchTagResolution.Unavailable(normalized)
        } else {
            WatchTagResolution.Unsupported(normalized)
        }
    }

    private fun temperatureWithUnit(value: String?, unit: String?): String? {
        val temperature = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if ('°' in temperature) return temperature
        val normalizedUnit = unit?.trim()?.removePrefix("°").orEmpty()
        return if (normalizedUnit.isBlank()) temperature else "$temperature°$normalizedUnit"
    }

    private fun renderCompactNumber(value: Float): String =
        if (value.isFinite() && value == value.toLong().toFloat()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}

/**
 * Small, deterministic expression engine for WatchMaker's hot-path numeric
 * attributes. It intentionally supports only side-effect-free Lua-style
 * arithmetic, comparisons and `and`/`or`/`not`; functions, assignments and
 * scripts remain unsupported and are surfaced by the compatibility report.
 */
object WatchmakerExpressionEvaluator {
    private val tagPattern = Regex("\\{([^{}]+)}")
    private const val MAX_EXPRESSION_LENGTH = 4 * 1024
    private const val MAX_EXPRESSION_TOKENS = 512
    private const val MAX_FUNCTION_ARGUMENTS = 16
    private const val MAX_RESULT_TEXT_LENGTH = 4 * 1024

    fun interpolate(
        raw: String,
        resolveTag: (String) -> WatchTagResolution,
    ): String = tagPattern.replace(raw) { match ->
        resolveTag(match.groupValues[1]).renderedValue
    }

    /**
     * Evaluate the expression forms WatchMaker commonly emits for text while
     * leaving ordinary labels as templates. Failed/unsupported code falls back
     * to tag interpolation, so one bad expression cannot blank the whole face.
     */
    fun evaluateText(
        raw: String,
        resolveTag: (String) -> WatchTagResolution,
    ): String = evaluateText(raw, WatchExpressionScope.EMPTY, resolveTag)

    fun evaluateText(
        raw: String,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): String {
        val template = interpolate(raw, resolveTag)
        val value = evaluate(raw, scope, resolveTag) ?: return template
        if (value === ExpressionValue.Nil ||
            value is ExpressionValue.Logical && !value.value
        ) {
            return template
        }
        if (value is ExpressionValue.Numeric && template.trim().toDoubleOrNull() != null) {
            // WatchMaker keeps zero-padded single-tag text such as "05".
            return template
        }
        return value.renderText().take(MAX_RESULT_TEXT_LENGTH)
    }

    fun evaluateNumber(
        raw: String?,
        fallback: Float,
        resolveTag: (String) -> WatchTagResolution,
    ): Float = evaluateNumber(raw, fallback, WatchExpressionScope.EMPTY, resolveTag)

    fun evaluateNumber(
        raw: String?,
        fallback: Float,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): Float {
        val value = evaluate(raw, scope, resolveTag) ?: return fallback
        val number = value.asNumber() ?: return fallback
        return number.toFloat().takeIf(Float::isFinite) ?: fallback
    }

    fun evaluateBoolean(
        raw: String?,
        fallback: Boolean,
        resolveTag: (String) -> WatchTagResolution,
    ): Boolean = evaluateBoolean(raw, fallback, WatchExpressionScope.EMPTY, resolveTag)

    fun evaluateBoolean(
        raw: String?,
        fallback: Boolean,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): Boolean {
        val value = evaluate(raw, scope, resolveTag) ?: return fallback
        return when (value) {
            is ExpressionValue.Logical -> value.value
            is ExpressionValue.Numeric -> value.value != 0.0
            is ExpressionValue.Text -> when {
                value.value.equals("true", true) -> true
                value.value.equals("false", true) -> false
                value.value.toDoubleOrNull() != null -> value.value.toDouble() != 0.0
                else -> value.value.isNotEmpty()
            }
            ExpressionValue.Nil -> false
        }
    }

    fun supports(raw: String): Boolean = supports(raw, WatchExpressionScope.EMPTY)

    fun supports(raw: String, scope: WatchExpressionScope): Boolean {
        if (raw.isBlank()) return true
        if (raw.length > MAX_EXPRESSION_LENGTH) return false
        val candidate = substituteTags(raw) { WatchTagResolution.Resolved("1") } ?: return false
        return runCatching { Parser(candidate, scope).parse() }.isSuccess
    }

    private fun evaluate(
        raw: String?,
        scope: WatchExpressionScope,
        resolveTag: (String) -> WatchTagResolution,
    ): ExpressionValue? {
        val source = raw?.trim().orEmpty()
        if (source.isBlank() || source.length > MAX_EXPRESSION_LENGTH) return null
        val substituted = try { substituteTags(source, resolveTag) } catch (_: Throwable) { null }
            ?: return null
        return runCatching { Parser(substituted, scope).parse() }.getOrNull()
    }

    /**
     * WatchMaker permits tags both as values and inside quoted strings. A
     * regex-only substitution corrupts the latter (`'{da}'` became a nested
     * quoted literal), so scan once while tracking the active quote.
     */
    private fun substituteTags(
        raw: String,
        resolveTag: (String) -> WatchTagResolution,
    ): String? {
        if (raw.length > MAX_EXPRESSION_LENGTH) return null
        val output = StringBuilder(raw.length + 32)
        var index = 0
        var quote: Char? = null
        while (index < raw.length) {
            val char = raw[index]
            if (char == '\u00a0' && quote == null) {
                output.append(' ')
                index++
                continue
            }
            if (char == '\\' && quote != null && index + 1 < raw.length) {
                output.append(char).append(raw[index + 1])
                index += 2
                continue
            }
            if (char == '\'' || char == '"') {
                if (quote == null) quote = char else if (quote == char) quote = null
                output.append(char)
                index++
                continue
            }
            if (char == '{') {
                val closing = raw.indexOf('}', startIndex = index + 1)
                if (closing > index + 1 && raw.substring(index + 1, closing).none { it == '{' }) {
                    val tag = raw.substring(index + 1, closing)
                    val resolution = resolveTag(tag)
                    if (quote == null) {
                        output.append(
                            when (resolution) {
                                is WatchTagResolution.Resolved -> resolution.renderedValue.toExpressionLiteral()
                                is WatchTagResolution.Unavailable,
                                is WatchTagResolution.Unsupported -> "nil"
                            },
                        )
                    } else {
                        val value = (resolution as? WatchTagResolution.Resolved)?.renderedValue.orEmpty()
                        output.append(value.escapeForQuote(quote))
                    }
                    index = closing + 1
                    continue
                }
            }
            output.append(char)
            index++
            if (output.length > MAX_EXPRESSION_LENGTH * 2) return null
        }
        if (quote != null) return null
        return output.toString()
    }

    private fun String.escapeForQuote(quote: Char): String = buildString(length) {
        this@escapeForQuote.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                quote -> append('\\').append(char)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private fun String.toExpressionLiteral(): String {
        val value = trim()
        if (value.toDoubleOrNull()?.isFinite() == true) return value
        if (value.equals("true", true) || value.equals("false", true) || value == "nil") {
            return value.lowercase(Locale.ROOT)
        }
        return buildString(value.length + 2) {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }

    private sealed interface ExpressionValue {
        fun asNumber(): Double?
        fun isTruthy(): Boolean
        fun renderText(): String

        data class Numeric(val value: Double) : ExpressionValue {
            override fun asNumber(): Double = value
            override fun isTruthy(): Boolean = true
            override fun renderText(): String = when {
                value.isFinite() && value == floor(value) && abs(value) <= 9_007_199_254_740_992.0 ->
                    value.toLong().toString()
                value.isFinite() -> String.format(Locale.ROOT, "%.6g", value)
                else -> ""
            }
        }

        data class Text(val value: String) : ExpressionValue {
            override fun asNumber(): Double? = value.toDoubleOrNull()
            override fun isTruthy(): Boolean = true
            override fun renderText(): String = value
        }

        data class Logical(val value: Boolean) : ExpressionValue {
            override fun asNumber(): Double = if (value) 1.0 else 0.0
            override fun isTruthy(): Boolean = value
            override fun renderText(): String = value.toString()
        }

        data object Nil : ExpressionValue {
            override fun asNumber(): Double? = null
            override fun isTruthy(): Boolean = false
            override fun renderText(): String = ""
        }
    }

    private enum class TokenType {
        NUMBER,
        STRING,
        TRUE,
        FALSE,
        NIL,
        IDENTIFIER,
        AND,
        OR,
        NOT,
        PLUS,
        MINUS,
        STAR,
        SLASH,
        PERCENT,
        POWER,
        CONCAT,
        DOT,
        COMMA,
        EQUAL,
        NOT_EQUAL,
        GREATER,
        GREATER_EQUAL,
        LESS,
        LESS_EQUAL,
        LEFT_PAREN,
        RIGHT_PAREN,
        END,
    }

    private data class Token(val type: TokenType, val text: String)

    private class Lexer(private val source: String) {
        private var index = 0

        fun tokens(): List<Token> = buildList {
            while (true) {
                require(size < MAX_EXPRESSION_TOKENS) { "Expression has too many tokens" }
                val token = nextToken()
                add(token)
                if (token.type == TokenType.END) break
            }
        }

        private fun nextToken(): Token {
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length) return Token(TokenType.END, "")
            val start = index
            val char = source[index++]
            return when (char) {
                '+' -> Token(TokenType.PLUS, "+")
                '-' -> Token(TokenType.MINUS, "-")
                '*' -> Token(TokenType.STAR, "*")
                '/' -> Token(TokenType.SLASH, "/")
                '%' -> Token(TokenType.PERCENT, "%")
                '^' -> Token(TokenType.POWER, "^")
                ',' -> Token(TokenType.COMMA, ",")
                '(' -> Token(TokenType.LEFT_PAREN, "(")
                ')' -> Token(TokenType.RIGHT_PAREN, ")")
                '=' -> {
                    require(match('=')) { "Assignment is not supported" }
                    Token(TokenType.EQUAL, "==")
                }
                '!', '~' -> {
                    require(match('=')) { "Unsupported operator at $start" }
                    Token(TokenType.NOT_EQUAL, source.substring(start, index))
                }
                '>' -> if (match('=')) Token(TokenType.GREATER_EQUAL, ">=") else Token(TokenType.GREATER, ">")
                '<' -> if (match('=')) Token(TokenType.LESS_EQUAL, "<=") else Token(TokenType.LESS, "<")
                '\'', '"' -> readString(char)
                '.' -> when {
                    match('.') -> Token(TokenType.CONCAT, "..")
                    index < source.length && source[index].isDigit() -> readNumber(start)
                    else -> Token(TokenType.DOT, ".")
                }
                else -> when {
                    char.isDigit() -> readNumber(start)
                    char.isLetter() || char == '_' -> readIdentifier(start)
                    else -> error("Unsupported expression character: $char")
                }
            }
        }

        private fun readNumber(start: Int): Token {
            var hasDecimalPoint = source[start] == '.'
            while (index < source.length) {
                val char = source[index]
                when {
                    char.isDigit() -> index++
                    char == '.' && !hasDecimalPoint &&
                        (index + 1 >= source.length || source[index + 1] != '.') -> {
                        hasDecimalPoint = true
                        index++
                    }
                    else -> break
                }
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                while (index < source.length && source[index].isDigit()) index++
            }
            val text = source.substring(start, index)
            require(text.toDoubleOrNull()?.isFinite() == true) { "Invalid number: $text" }
            return Token(TokenType.NUMBER, text)
        }

        private fun readIdentifier(start: Int): Token {
            while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
            val text = source.substring(start, index)
            val type = when (text.lowercase(Locale.ROOT)) {
                "true" -> TokenType.TRUE
                "false" -> TokenType.FALSE
                "nil" -> TokenType.NIL
                "and" -> TokenType.AND
                "or" -> TokenType.OR
                "not" -> TokenType.NOT
                else -> TokenType.IDENTIFIER
            }
            return Token(type, text)
        }

        private fun readString(quote: Char): Token {
            val result = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                if (char == quote) return Token(TokenType.STRING, result.toString())
                if (char == '\\') {
                    require(index < source.length) { "Unterminated escape" }
                    result.append(
                        when (val escaped = source[index++]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            '\\', '\'', '"' -> escaped
                            else -> escaped
                        },
                    )
                } else {
                    result.append(char)
                }
            }
            error("Unterminated string")
        }

        private fun match(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index++
            return true
        }
    }

    private class Parser(
        source: String,
        private val scope: WatchExpressionScope,
    ) {
        private val tokens = Lexer(source).tokens()
        private var index = 0

        fun parse(): ExpressionValue {
            val value = parseOr()
            require(peek().type == TokenType.END) { "Unexpected token: ${peek().text}" }
            return value
        }

        private fun parseOr(): ExpressionValue {
            var value = parseAnd()
            while (match(TokenType.OR)) {
                val right = parseAnd()
                value = if (value.isTruthy()) value else right
            }
            return value
        }

        private fun parseAnd(): ExpressionValue {
            var value = parseNot()
            while (match(TokenType.AND)) {
                val right = parseNot()
                value = if (value.isTruthy()) right else value
            }
            return value
        }

        /** WatchMaker/WFTools treats `not` as applying to the following comparison. */
        private fun parseNot(): ExpressionValue =
            if (match(TokenType.NOT)) ExpressionValue.Logical(!parseNot().isTruthy()) else parseComparison()

        private fun parseComparison(): ExpressionValue {
            var value = parseConcat()
            while (peek().type in COMPARISON_TYPES) {
                val operator = advance().type
                val right = parseConcat()
                value = ExpressionValue.Logical(compare(value, right, operator))
            }
            return value
        }

        /** Lua concatenation is right-associative and lower precedence than arithmetic. */
        private fun parseConcat(): ExpressionValue {
            val left = parseAdditive()
            if (!match(TokenType.CONCAT)) return left
            val right = parseConcat()
            val text = left.renderText() + right.renderText()
            require(text.length <= MAX_RESULT_TEXT_LENGTH) { "Expression text result is too large" }
            return ExpressionValue.Text(text)
        }

        private fun parseAdditive(): ExpressionValue {
            var value = parseMultiplicative()
            while (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS) {
                val operator = advance().type
                val leftNumber = value.asNumber() ?: error("Expected number")
                val rightNumber = parseMultiplicative().asNumber() ?: error("Expected number")
                value = numericValue(
                    if (operator == TokenType.PLUS) leftNumber + rightNumber else leftNumber - rightNumber,
                )
            }
            return value
        }

        private fun parseMultiplicative(): ExpressionValue {
            var value = parseUnary()
            while (peek().type in MULTIPLICATIVE_TYPES) {
                val operator = advance().type
                val leftNumber = value.asNumber() ?: error("Expected number")
                val rightNumber = parseUnary().asNumber() ?: error("Expected number")
                value = numericValue(
                    when (operator) {
                        TokenType.STAR -> leftNumber * rightNumber
                        TokenType.SLASH -> {
                            require(abs(rightNumber) > Double.MIN_VALUE) { "Division by zero" }
                            leftNumber / rightNumber
                        }
                        TokenType.PERCENT -> {
                            require(abs(rightNumber) > Double.MIN_VALUE) { "Division by zero" }
                            leftNumber % rightNumber
                        }
                        else -> error("Unexpected operator")
                    },
                )
            }
            return value
        }

        private fun parsePower(): ExpressionValue {
            val left = parsePrimary()
            if (!match(TokenType.POWER)) return left
            val right = parseUnary().asNumber() ?: error("Expected number")
            return numericValue((left.asNumber() ?: error("Expected number")).pow(right))
        }

        private fun parseUnary(): ExpressionValue = when {
            match(TokenType.MINUS) -> numericValue(-(parseUnary().asNumber() ?: error("Expected number")))
            match(TokenType.PLUS) -> numericValue(parseUnary().asNumber() ?: error("Expected number"))
            else -> parsePower()
        }

        private fun parsePrimary(): ExpressionValue {
            val token = advance()
            return when (token.type) {
                TokenType.NUMBER -> ExpressionValue.Numeric(token.text.toDouble())
                TokenType.STRING -> ExpressionValue.Text(token.text)
                TokenType.TRUE -> ExpressionValue.Logical(true)
                TokenType.FALSE -> ExpressionValue.Logical(false)
                TokenType.NIL -> ExpressionValue.Nil
                TokenType.LEFT_PAREN -> parseOr().also {
                    require(match(TokenType.RIGHT_PAREN)) { "Missing ')'" }
                }
                TokenType.IDENTIFIER -> parseIdentifier(token.text)
                else -> error("Expected value, found ${token.text}")
            }
        }

        private fun parseIdentifier(first: String): ExpressionValue {
            val qualified = StringBuilder(first)
            while (match(TokenType.DOT)) {
                val segment = advance()
                require(segment.type == TokenType.IDENTIFIER) { "Expected identifier after '.'" }
                qualified.append('.').append(segment.text)
            }
            val name = qualified.toString().lowercase(Locale.ROOT)
            if (!match(TokenType.LEFT_PAREN)) {
                return when (name) {
                    "math.pi" -> ExpressionValue.Numeric(Math.PI)
                    "math.huge" -> ExpressionValue.Numeric(Double.MAX_VALUE)
                    else -> scope.resolve(name)?.toScopedExpressionValue()
                        ?: error("Bare identifiers and script variables are not supported: $name")
                }
            }

            val arguments = ArrayList<ExpressionValue>()
            if (!match(TokenType.RIGHT_PAREN)) {
                do {
                    require(arguments.size < MAX_FUNCTION_ARGUMENTS) { "Too many function arguments" }
                    arguments += parseOr()
                } while (match(TokenType.COMMA))
                require(match(TokenType.RIGHT_PAREN)) { "Missing ')' after function arguments" }
            }
            return callFunction(name, arguments)
        }

        private fun callFunction(name: String, arguments: List<ExpressionValue>): ExpressionValue {
            fun arg(index: Int, fallback: ExpressionValue = ExpressionValue.Numeric(0.0)): ExpressionValue =
                arguments.getOrElse(index) { fallback }
            fun number(index: Int, fallback: Double = 0.0): Double =
                arguments.getOrNull(index)?.asNumber() ?: fallback
            fun text(index: Int): String = arguments.getOrNull(index)?.renderText().orEmpty()

            return when (name) {
                "math.floor" -> numericValue(floor(number(0)))
                "math.ceil" -> numericValue(ceil(number(0)))
                "math.abs" -> numericValue(abs(number(0)))
                "math.sqrt" -> numericValue(sqrt(number(0)))
                "math.max" -> numericValue(arguments.maxOfOrNull { it.asNumber() ?: 0.0 } ?: 0.0)
                "math.min" -> numericValue(arguments.minOfOrNull { it.asNumber() ?: 0.0 } ?: 0.0)
                "math.mod", "math.fmod" -> {
                    val divisor = number(1, 1.0)
                    require(abs(divisor) > Double.MIN_VALUE) { "Division by zero" }
                    numericValue(number(0) % divisor)
                }
                "math.pow" -> numericValue(number(0, 1.0).pow(number(1, 1.0)))
                "math.sin" -> numericValue(sin(number(0)))
                "math.cos" -> numericValue(cos(number(0)))
                "math.tan" -> numericValue(tan(number(0)))
                "math.asin" -> numericValue(asin(number(0)))
                "math.acos" -> numericValue(acos(number(0)))
                "math.atan" -> if (arguments.size >= 2) {
                    numericValue(atan2(number(0), number(1)))
                } else {
                    numericValue(atan(number(0)))
                }
                "math.atan2" -> numericValue(atan2(number(0), number(1, 1.0)))
                "math.rad" -> numericValue(Math.toRadians(number(0)))
                "math.deg" -> numericValue(Math.toDegrees(number(0)))
                "math.exp" -> numericValue(exp(number(0)))
                "math.log" -> {
                    val value = number(0)
                    require(value > 0.0) { "Logarithm input must be positive" }
                    val base = arguments.getOrNull(1)?.asNumber()
                    numericValue(if (base == null) ln(value) else ln(value) / ln(base))
                }
                "string.len" -> numericValue(text(0).length.toDouble())
                "string.upper" -> ExpressionValue.Text(text(0).uppercase(Locale.ROOT))
                "string.lower" -> ExpressionValue.Text(text(0).lowercase(Locale.ROOT))
                "string.sub" -> ExpressionValue.Text(luaSubstring(text(0), number(1, 1.0), arguments.getOrNull(2)?.asNumber()))
                "string.rep" -> {
                    val count = number(1).toInt().coerceIn(0, MAX_RESULT_TEXT_LENGTH)
                    val unit = text(0)
                    require(unit.length.toLong() * count <= MAX_RESULT_TEXT_LENGTH) {
                        "Repeated string is too large"
                    }
                    ExpressionValue.Text(unit.repeat(count))
                }
                "string.find" -> {
                    val start = luaStringIndex(number(2, 1.0).toInt(), text(0).length)
                    val found = text(0).indexOf(text(1), startIndex = start.coerceAtMost(text(0).length))
                    if (found < 0) ExpressionValue.Nil else numericValue((found + 1).toDouble())
                }
                "string.format" -> ExpressionValue.Text(formatLuaString(text(0), arguments.drop(1)))
                "tonumber" -> arg(0).asNumber()?.let(::numericValue) ?: ExpressionValue.Nil
                "tostring" -> ExpressionValue.Text(arg(0, ExpressionValue.Nil).renderText())
                "type" -> ExpressionValue.Text(
                    when (arg(0, ExpressionValue.Nil)) {
                        is ExpressionValue.Numeric -> "number"
                        is ExpressionValue.Text -> "string"
                        is ExpressionValue.Logical -> "boolean"
                        ExpressionValue.Nil -> "nil"
                    },
                )
                else -> error("Unsupported function: $name")
            }
        }

        private fun luaSubstring(value: String, fromRaw: Double, toRaw: Double?): String {
            val length = value.length
            val from = luaStringIndex(fromRaw.toInt(), length)
            val inclusiveTo = toRaw?.toInt()?.let { index ->
                if (index < 0) length + index else index - 1
            } ?: (length - 1)
            val endExclusive = (inclusiveTo + 1).coerceIn(0, length)
            return if (from < endExclusive) value.substring(from, endExclusive) else ""
        }

        private fun luaStringIndex(raw: Int, length: Int): Int =
            (if (raw < 0) length + raw else raw - 1).coerceIn(0, length)

        private fun formatLuaString(
            format: String,
            arguments: List<ExpressionValue>,
        ): String {
            require(format.length <= MAX_RESULT_TEXT_LENGTH) { "Format string is too large" }
            val output = StringBuilder(format.length + 16)
            var index = 0
            var argumentIndex = 0
            while (index < format.length) {
                if (format[index] != '%') {
                    output.append(format[index++])
                    continue
                }
                if (index + 1 < format.length && format[index + 1] == '%') {
                    output.append('%')
                    index += 2
                    continue
                }
                val specStart = index++
                while (index < format.length && format[index] in "-+ #0") index++
                val widthStart = index
                while (index < format.length && format[index].isDigit()) index++
                val width = format.substring(widthStart, index).toIntOrNull() ?: 0
                require(width <= 128) { "Format width is too large" }
                if (index < format.length && format[index] == '.') {
                    index++
                    val precisionStart = index
                    while (index < format.length && format[index].isDigit()) index++
                    val precision = format.substring(precisionStart, index).toIntOrNull() ?: 0
                    require(precision <= 32) { "Format precision is too large" }
                }
                require(index < format.length) { "Incomplete format specifier" }
                val type = format[index++]
                require(type in "diufFeEgGsq") { "Unsupported format type: $type" }
                val argument = arguments.getOrNull(argumentIndex++) ?: ExpressionValue.Numeric(0.0)
                val spec = format.substring(specStart, index)
                val rendered = when (type) {
                    'd', 'i' -> String.format(Locale.ROOT, spec.dropLast(1) + "d", (argument.asNumber() ?: 0.0).toLong())
                    'u' -> (argument.asNumber() ?: 0.0).toLong().toULong().toString()
                    'f', 'F', 'e', 'E', 'g', 'G' ->
                        String.format(Locale.ROOT, spec, argument.asNumber() ?: 0.0)
                    's' -> String.format(Locale.ROOT, spec, argument.renderText())
                    'q' -> "\"${argument.renderText().replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    else -> error("Unsupported format type")
                }
                output.append(rendered)
                require(output.length <= MAX_RESULT_TEXT_LENGTH) { "Formatted text is too large" }
            }
            return output.toString()
        }

        private fun numericValue(value: Double): ExpressionValue.Numeric {
            require(value.isFinite()) { "Expression result is not finite" }
            return ExpressionValue.Numeric(value)
        }

        private fun String.toScopedExpressionValue(): ExpressionValue = when {
            equals("true", true) -> ExpressionValue.Logical(true)
            equals("false", true) -> ExpressionValue.Logical(false)
            this == "nil" -> ExpressionValue.Nil
            toDoubleOrNull()?.isFinite() == true -> ExpressionValue.Numeric(toDouble())
            else -> ExpressionValue.Text(this)
        }

        private fun compare(left: ExpressionValue, right: ExpressionValue, operator: TokenType): Boolean {
            if (operator == TokenType.EQUAL || operator == TokenType.NOT_EQUAL) {
                val equal = when {
                    left === ExpressionValue.Nil || right === ExpressionValue.Nil -> left === right
                    left.asNumber() != null && right.asNumber() != null -> left.asNumber() == right.asNumber()
                    left is ExpressionValue.Text && right is ExpressionValue.Text -> left.value == right.value
                    left is ExpressionValue.Logical && right is ExpressionValue.Logical -> left.value == right.value
                    else -> false
                }
                return if (operator == TokenType.EQUAL) equal else !equal
            }
            val leftNumber = left.asNumber() ?: error("Expected number")
            val rightNumber = right.asNumber() ?: error("Expected number")
            return when (operator) {
                TokenType.GREATER -> leftNumber > rightNumber
                TokenType.GREATER_EQUAL -> leftNumber >= rightNumber
                TokenType.LESS -> leftNumber < rightNumber
                TokenType.LESS_EQUAL -> leftNumber <= rightNumber
                else -> error("Unexpected comparison")
            }
        }

        private fun peek(): Token = tokens[index]

        private fun advance(): Token = tokens[index++]

        private fun match(type: TokenType): Boolean {
            if (peek().type != type) return false
            index++
            return true
        }

        companion object {
            private val COMPARISON_TYPES = setOf(
                TokenType.EQUAL,
                TokenType.NOT_EQUAL,
                TokenType.GREATER,
                TokenType.GREATER_EQUAL,
                TokenType.LESS,
                TokenType.LESS_EQUAL,
            )
            private val MULTIPLICATIVE_TYPES = setOf(
                TokenType.STAR,
                TokenType.SLASH,
                TokenType.PERCENT,
            )
        }
    }
}

/** A compact compatibility report surfaced to editor/diagnostics callers. */
data class WatchCompatibilityReport(
    val unsupportedLayerTypes: Set<String>,
    val unsupportedTags: Set<String>,
    val unsupportedExpressions: Set<String>,
) {
    val isFullySupported: Boolean
        get() = unsupportedLayerTypes.isEmpty() &&
            unsupportedTags.isEmpty() &&
            unsupportedExpressions.isEmpty()
}
