package com.flue.launcher.ui.style

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlueOverlayControllerTest {
    @Test
    fun `nested prompts share one chain and restore the previous prompt`() {
        val controller = FlueOverlayController()
        val menu = entry("menu")
        val rename = entry("rename")

        controller.register(menu)
        val firstToken = controller.chainToken
        controller.register(rename)

        assertTrue(controller.chainOpen)
        assertEquals(firstToken, controller.chainToken)
        assertSame(rename, controller.entries.last())

        controller.unregister(rename.id)
        assertSame(menu, controller.entries.last())
        assertEquals(firstToken, controller.chainToken)
    }

    @Test
    fun `a prompt replacement before chain retirement reuses the capture`() {
        val controller = FlueOverlayController()
        val first = entry("first")
        controller.register(first)
        val token = controller.chainToken
        controller.unregister(first.id)

        controller.register(entry("replacement"))

        assertTrue(controller.chainOpen)
        assertEquals(token, controller.chainToken)
        controller.unregister(controller.entries.single().id)
        controller.finishEmptyChain()
        assertFalse(controller.chainOpen)
    }

    private fun entry(id: Any) = FlueOverlayEntry(
        id = id,
        onDismissRequest = {},
        dismissOnBackPress = true,
        uiScale = 1f,
        modifier = Modifier,
        maxHeightFraction = 0.92f,
        scrollableContent = true,
        content = EmptyContent,
    )

    private companion object {
        val EmptyContent: @Composable ColumnScope.() -> Unit = {}
    }
}
