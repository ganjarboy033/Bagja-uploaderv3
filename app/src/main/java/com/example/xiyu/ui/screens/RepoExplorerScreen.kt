package com.example.xiyu.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiyu.data.model.GitHubRepo
import com.example.xiyu.data.model.RepoContentItem
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.data.repository.GitHubRepository
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoExplorerScreen(
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
    var currentPath by remember { mutableStateOf("") }
    var contents by remember { mutableStateOf<List<RepoContentItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Dialog States
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<RepoContentItem?>(null) }
    var editorContent by remember { mutableStateOf("") }
    var editorCommitMsg by remember { mutableStateOf("") }
    var isSavingEditor by remember { mutableStateOf(false) }

    var itemToDelete by remember { mutableStateOf<RepoContentItem?>(null) }
    var itemToRename by remember { mutableStateOf<RepoContentItem?>(null) }
    var renameNewName by remember { mutableStateOf("") }

    // Multi-select for batch delete
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedItemPaths = remember { mutableStateListOf<String>() }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    fun refreshContents(repo: GitHubRepo, path: String) {
        coroutineScope.launch {
            isLoading = true
            toastController.showInfo("Memuat direktori: /${path.ifEmpty { "root" }}", isLoading = true)
            repository.getContents(
                owner = repo.fullName.substringBefore('/'),
                repo = repo.name,
                path = path,
                branch = repo.defaultBranch
            ).fold(
                onSuccess = { items ->
                    isLoading = false
                    contents = items.sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))
                    toastController.showSuccess("Direktori dimuat (${items.size} file/folder)")
                },
                onFailure = { err ->
                    isLoading = false
                    contents = emptyList()
                    toastController.showError("Gagal memuat file: ${err.localizedMessage}")
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        repository.getRepositories().fold(
            onSuccess = { list ->
                isLoading = false
                repos = list
                if (list.isNotEmpty() && selectedRepo == null) {
                    selectedRepo = list.first()
                    refreshContents(list.first(), "")
                }
            },
            onFailure = {
                isLoading = false
                toastController.showError("Gagal mengambil repositori")
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (selectedRepo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingActionButton(
                        onClick = { showCreateFolderDialog = true },
                        containerColor = BrandDark,
                        contentColor = BrandCream,
                        modifier = Modifier.testTag("fab_create_folder")
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                    }
                    ExtendedFloatingActionButton(
                        onClick = { showCreateFileDialog = true },
                        icon = { Icon(Icons.Default.NoteAdd, contentDescription = null) },
                        text = { Text("New File") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Cloud File Manager",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    ThinHighlightText("CRUD langsung ke repositori GitHub via Git Data API")
                }

                // Multi-select toggle button
                IconButton(
                    onClick = {
                        isMultiSelectMode = !isMultiSelectMode
                        selectedItemPaths.clear()
                    }
                ) {
                    Icon(
                        imageVector = if (isMultiSelectMode) Icons.Default.CheckCircle else Icons.Default.Checklist,
                        contentDescription = "Multi Select",
                        tint = if (isMultiSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Repo Selector Dropdown
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
                                currentPath = ""
                                repoDropdownExpanded = false
                                refreshContents(repo, "")
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Breadcrumbs Bar
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BrandCardBg,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, BrandBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Root",
                        tint = BrandDark,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable {
                                currentPath = ""
                                selectedRepo?.let { refreshContents(it, "") }
                            }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "/",
                        color = BrandRose,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    val pathSegments = currentPath.split("/").filter { it.isNotBlank() }
                    if (pathSegments.isEmpty()) {
                        ThinHighlightText("(root folder)")
                    } else {
                        pathSegments.forEachIndexed { index, seg ->
                            val segPath = pathSegments.take(index + 1).joinToString("/")
                            Text(
                                text = seg,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BrandDark,
                                modifier = Modifier.clickable {
                                    currentPath = segPath
                                    selectedRepo?.let { refreshContents(it, segPath) }
                                }
                            )
                            if (index < pathSegments.size - 1) {
                                Text(
                                    text = " / ",
                                    fontSize = 12.sp,
                                    color = BrandRose
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Up folder button
                    if (currentPath.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val parent = currentPath.substringBeforeLast('/', "")
                                currentPath = parent
                                selectedRepo?.let { refreshContents(it, parent) }
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(16.dp))
                        }
                    }
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
                            Text("Hapus Semua", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // File & Folder List
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
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
                            tint = BrandRose,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Folder ini kosong.", fontWeight = FontWeight.Medium)
                        ThinHighlightText("Gunakan tombol New File / Folder di bawah.")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(contents) { item ->
                        val isDir = item.type == "dir"
                        val isSelected = selectedItemPaths.contains(item.path)

                        Surface(
                            onClick = {
                                if (isMultiSelectMode) {
                                    if (isSelected) selectedItemPaths.remove(item.path)
                                    else selectedItemPaths.add(item.path)
                                } else if (isDir) {
                                    currentPath = item.path
                                    selectedRepo?.let { refreshContents(it, item.path) }
                                } else {
                                    // Open file in Cloud Code Editor
                                    coroutineScope.launch {
                                        toastController.showInfo("Mengunduh ${item.name}...", isLoading = true)
                                        selectedRepo?.let { r ->
                                            repository.getSingleFileContent(
                                                owner = r.fullName.substringBefore('/'),
                                                repo = r.name,
                                                path = item.path,
                                                branch = r.defaultBranch
                                            ).fold(
                                                onSuccess = { fullItem ->
                                                    val decoded = if (!fullItem.content.isNullOrBlank()) {
                                                        try {
                                                            String(Base64.decode(fullItem.content.replace("\n", ""), Base64.DEFAULT))
                                                        } catch (_: Exception) { fullItem.content }
                                                    } else ""
                                                    itemToEdit = fullItem
                                                    editorContent = decoded
                                                    editorCommitMsg = "Update ${item.name} via kruzenlab-uploader"
                                                    toastController.showSuccess("Editor dibuka")
                                                },
                                                onFailure = {
                                                    toastController.showError("Gagal mengambil isi file")
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, BrandBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isMultiSelectMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked == true) selectedItemPaths.add(item.path)
                                            else selectedItemPaths.remove(item.path)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                Icon(
                                    imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (isDir) BrandRose else BrandDark,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        fontSize = 13.sp,
                                        fontWeight = if (isDir) FontWeight.Bold else FontWeight.Medium
                                    )
                                    ThinHighlightText(
                                        if (isDir) "Direktori"
                                        else "${item.size} bytes · SHA: ${item.sha.take(7)}"
                                    )
                                }

                                // Quick actions menu for item
                                var itemMenuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(
                                        onClick = { itemMenuExpanded = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Actions", modifier = Modifier.size(18.dp))
                                    }

                                    DropdownMenu(
                                        expanded = itemMenuExpanded,
                                        onDismissRequest = { itemMenuExpanded = false }
                                    ) {
                                        if (!isDir) {
                                            DropdownMenuItem(
                                                text = { Text("Edit di Cloud") },
                                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    coroutineScope.launch {
                                                        selectedRepo?.let { r ->
                                                            repository.getSingleFileContent(
                                                                owner = r.fullName.substringBefore('/'),
                                                                repo = r.name,
                                                                path = item.path,
                                                                branch = r.defaultBranch
                                                            ).fold(
                                                                onSuccess = { fullItem ->
                                                                    val decoded = if (!fullItem.content.isNullOrBlank()) {
                                                                        try {
                                                                            String(Base64.decode(fullItem.content.replace("\n", ""), Base64.DEFAULT))
                                                                        } catch (_: Exception) { fullItem.content }
                                                                    } else ""
                                                                    itemToEdit = fullItem
                                                                    editorContent = decoded
                                                                    editorCommitMsg = "Update ${item.name} via kruzenlab-uploader"
                                                                },
                                                                onFailure = {
                                                                    toastController.showError("Gagal membuka file")
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                        }

                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                                            onClick = {
                                                itemMenuExpanded = false
                                                itemToRename = item
                                                renameNewName = item.name
                                            }
                                        )

                                        DropdownMenuItem(
                                            text = { Text("Salin Raw URL") },
                                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                                            onClick = {
                                                itemMenuExpanded = false
                                                selectedRepo?.let { r ->
                                                    val rawUrl = "https://raw.githubusercontent.com/${r.fullName}/${r.defaultBranch}/${item.path}"
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
                                                    val cdnUrl = "https://cdn.jsdelivr.net/gh/${r.fullName}@${r.defaultBranch}/${item.path}"
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("jsDelivr CDN", cdnUrl))
                                                    toastController.showSuccess("jsDelivr CDN URL disalin!")
                                                }
                                            }
                                        )

                                        HorizontalDivider()

                                        DropdownMenuItem(
                                            text = { Text("Hapus", color = MaterialTheme.colorScheme.error) },
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

            // Attribution Footer
            AttributionFooter()
        }
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
                        label = { Text("Nama File (e.g., config.json atau main.py)") },
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
                    ThinHighlightText("File akan langsung di-commit ke branch ${selectedRepo?.defaultBranch ?: "main"}")
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
                                    branch = r.defaultBranch
                                ).fold(
                                    onSuccess = {
                                        toastController.showSuccess("File $path berhasil dibuat!")
                                        refreshContents(r, currentPath)
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
                                    branch = r.defaultBranch
                                ).fold(
                                    onSuccess = {
                                        toastController.showSuccess("Folder $targetFolder dibuat!")
                                        refreshContents(r, currentPath)
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

    // Modal: Cloud Code Editor
    if (itemToEdit != null) {
        AlertDialog(
            onDismissRequest = { if (!isSavingEditor) itemToEdit = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Cloud Code Editor", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    ThinHighlightBadge(itemToEdit?.name ?: "")
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThinHighlightText("Edit langsung kode file di remote GitHub:")
                    OutlinedTextField(
                        value = editorContent,
                        onValueChange = { editorContent = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    )
                    OutlinedTextField(
                        value = editorCommitMsg,
                        onValueChange = { editorCommitMsg = it },
                        placeholder = { Text("Pesan commit") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSavingEditor = true
                        val target = itemToEdit ?: return@Button
                        val encoded = Base64.encodeToString(editorContent.toByteArray(), Base64.NO_WRAP)
                        coroutineScope.launch {
                            toastController.showInfo("Menyimpan perubahan ke GitHub...", isLoading = true)
                            selectedRepo?.let { r ->
                                repository.createOrUpdateFile(
                                    owner = r.fullName.substringBefore('/'),
                                    repo = r.name,
                                    path = target.path,
                                    contentBase64 = encoded,
                                    commitMessage = editorCommitMsg.ifBlank { "Update ${target.name}" },
                                    sha = target.sha,
                                    branch = r.defaultBranch
                                ).fold(
                                    onSuccess = {
                                        isSavingEditor = false
                                        itemToEdit = null
                                        toastController.showSuccess("File ${target.name} berhasil disimpan!")
                                        refreshContents(r, currentPath)
                                    },
                                    onFailure = { err ->
                                        isSavingEditor = false
                                        toastController.showError("Gagal menyimpan: ${err.localizedMessage}")
                                    }
                                )
                            }
                        }
                    },
                    enabled = !isSavingEditor
                ) {
                    if (isSavingEditor) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Commit & Save")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { itemToEdit = null },
                    enabled = !isSavingEditor
                ) {
                    Text("Batal")
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
                    ThinHighlightText("Sistem akan memindahkan blob dan menghapus path lama secara atomic.")
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
                                // 1. Read existing file content
                                repository.getSingleFileContent(
                                    owner = r.fullName.substringBefore('/'),
                                    repo = r.name,
                                    path = target.path,
                                    branch = r.defaultBranch
                                ).fold(
                                    onSuccess = { fullItem ->
                                        // 2. Put to new path
                                        repository.createOrUpdateFile(
                                            owner = r.fullName.substringBefore('/'),
                                            repo = r.name,
                                            path = newPath,
                                            contentBase64 = fullItem.content ?: "",
                                            commitMessage = "Rename ${target.path} to $newPath",
                                            branch = r.defaultBranch
                                        ).fold(
                                            onSuccess = {
                                                // 3. Delete old path
                                                repository.deleteFile(
                                                    owner = r.fullName.substringBefore('/'),
                                                    repo = r.name,
                                                    path = target.path,
                                                    sha = target.sha,
                                                    commitMessage = "Remove old path ${target.path}",
                                                    branch = r.defaultBranch
                                                )
                                                toastController.showSuccess("Item berhasil di-rename!")
                                                refreshContents(r, currentPath)
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

    // Modal: Confirmation for Single Delete
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
                            branch = r.defaultBranch
                        ).fold(
                            onSuccess = {
                                toastController.showSuccess("File ${target.name} berhasil dihapus!")
                                refreshContents(r, currentPath)
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
                                    branch = r.defaultBranch
                                )
                                successCount++
                            }
                        }
                        toastController.showSuccess("$successCount file berhasil dihapus!")
                        refreshContents(r, currentPath)
                    }
                }
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }
}
