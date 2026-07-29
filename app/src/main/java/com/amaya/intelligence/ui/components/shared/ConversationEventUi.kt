package com.amaya.intelligence.ui.components.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.domain.models.ConversationEvent
import com.amaya.intelligence.domain.models.ConversationEventState

private val EVENT_LINE = Regex("^---\\s*(.+?)\\s*---$", RegexOption.DOT_MATCHES_ALL)

internal fun parseSharedConversationEvent(text: String): String? =
    EVENT_LINE.matchEntire(text.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)

@Composable
internal fun SharedConversationEvent(event: ConversationEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isFailed = event.state == ConversationEventState.FAILED
        
        val color = if (isFailed) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
        
        val textColor = if (isFailed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        HorizontalDivider(modifier = Modifier.weight(1f), color = color)
        
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isFailed) Icons.Rounded.Error else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            
            val textToShow = if (isFailed) {
                "${event.label} ${event.state.displayLabel.replaceFirstChar { it.uppercase() }}"
            } else {
                event.label
            }
            
            Text(
                text = textToShow,
                textAlign = TextAlign.Center,
                color = textColor,
                style = MaterialTheme.typography.labelMedium
            )
        }
        
        HorizontalDivider(modifier = Modifier.weight(1f), color = color)
    }
}

