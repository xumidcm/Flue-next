package com.flue.launcher.watchface.agent

import android.app.Application
import android.content.Context
import com.flue.launcher.watchface.agent.model.AgentMessage
import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.runtime.WatchAgentViewModel
import com.flue.launcher.watchface.agent.storage.AgentConversationRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchAgentViewModelRetryTest {
    private lateinit var application: Application
    private lateinit var repository: AgentConversationRepository
    private lateinit var viewModel: WatchAgentViewModel

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("flue_agent_conversations", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repository = AgentConversationRepository(application)
        viewModel = WatchAgentViewModel(application)
    }

    @After
    fun tearDown() {
        viewModel.stop()
    }

    @Test
    fun `repeated retries reuse the original user message instead of appending duplicates`() {
        val conversation = repository.createConversation("face-a", now = 10L)
        val user = AgentMessage.user(conversation.id, "重新调整", now = 20L)
        repository.appendMessage(conversation.id, user)
        repository.appendMessage(conversation.id, AgentMessage.assistant(conversation.id, "旧回答", now = 30L))
        viewModel.onEditorEntered("face-a")
        viewModel.selectConversation(conversation.id)

        viewModel.retryMessage(user.id)
        assertEquals(1, repository.listMessages(conversation.id).count { it.role == AgentMessageRole.USER })

        viewModel.stop()
        viewModel.retryMessage(user.id)
        assertEquals(1, repository.listMessages(conversation.id).count { it.role == AgentMessageRole.USER })
    }
}
