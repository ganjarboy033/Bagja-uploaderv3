package com.example.xiyu.data.api

import com.example.xiyu.data.database.AppDatabaseHelper
import com.example.xiyu.data.model.*
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface GitHubApiService {

    @GET("user")
    suspend fun getCurrentUser(
        @Header("Authorization") auth: String
    ): UserProfileResponse

    @GET("rate_limit")
    suspend fun getRateLimit(
        @Header("Authorization") auth: String
    ): RateLimitResponse

    @GET("user/repos")
    suspend fun getRepositories(
        @Header("Authorization") auth: String,
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 100,
        @Query("affiliation") affiliation: String = "owner,collaborator"
    ): List<GitHubRepoResponse>

    @POST("user/repos")
    suspend fun createRepository(
        @Header("Authorization") auth: String,
        @Body request: CreateRepoRequest
    ): GitHubRepoResponse

    @DELETE("repos/{owner}/{repo}")
    suspend fun deleteRepository(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Unit>

    // Repository Contents CRUD (unrestricted)
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContents(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Query("ref") ref: String? = null
    ): List<RepoContentItem>

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getSingleFileContent(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Query("ref") ref: String? = null
    ): RepoContentItem

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun createOrUpdateFile(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Body request: ContentUpdateRequest
    ): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "repos/{owner}/{repo}/contents/{path}", hasBody = true)
    suspend fun deleteFile(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Body request: ContentDeleteRequest
    ): Response<ResponseBody>

    // Git Data API
    @GET("repos/{owner}/{repo}/git/ref/heads/{branch}")
    suspend fun getBranchRef(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String
    ): Response<BranchRefResponse>

    @GET("repos/{owner}/{repo}/git/commits/{sha}")
    suspend fun getCommit(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("sha") sha: String
    ): CommitDetailResponse

    @GET("repos/{owner}/{repo}/git/trees/{tree_sha}")
    suspend fun getGitTree(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("tree_sha") treeSha: String,
        @Query("recursive") recursive: Int = 1
    ): GitTreeResponse

    @POST("repos/{owner}/{repo}/git/blobs")
    suspend fun createBlob(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: BlobRequest
    ): BlobResponse

    @POST("repos/{owner}/{repo}/git/trees")
    suspend fun createTree(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: TreeRequest
    ): TreeResponse

    @POST("repos/{owner}/{repo}/git/commits")
    suspend fun createCommit(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: CommitRequest
    ): CommitResponse

    @PATCH("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun updateRef(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
        @Body request: RefRequest
    ): Response<Any>

    @POST("repos/{owner}/{repo}/git/refs")
    suspend fun createRef(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: RefRequest
    ): Response<Any>

    // Branches
    @GET("repos/{owner}/{repo}/branches")
    suspend fun getBranches(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100
    ): List<GitHubBranch>

    // Releases
    @GET("repos/{owner}/{repo}/releases")
    suspend fun getReleases(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): List<GitHubRelease>

    @POST("repos/{owner}/{repo}/releases")
    suspend fun createRelease(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: CreateReleaseRequest
    ): GitHubRelease

    // GitHub Actions Workflows, Logs & Artifacts
    @GET("repos/{owner}/{repo}/actions/runs")
    suspend fun getWorkflowRuns(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 30
    ): WorkflowRunsResponse

    @POST("repos/{owner}/{repo}/actions/runs/{run_id}/rerun")
    suspend fun rerunWorkflow(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<Unit>

    @Streaming
    @GET("repos/{owner}/{repo}/actions/runs/{run_id}/logs")
    suspend fun downloadWorkflowLogs(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<ResponseBody>

    @GET("repos/{owner}/{repo}/actions/runs/{run_id}/jobs")
    suspend fun getWorkflowJobs(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long,
        @Query("per_page") perPage: Int = 50
    ): WorkflowJobsResponse

    @Streaming
    @GET("repos/{owner}/{repo}/actions/jobs/{job_id}/logs")
    suspend fun downloadJobLogs(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("job_id") jobId: Long
    ): Response<ResponseBody>

    @GET("repos/{owner}/{repo}/actions/runs/{run_id}/artifacts")
    suspend fun getWorkflowArtifacts(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): WorkflowArtifactsResponse

    @Streaming
    @GET("repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip")
    suspend fun downloadArtifactZip(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("artifact_id") artifactId: Long
    ): Response<ResponseBody>

    companion object {
        const val DEFAULT_BASE_URL = "https://api.github.com/"

        fun buildOkHttpClient(dbHelper: AppDatabaseHelper? = null): OkHttpClient {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            return OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val isGitHubHost = original.url.host.endsWith("github.com", ignoreCase = true) ||
                        original.url.host.endsWith("githubusercontent.com", ignoreCase = true)

                    val requestBuilder = original.newBuilder()
                    if (isGitHubHost) {
                        requestBuilder
                            .header("Accept", original.header("Accept") ?: "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                    }
                    val request = requestBuilder
                        .method(original.method, original.body)
                        .build()

                    val startNs = System.nanoTime()
                    try {
                        val response = chain.proceed(request)
                        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                        dbHelper?.addInMemoryLog(
                            method = request.method,
                            url = request.url.encodedPath,
                            statusCode = response.code,
                            durationMs = durationMs,
                            summary = response.message.ifEmpty { "HTTP ${response.code}" }
                        )
                        response
                    } catch (e: Exception) {
                        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                        dbHelper?.addInMemoryLog(
                            method = request.method,
                            url = request.url.encodedPath,
                            statusCode = 0,
                            durationMs = durationMs,
                            summary = "Failed: ${e.message}"
                        )
                        throw e
                    }
                }
                .addNetworkInterceptor { chain ->
                    val req = chain.request()
                    val host = req.url.host.lowercase()
                    val isGitHubDomain = host == "github.com" ||
                        host.endsWith(".github.com") ||
                        host.endsWith(".githubusercontent.com")
                    val cleanReq = if (!isGitHubDomain) {
                        // Strip Authorization & GitHub headers on external 302 redirects (e.g., Azure Blob Storage)
                        req.newBuilder()
                            .removeHeader("Authorization")
                            .removeHeader("X-GitHub-Api-Version")
                            .build()
                    } else {
                        req
                    }
                    chain.proceed(cleanReq)
                }
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .build()
        }

        fun create(
            baseUrl: String = DEFAULT_BASE_URL,
            dbHelper: AppDatabaseHelper? = null
        ): GitHubApiService {
            val safeBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            return Retrofit.Builder()
                .baseUrl(safeBaseUrl)
                .client(buildOkHttpClient(dbHelper))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GitHubApiService::class.java)
        }
    }
}
