package com.flue.launcher.watchface.agent.storage

import android.content.Context
import com.flue.launcher.watchface.agent.model.AiProviderProfile
import com.flue.launcher.watchface.agent.model.AiProtocol
import com.flue.launcher.watchface.agent.model.ParallelToolCallMode
import org.json.JSONArray
import org.json.JSONObject

/** Persists provider metadata only; API keys and custom-header values are delegated to [AiSecretStore]. */
class ProviderSettingsStore(
    context: Context,
    private val secrets: AiSecretStore = AndroidKeystoreSecretStore(context),
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized fun list(): List<AiProviderProfile> = readProfiles()

    /** The selected provider is metadata only; credentials remain in [AiSecretStore]. */
    @Synchronized fun activeProviderId(): String? = preferences.getString(ACTIVE_PROVIDER_KEY, null)

    @Synchronized fun active(): AiProviderProfile? {
        val profiles = readProfiles()
        val id = activeProviderId()
        return profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
    }

    @Synchronized fun setActiveProvider(providerId: String?): Boolean {
        if (providerId != null && readProfiles().none { it.id == providerId }) return false
        preferences.edit().putString(ACTIVE_PROVIDER_KEY, providerId).apply()
        return true
    }

    @Synchronized fun get(id: String): AiProviderProfile? = readProfiles().firstOrNull { it.id == id }

    @Synchronized fun save(profile: AiProviderProfile, apiKey: String? = null): Boolean {
        val current = readProfiles()
        val previousHeaderNames = current.firstOrNull { it.id == profile.id }?.customHeaderNames?.keys.orEmpty()
        if (!writeHeaderSecrets(profile)) return false
        if (apiKey != null && !secrets.write(profile.id, apiKey)) return false

        val next = current.filterNot { it.id == profile.id } + profile
        writeProfileMetadata(next)
        if (activeProviderId() == null) preferences.edit().putString(ACTIVE_PROVIDER_KEY, profile.id).apply()
        (previousHeaderNames - profile.customHeaderNames.keys).forEach { headerName ->
            secrets.remove(headerSecretId(profile.id, headerName))
        }
        return true
    }

    fun apiKey(providerId: String): String? = secrets.read(providerId)

    @Synchronized fun updateGeneration(providerId: String, modelId: String?, reasoningEffort: String?) {
        val profiles = readProfiles()
        writeProfileMetadata(profiles.map {
            if (it.id == providerId) it.copy(modelId = modelId, reasoningEffort = reasoningEffort) else it
        })
    }

    fun cacheModels(providerId: String, models: List<com.flue.launcher.watchface.agent.model.AiModelDescriptor>) {
        preferences.edit().putString("models_$providerId", JSONArray().apply {
            models.distinctBy { it.id }.forEach { put(JSONObject().put("id", it.id).put("name", it.displayName)
                .put("vision", it.supportsVision ?: JSONObject.NULL)) }
        }.toString()).apply()
    }

    fun cachedModels(providerId: String): List<com.flue.launcher.watchface.agent.model.AiModelDescriptor> = runCatching {
        val array = JSONArray(preferences.getString("models_$providerId", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            com.flue.launcher.watchface.agent.model.AiModelDescriptor(item.getString("id"), item.optString("name"),
                supportsVision = if (item.isNull("vision")) null else item.optBoolean("vision"))
        }
    }.getOrDefault(emptyList())

    @Synchronized fun removeSecretOnly(providerId: String) {
        secrets.remove(providerId)
    }

    @Synchronized fun remove(providerId: String) {
        val current = readProfiles()
        val removedHeaderNames = current.firstOrNull { it.id == providerId }?.customHeaderNames?.keys.orEmpty()
        val next = current.filterNot { it.id == providerId }
        writeProfileMetadata(next)
        secrets.remove(providerId)
        removedHeaderNames.forEach { headerName -> secrets.remove(headerSecretId(providerId, headerName)) }
        if (activeProviderId() == providerId) {
            preferences.edit().putString(ACTIVE_PROVIDER_KEY, next.firstOrNull()?.id).apply()
        }
    }

    private fun readProfiles(): List<AiProviderProfile> {
        val array = runCatching { JSONArray(preferences.getString(PROFILES_KEY, "[]")) }.getOrNull() ?: JSONArray()
        var hasLegacyHeaderValues = false
        var migrationSucceeded = true
        val profiles = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val json = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
                val legacyHeaders = json.optJSONObject(CUSTOM_HEADERS_KEY)?.toStringMap()
                if (legacyHeaders != null) {
                    hasLegacyHeaderValues = true
                    val providerId = runCatching { json.getString("id") }.getOrNull()
                    if (providerId == null || !writeHeaderSecrets(providerId, legacyHeaders)) migrationSucceeded = false
                }
                runCatching { fromJson(json, legacyHeaders) }.getOrNull()?.let(::add)
            }
        }
        if (hasLegacyHeaderValues && migrationSucceeded) writeProfileMetadata(profiles)
        return profiles
    }

    private fun toJson(profile: AiProviderProfile) = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("protocol", profile.protocol.name)
        put("baseUrl", profile.baseUrl)
        profile.modelListUrlOverride?.let { put("modelListUrlOverride", it) }
        profile.modelId?.let { put("modelId", it) }
        profile.reasoningEffort?.let { put("reasoningEffort", it) }
        profile.contextWindowOverride?.let { put("contextWindowOverride", it) }
        put("maxOutputTokens", profile.maxOutputTokens)
        put("parallelToolCalls", profile.parallelToolCalls.name)
        put(CUSTOM_HEADERS_KEY, JSONArray(profile.customHeaderNames.keys.toList()))
        profile.userAgent?.takeIf { it.isNotBlank() }?.let { put("userAgent", it) }
    }

    private fun fromJson(json: JSONObject, legacyHeaders: Map<String, String>? = null): AiProviderProfile {
        val providerId = json.getString("id")
        val headerNames = legacyHeaders?.keys ?: json.optJSONArray(CUSTOM_HEADERS_KEY)?.toStringList().orEmpty()
        return AiProviderProfile(
            id = providerId,
            name = json.optString("name", json.getString("id")),
            protocol = AiProtocol.valueOf(json.getString("protocol")),
            baseUrl = json.getString("baseUrl"),
            modelListUrlOverride = json.optString("modelListUrlOverride").takeIf { it.isNotBlank() },
            modelId = json.optString("modelId").takeIf { it.isNotBlank() },
            reasoningEffort = json.optString("reasoningEffort").takeIf { it.isNotBlank() },
            contextWindowOverride = json.optLong("contextWindowOverride").takeIf { it > 0L },
            maxOutputTokens = json.optInt("maxOutputTokens", 0).takeIf { it > 0 },
            customHeaderNames = buildMap {
                headerNames.forEach { headerName ->
                    put(
                        headerName,
                        secrets.read(headerSecretId(providerId, headerName)) ?: legacyHeaders?.get(headerName).orEmpty(),
                    )
                }
            },
            parallelToolCalls = runCatching {
                ParallelToolCallMode.valueOf(json.optString("parallelToolCalls", ParallelToolCallMode.UNKNOWN.name))
            }.getOrDefault(ParallelToolCallMode.UNKNOWN),
            userAgent = json.optString("userAgent").takeIf { it.isNotBlank() },
        )
    }

    private fun writeHeaderSecrets(profile: AiProviderProfile): Boolean =
        writeHeaderSecrets(profile.id, profile.customHeaderNames)

    private fun writeHeaderSecrets(providerId: String, headers: Map<String, String>): Boolean =
        headers.all { (headerName, value) -> secrets.write(headerSecretId(providerId, headerName), value) }

    private fun writeProfileMetadata(profiles: List<AiProviderProfile>) {
        val metadata = JSONArray().apply { profiles.forEach { put(toJson(it)) } }.toString()
        preferences.edit().putString(PROFILES_KEY, metadata).apply()
    }

    private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
        keys().forEach { key -> put(key, optString(key)) }
    }

    private fun JSONArray.toStringList(): List<String> = buildList(length()) {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun headerSecretId(providerId: String, headerName: String): String =
        "$HEADER_SECRET_PREFIX:${providerId.length}:$providerId:${headerName.length}:$headerName"

    private companion object {
        const val PREFERENCES = "flue_agent_provider_settings"
        const val PROFILES_KEY = "profiles"
        const val ACTIVE_PROVIDER_KEY = "activeProviderId"
        const val CUSTOM_HEADERS_KEY = "customHeaderNames"
        const val HEADER_SECRET_PREFIX = "provider-header"
    }
}

typealias AiProviderSettingsStore = ProviderSettingsStore
