package com.amaya.intelligence.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.domain.models.AgentSelectorItem
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    agentItems: List<AgentSelectorItem>,
    activeAgentId: String,
    activeModel: String = "",
    activeProviderId: String = "",
    isRemote: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onSelect: (AgentSelectorItem) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberLockedModalBottomSheetState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val maxSheetHeight = (0.98f * LocalConfiguration.current.screenHeightDp).dp
    var query by remember { mutableStateOf("") }
    var sheetItems by remember { mutableStateOf(agentItems) }

    LaunchedEffect(agentItems) {
        if (sheetItems.isEmpty() && agentItems.isNotEmpty()) sheetItems = agentItems
    }

    val filteredItems = remember(sheetItems, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) sheetItems else sheetItems.filter { item ->
            item.name.lowercase().contains(q) ||
                item.modelId.lowercase().contains(q) ||
                item.providerName.lowercase().contains(q) ||
                item.providerId.lowercase().contains(q)
        }
    }
    val grouped = remember(filteredItems) {
        filteredItems.groupBy { it.providerName.ifBlank { if (it.isRemote) "Remote" else "Custom" } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = lockedModalBottomSheetProperties(),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        shape = responsiveBottomSheetShape(sheetState)
    ) {
        val gradients = LocalAmayaGradients.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .weight(1f, fill = false)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .ignoreNestedScrollForBottomSheet(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { Spacer(Modifier.height(90.dp)) }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) }
                        },
                        placeholder = { Text("Search models or providers") }
                    )
                }

                if (agentItems.isEmpty()) {
                    item { EmptyState("No active agents", "Enable agents in Settings → AI Agents") }
                } else if (filteredItems.isEmpty()) {
                    item { EmptyState("No models found", "Try another provider or model name") }
                } else {
                    grouped.forEach { (providerTitle, itemsForProvider) ->
                        item(key = "header_$providerTitle") {
                            Text(
                                providerTitle,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(
                            items = itemsForProvider,
                            key = { it.id }
                        ) { item ->
                            ModelSelectorRow(
                                item = item,
                                activeAgentId = activeAgentId,
                                activeModel = activeModel,
                                activeProviderId = activeProviderId,
                                firstItem = sheetItems.firstOrNull(),
                                sheetState = sheetState,
                                scope = scope,
                                onSelect = onSelect
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(gradients.modalTopScrim)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = responsiveDragHandleAlpha(sheetState)))
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Select Agent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.background))
                            .clickable { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectorRow(
    item: AgentSelectorItem,
    activeAgentId: String,
    activeModel: String,
    activeProviderId: String,
    firstItem: AgentSelectorItem?,
    sheetState: SheetState,
    scope: kotlinx.coroutines.CoroutineScope,
    onSelect: (AgentSelectorItem) -> Unit
) {
    val isSelected = item.id == activeAgentId ||
        (activeModel.isNotBlank() && item.modelId == activeModel && (activeProviderId.isBlank() || item.providerId == activeProviderId)) ||
        (activeAgentId.isBlank() && activeModel.isBlank() && item == firstItem)
    val missingModel = item.modelId.isBlank()
    val selectable = item.statusLabel != "Needs credential" && !missingModel
    val isDark = isSystemInDarkTheme()

    Surface(
        onClick = {
            if (!selectable) return@Surface
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                if (!sheetState.isVisible) onSelect(item)
            }
        },
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
        enabled = selectable
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                val iconSpec = AgentIcon.resolveByType(item.iconType, isDark)
                when {
                    missingModel -> Icon(Icons.Default.Warning, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    iconSpec != null -> Icon(
                        painterResource(id = iconSpec.resId),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (iconSpec.tintable) MaterialTheme.colorScheme.onSurface else Color.Unspecified
                    )
                    else -> Icon(
                        Icons.Default.SmartToy,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.isRemote) {
                    Text(
                        if (missingModel) "No model ID — edit in Settings" else item.modelId,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (missingModel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metadataLine = buildList {
                        item.statusLabel?.let { add(it) }
                        item.contextWindowLabel?.let { add("Context $it") }
                        if (item.capabilityLabels.isNotEmpty()) add(item.capabilityLabels.joinToString(" · "))
                        item.sourceLabel?.let { add(it) }
                    }.joinToString(" · ")
                    if (metadataLine.isNotBlank()) {
                        Text(
                            metadataLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (selectable) 0.75f else 0.45f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (item.quotaLabel != null || item.resetTime != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        item.quotaLabel?.let {
                            Text("Quota: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item.resetTime?.let {
                            Text(
                                "Resets at ${TimeUtils.parseResetTime(it)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (isSelected) {
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.SmartToy, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        }
    }
}
