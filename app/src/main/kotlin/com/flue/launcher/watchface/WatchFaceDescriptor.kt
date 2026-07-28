package com.flue.launcher.watchface

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.flue.launcher.R
import java.io.File

const val BUILT_IN_WATCHFACE_ID = "builtin"
const val BUILT_IN_PHOTO_WATCHFACE_ID = "builtin_photo"
const val BUILT_IN_VIDEO_WATCHFACE_ID = "builtin_video"

enum class WatchFaceType {
    BUILTIN,
    JBWATCH,
    /** Native Watchmaker 1.0/Studio 2.0 archive. */
    WATCHMAKER,
}

/** Flue-owned metadata for built-in, standard .watch, and JB watch faces. */
data class WatchFaceDescriptor(
    val id: String,
    val type: WatchFaceType,
    val displayName: String,
    val summary: String = "",
    val previewFilePath: String? = null,
    val versionCode: Long = 0,
    val author: String? = null,
    val sourceDirPath: String? = null,
    val supportsSettings: Boolean = false,
) {
    val isBuiltin: Boolean get() = type == WatchFaceType.BUILTIN
    val isJbWatch: Boolean get() = type == WatchFaceType.JBWATCH
    val isWatchmaker: Boolean get() = type == WatchFaceType.WATCHMAKER
    val stableKey: String get() = id
}

object WatchFaceDescriptors {
    fun builtInDescriptors(): List<WatchFaceDescriptor> = listOf(
        WatchFaceDescriptor(
            id = BUILT_IN_WATCHFACE_ID,
            type = WatchFaceType.BUILTIN,
            displayName = "星野 深蓝",
            summary = "内置表盘",
        ),
        WatchFaceDescriptor(
            id = BUILT_IN_PHOTO_WATCHFACE_ID,
            type = WatchFaceType.BUILTIN,
            displayName = "图片表盘",
            summary = "Flue Next 内置表盘",
            supportsSettings = true,
        ),
        WatchFaceDescriptor(
            id = BUILT_IN_VIDEO_WATCHFACE_ID,
            type = WatchFaceType.BUILTIN,
            displayName = "视频表盘",
            summary = "Flue Next 内置表盘",
            supportsSettings = true,
        ),
    )

    fun builtInDescriptor(id: String = BUILT_IN_WATCHFACE_ID): WatchFaceDescriptor =
        builtInDescriptors().firstOrNull { it.id == id } ?: builtInDescriptors().first()

    fun loadPreviewDrawable(context: Context, descriptor: WatchFaceDescriptor): Drawable? {
        descriptor.previewFilePath?.let { previewPath ->
            val previewFile = File(previewPath)
            if (previewFile.isFile) {
                BitmapFactory.decodeFile(previewFile.absolutePath)?.let { bitmap ->
                    return BitmapDrawable(context.resources, bitmap)
                }
            }
        }
        return if (descriptor.isBuiltin) {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher_foreground)
        } else {
            null
        }
    }
}
