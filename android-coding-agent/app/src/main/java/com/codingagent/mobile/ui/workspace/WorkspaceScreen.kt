package com.codingagent.mobile.ui.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    val path by viewModel.currentPath.collectAsState()
    val isSaf by viewModel.isSaf.collectAsState()
    val safPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.openSafTree(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (isSaf) "External folder" else "Workspace",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (isSaf) {
                        IconButton(onClick = { viewModel.useAppWorkspace() }) {
                            Icon(Icons.Outlined.Folder, contentDescription = "Back to app workspace")
                        }
                    }
                    IconButton(onClick = { safPicker.launch(null) }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Open external folder")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(entries, key = { it.path }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name) },
                    supportingContent = {
                        entry.size?.let { Text("$it B") }
                    },
                    leadingContent = {
                        Icon(
                            if (entry.isDirectory) Icons.Outlined.Folder
                            else Icons.Outlined.Description,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDirectory) viewModel.openDirectory(entry.path)
                            else viewModel.openFile(entry.path)
                        }
                )
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        "Workspace is empty. Pull to refresh or open a folder from Settings.",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
