package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.model.AgentConversation
import com.flue.launcher.watchface.agent.model.AgentSurface
import com.flue.launcher.watchface.agent.model.WatchAgentUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WatchAgentModelsTest {

    @Test
    fun `tool and detail expansion are independent per call`() {
        var state = WatchAgentUiState.initial("watch")
        state = state.toggleTool("call-a")
        state = state.toggleToolDetails("call-a")
        state = state.toggleTool("call-b")

        assertEquals(setOf("call-a", "call-b"), state.expandedToolIds)
        assertEquals(setOf("call-a"), state.expandedToolDetailIds)

        state = state.toggleTool("call-a")
        assertEquals(setOf("call-b"), state.expandedToolIds)
        assertEquals(setOf("call-a"), state.expandedToolDetailIds)
    }
    @Test
    fun `new editor entry creates a fresh conversation while history remains scoped`() {
        val first = AgentConversation.new("face-a", now = 10L)
        val second = AgentConversation.new("face-a", now = 20L)

        assertNotEquals(first.id, second.id)
        assertEquals("face-a", first.watchScope)
        assertEquals("", first.title)
    }

    @Test
    fun `conversation title comes from the first user message`() {
        val conversation = AgentConversation.new("face-a", now = 10L)
        val titled = conversation.withFirstUserMessage("  做一个蓝色数字时钟表盘  ")

        assertEquals("做一个蓝色数字时钟表盘", titled.title)
    }

    @Test
    fun `agent ui state preserves draft and expanded tools across fullscreen`() {
        val state = WatchAgentUiState.initial("face-a")
            .copy(inputDraft = "继续调整位置", expandedToolIds = setOf("tool-1"))

        val fullscreen = state.enterFullscreen()
        val restored = fullscreen.exitFullscreen()

        assertEquals(AgentSurface.SPLIT, restored.surface)
        assertEquals("继续调整位置", restored.inputDraft)
        assertEquals(setOf("tool-1"), restored.expandedToolIds)
    }
}
