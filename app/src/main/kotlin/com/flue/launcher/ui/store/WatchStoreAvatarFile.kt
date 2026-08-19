package com.flue.launcher.ui.store

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import com.flue.launcher.watchface.editor.WatchEditorImageCropper
import com.flue.launcher.watchface.editor.WatchEditorSourceRect
import com.flue.launcher.watchface.store.WatchStoreAvatarRules
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * H2 — 把裁剪结果编码成能传的头像文件。
 *
 * 三种结局分开返回，而不是统一「失败」：
 *
 * - [AvatarEncodeResult.DecodeFailed]：图根本读不出来（选到了非图片、损坏文件、provider
 *   给不出流）。这一类**在本地就该拦住**，不值得占一次上传往返。
 * - [AvatarEncodeResult.TooLarge]：降过一档质量之后仍超 2MB。提示要说清「压过了还是太大」，
 *   否则用户会以为是网络问题反复重试。
 * - [AvatarEncodeResult.Success]：交给上传；**类型仍由服务端按 magic bytes 判**
 *   （见 [WatchStoreAvatarRules] 的注释）。
 *
 * 编码格式选 WebP：同画质下比 PNG 小一个量级，而上游三种格式都收。API 30 之后
 * `WEBP` 常量被 `WEBP_LOSSY` 取代，minSdk 27 所以两条都要留。
 */
internal sealed interface AvatarEncodeResult {
    data class Success(val file: File) : AvatarEncodeResult
    data object DecodeFailed : AvatarEncodeResult
    data object TooLarge : AvatarEncodeResult
}

internal suspend fun cropAndEncodeAvatar(
    context: Context,
    uri: Uri,
    sourceRect: WatchEditorSourceRect,
): AvatarEncodeResult = withContext(Dispatchers.IO) {
    val cropped = runCatching {
        WatchEditorImageCropper.cropRegion(context.contentResolver, uri, sourceRect)
    }.getOrNull() ?: return@withContext AvatarEncodeResult.DecodeFailed

    val target = WatchStoreAvatarRules.fitWithinMaxEdge(cropped.width, cropped.height)
    val scaled = if (target.width == cropped.width && target.height == cropped.height) {
        cropped
    } else {
        // filter = true：头像是缩小，双线性采样比最近邻明显干净。
        Bitmap.createScaledBitmap(cropped, target.width, target.height, true).also {
            if (it !== cropped) cropped.recycle()
        }
    }

    val directory = File(context.cacheDir, AVATAR_CACHE_DIRECTORY).apply { mkdirs() }
    // 固定名 + 每次先删：这是给上传用的临时文件，攒着只会占空间。
    val file = File(directory, "avatar.webp")
    val outcome = runCatching {
        var lastLength = -1L
        for (quality in WatchStoreAvatarRules.QUALITY_LADDER) {
            file.delete()
            val encoded = file.outputStream().buffered().use { output ->
                scaled.compress(webpFormat(), quality, output)
            }
            if (!encoded) return@runCatching AvatarEncodeResult.DecodeFailed
            lastLength = file.length()
            if (WatchStoreAvatarRules.withinSizeLimit(lastLength)) {
                return@runCatching AvatarEncodeResult.Success(file)
            }
        }
        // 阶梯走完还超（或最后一档编出了 0 字节）。
        if (lastLength <= 0L) AvatarEncodeResult.DecodeFailed else AvatarEncodeResult.TooLarge
    }.getOrDefault(AvatarEncodeResult.DecodeFailed)

    scaled.recycle()
    if (outcome !is AvatarEncodeResult.Success) file.delete()
    outcome
}

@Suppress("DEPRECATION")
private fun webpFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }

private const val AVATAR_CACHE_DIRECTORY = "store-avatar"
