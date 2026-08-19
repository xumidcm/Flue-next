package com.flue.launcher.watchface.store

import com.flue.launcher.watchface.standard.WatchmakerDefinition
import com.flue.launcher.watchface.standard.WatchmakerDefinitionVersion
import java.util.Locale

/**
 * 社区商店上传规则的**本地镜像**。
 *
 * ## 为什么要在本地再写一份
 *
 * 上游 `api/src/services/watchParser.js` 扫描每个上传的 `.watch`，把问题分成
 * `security`（致命，直接 reject）与 `validation`（结构问题）两类。只要有任意一条，
 * `scan.success` 就是 false —— 而上游 `POST /my/watchfaces` **永远返回 201**：
 * 文件照存，状态转 pending，带上 `rejection_reason` 并发一条站内通知。
 *
 * 也就是说「被自动拦截」在 HTTP 层看不出来。客户端要么解析预检返回（见
 * [WatchStoreUploadPreflight]），要么在字节离开设备之前自己先查一遍 —— 后者能在
 * 没网、没登录、还在编辑器里的时候就告诉作者哪里不合规，所以两条都要有。
 *
 * ## 这份表跟谁对齐
 *
 * 逐条抄自上游 `watchParser.js`（`LAYER_TYPES` / `LAYER_REQUIRED` / `WM2_ONLY_LAYERS`
 * / `REQUIRED_FILES`），命名与分组都保持一致，方便日后对照上游改动。**不要按我们
 * 渲染器的能力增删**：这张表描述的是「商店会不会拦」，不是「我们能不能渲染」。
 * 我们渲染不了但商店放行的类型，拦下来只会挡住作者上传给别的客户端用。
 */
object WatchStoreUploadRules {

    /** 归档里必须存在的文件。`watch.pxml`（加密表盘）可以顶替 `watch.xml`。 */
    val requiredFiles: List<String> = listOf("watch.xml", "preview.jpg")

    /** 全部已知图层类型。未知类型算 validation error。 */
    val knownLayerTypes: Set<String> = setOf(
        // 1.0
        "image", "text", "ring", "progress", "image_cond", "chart", "text_curved",
        "text_ring", "markers", "markers_hm", "shape", "rounded", "image_gif",
        "video", "map", "series", "calendar", "gallery_2d",
        "camera", "3d_model", "3d_text", "light_dir", "light_spot", "light_point",
        // 2.0 新增
        "group", "gif", "image_tap", "photo_slot", "svg", "ring_image",
        "progress_image", "segments", "hr_zone", "hr_chart", "sunarc", "daylight",
        "complication", "complication_text", "complication_image",
    )

    /**
     * 每类图层的必填属性。**属性存在但为空字符串同样算缺失**（上游判据是
     * `undefined || === ''`）。
     */
    val requiredAttributes: Map<String, List<String>> = mapOf(
        "image" to listOf("path", "width", "height"),
        "text" to listOf("text", "text_size", "font"),
        "text_curved" to listOf("text", "text_size", "font", "radius"),
        "text_ring" to listOf("radius", "text_size", "font"),
        "ring" to listOf("radius_outer", "radius_inner", "angle_total", "angle"),
        "progress" to listOf("width", "height", "pct_complete"),
        "image_cond" to listOf("path", "width", "height", "cond_grid", "cond_value"),
        "image_gif" to listOf("path", "width", "height"),
        "video" to listOf("path", "width", "height"),
        "map" to listOf("width", "height", "lat", "lon"),
        "chart" to listOf("width", "height", "data_points"),
        "series" to listOf("series", "orientation", "text_size", "font"),
        "calendar" to listOf("text_size", "font"),
        "markers" to listOf("radius", "m_count", "m_width", "m_height"),
        "markers_hm" to listOf("radius", "m_hour", "m_minute"),
        "shape" to listOf("shape", "width", "height"),
        "rounded" to listOf("width", "height", "radius", "corner_type"),
        "gallery_2d" to listOf("width", "height", "dur_on"),
        // 2.0
        "gif" to listOf("path", "width", "height"),
        "image_tap" to listOf("path", "width", "height"),
        "photo_slot" to listOf("width", "height"),
        "svg" to listOf("path", "width", "height"),
        "ring_image" to listOf("path", "radius_outer", "radius_inner", "angle_total", "angle"),
        "progress_image" to listOf("path", "width", "height", "pct_complete"),
        "segments" to listOf("path", "width", "height"),
        "hr_zone" to listOf("width", "height"),
        "hr_chart" to listOf("width", "height"),
        "sunarc" to listOf("width", "height"),
        "daylight" to listOf("width", "height"),
        "complication" to listOf("slot"),
        "complication_text" to listOf("compField", "text"),
        "complication_image" to listOf("compField", "path"),
        "3d_model" to listOf("path"),
        "3d_text" to listOf("text", "text_size"),
    )

    /** 只存在于 2.0 的类型。1.0 表盘（根节点没有 `build_source="studio"`）里出现就算错。 */
    val format2OnlyLayerTypes: Set<String> = setOf(
        "group", "gif", "image_tap", "photo_slot", "svg", "ring_image",
        "progress_image", "segments", "hr_zone", "hr_chart", "sunarc", "daylight",
        "complication", "complication_text", "complication_image",
    )

    /** 一律拒收的扩展名（上游归进 security，直接 reject）。 */
    val blockedExtensions: Set<String> = setOf(
        "exe", "apk", "so", "sh", "dll", "bat", "cmd", "com", "ps1",
        "vbs", "jar", "elf", "app", "msi", "scr", "js", "php", "py",
    )

    /** 上游 `MAX_FILE_SIZE`：单个归档 20 MB。服务端可由管理员调大，这里取默认值。 */
    const val MAX_ARCHIVE_BYTES: Long = 20L * 1024 * 1024

    /**
     * 本地预检：按上面的规则挑出这块表盘会被记账的问题。
     *
     * @param definition 已解析的 watch.xml。
     * @param archiveEntryNames 归档内的相对路径（用于必需文件与扩展名检查）。传空集合
     *   表示「还在编辑器里、尚未打包」，此时跳过文件层面的检查，只查图层。
     */
    fun inspect(
        definition: WatchmakerDefinition,
        archiveEntryNames: Collection<String> = emptyList(),
    ): List<WatchStoreUploadFinding> = buildList {
        val entries = archiveEntryNames.map { it.replace('\\', '/').lowercase(Locale.ROOT) }
        if (entries.isNotEmpty()) {
            val hasXml = entries.any { it == "watch.xml" || it == "watch.pxml" }
            if (!hasXml) add(WatchStoreUploadFinding.MissingFile("watch.xml"))
            if (entries.none { it == "preview.jpg" }) {
                add(WatchStoreUploadFinding.MissingFile("preview.jpg"))
            }
            entries.forEach { entry ->
                val extension = entry.substringAfterLast('.', "")
                if (extension in blockedExtensions) {
                    add(WatchStoreUploadFinding.BlockedFile(entry))
                }
            }
        }
        val isFormat1 = definition.version == WatchmakerDefinitionVersion.FORMAT_1
        definition.layers.forEachIndexed { index, layer ->
            val type = layer.type.trim().lowercase(Locale.ROOT)
            val position = index + 1
            if (type.isEmpty()) return@forEachIndexed
            if (type !in knownLayerTypes) {
                add(WatchStoreUploadFinding.UnknownLayerType(position, layer.type))
                return@forEachIndexed
            }
            if (isFormat1 && type in format2OnlyLayerTypes) {
                add(WatchStoreUploadFinding.Format2LayerInFormat1(position, type))
            }
            requiredAttributes[type]?.forEach { attribute ->
                // 大小写不敏感地找：规范里 compField 是驼峰，而表盘作者手写时大小写不一。
                val value = layer.attributes.entries
                    .firstOrNull { it.key.equals(attribute, ignoreCase = true) }
                    ?.value
                if (value.isNullOrBlank()) {
                    add(WatchStoreUploadFinding.MissingAttribute(position, type, attribute))
                }
            }
        }
    }
}

/** 一条本地预检结论。措辞与上游 `watchParser.js` 的 `validation` / `security` 对应。 */
sealed interface WatchStoreUploadFinding {
    /** 致命问题（上游归进 security，直接 reject）。 */
    val fatal: Boolean get() = this is BlockedFile

    data class MissingFile(val name: String) : WatchStoreUploadFinding

    data class BlockedFile(val name: String) : WatchStoreUploadFinding

    data class UnknownLayerType(val layerPosition: Int, val type: String) : WatchStoreUploadFinding

    data class Format2LayerInFormat1(
        val layerPosition: Int,
        val type: String,
    ) : WatchStoreUploadFinding

    data class MissingAttribute(
        val layerPosition: Int,
        val type: String,
        val attribute: String,
    ) : WatchStoreUploadFinding
}
