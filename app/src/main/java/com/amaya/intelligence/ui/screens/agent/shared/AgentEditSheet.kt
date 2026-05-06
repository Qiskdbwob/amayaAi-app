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
    subscriptionAuth: AgentSubscriptionAuthUi? = null,
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
    val selectedProvider = AmayaProviderRegistry.find(selectedProviderId)
    val stepKey = when {
        isNew && wizardCategory == null -> "category"
        isNew && selectedProvider == null -> "provider_${wizardCategory?.name.orEmpty()}"
        showSubscriptionAuthFlow && selectedProvider?.id == subscriptionAuth?.providerId -> subscriptionAuthStepKey(subscriptionAuth)
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
            showSubscriptionAuthFlow && subscriptionAuth?.step !is SubscriptionAuthStep.Methods && subscriptionAuth?.step !is SubscriptionAuthStep.Error -> subscriptionAuth?.onCancel?.invoke()
            showSubscriptionAuthFlow -> showSubscriptionAuthFlow = false
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
            enabled = subscriptionAuth?.let { auth -> provider.id != auth.providerId || auth.authenticated } ?: true,
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

    LaunchedEffect(subscriptionAuth?.authenticated) {
        if (subscriptionAuth?.authenticated == true) showSubscriptionAuthFlow = false
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
                    transitionSpec = { modalStepTransition(initialState, targetState) },
                    label = "agent_step_transition"
                ) { activeStepKey ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        when {
                            activeStepKey == "category" -> CategoryStep { wizardCategory = it }
                            activeStepKey.startsWith("provider_") -> ProviderStep(
                                category = if (activeStepKey.endsWith(AgentWizardCategory.SUBSCRIPTION.name)) {
                                    AgentWizardCategory.SUBSCRIPTION
                                } else {
                                    AgentWizardCategory.API_KEY
                                },
                                onSelect = { provider ->
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
                                    } else if (modelId.isBlank()) {
                                        modelId = stableModelCatalog.firstOrNull { it.providerId == provider.id }?.modelId
                                            ?: KnownModelCatalog.entries.firstOrNull { it.providerId == provider.id }?.modelId.orEmpty()
                                    }
                                }
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
                                            enabledModelIds = clean
                                            quickSaveSubscription(clean)
                                        },
                                        authUi = subscriptionAuth?.takeIf { it.providerId == stepProvider.id }?.copy(
                                            onBrowserSignIn = { showSubscriptionAuthFlow = true }
                                        ),
                                        onDelete = onDelete?.let { { showDeleteConfirm = true } }
                                    )
                                }
                            }
                            activeStepKey.startsWith("auth_") -> SubscriptionAuthStepContent(subscriptionAuth)
                            else -> {
                                val stepProviderId = activeStepKey.removePrefix("api_").ifBlank {
                                    selectedProviderId ?: config.providerId.ifBlank { defaultApiProviderId() }
                                }
                                ApiProviderForm(
                                    providerId = stepProviderId,
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
                                    modelCatalog = stableModelCatalog.filter { it.providerId == stepProviderId },
                                    enabledModelIds = enabledModelIds,
                                    onEnabledModelIds = { enabledModelIds = it },
                                    onDelete = onDelete?.let { { showDeleteConfirm = true } },
                                    onSave = {
                                        val provider = AmayaProviderRegistry.find(stepProviderId)
                                        closeThen {
                                            onSave(
                                                config.copy(
                                                    name = name.trim(),
                                                    providerType = AmayaProviderRegistry.legacyProviderType(stepProviderId).name,
                                                    providerId = stepProviderId,
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
            }

            SheetHeader(
                title = when {
                    isNew && wizardCategory == null -> "New Agent"
                    isNew && selectedProvider == null -> if (wizardCategory == AgentWizardCategory.SUBSCRIPTION) "Subscription Provider" else "API Provider"
                    showSubscriptionAuthFlow -> subscriptionAuthTitle(subscriptionAuth)
                    selectedProvider?.isSubscription == true -> selectedProvider.displayName
                    isNew -> "Configure Agent"
                    else -> "Edit Agent"
                },
                gradient = gradients.modalTopScrim,
                onBack = if (showSubscriptionAuthFlow || (isNew && (wizardCategory != null || selectedProvider != null))) ::goBack else null,
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
    Field(maxTokensStr, onMaxTokens, "Max Tokens", Icons.Default.Tune, placeholder = AgentConfig().maxTokens.toString())
    Field(maxIterationsStr, onMaxIterations, "Max Iterations", Icons.Default.Repeat, placeholder = AgentConfig().maxIterations.toString())

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

private fun modalStepDepth(stepKey: String): Int = when {
    stepKey == "category" -> 0
    stepKey.startsWith("provider_") -> 1
    stepKey.startsWith("subscription_") || stepKey.startsWith("api_") -> 2
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
