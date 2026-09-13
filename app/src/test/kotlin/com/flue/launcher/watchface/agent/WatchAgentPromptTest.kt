package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.prompt.WatchAgentPrompt
import com.flue.launcher.watchface.agent.workspace.CapabilityReason
import com.flue.launcher.watchface.agent.workspace.WorkspaceCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAgentPromptTest {
    @Test
    fun `baseline names native preview and workspace gating`() {
        assertTrue(WatchAgentPrompt.baseline.contains("validate_face"))
        assertTrue(WatchAgentPrompt.baseline.contains("工作区工具能力只根据本轮动态探测结果声明"))
        assertTrue(WatchAgentPrompt.baseline.contains("只有用户明确要求修改且已有可编辑来源时"))
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

    @Test
    fun `only detected workspace tools appear in each request prompt`() {
        val absent = WorkspaceCapability("", false, false, false, CapabilityReason.WORKSPACE_NOT_FOUND)
        assertEquals("", WatchAgentPrompt.availableCapabilities(absent, networkAllowed = true))

        val python = WorkspaceCapability("workspace", true, true, true, CapabilityReason.READY)
        val prompt = WatchAgentPrompt.availableCapabilities(python, networkAllowed = false)
        assertTrue(prompt.contains("Python3 已安装"))
        assertTrue(prompt.contains("Bash 已安装"))
        assertFalse(prompt.contains("pip3"))
        assertFalse(prompt.contains("apt-get"))
        assertFalse(prompt.contains("网络访问"))

        val all = WatchAgentPrompt.availableCapabilities(
            python.copy(aptAvailable = true, yumAvailable = true, pipAvailable = true),
            networkAllowed = true,
        )
        assertTrue(all.contains("apt-get 可用"))
        assertTrue(all.contains("yum 可用"))
        assertTrue(all.contains("pip3 可用"))
        assertTrue(all.contains("网络访问已允许"))
    }
}
