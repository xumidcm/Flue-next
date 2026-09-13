package com.flue.launcher.watchface.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePermissionSettingsTest {
    @Test
    fun `defaults run agent commands automatically and keep access inside workspace`() {
        val permissions = WorkspacePermissionSettings()

        assertEquals(BashApprovalMode.AUTOMATIC, permissions.approvalMode)
        assertTrue(permissions.networkAllowed)
        assertTrue(permissions.readAllowed)
        assertTrue(permissions.writeAllowed)
        assertFalse(permissions.attachmentImportAllowed)
    }

    @Test
    fun `approval policy follows persisted mode`() {
        val request = ShellCommandRequest("workspace", "printf ok", dryRun = true)

        assertEquals(BashApproval.ALLOW, WorkspacePermissionSettings().approvalStrategy().decide(request))
        assertEquals(
            BashApproval.ALLOW,
            WorkspacePermissionSettings(approvalMode = BashApprovalMode.AUTOMATIC)
                .approvalStrategy()
                .decide(request),
        )
    }
}
