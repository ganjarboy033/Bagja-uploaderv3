package com.example.xiyu.ui.screens

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.xiyu.data.model.CommitResult
import com.example.xiyu.data.model.FileEntry
import com.example.xiyu.data.model.GitHubRepo
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.data.repository.GitHubRepository
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.ZipExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

enum class SourceMode {
    ARCHIVE, SAMPLE, LINK, PASTE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    repository: GitHubRepository,
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val toastController = LocalToastController.current

    var sourceMode by remember { mutableStateOf(SourceMode.ARCHIVE) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var archiveName by remember { mutableStateOf<String?>(null) }
    var searchFilter by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(true) }

    // Target repository state
    var repoMode by remember { mutableStateOf<String>("new") } // "new" or "existing"
    var repoName by remember { mutableStateOf("") }
    var repoDescription by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }
    var branchName by remember { mutableStateOf("main") }
    var commitMessage by remember { mutableStateOf("Initial commit via kruzenlab-uploader ⚡") }

    var selectedRepo by remember { mutableStateOf<GitHubRepo?>(null) }
    var existingRepos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var isExistingRepoDropdownExpanded by remember { mutableStateOf(false) }

    // Options
    var readmeChoice by remember { mutableStateOf("starter") }
    var licenseChoice by remember { mutableStateOf("MIT") }
    var gitignoreChoice by remember { mutableStateOf("Node") }
    var removeGitFolders by remember { mutableStateOf(true) }

    // Link mode
    var linkUrl by remember { mutableStateOf("") }

    // Paste mode
    var pastePath by remember { mutableStateOf("README.md") }
    var pasteContent by remember { mutableStateOf("# kruzenlab-uploader Project\n\nUploaded via kruzenlab-uploader by DEV BAGJA / KruzenLab ⚡\n") }

    // Upload progress and result
    var isBusy by remember { mutableStateOf(false) }
    var stageText by remember { mutableStateOf("") }
    var progressPercent by remember { mutableStateOf(0) }
    var commitSuccessResult by remember { mutableStateOf<CommitResult?>(null) }

    // Confirmation Modal
    var showCommitConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(currentUser) {
        repository.getRepositories().onSuccess {
            existingRepos = it
            if (it.isNotEmpty() && selectedRepo == null) {
                selectedRepo = it.first()
            }
        }
    }

    val normalizedRepoName = remember(repoName) {
        repoName.trim().replace("\\s+".toRegex(), "-")
    }
    val isRepoNameValid = remember(normalizedRepoName) {
        normalizedRepoName.isNotEmpty() && normalizedRepoName.matches(Regex("^[A-Za-z0-9._-]{1,100}$"))
    }

    val chosenEntries = remember(entries) { entries.filter { it.isSelected } }
    val chosenTotalBytes = remember(chosenEntries) { chosenEntries.sumOf { it.size } }

    val visibleEntries = remember(entries, searchFilter) {
        if (searchFilter.isBlank()) entries
        else entries.filter { it.path.contains(searchFilter, ignoreCase = true) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isBusy = true
            stageText = "Extracting archive…"
            progressPercent = 10
            toastController.showInfo("Mengekstrak file ZIP...", isLoading = true)

            scope.launch {
                try {
                    val extracted = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Could not read file stream")
                        input.use { stream ->
                            ZipExtractor.extractZip(stream, removeGitFolders)
                        }
                    }
                    archiveName = uri.lastPathSegment ?: "project.zip"
                    entries = extracted
                    progressPercent = 100
                    stageText = ""
                    toastController.showSuccess("ZIP berhasil diekstrak (${extracted.size} files)")
                } catch (e: Exception) {
                    toastController.showError("Gagal mengekstrak ZIP: ${e.localizedMessage}")
                } finally {
                    isBusy = false
                }
            }
        }
    }

    fun executePush() {
        val owner = if (repoMode == "new") {
            currentUser?.login ?: "bagja_dev"
        } else {
            selectedRepo?.fullName?.substringBefore("/") ?: (currentUser?.login ?: "bagja_dev")
        }

        val targetRepoName = if (repoMode == "new") {
            normalizedRepoName
        } else {
            selectedRepo?.name ?: ""
        }

        if (targetRepoName.isBlank()) {
            toastController.showError("Pilih atau masukkan nama repositori target!")
            return
        }

        isBusy = true
        progressPercent = 0
        stageText = "Mempersiapkan transaksi commit..."
        toastController.showInfo("Menghubungkan ke GitHub Git Data API...", isLoading = true)

        scope.launch {
            try {
                if (repoMode == "new") {
                    stageText = "Membuat repositori $targetRepoName di GitHub…"
                    repository.createRepository(targetRepoName, repoDescription, isPrivate)
                }

                val finalFiles = addGeneratedFiles(
                    chosenEntries,
                    targetRepoName,
                    repoDescription,
                    readmeChoice,
                    licenseChoice,
                    gitignoreChoice
                )

                repository.commitAndPush(
                    owner = owner,
                    repo = targetRepoName,
                    branch = branchName,
                    message = commitMessage,
                    entries = finalFiles,
                    onProgress = { stage, pct ->
                        stageText = stage
                        progressPercent = pct
                    }
                ).fold(
                    onSuccess = { res ->
                        commitSuccessResult = res
                        toastController.showSuccess("Commit berhasil di-push ke GitHub!")
                    },
                    onFailure = { err ->
                        toastController.showError("Gagal commit: ${err.localizedMessage}")
                    }
                )
            } catch (e: Exception) {
                toastController.showError("Error: ${e.localizedMessage}")
            } finally {
                isBusy = false
            }
        }
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
            Text(
                text = "Kembali ke Dashboard",
                fontSize = 12.sp,
                color = BrandTextMuted
            )
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
        ThinHighlightText("Ekstrak ZIP lokal & commit langsung seluruh file dalam 1 commit tunggal.")

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
                // Source Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SourceTabButton(
                        label = "Archive",
                        icon = Icons.Default.CloudUpload,
                        isSelected = sourceMode == SourceMode.ARCHIVE,
                        onClick = { sourceMode = SourceMode.ARCHIVE },
                        testTag = "tab_source_archive"
                    )
                    SourceTabButton(
                        label = "Sample",
                        icon = Icons.Default.Folder,
                        isSelected = sourceMode == SourceMode.SAMPLE,
                        onClick = { sourceMode = SourceMode.SAMPLE },
                        testTag = "tab_source_sample"
                    )
                    SourceTabButton(
                        label = "Paste",
                        icon = Icons.Default.EditNote,
                        isSelected = sourceMode == SourceMode.PASTE,
                        onClick = { sourceMode = SourceMode.PASTE },
                        testTag = "tab_source_paste"
                    )
                    SourceTabButton(
                        label = "Link",
                        icon = Icons.Default.Link,
                        isSelected = sourceMode == SourceMode.LINK,
                        onClick = { sourceMode = SourceMode.LINK },
                        testTag = "tab_source_link"
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (sourceMode) {
                    SourceMode.ARCHIVE -> {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = BrandCream.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                androidx.compose.ui.graphics.SolidColor(BrandRose.copy(alpha = 0.6f))
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { filePickerLauncher.launch("application/zip") }
                                .padding(vertical = 12.dp)
                                .testTag("pick_archive_box")
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                        .background(BrandPalePink),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = null,
                                        tint = BrandRose,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = archiveName ?: "Pilih Arsip .ZIP",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = BrandDark
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (archiveName != null) {
                                        "${entries.size} files ready (${ZipExtractor.formatSize(chosenTotalBytes)})"
                                    } else {
                                        "Ketuk untuk memilih file ZIP dari penyimpanan perangkat"
                                    },
                                    fontSize = 11.sp,
                                    color = BrandTextMuted
                                )
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
                            Text(
                                text = "Muat Proyek Contoh (KruzenLab Starter)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = BrandDark
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            ThinHighlightText("Membuat berkas demo Kotlin/Compose dan struktur README.")
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    entries = listOf(
                                        createEntry("src/main/kotlin/App.kt", "package com.kruzenlab\n\nfun main() {\n    println(\"kruzenlab-uploader ready ⚡\")\n}\n"),
                                        createEntry("src/main/resources/config.json", "{\n  \"appName\": \"kruzenlab-uploader\",\n  \"author\": \"DEV BAGJA / KruzenLab\",\n  \"version\": \"1.0.0\"\n}\n"),
                                        createEntry("docs/architecture.md", "# KruzenLab Cloud Architecture\n\nAtomic Git tree commit engine by DEV BAGJA.\n"),
                                        createEntry("README.md", "# kruzenlab-uploader\n\nCloud Repository Manager & Batch Uploader.\n")
                                    )
                                    archiveName = "kruzenlab-starter.zip"
                                    toastController.showSuccess("Sample project loaded")
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
                                label = { Text("Path File (e.g., src/index.ts)") },
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
                                        entries = entries.filter { it.path != clean } + newEntry
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
                                        isBusy = true
                                        stageText = "Mengunduh arsip dari URL…"
                                        progressPercent = 20
                                        toastController.showInfo("Mengunduh arsip...", isLoading = true)
                                        scope.launch {
                                            try {
                                                val extracted = withContext(Dispatchers.IO) {
                                                    val url = URL(linkUrl)
                                                    url.openStream().use { stream ->
                                                        ZipExtractor.extractZip(stream, removeGitFolders)
                                                    }
                                                }
                                                entries = extracted
                                                archiveName = linkUrl.substringAfterLast("/")
                                                toastController.showSuccess("Arsip berhasil diunduh (${extracted.size} files)")
                                            } catch (e: Exception) {
                                                toastController.showError("Gagal download: ${e.localizedMessage}")
                                            } finally {
                                                isBusy = false
                                                stageText = ""
                                            }
                                        }
                                    }
                                },
                                enabled = !isBusy && linkUrl.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.testTag("fetch_link_btn")
                            ) {
                                Text("Unduh & Ekstrak", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Files to push Section
        if (entries.isNotEmpty()) {
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
                            .clickable { isExpanded = !isExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Files to push — ${chosenEntries.size} dari ${entries.size} (${ZipExtractor.formatSize(chosenTotalBytes)})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = BrandDark
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = BrandRose
                        )
                    }

                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { entries = entries.map { it.copy(isSelected = true) } }) {
                                    Text("Pilih Semua", fontSize = 11.sp, color = BrandRose)
                                }
                                TextButton(onClick = { entries = entries.map { it.copy(isSelected = false) } }) {
                                    Text("Bersihkan", fontSize = 11.sp, color = BrandTextMuted)
                                }
                            }

                            OutlinedTextField(
                                value = searchFilter,
                                onValueChange = { searchFilter = it },
                                placeholder = { Text("Filter file...", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .width(150.dp)
                                    .height(44.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .background(BrandCream.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .padding(8.dp)
                        ) {
                            visibleEntries.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            entries = entries.map {
                                                if (it.path == entry.path) it.copy(isSelected = !it.isSelected)
                                                else it
                                            }
                                        }
                                        .padding(vertical = 4.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = entry.isSelected,
                                        onCheckedChange = { checked ->
                                            entries = entries.map {
                                                if (it.path == entry.path) it.copy(isSelected = checked)
                                                else it
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
                Text(
                    text = "Konfigurasi Repositori Target",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = BrandDark
                )
                ThinHighlightText("Mendukung auto-init pada repositori baru tanpa memicu error 404/409.")

                Spacer(modifier = Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = repoMode == "new",
                        onClick = { repoMode = "new" },
                        label = { Text("Repositori Baru", fontSize = 12.sp) },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandDark,
                            selectedLabelColor = BrandCream
                        ),
                        modifier = Modifier.testTag("mode_new_repo")
                    )
                    FilterChip(
                        selected = repoMode == "existing",
                        onClick = { repoMode = "existing" },
                        label = { Text("Repositori Yang Ada", fontSize = 12.sp) },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandDark,
                            selectedLabelColor = BrandCream
                        ),
                        modifier = Modifier.testTag("mode_existing_repo")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (repoMode == "new") {
                    OutlinedTextField(
                        value = repoName,
                        onValueChange = { repoName = it },
                        label = { Text("Nama Repositori") },
                        placeholder = { Text("e.g. kruzen-cloud-app") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("repo_name_input")
                    )

                    if (repoName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        ThinHighlightText("Target: $normalizedRepoName")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = repoDescription,
                        onValueChange = { repoDescription = it },
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
                            selected = isPrivate,
                            onClick = { isPrivate = true },
                            label = { Text("Private", fontSize = 11.sp) },
                            shape = RoundedCornerShape(16.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandDark, selectedLabelColor = BrandCream)
                        )
                        FilterChip(
                            selected = !isPrivate,
                            onClick = { isPrivate = false },
                            label = { Text("Public", fontSize = 11.sp) },
                            shape = RoundedCornerShape(16.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandDark, selectedLabelColor = BrandCream)
                        )
                    }
                } else {
                    ExposedDropdownMenuBox(
                        expanded = isExistingRepoDropdownExpanded,
                        onExpandedChange = { isExistingRepoDropdownExpanded = !isExistingRepoDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedRepo?.fullName ?: "Pilih repositori…",
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
                            existingRepos.forEach { repo ->
                                DropdownMenuItem(
                                    text = { Text("${repo.fullName} ${if (repo.isPrivate) "(private)" else ""}") },
                                    onClick = {
                                        selectedRepo = repo
                                        branchName = repo.defaultBranch
                                        isExistingRepoDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Target Branch") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("target_branch_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Pesan Commit") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("commit_message_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // AI Smart Commit & README Generator Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val count = chosenEntries.size
                            val hasCode = chosenEntries.any { it.path.endsWith(".kt") || it.path.endsWith(".ts") || it.path.endsWith(".js") }
                            commitMessage = if (hasCode) "feat: update core module and components ($count files)"
                            else "chore: sync project structure and documentation ($count files)"
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
                            readmeChoice = "starter"
                            val genReadme = """
                                # ${if (repoMode == "new") normalizedRepoName.ifEmpty { "kruzenlab-project" } else selectedRepo?.name ?: "kruzenlab-project"}
                                > Managed with kruzenlab-uploader ⚡
                                
                                ## Overview
                                Automated Cloud Git batch sync containing ${chosenEntries.size} files.
                                Developed by DEV BAGJA / KruzenLab.
                            """.trimIndent()
                            entries = entries.filter { it.path != "README.md" } + createEntry("README.md", genReadme)
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

                // Extras: README, License, .gitignore
                Text("Ekstra & Template Berkas", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = BrandDark)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("README", fontSize = 10.sp, color = BrandTextMuted)
                        OptionChip(
                            label = if (readmeChoice == "starter") "Starter" else "None",
                            onClick = { readmeChoice = if (readmeChoice == "starter") "none" else "starter" }
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("License", fontSize = 10.sp, color = BrandTextMuted)
                        OptionChip(
                            label = licenseChoice,
                            onClick = {
                                licenseChoice = when (licenseChoice) {
                                    "MIT" -> "Apache 2.0"
                                    "Apache 2.0" -> "GPL 3.0"
                                    "GPL 3.0" -> "None"
                                    else -> "MIT"
                                }
                            }
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(".gitignore", fontSize = 10.sp, color = BrandTextMuted)
                        OptionChip(
                            label = gitignoreChoice,
                            onClick = {
                                gitignoreChoice = when (gitignoreChoice) {
                                    "Node" -> "Java / Gradle"
                                    "Java / Gradle" -> "Python"
                                    "Python" -> "None"
                                    else -> "Node"
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { removeGitFolders = !removeGitFolders }
                ) {
                    Checkbox(
                        checked = removeGitFolders,
                        onCheckedChange = { removeGitFolders = it },
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Filter folder .git / cache otomatis",
                        fontSize = 12.sp,
                        color = BrandDark
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Push Button with Confirmation Dialog Trigger
                Button(
                    onClick = { showCommitConfirmDialog = true },
                    enabled = !isBusy && chosenEntries.isNotEmpty() && (repoMode != "new" || isRepoNameValid),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandDark,
                        contentColor = BrandCream
                    ),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("push_to_github_btn")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            color = BrandCream,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stageText.ifEmpty { "Memproses…" }, fontSize = 12.sp)
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Push Commit ke GitHub ⚡", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                if (isBusy || progressPercent > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progressPercent / 100f },
                        color = BrandAccent,
                        trackColor = BrandPalePink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stageText, fontSize = 11.sp, color = BrandRose)
                        Text("$progressPercent%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BrandDark)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Attribution Footer
        AttributionFooter()
    }

    // Modal: Confirmation for Commit
    if (showCommitConfirmDialog) {
        val targetName = if (repoMode == "new") normalizedRepoName else selectedRepo?.fullName ?: "repo"
        UniversalConfirmDialog(
            title = "Konfirmasi Batch Commit",
            message = "Apakah Anda yakin ingin mem-push ${chosenEntries.size} berkas ke $targetName (branch: $branchName)?",
            confirmText = "Push Sekarang",
            onConfirm = { executePush() },
            onDismiss = { showCommitConfirmDialog = false }
        )
    }

    // Success dialog
    if (commitSuccessResult != null) {
        val res = commitSuccessResult!!
        AlertDialog(
            onDismissRequest = { commitSuccessResult = null },
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
                    Text(
                        text = "Berhasil mengirim ${res.fileCount} file ke ${res.fullName}.",
                        fontSize = 13.sp,
                        color = BrandDark
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ThinHighlightText("Commit SHA: ${res.commitSha?.take(8) ?: "unknown"}")
                    Spacer(modifier = Modifier.height(6.dp))
                    ThinHighlightText("Transaksi atomic tree commit selesai tanpa error.")
                }
            },
            confirmButton = {
                Button(
                    onClick = { commitSuccessResult = null },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Selesai")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        commitSuccessResult = null
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) BrandAccent else BrandRose,
                modifier = Modifier.size(14.dp)
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
private fun OptionChip(
    label: String,
    onClick: () -> Unit
) {
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
        val year = 2026
        val text = "MIT License\n\nCopyright (c) $year KruzenLab / DEV BAGJA\n\nPermission is hereby granted, free of charge..."
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
