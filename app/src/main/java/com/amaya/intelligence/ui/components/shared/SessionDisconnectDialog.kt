package com.amaya.intelligence.ui.components.shared

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared confirmation dialog shown before tearing down a remote session
 * (Antigravity, Windows Bridge, or any future remote IDE). Used by the chat
 * Android back-press handler and by the sidebar disconnect action so every
 * disconnect path keeps the same warning UX.
 */
@Composable
fun SessionDisconnectDialog(
    sessionName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.LinkOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text("Disconnect from $sessionName?") },
        text = {
            Text(
                "The session will close and any running tool calls will stop. " +
                    "You can reconnect from the Remote Session screen anytime."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Disconnect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay connected") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge
    )
}
