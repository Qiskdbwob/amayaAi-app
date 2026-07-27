package com.amaya.intelligence.ui.screens.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.data.remote.api.AmayaProviderRegistry
import com.amaya.intelligence.data.remote.api.ConfiguredModel
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import com.amaya.intelligence.ui.viewmodels.models.ManageModelsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    connectionId: String,
    onNavigateBack: () -> Unit,
    viewModel: ManageModelsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val operation by viewModel.operation.collectAsState()
    val connection = settings.connections.firstOrNull { it.id == connectionId }
    val colors = rememberModelSettingsColors()

    // UI State
    var availableModels by remember(connectionId) { mutableStateOf<List<ConfiguredModel>>(emptyList()) }
    var selectedIds by remember(connectionId) { mutableStateOf<Set<String>>(emptySet()) }
    var initialSelectedIds by remember(connectionId) { mutableStateOf<Set<String>>(emptySet()) }
    var hasCredential by remember(connectionId) { mutableStateOf(false) }
    var subscriptionAuthenticated by remember(connectionId) { mutableStateOf(false) }
    var editingModel by remember(connectionId) { mutableStateOf<ConfiguredModel?>(null) }

    // Init connection data
    LaunchedEffect(connection) {
        if (connection != null) {
            hasCredential = viewModel.hasCredential(connection.id)
            val isSubscription = AmayaProviderRegistry.find(connection.providerId)?.isSubscription == true
            subscriptionAuthenticated = hasCredential || !isSubscription
            selectedIds = connection.visibleModels.filter { it.enabled }.map { it.id }.toSet()
            initialSelectedIds = selectedIds
            availableModels = connection.visibleModels
            if (!isSubscription) {
                viewModel.refresh(connection) { refreshed ->
                    availableModels = (connection.visibleModels + refreshed).distinctBy { it.id }
                }
            }
        }
    }

    val hasUnsavedChanges = selectedIds != initialSelectedIds
    var query by remember { mutableStateOf("") }

    val filteredModels = remember(availableModels, query) {
        val value = query.trim().lowercase()
        if (value.isBlank()) availableModels else availableModels.filter {
            it.id.lowercase().contains(value) || it.displayName.lowercase().contains(value)
        }
    }

    if (connection == null) {
        if (settings.connections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.primaryText)
            }
        } else {
            LaunchedEffect(Unit) { onNavigateBack() }
        }
        return
    }

    val isSubscription = AmayaProviderRegistry.find(connection.providerId)?.isSubscription == true

    Scaffold(
        containerColor = colors.groupedBackground,
        topBar = {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .align(Alignment.TopCenter)
                        .background(LocalAmayaGradients.current.topScrim)
                )
                TopAppBar(
                    title = {
                        Text(
                            connection.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primaryText,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    },
                    navigationIcon = { SettingsBackButton(onNavigateBack) },
                    actions = {
                        TextButton(
                            onClick = {
                                val savedModels = availableModels.filter { it.id in selectedIds }
                                viewModel.saveVisibleModels(connection.id, savedModels) {
                                    initialSelectedIds = selectedIds
                                }
                            },
                            enabled = hasUnsavedChanges && !operation.loading
                        ) {
                            if (operation.loading) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Save", fontWeight = FontWeight.Bold, color = if (hasUnsavedChanges) MaterialTheme.colorScheme.primary else colors.secondaryText)
                            }
                        }
                    },
                    modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), start = 20.dp, end = 20.dp, bottom = 80.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item {
                Spacer(Modifier.statusBarsPadding().height(52.dp))
            }
            item {
                ModelSection("Connection", colors) {
                    ModelSettingsRow(
                        icon = if (!isSubscription || subscriptionAuthenticated) Icons.Default.CheckCircle else Icons.Default.Error,
                        title = if (isSubscription && !subscriptionAuthenticated) "Sign In Required" else "Configured",
                        subtitle = AmayaProviderRegistry.displayName(connection.providerId),
                        colors = colors,
                        onClick = null
                    )
                    if (!isSubscription) {
                        ModelDivider(colors)
                        ModelSettingsRow(
                            Icons.Default.Key,
                            "Credential",
                            if (hasCredential) "API key saved" else "No API key",
                            colors,
                            onClick = null
                        )
                        ModelDivider(colors)
                        ModelSettingsRow(
                            Icons.Default.Refresh,
                            "Refresh Models",
                            if (operation.loading) "Loading…" else "Load from provider",
                            colors,
                            onClick = {
                                viewModel.refresh(connection) { refreshed ->
                                    availableModels = (availableModels + refreshed).distinctBy { it.id }
                                }
                            }
                        )
                    }
                }
            }

            operation.error?.let { error ->
                item { InlineError(error) }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = colors.secondaryText) },
                    trailingIcon = {
                        if (query.isNotBlank()) IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, "Clear search", tint = colors.secondaryText)
                        }
                    },
                    placeholder = { Text("Search models…", color = colors.secondaryText) },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.groupSurface,
                        unfocusedContainerColor = colors.groupSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = colors.primaryText,
                        unfocusedTextColor = colors.primaryText
                    )
                )
            }

            item {
                Text("Models", style = MaterialTheme.typography.labelMedium, color = colors.headerText, modifier = Modifier.padding(start = 16.dp))
                Spacer(Modifier.height(7.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.groupSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        if (filteredModels.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                if (operation.loading) {
                                    CircularProgressIndicator(Modifier.size(28.dp))
                                } else {
                                    Text("No models found", color = colors.secondaryText)
                                }
                            }
                        } else {
                            filteredModels.forEachIndexed { index, model ->
                                ModernModelToggleRow(
                                    model = model,
                                    providerId = connection.providerId,
                                    checked = model.id in selectedIds,
                                    colors = colors,
                                    onToggle = {
                                        selectedIds = if (model.id in selectedIds) {
                                            selectedIds - model.id
                                        } else {
                                            selectedIds + model.id
                                        }
                                    },
                                    onConfigure = { editingModel = model }
                                )
                                if (index < filteredModels.lastIndex) ModelDivider(colors)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                Text("Danger Zone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp))
                Spacer(Modifier.height(7.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.groupSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.deleteConnection(connection.id) {
                                    onNavigateBack()
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Delete Provider", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    editingModel?.let { model ->
        ModelConfigurationSheet(
            model = model,
            operation = operation,
            onDismiss = { editingModel = null },
            onSave = { updated ->
                viewModel.saveModel(connection.id, updated) {
                    availableModels = availableModels.map { if (it.id == updated.id) updated else it }
                    editingModel = null
                }
            }
        )
    }
}

@Composable
private fun ModelConfigurationSheet(
    model: ConfiguredModel,
    operation: ManageModelsViewModel.OperationState,
    onDismiss: () -> Unit,
    onSave: (ConfiguredModel) -> Unit
) {
    var contextWindow by remember(model.id, model.contextWindowTokens) { mutableStateOf(model.contextWindowTokens?.toString().orEmpty()) }
    var maxInput by remember(model.id, model.maxInputTokens) { mutableStateOf(model.maxInputTokens?.toString().orEmpty()) }
    var maxOutput by remember(model.id, model.maxOutputTokens) { mutableStateOf(model.maxOutputTokens?.toString().orEmpty()) }
    var supportsTools by remember(model.id) { mutableStateOf(model.supportsTools) }
    var supportsImages by remember(model.id) { mutableStateOf(model.supportsImages) }
    val candidate = runCatching {
        model.copy(
            contextWindowTokens = contextWindow.toPositiveTokenCount(),
            maxInputTokens = maxInput.toPositiveTokenCount(),
            maxOutputTokens = maxOutput.toPositiveTokenCount(),
            supportsTools = supportsTools,
            supportsImages = supportsImages
        ).also { com.amaya.intelligence.data.remote.api.normalizeConfiguredModel(it) }
    }

    com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet(
        onDismissRequest = onDismiss,
        title = model.displayName
    ) {
        Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TokenField("Context window", contextWindow) { contextWindow = it }
        TokenField("Max input", maxInput) { maxInput = it }
        TokenField("Max output", maxOutput) { maxOutput = it }
        ModelCapabilityRow("Tool use", supportsTools) { supportsTools = it }
        ModelCapabilityRow("Image input", supportsImages) { supportsImages = it }
        candidate.exceptionOrNull()?.message?.let { message -> InlineError(message) }
        Button(
            onClick = { candidate.getOrNull()?.let(onSave) },
            enabled = candidate.isSuccess && !operation.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (operation.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Save")
        }
    }
}

@Composable
private fun TokenField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.all(Char::isDigit)) onValueChange(input) },
        label = { Text(label) },
        placeholder = { Text("Auto") },
        supportingText = { Text("Leave blank for provider default") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
    )
}

@Composable
private fun ModelCapabilityRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun String.toPositiveTokenCount(): Int? = trim().takeIf(String::isNotBlank)?.toIntOrNull()

@Composable
fun ModernModelToggleRow(
    model: ConfiguredModel,
    providerId: String,
    checked: Boolean,
    colors: ModelSettingsColors,
    onToggle: () -> Unit,
    onConfigure: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConfigure)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(colors.iconBackground),
            contentAlignment = Alignment.Center
        ) {
            com.amaya.intelligence.ui.components.shared.ModelLeadingIcon(
                modelId = model.id,
                providerId = providerId,
                modifier = Modifier.size(17.dp),
                tint = if (checked) MaterialTheme.colorScheme.primary else colors.iconTint
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                model.displayName,
                color = colors.primaryText,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
            if (model.displayName != model.id) {
                Spacer(Modifier.height(2.dp))
                Text(
                    model.id,
                    color = colors.secondaryText,
                    fontSize = 13.sp
                )
            }
        }
        Icon(Icons.Default.ChevronRight, "Configure ${model.displayName}", tint = colors.secondaryText)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
