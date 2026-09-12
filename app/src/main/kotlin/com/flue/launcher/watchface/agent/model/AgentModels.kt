package com.flue.launcher.watchface.agent.model

import java.util.UUID

enum class AgentMessageRole { USER, ASSISTANT, SYSTEM, TOOL }

enum class AgentMessageStatus { COMPLETE, STREAMING, FAILED, CANCELLED }

data class AgentMessage(
    val id: String,
    val conversationId: String,
    val role: AgentMessageRole,
    val content: String,
    val createdAt: Long,
    val status: AgentMessageStatus = AgentMessageStatus.COMPLETE,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<AgentToolCallRecord> = emptyList(),
    val attachments: List<AgentAttachment> = emptyList(),
    val reasoningContent: String? = null,
) {
    companion object {
        fun user(conversationId: String, content: String, now: Long): AgentMessage =
            AgentMessage(UUID.randomUUID().toString(), conversationId, AgentMessageRole.USER, content, now)

        fun assistant(conversationId: String, content: String, now: Long): AgentMessage =
            AgentMessage(UUID.randomUUID().toString(), conversationId, AgentMessageRole.ASSISTANT, content, now)
    }
}

data class AgentAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
    val localPath: String? = null,
)

data class AgentToolCallRecord(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class AgentConversation(
    val id: String,
    val watchScope: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val endedAt: Long? = null,
    val titleFinalized: Boolean = false,
) {
    companion object {
        private const val LEGACY_UNTITLED_TITLE = "新会话"

        fun new(watchScope: String, now: Long): AgentConversation = AgentConversation(
            id = UUID.randomUUID().toString(),
            watchScope = watchScope,
            title = "",
            createdAt = now,
            updatedAt = now,
        )
    }

    fun withFirstUserMessage(content: String): AgentConversation {
        if (title.isNotBlank() && title != LEGACY_UNTITLED_TITLE) return this
        val compact = content.trim().replace(Regex("\\s+"), " ")
        return copy(title = compact.take(40))
    }
}

enum class AgentSurface { SPLIT, FULLSCREEN }

enum class AgentGenerationStatus { THINKING, CALLING_TOOL, PROCESSING_PREVIEW, PROCESSING_FOLLOW_UP }

data class WatchAgentUiState(
    val watchScope: String,
    val activeConversationId: String? = null,
    val surface: AgentSurface = AgentSurface.SPLIT,
    val inputDraft: String = "",
    val expandedToolIds: Set<String> = emptySet(),
    val expandedToolDetailIds: Set<String> = emptySet(),
    val showHistory: Boolean = false,
    val showSettings: Boolean = false,
    val isGenerating: Boolean = false,
    val pendingFollowUp: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val generationStatus: AgentGenerationStatus? = null,
    val attachments: List<AgentAttachment> = emptyList(),
    val editingMessageId: String? = null,
    val attachmentError: String? = null,
) {
    fun enterFullscreen(): WatchAgentUiState = copy(surface = AgentSurface.FULLSCREEN)

    fun exitFullscreen(): WatchAgentUiState = copy(surface = AgentSurface.SPLIT)

    fun toggleTool(toolId: String): WatchAgentUiState = copy(
        expandedToolIds = if (toolId in expandedToolIds) expandedToolIds - toolId else expandedToolIds + toolId,
    )

    fun toggleToolDetails(toolId: String): WatchAgentUiState = copy(
        expandedToolDetailIds = if (toolId in expandedToolDetailIds) expandedToolDetailIds - toolId else expandedToolDetailIds + toolId,
    )

    companion object {
        fun initial(watchScope: String): WatchAgentUiState = WatchAgentUiState(watchScope = watchScope)
    }
}
