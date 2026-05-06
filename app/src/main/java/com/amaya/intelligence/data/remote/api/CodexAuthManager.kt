package com.amaya.intelligence.data.remote.api

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Manages Codex (OpenAI/ChatGPT subscription) authentication for the Android app.
 *
 * Supports the browser-based PKCE flow.
 *
 * Tokens are stored encrypted via [AiSettingsManager.encryptedPrefs] alongside
 * the existing agent key infrastructure.
 */
@Singleton
class CodexAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val settingsManager: AiSettingsManager
) {
    companion object {
        private const val TAG = "AmayaAuthManager"

        // OpenAI public Codex client — used by the official CLI (Apache-2.0 licensed)
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val AUTH_URL = "https://auth.openai.com/oauth/authorize"
        private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        private const val SCOPE = "openid profile email offline_access"

        // Local callback server ports (same as Codex CLI)
        private const val CALLBACK_HOST = "localhost"
        private val CALLBACK_PORTS = intOf(1455, 1457, 1459)

        // Encrypted prefs keys
        private const val KEY_ACCESS_TOKEN = "codex_access_token"
        private const val KEY_REFRESH_TOKEN = "codex_refresh_token"
        private const val KEY_ID_TOKEN = "codex_id_token"
        private const val KEY_EXPIRES_AT = "codex_expires_at"
        private const val KEY_ACCOUNT_EMAIL = "codex_account_email"

        private fun intOf(vararg values: Int) = values.toList()
    }

    // ── State ────────────────────────────────────────────────────────

    private val _authState = MutableStateFlow<CodexAuthState>(CodexAuthState.Idle)
    val authState: StateFlow<CodexAuthState> = _authState.asStateFlow()

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var callbackServer: ServerSocket? = null
    private var loginJob: Job? = null

    // ── Local Server PKCE Flow ──────────────────────────────────────

    fun startLocalServerLogin(activityContext: Context) {
        _authState.value = CodexAuthState.Starting

        loginJob?.cancel()
        loginJob = authScope.launch {
            val verifier = generateCodeVerifier()
            val challenge = generateCodeChallenge(verifier)
            val state = generateSecureRandom(32)

            // Try to bind a local server
            val (server, port) = tryBindServer() ?: run {
                _authState.value = CodexAuthState.Error("Could not bind localhost server. Please try again.")
                return@launch
            }
            callbackServer = server
            val redirectUri = "http://$CALLBACK_HOST:$port/auth/callback"

            // Build auth URL
            val authUri = Uri.parse(AUTH_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .appendQueryParameter("id_token_add_organizations", "true")
                .appendQueryParameter("codex_cli_simplified_flow", "true")
                .appendQueryParameter("originator", "amaya")
                .appendQueryParameter("prompt", "consent")
                .build()

            // Open Custom Tabs
            withContext(Dispatchers.Main) {
                _authState.value = CodexAuthState.WaitingForBrowser
                try {
                    CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                        .launchUrl(activityContext, authUri)
                } catch (e: Exception) {
                    _authState.value = CodexAuthState.Error("Could not open browser: ${e.message}")
                    server.close()
                    return@withContext
                }
            }

            // Wait for callback (blocking on IO thread)
            try {
                server.soTimeout = 15_000
                val deadlineMs = System.currentTimeMillis() + 300_000L
                while (isActive && System.currentTimeMillis() < deadlineMs) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    val acceptedSocket = socket
                    try {
                        val reader = BufferedReader(InputStreamReader(acceptedSocket.getInputStream()))
                        val requestLine = reader.readLine().orEmpty()
                        val requestPath = parseRequestPath(requestLine)

                        if (requestPath != "/auth/callback") {
                            writeHtmlResponse(acceptedSocket, codexCallbackErrorHtml("Invalid callback path."), status = "404 Not Found")
                        } else {
                            val params = parseCallbackParams(requestLine)
                            val code = params["code"]
                            val returnedState = params["state"]
                            val error = params["error"]

                            if (!error.isNullOrBlank()) {
                                writeHtmlResponse(acceptedSocket, codexCallbackErrorHtml("Authorization returned: $error"))
                                server.close()
                                callbackServer = null
                                _authState.value = CodexAuthState.Error("Authorization failed: $error")
                                return@launch
                            }

                            if (code == null || returnedState != state) {
                                writeHtmlResponse(acceptedSocket, codexCallbackErrorHtml("The callback was missing a valid authorization code."))
                                server.close()
                                callbackServer = null
                                _authState.value = CodexAuthState.Error("Invalid callback: state mismatch or missing code.")
                                return@launch
                            }

                            // The browser cannot see token-exchange state, so show the controlled success page once
                            // a valid authorization callback reaches Amaya.
                            writeHtmlResponse(acceptedSocket, codexCallbackSuccessHtml())
                            server.close()
                            callbackServer = null

                            // Exchange code for token
                            _authState.value = CodexAuthState.ExchangingToken
                            exchangeCodeForToken(code, verifier, redirectUri)
                            return@launch
                        }
                    } finally {
                        runCatching { acceptedSocket.close() }
                    }
                }

                if (isActive) {
                    _authState.value = CodexAuthState.Error("Callback timeout. Please try again.")
                }
                server.close()
                callbackServer = null
            } catch (e: Exception) {
                if (!isActive) return@launch
                Log.e(TAG, "Local server error", e)
                _authState.value = CodexAuthState.Error("Callback timeout or error: ${e.message}")
                server.close()
                callbackServer = null
            }
        }
    }

    // ── Token Exchange (PKCE) ───────────────────────────────────────

    private suspend fun exchangeCodeForToken(code: String, verifier: String, redirectUri: String) {
        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", verifier)
                .build()
            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty token response")

            if (!response.isSuccessful) {
                _authState.value = CodexAuthState.Error("Token exchange failed: ${response.code}")
                return
            }

            val json = JSONObject(body)
            saveTokens(json)
            _authState.value = CodexAuthState.Authenticated(
                email = json.optString("id_token").let { parseEmailFromIdToken(it) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error", e)
            _authState.value = CodexAuthState.Error("Token exchange failed: ${e.message}")
        }
    }

    // ── Token Refresh ───────────────────────────────────────────────

    suspend fun refreshTokenIfNeeded(): String? = withContext(Dispatchers.IO) {
        val expiresAt = getStoredLong(KEY_EXPIRES_AT)
        val accessToken = getStoredString(KEY_ACCESS_TOKEN)
        val refreshToken = getStoredString(KEY_REFRESH_TOKEN)

        if (accessToken.isNullOrBlank()) return@withContext null

        // Refresh if expiring within 5 minutes
        if (expiresAt > 0 && System.currentTimeMillis() < expiresAt - 300_000L) {
            return@withContext accessToken
        }

        if (refreshToken.isNullOrBlank()) return@withContext accessToken

        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", CLIENT_ID)
                .build()
            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext accessToken

            if (response.isSuccessful) {
                val json = JSONObject(body)
                saveTokens(json)
                return@withContext json.getString("access_token")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh failed", e)
        }
        accessToken
    }

    // ── Session Management ──────────────────────────────────────────

    fun isAuthenticated(): Boolean = !getStoredString(KEY_ACCESS_TOKEN).isNullOrBlank()

    fun getAccessToken(): String? = getStoredString(KEY_ACCESS_TOKEN)

    fun getChatGptAccountId(): String? = extractChatGptAccountId(getStoredString(KEY_ACCESS_TOKEN))

    fun getAccountEmail(): String? = getStoredString(KEY_ACCOUNT_EMAIL)

    fun syncAuthStateFromStorage(): Boolean {
        val authenticated = isAuthenticated()
        if (authenticated) {
            _authState.value = CodexAuthState.Authenticated(getAccountEmail())
        }
        return authenticated
    }

    fun logout() {
        clearStoredTokens()
        _authState.value = CodexAuthState.Idle
    }

    fun cancel() {
        loginJob?.cancel()
        loginJob = null
        callbackServer?.close()
        callbackServer = null
        _authState.value = CodexAuthState.Idle
    }

    // ── Internal Helpers ────────────────────────────────────────────

    private fun tryBindServer(): Pair<ServerSocket, Int>? {
        for (port in CALLBACK_PORTS) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                return server to port
            } catch (_: Exception) { /* port busy, try next */ }
        }
        return null
    }

    private fun parseRequestPath(requestLine: String): String? {
        // "GET /auth/callback?code=abc&state=xyz HTTP/1.1"
        return requestLine.split(" ").getOrNull(1)?.substringBefore("?")
    }

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
        socket.close()
    }

    private fun codexCallbackSuccessHtml(): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>Sign In Successful</title>
            <style>
                * { box-sizing: border-box; }
                html, body {
                    width: 100%;
                    height: 100%;
                    margin: 0;
                    overflow: hidden;
                    background: #ffffff;
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                }
                body {
                    min-height: 100dvh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    padding: 16px;
                }
                .wrap {
                    width: min(100%, 360px);
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    text-align: center;
                }
                .icon {
                    width: 112px;
                    height: 112px;
                    border-radius: 9999px;
                    background: #f3f4f6;
                    color: #111827;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    margin-bottom: 32px;
                    flex: none;
                }
                .icon svg { width: 48px; height: 48px; }
                h1 {
                    margin: 0 0 20px;
                    color: #111827;
                    font-size: clamp(26px, 7vw, 30px);
                    font-weight: 400;
                    line-height: 1.15;
                    letter-spacing: -0.02em;
                }
                .body {
                    color: #4b5563;
                    font-size: 16px;
                    line-height: 1.55;
                    margin-bottom: 32px;
                }
                .body p { margin: 0; }
                .body p + p { margin-top: 6px; }
                .button {
                    appearance: none;
                    border: 0;
                    border-radius: 9999px;
                    background: #111827;
                    color: #fff;
                    padding: 14px 32px;
                    font-size: 14px;
                    font-weight: 500;
                    cursor: pointer;
                    box-shadow: 0 1px 2px rgba(0,0,0,.08);
                }
            </style>
        </head>
        <body>
            <main class="wrap">
                <div class="icon" aria-hidden="true">
                    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"></path>
                    </svg>
                </div>
                <h1>Sign In Successful</h1>
                <div class="body">
                    <p>Authorization received.</p>
                    <p>You can close this tab and return to the app.</p>
                </div>
                <button class="button" onclick="window.close()">Close tab</button>
            </main>
        </body>
        </html>
    """.trimIndent()

    private fun codexCallbackErrorHtml(message: String): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>Sign In Failed</title>
            <style>
                * { box-sizing: border-box; }
                html, body {
                    width: 100%;
                    height: 100%;
                    margin: 0;
                    overflow: hidden;
                    background: #ffffff;
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                }
                body {
                    min-height: 100dvh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    padding: 16px;
                }
                .wrap {
                    width: min(100%, 360px);
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    text-align: center;
                }
                .icon {
                    width: 112px;
                    height: 112px;
                    border-radius: 9999px;
                    background: #f3f4f6;
                    color: #111827;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    margin-bottom: 32px;
                    font-size: 48px;
                    font-weight: 400;
                    flex: none;
                }
                h1 {
                    margin: 0 0 20px;
                    color: #111827;
                    font-size: clamp(26px, 7vw, 30px);
                    font-weight: 400;
                    line-height: 1.15;
                    letter-spacing: -0.02em;
                }
                .body {
                    color: #4b5563;
                    font-size: 16px;
                    line-height: 1.55;
                    margin-bottom: 32px;
                }
                .body p { margin: 0; }
                .body p + p { margin-top: 6px; }
                .button {
                    appearance: none;
                    border: 0;
                    border-radius: 9999px;
                    background: #111827;
                    color: #fff;
                    padding: 14px 32px;
                    font-size: 14px;
                    font-weight: 500;
                    cursor: pointer;
                    box-shadow: 0 1px 2px rgba(0,0,0,.08);
                }
            </style>
        </head>
        <body>
            <main class="wrap">
                <div class="icon" aria-hidden="true">!</div>
                <h1>Sign In Failed</h1>
                <div class="body">
                    <p>${htmlEscape(message)}</p>
                    <p>Please return to the app and try again.</p>
                </div>
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

    private fun saveTokens(json: JSONObject) {
        val accessToken = json.optString("access_token", "")
        val refreshToken = json.optString("refresh_token", "")
        val idToken = json.optString("id_token", "")
        val expiresIn = json.optInt("expires_in", 3600)
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
        val email = parseEmailFromIdToken(idToken)

        storeString(KEY_ACCESS_TOKEN, accessToken)
        storeString(KEY_REFRESH_TOKEN, refreshToken)
        storeString(KEY_ID_TOKEN, idToken)
        storeLong(KEY_EXPIRES_AT, expiresAt)
        if (!email.isNullOrBlank()) storeString(KEY_ACCOUNT_EMAIL, email)
    }

    private fun clearStoredTokens() {
        val prefs = getEncryptedPrefs()
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ID_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_ACCOUNT_EMAIL)
            .apply()
    }

    private fun parseEmailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return try {
            val payload = decodeJwtPayload(idToken) ?: return null
            JSONObject(payload).optString("email", "").ifBlank { null }
        } catch (_: Exception) { null }
    }

    private fun extractChatGptAccountId(accessToken: String?): String? {
        if (accessToken.isNullOrBlank()) return null
        return try {
            val payload = decodeJwtPayload(accessToken) ?: return null
            val json = JSONObject(payload)
            val auth = json.optJSONObject("https://api.openai.com/auth")
            auth?.optString("chatgpt_account_id", "")?.ifBlank { null }
                ?: json.optString("chatgpt_account_id", "").ifBlank { null }
                ?: json.optString("chatgptAccountId", "").ifBlank { null }
        } catch (_: Exception) { null }
    }

    private fun decodeJwtPayload(jwt: String): String? {
        val payload = jwt.split(".").getOrNull(1) ?: return null
        return String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    }

    // ── Encrypted SharedPreferences access ──────────────────────────

    private fun getEncryptedPrefs() = settingsManager.getEncryptedPrefsForCodex()

    private fun storeString(key: String, value: String) {
        getEncryptedPrefs().edit().putString(key, value).apply()
    }

    private fun storeLong(key: String, value: Long) {
        getEncryptedPrefs().edit().putLong(key, value).apply()
    }

    private fun getStoredString(key: String): String? =
        getEncryptedPrefs().getString(key, null)

    private fun getStoredLong(key: String): Long =
        getEncryptedPrefs().getLong(key, 0L)

    // ── PKCE Helpers ────────────────────────────────────────────────

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

/**
 * Represents the current state of the Codex authentication flow.
 */
sealed class CodexAuthState {
    /** No auth in progress. */
    data object Idle : CodexAuthState()

    /** Flow is initializing. */
    data object Starting : CodexAuthState()

    /** Local server started, waiting for browser redirect. */
    data object WaitingForBrowser : CodexAuthState()

    /** Exchanging authorization code for tokens. */
    data object ExchangingToken : CodexAuthState()

    /** Authentication completed successfully. */
    data class Authenticated(val email: String?) : CodexAuthState()

    /** An error occurred. */
    data class Error(val message: String) : CodexAuthState()
}
