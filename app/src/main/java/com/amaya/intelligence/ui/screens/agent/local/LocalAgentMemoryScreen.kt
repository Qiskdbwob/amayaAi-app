package com.amaya.intelligence.ui.screens.agent.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.repository.AgentMemoryRecord
import com.amaya.intelligence.ui.screens.amaya.AmayaDivider
import com.amaya.intelligence.ui.screens.amaya.AmayaNavigationRow
import com.amaya.intelligence.ui.screens.amaya.AmayaScaffold
import com.amaya.intelligence.ui.screens.amaya.AmayaSection

@Composable
fun LocalAgentMemoryScreen(
    records: List<AgentMemoryRecord>,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onAdd: (String) -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf("") }
    AmayaScaffold("Agent Memory", snackbarHostState, onNavigateBack) {
        AmayaSection("Private to this agent") {
            AmayaNavigationRow(Icons.Default.Add, "Add Memory", "Saved only for this agent", onClick = { adding = true })
            records.forEach { record ->
                AmayaDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(record.title)
                    Text(record.content)
                }
            }
        }
    }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("Add Agent Memory") },
        text = { OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth(), label = { Text("Memory") }, minLines = 3) },
        confirmButton = { TextButton(enabled = content.isNotBlank(), onClick = { onAdd(content.trim()); content = ""; adding = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } }
    )
}
