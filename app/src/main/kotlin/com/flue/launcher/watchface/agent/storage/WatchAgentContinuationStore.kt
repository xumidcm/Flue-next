package com.flue.launcher.watchface.agent.storage

import android.content.Context

/**
 * Small durable marker for an Agent-edited face draft. The face contents live
 * in WorkspaceManager's controlled `face/` mirror; this store only records
 * which mirror and conversation should be offered for continuation.
 */
data class WatchAgentContinuation(
    val watchScope: String,
    val workspaceId: String,
    val conversationId: String,
    val mirrorRevision: Long,
    val savedAt: Long,
)

class WatchAgentContinuationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun load(watchScope: String): WatchAgentContinuation? {
        if (watchScope.isBlank()) return null
        val prefix = keyPrefix(watchScope)
        val workspaceId = preferences.getString(prefix + WORKSPACE_ID, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val conversationId = preferences.getString(prefix + CONVERSATION_ID, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val revision = preferences.getLong(prefix + MIRROR_REVISION, -1L)
        val savedAt = preferences.getLong(prefix + SAVED_AT, 0L)
        if (revision < 0L || savedAt <= 0L) return null
        return WatchAgentContinuation(watchScope, workspaceId, conversationId, revision, savedAt)
    }

    @Synchronized
    fun save(
        watchScope: String,
        workspaceId: String,
        conversationId: String,
        mirrorRevision: Long,
        savedAt: Long = System.currentTimeMillis(),
    ) {
        require(watchScope.isNotBlank()) { "Watch scope cannot be blank" }
        require(workspaceId.isNotBlank()) { "Workspace id cannot be blank" }
        require(conversationId.isNotBlank()) { "Conversation id cannot be blank" }
        require(mirrorRevision >= 0L) { "Mirror revision cannot be negative" }
        val prefix = keyPrefix(watchScope)
        preferences.edit()
            .putString(prefix + WORKSPACE_ID, workspaceId)
            .putString(prefix + CONVERSATION_ID, conversationId)
            .putLong(prefix + MIRROR_REVISION, mirrorRevision)
            .putLong(prefix + SAVED_AT, savedAt)
            .commit()
    }

    @Synchronized
    fun clear(watchScope: String) {
        if (watchScope.isBlank()) return
        val prefix = keyPrefix(watchScope)
        preferences.edit()
            .remove(prefix + WORKSPACE_ID)
            .remove(prefix + CONVERSATION_ID)
            .remove(prefix + MIRROR_REVISION)
            .remove(prefix + SAVED_AT)
            .commit()
    }

    private fun keyPrefix(scope: String): String = PREFIX + scope

    private companion object {
        const val PREFERENCES = "flue_agent_continuations"
        const val PREFIX = "scope_"
        const val WORKSPACE_ID = "_workspace"
        const val CONVERSATION_ID = "_conversation"
        const val MIRROR_REVISION = "_revision"
        const val SAVED_AT = "_saved_at"
    }
}
