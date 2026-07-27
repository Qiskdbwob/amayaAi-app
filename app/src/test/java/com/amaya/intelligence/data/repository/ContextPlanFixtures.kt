package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.remote.api.ToolCallMessage
import com.amaya.intelligence.data.remote.api.ToolResultMessage

internal fun contextUser(text: String) = ChatMessage(MessageRole.USER, text)
internal fun contextAssistant(text: String) = ChatMessage(MessageRole.ASSISTANT, text)
internal fun contextToolCall(id: String, name: String = "read_file") =
    ChatMessage(MessageRole.ASSISTANT, toolCalls = listOf(ToolCallMessage(id, name, mapOf("path" to "a.kt"))))
internal fun contextToolResult(id: String, content: String) =
    ChatMessage(MessageRole.TOOL, toolResult = ToolResultMessage(id, content))
