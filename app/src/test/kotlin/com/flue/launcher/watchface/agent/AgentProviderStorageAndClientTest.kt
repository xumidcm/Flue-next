package com.flue.launcher.watchface.agent

import android.content.Context
import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.model.AiProtocol
import com.flue.launcher.watchface.agent.model.AiProviderProfile
import com.flue.launcher.watchface.agent.provider.AgentErrorSanitizer
import com.flue.launcher.watchface.agent.provider.AgentHttpRequest
import com.flue.launcher.watchface.agent.provider.AgentHttpResponse
import com.flue.launcher.watchface.agent.provider.AgentHttpTransport
import com.flue.launcher.watchface.agent.provider.AgentProviderClient
import com.flue.launcher.watchface.agent.provider.AgentRequestMessage
import com.flue.launcher.watchface.agent.storage.AiSecretStore
import com.flue.launcher.watchface.agent.storage.InMemoryAiSecretStore
import com.flue.launcher.watchface.agent.storage.ProviderSettingsStore
import com.flue.launcher.watchface.agent.storage.AgentFeatureSettingsStore
import com.flue.launcher.watchface.agent.storage.AgentFeatureSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentProviderStorageAndClientTest {
    @Test fun `quick model changes retain provider secrets headers and cached catalog`() {
        val secrets = InMemoryAiSecretStore()
        val store = ProviderSettingsStore(context, secrets)
        val profile = profile().copy(customHeaderNames = mapOf("X-Region" to "private-region"))
        assertTrue(store.save(profile, "secret-key"))
        store.cacheModels(profile.id, listOf(com.flue.launcher.watchface.agent.model.AiModelDescriptor("vision-model", supportsVision = true)))
        store.updateGeneration(profile.id, "vision-model", "high")
        val restored = ProviderSettingsStore(context, secrets)
        assertEquals("vision-model", restored.active()?.modelId)
        assertEquals("high", restored.active()?.reasoningEffort)
        assertEquals("private-region", restored.active()?.customHeaderNames?.get("X-Region"))
        assertEquals("secret-key", restored.apiKey(profile.id))
        assertEquals(true, restored.cachedModels(profile.id).single().supportsVision)
    }
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("flue_agent_provider_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("flue_agent_feature_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `provider metadata never stores api key in ordinary preferences`() {
        val secretStore = InMemoryAiSecretStore()
        val store = ProviderSettingsStore(context, secretStore)
        val profile = profile()

        assertTrue(store.save(profile, apiKey = "sk-live-secret"))

        val raw = context.getSharedPreferences("flue_agent_provider_settings", Context.MODE_PRIVATE)
            .getString("profiles", "")
        assertFalse(raw.orEmpty().contains("sk-live-secret"))
        assertEquals("sk-live-secret", store.apiKey(profile.id))
    }

    @Test
    fun `custom header values stay in secret store and round trip`() {
        val secretStore = InMemoryAiSecretStore()
        val store = ProviderSettingsStore(context, secretStore)
        val headers = linkedMapOf(
            "X-Trace" to "trace-header-secret",
            "X-Region" to "region-header-secret",
        )

        assertTrue(store.save(profile().copy(customHeaderNames = headers)))

        val raw = context.getSharedPreferences("flue_agent_provider_settings", Context.MODE_PRIVATE)
            .getString("profiles", "")
            .orEmpty()
        assertTrue(raw.contains("X-Trace"))
        assertTrue(raw.contains("X-Region"))
        assertFalse(raw.contains("trace-header-secret"))
        assertFalse(raw.contains("region-header-secret"))
        assertEquals(headers, ProviderSettingsStore(context, secretStore).get("demo")?.customHeaderNames)
    }

    @Test
    fun `legacy plaintext custom headers migrate out of provider preferences`() {
        val legacyHeaders = linkedMapOf(
            "X-Legacy" to "legacy-header-secret",
            "X-Trace" to "legacy-trace-secret",
        )
        val legacyProfile = JSONObject()
            .put("id", "demo")
            .put("name", "Demo")
            .put("protocol", AiProtocol.CHAT_COMPLETIONS.name)
            .put("baseUrl", "https://example.com/v1")
            .put("maxOutputTokens", 8_192)
            .put("customHeaderNames", JSONObject(legacyHeaders))
        context.getSharedPreferences("flue_agent_provider_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("profiles", JSONArray().put(legacyProfile).toString())
            .commit()
        val secretStore = InMemoryAiSecretStore()

        assertEquals(legacyHeaders, ProviderSettingsStore(context, secretStore).get("demo")?.customHeaderNames)

        val migratedRaw = context.getSharedPreferences("flue_agent_provider_settings", Context.MODE_PRIVATE)
            .getString("profiles", "")
            .orEmpty()
        assertTrue(migratedRaw.contains("X-Legacy"))
        assertTrue(migratedRaw.contains("X-Trace"))
        assertFalse(migratedRaw.contains("legacy-header-secret"))
        assertFalse(migratedRaw.contains("legacy-trace-secret"))
        assertEquals(legacyHeaders, ProviderSettingsStore(context, secretStore).get("demo")?.customHeaderNames)
    }

    @Test
    fun `removing provider deletes api key and custom header secrets`() {
        val secretStore = TrackingSecretStore()
        val store = ProviderSettingsStore(context, secretStore)
        val headers = mapOf(
            "X-Trace" to "trace-header-secret",
            "X-Region" to "region-header-secret",
        )
        assertTrue(store.save(profile().copy(customHeaderNames = headers), apiKey = "sk-live-secret"))
        assertEquals(3, secretStore.values.size)
        assertEquals(
            setOf("sk-live-secret", "trace-header-secret", "region-header-secret"),
            secretStore.values.values.toSet(),
        )

        store.remove("demo")

        assertTrue(secretStore.values.isEmpty())
    }

    @Test
    fun `image and search settings persist metadata but not api keys`() {
        val secrets = InMemoryAiSecretStore()
        val store = AgentFeatureSettingsStore(context, secrets)
        assertTrue(store.save(AgentFeatureSettings(imageModelId = "image-model", workspaceSearchEnabled = false), "image-secret", "search-secret"))
        assertEquals("image-model", store.load().imageModelId)
        assertFalse(store.load().workspaceSearchEnabled)
        assertEquals("image-secret", store.imageApiKey())
        assertEquals("search-secret", store.tavilyApiKey())
        val raw = context.getSharedPreferences("flue_agent_feature_settings", Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("image-secret"))
        assertFalse(raw.contains("search-secret"))
    }

    @Test
    fun `agent feature is enabled by default and can be toggled independently`() {
        val secrets = InMemoryAiSecretStore()
        val store = AgentFeatureSettingsStore(context, secrets)

        assertTrue(store.load().enabled)
        assertTrue(store.saveEnabled(false))
        assertFalse(store.load().enabled)
        assertTrue(store.saveEnabled(true))
        assertTrue(store.load().enabled)
    }

    @Test
    fun `client sends auth and converts non streaming response`() = runBlocking {
        var captured: AgentHttpRequest? = null
        val transport = AgentHttpTransport { request, _ ->
            captured = request
            AgentHttpResponse(200, """{"choices":[{"message":{"content":"完成"}}]}""")
        }
        val result = AgentProviderClient(transport).complete(
            profile(),
            apiKey = "sk-secret",
            messages = listOf(AgentRequestMessage(AgentMessageRole.USER, "开始")),
            stream = false,
        )

        assertEquals("完成", result.text)
        assertEquals("Bearer sk-secret", captured?.headers?.get("Authorization"))
        assertEquals("https://example.com/v1/chat/completions", captured?.url)
    }

    @Test
    fun `active provider and custom user agent are persisted without allowing auth override`() = runBlocking {
        val secretStore = InMemoryAiSecretStore()
        val store = ProviderSettingsStore(context, secretStore)
        val profile = profile().copy(userAgent = "Flue-Test", customHeaderNames = mapOf("X-Trace" to "agent"))
        store.save(profile, "secret")
        store.setActiveProvider(profile.id)
        assertEquals(profile.id, store.active()?.id)

        var captured: AgentHttpRequest? = null
        val transport = AgentHttpTransport { request, _ ->
            captured = request
            AgentHttpResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")
        }
        AgentProviderClient(transport).complete(
            profile, "secret", listOf(AgentRequestMessage(AgentMessageRole.USER, "go")), stream = false,
            extraHeaders = mapOf("Authorization" to "bad", "X-Extra" to "yes"),
        )
        assertEquals("Flue-Test", captured?.headers?.get("User-Agent"))
        assertEquals("agent", captured?.headers?.get("X-Trace"))
        assertEquals("yes", captured?.headers?.get("X-Extra"))
        assertEquals("Bearer secret", captured?.headers?.get("Authorization"))
    }

    @Test
    fun `http errors are displayable and redact credentials`() {
        val message = AgentErrorSanitizer.forHttp(
            "Demo",
            401,
            "{\"error\":\"invalid api_key=sk-secret\"}",
            secrets = listOf("sk-secret"),
        )

        assertTrue(message.contains("Demo"))
        assertTrue(message.contains("401"))
        assertFalse(message.contains("sk-secret"))
    }

    private fun profile() = AiProviderProfile(
        id = "demo",
        name = "Demo",
        protocol = AiProtocol.CHAT_COMPLETIONS,
        baseUrl = "https://example.com/v1",
        modelId = "watch-model",
    )

    private class TrackingSecretStore : AiSecretStore {
        val values = linkedMapOf<String, String>()

        override fun read(providerId: String): String? = values[providerId]

        override fun write(providerId: String, value: String): Boolean {
            if (value.isBlank()) values.remove(providerId) else values[providerId] = value
            return true
        }

        override fun remove(providerId: String) {
            values.remove(providerId)
        }
    }
}
