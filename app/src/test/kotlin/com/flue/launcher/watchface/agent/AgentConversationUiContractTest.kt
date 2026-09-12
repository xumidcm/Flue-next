package com.flue.launcher.watchface.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationUiContractTest {
    @Test
    fun `assistant updates and tool invocations remain visible without raw arguments`() {
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")

        assertTrue(panel.contains("messages.lastOrNull()?.content"))
        assertTrue(panel.contains("message.toolCalls.forEach"))
        assertTrue(panel.contains("AgentToolInvocationStep("))
        assertFalse(panel.contains("call.argumentsJson"))
    }

    @Test
    fun `watch editor uses resize once for the agent keyboard`() {
        val manifest = sourceFromMain("AndroidManifest.xml")

        val editorActivity = manifest.substringAfter("android:name=\".WatchEditorActivity\"")
            .substringBefore("</activity>")
            .substringBefore("/>")
        assertTrue(editorActivity.contains("android:windowSoftInputMode=\"adjustResize\""))
    }

    private fun source(relative: String): String {
        val root = sequenceOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
            File("../app/src/main/kotlin"),
        ).firstOrNull(File::isDirectory) ?: error("找不到 Android Kotlin 源码目录")
        return File(root, relative).readText()
    }

    private fun sourceFromMain(relative: String): String {
        val root = sequenceOf(
            File("src/main"),
            File("app/src/main"),
            File("../app/src/main"),
        ).firstOrNull(File::isDirectory) ?: error("找不到 Android main 目录")
        return File(root, relative).readText()
    }
}
