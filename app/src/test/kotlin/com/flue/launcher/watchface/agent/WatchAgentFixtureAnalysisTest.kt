package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.tools.WatchAgentAssetPathNormalizer
import com.flue.launcher.watchface.agent.tools.WatchAgentExpressionClassifier
import com.flue.launcher.watchface.agent.tools.WatchAgentExpressionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

class WatchAgentFixtureAnalysisTest {
    @Test
    fun `path-error fixture is accepted for reading but normalized for CloX`() {
        val file = fixture("flue_open_3875474605600462187.watch")
        assumeTrue(file.isFile)
        val xml = readEntry(file, "watch.xml")
        val imageCount = ZipFile(file).use { zip ->
            zip.entries().asSequence().count { it.name.startsWith("images/") && !it.isDirectory }
        }
        val pathValues = PATH_PATTERN.findAll(xml).flatMap { it.groupValues[2].split('`').asSequence() }.toList()

        assertEquals(291, imageCount)
        assertEquals(291, pathValues.size)
        assertTrue(pathValues.all { it.startsWith("images/", ignoreCase = true) })
        assertTrue(pathValues.map(WatchAgentAssetPathNormalizer::normalizeImagePath).all { !it.startsWith("images/") })
    }

    @Test
    fun `inline-lua fixture does not require a global script and compiles unique sources once`() {
        val file = fixture("flue_open_4582926066476804939.watch")
        assumeTrue(file.isFile)
        val xml = readEntry(file, "watch.xml")
        val classifications = WatchAgentExpressionClassifier.classifyXml(xml)
        val lua = classifications.filter { it.kind == WatchAgentExpressionKind.LUA_EXPRESSION }

        assertFalse(hasEntry(file, "scripts/script.txt"))
        assertEquals(99, lua.size)
        assertEquals(53, lua.map { it.source }.toSet().size)
    }

    private fun fixture(name: String): File = File("D:/Administrator/Downloads", name)

    private fun hasEntry(file: File, name: String): Boolean = ZipFile(file).use { zip ->
        zip.getEntry(name) != null
    }

    private fun readEntry(file: File, name: String): String = ZipFile(file).use { zip ->
        zip.getInputStream(zip.getEntry(name)).use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    companion object {
        private val PATH_PATTERN = Regex("(?i)\\b(?:path|paths)\\s*=\\s*([\\\"'])(.*?)\\1")
    }
}
