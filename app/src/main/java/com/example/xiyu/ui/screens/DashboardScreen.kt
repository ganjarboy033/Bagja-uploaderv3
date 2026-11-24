package com.example.xiyu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.xiyu.data.model.GitHubRepo
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import com.example.xiyu.viewmodel.AppStateViewModel
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    appViewModel: AppStateViewModel,
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val repository = appViewModel.repository
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val toastController = LocalToastController.current

    val dashState by appViewModel.dashboardState.collectAsState()
    val repos = dashState.repos
    val isLoadingRepos = dashState.isLoading
    val rateLimit by repository.rateLimit.collectAsState()
    val palette = LocalKruzenPalette.current

    var repoToDelete by remember { mutableStateOf<GitHubRepo?>(null) }

    fun manualRefreshRepos() {
        appViewModel.refreshDashboard(
            onError = { msg -> toastController.showError("Gagal memuat repositori: $msg") },
            onSuccess = { toastController.showSuccess("Dashboard diperbarui!") }
        )
    }

    LaunchedEffect(Unit) {
        appViewModel.ensureDashboardInitialized()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.cream)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("dashboard_screen")
    ) {
        // Workspace greeting with Manual Refresh Control
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "KRUZENLAB WORKSPACE ⚡",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.rose,
                    letterSpacing = 1.6.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Welcome back, ${currentUser?.name ?: currentUser?.login ?: "Developer"}.",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    color = palette.dark
                )
            }
            RefreshIconButton(
                isRefreshing = isLoadingRepos,
                contentDescription = "Refresh Dashboard",
                onClick = { manualRefreshRepos() },
                modifier = Modifier.testTag("refresh_dashboard_header_btn")
            )
        }
        ThinHighlightText("kruzenlab-uploader · Cloud Git Engine siap digunakan.")

        Spacer(modifier = Modifier.height(14.dp))

        // Account status card with live rate limit
        Surface(
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
            color = palette.cardBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!currentUser?.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = currentUser?.avatarUrl,
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(palette.dark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚡",
                            fontSize = 20.sp,
                            color = palette.cream
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "@${currentUser?.login ?: "guest"}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = palette.dark
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThinHighlightText(
                            text = if (rateLimit != null) "Quota: ${rateLimit?.remaining}/${rateLimit?.limit} reqs"
                            else "Connected via GitHub API"
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onNavigate(Screen.Login) },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = "Switch",
                        fontSize = 11.sp,
                        color = palette.rose
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3 Metric Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                icon = Icons.Default.Folder,
                label = "Repositories",
                value = "${repos.size}",
                sub = "Siap dikelola",
                palette = palette,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                icon = Icons.Default.CloudUpload,
                label = "Batch Uploader",
                value = "Ready",
                sub = "ZIP · Atomic Tree",
                palette = palette,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                icon = Icons.Default.Bolt,
                label = "Rate Limit",
                value = "${rateLimit?.remaining ?: 5000}",
                sub = "Permintaan tersisa",
                palette = palette,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Quick Hub Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = { onNavigate(Screen.RepoExplorer) },
                shape = RoundedCornerShape(18.dp),
                color = palette.cardBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = palette.rose, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("File Manager", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = palette.dark)
                    ThinHighlightText("CRUD file & folder cloud")
                }
            }

            Surface(
                onClick = { onNavigate(Screen.WorkflowRelease) },
                shape = RoundedCornerShape(18.dp),
                color = palette.cardBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = palette.dark, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Workflows & CI", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = palette.dark)
                    ThinHighlightText("Actions run & Releases")
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Main CTA Card: Start Atomic Upload
        Surface(
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
            color = palette.cardBg,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ATOMIC BATCH UPLOADER",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.rose,
                            letterSpacing = 1.6.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Unggah Proyek ke GitHub.",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = palette.dark
                        )
                    }
                    Text(
                        text = "⚡",
                        fontSize = 26.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Ekstrak file .zip otomatis, periksa struktur direktori, dan commit sekaligus dalam satu transaksi atomic tree Git v3.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.textMuted
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = { onNavigate(Screen.Upload) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.dark,
                        contentColor = palette.cream
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("open_uploader_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Buka Batch Uploader",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Repositories List with Safe Deletion
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Repositori Anda (${repos.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = palette.dark
            )
            RefreshIconButton(
                isRefreshing = isLoadingRepos,
                iconSize = 18.dp,
                contentDescription = "Refresh Daftar Repositori",
                onClick = { manualRefreshRepos() },
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoadingRepos) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            repos.forEach { repo ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                    color = palette.cardBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = palette.rose,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = repo.name,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = palette.dark
                            )
                            ThinHighlightText(
                                text = repo.description ?: "Default branch: ${repo.defaultBranch}",
                                maxLines = 1
                            )
                        }

                        // Delete repo icon with confirmation dialog
                        IconButton(
                            onClick = { repoToDelete = repo },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete Repo",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Attribution Footer
        AttributionFooter()
    }

    // Modal: Safe Delete Repo Confirm Dialog (Requires typing repo name)
    if (repoToDelete != null) {
        val target = repoToDelete!!
        DeleteRepoConfirmDialog(
            repoName = target.name,
            onConfirmDelete = {
                val owner = target.fullName.substringBefore('/')
                coroutineScope.launch {
                    toastController.showInfo("Menghapus repositori ${target.name}...", isLoading = true)
                    repository.deleteRepository(owner, target.name).fold(
                        onSuccess = {
                            toastController.showSuccess("Repositori ${target.name} berhasil dihapus!")
                            manualRefreshRepos()
                        },
                        onFailure = { err ->
                            toastController.showError("Gagal menghapus repositori: ${err.localizedMessage}")
                        }
                    )
                }
            },
            onDismiss = { repoToDelete = null }
        )
    }
}

@Composable
private fun MetricCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
    palette: KruzenPalette = LocalKruzenPalette.current
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
        color = palette.cardBg,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.rose,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                color = palette.textMuted
            )
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = palette.dark,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            ThinHighlightText(
                text = sub,
                fontSize = 10.sp
            )
        }
    }
}
