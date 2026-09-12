package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.model.AiProtocol
import com.flue.launcher.watchface.agent.provider.AgentEndpointResolver
import com.flue.launcher.watchface.agent.provider.AgentStreamParser
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentProviderProtocolTest {
    @Test
    fun `known generation endpoints are normalized without duplicate v1`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AgentEndpointResolver.generation("https://api.openai.com/v1", AiProtocol.CHAT_COMPLETIONS),
        )
        assertEquals(
            "https://example.com/v1/responses",
            AgentEndpointResolver.generation("https://example.com/v1/responses", AiProtocol.RESPONSES),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AgentEndpointResolver.generation("https://api.anthropic.com", AiProtocol.CLAUDE_MESSAGES),
        )
    }

    @Test
    fun `three protocols expose text deltas`() {
        assertEquals("你", AgentStreamParser.textDelta(AiProtocol.CHAT_COMPLETIONS, """{"choices":[{"delta":{"content":"你"}}]}"""))
        assertEquals("好", AgentStreamParser.textDelta(AiProtocol.RESPONSES, """{"type":"response.output_text.delta","delta":"好"}"""))
        assertEquals("！", AgentStreamParser.textDelta(AiProtocol.CLAUDE_MESSAGES, """{"type":"content_block_delta","delta":{"type":"text_delta","text":"！"}}"""))
    }
}
