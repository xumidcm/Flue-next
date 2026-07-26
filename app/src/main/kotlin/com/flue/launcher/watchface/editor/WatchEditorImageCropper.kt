package com.flue.launcher.watchface.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Bitmap decoding for the image-import crop step. Every decode is bounded:
 * bounds are read first, then the pixel decode uses a power-of-two sample size
 * so a huge camera photo never materialises at full resolution.
 */
object WatchEditorImageCropper {
    /** Longest edge of the interactive crop preview bitmap. */
    const val PREVIEW_MAX_DIMENSION = 1024

    /** Longest edge of the final cropped output written into the draft. */
    const val OUTPUT_MAX_DIMENSION = 1024

    data class SourceBounds(val width: Int, val height: Int)

    fun readBounds(resolver: ContentResolver, uri: Uri): SourceBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(resolver, uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw WatchEditorException("无法识别所选图片")
        }
        return SourceBounds(options.outWidth, options.outHeight)
    }

    /** Decodes a downsampled preview for the crop UI. */
    fun decodePreview(resolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = readBounds(resolver, uri)
        val options = BitmapFactory.Options().apply {
            inSampleSize = watchEditorCropSampleSize(
                bounds.width,
                bounds.height,
                PREVIEW_MAX_DIMENSION,
            )
        }
        val bitmap = openStream(resolver, uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw WatchEditorException("图片解码失败")
        return bitmap
    }

    /**
     * Crops [sourceRect] (full-resolution source coordinates) out of the image
     * and returns a bitmap no larger than [OUTPUT_MAX_DIMENSION]. Prefers
     * [BitmapRegionDecoder] so only the cropped region is ever decoded; falls
     * back to a sampled full decode for formats without region support.
     */
    fun cropRegion(
        resolver: ContentResolver,
        uri: Uri,
        sourceRect: WatchEditorSourceRect,
    ): Bitmap {
        require(sourceRect.width > 0 && sourceRect.height > 0) { "裁剪区域为空" }
        val sample = watchEditorCropSampleSize(
            sourceRect.width,
            sourceRect.height,
            OUTPUT_MAX_DIMENSION,
        )
        val region = Rect(sourceRect.left, sourceRect.top, sourceRect.right, sourceRect.bottom)
        val fromRegionDecoder = runCatching {
            openStream(resolver, uri).use { input ->
                val decoder = newRegionDecoder(input) ?: return@use null
                try {
                    decoder.decodeRegion(
                        region,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                } finally {
                    decoder.recycle()
                }
            }
        }.getOrNull()
        if (fromRegionDecoder != null) return fromRegionDecoder

        // Fallback: sampled full decode, then crop in the sampled space.
        val bounds = readBounds(resolver, uri)
        val fullSample = watchEditorCropSampleSize(
            bounds.width,
            bounds.height,
            // Keep enough resolution that the cropped part still reaches the
            // output budget where possible.
            OUTPUT_MAX_DIMENSION * maxOf(
                1,
                maxOf(bounds.width, bounds.height) / maxOf(1, maxOf(sourceRect.width, sourceRect.height)),
            ),
        )
        val full = openStream(resolver, uri).use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply { inSampleSize = fullSample },
            )
        } ?: throw WatchEditorException("图片解码失败")
        val scaleX = full.width.toFloat() / bounds.width
        val scaleY = full.height.toFloat() / bounds.height
        val left = (sourceRect.left * scaleX).toInt().coerceIn(0, full.width - 1)
        val top = (sourceRect.top * scaleY).toInt().coerceIn(0, full.height - 1)
        val width = (sourceRect.width * scaleX).toInt().coerceIn(1, full.width - left)
        val height = (sourceRect.height * scaleY).toInt().coerceIn(1, full.height - top)
        val cropped = Bitmap.createBitmap(full, left, top, width, height)
        // createBitmap may return the source instance; only recycle real copies.
        if (cropped !== full) full.recycle()
        return cropped
    }

    /** Compresses the cropped bitmap to PNG bytes ready for [WatchEditorSession.importResource]. */
    fun toPngStream(bitmap: Bitmap): InputStream {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw WatchEditorException("图片编码失败")
        }
        return ByteArrayInputStream(output.toByteArray())
    }

    private fun newRegionDecoder(input: InputStream): BitmapRegionDecoder? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(input)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(input, false)
        }

    private fun openStream(resolver: ContentResolver, uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw WatchEditorException("无法读取所选图片")
}
