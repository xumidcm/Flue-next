package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.prompt.WatchAgentPrompt
import com.flue.launcher.watchface.agent.tools.InMemoryWatchAgentToolHost
import com.flue.launcher.watchface.agent.tools.WatchAgentInspectFaceRequest
import com.flue.launcher.watchface.agent.tools.WatchAgentToolContracts
import com.flue.launcher.watchface.agent.tools.WatchAgentToolName
import com.flue.launcher.watchface.agent.tools.WatchAgentValidateFaceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAgentDiagnosticsTest {
    @Test
    fun `inspect and validate expose ignored XML layer nodes`() {
        val host = InMemoryWatchAgentToolHost(
            "<Watch><Image path=\"bg.png\"/></Watch>",
        )

        val inspected = host.inspectFace(WatchAgentInspectFaceRequest())
        assertEquals(0, inspected.layersParsed)
        assertEquals(1, inspected.layersInXml)
        assertEquals("mismatch", inspected.parseStatus)

        val validation = host.validateFace(WatchAgentValidateFaceRequest())
        assertTrue(validation.diagnostics.any { it.code == "layer_parse_mismatch" })
        assertTrue(!validation.valid)
    }

    @Test
    fun `agent contract and prompt require render and parse signals`() {
        val inspect = WatchAgentToolContracts.find(WatchAgentToolName.INSPECT_FACE)!!
        val validate = WatchAgentToolContracts.find(WatchAgentToolName.VALIDATE_FACE)!!
        val preview = WatchAgentToolContracts.find(WatchAgentToolName.PREVIEW_FACE)!!
        assertTrue(inspect.description.contains("layers_parsed"))
        assertTrue(validate.description.contains("不能把解析失败报告为 valid=true"))
        assertTrue(preview.description.contains("non_background_pixel_ratio"))
        assertTrue(WatchAgentPrompt.baseline.contains("原点在画布中心 (256,256)"))
        assertTrue(WatchAgentPrompt.baseline.contains("layers_parsed"))
    }
}
