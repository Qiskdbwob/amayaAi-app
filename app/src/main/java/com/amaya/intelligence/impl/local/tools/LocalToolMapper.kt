package com.amaya.intelligence.impl.local.tools

import com.amaya.intelligence.impl.common.mappers.ToolUiMapper
import com.amaya.intelligence.domain.models.ToolUiMetadata

/**
 * Local-specific tool name and argument mapper.
 * Maps local AI tool names to Amaya's standard format.
 */
object LocalToolMapper {

    private fun firstNonNull(map: Map<String, Any?>, vararg keys: String): Any? {
        keys.forEach { key ->
            if (map.containsKey(key)) {
                val value = map[key]
                if (value != null && value.toString().isNotBlank()) return value
            }
        }
        return null
    }

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

    /**
     * Normalizes arguments based on the tool name.
     */
    fun mapToolArgs(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        val capabilityCall = com.amaya.intelligence.tools.CapabilityToolMapper.map(toolName, args)
        val normalizedName = mapToolName(capabilityCall?.handlerName ?: toolName)
        val mapped = (capabilityCall?.arguments ?: args).toMutableMap()

        mapped["summary"] = firstNonNull(
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
                mapped["command"] = firstNonNull(mapped, "command", "cmd", "CommandLine", "commandLine")
                mapped.remove("cwd")
                mapped.remove("Cwd")
                mapped.remove("DirectoryPath")
                mapped.remove("directory")
                mapped.remove("working_dir")
            }
            "check_status_terminal" -> {
                mapped["commandId"] = firstNonNull(mapped, "commandId", "CommandId", "ProcessID", "processId", "PID")
                mapped["waitSeconds"] = firstNonNull(mapped, "waitSeconds", "WaitDurationSeconds", "WaitTime") ?: "0"
                mapped["maxChars"] = firstNonNull(mapped, "maxChars", "OutputCharacterCount", "MaxChars")
            }
            "read_file" -> {
                mapped["path"] = firstNonNull(mapped, "path", "file", "filePath", "AbsolutePath")
            }
            "write_file", "edit_file" -> {
                mapped["path"] = firstNonNull(mapped, "path", "file", "filePath", "TargetFile")
                mapped["targetContent"] = firstNonNull(mapped, "targetContent", "TargetContent")
                mapped["replacementContent"] = firstNonNull(
                    mapped,
                    "replacementContent",
                    "ReplacementContent",
                    "CodeContent",
                    "codeContent"
                )
                mapped["replacementChunks"] = firstNonNull(mapped, "replacementChunks", "ReplacementChunks")
            }
            "find_files", "grep_search" -> {
                mapped["query"] = firstNonNull(mapped, "query", "pattern", "search", "Query")
                mapped["path"] = firstNonNull(mapped, "path", "directory", "SearchPath", "SearchDirectory")
            }
            "list_files" -> {
                mapped["path"] = firstNonNull(mapped, "path", "directory", "DirectoryPath")
            }
            "task_boundary" -> {
                mapped["taskStatus"] = firstNonNull(mapped, "taskStatus", "TaskStatus", "status", "status_text")
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
