package com.amaya.intelligence.ui.screens.agent.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.remote.api.AgentConfig
import com.amaya.intelligence.data.remote.api.AmayaProviderRegistry
import com.amaya.intelligence.data.remote.api.KnownModelCatalog
import com.amaya.intelligence.data.remote.api.ModelCatalogEntry
import com.amaya.intelligence.data.remote.api.ProviderCategory
import com.amaya.intelligence.ui.components.shared.ignoreNestedScrollForBottomSheet
import com.amaya.intelligence.ui.components.shared.rememberLockedModalBottomSheetState
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch

private enum class AgentWizardCategory { SUBSCRIPTION, API_KEY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditSheet(
    config: AgentConfig,
    apiKey: String,
    isNew: Boolean,
    maxSheetHeight: Dp,
    modelCatalog: List<ModelCatalogEntry> = emptyList(),
    onDismiss: () -> Unit,
    codexAuthenticated: Boolean = false,
    codexEmail: String? = null,
    onCodexLoginClick: (() -> Unit)? = null,
    onCodexLogoutClick: (() -> Unit)? = null,
    onQuickSave: ((AgentConfig, String) -> Unit)? = null,
    onSave: (AgentConfig, String) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberLockedModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val gradients = LocalAmayaGradients.current
    val scrollState = rememberScrollState()

    var wizardCategory by remember(config.id, isNew) { mutableStateOf<AgentWizardCategory?>(if (isNew) null else AgentWizardCategory.API_KEY) }
    var selectedProviderId by remember(config.id, isNew) { mutableStateOf(if (isNew) null else config.providerId.takeIf { it.isNotBlank() }) }
    val selectedProvider = AmayaProviderRegistry.find(selectedProviderId)
    val stepKey = when {
        isNew && wizardCategory == null -> "category"
        isNew && selectedProvider == null -> "provider_${wizardCategory?.name.orEmpty()}"
        selectedProvider?.isSubscription == true -> "subscription_${selectedProvider.id}"
        else -> "api_${selectedProviderId ?: config.providerId}"
    }

    var name by remember(config.id) { mutableStateOf(config.name) }
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl) }
    var modelId by remember(config.id) { mutableStateOf(config.modelId) }
    var key by remember(config.id) { mutableStateOf(apiKey) }
    var enabled by remember(config.id) { mutableStateOf(config.enabled) }
    var maxTokensStr by remember(config.id, isNew) { mutableStateOf(if (isNew) "" else config.maxTokens.toString()) }
    var maxIterationsStr by remember(config.id, isNew) { mutableStateOf(if (isNew) "" else config.maxIterations.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var toolCalling by remember(config.id) { mutableStateOf(config.toolCalling) }
    var vision by remember(config.id) { mutableStateOf(config.vision) }
    var reasoning by remember(config.id) { mutableStateOf(config.reasoning) }
    var structuredOutput by remember(config.id) { mutableStateOf(config.structuredOutput) }
    var embeddings by remember(config.id) { mutableStateOf(config.embeddings) }
    var jsonMode by remember(config.id) { mutableStateOf(config.jsonMode) }
    var streaming by remember(config.id) { mutableStateOf(config.streaming) }
    var enabledModelIds by remember(config.id) {
        mutableStateOf((config.enabledModelIds.ifEmpty { listOf(config.modelId) }).filter { it.isNotBlank() }.toSet())
    }
    var stableModelCatalog by remember(config.id) { mutableStateOf(modelCatalog) }

    LaunchedEffect(modelCatalog) {
        if (stableModelCatalog.isEmpty() && modelCatalog.isNotEmpty()) stableModelCatalog = modelCatalog
    }

    LaunchedEffect(selectedProviderId, stableModelCatalog.size) {
        val provider = AmayaProviderRegistry.find(selectedProviderId)
        if (provider?.isSubscription == true && (modelId.isBlank() || modelId == provider.id)) {
            val firstModel = stableModelCatalog.firstOrNull { it.providerId == provider.id }?.modelId
            if (!firstModel.isNullOrBlank()) {
                modelId = firstModel
                enabledModelIds = (enabledModelIds - provider.id + firstModel).filter { it.isNotBlank() }.toSet()
            }
        }
    }

    fun closeThen(block: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) block()
        }
    }

    fun goBack() {
        when {
            selectedProvider != null && isNew -> selectedProviderId = null
            wizardCategory != null && isNew -> wizardCategory = null
        }
    }

    fun buildSubscriptionConfig(provider: com.amaya.intelligence.data.remote.api.ProviderConfig, models: Set<String>, defaultModel: String): AgentConfig {
        val cleanModels = (models + defaultModel)
            .filter { it.isNotBlank() && it != provider.id }
            .distinct()
        val cleanDefault = defaultModel.takeIf { it.isNotBlank() && it != provider.id }
            ?: cleanModels.firstOrNull().orEmpty()
        return config.copy(
            name = name.ifBlank { provider.displayName },
            providerId = provider.id,
            providerType = AmayaProviderRegistry.legacyProviderType(provider.id).name,
            baseUrl = "",
            modelId = cleanDefault,
            enabled = provider.id != "openai_codex_bridge" || codexAuthenticated,
            toolCalling = provider.supportsTools,
            vision = provider.supportsVision,
            streaming = provider.supportsStreaming,
            enabledModelIds = cleanModels
        )
    }

    fun quickSaveSubscription(models: Set<String>, defaultModel: String = modelId) {
        val provider = selectedProvider ?: return
        if (!provider.isSubscription) return
        onQuickSave?.invoke(buildSubscriptionConfig(provider, models, defaultModel), "")
    }

    LaunchedEffect(stepKey) {
        if (scrollState.value != 0) scrollState.animateScrollTo(0)
    }

    ModalBottomSheet(
        onDismissRequest = { closeThen(onDismiss) },
        sheetState = sheetState,
        properties = com.amaya.intelligence.ui.components.shared.lockedModalBottomSheetProperties(),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        shape = com.amaya.intelligence.ui.components.shared.responsiveBottomSheetShape(sheetState)
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .weight(1f, fill = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .ignoreNestedScrollForBottomSheet()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(96.dp))
                AnimatedContent(
                    targetState = stepKey,
                    transitionSpec = { modalStepTransition() },
                    label = "agent_step_transition"
                ) { _ ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                    when {
                        isNew && wizardCategory == null -> CategoryStep { wizardCategory = it }
                        isNew && selectedProvider == null -> ProviderStep(
                            category = wizardCategory ?: AgentWizardCategory.API_KEY,
                            onSelect = { provider ->
                            selectedProviderId = provider.id
                            name = provider.displayName
                            if (provider.defaultBaseUrl != null) baseUrl = provider.defaultBaseUrl
                            toolCalling = provider.supportsTools
                            vision = provider.supportsVision
                            embeddings = provider.supportsEmbeddings
                            streaming = provider.supportsStreaming
                            if (modelId.isBlank()) {
                                modelId = stableModelCatalog.firstOrNull { it.providerId == provider.id }?.modelId
                                    ?: KnownModelCatalog.entries.firstOrNull { it.providerId == provider.id }?.modelId.orEmpty()
                            }
                            enabledModelIds = setOf(modelId).filter { it.isNotBlank() }.toSet()
                        }
                        )
                        selectedProvider?.isSubscription == true -> SubscriptionStep(
                        providerId = selectedProvider.id,
                        providerName = selectedProvider.displayName,
                        modelId = modelId,
                        onModelId = { selectedModel ->
                            modelId = selectedModel
                            val next = (enabledModelIds + selectedModel).filter { it.isNotBlank() }.toSet()
                            enabledModelIds = next
                            quickSaveSubscription(next, selectedModel)
                        },
                        modelCatalog = stableModelCatalog.filter { it.providerId == selectedProvider.id },
                        enabledModelIds = enabledModelIds,
                        onEnabledModelIds = { selectedModels ->
                            val clean = selectedModels.filter { it.isNotBlank() && it != selectedProvider.id }.toSet()
                            val nextDefault = modelId.takeIf { it in clean } ?: clean.firstOrNull().orEmpty()
                            modelId = nextDefault
                            enabledModelIds = clean
                            quickSaveSubscription(clean, nextDefault)
                        },
                        codexAuthenticated = codexAuthenticated,
                        codexEmail = codexEmail,
                        onCodexLoginClick = onCodexLoginClick,
                        onCodexLogoutClick = onCodexLogoutClick
                        )
                        else -> ApiProviderForm(
                        config = config,
                        isNew = isNew,
                        providerId = selectedProviderId ?: config.providerId,
                        name = name,
                        onName = { name = it },
                        key = key,
                        onKey = { key = it },
                        showKey = showKey,
                        onToggleShowKey = { showKey = !showKey },
                        baseUrl = baseUrl,
                        onBaseUrl = { baseUrl = it },
                        modelId = modelId,
                        onModelId = { modelId = it },
                        maxTokensStr = maxTokensStr,
                        onMaxTokens = { v -> if (v.all { it.isDigit() }) maxTokensStr = v },
                        maxIterationsStr = maxIterationsStr,
                        onMaxIterations = { v -> if (v.all { it.isDigit() }) maxIterationsStr = v },
                        enabled = enabled,
                        onEnabled = { enabled = it },
                        toolCalling = toolCalling,
                        onToolCalling = { toolCalling = it },
                        vision = vision,
                        onVision = { vision = it },
                        reasoning = reasoning,
                        onReasoning = { reasoning = it },
                        structuredOutput = structuredOutput,
                        onStructuredOutput = { structuredOutput = it },
                        embeddings = embeddings,
                        onEmbeddings = { embeddings = it },
                        jsonMode = jsonMode,
                        onJsonMode = { jsonMode = it },
                        streaming = streaming,
                        onStreaming = { streaming = it },
                        modelCatalog = stableModelCatalog.filter { it.providerId == (selectedProviderId ?: config.providerId) },
                        enabledModelIds = enabledModelIds,
                        onEnabledModelIds = { enabledModelIds = it },
                        onDelete = onDelete?.let { { showDeleteConfirm = true } },
                        onSave = {
                            val providerId = selectedProviderId ?: config.providerId.ifBlank { "openai" }
                            val provider = AmayaProviderRegistry.find(providerId)
                            closeThen {
                                onSave(
                                    config.copy(
                                        name = name.trim(),
                                        providerType = AmayaProviderRegistry.legacyProviderType(providerId).name,
                                        providerId = providerId,
                                        baseUrl = baseUrl.trim().ifBlank { provider?.defaultBaseUrl.orEmpty() },
                                        modelId = modelId.trim(),
                                        enabled = enabled,
                                        maxTokens = maxTokensStr.toIntOrNull()?.coerceIn(256, 128_000) ?: config.maxTokens,
                                        maxIterations = maxIterationsStr.toIntOrNull()?.coerceIn(1, 50) ?: config.maxIterations,
                                        toolCalling = toolCalling,
                                        vision = vision,
                                        reasoning = reasoning,
                                        structuredOutput = structuredOutput,
                                        embeddings = embeddings,
                                        jsonMode = jsonMode,
                                        streaming = streaming,
                                        enabledModelIds = (enabledModelIds + modelId.trim()).filter { it.isNotBlank() }.distinct()
                                    ),
                                    key.trim()
                                )
                            }
                        }
                        )
                    }
                    }
                }
            }

            SheetHeader(
                title = when {
                    isNew && wizardCategory == null -> "New Agent"
                    isNew && selectedProvider == null -> if (wizardCategory == AgentWizardCategory.SUBSCRIPTION) "Subscription Provider" else "API Provider"
                    selectedProvider?.isSubscription == true -> selectedProvider.displayName
                    isNew -> "Configure Agent"
                    else -> "Edit Agent"
                },
                gradient = gradients.modalTopScrim,
                onBack = if (isNew && (wizardCategory != null || selectedProvider != null)) ::goBack else null,
                onDismiss = { closeThen(onDismiss) },
                modifier = Modifier.align(Alignment.TopCenter),
                sheetState = sheetState
            )
        }
    }

    if (showDeleteConfirm && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Agent") },
            text = { Text("This agent will be removed from Amaya.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; closeThen(onDelete) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CategoryStep(onSelect: (AgentWizardCategory) -> Unit) {
    PickerCard(Icons.Default.AccountCircle, "Subscription", "ChatGPT, Google, or GitHub", onClick = { onSelect(AgentWizardCategory.SUBSCRIPTION) })
    PickerCard(Icons.Default.Key, "API key", "OpenAI-compatible, Gemini, local, or custom", onClick = { onSelect(AgentWizardCategory.API_KEY) })
}

@Composable
private fun ProviderStep(category: AgentWizardCategory, onSelect: (com.amaya.intelligence.data.remote.api.ProviderConfig) -> Unit) {
    val providers = AmayaProviderRegistry.providers.filter {
        if (category == AgentWizardCategory.SUBSCRIPTION) it.category == ProviderCategory.SUBSCRIPTION_LOGIN
        else it.category != ProviderCategory.SUBSCRIPTION_LOGIN
    }
    providers.forEach { provider ->
        PickerCard(
            icon = if (provider.category == ProviderCategory.SUBSCRIPTION_LOGIN) Icons.Default.AccountCircle else Icons.Default.Api,
            title = provider.displayName,
            subtitle = providerSubtitle(provider),
            onClick = { onSelect(provider) }
        )
    }
}

private fun providerSubtitle(provider: com.amaya.intelligence.data.remote.api.ProviderConfig): String = when (provider.id) {
    "openai_codex_bridge" -> "ChatGPT subscription models"
    "google_subscription" -> "Google account"
    "github_copilot" -> "GitHub Copilot account"
    else -> provider.defaultBaseUrl ?: provider.apiFormat.name.lowercase().replace('_', ' ')
}

@Composable
private fun SubscriptionStep(
    providerId: String,
    providerName: String,
    modelId: String,
    onModelId: (String) -> Unit,
    modelCatalog: List<ModelCatalogEntry>,
    enabledModelIds: Set<String>,
    onEnabledModelIds: (Set<String>) -> Unit,
    codexAuthenticated: Boolean,
    codexEmail: String?,
    onCodexLoginClick: (() -> Unit)?,
    onCodexLogoutClick: (() -> Unit)?
) {
    when (providerId) {
        "openai_codex_bridge" -> {
            CodexSubscriptionConnectionCard(
                authenticated = codexAuthenticated,
                email = codexEmail,
                onLoginClick = onCodexLoginClick,
                onLogoutClick = onCodexLogoutClick
            )
            if (modelCatalog.isNotEmpty()) {
                ManageProviderModelsSection(
                    providerName = providerName,
                    modelCatalog = modelCatalog,
                    defaultModelId = modelId,
                    enabledModelIds = enabledModelIds,
                    onEnabledModelIds = onEnabledModelIds,
                    onSetDefaultModel = onModelId,
                    showDefaultControls = false
                )
            } else {
                Text("Codex models are not synced yet. Try opening this modal again after models.dev sync finishes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "github_copilot" -> Text("Sign in support is prepared for a future bridge.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        "google_subscription" -> Text("Sign in support is prepared.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodexSubscriptionConnectionCard(
    authenticated: Boolean,
    email: String?,
    onLoginClick: (() -> Unit)?,
    onLogoutClick: (() -> Unit)?
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (authenticated) Color(0xFF10A37F).copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (authenticated) Icons.Default.CheckCircle else Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (authenticated) Color(0xFF10A37F) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("ChatGPT / Codex login", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (authenticated) email ?: "Connected to OpenAI auth" else "Connect once, then use it from Codex subscription agents.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (authenticated) Color(0xFF10A37F) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (authenticated) {
                TextButton(onClick = { onLogoutClick?.invoke() }) { Text("Sign out") }
            } else {
                Button(
                    onClick = { onLoginClick?.invoke() },
                    enabled = onLoginClick != null,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Sign in") }
            }
        }
    }
}

@Composable
private fun ApiProviderForm(
    config: AgentConfig,
    isNew: Boolean,
    providerId: String,
    name: String,
    onName: (String) -> Unit,
    key: String,
    onKey: (String) -> Unit,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    modelId: String,
    onModelId: (String) -> Unit,
    maxTokensStr: String,
    onMaxTokens: (String) -> Unit,
    maxIterationsStr: String,
    onMaxIterations: (String) -> Unit,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    toolCalling: Boolean,
    onToolCalling: (Boolean) -> Unit,
    vision: Boolean,
    onVision: (Boolean) -> Unit,
    reasoning: Boolean,
    onReasoning: (Boolean) -> Unit,
    structuredOutput: Boolean,
    onStructuredOutput: (Boolean) -> Unit,
    embeddings: Boolean,
    onEmbeddings: (Boolean) -> Unit,
    jsonMode: Boolean,
    onJsonMode: (Boolean) -> Unit,
    streaming: Boolean,
    onStreaming: (Boolean) -> Unit,
    modelCatalog: List<ModelCatalogEntry>,
    enabledModelIds: Set<String>,
    onEnabledModelIds: (Set<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit
) {
    val provider = AmayaProviderRegistry.find(providerId)
    val requiresKey = provider?.requiredFields?.any { it.key == "apiKey" } == true && provider.category != ProviderCategory.LOCAL
    val requiresBaseUrl = provider?.defaultBaseUrl == null || provider.category == ProviderCategory.LOCAL || provider.category == ProviderCategory.CUSTOM
    val isValid = name.trim().isNotBlank() && modelId.trim().isNotBlank() && (!requiresKey || key.trim().isNotBlank()) && (!requiresBaseUrl || baseUrl.trim().isNotBlank())

    Text(provider?.displayName ?: "Custom Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

    Field(name, onName, "Agent Name", Icons.AutoMirrored.Filled.Label)
    SecretField(key, onKey, showKey, onToggleShowKey, provider?.requiredFields?.firstOrNull { it.key == "apiKey" }?.label ?: "API Key")
    Field(baseUrl, onBaseUrl, "Base URL", Icons.Default.Link, placeholder = provider?.defaultBaseUrl ?: "https://example.com/v1")
    Field(modelId, onModelId, "Default Model", Icons.Default.Psychology, placeholder = modelCatalog.firstOrNull()?.modelId ?: KnownModelCatalog.entries.firstOrNull { it.providerId == providerId }?.modelId ?: "model-id")
    ManageProviderModelsSection(
        providerName = provider?.displayName ?: "Provider",
        modelCatalog = modelCatalog,
        defaultModelId = modelId,
        enabledModelIds = enabledModelIds,
        onEnabledModelIds = onEnabledModelIds,
        onSetDefaultModel = onModelId
    )
    Field(maxTokensStr, onMaxTokens, "Max Tokens", Icons.Default.Tune, placeholder = "8192")
    Field(maxIterationsStr, onMaxIterations, "Max Iterations", Icons.Default.Repeat, placeholder = "10")

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Capabilities override", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    CapabilitySwitch("Tool calling", "Send Amaya tools to this model", toolCalling, onToolCalling)
    CapabilitySwitch("Vision", "Allow image-capable usage for this agent", vision, onVision)
    CapabilitySwitch("Reasoning", "Model has reasoning/thinking capability", reasoning, onReasoning)
    CapabilitySwitch("Structured output", "Prefer schema/structured output when supported", structuredOutput, onStructuredOutput)
    CapabilitySwitch("Embeddings", "Provider/model can be used for embeddings", embeddings, onEmbeddings)
    CapabilitySwitch("JSON mode", "Model supports JSON-style responses", jsonMode, onJsonMode)
    CapabilitySwitch("Streaming", "Use streaming responses", streaming, onStreaming)
    CapabilitySwitch("Enabled", "Agent available in chat selector", enabled, onEnabled)

    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onDelete != null) {
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Delete")
            }
        }
        Button(onClick = onSave, enabled = isValid, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save")
        }
    }
}

@Composable
private fun ManageProviderModelsSection(
    providerName: String,
    modelCatalog: List<ModelCatalogEntry>,
    defaultModelId: String,
    enabledModelIds: Set<String>,
    onEnabledModelIds: (Set<String>) -> Unit,
    onSetDefaultModel: (String) -> Unit,
    showDefaultControls: Boolean = true
) {
    var query by remember { mutableStateOf("") }
    val sortedModels = remember(modelCatalog) { modelCatalog.distinctBy { it.modelId }.sortedBy { it.displayName.lowercase() } }
    val q = query.trim().lowercase()
    val visibleModels = sortedModels
        .filter { q.isBlank() || it.displayName.lowercase().contains(q) || it.modelId.lowercase().contains(q) }
        .take(80)

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Manage Models", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    if (showDefaultControls) {
        Text(
            "Only checked $providerName models will appear in Select Agent. Your default model is always kept enabled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) }
        },
        label = { Text("Search provider models") }
    )

    if (sortedModels.isEmpty()) {
        Text(
            "No models.dev catalog found for this provider yet. Save a default model manually or refresh models from chat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (showDefaultControls) {
            TextButton(onClick = { onEnabledModelIds(setOf(defaultModelId).filter { it.isNotBlank() }.toSet()) }) { Text("Default only") }
        }
        TextButton(onClick = { onEnabledModelIds(sortedModels.map { it.modelId }.toSet()) }) { Text("Check all") }
        TextButton(onClick = { onEnabledModelIds(if (showDefaultControls) setOf(defaultModelId).filter { it.isNotBlank() }.toSet() else emptySet()) }) { Text("Clear") }
    }

    visibleModels.forEach { entry ->
        val checked = (showDefaultControls && entry.modelId == defaultModelId) || entry.modelId in enabledModelIds
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    val lockedDefault = showDefaultControls && entry.modelId == defaultModelId
                    val next = if (checked && !lockedDefault) enabledModelIds - entry.modelId else enabledModelIds + entry.modelId
                    onEnabledModelIds((if (showDefaultControls) next + defaultModelId else next).filter { it.isNotBlank() }.toSet())
                }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { isChecked ->
                        val next = if (isChecked) enabledModelIds + entry.modelId else enabledModelIds - entry.modelId
                        onEnabledModelIds((if (showDefaultControls) next + defaultModelId else next).filter { it.isNotBlank() }.toSet())
                    },
                    enabled = !showDefaultControls || entry.modelId != defaultModelId
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(entry.modelId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    entry.contextWindow?.let {
                        Text("Context ${formatTokenCountLocal(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f))
                    }
                }
                if (showDefaultControls && entry.modelId == defaultModelId) {
                    AssistChip(onClick = {}, label = { Text("Default") })
                }
            }
        }
    }
    if (visibleModels.size < sortedModels.size) {
        Text("Showing ${visibleModels.size} of ${sortedModels.size}. Use search to narrow results.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTokenCountLocal(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

@Composable
private fun PickerCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, placeholder: String = "") {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) }
    )
}

@Composable
private fun SecretField(value: String, onValue: (String) -> Unit, show: Boolean, onToggleShow: () -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text("Enter token or API key") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        leadingIcon = { Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp)) },
        trailingIcon = { IconButton(onClick = onToggleShow) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } }
    )
}

@Composable
private fun CapabilitySwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun modalStepTransition(): ContentTransform {
    val spec = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    return (slideInHorizontally(spec) { it / 5 } + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))) togetherWith
        (slideOutHorizontally(spec) { -it / 8 } + fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetHeader(
    title: String,
    gradient: androidx.compose.ui.graphics.Brush,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier,
    sheetState: SheetState
) {
    Column(modifier = modifier.fillMaxWidth().background(gradient)) {
        Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = com.amaya.intelligence.ui.components.shared.responsiveDragHandleAlpha(sheetState)))
            )
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.background))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(20.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.background))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(20.dp))
            }
        }
    }
}
