package com.amaya.intelligence.ui.components.local

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import com.amaya.intelligence.ui.components.shared.ContextWindowUtils

import com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoSheet(
    totalTokens: Int,
    activeModel: String,
    activeReminderCount: Int,
    onDismiss: () -> Unit,
    inputTokens: Int = 0,
    outputTokens: Int = 0,
    providerId: String? = null,
    providerNameOverride: String? = null,
    modelDisplayNameOverride: String? = null
) {
    StandardModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Session Info"
    ) {
        SessionInfoRow(
            icon = Icons.Default.Error,
            label = "Tokens used",
            value = if (totalTokens > 0) ContextWindowUtils.formatTokenCount(totalTokens) else "0"
        )

        if (inputTokens > 0 || outputTokens > 0) {
            SessionInfoRow(
                icon = Icons.AutoMirrored.Filled.Login,
                label = "Input tokens",
                value = ContextWindowUtils.formatTokenCount(inputTokens)
            )
            SessionInfoRow(
                icon = Icons.AutoMirrored.Filled.Logout,
                label = "Output tokens",
                value = ContextWindowUtils.formatTokenCount(outputTokens)
            )
        }

        SessionInfoRow(
            icon = Icons.Default.AccountTree,
            label = "Provider",
            value = providerNameOverride ?: providerId ?: "Unknown"
        )

        SessionInfoRow(
            icon = Icons.Default.Psychology,
            label = "Model",
            value = modelDisplayNameOverride ?: activeModel
        )

        SessionInfoRow(
            icon = Icons.Default.Alarm,
            label = "Active reminders",
            value = if (activeReminderCount > 0) "$activeReminderCount active" else "None",
            valueColor = if (activeReminderCount > 0) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SessionInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}
