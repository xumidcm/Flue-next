package com.flue.launcher.watchface.agent.workspace

data class WorkspacePermissionSettings(
    /** Agent commands run without an approval round-trip by default. */
    val approvalMode: BashApprovalMode = BashApprovalMode.AUTOMATIC,
    val networkAllowed: Boolean = true,
    val readAllowed: Boolean = true,
    val writeAllowed: Boolean = true,
    val attachmentImportAllowed: Boolean = false,
) {
    fun approvalStrategy(): BashApprovalStrategy = WorkspaceToolPolicy(approvalMode)
}
