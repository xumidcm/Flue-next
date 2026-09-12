package com.flue.launcher.watchface.agent.storage

import android.content.Context

data class AgentFeatureSettings(
    val enabled: Boolean = true,
    val imageBaseUrl: String = "",
    val imageModelId: String = "",
    val imageSize: String = "1024x1024",
    val imageQuality: String = "auto",
    val imageBackground: String = "auto",
    val tavilyBaseUrl: String = "https://api.tavily.com",
    val searchMaxResults: Int = 5,
    val searchDepth: String = "basic",
    val webFetchEnabled: Boolean = true,
    val workspaceSearchEnabled: Boolean = true,
    val copyAttachmentsIntoWorkspace: Boolean = true,
    val bashApproval: String = "always_ask",
)

/** Feature metadata is ordinary preferences; image/search keys stay in Keystore. */
class AgentFeatureSettingsStore(
    context: Context,
    private val secrets: AiSecretStore = AndroidKeystoreSecretStore(context),
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AgentFeatureSettings = AgentFeatureSettings(
        enabled = preferences.getBoolean("enabled", true),
        imageBaseUrl = preferences.getString("imageBaseUrl", "").orEmpty(),
        imageModelId = preferences.getString("imageModelId", "").orEmpty(),
        imageSize = preferences.getString("imageSize", "1024x1024").orEmpty().ifBlank { "1024x1024" },
        imageQuality = preferences.getString("imageQuality", "auto").orEmpty(),
        imageBackground = preferences.getString("imageBackground", "auto").orEmpty(),
        tavilyBaseUrl = preferences.getString("tavilyBaseUrl", "https://api.tavily.com").orEmpty(),
        searchMaxResults = preferences.getInt("searchMaxResults", 5),
        searchDepth = preferences.getString("searchDepth", "basic").orEmpty(),
        webFetchEnabled = preferences.getBoolean("webFetchEnabled", true),
        workspaceSearchEnabled = preferences.getBoolean("workspaceSearchEnabled", true),
        copyAttachmentsIntoWorkspace = preferences.getBoolean("copyAttachmentsIntoWorkspace", true),
        bashApproval = preferences.getString("bashApproval", "always_ask").orEmpty().ifBlank { "always_ask" },
    )

    fun save(settings: AgentFeatureSettings, imageApiKey: String? = null, tavilyApiKey: String? = null): Boolean {
        preferences.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("imageBaseUrl", settings.imageBaseUrl)
            .putString("imageModelId", settings.imageModelId)
            .putString("imageSize", settings.imageSize)
            .putString("imageQuality", settings.imageQuality)
            .putString("imageBackground", settings.imageBackground)
            .putString("tavilyBaseUrl", settings.tavilyBaseUrl)
            .putInt("searchMaxResults", settings.searchMaxResults)
            .putString("searchDepth", settings.searchDepth)
            .putBoolean("webFetchEnabled", settings.webFetchEnabled)
            .putBoolean("workspaceSearchEnabled", settings.workspaceSearchEnabled)
            .putBoolean("copyAttachmentsIntoWorkspace", settings.copyAttachmentsIntoWorkspace)
            .putString("bashApproval", settings.bashApproval)
            .apply()
        val imageSaved = imageApiKey?.let { secrets.write(IMAGE_SECRET_ID, it) } ?: true
        val searchSaved = tavilyApiKey?.let { secrets.write(TAVILY_SECRET_ID, it) } ?: true
        return imageSaved && searchSaved
    }

    fun saveEnabled(enabled: Boolean): Boolean = preferences.edit().putBoolean("enabled", enabled).commit()

    fun imageApiKey(): String? = secrets.read(IMAGE_SECRET_ID)
    fun tavilyApiKey(): String? = secrets.read(TAVILY_SECRET_ID)
    fun clearImageApiKey() = secrets.remove(IMAGE_SECRET_ID)
    fun clearTavilyApiKey() = secrets.remove(TAVILY_SECRET_ID)

    private companion object {
        const val PREFERENCES = "flue_agent_feature_settings"
        const val IMAGE_SECRET_ID = "agent-image-provider"
        const val TAVILY_SECRET_ID = "agent-tavily"
    }
}
