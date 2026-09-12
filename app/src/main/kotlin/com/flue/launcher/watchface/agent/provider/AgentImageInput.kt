package com.flue.launcher.watchface.agent.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/** Bounds decoding before allocating pixels; the wire payload is a supported static image. */
object AgentImageInput {
    const val MAX_FILE_BYTES = 20 * 1024 * 1024
    fun fromFile(file: File): AgentRequestImage {
        require(file.isFile && file.length() in 1..MAX_FILE_BYTES.toLong()) { "图片不存在或超过 20 MB" }
        return fromBytes(file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES) { "图片超过 20 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        })
    }

    fun fromBytes(bytes: ByteArray): AgentRequestImage {
        require(bytes.size in 1..MAX_FILE_BYTES) { "图片大小无效" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片内容" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1568) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })) { "图片解码失败" }
        try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            return AgentRequestImage("image/png", Base64.getEncoder().encodeToString(output.toByteArray()))
        } finally { bitmap.recycle() }
    }
}
