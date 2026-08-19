package com.flue.launcher.watchface.store

import com.flue.launcher.watchface.standard.WatchmakerDefinitionParser
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 上传前的本地预检。
 *
 * ## 为什么必须在本地先查一遍
 *
 * 上游 `POST /my/watchfaces` **永远返回 201**：扫描不通过时文件照存，状态转 pending，
 * 带上 `rejection_reason` 并发一条站内通知（见 `api/src/routes/my.js` 那句
 * 「A scanner failure NEVER blocks the upload」）。也就是说「被自动拦截」在 HTTP 层
 * 完全看不出来 —— 客户端只会显示「上传成功」，作者要过一阵子才从通知里发现表盘没上架，
 * 而且未必看得懂原因。
 *
 * 所以在字节离开设备之前，用 [WatchStoreUploadRules] 自己查一遍，把「哪个图层缺哪个
 * 必填属性」直接摆在作者面前。这条路不需要网络、不需要登录，也不依赖服务端的响应格式。
 */
object WatchStoreUploadPreflight {

    /** 归档里 watch.xml 的读取上限。与安装路径同量级，够大又不至于被畸形档案拖死。 */
    private const val MAX_XML_BYTES = 10L * 1024 * 1024

    /**
     * 检查一个待上传的 `.watch`。返回空列表表示按商店规则没有问题。
     *
     * 解析失败（不是 zip、没有 watch.xml、XML 畸形）也只返回结论，不抛异常 —— 抛出来
     * 会让「文件坏了」和「网络挂了」在上层混成同一种错误。
     */
    fun inspect(archive: File): List<WatchStoreUploadFinding> {
        if (!archive.isFile) return emptyList()
        return runCatching {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name.replace('\\', '/').trimStart('/') }
                    .toList()
                val relative = stripCommonRoot(entries)
                val xmlName = relative.keys.firstOrNull {
                    it.equals("watch.xml", ignoreCase = true) ||
                        it.equals("watch.pxml", ignoreCase = true)
                }
                if (xmlName == null) {
                    return@use listOf(WatchStoreUploadFinding.MissingFile("watch.xml"))
                }
                // 加密表盘（watch.pxml）的真实 XML 是编码过的，本地解不开也不该误报 ——
                // 只查文件层面，图层检查交给服务端。
                if (xmlName.equals("watch.pxml", ignoreCase = true)) {
                    return@use fileLevelFindings(relative.keys)
                }
                val entry = zip.getEntry(relative.getValue(xmlName))
                    ?: return@use listOf(WatchStoreUploadFinding.MissingFile("watch.xml"))
                val definition = zip.getInputStream(entry).use { stream ->
                    WatchmakerDefinitionParser().parse(stream, MAX_XML_BYTES)
                }
                WatchStoreUploadRules.inspect(definition, relative.keys)
            }
        }.getOrDefault(emptyList())
    }

    /** 只做文件层面的检查（必需文件 + 禁用扩展名）。 */
    private fun fileLevelFindings(entries: Set<String>): List<WatchStoreUploadFinding> = buildList {
        if (entries.none { it.equals("preview.jpg", ignoreCase = true) }) {
            add(WatchStoreUploadFinding.MissingFile("preview.jpg"))
        }
        entries.forEach { entry ->
            if (entry.substringAfterLast('.', "").lowercase(Locale.ROOT)
                in WatchStoreUploadRules.blockedExtensions
            ) {
                add(WatchStoreUploadFinding.BlockedFile(entry))
            }
        }
    }

    /**
     * 若所有条目共享同一个顶层目录（`face/watch.xml`…），把它剥掉。
     *
     * 与上游 `watchParser.js` 的 `stripCommonRoot` 同规则 —— 不剥的话这种归档会被误报
     * 成「缺少 watch.xml」，而服务端明明是接受的。
     *
     * @return 相对路径 → 归档内原始条目名。
     */
    private fun stripCommonRoot(entries: List<String>): Map<String, String> {
        if (entries.isEmpty()) return emptyMap()
        val firstSegments = entries.map { it.substringBefore('/', "") }
        val common = firstSegments.first().takeIf { root ->
            root.isNotEmpty() && firstSegments.all { it == root }
        }
        return entries.associateBy { entry ->
            if (common == null) entry else entry.removePrefix("$common/")
        }
    }

    /**
     * 不合规就抛。放在上传流程的最前面 —— 两个入口（编辑器与商店页）都经
     * `HttpWatchStoreApi.uploadWatchFace`，在那里查一次两边都覆盖到。
     */
    fun requireCompliant(archive: File) {
        val findings = inspect(archive)
        if (findings.isNotEmpty()) throw WatchStoreNonCompliantArchiveException(findings)
    }
}

/** The upload screen must make a non-compliant archive an explicit user choice. */
internal enum class WatchStoreUploadDecision {
    UPLOAD,
    REVIEW,
}

internal fun watchStoreUploadDecision(findings: Int, override: Boolean): WatchStoreUploadDecision =
    if (findings <= 0 || override) WatchStoreUploadDecision.UPLOAD
    else WatchStoreUploadDecision.REVIEW

/**
 * 这块表盘不符合商店规则，上传上去会被自动转待审。
 *
 * 带着结论列表而不是一句现成的话：文案要本地化，而这一层拿不到 `Context`。
 */
class WatchStoreNonCompliantArchiveException(
    val findings: List<WatchStoreUploadFinding>,
) : Exception("Watch face does not satisfy the community store rules")
