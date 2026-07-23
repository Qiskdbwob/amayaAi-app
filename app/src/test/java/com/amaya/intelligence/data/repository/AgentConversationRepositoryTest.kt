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
    }

    @Test
    fun `corrupt target history does not drop delegation`() {
        val result = appendDelegationMessage("not-json", MessageRole.USER, "Incoming", mapOf("delegation" to "incoming"), 1)

        assertEquals("Incoming", delegationHistoryFromJson(result).single().content)
        assertTrue(result.contains("incoming"))
    }
}
