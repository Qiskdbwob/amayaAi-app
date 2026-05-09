package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.domain.bridge.BridgeToolError
import com.amaya.intelligence.domain.bridge.BridgeToolErrorCode
import com.amaya.intelligence.domain.bridge.BridgeToolResult
import com.amaya.intelligence.domain.bridge.BridgeToolResultStatus
import com.amaya.intelligence.tools.ErrorType
import com.amaya.intelligence.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * [BridgeToolResult] / [BridgeToolError] → existing [ToolResult] mapping.
 *
 * The Android agent loop expects string output plus optional metadata. We serialize
 * the bridge outcome as a small JSON envelope so the model can reason over it without
 * needing a typed adapter. Metadata carries machine-friendly fields for the tool-card
 * renderer.
 */
internal object WindowsBridgeToolResultMapper {

    fun toSuccess(result: BridgeToolResult): ToolResult.Success {
        // Extract image data from result before serialising to JSON so that the
        // base64 payload never lands in the conversation-history string.  Providers
        // that support vision (OpenAI, Anthropic) will attach it as a proper image
        // content block; others will receive a lightweight placeholder instead.
        val resultMap = result.result.toMutableMap()
        val imageBase64 = resultMap.remove("imageBase64") as? String
        val imageFormat = resultMap.remove("format") as? String ?: "jpeg"

        // Build a compact JSON body without the raw base64 blob.
        val body = JSONObject().apply {
            put("ok", true)
            put("tool", result.tool)
            put("status", result.status.wireName)
            put("result", mapToJson(resultMap))
            put("startedAt", result.startedAt)
            put("finishedAt", result.finishedAt)
            put("durationMs", result.durationMs)
        }
        val metadata = buildMap<String, Any> {
            put("bridge_tool_call_id", result.toolCallId)
            put("bridge_tool", result.tool)
            put("bridge_status", result.status.wireName)
            put("bridge_duration_ms", result.durationMs)
            put("executionTarget", "WINDOWS_BRIDGE")
            if (imageBase64 != null) {
                put("bridge_image_base64", imageBase64)
                put("bridge_image_format", imageFormat)
            }
        }
        return ToolResult.Success(output = body.toString(), metadata = metadata)
    }

    fun toError(error: BridgeToolError): ToolResult.Error {
        val body = JSONObject().apply {
            put("ok", false)
            put("tool", error.tool)
            put("error", JSONObject().apply {
                put("code", error.code.wireName)
                put("message", error.message)
                if (error.details.isNotEmpty()) put("details", mapToJson(error.details))
            })
        }
        return ToolResult.Error(
            message = body.toString(),
            errorType = mapErrorType(error.code),
            recoverable = error.recoverable
        )
    }

    fun timeout(toolName: String, toolCallId: String?, timeoutMs: Long?): ToolResult.Error {
        val body = JSONObject().apply {
            put("ok", false)
            put("tool", toolName)
            put("error", JSONObject().apply {
                put("code", BridgeToolErrorCode.TIMEOUT.wireName)
                put("message", "Windows Bridge tool timed out.")
                if (toolCallId != null) put("toolCallId", toolCallId)
                if (timeoutMs != null) put("timeoutMs", timeoutMs)
            })
        }
        return ToolResult.Error(
            message = body.toString(),
            errorType = ErrorType.TIMEOUT,
            recoverable = true
        )
    }

    fun cancelled(toolName: String, reason: String): ToolResult.Error {
        val body = JSONObject().apply {
            put("ok", false)
            put("tool", toolName)
            put("error", JSONObject().apply {
                put("code", BridgeToolResultStatus.CANCELLED.wireName)
                put("message", reason)
            })
        }
        return ToolResult.Error(
            message = body.toString(),
            errorType = ErrorType.EXECUTION_ERROR,
            recoverable = true
        )
    }

    fun unavailable(toolName: String, reason: String): ToolResult.Error {
        val body = JSONObject().apply {
            put("ok", false)
            put("tool", toolName)
            put("error", JSONObject().apply {
                put("code", "BRIDGE_UNAVAILABLE")
                put("message", reason)
            })
        }
        return ToolResult.Error(
            message = body.toString(),
            errorType = ErrorType.PERMISSION_ERROR,
            recoverable = true
        )
    }

    fun disabled(toolName: String): ToolResult.Error {
        val body = JSONObject().apply {
            put("ok", false)
            put("tool", toolName)
            put("error", JSONObject().apply {
                put("code", "TOOL_DISABLED")
                put("message", "Windows Bridge tool is disabled in this phase.")
            })
        }
        return ToolResult.Error(
            message = body.toString(),
            errorType = ErrorType.PERMISSION_ERROR,
            recoverable = false
        )
    }

    fun unknown(toolName: String): ToolResult.Error {
        val body = JSONObject().apply {
            put("ok", false)
            put("tool", toolName)
            put("error", JSONObject().apply {
                put("code", "UNKNOWN_TOOL")
                put("message", "Unknown Windows Bridge tool: $toolName")
            })
        }
        return ToolResult.Error(
            message = body.toString(),
            errorType = ErrorType.VALIDATION_ERROR,
            recoverable = false
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun mapErrorType(code: BridgeToolErrorCode): ErrorType = when (code) {
        BridgeToolErrorCode.INVALID_ARGS -> ErrorType.VALIDATION_ERROR
        BridgeToolErrorCode.PERMISSION_DENIED,
        BridgeToolErrorCode.APP_NOT_ALLOWED,
        BridgeToolErrorCode.PATH_NOT_ALLOWED,
        BridgeToolErrorCode.COMMAND_BLOCKED,
        BridgeToolErrorCode.APPROVAL_REQUIRED,
        BridgeToolErrorCode.APPROVAL_REJECTED -> ErrorType.PERMISSION_ERROR
        BridgeToolErrorCode.TIMEOUT -> ErrorType.TIMEOUT
        BridgeToolErrorCode.SESSION_CLOSED,
        BridgeToolErrorCode.EXECUTION_FAILED,
        BridgeToolErrorCode.UNKNOWN -> ErrorType.EXECUTION_ERROR
    }

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) obj.put(key, anyToJson(value))
        return obj
    }

    private fun anyToJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String, is Boolean, is Int, is Long, is Double, is Float -> value
        is Number -> value
        is Map<*, *> -> {
            val obj = JSONObject()
            for ((k, v) in value) obj.put(k.toString(), anyToJson(v))
            obj
        }
        is List<*> -> JSONArray().apply { value.forEach { put(anyToJson(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(anyToJson(it)) } }
        else -> value.toString()
    }
}
