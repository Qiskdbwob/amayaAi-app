package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable

@Composable
fun MemoryScreen(
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onReview: () -> Unit,
    onToggleUseSavedMemory: (Boolean) -> Unit,
    onToggleSuggestNewMemory: (Boolean) -> Unit,
    onToggleAutoSaveSafeMemory: (Boolean) -> Unit,
    onToggleDailyNotes: (Boolean) -> Unit,
    onOpenArea: (MemoryArea) -> Unit
) {
    val memory = state.settings.memory
    AmayaScaffold("Memory", snackbarHostState, onNavigateBack) {
        AmayaSection("Memory") {
            AmayaSwitchRow("Use in chat", "Recall matching saved memory in future replies", memory.useSavedMemory, onToggleUseSavedMemory)
            AmayaDivider()
            AmayaSwitchRow("Learn from chat", "Extract explicit durable facts after a chat", memory.suggestNewMemories, onToggleSuggestNewMemory)
            AmayaDivider()
            AmayaSwitchRow(
                "Auto-save safe facts",
                if (memory.suggestNewMemories) "High-confidence facts skip review" else "Enable Learn from chat first",
                memory.autoSaveSafeMemory && memory.suggestNewMemories,
                onToggleAutoSaveSafeMemory,
                enabled = memory.suggestNewMemories
            )
            AmayaDivider()
            AmayaSwitchRow("Daily notes", "Store compact chronological summaries", memory.dailyNotesEnabled, onToggleDailyNotes)
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
            AmayaNavigationRow(Icons.Default.Star, "Important Memory", "${state.importantMemoryCount} item${if (state.importantMemoryCount == 1) "" else "s"} · ${state.importantMemoryPreview.oneLine()}", onClick = { onOpenArea(MemoryArea.IMPORTANT) })
            AmayaDivider()
            AmayaNavigationRow(Icons.Default.FolderSpecial, "Project Memory", "${state.projectMemoryCount} item${if (state.projectMemoryCount == 1) "" else "s"} · ${state.projectMemoryPreview.oneLine()}", onClick = { onOpenArea(MemoryArea.PROJECT) })
            AmayaDivider()
            AmayaNavigationRow(Icons.Default.CalendarMonth, "Daily Notes", if (memory.dailyNotesEnabled) "${state.dailyMemoryRecords.size} item${if (state.dailyMemoryRecords.size == 1) "" else "s"} · ${state.dailyNotesPreview.oneLine()}" else "Off", onClick = { onOpenArea(MemoryArea.DAILY) })
        }
    }
}

enum class MemoryArea(val key: String, val title: String) {
    USER("user", "About You"),
    IMPORTANT("important", "Important Memory"),
    PROJECT("project", "Project Memory"),
    DAILY("daily", "Daily Notes");

    companion object {
        fun fromKey(key: String?): MemoryArea = entries.firstOrNull { it.key == key } ?: USER
    }
}

private fun String.oneLine(): String = lines()
    .firstOrNull { it.isNotBlank() && it != "No saved items" }
    ?.take(90)
    ?: "No saved items"
