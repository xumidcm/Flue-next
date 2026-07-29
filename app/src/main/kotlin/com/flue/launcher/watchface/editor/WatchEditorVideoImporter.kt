package com.flue.launcher.watchface.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    /** Source stream bitrate in kbps when exposed by the platform metadata retriever. */
    val bitrateKbps: Int? = null,
)

/** Normalised crop in the display-oriented video coordinate space. */
data class WatchEditorVideoCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "crop coordinates must be finite"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "crop coordinates must be normalised"
        }
        require(right > left && bottom > top) { "crop must have positive area" }
    }

    companion object {
        val Full = WatchEditorVideoCrop()
    }
}

enum class WatchEditorVideoResolutionPreset(val targetShortEdge: Int?) {
    ORIGINAL(null),
    P1080(1080),
    P720(720),
    P480(480),
    P360(360),
}

data class WatchEditorVideoTransform(
    val cropX: Int,
    val cropY: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    val scalesOutput: Boolean
        get() = outputWidth != cropWidth || outputHeight != cropHeight
}

data class WatchEditorVideoImportOptions(
    val startMs: Long,
    val endMs: Long,
    val crop: WatchEditorVideoCrop = WatchEditorVideoCrop.Full,
    val resolutionPreset: WatchEditorVideoResolutionPreset = WatchEditorVideoResolutionPreset.P480,
    val frameRate: Float,
    val bitrateKbps: Int,
) {
    init {
        require(startMs >= 0L) { "startMs must be non-negative" }
        require(endMs > startMs) { "endMs must be greater than startMs" }
        require(frameRate in 0.5f..60f) { "invalid frame rate" }
        require(bitrateKbps in 64..100_000) { "invalid bitrate" }
    }

    val durationMs: Long get() = endMs - startMs
    val frameDelayMs: Int get() = (1_000f / frameRate).roundToInt().coerceAtLeast(1)
}

internal fun resolveVideoTransform(
    info: WatchEditorVideoInfo,
    options: WatchEditorVideoImportOptions,
): WatchEditorVideoTransform {
    fun evenFloor(value: Int): Int = (value.coerceAtLeast(0) / 2) * 2
    fun evenSize(value: Int): Int = evenFloor(value).coerceAtLeast(2)

    val sourceWidth = info.width.coerceAtLeast(2)
    val sourceHeight = info.height.coerceAtLeast(2)
    val cropX = evenFloor((options.crop.left * sourceWidth).roundToInt())
        .coerceAtMost(evenFloor(sourceWidth - 2))
    val cropY = evenFloor((options.crop.top * sourceHeight).roundToInt())
        .coerceAtMost(evenFloor(sourceHeight - 2))
    val requestedWidth = evenSize(((options.crop.right - options.crop.left) * sourceWidth).roundToInt())
    val requestedHeight = evenSize(((options.crop.bottom - options.crop.top) * sourceHeight).roundToInt())
    val cropWidth = requestedWidth.coerceAtMost(evenSize(sourceWidth - cropX))
    val cropHeight = requestedHeight.coerceAtMost(evenSize(sourceHeight - cropY))
    require(cropWidth >= 16 && cropHeight >= 16) { "裁剪区域太小" }

    val targetShortEdge = options.resolutionPreset.targetShortEdge
    val scale = if (targetShortEdge == null) {
        1f
    } else {
        minOf(1f, targetShortEdge.toFloat() / minOf(cropWidth, cropHeight).coerceAtLeast(1))
    }
    val outputWidth = evenSize((cropWidth * scale).roundToInt())
    val outputHeight = evenSize((cropHeight * scale).roundToInt())
    return WatchEditorVideoTransform(
        cropX = cropX,
        cropY = cropY,
        cropWidth = cropWidth,
        cropHeight = cropHeight,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
    )
}

internal fun resolveVideoBitrateKbps(
    info: WatchEditorVideoInfo,
    preset: WatchEditorVideoResolutionPreset,
    requestedBitrateKbps: Int,
): Int = if (preset == WatchEditorVideoResolutionPreset.ORIGINAL) {
    info.bitrateKbps ?: requestedBitrateKbps
} else {
    requestedBitrateKbps
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
    fun isRuntimeSupported(): Boolean = isRuntimeSupported(Build.SUPPORTED_ABIS)

    internal fun isRuntimeSupported(supportedAbis: Array<String>): Boolean = supportedAbis.any {
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
            val bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
                ?.div(1_000L)
                ?.coerceIn(64L, 100_000L)
                ?.toInt()
            val rotated = rotation == 90 || rotation == 270
            WatchEditorVideoInfo(
                durationMs = durationMs,
                width = if (rotated) rawHeight else rawWidth,
                height = if (rotated) rawWidth else rawHeight,
                bitrateKbps = bitrateKbps,
            )
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "读取视频信息失败（scheme=${uri.scheme.orEmpty()}, " +
                    "mime=${context.contentResolver.getType(uri).orEmpty()}）",
                error,
            )
            if (error is WatchEditorException) throw error
            throw WatchEditorException(error.message ?: "无法读取视频信息", error)
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
        val inputFile = File(root, "input-video.${resolveInputExtension(context, uri)}")
        val outputDirectory = File(root, "frames")
        var stage = "准备缓存"
        try {
            stage = "复制源视频"
            withContext(Dispatchers.IO) {
                if (!outputDirectory.mkdirs()) throw WatchEditorException("无法创建视频帧缓存目录")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    inputFile.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: throw WatchEditorException("无法读取所选视频")
            }
            val outputPattern = File(outputDirectory, "frame_%05d.jpg")
            stage = "读取源视频信息"
            val info = inspect(context, uri)
            val arguments = buildArguments(inputFile, outputPattern, info, options)
            stage = "FFmpeg 解码与转帧"
            execute(arguments, options.durationMs, onProgress)
            stage = "收集转换帧"
            val frames = withContext(Dispatchers.IO) {
                outputDirectory.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                    .sortedBy { it.name }
            }
            if (frames.isEmpty()) throw WatchEditorException("FFmpeg 没有生成可用的视频帧")
            return WatchEditorVideoFrames(root, frames, options.frameDelayMs)
        } catch (error: Throwable) {
            withContext(Dispatchers.IO + NonCancellable) { root.deleteRecursively() }
            if (error is CancellationException) throw error
            Log.e(
                TAG,
                "视频导入失败（stage=$stage, input=${inputFile.name}, size=${inputFile.length()}）",
                error,
            )
            if (error is WatchEditorException) throw error
            throw WatchEditorException(error.message ?: "视频转换失败", error)
        }
    }

    internal fun buildArguments(
        inputFile: File,
        outputPattern: File,
        info: WatchEditorVideoInfo,
        options: WatchEditorVideoImportOptions,
    ): List<String> {
        val transform = resolveVideoTransform(info, options)
        val videoFilter = buildList {
            add(
                "crop=w=${transform.cropWidth}:h=${transform.cropHeight}:" +
                    "x=${transform.cropX}:y=${transform.cropY}",
            )
            if (transform.scalesOutput) {
                add("scale=w=${transform.outputWidth}:h=${transform.outputHeight}:flags=lanczos")
            }
            add("fps=${formatDecimal(options.frameRate)}")
        }.joinToString(",")
        return listOf(
            "-hide_banner",
            "-loglevel", "warning",
            "-y",
            "-ss", formatSeconds(options.startMs),
            "-i", inputFile.absolutePath,
            "-t", formatSeconds(options.durationMs),
            "-map", "0:v:0",
            "-an",
            "-vf", videoFilter,
            "-c:v", "mjpeg",
            "-pix_fmt", "yuvj420p",
            "-threads", "1",
            "-b:v", "${options.bitrateKbps}k",
            "-qmin", "2",
            "-qmax", "31",
            "-start_number", "0",
            outputPattern.absolutePath,
        )
    }

    private fun resolveInputExtension(context: Context, uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        val mimeExtension = context.contentResolver.getType(uri)
            ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
        return sequenceOf(
            displayName?.substringAfterLast('.', ""),
            uri.lastPathSegment?.substringAfterLast('.', ""),
            mimeExtension,
        )
            .mapNotNull(::sanitizeInputExtension)
            .firstOrNull()
            ?: "mp4"
    }

    private fun sanitizeInputExtension(extension: String?): String? = extension
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { value ->
            value.length in 1..10 && value.all { it in 'a'..'z' || it in '0'..'9' }
        }

    private suspend fun execute(
        arguments: List<String>,
        durationMs: Long,
        onProgress: (WatchEditorVideoImportProgress) -> Unit,
    ) = suspendCancellableCoroutine { continuation ->
        val sessionId = AtomicLong(0L)
        val mainHandler = Handler(Looper.getMainLooper())
        val logTail = StringBuilder()
        fun appendLog(message: String?) {
            val line = message?.trim().orEmpty()
            if (line.isBlank()) return
            synchronized(logTail) {
                logTail.appendLine(line)
                if (logTail.length > MAX_DIAGNOSTIC_LOG_CHARS) {
                    logTail.delete(0, logTail.length - MAX_DIAGNOSTIC_LOG_CHARS)
                }
            }
        }
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments.toTypedArray(),
            { completed ->
                if (!continuation.isActive) return@executeWithArgumentsAsync
                when {
                    ReturnCode.isSuccess(completed.returnCode) -> continuation.resume(Unit)
                    ReturnCode.isCancel(completed.returnCode) -> continuation.cancel()
                    else -> {
                        val failure = completed.failStackTrace
                            ?.lineSequence()
                            ?.firstOrNull { it.isNotBlank() }
                        val logs = synchronized(logTail) { logTail.toString().trim() }
                        val detail = buildString {
                            append("FFmpeg 视频转换失败（returnCode=")
                            append(completed.returnCode)
                            append('）')
                            if (!failure.isNullOrBlank()) {
                                append("\n")
                                append(failure.take(600))
                            }
                            if (logs.isNotBlank()) {
                                append("\n")
                                append(logs)
                            }
                        }
                        Log.e(TAG, detail)
                        continuation.resumeWithException(
                            WatchEditorException(detail),
                        )
                    }
                }
            },
            { log -> appendLog(log.message) },
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

    private const val TAG = "WatchEditorVideo"
    private const val MAX_DIAGNOSTIC_LOG_CHARS = 8_192
}
