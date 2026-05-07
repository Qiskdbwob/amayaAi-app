package com.amaya.intelligence.ui.screens.cronjob.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.data.local.entity.CronJobEntity
import com.amaya.intelligence.data.local.entity.CronRecurringType
import com.amaya.intelligence.data.local.entity.CronSessionMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class IosCronJobCardColors(
    val groupSurface: Color,
    val border: Color,
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tagBackground: Color
)

@Composable
private fun iosCronJobCardColors(): IosCronJobCardColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosCronJobCardColors(
            groupSurface = Color(0xFF1C1C1E),
            border = Color.White.copy(alpha = 0.10f),
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            tagBackground = Color(0xFF2C2C2E)
        )
    } else {
        IosCronJobCardColors(
            groupSurface = Color.White,
            border = Color.Black.copy(alpha = 0.08f),
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            tagBackground = Color(0xFFF2F2F7)
        )
    }
}

@Composable
fun CronJobCard(
    job: CronJobEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = iosCronJobCardColors()
    val fmt = remember { SimpleDateFormat("EEE, dd MMM yyyy · HH:mm", Locale.getDefault()) }
    val timeStr = fmt.format(Date(job.triggerTimeMillis))
    val isPast = job.triggerTimeMillis < System.currentTimeMillis() && job.recurringType == CronRecurringType.ONCE
    val isActive = job.isActive && !isPast

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.groupSurface,
        border = BorderStroke(0.7.dp, colors.border),
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colors.iconBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when (job.recurringType) {
                            CronRecurringType.ONCE -> Icons.Default.Alarm
                            CronRecurringType.DAILY -> Icons.Default.Repeat
                            CronRecurringType.WEEKLY -> Icons.Default.DateRange
                        },
                        contentDescription = null,
                        tint = colors.iconTint,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        job.title.ifBlank { "Reminder" },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            lineHeight = 19.sp
                        ),
                        color = colors.primaryText
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        timeStr,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                        color = colors.secondaryText
                    )
                }
                Switch(
                    checked = isActive,
                    onCheckedChange = { if (!isPast) onToggle(it) },
                    enabled = !isPast
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (job.prompt.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    job.prompt,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                    color = colors.secondaryText,
                    maxLines = 2
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = colors.tagBackground
                ) {
                    Text(
                        job.recurringType.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = colors.secondaryText
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = colors.tagBackground
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (job.sessionMode == CronSessionMode.CONTINUE) Icons.Default.Forum else Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = colors.secondaryText
                        )
                        Text(
                            if (job.sessionMode == CronSessionMode.CONTINUE) "Continue" else "New chat",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.secondaryText
                        )
                    }
                }
                if (isPast) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
                    ) {
                        Text(
                            "Expired",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
