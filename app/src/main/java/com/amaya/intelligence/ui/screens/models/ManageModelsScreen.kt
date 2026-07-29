package com.amaya.intelligence.ui.screens.models


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.data.remote.api.*

import com.amaya.intelligence.domain.models.ModelOption
import com.amaya.intelligence.ui.components.shared.ModelLeadingIcon
import com.amaya.intelligence.ui.components.shared.SettingsBackButton

import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import com.amaya.intelligence.ui.viewmodels.models.ManageModelsViewModel

private enum class ModelsSheet { PROVIDERS, SETUP, SELECT_MODEL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageModelsScreen(
    codexAuthManager: CodexAuthManager,
    onNavigateBack: () -> Unit,
    onNavigateToProvider: (String) -> Unit,
    viewModel: ManageModelsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val operation by viewModel.operation.collectAsState()
    val codexState by codexAuthManager.authState.collectAsState()
    val context = LocalContext.current
    val colors = rememberModelSettingsColors()

    var sheet by remember { mutableStateOf<ModelsSheet?>(null) }
    var setupProvider by remember { mutableStateOf<ProviderConfig?>(null) }


    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0.dp)) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize().background(colors.groupedBackground)) {
            ConnectionsOverview(
                settings = settings,
                colors = colors,
                onConnection = { onNavigateToProvider(it.id) },
                onSelectModel = { sheet = ModelsSheet.SELECT_MODEL },
                onAddProvider = { sheet = ModelsSheet.PROVIDERS }
            )

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
                        "Manage Models",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primaryText,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                },
                navigationIcon = {
                    SettingsBackButton(onClick = onNavigateBack)
                },
                actions = {
                    com.amaya.intelligence.ui.components.shared.AmayaTopBarButton(
                        icon = Icons.Default.Add,
                        onClick = { sheet = ModelsSheet.PROVIDERS },
                        contentDescription = "Add provider",
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp),
                windowInsets = WindowInsets(0.dp)
            )
        }
    }

    when (sheet) {
        ModelsSheet.PROVIDERS -> ProviderPickerSheet(
            onDismiss = { sheet = null },
            onSelect = { provider ->
                viewModel.clearOperation()
                setupProvider = provider
                sheet = ModelsSheet.SETUP
            }
        )
        ModelsSheet.SETUP -> setupProvider?.let { provider ->
            ProviderSetupSheet(
                provider = provider,
                operation = operation,
                codexAuthenticated = codexAuthManager.isAuthenticated(),
                codexAccount = codexAuthManager.getAccountEmail(),
                codexState = codexState,
                onSignIn = { codexAuthManager.startLocalServerLogin(context) },
                onCancelSignIn = codexAuthManager::cancel,
                onConnect = { name, baseUrl, apiKey ->
                    viewModel.connect(provider, name, baseUrl, apiKey) { saved, _ ->
                        onNavigateToProvider(saved.id)
                        sheet = null
                    }
                },
                onSaveWithoutModelList = { name, baseUrl, apiKey ->
                    viewModel.saveWithoutModelList(provider, name, baseUrl, apiKey) { saved ->
                        onNavigateToProvider(saved.id)
                        sheet = null
                    }
                },
                onSaveSubscription = {
                    viewModel.saveSubscriptionConnection { saved ->
                        onNavigateToProvider(saved.id)
                        sheet = null
                    }
                },
                onBack = { sheet = ModelsSheet.PROVIDERS },
                onDismiss = { sheet = null }
            )
        }
        ModelsSheet.SELECT_MODEL -> SelectModelSheet(
            settings = settings,
            onSelect = { option ->
                viewModel.selectModel(option.connectionId, option.modelId) { sheet = null }
            },
            onDismiss = { sheet = null }
        )
        null -> Unit
    }
}

@Composable
private fun ConnectionsOverview(
    settings: AiSettings,
    colors: ModelSettingsColors,
    onConnection: (ProviderConnection) -> Unit,
    onSelectModel: () -> Unit,
    onAddProvider: () -> Unit
) {
    val active = settings.activeSelection?.let { selection ->
        settings.connections.firstOrNull { it.id == selection.connectionId }
            ?.let { connection -> connection to connection.visibleModels.firstOrNull { it.id == selection.modelId && it.enabled } }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Spacer(Modifier.statusBarsPadding().height(52.dp))
        }
        if (settings.connections.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillParentMaxHeight(0.75f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.SmartToy,
                        null,
                        modifier = Modifier.size(56.dp),
                        tint = colors.secondaryText.copy(alpha = 0.45f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No Models Configured", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add an API or subscription provider to start using AI models.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryText
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onAddProvider) { Text("Add Provider") }
                }
            }
        } else {
            item {
                ModelSection("Active Model", colors) {
                    ModelSettingsRow(
                        icon = Icons.Default.Psychology,
                        title = active?.second?.displayName ?: "Select Model",
                        subtitle = active?.first?.name ?: "Choose a model for chat",
                        colors = colors,
                        onClick = onSelectModel,
                        modelId = active?.second?.id,
                        providerId = active?.first?.providerId
                    )
                }
            }
            item {
                ModelSection("Providers", colors) {
                    settings.connections.forEachIndexed { index, connection ->
                        ModelSettingsRow(
                            icon = if (AmayaProviderRegistry.find(connection.providerId)?.isSubscription == true) Icons.Default.AccountCircle else Icons.Default.Api,
                            title = connection.name,
                            subtitle = "${AmayaProviderRegistry.displayName(connection.providerId)} · ${connection.visibleModels.size} ${if (connection.visibleModels.size == 1) "model" else "models"}",
                            colors = colors,
                            onClick = { onConnection(connection) }
                        )
                        if (index < settings.connections.lastIndex) ModelDivider(colors)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPickerSheet(onDismiss: () -> Unit, onSelect: (ProviderConfig) -> Unit) {
    com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Select Provider",
        scrollable = false
    ) {
        LazyColumn(contentPadding = com.amaya.intelligence.ui.components.shared.StandardModalSheetDefaults.ContentPadding, modifier = Modifier.fillMaxWidth().heightIn(max = com.amaya.intelligence.ui.components.shared.StandardModalSheetDefaults.MaxHeight)) {
            ProviderCategory.entries.forEach { category ->
                val categoryProviders = AmayaProviderRegistry.providers.filter { it.category == category }
                if (categoryProviders.isNotEmpty()) {
                    item {
                        Text(providerCategoryLabel(category), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp))
                    }
                    items(categoryProviders) { provider ->
                        Surface(onClick = { dismiss { onSelect(provider) } }, shape = RoundedCornerShape(16.dp), color = Color.Transparent) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (provider.isSubscription) Icons.Default.AccountCircle else Icons.Default.Api, null)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(provider.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(if (provider.isSubscription) "Sign in with your account" else if (provider.isCustom) "Custom endpoint" else "API key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSetupSheet(
    provider: ProviderConfig,
    operation: ManageModelsViewModel.OperationState,
    codexAuthenticated: Boolean,
    codexAccount: String?,
    codexState: CodexAuthState,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onConnect: (String, String, String) -> Unit,
    onSaveWithoutModelList: (String, String, String) -> Unit,
    onSaveSubscription: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(provider.id) { mutableStateOf(provider.displayName) }
    var baseUrl by remember(provider.id) { mutableStateOf("") }
    var apiKey by remember(provider.id) { mutableStateOf("") }
    var showKey by remember(provider.id) { mutableStateOf(false) }

    com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet(
        onDismissRequest = onDismiss,
        title = provider.displayName,
        actions = {
            com.amaya.intelligence.ui.components.shared.AmayaTopBarButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = { dismiss(onBack) },
                contentDescription = "Back"
            )
        }
    ) {
        if (provider.isSubscription) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (codexAuthenticated) Icons.Default.CheckCircle else Icons.Default.AccountCircle, null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (codexAuthenticated) "Connected" else "Not Connected", fontWeight = FontWeight.SemiBold)
                        Text(codexAccount ?: subscriptionStateLabel(codexState), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            operation.error?.let { InlineError(it) }
            when {
                codexAuthenticated -> Button(onClick = onSaveSubscription, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Continue") }
                codexState is CodexAuthState.Starting || codexState is CodexAuthState.WaitingForBrowser || codexState is CodexAuthState.ExchangingToken -> {
                    Button(onClick = onCancelSignIn, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Cancel Sign In") }
                }
                else -> Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Sign In With OpenAI") }
            }
        } else {
            OutlinedTextField(name, { name = it }, label = { Text("Connection Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (provider.isCustom) {
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, placeholder = { Text("https://example.com/v1") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            } else {
                Text("Requests use ${provider.defaultBaseUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                apiKey,
                { apiKey = it },
                label = { Text(if (provider.credentialRequired) "API Key" else "API Key (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "Hide API key" else "Show API key")
                    }
                }
            )
            operation.error?.let {
                InlineError(it)
                if (provider.isCustom) {
                    Text(
                        "If this endpoint does not expose a model list, save it now, then add model IDs manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { onSaveWithoutModelList(name, baseUrl, apiKey) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        enabled = !operation.loading && baseUrl.isNotBlank()
                    ) { Text("Save Without Model List") }
                }
            }
            Button(
                onClick = { onConnect(name, baseUrl, apiKey) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                enabled = !operation.loading && (!provider.credentialRequired || apiKey.isNotBlank()) && (!provider.isCustom || baseUrl.isNotBlank())
            ) {
                if (operation.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Connect")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectModelSheet(settings: AiSettings, onSelect: (ModelOption) -> Unit, onDismiss: () -> Unit) {
    val colors = rememberModelSettingsColors()
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val allOptions = remember(settings.connections) {
        settings.connections.flatMap { connection ->
            connection.visibleModels.mapNotNull { model ->
                com.amaya.intelligence.impl.common.mappers.ModelUiMapper.mapConnectionModel(connection, model)
            }
        }
    }
    val options = remember(allOptions, query) {
        val value = query.trim().lowercase()
        if (value.isBlank()) allOptions else allOptions.filter {
            it.name.lowercase().contains(value) ||
                it.modelId.lowercase().contains(value) ||
                it.providerName.lowercase().contains(value)
        }
    }
    com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Select Model",
        scrollable = false,
        actions = {
            com.amaya.intelligence.ui.components.shared.AmayaTopBarButton(
                icon = Icons.Default.Search,
                onClick = { showSearch = !showSearch },
                contentDescription = "Search"
            )
        }
    ) {
        val closeWithSelection: (ModelOption) -> Unit = { option -> dismiss { onSelect(option) } }
        LazyColumn(contentPadding = com.amaya.intelligence.ui.components.shared.StandardModalSheetDefaults.ContentPadding, modifier = Modifier.fillMaxWidth().heightIn(max = com.amaya.intelligence.ui.components.shared.StandardModalSheetDefaults.MaxHeight)) {
            if (showSearch) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotBlank()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        },
                        placeholder = { Text("Search models…") },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
            if (allOptions.isEmpty()) {
                item { Text("No models shown in chat", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (options.isEmpty()) {
                item { Text("No models found", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                settings.connections.forEach { connection ->
                    val connectionModels = options.filter { it.connectionId == connection.id }
                    if (connectionModels.isNotEmpty()) {
                        item(key = "header_${connection.id}") {
                            Text(connection.name.uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.headerText, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
                        }
                        item(key = "group_${connection.id}") {
                            Surface(shape = RoundedCornerShape(16.dp), color = colors.groupSurface, border = BorderStroke(0.7.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    connectionModels.forEachIndexed { index, option ->
                                        val isActive = option.id == settings.activeSelection?.key
                                        Surface(onClick = { closeWithSelection(option) }, color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent) {
                                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                                        .background(if (isActive) MaterialTheme.colorScheme.primary else colors.iconBackground),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    ModelLeadingIcon(
                                                        modelId = option.modelId,
                                                        providerId = option.providerId,
                                                        iconType = option.iconType,
                                                        modifier = Modifier.size(17.dp),
                                                        tint = if (isActive) MaterialTheme.colorScheme.onPrimary else colors.iconTint
                                                    )
                                                }
                                                Spacer(Modifier.width(12.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        option.name,
                                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                                        color = if (isActive) MaterialTheme.colorScheme.primary else colors.primaryText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (option.name != option.modelId) {
                                                        Text(option.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                            }
                                        }
                                        if (index < connectionModels.lastIndex) {
                                            HorizontalDivider(color = colors.separator)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}



private fun providerCategoryLabel(category: ProviderCategory): String = when (category) {
    ProviderCategory.SUBSCRIPTION -> "SUBSCRIPTION"
    ProviderCategory.API -> "API KEY"
    ProviderCategory.CUSTOM -> "CUSTOM ENDPOINT"
}

private fun subscriptionStateLabel(state: CodexAuthState): String = when (state) {
    is CodexAuthState.Starting -> "Opening browser…"
    is CodexAuthState.WaitingForBrowser -> "Waiting for browser…"
    is CodexAuthState.ExchangingToken -> "Finishing sign in…"
    is CodexAuthState.Error -> state.message
    else -> "Sign in to continue"
}
