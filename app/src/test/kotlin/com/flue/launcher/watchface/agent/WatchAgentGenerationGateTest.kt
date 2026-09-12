package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.runtime.AgentGenerationGate
import com.flue.launcher.watchface.agent.runtime.AgentPendingToolCallLedger
import com.flue.launcher.watchface.agent.model.AgentToolCallRecord
import com.flue.launcher.watchface.agent.tools.WatchAgentToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAgentGenerationGateTest {
    @Test
    fun `invalidated generation cannot publish into a selected or newer conversation`() {
        val gate = AgentGenerationGate()
        val first = gate.begin(conversationId = "conversation-a", watchScope = "face-a")

        assertTrue(gate.canPublish(first, activeConversationId = "conversation-a", activeWatchScope = "face-a"))

        gate.invalidate()
        assertFalse(gate.canPublish(first, activeConversationId = "conversation-a", activeWatchScope = "face-a"))

        val second = gate.begin(conversationId = "conversation-b", watchScope = "face-a")
        assertFalse(gate.canPublish(first, activeConversationId = "conversation-b", activeWatchScope = "face-a"))
        assertTrue(gate.canPublish(second, activeConversationId = "conversation-b", activeWatchScope = "face-a"))
        assertFalse(gate.canPublish(second, activeConversationId = "conversation-b", activeWatchScope = "face-b"))
        assertFalse(gate.complete(first))
        assertTrue(gate.canPublish(second, activeConversationId = "conversation-b", activeWatchScope = "face-a"))
        var published = false
        assertTrue(gate.publishIfCurrent(second, "conversation-b", "face-a") { published = true })
        assertTrue(published)
        assertTrue(gate.complete(second))
        assertFalse(gate.canPublish(second, activeConversationId = "conversation-b", activeWatchScope = "face-a"))
        published = false
        assertFalse(gate.publishIfCurrent(second, "conversation-b", "face-a") { published = true })
        assertFalse(published)
    }

    @Test
    fun `cancellation drains only unmatched tool calls and cannot duplicate results`() {
        val ledger = AgentPendingToolCallLedger()
        ledger.recordCalls(
            listOf(
                AgentToolCallRecord("call-a", "inspect_face", "{}"),
                AgentToolCallRecord("call-b", "preview_face", "{}"),
            ),
        )
        ledger.recordResults(
            listOf(WatchAgentToolResult("call-a", "inspect_face", revision = 4L)),
        )

        val cancelled = ledger.drainCancelled(revision = 5L)

        assertEquals(listOf("call-b"), cancelled.map { it.callId })
        assertEquals("cancelled", cancelled.single().error?.code)
        assertTrue(ledger.drainCancelled(revision = 6L).isEmpty())
    }
}
