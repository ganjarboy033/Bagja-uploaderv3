package com.example.xiyu.data.repository

import android.content.Context
import android.util.Base64
import com.example.xiyu.data.api.GitHubApiService
import com.example.xiyu.data.database.AppDatabaseHelper
import com.example.xiyu.data.database.CommitRecord
import com.example.xiyu.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class GitHubRepository(private val context: Context) {

    val dbHelper = AppDatabaseHelper.getInstance(context)

    private var api = GitHubApiService.create(
        baseUrl = dbHelper.configState.value.githubApiUrl,
        dbHelper = dbHelper
    )

    private val prefs = context.getSharedPreferences("kruzenlab_github_prefs", Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow<UserProfile?>(null)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _rateLimit = MutableStateFlow<RateLimitInfo?>(null)
    val rateLimit: StateFlow<RateLimitInfo?> = _rateLimit.asStateFlow()

    init {
        val savedToken = prefs.getString("github_token", null)
        val isDemo = prefs.getBoolean("is_demo_mode", false)

        if (!savedToken.isNullOrBlank()) {
            _token.value = savedToken
            _isDemoMode.value = false
            _currentUser.value = UserProfile(
                login = prefs.getString("user_login", "user") ?: "user",
                name = prefs.getString("user_name", "Developer"),
                avatarUrl = prefs.getString("user_avatar", null),
                bio = "GitHub connected ⚡"
            )
        } else if (isDemo) {
            enableDemoMode()
        } else {
            enableDemoMode()
        }
    }

    fun updateApiBaseUrl(newBaseUrl: String) {
        api = GitHubApiService.create(baseUrl = newBaseUrl, dbHelper = dbHelper)
    }

    fun enableDemoMode() {
        _isDemoMode.value = true
        _token.value = null
        prefs.edit().putBoolean("is_demo_mode", true).apply()
        _currentUser.value = UserProfile(
            login = "bagja_dev",
            name = "DEV BAGJA / KruzenLab",
            avatarUrl = null,
            bio = "kruzenlab-uploader Demo Workspace ⚡"
        )
        _rateLimit.value = RateLimitInfo(limit = 5000, remaining = 4995, used = 5)
        dbHelper.addInMemoryLog("DEMO", "/auth/demo", 200, 15, "Switched to KruzenLab Demo Workspace")
    }

    suspend fun loginWithToken(inputToken: String, isOAuth: Boolean = false): Result<UserProfile> = withContext(Dispatchers.IO) {
        val cleanToken = inputToken.trim()
        if (cleanToken.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Token cannot be empty"))
        }

        try {
            val authHeader = if (cleanToken.startsWith("Bearer ") || cleanToken.startsWith("token ")) {
                cleanToken
            } else {
                "Bearer $cleanToken"
            }

            val userResp = api.getCurrentUser(authHeader)
            val profile = UserProfile(
                login = userResp.login,
                name = userResp.name ?: userResp.login,
                avatarUrl = userResp.avatarUrl,
                htmlUrl = userResp.htmlUrl,
                bio = userResp.bio
            )

            prefs.edit()
                .putString("github_token", cleanToken)
                .putString("user_login", profile.login)
                .putString("user_name", profile.name)
                .putString("user_avatar", profile.avatarUrl)
                .putBoolean("is_demo_mode", false)
                .apply()

            _token.value = cleanToken
            _isDemoMode.value = false
            _currentUser.value = profile

            // Save to SQLite database accounts table
            dbHelper.saveAccount(
                AccountEntity(
                    login = profile.login,
                    name = profile.name,
                    avatarUrl = profile.avatarUrl,
                    token = cleanToken,
                    isOAuth = isOAuth,
                    addedAt = System.currentTimeMillis()
                )
            )

            // Refresh rate limit
            fetchRateLimitInternal(cleanToken)

            dbHelper.addInMemoryLog("AUTH", "/user", 200, 45, "User verified: @${profile.login}")
            Result.success(profile)
        } catch (e: Exception) {
            dbHelper.addInMemoryLog("AUTH", "/user", 401, 30, "Token verification failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun switchAccount(account: AccountEntity): Result<UserProfile> {
        return loginWithToken(account.token, account.isOAuth)
    }

    suspend fun removeAccount(login: String) {
        dbHelper.removeAccount(login)
        if (_currentUser.value?.login == login) {
            logout()
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
        _token.value = null
        _currentUser.value = null
        _isDemoMode.value = false
        _rateLimit.value = null
        dbHelper.addInMemoryLog("AUTH", "/logout", 200, 5, "Session disconnected")
    }

    suspend fun fetchRateLimit(): Result<RateLimitInfo> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val demoLimit = RateLimitInfo(5000, 4995, System.currentTimeMillis() / 1000 + 3600, 5)
            _rateLimit.value = demoLimit
            return@withContext Result.success(demoLimit)
        }
        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.getRateLimit(authHeader)
            val info = RateLimitInfo(
                limit = resp.resources.core.limit,
                remaining = resp.resources.core.remaining,
                resetTimeEpochSeconds = resp.resources.core.reset,
                used = resp.resources.core.used
            )
            _rateLimit.value = info
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchRateLimitInternal(token: String) {
        try {
            val resp = api.getRateLimit("Bearer $token")
            _rateLimit.value = RateLimitInfo(
                limit = resp.resources.core.limit,
                remaining = resp.resources.core.remaining,
                resetTimeEpochSeconds = resp.resources.core.reset,
                used = resp.resources.core.used
            )
        } catch (_: Exception) {}
    }

    suspend fun getRepositories(): Result<List<GitHubRepo>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            dbHelper.addInMemoryLog("GET", "/user/repos (demo)", 200, 12, "Loaded 3 demo repositories")
            return@withContext Result.success(
                listOf(
                    GitHubRepo(
                        id = 101,
                        name = "kruzen-cloud-core",
                        fullName = "${_currentUser.value?.login ?: "bagja_dev"}/kruzen-cloud-core",
                        isPrivate = true,
                        defaultBranch = "main",
                        htmlUrl = "https://github.com/bagja_dev/kruzen-cloud-core",
                        description = "Core cloud uploader infrastructure ⚡"
                    ),
                    GitHubRepo(
                        id = 102,
                        name = "android-batch-sync",
                        fullName = "${_currentUser.value?.login ?: "bagja_dev"}/android-batch-sync",
                        isPrivate = false,
                        defaultBranch = "main",
                        htmlUrl = "https://github.com/bagja_dev/android-batch-sync",
                        description = "Git tree atomic uploader tools"
                    ),
                    GitHubRepo(
                        id = 103,
                        name = "kruzenlab-demo-project",
                        fullName = "${_currentUser.value?.login ?: "bagja_dev"}/kruzenlab-demo-project",
                        isPrivate = true,
                        defaultBranch = "main",
                        htmlUrl = "https://github.com/bagja_dev/kruzenlab-demo-project",
                        description = "Demo workspace repository by KruzenLab"
                    )
                )
            )
        }

        try {
            val authHeader = "Bearer $currentToken"
            val repos = api.getRepositories(authHeader)
            val result = repos.map { r ->
                GitHubRepo(
                    id = r.id,
                    name = r.name,
                    fullName = r.fullName,
                    isPrivate = r.private,
                    defaultBranch = r.defaultBranch ?: "main",
                    htmlUrl = r.htmlUrl ?: "https://github.com/${r.fullName}",
                    description = r.description
                )
            }
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createRepository(
        name: String,
        description: String?,
        isPrivate: Boolean
    ): Result<GitHubRepo> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        val normalized = name.trim().replace("\\s+".toRegex(), "-")

        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val owner = _currentUser.value?.login ?: "bagja_dev"
            val demoRepo = GitHubRepo(
                id = System.currentTimeMillis(),
                name = normalized,
                fullName = "$owner/$normalized",
                isPrivate = isPrivate,
                defaultBranch = "main",
                htmlUrl = "https://github.com/$owner/$normalized",
                description = description
            )
            dbHelper.addInMemoryLog("POST", "/user/repos (demo)", 201, 20, "Created demo repo: $normalized")
            return@withContext Result.success(demoRepo)
        }

        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.createRepository(
                authHeader,
                CreateRepoRequest(
                    name = normalized,
                    description = description,
                    private = isPrivate,
                    autoInit = true
                )
            )
            val repo = GitHubRepo(
                id = resp.id,
                name = resp.name,
                fullName = resp.fullName,
                isPrivate = resp.private,
                defaultBranch = resp.defaultBranch ?: "main",
                htmlUrl = resp.htmlUrl ?: "https://github.com/${resp.fullName}",
                description = resp.description
            )
            Result.success(repo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteRepository(owner: String, repo: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            dbHelper.addInMemoryLog("DELETE", "/repos/$owner/$repo (demo)", 204, 15, "Deleted demo repo $repo")
            return@withContext Result.success(true)
        }
        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.deleteRepository(authHeader, owner, repo)
            if (resp.isSuccessful) {
                dbHelper.addInMemoryLog("DELETE", "/repos/$owner/$repo", 204, 30, "Repository $repo deleted")
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to delete repository (HTTP ${resp.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Repository Contents CRUD
    suspend fun getContents(
        owner: String,
        repo: String,
        path: String = "",
        branch: String? = null
    ): Result<List<RepoContentItem>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            // Simulated file tree for demo
            val sampleItems = if (path.isEmpty()) {
                listOf(
                    RepoContentItem("src", "src", "d1234", 0, "dir"),
                    RepoContentItem("docs", "docs", "d5678", 0, "dir"),
                    RepoContentItem("README.md", "README.md", "f1111", 512, "file"),
                    RepoContentItem("package.json", "package.json", "f2222", 340, "file"),
                    RepoContentItem(".gitignore", ".gitignore", "f3333", 120, "file")
                )
            } else if (path == "src") {
                listOf(
                    RepoContentItem("index.ts", "src/index.ts", "f4444", 820, "file"),
                    RepoContentItem("app.ts", "src/app.ts", "f5555", 1420, "file")
                )
            } else {
                listOf(
                    RepoContentItem("guide.md", "$path/guide.md", "f6666", 400, "file")
                )
            }
            return@withContext Result.success(sampleItems)
        }

        try {
            val authHeader = "Bearer $currentToken"
            val items = api.getContents(authHeader, owner, repo, path, branch)
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSingleFileContent(
        owner: String,
        repo: String,
        path: String,
        branch: String? = null
    ): Result<RepoContentItem> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val dummyContent = """
                # KruzenLab Cloud Project
                Welcome to $path!
                Automated Git Batch Manager by DEV BAGJA.
                Powered by KruzenLab.
            """.trimIndent()
            val encoded = Base64.encodeToString(dummyContent.toByteArray(), Base64.NO_WRAP)
            return@withContext Result.success(
                RepoContentItem(
                    name = path.substringAfterLast('/'),
                    path = path,
                    sha = "dummy_sha_${System.currentTimeMillis()}",
                    size = dummyContent.length.toLong(),
                    type = "file",
                    content = encoded,
                    encoding = "base64"
                )
            )
        }

        try {
            val authHeader = "Bearer $currentToken"
            val item = api.getSingleFileContent(authHeader, owner, repo, path, branch)
            Result.success(item)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createOrUpdateFile(
        owner: String,
        repo: String,
        path: String,
        contentBase64: String,
        commitMessage: String,
        sha: String? = null,
        branch: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        val cleanPath = path.trim().removePrefix("/")
        val cleanMsg = commitMessage.ifBlank { "Update $cleanPath" }

        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            dbHelper.addInMemoryLog("PUT", "/repos/$owner/$repo/contents/$cleanPath (demo)", 200, 30, "Saved $cleanPath")
            return@withContext Result.success(true)
        }

        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.createOrUpdateFile(
                auth = authHeader,
                owner = owner,
                repo = repo,
                path = cleanPath,
                request = ContentUpdateRequest(
                    message = cleanMsg,
                    content = contentBase64,
                    sha = sha,
                    branch = branch
                )
            )
            if (resp.isSuccessful) {
                dbHelper.addInMemoryLog("PUT", "/repos/$owner/$repo/contents/$cleanPath", 200, 40, "Updated $cleanPath")
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to save file: HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFile(
        owner: String,
        repo: String,
        path: String,
        sha: String,
        commitMessage: String,
        branch: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        val cleanPath = path.trim().removePrefix("/")
        val cleanMsg = commitMessage.ifBlank { "Delete $cleanPath" }

        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            dbHelper.addInMemoryLog("DELETE", "/repos/$owner/$repo/contents/$cleanPath (demo)", 200, 25, "Deleted $cleanPath")
            return@withContext Result.success(true)
        }

        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.deleteFile(
                auth = authHeader,
                owner = owner,
                repo = repo,
                path = cleanPath,
                request = ContentDeleteRequest(
                    message = cleanMsg,
                    sha = sha,
                    branch = branch
                )
            )
            if (resp.isSuccessful) {
                dbHelper.addInMemoryLog("DELETE", "/repos/$owner/$repo/contents/$cleanPath", 200, 40, "Deleted $cleanPath")
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to delete file: HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createFolder(
        owner: String,
        repo: String,
        folderPath: String,
        branch: String? = null
    ): Result<Boolean> {
        val cleanFolder = folderPath.trim().removePrefix("/").removeSuffix("/")
        val targetPath = "$cleanFolder/.gitkeep"
        val emptyContent = Base64.encodeToString("".toByteArray(), Base64.NO_WRAP)
        return createOrUpdateFile(
            owner = owner,
            repo = repo,
            path = targetPath,
            contentBase64 = emptyContent,
            commitMessage = "Initialize folder $cleanFolder",
            branch = branch
        )
    }

    // Branches, Releases, Actions
    suspend fun getBranches(owner: String, repo: String): Result<List<GitHubBranch>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success(
                listOf(
                    GitHubBranch("main", BranchCommit("c111", null)),
                    GitHubBranch("dev", BranchCommit("c222", null)),
                    GitHubBranch("v1.0.0-release", BranchCommit("c333", null))
                )
            )
        }
        try {
            val authHeader = "Bearer $currentToken"
            val branches = api.getBranches(authHeader, owner, repo)
            Result.success(branches)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getReleases(owner: String, repo: String): Result<List<GitHubRelease>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success(
                listOf(
                    GitHubRelease(
                        id = 1,
                        tagName = "v1.0.0",
                        name = "Initial Release 1.0.0",
                        body = "First stable cloud build with atomic Git uploader.",
                        prerelease = false,
                        createdAt = "2026-11-01T10:00:00Z",
                        htmlUrl = "https://github.com/$owner/$repo/releases/v1.0.0"
                    )
                )
            )
        }
        try {
            val authHeader = "Bearer $currentToken"
            val releases = api.getReleases(authHeader, owner, repo)
            Result.success(releases)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createRelease(
        owner: String,
        repo: String,
        tagName: String,
        name: String,
        body: String,
        prerelease: Boolean = false
    ): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val demoRel = GitHubRelease(
                id = System.currentTimeMillis(),
                tagName = tagName,
                name = name,
                body = body,
                prerelease = prerelease,
                createdAt = "Just now",
                htmlUrl = "https://github.com/$owner/$repo/releases/$tagName"
            )
            return@withContext Result.success(demoRel)
        }
        try {
            val authHeader = "Bearer $currentToken"
            val rel = api.createRelease(
                auth = authHeader,
                owner = owner,
                repo = repo,
                request = CreateReleaseRequest(
                    tagName = tagName,
                    name = name,
                    body = body,
                    prerelease = prerelease
                )
            )
            Result.success(rel)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWorkflowRuns(owner: String, repo: String): Result<List<WorkflowRun>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success(
                listOf(
                    WorkflowRun(
                        id = 1001,
                        name = "Build & Test Android APK",
                        headBranch = "main",
                        status = "completed",
                        conclusion = "success",
                        htmlUrl = "https://github.com/$owner/$repo/actions/runs/1001",
                        createdAt = "2 hours ago"
                    ),
                    WorkflowRun(
                        id = 1002,
                        name = "Code Quality & Lint",
                        headBranch = "main",
                        status = "completed",
                        conclusion = "success",
                        htmlUrl = "https://github.com/$owner/$repo/actions/runs/1002",
                        createdAt = "Yesterday"
                    )
                )
            )
        }
        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.getWorkflowRuns(authHeader, owner, repo)
            Result.success(resp.workflowRuns)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rerunWorkflow(owner: String, repo: String, runId: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            dbHelper.addInMemoryLog("POST", "/repos/$owner/$repo/actions/runs/$runId/rerun (demo)", 201, 20, "Re-run triggered")
            return@withContext Result.success(true)
        }
        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.rerunWorkflow(authHeader, owner, repo, runId)
            Result.success(resp.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Atomic Batch Commit & Push with 0-Commit Empty Repo Handler
    suspend fun commitAndPush(
        owner: String,
        repo: String,
        branch: String,
        message: String,
        entries: List<FileEntry>,
        onProgress: (stage: String, percent: Int) -> Unit
    ): Result<CommitResult> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        val cleanOwner = owner.trim()
        val cleanRepo = repo.trim().replace("\\s+".toRegex(), "-")
        val cleanBranch = branch.trim().ifEmpty { "main" }
        val commitMsg = message.trim().ifEmpty { "Batch upload via kruzenlab-uploader" }

        val selectedFiles = entries.filter { it.isSelected }
        if (selectedFiles.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Please select at least one file."))
        }

        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            for (i in selectedFiles.indices) {
                val file = selectedFiles[i]
                val pct = ((i + 1) * 80) / selectedFiles.size
                onProgress("Uploading file ${i + 1}/${selectedFiles.size}: ${file.path}", pct)
                kotlinx.coroutines.delay(80)
            }

            onProgress("Creating tree…", 88)
            kotlinx.coroutines.delay(120)

            onProgress("Writing commit…", 95)
            kotlinx.coroutines.delay(120)

            onProgress("Updating branch $cleanBranch…", 100)
            kotlinx.coroutines.delay(80)

            val mockSha = "kz" + (1000000..9999999).random() + "ff"
            val commitUrl = "https://github.com/$cleanOwner/$cleanRepo/commit/$mockSha"

            dbHelper.recordCommit(
                CommitRecord(
                    repoName = cleanRepo,
                    owner = cleanOwner,
                    commitSha = mockSha,
                    branch = cleanBranch,
                    fileCount = selectedFiles.size,
                    commitMessage = commitMsg,
                    htmlUrl = commitUrl
                )
            )

            dbHelper.addInMemoryLog("COMMIT", "/repos/$cleanOwner/$cleanRepo/git/commits", 201, 350, "Pushed ${selectedFiles.size} files (SHA $mockSha)")

            return@withContext Result.success(
                CommitResult(
                    success = true,
                    commitSha = mockSha,
                    htmlUrl = commitUrl,
                    repoUrl = "https://github.com/$cleanOwner/$cleanRepo",
                    fullName = "$cleanOwner/$cleanRepo",
                    fileCount = selectedFiles.size
                )
            )
        }

        try {
            val authHeader = "Bearer $currentToken"

            // 1. Upload blobs in batch
            val treeItems = mutableListOf<TreeItem>()
            for (i in selectedFiles.indices) {
                val file = selectedFiles[i]
                val pct = ((i + 1) * 75) / selectedFiles.size
                onProgress("Creating blob ${i + 1}/${selectedFiles.size}: ${file.path}", pct)

                val blobResp = api.createBlob(
                    auth = authHeader,
                    owner = cleanOwner,
                    repo = cleanRepo,
                    request = BlobRequest(content = file.contentBase64, encoding = "base64")
                )

                treeItems.add(
                    TreeItem(
                        path = file.path.removePrefix("/"),
                        sha = blobResp.sha
                    )
                )
            }

            onProgress("Checking repository branch status…", 80)
            var parentCommitSha: String? = null
            var baseTreeSha: String? = null
            var isBrandNewRepo = false

            try {
                val refResp = api.getBranchRef(authHeader, cleanOwner, cleanRepo, cleanBranch)
                if (refResp.isSuccessful && refResp.body() != null) {
                    parentCommitSha = refResp.body()?.`object`?.sha
                    if (!parentCommitSha.isNullOrEmpty()) {
                        val parentCommit = api.getCommit(authHeader, cleanOwner, cleanRepo, parentCommitSha)
                        baseTreeSha = parentCommit.tree.sha
                    }
                } else if (refResp.code() == 404) {
                    // Empty repository with 0 commits or branch does not exist yet!
                    isBrandNewRepo = true
                }
            } catch (_: Exception) {
                isBrandNewRepo = true
            }

            onProgress("Constructing Git Tree…", 88)
            val treeResp = api.createTree(
                auth = authHeader,
                owner = cleanOwner,
                repo = cleanRepo,
                request = TreeRequest(tree = treeItems, baseTree = baseTreeSha)
            )

            onProgress("Signing & creating Commit…", 93)
            val commitResp = api.createCommit(
                auth = authHeader,
                owner = cleanOwner,
                repo = cleanRepo,
                request = CommitRequest(
                    message = commitMsg,
                    tree = treeResp.sha,
                    parents = if (parentCommitSha != null) listOf(parentCommitSha) else null
                )
            )

            onProgress("Updating branch reference $cleanBranch…", 97)
            if (parentCommitSha != null && !isBrandNewRepo) {
                val updateResp = api.updateRef(
                    auth = authHeader,
                    owner = cleanOwner,
                    repo = cleanRepo,
                    branch = cleanBranch,
                    request = RefRequest(sha = commitResp.sha)
                )
                if (!updateResp.isSuccessful && updateResp.code() == 422) {
                    // Fallback to createRef if branch wasn't found
                    api.createRef(
                        auth = authHeader,
                        owner = cleanOwner,
                        repo = cleanRepo,
                        request = RefRequest(ref = "refs/heads/$cleanBranch", sha = commitResp.sha)
                    )
                }
            } else {
                // Initial commit on an empty repository (prevents 404/409 errors!)
                api.createRef(
                    auth = authHeader,
                    owner = cleanOwner,
                    repo = cleanRepo,
                    request = RefRequest(ref = "refs/heads/$cleanBranch", sha = commitResp.sha)
                )
            }

            val commitUrl = commitResp.htmlUrl ?: "https://github.com/$cleanOwner/$cleanRepo/commit/${commitResp.sha}"

            dbHelper.recordCommit(
                CommitRecord(
                    repoName = cleanRepo,
                    owner = cleanOwner,
                    commitSha = commitResp.sha,
                    branch = cleanBranch,
                    fileCount = selectedFiles.size,
                    commitMessage = commitMsg,
                    htmlUrl = commitUrl
                )
            )

            fetchRateLimitInternal(currentToken)

            onProgress("Push complete!", 100)
            Result.success(
                CommitResult(
                    success = true,
                    commitSha = commitResp.sha,
                    htmlUrl = commitUrl,
                    repoUrl = "https://github.com/$cleanOwner/$cleanRepo",
                    fullName = "$cleanOwner/$cleanRepo",
                    fileCount = selectedFiles.size
                )
            )
        } catch (e: Exception) {
            dbHelper.addInMemoryLog("ERROR", "/git/commit", 500, 100, "Commit failed: ${e.message}")
            Result.failure(e)
        }
    }
}
