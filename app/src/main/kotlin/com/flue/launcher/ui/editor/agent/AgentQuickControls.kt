package com.flue.launcher.ui.editor.agent

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.key
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.ui.style.*
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.watchface.agent.runtime.WatchAgentViewModel

@Composable
internal fun AgentQuickControls(viewModel: WatchAgentViewModel) {
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<String?>(null) }
    var workspaceName by remember { mutableStateOf("") }
    var modelSearch by remember { mutableStateOf("") }
    LaunchedEffect(state.watchScope) { viewModel.refreshChoices() }
    val selectedWorkspace = choices.workspaces.firstOrNull { it.id == choices.workspaceId }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentChoiceChip(choices.profile?.modelId ?: stringResource(R.string.agent_models)) { picker = "model"; viewModel.refreshChoices(choices.models.isEmpty()) }
        AgentChoiceChip(stringResource(R.string.agent_reasoning_depth) + " · " + reasoningLabel(choices.profile?.reasoningEffort)) { picker = "reasoning" }
        AgentChoiceChip(selectedWorkspace?.displayName ?: stringResource(R.string.agent_choose_workspace)) { picker = "workspace"; viewModel.refreshChoices() }
    }
    val pickerType = picker
    if (pickerType != null) FlueAlertDialog(
        onDismissRequest = { picker = null },
        scrollableContent = false,
        title = stringResource(when (pickerType) { "model" -> R.string.agent_models; "reasoning" -> R.string.agent_reasoning_depth; else -> R.string.agent_choose_workspace }),
        confirmText = stringResource(R.string.agent_close), onConfirm = { picker = null },
        content = {
            key(pickerType) {
                AnimatedVisibility(visible = pickerType != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (choices.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                choices.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                when (pickerType) {
                    "model" -> {
                        FlueTextField(modelSearch, { modelSearch = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.agent_search_models))
                        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(choices.models.distinctBy { it.id }.filter { it.id.contains(modelSearch, true) || it.displayName.contains(modelSearch, true) }, key = { it.id }) { model ->
                                AgentChoiceRow(model.displayName, model.id == choices.profile?.modelId) { viewModel.selectModel(model.id); picker = null }
                            }
                        }
                        FlueOutlinedButton(onClick = { viewModel.refreshChoices(true) }, enabled = !choices.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_refresh_models)) }
                        if (state.isGenerating) Text(stringResource(R.string.agent_next_turn_setting), style = MaterialTheme.typography.bodySmall)
                    }
                    "reasoning" -> {
                        listOf(null, "none", "low", "medium", "high").forEach { effort ->
                            AgentChoiceRow(reasoningLabel(effort), effort == choices.profile?.reasoningEffort) { viewModel.selectReasoning(effort); picker = null }
                        }
                        Text(stringResource(R.string.agent_reasoning_compatibility), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.style.secondaryTextColor)
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(choices.workspaces, key = { it.id }) { workspace ->
                                AgentChoiceRow(workspace.displayName, workspace.id == choices.workspaceId, !state.isGenerating) { viewModel.selectWorkspace(workspace.id); picker = null }
                            }
                        }
                        FlueTextField(workspaceName, { workspaceName = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.agent_workspace_name))
                        FlueButton(onClick = { viewModel.createWorkspace(workspaceName); workspaceName = "" }, enabled = workspaceName.isNotBlank() && !state.isGenerating, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_create_workspace)) }
                        if (state.isGenerating) Text(stringResource(R.string.agent_workspace_after_stop), style = MaterialTheme.typography.bodySmall)
                    }
                }
                    }
                }
            }
        },
    )
}

@Composable private fun AgentChoiceChip(label: String, onClick: () -> Unit) {
    FlueSurface(shape = RoundedCornerShape(10.dp), color = LauncherTheme.style.topBarChipColor, border = null) {
        Text(label, Modifier.widthIn(max = 210.dp).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium, color = LauncherTheme.style.secondaryTextColor)
    }
}

@Composable private fun AgentChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FlueSurface(Modifier.fillMaxWidth(), shape = LauncherTheme.style.smallControlShape, color = if (selected) LauncherTheme.style.selectedCardColor else LauncherTheme.style.topBarChipColor, border = null) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = LauncherTheme.style.titleColor)
            if (selected) Text("✓", color = LauncherTheme.style.accentColor)
        }
    }
}

@Composable private fun reasoningLabel(effort: String?): String = stringResource(when (effort) {
    "none" -> R.string.agent_reasoning_none
    "low" -> R.string.agent_reasoning_low
    "medium" -> R.string.agent_reasoning_medium
    "high" -> R.string.agent_reasoning_high
    else -> R.string.agent_reasoning_default
})
