package com.example.xiyu.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Dashboard : Screen("dashboard")
    data object RepoExplorer : Screen("repo_explorer")
    data object Upload : Screen("upload")
    data object WorkflowRelease : Screen("workflow_release")
    data object CiCdMonitor : Screen("cicd_monitor")
    data object Downloads : Screen("downloads")
    data object Login : Screen("login")
    data object DebugPanel : Screen("debug_panel")
}
