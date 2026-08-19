package com.flue.launcher.watchface.store

import com.flue.launcher.watchface.standard.WatchmakerDefinition
import com.flue.launcher.watchface.standard.WatchmakerDefinitionVersion
import com.flue.launcher.watchface.standard.WatchmakerLayer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 上传前的本地预检。
 *
 * 这层存在的理由：上游 `POST /my/watchfaces` **永远返回 201** —— 扫描不通过时文件照存、
 * 状态转待审、发一条站内通知。HTTP 层看不出「被自动拦截」，所以必须在字节离开设备之前
 * 自己查一遍，否则作者只会看到「上传成功」。
 */
class WatchStoreUploadPreflightTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun layer(type: String, vararg attributes: Pair<String, String>) = WatchmakerLayer(
        id = null,
        type = type,
        parentId = null,
        attributes = attributes.toMap(),
        children = emptyList(),
    )

    private fun definition(
        version: WatchmakerDefinitionVersion = WatchmakerDefinitionVersion.FORMAT_1,
        layers: List<WatchmakerLayer>,
    ) = WatchmakerDefinition(
        version = version,
        rootAttributes = mapOf("name" to "Probe", "shape" to "R"),
        themes = emptyList(),
        layers = layers,
        auxiliaryElements = emptyList(),
    )

    @Test
    fun `a ring built from the old default attributes trips every required attribute`() {
        // 这正是老师转述的那一类被拦表盘：编辑器旧的默认属性写的是 radius / width，
        // 而规范名是 radius_outer / radius_inner / angle_total / angle。
        val findings = WatchStoreUploadRules.inspect(
            definition(layers = listOf(layer("ring", "radius" to "120", "width" to "12"))),
        )
        val missing = findings.filterIsInstance<WatchStoreUploadFinding.MissingAttribute>()
            .map { it.attribute }
        assertEquals(
            listOf("radius_outer", "radius_inner", "angle_total", "angle"),
            missing,
        )
    }

    @Test
    fun `a spec compliant face has nothing to report`() {
        val findings = WatchStoreUploadRules.inspect(
            definition(
                layers = listOf(
                    layer("shape", "shape" to "circle", "width" to "120", "height" to "120"),
                    layer(
                        "ring",
                        "radius_outer" to "120",
                        "radius_inner" to "108",
                        "angle_total" to "360",
                        "angle" to "360",
                    ),
                    layer("text", "text" to "Flue", "text_size" to "48", "font" to "Roboto-Regular"),
                ),
            ),
        )
        assertEquals(emptyList<WatchStoreUploadFinding>(), findings)
    }

    @Test
    fun `an empty attribute counts as missing just like upstream`() {
        // 上游判据是 `undefined || === ''`，空字符串同样算缺失。
        val findings = WatchStoreUploadRules.inspect(
            definition(layers = listOf(layer("text", "text" to "hi", "text_size" to "40", "font" to ""))),
        )
        assertEquals(
            listOf("font"),
            findings.filterIsInstance<WatchStoreUploadFinding.MissingAttribute>().map { it.attribute },
        )
    }

    @Test
    fun `a two point zero layer inside a one point zero face is reported`() {
        val findings = WatchStoreUploadRules.inspect(
            definition(layers = listOf(layer("photo_slot", "width" to "100", "height" to "100"))),
        )
        assertTrue(findings.any { it is WatchStoreUploadFinding.Format2LayerInFormat1 })
        // 同一个类型在 2.0 表盘里是合法的。
        assertEquals(
            emptyList<WatchStoreUploadFinding>(),
            WatchStoreUploadRules.inspect(
                definition(
                    version = WatchmakerDefinitionVersion.FORMAT_2,
                    layers = listOf(layer("photo_slot", "width" to "100", "height" to "100")),
                ),
            ),
        )
    }

    @Test
    fun `an unknown layer type short circuits its attribute check`() {
        val findings = WatchStoreUploadRules.inspect(
            definition(layers = listOf(layer("rainbow_cannon"))),
        )
        assertEquals(1, findings.size)
        assertTrue(findings.single() is WatchStoreUploadFinding.UnknownLayerType)
    }

    @Test
    fun `required files and blocked extensions are checked only when packaged`() {
        val layers = listOf(layer("shape", "shape" to "circle", "width" to "10", "height" to "10"))
        // 还在编辑器里（没有归档条目）时不查文件层面 —— 那时本来就还没打包。
        assertEquals(
            emptyList<WatchStoreUploadFinding>(),
            WatchStoreUploadRules.inspect(definition(layers = layers)),
        )
        val packaged = WatchStoreUploadRules.inspect(
            definition(layers = layers),
            archiveEntryNames = listOf("watch.xml", "hack.js"),
        )
        assertTrue(packaged.any { it is WatchStoreUploadFinding.MissingFile })
        val blocked = packaged.filterIsInstance<WatchStoreUploadFinding.BlockedFile>().single()
        assertEquals("hack.js", blocked.name)
        assertTrue("禁用扩展名必须算致命", blocked.fatal)
    }

    // ── 走真实归档 ────────────────────────────────────────────────────────────

    private fun writeArchive(name: String, entries: Map<String, ByteArray>): File {
        val archive = temporaryFolder.newFile(name)
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun watchXml(layerXml: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Watch name="Probe" shape="R" width="512" height="512">$layerXml</Watch>
    """.trimIndent().toByteArray()

    @Test
    fun `inspecting a real archive finds the offending layer`() {
        val archive = writeArchive(
            "bad.watch",
            mapOf(
                "watch.xml" to watchXml("""<Layer type="ring" radius="120" width="12"/>"""),
                "preview.jpg" to ByteArray(256) { 0x11 },
            ),
        )
        val findings = WatchStoreUploadPreflight.inspect(archive)
        assertEquals(4, findings.filterIsInstance<WatchStoreUploadFinding.MissingAttribute>().size)
    }

    @Test
    fun `a clean archive passes and requireCompliant stays silent`() {
        val archive = writeArchive(
            "good.watch",
            mapOf(
                "watch.xml" to watchXml(
                    """<Layer type="text" text="Flue" text_size="48" font="Roboto-Regular"/>""",
                ),
                "preview.jpg" to ByteArray(256) { 0x22 },
            ),
        )
        assertEquals(emptyList<WatchStoreUploadFinding>(), WatchStoreUploadPreflight.inspect(archive))
        WatchStoreUploadPreflight.requireCompliant(archive)
    }

    @Test
    fun `a missing preview is reported because the store requires it`() {
        val archive = writeArchive(
            "nopreview.watch",
            mapOf("watch.xml" to watchXml("""<Layer type="shape" shape="circle" width="8" height="8"/>""")),
        )
        val missing = WatchStoreUploadPreflight.inspect(archive)
            .filterIsInstance<WatchStoreUploadFinding.MissingFile>()
            .map { it.name }
        assertEquals(listOf("preview.jpg"), missing)
    }

    @Test
    fun `a shared top level directory is stripped like upstream does`() {
        // 上游 stripCommonRoot 接受 `face/watch.xml` 这种归档。不剥掉的话我们会误报
        // 「缺少 watch.xml」，把服务端本来会接受的包拦下来。
        val archive = writeArchive(
            "nested.watch",
            mapOf(
                "face/watch.xml" to watchXml(
                    """<Layer type="text" text="Flue" text_size="48" font="Roboto-Regular"/>""",
                ),
                "face/preview.jpg" to ByteArray(256) { 0x33 },
            ),
        )
        assertEquals(emptyList<WatchStoreUploadFinding>(), WatchStoreUploadPreflight.inspect(archive))
    }

    @Test
    fun `requireCompliant throws with the findings attached`() {
        val archive = writeArchive(
            "bad2.watch",
            mapOf(
                "watch.xml" to watchXml("""<Layer type="progress" radius="120" value="50"/>"""),
                "preview.jpg" to ByteArray(256) { 0x44 },
            ),
        )
        val error = runCatching { WatchStoreUploadPreflight.requireCompliant(archive) }
            .exceptionOrNull()
        assertTrue(error is WatchStoreNonCompliantArchiveException)
        val findings = (error as WatchStoreNonCompliantArchiveException).findings
        assertEquals(
            listOf("width", "height", "pct_complete"),
            findings.filterIsInstance<WatchStoreUploadFinding.MissingAttribute>().map { it.attribute },
        )
    }

    @Test
    fun `a broken archive yields no findings instead of an exception`() {
        // 「文件坏了」不能和「不合规」混成一件事 —— 前者交给上传本身去失败。
        val notAZip = temporaryFolder.newFile("garbage.watch")
        notAZip.writeText("this is not a zip")
        assertEquals(emptyList<WatchStoreUploadFinding>(), WatchStoreUploadPreflight.inspect(notAZip))
        WatchStoreUploadPreflight.requireCompliant(notAZip)
    }
}
