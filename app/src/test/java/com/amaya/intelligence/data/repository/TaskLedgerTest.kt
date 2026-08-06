package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.remote.api.ToolCallMessage
import com.amaya.intelligence.data.remote.api.ToolResultMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLedgerTest {

    private val goal = "Migrate the database layer to Room and keep the existing DAO API"

    /**
     * The defect this design replaces: each compaction fed the previous summary back into the next
     * summarization prompt, so the user's original request decayed a little every round.
     */
    @Test
    fun `goal is byte-identical after repeated compaction rounds`() {
        var ledger = TaskLedger(goal = goal)
        repeat(5) { round ->
            ledger = ledger.mergedWith(
                LedgerDelta(
                    decisions = listOf("round-$round decision"),
                    lastState = "state after round $round"
                ),
                newlyEvicted = 4,
                newlyEvictedToolResults = 1
            )
        }

        assertEquals(goal, ledger.goal)
        assertTrue(ledger.render().startsWith("GOAL: $goal"))
        assertEquals(20, ledger.evictedMessages)
        assertEquals(5, ledger.evictedToolResults)
    }

    @Test
    fun `list sections append without rewriting or reordering earlier entries`() {
        val first = TaskLedger(goal = goal).mergedWith(
            LedgerDelta(constraints = listOf("no new dependencies", "keep API stable")),
            newlyEvicted = 1,
            newlyEvictedToolResults = 0
        )
        val second = first.mergedWith(
            // "keep API stable" is a duplicate and must not appear twice.
            LedgerDelta(constraints = listOf("keep API stable", "min SDK 26")),
            newlyEvicted = 1,
            newlyEvictedToolResults = 0
        )

        assertEquals(listOf("no new dependencies", "keep API stable", "min SDK 26"), second.constraints)
    }

    @Test
    fun `an open question leaves the list only by being answered into decisions`() {
        val ledger = TaskLedger(goal = goal, openQuestions = listOf("Which migration strategy?"))

        val unresolved = ledger.mergedWith(LedgerDelta(decisions = listOf("Use Hilt for injection")), 1, 0)
        assertEquals(listOf("Which migration strategy?"), unresolved.openQuestions)

        val resolved = ledger.mergedWith(
            LedgerDelta(decisions = listOf("Which migration strategy? — destructive migration for dev builds")),
            1,
            0
        )
        assertTrue(resolved.openQuestions.isEmpty())
    }

    @Test
    fun `lastState is carried forward when the delta omits it`() {
        val ledger = TaskLedger(goal = goal, lastState = "stopped after writing Migration_7_8")

        val merged = ledger.mergedWith(LedgerDelta(decisions = listOf("x")), 1, 0)

        assertEquals("stopped after writing Migration_7_8", merged.lastState)
    }

    @Test
    fun `delta parser reads known sections and ignores everything else`() {
        val delta = parseLedgerDelta(
            """
            Here is the update.

            ## CONSTRAINTS
            - offline builds only
            ## DECISIONS
            - chose Room over SQLDelight — smaller migration surface
            ## FILES TOUCHED
            - app/src/main/java/com/example/Db.kt
            ## OPEN QUESTIONS
            - do we need a WAL checkpoint?
            ## LAST STATE
            Migration 7 to 8 compiles; unit tests not run yet.
            ## SOMETHING ELSE
            - ignored
            """.trimIndent()
        )

        assertEquals(listOf("offline builds only"), delta.constraints)
        assertEquals(listOf("chose Room over SQLDelight — smaller migration surface"), delta.decisions)
        assertEquals(listOf("app/src/main/java/com/example/Db.kt"), delta.filesTouched)
        assertEquals(listOf("do we need a WAL checkpoint?"), delta.openQuestions)
        assertEquals("Migration 7 to 8 compiles; unit tests not run yet.", delta.lastState)
        assertFalse(delta.isEmpty)
    }

    @Test
    fun `delta parser reports empty for output with no recognisable sections`() {
        assertTrue(parseLedgerDelta("I could not summarize that.").isEmpty)
        assertTrue(parseLedgerDelta("").isEmpty)
    }

    /**
     * The fallback that makes "never commit a lossy plan without a record" true even when the
     * summarizer errors, times out, or returns nothing.
     */
    @Test
    fun `mechanical ledger records touched files and the last state without a model`() {
        val evicted = listOf(
            ChatMessage(MessageRole.USER, "read the settings screen"),
            ChatMessage(
                MessageRole.ASSISTANT,
                toolCalls = listOf(
                    ToolCallMessage("c1", "read_file", mapOf("path" to "app/src/main/Settings.kt")),
                    ToolCallMessage("c2", "write_file", mapOf("file_path" to "app/src/main/Theme.kt", "content" to "x"))
                )
            ),
            ChatMessage(MessageRole.TOOL, toolResult = ToolResultMessage("c1", "file contents here")),
            ChatMessage(MessageRole.ASSISTANT, "I updated the theme colors.")
        )

        val ledger = mechanicalLedger(current = null, goal = goal, evicted = evicted, evictedToolResults = 1)

        assertEquals(goal, ledger.goal)
        assertTrue(ledger.filesTouched.contains("app/src/main/Settings.kt"))
        assertTrue(ledger.filesTouched.contains("app/src/main/Theme.kt"))
        assertFalse("tool argument values that are not paths must not leak in", ledger.filesTouched.contains("x"))
        assertTrue(ledger.lastState.contains("I updated the theme colors."))
        assertEquals(4, ledger.evictedMessages)
    }

    @Test
    fun `mechanical ledger preserves prior state it cannot re-derive`() {
        val existing = TaskLedger(
            goal = goal,
            constraints = listOf("offline only"),
            openQuestions = listOf("which index?"),
            decisions = listOf("chose Room")
        )

        val merged = mechanicalLedger(existing, goal, listOf(ChatMessage(MessageRole.USER, "next")), 0)

        assertEquals(listOf("offline only"), merged.constraints)
        assertEquals(listOf("which index?"), merged.openQuestions)
        assertEquals(listOf("chose Room"), merged.decisions)
    }

    @Test
    fun `render omits empty sections and states what was evicted`() {
        val rendered = TaskLedger(goal = goal, evictedMessages = 12, evictedToolResults = 3).render()

        assertTrue(rendered.startsWith("GOAL: $goal"))
        assertFalse(rendered.contains("CONSTRAINTS"))
        assertFalse(rendered.contains("OPEN QUESTIONS"))
        assertTrue(rendered.contains("EVICTED: 12 messages, 3 tool results"))
    }

    /**
     * The rendered ledger is the only copy that survives a process restart. If it cannot be read
     * back, the first compaction of the new session replaces every accumulated constraint,
     * decision and open question with a ledger holding only the goal.
     */
    @Test
    fun `a rendered ledger round-trips back into its sections`() {
        val original = TaskLedger(
            goal = goal,
            constraints = listOf("offline builds only", "min SDK 26"),
            decisions = listOf("chose Room over SQLDelight — smaller migration surface"),
            filesTouched = listOf("app/src/main/java/com/example/Db.kt"),
            openQuestions = listOf("do we need a WAL checkpoint?"),
            lastState = "Migration 7 to 8 compiles; unit tests not run yet.",
            evictedMessages = 34,
            evictedToolResults = 9
        )

        val restored = parseRenderedLedger(original.render(), fallbackGoal = "unused")!!

        assertEquals(original.goal, restored.goal)
        assertEquals(original.constraints, restored.constraints)
        assertEquals(original.decisions, restored.decisions)
        assertEquals(original.filesTouched, restored.filesTouched)
        assertEquals(original.openQuestions, restored.openQuestions)
        assertEquals(original.lastState, restored.lastState)
        assertEquals(34, restored.evictedMessages)
        assertEquals(9, restored.evictedToolResults)
        assertEquals(original.render(), restored.render())
    }

    @Test
    fun `a restored ledger keeps accumulating instead of being replaced`() {
        val before = TaskLedger(goal = goal, constraints = listOf("offline builds only"), evictedMessages = 12)

        val restored = parseRenderedLedger(before.render(), fallbackGoal = goal)!!
        val after = mechanicalLedger(restored, goal, listOf(ChatMessage(MessageRole.USER, "next")), 0)

        assertEquals(listOf("offline builds only"), after.constraints)
        assertEquals(13, after.evictedMessages)
    }

    @Test
    fun `unparseable text yields no ledger rather than an empty one`() {
        assertNull(parseRenderedLedger("", fallbackGoal = goal))
        assertNull(parseRenderedLedger("just some prose with no ledger structure", fallbackGoal = goal))
    }

    @Test
    fun `conversation goal is the first real user message not the continuation prompt`() {
        val messages = listOf(
            ChatMessage(MessageRole.ASSISTANT, "greeting"),
            ChatMessage(MessageRole.USER, goal),
            ChatMessage(MessageRole.USER, STREAM_CONTINUATION_PROMPT)
        )

        assertEquals(goal, conversationGoal(messages, fallback = "fallback"))
        assertEquals(
            "fallback",
            conversationGoal(listOf(ChatMessage(MessageRole.USER, STREAM_CONTINUATION_PROMPT)), fallback = "fallback")
        )
    }
}
