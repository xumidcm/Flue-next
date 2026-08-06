package com.flue.launcher.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetBindingRecoveryPolicyTest {
    @Test
    fun `有效绑定直接渲染且不恢复`() {
        assertEquals(
            WidgetBindingDecision.VALID,
            widgetBindingDecision(
                savedId = 42,
                expectedProviderKey = "pkg/Widget",
                hostIds = setOf(42),
                actualProviderKey = "pkg/Widget",
                providerInstalled = true,
            ),
        )
    }

    @Test
    fun `旧 id 不属于当前 host 时请求恢复`() {
        assertEquals(
            WidgetBindingDecision.RECOVER,
            widgetBindingDecision(
                savedId = 42,
                expectedProviderKey = "pkg/Widget",
                hostIds = emptySet(),
                actualProviderKey = null,
                providerInstalled = true,
            ),
        )
    }

    @Test
    fun `provider 不匹配时请求恢复`() {
        assertEquals(
            WidgetBindingDecision.RECOVER,
            widgetBindingDecision(
                savedId = 42,
                expectedProviderKey = "pkg/Widget",
                hostIds = setOf(42),
                actualProviderKey = "other/Widget",
                providerInstalled = true,
            ),
        )
    }

    @Test
    fun `provider 已卸载时显示不可用而不是循环分配`() {
        assertEquals(
            WidgetBindingDecision.PROVIDER_UNAVAILABLE,
            widgetBindingDecision(
                savedId = 42,
                expectedProviderKey = "pkg/Widget",
                hostIds = emptySet(),
                actualProviderKey = null,
                providerInstalled = false,
            ),
        )
    }

    @Test
    fun `恢复门只允许同一槽位启动一次`() {
        val gate = WidgetRecoveryGate()

        assertEquals(true, gate.tryStart("slot-0"))
        assertEquals(false, gate.tryStart("slot-0"))
        gate.finish("slot-0")
        assertEquals(false, gate.tryStart("slot-0"))
        gate.reset("slot-0")
        assertEquals(true, gate.tryStart("slot-0"))
    }
}
