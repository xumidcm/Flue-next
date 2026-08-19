package com.flue.launcher.applist.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.flue.launcher.ui.anim.createLegacyPrebuiltBlur
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用列表背景的底图来源。
 *
 * ## 「跟随表盘」为什么取预览图而不是实时窗口帧
 *
 * 表盘层与应用列表层是**兄弟节点**，各有各的 alpha / scale 时钟（见 `LauncherActivity`
 * 那两个 `scaleBlurAlpha`）。想让列表背景实时显示模糊后的表盘，就得改动整套转场机器 ——
 * 而这层背景本身要被重度模糊 + 遮罩压暗，用预览图与用实时帧**肉眼无法区分**。
 *
 * 所以取图规则与 `LegacyWatchFaceBackdrop.legacyBackdropSource` 完全一致：
 * 图片表盘取图片、视频表盘取首帧、其余取 `previewFilePath`。
 *
 * ## 模糊分两条路
 *
 * - **31+**：缓存里存**清晰图**，模糊在绘制期用 `RenderEffect` 做（真高斯，拖滑块即时生效）。
 * - **30 及以下**：缓存里存**已烘焙好的模糊图**（[createLegacyPrebuiltBlur] 的降采样盒模糊，
 *   在 `Dispatchers.Default` 上算一次）。与控制中心 / 通知中心同一套策略、同一份代码。
 *
 * 这条分叉只在本文件里，渲染侧（[AppListBackgroundPainting]）看不到。
 */
internal data class AppListBackgroundImageSource(
    /** 缓存身份。图源换了它就得换，否则会一直画上一张。 */
    val key: String,
    /** 文件路径或 `content://`。 */
    val path: String,
    val isVideo: Boolean,
)

/**
 * 解析这份配置该用哪个图源。纯函数，可单测。
 *
 * 返回 `null` = 这一档不需要底图（纯色两档），或者需要但**没有可用图源**
 * （比如刚装好还没选过表盘预览图）—— 渲染侧此时落回纯色。
 */
internal fun appListBackgroundImageSourceFor(
    config: AppListBackgroundConfig,
    watchFaceId: String,
    watchFaceStableKey: String,
    watchFacePreviewPath: String?,
    builtInPhotoPath: String?,
    builtInVideoPath: String?,
    refreshToken: Int,
): AppListBackgroundImageSource? = when (config.effectiveMode) {
    AppListBackgroundMode.SOLID_SYSTEM,
    AppListBackgroundMode.SOLID_CUSTOM -> null

    AppListBackgroundMode.CUSTOM_IMAGE -> config.imagePath
        ?.takeIf(String::isNotBlank)
        ?.let { path ->
            AppListBackgroundImageSource(
                // 自定义图会被原地覆盖（每次裁剪都写同一个文件名），所以身份里必须带
                // 文件大小与 mtime，否则换了图还画旧的。
                key = "custom|${pathSignature(path)}",
                path = path,
                isVideo = false,
            )
        }

    AppListBackgroundMode.WATCH_FACE -> {
        val (path, isVideo) = when (watchFaceId) {
            BUILT_IN_PHOTO_WATCHFACE_ID -> builtInPhotoPath to false
            BUILT_IN_VIDEO_WATCHFACE_ID -> builtInVideoPath to true
            else -> watchFacePreviewPath to false
        }
        path?.takeIf(String::isNotBlank)?.let {
            AppListBackgroundImageSource(
                // refreshToken：保存表盘会**原地覆盖** preview.jpg，路径与 mtime 都可能
                // 在同一秒内不变，所以要靠这个令牌驱动重读（与表盘缩略图同一套理由）。
                key = "face|$watchFaceStableKey|${pathSignature(it)}|$refreshToken",
                path = it,
                isVideo = isVideo,
            )
        }
    }
}

private fun pathSignature(path: String): String {
    if (path.startsWith("content://")) return path
    val file = File(path)
    return if (file.isFile) "${file.absolutePath}|${file.length()}|${file.lastModified()}" else path
}

/**
 * 取（并按需烘焙）背景底图。
 *
 * 解码走 `Dispatchers.IO`、烘焙走 `Dispatchers.Default`，主线程一点都不碰 —— 全屏
 * ARGB_8888 位图分配 + 盒模糊卷积在低端机上是几十毫秒级，放主线程就是一次可见掉帧。
 *
 * @param targetWidth / [targetHeight] 视口尺寸。解码按它降采样，绝不把一张相机原图
 *   完整materialise 出来。
 */
@Composable
internal fun rememberAppListBackgroundImage(
    source: AppListBackgroundImageSource?,
    targetWidth: Int,
    targetHeight: Int,
    blurPercent: Int,
): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    if (source == null || targetWidth <= 0 || targetHeight <= 0) return null
    // 31+ 的模糊在绘制期做，缓存里存清晰图 —— 一张清晰图服务所有模糊档位，
    // 拖滑块不会每档烘焙一次。
    val bakedRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        0
    } else {
        appListBackgroundLegacyBlurRadius(blurPercent)
    }
    val cacheKey = "${source.key}|${targetWidth}x$targetHeight|r$bakedRadius"
    return produceState<ImageBitmap?>(
        initialValue = AppListBackgroundImageCache.get(cacheKey),
        key1 = cacheKey,
    ) {
        AppListBackgroundImageCache.get(cacheKey)?.let {
            value = it
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) {
            runCatching { decodeSource(context, source, targetWidth, targetHeight) }.getOrNull()
        } ?: return@produceState
        val prepared = if (bakedRadius >= 1) {
            withContext(Dispatchers.Default) {
                runCatching { createLegacyPrebuiltBlur(decoded, bakedRadius) }.getOrNull()
            } ?: decoded
        } else {
            decoded
        }
        val image = prepared.asImageBitmap()
        AppListBackgroundImageCache.put(cacheKey, image, prepared.allocationByteCount)
        value = image
    }.value
}

/**
 * 已准备好的底图缓存。
 *
 * **故意不 recycle 被淘汰的位图**：Compose 的绘制可能还持有它的 `ImageBitmap` 包装，
 * 回收一张还在画的位图会直接崩渲染节点（编辑器的裁剪预览踩过同一个坑，那里也是交给 GC）。
 * 上限按字节算，一张全屏 ARGB_8888（466² 约 870KB）算下来能装十几张，够用而且封顶。
 */
private object AppListBackgroundImageCache {
    private const val MAX_CACHE_KIB = 12 * 1024

    private class Entry(val image: ImageBitmap, val bytes: Int)

    private val cache = object : LruCache<String, Entry>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: Entry): Int =
            (value.bytes / 1024).coerceAtLeast(1)
    }

    fun get(key: String): ImageBitmap? = synchronized(cache) { cache.get(key)?.image }

    fun put(key: String, image: ImageBitmap, bytes: Int) {
        synchronized(cache) { cache.put(key, Entry(image, bytes)) }
    }
}

private fun decodeSource(
    context: Context,
    source: AppListBackgroundImageSource,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap? = if (source.isVideo) {
    extractFirstVideoFrame(context, source.path)?.scaledToCover(targetWidth, targetHeight)
} else {
    decodeSampledImage(context, source.path, targetWidth, targetHeight)
        ?.scaledToCover(targetWidth, targetHeight)
}

private fun decodeSampledImage(
    context: Context,
    path: String,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap? {
    val uri = path.takeIf { it.startsWith("content://") }?.let(Uri::parse)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (uri != null) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        }
    } else {
        BitmapFactory.decodeFile(path, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        bounds.outWidth / (sampleSize * 2) >= targetWidth &&
        bounds.outHeight / (sampleSize * 2) >= targetHeight
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return if (uri != null) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull()
    } else {
        BitmapFactory.decodeFile(path, options)
    }
}

private fun extractFirstVideoFrame(context: Context, path: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (path.startsWith("content://")) {
            retriever.setDataSource(context, Uri.parse(path))
        } else {
            retriever.setDataSource(path)
        }
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * 缩到「刚好盖住视口」的尺寸。
 *
 * 这一步是为了**封住内存**：视频首帧和 4K 相机图都可能比视口大好几倍，而背景要糊要压暗，
 * 多出来的像素一点用处没有。已经不比视口大就原样返回（不做无意义的放大与拷贝）。
 */
private fun Bitmap.scaledToCover(targetWidth: Int, targetHeight: Int): Bitmap {
    if (width <= 0 || height <= 0) return this
    val scale = max(
        targetWidth.toFloat() / width,
        targetHeight.toFloat() / height,
    )
    if (scale >= 1f) return this
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return runCatching {
        Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
    }.getOrDefault(this)
}
