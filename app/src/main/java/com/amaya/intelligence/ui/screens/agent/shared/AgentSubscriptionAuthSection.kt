package com.amaya.intelligence.ui.screens.agent.shared

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SubscriptionAuthStepContent(authUi: AgentSubscriptionAuthUi?) {
    val step = authUi?.step ?: SubscriptionAuthStep.Methods
    when (step) {
        is SubscriptionAuthStep.Waiting -> SubscriptionAuthWaitingStep(
            label = step.label,
            onDeviceCodeLogin = authUi?.onDeviceCodeSignIn,
            onCancel = authUi?.onCancel
        )
        is SubscriptionAuthStep.DeviceCode -> SubscriptionDeviceCodeStep(
            userCode = step.userCode,
            verificationUri = step.verificationUri,
            expiresInSeconds = step.expiresInSeconds,
            onCancel = authUi?.onCancel
        )
        is SubscriptionAuthStep.Error -> SubscriptionAuthMethodsStep(
            errorMessage = step.message,
            onBrowserLogin = authUi?.onBrowserSignIn,
            onDeviceCodeLogin = authUi?.onDeviceCodeSignIn
        )
        SubscriptionAuthStep.Methods -> SubscriptionAuthMethodsStep(
            errorMessage = null,
            onBrowserLogin = authUi?.onBrowserSignIn,
            onDeviceCodeLogin = authUi?.onDeviceCodeSignIn
        )
    }
}

@Composable
internal fun SubscriptionConnectionCard(authUi: AgentSubscriptionAuthUi?) {
    val authenticated = authUi?.authenticated == true
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (authenticated) Color(0xFF10A37F).copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (authenticated) Icons.Default.CheckCircle else Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (authenticated) Color(0xFF10A37F) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (authenticated) "Connected" else "Not connected", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (authenticated) authUi?.accountLabel ?: "Subscription active" else "Sign in to enable subscription models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (authenticated) Color(0xFF10A37F) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (authenticated) {
                TextButton(onClick = { authUi?.onSignOut?.invoke() }) { Text("Sign out") }
            } else {
                Button(
                    onClick = { authUi?.onBrowserSignIn?.invoke() },
                    enabled = authUi?.onBrowserSignIn != null,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Sign in") }
            }
        }
    }
}

@Composable
private fun SubscriptionAuthMethodsStep(
    errorMessage: String?,
    onBrowserLogin: (() -> Unit)?,
    onDeviceCodeLogin: (() -> Unit)?
) {
    errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                message,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
    AuthActionCard(
        icon = Icons.Default.OpenInBrowser,
        title = "Sign in with browser",
        subtitle = "Recommended",
        onClick = { onBrowserLogin?.invoke() },
        enabled = onBrowserLogin != null
    )
    AuthActionCard(
        icon = Icons.Default.PhonelinkSetup,
        title = "Use device code",
        subtitle = "Fallback",
        onClick = { onDeviceCodeLogin?.invoke() },
        enabled = onDeviceCodeLogin != null
    )
}

@Composable
private fun SubscriptionAuthWaitingStep(
    label: String,
    onDeviceCodeLogin: (() -> Unit)?,
    onCancel: (() -> Unit)?
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp, color = Color(0xFF10A37F))
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
    OutlinedButton(
        onClick = { onDeviceCodeLogin?.invoke() },
        enabled = onDeviceCodeLogin != null,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text("Use device code instead") }
    TextButton(
        onClick = { onCancel?.invoke() },
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) { Text("Cancel") }
}

@Composable
private fun SubscriptionDeviceCodeStep(
    userCode: String,
    verificationUri: String,
    expiresInSeconds: Int,
    onCancel: (() -> Unit)?
) {
    val context = LocalContext.current
    var copied by remember(userCode) { mutableStateOf(false) }
    var remainingSeconds by remember(expiresInSeconds) { mutableIntStateOf(expiresInSeconds) }

    LaunchedEffect(expiresInSeconds) {
        while (remainingSeconds > 0) {
            kotlinx.coroutines.delay(1000)
            remainingSeconds--
        }
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                userCode,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                ),
                textAlign = TextAlign.Center
            )
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            Text("Expires in $minutes:${"%02d".format(seconds)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Subscription code", userCode))
                    copied = true
                }
            ) {
                Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (copied) "Copied" else "Copy code")
            }
        }
    }
    Button(
        onClick = { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(verificationUri)) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10A37F), contentColor = Color.White)
    ) {
        Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Open verification page")
    }
    TextButton(
        onClick = { onCancel?.invoke() },
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) { Text("Cancel") }
}

@Composable
private fun AuthActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF10A37F).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Color(0xFF10A37F), modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
