package com.flue.launcher.watchface.editor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
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
import kotlinx.coroutines.isActive
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
    /** Source frame rate when exposed by the platform metadata retriever. */
    val frameRate: Float? = null,
) {
    /**
     * 夹到 [WatchEditorVideoImportOptions] 合法区间的帧率，供 UI 初始化用。
     * 源可能是 120/240fps，而 options 只接受 ≤60 —— 直接把 [frameRate] 填进 options 会抛。
     */
    val importableFrameRate: Float? get() = frameRate?.coerceIn(0.5f, 60f)

}

internal const val MAX_VIDEO_DURATION_MS = 10 * 60 * 1_000L
internal const val MAX_VIDEO_DIMENSION_PX = 3_840
internal const val MAX_VIDEO_FRAME_RATE = 240f

/** Validates source metadata before any large copy or FFmpeg process starts. */
internal fun validateVideoInput(info: WatchEditorVideoInfo) {
    fun reject(message: String): Nothing = throw WatchEditorException(message)
    if (info.durationMs <= 0L) reject("视频时长无效")
    if (info.durationMs > MAX_VIDEO_DURATION_MS) {
        reject("视频时长不能超过 ${MAX_VIDEO_DURATION_MS / 60_000L} 分钟")
    }
    if (info.width < 2 || info.height < 2) reject("视频分辨率无效")
    if (info.width > MAX_VIDEO_DIMENSION_PX || info.height > MAX_VIDEO_DIMENSION_PX) {
        reject("视频分辨率不能超过 ${MAX_VIDEO_DIMENSION_PX}×${MAX_VIDEO_DIMENSION_PX}")
    }
    info.frameRate?.let { frameRate ->
        if (!frameRate.isFinite() || frameRate !in 0.5f..MAX_VIDEO_FRAME_RATE) {
            reject("视频帧率必须在 0.5 到 ${MAX_VIDEO_FRAME_RATE.toInt()} fps 之间")
        }
    }
}

internal fun validateEncodedVideoMetadata(
    containerMimeType: String?,
    codecMimeType: String?,
    width: Int,
    height: Int,
    expectedWidth: Int,
    expectedHeight: Int,
) {
    val container = containerMimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
    if (container != "video/mp4") throw WatchEditorException("输出视频容器不是 MP4")
    if (!codecMimeType.equals("video/avc", ignoreCase = true)) {
        throw WatchEditorException("输出视频编码不是 H.264")
    }
    if (width != expectedWidth || height != expectedHeight) {
        throw WatchEditorException("输出视频尺寸与转换设置不一致")
    }
}

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
    @Suppress("UNUSED_PARAMETER") info: WatchEditorVideoInfo,
    @Suppress("UNUSED_PARAMETER") preset: WatchEditorVideoResolutionPreset,
    requestedBitrateKbps: Int,
): Int = requestedBitrateKbps

/**
 * 各分辨率档的编辑器默认码率；用户随后仍可手动修改输入框。
 *
 * 取 `min(源码率, 档位默认值)`：源本身就比默认值省的时候没理由反而调高，
 * 重编码不会凭空补回信息量。
 */
internal fun defaultVideoBitrateKbps(
    info: WatchEditorVideoInfo,
    preset: WatchEditorVideoResolutionPreset,
): Int {
    val source = info.bitrateKbps?.coerceIn(64, 100_000)
    if (preset == WatchEditorVideoResolutionPreset.ORIGINAL) return source ?: 3_000
    val presetDefault = when (preset) {
        WatchEditorVideoResolutionPreset.P1080,
        WatchEditorVideoResolutionPreset.P720 -> 8_000
        WatchEditorVideoResolutionPreset.P480,
        WatchEditorVideoResolutionPreset.P360 -> 4_000
        WatchEditorVideoResolutionPreset.ORIGINAL -> 3_000
    }
    return source?.coerceAtMost(presetDefault) ?: presetDefault
}

/**
 * FFmpeg 在当前设备架构上不可用（现有 FFmpeg 只提供 arm64-v8a）。
 *
 * 单独立一个类型而不是抛通用 [WatchEditorException]，是为了让 UI 层能把它映射到
 * 字符串资源做三语提示 —— 直接抛中文字面量的话，文案就漏在业务代码里了。
 */
class WatchEditorFfmpegUnsupportedException :
    WatchEditorException("FFmpeg unavailable on this device ABI")

/**
 * 重编码用的视频编码器。
 *
 * 表盘的 `video` 图层由 `MediaPlayer` 播放，实机结论是**只有 H.264 播得出来**
 * （老师报的「视频格式必须为 h264」）。但项目用的 `ffmpeg-kit-video` 变体不含 GPL 的
 * `libx264`（写 `-c:v libx264` 会 returnCode=1，libavcodec.so 里查不到该符号），
 * 所以真 H.264 只能走机器自己的硬件编码器。编码器不可用时必须失败，不能产出
 * 不符合规范的 MPEG-4 Part 2 文件。
 */
internal enum class WatchEditorVideoEncoder(
    val ffmpegName: String,
    val pixelFormat: String,
) {
    /** 真 H.264（AVC），走 MediaCodec 硬件编码。 */
    H264_MEDIACODEC("h264_mediacodec", "yuv420p"),
    ;
}

/** 已完成 H.264 重编码、待写入草稿的临时视频文件。 */
data class WatchEditorVideoFile(
    val workingDirectory: File,
    val videoFile: File,
) {
    fun deleteTemporaryFiles() {
        workingDirectory.deleteRecursively()
    }
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
                ?: throw WatchEditorException("无法读取视频时长")
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: throw WatchEditorException("无法读取视频宽度")
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
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
            val frameRate = readFrameRate(context, uri, retriever, durationMs)
            readVideoCodecMime(context, uri)
                ?: throw WatchEditorException("所选文件没有可用的视频轨")
            val rotated = rotation == 90 || rotation == 270
            WatchEditorVideoInfo(
                durationMs = durationMs,
                width = if (rotated) rawHeight else rawWidth,
                height = if (rotated) rawWidth else rawHeight,
                bitrateKbps = bitrateKbps,
                frameRate = frameRate,
            ).also(::validateVideoInput)
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

    /**
     * 三级回退读取源帧率，全部失败返回 null。
     *
     * 1. `METADATA_KEY_CAPTURE_FRAMERATE`：最权威，但**只有慢动作/高帧率录制**才写这个字段，
     *    普通视频恒为 null，所以不能只靠它。
     * 2. [MediaExtractor] 的视频轨 `KEY_FRAME_RATE`：覆盖绝大多数普通视频。这个键在不同设备上
     *    可能存 Int 也可能存 Float，两种都要试。
     * 3. `METADATA_KEY_VIDEO_FRAME_COUNT / 时长` 反算：容器没声明帧率时的兜底，
     *    可变帧率下算出来是平均值。
     *
     * 每一级都用 `runCatching` 隔离 —— 帧率是可选的输入元数据，
     * 读取失败交给输入校验和后续 FFmpeg 处理。
     */
    private fun readFrameRate(
        context: Context,
        uri: Uri,
        retriever: MediaMetadataRetriever,
        durationMs: Long,
    ): Float? {
        fun valid(value: Float?): Float? = value?.takeIf { it.isFinite() && it in 0.5f..240f }

        val captured = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()
            } else null
        }.getOrNull()
        valid(captured)?.let { return it }

        val extracted = runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                (0 until extractor.trackCount)
                    .asSequence()
                    .map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                    ?.let { format ->
                        runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                            .getOrNull()
                            ?: runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                    }
            } finally {
                extractor.release()
            }
        }.getOrNull()
        valid(extracted)?.let { return it }

        val counted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && durationMs > 0L) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { (it / (durationMs / 1_000.0)).toFloat() }
            } else null
        }.getOrNull()
        return valid(counted)
    }

    /**
     * 源视频轨的编码 MIME（如 `video/avc`）。读不到返回 null。
     * 与 [readFrameRate] 一样用 `runCatching` 全隔离，调用方会把缺失的视频轨当作
     * 不可导入的输入，而不会启动 FFmpeg。
     */
    private fun readVideoCodecMime(context: Context, uri: Uri): String? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .asSequence()
                .map(extractor::getTrackFormat)
                .mapNotNull { it.getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("video/") }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    suspend fun convertToFrames(
        context: Context,
        uri: Uri,
        options: WatchEditorVideoImportOptions,
        onProgress: (WatchEditorVideoImportProgress) -> Unit = {},
    ): WatchEditorVideoFrames {
        if (!isRuntimeSupported()) throw WatchEditorFfmpegUnsupportedException()
        val info = inspect(context, uri)
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

    /**
     * 重编码导入：解码后按用户设定的裁剪/剪辑/分辨率/帧率/码率重新编码成 mp4。
     *
     * 视频图层统一走这一条路径，输出固定为符合表盘规范的 H.264 MP4。
     */
    suspend fun reencodeToVideo(
        context: Context,
        uri: Uri,
        options: WatchEditorVideoImportOptions,
        onProgress: (WatchEditorVideoImportProgress) -> Unit = {},
    ): WatchEditorVideoFile {
        if (!isRuntimeSupported()) throw WatchEditorFfmpegUnsupportedException()
        val info = inspect(context, uri)
        val root = withContext(Dispatchers.IO) {
            File(context.cacheDir, "watch-editor-video/${UUID.randomUUID()}").also { directory ->
                if (!directory.mkdirs()) throw WatchEditorException("无法创建视频转换缓存目录")
            }
        }
        val inputFile = File(root, "input-video.${resolveInputExtension(context, uri)}")
        // 输出容器固定 mp4：表盘包里的 video 图层由 MediaPlayer 播放，H.264/mp4
        // 是覆盖面最广的组合。
        val outputFile = File(root, "source-video.mp4")
        var stage = "准备缓存"
        try {
            stage = "复制源视频"
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    inputFile.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: throw WatchEditorException("无法读取所选视频")
            }
            stage = "FFmpeg 解码与重编码"
            val arguments = buildReencodeArguments(inputFile, outputFile, info, options)
            execute(arguments, options.durationMs, onProgress)
            stage = "校验输出"
            if (!outputFile.isFile || outputFile.length() <= 0L) {
                throw WatchEditorException("FFmpeg 没有生成可用的 H.264 视频文件")
            }
            validateEncodedOutput(outputFile, options, info)
            // 源文件已经没用了，早点让出缓存空间：视频动辄几十上百 MB。
            withContext(Dispatchers.IO) { inputFile.delete() }
            return WatchEditorVideoFile(root, outputFile)
        } catch (error: Throwable) {
            withContext(Dispatchers.IO + NonCancellable) { root.deleteRecursively() }
            if (error is CancellationException) throw error
            Log.e(
                TAG,
                "视频重编码失败（stage=$stage, input=${inputFile.name}, size=${inputFile.length()}）",
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
    ): List<String> = listOf(
        "-hide_banner",
        "-loglevel", "warning",
        "-y",
        "-ss", formatSeconds(options.startMs),
        "-i", inputFile.absolutePath,
        "-t", formatSeconds(options.durationMs),
        "-map", "0:v:0",
        "-an",
        "-vf", buildVideoFilter(info, options),
        "-c:v", "mjpeg",
        "-pix_fmt", "yuvj420p",
        "-b:v", "${options.bitrateKbps}k",
        "-qmin", "2",
        "-qmax", "31",
        "-start_number", "0",
        outputPattern.absolutePath,
    )

    /**
     * 重编码成符合表盘规范的 H.264 MP4。与 [buildArguments] 共用裁剪和帧率滤镜。
     */
    internal fun buildReencodeArguments(
        inputFile: File,
        outputFile: File,
        info: WatchEditorVideoInfo,
        options: WatchEditorVideoImportOptions,
    ): List<String> = listOf(
        "-hide_banner",
        "-loglevel", "warning",
        "-y",
        "-ss", formatSeconds(options.startMs),
        "-i", inputFile.absolutePath,
        "-t", formatSeconds(options.durationMs),
        "-map", "0:v:0",
        // 表盘视频不需要音轨，去掉能省体积也省解码开销。
        "-an",
        "-vf", buildVideoFilter(info, options),
        "-c:v", WatchEditorVideoEncoder.H264_MEDIACODEC.ffmpegName,
        "-profile:v", "baseline",
        "-level", "3.1",
        "-g", (options.frameRate * 2f).roundToInt().coerceAtLeast(1).toString(),
        "-pix_fmt", "yuv420p",
        "-b:v", "${options.bitrateKbps}k",
        "-maxrate", "${options.bitrateKbps}k",
        "-bufsize", "${options.bitrateKbps * 2}k",
        // moov 前置，播放器不必先 seek 到文件尾才能起播。
        "-movflags", "+faststart",
        "-f", "mp4",
        outputFile.absolutePath,
    )

    private fun validateEncodedOutput(
        outputFile: File,
        options: WatchEditorVideoImportOptions,
        sourceInfo: WatchEditorVideoInfo,
    ) {
        val containerMime: String?
        val outputWidth: Int
        val outputHeight: Int
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(outputFile.absolutePath)
            containerMime = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            outputWidth = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: throw WatchEditorException("无法读取输出视频宽度")
            outputHeight = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: throw WatchEditorException("无法读取输出视频高度")
        } catch (error: WatchEditorException) {
            throw error
        } catch (error: Throwable) {
            throw WatchEditorException(error.message ?: "无法校验输出视频", error)
        } finally {
            retriever.release()
        }

        val videoMime: String?
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(outputFile.absolutePath)
            videoMime = (0 until extractor.trackCount)
                .asSequence()
                .map(extractor::getTrackFormat)
                .mapNotNull { format -> format.getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("video/") }
        } catch (error: WatchEditorException) {
            throw error
        } catch (error: Throwable) {
            throw WatchEditorException(error.message ?: "无法读取输出视频编码", error)
        } finally {
            extractor.release()
        }
        val expected = resolveVideoTransform(sourceInfo, options)
        validateEncodedVideoMetadata(
            containerMimeType = containerMime,
            codecMimeType = videoMime,
            width = outputWidth,
            height = outputHeight,
            expectedWidth = expected.outputWidth,
            expectedHeight = expected.outputHeight,
        )
    }

    /**
     * 裁剪 → 缩放 → 抽帧的滤镜链。两条 FFmpeg 路径共用，避免改一处漏一处。
     */
    private fun buildVideoFilter(
        info: WatchEditorVideoInfo,
        options: WatchEditorVideoImportOptions,
    ): String {
        val transform = resolveVideoTransform(info, options)
        return buildList {
            add(
                "crop=w=${transform.cropWidth}:h=${transform.cropHeight}:" +
                    "x=${transform.cropX}:y=${transform.cropY}",
            )
            if (transform.scalesOutput) {
                add("scale=w=${transform.outputWidth}:h=${transform.outputHeight}:flags=lanczos")
            }
            add("fps=${formatDecimal(options.frameRate)}")
        }.joinToString(",")
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
