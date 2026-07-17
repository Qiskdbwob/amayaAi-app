package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.*

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── ToolCallCard ─────────────────────────────────────────────────────────────

internal object ToolCallMotion {
    val motionSpec: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
    val mountFadeIn = fadeIn(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))
    val enter = expandVertically(animationSpec = motionSpec) + fadeIn(tween(durationMillis = 180, easing = FastOutSlowInEasing))
    val exit = shrinkVertically(animationSpec = motionSpec) + fadeOut(tween(durationMillis = 140, easing = FastOutSlowInEasing))
}

@Composable
internal fun ToolLeadIconPill(
    icon: ToolInfoIcon,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier.size(width = 28.dp, height = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = mapToolIcon(icon),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = tint.copy(alpha = 0.88f)
            )
        }
    }
}

private fun resolveToolCallHeaderText(
    execution: ToolExecution,
    uiMeta: ToolUiMetadata?,
    showApprovalActions: Boolean,
    approvalPending: Boolean
): String {
    if (execution.metadata["source"].equals("local", ignoreCase = true)) {
        localToolHeader(execution)?.let { return it }
    }

    if (execution.isSyntheticThinkingCard()) {
        val explicit = uiMeta?.label?.takeIf { it.isNotBlank() && !it.equals("Thinking", ignoreCase = true) }
        return explicit ?: deriveThinkingTitle(execution.result) ?: "Thinking"
    }

    if (!execution.isShellTool()) {
        return uiMeta?.label?.takeIf { it.isNotBlank() }
            ?: execution.arguments["path"]?.toString()?.substringAfterLast("/")?.substringAfterLast("\\")?.takeIf { it.isNotBlank() }
            ?: execution.arguments["TargetFile"]?.toString()?.substringAfterLast("/")?.substringAfterLast("\\")?.takeIf { it.isNotBlank() }
            ?: execution.arguments["command"]?.toString()?.takeIf { it.isNotBlank() }
            ?: execution.name
    }

    val command = execution.arguments["command"]?.toString()
        ?: execution.arguments["CommandLine"]?.toString()
        ?: execution.arguments["commandLine"]?.toString()
        ?: execution.arguments["submittedCommandLine"]?.toString()
        ?: execution.arguments["proposedCommandLine"]?.toString()
        ?: execution.arguments["cmd"]?.toString()

    return command
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: uiMeta?.label?.takeIf { it.isNotBlank() }
        ?: execution.name
}

private fun localToolHeader(execution: ToolExecution): String? {
    fun arg(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        execution.arguments[key]?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }
    fun fileName(): String? = arg("path", "TargetFile", "AbsolutePath", "file", "filePath")
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    fun verb(present: String, past: String) = if (execution.status == ToolStatus.SUCCESS) past else present
    fun command(): String? = arg("command", "CommandLine", "commandLine", "cmd")
        ?.trim()?.lineSequence()?.firstOrNull()?.take(56)

    if (execution.metadata["groupedChild"].equals("true", ignoreCase = true)) {
        return when (execution.name) {
            "read_file", "write_file", "edit_file", "create_directory", "delete_file", "undo_change", "list_files" -> fileName()
            "find_files" -> arg("content", "pattern", "query")
            "run_shell" -> command()
            "web_search", "session_search" -> arg("query")
            "create_reminder", "update_memory" -> arg("title", "content")
            "memory_manage", "skill_view", "skill_manage" -> execution.uiMetadata?.label
            else -> null
        }
    }

    return when (execution.name) {
        "read_file" -> arg("paths")?.let { "Read files" } ?: fileName()?.let { "Read $it" }
        "write_file" -> fileName()?.let { "${verb("Write", "Wrote")} $it" }
        "edit_file" -> fileName()?.let { "${verb("Edit", "Edited")} $it" }
        "create_directory" -> fileName()?.let { "${verb("Create", "Created")} $it" }
        "delete_file" -> fileName()?.let {
            when {
                execution.status != ToolStatus.SUCCESS -> "Delete $it"
                execution.arguments["permanent"] == true -> "Deleted $it"
                else -> "Moved $it to trash"
            }
        }
        "undo_change" -> fileName()?.let { "${verb("Restore", "Restored")} $it" }
        "list_files" -> fileName()?.let { "${verb("List", "Listed")} $it" }
        "find_files" -> arg("content", "pattern", "query")?.let { "${verb("Find", "Found")} files for $it" }
        "run_shell" -> command()?.let { "${verb("Run", "Ran")} $it" }
        "web_search" -> arg("query")?.let { "${verb("Search", "Searched")} $it" }
        "create_reminder" -> arg("title")?.let { "${verb("Schedule", "Scheduled")} $it" }
        "update_memory" -> arg("title", "content")?.let { "${verb("Save", "Saved")} $it" }
        "memory_manage" -> execution.uiMetadata?.label
        "skill_view", "skill_manage" -> execution.uiMetadata?.label
        "session_search" -> if (execution.status == ToolStatus.SUCCESS) "Previous chats" else "Search previous chats"
        "invoke_subagents" -> arg("title") ?: "Parallel work"
        else -> null
    }
}

private fun deriveThinkingTitle(raw: String?): String? {
    val lines = raw
        ?.replace(Regex("</?think>", RegexOption.IGNORE_CASE), " ")
        ?.trim()
        ?.lines()
        ?.map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    if (lines.isEmpty()) return null

    val first = lines.first()
        .replace(Regex("^#{1,6}\\s+"), "")
        .replace(Regex("^(?:\\*\\*|__)(.+?)(?:\\*\\*|__)\\s*:?.*$")) { it.groupValues[1] }
        .trim()
        .removeSuffix(":")
        .trim()

    val sentenceEnd = first.indexOfAny(charArrayOf('.', '!', '?'))
    val sentence = if (sentenceEnd in 2..80) first.substring(0, sentenceEnd + 1) else first
    return sentence
        .replace(Regex("\\s+"), " ")
        .take(56)
        .trim()
        .takeIf { it.length >= 3 }
}

@Composable
internal fun ToolCallAnimatedSection(
    visible: Boolean,
    modifier: Modifier = Modifier,
    initiallyVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val visibilityState = remember { MutableTransitionState(initiallyVisible) }
    LaunchedEffect(visible) {
        visibilityState.targetState = visible
    }

    AnimatedVisibility(
        visibleState = visibilityState,
        enter = ToolCallMotion.enter,
        exit = ToolCallMotion.exit,
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun ToolCallCard(
    execution: ToolExecution,
    onAccept: (() -> Unit)? = null,
    onDecline: (() -> Unit)? = null,
    onLocalhostLinkClick: ((String) -> Unit)? = null,
    onInteraction: () -> Unit = {},
    embeddedText: String? = null
) {
    val shouldAnimate = execution.metadata["animateOnMount"].equals("true", ignoreCase = true)
    var visible by remember(execution.toolCallId) { mutableStateOf(!shouldAnimate) }

    if (shouldAnimate) {
        LaunchedEffect(execution.toolCallId) {
            visible = true
        }
    }

    if (shouldAnimate) {
        AnimatedVisibility(
            visible = visible,
            enter = ToolCallMotion.mountFadeIn
        ) {
            ToolCardContent(execution, onAccept, onDecline, onLocalhostLinkClick, onInteraction, embeddedText)
        }
    } else {
        ToolCardContent(execution, onAccept, onDecline, onLocalhostLinkClick, onInteraction, embeddedText)
    }
}

// ── ToolCardContent (internal) ───────────────────────────────────────────────

@Composable
internal fun ToolCardContent(
    execution: ToolExecution,
    onAccept: (() -> Unit)? = null,
    onDecline: (() -> Unit)? = null,
    onLocalhostLinkClick: ((String) -> Unit)? = null,
    onInteraction: () -> Unit = {},
    embeddedText: String? = null
) {
    if (execution.name == "browser") {
        BrowserToolCallCard(execution = execution, onInteraction = onInteraction, embeddedText = embeddedText)
        return
    }

    val isThinkingCard = execution.isSyntheticThinkingCard()
    val isLocal = execution.metadata["source"].equals("local", ignoreCase = true)
    var expanded by remember(execution.toolCallId) { mutableStateOf(false) }
    var approvalSubmitted by remember(execution.toolCallId, execution.metadata["approvalState"]) { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val isTerminalApprovalCandidate = execution.metadata["isTerminal"].equals("true", ignoreCase = true)
        || execution.isShellTool()
    val approvalRequired = execution.metadata["approvalRequired"].equals("true", ignoreCase = true)
        || (isTerminalApprovalCandidate && execution.status == ToolStatus.PENDING)
    val approvalPending = execution.metadata["approvalState"].equals("pending", ignoreCase = true)
        || (approvalRequired && execution.status == ToolStatus.PENDING)
    val showApprovalActions = approvalRequired && approvalPending && onAccept != null && onDecline != null
    LaunchedEffect(showApprovalActions) {
        if (showApprovalActions) expanded = true
    }

    val iosGreen = Color(0xFF34C759)
    val iosBlue  = Color(0xFF007AFF)
    val iosRed   = MaterialTheme.colorScheme.error

    val isSubagent = execution.name == "invoke_subagents"
    val isWebSearch = execution.name == "web_search" || execution.name == "search_web" || execution.name == "websearch"
    val isMemoryManage = execution.name == "memory_manage"
    val hasLocalSemanticBody = execution.status == ToolStatus.ERROR ||
        showApprovalActions ||
        localToolPath(execution.arguments) != null ||
        when (execution.name) {
            "read_file", "list_files", "find_files", "run_shell", "web_search", "update_memory",
            "memory_manage", "skill_view", "skill_manage", "session_search", "invoke_subagents" ->
                !execution.result.isNullOrBlank()
            "edit_file" -> execution.hasCanonicalFileDiff() || !execution.result.isNullOrBlank()
            "write_file", "create_directory", "delete_file", "undo_change" ->
                !execution.result.isNullOrBlank() && !isNoisyLocalSuccess(execution.result.orEmpty())
            else -> false
        }
    val canExpand = when {
        isThinkingCard -> !execution.result.isNullOrBlank()
        isLocal -> hasLocalSemanticBody
        else -> ((execution.status == ToolStatus.SUCCESS || execution.status == ToolStatus.ERROR) &&
            (execution.result != null || execution.children.isNotEmpty() || execution.arguments.isNotEmpty())) ||
            (execution.status == ToolStatus.RUNNING && execution.arguments.isNotEmpty())
    }
    val showChildren = isSubagent && execution.children.isNotEmpty() && expanded

    val uiMeta = execution.uiMetadata

    val bgColor = when (execution.status) {
        ToolStatus.ERROR   -> if (isDark) iosRed.copy(alpha = 0.10f)  else iosRed.copy(alpha = 0.06f)
        ToolStatus.SUCCESS -> MaterialTheme.colorScheme.surfaceContainerLow
        ToolStatus.RUNNING -> if (isDark) iosBlue.copy(alpha = 0.08f) else iosBlue.copy(alpha = 0.04f)
        ToolStatus.PENDING -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
    }
    val statusColor = when (execution.status) {
        ToolStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        ToolStatus.RUNNING -> iosBlue
        ToolStatus.SUCCESS -> iosGreen
        ToolStatus.ERROR   -> iosRed
    }
    val statusIcon = when (execution.status) {
        ToolStatus.PENDING -> Icons.Default.Pause
        ToolStatus.RUNNING -> Icons.Default.Autorenew
        ToolStatus.SUCCESS -> Icons.Default.Check
        ToolStatus.ERROR   -> Icons.Default.Close
    }
    val blockBorderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

    val shouldShimmer = execution.status == ToolStatus.RUNNING ||
        execution.children.any { it.status == ToolStatus.RUNNING }
    val shimmerProgress = if (shouldShimmer) {
        val shimmerTransition = rememberInfiniteTransition(label = "tool_shimmer")
        val animated by shimmerTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
            label = "shimmer_x"
        )
        animated
    } else {
        0f
    }

    val isTaskBoundary = execution.isTaskBoundaryTool()
    val hasTaskBoundaryArgs = isTaskBoundary && (
        execution.arguments["title"] != null ||
            execution.arguments["TaskName"] != null ||
            execution.arguments["TaskSummary"] != null ||
            execution.arguments["description"] != null
        )
    val genericResultStrings = setOf(
        "done", "success", "completed", "file updated", "file written",
        "directory listed", "search complete", "user notified",
        "file read", "file created", "read", "write", "written", "completed successfully"
    )
    val normalizedResult = execution.result?.trim()?.lowercase() ?: ""
    val isGenericResult = normalizedResult in genericResultStrings || normalizedResult.contains("success")

    val hasInjectedPreview = execution.hasCanonicalFileDiff()

    val isTerminal = execution.isShellTool()

    val shouldShowResult = !isThinkingCard && (execution.result != null && execution.result.isNotBlank() &&
        !isTaskBoundary &&
        execution.uiMetadata?.actionIcon != ToolInfoIcon.MESSAGE &&
        (isWebSearch || !isGenericResult || hasInjectedPreview || isTerminal))

    val thinkingVisible = isThinkingCard && !execution.result.isNullOrBlank() && expanded
    val hasResultDetails = expanded && !isSubagent && !isThinkingCard && (execution.result != null || execution.arguments.isNotEmpty())
    val hasSubagentResultDetails = expanded && isSubagent && execution.children.isEmpty() && execution.result != null
    val approvalSectionVisible = showApprovalActions && !approvalSubmitted
    val headerText = resolveToolCallHeaderText(execution, uiMeta, showApprovalActions, approvalPending)

    Surface(
        shape    = RoundedCornerShape(14.dp),
        color    = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // HEADER ROW
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (canExpand) Modifier.clickable { expanded = !expanded; onInteraction() } else Modifier)
                    .padding(horizontal = 12.dp, vertical = if (isThinkingCard) 6.dp else 9.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolLeadIconPill(uiMeta?.actionIcon ?: ToolInfoIcon.TASK)

                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )

                Text(
                    text       = headerText,
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Normal,
                    color      = MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier
                        .weight(1f)
                        .then(
                            if (execution.status == ToolStatus.RUNNING)
                                Modifier
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        val w = size.width
                                        val peakX = (shimmerProgress * (w * 3f)) - w
                                        val hw = w * 0.6f
                                        drawRect(
                                            brush = Brush.linearGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 1f),
                                                    Color.White.copy(alpha = 0.7f),
                                                    Color.White.copy(alpha = 0.3f),
                                                    Color.White.copy(alpha = 0f),
                                                    Color.White.copy(alpha = 0.3f),
                                                    Color.White.copy(alpha = 0.7f),
                                                    Color.White.copy(alpha = 1f)
                                                ),
                                                start = Offset(peakX - hw, 0f),
                                                end   = Offset(peakX + hw, 0f)
                                            ),
                                            blendMode = BlendMode.DstIn
                                        )
                                    }
                            else Modifier
                        )
                )

                if (execution.status == ToolStatus.RUNNING || execution.status == ToolStatus.PENDING) {
                    Icon(statusIcon, null, modifier = Modifier.size(14.dp), tint = statusColor)
                }

                if (canExpand) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            ToolCallAnimatedSection(visible = thinkingVisible) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                    border = BorderStroke(1.dp, blockBorderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                ) {
                    MarkdownText(
                        text = execution.result.orEmpty().take(1500),
                        color = MaterialTheme.colorScheme.onSurface,
                        compact = true,
                        modifier = Modifier.padding(10.dp),
                        onLocalhostLinkClick = onLocalhostLinkClick
                    )
                }
            }

            ToolCallAnimatedSection(visible = approvalSectionVisible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(
                        color = if (isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.15f),
                        thickness = 1.dp
                    )
                    Text(
                        text = execution.metadata["approvalReason"]?.takeIf { it.isNotBlank() } ?: "Waiting for approval",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!isLocal && (isTerminal || execution.uiMetadata?.category == ToolCategory.MEMORY || execution.uiMetadata?.category == ToolCategory.SKILL) && execution.arguments.isNotEmpty()) {
                        ToolArgumentsPreview(
                            toolName = execution.name,
                            arguments = execution.arguments,
                            isDark = isDark,
                            category = execution.uiMetadata?.category ?: ToolCategory.UNKNOWN,
                            uiMetadata = execution.uiMetadata
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!approvalSubmitted && approvalSectionVisible) {
                                    approvalSubmitted = true
                                    onDecline?.invoke()
                                }
                            },
                            enabled = !approvalSubmitted,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Decline")
                        }
                        Button(
                            onClick = {
                                if (!approvalSubmitted && approvalSectionVisible) {
                                    approvalSubmitted = true
                                    onAccept?.invoke()
                                }
                            },
                            enabled = !approvalSubmitted,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Accept")
                        }
                    }
                }
            }

            ToolCallAnimatedSection(visible = showChildren) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    execution.children.forEach { child ->
                        key(child.index) {
                            SubagentChildCard(
                                child           = child,
                                isDark          = isDark,
                                iosGreen        = iosGreen,
                                iosBlue         = iosBlue,
                                iosRed          = iosRed,
                                shimmerProgress = shimmerProgress,
                                onInteraction   = onInteraction
                            )
                        }
                    }
                }
            }

            ToolCallAnimatedSection(visible = hasResultDetails) {
                HorizontalDivider(
                    color = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.12f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )

                if (isLocal) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        localToolPath(execution.arguments)?.let { path ->
                            Text(
                                text = path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
                                softWrap = true
                            )
                        }
                        ToolResultPreview(
                            toolName = execution.name,
                            arguments = execution.arguments,
                            result = execution.result.orEmpty(),
                            isDark = isDark,
                            category = execution.uiMetadata?.category ?: ToolCategory.UNKNOWN,
                            onLocalhostLinkClick = onLocalhostLinkClick,
                            uiMetadata = execution.uiMetadata,
                            isLocal = true,
                            isError = execution.status == ToolStatus.ERROR
                        )
                    }
                } else {
                    if (execution.arguments.isNotEmpty() && !isWebSearch && !isMemoryManage) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, bottom = if (shouldShowResult) 8.dp else 12.dp)
                        ) {
                            ToolArgumentsPreview(
                                toolName = execution.name,
                                arguments = execution.arguments,
                                isDark = isDark,
                                category = execution.uiMetadata?.category ?: ToolCategory.UNKNOWN,
                                uiMetadata = execution.uiMetadata,
                                result = execution.result
                            )
                        }
                        if (shouldShowResult) {
                            HorizontalDivider(
                                color = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.12f),
                                thickness = 0.8.dp,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                            )
                        }
                    }

                    if (shouldShowResult) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        ) {
                            ToolResultPreview(
                                toolName = execution.name,
                                arguments = execution.arguments,
                                result = execution.result ?: "",
                                isDark = isDark,
                                category = execution.uiMetadata?.category ?: ToolCategory.UNKNOWN,
                                onLocalhostLinkClick = onLocalhostLinkClick,
                                uiMetadata = execution.uiMetadata
                            )
                        }
                    }
                }
            }

            ToolCallAnimatedSection(visible = hasSubagentResultDetails) {
                HorizontalDivider(
                    color = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.12f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                )
                Surface(
                    shape    = RoundedCornerShape(8.dp),
                    color    = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                    border   = BorderStroke(1.dp, blockBorderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    MarkdownText(
                        text     = (execution.result ?: "").take(3000),
                        color    = MaterialTheme.colorScheme.onSurface,
                        compact  = true,
                        modifier = Modifier.padding(10.dp),
                        onLocalhostLinkClick = onLocalhostLinkClick
                    )
                }
            }
        }
    }
}

private fun localToolPath(arguments: Map<String, Any?>): String? =
    listOf("path", "TargetFile", "AbsolutePath", "file", "filePath", "working_dir")
        .firstNotNullOfOrNull { key -> arguments[key]?.toString()?.takeIf { it.isNotBlank() } }
        ?.replace("/", "/\u200B")
        ?.replace("\\", "\\\u200B")

private fun isNoisyLocalSuccess(result: String): Boolean {
    val normalized = result.trim().lowercase()
    return normalized.isBlank() || normalized == "done" || normalized == "success" ||
        normalized.startsWith("successfully ") || normalized.startsWith("created directory:") ||
        normalized.startsWith("directory already exists:") || normalized.startsWith("moved to trash:") ||
        normalized.startsWith("permanently deleted:") || normalized.startsWith("restored ")
}

// ── SubagentChildCard ────────────────────────────────────────────────────────

@Composable
internal fun SubagentChildCard(
    child: SubagentExecution,
    isDark: Boolean,
    iosGreen: Color,
    iosBlue: Color,
    iosRed: Color,
    shimmerProgress: Float,
    onInteraction: () -> Unit = {}
) {
    var expanded by remember(child.index) { mutableStateOf(false) }

    val statusColor = when (child.status) {
        ToolStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        ToolStatus.RUNNING -> iosBlue
        ToolStatus.SUCCESS -> iosGreen
        ToolStatus.ERROR   -> iosRed
    }
    val bgColor = when (child.status) {
        ToolStatus.SUCCESS -> if (isDark) iosGreen.copy(alpha = 0.07f) else iosGreen.copy(alpha = 0.04f)
        ToolStatus.ERROR   -> if (isDark) iosRed.copy(alpha = 0.10f)  else iosRed.copy(alpha = 0.06f)
        ToolStatus.RUNNING -> if (isDark) iosBlue.copy(alpha = 0.10f) else iosBlue.copy(alpha = 0.05f)
        ToolStatus.PENDING -> if (isDark) Color(0xFF2C2C2E)           else MaterialTheme.colorScheme.surfaceContainerLowest
    }
    val canExpand = child.result != null &&
        (child.status == ToolStatus.SUCCESS || child.status == ToolStatus.ERROR)

    Surface(
        shape    = RoundedCornerShape(10.dp),
        color    = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // HEADER ROW
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (canExpand) Modifier.clickable { expanded = !expanded; onInteraction() } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape    = CircleShape,
                    color    = statusColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (child.status == ToolStatus.RUNNING) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color       = iosBlue
                            )
                        } else {
                            Text(
                                text       = "${child.index + 1}",
                                style      = MaterialTheme.typography.labelSmall,
                                fontSize   = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color      = statusColor
                            )
                        }
                    }
                }

                Text(
                    text       = child.taskName,
                    style      = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color      = if (child.status == ToolStatus.PENDING)
                                     MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                 else MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier
                        .weight(1f)
                        .then(
                            if (child.status == ToolStatus.RUNNING)
                                Modifier
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        val w = size.width
                                        val peakX = (shimmerProgress * (w * 3f)) - w
                                        val hw = w * 0.6f
                                        drawRect(
                                            brush = Brush.linearGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 1f),
                                                    Color.White.copy(alpha = 0.7f),
                                                    Color.White.copy(alpha = 0.3f),
                                                    Color.White.copy(alpha = 0f),
                                                    Color.White.copy(alpha = 0.3f),
                                                    Color.White.copy(alpha = 0.7f),
                                                    Color.White.copy(alpha = 1f)
                                                ),
                                                start = Offset(peakX - hw, 0f),
                                                end   = Offset(peakX + hw, 0f)
                                            ),
                                            blendMode = BlendMode.DstIn
                                        )
                                    }
                            else Modifier
                        )
                )

                when (child.status) {
                    ToolStatus.SUCCESS -> Surface(
                        shape = RoundedCornerShape(20.dp), color = iosGreen.copy(alpha = 0.15f)
                    ) {
                        Text("Done", style = MaterialTheme.typography.labelSmall,
                            color = iosGreen, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    ToolStatus.ERROR -> Surface(
                        shape = RoundedCornerShape(20.dp), color = iosRed.copy(alpha = 0.15f)
                    ) {
                        Text("Failed", style = MaterialTheme.typography.labelSmall,
                            color = iosRed, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    ToolStatus.RUNNING -> Icon(
                        Icons.Default.Autorenew,
                        null,
                        modifier = Modifier.size(13.dp),
                        tint = iosBlue
                    )
                    ToolStatus.PENDING -> Text(
                        "Pending", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }

                if (canExpand) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            // EXPANDABLE CONTENT
            AnimatedVisibility(
                visible = expanded && child.result != null,
                enter = ToolCallMotion.enter,
                exit = ToolCallMotion.exit
            ) {
                var showFull by remember(child.index) { mutableStateOf(false) }
                val truncateAt    = 2000
                val isTruncatable = (child.result?.length ?: 0) > truncateAt
                val displayText   = if (showFull || !isTruncatable) child.result ?: ""
                                    else child.result!!.take(truncateAt)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                ) {
                    HorizontalDivider(
                        color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Surface(
                        shape    = RoundedCornerShape(8.dp),
                        color    = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                        border   = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            MarkdownText(
                                text     = displayText,
                                color    = MaterialTheme.colorScheme.onSurface,
                                compact  = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (isTruncatable) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text       = if (showFull) "Show less"
                                                 else "\u2026 Show ${(child.result?.length ?: 0) - truncateAt} more chars",
                                    style      = MaterialTheme.typography.labelSmall,
                                    color      = iosBlue,
                                    fontWeight = FontWeight.Medium,
                                    modifier   = Modifier
                                        .clickable { showFull = !showFull; onInteraction() }
                                        .padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun mapToolIcon(icon: ToolInfoIcon): ImageVector {
    return when (icon) {
        ToolInfoIcon.EDIT      -> Icons.Default.Edit
        ToolInfoIcon.READ      -> Icons.Default.Visibility
        ToolInfoIcon.WRITE     -> Icons.Default.Add
        ToolInfoIcon.RUN       -> Icons.Default.Terminal
        ToolInfoIcon.CHECK     -> Icons.Default.CheckCircle
        ToolInfoIcon.SEARCH    -> Icons.Default.Search
        ToolInfoIcon.WEB_READ  -> Icons.Default.Language
        ToolInfoIcon.MESSAGE   -> Icons.Default.ChatBubble
        ToolInfoIcon.LIST      -> Icons.AutoMirrored.Filled.FormatListBulleted
        ToolInfoIcon.FIND      -> Icons.AutoMirrored.Filled.ManageSearch
        ToolInfoIcon.TASK      -> Icons.Default.Flag
        ToolInfoIcon.BROWSER   -> Icons.Default.Language
        ToolInfoIcon.DOCS      -> Icons.AutoMirrored.Filled.MenuBook
        ToolInfoIcon.GENERATE  -> Icons.Default.AutoAwesome
        ToolInfoIcon.FILE      -> Icons.Default.Description
        ToolInfoIcon.FOLDER    -> Icons.Default.Folder
        ToolInfoIcon.COMMAND   -> Icons.Default.PlayArrow
        ToolInfoIcon.TERMINAL  -> Icons.Default.Terminal
        ToolInfoIcon.WORLD     -> Icons.Default.Public
        ToolInfoIcon.LINK      -> Icons.Default.Link
        ToolInfoIcon.PERSON    -> Icons.Default.Person
        ToolInfoIcon.CHUNK     -> Icons.Default.Extension
        ToolInfoIcon.ROCKET    -> Icons.Default.RocketLaunch
        ToolInfoIcon.MOUSE     -> Icons.Default.Mouse
        ToolInfoIcon.BOOK      -> Icons.Default.Book
        ToolInfoIcon.IMAGE     -> Icons.Default.Image
        ToolInfoIcon.DELETE    -> Icons.Default.Delete
        ToolInfoIcon.BRAIN     -> Icons.Default.Psychology
        ToolInfoIcon.LIGHTBULB -> Icons.Default.Lightbulb
    }
}
data class ToolExecutionGroup(
    val key: String,
    val startIndex: Int,
    val endIndex: Int,
    val executions: List<ToolExecution>,
    val isActive: Boolean
)

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

