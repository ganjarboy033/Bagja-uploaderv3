package com.example.xiyu.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.xiyu.data.model.AccountEntity
import com.example.xiyu.data.model.DownloadRecord
import com.example.xiyu.data.model.UploadQueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AppConfigEntity(
    val clientId: String = "Ov23lizAXWwIXYOXCLKg",
    val clientSecret: String = "1b02b712f931cc0eed574a1c98a581f63d5fadb7",
    val sessionPassword: String = "bagja_uploader_secret_session_key_32char_min",
    val githubApiUrl: String = "https://api.github.com/",
    val defaultBranch: String = "main",
    val autoReadme: Boolean = true,
    val autoLicense: String = "MIT",
    val autoGitignore: String = "Node"
) {
    val effectiveClientId: String
        get() = clientId.ifBlank { "Ov23lizAXWwIXYOXCLKg" }

    val effectiveClientSecret: String
        get() = clientSecret.ifBlank { "1b02b712f931cc0eed574a1c98a581f63d5fadb7" }

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
        const val DATABASE_VERSION = 3

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

        private const val TABLE_DOWNLOADS = "downloads"
        private const val COL_DL_REPO = "repo_full_name"
        private const val COL_DL_FILENAME = "file_name"
        private const val COL_DL_TYPE = "file_type"
        private const val COL_DL_LOCAL_PATH = "local_path"
        private const val COL_DL_SIZE = "size_bytes"
        private const val COL_DL_RUN_ID = "source_run_id"
        private const val COL_DL_CREATED_AT = "created_at"

        private const val TABLE_UPLOAD_QUEUE = "upload_queue"
        private const val COL_UQ_BATCH_ID = "batch_id"
        private const val COL_UQ_OWNER = "owner"
        private const val COL_UQ_REPO = "repo"
        private const val COL_UQ_BRANCH = "branch"
        private const val COL_UQ_MSG = "commit_message"
        private const val COL_UQ_MODE = "upload_mode"
        private const val COL_UQ_FILE_PATH = "file_path"
        private const val COL_UQ_LOCAL_PATH = "local_staging_path"
        private const val COL_UQ_SIZE = "size_bytes"
        private const val COL_UQ_BLOB_SHA = "blob_sha"
        private const val COL_UQ_STATUS = "status"
        private const val COL_UQ_UPDATED_AT = "updated_at"

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

    private val _downloads = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val downloads: StateFlow<List<DownloadRecord>> = _downloads.asStateFlow()

    private val _uploadQueue = MutableStateFlow<List<UploadQueueItem>>(emptyList())
    val uploadQueue: StateFlow<List<UploadQueueItem>> = _uploadQueue.asStateFlow()

    init {
        loadConfigSynchronously()
        refreshLogsSynchronously()
        refreshCommitsSynchronously()
        refreshAccountsSynchronously()
        refreshDownloadsSynchronously()
        refreshUploadQueueSynchronously()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONFIG (
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
            CREATE TABLE IF NOT EXISTS $TABLE_ACCOUNTS (
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
            CREATE TABLE IF NOT EXISTS $TABLE_COMMITS (
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
            CREATE TABLE IF NOT EXISTS $TABLE_LOGS (
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

        createDownloadsAndQueueTables(db)

        val initialValues = ContentValues().apply {
            put(COL_ID, 1)
            put(COL_CLIENT_ID, "Ov23lizAXWwIXYOXCLKg")
            put(COL_CLIENT_SECRET, "1b02b712f931cc0eed574a1c98a581f63d5fadb7")
            put(COL_SESSION_PASSWORD, "bagja_uploader_secret_session_key_32char_min")
            put(COL_API_URL, "https://api.github.com/")
            put(COL_DEFAULT_BRANCH, "main")
            put(COL_AUTO_README, 1)
            put(COL_AUTO_LICENSE, "MIT")
            put(COL_AUTO_GITIGNORE, "Node")
        }
        db.insertWithOnConflict(TABLE_CONFIG, null, initialValues, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun createDownloadsAndQueueTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DOWNLOADS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DL_REPO TEXT,
                $COL_DL_FILENAME TEXT,
                $COL_DL_TYPE TEXT,
                $COL_DL_LOCAL_PATH TEXT,
                $COL_DL_SIZE INTEGER,
                $COL_DL_RUN_ID INTEGER,
                $COL_DL_CREATED_AT INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_UPLOAD_QUEUE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_UQ_BATCH_ID TEXT,
                $COL_UQ_OWNER TEXT,
                $COL_UQ_REPO TEXT,
                $COL_UQ_BRANCH TEXT,
                $COL_UQ_MSG TEXT,
                $COL_UQ_MODE TEXT,
                $COL_UQ_FILE_PATH TEXT,
                $COL_UQ_LOCAL_PATH TEXT,
                $COL_UQ_SIZE INTEGER,
                $COL_UQ_BLOB_SHA TEXT,
                $COL_UQ_STATUS TEXT,
                $COL_UQ_UPDATED_AT INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            createDownloadsAndQueueTables(db)
            // Backfill blank OAuth credentials with exact verified defaults
            db.execSQL(
                "UPDATE $TABLE_CONFIG SET $COL_CLIENT_ID = 'Ov23lizAXWwIXYOXCLKg' WHERE $COL_CLIENT_ID IS NULL OR $COL_CLIENT_ID = ''"
            )
            db.execSQL(
                "UPDATE $TABLE_CONFIG SET $COL_CLIENT_SECRET = '1b02b712f931cc0eed574a1c98a581f63d5fadb7' WHERE $COL_CLIENT_SECRET IS NULL OR $COL_CLIENT_SECRET = ''"
            )
        }
    }

    private fun loadConfigSynchronously() {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_CONFIG WHERE $COL_ID = 1", null)
            if (cursor.moveToFirst()) {
                val rawCid = cursor.getString(cursor.getColumnIndexOrThrow(COL_CLIENT_ID)) ?: ""
                val rawCsec = cursor.getString(cursor.getColumnIndexOrThrow(COL_CLIENT_SECRET)) ?: ""
                val entity = AppConfigEntity(
                    clientId = rawCid.ifBlank { "Ov23lizAXWwIXYOXCLKg" },
                    clientSecret = rawCsec.ifBlank { "1b02b712f931cc0eed574a1c98a581f63d5fadb7" },
                    sessionPassword = (cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_PASSWORD)) ?: "")
                        .ifBlank { "bagja_uploader_secret_session_key_32char_min" },
                    githubApiUrl = (cursor.getString(cursor.getColumnIndexOrThrow(COL_API_URL)) ?: "")
                        .ifBlank { "https://api.github.com/" },
                    defaultBranch = (cursor.getString(cursor.getColumnIndexOrThrow(COL_DEFAULT_BRANCH)) ?: "")
                        .ifBlank { "main" },
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
        val normalized = config.copy(
            clientId = config.clientId.trim().ifEmpty { "Ov23lizAXWwIXYOXCLKg" },
            clientSecret = config.clientSecret.trim().ifEmpty { "1b02b712f931cc0eed574a1c98a581f63d5fadb7" },
            sessionPassword = config.sessionPassword.trim().ifEmpty { "bagja_uploader_secret_session_key_32char_min" },
            githubApiUrl = config.githubApiUrl.trim().ifEmpty { "https://api.github.com/" },
            defaultBranch = config.defaultBranch.trim().ifEmpty { "main" }
        )
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_CLIENT_ID, normalized.clientId)
            put(COL_CLIENT_SECRET, normalized.clientSecret)
            put(COL_SESSION_PASSWORD, normalized.sessionPassword)
            put(COL_API_URL, normalized.githubApiUrl)
            put(COL_DEFAULT_BRANCH, normalized.defaultBranch)
            put(COL_AUTO_README, if (normalized.autoReadme) 1 else 0)
            put(COL_AUTO_LICENSE, normalized.autoLicense.trim())
            put(COL_AUTO_GITIGNORE, normalized.autoGitignore.trim())
        }
        db.update(TABLE_CONFIG, values, "$COL_ID = 1", null)
        _configState.value = normalized
    }

    // Downloads CRUD
    suspend fun insertDownload(record: DownloadRecord): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_DL_REPO, record.repoFullName)
            put(COL_DL_FILENAME, record.fileName)
            put(COL_DL_TYPE, record.fileType)
            put(COL_DL_LOCAL_PATH, record.localPath)
            put(COL_DL_SIZE, record.sizeBytes)
            if (record.sourceRunId != null) put(COL_DL_RUN_ID, record.sourceRunId) else putNull(COL_DL_RUN_ID)
            put(COL_DL_CREATED_AT, record.createdAt)
        }
        val id = db.insert(TABLE_DOWNLOADS, null, values)
        refreshDownloadsSynchronously()
        id
    }

    suspend fun deleteDownload(id: Long) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete(TABLE_DOWNLOADS, "$COL_ID = ?", arrayOf(id.toString()))
        refreshDownloadsSynchronously()
    }

    fun refreshDownloads() {
        refreshDownloadsSynchronously()
    }

    private fun refreshDownloadsSynchronously() {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_DOWNLOADS ORDER BY $COL_DL_CREATED_AT DESC", null)
            val list = mutableListOf<DownloadRecord>()
            while (cursor.moveToNext()) {
                val runIdIdx = cursor.getColumnIndexOrThrow(COL_DL_RUN_ID)
                list.add(
                    DownloadRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                        repoFullName = cursor.getString(cursor.getColumnIndexOrThrow(COL_DL_REPO)) ?: "",
                        fileName = cursor.getString(cursor.getColumnIndexOrThrow(COL_DL_FILENAME)) ?: "",
                        fileType = cursor.getString(cursor.getColumnIndexOrThrow(COL_DL_TYPE)) ?: "asset",
                        localPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_DL_LOCAL_PATH)) ?: "",
                        sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DL_SIZE)),
                        sourceRunId = if (cursor.isNull(runIdIdx)) null else cursor.getLong(runIdIdx),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DL_CREATED_AT))
                    )
                )
            }
            cursor.close()
            _downloads.value = list
        } catch (_: Exception) {}
    }

    // Background Upload Queue Checkpointing
    suspend fun enqueueUploadBatch(items: List<UploadQueueItem>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put(COL_UQ_BATCH_ID, item.batchId)
                    put(COL_UQ_OWNER, item.owner)
                    put(COL_UQ_REPO, item.repo)
                    put(COL_UQ_BRANCH, item.branch)
                    put(COL_UQ_MSG, item.commitMessage)
                    put(COL_UQ_MODE, item.uploadMode)
                    put(COL_UQ_FILE_PATH, item.filePath)
                    put(COL_UQ_LOCAL_PATH, item.localStagingPath)
                    put(COL_UQ_SIZE, item.sizeBytes)
                    put(COL_UQ_BLOB_SHA, item.blobSha)
                    put(COL_UQ_STATUS, item.status)
                    put(COL_UQ_UPDATED_AT, item.updatedAt)
                }
                db.insert(TABLE_UPLOAD_QUEUE, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshUploadQueueSynchronously()
    }

    fun getBatchItemsSync(batchId: String): List<UploadQueueItem> {
        val list = mutableListOf<UploadQueueItem>()
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_UPLOAD_QUEUE WHERE $COL_UQ_BATCH_ID = ? ORDER BY $COL_ID ASC",
                arrayOf(batchId)
            )
            while (cursor.moveToNext()) {
                list.add(
                    UploadQueueItem(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                        batchId = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_BATCH_ID)),
                        owner = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_OWNER)),
                        repo = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_REPO)),
                        branch = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_BRANCH)),
                        commitMessage = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_MSG)),
                        uploadMode = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_MODE)),
                        filePath = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_FILE_PATH)),
                        localStagingPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_LOCAL_PATH)),
                        sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UQ_SIZE)),
                        blobSha = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_BLOB_SHA)),
                        status = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_STATUS)),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UQ_UPDATED_AT))
                    )
                )
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    fun updateQueueItemCheckpointSync(itemId: Long, blobSha: String?, status: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                if (blobSha != null) put(COL_UQ_BLOB_SHA, blobSha)
                put(COL_UQ_STATUS, status)
                put(COL_UQ_UPDATED_AT, System.currentTimeMillis())
            }
            db.update(TABLE_UPLOAD_QUEUE, values, "$COL_ID = ?", arrayOf(itemId.toString()))
            refreshUploadQueueSynchronously()
        } catch (_: Exception) {}
    }

    fun markBatchDoneSync(batchId: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_UQ_STATUS, "DONE")
                put(COL_UQ_UPDATED_AT, System.currentTimeMillis())
            }
            db.update(TABLE_UPLOAD_QUEUE, values, "$COL_UQ_BATCH_ID = ?", arrayOf(batchId))
            refreshUploadQueueSynchronously()
        } catch (_: Exception) {}
    }

    private fun refreshUploadQueueSynchronously() {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_UPLOAD_QUEUE ORDER BY $COL_ID DESC LIMIT 100", null)
            val list = mutableListOf<UploadQueueItem>()
            while (cursor.moveToNext()) {
                list.add(
                    UploadQueueItem(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                        batchId = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_BATCH_ID)),
                        owner = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_OWNER)),
                        repo = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_REPO)),
                        branch = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_BRANCH)),
                        commitMessage = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_MSG)),
                        uploadMode = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_MODE)),
                        filePath = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_FILE_PATH)),
                        localStagingPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_LOCAL_PATH)),
                        sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UQ_SIZE)),
                        blobSha = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_BLOB_SHA)),
                        status = cursor.getString(cursor.getColumnIndexOrThrow(COL_UQ_STATUS)),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UQ_UPDATED_AT))
                    )
                )
            }
            cursor.close()
            _uploadQueue.value = list
        } catch (_: Exception) {}
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

    fun refreshAllSynchronously() {
        loadConfigSynchronously()
        refreshLogsSynchronously()
        refreshCommitsSynchronously()
        refreshAccountsSynchronously()
        refreshDownloadsSynchronously()
        refreshUploadQueueSynchronously()
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
        db.delete(TABLE_UPLOAD_QUEUE, null, null)
        val resetConfig = AppConfigEntity()
        saveConfig(resetConfig)
        _commitHistory.value = emptyList()
        _recentLogs.value = emptyList()
        _uploadQueue.value = emptyList()
    }
}
