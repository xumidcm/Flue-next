package com.flue.launcher.watchface.standard

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/** Test provider that models Android XML implementations without this optional feature. */
class UnsupportedDoctypeFeatureFactory : DocumentBuilderFactory() {
    private val delegate = createPlatformDelegate()

    override fun newDocumentBuilder(): DocumentBuilder = delegate.newDocumentBuilder()

    override fun setAttribute(name: String, value: Any?) {
        delegate.setAttribute(name, value)
    }

    override fun getAttribute(name: String): Any = delegate.getAttribute(name)

    override fun setFeature(name: String, value: Boolean) {
        if (name == DISALLOW_DOCTYPE_FEATURE) {
            throw ParserConfigurationException("Feature is not supported by this provider")
        }
        delegate.setFeature(name, value)
    }

    override fun getFeature(name: String): Boolean = delegate.getFeature(name)

    override fun setNamespaceAware(awareness: Boolean) {
        delegate.isNamespaceAware = awareness
    }

    override fun isNamespaceAware(): Boolean = delegate.isNamespaceAware

    override fun setValidating(validating: Boolean) {
        delegate.isValidating = validating
    }

    override fun isValidating(): Boolean = delegate.isValidating

    override fun setCoalescing(coalescing: Boolean) {
        delegate.isCoalescing = coalescing
    }

    override fun isCoalescing(): Boolean = delegate.isCoalescing

    override fun setExpandEntityReferences(expandEntityRef: Boolean) {
        delegate.isExpandEntityReferences = expandEntityRef
    }

    override fun isExpandEntityReferences(): Boolean = delegate.isExpandEntityReferences

    override fun setXIncludeAware(state: Boolean) {
        delegate.isXIncludeAware = state
    }

    override fun isXIncludeAware(): Boolean = delegate.isXIncludeAware

    private companion object {
        fun createPlatformDelegate(): DocumentBuilderFactory {
            val property = DocumentBuilderFactory::class.java.name
            val configured = System.getProperty(property)
            return try {
                System.clearProperty(property)
                DocumentBuilderFactory.newInstance()
            } finally {
                if (configured == null) {
                    System.clearProperty(property)
                } else {
                    System.setProperty(property, configured)
                }
            }
        }
    }
}

internal fun <T> withUnsupportedDoctypeFeature(block: () -> T): T =
    synchronized(DocumentBuilderFactory::class.java) {
        val property = DocumentBuilderFactory::class.java.name
        val original = System.getProperty(property)
        try {
            System.setProperty(property, UnsupportedDoctypeFeatureFactory::class.java.name)
            block()
        } finally {
            if (original == null) System.clearProperty(property) else System.setProperty(property, original)
        }
    }

internal const val DISALLOW_DOCTYPE_FEATURE =
    "http://apache.org/xml/features/disallow-doctype-decl"
