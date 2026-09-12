package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.model.AiModelDescriptor
import com.flue.launcher.watchface.agent.model.AiProtocol
import com.flue.launcher.watchface.agent.model.AiProviderProfile
import com.flue.launcher.watchface.agent.model.ModelMetadataSource
import com.flue.launcher.watchface.agent.provider.AgentModelMetadataResolver
import com.flue.launcher.watchface.agent.provider.AgentProviderException
import com.flue.launcher.watchface.agent.runtime.AgentContextBudgetManager
import com.flue.launcher.watchface.agent.runtime.AgentContextCompressionItem
import com.flue.launcher.watchface.agent.runtime.AgentContextCompressor
import com.flue.launcher.watchface.agent.runtime.AgentContextRetryExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextBudgetTest {
    @Test
    fun `budget reserves output and safety margin before accepting input`() {
        val budget = AgentContextBudgetManager.calculate(
            contextWindowTokens = 100_000L,
            maxOutputTokens = 8_000,
            safetyMarginTokens = 2_000,
            systemPromptTokens = 1_200,
            toolSchemaTokens = 800,
            messageTokens = 84_000,
            attachmentTokens = 1_000,
        )

        assertEquals(90_000L, budget.availableInputTokens)
        assertEquals(87_000L, budget.currentInputTokens)
        assertFalse(budget.requiresCompression)
        assertEquals(97, budget.usagePercent)
    }

    @Test
    fun `metadata resolver honors manual provider public and builtin precedence`() {
        val profile = AiProviderProfile("p", "P", AiProtocol.RESPONSES, "https://example.com", contextWindowOverride = 64_000L)
        val provider = AiModelDescriptor("m", contextWindowTokens = 100_000L, supportsTools = true, metadataSource = ModelMetadataSource.PROVIDER)
        val public = AiModelDescriptor("m", contextWindowTokens = 200_000L, supportsVision = true, metadataSource = ModelMetadataSource.PUBLIC_CATALOG)
        val builtin = AiModelDescriptor("m", contextWindowTokens = 32_000L, maxOutputTokens = 2_000, metadataSource = ModelMetadataSource.BUILTIN)

        val resolved = AgentModelMetadataResolver.resolve(profile, provider, public, builtin)

        assertEquals(64_000L, resolved.contextWindowTokens)
        assertEquals(true, resolved.supportsTools)
        assertEquals(true, resolved.supportsVision)
        assertEquals(2_000, resolved.maxOutputTokens)
        assertEquals(ModelMetadataSource.MANUAL, resolved.metadataSource)
    }

    @Test
    fun `compression keeps required entries and fits target`() {
        val items = listOf(
            AgentContextCompressionItem("system", 8, mustKeep = true),
            AgentContextCompressionItem("old", 12),
            AgentContextCompressionItem("error", 5, mustKeep = true),
            AgentContextCompressionItem("latest", 10, mustKeep = true),
            AgentContextCompressionItem("recent", 6),
        )

        val compacted = AgentContextCompressor.compact(items, targetTokens = 29)

        assertTrue(compacted.sumOf { it.tokenCount } <= 29)
        assertTrue(compacted.map { it.id }.containsAll(listOf("system", "error", "latest")))
        assertTrue(compacted.any { it.id == AgentContextCompressor.SUMMARY_ID })
    }

    @Test
    fun `context overflow is compressed and retried at most once`() = runBlocking {
        var calls = 0
        val result = AgentContextRetryExecutor.execute(
            initial = "full",
            send = { request ->
                calls++
                if (request == "full") throw AgentProviderException("context_length_exceeded")
                request
            },
            compress = { "compressed" },
        )

        assertEquals("compressed", result)
        assertEquals(2, calls)
    }

    @Test
    fun `non context errors are not retried`() = runBlocking {
        var calls = 0
        try {
            AgentContextRetryExecutor.execute(
                initial = "full",
                send = { calls++; throw AgentProviderException("401 invalid api key") },
                compress = { "compressed" },
            )
        } catch (_: AgentProviderException) {
            // expected
        }
        assertEquals(1, calls)
    }
}
