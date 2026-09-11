package com.flue.launcher

import org.junit.Assert.*
import org.junit.Test

class WatchEditorFeedbackUiTest {
    @Test fun animationRestartSelectorsUseProtocolTokens() {
        assertTrue(editableAttributesFor("text").contains("restart_on_bright"))
        assertEquals(listOf("Y", "N"), attributeEnumOptions("text", "restart_on_load")?.map { it.value })
        assertEquals(listOf("Y", "N"), attributeEnumOptions("text", "restart_on_bright")?.map { it.value })
    }

    @Test fun allLayerTypesExposeDisplayAndLegacyAmbientColor() {
        listOf("text", "image", "gif", "group", "shape", "ring", "progress", "video").forEach { type ->
            assertTrue("$type display", editableAttributesFor(type).contains("display"))
            assertTrue("$type color_dim", editableAttributesFor(type).contains("color_dim"))
        }
    }
}
