package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
            
            Surface(
                color = Color(0xFF0A84FF),
                shape = RoundedCornerShape(21.dp, 21.dp, 6.dp, 21.dp),
                modifier = Modifier.widthIn(max = maxBubbleWidthDp)
            ) {
                Text(
                    message.content,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = hPad, vertical = vPad),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp)
                )
            }
        }
    } else {
        val sawLiveThinking = remember(message.id) { mutableStateOf(message.isThinking) }
        SideEffect {
            if (message.isThinking) sawLiveThinking.value = true
        }
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
                            // "Worked for {duration} · N tools" card. The
                            // thinking segment lives INSIDE this card so it
                            // stays inside the same container as the tool
                            // timeline; without this the reasoning card
                            // rendered outside the worked-by header and read
                            // as a separate block. The ThinkingCard keeps
                            // its own PROCESSING → DONE auto-collapse so a
                            // finished segment doesn't bloat the summary.
                            val summarySteps = summaryTimelineSteps(message, finalTextIndex)
                            val finalTextStep = message.steps[finalTextIndex] as MessageStep.Text
                            WorkSummaryCard(
                                message = message,
                                steps = summarySteps,
                                onInteraction = onInteraction,
                                animateInitialCollapse = hasLocalError(summarySteps)
                            ) {
                                MessageThinkingBlock(
                                    message = message,
                                    hideWhenDuplicate = hideThinkingHeader,
                                    onLocalhostLinkClick = onLocalhostLinkClick,
                                    onBodyScroll = onThinkingScroll,
                                    animateInitialCollapse = sawLiveThinking.value && !message.isThinking
                                )
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
                            // Steps branch — no summary card (streaming or
                            // single-step shortcut). Thinking card stays
                            // OUTSIDE above the timeline, matching legacy
                            // behaviour.
                            MessageThinkingBlock(
                                message = message,
                                hideWhenDuplicate = hideThinkingHeader,
                                onLocalhostLinkClick = onLocalhostLinkClick,
                                onBodyScroll = onThinkingScroll,
                                animateInitialCollapse = sawLiveThinking.value && !message.isThinking
                            )
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
                                val browserMerged = mergeBrowserToolExecutions(message.toolExecutions.filter { it.name == "browser" })
                                val visibleExecutions = message.toolExecutions.filter { it.name != "update_todo" }
                                val groups = buildToolExecutionGroups(
                                    visibleExecutions.map { MessageStep.ToolCall(execution = it) },
                                    autoExpandLatest = !visibleExecutions.any { it.status == ToolStatus.ERROR }
                                )

                                visibleExecutions.forEach { execution ->
                                    val group = groups.find { it.executions.contains(execution) }
                                    when {
                                        group != null && execution == group.executions.first() -> {
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
                                        execution.name == "browser" && browserMerged != null && execution == message.toolExecutions.firstOrNull { it.name == "browser" } -> {
                                            key(browserMerged.toolCallId) {
                                                ToolCallCard(
                                                    execution = browserMerged,
                                                    onAccept = onToolAccept?.let { callback -> { callback(execution) } },
                                                    onDecline = onToolDecline?.let { callback -> { callback(execution) } },
                                                    onLocalhostLinkClick = onLocalhostLinkClick,
                                                    onInteraction = onInteraction
                                                )
                                            }
                                        }
                                        execution.name == "browser" -> Unit
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
                            onBodyScroll = onThinkingScroll,
                            animateInitialCollapse = sawLiveThinking.value && !message.isThinking
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
    val browserIndices = steps.mapIndexedNotNull { idx, step ->
        ((step as? MessageStep.ToolCall)?.execution?.name == "browser").takeIf { it }?.let { idx }
    }
    val firstBrowserIndex = browserIndices.firstOrNull()
    val lastBrowserIndex = browserIndices.lastOrNull()
    val mergedBrowserExecution = mergeBrowserToolExecutions(
        steps.mapNotNull { (it as? MessageStep.ToolCall)?.execution?.takeIf { exec -> exec.name == "browser" } }
    )
    val embeddedBrowserText = if (firstBrowserIndex != null && lastBrowserIndex != null && firstBrowserIndex < lastBrowserIndex) {
        steps.subList(firstBrowserIndex + 1, lastBrowserIndex).mapNotNull { step ->
            val textStep = step as? MessageStep.Text ?: return@mapNotNull null
            (textStep.formattedContent ?: textStep.content).takeIf { it.isNotBlank() }
        }.joinToString("\n\n").takeIf { it.isNotBlank() }
    } else null
    val groups = buildToolExecutionGroups(steps, autoExpandLatest = !steps.any { (it as? MessageStep.ToolCall)?.execution?.status == ToolStatus.ERROR })

    steps.forEachIndexed { idx, step ->
        val isBetweenBrowserCalls = firstBrowserIndex != null && lastBrowserIndex != null && idx in (firstBrowserIndex + 1) until lastBrowserIndex
        when (step) {
            is MessageStep.ToolCall -> {
                when {
                    step.execution.name == "update_todo" -> Unit
                    isThinkingExecution(step.execution) && isBetweenBrowserCalls -> Unit
                    step.execution.name == "browser" && idx == firstBrowserIndex && mergedBrowserExecution != null -> {
                        key(mergedBrowserExecution.toolCallId) {
                            ToolCallCard(
                                execution = attachBrowserTimeline(
                                    execution = mergedBrowserExecution,
                                    steps = steps,
                                    firstIndex = firstBrowserIndex,
                                    lastIndex = lastBrowserIndex
                                ),
                                onAccept = onToolAccept?.let { callback -> { callback(step.execution) } },
                                onDecline = onToolDecline?.let { callback -> { callback(step.execution) } },
                                onLocalhostLinkClick = onLocalhostLinkClick,
                                onInteraction = onInteraction,
                                embeddedText = embeddedBrowserText
                            )
                        }
                    }
                    step.execution.name == "browser" -> Unit
                    isThinkingExecution(step.execution) -> {
                        key(step.execution.toolCallId) {
                            ThinkingCard(
                                text = step.execution.result.orEmpty(),
                                isStreaming = step.execution.status == ToolStatus.RUNNING
                            )
                        }
                    }
                    else -> {
                        val group = groups.find { it.executions.contains(step.execution) }

                        when {
                            group != null && step.execution == group.executions.first() -> {
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
                if (!isBetweenBrowserCalls) {
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
}

private fun hasLocalError(steps: List<MessageStep>): Boolean =
    steps.any { (it as? MessageStep.ToolCall)?.execution?.status == ToolStatus.ERROR }

@Composable
private fun WorkSummaryCard(
    message: UiMessage,
    steps: List<MessageStep>,
    onInteraction: () -> Unit,
    animateInitialCollapse: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    if (steps.isEmpty()) return
    var expanded by remember(message.id, steps.size) { mutableStateOf(!animateInitialCollapse) }
    LaunchedEffect(animateInitialCollapse) {
        if (animateInitialCollapse) {
            withFrameNanos { }
            expanded = false
        }
    }
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
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + fadeOut()
            ) {
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
    // Keep the human-facing final text outside the work summary and show only the
    // tool/thinking timeline inside the collapsible summary card.
    return message.steps.filterIndexed { index, step ->
        index != finalTextIndex && step is MessageStep.ToolCall && step.execution.name != "update_todo"
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

private fun attachBrowserTimeline(
    execution: ToolExecution,
    steps: List<MessageStep>,
    firstIndex: Int?,
    lastIndex: Int?
): ToolExecution {
    if (firstIndex == null || lastIndex == null) return execution
    val parent = runCatching { JSONObject(execution.result.orEmpty()) }.getOrNull() ?: return execution
    val timeline = JSONArray()

    for (idx in firstIndex..lastIndex) {
        when (val step = steps[idx]) {
            is MessageStep.Text -> {
                val content = step.formattedContent ?: step.content
                parseThinkingTags(content).forEach { part ->
                    if (part.text.isNotBlank()) {
                        timeline.put(JSONObject().apply {
                            put("type", if (part.isThinking) "thinking" else "text")
                            put("content", part.text)
                        })
                    }
                }
            }
            is MessageStep.ToolCall -> {
                when {
                    step.execution.name == "browser" -> {
                        val childParent = runCatching { JSONObject(step.execution.result.orEmpty()) }.getOrNull()
                        val subs = childParent?.optJSONArray("sub_toolcalls") ?: JSONArray()
                        for (i in 0 until subs.length()) {
                            timeline.put(JSONObject().apply {
                                put("type", "subtool")
                                put("item", subs.optJSONObject(i))
                            })
                        }
                    }
                    isThinkingExecution(step.execution) && !step.execution.result.isNullOrBlank() -> {
                        timeline.put(JSONObject().apply {
                            put("type", "thinking")
                            put("content", step.execution.result.orEmpty())
                        })
                    }
                }
            }
        }
    }

    parent.put("timeline", timeline)
    return execution.copy(result = parent.toString(2))
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


