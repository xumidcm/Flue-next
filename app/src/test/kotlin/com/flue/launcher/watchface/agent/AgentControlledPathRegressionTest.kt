package com.flue.launcher.watchface.agent

import android.graphics.Bitmap
import android.graphics.Color
import com.flue.launcher.watchface.agent.tools.AgentWorkspaceImageTools
import com.flue.launcher.watchface.agent.tools.WatchAgentToolCall
import com.flue.launcher.watchface.agent.tools.WatchAgentToolRunner
import com.flue.launcher.watchface.agent.tools.WatchEditorSessionAgentToolHost
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import com.flue.launcher.watchface.editor.WatchEditorSession
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentControlledPathRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unregisteredWorkspaceAndCurrentScopedFaceImagesAreReadable() {
        val manager = WorkspaceManager(temporary.newFolder().toPath())
        val workspace = manager.create("Shared", watchScope = "older-face")
        manager.bind("current-face", workspace.id)
        val face = manager.faceMirrorDirectory(workspace.id, "current-face")!!
        Files.createDirectories(face.resolve("images"))
        writeImage(face.resolve("images/unregistered.png").toFile(), Color.RED)
        writeImage(workspace.paths.workspace.resolve("unregistered.png").toFile(), Color.BLUE)
        val tools = AgentWorkspaceImageTools(manager, "current-face")
        assertEquals("face/images/unregistered.png", tools.readImage("face/images/unregistered.png").path)
        assertEquals("workspace/unregistered.png", tools.readImage("unregistered.png").path)
        assertTrue(tools.listFiles().contains("face/images/unregistered.png"))
    }

    @Test fun scopesThatPreviouslySanitizedToTheSameNameHaveDifferentMirrors() {
        val manager = WorkspaceManager(temporary.newFolder().toPath())
        val workspace = manager.create("Shared", watchScope = "face:a")
        manager.bind("face/a", workspace.id)
        assertNotEquals(manager.faceMirrorDirectory(workspace.id, "face:a"), manager.faceMirrorDirectory(workspace.id, "face/a"))
    }

    @Test fun explicitWorkspaceReadDoesNotFallBackToAnAttachmentWithTheSameBasename() = runBlocking {
        val manager = WorkspaceManager(temporary.newFolder().toPath())
        val workspace = manager.create("Selected", watchScope = "face")
        val attachments = temporary.newFolder()
        File(attachments, "same.txt").writeText("wrong attachment")
        workspace.paths.workspace.resolve("same.txt").toFile().writeText("workspace file")
        WatchEditorSession.createBlank(temporary.newFolder()).use { session ->
            val runner = WatchAgentToolRunner(
                WatchEditorSessionAgentToolHost(session, workspaceManager = manager, watchScope = "face"),
                workspaceManager = manager, workspaceId = workspace.id, attachmentsDir = attachments,
            )
            val result = runner.execute(WatchAgentToolCall("read", "read_file", mapOf("path" to "workspace/same.txt")))
            assertTrue(result.error?.message, result.succeeded)
            assertEquals("workspace file", (result.content as Map<*, *>)["content"])
        }
    }

    private fun writeImage(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
