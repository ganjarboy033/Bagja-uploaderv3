package com.example.xiyu.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.xiyu.data.database.AppDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

object OAuthHelper {

    private const val PREFS_NAME = "kruzenlab_oauth_prefs"
    private const val KEY_CLIENT_ID = "oauth_client_id"
    private const val KEY_CLIENT_SECRET = "oauth_client_secret"
    private const val KEY_SESSION_PASSWORD = "oauth_session_password"
    private const val KEY_LAST_STATE = "oauth_last_state"

    // Exact Default Fallback values
    const val FALLBACK_CLIENT_ID = "Ov23lizAXWwIXYOXCLKg"
    const val FALLBACK_CLIENT_SECRET = "1b02b712f931cc0eed574a1c98a581f63d5fadb7"
    const val FALLBACK_SESSION_PASSWORD = "bagja_uploader_secret_session_key_32char_min"

    const val REDIRECT_URI = "kruzenlab://oauth-callback"
    const val LEGACY_REDIRECT_URI = "xiyu://oauth-callback"
    const val GITHUB_AUTH_URL = "https://github.com/login/oauth/authorize"
    const val GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token"

    fun getClientId(context: Context): String {
        val custom = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CLIENT_ID, "") ?: ""
        if (custom.isNotBlank()) return custom
        val dbConfig = AppDatabaseHelper.getInstance(context).configState.value.clientId
        return if (dbConfig.isNotBlank()) dbConfig else FALLBACK_CLIENT_ID
    }

    fun getClientSecret(context: Context): String {
        val custom = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CLIENT_SECRET, "") ?: ""
        if (custom.isNotBlank()) return custom
        val dbConfig = AppDatabaseHelper.getInstance(context).configState.value.clientSecret
        return if (dbConfig.isNotBlank()) dbConfig else FALLBACK_CLIENT_SECRET
    }

    fun getSessionPassword(context: Context): String {
        val custom = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SESSION_PASSWORD, "") ?: ""
        if (custom.isNotBlank()) return custom
        val dbConfig = AppDatabaseHelper.getInstance(context).configState.value.sessionPassword
        return if (dbConfig.isNotBlank()) dbConfig else FALLBACK_SESSION_PASSWORD
    }

    fun saveOAuthCredentials(
        context: Context,
        clientId: String,
        clientSecret: String,
        sessionPassword: String = FALLBACK_SESSION_PASSWORD
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLIENT_ID, clientId.trim())
            .putString(KEY_CLIENT_SECRET, clientSecret.trim())
            .putString(KEY_SESSION_PASSWORD, sessionPassword.trim().ifEmpty { FALLBACK_SESSION_PASSWORD })
            .apply()
    }

    fun saveLastState(context: Context, state: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATE, state)
            .apply()
    }

    fun getLastState(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STATE, null)
    }

    fun launchChromeOAuth(context: Context, clientId: String? = null): Boolean {
        val activeClientId = if (!clientId.isNullOrBlank()) clientId else getClientId(context)
        val state = UUID.randomUUID().toString()
        saveLastState(context, state)

        val authUri = Uri.parse(GITHUB_AUTH_URL)
            .buildUpon()
            .appendQueryParameter("client_id", activeClientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "repo,read:user,user:email,workflow,delete_repo")
            .appendQueryParameter("state", state)
            .build()

        return openInChromeOrBrowser(context, authUri)
    }

    fun openChromeForTokenCreation(context: Context): Boolean {
        val tokenPageUri = Uri.parse(
            "https://github.com/settings/tokens/new?description=kruzenlab-uploader&scopes=repo,read:user,user:email,workflow,delete_repo"
        )
        return openInChromeOrBrowser(context, tokenPageUri)
    }

    private fun openInChromeOrBrowser(context: Context, uri: Uri): Boolean {
        val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chromeIntent)
            return true
        } catch (_: ActivityNotFoundException) {
            val generalIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(generalIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun exchangeCodeForToken(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String = REDIRECT_URI
    ): Result<String> = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val formBody = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("client_secret", clientSecret.trim())
            .add("code", code.trim())
            .add("redirect_uri", redirectUri)
            .build()

        val request = Request.Builder()
            .url(GITHUB_TOKEN_URL)
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("GitHub rejected token exchange (HTTP ${response.code}): $raw")
                )
            }

            val json = JSONObject(raw)
            if (json.has("error")) {
                val errorMsg = json.optString("error_description", json.getString("error"))
                return@withContext Result.failure(Exception("GitHub OAuth Error: $errorMsg"))
            }

            val accessToken = json.optString("access_token", "")
            if (accessToken.isNotEmpty()) {
                Result.success(accessToken)
            } else {
                Result.failure(Exception("No access_token returned by GitHub: $raw"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
