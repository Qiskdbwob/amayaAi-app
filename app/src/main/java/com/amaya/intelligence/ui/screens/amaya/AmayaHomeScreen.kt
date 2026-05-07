package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable

@Composable
fun AmayaHomeScreen(
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onPersona: () -> Unit,
    onMemory: () -> Unit,
    onReview: () -> Unit,
    onSkills: () -> Unit,
    onContext: () -> Unit,
    onPrivacy: () -> Unit
) {
    AmayaScaffold("Amaya", snackbarHostState, onNavigateBack) {
        if (state.pendingProposals.isNotEmpty()) {
            AmayaSection("Needs Attention") {
                AmayaNavigationRow(
                    icon = Icons.Default.SettingsSuggest,
                    title = "${state.pendingProposals.size} suggestion${if (state.pendingProposals.size == 1) "" else "s"} need review",
                    subtitle = "Save or dismiss memory and context updates",
                    onClick = onReview
                )
            }
        }
        AmayaSection("Settings") {
            AmayaNavigationRow(Icons.Default.Person, "Persona", "Style, tone, and behavior", onPersona)
            AmayaDivider()
            AmayaNavigationRow(Icons.Default.Memory, "Memory", "${state.totalMemoryCount} saved items", onMemory)
            AmayaDivider()
            AmayaNavigationRow(Icons.Default.Psychology, "Skills", "${state.enabledSkills} enabled workflows", onSkills)
            AmayaDivider()
            AmayaNavigationRow(Icons.Default.TravelExplore, "Context & Recall", "${state.settings.context.enabledSourceCount()} sources enabled", onContext)
            AmayaDivider()
            AmayaNavigationRow(Icons.Default.Security, "Privacy & Safety", "Sensitive data and safety boundaries", onPrivacy)
        }
    }
}

private fun com.amaya.intelligence.data.repository.ContextRecallSettings.enabledSourceCount(): Int =
    listOf(pastChatRecallEnabled, workspaceContextEnabled, relevantMemoryEnabled).count { it }
