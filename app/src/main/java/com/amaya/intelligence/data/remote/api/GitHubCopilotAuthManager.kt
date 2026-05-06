package com.amaya.intelligence.data.remote.api

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.amaya.intelligence.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages GitHub Copilot subscription authentication for the Android app.
 *
 * Browser sign-in uses OAuth + PKCE against GitHub and a localhost callback.
 * Tokens are stored in encrypted prefs.
 */
@Singleton
class GitHubCopilotAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val settingsManager: AiSettingsManager
) {
    companion object {
        private const val TAG = "GitHubCopilotAuthManager"

        private const val AUTH_URL = "https://github.com/login/oauth/authorize"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val USER_URL = "https://api.github.com/user"
        private const val SCOPE = "read:user user:email"

        private const val CALLBACK_HOST = "localhost"
        private val CALLBACK_PORTS = intOf(1455, 1457, 1459)

        private const val KEY_ACCESS_TOKEN = "github_copilot_access_token"
        private const val KEY_REFRESH_TOKEN = "github_copilot_refresh_token"
        private const val KEY_ACCOUNT_LOGIN = "github_copilot_account_login"
        private const val KEY_ACCOUNT_NAME = "github_copilot_account_name"
        private const val KEY_EXPIRES_AT = "github_copilot_expires_at"

        private fun intOf(vararg values: Int) = values.toList()
    }

    private val _authState = MutableStateFlow<GitHubCopilotAuthState>(GitHubCopilotAuthState.Idle)
    val authState: StateFlow<GitHubCopilotAuthState> = _authState.asStateFlow()

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var callbackServer: ServerSocket? = null
    private var loginJob: Job? = null

    fun startBrowserLogin(activityContext: Context) {
        _authState.value = GitHubCopilotAuthState.Starting

        loginJob?.cancel()
        loginJob = authScope.launch {
            val verifier = generateCodeVerifier()
            val challenge = generateCodeChallenge(verifier)
            val state = generateSecureRandom(32)

            val (server, port) = tryBindServer() ?: run {
                _authState.value = GitHubCopilotAuthState.Error("Could not bind localhost server. Please try again.")
                return@launch
            }
            callbackServer = server
            val redirectUri = "http://$CALLBACK_HOST:$port/auth/callback"

            val authUri = Uri.parse(AUTH_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", BuildConfig.GITHUB_COPILOT_CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .appendQueryParameter("allow_signup", "true")
                .build()

            withContext(Dispatchers.Main) {
                _authState.value = GitHubCopilotAuthState.WaitingForBrowser
                try {
                    CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                        .launchUrl(activityContext, authUri)
                } catch (e: Exception) {
                    _authState.value = GitHubCopilotAuthState.Error("Could not open browser: ${e.message}")
                    server.close()
                    return@withContext
                }
            }

            try {
                server.soTimeout = 15_000
                val deadlineMs = System.currentTimeMillis() + 300_000L
                while (isActive && System.currentTimeMillis() < deadlineMs) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    try {
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine().orEmpty()
                        val requestPath = parseRequestPath(requestLine)
                        if (requestPath != "/auth/callback") {
                            writeHtmlResponse(socket, callbackErrorHtml("Invalid callback path."), status = "404 Not Found")
                            continue
                        }

                        val params = parseCallbackParams(requestLine)
                        val code = params["code"]
                        val returnedState = params["state"]
                        val error = params["error"]

                        if (!error.isNullOrBlank()) {
                            writeHtmlResponse(socket, callbackErrorHtml("Authorization returned: $error"))
                            server.close()
                            callbackServer = null
                            _authState.value = GitHubCopilotAuthState.Error("Authorization failed: $error")
                            return@launch
                        }

                        if (code == null || returnedState != state) {
                            writeHtmlResponse(socket, callbackErrorHtml("The callback was missing a valid authorization code."))
                            server.close()
                            callbackServer = null
                            _authState.value = GitHubCopilotAuthState.Error("Invalid callback: state mismatch or missing code.")
                            return@launch
                        }

                        writeHtmlResponse(socket, callbackSuccessHtml())
                        server.close()
                        callbackServer = null

                        _authState.value = GitHubCopilotAuthState.ExchangingToken
                        exchangeCodeForToken(code, verifier, redirectUri)
                        return@launch
                    } finally {
                        runCatching { socket.close() }
                    }
                }

                if (isActive) {
                    _authState.value = GitHubCopilotAuthState.Error("Callback timeout. Please try again.")
                }
                server.close()
                callbackServer = null
            } catch (e: Exception) {
                if (!isActive) return@launch
                Log.e(TAG, "GitHub Copilot auth error", e)
                _authState.value = GitHubCopilotAuthState.Error("GitHub auth failed: ${e.message}")
                server.close()
                callbackServer = null
            }
        }
    }

    suspend fun refreshTokenIfNeeded(): String? = withContext(Dispatchers.IO) {
        val expiresAt = getStoredLong(KEY_EXPIRES_AT)
        val accessToken = getStoredString(KEY_ACCESS_TOKEN)
        val refreshToken = getStoredString(KEY_REFRESH_TOKEN)

        if (accessToken.isNullOrBlank()) return@withContext null
        if (expiresAt > 0 && System.currentTimeMillis() < expiresAt - 300_000L) return@withContext accessToken
        if (refreshToken.isNullOrBlank()) return@withContext accessToken

        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", BuildConfig.GITHUB_COPILOT_CLIENT_ID)
                .build()
            val request = Request.Builder()
                .url(TOKEN_URL)
                .header("Accept", "application/json")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful && body.isNotBlank()) {
                val json = JSONObject(body)
                val profile = loadGitHubProfile(json.optString("access_token"))
                saveTokens(json, profile)
                return@withContext json.optString("access_token").ifBlank { accessToken }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub Copilot token refresh failed", e)
        }
        accessToken
    }

    fun isAuthenticated(): Boolean = !getStoredString(KEY_ACCESS_TOKEN).isNullOrBlank()

    fun getAccessToken(): String? = getStoredString(KEY_ACCESS_TOKEN)

    fun getAccountLogin(): String? = getStoredString(KEY_ACCOUNT_LOGIN)

    fun getAccountLabel(): String? = getStoredString(KEY_ACCOUNT_NAME)
        ?.takeIf { it.isNotBlank() }
        ?: getStoredString(KEY_ACCOUNT_LOGIN)

    fun syncAuthStateFromStorage(): Boolean {
        val authenticated = isAuthenticated()
        if (authenticated) {
            _authState.value = GitHubCopilotAuthState.Authenticated(
                login = getAccountLogin(),
                displayName = getAccountLabel()
            )
        }
        return authenticated
    }

    fun logout() {
        clearStoredTokens()
        _authState.value = GitHubCopilotAuthState.Idle
    }

    fun cancel() {
        loginJob?.cancel()
        loginJob = null
        callbackServer?.close()
        callbackServer = null
        _authState.value = GitHubCopilotAuthState.Idle
    }

    private suspend fun exchangeCodeForToken(code: String, verifier: String, redirectUri: String) {
        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", BuildConfig.GITHUB_COPILOT_CLIENT_ID)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", verifier)
                .build()
            val request = Request.Builder()
                .url(TOKEN_URL)
                .header("Accept", "application/json")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                _authState.value = GitHubCopilotAuthState.Error("Token exchange failed: ${response.code}")
                return
            }

            val json = JSONObject(body)
            val profile = loadGitHubProfile(json.optString("access_token"))
            saveTokens(json, profile)
            _authState.value = GitHubCopilotAuthState.Authenticated(
                login = profile?.login,
                displayName = profile?.displayName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error", e)
            _authState.value = GitHubCopilotAuthState.Error("Token exchange failed: ${e.message}")
        }
    }

    private suspend fun loadGitHubProfile(accessToken: String): GitHubProfile? = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(USER_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer $accessToken")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return@withContext null
            val json = JSONObject(body)
            val login = json.optString("login").takeIf { it.isNotBlank() }
            val displayName = json.optString("name").takeIf { it.isNotBlank() } ?: login
            GitHubProfile(login = login, displayName = displayName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load GitHub profile", e)
            null
        }
    }

    private fun saveTokens(tokenJson: JSONObject, profile: GitHubProfile?) {
        val accessToken = tokenJson.optString("access_token", "")
        val refreshToken = tokenJson.optString("refresh_token", "")
        val expiresIn = tokenJson.optInt("expires_in", 3600)
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
        val prefs = getEncryptedPrefs()

        prefs.edit().putString(KEY_ACCESS_TOKEN, accessToken).apply()
        if (refreshToken.isNotBlank()) prefs.edit().putString(KEY_REFRESH_TOKEN, refreshToken).apply() else prefs.edit().remove(KEY_REFRESH_TOKEN).apply()
        prefs.edit().putLong(KEY_EXPIRES_AT, expiresAt).apply()

        if (profile?.login.isNullOrBlank()) prefs.edit().remove(KEY_ACCOUNT_LOGIN).apply() else prefs.edit().putString(KEY_ACCOUNT_LOGIN, profile?.login).apply()
        if (profile?.displayName.isNullOrBlank()) prefs.edit().remove(KEY_ACCOUNT_NAME).apply() else prefs.edit().putString(KEY_ACCOUNT_NAME, profile?.displayName).apply()
    }

    private fun clearStoredTokens() {
        getEncryptedPrefs().edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCOUNT_LOGIN)
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    private fun getEncryptedPrefs() = settingsManager.getEncryptedPrefsForProviderAuth("github_copilot")

    private fun getStoredString(key: String): String? = getEncryptedPrefs().getString(key, null)

    private fun getStoredLong(key: String): Long = getEncryptedPrefs().getLong(key, 0L)

    private fun tryBindServer(): Pair<ServerSocket, Int>? {
        for (port in CALLBACK_PORTS) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                return server to port
            } catch (_: Exception) { }
        }
        return null
    }

    private fun parseRequestPath(requestLine: String): String? =
        requestLine.split(" ").getOrNull(1)?.substringBefore("?")

    private fun parseCallbackParams(requestLine: String): Map<String, String> {
        val pathAndQuery = requestLine.split(" ").getOrNull(1) ?: return emptyMap()
        val queryStr = pathAndQuery.substringAfter("?", "")
        return queryStr.split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
        }.toMap()
    }

    private fun writeHtmlResponse(socket: Socket, html: String, status: String = "200 OK") {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val writer = PrintWriter(socket.getOutputStream(), true)
        writer.print("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        writer.flush()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun callbackSuccessHtml(): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>Sign In Successful</title>
            <style>
                * { box-sizing: border-box; }
                html, body {
                    width: 100%; height: 100%; margin: 0; overflow: hidden; background: #ffffff;
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                }
                body { min-height: 100dvh; display: flex; align-items: center; justify-content: center; padding: 16px; }
                .wrap { width: min(100%, 360px); display: flex; flex-direction: column; align-items: center; text-align: center; }
                .icon { width: 112px; height: 112px; border-radius: 9999px; background: #f3f4f6; color: #111827; display: flex; align-items: center; justify-content: center; margin-bottom: 32px; flex: none; }
                .icon svg { width: 48px; height: 48px; }
                h1 { margin: 0 0 20px; color: #111827; font-size: clamp(26px, 7vw, 30px); font-weight: 400; line-height: 1.15; letter-spacing: -0.02em; }
                .body { color: #4b5563; font-size: 16px; line-height: 1.55; margin-bottom: 32px; }
                .body p { margin: 0; } .body p + p { margin-top: 6px; }
                .button { appearance: none; border: 0; border-radius: 9999px; background: #111827; color: #fff; padding: 14px 32px; font-size: 14px; font-weight: 500; cursor: pointer; box-shadow: 0 1px 2px rgba(0,0,0,.08); }
            </style>
        </head>
        <body>
            <main class="wrap">
                <div class="icon" aria-hidden="true">
                    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"></path></svg>
                </div>
                <h1>Sign In Successful</h1>
                <div class="body"><p>Authorization received.</p><p>You can close this tab and return to the app.</p></div>
                <button class="button" onclick="window.close()">Close tab</button>
            </main>
        </body>
        </html>
    """.trimIndent()

    private fun callbackErrorHtml(message: String): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>Sign In Failed</title>
            <style>
                * { box-sizing: border-box; }
                html, body { width: 100%; height: 100%; margin: 0; overflow: hidden; background: #ffffff; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
                body { min-height: 100dvh; display: flex; align-items: center; justify-content: center; padding: 16px; }
                .wrap { width: min(100%, 360px); display: flex; flex-direction: column; align-items: center; text-align: center; }
                .icon { width: 112px; height: 112px; border-radius: 9999px; background: #f3f4f6; color: #111827; display: flex; align-items: center; justify-content: center; margin-bottom: 32px; font-size: 48px; flex: none; }
                h1 { margin: 0 0 20px; color: #111827; font-size: clamp(26px, 7vw, 30px); font-weight: 400; line-height: 1.15; letter-spacing: -0.02em; }
                .body { color: #4b5563; font-size: 16px; line-height: 1.55; margin-bottom: 32px; }
                .body p { margin: 0; } .body p + p { margin-top: 6px; }
                .button { appearance: none; border: 0; border-radius: 9999px; background: #111827; color: #fff; padding: 14px 32px; font-size: 14px; font-weight: 500; cursor: pointer; box-shadow: 0 1px 2px rgba(0,0,0,.08); }
            </style>
        </head>
        <body>
            <main class="wrap">
                <div class="icon" aria-hidden="true">!</div>
                <h1>Sign In Failed</h1>
                <div class="body"><p>${htmlEscape(message)}</p><p>Please return to the app and try again.</p></div>
                <button class="button" onclick="window.close()">Close tab</button>
            </main>
        </body>
        </html>
    """.trimIndent()

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateSecureRandom(length: Int): String {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

data class GitHubProfile(
    val login: String?,
    val displayName: String?
)

sealed class GitHubCopilotAuthState {
    data object Idle : GitHubCopilotAuthState()
    data object Starting : GitHubCopilotAuthState()
    data object WaitingForBrowser : GitHubCopilotAuthState()
    data object ExchangingToken : GitHubCopilotAuthState()
    data class Authenticated(
        val login: String?,
        val displayName: String?
    ) : GitHubCopilotAuthState()
    data class Error(val message: String) : GitHubCopilotAuthState()
}
