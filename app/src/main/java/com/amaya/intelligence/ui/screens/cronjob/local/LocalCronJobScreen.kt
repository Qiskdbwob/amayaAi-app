package com.amaya.intelligence.ui.screens.cronjob.local

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.repository.CronJobRepository
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.screens.cronjob.shared.CronJobEditSheet
import com.amaya.intelligence.ui.screens.cronjob.shared.CronJobList
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

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

    var showAlarmPermissionDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!cronJobRepository.canScheduleExact()) {
            showAlarmPermissionDialog = true
        }
    }
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp

    var showAddSheet by remember { mutableStateOf(false) }

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
                            .clickable { showAddSheet = true },
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

    if (showAlarmPermissionDialog) {
        com.amaya.intelligence.ui.components.shared.PermissionRequirementSheet(
            permissionType = com.amaya.intelligence.ui.components.shared.PermissionType.EXACT_ALARM,
            onGrant = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            },
            onDismiss = { showAlarmPermissionDialog = false }
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
