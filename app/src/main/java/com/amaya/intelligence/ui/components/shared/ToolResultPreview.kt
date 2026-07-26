package com.amaya.intelligence.ui.components.shared

import android.content.Intent
import android.net.Uri
import com.amaya.intelligence.domain.models.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.amaya.intelligence.ui.components.shared.MarkdownText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * Renders a per-tool-type expanded result preview inside an expanded ToolCallCard.
 * Uses ToolCategory for primary rendering logic, with toolName for specific behavior.
 */
@Composable
fun ToolResultPreview(
    toolName: String,
    arguments: Map<String, Any?>,
    result: String,
    isDark: Boolean,
    category: ToolCategory = ToolCategory.UNKNOWN,
    onLocalhostLinkClick: ((String) -> Unit)? = null,
    uiMetadata: ToolUiMetadata? = null,
    isLocal: Boolean = false,
    isError: Boolean = false,
    isBrowserStep: Boolean = false
) {
    val codeBlockBg   = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val codeTextColor = if (isDark) Color(0xFFD1D1D6) else Color(0xFF3A3A3C)
    val metaColor     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val blockBorderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

    val redBg = Color(0xFFFF3B30).copy(alpha = 0.12f)
    val greenBg = Color(0xFF34C759).copy(alpha = 0.12f)

    @Suppress("UNCHECKED_CAST")
    val args = arguments
    val effectiveCategory = when (toolName) {
        "update_memory", "memory_manage" -> ToolCategory.MEMORY
        "skill_manage", "skill_view" -> ToolCategory.SKILL
        else -> category
    }

    fun findArg(vararg keys: String): String? {
        keys.forEach { key ->
            val v = args[key] ?: args[key.lowercase()] ?: args[key.replaceFirstChar { it.uppercase() }]
            if (v != null && v.toString().isNotBlank() && !v.toString().contains("%SAME%")) {
                return v.toString()
            }
        }
        return null
    }

    fun truncateBlock(text: String, maxChars: Int = 1800): String {
        val trimmed = text.trim()
        return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars).trimEnd() + "\n… truncated"
    }

    @Composable
    fun SectionLabel(label: String) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = metaColor)
    }

    @Composable
    fun DiffBlock(content: String) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = codeBlockBg,
            border = BorderStroke(1.dp, blockBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content.lines().forEach { line ->
                    val bgColor = when {
                        line.startsWith("-") -> redBg
                        line.startsWith("+") -> greenBg
                        else -> Color.Transparent
                    }
                    val textColor = when {
                        line.startsWith("-") -> Color(0xFFFF453A)
                        line.startsWith("+") -> Color(0xFF32D74B)
                        else -> codeTextColor
                    }
                    
                    Box(modifier = Modifier.fillMaxWidth().background(bgColor).padding(horizontal = 8.dp, vertical = 1.dp)) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 16.sp,
                                color = textColor
                            )
                        )
                    }
                }
            }
        }
    }

    if (isBrowserStep) {
        GenericResultBlock(result, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
        return
    }

    if (isLocal) {
        LocalToolResultBody(
            toolName = toolName,
            arguments = arguments,
            result = result,
            isError = isError,
            codeBlockBg = codeBlockBg,
            codeTextColor = codeTextColor,
            metaColor = metaColor,
            blockBorderColor = blockBorderColor,
            sectionLabel = { SectionLabel(it) },
            diffBlock = { DiffBlock(it) },
            onLocalhostLinkClick = onLocalhostLinkClick
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (effectiveCategory) {
            ToolCategory.FILE_IO -> {
                if (arguments.hasCanonicalFileDiff()) {
                    val target = findArg("targetContent", "TargetContent")
                    val replacement = findArg("replacementContent", "ReplacementContent", "CodeContent")

                    val diffText = buildString {
                        val chunks = (args["replacementChunks"] as? List<*>)
                            ?: (args["ReplacementChunks"] as? List<*>)
                            ?: emptyList<Any?>()
                        val normalizedChunks = chunks.mapNotNull { it as? Map<*, *> }

                        if (normalizedChunks.isNotEmpty()) {
                            normalizedChunks.forEachIndexed { idx, chunk ->
                                val t = chunk["TargetContent"]?.toString() ?: chunk["targetContent"]?.toString()
                                val r = chunk["ReplacementContent"]?.toString() ?: chunk["replacementContent"]?.toString()
                                if (!t.isNullOrBlank()) append("- $t\n")
                                if (!r.isNullOrBlank()) append("+ $r\n")
                                if (idx < normalizedChunks.size - 1) append("\n")
                            }
                        } else {
                            if (!target.isNullOrBlank()) append("- $target\n")
                            if (!replacement.isNullOrBlank()) append("+ $replacement")
                        }
                    }.trim()

                    if (diffText.isNotBlank()) {
                        DiffBlock(diffText)
                    } else if (result.isNotBlank() && !result.lowercase().contains("file updated") && !result.lowercase().contains("success")) {
                        GenericResultBlock(result, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
                    }
                } else if (result.isNotBlank()) {
                    GenericResultBlock(result, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
                }
            }

            ToolCategory.SHELL -> {
                if (result.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = codeBlockBg,
                        border = BorderStroke(1.dp, blockBorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(result.trim().take(4096).let { if (result.length > 4096) "$it…" else it },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace, lineHeight = 18.sp, fontSize = 11.sp),
                            color = codeTextColor,
                            modifier = Modifier.padding(10.dp))
                    }
                }
            }

            ToolCategory.SEARCH -> {
                val lines = result.lines().filter { it.isNotBlank() }
                if (lines.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = codeBlockBg,
                        border = BorderStroke(1.dp, blockBorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            lines.take(30).forEach { line ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                                    val icon = uiMetadata?.targetIcon?.let { mapToolIcon(it) }
                                        ?: if (effectiveCategory == ToolCategory.SEARCH) Icons.Default.Search else Icons.Default.Link
                                    Icon(icon, null, modifier = Modifier.size(12.dp), tint = metaColor)
                                    Spacer(Modifier.width(6.dp))
                                    val displayLine = line.trim()
                                    Text(displayLine, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, 
                                        color = codeTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            ToolCategory.WEB -> {
                if (result.isNotBlank()) {
                    if (toolName == "web_search" || toolName == "search_web" || toolName == "websearch") {
                        WebSearchResultBlock(result, codeBlockBg, codeTextColor, metaColor, blockBorderColor)
                    } else {
                        GenericResultBlock(result, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
                    }
                }
            }

            ToolCategory.SYSTEM, ToolCategory.TASK_MANAGEMENT -> {
            }

            ToolCategory.SKILL -> {
                SkillResultBlock(
                    toolName = toolName,
                    result = result,
                    codeBlockBg = codeBlockBg,
                    codeTextColor = codeTextColor,
                    metaColor = metaColor,
                    blockBorderColor = blockBorderColor,
                    truncateBlock = { text, max -> truncateBlock(text, max) },
                    diffBlock = { diff -> DiffBlock(diff) }
                )
            }

            ToolCategory.MEMORY -> {
                if (toolName == "update_memory" && result.isNotBlank()) {
                    MemoryReasonBlock(result, codeBlockBg, codeTextColor, metaColor, blockBorderColor)
                } else if (toolName == "memory_manage" && result.isNotBlank()) {
                    MemoryManageResultBlock(result, codeBlockBg, codeTextColor, metaColor, blockBorderColor)
                }
            }

            ToolCategory.UNKNOWN -> {
                if (result.isNotBlank() && !result.lowercase().contains("success")) {
                    GenericResultBlock(result, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
                }
            }
        }
    }
}

@Composable
private fun LocalToolResultBody(
    toolName: String,
    arguments: Map<String, Any?>,
    result: String,
    isError: Boolean,
    codeBlockBg: Color,
    codeTextColor: Color,
    metaColor: Color,
    blockBorderColor: Color,
    sectionLabel: @Composable (String) -> Unit,
    diffBlock: @Composable (String) -> Unit,
    onLocalhostLinkClick: ((String) -> Unit)?
) {
    val cleanResult = result.trim()
    if (isError) {
        sectionLabel("Error")
        GenericResultBlock(cleanResult.ifBlank { "Tool failed." }, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
        return
    }

    fun arg(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        arguments[key]?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }
    fun canonicalDiff(): String {
        val chunks = (arguments["replacementChunks"] as? List<*>)
            ?: (arguments["ReplacementChunks"] as? List<*>)
            ?: emptyList<Any?>()
        val chunkDiff = chunks.mapNotNull { it as? Map<*, *> }.joinToString("\n") { chunk ->
            val before = chunk["TargetContent"]?.toString() ?: chunk["targetContent"]?.toString()
            val after = chunk["ReplacementContent"]?.toString() ?: chunk["replacementContent"]?.toString()
            listOfNotNull(before?.let { "- $it" }, after?.let { "+ $it" }).joinToString("\n")
        }
        if (chunkDiff.isNotBlank()) return chunkDiff
        return listOfNotNull(
            arg("old_content", "targetContent", "TargetContent")?.let { "- $it" },
            arg("new_content", "replacementContent", "ReplacementContent", "CodeContent", "codeContent")?.let { "+ $it" },
            arg("diff")
        ).joinToString("\n")
    }
    fun isNoisySuccess(): Boolean {
        val normalized = cleanResult.lowercase()
        return cleanResult.isBlank() || normalized == "done" || normalized == "success" ||
            normalized.startsWith("successfully ") || normalized.startsWith("created directory:") ||
            normalized.startsWith("directory already exists:") || normalized.startsWith("moved to trash:") ||
            normalized.startsWith("permanently deleted:") || normalized.startsWith("restored ")
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (toolName) {
            // Successful reads have no body at all (see ToolCallCard); what reaches here
            // is the failure note for one file.
            "read_file" -> if (cleanResult.isNotBlank()) {
                sectionLabel(if (isError) "Error" else "Content")
                GenericResultBlock(cleanResult, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
            }
            "edit_file" -> canonicalDiff().takeIf { it.isNotBlank() }?.let {
                sectionLabel("Diff")
                diffBlock(it)
            }
            "write_file", "create_directory", "delete_file", "undo_change" -> {
                if (!isNoisySuccess()) {
                    sectionLabel("Output")
                    GenericResultBlock(cleanResult, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
                }
            }
            "list_files", "find_files", "run_shell" -> if (cleanResult.isNotBlank()) {
                sectionLabel("Output")
                GenericResultBlock(cleanResult, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
            }
            "web_search" -> if (cleanResult.isNotBlank()) {
                sectionLabel("Records")
                WebSearchResultBlock(cleanResult, codeBlockBg, codeTextColor, metaColor, blockBorderColor)
            }
            "create_reminder" -> Unit
            "update_memory" -> {
                val parsed = remember(cleanResult) { runCatching { JSONObject(cleanResult) }.getOrNull() }
                val content = parsed?.optString("content")?.takeIf { it.isNotBlank() }
                if (content != null) {
                    sectionLabel("Content")
                    GenericResultBlock(content, codeBlockBg, codeTextColor, blockBorderColor)
                }
            }
            "memory_manage" -> {
                sectionLabel("Records")
                MemoryManageResultBlock(cleanResult, codeBlockBg, codeTextColor, metaColor, blockBorderColor)
            }
            "skill_view", "skill_manage" -> {
                val parsed = remember(cleanResult) { runCatching { JSONObject(cleanResult) }.getOrNull() }
                val diff = parsed?.optString("diff")?.takeIf { it.isNotBlank() }
                val content = parsed?.optString("content")?.takeIf { it.isNotBlank() }
                when {
                    diff != null -> { sectionLabel("Diff"); diffBlock(diff) }
                    content != null -> { sectionLabel("Content"); GenericResultBlock(content, codeBlockBg, codeTextColor, blockBorderColor) }
                    cleanResult.isNotBlank() -> { sectionLabel("Records"); SkillResultBlock(toolName, cleanResult, codeBlockBg, codeTextColor, metaColor, blockBorderColor, { text, max -> if (text.length <= max) text else text.take(max) + "\n… truncated" }, diffBlock) }
                }
            }
            "session_search" -> {
                sectionLabel("Records")
                SessionSearchRecordsBlock(cleanResult, codeBlockBg, codeTextColor, metaColor, blockBorderColor)
            }
            "invoke_subagents" -> if (cleanResult.isNotBlank()) {
                sectionLabel("Nested tools")
                GenericResultBlock(cleanResult, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
            }
            else -> if (!isNoisySuccess()) {
                sectionLabel("Output")
                GenericResultBlock(cleanResult, codeBlockBg, codeTextColor, blockBorderColor, onLocalhostLinkClick)
            }
        }
    }
}

@Composable
private fun SessionSearchRecordsBlock(
    result: String,
    codeBlockBg: Color,
    codeTextColor: Color,
    metaColor: Color,
    blockBorderColor: Color
) {
    val parsed = remember(result) { runCatching { JSONObject(result) }.getOrNull() }
    val records = parsed?.optJSONArray("results")
    if (records == null || records.length() == 0) {
        Text("No previous chats found.", style = MaterialTheme.typography.labelSmall, color = metaColor)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(records.length().coerceAtMost(5)) { index ->
            val record = records.optJSONObject(index) ?: return@repeat
            val summary = record.optString("summary").ifBlank { record.optString("matchedText") }.ifBlank { "Previous chat" }
            val matched = record.optString("matchedText").takeIf { it.isNotBlank() && it != summary }
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = codeBlockBg,
                border = BorderStroke(1.dp, blockBorderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(summary, style = MaterialTheme.typography.labelSmall, color = codeTextColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    matched?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, color = metaColor, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun SkillResultBlock(
    toolName: String,
    result: String,
    codeBlockBg: Color,
    codeTextColor: Color,
    metaColor: Color,
    blockBorderColor: Color,
    truncateBlock: (String, Int) -> String,
    diffBlock: @Composable (String) -> Unit
) {
    val parsed = remember(result) { runCatching { JSONObject(result) }.getOrNull() }
    if (parsed == null) {
        if (result.contains("User declined", ignoreCase = true)) {
            SkillSummaryCard("Declined", "", "Skill was not changed.", emptyList(), codeBlockBg, codeTextColor, metaColor, blockBorderColor)
        } else {
            GenericResultBlock(truncateBlock(result, 1800), codeBlockBg, codeTextColor, blockBorderColor)
        }
        return
    }

    val action = parsed.optString("action").ifBlank { if (toolName == "skill_view") "view" else "skill" }
    val name = parsed.optString("name")
    val description = parsed.optString("description")
    val content = parsed.optString("content")
    val diff = parsed.optString("diff")
    val status = parsed.optString("status")
    val summary = parsed.optString("summary")
    val metadata = parsed.optJSONObject("metadata")
    val tagsArray = parsed.optJSONArray("tags") ?: metadata?.optJSONArray("tags")
    val tags = tagsArray?.let { array -> List(array.length()) { idx -> array.optString(idx) }.filter { it.isNotBlank() } }.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (action.lowercase()) {
            "create" -> {
                Text("SKILL.md", style = MaterialTheme.typography.labelSmall, color = metaColor)
                if (content.isNotBlank()) {
                    GenericResultBlock(truncateBlock(content, 1800), codeBlockBg, codeTextColor, blockBorderColor)
                } else {
                    SkillSummaryCard("Created", name, description, tags, codeBlockBg, codeTextColor, metaColor, blockBorderColor)
                }
            }
            "update", "patch" -> {
                if (diff.isNotBlank()) {
                    Text("Diff", style = MaterialTheme.typography.labelSmall, color = metaColor)
                    diffBlock(truncateBlock(diff, 2200))
                } else if (content.isNotBlank()) {
                    Text("SKILL.md", style = MaterialTheme.typography.labelSmall, color = metaColor)
                    GenericResultBlock(truncateBlock(content, 1800), codeBlockBg, codeTextColor, blockBorderColor)
                }
                if (summary.isNotBlank()) {
                    Text(summary, style = MaterialTheme.typography.labelSmall, color = metaColor)
                }
            }
            "view" -> {
                val displayName = name.ifBlank { metadata?.optString("name").orEmpty() }
                val displayDescription = description.ifBlank { metadata?.optString("description").orEmpty() }
                val displayStatus = metadata?.optString("status").orEmpty()
                val used = metadata?.optInt("usageCount", 0) ?: 0
                SkillSummaryCard(
                    title = displayName.ifBlank { "Skill" },
                    subtitle = displayDescription,
                    tags = tags + listOfNotNull(
                        displayStatus.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
                        "Used $used"
                    ),
                    codeBlockBg = codeBlockBg,
                    codeTextColor = codeTextColor,
                    metaColor = metaColor,
                    blockBorderColor = blockBorderColor
                )
            }
            "delete", "archive" -> {
                val label = when {
                    status.equals("declined", ignoreCase = true) -> "Declined"
                    action.equals("delete", ignoreCase = true) -> "Deleted"
                    else -> "Archived"
                }
                SkillSummaryCard(label, name, if (status.equals("declined", true)) "Skill was not deleted." else "Skill operation completed.", emptyList(), codeBlockBg, codeTextColor, metaColor, blockBorderColor)
            }
            else -> SkillSummaryCard(action.replaceFirstChar { it.uppercase() }, name, description, tags, codeBlockBg, codeTextColor, metaColor, blockBorderColor)
        }
    }
}

@Composable
private fun SkillSummaryCard(
    title: String,
    subtitle: String,
    description: String = "",
    tags: List<String> = emptyList(),
    codeBlockBg: Color,
    codeTextColor: Color,
    metaColor: Color,
    blockBorderColor: Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = codeBlockBg,
        border = BorderStroke(1.dp, blockBorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = codeTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = codeTextColor.copy(alpha = 0.82f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            description.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp, fontSize = 10.sp), color = metaColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (tags.isNotEmpty()) {
                Text(tags.take(5).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = metaColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MemoryReasonBlock(
    result: String,
    codeBlockBg: Color,
    codeTextColor: Color,
    metaColor: Color,
    blockBorderColor: Color
) {
    val parsed = remember(result) { runCatching { JSONObject(result) }.getOrNull() }
    val proposal = parsed?.optJSONObject("proposal") ?: parsed?.optJSONObject("memory")
    val reason = proposal?.optString("reason")?.takeIf { it.isNotBlank() }
        ?: parsed?.optString("reason")?.takeIf { it.isNotBlank() }
        ?: result.takeIf { it.isNotBlank() && !it.trim().startsWith("{") }
        ?: return

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = codeBlockBg,
        border = BorderStroke(1.dp, blockBorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Reason",
                style = MaterialTheme.typography.labelSmall,
                color = metaColor
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                color = codeTextColor
            )
        }
    }
}

@Composable
private fun MemoryManageResultBlock(
    result: String,
    codeBlockBg: Color,
    codeTextColor: Color,
    metaColor: Color,
    blockBorderColor: Color
) {
    val parsed = remember(result) { runCatching { JSONObject(result) }.getOrNull() }
    if (parsed == null) {
        GenericResultBlock(result, codeBlockBg, codeTextColor, blockBorderColor)
        return
    }

    val records = remember(parsed) {
        val list = mutableListOf<JSONObject>()
        val results = parsed.optJSONArray("results")
        if (results != null) {
            repeat(results.length()) { index ->
                results.optJSONObject(index)?.let { list += it }
            }
        } else if (parsed.has("id") || parsed.has("content") || parsed.has("type")) {
            list += parsed
        }
        list
    }

    if (records.isEmpty()) {
        Text("No saved memory found.", style = MaterialTheme.typography.labelSmall, color = metaColor)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (records.size > 5) {
            Text(
                "Showing 5 of ${records.size} memories. Results truncated.",
                style = MaterialTheme.typography.labelSmall,
                color = metaColor
            )
        }
        records.take(5).forEach { memory ->
            val content = memory.optString("content")
            if (content.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = codeBlockBg,
                    border = BorderStroke(1.dp, blockBorderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        content,
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp, fontSize = 10.sp),
                        color = codeTextColor.copy(alpha = 0.84f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WebSearchResultBlock(
    result: String,
    codeBlockBg: Color,
    codeTextColor: Color,
    metaColor: Color,
    blockBorderColor: Color
) {
    val parsed = remember(result) { runCatching { JSONObject(result) }.getOrNull() }
    val pages = parsed?.optJSONArray("pages")
    if (parsed == null || pages == null) {
        GenericResultBlock(result, codeBlockBg, codeTextColor, blockBorderColor)
        return
    }

    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val summary = parsed.optString("summary")
        if (summary.isNotBlank()) {
            Text(summary, style = MaterialTheme.typography.labelSmall, color = metaColor)
        }

        repeat(pages.length().coerceAtMost(5)) { index ->
            val page = pages.optJSONObject(index) ?: return@repeat
            val title = page.optString("title").ifBlank { "Result ${index + 1}" }
            val url = page.optString("url")
            val snippet = page.optString("snippet")
            val error = page.optString("error")

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = codeBlockBg,
                border = BorderStroke(1.dp, blockBorderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = url.isNotBlank()) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, null, modifier = Modifier.size(13.dp), tint = metaColor)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.labelMedium,
                            color = codeTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.OpenInBrowser,
                            null,
                            modifier = Modifier.size(15.dp),
                            tint = metaColor
                        )
                    }
                    if (url.isNotBlank()) {
                        Text(url, style = MaterialTheme.typography.labelSmall, color = metaColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        error.isNotBlank() -> Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF453A), fontSize = 10.sp)
                        snippet.isNotBlank() -> Text(
                            snippet.take(180),
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp, fontSize = 10.sp),
                            color = codeTextColor.copy(alpha = 0.78f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenericResultBlock(
    result: String,
    codeBlockBg: Color,
    codeTextColor: Color,
    blockBorderColor: Color,
    onLocalhostLinkClick: ((String) -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = codeBlockBg,
        border = BorderStroke(1.dp, blockBorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        MarkdownText(text = result.trim(), color = codeTextColor, modifier = Modifier.padding(10.dp), compact = true, onLocalhostLinkClick = onLocalhostLinkClick)
    }
}
