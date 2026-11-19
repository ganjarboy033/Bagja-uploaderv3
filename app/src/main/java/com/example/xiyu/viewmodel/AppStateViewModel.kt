package com.example.xiyu.viewmodel

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.example.xiyu.data.model.*
import com.example.xiyu.data.repository.GitHubRepository
import com.example.xiyu.worker.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

data class ExplorerUiState(
    val repos: List<GitHubRepo> = emptyList(),
    val selectedRepo: GitHubRepo? = null,
    val selectedBranch: String = "main",
    val currentPath: String = "",
    val folderCache: Map<String, List<RepoContentItem>> = emptyMap(),
    val isLoading: Boolean = false,
    val hasLoadedRepos: Boolean = false
) {
    val activeCacheKey: String
        get() {
            val repoName = selectedRepo?.fullName ?: return ""
            return "$repoName@$selectedBranch:$currentPath"
        }

    val currentContents: List<RepoContentItem>
        get() = folderCache[activeCacheKey] ?: emptyList()
}

data class UploaderUiState(
    val sourceModeName: String = "ARCHIVE",
    val archiveMode: UploadArchiveMode = UploadArchiveMode.ATOMIC_EXTRACT,
    val entries: List<FileEntry> = emptyList(),
    val archiveName: String? = null,
    val selectedFolderName: String? = null,
    val includeParentFolderInPath: Boolean = false,
    val totalStagedBytes: Long = 0L,
    val searchFilter: String = "",
    val isFilesExpanded: Boolean = true,
    val repoMode: String = "new",
    val repoName: String = "",
    val repoDescription: String = "",
    val isPrivate: Boolean = true,
    val branchName: String = "main",
    val commitMessage: String = "Initial commit via kruzenlab-uploader ⚡",
    val selectedExistingRepo: GitHubRepo? = null,
    val existingRepos: List<GitHubRepo> = emptyList(),
    val readmeChoice: String = "starter",
    val licenseChoice: String = "MIT",
    val gitignoreChoice: String = "Node",
    val removeGitFolders: Boolean = false,
    val isUploading: Boolean = false,
    val stageText: String = "",
    val progressPercent: Int = 0,
    val activeBatchWorkId: String? = null,
    val lastSuccessResult: CommitResult? = null
)

data class WorkflowsUiState(
    val repos: List<GitHubRepo> = emptyList(),
    val selectedRepo: GitHubRepo? = null,
    val selectedTab: Int = 0, // 0: Releases, 1: Actions CI/CD, 2: Branches
    val releases: List<GitHubRelease> = emptyList(),
    val workflowRuns: List<WorkflowRun> = emptyList(),
    val branches: List<GitHubBranch> = emptyList(),
    val isLoading: Boolean = false,
    val isAutoPolling: Boolean = false,
    val hasInitialized: Boolean = false
)

data class CiCdMonitorUiState(
    val repos: List<GitHubRepo> = emptyList(),
    val selectedRepo: GitHubRepo? = null,
    val runs: List<WorkflowRun> = emptyList(),
    val isLoadingRuns: Boolean = false,
    val isAutoPolling: Boolean = false,
    val hasInitialized: Boolean = false,
    val selectedRun: WorkflowRun? = null,
    val jobs: List<WorkflowJob> = emptyList(),
    val selectedJobId: Long? = null,
    val rawJobLog: String = "",
    val isLoadingJobs: Boolean = false,
    val isLoadingJobLog: Boolean = false,
    val artifacts: List<WorkflowArtifact> = emptyList(),
    val activeArtifactId: Long? = null,
    val extractedArtifactFiles: List<ExtractedArtifactFile> = emptyList(),
    val extractedFromArtifactName: String? = null
)

data class DashboardUiState(
    val repos: List<GitHubRepo> = emptyList(),
    val isLoading: Boolean = false,
    val hasInitialized: Boolean = false
)

class AppStateViewModel(application: Application) : AndroidViewModel(application) {

    val repository = GitHubRepository(application.applicationContext)
    private val workManager = WorkManager.getInstance(application.applicationContext)

    private val _dashboardState = MutableStateFlow(DashboardUiState())
    val dashboardState: StateFlow<DashboardUiState> = _dashboardState.asStateFlow()

    private val _explorerState = MutableStateFlow(ExplorerUiState())
    val explorerState: StateFlow<ExplorerUiState> = _explorerState.asStateFlow()

    private val _uploaderState = MutableStateFlow(UploaderUiState())
    val uploaderState: StateFlow<UploaderUiState> = _uploaderState.asStateFlow()

    private val _workflowsState = MutableStateFlow(WorkflowsUiState())
    val workflowsState: StateFlow<WorkflowsUiState> = _workflowsState.asStateFlow()

    private val _cicdState = MutableStateFlow(CiCdMonitorUiState())
    val cicdState: StateFlow<CiCdMonitorUiState> = _cicdState.asStateFlow()

    val downloads: StateFlow<List<DownloadRecord>> = repository.dbHelper.downloads

    private var pollingJob: Job? = null
    private var cicdPollingJob: Job? = null

    // Dashboard Manual Refresh (Zero auto-reset on tab revisit)
    fun ensureDashboardInitialized() {
        if (!_dashboardState.value.hasInitialized) {
            refreshDashboard()
        }
    }

    fun refreshDashboard(onError: (String) -> Unit = {}, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _dashboardState.update { it.copy(isLoading = true) }
            repository.getRepositories().fold(
                onSuccess = { list ->
                    _dashboardState.update {
                        it.copy(repos = list, isLoading = false, hasInitialized = true)
                    }
                    repository.fetchRateLimit()
                    onSuccess()
                },
                onFailure = { err ->
                    _dashboardState.update { it.copy(isLoading = false) }
                    onError(err.localizedMessage ?: "Gagal memuat repositori")
                }
            )
        }
    }

    // Explorer State & Cache (Zero-Reset Directory Path)
    fun ensureExplorerInitialized() {
        if (!_explorerState.value.hasLoadedRepos) {
            refreshExplorerRepos(resetPath = false)
        }
    }

    fun refreshExplorerRepos(resetPath: Boolean = false, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            _explorerState.update { it.copy(isLoading = true) }
            repository.getRepositories().fold(
                onSuccess = { list ->
                    val currentSelected = _explorerState.value.selectedRepo
                    val targetRepo = if (currentSelected != null && list.any { it.fullName == currentSelected.fullName }) {
                        list.first { it.fullName == currentSelected.fullName }
                    } else {
                        list.firstOrNull()
                    }
                    val targetBranch = if (currentSelected?.fullName == targetRepo?.fullName && !resetPath) {
                        _explorerState.value.selectedBranch
                    } else {
                        targetRepo?.defaultBranch ?: "main"
                    }
                    val targetPath = if (resetPath || currentSelected?.fullName != targetRepo?.fullName) "" else _explorerState.value.currentPath

                    _explorerState.update {
                        it.copy(
                            repos = list,
                            selectedRepo = targetRepo,
                            selectedBranch = targetBranch,
                            currentPath = targetPath,
                            hasLoadedRepos = true,
                            isLoading = false
                        )
                    }
                    if (targetRepo != null) {
                        fetchExplorerDirectory(targetRepo, targetBranch, targetPath, forceNetwork = true, onDone = onDone)
                    } else {
                        onDone(null)
                    }
                },
                onFailure = { err ->
                    _explorerState.update { it.copy(isLoading = false) }
                    onDone(err.localizedMessage)
                }
            )
        }
    }

    fun selectExplorerRepo(repo: GitHubRepo) {
        _explorerState.update {
            it.copy(selectedRepo = repo, selectedBranch = repo.defaultBranch, currentPath = "")
        }
        openExplorerPath("")
    }

    fun openExplorerPath(path: String, forceNetwork: Boolean = false, onDone: (String?) -> Unit = {}) {
        val st = _explorerState.value
        val repo = st.selectedRepo ?: return
        val cacheKey = "${repo.fullName}@${st.selectedBranch}:$path"
        _explorerState.update { it.copy(currentPath = path) }

        if (!forceNetwork && st.folderCache.containsKey(cacheKey)) {
            onDone(null)
            return
        }
        fetchExplorerDirectory(repo, st.selectedBranch, path, forceNetwork = true, onDone = onDone)
    }

    fun refreshCurrentExplorerFolder(onDone: (String?) -> Unit = {}) {
        val st = _explorerState.value
        val repo = st.selectedRepo ?: return
        fetchExplorerDirectory(repo, st.selectedBranch, st.currentPath, forceNetwork = true, onDone = onDone)
    }

    private fun fetchExplorerDirectory(
        repo: GitHubRepo,
        branch: String,
        path: String,
        forceNetwork: Boolean,
        onDone: (String?) -> Unit
    ) {
        val cacheKey = "${repo.fullName}@$branch:$path"
        if (!forceNetwork && _explorerState.value.folderCache.containsKey(cacheKey)) {
            onDone(null)
            return
        }
        viewModelScope.launch {
            _explorerState.update { it.copy(isLoading = true) }
            val owner = repo.fullName.substringBefore('/')
            repository.getContents(owner, repo.name, path, branch).fold(
                onSuccess = { items ->
                    val sorted = items.sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))
                    _explorerState.update { state ->
                        val updatedCache = state.folderCache.toMutableMap()
                        updatedCache[cacheKey] = sorted
                        state.copy(folderCache = updatedCache, isLoading = false)
                    }
                    onDone(null)
                },
                onFailure = { err ->
                    _explorerState.update { it.copy(isLoading = false) }
                    onDone(err.localizedMessage ?: "Gagal memuat folder")
                }
            )
        }
    }

    // Uploader Persistent Staging & WorkManager Execution
    fun updateUploader(transform: (UploaderUiState) -> UploaderUiState) {
        _uploaderState.update(transform)
    }

    fun toggleIncludeParentFolder(includeParent: Boolean) {
        _uploaderState.update { st ->
            val folderName = st.selectedFolderName
            if (folderName.isNullOrBlank()) {
                st.copy(includeParentFolderInPath = includeParent)
            } else {
                val updated = st.entries.map { entry ->
                    if (entry.localStagingPath != null) {
                        val nextPath = if (includeParent) {
                            "$folderName/${entry.rawRelativePath}"
                        } else {
                            entry.rawRelativePath
                        }
                        entry.copy(path = nextPath)
                    } else {
                        entry
                    }
                }
                st.copy(includeParentFolderInPath = includeParent, entries = updated)
            }
        }
    }

    fun refreshUploaderRepos() {
        viewModelScope.launch {
            repository.getRepositories().onSuccess { list ->
                _uploaderState.update { st ->
                    val chosen = st.selectedExistingRepo?.let { prev -> list.find { it.fullName == prev.fullName } } ?: list.firstOrNull()
                    st.copy(existingRepos = list, selectedExistingRepo = chosen)
                }
            }
        }
    }

    fun enqueueBackgroundPush(
        owner: String,
        repoName: String,
        isNewRepo: Boolean,
        repoDescription: String,
        isPrivate: Boolean,
        branch: String,
        commitMessage: String,
        filesToPush: List<FileEntry>,
        onError: (String) -> Unit,
        onQueued: () -> Unit
    ) {
        viewModelScope.launch {
            _uploaderState.update {
                it.copy(isUploading = true, progressPercent = 5, stageText = "Menyiapkan antrean background...", lastSuccessResult = null)
            }
            try {
                if (isNewRepo) {
                    _uploaderState.update { it.copy(stageText = "Membuat repositori $repoName...") }
                    val createRes = repository.createRepository(repoName, repoDescription, isPrivate)
                    if (createRes.isFailure) {
                        val msg = createRes.exceptionOrNull()?.localizedMessage ?: "Gagal membuat repositori"
                        _uploaderState.update { it.copy(isUploading = false, stageText = "") }
                        onError(msg)
                        return@launch
                    }
                }

                val batchId = UUID.randomUUID().toString()
                val stagingDir = File(repository.getStagingDir(), batchId).apply { mkdirs() }
                val modeName = _uploaderState.value.archiveMode.name

                // Stage all binaries cleanly to disk off the Main thread (zero large Heap Strings)
                val queueItems = withContext(Dispatchers.IO) {
                    filesToPush.mapIndexed { idx, entry ->
                        val stageFile = File(stagingDir, "item_${idx}.bin")
                        val existingPath = entry.localStagingPath
                        if (!existingPath.isNullOrBlank() && File(existingPath).exists()) {
                            FileInputStream(File(existingPath)).use { fis ->
                                FileOutputStream(stageFile).use { fos ->
                                    fis.copyTo(fos, bufferSize = 16 * 1024)
                                }
                            }
                        } else {
                            val decodedBytes = try {
                                Base64.decode(entry.contentBase64, Base64.DEFAULT)
                            } catch (_: Exception) {
                                entry.contentBase64.toByteArray(Charsets.UTF_8)
                            }
                            stageFile.writeBytes(decodedBytes)
                        }

                        UploadQueueItem(
                            batchId = batchId,
                            owner = owner,
                            repo = repoName,
                            branch = branch.ifBlank { "main" },
                            commitMessage = commitMessage,
                            uploadMode = modeName,
                            filePath = entry.path,
                            localStagingPath = stageFile.absolutePath,
                            sizeBytes = stageFile.length(),
                            status = "PENDING"
                        )
                    }
                }

                repository.dbHelper.enqueueUploadBatch(queueItems)

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(workDataOf(UploadWorker.KEY_BATCH_ID to batchId))
                    .setConstraints(constraints)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()

                _uploaderState.update { it.copy(activeBatchWorkId = request.id.toString()) }
                workManager.enqueue(request)
                onQueued()

                // Observe WorkInfo status
                workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                    if (info != null) {
                        val stage = info.progress.getString(UploadWorker.KEY_STAGE)
                        val pct = info.progress.getInt(UploadWorker.KEY_PROGRESS, -1)
                        if (!stage.isNullOrBlank() || pct >= 0) {
                            _uploaderState.update {
                                it.copy(
                                    stageText = stage ?: it.stageText,
                                    progressPercent = if (pct >= 0) pct else it.progressPercent
                                )
                            }
                        }

                        when (info.state) {
                            WorkInfo.State.SUCCEEDED -> {
                                val sha = info.outputData.getString(UploadWorker.KEY_COMMIT_SHA)
                                val fullName = info.outputData.getString(UploadWorker.KEY_FULL_NAME)
                                val count = info.outputData.getInt(UploadWorker.KEY_FILE_COUNT, filesToPush.size)
                                _uploaderState.update {
                                    it.copy(
                                        isUploading = false,
                                        progressPercent = 100,
                                        stageText = "Push selesai!",
                                        lastSuccessResult = CommitResult(
                                            success = true,
                                            commitSha = sha,
                                            fullName = fullName,
                                            fileCount = count
                                        )
                                    )
                                }
                                return@collect
                            }
                            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                                val errMsg = info.outputData.getString(UploadWorker.KEY_ERROR) ?: "Upload terhenti"
                                _uploaderState.update { it.copy(isUploading = false, stageText = errMsg) }
                                onError(errMsg)
                                return@collect
                            }
                            else -> {}
                        }
                    }
                }
            } catch (e: Exception) {
                _uploaderState.update { it.copy(isUploading = false) }
                onError(e.localizedMessage ?: "Gagal memulai upload")
            }
        }
    }

    // Workflows & Conditional Polling (only when queued or in_progress)
    fun ensureWorkflowsInitialized() {
        if (!_workflowsState.value.hasInitialized) {
            refreshWorkflowRepos()
        }
    }

    fun refreshWorkflowRepos(onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            _workflowsState.update { it.copy(isLoading = true) }
            repository.getRepositories().fold(
                onSuccess = { list ->
                    val prev = _workflowsState.value.selectedRepo
                    val target = prev?.let { p -> list.find { it.fullName == p.fullName } } ?: list.firstOrNull()
                    _workflowsState.update {
                        it.copy(repos = list, selectedRepo = target, hasInitialized = true, isLoading = false)
                    }
                    if (target != null) {
                        refreshActiveWorkflowTab()
                    }
                },
                onFailure = { err ->
                    _workflowsState.update { it.copy(isLoading = false) }
                    onError(err.localizedMessage ?: "Gagal memuat repositori")
                }
            )
        }
    }

    fun selectWorkflowTab(tabIndex: Int) {
        _workflowsState.update { it.copy(selectedTab = tabIndex) }
        val st = _workflowsState.value
        val needsInitial = when (tabIndex) {
            0 -> st.releases.isEmpty()
            1 -> st.workflowRuns.isEmpty()
            2 -> st.branches.isEmpty()
            else -> false
        }
        if (needsInitial) {
            refreshActiveWorkflowTab()
        }
    }

    fun selectWorkflowRepo(repo: GitHubRepo) {
        _workflowsState.update {
            it.copy(
                selectedRepo = repo,
                releases = emptyList(),
                workflowRuns = emptyList(),
                branches = emptyList()
            )
        }
        refreshActiveWorkflowTab()
    }

    fun refreshActiveWorkflowTab(onError: (String) -> Unit = {}, onSuccess: () -> Unit = {}) {
        val repo = _workflowsState.value.selectedRepo ?: return
        val tab = _workflowsState.value.selectedTab
        val owner = repo.fullName.substringBefore('/')

        viewModelScope.launch {
            _workflowsState.update { it.copy(isLoading = true) }
            when (tab) {
                0 -> {
                    repository.getReleases(owner, repo.name).fold(
                        onSuccess = { list ->
                            _workflowsState.update { it.copy(releases = list, isLoading = false) }
                            onSuccess()
                        },
                        onFailure = { err ->
                            _workflowsState.update { it.copy(isLoading = false) }
                            onError(err.localizedMessage ?: "Gagal memuat releases")
                        }
                    )
                }
                1 -> {
                    repository.getWorkflowRuns(owner, repo.name).fold(
                        onSuccess = { runs ->
                            _workflowsState.update { it.copy(workflowRuns = runs, isLoading = false) }
                            evaluateWorkflowPolling(runs)
                            onSuccess()
                        },
                        onFailure = { err ->
                            _workflowsState.update { it.copy(isLoading = false) }
                            onError(err.localizedMessage ?: "Gagal memuat workflow runs")
                        }
                    )
                }
                2 -> {
                    repository.getBranches(owner, repo.name).fold(
                        onSuccess = { branchList ->
                            _workflowsState.update { it.copy(branches = branchList, isLoading = false) }
                            onSuccess()
                        },
                        onFailure = { err ->
                            _workflowsState.update { it.copy(isLoading = false) }
                            onError(err.localizedMessage ?: "Gagal memuat branches")
                        }
                    )
                }
            }
        }
    }

    private fun evaluateWorkflowPolling(runs: List<WorkflowRun>) {
        val hasActiveRun = runs.any { it.status == "queued" || it.status == "in_progress" }
        if (hasActiveRun && pollingJob == null) {
            _workflowsState.update { it.copy(isAutoPolling = true) }
            pollingJob = viewModelScope.launch {
                while (true) {
                    delay(5000)
                    val repo = _workflowsState.value.selectedRepo ?: break
                    val owner = repo.fullName.substringBefore('/')
                    val res = repository.getWorkflowRuns(owner, repo.name)
                    if (res.isSuccess) {
                        val updated = res.getOrThrow()
                        _workflowsState.update { it.copy(workflowRuns = updated) }
                        val stillRunning = updated.any { it.status == "queued" || it.status == "in_progress" }
                        if (!stillRunning) break
                    } else {
                        break
                    }
                }
                _workflowsState.update { it.copy(isAutoPolling = false) }
                pollingJob = null
            }
        } else if (!hasActiveRun) {
            pollingJob?.cancel()
            pollingJob = null
            _workflowsState.update { it.copy(isAutoPolling = false) }
        }
    }

    // CI/CD Monitor State & Actions
    fun ensureCiCdInitialized() {
        if (!_cicdState.value.hasInitialized) {
            refreshCiCdRepos()
        }
    }

    fun refreshCiCdRepos(onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            _cicdState.update { it.copy(isLoadingRuns = true) }
            repository.getRepositories().fold(
                onSuccess = { list ->
                    val prev = _cicdState.value.selectedRepo
                    val target = prev?.let { p -> list.find { it.fullName == p.fullName } } ?: list.firstOrNull()
                    _cicdState.update {
                        it.copy(repos = list, selectedRepo = target, hasInitialized = true, isLoadingRuns = false)
                    }
                    if (target != null) {
                        refreshCiCdRuns()
                    }
                },
                onFailure = { err ->
                    _cicdState.update { it.copy(isLoadingRuns = false) }
                    onError(err.localizedMessage ?: "Gagal memuat repositori")
                }
            )
        }
    }

    fun selectCiCdRepo(repo: GitHubRepo) {
        _cicdState.update {
            it.copy(
                selectedRepo = repo,
                runs = emptyList(),
                selectedRun = null,
                jobs = emptyList(),
                rawJobLog = "",
                artifacts = emptyList(),
                extractedArtifactFiles = emptyList()
            )
        }
        refreshCiCdRuns()
    }

    fun refreshCiCdRuns(onError: (String) -> Unit = {}, onSuccess: () -> Unit = {}) {
        val repo = _cicdState.value.selectedRepo ?: return
        val owner = repo.fullName.substringBefore('/')
        viewModelScope.launch {
            _cicdState.update { it.copy(isLoadingRuns = true) }
            repository.getWorkflowRuns(owner, repo.name).fold(
                onSuccess = { runs ->
                    _cicdState.update { it.copy(runs = runs, isLoadingRuns = false) }
                    evaluateCiCdPolling(runs)
                    onSuccess()
                },
                onFailure = { err ->
                    _cicdState.update { it.copy(isLoadingRuns = false) }
                    onError(err.localizedMessage ?: "Gagal memuat riwayat CI/CD")
                }
            )
        }
    }

    private fun evaluateCiCdPolling(runs: List<WorkflowRun>) {
        val hasActive = runs.any { it.status == "queued" || it.status == "in_progress" }
        if (hasActive && cicdPollingJob == null) {
            _cicdState.update { it.copy(isAutoPolling = true) }
            cicdPollingJob = viewModelScope.launch {
                while (true) {
                    delay(5000)
                    val repo = _cicdState.value.selectedRepo ?: break
                    val owner = repo.fullName.substringBefore('/')
                    val res = repository.getWorkflowRuns(owner, repo.name)
                    if (res.isSuccess) {
                        val updated = res.getOrThrow()
                        _cicdState.update { it.copy(runs = updated) }
                        if (!updated.any { it.status == "queued" || it.status == "in_progress" }) break
                    } else {
                        break
                    }
                }
                _cicdState.update { it.copy(isAutoPolling = false) }
                cicdPollingJob = null
            }
        } else if (!hasActive) {
            cicdPollingJob?.cancel()
            cicdPollingJob = null
            _cicdState.update { it.copy(isAutoPolling = false) }
        }
    }

    fun openCiCdRunDetail(run: WorkflowRun) {
        val repo = _cicdState.value.selectedRepo ?: return
        val owner = repo.fullName.substringBefore('/')
        _cicdState.update {
            it.copy(
                selectedRun = run,
                jobs = emptyList(),
                selectedJobId = null,
                rawJobLog = "",
                artifacts = emptyList(),
                extractedArtifactFiles = emptyList(),
                extractedFromArtifactName = null,
                isLoadingJobs = true
            )
        }
        viewModelScope.launch {
            val jobsDeferred = async { repository.getWorkflowJobs(owner, repo.name, run.id) }
            val artDeferred = async { repository.getWorkflowArtifacts(owner, repo.name, run.id) }
            val jobsList = jobsDeferred.await().getOrDefault(emptyList())
            val artList = artDeferred.await().getOrDefault(emptyList())

            _cicdState.update {
                it.copy(
                    jobs = jobsList,
                    artifacts = artList,
                    isLoadingJobs = false
                )
            }

            // Auto-load logs of first failed job or first job
            val targetJob = jobsList.firstOrNull { it.conclusion == "failure" } ?: jobsList.firstOrNull()
            if (targetJob != null) {
                loadCiCdJobLog(targetJob.id)
            }
        }
    }

    fun loadCiCdJobLog(jobId: Long, onError: (String) -> Unit = {}) {
        val repo = _cicdState.value.selectedRepo ?: return
        val owner = repo.fullName.substringBefore('/')
        viewModelScope.launch {
            _cicdState.update { it.copy(selectedJobId = jobId, isLoadingJobLog = true) }
            repository.getJobLogs(owner, repo.name, jobId).fold(
                onSuccess = { text ->
                    _cicdState.update { it.copy(rawJobLog = text, isLoadingJobLog = false) }
                },
                onFailure = { err ->
                    _cicdState.update {
                        it.copy(
                            rawJobLog = "Gagal mengambil log job #$jobId: ${err.localizedMessage}",
                            isLoadingJobLog = false
                        )
                    }
                    onError(err.localizedMessage ?: "Gagal memuat log job")
                }
            )
        }
    }

    fun extractCiCdArtifactToCache(
        artifact: WorkflowArtifact,
        onError: (String) -> Unit,
        onSuccess: (List<ExtractedArtifactFile>) -> Unit
    ) {
        val repo = _cicdState.value.selectedRepo ?: return
        val owner = repo.fullName.substringBefore('/')
        viewModelScope.launch {
            _cicdState.update { it.copy(activeArtifactId = artifact.id, extractedArtifactFiles = emptyList()) }
            repository.downloadAndExtractArtifactToCache(owner, repo.name, artifact).fold(
                onSuccess = { files ->
                    _cicdState.update {
                        it.copy(
                            activeArtifactId = null,
                            extractedArtifactFiles = files,
                            extractedFromArtifactName = artifact.name
                        )
                    }
                    onSuccess(files)
                },
                onFailure = { err ->
                    _cicdState.update { it.copy(activeArtifactId = null) }
                    onError(err.localizedMessage ?: "Gagal mengunduh/ekstrak artifact")
                }
            )
        }
    }

    fun closeCiCdRunDetail() {
        repository.clearCiCdArtifactCache()
        _cicdState.update {
            it.copy(
                selectedRun = null,
                jobs = emptyList(),
                selectedJobId = null,
                rawJobLog = "",
                artifacts = emptyList(),
                activeArtifactId = null,
                extractedArtifactFiles = emptyList(),
                extractedFromArtifactName = null
            )
        }
    }

    fun clearCiCdCache() {
        repository.clearCiCdArtifactCache()
        _cicdState.update {
            it.copy(
                extractedArtifactFiles = emptyList(),
                extractedFromArtifactName = null
            )
        }
    }

    fun refreshDownloads() {
        repository.dbHelper.refreshDownloads()
    }
}
