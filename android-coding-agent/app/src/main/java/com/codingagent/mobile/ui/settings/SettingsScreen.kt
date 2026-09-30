package com.codingagent.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.codingagent.mobile.domain.AgentKind
import com.codingagent.mobile.domain.RuntimeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val runtime by viewModel.runtimeStatus.collectAsState()
    val agent by viewModel.selectedAgent.collectAsState()
    val cliStatus by viewModel.cliStatus.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(ProviderPresets.OLLAMA_LOCAL) }
    var baseUrl by remember { mutableStateOf(ProviderPresets.OLLAMA_LOCAL.baseUrl) }
    var model by remember { mutableStateOf(ProviderPresets.OLLAMA_LOCAL.model) }
    var apiKey by remember { mutableStateOf("") }
    var presetMenu by remember { mutableStateOf(false) }
    var prefilled by remember { mutableStateOf(false) }

    if (!prefilled) {
        LaunchedEffect(Unit) {
            val saved = viewModel.savedProvider()
            preset = ProviderPresets.ALL.firstOrNull { it.type == saved.type }
                ?: ProviderPresets.CUSTOM
            baseUrl = saved.baseUrl ?: preset.baseUrl
            model = saved.defaultModel ?: preset.model
            apiKey = saved.apiKey ?: ""
            prefilled = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("On-device runtime", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("State: ${runtime.state.name}")
                    Text(runtime.message, style = MaterialTheme.typography.bodyMedium)
                    runtime.error?.let {
                        Text(
                            "Error: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    runtime.alpineVersion?.let {
                        Text("Alpine: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    if (runtime.state == RuntimeState.DOWNLOADING ||
                        runtime.state == RuntimeState.INSTALLING
                    ) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { runtime.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.setupRuntime() },
                        enabled = runtime.state != RuntimeState.DOWNLOADING &&
                            runtime.state != RuntimeState.INSTALLING
                    ) {
                        Text(
                            when (runtime.state) {
                                RuntimeState.NOT_INSTALLED -> "Install runtime"
                                RuntimeState.READY, RuntimeState.STOPPED -> "Start runtime"
                                RuntimeState.RUNNING -> "Runtime running"
                                RuntimeState.ERROR -> "Retry install"
                                else -> "Working…"
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(viewModel.diagnostics()))
                        copied = true
                    }) {
                        Text(if (copied) "Diagnostics copied" else "Copy diagnostics")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Coding agent", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Chat runs through the on-device intelligence layer for every agent. " +
                            "CLI kinds only change the session label until their native loop lands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    AgentKind.entries.forEach { kind ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = agent == kind,
                                onClick = { viewModel.selectAgent(kind) }
                            )
                            Text(kind.name.lowercase().replace('_', ' '))
                        }
                    }
                    if (agent == AgentKind.OPENCODE) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.installCli() },
                            enabled = runtime.state == RuntimeState.READY ||
                                runtime.state == RuntimeState.RUNNING,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Install OpenCode CLI in Alpine")
                        }
                        cliStatus?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (agent == AgentKind.QWEN_CODE) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.installQwenCli() },
                            enabled = runtime.state == RuntimeState.READY ||
                                runtime.state == RuntimeState.RUNNING,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Install Qwen Code CLI in Alpine (npm)")
                        }
                        cliStatus?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Model provider", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Box {
                        OutlinedButton(onClick = { presetMenu = true }) {
                            Text("Preset: ${preset.label}")
                        }
                        DropdownMenu(
                            expanded = presetMenu,
                            onDismissRequest = { presetMenu = false }
                        ) {
                            ProviderPresets.ALL.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.label) },
                                    onClick = {
                                        preset = p
                                        baseUrl = p.baseUrl
                                        model = p.model
                                        presetMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API key (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            viewModel.saveProvider(
                                preset.type,
                                baseUrl,
                                model,
                                apiKey.ifBlank { null }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save provider")
                    }
                }
            }

            Text(
                "Tip: Use Ollama on this device or LAN, OpenRouter, or any OpenAI-compatible endpoint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
