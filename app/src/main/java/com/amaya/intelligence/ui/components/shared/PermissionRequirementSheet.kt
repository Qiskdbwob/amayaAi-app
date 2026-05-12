package com.amaya.intelligence.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch

enum class PermissionType {
    STORAGE,
    CAMERA,
    NOTIFICATIONS,
    EXACT_ALARM,
    BATTERY_OPTIMIZATION
}

private fun PermissionType.toSpec(): PermissionSheetSpec = when (this) {
    PermissionType.STORAGE -> PermissionSheetSpec(
        icon = Icons.Default.FolderOpen,
        title = "File Access",
        subtitle = "Index local projects.",
        detail = "Amaya reads your workspace so it can build context and search files locally.",
        systemFlow = "Android will show all-files access or storage permission dialog.",
        fallback = "You can always change this later in system app settings.",
        actionLabel = "Grant"
    )
    PermissionType.CAMERA -> PermissionSheetSpec(
        icon = Icons.Default.CameraAlt,
        title = "Camera Access",
        subtitle = "Scan remote pairing QR codes.",
        detail = "Used only when you connect to the IDE remotely.",
        systemFlow = "Android asks camera permission once when scan is requested.",
        fallback = "You can disable camera any time if you stop using remote QR.",
        actionLabel = "Grant"
    )
    PermissionType.NOTIFICATIONS -> PermissionSheetSpec(
        icon = Icons.Default.Notifications,
        title = "Notifications",
        subtitle = "Keep task updates visible.",
        detail = "Used for background jobs, reminders, and completion alerts.",
        systemFlow = "Android requests notification permission so alerts can appear.",
        fallback = "You can mute channels later without disabling everything.",
        actionLabel = "Enable"
    )
    PermissionType.EXACT_ALARM -> PermissionSheetSpec(
        icon = Icons.Default.Alarm,
        title = "Precise Alarms",
        subtitle = "Run time-based actions on time.",
        detail = "Needed for scheduled jobs that should not drift.",
        systemFlow = "Android opens alarm settings to allow exact scheduling.",
        fallback = "You can keep it off if you do not use scheduled automations.",
        actionLabel = "Enable"
    )
    PermissionType.BATTERY_OPTIMIZATION -> PermissionSheetSpec(
        icon = Icons.Default.BatteryChargingFull,
        title = "Battery Optimization",
        subtitle = "Keep background work alive.",
        detail = "Helps Amaya stay stable during long syncs and local tasks.",
        systemFlow = "Android opens battery optimization page for this app.",
        fallback = "You can re-enable optimization later if needed.",
        actionLabel = "Allow"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionRequirementSheet(
    permissionType: PermissionType,
    onGrant: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = permissionType.toSpec()
    val sheetState = rememberLockedModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val gradients = LocalAmayaGradients.current
    val maxSheetHeight = (0.98f * LocalConfiguration.current.screenHeightDp).dp

    fun closeSheet(afterClose: (() -> Unit)? = null) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) afterClose?.invoke()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { closeSheet(onDismiss) },
        sheetState = sheetState,
        properties = lockedModalBottomSheetProperties(),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        shape = responsiveBottomSheetShape(sheetState)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .ignoreNestedScrollForBottomSheet()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp)
            ) {
                Spacer(Modifier.height(92.dp))
                PermissionSheetBody(
                    spec = spec,
                    granted = false,
                    onPrimary = { closeSheet(onGrant) },
                    onSecondary = { closeSheet(onDismiss) },
                    primaryLabel = spec.actionLabel,
                    secondaryLabel = "Maybe later"
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(gradients.modalTopScrim)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = responsiveDragHandleAlpha(sheetState)))
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = spec.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    .compositeOver(MaterialTheme.colorScheme.background)
                            )
                            .clickable { closeSheet(onDismiss) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
