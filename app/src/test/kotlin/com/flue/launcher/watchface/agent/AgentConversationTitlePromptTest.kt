package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.runtime.AgentConversationTitlePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationTitlePromptTest {
    @Test
    fun `title request contains only user and model text without watch tools`() {
        val request = AgentConversationTitlePrompt.request(
            userText = "预览失败：图片格式非法，请告诉我原因",
            modelText = "当前响应只是一条错误信息，无法作为可编辑表盘内容。",
        )

        assertFalse(request.stream)
        assertTrue(request.tools.isEmpty())
        assertEquals(listOf(AgentMessageRole.SYSTEM, AgentMessageRole.USER), request.messages.map { it.role })
        assertTrue(request.messages.single { it.role == AgentMessageRole.SYSTEM }.content.contains("纯会话标题生成器"))
        assertTrue(request.messages.single { it.role == AgentMessageRole.USER }.content.contains("用户消息："))
        assertTrue(request.messages.single { it.role == AgentMessageRole.USER }.content.contains("模型回复："))
        assertTrue(request.messages.none { it.images.isNotEmpty() || it.toolCalls.isNotEmpty() || it.toolCallId != null })
    }
}
