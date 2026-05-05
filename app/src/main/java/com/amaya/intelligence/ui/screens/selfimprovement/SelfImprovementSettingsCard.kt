package com.amaya.intelligence.ui.screens.selfimprovement

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.repository.SelfImprovementMode

@Composable
fun SelfImprovementSettingsCard(
    mode: SelfImprovementMode,
    pendingCount: Int,
    lastMaintenanceRun: String,
    onModeChange: (SelfImprovementMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Learning mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose how much Amaya may remember after a chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelfImprovementMode.entries.forEach { entry ->
                    ModeChoiceRow(
                        mode = entry,
                        selected = entry == mode,
                        onClick = { onModeChange(entry) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricPill("Pending", pendingCount.toString(), Modifier.weight(1f))
                MetricPill("Memory", pendingCount.toString(), Modifier.weight(1f))
            }
            Text(
                "Last maintenance: $lastMaintenanceRun",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModeChoiceRow(
    mode: SelfImprovementMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    )
    Card(
        onClick = onClick,
        colors = colors,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(modeLabel(mode), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(modeDescription(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Text("Active", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun modeLabel(mode: SelfImprovementMode): String = when (mode) {
    SelfImprovementMode.OFF -> "Off"
    SelfImprovementMode.DAILY_LOG_ONLY -> "Daily log only"
    SelfImprovementMode.ASK_APPROVAL -> "Ask before saving"
    SelfImprovementMode.SAFE_AUTO -> "Safe auto-save"
}

fun modeDescription(mode: SelfImprovementMode): String = when (mode) {
    SelfImprovementMode.OFF -> "Amaya does not write memories or create memory suggestions."
    SelfImprovementMode.DAILY_LOG_ONLY -> "Only chronological daily notes are saved; durable memory writes and removals stay off."
    SelfImprovementMode.ASK_APPROVAL -> "Daily notes save automatically; durable memory saves/removals wait for review before future chats."
    SelfImprovementMode.SAFE_AUTO -> "Daily notes and safe explicit memory saves/removals apply automatically; noisy items are ignored."
}
