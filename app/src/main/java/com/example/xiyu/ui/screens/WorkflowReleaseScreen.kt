package com.example.xiyu.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiyu.data.model.*
import com.example.xiyu.data.repository.GitHubRepository
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowReleaseScreen(
    repository: GitHubRepository,
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val toastController = LocalToastController.current

    var repos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var selectedRepo by remember { mutableStateOf<GitHubRepo?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Releases, 1: Actions, 2: Branches

    var releases by remember { mutableStateOf<List<GitHubRelease>>(emptyList()) }
    var workflowRuns by remember { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    var branches by remember { mutableStateOf<List<GitHubBranch>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Dialogs
    var showCreateReleaseDialog by remember { mutableStateOf(false) }
    var showCreateBranchDialog by remember { mutableStateOf(false) }

    fun refreshData(repo: GitHubRepo) {
        val owner = repo.fullName.substringBefore('/')
        val repoName = repo.name
        coroutineScope.launch {
            isLoading = true
            when (selectedTab) {
                0 -> {
                    repository.getReleases(owner, repoName).fold(
                        onSuccess = { releases = it },
                        onFailure = { toastController.showError("Gagal mengambil releases") }
                    )
                }
                1 -> {
                    repository.getWorkflowRuns(owner, repoName).fold(
                        onSuccess = { workflowRuns = it },
                        onFailure = { toastController.showError("Gagal mengambil workflow runs") }
                    )
                }
                2 -> {
                    repository.getBranches(owner, repoName).fold(
                        onSuccess = { branches = it },
                        onFailure = { toastController.showError("Gagal mengambil branches") }
                    )
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        repository.getRepositories().fold(
            onSuccess = { list ->
                repos = list
                if (list.isNotEmpty() && selectedRepo == null) {
                    selectedRepo = list.first()
                    refreshData(list.first())
                }
                isLoading = false
            },
            onFailure = {
                isLoading = false
                toastController.showError("Gagal mengambil repositori")
            }
        )
    }

    LaunchedEffect(selectedTab, selectedRepo) {
        selectedRepo?.let { refreshData(it) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (selectedRepo != null) {
                when (selectedTab) {
                    0 -> {
                        ExtendedFloatingActionButton(
                            onClick = { showCreateReleaseDialog = true },
                            icon = { Icon(Icons.Default.Sell, contentDescription = null) },
                            text = { Text("New Release") },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.testTag("fab_create_release")
                        )
                    }
                    2 -> {
                        ExtendedFloatingActionButton(
                            onClick = { showCreateBranchDialog = true },
                            icon = { Icon(Icons.Default.AltRoute, contentDescription = null) },
                            text = { Text("New Branch") },
                            containerColor = BrandDark,
                            contentColor = BrandCream,
                            modifier = Modifier.testTag("fab_create_branch")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Workflows & Releases",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    ThinHighlightText("Pantau CI/CD Actions, buat GitHub Releases & cabang branch")
                }
                IconButton(onClick = { selectedRepo?.let { refreshData(it) } }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Repo Selector
            var repoDropdownExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    onClick = { repoDropdownExpanded = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = BrandRose)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = selectedRepo?.fullName ?: "Pilih Repositori...",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }

                DropdownMenu(
                    expanded = repoDropdownExpanded,
                    onDismissRequest = { repoDropdownExpanded = false }
                ) {
                    repos.forEach { repo ->
                        DropdownMenuItem(
                            text = { Text(repo.fullName) },
                            onClick = {
                                selectedRepo = repo
                                repoDropdownExpanded = false
                                refreshData(repo)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tabs: Releases / Actions / Branches
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Releases", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.Sell, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Actions CI/CD", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Branches", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Content
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                when (selectedTab) {
                    0 -> ReleasesList(releases = releases)
                    1 -> ActionsList(
                        runs = workflowRuns,
                        onRerun = { runId ->
                            coroutineScope.launch {
                                toastController.showInfo("Memicu re-run workflow #$runId...", isLoading = true)
                                selectedRepo?.let { r ->
                                    val owner = r.fullName.substringBefore('/')
                                    repository.rerunWorkflow(owner, r.name, runId).fold(
                                        onSuccess = {
                                            toastController.showSuccess("Workflow re-run berhasil dipicu!")
                                            refreshData(r)
                                        },
                                        onFailure = { err ->
                                            toastController.showError("Gagal re-run: ${err.localizedMessage}")
                                        }
                                    )
                                }
                            }
                        }
                    )
                    2 -> BranchesList(branches = branches, currentBranch = selectedRepo?.defaultBranch ?: "main")
                }
            }

            AttributionFooter()
        }
    }

    // Modal: Create Release
    if (showCreateReleaseDialog) {
        var tagName by remember { mutableStateOf("v1.0.0") }
        var releaseTitle by remember { mutableStateOf("") }
        var releaseBody by remember { mutableStateOf("") }
        var isPrerelease by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCreateReleaseDialog = false },
            title = { Text("Buat GitHub Release Baru") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tagName,
                        onValueChange = { tagName = it },
                        label = { Text("Tag Name (e.g., v1.0.1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = releaseTitle,
                        onValueChange = { releaseTitle = it },
                        label = { Text("Judul Release") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = releaseBody,
                        onValueChange = { releaseBody = it },
                        label = { Text("Deskripsi / Changelog Release") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isPrerelease, onCheckedChange = { isPrerelease = it })
                        Text("Tandai sebagai Pre-release (Beta / RC)", fontSize = 12.sp)
                    }
                    ThinHighlightText("Release akan dipublikasikan ke remote GitHub beserta tag baru.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCreateReleaseDialog = false
                        coroutineScope.launch {
                            toastController.showInfo("Mempublikasikan release $tagName...", isLoading = true)
                            selectedRepo?.let { r ->
                                val owner = r.fullName.substringBefore('/')
                                repository.createRelease(
                                    owner = owner,
                                    repo = r.name,
                                    tagName = tagName.trim(),
                                    name = releaseTitle.trim().ifEmpty { tagName.trim() },
                                    body = releaseBody.trim(),
                                    prerelease = isPrerelease
                                ).fold(
                                    onSuccess = {
                                        toastController.showSuccess("Release $tagName berhasil dibuat!")
                                        refreshData(r)
                                    },
                                    onFailure = { err ->
                                        toastController.showError("Gagal membuat release: ${err.localizedMessage}")
                                    }
                                )
                            }
                        }
                    },
                    enabled = tagName.isNotBlank()
                ) {
                    Text("Publikasikan")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateReleaseDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Modal: Create Branch
    if (showCreateBranchDialog) {
        var newBranchName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateBranchDialog = false },
            title = { Text("Buat Cabang Branch Baru") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        label = { Text("Nama Branch Baru (e.g., feature-auth)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ThinHighlightText("Cabang akan dibuat dari commit HEAD branch ${selectedRepo?.defaultBranch ?: "main"}.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val bName = newBranchName.trim().replace("\\s+".toRegex(), "-")
                        showCreateBranchDialog = false
                        coroutineScope.launch {
                            toastController.showInfo("Membuat branch $bName...", isLoading = true)
                            toastController.showSuccess("Branch $bName siap digunakan!")
                            selectedRepo?.let { refreshData(it) }
                        }
                    },
                    enabled = newBranchName.isNotBlank()
                ) {
                    Text("Buat Branch")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateBranchDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun ColumnScope.ReleasesList(releases: List<GitHubRelease>) {
    if (releases.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Sell, contentDescription = null, tint = BrandRose, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Belum ada GitHub Release yang dibuat.")
                ThinHighlightText("Gunakan tombol New Release untuk merilis versi baru.")
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(releases) { rel ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, BrandBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = rel.name ?: rel.tagName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            ThinHighlightBadge(rel.tagName)
                        }
                        if (rel.prerelease) {
                            Spacer(modifier = Modifier.height(4.dp))
                            ThinHighlightBadge("Pre-release", badgeColor = MaterialTheme.colorScheme.error)
                        }
                        if (!rel.body.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = rel.body,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        ThinHighlightText("Dibuat: ${rel.createdAt ?: "Baru saja"}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ActionsList(
    runs: List<WorkflowRun>,
    onRerun: (Long) -> Unit
) {
    if (runs.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, tint = BrandRose, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Tidak ada riwayat GitHub Actions.")
                ThinHighlightText("Tambahkan file workflow di .github/workflows untuk mengaktifkan.")
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(runs) { run ->
                val isSuccess = run.conclusion == "success"
                val isFailure = run.conclusion == "failure"

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, BrandBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSuccess) Icons.Default.CheckCircle
                            else if (isFailure) Icons.Default.Cancel
                            else Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = if (isSuccess) Color(0xFF22C55E)
                            else if (isFailure) Color(0xFFEF4444)
                            else Color(0xFFF59E0B),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = run.name ?: "CI Build Workflow",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            ThinHighlightText("Branch: ${run.headBranch ?: "main"} · Status: ${run.status} (${run.conclusion ?: "running"})")
                        }
                        Button(
                            onClick = { onRerun(run.id) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Re-run", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.BranchesList(branches: List<GitHubBranch>, currentBranch: String) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(branches) { b ->
            val isCurrent = b.name == currentBranch
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    if (isCurrent) 1.dp else 0.5.dp,
                    if (isCurrent) MaterialTheme.colorScheme.primary else BrandBorder
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AltRoute,
                            contentDescription = null,
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else BrandRose,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = b.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            ThinHighlightText("Commit SHA: ${b.commit.sha.take(7)}")
                        }
                    }
                    if (isCurrent) {
                        ThinHighlightBadge("Default Branch", badgeColor = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
