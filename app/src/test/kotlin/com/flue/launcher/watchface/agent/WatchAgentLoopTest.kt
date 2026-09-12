package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.provider.AgentCompletion
import com.flue.launcher.watchface.agent.provider.AgentProviderRequest
import com.flue.launcher.watchface.agent.provider.AgentRequestMessage
import com.flue.launcher.watchface.agent.provider.AgentStreamEvent
import com.flue.launcher.watchface.agent.runtime.AgentCompletionExecutor
import com.flue.launcher.watchface.agent.runtime.WatchAgentLoop
import com.flue.launcher.watchface.agent.tools.InMemoryWatchAgentToolHost
import com.flue.launcher.watchface.agent.tools.WatchAgentToolRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAgentLoopTest {
    @Test
    fun `tool loop reads then edits the active watch document`() = runBlocking {
        val host = InMemoryWatchAgentToolHost("<Watch name=\"Before\"/>")
        val requests = mutableListOf<AgentProviderRequest>()
        var turn = 0
        val executor = AgentCompletionExecutor { request, _ ->
            requests += request
            when (turn++) {
                0 -> completion(
                    AgentStreamEvent.ToolCallDelta(
                        id = "read-1",
                        name = "read_document",
                        jsonDelta = "{\"path\":\"watch.xml\"}",
                    ),
                )
                1 -> completion(
                    AgentStreamEvent.ToolCallDelta(
                        id = "edit-1",
                        name = "edit_document",
                        jsonDelta = "{\"path\":\"watch.xml\",\"old_text\":\"Before\",\"new_text\":\"After\",\"expected_revision\":1}",
                    ),
                )
                else -> AgentCompletion(
                    text = "已经修改完成。",
                    events = listOf(AgentStreamEvent.TextDelta("已经修改完成。")),
                )
            }
        }

        val result = WatchAgentLoop(executor, WatchAgentToolRunner(host)).run(
            listOf(AgentRequestMessage(AgentMessageRole.USER, "把名称改成 After")),
        )

        assertEquals("After", host.inspectFace(com.flue.launcher.watchface.agent.tools.WatchAgentInspectFaceRequest()).name)
        assertEquals(2L, host.currentRevision)
        assertEquals("已经修改完成。", result.text)
        assertEquals(3, requests.size)
        assertTrue(requests.all { request -> request.tools.any { it.name == "edit_document" } })
        assertTrue(requests[1].messages.any { it.role == AgentMessageRole.TOOL && it.toolCallId == "read-1" })
        assertTrue(requests[2].messages.any { it.role == AgentMessageRole.TOOL && it.toolCallId == "edit-1" })
    }

    @Test
    fun `fragmented tool arguments are joined by slot while retaining provider call id`() = runBlocking {
        val host = InMemoryWatchAgentToolHost("<Watch name=\"Before\"/>")
        var turn = 0
        val executor = AgentCompletionExecutor { _, _ ->
            if (turn++ == 0) {
                completion(
                    AgentStreamEvent.ToolCallDelta(
                        id = "edit-7",
                        name = "edit_document",
                        jsonDelta = "{\"path\":\"watch.xml\",\"old_text\":\"Before\",",
                        slot = 0,
                    ),
                    AgentStreamEvent.ToolCallDelta(
                        id = "0",
                        jsonDelta = "\"new_text\":\"After\",\"expected_revision\":1}",
                        slot = 0,
                    ),
                )
            } else {
                AgentCompletion("完成", listOf(AgentStreamEvent.TextDelta("完成")))
            }
        }

        WatchAgentLoop(executor, WatchAgentToolRunner(host)).run(
            listOf(AgentRequestMessage(AgentMessageRole.USER, "修改")),
        )

        assertEquals("After", host.inspectFace(com.flue.launcher.watchface.agent.tools.WatchAgentInspectFaceRequest()).name)
    }

    private fun completion(vararg events: AgentStreamEvent) = AgentCompletion(
        text = events.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.text },
        events = events.toList(),
    )
}
