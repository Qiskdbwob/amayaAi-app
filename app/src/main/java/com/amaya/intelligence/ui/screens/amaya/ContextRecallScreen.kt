package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable

@Composable
fun ContextRecallScreen(
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onTogglePastChats: (Boolean) -> Unit,
    onToggleWorkspace: (Boolean) -> Unit,
    onToggleRelevantMemory: (Boolean) -> Unit
) {
    val context = state.settings.context
    AmayaScaffold("Context & Recall", snackbarHostState, onNavigateBack) {
        AmayaSection("Recall Sources") {
            AmayaSwitchRow("Previous conversations", "Use session search when older chats are referenced", context.pastChatRecallEnabled, onTogglePastChats)
            AmayaDivider()
            AmayaSwitchRow("Relevant memory", "Inject matching saved memory", context.relevantMemoryEnabled, onToggleRelevantMemory)
            AmayaDivider()
            AmayaSwitchRow("Project context", "Use workspace path and project memory", context.workspaceContextEnabled, onToggleWorkspace)
        }
        AmayaSection("Limits") {
            AmayaStatusRow("Maximum recall", "${context.maxRecallItems}", "Items per source")
            AmayaDivider()
            AmayaStatusRow("Saved skills", "${state.enabledSkills}", "Enabled workflows can appear when relevant")
        }
    }
}
