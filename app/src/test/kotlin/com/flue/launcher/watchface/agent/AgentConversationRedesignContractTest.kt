package com.flue.launcher.watchface.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRedesignContractTest {
    @Test
    fun `reasoning and tool activity share one ordered thought chain`() {
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")
        val components = sourceOrEmpty("com/flue/launcher/ui/editor/agent/AgentConversationComponents.kt")

        assertTrue(panel.contains("groupConversationForDisplay(messages)"))
        assertTrue(panel.contains("AgentThoughtChain("))
        assertTrue(panel.contains("message.toolCalls.forEach"))
        assertTrue(components.contains("internal fun groupConversationForDisplay"))
        assertTrue(components.contains("AgentConversationItem.AssistantTurn"))
        assertFalse(panel.contains("call.argumentsJson"))
        assertFalse(components.contains("argumentsJson"))
    }

    @Test
    fun `conversation chrome adapts to every launcher style`() {
        val components = sourceOrEmpty("com/flue/launcher/ui/editor/agent/AgentConversationComponents.kt")

        assertTrue(components.contains("AgentTopBar("))
        assertTrue(components.contains("AgentComposer("))
        assertTrue(components.contains("FlueTextField("))
        assertTrue(components.contains("UiStyle.APPLE_WATCH"))
        assertTrue(components.contains("UiStyle.MATERIAL_3"))
        assertTrue(components.contains("UiStyle.MIUIX"))
    }

    @Test
    fun `message originals and existing conversation actions remain reachable`() {
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")

        assertTrue(panel.contains("AgentMessageLayout.USER_BUBBLE"))
        assertTrue(panel.contains("AgentMessageLayout.ASSISTANT_FLOW"))
        assertTrue(panel.contains("message.content.takeIf(String::isNotBlank)"))
        assertTrue(panel.contains("viewModel.toggleHistory(true)"))
        assertTrue(panel.contains("viewModel.editMessage(message.id)"))
        assertTrue(panel.contains("viewModel.retryMessage"))
        assertTrue(panel.contains("viewModel::newConversation"))
        assertTrue(panel.contains("viewModel.renameConversation"))
        assertFalse(panel.contains("@Composable\n@Composable"))
    }

    private fun source(relative: String): String = File(sourceRoot(), relative).readText()

    private fun sourceOrEmpty(relative: String): String =
        File(sourceRoot(), relative).takeIf(File::isFile)?.readText().orEmpty()

    private fun sourceRoot(): File = sequenceOf(
        File("src/main/kotlin"),
        File("app/src/main/kotlin"),
        File("../app/src/main/kotlin"),
    ).firstOrNull(File::isDirectory) ?: error("找不到 Android Kotlin 源码目录")
}
