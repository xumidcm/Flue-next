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
import kotlinx.coroutines.currentCoroutineContext
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
    /**
     * 容器里声明的旋转角（0/90/180/270）。[width]/[height] 已按它换算成显示朝向，
     * 但直通判定必须能看到原始角度：直通是原文件字节拷贝，旋转元数据会一起留在
     * 文件里，交给播放器处理。90/270 的横竖交换风险最大，所以这一档不走直通。
     */
    val rotationDegrees: Int = 0,
) {
    /**
     * 夹到 [WatchEditorVideoImportOptions] 合法区间的帧率，供 UI 初始化用。
     * 源可能是 120/240fps，而 options 只接受 ≤60 —— 直接把 [frameRate] 填进 options 会抛。
     */
    val importableFrameRate: Float? get() = frameRate?.coerceIn(0.5f, 60f)
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
 * 直通判定：源文件能否原样拷进 `videos/`，跳过 FFmpeg 重编码。
 *
 * 直通只在「用户什么都没改」时成立，所以每一条都是等值比较而不是容差比较。
 * 判定材料全部现成 —— [resolveVideoTransform] 已算出裁剪与缩放，[inspect]
 * 已取到源码率/帧率/旋转。
 *
 * 不满足任何一条就退回原有的转帧管线；直通是优化，不是新语义。
 */
internal fun canPassThroughVideo(
    info: WatchEditorVideoInfo,
    options: WatchEditorVideoImportOptions,
): Boolean {
    // 旋转 90/270 会让显示朝向与存储朝向不一致。直通保留原始元数据，
    // 播放器若忽略 rotation 就会显示成躺倒的画面 —— 这一档交给 FFmpeg
    // 烘焙掉旋转，比赌播放器行为安全。
    if (info.rotationDegrees == 90 || info.rotationDegrees == 270) return false

    // 裁剪必须是整幅。这里不用 crop == Full 比较对象，而是走
    // resolveVideoTransform 的实际输出：偶数对齐可能让「几乎全幅」的
    // 裁剪落回整幅，那种情况同样算没裁。
    val transform = resolveVideoTransform(info, options)
    if (transform.cropX != 0 || transform.cropY != 0) return false
    if (transform.cropWidth != info.width || transform.cropHeight != info.height) return false
    if (transform.scalesOutput) return false

    // 时间轴必须是整段。startMs 非零或 endMs 早于结尾都意味着要裁剪时间，
    // 只有解码才能做到。durationMs 允许 1ms 误差：容器时长与
    // MediaMetadataRetriever 的读数常有一帧级别的出入。
    if (options.startMs != 0L) return false
    if (kotlin.math.abs(options.endMs - info.durationMs) > 1L) return false

    // 帧率与码率必须与源一致。任一为 null 说明平台没暴露该元数据，
    // 无从判断「有没有变」，此时保守走重编码。
    //
    // 源帧率 >60 时（120/240fps 素材）这里必然不相等：options 的 init 只接受
    // ≤60，UI 也只会填 [WatchEditorVideoInfo.importableFrameRate] 夹过的值，
    // 相等分支不可能出现。此时直通不成立是正确行为 —— 输出帧率确实和源不同，
    // 必须重编码 —— 不是漏判。
    val sourceFrameRate = info.frameRate ?: return false
    if (kotlin.math.abs(options.frameRate - sourceFrameRate) > 0.01f) return false
    val sourceBitrate = info.bitrateKbps ?: return false
    if (options.bitrateKbps != sourceBitrate) return false

    return true
}

/**
 * FFmpeg 在当前设备架构上不可用（现有 FFmpeg 只提供 arm64-v8a）。
 *
 * 单独立一个类型而不是抛通用 [WatchEditorException]，是为了让 UI 层能把它映射到
 * 字符串资源做三语提示 —— 直接抛中文字面量的话，文案就漏在业务代码里了。
 */
class WatchEditorFfmpegUnsupportedException :
    WatchEditorException("FFmpeg unavailable on this device ABI")

/** 直通导入的产物：已落在草稿 `videos/` 下的源文件副本。 */
data class WatchEditorVideoPassThrough(
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
            val frameRate = readFrameRate(context, uri, retriever, durationMs)
            val rotated = rotation == 90 || rotation == 270
            WatchEditorVideoInfo(
                durationMs = durationMs,
                width = if (rotated) rawHeight else rawWidth,
                height = if (rotated) rawWidth else rawHeight,
                bitrateKbps = bitrateKbps,
                frameRate = frameRate,
                rotationDegrees = rotation,
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
     * 每一级都用 `runCatching` 隔离 —— 帧率只是优化直通判定用的可选信息，
     * 读取失败绝不能让 [inspect] 抛异常打断整个导入。
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

    suspend fun convertToFrames(
        context: Context,
        uri: Uri,
        options: WatchEditorVideoImportOptions,
        onProgress: (WatchEditorVideoImportProgress) -> Unit = {},
    ): WatchEditorVideoFrames {
        if (!isRuntimeSupported()) throw WatchEditorFfmpegUnsupportedException()
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

    /**
     * 直通导入：把源文件原样拷到缓存目录，不解码、不重编码。
     *
     * 调用方须先用 [canPassThroughVideo] 确认条件成立。这里不重复判定，
     * 因为判定需要 [WatchEditorVideoInfo]，而拿它要多读一次元数据。
     *
     * 与 [convertToFrames] 用同一套缓存目录约定和失败清理策略，好让上层
     * 两条路径的生命周期处理保持一致。
     */
    suspend fun passThrough(
        context: Context,
        uri: Uri,
        onProgress: (WatchEditorVideoImportProgress) -> Unit = {},
        durationMs: Long = 0L,
    ): WatchEditorVideoPassThrough {
        val root = withContext(Dispatchers.IO) {
            File(context.cacheDir, "watch-editor-video/${UUID.randomUUID()}").also { directory ->
                if (!directory.mkdirs()) throw WatchEditorException("无法创建视频缓存目录")
            }
        }
        val videoFile = File(root, "source-video.${resolveInputExtension(context, uri)}")
        try {
            withContext(Dispatchers.IO) {
                val totalBytes = runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull()?.takeIf { it > 0L }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    videoFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copied = 0L
                        while (true) {
                            // 拷贝是阻塞读，不响应协程取消；每个缓冲区边界查一次，
                            // 否则用户取消后整个文件仍会拷完。
                            if (!currentCoroutineContext().isActive) {
                                throw CancellationException("视频拷贝已取消")
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            // 进度按字节比例映射到时长轴：直通没有解码时间轴可用，
                            // 但复用同一个进度模型能让 UI 不必区分两条路径。
                            if (totalBytes != null && durationMs > 0L) {
                                val processed = (durationMs * copied / totalBytes)
                                    .coerceIn(0L, durationMs)
                                onProgress(WatchEditorVideoImportProgress(processed, durationMs))
                            }
                        }
                    }
                } ?: throw WatchEditorException("无法读取所选视频")
            }
            if (videoFile.length() <= 0L) {
                throw WatchEditorException("所选视频为空文件")
            }
            if (durationMs > 0L) {
                onProgress(WatchEditorVideoImportProgress(durationMs, durationMs))
            }
            return WatchEditorVideoPassThrough(root, videoFile)
        } catch (error: Throwable) {
            withContext(Dispatchers.IO + NonCancellable) { root.deleteRecursively() }
            if (error is CancellationException) throw error
            Log.e(TAG, "视频直通导入失败（input=${videoFile.name}）", error)
            if (error is WatchEditorException) throw error
            throw WatchEditorException(error.message ?: "视频导入失败", error)
        }
    }

    /**
     * 重编码导入：解码后按用户设定的裁剪/剪辑/分辨率/帧率/码率重新编码成 mp4。
     *
     * 这是 video 图层的第三个终点。此前只有「转序列帧」和「字节直通」两条路，
     * 于是「video 图层 + 用户改过参数」无路可走 —— 直通要求所有参数与源一致，
     * 转序列帧又会把图层改写成 image_gif。
     *
     * 产物形状与 [passThrough] 相同（一个工作目录 + 一个视频文件），所以复用
     * [WatchEditorVideoPassThrough]，上层两条路径的落盘与清理逻辑不必分叉。
     */
    suspend fun reencodeToVideo(
        context: Context,
        uri: Uri,
        options: WatchEditorVideoImportOptions,
        onProgress: (WatchEditorVideoImportProgress) -> Unit = {},
    ): WatchEditorVideoPassThrough {
        if (!isRuntimeSupported()) throw WatchEditorFfmpegUnsupportedException()
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
            stage = "读取源视频信息"
            val info = inspect(context, uri)
            val arguments = buildReencodeArguments(inputFile, outputFile, info, options)
            stage = "FFmpeg 解码与重编码"
            execute(arguments, options.durationMs, onProgress)
            stage = "校验输出"
            if (!outputFile.isFile || outputFile.length() <= 0L) {
                throw WatchEditorException("FFmpeg 没有生成可用的视频文件")
            }
            // 源文件已经没用了，早点让出缓存空间：视频动辄几十上百 MB。
            withContext(Dispatchers.IO) { inputFile.delete() }
            return WatchEditorVideoPassThrough(root, outputFile)
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
     * 重编码成 mp4 的参数。与 [buildArguments] 共用同一条滤镜链，差异只在编码器：
     * mjpeg 出的是独立静帧序列，这里出的是单个 H.264 视频轨。
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
        // 用 mpeg4 而不是 libx264：ffmpeg-kit-video 这个变体**不含 GPL 的
        // libx264**（实机 returnCode=1 就是它，libavcodec.so 里查不到该符号）。
        // 备选里 h264_mediacodec 依赖机型硬件编码器、失败率不可控，kvazaar 太慢，
        // libvpx 要换 webm 容器 —— mpeg4 是纯软件、mp4 原生、MediaPlayer 必然
        // 能解的那个，代价只是同码率下画质略逊于 H.264。
        "-c:v", "mpeg4",
        // mpeg4 编码器只认全范围的 yuv420p，写 yuvj420p 会被拒。
        "-pix_fmt", "yuv420p",
        "-b:v", "${options.bitrateKbps}k",
        // moov 前置，播放器不必先 seek 到文件尾才能起播。
        "-movflags", "+faststart",
        outputFile.absolutePath,
    )

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
    private const val COPY_BUFFER_BYTES = 8_192
}
