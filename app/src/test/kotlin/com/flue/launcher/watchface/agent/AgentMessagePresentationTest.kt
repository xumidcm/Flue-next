package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.model.AgentMessage
import com.flue.launcher.watchface.agent.model.AgentMessageLayout
import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.model.agentMessageLayout
import com.flue.launcher.watchface.agent.model.shouldRenderMessageBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessagePresentationTest {
    @Test
    fun `user and assistant bodies stay visible while tools use compact steps`() {
        val user = message(role = AgentMessageRole.USER, content = "把表盘改成蓝色")
        val assistant = message(role = AgentMessageRole.ASSISTANT, content = "已经调整配色。")
        val tool = message(
            role = AgentMessageRole.TOOL,
            content = "{\"ok\":true,\"result\":{\"layers\":5}}",
            toolName = "inspect_face",
        )

        assertEquals(AgentMessageLayout.USER_BUBBLE, agentMessageLayout(user))
        assertEquals(AgentMessageLayout.ASSISTANT_FLOW, agentMessageLayout(assistant))
        assertEquals(AgentMessageLayout.TOOL_STEP, agentMessageLayout(tool))
        assertTrue(shouldRenderMessageBody(user))
        assertTrue(shouldRenderMessageBody(assistant))
        assertFalse(shouldRenderMessageBody(tool))
    }

    private fun message(
        role: AgentMessageRole,
        content: String,
        toolName: String? = null,
    ) = AgentMessage(
        id = "$role-$toolName",
        conversationId = "conversation",
        role = role,
        content = content,
        createdAt = 1L,
        toolName = toolName,
    )
}
