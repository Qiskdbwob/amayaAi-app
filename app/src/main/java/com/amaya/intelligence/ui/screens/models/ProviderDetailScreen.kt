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
import com.amaya.intelligence.ui.screens.amaya.AmayaGroupedSettingsTokens
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
    val colors = com.amaya.intelligence.ui.screens.amaya.iosAmayaColors()

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
            availableModels = connection.visibleModels.sortedBy { it.displayName.lowercase() }
            if (!isSubscription) {
                viewModel.refresh(connection) { refreshed ->
                    availableModels = mergeConfiguredModels(connection.visibleModels, refreshed)
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
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize().background(colors.groupedBackground)) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = AmayaGroupedSettingsTokens.contentHorizontalPadding,
                    end = AmayaGroupedSettingsTokens.contentHorizontalPadding,
                    bottom = AmayaGroupedSettingsTokens.screenContentBottomSpacer
                ),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AmayaGroupedSettingsTokens.sectionSpacing)
            ) {
                item {
                    Spacer(
                        Modifier
                            .statusBarsPadding()
                            .height(AmayaGroupedSettingsTokens.screenContentTopSpacer)
                    )
                }

                item {
                    com.amaya.intelligence.ui.screens.amaya.AmayaSection("Connection") {
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
                                        availableModels = mergeConfiguredModels(availableModels, refreshed)
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
                    com.amaya.intelligence.ui.screens.amaya.AmayaSection("Models") {
                        if (filteredModels.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(AmayaGroupedSettingsTokens.emptyStateContentPadding),
                                contentAlignment = Alignment.Center
                            ) {
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

                item {
                    Spacer(Modifier.height(AmayaGroupedSettingsTokens.inlineTextSpacing))
                    com.amaya.intelligence.ui.screens.amaya.AmayaSection("Danger Zone") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.deleteConnection(connection.id) {
                                        onNavigateBack()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Delete Provider", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }
                }
            }

            com.amaya.intelligence.ui.screens.amaya.AmayaTopScrim(
                Modifier.align(Alignment.TopCenter)
            )
            TopAppBar(
                title = {
                    Text(
                        connection.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primaryText,
                        modifier = Modifier.padding(start = AmayaGroupedSettingsTokens.topBarTitleStartPadding)
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
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = AmayaGroupedSettingsTokens.topBarHorizontalPadding),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                windowInsets = WindowInsets(0.dp)
            )
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
        com.amaya.intelligence.ui.screens.amaya.AmayaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun String.toPositiveTokenCount(): Int? = trim().takeIf(String::isNotBlank)?.toIntOrNull()

@Composable
fun ModernModelToggleRow(
    model: ConfiguredModel,
    providerId: String,
    checked: Boolean,
    colors: com.amaya.intelligence.ui.screens.amaya.IosAmayaColors,
    onToggle: () -> Unit,
    onConfigure: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConfigure)
            .padding(
                horizontal = AmayaGroupedSettingsTokens.rowHorizontalPadding,
                vertical = AmayaGroupedSettingsTokens.rowVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(AmayaGroupedSettingsTokens.rowIconSize)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(colors.iconBackground),
            contentAlignment = Alignment.Center
        ) {
            com.amaya.intelligence.ui.components.shared.ModelLeadingIcon(
                modelId = model.id,
                providerId = providerId,
                modifier = Modifier.size(AmayaGroupedSettingsTokens.rowIconGlyphSize),
                tint = colors.iconTint
            )
        }
        Spacer(Modifier.width(AmayaGroupedSettingsTokens.rowIconTextGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                model.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                ),
                color = colors.primaryText
            )
            if (model.displayName != model.id) {
                Spacer(Modifier.height(AmayaGroupedSettingsTokens.inlineTextSpacing))
                Text(
                    model.id,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp
                    ),
                    color = colors.secondaryText,
                    maxLines = 1
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            "Configure ${model.displayName}",
            tint = colors.secondaryText,
            modifier = Modifier.size(AmayaGroupedSettingsTokens.rowChevronSize)
        )
        Spacer(Modifier.width(AmayaGroupedSettingsTokens.rowHorizontalPadding))
        com.amaya.intelligence.ui.screens.amaya.AmayaSwitch(checked = checked, onCheckedChange = { onToggle() })
    }
}

internal fun mergeConfiguredModels(
    savedModels: List<ConfiguredModel>,
    refreshedModels: List<ConfiguredModel>
): List<ConfiguredModel> =
    (refreshedModels + savedModels)
        .associateBy { it.id }
        .values
        .sortedBy { it.displayName.lowercase() }
