package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.data.remote.api.AiSettingsManager

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.ProjectDao
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.repository.AiRepository
import com.amaya.intelligence.data.repository.AgentEvent
import com.amaya.intelligence.data.repository.SessionMemoryRepository

import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.impl.common.mappers.ModelUiMapper
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.impl.local.tools.LocalToolMapper
import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.util.LocalStreamPerfLog
import com.amaya.intelligence.tools.ConfirmationRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.amaya.intelligence.tools.SubagentResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context


internal fun LocalIntelligenceService.serializeMessagesToJson(messages: List<UiMessage>): String {
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
