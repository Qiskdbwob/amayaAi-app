# Model Settings

Amaya stores provider connections and the models you choose to show in chat. It does not use a global model catalog.

## Concepts

| Concept | Purpose |
|---|---|
| Provider preset | Stable adapter, official endpoint, and auth requirement |
| Provider connection | One configured credential or subscription account |
| Visible model | A model shown in the chat model selector |
| Active model selection | The exact connection and model used by chat |

The active selection uses both IDs:

```text
connectionId + modelId
```

This keeps credentials correct when you configure multiple connections for the same provider.

## Settings flow

```text
Settings
  → Manage Models
  → Add Provider
  → Connect
  → Choose Models Shown in Chat
  → Select Model
  → Chat
```

Use a full screen for provider model inventory. Use bottom sheets for provider setup, credential replacement, manual model entry, and model selection.

## Persistence

`AiSettingsManager` stores these non-secret values in DataStore:

```kotlin
data class ProviderConnection(
    val id: String,
    val name: String,
    val providerId: String,
    val baseUrl: String,
    val visibleModels: List<ConfiguredModel>
)

data class ActiveModelSelection(
    val connectionId: String,
    val modelId: String
)
```

Encrypted SharedPreferences stores API keys by connection ID. The UI never reads an existing key into a text field. It only shows **API key saved** and **Replace API Key**.

## Model discovery

`ProviderModelService` queries the configured provider directly:

| Adapter | Discovery request |
|---|---|
| OpenAI-compatible | `GET /models` with bearer auth when configured |
| Anthropic | `GET /models` with `x-api-key` |
| Gemini | `GET /models` with `x-goog-api-key` |
| GitHub Models | GitHub catalog endpoint; chat uses the inference endpoint |
| OpenAI subscription | Manual model ID after authenticated sign-in |

If refresh fails, saved models remain available. Custom OpenAI-compatible endpoints can be saved without discovery, then configured with manual model IDs.

Amaya persists only models you choose. It does not persist pricing, capabilities, context limits, release dates, routing aliases, or inferred metadata.

## Provider URLs

Official provider presets use their fixed endpoint. Only **OpenAI-compatible** exposes a Base URL field.

- Public endpoints must use HTTPS.
- HTTP is accepted only for loopback, private LAN, or `.local` hosts.
- URLs must include a scheme.
- URLs cannot embed credentials, queries, fragments, or final API method paths.

## Chat selection

Chat receives a `ModelOption` key:

```text
model|<connection-id>|<model-id>
```

Local and Windows Bridge chat resolve the exact connection before sending a request. Opencode and Antigravity keep using model lists owned by their runtimes.

## Removed systems

The current system intentionally excludes:

- `models.dev`
- Built-in model catalogs
- Local model providers
- Model capabilities
- Default models and star actions
- Pricing and context metadata
- Model aliases and routing
- Manual metadata overrides
- Provider and model Room tables

Room migration `8 → 9` drops the obsolete provider and catalog tables. DataStore migration reads the previous agent JSON and converts supported connections while preserving connection IDs, so encrypted credentials remain addressable.
