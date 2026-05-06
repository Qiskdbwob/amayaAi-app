package com.amaya.intelligence.ui.components.shared

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.data.remote.api.CodexAuthManager
import com.amaya.intelligence.data.remote.api.CodexAuthState
import kotlinx.coroutines.delay

/**
 * Bottom sheet that presents the two Codex authentication options:
 * 1. Sign in with Browser (Local Server PKCE)
 * 2. Sign in with Device Code (RFC 8628)
 *
 * When in Device Code mode, displays the user code and verification URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexAuthSheet(
    codexAuthManager: CodexAuthManager,
    onDismiss: () -> Unit,
    onAuthenticated: () -> Unit
) {
    val sheetState = rememberLockedModalBottomSheetState()
    val authState by codexAuthManager.authState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        codexAuthManager.syncAuthStateFromStorage()
    }

    // Auto-dismiss on successful auth
    LaunchedEffect(authState) {
        if (authState is CodexAuthState.Authenticated) {
            delay(1500)
            onAuthenticated()
        }
    }

    StandardModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = {
            codexAuthManager.cancel()
            onDismiss()
        },
        title = "OpenAI Login",
        icon = Icons.Default.Key,
        subtitle = "Sign in with OpenAI subscription"
    ) {
        when (val state = authState) {
            is CodexAuthState.Idle,
            is CodexAuthState.Error -> {
                // Show method picker
                CodexAuthMethodPicker(
                    errorMessage = (state as? CodexAuthState.Error)?.message,
                    onBrowserLogin = { codexAuthManager.startLocalServerLogin(context) },
                    onDeviceCodeLogin = { codexAuthManager.startDeviceCodeLogin() }
                )
            }

            is CodexAuthState.Starting,
            is CodexAuthState.ExchangingToken -> {
                CodexAuthLoading(
                    message = if (state is CodexAuthState.Starting) "Initializing…"
                              else "Exchanging token…"
                )
            }

            is CodexAuthState.WaitingForBrowser -> {
                CodexAuthLoading(message = "Waiting for browser login…")
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                ) {
                    Text(
                        text = "After OpenAI authorizes the login, the browser should show “Successfully logged in”. If the browser only shows an OpenAI logged-in page but Amaya already received the token, tap the button below.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { codexAuthManager.syncAuthStateFromStorage() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10A37F), contentColor = Color.White)
                ) { Text("I finished in browser") }
                OutlinedButton(
                    onClick = {
                        codexAuthManager.cancel()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Cancel") }
            }

            is CodexAuthState.DeviceCodeReady -> {
                DeviceCodeView(
                    userCode = state.userCode,
                    verificationUri = state.verificationUri,
                    expiresInSeconds = state.expiresInSeconds,
                    onCancel = { codexAuthManager.cancel() }
                )
            }

            is CodexAuthState.Authenticated -> {
                CodexAuthSuccess(email = state.email)
            }
        }
    }
}

// ── Method Picker ───────────────────────────────────────────────────

@Composable
private fun CodexAuthMethodPicker(
    errorMessage: String?,
    onBrowserLogin: () -> Unit,
    onDeviceCodeLogin: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Error display
        AnimatedVisibility(visible = errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Info note
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Sign in with your OpenAI account to use subscription models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }

        // Option 1: Browser Login
        AuthOptionCard(
            icon = Icons.Default.OpenInBrowser,
            title = "Sign in with Browser",
            subtitle = "Opens login page — auto-detects when done",
            recommended = true,
            onClick = onBrowserLogin
        )

        // Option 2: Device Code
        AuthOptionCard(
            icon = Icons.Default.PhonelinkSetup,
            title = "Sign in with Device Code",
            subtitle = "Get a code, enter it on any browser",
            recommended = false,
            onClick = onDeviceCodeLogin
        )

        Spacer(Modifier.height(4.dp))
    }
}

// ── Auth Option Card ────────────────────────────────────────────────

@Composable
private fun AuthOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    recommended: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (recommended)
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        else
            Color.Transparent,
        border = if (!recommended)
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (recommended)
                            Brush.linearGradient(listOf(Color(0xFF10A37F), Color(0xFF1A7F64)))
                        else
                            Brush.linearGradient(listOf(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                            ))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (recommended) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (recommended) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF10A37F).copy(alpha = 0.15f)
                        ) {
                            Text(
                                "Recommended",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF10A37F)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

// ── Device Code View ────────────────────────────────────────────────

@Composable
private fun DeviceCodeView(
    userCode: String,
    verificationUri: String,
    expiresInSeconds: Int,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(expiresInSeconds) }

    // Countdown timer
    LaunchedEffect(expiresInSeconds) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Step indicator
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StepRow(number = "1", text = "Copy the code below")
                StepRow(number = "2", text = "Open the verification link")
                StepRow(number = "3", text = "Paste the code and sign in")
            }
        }

        // User code display
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Your code",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // The big code display
                Text(
                    text = userCode,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                // Copy button
                Surface(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OpenAI Code", userCode))
                        copied = true
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = if (copied) Color(0xFF10A37F).copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (copied) Color(0xFF10A37F)
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (copied) "Copied!" else "Copy code",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (copied) Color(0xFF10A37F)
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Timer
                val minutes = remainingSeconds / 60
                val seconds = remainingSeconds % 60
                Text(
                    "Expires in ${minutes}:${"%02d".format(seconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remainingSeconds < 60) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        // Waiting indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF10A37F)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Waiting for authorization…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Open verification link button
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri))
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF10A37F),
                contentColor = Color.White
            )
        ) {
            Icon(
                Icons.Default.Launch,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Open Verification Page", fontWeight = FontWeight.SemiBold)
        }

        // Cancel
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Cancel") }
    }
}

@Composable
private fun StepRow(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Loading State ───────────────────────────────────────────────────

@Composable
private fun CodexAuthLoading(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
            color = Color(0xFF10A37F)
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Success State ───────────────────────────────────────────────────

@Composable
private fun CodexAuthSuccess(email: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xFF10A37F).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color(0xFF10A37F)
            )
        }

        Text(
            "Signed in successfully",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (!email.isNullOrBlank()) {
            Text(
                email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
