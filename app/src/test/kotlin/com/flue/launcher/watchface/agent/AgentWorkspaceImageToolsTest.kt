package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.tools.AgentWorkspaceImageTools
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentWorkspaceImageToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `list exposes generated and attachment files only in the selected workspace`() {
        val manager = WorkspaceManager(temporary.newFolder().toPath())
        val selected = manager.create("Selected", watchScope = "face")
        val other = manager.create("Other")
        Files.write(selected.paths.generated.resolve("image.png"), byteArrayOf(1))
        Files.write(selected.paths.workspace.resolve("attachment.jpg"), byteArrayOf(2))
        Files.write(other.paths.generated.resolve("private.png"), byteArrayOf(3))
        val files = AgentWorkspaceImageTools(manager, "face").listFiles()
        assertEquals(setOf("generated/image.png", "workspace/attachment.jpg"), files.toSet())
    }

    @Test fun `image paths reject traversal and Linux or metadata reads before decoding`() {
        val manager = WorkspaceManager(temporary.newFolder().toPath())
        manager.create("Selected", watchScope = "face")
        val tools = AgentWorkspaceImageTools(manager, "face")
        for (path in listOf("../secret.png", "/tmp/secret.png", "linux/etc/passwd", "workspace.json")) {
            assertTrue("must reject $path", runCatching { tools.readImage(path) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
