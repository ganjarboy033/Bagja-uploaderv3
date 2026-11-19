package com.example.xiyu.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.xiyu.data.model.*
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.ZipExtractor
import com.example.xiyu.viewmodel.AppStateViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowReleaseScreen(
    appViewModel: AppStateViewModel,
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val toastController = LocalToastController.current
    val repository = appViewModel.repository

    val state by appViewModel.workflowsState.collectAsState()
    val downloads by appViewModel.downloads.collectAsState()

    val repos = state.repos
    val selectedRepo = state.selectedRepo
    val selectedTab = state.selectedTab
    val releases = state.releases
    val workflowRuns = state.workflowRuns
    val branches = state.branches
    val isLoading = state.isLoading

    // Modals
    var showCreateReleaseDialog by remember { mutableStateOf(false) }
    var showCreateBranchDialog by remember { mutableStateOf(false) }
    var selectedRunForDetail by remember { mutableStateOf<WorkflowRun?>(null) }

    LaunchedEffect(Unit) {
        appViewModel.ensureWorkflowsInitialized()
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Workflows & Releases",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThinHighlightText("Log CI/CD, Artifak APK, Release Asset & Branch")
                        if (state.isAutoPolling) {
                            ThinHighlightBadge("Polling 5s", badgeColor = Color(0xFFF59E0B))
                        }
                    }
                }
                RefreshIconButton(
                    isRefreshing = isLoading,
                    contentDescription = "Refresh Tab Aktif",
                    onClick = {
                        appViewModel.refreshActiveWorkflowTab(
                            onError = { toastController.showError(it) },
                            onSuccess = { toastController.showSuccess("Data diperbarui") }
                        )
                    },
                    modifier = Modifier.testTag("refresh_workflows_btn")
                )
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = BrandRose)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = selectedRepo?.fullName ?: "Pilih Repositori...",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
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
                                repoDropdownExpanded = false
                                appViewModel.selectWorkflowRepo(repo)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sub-Tabs: Releases / Actions / Branches
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { appViewModel.selectWorkflowTab(0) },
                    text = { Text("Releases", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.Sell, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { appViewModel.selectWorkflowTab(1) },
                    text = { Text("Actions CI/CD", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { appViewModel.selectWorkflowTab(2) },
                    text = { Text("Branches", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sub-tab Dedicated Manual Refresh Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sectionLabel = when (selectedTab) {
                    0 -> "Daftar Releases (${releases.size})"
                    1 -> "Riwayat Actions CI/CD (${workflowRuns.size})"
                    else -> "Daftar Branch (${branches.size})"
                }
                ThinHighlightText(sectionLabel)
                RefreshIconButton(
                    isRefreshing = isLoading,
                    iconSize = 16.dp,
                    contentDescription = "Muat Ulang Bagian Ini",
                    onClick = {
                        appViewModel.refreshActiveWorkflowTab(
                            onError = { toastController.showError(it) },
                            onSuccess = { toastController.showSuccess("$sectionLabel diperbarui") }
                        )
                    },
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (selectedTab == 1) {
                Surface(
                    onClick = { onNavigate(Screen.CiCdMonitor) },
                    shape = RoundedCornerShape(12.dp),
                    color = BrandPalePink.copy(alpha = 0.7f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandRose.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("shortcut_open_cicd_monitor")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = BrandRose, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Buka CI/CD Monitor Lengkap", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandDark)
                                ThinHighlightText("Job/Step detail, error highlight Gradle, ekstrak APK otomatis & Generator YML")
                            }
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = BrandDark)
                    }
                }
            }

            if (isLoading && ((selectedTab == 0 && releases.isEmpty()) || (selectedTab == 1 && workflowRuns.isEmpty()) || (selectedTab == 2 && branches.isEmpty()))) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                when (selectedTab) {
                    0 -> ReleasesList(releases = releases)
                    1 -> ActionsList(
                        runs = workflowRuns,
                        onOpenDetail = { selectedRunForDetail = it },
                        onRerun = { runId ->
                            coroutineScope.launch {
                                toastController.showInfo("Memicu re-run workflow #$runId...", isLoading = true)
                                selectedRepo?.let { r ->
                                    val owner = r.fullName.substringBefore('/')
                                    repository.rerunWorkflow(owner, r.name, runId).fold(
                                        onSuccess = {
                                            toastController.showSuccess("Workflow re-run berhasil dipicu!")
                                            appViewModel.refreshActiveWorkflowTab()
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

    // Modal: Run Detail (Logs + Artifacts Download to Internal Storage)
    if (selectedRunForDetail != null && selectedRepo != null) {
        val targetRun = selectedRunForDetail!!
        val repoOwner = selectedRepo.fullName.substringBefore('/')
        val repoName = selectedRepo.name

        WorkflowRunDetailDialog(
            appViewModel = appViewModel,
            owner = repoOwner,
            repoName = repoName,
            run = targetRun,
            onDismiss = { selectedRunForDetail = null },
            onOpenDownloadsTab = {
                selectedRunForDetail = null
                onNavigate(Screen.Downloads)
            }
        )
    }

    // Modal: Create Release with Binary Asset Attachments (Device SAF or Internal Downloads)
    if (showCreateReleaseDialog && selectedRepo != null) {
        var tagName by remember { mutableStateOf("v1.0.0") }
        var releaseTitle by remember { mutableStateOf("") }
        var releaseBody by remember { mutableStateOf("") }
        var isPrerelease by remember { mutableStateOf(false) }
        val attachments = remember { mutableStateListOf<ReleaseAssetAttachment>() }
        var showInternalPicker by remember { mutableStateOf(false) }
        var isPublishing by remember { mutableStateOf(false) }

        val deviceAssetLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris: List<Uri> ->
            uris.forEach { uri ->
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "asset.bin"
                var size = 0L
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                        }
                    }
                } catch (_: Exception) {}
                if (attachments.none { it.displayName == name && it.contentUriString == uri.toString() }) {
                    attachments.add(
                        ReleaseAssetAttachment(
                            displayName = name,
                            contentUriString = uri.toString(),
                            sizeBytes = size
                        )
                    )
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!isPublishing) showCreateReleaseDialog = false },
            title = { Text("Buat GitHub Release Baru") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tagName,
                        onValueChange = { tagName = it },
                        label = { Text("Tag Name (e.g., v2.0.0)") },
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
                            .height(90.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isPrerelease, onCheckedChange = { isPrerelease = it })
                        Text("Tandai sebagai Pre-release (Beta / RC)", fontSize = 12.sp)
                    }

                    HorizontalDivider(color = BrandBorder)
                    Text("Lampiran Asset Release (${attachments.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandDark)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { deviceAssetLauncher.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Dari Perangkat", fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = { showInternalPicker = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Dari Downloads (${downloads.size})", fontSize = 10.sp)
                        }
                    }

                    attachments.forEachIndexed { idx, att ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandPalePink.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${att.displayName} (${ZipExtractor.formatSize(att.sizeBytes)})",
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                IconButton(
                                    onClick = { attachments.removeAt(idx) },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Hapus Lampiran", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    ThinHighlightText("Asset APK/ZIP akan diunggah langsung ke upload_url Release GitHub.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isPublishing = true
                        val owner = selectedRepo.fullName.substringBefore('/')
                        coroutineScope.launch {
                            toastController.showInfo("Mempublikasikan release $tagName...", isLoading = true)
                            repository.createReleaseWithAssets(
                                owner = owner,
                                repo = selectedRepo.name,
                                tagName = tagName.trim(),
                                name = releaseTitle.trim().ifEmpty { tagName.trim() },
                                body = releaseBody.trim(),
                                prerelease = isPrerelease,
                                assets = attachments.toList(),
                                onProgress = { msg -> toastController.showInfo(msg, isLoading = true) }
                            ).fold(
                                onSuccess = {
                                    isPublishing = false
                                    showCreateReleaseDialog = false
                                    toastController.showSuccess("Release $tagName & ${attachments.size} asset berhasil dibuat!")
                                    appViewModel.refreshActiveWorkflowTab()
                                },
                                onFailure = { err ->
                                    isPublishing = false
                                    toastController.showError("Gagal membuat release: ${err.localizedMessage}")
                                }
                            )
                        }
                    },
                    enabled = !isPublishing && tagName.isNotBlank()
                ) {
                    if (isPublishing) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text("Publikasikan")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { if (!isPublishing) showCreateReleaseDialog = false }, enabled = !isPublishing) {
                    Text("Batal")
                }
            }
        )

        // Nested Picker: Choose asset from Internal App Downloads
        if (showInternalPicker) {
            AlertDialog(
                onDismissRequest = { showInternalPicker = false },
                title = { Text("Pilih dari Unduhan Aplikasi", fontSize = 16.sp) },
                text = {
                    if (downloads.isEmpty()) {
                        ThinHighlightText("Belum ada berkas APK/ZIP tersimpan di tab Downloads.")
                    } else {
                        Column(
                            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            downloads.forEach { dl ->
                                Surface(
                                    onClick = {
                                        if (attachments.none { it.localFilePath == dl.localPath }) {
                                            attachments.add(
                                                ReleaseAssetAttachment(
                                                    displayName = dl.fileName,
                                                    localFilePath = dl.localPath,
                                                    sizeBytes = dl.sizeBytes
                                                )
                                            )
                                        }
                                        showInternalPicker = false
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = BrandCream,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.InsertDriveFile, null, tint = BrandRose, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(dl.fileName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            ThinHighlightText("${dl.repoFullName} · ${ZipExtractor.formatSize(dl.sizeBytes)}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInternalPicker = false }) { Text("Tutup") }
                }
            )
        }
    }

    // Modal: Create Branch (Real Git Refs API)
    if (showCreateBranchDialog && selectedRepo != null) {
        var newBranchName by remember { mutableStateOf("") }
        var sourceBranch by remember(branches, selectedRepo) {
            mutableStateOf(branches.firstOrNull { it.name == selectedRepo.defaultBranch } ?: branches.firstOrNull())
        }
        var sourceExpanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCreateBranchDialog = false },
            title = { Text("Buat Cabang Branch Baru") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        label = { Text("Nama Branch Baru (e.g., feature-auth)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (branches.isNotEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { sourceExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Dari branch: ${sourceBranch?.name ?: selectedRepo.defaultBranch}",
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                                branches.forEach { b ->
                                    DropdownMenuItem(
                                        text = { Text("${b.name} (${b.commit.sha.take(7)})") },
                                        onClick = {
                                            sourceBranch = b
                                            sourceExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    ThinHighlightText("Branch akan dibuat via GitHub POST /git/refs dari commit SHA terpilih.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val bName = newBranchName.trim().replace("\\s+".toRegex(), "-")
                        val sha = sourceBranch?.commit?.sha ?: "HEAD"
                        val owner = selectedRepo.fullName.substringBefore('/')
                        showCreateBranchDialog = false
                        coroutineScope.launch {
                            toastController.showInfo("Membuat branch $bName...", isLoading = true)
                            repository.createBranch(owner, selectedRepo.name, bName, sha).fold(
                                onSuccess = {
                                    toastController.showSuccess("Branch $bName berhasil dibuat di GitHub!")
                                    appViewModel.refreshActiveWorkflowTab()
                                },
                                onFailure = { err ->
                                    toastController.showError("Gagal membuat branch: ${err.localizedMessage}")
                                }
                            )
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
private fun WorkflowRunDetailDialog(
    appViewModel: AppStateViewModel,
    owner: String,
    repoName: String,
    run: WorkflowRun,
    onDismiss: () -> Unit,
    onOpenDownloadsTab: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastController = LocalToastController.current
    val repository = appViewModel.repository

    var logsText by remember { mutableStateOf("") }
    var isLoadingLogs by remember { mutableStateOf(true) }
    var artifacts by remember { mutableStateOf<List<WorkflowArtifact>>(emptyList()) }
    var logSearch by remember { mutableStateOf("") }
    var downloadingArtifactId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(run.id) {
        isLoadingLogs = true
        repository.getWorkflowLogs(owner, repoName, run.id).fold(
            onSuccess = { logsText = it },
            onFailure = { logsText = "Gagal mengambil log build: ${it.localizedMessage}" }
        )
        repository.getWorkflowArtifacts(owner, repoName, run.id).onSuccess {
            artifacts = it
        }
        isLoadingLogs = false
    }

    val filteredLogText = remember(logsText, logSearch) {
        if (logSearch.isBlank()) logsText
        else logsText.lines().filter { it.contains(logSearch, ignoreCase = true) }.joinToString("\n")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .testTag("workflow_run_detail_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = BrandCream,
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(run.name ?: "Workflow Run #${run.id}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = BrandDark)
                        ThinHighlightText("Branch: ${run.headBranch ?: "main"} · Status: ${run.status} (${run.conclusion ?: "running"})")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Artifacts section (ZIP/APK download to internal Downloads tab)
                if (artifacts.isNotEmpty()) {
                    Text("Artifak Build (${artifacts.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BrandDark)
                    Spacer(modifier = Modifier.height(4.dp))
                    artifacts.forEach { art ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BrandCardBg,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, BrandBorder),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.FolderZip, null, tint = BrandRose, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(art.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandDark)
                                    ThinHighlightText("${ZipExtractor.formatSize(art.sizeInBytes)} · ${if (art.expired) "Kadaluarsa" else "Tersedia"}")
                                }
                                Button(
                                    onClick = {
                                        downloadingArtifactId = art.id
                                        scope.launch {
                                            toastController.showInfo("Mengunduh ${art.name} ke Downloads internal...", isLoading = true)
                                            repository.downloadArtifactToInternalStorage(owner, repoName, art, run.id).fold(
                                                onSuccess = {
                                                    downloadingArtifactId = null
                                                    toastController.showSuccess("${art.name} tersimpan di Downloads!")
                                                    onOpenDownloadsTab()
                                                },
                                                onFailure = { err ->
                                                    downloadingArtifactId = null
                                                    toastController.showError("Gagal unduh artifak: ${err.localizedMessage}")
                                                }
                                            )
                                        }
                                    },
                                    enabled = !art.expired && downloadingArtifactId == null,
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    if (downloadingArtifactId == art.id) {
                                        CircularProgressIndicator(color = BrandCream, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("Unduh ZIP", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Log Search + Save/Copy actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = logSearch,
                        onValueChange = { logSearch = it },
                        placeholder = { Text("Filter baris log...", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Build Logs", filteredLogText))
                        toastController.showSuccess("Log build disalin!")
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Salin Log", tint = BrandDark)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            repository.saveLogToDownloads(owner, repoName, run.id, logsText).onSuccess {
                                toastController.showSuccess("Log disimpan ke tab Downloads!")
                            }
                        }
                    }) {
                        Icon(Icons.Default.SaveAlt, contentDescription = "Simpan Log", tint = BrandRose)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(BrandDark, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    if (isLoadingLogs) {
                        CircularProgressIndicator(color = BrandAccent, modifier = Modifier.align(Alignment.Center))
                    } else {
                        Text(
                            text = filteredLogText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = BrandCream,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
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
                ThinHighlightText("Gunakan tombol New Release untuk merilis versi & unggah APK/ZIP asset.")
            }
        }
    } else {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(releases, key = { it.id }) { rel ->
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
                            Text(text = rel.name ?: rel.tagName, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            ThinHighlightBadge(rel.tagName)
                        }
                        if (rel.prerelease) {
                            Spacer(modifier = Modifier.height(4.dp))
                            ThinHighlightBadge("Pre-release", badgeColor = MaterialTheme.colorScheme.error)
                        }
                        if (!rel.body.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = rel.body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onOpenDetail: (WorkflowRun) -> Unit,
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
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(runs, key = { it.id }) { run ->
                val isSuccess = run.conclusion == "success"
                val isFailure = run.conclusion == "failure"

                Surface(
                    onClick = { onOpenDetail(run) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, BrandBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                            Text(text = run.name ?: "CI Build Workflow", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            ThinHighlightText("Branch: ${run.headBranch ?: "main"} · Status: ${run.status} (${run.conclusion ?: "running"}) · Tap untuk Log & APK")
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
    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(branches, key = { it.name }) { b ->
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
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
                            Text(text = b.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
