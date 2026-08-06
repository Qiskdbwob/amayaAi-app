package com.amaya.intelligence.ui.screens.project.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.domain.models.ProjectFileEntry
import com.amaya.intelligence.ui.components.shared.getFileIcon

private data class IosProjectColors(
    val groupSurface: Color,
    val border: Color,
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val chevronTint: Color
)

@Composable
private fun iosProjectColors(): IosProjectColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosProjectColors(
            groupSurface = Color(0xFF1C1C1E),
            border = Color.White.copy(alpha = 0.10f),
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            chevronTint = Color(0xFFEBEBF5).copy(alpha = 0.35f)
        )
    } else {
        IosProjectColors(
            groupSurface = Color.White,
            border = Color.Black.copy(alpha = 0.08f),
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            chevronTint = Color(0xFF3C3C43).copy(alpha = 0.35f)
        )
    }
}

@Composable
fun FileListItem(
    item: ProjectFileEntry,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onClick: (ProjectFileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = iosProjectColors()
    val isDirectory = item.type == "directory"
    val itemShape = when {
        isFirst && isLast -> RoundedCornerShape(16.dp)
        isFirst           -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        isLast            -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
        else              -> RoundedCornerShape(0.dp)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(itemShape)
            .clickable { onClick(item) },
        shape = itemShape,
        color = colors.groupSurface,
        border = BorderStroke(0.7.dp, colors.border),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isDirectory) Icons.Default.Folder else getFileIcon(item.name),
                    contentDescription = null,
                    tint = colors.iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                ),
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            if (isDirectory && item.name != "..") {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.chevronTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
