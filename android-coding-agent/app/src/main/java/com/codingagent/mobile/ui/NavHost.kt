package com.codingagent.mobile.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.codingagent.mobile.ui.chat.ChatScreen
import com.codingagent.mobile.ui.settings.SettingsScreen
import com.codingagent.mobile.ui.workspace.WorkspaceScreen

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Chat : Dest("chat", "Chat", Icons.AutoMirrored.Outlined.Chat)
    data object Workspace : Dest("workspace", "Files", Icons.Outlined.Folder)
    data object Settings : Dest("settings", "Settings", Icons.Outlined.Settings)
}

private val bottomDestinations = listOf(Dest.Chat, Dest.Workspace, Dest.Settings)

@Composable
fun CodingAgentNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Chat.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Dest.Chat.route) { ChatScreen() }
            composable(Dest.Workspace.route) { WorkspaceScreen() }
            composable(Dest.Settings.route) { SettingsScreen() }
        }
    }
}
