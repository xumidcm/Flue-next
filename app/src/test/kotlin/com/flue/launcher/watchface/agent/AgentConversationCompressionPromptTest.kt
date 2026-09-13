package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.model.AgentMessage
import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.runtime.AgentConversationCompressionPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationCompressionPromptTest {
    @Test
    fun `compression request contains only visible text and no tools`() {
        val messages = listOf(
            AgentMessage("u", "c", AgentMessageRole.USER, "保留这个目标", 1L),
            AgentMessage("t", "c", AgentMessageRole.TOOL, "{\"command\":\"rm\"}", 2L, toolName = "run_bash"),
            AgentMessage("a", "c", AgentMessageRole.ASSISTANT, "模型结论", 3L),
        )
        val request = AgentConversationCompressionPrompt.request(messages)
        assertEquals(2, request.messages.size)
        assertTrue(request.tools.isEmpty())
        assertFalse(request.messages.last().content.contains("run_bash"))
        assertFalse(request.messages.last().content.contains("command"))
        assertTrue(request.messages.last().content.contains("保留这个目标"))
    }
}
