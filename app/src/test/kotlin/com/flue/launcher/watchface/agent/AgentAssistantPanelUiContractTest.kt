package com.flue.launcher.watchface.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests for the assistant panel's streaming status and picker chrome. */
class AgentAssistantPanelUiContractTest {
    @Test
    fun `generation status is rendered in assistant turn with token usage`() {
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")

        assertTrue(panel.contains("generationStatus = state.generationStatus"))
        assertTrue(panel.contains("inputTokens = state.inputTokens"))
        assertTrue(panel.contains("outputTokens = state.outputTokens"))
        assertTrue(panel.contains("AgentGenerationStatus.THINKING"))
        assertTrue(panel.contains("AgentGenerationStatus.PROCESSING_FOLLOW_UP"))
    }

    @Test
    fun `back to bottom is an overlay and does not occupy conversation column space`() {
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")

        assertTrue(panel.contains("visible = !nearBottom && displayItems.isNotEmpty() && (!state.isGenerating || !followOutput)"))
        assertTrue(panel.contains("if (state.isGenerating) followOutput = nearBottom"))
        assertTrue(panel.contains("Modifier.align(Alignment.BottomCenter)"))
        assertTrue(panel.contains("navigationBarsPadding()"))
        assertFalse(panel.contains("AnimatedVisibility(\n            visible = !nearBottom"))
    }

    @Test
    fun `picker content is keyed by picker type while close animation runs`() {
        val controls = source("com/flue/launcher/ui/editor/agent/AgentQuickControls.kt")

        assertTrue(controls.contains("val pickerType = picker"))
        assertTrue(controls.contains("key(pickerType)"))
        assertTrue(controls.contains("AnimatedVisibility"))
        assertTrue(controls.contains("onDismissRequest = { picker = null }"))
    }

    @Test
    fun `assistant turn pairs each tool invocation with its result row`() {
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")
        assertTrue(panel.contains("remember(messages) { messages.orderedThoughtSteps() }"))
        assertTrue(panel.contains("thoughtSteps.forEach"))
        assertFalse(panel.contains("AgentToolInvocationStep(call, null"))
    }

    private fun source(relative: String): String {
        val root = sequenceOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
            File("../app/src/main/kotlin"),
        ).firstOrNull(File::isDirectory) ?: error("找不到 Android Kotlin 源码目录")
        return File(root, relative).readText()
    }
}
