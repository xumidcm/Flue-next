package com.flue.launcher.ui.settings.agent

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.flue.launcher.R
import com.flue.launcher.ui.settings.miuix.MiuixIcons
import com.flue.launcher.ui.settings.miuix.SettingsIcon
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.style.FlueSwitch
import com.flue.launcher.ui.style.FlueTextField
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.watchface.agent.workspace.AgentWorkspace
import com.flue.launcher.watchface.agent.workspace.AndroidProotProcessGroupRunner
import com.flue.launcher.watchface.agent.workspace.AndroidRootfsSelfChecker
import com.flue.launcher.watchface.agent.workspace.BashApproval
import com.flue.launcher.watchface.agent.workspace.BashApprovalMode
import com.flue.launcher.watchface.agent.workspace.BashApprovalStrategy
import com.flue.launcher.watchface.agent.workspace.RootfsStatus
import com.flue.launcher.watchface.agent.workspace.HttpRootfsArchiveSource
import com.flue.launcher.watchface.agent.workspace.RootfsArchiveFormat
import com.flue.launcher.watchface.agent.workspace.RootfsArchiveSource
import com.flue.launcher.watchface.agent.workspace.RootfsCatalog
import com.flue.launcher.watchface.agent.workspace.RootfsInstallError
import com.flue.launcher.watchface.agent.workspace.RootfsInstallProgress
import com.flue.launcher.watchface.agent.workspace.RootfsInstallResult
import com.flue.launcher.watchface.agent.workspace.RootfsInstallStage
import com.flue.launcher.watchface.agent.workspace.RootfsInstaller
import com.flue.launcher.watchface.agent.workspace.SafeProotShellRunner
import com.flue.launcher.watchface.agent.workspace.ShellCommandRequest
import com.flue.launcher.watchface.agent.workspace.ShellRunResult
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import com.flue.launcher.watchface.agent.workspace.WorkspacePermissionSettings
import com.flue.launcher.watchface.agent.workspace.WorkspacePermissionStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal fun AgentSettingsDestination.titleResource(): Int = when (this) {
    AgentSettingsDestination.OVERVIEW -> R.string.agent_settings_title
    AgentSettingsDestination.PROVIDERS,
    AgentSettingsDestination.PROVIDER_DETAIL -> R.string.agent_overview_provider
    AgentSettingsDestination.PROVIDER_CONNECTION -> R.string.agent_provider_connection
    AgentSettingsDestination.PROVIDER_MODEL -> R.string.agent_provider_model
    AgentSettingsDestination.PROVIDER_ADVANCED -> R.string.agent_provider_advanced
    AgentSettingsDestination.FEATURES -> R.string.agent_overview_features
    AgentSettingsDestination.IMAGE_GENERATION -> R.string.agent_image_generation
    AgentSettingsDestination.WEB_SEARCH -> R.string.agent_web_search
    AgentSettingsDestination.WORKSPACE_FEATURES -> R.string.agent_workspace_search
    AgentSettingsDestination.WORKSPACES -> R.string.agent_workspaces
    AgentSettingsDestination.WORKSPACE_DETAIL -> R.string.agent_workspace_detail
    AgentSettingsDestination.TERMINAL -> R.string.agent_terminal_title
    AgentSettingsDestination.BASH_PERMISSIONS -> R.string.agent_bash_permissions
    AgentSettingsDestination.DATA -> R.string.agent_data
}

@Composable
internal fun AgentWorkspaceDetailPage(
    workspace: AgentWorkspace?,
    workspaceManager: WorkspaceManager,
    onWorkspaceChanged: (AgentWorkspace) -> Unit,
    onOpenTerminal: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (workspace == null) {
        Text(stringResource(R.string.agent_terminal_workspace_missing))
        return
    }
    var displayName by remember(workspace.id, workspace.displayName) { mutableStateOf(workspace.displayName) }
    var confirmDelete by remember(workspace.id) { mutableStateOf(false) }
    var currentDirectory by remember(workspace.id) { mutableStateOf("workspace") }
    var files by remember(workspace.id) { mutableStateOf<List<WorkspaceManager.DirectoryEntry>>(emptyList()) }
    var fileToDelete by remember(workspace.id) { mutableStateOf<WorkspaceManager.DirectoryEntry?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val abi = remember {
        Build.SUPPORTED_ABIS.firstOrNull { RootfsCatalog.forAbi(it) != null }
            ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    }
    val onlinePackage = remember(abi) { RootfsCatalog.forAbi(abi) }
    val installer = remember(context, workspaceManager, abi) {
        RootfsInstaller(
            manager = workspaceManager,
            currentAbi = { abi },
            selfChecker = AndroidRootfsSelfChecker(File(context.applicationInfo.nativeLibraryDir).toPath()),
        )
    }
    var installJob by remember(workspace.id) { mutableStateOf<Job?>(null) }
    var installVersion by remember(workspace.id) { mutableStateOf(0L) }
    var installProgress by remember(workspace.id) { mutableStateOf<RootfsInstallProgress?>(null) }
    var installMessage by remember(workspace.id) { mutableStateOf<String?>(null) }
    var installMessageIsError by remember(workspace.id) { mutableStateOf(false) }
    var showOnlineUrlDialog by remember(workspace.id) { mutableStateOf(false) }
    var onlineUrlDraft by remember(workspace.id) { mutableStateOf("") }

    fun beginInstall(source: RootfsArchiveSource, format: RootfsArchiveFormat) {
        val pinned = onlinePackage ?: return
        val version = ++installVersion
        installJob?.cancel()
        installMessage = null
        installMessageIsError = false
        installProgress = RootfsInstallProgress(RootfsInstallStage.COPYING)
        installJob = scope.launch {
            try {
            val result = runInterruptible(Dispatchers.IO) {
                installer.install(
                    workspaceId = workspace.id,
                    manifest = pinned.manifest,
                    format = format,
                    source = source,
                    onProgress = { if (installVersion == version) installProgress = it },
                )
            }
            when (result) {
                is RootfsInstallResult.Installed -> {
                    installMessage = context.getString(R.string.agent_rootfs_install_complete)
                    installMessageIsError = false
                    onWorkspaceChanged(result.workspace)
                }
                is RootfsInstallResult.Invalid -> {
                    installMessage = context.getString(result.reason.messageResource())
                    installMessageIsError = true
                }
                is RootfsInstallResult.Failed -> {
                    installMessage = context.getString(R.string.agent_rootfs_install_failed)
                    installMessageIsError = true
                }
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (installVersion == version) {
                    installMessage = context.getString(R.string.agent_rootfs_install_failed)
                    installMessageIsError = true
                }
            } finally {
                if (installVersion == version) {
                    installProgress = null
                    installJob = null
                }
            }
        }
    }

    val localRootfsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && onlinePackage != null) {
            beginInstall(
                source = RootfsArchiveSource {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Selected Rootfs package cannot be opened")
                },
                format = onlinePackage.format,
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val name = resolveDisplayName(context, uri)
            val targetDir = currentDirectory.ifEmpty { "workspace" }
            val result = withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "agent-import-${UUID.randomUUID()}")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use(input::copyTo) }
                        ?: return@withContext null
                    workspaceManager.importFile(workspace.id, temp.toPath(), "$targetDir/$name")
                } catch (_: Exception) {
                    null
                } finally { temp.delete() }
            }
            if (result == com.flue.launcher.watchface.agent.workspace.ImportResult.IMPORTED) {
                android.widget.Toast.makeText(context, "导入成功: $name", android.widget.Toast.LENGTH_SHORT).show()
                onWorkspaceChanged(workspaceManager.get(workspace.id) ?: workspace)
                files = withContext(Dispatchers.IO) {
                    runCatching { workspaceManager.listDirectory(workspace.id, currentDirectory) }.getOrDefault(emptyList())
                }
            } else {
                android.widget.Toast.makeText(context, "导入失败: ${result?.name ?: "无法读取文件"}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(workspace.id, workspace.quota.usedBytes, currentDirectory) {
        files = withContext(Dispatchers.IO) {
            runCatching { workspaceManager.listDirectory(workspace.id, currentDirectory) }.getOrDefault(emptyList())
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AgentSettingsGroup {
            AgentSettingsEntry(
                title = workspace.displayName,
                subtitle = stringResource(
                    R.string.agent_workspace_usage,
                    formatWorkspaceBytes(workspace.quota.usedBytes),
                ),
                leading = { SettingsIcon(material = MiuixIcons.Grid, miuix = MiuixIcons.Grid) },
            )
            AgentSettingsEntry(
                title = if (workspace.rootfs.status == RootfsStatus.INSTALLED) {
                    stringResource(
                        R.string.agent_workspace_rootfs_ready,
                        stringResource(if (workspace.rootfs.bashAvailable) R.string.agent_workspace_capability_yes else R.string.agent_workspace_capability_no),
                        stringResource(if (workspace.rootfs.pythonAvailable) R.string.agent_workspace_capability_yes else R.string.agent_workspace_capability_no),
                    )
                } else {
                    stringResource(R.string.agent_workspace_rootfs_missing)
                },
                subtitle = stringResource(R.string.agent_workspace_location, workspace.paths.workspace),
                leading = { SettingsIcon(material = MiuixIcons.Tool, miuix = MiuixIcons.Tool) },
            )
            AgentSettingsEntry(
                title = stringResource(R.string.agent_workspace_id, workspace.id),
                leading = { SettingsIcon(material = MiuixIcons.Info, miuix = MiuixIcons.Info) },
                last = true,
            )
        }
        AgentSettingsFormCard {
            FlueTextField(
                value = displayName,
                onValueChange = { displayName = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.agent_workspace_name),
                singleLine = true,
            )
            FlueButton(
                enabled = displayName.isNotBlank() && displayName.trim() != workspace.displayName,
                onClick = { onRename(workspace.id, displayName.trim()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.agent_workspace_rename)) }
        }
        AgentSettingsGroup {
            AgentSettingsEntry(
                title = stringResource(R.string.agent_workspace_open_terminal),
                onClick = onOpenTerminal,
                leading = { SettingsIcon(material = MiuixIcons.Tool, miuix = MiuixIcons.Tool) },
                trailing = { Icon(MiuixIcons.Forward, contentDescription = null) },
                last = true,
            )
        }
        AgentSettingsFormCard {
            Text(
                stringResource(R.string.agent_rootfs_installer_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (onlinePackage == null) {
                Text(
                    stringResource(R.string.agent_rootfs_unsupported_abi, abi.ifBlank { "unknown" }),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    stringResource(
                        R.string.agent_rootfs_package_summary,
                        "Ubuntu Base ${onlinePackage.manifest.version}",
                        formatWorkspaceBytes(onlinePackage.manifest.archiveSizeBytes),
                        abi,
                    ),
                    color = LauncherTheme.style.secondaryTextColor,
                )
                Text(
                    stringResource(
                        R.string.agent_rootfs_package_hash,
                        onlinePackage.manifest.archiveSha256.take(12),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = LauncherTheme.style.secondaryTextColor,
                )
                FlueButton(
                    onClick = {
                        onlineUrlDraft = onlinePackage.url
                        showOnlineUrlDialog = true
                    },
                    enabled = installJob == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (workspace.rootfs.status == RootfsStatus.INSTALLED) {
                                R.string.agent_rootfs_reinstall_online
                            } else {
                                R.string.agent_rootfs_install_online
                            },
                        ),
                    )
                }
                FlueOutlinedButton(
                    onClick = {
                        localRootfsPicker.launch(
                            arrayOf(
                                "application/gzip",
                                "application/x-gzip",
                                "application/octet-stream",
                            ),
                        )
                    },
                    enabled = installJob == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.agent_rootfs_choose_local)) }
                Text(
                    stringResource(R.string.agent_rootfs_local_requirement),
                    style = MaterialTheme.typography.bodySmall,
                    color = LauncherTheme.style.secondaryTextColor,
                )
            }
            installProgress?.takeIf { installJob != null }?.let { progress ->
                val total = progress.totalBytes
                if (total != null && total > 0L) {
                    val frac = (progress.bytesCopied.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${stringResource(progress.stage.labelResource())} - ${formatWorkspaceBytes(progress.bytesCopied)} / ${formatWorkspaceBytes(total)} (${(frac * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    val copiedText = if (progress.bytesCopied > 0L) " - ${formatWorkspaceBytes(progress.bytesCopied)}" else ""
                    Text(
                        "${stringResource(progress.stage.labelResource())}$copiedText",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (installJob != null) {
                    FlueOutlinedButton(
                        onClick = {
                            installJob?.cancel()
                            installVersion += 1L
                            installJob = null
                            installProgress = null
                            installMessage = context.getString(R.string.agent_rootfs_install_cancelled)
                            installMessageIsError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.agent_rootfs_cancel_install)) }
                }
            }
            installMessage?.let { message ->
                Text(
                    message,
                    color = if (installMessageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        LauncherTheme.style.accentColor
                    },
                )
            }
        }
        AgentSettingsFormCard {
            Text(
                stringResource(R.string.agent_workspace_folders),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentDirectory.isNotEmpty()) {
                    FlueOutlinedButton(
                        onClick = {
                            currentDirectory = if (currentDirectory.contains('/')) currentDirectory.substringBeforeLast('/') else ""
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("返回上级") }
                }
                FlueButton(onClick = { importPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("导入文件") }
            }
            Text(if (currentDirectory.isEmpty()) "/" else "/$currentDirectory", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (files.isEmpty()) {
                Text(
                    stringResource(R.string.agent_workspace_empty),
                    color = LauncherTheme.style.secondaryTextColor,
                )
            } else {
                files.forEach { entry ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val fullPath = if (currentDirectory.isEmpty()) entry.name else "$currentDirectory/${entry.name}"
                        Text(
                            (if (entry.isDirectory) "📁 " else "📄 ") + entry.name,
                            modifier = Modifier
                                .weight(1f)
                                .then(if (entry.isDirectory) Modifier.clickable { currentDirectory = fullPath } else Modifier),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlueOutlinedButton(onClick = { fileToDelete = entry.copy(relativePath = fullPath) }) { Text("删除") }
                    }
                }
            }
        }
        FlueOutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
            borderColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.error,
        ) {
            Text(stringResource(R.string.agent_delete))
        }
    }
    val catalog = onlinePackage
    if (showOnlineUrlDialog && catalog != null) {
        val downloadUrl = onlineUrlDraft.trim()
        val validDownloadUrl = downloadUrl.startsWith("https://", ignoreCase = true) ||
            downloadUrl.startsWith("http://", ignoreCase = true)
        FlueAlertDialog(
            onDismissRequest = {
                showOnlineUrlDialog = false
                onlineUrlDraft = ""
            },
            title = stringResource(R.string.agent_rootfs_download_url_title),
            confirmText = stringResource(R.string.agent_rootfs_install_online),
            confirmEnabled = validDownloadUrl && installJob == null,
            onConfirm = {
                showOnlineUrlDialog = false
                beginInstall(
                    source = HttpRootfsArchiveSource(downloadUrl),
                    format = catalog.format,
                )
                onlineUrlDraft = ""
            },
            dismissText = stringResource(R.string.agent_cancel),
        ) {
            FlueTextField(
                value = onlineUrlDraft,
                onValueChange = { onlineUrlDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.agent_rootfs_download_url),
                supportingText = stringResource(R.string.agent_rootfs_download_url_summary),
                singleLine = true,
                isError = onlineUrlDraft.isNotBlank() && !validDownloadUrl,
            )
        }
    }
    if (confirmDelete) {
        FlueAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(R.string.agent_workspace_delete_title),
            text = stringResource(R.string.agent_workspace_delete_summary),
            confirmText = stringResource(R.string.agent_delete),
            onConfirm = {
                confirmDelete = false
                onDelete(workspace.id)
            },
            dismissText = stringResource(R.string.agent_cancel),
            dangerous = true,
        )
    }
    fileToDelete?.let { entry ->
        FlueAlertDialog(onDismissRequest = { fileToDelete = null }, title = "删除 ${entry.name}？", text = "此操作会从当前工作区移除该项目。", confirmText = stringResource(R.string.agent_delete), dismissText = stringResource(R.string.agent_cancel), dangerous = true, onConfirm = {
            fileToDelete = null
            scope.launch {
                withContext(Dispatchers.IO) {
                    workspaceManager.deleteWorkspaceEntry(workspace.id, entry.relativePath)
                }
                onWorkspaceChanged(workspaceManager.get(workspace.id) ?: workspace)
                files = withContext(Dispatchers.IO) {
                    runCatching { workspaceManager.listDirectory(workspace.id, currentDirectory) }.getOrDefault(emptyList())
                }
            }
        })
    }
}

private fun RootfsInstallStage.labelResource(): Int = when (this) {
    RootfsInstallStage.COPYING -> R.string.agent_rootfs_progress_copying
    RootfsInstallStage.VERIFYING -> R.string.agent_rootfs_progress_verifying
    RootfsInstallStage.EXTRACTING -> R.string.agent_rootfs_progress_extracting
    RootfsInstallStage.PATCHING -> R.string.agent_rootfs_progress_patching
    RootfsInstallStage.SELF_CHECKING -> R.string.agent_rootfs_progress_self_checking
    RootfsInstallStage.COMMITTING -> R.string.agent_rootfs_progress_committing
    RootfsInstallStage.INSTALLED -> R.string.agent_rootfs_install_complete
}

private fun RootfsInstallError.messageResource(): Int = when (this) {
    RootfsInstallError.WORKSPACE_NOT_FOUND -> R.string.agent_terminal_workspace_missing
    RootfsInstallError.ABI_MISMATCH -> R.string.agent_rootfs_error_abi
    RootfsInstallError.SIZE_MISMATCH -> R.string.agent_rootfs_error_size
    RootfsInstallError.SHA256_MISMATCH -> R.string.agent_rootfs_error_hash
    RootfsInstallError.SIGNATURE_INVALID -> R.string.agent_rootfs_error_signature
    RootfsInstallError.INVALID_ARCHIVE -> R.string.agent_rootfs_error_archive
    RootfsInstallError.BASH_UNAVAILABLE -> R.string.agent_rootfs_error_bash
    RootfsInstallError.IO_ERROR -> R.string.agent_rootfs_error_io
}

@Composable
internal fun AgentWorkspaceTerminalPage(
    workspace: AgentWorkspace?,
    permissionStore: WorkspacePermissionStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { WorkspaceManager(File(context.filesDir, "agent-runtime").toPath()) }
    val processRunner = remember(context, permissionStore) {
        AndroidProotProcessGroupRunner(
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir).toPath(),
            networkAllowed = { permissionStore.load().networkAllowed },
        )
    }
    val shellRunner = remember(manager, processRunner) {
        SafeProotShellRunner(
            manager = manager,
            approvalStrategy = BashApprovalStrategy { BashApproval.ALLOW },
            processGroupRunner = processRunner,
        )
    }
    var command by remember(workspace?.id) { mutableStateOf("") }
    var output by remember(workspace?.id) { mutableStateOf(context.getString(R.string.agent_terminal_waiting)) }
    var running by remember(workspace?.id) { mutableStateOf(false) }
    var activeRunId by remember(workspace?.id) { mutableStateOf<String?>(null) }
    var history by remember(workspace?.id) { mutableStateOf<List<String>>(emptyList()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (workspace == null) {
            Text(stringResource(R.string.agent_terminal_workspace_missing))
            return@Column
        }
        AgentSettingsGroup {
            AgentSettingsEntry(
                title = workspace.displayName,
                subtitle = "/workspace",
                leading = { SettingsIcon(material = MiuixIcons.Grid, miuix = MiuixIcons.Grid) },
                last = true,
            )
        }
        if (!workspace.rootfs.bashAvailable) {
            AgentSettingsGroup {
                AgentSettingsEntry(
                    title = stringResource(R.string.agent_terminal_rootfs_missing),
                    leading = { SettingsIcon(material = MiuixIcons.Info, miuix = MiuixIcons.Info) },
                    last = true,
                )
            }
        }
        AgentSettingsFormCard {
            FlueTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.agent_terminal_command),
                placeholder = stringResource(R.string.agent_terminal_command_hint),
                singleLine = false,
                minLines = 2,
                maxLines = 5,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlueButton(
                    modifier = Modifier.weight(1f),
                    enabled = !running && command.isNotBlank() && workspace.rootfs.bashAvailable,
                    onClick = {
                        val submitted = command.trim()
                        val runId = UUID.randomUUID().toString()
                        activeRunId = runId
                        running = true
                        output = context.getString(R.string.agent_terminal_running)
                        history = (listOf(submitted) + history).distinct().take(20)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                shellRunner.run(ShellCommandRequest(workspace.id, submitted, runId = runId))
                            }
                            output = result.toTerminalText(context)
                            running = false
                            activeRunId = null
                        }
                    },
                ) { Text(stringResource(R.string.agent_terminal_run)) }
                FlueOutlinedButton(
                    enabled = running,
                    onClick = {
                        activeRunId?.let { runId ->
                            scope.launch(Dispatchers.IO) { shellRunner.cancel(runId) }
                        }
                    },
                ) { Text(stringResource(R.string.agent_terminal_stop)) }
            }
        }
        AgentSettingsFormCard {
            Text(
                stringResource(R.string.agent_terminal_output),
                style = MaterialTheme.typography.titleMedium,
            )
            SelectionContainer {
                Text(
                    output,
                    modifier = Modifier.fillMaxWidth(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (history.isNotEmpty()) {
            AgentSettingsGroup(stringResource(R.string.agent_terminal_history)) {
                history.forEachIndexed { index, previous ->
                    AgentSettingsEntry(
                        title = previous,
                        onClick = { command = previous },
                        leading = { SettingsIcon(material = MiuixIcons.Tool, miuix = MiuixIcons.Tool) },
                        last = index == history.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AgentBashPermissionPage(
    permissions: WorkspacePermissionSettings,
    onChange: (WorkspacePermissionSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AgentSettingsGroup(title = stringResource(R.string.agent_bash_approval)) {
            BashApprovalMode.entries.forEachIndexed { index, mode ->
                AgentSettingsEntry(
                    title = stringResource(mode.labelResource()),
                    onClick = { onChange(permissions.copy(approvalMode = mode)) },
                    trailing = {
                        if (permissions.approvalMode == mode) {
                            SettingsIcon(material = MiuixIcons.Check, miuix = MiuixIcons.Check)
                        }
                    },
                    last = index == BashApprovalMode.entries.lastIndex,
                )
            }
        }
        AgentSettingsGroup {
            PermissionSwitchRow(
                label = stringResource(R.string.agent_bash_network),
                summary = stringResource(R.string.agent_bash_network_summary),
                checked = permissions.networkAllowed,
            ) { onChange(permissions.copy(networkAllowed = it)) }
            PermissionSwitchRow(stringResource(R.string.agent_bash_read), checked = permissions.readAllowed) {
                onChange(permissions.copy(readAllowed = it))
            }
            PermissionSwitchRow(stringResource(R.string.agent_bash_write), checked = permissions.writeAllowed) {
                onChange(permissions.copy(writeAllowed = it))
            }
            PermissionSwitchRow(
                label = stringResource(R.string.agent_bash_attachment_import),
                checked = permissions.attachmentImportAllowed,
                last = true,
            ) { onChange(permissions.copy(attachmentImportAllowed = it)) }
        }
        Text(stringResource(R.string.agent_bash_manual_summary), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.style.secondaryTextColor)
    }
}

@Composable
private fun PermissionSwitchRow(
    label: String,
    checked: Boolean,
    summary: String? = null,
    last: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) = AgentSettingsEntry(
    title = label,
    subtitle = summary,
    onClick = { onCheckedChange(!checked) },
    trailing = { FlueSwitch(checked = checked, onCheckedChange = onCheckedChange) },
    last = last,
)

private fun BashApprovalMode.labelResource(): Int = when (this) {
    BashApprovalMode.EACH_COMMAND -> R.string.agent_bash_approval_each
    BashApprovalMode.HIGH_RISK_ONLY -> R.string.agent_bash_approval_high_risk
    BashApprovalMode.AUTOMATIC -> R.string.agent_bash_approval_automatic
}

private fun ShellRunResult.toTerminalText(context: Context): String = when (this) {
    is ShellRunResult.Completed -> context.getString(
        R.string.agent_terminal_exit,
        exitCode,
        if (truncated) context.getString(R.string.agent_terminal_truncated) else "",
    ) + if (stdout.isBlank()) "" else "\n\n$stdout"
    is ShellRunResult.DryRun -> command
    is ShellRunResult.InvalidRequest -> context.getString(R.string.agent_terminal_invalid, reason)
    is ShellRunResult.RootfsNotInstalled -> context.getString(R.string.agent_terminal_rootfs_missing)
    is ShellRunResult.RuntimeUnavailable -> context.getString(R.string.agent_terminal_runtime_missing)
    is ShellRunResult.WorkspaceNotFound -> context.getString(R.string.agent_terminal_workspace_missing)
    ShellRunResult.AwaitingApproval -> context.getString(R.string.agent_terminal_approval_waiting)
    ShellRunResult.RejectedApproval -> context.getString(R.string.agent_terminal_rejected)
}

private fun formatWorkspaceBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "imported-file"
}
