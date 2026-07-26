package com.flue.launcher.watchface.editor.conversion

import com.flue.launcher.watchface.standard.SecureWatchXmlParser
import com.flue.launcher.watchface.standard.WatchCompatibilityReport
import com.flue.launcher.watchface.standard.WatchmakerDefinitionParser
import com.flue.launcher.watchface.standard.WatchmakerProtection
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Input formats understood by the editor conversion seam.
 *
 * The converter intentionally owns format detection and conversion rather than
 * making the UI inspect archive entries. This keeps the editor independent from
 * HSC/WFF implementation details and lets us return an honest compatibility
 * report when a source contains a feature Flue cannot render yet.
 */
enum class WatchSourceFormat {
    WATCHMAKER_1,
    WATCHMAKER_2,
    HSC,
    WFF,
    UNKNOWN,
}

data class WatchConversionReport(
    val sourceFormat: WatchSourceFormat,
    val title: String,
    val convertedLayerCount: Int,
    val preservedEntryCount: Int,
    val unsupportedFeatures: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val fixes: List<String> = emptyList(),
    val compatibility: WatchCompatibilityReport? = null,
) {
    val isLossless: Boolean
        get() = unsupportedFeatures.isEmpty() && warnings.isEmpty()
}

sealed interface WatchConversionResult {
    data class Success(
        val outputFile: File,
        val report: WatchConversionReport,
    ) : WatchConversionResult

    data class Failure(
        val report: WatchConversionReport,
        val message: String,
    ) : WatchConversionResult
}

data class WatchFormatProbe(
    val format: WatchSourceFormat,
    val title: String?,
    val entryNames: List<String>,
    val protectedDefinition: Boolean,
)

/**
 * WFTools-compatible conversion boundary.
 *
 * Capability baseline is WFTools 4c61b943. The implementation deliberately
 * keeps a small, dependency-free core suitable for old Wear devices:
 *  - HSC dial.json image/text/number/progress layers become WatchMaker layers.
 *  - WFF declarative XML image/text/arc/progress nodes are converted.
 *  - Studio 2.0 WatchMaker groups/themes are flattened to format 1.0 while
 *    unknown XML attributes and nodes remain in the DOM.
 *
 * Unsupported source features are reported and never silently represented as a
 * fake layer. A conversion with no renderable layers fails.
 */
class WatchFormatConverter(
    private val maximumEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maximumEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val maximumTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    fun probe(source: File): WatchFormatProbe {
        require(source.isFile) { "源文件不存在：${source.name}" }
        val extracted = extract(source)
        return try {
            detect(extracted.root)
        } finally {
            extracted.delete()
        }
    }

    fun convert(source: File, output: File): WatchConversionResult {
        require(source.isFile) { "源文件不存在：${source.name}" }
        val extracted = try {
            extract(source)
        } catch (error: Exception) {
            return WatchConversionResult.Failure(
                report = WatchConversionReport(
                    sourceFormat = WatchSourceFormat.UNKNOWN,
                    title = source.nameWithoutExtension,
                    convertedLayerCount = 0,
                    preservedEntryCount = 0,
                    unsupportedFeatures = listOf("archive"),
                ),
                message = "无法读取源文件：${error.message ?: error.javaClass.simpleName}",
            )
        }
        return try {
            val probe = detect(extracted.root)
            when (probe.format) {
                WatchSourceFormat.HSC -> convertHsc(extracted, output, probe)
                WatchSourceFormat.WFF -> convertWff(extracted, output, probe)
                WatchSourceFormat.WATCHMAKER_2 -> flattenWatchmaker(extracted, output, probe)
                WatchSourceFormat.WATCHMAKER_1 -> {
                    // A 1.0 archive already is Flue's canonical input format.
                    copyFileAtomically(source, output)
                    WatchConversionResult.Success(
                        outputFile = output,
                        report = WatchConversionReport(
                            sourceFormat = probe.format,
                            title = probe.title.orEmpty().ifBlank { source.nameWithoutExtension },
                            convertedLayerCount = 0,
                            preservedEntryCount = probe.entryNames.size,
                            fixes = listOf("源文件已经是 WatchMaker 1.0，无需转换"),
                        ),
                    )
                }
                WatchSourceFormat.UNKNOWN -> WatchConversionResult.Failure(
                    report = WatchConversionReport(
                        sourceFormat = WatchSourceFormat.UNKNOWN,
                        title = probe.title.orEmpty().ifBlank { source.nameWithoutExtension },
                        convertedLayerCount = 0,
                        preservedEntryCount = probe.entryNames.size,
                        unsupportedFeatures = listOf("unknown-format"),
                    ),
                    message = "无法识别文件格式。支持 .watch、HSC dial.json 和 WFF watchface.xml。",
                )
            }
        } catch (error: Exception) {
            WatchConversionResult.Failure(
                report = WatchConversionReport(
                    sourceFormat = runCatching { detect(extracted.root).format }
                        .getOrDefault(WatchSourceFormat.UNKNOWN),
                    title = source.nameWithoutExtension,
                    convertedLayerCount = 0,
                    preservedEntryCount = extracted.entryNames.size,
                    unsupportedFeatures = listOf("conversion-error"),
                ),
                message = error.message ?: "转换失败",
            )
        } finally {
            extracted.delete()
        }
    }

    private fun detect(root: File): WatchFormatProbe {
        val names = root.walkTopDown().filter(File::isFile)
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        val watchXml = names.firstOrNull { it.substringAfterLast('/').equals("watch.xml", true) }
        val watchPxml = names.firstOrNull { it.substringAfterLast('/').equals("watch.pxml", true) }
        val dial = names.firstOrNull { it.substringAfterLast('/').equals("dial.json", true) }
        val wff = names.firstOrNull {
            it.substringAfterLast('/').equals("watchface.xml", true) ||
                it.substringAfterLast('/').equals("watch_face.xml", true)
        }
        if (dial != null) {
            val json = File(root, dial).readText(StandardCharsets.UTF_8)
            val title = runCatching {
                JSONObject(json).optStringAny("title", "name", "dialName")
            }.getOrNull()
            return WatchFormatProbe(WatchSourceFormat.HSC, title, names, false)
        }
        if (wff != null) {
            val title = runCatching {
                val document = parseXml(File(root, wff))
                document.documentElement.getAttribute("name").ifBlank {
                    document.documentElement.getAttribute("title")
                }
            }.getOrNull()
            return WatchFormatProbe(WatchSourceFormat.WFF, title, names, false)
        }
        if (watchXml != null || watchPxml != null) {
            val xmlFile = File(root, watchXml ?: "watch.xml")
            val decoded = if (watchPxml != null) {
                File(root, ".flue-probe-watch.xml").also { destination ->
                    WatchmakerProtection.decodeXmlToFile(
                        File(root, watchPxml),
                        destination,
                        MAXIMUM_XML_BYTES,
                    )
                }
            } else {
                xmlFile
            }
            return try {
                val document = parseXml(decoded)
                val attrs = document.documentElement.attributes
                val title = document.documentElement.getAttribute("name")
                val studio = attrs?.getNamedItem("build_source")
                    ?.nodeValue.equals("studio", ignoreCase = true)
                WatchFormatProbe(
                    format = if (studio) WatchSourceFormat.WATCHMAKER_2 else WatchSourceFormat.WATCHMAKER_1,
                    title = title,
                    entryNames = names,
                    protectedDefinition = watchPxml != null,
                )
            } finally {
                if (decoded != xmlFile) decoded.delete()
            }
        }
        return WatchFormatProbe(WatchSourceFormat.UNKNOWN, null, names, false)
    }

    private fun convertHsc(
        extracted: ExtractedArchive,
        output: File,
        probe: WatchFormatProbe,
    ): WatchConversionResult {
        val dialPath = probe.entryNames.first {
            it.substringAfterLast('/').equals("dial.json", true)
        }
        val rootJson = JSONObject(File(extracted.root, dialPath).readText(StandardCharsets.UTF_8))
        val drawpoints = rootJson.optJSONArrayAny("drawpoints", "drawPoints", "layers")
        val warnings = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        val fixes = mutableListOf<String>()
        val title = rootJson.optStringAny("title", "name", "dialName").ifBlank {
            "HSC Converted"
        }
        val width = (rootJson.optIntAny("width", "canvasWidth", "screenWidth") ?: 512)
            .coerceAtLeast(1)
        val height = (rootJson.optIntAny("height", "canvasHeight", "screenHeight") ?: 512)
            .coerceAtLeast(1)
        val destinationRoot = temporaryOutputRoot(output)
        val imagesDir = File(destinationRoot, "images").also { it.mkdirs() }
        val fontsDir = File(destinationRoot, "fonts").also { it.mkdirs() }
        val document = newWatchDocument(
            title = title,
            shape = if (width == height) "R" else "S",
            width = width,
            height = height,
        )
        val watch = document.documentElement
        var converted = 0
        if (drawpoints == null) {
            destinationRoot.deleteRecursively()
            return WatchConversionResult.Failure(
                report = WatchConversionReport(
                    sourceFormat = WatchSourceFormat.HSC,
                    title = title,
                    convertedLayerCount = 0,
                    preservedEntryCount = extracted.entryNames.size,
                    unsupportedFeatures = listOf("drawpoints"),
                ),
                message = "HSC dial.json 缺少 drawpoints 图层数组",
            )
        }
        for (index in 0 until drawpoints.length()) {
            val point = drawpoints.optJSONObject(index) ?: continue
            val type = point.optStringAny("pointType", "type", "kind").ifBlank { "UNKNOWN" }
            val normalized = type.uppercase(Locale.ROOT)
            val layer = when {
                normalized.startsWith("TEXT") || normalized.startsWith("TIME") ||
                    normalized.startsWith("DATE") || normalized.startsWith("WEEK") ||
                    normalized.contains("NUM") ->
                    appendHscText(document, point, width, height, normalized, warnings)
                normalized.contains("PICTURE") || normalized.contains("PICTRUE") ||
                    normalized.contains("IMAGE") ->
                    appendHscImage(
                        extracted.root,
                        imagesDir,
                        document,
                        point,
                        width,
                        height,
                        warnings,
                    )
                normalized.contains("PROGRESS") || normalized.contains("PROCESS") ->
                    appendHscProgress(document, point, width, height, warnings)
                else -> null
            }
            if (layer != null) {
                converted++
            } else {
                unsupported += type
            }
        }
        copyHscFontsAndPreview(extracted.root, destinationRoot, fontsDir)
        if (converted == 0) {
            destinationRoot.deleteRecursively()
            return WatchConversionResult.Failure(
                report = WatchConversionReport(
                    sourceFormat = WatchSourceFormat.HSC,
                    title = title,
                    convertedLayerCount = 0,
                    preservedEntryCount = extracted.entryNames.size,
                    unsupportedFeatures = unsupported.distinct().ifEmpty { listOf("no-renderable-layer") },
                    warnings = warnings,
                ),
                message = "HSC 没有可转换的图层；未生成伪造表盘。",
            )
        }
        if (unsupported.isNotEmpty()) {
            warnings += "有 ${unsupported.distinct().size} 种 HSC 图层未实现，已列入兼容报告"
        }
        val xml = serializeDocument(document)
        File(destinationRoot, "watch.xml").writeBytes(xml)
        val report = compatibilityReport(File(destinationRoot, "watch.xml"), WatchSourceFormat.HSC, title)
            .copy(
                convertedLayerCount = converted,
                preservedEntryCount = extracted.entryNames.size,
                unsupportedFeatures = unsupported.distinct(),
                warnings = warnings.distinct(),
                fixes = fixes,
            )
        return commitArchive(destinationRoot, output, report)
    }

    private fun convertWff(
        extracted: ExtractedArchive,
        output: File,
        probe: WatchFormatProbe,
    ): WatchConversionResult {
        val sourceName = probe.entryNames.first {
            it.substringAfterLast('/').equals("watchface.xml", true) ||
                it.substringAfterLast('/').equals("watch_face.xml", true)
        }
        val source = File(extracted.root, sourceName)
        val sourceDocument = parseXml(source)
        val rootElement = sourceDocument.documentElement
        val title = probe.title.orEmpty().ifBlank { "WFF Converted" }
        val destinationRoot = temporaryOutputRoot(output)
        val imagesDir = File(destinationRoot, "images").also { it.mkdirs() }
        val document = newWatchDocument(
            title = title,
            shape = rootElement.getAttribute("shape").ifBlank { "R" },
            width = rootElement.getAttribute("width").toIntOrNull() ?: 512,
            height = rootElement.getAttribute("height").toIntOrNull() ?: 512,
        )
        val warnings = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        val layerNodes = rootElement.childNodes.asSequenceElements()
            .flatMap { child ->
                if (child.tagName.equals("layers", true) || child.tagName.equals("scene", true)) {
                    child.childNodes.asSequenceElements().asIterable()
                } else listOf(child)
            }
            .toList()
        var converted = 0
        layerNodes.forEach { node ->
            val type = node.tagName.lowercase(Locale.ROOT)
            val layer = when {
                type in setOf("image", "bitmap", "picture") ->
                    appendWffImage(extracted.root, imagesDir, document, node, warnings)
                type in setOf("text", "label", "textview") ->
                    appendWffText(document, node)
                type in setOf("arc", "progress", "ring") ->
                    appendWffProgress(document, node)
                else -> null
            }
            if (layer != null) converted++ else unsupported += node.tagName
        }
        copyMatchingAssets(extracted.root, destinationRoot)
        if (converted == 0) {
            destinationRoot.deleteRecursively()
            return WatchConversionResult.Failure(
                report = WatchConversionReport(
                    sourceFormat = WatchSourceFormat.WFF,
                    title = title,
                    convertedLayerCount = 0,
                    preservedEntryCount = extracted.entryNames.size,
                    unsupportedFeatures = unsupported.distinct().ifEmpty { listOf("no-renderable-layer") },
                ),
                message = "WFF 没有可转换的 image/text/progress 图层。",
            )
        }
        File(destinationRoot, "watch.xml").writeBytes(serializeDocument(document))
        val report = compatibilityReport(File(destinationRoot, "watch.xml"), WatchSourceFormat.WFF, title)
            .copy(
                convertedLayerCount = converted,
                preservedEntryCount = extracted.entryNames.size,
                unsupportedFeatures = unsupported.distinct(),
                warnings = warnings.distinct(),
            )
        return commitArchive(destinationRoot, output, report)
    }

    private fun flattenWatchmaker(
        extracted: ExtractedArchive,
        output: File,
        probe: WatchFormatProbe,
    ): WatchConversionResult {
        val xmlFile = extracted.root.walkTopDown().firstOrNull {
            it.isFile && it.name.equals("watch.xml", true)
        } ?: File(extracted.root, "watch.xml")
        val decoded = if (probe.protectedDefinition) {
            File(extracted.root, ".flue-flatten-watch.xml").also { target ->
                val pxml = extracted.root.walkTopDown().first {
                    it.isFile && it.name.equals("watch.pxml", true)
                }
                WatchmakerProtection.decodeXmlToFile(pxml, target, MAXIMUM_XML_BYTES)
            }
        } else xmlFile
        val document = parseXml(decoded)
        val root = document.documentElement
        val layerElements = root.childNodes.asSequenceElements()
            .filter { it.tagName.equals("Layer", true) }
            .toList()
        val byId = layerElements.mapNotNull { element ->
            element.getAttribute("id").takeIf(String::isNotBlank)?.let { it to element }
        }.toMap()
        val warnings = mutableListOf<String>()
        val fixes = mutableListOf<String>()
        val groups = layerElements.filter {
            it.getAttribute("type").equals("group", true)
        }.toSet()
        layerElements.forEach { layer ->
            val parents = collectParentElements(layer, byId, warnings)
            if (parents.isNotEmpty()) {
                addParentCoordinates(layer, parents)
            }
            listAttributes(layer) { value ->
                value.replaceThemeTokens(root)
            }
            layer.removeAttribute("id")
            layer.removeAttribute("name")
            layer.removeAttribute("parentId")
            when {
                layer.getAttribute("type").equals("gif", true) -> {
                    layer.setAttribute("type", "image_gif")
                    warnings += "GIF 帧未在 flatten 阶段重编码，保留原 path；请检查设备兼容性"
                }
                layer.getAttribute("type").equals("image_tap", true) -> {
                    layer.setAttribute("type", "image")
                    layer.removeAttribute("paths")
                    warnings += "image_tap 已降级为静态 image 首帧"
                }
                layer.getAttribute("type").equals("shape", true) &&
                    layer.getAttribute("path").isNotBlank() -> {
                    layer.setAttribute("type", "image")
                    warnings += "带 path 的 shape 未做矢量栅格化，保留 path 并标记兼容风险"
                }
            }
        }
        layerElements.filter { it in groups }.forEach { it.parentNode?.removeChild(it) }
        root.removeAttribute("build_source")
        val themeSets = root.childNodes.asSequenceElements()
            .filter { it.tagName.equals("ThemeSets", true) }
            .toList()
        val theme = themeSets.firstOrNull()?.childNodes?.asSequenceElements()?.firstOrNull()
        theme?.getAttribute("ucolor")?.takeIf(String::isNotBlank)?.let {
            root.setAttribute("ucolor_default", it)
        }
        themeSets.forEach { it.parentNode?.removeChild(it) }
        if (groups.isNotEmpty()) fixes += "已将 ${groups.size} 个 Studio group 展平为 1.0 图层坐标"
        val destinationRoot = temporaryOutputRoot(output)
        copyMatchingAssets(extracted.root, destinationRoot) {
            val name = it.name.lowercase(Locale.ROOT)
            name != "watch.xml" && name != "watch.pxml"
        }
        File(destinationRoot, "watch.xml").writeBytes(serializeDocument(document))
        decoded.takeIf { it != xmlFile }?.delete()
        val parsed = WatchmakerDefinitionParser().parse(File(destinationRoot, "watch.xml"))
        val report = WatchConversionReport(
            sourceFormat = WatchSourceFormat.WATCHMAKER_2,
            title = probe.title.orEmpty().ifBlank { "Flattened Watch" },
            convertedLayerCount = parsed.layers.size,
            preservedEntryCount = extracted.entryNames.size,
            unsupportedFeatures = parsed.compatibilityReport.unsupportedLayerTypes.toList(),
            warnings = warnings.distinct(),
            fixes = fixes,
            compatibility = parsed.compatibilityReport,
        )
        return commitArchive(destinationRoot, output, report)
    }

    private fun appendHscText(
        document: Document,
        point: JSONObject,
        width: Int,
        height: Int,
        type: String,
        warnings: MutableList<String>,
    ): Element {
        val layer = document.createElement("Layer")
        layer.setAttribute("type", "text")
        val x = point.optDoubleAny("startX", "x") ?: 0.0
        val y = point.optDoubleAny("startY", "y") ?: 0.0
        layer.setAttribute("x", fmt(x - width / 2.0))
        layer.setAttribute("y", fmt(y - height / 2.0))
        layer.setAttribute("text", hscTextExpression(type, point))
        val font = point.optJSONObject("font")
        val size = font?.optDoubleAny("textSize", "size") ?: point.optDoubleAny("textSize", "size")
        if (size != null && size > 0) layer.setAttribute("text_size", fmt(size))
        val color = font?.optIntAny("color") ?: point.optIntAny("color")
        if (color != null) layer.setAttribute("color", colorToHex(color))
        val alpha = point.optIntAny("alpha")
        if (alpha != null && alpha >= 0) layer.setAttribute("opacity", ((alpha * 100) / 255).coerceIn(0, 100).toString())
        document.documentElement.appendChild(layer)
        if (type !in SUPPORTED_HSC_TEXT_TYPES) warnings += "HSC 文本类型 $type 使用近似 WatchMaker tag"
        return layer
    }

    private fun appendHscImage(
        sourceRoot: File,
        imagesDir: File,
        document: Document,
        point: JSONObject,
        width: Int,
        height: Int,
        warnings: MutableList<String>,
    ): Element? {
        val paths = point.optJSONArrayAny("pices", "pieces", "paths")
        val raw = paths?.optString(0).orEmpty().ifBlank {
            point.optStringAny("path", "src", "image")
        }
        if (raw.isBlank()) {
            warnings += "HSC 图片图层缺少 pices/path"
            return null
        }
        val source = findAsset(sourceRoot, raw) ?: run {
            warnings += "HSC 图片资源不存在：$raw"
            return null
        }
        val targetName = uniqueName(imagesDir, source.name)
        source.copyTo(File(imagesDir, targetName), overwrite = true)
        val layer = document.createElement("Layer")
        layer.setAttribute("type", if ((paths?.length() ?: 0) > 1) "image_gif" else "image")
        layer.setAttribute("path", "images/$targetName")
        layer.setAttribute(
            "x",
            fmt((point.optDoubleAny("startX", "x") ?: 0.0) - width / 2.0),
        )
        layer.setAttribute(
            "y",
            fmt((point.optDoubleAny("startY", "y") ?: 0.0) - height / 2.0),
        )
        point.optDoubleAny("scale")?.takeIf { it > 0 }?.let { layer.setAttribute("scale", fmt(it)) }
        point.optIntAny("rotate")?.let { layer.setAttribute("rotation", it.toString()) }
        point.optIntAny("alpha")?.takeIf { it >= 0 }?.let {
            layer.setAttribute("opacity", ((it * 100) / 255).coerceIn(0, 100).toString())
        }
        if ((paths?.length() ?: 0) > 1) {
            warnings += "HSC 多帧图片 $raw 仅保留首帧；完整帧展开需要 WFTools bitmap pipeline"
        }
        document.documentElement.appendChild(layer)
        return layer
    }

    private fun appendHscProgress(
        document: Document,
        point: JSONObject,
        width: Int,
        height: Int,
        warnings: MutableList<String>,
    ): Element {
        val layer = document.createElement("Layer")
        layer.setAttribute("type", "progress")
        layer.setAttribute(
            "x",
            fmt((point.optDoubleAny("startX", "x") ?: 0.0) - width / 2.0),
        )
        layer.setAttribute(
            "y",
            fmt((point.optDoubleAny("startY", "y") ?: 0.0) - height / 2.0),
        )
        layer.setAttribute("value", point.optStringAny("dataFormat", "startValue").ifBlank { "50" })
        point.optIntAny("processWidth", "width")?.let { layer.setAttribute("width", it.toString()) }
        point.optDoubleAny("fromDegrees")?.let { layer.setAttribute("from", fmt(it)) }
        point.optDoubleAny("toDegrees")?.let { layer.setAttribute("to", fmt(it)) }
        point.optIntAny("color")?.let { layer.setAttribute("color", colorToHex(it)) }
        document.documentElement.appendChild(layer)
        if (point.optStringAny("processType").isNotBlank()) {
            warnings += "HSC progress processType 使用原始值，设备 renderer 可能需要兼容映射"
        }
        return layer
    }

    private fun appendWffImage(
        sourceRoot: File,
        imagesDir: File,
        document: Document,
        source: Element,
        warnings: MutableList<String>,
    ): Element? {
        val raw = source.getAttribute("src").ifBlank {
            source.getAttribute("path").ifBlank { source.getAttribute("resource") }
        }
        if (raw.isBlank()) {
            warnings += "WFF image 缺少 src/path"
            return null
        }
        val asset = findAsset(sourceRoot, raw) ?: run {
            warnings += "WFF 图片资源不存在：$raw"
            return null
        }
        val targetName = uniqueName(imagesDir, asset.name)
        asset.copyTo(File(imagesDir, targetName), overwrite = true)
        val layer = document.createElement("Layer")
        layer.setAttribute("type", "image")
        layer.setAttribute("path", "images/$targetName")
        copyIfPresent(source, layer, "x", "y", "width", "height", "rotation", "opacity", "color")
        document.documentElement.appendChild(layer)
        return layer
    }

    private fun appendWffText(document: Document, source: Element): Element {
        val layer = document.createElement("Layer")
        layer.setAttribute("type", "text")
        val text = source.getAttribute("text").ifBlank {
            source.getAttribute("value").ifBlank { source.textContent.orEmpty().trim() }
        }
        layer.setAttribute("text", text)
        copyIfPresent(source, layer, "x", "y", "width", "height", "rotation", "opacity", "color")
        source.getAttribute("size").takeIf(String::isNotBlank)?.let { layer.setAttribute("text_size", it) }
        document.documentElement.appendChild(layer)
        return layer
    }

    private fun appendWffProgress(document: Document, source: Element): Element {
        val layer = document.createElement("Layer")
        layer.setAttribute("type", if (source.tagName.equals("ring", true)) "ring" else "progress")
        copyIfPresent(source, layer, "x", "y", "width", "height", "rotation", "opacity", "color", "radius", "value")
        document.documentElement.appendChild(layer)
        return layer
    }

    private fun copyHscFontsAndPreview(sourceRoot: File, destinationRoot: File, fontsDir: File) {
        sourceRoot.walkTopDown().filter { it.isFile }.forEach { source ->
            when {
                source.extension.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc") -> {
                    val target = File(fontsDir, uniqueName(fontsDir, source.name))
                    source.copyTo(target, overwrite = true)
                }
                source.name.equals("preview.jpg", true) || source.name.equals("preview.png", true) -> {
                    source.copyTo(File(destinationRoot, "preview.jpg"), overwrite = true)
                }
            }
        }
    }

    private fun compatibilityReport(
        xml: File,
        sourceFormat: WatchSourceFormat,
        title: String,
    ): WatchConversionReport {
        val parsed = WatchmakerDefinitionParser().parse(xml)
        return WatchConversionReport(
            sourceFormat = sourceFormat,
            title = title,
            convertedLayerCount = parsed.layers.size,
            preservedEntryCount = 0,
            unsupportedFeatures = parsed.compatibilityReport.unsupportedLayerTypes.toList(),
            compatibility = parsed.compatibilityReport,
        )
    }

    private fun commitArchive(
        sourceRoot: File,
        output: File,
        report: WatchConversionReport,
    ): WatchConversionResult {
        output.parentFile?.mkdirs()
        val temporary = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.part")
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                sourceRoot.walkTopDown().filter(File::isFile).forEach { file ->
                    val name = file.relativeTo(sourceRoot).invariantSeparatorsPath
                    if (name.startsWith(".flue/") || name.startsWith(".")) return@forEach
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            copyFileAtomically(temporary, output)
            return WatchConversionResult.Success(output, report)
        } catch (error: Exception) {
            temporary.delete()
            return WatchConversionResult.Failure(report, "无法写入转换结果：${error.message ?: "I/O error"}")
        } finally {
            sourceRoot.deleteRecursively()
        }
    }

    private fun copyMatchingAssets(
        sourceRoot: File,
        destinationRoot: File,
        predicate: (File) -> Boolean = { true },
    ) {
        sourceRoot.walkTopDown().filter { it.isFile && predicate(it) }.forEach { source ->
            val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
            if (relative.startsWith(".") || relative.equals("dial.json", true)) return@forEach
            val target = File(destinationRoot, relative)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    private fun extract(source: File): ExtractedArchive {
        val root = File.createTempFile("flue-converter-", "").also {
            it.delete()
            it.mkdirs()
        }
        val names = mutableListOf<String>()
        var total = 0L
        var count = 0
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(source))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalized = normalizeEntry(entry.name)
                    if (!names.add(normalized)) throw IOException("压缩包包含重复路径：$normalized")
                    if (++count > maximumEntries) throw IOException("压缩包图层资源过多")
                    val target = File(root, normalized)
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    var written = 0L
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            written += read
                            total += read
                            if (written > maximumEntryBytes || total > maximumTotalBytes) {
                                throw IOException("源压缩包资源超过安全大小限制")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            return ExtractedArchive(root, names)
        } catch (error: Throwable) {
            // No ExtractedArchive reaches the caller on failure, therefore
            // clean the partially unpacked tree here instead of leaking it.
            runCatching { root.deleteRecursively() }
            throw error
        }
    }

    private fun normalizeEntry(raw: String): String {
        val normalized = raw.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') &&
            !normalized.matches(Regex("^[A-Za-z]:.*"))) { "压缩包路径非法：$raw" }
        val parts = normalized.split('/')
        require(parts.none { it == ".." }) { "压缩包路径穿越：$raw" }
        return parts.filter { it.isNotBlank() && it != "." }.joinToString("/")
            .also { require(it.isNotBlank()) { "压缩包包含空路径" } }
    }

    private fun temporaryOutputRoot(output: File): File =
        File(output.parentFile ?: output.absoluteFile.parentFile, ".flue-convert-${UUID.randomUUID()}").also {
            it.mkdirs()
        }

    private fun newWatchDocument(title: String, shape: String, width: Int, height: Int): Document {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val document = factory.newDocumentBuilder().newDocument()
        val root = document.createElement("Watch")
        root.setAttribute("name", title)
        root.setAttribute("shape", shape)
        root.setAttribute("width", width.toString())
        root.setAttribute("height", height.toString())
        document.appendChild(root)
        return document
    }

    private fun parseXml(file: File): Document = SecureWatchXmlParser.parse(file)

    private fun serializeDocument(document: Document): ByteArray {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        return java.io.ByteArrayOutputStream().use { output ->
            transformer.transform(DOMSource(document), StreamResult(output))
            output.toByteArray()
        }
    }

    /**
     * Walk a Studio parent chain defensively.  Imported XML is not guaranteed
     * to be a tree: a self-parent or a cycle must not spin the editor forever.
     * We keep the safe prefix and report the truncation in the conversion
     * compatibility report.
     */
    private fun collectParentElements(
        layer: Element,
        byId: Map<String, Element>,
        warnings: MutableList<String>,
    ): List<Element> {
        val parents = mutableListOf<Element>()
        val visited = HashSet<String>()
        layer.getAttribute("id").trim().takeIf(String::isNotBlank)?.let(visited::add)
        var parentId = layer.getAttribute("parentId")
            .trim()
            .takeIf(String::isNotBlank)
        var depth = 0
        while (parentId != null && depth < MAX_PARENT_DEPTH) {
            val id = parentId
            if (!visited.add(id)) {
                warnings += "检测到循环 parentId=$id，已截断 Studio group 坐标链"
                break
            }
            val parent = byId[id]
            if (parent == null) {
                warnings += "parentId=$id 找不到对应图层，已忽略剩余父级"
                break
            }
            parents += parent
            parentId = parent.getAttribute("parentId")
                .trim()
                .takeIf(String::isNotBlank)
            depth++
        }
        if (parentId != null && depth >= MAX_PARENT_DEPTH) {
            warnings += "Studio group 父级深度超过 $MAX_PARENT_DEPTH，已截断坐标链"
        }
        return parents
    }

    private fun addParentCoordinates(layer: Element, parents: List<Element>) {
        val xs = listOf(layer.getAttribute("x")) + parents.map { it.getAttribute("x") }
        val ys = listOf(layer.getAttribute("y")) + parents.map { it.getAttribute("y") }
        addAttributeTerms(layer, "x", xs)
        addAttributeTerms(layer, "y", ys)
        val rotations = listOf(layer.getAttribute("rotation")) + parents.map { it.getAttribute("rotation") }
        addAttributeTerms(layer, "rotation", rotations)
    }

    private fun addAttributeTerms(layer: Element, name: String, values: List<String>) {
        val terms = values.map(String::trim).filter(String::isNotBlank)
        if (terms.isEmpty()) return
        val numbers = terms.map(String::toDoubleOrNull)
        layer.setAttribute(
            name,
            if (numbers.all { it != null }) fmt(numbers.filterNotNull().sum())
            else terms.joinToString(" + ") { "($it)" },
        )
    }

    private fun listAttributes(element: Element, transform: (String) -> String) {
        for (index in 0 until element.attributes.length) {
            val attribute = element.attributes.item(index)
            attribute.nodeValue = transform(attribute.nodeValue)
        }
        element.childNodes.asSequenceElements().forEach { child ->
            listAttributes(child, transform)
        }
    }

    private fun String.replaceThemeTokens(root: Element): String {
        var result = this
        val theme = root.childNodes.asSequenceElements()
            .firstOrNull { it.tagName.equals("ThemeSets", true) }
            ?.childNodes?.asSequenceElements()?.firstOrNull()
        theme?.getAttribute("ucolor2")?.takeIf(String::isNotBlank)?.let {
            result = result.replace("{ucolor2}", it)
        }
        theme?.getAttribute("ucolor3")?.takeIf(String::isNotBlank)?.let {
            result = result.replace("{ucolor3}", it)
        }
        return result
    }

    private fun copyIfPresent(source: Element, target: Element, vararg attributes: String) {
        attributes.forEach { name ->
            source.getAttribute(name).takeIf(String::isNotBlank)?.let { target.setAttribute(name, it) }
        }
    }

    /**
     * Resolve a format-provided asset only inside the extracted archive.
     *
     * Conversion output can be exported or installed by the caller, so a
     * `../` reference must never be allowed to copy an app-private file into
     * the resulting watch archive.  Canonical containment also rejects a
     * symlink planted in a malformed input directory.
     */
    private fun findAsset(root: File, rawPath: String): File? {
        val raw = rawPath.replace('\\', '/').trim()
        if (raw.isBlank()) return null
        val normalized = raw.trimStart('/')
        if (normalized.matches(Regex("^[A-Za-z]:.*")) ||
            normalized.split('/').any { it == ".." }
        ) {
            return null
        }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val direct = runCatching { File(canonicalRoot, normalized).canonicalFile }.getOrNull()
            ?: return null
        if (!direct.isDescendantOf(canonicalRoot)) return null
        if (direct.isFile) return direct
        val name = normalized.substringAfterLast('/')
        if (name.isBlank()) return null
        return canonicalRoot.walkTopDown().firstOrNull {
            it.isFile &&
                it.name.equals(name, true) &&
                runCatching { it.canonicalFile.isDescendantOf(canonicalRoot) }.getOrDefault(false)
        }
    }

    private fun uniqueName(directory: File, requested: String): String {
        val safe = requested.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "asset.bin" }
        var candidate = safe
        var index = 1
        while (File(directory, candidate).exists()) {
            val dot = safe.lastIndexOf('.')
            candidate = if (dot > 0) "${safe.substring(0, dot)}_$index${safe.substring(dot)}"
            else "${safe}_$index"
            index++
        }
        return candidate
    }

    private fun File.isDescendantOf(root: File): Boolean {
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        val candidatePath = canonicalPath
        return candidatePath == root.canonicalPath || candidatePath.startsWith(rootPath)
    }

    private fun copyFileAtomically(source: File, target: File) {
        if (source.canonicalFile == target.canonicalFile) return
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        source.copyTo(temporary, overwrite = true)
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "无法提交转换文件" }
        }
    }

    private data class ExtractedArchive(val root: File, val entryNames: List<String>) {
        fun delete() {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 1024
        const val DEFAULT_MAX_ENTRY_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_MAX_TOTAL_BYTES = 192L * 1024L * 1024L
        const val MAXIMUM_XML_BYTES = 24L * 1024L * 1024L
        const val MAX_PARENT_DEPTH = 64
        val SUPPORTED_HSC_TEXT_TYPES = setOf(
            "TEXT_NORMAL", "TEXT_WEEK", "TEXT_WEEKX", "TEXT_ROLL",
            "TIME_HH", "TIME_HHMM", "TIME_HHMMSS", "TIME_MM", "TIME_SS",
        )
    }
}

private fun JSONObject.optStringAny(vararg keys: String): String =
    keys.asSequence().map { optString(it, "") }.firstOrNull { it.isNotBlank() }.orEmpty()

private fun JSONObject.optIntAny(vararg keys: String): Int? =
    keys.asSequence().mapNotNull { key ->
        if (!has(key) || isNull(key)) null else optInt(key, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }
    }.firstOrNull()

private fun JSONObject.optDoubleAny(vararg keys: String): Double? =
    keys.asSequence().mapNotNull { key ->
        if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeUnless { it.isNaN() }
    }.firstOrNull()

private fun JSONObject.optJSONArrayAny(vararg keys: String): JSONArray? =
    keys.asSequence().mapNotNull { optJSONArray(it) }.firstOrNull()

private fun JSONArray.optString(index: Int): String =
    if (index in 0 until length()) optString(index, "") else ""

private fun Sequence<Element>.asIterable(): List<Element> = toList()

private class NodeListSequence(private val nodeList: org.w3c.dom.NodeList) : Sequence<Element> {
    override fun iterator(): Iterator<Element> = object : Iterator<Element> {
        private var index = 0
        private var next: Element? = advance()
        private fun advance(): Element? {
            while (index < nodeList.length) {
                val node = nodeList.item(index++)
                if (node.nodeType == Node.ELEMENT_NODE) return node as Element
            }
            return null
        }
        override fun hasNext(): Boolean = next != null
        override fun next(): Element {
            val value = next ?: throw NoSuchElementException()
            next = advance()
            return value
        }
    }
}

private fun org.w3c.dom.NodeList.asSequenceElements(): Sequence<Element> = NodeListSequence(this)

private fun colorToHex(value: Int): String = "#%08X".format(Locale.ROOT, value)

private fun fmt(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.US, "%.2f", value)

private fun hscTextExpression(type: String, point: JSONObject): String {
    val raw = point.optStringAny("text", "startValue", "nextText")
    return when {
        "HH_A" in type || "TIME_HH_A" in type -> "math.floor({dh23}/10)"
        "HH_B" in type || "TIME_HH_B" in type -> "{dh23}%10"
        "MINUTE_A" in type || "MM_A" in type -> "math.floor({dm}/10)"
        "MINUTE_B" in type || "MM_B" in type -> "{dm}%10"
        "SECOND_A" in type || "SS_A" in type -> "math.floor({ds}/10)"
        "SECOND_B" in type || "SS_B" in type -> "{ds}%10"
        "TIME_HHMMSS" in type -> "'{dh23z}:{dmz}:{dsz}'"
        "TIME_HHMM" in type -> "'{dh23z}:{dmz}'"
        "TIME_HH" in type -> "'{dh23z}'"
        "TIME_MM" in type || "TIME_MINUTE" in type -> "'{dmz}'"
        "TIME_SS" in type || "TIME_SECOND" in type -> "'{dsz}'"
        "DATE_CHINA" in type -> "'{dn}月{dd}日'"
        "DATE" in type -> "'{dd}/{dn}/{dyy}'"
        "WEEK" in type -> "'{ddw}'"
        "HEARTRATE" in type -> "'{shr}'"
        "STEP" in type -> "'{ssc}'"
        "CHARGE" in type -> "'{bl}%'"
        "TEMPERATURE" in type || "WEATHER" in type -> "'{wt}'"
        "SIGNAL" in type -> "math.floor({ncs}*100)"
        else -> raw
    }
}
