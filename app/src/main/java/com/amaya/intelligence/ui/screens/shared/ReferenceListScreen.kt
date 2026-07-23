package com.amaya.intelligence.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.ui.screens.amaya.AmayaDivider
import com.amaya.intelligence.ui.screens.amaya.AmayaNavigationRow
import com.amaya.intelligence.ui.screens.amaya.AmayaScaffold
import com.amaya.intelligence.ui.screens.amaya.AmayaSection
import java.io.File

@Composable
fun ReferenceListScreen(
    title: String,
    paths: List<String>,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onAdd: () -> Unit,
    onAddManual: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var manual by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        AmayaScaffold(title, snackbarHostState, onNavigateBack) {
            AmayaSection("References") {
                if (paths.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No references", style = MaterialTheme.typography.titleSmall)
                        Text("Add a text document for this context.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else paths.forEachIndexed { index, path ->
                    if (index > 0) AmayaDivider()
                    AmayaNavigationRow(Icons.Default.Description, File(path).name.substringAfter('_'), "Tap to remove", onClick = { deleting = path })
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Default.Add, "Add reference") },
            text = { Text("Import") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
        ExtendedFloatingActionButton(
            onClick = { manual = true },
            icon = { Icon(Icons.Default.Add, "Add note") },
            text = { Text("Note") },
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp)
        )
    }
    if (manual) AlertDialog(
        onDismissRequest = { manual = false },
        title = { Text("Add reference note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth(), label = { Text("Content") }, minLines = 4)
            }
        },
        confirmButton = { TextButton(enabled = content.isNotBlank(), onClick = { onAddManual(name, content); name = ""; content = ""; manual = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { manual = false }) { Text("Cancel") } }
    )
    deleting?.let { path -> AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("Remove reference?") },
        text = { Text(File(path).name.substringAfter('_')) },
        confirmButton = { TextButton(onClick = { onDelete(path); deleting = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
    ) }
}
