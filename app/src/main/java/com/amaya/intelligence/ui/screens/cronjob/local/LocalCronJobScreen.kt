package com.amaya.intelligence.ui.screens.cronjob.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlarm
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.amaya.intelligence.data.repository.CronJobRepository
import com.amaya.intelligence.ui.components.shared.PermissionRequirementSheet
import com.amaya.intelligence.ui.components.shared.PermissionType
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.screens.cronjob.shared.CronJobEditSheet
import com.amaya.intelligence.ui.screens.cronjob.shared.CronJobList
import kotlinx.coroutines.launch

private data class IosCronJobScreenColors(
    val groupedBackground: Color,
    val iconBackground: Color,
    val iconTint: Color
)

@Composable
private fun iosCronJobScreenColors(): IosCronJobScreenColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosCronJobScreenColors(
            groupedBackground = Color(0xFF0B0B0F),
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC)
        )
    } else {
        IosCronJobScreenColors(
            groupedBackground = Color(0xFFF2F2F7),
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalCronJobScreen(
    onNavigateBack: () -> Unit,
    cronJobRepository: CronJobRepository
) {
    val colors = iosCronJobScreenColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val jobs by cronJobRepository.allJobs.collectAsState(initial = emptyList())

    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp

    var showAddSheet by remember { mutableStateOf(false) }
    var showNotificationPermissionSheet by remember { mutableStateOf(false) }
    var showAlarmPermissionSheet by remember { mutableStateOf(false) }

    fun openAddFlow() {
        when {
            !canPostNotifications(context) -> showNotificationPermissionSheet = true
            !cronJobRepository.canScheduleExact() -> showAlarmPermissionSheet = true
            else -> showAddSheet = true
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            showNotificationPermissionSheet = false
            if (granted) {
                if (cronJobRepository.canScheduleExact()) {
                    showAddSheet = true
                } else {
                    showAlarmPermissionSheet = true
                }
            }
        }
    )

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(colors.groupedBackground)) {
            CronJobList(
                jobs = jobs,
                onToggle = { job, active ->
                    scope.launch { cronJobRepository.setActive(job.id, active) }
                },
                onDelete = { job ->
                    scope.launch {
                        cronJobRepository.deleteJob(job.id)
                        snackbarHostState.showSnackbar("Reminder deleted")
                    }
                },
                topPadding = topPadding
            )

            TopAppBar(
                title = {
                    Text(
                        "Reminders",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 12.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    SettingsBackButton(onClick = onNavigateBack)
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.iconBackground)
                            .clickable { openAddFlow() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AddAlarm,
                            "Add Reminder",
                            modifier = Modifier.size(20.dp),
                            tint = colors.iconTint
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                modifier = Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp),
                windowInsets = WindowInsets(0.dp)
            )
        }
    }

    if (showNotificationPermissionSheet) {
        PermissionRequirementSheet(
            permissionType = PermissionType.NOTIFICATIONS,
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    showNotificationPermissionSheet = false
                    openAddFlow()
                }
            },
            onDismiss = { showNotificationPermissionSheet = false }
        )
    }

    if (showAlarmPermissionSheet) {
        PermissionRequirementSheet(
            permissionType = PermissionType.EXACT_ALARM,
            onGrant = {
                showAlarmPermissionSheet = false
                openExactAlarmSettings(context)
            },
            onDismiss = { showAlarmPermissionSheet = false }
        )
    }

    if (showAddSheet) {
        CronJobEditSheet(
            onDismiss = { showAddSheet = false },
            onAdd = { job ->
                showAddSheet = false
                scope.launch {
                    cronJobRepository.addJob(job)
                    snackbarHostState.showSnackbar("Reminder set ✓")
                }
            }
        )
    }
}

private fun canPostNotifications(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        })
    }
}
