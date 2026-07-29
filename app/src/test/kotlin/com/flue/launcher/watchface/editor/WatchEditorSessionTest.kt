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
