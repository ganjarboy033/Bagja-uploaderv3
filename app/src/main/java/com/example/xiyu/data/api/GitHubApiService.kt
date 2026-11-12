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
        @Query("affiliation") affiliation: String = "owner"
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

    // Repository Contents CRUD
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
        @Path("repo") repo: String
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

    // GitHub Actions Workflows
    @GET("repos/{owner}/{repo}/actions/runs")
    suspend fun getWorkflowRuns(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): WorkflowRunsResponse

    @POST("repos/{owner}/{repo}/actions/runs/{run_id}/rerun")
    suspend fun rerunWorkflow(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<Unit>

    companion object {
        const val DEFAULT_BASE_URL = "https://api.github.com/"

        fun create(
            baseUrl: String = DEFAULT_BASE_URL,
            dbHelper: AppDatabaseHelper? = null
        ): GitHubApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val clientBuilder = OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val request = original.newBuilder()
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
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
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)

            val safeBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            return Retrofit.Builder()
                .baseUrl(safeBaseUrl)
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GitHubApiService::class.java)
        }
    }
}
