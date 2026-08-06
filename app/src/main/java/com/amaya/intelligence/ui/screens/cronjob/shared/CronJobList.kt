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
import com.amaya.intelligence.ui.components.shared.SettingsEmptyState
import com.amaya.intelligence.ui.screens.amaya.AmayaGroupedSettingsTokens
import com.amaya.intelligence.ui.screens.settings.shared.SettingsSectionCard

import com.amaya.intelligence.ui.screens.amaya.iosAmayaColors

@Composable
fun CronJobList(
    jobs: List<CronJobEntity>,
    onToggle: (CronJobEntity, Boolean) -> Unit,
    onDelete: (CronJobEntity) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 72.dp,
    modifier: Modifier = Modifier
) {
    val colors = iosAmayaColors()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AmayaGroupedSettingsTokens.sectionSpacing),
        contentPadding = PaddingValues(
            start = AmayaGroupedSettingsTokens.contentHorizontalPadding,
            end = AmayaGroupedSettingsTokens.contentHorizontalPadding,
            top = topPadding,
            bottom = AmayaGroupedSettingsTokens.screenContentBottomSpacer
        )
    ) {
        if (jobs.isEmpty()) {
            item {
                SettingsEmptyState(
                    title = "No reminders yet",
                    subtitle = "Tap + to schedule a reminder",
                    icon = Icons.Default.Alarm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AmayaGroupedSettingsTokens.emptyStateListTopSpacing)
                )
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
