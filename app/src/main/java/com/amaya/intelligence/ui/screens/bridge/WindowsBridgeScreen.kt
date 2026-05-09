package com.amaya.intelligence.ui.screens.bridge

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.ui.components.remote.WindowsBridgeAgentControlDialog

/**
 * Windows Bridge management screen. Allows the user to connect, view the remote
 * screen, toggle Agent Control, handle approvals, and emergency-stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsBridgeScreen(
    onBack: () -> Unit,
    onStartChat: () -> Unit = onBack,
    viewModel: WindowsBridgeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showAgentControlDialog by remember { mutableStateOf(false) }
    var openChatAfterConnect by remember { mutableStateOf(false) }

    LaunchedEffect(state.isConnected, openChatAfterConnect) {
        if (state.isConnected && openChatAfterConnect) {
            openChatAfterConnect = false
            onStartChat()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Windows Bridge", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Status card ──────────────────────────────────────────────
            StatusCard(state)

            // ── Pending approval ─────────────────────────────────────────
            state.pendingApproval?.let { approval ->
                ApprovalCard(
                    tool = approval.tool,
                    risk = approval.risk.wireName,
                    reason = approval.reason,
                    onApprove = viewModel::approveRequest,
                    onReject = viewModel::rejectRequest
                )
            }

            // ── Connect / Disconnect ─────────────────────────────────────
            if (!state.isConnected && !state.isConnecting) {
                ConnectForm(
                    host = state.host,
                    port = state.port,
                    token = state.token,
                    onHostChange = viewModel::updateHost,
                    onPortChange = viewModel::updatePort,
                    onTokenChange = viewModel::updateToken,
                    onConnect = {
                        openChatAfterConnect = true
                        viewModel.connect()
                    }
                )
            }

            // ── Controls (when connected) ────────────────────────────────
            if (state.isConnected) {
                ControlsCard(
                    agentControlEnabled = state.agentControlEnabled,
                    onToggleAgentControl = {
                        if (state.agentControlEnabled) viewModel.toggleAgentControl()
                        else showAgentControlDialog = true
                    },
                    onEmergencyStop = viewModel::emergencyStop,
                    onDisconnect = viewModel::disconnect,
                    onCaptureScreen = viewModel::captureScreen,
                    onStartChat = onStartChat
                )
            }

            // ── Screen capture preview ───────────────────────────────────
            state.screenCapture?.let { capture ->
                ScreenCaptureCard(capture)
            }

            // ── Error ────────────────────────────────────────────────────
            state.error?.let { error ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ── Diagnostics ──────────────────────────────────────────────
            if (state.isConnected || state.connectionState.name == "ERROR") {
                DiagnosticsCard(state = state, visibleTools = viewModel.visibleTools())
            }
        }
    }

    if (showAgentControlDialog) {
        WindowsBridgeAgentControlDialog(
            onConfirm = {
                showAgentControlDialog = false
                viewModel.toggleAgentControl()
            },
            onDismiss = { showAgentControlDialog = false }
        )
    }
}

@Composable
private fun DiagnosticsCard(state: WindowsBridgeUiState, visibleTools: List<String>) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            DiagRow("Connection", state.statusLabel)
            DiagRow("Session ID", state.sessionId ?: "—")
            DiagRow("Agent Control", if (state.agentControlEnabled) "ON" else "OFF")
            DiagRow("Visible tools", "${visibleTools.size}")
            if (visibleTools.isNotEmpty()) {
                Text(
                    visibleTools.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            DiagRow("Protocol version", "1")
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusCard(state: WindowsBridgeUiState) {
    val dotColor = when {
        state.connectionState.name == "ERROR" -> MaterialTheme.colorScheme.error
        state.agentControlEnabled -> Color(0xFFF39C12)
        state.isConnected -> Color(0xFF2ECC71)
        state.isConnecting -> Color(0xFF3498DB)
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    state.statusLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (state.sessionId != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Session: ${state.sessionId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConnectForm(
    host: String,
    port: String,
    token: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    var showToken by remember { mutableStateOf(false) }

    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to Windows Bridge", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Host / IP") },
                placeholder = { Text("192.168.1.x") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                placeholder = { Text("17878") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text("Token (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle token visibility"
                        )
                    }
                }
            )
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = host.isNotBlank()
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Connect")
            }
        }
    }
}

@Composable
private fun ControlsCard(
    agentControlEnabled: Boolean,
    onToggleAgentControl: () -> Unit,
    onEmergencyStop: () -> Unit,
    onDisconnect: () -> Unit,
    onCaptureScreen: () -> Unit,
    onStartChat: () -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Controls", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Agent Control", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (agentControlEnabled) "Mouse, keyboard, window focus enabled"
                        else "View only — input tools hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = agentControlEnabled,
                    onCheckedChange = { onToggleAgentControl() }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCaptureScreen,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Screenshot, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Capture", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onEmergencyStop,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Disconnect", fontSize = 13.sp)
                }
            }

            Button(
                onClick = onStartChat,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Chat, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start Chat with Windows Bridge")
            }
        }
    }
}

@Composable
private fun ScreenCaptureCard(capture: ScreenCaptureState) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Remote Screen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            when {
                capture.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
                capture.error != null -> {
                    Text(
                        capture.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                capture.imageBase64 != null -> {
                    val bitmap = remember(capture.imageBase64) {
                        try {
                            val bytes = Base64.decode(capture.imageBase64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        } catch (_: Exception) { null }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Windows screen capture",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${capture.width}×${capture.height} ${capture.format}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    tool: String,
    risk: String,
    reason: String,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Approval Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text("Tool: $tool", style = MaterialTheme.typography.bodySmall)
            Text("Risk: $risk", style = MaterialTheme.typography.bodySmall)
            if (reason.isNotBlank()) {
                Text("Reason: $reason", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApprove,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2ECC71)
                    )
                ) {
                    Text("Approve", color = Color.White)
                }
                OutlinedButton(
                    onClick = onReject,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Reject")
                }
            }
        }
    }
}
