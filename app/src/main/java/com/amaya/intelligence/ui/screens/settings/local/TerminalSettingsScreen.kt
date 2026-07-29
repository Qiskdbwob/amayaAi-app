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
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settings = repository.getSettings()
        trusted = settings.trustedCommands.joinToString("\n")
        declined = settings.declinedCommands.joinToString("\n")
        loaded = true
    }

    AmayaScaffold("Terminal", snackbar, onNavigateBack) {
        AmayaSection("Command Policy") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "One wildcard pattern per line. Trusted commands run automatically. Declined commands are blocked. Other commands require review.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = trusted,
                    onValueChange = { trusted = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Trusted Commands") },
                    supportingText = { Text("Examples: npm * or npm run *") },
                    minLines = 6,
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
                    minLines = 4,
                    enabled = loaded,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                Button(
                    onClick = {
                        scope.launch {
                            repository.setSettings(
                                TerminalSettings(
                                    trustedCommands = trusted.lines(),
                                    declinedCommands = declined.lines()
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
