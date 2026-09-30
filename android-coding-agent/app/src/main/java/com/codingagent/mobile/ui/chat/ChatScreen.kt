package com.codingagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.codingagent.mobile.domain.AgentKind
import com.codingagent.mobile.domain.ChatMessage
import com.codingagent.mobile.ui.approvals.ApprovalBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val approvals by viewModel.pendingApprovals.collectAsState()
    val agent by viewModel.selectedAgent.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val opencodeModel by viewModel.opencodeModel.collectAsState()
    val opencodeMode by viewModel.opencodeAgent.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var agentMenu by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // Real agent state: working (streaming/tools/waits) or paused on approvals.
    val glowActive = isBusy || approvals.isNotEmpty()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(lastError) {
        val err = lastError
        if (err != null) {
            snackbar.showSnackbar("Error: $err")
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable(
                            enabled = agent == AgentKind.OPENCODE,
                            onClick = { modelSheet = true }
                        )
                    ) {
                        Text("Coding Agent", style = MaterialTheme.typography.titleLarge)
                        Text(
                            viewModel.modelLabel +
                                (opencodeModel?.let { " · ${it.second}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (agent == AgentKind.OPENCODE) {
                        FilterChip(
                            selected = opencodeMode == "plan",
                            onClick = {
                                viewModel.selectOpencodeAgent(
                                    if (opencodeMode == "plan") "build" else "plan"
                                )
                            },
                            label = { Text(if (opencodeMode == "plan") "Think" else "Build") }
                        )
                    }
                    Box {
                        FilterChip(
                            selected = true,
                            onClick = { agentMenu = true },
                            label = { Text(agentLabel(agent)) }
                        )
                        DropdownMenu(
                            expanded = agentMenu,
                            onDismissRequest = { agentMenu = false }
                        ) {
                            AgentKind.entries.forEach { kind ->
                                DropdownMenuItem(
                                    text = { Text(agentLabel(kind)) },
                                    onClick = {
                                        viewModel.selectAgent(kind)
                                        agentMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (isBusy) {
                        IconButton(onClick = { viewModel.abort() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (approvals.isNotEmpty()) {
                ApprovalBanner(
                    approvals = approvals,
                    onAllow = { viewModel.allowApproval(it) },
                    onDeny = { viewModel.denyApproval(it) }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .composerGlow(active = glowActive),
                    placeholder = { Text("Ask the agent…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp),
                    colors = if (glowActive) {
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            errorBorderColor = Color.Transparent
                        )
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    }
                )
                FilledIconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            viewModel.send(text)
                            input = ""
                        }
                    },
                    enabled = !isBusy && input.isNotBlank(),
                    modifier = Modifier.size(48.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }

    if (modelSheet) {
        val providers by viewModel.opencodeProviders.collectAsState()
        val ready by viewModel.opencodeReady.collectAsState()
        LaunchedEffect(Unit) { viewModel.refreshOpencodeModels() }
        ModelSheet(
            providers = providers,
            selectedModelId = opencodeModel?.let { "${it.first}/${it.second}" },
            serverReady = ready,
            onRefresh = { viewModel.refreshOpencodeModels() },
            onSelect = { pid, mid ->
                viewModel.setOpencodeModel(pid, mid)
                modelSheet = false
            },
            onDismiss = { modelSheet = false }
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    if (message.isStreaming && message.role != ChatMessage.Role.USER) {
        ThinkingBubble(
            thinking = message.thinking,
            startedAt = message.timestamp,
            toolCount = message.toolCalls.size
        )
        return
    }
    val isUser = message.role == ChatMessage.Role.USER
    val bg = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val bubbleShape = if (isUser) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(bubbleShape)
                .background(bg)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    bubbleShape
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!isUser) {
                Text(
                    "Agent",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = message.content.ifBlank { "" },
                style = MaterialTheme.typography.bodyLarge
            )
            ToolTimeline(tools = message.toolCalls)
            Text(
                text = java.text.SimpleDateFormat(
                    "HH:mm",
                    java.util.Locale.getDefault()
                ).format(java.util.Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}

private fun agentLabel(kind: AgentKind): String = when (kind) {
    AgentKind.INTELLIGENCE -> "Intelligence"
    AgentKind.OPENCODE -> "OpenCode"
    AgentKind.QWEN_CODE -> "Qwen Code"
    AgentKind.CLAUDE_CODE -> "Claude Code"
    AgentKind.CODEX -> "Codex"
}
