package com.flue.launcher.watchface.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUiContractTest {
    @Test
    fun `watch face selector delegates vertical scrolling to fullscreen host`() {
        val selector = source("com/example/wlauncher/ui/home/WatchFaceCardSelector.kt")

        assertFalse(selector.contains(".verticalScroll("))
        assertFalse(selector.contains("flueOverscroll("))
    }

    @Test
    fun `agent settings use one lazy list and expose workspace management`() {
        val settings = source("com/flue/launcher/ui/settings/agent/AgentSettingsPage.kt")
        val components = source("com/flue/launcher/ui/settings/agent/AgentSettingsComponents.kt")

        assertTrue(components.contains("LazyColumn("))
        assertFalse(settings.contains(".verticalScroll("))
        assertFalse(components.contains(".verticalScroll("))
        assertTrue(settings.contains("WorkspaceManager("))
        assertTrue(settings.contains("agent_create_workspace"))
    }

    @Test
    fun `provider mutations keep activation explicit and ignore stale async results`() {
        val settings = source("com/flue/launcher/ui/settings/agent/AgentSettingsPage.kt")
        val saveProvider = settings.substringAfter("fun saveProvider()").substringBefore("fun testConnection()")

        assertEquals(1, Regex("store\\.setActiveProvider\\(").findAll(settings).count())
        assertTrue(settings.contains("var selectedId by rememberSaveable"))
        assertTrue(settings.contains("val operationProviderId = selectedId"))
        assertTrue(settings.contains("selectedId != operationProviderId"))
        assertEquals(
            2,
            Regex("providerOperationVersion != operationVersion \\|\\| selectedId != operationProviderId")
                .findAll(saveProvider)
                .count(),
        )
    }

    @Test
    fun `settings destinations own independent scroll state and workspace mutations isolate failures`() {
        val settings = source("com/flue/launcher/ui/settings/agent/AgentSettingsPage.kt")
        val components = source("com/flue/launcher/ui/settings/agent/AgentSettingsComponents.kt")

        assertTrue(components.contains("remember(stateKey) { LazyListState() }"))
        assertTrue(components.contains("LaunchedEffect(stateKey)"))
        assertTrue(components.contains("state.scrollToItem(0)"))
        assertTrue(settings.contains("runCatching { workspaceManager.rename"))
        assertTrue(settings.contains("check(workspaceManager.delete"))
    }

    @Test
    fun `one permission store gates settings terminal tools and attachment import`() {
        val settings = source("com/flue/launcher/ui/settings/agent/AgentSettingsPage.kt")
        val workspacePages = source("com/flue/launcher/ui/settings/agent/AgentWorkspaceSettingsPages.kt")
        val viewModel = source("com/flue/launcher/watchface/agent/runtime/WatchAgentViewModel.kt")
        val toolRunner = source("com/flue/launcher/watchface/agent/tools/WatchAgentToolRunner.kt")

        assertTrue(settings.contains("WorkspacePermissionStore(context)"))
        assertTrue(workspacePages.contains("permissionStore.load().networkAllowed"))
        assertTrue(viewModel.contains("permissionProvider = permissionStore::load"))
        assertTrue(viewModel.contains("permissionStore.load().attachmentImportAllowed"))
        assertTrue(toolRunner.contains("permission_denied"))
    }

    @Test
    fun `rootfs failure surface never renders raw installer diagnostics`() {
        val workspacePages = source("com/flue/launcher/ui/settings/agent/AgentWorkspaceSettingsPages.kt")

        assertFalse(workspacePages.contains("agent_rootfs_install_failed, result.message"))
        assertTrue(workspacePages.contains("getString(R.string.agent_rootfs_install_failed)"))
    }

    @Test
    fun `agent input relies on host resize and does not apply ime inset twice`() {
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")

        assertTrue(panel.contains("OpenMultipleDocuments"))
        assertTrue(panel.contains("Modifier.fillMaxWidth().padding(8.dp)"))
        assertFalse(panel.contains("imePadding()"))
        assertFalse(panel.contains("Column(modifier.imePadding()"))
    }

    @Test
    fun `tool cards expose localized summaries instead of raw payloads`() {
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")

        assertTrue(panel.contains("toolTitleResource"))
        assertTrue(panel.contains("ToolCardPresentation"))
        assertFalse(panel.contains("if (expanded) message.content"))
        assertFalse(panel.contains("agent_view_raw"))
        assertFalse(panel.contains("agent_collapse_raw"))
    }

    @Test
    fun `assistant messages use content flow and agent entry uses a sparkle`() {
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")
        val editor = source("com/example/wlauncher/WatchEditorActivity.kt")
        val icons = source("com/example/wlauncher/ui/settings/miuix/MiuixIcons.kt")

        assertTrue(panel.contains("AgentMessageLayout.ASSISTANT_FLOW"))
        assertTrue(panel.contains("AgentMessageLayout.USER_BUBBLE"))
        assertTrue(panel.contains("AgentMessageLayout.TOOL_STEP"))
        assertTrue(editor.contains("FlueMiuixIcons.Sparkle"))
        assertTrue(icons.contains("val Sparkle: ImageVector"))
    }

    @Test
    fun `agent settings expose navigable workspace terminal and permission pages`() {
        val settings = source("com/flue/launcher/ui/settings/agent/AgentSettingsPage.kt")
        val workspacePages = source("com/flue/launcher/ui/settings/agent/AgentWorkspaceSettingsPages.kt")

        assertTrue(settings.contains("AgentSettingsDestination.OVERVIEW"))
        assertTrue(settings.contains("AgentSettingsDestination.WORKSPACES"))
        assertTrue(settings.contains("AgentSettingsDestination.WORKSPACE_DETAIL"))
        assertTrue(settings.contains("AgentSettingsDestination.TERMINAL"))
        assertTrue(settings.contains("AgentSettingsDestination.BASH_PERMISSIONS"))
        assertTrue(workspacePages.contains("agent_terminal_run"))
        assertTrue(workspacePages.contains("shellRunner.run("))
        assertTrue(workspacePages.contains("shellRunner.cancel("))
    }

    @Test
    fun `agent settings and workspace pages use style adaptive flue components`() {
        val settings = source("com/flue/launcher/ui/settings/agent/AgentSettingsPage.kt")
        val components = source("com/flue/launcher/ui/settings/agent/AgentSettingsComponents.kt")
        val workspacePages = source("com/flue/launcher/ui/settings/agent/AgentWorkspaceSettingsPages.kt")

        assertTrue(settings.contains("AgentSettingsGroup("))
        assertTrue(settings.contains("AgentSettingsEntry("))
        assertTrue(settings.contains("FlueTextField("))
        assertFalse(settings.contains("MiuixSettingsCard("))
        assertFalse(settings.contains("MiuixSettingsRow("))
        assertTrue(components.contains("UiStyle.MIUIX"))
        assertTrue(components.contains("UiStyle.MATERIAL_3"))
        assertTrue(components.contains("UiStyle.APPLE_WATCH"))
        assertTrue(settings.contains("SettingsIcon("))
        assertTrue(workspacePages.contains("AgentSettingsGroup("))
        assertTrue(workspacePages.contains("AgentSettingsEntry("))
        assertTrue(workspacePages.contains("FlueTextField("))
        assertFalse(workspacePages.contains("MiuixSettingsCard("))
        assertFalse(workspacePages.contains("MiuixSettingsRow("))
    }

    @Test
    fun `provider setup is split into clear secondary pages`() {
        val settings = source("com/flue/launcher/ui/settings/agent/AgentSettingsPage.kt")

        assertTrue(settings.contains("AgentSettingsDestination.PROVIDERS"))
        assertTrue(settings.contains("AgentSettingsDestination.PROVIDER_DETAIL"))
        assertTrue(settings.contains("AgentSettingsDestination.PROVIDER_CONNECTION"))
        assertTrue(settings.contains("AgentSettingsDestination.PROVIDER_MODEL"))
        assertTrue(settings.contains("AgentSettingsDestination.PROVIDER_ADVANCED"))
        assertFalse(settings.contains("AgentSettingsDestination.PROVIDER,"))
    }

    @Test
    fun `agent sparkle tab inherits the same tint as sibling tabs`() {
        val editor = source("com/example/wlauncher/WatchEditorActivity.kt")

        assertTrue(editor.contains("WatchEditorPanel.AGENT -> FlueMiuixIcons.Sparkle"))
        assertTrue(editor.contains("tint = LocalContentColor.current"))
        assertFalse(editor.contains("candidate == WatchEditorPanel.AGENT) Color(0xFF2F73D9)"))
    }

    @Test
    fun `workspace detail exposes verified online and local rootfs installation`() {
        val settings = source("com/flue/launcher/ui/settings/agent/AgentSettingsPage.kt")
        val workspacePages = source("com/flue/launcher/ui/settings/agent/AgentWorkspaceSettingsPages.kt")

        assertTrue(workspacePages.contains("OpenDocument"))
        assertTrue(workspacePages.contains("agent_rootfs_install_online"))
        assertTrue(workspacePages.contains("agent_rootfs_choose_local"))
        assertTrue(workspacePages.contains("RootfsInstaller("))
        assertTrue(workspacePages.contains("HttpRootfsArchiveSource("))
        assertTrue(workspacePages.contains("onlineUrlDraft = onlinePackage.url"))
        assertTrue(workspacePages.contains("agent_rootfs_download_url"))
        assertTrue(workspacePages.contains("showOnlineUrlDialog"))
        assertTrue(settings.contains("onWorkspaceChanged"))
    }

    @Test
    fun `untitled conversations are localized by the ui instead of persisted in Chinese`() {
        val models = source("com/flue/launcher/watchface/agent/model/AgentModels.kt")
        val panel = source("com/flue/launcher/ui/editor/agent/WatchAgentPanel.kt")

        assertFalse(models.contains("title = \"新会话\""))
        assertTrue(panel.contains("localizedConversationTitle"))
    }

    private fun source(relative: String): String {
        val root = sequenceOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
            File("../app/src/main/kotlin"),
        ).firstOrNull(File::isDirectory) ?: error("找不到 Android Kotlin 源码目录")
        return File(root, relative).readText()
    }
}
