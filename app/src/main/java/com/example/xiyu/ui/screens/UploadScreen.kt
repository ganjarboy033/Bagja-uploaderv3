package com.example.xiyu.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.xiyu.data.model.FileEntry
import com.example.xiyu.data.model.UploadArchiveMode
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.FolderImporter
import com.example.xiyu.util.WorkflowTemplateGenerator
import com.example.xiyu.util.ZipExtractor
import com.example.xiyu.util.ZipInspectEntry
import com.example.xiyu.viewmodel.AppStateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

enum class SourceMode {
    ARCHIVE, SAMPLE, LINK, PASTE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    appViewModel: AppStateViewModel,
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val toastController = LocalToastController.current

    val state by appViewModel.uploaderState.collectAsState()
    val sourceMode = remember(state.sourceModeName) {
        runCatching { SourceMode.valueOf(state.sourceModeName) }.getOrDefault(SourceMode.ARCHIVE)
    }

    var isExistingRepoDropdownExpanded by remember { mutableStateOf(false) }
    var isRefreshingRepos by remember { mutableStateOf(false) }
    var isExtracting by remember { mutableStateOf(false) }
    var intakeProgressText by remember { mutableStateOf("") }

    // Link / Paste transient fields
    var linkUrl by remember { mutableStateOf("") }
    var pastePath by remember { mutableStateOf("README.md") }
    var pasteContent by remember { mutableStateOf("# kruzenlab-uploader Project\n\nUploaded via kruzenlab-uploader by DEV BAGJA / KruzenLab ⚡\n") }

    // Staged file inspection / editing
    var stagedEntryToEdit by remember { mutableStateOf<FileEntry?>(null) }
    var largeBinaryEntryToInspect by remember { mutableStateOf<FileEntry?>(null) }
    var showCommitConfirmDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Worker starts regardless; notification shows if granted */ }

    LaunchedEffect(Unit) {
        if (state.existingRepos.isEmpty()) {
            appViewModel.refreshUploaderRepos()
        }
    }

    val normalizedRepoName = remember(state.repoName) {
        state.repoName.trim().replace("\\s+".toRegex(), "-")
    }
    val isRepoNameValid = remember(normalizedRepoName) {
        normalizedRepoName.isNotEmpty() && normalizedRepoName.matches(Regex("^[A-Za-z0-9._-]{1,100}$"))
    }

    val chosenEntries = remember(state.entries) { state.entries.filter { it.isSelected } }
    val chosenTotalBytes = remember(chosenEntries) { chosenEntries.sumOf { it.size } }
    val visibleEntries = remember(state.entries, state.searchFilter) {
        if (state.searchFilter.isBlank()) state.entries
        else state.entries.filter { it.path.contains(state.searchFilter, ignoreCase = true) }
    }

    fun resolveDisplayName(uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "project.zip"
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isExtracting = true
            intakeProgressText = "Memulai pembacaan arsip..."
            val displayName = resolveDisplayName(uri)
            val isZip = displayName.endsWith(".zip", ignoreCase = true)
            val rawMode = state.archiveMode == UploadArchiveMode.RAW_ZIP || !isZip

            toastController.showInfo(
                if (rawMode) "Streaming berkas $displayName (maks 100 MB)..."
                else "Mengekstrak arsip $displayName ke staging disk (maks 500 MB)...",
                isLoading = true
            )

            scope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        val stagingDir = appViewModel.repository.resetIntakeStagingDir()
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Tidak dapat membaca berkas")
                        input.use { stream ->
                            if (rawMode) {
                                listOf(
                                    ZipExtractor.readRawToStaging(stream, displayName, stagingDir) { bytesRead ->
                                        intakeProgressText = "Streaming ${ZipExtractor.formatSize(bytesRead)}..."
                                    }
                                )
                            } else {
                                ZipExtractor.extractZipToStaging(stream, stagingDir, state.removeGitFolders) { count, path, totalBytes ->
                                    intakeProgressText = "$count berkas (${ZipExtractor.formatSize(totalBytes)}): $path"
                                }
                            }
                        }
                    }
                    val totalSize = loaded.sumOf { it.size }
                    appViewModel.updateUploader {
                        it.copy(
                            entries = loaded,
                            archiveName = displayName,
                            selectedFolderName = null,
                            includeParentFolderInPath = false,
                            totalStagedBytes = totalSize
                        )
                    }
                    toastController.showSuccess(
                        if (rawMode) "$displayName siap di-push (${ZipExtractor.formatSize(totalSize)})!"
                        else "$displayName diekstrak (${loaded.size} files · ${ZipExtractor.formatSize(totalSize)})"
                    )
                } catch (e: Exception) {
                    toastController.showError("Gagal memuat arsip: ${e.localizedMessage}")
                } finally {
                    isExtracting = false
                    intakeProgressText = ""
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            isExtracting = true
            intakeProgressText = "Memindai struktur folder..."
            toastController.showInfo("Memindai folder perangkat ke staging disk...", isLoading = true)

            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val stagingDir = appViewModel.repository.resetIntakeStagingDir()
                        FolderImporter.importFolderToStaging(
                            context = context,
                            treeUri = treeUri,
                            stagingDir = stagingDir,
                            removeGit = state.removeGitFolders,
                            includeParentFolder = state.includeParentFolderInPath
                        ) { count, relPath, cumulativeBytes ->
                            intakeProgressText = "$count file (${ZipExtractor.formatSize(cumulativeBytes)}): $relPath"
                        }
                    }
                    appViewModel.updateUploader {
                        it.copy(
                            entries = result.entries,
                            archiveName = "Folder: ${result.folderName}",
                            selectedFolderName = result.folderName,
                            totalStagedBytes = result.totalBytes
                        )
                    }
                    toastController.showSuccess(
                        "Folder \"${result.folderName}\" siap (${result.entries.size} files · ${ZipExtractor.formatSize(result.totalBytes)})"
                    )
                } catch (e: Exception) {
                    toastController.showError("Gagal memuat folder: ${e.localizedMessage}")
                } finally {
                    isExtracting = false
                    intakeProgressText = ""
                }
            }
        }
    }

    fun executeBackgroundPush() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val owner = if (state.repoMode == "new") {
            currentUser?.login ?: "bagja_dev"
        } else {
            state.selectedExistingRepo?.fullName?.substringBefore("/") ?: (currentUser?.login ?: "bagja_dev")
        }

        val targetRepoName = if (state.repoMode == "new") {
            normalizedRepoName
        } else {
            state.selectedExistingRepo?.name ?: ""
        }

        if (targetRepoName.isBlank()) {
            toastController.showError("Pilih atau masukkan nama repositori target!")
            return
        }

        val finalFiles = addGeneratedFiles(
            chosenEntries,
            targetRepoName,
            state.repoDescription,
            state.readmeChoice,
            state.licenseChoice,
            state.gitignoreChoice
        )

        appViewModel.enqueueBackgroundPush(
            owner = owner,
            repoName = targetRepoName,
            isNewRepo = state.repoMode == "new",
            repoDescription = state.repoDescription,
            isPrivate = state.isPrivate,
            branch = state.branchName,
            commitMessage = state.commitMessage,
            filesToPush = finalFiles,
            onError = { err ->
                toastController.showError("Push gagal: $err")
            },
            onQueued = {
                toastController.showInfo("Upload berjalan di background (WakeLock aktif)", isLoading = false)
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BrandCream)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("upload_screen")
    ) {
        // Back to dashboard
        Row(
            modifier = Modifier
                .clickable { onNavigate(Screen.Dashboard) }
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = BrandRose,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Kembali ke Dashboard", fontSize = 12.sp, color = BrandTextMuted)
        }

        Text(
            text = "KRUZENLAB BATCH UPLOADER ⚡",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = BrandRose,
            letterSpacing = 1.6.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Atomic Single-Tree Push",
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = BrandDark
        )
        ThinHighlightText("Persisten antar-tab, mendukung background WorkManager (WakeLock) & mode ZIP utuh/ekstrak.")

        Spacer(modifier = Modifier.height(16.dp))

        // Intake Source Card
        Surface(
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
            color = BrandCardBg,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SourceTabButton(
                        label = "Archive",
                        icon = Icons.Default.CloudUpload,
                        isSelected = sourceMode == SourceMode.ARCHIVE,
                        onClick = { appViewModel.updateUploader { it.copy(sourceModeName = SourceMode.ARCHIVE.name) } },
                        testTag = "tab_source_archive"
                    )
                    SourceTabButton(
                        label = "Sample",
                        icon = Icons.Default.Folder,
                        isSelected = sourceMode == SourceMode.SAMPLE,
                        onClick = { appViewModel.updateUploader { it.copy(sourceModeName = SourceMode.SAMPLE.name) } },
                        testTag = "tab_source_sample"
                    )
                    SourceTabButton(
                        label = "Paste",
                        icon = Icons.Default.EditNote,
                        isSelected = sourceMode == SourceMode.PASTE,
                        onClick = { appViewModel.updateUploader { it.copy(sourceModeName = SourceMode.PASTE.name) } },
                        testTag = "tab_source_paste"
                    )
                    SourceTabButton(
                        label = "Link",
                        icon = Icons.Default.Link,
                        isSelected = sourceMode == SourceMode.LINK,
                        onClick = { appViewModel.updateUploader { it.copy(sourceModeName = SourceMode.LINK.name) } },
                        testTag = "tab_source_link"
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mode Pemrosesan Arsip (Ekstrak vs Upload ZIP Utuh)
                Text("Mode Pemrosesan Arsip", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BrandDark)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = state.archiveMode == UploadArchiveMode.ATOMIC_EXTRACT,
                        onClick = {
                            appViewModel.updateUploader { it.copy(archiveMode = UploadArchiveMode.ATOMIC_EXTRACT) }
                        },
                        label = { Text("Ekstrak & Atomic Push", fontSize = 11.sp) },
                        shape = RoundedCornerShape(14.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandDark,
                            selectedLabelColor = BrandCream
                        )
                    )
                    FilterChip(
                        selected = state.archiveMode == UploadArchiveMode.RAW_ZIP,
                        onClick = {
                            appViewModel.updateUploader { it.copy(archiveMode = UploadArchiveMode.RAW_ZIP) }
                        },
                        label = { Text("Upload ZIP Utuh", fontSize = 11.sp) },
                        shape = RoundedCornerShape(14.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandDark,
                            selectedLabelColor = BrandCream
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (sourceMode) {
                    SourceMode.ARCHIVE -> {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = BrandCream.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, BrandRose.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .testTag("pick_archive_box")
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(BrandPalePink),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isExtracting) {
                                        CircularProgressIndicator(color = BrandRose, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.CloudUpload, null, tint = BrandRose, modifier = Modifier.size(24.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = state.archiveName ?: "Pilih Arsip .ZIP, Berkas, atau Folder Proyek",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = BrandDark
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = when {
                                        isExtracting && intakeProgressText.isNotBlank() -> intakeProgressText
                                        state.archiveName != null -> "${state.entries.size} files siap (${ZipExtractor.formatSize(chosenTotalBytes)})"
                                        else -> "Streaming disk hingga 100 MB/file · 500 MB/batch · 100% tanpa filter ekstensi"
                                    },
                                    fontSize = 11.sp,
                                    color = BrandTextMuted
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                // Side-by-side Pick File / ZIP and Pick Folder buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                        enabled = !isExtracting,
                                        colors = ButtonDefaults.buttonColors(containerColor = BrandDark, contentColor = BrandCream),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("pick_file_zip_btn")
                                    ) {
                                        Icon(Icons.Default.FolderZip, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Pilih File / ZIP", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }

                                    OutlinedButton(
                                        onClick = { folderPickerLauncher.launch(null) },
                                        enabled = !isExtracting,
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.2.dp, BrandRose),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("pick_folder_btn")
                                    ) {
                                        Icon(Icons.Default.CreateNewFolder, null, tint = BrandRose, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Pilih Folder", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BrandDark, maxLines = 1)
                                    }
                                }

                                // Toggle switch for folder path prefix when folder is selected
                                if (!state.selectedFolderName.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = BrandCardBg,
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, BrandBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Sertakan nama folder induk",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = BrandDark
                                                )
                                                ThinHighlightText(
                                                    if (state.includeParentFolderInPath) "Path: ${state.selectedFolderName}/..."
                                                    else "Path: langsung ke root repositori"
                                                )
                                            }
                                            Switch(
                                                checked = state.includeParentFolderInPath,
                                                onCheckedChange = { appViewModel.toggleIncludeParentFolder(it) },
                                                modifier = Modifier.testTag("toggle_include_parent_folder")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SourceMode.SAMPLE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BrandCream.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Text("Muat Proyek Contoh (KruzenLab Starter + CI/CD)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = BrandDark)
                            Spacer(modifier = Modifier.height(4.dp))
                            ThinHighlightText("Membuat berkas demo Kotlin, JSON, Markdown & .github/workflows/build.yml (if: always() + gradle-build.log).")
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val workflowYml = WorkflowTemplateGenerator.generateAndroidBuildWorkflow(state.branchName)
                                    val sampleList = listOf(
                                        createEntry(".github/workflows/build.yml", workflowYml),
                                        createEntry("src/main/kotlin/App.kt", "package com.kruzenlab\n\nfun main() {\n    println(\"kruzenlab-uploader ready ⚡\")\n}\n"),
                                        createEntry("src/main/resources/config.json", "{\n  \"appName\": \"kruzenlab-uploader\",\n  \"author\": \"DEV BAGJA / KruzenLab\",\n  \"version\": \"2.1.0\"\n}\n"),
                                        createEntry("docs/architecture.md", "# KruzenLab Cloud Architecture\n\nAtomic Git tree commit engine by DEV BAGJA.\n"),
                                        createEntry("README.md", "# kruzenlab-uploader\n\nCloud Repository Manager & Batch Uploader.\n")
                                    )
                                    appViewModel.updateUploader {
                                        it.copy(
                                            entries = sampleList,
                                            archiveName = "kruzenlab-starter.zip",
                                            selectedFolderName = null,
                                            includeParentFolderInPath = false,
                                            totalStagedBytes = sampleList.sumOf { e -> e.size }
                                        )
                                    }
                                    toastController.showSuccess("Sample project + workflow if:always() dimuat ke staging")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.testTag("load_sample_project_btn")
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Muat File Contoh", fontSize = 12.sp)
                            }
                        }
                    }

                    SourceMode.PASTE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BrandCream.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            OutlinedTextField(
                                value = pastePath,
                                onValueChange = { pastePath = it },
                                label = { Text("Path File (e.g., .github/workflows/ci.yml)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("paste_path_input")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pasteContent,
                                onValueChange = { pasteContent = it },
                                label = { Text("Isi Kode / Teks") },
                                minLines = 4,
                                maxLines = 8,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("paste_content_input")
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    if (pastePath.isNotBlank()) {
                                        val clean = ZipExtractor.cleanPath(pastePath)
                                        val newEntry = createEntry(clean, pasteContent)
                                        appViewModel.updateUploader { cur ->
                                            cur.copy(entries = cur.entries.filter { it.path != clean } + newEntry)
                                        }
                                        pastePath = ""
                                        pasteContent = ""
                                        toastController.showSuccess("File ditambahkan ke staging!")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.testTag("add_pasted_file_btn")
                            ) {
                                Text("Tambahkan File", fontSize = 12.sp)
                            }
                        }
                    }

                    SourceMode.LINK -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BrandCream.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            OutlinedTextField(
                                value = linkUrl,
                                onValueChange = { linkUrl = it },
                                label = { Text("URL Arsip Langsung (.zip)") },
                                placeholder = { Text("https://example.com/project.zip") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("link_url_input")
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    if (linkUrl.isNotBlank()) {
                                        isExtracting = true
                                        intakeProgressText = "Mengunduh arsip dari URL..."
                                        val rawMode = state.archiveMode == UploadArchiveMode.RAW_ZIP
                                        toastController.showInfo("Mengunduh arsip dari URL ke staging disk...", isLoading = true)
                                        scope.launch {
                                            try {
                                                val fileName = linkUrl.substringAfterLast("/").ifBlank { "archive.zip" }
                                                val loaded = withContext(Dispatchers.IO) {
                                                    val stagingDir = appViewModel.repository.resetIntakeStagingDir()
                                                    URL(linkUrl).openStream().use { stream ->
                                                        if (rawMode) {
                                                            listOf(ZipExtractor.readRawToStaging(stream, fileName, stagingDir))
                                                        } else {
                                                            ZipExtractor.extractZipToStaging(stream, stagingDir, state.removeGitFolders)
                                                        }
                                                    }
                                                }
                                                appViewModel.updateUploader {
                                                    it.copy(
                                                        entries = loaded,
                                                        archiveName = fileName,
                                                        selectedFolderName = null,
                                                        includeParentFolderInPath = false,
                                                        totalStagedBytes = loaded.sumOf { e -> e.size }
                                                    )
                                                }
                                                toastController.showSuccess("Arsip diunduh (${loaded.size} item)")
                                            } catch (e: Exception) {
                                                toastController.showError("Gagal download: ${e.localizedMessage}")
                                            } finally {
                                                isExtracting = false
                                                intakeProgressText = ""
                                            }
                                        }
                                    }
                                },
                                enabled = !isExtracting && linkUrl.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.testTag("fetch_link_btn")
                            ) {
                                Text("Unduh & Proses", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Files to push Section (Tap item to inspect/edit with UniversalFileEditorDialog)
        if (state.entries.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                color = BrandCardBg,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("files_preview_section")
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { appViewModel.updateUploader { it.copy(isFilesExpanded = !it.isFilesExpanded) } },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Files to push — ${chosenEntries.size} dari ${state.entries.size} (${ZipExtractor.formatSize(chosenTotalBytes)})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = BrandDark
                            )
                            ThinHighlightText("Ketuk baris file untuk buka di Editor / Inspektur Arsip")
                        }
                        Icon(
                            imageVector = if (state.isFilesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = BrandRose
                        )
                    }

                    if (state.isFilesExpanded) {
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    appViewModel.updateUploader { cur ->
                                        cur.copy(entries = cur.entries.map { it.copy(isSelected = true) })
                                    }
                                }) {
                                    Text("Pilih Semua", fontSize = 11.sp, color = BrandRose)
                                }
                                TextButton(onClick = {
                                    appViewModel.updateUploader { cur ->
                                        cur.copy(entries = cur.entries.map { it.copy(isSelected = false) })
                                    }
                                }) {
                                    Text("Bersihkan", fontSize = 11.sp, color = BrandTextMuted)
                                }
                            }

                            OutlinedTextField(
                                value = state.searchFilter,
                                onValueChange = { q -> appViewModel.updateUploader { it.copy(searchFilter = q) } },
                                placeholder = { Text("Cari file...", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.width(140.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .background(BrandCream.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .padding(8.dp)
                        ) {
                            visibleEntries.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (entry.size > 6L * 1024L * 1024L) {
                                                largeBinaryEntryToInspect = entry
                                            } else {
                                                stagedEntryToEdit = entry
                                            }
                                        }
                                        .padding(vertical = 4.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = entry.isSelected,
                                        onCheckedChange = { checked ->
                                            appViewModel.updateUploader { cur ->
                                                cur.copy(entries = cur.entries.map {
                                                    if (it.path == entry.path) it.copy(isSelected = checked) else it
                                                })
                                            }
                                        },
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = entry.path,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = BrandDark,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = ZipExtractor.formatSize(entry.size),
                                        fontSize = 10.sp,
                                        color = BrandTextMuted
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.Edit, contentDescription = "Inspect", tint = BrandRose, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Repository Target Configuration
        Surface(
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
            color = BrandCardBg,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Konfigurasi Repositori Target", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = BrandDark)
                ThinHighlightText("Mendukung auto-init pada repositori baru & checkpoint resumable SQLite.")

                Spacer(modifier = Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.repoMode == "new",
                        onClick = { appViewModel.updateUploader { it.copy(repoMode = "new") } },
                        label = { Text("Repositori Baru", fontSize = 12.sp) },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandDark, selectedLabelColor = BrandCream),
                        modifier = Modifier.testTag("mode_new_repo")
                    )
                    FilterChip(
                        selected = state.repoMode == "existing",
                        onClick = { appViewModel.updateUploader { it.copy(repoMode = "existing") } },
                        label = { Text("Repositori Yang Ada", fontSize = 12.sp) },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandDark, selectedLabelColor = BrandCream),
                        modifier = Modifier.testTag("mode_existing_repo")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (state.repoMode == "new") {
                    OutlinedTextField(
                        value = state.repoName,
                        onValueChange = { v -> appViewModel.updateUploader { it.copy(repoName = v) } },
                        label = { Text("Nama Repositori") },
                        placeholder = { Text("e.g. kruzen-cloud-app") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("repo_name_input")
                    )

                    if (state.repoName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        ThinHighlightText("Target: $normalizedRepoName")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = state.repoDescription,
                        onValueChange = { v -> appViewModel.updateUploader { it.copy(repoDescription = v) } },
                        label = { Text("Deskripsi Repositori (opsional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("repo_desc_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Privasi:", fontSize = 12.sp, color = BrandTextMuted)
                        FilterChip(
                            selected = state.isPrivate,
                            onClick = { appViewModel.updateUploader { it.copy(isPrivate = true) } },
                            label = { Text("Private", fontSize = 11.sp) },
                            shape = RoundedCornerShape(16.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandDark, selectedLabelColor = BrandCream)
                        )
                        FilterChip(
                            selected = !state.isPrivate,
                            onClick = { appViewModel.updateUploader { it.copy(isPrivate = false) } },
                            label = { Text("Public", fontSize = 11.sp) },
                            shape = RoundedCornerShape(16.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandDark, selectedLabelColor = BrandCream)
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = isExistingRepoDropdownExpanded,
                            onExpandedChange = { isExistingRepoDropdownExpanded = !isExistingRepoDropdownExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = state.selectedExistingRepo?.fullName ?: "Pilih repositori…",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Pilih Repositori") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExistingRepoDropdownExpanded) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                                    .testTag("select_existing_repo_dropdown")
                            )
                            ExposedDropdownMenu(
                                expanded = isExistingRepoDropdownExpanded,
                                onDismissRequest = { isExistingRepoDropdownExpanded = false }
                            ) {
                                state.existingRepos.forEach { repo ->
                                    DropdownMenuItem(
                                        text = { Text("${repo.fullName} ${if (repo.isPrivate) "(private)" else ""}") },
                                        onClick = {
                                            appViewModel.updateUploader {
                                                it.copy(selectedExistingRepo = repo, branchName = repo.defaultBranch)
                                            }
                                            isExistingRepoDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        RefreshIconButton(
                            isRefreshing = isRefreshingRepos,
                            contentDescription = "Refresh Daftar Repositori",
                            onClick = {
                                isRefreshingRepos = true
                                appViewModel.refreshUploaderRepos()
                                isRefreshingRepos = false
                                toastController.showSuccess("Daftar repositori diperbarui")
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = state.branchName,
                    onValueChange = { v -> appViewModel.updateUploader { it.copy(branchName = v) } },
                    label = { Text("Target Branch") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("target_branch_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = state.commitMessage,
                    onValueChange = { v -> appViewModel.updateUploader { it.copy(commitMessage = v) } },
                    label = { Text("Pesan Commit") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("commit_message_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val count = chosenEntries.size
                            val hasCode = chosenEntries.any { it.path.endsWith(".kt") || it.path.endsWith(".ts") || it.path.endsWith(".js") }
                            val smartMsg = if (hasCode) "feat: update core module and components ($count files)"
                            else "chore: sync project structure and documentation ($count files)"
                            appViewModel.updateUploader { it.copy(commitMessage = smartMsg) }
                            toastController.showSuccess("Saran commit diterapkan!")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Smart Commit", fontSize = 10.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val title = if (state.repoMode == "new") normalizedRepoName.ifEmpty { "kruzenlab-project" }
                            else state.selectedExistingRepo?.name ?: "kruzenlab-project"
                            val genReadme = """
                                # $title
                                > Managed with kruzenlab-uploader ⚡
                                
                                ## Overview
                                Automated Cloud Git batch sync containing ${chosenEntries.size} files.
                                Developed by DEV BAGJA / KruzenLab.
                            """.trimIndent()
                            appViewModel.updateUploader { cur ->
                                cur.copy(
                                    readmeChoice = "starter",
                                    entries = cur.entries.filter { it.path != "README.md" } + createEntry("README.md", genReadme)
                                )
                            }
                            toastController.showSuccess("README.md dibuat!")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Gen README", fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Ekstra & Template Berkas", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = BrandDark)
                Spacer(modifier = Modifier.height(6.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("README", fontSize = 10.sp, color = BrandTextMuted)
                        OptionChip(
                            label = if (state.readmeChoice == "starter") "Starter" else "None",
                            onClick = {
                                appViewModel.updateUploader {
                                    it.copy(readmeChoice = if (it.readmeChoice == "starter") "none" else "starter")
                                }
                            }
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("License", fontSize = 10.sp, color = BrandTextMuted)
                        OptionChip(
                            label = state.licenseChoice,
                            onClick = {
                                val next = when (state.licenseChoice) {
                                    "MIT" -> "Apache 2.0"
                                    "Apache 2.0" -> "GPL 3.0"
                                    "GPL 3.0" -> "None"
                                    else -> "MIT"
                                }
                                appViewModel.updateUploader { it.copy(licenseChoice = next) }
                            }
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(".gitignore", fontSize = 10.sp, color = BrandTextMuted)
                        OptionChip(
                            label = state.gitignoreChoice,
                            onClick = {
                                val next = when (state.gitignoreChoice) {
                                    "Node" -> "Java / Gradle"
                                    "Java / Gradle" -> "Python"
                                    "Python" -> "None"
                                    else -> "Node"
                                }
                                appViewModel.updateUploader { it.copy(gitignoreChoice = next) }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        appViewModel.updateUploader { it.copy(removeGitFolders = !it.removeGitFolders) }
                    }
                ) {
                    Checkbox(
                        checked = state.removeGitFolders,
                        onCheckedChange = { checked -> appViewModel.updateUploader { it.copy(removeGitFolders = checked) } },
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Buang folder .git internal saat ekstrak (ekstensi lain tetap 100% lolos)",
                        fontSize = 11.sp,
                        color = BrandDark
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Push Button triggering WorkManager Background Worker
                Button(
                    onClick = { showCommitConfirmDialog = true },
                    enabled = !state.isUploading && chosenEntries.isNotEmpty() && (state.repoMode != "new" || isRepoNameValid),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark, contentColor = BrandCream),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("push_to_github_btn")
                ) {
                    if (state.isUploading) {
                        CircularProgressIndicator(color = BrandCream, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(state.stageText.ifEmpty { "Upload Latar Belakang Berjalan…" }, fontSize = 12.sp, maxLines = 1)
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Push Background ke GitHub ⚡", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                if (state.isUploading || state.progressPercent > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { (state.progressPercent.coerceIn(0, 100)) / 100f },
                        color = BrandAccent,
                        trackColor = BrandPalePink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(state.stageText, fontSize = 11.sp, color = BrandRose, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${state.progressPercent}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BrandDark)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        AttributionFooter()
    }

    // Universal Editor for staged file (<= 6 MB) before pushing
    if (stagedEntryToEdit != null) {
        val target = stagedEntryToEdit!!
        val initialBytes = remember(target) {
            val stagingPath = target.localStagingPath
            if (!stagingPath.isNullOrBlank() && File(stagingPath).exists()) {
                try {
                    File(stagingPath).readBytes()
                } catch (_: Exception) {
                    ByteArray(0)
                }
            } else {
                try {
                    Base64.decode(target.contentBase64, Base64.DEFAULT)
                } catch (_: Exception) {
                    target.contentBase64.toByteArray()
                }
            }
        }
        UniversalFileEditorDialog(
            fileName = target.path.substringAfterLast('/'),
            filePath = "Staging/${target.path}",
            initialBytes = initialBytes,
            allowRemoteCommit = false,
            onDismiss = { stagedEntryToEdit = null },
            onSaveBytes = { updatedBytes, _ ->
                val stagingPath = target.localStagingPath
                if (!stagingPath.isNullOrBlank()) {
                    try {
                        File(stagingPath).writeBytes(updatedBytes)
                    } catch (_: Exception) {}
                }
                val encoded = if (stagingPath.isNullOrBlank()) {
                    Base64.encodeToString(updatedBytes, Base64.NO_WRAP)
                } else {
                    ""
                }
                appViewModel.updateUploader { cur ->
                    cur.copy(
                        entries = cur.entries.map {
                            if (it.path == target.path) it.copy(contentBase64 = encoded, size = updatedBytes.size.toLong())
                            else it
                        }
                    )
                }
                stagedEntryToEdit = null
                toastController.showSuccess("Berkas staging ${target.path} diperbarui")
            }
        )
    }

    // Streaming-safe Inspector for Large Files (> 6 MB)
    if (largeBinaryEntryToInspect != null) {
        val target = largeBinaryEntryToInspect!!
        val isZipOrApk = target.path.endsWith(".zip", true) ||
            target.path.endsWith(".apk", true) ||
            target.path.endsWith(".jar", true)
        var innerArchiveList by remember(target) { mutableStateOf<List<ZipInspectEntry>>(emptyList()) }

        LaunchedEffect(target) {
            val stagingPath = target.localStagingPath
            if (isZipOrApk && !stagingPath.isNullOrBlank()) {
                innerArchiveList = withContext(Dispatchers.IO) {
                    ZipExtractor.inspectZipFile(File(stagingPath))
                }
            }
        }

        AlertDialog(
            onDismissRequest = { largeBinaryEntryToInspect = null },
            title = { Text("Inspektur Berkas Besar", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandDark)
                    ThinHighlightText("Ukuran: ${ZipExtractor.formatSize(target.size)} · Mode Streaming Disk (aman tanpa muat penuh ke RAM)")
                    if (isZipOrApk && innerArchiveList.isNotEmpty()) {
                        ThinHighlightText("Daftar isi arsip (${innerArchiveList.size} entri):")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .background(BrandCream, RoundedCornerShape(10.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            innerArchiveList.take(200).forEach { entry ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(entry.path, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(ZipExtractor.formatSize(entry.size), fontSize = 10.sp, color = BrandTextMuted)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { largeBinaryEntryToInspect = null }, colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) {
                    Text("Tutup")
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = BrandCardBg
        )
    }

    // Modal: Confirmation for Background Push
    if (showCommitConfirmDialog) {
        val targetName = if (state.repoMode == "new") normalizedRepoName else state.selectedExistingRepo?.fullName ?: "repo"
        UniversalConfirmDialog(
            title = "Konfirmasi Background Batch Push",
            message = "Push ${chosenEntries.size} berkas ke $targetName (branch: ${state.branchName}) via WorkManager & WakeLock anti-putus?",
            confirmText = "Push Sekarang",
            onConfirm = { executeBackgroundPush() },
            onDismiss = { showCommitConfirmDialog = false }
        )
    }

    // Success dialog
    if (state.lastSuccessResult != null) {
        val res = state.lastSuccessResult!!
        AlertDialog(
            onDismissRequest = { appViewModel.updateUploader { it.copy(lastSuccessResult = null) } },
            title = {
                Text(
                    text = "Commit Sukses! ⚡",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = BrandDark
                )
            },
            text = {
                Column {
                    Text("Berhasil mengirim ${res.fileCount} file ke ${res.fullName}.", fontSize = 13.sp, color = BrandDark)
                    Spacer(modifier = Modifier.height(6.dp))
                    ThinHighlightText("Commit SHA: ${res.commitSha?.take(8) ?: "unknown"}")
                    Spacer(modifier = Modifier.height(6.dp))
                    ThinHighlightText("Transaksi background atomic tree selesai & dicatat ke SQLite.")
                }
            },
            confirmButton = {
                Button(
                    onClick = { appViewModel.updateUploader { it.copy(lastSuccessResult = null) } },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Selesai")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        appViewModel.updateUploader { it.copy(lastSuccessResult = null) }
                        onNavigate(Screen.RepoExplorer)
                    }
                ) {
                    Text("Lihat di File Manager", color = BrandRose)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = BrandCardBg
        )
    }
}

@Composable
private fun SourceTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) BrandDark else BrandCream,
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
        modifier = Modifier.testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) BrandAccent else BrandRose,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) BrandCream else BrandDark
            )
        }
    }
}

@Composable
private fun OptionChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = BrandPalePink,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = BrandDark,
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 8.dp)
        )
    }
}

private fun createEntry(path: String, text: String): FileEntry {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return FileEntry(
        path = path,
        contentBase64 = base64,
        size = bytes.size.toLong(),
        isSelected = true
    )
}

private fun addGeneratedFiles(
    files: List<FileEntry>,
    repoName: String,
    description: String,
    readme: String,
    license: String,
    gitignore: String
): List<FileEntry> {
    val result = files.toMutableList()
    if (readme != "none" && files.none { it.path.equals("README.md", ignoreCase = true) }) {
        val content = "# $repoName\n\n${description.ifEmpty { "Managed with kruzenlab-uploader ⚡" }}\n"
        result.add(createEntry("README.md", content))
    }
    if (license != "None" && files.none { it.path.equals("LICENSE", ignoreCase = true) }) {
        val text = "MIT License\n\nCopyright (c) 2026 KruzenLab / DEV BAGJA\n\nPermission is hereby granted, free of charge..."
        result.add(createEntry("LICENSE", text))
    }
    if (gitignore != "None" && files.none { it.path == ".gitignore" }) {
        val template = when (gitignore) {
            "Java / Gradle" -> ".gradle/\nbuild/\n*.class\n.idea/\n"
            "Python" -> "__pycache__/\n*.py[cod]\n.venv/\nvenv/\n"
            else -> "node_modules/\n.env\ndist/\nbuild/\n"
        }
        result.add(createEntry(".gitignore", template))
    }
    return result
}
