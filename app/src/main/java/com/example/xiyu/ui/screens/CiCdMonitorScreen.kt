package com.example.xiyu.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.xiyu.data.model.*
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.WorkflowTemplateGenerator
import com.example.xiyu.util.ZipExtractor
import com.example.xiyu.viewmodel.AppStateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.time.Duration
import java.time.Instant

@Composable
fun CiCdMonitorScreen(
    appViewModel: AppStateViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastController = LocalToastController.current
    val state by appViewModel.cicdState.collectAsState()

    var repoDropdownExpanded by remember { mutableStateOf(false) }
    var showGenerateWorkflowDialog by remember { mutableStateOf(false) }
    var showCommitWorkflowConfirm by remember { mutableStateOf(false) }
    var isCommittingWorkflow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appViewModel.ensureCiCdInitialized()
    }

    // Automatic cacheDir cleanup whenever user exits CI/CD Monitor
    DisposableEffect(Unit) {
        onDispose {
            appViewModel.clearCiCdCache()
        }
    }

    val selectedRepo = state.selectedRepo
    val generatedYaml = remember(selectedRepo) {
        WorkflowTemplateGenerator.generateAndroidBuildWorkflow(selectedRepo?.defaultBranch ?: "main")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BrandCream)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("cicd_monitor_screen")
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "KRUZENLAB CI/CD MONITOR ⚡",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandRose,
                    letterSpacing = 1.4.sp
                )
                Text(
                    text = "Actions & Artifact Monitor",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = BrandDark
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThinHighlightText("Error highlight Gradle · Ekstrak APK & Log otomatis")
                    if (state.isAutoPolling) {
                        ThinHighlightBadge("Polling 5s", badgeColor = Color(0xFFF59E0B))
                    }
                }
            }

            RefreshIconButton(
                isRefreshing = state.isLoadingRuns,
                contentDescription = "Refresh Runs CI/CD",
                onClick = {
                    appViewModel.refreshCiCdRuns(
                        onError = { toastController.showError(it) },
                        onSuccess = { toastController.showSuccess("Riwayat CI/CD diperbarui") }
                    )
                },
                modifier = Modifier.testTag("refresh_cicd_runs_btn")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Repo Selector + Workflow Generator Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    onClick = { repoDropdownExpanded = true },
                    shape = RoundedCornerShape(12.dp),
                    color = BrandCardBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = BrandRose, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = selectedRepo?.fullName ?: "Pilih Repositori...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BrandDark,
                                maxLines = 1
                            )
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = BrandDark)
                    }
                }

                DropdownMenu(
                    expanded = repoDropdownExpanded,
                    onDismissRequest = { repoDropdownExpanded = false }
                ) {
                    state.repos.forEach { repo ->
                        DropdownMenuItem(
                            text = { Text(repo.fullName, fontSize = 13.sp) },
                            onClick = {
                                repoDropdownExpanded = false
                                appViewModel.selectCiCdRepo(repo)
                            }
                        )
                    }
                }
            }

            Button(
                onClick = { showGenerateWorkflowDialog = true },
                enabled = selectedRepo != null,
                colors = ButtonDefaults.buttonColors(containerColor = BrandDark, contentColor = BrandCream),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("generate_workflow_btn")
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("Gen Workflow", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Runs Summary Strip
        val successCount = state.runs.count { it.conclusion == "success" }
        val failCount = state.runs.count { it.conclusion == "failure" }
        val activeCount = state.runs.count { it.status == "queued" || it.status == "in_progress" }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThinHighlightBadge("Total: ${state.runs.size}")
            ThinHighlightBadge("Sukses: $successCount", badgeColor = Color(0xFF16A34A))
            ThinHighlightBadge("Gagal: $failCount", badgeColor = Color(0xFFDC2626))
            if (activeCount > 0) {
                ThinHighlightBadge("Jalan: $activeCount", badgeColor = Color(0xFFF59E0B))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (state.isLoadingRuns && state.runs.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandRose)
            }
        } else if (state.runs.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = BrandRose, modifier = Modifier.size(46.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Belum ada riwayat workflow run.", fontWeight = FontWeight.SemiBold, color = BrandDark)
                    Spacer(modifier = Modifier.height(4.dp))
                    ThinHighlightText("Gunakan tombol \"Gen Workflow\" di atas untuk membuat .github/workflows/build.yml otomatis.")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.runs, key = { it.id }) { run ->
                    CiCdRunCard(
                        run = run,
                        onClick = { appViewModel.openCiCdRunDetail(run) }
                    )
                }
            }
        }

        AttributionFooter()
    }

    // Run Detail Modal (Jobs + Steps + Gradle Error Logs + Auto-Extract Artifacts)
    if (state.selectedRun != null && selectedRepo != null) {
        CiCdRunDetailDialog(
            appViewModel = appViewModel,
            owner = selectedRepo.fullName.substringBefore('/'),
            repoName = selectedRepo.name,
            run = state.selectedRun!!,
            onDismiss = { appViewModel.closeCiCdRunDetail() }
        )
    }

    // Workflow Generator Preview Dialog
    if (showGenerateWorkflowDialog && selectedRepo != null) {
        AlertDialog(
            onDismissRequest = { if (!isCommittingWorkflow) showGenerateWorkflowDialog = false },
            title = {
                Text("Generator Workflow CI/CD Android", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = BrandDark)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThinHighlightText(
                        "File: .github/workflows/build.yml · Branch: ${selectedRepo.defaultBranch}\n" +
                            "Mengunggah gradle-build.log & *.apk dengan if: always() meskipun build gagal."
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .background(BrandDark, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = generatedYaml,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = BrandCream
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showCommitWorkflowConfirm = true },
                    enabled = !isCommittingWorkflow,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark)
                ) {
                    if (isCommittingWorkflow) {
                        CircularProgressIndicator(color = BrandCream, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Commit ke Repo", fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showGenerateWorkflowDialog = false }, enabled = !isCommittingWorkflow) {
                    Text("Batal", fontSize = 12.sp)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = BrandCardBg
        )
    }

    if (showCommitWorkflowConfirm && selectedRepo != null) {
        UniversalConfirmDialog(
            title = "Commit .github/workflows/build.yml?",
            message = "File workflow dengan auto-upload gradle-build.log & APK (if: always()) akan di-commit ke ${selectedRepo.fullName} (branch ${selectedRepo.defaultBranch}).",
            confirmText = "Commit Workflow",
            onConfirm = {
                isCommittingWorkflow = true
                val owner = selectedRepo.fullName.substringBefore('/')
                val branch = selectedRepo.defaultBranch
                scope.launch {
                    toastController.showInfo("Menyimpan .github/workflows/build.yml ke GitHub...", isLoading = true)
                    val existingSha = appViewModel.repository
                        .getSingleFileContent(owner, selectedRepo.name, ".github/workflows/build.yml", branch)
                        .getOrNull()?.sha
                    val encoded = Base64.encodeToString(generatedYaml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    appViewModel.repository.createOrUpdateFile(
                        owner = owner,
                        repo = selectedRepo.name,
                        path = ".github/workflows/build.yml",
                        contentBase64 = encoded,
                        commitMessage = "ci: add Android build workflow with if: always() log & APK artifacts",
                        sha = existingSha,
                        branch = branch
                    ).fold(
                        onSuccess = {
                            isCommittingWorkflow = false
                            showGenerateWorkflowDialog = false
                            toastController.showSuccess("Workflow build.yml berhasil di-commit ke GitHub!")
                            appViewModel.refreshCiCdRuns()
                        },
                        onFailure = { err ->
                            isCommittingWorkflow = false
                            toastController.showError("Gagal commit workflow: ${err.localizedMessage}")
                        }
                    )
                }
            },
            onDismiss = { showCommitWorkflowConfirm = false }
        )
    }
}

@Composable
private fun CiCdRunCard(
    run: WorkflowRun,
    onClick: () -> Unit
) {
    val isSuccess = run.conclusion == "success"
    val isFailure = run.conclusion == "failure"
    val isActive = run.status == "queued" || run.status == "in_progress"

    val statusLabel = when {
        isSuccess -> "Success"
        isFailure -> "Failed"
        isActive -> "In Progress"
        else -> (run.conclusion ?: run.status ?: "Completed").replaceFirstChar { it.uppercase() }
    }
    val statusColor = when {
        isSuccess -> Color(0xFF16A34A)
        isFailure -> Color(0xFFDC2626)
        else -> Color(0xFFD97706)
    }
    val durationText = remember(run.createdAt, run.updatedAt, run.status) {
        computeIsoDuration(run.createdAt, run.updatedAt, isActive)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = BrandCardBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cicd_run_card_${run.id}")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    isSuccess -> Icons.Default.CheckCircle
                    isFailure -> Icons.Default.Cancel
                    else -> Icons.Default.HourglassBottom
                },
                contentDescription = statusLabel,
                tint = statusColor,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = run.name ?: "Workflow Run #${run.id}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandDark,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ThinHighlightBadge(statusLabel, badgeColor = statusColor)
                    ThinHighlightBadge(durationText)
                    ThinHighlightText("Branch: ${run.headBranch ?: "main"}")
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Buka Detail", tint = BrandRose)
        }
    }
}

@Composable
private fun CiCdRunDetailDialog(
    appViewModel: AppStateViewModel,
    owner: String,
    repoName: String,
    run: WorkflowRun,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastController = LocalToastController.current
    val state by appViewModel.cicdState.collectAsState()

    var detailTab by remember { mutableIntStateOf(0) } // 0: Jobs & Steps, 1: Error Logs, 2: Artifacts & Files
    var onlyErrorsFilter by remember { mutableStateOf(false) }
    var logSearchQuery by remember { mutableStateOf("") }
    var previewTextFile by remember { mutableStateOf<Pair<ExtractedArtifactFile, String>?>(null) }
    var pendingSafApkFile by remember { mutableStateOf<ExtractedArtifactFile?>(null) }

    val safApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val fileObj = pendingSafApkFile
        pendingSafApkFile = null
        if (uri != null && fileObj != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val src = File(fileObj.localCachePath)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(src).use { inp -> inp.copyTo(out) }
                        }
                    }
                    toastController.showSuccess("APK ${fileObj.name} berhasil disimpan!")
                } catch (e: Exception) {
                    toastController.showError("Gagal menyimpan APK: ${e.localizedMessage}")
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f)
                .testTag("cicd_run_detail_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = BrandCream,
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = run.name ?: "Build Run #${run.id}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = BrandDark,
                            maxLines = 1
                        )
                        val dur = computeIsoDuration(run.createdAt, run.updatedAt, run.status != "completed")
                        ThinHighlightText("Run #${run.id} · ${run.headBranch ?: "main"} · Durasi: $dur · Jobs: ${state.jobs.size}")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = BrandDark)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tabs inside Run Detail
                TabRow(
                    selectedTabIndex = detailTab,
                    containerColor = BrandCardBg,
                    contentColor = BrandDark
                ) {
                    Tab(
                        selected = detailTab == 0,
                        onClick = { detailTab = 0 },
                        text = { Text("Jobs (${state.jobs.size})", fontSize = 11.sp) }
                    )
                    Tab(
                        selected = detailTab == 1,
                        onClick = { detailTab = 1 },
                        text = { Text("Error Logs", fontSize = 11.sp) }
                    )
                    Tab(
                        selected = detailTab == 2,
                        onClick = { detailTab = 2 },
                        text = { Text("Artifacts (${state.artifacts.size})", fontSize = 11.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (detailTab) {
                    0 -> {
                        // Jobs & Steps list
                        if (state.isLoadingJobs) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = BrandRose)
                            }
                        } else if (state.jobs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                ThinHighlightText("Tidak ada rincian job ditemukan untuk run #${run.id}.")
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(state.jobs, key = { it.id }) { job ->
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = BrandCardBg,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(job.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BrandDark)
                                                    val jDur = computeIsoDuration(job.startedAt, job.completedAt, job.status != "completed")
                                                    ThinHighlightText("${job.conclusion ?: job.status ?: "running"} · $jDur · ${job.steps.size} steps")
                                                }
                                                Button(
                                                    onClick = {
                                                        appViewModel.loadCiCdJobLog(job.id) { toastController.showError(it) }
                                                        detailTab = 1
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(34.dp)
                                                ) {
                                                    Text("Lihat Log", fontSize = 10.sp)
                                                }
                                            }
                                            if (job.steps.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                HorizontalDivider(color = BrandBorder)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                job.steps.forEach { step ->
                                                    val stepFail = step.conclusion == "failure"
                                                    val stepOk = step.conclusion == "success"
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = when {
                                                                stepOk -> Icons.Default.CheckCircle
                                                                stepFail -> Icons.Default.Error
                                                                else -> Icons.Default.Schedule
                                                            },
                                                            contentDescription = null,
                                                            tint = when {
                                                                stepOk -> Color(0xFF16A34A)
                                                                stepFail -> Color(0xFFDC2626)
                                                                else -> Color(0xFFD97706)
                                                            },
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "${step.number}. ${step.name}",
                                                            fontSize = 11.sp,
                                                            color = if (stepFail) Color(0xFFDC2626) else BrandDark,
                                                            fontWeight = if (stepFail) FontWeight.Bold else FontWeight.Normal,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Error Logs Viewer with Gradle Error Highlight & Copy
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = logSearchQuery,
                                    onValueChange = { logSearchQuery = it },
                                    placeholder = { Text("Cari di log job...", fontSize = 11.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = onlyErrorsFilter,
                                    onClick = { onlyErrorsFilter = !onlyErrorsFilter },
                                    label = { Text("Error Saja", fontSize = 10.sp) }
                                )
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Gradle Job Logs", state.rawJobLog))
                                    toastController.showSuccess("Log job disalin ke clipboard!")
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Salin Log", tint = BrandDark)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            val displayText = remember(state.rawJobLog, onlyErrorsFilter, logSearchQuery) {
                                var lines = state.rawJobLog.lines()
                                if (onlyErrorsFilter) {
                                    lines = lines.filter { isGradleErrorLine(it) }
                                }
                                if (logSearchQuery.isNotBlank()) {
                                    lines = lines.filter { it.contains(logSearchQuery, ignoreCase = true) }
                                }
                                lines.joinToString("\n").ifBlank {
                                    if (onlyErrorsFilter) "Tidak ditemukan baris error Gradle pada log ini."
                                    else "Belum ada log yang dimuat."
                                }
                            }

                            val highlighted = remember(displayText) { highlightGradleLogLines(displayText) }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(BrandDark, RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                if (state.isLoadingJobLog) {
                                    CircularProgressIndicator(color = BrandAccent, modifier = Modifier.align(Alignment.Center))
                                } else {
                                    Text(
                                        text = highlighted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .horizontalScroll(rememberScrollState())
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        // Artifacts -> Auto-Extract in cacheDir -> Click APK (MediaStore.Downloads) / Click Log (Reader)
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Text("Daftar Artifacts (${state.artifacts.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BrandDark)
                            ThinHighlightText("Ketuk artifact untuk mengunduh ZIP & mengekstrak seluruh isinya secara otomatis di cache.")
                            Spacer(modifier = Modifier.height(8.dp))

                            if (state.artifacts.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = BrandCardBg,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Tidak ada artifact pada run #$owner/$repoName.",
                                        fontSize = 12.sp,
                                        color = BrandTextMuted,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            } else {
                                state.artifacts.forEach { art ->
                                    val isDownloading = state.activeArtifactId == art.id
                                    Surface(
                                        onClick = {
                                            if (!art.expired && state.activeArtifactId == null) {
                                                toastController.showInfo("Mengunduh & mengekstrak ${art.name}.zip...", isLoading = true)
                                                appViewModel.extractCiCdArtifactToCache(
                                                    artifact = art,
                                                    onError = { toastController.showError(it) },
                                                    onSuccess = { files ->
                                                        toastController.showSuccess("${art.name} diekstrak (${files.size} berkas)!")
                                                    }
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = BrandCardBg,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.FolderZip, null, tint = BrandRose, modifier = Modifier.size(22.dp))
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(art.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BrandDark)
                                                ThinHighlightText(
                                                    "${ZipExtractor.formatSize(art.sizeInBytes)} · " +
                                                        if (art.expired) "Kadaluarsa" else "Ketuk untuk ekstrak ke layar"
                                                )
                                            }
                                            if (isDownloading) {
                                                CircularProgressIndicator(color = BrandRose, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Default.Unarchive, contentDescription = "Ekstrak Artifact", tint = BrandDark)
                                            }
                                        }
                                    }
                                }
                            }

                            if (state.extractedArtifactFiles.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = BrandBorder)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Hasil Ekstraksi: ${state.extractedFromArtifactName ?: "Artifact"} (${state.extractedArtifactFiles.size} file)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = BrandDark
                                )
                                ThinHighlightText("Klik .apk untuk simpan langsung ke folder Download HP; klik .log/.txt untuk baca isi.")
                                Spacer(modifier = Modifier.height(8.dp))

                                state.extractedArtifactFiles.forEach { fileItem ->
                                    Surface(
                                        onClick = {
                                            when {
                                                fileItem.isApk -> {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                        scope.launch {
                                                            saveApkToPublicDownloads(context, File(fileItem.localCachePath), fileItem.name).fold(
                                                                onSuccess = {
                                                                    toastController.showSuccess("APK ${fileItem.name} disimpan ke folder Download HP! Siap diinstal.")
                                                                },
                                                                onFailure = { err ->
                                                                    toastController.showError("Gagal simpan APK: ${err.localizedMessage}")
                                                                }
                                                            )
                                                        }
                                                    } else {
                                                        pendingSafApkFile = fileItem
                                                        safApkLauncher.launch(fileItem.name)
                                                    }
                                                }
                                                fileItem.isTextLike -> {
                                                    scope.launch {
                                                        val txt = withContext(Dispatchers.IO) {
                                                            val f = File(fileItem.localCachePath)
                                                            if (f.exists()) f.readText() else ""
                                                        }
                                                        previewTextFile = fileItem to txt
                                                    }
                                                }
                                                else -> {
                                                    toastController.showInfo("Berkas ${fileItem.name} (${ZipExtractor.formatSize(fileItem.sizeBytes)}) berada di cache ekstrak.")
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = BrandPalePink.copy(alpha = 0.45f),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, BrandBorder),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = when {
                                                    fileItem.isApk -> Icons.Default.Android
                                                    fileItem.isTextLike -> Icons.Default.Description
                                                    else -> Icons.Default.InsertDriveFile
                                                },
                                                contentDescription = null,
                                                tint = if (fileItem.isApk) Color(0xFF16A34A) else BrandDark,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(fileItem.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandDark)
                                                ThinHighlightText("${fileItem.relativePath} · ${ZipExtractor.formatSize(fileItem.sizeBytes)}")
                                            }
                                            ThinHighlightBadge(
                                                text = when {
                                                    fileItem.isApk -> "Simpan APK"
                                                    fileItem.isTextLike -> "Baca Log"
                                                    else -> "File"
                                                },
                                                badgeColor = if (fileItem.isApk) Color(0xFF16A34A) else BrandRose
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Extracted Log / Text Reader Dialog with Gradle Error Highlights + Copy
    if (previewTextFile != null) {
        val (fileMeta, contentText) = previewTextFile!!
        AlertDialog(
            onDismissRequest = { previewTextFile = null },
            title = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(fileMeta.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(fileMeta.name, contentText))
                        toastController.showSuccess("Isi ${fileMeta.name} disalin!")
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Salin Teks", tint = BrandDark)
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 380.dp)
                        .background(BrandDark, RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = remember(contentText) { highlightGradleLogLines(contentText) },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                Button(onClick = { previewTextFile = null }, colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) {
                    Text("Tutup")
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = BrandCardBg
        )
    }
}

private fun isGradleErrorLine(line: String): Boolean {
    return line.contains("FAILURE:", ignoreCase = true) ||
        line.contains("Execution failed", ignoreCase = true) ||
        line.contains("Exception", ignoreCase = false) ||
        line.contains("e: ", ignoreCase = false) ||
        line.contains("ERROR:", ignoreCase = true) ||
        line.contains("* What went wrong:", ignoreCase = true)
}

private fun highlightGradleLogLines(raw: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = raw.split("\n")
        lines.forEachIndexed { idx, line ->
            when {
                line.contains("FAILURE:", true) || line.contains("Execution failed", true) || line.contains("* What went wrong:", true) -> {
                    withStyle(SpanStyle(color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)) {
                        append(line)
                    }
                }
                line.contains("e: ") || line.contains("Exception") || line.contains("ERROR:", true) -> {
                    withStyle(SpanStyle(color = Color(0xFFFBBF24), fontWeight = FontWeight.SemiBold)) {
                        append(line)
                    }
                }
                line.contains("BUILD SUCCESSFUL", true) -> {
                    withStyle(SpanStyle(color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold)) {
                        append(line)
                    }
                }
                else -> {
                    withStyle(SpanStyle(color = BrandCream)) {
                        append(line)
                    }
                }
            }
            if (idx < lines.size - 1) append("\n")
        }
    }
}

private fun computeIsoDuration(startIso: String?, endIso: String?, isActive: Boolean): String {
    if (isActive) return "Berjalan..."
    if (startIso.isNullOrBlank() || endIso.isNullOrBlank()) return "-"
    return try {
        val s = Instant.parse(startIso)
        val e = Instant.parse(endIso)
        val secs = Duration.between(s, e).seconds.coerceAtLeast(0)
        val mins = secs / 60
        val rem = secs % 60
        if (mins > 0) "${mins}m ${rem}s" else "${rem}s"
    } catch (_: Exception) {
        "-"
    }
}

private suspend fun saveApkToPublicDownloads(
    context: Context,
    sourceFile: File,
    fileName: String
): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        if (!sourceFile.exists()) {
            return@withContext Result.failure(IllegalStateException("Berkas APK sementara tidak ditemukan"))
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            null
        } ?: return@withContext Result.failure(IllegalStateException("Gagal membuat entri MediaStore Downloads"))

        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(sourceFile).use { inp ->
                inp.copyTo(out, bufferSize = 16 * 1024)
            }
        } ?: return@withContext Result.failure(IllegalStateException("Stream output MediaStore gagal dibuka"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
