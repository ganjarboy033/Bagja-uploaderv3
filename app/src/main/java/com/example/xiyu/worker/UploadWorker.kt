package com.example.xiyu.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.xiyu.data.database.AppDatabaseHelper
import com.example.xiyu.data.model.TreeItem
import com.example.xiyu.data.repository.GitHubRepository
import java.io.File

class UploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_BATCH_ID = "batch_id"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_COMMIT_SHA = "commit_sha"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_FILE_COUNT = "file_count"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "kruzenlab_upload_channel"
        private const val NOTIFICATION_ID = 7071
    }

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing batch ID"))

        val dbHelper = AppDatabaseHelper.getInstance(applicationContext)
        val repository = GitHubRepository(applicationContext)

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "kruzenlab:upload_wakelock"
        )

        try {
            wakeLock.acquire(15 * 60 * 1000L) // max 15 minutes safeguard
            setForeground(createForegroundInfo("Mempersiapkan antrean upload...", 0))
        } catch (_: Exception) {
            // Foreground notification may fail if POST_NOTIFICATIONS denied on API 33+, continue work
        }

        return try {
            val batchItems = dbHelper.getBatchItemsSync(batchId)
            if (batchItems.isEmpty()) {
                return Result.failure(workDataOf(KEY_ERROR to "Antrean batch kosong"))
            }

            val first = batchItems.first()
            val owner = first.owner
            val repo = first.repo
            val branch = first.branch
            val commitMessage = first.commitMessage

            val treeItems = mutableListOf<TreeItem>()
            val total = batchItems.size

            batchItems.forEachIndexed { index, item ->
                if (item.status == "UPLOADED" && !item.blobSha.isNullOrBlank()) {
                    // Resuming: reuse previously checkpointed blob SHA
                    treeItems.add(TreeItem(path = item.filePath.removePrefix("/"), sha = item.blobSha))
                } else {
                    val pct = ((index + 1) * 80) / total
                    val stage = "Upload (${index + 1}/$total): ${item.filePath}"
                    setProgress(workDataOf(KEY_STAGE to stage, KEY_PROGRESS to pct))
                    try {
                        setForeground(createForegroundInfo(stage, pct))
                    } catch (_: Exception) {}

                    val stagingFile = File(item.localStagingPath)
                    if (!stagingFile.exists()) {
                        dbHelper.updateQueueItemCheckpointSync(item.id, null, "FAILED")
                        return Result.failure(workDataOf(KEY_ERROR to "Staging file hilang: ${item.filePath}"))
                    }
                    val blobRes = if (stagingFile.name.endsWith(".b64")) {
                        val base64Content = stagingFile.readText()
                        repository.createBlobWithCheckpoint(owner, repo, base64Content)
                    } else {
                        repository.createBlobFromFileStreaming(owner, repo, stagingFile)
                    }
                    if (blobRes.isFailure) {
                        dbHelper.updateQueueItemCheckpointSync(item.id, null, "FAILED")
                        // Retry on transient socket/network failure
                        return Result.retry()
                    }
                    val blobSha = blobRes.getOrThrow()
                    dbHelper.updateQueueItemCheckpointSync(item.id, blobSha, "UPLOADED")
                    treeItems.add(TreeItem(path = item.filePath.removePrefix("/"), sha = blobSha))
                }
            }

            setProgress(workDataOf(KEY_STAGE to "Membangun Git Tree & Commit tunggal...", KEY_PROGRESS to 92))
            try {
                setForeground(createForegroundInfo("Membangun Git Tree & Commit tunggal...", 92))
            } catch (_: Exception) {}

            val commitRes = repository.commitTreeAndPush(owner, repo, branch, commitMessage, treeItems)
            if (commitRes.isFailure) {
                return Result.retry()
            }

            val res = commitRes.getOrThrow()
            dbHelper.markBatchDoneSync(batchId)

            // Clean up staged files on disk
            batchItems.forEach { item ->
                try {
                    File(item.localStagingPath).delete()
                } catch (_: Exception) {}
            }

            setProgress(workDataOf(KEY_STAGE to "Push selesai!", KEY_PROGRESS to 100))

            Result.success(
                Data.Builder()
                    .putString(KEY_COMMIT_SHA, res.commitSha ?: "")
                    .putString(KEY_FULL_NAME, res.fullName ?: "$owner/$repo")
                    .putInt(KEY_FILE_COUNT, res.fileCount)
                    .build()
            )
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.localizedMessage ?: "Upload background error")))
        } finally {
            if (wakeLock.isHeld) {
                try {
                    wakeLock.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun createForegroundInfo(statusText: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KruzenLab Background Upload",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("kruzenlab-uploader ⚡")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
