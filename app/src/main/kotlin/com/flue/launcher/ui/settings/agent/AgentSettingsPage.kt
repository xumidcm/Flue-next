package com.flue.launcher.ui.settings.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.flue.launcher.watchface.agent.model.AiModelDescriptor
import com.flue.launcher.watchface.agent.model.AiProtocol
import com.flue.launcher.watchface.agent.model.AiProviderProfile
import com.flue.launcher.watchface.agent.provider.AgentModelContextResolver
import com.flue.launcher.watchface.agent.provider.AgentProviderClient
import com.flue.launcher.watchface.agent.storage.AgentFeatureSettings
import com.flue.launcher.watchface.agent.storage.AgentFeatureSettingsStore
import com.flue.launcher.watchface.agent.storage.ProviderSettingsStore
import com.flue.launcher.watchface.agent.workspace.AgentWorkspace
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import com.flue.launcher.watchface.agent.workspace.WorkspacePermissionSettings
import com.flue.launcher.watchface.agent.workspace.WorkspacePermissionStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_PROFILE_ID = "default"

internal enum class AgentSettingsDestination {
    OVERVIEW,
    PROVIDERS,
    PROVIDER_DETAIL,
    PROVIDER_CONNECTION,
    PROVIDER_MODEL,
    PROVIDER_ADVANCED,
    FEATURES,
    IMAGE_GENERATION,
    WEB_SEARCH,
    WORKSPACE_FEATURES,
    WORKSPACES,
    WORKSPACE_DETAIL,
    TERMINAL,
    BASH_PERMISSIONS,
    DATA,
}

@Composable
fun AgentSettingsPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { ProviderSettingsStore(context) }
    val featureStore = remember(context) { AgentFeatureSettingsStore(context) }
    val workspaceManager = remember(context) {
        WorkspaceManager(File(context.filesDir, "agent-runtime").toPath())
    }
    val permissionStore = remember(context) { WorkspacePermissionStore(context) }
    val featureInitial = remember { featureStore.load() }
    val defaultProviderName = stringResource(R.string.agent_default_provider)
    val newProviderName = stringResource(R.string.agent_new_provider_name)
    val initial = remember {
        store.list().ifEmpty {
            listOf(
                AiProviderProfile(
                    DEFAULT_PROFILE_ID,
                    defaultProviderName,
                    AiProtocol.CHAT_COMPLETIONS,
                    "https://api.openai.com",
                ),
            )
        }
    }
    var profiles by remember { mutableStateOf(initial) }
    var selectedId by rememberSaveable { mutableStateOf(store.active()?.id ?: initial.first().id) }
    val selectedInitial = initial.firstOrNull { it.id == selectedId } ?: initial.first()
    var protocol by rememberSaveable { mutableStateOf(selectedInitial.protocol) }
    var name by rememberSaveable { mutableStateOf(selectedInitial.name) }
    var baseUrl by rememberSaveable { mutableStateOf(selectedInitial.baseUrl) }
    var modelListUrl by rememberSaveable { mutableStateOf(selectedInitial.modelListUrlOverride.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(selectedInitial.modelId.orEmpty()) }
    var contextWindow by rememberSaveable { mutableStateOf(selectedInitial.contextWindowOverride?.toString().orEmpty()) }
    var maxOutputTokens by rememberSaveable { mutableStateOf(selectedInitial.maxOutputTokens?.toString().orEmpty()) }
    var customHeaders by remember { mutableStateOf(formatHeaders(selectedInitial.customHeaderNames)) }
    var userAgent by rememberSaveable { mutableStateOf(selectedInitial.userAgent.orEmpty()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(store.cachedModels(selectedId)) }
    var confirmDelete by remember { mutableStateOf(false) }
    var providerOperationVersion by remember { mutableStateOf(0L) }

    var imageBaseUrl by rememberSaveable { mutableStateOf(featureInitial.imageBaseUrl) }
    var imageModel by rememberSaveable { mutableStateOf(featureInitial.imageModelId) }
    var imageKey by remember { mutableStateOf("") }
    var savedImageKey by remember { mutableStateOf<String?>(null) }
    var showImageKey by remember { mutableStateOf(false) }
    var tavilyBaseUrl by rememberSaveable { mutableStateOf(featureInitial.tavilyBaseUrl) }
    var searchMaxResults by rememberSaveable { mutableStateOf(featureInitial.searchMaxResults.toString()) }
    var searchDepth by rememberSaveable { mutableStateOf(featureInitial.searchDepth) }
    var webFetchEnabled by rememberSaveable { mutableStateOf(featureInitial.webFetchEnabled) }
    var tavilyKey by remember { mutableStateOf("") }
    var savedTavilyKey by remember { mutableStateOf<String?>(null) }
    var showTavilyKey by remember { mutableStateOf(false) }
    var workspaceSearchEnabled by rememberSaveable { mutableStateOf(featureInitial.workspaceSearchEnabled) }
    var copyAttachments by rememberSaveable { mutableStateOf(featureInitial.copyAttachmentsIntoWorkspace) }
    var agentEnabled by rememberSaveable { mutableStateOf(featureInitial.enabled) }

    var workspaces by remember { mutableStateOf<List<AgentWorkspace>>(emptyList()) }
    var workspaceName by rememberSaveable { mutableStateOf("") }
    var destinationStack by rememberSaveable { mutableStateOf(listOf(AgentSettingsDestination.OVERVIEW)) }
    val destination = destinationStack.lastOrNull() ?: AgentSettingsDestination.OVERVIEW
    var selectedWorkspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var workspacePermissions by remember { mutableStateOf(permissionStore.load()) }

    val scrollPositions = remember { mutableMapOf<String, Pair<Int, Int>>() }
    val pageKey = "${destination.name}_${selectedId}_${selectedWorkspaceId.orEmpty()}"
    val listState = remember(pageKey) {
        val (index, offset) = scrollPositions[pageKey] ?: (0 to 0)
        androidx.compose.foundation.lazy.LazyListState(index, offset)
    }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { scrollPositions[pageKey] = it }
    }

    fun navigateTo(dest: AgentSettingsDestination) {
        destinationStack = destinationStack + dest
    }

    fun navigateBack() {
        if (confirmDelete) {
            confirmDelete = false
            return
        }
        if (destinationStack.size > 1) {
            destinationStack = destinationStack.dropLast(1)
            return
        }
        onBack()
    }

    fun persistPermissions(next: WorkspacePermissionSettings) {
        workspacePermissions = next
        permissionStore.save(next)
    }

    fun load(id: String) {
        val profile = profiles.firstOrNull { it.id == id } ?: return
        providerOperationVersion += 1L
        busy = false
        selectedId = id
        protocol = profile.protocol
        name = profile.name
        baseUrl = profile.baseUrl
        modelListUrl = profile.modelListUrlOverride.orEmpty()
        model = profile.modelId.orEmpty()
        contextWindow = profile.contextWindowOverride?.toString().orEmpty()
        maxOutputTokens = profile.maxOutputTokens?.toString().orEmpty()
        customHeaders = formatHeaders(profile.customHeaderNames)
        userAgent = profile.userAgent.orEmpty()
        apiKeyInput = ""
        savedKey = null
        showKey = false
        models = store.cachedModels(id)
        scope.launch {
            val key = withContext(Dispatchers.IO) { store.apiKey(id) }
            if (selectedId == id) savedKey = key
        }
    }

    fun draftProfile(): AiProviderProfile = AiProviderProfile(
        id = selectedId,
        name = name.trim().ifBlank { selectedId },
        protocol = protocol,
        baseUrl = baseUrl.trim(),
        modelListUrlOverride = modelListUrl.trim().takeIf(String::isNotBlank),
        modelId = model.trim().takeIf(String::isNotBlank),
        contextWindowOverride = contextWindow.toLongOrNull()?.takeIf { it > 0L },
        maxOutputTokens = maxOutputTokens.toIntOrNull()?.coerceAtLeast(1),
        customHeaderNames = parseHeaders(customHeaders),
        userAgent = userAgent.trim().takeIf(String::isNotBlank),
        reasoningEffort = store.get(selectedId)?.reasoningEffort,
    )

    fun saveProvider() {
        if (busy) return
        busy = true
        val profile = draftProfile()
        val operationProviderId = selectedId
        providerOperationVersion += 1L
        val operationVersion = providerOperationVersion
        val pendingKey = apiKeyInput.trim().takeIf(String::isNotBlank)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(store.save(profile, pendingKey)) {
                        context.getString(R.string.agent_save_failed, "")
                    }
                }
            }
            if (providerOperationVersion != operationVersion || selectedId != operationProviderId) return@launch
            val persisted = if (result.isSuccess) {
                withContext(Dispatchers.IO) {
                    store.list() to store.apiKey(operationProviderId)
                }
            } else {
                null
            }
            if (providerOperationVersion != operationVersion || selectedId != operationProviderId) return@launch
            busy = false
            result.onSuccess {
                profiles = persisted?.first.orEmpty()
                savedKey = persisted?.second
                apiKeyInput = ""
                val msg = context.getString(R.string.agent_saved)
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                val msg = context.getString(
                    R.string.agent_save_failed,
                    it.message ?: context.getString(R.string.agent_unknown_error),
                )
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun testConnection() {
        if (busy || baseUrl.isBlank()) return
        busy = true
        val profile = draftProfile()
        val operationProviderId = selectedId
        providerOperationVersion += 1L
        val operationVersion = providerOperationVersion
        val key = apiKeyInput.trim().takeIf(String::isNotBlank) ?: savedKey
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AgentProviderClient().listModels(profile, key) }
            }
            if (providerOperationVersion != operationVersion || selectedId != operationProviderId) return@launch
            busy = false
            result.onSuccess {
                models = it
                store.cacheModels(selectedId, it)
                val msg = context.getString(R.string.agent_connect_success, it.size)
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                val msg = context.getString(
                    R.string.agent_connect_failed,
                    it.message ?: context.getString(R.string.agent_unknown_error),
                )
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveFeatures() {
        if (busy) return
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                featureStore.save(
                    AgentFeatureSettings(
                        enabled = agentEnabled,
                        imageBaseUrl = imageBaseUrl.trim(),
                        imageModelId = imageModel.trim(),
                        tavilyBaseUrl = tavilyBaseUrl.trim(),
                        searchMaxResults = searchMaxResults.toIntOrNull()?.coerceIn(1, 50) ?: 5,
                        searchDepth = searchDepth.trim(),
                        webFetchEnabled = webFetchEnabled,
                        workspaceSearchEnabled = workspaceSearchEnabled,
                        copyAttachmentsIntoWorkspace = copyAttachments,
                    ),
                    imageKey.trim().takeIf(String::isNotBlank),
                    tavilyKey.trim().takeIf(String::isNotBlank),
                )
            }
            if (saved) {
                savedImageKey = withContext(Dispatchers.IO) { featureStore.imageApiKey() }
                savedTavilyKey = withContext(Dispatchers.IO) { featureStore.tavilyApiKey() }
                imageKey = ""
                tavilyKey = ""
                val msg = context.getString(R.string.agent_feature_saved)
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val msg = context.getString(R.string.agent_feature_save_failed)
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        val initialProviderId = selectedId
        val initialKey = withContext(Dispatchers.IO) { store.apiKey(initialProviderId) }
        if (selectedId == initialProviderId) savedKey = initialKey
        val featureKeys = withContext(Dispatchers.IO) {
            featureStore.imageApiKey() to featureStore.tavilyApiKey()
        }
        savedImageKey = featureKeys.first
        savedTavilyKey = featureKeys.second
        workspaces = withContext(Dispatchers.IO) { workspaceManager.list() }
    }

    BackHandler(onBack = ::navigateBack)
    val pageTitle = when (destination) {
        AgentSettingsDestination.PROVIDER_DETAIL -> name.ifBlank { stringResource(R.string.agent_overview_provider) }
        else -> stringResource(destination.titleResource())
    }
    val pageSubtitle = when (destination) {
        AgentSettingsDestination.OVERVIEW -> stringResource(R.string.agent_settings_intro)
        AgentSettingsDestination.PROVIDERS -> stringResource(R.string.agent_provider_list_summary)
        AgentSettingsDestination.PROVIDER_DETAIL -> stringResource(R.string.agent_provider_detail_summary)
        AgentSettingsDestination.PROVIDER_CONNECTION -> stringResource(R.string.agent_provider_connection_summary)
        AgentSettingsDestination.PROVIDER_MODEL -> stringResource(R.string.agent_provider_model_summary)
        AgentSettingsDestination.PROVIDER_ADVANCED -> stringResource(R.string.agent_provider_advanced_summary)
        else -> null
    }

    AgentSettingsScreen(
        stateKey = pageKey,
        title = pageTitle,
        subtitle = pageSubtitle,
        onBack = ::navigateBack,
        listState = listState,
    ) {
        when (destination) {
            AgentSettingsDestination.OVERVIEW -> {
                item("agent_overview_ai") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_enabled),
                            subtitle = stringResource(R.string.agent_enabled_summary),
                            onClick = {
                                agentEnabled = !agentEnabled
                                featureStore.saveEnabled(agentEnabled)
                            },
                            leading = { SettingsIcon(MiuixIcons.Sparkle, MiuixIcons.Sparkle) },
                            trailing = { FlueSwitch(agentEnabled, { value -> agentEnabled = value; featureStore.saveEnabled(value) }) },
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_overview_provider),
                            subtitle = stringResource(R.string.agent_overview_provider_summary),
                            onClick = { navigateTo(AgentSettingsDestination.PROVIDERS) },
                            leading = { SettingsIcon(MiuixIcons.Settings, MiuixIcons.Settings) },
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_overview_features),
                            subtitle = stringResource(R.string.agent_overview_features_summary),
                            onClick = { navigateTo(AgentSettingsDestination.FEATURES) },
                            leading = { SettingsIcon(MiuixIcons.Sparkle, MiuixIcons.Sparkle) },
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_bash_permissions),
                            subtitle = stringResource(R.string.agent_bash_manual_summary),
                            onClick = { navigateTo(AgentSettingsDestination.BASH_PERMISSIONS) },
                            leading = { SettingsIcon(MiuixIcons.Tool, MiuixIcons.Tool) },
                            last = true,
                        )
                    }
                }
                item("agent_overview_workspace") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_workspaces),
                            subtitle = stringResource(R.string.agent_workspace_management_summary),
                            onClick = { navigateTo(AgentSettingsDestination.WORKSPACES) },
                            leading = { SettingsIcon(MiuixIcons.Grid, MiuixIcons.Grid) },
                            last = true,
                        )
                    }
                }
                item("agent_overview_data") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_data),
                            subtitle = stringResource(R.string.agent_data_summary),
                            onClick = { navigateTo(AgentSettingsDestination.DATA) },
                            leading = { SettingsIcon(MiuixIcons.Info, MiuixIcons.Info) },
                            last = true,
                        )
                    }
                }
            }

            AgentSettingsDestination.PROVIDERS -> {
                item("provider_list") {
                    AgentSettingsGroup(stringResource(R.string.agent_active_provider)) {
                        profiles.forEachIndexed { index, profile ->
                            val active = store.active()?.id == profile.id
                            AgentSettingsEntry(
                                title = profile.name,
                                subtitle = if (active) stringResource(R.string.agent_provider_active) else profile.protocol.label() + " · " + profile.baseUrl,
                                onClick = {
                                    load(profile.id)
                                    navigateTo(AgentSettingsDestination.PROVIDER_DETAIL)
                                },
                                leading = { SettingsIcon(MiuixIcons.Sparkle, MiuixIcons.Sparkle) },
                                trailing = {
                                    Icon(if (active) MiuixIcons.Check else MiuixIcons.Forward, contentDescription = null)
                                },
                                last = index == profiles.lastIndex,
                            )
                        }
                    }
                }
                item("provider_new") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_new_provider),
                            subtitle = stringResource(R.string.agent_new_provider_summary),
                            onClick = {
                                val id = "provider-${System.currentTimeMillis()}"
                                val profile = AiProviderProfile(id, newProviderName, AiProtocol.CHAT_COMPLETIONS, "https://api.openai.com")
                                store.save(profile)
                                profiles = store.list()
                                load(id)
                                navigateTo(AgentSettingsDestination.PROVIDER_CONNECTION)
                            },
                            leading = { SettingsIcon(MiuixIcons.Add, MiuixIcons.Add) },
                            last = true,
                        )
                    }
                }
            }

            AgentSettingsDestination.PROVIDER_DETAIL -> {
                item("provider_sections") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_provider_connection),
                            subtitle = buildString {
                                append(baseUrl.ifBlank { stringResource(R.string.agent_not_configured) })
                                append(" · ")
                                append(stringResource(if (savedKey != null) R.string.agent_api_key_configured else R.string.agent_api_key_missing))
                            },
                            onClick = { navigateTo(AgentSettingsDestination.PROVIDER_CONNECTION) },
                            leading = { SettingsIcon(MiuixIcons.Wifi, MiuixIcons.Wifi) },
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_provider_model),
                            subtitle = listOf(protocol.label(), model.ifBlank { stringResource(R.string.agent_model_automatic) }).joinToString(" · "),
                            onClick = { navigateTo(AgentSettingsDestination.PROVIDER_MODEL) },
                            leading = { SettingsIcon(MiuixIcons.Sparkle, MiuixIcons.Sparkle) },
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_provider_advanced),
                            subtitle = stringResource(R.string.agent_provider_advanced_summary),
                            onClick = { navigateTo(AgentSettingsDestination.PROVIDER_ADVANCED) },
                            leading = { SettingsIcon(MiuixIcons.Tool, MiuixIcons.Tool) },
                            last = true,
                        )
                    }
                }
                item("provider_actions") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = if (busy) stringResource(R.string.agent_provider_testing) else stringResource(R.string.agent_test_connection),
                            subtitle = stringResource(R.string.agent_test_connection_summary),
                            enabled = !busy && baseUrl.isNotBlank(),
                            onClick = ::testConnection,
                            leading = { SettingsIcon(MiuixIcons.Refresh, MiuixIcons.Refresh) },
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_provider_make_active),
                            subtitle = stringResource(R.string.agent_provider_make_active_summary),
                            onClick = {
                                store.setActiveProvider(selectedId)
                                val msg = context.getString(R.string.agent_provider_active)
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            },
                            leading = { SettingsIcon(MiuixIcons.Check, MiuixIcons.Check) },
                            last = true,
                        )
                    }
                }
                if (models.isNotEmpty()) {
                    item("provider_models") {
                        AgentSettingsFormCard {
                            Text(stringResource(R.string.agent_models), style = MaterialTheme.typography.titleMedium)
                            Text(models.joinToString("\n") { it.displayName }, color = LauncherTheme.style.secondaryTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item("provider_delete") {
                    FlueOutlinedButton(
                        enabled = profiles.size > 1,
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentColor = MaterialTheme.colorScheme.error,
                    ) { Text(stringResource(R.string.agent_delete_current)) }
                }
            }

            AgentSettingsDestination.PROVIDER_CONNECTION -> {
                item("provider_connection_form") {
                    AgentSettingsFormCard {
                        FlueTextField(name, { name = it }, Modifier.fillMaxWidth(), stringResource(R.string.agent_provider_name))
                        FlueTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), stringResource(R.string.agent_base_url))
                        FlueTextField(
                            value = if (showKey && apiKeyInput.isBlank()) savedKey.orEmpty() else apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(if (savedKey == null) R.string.agent_api_key else R.string.agent_api_key_saved),
                            placeholder = if (savedKey != null) "••••••••" else null,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) MiuixIcons.Close else MiuixIcons.Visibility, stringResource(R.string.agent_show_hide_key))
                                }
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlueButton(enabled = !busy && name.isNotBlank() && baseUrl.isNotBlank(), onClick = ::saveProvider, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.agent_save))
                            }
                            FlueOutlinedButton(
                                enabled = !busy && savedKey != null,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        withContext(Dispatchers.IO) { store.removeSecretOnly(selectedId) }
                                        savedKey = null
                                        apiKeyInput = ""
                                        showKey = false
                                        val msg = context.getString(R.string.agent_key_cleared)
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                        busy = false
                                    }
                                },
                            ) { Text(stringResource(R.string.agent_clear_key)) }
                        }
                    }
                }
            }

            AgentSettingsDestination.PROVIDER_MODEL -> {
                item("provider_protocol") {
                    AgentSettingsGroup(stringResource(R.string.agent_protocol)) {
                        AiProtocol.entries.forEachIndexed { index, candidate ->
                            AgentSettingsEntry(
                                title = candidate.label(),
                                subtitle = if (protocol == candidate) stringResource(R.string.agent_selected) else null,
                                onClick = { protocol = candidate },
                                trailing = { if (protocol == candidate) Icon(MiuixIcons.Check, contentDescription = null) },
                                last = index == AiProtocol.entries.lastIndex,
                            )
                        }
                    }
                }
                item("provider_model_form") {
                    AgentSettingsFormCard {
                        FlueTextField(model, { model = it }, Modifier.fillMaxWidth(), stringResource(R.string.agent_model_id))
                        FlueTextField(
                            value = contextWindow,
                            onValueChange = { contextWindow = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.agent_context_window),
                            supportingText = stringResource(R.string.agent_context_default, AgentModelContextResolver.DEFAULT_CONTEXT_WINDOW_TOKENS / 1000),
                        )
                        FlueTextField(maxOutputTokens, { maxOutputTokens = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), stringResource(R.string.agent_max_output_tokens))
                        FlueButton(enabled = !busy, onClick = ::saveProvider, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_save)) }
                    }
                }
            }

            AgentSettingsDestination.PROVIDER_ADVANCED -> {
                item("provider_advanced_form") {
                    AgentSettingsFormCard {
                        FlueTextField(modelListUrl, { modelListUrl = it }, Modifier.fillMaxWidth(), stringResource(R.string.agent_model_list_url))
                        FlueTextField(
                            value = customHeaders,
                            onValueChange = { customHeaders = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.agent_custom_headers),
                            supportingText = stringResource(R.string.agent_custom_headers_summary),
                            singleLine = false,
                            minLines = 3,
                            maxLines = 6,
                        )
                        FlueTextField(userAgent, { userAgent = it }, Modifier.fillMaxWidth(), stringResource(R.string.agent_user_agent))
                        FlueButton(enabled = !busy, onClick = ::saveProvider, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_save)) }
                    }
                }
            }

            AgentSettingsDestination.FEATURES -> {
                item("feature_entries") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_image_generation),
                            subtitle = stringResource(R.string.agent_image_generation_summary),
                            onClick = { navigateTo(AgentSettingsDestination.IMAGE_GENERATION) },
                            leading = { SettingsIcon(MiuixIcons.Sparkle, MiuixIcons.Sparkle) },
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_web_search),
                            subtitle = stringResource(R.string.agent_web_search_summary),
                            onClick = { navigateTo(AgentSettingsDestination.WEB_SEARCH) },
                            leading = { SettingsIcon(MiuixIcons.Search, MiuixIcons.Search) },
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_workspace_search),
                            subtitle = stringResource(R.string.agent_workspace_search_summary),
                            onClick = { navigateTo(AgentSettingsDestination.WORKSPACE_FEATURES) },
                            leading = { SettingsIcon(MiuixIcons.Grid, MiuixIcons.Grid) },
                            last = true,
                        )
                    }
                }
            }

            AgentSettingsDestination.IMAGE_GENERATION -> {
                item("image_form") {
                    AgentSettingsFormCard {
                        FlueTextField(imageBaseUrl, { imageBaseUrl = it }, Modifier.fillMaxWidth(), stringResource(R.string.agent_image_base_url))
                        FlueTextField(imageModel, { imageModel = it }, Modifier.fillMaxWidth(), stringResource(R.string.agent_image_model))
                        Text(
                            "尺寸、背景和质量由 Agent 根据表盘画布与素材用途自动选择。留空时使用图片 API 默认值。",
                            style = MaterialTheme.typography.bodySmall,
                            color = LauncherTheme.style.secondaryTextColor,
                        )
                        FlueTextField(
                            value = if (showImageKey && imageKey.isBlank()) savedImageKey.orEmpty() else imageKey,
                            onValueChange = { imageKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(if (savedImageKey == null) R.string.agent_image_key else R.string.agent_image_key_saved),
                            placeholder = if (savedImageKey != null) "••••••••" else null,
                            visualTransformation = if (showImageKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showImageKey = !showImageKey }) {
                                    Icon(if (showImageKey) MiuixIcons.Close else MiuixIcons.Visibility, stringResource(R.string.agent_show_hide_key))
                                }
                            },
                        )
                        FlueButton(enabled = !busy, onClick = ::saveFeatures, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_save)) }
                        if (savedImageKey != null) TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { featureStore.clearImageApiKey() }
                                    savedImageKey = null
                                    imageKey = ""
                                    showImageKey = false
                                }
                            },
                        ) { Text(stringResource(R.string.agent_clear_image_key)) }
                    }
                }
            }

            AgentSettingsDestination.WEB_SEARCH -> {
                item("web_form") {
                    AgentSettingsFormCard {
                        FlueTextField(tavilyBaseUrl, { tavilyBaseUrl = it }, Modifier.fillMaxWidth(), stringResource(R.string.agent_tavily_url))
                        FlueTextField(searchMaxResults, { searchMaxResults = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), "搜索条数")
                        FlueTextField(searchDepth, { searchDepth = it }, Modifier.fillMaxWidth(), "搜索强度（basic / advanced）")
                        AgentSettingsEntry(
                            title = "网页抓取",
                            subtitle = "允许搜索后抓取网页正文",
                            onClick = { webFetchEnabled = !webFetchEnabled },
                            trailing = { FlueSwitch(webFetchEnabled, { value -> webFetchEnabled = value }) },
                        )
                        FlueTextField(
                            value = if (showTavilyKey && tavilyKey.isBlank()) savedTavilyKey.orEmpty() else tavilyKey,
                            onValueChange = { tavilyKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(if (savedTavilyKey == null) R.string.agent_tavily_key else R.string.agent_tavily_key_saved),
                            placeholder = if (savedTavilyKey != null) "••••••••" else null,
                            visualTransformation = if (showTavilyKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showTavilyKey = !showTavilyKey }) {
                                    Icon(if (showTavilyKey) MiuixIcons.Close else MiuixIcons.Visibility, stringResource(R.string.agent_show_hide_key))
                                }
                            },
                        )
                        FlueButton(enabled = !busy, onClick = ::saveFeatures, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_save)) }
                        if (savedTavilyKey != null) TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { featureStore.clearTavilyApiKey() }
                                    savedTavilyKey = null
                                    tavilyKey = ""
                                    showTavilyKey = false
                                }
                            },
                        ) { Text(stringResource(R.string.agent_clear_tavily_key)) }
                    }
                }
            }

            AgentSettingsDestination.WORKSPACE_FEATURES -> {
                item("workspace_features") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_workspace_search_enabled),
                            subtitle = stringResource(R.string.agent_workspace_search_summary),
                            onClick = { workspaceSearchEnabled = !workspaceSearchEnabled },
                            trailing = { FlueSwitch(workspaceSearchEnabled, { workspaceSearchEnabled = it }) },
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.agent_copy_attachments),
                            onClick = { copyAttachments = !copyAttachments },
                            trailing = { FlueSwitch(copyAttachments, { copyAttachments = it }) },
                            last = true,
                        )
                    }
                }
                item("workspace_features_save") {
                    FlueButton(enabled = !busy, onClick = ::saveFeatures, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_save)) }
                }
            }

            AgentSettingsDestination.WORKSPACES -> {
                if (workspaces.isNotEmpty()) {
                    item("workspace_list") {
                        AgentSettingsGroup(stringResource(R.string.agent_workspaces)) {
                            workspaces.forEachIndexed { index, workspace ->
                                AgentSettingsEntry(
                                    title = workspace.displayName,
                                    subtitle = buildString {
                                        append(stringResource(R.string.agent_workspace_usage, formatBytes(workspace.quota.usedBytes), formatBytes(workspace.quota.limitBytes)))
                                        if (workspace.binding != null) append(" · ${stringResource(R.string.agent_workspace_bound)}")
                                    },
                                    onClick = {
                                        selectedWorkspaceId = workspace.id
                                        navigateTo(AgentSettingsDestination.WORKSPACE_DETAIL)
                                    },
                                    leading = { SettingsIcon(MiuixIcons.Grid, MiuixIcons.Grid) },
                                    trailing = { Icon(MiuixIcons.Forward, contentDescription = null) },
                                    last = index == workspaces.lastIndex,
                                )
                            }
                        }
                    }
                }
                item("workspace_create") {
                    AgentSettingsFormCard {
                        FlueTextField(workspaceName, { workspaceName = it }, Modifier.fillMaxWidth(), stringResource(R.string.agent_workspace_name))
                        FlueButton(
                            enabled = workspaceName.isNotBlank() && !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { workspaceManager.create(workspaceName.trim()) } }
                                    result.onSuccess {
                                        workspaces = withContext(Dispatchers.IO) { workspaceManager.list() }
                                        workspaceName = ""
                                        val msg = context.getString(R.string.agent_workspace_created)
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        val msg = context.getString(R.string.agent_workspace_create_failed, it.message ?: context.getString(R.string.agent_unknown_error))
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.agent_create_workspace)) }
                    }
                }
            }

            AgentSettingsDestination.WORKSPACE_DETAIL -> item("workspace_detail") {
                AgentWorkspaceDetailPage(
                    workspace = workspaces.firstOrNull { it.id == selectedWorkspaceId },
                    workspaceManager = workspaceManager,
                    onWorkspaceChanged = { updated -> workspaces = workspaces.map { if (it.id == updated.id) updated else it } },
                    onOpenTerminal = { navigateTo(AgentSettingsDestination.TERMINAL) },
                    onRename = { workspaceId, nextName ->
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { workspaceManager.rename(workspaceId, nextName); workspaceManager.list() }
                                }
                                busy = false
                                result.onSuccess { updated ->
                                    workspaces = updated
                                    val msg = context.getString(R.string.agent_saved)
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }.onFailure { error ->
                                    val msg = context.getString(
                                        R.string.agent_save_failed,
                                        error.message ?: context.getString(R.string.agent_unknown_error),
                                    )
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onDelete = { workspaceId ->
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        check(workspaceManager.delete(workspaceId)) { "Workspace could not be deleted" }
                                        workspaceManager.list()
                                    }
                                }
                                busy = false
                                result.onSuccess { updated ->
                                    workspaces = updated
                                    selectedWorkspaceId = null
                                    destinationStack = listOf(AgentSettingsDestination.OVERVIEW, AgentSettingsDestination.WORKSPACES)
                                    val msg = context.getString(R.string.agent_deleted)
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }.onFailure { error ->
                                    val msg = context.getString(
                                        R.string.agent_save_failed,
                                        error.message ?: context.getString(R.string.agent_unknown_error),
                                    )
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                )
            }

            AgentSettingsDestination.TERMINAL -> item("workspace_terminal") {
                AgentWorkspaceTerminalPage(
                    workspace = workspaces.firstOrNull { it.id == selectedWorkspaceId },
                    permissionStore = permissionStore,
                )
            }

            AgentSettingsDestination.BASH_PERMISSIONS -> item("bash_permissions") {
                AgentBashPermissionPage(workspacePermissions, ::persistPermissions)
            }

            AgentSettingsDestination.DATA -> {
                item("agent_data_contract") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(stringResource(R.string.agent_rootfs), subtitle = stringResource(R.string.agent_rootfs_summary))
                        AgentSettingsEntry(stringResource(R.string.agent_data), subtitle = stringResource(R.string.agent_data_summary), last = true)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        FlueAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(R.string.agent_delete_provider_title),
            text = stringResource(R.string.agent_delete_provider_summary),
            confirmText = stringResource(R.string.agent_delete),
            onConfirm = {
                store.remove(selectedId)
                profiles = store.list()
                confirmDelete = false
                val next = store.active() ?: profiles.firstOrNull()
                if (next != null) {
                    load(next.id)
                    destinationStack = listOf(AgentSettingsDestination.OVERVIEW, AgentSettingsDestination.PROVIDERS, AgentSettingsDestination.PROVIDER_DETAIL)
                } else {
                    destinationStack = listOf(AgentSettingsDestination.OVERVIEW, AgentSettingsDestination.PROVIDERS)
                }
            },
            dismissText = stringResource(R.string.agent_cancel),
            dangerous = true,
        )
    }
}

private fun AiProtocol.label(): String = when (this) {
    AiProtocol.CHAT_COMPLETIONS -> "Chat"
    AiProtocol.RESPONSES -> "Responses"
    AiProtocol.CLAUDE_MESSAGES -> "Claude"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun parseHeaders(raw: String): Map<String, String> = raw.lineSequence().mapNotNull { line ->
    val separator = line.indexOf(':')
    if (separator <= 0) null else line.substring(0, separator).trim().takeIf(String::isNotBlank)?.let { header ->
        header to line.substring(separator + 1).trim()
    }
}.filter { it.second.isNotBlank() }.toMap()

private fun formatHeaders(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { (header, value) -> "$header: $value" }
