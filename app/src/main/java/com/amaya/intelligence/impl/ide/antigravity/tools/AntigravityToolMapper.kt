package com.amaya.intelligence.impl.ide.antigravity.tools

import com.amaya.intelligence.impl.common.mappers.ToolUiMapper
import com.amaya.intelligence.domain.models.ToolUiMetadata
import com.amaya.intelligence.tools.firstToolArgument
import com.amaya.intelligence.impl.ide.antigravity.AntigravityProtocol

/**
 * Antigravity-specific tool name and argument mapper.
 *
 * Maps Antigravity's internal step type names (CORTEX_STEP_TYPE_*) to
 * Amaya's standard tool names used by ToolCallCard and ToolResultPreview.
 */
object AntigravityToolMapper {


    /**
     * Maps the raw tool name from Antigravity to a normalized name.
     */
    fun mapToolName(rawName: String): String {
        val normalized = rawName
            .removePrefix("CORTEX_STEP_TYPE_")
            .lowercase()
        return when (normalized) {
            // File operations
            "read_file", "view_file", "view_file_outline", "view_code_item" -> "read_file"
            "write_file", "write_to_file" -> "write_file"
            "edit_file", "code_action", "replace_file_content", "multi_replace_file_content" -> "edit_file"
            "delete_file" -> "delete_file"

            // Shell / terminal
            "run_command", "run_shell" -> "run_shell"
            "command_status", "check_status_terminal" -> "check_status_terminal"
            "send_command_input" -> "send_command_input"
            "read_terminal" -> "read_terminal"

            // Search / find
            "search", "find", "grep_search", "grep", "find_by_name", "find_files", "search_files" -> "find_files"
            "list_directory", "list_dir", "list_files" -> "list_files"

            // Browser / web tools
            "browser_subagent", "browser", "read_url_content", "search_web" -> "browser"

            // Task management
            "task_boundary" -> "task_boundary"
            "notify_user" -> "notify_user"

            // Image generation
            "generate_image" -> "generate_image"

            // MCP tools
            else -> if (rawName.startsWith("mcp_")) rawName else normalized.ifBlank { rawName }
        }
    }

    /**
     * Normalizes arguments based on the mapped tool name.
     */
    fun mapToolArgs(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        val normalizedName = mapToolName(toolName)
        val mapped = args.toMutableMap()

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
                mapped["command"] = firstToolArgument(
                    mapped,
                    "command",
                    "CommandLine",
                    "commandLine",
                    "submittedCommandLine",
                    "proposedCommandLine",
                    "cmd"
                ) ?: mapped.values.firstOrNull()?.toString()
                mapped["cwd"] = firstToolArgument(mapped, "cwd", "Cwd", "DirectoryPath", "searchPath")
            }
            "check_status_terminal" -> {
                mapped["commandId"] = firstToolArgument(mapped, "commandId", "CommandId", "ProcessID", "processId", "PID")
                mapped["waitSeconds"] = firstToolArgument(mapped, "waitSeconds", "WaitDurationSeconds", "waitDurationSeconds", "WaitTime", "waitTime") ?: "0"
                mapped["maxChars"] = firstToolArgument(mapped, "maxChars", "OutputCharacterCount", "outputCharacterCount", "MaxChars")
            }
            "send_command_input" -> {
                mapped["command"] = "Input " + (mapped["Input"] ?: mapped["CommandId"] ?: "")
            }
            "read_terminal" -> {
                mapped["command"] = "Read " + (mapped["ProcessID"] ?: "")
            }
            "read_file" -> {
                mapped["path"] = firstToolArgument(mapped, "path", "AbsolutePath", "absolutePath", "absolutePathUri", "File", "file", "filePath", "uri")
            }
            "write_file" -> {
                mapped["path"] = firstToolArgument(mapped, "path", "TargetFile", "targetFile", "AbsolutePath", "absolutePath", "File", "file", "filePath", "uri")
                mapped["targetContent"] = firstToolArgument(mapped, "targetContent", "TargetContent")
                mapped["replacementContent"] = firstToolArgument(mapped, "replacementContent", "ReplacementContent", "CodeContent", "codeContent")
                mapped["replacementChunks"] = firstToolArgument(mapped, "replacementChunks", "ReplacementChunks")
            }
            "edit_file" -> {
                mapped["path"] = firstToolArgument(mapped, "path", "TargetFile", "targetFile", "AbsolutePath", "absolutePath", "File", "file", "filePath", "uri")
                mapped["targetContent"] = firstToolArgument(mapped, "targetContent", "TargetContent")
                mapped["replacementContent"] = firstToolArgument(mapped, "replacementContent", "ReplacementContent", "CodeContent", "codeContent")
                mapped["replacementChunks"] = firstToolArgument(mapped, "replacementChunks", "ReplacementChunks")
            }
            "find_files" -> {
                mapped["query"] = firstToolArgument(mapped, "query", "Query", "content", "Pattern", "pattern")
                mapped["path"] = firstToolArgument(mapped, "path", "SearchPath", "searchPath", "SearchDirectory", "searchDirectory", "DirectoryPath", "directoryPath")
                mapped["pattern"] = mapped["Pattern"] ?: mapped["pattern"]
            }
            "list_files" -> {
                mapped["path"] = firstToolArgument(mapped, "path", "DirectoryPath", "directoryPath", "directoryPathUri", "SearchDirectory", "searchDirectory")
            }
            "browser" -> {
                mapped["task"] = mapped["Task"] ?: mapped["task"] ?: mapped["Url"] ?: mapped["url"] ?: mapped["query"] ?: mapped["task"]
            }
            "view_code_item" -> {
                mapped["path"] = mapped["File"] ?: mapped["file"] ?: mapped["filePath"] ?: mapped["path"]
            }
            "read_url_content" -> {
                mapped["task"] = mapped["Url"] ?: mapped["url"] ?: mapped["task"]
            }
            "search_web" -> {
                mapped["task"] = mapped["query"] ?: mapped["Query"] ?: mapped["task"]
            }
            "task_boundary" -> {
                mapped["title"] = mapped["TaskName"] ?: mapped["taskName"] ?: mapped["title"]
                mapped["summary"] = mapped["TaskSummary"] ?: mapped["taskSummary"] ?: mapped["summary"]
                mapped["taskStatus"] = mapped["TaskStatus"] ?: mapped["taskStatus"] ?: mapped["status_text"]
            }
            "notify_user" -> {
                mapped["content"] = mapped["Message"] ?: mapped["message"] ?: mapped["content"]
            }
            "generate_image" -> {
                mapped["prompt"] = mapped["Prompt"] ?: mapped["prompt"]
            }
        }

        // Map generic metadata that applies to all tools
        if (mapped["complexity"] == null) mapped["complexity"] = mapped["Complexity"] ?: mapped["complexity"]
        if (mapped["content"] == null) mapped["content"] = mapped["Description"] ?: mapped["description"] ?: mapped["Instruction"] ?: mapped["instruction"]

        // Ensure original_name is preserved
        mapped["original_name"] = args["original_name"] ?: toolName

        return mapped
    }

    /**
     * Gets UI metadata for rendering.
     */
    fun getUiMetadata(toolName: String, args: Map<String, Any?>, metadata: Map<String, String>? = null): ToolUiMetadata {
        val normalizedName = mapToolName(toolName)
        val normalizedArgs = mapToolArgs(toolName, args)
        val ui = ToolUiMapper.getToolUiMetadata(normalizedName, normalizedArgs, metadata)
        if (ui.label.isNotBlank()) return ui
        return ui.copy(label = fallbackLabel(normalizedName, normalizedArgs))
    }

    private fun fallbackLabel(toolName: String, args: Map<String, Any?>): String {
        fun value(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            args[key]?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        }
        fun fileName(path: String?): String? = path
            ?.replace('/', '\\')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }

        return when (toolName) {
            "read_file", "write_file", "edit_file", "delete_file" ->
                fileName(value("path", "TargetFile", "targetFile", "AbsolutePath", "absolutePath", "File", "file", "filePath"))
                    ?: toolName
            "list_files" ->
                fileName(value("path", "DirectoryPath", "directoryPath", "SearchDirectory", "searchDirectory"))
                    ?: "Files"
            "find_files" ->
                value("query", "Query", "pattern", "Pattern", "content")?.take(56) ?: "Find files"
            "run_shell" ->
                value("command", "CommandLine", "commandLine", "submittedCommandLine", "proposedCommandLine", "cmd")?.take(88)
                    ?: "Shell"
            "browser" ->
                value("task", "Task", "url", "Url", "query", "Query")?.take(56) ?: "Browser"
            "task_boundary" ->
                value("title", "TaskName", "taskName", "taskStatus", "TaskStatus")?.take(56) ?: "Task"
            else -> toolName.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    /**
     * Extracts a human-readable result from raw data.
     */
    fun extractToolResult(rawData: Any?): String {
        return when (rawData) {
            is String -> rawData
            is Map<*, *> -> {
                (rawData["output"] ?: rawData["result"] ?: rawData.toString()).toString()
            }
            null -> ""
            else -> rawData.toString()
        }
    }
}
