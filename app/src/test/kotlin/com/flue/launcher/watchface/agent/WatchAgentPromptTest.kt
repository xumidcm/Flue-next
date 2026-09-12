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

    @Test
    fun `baseline carries the essential format and script rules`() {
        val prompt = WatchAgentPrompt.baseline
        assertTrue(prompt.contains("根节点为 <Watch>"))
        assertTrue(prompt.contains("512×512"))
        assertTrue(prompt.contains("alignment"))
        assertTrue(prompt.contains("tap_action"))
        assertTrue(prompt.contains("var_s_t1"))
        assertTrue(prompt.contains("function/local/return/end"))
        assertTrue(prompt.contains("V2"))
    }
}
