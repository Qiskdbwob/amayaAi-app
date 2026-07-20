package com.amaya.intelligence.ui.screens.agent.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.local.entity.AgentEntity
import com.amaya.intelligence.data.local.entity.AgentGroupEntity
import com.amaya.intelligence.ui.screens.amaya.AmayaDivider
import com.amaya.intelligence.ui.screens.amaya.AmayaNavigationRow
import com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet
import com.amaya.intelligence.ui.screens.amaya.AmayaScaffold
import com.amaya.intelligence.ui.screens.amaya.AmayaSection

@Composable
fun LocalAgentDetailScreen(
    group: AgentGroupEntity,
    agents: List<AgentEntity>,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onSaveGroupName: (String) -> Unit,
    onSaveGroupInstructions: (String) -> Unit,
    onSelectWorkspace: () -> Unit,
    onAddReference: () -> Unit,
    onOpenAgent: (AgentEntity) -> Unit,
    onCreateAgent: (String, String, String) -> Unit,
    onDeleteGroup: () -> Unit
) {
    var name by remember(group.id, group.name) { mutableStateOf(group.name) }
    var instructions by remember(group.id, group.instructions) { mutableStateOf(group.instructions) }
    var creatingAgent by remember { mutableStateOf(false) }
    var editSheet by remember(group.id) { mutableStateOf<String?>(null) }
    var agentName by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var agentInstructions by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AmayaScaffold(group.name, snackbarHostState, onNavigateBack) {
            AmayaSection("Group") {
                AmayaNavigationRow(Icons.Default.Badge, "Group name", group.name, onClick = { name = group.name; editSheet = "name" })
                AmayaDivider()
                AmayaNavigationRow(Icons.Default.Description, "Shared instructions", group.instructions.ifBlank { "No shared instructions" }, onClick = { instructions = group.instructions; editSheet = "instructions" })
                AmayaDivider()
                AmayaNavigationRow(Icons.Default.Folder, "Workspace", group.workspacePath, onSelectWorkspace)
                AmayaDivider()
                AmayaNavigationRow(Icons.Default.AttachFile, "Shared References", "${referenceCount(group.referencePathsJson)} attached · add text document", onAddReference)
            }
            AmayaSection("Agents") {
                if (agents.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No agents in this group", style = MaterialTheme.typography.titleSmall)
                        Text("Use + Agent to add the first role.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else agents.forEachIndexed { index, agent ->
                    if (index > 0) AmayaDivider()
                    AmayaNavigationRow(Icons.Default.SmartToy, agent.name, agent.role.ifBlank { "No role" }, onClick = { onOpenAgent(agent) })
                }
            }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete Group", color = MaterialTheme.colorScheme.error)
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creatingAgent = true },
            icon = { Icon(Icons.Default.Add, "Add agent") },
            text = { Text("Agent") },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(4.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }

    when (editSheet) {
        "name" -> StandardModalBottomSheet(onDismissRequest = { editSheet = null }, title = "Group name") {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Group name") }, singleLine = true)
            Button(onClick = { onSaveGroupName(name.trim()); editSheet = null; dismiss() }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save Name") }
        }
        "instructions" -> StandardModalBottomSheet(onDismissRequest = { editSheet = null }, title = "Shared instructions") {
            OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text("Instructions") }, minLines = 5)
            Button(onClick = { onSaveGroupInstructions(instructions.trim()); editSheet = null; dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Save Instructions") }
        }
        null -> Unit
    }

    if (creatingAgent) StandardModalBottomSheet(onDismissRequest = { creatingAgent = false }, title = "New agent") {
        OutlinedTextField(agentName, { agentName = it }, Modifier.fillMaxWidth(), label = { Text("Agent name") }, singleLine = true)
        OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text("Role") }, singleLine = true)
        OutlinedTextField(agentInstructions, { agentInstructions = it }, Modifier.fillMaxWidth(), label = { Text("Instructions") }, minLines = 4)
        Button(onClick = { onCreateAgent(agentName.trim(), role.trim(), agentInstructions.trim()); creatingAgent = false; dismiss() }, enabled = agentName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add Agent") }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${group.name}?") },
        text = { Text("All agents, delegation tasks, group conversations, and imported references will be deleted. Workspace files stay unchanged.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteGroup() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}

private fun referenceCount(json: String): Int = runCatching { org.json.JSONArray(json).length() }.getOrDefault(0)
