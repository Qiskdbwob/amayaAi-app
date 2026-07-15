package com.amaya.intelligence.ui.components.remote

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp


import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch

/**
 * Windows Bridge session-info sheet. Opened from the chat top bar (more menu)
 * when the session is in Windows Bridge mode.
 *
 * Content is intentionally minimal: the agent-mode toggle, a compact info card,
 * the capture preview, and a single disconnect action. Emergency-stop still lives
 * on the inline banner in local chat — duplicating it here caused clutter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsBridgeSessionInfoSheet(
    state: WindowsBridgeChatUiState,
    onToggleAgentControl: () -> Unit,
    onCapture: () -> Unit,
    onClearCapture: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Windows Bridge"
    ) {
        // Agent control toggle
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (state.isAgentControlEnabled) Icons.Default.Mouse else Icons.Default.Visibility,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = if (state.isAgentControlEnabled)
                        Color(0xFFF39C12)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Agent mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (state.isAgentControlEnabled)
                            "Mouse, keyboard, and window focus enabled"
                        else "View only — input tools hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.isAgentControlEnabled,
                    onCheckedChange = { onToggleAgentControl() }
                )
            }
        }

        // Info rows
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow(Icons.Default.Dns, "Server", state.serverLabel)
                InfoRow(Icons.Default.Tag, "Session", state.sessionId?.let(::shortSessionId) ?: "—")
                InfoRow(
                    Icons.Default.DesktopWindows,
                    "Mode",
                    if (state.isAgentControlEnabled) "Agent Control" else "View Only"
                )
            }
        }

        // Capture preview + controls
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Screenshot, null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Remote screen",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    val capture = state.screenCapture
                    if (capture?.imageBase64 != null) {
                        TextButton(onClick = onClearCapture, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("Clear", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                val capture = state.screenCapture
                when {
                    capture?.isLoading == true -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                    }
                    capture?.error != null -> {
                        Text(
                            capture.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    capture?.imageBase64 != null -> {
                        val bitmap = remember(capture.imageBase64) {
                            try {
                                val bytes = Base64.decode(capture.imageBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } catch (_: Exception) { null }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            )
                            Text(
                                "${capture.width}×${capture.height} ${capture.format}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        Text(
                            "Preview the current Windows screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = onCapture,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = state.isConnected && capture?.isLoading != true
                ) {
                    Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Capture screen")
                }
            }
        }

        // Disconnect action (primary destructive action for this sheet)
        Button(
            onClick = { dismiss(onDisconnect) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Disconnect",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 72.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

/** Collapse a long UUID-like session id to the short form "abc1234…9f5d". */
private fun shortSessionId(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length <= 14) return trimmed
    return trimmed.take(8) + "…" + trimmed.takeLast(4)
}
