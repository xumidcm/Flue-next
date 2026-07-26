package com.flue.launcher.watchface.standard

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.util.Locale

enum class WatchmakerDefinitionVersion {
    FORMAT_1,
    FORMAT_2,
}

data class WatchmakerXmlElement(
    val name: String,
    val attributes: Map<String, String>,
    val text: String?,
    val children: List<WatchmakerXmlElement>,
)

data class WatchmakerTheme(
    val name: String,
    val attributes: Map<String, String>,
)

data class WatchmakerLayer(
    val id: String?,
    val type: String,
    val parentId: String?,
    val attributes: Map<String, String>,
    val children: List<WatchmakerXmlElement>,
) {
    val isGroup: Boolean get() = type.equals("group", ignoreCase = true)
}

data class WatchmakerDefinition(
    val version: WatchmakerDefinitionVersion,
    val rootAttributes: Map<String, String>,
    val themes: List<WatchmakerTheme>,
    val layers: List<WatchmakerLayer>,
    val auxiliaryElements: List<WatchmakerXmlElement>,
) {
    val name: String get() = rootAttributes["name"].orEmpty()
    val shape: WatchFaceShape get() = watchFaceShape(rootAttributes["shape"])
    val rawShape: String? get() = rootAttributes["shape"]?.takeIf(String::isNotBlank)
    val designWidth: Int = 512
    val designHeight: Int = 512

    /** Unknown types are preserved in the IR instead of being silently dropped. */
    val unsupportedLayerTypes: Set<String> = layers
        .map { it.type }
        .filterNot { it.lowercase(Locale.ROOT) in SUPPORTED_LAYER_TYPES }
        .toSet()

    val compatibilityReport: WatchCompatibilityReport by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val values = buildList {
            addAll(rootAttributes.values)
            layers.forEach { layer ->
                addAll(layer.attributes.values)
                layer.children.collectAttributeValues(this)
            }
            auxiliaryElements.collectAttributeValues(this)
        }
        val unsupportedTags = values.asSequence()
            .flatMap { value -> DYNAMIC_TAG_PATTERN.findAll(value).map { it.groupValues[1] } }
            .filterNot(WatchmakerTagResolver::isKnown)
            .toSet()
        val unsupportedExpressions = values.asSequence()
            .filter(::containsUnsupportedExpression)
            .toSet()
        WatchCompatibilityReport(
            unsupportedLayerTypes = unsupportedLayerTypes,
            unsupportedTags = unsupportedTags,
            unsupportedExpressions = unsupportedExpressions,
        )
    }
}

/** Secure, lossless parser for Watchmaker 1.0 and Studio 2.0 watch.xml files. */
class WatchmakerDefinitionParser {
    fun parse(file: File): WatchmakerDefinition {
        val document = SecureWatchXmlParser.parse(file)
        return parseDocument(document)
    }

    fun parse(input: InputStream, maximumBytes: Long): WatchmakerDefinition {
        val document = SecureWatchXmlParser.parse(input, maximumBytes)
        return parseDocument(document)
    }

    private fun parseDocument(document: org.w3c.dom.Document): WatchmakerDefinition {
        val root = document.documentElement
            ?: throw IllegalArgumentException("watch.xml has no root element")
        require(root.tagName.equals("Watch", ignoreCase = true)) {
            "watch.xml root must be <Watch>"
        }
        val rootAttributes = root.attributes.asMap()
        val version = if (rootAttributes["build_source"]
                ?.equals("studio", ignoreCase = true) == true
        ) {
            WatchmakerDefinitionVersion.FORMAT_2
        } else {
            WatchmakerDefinitionVersion.FORMAT_1
        }
        val layers = mutableListOf<WatchmakerLayer>()
        val auxiliary = mutableListOf<WatchmakerXmlElement>()
        val themes = mutableListOf<WatchmakerTheme>()
        root.childElements().forEach { child ->
            when {
                child.tagName.equals("Layer", ignoreCase = true) -> child.collectLayers(layers)
                child.tagName.equals("ThemeSets", ignoreCase = true) -> {
                    child.childElements()
                        .filter { it.tagName.equals("ThemeSet", ignoreCase = true) }
                        .forEach { theme ->
                            themes += WatchmakerTheme(
                                name = theme.getAttribute("name").orEmpty(),
                                attributes = theme.attributes.asMap(),
                            )
                        }
                }
                else -> {
                    child.collectLayers(layers)
                    auxiliary += child.toModel()
                }
            }
        }
        return WatchmakerDefinition(
            version = version,
            rootAttributes = rootAttributes,
            themes = themes,
            layers = layers,
            auxiliaryElements = auxiliary,
        )
    }

}

private fun List<WatchmakerXmlElement>.collectAttributeValues(destination: MutableList<String>) {
    forEach { element ->
        destination += element.attributes.values
        element.text?.let(destination::add)
        element.children.collectAttributeValues(destination)
    }
}

private fun Element.toLayer(): WatchmakerLayer {
    val attributes = attributes.asMap()
    return WatchmakerLayer(
        id = attributes["id"],
        type = attributes["type"].orEmpty().ifBlank { "unknown" },
        parentId = attributes["parentId"],
        attributes = attributes,
        children = childElements().map { it.toModel() },
    )
}

/**
 * Studio exporters may wrap layers in `<Layers>`, folders or other extension
 * nodes. Treat every descendant `<Layer>` as a render layer while preserving
 * document order; malformed parent links are handled later by the tolerant
 * stable-id forest builder instead of rejecting the complete face.
 */
private fun Element.collectLayers(destination: MutableList<WatchmakerLayer>) {
    if (tagName.equals("Layer", ignoreCase = true)) destination += toLayer()
    childElements().forEach { child -> child.collectLayers(destination) }
}

private fun Element.toModel(): WatchmakerXmlElement = WatchmakerXmlElement(
    name = tagName,
    attributes = attributes.asMap(),
    text = textContent?.trim()?.ifBlank { null },
    children = childElements().map { it.toModel() },
)

private fun org.w3c.dom.NamedNodeMap.asMap(): Map<String, String> = buildMap {
    for (index in 0 until length) {
        val node = item(index)
        put(node.nodeName, node.nodeValue.orEmpty())
    }
}

private fun Element.childElements(): List<Element> {
    val result = ArrayList<Element>(childNodes.length)
    for (index in 0 until childNodes.length) {
        val node = childNodes.item(index)
        if (node.nodeType == Node.ELEMENT_NODE) result += node as Element
    }
    return result
}

private val SUPPORTED_LAYER_TYPES = setOf(
    "image",
    "text",
    "ring",
    "progress",
    "image_cond",
    "image_gif",
    "image_tap",
    "gif",
    "series",
    "calendar",
    "text_curved",
    "text_ring",
    "markers",
    "markers_hm",
    // WM_FORMAT_1.0/2.0 common data and vector layers.  `video` and `map`
    // intentionally stay out of this set: they require a MediaPlayer/live
    // map bridge and are reported as unsupported instead of being claimed as
    // fully rendered by the Canvas runtime.
    "chart",
    "svg",
    "shape",
    "rounded",
    "group",
)

private val DYNAMIC_TAG_PATTERN = Regex("\\{([^{}]+)}")

/**
 * Side-effect-free numeric/boolean expressions are handled by the runtime's
 * bounded evaluator. Functions, Lua/script actions and syntax outside that
 * grammar stay in the report instead of being rendered as literal strings.
 */
private fun containsUnsupportedExpression(value: String): Boolean {
    if (value.contains("lua:", ignoreCase = true) ||
        value.contains("script:", ignoreCase = true)
    ) {
        return true
    }
    val hasBoundedFunction = value.contains("string.", ignoreCase = true) ||
        value.contains("math.", ignoreCase = true) ||
        Regex("\\b(?:tonumber|tostring|type)\\s*\\(", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)
    val hasDynamicTag = DYNAMIC_TAG_PATTERN.containsMatchIn(value)
    val hasComparisonOrBoolean = Regex(
        "(?:==|~=|>=|<=|(?<![A-Za-z])>(?!=)|(?<![A-Za-z])<(?!=)|\\band\\b|\\bor\\b|\\bnot\\b)",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(value)
    val hasArithmetic = Regex("[+*/%^]").containsMatchIn(value) || value.contains("..") ||
        Regex("(?:}\\s*-\\s*(?:\\{|\\d)|(?:\\}|\\d)\\s*-\\s*\\{)")
            .containsMatchIn(value)
    val looksLikeExpression = hasBoundedFunction ||
        hasDynamicTag && (hasComparisonOrBoolean || hasArithmetic) ||
        (!hasDynamicTag && (hasComparisonOrBoolean || hasArithmetic) && value.contains("=="))
    return looksLikeExpression && !WatchmakerExpressionEvaluator.supports(value)
}
