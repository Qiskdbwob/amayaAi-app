package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.impl.local.tools.LocalToolMapper
import com.amaya.intelligence.data.remote.api.MessageRole
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun parseMessagesFromJson(json: String): Result<List<UiMessage>> {
        if (json.isBlank()) return Result.success(emptyList())
        return try {
            val messages = mutableListOf<UiMessage>()
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val role = when (obj.optString("role")) {
                    "USER" -> MessageRole.USER
                    "ASSISTANT" -> MessageRole.ASSISTANT
                    "SYSTEM" -> MessageRole.SYSTEM
                    else -> MessageRole.USER
                }

                val toolExecutions = mutableListOf<ToolExecution>()
                if (obj.has("toolExecutions")) {
                    val execArr = obj.getJSONArray("toolExecutions")
                    for (j in 0 until execArr.length()) {
                        val e = execArr.getJSONObject(j)
                        toolExecutions.add(parseToolExecutionFromJson(e))
                    }
                }

                val steps = mutableListOf<MessageStep>()
                if (obj.has("steps")) {
                    val stepsArr = obj.getJSONArray("steps")
                    for (j in 0 until stepsArr.length()) {
                        val s = stepsArr.getJSONObject(j)
                        val stepId = s.optString("id", UUID.randomUUID().toString())
                        when (s.optString("type")) {
                            "thinking" -> {
                                steps.add(MessageStep.Thinking(
                                    id = stepId,
                                    text = s.optString("text"),
                                    isStreaming = s.optBoolean("isStreaming"),
                                    startedAt = s.optLong("startedAt", 0L).takeIf { it > 0 },
                                    durationMs = s.optLong("durationMs", 0L).takeIf { it > 0 }
                                ))
                            }
                            "text" -> {
                                steps.add(MessageStep.Text(
                                    id = stepId,
                                    content = s.getString("content"),
                                    formattedContent = s.optString("formattedContent").takeIf { it.isNotBlank() }
                                ))
                            }
                            "toolCall" -> {
                                val eObj = s.getJSONObject("execution")
                                steps.add(MessageStep.ToolCall(
                                    id = stepId,
                                    execution = parseToolExecutionFromJson(eObj)
                                ))
                            }
                        }
                    }
                }

                val todoItems = mutableListOf<com.amaya.intelligence.tools.TodoItem>()
                if (obj.has("todoItems")) {
                    val todoArr = obj.getJSONArray("todoItems")
                    for (j in 0 until todoArr.length()) {
                        val t = todoArr.getJSONObject(j)
                        todoItems.add(
                            com.amaya.intelligence.tools.TodoItem(
                                id = t.getInt("id"),
                                content = t.optString("content").takeIf { it.isNotBlank() },
                                activeForm = t.optString("activeForm").takeIf { it.isNotBlank() },
                                status = runCatching {
                                    com.amaya.intelligence.tools.TodoStatus.valueOf(t.getString("status"))
                                }.getOrDefault(com.amaya.intelligence.tools.TodoStatus.PENDING)
                            )
                        )
                    }
                }

                val metadata = mutableMapOf<String, String>()
                if (obj.has("metadata")) {
                    val metaObj = obj.getJSONObject("metadata")
                    metaObj.keys().forEach { key ->
                        metadata[key] = metaObj.optString(key, "")
                    }
                }

                val responseItems = obj.optJSONArray("responseItems")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
                }.orEmpty()
                val canonicalHistory = obj.optJSONArray("canonicalHistory")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
                }.orEmpty()
                val attachments = obj.optJSONArray("attachments")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        val attachment = array.optJSONObject(index) ?: return@mapNotNull null
                        val mime = attachment.optString("mimeType")
                        val data = attachment.optString("dataBase64")
                        if (mime.isBlank() || data.isBlank()) null else MessageAttachment(
                            mimeType = mime,
                            dataBase64 = data,
                            fileName = attachment.optString("fileName")
                        )
                    }
                }.orEmpty()
                messages.add(
                    UiMessage(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        role = role,
                        content = obj.optString("content"),
                        formattedContent = obj.optString("formattedContent").takeIf { it.isNotBlank() },
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        thinking = obj.optString("thinking").takeIf { it.isNotBlank() },
                        thinkingStartedAt = obj.optLong("thinkingStartedAt", 0L).takeIf { it > 0 },
                        thinkingDurationMs = obj.optLong("thinkingDurationMs", 0L).takeIf { it > 0 },
                        metadata = metadata,
                        toolExecutions = toolExecutions,
                        steps = steps,
                        todoItems = todoItems,
                        attachments = attachments,
                        responseItems = responseItems,
                        canonicalHistory = canonicalHistory
                    )
                )
            }
            Result.success(messages)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
internal fun parseToolExecutionFromJson(e: JSONObject): ToolExecution {
        val argsMap = mutableMapOf<String, Any?>()
        if (e.has("arguments")) {
            val argsObj = e.getJSONObject("arguments")
            argsObj.keys().forEach { key -> argsMap[key] = argsObj.get(key) }
        }
        val children = mutableListOf<SubagentExecution>()
        if (e.has("children")) {
            val childArr = e.getJSONArray("children")
            for (k in 0 until childArr.length()) {
                val c = childArr.getJSONObject(k)
                children.add(
                    SubagentExecution(
                        index = c.getInt("index"),
                        taskName = c.getString("taskName"),
                        prompt = c.getString("prompt"),
                        result = c.optString("result").takeIf { it.isNotBlank() },
                        status = runCatching { ToolStatus.valueOf(c.getString("status")) }
                            .getOrDefault(ToolStatus.SUCCESS)
                            .let { status -> if (status == ToolStatus.PENDING || status == ToolStatus.RUNNING) ToolStatus.ERROR else status }
                    )
                )
            }
        }
        val metaMap = mutableMapOf<String, String>()
        if (e.has("metadata")) {
            val mObj = e.getJSONObject("metadata")
            mObj.keys().forEach { key -> metaMap[key] = mObj.getString(key) }
        } else {
            metaMap["source"] = "local"
        }
        val persistedStatus = runCatching { ToolStatus.valueOf(e.getString("status")) }
            .getOrDefault(ToolStatus.SUCCESS)
        val interrupted = persistedStatus == ToolStatus.PENDING || persistedStatus == ToolStatus.RUNNING
        return ToolExecution(
            toolCallId = e.getString("toolCallId"),
            name = e.getString("name"),
            arguments = argsMap,
            result = e.optString("result").takeIf { it.isNotBlank() }
                ?: "Stopped before completion".takeIf { interrupted },
            status = if (interrupted) ToolStatus.ERROR else persistedStatus,
            children = children,
            metadata = if (metaMap["approvalState"] == "pending") metaMap + mapOf(
                "approvalRequired" to "false",
                "approvalState" to "cancelled"
            ) else metaMap,
            uiMetadata = LocalToolMapper.getUiMetadata(
                toolName = e.getString("name"),
                args = argsMap
            )
        )
    }

internal fun serializeToolExecutionToJson(exec: ToolExecution): JSONObject {
        return JSONObject().apply {
            put("toolCallId", exec.toolCallId)
            put("name", exec.name)
            put("status", exec.status.name)
            exec.result?.let { put("result", it) }
            put("arguments", JSONObject().apply {
                exec.arguments.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
            })
            if (exec.children.isNotEmpty()) {
                val childArr = JSONArray()
                exec.children.forEach { child ->
                    childArr.put(
                        JSONObject().apply {
                            put("index", child.index)
                            put("taskName", child.taskName)
                            put("prompt", child.prompt)
                            child.result?.let { put("result", it) }
                            put("status", child.status.name)
                        }
                    )
                }
                put("children", childArr)
            }
            if (exec.metadata.isNotEmpty()) {
                val mObj = JSONObject()
                exec.metadata.forEach { (k, v) -> mObj.put(k, v) }
                put("metadata", mObj)
            }
        }
    }

