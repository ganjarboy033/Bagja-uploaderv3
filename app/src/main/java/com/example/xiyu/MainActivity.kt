package com.example.xiyu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.xiyu.data.repository.GitHubRepository
import com.example.xiyu.ui.components.LocalToastController
import com.example.xiyu.ui.components.ToastController
import com.example.xiyu.ui.components.ToastHost
import com.example.xiyu.ui.components.Topbar
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.screens.*
import com.example.xiyu.ui.theme.XIYUUploaderTheme
import com.example.xiyu.util.OAuthHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: GitHubRepository
    private val pendingDeepLink = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = GitHubRepository(applicationContext)

        intent?.data?.let { uri ->
            handleDeepLink(uri)
        }

        setContent {
            XIYUUploaderTheme {
                val context = LocalContext.current
                val currentUser by repository.currentUser.collectAsState()
                val rateLimit by repository.rateLimit.collectAsState()
                val incomingUri by pendingDeepLink.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                val toastController = remember { ToastController() }

                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                // Automatically navigate to Dashboard on successful login
                LaunchedEffect(incomingUri) {
                    incomingUri?.let { uri ->
                        processOAuthRedirect(uri, toastController) {
                            currentScreen = Screen.Dashboard
                        }
                        pendingDeepLink.value = null
                    }
                }

                // Handle system back navigation
                if (currentScreen != Screen.Home) {
                    BackHandler {
                        currentScreen = Screen.Home
                    }
                }

                CompositionLocalProvider(LocalToastController provides toastController) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            topBar = {
                                Topbar(
                                    currentUser = currentUser,
                                    rateLimit = rateLimit,
                                    currentScreen = currentScreen.route,
                                    onNavigate = { screen ->
                                        currentScreen = screen
                                    }
                                )
                            }
                        ) { innerPadding ->
                            when (currentScreen) {
                                Screen.Home -> HomeScreen(
                                    currentUser = currentUser,
                                    onNavigate = { currentScreen = it },
                                    modifier = Modifier.padding(innerPadding)
                                )
                                Screen.Dashboard -> DashboardScreen(
                                    repository = repository,
                                    currentUser = currentUser,
                                    onNavigate = { currentScreen = it },
                                    modifier = Modifier.padding(innerPadding)
                                )
                                Screen.RepoExplorer -> RepoExplorerScreen(
                                    repository = repository,
                                    currentUser = currentUser,
                                    onNavigate = { currentScreen = it },
                                    modifier = Modifier.padding(innerPadding)
                                )
                                Screen.WorkflowRelease -> WorkflowReleaseScreen(
                                    repository = repository,
                                    currentUser = currentUser,
                                    onNavigate = { currentScreen = it },
                                    modifier = Modifier.padding(innerPadding)
                                )
                                Screen.Upload -> UploadScreen(
                                    repository = repository,
                                    currentUser = currentUser,
                                    onNavigate = { currentScreen = it },
                                    modifier = Modifier.padding(innerPadding)
                                )
                                Screen.Login -> LoginScreen(
                                    repository = repository,
                                    currentUser = currentUser,
                                    onNavigate = { currentScreen = it },
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

                        // Floating dynamic toast host on top of content
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
        intent.data?.let { uri ->
            handleDeepLink(uri)
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
                repository.loginWithToken(accessToken, isOAuth = true).fold(
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
                        repository.loginWithToken(token, isOAuth = true).fold(
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
