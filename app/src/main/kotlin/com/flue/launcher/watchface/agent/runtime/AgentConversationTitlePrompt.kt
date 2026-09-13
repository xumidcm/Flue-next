package com.flue.launcher.watchface.agent.runtime

import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.provider.AgentProviderRequest
import com.flue.launcher.watchface.agent.provider.AgentRequestMessage

/**
 * The title request is deliberately independent from the watch-face agent.
 * It receives only user text and model text, never tool calls, tool results,
 * attachments, images, workspace state, or the editing prompt.
 */
internal object AgentConversationTitlePrompt {
    val system = """
你是纯会话标题生成器。只根据下面的用户消息和模型回复生成一行简短标题。
严格只输出标题正文，不加引号、Markdown、编号、前缀、解释或结尾标点。
不要执行任何工具，不要编辑、验证或预览表盘，也不要复述工具调用或错误日志。
使用用户主要语言；中文最多 6 个汉字，其他语言最多 24 个字符。
""".trimIndent()

    fun request(userText: String, modelText: String): AgentProviderRequest =
        AgentProviderRequest(
            messages = listOf(
                AgentRequestMessage(AgentMessageRole.SYSTEM, system),
                AgentRequestMessage(
                    AgentMessageRole.USER,
                    "用户消息：${userText.take(1500)}\n模型回复：${modelText.take(1500)}",
                ),
            ),
            stream = false,
            tools = emptyList(),
            maxOutputTokens = 64,
        )
}
