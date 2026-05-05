package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable

@Composable
fun PrivacySafetyScreen(
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit
) {
    AmayaScaffold("Privacy & Safety", snackbarHostState, onNavigateBack) {
        AmayaSection("Blocked Data") {
            AmayaStatusRow("Passwords", "Blocked")
            AmayaDivider()
            AmayaStatusRow("API keys and tokens", "Blocked")
            AmayaDivider()
            AmayaStatusRow("OTPs and cookies", "Blocked")
            AmayaDivider()
            AmayaStatusRow("Payment data", "Blocked")
        }
        AmayaSection("Confirmations") {
            AmayaStatusRow(
                "Memory writes",
                if (state.settings.memory.autoSaveSafeMemory) "Auto-safe" else "Review"
            )
            AmayaDivider()
            AmayaStatusRow("Memory update/remove", "Confirm")
            AmayaDivider()
            AmayaStatusRow("Sensitive browser pages", "Pause")
        }
    }
}
