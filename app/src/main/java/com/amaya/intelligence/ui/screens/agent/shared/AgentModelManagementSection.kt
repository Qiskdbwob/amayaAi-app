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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.remote.api.ModelCatalogEntry
import com.amaya.intelligence.ui.components.shared.ModelSelectionSheet
import com.amaya.intelligence.ui.components.shared.ignoreNestedScrollForBottomSheet

@Composable
internal fun SubscriptionStep(
    providerId: String,
    providerName: String,
    modelCatalog: List<ModelCatalogEntry>,
    enabledModelIds: Set<String>,
    onEnabledModelIds: (Set<String>) -> Unit,
    authUi: AgentSubscriptionAuthUi?,
    onDelete: (() -> Unit)? = null
) {
    val sortedModels = remember(modelCatalog) { modelCatalog.distinctBy { it.modelId }.sortedBy { it.displayName.lowercase() } }
    val catalogModelIds = remember(sortedModels) { sortedModels.map { it.modelId }.toSet() }
    val normalizedEnabledModelIds = remember(enabledModelIds, catalogModelIds) {
        enabledModelIds.filter { it.isNotBlank() && it in catalogModelIds }.distinct().toSet()
    }
    val enabledCount = remember(normalizedEnabledModelIds) { normalizedEnabledModelIds.size }
    var showModelSelectionSheet by remember(providerId) { mutableStateOf(false) }

    if (authUi != null) {
        SubscriptionConnectionCard(authUi = authUi)
    }

    if (sortedModels.isNotEmpty()) {
        Text(
            if (enabledCount > 0) "$enabledCount enabled" else "No models enabled",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SubscriptionModelsSummaryCard(
            modelCatalog = sortedModels,
            enabledModelIds = normalizedEnabledModelIds,
            expanded = showModelSelectionSheet,
            onEditSelection = { showModelSelectionSheet = true }
        )
    } else {
        Text(
            "$providerName models are not synced yet. Try opening this modal again after sync finishes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showModelSelectionSheet) {
        ModelSelectionSheet(
            title = "Edit selection",
            subtitle = null,
            modelCatalog = sortedModels,
            selectedModelIds = normalizedEnabledModelIds,
            onSelectedModelIdsChange = { next ->
                val clean = next.filter { it.isNotBlank() && it in catalogModelIds }.toSet()
                onEnabledModelIds(clean)
            },
            onDismiss = { showModelSelectionSheet = false }
        )
    }

    if (onDelete != null) {
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text("Delete subscription")
        }
    }
}

@Composable
private fun SubscriptionModelsSummaryCard(
    modelCatalog: List<ModelCatalogEntry>,
    enabledModelIds: Set<String>,
    expanded: Boolean,
    onEditSelection: () -> Unit
) {
    val selectedModels = remember(modelCatalog, enabledModelIds) {
        modelCatalog.filter { it.modelId in enabledModelIds }
    }
    val previewModels = selectedModels.take(3)
    val moreCount = (selectedModels.size - previewModels.size).coerceAtLeast(0)

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (selectedModels.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Text("No models selected yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                previewModels.forEachIndexed { index, entry ->
                    SubscriptionModelRow(entry = entry)
                    if (index < previewModels.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }
                if (moreCount > 0) {
                    HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("+$moreCount more models", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            TextButton(
                onClick = onEditSelection,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(0.dp)
            ) {
                Text(if (expanded) "Hide selection" else "Edit selection")
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SubscriptionModelRow(entry: ModelCatalogEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF10A37F).copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10A37F), modifier = Modifier.size(20.dp))
        }
        Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
        entry.contextWindow?.let {
            Text(formatTokenCountLocal(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ManageProviderModelsSection(
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
    Text(
        if (showDefaultControls) "Manage Models" else "Edit selection",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    if (showDefaultControls) {
        Text(
            "Only checked $providerName models will appear in Select Agent. Your default model is always kept enabled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            "Choose which $providerName models show up in the agent picker.",
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

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .ignoreNestedScrollForBottomSheet(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(visibleModels, key = { it.modelId }) { entry ->
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
                    if (showDefaultControls) {
                        if (entry.modelId == defaultModelId) {
                            AssistChip(onClick = {}, label = { Text("Default") })
                        } else {
                            TextButton(onClick = {
                                onSetDefaultModel(entry.modelId)
                                onEnabledModelIds((enabledModelIds + entry.modelId).filter { it.isNotBlank() }.toSet())
                            }) { Text("Set default") }
                        }
                    }
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
