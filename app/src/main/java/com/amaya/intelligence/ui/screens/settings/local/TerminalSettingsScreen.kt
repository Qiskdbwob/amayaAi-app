package com.amaya.intelligence.ui.screens.settings.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.repository.TerminalSettings
import com.amaya.intelligence.data.repository.TerminalSettingsRepository
import com.amaya.intelligence.ui.screens.amaya.AmayaScaffold
import com.amaya.intelligence.ui.screens.amaya.AmayaSection
import com.amaya.intelligence.ui.screens.amaya.AmayaSwitchRow
import kotlinx.coroutines.launch

@Composable
fun TerminalSettingsScreen(
    repository: TerminalSettingsRepository,
    onNavigateBack: () -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var trusted by remember { mutableStateOf("") }
    var declined by remember { mutableStateOf("") }
    var autoApproveNonDestructive by remember { mutableStateOf(true) }
    var autoApproveAll by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settings = repository.getSettings()
        trusted = settings.trustedCommands.joinToString("\n")
        declined = settings.declinedCommands.joinToString("\n")
        autoApproveNonDestructive = settings.autoApproveNonDestructive
        autoApproveAll = settings.autoApproveAll
        loaded = true
    }

    AmayaScaffold("Terminal", snackbar, onNavigateBack) {
        AmayaSection("Command Policy") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "One wildcard pattern per line. Trusted commands run automatically without approval. Declined commands are blocked. Toggle Auto-Approve below to run commands without manual confirmation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = trusted,
                    onValueChange = { trusted = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Trusted Commands") },
                    supportingText = { Text("Examples: npm * or python * or * (for all)") },
                    minLines = 5,
                    enabled = loaded,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                OutlinedTextField(
                    value = declined,
                    onValueChange = { declined = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Declined Commands") },
                    supportingText = { Text("Matched commands are rejected without review") },
                    minLines = 3,
                    enabled = loaded,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                AmayaSwitchRow(
                    title = "Auto-approve all commands (Full Auto)",
                    subtitle = "Automatically run all workspace commands and tool actions without confirmation dialogs. Critical system boundaries (e.g. root/system format) and declined patterns are still enforced.",
                    checked = autoApproveAll,
                    onCheckedChange = {
                        autoApproveAll = it
                        if (it) autoApproveNonDestructive = true
                    },
                    enabled = loaded
                )
                AmayaSwitchRow(
                    title = "Auto-approve safe commands",
                    subtitle = "Auto-approve non-destructive commands (MCP, python, gradle, git status, reads); destructive commands (deletion, overwrite, chmod, sudo, git push) still require manual confirmation.",
                    checked = autoApproveNonDestructive || autoApproveAll,
                    onCheckedChange = { autoApproveNonDestructive = it },
                    enabled = loaded && !autoApproveAll
                )
                Button(
                    onClick = {
                        scope.launch {
                            val cleanTrusted = trusted.lines().map { it.trim() }.filter { it.isNotBlank() }
                            val cleanDeclined = declined.lines().map { it.trim() }.filter { it.isNotBlank() }
                            repository.setSettings(
                                TerminalSettings(
                                    trustedCommands = cleanTrusted,
                                    declinedCommands = cleanDeclined,
                                    autoApproveNonDestructive = autoApproveNonDestructive,
                                    autoApproveAll = autoApproveAll
                                )
                            )
                            snackbar.showSnackbar("Terminal settings saved")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = loaded
                ) { Text("Save") }
            }
        }
    }
}
