package com.flue.launcher.watchface.editor

import com.flue.launcher.watchface.standard.WatchArchiveInstaller
import com.flue.launcher.watchface.standard.WatchmakerDefinitionParser
import com.flue.launcher.watchface.standard.WatchmakerProtection
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
    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )

    @Test
    fun protectedExportWritesPxmlAndEncryptedImageTwinWithoutPlainImage() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val imagePath = session.importResource(
                    ByteArrayInputStream(
                        pngHeader +
                            byteArrayOf(1, 2, 3, 4),
                    ),
                    "face.png",
                )
                session.addLayer(
                    type = "image",
                    attributes = mapOf("id" to "face", "path" to imagePath),
                )

                val output = File(root, "protected.watch")
                session.exportWatch(output, protectedExport = true)

                ZipFile(output).use { zip ->
                    assertTrue(zip.getEntry("watch.pxml") != null)
                    assertTrue(zip.getEntry("images/face.ppng") != null)
                    assertTrue(zip.getEntry("images/face.png") == null)
                    val shell = zip.getInputStream(zip.getEntry("watch.xml"))
                        .use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    assertTrue(shell.contains("protection=\"y\""))

                    val pxml = zip.getInputStream(zip.getEntry("watch.pxml"))
                        .use { WatchmakerProtection.openDecodedXml(it).readBytes() }
                        .toString(StandardCharsets.UTF_8)
                    assertTrue(pxml.contains("path=\"images/face.ppng\""))
                    val encodedImage = zip.getInputStream(zip.getEntry("images/face.ppng"))
                        .use { it.readBytes() }
                    assertTrue(
                        WatchmakerProtection.unprotectAssetBytes(encodedImage)
                            .contentEquals(
                                pngHeader +
                                    byteArrayOf(1, 2, 3, 4),
                            ),
                    )
                }
            }
        }

    @Test
    fun plainExportDropsStaleProtectedDefinitionFromInstalledDraft() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(SOURCE_XML)
            File(source, "watch.pxml").writeText("stale protected definition")

            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                session.updateRootAttribute("name", "Updated")
                val output = File(root, "plain.watch")
                session.exportWatch(output)

                ZipFile(output).use { zip ->
                    assertTrue(zip.getEntry("watch.pxml") == null)
                    val xml = zip.getInputStream(zip.getEntry("watch.xml"))
                        .use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    assertTrue(xml.contains("name=\"Updated\""))
                }
            }
        }
    @Test
    fun globalScriptReadsExistingContentAndIsHiddenFromOrdinaryResources() =
        withTemporaryDirectory { root ->
            val installed = File(root, "installed").apply { mkdirs() }
            File(installed, "watch.xml").writeText(SOURCE_XML)
            File(installed, "scripts/script.txt").apply {
                parentFile.mkdirs()
                writeText("function on_display_bright()\nend\n")
            }

            WatchEditorSession.openInstalled(installed, File(root, "cache")).use { session ->
                assertEquals("function on_display_bright()\nend\n", session.globalScript())
                assertFalse("scripts/script.txt" in session.snapshot().resourcePaths)
                assertFalse(session.isDirty)
            }
        }

    @Test
    fun globalScriptCreateOverwriteUndoRedoAndSaveTrackContent() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val initialRevision = session.snapshot().globalScriptRevision

                session.updateGlobalScript("var_score = 1")
                assertEquals("var_score = 1", session.globalScript())
                assertTrue(session.isDirty)
                assertNotEquals(initialRevision, session.snapshot().globalScriptRevision)

                session.save()
                assertFalse(session.isDirty)
                session.updateGlobalScript("var_score = 2")
                assertTrue(session.isDirty)

                assertTrue(session.undo())
                assertEquals("var_score = 1", session.globalScript())
                assertFalse(session.isDirty)
                assertTrue(session.redo())
                assertEquals("var_score = 2", session.globalScript())
                assertTrue(session.isDirty)
            }
        }

    @Test
    fun blankGlobalScriptDeletesFileAndCanBeUndone() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            session.updateGlobalScript("var_enabled = true")
            val script = File(session.draftDirectory, "scripts/script.txt")
            assertTrue(script.isFile)

            session.updateGlobalScript("")
            assertFalse(script.exists())
            assertEquals("", session.globalScript())
            assertTrue(session.undo())
            assertTrue(script.isFile)
            assertEquals("var_enabled = true", session.globalScript())
        }
    }

    @Test
    fun oversizedGlobalScriptIsRejectedWithoutChangingExistingContent() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                session.updateGlobalScript("keep me")
                val tooLarge = "界".repeat(WatchEditorSession.MAX_WATCH_SCRIPT_BYTES / 3 + 1)

                assertEditorFailure { session.updateGlobalScript(tooLarge) }

                assertEquals("keep me", session.globalScript())
                assertEquals("keep me", File(session.draftDirectory, "scripts/script.txt").readText())
            }
        }

    @Test
    fun exportedWatchContainsUpdatedScriptAndOmitsDeletedScript() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                session.updateGlobalScript("var_exported = 42")
                val withScript = File(root, "with-script.watch")
                session.exportWatch(withScript)
                ZipFile(withScript).use { zip ->
                    val entry = zip.getEntry("scripts/script.txt")
                    assertTrue(entry != null)
                    assertEquals(
                        "var_exported = 42",
                        zip.getInputStream(entry).use { it.readBytes().toString(StandardCharsets.UTF_8) },
                    )
                }
                assertFalse(session.isDirty)

                session.updateGlobalScript("")
                val withoutScript = File(root, "without-script.watch")
                session.exportWatch(withoutScript)
                ZipFile(withoutScript).use { zip ->
                    assertTrue(zip.getEntry("scripts/script.txt") == null)
                }
                assertFalse(session.isDirty)
            }
        }

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
    fun installedAtMetadataNeverLeaksIntoDraftOrExportedWatch() =
        withTemporaryDirectory { root ->
            val installed = File(root, "installed").apply { mkdirs() }
            File(installed, "watch.xml").writeText(SOURCE_XML)
            File(installed, ".flue/installed-at").apply {
                parentFile.mkdirs()
                writeText("1700000000000")
            }

            WatchEditorSession.openInstalled(installed, File(root, "cache")).use { session ->
                assertFalse(File(session.draftDirectory, ".flue").exists())
                // Defense in depth: even an internal file created after open
                // must not enter draft accounting or the exported archive.
                File(session.draftDirectory, ".flue/installed-at").apply {
                    parentFile.mkdirs()
                    writeText("1800000000000")
                }
                val output = File(root, "metadata-safe.watch")
                session.exportWatch(output)

                ZipFile(output).use { zip ->
                    assertTrue(
                        zip.entries().asSequence().none {
                            it.name == ".flue" || it.name.startsWith(".flue/")
                        },
                    )
                }
                assertEquals(
                    "Original",
                    WatchArchiveInstaller().install(output, File(root, "reinstalled"))
                        .definition.name,
                )
            }
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
    fun wrappedLayersRemainEditableAndMalformedParentLinksDoNotProduceZeroLayers() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(
                """
                <Watch name="Wrapped" build_source="studio">
                    <Layers vendor-container="kept">
                        <Layer id="duplicate" type="image" x="1"/>
                        <Layer id="duplicate" type="text" parentId="missing" text="Before"/>
                    </Layers>
                </Watch>
                """.trimIndent(),
            )

            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                val initial = session.snapshot()
                assertEquals(2, initial.layers.size)
                assertEquals(listOf("image", "text"), initial.layers.map { it.type })

                session.updateLayerAttribute(initial.layers[1].key, "text", "After")
                val added = session.addLayer("shape", mapOf("id" to "new-shape"))
                assertEquals(3, session.snapshot().layers.size)
                assertEquals("shape", session.snapshot().layers.first { it.key == added }.type)
                session.save()

                val saved = session.watchXmlFile.readText()
                assertTrue(saved.contains("vendor-container=\"kept\""))
                assertTrue(saved.contains("text=\"After\""))
                val layersEnd = saved.indexOf("</Layers>")
                val addedPosition = saved.indexOf("id=\"new-shape\"")
                assertTrue(addedPosition >= 0 && addedPosition < layersEnd)
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
    fun exportRootOverridesChangeArchiveWithoutMutatingDraftOrInstalledFace() =
        withTemporaryDirectory { root ->
            val installed = File(root, "installed").apply { mkdirs() }
            val installedXml = File(installed, "watch.xml").apply {
                writeText(
                    SOURCE_XML.replace(
                        "build_source=\"studio\"",
                        "build_source=\"studio\" designer_name=\"Original Designer\" " +
                            "author=\"Original Author\"",
                    ),
                )
            }
            val originalInstalledBytes = installedXml.readBytes()

            WatchEditorSession.openInstalled(installed, File(root, "cache")).use { session ->
                val output = File(root, "upload.watch")
                session.exportWatch(
                    output,
                    rootAttributeOverrides = mapOf(
                        "designer_name" to "Upload Designer",
                        "author" to "Upload Author",
                    ),
                )

                ZipFile(output).use { zip ->
                    val xml = zip.getInputStream(zip.getEntry("watch.xml"))
                        .use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    assertTrue(xml.contains("designer_name=\"Upload Designer\""))
                    assertTrue(xml.contains("author=\"Upload Author\""))
                    assertFalse(xml.contains("designer_name=\"Original Designer\""))
                    assertFalse(xml.contains("author=\"Original Author\""))
                }

                val snapshot = session.snapshot()
                assertEquals("Original Designer", snapshot.rootAttributes["designer_name"])
                assertEquals("Original Author", snapshot.rootAttributes["author"])
                val draftXml = session.watchXmlFile.readText()
                assertTrue(draftXml.contains("designer_name=\"Original Designer\""))
                assertTrue(draftXml.contains("author=\"Original Author\""))
                assertTrue(originalInstalledBytes.contentEquals(installedXml.readBytes()))
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
                // 必须真的被图层引用：导出会裁掉没人引用的素材，只 import 不挂图层
                // 的话这份 zeros.png 根本进不了包，压缩比守卫也就不会触发。
                val zeros = session.importResource(ByteArrayInputStream(ByteArray(128 * 1024)), "zeros.png")
                session.addLayer("image", mapOf("path" to watchXmlAssetPath(zeros, "images")))
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
    fun deletingLoopingBackgroundRemovesFramesButUndoRestoresThem() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val layer = session.addLayer("image_gif", mapOf("id" to "loop"))
                val sourceFrames = (1..3).map { index ->
                    File(root, "frame_$index.jpg").apply { writeBytes(ByteArray(16) { index.toByte() }) }
                }
                val imported = session.importVideoFrameSequence(layer, sourceFrames, frameDelayMs = 50)
                assertTrue(imported.all { File(session.draftDirectory, it).isFile })

                assertTrue(session.deleteLayer(layer))
                assertTrue(imported.none { File(session.draftDirectory, it).exists() })

                assertTrue(session.undo())
                assertTrue(imported.all { File(session.draftDirectory, it).isFile })
                assertEquals(layer, session.snapshot().layers.single().key)
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

    @Test
    fun transformPreviewCreatesExactlyOneUndoEntryOnCommit() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val key = session.addLayer(
                    type = "shape",
                    attributes = mapOf("id" to "shape", "x" to "4", "y" to "6"),
                )
                val original = session.layerTransform(key)
                session.beginLayerTransformGesture(key)
                session.updateLayerTransformPreview(
                    key,
                    original.copy(
                        x = 40f,
                        y = 60f,
                        scaleX = 1.8f,
                        scaleY = 1.2f,
                        rotation = 22f,
                    ),
                )
                session.updateLayerTransformPreview(
                    key,
                    original.copy(
                        x = 80f,
                        y = 90f,
                        scaleX = 2.2f,
                        scaleY = 1.6f,
                        rotation = 48f,
                    ),
                )
                assertTrue(session.commitLayerTransformGesture(key))
                assertEquals(80f, session.layerTransform(key).x, 0f)
                assertEquals(48f, session.layerTransform(key).rotation, 0f)

                assertTrue(session.undo())
                assertEquals(original, session.layerTransform(key))
                assertTrue(session.redo())
                assertEquals(2.2f, session.layerTransform(key).scaleX, 0f)
                assertEquals(1.6f, session.layerTransform(key).scaleY, 0f)
            }
        }

    @Test
    fun failedTransformBeginDoesNotPublishAnActiveGesture() =
        withTemporaryDirectory { root ->
            WatchEditorSession.createBlank(File(root, "cache")).use { session ->
                val staleKey = session.addLayer("shape")
                assertTrue(session.deleteLayer(staleKey))

                try {
                    session.beginLayerTransformGesture(staleKey)
                    fail("Expected stale layer key to be rejected")
                } catch (_: IllegalArgumentException) {
                    // Expected.
                }

                assertFalse(session.cancelLayerTransformGesture(staleKey))
            }
        }

    @Test
    fun transformUsesWatchmakerPercentAxesAndPreservesNonUniformScale() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(
                """
                <Watch name="Scale">
                    <Layer
                        id="scaled"
                        type="image"
                        scale="250"
                        anim_scale_x="150"
                        anim_scale_y="80"
                    />
                </Watch>
                """.trimIndent(),
            )

            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                val key = session.snapshot().layers.single().key
                val original = session.layerTransform(key)
                assertEquals(1.5f, original.scaleX, 0f)
                assertEquals(0.8f, original.scaleY, 0f)

                session.beginLayerTransformGesture(key)
                session.updateLayerTransformPreview(
                    key,
                    original.copy(scaleX = 3f, scaleY = 1.6f),
                )
                assertTrue(session.commitLayerTransformGesture(key))
                session.save()

                val saved = session.watchXmlFile.readText()
                assertTrue(saved.contains("anim_scale_x=\"300\""))
                assertTrue(saved.contains("anim_scale_y=\"160\""))
                assertTrue(saved.contains("scale=\"250\""))

                assertTrue(session.undo())
                assertEquals(1.5f, session.layerTransform(key).scaleX, 0f)
                assertEquals(0.8f, session.layerTransform(key).scaleY, 0f)
                assertTrue(session.redo())
                assertEquals(3f, session.layerTransform(key).scaleX, 0f)
                assertEquals(1.6f, session.layerTransform(key).scaleY, 0f)
            }
        }

    @Test
    fun interruptedTransformRollsBackBeforeTheCompetingMutation() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(
                """
                <Watch name="Before">
                    <Layer
                        id="scaled"
                        type="image"
                        x="10"
                        anim_scale_x="150"
                        anim_scale_y="80"
                    />
                </Watch>
                """.trimIndent(),
            )

            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                val key = session.snapshot().layers.single().key
                val original = session.layerTransform(key)

                session.beginLayerTransformGesture(key)
                session.updateLayerTransformPreview(
                    key,
                    original.copy(x = 90f, scaleX = 3f, scaleY = 1.6f),
                )
                session.updateRootAttribute("name", "After")

                assertEquals(original, session.layerTransform(key))
                assertEquals("After", session.snapshot().rootAttributes["name"])
                assertFalse(session.cancelLayerTransformGesture(key))
                assertTrue(session.undo())
                assertEquals("Before", session.snapshot().rootAttributes["name"])
                assertEquals(original, session.layerTransform(key))
            }
        }

    @Test
    fun multipleWrapperMutationsKeepDocumentOrderUnknownXmlAndStableKeys() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(
                """
                <Watch name="Wrapped" build_source="studio">
                    <Layers vendor="first">
                        <Layer id="a" type="group">
                            <VendorPayload keep="yes">
                                <Layer id="extension-owned-by-a" type="vendor"/>
                            </VendorPayload>
                        </Layer>
                        <UnknownFirst keep="1"/>
                    </Layers>
                    <!--between-wrappers-->
                    <VendorFolder keep="2">
                        <Layer id="b" type="image"/>
                    </VendorFolder>
                    <Layers vendor="last">
                        <UnknownLast keep="3"/>
                        <Layer id="c" type="text"/>
                    </Layers>
                </Watch>
                """.trimIndent(),
            )

            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                val initial = session.snapshot()
                assertEquals(listOf("a", "b", "c"), initial.layers.map { it.id })
                val originalKeys = initial.layers.associate {
                    requireNotNull(it.id) to it.key
                }

                val added = session.addLayer("shape", mapOf("id" to "d"))
                val duplicate = session.duplicateLayer(originalKeys.getValue("a"))
                assertEquals(
                    listOf("a", "b", "c", "d", "a_copy"),
                    session.snapshot().layers.map { it.id },
                )
                assertEquals("d", session.snapshot().layers.first { it.key == added }.id)
                assertEquals("a_copy", session.snapshot().layers.first { it.key == duplicate }.id)

                assertTrue(session.reorderLayer(originalKeys.getValue("a"), 4))
                assertEquals(
                    listOf("b", "c", "d", "a_copy", "a"),
                    session.snapshot().layers.map { it.id },
                )
                originalKeys.forEach { (id, key) ->
                    assertEquals(id, session.snapshot().layers.first { it.key == key }.id)
                }

                assertTrue(session.deleteLayer(originalKeys.getValue("b")))
                assertTrue(session.undo())
                assertEquals(
                    "b",
                    session.snapshot().layers
                        .first { it.key == originalKeys.getValue("b") }
                        .id,
                )
                assertTrue(session.redo())
                assertFalse(
                    session.snapshot().layers.any {
                        it.key == originalKeys.getValue("b")
                    },
                )

                session.save()
                val saved = session.watchXmlFile.readText()
                assertTrue(saved.contains("VendorPayload"))
                assertTrue(saved.contains("extension-owned-by-a"))
                assertTrue(saved.contains("UnknownFirst"))
                assertTrue(saved.contains("UnknownLast"))
                assertTrue(saved.contains("between-wrappers"))
                assertTrue(saved.indexOf("id=\"c\"") < saved.indexOf("id=\"d\""))
                assertTrue(saved.indexOf("id=\"d\"") < saved.indexOf("id=\"a_copy\""))
                assertTrue(saved.indexOf("id=\"a_copy\"") < saved.indexOf("id=\"a\""))
            }
        }

    @Test
    fun emptyNestedLayersContainerReceivesTheFirstEditableLayer() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(
                """
                <Watch name="Nested Empty">
                    <VendorFolder keep="yes">
                        <Layers vendor="nested">
                            <Unknown keep="yes"/>
                        </Layers>
                    </VendorFolder>
                </Watch>
                """.trimIndent(),
            )

            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                session.addLayer("shape", mapOf("id" to "inside"))
                session.save()

                val saved = session.watchXmlFile.readText()
                assertTrue(saved.indexOf("vendor=\"nested\"") < saved.indexOf("id=\"inside\""))
                assertTrue(saved.indexOf("id=\"inside\"") < saved.indexOf("</Layers>"))
                assertTrue(saved.contains("Unknown"))
            }
        }

    @Test
    fun revisionBackupCleanupRequiresPublishedOrRestoredTarget() {
        assertFalse(
            shouldDeleteWatchRevisionBackup(
                originalMoved = true,
                targetPublished = false,
                backupRestored = false,
            ),
        )
        assertTrue(
            shouldDeleteWatchRevisionBackup(
                originalMoved = true,
                targetPublished = true,
                backupRestored = false,
            ),
        )
        assertTrue(
            shouldDeleteWatchRevisionBackup(
                originalMoved = true,
                targetPublished = false,
                backupRestored = true,
            ),
        )
        assertTrue(
            shouldDeleteWatchRevisionBackup(
                originalMoved = false,
                targetPublished = false,
                backupRestored = false,
            ),
        )
    }

    @Test
    fun layerTreeMatchesRuntimeParentResolutionAndUsesStableCollapseKeys() {
        fun layer(
            key: String,
            id: String?,
            type: String,
            parentId: String?,
            order: Int,
        ) = WatchEditorLayer(
            key = WatchEditorLayerKey(key),
            id = id,
            type = type,
            parentId = parentId,
            attributes = emptyMap(),
            childElementNames = emptyList(),
            order = order,
        )

        val layers = listOf(
            layer("first-duplicate", "duplicate", "group", null, 0),
            layer("second-duplicate", "duplicate", "group", null, 1),
            layer("duplicate-child", "duplicate-child", "image", "duplicate", 2),
            layer("not-a-group", "not-a-group", "image", null, 3),
            layer("invalid-child", "invalid-child", "image", "not-a-group", 4),
            layer("self", "self", "group", "self", 5),
            layer("cycle-a", "cycle-a", "group", "cycle-b", 6),
            layer("cycle-b", "cycle-b", "group", "cycle-a", 7),
            layer("missing", "missing", "image", "absent", 8),
        )

        val rows = buildWatchEditorTreeRows(layers)
        assertEquals(layers.size, rows.size)
        assertEquals(1, rows.single { it.layer.key.value == "duplicate-child" }.depth)
        assertEquals(0, rows.single { it.layer.key.value == "invalid-child" }.depth)
        assertEquals(0, rows.single { it.layer.key.value == "self" }.depth)
        assertEquals(0, rows.single { it.layer.key.value == "cycle-a" }.depth)
        assertEquals(0, rows.single { it.layer.key.value == "cycle-b" }.depth)
        assertEquals(
            "duplicate-child",
            rows[rows.indexOfFirst { it.layer.key.value == "first-duplicate" } + 1].layer.key.value,
        )

        val collapseSecond = buildWatchEditorTreeRows(
            layers,
            collapsedKeys = setOf("second-duplicate"),
        )
        assertTrue(collapseSecond.any { it.layer.key.value == "duplicate-child" })
        val collapseFirst = buildWatchEditorTreeRows(
            layers,
            collapsedKeys = setOf("first-duplicate"),
        )
        assertFalse(collapseFirst.any { it.layer.key.value == "duplicate-child" })
    }

    @Test
    fun layerTreeUsesParentIdsAndCanCollapseChildren() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(
                """
                <Watch name="Tree">
                    <Layer id="group" type="group"/>
                    <Layer id="text" type="text" parentId="group"/>
                </Watch>
                """.trimIndent(),
            )
            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                val layers = session.snapshot().layers
                val rows = buildWatchEditorTreeRows(layers)
                assertEquals("group", rows[0].layer.id)
                assertEquals(0, rows[0].depth)
                assertEquals("text", rows[1].layer.id)
                assertEquals(1, rows[1].depth)
                assertTrue(rows[0].hasChildren)

                val collapsed = buildWatchEditorTreeRows(
                    layers,
                    collapsedKeys = setOf(layers.first().key.value),
                )
                assertTrue(collapsed.none { it.layer.id == "text" })
                assertTrue(collapsed.first { it.layer.id == "group" }.hasChildren)
            }
        }

    @Test
    fun savingCurrentRevisionKeepsLogicalDirectoryAndMetadata() =
        withTemporaryDirectory { root ->
            val installed = File(root, "installed").apply { mkdirs() }
            File(installed, "watch.xml").writeText(SOURCE_XML)
            File(installed, ".flue/installed-at").apply {
                parentFile.mkdirs()
                writeText("1700000000000")
            }
            val originalDirectory = installed.canonicalFile
            WatchEditorSession.openInstalled(
                sourceDirectory = installed,
                cacheDirectory = File(root, "cache"),
                logicalId = "watchmaker:installed",
                installedDirectory = installed,
                removeDraftOnClose = false,
            ).use { session ->
                assertTrue(session.canSaveCurrentRevision)
                session.updateRootAttribute("name", "Revision")
                session.saveCurrentRevision()
                assertEquals(originalDirectory, installed.canonicalFile)
                assertTrue(File(installed, ".flue/installed-at").isFile)
                assertTrue(File(installed, "watch.xml").readText().contains("name=\"Revision\""))
                assertFalse(session.isDirty)
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

    /**
     * 编辑器的 `definitionIndex` 必须与渲染器解析器的扁平表逐项对齐。
     *
     * 这是「拖文字只有选中框跟手、像素不动」的判据：编辑器只数顶层图层，解析器
     * （`WatchmakerDefinitionParser.collectLayers`）前序递归、把分组里的子图层也收进
     * 同一张表。两边一错位，`setLiveLayerTransform` 就把实时变换按到别的层上；
     * 有 `id` 的层靠 id 匹配蒙对了，没写 `id` 的层（文字层常见）就完全不跟手。
     */
    @Test
    fun layerDefinitionIndexMatchesRendererFlattenOrder() = withTemporaryDirectory { root ->
        val installed = File(root, "installed").apply { mkdirs() }
        File(installed, "watch.xml").writeText(NESTED_GROUP_XML)

        WatchEditorSession.openInstalled(installed, File(root, "cache")).use { session ->
            val editorLayers = session.snapshot().layers
            val rendererLayers = WatchmakerDefinitionParser()
                .parse(session.watchXmlFile)
                .layers

            // 顶层图层三个（分组 / 无 id 的文字 / 图片），渲染器那张表因为多了两个
            // 嵌套子层而是五个 —— 正是会让 order 和下标分道扬镳的形状。
            assertEquals(3, editorLayers.size)
            assertEquals(5, rendererLayers.size)

            editorLayers.forEach { layer ->
                val rendererLayer = rendererLayers[layer.definitionIndex]
                assertEquals(layer.id, rendererLayer.attributes["id"])
                assertEquals(layer.type, rendererLayer.type)
            }

            // 具体数值也钉住，避免「两边一起错、测试照样绿」。
            assertEquals(listOf(0, 1, 2), editorLayers.map { it.order })
            assertEquals(listOf(0, 3, 4), editorLayers.map { it.definitionIndex })
            // 没有 id 的文字层是回归的重点：它只能按下标匹配。
            val text = editorLayers.first { it.type == "text" }
            assertEquals(null, text.id)
            assertEquals("text", rendererLayers[text.definitionIndex].type)
        }
    }

    /**
     * 写进 watch.xml 的 `path` 必须是**约定目录内的裸文件名**。
     *
     * 规范出处：`WM_FORMAT_1.0.md` §7.1（image「Filename inside images/」）、
     * §7.14（video「Filename inside videos/」）；上游两个真实样本包都是裸名。
     * 带上 `images/` 前缀的话，官方 WatchMaker / CloX / wftools 会去找
     * `images/images/foo.png` —— 图片、帧序列、视频全渲染成空层。
     *
     * Flue 自己看不出来（`resolveAsset` 先按原样相对 watch.xml 解一次正好命中），
     * 所以这条只能靠测试守住。
     */
    @Test
    fun importedResourcesAreReferencedByBareFileName() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val imagePath = session.importResource(
                ByteArrayInputStream(pngHeader + byteArrayOf(1, 2, 3, 4)),
                "hand.png",
            )
            // 仓库层仍然按草稿相对路径落盘 —— 那是文件 I/O 要的形状。
            assertEquals("images/hand.png", imagePath)

            val layer = session.addLayer(
                type = "image",
                attributes = mapOf("path" to watchXmlAssetPath(imagePath, "images")),
            )
            session.flushDraftForPreview()
            val snapshotLayer = session.snapshot().layers.first { it.key == layer }
            assertEquals("hand.png", snapshotLayer.attributes["path"])
            // 落盘的 XML 里也必须是裸名，不能只是快照层做了转换。
            assertTrue(session.watchXmlFile.readText().contains("path=\"hand.png\""))
        }
    }

    /** 纯函数本身的边界：只剥一层约定目录，子目录与已是裸名的情形都不动。 */
    @Test
    fun watchXmlAssetPathStripsOnlyTheConventionalDirectory() {
        assertEquals("hand.png", watchXmlAssetPath("images/hand.png", "images"))
        assertEquals("clip.mp4", watchXmlAssetPath("videos/clip.mp4", "videos"))
        // 已经是裸名：原样返回。
        assertEquals("hand.png", watchXmlAssetPath("hand.png", "images"))
        // 约定目录下的子目录必须保留 —— 一律取 basename 会让同名文件撞车。
        assertEquals("hands/big.png", watchXmlAssetPath("images/hands/big.png", "images"))
        // 反斜杠与前导斜杠都归一化。
        assertEquals("hand.png", watchXmlAssetPath("images\\hand.png", "images"))
        assertEquals("hand.png", watchXmlAssetPath("/images/hand.png", "images"))
        // 目录名大小写不敏感（作者手写的 XML 里出现过 Images/）。
        assertEquals("hand.png", watchXmlAssetPath("Images/hand.png", "images"))
        // 不是该目录的东西不能被剥。
        assertEquals("fonts/x.ttf", watchXmlAssetPath("fonts/x.ttf", "images"))
    }

    /**
     * 拖动绑了表达式的图层：数值轴照写，表达式轴原样保留。
     *
     * 回归的是**数据丢失**：`rotation="{drss}"` 被 `layerTransform` 读成 0f，
     * 放手时如果无条件写回就变成 `rotation="0"`，秒针永久停住，撤销栈里也只有
     * 「移动图层」这一步可回退，作者不会想到自己丢了动画。
     */
    @Test
    fun draggingALayerNeverOverwritesExpressionBoundTransformAttributes() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(
                """
                <Watch name="Bound">
                    <Layer
                        id="second-hand"
                        type="image"
                        x="10"
                        y="20"
                        rotation="{drss}"
                        anim_scale="{bl}"
                    />
                    <Layer id="plain" type="image" x="1" y="2" rotation="30"/>
                </Watch>
                """.trimIndent(),
            )

            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                val bound = session.snapshot().layers.first { it.id == "second-hand" }.key
                assertEquals(
                    setOf("rotation", "anim_scale_x", "anim_scale_y"),
                    session.layerBoundTransformAttributes(bound),
                )

                session.beginLayerTransformGesture(bound)
                session.updateLayerTransformPreview(
                    bound,
                    WatchEditorTransform(x = 90f, y = 95f, scaleX = 3f, scaleY = 3f, rotation = 45f),
                )
                assertTrue(session.commitLayerTransformGesture(bound))
                session.save()

                val saved = session.watchXmlFile.readText()
                // 位置是纯数字，拖动照常生效。
                assertTrue(saved.contains("x=\"90\""))
                assertTrue(saved.contains("y=\"95\""))
                // 绑定原样保留，且不能被 anim_scale_x 遮蔽。
                assertTrue(saved.contains("rotation=\"{drss}\""))
                assertTrue(saved.contains("anim_scale=\"{bl}\""))
                assertFalse(saved.contains("anim_scale_x="))
                assertFalse(saved.contains("anim_scale_y="))

                // 没有绑定的图层行为完全不变。
                val plain = session.snapshot().layers.first { it.id == "plain" }.key
                assertEquals(emptySet<String>(), session.layerBoundTransformAttributes(plain))
                session.beginLayerTransformGesture(plain)
                session.updateLayerTransformPreview(
                    plain,
                    session.layerTransform(plain).copy(rotation = 45f),
                )
                assertTrue(session.commitLayerTransformGesture(plain))
                assertEquals(45f, session.layerTransform(plain).rotation, 0f)
            }
        }

    /**
     * 空白草稿的根节点只写规范属性。
     *
     * `width` / `height` 不在 1.0 §3 的根属性表里（§4 把画布固定成 512×512）。
     * 写了它们不会让任何官方渲染器读到，却会让 Flue 自己的
     * `WatchCanvasSize.fromRootAttributes` 走「声明尺寸」分支 —— 同一份 XML 在
     * Flue 和在 WatchMaker 里可能按不同设计空间布局。两者都缺失时那个函数正好
     * 回落 512×512，所以省掉是零行为变化。
     */
    @Test
    fun aBlankDraftRootCarriesOnlySpecAttributes() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache"), name = "Blank").use { session ->
            val attributes = session.snapshot().rootAttributes
            assertEquals(setOf("name", "shape"), attributes.keys)
            assertTrue("width" !in session.watchXmlFile.readText())
            // 画布仍然是原生 512×512。
            assertEquals(WatchCanvasSize.DEFAULT, WatchCanvasSize.fromRootAttributes(attributes))
        }
    }

    /**
     * 2.0 文档里新建/复制出来的图层必须带唯一 `id` 和显示名 `name`。
     *
     * 2.0 的层级关系靠 `parentId` **引用**而不是嵌套（§6.1、§7），没有 `id` 的图层
     * 永远不能当父层，拿回 Studio 里也就没法把别的层拖进去。1.0 没这两项，不能乱补。
     */
    @Test
    fun studioDocumentsGiveEveryNewLayerAUniqueIdAndDisplayName() = withTemporaryDirectory { root ->
        val source = File(root, "source").apply { mkdirs() }
        File(source, "watch.xml").writeText(
            """
            <Watch name="Studio" build_source="studio" shape="R">
                <Layer id="group" type="group" name="Group 1">
                    <Payload><Layer id="nested" type="text" name="Text 1"/></Payload>
                </Layer>
            </Watch>
            """.trimIndent(),
        )

        WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
            val added = session.addLayer("text")
            val addedLayer = session.snapshot().layers.first { it.key == added }
            val id = addedLayer.id
            assertTrue("2.0 图层必须有 id", !id.isNullOrBlank())
            assertTrue("id 应是 Studio 形状: $id", id!!.startsWith("layer-"))
            // 唯一性要对整份文档成立，嵌套层也算。
            assertNotEquals("nested", id)
            assertNotEquals("group", id)
            // name 自动取 "<Type> N"，且不能撞上已有的 "Text 1"。
            assertEquals("Text 2", addedLayer.attributes["name"])

            val duplicate = session.duplicateLayer(added)
            val duplicateId = session.snapshot().layers.first { it.key == duplicate }.id
            assertNotEquals(id, duplicateId)
            assertTrue("副本必须有 id", !duplicateId.isNullOrBlank())
        }
    }

    /** 1.0 文档不能被补上 2.0 才有的 id / name。 */
    @Test
    fun formatOneDocumentsDoNotGainStudioOnlyIdentityAttributes() = withTemporaryDirectory { root ->
        val source = File(root, "source").apply { mkdirs() }
        File(source, "watch.xml").writeText("""<Watch name="Plain" shape="R"/>""")

        WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
            val added = session.addLayer("text")
            val layer = session.snapshot().layers.first { it.key == added }
            assertEquals(null, layer.id)
            assertTrue("1.0 不该有 name", "name" !in layer.attributes)
        }
    }

    /**
     * 导出时必须重算 `features`、补齐 `ucolor_default`，并且不打包没人引用的素材。
     *
     * 三件事都属于「打包方的责任」：`features` 是市场唯一的内容索引（规范说每次导出
     * 覆盖）；缺 `ucolor_default` 时用了 `{ucolor}` 的图层在别的渲染器里没颜色；
     * 而编辑过程中被换掉的旧图会一直留在草稿里，不裁就跟着包一起发出去。
     */
    @Test
    fun exportRecomputesFeaturesFillsAccentColourAndDropsOrphanAssets() =
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            File(source, "images").mkdirs()
            File(source, "images/used.png").writeBytes(pngHeader + byteArrayOf(1))
            File(source, "images/orphan.png").writeBytes(pngHeader + byteArrayOf(2))
            File(source, "fonts").mkdirs()
            File(source, "fonts/Bevel.ttf").writeBytes(byteArrayOf(0, 1, 2))
            File(source, "preview.jpg").writeBytes(byteArrayOf(9, 9, 9))
            // 进来时带着一份过时的 features：改完必须被覆盖，不能原样带出去。
            File(source, "watch.xml").writeText(
                """
                <Watch name="Meta" shape="R" features="Heart Rate,Steps">
                    <Layer type="image" path="used.png" rotation="{drss}"/>
                    <Layer type="text" text="{wt} {dn}" font="Bevel"/>
                </Watch>
                """.trimIndent(),
            )

            WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
                val output = File(root, "out.watch")
                session.exportWatch(output)

                ZipFile(output).use { archive ->
                    val names = archive.entries().asSequence().map { it.name }.toSet()
                    // 引用到的图和字体都在；没人引用的图被裁掉。
                    assertTrue("$names", "images/used.png" in names)
                    assertTrue("$names", "fonts/Bevel.ttf" in names)
                    assertTrue("orphan 不该进包: $names", "images/orphan.png" !in names)
                    assertTrue("$names", "preview.jpg" in names)

                    val xml = archive.getInputStream(archive.getEntry("watch.xml"))
                        .use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    // 1.0 文档写人类可读名，字母序。{drss}→Analog、{wt}→Weather、{dn}→Date。
                    assertTrue(xml, xml.contains("features=\"Analog,Date,Weather\""))
                    assertTrue(xml, xml.contains("ucolor_default=\"ffffff\""))
                }
            }
        }

    /** 调用方显式给了 `features` 时不覆盖：那是上传流程刻意附加的元数据。 */
    @Test
    fun anExplicitFeaturesOverrideWinsOverRecomputation() = withTemporaryDirectory { root ->
        val source = File(root, "source").apply { mkdirs() }
        File(source, "watch.xml").writeText(
            """<Watch name="Override" shape="R"><Layer type="text" text="{wt}"/></Watch>""",
        )

        WatchEditorSession.openInstalled(source, File(root, "cache")).use { session ->
            val output = File(root, "out.watch")
            session.exportWatch(output, rootAttributeOverrides = mapOf("features" to "Curated"))

            ZipFile(output).use { archive ->
                val xml = archive.getInputStream(archive.getEntry("watch.xml"))
                    .use { it.readBytes().toString(StandardCharsets.UTF_8) }
                assertTrue(xml, xml.contains("features=\"Curated\""))
            }
        }
    }

    /**
     * 图片导入后要写 `width` / `height`。
     *
     * 这两条对 image / image_gif 是必填（1.0 §7.1，商店的 `requiredAttributes` 同款）。
     * 默认属性表里**不能**预填 —— 那会破坏「没有尺寸 = 按素材比例自动适配」这条约定。
     * 所以补的时机只能是素材落盘之后，尺寸按 contain 语义装进 512 设计空间。
     */
    @Test
    fun importedImagesReceiveContainedDisplayDimensions() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val layer = session.addLayer("image")
            // 1024×512 的素材：长边贴边 → 512×256。
            session.applyImportedImageSize(layer, pixelWidth = 1024, pixelHeight = 512)

            val attributes = session.snapshot().layers.first { it.key == layer }.attributes
            assertEquals("512", attributes["width"])
            assertEquals("256", attributes["height"])

            // 已经有尺寸的图层默认不动：用户手调过的盒子不能被下一次导入覆盖。
            session.applyImportedImageSize(layer, pixelWidth = 100, pixelHeight = 100)
            assertEquals("512", session.snapshot().layers.first { it.key == layer }.attributes["width"])
            // 明确要求覆盖时才改。
            session.applyImportedImageSize(layer, pixelWidth = 100, pixelHeight = 100, overwriteSize = true)
            assertEquals("512", session.snapshot().layers.first { it.key == layer }.attributes["height"])
        }
    }

    /**
     * 换掉或删掉 video 图层时，旧的视频文件必须从草稿里消失。
     *
     * 回收逻辑（`layerOwnedResources`）以前只认序列帧，video 那一支是漏的：给同一个
     * 图层换第二段视频、或者把图层整个删掉，第一段 mp4 会一直留在包里
     * （老师报的「上一个视频还会存在表盘文件里不会删除」）。视频动辄几 MB，攒几次就
     * 把 20MB 的上传上限吃满，而作者在编辑器里根本看不到这些孤儿文件。
     */
    @Test
    fun replacingOrDeletingAVideoLayerReclaimsTheOldFile() = withTemporaryDirectory { root ->
        val first = File(root, "first.mp4").apply { writeBytes(ByteArray(2_048) { 1 }) }
        val second = File(root, "second.mp4").apply { writeBytes(ByteArray(2_048) { 2 }) }

        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val layer = session.addLayer("video")
            val firstPath = session.importVideoFile(layer, first, videoWidth = 512, videoHeight = 512)
            assertTrue(File(session.draftDirectory, firstPath).isFile)

            // 换第二段：第一段必须被回收。
            val secondPath = session.importVideoFile(layer, second, videoWidth = 512, videoHeight = 512)
            assertNotEquals(firstPath, secondPath)
            assertTrue("新视频应在草稿里", File(session.draftDirectory, secondPath).isFile)
            assertTrue(
                "旧视频没被回收：$firstPath",
                !File(session.draftDirectory, firstPath).exists(),
            )

            // 删图层：第二段也要被回收。
            assertTrue(session.deleteLayer(layer))
            assertTrue(
                "删图层后视频没被回收：$secondPath",
                !File(session.draftDirectory, secondPath).exists(),
            )

            // 撤销要能把它拿回来 —— 回收走的是 stash，不是硬删。
            assertTrue(session.undo())
            assertTrue(File(session.draftDirectory, secondPath).isFile)
        }
    }

    /**
     * 换掉或删掉**单张图片**图层时，旧图片同样要从草稿里消失。
     *
     * 与 video 那条同源的漏洞：`layerOwnedResources` 早先只认 video 和序列帧，单张
     * `image` 直接返回空集，于是替换十次背景就在包里攒十张再也不会被引用的图
     *（老师报「删除图片图层、替换图片时也应该和视频一样从表盘资源移除」）。
     */
    @Test
    fun replacingOrDeletingAnImageLayerReclaimsTheOldFile() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val firstPath = session.importResource(
                ByteArrayInputStream(ByteArray(1_024) { 1 }),
                "first.png",
            )
            val secondPath = session.importResource(
                ByteArrayInputStream(ByteArray(1_024) { 2 }),
                "second.png",
            )
            val layer = session.addLayer(
                "image",
                mapOf("path" to watchXmlAssetPath(firstPath, "images")),
            )
            assertTrue(File(session.draftDirectory, firstPath).isFile)

            // 换第二张：第一张必须被回收。
            session.updateLayerAttribute(layer, "path", watchXmlAssetPath(secondPath, "images"))
            assertTrue("新图片应在草稿里", File(session.draftDirectory, secondPath).isFile)
            assertTrue(
                "旧图片没被回收：$firstPath",
                !File(session.draftDirectory, firstPath).exists(),
            )

            // 删图层：第二张也要被回收。
            assertTrue(session.deleteLayer(layer))
            assertTrue(
                "删图层后图片没被回收：$secondPath",
                !File(session.draftDirectory, secondPath).exists(),
            )

            // 回收走的是 stash，撤销要能拿回来。
            assertTrue(session.undo())
            assertTrue(File(session.draftDirectory, secondPath).isFile)
        }
    }

    /**
     * 被**另一个图层共用**的图片不能因为其中一个图层换素材就被删掉。
     *
     * 这是把单张图片纳入回收之后最容易踩的坑：候选集只表示「这个图层引用过它」，
     * 真正的判据是 `stashUnreferencedResources` 先算一遍全文档引用集再过滤。少了那一步，
     * 两个图层共用一张背景时，改动其中一个就会把另一个渲染成空层。
     */
    @Test
    fun aSharedImageSurvivesWhenOnlyOneLayerStopsUsingIt() = withTemporaryDirectory { root ->
        WatchEditorSession.createBlank(File(root, "cache")).use { session ->
            val sharedPath = session.importResource(
                ByteArrayInputStream(ByteArray(1_024) { 7 }),
                "shared.png",
            )
            val replacementPath = session.importResource(
                ByteArrayInputStream(ByteArray(1_024) { 8 }),
                "other.png",
            )
            val shared = watchXmlAssetPath(sharedPath, "images")
            val first = session.addLayer("image", mapOf("path" to shared))
            session.addLayer("image", mapOf("path" to shared))

            // 只有第一个图层改走别的图，共用的那张仍被第二个图层引用着。
            session.updateLayerAttribute(first, "path", watchXmlAssetPath(replacementPath, "images"))

            assertTrue(
                "共用图片被误删了：$sharedPath",
                File(session.draftDirectory, sharedPath).isFile,
            )
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
        /**
         * 一个分组带两个子图层，后面跟着**没有 id** 的文字层与一个图片层。
         *
         * 这是 `order` 与 `definitionIndex` 会分叉的最小形状：编辑器看到 3 个顶层图层，
         * 渲染器的扁平表有 5 个。
         */
        private val NESTED_GROUP_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Watch name="Nested" shape="R">
                <Layer id="group" type="group">
                    <Layer id="child_a" type="image" path="images/a.png"/>
                    <Layer id="child_b" type="shape"/>
                </Layer>
                <Layer type="text" text="12:00"/>
                <Layer id="hand" type="image" path="images/hand.png"/>
            </Watch>
        """.trimIndent()

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
