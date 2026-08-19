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

    /**
     * WFF 的坐标必须真的换算到 WatchMaker 的中心原点 512 画布，根节点不许留下
     * 规范外的 width / height。
     *
     * 转换器过去逐字照抄 x/y/width/height，再靠往根节点写源尺寸让 Flue 自己的
     * 渲染器把设计空间改成源画布 —— 那两个属性不在 1.0 §3 的表里，别的渲染器
     * 读不到，而「左上角原点 → 中心原点」和「左上角 → 中心」这两层偏移一直没做，
     * 于是整块表盘往右下偏半个屏幕。这条测试盯着这三件事一起对。
     */
    @Test
    fun wffGeometryIsMappedIntoTheCenterOriginCanvasAndTheRootStaysSpecClean() =
        withTempDir { root ->
            val source = File(root, "geometry.wff")
            zip(
                source,
                "watchface.xml" to """
                    <WatchFace name="Geometry" width="256" height="256">
                      <Image src="hand.png" x="0" y="0" width="128" height="128" color="#FF112233"/>
                    </WatchFace>
                """.trimIndent(),
                "hand.png" to "not-really-a-png",
            )
            val output = File(root, "converted.watch")

            val result = WatchFormatConverter().convert(source, output)

            assertTrue("conversion result: $result", result is WatchConversionResult.Success)
            ZipFile(output).use { archive ->
                val xml = archive.getInputStream(archive.getEntry("watch.xml"))
                    .use { it.readBytes().toString(StandardCharsets.UTF_8) }
                // 256 源画布 → 系数 2。左上角 (0,0) 的 128 方块中心是 (64,64)，
                // 相对画布中心 (128,128) 是 (-64,-64)，乘 2 得 (-128,-128)。
                assertTrue(xml, xml.contains("x=\"-128\""))
                assertTrue(xml, xml.contains("y=\"-128\""))
                assertTrue(xml, xml.contains("width=\"256\""))
                assertTrue(xml, xml.contains("height=\"256\""))
                // path 是 images/ 内的裸名，不带目录段（1.0 §7.1）。
                assertTrue(xml, xml.contains("path=\"hand.png\""))
                assertTrue(xml, !xml.contains("path=\"images/"))
                // 颜色归一成裸 6 位小写，alpha 丢给 opacity。
                assertTrue(xml, xml.contains("color=\"112233\""))
                // 根节点只剩规范属性。
                val rootTag = xml.substringAfter("<Watch").substringBefore('>')
                assertTrue(rootTag, !rootTag.contains("width="))
                assertTrue(rootTag, !rootTag.contains("height="))
            }
        }

    /** 认不出来的颜色原样保留 —— 那可能是主题引用，猜错比留着更糟。 */
    @Test
    fun colorNormalisationOnlyTouchesRealHexLiterals() {
        assertEquals("112233", wffColorToWatchmakerHex("#FF112233"))
        assertEquals("112233", wffColorToWatchmakerHex("112233"))
        assertEquals("112233", wffColorToWatchmakerHex("  #112233 "))
        assertEquals(null, wffColorToWatchmakerHex("[THEME_COLOR_1]"))
        assertEquals(null, wffColorToWatchmakerHex("#123"))
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
