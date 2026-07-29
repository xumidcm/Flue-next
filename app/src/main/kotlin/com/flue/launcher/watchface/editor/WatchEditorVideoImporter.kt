package com.flue.launcher.watchface.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class WatchEditorVideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
)

data class WatchEditorVideoImportOptions(
    val startMs: Long,
    val endMs: Long,
    val maxWidth: Int,
    val maxHeight: Int,
    val frameRate: Float,
    val bitrateKbps: Int,
) {
    init {
        require(startMs >= 0L) { "startMs must be non-negative" }
        require(endMs > startMs) { "endMs must be greater than startMs" }
        require(maxWidth in 16..4096 && maxHeight in 16..4096) { "invalid output resolution" }
        require(frameRate in 0.5f..60f) { "invalid frame rate" }
        require(bitrateKbps in 64..100_000) { "invalid bitrate" }
    }

    val durationMs: Long get() = endMs - startMs
    val frameDelayMs: Int get() = (1_000f / frameRate).roundToInt().coerceAtLeast(1)
}

data class WatchEditorVideoImportProgress(
    val processedMs: Long,
    val durationMs: Long,
) {
    val fraction: Float = if (durationMs <= 0L) 0f else {
        (processedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

data class WatchEditorVideoFrames(
    val workingDirectory: File,
    val frameFiles: List<File>,
    val frameDelayMs: Int,
) {
    fun deleteTemporaryFiles() {
        workingDirectory.deleteRecursively()
    }
}

object WatchEditorVideoImporter {
    fun isRuntimeSupported(): Boolean = Build.SUPPORTED_ABIS.any {
        it.equals("arm64-v8a", ignoreCase = true)
    }

    fun inspect(context: Context, uri: Uri): WatchEditorVideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: throw WatchEditorException("无法读取视频时长")
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: throw WatchEditorException("无法读取视频宽度")
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: throw WatchEditorException("无法读取视频高度")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?.mod(360)
                ?: 0
            val rotated = rotation == 90 || rotation == 270
            WatchEditorVideoInfo(
                durationMs = durationMs,
                width = if (rotated) rawHeight else rawWidth,
                height = if (rotated) rawWidth else rawHeight,
            )
        } finally {
            retriever.release()
        }
    }

    suspend fun convertToFrames(
        context: Context,
        uri: Uri,
        options: WatchEditorVideoImportOptions,
        onProgress: (WatchEditorVideoImportProgress) -> Unit = {},
    ): WatchEditorVideoFrames {
        if (!isRuntimeSupported()) {
            throw WatchEditorException("当前设备架构暂不支持 FFmpeg 视频导入")
        }
        val root = withContext(Dispatchers.IO) {
            File(context.cacheDir, "watch-editor-video/${UUID.randomUUID()}").also { directory ->
                if (!directory.mkdirs()) throw WatchEditorException("无法创建视频转换缓存目录")
            }
        }
        val inputFile = File(root, "input-video")
        val outputDirectory = File(root, "frames")
        try {
            withContext(Dispatchers.IO) {
                if (!outputDirectory.mkdirs()) throw WatchEditorException("无法创建视频帧缓存目录")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    inputFile.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: throw WatchEditorException("无法读取所选视频")
            }
            val outputPattern = File(outputDirectory, "frame_%05d.jpg")
            val arguments = buildArguments(inputFile, outputPattern, options)
            execute(arguments, options.durationMs, onProgress)
            val frames = withContext(Dispatchers.IO) {
                outputDirectory.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                    .sortedBy { it.name }
            }
            if (frames.isEmpty()) throw WatchEditorException("FFmpeg 没有生成可用的视频帧")
            return WatchEditorVideoFrames(root, frames, options.frameDelayMs)
        } catch (error: Throwable) {
            withContext(Dispatchers.IO) { root.deleteRecursively() }
            if (error is WatchEditorException) throw error
            throw WatchEditorException(error.message ?: "视频转换失败", error)
        }
    }

    internal fun buildArguments(
        inputFile: File,
        outputPattern: File,
        options: WatchEditorVideoImportOptions,
    ): List<String> = listOf(
        "-hide_banner",
        "-loglevel", "error",
        "-y",
        "-ss", formatSeconds(options.startMs),
        "-i", inputFile.absolutePath,
        "-t", formatSeconds(options.durationMs),
        "-map", "0:v:0",
        "-an",
        "-vf", buildString {
            append("fps=")
            append(formatDecimal(options.frameRate))
            append(",scale=w=")
            append(options.maxWidth)
            append(":h=")
            append(options.maxHeight)
            append(":force_original_aspect_ratio=decrease:force_divisible_by=2")
        },
        "-c:v", "mjpeg",
        "-b:v", "${options.bitrateKbps}k",
        "-qmin", "2",
        "-qmax", "31",
        "-start_number", "0",
        outputPattern.absolutePath,
    )

    private suspend fun execute(
        arguments: List<String>,
        durationMs: Long,
        onProgress: (WatchEditorVideoImportProgress) -> Unit,
    ) = suspendCancellableCoroutine { continuation ->
        val sessionId = AtomicLong(0L)
        val mainHandler = Handler(Looper.getMainLooper())
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments.toTypedArray(),
            { completed ->
                if (!continuation.isActive) return@executeWithArgumentsAsync
                when {
                    ReturnCode.isSuccess(completed.returnCode) -> continuation.resume(Unit)
                    ReturnCode.isCancel(completed.returnCode) -> continuation.cancel()
                    else -> {
                        val detail = completed.failStackTrace
                            ?.lineSequence()
                            ?.firstOrNull { it.isNotBlank() }
                            ?.take(240)
                        continuation.resumeWithException(
                            WatchEditorException(detail ?: "FFmpeg 视频转换失败（${completed.returnCode}）"),
                        )
                    }
                }
            },
            null,
            { statistics ->
                val processedMs = statistics.time.roundToInt().toLong().coerceAtLeast(0L)
                mainHandler.post {
                    onProgress(WatchEditorVideoImportProgress(processedMs, durationMs))
                }
            },
        )
        sessionId.set(session.sessionId)
        continuation.invokeOnCancellation {
            sessionId.get().takeIf { it > 0L }?.let(FFmpegKit::cancel)
        }
    }

    private fun formatSeconds(milliseconds: Long): String =
        String.format(Locale.US, "%.3f", milliseconds / 1_000.0)

    private fun formatDecimal(value: Float): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
}
