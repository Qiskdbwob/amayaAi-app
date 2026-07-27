package com.amaya.intelligence.impl.local.tools

import com.amaya.intelligence.impl.common.mappers.ToolUiMapper
import com.amaya.intelligence.domain.models.ToolUiMetadata
import com.amaya.intelligence.tools.firstToolArgument

/**
 * Local-specific tool name and argument mapper.
 * Maps local AI tool names to Amaya's standard format.
 */
object LocalToolMapper {


    /**
     * Maps local tool names to normalized names.
     * Local tools already use standard naming, so this is mostly pass-through.
     */
    fun mapToolName(rawName: String): String = when (rawName) {
        "read", "view_file" -> "read_file"
        "write", "create_file" -> "write_file"
        "replace_file_content", "multi_replace_file_content" -> "edit_file"
        "delete" -> "delete_file"
        "list_dir", "ls" -> "list_files"
        "run_command", "shell", "execute" -> "run_shell"
        "find", "search_files" -> "find_files"
        "websearch" -> "web_search"
        else -> rawName
    }

    fun mapDisplayToolName(toolName: String, args: Map<String, Any?>): String =
        com.amaya.intelligence.tools.CapabilityToolMapper.displayName(toolName, args).let(::mapToolName)

    /** Compact host-owned label shared by chat, drawer, and notifications. */
    fun displayLabel(toolName: String, args: Map<String, Any?>): String {
        val normalizedName = mapDisplayToolName(toolName, args)
        val normalizedArgs = mapToolArgs(toolName, args)
        fun value(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            normalizedArgs[key]?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        }
        fun fileName(): String? = value("path", "file", "filePath", "TargetFile", "AbsolutePath")
            ?.replace('\\', '/')?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        fun quoted(raw: String?) = raw?.take(40)?.let { "“$it”" }
        fun batchCount(): Int? = (normalizedArgs["paths"] as? List<*>)
            ?.count { (it as? String)?.isNotBlank() == true }
            ?.takeIf { it > 0 }
        return when (normalizedName) {
            "read_file" -> fileName()?.let { "Read $it" } ?: batchCount()?.let { "Read $it files" }
            "write_file" -> fileName()?.let { "Write $it" }
            "edit_file" -> fileName()?.let { "Edit $it" }
            "delete_file" -> fileName()?.let { "Delete $it" }
            "create_directory" -> fileName()?.let { "Create $it" }
            "list_files" -> fileName()?.let { "List $it" }
            "find_files", "grep_search" -> quoted(value("query", "pattern", "content"))?.let { "Find $it" }
            "run_shell" -> value("command", "cmd", "CommandLine")?.lineSequence()?.firstOrNull()?.take(56)?.let { "Run $it" }
            "web_search" -> quoted(value("query"))?.let { "Search $it" }
            "session_search" -> quoted(value("query"))?.let { "Search chats for $it" }
            "delegate_agent" -> value("title")?.take(48)?.let { "Delegate $it" } ?: "Delegate Agent"
            "invoke_subagents" -> value("title")?.take(48) ?: "Parallel research"
            else -> null
        } ?: getUiMetadata(toolName, args).label.takeIf(String::isNotBlank) ?: normalizedName.replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * Normalizes arguments based on the tool name.
     */
    fun mapToolArgs(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        val capabilityCall = com.amaya.intelligence.tools.CapabilityToolMapper.map(toolName, args)
        val normalizedName = mapToolName(capabilityCall?.handlerName ?: toolName)
        val mapped = (capabilityCall?.arguments ?: args).toMutableMap()

        mapped["summary"] = firstToolArgument(
            mapped,
            "summary",
            "Summary",
            "Description",
            "description",
            "Instruction",
            "instruction",
            "TaskSummary",
            "taskSummary"
        )

        when (normalizedName) {
            "run_shell" -> {
                mapped["command"] = firstToolArgument(mapped, "command", "cmd", "CommandLine", "commandLine")
                mapped.remove("cwd")
                mapped.remove("Cwd")
                mapped.remove("DirectoryPath")
                mapped.remove("directory")
                mapped.remove("working_dir")
            }
            "check_status_terminal" -> {
                mapped["commandId"] = firstToolArgument(mapped, "commandId", "CommandId", "ProcessID", "processId", "PID")
                mapped["waitSeconds"] = firstToolArgument(mapped, "waitSeconds", "WaitDurationSeconds", "WaitTime") ?: "0"
                mapped["maxChars"] = firstToolArgument(mapped, "maxChars", "OutputCharacterCount", "MaxChars")
            }
            "read_file" -> {
                mapped["path"] = firstToolArgument(mapped, "path", "file", "filePath", "AbsolutePath")
            }
            "write_file", "edit_file" -> {
                mapped["path"] = firstToolArgument(mapped, "path", "file", "filePath", "TargetFile")
                mapped["targetContent"] = firstToolArgument(mapped, "targetContent", "TargetContent")
                mapped["replacementContent"] = firstToolArgument(
                    mapped,
                    "replacementContent",
                    "ReplacementContent",
                    "CodeContent",
                    "codeContent"
                )
                mapped["replacementChunks"] = firstToolArgument(mapped, "replacementChunks", "ReplacementChunks")
            }
            "find_files", "grep_search" -> {
                mapped["query"] = firstToolArgument(mapped, "query", "pattern", "search", "Query")
                mapped["path"] = firstToolArgument(mapped, "path", "directory", "SearchPath", "SearchDirectory")
            }
            "list_files" -> {
                mapped["path"] = firstToolArgument(mapped, "path", "directory", "DirectoryPath")
            }
            "task_boundary" -> {
                mapped["taskStatus"] = firstToolArgument(mapped, "taskStatus", "TaskStatus", "status", "status_text")
            }
        }

        return mapped.filterValues { it != null }
    }

    /**
     * Gets UI metadata for rendering.
     */
    fun getUiMetadata(toolName: String, args: Map<String, Any?>, metadata: Map<String, String>? = null): ToolUiMetadata {
        val normalizedName = mapDisplayToolName(toolName, args)
        val normalizedArgs = mapToolArgs(toolName, args)
        return ToolUiMapper.getToolUiMetadata(normalizedName, normalizedArgs, metadata)
    }
}
