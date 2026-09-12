package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.model.*
import com.flue.launcher.watchface.agent.provider.*
import org.junit.Assert.*
import org.junit.Test

class AgentVisionRequestTest {
    @Test fun `Claude signed thinking survives the next tool turn`() {
        val events = AgentStreamParser.events(AiProtocol.CLAUDE_MESSAGES, """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-proof"}}""")
        assertEquals("signed-proof", events.filterIsInstance<AgentStreamEvent.ThinkingDelta>().single().signature)
        val message = AgentRequestMessage(AgentMessageRole.ASSISTANT, "", thinkingBlocks = listOf(AgentThinkingBlock("consider", "signed-proof")), toolCalls = listOf(AgentAssistantToolCall("call", "read_image", "{}")))
        val body = AgentRequestBuilder.build(AiProviderProfile("p", "p", AiProtocol.CLAUDE_MESSAGES, "https://example.com", modelId = "claude-sonnet-4-6", reasoningEffort = "high"), listOf(message))
        assertTrue(body.contains("signed-proof"))
        assertTrue(body.contains("adaptive"))
        assertTrue(body.indexOf("signed-proof") < body.indexOf("tool_use"))
    }
    @Test fun `images reach all three provider protocols as content rather than metadata`() {
        val image = AgentRequestImage("image/png", "aW1hZ2U=")
        val messages = listOf(AgentRequestMessage(AgentMessageRole.USER, "看这张图", images = listOf(image)))
        for (protocol in AiProtocol.entries) {
            val body = AgentRequestBuilder.build(AiProviderProfile("p", "p", protocol, "https://example.com", modelId = "vision"), messages)
            assertTrue(body.contains("aW1hZ2U="))
            assertTrue(body.contains(when (protocol) {
                AiProtocol.CHAT_COMPLETIONS -> "image_url"
                AiProtocol.RESPONSES -> "input_image"
                AiProtocol.CLAUDE_MESSAGES -> "media_type"
            }))
        }
    }

    @Test fun `reasoning selection is serialized and default omits the override`() {
        for (protocol in listOf(AiProtocol.CHAT_COMPLETIONS, AiProtocol.RESPONSES)) {
            val profile = AiProviderProfile("p", "p", protocol, "https://example.com", modelId = "reasoner")
            val request = AgentProviderRequest(listOf(AgentRequestMessage(AgentMessageRole.USER, "hi")))
            assertFalse(AgentRequestBuilder.build(profile, request).contains("effort"))
            assertTrue(AgentRequestBuilder.build(profile.copy(reasoningEffort = "high"), request).contains("high"))
        }
    }
}
