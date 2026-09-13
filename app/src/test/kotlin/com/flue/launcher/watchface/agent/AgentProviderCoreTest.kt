package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.model.AiProtocol
import com.flue.launcher.watchface.agent.model.AiProviderProfile
import com.flue.launcher.watchface.agent.provider.AgentRequestBuilder
import com.flue.launcher.watchface.agent.provider.AgentRequestMessage
import com.flue.launcher.watchface.agent.provider.AgentStreamEvent
import com.flue.launcher.watchface.agent.provider.AgentStreamParser
import com.flue.launcher.watchface.agent.provider.AgentToolDefinition
import com.flue.launcher.watchface.agent.provider.AgentModelCatalogParser
import com.flue.launcher.watchface.agent.provider.AgentModelContextResolver
import com.flue.launcher.watchface.agent.model.AiModelDescriptor
import com.flue.launcher.watchface.agent.model.ModelMetadataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProviderCoreTest {
    @Test
    fun `chat completions request encodes messages and stream`() {
        val json = com.flue.launcher.watchface.agent.provider.AgentJsonParser.parse(
            AgentRequestBuilder.build(
                profile(AiProtocol.CHAT_COMPLETIONS),
                messages = listOf(AgentRequestMessage(AgentMessageRole.USER, "你好")),
                stream = true,
            ),
        )

        val root = json as com.flue.launcher.watchface.agent.provider.AgentJsonValue.Obj
        assertEquals("watch-model", root.string("model"))
        assertTrue(root.bool("stream") == true)
        val message = (root.array("messages")!!.items.first() as com.flue.launcher.watchface.agent.provider.AgentJsonValue.Obj)
        assertEquals("user", message.string("role"))
        assertEquals("你好", message.string("content"))
    }

    @Test
    fun `responses and claude requests preserve protocol specific fields`() {
        val responses = com.flue.launcher.watchface.agent.provider.AgentJsonParser.parse(
            AgentRequestBuilder.build(
                profile(AiProtocol.RESPONSES),
                messages = listOf(AgentRequestMessage(AgentMessageRole.USER, "画一个表盘")),
                stream = false,
                tools = listOf(AgentToolDefinition("inspect_face", "Inspect the current face", "{\"type\":\"object\"}")),
            ),
        )
        val responsesRoot = responses as com.flue.launcher.watchface.agent.provider.AgentJsonValue.Obj
        val input = responsesRoot.array("input")!!.items.first() as com.flue.launcher.watchface.agent.provider.AgentJsonValue.Obj
        val content = input.array("content")!!.items.first() as com.flue.launcher.watchface.agent.provider.AgentJsonValue.Obj
        assertEquals("画一个表盘", content.string("text"))
        val tool = responsesRoot.array("tools")!!.items.first() as com.flue.launcher.watchface.agent.provider.AgentJsonValue.Obj
        assertEquals("function", tool.string("type"))
        assertEquals("inspect_face", tool.string("name"))
        assertTrue(responsesRoot.value("max_output_tokens") != null)

        val claude = com.flue.launcher.watchface.agent.provider.AgentJsonParser.parse(
            AgentRequestBuilder.build(
                profile(AiProtocol.CLAUDE_MESSAGES),
                messages = listOf(AgentRequestMessage(AgentMessageRole.USER, "你好")),
                stream = false,
            ),
        )
        val claudeRoot = claude as com.flue.launcher.watchface.agent.provider.AgentJsonValue.Obj
        assertEquals(8192L, claudeRoot.long("max_tokens"))
        val claudeMessage = claudeRoot.array("messages")!!.items.first() as com.flue.launcher.watchface.agent.provider.AgentJsonValue.Obj
        val claudeContent = claudeMessage.array("content")!!.items.first() as com.flue.launcher.watchface.agent.provider.AgentJsonValue.Obj
        assertEquals("你好", claudeContent.string("text"))
    }

    @Test
    fun `sse parser emits text tool usage and completion events`() {
        val payload = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你\",\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"name\":\"inspect_face\",\"arguments\":\"{\\\"x\\\":\"}}]}}]}\n",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"arguments\":\"1}\"}}]}}]}\n",
            "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n",
        ).joinToString("\n")

        val events = AgentStreamParser.parseSse(AiProtocol.CHAT_COMPLETIONS, payload)

        assertTrue(events.any { it is AgentStreamEvent.TextDelta && it.text == "你" })
        assertTrue(events.any { it is AgentStreamEvent.ToolCallDelta && it.id == "call-1" })
        assertTrue(events.any { it is AgentStreamEvent.Completed })
    }

    @Test
    fun `provider parsers preserve reasoning deltas separately from answer text`() {
        val chat = AgentStreamParser.events(
            AiProtocol.CHAT_COMPLETIONS,
            """{"choices":[{"delta":{"reasoning_content":"先检查图层"}}]}""",
        )
        val responses = AgentStreamParser.events(
            AiProtocol.RESPONSES,
            """{"type":"response.reasoning_summary_text.delta","delta":"准备预览"}""",
        )
        val claude = AgentStreamParser.events(
            AiProtocol.CLAUDE_MESSAGES,
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"核对坐标"}}""",
        )

        assertEquals("先检查图层", chat.filterIsInstance<AgentStreamEvent.ReasoningDelta>().single().text)
        assertEquals("准备预览", responses.filterIsInstance<AgentStreamEvent.ReasoningDelta>().single().text)
        assertEquals("核对坐标", claude.filterIsInstance<AgentStreamEvent.ReasoningDelta>().single().text)
    }

    @Test
    fun `non streaming protocol payloads expose text without losing native payload`() {
        val response = AgentStreamParser.parseResponse(
            AiProtocol.RESPONSES,
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"完成"}]}],"usage":{"input_tokens":12,"output_tokens":4}}""",
        )

        assertEquals("完成", response.text)
        assertEquals(12L, response.inputTokens)
        assertEquals(4L, response.outputTokens)
        assertTrue(response.nativePayload?.contains("output") == true)
    }

    @Test
    fun `model catalog accepts common ids limits and capability fields`() {
        val models = AgentModelCatalogParser.parse(
            """{"data":[{"id":"vision-1","display_name":"Vision 1","context_length":128000,"supports_vision":true,"supports_tools":true}]}""",
        )

        assertEquals(1, models.size)
        assertEquals("vision-1", models.single().id)
        assertEquals(128000L, models.single().contextWindowTokens)
        assertEquals(true, models.single().supportsVision)
        assertEquals(true, models.single().supportsTools)
    }

    @Test
    fun `context window falls back to 200k and manual override wins`() {
        val descriptor = AiModelDescriptor("unknown", metadataSource = ModelMetadataSource.PROVIDER)
        assertEquals(100_000L, AgentModelContextResolver.contextWindow(profile(AiProtocol.RESPONSES), descriptor))
        assertEquals(
            64_000L,
            AgentModelContextResolver.contextWindow(
                profile(AiProtocol.RESPONSES).copy(contextWindowOverride = 64_000L),
                descriptor,
            ),
        )
    }

    private fun profile(protocol: AiProtocol) = AiProviderProfile(
        id = "provider",
        name = "Provider",
        protocol = protocol,
        baseUrl = "https://example.com/v1",
        modelId = "watch-model",
    )
}
