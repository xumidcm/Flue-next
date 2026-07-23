package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WatchArchiveInstallerTest {
    @Test
    fun installsAStandardWatchXmlWithoutLegacyWatchPxml() = withTemporaryDirectory { root ->
        val archive = File(root, "simple.watch")
        writeZip(
            archive,
            "watch.xml" to FORMAT_ONE_XML.toByteArray(),
            "preview.jpg" to byteArrayOf(1, 2, 3),
            "images/.hand.png" to byteArrayOf(4, 5, 6),
        )

        val installed = WatchArchiveInstaller().install(archive, File(root, "installed"))

        assertTrue(installed.watchXml.isFile)
        assertTrue(installed.preview?.isFile == true)
        assertEquals("Simple", installed.definition.name)
        assertEquals(WatchmakerDefinitionVersion.FORMAT_1, installed.definition.version)
        assertEquals(listOf("image", "text"), installed.definition.layers.map { it.type })
        assertFalse(File(installed.rootDirectory, "watch.pxml").exists())
    }

    @Test
    fun installsSafeArchiveWhenProviderCannotDisableDoctype() = withTemporaryDirectory { root ->
        val archive = File(root, "fallback.watch")
        writeZip(archive, "watch.xml" to FORMAT_ONE_XML.toByteArray())

        val installed = withUnsupportedDoctypeFeature {
            WatchArchiveInstaller().install(archive, File(root, "installed"))
        }

        assertEquals("Simple", installed.definition.name)
        assertTrue(installed.watchXml.isFile)
    }

    @Test
    fun contentAddressedInstallIsIdempotentAndDoesNotReplaceAnotherFace() =
        withTemporaryDirectory { root ->
            val firstArchive = File(root, "first.watch")
            val secondArchive = File(root, "second.watch")
            writeZip(firstArchive, "watch.xml" to FORMAT_ONE_XML.toByteArray())
            writeZip(
                secondArchive,
                "watch.xml" to FORMAT_ONE_XML.replace("Simple", "Second").toByteArray(),
            )
            val installer = WatchArchiveInstaller()
            val installRoot = File(root, "installed")

            val first = installer.install(firstArchive, installRoot)
            val same = installer.install(firstArchive, installRoot)
            val second = installer.install(secondArchive, installRoot)

            assertEquals(first.stableId, same.stableId)
            assertTrue(first.rootDirectory.canonicalFile == same.rootDirectory.canonicalFile)
            assertTrue(first.stableId != second.stableId)
            assertEquals("Simple", first.definition.name)
            assertEquals("Second", second.definition.name)
        }

    @Test
    fun nestedWatchXmlKeepsAssetsOutsideItsParentDirectory() =
        withTemporaryDirectory { root ->
            val archive = File(root, "nested.watch")
            writeZip(
                archive,
                "face/watch.xml" to FORMAT_ONE_XML.toByteArray(),
                "face/preview.jpg" to byteArrayOf(1, 2, 3),
                "shared/fonts/digits.ttf" to byteArrayOf(4, 5, 6),
            )

            val installed = WatchArchiveInstaller().install(archive, File(root, "installed"))

            assertEquals("face/watch.xml", installed.watchXml.relativeTo(installed.rootDirectory).invariantSeparatorsPath)
            assertEquals("face/preview.jpg", installed.preview?.relativeTo(installed.rootDirectory)?.invariantSeparatorsPath)
            assertTrue(File(installed.rootDirectory, "shared/fonts/digits.ttf").isFile)
        }

    @Test
    fun reportsTypedFailureWhenInstallRootIsAFile() = withTemporaryDirectory { root ->
        val archive = File(root, "simple.watch")
        writeZip(archive, "watch.xml" to FORMAT_ONE_XML.toByteArray())
        val installRoot = File(root, "installed").apply { writeText("not a directory") }

        assertArchiveFailure(WatchArchiveFailureReason.INSTALL_FAILED) {
            WatchArchiveInstaller().install(archive, installRoot)
        }
    }

    @Test
    fun rejectsPathTraversalBeforeAnythingIsExtracted() = withTemporaryDirectory { root ->
        val archive = File(root, "escape.watch")
        writeZip(
            archive,
            "watch.xml" to FORMAT_ONE_XML.toByteArray(),
            "../outside.txt" to byteArrayOf(1),
        )

        assertArchiveFailure(WatchArchiveFailureReason.INVALID_ENTRY_NAME) {
            WatchArchiveInstaller().install(archive, File(root, "installed"))
        }
        assertFalse(File(root, "outside.txt").exists())
    }

    @Test
    fun rejectsCaseInsensitiveDuplicateEntriesForWindowsFilesystems() =
        withTemporaryDirectory { root ->
            val archive = File(root, "duplicate.watch")
            writeZip(
                archive,
                "watch.xml" to FORMAT_ONE_XML.toByteArray(),
                "WATCH.XML" to FORMAT_ONE_XML.toByteArray(),
            )

            assertArchiveFailure(WatchArchiveFailureReason.DUPLICATE_ENTRY) {
                WatchArchiveValidator().inspect(archive)
            }
        }

    @Test
    fun rejectsFileAndDirectoryPathConflictsBeforeExtraction() =
        withTemporaryDirectory { root ->
            val archive = File(root, "path-conflict.watch")
            writeZip(
                archive,
                "watch.xml" to FORMAT_ONE_XML.toByteArray(),
                "images" to byteArrayOf(1),
                "images/hand.png" to byteArrayOf(2),
            )

            assertArchiveFailure(WatchArchiveFailureReason.DUPLICATE_ENTRY) {
                WatchArchiveValidator().inspect(archive)
            }
        }

    @Test
    fun rejectsMissingWatchXml() = withTemporaryDirectory { root ->
        val archive = File(root, "missing.watch")
        writeZip(archive, "preview.jpg" to byteArrayOf(1))

        assertArchiveFailure(WatchArchiveFailureReason.MISSING_WATCH_XML) {
            WatchArchiveValidator().inspect(archive)
        }
    }

    @Test
    fun rejectsDeclaredOrStreamingExpansionOverBudget() = withTemporaryDirectory { root ->
        val archive = File(root, "large.watch")
        writeZip(
            archive,
            "watch.xml" to FORMAT_ONE_XML.toByteArray(),
            "images/large.bin" to ByteArray(4_096) { 7 },
        )
        val limits = WatchArchiveLimits(
            maximumArchiveBytes = 1_000_000,
            maximumSingleFileBytes = 1_024,
            maximumExpandedBytes = 2_048,
            maximumCompressionRatio = 10_000.0,
        )

        assertArchiveFailure(WatchArchiveFailureReason.ENTRY_TOO_LARGE) {
            WatchArchiveValidator(limits).inspect(archive)
        }
    }

    @Test
    fun parserPreservesStudioHierarchyThemesAndUnknownAttributes() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply { writeText(FORMAT_TWO_XML) }

            val definition = WatchmakerDefinitionParser().parse(xml)

            assertEquals(WatchmakerDefinitionVersion.FORMAT_2, definition.version)
            assertEquals("Ocean", definition.themes.single().name)
            assertEquals("group-1", definition.layers[1].parentId)
            assertEquals("kept", definition.layers[1].attributes["future_attr"])
            assertTrue(definition.unsupportedLayerTypes.isEmpty())
        }

    @Test
    fun parserAcceptsCaseVariantsUsedByCommunityExporters() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText(
                    """
                    <watch build_source="studio" name="case-variant">
                      <themesets><themeset name="Ocean" /></themesets>
                      <layer id="text-1" type="text" />
                    </watch>
                    """.trimIndent(),
                )
            }

            val definition = WatchmakerDefinitionParser().parse(xml)

            assertEquals("Ocean", definition.themes.single().name)
            assertEquals("text", definition.layers.single().type)
        }

    @Test
    fun parserAcceptsSafeXmlWhenProviderCannotDisableDoctype() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply { writeText(FORMAT_ONE_XML) }

            val definition = withUnsupportedDoctypeFeature {
                WatchmakerDefinitionParser().parse(xml)
            }

            assertEquals("Simple", definition.name)
            assertEquals(listOf("image", "text"), definition.layers.map { it.type })
        }

    @Test
    fun parserStillRejectsDoctypeWhenProviderCannotDisableDoctype() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText("<!DOCTYPE Watch SYSTEM \"file:///not-allowed\"><Watch name=\"unsafe\"/>")
            }

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("DOCTYPE"))
        }

    @Test
    fun parserStillRejectsEntityDeclarationWhenProviderCannotDisableDoctype() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText("<!ENTITY secret SYSTEM \"file:///not-allowed\"><Watch name=\"unsafe\"/>")
            }

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("ENTITY"))
        }

    @Test
    fun parserDoesNotExpandInternalEntityWhenProviderCannotDisableDoctype() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText(
                    "<!DOCTYPE Watch [<!ENTITY secret \"expanded\">]>" +
                        "<Watch name=\"&secret;\"/>",
                )
            }

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("DOCTYPE"))
        }

    @Test
    fun parserRejectsEbcDicXmlBeforeFallbackProviderCanExpandEntities() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml")
            xml.writeBytes(
                (
                    "<?xml version=\"1.0\" encoding=\"Cp037\"?><!DOCTYPE Watch " +
                        "[<!ENTITY secret \"expanded\">]><Watch name=\"&secret;\"/>"
                    ).toByteArray(Charset.forName("IBM037")),
            )

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("encoding"))
        }

    @Test
    fun parserRejectsUtf16DoctypeBeforeFallbackParserRuns() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText(
                    "<!DOCTYPE Watch><Watch name=\"unsafe\"/>",
                    charset("UTF-16LE"),
                )
            }

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("DOCTYPE"))
        }

    @Test
    fun parserRejectsStudioParentCycles() = withTemporaryDirectory { root ->
        val xml = File(root, "watch.xml").apply {
            writeText(
                """
                <Watch build_source="studio" name="cycle">
                  <Layer id="a" parentId="b" type="group" />
                  <Layer id="b" parentId="a" type="group" />
                </Watch>
                """.trimIndent(),
            )
        }

        val error = runCatching { WatchmakerDefinitionParser().parse(xml) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("cycle", ignoreCase = true))
    }

    @Test
    fun parserRejectsDoctypeAndExternalEntities() = withTemporaryDirectory { root ->
        val xml = File(root, "watch.xml").apply {
            writeText(
                """
                <!DOCTYPE Watch [<!ENTITY secret SYSTEM "file:///not-allowed">]>
                <Watch name="&secret;"><Layer type="text" text="x" /></Watch>
                """.trimIndent(),
            )
        }

        assertTrue(runCatching { WatchmakerDefinitionParser().parse(xml) }.isFailure)
    }

    private fun assertArchiveFailure(
        reason: WatchArchiveFailureReason,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected WatchArchiveException($reason)")
        } catch (error: WatchArchiveException) {
            assertEquals(reason, error.reason)
        }
    }

    private fun writeZip(file: File, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private inline fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("flue-watch-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val FORMAT_ONE_XML =
            """
            <Watch name="Simple" shape="R" bg_color="000000">
              <Layer type="image" path=".hand.png" width="32" height="128" rotation="{drm}" />
              <Layer type="text" text="{dh24z}:{dmz}" text_size="32" color="ffffff" />
            </Watch>
            """.trimIndent()

        val FORMAT_TWO_XML =
            """
            <Watch build_source="studio" name="Studio" shape="R" ucolor_default="12aff6">
              <ThemeSets>
                <ThemeSet name="Ocean" ucolor="12aff6" ucolor2="1842f8" ucolor3="641ffa" />
              </ThemeSets>
              <Layer id="group-1" type="group" name="Hands" />
              <Layer id="hand-1" parentId="group-1" type="image" path="hand.png" future_attr="kept">
                <Ambient opacity="60" />
              </Layer>
            </Watch>
            """.trimIndent()
    }
}
