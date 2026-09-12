package com.flue.launcher.watchface.agent

import android.content.Context
import com.flue.launcher.watchface.agent.model.AgentMessage
import com.flue.launcher.watchface.agent.model.AgentAttachment
import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.model.AgentMessageStatus
import com.flue.launcher.watchface.agent.model.AgentMessageLayout
import com.flue.launcher.watchface.agent.model.AgentToolCallRecord
import com.flue.launcher.watchface.agent.model.agentMessageLayout
import com.flue.launcher.watchface.agent.model.shouldRenderMessageBody
import com.flue.launcher.watchface.agent.storage.AgentConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class AgentConversationRepositoryTest {
    @Test fun `automatic titles cannot overwrite manual names or resurrect deleted conversations`() {
        val conversation = repository.createConversation("face-a")
        repository.appendMessage(conversation.id, AgentMessage.user(conversation.id, "做个天气表盘", 20L))
        repository.applyGeneratedTitle(conversation.id, "天气与温度")
        assertEquals("天气与温度", repository.getConversation(conversation.id)?.title)
        repository.renameConversation(conversation.id, "我的天气")
        repository.applyGeneratedTitle(conversation.id, "迟到的标题")
        assertEquals("我的天气", repository.getConversation(conversation.id)?.title)
        repository.deleteConversation(conversation.id)
        repository.applyGeneratedTitle(conversation.id, "已删除")
        assertNull(repository.getConversation(conversation.id))
        assertTrue(repository.listMessages(conversation.id).isEmpty())
    }

    @Test fun `manual rename during first title generation wins`() {
        val conversation = repository.createConversation("face-a")
        repository.renameConversation(conversation.id, "保留这个名字")
        repository.applyGeneratedTitle(conversation.id, "模型的名字")
        assertEquals("保留这个名字", repository.getConversation(conversation.id)?.title)
    }

    private lateinit var repository: AgentConversationRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("flue_agent_conversations", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repository = AgentConversationRepository(context)
    }

    @Test
    fun `history is filtered by watch scope and messages are ordered`() {
        val a = repository.createConversation("face-a", now = 10L)
        val b = repository.createConversation("face-b", now = 20L)
        repository.appendMessage(a.id, AgentMessage.user(a.id, "第一条", now = 30L))
        repository.appendMessage(a.id, AgentMessage.assistant(a.id, "第二条", now = 40L))

        assertEquals(listOf(a.id), repository.listConversations("face-a").map { it.id })
        assertEquals(listOf(b.id), repository.listConversations("face-b").map { it.id })
        assertEquals(listOf("第一条", "第二条"), repository.listMessages(a.id).map { it.content })
    }

    @Test
    fun `first user message updates title without changing scope`() {
        val conversation = repository.createConversation("face-a", now = 10L)
        val updated = repository.applyUserTitle(conversation.id, "  夜光天气  ", now = 50L)

        assertEquals("夜光天气", updated.title)
        assertEquals("face-a", updated.watchScope)
        assertTrue(repository.listConversations("face-b").isEmpty())
    }

    @Test
    fun `conversation can be renamed and attachments survive reload`() {
        val conversation = repository.createConversation("face-a", now = 10L)
        val message = AgentMessage.user(conversation.id, "带附件", 20L).copy(
            attachments = listOf(AgentAttachment("a", "note.txt", "text/plain", 4L, "abcd", "/tmp/note.txt")),
        )
        repository.appendMessage(conversation.id, message)
        repository.renameConversation(conversation.id, "  自定义会话  ")
        assertEquals("自定义会话", repository.getConversation(conversation.id)?.title)
        assertEquals("abcd", repository.listMessages(conversation.id).single().attachments.single().sha256)
    }

    @Test
    fun `attachment optional fields remain null after persistence`() {
        val conversation = repository.createConversation("face-a", now = 10L)
        val message = AgentMessage.user(conversation.id, "无可选元数据附件", 20L).copy(
            attachments = listOf(AgentAttachment("a", "unknown.bin", null, 4L, null, null)),
        )
        repository.appendMessage(conversation.id, message)

        val attachment = repository.listMessages(conversation.id).single().attachments.single()

        assertNull(attachment.mimeType)
        assertNull(attachment.sha256)
        assertNull(attachment.localPath)
    }

    @Test
    fun `editing a user message replaces it and truncates the following branch`() {
        val conversation = repository.createConversation("face-a", now = 10L)
        val user = AgentMessage.user(conversation.id, "旧问题", 20L)
        repository.appendMessage(conversation.id, user)
        repository.appendMessage(conversation.id, AgentMessage.assistant(conversation.id, "旧回答", 30L))

        repository.replaceMessageAndDeleteAfter(user.copy(content = "新问题"))

        val messages = repository.listMessages(conversation.id)
        assertEquals(1, messages.size)
        assertEquals(user.id, messages.single().id)
        assertEquals("新问题", messages.single().content)
    }

    @Test
    fun `final assistant replacement stays after tool results even with placeholder timestamp`() {
        val conversation = repository.createConversation("face-a", now = 10L)
        repository.appendMessage(conversation.id, AgentMessage.user(conversation.id, "调整表盘", 20L))
        val placeholder = AgentMessage(
            id = "assistant",
            conversationId = conversation.id,
            role = AgentMessageRole.ASSISTANT,
            content = "",
            createdAt = 21L,
            status = AgentMessageStatus.STREAMING,
        )
        repository.appendMessage(conversation.id, placeholder)
        repository.appendMessage(
            conversation.id,
            AgentMessage(
                id = "tool",
                conversationId = conversation.id,
                role = AgentMessageRole.TOOL,
                content = "{\"ok\":true}",
                createdAt = 30L,
                toolName = "inspect_face",
            ),
        )

        repository.appendMessage(
            conversation.id,
            placeholder.copy(content = "已经完成调整。", status = AgentMessageStatus.COMPLETE),
        )

        assertEquals(
            listOf("调整表盘", "{\"ok\":true}", "已经完成调整。"),
            repository.listMessages(conversation.id).map { it.content },
        )
        assertEquals(30L, repository.getConversation(conversation.id)?.updatedAt)
    }

    @Test
    fun `persisted tool round keeps user assistant reasoning and tool payload in separate channels`() {
        val conversation = repository.createConversation("face-a", now = 10L)
        repository.appendMessage(
            conversation.id,
            AgentMessage.user(conversation.id, "把指针改成蓝色", now = 20L),
        )
        repository.appendMessage(
            conversation.id,
            AgentMessage(
                id = "assistant-tool-turn",
                conversationId = conversation.id,
                role = AgentMessageRole.ASSISTANT,
                content = "我先检查当前表盘。",
                createdAt = 21L,
                reasoningContent = "先读取图层，再决定修改范围。",
                toolCalls = listOf(
                    AgentToolCallRecord(
                        id = "inspect-1",
                        name = "inspect_face",
                        argumentsJson = "{\"include_assets\":true}",
                    ),
                ),
            ),
        )
        repository.appendMessage(
            conversation.id,
            AgentMessage(
                id = "tool-result",
                conversationId = conversation.id,
                role = AgentMessageRole.TOOL,
                content = "{\"ok\":true,\"result\":{\"layers\":5}}",
                createdAt = 22L,
                toolName = "inspect_face",
                toolCallId = "inspect-1",
            ),
        )
        repository.appendMessage(
            conversation.id,
            AgentMessage.assistant(conversation.id, "已经把指针改成蓝色。", now = 23L),
        )

        val restored = repository.listMessages(conversation.id)

        assertEquals(
            listOf(
                AgentMessageLayout.USER_BUBBLE,
                AgentMessageLayout.ASSISTANT_FLOW,
                AgentMessageLayout.TOOL_STEP,
                AgentMessageLayout.ASSISTANT_FLOW,
            ),
            restored.map(::agentMessageLayout),
        )
        assertEquals(
            listOf("把指针改成蓝色", "我先检查当前表盘。", "已经把指针改成蓝色。"),
            restored.filter(::shouldRenderMessageBody).map { it.content },
        )
        assertEquals("先读取图层，再决定修改范围。", restored[1].reasoningContent)
        assertEquals("{\"include_assets\":true}", restored[1].toolCalls.single().argumentsJson)
        assertTrue(restored.filter(::shouldRenderMessageBody).none { it.content.contains("include_assets") })
        assertEquals(null, restored[0].toolName)
        assertEquals(null, restored[0].toolCallId)
        assertEquals(null, restored[0].reasoningContent)
        assertEquals(null, restored[3].toolName)
    }
}
