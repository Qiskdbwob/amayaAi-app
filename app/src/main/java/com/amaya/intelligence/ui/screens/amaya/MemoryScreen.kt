package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.material.icons.Icons
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable

@Composable
fun MemoryScreen(
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onToggleUseSavedMemory: (Boolean) -> Unit,
    onOpenAboutYou: () -> Unit
) {
    MemoryAreaListScreen(
        area = MemoryArea.USER,
        state = state,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onAdd = {}
    )
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
