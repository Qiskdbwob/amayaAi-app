package com.amaya.intelligence.ui.screens.cronjob.remote

import androidx.compose.runtime.Composable
import com.amaya.intelligence.ui.screens.shared.ComingSoonScreen

@Composable
fun RemoteCronJobScreen(
    onNavigateBack: () -> Unit
) {
    ComingSoonScreen(
        title = "Reminders",
        onNavigateBack = onNavigateBack
    )
}