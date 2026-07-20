package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.impl.local.tools.LocalToolMapper
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.ToolUiMetadata

/** Resolve the one-line header label for a tool card. */
internal fun resolveToolCallHeaderText(
    execution: ToolExecution,
    uiMeta: ToolUiMetadata?,
    @Suppress("UNUSED_PARAMETER") showApprovalActions: Boolean,
    approvalPending: Boolean
): String {
    if (execution.metadata["source"].equals("local", ignoreCase = true)) {
        val label = LocalToolMapper.displayLabel(execution.name, execution.arguments)
        return if (approvalPending) "Tools: $label" else localToolHeader(execution) ?: label
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

/** Semantic local-tool header: verb + target, present/past by status. */
internal fun localToolHeader(execution: ToolExecution): String? {
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
        "delegate_agent" -> arg("title") ?: "Delegation"
        else -> null
    }
}

internal fun localToolPath(arguments: Map<String, Any?>): String? =
    listOf("path", "TargetFile", "AbsolutePath", "file", "filePath", "working_dir")
        .firstNotNullOfOrNull { key -> arguments[key]?.toString()?.takeIf { it.isNotBlank() } }
        ?.replace("/", "/\u200B")
        ?.replace("\\", "\\\u200B")

internal fun isNoisyLocalSuccess(result: String): Boolean {
    val normalized = result.trim().lowercase()
    return normalized.isBlank() || normalized == "done" || normalized == "success" ||
        normalized.startsWith("successfully ") || normalized.startsWith("created directory:") ||
        normalized.startsWith("directory already exists:") || normalized.startsWith("moved to trash:") ||
        normalized.startsWith("permanently deleted:") || normalized.startsWith("restored ")
}

internal fun deriveThinkingTitle(raw: String?): String? {
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
