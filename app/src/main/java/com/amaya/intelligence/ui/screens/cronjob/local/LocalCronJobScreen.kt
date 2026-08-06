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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlarm
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.amaya.intelligence.data.repository.CronJobRepository
import com.amaya.intelligence.ui.components.shared.PermissionRequirementSheet
import com.amaya.intelligence.ui.components.shared.PermissionType
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.screens.amaya.iosAmayaColors
import com.amaya.intelligence.ui.screens.cronjob.shared.CronJobEditSheet
import com.amaya.intelligence.ui.screens.cronjob.shared.CronJobList
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalCronJobScreen(
    title: String = "Reminders",
    ownerAgentId: Long? = null,
    onNavigateBack: () -> Unit,
    cronJobRepository: CronJobRepository
) {
    val colors = iosAmayaColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val jobs by cronJobRepository.jobsForAgent(ownerAgentId).collectAsState(initial = emptyList())

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
        contentWindowInsets = WindowInsets(0.dp),
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

            com.amaya.intelligence.ui.screens.amaya.AmayaTopScrim(
                Modifier.align(Alignment.TopCenter)
            )

            TopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 12.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    SettingsBackButton(onClick = onNavigateBack)
                },
                actions = {
                    com.amaya.intelligence.ui.components.shared.AmayaTopBarButton(
                        icon = Icons.Default.AddAlarm,
                        onClick = { openAddFlow() },
                        contentDescription = "Add Reminder",
                        modifier = Modifier.padding(end = 12.dp)
                    )
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
            ownerAgentId = ownerAgentId,
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
