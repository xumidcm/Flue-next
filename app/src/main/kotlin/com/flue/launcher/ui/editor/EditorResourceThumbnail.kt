package com.flue.launcher.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.watchface.editor.WatchEditorResourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Play
import java.io.File as JavaFile

/**
 * 资源管理器每一行的缩略图。
 *
 * ## 为什么必须自己解码
 *
 * 老师报「资源管理还是没法预览到图片视频字体缩略图」。这些文件都在**草稿目录**里
 * （应用私有存储中一份临时拷贝），没有 content URI、也不在媒体库里，所以没有任何
 * 现成的加载器能拿到它们 —— 只能按路径自己解。
 *
 * ## 三类各有各的取图方式
 *
 * - **图片**：`BitmapFactory` 两趟。第一趟只读边界（`inJustDecodeBounds`）算采样率，
 *   第二趟才真解。少了第一趟，一张 2048×2048 的背景图会为了 40dp 的格子申请 16MB ——
 *   资源管理器一屏十几行，直接 OOM。
 * - **视频**：`MediaMetadataRetriever.getFrameAtTime`。它是重操作（要起解码器），所以
 *   和图片一样丢到 IO 线程。
 * - **字体**：没有「缩略图」可言，`Typeface.createFromFile` 之后把 `Ag` 画成一张小图 ——
 *   作者要看的正是这个字形长什么样。位图字体（`.fnt`）是文本描述文件，出不了图，走图标。
 *
 * ## 缓存与线程
 *
 * `produceState` 的 key 取「路径 + 字节数」：同一个文件被替换过（字节数变了）就重解，
 * 否则整个滚动过程只解一次。解码全在 [Dispatchers.IO]，主线程只拿结果 ——
 * 圆屏手表的单核性能扛不住在组合期做这些。
 */
@Composable
fun EditorResourceThumbnail(
    file: JavaFile,
    kind: WatchEditorResourceKind,
    fileBytes: Long,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    val style = LauncherTheme.style
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        file.absolutePath,
        fileBytes,
        kind,
        targetPx,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeResourceThumbnail(file, kind, targetPx) }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(style.chipShape)
            // 垫一层底：透明 PNG（表盘素材里绝大多数都是）在深色行上直接看不见轮廓，
            // 有个底才知道「这里有一张图，只是它是透明的」。
            .background(style.titleColor.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        val decoded = bitmap
        if (decoded != null) {
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Fit 而不是 Crop：表盘素材常是长条指针或整屏背景，裁掉之后
                // 几张不同的素材缩略图会长得一模一样。
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                // 兜底图标按类型给：解不出来时至少能看出这是张图、一段视频还是一个脚本。
                imageVector = when (kind) {
                    WatchEditorResourceKind.SCRIPT -> MiuixIcons.Edit
                    WatchEditorResourceKind.IMAGE -> MiuixIcons.Image
                    WatchEditorResourceKind.VIDEO -> MiuixIcons.Play
                    else -> MiuixIcons.File
                },
                contentDescription = null,
                tint = style.secondaryTextColor,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** 见 [EditorResourceThumbnail] 的注释。返回 null 表示这类文件出不了缩略图。 */
private fun decodeResourceThumbnail(
    file: JavaFile,
    kind: WatchEditorResourceKind,
    targetPx: Int,
): Bitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    return when (kind) {
        WatchEditorResourceKind.IMAGE -> decodeSampledImage(file, targetPx)
        WatchEditorResourceKind.VIDEO -> decodeVideoFrame(file)
        WatchEditorResourceKind.FONT -> renderFontSample(file, targetPx)
        else -> null
    }
}

/**
 * 两趟解码：先只读边界算采样率，再按采样率真解。
 *
 * 采样率取 2 的幂（`inSampleSize` 的契约），并且允许解出来比目标格子大一档 ——
 * 正好等于目标时缩放会发虚。
 */
private fun decodeSampledImage(file: JavaFile, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    var sample = 1
    val safeTarget = targetPx.coerceAtLeast(1)
    while (width / (sample * 2) >= safeTarget && height / (sample * 2) >= safeTarget) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun decodeVideoFrame(file: JavaFile): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        // 取第一帧而不是中间某一帧：表盘视频多是循环动画，首帧就是它的静止形态，
        // 而按时间取中间帧还得先读时长，多一次跨进程调用。
        retriever.getFrameAtTime(0)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** 把字体画成一张 `Ag` 小样 —— 字体文件本身没有可展示的图像。 */
private fun renderFontSample(file: JavaFile, targetPx: Int): Bitmap? {
    val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
    val size = targetPx.coerceIn(16, 256)
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = size * 0.62f
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    // 按实际字形的垂直中心摆，而不是按 size/2：不同字体的 ascent/descent 差很多，
    // 用固定中线会让一部分字体的小样贴边或出框。
    val metrics = paint.fontMetrics
    val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
    canvas.drawText("Ag", size / 2f, baseline, paint)
    return output
}
