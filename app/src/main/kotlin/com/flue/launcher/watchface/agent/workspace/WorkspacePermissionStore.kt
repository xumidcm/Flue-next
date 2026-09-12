package com.flue.launcher.watchface.agent.workspace

import android.content.Context

/** Single persisted source of truth for Agent workspace, attachment, and shell permissions. */
class WorkspacePermissionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun load(): WorkspacePermissionSettings {
        val defaults = WorkspacePermissionSettings()
        val mode = runCatching {
            BashApprovalMode.valueOf(preferences.getString(KEY_APPROVAL_MODE, defaults.approvalMode.name)!!)
        }.getOrDefault(defaults.approvalMode)
        return WorkspacePermissionSettings(
            approvalMode = mode,
            networkAllowed = preferences.getBoolean(KEY_NETWORK_ALLOWED, defaults.networkAllowed),
            readAllowed = preferences.getBoolean(KEY_READ_ALLOWED, defaults.readAllowed),
            writeAllowed = preferences.getBoolean(KEY_WRITE_ALLOWED, defaults.writeAllowed),
            attachmentImportAllowed = preferences.getBoolean(KEY_ATTACHMENT_IMPORT_ALLOWED, defaults.attachmentImportAllowed),
        )
    }

    @Synchronized
    fun save(settings: WorkspacePermissionSettings) {
        preferences.edit()
            .putString(KEY_APPROVAL_MODE, settings.approvalMode.name)
            .putBoolean(KEY_NETWORK_ALLOWED, settings.networkAllowed)
            .putBoolean(KEY_READ_ALLOWED, settings.readAllowed)
            .putBoolean(KEY_WRITE_ALLOWED, settings.writeAllowed)
            .putBoolean(KEY_ATTACHMENT_IMPORT_ALLOWED, settings.attachmentImportAllowed)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "agent_workspace_permissions"
        const val KEY_APPROVAL_MODE = "approval_mode"
        const val KEY_NETWORK_ALLOWED = "network_allowed"
        const val KEY_READ_ALLOWED = "read_allowed"
        const val KEY_WRITE_ALLOWED = "write_allowed"
        const val KEY_ATTACHMENT_IMPORT_ALLOWED = "attachment_import_allowed"
    }
}
