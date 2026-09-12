package com.flue.launcher.watchface.agent.workspace

data class WorkspacePermissionSettings(
    val approvalMode: BashApprovalMode = BashApprovalMode.EACH_COMMAND,
    val networkAllowed: Boolean = true,
    val readAllowed: Boolean = true,
    val writeAllowed: Boolean = true,
    val attachmentImportAllowed: Boolean = false,
) {
    fun approvalStrategy(): BashApprovalStrategy = WorkspaceToolPolicy(approvalMode)
}
