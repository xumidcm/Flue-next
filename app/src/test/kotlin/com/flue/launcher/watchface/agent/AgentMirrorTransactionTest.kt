package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.tools.WatchEditorSessionAgentToolHost
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import com.flue.launcher.watchface.editor.WatchEditorSession
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentMirrorTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun xmlScriptAndNewImageCommitAsOneRevision() = fixture { session, host, face ->
        val revision = session.agentRevision()
        write(face, "images/new.png", byteArrayOf(1, 2, 3))
        write(face, "watch.xml", "<Watch><Layer type=\"image\" path=\"new.png\"/></Watch>".toByteArray())
        write(face, "scripts/script.txt", "var_counter = 3".toByteArray())
        host.commitFaceMirror(revision)
        assertEquals(revision + 1, session.agentRevision())
        assertTrue(session.agentCandidate().watchXml.contains("new.png"))
        assertEquals("var_counter = 3", session.globalScript())
        assertArrayEquals(byteArrayOf(1, 2, 3), session.draftDirectory.resolve("images/new.png").readBytes())
        assertTrue(session.undo())
        assertFalse(session.draftDirectory.resolve("images/new.png").exists())
    }

    @Test fun invalidCandidateDoesNotImportAnyFilesOrAdvanceRevision() = fixture { session, host, face ->
        val before = session.agentCandidate()
        write(face, "images/new.png", byteArrayOf(1))
        write(face, "watch.xml", "<Watch><Layer type=\"image\" path=\"missing.png\"/></Watch>".toByteArray())
        assertThrows(Exception::class.java) { host.commitFaceMirror(before.revision) }
        assertEquals(before, session.agentCandidate())
        assertFalse(session.draftDirectory.resolve("images/new.png").exists())
        assertTrue(face.resolve("watch.xml").toFile().readText().contains("missing.png"))
    }

    @Test fun overwritingExistingImageIsDirtyAndUndoRestoresItsBytes() = fixture { session, host, face ->
        session.importResource(byteArrayOf(1, 2).inputStream(), "old.png", "images")
        session.flushDraftForPreview()
        host.syncFaceMirror()
        val revision = session.agentRevision()
        write(face, "images/old.png", byteArrayOf(4, 5))
        host.commitFaceMirror(revision)
        assertArrayEquals(byteArrayOf(4, 5), session.draftDirectory.resolve("images/old.png").readBytes())
        assertEquals(revision + 1, session.agentRevision())
        assertTrue(session.isDirty)
        assertTrue(session.undo())
        assertArrayEquals(byteArrayOf(1, 2), session.draftDirectory.resolve("images/old.png").readBytes())
        assertTrue(session.redo())
        assertArrayEquals(byteArrayOf(4, 5), session.draftDirectory.resolve("images/old.png").readBytes())
    }

    @Test fun editorConflictPreservesBothTheNewEditorAndBashCandidate() = fixture { session, host, face ->
        write(face, "watch.xml", "<Watch name=\"bash\"/>".toByteArray())
        session.replaceAgentDocument("watch.xml", "<Watch name=\"user\"/>", session.agentRevision())
        assertThrows(Exception::class.java) { host.commitFaceMirror(session.agentRevision()) }
        assertTrue(session.agentCandidate().watchXml.contains("user"))
        assertTrue(face.resolve("watch.xml").toFile().readText().contains("bash"))
    }

    @Test fun dynamicImagePathIsDiagnosedWithoutPretendingItIsAStaticFilename() = fixture { _, host, face ->
        write(face, "watch.xml", "<Watch><Layer type=\"image\" path=\"t{dh}.png\"/></Watch>".toByteArray())
        val validation = host.validateFaceMirror() as Map<*, *>
        assertEquals(true, validation["valid"])
        assertTrue(validation["diagnostics"].toString().contains("dynamic_resource"))
    }

    private fun fixture(block: (WatchEditorSession, WatchEditorSessionAgentToolHost, Path) -> Unit) {
        val manager = WorkspaceManager(temporary.newFolder().toPath())
        val workspace = manager.create("Shared", watchScope = "face")
        WatchEditorSession.createBlank(temporary.newFolder()).use { session ->
            val host = WatchEditorSessionAgentToolHost(session, workspaceManager = manager, watchScope = "face")
            host.prepareBashMirror()
            block(session, host, requireNotNull(manager.faceMirrorDirectory(workspace.id, "face")))
        }
    }

    private fun write(root: Path, path: String, bytes: ByteArray) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }
}
