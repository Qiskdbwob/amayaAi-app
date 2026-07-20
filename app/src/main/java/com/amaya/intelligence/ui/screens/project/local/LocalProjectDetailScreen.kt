package com.amaya.intelligence.ui.screens.project.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.amaya.intelligence.data.local.entity.ProjectEntity
import com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet
import com.amaya.intelligence.ui.screens.amaya.AmayaDivider
import com.amaya.intelligence.ui.screens.amaya.AmayaNavigationRow
import com.amaya.intelligence.ui.screens.amaya.AmayaScaffold
import com.amaya.intelligence.ui.screens.amaya.AmayaSection

private enum class ProjectEditSheet { NAME, INSTRUCTIONS }

@Composable
fun LocalProjectDetailScreen(
    project: ProjectEntity,
    memoryCount: Int,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onSaveName: (String) -> Unit,
    onSaveInstructions: (String) -> Unit,
    onOpenMemory: () -> Unit,
    onAddReference: () -> Unit,
    onDelete: () -> Unit
) {
    var editSheet by remember(project.id) { mutableStateOf<ProjectEditSheet?>(null) }
    var name by remember(project.id, project.name) { mutableStateOf(project.name) }
    var instructions by remember(project.id, project.instructions) { mutableStateOf(project.instructions) }
    var confirmDelete by remember(project.id) { mutableStateOf(false) }

    AmayaScaffold(project.name, snackbarHostState, onNavigateBack) {
        AmayaSection("Identity") {
            AmayaNavigationRow(Icons.Default.Badge, "Project name", project.name, onClick = { name = project.name; editSheet = ProjectEditSheet.NAME })
            AmayaDivider()
            AmayaNavigationRow(Icons.Default.Description, "Instructions", project.instructions.ifBlank { "No project instructions" }, onClick = { instructions = project.instructions; editSheet = ProjectEditSheet.INSTRUCTIONS })
            AmayaDivider()
            AmayaNavigationRow(Icons.Default.Folder, "Workspace", project.rootPath, onClick = {})
        }
        AmayaSection("Project") {
            AmayaNavigationRow(Icons.Default.Memory, "Project Memory", "$memoryCount saved", onOpenMemory)
            AmayaDivider()
            AmayaNavigationRow(Icons.Default.AttachFile, "References", "${referenceCount(project.referencePathsJson)} attached · add text document", onAddReference)
        }
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Delete Project", color = MaterialTheme.colorScheme.error)
        }
    }

    when (editSheet) {
        ProjectEditSheet.NAME -> StandardModalBottomSheet(
            onDismissRequest = { editSheet = null },
            title = "Project name"
        ) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Project name") }, singleLine = true)
            Button(
                onClick = { onSaveName(name.trim()); editSheet = null; dismiss() },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Name") }
        }
        ProjectEditSheet.INSTRUCTIONS -> StandardModalBottomSheet(
            onDismissRequest = { editSheet = null },
            title = "Project instructions"
        ) {
            OutlinedTextField(
                instructions,
                { instructions = it },
                Modifier.fillMaxWidth(),
                label = { Text("Instructions") },
                supportingText = { Text("Applied to every conversation in this project") },
                minLines = 5
            )
            Button(
                onClick = { onSaveInstructions(instructions.trim()); editSheet = null; dismiss() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Instructions") }
        }
        null -> Unit
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${project.name}?") },
        text = { Text("Project conversations and imported references will be deleted. Workspace files stay unchanged.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}

private fun referenceCount(json: String): Int = runCatching { org.json.JSONArray(json).length() }.getOrDefault(0)
