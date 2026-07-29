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
    fun builtInDescriptors(context: Context): List<WatchFaceDescriptor> = listOf(
        WatchFaceDescriptor(
            id = BUILT_IN_WATCHFACE_ID,
            type = WatchFaceType.BUILTIN,
            displayName = context.getString(R.string.watchface_builtin_default_name),
            summary = context.getString(R.string.watchface_builtin_summary),
        ),
        WatchFaceDescriptor(
            id = BUILT_IN_PHOTO_WATCHFACE_ID,
            type = WatchFaceType.BUILTIN,
            displayName = context.getString(R.string.watchface_builtin_photo_name),
            summary = context.getString(R.string.watchface_builtin_summary),
            supportsSettings = true,
        ),
        WatchFaceDescriptor(
            id = BUILT_IN_VIDEO_WATCHFACE_ID,
            type = WatchFaceType.BUILTIN,
            displayName = context.getString(R.string.watchface_builtin_video_name),
            summary = context.getString(R.string.watchface_builtin_summary),
            supportsSettings = true,
        ),
    )

    fun builtInDescriptor(
        context: Context,
        id: String = BUILT_IN_WATCHFACE_ID,
    ): WatchFaceDescriptor {
        val descriptors = builtInDescriptors(context)
        return descriptors.firstOrNull { it.id == id } ?: descriptors.first()
    }

    /** Re-localises cached built-ins after an in-process language switch. */
    fun localizeBuiltIns(
        context: Context,
        descriptors: List<WatchFaceDescriptor>,
    ): List<WatchFaceDescriptor> {
        val localized = builtInDescriptors(context).associateBy(WatchFaceDescriptor::id)
        return descriptors.map { descriptor -> localized[descriptor.id] ?: descriptor }
    }

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
