package com.codingagent.mobile.agents

import com.codingagent.mobile.bridge.IntelligenceBridge
import com.codingagent.mobile.bridge.OpencodeBridge
import com.codingagent.mobile.data.SessionStore
import com.codingagent.mobile.domain.AgentKind
import com.codingagent.mobile.domain.AgentSession
import com.codingagent.mobile.domain.ApprovalRequest
import com.codingagent.mobile.domain.ChatMessage
import com.codingagent.mobile.domain.ProviderConfig
import com.codingagent.mobile.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level agent orchestration.
 * Talks to IntelligenceBridge and (later) native agent CLIs.
 * Provider/agent/history survive process death via SessionStore.
 */
@Singleton
class AgentRepository @Inject constructor(
    private val bridge: IntelligenceBridge,
    private val opencode: OpencodeBridge,
    private val runtimeManager: RuntimeManager,
    private val sessionStore: SessionStore
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<ApprovalRequest>> = _pendingApprovals.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _selectedAgent = MutableStateFlow(AgentKind.INTELLIGENCE)
    val selectedAgent: StateFlow<AgentKind> = _selectedAgent.asStateFlow()

    private val _cliStatus = MutableStateFlow<String?>(null)
    val cliStatus: StateFlow<String?> = _cliStatus.asStateFlow()

    private var currentSession: AgentSession = AgentSession(
        id = UUID.randomUUID().toString(),
        kind = AgentKind.INTELLIGENCE,
        title = "New session",
        model = "qwen2.5-coder:7b"
    )

    private var providerConfig: ProviderConfig = ProviderConfig(
        type = "ollama",
        baseUrl = "http://127.0.0.1:11434",
        defaultModel = "qwen2.5-coder:7b"
    )

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Extra agent-visible roots from the SAF picker (primary volume only,
     * resolved to real paths). Always a subset of the Node sandbox
     * allowlist; content:// trees that don't resolve stay browse-only.
     */
    data class ExternalRoot(val label: String, val hostPath: String, val guestPath: String)

    private val _externalRoots = MutableStateFlow<List<ExternalRoot>>(emptyList())
    val externalRoots: StateFlow<List<ExternalRoot>> = _externalRoots.asStateFlow()

    fun setExternalRoot(label: String, hostPath: String) {
        runtimeManager.setExtraBinds(listOf(hostPath))
        val guest = runtimeManager.guestPathFor(hostPath)
        _externalRoots.value = listOf(ExternalRoot(label, hostPath, guest))
        Timber.i("External root: %s -> %s", hostPath, guest)
    }

    fun clearExternalRoots() {
        _externalRoots.value = emptyList()
        runtimeManager.setExtraBinds(emptyList())
    }

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repoScope.launch {
            runCatching {
                sessionStore.loadProvider()?.let { providerConfig = it }
                sessionStore.loadAgent()?.let { kind ->
                    _selectedAgent.value = kind
                    currentSession = currentSession.copy(kind = kind, model = defaultModelFor(kind))
                }
                val history = sessionStore.loadHistory()
                if (history.isNotEmpty()) _messages.value = history
                sessionStore.loadOpencodeModel()?.let { _opencodeModel.value = it }
                sessionStore.loadOpencodeAgent()?.let { _opencodeAgent.value = it }
            }.onFailure { Timber.w(it, "session restore failed") }
        }
    }

    fun setProvider(config: ProviderConfig) {
        providerConfig = config
        _lastError.value = null
        repoScope.launch { runCatching { sessionStore.saveProvider(config) } }
    }

    fun getProvider(): ProviderConfig = providerConfig

    fun currentModel(): String = providerConfig.defaultModel ?: currentSession.model

    /**
     * Switch the active agent. The intelligence layer handles chat for every
     * agent today; CLI kinds only change the session label + default model
     * until their native loop is wired (Phase 4).
     */
    fun selectAgent(kind: AgentKind) {
        _selectedAgent.value = kind
        currentSession = currentSession.copy(
            kind = kind,
            model = defaultModelFor(kind)
        )
        repoScope.launch { runCatching { sessionStore.saveAgent(kind) } }
    }

    suspend fun installCli(): String {
        _cliStatus.value = "Installing OpenCode CLI…"
        val result = runtimeManager.installAgentCli()
        val msg = result.getOrElse { "Install failed: ${it.message}" }
        _cliStatus.value = msg
        return msg
    }

    suspend fun installQwenCli(): String {
        _cliStatus.value = "Installing Qwen Code CLI (npm)…"
        val result = runtimeManager.installQwenCli()
        val msg = result.getOrElse { "Install failed: ${it.message}" }
        _cliStatus.value = msg
        return msg
    }

    private fun defaultModelFor(kind: AgentKind): String = when (kind) {
        AgentKind.INTELLIGENCE -> providerConfig.defaultModel ?: "qwen2.5-coder:7b"
        AgentKind.OPENCODE -> "opencode/qwen-2.5-coder"
        AgentKind.QWEN_CODE -> "qwen-coder-plus"
        AgentKind.CLAUDE_CODE -> "claude-sonnet-4-5"
        AgentKind.CODEX -> "gpt-5-codex"
    }

    suspend fun sendMessage(text: String) {
        if (text.isBlank() || _isBusy.value) return

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.USER,
            content = text.trim()
        )
        _messages.update { it + userMsg }
        _isBusy.value = true

        val placeholderId = UUID.randomUUID().toString()
        _messages.update {
            it + ChatMessage(
                id = placeholderId,
                role = ChatMessage.Role.ASSISTANT,
                content = "",
                isStreaming = true
            )
        }

        if (_selectedAgent.value == AgentKind.OPENCODE) {
            sendOpencodeMessage(text.trim(), placeholderId)
            return
        }
        try {
            val allowedDirs = buildList {
                add(runtimeManager.getWorkspacePath())
                // External SAF roots resolve to guest bind paths; the guest
                // Node re-resolves them against its own sandbox. Host paths
                // that never mapped stay out.
                for (root in _externalRoots.value) {
                    if (root.guestPath != root.hostPath) add(root.guestPath)
                }
            }
            val result = bridge.chat(
                message = text.trim(),
                history = _messages.value.filter { it.id != placeholderId },
                sessionId = currentSession.id,
                allowedDirectories = allowedDirs
            )

            _messages.update { list ->
                list.map { msg ->
                    if (msg.id == placeholderId) {
                        msg.copy(
                            content = result.finalResponse.ifBlank { "(no response)" },
                            isStreaming = false,
                            pendingApprovals = result.approvals
                        )
                    } else msg
                }
            }
            if (result.approvals.isNotEmpty()) {
                _pendingApprovals.value = result.approvals
            }
            _lastError.value = null
        } catch (e: Exception) {
            Timber.e(e, "sendMessage failed")
            _lastError.value = e.message ?: "Request failed"
            _messages.update { list ->
                list.map { msg ->
                    if (msg.id == placeholderId) {
                        msg.copy(
                            content = "Error: ${e.message}",
                            isStreaming = false
                        )
                    } else msg
                }
            }
        } finally {
            _isBusy.value = false
            persistHistory()
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    private fun persistHistory() {
        val snapshot = _messages.value
        repoScope.launch { runCatching { sessionStore.saveHistory(snapshot) } }
    }

    // ---------------- OpenCode native backend ----------------

    private var opencodeSessionId: String? = null
    private var opencodeStream: Closeable? = null
    private val pendingOpencodePermissions = ConcurrentHashMap.newKeySet<String>()

    private val _opencodeProviders = MutableStateFlow<List<OpencodeBridge.OpencodeProvider>>(emptyList())
    val opencodeProviders: StateFlow<List<OpencodeBridge.OpencodeProvider>> = _opencodeProviders.asStateFlow()

    private val _opencodeModel = MutableStateFlow<Pair<String, String>?>(null)
    val opencodeModel: StateFlow<Pair<String, String>?> = _opencodeModel.asStateFlow()

    private val _opencodeAgent = MutableStateFlow("build")
    val opencodeAgent: StateFlow<String> = _opencodeAgent.asStateFlow()

    private val _opencodeReady = MutableStateFlow(false)
    val opencodeReady: StateFlow<Boolean> = _opencodeReady.asStateFlow()

    fun selectOpencodeAgent(agent: String) {
        if (agent != "build" && agent != "plan") return
        _opencodeAgent.value = agent
        repoScope.launch { runCatching { sessionStore.saveOpencodeAgent(agent) } }
    }

    suspend fun refreshOpencodeModels(): List<OpencodeBridge.OpencodeProvider> {
        if (!ensureOpencodeServer()) return emptyList()
        val list = runCatching { opencode.providers() }.getOrDefault(emptyList())
        _opencodeProviders.value = list
        return list
    }

    fun setOpencodeModel(providerID: String, modelID: String) {
        _opencodeModel.value = providerID to modelID
        repoScope.launch {
            runCatching {
                opencode.setDefaultModel(providerID, modelID)
                sessionStore.saveOpencodeModel(providerID, modelID)
            }
        }
    }

    suspend fun ensureOpencodeServer(): Boolean {
        if (opencodeReady()) return true
        val res = runtimeManager.ensureOpencodeServer()
        if (res.isFailure) {
            _lastError.value = res.exceptionOrNull()?.message ?: "opencode server failed to start"
            return false
        }
        repeat(12) {
            if (runCatching { opencode.health() }.getOrDefault(false)) {
                _opencodeReady.value = true
                return true
            }
            kotlinx.coroutines.delay(2000)
        }
        _lastError.value = "opencode server did not respond on 127.0.0.1:4097"
        return false
    }

    private suspend fun opencodeReady(): Boolean {
        if (_opencodeReady.value && runCatching { opencode.health() }.getOrDefault(false)) return true
        _opencodeReady.value = false
        return false
    }

    private data class OpencodeRun(
        val done: CompletableDeferred<Boolean> = CompletableDeferred(),
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        val tools: ConcurrentHashMap<String, com.codingagent.mobile.domain.ToolCallUi> = ConcurrentHashMap(),
        val failed: AtomicReference<String?> = AtomicReference(null),
        val finished: AtomicBoolean = AtomicBoolean(false)
    )

    @Volatile
    private var activeRun: OpencodeRun? = null

    private fun ensureOpencodeStream() {
        if (opencodeStream != null) return
        opencodeStream = opencode.openEvents { ev -> onOpencodeEvent(ev) }
    }

    private fun onOpencodeEvent(ev: OpencodeBridge.OpEvent) {
        val sid = opencodeSessionId
        val run = activeRun
        if (ev is OpencodeBridge.OpEvent.StreamError) {
            run?.failed?.compareAndSet(null, ev.message)
            run?.done?.complete(false)
            return
        }
        if (sid == null || run == null) return
        when (ev) {
            is OpencodeBridge.OpEvent.Text -> {
                if (ev.sessionId != sid) return
                synchronized(run.text) { run.text.append(ev.delta) }
                updateOpencodePlaceholder(run)
            }
            is OpencodeBridge.OpEvent.Reasoning -> {
                if (ev.sessionId != sid) return
                synchronized(run.thinking) { run.thinking.append(ev.delta) }
                updateOpencodePlaceholder(run)
            }
            is OpencodeBridge.OpEvent.Tool -> {
                if (ev.sessionId != sid) return
                val status = when (ev.status) {
                    "completed" -> com.codingagent.mobile.domain.ToolCallUi.ToolStatus.SUCCESS
                    "error" -> com.codingagent.mobile.domain.ToolCallUi.ToolStatus.ERROR
                    else -> com.codingagent.mobile.domain.ToolCallUi.ToolStatus.RUNNING
                }
                run.tools[ev.partId] = com.codingagent.mobile.domain.ToolCallUi(
                    id = ev.partId,
                    name = ev.toolName,
                    arguments = ev.title,
                    result = ev.output?.take(500),
                    status = status
                )
                updateOpencodePlaceholder(run)
            }
            is OpencodeBridge.OpEvent.Permission -> {
                if (ev.sessionId != sid) return
                if (pendingOpencodePermissions.add(ev.permissionId)) {
                    _pendingApprovals.update { list ->
                        list + ApprovalRequest(
                            id = ev.permissionId,
                            toolName = ev.toolName.ifBlank { "opencode-tool" },
                            argsJson = ev.metadata,
                            description = ev.title.ifBlank { "OpenCode requests permission" },
                            diff = null,
                            riskLevel = com.codingagent.mobile.domain.RiskLevel.HIGH
                        )
                    }
                }
            }
            is OpencodeBridge.OpEvent.Idle, is OpencodeBridge.OpEvent.Status -> {
                val id = when (ev) {
                    is OpencodeBridge.OpEvent.Idle -> ev.sessionId
                    is OpencodeBridge.OpEvent.Status -> ev.sessionId
                    else -> return
                }
                if (id != sid) return
                val idleStatus = ev is OpencodeBridge.OpEvent.Idle ||
                    (ev as OpencodeBridge.OpEvent.Status).status == "idle"
                if (idleStatus && pendingOpencodePermissions.isEmpty()) {
                    if (run.finished.compareAndSet(false, true)) run.done.complete(true)
                }
            }
            is OpencodeBridge.OpEvent.RunError -> {
                if (ev.sessionId != sid && ev.sessionId.isNotBlank()) return
                run.failed.compareAndSet(null, ev.message)
                if (run.finished.compareAndSet(false, true)) run.done.complete(false)
            }
            is OpencodeBridge.OpEvent.StreamError -> Unit // handled above
        }
    }

    private fun updateOpencodePlaceholder(run: OpencodeRun) {
        val text = synchronized(run.text) { run.text.toString() }
        val thinking = synchronized(run.thinking) { run.thinking.toString() }
        val tools = run.tools.values.sortedBy { it.id }
        val current = _messages.value
        val placeholder = current.lastOrNull { it.isStreaming } ?: return
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == placeholder.id) {
                    msg.copy(content = text, thinking = thinking, toolCalls = tools)
                } else msg
            }
        }
    }

    private suspend fun sendOpencodeMessage(text: String, placeholderId: String) {
        try {
            if (!ensureOpencodeServer()) {
                failPlaceholder(placeholderId, _lastError.value ?: "opencode server unavailable")
                _isBusy.value = false
                return
            }
            ensureOpencodeStream()
            var sid = opencodeSessionId
            if (sid == null) {
                sid = opencode.createSession(text)
                opencodeSessionId = sid
            }
            val run = OpencodeRun()
            activeRun = run
            val model = _opencodeModel.value
            try {
                opencode.promptAsync(
                    sessionId = sid,
                    text = text,
                    providerID = model?.first,
                    modelID = model?.second,
                    agent = _opencodeAgent.value
                )
            } catch (e: Exception) {
                failPlaceholder(placeholderId, e.message ?: "send failed")
                activeRun = null
                _isBusy.value = false
                return
            }
            val ok = withTimeoutOrNull(15 * 60 * 1000L) { run.done.await() } == true &&
                run.failed.get() == null
            activeRun = null
            if (!ok) {
                val err = run.failed.get() ?: "OpenCode run timed out"
                _lastError.value = err
                failPlaceholder(placeholderId, "Error: $err")
            } else {
                val finalText = synchronized(run.text) { run.text.toString() }
                    .ifBlank { "Done." }
                val finalThinking = synchronized(run.thinking) { run.thinking.toString() }
                val finalTools = run.tools.values.sortedBy { it.id }
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.id == placeholderId) {
                            msg.copy(
                                content = finalText,
                                thinking = finalThinking,
                                toolCalls = finalTools,
                                isStreaming = false
                            )
                        } else msg
                    }
                }
                _lastError.value = null
            }
            persistHistory()
        } catch (e: Exception) {
            Timber.e(e, "opencode send failed")
            _lastError.value = e.message ?: "OpenCode request failed"
            failPlaceholder(placeholderId, "Error: ${e.message}")
            activeRun = null
        } finally {
            _isBusy.value = false
        }
    }

    private fun failPlaceholder(placeholderId: String, content: String) {
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == placeholderId) {
                    msg.copy(content = content, isStreaming = false)
                } else msg
            }
        }
    }

    suspend fun abort() {
        runCatching { bridge.abort(currentSession.id) }
        opencodeSessionId?.let { sid -> runCatching { opencode.abortSession(sid) } }
        activeRun?.let { run ->
            if (run.finished.compareAndSet(false, true)) {
                run.failed.compareAndSet(null, "cancelled")
                run.done.complete(false)
            }
        }
        activeRun = null
        _isBusy.value = false
        _messages.update { list ->
            list.map { if (it.isStreaming) it.copy(isStreaming = false, content = it.content.ifBlank { "(cancelled)" }) else it }
        }
    }

    fun clearApprovals() {
        _pendingApprovals.value = emptyList()
    }

    suspend fun approve(request: ApprovalRequest) {
        if (pendingOpencodePermissions.remove(request.id)) {
            val sid = opencodeSessionId
            val ok = if (sid != null) {
                runCatching { opencode.replyPermission(sid, request.id, true) }.getOrDefault(false)
            } else false
            if (!ok) {
                _lastError.value = "Permission reply failed"
                appendToolResult(request, "Error: permission reply failed")
            }
            _pendingApprovals.update { list -> list.filterNot { it.id == request.id } }
            persistHistory()
            return
        }
        try {
            val result = bridge.approveTool(currentSession.id, request.id, true)
            appendToolResult(request, result.ifBlank { "Approved and executed." })
        } catch (e: Exception) {
            Timber.e(e, "approve failed")
            appendToolResult(request, "Error: ${e.message}")
        } finally {
            _pendingApprovals.update { list -> list.filterNot { it.id == request.id } }
        }
    }

    suspend fun deny(request: ApprovalRequest) {
        if (pendingOpencodePermissions.remove(request.id)) {
            val sid = opencodeSessionId
            if (sid != null) runCatching { opencode.replyPermission(sid, request.id, false) }
            appendToolResult(request, "Denied by user — tool was not executed.")
            _pendingApprovals.update { list -> list.filterNot { it.id == request.id } }
            persistHistory()
            return
        }
        try {
            runCatching { bridge.approveTool(currentSession.id, request.id, false) }
            appendToolResult(request, "Denied by user — tool was not executed.")
        } finally {
            _pendingApprovals.update { list -> list.filterNot { it.id == request.id } }
        }
    }

    private fun appendToolResult(request: ApprovalRequest, result: String) {
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.TOOL,
            content = "${request.toolName}: $result"
        )
        _messages.update { it + msg }
        persistHistory()
    }

    fun clearChat() {
        _messages.value = emptyList()
        _pendingApprovals.value = emptyList()
        _lastError.value = null
        pendingOpencodePermissions.clear()
        opencodeSessionId = null
        activeRun = null
        currentSession = currentSession.copy(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis()
        )
        repoScope.launch { runCatching { sessionStore.clearHistory() } }
    }
}
