package com.codingagent.mobile.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codingagent.mobile.bridge.OpencodeBridge

/**
 * Model picker for the OpenCode backend: live provider/model list from
 * `GET /provider`, reasoning-capable models badged, server default applied
 * on select. Falls back to a clear error state when the server is down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    providers: List<OpencodeBridge.OpencodeProvider>,
    selectedModelId: String?,
    serverReady: Boolean,
    onRefresh: () -> Unit,
    onSelect: (providerID: String, modelID: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("OpenCode model", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onRefresh) { Text("Refresh") }
            }
            if (!serverReady) {
                Text(
                    "Server not running. Install the runtime + OpenCode CLI in Settings first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            LazyColumn(modifier = Modifier.padding(vertical = 8.dp)) {
                providers.forEach { provider ->
                    if (provider.models.isEmpty()) return@forEach
                    item(key = "h-" + provider.id) {
                        Text(
                            provider.name + if (provider.connected) " · connected" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(provider.models, key = { it.id }) { model ->
                        ListItem(
                            headlineContent = { Text(model.name) },
                            supportingContent = {
                                Text(
                                    model.id +
                                        if (model.status != "active") " · ${model.status}" else ""
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (model.reasoning) {
                                        FilterChip(
                                            selected = false,
                                            onClick = {},
                                            label = { Text("Reasoning") }
                                        )
                                    }
                                    if (model.id == selectedModelId) {
                                        Text(
                                            " ✓",
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(model.providerID, model.modelID)
                                }
                        )
                    }
                }
            }
        }
    }
}
