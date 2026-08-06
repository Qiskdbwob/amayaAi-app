package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.remote.api.ToolCallMessage
import com.amaya.intelligence.data.remote.api.ToolResultMessage
import com.amaya.intelligence.data.local.entity.AgentEntity
import com.amaya.intelligence.tools.SubagentResult
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRepositoryTest {
    @Test
    fun `delegation append preserves existing target conversation`() {
        val existing = appendDelegationMessage("", MessageRole.USER, "Existing CEO context", emptyMap(), 1)
        val withRequest = appendDelegationMessage(existing, MessageRole.USER, "Delegation from @CMO", emptyMap(), 2)
        val withResponse = appendDelegationMessage(withRequest, MessageRole.ASSISTANT, "CEO answer", emptyMap(), 3)

        assertEquals(
            listOf("Existing CEO context", "Delegation from @CMO", "CEO answer"),
            delegationHistoryFromJson(withResponse).map { it.content }
        )
        assertEquals(3, JSONArray(withResponse).length())
    }

    @Test
    fun `delegation sender is model context only`() {
        val source = AgentEntity(groupId = 1, localId = 7, name = "CEO")

        assertEquals(
            "[HOST-AUTHORITATIVE DELEGATION from CEO (agent_id=7)]\nReview the release plan",
            delegationPrompt(source, "Review the release plan")
        )
    }

    @Test
    fun `delegated turn persists tool iteration and final response`() {
        val result = SubagentResult(
            taskName = "CEO",
            summary = "Final answer",
            turnMessages = listOf(
                ChatMessage(MessageRole.ASSISTANT, toolCalls = listOf(ToolCallMessage("call-1", "read_file", mapOf("path" to "plan.md")))),
                ChatMessage(MessageRole.TOOL, toolResult = ToolResultMessage("call-1", "file body")),
                ChatMessage(MessageRole.ASSISTANT, content = "Final answer")
            ),
            startedAt = 10,
            completedAt = 20
        )

        val json = appendDelegationTurn("[]", result, mapOf("delegation" to "response"))
        val message = JSONArray(json).getJSONObject(0)

        assertEquals("Final answer", message.getString("content"))
        assertEquals(2, message.getJSONArray("steps").length())
        assertEquals("file body", message.getJSONArray("toolExecutions").getJSONObject(0).getString("result"))
        assertEquals("file body", message.getJSONArray("steps").getJSONObject(0).getJSONObject("execution").getString("result"))
        assertEquals("20", message.getJSONObject("metadata").getString("completedAt"))
        assertEquals("completed", message.getJSONObject("metadata").getString("turnStatus"))
    }

    @Test
    fun `delegation completion finishes durable tool and timeline step`() {
        val json = JSONArray().put(org.json.JSONObject().apply {
            put("role", "ASSISTANT")
            put("content", "Waiting")
            put("toolExecutions", JSONArray().put(deferredDelegationExecution(42)))
            put("steps", JSONArray().put(org.json.JSONObject()
                .put("type", "toolCall")
                .put("execution", deferredDelegationExecution(42))))
            put("canonicalHistory", JSONArray().put(org.json.JSONObject()
                .put("kind", "tool_result")
                .put("deferredTaskId", 42)
                .put("result", "Delegation started")
                .toString()))
        }).toString()

        val completed = JSONArray(completeDelegationTools(json, 42, "Agent answer", failed = false))
            .getJSONObject(0)
        val executions = listOf(
            completed.getJSONArray("toolExecutions").getJSONObject(0),
            completed.getJSONArray("steps").getJSONObject(0).getJSONObject("execution")
        )

        executions.forEach { execution ->
            assertEquals("SUCCESS", execution.getString("status"))
            assertEquals("Agent answer", execution.getString("result"))
            assertEquals("done", execution.getJSONObject("metadata").getString("delegationState"))
        }
        val canonical = org.json.JSONObject(completed.getJSONArray("canonicalHistory").getString(0))
        assertEquals("Agent answer", canonical.getString("result"))
        assertEquals(false, canonical.getBoolean("isError"))
    }

    @Test
    fun `timeline-only delegation completion is not skipped`() {
        val json = JSONArray().put(org.json.JSONObject().apply {
            put("role", "ASSISTANT")
            put("steps", JSONArray().put(org.json.JSONObject()
                .put("type", "toolCall")
                .put("execution", deferredDelegationExecution(7))))
        }).toString()

        val execution = JSONArray(completeDelegationTools(json, 7, "Done", failed = false))
            .getJSONObject(0).getJSONArray("steps").getJSONObject(0).getJSONObject("execution")

        assertEquals("SUCCESS", execution.getString("status"))
        assertEquals("done", execution.getJSONObject("metadata").getString("delegationState"))
    }

    @Test
    fun `completion events retain assigned delivery order`() {
        val later = delegationCompletionMessage(
            "Later", "second", delegationCompletionMetadata("A", "C", 10, 2, 200), 10
        )
        val earlier = delegationCompletionMessage(
            "Earlier", "first", delegationCompletionMetadata("A", "B", 20, 1, 100), 20
        )

        val json = appendDelegationMessageJson(appendDelegationMessageJson("[]", later), earlier)
        val messages = JSONArray(json)

        assertEquals("1", messages.getJSONObject(0).getJSONObject("metadata").getString("delegationTaskId"))
        assertEquals("2", messages.getJSONObject(1).getJSONObject("metadata").getString("delegationTaskId"))
    }

    @Test
    fun `durable completion lookup is task scoped`() {
        val event = delegationCompletionMessage(
            "Done", "result", delegationCompletionMetadata("A", "B", 10, 42, 100), 10
        )
        val json = appendDelegationMessageJson("[]", event)

        assertTrue(hasDelegationCompletion(json, 42))
        assertEquals(false, hasDelegationCompletion(json, 43))
    }

    @Test
    fun `corrupt target history does not drop delegation`() {
        val result = appendDelegationMessage("not-json", MessageRole.USER, "Incoming", mapOf("delegation" to "incoming"), 1)

        assertEquals("Incoming", delegationHistoryFromJson(result).single().content)
        assertTrue(result.contains("incoming"))
    }

    private fun deferredDelegationExecution(taskId: Long) = org.json.JSONObject()
        .put("toolCallId", "call-$taskId")
        .put("name", "delegate_agent")
        .put("status", "RUNNING")
        .put("arguments", org.json.JSONObject())
        .put("metadata", org.json.JSONObject()
            .put("delegationTaskId", taskId.toString())
            .put("delegationState", "running"))
}
