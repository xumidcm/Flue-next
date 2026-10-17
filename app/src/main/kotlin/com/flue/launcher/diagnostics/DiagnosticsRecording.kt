package com.flue.launcher.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ProcessSample(val pssKb: Int? = null, val javaHeapBytes: Long? = null, val nativeHeapBytes: Long? = null, val threads: Int? = null)

/** One row per sampled window/second, flushed incrementally. No raw frame buffer grows in memory. */
internal class DiagnosticsRecording private constructor(
    val directory: File,
    val window: RecordingWindow,
    private val writer: BufferedWriter,
) {
    private var closed = false
    val performance = TransitionPerformanceCollector()

    @Synchronized
    fun append(nowMs: Long, activity: String, frame: FrameSample, process: ProcessSample) {
        if (closed || window.isExpired(nowMs)) return
        writer.append(listOf(
            (nowMs - window.startMs).coerceAtLeast(0).toString(),
            activity.replace(Regex("[^A-Za-z0-9_.]"), "_"),
            frame.frames.toString(), decimal(frame.fps), decimal(frame.averageMs), decimal(frame.maxMs),
            decimal(frame.jankPercent), frame.droppedCallbacks.toString(),
            process.pssKb?.toString().orEmpty(), process.javaHeapBytes?.toString().orEmpty(),
            process.nativeHeapBytes?.toString().orEmpty(), process.threads?.toString().orEmpty(),
            decimal(frame.p95Ms),
        ).joinToString(",")).append('\n')
        writer.flush()
    }

    @Synchronized
    fun finish(nowMs: Long, reason: String): File {
        if (!closed) {
            closed = true
            writer.close()
        }
        return archive(directory, (nowMs - window.startMs).coerceIn(0, MAX_RECORDING_DURATION_MS), reason, performance.toJson())
    }

    companion object {
        private fun decimal(value: Double): String = String.format(Locale.US, "%.3f", value)

        fun create(root: File, nowMs: Long, info: String): DiagnosticsRecording {
            val directory = File(root, "flue-performance-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
            check(directory.mkdirs()) { "Cannot create recording directory" }
            File(directory, "info.json").writeText(info, Charsets.UTF_8)
            // Presence of samples.csv without completed identifies recoverable process-death data.
            val writer = File(directory, "samples.csv").bufferedWriter(Charsets.UTF_8)
            writer.append("elapsed_ms,activity,frames,fps,average_frame_ms,max_frame_ms,jank_percent,dropped_callbacks,pss_kb,java_heap_bytes,native_heap_bytes,threads,p95_frame_ms\n")
            writer.flush()
            return DiagnosticsRecording(directory, RecordingWindow(nowMs), writer)
        }

        fun recoverLatest(root: File): File? {
            val candidates = root.listFiles()?.filter { it.isDirectory && File(it, "samples.csv").isFile }
                ?.sortedByDescending(File::lastModified).orEmpty()
            // Older recoverable folders remain available; recovering the latest keeps startup bounded.
            val partial = candidates.firstOrNull { !File(it, "completed").exists() }
            if (partial != null) return archive(partial, null, "process_interrupted")
            return root.listFiles()?.filter { it.isFile && it.extension == "zip" }?.maxByOrNull(File::lastModified)
        }

        private fun archive(directory: File, elapsedMs: Long?, reason: String, performance: String = "null"): File {
            require(reason in setOf("manual", "deadline", "process_interrupted", "io_error"))
            File(directory, "session.json").writeText(
                "{\"reason\":\"$reason\",\"elapsed_ms\":${elapsedMs ?: "null"},\"max_duration_ms\":$MAX_RECORDING_DURATION_MS,\"partial\":${reason == "process_interrupted" || reason == "io_error"},\"transition_performance\":$performance}",
                Charsets.UTF_8,
            )
            val output = File(directory.parentFile, "${directory.name}.zip")
            val temporary = File(directory.parentFile, "${directory.name}.zip.part")
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                listOf("info.json", "session.json", "samples.csv").forEach { name ->
                    val source = File(directory, name)
                    if (source.isFile) {
                        zip.putNextEntry(ZipEntry(name))
                        source.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            check(temporary.renameTo(output)) { "Cannot publish recording archive" }
            File(directory, "completed").writeText(output.name, Charsets.UTF_8)
            return output
        }
    }
}
