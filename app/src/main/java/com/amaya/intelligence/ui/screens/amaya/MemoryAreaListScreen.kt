package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.repository.MemoryRecord

@Composable
fun MemoryAreaListScreen(
    area: MemoryArea,
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val records = when (area) {
        MemoryArea.USER -> state.userMemoryRecords
        MemoryArea.IMPORTANT -> state.importantMemoryRecords
        MemoryArea.PROJECT -> state.projectMemoryRecords
        MemoryArea.DAILY -> state.dailyMemoryRecords
    }

    AmayaScaffold(area.title, snackbarHostState, onNavigateBack) {
        AddMemorySection(area = area, onAdd = onAdd)
        if (records.isNotEmpty()) {
            AmayaSection("Saved") {
                records.forEachIndexed { index, record ->
                    MemoryRecordCard(record = record, onDelete = { onDelete(record.id) })
                    if (index < records.lastIndex) AmayaDivider()
                }
            }
        }
    }
}

@Composable
private fun AddMemorySection(area: MemoryArea, onAdd: (String) -> Unit) {
    var text by remember(area) { mutableStateOf("") }
    AmayaSection("Add") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(area.inputLabel()) },
                minLines = if (area == MemoryArea.DAILY) 3 else 2
            )
            Button(
                onClick = {
                    onAdd(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.align(Alignment.End)
            ) { Text("Save") }
        }
    }
}

@Composable
private fun MemoryRecordCard(record: MemoryRecord, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(record.content, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            if (record.reason.isNotBlank()) {
                Text(record.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun MemoryArea.inputLabel(): String = when (this) {
    MemoryArea.USER -> "Preference or profile fact"
    MemoryArea.IMPORTANT -> "Important durable fact"
    MemoryArea.PROJECT -> "Workspace or project fact"
    MemoryArea.DAILY -> "Daily note summary"
}

