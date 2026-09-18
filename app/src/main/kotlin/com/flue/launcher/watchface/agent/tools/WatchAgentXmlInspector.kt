package com.flue.launcher.watchface.agent.tools

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Compares obvious layer nodes in watch.xml with the host's parsed layer list. */
data class WatchAgentXmlInspection(
    val layersInXml: Int,
    val candidateNames: List<String>,
    val recognizedLayers: Int,
) {
    val parseStatus: String get() = if (layersInXml == recognizedLayers) "ok" else "mismatch"
}

object WatchAgentXmlInspector {
    private val candidateNames = setOf(
        "layer", "image", "text", "shape", "rounded", "progress", "ring", "text_curved",
        "markers", "markers_hm", "text_ring", "series", "calendar", "chart", "video", "map",
        "gallery_2d",
    )

    fun inspect(xml: String): WatchAgentXmlInspection {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isCoalescing = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            // Android's javax.xml stubs do not expose the Java SE
            // XMLConstants.ACCESS_EXTERNAL_* fields. Keep the standard URI
            // values here and tolerate parsers that do not implement the
            // optional properties; the entity/DTD features above remain the
            // mandatory XXE guard on those runtimes.
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            }
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
        }
        val root = factory.newDocumentBuilder().parse(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)),
        ).documentElement
        require(root.tagName.equals("Watch", true) || root.tagName.equals("watchface", true)) {
            "XML 根节点必须为 Watch 或 watchface"
        }
        val names = mutableListOf<String>()
        fun visit(element: Element) {
            if (element.tagName.lowercase() in candidateNames) names += element.tagName
            for (index in 0 until element.childNodes.length) {
                (element.childNodes.item(index) as? Element)?.let(::visit)
            }
        }
        visit(root)
        return WatchAgentXmlInspection(
            layersInXml = names.size,
            candidateNames = names,
            recognizedLayers = names.count { it.equals("Layer", true) },
        )
    }
}
