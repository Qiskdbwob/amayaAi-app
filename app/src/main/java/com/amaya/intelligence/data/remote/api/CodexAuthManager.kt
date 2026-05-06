package com.amaya.intelligence.data.remote.api

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Codex (OpenAI/ChatGPT subscription) authentication for the Android app.
 *
 * Supports two flows:
 * 1. **Local Server PKCE** — spins up a lightweight localhost HTTP server, opens
 *    the OpenAI auth page in Custom Tabs, and catches the redirect callback.
 * 2. **Device Code** (RFC 8628) — requests a device code, shows a user code for
 *    the user to enter in a browser, and polls until authorization completes.
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
        private const val TAG = "CodexAuthManager"

        // OpenAI public Codex client — used by the official CLI (Apache-2.0 licensed)
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val AUTH_URL = "https://auth.openai.com/oauth/authorize"
        private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        private const val DEVICE_AUTH_URL = "https://auth.openai.com/oauth/device/authorize"
        private const val SCOPE = "openid profile email offline_access"

        // Local callback server ports (same as Codex CLI)
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
    private var pollingJob: Job? = null

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
                _authState.value = CodexAuthState.Error("Could not bind localhost server. Try Device Code instead.")
                return@launch
            }
            callbackServer = server
            val redirectUri = "http://localhost:$port/auth/callback"

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
                .appendQueryParameter("originator", "pi")
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
                server.soTimeout = 300_000 // 5 minute timeout
                val socket = server.accept()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: ""
                // Parse GET /auth/callback?code=...&state=...
                val params = parseCallbackParams(requestLine)
                val code = params["code"]
                val returnedState = params["state"]
                val error = params["error"]

                if (!error.isNullOrBlank()) {
                    writeHtmlResponse(socket, codexCallbackErrorHtml("OpenAI returned: $error"))
                    server.close()
                    callbackServer = null
                    _authState.value = CodexAuthState.Error("OpenAI authorization failed: $error")
                    return@launch
                }

                if (code == null || returnedState != state) {
                    writeHtmlResponse(socket, codexCallbackErrorHtml("The callback was missing a valid authorization code."))
                    server.close()
                    callbackServer = null
                    _authState.value = CodexAuthState.Error("Invalid callback: state mismatch or missing code.")
                    return@launch
                }

                // The browser cannot see token-exchange state, so show the controlled success page once
                // a valid authorization callback reaches Amaya.
                writeHtmlResponse(socket, codexCallbackSuccessHtml())
                server.close()
                callbackServer = null

                // Exchange code for token
                _authState.value = CodexAuthState.ExchangingToken
                exchangeCodeForToken(code, verifier, redirectUri)

            } catch (e: Exception) {
                if (!isActive) return@launch
                Log.e(TAG, "Local server error", e)
                _authState.value = CodexAuthState.Error("Callback timeout or error: ${e.message}")
                server.close()
                callbackServer = null
            }
        }
    }

    // ── Device Code Flow ────────────────────────────────────────────

    fun startDeviceCodeLogin() {
        _authState.value = CodexAuthState.Starting

        pollingJob?.cancel()
        pollingJob = authScope.launch {
            try {
                // 1. Request device code
                val formBody = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("scope", SCOPE)
                    .build()
                val request = Request.Builder()
                    .url(DEVICE_AUTH_URL)
                    .post(formBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    _authState.value = CodexAuthState.Error("Device code request failed: ${response.code} — $body")
                    return@launch
                }

                val json = JSONObject(body)
                val deviceCode = json.getString("device_code")
                val userCode = json.getString("user_code")
                val verificationUri = json.optString("verification_uri_complete",
                    json.optString("verification_uri", "https://chatgpt.com/device"))
                val interval = json.optInt("interval", 5)
                val expiresIn = json.optInt("expires_in", 600)

                _authState.value = CodexAuthState.DeviceCodeReady(
                    userCode = userCode,
                    verificationUri = verificationUri,
                    expiresInSeconds = expiresIn
                )

                // 2. Poll for token
                val deadline = System.currentTimeMillis() + (expiresIn * 1000L)
                var pollInterval = interval.toLong()

                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(pollInterval * 1000L)

                    val tokenBody = FormBody.Builder()
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .add("device_code", deviceCode)
                        .add("client_id", CLIENT_ID)
                        .build()
                    val tokenRequest = Request.Builder()
                        .url(TOKEN_URL)
                        .post(tokenBody)
                        .build()

                    val tokenResponse = httpClient.newCall(tokenRequest).execute()
                    val tokenBodyStr = tokenResponse.body?.string() ?: continue

                    if (tokenResponse.isSuccessful) {
                        val tokenJson = JSONObject(tokenBodyStr)
                        saveTokens(tokenJson)
                        _authState.value = CodexAuthState.Authenticated(
                            email = tokenJson.optString("id_token")
                                .let { parseEmailFromIdToken(it) }
                        )
                        return@launch
                    }

                    // Handle error cases per RFC 8628
                    val errorJson = runCatching { JSONObject(tokenBodyStr) }.getOrNull()
                    when (errorJson?.optString("error")) {
                        "authorization_pending" -> continue
                        "slow_down" -> { pollInterval += 5; continue }
                        "expired_token" -> {
                            _authState.value = CodexAuthState.Error("Device code expired. Please try again.")
                            return@launch
                        }
                        "access_denied" -> {
                            _authState.value = CodexAuthState.Error("Authorization denied by user.")
                            return@launch
                        }
                        else -> continue
                    }
                }

                if (isActive) {
                    _authState.value = CodexAuthState.Error("Device code expired. Please try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Device code flow error", e)
                _authState.value = CodexAuthState.Error("Device code error: ${e.message}")
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
        pollingJob?.cancel()
        pollingJob = null
        callbackServer?.close()
        callbackServer = null
        _authState.value = CodexAuthState.Idle
    }

    // ── Internal Helpers ────────────────────────────────────────────

    private fun tryBindServer(): Pair<ServerSocket, Int>? {
        for (port in CALLBACK_PORTS) {
            try {
                val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
                return server to port
            } catch (_: Exception) { /* port busy, try next */ }
        }
        return null
    }

    private fun parseCallbackParams(requestLine: String): Map<String, String> {
        // "GET /auth/callback?code=abc&state=xyz HTTP/1.1"
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
        writer.print("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        writer.flush()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
        socket.close()
    }

    private fun codexCallbackSuccessHtml(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width,initial-scale=1" />
          <title>Amaya Codex Login</title>
          <style>
            body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0b0f0e;color:#f6fffb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}
            .card{width:min(420px,calc(100vw - 40px));padding:32px;border-radius:28px;background:linear-gradient(180deg,rgba(255,255,255,.10),rgba(255,255,255,.04));box-shadow:0 24px 80px rgba(0,0,0,.36);text-align:center;border:1px solid rgba(255,255,255,.12)}
            .icon{width:64px;height:64px;margin:0 auto 18px;border-radius:50%;display:grid;place-items:center;background:rgba(16,163,127,.18);color:#20d6aa;font-size:34px}
            h1{font-size:24px;margin:0 0 10px}.muted{color:rgba(246,255,251,.68);line-height:1.5;margin:0}.brand{margin-top:18px;color:#20d6aa;font-weight:700;font-size:13px;letter-spacing:.04em;text-transform:uppercase}
          </style>
        </head>
        <body>
          <main class="card">
            <div class="icon">✓</div>
            <h1>Successfully logged in</h1>
            <p class="muted">Amaya received the Codex authorization. You can close this tab and return to Amaya.</p>
            <div class="brand">Amaya Codex</div>
          </main>
        </body>
        </html>
    """.trimIndent()

    private fun codexCallbackErrorHtml(message: String): String = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8" /><meta name="viewport" content="width=device-width,initial-scale=1" />
        <title>Amaya Codex Login</title></head>
        <body style="margin:0;min-height:100vh;display:grid;place-items:center;background:#160b0b;color:#fff;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif">
          <main style="width:min(420px,calc(100vw - 40px));padding:32px;border-radius:28px;background:rgba(255,255,255,.08);text-align:center;border:1px solid rgba(255,255,255,.12)">
            <div style="font-size:34px;margin-bottom:14px">!</div>
            <h1 style="font-size:24px;margin:0 0 10px">Login not completed</h1>
            <p style="color:rgba(255,255,255,.72);line-height:1.5;margin:0">${htmlEscape(message)}</p>
            <p style="color:rgba(255,255,255,.55);line-height:1.5;margin:18px 0 0">Return to Amaya and try Device Code if the browser redirect keeps failing.</p>
          </main>
        </body></html>
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

    /** Device code is ready — show user_code and verification_uri. */
    data class DeviceCodeReady(
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Int
    ) : CodexAuthState()

    /** Authentication completed successfully. */
    data class Authenticated(val email: String?) : CodexAuthState()

    /** An error occurred. */
    data class Error(val message: String) : CodexAuthState()
}
