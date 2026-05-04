package com.flue.launcher.watchface.dingdingcat

import android.content.Context
import android.net.Uri
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object DingDingCatWatchFaceStorage {
    private const val ROOT_FOLDER = "dingdingcat_watchfaces"

    fun rootDir(context: Context): File {
        return File(context.filesDir, ROOT_FOLDER).apply { mkdirs() }
    }

    fun importZip(context: Context, uri: Uri): LunchWatchFaceDescriptor {
        val tempZip = File.createTempFile("dingdingcat_import_", ".zip", context.cacheDir)
        val tempExtract = File(context.cacheDir, "dingdingcat_extract_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempZip).use { output -> input.copyTo(output) }
            } ?: error("无法读取 ZIP 文件")

            ZipFile(tempZip).use { zip ->
                val dialEntry = findDialEntry(zip) ?: error("找不到 dial.json")
                val basePath = dialEntry.name.substringBeforeLast('/', missingDelimiterValue = "")
                zip.entries().asSequence().forEach { entry ->
                    copyEntry(zip, entry, tempExtract, basePath)
                }
            }

            val parsed = DingDingCatDialParser.parse(tempExtract)
            val safeId = sanitizeId(parsed.id)
            val finalDir = File(rootDir(context), safeId)
            if (finalDir.exists()) {
                finalDir.deleteRecursively()
            }
            tempExtract.copyRecursively(finalDir, overwrite = true)
            val storedDial = DingDingCatDialParser.parse(finalDir)
            return storedDial.toDescriptor(finalDir)
        } finally {
            tempZip.delete()
            tempExtract.deleteRecursively()
        }
    }

    fun delete(context: Context, descriptor: LunchWatchFaceDescriptor): Boolean {
        val dir = descriptor.sourceDirPath?.let(::File) ?: return false
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    fun scan(context: Context): List<LunchWatchFaceDescriptor> {
        val root = rootDir(context)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.mapNotNull { dir ->
            runCatching { DingDingCatDialParser.parse(dir).toDescriptor(dir) }.getOrNull()
        }.sortedBy { it.displayName.lowercase() }
    }

    private fun DingDingCatDial.toDescriptor(dir: File): LunchWatchFaceDescriptor {
        return LunchWatchFaceDescriptor(
            id = "${DINGDINGCAT_ID_PREFIX}${id}",
            type = com.flue.launcher.watchface.LunchWatchFaceType.DINGDINGCAT,
            displayName = title,
            summary = dingDingCatSummary(),
            previewFilePath = File(dir, "thumb.png").takeIf { it.isFile }?.absolutePath,
            sourceDirPath = dir.absolutePath,
            watchFaceName = id
        )
    }

    private fun DingDingCatDial.dingDingCatSummary(): String {
        val hasGravity = drawPoints.any { it.pointType == "PICTRUE_EFFECT_GRAVITY" }
        val hasTapTrigger = drawPoints.any { point ->
            val animator = point.animator ?: return@any false
            point.pieces.size > 1 &&
                animator.triggerType == 1024 &&
                animator.startWithStatusPlay.not()
        }
        val hasAnimation = drawPoints.any { it.pieces.size > 1 }
        return when {
            hasGravity -> "叮叮猫重力表盘"
            hasTapTrigger -> "叮叮猫互动表盘"
            hasAnimation -> "叮叮猫动态表盘"
            else -> "叮叮猫图片表盘"
        }
    }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "dial_${System.currentTimeMillis()}" }
    }

    private fun findDialEntry(zip: ZipFile): ZipEntry? {
        return zip.entries().asSequence().firstOrNull { entry ->
            !entry.isDirectory && entry.name.replace('\\', '/').endsWith("dial.json")
        }
    }

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, targetRoot: File, basePath: String) {
        val normalized = entry.name.replace('\\', '/')
        if (basePath.isNotBlank() && normalized != basePath && !normalized.startsWith("$basePath/")) {
            return
        }
        val relative = when {
            basePath.isNotBlank() && normalized.startsWith("$basePath/") -> normalized.removePrefix("$basePath/")
            basePath.isNotBlank() && normalized == basePath -> ""
            else -> normalized
        }
        if (relative.isBlank()) return
        val outFile = File(targetRoot, relative)
        val canonicalTarget = outFile.canonicalFile
        val canonicalRoot = targetRoot.canonicalFile
        val insideRoot = canonicalTarget.path == canonicalRoot.path ||
            canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        require(insideRoot) { "ZIP 路径越界: ${entry.name}" }
        if (entry.isDirectory) {
            canonicalTarget.mkdirs()
            return
        }
        canonicalTarget.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
            FileOutputStream(canonicalTarget).use { output -> input.copyTo(output) }
        }
    }
}
