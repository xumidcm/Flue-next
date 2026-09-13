package com.flue.launcher.watchface.agent.runtime

import com.flue.launcher.watchface.agent.model.AgentMessage
import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.provider.AgentProviderRequest
import com.flue.launcher.watchface.agent.provider.AgentRequestMessage

/**
 * A tool-free request used only to compact visible conversation text.  Keeping this
 * prompt separate prevents the watch-face editing prompt and tool schemas from leaking
 * into a title or compression request.
 */
internal object AgentConversationCompressionPrompt {
    val system = """
你是纯会话上下文压缩器。只总结用户消息和模型最终可见的文字，保留目标、约束、已经确认的决定和未完成事项。
不要执行工具，不要编辑、验证或预览表盘，不要输出工具名称、参数、JSON、命令、日志、revision 或错误堆栈。
只输出简洁的中文摘要正文，不要标题前缀、Markdown 或解释。
""".trimIndent()

    fun request(messages: List<AgentMessage>): AgentProviderRequest {
        val transcript = messages.asSequence()
            .filter { it.role == AgentMessageRole.USER || it.role == AgentMessageRole.ASSISTANT }
            .filter { it.content.isNotBlank() }
            .map { message ->
                val speaker = if (message.role == AgentMessageRole.USER) "用户" else "模型"
                "$speaker：${message.content.take(6000)}"
            }
            .joinToString("\n")
        return AgentProviderRequest(
            messages = listOf(
                AgentRequestMessage(AgentMessageRole.SYSTEM, system),
                AgentRequestMessage(AgentMessageRole.USER, transcript.take(48_000)),
            ),
            stream = false,
            tools = emptyList(),
            maxOutputTokens = 2048,
        )
    }
}
