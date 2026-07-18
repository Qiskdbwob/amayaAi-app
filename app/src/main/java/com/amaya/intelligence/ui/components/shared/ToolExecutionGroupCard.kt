package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolInfoIcon
import com.amaya.intelligence.domain.models.ToolStatus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class ToolExecutionGroup(
    val key: String,
    val startIndex: Int,
    val endIndex: Int,
    val executions: List<ToolExecution>,
    val isActive: Boolean
)

/** Contiguous local tool calls sharing [toolGroupKey]; only groups with count >= 2. */
internal fun buildToolExecutionGroups(
    steps: List<MessageStep>,
    autoExpandLatest: Boolean
): List<ToolExecutionGroup> {
    val groups = mutableListOf<ToolExecutionGroup>()
    var index = 0
    while (index < steps.size) {
        val execution = (steps[index] as? MessageStep.ToolCall)?.execution
        val key = execution?.toolGroupKey()
        if (key == null) {
            index++
            continue
        }

        val start = index
        val children = mutableListOf(execution)
        while (index + 1 < steps.size) {
            val next = (steps[index + 1] as? MessageStep.ToolCall)?.execution ?: break
            if (next.toolGroupKey() != key) break
            children += next
            index++
        }
        if (children.size >= 2) {
            groups += ToolExecutionGroup(
                key = key,
                startIndex = start,
                endIndex = index,
                executions = children,
                isActive = autoExpandLatest && index == steps.lastIndex
            )
        }
        index++
    }
    return groups
}

private fun ToolExecution.toolGroupKey(): String? {
    if (!metadata["source"].equals("local", ignoreCase = true)) return null
    return when (name) {
        "read_file", "write_file", "edit_file", "create_directory", "delete_file", "undo_change",
        "list_files", "find_files", "run_shell", "web_search", "create_reminder", "update_memory",
        "memory_manage", "skill_view", "skill_manage", "session_search" -> name
        else -> null
    }
}

/** Non-collapsible group wrapper holding collapsible child [ToolCallCard]s. */
@Composable
internal fun ToolExecutionGroupCard(
    group: ToolExecutionGroup,
    onToolAccept: ((ToolExecution) -> Unit)? = null,
    onToolDecline: ((ToolExecution) -> Unit)? = null,
    onLocalhostLinkClick: ((String) -> Unit)? = null,
    onInteraction: () -> Unit = {}
) {
    val hasError = group.executions.any { it.status == ToolStatus.ERROR }
    val isRunning = group.executions.any { it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING }
    var expanded by remember(group.key, group.executions.first().toolCallId) { mutableStateOf(group.isActive) }
    LaunchedEffect(group.isActive) {
        expanded = group.isActive
    }
    val isDark = isSystemInDarkTheme()
    val tint = when {
        hasError -> MaterialTheme.colorScheme.error
        isRunning -> Color(0xFF007AFF)
        else -> MaterialTheme.colorScheme.primary
    }
    val background = when {
        hasError -> tint.copy(alpha = if (isDark) 0.10f else 0.06f)
        isRunning -> tint.copy(alpha = if (isDark) 0.08f else 0.04f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        onInteraction()
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolLeadIconPill(groupIcon(group.key), tint)
                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                Text(
                    text = groupTitle(group),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isRunning) {
                    Icon(Icons.Default.Autorenew, null, modifier = Modifier.size(14.dp), tint = tint)
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = ToolCallMotion.enter,
                exit = ToolCallMotion.exit
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    group.executions.forEach { execution ->
                        key(execution.toolCallId) {
                            ToolCallCard(
                                execution = execution.copy(metadata = execution.metadata + ("groupedChild" to "true")),
                                onAccept = onToolAccept?.let { callback -> { callback(execution) } },
                                onDecline = onToolDecline?.let { callback -> { callback(execution) } },
                                onLocalhostLinkClick = onLocalhostLinkClick,
                                onInteraction = onInteraction
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun groupTitle(group: ToolExecutionGroup): String {
    val count = group.executions.size
    val successful = group.executions.all { it.status == ToolStatus.SUCCESS }
    return when (group.key) {
        "read_file" -> "Read $count files"
        "write_file" -> "${if (successful) "Wrote" else "Write"} $count files"
        "edit_file" -> "${if (successful) "Edited" else "Edit"} $count files"
        "create_directory" -> "${if (successful) "Created" else "Create"} $count directories"
        "delete_file" -> "${if (successful) "Deleted" else "Delete"} $count items"
        "undo_change" -> "${if (successful) "Restored" else "Restore"} $count files"
        "list_files" -> "${if (successful) "Listed" else "List"} $count directories"
        "find_files" -> "${if (successful) "Ran" else "Run"} $count file searches"
        "run_shell" -> "${if (successful) "Ran" else "Run"} $count commands"
        "web_search" -> "${if (successful) "Ran" else "Run"} $count web searches"
        "create_reminder" -> "${if (successful) "Scheduled" else "Schedule"} $count reminders"
        "update_memory" -> "${if (successful) "Saved" else "Save"} $count memories"
        "memory_manage" -> "Manage $count memories"
        "skill_view" -> "Read $count skills"
        "skill_manage" -> "Manage $count skills"
        "session_search" -> "Search $count previous chats"
        else -> "$count tools"
    }
}

private fun groupIcon(key: String): ToolInfoIcon = when (key) {
    "read_file" -> ToolInfoIcon.READ
    "write_file" -> ToolInfoIcon.WRITE
    "edit_file" -> ToolInfoIcon.EDIT
    "create_directory" -> ToolInfoIcon.FOLDER
    "delete_file" -> ToolInfoIcon.DELETE
    "undo_change" -> ToolInfoIcon.EDIT
    "list_files" -> ToolInfoIcon.LIST
    "find_files", "session_search" -> ToolInfoIcon.SEARCH
    "run_shell" -> ToolInfoIcon.RUN
    "web_search" -> ToolInfoIcon.WORLD
    "create_reminder" -> ToolInfoIcon.TASK
    "update_memory", "memory_manage" -> ToolInfoIcon.BRAIN
    "skill_view", "skill_manage" -> ToolInfoIcon.BOOK
    else -> ToolInfoIcon.TASK
}
