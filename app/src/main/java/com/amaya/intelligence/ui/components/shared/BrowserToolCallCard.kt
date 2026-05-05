package com.amaya.intelligence.ui.components.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolInfoIcon
import com.amaya.intelligence.domain.models.ToolStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Browser parent toolcall renderer.
 * Intentionally mirrors the global ToolCallCard/SubagentChildCard language:
 * compact header, badge row, single expandable parent, nested children with
 * status icon + optional details. This prevents Browser from feeling like a
 * separate UI system.
 */
@Composable
fun BrowserToolCallCard(
    execution: ToolExecution,
    onInteraction: () -> Unit = {},
    embeddedText: String? = null
) {
    val parent = remember(execution.result) { parseBrowserParent(execution.result) }
    var expanded by remember(execution.toolCallId) { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()

    val iosGreen = Color(0xFF34C759)
    val iosBlue = Color(0xFF007AFF)
    val iosRed = MaterialTheme.colorScheme.error

    val status = parent?.optString("status") ?: execution.status.name.lowercase()
    val statusColor = browserStatusColor(status, execution.status, iosGreen, iosBlue, iosRed)
    val statusIcon = browserStatusIcon(status, execution.status)
    val bgColor = when (status) {
        "error", "cancelled", "timeout" -> if (isDark) iosRed.copy(alpha = 0.10f) else iosRed.copy(alpha = 0.06f)
        "running" -> if (isDark) iosBlue.copy(alpha = 0.08f) else iosBlue.copy(alpha = 0.04f)
        "paused" -> Color(0xFFFFA000).copy(alpha = if (isDark) 0.12f else 0.07f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    val summary = parent?.optString("summary")?.takeIf { it.isNotBlank() }
        ?: execution.arguments["task"]?.toString()
        ?: "Browser"
    val context = LocalContext.current
    val subcalls = parent?.optJSONArray("sub_toolcalls") ?: JSONArray()
    val timeline = parent?.optJSONArray("timeline")
    val canExpand = subcalls.length() > 0 || !execution.result.isNullOrBlank()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (canExpand) Modifier.clickable { expanded = !expanded; onInteraction() } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolLeadIconPill(ToolInfoIcon.BROWSER)
                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.OpenInBrowser,
                    contentDescription = "Open browser",
                    modifier = Modifier.size(18.dp).clickable {
                        context.startActivity(android.content.Intent(context, com.amaya.intelligence.ui.activities.browser.BrowserOperatorActivity::class.java))
                    },
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
                )
                Icon(statusIcon, null, modifier = Modifier.size(14.dp), tint = statusColor)
                if (canExpand) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = browserExpandSpec()) + fadeIn(),
                exit = shrinkVertically(animationSpec = browserExpandSpec()) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (timeline != null && timeline.length() > 0) {
                        BrowserTimelineItems(
                            timeline = timeline,
                            isDark = isDark,
                            iosGreen = iosGreen,
                            iosBlue = iosBlue,
                            iosRed = iosRed,
                            onInteraction = onInteraction
                        )
                    } else {
                        BrowserSubToolSummary(
                            subcalls = subcalls,
                            isDark = isDark,
                            iosGreen = iosGreen,
                            iosBlue = iosBlue,
                            iosRed = iosRed,
                            onInteraction = onInteraction
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserTimelineItems(
    timeline: JSONArray,
    isDark: Boolean,
    iosGreen: Color,
    iosBlue: Color,
    iosRed: Color,
    onInteraction: () -> Unit
) {
    val entries = remember(timeline) { (0 until timeline.length()).mapNotNull { timeline.optJSONObject(it) } }
    val pendingSubtools = mutableListOf<JSONObject>()
    var summaryIndex = 0

    @Composable
    fun FlushSubtools() {
        if (pendingSubtools.isEmpty()) return
        val items = pendingSubtools.toList()
        pendingSubtools.clear()
        if (items.size > 1) {
            key("subtool-summary-${summaryIndex++}-${items.firstOrNull()?.optString("id")}") {
                BrowserSubToolSummary(items, isDark, iosGreen, iosBlue, iosRed, onInteraction)
            }
        } else {
            val item = items.first()
            key(item.optString("id", "subtool-${summaryIndex++}")) {
                BrowserSubToolCallItem(item, summaryIndex, isDark, iosGreen, iosBlue, iosRed, onInteraction)
            }
        }
    }

    entries.forEachIndexed { index, entry ->
        when (entry.optString("type")) {
            "subtool" -> entry.optJSONObject("item")?.let { pendingSubtools += it }
            "text" -> {
                FlushSubtools()
                BrowserTimelineMarkdown(entry.optString("content"))
            }
            "thinking" -> {
                FlushSubtools()
                BrowserTimelineThinking(entry.optString("content"), onInteraction)
            }
            else -> Unit
        }
        if (index == entries.lastIndex) FlushSubtools()
    }
}

@Composable
private fun BrowserSubToolSummary(
    subcalls: JSONArray,
    isDark: Boolean,
    iosGreen: Color,
    iosBlue: Color,
    iosRed: Color,
    onInteraction: () -> Unit
) {
    val items = remember(subcalls) { (0 until subcalls.length()).mapNotNull { subcalls.optJSONObject(it) } }
    BrowserSubToolSummary(items, isDark, iosGreen, iosBlue, iosRed, onInteraction)
}

@Composable
private fun BrowserSubToolSummary(
    items: List<JSONObject>,
    isDark: Boolean,
    iosGreen: Color,
    iosBlue: Color,
    iosRed: Color,
    onInteraction: () -> Unit
) {
    val firstStarted = items.mapNotNull { parseBrowserIsoMillis(it.optString("started_at")) }.minOrNull()
    val lastUpdated = items.mapNotNull { parseBrowserIsoMillis(it.optString("completed_at").ifBlank { it.optString("updated_at") }) }.maxOrNull()
    val duration = formatBrowserDuration((lastUpdated ?: firstStarted ?: 0L) - (firstStarted ?: lastUpdated ?: 0L))
    BrowserWorkSummaryCard(
        title = "Worked for $duration${if (items.isNotEmpty()) " · ${items.size} tools" else ""}",
        onInteraction = onInteraction
    ) {
        items.forEachIndexed { index, item ->
            key(item.optString("id", index.toString())) {
                BrowserSubToolCallItem(item, index, isDark, iosGreen, iosBlue, iosRed, onInteraction)
            }
        }
    }
}

@Composable
private fun BrowserWorkSummaryCard(
    title: String,
    onInteraction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember(title) { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val borderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded; onInteraction() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolLeadIconPill(ToolInfoIcon.BROWSER)
                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = browserExpandSpec()) + fadeIn(),
                exit = shrinkVertically(animationSpec = browserExpandSpec()) + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = borderColor,
                        thickness = 1.dp,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserTimelineMarkdown(text: String) {
    val isDark = isSystemInDarkTheme()
    val blockBorderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
        border = androidx.compose.foundation.BorderStroke(1.dp, blockBorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        MarkdownText(
            text = text,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
            compact = true,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun BrowserTimelineThinking(text: String, onInteraction: () -> Unit) {
    var expanded by remember(text) { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val borderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    val title = remember(text) { browserThinkingTitle(text) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded; onInteraction() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolLeadIconPill(ToolInfoIcon.LIGHTBULB)
                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = browserExpandSpec()) + fadeIn(),
                exit = shrinkVertically(animationSpec = browserExpandSpec()) + fadeOut()
            ) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = borderColor, thickness = 1.dp, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
                    BrowserTextBlock(text = text, isDark = isDark, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
                }
            }
        }
    }
}

private fun browserThinkingTitle(raw: String): String {
    val clean = stripThinkingTags(raw)
        .lines()
        .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
        .firstOrNull { it.isNotBlank() }
        ?.replace(Regex("^#{1,6}\\s+"), "")
        ?.replace(Regex("^(?:\\*\\*|__)(.+?)(?:\\*\\*|__)\\s*:?.*$")) { it.groupValues[1] }
        ?.trim()
        ?.removeSuffix(":")
        ?.trim()
        .orEmpty()
    return clean.take(56).ifBlank { "Thinking" }
}

private fun browserExpandSpec() = spring<androidx.compose.ui.unit.IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium
)

private fun parseBrowserIsoMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
}

private fun formatBrowserDuration(elapsedMs: Long): String {
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

@Composable
fun BrowserSubToolCallItem(
    item: JSONObject,
    index: Int,
    isDark: Boolean,
    iosGreen: Color,
    iosBlue: Color,
    iosRed: Color,
    onInteraction: () -> Unit = {}
) {
    var expanded by remember(item.optString("id")) { mutableStateOf(false) }
    val response = item.optJSONObject("response") ?: JSONObject()
    val status = item.optString("status", response.optString("status"))
    val tool = item.optString("tool", response.optString("tool", "browser.step"))
    val label = browserToolDisplayName(tool)
    val rawSummary = stripThinkingTags(item.optString("summary", response.optJSONObject("ui")?.optString("summary") ?: ""))
    val summary = browserCleanSubtoolSummary(rawSummary, label, status)
    val statusColor = browserStatusColor(status, ToolStatus.PENDING, iosGreen, iosBlue, iosRed)
    val canExpand = response.length() > 0
    val borderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    val bgColor = MaterialTheme.colorScheme.surfaceContainerLow
    val duration = browserSubtoolDurationLabel(item)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (canExpand) Modifier.clickable { expanded = !expanded; onInteraction() } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrowserSubtoolLeadIcon(tool = tool, status = status, color = statusColor)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = listOfNotNull(summary, duration).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                BrowserSubtoolStatusIndicator(status, statusColor)
                if (canExpand) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = browserExpandSpec()) + fadeIn(),
                exit = shrinkVertically(animationSpec = browserExpandSpec()) + fadeOut()
            ) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = borderColor, thickness = 1.dp, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
                    BrowserSubToolResponseBody(
                    response = response,
                    isDark = isDark,
                    iosRed = iosRed,
                    modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserSubtoolLeadIcon(tool: String, status: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.18f))
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (status == "running") {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
            } else {
                Icon(
                    imageVector = mapToolIcon(browserToolIcon(tool)),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = color.copy(alpha = 0.88f)
                )
            }
        }
    }
}

@Composable
private fun BrowserSubtoolStatusIndicator(status: String, color: Color) {
    when (status.lowercase()) {
        "success", "completed" -> Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = color)
        "running", "browsing" -> Icon(Icons.Default.Autorenew, null, modifier = Modifier.size(14.dp), tint = color)
        "error", "cancelled", "timeout" -> Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp), tint = color)
        "paused", "waiting_input" -> Icon(Icons.Default.Pause, null, modifier = Modifier.size(14.dp), tint = color)
        else -> Unit
    }
}

private fun browserToolIcon(tool: String): ToolInfoIcon {
    val normalized = tool.removePrefix("browser.").lowercase()
    return when (normalized) {
        "open_url", "new_page", "new_tab", "reload", "reload_page", "go_back", "go_forward" -> ToolInfoIcon.WORLD
        "get_dom", "analyze_page", "observe", "get_visible_text" -> ToolInfoIcon.READ
        "click", "click_element", "tap", "press_key" -> ToolInfoIcon.MOUSE
        "type", "type_text", "clear_input" -> ToolInfoIcon.EDIT
        "scroll", "scroll_page", "swipe" -> ToolInfoIcon.MOUSE
        "search", "find_element", "wait_for_element" -> ToolInfoIcon.SEARCH
        "evaluate_script" -> ToolInfoIcon.COMMAND
        "get_screenshot", "screenshot" -> ToolInfoIcon.IMAGE
        else -> ToolInfoIcon.BROWSER
    }
}

private fun browserCleanSubtoolSummary(raw: String, label: String, status: String): String? {
    val clean = raw
        .replace(Regex("\\s+"), " ")
        .trim()
        .removeSuffix(".")
    if (clean.isBlank()) return null
    val generic = setOf("done", "success", "completed", "running", "browser", status.lowercase(), label.lowercase())
    return clean.takeUnless { it.lowercase() in generic }?.take(90)
}

private fun browserSubtoolDurationLabel(item: JSONObject): String? {
    val started = parseBrowserIsoMillis(item.optString("started_at")) ?: return null
    val ended = parseBrowserIsoMillis(item.optString("completed_at").ifBlank { item.optString("updated_at") }) ?: return null
    return formatBrowserDuration((ended - started).coerceAtLeast(0L))
}

@Composable
private fun BrowserTextBlock(text: String, isDark: Boolean, modifier: Modifier = Modifier) {
    val blockBorderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
        border = androidx.compose.foundation.BorderStroke(1.dp, blockBorderColor),
        modifier = modifier.fillMaxWidth()
    ) {
        MarkdownText(
            text = text,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
            compact = true,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun BrowserSubToolResponseBody(
    response: JSONObject,
    isDark: Boolean,
    iosRed: Color,
    modifier: Modifier = Modifier
) {
    val result = response.optJSONObject("result")
    val error = response.optJSONObject("error")
    val dom = result?.optJSONObject("dom")
    val scriptOutput = result?.takeIf { it.optString("mode") == "script_result" }?.opt("output")?.toString()
    val showRawBlock = dom != null || scriptOutput != null
    val blockBorderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

    if (showRawBlock) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
            border = androidx.compose.foundation.BorderStroke(1.dp, blockBorderColor),
            modifier = modifier
        ) {
            Text(
                text = if (scriptOutput != null) scriptOutput.take(6000) else result.toString(2).take(6000),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
        border = androidx.compose.foundation.BorderStroke(1.dp, blockBorderColor),
        modifier = modifier
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            error?.let {
                Text(it.optString("code", "Error"), style = MaterialTheme.typography.labelMedium, color = iosRed, fontWeight = FontWeight.SemiBold)
                Text(it.optString("message"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                it.optString("suggested_action").takeIf { action -> action.isNotBlank() }?.let { action ->
                    Text("Next: $action", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f))
                }
                return@Column
            }
            result?.optString("message")?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            result?.optJSONObject("page")?.let { page ->
                val title = page.optString("title").ifBlank { page.optString("url") }
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                page.optString("url").takeIf { it.isNotBlank() }?.let { url ->
                    Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (result == null && error == null) {
                response.optString("summary")
                    .takeIf { it.isNotBlank() && it.lowercase() !in setOf("done", "success", "completed") }
                    ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun BrowserBadge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun BrowserStatusChip(status: String, color: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text = when (status) {
                "success" -> "Done"
                "error" -> "Failed"
                "paused" -> "Paused"
                "cancelled" -> "Stopped"
                "timeout" -> "Timeout"
                "running" -> "Running"
                else -> status.replace('_', ' ')
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

fun mergeBrowserToolExecutions(executions: List<ToolExecution>): ToolExecution? {
    if (executions.isEmpty()) return null
    if (executions.size == 1) return executions.first()

    val parents = executions.mapNotNull { parseBrowserParent(it.result) }
    if (parents.isEmpty()) return executions.last()
    val first = parents.first()
    val last = parents.last()
    val mergedSubcalls = JSONArray()
    parents.forEach { parent ->
        val subs = parent.optJSONArray("sub_toolcalls") ?: JSONArray()
        for (i in 0 until subs.length()) {
            mergedSubcalls.put(subs.optJSONObject(i))
        }
    }

    val merged = JSONObject(first.toString()).apply {
        put("id", first.optString("id", executions.first().toolCallId))
        put("status", last.optString("status", first.optString("status")))
        put("summary", first.optString("summary", "Browser"))
        put("active_url", last.optString("active_url", first.optString("active_url")))
        put("active_page_id", last.optString("active_page_id", first.optString("active_page_id")))
        put("updated_at", last.optString("updated_at"))
        put("sub_toolcalls", mergedSubcalls)
        put("progress", JSONObject().apply {
            put("current_step", mergedSubcalls.length())
            put("total_steps", mergedSubcalls.length().coerceAtLeast(1))
            put("label", last.optJSONObject("progress")?.optString("label") ?: "Browser")
        })
    }

    val aggregateStatus = when (merged.optString("status")) {
        "completed", "success" -> ToolStatus.SUCCESS
        "running" -> ToolStatus.RUNNING
        "error", "cancelled", "timeout" -> ToolStatus.ERROR
        else -> executions.last().status
    }

    return executions.first().copy(
        toolCallId = "browser_visual_${UUID.nameUUIDFromBytes(executions.joinToString { it.toolCallId }.toByteArray())}",
        name = "browser",
        result = merged.toString(2),
        status = aggregateStatus,
        metadata = executions.first().metadata + mapOf("mergedBrowserCalls" to executions.size.toString())
    )
}

private fun browserToolDisplayName(tool: String): String {
    val normalized = tool.removePrefix("browser.").lowercase()
    return when (normalized) {
        "new_page", "new_tab" -> "Open new tab"
        "open_url" -> "Open page"
        "get_dom", "analyze_page", "observe" -> "Observe page"
        "get_visible_text" -> "Read visible text"
        "click", "click_element" -> "Click element"
        "tap" -> "Tap screen"
        "type", "type_text" -> "Type text"
        "clear_input" -> "Clear field"
        "press_key" -> "Press key"
        "scroll", "scroll_page", "swipe" -> "Scroll page"
        "search" -> "Search page"
        "find_element" -> "Find element"
        "wait_for_element" -> "Wait for element"
        "evaluate_script" -> "Evaluate script"
        "get_screenshot", "screenshot" -> "Capture screenshot"
        "go_back" -> "Go back"
        "go_forward" -> "Go forward"
        "reload_page", "reload" -> "Reload page"
        else -> normalized.split('_', '-').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}

private fun parseBrowserParent(result: String?): JSONObject? {
    if (result.isNullOrBlank()) return null
    return runCatching { JSONObject(result) }.getOrNull()?.takeIf { it.optString("tool") == "browser" }
}

@Composable
private fun browserStatusColor(
    status: String,
    fallback: ToolStatus,
    iosGreen: Color,
    iosBlue: Color,
    iosRed: Color
): Color = when (status.lowercase()) {
    "completed", "success" -> iosGreen
    "running", "browsing" -> iosBlue
    "paused", "waiting_input" -> Color(0xFFFFA000)
    "cancelled", "error", "timeout" -> iosRed
    else -> when (fallback) {
        ToolStatus.SUCCESS -> iosGreen
        ToolStatus.RUNNING -> iosBlue
        ToolStatus.ERROR -> iosRed
        ToolStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
}

private fun browserStatusIcon(status: String, fallback: ToolStatus) = when (status.lowercase()) {
    "completed", "success" -> Icons.Default.Check
    "running", "browsing" -> Icons.Default.Autorenew
    "paused", "waiting_input" -> Icons.Default.Pause
    "cancelled", "error", "timeout" -> Icons.Default.Close
    else -> when (fallback) {
        ToolStatus.SUCCESS -> Icons.Default.Check
        ToolStatus.RUNNING -> Icons.Default.Autorenew
        ToolStatus.ERROR -> Icons.Default.Close
        ToolStatus.PENDING -> Icons.Default.Pause
    }
}
