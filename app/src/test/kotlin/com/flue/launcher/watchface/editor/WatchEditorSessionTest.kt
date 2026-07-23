package com.flue.launcher.watchface.editor

import com.flue.launcher.watchface.standard.WatchArchiveInstaller
import com.flue.launcher.watchface.standard.withUnsupportedDoctypeFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipFile

class WatchEditorSessionTest {
    @Test
    fun opensInstalledFaceAsCacheDraftWithoutMutatingSource() = withTemporaryDirectory { root ->
        val installed = File(root, "installed").apply { mkdirs() }
        val sourceXml = File(installed, "watch.xml").apply { writeText(SOURCE_XML) }
        File(installed, "images/hand.png").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val originalXml = sourceXml.readBytes()
        val cache = File(root, "cache")

        WatchEditorSession.openInstalled(installed, cache).use { session ->
            assertTrue(session.draftDirectory.canonicalPath.startsWith(cache.canonicalPath))
            assertNotEquals(installed.canonicalFile, session.draftDirectory.canonicalFile)

            session.updateRootAttribute("name", "Edited")
            session.updateLayerAttribute(session.snapshot().layers.first().key, "x", "42")
            session.save()

            assertTrue(session.watchXmlFile.readText().contains("name=\"Edited\""))
            assertTrue(File(session.draftDirectory, "images/hand.png").isFile)
            assertTrue(originalXml.contentEquals(sourceXml.readBytes()))
        }
        assertTrue(originalXml.contentEquals(sourceXml.readBytes()))
    }

    @Test
    fun preservesUnknownXmlWhileAddingDuplicatingDeletingAndReorderingLayers() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(SOURCE_XML)

            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                val initial = session.snapshot()
                val image = initial.layers.first()
                val text = initial.layers.last()

                session.updateRootAttributes(mapOf("vendor-root" to "still-here", "name" to "Edited"))
                session.updateLayerAttributes(image.key, mapOf("x" to "24", "vendor-attr" to "kept"))
                val added = session.addLayer(
                    type = "future_type",
                    attributes = mapOf("id" to "future", "custom" to "yes"),
                    index = 1,
                )
                val duplicate = session.duplicateLayer(image.key, index = 2)

                assertEquals("image_copy", session.snapshot().layers.first { it.key == duplicate }.id)
                assertTrue(session.reorderLayer(duplicate, 0))
                assertEquals(duplicate, session.snapshot().layers.first().key)
                assertTrue(session.deleteLayer(added))
                assertFalse(session.snapshot().layers.any { it.key == added })

                assertTrue(session.undo())
                assertTrue(session.snapshot().layers.any { it.key == added })
                assertTrue(session.redo())
                assertFalse(session.snapshot().layers.any { it.key == added })
                assertTrue(session.snapshot().layers.any { it.key == text.key })

                session.save()
                val saved = session.watchXmlFile.readText()
                assertTrue(saved.contains("FutureRoot"))
                assertTrue(saved.contains("VendorNode"))
                assertTrue(saved.contains("NestedUnknown"))
                assertTrue(saved.contains("keep-this-comment"))
                assertTrue(saved.contains("vendor-root=\"still-here\""))
            }
        }

    @Test
    fun blankDraftImportsResourceWithSafeNameAndExportsAtomicWatchZip() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache"), "Blank & Safe").use { session ->
                val resource = session.importResource(
                    ByteArrayInputStream(byteArrayOf(9, 8, 7)),
                    "../../unsafe hand.png",
                )
                assertEquals("images/unsafe_hand.png", resource)
                assertTrue(File(session.draftDirectory, resource).isFile)
                val layer = session.addLayer(
                    type = "image",
                    attributes = mapOf("id" to "hand", "src" to resource),
                )
                assertEquals("hand", session.snapshot().layers.first { it.key == layer }.id)

                val output = File(root, "exports/blank.watch")
                val exported = session.exportWatch(output)
                assertTrue(exported.outputFile.isFile)
                assertTrue(exported.bytes > 0)
                ZipFile(output).use { zip ->
                    val names = zip.entries().asSequence().map { it.name }.toSet()
                    assertTrue("watch.xml" in names)
                    assertTrue(resource in names)
                    assertTrue(names.none { it.startsWith('/') || ".." in it.split('/') })
                    val xml = zip.getInputStream(zip.getEntry("watch.xml"))
                        .use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    assertTrue(xml.contains("Blank &amp; Safe"))
                    assertTrue(xml.contains("src=\"$resource\""))
                }
                assertFalse(session.isDirty)
                assertTrue(output.parentFile?.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
                val installed = WatchArchiveInstaller().install(output, File(root, "reinstalled"))
                assertEquals("Blank & Safe", installed.definition.name)
                assertEquals("image", installed.definition.layers.single().type)
            }
        }

    @Test
    fun historyIsBoundedToThirtyTwoSteps() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            repeat(40) { index -> session.updateRootAttribute("revision", index.toString()) }
            var undoCount = 0
            while (session.undo()) undoCount++
            assertEquals(WatchEditorSession.MAX_HISTORY_STEPS, undoCount)
            assertEquals("7", session.snapshot().rootAttributes["revision"])
            assertFalse(session.undo())
        }
    }

    @Test
    fun failedExportKeepsDraftDirtyUntilAnArchiveIsActuallyCommitted() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                session.updateRootAttribute("name", "Must Export")
                // Highly compressible data intentionally trips the existing
                // archive validator's compression-ratio guard.
                session.importResource(ByteArrayInputStream(ByteArray(128 * 1024)), "zeros.png")
                assertTrue(session.isDirty)
                assertEditorFailure { session.exportWatch(File(root, "bad.watch")) }
                assertTrue(session.isDirty)
                assertFalse(File(root, "bad.watch").exists())
            }
        }

    @Test
    fun previewFlushWritesCurrentXmlWithoutClearingDirtyState() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            session.updateRootAttribute("name", "Preview Name")
            assertTrue(session.isDirty)
            val previewXml = session.flushDraftForPreview()
            assertTrue(previewXml.readText().contains("name=\"Preview Name\""))
            assertTrue(session.isDirty)
            session.save()
            assertFalse(session.isDirty)
        }
    }

    @Test
    fun closingSessionRemovesDraftAndResourceHistory() = withTemporaryDirectory { root ->
        val session = WatchEditorSession.createBlank(File(root, "cache"))
        val draft = session.draftDirectory
        val history = File(draft.parentFile, ".${draft.name}-history")
        session.importResource(ByteArrayInputStream(byteArrayOf(1)), "image.png")
        session.undo()
        assertTrue(draft.exists())
        assertTrue(history.exists())
        session.close()
        assertFalse(draft.exists())
        assertFalse(history.exists())
    }

    @Test
    fun undoAndRedoMoveImportedResourcesThroughTheSessionStash() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val layer = session.addLayer("image", mapOf("id" to "image"))
                val resource = session.importResource(ByteArrayInputStream(byteArrayOf(1)), "image.png")
                assertTrue(File(session.draftDirectory, resource).isFile)

                assertTrue(session.undo())
                assertFalse(File(session.draftDirectory, resource).exists())
                assertEquals(layer, session.snapshot().layers.single().key)
                assertTrue(session.redo())
                assertTrue(File(session.draftDirectory, resource).isFile)
                assertEquals(byteArrayOf(1).toList(), File(session.draftDirectory, resource).readBytes().toList())
            }
        }

    @Test
    fun rejectsDoctypeAndExternalEntityDefinitions() = withTemporaryDirectory { root ->
        val source = File(root, "source").apply { mkdirs() }
        File(source, "watch.xml").writeText(
            """
            <!DOCTYPE Watch [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <Watch name="&xxe;"/>
            """.trimIndent(),
        )

        assertEditorFailure {
            WatchEditorSession.openInstalled(source, File(root, "cache"))
        }
    }

    @Test
    fun opensSafeDraftWhenProviderCannotDisableDoctype() = withTemporaryDirectory { root ->
        val source = File(root, "source").apply { mkdirs() }
        File(source, "watch.xml").writeText(SOURCE_XML)

        withUnsupportedDoctypeFeature {
            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                assertEquals("Original", session.snapshot().rootAttributes["name"])
            }
        }
    }

    @Test
    fun editorStillRejectsDoctypeWhenProviderCannotDisableDoctype() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText("<!DOCTYPE Watch><Watch name=\"unsafe\"/>")

            assertEditorFailure {
                withUnsupportedDoctypeFeature {
                    WatchEditorSession.openInstalled(source, File(root, "cache"))
                }
            }
        }

    @Test
    fun enforcesPerFileAndTotalDraftBudgets() = withTemporaryDirectory { root ->
        val limits = WatchEditorLimits(maxTotalBytes = 700, maxFileBytes = 512, maxXmlDepth = 16)
        WatchEditorSession.createBlank(File(root, "cache"), limits = limits).use { session ->
            session.importResource(ByteArrayInputStream(ByteArray(400)), "first.png")
            assertEditorFailure {
                session.importResource(ByteArrayInputStream(ByteArray(513)), "too-large.png")
            }
            assertEditorFailure {
                session.importResource(ByteArrayInputStream(ByteArray(400)), "total-overflow.png")
            }
            assertEquals(listOf("images/first.png"), session.snapshot().resourcePaths)
        }
    }

    private fun assertEditorFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected WatchEditorException")
        } catch (_: WatchEditorException) {
            // Expected.
        }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("watch-editor-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private val SOURCE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Watch name="Original" build_source="studio" shape="R" vendor-root="keep">
                <!--keep-this-comment-->
                <ThemeSets><ThemeSet name="Default"><VendorTheme/></ThemeSet></ThemeSets>
                <Layer id="image" type="image" x="1" vendor-attr="original">
                    <VendorNode mode="future"><NestedUnknown value="1"/></VendorNode>
                </Layer>
                <FutureRoot enabled="yes"><Nested/></FutureRoot>
                <Layer id="text" type="text" parentId="image" expression="{dh23}"/>
            </Watch>
        """.trimIndent()
    }
}
