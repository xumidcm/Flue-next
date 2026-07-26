package com.flue.launcher.watchface.jbwatch

import android.content.Context
import android.net.Uri
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceType
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

const val JBWATCH_ID_PREFIX = "jbwatch:"

data class JbWatchMetadata(
    val id: String,
    val title: String,
    val summary: String,
    val rootDir: File,
    val previewFileName: String? = null
)

object JbWatchFaceStorage {
    private const val ROOT_FOLDER = "jbwatch_faces"

    // 与 standard 侧 WatchArchiveValidator 对齐的导入防护。此前这条导入
    // 通道对归档大小 / 条目数 / 展开总量没有任何限制，一个高压缩比的
    // zip 炸弹就能写满 /data 让整机异常（审查报告 P1-23）。
    private const val MAX_ARCHIVE_BYTES = 96L * 1024L * 1024L
    private const val MAX_ENTRY_COUNT = 2_048
    private const val MAX_SINGLE_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_TOTAL_EXTRACTED_BYTES = 256L * 1024L * 1024L

    fun rootDir(context: Context): File {
        return File(context.filesDir, ROOT_FOLDER).apply { mkdirs() }
    }

    fun importArchive(context: Context, uri: Uri): LunchWatchFaceDescriptor {
        val tempZip = File.createTempFile("jbwatch_import_", ".watch", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempZip).use { output ->
                    copyWithLimit(input, output, MAX_ARCHIVE_BYTES, "表盘归档")
                }
            } ?: error("无法读取 .watch 表盘归档")
            return importArchiveFile(context, tempZip)
        } finally {
            tempZip.delete()
        }
    }

    private fun copyWithLimit(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        limitBytes: Long,
        what: String,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            require(total <= limitBytes) {
                "$what 超过大小上限（${limitBytes / (1024 * 1024)}MB）"
            }
            output.write(buffer, 0, read)
        }
    }

    fun importArchiveFile(context: Context, archive: File): LunchWatchFaceDescriptor {
        val tempExtract = File(context.cacheDir, "jbwatch_extract_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            extractArchive(archive, tempExtract)
            val watchRoot = findWatchRoot(tempExtract)
                ?: error("缺少 watch.xml / watch.pxml")
            runCatching { JbWatchParser.parse(watchRoot) }
                .getOrElse { throw IllegalArgumentException("JB 表盘解析失败: ${it.message ?: it.javaClass.simpleName}", it) }
            val metadata = parseMetadata(watchRoot)
            val finalDir = File(rootDir(context), sanitizeId(metadata.id))
            if (finalDir.exists()) {
                finalDir.deleteRecursively()
            }
            watchRoot.copyRecursively(finalDir, overwrite = true)
            val stored = parseMetadata(finalDir)
            return stored.toDescriptor()
        } finally {
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
            runCatching { parseMetadata(dir).toDescriptor() }.getOrNull()
        }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    fun hasJbWatchStructure(rootDir: File): Boolean {
        val watchXml = File(rootDir, "watch.xml")
        val watchPxml = File(rootDir, "watch.pxml")
        return watchXml.isFile && watchPxml.isFile
    }

    private fun parseMetadata(rootDir: File): JbWatchMetadata {
        require(hasJbWatchStructure(rootDir)) { "缺少 watch.xml / watch.pxml" }
        val watchXml = File(rootDir, "watch.xml")
        val document = newSecureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(watchXml)
        val root = document.documentElement ?: error("watch.xml 无根节点")
        val title = root.getAttribute("name").trim().ifBlank { rootDir.name }
        val features = root.getAttribute("features").trim()
        val summary = buildString {
            append(".watch 表盘")
            if (features.isNotBlank()) {
                append(" · ")
                append(features)
            }
        }
        val preview = listOf("preview.jpg", "preview_dim.jpg")
            .firstOrNull { name -> File(rootDir, name).isFile }
        val id = rootDir.name.ifBlank { title }
        return JbWatchMetadata(
            id = id,
            title = title,
            summary = summary,
            rootDir = rootDir,
            previewFileName = preview
        )
    }

    private fun JbWatchMetadata.toDescriptor(): LunchWatchFaceDescriptor {
        return LunchWatchFaceDescriptor(
            id = "$JBWATCH_ID_PREFIX$id",
            type = LunchWatchFaceType.JBWATCH,
            displayName = title,
            summary = summary,
            previewFilePath = previewFileName?.let { File(rootDir, it).absolutePath },
            sourceDirPath = rootDir.absolutePath,
            watchFaceName = id
        )
    }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "jbwatch_${System.currentTimeMillis()}" }
    }

    private fun newSecureDocumentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
    }

    private fun extractArchive(zipFile: File, targetRoot: File) {
        require(zipFile.length() <= MAX_ARCHIVE_BYTES) {
            "表盘归档超过大小上限（${MAX_ARCHIVE_BYTES / (1024 * 1024)}MB）"
        }
        ZipFile(zipFile).use { zip ->
            var entryCount = 0
            var totalExtracted = 0L
            zip.entries().asSequence().forEach { entry ->
                entryCount += 1
                require(entryCount <= MAX_ENTRY_COUNT) {
                    "表盘归档条目过多（上限 $MAX_ENTRY_COUNT）"
                }
                totalExtracted += copyEntry(zip, entry, targetRoot)
                require(totalExtracted <= MAX_TOTAL_EXTRACTED_BYTES) {
                    "表盘归档展开总量超限（上限 ${MAX_TOTAL_EXTRACTED_BYTES / (1024 * 1024)}MB）"
                }
            }
        }
    }

    private fun findWatchRoot(rootDir: File): File? {
        if (hasJbWatchStructure(rootDir)) return rootDir
        return rootDir
            .walkTopDown()
            .filter { it.isDirectory && hasJbWatchStructure(it) }
            .minByOrNull { dir ->
                dir.relativeTo(rootDir).invariantSeparatorsPath.count { it == '/' }
            }
    }

    /** 返回该条目实际展开的字节数（目录为 0）。 */
    private fun copyEntry(zip: ZipFile, entry: ZipEntry, targetRoot: File): Long {
        val normalized = entry.name.replace('\\', '/')
        val outFile = File(targetRoot, normalized)
        val canonicalTarget = outFile.canonicalFile
        val canonicalRoot = targetRoot.canonicalFile
        val insideRoot = canonicalTarget.path == canonicalRoot.path ||
            canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        require(insideRoot) { "归档路径越界: ${entry.name}" }
        if (entry.isDirectory) {
            canonicalTarget.mkdirs()
            return 0L
        }
        canonicalTarget.parentFile?.mkdirs()
        // 逐条目限流：entry.size 是归档自报的值，zip 炸弹会谎报，
        // 必须按实际写出的字节数计数。
        zip.getInputStream(entry).use { input ->
            FileOutputStream(canonicalTarget).use { output ->
                return copyWithLimit(input, output, MAX_SINGLE_ENTRY_BYTES, "归档条目 ${entry.name}")
            }
        }
    }
}
