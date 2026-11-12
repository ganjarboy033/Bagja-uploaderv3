package com.example.xiyu.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.xiyu.data.model.AccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AppConfigEntity(
    val clientId: String = "",
    val clientSecret: String = "",
    val sessionPassword: String = "bagja_uploader_secret_session_key_32char_min",
    val githubApiUrl: String = "https://api.github.com/",
    val defaultBranch: String = "main",
    val autoReadme: Boolean = true,
    val autoLicense: String = "MIT",
    val autoGitignore: String = "Node"
) {
    // Built-in fallback helpers
    val effectiveClientId: String
        get() = clientId.ifBlank { "Ov23lia8uQkruzenlab" }

    val effectiveClientSecret: String
        get() = clientSecret.ifBlank { "kruzen_sec_fallback_default" }

    val effectiveSessionPassword: String
        get() = sessionPassword.ifBlank { "bagja_uploader_secret_session_key_32char_min" }
}

data class CommitRecord(
    val id: Long = 0,
    val repoName: String,
    val owner: String,
    val commitSha: String,
    val branch: String,
    val fileCount: Int,
    val commitMessage: String,
    val htmlUrl: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class NetworkLogRecord(
    val id: Long = 0,
    val method: String,
    val url: String,
    val statusCode: Int,
    val durationMs: Long,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "kruzenlab_backend.db"
        const val DATABASE_VERSION = 2

        private const val TABLE_CONFIG = "app_config"
        private const val COL_ID = "id"
        private const val COL_CLIENT_ID = "client_id"
        private const val COL_CLIENT_SECRET = "client_secret"
        private const val COL_SESSION_PASSWORD = "session_password"
        private const val COL_API_URL = "github_api_url"
        private const val COL_DEFAULT_BRANCH = "default_branch"
        private const val COL_AUTO_README = "auto_readme"
        private const val COL_AUTO_LICENSE = "auto_license"
        private const val COL_AUTO_GITIGNORE = "auto_gitignore"

        private const val TABLE_ACCOUNTS = "saved_accounts"
        private const val COL_ACC_LOGIN = "login"
        private const val COL_ACC_NAME = "name"
        private const val COL_ACC_AVATAR = "avatar_url"
        private const val COL_ACC_TOKEN = "token"
        private const val COL_ACC_IS_OAUTH = "is_oauth"
        private const val COL_ACC_ADDED_AT = "added_at"

        private const val TABLE_COMMITS = "commit_history"
        private const val COL_REPO_NAME = "repo_name"
        private const val COL_OWNER = "owner"
        private const val COL_COMMIT_SHA = "commit_sha"
        private const val COL_BRANCH = "branch"
        private const val COL_FILE_COUNT = "file_count"
        private const val COL_MESSAGE = "commit_message"
        private const val COL_HTML_URL = "html_url"
        private const val COL_TIMESTAMP = "timestamp"

        private const val TABLE_LOGS = "network_logs"
        private const val COL_METHOD = "method"
        private const val COL_URL = "url"
        private const val COL_STATUS_CODE = "status_code"
        private const val COL_DURATION_MS = "duration_ms"
        private const val COL_SUMMARY = "summary"

        @Volatile
        private var instance: AppDatabaseHelper? = null

        fun getInstance(context: Context): AppDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: AppDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _configState = MutableStateFlow(AppConfigEntity())
    val configState: StateFlow<AppConfigEntity> = _configState.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<NetworkLogRecord>>(emptyList())
    val recentLogs: StateFlow<List<NetworkLogRecord>> = _recentLogs.asStateFlow()

    private val _commitHistory = MutableStateFlow<List<CommitRecord>>(emptyList())
    val commitHistory: StateFlow<List<CommitRecord>> = _commitHistory.asStateFlow()

    private val _savedAccounts = MutableStateFlow<List<AccountEntity>>(emptyList())
    val savedAccounts: StateFlow<List<AccountEntity>> = _savedAccounts.asStateFlow()

    init {
        loadConfigSynchronously()
        refreshLogsSynchronously()
        refreshCommitsSynchronously()
        refreshAccountsSynchronously()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CONFIG (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_CLIENT_ID TEXT,
                $COL_CLIENT_SECRET TEXT,
                $COL_SESSION_PASSWORD TEXT,
                $COL_API_URL TEXT,
                $COL_DEFAULT_BRANCH TEXT,
                $COL_AUTO_README INTEGER,
                $COL_AUTO_LICENSE TEXT,
                $COL_AUTO_GITIGNORE TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_ACCOUNTS (
                $COL_ACC_LOGIN TEXT PRIMARY KEY,
                $COL_ACC_NAME TEXT,
                $COL_ACC_AVATAR TEXT,
                $COL_ACC_TOKEN TEXT,
                $COL_ACC_IS_OAUTH INTEGER,
                $COL_ACC_ADDED_AT INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_COMMITS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_REPO_NAME TEXT,
                $COL_OWNER TEXT,
                $COL_COMMIT_SHA TEXT,
                $COL_BRANCH TEXT,
                $COL_FILE_COUNT INTEGER,
                $COL_MESSAGE TEXT,
                $COL_HTML_URL TEXT,
                $COL_TIMESTAMP INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_LOGS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_METHOD TEXT,
                $COL_URL TEXT,
                $COL_STATUS_CODE INTEGER,
                $COL_DURATION_MS INTEGER,
                $COL_SUMMARY TEXT,
                $COL_TIMESTAMP INTEGER
            )
            """.trimIndent()
        )

        val initialValues = ContentValues().apply {
            put(COL_ID, 1)
            put(COL_CLIENT_ID, "")
            put(COL_CLIENT_SECRET, "")
            put(COL_SESSION_PASSWORD, "bagja_uploader_secret_session_key_32char_min")
            put(COL_API_URL, "https://api.github.com/")
            put(COL_DEFAULT_BRANCH, "main")
            put(COL_AUTO_README, 1)
            put(COL_AUTO_LICENSE, "MIT")
            put(COL_AUTO_GITIGNORE, "Node")
        }
        db.insert(TABLE_CONFIG, null, initialValues)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CONFIG")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ACCOUNTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_COMMITS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    private fun loadConfigSynchronously() {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_CONFIG WHERE $COL_ID = 1", null)
            if (cursor.moveToFirst()) {
                val entity = AppConfigEntity(
                    clientId = cursor.getString(cursor.getColumnIndexOrThrow(COL_CLIENT_ID)) ?: "",
                    clientSecret = cursor.getString(cursor.getColumnIndexOrThrow(COL_CLIENT_SECRET)) ?: "",
                    sessionPassword = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_PASSWORD))
                        ?: "bagja_uploader_secret_session_key_32char_min",
                    githubApiUrl = cursor.getString(cursor.getColumnIndexOrThrow(COL_API_URL)) ?: "https://api.github.com/",
                    defaultBranch = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEFAULT_BRANCH)) ?: "main",
                    autoReadme = cursor.getInt(cursor.getColumnIndexOrThrow(COL_AUTO_README)) == 1,
                    autoLicense = cursor.getString(cursor.getColumnIndexOrThrow(COL_AUTO_LICENSE)) ?: "MIT",
                    autoGitignore = cursor.getString(cursor.getColumnIndexOrThrow(COL_AUTO_GITIGNORE)) ?: "Node"
                )
                _configState.value = entity
            }
            cursor.close()
        } catch (_: Exception) {}
    }

    suspend fun saveConfig(config: AppConfigEntity) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_CLIENT_ID, config.clientId.trim())
            put(COL_CLIENT_SECRET, config.clientSecret.trim())
            put(COL_SESSION_PASSWORD, config.sessionPassword.trim().ifEmpty { "bagja_uploader_secret_session_key_32char_min" })
            put(COL_API_URL, config.githubApiUrl.trim())
            put(COL_DEFAULT_BRANCH, config.defaultBranch.trim())
            put(COL_AUTO_README, if (config.autoReadme) 1 else 0)
            put(COL_AUTO_LICENSE, config.autoLicense.trim())
            put(COL_AUTO_GITIGNORE, config.autoGitignore.trim())
        }
        db.update(TABLE_CONFIG, values, "$COL_ID = 1", null)
        _configState.value = config
    }

    suspend fun saveAccount(account: AccountEntity) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ACC_LOGIN, account.login)
            put(COL_ACC_NAME, account.name)
            put(COL_ACC_AVATAR, account.avatarUrl)
            put(COL_ACC_TOKEN, account.token)
            put(COL_ACC_IS_OAUTH, if (account.isOAuth) 1 else 0)
            put(COL_ACC_ADDED_AT, account.addedAt)
        }
        db.insertWithOnConflict(TABLE_ACCOUNTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshAccountsSynchronously()
    }

    suspend fun removeAccount(login: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete(TABLE_ACCOUNTS, "$COL_ACC_LOGIN = ?", arrayOf(login))
        refreshAccountsSynchronously()
    }

    private fun refreshAccountsSynchronously() {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_ACCOUNTS ORDER BY $COL_ACC_ADDED_AT DESC", null)
            val list = mutableListOf<AccountEntity>()
            while (cursor.moveToNext()) {
                list.add(
                    AccountEntity(
                        login = cursor.getString(cursor.getColumnIndexOrThrow(COL_ACC_LOGIN)),
                        name = cursor.getString(cursor.getColumnIndexOrThrow(COL_ACC_NAME)),
                        avatarUrl = cursor.getString(cursor.getColumnIndexOrThrow(COL_ACC_AVATAR)),
                        token = cursor.getString(cursor.getColumnIndexOrThrow(COL_ACC_TOKEN)),
                        isOAuth = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ACC_IS_OAUTH)) == 1,
                        addedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ACC_ADDED_AT))
                    )
                )
            }
            cursor.close()
            _savedAccounts.value = list
        } catch (_: Exception) {}
    }

    suspend fun recordCommit(record: CommitRecord) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_REPO_NAME, record.repoName)
            put(COL_OWNER, record.owner)
            put(COL_COMMIT_SHA, record.commitSha)
            put(COL_BRANCH, record.branch)
            put(COL_FILE_COUNT, record.fileCount)
            put(COL_MESSAGE, record.commitMessage)
            put(COL_HTML_URL, record.htmlUrl)
            put(COL_TIMESTAMP, record.timestamp)
        }
        db.insert(TABLE_COMMITS, null, values)
        refreshCommitsSynchronously()
    }

    private fun refreshCommitsSynchronously() {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_COMMITS ORDER BY $COL_TIMESTAMP DESC LIMIT 50", null)
            val list = mutableListOf<CommitRecord>()
            while (cursor.moveToNext()) {
                list.add(
                    CommitRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                        repoName = cursor.getString(cursor.getColumnIndexOrThrow(COL_REPO_NAME)),
                        owner = cursor.getString(cursor.getColumnIndexOrThrow(COL_OWNER)),
                        commitSha = cursor.getString(cursor.getColumnIndexOrThrow(COL_COMMIT_SHA)),
                        branch = cursor.getString(cursor.getColumnIndexOrThrow(COL_BRANCH)),
                        fileCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_FILE_COUNT)),
                        commitMessage = cursor.getString(cursor.getColumnIndexOrThrow(COL_MESSAGE)),
                        htmlUrl = cursor.getString(cursor.getColumnIndexOrThrow(COL_HTML_URL)),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP))
                    )
                )
            }
            cursor.close()
            _commitHistory.value = list
        } catch (_: Exception) {}
    }

    fun addInMemoryLog(method: String, url: String, statusCode: Int, durationMs: Long, summary: String) {
        try {
            val record = NetworkLogRecord(
                id = System.currentTimeMillis(),
                method = method,
                url = url,
                statusCode = statusCode,
                durationMs = durationMs,
                summary = summary
            )
            val current = _recentLogs.value.toMutableList()
            current.add(0, record)
            _recentLogs.value = current.take(100)

            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_METHOD, method)
                put(COL_URL, url)
                put(COL_STATUS_CODE, statusCode)
                put(COL_DURATION_MS, durationMs)
                put(COL_SUMMARY, summary)
                put(COL_TIMESTAMP, record.timestamp)
            }
            db.insert(TABLE_LOGS, null, values)
        } catch (_: Exception) {}
    }

    private fun refreshLogsSynchronously() {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_LOGS ORDER BY $COL_TIMESTAMP DESC LIMIT 100", null)
            val list = mutableListOf<NetworkLogRecord>()
            while (cursor.moveToNext()) {
                list.add(
                    NetworkLogRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                        method = cursor.getString(cursor.getColumnIndexOrThrow(COL_METHOD)),
                        url = cursor.getString(cursor.getColumnIndexOrThrow(COL_URL)),
                        statusCode = cursor.getInt(cursor.getColumnIndexOrThrow(COL_STATUS_CODE)),
                        durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DURATION_MS)),
                        summary = cursor.getString(cursor.getColumnIndexOrThrow(COL_SUMMARY)),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP))
                    )
                )
            }
            cursor.close()
            _recentLogs.value = list
        } catch (_: Exception) {}
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete(TABLE_LOGS, null, null)
        _recentLogs.value = emptyList()
    }

    suspend fun resetDatabase() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete(TABLE_COMMITS, null, null)
        db.delete(TABLE_LOGS, null, null)
        val resetConfig = AppConfigEntity()
        saveConfig(resetConfig)
        _commitHistory.value = emptyList()
        _recentLogs.value = emptyList()
    }
}
