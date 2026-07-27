package com.amaya.intelligence.impl.common.conversation

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.impl.local.tools.LocalToolMapper
import com.amaya.intelligence.impl.common.mappers.ToolUiMapper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ConversationJsonCodec {
    fun parseLocal(json: String): Result<List<UiMessage>> {
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

    fun serializeLocal(messages: List<UiMessage>): String {
        val jsonArray = JSONArray()
        messages.forEach { msg ->
            val obj = JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role.name)
                put("content", msg.content)
                msg.formattedContent?.let { put("formattedContent", it) }
                put("timestamp", msg.timestamp)
                if (!msg.thinking.isNullOrBlank()) put("thinking", msg.thinking)
                msg.thinkingStartedAt?.let { put("thinkingStartedAt", it) }
                msg.thinkingDurationMs?.let { put("thinkingDurationMs", it) }
                if (msg.responseItems.isNotEmpty()) put("responseItems", JSONArray(msg.responseItems))
                if (msg.canonicalHistory.isNotEmpty()) put("canonicalHistory", JSONArray(msg.canonicalHistory))
                if (msg.attachments.isNotEmpty()) {
                    put("attachments", JSONArray().apply {
                        msg.attachments.forEach { attachment ->
                            put(JSONObject()
                                .put("mimeType", attachment.mimeType)
                                .put("dataBase64", attachment.dataBase64)
                                .put("fileName", attachment.fileName))
                        }
                    })
                }
            }

            if (msg.toolExecutions.isNotEmpty()) {
                val execArr = JSONArray()
                msg.toolExecutions.forEach { exec ->
                    execArr.put(serializeToolExecutionToJson(exec))
                }
                obj.put("toolExecutions", execArr)
            }

            if (msg.steps.isNotEmpty()) {
                val stepsArr = JSONArray()
                msg.steps.forEach { step ->
                    val stepObj = JSONObject().apply {
                        put("id", step.id)
                        when (step) {
                            is MessageStep.Thinking -> {
                                put("type", "thinking")
                                put("text", step.text)
                                put("isStreaming", step.isStreaming)
                                step.startedAt?.let { put("startedAt", it) }
                                step.durationMs?.let { put("durationMs", it) }
                            }
                            is MessageStep.Text -> {
                                put("type", "text")
                                put("content", step.content)
                                step.formattedContent?.let { put("formattedContent", it) }
                            }
                            is MessageStep.ToolCall -> {
                                put("type", "toolCall")
                                put("execution", serializeToolExecutionToJson(step.execution))
                            }
                        }
                    }
                    stepsArr.put(stepObj)
                }
                obj.put("steps", stepsArr)
            }

            if (msg.metadata.isNotEmpty()) {
                val metaObj = JSONObject()
                msg.metadata.forEach { (key, value) -> metaObj.put(key, value) }
                obj.put("metadata", metaObj)
            }

            if (msg.todoItems.isNotEmpty()) {
                val todoArr = JSONArray()
                msg.todoItems.forEach { todo ->
                    todoArr.put(
                        JSONObject().apply {
                            put("id", todo.id)
                            todo.content?.let { put("content", it) }
                            todo.activeForm?.let { put("activeForm", it) }
                            put("status", todo.status.name)
                        }
                    )
                }
                obj.put("todoItems", todoArr)
            }

            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }
    private fun parseToolExecutionFromJson(e: JSONObject): ToolExecution {
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

    private fun serializeToolExecutionToJson(exec: ToolExecution): JSONObject {
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

    fun serializeWindowsBridge(messages: List<UiMessage>): String = JSONArray().apply {
        messages.forEach { msg ->
            put(JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role.name)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
                put("metadata", JSONObject(msg.metadata))
                put("toolExecutions", JSONArray().apply {
                    msg.toolExecutions.forEach { put(serializeToolExecution(it)) }
                })
                put("steps", JSONArray().apply {
                    msg.steps.forEach { step ->
                        when (step) {
                            is MessageStep.Thinking -> put(JSONObject().apply {
                                put("id", step.id)
                                put("type", "thinking")
                                put("text", step.text)
                                put("isStreaming", step.isStreaming)
                                step.startedAt?.let { put("startedAt", it) }
                                step.durationMs?.let { put("durationMs", it) }
                            })
                            is MessageStep.Text -> put(JSONObject().apply {
                                put("id", step.id)
                                put("type", "text")
                                put("content", step.content)
                            })
                            is MessageStep.ToolCall -> put(JSONObject().apply {
                                put("id", step.id)
                                put("type", "toolCall")
                                put("execution", serializeToolExecution(step.execution))
                            })
                        }
                    }
                })
            })
        }
    }.toString()

    private fun serializeToolExecution(exec: ToolExecution): JSONObject = JSONObject().apply {
        put("toolCallId", exec.toolCallId)
        put("name", exec.name)
        put("arguments", JSONObject(exec.arguments))
        put("result", exec.result ?: JSONObject.NULL)
        put("status", exec.status.name)
        put("metadata", JSONObject(exec.metadata))
    }

    fun parseWindowsBridge(json: String): List<UiMessage> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val metadata = obj.optJSONObject("metadata")?.let(::toStringMap) ?: emptyMap()
                val tools = obj.optJSONArray("toolExecutions")?.let { execArr ->
                    (0 until execArr.length()).map { parseToolExecution(execArr.getJSONObject(it)) }
                } ?: emptyList()
                val steps = obj.optJSONArray("steps")?.let { stepsArr ->
                    (0 until stepsArr.length()).mapNotNull { idx ->
                        val step = stepsArr.getJSONObject(idx)
                        when (step.optString("type")) {
                            "thinking" -> MessageStep.Thinking(
                                id = step.optString("id", UUID.randomUUID().toString()),
                                text = step.optString("text"),
                                isStreaming = step.optBoolean("isStreaming"),
                                startedAt = step.optLong("startedAt", 0L).takeIf { it > 0 },
                                durationMs = step.optLong("durationMs", 0L).takeIf { it > 0 }
                            )
                            "text" -> MessageStep.Text(
                                id = step.optString("id", UUID.randomUUID().toString()),
                                content = step.optString("content")
                            )
                            "toolCall" -> MessageStep.ToolCall(
                                id = step.optString("id", UUID.randomUUID().toString()),
                                execution = parseToolExecution(step.getJSONObject("execution"))
                            )
                            else -> null
                        }
                    }
                } ?: emptyList()
                UiMessage(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    role = runCatching { MessageRole.valueOf(obj.optString("role")) }.getOrDefault(MessageRole.USER),
                    content = obj.optString("content"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    metadata = metadata,
                    toolExecutions = tools,
                    steps = steps
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseToolExecution(obj: JSONObject): ToolExecution {
        val args = obj.optJSONObject("arguments")?.let(::toAnyMap) ?: emptyMap()
        val metadata = obj.optJSONObject("metadata")?.let(::toStringMap) ?: emptyMap()
        val name = obj.optString("name")
        return ToolExecution(
            toolCallId = obj.optString("toolCallId", UUID.randomUUID().toString()),
            name = name,
            arguments = args,
            result = obj.optString("result").takeIf { it.isNotBlank() && it != "null" },
            status = runCatching { ToolStatus.valueOf(obj.optString("status")) }.getOrDefault(ToolStatus.PENDING),
            metadata = metadata,
            uiMetadata = ToolUiMapper.getToolUiMetadata(name, args, metadata)
        )
    }

    fun toAnyMap(json: JSONObject): Map<String, Any?> = buildMap {
        json.keys().forEach { key -> put(key, json.opt(key).takeUnless { it == JSONObject.NULL }) }
    }

    fun toStringMap(json: JSONObject): Map<String, String> = buildMap {
        json.keys().forEach { key -> put(key, json.optString(key, "")) }
    }
    fun serializeOpencode(
        messages: List<UiMessage>,
        opencodeSessionId: String?
    ): String {
        val root = JSONObject().apply {
            put("opencodeSessionId", opencodeSessionId ?: JSONObject.NULL)
            put("messages", JSONArray().apply {
                messages.forEach { msg -> put(serializeMessage(msg)) }
            })
        }
        return root.toString()
    }

    private fun serializeMessage(msg: UiMessage): JSONObject = JSONObject().apply {
        put("id", msg.id)
        put("role", msg.role.name)
        put("content", msg.content)
        put("timestamp", msg.timestamp)
        msg.thinking?.let { put("thinking", it) }
        msg.thinkingDurationMs?.let { put("thinkingDurationMs", it) }
        put("steps", JSONArray().apply {
            msg.steps.forEach { step ->
                when (step) {
                    is MessageStep.Thinking -> put(JSONObject().apply {
                        put("id", step.id)
                        put("type", "thinking")
                        put("text", step.text)
                        put("isStreaming", step.isStreaming)
                        step.startedAt?.let { put("startedAt", it) }
                        step.durationMs?.let { put("durationMs", it) }
                    })
                    is MessageStep.Text -> put(JSONObject().apply {
                        put("id", step.id)
                        put("type", "text")
                        put("content", step.content)
                    })
                    is MessageStep.ToolCall -> put(JSONObject().apply {
                        put("id", step.id)
                        put("type", "toolCall")
                        put("execution", JSONObject().apply {
                            put("toolCallId", step.execution.toolCallId)
                            put("name", step.execution.name)
                            put("status", step.execution.status.name)
                            put("result", step.execution.result ?: JSONObject.NULL)
                        })
                    })
                }
            }
        })
    }

    fun parseOpencode(json: String): List<UiMessage> {
        return runCatching {
            val root = JSONObject(json)
            val arr = root.optJSONArray("messages") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val steps = mutableListOf<MessageStep>()
                val tools = mutableListOf<ToolExecution>()
                val stepsArr = obj.optJSONArray("steps")
                if (stepsArr != null) {
                    for (j in 0 until stepsArr.length()) {
                        val step = stepsArr.optJSONObject(j) ?: continue
                        when (step.optString("type")) {
                            "thinking" -> steps.add(
                                MessageStep.Thinking(
                                    id = step.optString("id", UUID.randomUUID().toString()),
                                    text = step.optString("text"),
                                    isStreaming = step.optBoolean("isStreaming"),
                                    startedAt = step.optLong("startedAt", 0L).takeIf { it > 0 },
                                    durationMs = step.optLong("durationMs", 0L).takeIf { it > 0 }
                                )
                            )
                            "text" -> steps.add(
                                MessageStep.Text(
                                    id = step.optString("id", UUID.randomUUID().toString()),
                                    content = step.optString("content")
                                )
                            )
                            "toolCall" -> {
                                val exec = step.optJSONObject("execution") ?: continue
                                val execution = ToolExecution(
                                    toolCallId = exec.optString("toolCallId", UUID.randomUUID().toString()),
                                    name = exec.optString("name"),
                                    arguments = emptyMap(),
                                    status = runCatching {
                                        ToolStatus.valueOf(exec.optString("status"))
                                    }.getOrDefault(ToolStatus.SUCCESS),
                                    result = exec.optString("result").takeIf { it.isNotBlank() && it != "null" }
                                )
                                tools.add(execution)
                                steps.add(MessageStep.ToolCall(id = step.optString("id", UUID.randomUUID().toString()), execution = execution))
                            }
                        }
                    }
                }
                UiMessage(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    role = runCatching { MessageRole.valueOf(obj.optString("role")) }
                        .getOrDefault(MessageRole.USER),
                    content = obj.optString("content"),
                    thinking = obj.optString("thinking").takeIf { it.isNotBlank() },
                    thinkingDurationMs = obj.optLong("thinkingDurationMs", 0L).takeIf { it > 0 },
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    toolExecutions = tools,
                    steps = steps
                )
            }
        }.getOrDefault(emptyList())
    }

    fun extractOpencodeSessionId(json: String): String? = runCatching {
        val root = JSONObject(json)
        root.optString("opencodeSessionId").takeIf { it.isNotBlank() && it != "null" }
    }.getOrNull()


}
