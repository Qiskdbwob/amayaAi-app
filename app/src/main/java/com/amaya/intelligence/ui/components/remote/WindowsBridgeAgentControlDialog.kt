package com.amaya.intelligence.ui.components.remote

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Confirmation dialog shown before enabling Agent Control.
 */
@Composable
fun WindowsBridgeAgentControlDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Agent Control?") },
        text = {
            Text(
                "Amaya will be able to click, type, and focus windows on your PC. " +
                    "Only enable this when you can monitor the computer. " +
                    "You can stop anytime with Emergency Stop."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
