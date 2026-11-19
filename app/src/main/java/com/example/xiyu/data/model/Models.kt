package com.example.xiyu.data.model

import com.google.gson.annotations.SerializedName

enum class UploadArchiveMode {
    ATOMIC_EXTRACT,
    RAW_ZIP
}

data class UserProfile(
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val htmlUrl: String? = null,
    val bio: String? = null
)

data class GitHubRepo(
    val id: Long,
    val name: String,
    val fullName: String,
    val isPrivate: Boolean,
    val defaultBranch: String = "main",
    val htmlUrl: String = "",
    val description: String? = null
)

data class FileEntry(
    val path: String,
    val contentBase64: String = "",
    val size: Long,
    var isSelected: Boolean = true,
    val rawRelativePath: String = path,
    val localStagingPath: String? = null
)

data class CommitResult(
    val success: Boolean,
    val commitSha: String? = null,
    val htmlUrl: String? = null,
    val repoUrl: String? = null,
    val fullName: String? = null,
    val fileCount: Int = 0,
    val error: String? = null
)

data class AccountEntity(
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val token: String,
    val isOAuth: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

data class RateLimitInfo(
    val limit: Int = 5000,
    val remaining: Int = 5000,
    val resetTimeEpochSeconds: Long = 0L,
    val used: Int = 0
)

data class RepoContentItem(
    val name: String,
    val path: String,
    val sha: String,
    val size: Long,
    val type: String, // "file" or "dir"
    @SerializedName("download_url") val downloadUrl: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null,
    val content: String? = null,
    val encoding: String? = null
)

data class ContentUpdateRequest(
    val message: String,
    val content: String, // base64
    val sha: String? = null,
    val branch: String? = null
)

data class ContentDeleteRequest(
    val message: String,
    val sha: String,
    val branch: String? = null
)

data class GitHubRelease(
    val id: Long,
    @SerializedName("tag_name") val tagName: String,
    val name: String?,
    val body: String?,
    val prerelease: Boolean,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("upload_url") val uploadUrl: String? = null
)

data class CreateReleaseRequest(
    @SerializedName("tag_name") val tagName: String,
    val name: String,
    val body: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false
)

data class ReleaseAssetAttachment(
    val displayName: String,
    val localFilePath: String? = null, // When sourced from internal Downloads
    val contentUriString: String? = null, // When sourced from device SAF picker
    val sizeBytes: Long = 0L
)

data class WorkflowRunsResponse(
    @SerializedName("total_count") val totalCount: Int,
    @SerializedName("workflow_runs") val workflowRuns: List<WorkflowRun>
)

data class WorkflowRun(
    val id: Long,
    val name: String?,
    @SerializedName("head_branch") val headBranch: String?,
    val status: String?, // completed, in_progress, queued
    val conclusion: String?, // success, failure, cancelled
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updatedAt: String? = null
)

data class WorkflowJobsResponse(
    @SerializedName("total_count") val totalCount: Int,
    val jobs: List<WorkflowJob>
)

data class WorkflowJob(
    val id: Long,
    @SerializedName("run_id") val runId: Long = 0L,
    val name: String,
    val status: String?,
    val conclusion: String?,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("completed_at") val completedAt: String? = null,
    val steps: List<WorkflowStep> = emptyList()
)

data class WorkflowStep(
    val name: String,
    val status: String?,
    val conclusion: String?,
    val number: Int,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("completed_at") val completedAt: String? = null
)

data class WorkflowArtifactsResponse(
    @SerializedName("total_count") val totalCount: Int,
    val artifacts: List<WorkflowArtifact>
)

data class WorkflowArtifact(
    val id: Long,
    val name: String,
    @SerializedName("size_in_bytes") val sizeInBytes: Long,
    val expired: Boolean = false,
    @SerializedName("archive_download_url") val archiveDownloadUrl: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

data class ExtractedArtifactFile(
    val name: String,
    val relativePath: String,
    val localCachePath: String,
    val sizeBytes: Long,
    val isApk: Boolean,
    val isTextLike: Boolean
)

data class GitHubBranch(
    val name: String,
    val commit: BranchCommit
)

data class BranchCommit(
    val sha: String,
    val url: String?
)

// Full Recursive Tree DTOs (for atomic directory deletion)
data class GitTreeResponse(
    val sha: String,
    val tree: List<GitTreeNode>,
    val truncated: Boolean = false
)

data class GitTreeNode(
    val path: String,
    val mode: String,
    val type: String, // "blob" or "tree"
    val sha: String,
    val size: Long? = null
)

// Internal Downloads & Background Upload Queue Models
data class DownloadRecord(
    val id: Long = 0,
    val repoFullName: String,
    val fileName: String,
    val fileType: String, // "apk", "zip", "log", "asset"
    val localPath: String,
    val sizeBytes: Long,
    val sourceRunId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class UploadQueueItem(
    val id: Long = 0,
    val batchId: String,
    val owner: String,
    val repo: String,
    val branch: String,
    val commitMessage: String,
    val uploadMode: String, // "ATOMIC_EXTRACT" or "RAW_ZIP"
    val filePath: String,
    val localStagingPath: String,
    val sizeBytes: Long,
    val blobSha: String? = null,
    val status: String = "PENDING", // "PENDING", "UPLOADED", "FAILED", "DONE"
    val updatedAt: Long = System.currentTimeMillis()
)

// API Request/Response DTOs
data class UserProfileResponse(
    val login: String,
    val name: String?,
    @SerializedName("avatar_url") val avatarUrl: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    val bio: String?
)

data class GitHubRepoResponse(
    val id: Long,
    val name: String,
    @SerializedName("full_name") val fullName: String,
    val private: Boolean,
    @SerializedName("default_branch") val defaultBranch: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    val description: String?
)

data class CreateRepoRequest(
    val name: String,
    val description: String? = null,
    val private: Boolean = true,
    @SerializedName("auto_init") val autoInit: Boolean = true
)

data class BranchRefResponse(
    val ref: String,
    @SerializedName("node_id") val nodeId: String?,
    val url: String?,
    val `object`: RefObject
)

data class RefObject(
    val sha: String,
    val type: String?,
    val url: String?
)

data class CommitDetailResponse(
    val sha: String,
    val tree: TreeObject
)

data class TreeObject(
    val sha: String,
    val url: String?
)

data class BlobRequest(
    val content: String,
    val encoding: String = "base64"
)

data class BlobResponse(
    val sha: String,
    val url: String?
)

data class TreeItem(
    val path: String,
    val mode: String = "100644",
    val type: String = "blob",
    val sha: String
)

data class TreeRequest(
    val tree: List<TreeItem>,
    @SerializedName("base_tree") val baseTree: String? = null
)

data class TreeResponse(
    val sha: String,
    val url: String?
)

data class CommitRequest(
    val message: String,
    val tree: String,
    val parents: List<String>? = null
)

data class CommitResponse(
    val sha: String,
    @SerializedName("html_url") val htmlUrl: String?
)

data class RefRequest(
    val ref: String? = null,
    val sha: String
)

data class RateLimitResponse(
    val resources: RateLimitResources
)

data class RateLimitResources(
    val core: RateLimitCore
)

data class RateLimitCore(
    val limit: Int,
    val remaining: Int,
    val reset: Long,
    val used: Int
)
