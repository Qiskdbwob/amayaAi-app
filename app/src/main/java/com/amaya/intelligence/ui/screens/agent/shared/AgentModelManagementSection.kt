package com.amaya.intelligence.ui.screens.agent.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.remote.api.ModelCatalogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MinModelsLoadingMs = 280L

@Composable
internal fun SubscriptionStep(
    providerId: String,
    providerName: String,
    modelCatalog: List<ModelCatalogEntry>,
    enabledModelIds: Set<String>,
    onEnabledModelIds: (Set<String>) -> Unit,
    authUi: AgentSubscriptionAuthUi?,
    onOpenModels: () -> Unit,
    maxIterationsStr: String,
    onMaxIterationsChange: (String) -> Unit,
    maxIterationsPlaceholder: String
) {
    val sortedModels = remember(modelCatalog) { modelCatalog.distinctBy { it.modelId }.sortedBy { it.displayName.lowercase() } }
    val catalogModelIds = remember(sortedModels) { sortedModels.map { it.modelId }.toSet() }
    val normalizedEnabledModelIds = remember(enabledModelIds, catalogModelIds) {
        enabledModelIds.filter { it.isNotBlank() && (catalogModelIds.isEmpty() || it in catalogModelIds) }.distinct().toSet()
    }

    SubscriptionConnectionCard(authUi = authUi)
    AgentModelsSummaryRow(
        title = "Models",
        summary = if (authUi?.authenticated == false) "Sign in first" else modelsSummary(defaultModelId = "", enabledModelIds = normalizedEnabledModelIds),
        onClick = onOpenModels,
        enabled = authUi?.authenticated != false
    )
    OutlinedTextField(
        value = maxIterationsStr,
        onValueChange = { v -> if (v.all { it.isDigit() }) onMaxIterationsChange(v) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        label = { Text("Max Iterations") },
        placeholder = { Text(maxIterationsPlaceholder) },
        leadingIcon = { Icon(Icons.Default.Repeat, null, modifier = Modifier.size(18.dp)) }
    )
}

@Composable
internal fun AgentModelsSummaryRow(
    title: String = "Models",
    summary: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AgentModelsSection(
    modelCatalog: List<ModelCatalogEntry>,
    modelId: String,
    enabledModelIds: Set<String>,
    onModelId: (String) -> Unit,
    onEnabledModelIds: (Set<String>) -> Unit,
    useDefaultModel: Boolean
) {
    var query by remember { mutableStateOf("") }
    var customModelId by remember { mutableStateOf("") }
    var preparedCatalog by remember { mutableStateOf<List<AgentModelRowEntry>?>(null) }

    LaunchedEffect(modelCatalog) {
        preparedCatalog = null
        val catalogSnapshot = modelCatalog.toList()
        delay(MinModelsLoadingMs)
        preparedCatalog = withContext(Dispatchers.Default) {
            catalogSnapshot
                .distinctBy { it.modelId }
                .sortedBy { it.displayName.lowercase() }
                .map { AgentModelRowEntry(it.modelId, it.displayName, custom = false) }
        }
    }

    val readyCatalog = preparedCatalog
    if (readyCatalog == null) {
        AgentModelsLoadingSection()
        return
    }

    val catalogIds = remember(readyCatalog) { readyCatalog.map { it.modelId }.toSet() }
    val selectedIds = remember(modelId, enabledModelIds) { (enabledModelIds + modelId).filter { it.isNotBlank() }.toSet() }
    val customIds = remember(selectedIds, catalogIds) {
        selectedIds.filter { it !in catalogIds }.distinct().sorted()
    }
    val allEntries = remember(readyCatalog, customIds) {
        readyCatalog + customIds.map { AgentModelRowEntry(it, it, custom = true) }
    }
    val visibleEntries = remember(allEntries, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) allEntries else allEntries.filter {
            it.displayName.lowercase().contains(q) || it.modelId.lowercase().contains(q)
        }
    }

    fun applySelection(nextSelected: Set<String>, preferredDefault: String? = null) {
        val clean = nextSelected.filter { it.isNotBlank() }.toSet()
        if (!useDefaultModel) {
            onModelId("")
            onEnabledModelIds(clean)
            return
        }
        val nextDefault = when {
            preferredDefault != null && preferredDefault in clean -> preferredDefault
            modelId.isNotBlank() && modelId in clean -> modelId
            else -> clean.firstOrNull().orEmpty()
        }
        onModelId(nextDefault)
        onEnabledModelIds((clean - nextDefault).filter { it.isNotBlank() }.toSet())
    }

    fun addCustomModel() {
        val clean = customModelId.trim()
        if (clean.isBlank()) return
        applySelection(selectedIds + clean, preferredDefault = if (modelId.isBlank()) clean else null)
        customModelId = ""
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) }
        },
        placeholder = { Text("Search models") }
    )

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
        ) {
            if (visibleEntries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (allEntries.isEmpty()) "Add a custom model ID" else "No models found",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                itemsIndexed(
                    items = visibleEntries,
                    key = { _, entry -> entry.modelId }
                ) { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    AgentModelRow(
                        entry = entry,
                        checked = entry.modelId in selectedIds,
                        isDefault = useDefaultModel && entry.modelId == modelId,
                        showDefault = useDefaultModel,
                        onToggle = {
                            val checked = entry.modelId in selectedIds
                            applySelection(if (checked) selectedIds - entry.modelId else selectedIds + entry.modelId)
                        },
                        onDefault = {
                            if (entry.modelId == modelId) {
                                onModelId("")
                            } else {
                                applySelection(selectedIds + entry.modelId, preferredDefault = entry.modelId)
                            }
                        }
                    )
                }
            }
        }
    }

    OutlinedTextField(
        value = customModelId,
        onValueChange = { customModelId = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (customModelId.isNotBlank()) IconButton(onClick = ::addCustomModel) { Icon(Icons.Default.Check, null) }
        },
        placeholder = { Text("Custom model ID") }
    )

}

@Composable
private fun AgentModelsLoadingSection() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MorphingLoadingIndicator(
                        modifier = Modifier.size(52.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                "Loading models",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Preparing the provider catalog…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MorphingLoadingIndicator(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "models_loading")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "models_loading_progress"
    )
    val phases = listOf(0f, 0.28f, 0.56f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        phases.forEach { phase ->
            val t = (progress + phase) % 1f
            val pulse = 1f - kotlin.math.abs(t - 0.5f) * 2f
            val width = 10.dp + (18.dp * pulse)
            val height = 10.dp + (8.dp * pulse)
            Box(
                modifier = Modifier
                    .size(width, height)
                    .background(color.copy(alpha = 0.45f + (0.55f * pulse)), RoundedCornerShape(50))
            )
        }
    }
}

@Composable
private fun AgentModelRow(
    entry: AgentModelRowEntry,
    checked: Boolean,
    isDefault: Boolean,
    showDefault: Boolean,
    onToggle: () -> Unit,
    onDefault: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                if (entry.custom) "Custom model" else entry.modelId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (showDefault) {
            IconButton(onClick = onDefault) {
                Icon(
                    if (isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isDefault) "Default model" else "Set default model",
                    tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun modelsSummary(defaultModelId: String, enabledModelIds: Set<String>): String {
    val cleanEnabled = enabledModelIds.filter { it.isNotBlank() }.distinct()
    val count = cleanEnabled.size + defaultModelId.takeIf { it.isNotBlank() && it !in cleanEnabled }?.let { 1 }.orZero()
    return when {
        defaultModelId.isNotBlank() && count > 1 -> "$defaultModelId · $count enabled"
        defaultModelId.isNotBlank() -> defaultModelId
        count > 0 -> "$count enabled"
        else -> "Choose models"
    }
}

private data class AgentModelRowEntry(
    val modelId: String,
    val displayName: String,
    val custom: Boolean
)

private fun Int?.orZero(): Int = this ?: 0
