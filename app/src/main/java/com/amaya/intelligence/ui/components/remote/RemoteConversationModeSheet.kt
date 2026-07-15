package com.amaya.intelligence.ui.components.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.amaya.intelligence.domain.models.ConversationMode
import com.amaya.intelligence.ui.theme.LocalAmayaGradients



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteConversationModeSheet(
    currentMode: ConversationMode,
    onSelect: (ConversationMode) -> Unit,
    onDismiss: () -> Unit
) {
    com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Conversation Mode"
    ) {
        RemoteModeItem(
            mode = ConversationMode.PLANNING,
            isSelected = currentMode == ConversationMode.PLANNING,
            onClick = { dismiss { onSelect(ConversationMode.PLANNING) } }
        )

        RemoteModeItem(
            mode = ConversationMode.FAST,
            isSelected = currentMode == ConversationMode.FAST,
            onClick = { dismiss { onSelect(ConversationMode.FAST) } }
        )
    }
}

@Composable
private fun RemoteModeItem(
    mode: ConversationMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val (label, description) = when (mode) {
        ConversationMode.PLANNING -> "Planning" to "Agent can plan before executing tasks. Use for deep research, complex tasks, or collaborative work"
        ConversationMode.FAST -> "Fast" to "Agent will execute tasks directly. Use for simple tasks that can be completed faster"
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
