package com.flue.launcher.watchface.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class WatchEditorFeedbackSessionTest {
    @Test fun cancellingTranslationAfterSessionCloseIsAnIdempotentNoOp() = withSession(
        """<Watch><Layer type="text" x="5"/></Watch>""",
    ) { session, _ ->
        session.beginLayerTranslationGesture(setOf(session.snapshot().layers.single().key))
        session.updateLayerTranslationPreview(10f, 0f)
        session.close()
        assertFalse(session.cancelLayerTranslationGesture())
    }

    @Test fun audioPickerIdentifiesSameStemResourcesByTheirCompleteNames() {
        assertEquals("play_sound:bell.mp3", watchEditorAudioTapAction("sfx/bell.mp3"))
        assertEquals("play_sound:bell.ogg", watchEditorAudioTapAction("sfx/bell.ogg"))
    }

    @Test fun movingAContiguousSelectionDownCrossesOneNeighbor() = withSession(
        """<Watch><Layer id="a" type="text"/><Layer id="b" type="text"/><Layer id="c" type="text"/><Layer id="d" type="text"/></Watch>""",
    ) { session, _ ->
        val selected = session.snapshot().layers.take(2).map { it.key }.toSet()
        assertTrue(session.moveLayersByOne(selected, 1))
        assertEquals(listOf("c", "a", "b", "d"), session.snapshot().layers.map { it.id })
        assertTrue(session.undo())
        assertEquals(listOf("a", "b", "c", "d"), session.snapshot().layers.map { it.id })
    }

    @Test fun movingAGroupUpCarriesItsChildrenAndStopsAtTheBoundary() = withSession(
        """<Watch><Layer id="a" type="text"/><Layer id="g" type="group"/><Layer id="c" parentId="g" type="text"/><Layer id="d" type="text"/></Watch>""",
    ) { session, _ ->
        val selected = setOf(session.snapshot().layers[1].key)
        assertTrue(session.moveLayersByOne(selected, -1))
        assertEquals(listOf("g", "c", "a", "d"), session.snapshot().layers.map { it.id })
        assertFalse(session.moveLayersByOne(selected, -1))
        assertTrue(session.undo())
        assertFalse(session.snapshot().canUndo)
    }

    @Test fun previewViewportKeepsHighResolutionDeviceAspectRatio() {
        assertEquals(0.45f, WatchEditorViewport(1080, 2400).aspectRatio, 0.00001f)
    }

    private fun withSession(xml: String, block: (WatchEditorSession, File) -> Unit) {
        val root = Files.createTempDirectory("editor-feedback").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "watch.xml").writeText(xml)
            WatchEditorSession.openInstalled(source, File(root, "cache")).use { block(it, root) }
        } finally { root.deleteRecursively() }
    }

    @Test fun batchDragPreservesExpressionsAndHasOneUndo() = withSession(
        """<Watch><Layer type="text" x="{dh}*2" y="3"/><Layer type="text" x="20" y="5"/></Watch>""",
    ) { session, _ ->
        val keys = session.snapshot().layers.map { it.key }.toSet()
        session.beginLayerTranslationGesture(keys)
        session.updateLayerTranslationPreview(7f, -2f)
        session.updateLayerTranslationPreview(9f, -4f)
        assertEquals(true, session.commitLayerTranslationGesture())
        val changed = session.snapshot().layers
        assertEquals("({dh}*2)+(9)", changed[0].attributes["x"])
        assertEquals("29", changed[1].attributes["x"])
        assertEquals("1", changed[1].attributes["y"])
        assertTrue(session.undo())
        assertEquals("{dh}*2", session.snapshot().layers[0].attributes["x"])
        assertFalse(session.snapshot().canUndo)
    }

    @Test fun batchDragSkipsSelectedDescendantAndCancelRestoresEverything() = withSession(
        """<Watch build_source="studio"><Layer id="g" type="group" x="1"/><Layer id="c" parentId="g" type="text" x="4"/></Watch>""",
    ) { session, _ ->
        session.beginLayerTranslationGesture(session.snapshot().layers.map { it.key }.toSet())
        session.updateLayerTranslationPreview(10f, 6f)
        assertEquals("11", session.snapshot().layers[0].attributes["x"])
        assertEquals("4", session.snapshot().layers[1].attributes["x"])
        assertEquals(true, session.cancelLayerTranslationGesture())
        assertEquals("1", session.snapshot().layers[0].attributes["x"])
        assertFalse(session.snapshot().dirty)
        assertFalse(session.snapshot().canUndo)
    }

    @Test fun setReorderKeepsOrderKeysAndGroupMembership() = withSession(
        """<Watch><Layer id="a" type="group"/><Layer id="b" parentId="a" type="text"/><Layer id="c" type="text"/><Layer id="d" type="text"/></Watch>""",
    ) { session, _ ->
        val before = session.snapshot().layers
        session.reorderLayers(setOf(before[0].key, before[1].key), 2)
        assertEquals(listOf("c", "d", "a", "b"), session.snapshot().layers.map { it.id })
        assertEquals("a", session.snapshot().layers.last().parentId)
        assertEquals(before[0].key, session.snapshot().layers[2].key)
        assertTrue(session.undo())
        assertEquals(before.map { it.key }, session.snapshot().layers.map { it.key })
    }

    @Test fun ambientOverridesRoundTripWithoutChangingActiveOrVendorPayload() = withSession(
        """<Watch build_source="studio"><Layer type="text" color="ffffff" x="5"><Ambient color="888888" vendor="keep"/><Vendor value="kept"/></Layer></Watch>""",
    ) { session, root ->
        val key = session.snapshot().layers.single().key
        // Ambient edits preserve unrelated active attributes and unknown child payloads.
        session.updateAmbientAttributes(key, mapOf("opacity" to "60", "x" to "12", "fontSize" to "30"))
        session.flushDraftForPreview()
        val xml = session.watchXmlFile.readText()
        assertTrue(xml.contains("vendor=\"keep\""))
        assertTrue(xml.contains("fontSize=\"30\""))
        assertEquals("5", session.snapshot().layers.single().attributes["x"])
        val out = File(root, "ambient.watch")
        session.exportWatch(out)
        ZipFile(out).use { zip ->
            val exported = zip.getInputStream(zip.getEntry("watch.xml")).reader().readText()
            assertTrue(exported.contains("Ambient"))
            assertTrue(exported.contains("fontSize=\"30\""))
            assertTrue(exported.contains("Vendor"))
        }
    }

    @Test fun reorderingASelectedGroupCarriesItsUnselectedChildren() = withSession(
        """<Watch><Layer id="a" type="group"/><Layer id="b" parentId="a" type="text"/><Layer id="c" type="text"/></Watch>""",
    ) { session, _ ->
        val keys = session.snapshot().layers.map { it.key }
        session.reorderLayers(setOf(keys[0]), 2)
        assertEquals(listOf("c", "a", "b"), session.snapshot().layers.map { it.id })
        assertEquals("a", session.snapshot().layers.last().parentId)
    }

    @Test fun droppingSelectionOnItsOwnMemberKeepsOrderAndHistory() = withSession(
        """<Watch><Layer id="a" type="text"/><Layer id="b" type="text"/><Layer id="c" type="text"/></Watch>""",
    ) { session, _ ->
        val before = session.snapshot().layers
        assertFalse(session.reorderLayers(setOf(before[0].key, before[1].key), 1))
        assertEquals(before.map { it.key }, session.snapshot().layers.map { it.key })
        assertFalse(session.snapshot().canUndo)
    }

    @Test fun audioActionProtectsReferencedAssetAndRemovalIsUndoable() = withSession(
        """<Watch><Layer type="text" tap_action="play_sound:bell"/></Watch>""",
    ) { session, _ ->
        val path = session.importResource(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "bell.mp3", "sfx")
        assertEquals("AUDIO", session.resourceInventory().single().kind.name)
        assertTrue(session.resourceInventory().single().referenced)
        assertFalse(session.deleteResource(path))
        session.updateLayerAttribute(session.snapshot().layers.single().key, "tap_action", "")
        assertTrue(session.deleteResource(path))
        assertTrue(session.undo())
        assertTrue(File(session.draftDirectory, path).exists())
    }

    @Test fun dragConvertsWorldDeltaThroughRotatedScaledParent() = withSession(
        """<Watch><Layer id="g" type="group" rotation="90" anim_scale="200"/><Layer parentId="g" type="text" x="10" y="20"/></Watch>""",
    ) { session, _ ->
        val key = session.snapshot().layers.last().key
        session.beginLayerTranslationGesture(setOf(key))
        session.updateLayerTranslationPreview(10f, 0f)
        session.commitLayerTranslationGesture()
        val attrs = session.snapshot().layers.last().attributes
        assertEquals(10f, attrs.getValue("x").toFloat(), 0.0001f)
        assertEquals(15f, attrs.getValue("y").toFloat(), 0.0001f)
    }

    @Test fun audioReferenceRetainsDotsInTheSoundStem() = withSession(
        """<Watch><Layer type="text" tap_action="play_sound:bell.v2"/></Watch>""",
    ) { session, _ ->
        val path = session.importResource(ByteArrayInputStream(byteArrayOf(1)), "bell.v2.mp3", "sfx")
        assertTrue(session.resourceInventory().single().referenced)
        assertFalse(session.deleteResource(path))
    }

    @Test fun audioReferenceRecognizesCaseInsensitiveProtocolPrefix() = withSession(
        """<Watch><Layer type="text" tap_action="PLAY_SOUND:Bell.v2"/></Watch>""",
    ) { session, _ ->
        val path = session.importResource(ByteArrayInputStream(byteArrayOf(1)), "Bell.v2.ogg", "sfx")
        assertTrue(session.resourceInventory().single().referenced)
        assertFalse(session.deleteResource(path))
    }

    @Test fun ambientDragChangesOnlyAmbientPositionAndUndoRestoresIt() = withSession(
        """<Watch build_source="studio"><Layer type="text" x="5"><Ambient x="10"/></Layer></Watch>""",
    ) { session, _ ->
        val key = session.snapshot().layers.single().key
        session.beginLayerTranslationGesture(setOf(key), mapOf(key to WatchEditorTransform(x = 10f)), true)
        session.updateLayerTranslationPreview(20f, 0f)
        session.commitLayerTranslationGesture()
        assertEquals("5", session.snapshot().layers.single().attributes["x"])
        assertEquals("30", session.snapshot().layers.single().ambientAttributes["x"])
        assertTrue(session.undo())
        assertEquals("10", session.snapshot().layers.single().ambientAttributes["x"])
    }

    @Test fun zeroTranslationLeavesUnspecifiedCoordinatesAndHistoryUntouched() = withSession(
        """<Watch><Layer type="text"/></Watch>""",
    ) { session, _ ->
        session.beginLayerTranslationGesture(setOf(session.snapshot().layers.single().key))
        session.updateLayerTranslationPreview(0f, 0f)
        assertEquals(false, session.commitLayerTranslationGesture())
        assertFalse(session.snapshot().dirty)
        assertFalse(session.snapshot().canUndo)
        assertFalse(session.snapshot().layers.single().attributes.containsKey("x"))
    }
}
