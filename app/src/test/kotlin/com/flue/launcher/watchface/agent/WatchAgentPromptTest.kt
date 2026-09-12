package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.prompt.WatchAgentPrompt
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAgentPromptTest {
    @Test
    fun `baseline names native preview and workspace gating`() {
        assertTrue(WatchAgentPrompt.baseline.contains("validate_face"))
        assertTrue(WatchAgentPrompt.baseline.contains("未安装 Rootfs 或未绑定工作区"))
        assertTrue(WatchAgentPrompt.promptVersion.isNotBlank())
        assertTrue(WatchAgentPrompt.toolSchemaVersion.isNotBlank())
    }
}
