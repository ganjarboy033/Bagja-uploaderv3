package com.example.xiyu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.xiyu.ui.components.LocalToastController
import com.example.xiyu.ui.components.ToastController
import com.example.xiyu.ui.components.ToastHost
import com.example.xiyu.ui.components.Topbar
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.screens.*
import com.example.xiyu.ui.theme.XIYUUploaderTheme
import com.example.xiyu.util.NotificationHelper
import com.example.xiyu.util.OAuthHelper
import com.example.xiyu.viewmodel.AppStateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val appViewModel: AppStateViewModel by viewModels()
    private val pendingDeepLink = MutableStateFlow<Uri?>(null)
    private val pendingNavigateRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            val appConfig by appViewModel.repository.dbHelper.configState.collectAsState()
            XIYUUploaderTheme(themeSettings = appConfig.themeSettings) {
                val repository = appViewModel.repository
                val currentUser by repository.currentUser.collectAsState()
                val rateLimit by repository.rateLimit.collectAsState()
                val downloads by appViewModel.downloads.collectAsState()
                val incomingUri by pendingDeepLink.collectAsState()
                val incomingRoute by pendingNavigateRoute.collectAsState()

                val snackbarHostState = remember { SnackbarHostState() }
                val toastController = remember { ToastController() }
                val saveableStateHolder = rememberSaveableStateHolder()

                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                // Clean CI/CD artifact cache when leaving CI/CD Monitor tab
                LaunchedEffect(currentScreen) {
                    if (currentScreen != Screen.CiCdMonitor) {
                        appViewModel.clearCiCdCache()
                    }
                }

                LaunchedEffect(incomingRoute) {
                    incomingRoute?.let { route ->
                        currentScreen = when (route) {
                            Screen.Downloads.route -> Screen.Downloads
                            Screen.RepoExplorer.route -> Screen.RepoExplorer
                            Screen.DebugPanel.route -> Screen.DebugPanel
                            Screen.Upload.route -> Screen.Upload
                            Screen.WorkflowRelease.route -> Screen.WorkflowRelease
                            Screen.CiCdMonitor.route -> Screen.CiCdMonitor
                            Screen.Dashboard.route -> Screen.Dashboard
                            Screen.Login.route -> Screen.Login
                            else -> Screen.Home
                        }
                        pendingNavigateRoute.value = null
                    }
                }

                LaunchedEffect(incomingUri) {
                    incomingUri?.let { uri ->
                        processOAuthRedirect(uri, toastController) {
                            appViewModel.refreshDashboard()
                            appViewModel.refreshExplorerRepos(resetPath = false)
                            currentScreen = Screen.Dashboard
                        }
                        pendingDeepLink.value = null
                    }
                }

                if (currentScreen != Screen.Home) {
                    BackHandler {
                        currentScreen = Screen.Home
                    }
                }

                CompositionLocalProvider(LocalToastController provides toastController) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding(),
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            topBar = {
                                Topbar(
                                    currentUser = currentUser,
                                    rateLimit = rateLimit,
                                    currentScreen = currentScreen.route,
                                    downloadsCount = downloads.size,
                                    onNavigate = { screen ->
                                        currentScreen = screen
                                    }
                                )
                            }
                        ) { innerPadding ->
                            // Zero-reset navigation: saveable state + persistent AppStateViewModel
                            saveableStateHolder.SaveableStateProvider(key = currentScreen.route) {
                                when (currentScreen) {
                                    Screen.Home -> HomeScreen(
                                        currentUser = currentUser,
                                        onNavigate = { currentScreen = it },
                                        modifier = Modifier.padding(innerPadding)
                                    )
                                    Screen.Dashboard -> DashboardScreen(
                                        appViewModel = appViewModel,
                                        currentUser = currentUser,
                                        onNavigate = { currentScreen = it },
                                        modifier = Modifier.padding(innerPadding)
                                    )
                                    Screen.RepoExplorer -> RepoExplorerScreen(
                                        appViewModel = appViewModel,
                                        currentUser = currentUser,
                                        onNavigate = { currentScreen = it },
                                        modifier = Modifier.padding(innerPadding)
                                    )
                                    Screen.WorkflowRelease -> WorkflowReleaseScreen(
                                        appViewModel = appViewModel,
                                        currentUser = currentUser,
                                        onNavigate = { currentScreen = it },
                                        modifier = Modifier.padding(innerPadding)
                                    )
                                    Screen.CiCdMonitor -> CiCdMonitorScreen(
                                        appViewModel = appViewModel,
                                        modifier = Modifier.padding(innerPadding)
                                    )
                                    Screen.Upload -> UploadScreen(
                                        appViewModel = appViewModel,
                                        currentUser = currentUser,
                                        onNavigate = { currentScreen = it },
                                        modifier = Modifier.padding(innerPadding)
                                    )
                                    Screen.Downloads -> DownloadsScreen(
                                        appViewModel = appViewModel,
                                        modifier = Modifier.padding(innerPadding)
                                    )
                                    Screen.Login -> LoginScreen(
                                        repository = repository,
                                        currentUser = currentUser,
                                        onNavigate = { target ->
                                            if (target == Screen.Dashboard) {
                                                appViewModel.refreshDashboard()
                                                appViewModel.refreshExplorerRepos(resetPath = false)
                                            }
                                            currentScreen = target
                                        },
                                        modifier = Modifier.padding(innerPadding)
                                    )
                                    Screen.DebugPanel -> DebugPanelScreen(
                                        repository = repository,
                                        currentUser = currentUser,
                                        onNavigate = { currentScreen = it },
                                        modifier = Modifier.padding(innerPadding)
                                    )
                                }
                            }
                        }

                        ToastHost(
                            controller = toastController,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 90.dp)
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            handleDeepLink(uri)
        }
        val targetRoute = intent?.getStringExtra(NotificationHelper.EXTRA_NAVIGATE_TO)
        if (!targetRoute.isNullOrBlank()) {
            pendingNavigateRoute.value = targetRoute
            intent.removeExtra(NotificationHelper.EXTRA_NAVIGATE_TO)
        }
    }

    private fun handleDeepLink(uri: Uri) {
        if ((uri.scheme == "kruzenlab" || uri.scheme == "xiyu") && uri.host == "oauth-callback") {
            pendingDeepLink.value = uri
        }
    }

    private fun processOAuthRedirect(
        uri: Uri,
        toastController: ToastController,
        onSuccess: () -> Unit
    ) {
        val code = uri.getQueryParameter("code")
        val accessToken = uri.getQueryParameter("access_token")
        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")

        if (!error.isNullOrBlank()) {
            toastController.showError("Gagal otorisasi GitHub: $error")
            return
        }

        if (!accessToken.isNullOrBlank()) {
            lifecycleScope.launch {
                appViewModel.repository.loginWithToken(accessToken, isOAuth = true).fold(
                    onSuccess = { profile ->
                        toastController.showSuccess("Berhasil masuk sebagai @${profile.login} via Chrome!")
                        onSuccess()
                    },
                    onFailure = { err ->
                        toastController.showError("Gagal memuat profil: ${err.localizedMessage}")
                    }
                )
            }
            return
        }

        if (!code.isNullOrBlank()) {
            val clientId = OAuthHelper.getClientId(this)
            val clientSecret = OAuthHelper.getClientSecret(this)

            lifecycleScope.launch {
                toastController.showInfo("Menukarkan kode otorisasi GitHub...", isLoading = true)
                OAuthHelper.exchangeCodeForToken(clientId, clientSecret, code).fold(
                    onSuccess = { token ->
                        appViewModel.repository.loginWithToken(token, isOAuth = true).fold(
                            onSuccess = { profile ->
                                toastController.showSuccess("Berhasil masuk sebagai @${profile.login} via Chrome!")
                                onSuccess()
                            },
                            onFailure = { err ->
                                toastController.showError("Gagal memuat profil: ${err.localizedMessage}")
                            }
                        )
                    },
                    onFailure = { err ->
                        toastController.showError("Gagal tukar kode OAuth: ${err.localizedMessage}")
                    }
                )
            }
        }
    }
}
