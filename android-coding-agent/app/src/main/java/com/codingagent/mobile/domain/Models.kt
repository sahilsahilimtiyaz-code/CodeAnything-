package com.codingagent.mobile.domain

/**
 * Core domain models shared across UI, runtime and bridge.
 */

enum class RuntimeState {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLING,
    READY,
    STARTING,
    RUNNING,
    ERROR,
    STOPPED
}

enum class AgentKind {
    OPENCODE,
    QWEN_CODE,
    CLAUDE_CODE,
    CODEX,
    INTELLIGENCE  // extracted-intelligence Node service
}

enum class RiskLevel {
    LOW, MEDIUM, HIGH
}

enum class ApprovalPolicy {
    ALWAYS_ASK,
    ASK_DANGEROUS,
    FULL_ACCESS
}

data class RuntimeStatus(
    val state: RuntimeState = RuntimeState.NOT_INSTALLED,
    val message: String = "",
    val progress: Float = 0f,
    val alpineVersion: String? = null,
    val error: String? = null
)

data class AgentSession(
    val id: String,
    val kind: AgentKind,
    val title: String,
    val model: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCalls: List<ToolCallUi> = emptyList(),
    val pendingApprovals: List<ApprovalRequest> = emptyList(),
    val thinking: String = "",
    val isStreaming: Boolean = false
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL }
}

data class ToolCallUi(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val status: ToolStatus = ToolStatus.RUNNING
) {
    enum class ToolStatus { RUNNING, SUCCESS, ERROR, DENIED }
}

data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val argsJson: String,
    val description: String,
    val diff: String? = null,
    val riskLevel: RiskLevel
)

data class Workspace(
    val id: String,
    val name: String,
    val rootPath: String,
    val isSaf: Boolean = false,
    val lastOpened: Long = System.currentTimeMillis()
)

data class ProviderConfig(
    val type: String,           // ollama | openrouter | generic-openai | anthropic | ...
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val defaultModel: String? = null,
    val extraHeaders: Map<String, String> = emptyMap()
)

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val modifiedAt: String? = null
)
