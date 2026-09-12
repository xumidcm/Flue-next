package com.flue.launcher.watchface.agent.runtime

import com.flue.launcher.watchface.agent.model.AiModelDescriptor
import com.flue.launcher.watchface.agent.model.AiProviderProfile
import com.flue.launcher.watchface.agent.workspace.AgentWorkspace

data class AgentConversationChoices(
    val profile: AiProviderProfile? = null,
    val models: List<AiModelDescriptor> = emptyList(),
    val workspaces: List<AgentWorkspace> = emptyList(),
    val workspaceId: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
)
