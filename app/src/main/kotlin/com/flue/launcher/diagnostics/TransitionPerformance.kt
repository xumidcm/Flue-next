package com.flue.launcher.diagnostics

import java.util.Locale
import kotlin.math.ceil

/** Recording-only counters. Disabled render paths do one volatile read, without allocating. */
internal object TransitionPerformance {
    enum class Stage {
        LIST_CAPTURE, LIST_BLUR, FACE_PREPARE, NATIVE_LAYOUT, NATIVE_DRAW,
        FRAME_TOTAL, FRAME_UNKNOWN_DELAY, FRAME_INPUT, FRAME_ANIMATION, FRAME_LAYOUT,
        FRAME_DRAW, FRAME_SYNC, FRAME_COMMAND, FRAME_SWAP,
    }
    enum class Scene { IDLE, OPEN, CLOSE, REVERSE, SCROLL, LAUNCH, RETURN }

    @Volatile var collector: TransitionPerformanceCollector? = null

    fun start(): Long = if (collector == null) 0L else System.nanoTime()

    fun finish(stage: Stage, started: Long) {
        if (started == 0L) return
        val target = collector ?: return
        target.record(stage, started, (System.nanoTime() - started).coerceAtLeast(0L))
    }

    fun markScene(scene: Scene) { collector?.markScene(scene, System.nanoTime()) }
    fun markScroll() { collector?.markScroll(System.nanoTime()) }
    fun endScene(scene: Scene) { collector?.endScene(scene, System.nanoTime()) }
}

/** Fixed 0.5 ms buckets; the final bucket is overflow, whose P95 reports the observed maximum. */
internal class DurationHistogram {
    private val buckets = LongArray(1025)
    var count = 0L
        private set
    var total = 0L
        private set
    var maximum = 0L
        private set

    fun record(nanos: Long) {
        if (nanos < 0L) return
        count++
        total += nanos
        maximum = maxOf(maximum, nanos)
        buckets[(nanos / 500_000L).coerceAtMost(buckets.lastIndex.toLong()).toInt()]++
    }

    fun percentile95Ms(): Double {
        if (count == 0L) return 0.0
        val threshold = ceil(count * .95).toLong()
        var cumulative = 0L
        for (index in buckets.indices) {
            cumulative += buckets[index]
            if (cumulative >= threshold) return (if (index == buckets.lastIndex) maximum
                else minOf(maximum, (index + 1L) * 500_000L)) / 1_000_000.0
        }
        return maximum / 1_000_000.0
    }

    fun clear() { buckets.fill(0L); count = 0; total = 0; maximum = 0 }
}

/** Keeps scene boundaries rather than assigning a mixed one-second interval to its last gesture. */
internal class TransitionPerformanceCollector(private val startedAt: Long = System.nanoTime()) {
    private val timestamps = LongArray(256)
    private val scenes = arrayOfNulls<TransitionPerformance.Scene>(256)
    private var next = 0
    private var size = 0
    private var current = TransitionPerformance.Scene.IDLE
    private val totals = Array(TransitionPerformance.Scene.entries.size) {
        Array(TransitionPerformance.Stage.entries.size) { DurationHistogram() }
    }
    private val jank = LongArray(TransitionPerformance.Scene.entries.size)
    private val dropped = LongArray(jank.size)
    private val runs = LongArray(jank.size)

    init { appendScene(current, startedAt) }

    @Synchronized fun markScene(scene: TransitionPerformance.Scene, now: Long) {
        if (scene == current) return
        current = scene
        runs[scene.ordinal]++
        appendScene(scene, now)
    }

    @Synchronized fun endScene(scene: TransitionPerformance.Scene, now: Long) {
        if (current == scene) markScene(TransitionPerformance.Scene.IDLE, now)
    }

    @Synchronized fun markScroll(now: Long) {
        if (current == TransitionPerformance.Scene.IDLE) markScene(TransitionPerformance.Scene.SCROLL, now)
    }

    private fun appendScene(scene: TransitionPerformance.Scene, now: Long) {
        timestamps[next] = now
        scenes[next] = scene
        next = (next + 1) % timestamps.size
        size = minOf(size + 1, timestamps.size)
    }

    private fun sceneAt(now: Long): TransitionPerformance.Scene? {
        if (now < startedAt) return null
        for (offset in 1..size) {
            val index = (next - offset + timestamps.size) % timestamps.size
            if (timestamps[index] <= now) return scenes[index]
        }
        // A callback older than the bounded history must not acquire a new scene label.
        return null
    }

    @Synchronized fun record(stage: TransitionPerformance.Stage, started: Long, duration: Long) {
        val scene = sceneAt(started) ?: return
        totals[scene.ordinal][stage.ordinal].record(duration)
    }

    @Synchronized fun recordFrame(started: Long, duration: Long, budget: Long, droppedCallbacks: Int) {
        val scene = sceneAt(started) ?: return
        totals[scene.ordinal][TransitionPerformance.Stage.FRAME_TOTAL.ordinal].record(duration)
        if (duration > budget.coerceAtLeast(1L)) jank[scene.ordinal]++
        dropped[scene.ordinal] += droppedCallbacks.coerceAtLeast(0)
    }

    @Synchronized fun toJson(): String = buildString {
        append("{\"p95_resolution_ms\":0.5,\"scenes\":{")
        TransitionPerformance.Scene.entries.forEachIndexed { index, scene ->
            if (index > 0) append(',')
            val frames = totals[index][TransitionPerformance.Stage.FRAME_TOTAL.ordinal].count
            append('"').append(scene.name.lowercase(Locale.ROOT)).append("\":{\"runs\":").append(runs[index])
            append(",\"over_budget_percent\":").append(if (frames == 0L) 0.0 else jank[index] * 100.0 / frames)
            append(",\"dropped_callbacks\":").append(dropped[index]).append(",\"stages\":{")
            TransitionPerformance.Stage.entries.forEachIndexed { stageIndex, stage ->
                if (stageIndex > 0) append(',')
                val histogram = totals[index][stageIndex]
                append('"').append(stage.name.lowercase(Locale.ROOT)).append("\":{\"count\":").append(histogram.count)
                append(",\"average_ms\":").append(if (histogram.count == 0L) 0.0 else histogram.total / histogram.count / 1_000_000.0)
                append(",\"p95_ms\":").append(histogram.percentile95Ms())
                append(",\"max_ms\":").append(histogram.maximum / 1_000_000.0).append('}')
            }
            append("}}")
        }
        append("}}")
    }
}
