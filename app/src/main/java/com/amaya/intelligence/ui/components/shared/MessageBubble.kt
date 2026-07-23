package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.data.remote.api.MessageRole
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun MessageBubble(
    message: UiMessage,
    hideThinkingHeader: Boolean = false,
    onToolAccept: ((ToolExecution) -> Unit)? = null,
    onToolDecline: ((ToolExecution) -> Unit)? = null,
    onLocalhostLinkClick: ((String) -> Unit)? = null,
    onInteraction: () -> Unit = {},
    onThinkingScroll: () -> Unit = {}
) {
    val isUser = message.role == MessageRole.USER
    if (isUser) {
        val screenWidth = LocalConfiguration.current.screenWidthDp
        val maxBubbleWidthDp = (screenWidth * 0.75f).dp
        val hPad = 14.dp
        val vPad = 10.dp

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            // Display image attachments
            if (message.attachments.isNotEmpty()) {
                val imageAttachments = message.attachments.filter { it.mimeType.startsWith("image/") }
                if (imageAttachments.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = maxBubbleWidthDp)
                            .padding(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        imageAttachments.forEach { attachment ->
                            val bitmap = remember(attachment.dataBase64) {
                                try {
                                    val bytes = Base64.decode(attachment.dataBase64, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } catch (_: Exception) { null }
                            }
                            
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = attachment.fileName.ifBlank { "Attached image" },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
                        }
                    }
                }
            }
            
            val delegationSource = message.metadata["sourceAgentName"]
                ?.takeIf { message.metadata["delegation"] == "incoming" }
            val bubbleColor = if (delegationSource == null) Color(0xFF0A84FF) else Color(0xFF6D5BD0)
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(21.dp, 21.dp, 6.dp, 21.dp),
                modifier = Modifier.widthIn(max = maxBubbleWidthDp)
            ) {
                Column {
                    if (delegationSource != null) {
                        Text(
                            text = "Delegation from $delegationSource",
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = hPad, top = 9.dp, end = hPad, bottom = 7.dp)
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.22f), thickness = 1.dp)
                    }
                    MarkdownText(
                        text = message.formattedContent ?: message.content,
                        color = Color.White,
                        modifier = Modifier.padding(
                            start = hPad,
                            top = if (delegationSource == null) vPad else 9.dp,
                            end = hPad,
                            bottom = vPad
                        ),
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (message.steps.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val finalTextIndex = remember(message.steps, message.metadata) {
                            finalVisibleTextIndexForSummary(message)
                        }
                        if (finalTextIndex != null) {
                            // Steps branch — completed turn wrapped in a
                            // "Worked for {duration} · N tools" card. Timeline
                            // steps preserve provider event order.
                            val summarySteps = summaryTimelineSteps(message, finalTextIndex)
                            val finalTextStep = message.steps[finalTextIndex] as MessageStep.Text
                            WorkSummaryCard(
                                message = message,
                                steps = summarySteps,
                                onInteraction = onInteraction
                            ) {
                                StepTimeline(
                                    steps = summarySteps,
                                    hideThinkingHeader = hideThinkingHeader,
                                    onToolAccept = onToolAccept,
                                    onToolDecline = onToolDecline,
                                    onLocalhostLinkClick = onLocalhostLinkClick,
                                    onInteraction = onInteraction
                                )
                            }
                            val finalText = if (message.metadata["source"].equals("remote", ignoreCase = true) && message.content.isNotBlank()) {
                                message.formattedContent ?: message.content
                            } else {
                                finalTextStep.formattedContent ?: finalTextStep.content
                            }
                            if (finalText.isNotBlank()) {
                                key(finalTextStep.id) {
                                    AssistantTextWithThinking(
                                        text = finalText,
                                        hideThinkingHeader = hideThinkingHeader,
                                        onLocalhostLinkClick = onLocalhostLinkClick
                                    )
                                }
                            }
                        } else {
                            // Field reasoning is legacy fallback only. New
                            // reasoning lives in ordered MessageStep.Thinking.
                            if (message.steps.none { it is MessageStep.Thinking }) {
                                MessageThinkingBlock(
                                    message = message,
                                    hideWhenDuplicate = hideThinkingHeader,
                                    onLocalhostLinkClick = onLocalhostLinkClick,
                                    onBodyScroll = onThinkingScroll
                                )
                            }
                            StepTimeline(
                                steps = message.steps,
                                hideThinkingHeader = hideThinkingHeader,
                                onToolAccept = onToolAccept,
                                onToolDecline = onToolDecline,
                                onLocalhostLinkClick = onLocalhostLinkClick,
                                onInteraction = onInteraction
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (message.toolExecutions.isNotEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val visibleExecutions = message.toolExecutions.filter { it.name != "update_todo" }
                                val groups = buildToolExecutionGroups(
                                    visibleExecutions.map { MessageStep.ToolCall(execution = it) }
                                )

                                visibleExecutions.forEach { execution ->
                                    val group = groups.find {
                                        it.executions.contains(execution) || it.parentToolCallId == execution.toolCallId
                                    }
                                    when {
                                        group != null && (
                                            execution == group.executions.first() || group.parentToolCallId == execution.toolCallId
                                        ) -> {
                                            key(group.key + "_" + execution.toolCallId) {
                                                ToolExecutionGroupCard(
                                                    group = group,
                                                    onToolAccept = onToolAccept,
                                                    onToolDecline = onToolDecline,
                                                    onLocalhostLinkClick = onLocalhostLinkClick,
                                                    onInteraction = onInteraction
                                                )
                                            }
                                        }
                                        group != null -> Unit // Skip other group members
                                        else -> {
                                            key(execution.toolCallId) {
                                                ToolCallCard(
                                                    execution = execution,
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

                        // Reasoning accumulated from provider thinking deltas (e.g.
                        // DeepSeek reasoning_content). Rendered via the dedicated
                        // ThinkingCard — single source of truth for both
                        // reasoning_delta and inline <think> tags.
                        MessageThinkingBlock(
                            message = message,
                            hideWhenDuplicate = hideThinkingHeader,
                            onLocalhostLinkClick = onLocalhostLinkClick,
                            onBodyScroll = onThinkingScroll
                        )

                        if (message.content.isNotBlank()) {
                            val content = message.formattedContent ?: message.content
                            AssistantTextWithThinking(
                                text = content,
                                hideThinkingHeader = hideThinkingHeader,
                                onLocalhostLinkClick = onLocalhostLinkClick
                            )
                        }
                    }
                }
            }
        }


    }
}

@Composable
private fun StepTimeline(
    steps: List<MessageStep>,
    hideThinkingHeader: Boolean,
    onToolAccept: ((ToolExecution) -> Unit)?,
    onToolDecline: ((ToolExecution) -> Unit)?,
    onLocalhostLinkClick: ((String) -> Unit)?,
    onInteraction: () -> Unit
) {
    val groups = buildToolExecutionGroups(steps)

    steps.forEachIndexed { idx, step ->
        when (step) {
            is MessageStep.Thinking -> {
                key(step.id) {
                    ThinkingCard(
                        text = step.text,
                        isStreaming = step.isStreaming,
                        startedAt = step.startedAt,
                        durationMs = step.durationMs,
                        onLocalhostLinkClick = onLocalhostLinkClick
                    )
                }
            }
            is MessageStep.ToolCall -> {
                when {
                    step.execution.name == "update_todo" -> Unit
                    isThinkingExecution(step.execution) -> {
                        key(step.execution.toolCallId) {
                            ThinkingCard(
                                text = step.execution.result.orEmpty(),
                                isStreaming = step.execution.status == ToolStatus.RUNNING
                            )
                        }
                    }
                    else -> {
                        val group = groups.find {
                            it.executions.contains(step.execution) || it.parentToolCallId == step.execution.toolCallId
                        }

                        when {
                            group != null && (
                                step.execution == group.executions.first() || group.parentToolCallId == step.execution.toolCallId
                            ) -> {
                                key(group.key + "_" + step.execution.toolCallId) {
                                    ToolExecutionGroupCard(
                                        group = group,
                                        onToolAccept = onToolAccept,
                                        onToolDecline = onToolDecline,
                                        onLocalhostLinkClick = onLocalhostLinkClick,
                                        onInteraction = onInteraction
                                    )
                                }
                            }
                            group != null -> Unit // Skip other group members
                            else -> {
                                key(step.execution.toolCallId) {
                                    ToolCallCard(
                                        execution = step.execution,
                                        onAccept = onToolAccept?.let { callback -> { callback(step.execution) } },
                                        onDecline = onToolDecline?.let { callback -> { callback(step.execution) } },
                                        onLocalhostLinkClick = onLocalhostLinkClick,
                                        onInteraction = onInteraction
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is MessageStep.Text -> {
                val textContent = step.formattedContent ?: step.content
                if (textContent.isNotBlank()) {
                    key(step.id) {
                        AssistantTextWithThinking(
                            text = textContent,
                            hideThinkingHeader = hideThinkingHeader,
                            onLocalhostLinkClick = onLocalhostLinkClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkSummaryCard(
    message: UiMessage,
    steps: List<MessageStep>,
    onInteraction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (steps.isEmpty()) return
    var expanded by remember(message.id) { mutableStateOf(false) }
    val toolCount = steps.count { it is MessageStep.ToolCall && it.execution.name != "update_todo" && !isThinkingExecution(it.execution) }
    val duration = formatWorkedDuration(message.timestamp, message.metadata["completedAt"]?.toLongOrNull())
    val subtitle = "Worked for $duration${if (toolCount > 0) " · $toolCount tool${if (toolCount == 1) "" else "s"}" else ""}"
    val shape = RoundedCornerShape(14.dp)
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val borderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clip(shape)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        expanded = !expanded
                        onInteraction()
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolLeadIconPill(ToolInfoIcon.TASK)
                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f).toolHeaderFade()
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            ToolCallAnimatedSection(visible = expanded) {
                Column {
                    HorizontalDivider(
                        color = borderColor,
                        thickness = 1.dp,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content
                    )
                }
            }
        }
    }
}

private fun finalVisibleTextIndexForSummary(message: UiMessage): Int? {
    val steps = message.steps
    if (message.metadata["completedAt"].isNullOrBlank()) return null
    if (steps.size < 2) return null
    if (steps.any { it is MessageStep.ToolCall && it.execution.status == ToolStatus.RUNNING }) return null

    val browserRanges = browserToolRanges(steps)
    fun isEmbeddedBrowserText(index: Int): Boolean = browserRanges.any { range -> index in range }

    val meaningful = steps.mapIndexedNotNull { index, step ->
        when (step) {
            is MessageStep.Thinking -> index
            is MessageStep.Text -> index.takeIf {
                !isEmbeddedBrowserText(index) && (step.formattedContent ?: step.content).isNotBlank()
            }
            is MessageStep.ToolCall -> index.takeIf {
                step.execution.name != "update_todo" &&
                    (!isThinkingExecution(step.execution) || (!isEmbeddedBrowserText(index) && !step.execution.result.isNullOrBlank()))
            }
        }
    }
    if (meaningful.size <= 1) return null

    val isRemote = message.metadata["source"].equals("remote", ignoreCase = true)
    if (!isRemote) {
        val last = meaningful.lastOrNull() ?: return null
        val lastStep = steps[last] as? MessageStep.Text ?: return null
        if ((lastStep.formattedContent ?: lastStep.content).isBlank()) return null
        return last
    }

    return meaningful.asReversed().firstOrNull { index ->
        val text = steps[index] as? MessageStep.Text ?: return@firstOrNull false
        (text.formattedContent ?: text.content).isNotBlank()
    }
}

private fun summaryTimelineSteps(message: UiMessage, finalTextIndex: Int): List<MessageStep> {
    val isRemote = message.metadata["source"].equals("remote", ignoreCase = true)
    if (!isRemote) return message.steps.take(finalTextIndex)

    // Antigravity state snapshots often contain cumulative/duplicated text fragments.
    // Keep the final answer outside the summary while retaining ordered tool and
    // reasoning events inside it.
    return message.steps.filterIndexed { index, step ->
        index != finalTextIndex && when (step) {
            is MessageStep.Thinking -> true
            is MessageStep.ToolCall -> step.execution.name != "update_todo"
            is MessageStep.Text -> false
        }
    }
}

private fun browserToolRanges(steps: List<MessageStep>): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var currentStart: Int? = null
    steps.forEachIndexed { index, step ->
        val isBrowser = (step as? MessageStep.ToolCall)?.execution?.name == "browser"
        if (isBrowser) {
            val start = currentStart
            if (start != null && index > start) {
                ranges += (start + 1) until index
            }
            currentStart = index
        } else if (step is MessageStep.ToolCall && !isThinkingExecution(step.execution)) {
            currentStart = null
        }
    }
    return ranges
}

private fun isThinkingExecution(execution: ToolExecution): Boolean {
    return execution.name.equals("thinking", ignoreCase = true) ||
        execution.metadata["syntheticThinking"].equals("true", ignoreCase = true) ||
        execution.metadata["thinkingTool"].equals("true", ignoreCase = true)
}

private fun formatWorkedDuration(startedAt: Long, completedAt: Long?): String {
    val elapsedMs = ((completedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0L)
    val seconds = (elapsedMs / 1000).coerceAtLeast(1L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

/**
 * Render the visible (non-thinking) answer body. Thinking is intentionally
 * NOT rendered here — it is owned by [MessageThinkingBlock] at message level
 * so there is a single source of truth. Any inline <think> tags still present
 * in providers that do not strip them are dropped here to avoid duplication.
 */
@Composable
fun AssistantTextWithThinking(
    text: String,
    hideThinkingHeader: Boolean = false,
    onLocalhostLinkClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val visible = remember(text) { stripThinkingTags(text) }
    if (visible.isBlank()) return
    MarkdownText(
        text = visible,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
        onLocalhostLinkClick = onLocalhostLinkClick
    )
}


