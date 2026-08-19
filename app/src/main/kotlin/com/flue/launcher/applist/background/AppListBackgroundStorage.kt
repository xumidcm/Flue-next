package com.flue.launcher.applist.background

import android.content.Context
import android.net.Uri
import com.flue.launcher.watchface.editor.WatchEditorImageCropper
import com.flue.launcher.watchface.editor.WatchEditorSourceRect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 裁出选中区域并落盘，返回新路径。
 *
 * 落在 `filesDir/app_list_background/`，文件名带时间戳而不是固定名：固定名会被
 * 「解码缓存」按路径 + mtime 认成同一张（同一秒内换图就换不掉），而且换图失败时旧图
 * 已经被覆盖没了。写成新文件、成功之后再删旧的，任何一步失败都还留着上一张能用的。
 *
 * 整个函数在 [Dispatchers.IO] 上跑 —— 区域解码 + PNG 编码是几十毫秒级。
 */
internal suspend fun cropAndStoreAppListBackground(
    context: Context,
    uri: Uri,
    sourceRect: WatchEditorSourceRect,
    previousPath: String?,
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = WatchEditorImageCropper.cropRegion(context.contentResolver, uri, sourceRect)
        val directory = File(context.filesDir, APP_LIST_BACKGROUND_DIRECTORY).apply { mkdirs() }
        val target = File(directory, "bg_${System.currentTimeMillis()}.png")
        WatchEditorImageCropper.toPngStream(bitmap).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        // 新图写成功了才清理目录里其他文件（含上一张）。
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath != target.absolutePath) file.delete()
        }
        // 上一张可能来自备份还原，落在别的目录里 —— 那种也一并清掉，别越攒越多。
        previousPath
            ?.takeIf { it != target.absolutePath }
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.delete()
        target.absolutePath
    }.getOrNull()
}

/** 自定义背景图落盘的子目录名。 */
internal const val APP_LIST_BACKGROUND_DIRECTORY = "app_list_background"
