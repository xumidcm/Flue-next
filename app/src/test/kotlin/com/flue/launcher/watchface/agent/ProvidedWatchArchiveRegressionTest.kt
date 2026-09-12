package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.tools.WatchAgentExpressionClassifier
import com.flue.launcher.watchface.agent.tools.WatchAgentExpressionKind
import com.flue.launcher.watchface.standard.WatchmakerDefinitionParser
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Local regression over the two archives supplied with the bug report. */
class ProvidedWatchArchiveRegressionTest {
    @Test
    fun `458 archive keeps all layers and classifies inline lua without a global script`() {
        val archive = File("D:/Administrator/Downloads/flue_open_4582926066476804939.watch")
        assumeTrue(archive.isFile)
        ZipFile(archive).use { zip ->
            val watchEntry = requireNotNull(zip.getEntry("watch.xml"))
            val xml = zip.getInputStream(watchEntry).bufferedReader().use { it.readText() }
            val definition = WatchmakerDefinitionParser().parse(xml.byteInputStream(), 2_000_000L)
            val lua = WatchAgentExpressionClassifier.classifyXml(xml)
                .filter { it.kind == WatchAgentExpressionKind.LUA_EXPRESSION }

            assertEquals(293, definition.layers.size)
            assertEquals(99, lua.size)
            assertTrue(lua.all { it.source.contains("function", ignoreCase = true) })
            assertFalse(zip.entries().asSequence().any { it.name.equals("scripts/script.txt", ignoreCase = true) })
        }
    }

    @Test
    fun `387 archive retains images paths and global script compatibility`() {
        val archive = File("D:/Administrator/Downloads/flue_open_3875474605600462187.watch")
        assumeTrue(archive.isFile)
        ZipFile(archive).use { zip ->
            val watchEntry = requireNotNull(zip.getEntry("watch.xml"))
            val definition = WatchmakerDefinitionParser().parse(zip.getInputStream(watchEntry), 2_000_000L)
            val imagePaths = definition.layers.mapNotNull { it.attributes["path"] }

            assertEquals(293, definition.layers.size)
            assertTrue(zip.getEntry("scripts/script.txt") != null)
            assertTrue(imagePaths.any { it.startsWith("images/") })
        }
    }
}
