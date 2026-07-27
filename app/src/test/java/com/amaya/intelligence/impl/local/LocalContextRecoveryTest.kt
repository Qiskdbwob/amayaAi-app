package com.amaya.intelligence.impl.local

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalContextRecoveryTest {

    private fun runningAssistant() = UiMessage(
        role = MessageRole.ASSISTANT,
        content = "working on it",
        toolExecutions = listOf(
            ToolExecution(toolCallId = "c1", name = "read_file", arguments = mapOf("path" to "a.kt"), status = ToolStatus.RUNNING)
        )
    )

    @Test
    fun `interrupted turn closes running tools and tags the message`() {
        val messages = listOf(UiMessage(role = MessageRole.USER, content = "go"), runningAssistant())

        val recovered = markInterruptedTurn(messages)!!

        assertEquals(2, recovered.size)
        assertEquals(ToolStatus.ERROR, recovered[1].toolExecutions.single().status)
        assertEquals("interrupted", recovered[1].metadata["turnStatus"])
        assertTrue(recovered[1].toolExecutions.single().result!!.contains("Interrupted"))
    }

    @Test
    fun `nothing to recover is reported as null so the stored column is left alone`() {
        val settled = listOf(
            UiMessage(role = MessageRole.USER, content = "go"),
            UiMessage(role = MessageRole.ASSISTANT, content = "done", metadata = mapOf("turnStatus" to "completed"))
        )

        assertNull(markInterruptedTurn(settled))
    }

    /**
     * Recovery used to rebuild `contextMessagesJson` from the visible transcript, which silently
     * discarded a compaction summary and undid a cleared history. The two columns must recover
     * independently.
     */
    @Test
    fun `recovering each column independently preserves compaction state in the model context`() {
        val visible = listOf(UiMessage(role = MessageRole.USER, content = "go"), runningAssistant())
        val context = compressedSessionContext("GOAL: ship the settings screen") + visible

        val recoveredVisible = markInterruptedTurn(visible)!!
        val recoveredContext = markInterruptedTurn(context)!!

        assertTrue(recoveredContext.first().content.contains("GOAL: ship the settings screen"))
        assertEquals("true", recoveredContext.first().metadata["compressed"])
        assertEquals(ToolStatus.ERROR, recoveredContext.last().toolExecutions.single().status)
        // The visible transcript never gains the compaction record.
        assertTrue(recoveredVisible.none { it.metadata["compressed"] == "true" })
    }

    @Test
    fun `a committed run of assistant text becomes one canonical entry`() {
        val history = emptyList<String>().appendCanonicalText("Hello world")

        assertEquals(1, history.size)
        assertEquals("Hello world", canonicalHistoryToChatMessages(history).single().content)
    }

    @Test
    fun `empty or absent text does not create canonical entries`() {
        assertTrue(emptyList<String>().appendCanonicalText("").isEmpty())
        assertTrue(emptyList<String>().appendCanonicalText(null).isEmpty())
    }

    /**
     * The compaction record is tagged by source so an automatic ledger replaces only the previous
     * automatic ledger — a summary the user asked for with /compact must survive.
     */
    @Test
    fun `an automatic ledger replaces only the previous automatic record`() {
        val manual = compressedSessionContext("user-curated: keep the repro steps", auto = false)
        val firstAuto = compressedSessionContext("GOAL: ship settings", auto = true)
        val context = firstAuto + manual + listOf(UiMessage(role = MessageRole.USER, content = "go"))

        val retained = context.filterNot { it.metadata["compactionSource"] == "auto" }
        val updated = compressedSessionContext("GOAL: ship settings (updated)", auto = true) + retained

        assertEquals(1, updated.count { it.metadata["compactionSource"] == "auto" })
        assertEquals(1, updated.count { it.metadata["compactionSource"] == "manual" })
        assertTrue(updated.any { it.content.contains("user-curated: keep the repro steps") })
        assertTrue(updated.any { it.content.contains("GOAL: ship settings (updated)") })
        assertTrue(updated.none { it.content.contains("GOAL: ship settings\n") })
    }

    /**
     * A turn that died between ToolCallStart and ToolCallResult leaves the execution pending.
     * Replaying that as a bare tool_call orphans the id, and every provider rejects the next
     * request in that conversation.
     */
    @Test
    fun `a tool call with no result still replays with a matching tool result`() {
        val assistant = UiMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolExecutions = listOf(
                ToolExecution(toolCallId = "c1", name = "screen.capture", arguments = emptyMap(), status = ToolStatus.RUNNING)
            )
        )

        val replayed = assistant.toChatMessages()

        val callIds = replayed.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
        val resultIds = replayed.mapNotNull { it.toolResult }.map { it.toolCallId }.toSet()
        assertEquals(callIds, resultIds)
        val result = replayed.mapNotNull { it.toolResult }.single()
        assertEquals(UNFINISHED_TOOL_RESULT, result.content)
        assertTrue(result.isError)
    }

    /**
     * An empty string becomes an empty text content block, which Anthropic rejects outright. An
     * assistant turn that was pure tool calls legitimately has no text.
     */
    @Test
    fun `a blank assistant message replays with null content rather than an empty block`() {
        val toolOnly = UiMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolExecutions = listOf(
                ToolExecution(toolCallId = "c1", name = "mouse.click", arguments = emptyMap(), result = "ok", status = ToolStatus.SUCCESS)
            )
        )

        val replayed = toolOnly.toChatMessages()

        assertNull(replayed.first().content)
        // With nothing at all to say the message is not replayed; with tool calls it is, minus text.
        assertTrue(UiMessage(role = MessageRole.ASSISTANT, content = "   ").toChatMessages().isEmpty())
        assertEquals("real text", UiMessage(role = MessageRole.ASSISTANT, content = "real text").toChatMessages().single().content)
    }

    /**
     * A provider error between the call and its result persists a tool_call with nothing answering
     * it. Repairing at the replay boundary also heals conversations already stored in that state.
     */
    @Test
    fun `canonical replay never emits a tool call without a matching result`() {
        val history = listOf(
            org.json.JSONObject()
                .put("kind", "assistant_tool_call")
                .put("id", "c1")
                .put("name", "browser")
                .put("arguments", org.json.JSONObject())
                .put("metadata", org.json.JSONObject())
                .toString()
        )

        val replayed = canonicalHistoryToChatMessages(history)

        val callIds = replayed.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
        val resultIds = replayed.mapNotNull { it.toolResult }.map { it.toolCallId }.toSet()
        assertEquals(callIds, resultIds)
        assertEquals(UNFINISHED_TOOL_RESULT, replayed.last().toolResult!!.content)
    }

    @Test
    fun `an already-answered tool call is not given a second result`() {
        val messages = listOf(
            UiMessage(
                role = MessageRole.ASSISTANT,
                content = "done",
                toolExecutions = listOf(
                    ToolExecution(toolCallId = "c1", name = "read_file", arguments = emptyMap(), result = "ok", status = ToolStatus.SUCCESS)
                )
            )
        ).flatMap { it.toChatMessages() }

        val repaired = messages.withSyntheticToolResults()

        assertEquals(messages, repaired)
        assertEquals(1, repaired.count { it.toolResult != null })
    }

    private fun toolTurn(id: String, payload: String) = UiMessage(
        role = MessageRole.ASSISTANT,
        content = "looking",
        toolExecutions = listOf(
            ToolExecution(toolCallId = id, name = "browser", arguments = emptyMap(), result = payload, status = ToolStatus.SUCCESS)
        ),
        canonicalHistory = listOf(
            org.json.JSONObject().put("kind", "assistant_tool_call").put("id", id).put("name", "browser")
                .put("arguments", org.json.JSONObject()).put("metadata", org.json.JSONObject()).toString(),
            org.json.JSONObject().put("kind", "tool_result").put("id", id).put("name", "browser")
                .put("result", payload).put("isError", false).toString()
        )
    )

    /**
     * Tool-result bytes are the term that grows without limit. They are shrunk in place rather than
     * by removing messages, because ledgerCoverage is an index into this list.
     */
    @Test
    fun `old tool payloads are trimmed in stored context while message count is preserved`() {
        val context = (1..40).map { toolTurn("c-$it", "dom-$it ".repeat(20_000)) }

        val stored = digestOldToolPayloads(context)

        assertEquals(context.size, stored.size, "message count must not change")
        val before = context.sumOf { m -> m.toolExecutions.sumOf { it.result?.length ?: 0 } }
        val after = stored.sumOf { m -> m.toolExecutions.sumOf { it.result?.length ?: 0 } }
        assertTrue(after < before / 2, "stored payload must shrink substantially")
        assertTrue(stored.first().toolExecutions.single().result!!.contains("trimmed in stored context"))
        // The newest turns stay verbatim so the model still sees recent work in full.
        assertEquals(context.last().toolExecutions.single().result, stored.last().toolExecutions.single().result)
    }

    /** Trimming must not orphan a tool call or change how many provider messages replay. */
    @Test
    fun `trimming stored payloads preserves the replayed message shape`() {
        val context = (1..40).map { toolTurn("c-$it", "dom-$it ".repeat(20_000)) }

        val stored = digestOldToolPayloads(context)

        val before = context.flatMap { it.toChatMessages() }
        val after = stored.flatMap { it.toChatMessages() }
        assertEquals(before.size, after.size, "replayed ChatMessage count must be stable")
        assertEquals(
            before.map { it.role },
            after.map { it.role },
            "roles must line up so ledger indices stay valid"
        )
        val callIds = after.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
        val resultIds = after.mapNotNull { it.toolResult }.map { it.toolCallId }.toSet()
        assertEquals(callIds, resultIds)
        assertTrue(after.mapNotNull { it.toolResult }.first().content.contains("trimmed in stored context"))
    }

    @Test
    fun `a context under the payload ceiling is returned untouched`() {
        val context = (1..5).map { toolTurn("c-$it", "small result $it") }

        assertEquals(context, digestOldToolPayloads(context))
    }

    /** All three stored copies of a tool result must shrink, or the ceiling is not enforced. */
    @Test
    fun `every stored copy of a tool result is trimmed`() {
        val payload = "dom ".repeat(20_000)
        val context = (1..40).map { index ->
            toolTurn("c-$index", payload).let { message ->
                message.copy(
                    steps = listOf(MessageStep.ToolCall(execution = message.toolExecutions.single()))
                )
            }
        }

        val stored = digestOldToolPayloads(context)
        val oldest = stored.first()

        assertTrue(oldest.toolExecutions.single().result!!.length < payload.length)
        val step = oldest.steps.filterIsInstance<MessageStep.ToolCall>().single()
        assertTrue(step.execution.result!!.length < payload.length, "steps copy must be trimmed too")
        assertTrue(oldest.canonicalHistory.none { it.length > payload.length / 2 })
    }

    /**
     * A tool result that quotes the trim marker must still be subject to the ceiling — the
     * idempotence check is anchored to the end of the body, not searched for inside it.
     */
    @Test
    fun `a payload that quotes the trim marker is still trimmed`() {
        val quoting = ("result trimmed in stored context after 10 chars; full text remains in the transcript] " + "x".repeat(500))
            .repeat(200)
        val context = (1..40).map { toolTurn("c-$it", quoting) }

        val stored = digestOldToolPayloads(context)

        assertTrue(stored.first().toolExecutions.single().result!!.length < quoting.length)
    }

    /** A turn that failed before the first delta must not replay as an empty content block. */
    @Test
    fun `a content-less assistant message is dropped at the replay boundary`() {
        val empty = UiMessage(role = MessageRole.ASSISTANT, content = "", metadata = mapOf("source" to "local"))

        assertTrue(empty.toChatMessages().isEmpty())
        assertEquals(1, UiMessage(role = MessageRole.ASSISTANT, content = "real").toChatMessages().size)
        assertEquals(1, UiMessage(role = MessageRole.USER, content = "").toChatMessages().size)
    }

    /** Re-trimming an already-trimmed body would cut through its own marker and compound it. */
    @Test
    fun `trimming is idempotent`() {
        val context = (1..40).map { toolTurn("c-$it", "dom-$it ".repeat(20_000)) }

        val once = digestOldToolPayloads(context)
        val twice = digestOldToolPayloads(once, maxTotalChars = 0)

        assertEquals(once, twice)
        val body = once.first().toolExecutions.single().result!!
        assertEquals(1, Regex("trimmed in stored context").findAll(body).count())
    }

    /**
     * The canonical list is replayed as model context on later turns, so it must carry the
     * provider's own tool name and arguments rather than the display-mapped ones.
     */
    @Test
    fun `canonical history replays raw tool names and arguments`() {
        val history = emptyList<String>()
            .appendCanonicalText("looking at the file")
            .plus(
                org.json.JSONObject()
                    .put("kind", "assistant_tool_call")
                    .put("id", "c1")
                    .put("name", "workspace_search")
                    .put("arguments", org.json.JSONObject(mapOf("query" to "LoginViewModel")))
                    .put("metadata", org.json.JSONObject())
                    .toString()
            )
            .plus(
                org.json.JSONObject()
                    .put("kind", "tool_result")
                    .put("id", "c1")
                    .put("name", "workspace_search")
                    .put("result", "3 matches")
                    .put("isError", false)
                    .toString()
            )

        val replayed = canonicalHistoryToChatMessages(history)

        assertEquals(2, replayed.size)
        val call = replayed.first().toolCalls!!.single()
        assertEquals("workspace_search", call.name)
        assertEquals("LoginViewModel", call.arguments["query"])
        assertEquals("3 matches", replayed.last().toolResult!!.content)
    }
}
