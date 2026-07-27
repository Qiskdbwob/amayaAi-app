package com.amaya.intelligence.ui.screens.cronjob.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.local.entity.CronJobEntity
import com.amaya.intelligence.ui.screens.settings.shared.SettingsSectionCard

private data class IosCronJobListColors(
    val iconBackground: Color,
    val iconTint: Color,
    val secondaryText: Color,
    val separator: Color
)

@Composable
private fun iosCronJobListColors(): IosCronJobListColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosCronJobListColors(
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            separator = Color.White.copy(alpha = 0.10f)
        )
    } else {
        IosCronJobListColors(
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            separator = Color(0xFF3C3C43).copy(alpha = 0.13f)
        )
    }
}

@Composable
fun CronJobList(
    jobs: List<CronJobEntity>,
    onToggle: (CronJobEntity, Boolean) -> Unit,
    onDelete: (CronJobEntity) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 72.dp,
    modifier: Modifier = Modifier
) {
    val colors = iosCronJobListColors()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topPadding,
            bottom = 100.dp
        )
    ) {
        if (jobs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(colors.iconBackground),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Alarm,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = colors.iconTint.copy(alpha = 0.5f)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No reminders yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.secondaryText
                        )
                        Text(
                            "Tap + to schedule a reminder",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryText.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        if (jobs.isNotEmpty()) {
            item {
                SettingsSectionCard(title = "Automation") {
                    jobs.forEachIndexed { index, job ->
                        CronJobCard(
                            job = job,
                            onToggle = { active -> onToggle(job, active) },
                            onDelete = { onDelete(job) }
                        )
                        if (index < jobs.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 58.dp, end = 16.dp),
                                color = colors.separator,
                                thickness = 0.7.dp
                            )
                        }
                    }
                }
            }
        }
    }
}
