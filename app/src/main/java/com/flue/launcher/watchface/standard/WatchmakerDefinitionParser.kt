package com.flue.launcher.watchface.standard

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File

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
    val shape: String get() = rootAttributes["shape"].orEmpty().ifBlank { "R" }
    val designWidth: Int = 512
    val designHeight: Int = 512

    /** Unknown types are preserved in the IR instead of being silently dropped. */
    val unsupportedLayerTypes: Set<String> = layers
        .map { it.type }
        .filterNot { it.lowercase() in SUPPORTED_LAYER_TYPES }
        .toSet()
}

/** Secure, lossless parser for Watchmaker 1.0 and Studio 2.0 watch.xml files. */
class WatchmakerDefinitionParser {
    fun parse(file: File): WatchmakerDefinition {
        val document = SecureWatchXmlParser.parse(file)
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
                child.tagName.equals("Layer", ignoreCase = true) -> layers += child.toLayer()
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
                else -> auxiliary += child.toModel()
            }
        }
        validateLayerIdentity(version, layers)
        return WatchmakerDefinition(
            version = version,
            rootAttributes = rootAttributes,
            themes = themes,
            layers = layers,
            auxiliaryElements = auxiliary,
        )
    }

    private fun validateLayerIdentity(
        version: WatchmakerDefinitionVersion,
        layers: List<WatchmakerLayer>,
    ) {
        if (version == WatchmakerDefinitionVersion.FORMAT_1) return
        val ids = HashSet<String>()
        layers.forEach { layer ->
            layer.id?.takeIf(String::isNotBlank)?.let { id ->
                require(ids.add(id)) { "Duplicate Studio layer id: $id" }
            }
        }
        val knownIds = ids.toSet()
        layers.forEach { layer ->
            val parent = layer.parentId?.takeIf(String::isNotBlank) ?: return@forEach
            require(parent in knownIds) {
                "Studio layer ${layer.id ?: layer.type} references missing parent $parent"
            }
        }
        val parents = layers.associateNotNull { layer ->
            layer.id?.let { it to layer.parentId }
        }
        parents.keys.forEach { id ->
            val seen = HashSet<String>()
            var cursor: String? = id
            while (cursor != null) {
                require(seen.add(cursor)) { "Studio layer hierarchy contains a cycle at $cursor" }
                cursor = parents[cursor].orEmpty().ifBlank { null }
            }
        }
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

private fun <T, K, V> Iterable<T>.associateNotNull(transform: (T) -> Pair<K, V>?): Map<K, V> =
    buildMap {
        for (item in this@associateNotNull) transform(item)?.let { (key, value) -> put(key, value) }
    }

private val SUPPORTED_LAYER_TYPES = setOf(
    "image",
    "text",
    "ring",
    "progress",
    "image_cond",
    "chart",
    "text_curved",
    "text_ring",
    "markers",
    "markers_hm",
    "shape",
    "rounded",
    "image_gif",
    "video",
    "map",
    "series",
    "calendar",
    "group",
    "gif",
    "image_tap",
    "svg",
)
