package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable

@Composable
fun MemoryScreen(
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onReview: () -> Unit,
    onToggleUseSavedMemory: (Boolean) -> Unit,
    onOpenArea: (MemoryArea) -> Unit,
    onReconnectWorkspace: (String) -> Unit
) {
    val memory = state.settings.memory
    AmayaScaffold("Memory", snackbarHostState, onNavigateBack) {
        AmayaSection("Memory") {
            AmayaSwitchRow("Use in chat", "Recall matching saved memory in future replies", memory.useSavedMemory, onToggleUseSavedMemory)
            AmayaDivider()
            AmayaStatusRow("Memory writes", "Tool only")
        }
        val missingWorkspaceBindings = state.workspaceBindings.filter { !it.rootExists && it.recordCount > 0 }
        if (missingWorkspaceBindings.isNotEmpty()) {
            AmayaSection("Moved Workspace Memory") {
                missingWorkspaceBindings.forEachIndexed { index, binding ->
                    AmayaNavigationRow(
                        Icons.Default.FolderSpecial,
                        "Reconnect ${binding.root.substringAfterLast('/')}",
                        if (state.workspacePath == null) "Select the moved workspace first" else "Attach ${binding.recordCount} saved item${if (binding.recordCount == 1) "" else "s"} to the current workspace",
                        onClick = { onReconnectWorkspace(binding.id) }
                    )
                    if (index < missingWorkspaceBindings.lastIndex) AmayaDivider()
                }
            }
        }
        if (state.memorySuggestions > 0) {
            AmayaSection("Review") {
                AmayaNavigationRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "${state.memorySuggestions} memory suggestion${if (state.memorySuggestions == 1) "" else "s"}",
                    subtitle = "Save or dismiss suggested memories",
                    onClick = onReview
                )
            }
        }
        AmayaSection("Saved Areas") {
            AmayaNavigationRow(Icons.Default.Person, "About You", "${state.userMemoryCount} item${if (state.userMemoryCount == 1) "" else "s"} · ${state.userMemoryPreview.oneLine()}", onClick = { onOpenArea(MemoryArea.USER) })
            AmayaDivider()
            AmayaNavigationRow(
                Icons.Default.FolderSpecial,
                "Project Memory",
                if (state.workspacePath == null) "No workspace selected" else "${state.projectMemoryCount} item${if (state.projectMemoryCount == 1) "" else "s"} · ${state.projectMemoryPreview.oneLine()}",
                onClick = { onOpenArea(MemoryArea.PROJECT) }
            )
        }
    }
}

enum class MemoryArea(val key: String, val title: String) {
    USER("user", "About You"),
    PROJECT("project", "Project Memory");

    companion object {
        fun fromKey(key: String?): MemoryArea = entries.firstOrNull { it.key == key } ?: USER
    }
}

private fun String.oneLine(): String = lines()
    .firstOrNull { it.isNotBlank() && it != "No saved items" }
    ?.take(90)
    ?: "No saved items"
