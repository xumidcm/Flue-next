package com.flue.launcher.watchface.agent.provider

import com.flue.launcher.watchface.agent.model.AiProtocol
import org.json.JSONArray
import org.json.JSONObject

sealed interface AgentStreamEvent {
    data class TextDelta(val text: String) : AgentStreamEvent
    data class ReasoningDelta(val text: String) : AgentStreamEvent
    data class ThinkingDelta(val index: Int, val text: String = "", val signature: String = "", val redactedData: String? = null) : AgentStreamEvent
    data class ToolCallDelta(
        val id: String,
        val name: String? = null,
        val jsonDelta: String = "",
        val slot: Int? = null,
        val providerItemId: String? = null,
    ) : AgentStreamEvent
    data class Usage(val input: Long? = null, val output: Long? = null) : AgentStreamEvent
    data class Completed(val nativePayload: String? = null) : AgentStreamEvent
}

data class AgentParsedResponse(
    val text: String,
    val events: List<AgentStreamEvent>,
    val nativePayload: String?,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

/** Protocol-specific parsing is kept pure so it can be tested without an Android device. */
object AgentStreamParser {
    fun textDelta(protocol: AiProtocol, payload: String): String? =
        events(protocol, payload).filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.text }
            .takeIf { it.isNotEmpty() }

    fun event(protocol: AiProtocol, payload: String): AgentStreamEvent? = events(protocol, payload).firstOrNull()

    fun events(protocol: AiProtocol, payload: String): List<AgentStreamEvent> {
        val jsonText = payload.removePrefix("data:").trim()
        if (jsonText.isEmpty() || jsonText == "[DONE]") {
            return if (jsonText == "[DONE]") listOf(AgentStreamEvent.Completed()) else emptyList()
        }
        AgentJsonParser.parse(jsonText)?.let { parsed ->
            return pureEvents(protocol, parsed, jsonText)
        }
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        return when (protocol) {
            AiProtocol.CHAT_COMPLETIONS -> chatEvents(json, jsonText)
            AiProtocol.RESPONSES -> responsesEvents(json, jsonText)
            AiProtocol.CLAUDE_MESSAGES -> claudeEvents(json, jsonText)
        }
    }

    private fun pureEvents(protocol: AiProtocol, value: AgentJsonValue, raw: String): List<AgentStreamEvent> {
        val root = value as? AgentJsonValue.Obj ?: return emptyList()
        val result = mutableListOf<AgentStreamEvent>()
        when (protocol) {
            AiProtocol.CHAT_COMPLETIONS -> {
                val choices = root.array("choices")?.items.orEmpty()
                choices.forEach { choiceValue ->
                    val choice = choiceValue as? AgentJsonValue.Obj ?: return@forEach
                    val delta = choice.obj("delta")
                    delta?.string("content")?.takeIf { it.isNotEmpty() }?.let { result += AgentStreamEvent.TextDelta(it) }
                    (delta?.string("reasoning_content") ?: delta?.string("reasoning"))
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { result += AgentStreamEvent.ReasoningDelta(it) }
                    delta?.array("tool_calls")?.items.orEmpty().forEachIndexed { index, toolValue ->
                        val tool = toolValue as? AgentJsonValue.Obj ?: return@forEachIndexed
                        val fn = tool.obj("function")
                        result += AgentStreamEvent.ToolCallDelta(
                            id = tool.string("id").orEmpty(),
                            name = fn?.string("name"),
                            jsonDelta = fn?.string("arguments").orEmpty(),
                            slot = tool.long("index")?.toInt() ?: index,
                        )
                    }
                    if (!choice.string("finish_reason").isNullOrBlank()) result += AgentStreamEvent.Completed(raw)
                }
            }
            AiProtocol.RESPONSES -> when (root.string("type")) {
                "response.output_text.delta" -> root.string("delta")?.takeIf { it.isNotEmpty() }?.let { result += AgentStreamEvent.TextDelta(it) }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                    root.string("delta")?.takeIf { it.isNotEmpty() }?.let { result += AgentStreamEvent.ReasoningDelta(it) }
                "response.function_call_arguments.delta" -> result += AgentStreamEvent.ToolCallDelta(
                    id = root.string("call_id").orEmpty(), jsonDelta = root.string("delta").orEmpty(),
                    slot = root.long("output_index")?.toInt(), providerItemId = root.string("item_id"),
                )
                "response.output_item.added" -> root.obj("item")?.let { item ->
                    if (item.string("type") == "function_call") result += AgentStreamEvent.ToolCallDelta(
                        id = item.string("call_id") ?: item.string("id").orEmpty(), name = item.string("name"),
                        slot = root.long("output_index")?.toInt(), providerItemId = item.string("id"),
                    )
                }
                "response.completed", "response.done" -> result += AgentStreamEvent.Completed(raw)
                else -> root.array("output")?.items.orEmpty().forEachIndexed { index, itemValue ->
                    val item = itemValue as? AgentJsonValue.Obj ?: return@forEachIndexed
                    if (item.string("type") == "function_call") result += AgentStreamEvent.ToolCallDelta(
                        id = item.string("call_id") ?: item.string("id").orEmpty(), name = item.string("name"),
                        jsonDelta = item.string("arguments").orEmpty(), slot = index,
                    )
                    item.array("content")?.items.orEmpty().forEach { partValue ->
                        val part = partValue as? AgentJsonValue.Obj ?: return@forEach
                        part.string("text")?.takeIf { it.isNotEmpty() }?.let { result += AgentStreamEvent.TextDelta(it) }
                    }
                }
            }
            AiProtocol.CLAUDE_MESSAGES -> when (root.string("type")) {
                "content_block_start" -> root.obj("content_block")?.let { block ->
                    if (block.string("type") == "thinking") result += AgentStreamEvent.ThinkingDelta(root.long("index")?.toInt() ?: 0, block.string("thinking").orEmpty(), block.string("signature").orEmpty())
                    if (block.string("type") == "redacted_thinking") result += AgentStreamEvent.ThinkingDelta(root.long("index")?.toInt() ?: 0, redactedData = block.string("data"))
                    if (block.string("type") == "tool_use") result += AgentStreamEvent.ToolCallDelta(block.string("id").orEmpty(), block.string("name"), slot = root.long("index")?.toInt())
                }
                "content_block_delta" -> root.obj("delta")?.let { delta ->
                    when (delta.string("type")) {
                        "text_delta" -> delta.string("text")?.takeIf { it.isNotEmpty() }?.let { result += AgentStreamEvent.TextDelta(it) }
                        "thinking_delta" -> delta.string("thinking")?.takeIf { it.isNotEmpty() }?.let { result += AgentStreamEvent.ReasoningDelta(it) }
                        "signature_delta" -> result += AgentStreamEvent.ThinkingDelta(root.long("index")?.toInt() ?: 0, signature = delta.string("signature").orEmpty())
                        "input_json_delta" -> result += AgentStreamEvent.ToolCallDelta("", jsonDelta = delta.string("partial_json").orEmpty(), slot = root.long("index")?.toInt())
                    }
                    if (delta.string("type") == "thinking_delta") result += AgentStreamEvent.ThinkingDelta(root.long("index")?.toInt() ?: 0, text = delta.string("thinking").orEmpty())
                }
                "message_stop" -> result += AgentStreamEvent.Completed(raw)
                else -> root.array("content")?.items.orEmpty().forEachIndexed { index, partValue ->
                    val part = partValue as? AgentJsonValue.Obj ?: return@forEachIndexed
                    when (part.string("type")) {
                        "thinking" -> result += AgentStreamEvent.ThinkingDelta(index, part.string("thinking").orEmpty(), part.string("signature").orEmpty())
                        "redacted_thinking" -> result += AgentStreamEvent.ThinkingDelta(index, redactedData = part.string("data"))
                        "text" -> part.string("text")?.takeIf { it.isNotEmpty() }?.let { result += AgentStreamEvent.TextDelta(it) }
                        "tool_use" -> result += AgentStreamEvent.ToolCallDelta(part.string("id").orEmpty(), part.string("name"), part.obj("input")?.let(AgentJsonWriter::encode).orEmpty(), index)
                    }
                }
            }
        }
        root.obj("usage")?.let { usage ->
            val input = usage.long("input_tokens") ?: usage.long("prompt_tokens")
            val output = usage.long("output_tokens") ?: usage.long("completion_tokens")
            if (input != null || output != null) result += AgentStreamEvent.Usage(input, output)
        }
        return result
    }

    fun parseSse(protocol: AiProtocol, payload: String): List<AgentStreamEvent> {
        val decoder = SseDecoder(protocol)
        payload.lineSequence().forEach(decoder::acceptLine)
        return decoder.finish()
    }

    fun parseResponse(protocol: AiProtocol, payload: String): AgentParsedResponse {
        require(AgentJsonParser.parse(payload) != null) { "Provider 返回了无效 JSON" }
        val events = events(protocol, payload)
        val text = buildString {
            events.filterIsInstance<AgentStreamEvent.TextDelta>().forEach { append(it.text) }
            if (isEmpty()) append(nonStreamingText(protocol, payload))
        }
        val usage = events.filterIsInstance<AgentStreamEvent.Usage>().lastOrNull()
        require(text.isNotBlank() || events.any { it is AgentStreamEvent.ToolCallDelta || it is AgentStreamEvent.Completed }) {
            "Provider 返回了空内容"
        }
        return AgentParsedResponse(
            text = text,
            events = events + AgentStreamEvent.Completed(payload),
            nativePayload = payload.takeIf { it.isNotBlank() },
            inputTokens = usage?.input,
            outputTokens = usage?.output,
        )
    }

    private fun chatEvents(json: JSONObject, raw: String): List<AgentStreamEvent> {
        val result = mutableListOf<AgentStreamEvent>()
        val choices = json.optJSONArray("choices") ?: JSONArray()
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            val delta = choice.optJSONObject("delta")
            textFromContent(delta?.opt("content")).takeIf { it.isNotEmpty() }?.let { result += AgentStreamEvent.TextDelta(it) }
            listOf("reasoning_content", "reasoning").firstNotNullOfOrNull { key ->
                delta?.optString(key)?.takeIf { it.isNotEmpty() }
            }?.let { result += AgentStreamEvent.ReasoningDelta(it) }
            val toolCalls = delta?.optJSONArray("tool_calls") ?: JSONArray()
            for (j in 0 until toolCalls.length()) {
                val tool = toolCalls.optJSONObject(j) ?: continue
                val function = tool.optJSONObject("function")
                val id = tool.optString("id").ifBlank { tool.optString("index", "0") }
                result += AgentStreamEvent.ToolCallDelta(
                    id = tool.optString("id"),
                    name = function?.optString("name").orEmpty().ifBlank { null },
                    jsonDelta = function?.optString("arguments").orEmpty(),
                    slot = tool.optInt("index", j),
                )
            }
            if (choice.optString("finish_reason").isNotBlank()) result += AgentStreamEvent.Completed(raw)
        }
        usageFrom(json)?.let { result += it }
        return result
    }

    private fun responsesEvents(json: JSONObject, raw: String): List<AgentStreamEvent> {
        val result = mutableListOf<AgentStreamEvent>()
        when (json.optString("type")) {
            "response.output_text.delta" ->
                json.optString("delta").takeIf { it.isNotEmpty() }
                    ?.let { result += AgentStreamEvent.TextDelta(it) }
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                json.optString("delta").takeIf { it.isNotEmpty() }
                    ?.let { result += AgentStreamEvent.ReasoningDelta(it) }
            "response.function_call_arguments.delta" -> result += AgentStreamEvent.ToolCallDelta(
                id = json.optString("call_id"),
                jsonDelta = json.optString("delta"),
                slot = json.optInt("output_index").takeIf { json.has("output_index") },
                providerItemId = json.optString("item_id").ifBlank { null },
            )
            "response.output_item.added" -> {
                val item = json.optJSONObject("item")
                if (item?.optString("type") == "function_call") result += AgentStreamEvent.ToolCallDelta(
                    id = item.optString("call_id").ifBlank { item.optString("id") },
                    name = item.optString("name").ifBlank { null },
                    slot = json.optInt("output_index").takeIf { json.has("output_index") },
                    providerItemId = item.optString("id").ifBlank { null },
                )
            }
            "response.completed", "response.done" -> result += AgentStreamEvent.Completed(raw)
        }
        if (result.isEmpty()) {
            val output = json.optJSONArray("output") ?: JSONArray()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") == "function_call") {
                    result += AgentStreamEvent.ToolCallDelta(
                        id = item.optString("call_id").ifBlank { item.optString("id") },
                        name = item.optString("name").ifBlank { null },
                        jsonDelta = item.optString("arguments"),
                        slot = i,
                        providerItemId = item.optString("id").ifBlank { null },
                    )
                    continue
                }
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    part.optString("text").takeIf { it.isNotEmpty() }?.let { result += AgentStreamEvent.TextDelta(it) }
                }
            }
        }
        usageFrom(json)?.let { result += it }
        return result
    }

    private fun claudeEvents(json: JSONObject, raw: String): List<AgentStreamEvent> {
        val result = mutableListOf<AgentStreamEvent>()
        when (json.optString("type")) {
            "content_block_start" -> {
                val block = json.optJSONObject("content_block")
                if (block?.optString("type") == "tool_use") result += AgentStreamEvent.ToolCallDelta(
                    id = block.optString("id"),
                    name = block.optString("name").ifBlank { null },
                    jsonDelta = block.optJSONObject("input")?.toString().takeUnless { it == "{}" }.orEmpty(),
                    slot = json.optInt("index").takeIf { json.has("index") },
                )
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta")
                when (delta?.optString("type")) {
                    "text_delta" -> delta.optString("text").takeIf { it.isNotEmpty() }
                        ?.let { result += AgentStreamEvent.TextDelta(it) }
                    "thinking_delta" -> delta.optString("thinking").takeIf { it.isNotEmpty() }
                        ?.let { result += AgentStreamEvent.ReasoningDelta(it) }
                    "input_json_delta" -> result += AgentStreamEvent.ToolCallDelta(
                        id = "",
                        jsonDelta = delta.optString("partial_json"),
                        slot = json.optInt("index").takeIf { json.has("index") },
                    )
                }
            }
            "message_stop" -> result += AgentStreamEvent.Completed(raw)
        }
        if (result.isEmpty()) {
            val content = json.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> part.optString("text").takeIf { it.isNotEmpty() }
                        ?.let { result += AgentStreamEvent.TextDelta(it) }
                    "tool_use" -> result += AgentStreamEvent.ToolCallDelta(
                        id = part.optString("id"),
                        name = part.optString("name").ifBlank { null },
                        jsonDelta = part.optJSONObject("input")?.toString().orEmpty(),
                        slot = i,
                    )
                }
            }
        }
        usageFrom(json)?.let { result += it }
        return result
    }

    private fun nonStreamingText(protocol: AiProtocol, payload: String): String {
        AgentJsonParser.parse(payload)?.let { value ->
            val root = value as? AgentJsonValue.Obj ?: return@let ""
            return when (protocol) {
                AiProtocol.CHAT_COMPLETIONS -> root.array("choices")?.items?.firstOrNull()?.let { (it as? AgentJsonValue.Obj)?.obj("message")?.string("content") }.orEmpty()
                AiProtocol.RESPONSES -> root.array("output")?.items.orEmpty().joinToString("") { item ->
                    (item as? AgentJsonValue.Obj)?.array("content")?.items.orEmpty().joinToString("") { (it as? AgentJsonValue.Obj)?.string("text").orEmpty() }
                }
                AiProtocol.CLAUDE_MESSAGES -> root.array("content")?.items.orEmpty().joinToString("") { (it as? AgentJsonValue.Obj)?.string("text").orEmpty() }
            }
        }
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return ""
        return when (protocol) {
            AiProtocol.CHAT_COMPLETIONS -> {
                val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                textFromContent(message?.opt("content"))
            }
            AiProtocol.RESPONSES -> {
                buildString {
                    val output = json.optJSONArray("output") ?: JSONArray()
                    for (i in 0 until output.length()) {
                        val parts = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                        for (j in 0 until parts.length()) append(parts.optJSONObject(j)?.optString("text").orEmpty())
                    }
                }
            }
            AiProtocol.CLAUDE_MESSAGES -> textFromContent(json.optJSONArray("content"))
        }
    }

    private fun textFromContent(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val part = value.optJSONObject(i)
                append(part?.optString("text").orEmpty())
            }
        }
        is JSONObject -> value.optString("text")
        else -> ""
    }

    private fun usageFrom(json: JSONObject): AgentStreamEvent.Usage? {
        val usage = json.optJSONObject("usage") ?: return null
        val input = firstLong(usage, "input_tokens", "prompt_tokens", "input")
        val output = firstLong(usage, "output_tokens", "completion_tokens", "output")
        return if (input != null || output != null) AgentStreamEvent.Usage(input, output) else null
    }

    private fun firstLong(json: JSONObject, vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        if (!json.has(key) || json.isNull(key)) null else json.optLong(key).takeIf { it >= 0L }
    }

    class SseDecoder(private val protocol: AiProtocol) {
        private val data = StringBuilder()
        private val output = mutableListOf<AgentStreamEvent>()

        fun acceptLine(line: String) {
            val normalized = line.removeSuffix("\r")
            when {
                normalized.isBlank() -> flush()
                normalized.startsWith("data:") -> data.append(normalized.removePrefix("data:").trim()).append('\n')
                normalized.startsWith(":") -> Unit
            }
        }

        fun finish(): List<AgentStreamEvent> {
            flush()
            return output.toList()
        }

        fun snapshot(): List<AgentStreamEvent> = output.toList()

        private fun flush() {
            if (data.isEmpty()) return
            val payload = data.toString().trimEnd('\n')
            output += events(protocol, payload)
            data.setLength(0)
        }
    }
}
