# Amaya Provider System — Overview, Provider Tree, Provider Configuration & models.dev Binding

## Goal

Bangun konsep awal **Amaya Provider System** untuk mendukung dua kategori provider AI:

1. **Subscription Login Provider**

   * Provider yang digunakan melalui login akun/subscription resmi.
   * Contoh: ChatGPT/Codex, Claude Code, Google Gemini CLI, GitHub Copilot.
   * Digunakan sebagai **Tool Bridge**, bukan generic API backend.

2. **API Key / Credentials Provider**

   * Provider yang digunakan melalui API key, cloud credentials, bearer token, AWS credentials, atau base URL.
   * Contoh: OpenAI API, Anthropic API, Google Gemini API, AWS Bedrock, Azure OpenAI, Vercel AI Gateway, GitHub Models, OpenRouter, Groq, DeepSeek, xAI, Ollama, LM Studio, custom OpenAI-compatible server.
   * Digunakan sebagai **Gateway Engine**.

Project utama menggunakan **Kotlin**, jadi konfigurasi provider harus dibuat dalam bentuk struktur data Kotlin yang jelas, scalable, dan mudah ditambah provider baru.

Fokus hanya pada:

* Overview sistem provider
* List provider dalam bentuk tree
* Struktur konfigurasi tiap provider
* Field wajib/opsional untuk tiap provider
* Kotlin data model/config untuk provider
* Bagaimana **models.dev** terikat dengan semua model/provider di Amaya


---

# 1. High-Level Overview

Amaya memiliki sistem provider seperti ini:

```txt
Amaya Provider System
├─ Subscription Login Provider
│  ├─ ChatGPT / Codex
│  ├─ Claude Code
│  ├─ Google Gemini CLI
│  └─ GitHub Copilot
│
├─ API Key / Credentials Provider
│  ├─ OpenAI API
│  ├─ Anthropic API
│  ├─ Google Gemini API
│  ├─ Google Vertex AI
│  ├─ AWS Bedrock
│  ├─ Azure OpenAI / Microsoft Foundry
│  ├─ Vercel AI Gateway
│  ├─ GitHub Models
│  ├─ OpenRouter
│  ├─ Groq
│  ├─ DeepSeek
│  ├─ xAI
│  └─ Custom / Local Provider
│     ├─ Ollama
│     ├─ LM Studio
│     ├─ llama.cpp server
│     └─ Custom OpenAI-compatible endpoint
│
└─ Model Catalog
   ├─ models.dev global catalog
   ├─ provider live model list
   ├─ local model scan
   ├─ manual model override
   └─ model alias/routing metadata
```

Amaya harus memisahkan antara:

```txt
Subscription Login
= akun/subscription user via tool resmi
= Tool Bridge
= cocok untuk coding agent, repo edit, terminal task

API Key / Credentials
= direct API/backend call
= Gateway Engine
= cocok untuk one base URL, routing, fallback, usage log, quota, model alias
```

---

# 2. models.dev Binding Concept

## 2.1 Posisi models.dev di Amaya

**models.dev** dipakai sebagai **Global Model Catalog Source**.

Tujuannya agar Amaya tidak perlu memasukkan model satu per satu secara manual.

```txt
models.dev
   ↓
Amaya Global Model Catalog
   ↓
Provider Availability Check
   ↓
Enabled Models / Aliases / Routing
```

models.dev digunakan untuk mengetahui:

```txt
- provider id
- model id
- display name
- pricing
- context window
- input/output limit
- capability flags
- tool calling support
- vision support
- reasoning support
- structured output support
- embeddings support
- image generation support
- release date
- knowledge cutoff
- deprecated/beta/stable status
```

Namun models.dev **bukan pengganti credential manager** dan **bukan penentu availability final**.

Artinya:

```txt
models.dev = source of global model metadata
provider live API = source of availability for connected credential
Amaya DB = source of enable/disable, alias, override, routing, and policy
```

---

## 2.2 Kenapa models.dev tidak bisa berdiri sendiri?

Tidak semua model yang ada di katalog otomatis bisa dipakai user.

Contoh:

```txt
OpenAI
- models.dev tahu model ada
- Amaya tetap perlu cek API key user bisa akses model tersebut atau tidak

AWS Bedrock
- models.dev bisa tahu model Bedrock tersedia secara global
- Amaya tetap perlu cek region, credential, dan model access user

Azure OpenAI
- models.dev tahu base model
- Azure user memakai deployment name custom
- butuh manual mapping deployment → base model

Ollama / LM Studio
- models.dev mungkin tidak tahu model lokal user
- Amaya harus scan local server

Subscription Provider
- ChatGPT/Codex, Claude Code, Gemini CLI, GitHub Copilot bukan generic API model list
- models.dev tidak dipakai sebagai source utama untuk tool bridge
```

---

## 2.3 Tiga Layer Model Catalog

Amaya harus memakai 3 layer model catalog:

```txt
Layer 1: Global Catalog
Source: models.dev
Purpose:
- daftar provider/model global
- metadata model
- harga
- context window
- capability flags
- status model

Layer 2: Provider Availability
Source: provider live API / cloud API / local scan
Purpose:
- cek model benar-benar tersedia untuk credential user
- cek region
- cek model access
- cek deployment name
- cek local installed model

Layer 3: Amaya Override
Source: Amaya database/manual config
Purpose:
- enable/disable model
- hide deprecated model
- custom price
- custom alias
- routing priority
- manual mapping
- workspace policy
```

---

## 2.4 Model Status di Amaya

Setiap model harus punya status lifecycle:

```txt
DISCOVERED
PENDING_REVIEW
AVAILABLE
ENABLED
DISABLED
HIDDEN
DEPRECATED
UNAVAILABLE
NEEDS_CREDENTIAL
NEEDS_ACCESS
REGION_UNSUPPORTED
MANUAL_MAPPING_REQUIRED
SUBSCRIPTION_TOOL_ONLY
```

Contoh:

```txt
anthropic/claude-sonnet
Source: models.dev
Provider: Anthropic
Credential: connected
Availability: available
Status: enabled

bedrock/anthropic.claude-sonnet
Source: models.dev + AWS live check
Region: ap-southeast-1
Availability: needs_access
Status: needs_access

azure/company-prod-gpt41
Source: manual mapping
Base model: openai/gpt-4.1
Status: enabled

chatgpt_codex
Source: subscription login
Mode: Tool Bridge
Status: subscription_tool_only
```

---

## 2.5 Kotlin Model Catalog Data

```kotlin
enum class ModelSource {
    MODELS_DEV,
    PROVIDER_LIVE,
    LOCAL_SCAN,
    MANUAL,
    SUBSCRIPTION_TOOL
}

enum class ModelStatus {
    DISCOVERED,
    PENDING_REVIEW,
    AVAILABLE,
    ENABLED,
    DISABLED,
    HIDDEN,
    DEPRECATED,
    UNAVAILABLE,
    NEEDS_CREDENTIAL,
    NEEDS_ACCESS,
    REGION_UNSUPPORTED,
    MANUAL_MAPPING_REQUIRED,
    SUBSCRIPTION_TOOL_ONLY
}

enum class ModelCapability {
    TEXT_INPUT,
    TEXT_OUTPUT,
    IMAGE_INPUT,
    IMAGE_OUTPUT,
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    VIDEO_INPUT,
    VIDEO_OUTPUT,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    REASONING,
    EMBEDDINGS,
    STREAMING,
    JSON_MODE
}

data class ModelCatalogEntry(
    val id: String,
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val source: ModelSource,
    val status: ModelStatus,
    val capabilities: Set<ModelCapability>,
    val inputPricePerMillionTokens: Double? = null,
    val outputPricePerMillionTokens: Double? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val releaseDate: String? = null,
    val knowledgeCutoff: String? = null,
    val lastSyncedAt: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)
```

---

## 2.6 Provider Availability Mapping

```kotlin
data class ProviderModelAvailability(
    val providerConnectionId: String,
    val catalogModelId: String,
    val providerModelId: String,
    val status: ModelStatus,
    val region: String? = null,
    val deploymentName: String? = null,
    val errorMessage: String? = null,
    val lastCheckedAt: Long? = null
)
```

---

## 2.7 Manual Override Mapping

```kotlin
data class ManualModelOverride(
    val providerId: String,
    val modelId: String,
    val enabled: Boolean,
    val displayNameOverride: String? = null,
    val inputPriceOverride: Double? = null,
    val outputPriceOverride: Double? = null,
    val contextWindowOverride: Int? = null,
    val capabilitiesOverride: Set<ModelCapability>? = null,
    val metadataOverride: Map<String, String> = emptyMap()
)
```

---

## 2.8 Model Alias Mapping

Model alias dipakai agar user tidak perlu memilih model spesifik terus-menerus.

Contoh:

```txt
smart-code
├─ anthropic/claude-sonnet
├─ openai/gpt-5
└─ google/gemini-pro

cheap-fast
├─ groq/llama
├─ deepseek/deepseek-chat
└─ openrouter/qwen
```

Data structure:

```kotlin
enum class ModelAliasStrategy {
    FIXED,
    FALLBACK,
    CHEAPEST,
    FASTEST,
    BEST_REASONING,
    BEST_CODING,
    BALANCED
}

data class ModelAlias(
    val alias: String,
    val strategy: ModelAliasStrategy,
    val routes: List<ModelRoute>
)

data class ModelRoute(
    val providerId: String,
    val modelId: String,
    val priority: Int,
    val enabled: Boolean = true
)
```

---

# 3. Provider Category Rules

## 3.1 Subscription Login Provider

Subscription provider dipakai untuk login akun resmi dan menjalankan tool/CLI/SDK resmi.

Contoh:

```txt
ChatGPT / Codex
Claude Code
Google Gemini CLI
GitHub Copilot
```

Karakteristik:

```txt
- Login menggunakan akun user
- Bisa pakai browser login / OAuth / device flow
- Tidak diperlakukan sebagai API key biasa
- Tidak dipakai sebagai generic /v1/chat/completions backend
- Cocok untuk coding agent, repo edit, terminal task, local tool bridge
```

Subscription provider masuk ke:

```txt
ProviderEngine.TOOL_BRIDGE
```

Bukan:

```txt
ProviderEngine.GATEWAY_ENGINE
```

---

## 3.1.1 Subscription Auth UI Standard

Semua subscription provider harus memakai modal/sheet yang sama seperti OpenAI auth UI global di Amaya.

```txt
- Container: modal bottom sheet
- Header: drag handle + centered title + back/dismiss icon
- Scrim: pakai `modalTopScrim` dan `bottomScrim` dari theme global
- Layout: padding horizontal 24dp, kartu 18dp radius, tombol 16dp radius, tinggi 52-56dp
- Motion: transisi step halus seperti `modalStepTransition`
- State: Methods -> Waiting -> Device Code -> Error
- Primary action: browser/OAuth sign in
- Fallback: device code
- Jangan bikin halaman auth terpisah untuk provider subscription
```

Flow standar:

```txt
Browser flow
1. buka auth URL di Custom Tab
2. tunggu callback / token exchange
3. update state ke Authenticated

Device code flow
1. request device code
2. tampilkan user code + verification URI
3. copy code + open verification page
4. poll sampai auth success/error
```

Provider yang wajib ikut layout ini:

```txt
ChatGPT / Codex
Claude Code
Google Gemini CLI
GitHub Copilot
```

---

## 3.2 API Key / Credentials Provider

API provider dipakai untuk request backend/gateway langsung.

Contoh:

```txt
OpenAI API
Anthropic API
Google Gemini API
AWS Bedrock
Azure OpenAI
Vercel AI Gateway
GitHub Models
OpenRouter
Groq
DeepSeek
xAI
Ollama
LM Studio
Custom OpenAI-compatible endpoint
```

Karakteristik:

```txt
- Menggunakan API key, bearer token, cloud credential, AWS credential, atau base URL
- Bisa masuk ke Amaya Gateway Engine
- Bisa dipakai untuk one base URL
- Bisa dipakai untuk routing, fallback, usage billing, quota, dan model alias
```

---

# 4. Provider Tree

```txt
Provider Center
├─ Subscription Login
│  ├─ ChatGPT / Codex
│  │  ├─ Auth: Sign in with ChatGPT
│  │  ├─ Runtime: Codex CLI / Codex Bridge
│  │  └─ Engine: Tool Bridge
│  │
│  ├─ Claude Code
│  │  ├─ Auth: Claude.ai login
│  │  ├─ Runtime: Claude Code CLI
│  │  └─ Engine: Tool Bridge
│  │
│  ├─ Google Gemini CLI
│  │  ├─ Auth: Login with Google
│  │  ├─ Runtime: Gemini CLI
│  │  └─ Engine: Tool Bridge
│  │
│  └─ GitHub Copilot
│     ├─ Auth: GitHub OAuth (browser/PKCE) + device code fallback
│     ├─ Runtime: Copilot CLI / Copilot SDK Bridge
│     └─ Engine: Tool Bridge
│
├─ API Key / Credentials
│  ├─ OpenAI
│  ├─ Anthropic
│  ├─ Google Gemini API
│  ├─ Google Vertex AI
│  ├─ AWS Bedrock
│  ├─ Azure OpenAI / Microsoft Foundry
│  ├─ Vercel AI Gateway
│  ├─ GitHub Models
│  ├─ OpenRouter
│  ├─ Groq
│  ├─ DeepSeek
│  ├─ xAI
│  └─ Custom Provider
│
└─ Local Provider
   ├─ Ollama
   ├─ LM Studio
   ├─ llama.cpp server
   └─ Custom local OpenAI-compatible server
```

---

# 5. Kotlin Provider Configuration Model

```kotlin
enum class ProviderCategory {
    SUBSCRIPTION_LOGIN,
    API_KEY,
    CLOUD_CREDENTIALS,
    LOCAL,
    CUSTOM
}

enum class ProviderEngine {
    TOOL_BRIDGE,
    GATEWAY_ENGINE,
    LOCAL_ENGINE
}

enum class AuthMode {
    NONE,
    API_KEY,
    BEARER_TOKEN,
    X_API_KEY,
    BASIC_AUTH,
    OAUTH,
    BROWSER_LOGIN,
    DEVICE_FLOW,
    AWS_PROFILE,
    AWS_IAM_KEYS,
    AWS_STS_TOKEN,
    AWS_ASSUME_ROLE,
    BEDROCK_API_KEY,
    GOOGLE_ADC,
    GOOGLE_SERVICE_ACCOUNT,
    AZURE_API_KEY,
    AZURE_ENTRA_ID,
    CUSTOM_HEADERS
}

enum class ApiFormat {
    OPENAI_COMPATIBLE,
    OPENAI_RESPONSES,
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
    BEDROCK_RUNTIME,
    AZURE_OPENAI,
    VERCEL_AI_GATEWAY,
    GITHUB_MODELS,
    TOOL_BRIDGE,
    LOCAL_OPENAI_COMPATIBLE,
    CUSTOM
}

enum class CredentialStorage {
    LOCAL_SECURE_STORAGE,
    BACKEND_VAULT,
    ENVIRONMENT_VARIABLE,
    SYSTEM_PROFILE,
    EXTERNAL_CLI,
    NONE
}

data class ProviderConfig(
    val id: String,
    val displayName: String,
    val category: ProviderCategory,
    val engine: ProviderEngine,
    val authModes: List<AuthMode>,
    val apiFormat: ApiFormat,
    val defaultBaseUrl: String? = null,
    val requiredFields: List<ProviderField>,
    val optionalFields: List<ProviderField> = emptyList(),
    val credentialStorage: CredentialStorage,
    val supportsModelSync: Boolean,
    val modelCatalogSources: List<ModelCatalogSource>,
    val supportsStreaming: Boolean,
    val supportsTools: Boolean,
    val supportsVision: Boolean,
    val supportsEmbeddings: Boolean,
    val supportsImageGeneration: Boolean,
    val supportsLocalRuntime: Boolean = false,
    val notes: String? = null
)

data class ProviderField(
    val key: String,
    val label: String,
    val type: ProviderFieldType,
    val required: Boolean,
    val secret: Boolean = false,
    val placeholder: String? = null,
    val description: String? = null
)

enum class ProviderFieldType {
    TEXT,
    PASSWORD,
    URL,
    SELECT,
    NUMBER,
    BOOLEAN,
    JSON,
    FILE,
    REGION,
    PATH
}

enum class ModelCatalogSource {
    MODELS_DEV,
    PROVIDER_LIVE_API,
    LOCAL_SCAN,
    MANUAL_ONLY,
    SUBSCRIPTION_TOOL
}
```

---

# 6. Provider Configurations

## 6.1 ChatGPT / Codex

```kotlin
val chatgptCodexProvider = ProviderConfig(
    id = "chatgpt_codex",
    displayName = "ChatGPT / Codex",
    category = ProviderCategory.SUBSCRIPTION_LOGIN,
    engine = ProviderEngine.TOOL_BRIDGE,
    authModes = listOf(AuthMode.BROWSER_LOGIN),
    apiFormat = ApiFormat.TOOL_BRIDGE,
    defaultBaseUrl = null,
    requiredFields = listOf(
        ProviderField(
            key = "codexRuntime",
            label = "Codex Runtime",
            type = ProviderFieldType.SELECT,
            required = true,
            description = "Runtime bridge yang digunakan untuk menjalankan Codex task."
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "codexCliPath",
            label = "Codex CLI Path",
            type = ProviderFieldType.PATH,
            required = false,
            description = "Path custom untuk Codex CLI jika tidak memakai default system path."
        ),
        ProviderField(
            key = "workspacePath",
            label = "Default Workspace Path",
            type = ProviderFieldType.PATH,
            required = false,
            description = "Folder kerja default untuk task Codex."
        )
    ),
    credentialStorage = CredentialStorage.EXTERNAL_CLI,
    supportsModelSync = false,
    modelCatalogSources = listOf(ModelCatalogSource.SUBSCRIPTION_TOOL),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = false,
    supportsEmbeddings = false,
    supportsImageGeneration = false,
    supportsLocalRuntime = true,
    notes = "Dipakai sebagai Tool Bridge untuk coding agent, bukan generic API provider."
)
```

---

## 6.2 Claude Code

```kotlin
val claudeCodeProvider = ProviderConfig(
    id = "claude_code",
    displayName = "Claude Code",
    category = ProviderCategory.SUBSCRIPTION_LOGIN,
    engine = ProviderEngine.TOOL_BRIDGE,
    authModes = listOf(AuthMode.BROWSER_LOGIN),
    apiFormat = ApiFormat.TOOL_BRIDGE,
    defaultBaseUrl = null,
    requiredFields = listOf(
        ProviderField(
            key = "claudeCodeRuntime",
            label = "Claude Code Runtime",
            type = ProviderFieldType.SELECT,
            required = true,
            description = "Runtime bridge untuk Claude Code CLI."
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "claudeCodeCliPath",
            label = "Claude Code CLI Path",
            type = ProviderFieldType.PATH,
            required = false
        ),
        ProviderField(
            key = "workspacePath",
            label = "Default Workspace Path",
            type = ProviderFieldType.PATH,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.EXTERNAL_CLI,
    supportsModelSync = false,
    modelCatalogSources = listOf(ModelCatalogSource.SUBSCRIPTION_TOOL),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = false,
    supportsEmbeddings = false,
    supportsImageGeneration = false,
    supportsLocalRuntime = true,
    notes = "Dipakai sebagai Tool Bridge untuk coding workflow dan repo task."
)
```

---

## 6.3 Google Gemini CLI

```kotlin
val googleGeminiCliProvider = ProviderConfig(
    id = "google_gemini_cli",
    displayName = "Google Gemini CLI",
    category = ProviderCategory.SUBSCRIPTION_LOGIN,
    engine = ProviderEngine.TOOL_BRIDGE,
    authModes = listOf(AuthMode.BROWSER_LOGIN, AuthMode.OAUTH),
    apiFormat = ApiFormat.TOOL_BRIDGE,
    defaultBaseUrl = null,
    requiredFields = listOf(
        ProviderField(
            key = "geminiCliRuntime",
            label = "Gemini CLI Runtime",
            type = ProviderFieldType.SELECT,
            required = true
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "geminiCliPath",
            label = "Gemini CLI Path",
            type = ProviderFieldType.PATH,
            required = false
        ),
        ProviderField(
            key = "workspacePath",
            label = "Default Workspace Path",
            type = ProviderFieldType.PATH,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.EXTERNAL_CLI,
    supportsModelSync = false,
    modelCatalogSources = listOf(ModelCatalogSource.SUBSCRIPTION_TOOL),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = false,
    supportsImageGeneration = false,
    supportsLocalRuntime = true,
    notes = "Login Google untuk Gemini CLI. Jangan dicampur dengan Gemini API Key provider."
)
```

---

## 6.4 GitHub Copilot

```kotlin
val githubCopilotProvider = ProviderConfig(
    id = "github_copilot",
    displayName = "GitHub Copilot",
    category = ProviderCategory.SUBSCRIPTION_LOGIN,
    engine = ProviderEngine.TOOL_BRIDGE,
    authModes = listOf(AuthMode.OAUTH, AuthMode.BROWSER_LOGIN, AuthMode.DEVICE_FLOW),
    apiFormat = ApiFormat.TOOL_BRIDGE,
    defaultBaseUrl = null,
    requiredFields = listOf(
        ProviderField(
            key = "copilotRuntime",
            label = "Copilot Runtime",
            type = ProviderFieldType.SELECT,
            required = true,
            description = "Copilot CLI atau Copilot SDK Bridge yang berjalan lokal."
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "githubAccount",
            label = "GitHub Account",
            type = ProviderFieldType.TEXT,
            required = false,
            description = "Label akun setelah OAuth berhasil."
        ),
        ProviderField(
            key = "organization",
            label = "GitHub Organization",
            type = ProviderFieldType.TEXT,
            required = false
        ),
        ProviderField(
            key = "cliPath",
            label = "Copilot CLI Path",
            type = ProviderFieldType.PATH,
            required = false
        ),
        ProviderField(
            key = "workspacePath",
            label = "Default Workspace Path",
            type = ProviderFieldType.PATH,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.LOCAL_SECURE_STORAGE,
    supportsModelSync = false,
    modelCatalogSources = listOf(ModelCatalogSource.SUBSCRIPTION_TOOL),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = false,
    supportsEmbeddings = false,
    supportsImageGeneration = false,
    supportsLocalRuntime = true,
    notes = "Pakai modal auth subscription yang sama seperti OpenAI: browser/OAuth sebagai utama, device code sebagai fallback, dan simpan token terenkripsi lokal. Bedakan dari GitHub Models API."
)
```

---

## 6.5 OpenAI API

```kotlin
val openAiProvider = ProviderConfig(
    id = "openai",
    displayName = "OpenAI API",
    category = ProviderCategory.API_KEY,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(AuthMode.BEARER_TOKEN),
    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
    defaultBaseUrl = "https://api.openai.com/v1",
    requiredFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "OpenAI API Key",
            type = ProviderFieldType.PASSWORD,
            required = true,
            secret = true,
            placeholder = "sk-..."
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "organizationId",
            label = "Organization ID",
            type = ProviderFieldType.TEXT,
            required = false
        ),
        ProviderField(
            key = "projectId",
            label = "Project ID",
            type = ProviderFieldType.TEXT,
            required = false
        ),
        ProviderField(
            key = "baseUrl",
            label = "Custom Base URL",
            type = ProviderFieldType.URL,
            required = false,
            placeholder = "https://api.openai.com/v1"
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = true,
    supportsImageGeneration = true
)
```

---

## 6.6 Anthropic API

```kotlin
val anthropicProvider = ProviderConfig(
    id = "anthropic",
    displayName = "Anthropic API",
    category = ProviderCategory.API_KEY,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(AuthMode.X_API_KEY),
    apiFormat = ApiFormat.ANTHROPIC_MESSAGES,
    defaultBaseUrl = "https://api.anthropic.com",
    requiredFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "Anthropic API Key",
            type = ProviderFieldType.PASSWORD,
            required = true,
            secret = true,
            placeholder = "sk-ant-..."
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "baseUrl",
            label = "Custom Base URL",
            type = ProviderFieldType.URL,
            required = false,
            placeholder = "https://api.anthropic.com"
        ),
        ProviderField(
            key = "anthropicVersion",
            label = "Anthropic Version",
            type = ProviderFieldType.TEXT,
            required = false,
            placeholder = "2023-06-01"
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = false,
    supportsImageGeneration = false
)
```

---

## 6.7 Google Gemini API

```kotlin
val googleGeminiApiProvider = ProviderConfig(
    id = "google_gemini_api",
    displayName = "Google Gemini API",
    category = ProviderCategory.API_KEY,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(AuthMode.API_KEY),
    apiFormat = ApiFormat.GEMINI_GENERATE_CONTENT,
    defaultBaseUrl = "https://generativelanguage.googleapis.com",
    requiredFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "Gemini API Key",
            type = ProviderFieldType.PASSWORD,
            required = true,
            secret = true
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "baseUrl",
            label = "Custom Base URL",
            type = ProviderFieldType.URL,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = true,
    supportsImageGeneration = false
)
```

---

## 6.8 Google Vertex AI

```kotlin
val googleVertexAiProvider = ProviderConfig(
    id = "google_vertex_ai",
    displayName = "Google Vertex AI",
    category = ProviderCategory.CLOUD_CREDENTIALS,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(
        AuthMode.GOOGLE_ADC,
        AuthMode.GOOGLE_SERVICE_ACCOUNT
    ),
    apiFormat = ApiFormat.GEMINI_GENERATE_CONTENT,
    defaultBaseUrl = null,
    requiredFields = listOf(
        ProviderField(
            key = "projectId",
            label = "Google Cloud Project ID",
            type = ProviderFieldType.TEXT,
            required = true
        ),
        ProviderField(
            key = "location",
            label = "Location / Region",
            type = ProviderFieldType.REGION,
            required = true,
            placeholder = "us-central1"
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "serviceAccountJson",
            label = "Service Account JSON",
            type = ProviderFieldType.JSON,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "useApplicationDefaultCredentials",
            label = "Use Application Default Credentials",
            type = ProviderFieldType.BOOLEAN,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = true,
    supportsImageGeneration = false
)
```

---

## 6.9 AWS Bedrock

```kotlin
val awsBedrockProvider = ProviderConfig(
    id = "aws_bedrock",
    displayName = "AWS Bedrock",
    category = ProviderCategory.CLOUD_CREDENTIALS,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(
        AuthMode.AWS_PROFILE,
        AuthMode.AWS_IAM_KEYS,
        AuthMode.AWS_STS_TOKEN,
        AuthMode.AWS_ASSUME_ROLE,
        AuthMode.BEDROCK_API_KEY
    ),
    apiFormat = ApiFormat.BEDROCK_RUNTIME,
    defaultBaseUrl = null,
    requiredFields = listOf(
        ProviderField(
            key = "region",
            label = "AWS Region",
            type = ProviderFieldType.REGION,
            required = true,
            placeholder = "us-east-1"
        ),
        ProviderField(
            key = "authMethod",
            label = "Auth Method",
            type = ProviderFieldType.SELECT,
            required = true,
            description = "AWS Profile, IAM Keys, STS Token, Assume Role, atau Bedrock API Key."
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "profileName",
            label = "AWS Profile Name",
            type = ProviderFieldType.TEXT,
            required = false,
            placeholder = "default"
        ),
        ProviderField(
            key = "accessKeyId",
            label = "AWS Access Key ID",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "secretAccessKey",
            label = "AWS Secret Access Key",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "sessionToken",
            label = "AWS Session Token",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "roleArn",
            label = "Assume Role ARN",
            type = ProviderFieldType.TEXT,
            required = false
        ),
        ProviderField(
            key = "externalId",
            label = "External ID",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "bedrockApiKey",
            label = "Bedrock API Key",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = true,
    supportsImageGeneration = true,
    notes = "Bedrock tidak hanya memakai single API key. Harus support AWS credential chain, IAM keys, STS, assume role, profile, dan Bedrock API key. models.dev hanya memberi katalog global; availability tetap perlu region dan model access check."
)
```

---

## 6.10 Azure OpenAI / Microsoft Foundry

```kotlin
val azureOpenAiProvider = ProviderConfig(
    id = "azure_openai",
    displayName = "Azure OpenAI / Microsoft Foundry",
    category = ProviderCategory.CLOUD_CREDENTIALS,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(
        AuthMode.AZURE_API_KEY,
        AuthMode.AZURE_ENTRA_ID
    ),
    apiFormat = ApiFormat.AZURE_OPENAI,
    defaultBaseUrl = null,
    requiredFields = listOf(
        ProviderField(
            key = "endpoint",
            label = "Azure OpenAI Endpoint",
            type = ProviderFieldType.URL,
            required = true,
            placeholder = "https://your-resource.openai.azure.com"
        ),
        ProviderField(
            key = "authMethod",
            label = "Auth Method",
            type = ProviderFieldType.SELECT,
            required = true
        ),
        ProviderField(
            key = "apiVersion",
            label = "API Version",
            type = ProviderFieldType.TEXT,
            required = true,
            placeholder = "2025-xx-xx"
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "Azure OpenAI API Key",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "tenantId",
            label = "Tenant ID",
            type = ProviderFieldType.TEXT,
            required = false
        ),
        ProviderField(
            key = "clientId",
            label = "Client ID",
            type = ProviderFieldType.TEXT,
            required = false
        ),
        ProviderField(
            key = "clientSecret",
            label = "Client Secret",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "deploymentName",
            label = "Default Deployment Name",
            type = ProviderFieldType.TEXT,
            required = false,
            description = "Azure membutuhkan deployment name, bukan hanya model id."
        ),
        ProviderField(
            key = "baseModelId",
            label = "Base Model ID",
            type = ProviderFieldType.TEXT,
            required = false,
            description = "Mapping deployment Azure ke model katalog, misalnya openai/gpt-4.1."
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = false,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = true,
    supportsImageGeneration = true,
    notes = "Azure butuh mapping deployment name ke base model. models.dev dipakai untuk base model metadata, bukan untuk deployment name user."
)
```

---

## 6.11 Vercel AI Gateway

```kotlin
val vercelAiGatewayProvider = ProviderConfig(
    id = "vercel_ai_gateway",
    displayName = "Vercel AI Gateway",
    category = ProviderCategory.API_KEY,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(
        AuthMode.BEARER_TOKEN,
        AuthMode.OAUTH
    ),
    apiFormat = ApiFormat.VERCEL_AI_GATEWAY,
    defaultBaseUrl = "https://ai-gateway.vercel.sh/v1",
    requiredFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "Vercel AI Gateway API Key",
            type = ProviderFieldType.PASSWORD,
            required = true,
            secret = true
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "baseUrl",
            label = "Custom Base URL",
            type = ProviderFieldType.URL,
            required = false,
            placeholder = "https://ai-gateway.vercel.sh/v1"
        ),
        ProviderField(
            key = "useByok",
            label = "Use BYOK",
            type = ProviderFieldType.BOOLEAN,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = true,
    supportsImageGeneration = true,
    notes = "Vercel AI Gateway bisa punya model list sendiri. models.dev tetap dipakai sebagai metadata enrichment dan fallback catalog."
)
```

---

## 6.12 GitHub Models

```kotlin
val githubModelsProvider = ProviderConfig(
    id = "github_models",
    displayName = "GitHub Models",
    category = ProviderCategory.API_KEY,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(AuthMode.BEARER_TOKEN),
    apiFormat = ApiFormat.GITHUB_MODELS,
    defaultBaseUrl = "https://models.github.ai",
    requiredFields = listOf(
        ProviderField(
            key = "githubToken",
            label = "GitHub Token",
            type = ProviderFieldType.PASSWORD,
            required = true,
            secret = true,
            description = "Token harus memiliki permission models:read."
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "organization",
            label = "GitHub Organization",
            type = ProviderFieldType.TEXT,
            required = false
        ),
        ProviderField(
            key = "baseUrl",
            label = "Custom Base URL",
            type = ProviderFieldType.URL,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = false,
    supportsImageGeneration = false,
    notes = "Bedakan GitHub Models dari GitHub Copilot subscription. GitHub Models adalah API provider, Copilot adalah Tool Bridge."
)
```

---

## 6.13 OpenRouter

```kotlin
val openRouterProvider = ProviderConfig(
    id = "openrouter",
    displayName = "OpenRouter",
    category = ProviderCategory.API_KEY,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(AuthMode.BEARER_TOKEN),
    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
    defaultBaseUrl = "https://openrouter.ai/api/v1",
    requiredFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "OpenRouter API Key",
            type = ProviderFieldType.PASSWORD,
            required = true,
            secret = true
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "siteUrl",
            label = "HTTP Referer / Site URL",
            type = ProviderFieldType.URL,
            required = false
        ),
        ProviderField(
            key = "appName",
            label = "App Name",
            type = ProviderFieldType.TEXT,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = false,
    supportsImageGeneration = false,
    notes = "OpenRouter punya katalog model sendiri. models.dev dipakai untuk metadata normalization dan enrichment."
)
```

---

## 6.14 Groq

```kotlin
val groqProvider = ProviderConfig(
    id = "groq",
    displayName = "Groq",
    category = ProviderCategory.API_KEY,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(AuthMode.BEARER_TOKEN),
    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
    defaultBaseUrl = "https://api.groq.com/openai/v1",
    requiredFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "Groq API Key",
            type = ProviderFieldType.PASSWORD,
            required = true,
            secret = true
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "baseUrl",
            label = "Custom Base URL",
            type = ProviderFieldType.URL,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = false,
    supportsEmbeddings = false,
    supportsImageGeneration = false
)
```

---

## 6.15 DeepSeek

```kotlin
val deepSeekProvider = ProviderConfig(
    id = "deepseek",
    displayName = "DeepSeek",
    category = ProviderCategory.API_KEY,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(AuthMode.BEARER_TOKEN),
    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
    defaultBaseUrl = "https://api.deepseek.com",
    requiredFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "DeepSeek API Key",
            type = ProviderFieldType.PASSWORD,
            required = true,
            secret = true
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "baseUrl",
            label = "Custom Base URL",
            type = ProviderFieldType.URL,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = false,
    supportsEmbeddings = false,
    supportsImageGeneration = false
)
```

---

## 6.16 xAI

```kotlin
val xAiProvider = ProviderConfig(
    id = "xai",
    displayName = "xAI",
    category = ProviderCategory.API_KEY,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(AuthMode.BEARER_TOKEN),
    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
    defaultBaseUrl = "https://api.x.ai/v1",
    requiredFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "xAI API Key",
            type = ProviderFieldType.PASSWORD,
            required = true,
            secret = true
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "baseUrl",
            label = "Custom Base URL",
            type = ProviderFieldType.URL,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.MODELS_DEV,
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = false,
    supportsImageGeneration = false
)
```

---

## 6.17 Ollama

```kotlin
val ollamaProvider = ProviderConfig(
    id = "ollama",
    displayName = "Ollama",
    category = ProviderCategory.LOCAL,
    engine = ProviderEngine.LOCAL_ENGINE,
    authModes = listOf(AuthMode.NONE),
    apiFormat = ApiFormat.LOCAL_OPENAI_COMPATIBLE,
    defaultBaseUrl = "http://localhost:11434",
    requiredFields = listOf(
        ProviderField(
            key = "baseUrl",
            label = "Ollama Base URL",
            type = ProviderFieldType.URL,
            required = true,
            placeholder = "http://localhost:11434"
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "Optional API Key",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        )
    ),
    credentialStorage = CredentialStorage.LOCAL_SECURE_STORAGE,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.LOCAL_SCAN,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = false,
    supportsVision = true,
    supportsEmbeddings = true,
    supportsImageGeneration = false,
    supportsLocalRuntime = true,
    notes = "Model lokal tidak selalu ada di models.dev. Gunakan local scan dan manual metadata override."
)
```

---

## 6.18 LM Studio

```kotlin
val lmStudioProvider = ProviderConfig(
    id = "lm_studio",
    displayName = "LM Studio",
    category = ProviderCategory.LOCAL,
    engine = ProviderEngine.LOCAL_ENGINE,
    authModes = listOf(AuthMode.NONE, AuthMode.BEARER_TOKEN),
    apiFormat = ApiFormat.LOCAL_OPENAI_COMPATIBLE,
    defaultBaseUrl = "http://localhost:1234/v1",
    requiredFields = listOf(
        ProviderField(
            key = "baseUrl",
            label = "LM Studio Base URL",
            type = ProviderFieldType.URL,
            required = true,
            placeholder = "http://localhost:1234/v1"
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "Optional API Key",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        )
    ),
    credentialStorage = CredentialStorage.LOCAL_SECURE_STORAGE,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.LOCAL_SCAN,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = false,
    supportsVision = false,
    supportsEmbeddings = true,
    supportsImageGeneration = false,
    supportsLocalRuntime = true,
    notes = "Model lokal tidak selalu ada di models.dev. Gunakan local scan dan manual metadata override."
)
```

---

## 6.19 llama.cpp Server

```kotlin
val llamaCppProvider = ProviderConfig(
    id = "llama_cpp",
    displayName = "llama.cpp Server",
    category = ProviderCategory.LOCAL,
    engine = ProviderEngine.LOCAL_ENGINE,
    authModes = listOf(AuthMode.NONE, AuthMode.BEARER_TOKEN),
    apiFormat = ApiFormat.LOCAL_OPENAI_COMPATIBLE,
    defaultBaseUrl = "http://localhost:8080/v1",
    requiredFields = listOf(
        ProviderField(
            key = "baseUrl",
            label = "llama.cpp Server Base URL",
            type = ProviderFieldType.URL,
            required = true,
            placeholder = "http://localhost:8080/v1"
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "Optional API Key",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "defaultModel",
            label = "Default Model",
            type = ProviderFieldType.TEXT,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.LOCAL_SECURE_STORAGE,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.LOCAL_SCAN,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = false,
    supportsVision = false,
    supportsEmbeddings = true,
    supportsImageGeneration = false,
    supportsLocalRuntime = true,
    notes = "Model lokal tidak selalu ada di models.dev. Gunakan local scan dan manual metadata override."
)
```

---

## 6.20 Custom OpenAI-Compatible Provider

```kotlin
val customOpenAiCompatibleProvider = ProviderConfig(
    id = "custom_openai_compatible",
    displayName = "Custom OpenAI-Compatible Provider",
    category = ProviderCategory.CUSTOM,
    engine = ProviderEngine.GATEWAY_ENGINE,
    authModes = listOf(
        AuthMode.NONE,
        AuthMode.BEARER_TOKEN,
        AuthMode.API_KEY,
        AuthMode.CUSTOM_HEADERS
    ),
    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
    defaultBaseUrl = null,
    requiredFields = listOf(
        ProviderField(
            key = "providerName",
            label = "Provider Name",
            type = ProviderFieldType.TEXT,
            required = true
        ),
        ProviderField(
            key = "baseUrl",
            label = "Base URL",
            type = ProviderFieldType.URL,
            required = true,
            placeholder = "https://example.com/v1"
        ),
        ProviderField(
            key = "authMethod",
            label = "Auth Method",
            type = ProviderFieldType.SELECT,
            required = true
        )
    ),
    optionalFields = listOf(
        ProviderField(
            key = "apiKey",
            label = "API Key / Token",
            type = ProviderFieldType.PASSWORD,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "customHeaders",
            label = "Custom Headers",
            type = ProviderFieldType.JSON,
            required = false,
            secret = true
        ),
        ProviderField(
            key = "modelsEndpoint",
            label = "Models Endpoint",
            type = ProviderFieldType.URL,
            required = false,
            placeholder = "/models"
        ),
        ProviderField(
            key = "defaultModel",
            label = "Default Model",
            type = ProviderFieldType.TEXT,
            required = false
        )
    ),
    credentialStorage = CredentialStorage.BACKEND_VAULT,
    supportsModelSync = true,
    modelCatalogSources = listOf(
        ModelCatalogSource.PROVIDER_LIVE_API,
        ModelCatalogSource.MANUAL_ONLY
    ),
    supportsStreaming = true,
    supportsTools = true,
    supportsVision = true,
    supportsEmbeddings = true,
    supportsImageGeneration = true,
    notes = "Dipakai untuk LiteLLM, New API, One API, self-hosted gateway, atau provider OpenAI-compatible lain. models.dev bisa dipakai hanya jika model id cocok dengan katalog global."
)
```

---

# 7. Required Provider Registry

Buat registry statis awal:

```kotlin
object AmayaProviderRegistry {
    val providers: List<ProviderConfig> = listOf(
        chatgptCodexProvider,
        claudeCodeProvider,
        googleGeminiCliProvider,
        githubCopilotProvider,

        openAiProvider,
        anthropicProvider,
        googleGeminiApiProvider,
        googleVertexAiProvider,
        awsBedrockProvider,
        azureOpenAiProvider,
        vercelAiGatewayProvider,
        githubModelsProvider,
        openRouterProvider,
        groqProvider,
        deepSeekProvider,
        xAiProvider,

        ollamaProvider,
        lmStudioProvider,
        llamaCppProvider,
        customOpenAiCompatibleProvider
    )
}
```

---

# 8. Important Rules

## Rule 1 — Subscription is not API Key

Jangan memperlakukan subscription provider sebagai generic API backend.

```txt
ChatGPT / Codex
Claude Code
Gemini CLI
GitHub Copilot
```

Semua ini harus masuk ke:

```txt
ProviderEngine.TOOL_BRIDGE
```

Bukan:

```txt
ProviderEngine.GATEWAY_ENGINE
```

---

## Rule 2 — API Key Provider boleh masuk Gateway

Provider berikut boleh digunakan untuk Amaya one base URL, routing, fallback, billing, dan usage tracking:

```txt
OpenAI
Anthropic
Google Gemini API
Google Vertex AI
AWS Bedrock
Azure OpenAI
Vercel AI Gateway
GitHub Models
OpenRouter
Groq
DeepSeek
xAI
Custom OpenAI-compatible
Local OpenAI-compatible
```

---

## Rule 3 — Bedrock harus multi-auth

AWS Bedrock jangan dibuat hanya field `apiKey`.

Bedrock harus support:

```txt
AWS Profile
IAM Access Key + Secret
STS Session Token
Assume Role ARN
Bedrock API Key
Region
```

---

## Rule 4 — Azure butuh deployment mapping

Azure OpenAI tidak cukup hanya model id.

Harus ada:

```txt
Endpoint
API Version
Deployment Name
Base Model Mapping
Auth Method
```

models.dev hanya membantu metadata base model, bukan deployment name milik user.

---

## Rule 5 — models.dev hanya katalog awal

models.dev boleh dipakai untuk auto-import:

```txt
provider
model id
display name
pricing
context window
capabilities
release date
status
```

Tapi jangan menganggap semua model dari models.dev otomatis tersedia.

Availability tetap dicek melalui:

```txt
provider live sync
credential validation
region check
manual override
```

---

## Rule 6 — Local model tidak bergantung pada models.dev

Provider lokal seperti Ollama, LM Studio, dan llama.cpp harus memakai:

```txt
local scan
manual metadata override
optional models.dev enrichment jika model id cocok
```

---

## Rule 7 — Custom provider harus fleksibel

Custom OpenAI-compatible provider harus mendukung:

```txt
custom base URL
optional API key
custom headers
manual model entry
optional models endpoint
manual capabilities
```

---

# 9. Deliverable

Hasil implementasi yang diminta dari prompt ini:

```txt
1. Kotlin data model untuk provider configuration
2. Kotlin data model untuk model catalog binding
3. Provider registry awal
4. Provider config untuk semua provider di atas
5. Field required/optional untuk setiap provider
6. Kategori provider jelas:
   - Subscription Login
   - API Key
   - Cloud Credentials
   - Local
   - Custom
7. Engine provider jelas:
   - Tool Bridge
   - Gateway Engine
   - Local Engine
8. models.dev binding jelas:
   - Global model catalog
   - Provider availability layer
   - Manual override layer
   - Alias/routing metadata layer
```


Fokus hanya pada **overview provider system, konfigurasi provider, dan hubungan models.dev dengan model catalog Amaya**.
