package com.flue.launcher.watchface.editor.conversion

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchFormatConverterTest {
    @Test
    fun convertsWffTextWithoutInventingUnsupportedLayers() = withTempDir { root ->
        val source = File(root, "sample.wff")
        zip(
            source,
            "watchface.xml" to """
                <WatchFace name="WFF demo" width="512" height="512">
                  <Text x="10" y="20" size="32" text="Hello"/>
                  <Chart x="0" y="0"/>
                </WatchFace>
            """.trimIndent(),
        )
        val output = File(root, "converted.watch")
        val result = WatchFormatConverter().convert(source, output)
        assertTrue(result is WatchConversionResult.Success)
        val report = (result as WatchConversionResult.Success).report
        assertEquals(1, report.convertedLayerCount)
        assertTrue("Chart" in report.unsupportedFeatures)
        ZipFile(output).use { archive ->
            val xml = archive.getInputStream(archive.getEntry("watch.xml"))
                .use { it.readBytes().toString(StandardCharsets.UTF_8) }
            assertTrue(xml.contains("type=\"text\""))
            assertTrue(xml.contains("text=\"Hello\""))
        }
    }

    @Test
    fun flattensStudioGroupsAndKeepsUnknownXml() = withTempDir { root ->
        val source = File(root, "studio.watch")
        zip(
            source,
            "watch.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <Watch name="Studio" build_source="studio" shape="R">
                  <ThemeSets><ThemeSet name="Default" ucolor="#FF00FF"/></ThemeSets>
                  <VendorRoot answer="42"><Nested/></VendorRoot>
                  <Layer id="group" type="group" x="10" y="20"/>
                  <Layer id="child" type="text" parentId="group" x="2" y="3" text="{ucolor2}"/>
                </Watch>
            """.trimIndent(),
        )
        val output = File(root, "flat.watch")
        val result = WatchFormatConverter().convert(source, output)
        assertTrue(result is WatchConversionResult.Success)
        val report = (result as WatchConversionResult.Success).report
        assertEquals(1, report.convertedLayerCount)
        assertTrue(report.fixes.any { it.contains("group") })
        ZipFile(output).use { archive ->
            val xml = archive.getInputStream(archive.getEntry("watch.xml"))
                .use { it.readBytes().toString(StandardCharsets.UTF_8) }
            assertTrue(!xml.contains("build_source"))
            assertTrue(xml.contains("VendorRoot"))
            assertTrue(xml.contains("x=\"12\""))
            assertTrue(xml.contains("#FF00FF"))
        }
    }

    @Test(timeout = 2_000L)
    fun truncatesCyclicStudioParentGraphInsteadOfLooping() = withTempDir { root ->
        val source = File(root, "cyclic-studio.watch")
        zip(
            source,
            "watch.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <Watch name="Cyclic Studio" build_source="studio" shape="R">
                  <Layer id="group-a" type="group" parentId="group-b" x="1" y="2"/>
                  <Layer id="group-b" type="group" parentId="group-a" x="3" y="4"/>
                  <Layer id="child" type="text" parentId="group-a" x="5" y="6" text="safe"/>
                </Watch>
            """.trimIndent(),
        )

        val result = WatchFormatConverter().convert(source, File(root, "flat.watch"))

        assertTrue("conversion result: $result", result is WatchConversionResult.Success)
        val report = (result as WatchConversionResult.Success).report
        assertTrue(report.warnings.any { it.contains("循环 parentId") })
        assertEquals(1, report.convertedLayerCount)
    }

    @Test
    fun rejectsAssetReferenceThatEscapesExtractedArchive() = withTempDir { root ->
        val externalName = "flue-converter-secret-${UUID.randomUUID()}.png"
        val external = File(System.getProperty("java.io.tmpdir"), externalName)
        external.writeText("private-app-data", StandardCharsets.UTF_8)
        try {
            val source = File(root, "traversal.wff")
            zip(
                source,
                "watchface.xml" to """
                    <WatchFace name="Traversal" width="512" height="512">
                      <Image src="../$externalName"/>
                      <Text x="0" y="0" size="24" text="safe"/>
                    </WatchFace>
                """.trimIndent(),
            )
            val output = File(root, "converted.watch")

            val result = WatchFormatConverter().convert(source, output)

            assertTrue("conversion result: $result", result is WatchConversionResult.Success)
            val success = result as WatchConversionResult.Success
            assertTrue(success.report.warnings.any { it.contains("资源不存在") })
            ZipFile(output).use { archive ->
                val names = archive.entries().asSequence().map { it.name }.toList()
                assertTrue("$externalName was copied into output", names.none { it.contains(externalName) })
            }
        } finally {
            external.delete()
        }
    }

    @Test
    fun removesPartialExtractionDirectoryWhenArchiveExceedsLimit() = withTempDir { root ->
        val tempRoot = File(System.getProperty("java.io.tmpdir"))
        val before = converterTempDirectories(tempRoot)
        val source = File(root, "too-large.wff")
        zip(
            source,
            "watchface.xml" to "<WatchFace>${"x".repeat(128)}</WatchFace>",
        )

        val result = WatchFormatConverter(maximumEntryBytes = 32L)
            .convert(source, File(root, "out.watch"))

        assertTrue(result is WatchConversionResult.Failure)
        val leaked = converterTempDirectories(tempRoot) - before
        assertTrue("partial extraction directories leaked: $leaked", leaked.isEmpty())
    }

    @Test
    fun rejectsUnknownArchiveWithExplicitReport() = withTempDir { root ->
        val source = File(root, "unknown.bin")
        zip(source, "readme.txt" to "not a watch")
        val result = WatchFormatConverter().convert(source, File(root, "out.watch"))
        assertTrue(result is WatchConversionResult.Failure)
        val failure = result as WatchConversionResult.Failure
        assertTrue(failure.message.contains("无法识别"))
        assertTrue("unknown-format" in failure.report.unsupportedFeatures)
    }

    private fun zip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(java.util.zip.ZipEntry(name))
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.closeEntry()
            }
        }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("flue-converter-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun converterTempDirectories(root: File): Set<String> =
        root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("flue-converter-") }
            .mapTo(linkedSetOf()) { it.canonicalPath }
}
