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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.remote.api.AgentConfig
import com.amaya.intelligence.data.remote.api.AmayaProviderRegistry
import com.amaya.intelligence.data.remote.api.ModelCatalogEntry
import com.amaya.intelligence.data.remote.api.ProviderCategory
import com.amaya.intelligence.ui.components.shared.ignoreNestedScrollForBottomSheet
import com.amaya.intelligence.ui.components.shared.rememberLockedModalBottomSheetState
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch

private enum class AgentWizardCategory { SUBSCRIPTION, API_KEY }

private const val MinAgentMaxTokens = 256
private const val MaxAgentMaxTokens = 128_000
private const val MinAgentIterations = 1
private const val MaxAgentIterations = 50

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditSheet(
    config: AgentConfig,
    apiKey: String,
    isNew: Boolean,
    maxSheetHeight: Dp,
    modelCatalog: List<ModelCatalogEntry> = emptyList(),
    onDismiss: () -> Unit,
    subscriptionAuths: List<AgentSubscriptionAuthUi> = emptyList(),
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
    var showSubscriptionAuthFlow by remember(config.id, isNew) { mutableStateOf(false) }
    var showAdvancedSettings by remember(config.id, isNew) { mutableStateOf(false) }
    var showModelPicker by remember(config.id, isNew) { mutableStateOf(false) }
    var enableStepAnimation by remember(config.id, isNew) { mutableStateOf(false) }
    val selectedProvider = remember(selectedProviderId) { AmayaProviderRegistry.find(selectedProviderId) }
    val selectedSubscriptionAuth = remember(selectedProvider?.id, subscriptionAuths) {
        selectedProvider?.id?.let { providerId ->
            subscriptionAuths.firstOrNull { it.providerId == providerId }
        }
    }
    val stepKey = when {
        isNew && wizardCategory == null -> "category"
        isNew && selectedProvider == null -> "provider_${wizardCategory?.name.orEmpty()}"
        showSubscriptionAuthFlow && selectedProvider?.id == selectedSubscriptionAuth?.providerId -> subscriptionAuthStepKey(selectedSubscriptionAuth)
        selectedProvider?.isSubscription == true && showModelPicker -> "models_${selectedProvider.id}"
        selectedProvider?.isSubscription == true -> "subscription_${selectedProvider.id}"
        showModelPicker -> "models_${selectedProviderId ?: config.providerId}"
        showAdvancedSettings -> "advanced_${selectedProviderId ?: config.providerId}"
        else -> "api_${selectedProviderId ?: config.providerId}"
    }
    val hasSaveFooter = stepKey.startsWith("api_") || stepKey.startsWith("advanced_") || stepKey.startsWith("subscription_") || stepKey.startsWith("models_")

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
        mutableStateOf(config.enabledModelIds.filter { it.isNotBlank() && it != config.modelId }.toSet())
    }
    var stableModelCatalog by remember(config.id) { mutableStateOf(modelCatalog) }

    LaunchedEffect(modelCatalog) {
        if (stableModelCatalog.isEmpty() && modelCatalog.isNotEmpty()) stableModelCatalog = modelCatalog
    }


    fun closeThen(block: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) block()
        }
    }

    fun goBack() {
        when {
            showSubscriptionAuthFlow && selectedSubscriptionAuth?.step !is SubscriptionAuthStep.Methods && selectedSubscriptionAuth?.step !is SubscriptionAuthStep.Error -> selectedSubscriptionAuth?.onCancel?.invoke()
            showSubscriptionAuthFlow -> showSubscriptionAuthFlow = false
            showModelPicker -> showModelPicker = false
            showAdvancedSettings -> showAdvancedSettings = false
            selectedProvider != null && isNew -> selectedProviderId = null
            wizardCategory != null && isNew -> wizardCategory = null
        }
    }

    fun buildSubscriptionConfig(provider: com.amaya.intelligence.data.remote.api.ProviderConfig, models: Set<String>): AgentConfig {
        val cleanModels = models
            .filter { it.isNotBlank() && it != provider.id }
            .distinct()
        return config.copy(
            name = name.ifBlank { provider.displayName },
            providerId = provider.id,
            providerType = AmayaProviderRegistry.legacyProviderType(provider.id).name,
            baseUrl = "",
            modelId = "",
            enabled = selectedSubscriptionAuth?.let { auth -> provider.id != auth.providerId || auth.authenticated } ?: true,
            toolCalling = provider.supportsTools,
            vision = provider.supportsVision,
            streaming = provider.supportsStreaming,
            enabledModelIds = cleanModels
        )
    }

    fun quickSaveSubscription(models: Set<String>) {
        val provider = selectedProvider ?: return
        if (!provider.isSubscription) return
        onQuickSave?.invoke(buildSubscriptionConfig(provider, models), "")
    }

    fun apiProviderConfig(providerId: String): AgentConfig {
        val provider = AmayaProviderRegistry.find(providerId)
        return config.copy(
            name = name.trim(),
            providerType = AmayaProviderRegistry.legacyProviderType(providerId).name,
            providerId = providerId,
            baseUrl = baseUrl.trim().ifBlank { provider?.defaultBaseUrl.orEmpty() },
            modelId = modelId.trim(),
            enabled = enabled,
            maxTokens = maxTokensStr.toIntOrNull()?.coerceIn(MinAgentMaxTokens, MaxAgentMaxTokens) ?: config.maxTokens,
            maxIterations = maxIterationsStr.toIntOrNull()?.coerceIn(MinAgentIterations, MaxAgentIterations) ?: config.maxIterations,
            toolCalling = toolCalling,
            vision = vision,
            reasoning = reasoning,
            structuredOutput = structuredOutput,
            embeddings = embeddings,
            jsonMode = jsonMode,
            streaming = streaming,
            enabledModelIds = (enabledModelIds + modelId.trim()).filter { it.isNotBlank() }.distinct()
        )
    }

    fun apiFormIsValid(providerId: String): Boolean {
        val provider = AmayaProviderRegistry.find(providerId)
        val requiresKey = provider?.requiredFields?.any { it.key == "apiKey" } == true && provider.category != ProviderCategory.LOCAL
        val requiresBaseUrl = provider?.defaultBaseUrl == null || provider.category == ProviderCategory.LOCAL || provider.category == ProviderCategory.CUSTOM
        return name.trim().isNotBlank() && modelId.trim().isNotBlank() && (!requiresKey || key.trim().isNotBlank()) && (!requiresBaseUrl || baseUrl.trim().isNotBlank())
    }

    fun saveApiProvider(providerId: String) {
        closeThen { onSave(apiProviderConfig(providerId), key.trim()) }
    }

    fun saveSubscriptionProvider() {
        val provider = selectedProvider ?: return
        if (provider.isSubscription) {
            closeThen { onSave(buildSubscriptionConfig(provider, enabledModelIds), "") }
        }
    }

    LaunchedEffect(selectedSubscriptionAuth?.authenticated) {
        if (selectedSubscriptionAuth?.authenticated == true) showSubscriptionAuthFlow = false
    }

    LaunchedEffect(config.id, isNew) {
        enableStepAnimation = false
        withFrameNanos { }
        enableStepAnimation = true
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
                    .padding(bottom = if (hasSaveFooter) 132.dp else 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(96.dp))
                if (enableStepAnimation) {
                    AnimatedContent(
                        targetState = stepKey,
                        transitionSpec = { modalStepTransition(initialState, targetState) },
                        label = "agent_step_transition"
                    ) { activeStepKey ->
                        AgentStepContent(
                            activeStepKey = activeStepKey,
                            isNew = isNew,
                            config = config,
                            selectedProviderId = selectedProviderId,
                            selectedProvider = selectedProvider,
                            selectedSubscriptionAuth = selectedSubscriptionAuth,
                            stableModelCatalog = stableModelCatalog,
                            name = name,
                            onNameChange = { name = it },
                            baseUrl = baseUrl,
                            onBaseUrlChange = { baseUrl = it },
                            modelId = modelId,
                            onModelIdChange = { modelId = it },
                            key = key,
                            onKeyChange = { key = it },
                            showKey = showKey,
                            onToggleShowKey = { showKey = !showKey },
                            enabled = enabled,
                            onEnabledChange = { enabled = it },
                            maxTokensStr = maxTokensStr,
                            onMaxTokensChange = { v -> if (v.all { it.isDigit() }) maxTokensStr = v },
                            maxIterationsStr = maxIterationsStr,
                            onMaxIterationsChange = { v -> if (v.all { it.isDigit() }) maxIterationsStr = v },
                            toolCalling = toolCalling,
                            onToolCallingChange = { toolCalling = it },
                            vision = vision,
                            onVisionChange = { vision = it },
                            reasoning = reasoning,
                            onReasoningChange = { reasoning = it },
                            structuredOutput = structuredOutput,
                            onStructuredOutputChange = { structuredOutput = it },
                            embeddings = embeddings,
                            onEmbeddingsChange = { embeddings = it },
                            jsonMode = jsonMode,
                            onJsonModeChange = { jsonMode = it },
                            streaming = streaming,
                            onStreamingChange = { streaming = it },
                            enabledModelIds = enabledModelIds,
                            onEnabledModelIdsChange = { enabledModelIds = it },
                            onWizardCategoryChange = { wizardCategory = it },
                            onProviderSelected = { provider ->
                                selectedProviderId = provider.id
                                name = provider.displayName
                                if (provider.defaultBaseUrl != null) baseUrl = provider.defaultBaseUrl
                                toolCalling = provider.supportsTools
                                vision = provider.supportsVision
                                embeddings = provider.supportsEmbeddings
                                streaming = provider.supportsStreaming
                                if (provider.isSubscription) {
                                    modelId = ""
                                    enabledModelIds = emptySet()
                                }
                            },
                            onOpenSubscriptionAuth = { showSubscriptionAuthFlow = true },
                            onOpenModels = { showModelPicker = true },
                            onOpenAdvanced = { showAdvancedSettings = true },
                            onQuickSaveSubscription = { models -> quickSaveSubscription(models) }
                        )
                    }
                } else {
                    AgentStepContent(
                        activeStepKey = stepKey,
                        isNew = isNew,
                        config = config,
                        selectedProviderId = selectedProviderId,
                        selectedProvider = selectedProvider,
                        selectedSubscriptionAuth = selectedSubscriptionAuth,
                        stableModelCatalog = stableModelCatalog,
                        name = name,
                        onNameChange = { name = it },
                        baseUrl = baseUrl,
                        onBaseUrlChange = { baseUrl = it },
                        modelId = modelId,
                        onModelIdChange = { modelId = it },
                        key = key,
                        onKeyChange = { key = it },
                        showKey = showKey,
                        onToggleShowKey = { showKey = !showKey },
                        enabled = enabled,
                        onEnabledChange = { enabled = it },
                        maxTokensStr = maxTokensStr,
                        onMaxTokensChange = { v -> if (v.all { it.isDigit() }) maxTokensStr = v },
                        maxIterationsStr = maxIterationsStr,
                        onMaxIterationsChange = { v -> if (v.all { it.isDigit() }) maxIterationsStr = v },
                        toolCalling = toolCalling,
                        onToolCallingChange = { toolCalling = it },
                        vision = vision,
                        onVisionChange = { vision = it },
                        reasoning = reasoning,
                        onReasoningChange = { reasoning = it },
                        structuredOutput = structuredOutput,
                        onStructuredOutputChange = { structuredOutput = it },
                        embeddings = embeddings,
                        onEmbeddingsChange = { embeddings = it },
                        jsonMode = jsonMode,
                        onJsonModeChange = { jsonMode = it },
                        streaming = streaming,
                        onStreamingChange = { streaming = it },
                        enabledModelIds = enabledModelIds,
                        onEnabledModelIdsChange = { enabledModelIds = it },
                        onWizardCategoryChange = { wizardCategory = it },
                        onProviderSelected = { provider ->
                            selectedProviderId = provider.id
                            name = provider.displayName
                            if (provider.defaultBaseUrl != null) baseUrl = provider.defaultBaseUrl
                            toolCalling = provider.supportsTools
                            vision = provider.supportsVision
                            embeddings = provider.supportsEmbeddings
                            streaming = provider.supportsStreaming
                            if (provider.isSubscription) {
                                modelId = ""
                                enabledModelIds = emptySet()
                            }
                        },
                        onOpenSubscriptionAuth = { showSubscriptionAuthFlow = true },
                        onOpenModels = { showModelPicker = true },
                        onOpenAdvanced = { showAdvancedSettings = true },
                        onQuickSaveSubscription = { models -> quickSaveSubscription(models) }
                    )
                }
            }

            SheetHeader(
                title = when {
                    isNew && wizardCategory == null -> "New Agent"
                    isNew && selectedProvider == null -> if (wizardCategory == AgentWizardCategory.SUBSCRIPTION) "Subscription Provider" else "API Provider"
                    showSubscriptionAuthFlow -> subscriptionAuthTitle(selectedSubscriptionAuth)
                    showModelPicker -> "Models"
                    showAdvancedSettings -> "Advanced"
                    selectedProvider?.isSubscription == true -> selectedProvider.displayName
                    isNew -> "Configure Agent"
                    else -> "Edit Agent"
                },
                subtitle = when {
                    showModelPicker || showAdvancedSettings -> selectedProvider?.displayName
                    !showSubscriptionAuthFlow && selectedProvider?.isSubscription != true && selectedProvider != null -> selectedProvider.displayName
                    else -> null
                },
                gradient = gradients.modalTopScrim,
                onBack = if (showSubscriptionAuthFlow || showModelPicker || showAdvancedSettings || (isNew && (wizardCategory != null || selectedProvider != null))) ::goBack else null,
                onDismiss = { closeThen(onDismiss) },
                modifier = Modifier.align(Alignment.TopCenter),
                sheetState = sheetState
            )

            if (hasSaveFooter) {
                val footerProviderId = when {
                    stepKey.startsWith("api_") -> stepKey.removePrefix("api_").ifBlank { selectedProviderId ?: config.providerId.ifBlank { defaultApiProviderId() } }
                    stepKey.startsWith("advanced_") -> stepKey.removePrefix("advanced_").ifBlank { selectedProviderId ?: config.providerId.ifBlank { defaultApiProviderId() } }
                    stepKey.startsWith("models_") -> stepKey.removePrefix("models_").ifBlank { selectedProviderId ?: config.providerId.ifBlank { defaultApiProviderId() } }
                    else -> selectedProvider?.id.orEmpty()
                }
                val isModelsStep = stepKey.startsWith("models_")
                AgentSheetFooter(
                    primaryLabel = when {
                        isModelsStep -> "Done"
                        selectedProvider?.isSubscription == true -> "Done"
                        isNew -> "Save agent"
                        else -> "Save changes"
                    },
                    primaryEnabled = isModelsStep || selectedProvider?.isSubscription == true || apiFormIsValid(footerProviderId),
                    onPrimary = {
                        when {
                            isModelsStep -> showModelPicker = false
                            selectedProvider?.isSubscription == true -> saveSubscriptionProvider()
                            else -> saveApiProvider(footerProviderId)
                        }
                    },
                    onDelete = onDelete?.takeIf { !isModelsStep && !isNew && selectedProvider?.isSubscription != true }?.let { { showDeleteConfirm = true } },
                    gradient = gradients.bottomScrim,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
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
private fun AgentStepContent(
    activeStepKey: String,
    isNew: Boolean,
    config: AgentConfig,
    selectedProviderId: String?,
    selectedProvider: com.amaya.intelligence.data.remote.api.ProviderConfig?,
    selectedSubscriptionAuth: AgentSubscriptionAuthUi?,
    stableModelCatalog: List<ModelCatalogEntry>,
    name: String,
    onNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    modelId: String,
    onModelIdChange: (String) -> Unit,
    key: String,
    onKeyChange: (String) -> Unit,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    maxTokensStr: String,
    onMaxTokensChange: (String) -> Unit,
    maxIterationsStr: String,
    onMaxIterationsChange: (String) -> Unit,
    toolCalling: Boolean,
    onToolCallingChange: (Boolean) -> Unit,
    vision: Boolean,
    onVisionChange: (Boolean) -> Unit,
    reasoning: Boolean,
    onReasoningChange: (Boolean) -> Unit,
    structuredOutput: Boolean,
    onStructuredOutputChange: (Boolean) -> Unit,
    embeddings: Boolean,
    onEmbeddingsChange: (Boolean) -> Unit,
    jsonMode: Boolean,
    onJsonModeChange: (Boolean) -> Unit,
    streaming: Boolean,
    onStreamingChange: (Boolean) -> Unit,
    enabledModelIds: Set<String>,
    onEnabledModelIdsChange: (Set<String>) -> Unit,
    onWizardCategoryChange: (AgentWizardCategory) -> Unit,
    onProviderSelected: (com.amaya.intelligence.data.remote.api.ProviderConfig) -> Unit,
    onOpenSubscriptionAuth: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onQuickSaveSubscription: (Set<String>) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when {
            activeStepKey == "category" -> CategoryStep { onWizardCategoryChange(it) }
            activeStepKey.startsWith("provider_") -> ProviderStep(
                category = if (activeStepKey.endsWith(AgentWizardCategory.SUBSCRIPTION.name)) {
                    AgentWizardCategory.SUBSCRIPTION
                } else {
                    AgentWizardCategory.API_KEY
                },
                onSelect = { provider -> onProviderSelected(provider) }
            )
            activeStepKey.startsWith("subscription_") -> {
                val stepProviderId = activeStepKey.removePrefix("subscription_")
                val stepProvider = AmayaProviderRegistry.find(stepProviderId) ?: selectedProvider
                if (stepProvider != null) {
                    SubscriptionStep(
                        providerId = stepProvider.id,
                        providerName = stepProvider.displayName,
                        modelCatalog = stableModelCatalog.filter { it.providerId == stepProvider.id },
                        enabledModelIds = enabledModelIds,
                        onEnabledModelIds = { selectedModels ->
                            val clean = selectedModels.filter { it.isNotBlank() && it != stepProvider.id }.toSet()
                            onEnabledModelIdsChange(clean)
                            onQuickSaveSubscription(clean)
                        },
                        authUi = selectedSubscriptionAuth?.takeIf { it.providerId == stepProvider.id }?.copy(
                            onBrowserSignIn = { onOpenSubscriptionAuth() }
                        ),
                        onOpenModels = { onOpenModels() }
                    )
                }
            }
            activeStepKey.startsWith("models_") -> {
                val stepProviderId = activeStepKey.removePrefix("models_").ifBlank {
                    selectedProviderId ?: config.providerId.ifBlank { defaultApiProviderId() }
                }
                val stepProvider = AmayaProviderRegistry.find(stepProviderId)
                val stepCatalog = stableModelCatalog.filter { it.providerId == stepProviderId }
                AgentModelsSection(
                    modelCatalog = stepCatalog,
                    modelId = modelId,
                    enabledModelIds = enabledModelIds,
                    onModelId = { onModelIdChange(it) },
                    onEnabledModelIds = { next ->
                        onEnabledModelIdsChange(next)
                        if (stepProvider?.isSubscription == true) onQuickSaveSubscription(next)
                    },
                    useDefaultModel = stepProvider?.isSubscription != true
                )
            }
            activeStepKey.startsWith("auth_") -> SubscriptionAuthStepContent(selectedSubscriptionAuth)
            activeStepKey.startsWith("advanced_") -> {
                val stepProviderId = activeStepKey.removePrefix("advanced_").ifBlank {
                    selectedProviderId ?: config.providerId.ifBlank { defaultApiProviderId() }
                }
                AgentAdvancedSettingsSection(
                    providerId = stepProviderId,
                    baseUrl = baseUrl,
                    onBaseUrl = onBaseUrlChange,
                    maxTokensStr = maxTokensStr,
                    onMaxTokens = onMaxTokensChange,
                    maxIterationsStr = maxIterationsStr,
                    onMaxIterations = onMaxIterationsChange,
                    enabled = enabled,
                    onEnabled = onEnabledChange,
                    toolCalling = toolCalling,
                    onToolCalling = onToolCallingChange,
                    vision = vision,
                    onVision = onVisionChange,
                    reasoning = reasoning,
                    onReasoning = onReasoningChange,
                    structuredOutput = structuredOutput,
                    onStructuredOutput = onStructuredOutputChange,
                    embeddings = embeddings,
                    onEmbeddings = onEmbeddingsChange,
                    jsonMode = jsonMode,
                    onJsonMode = onJsonModeChange,
                    streaming = streaming,
                    onStreaming = onStreamingChange
                )
            }
            else -> {
                val stepProviderId = activeStepKey.removePrefix("api_").ifBlank {
                    selectedProviderId ?: config.providerId.ifBlank { defaultApiProviderId() }
                }
                ApiProviderForm(
                    providerId = stepProviderId,
                    name = name,
                    onName = onNameChange,
                    key = key,
                    onKey = onKeyChange,
                    showKey = showKey,
                    onToggleShowKey = onToggleShowKey,
                    modelId = modelId,
                    enabledModelIds = enabledModelIds,
                    onOpenModels = onOpenModels,
                    onAdvanced = onOpenAdvanced
                )
            }
        }
    }
}

@Composable
private fun CategoryStep(onSelect: (AgentWizardCategory) -> Unit) {
    PickerCard(Icons.Default.AccountCircle, "Subscription", "Account-based model providers", onClick = { onSelect(AgentWizardCategory.SUBSCRIPTION) })
    PickerCard(Icons.Default.Key, "API key", "Hosted, local, or custom providers", onClick = { onSelect(AgentWizardCategory.API_KEY) })
}

@Composable
private fun ProviderStep(category: AgentWizardCategory, onSelect: (com.amaya.intelligence.data.remote.api.ProviderConfig) -> Unit) {
    val providers = AmayaProviderRegistry.providers.filter {
        if (category == AgentWizardCategory.SUBSCRIPTION) {
            it.category == ProviderCategory.SUBSCRIPTION_LOGIN && it.supportsLocalRuntime
        } else {
            it.category != ProviderCategory.SUBSCRIPTION_LOGIN
        }
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

private fun providerSubtitle(provider: com.amaya.intelligence.data.remote.api.ProviderConfig): String = when {
    provider.category == ProviderCategory.SUBSCRIPTION_LOGIN -> "${provider.displayName} subscription"
    else -> provider.defaultBaseUrl ?: provider.apiFormat.name.lowercase().replace('_', ' ')
}

private fun defaultApiProviderId(): String = AmayaProviderRegistry.providers
    .firstOrNull { it.category == ProviderCategory.API_KEY }
    ?.id
    ?: AgentConfig().providerId

@Composable
private fun ApiProviderForm(
    providerId: String,
    name: String,
    onName: (String) -> Unit,
    key: String,
    onKey: (String) -> Unit,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    modelId: String,
    enabledModelIds: Set<String>,
    onOpenModels: () -> Unit,
    onAdvanced: () -> Unit
) {
    val provider = AmayaProviderRegistry.find(providerId)
    val apiKeyField = provider?.requiredFields.orEmpty().plus(provider?.optionalFields.orEmpty()).firstOrNull { it.key == "apiKey" }

    Field(name, onName, "Name", Icons.AutoMirrored.Filled.Label)
    if (apiKeyField != null && provider?.category != ProviderCategory.LOCAL) {
        SecretField(key, onKey, showKey, onToggleShowKey, apiKeyField.label)
    }
    AgentModelsSummaryRow(
        summary = modelsSummary(modelId, enabledModelIds),
        onClick = onOpenModels
    )
    NavigationRow(
        title = "Advanced",
        subtitle = "Base URL, limits, features",
        onClick = onAdvanced
    )
}

@Composable
private fun AgentAdvancedSettingsSection(
    providerId: String,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
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
    onStreaming: (Boolean) -> Unit
) {
    val provider = AmayaProviderRegistry.find(providerId)
    Field(baseUrl, onBaseUrl, "Base URL", Icons.Default.Link, placeholder = provider?.defaultBaseUrl ?: "https://example.com/v1")
    Field(maxTokensStr, onMaxTokens, "Max Tokens", Icons.Default.Tune, placeholder = AgentConfig().maxTokens.toString())
    Field(maxIterationsStr, onMaxIterations, "Max Iterations", Icons.Default.Repeat, placeholder = AgentConfig().maxIterations.toString())

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    Text("Capabilities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    CapabilitySwitch("Tool calling", toolCalling, onToolCalling)
    CapabilitySwitch("Vision", vision, onVision)
    CapabilitySwitch("Reasoning", reasoning, onReasoning)
    CapabilitySwitch("Structured output", structuredOutput, onStructuredOutput)
    CapabilitySwitch("Embeddings", embeddings, onEmbeddings)
    CapabilitySwitch("JSON mode", jsonMode, onJsonMode)
    CapabilitySwitch("Streaming", streaming, onStreaming)
    CapabilitySwitch("Enabled", enabled, onEnabled)
}

@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgentSheetFooter(
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onDelete: (() -> Unit)?,
    gradient: androidx.compose.ui.graphics.Brush,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(gradient)
            .ignoreNestedScrollForBottomSheet()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 34.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onDelete != null) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = (if (onDelete != null) Modifier.weight(1f) else Modifier.fillMaxWidth()).height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(primaryLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
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
private fun CapabilitySwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun modalStepDepth(stepKey: String): Int = when {
    stepKey == "category" -> 0
    stepKey.startsWith("provider_") -> 1
    stepKey.startsWith("subscription_") || stepKey.startsWith("api_") -> 2
    stepKey.startsWith("models_") -> 3
    stepKey.startsWith("advanced_") -> 3
    stepKey == "auth_methods" -> 3
    stepKey == "auth_wait" -> 4
    stepKey == "auth_device" -> 5
    else -> 2
}

private fun modalStepTransition(initialStepKey: String, targetStepKey: String): ContentTransform {
    val spec = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val forward = modalStepDepth(targetStepKey) >= modalStepDepth(initialStepKey)
    val enterOffset: (Int) -> Int = if (forward) ({ it / 5 }) else ({ -it / 5 })
    val exitOffset: (Int) -> Int = if (forward) ({ -it / 8 }) else ({ it / 8 })
    return (slideInHorizontally(spec, initialOffsetX = enterOffset) + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))) togetherWith
        (slideOutHorizontally(spec, targetOffsetX = exitOffset) + fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetHeader(
    title: String,
    subtitle: String? = null,
    gradient: androidx.compose.ui.graphics.Brush,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier,
    sheetState: SheetState
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(gradient)
            .verticalScroll(rememberScrollState())
    ) {
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
