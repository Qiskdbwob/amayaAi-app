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
    onInteraction: () -> Unit = {}
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
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier.widthIn(max = maxBubbleWidthDp)
            ) {
                Text(
                    message.content,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = hPad, vertical = vPad),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp)
                )
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
                            val previousSteps = message.steps.take(finalTextIndex)
                            val finalTextStep = message.steps[finalTextIndex] as MessageStep.Text
                            WorkSummaryCard(
                                message = message,
                                steps = previousSteps,
                                onInteraction = onInteraction
                            ) {
                                StepTimeline(
                                    steps = previousSteps,
                                    hideThinkingHeader = hideThinkingHeader,
                                    onToolAccept = onToolAccept,
                                    onToolDecline = onToolDecline,
                                    onLocalhostLinkClick = onLocalhostLinkClick,
                                    onInteraction = onInteraction
                                )
                            }
                            val finalText = finalTextStep.formattedContent ?: finalTextStep.content
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
                    if (message.toolExecutions.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val browserMerged = mergeBrowserToolExecutions(message.toolExecutions.filter { it.name == "browser" })
                            message.toolExecutions.filter { it.name != "update_todo" }.forEach { execution ->
                                when {
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

@Composable
private fun WorkSummaryCard(
    message: UiMessage,
    steps: List<MessageStep>,
    onInteraction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (steps.isEmpty()) return
    var expanded by remember(message.id, steps.size) { mutableStateOf(false) }
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
    val last = meaningful.lastOrNull() ?: return null
    val lastStep = steps[last] as? MessageStep.Text ?: return null
    if ((lastStep.formattedContent ?: lastStep.content).isBlank()) return null
    return last.takeIf { meaningful.size > 1 }
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

private data class AssistantTextPart(
    val text: String,
    val isThinking: Boolean,
    val isOpen: Boolean = false
)

private fun parseThinkingTags(raw: String): List<AssistantTextPart> {
    if (raw.isBlank()) return emptyList()
    val parts = mutableListOf<AssistantTextPart>()
    var cursor = 0
    val tagRegex = Regex("</?think>", RegexOption.IGNORE_CASE)
    var inThinking = false

    tagRegex.findAll(raw).forEach { match ->
        raw.substring(cursor, match.range.first).takeIf { it.isNotBlank() }?.let {
            parts += AssistantTextPart(it.trim(), isThinking = inThinking)
        }
        inThinking = !match.value.startsWith("</", ignoreCase = true)
        cursor = match.range.last + 1
    }

    raw.substring(cursor).takeIf { it.isNotBlank() }?.let {
        parts += AssistantTextPart(it.trim(), isThinking = inThinking, isOpen = inThinking)
    }
    return parts.ifEmpty { listOf(AssistantTextPart(raw, isThinking = false)) }
}

fun stripThinkingTags(raw: String): String = parseThinkingTags(raw)
    .filterNot { it.isThinking }
    .joinToString("\n\n") { it.text }
    .ifBlank { raw.replace(Regex("</?think>", RegexOption.IGNORE_CASE), "").trim() }

@Composable
fun AssistantTextWithThinking(
    text: String,
    hideThinkingHeader: Boolean = false,
    onLocalhostLinkClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val parts = parseThinkingTags(text)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { index, part ->
            if (part.isThinking) {
                if (part.text.isNotBlank()) {
                    ToolCallCard(
                        execution = ToolExecution(
                            toolCallId = "think_${text.hashCode()}_$index",
                            name = "thinking",
                            arguments = mapOf("source" to "think_tag"),
                            result = part.text,
                            status = if (part.isOpen) ToolStatus.RUNNING else ToolStatus.SUCCESS,
                            metadata = mapOf("syntheticThinking" to "true"),
                            uiMetadata = ToolUiMetadata(
                                category = ToolCategory.TASK_MANAGEMENT,
                                label = "Thinking",
                                actionIcon = ToolInfoIcon.LIGHTBULB,
                                targetIcon = ToolInfoIcon.GENERATE,
                                badges = listOf("THINKING")
                            )
                        ),
                        onLocalhostLinkClick = onLocalhostLinkClick
                    )
                }
            } else {
                MarkdownText(
                    text = part.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    onLocalhostLinkClick = onLocalhostLinkClick
                )
            }
        }
    }
}

