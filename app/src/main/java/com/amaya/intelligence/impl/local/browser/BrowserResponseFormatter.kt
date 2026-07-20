package com.amaya.intelligence.impl.local.browser

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object BrowserResponseFormatter {
    fun newCallId(): String = "call_${UUID.randomUUID().toString().take(8)}"

    fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }.format(Date())

    fun subToolResponse(
        id: String,
        parentCallId: String,
        tool: String,
        status: String,
        durationMs: Long,
        session: JSONObject,
        requestParams: Map<String, Any?>,
        result: Any?,
        safety: JSONObject = defaultSafety(),
        summary: String,
        agentStatus: BrowserAgentStatus,
        error: JSONObject? = null
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("parent_call_id", parentCallId)
        put("tool", "browser.$tool")
        put("status", status)
        put("timestamp", nowIso())
        put("duration_ms", durationMs)
        put("session", session)
        put("request", JSONObject().put("params", toJsonValue(redactParams(requestParams))))
        put("result", toJsonValue(result))
        put("safety", safety)
        put("ui", JSONObject().apply {
            put("summary", summary)
            put("agent_status", agentStatus.name.lowercase())
            put("expandable", true)
        })
        put("error", error ?: JSONObject.NULL)
    }

    fun parentTask(
        id: String,
        summary: String,
        status: String,
        sessionId: String,
        browserId: String,
        activePageId: String,
        activeUrl: String,
        startedAt: String,
        updatedAt: String,
        currentStep: Int,
        totalSteps: Int,
        label: String,
        subToolcalls: List<JSONObject>
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("tool", "browser")
        put("type", "parent_toolcall")
        put("status", status)
        put("summary", summary)
        put("session_id", sessionId)
        put("browser_id", browserId)
        put("active_page_id", activePageId)
        put("active_url", activeUrl)
        put("started_at", startedAt)
        put("updated_at", updatedAt)
        put("progress", JSONObject().apply {
            put("current_step", currentStep)
            put("total_steps", totalSteps)
            put("label", label)
        })
        put("sub_toolcalls", JSONArray().apply {
            subToolcalls.forEach { sub ->
                put(JSONObject().apply {
                    put("id", sub.optString("id"))
                    put("tool", sub.optString("tool"))
                    put("status", sub.optString("status"))
                    put("summary", sub.optJSONObject("ui")?.optString("summary") ?: sub.optString("status"))
                    put("response", compactSubToolResponse(sub))
                })
            }
        })
        subToolcalls.lastOrNull()?.let { latest ->
            put("agent", compactAgentContext(latest))
        }
        put("ui", JSONObject().apply {
            put("expandable", true)
            put("show_as_single_chat_tool", true)
            put("nested_subtools", true)
        })
    }

    fun successStatus(response: BrowserToolResponse): String = when (response) {
        is BrowserToolResponse.Success -> "success"
        is BrowserToolResponse.Failure -> if (response.recoverable) "error" else "cancelled"
        is BrowserToolResponse.SafetyPause -> "paused"
    }

    fun resultFor(action: String, response: BrowserToolResponse): Any? = when (response) {
        is BrowserToolResponse.Success -> normalizeResult(action, response)
        is BrowserToolResponse.Failure -> normalizeFailureResult(response)
        is BrowserToolResponse.SafetyPause -> null
    }

    fun summaryFor(action: String, response: BrowserToolResponse): String = when (response) {
        is BrowserToolResponse.Success -> response.output.take(180)
        is BrowserToolResponse.Failure -> response.message.take(180)
        is BrowserToolResponse.SafetyPause -> response.prompt.reason.take(180)
    }.ifBlank { action.split('_').joinToString(" ") }

    fun safetyFor(response: BrowserToolResponse): JSONObject = when (response) {
        is BrowserToolResponse.SafetyPause -> JSONObject().apply {
            put("sensitive_detected", true)
            put("sensitive_type", inferSensitiveType(response.prompt.fieldLabel ?: response.prompt.selector ?: response.prompt.reason))
            put("requires_user_decision", true)
            put("reason", response.prompt.reason)
            put("allowed_next_actions", JSONArray(listOf(
                "fill_manually", "allow_once", "allow_for_this_site", "skip_step", "cancel_task", "resume_after_user_done"
            )))
        }
        else -> defaultSafety()
    }

    fun errorFor(action: String, response: BrowserToolResponse, pageUrl: String, params: Map<String, Any?>): JSONObject? {
        if (response !is BrowserToolResponse.Failure) return null
        val code = when {
            response.message.contains("missing query", ignoreCase = true) -> "MISSING_QUERY"
            response.message.contains("missing selector", ignoreCase = true) || response.message.contains("missing selector/element_id", ignoreCase = true) -> "MISSING_TARGET"
            response.message.contains("not found", ignoreCase = true) -> "ELEMENT_NOT_FOUND"
            response.message.contains("timeout", ignoreCase = true) || response.message.contains("timed out", ignoreCase = true) -> "TIMEOUT"
            response.message.contains("network", ignoreCase = true) -> "NETWORK_ERROR"
            response.message.contains("permission", ignoreCase = true) -> "ANDROID_PERMISSION_PROMPT"
            response.message.contains("cancel", ignoreCase = true) -> "CANCELLED"
            else -> "BROWSER_ACTION_FAILED"
        }
        return JSONObject().apply {
            put("code", code)
            put("message", response.message)
            put("recoverable", response.recoverable)
            put("suggested_action", suggestedAction(code))
            put("details", JSONObject().apply {
                put("action", action)
                put("page_url", pageUrl)
                put("params", toJsonValue(redactParams(params)))
            })
        }
    }

    fun agentStatusFor(response: BrowserToolResponse): BrowserAgentStatus = when (response) {
        is BrowserToolResponse.Success -> BrowserAgentStatus.IDLE
        is BrowserToolResponse.Failure -> if (response.recoverable) BrowserAgentStatus.ERROR else BrowserAgentStatus.CANCELLED
        is BrowserToolResponse.SafetyPause -> BrowserAgentStatus.WAITING_INPUT
    }

    fun defaultSafety(): JSONObject = JSONObject().apply {
        put("sensitive_detected", false)
        put("sensitive_type", JSONObject.NULL)
        put("requires_user_decision", false)
        put("reason", JSONObject.NULL)
        put("allowed_next_actions", JSONArray())
    }

    fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> value
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k?.toString() ?: return@forEach, toJsonValue(v)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Boolean, is Number, is String -> value
        else -> value.toString()
    }

    private fun normalizeFailureResult(response: BrowserToolResponse.Failure): Any? {
        val dom = response.metadata["dom"]?.toString()
        val base = if (dom != null) normalizeDom(dom) else JSONObject()
        response.metadata["element"]?.let { base.put("element", parseJsonOrString(it.toString())) }
        response.metadata["blocker"]?.let { base.put("blocker", parseJsonOrString(it.toString())) }
        if (base.length() == 0) return null
        return base.apply {
            put("failure_message", response.message)
            put("retry_hint", "Do not repeat the same failed call. Pick an element_id from interactive_elements, clear blocker if present, or ask the user if no matching element exists.")
        }
    }

    private fun normalizeResult(action: String, response: BrowserToolResponse.Success): Any {
        val metadata = response.metadata
        return when (action) {
            "get_dom", "analyze_page" -> normalizeDom(metadata["dom"]?.toString() ?: response.output)
            "get_visible_text" -> JSONObject().apply {
                put("mode", "visible_text")
                put("text", response.output)
                put("truncated", response.output.length > 6000)
                put("length", response.output.length)
            }
            "screenshot" -> JSONObject().apply {
                put("captured", true)
                put("mime_type", metadata["mime_type"] ?: "image/jpeg")
                put("width", metadata["width"] ?: JSONObject.NULL)
                put("height", metadata["height"] ?: JSONObject.NULL)
                put("image_ref", metadata["image_base64"]?.let { "base64:${it.toString().length}chars" } ?: JSONObject.NULL)
            }
            "find_element", "wait_for_element" -> JSONObject().apply {
                put("element", parseJsonOrString(response.output))
            }
            else -> JSONObject().apply {
                put("message", response.output.take(1000))
                put("page", JSONObject().apply {
                    put("url", metadata["url"] ?: JSONObject.NULL)
                    put("title", metadata["title"] ?: JSONObject.NULL)
                })
                metadata["element"]?.let { put("element", parseJsonOrString(it.toString())) }
                if (metadata.containsKey("page_changed")) {
                    put("page_changed", metadata["page_changed"] ?: false)
                    put("page_before", JSONObject().apply {
                        put("url", metadata["before_url"] ?: JSONObject.NULL)
                        put("title", metadata["before_title"] ?: JSONObject.NULL)
                    })
                    put("page_after", JSONObject().apply {
                        put("url", metadata["after_url"] ?: JSONObject.NULL)
                        put("title", metadata["after_title"] ?: JSONObject.NULL)
                    })
                }
            }
        }
    }

    private fun normalizeDom(domJson: String): JSONObject {
        val root = runCatching { JSONObject(domJson) }.getOrNull() ?: JSONObject()
        val interactive = JSONArray()
        var sensitiveCount = 0
        listOf("inputs", "buttons", "links", "cards").forEach { key ->
            val arr = root.optJSONArray(key) ?: JSONArray()
            for (i in 0 until arr.length()) {
                if (interactive.length() >= 80) break
                val node = arr.optJSONObject(i) ?: continue
                val sensitive = node.optBoolean("sensitive", false)
                if (sensitive) sensitiveCount++
                interactive.put(JSONObject().apply {
                    put("element_id", node.optString("element_id", "el_${interactive.length() + 1}"))
                    put("tag", node.optString("tag"))
                    put("role", node.optString("role"))
                    put("type", node.optString("type"))
                    put("name", node.optString("name"))
                    put("placeholder", node.optString("placeholder"))
                    put("label", node.optString("label"))
                    put("accessible_name", node.optString("accessible_name"))
                    put("data_testid", node.optString("data_testid"))
                    put("title", node.optString("title"))
                    put("text_preview", node.optString("text").take(160))
                    put("selector", node.optString("selector"))
                    put("click_selector", node.optString("click_selector"))
                    put("visible", node.optBoolean("visible", true))
                    put("enabled", node.optBoolean("enabled", true))
                    put("sensitive", sensitive)
                    put("sensitive_type", if (sensitive) inferSensitiveType(node.toString()) else JSONObject.NULL)
                    put("bounds", node.optJSONObject("bounds") ?: JSONObject.NULL)
                    put("center", node.optJSONObject("center") ?: JSONObject.NULL)
                    put("focused", node.optBoolean("focused", false))
                    put("editable", node.optBoolean("editable", false))
                    put("clickable", node.optBoolean("clickable", false))
                    put("actions", node.optJSONArray("actions") ?: JSONArray())
                })
            }
        }
        return JSONObject().apply {
            put("page", JSONObject().apply {
                put("url", root.optString("url"))
                put("title", root.optString("title"))
            })
            put("dom", JSONObject().apply {
                put("mode", root.optString("mode", "interactive_summary"))
                put("nodes_count", interactive.length())
                put("truncated", root.optBoolean("truncated", false) || interactive.length() >= 80)
                put("interactive_elements", interactive)
                put("forms", root.optJSONArray("forms") ?: JSONArray())
                put("editor_clusters", root.optJSONArray("editor_clusters") ?: JSONArray())
                put("sensitive_fields", sensitiveCount)
                put("visible_text_preview", root.optString("visible_text_preview").take(1200))
            })
        }
    }

    private fun compactSubToolResponse(sub: JSONObject): JSONObject = JSONObject().apply {
        put("tool", sub.optString("tool"))
        put("status", sub.optString("status"))
        put("summary", sub.optJSONObject("ui")?.optString("summary") ?: "")
        put("result", compactResult(sub.opt("result")))
        put("error", sub.opt("error"))
        val safety = sub.optJSONObject("safety")
        if (safety != null && safety.optBoolean("sensitive_detected", false)) put("safety", safety)
    }

    private fun compactAgentContext(latest: JSONObject): JSONObject = JSONObject().apply {
        val result = compactResult(latest.opt("result"))
        put("latest_tool", latest.optString("tool"))
        put("latest_status", latest.optString("status"))
        put("latest_summary", latest.optJSONObject("ui")?.optString("summary") ?: "")
        put("latest_result", result)
        put("error", latest.opt("error"))
        put("safety", latest.opt("safety"))
        if (result is JSONObject) {
            result.optJSONObject("page")?.let { put("page", it) }
            result.optJSONObject("dom")?.let { dom ->
                put("interactive_elements", dom.optJSONArray("interactive_elements") ?: JSONArray())
                put("forms", dom.optJSONArray("forms") ?: JSONArray())
                put("editor_clusters", dom.optJSONArray("editor_clusters") ?: JSONArray())
                put("visible_text_preview", dom.optString("visible_text_preview"))
            }
            result.optJSONObject("element")?.let { put("element", it) }
        }
        put("hint", "Use element_id from interactive_elements for click/type. If latest_status is error, observe/search or ask the user.")
    }

    private fun compactResult(value: Any?): Any {
        if (value !is JSONObject) return value ?: JSONObject.NULL
        val dom = value.optJSONObject("dom") ?: return value
        return JSONObject(value.toString()).apply {
            put("dom", JSONObject(dom.toString()).apply {
                put("visible_text_preview", optString("visible_text_preview").take(700))
                put("interactive_elements", compactElements(optJSONArray("interactive_elements"), 30))
            })
        }
    }

    private fun compactElements(elements: JSONArray?, limit: Int): JSONArray {
        val out = JSONArray()
        if (elements == null) return out
        for (i in 0 until minOf(elements.length(), limit)) {
            val node = elements.optJSONObject(i) ?: continue
            out.put(JSONObject().apply {
                put("element_id", node.optString("element_id"))
                put("tag", node.optString("tag"))
                put("role", node.optString("role"))
                put("type", node.optString("type"))
                put("name", node.optString("name"))
                put("placeholder", node.optString("placeholder"))
                put("label", node.optString("label").take(120))
                put("accessible_name", node.optString("accessible_name").take(140))
                put("data_testid", node.optString("data_testid").take(80))
                put("title", node.optString("title").take(120))
                put("text_preview", node.optString("text_preview").take(100))
                put("selector", node.optString("selector"))
                put("click_selector", node.optString("click_selector"))
                put("visible", node.optBoolean("visible", true))
                put("enabled", node.optBoolean("enabled", true))
                put("sensitive", node.optBoolean("sensitive", false))
                put("sensitive_type", node.opt("sensitive_type"))
                put("bounds", node.opt("bounds"))
                put("center", node.opt("center"))
                put("focused", node.optBoolean("focused", false))
                put("editable", node.optBoolean("editable", false))
                put("clickable", node.optBoolean("clickable", false))
                put("actions", node.optJSONArray("actions") ?: JSONArray())
            })
        }
        return out
    }

    private fun parseJsonOrString(raw: String): Any = runCatching {
        if (raw.trim().startsWith("[")) JSONArray(raw) else JSONObject(raw)
    }.getOrElse { raw.take(1000) }

    private fun redactParams(params: Map<String, Any?>): Map<String, Any?> = params.mapValues { (key, value) ->
        val k = key.lowercase()
        if (k.contains("password") || k.contains("otp") || k.contains("token") || k.contains("secret") || k.contains("text")) {
            if (value == null) null else "[redacted:${value.toString().length}]"
        } else value
    }

    private fun inferSensitiveType(raw: String): String {
        val text = raw.lowercase()
        return when {
            "password" in text || "passwd" in text -> "password"
            "otp" in text || "one-time" in text || "2fa" in text || "verification" in text -> "otp"
            "card" in text || "credit" in text || "cvv" in text || "payment" in text -> "payment"
            "email" in text -> "email_login"
            "username" in text || "login" in text -> "username"
            "address" in text || "phone" in text || "passport" in text || "ktp" in text || "nik" in text -> "personal_data"
            else -> "unknown_sensitive"
        }
    }

    private fun suggestedAction(code: String): String = when (code) {
        "MISSING_QUERY" -> "use_existing_agent_interactive_elements_or_call_get_dom"
        "MISSING_TARGET" -> "use_element_id_from_agent_interactive_elements"
        "ELEMENT_NOT_FOUND" -> "choose_candidate_from_agent_interactive_elements_or_ask_user"
        "TIMEOUT" -> "retry_with_longer_timeout_or_reload"
        "NETWORK_ERROR" -> "check_connection_or_reload"
        "ANDROID_PERMISSION_PROMPT" -> "ask_user_to_grant_permission_manually"
        "CANCELLED" -> "resume_session_or_start_new_task"
        else -> "inspect_page_and_retry"
    }
}
