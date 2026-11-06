package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchmakerMetadataReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `unchanged complex face is parsed only once across reader instances`() {
        val xml = temporary.newFile("watch.xml")
        xml.writeText(definition("Rainbow", layers = 128))
        val cache = temporary.newFolder("cache")
        var parseCount = 0
        val parse: (ByteArray) -> WatchmakerDefinition = {
            parseCount++
            WatchmakerDefinitionParser().parse(it.inputStream(), SecureWatchXmlParser.DEFAULT_MAX_BYTES)
        }

        val first = WatchmakerMetadataReader(cache, parse).read(xml)
        repeat(5) { assertEquals(first, WatchmakerMetadataReader(cache, parse).read(xml)) }

        assertEquals(128, first.layerCount)
        assertEquals("Author", first.author)
        assertEquals(1, parseCount)
    }

    @Test
    fun `same size edit with unchanged timestamp still refreshes metadata`() {
        val xml = temporary.newFile("watch.xml")
        xml.writeText(definition("Before"))
        val reader = WatchmakerMetadataReader(temporary.newFolder("cache"))
        assertEquals("Before", reader.read(xml).name)
        val modified = xml.lastModified()
        val length = xml.length()

        xml.writeText(definition("After!"))
        assertTrue(xml.setLastModified(modified))
        assertEquals(length, xml.length())

        assertEquals("After!", reader.read(xml).name)
    }

    @Test
    fun `invalid edit never returns cached valid metadata`() {
        val xml = temporary.newFile("watch.xml")
        xml.writeText(definition("Valid"))
        val reader = WatchmakerMetadataReader(temporary.newFolder("cache"))
        reader.read(xml)
        xml.writeText("<Watch><Layer")
        try {
            reader.read(xml)
            fail("Malformed XML must still fail after caching")
        } catch (_: org.xml.sax.SAXException) {
            // The real secure parser rejected the modified file.
        }
    }

    @Test
    fun `corrupt cache is rebuilt from validated xml`() {
        val xml = temporary.newFile("watch.xml")
        xml.writeText(definition("Valid"))
        val cache = temporary.newFolder("cache")
        val reader = WatchmakerMetadataReader(cache)
        val expected = reader.read(xml)
        val entries = cache.listFiles().orEmpty()
        assertEquals(1, entries.size)
        entries.single().writeText("truncated")

        assertEquals(expected, WatchmakerMetadataReader(cache).read(xml))
    }

    @Test
    fun `unwritable cache does not hide an installed face`() {
        val xml = temporary.newFile("watch.xml")
        xml.writeText(definition("Valid"))
        val notADirectory = temporary.newFile("cache")

        assertEquals("Valid", WatchmakerMetadataReader(notADirectory).read(xml).name)
    }

    @Test
    fun `revisions replace one cache entry rather than accumulating definitions`() {
        val xml = temporary.newFile("watch.xml")
        val cache = temporary.newFolder("cache")
        val reader = WatchmakerMetadataReader(cache)
        repeat(4) { revision ->
            xml.writeText(definition("Revision $revision"))
            assertEquals("Revision $revision", reader.read(xml).name)
        }
        assertEquals(1, cache.listFiles().orEmpty().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized xml is rejected before being parsed or cached`() {
        val xml = temporary.newFile("watch.xml")
        java.io.RandomAccessFile(xml, "rw").use {
            it.setLength(SecureWatchXmlParser.DEFAULT_MAX_BYTES + 1)
        }
        WatchmakerMetadataReader(temporary.newFolder("cache")) { error("Must not parse oversized XML") }
            .read(xml)
    }

    private fun definition(name: String, layers: Int = 1): String = buildString {
        append("<Watch name=\"$name\" author=\"Author\">")
        repeat(layers) { append("<Layer type=\"text\" text=\"{dh24}:{dm}\" />") }
        append("</Watch>")
    }
}
