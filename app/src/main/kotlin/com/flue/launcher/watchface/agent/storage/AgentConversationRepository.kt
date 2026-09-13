package com.flue.launcher.watchface.agent.storage

import android.content.Context
import com.flue.launcher.watchface.agent.model.AgentConversation
import com.flue.launcher.watchface.agent.model.AgentAttachment
import com.flue.launcher.watchface.agent.model.AgentMessage
import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.model.AgentMessageStatus
import com.flue.launcher.watchface.agent.model.AgentToolCallRecord
import org.json.JSONArray
import org.json.JSONObject

class AgentConversationRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun createConversation(watchScope: String, now: Long = System.currentTimeMillis()): AgentConversation {
        val conversation = AgentConversation.new(watchScope, now)
        writeConversation(conversation)
        return conversation
    }

    @Synchronized
    fun listConversations(watchScope: String): List<AgentConversation> = conversations()
        .filter { it.watchScope == watchScope }
        .sortedByDescending { it.updatedAt }

    @Synchronized
    fun getConversation(id: String): AgentConversation? = conversations().firstOrNull { it.id == id }

    @Synchronized
    fun appendMessage(conversationId: String, message: AgentMessage): AgentMessage {
        require(message.conversationId == conversationId) { "消息不属于当前会话" }
        val messages = messages(conversationId).filterNot { it.id == message.id } + message
        val encoded = JSONArray().apply { messages.forEach { put(messageJson(it)) } }
        preferences.edit().putString(messagesKey(conversationId), encoded.toString()).apply()
        getConversation(conversationId)?.let { current ->
            val titled = if (message.role == AgentMessageRole.USER) current.withFirstUserMessage(message.content) else current
            writeConversation(titled.copy(updatedAt = maxOf(current.updatedAt, message.createdAt)))
        }
        return message
    }

    /**
     * Replaces a message without changing its position in the conversation.
     *
     * Streaming assistant messages are rendered by a stable LazyColumn key. Moving
     * the same id to the end on every persistence update makes the reply jump from
     * the top of the viewport to the bottom when a tool turn completes.
     */
    @Synchronized
    fun updateMessage(message: AgentMessage): AgentMessage {
        val all = messages(message.conversationId)
        val index = all.indexOfFirst { it.id == message.id }
        if (index < 0) return appendMessage(message.conversationId, message)
        val next = all.toMutableList().apply { set(index, message) }
        preferences.edit().putString(
            messagesKey(message.conversationId),
            JSONArray().apply { next.forEach { put(messageJson(it)) } }.toString(),
        ).apply()
        getConversation(message.conversationId)?.let { current ->
            writeConversation(current.copy(updatedAt = maxOf(current.updatedAt, message.createdAt)))
        }
        return message
    }

    @Synchronized
    fun listMessages(conversationId: String): List<AgentMessage> = messages(conversationId)

    @Synchronized
    fun applyUserTitle(conversationId: String, content: String, now: Long = System.currentTimeMillis()): AgentConversation {
        val current = requireNotNull(getConversation(conversationId))
        val updated = current.withFirstUserMessage(content).copy(updatedAt = now)
        writeConversation(updated)
        return updated
    }

    @Synchronized fun renameConversation(conversationId: String, title: String, now: Long = System.currentTimeMillis()): AgentConversation {
        val current = requireNotNull(getConversation(conversationId))
        require(title.isNotBlank())
        return current.copy(title = title.trim().replace(Regex("\\s+"), " ").take(120), titleFinalized = true, updatedAt = now).also(::writeConversation)
    }

    @Synchronized fun applyGeneratedTitle(conversationId: String, title: String) {
        val current = getConversation(conversationId) ?: return
        if (current.titleFinalized) return
        val cleaned = title.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '“', '”', '#', ' ')
            ?.replace(Regex("\\s+"), " ")?.take(120).orEmpty()
        // Generated titles remain provisional so the next completed turn can refine them.
        if (cleaned.isNotBlank()) writeConversation(current.copy(title = cleaned, titleFinalized = false))
    }

    @Synchronized fun markGeneratedTitlePending(conversationId: String) {
        val current = getConversation(conversationId) ?: return
        if (!current.titleFinalized) return
        writeConversation(current.copy(titleFinalized = false))
    }

    @Synchronized fun deleteConversation(conversationId: String) {
        val current = getConversation(conversationId)
        val remaining = listConversations(current?.watchScope.orEmpty()).filterNot { it.id == conversationId }
        preferences.edit().remove(messagesKey(conversationId)).putString(CONVERSATIONS_KEY,
            JSONArray().apply { remaining.forEach { put(conversationJson(it)) } }.toString()).apply()
    }

    @Synchronized fun deleteMessagesAfter(conversationId: String, messageId: String) {
        val all = messages(conversationId)
        val index = all.indexOfFirst { it.id == messageId }
        if (index < 0) return
        preferences.edit().putString(messagesKey(conversationId), JSONArray().apply { all.take(index + 1).forEach { put(messageJson(it)) } }.toString()).apply()
    }

    @Synchronized fun replaceMessageAndDeleteAfter(message: AgentMessage) {
        val all = messages(message.conversationId)
        val index = all.indexOfFirst { it.id == message.id }
        if (index < 0) return
        val next = all.take(index) + message
        preferences.edit().putString(
            messagesKey(message.conversationId),
            JSONArray().apply { next.forEach { put(messageJson(it)) } }.toString(),
        ).apply()
        getConversation(message.conversationId)?.let { current ->
            writeConversation(current.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    @Synchronized
    fun markEnded(conversationId: String, now: Long = System.currentTimeMillis()) {
        getConversation(conversationId)?.let { writeConversation(it.copy(endedAt = now, updatedAt = now)) }
    }

    private fun conversations(): List<AgentConversation> {
        val array = JSONArray(preferences.getString(CONVERSATIONS_KEY, "[]"))
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(conversationFrom(array.getJSONObject(index)))
        }
    }

    private fun messages(conversationId: String): List<AgentMessage> {
        val array = JSONArray(preferences.getString(messagesKey(conversationId), "[]"))
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(messageFrom(array.getJSONObject(index)))
        }
    }

    private fun writeConversation(conversation: AgentConversation) {
        val next = conversations().filterNot { it.id == conversation.id } + conversation
        val encoded = JSONArray().apply { next.forEach { put(conversationJson(it)) } }
        preferences.edit().putString(CONVERSATIONS_KEY, encoded.toString()).apply()
    }

    private fun conversationJson(value: AgentConversation) = JSONObject().apply {
        put("id", value.id)
        put("watchScope", value.watchScope)
        put("title", value.title)
        put("createdAt", value.createdAt)
        put("updatedAt", value.updatedAt)
        put("endedAt", value.endedAt ?: JSONObject.NULL)
        put("titleFinalized", value.titleFinalized)
    }

    private fun conversationFrom(value: JSONObject) = AgentConversation(
        id = value.getString("id"), watchScope = value.getString("watchScope"), title = value.getString("title"),
        createdAt = value.getLong("createdAt"), updatedAt = value.getLong("updatedAt"),
        endedAt = value.optLong("endedAt").takeIf { !value.isNull("endedAt") && it > 0L },
        titleFinalized = value.optBoolean("titleFinalized", false),
    )

    private fun messageJson(value: AgentMessage) = JSONObject().apply {
        put("id", value.id); put("conversationId", value.conversationId); put("role", value.role.name)
        put("content", value.content); put("createdAt", value.createdAt); put("status", value.status.name)
        put("toolName", value.toolName ?: JSONObject.NULL)
        put("toolCallId", value.toolCallId ?: JSONObject.NULL)
        put("reasoningContent", value.reasoningContent ?: JSONObject.NULL)
        put("thinkingDurationMs", value.thinkingDurationMs ?: JSONObject.NULL)
        put("toolCalls", JSONArray().apply {
            value.toolCalls.forEach { call ->
                put(JSONObject().put("id", call.id).put("name", call.name).put("argumentsJson", call.argumentsJson))
            }
        })
        put("attachments", JSONArray().apply { value.attachments.forEach { attachment ->
            put(JSONObject().put("id", attachment.id).put("displayName", attachment.displayName)
                .put("mimeType", attachment.mimeType ?: JSONObject.NULL).put("sizeBytes", attachment.sizeBytes)
                .put("sha256", attachment.sha256 ?: JSONObject.NULL).put("localPath", attachment.localPath ?: JSONObject.NULL))
        } })
    }

    private fun messageFrom(value: JSONObject) = AgentMessage(
        id = value.getString("id"), conversationId = value.getString("conversationId"),
        role = AgentMessageRole.valueOf(value.getString("role")), content = value.getString("content"),
        createdAt = value.getLong("createdAt"), status = AgentMessageStatus.valueOf(value.optString("status", AgentMessageStatus.COMPLETE.name)),
        toolName = value.nullableString("toolName"),
        toolCallId = value.nullableString("toolCallId"),
        reasoningContent = value.nullableString("reasoningContent"),
        thinkingDurationMs = value.optLong("thinkingDurationMs").takeIf { !value.isNull("thinkingDurationMs") && it >= 0L },
        toolCalls = buildList {
            val calls = value.optJSONArray("toolCalls") ?: return@buildList
            for (index in 0 until calls.length()) {
                val call = calls.optJSONObject(index) ?: continue
                add(AgentToolCallRecord(call.optString("id"), call.optString("name"), call.optString("argumentsJson", "{}")))
            }
        },
        attachments = buildList {
            val attachments = value.optJSONArray("attachments") ?: return@buildList
            for (index in 0 until attachments.length()) {
                val item = attachments.optJSONObject(index) ?: continue
                add(AgentAttachment(
                    id = item.optString("id"), displayName = item.optString("displayName"),
                    mimeType = item.nullableString("mimeType"),
                    sizeBytes = item.optLong("sizeBytes", 0L),
                    sha256 = item.nullableString("sha256"),
                    localPath = item.nullableString("localPath"),
                ))
            }
        },
    )

    private fun messagesKey(conversationId: String) = "messages_$conversationId"

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    companion object {
        private const val PREFERENCES = "flue_agent_conversations"
        private const val CONVERSATIONS_KEY = "conversations"
    }
}
