package com.flue.launcher.ui.editor.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.ui.text.style.TextOverflow
import com.flue.launcher.ui.style.FlueTextField
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.watchface.agent.model.AgentAttachment
import com.flue.launcher.watchface.agent.model.AgentConversation
import com.flue.launcher.watchface.agent.model.AgentMessage
import com.flue.launcher.watchface.agent.model.AgentMessageLayout
import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.model.AgentMessageStatus
import com.flue.launcher.watchface.agent.model.AgentToolCallRecord
import com.flue.launcher.watchface.agent.model.agentMessageLayout
import com.flue.launcher.watchface.agent.model.AgentGenerationStatus
import com.flue.launcher.watchface.agent.model.AgentSurface
import com.flue.launcher.watchface.agent.runtime.WatchAgentViewModel
import com.flue.launcher.ui.settings.miuix.MiuixIcons
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.style.FlueSurface
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.R
import kotlinx.coroutines.launch

@Composable
fun WatchAgentPanel(viewModel: WatchAgentViewModel, onMaximize: () -> Unit, onMinimize: () -> Unit, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    AgentSurfaceContent(viewModel, messages, Modifier.fillMaxSize(), onMaximize, onMinimize, onOpenSettings, conversations.firstOrNull { it.id == state.activeConversationId }?.title.localizedConversationTitle())
    if (state.showHistory) AgentHistoryDialog(viewModel, conversations)
}

@Composable
fun WatchAgentFullscreen(viewModel: WatchAgentViewModel, onRestore: () -> Unit, onMinimize: () -> Unit, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = LauncherTheme.style.screenBackground) {
        AgentSurfaceContent(viewModel, messages, Modifier.fillMaxSize(), onRestore, onMinimize, onOpenSettings, conversations.firstOrNull { it.id == state.activeConversationId }?.title.localizedConversationTitle())
    }
    if (state.showHistory) AgentHistoryDialog(viewModel, conversations)
}

@Composable
private fun AgentSurfaceContent(viewModel: WatchAgentViewModel, messages: List<AgentMessage>, modifier: Modifier, onMaximize: () -> Unit, onMinimize: () -> Unit, onOpenSettings: () -> Unit, title: String) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val displayItems = remember(messages) { groupConversationForDisplay(messages) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { viewModel.addAttachment(context, it) }
    }
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            !listState.canScrollForward || (last?.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 48)
        }
    }
    var followOutput by remember(state.activeConversationId) { mutableStateOf(true) }
    LaunchedEffect(listState, state.activeConversationId) {
        snapshotFlow { listState.isScrollInProgress to nearBottom }.collect { (scrolling, atBottom) ->
            if (scrolling) followOutput = atBottom
        }
    }
    suspend fun scrollToEnd(animated: Boolean) {
        if (displayItems.isEmpty()) return
        // An assistant turn can be taller than the whole viewport. Its item index
        // alone identifies the beginning of the reply, not the conversation end.
        if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index != displayItems.lastIndex) {
            listState.scrollToItem(displayItems.lastIndex)
        }
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        val remaining = last?.let { (it.offset.toLong() + it.size - info.viewportEndOffset).coerceAtLeast(0L).toFloat() } ?: 0f
        if (animated) listState.animateScrollBy(remaining) else listState.scrollBy(remaining)
        followOutput = true
    }
    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.id,
        messages.lastOrNull()?.content,
        messages.lastOrNull()?.reasoningContent,
        messages.lastOrNull()?.status,
    ) {
        if (followOutput && !listState.isScrollInProgress) scrollToEnd(animated = false)
    }
    BackHandler(enabled = state.surface == AgentSurface.FULLSCREEN, onBack = onMinimize)
    Column(modifier.then(Modifier.fillMaxSize())) {
        AgentTopBar(
            title = title,
            fullscreen = state.surface == AgentSurface.FULLSCREEN,
            expandRestoreDescription = stringResource(R.string.agent_expand_restore),
            settingsDescription = stringResource(R.string.agent_open_settings),
            closeDescription = stringResource(R.string.agent_close),
            onToggleSurface = onMaximize,
            onOpenSettings = onOpenSettings,
            onOpenHistory = { viewModel.toggleHistory(true) },
            onMinimize = onMinimize,
        )
        AgentQuickControls(viewModel)
        if (state.generationStatus != null || state.inputTokens != null || state.pendingFollowUp != null) {
            Text(buildString {
                state.generationStatus?.let { append(context.getString(it.labelResource())) }
                state.inputTokens?.let { append(if (isNotEmpty()) " · " else ""); append(context.getString(R.string.agent_input_tokens, it)) }
                state.outputTokens?.let { append(" · "); append(context.getString(R.string.agent_output_tokens, it)) }
                state.pendingFollowUp?.let { append(" · "); append(context.getString(R.string.agent_pending_next)) }
            }, Modifier.fillMaxWidth().padding(horizontal = 14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (displayItems.isEmpty()) item { EmptyAgentState() }
                items(displayItems, key = AgentConversationItem::key) { item ->
                    when (item) {
                        is AgentConversationItem.UserMessage -> {
                            val message = item.message
                            AgentMessageCard(
                                message = message,
                                actionsEnabled = !state.isGenerating,
                                onCopy = { copyToClipboard(context, it) },
                                onEdit = { viewModel.editMessage(message.id) },
                                onRetry = { viewModel.retryMessage(message.id) },
                                onToggleTool = { viewModel.toggleTool(message.id) },
                                expanded = message.id in state.expandedToolIds,
                            )
                        }
                        is AgentConversationItem.AssistantTurn -> {
                            AgentAssistantTurn(
                                messages = item.messages,
                                actionsEnabled = !state.isGenerating,
                                expanded = item.expansionKey in state.expandedToolIds,
                                onToggleThoughtChain = { viewModel.toggleTool(item.expansionKey) },
                                onCopy = { copyToClipboard(context, it) },
                                onRetry = {
                                    val firstIndex = messages.indexOfFirst { message -> message.id == item.messages.first().id }
                                    messages.take(firstIndex.coerceAtLeast(0) + 1)
                                        .lastOrNull { message -> message.role == AgentMessageRole.USER }
                                        ?.let { user -> viewModel.retryMessage(user.id) }
                                },
                            )
                        }
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = !nearBottom && displayItems.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                FlueSurface(shape = RoundedCornerShape(12.dp), color = LauncherTheme.style.topBarChipColor, border = null) {
                    TextButton(onClick = { scope.launch { scrollToEnd(animated = true) } }) {
                        Icon(Icons.Default.ArrowDownward, null, Modifier.size(16.dp))
                        Text(stringResource(R.string.agent_back_to_bottom), Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        if (state.attachments.isNotEmpty()) AttachmentStrip(state.attachments, viewModel::removeAttachment)
        state.attachmentError?.let { error ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.agent_attachment_failed, error), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::clearAttachmentError) { Text(stringResource(R.string.agent_close)) }
            }
        }
        state.pendingFollowUp?.let { pending ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.agent_pending_preview, pending), Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = viewModel::cancelPendingFollowUp) { Text(stringResource(R.string.agent_cancel_pending)) }
            }
        }
        // The editor root owns IME insets once for both split and fullscreen surfaces.
        val shouldStop = state.isGenerating && state.inputDraft.isBlank()
        AgentComposer(
            value = state.inputDraft,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            placeholder = stringResource(if (state.pendingFollowUp != null) R.string.agent_edit_pending else R.string.agent_input_hint),
            attachmentDescription = stringResource(R.string.agent_add_attachment),
            actionDescription = stringResource(if (shouldStop) R.string.agent_stop else R.string.agent_send),
            isGenerating = shouldStop,
            enabled = state.isGenerating || state.inputDraft.isNotBlank(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            onValueChange = viewModel::updateDraft,
            onAddAttachment = { picker.launch(arrayOf("*/*")) },
            onSubmitOrStop = { if (shouldStop) viewModel.stop() else viewModel.send() },
        )
    }
}

@Composable
private fun AgentMessageCard(message: AgentMessage, actionsEnabled: Boolean, onCopy: (String) -> Unit, onEdit: () -> Unit, onRetry: () -> Unit, onToggleTool: () -> Unit, expanded: Boolean) {
    when (agentMessageLayout(message)) {
        AgentMessageLayout.TOOL_STEP,
        AgentMessageLayout.ASSISTANT_FLOW,
        -> AgentAssistantTurn(
            messages = listOf(message),
            actionsEnabled = actionsEnabled,
            expanded = expanded,
            onToggleThoughtChain = onToggleTool,
            onCopy = onCopy,
            onRetry = onRetry,
        )
        AgentMessageLayout.USER_BUBBLE -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            FlueSurface(
                modifier = Modifier.widthIn(max = 360.dp).animateContentSize(),
                shape = LauncherTheme.style.compactShape,
                color = LauncherTheme.style.selectedCardColor,
                border = null,
            ) {
                AgentTextMessageContent(message, actionsEnabled, onCopy, onEdit, onRetry)
            }
        }
    }
}

private sealed interface AgentThoughtStep {
    data class Reasoning(val message: AgentMessage, val content: String) : AgentThoughtStep
    data class Tool(val call: AgentToolCallRecord?, val result: AgentMessage?) : AgentThoughtStep
}

private fun List<AgentMessage>.orderedThoughtSteps(): List<AgentThoughtStep> = buildList {
    val toolResults = this@orderedThoughtSteps
        .filter { agentMessageLayout(it) == AgentMessageLayout.TOOL_STEP }
    val consumedResultIds = mutableSetOf<String>()

    this@orderedThoughtSteps.forEach { message ->
        message.reasoningContent?.takeIf(String::isNotBlank)?.let { reasoning ->
            add(AgentThoughtStep.Reasoning(message, reasoning))
        }
        message.toolCalls.forEach { call ->
            val result = toolResults.firstOrNull { candidate ->
                candidate.id !in consumedResultIds && candidate.toolCallId == call.id
            }
            result?.let { consumedResultIds += it.id }
            add(AgentThoughtStep.Tool(call, result))
        }
        if (agentMessageLayout(message) == AgentMessageLayout.TOOL_STEP && message.id !in consumedResultIds) {
            consumedResultIds += message.id
            add(AgentThoughtStep.Tool(call = null, result = message))
        }
    }
}

@Composable
private fun AgentAssistantTurn(
    messages: List<AgentMessage>,
    actionsEnabled: Boolean,
    expanded: Boolean,
    onToggleThoughtChain: () -> Unit,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                MiuixIcons.Sparkle,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = LauncherTheme.style.accentColor,
            )
            Text(
                stringResource(R.string.agent_title),
                modifier = Modifier.padding(start = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                color = LauncherTheme.style.secondaryTextColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        messages.forEach { message ->
            // Preserve the provider event order: reasoning, text, tool, then any follow-up text.
            message.reasoningContent?.takeIf(String::isNotBlank)?.let { reasoning ->
                AgentReasoningStep(AgentThoughtStep.Reasoning(message, reasoning), showDetails = expanded)
            }
            message.toolCalls.forEach { call ->
                AgentToolInvocationStep(call, null, expanded, onCopy)
            }
            if (agentMessageLayout(message) == AgentMessageLayout.TOOL_STEP) {
                AgentToolResultStep(message, expanded, onCopy)
            }
            if (agentMessageLayout(message) == AgentMessageLayout.ASSISTANT_FLOW) {
                message.content.takeIf(String::isNotBlank)?.let {
                    AgentTextMessageContent(message, actionsEnabled, onCopy, {}, onRetry)
                }
            }
        }
    }
}

@Composable
private fun AgentThoughtStepContent(
    step: AgentThoughtStep,
    showDetails: Boolean,
    onCopy: (String) -> Unit,
) {
    when (step) {
        is AgentThoughtStep.Reasoning -> AgentReasoningStep(step, showDetails)
        is AgentThoughtStep.Tool -> {
            val call = step.call
            if (call != null) {
                AgentToolInvocationStep(call, step.result, showDetails, onCopy)
            } else {
                AgentToolResultStep(requireNotNull(step.result), showDetails, onCopy)
            }
        }
    }
}

@Composable
private fun AgentReasoningStep(step: AgentThoughtStep.Reasoning, showDetails: Boolean) {
    val active = step.message.status == AgentMessageStatus.STREAMING
    AgentThoughtStepFrame(icon = MiuixIcons.Sparkle) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (active) R.string.agent_reasoning_active else R.string.agent_reasoning_title) +
                    " · " + if (active) "…" else "1s",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = LauncherTheme.style.titleColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (showDetails) {
            SafeMarkdownText(step.content, LauncherTheme.style.secondaryTextColor)
        }
    }
}

@Composable
private fun AgentToolInvocationStep(
    call: AgentToolCallRecord,
    result: AgentMessage?,
    showDetails: Boolean,
    onCopy: (String) -> Unit,
) {
    AgentToolStepContent(call.name, result, showDetails, onCopy)
}

@Composable
private fun AgentToolResultStep(
    result: AgentMessage,
    showDetails: Boolean,
    onCopy: (String) -> Unit,
) {
    AgentToolStepContent(result.toolName, result, showDetails, onCopy)
}

@Composable
private fun AgentToolStepContent(
    toolName: String?,
    result: AgentMessage?,
    showDetails: Boolean,
    onCopy: (String) -> Unit,
) {
    val presentation = remember(toolName, result?.content) {
        result?.let { parseToolPresentation(toolName, it.content) }
    }
    val title = stringResource(presentation?.titleResource ?: toolTitleResource(toolName))
    val summary = presentation?.summary?.displayText()
        ?: stringResource(R.string.agent_status_calling_tool)
    val details = presentation?.details?.map { it.displayText() }.orEmpty()
    val statusResource = when (result?.status) {
        null,
        AgentMessageStatus.STREAMING,
        -> R.string.agent_status_calling_tool
        AgentMessageStatus.COMPLETE -> R.string.agent_completed
        AgentMessageStatus.FAILED -> R.string.agent_failed
        AgentMessageStatus.CANCELLED -> R.string.agent_cancel
    }
    AgentThoughtStepFrame(icon = Icons.Outlined.Build) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = LauncherTheme.style.titleColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(statusResource),
                color = when (result?.status) {
                    AgentMessageStatus.FAILED -> MaterialTheme.colorScheme.error
                    AgentMessageStatus.COMPLETE -> LauncherTheme.style.positiveColor
                    else -> LauncherTheme.style.accentColor
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(summary, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.style.secondaryTextColor)
        if (showDetails) {
            details.forEach { detail ->
                Text("• $detail", style = MaterialTheme.typography.labelMedium, color = LauncherTheme.style.secondaryTextColor)
            }
            IconButton(
                onClick = { onCopy(buildToolCardCopyText(title, summary, details)) },
                modifier = Modifier.size(28.dp),
            ) { Icon(MiuixIcons.Copy, stringResource(R.string.agent_copy), modifier = Modifier.size(15.dp)) }
        }
    }
}

@Composable
private fun AgentThoughtStepFrame(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        FlueSurface(
            modifier = Modifier.size(20.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = LauncherTheme.style.topBarChipColor,
            border = null,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(12.dp), tint = LauncherTheme.style.accentColor)
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
private fun AgentTextMessageContent(
    message: AgentMessage,
    actionsEnabled: Boolean,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
) {
    val isUser = message.role == AgentMessageRole.USER
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        message.attachments.forEach { attachment ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(MiuixIcons.Attachment, null, Modifier.size(16.dp), tint = LauncherTheme.style.secondaryTextColor)
                Text(attachment.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = LauncherTheme.style.secondaryTextColor)
            }
        }
        message.content.takeIf(String::isNotBlank)?.let { content ->
            SafeMarkdownText(content, LauncherTheme.style.bodyColor)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = { onCopy(content) }, modifier = Modifier.size(28.dp)) { Icon(MiuixIcons.Copy, stringResource(R.string.agent_copy), modifier = Modifier.size(15.dp)) }
                if (isUser) IconButton(enabled = actionsEnabled, onClick = onEdit, modifier = Modifier.size(28.dp)) { Icon(MiuixIcons.Edit, stringResource(R.string.agent_edit), modifier = Modifier.size(15.dp)) }
                if (message.role == AgentMessageRole.ASSISTANT || isUser) IconButton(enabled = actionsEnabled, onClick = onRetry, modifier = Modifier.size(28.dp)) { Icon(MiuixIcons.Retry, stringResource(R.string.agent_retry), modifier = Modifier.size(15.dp)) }
            }
        }
    }
}

private data class ToolSummarySpec(
    val resource: Int,
    val arguments: List<Any> = emptyList(),
    val literal: String? = null,
)

private data class ToolCardPresentation(
    val titleResource: Int,
    val summary: ToolSummarySpec,
    val details: List<ToolSummarySpec>,
)

@Composable
private fun ToolSummarySpec.displayText(): String =
    literal ?: stringResource(resource, *arguments.toTypedArray())

private fun toolTitleResource(toolName: String?): Int = when (toolName) {
    "read_image" -> R.string.agent_tool_read_image
    "list_workspace_files" -> R.string.agent_tool_list_workspace
    "inspect_face" -> R.string.agent_tool_name_inspect
    "read_document" -> R.string.agent_tool_name_read
    "search_knowledge" -> R.string.agent_tool_name_search
    "validate_face" -> R.string.agent_tool_name_validate
    "preview_face" -> R.string.agent_tool_name_preview
    "edit_document" -> R.string.agent_tool_name_edit
    "write_document" -> R.string.agent_tool_name_write
    else -> R.string.agent_tool_name_generic
}

private fun parseToolPresentation(toolName: String?, raw: String): ToolCardPresentation {
    val title = toolTitleResource(toolName)
    if (raw.contains("ExceptionInInitializerError", ignoreCase = true) || raw.contains("ExceptionlnlnitializerError", ignoreCase = true)) {
        return ToolCardPresentation(
            title,
            ToolSummarySpec(R.string.agent_tool_initialization_failed),
            listOf(ToolSummarySpec(R.string.agent_tool_retry_detail)),
        )
    }
    return runCatching {
        val json = org.json.JSONObject(raw)
        val revision = json.optLong("revision")
        if (!json.optBoolean("ok", true)) {
            val message = json.optJSONObject("error")?.optString("message").orEmpty().toHumanSummary()
            return@runCatching ToolCardPresentation(
                title,
                ToolSummarySpec(R.string.agent_tool_failed_summary),
                listOf(ToolSummarySpec(R.string.agent_tool_detail_message, listOf(message.ifBlank { "—" }))),
            )
        }
        val result = json.optJSONObject("result")
        when (toolName) {
            "inspect_face" -> {
                val layers = result?.optJSONArray("layers")?.length() ?: 0
                val assets = result?.optJSONArray("assets")?.length() ?: 0
                ToolCardPresentation(
                    title,
                    ToolSummarySpec(R.string.agent_tool_inspect_summary, listOf(layers, assets)),
                    listOfNotNull(
                        result?.optString("name")?.takeIf(String::isNotBlank)?.let { ToolSummarySpec(R.string.agent_tool_detail_face_name, listOf(it.toHumanSummary())) },
                        result?.optString("format")?.takeIf(String::isNotBlank)?.let { ToolSummarySpec(R.string.agent_tool_detail_format, listOf(it.toHumanSummary())) },
                        ToolSummarySpec(R.string.agent_tool_detail_layers, listOf(layers)),
                        ToolSummarySpec(R.string.agent_tool_detail_assets, listOf(assets)),
                        ToolSummarySpec(if (result?.optBoolean("has_global_script", false) == true) R.string.agent_tool_detail_script_available else R.string.agent_tool_detail_script_unavailable),
                        ToolSummarySpec(if (result?.optBoolean("preview_available", false) == true) R.string.agent_tool_detail_preview_available else R.string.agent_tool_detail_preview_unavailable),
                    ),
                )
            }
            "validate_face" -> {
                val diagnostics = result?.optJSONArray("diagnostics")
                val errors = countSeverity(diagnostics, "error")
                val warnings = countSeverity(diagnostics, "warning")
                val detailRows = mutableListOf(
                    ToolSummarySpec(R.string.agent_tool_detail_errors, listOf(errors)),
                    ToolSummarySpec(R.string.agent_tool_detail_warnings, listOf(warnings)),
                )
                diagnosticMessages(diagnostics).forEach { detailRows += ToolSummarySpec(R.string.agent_tool_detail_diagnostic, listOf(it)) }
                ToolCardPresentation(
                    title,
                    if (result?.optBoolean("valid", false) == true) ToolSummarySpec(R.string.agent_tool_validation_passed, listOf(warnings))
                    else ToolSummarySpec(R.string.agent_tool_validation_failed, listOf(errors, warnings)),
                    detailRows,
                )
            }
            "preview_face" -> {
                val available = result?.optBoolean("available", false) == true
                val targetName = result?.optString("target", "face").orEmpty().toHumanSummary()
                val byteCount = result?.optInt("image_byte_count", 0) ?: 0
                val detailRows = mutableListOf(
                    ToolSummarySpec(R.string.agent_tool_detail_target, listOf(targetName)),
                    ToolSummarySpec(R.string.agent_tool_detail_renderer, listOf(result?.optString("renderer").orEmpty().toHumanSummary().ifBlank { "—" })),
                    ToolSummarySpec(R.string.agent_tool_detail_image_size, listOf(byteCount)),
                    ToolSummarySpec(R.string.agent_tool_detail_revision, listOf(revision)),
                )
                diagnosticMessages(result?.optJSONArray("diagnostics")).forEach { detailRows += ToolSummarySpec(R.string.agent_tool_detail_diagnostic, listOf(it)) }
                ToolCardPresentation(
                    title,
                    if (available) ToolSummarySpec(R.string.agent_tool_preview_ready, listOf(targetName, byteCount)) else ToolSummarySpec(R.string.agent_tool_preview_unavailable),
                    detailRows,
                )
            }
            "read_document" -> documentPresentation(title, result, revision, R.string.agent_tool_document_read)
            "edit_document", "write_document" -> documentPresentation(title, result, revision, R.string.agent_tool_document_updated)
            "search_knowledge" -> {
                val hits = result?.optJSONArray("hits")
                val hitCount = hits?.length() ?: 0
                val details = mutableListOf<ToolSummarySpec>()
                result?.optString("query")?.takeIf(String::isNotBlank)?.let { details += ToolSummarySpec(R.string.agent_tool_detail_query, listOf(it.toHumanSummary())) }
                details += ToolSummarySpec(R.string.agent_tool_detail_hits, listOf(hitCount))
                if (hits != null) (0 until minOf(hits.length(), 3)).forEach { index ->
                    val hit = hits.optJSONObject(index) ?: return@forEach
                    val titleText = hit.optString("title").toHumanSummary()
                    val snippet = hit.optString("snippet").toHumanSummary()
                    details += ToolSummarySpec(R.string.agent_tool_detail_hit, listOf(titleText.ifBlank { "—" }, snippet.ifBlank { "—" }))
                }
                ToolCardPresentation(title, ToolSummarySpec(R.string.agent_tool_search_summary, listOf(hitCount)), details)
            }
            else -> ToolCardPresentation(
                title,
                ToolSummarySpec(R.string.agent_tool_result_ready, listOf(revision)),
                listOf(ToolSummarySpec(R.string.agent_tool_detail_revision, listOf(revision))),
            )
        }
    }.getOrElse {
        ToolCardPresentation(
            title,
            ToolSummarySpec(R.string.agent_tool_unreadable_summary),
            listOf(ToolSummarySpec(R.string.agent_tool_retry_detail)),
        )
    }
}

private fun documentPresentation(title: Int, result: org.json.JSONObject?, revision: Long, summaryResource: Int): ToolCardPresentation {
    val path = result?.optString("path").orEmpty().toHumanSummary()
    val contentLength = result?.optString("content").orEmpty().length
    return ToolCardPresentation(
        title,
        ToolSummarySpec(summaryResource, listOf(path, revision)),
        listOf(
            ToolSummarySpec(R.string.agent_tool_detail_path, listOf(path.ifBlank { "—" })),
            ToolSummarySpec(R.string.agent_tool_detail_revision, listOf(revision)),
            ToolSummarySpec(R.string.agent_tool_detail_document_size, listOf(contentLength)),
        ),
    )
}

private fun diagnosticMessages(items: org.json.JSONArray?): List<String> {
    if (items == null) return emptyList()
    return (0 until minOf(items.length(), 4)).mapNotNull { index ->
        items.optJSONObject(index)?.optString("message")?.toHumanSummary()?.takeIf(String::isNotBlank)
    }
}

private fun String.toHumanSummary(): String =
    replace(Regex("\\s+"), " ").trim().let { if (it.length > 160) it.take(157) + "…" else it }

private fun buildToolCardCopyText(title: String, summary: String, details: List<String>): String =
    buildString {
        append(title)
        append('\n')
        append(summary)
        details.forEach { append("\n• ").append(it) }
    }

private fun countSeverity(items: org.json.JSONArray?, severity: String): Int {
    if (items == null) return 0
    return (0 until items.length()).count { index -> items.optJSONObject(index)?.optString("severity") == severity }
}

@Composable
private fun SafeMarkdownText(markdown: String, color: Color) {
    val linkColor = MaterialTheme.colorScheme.primary
    Text(
        text = remember(markdown, color, linkColor) { safeMarkdown(markdown, color, linkColor) },
        color = color,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun safeMarkdown(source: String, color: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var inCodeBlock = false
    source.lines().forEachIndexed { index, original ->
        if (original.trimStart().startsWith("```")) {
            inCodeBlock = !inCodeBlock
        } else if (inCodeBlock) {
            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = color))
            append(original)
            pop()
        } else {
            val trimmed = original.trimStart()
            val headingMarks = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
            val line = when {
                headingMarks > 0 && trimmed.getOrNull(headingMarks) == ' ' -> trimmed.drop(headingMarks + 1)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> "• ${trimmed.drop(2)}"
                else -> original
            }
            if (headingMarks > 0) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            appendInlineMarkdown(line, color, linkColor)
            if (headingMarks > 0) pop()
        }
        if (index != source.lines().lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(line: String, color: Color, linkColor: Color) {
    var index = 0
    while (index < line.length) {
        when {
            line.startsWith("**", index) -> {
                val end = line.indexOf("**", index + 2)
                if (end > index + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(line.substring(index + 2, end)); pop(); index = end + 2
                } else { append(line[index]); index++ }
            }
            line[index] == '`' -> {
                val end = line.indexOf('`', index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = color)); append(line.substring(index + 1, end)); pop(); index = end + 1
                } else { append(line[index]); index++ }
            }
            line[index] == '*' -> {
                val end = line.indexOf('*', index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(line.substring(index + 1, end)); pop(); index = end + 1
                } else { append(line[index]); index++ }
            }
            line[index] == '[' -> {
                val labelEnd = line.indexOf(']', index + 1)
                val urlStart = if (labelEnd >= 0) line.indexOf('(', labelEnd + 1) else -1
                val urlEnd = if (urlStart >= 0) line.indexOf(')', urlStart + 1) else -1
                if (labelEnd > index && urlStart == labelEnd + 1 && urlEnd > urlStart + 1) {
                    pushStringAnnotation("URL", line.substring(urlStart + 1, urlEnd))
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)); append(line.substring(index + 1, labelEnd)); pop(); pop(); index = urlEnd + 1
                } else { append(line[index]); index++ }
            }
            else -> { append(line[index]); index++ }
        }
    }
}

private fun AgentGenerationStatus.labelResource(): Int = when (this) {
    AgentGenerationStatus.THINKING -> R.string.agent_status_thinking
    AgentGenerationStatus.CALLING_TOOL -> R.string.agent_status_calling_tool
    AgentGenerationStatus.PROCESSING_PREVIEW -> R.string.agent_status_processing_preview
    AgentGenerationStatus.PROCESSING_FOLLOW_UP -> R.string.agent_status_follow_up
}

private fun copyToClipboard(context: Context, content: String) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Agent", content)) }

@Composable private fun AttachmentStrip(attachments: List<AgentAttachment>, onRemove: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { attachments.forEach { attachment -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(MiuixIcons.Attachment, null, Modifier.size(16.dp)); Text(" ${attachment.displayName} · ${attachment.sizeBytes} B", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); TextButton(onClick = { onRemove(attachment.id) }) { Text(stringResource(R.string.agent_remove)) } } } }
}

@Composable private fun EmptyAgentState() { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(MiuixIcons.Sparkle, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary); Text(stringResource(R.string.agent_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun AgentHistoryDialog(viewModel: WatchAgentViewModel, conversations: List<AgentConversation>) {
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AgentConversation?>(null) }
    var deleteTarget by remember { mutableStateOf<AgentConversation?>(null) }
    FlueAlertDialog(
        onDismissRequest = { viewModel.toggleHistory(false) },
        title = stringResource(R.string.agent_history),
        scrollableContent = false,
        confirmText = stringResource(R.string.agent_close),
        onConfirm = { viewModel.toggleHistory(false) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations, key = { it.id }) { conversation ->
                        FlueSurface(Modifier.fillMaxWidth(), shape = LauncherTheme.style.smallControlShape, color = LauncherTheme.style.topBarChipColor, border = null) {
                            Column(Modifier.fillMaxWidth().combinedClickable(
                                onClick = { viewModel.selectConversation(conversation.id) },
                                onLongClickLabel = stringResource(R.string.agent_conversation_actions),
                                onLongClick = { selected = conversation },
                            ).padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Text(conversation.title.localizedConversationTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, color = LauncherTheme.style.titleColor)
                                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(conversation.updatedAt)),
                                    style = MaterialTheme.typography.labelSmall, color = LauncherTheme.style.secondaryTextColor)
                            }
                        }
                    }
                }
                FlueButton(onClick = viewModel::newConversation, modifier = Modifier.fillMaxWidth(), shape = LauncherTheme.style.smallControlShape) { Text(stringResource(R.string.agent_new_conversation)) }
            }
        },
    )
    if (renameId != null) FlueAlertDialog(
        onDismissRequest = { renameId = null },
        title = stringResource(R.string.agent_rename_conversation),
        confirmText = stringResource(R.string.agent_save),
        confirmEnabled = renameText.isNotBlank(),
        onConfirm = { viewModel.renameConversation(renameId!!, renameText); renameId = null },
        dismissText = stringResource(R.string.agent_cancel),
        content = { FlueTextField(renameText, { renameText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true) },
    )
    selected?.let { conversation ->
        FlueAlertDialog(onDismissRequest = { selected = null }, title = conversation.title.localizedConversationTitle(),
            confirmText = stringResource(R.string.agent_close), onConfirm = { selected = null }, content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlueOutlinedButton(onClick = { renameId = conversation.id; renameText = conversation.title; selected = null }, modifier = Modifier.fillMaxWidth(), shape = LauncherTheme.style.smallControlShape) { Text(stringResource(R.string.agent_rename_conversation)) }
                    FlueOutlinedButton(onClick = { deleteTarget = conversation; selected = null }, modifier = Modifier.fillMaxWidth(), shape = LauncherTheme.style.smallControlShape) { Text(stringResource(R.string.action_delete)) }
                }
            })
    }
    deleteTarget?.let { conversation ->
        FlueAlertDialog(onDismissRequest = { deleteTarget = null }, title = stringResource(R.string.agent_delete_conversation),
            text = conversation.title.localizedConversationTitle(), dangerous = true,
            confirmText = stringResource(R.string.action_delete), dismissText = stringResource(R.string.agent_cancel),
            onConfirm = { viewModel.deleteConversation(conversation.id); deleteTarget = null })
    }
}

@Composable
private fun String?.localizedConversationTitle(): String =
    takeUnless { it.isNullOrBlank() || it == "新会话" } ?: stringResource(R.string.agent_new_conversation)
