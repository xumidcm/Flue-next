package com.flue.launcher.watchface.agent.workspace

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkspacePermissionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("agent_workspace_permissions", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `missing preferences load the safe defaults`() {
        val settings = WorkspacePermissionStore(context).load()

        assertEquals(BashApprovalMode.EACH_COMMAND, settings.approvalMode)
        assertTrue(settings.networkAllowed)
        assertTrue(settings.readAllowed)
        assertTrue(settings.writeAllowed)
        assertFalse(settings.attachmentImportAllowed)
    }

    @Test
    fun `all permission choices round trip through the single store`() {
        val store = WorkspacePermissionStore(context)
        val expected = WorkspacePermissionSettings(
            approvalMode = BashApprovalMode.HIGH_RISK_ONLY,
            networkAllowed = true,
            readAllowed = false,
            writeAllowed = false,
            attachmentImportAllowed = true,
        )

        store.save(expected)

        assertEquals(expected, WorkspacePermissionStore(context).load())
    }
}
