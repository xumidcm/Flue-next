package com.flue.launcher.watchface.agent.runtime

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flue.launcher.watchface.agent.model.AgentConversation
import com.flue.launcher.watchface.agent.model.AgentAttachment
import com.flue.launcher.watchface.agent.model.AgentMessage
import com.flue.launcher.watchface.agent.model.AgentMessageRole
import com.flue.launcher.watchface.agent.model.AgentMessageStatus
import com.flue.launcher.watchface.agent.model.AgentSurface
import com.flue.launcher.watchface.agent.model.AgentGenerationStatus
import com.flue.launcher.watchface.agent.model.AiModelDescriptor
import com.flue.launcher.watchface.agent.model.AgentToolCallRecord
import com.flue.launcher.watchface.agent.model.WatchAgentUiState
import com.flue.launcher.watchface.agent.storage.AgentConversationRepository
import com.flue.launcher.watchface.agent.storage.ProviderSettingsStore
import com.flue.launcher.watchface.agent.storage.AgentFeatureSettingsStore
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import com.flue.launcher.watchface.agent.workspace.ImportResult
import com.flue.launcher.watchface.agent.workspace.WorkspacePermissionStore
import com.flue.launcher.watchface.agent.workspace.AndroidProotProcessGroupRunner
import com.flue.launcher.watchface.agent.workspace.SafeProotShellRunner
import com.flue.launcher.watchface.agent.workspace.BashApprovalStrategy
import com.flue.launcher.watchface.agent.workspace.BashApproval
import com.flue.launcher.watchface.agent.prompt.WatchAgentPrompt
import com.flue.launcher.watchface.agent.provider.AgentProviderClient
import com.flue.launcher.watchface.agent.provider.ImageGenerationOptions
import com.flue.launcher.watchface.agent.provider.OpenAiImageClient
import com.flue.launcher.watchface.agent.tools.TavilyWebResearchClient
import com.flue.launcher.watchface.agent.provider.AgentRequestMessage
import com.flue.launcher.watchface.agent.provider.AgentStreamEvent
import com.flue.launcher.watchface.agent.provider.AgentProviderRequest
import com.flue.launcher.watchface.agent.tools.WatchAgentToolHost
import com.flue.launcher.watchface.agent.tools.WatchAgentToolError
import com.flue.launcher.watchface.agent.tools.WatchAgentToolResult
import com.flue.launcher.watchface.agent.tools.WatchAgentToolRunner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal data class AgentGenerationScope(
    val token: Long,
    val conversationId: String,
    val watchScope: String,
)

/** Owns the one generation that may publish into the currently visible conversation. */
internal class AgentGenerationGate {
    private var nextToken = 0L
    private var activeToken: Long? = null

    @Synchronized
    fun begin(conversationId: String, watchScope: String): AgentGenerationScope =
        AgentGenerationScope(++nextToken, conversationId, watchScope).also { activeToken = it.token }

    @Synchronized
    fun invalidate() {
        activeToken = null
    }

    @Synchronized
    fun canPublish(
        generation: AgentGenerationScope,
        activeConversationId: String?,
        activeWatchScope: String,
    ): Boolean = activeToken == generation.token &&
        activeConversationId == generation.conversationId &&
        activeWatchScope == generation.watchScope

    @Synchronized
    fun publishIfCurrent(
        generation: AgentGenerationScope,
        activeConversationId: String?,
        activeWatchScope: String,
        publish: () -> Unit,
    ): Boolean {
        if (!canPublish(generation, activeConversationId, activeWatchScope)) return false
        publish()
        return true
    }

    @Synchronized
    fun completeIfCurrent(
        generation: AgentGenerationScope,
        activeConversationId: String?,
        activeWatchScope: String,
        publish: () -> Unit,
    ): Boolean {
        if (!canPublish(generation, activeConversationId, activeWatchScope)) return false
        activeToken = null
        publish()
        return true
    }

    @Synchronized
    fun complete(generation: AgentGenerationScope): Boolean {
        if (activeToken != generation.token) return false
        activeToken = null
        return true
    }
}

/** Tracks tool calls already persisted in history but not yet paired with a tool result. */
internal class AgentPendingToolCallLedger {
    private val pending = linkedMapOf<String, AgentToolCallRecord>()

    fun recordCalls(calls: List<AgentToolCallRecord>) {
        calls.forEach { call -> pending[call.id] = call }
    }

    fun recordResults(results: List<WatchAgentToolResult>) {
        results.forEach { result -> pending.remove(result.callId) }
    }

    fun drainCancelled(revision: Long): List<WatchAgentToolResult> = pending.values.map { call ->
        WatchAgentToolResult(
            callId = call.id,
            name = call.name,
            revision = revision,
            error = WatchAgentToolError("cancelled", "Agent generation was cancelled before this tool returned"),
        )
    }.also { pending.clear() }
}

class WatchAgentViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AgentConversationRepository(application)
    private val providerStore = ProviderSettingsStore(application)
    private val providerClient = AgentProviderClient()
    private val featureSettings = AgentFeatureSettingsStore(application)
    private val workspaceManager = WorkspaceManager(File(application.filesDir, "agent-runtime").toPath())
    private val permissionStore = WorkspacePermissionStore(application)
    private val shellRunner by lazy {
      SafeProotShellRunner(
        manager = workspaceManager,
        processGroupRunner = AndroidProotProcessGroupRunner(
            nativeLibraryDir = File(application.applicationInfo.nativeLibraryDir).toPath(),
            networkAllowed = { permissionStore.load().networkAllowed },
        ),
        permissionProvider = permissionStore::load,
        approvalStrategy = BashApprovalStrategy { BashApproval.ALLOW },
      )
    }
    private val _state = MutableStateFlow(WatchAgentUiState.initial(""))
    val state: StateFlow<WatchAgentUiState> = _state.asStateFlow()
    private val _conversations = MutableStateFlow<List<AgentConversation>>(emptyList())
    val conversations: StateFlow<List<AgentConversation>> = _conversations.asStateFlow()
    private val _messages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages: StateFlow<List<AgentMessage>> = _messages.asStateFlow()
    private var entered = false
    private var generationJob: Job? = null
    private val generationGate = AgentGenerationGate()
    private var toolHost: WatchAgentToolHost? = null
    private val _choices = MutableStateFlow(AgentConversationChoices())
    val choices = _choices.asStateFlow()
    private val titleJobs = mutableMapOf<String, Job>()
    private var choicesVersion = 0L

    fun refreshChoices(fetchModels: Boolean = false) {
        val version = ++choicesVersion
        _choices.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val scope = _state.value.watchScope
            try {
            val next = withContext(Dispatchers.IO) {
                val profile = providerStore.active()
                var models = profile?.let { providerStore.cachedModels(it.id) }.orEmpty()
                var error: String? = null
                if (profile != null && fetchModels) {
                    runCatching { providerClient.listModels(profile, providerStore.apiKey(profile.id).orEmpty()) }
                        .onSuccess { models = it; providerStore.cacheModels(profile.id, it) }
                        .onFailure { error = it.displayableMessage() }
                }
                AgentConversationChoices(profile, models, workspaceManager.list(), workspaceManager.bindingFor(scope)?.workspaceId, error)
            }
            if (_state.value.watchScope == scope && version == choicesVersion) {
                val active = withContext(Dispatchers.IO) { providerStore.active() }
                if (active?.id != next.profile?.id) refreshChoices()
                else _choices.value = next.copy(profile = active)
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (_state.value.watchScope == scope && version == choicesVersion) _choices.update { it.copy(loading = false, error = error.displayableMessage()) }
            }
        }
    }

    fun selectModel(modelId: String) {
        val current = _choices.value
        val profile = current.profile ?: return
        if (current.models.none { it.id == modelId }) return
        providerStore.updateGeneration(profile.id, modelId, profile.reasoningEffort)
        _choices.value = current.copy(profile = profile.copy(modelId = modelId))
    }

    fun selectReasoning(effort: String?) {
        if (effort !in listOf(null, "none", "low", "medium", "high")) return
        val current = _choices.value
        val profile = current.profile ?: return
        providerStore.updateGeneration(profile.id, profile.modelId, effort)
        _choices.value = current.copy(profile = profile.copy(reasoningEffort = effort))
    }

    fun selectWorkspace(id: String) {
        if (_state.value.isGenerating) return
        val watchScope = _state.value.watchScope
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceManager.bind(watchScope, id) }
                .onSuccess { refreshChoices() }
                .onFailure { error -> _choices.update { it.copy(error = error.displayableMessage()) } }
        }
    }

    fun createWorkspace(name: String) {
        if (name.isBlank() || _state.value.isGenerating) return
        val watchScope = _state.value.watchScope
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceManager.create(name.trim(), watchScope = watchScope) }
                .onSuccess { refreshChoices() }
                .onFailure { error -> _choices.update { it.copy(error = error.displayableMessage()) } }
        }
    }

    fun renameConversation(id: String, title: String) {
        if (repository.getConversation(id)?.watchScope != _state.value.watchScope || title.isBlank()) return
        repository.renameConversation(id, title)
        _conversations.value = repository.listConversations(_state.value.watchScope)
    }

    fun deleteConversation(id: String) {
        if (repository.getConversation(id)?.watchScope != _state.value.watchScope) return
        titleJobs.remove(id)?.cancel()
        if (_state.value.activeConversationId == id) {
            invalidateGeneration()
            _state.update { it.copy(activeConversationId = null, isGenerating = false, generationStatus = null, pendingFollowUp = null, inputDraft = "", attachments = emptyList(), editingMessageId = null) }
            _messages.value = emptyList()
        }
        repository.deleteConversation(id)
        _conversations.value = repository.listConversations(_state.value.watchScope)
    }

    private fun generateTitle(conversation: AgentConversation, profile: com.flue.launcher.watchface.agent.model.AiProviderProfile, apiKey: String, prompt: String, answer: String) {
        if (repository.getConversation(conversation.id)?.titleFinalized != false || titleJobs[conversation.id]?.isActive == true) return
        titleJobs[conversation.id] = viewModelScope.launch {
            try {
                val completion = providerClient.send(profile.copy(reasoningEffort = null), apiKey,
                    AgentConversationTitlePrompt.request(prompt, answer))
                val title = completion.text.lineSequence().firstOrNull().orEmpty()
                    .replace(Regex("""^[\s\"'“”‘’`#：:、-]+|[\s\"'“”‘’`。.!！?？]+$"""), "")
                    .trim()
                    .takeIf { it.isNotBlank() }
                if (title != null) repository.applyGeneratedTitle(conversation.id, title)
                if (_state.value.watchScope == conversation.watchScope) _conversations.value = repository.listConversations(conversation.watchScope)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the first-message title; retry after a later successful turn. */ }
            finally { titleJobs.remove(conversation.id) }
        }
    }

    fun bindToolHost(host: WatchAgentToolHost) {
        toolHost = host
    }

    fun unbindToolHost(host: WatchAgentToolHost) {
        if (toolHost === host) toolHost = null
    }

    fun onEditorEntered(watchScope: String) {
        if (entered && _state.value.watchScope == watchScope) return
        entered = true
        invalidateGeneration()
        _state.value = WatchAgentUiState.initial(watchScope)
        _conversations.value = repository.listConversations(watchScope)
        _messages.value = emptyList()
    }

    fun onEditorExited() {
        val id = _state.value.activeConversationId
        if (id != null) repository.markEnded(id)
        invalidateGeneration()
        entered = false
        _state.update { it.copy(isGenerating = false, surface = AgentSurface.SPLIT) }
    }

    fun openAgent() {
        ensureConversation()
    }

    fun enterFullscreen() { _state.update { it.enterFullscreen() } }
    fun exitFullscreen() { _state.update { it.exitFullscreen() } }

    fun minimize() {
        _state.update { it.copy(surface = AgentSurface.SPLIT) }
    }

    fun updateDraft(value: String) { _state.update { it.copy(inputDraft = value.take(MAX_DRAFT_LENGTH)) } }

    fun addAttachment(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "附件"
                val mime = resolver.getType(uri)
                val root = File(context.filesDir, "agent-attachments").apply { mkdirs() }
                val target = File(root, "${java.util.UUID.randomUUID()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) { val count = input.read(buffer); if (count < 0) break; if (count == 0) continue; output.write(buffer, 0, count); digest.update(buffer, 0, count); size += count }
                } } ?: error("无法读取所选文件")
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                val localPath = if (
                    featureSettings.load().copyAttachmentsIntoWorkspace &&
                    permissionStore.load().attachmentImportAllowed &&
                    _state.value.watchScope.isNotBlank()
                ) {
                    val scope = _state.value.watchScope
                    val binding = workspaceManager.bindingFor(scope)
                        ?: workspaceManager.create("Flue ${scope.take(28)}", watchScope = scope).binding
                    val workspace = binding?.let { workspaceManager.get(it.workspaceId) }
                    val relative = "workspace/attachments/${target.name}"
                    if (workspace != null && workspaceManager.importFile(workspace.id, target.toPath(), relative) == ImportResult.IMPORTED) {
                        workspace.paths.root.resolve(relative).toString()
                    } else target.absolutePath
                } else target.absolutePath
                AgentAttachment(java.util.UUID.randomUUID().toString(), name, mime, size, sha, localPath)
            }.onSuccess { attachment ->
                _state.update { it.copy(attachments = it.attachments + attachment, attachmentError = null) }
            }.onFailure { error ->
                _state.update { it.copy(attachmentError = error.message?.take(240) ?: error.javaClass.simpleName) }
            }
        }
    }

    fun removeAttachment(id: String) { _state.update { it.copy(attachments = it.attachments.filterNot { attachment -> attachment.id == id }) } }
    fun clearAttachmentError() { _state.update { it.copy(attachmentError = null) } }
    fun cancelPendingFollowUp() { _state.update { it.copy(pendingFollowUp = null) } }

    fun toggleHistory(show: Boolean) { _state.update { it.copy(showHistory = show, showSettings = false) } }
    fun toggleSettings(show: Boolean) { _state.update { it.copy(showSettings = show, showHistory = false) } }

    fun toggleTool(toolId: String) = _state.update {
        it.toggleTool(toolId)
    }

    fun toggleToolDetails(toolId: String) = _state.update {
        it.toggleToolDetails(toolId)
    }

    fun toggleReasoning(messageId: String) = _state.update {
        it.toggleReasoning(messageId)
    }

    fun activeConversationId(): String? = _state.value.activeConversationId

    fun resumeConversation(id: String) {
        repository.markResumed(id)
        selectConversation(id)
    }

    fun selectConversation(id: String) {
        val conversation = _conversations.value.firstOrNull { it.id == id } ?: return
        invalidateGeneration()
        _state.update {
            it.copy(
                activeConversationId = conversation.id,
                showHistory = false,
                inputDraft = "",
                editingMessageId = null,
                attachments = emptyList(),
                isGenerating = false,
                pendingFollowUp = null,
                generationStatus = null,
                turnTokens = null,
                conversationTokens = conversation.tokenCount,
            )
        }
        _messages.value = repository.listMessages(conversation.id)
    }

    fun newConversation() {
        invalidateGeneration()
        _state.update {
            it.copy(
                activeConversationId = null,
                inputDraft = "",
                showHistory = false,
                isGenerating = false,
                editingMessageId = null,
                attachments = emptyList(),
                pendingFollowUp = null,
                generationStatus = null,
                turnTokens = null,
                conversationTokens = 0L,
            )
        }
        _messages.value = emptyList()
    }

    fun send() {
        val prompt = _state.value.inputDraft.trim()
        if (prompt.isEmpty()) return
        if (_state.value.isGenerating) {
            _state.update { it.copy(pendingFollowUp = prompt, inputDraft = "") }
            return
        }
        val conversation = ensureConversation()
        val now = System.currentTimeMillis()
        val attachments = _state.value.attachments
        val editing = _state.value.editingMessageId?.let { id ->
            _messages.value.firstOrNull { it.id == id && it.role == AgentMessageRole.USER }
        }
        val user = editing?.copy(content = prompt, attachments = attachments, status = AgentMessageStatus.COMPLETE)
            ?: AgentMessage.user(conversation.id, prompt, now).copy(attachments = attachments)
        if (editing != null) repository.replaceMessageAndDeleteAfter(user)
        else repository.appendMessage(conversation.id, user)
        _conversations.value = repository.listConversations(_state.value.watchScope)
        refresh(conversation.id)
        _state.update { it.copy(inputDraft = "", attachments = emptyList(), editingMessageId = null, attachmentError = null, isGenerating = true, turnTokens = null, conversationTokens = conversation.tokenCount) }
        // Keep one stable placeholder for each provider turn. A tool turn updates
        // the placeholder in place, then the next turn gets a new placeholder
        // after the tool results. This preserves the provider's timeline while
        // keeping LazyColumn item identity stable during streaming.
        var assistantId = java.util.UUID.randomUUID().toString()
        var assistantThinkingStartedAt = SystemClock.uptimeMillis()
        repository.appendMessage(
            conversation.id,
            AgentMessage(
                id = assistantId,
                conversationId = conversation.id,
                role = AgentMessageRole.ASSISTANT,
                content = "",
                createdAt = now + 1L,
                status = AgentMessageStatus.STREAMING,
            ),
        )
        refresh(conversation.id)
        val generation = generationGate.begin(conversation.id, _state.value.watchScope)
        val streamAccumulator = AgentStreamAccumulator()
        val reasoningAccumulator = AgentStreamAccumulator()
        val pendingToolCalls = AgentPendingToolCallLedger()
        val generationHost = toolHost ?: EmptyWatchAgentToolHost
        var encodeToolResult: ((WatchAgentToolResult) -> String)? = null
        var turnInputTokens = 0L
        var turnOutputTokens = 0L
        var usageCommitted = false
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val configured = withContext(Dispatchers.IO) {
                    providerStore.active()?.let { profile ->
                        profile to providerStore.apiKey(profile.id)
                    }
                }
                val profile = configured?.first
                val apiKey = configured?.second
                if (profile == null || apiKey.isNullOrBlank() || profile.modelId.isNullOrBlank()) {
                    updateAssistant(
                        generation,
                        assistantId,
                        "请先打开“Agent 设置”，填写服务商地址、模型 ID 和 API 密钥。",
                        AgentMessageStatus.FAILED,
                    )
                } else {
                    val acceptsImages = providerStore.cachedModels(profile.id).firstOrNull { it.id == profile.modelId }?.supportsVision != false
                    if (!acceptsImages && attachments.any { it.mimeType?.startsWith("image/") == true }) {
                        error("当前模型不支持图片输入，请在对话顶部选择支持视觉的模型后重试。")
                    }
                    var storedMessages = repository.listMessages(conversation.id)
                    val descriptor = providerStore.cachedModels(profile.id).firstOrNull { it.id == profile.modelId }
                        ?: AiModelDescriptor(profile.modelId.orEmpty())
                    val contextWindow = com.flue.launcher.watchface.agent.provider.AgentModelContextResolver.contextWindow(profile, descriptor)
                    val estimatedTokens = estimateMessageTokens(storedMessages) + estimateTextTokens(WatchAgentPrompt.baseline)
                    if (estimatedTokens >= (contextWindow * 0.8).toLong()) {
                        updateGenerationState(generation) { it.copy(generationStatus = AgentGenerationStatus.COMPRESSING) }
                        compressConversation(conversation, profile, apiKey, storedMessages)
                        storedMessages = repository.listMessages(conversation.id)
                        publishGeneration(generation) { refresh(conversation.id) }
                    }
                    val recentImageIds = storedMessages.flatMap { it.attachments }.filter { it.mimeType?.startsWith("image/") == true }.takeLast(4).map { it.id }.toSet()
                    val history = withContext(Dispatchers.IO) { storedMessages
                        .filter { it.id != assistantId && it.role in setOf(AgentMessageRole.USER, AgentMessageRole.ASSISTANT, AgentMessageRole.TOOL) }
                        .map { message ->
                            AgentRequestMessage(
                                role = message.role,
                                content = message.content + message.attachments.joinToString("", prefix = if (message.attachments.isEmpty()) "" else "\n\n附件：\n") { "- ${it.displayName} (${it.mimeType ?: "未知类型"}, ${formatByteCountForPrompt(it.sizeBytes)}, sha256=${it.sha256.orEmpty()})" },
                                name = message.toolName,
                                images = if (acceptsImages && message.role == AgentMessageRole.USER) message.attachments
                                    .filter { it.id in recentImageIds }
                                    .mapNotNull { attachment -> attachment.localPath?.let { path ->
                                        runCatching { com.flue.launcher.watchface.agent.provider.AgentImageInput.fromFile(File(path)) }
                                            .getOrElse { if (message.id == user.id) throw it else null }
                                    } } else emptyList(),
                                toolCallId = message.toolCallId,
                                toolCalls = message.toolCalls.map { call ->
                                    com.flue.launcher.watchface.agent.provider.AgentAssistantToolCall(
                                        call.id,
                                        call.name,
                                        call.argumentsJson,
                                    )
                                },
                            )
                        } }
                    val workspaceId = workspaceManager.bindingFor(generation.watchScope)?.workspaceId.orEmpty()
                    val capability = workspaceManager.capability(workspaceId)
                    val permissions = permissionStore.load()
                    val feature = featureSettings.load()
                    val imageClient = feature.imageBaseUrl.trim().takeIf { it.isNotBlank() }
                        ?.let { baseUrl ->
                            feature.imageModelId.trim().takeIf { it.isNotBlank() }?.let { model ->
                                featureSettings.imageApiKey()?.takeIf { it.isNotBlank() }?.let { key ->
                                    runCatching { OpenAiImageClient(baseUrl, key, model) }.getOrNull()
                                }
                            }
                        }
                    val webClient = feature.tavilyBaseUrl.trim().takeIf { it.isNotBlank() }
                        ?.let { baseUrl -> featureSettings.tavilyApiKey()?.takeIf { it.isNotBlank() }?.let { key ->
                            runCatching {
                                TavilyWebResearchClient(
                                    baseUrl = baseUrl,
                                    apiKey = key,
                                    defaultMaxResults = feature.searchMaxResults,
                                    defaultSearchDepth = feature.searchDepth,
                                    fetchEnabled = feature.webFetchEnabled,
                                )
                            }.getOrNull()
                        } }
                    val capabilityPrompt = WatchAgentPrompt.availableCapabilities(
                        capability = capability,
                        networkAllowed = permissions.networkAllowed,
                        approvalMode = permissions.approvalMode,
                        imageAvailable = imageClient != null,
                        webSearchAvailable = webClient != null,
                        webFetchAvailable = webClient != null && feature.webFetchEnabled,
                    )
                    val requestMessages = listOf(
                        AgentRequestMessage(AgentMessageRole.SYSTEM, WatchAgentPrompt.baseline + capabilityPrompt),
                    ) + history
                    val loop = WatchAgentLoop(
                        acceptsImages = acceptsImages,
                        completionExecutor = AgentCompletionExecutor { request: AgentProviderRequest, onEvent: (AgentStreamEvent) -> Unit ->
                            providerClient.send(
                                profile = profile,
                                apiKey = apiKey,
                                request = request,
                                onEvent = onEvent,
                            )
                        },
                        toolRunner = WatchAgentToolRunner(
                            host = generationHost,
                            permissionProvider = permissionStore::load,
                            workspaceImages = com.flue.launcher.watchface.agent.tools.AgentWorkspaceImageTools(workspaceManager, generation.watchScope),
                            shellRunner = shellRunner,
                            workspaceId = workspaceManager.bindingFor(generation.watchScope)?.workspaceId.orEmpty(),
                            workspaceIdProvider = {
                                workspaceManager.bindingFor(generation.watchScope)?.workspaceId.orEmpty()
                            },
                            imageClient = imageClient,
                            webClient = webClient,
                            imageDefaults = ImageGenerationOptions(
                                size = feature.imageSize.trim().takeIf { it.isNotBlank() },
                                quality = feature.imageQuality.trim().takeIf { it.isNotBlank() },
                                background = feature.imageBackground.trim().takeIf { it.isNotBlank() },
                            ),
                            webMaxResults = feature.searchMaxResults,
                            webSearchDepth = feature.searchDepth,
                            webFetchEnabled = feature.webFetchEnabled,
                        ),
                    )
                    encodeToolResult = loop::encodeToolResult
                    updateGenerationState(generation) { it.copy(generationStatus = AgentGenerationStatus.THINKING) }
                    val completion = loop.run(
                        initialMessages = requestMessages,
                        onEvent = { event ->
                            if (event is AgentStreamEvent.TextDelta) {
                                streamAccumulator.append(event.text, SystemClock.uptimeMillis())?.let { preview ->
                                    publishGeneration(generation) { updateAssistantPreview(assistantId, preview) }
                                }
                            }
                            if (event is AgentStreamEvent.ReasoningDelta) {
                                reasoningAccumulator.append(event.text, SystemClock.uptimeMillis())?.let { reasoning ->
                                    publishGeneration(generation) {
                                        updateAssistantReasoningPreview(assistantId, reasoning)
                                    }
                                }
                            }
                            if (event is AgentStreamEvent.Usage) {
                                turnInputTokens += event.input ?: 0L
                                turnOutputTokens += event.output ?: 0L
                                val turnTotal = turnInputTokens + turnOutputTokens
                                updateGenerationState(generation) {
                                    it.copy(inputTokens = event.input, outputTokens = event.output, turnTokens = turnTotal, conversationTokens = conversation.tokenCount + turnTotal)
                                }
                            }
                        },
                        onAssistantToolTurn = { content, calls ->
                            updateGenerationState(generation) { current ->
                                current.copy(
                                    generationStatus = if (calls.any { it.name == "preview_face" }) {
                                        AgentGenerationStatus.PROCESSING_PREVIEW
                                    } else {
                                        AgentGenerationStatus.CALLING_TOOL
                                    },
                                )
                            }
                            updateAssistant(
                                generation = generation,
                                messageId = assistantId,
                                content = content,
                                status = AgentMessageStatus.COMPLETE,
                                reasoningContent = reasoningAccumulator.snapshot(),
                                toolCalls = calls,
                                thinkingDurationMs = elapsedThinkingMs(assistantThinkingStartedAt),
                            )
                            pendingToolCalls.recordCalls(calls)
                            streamAccumulator.reset()
                            reasoningAccumulator.reset()
                        },
                        onToolResults = { results ->
                            results.forEach { result ->
                                repository.appendMessage(
                                    conversation.id,
                                    AgentMessage(
                                        id = java.util.UUID.randomUUID().toString(),
                                        conversationId = conversation.id,
                                        role = AgentMessageRole.TOOL,
                                        content = loop.encodeToolResult(result),
                                        createdAt = System.currentTimeMillis(),
                                        status = if (result.succeeded) AgentMessageStatus.COMPLETE else AgentMessageStatus.FAILED,
                                        toolName = result.name,
                                        toolCallId = result.callId,
                                    ),
                                )
                                pendingToolCalls.recordResults(listOf(result))
                            }
                            // Reserve the exact position where the next provider
                            // turn will stream. It is appended only after all
                            // results, so no text can be reinserted above them.
                            assistantId = java.util.UUID.randomUUID().toString()
                            assistantThinkingStartedAt = SystemClock.uptimeMillis()
                            repository.appendMessage(
                                conversation.id,
                                AgentMessage(
                                    id = assistantId,
                                    conversationId = conversation.id,
                                    role = AgentMessageRole.ASSISTANT,
                                    content = "",
                                    createdAt = System.currentTimeMillis(),
                                    status = AgentMessageStatus.STREAMING,
                                ),
                            )
                            publishGeneration(generation) { refresh(conversation.id) }
                            updateGenerationState(generation) { it.copy(generationStatus = AgentGenerationStatus.THINKING) }
                        },
                        onBeforeNextTurn = {
                            var pending: String? = null
                            publishGeneration(generation) {
                                pending = _state.value.pendingFollowUp?.takeIf { it.isNotBlank() }
                                if (pending != null) {
                                    _state.update {
                                        it.copy(
                                            pendingFollowUp = null,
                                            generationStatus = AgentGenerationStatus.PROCESSING_FOLLOW_UP,
                                        )
                                    }
                                }
                            }
                            if (pending != null) {
                                val followUp = checkNotNull(pending)
                                repository.appendMessage(conversation.id, AgentMessage.user(conversation.id, followUp, System.currentTimeMillis()))
                                publishGeneration(generation) { refresh(conversation.id) }
                                AgentRequestMessage(AgentMessageRole.USER, followUp)
                            } else null
                        },
                    )
                    val finalText = completion.text.ifBlank {
                        streamAccumulator.snapshot()
                    }
                    val turnTotal = turnInputTokens + turnOutputTokens
                    updateGenerationState(generation) { it.copy(turnTokens = turnTotal, conversationTokens = conversation.tokenCount + turnTotal) }
                    updateAssistant(
                        generation,
                        assistantId,
                        finalText,
                        AgentMessageStatus.COMPLETE,
                        reasoningAccumulator.snapshot(),
                        thinkingDurationMs = elapsedThinkingMs(assistantThinkingStartedAt),
                        turnTokens = turnTotal,
                    )
                    commitUsage(conversation.id, turnTotal).also { usageCommitted = true }
                    repository.markGeneratedTitlePending(conversation.id)
                    generateTitle(conversation, profile, apiKey, prompt, finalText)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                persistCancelledToolResults(
                    generation = generation,
                    pending = pendingToolCalls,
                    revision = runCatching { generationHost.currentRevision }.getOrDefault(0L),
                    encoder = encodeToolResult,
                )
                val partial = streamAccumulator.snapshot()
                updateAssistant(
                    generation,
                    assistantId,
                    partial,
                    AgentMessageStatus.CANCELLED,
                    reasoningAccumulator.snapshot(),
                    thinkingDurationMs = elapsedThinkingMs(assistantThinkingStartedAt),
                    turnTokens = turnInputTokens + turnOutputTokens,
                )
                commitUsage(conversation.id, turnInputTokens + turnOutputTokens).also { usageCommitted = true }
                throw cancellation
            } catch (error: Throwable) {
                persistCancelledToolResults(
                    generation = generation,
                    pending = pendingToolCalls,
                    revision = runCatching { generationHost.currentRevision }.getOrDefault(0L),
                    encoder = encodeToolResult,
                )
                val partial = streamAccumulator.snapshot()
                val suffix = error.displayableMessage()
                updateAssistant(
                    generation,
                    assistantId,
                    listOf(partial, "Agent 请求失败：$suffix").filter { it.isNotBlank() }.joinToString("\n\n"),
                    AgentMessageStatus.FAILED,
                    reasoningAccumulator.snapshot(),
                    thinkingDurationMs = elapsedThinkingMs(assistantThinkingStartedAt),
                    turnTokens = turnInputTokens + turnOutputTokens,
                )
                commitUsage(conversation.id, turnInputTokens + turnOutputTokens).also { usageCommitted = true }
            } finally {
                if (!usageCommitted) {
                    commitUsage(conversation.id, turnInputTokens + turnOutputTokens)
                    usageCommitted = true
                }
                completeGeneration(generation) {
                    generationJob = null
                    refresh(conversation.id)
                    _conversations.value = repository.listConversations(generation.watchScope)
                    _state.update { it.copy(isGenerating = false) }
                    val pending = _state.value.pendingFollowUp
                    _state.update { it.copy(pendingFollowUp = null, generationStatus = null) }
                    if (!pending.isNullOrBlank() && entered) {
                        _state.update { it.copy(inputDraft = pending) }
                        send()
                    }
                }
            }
        }
        generationJob = job
        job.start()
    }

    fun stop() {
        invalidateGeneration()
        _state.update { it.copy(isGenerating = false, pendingFollowUp = null, generationStatus = null) }
    }

    /** Manually compacts the visible conversation without sending any tool schema. */
    fun compressContext() {
        if (_state.value.isGenerating) return
        val conversationId = _state.value.activeConversationId ?: return
        val conversation = repository.getConversation(conversationId) ?: return
        val scope = _state.value.watchScope
        _state.update { it.copy(isGenerating = true, generationStatus = AgentGenerationStatus.COMPRESSING) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = providerStore.active()
                val apiKey = profile?.let { providerStore.apiKey(it.id) }
                if (profile != null && !apiKey.isNullOrBlank()) {
                    compressConversation(conversation, profile, apiKey, repository.listMessages(conversation.id))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Keep the full conversation when a provider cannot perform a manual compaction.
            } finally {
                if (_state.value.watchScope == scope && _state.value.activeConversationId == conversation.id) {
                    withContext(Dispatchers.Main.immediate) {
                        refresh(conversation.id)
                        _conversations.value = repository.listConversations(scope)
                        _state.update { it.copy(isGenerating = false, generationStatus = null, conversationTokens = repository.getConversation(conversation.id)?.tokenCount ?: it.conversationTokens) }
                    }
                }
            }
        }
    }

    fun renameConversation(title: String) {
        val id = _state.value.activeConversationId ?: return
        repository.renameConversation(id, title)
        _conversations.value = repository.listConversations(_state.value.watchScope)
    }

    fun editMessage(messageId: String) {
        if (_state.value.isGenerating) return
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (message.role != AgentMessageRole.USER) return
        repository.deleteMessagesAfter(message.conversationId, messageId)
        _state.update { it.copy(inputDraft = message.content, attachments = message.attachments, editingMessageId = message.id, isGenerating = false) }
        refresh(message.conversationId)
    }

    fun retryMessage(messageId: String) {
        if (_state.value.isGenerating) return
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (message.role != AgentMessageRole.USER) return
        repository.deleteMessagesAfter(message.conversationId, messageId)
        refresh(message.conversationId)
        _state.update { it.copy(inputDraft = message.content, attachments = message.attachments, editingMessageId = message.id, isGenerating = false) }
        send()
    }

    private fun ensureConversation(): AgentConversation {
        val existingId = _state.value.activeConversationId
        if (existingId != null) return repository.getConversation(existingId)!!
        val conversation = repository.createConversation(_state.value.watchScope)
        _state.update { it.copy(activeConversationId = conversation.id) }
        _conversations.value = repository.listConversations(_state.value.watchScope)
        return conversation
    }

    private fun refresh(conversationId: String) { _messages.value = repository.listMessages(conversationId) }

    private fun commitUsage(conversationId: String, tokens: Long) {
        if (tokens > 0L) repository.addTokenUsage(conversationId, tokens)
    }

    private suspend fun compressConversation(
        conversation: AgentConversation,
        profile: com.flue.launcher.watchface.agent.model.AiProviderProfile,
        apiKey: String,
        messages: List<AgentMessage>,
    ): Boolean {
        val visible = messages.filter { it.role == AgentMessageRole.USER || it.role == AgentMessageRole.ASSISTANT }
            .filter { it.content.isNotBlank() }
        if (visible.size < 2) return false
        val generatedSummary = try {
            withContext(Dispatchers.IO) {
                providerClient.send(profile.copy(reasoningEffort = null), apiKey, AgentConversationCompressionPrompt.request(visible))
                    .text.trim()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return false
        }
        val summary = generatedSummary.takeIf { it.isNotBlank() } ?: return false
        val keep = visible.takeLast(6)
        val summaryMessage = AgentMessage(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = conversation.id,
            role = AgentMessageRole.ASSISTANT,
            content = "上下文摘要：$summary",
            createdAt = System.currentTimeMillis(),
            status = AgentMessageStatus.COMPLETE,
        )
        repository.replaceMessages(conversation.id, listOf(summaryMessage) + keep)
        return true
    }

    private fun estimateMessageTokens(messages: List<AgentMessage>): Long =
        messages.sumOf { estimateTextTokens(it.content) + it.attachments.sumOf { attachment -> estimateTextTokens(attachment.displayName) } }

    private fun estimateTextTokens(text: String): Long =
        kotlin.math.ceil(text.length / 4.0).toLong().coerceAtLeast(0L)

    private fun updateAssistantPreview(messageId: String, content: String) {
        _messages.update { messages ->
            messages.map { message ->
                if (message.id == messageId) message.copy(content = content, status = AgentMessageStatus.STREAMING)
                else message
            }
        }
    }

    private fun updateAssistantReasoningPreview(messageId: String, reasoning: String) {
        _messages.update { messages ->
            messages.map { message ->
                if (message.id == messageId) message.copy(reasoningContent = reasoning, status = AgentMessageStatus.STREAMING)
                else message
            }
        }
    }

    private fun updateAssistant(
        generation: AgentGenerationScope,
        messageId: String,
        content: String,
        status: AgentMessageStatus,
        reasoningContent: String? = null,
        toolCalls: List<AgentToolCallRecord> = emptyList(),
        thinkingDurationMs: Long? = null,
        turnTokens: Long? = null,
    ) {
        val existing = repository.listMessages(generation.conversationId).firstOrNull { it.id == messageId }
        repository.updateMessage(
            AgentMessage(
                id = messageId,
                conversationId = generation.conversationId,
                role = AgentMessageRole.ASSISTANT,
                content = content,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                status = status,
                reasoningContent = reasoningContent?.takeIf(String::isNotBlank),
                toolCalls = toolCalls,
                thinkingDurationMs = thinkingDurationMs ?: existing?.thinkingDurationMs,
                turnTokens = turnTokens ?: existing?.turnTokens,
            ),
        )
        publishGeneration(generation) { refresh(generation.conversationId) }
    }

    private fun elapsedThinkingMs(startedAt: Long): Long =
        (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)

    private fun persistCancelledToolResults(
        generation: AgentGenerationScope,
        pending: AgentPendingToolCallLedger,
        revision: Long,
        encoder: ((WatchAgentToolResult) -> String)?,
    ) {
        val encode = encoder ?: return
        pending.drainCancelled(revision).forEach { result ->
            repository.appendMessage(
                generation.conversationId,
                AgentMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    conversationId = generation.conversationId,
                    role = AgentMessageRole.TOOL,
                    content = encode(result),
                    createdAt = System.currentTimeMillis(),
                    status = AgentMessageStatus.CANCELLED,
                    toolName = result.name,
                    toolCallId = result.callId,
                ),
            )
        }
    }

    private fun publishGeneration(generation: AgentGenerationScope, publish: () -> Unit): Boolean {
        val current = _state.value
        return generationGate.publishIfCurrent(
            generation = generation,
            activeConversationId = current.activeConversationId,
            activeWatchScope = current.watchScope,
            publish = publish,
        )
    }

    private fun completeGeneration(generation: AgentGenerationScope, publish: () -> Unit): Boolean {
        val current = _state.value
        return generationGate.completeIfCurrent(
            generation = generation,
            activeConversationId = current.activeConversationId,
            activeWatchScope = current.watchScope,
            publish = publish,
        )
    }

    private fun updateGenerationState(
        generation: AgentGenerationScope,
        transform: (WatchAgentUiState) -> WatchAgentUiState,
    ) {
        publishGeneration(generation) { _state.update(transform) }
    }

    private fun invalidateGeneration() {
        generationGate.invalidate()
        generationJob?.cancel()
        generationJob = null
    }

    companion object {
        private const val MAX_DRAFT_LENGTH = 16_000
        private const val DEFAULT_PROFILE_ID = "default"
    }
}

private fun Throwable.displayableMessage(): String {
    val root = generateSequence(this) { it.cause }.lastOrNull() ?: this
    return when {
        this is ExceptionInInitializerError || root is ExceptionInInitializerError ->
            "初始化失败，当前会话和已完成工具结果已保留。请重试；若持续出现，请检查预览渲染器。"
        !message.isNullOrBlank() -> message.orEmpty().take(500)
        !root.message.isNullOrBlank() -> root.message.orEmpty().take(500)
        else -> root.javaClass.simpleName
    }
}

private fun formatByteCountForPrompt(value: Long): String = when {
    value < 1_000L -> "$value B"
    value < 1_000_000L -> "${"%.1f".format(java.util.Locale.US, value / 1_000.0).trimEnd('0').trimEnd('.')} KB"
    value < 1_000_000_000L -> "${"%.1f".format(java.util.Locale.US, value / 1_000_000.0).trimEnd('0').trimEnd('.')} MB"
    else -> "${"%.1f".format(java.util.Locale.US, value / 1_000_000_000.0).trimEnd('0').trimEnd('.')} GB"
}

/** A generation without an editor binding can still show a useful configuration error. */
private object EmptyWatchAgentToolHost : WatchAgentToolHost {
    override val currentRevision: Long = 0L
    override fun inspectFace(request: com.flue.launcher.watchface.agent.tools.WatchAgentInspectFaceRequest) = error("当前 Agent 未绑定表盘")
    override fun readDocument(request: com.flue.launcher.watchface.agent.tools.WatchAgentReadDocumentRequest) = error("当前 Agent 未绑定表盘")
    override fun searchKnowledge(request: com.flue.launcher.watchface.agent.tools.WatchAgentSearchKnowledgeRequest) = com.flue.launcher.watchface.agent.tools.WatchAgentKnowledgeResult(request.query, emptyList())
    override fun validateFace(request: com.flue.launcher.watchface.agent.tools.WatchAgentValidateFaceRequest) = error("当前 Agent 未绑定表盘")
    override fun previewFace(request: com.flue.launcher.watchface.agent.tools.WatchAgentPreviewRequest) = error("当前 Agent 未绑定表盘")
    override fun editDocument(request: com.flue.launcher.watchface.agent.tools.WatchAgentEditDocumentRequest) = error("当前 Agent 未绑定表盘")
    override fun writeDocument(request: com.flue.launcher.watchface.agent.tools.WatchAgentWriteDocumentRequest) = error("当前 Agent 未绑定表盘")
}
