package com.amaya.intelligence.tools

import com.squareup.moshi.JsonClass

/**
 * Base result type for all tool executions.
 */
sealed class ToolResult {

    /**
     * Tool executed successfully.
     */
    data class Success(
        val output: String,
        val metadata: Map<String, Any> = emptyMap()
    ) : ToolResult()

    /**
     * Tool execution failed.
     */
    data class Error(
        val message: String,
        val errorType: ErrorType = ErrorType.EXECUTION_ERROR,
        val recoverable: Boolean = false
    ) : ToolResult()

    /**
     * Tool requires user confirmation before proceeding.
     */
    data class RequiresConfirmation(
        val reason: String,
        val details: String
    ) : ToolResult()
}

enum class ErrorType {
    VALIDATION_ERROR,    // Input validation failed
    PERMISSION_ERROR,    // Insufficient permissions
    NOT_FOUND,           // File/resource not found
    EXECUTION_ERROR,     // Runtime error during execution
    TIMEOUT,             // Operation timed out
    SIZE_LIMIT,          // File too large
    SECURITY_VIOLATION   // Security guardrail triggered
}

enum class ToolVisibility {
    MODEL,
    INTERNAL
}

/** Host-owned execution data. Model arguments never enter this object. */
data class ToolExecutionContext(
    val toolCallId: String? = null,
    val workspacePath: String? = null,
    val onEvent: (suspend (Any) -> Unit)? = null,
    val providerConnection: com.amaya.intelligence.data.remote.api.ProviderConnection? = null,
    val selectedModelId: String? = null,
    val conversationId: String? = null,
    val confirmed: Boolean = false,
    /** Host-enforced subagent mode. Model arguments cannot change this. */
    val readOnly: Boolean = false
)

/** Implement only when a tool needs host-owned execution context. */
interface ContextAwareTool {
    suspend fun execute(arguments: Map<String, Any?>, context: ToolExecutionContext): ToolResult
}

/**
 * Information about a file for tool responses.
 */
@JsonClass(generateAdapter = true)
data class FileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val extension: String?
)

/**
 * Base interface for all tools.
 */
interface Tool {
    val name: String
    val description: String
    val visibility: ToolVisibility get() = ToolVisibility.MODEL

    suspend fun execute(arguments: Map<String, Any?>): ToolResult
}
