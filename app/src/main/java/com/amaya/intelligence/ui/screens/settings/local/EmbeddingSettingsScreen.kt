package com.amaya.intelligence.ui.screens.settings.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.data.remote.api.MemoryEmbeddingConfig
import com.amaya.intelligence.ui.screens.amaya.AmayaScaffold
import com.amaya.intelligence.ui.screens.amaya.AmayaSection
import com.amaya.intelligence.ui.screens.amaya.AmayaSwitchRow
import kotlinx.coroutines.launch

private const val FORMAT_OPENAI = "openai_compatible"
private const val FORMAT_GEMINI = "gemini"

@Composable
fun EmbeddingSettingsScreen(
    settingsManager: AiSettingsManager,
    onNavigateBack: () -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(FORMAT_OPENAI) }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Never crash the screen over settings storage — surface the failure instead.
        runCatching {
            val current = settingsManager.getSettings().memoryEmbedding
            enabled = current.enabled
            format = current.format
            endpoint = current.endpoint
            model = current.model
            apiKey = settingsManager.getMemoryEmbeddingApiKey()
        }.onFailure { failure ->
            snackbar.showSnackbar("Could not load semantic settings: ${failure.message}")
        }
        loaded = true
    }

    val placeholder = when (format) {
        FORMAT_GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        else -> "https://integrate.api.nvidia.com/v1"
    }
    val modelHint = when (format) {
        FORMAT_GEMINI -> "text-embedding-004 (or another embedding model)"
        else -> "nvidia/llama-3.2-nv-embedqa-1b-v2, text-embedding-3-small, …"
    }

    AmayaScaffold("Semantic Memory", snackbar, onNavigateBack) {
        AmayaSection("Embedding Provider") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Optional. When enabled, memory recall re-ranks its top local matches by embedding similarity " +
                        "against your query, using any cloud embedding API you configure. Any failure (offline, bad key, " +
                        "wrong model) automatically falls back to local ranking — semantic recall never breaks a search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AmayaSwitchRow(
                    title = "Enable semantic recall",
                    subtitle = "Re-rank memory with an embedding API instead of local matching alone",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    enabled = loaded
                )
                Text(
                    "Provider format",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = format == FORMAT_OPENAI,
                        onClick = { format = FORMAT_OPENAI },
                        label = { Text("OpenAI-compatible") },
                        enabled = loaded
                    )
                    FilterChip(
                        selected = format == FORMAT_GEMINI,
                        onClick = { format = FORMAT_GEMINI },
                        label = { Text("Google Gemini") },
                        enabled = loaded
                    )
                }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API endpoint (base URL)") },
                    supportingText = { Text("Leave Gemini blank to use the default endpoint. Example: $placeholder") },
                    singleLine = true,
                    enabled = loaded,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Embedding model") },
                    supportingText = { Text(modelHint) },
                    singleLine = true,
                    enabled = loaded,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key") },
                    supportingText = { Text("Stored encrypted on-device. Leave blank to keep the current key.") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = loaded,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                settingsManager.saveMemoryEmbedding(
                                    MemoryEmbeddingConfig(
                                        enabled = enabled,
                                        format = format,
                                        endpoint = endpoint,
                                        model = model
                                    ),
                                    apiKey = apiKey.ifBlank { null }
                                )
                            }.onSuccess {
                                snackbar.showSnackbar("Semantic memory settings saved")
                            }.onFailure { failure ->
                                snackbar.showSnackbar("Failed to save: ${failure.message}")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = loaded
                ) { Text("Save") }
            }
        }
    }
}
