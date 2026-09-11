package com.flue.launcher.diagnostics

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.flue.launcher.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class RecordingPhase { IDLE, STARTING, RECORDING, FINISHING, READY, ERROR }

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val elapsedSeconds: Int = 0,
    val archive: File? = null,
    val errorRes: Int? = null,
    val completedAutomatically: Boolean = false,
)

/** Main-thread owner. Activities attach only while resumed; no overlay permission or service is used. */
object DiagnosticsController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableConfig = MutableStateFlow(DiagnosticsConfig())
    val config = mutableConfig.asStateFlow()
    private val mutableRecording = MutableStateFlow(RecordingState())
    val recording = mutableRecording.asStateFlow()
    private val probes = linkedMapOf<Activity, WindowProbe>()
    private var frameThread: HandlerThread? = null
    private var sampleJob: Job? = null
    private var deadlineJob: Job? = null
    private var recoveryJob: Job? = null
    private var current: DiagnosticsRecording? = null
    private var initialized = false

    fun onResume(activity: Activity) {
        initialize(activity)
        if (current?.window?.isExpired(SystemClock.elapsedRealtime()) == true) stopRecording(automatic = true)
        probes.getOrPut(activity) { WindowProbe(activity) }
        refreshInstrumentation()
    }

    fun onPause(activity: Activity) {
        probes.remove(activity)?.close()
        refreshInstrumentation()
    }

    private fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val root = File(context.applicationContext.cacheDir, "diagnostics")
        recoveryJob = scope.launch {
            try {
                val recovered = withContext(Dispatchers.IO) { DiagnosticsRecording.recoverLatest(root) }
                if (recovered != null && mutableRecording.value.phase == RecordingPhase.IDLE) {
                    mutableRecording.value = RecordingState(phase = RecordingPhase.READY, archive = recovered)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (mutableRecording.value.phase == RecordingPhase.IDLE) {
                    mutableRecording.value = RecordingState(phase = RecordingPhase.ERROR, errorRes = R.string.diagnostics_recovery_failed)
                }
            }
        }
    }

    fun setFps(enabled: Boolean) = setConfig(mutableConfig.value.copy(fps = enabled))
    fun setMemory(enabled: Boolean) = setConfig(mutableConfig.value.copy(memory = enabled))
    fun setThreads(enabled: Boolean) = setConfig(mutableConfig.value.copy(threads = enabled))

    private fun setConfig(value: DiagnosticsConfig) {
        mutableConfig.value = value
        refreshInstrumentation()
    }

    fun startRecording(context: Context, configuration: Map<String, Any>) {
        initialize(context)
        if (mutableRecording.value.phase in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.FINISHING)) return
        val app = context.applicationContext
        mutableRecording.value = RecordingState(phase = RecordingPhase.STARTING)
        scope.launch {
            try {
                recoveryJob?.join()
                val info = DiagnosticsSnapshot.collect(app, configuration)
                val created = withContext(Dispatchers.IO) {
                    DiagnosticsRecording.create(File(app.cacheDir, "diagnostics"), SystemClock.elapsedRealtime(), info)
                }
                current = created
                mutableRecording.value = mutableRecording.value.copy(phase = RecordingPhase.RECORDING, elapsedSeconds = 0)
                refreshInstrumentation()
                deadlineJob = scope.launch {
                    while (current === created && !created.window.isExpired(SystemClock.elapsedRealtime())) {
                        delay(created.window.remainingMs(SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                    }
                    if (current === created) stopRecording(automatic = true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableRecording.value = mutableRecording.value.copy(phase = RecordingPhase.ERROR, errorRes = R.string.diagnostics_recording_failed)
            }
        }
    }

    fun stopRecording(automatic: Boolean = false) {
        finishRecording(if (automatic) "deadline" else "manual")
    }

    private fun finishRecording(reason: String, errorRes: Int? = null) {
        val session = current ?: return
        current = null
        deadlineJob?.cancel()
        deadlineJob = null
        val now = SystemClock.elapsedRealtime()
        mutableRecording.value = mutableRecording.value.copy(
            phase = RecordingPhase.FINISHING,
            elapsedSeconds = ((now - session.window.startMs).coerceIn(0L, MAX_RECORDING_DURATION_MS) / 1000).toInt(),
        )
        refreshInstrumentation() // independent overlay switches retain their values.
        scope.launch {
            try {
                val archive = withContext(NonCancellable + Dispatchers.IO) { session.finish(now, reason) }
                mutableRecording.value = mutableRecording.value.copy(
                    phase = RecordingPhase.READY, archive = archive, errorRes = errorRes, completedAutomatically = reason == "deadline",
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableRecording.value = mutableRecording.value.copy(phase = RecordingPhase.ERROR, errorRes = R.string.diagnostics_export_failed)
            }
        }
    }

    /** URI grant stays valid because the archive is retained in cache after launching the receiver. */
    fun shareArchive(context: Context) {
        val file = mutableRecording.value.archive ?: return
        try {
            check(file.isFile && file.length() > 0)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Flue performance", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (send.resolveActivity(context.packageManager) == null) throw ActivityNotFoundException()
            context.startActivity(Intent.createChooser(send, context.getString(R.string.diagnostics_export)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            mutableRecording.value = mutableRecording.value.copy(errorRes = null)
        } catch (_: ActivityNotFoundException) {
            mutableRecording.value = mutableRecording.value.copy(errorRes = R.string.diagnostics_no_share_handler)
        } catch (_: Exception) {
            mutableRecording.value = mutableRecording.value.copy(errorRes = R.string.diagnostics_export_failed)
        }
    }

    private fun refreshInstrumentation() {
        val options = mutableConfig.value
        val needFrames = (options.fps || current != null) && probes.isNotEmpty()
        if (needFrames && frameThread == null) frameThread = HandlerThread("FlueFrameMetrics").apply { start() }
        val handler = frameThread?.let { Handler(it.looper) }
        probes.values.forEach { it.configure(options, needFrames, handler) }
        if (!needFrames) {
            frameThread?.quitSafely()
            frameThread = null
        }
        val needSampling = probes.isNotEmpty() && (options.anyEnabled || current != null)
        if (!needSampling) {
            sampleJob?.cancel()
            sampleJob = null
        } else if (sampleJob == null) {
            probes.values.forEach { it.resetInterval() }
            sampleJob = scope.launch {
                while (isActive) {
                    delay(1000L)
                    sample()
                }
            }
        }
    }

    private suspend fun sample() {
        val session = current
        val now = SystemClock.elapsedRealtime()
        if (session?.window?.isExpired(now) == true) {
            stopRecording(automatic = true)
            return
        }
        val options = mutableConfig.value
        val process = withContext(Dispatchers.IO) { processSample(options.memory || session != null, options.threads || session != null) }
        // onPause cancels this coroutine; no disk samples or UI updates continue in background.
        for (probe in probes.values.toList()) {
            val frames = probe.take(now)
            probe.show(options, frames, process)
            if (session != null && session === current) {
                try {
                    withContext(Dispatchers.IO) { session.append(now, probe.activity.javaClass.simpleName, frames, process) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    finishRecording("io_error", R.string.diagnostics_recording_failed)
                    return
                }
            }
        }
        if (session != null && session === current) {
            mutableRecording.value = mutableRecording.value.copy(elapsedSeconds = ((now - session.window.startMs) / 1000L).toInt())
        }
    }

    private fun processSample(memory: Boolean, threads: Boolean): ProcessSample {
        val info = if (memory) runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo) }.getOrNull() else null
        val count = if (threads) runCatching {
            File("/proc/self/status").bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()?.toIntOrNull()
            }
        }.getOrNull() else null
        val runtime = Runtime.getRuntime()
        return ProcessSample(
            pssKb = info?.totalPss,
            javaHeapBytes = if (memory) runtime.totalMemory() - runtime.freeMemory() else null,
            nativeHeapBytes = if (memory) Debug.getNativeHeapAllocatedSize() else null,
            threads = count,
        )
    }
}

private class WindowProbe(val activity: Activity) {
    private val accumulator = FrameIntervalAccumulator()
    private var lastSampleAt = SystemClock.elapsedRealtime()
    private var overlay: TextView? = null
    private var lastFrame = FrameSample()
    private var lastProcess = ProcessSample()
    @Volatile private var tracking = false
    @Volatile private var frameBudgetNanos = 16_666_667L
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        if (tracking) accumulator.record(metrics.getMetric(FrameMetrics.TOTAL_DURATION), frameBudgetNanos, dropped)
    }

    fun configure(config: DiagnosticsConfig, needFrames: Boolean, handler: Handler?) {
        if (needFrames && !tracking && handler != null) {
            resetInterval()
            tracking = true
            activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        } else if (!needFrames && tracking) {
            tracking = false
            activity.window.removeOnFrameMetricsAvailableListener(listener)
            resetInterval()
        }
        if (config.anyEnabled && overlay == null) {
            val density = activity.resources.displayMetrics.density
            val round = activity.resources.configuration.isScreenRound
            overlay = TextView(activity).apply {
                tag = "flue-performance-overlay"
                textSize = 10f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                background = GradientDrawable().apply { setColor(0xCC101010.toInt()); cornerRadius = 8 * density }
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                maxWidth = (activity.resources.displayMetrics.widthPixels * 0.75f).toInt()
                text = activity.getString(R.string.diagnostics_overlay_waiting)
            }.also { view ->
                (activity.window.decorView as? ViewGroup)?.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or if (round) Gravity.CENTER_HORIZONTAL else Gravity.END,
                ).apply {
                    topMargin = ((if (round) 32 else 28) * density).toInt()
                    rightMargin = (if (round) 0 else 8 * density).toInt()
                })
            }
        } else if (!config.anyEnabled) removeOverlay()
        if (overlay != null) show(config, lastFrame, lastProcess)
    }

    fun resetInterval() {
        accumulator.take(1L)
        lastSampleAt = SystemClock.elapsedRealtime()
    }

    @Suppress("DEPRECATION")
    fun take(now: Long): FrameSample {
        val refreshRate = activity.windowManager.defaultDisplay.refreshRate.coerceAtLeast(1f)
        frameBudgetNanos = (1_000_000_000.0 / refreshRate).toLong()
        return accumulator.take(now - lastSampleAt).also { lastSampleAt = now }
    }

    fun show(config: DiagnosticsConfig, frame: FrameSample, process: ProcessSample) {
        lastFrame = frame
        lastProcess = process
        val text = buildList {
            if (config.fps) {
                add(activity.getString(R.string.diagnostics_overlay_fps, frame.fps, frame.averageMs))
                add(activity.getString(R.string.diagnostics_overlay_jank, frame.jankPercent, frame.maxMs))
            }
            if (config.memory) add(activity.getString(R.string.diagnostics_overlay_memory, process.pssKb?.let { String.format(Locale.US, "%.1f", it / 1024.0) } ?: "—"))
            if (config.threads) add(activity.getString(R.string.diagnostics_overlay_threads, process.threads?.toString() ?: "—"))
        }.joinToString("\n")
        overlay?.let { if (it.text.toString() != text) it.text = text }
    }

    private fun removeOverlay() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }

    fun close() {
        if (tracking) {
            tracking = false
            activity.window.removeOnFrameMetricsAvailableListener(listener)
        }
        removeOverlay()
    }
}
