package com.example.xiyu.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Base64OutputStream
import com.example.xiyu.data.api.GitHubApiService
import com.example.xiyu.data.database.AppDatabaseHelper
import com.example.xiyu.data.database.CommitRecord
import com.example.xiyu.data.model.*
import com.example.xiyu.util.NotificationHelper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class GitHubRepository(private val context: Context) {

    val dbHelper = AppDatabaseHelper.getInstance(context)

    private var api = GitHubApiService.create(
        baseUrl = dbHelper.configState.value.githubApiUrl,
        dbHelper = dbHelper
    )
    private val httpClient = GitHubApiService.buildOkHttpClient(dbHelper)

    private val prefs = context.getSharedPreferences("kruzenlab_github_prefs", Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow<UserProfile?>(null)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _rateLimit = MutableStateFlow<RateLimitInfo?>(null)
    val rateLimit: StateFlow<RateLimitInfo?> = _rateLimit.asStateFlow()

    private val _authRevision = MutableStateFlow(0)
    val authRevision: StateFlow<Int> = _authRevision.asStateFlow()

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

    fun getDownloadsDir(): File {
        val dir = File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getStagingDir(): File {
        val dir = File(context.filesDir, "upload_staging")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getIntakeStagingDir(): File {
        val dir = File(context.filesDir, "draft_staging")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun resetIntakeStagingDir(): File {
        val dir = getIntakeStagingDir()
        try {
            dir.deleteRecursively()
        } catch (_: Exception) {}
        dir.mkdirs()
        return dir
    }

    fun getCiCdArtifactCacheDir(): File {
        val dir = File(context.cacheDir, "cicd_artifacts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun clearCiCdArtifactCache() {
        try {
            val dir = File(context.cacheDir, "cicd_artifacts")
            if (dir.exists()) dir.deleteRecursively()
        } catch (_: Exception) {}
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
        _authRevision.value = _authRevision.value + 1
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

            fetchRateLimitInternal(cleanToken)
            _authRevision.value = _authRevision.value + 1
            dbHelper.addInMemoryLog("AUTH", "/user", 200, 45, "User verified: @${profile.login}")
            NotificationHelper.notifyActivity(
                context,
                title = "Akun Terhubung: @${profile.login}",
                message = "Sesi GitHub aktif dan siap digunakan.",
                targetRoute = "dashboard"
            )
            Result.success(profile)
        } catch (e: Exception) {
            dbHelper.addInMemoryLog("AUTH", "/user", 401, 30, "Token verification failed: ${e.message}")
            if (dbHelper.configState.value.enableLogErrorAdvisor) {
                NotificationHelper.notifyError(
                    context,
                    title = "Autentikasi Gagal (401)",
                    message = "Token ditolak GitHub. Ketuk untuk login ulang atau ganti ke Classic PAT.",
                    targetRoute = "login"
                )
            }
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
        _authRevision.value = _authRevision.value + 1
        dbHelper.addInMemoryLog("AUTH", "/logout", 200, 5, "Session disconnected")
    }

    /**
     * Attempts to recover an active token from SharedPreferences or SQLite saved_accounts
     * if a transient HTTP 401 occurs before forcing re-login.
     */
    fun tryRehydrateTokenFromSavedAccounts(): String? {
        val currentLogin = _currentUser.value?.login
        val fromPrefs = prefs.getString("github_token", null)
        if (!fromPrefs.isNullOrBlank() && fromPrefs != _token.value) {
            _token.value = fromPrefs
            return fromPrefs
        }
        val matchingAccount = dbHelper.savedAccounts.value.firstOrNull {
            !currentLogin.isNullOrBlank() && it.login.equals(currentLogin, ignoreCase = true) && it.token.isNotBlank()
        } ?: dbHelper.savedAccounts.value.firstOrNull { it.token.isNotBlank() }

        if (matchingAccount != null && matchingAccount.token != _token.value) {
            _token.value = matchingAccount.token
            prefs.edit().putString("github_token", matchingAccount.token).apply()
            dbHelper.addInMemoryLog("AUTH", "/rehydrate", 200, 5, "Token dipulihkan dari saved_accounts (@${matchingAccount.login})")
            return matchingAccount.token
        }
        return _token.value
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

    // Unrestricted Repository Contents CRUD
    suspend fun getContents(
        owner: String,
        repo: String,
        path: String = "",
        branch: String? = null
    ): Result<List<RepoContentItem>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val sampleItems = when (path) {
                "" -> listOf(
                    RepoContentItem(".github", ".github", "d0001", 0, "dir"),
                    RepoContentItem("app", "app", "d1234", 0, "dir"),
                    RepoContentItem("docs", "docs", "d5678", 0, "dir"),
                    RepoContentItem("README.md", "README.md", "f1111", 512, "file"),
                    RepoContentItem("package.json", "package.json", "f2222", 340, "file"),
                    RepoContentItem(".gitignore", ".gitignore", "f3333", 120, "file"),
                    RepoContentItem("Makefile", "Makefile", "f3334", 95, "file")
                )
                ".github" -> listOf(
                    RepoContentItem("workflows", ".github/workflows", "d0002", 0, "dir")
                )
                ".github/workflows" -> listOf(
                    RepoContentItem("build.yml", ".github/workflows/build.yml", "f0003", 1040, "file")
                )
                "app" -> listOf(
                    RepoContentItem("src", "app/src", "d1235", 0, "dir"),
                    RepoContentItem("build.gradle.kts", "app/build.gradle.kts", "f4444", 1220, "file")
                )
                "app/src" -> listOf(
                    RepoContentItem("MainActivity.kt", "app/src/MainActivity.kt", "f5555", 2420, "file")
                )
                else -> listOf(
                    RepoContentItem("notes.md", "$path/notes.md", "f6666", 400, "file")
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
            val dummyContent = when {
                path.endsWith(".json") -> "{\n  \"name\": \"kruzenlab-uploader\",\n  \"version\": \"2.0.0\",\n  \"author\": \"DEV BAGJA\"\n}"
                path.endsWith(".yml") || path.endsWith(".yaml") -> "name: Build APK\non:\n  push:\n    branches: [ main ]\njobs:\n  build:\n    runs-on: ubuntu-latest"
                path.endsWith(".kt") -> "package com.kruzenlab\n\nclass MainActivity {\n    fun greet() = \"Hello kruzenlab-uploader\"\n}"
                else -> "# KruzenLab Cloud Project\n\nFile: $path\nDeveloped by DEV BAGJA / KruzenLab ⚡"
            }
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

    /**
     * Deletes an entire directory and all nested files in 1 single atomic Git commit
     * via Git Data API recursive tree rebuild.
     */
    suspend fun deleteDirectory(
        owner: String,
        repo: String,
        branch: String,
        folderPath: String
    ): Result<CommitResult> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        val cleanPrefix = folderPath.trim().removePrefix("/").removeSuffix("/") + "/"

        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val sha = "kzdel" + (1000..9999).random()
            dbHelper.addInMemoryLog("DELETE", "/repos/$owner/$repo/git/trees (demo)", 200, 35, "Atomic delete folder $cleanPrefix")
            return@withContext Result.success(
                CommitResult(
                    success = true,
                    commitSha = sha,
                    fullName = "$owner/$repo",
                    fileCount = 1
                )
            )
        }

        try {
            val authHeader = "Bearer $currentToken"
            val refResp = api.getBranchRef(authHeader, owner, repo, branch)
            if (!refResp.isSuccessful || refResp.body() == null) {
                return@withContext Result.failure(Exception("Branch $branch not found (HTTP ${refResp.code()})"))
            }

            val parentCommitSha = refResp.body()!!.`object`.sha
            val parentCommit = api.getCommit(authHeader, owner, repo, parentCommitSha)
            val fullTree = api.getGitTree(authHeader, owner, repo, parentCommit.tree.sha, recursive = 1)

            // Keep only blobs outside the deleted folder prefix
            val remainingBlobs = fullTree.tree
                .filter { it.type == "blob" && !it.path.startsWith(cleanPrefix) }
                .map { TreeItem(path = it.path, mode = it.mode, type = "blob", sha = it.sha) }

            // Create brand new root tree without base_tree so deleted paths vanish cleanly
            val newTreeResp = api.createTree(
                auth = authHeader,
                owner = owner,
                repo = repo,
                request = TreeRequest(tree = remainingBlobs, baseTree = null)
            )

            val commitResp = api.createCommit(
                auth = authHeader,
                owner = owner,
                repo = repo,
                request = CommitRequest(
                    message = "Delete directory $cleanPrefix via kruzenlab-uploader",
                    tree = newTreeResp.sha,
                    parents = listOf(parentCommitSha)
                )
            )

            api.updateRef(
                auth = authHeader,
                owner = owner,
                repo = repo,
                branch = branch,
                request = RefRequest(sha = commitResp.sha)
            )

            dbHelper.recordCommit(
                CommitRecord(
                    repoName = repo,
                    owner = owner,
                    commitSha = commitResp.sha,
                    branch = branch,
                    fileCount = 0,
                    commitMessage = "Delete directory $cleanPrefix",
                    htmlUrl = commitResp.htmlUrl ?: "https://github.com/$owner/$repo/commit/${commitResp.sha}"
                )
            )

            Result.success(
                CommitResult(
                    success = true,
                    commitSha = commitResp.sha,
                    htmlUrl = commitResp.htmlUrl,
                    fullName = "$owner/$repo"
                )
            )
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

    // Branches
    suspend fun getBranches(owner: String, repo: String): Result<List<GitHubBranch>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success(
                listOf(
                    GitHubBranch("main", BranchCommit("c11188a90", null)),
                    GitHubBranch("dev", BranchCommit("c22244b12", null)),
                    GitHubBranch("v2.0-release", BranchCommit("c33399c77", null))
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

    suspend fun createBranch(
        owner: String,
        repo: String,
        newBranchName: String,
        sourceSha: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanBranch = newBranchName.trim().replace("\\s+".toRegex(), "-")
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            dbHelper.addInMemoryLog("POST", "/repos/$owner/$repo/git/refs (demo)", 201, 20, "Created branch $cleanBranch")
            return@withContext Result.success(true)
        }
        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.createRef(
                auth = authHeader,
                owner = owner,
                repo = repo,
                request = RefRequest(ref = "refs/heads/$cleanBranch", sha = sourceSha)
            )
            if (resp.isSuccessful) {
                dbHelper.addInMemoryLog("POST", "/repos/$owner/$repo/git/refs", 201, 35, "Created branch $cleanBranch")
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to create branch: HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Releases + Binary Asset Upload
    suspend fun getReleases(owner: String, repo: String): Result<List<GitHubRelease>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success(
                listOf(
                    GitHubRelease(
                        id = 1,
                        tagName = "v2.0.0",
                        name = "KruzenLab Release v2.0.0",
                        body = "Includes WorkManager background push, Downloads Manager, and Multi-Mode File Editor.",
                        prerelease = false,
                        createdAt = "2026-11-18T09:00:00Z",
                        htmlUrl = "https://github.com/$owner/$repo/releases/tag/v2.0.0"
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

    suspend fun createReleaseWithAssets(
        owner: String,
        repo: String,
        tagName: String,
        name: String,
        body: String,
        prerelease: Boolean = false,
        assets: List<ReleaseAssetAttachment> = emptyList(),
        onProgress: (String) -> Unit = {}
    ): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            onProgress("Mempublikasikan demo release & ${assets.size} lampiran...")
            val demoRel = GitHubRelease(
                id = System.currentTimeMillis(),
                tagName = tagName,
                name = name,
                body = body,
                prerelease = prerelease,
                createdAt = "Baru saja",
                htmlUrl = "https://github.com/$owner/$repo/releases/tag/$tagName"
            )
            dbHelper.addInMemoryLog("POST", "/repos/$owner/$repo/releases (demo)", 201, 40, "Release $tagName + ${assets.size} assets")
            return@withContext Result.success(demoRel)
        }

        try {
            val authHeader = "Bearer $currentToken"
            onProgress("Membuat release $tagName di GitHub...")
            val release = api.createRelease(
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

            val rawUploadUrl = release.uploadUrl
                ?: "https://uploads.github.com/repos/$owner/$repo/releases/${release.id}/assets"
            val baseUploadUrl = rawUploadUrl.substringBefore("{")

            assets.forEachIndexed { idx, asset ->
                onProgress("Mengunggah asset ${idx + 1}/${assets.size}: ${asset.displayName}...")
                val bytes = readAttachmentBytes(asset)
                val encodedName = URLEncoder.encode(asset.displayName, "UTF-8")
                val targetUrl = "$baseUploadUrl?name=$encodedName"
                val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())

                val req = Request.Builder()
                    .url(targetUrl)
                    .header("Authorization", authHeader)
                    .header("Accept", "application/vnd.github+json")
                    .post(requestBody)
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) {
                    val errText = resp.body?.string().orEmpty()
                    resp.close()
                    throw Exception("Gagal unggah asset ${asset.displayName} (HTTP ${resp.code}): $errText")
                }
                resp.close()
            }

            Result.success(release)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readAttachmentBytes(asset: ReleaseAssetAttachment): ByteArray {
        if (!asset.localFilePath.isNullOrBlank()) {
            val f = File(asset.localFilePath)
            if (f.exists()) return f.readBytes()
        }
        if (!asset.contentUriString.isNullOrBlank()) {
            val uri = Uri.parse(asset.contentUriString)
            context.contentResolver.openInputStream(uri)?.use { return it.readBytes() }
        }
        throw IllegalStateException("Berkas asset tidak ditemukan: ${asset.displayName}")
    }

    // Actions Workflows, Build Logs, Jobs & Artifacts
    suspend fun getWorkflowRuns(owner: String, repo: String): Result<List<WorkflowRun>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success(
                listOf(
                    WorkflowRun(
                        id = 1001,
                        name = "Android CI Build & Log Monitor",
                        headBranch = "main",
                        status = "completed",
                        conclusion = "success",
                        htmlUrl = "https://github.com/$owner/$repo/actions/runs/1001",
                        createdAt = "2026-11-19T08:10:00Z",
                        updatedAt = "2026-11-19T08:12:48Z"
                    ),
                    WorkflowRun(
                        id = 1002,
                        name = "Android CI Build & Log Monitor",
                        headBranch = "feature/folder-upload",
                        status = "completed",
                        conclusion = "failure",
                        htmlUrl = "https://github.com/$owner/$repo/actions/runs/1002",
                        createdAt = "2026-11-19T07:45:00Z",
                        updatedAt = "2026-11-19T07:46:19Z"
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

    suspend fun getWorkflowJobs(owner: String, repo: String, runId: Long): Result<List<WorkflowJob>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val isFail = runId == 1002L
            return@withContext Result.success(
                listOf(
                    WorkflowJob(
                        id = runId * 10 + 1,
                        runId = runId,
                        name = "Build Debug APK & Capture Gradle Logs",
                        status = "completed",
                        conclusion = if (isFail) "failure" else "success",
                        startedAt = "2026-11-19T08:10:05Z",
                        completedAt = if (isFail) "2026-11-19T08:11:20Z" else "2026-11-19T08:12:45Z",
                        steps = listOf(
                            WorkflowStep("Set up job", "completed", "success", 1),
                            WorkflowStep("Checkout repository", "completed", "success", 2),
                            WorkflowStep("Set up JDK 21", "completed", "success", 3),
                            WorkflowStep("Build Debug APK (with gradle-build.log)", "completed", if (isFail) "failure" else "success", 4),
                            WorkflowStep("Upload APK & Error Logs (Always)", "completed", "success", 5)
                        )
                    )
                )
            )
        }
        try {
            val resp = api.getWorkflowJobs("Bearer $currentToken", owner, repo, runId)
            Result.success(resp.jobs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getJobLogs(owner: String, repo: String, jobId: Long): Result<String> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val isFailJob = (jobId / 10L) == 1002L
            val logText = if (isFailJob) {
                """
                2026-11-19T07:45:10Z > Configure project :app
                2026-11-19T07:45:24Z > Task :app:checkDebugAarMetadata UP-TO-DATE
                2026-11-19T07:45:42Z > Task :app:compileDebugKotlin
                2026-11-19T07:45:55Z e: file:///home/runner/work/$repo/app/src/main/java/com/example/App.kt:42:18 Unresolved reference 'missingSymbol'.
                2026-11-19T07:45:56Z FAILURE: Build failed with an exception.
                2026-11-19T07:45:56Z * What went wrong:
                2026-11-19T07:45:56Z Execution failed for task ':app:compileDebugKotlin'.
                2026-11-19T07:45:56Z > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers
                2026-11-19T07:46:12Z Uploading artifact kruzenlab-build-output (if: always()) -> gradle-build.log included!
                """.trimIndent()
            } else {
                """
                2026-11-19T08:10:12Z > Configure project :app
                2026-11-19T08:10:40Z > Task :app:compileDebugKotlin
                2026-11-19T08:11:55Z > Task :app:assembleDebug
                2026-11-19T08:12:38Z BUILD SUCCESSFUL in 2m 26s
                2026-11-19T08:12:42Z Uploading app/build/outputs/apk/debug/app-debug.apk & gradle-build.log
                """.trimIndent()
            }
            return@withContext Result.success(logText)
        }
        try {
            val resp = api.downloadJobLogs("Bearer $currentToken", owner, repo, jobId)
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(Exception("Gagal mengambil log job (HTTP ${resp.code()})"))
            }
            val text = resp.body()!!.string()
            Result.success(text.ifBlank { "Log job #$jobId kosong." })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadAndExtractArtifactToCache(
        owner: String,
        repo: String,
        artifact: WorkflowArtifact
    ): Result<List<ExtractedArtifactFile>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        val cacheRoot = getCiCdArtifactCacheDir()
        val tempZip = File(cacheRoot, "artifact_${artifact.id}_${System.currentTimeMillis()}.zip")
        val extractDir = File(cacheRoot, "extracted_${artifact.id}_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            if (_isDemoMode.value || currentToken.isNullOrBlank()) {
                ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                    zos.putNextEntry(ZipEntry("apk/debug/app-debug.apk"))
                    zos.write("PK\u0003\u0004KruzenLab Build APK Output for $owner/$repo (${artifact.name})".toByteArray())
                    zos.closeEntry()

                    zos.putNextEntry(ZipEntry("logs/gradle-build.log"))
                    val sampleGradleLog = """
                        > Task :app:preBuild UP-TO-DATE
                        > Task :app:compileDebugKotlin
                        e: Warning demo line in MainScreen.kt:18: unchecked cast
                        > Task :app:assembleDebug
                        FAILURE: Sample highlighted line if any error occurs
                        Execution failed preview sample line
                        BUILD SUCCESSFUL in 26s
                    """.trimIndent()
                    zos.write(sampleGradleLog.toByteArray())
                    zos.closeEntry()
                }
            } else {
                val resp = api.downloadArtifactZip("Bearer $currentToken", owner, repo, artifact.id)
                if (!resp.isSuccessful || resp.body() == null) {
                    return@withContext Result.failure(Exception("Gagal mengunduh artifact ZIP (HTTP ${resp.code()})"))
                }
                resp.body()!!.byteStream().use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                        }
                    }
                }
            }

            val extractedList = mutableListOf<ExtractedArtifactFile>()
            val extractRootCanonical = extractDir.canonicalPath
            val textExtensions = setOf("log", "txt", "json", "yml", "yaml", "md", "xml", "kt", "kts", "pro", "properties")

            ZipInputStream(FileInputStream(tempZip)).use { zis ->
                var entry = zis.nextEntry
                val buf = ByteArray(8192)
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val relPath = entry.name.replace("\\", "/").removePrefix("/")
                        val targetFile = File(extractDir, relPath)
                        val targetCanonical = targetFile.canonicalPath
                        if (!targetCanonical.startsWith(extractRootCanonical + File.separator)) {
                            throw SecurityException("Invalid ZIP entry path: $relPath")
                        }
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            var n: Int
                            while (zis.read(buf).also { n = it } != -1) {
                                fos.write(buf, 0, n)
                            }
                        }
                        val fileName = targetFile.name
                        val ext = fileName.substringAfterLast('.', "").lowercase()
                        extractedList.add(
                            ExtractedArtifactFile(
                                name = fileName,
                                relativePath = relPath,
                                localCachePath = targetFile.absolutePath,
                                sizeBytes = targetFile.length(),
                                isApk = ext == "apk",
                                isTextLike = ext in textExtensions
                            )
                        )
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Delete temporary .zip immediately after extraction
            tempZip.delete()
            dbHelper.addInMemoryLog(
                "GET",
                "/actions/artifacts/${artifact.id}/zip",
                200,
                140,
                "Extracted ${extractedList.size} files to cacheDir"
            )
            Result.success(extractedList.sortedWith(compareBy({ !it.isApk }, { it.relativePath.lowercase() })))
        } catch (e: Exception) {
            tempZip.delete()
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

    suspend fun getWorkflowLogs(owner: String, repo: String, runId: Long): Result<String> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val demoLogs = """
                === Workflow Run #$runId — $owner/$repo ===
                [00:01] Setting up JDK 21 Temurin...
                [00:04] Restoring Gradle dependencies cache...
                [00:11] > Task :app:compileDebugKotlin
                [00:25] > Task :app:assembleDebug
                [00:28] BUILD SUCCESSFUL in 27s
                [00:29] Uploading artifact: app-debug.apk (8.4 MB)
            """.trimIndent()
            return@withContext Result.success(demoLogs)
        }

        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.downloadWorkflowLogs(authHeader, owner, repo, runId)
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(Exception("Gagal mengunduh log (HTTP ${resp.code()})"))
            }

            val bytes = resp.body()!!.bytes()
            val sb = StringBuilder()
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            sb.appendLine("=== ${entry.name} ===")
                            val text = zis.readBytes().toString(Charsets.UTF_8)
                            sb.appendLine(text)
                            sb.appendLine()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (_: Exception) {
                sb.append(bytes.toString(Charsets.UTF_8))
            }

            Result.success(sb.toString().ifBlank { "Log kosong untuk run #$runId" })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWorkflowArtifacts(owner: String, repo: String, runId: Long): Result<List<WorkflowArtifact>> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success(
                listOf(
                    WorkflowArtifact(
                        id = 9001L + runId,
                        name = "kruzenlab-debug-apk",
                        sizeInBytes = 8_420_110L,
                        expired = false,
                        createdAt = "Baru saja"
                    )
                )
            )
        }

        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.getWorkflowArtifacts(authHeader, owner, repo, runId)
            Result.success(resp.artifacts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadArtifactToInternalStorage(
        owner: String,
        repo: String,
        artifact: WorkflowArtifact,
        runId: Long
    ): Result<DownloadRecord> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        val fileName = "${artifact.name.replace("\\s+".toRegex(), "_")}.zip"
        val destFile = File(getDownloadsDir(), "${System.currentTimeMillis()}_$fileName")

        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            // Create a valid demo ZIP containing a sample APK marker entry so user can inspect/test
            java.util.zip.ZipOutputStream(FileOutputStream(destFile)).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("app-debug.apk"))
                zos.write("PK\u0003\u0004KruzenLab Demo APK Artifact for $owner/$repo Run #$runId".toByteArray())
                zos.closeEntry()
            }
            val rec = DownloadRecord(
                repoFullName = "$owner/$repo",
                fileName = fileName,
                fileType = "zip",
                localPath = destFile.absolutePath,
                sizeBytes = destFile.length(),
                sourceRunId = runId
            )
            val id = dbHelper.insertDownload(rec)
            return@withContext Result.success(rec.copy(id = id))
        }

        try {
            val authHeader = "Bearer $currentToken"
            val resp = api.downloadArtifactZip(authHeader, owner, repo, artifact.id)
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(Exception("Gagal mengunduh artifak (HTTP ${resp.code()})"))
            }

            resp.body()!!.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            val rec = DownloadRecord(
                repoFullName = "$owner/$repo",
                fileName = fileName,
                fileType = "zip",
                localPath = destFile.absolutePath,
                sizeBytes = destFile.length(),
                sourceRunId = runId
            )
            val id = dbHelper.insertDownload(rec)
            dbHelper.addInMemoryLog("GET", "/actions/artifacts/${artifact.id}/zip", 200, 120, "Saved $fileName")
            Result.success(rec.copy(id = id))
        } catch (e: Exception) {
            if (destFile.exists()) destFile.delete()
            Result.failure(e)
        }
    }

    suspend fun saveLogToDownloads(
        owner: String,
        repo: String,
        runId: Long,
        logText: String
    ): Result<DownloadRecord> = withContext(Dispatchers.IO) {
        val fileName = "${repo}_run_${runId}.log"
        val destFile = File(getDownloadsDir(), "${System.currentTimeMillis()}_$fileName")
        destFile.writeText(logText)
        val rec = DownloadRecord(
            repoFullName = "$owner/$repo",
            fileName = fileName,
            fileType = "log",
            localPath = destFile.absolutePath,
            sizeBytes = destFile.length(),
            sourceRunId = runId
        )
        val id = dbHelper.insertDownload(rec)
        Result.success(rec.copy(id = id))
    }

    suspend fun extractZipDownloadEntry(downloadRecord: DownloadRecord): Result<List<DownloadRecord>> = withContext(Dispatchers.IO) {
        val srcFile = File(downloadRecord.localPath)
        if (!srcFile.exists()) return@withContext Result.failure(Exception("File tidak ditemukan di penyimpanan internal"))

        val extractedRecords = mutableListOf<DownloadRecord>()
        ZipInputStream(srcFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outName = entry.name.substringAfterLast('/')
                    val outFile = File(getDownloadsDir(), "${System.currentTimeMillis()}_$outName")
                    FileOutputStream(outFile).use { out ->
                        zis.copyTo(out)
                    }
                    val fType = when {
                        outName.endsWith(".apk", true) -> "apk"
                        outName.endsWith(".zip", true) -> "zip"
                        outName.endsWith(".log", true) || outName.endsWith(".txt", true) -> "log"
                        else -> "asset"
                    }
                    val rec = DownloadRecord(
                        repoFullName = downloadRecord.repoFullName,
                        fileName = outName,
                        fileType = fType,
                        localPath = outFile.absolutePath,
                        sizeBytes = outFile.length(),
                        sourceRunId = downloadRecord.sourceRunId
                    )
                    val id = dbHelper.insertDownload(rec)
                    extractedRecords.add(rec.copy(id = id))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Result.success(extractedRecords)
    }

    suspend fun deleteInternalDownload(record: DownloadRecord) = withContext(Dispatchers.IO) {
        try {
            val file = File(record.localPath)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
        dbHelper.deleteDownload(record.id)
    }

    // Direct Git Data API wrappers for WorkManager resumable UploadWorker
    suspend fun createBlobWithCheckpoint(
        owner: String,
        repo: String,
        contentBase64: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success("demo_blob_${System.currentTimeMillis()}_${(100..999).random()}")
        }
        try {
            val resp = api.createBlob("Bearer $currentToken", owner, repo, BlobRequest(contentBase64, "base64"))
            Result.success(resp.sha)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams binary file content directly into Base64 JSON request body via OkHttp RequestBody
     * without allocating large Base64 strings in JVM heap (supports up to 100 MB per single file).
     */
    suspend fun createBlobFromFileStreaming(
        owner: String,
        repo: String,
        stagingFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success("demo_blob_${System.currentTimeMillis()}_${(100..999).random()}")
        }
        if (!stagingFile.exists()) {
            return@withContext Result.failure(IllegalStateException("Staging file tidak ditemukan: ${stagingFile.name}"))
        }
        try {
            val rawBaseUrl = dbHelper.configState.value.githubApiUrl.ifBlank { GitHubApiService.DEFAULT_BASE_URL }
            val safeBase = if (rawBaseUrl.endsWith("/")) rawBaseUrl else "$rawBaseUrl/"
            val endpointUrl = "${safeBase}repos/$owner/$repo/git/blobs"

            val streamingBody = object : RequestBody() {
                override fun contentType() = "application/json; charset=utf-8".toMediaType()

                override fun writeTo(sink: BufferedSink) {
                    val out = sink.outputStream()
                    out.write("{\"encoding\":\"base64\",\"content\":\"".toByteArray(Charsets.UTF_8))
                    val nonClosingOut = object : FilterOutputStream(out) {
                        override fun close() {
                            out.flush()
                        }
                    }
                    Base64OutputStream(nonClosingOut, Base64.NO_WRAP).use { b64Out ->
                        FileInputStream(stagingFile).use { fis ->
                            val buf = ByteArray(16 * 1024)
                            var n: Int
                            while (fis.read(buf).also { n = it } != -1) {
                                b64Out.write(buf, 0, n)
                            }
                        }
                    }
                    out.write("\"}".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }

            val req = Request.Builder()
                .url(endpointUrl)
                .header("Authorization", "Bearer $currentToken")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .post(streamingBody)
                .build()

            var resp = httpClient.newCall(req).execute()
            var bodyStr = resp.body?.string().orEmpty()
            var code = resp.code
            resp.close()

            if (code == 401) {
                val recovered = tryRehydrateTokenFromSavedAccounts()
                if (!recovered.isNullOrBlank()) {
                    val retryReq = req.newBuilder().header("Authorization", "Bearer $recovered").build()
                    resp = httpClient.newCall(retryReq).execute()
                    bodyStr = resp.body?.string().orEmpty()
                    code = resp.code
                    resp.close()
                }
            }

            if (code !in 200..299) {
                if (code == 401 && dbHelper.configState.value.enableLogErrorAdvisor) {
                    NotificationHelper.notifyError(
                        context,
                        title = "HTTP 401 saat Upload Blob",
                        message = "Token kedaluwarsa atau ditolak. Ketuk untuk login ulang dengan Classic PAT.",
                        targetRoute = "login"
                    )
                }
                return@withContext Result.failure(Exception("Gagal upload blob HTTP $code: $bodyStr"))
            }
            val parsed = Gson().fromJson(bodyStr, BlobResponse::class.java)
            Result.success(parsed.sha)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Explorer Source ZIP Download, Direct File Save, Branch Compare/Merge/Default/Delete, Global Code Search & Vercel Webhook
    suspend fun downloadSourceZipToDownloads(
        owner: String,
        repo: String,
        branch: String
    ): Result<DownloadRecord> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        val safeBranch = branch.ifBlank { "main" }.replace("/", "_")
        val fileName = "$repo-$safeBranch.zip"
        val destFile = File(getDownloadsDir(), "${System.currentTimeMillis()}_$fileName")

        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            ZipOutputStream(FileOutputStream(destFile)).use { zos ->
                zos.putNextEntry(ZipEntry("$repo-$safeBranch/README.md"))
                zos.write("# $owner/$repo ($branch)\n\nDemo source ZIP snapshot by kruzenlab-uploader.\n".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("$repo-$safeBranch/src/Main.kt"))
                zos.write("fun main() = println(\"KruzenLab $repo@$branch\")\n".toByteArray())
                zos.closeEntry()
            }
            val rec = DownloadRecord(
                repoFullName = "$owner/$repo",
                fileName = fileName,
                fileType = "zip",
                localPath = destFile.absolutePath,
                sizeBytes = destFile.length(),
                sourceRunId = null
            )
            val id = dbHelper.insertDownload(rec)
            dbHelper.addInMemoryLog("GET", "/repos/$owner/$repo/zipball/$branch (demo)", 200, 25, "Saved $fileName")
            NotificationHelper.notifyActivity(
                context,
                title = "Source ZIP Tersimpan",
                message = "$fileName siap di tab Downloads.",
                targetRoute = "downloads",
                isDownload = true
            )
            return@withContext Result.success(rec.copy(id = id))
        }

        try {
            val resp = api.downloadRepoZipball("Bearer $currentToken", owner, repo, branch.ifBlank { "main" })
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(Exception("Gagal unduh Source ZIP (HTTP ${resp.code()})"))
            }
            resp.body()!!.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            val rec = DownloadRecord(
                repoFullName = "$owner/$repo",
                fileName = fileName,
                fileType = "zip",
                localPath = destFile.absolutePath,
                sizeBytes = destFile.length(),
                sourceRunId = null
            )
            val id = dbHelper.insertDownload(rec)
            NotificationHelper.notifyActivity(
                context,
                title = "Source ZIP Diunduh",
                message = "$fileName (${com.example.xiyu.util.ZipExtractor.formatSize(rec.sizeBytes)}) tersimpan di Downloads.",
                targetRoute = "downloads",
                isDownload = true
            )
            Result.success(rec.copy(id = id))
        } catch (e: Exception) {
            if (destFile.exists()) destFile.delete()
            Result.failure(e)
        }
    }

    suspend fun saveFileBytesToInternalDownloads(
        repoFullName: String,
        fileName: String,
        bytes: ByteArray,
        sourceRunId: Long? = null
    ): Result<DownloadRecord> = withContext(Dispatchers.IO) {
        try {
            val cleanName = fileName.substringAfterLast('/').ifBlank { "downloaded_file" }
            val destFile = File(getDownloadsDir(), "${System.currentTimeMillis()}_$cleanName")
            destFile.writeBytes(bytes)
            val fType = when {
                cleanName.endsWith(".apk", true) -> "apk"
                cleanName.endsWith(".zip", true) -> "zip"
                cleanName.endsWith(".log", true) || cleanName.endsWith(".txt", true) -> "log"
                else -> "asset"
            }
            val rec = DownloadRecord(
                repoFullName = repoFullName,
                fileName = cleanName,
                fileType = fType,
                localPath = destFile.absolutePath,
                sizeBytes = destFile.length(),
                sourceRunId = sourceRunId
            )
            val id = dbHelper.insertDownload(rec)
            NotificationHelper.notifyActivity(
                context,
                title = "File Tersimpan ke Downloads",
                message = "$cleanName siap dibuka dari tab Downloads.",
                targetRoute = "downloads",
                isDownload = true
            )
            Result.success(rec.copy(id = id))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun compareBranches(
        owner: String,
        repo: String,
        baseBranch: String,
        headBranch: String
    ): Result<BranchCompareResponse> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            return@withContext Result.success(
                BranchCompareResponse(
                    status = if (baseBranch == headBranch) "identical" else "ahead",
                    aheadBy = if (baseBranch == headBranch) 0 else 3,
                    behindBy = 0,
                    totalCommits = if (baseBranch == headBranch) 0 else 3,
                    htmlUrl = "https://github.com/$owner/$repo/compare/$baseBranch...$headBranch",
                    files = if (baseBranch == headBranch) emptyList() else listOf(
                        CompareFileItem("app/src/MainActivity.kt", "modified", 24, 5, 29),
                        CompareFileItem("README.md", "modified", 8, 2, 10),
                        CompareFileItem(".github/workflows/build.yml", "added", 42, 0, 42)
                    )
                )
            )
        }
        try {
            val basehead = "${URLEncoder.encode(baseBranch, "UTF-8")}...${URLEncoder.encode(headBranch, "UTF-8")}"
            val resp = api.compareBranches("Bearer $currentToken", owner, repo, basehead)
            Result.success(resp)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun mergeBranch(
        owner: String,
        repo: String,
        baseBranch: String,
        headBranch: String,
        commitMessage: String = "Merge $headBranch into $baseBranch via kruzenlab-uploader"
    ): Result<MergeBranchResponse> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            dbHelper.addInMemoryLog("POST", "/repos/$owner/$repo/merges (demo)", 201, 20, "Merged $headBranch -> $baseBranch")
            return@withContext Result.success(
                MergeBranchResponse(sha = "demo_merge_${System.currentTimeMillis()}", merged = true, message = "Demo merge successful")
            )
        }
        try {
            val resp = api.mergeBranches(
                "Bearer $currentToken",
                owner,
                repo,
                MergeBranchRequest(base = baseBranch, head = headBranch, commitMessage = commitMessage)
            )
            if (resp.isSuccessful) {
                val body = resp.body() ?: MergeBranchResponse(merged = true, message = "Fast-forward / up to date")
                NotificationHelper.notifyActivity(
                    context,
                    title = "Merge Branch Berhasil",
                    message = "$headBranch -> $baseBranch pada $owner/$repo",
                    targetRoute = "repo_explorer"
                )
                Result.success(body)
            } else {
                Result.failure(Exception("Gagal merge branch (HTTP ${resp.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setDefaultBranch(
        owner: String,
        repo: String,
        newDefaultBranch: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            dbHelper.addInMemoryLog("PATCH", "/repos/$owner/$repo (demo)", 200, 15, "Default branch -> $newDefaultBranch")
            return@withContext Result.success(true)
        }
        try {
            val resp = api.updateRepository(
                "Bearer $currentToken",
                owner,
                repo,
                UpdateRepoRequest(defaultBranch = newDefaultBranch)
            )
            if (resp.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Gagal ubah default branch (HTTP ${resp.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteBranch(
        owner: String,
        repo: String,
        branchName: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            dbHelper.addInMemoryLog("DELETE", "/repos/$owner/$repo/git/refs/heads/$branchName (demo)", 204, 15, "Deleted branch $branchName")
            return@withContext Result.success(true)
        }
        try {
            val resp = api.deleteBranchRef("Bearer $currentToken", owner, repo, branchName)
            if (resp.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Gagal hapus branch $branchName (HTTP ${resp.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchRepoCode(
        owner: String,
        repo: String,
        query: String
    ): Result<List<GlobalCodeSearchResult>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext Result.success(emptyList())
        val currentToken = _token.value
        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val demoCandidates = listOf(
                GlobalCodeSearchResult("MainActivity.kt", "app/src/MainActivity.kt", "f5555"),
                GlobalCodeSearchResult("build.gradle.kts", "app/build.gradle.kts", "f4444"),
                GlobalCodeSearchResult("build.yml", ".github/workflows/build.yml", "f0003"),
                GlobalCodeSearchResult("README.md", "README.md", "f1111"),
                GlobalCodeSearchResult("package.json", "package.json", "f2222")
            )
            return@withContext Result.success(
                demoCandidates.filter {
                    it.name.contains(cleanQuery, ignoreCase = true) ||
                        it.path.contains(cleanQuery, ignoreCase = true)
                }.ifEmpty { demoCandidates.take(2) }
            )
        }
        try {
            val fullQuery = "$cleanQuery repo:$owner/$repo"
            val resp = api.searchCode("Bearer $currentToken", fullQuery)
            Result.success(resp.items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun triggerVercelWebhook(
        repoFullName: String,
        hookUrl: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = hookUrl.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("URL Deploy Hook Vercel harus diawali https://"))
        }
        if (_isDemoMode.value) {
            dbHelper.markWebhookDeployed(repoFullName)
            dbHelper.addInMemoryLog("POST", "/vercel-hook (demo)", 201, 30, "Triggered demo deploy for $repoFullName")
            NotificationHelper.notifyActivity(
                context,
                title = "Vercel Deploy Dipicu",
                message = "Webhook deployment berhasil dikirim untuk $repoFullName (Demo).",
                targetRoute = "repo_explorer"
            )
            return@withContext Result.success("Demo Vercel Deploy Hook berhasil dipicu!")
        }
        try {
            val req = Request.Builder()
                .url(cleanUrl)
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string().orEmpty()
            resp.close()
            if (code in 200..299) {
                dbHelper.markWebhookDeployed(repoFullName)
                NotificationHelper.notifyActivity(
                    context,
                    title = "Vercel Deploy Dipicu",
                    message = "Deployment build dimulai untuk $repoFullName (HTTP $code).",
                    targetRoute = "repo_explorer"
                )
                Result.success("Vercel Deploy dimulai (HTTP $code)")
            } else {
                Result.failure(Exception("Vercel Hook ditolak (HTTP $code): $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun commitTreeAndPush(
        owner: String,
        repo: String,
        branch: String,
        message: String,
        treeItems: List<TreeItem>
    ): Result<CommitResult> = withContext(Dispatchers.IO) {
        val currentToken = _token.value
        val cleanOwner = owner.trim()
        val cleanRepo = repo.trim().replace("\\s+".toRegex(), "-")
        val cleanBranch = branch.trim().ifEmpty { "main" }
        val commitMsg = message.trim().ifEmpty { "Batch upload via kruzenlab-uploader" }

        if (_isDemoMode.value || currentToken.isNullOrBlank()) {
            val mockSha = "kz" + (1000000..9999999).random() + "ff"
            val commitUrl = "https://github.com/$cleanOwner/$cleanRepo/commit/$mockSha"
            dbHelper.recordCommit(
                CommitRecord(
                    repoName = cleanRepo,
                    owner = cleanOwner,
                    commitSha = mockSha,
                    branch = cleanBranch,
                    fileCount = treeItems.size,
                    commitMessage = commitMsg,
                    htmlUrl = commitUrl
                )
            )
            return@withContext Result.success(
                CommitResult(
                    success = true,
                    commitSha = mockSha,
                    htmlUrl = commitUrl,
                    repoUrl = "https://github.com/$cleanOwner/$cleanRepo",
                    fullName = "$cleanOwner/$cleanRepo",
                    fileCount = treeItems.size
                )
            )
        }

        try {
            val authHeader = "Bearer $currentToken"
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
                    isBrandNewRepo = true
                }
            } catch (_: Exception) {
                isBrandNewRepo = true
            }

            val treeResp = api.createTree(
                auth = authHeader,
                owner = cleanOwner,
                repo = cleanRepo,
                request = TreeRequest(tree = treeItems, baseTree = baseTreeSha)
            )

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

            if (parentCommitSha != null && !isBrandNewRepo) {
                val updateResp = api.updateRef(
                    auth = authHeader,
                    owner = cleanOwner,
                    repo = cleanRepo,
                    branch = cleanBranch,
                    request = RefRequest(sha = commitResp.sha)
                )
                if (!updateResp.isSuccessful && updateResp.code() == 422) {
                    api.createRef(
                        auth = authHeader,
                        owner = cleanOwner,
                        repo = cleanRepo,
                        request = RefRequest(ref = "refs/heads/$cleanBranch", sha = commitResp.sha)
                    )
                }
            } else {
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
                    fileCount = treeItems.size,
                    commitMessage = commitMsg,
                    htmlUrl = commitUrl
                )
            )
            fetchRateLimitInternal(currentToken)

            Result.success(
                CommitResult(
                    success = true,
                    commitSha = commitResp.sha,
                    htmlUrl = commitUrl,
                    repoUrl = "https://github.com/$cleanOwner/$cleanRepo",
                    fullName = "$cleanOwner/$cleanRepo",
                    fileCount = treeItems.size
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Legacy direct call kept compatible
    suspend fun commitAndPush(
        owner: String,
        repo: String,
        branch: String,
        message: String,
        entries: List<FileEntry>,
        onProgress: (stage: String, percent: Int) -> Unit
    ): Result<CommitResult> = withContext(Dispatchers.IO) {
        val selectedFiles = entries.filter { it.isSelected }
        if (selectedFiles.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Please select at least one file."))
        }

        val treeItems = mutableListOf<TreeItem>()
        for (i in selectedFiles.indices) {
            val file = selectedFiles[i]
            val pct = ((i + 1) * 75) / selectedFiles.size
            onProgress("Mengunggah blob ${i + 1}/${selectedFiles.size}: ${file.path}", pct)
            val blobRes = createBlobWithCheckpoint(owner, repo, file.contentBase64)
            if (blobRes.isFailure) {
                return@withContext Result.failure(blobRes.exceptionOrNull() ?: Exception("Blob upload failed"))
            }
            treeItems.add(TreeItem(path = file.path.removePrefix("/"), sha = blobRes.getOrThrow()))
        }

        onProgress("Membuat Git Tree & Commit tunggal…", 90)
        val commitRes = commitTreeAndPush(owner, repo, branch, message, treeItems)
        if (commitRes.isSuccess) {
            onProgress("Selesai!", 100)
        }
        commitRes
    }
}
