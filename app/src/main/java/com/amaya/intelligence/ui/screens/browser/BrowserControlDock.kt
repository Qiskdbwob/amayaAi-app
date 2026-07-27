package com.amaya.intelligence.ui.screens.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.domain.models.ToolInfoIcon
import com.amaya.intelligence.impl.local.browser.BrowserAgentStatus
import com.amaya.intelligence.impl.local.browser.BrowserToolLog
import com.amaya.intelligence.impl.local.browser.BrowserUiState
import com.amaya.intelligence.ui.components.shared.MarkdownText
import com.amaya.intelligence.ui.components.shared.formatCompactDuration
import com.amaya.intelligence.ui.components.shared.ToolLeadIconPill
import com.amaya.intelligence.ui.components.shared.mapToolIcon

@Composable
fun BrowserControlDock(
    state: BrowserUiState,
    address: String,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
    onClose: () -> Unit,
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var autoDoneExpanded by remember { mutableStateOf(false) }
    val logs = remember(state.logs, state.assistantStreamText, state.assistantStreamUpdatedAt) { browserLiveLogs(state) }

    val isDark = isSystemInDarkTheme()
    val bgMain = if (isDark) Color(0xFF1D1F24).copy(alpha = 0.92f) else Color(0xFFF7F7FA).copy(alpha = 0.94f)
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    val cardBg = if (isDark) Color(0xFF1C1C1E) else Color.White

    val duration = remember(logs, state.status, state.isAssistantStreaming) {
        if (logs.isEmpty()) return@remember "0s"
        val firstTime = logs.first().timestamp
        val lastTime = logs.last().timestamp
        val elapsed = if (state.isAssistantStreaming || state.status == BrowserAgentStatus.BROWSING) {
            System.currentTimeMillis() - firstTime
        } else {
            lastTime - firstTime
        }
        formatCompactDuration(elapsed, minimumSeconds = 1L)
    }

    val toolCount = remember(logs) {
        logs.count { !it.toolName.equals("assistant", true) && !it.toolName.equals("thinking", true) && it.id != "stream_active" }
    }

    val subtitle = "Worked for $duration${if (toolCount > 0) " · $toolCount tool${if (toolCount == 1) "" else "s"}" else ""}"

    var wasStreaming by remember { mutableStateOf(false) }
    LaunchedEffect(state.isAssistantStreaming, state.status) {
        if (state.isAssistantStreaming) {
            autoDoneExpanded = false
            if (expanded) expanded = false
        }
        if (!state.isAssistantStreaming && state.status == BrowserAgentStatus.COMPLETED) {
            expanded = true
            autoDoneExpanded = true
        }
        if (wasStreaming && !state.isAssistantStreaming && state.assistantStreamText.isNotBlank()) {
            expanded = true
            autoDoneExpanded = true
        }
        wasStreaming = state.isAssistantStreaming
    }

    val borderMain = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    val textPrimary = if (isDark) Color.White.copy(alpha = 0.90f) else Color.Black.copy(alpha = 0.88f)
    val textSecondary = if (isDark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.50f)
    val textTertiary = if (isDark) Color.White.copy(alpha = 0.42f) else Color.Black.copy(alpha = 0.38f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.6f),
                        Color.Black.copy(alpha = 0.95f)
                    )
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = bgMain,
            border = BorderStroke(1.dp, borderMain),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp)) {
            if (!autoDoneExpanded) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = cardBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 44.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    val isDone = state.status == BrowserAgentStatus.COMPLETED
                    if (expanded || isDone) {
                        ToolLeadIconPill(ToolInfoIcon.TASK)
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        val liveText = state.assistantStreamText
                        val isThinking = hasOpenThinkingTag(liveText)
                        val latestLog = logs.lastOrNull()

                        if (state.isAssistantStreaming && liveText.isNotBlank()) {
                            if (isThinking) {
                                Icon(Icons.Default.Lightbulb, null, modifier = Modifier.size(14.dp), tint = Color(0xFFFFB300))
                                Text(text = cleanStreamText(liveText).ifBlank { "Thinking..." }, style = MaterialTheme.typography.labelMedium, color = textPrimary, maxLines = 1, modifier = Modifier.weight(1f).basicMarquee())
                            } else {
                                Text(text = cleanStreamText(liveText), style = MaterialTheme.typography.labelMedium, color = textPrimary, maxLines = 1, modifier = Modifier.weight(1f).basicMarquee())
                            }
                        } else if (latestLog != null) {
                            val isText = latestLog.toolName.equals("thinking", true) || latestLog.toolName.equals("assistant", true)
                            if (isText) {
                                val cText = cleanStreamText(latestLog.message)
                                if (cText.isNotBlank()) {
                                    Text(cText, style = MaterialTheme.typography.labelMedium, color = textPrimary, maxLines = 1, modifier = Modifier.weight(1f).basicMarquee())
                                } else {
                                    ToolLeadIconPill(ToolInfoIcon.TASK)
                                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                }
                            } else {
                                val sc = browserStatusColor(latestLog.status)
                                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                    Surface(shape = CircleShape, color = sc.copy(alpha = 0.14f), border = BorderStroke(1.dp, sc.copy(alpha = 0.16f)), modifier = Modifier.matchParentSize()) {}
                                    Icon(browserLogIcon(latestLog), null, modifier = Modifier.size(12.dp), tint = sc)
                                }
                                val label = browserActionLabel(latestLog.toolName)
                                Text(text = label, style = MaterialTheme.typography.labelMedium, color = textPrimary, maxLines = 1, modifier = Modifier.weight(1f).basicMarquee())
                            }
                        } else {
                            ToolLeadIconPill(ToolInfoIcon.TASK)
                            Text(text = subtitle, style = MaterialTheme.typography.labelMedium, color = textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = textSecondary
                    )
                }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + fadeOut()
            ) {
                Column {
                    if (!autoDoneExpanded) Spacer(Modifier.height(12.dp))
                    if (logs.isNotEmpty()) {
                        val scrollState = rememberScrollState()
                        LaunchedEffect(logs.size) { scrollState.animateScrollTo(scrollState.maxValue) }
                        Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val timelineItems = mutableListOf<BrowserDockTimelineItem>()
                            val normalTextLogs = mutableListOf<@Composable () -> Unit>()
                            val normalTextTotal = logs.sumOf { log ->
                                if (log.toolName.equals("thinking", true) || log.toolName.equals("assistant", true)) {
                                    parseBrowserThinkingTags(log.message).count { part -> part.text.isNotBlank() && !part.isThinking }
                                } else 0
                            }
                            var normalTextIndex = 0

                            logs.forEach { log ->
                                val isText = log.toolName.equals("thinking", true) || log.toolName.equals("assistant", true)
                                if (isText) {
                                    parseBrowserThinkingTags(log.message).forEach { part ->
                                        if (part.text.isBlank()) return@forEach
                                        if (part.isThinking) {
                                            val thinkingBlock: @Composable () -> Unit = {
                                                BrowserDockThinkingBlock(text = part.text, isDark = isDark, cardBorder = cardBorder, textPrimary = textPrimary, textTertiary = textTertiary)
                                            }
                                            timelineItems.add(BrowserDockTimelineItem(isTool = false, content = thinkingBlock))
                                        } else {
                                            normalTextIndex++
                                            val isLatestText = autoDoneExpanded && normalTextIndex == normalTextTotal
                                            val textBlock: @Composable () -> Unit = {
                                                Surface(shape = RoundedCornerShape(12.dp), color = cardBg, modifier = Modifier.fillMaxWidth()) {
                                                    if (isLatestText) {
                                                        MarkdownText(
                                                            text = part.text,
                                                            color = if (isDark) Color.White.copy(alpha = 0.88f) else Color.Black.copy(alpha = 0.86f),
                                                            compact = false,
                                                            fontSize = 17.sp,
                                                            lineHeight = 25.sp,
                                                            modifier = Modifier.padding(12.dp)
                                                        )
                                                    } else {
                                                        MarkdownText(
                                                            text = part.text,
                                                            color = if (isDark) Color.White.copy(alpha = 0.82f) else Color.Black.copy(alpha = 0.80f),
                                                            compact = true,
                                                            fontSize = 13.sp,
                                                            lineHeight = 19.sp,
                                                            modifier = Modifier.padding(9.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            if (isLatestText) {
                                                normalTextLogs.add(textBlock)
                                            } else {
                                                timelineItems.add(BrowserDockTimelineItem(isTool = false, content = textBlock))
                                            }
                                        }
                                    }
                                } else {
                                    val toolBlock: @Composable () -> Unit = {
                                        val sc = browserStatusColor(log.status)
                                        Surface(shape = RoundedCornerShape(12.dp), color = cardBg, modifier = Modifier.fillMaxWidth()) {
                                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                BrowserDockSubtoolLeadIcon(tool = log.toolName, status = log.status, color = sc)
                                                Column(Modifier.weight(1f)) {
                                                    val label = browserActionLabel(log.toolName)
                                                    Text(text = label, style = MaterialTheme.typography.labelMedium, color = textPrimary.copy(alpha = 0.84f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Surface(shape = CircleShape, color = sc, modifier = Modifier.size(6.dp)) {}
                                            }
                                        }
                                    }
                                    timelineItems.add(BrowserDockTimelineItem(isTool = true, content = toolBlock))
                                }
                            }

                            if (timelineItems.isNotEmpty()) {
                                if (autoDoneExpanded) {
                                    BrowserDockWorkSummaryCard(
                                        subtitle = subtitle,
                                        cardBg = cardBg,
                                        cardBorder = cardBorder,
                                        textPrimary = textPrimary,
                                        textSecondary = textSecondary,
                                        textTertiary = textTertiary,
                                        initiallyExpanded = false
                                    ) {
                                        BrowserDockTimelineItems(
                                            items = timelineItems,
                                            cardBorder = cardBorder,
                                            textPrimary = textPrimary,
                                            textTertiary = textTertiary
                                        )
                                    }
                                } else {
                                    timelineItems.forEach { it.content() }
                                }
                            }
                            normalTextLogs.forEach { it() }
                        }
                    } else {
                        Column(modifier = Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { BrowserEmptyText("No browser activity yet.") }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onStop) { Text("Stop") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    }
}

@Composable
private fun BrowserEmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.58f), modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
}

private fun browserLiveLogs(state: BrowserUiState): List<BrowserToolLog> {
    val completed = state.logs.sortedBy { it.timestamp }
    if (state.assistantStreamText.isNotBlank()) {
        val streamText = state.assistantStreamText.trim()
        val alreadySnapshotted = completed.any {
            (it.toolName.equals("thinking", true) || it.toolName.equals("assistant", true)) &&
                it.message.trim() == streamText
        }
        if (!alreadySnapshotted) {
            val isThinking = hasOpenThinkingTag(streamText)
            val toolName = if (isThinking) "thinking" else "assistant"
            return completed + BrowserToolLog(
                id = if (state.isAssistantStreaming) "stream_active" else "stream_final",
                toolName = toolName,
                argumentsPreview = "",
                status = if (isThinking) "running" else "completed",
                message = streamText,
                timestamp = state.assistantStreamUpdatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }
    }
    return completed
}

@Composable
private fun BrowserDockSubtoolLeadIcon(tool: String, status: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (status.equals("running", true)) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
            } else {
                Icon(
                    imageVector = mapToolIcon(browserDockToolIcon(tool)),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = color.copy(alpha = 0.88f)
                )
            }
        }
    }
}

private fun browserDockToolIcon(tool: String): ToolInfoIcon = browserToolIcon(tool)

private fun browserLogIcon(log: BrowserToolLog) = when (log.toolName.lowercase()) {
    "thinking" -> Icons.Default.Lightbulb
    "assistant" -> Icons.Default.Build
    "open_url", "new_page", "new_tab" -> Icons.Default.Public
    "get_dom", "analyze_page", "observe" -> Icons.Default.Check
    "click_element", "click", "tap" -> Icons.Default.PlayArrow
    "type_text", "type", "search" -> Icons.Default.PlayArrow
    "scroll_page", "scroll", "swipe" -> Icons.Default.Autorenew
    else -> when (log.status.lowercase()) {
        "error", "cancelled" -> Icons.Default.Stop
        "paused", "waiting" -> Icons.Default.Pause
        else -> Icons.Default.Autorenew
    }
}

private fun cleanStreamText(raw: String): String = cleanBrowserThinkText(raw)

private data class BrowserTextPart(val text: String, val isThinking: Boolean)

private fun parseBrowserThinkingTags(raw: String): List<BrowserTextPart> {
    if (raw.isBlank()) return emptyList()
    val parts = mutableListOf<BrowserTextPart>()
    var cursor = 0
    val tagRegex = Regex("</?think>", RegexOption.IGNORE_CASE)
    var inThinking = false
    tagRegex.findAll(raw).forEach { match ->
        raw.substring(cursor, match.range.first).takeIf { it.isNotBlank() }?.let { parts += BrowserTextPart(it.trim(), isThinking = inThinking) }
        inThinking = !match.value.startsWith("</", ignoreCase = true)
        cursor = match.range.last + 1
    }
    raw.substring(cursor).takeIf { it.isNotBlank() }?.let { parts += BrowserTextPart(it.trim(), isThinking = inThinking) }
    return parts.ifEmpty { listOf(BrowserTextPart(raw, isThinking = false)) }
}

@Composable
private fun BrowserDockWorkSummaryCard(
    subtitle: String,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    textTertiary: Color,
    initiallyExpanded: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Surface(shape = RoundedCornerShape(12.dp), color = cardBg, border = BorderStroke(1.dp, cardBorder), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolLeadIconPill(ToolInfoIcon.TASK)
                Text(">", style = MaterialTheme.typography.labelSmall, color = textTertiary)
                Text(text = subtitle, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = textTertiary)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = cardBorder, modifier = Modifier.padding(bottom = 4.dp))
                    content()
                }
            }
        }
    }
}

private data class BrowserDockTimelineItem(
    val isTool: Boolean,
    val content: @Composable () -> Unit
)

@Composable
private fun BrowserDockTimelineItems(
    items: List<BrowserDockTimelineItem>,
    cardBorder: Color,
    textPrimary: Color,
    textTertiary: Color
) {
    val pendingTools = mutableListOf<@Composable () -> Unit>()

    @Composable
    fun androidx.compose.foundation.layout.ColumnScope.flushTools() {
        if (pendingTools.isEmpty()) return
        val group = pendingTools.toList()
        if (group.size > 1) {
            BrowserDockNestedSummaryCard(
                title = "${group.size} tools used",
                cardBorder = cardBorder,
                textPrimary = textPrimary,
                textTertiary = textTertiary
            ) {
                group.forEach { it() }
            }
        } else {
            group.first().invoke()
        }
        pendingTools.clear()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            if (item.isTool) {
                pendingTools.add(item.content)
            } else {
                flushTools()
                item.content()
            }
        }
        flushTools()
    }
}

@Composable
private fun BrowserDockNestedSummaryCard(
    title: String,
    cardBorder: Color,
    textPrimary: Color,
    textTertiary: Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolLeadIconPill(ToolInfoIcon.TASK)
                Text(">", style = MaterialTheme.typography.labelSmall, color = textTertiary)
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = textPrimary.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = textTertiary
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = cardBorder, modifier = Modifier.padding(bottom = 4.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun BrowserDockThinkingBlock(
    text: String,
    isDark: Boolean,
    cardBorder: Color,
    textPrimary: Color,
    textTertiary: Color
) {
    var expanded by remember { mutableStateOf(false) }
    val cardBg = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    Surface(shape = RoundedCornerShape(12.dp), color = cardBg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolLeadIconPill(ToolInfoIcon.LIGHTBULB)
                Text(">", style = MaterialTheme.typography.labelSmall, color = textTertiary)
                Text(text = text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Normal, color = textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = textTertiary)
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = cardBorder, modifier = Modifier.padding(horizontal = 10.dp))
                    MarkdownText(text = text, color = textPrimary.copy(alpha = 0.82f), compact = true, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(9.dp))
                }
            }
        }
    }
}
