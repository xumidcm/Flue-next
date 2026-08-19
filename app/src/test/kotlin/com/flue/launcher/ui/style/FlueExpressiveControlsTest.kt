package com.flue.launcher.ui.style

import com.flue.launcher.ui.theme.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class FlueExpressiveControlsTest {
    @Test
    fun `material3 uses expressive action containers while other styles keep legacy layout`() {
        assertEquals(
            FlueExpressiveActionContainer.BUTTON_GROUP,
            flueExpressiveActionContainer(UiStyle.MATERIAL_3, FlueExpressiveActionKind.LOGIN_ACTIONS),
        )
        assertEquals(
            FlueExpressiveActionContainer.SPLIT_BUTTON,
            flueExpressiveActionContainer(UiStyle.MATERIAL_3, FlueExpressiveActionKind.WEB_ACTIONS),
        )
        assertEquals(
            FlueExpressiveActionContainer.FLOATING_TOOLBAR,
            flueExpressiveActionContainer(UiStyle.MATERIAL_3, FlueExpressiveActionKind.EDITOR_TOOLBAR),
        )
        UiStyle.entries.filterNot { it == UiStyle.MATERIAL_3 }.forEach { style ->
            FlueExpressiveActionKind.entries.forEach { kind ->
                assertEquals(
                    "${style.name} must retain its existing action layout",
                    FlueExpressiveActionContainer.LEGACY,
                    flueExpressiveActionContainer(style, kind),
                )
            }
        }
    }
}
