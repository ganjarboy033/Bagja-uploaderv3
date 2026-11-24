package com.example.xiyu.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiyu.data.model.BranchCompareResponse
import com.example.xiyu.data.model.GitHubRepo
import com.example.xiyu.data.model.GlobalCodeSearchResult
import com.example.xiyu.data.model.RepoContentItem
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.ZipExtractor
import com.example.xiyu.viewmodel.AppStateViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoExplorerScreen(
    appViewModel: AppStateViewModel,
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalKruzenPalette.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val toastController = LocalToastController.current
    val repository = appViewModel.repository

    val configState by repository.dbHelper.configState.collectAsState()
    val repoWebhooks by repository.dbHelper.repoWebhooks.collectAsState()
    val explorerState by appViewModel.explorerState.collectAsState()
    val repos = explorerState.repos
    val selectedRepo = explorerState.selectedRepo
    val selectedBranch = explorerState.selectedBranch
    val branches = explorerState.branches
    val currentPath = explorerState.currentPath
    val contents = explorerState.currentContents
    val isLoading = explorerState.isLoading

    // Dialog States
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<Pair<RepoContentItem, ByteArray>?>(null) }
    var isSavingEditor by remember { mutableStateOf(false) }

    var itemToDelete by remember { mutableStateOf<RepoContentItem?>(null) }
    var dirToDelete by remember { mutableStateOf<RepoContentItem?>(null) }
    var itemToRename by remember { mutableStateOf<RepoContentItem?>(null) }
    var renameNewName by remember { mutableStateOf("") }

    // New feature dialogs
    var isDownloadingZip by remember { mutableStateOf(false) }
    var showBranchCompareDialog by remember { mutableStateOf(false) }
    var showGlobalSearchDialog by remember { mutableStateOf(false) }
    var showVercelDeployDialog by remember { mutableStateOf(false) }

    // Multi-select for batch delete
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedItemPaths = remember { mutableStateListOf<String>() }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appViewModel.ensureExplorerInitialized()
    }

    fun openItemInUniversalEditor(repo: GitHubRepo, item: RepoContentItem) {
        coroutineScope.launch {
            toastController.showInfo("Mengunduh ${item.name}...", isLoading = true)
            repository.getSingleFileContent(
                owner = repo.fullName.substringBefore('/'),
                repo = repo.name,
                path = item.path,
                branch = selectedBranch
            ).fold(
                onSuccess = { fullItem ->
                    val rawBytes = if (!fullItem.content.isNullOrBlank()) {
                        try {
                            Base64.decode(fullItem.content.replace("\n", ""), Base64.DEFAULT)
                        } catch (_: Exception) {
                            fullItem.content.toByteArray(Charsets.UTF_8)
                        }
                    } else ByteArray(0)
                    editingTarget = fullItem to rawBytes
                    toastController.showSuccess("${item.name} siap diinspeksi")
                },
                onFailure = { err ->
                    toastController.showError("Gagal mengambil isi file: ${err.localizedMessage}")
                }
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.cream,
        floatingActionButton = {
            if (selectedRepo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingActionButton(
                        onClick = { showCreateFolderDialog = true },
                        containerColor = palette.dark,
                        contentColor = palette.cream,
                        modifier = Modifier.testTag("fab_create_folder")
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                    }
                    ExtendedFloatingActionButton(
                        onClick = { showCreateFileDialog = true },
                        icon = { Icon(Icons.Default.NoteAdd, contentDescription = null) },
                        text = { Text("New File", maxLines = 1, softWrap = false) },
                        containerColor = palette.dark,
                        contentColor = palette.cream,
                        modifier = Modifier.testTag("fab_create_file")
                    )
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
            // Header with Download ZIP, Refresh, Mulai Ulang Files, Global Search & Multi-Select
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cloud File Manager",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.dark
                    )
                    ThinHighlightText("100% tanpa filter · Branch & Compare", color = palette.textMuted)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Download Source ZIP left of Refresh
                    if (selectedRepo != null) {
                        IconButton(
                            onClick = {
                                if (!isDownloadingZip) {
                                    isDownloadingZip = true
                                    toastController.showInfo("Mengunduh Source ZIP (${selectedBranch})...", isLoading = true)
                                    appViewModel.downloadExplorerSourceZip(
                                        onError = { msg ->
                                            isDownloadingZip = false
                                            toastController.showError(msg)
                                        },
                                        onSuccess = { rec ->
                                            isDownloadingZip = false
                                            toastController.showSuccess("Source ZIP tersimpan: ${rec.fileName}")
                                        }
                                    )
                                }
                            },
                            enabled = !isDownloadingZip,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("download_source_zip_btn")
                        ) {
                            if (isDownloadingZip) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.rose)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.DownloadForOffline,
                                    contentDescription = "Unduh Source ZIP",
                                    tint = palette.rose,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    RefreshIconButton(
                        isRefreshing = isLoading,
                        contentDescription = "Refresh Repositori & Folder",
                        onClick = {
                            appViewModel.refreshExplorerRepos(resetPath = false) { err ->
                                if (err != null) toastController.showError(err)
                                else toastController.showSuccess("Daftar repositori & folder diperbarui")
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("refresh_explorer_header_btn")
                    )

                    // Mulai Ulang Files (reset cache & reload active user workspace)
                    IconButton(
                        onClick = {
                            toastController.showInfo("Memuat ulang workspace Files...", isLoading = true)
                            appViewModel.resetExplorerWorkspace { err ->
                                if (err != null) toastController.showError(err)
                                else toastController.showSuccess("Files di-reset ke akun aktif!")
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("reset_explorer_workspace_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Mulai Ulang Files",
                            tint = palette.dark,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Global Repo Code Search
                    if (configState.enableGlobalRepoSearch && selectedRepo != null) {
                        IconButton(
                            onClick = { showGlobalSearchDialog = true },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("global_repo_search_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Cari Kode di Repo",
                                tint = palette.rose,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            isMultiSelectMode = !isMultiSelectMode
                            selectedItemPaths.clear()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isMultiSelectMode) Icons.Default.CheckCircle else Icons.Default.Checklist,
                            contentDescription = "Multi Select",
                            tint = if (isMultiSelectMode) palette.dark else palette.textMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Repo Selector Dropdown
            var repoDropdownExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    onClick = { repoDropdownExpanded = true },
                    shape = RoundedCornerShape(12.dp),
                    color = palette.cardBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
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
                            Icon(Icons.Default.Folder, contentDescription = null, tint = palette.rose)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = selectedRepo?.fullName ?: "Pilih Repositori...",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.dark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = palette.dark)
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
                                appViewModel.selectExplorerRepo(repo)
                            }
                        )
                    }
                }
            }

            // Branch Switcher + Compare Branch + Vercel Deploy Row
            if (selectedRepo != null) {
                Spacer(modifier = Modifier.height(6.dp))
                var branchDropdownExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            onClick = { branchDropdownExpanded = true },
                            shape = RoundedCornerShape(10.dp),
                            color = palette.palePink.copy(alpha = 0.7f),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, palette.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("explorer_branch_switcher")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.AltRoute, contentDescription = null, tint = palette.rose, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Branch: $selectedBranch",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.dark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = palette.dark, modifier = Modifier.size(18.dp))
                            }
                        }

                        DropdownMenu(
                            expanded = branchDropdownExpanded,
                            onDismissRequest = { branchDropdownExpanded = false }
                        ) {
                            val branchNames = if (branches.isNotEmpty()) branches.map { it.name } else listOf(selectedBranch)
                            branchNames.forEach { bName ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(bName, fontWeight = if (bName == selectedBranch) FontWeight.Bold else FontWeight.Normal)
                                            if (bName == selectedRepo.defaultBranch) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                ThinHighlightBadge("default")
                                            }
                                        }
                                    },
                                    onClick = {
                                        branchDropdownExpanded = false
                                        appViewModel.selectExplorerBranch(bName) { err ->
                                            if (err != null) toastController.showError(err)
                                            else toastController.showSuccess("Beralih ke branch $bName")
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (configState.enableBranchCompare) {
                        OutlinedButton(
                            onClick = { showBranchCompareDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("branch_compare_btn")
                        ) {
                            Icon(Icons.Default.CompareArrows, contentDescription = null, tint = palette.rose, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Compare", fontSize = 11.sp, color = palette.dark, maxLines = 1, softWrap = false)
                        }
                    }

                    if (configState.enableVercelWebhook) {
                        val hasHook = repoWebhooks.any { it.repoFullName == selectedRepo.fullName }
                        OutlinedButton(
                            onClick = { showVercelDeployDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (hasHook) palette.accent else palette.border),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (hasHook) palette.dark else palette.cardBg,
                                contentColor = if (hasHook) palette.cream else palette.dark
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("vercel_deploy_explorer_btn")
                        ) {
                            Icon(
                                Icons.Default.RocketLaunch,
                                contentDescription = null,
                                tint = if (hasHook) palette.accent else palette.rose,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Vercel", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Breadcrumbs Bar with Per-Folder Manual Refresh Button
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.cardBg,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, palette.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Root",
                        tint = palette.dark,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { appViewModel.openExplorerPath("") }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "/", color = palette.rose, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))

                    val pathSegments = currentPath.split("/").filter { it.isNotBlank() }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (pathSegments.isEmpty()) {
                            ThinHighlightText("(root folder)", color = palette.textMuted)
                        } else {
                            pathSegments.forEachIndexed { index, seg ->
                                val segPath = pathSegments.take(index + 1).joinToString("/")
                                Text(
                                    text = seg,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.dark,
                                    modifier = Modifier.clickable { appViewModel.openExplorerPath(segPath) }
                                )
                                if (index < pathSegments.size - 1) {
                                    Text(text = " / ", fontSize = 12.sp, color = palette.rose)
                                }
                            }
                        }
                    }

                    if (currentPath.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val parent = currentPath.substringBeforeLast('/', "")
                                appViewModel.openExplorerPath(parent)
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Naik Folder", tint = palette.dark, modifier = Modifier.size(16.dp))
                        }
                    }

                    RefreshIconButton(
                        isRefreshing = isLoading,
                        contentDescription = "Refresh Folder Aktif",
                        iconSize = 16.dp,
                        onClick = {
                            appViewModel.refreshCurrentExplorerFolder { err ->
                                if (err != null) toastController.showError(err)
                                else toastController.showSuccess("Isi folder diperbarui")
                            }
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("refresh_active_folder_btn")
                    )
                }
            }

            // Multi-select batch actions bar
            if (isMultiSelectMode && selectedItemPaths.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${selectedItemPaths.size} item terpilih",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            onClick = { showBatchDeleteConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Hapus Semua", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // File & Folder List
            if (isLoading && contents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = palette.dark)
                }
            } else if (contents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = palette.rose,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Folder ini kosong.", fontWeight = FontWeight.Medium, color = palette.dark)
                        ThinHighlightText("Gunakan tombol New File / Folder di bawah.", color = palette.textMuted)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(contents, key = { it.path }) { item ->
                        val isDir = item.type == "dir"
                        val isSelected = selectedItemPaths.contains(item.path)

                        Surface(
                            onClick = {
                                if (isMultiSelectMode) {
                                    if (!isDir) {
                                        if (isSelected) selectedItemPaths.remove(item.path)
                                        else selectedItemPaths.add(item.path)
                                    }
                                } else if (isDir) {
                                    appViewModel.openExplorerPath(item.path)
                                } else {
                                    selectedRepo?.let { openItemInUniversalEditor(it, item) }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) palette.palePink else palette.cardBg,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, palette.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isMultiSelectMode && !isDir) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedItemPaths.add(item.path)
                                            else selectedItemPaths.remove(item.path)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                Icon(
                                    imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (isDir) palette.rose else palette.dark,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        fontSize = 13.sp,
                                        fontWeight = if (isDir) FontWeight.Bold else FontWeight.Medium,
                                        color = palette.dark
                                    )
                                    ThinHighlightText(
                                        text = if (isDir) "Direktori · Tap untuk membuka"
                                        else "${ZipExtractor.formatSize(item.size)} · SHA: ${item.sha.take(7)}",
                                        color = palette.textMuted
                                    )
                                }

                                // Quick actions menu
                                var itemMenuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(
                                        onClick = { itemMenuExpanded = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Actions", tint = palette.dark, modifier = Modifier.size(18.dp))
                                    }

                                    DropdownMenu(
                                        expanded = itemMenuExpanded,
                                        onDismissRequest = { itemMenuExpanded = false }
                                    ) {
                                        if (!isDir) {
                                            DropdownMenuItem(
                                                text = { Text("Buka di Editor Universal") },
                                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    selectedRepo?.let { openItemInUniversalEditor(it, item) }
                                                }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Simpan ke Downloads") },
                                                leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    selectedRepo?.let { repo ->
                                                        coroutineScope.launch {
                                                            toastController.showInfo("Menyimpan ${item.name} ke Downloads...", isLoading = true)
                                                            repository.getSingleFileContent(
                                                                owner = repo.fullName.substringBefore('/'),
                                                                repo = repo.name,
                                                                path = item.path,
                                                                branch = selectedBranch
                                                            ).fold(
                                                                onSuccess = { fullItem ->
                                                                    val rawBytes = if (!fullItem.content.isNullOrBlank()) {
                                                                        try {
                                                                            Base64.decode(fullItem.content.replace("\n", ""), Base64.DEFAULT)
                                                                        } catch (_: Exception) {
                                                                            fullItem.content.toByteArray(Charsets.UTF_8)
                                                                        }
                                                                    } else ByteArray(0)
                                                                    appViewModel.saveExplorerFileToDownloads(
                                                                        fileName = item.name,
                                                                        bytes = rawBytes,
                                                                        onError = { err -> toastController.showError(err) },
                                                                        onSuccess = { rec -> toastController.showSuccess("${rec.fileName} disimpan ke Downloads!") }
                                                                    )
                                                                },
                                                                onFailure = { err ->
                                                                    toastController.showError("Gagal unduh file: ${err.localizedMessage}")
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Rename") },
                                                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    itemToRename = item
                                                    renameNewName = item.name
                                                }
                                            )
                                        }

                                        DropdownMenuItem(
                                            text = { Text("Salin Raw URL") },
                                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                                            onClick = {
                                                itemMenuExpanded = false
                                                selectedRepo?.let { r ->
                                                    val rawUrl = "https://raw.githubusercontent.com/${r.fullName}/$selectedBranch/${item.path}"
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("Raw URL", rawUrl))
                                                    toastController.showSuccess("Raw URL disalin!")
                                                }
                                            }
                                        )

                                        DropdownMenuItem(
                                            text = { Text("Salin jsDelivr CDN") },
                                            leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                                            onClick = {
                                                itemMenuExpanded = false
                                                selectedRepo?.let { r ->
                                                    val cdnUrl = "https://cdn.jsdelivr.net/gh/${r.fullName}@$selectedBranch/${item.path}"
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("jsDelivr CDN", cdnUrl))
                                                    toastController.showSuccess("jsDelivr CDN URL disalin!")
                                                }
                                            }
                                        )

                                        HorizontalDivider()

                                        if (isDir) {
                                            DropdownMenuItem(
                                                text = { Text("Hapus Folder (Atomic)", color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    dirToDelete = item
                                                }
                                            )
                                        } else {
                                            DropdownMenuItem(
                                                text = { Text("Hapus File", color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    itemToDelete = item
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AttributionFooter()
        }
    }

    // Dialog: Branch Compare & Merge
    if (showBranchCompareDialog && selectedRepo != null) {
        val branchNames = remember(branches, selectedBranch) {
            val list = branches.map { it.name }.toMutableList()
            if (!list.contains(selectedBranch)) list.add(0, selectedBranch)
            list.distinct()
        }
        var baseBranch by remember { mutableStateOf(selectedRepo.defaultBranch) }
        var headBranch by remember {
            mutableStateOf(branchNames.firstOrNull { it != baseBranch } ?: selectedBranch)
        }
        var baseMenuExpanded by remember { mutableStateOf(false) }
        var headMenuExpanded by remember { mutableStateOf(false) }
        var compareResult by remember { mutableStateOf<BranchCompareResponse?>(null) }
        var isComparing by remember { mutableStateOf(false) }
        var isMerging by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isMerging) showBranchCompareDialog = false },
            title = { Text("Bandingkan & Merge Branch", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = palette.dark) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThinHighlightText("Pilih Base (target) dan Head (sumber perubahan) untuk melihat diff file.", color = palette.textMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { baseMenuExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Base: $baseBranch", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = baseMenuExpanded, onDismissRequest = { baseMenuExpanded = false }) {
                                branchNames.forEach { b ->
                                    DropdownMenuItem(text = { Text(b) }, onClick = { baseBranch = b; baseMenuExpanded = false })
                                }
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { headMenuExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Head: $headBranch", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = headMenuExpanded, onDismissRequest = { headMenuExpanded = false }) {
                                branchNames.forEach { b ->
                                    DropdownMenuItem(text = { Text(b) }, onClick = { headBranch = b; headMenuExpanded = false })
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            isComparing = true
                            val owner = selectedRepo.fullName.substringBefore('/')
                            coroutineScope.launch {
                                repository.compareBranches(owner, selectedRepo.name, baseBranch, headBranch).fold(
                                    onSuccess = { res -> compareResult = res },
                                    onFailure = { err -> toastController.showError("Compare gagal: ${err.localizedMessage}") }
                                )
                                isComparing = false
                            }
                        },
                        enabled = !isComparing && baseBranch != headBranch,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isComparing) {
                            CircularProgressIndicator(color = palette.cream, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Bandingkan $baseBranch...$headBranch", fontSize = 12.sp)
                        }
                    }

                    if (baseBranch == headBranch) {
                        ThinHighlightText("Pilih dua branch yang berbeda untuk membandingkan diff.", color = palette.rose)
                    }

                    compareResult?.let { cmp ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = palette.cream,
                            border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Status: ${cmp.status ?: "compared"} · +${cmp.aheadBy} ahead / -${cmp.behindBy} behind (${cmp.totalCommits} commit)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.dark
                                )
                                if (cmp.files.isEmpty()) {
                                    ThinHighlightText("Tidak ada perbedaan file antar branch ini.", color = palette.textMuted)
                                } else {
                                    cmp.files.take(25).forEach { f ->
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(
                                                text = f.filename,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = palette.dark,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "${f.status} (+${f.additions}/-${f.deletions})",
                                                fontSize = 10.sp,
                                                color = palette.rose
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (cmp.aheadBy > 0) {
                            Button(
                                onClick = {
                                    isMerging = true
                                    val owner = selectedRepo.fullName.substringBefore('/')
                                    coroutineScope.launch {
                                        repository.mergeBranch(
                                            owner = owner,
                                            repo = selectedRepo.name,
                                            baseBranch = baseBranch,
                                            headBranch = headBranch,
                                            commitMessage = "Merge $headBranch into $baseBranch via kruzenlab-uploader"
                                        ).fold(
                                            onSuccess = {
                                                isMerging = false
                                                showBranchCompareDialog = false
                                                toastController.showSuccess("Branch $headBranch berhasil di-merge ke $baseBranch!")
                                                appViewModel.refreshCurrentExplorerFolder()
                                            },
                                            onFailure = { err ->
                                                isMerging = false
                                                toastController.showError("Merge gagal: ${err.localizedMessage}")
                                            }
                                        )
                                    }
                                },
                                enabled = !isMerging,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D), contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isMerging) "Menggabungkan..." else "Merge $headBranch → $baseBranch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (!isMerging) showBranchCompareDialog = false }) {
                    Text("Tutup", color = palette.rose)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = palette.cardBg
        )
    }

    // Dialog: Global Repo Code Search
    if (showGlobalSearchDialog && selectedRepo != null) {
        var searchQuery by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf<List<GlobalCodeSearchResult>>(emptyList()) }
        var isSearching by remember { mutableStateOf(false) }
        var hasSearched by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showGlobalSearchDialog = false },
            title = { Text("Cari Kode di ${selectedRepo.name}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = palette.dark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Kata kunci kode / nama simbol...", fontSize = 12.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (searchQuery.isBlank()) return@Button
                                isSearching = true
                                hasSearched = true
                                val owner = selectedRepo.fullName.substringBefore('/')
                                coroutineScope.launch {
                                    repository.searchRepoCode(owner, selectedRepo.name, searchQuery.trim()).fold(
                                        onSuccess = { list -> searchResults = list },
                                        onFailure = { err -> toastController.showError("Pencarian gagal: ${err.localizedMessage}") }
                                    )
                                    isSearching = false
                                }
                            },
                            enabled = !isSearching && searchQuery.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (isSearching) "..." else "Cari", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    if (isSearching) {
                        Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = palette.dark)
                        }
                    } else if (hasSearched && searchResults.isEmpty()) {
                        ThinHighlightText("Tidak ada berkas yang cocok dengan \"$searchQuery\".", color = palette.textMuted)
                    } else if (searchResults.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            searchResults.forEach { res ->
                                Surface(
                                    onClick = {
                                        showGlobalSearchDialog = false
                                        openItemInUniversalEditor(
                                            selectedRepo,
                                            RepoContentItem(
                                                name = res.name,
                                                path = res.path,
                                                sha = res.sha,
                                                size = 0L,
                                                type = "file"
                                            )
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = palette.cream,
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, palette.border),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(res.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.dark)
                                        ThinHighlightText(res.path, fontSize = 10.sp, color = palette.rose, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGlobalSearchDialog = false }) {
                    Text("Tutup", color = palette.rose)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = palette.cardBg
        )
    }

    // Dialog: Vercel 1-Click Deploy Hook for Active Repo
    if (showVercelDeployDialog && selectedRepo != null) {
        val existingHook = repoWebhooks.find { it.repoFullName == selectedRepo.fullName }
        var hookInput by remember(existingHook) { mutableStateOf(existingHook?.vercelHookUrl ?: "") }
        var isDeploying by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isDeploying) showVercelDeployDialog = false },
            title = { Text("Vercel 1-Click Deploy", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = palette.dark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selectedRepo.fullName, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = palette.rose)
                    OutlinedTextField(
                        value = hookInput,
                        onValueChange = { hookInput = it },
                        label = { Text("Vercel Deploy Hook URL") },
                        placeholder = { Text("https://api.vercel.com/v1/integrations/deploy/...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (existingHook != null && existingHook.lastDeployedAt > 0L) {
                        val sdf = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
                        ThinHighlightText("Deploy terakhir: ${sdf.format(Date(existingHook.lastDeployedAt))}", color = palette.textMuted)
                    }
                    ThinHighlightText("URL Hook disimpan lokal per repositori di SQLite.", color = palette.textMuted)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanUrl = hookInput.trim()
                        if (!cleanUrl.startsWith("http")) {
                            toastController.showError("Masukkan URL Webhook Vercel yang valid!")
                            return@Button
                        }
                        isDeploying = true
                        coroutineScope.launch {
                            repository.dbHelper.saveRepoWebhook(selectedRepo.fullName, cleanUrl)
                            repository.triggerVercelWebhook(selectedRepo.fullName, cleanUrl).fold(
                                onSuccess = { msg ->
                                    isDeploying = false
                                    showVercelDeployDialog = false
                                    toastController.showSuccess(msg)
                                },
                                onFailure = { err ->
                                    isDeploying = false
                                    toastController.showError("Deploy gagal: ${err.localizedMessage}")
                                }
                            )
                        }
                    },
                    enabled = !isDeploying && hookInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream)
                ) {
                    Text(if (isDeploying) "Memicu..." else "Simpan & Deploy ⚡")
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!isDeploying) showVercelDeployDialog = false }) {
                    Text("Batal", color = palette.rose)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = palette.cardBg
        )
    }

    // Dialog: Create New File
    if (showCreateFileDialog) {
        var newFileName by remember { mutableStateOf("") }
        var newFileContent by remember { mutableStateOf("") }
        var newFileCommitMsg by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("Buat File Baru di GitHub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("Nama File (e.g., .gitignore, config.json)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newFileContent,
                        onValueChange = { newFileContent = it },
                        label = { Text("Isi Konten File") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                    OutlinedTextField(
                        value = newFileCommitMsg,
                        onValueChange = { newFileCommitMsg = it },
                        placeholder = { Text("Pesan commit (opsional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ThinHighlightText("File akan langsung di-commit ke branch $selectedBranch")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = if (currentPath.isEmpty()) newFileName.trim() else "$currentPath/${newFileName.trim()}"
                        val encoded = Base64.encodeToString(newFileContent.toByteArray(), Base64.NO_WRAP)
                        showCreateFileDialog = false
                        coroutineScope.launch {
                            toastController.showInfo("Menyimpan file...", isLoading = true)
                            selectedRepo?.let { r ->
                                repository.createOrUpdateFile(
                                    owner = r.fullName.substringBefore('/'),
                                    repo = r.name,
                                    path = path,
                                    contentBase64 = encoded,
                                    commitMessage = newFileCommitMsg.ifBlank { "Create $path via kruzenlab-uploader" },
                                    branch = selectedBranch
                                ).fold(
                                    onSuccess = {
                                        toastController.showSuccess("File $path berhasil dibuat!")
                                        appViewModel.refreshCurrentExplorerFolder()
                                    },
                                    onFailure = { err ->
                                        toastController.showError("Gagal membuat file: ${err.localizedMessage}")
                                    }
                                )
                            }
                        }
                    },
                    enabled = newFileName.isNotBlank()
                ) {
                    Text("Buat File")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateFileDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Dialog: Create New Folder
    if (showCreateFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Buat Folder Baru") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Nama Folder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ThinHighlightText("GitHub tidak menyimpan folder kosong, sistem otomatis membuat file .gitkeep di dalamnya.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetFolder = if (currentPath.isEmpty()) newFolderName.trim() else "$currentPath/${newFolderName.trim()}"
                        showCreateFolderDialog = false
                        coroutineScope.launch {
                            toastController.showInfo("Membuat folder $targetFolder...", isLoading = true)
                            selectedRepo?.let { r ->
                                repository.createFolder(
                                    owner = r.fullName.substringBefore('/'),
                                    repo = r.name,
                                    folderPath = targetFolder,
                                    branch = selectedBranch
                                ).fold(
                                    onSuccess = {
                                        toastController.showSuccess("Folder $targetFolder dibuat!")
                                        appViewModel.refreshCurrentExplorerFolder()
                                    },
                                    onFailure = { err ->
                                        toastController.showError("Gagal membuat folder: ${err.localizedMessage}")
                                    }
                                )
                            }
                        }
                    },
                    enabled = newFolderName.isNotBlank()
                ) {
                    Text("Buat Folder")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Universal Multi-Mode File Editor & Inspector
    if (editingTarget != null) {
        val (targetItem, rawBytes) = editingTarget!!
        UniversalFileEditorDialog(
            fileName = targetItem.name,
            filePath = targetItem.path,
            initialBytes = rawBytes,
            allowRemoteCommit = true,
            isSaving = isSavingEditor,
            onDismiss = { if (!isSavingEditor) editingTarget = null },
            onSaveBytes = { updatedBytes, msg ->
                isSavingEditor = true
                val encoded = Base64.encodeToString(updatedBytes, Base64.NO_WRAP)
                coroutineScope.launch {
                    toastController.showInfo("Menyimpan perubahan ke GitHub...", isLoading = true)
                    selectedRepo?.let { r ->
                        repository.createOrUpdateFile(
                            owner = r.fullName.substringBefore('/'),
                            repo = r.name,
                            path = targetItem.path,
                            contentBase64 = encoded,
                            commitMessage = msg,
                            sha = targetItem.sha,
                            branch = selectedBranch
                        ).fold(
                            onSuccess = {
                                isSavingEditor = false
                                editingTarget = null
                                toastController.showSuccess("File ${targetItem.name} berhasil disimpan!")
                                appViewModel.refreshCurrentExplorerFolder()
                            },
                            onFailure = { err ->
                                isSavingEditor = false
                                toastController.showError("Gagal menyimpan: ${err.localizedMessage}")
                            }
                        )
                    }
                }
            }
        )
    }

    // Modal: Rename Item
    if (itemToRename != null) {
        val target = itemToRename!!
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text("Ubah Nama (Rename)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ubah nama ${target.name}:")
                    OutlinedTextField(
                        value = renameNewName,
                        onValueChange = { renameNewName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ThinHighlightText("Sistem akan memindahkan blob dan menghapus path lama.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newPath = if (currentPath.isEmpty()) renameNewName.trim() else "$currentPath/${renameNewName.trim()}"
                        itemToRename = null
                        coroutineScope.launch {
                            toastController.showInfo("Mengubah nama menjadi $newPath...", isLoading = true)
                            selectedRepo?.let { r ->
                                repository.getSingleFileContent(
                                    owner = r.fullName.substringBefore('/'),
                                    repo = r.name,
                                    path = target.path,
                                    branch = selectedBranch
                                ).fold(
                                    onSuccess = { fullItem ->
                                        repository.createOrUpdateFile(
                                            owner = r.fullName.substringBefore('/'),
                                            repo = r.name,
                                            path = newPath,
                                            contentBase64 = fullItem.content ?: "",
                                            commitMessage = "Rename ${target.path} to $newPath",
                                            branch = selectedBranch
                                        ).fold(
                                            onSuccess = {
                                                repository.deleteFile(
                                                    owner = r.fullName.substringBefore('/'),
                                                    repo = r.name,
                                                    path = target.path,
                                                    sha = target.sha,
                                                    commitMessage = "Remove old path ${target.path}",
                                                    branch = selectedBranch
                                                )
                                                toastController.showSuccess("Item berhasil di-rename!")
                                                appViewModel.refreshCurrentExplorerFolder()
                                            },
                                            onFailure = { err ->
                                                toastController.showError("Gagal rename: ${err.localizedMessage}")
                                            }
                                        )
                                    },
                                    onFailure = { err ->
                                        toastController.showError("Gagal membaca file: ${err.localizedMessage}")
                                    }
                                )
                            }
                        }
                    },
                    enabled = renameNewName.isNotBlank() && renameNewName.trim() != target.name
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { itemToRename = null }) {
                    Text("Batal")
                }
            }
        )
    }

    // Modal: Confirmation for Single File Delete
    if (itemToDelete != null) {
        val target = itemToDelete!!
        UniversalConfirmDialog(
            title = "Hapus File GitHub",
            message = "Apakah Anda yakin ingin menghapus \"${target.name}\" dari repositori?",
            confirmText = "Hapus",
            isDestructive = true,
            onConfirm = {
                coroutineScope.launch {
                    toastController.showInfo("Menghapus ${target.name}...", isLoading = true)
                    selectedRepo?.let { r ->
                        repository.deleteFile(
                            owner = r.fullName.substringBefore('/'),
                            repo = r.name,
                            path = target.path,
                            sha = target.sha,
                            commitMessage = "Delete ${target.path} via kruzenlab-uploader",
                            branch = selectedBranch
                        ).fold(
                            onSuccess = {
                                toastController.showSuccess("File ${target.name} berhasil dihapus!")
                                appViewModel.refreshCurrentExplorerFolder()
                            },
                            onFailure = { err ->
                                toastController.showError("Gagal menghapus file: ${err.localizedMessage}")
                            }
                        )
                    }
                }
            },
            onDismiss = { itemToDelete = null }
        )
    }

    // Modal: Confirmation for Atomic Directory Delete
    if (dirToDelete != null) {
        val targetDir = dirToDelete!!
        UniversalConfirmDialog(
            title = "Hapus Folder Permanen (Atomic)?",
            message = "Seluruh isi folder \"${targetDir.path}\" dan semua sub-foldernya akan dihapus dalam 1 commit tunggal Git Data API.",
            confirmText = "Hapus Folder",
            isDestructive = true,
            onConfirm = {
                coroutineScope.launch {
                    toastController.showInfo("Menghapus folder ${targetDir.name} secara atomic...", isLoading = true)
                    selectedRepo?.let { r ->
                        repository.deleteDirectory(
                            owner = r.fullName.substringBefore('/'),
                            repo = r.name,
                            branch = selectedBranch,
                            folderPath = targetDir.path
                        ).fold(
                            onSuccess = {
                                toastController.showSuccess("Folder ${targetDir.name} dihapus dalam 1 commit!")
                                appViewModel.refreshCurrentExplorerFolder()
                            },
                            onFailure = { err ->
                                toastController.showError("Gagal menghapus folder: ${err.localizedMessage}")
                            }
                        )
                    }
                }
            },
            onDismiss = { dirToDelete = null }
        )
    }

    // Modal: Confirmation for Batch Delete
    if (showBatchDeleteConfirm) {
        UniversalConfirmDialog(
            title = "Hapus ${selectedItemPaths.size} Item Terpilih?",
            message = "Semua file yang dipilih akan dihapus secara berurutan langsung dari remote GitHub.",
            confirmText = "Hapus Semua",
            isDestructive = true,
            onConfirm = {
                val toDelete = selectedItemPaths.toList()
                selectedItemPaths.clear()
                isMultiSelectMode = false
                coroutineScope.launch {
                    selectedRepo?.let { r ->
                        var successCount = 0
                        for (path in toDelete) {
                            val item = contents.find { it.path == path }
                            if (item != null) {
                                repository.deleteFile(
                                    owner = r.fullName.substringBefore('/'),
                                    repo = r.name,
                                    path = item.path,
                                    sha = item.sha,
                                    commitMessage = "Batch delete ${item.name}",
                                    branch = selectedBranch
                                )
                                successCount++
                            }
                        }
                        toastController.showSuccess("$successCount file berhasil dihapus!")
                        appViewModel.refreshCurrentExplorerFolder()
                    }
                }
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }
}
