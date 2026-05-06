# Amaya Android — Subscription Provider Auth Documentation

> Scope: dokumentasi ini untuk **Kotlin Android**. Tidak memakai CLI. Tidak memakai WebView token scraping. Tidak mengambil cookie/session token dari aplikasi/web resmi provider. Semua flow harus memakai OAuth/API resmi, Custom Tabs, App Links/deep link, atau backend token exchange.

---

# 1. Core Principle

Amaya Android memiliki dua jalur provider:

```txt
Amaya Android Provider System
├─ Subscription Login Provider
│  ├─ Google Account / Gemini-related account login
│  ├─ GitHub Copilot via GitHub OAuth + backend bridge
│  ├─ OpenAI / ChatGPT / Codex official OAuth slot
│  └─ Claude official OAuth slot
│
└─ API Key / Credentials Provider
   ├─ OpenAI API Key
   ├─ Anthropic API Key
   ├─ Google Gemini API Key
   ├─ Google Vertex AI Credentials
   ├─ AWS Bedrock Credentials
   ├─ Azure OpenAI Credentials
   ├─ Vercel AI Gateway API Key
   ├─ GitHub Models Token
   ├─ OpenRouter API Key
   ├─ Groq API Key
   ├─ DeepSeek API Key
   ├─ xAI API Key
   └─ Custom OpenAI-compatible Provider
```


---

# 2. Android Auth Architecture

Untuk Android, Amaya harus memakai pola:

```txt
User taps Connect
   ↓
Amaya opens Custom Tabs / Google Identity / AppAuth
   ↓
Provider login page opens in external browser surface
   ↓
Provider redirects to Amaya App Link / custom scheme
   ↓
Amaya receives authorization code
   ↓
If public native client:
   Android exchanges code with PKCE

If confidential client:
   Android sends code to Amaya backend
   Backend exchanges code with client secret
   Backend stores provider token in vault
   Android only stores Amaya session
```

Jangan memakai WebView untuk login OAuth.

Recommended Android components:

```txt
- Chrome Custom Tabs
- AndroidX Browser
- AppAuth Android
- Google Identity Services
- App Links / custom URI scheme
- PKCE
- Android Keystore
- EncryptedSharedPreferences or secure local storage
```

---

# 3. Provider Support Matrix

```txt
Subscription Providers
├─ Google
│  ├─ Android-native: Supported
│  ├─ Auth: Google Identity / OAuth / AppAuth
│  ├─ Token storage: Android Keystore or backend vault
│  └─ Notes: Gemini API usage may still need API key, OAuth scopes, or Vertex setup
│
├─ GitHub Copilot
│  ├─ Android-native: Experimental
│  ├─ Auth: GitHub OAuth App
│  ├─ Token exchange: Amaya backend recommended
│  ├─ Runtime: Copilot SDK/backend bridge if officially supported
│  └─ Notes: Do not scrape Copilot web/app session
│
├─ OpenAI / ChatGPT / Codex
│  ├─ Android-native: Only if Amaya has official OpenAI OAuth app/client
│  ├─ Auth: OAuth/OIDC with Amaya-owned client_id
│  ├─ Token exchange: PKCE or backend exchange depending on registration
│  ├─ Fallback: OpenAI API Key
│  └─ Notes: Do not reuse Codex official client_id as Amaya
│
└─ Claude / Claude Code
   ├─ Android-native: Only if Amaya has official Claude OAuth/app integration
   ├─ Fallback: Anthropic API Key / Bedrock / Vertex AI
   └─ Notes: Do not reuse Claude/Claude Code internal auth tokens
```

---

# 4. OpenAI / ChatGPT / Codex Auth Notes

OpenAI/Codex authorization URLs can look like this:

```txt
https://auth.openai.com/oauth/authorize
  ?response_type=code
  &client_id=...
  &redirect_uri=...
  &scope=openid profile email offline_access ...
  &code_challenge=...
  &code_challenge_method=S256
  &state=...
```

custom
 https://auth.openai.com/oauth/authorize?response_type=code&client_id
 =app_EMoamEEZ73f0CkXaXp7hrann&redirect_uri=http%3A%2F%2Flocalhost%3A
 1455%2Fauth%2Fcallback&scope=openid+profile+email+offline_access&cod
 e_challenge=1l-utQpEIfviRSgyyE9jPW9Fjvy-a-8-1_n8qDZHJ6s&code_challen
 ge_method=S256&state=a1cd6fe88a71279ae09c44c7bfcd0b3e&id_token_add_o
 rganizations=true&codex_cli_simplified_flow=true&originator=pi

codex official
https://auth.openai.com/oauth/authorize?response_type=code&client_id=app_EMoamEEZ73f0CkXaXp7hrann&redirect_uri=http%3A%2F%2Flocalhost%3A1457%2Fauth%2Fcallback&scope=openid%20profile%20email%20offline_access%20api.connectors.read%20api.connectors.invoke&code_challenge=8HGhZwjOMNBHSE-GE0oVc9Fnnnw1K2d_rcT6sfgvgAk&code_challenge_method=S256&id_token_add_organizations=true&codex_cli_simplified_flow=true&state=yq4wEhWiUNws-JGn8vqQG9eXhWpEYKjh5ifGkd3yDuE&originator=Codex%20Desktop  

contoh real

Parameters:

```txt
client_id
= OAuth client identifier.
= Must belong to the app performing the integration.
= Amaya should use an Amaya-owned client_id, not a Codex-owned client_id.

redirect_uri
= Callback URI registered for that OAuth client.
= For Android, prefer App Links or custom scheme.

scope
= Permissions requested.
= Provider may reject scopes not allowed for the client.

code_challenge
= PKCE challenge derived from code_verifier.
= Required for native app OAuth.

state
= CSRF/session binding value.
= Must be generated per auth request and verified on callback.

originator / product flags
= Provider-specific metadata.
= Must not be relied on for stable third-party integration.
```

## 4.1 What Amaya Should Support

Amaya should support this safe OpenAI auth mode:

```txt
OpenAIAuthMode.OFFICIAL_OAUTH
├─ authorizationEndpoint: https://auth.openai.com/oauth/authorize
├─ tokenEndpoint: official token endpoint if provided to Amaya
├─ clientId: Amaya-owned client ID
├─ redirectUri: com.amaya.app:/oauth/openai or https://auth.amaya.app/openai/callback
├─ scopes: provider-approved scopes
├─ pkce: enabled
└─ storage: Android Keystore or backend vault
```

Amaya should also support:

```txt
OpenAIAuthMode.API_KEY
├─ API key input
├─ optional organization ID
├─ optional project ID
└─ encrypted storage
```

## 4.2 What Amaya Must Not Implement

```txt
OpenAIAuthMode.REUSE_CODEX_CLIENT_ID = prohibited
OpenAIAuthMode.CAPTURE_CODEX_LOCALHOST_TOKEN = prohibited
OpenAIAuthMode.EXTRACT_CHATGPT_COOKIE = prohibited
OpenAIAuthMode.WEBVIEW_SESSION_SCRAPE = prohibited
```

Reason:

```txt
- client_id belongs to another application
- redirect URI may be registered only for that application
- token audience may be limited to that application
- scopes may be product-internal
- implementation can break anytime
- unsafe for user accounts and production release
```

---

# 5. GitHub Copilot Android Flow

GitHub Copilot is the most realistic subscription integration if Amaya uses GitHub OAuth and an official Copilot SDK/backend bridge.

Recommended architecture:

```txt
Amaya Android
   ↓
GitHub OAuth via Custom Tabs
   ↓
Amaya Backend Callback
   ↓
Backend exchanges code using GitHub OAuth client secret
   ↓
Backend stores token in vault
   ↓
Backend calls official Copilot SDK/API path
   ↓
Android receives Amaya-normalized response
```

## 5.1 GitHub OAuth App Setup

Create a GitHub OAuth App:

```txt
Application name: Amaya
Homepage URL: https://amaya.app
Authorization callback URL: https://api.amaya.app/oauth/github/callback
```

Android does not store GitHub client secret.

## 5.2 Android Connect Button

```kotlin
fun connectGitHubCopilot(context: Context, amayaSessionToken: String) {
    val uri = Uri.parse("https://api.amaya.app/oauth/github/start")
        .buildUpon()
        .appendQueryParameter("provider", "github_copilot")
        .appendQueryParameter("session", amayaSessionToken)
        .build()

    val customTabsIntent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()

    customTabsIntent.launchUrl(context, uri)
}
```

Backend start endpoint builds GitHub authorization URL:

```txt
https://github.com/login/oauth/authorize
  ?client_id=AMAYA_GITHUB_CLIENT_ID
  &redirect_uri=https://api.amaya.app/oauth/github/callback
  &state=SECURE_RANDOM_STATE
```

## 5.3 Backend Callback Responsibilities

```txt
1. Verify state
2. Read authorization code
3. Exchange code for GitHub user token
4. Store token encrypted in Amaya backend vault
5. Mark provider connection as connected
6. Redirect user back to Amaya Android via App Link
```

Pseudo backend exchange:

```kotlin
suspend fun exchangeGitHubCode(code: String): GitHubTokenResponse {
    return httpClient.post("https://github.com/login/oauth/access_token") {
        header("Accept", "application/json")
        contentType(ContentType.Application.Json)
        setBody(
            mapOf(
                "client_id" to System.getenv("GITHUB_CLIENT_ID"),
                "client_secret" to System.getenv("GITHUB_CLIENT_SECRET"),
                "code" to code
            )
        )
    }.body()
}
```

## 5.4 Android Provider Config

```kotlin
val githubCopilotSubscriptionProvider = AndroidSubscriptionProviderConfig(
    id = "github_copilot",
    displayName = "GitHub Copilot",
    status = SubscriptionSupportStatus.EXPERIMENTAL,
    authFlow = AndroidAuthFlow.OAUTH_BACKEND_EXCHANGE,
    connectUrl = "https://api.amaya.app/oauth/github/start",
    callbackUri = "amaya://oauth/github/callback",
    tokenOwner = TokenOwner.AMAYA_BACKEND,
    canUseAsGenericApiBackend = false,
    notes = "Use GitHub OAuth + backend Copilot SDK bridge. Do not scrape Copilot session."
)
```

---

# 6. Google / Gemini Android Flow

Google has the strongest Android-native support.

There are three separate modes:

```txt
Google Account Login
= authenticate user identity and authorized Google scopes

Gemini API Key
= direct API provider mode

Vertex AI
= cloud credential / backend integration mode
```

## 6.1 Google Account Login

Recommended architecture:

```txt
Amaya Android
   ↓
Google Identity / OAuth via Custom Tabs
   ↓
Google authorization
   ↓
Android receives auth result
   ↓
Amaya stores token or sends auth code to backend
```

Config:

```kotlin
data class GoogleSubscriptionConfig(
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val useBackendExchange: Boolean
)
```

Provider config:

```kotlin
val googleSubscriptionProvider = AndroidSubscriptionProviderConfig(
    id = "google",
    displayName = "Google",
    status = SubscriptionSupportStatus.SUPPORTED,
    authFlow = AndroidAuthFlow.GOOGLE_IDENTITY,
    connectUrl = null,
    callbackUri = "com.amaya.app:/oauth/google",
    tokenOwner = TokenOwner.ANDROID_KEYSTORE_OR_BACKEND,
    canUseAsGenericApiBackend = false,
    notes = "Google login is supported. Gemini API usage may still require Gemini API key, OAuth scopes, or Vertex AI."
)
```

## 6.2 Gemini API Key Mode

This is not subscription login. This is API provider mode.

```kotlin
data class GeminiApiKeyProviderConfig(
    val apiKey: String,
    val baseUrl: String = "https://generativelanguage.googleapis.com"
)
```

UI fields:

```txt
Google Gemini API
├─ API Key
├─ Base URL optional
└─ Test Connection
```

## 6.3 Vertex AI Mode

Recommended for production if Amaya has backend/cloud component.

```txt
Vertex AI Provider
├─ Project ID
├─ Location
├─ Service account / OAuth / backend identity
├─ Model mapping
└─ Test connection
```

Do not place service account private keys directly inside APK.

---

# 7. ChatGPT / Codex Android Flow

For Amaya Android, ChatGPT/Codex should have these modes:

```txt
ChatGPT / Codex Provider
├─ Official OAuth App Mode
│  ├─ only if Amaya has its own OpenAI-approved OAuth client
│  └─ use AppAuth + PKCE
│
├─ OpenAI API Key Mode
│  ├─ supported now
│  └─ recommended for Amaya Gateway
│
└─ External Handoff Mode
   ├─ open official ChatGPT/Codex page/app
   └─ no token capture
```

## 7.1 Official OAuth Mode Placeholder

```kotlin
data class OpenAiOfficialOAuthConfig(
    val authorizationEndpoint: String = "https://auth.openai.com/oauth/authorize",
    val tokenEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val usePkce: Boolean = true
)
```

Provider config:

```kotlin
val openAiSubscriptionProvider = AndroidSubscriptionProviderConfig(
    id = "openai_subscription",
    displayName = "OpenAI / ChatGPT / Codex",
    status = SubscriptionSupportStatus.REQUIRES_OFFICIAL_OAUTH_CLIENT,
    authFlow = AndroidAuthFlow.OAUTH_CUSTOM_TABS_PKCE,
    connectUrl = null,
    callbackUri = "com.amaya.app:/oauth/openai",
    tokenOwner = TokenOwner.ANDROID_KEYSTORE_OR_BACKEND,
    canUseAsGenericApiBackend = false,
    notes = "Only supported if Amaya owns an official OpenAI OAuth client. Do not reuse Codex client_id."
)
```

## 7.2 OpenAI API Key Mode

```kotlin
data class OpenAiApiKeyProviderConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.openai.com/v1",
    val organizationId: String? = null,
    val projectId: String? = null
)
```

UI fields:

```txt
OpenAI API
├─ API Key
├─ Organization ID optional
├─ Project ID optional
├─ Custom Base URL optional
└─ Test Connection
```

## 7.3 External Handoff Mode

```kotlin
fun openChatGptCodexHandoff(context: Context) {
    val uri = Uri.parse("https://chatgpt.com/codex")
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    intent.launchUrl(context, uri)
}
```

This mode does not return a token to Amaya.

---

# 8. Claude / Claude Code Android Flow

For Amaya Android, Claude should have these modes:

```txt
Claude Provider
├─ Official OAuth App Mode
│  ├─ only if Anthropic/Claude provides Amaya-approved OAuth flow
│  └─ use AppAuth + PKCE
│
├─ Anthropic API Key Mode
│  ├─ supported now
│  └─ recommended for Amaya Gateway
│
├─ Claude via Bedrock
│  ├─ AWS credential provider
│  └─ region/model access check
│
├─ Claude via Vertex AI
│  ├─ Google Cloud auth
│  └─ project/location mapping
│
└─ External Handoff Mode
   ├─ open claude.ai
   └─ no token capture
```

## 8.1 Anthropic API Key Mode

```kotlin
data class AnthropicApiKeyProviderConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.anthropic.com",
    val anthropicVersion: String = "2023-06-01"
)
```

UI fields:

```txt
Anthropic API
├─ API Key
├─ Anthropic Version optional
├─ Custom Base URL optional
└─ Test Connection
```

## 8.2 External Handoff Mode

```kotlin
fun openClaudeHandoff(context: Context) {
    val uri = Uri.parse("https://claude.ai")
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    intent.launchUrl(context, uri)
}
```

---

# 9. Android Token Storage

Use secure storage for any token that belongs to Amaya.

```kotlin
data class OAuthTokenBundle(
    val providerId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long?,
    val scopes: List<String>,
    val accountId: String? = null,
    val accountEmail: String? = null
)

interface SecureTokenStore {
    suspend fun save(providerId: String, tokenBundle: OAuthTokenBundle)
    suspend fun get(providerId: String): OAuthTokenBundle?
    suspend fun delete(providerId: String)
}
```

Token owner strategy:

```kotlin
enum class TokenOwner {
    ANDROID_KEYSTORE,
    AMAYA_BACKEND,
    ANDROID_KEYSTORE_OR_BACKEND,
    PROVIDER_APP_ONLY,
    NONE
}
```

Recommended:

```txt
Android-only provider token
→ Android Keystore + encrypted local storage

Provider requiring client secret
→ Amaya backend vault
→ Android stores only Amaya session

External handoff
→ no token stored by Amaya
```

---

# 10. Android Subscription Provider Config Model

```kotlin
enum class SubscriptionSupportStatus {
    SUPPORTED,
    EXPERIMENTAL,
    REQUIRES_OFFICIAL_OAUTH_CLIENT,
    UNSUPPORTED,
    EXTERNAL_HANDOFF_ONLY
}

enum class AndroidAuthFlow {
    GOOGLE_IDENTITY,
    OAUTH_CUSTOM_TABS_PKCE,
    OAUTH_BACKEND_EXCHANGE,
    API_KEY_FALLBACK,
    EXTERNAL_HANDOFF_ONLY,
    UNSUPPORTED
}

data class AndroidSubscriptionProviderConfig(
    val id: String,
    val displayName: String,
    val status: SubscriptionSupportStatus,
    val authFlow: AndroidAuthFlow,
    val connectUrl: String?,
    val callbackUri: String?,
    val tokenOwner: TokenOwner,
    val canUseAsGenericApiBackend: Boolean,
    val notes: String
)
```

Registry:

```kotlin
object AndroidSubscriptionProviderRegistry {
    val providers = listOf(
        AndroidSubscriptionProviderConfig(
            id = "google",
            displayName = "Google",
            status = SubscriptionSupportStatus.SUPPORTED,
            authFlow = AndroidAuthFlow.GOOGLE_IDENTITY,
            connectUrl = null,
            callbackUri = "com.amaya.app:/oauth/google",
            tokenOwner = TokenOwner.ANDROID_KEYSTORE_OR_BACKEND,
            canUseAsGenericApiBackend = false,
            notes = "Supported for Google login. Gemini API may require API key/OAuth scopes/Vertex."
        ),
        AndroidSubscriptionProviderConfig(
            id = "github_copilot",
            displayName = "GitHub Copilot",
            status = SubscriptionSupportStatus.EXPERIMENTAL,
            authFlow = AndroidAuthFlow.OAUTH_BACKEND_EXCHANGE,
            connectUrl = "https://api.amaya.app/oauth/github/start",
            callbackUri = "amaya://oauth/github/callback",
            tokenOwner = TokenOwner.AMAYA_BACKEND,
            canUseAsGenericApiBackend = false,
            notes = "Use GitHub OAuth + backend bridge. Do not scrape Copilot web session."
        ),
        AndroidSubscriptionProviderConfig(
            id = "openai_subscription",
            displayName = "OpenAI / ChatGPT / Codex",
            status = SubscriptionSupportStatus.REQUIRES_OFFICIAL_OAUTH_CLIENT,
            authFlow = AndroidAuthFlow.OAUTH_CUSTOM_TABS_PKCE,
            connectUrl = null,
            callbackUri = "com.amaya.app:/oauth/openai",
            tokenOwner = TokenOwner.ANDROID_KEYSTORE_OR_BACKEND,
            canUseAsGenericApiBackend = false,
            notes = "Only if Amaya owns an official OpenAI OAuth client. Use API key fallback otherwise."
        ),
        AndroidSubscriptionProviderConfig(
            id = "claude_subscription",
            displayName = "Claude / Claude Code",
            status = SubscriptionSupportStatus.REQUIRES_OFFICIAL_OAUTH_CLIENT,
            authFlow = AndroidAuthFlow.OAUTH_CUSTOM_TABS_PKCE,
            connectUrl = null,
            callbackUri = "com.amaya.app:/oauth/claude",
            tokenOwner = TokenOwner.ANDROID_KEYSTORE_OR_BACKEND,
            canUseAsGenericApiBackend = false,
            notes = "Only if Amaya owns an official Claude OAuth client. Use Anthropic API key/Bedrock/Vertex fallback otherwise."
        )
    )
}
```

---

# 11. UI Copy for Subscription Providers

```txt
Google
Status: Supported
Description: Connect your Google account using Android-native Google sign-in. Gemini API access may require API key, OAuth scopes, or Vertex configuration.
Button: Connect Google

GitHub Copilot
Status: Experimental
Description: Connect GitHub using OAuth. Amaya backend will use the official Copilot integration path if available for your account.
Button: Connect GitHub

OpenAI / ChatGPT / Codex
Status: Requires official OAuth client
Description: ChatGPT/Codex subscription login can only be used if Amaya has an official OpenAI OAuth integration. Otherwise use OpenAI API key.
Buttons: Add OpenAI API Key / Open ChatGPT

Claude / Claude Code
Status: Requires official OAuth client
Description: Claude subscription login can only be used if Amaya has an official Claude OAuth integration. Otherwise use Anthropic API key, Bedrock, or Vertex.
Buttons: Add Anthropic API Key / Open Claude
```

---

# 12. Security Rules

```txt
1. Do not use another app's OAuth client_id as Amaya.
2. Do not capture tokens from localhost callbacks intended for another app.
3. Do not read provider cookies.
4. Do not log access tokens.
5. Do not store refresh tokens in plaintext.
6. Do not place OAuth client secrets inside APK.
7. Do not use WebView for provider login.
8. Always verify state.
9. Always use PKCE for native OAuth.
10. Prefer backend vault for tokens that require client secret exchange.
```

---

# 13. Final Android Implementation Plan

```txt
Phase 1 — Safe MVP
├─ Google login
├─ Gemini API Key
├─ OpenAI API Key
├─ Anthropic API Key
├─ GitHub Models Token
├─ Vercel/OpenRouter/Groq/DeepSeek/xAI API keys
└─ Custom OpenAI-compatible provider

Phase 2 — Backend OAuth
├─ GitHub Copilot OAuth
├─ Backend token exchange
├─ Backend Copilot SDK bridge
└─ Android provider status sync

Phase 3 — Official OAuth Slots
├─ OpenAI/ChatGPT/Codex official OAuth if Amaya gets client approval
├─ Claude official OAuth if available
└─ Android AppAuth + PKCE

Phase 4 — External Handoff
├─ Open ChatGPT/Codex official surface
├─ Open Claude official surface
└─ No token capture
```

---

# 14. Summary

Amaya Android subscription system should be designed as:

```txt
Google
= supported Android-native login

GitHub Copilot
= experimental with GitHub OAuth + backend bridge

OpenAI / ChatGPT / Codex
= official OAuth only if Amaya owns approved client; otherwise API key or handoff

Claude / Claude Code
= official OAuth only if Amaya owns approved client; otherwise Anthropic API key/Bedrock/Vertex or handoff
```

Do not build Amaya around reusing internal OAuth clients from Codex/Desktop or any provider-owned app. Build the architecture so Amaya can plug in official OAuth clients later while shipping API-key providers now.
