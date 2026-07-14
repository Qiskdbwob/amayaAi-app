package com.amaya.intelligence.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close

import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.domain.models.ModelOption
import com.amaya.intelligence.ui.screens.models.rememberModelSettingsColors
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    modelOptions: List<ModelOption>,
    activeModelKey: String,
    onSelect: (ModelOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberLockedModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val colors = rememberModelSettingsColors()
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(modelOptions, query) {
        val value = query.trim().lowercase()
        if (value.isBlank()) modelOptions else modelOptions.filter {
            it.name.lowercase().contains(value) ||
                it.modelId.lowercase().contains(value) ||
                it.providerName.lowercase().contains(value)
        }
    }
    val grouped = remember(filtered) {
        filtered.groupBy { it.providerName.ifBlank { "Models" } }
    }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = ::close,
        sheetState = sheetState,
        properties = lockedModalBottomSheetProperties(),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = responsiveBottomSheetShape(sheetState),
        dragHandle = null
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().ignoreNestedScrollForBottomSheet(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 96.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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
                if (modelOptions.isEmpty()) {
                    item {
                        Text(
                            "No models shown in chat. Add models in Settings → Manage Models.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Text("No models found", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    grouped.forEach { (provider, options) ->
                        item(key = "header_$provider") {
                            Text(
                                provider.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.headerText,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
                            )
                        }
                        item(key = "group_$provider") {
                            Surface(shape = RoundedCornerShape(16.dp), color = colors.groupSurface, border = androidx.compose.foundation.BorderStroke(0.7.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    options.forEachIndexed { index, option ->
                                        val isActive = option.id == activeModelKey
                                        Surface(
                                            onClick = {
                                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                                    if (!sheetState.isVisible) onSelect(option)
                                                }
                                            },
                                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent
                                        ) {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                        if (index < options.lastIndex) {
                                            HorizontalDivider(color = colors.separator)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).background(LocalAmayaGradients.current.modalTopScrim).padding(horizontal = 24.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Box(modifier = Modifier.size(36.dp).clickable { showSearch = !showSearch }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Search, "Search", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Select Model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Box(modifier = Modifier.size(36.dp).clickable(onClick = ::close), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
