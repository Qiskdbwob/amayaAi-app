package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.ChatImage
import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.remote.api.ToolCallMessage
import com.amaya.intelligence.data.remote.api.ToolResultMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ContextPlanTest {

    private fun user(text: String) = contextUser(text)
    private fun assistant(text: String) = contextAssistant(text)
    private fun toolCall(id: String, name: String = "read_file") = contextToolCall(id, name)
    private fun toolResult(id: String, content: String) = contextToolResult(id, content)

    /**
     * The regression from commit 28c499e9: the whole prefix was dropped on every request regardless
     * of remaining budget, so verbatim history never reached the model from turn 2 onward.
     */
    @Test
    fun `plan retains full history when it fits`() {
        val history = buildList {
            add(user("Refactor LoginViewModel to use Flow"))
            repeat(9) { index ->
                add(assistant("answer-$index"))
                add(user("follow-up-$index"))
            }
        }

        val plan = planContextWindow(history, maxTokens = 1_000_000)

        assertEquals(history, plan.messages)
        assertTrue(plan.evicted.isEmpty())
        assertTrue(plan.truncatedOriginals.isEmpty())
        assertFalse(plan.isLossy)
    }

    @Test
    fun `plan pins the first user message when the middle must be evicted`() {
        val history = buildList {
            add(user("ORIGINAL GOAL: migrate the database layer to Room"))
            repeat(40) { index -> add(assistant("filler-$index ".repeat(120))) }
            add(user("keep going"))
        }

        val plan = planContextWindow(history, maxTokens = 800)

        assertEquals(history.first(), plan.messages.first())
        assertEquals(history.last(), plan.messages.last())
        assertTrue(plan.evicted.isNotEmpty())
    }

    /**
     * A pasted spec or stack trace as the first message used to disqualify the anchor entirely, so
     * the original request was evicted while budget was still free. It is now abridged, not dropped.
     */
    @Test
    fun `an oversized first message is pinned in abridged form rather than dropped`() {
        val hugeGoal = "ORIGINAL GOAL: " + "detailed specification paragraph. ".repeat(2_000)
        val history = buildList {
            add(user(hugeGoal))
            repeat(6) { index -> add(assistant("reply-$index ".repeat(30))) }
            add(user("continue"))
        }

        val plan = planContextWindow(history, maxTokens = 1_500)

        val first = plan.messages.first()
        assertTrue("the goal must still lead the window", first.content!!.startsWith("ORIGINAL GOAL:"))
        assertTrue("it must be marked as abridged", first.content!!.contains("abridged"))
        assertTrue(first.content!!.length < hugeGoal.length)
        assertTrue(TokenEstimator.messages(plan.messages) <= 1_500)
        assertTrue("the abridgement must be reported", plan.truncatedOriginals.isNotEmpty())
        assertEquals(history.size, plan.messages.size + plan.evicted.size)
    }

    /**
     * Abridgement is a last resort for pinning, not a property of the message. When the whole
     * history fits, a long first message must be sent exactly as the user wrote it.
     */
    @Test
    fun `a long first message is sent verbatim when the whole history fits`() {
        val longGoal = "ORIGINAL GOAL: " + "detailed specification paragraph. ".repeat(2_000)
        val history = listOf(user(longGoal), assistant("ok"), user("continue"))

        val plan = planContextWindow(history, maxTokens = 1_000_000)

        assertEquals(history, plan.messages)
        assertEquals(longGoal, plan.messages.first().content)
        assertTrue(plan.truncatedOriginals.isEmpty())
        assertFalse(plan.isLossy)
    }

    /**
     * Abridging an image-bearing first turn would strip the payload and leave an empty user
     * message, which providers reject.
     */
    @Test
    fun `an image-bearing first message is never abridged into an empty message`() {
        val screenshot = ChatImage(base64 = "x".repeat(400_000), mediaType = "image/png")
        val imageTurn = ChatMessage(MessageRole.USER, content = "", images = listOf(screenshot))
        val history = listOf(imageTurn, assistant("that is the settings screen"), user("now fix it"))

        val plan = planContextWindow(history, maxTokens = 2_000)

        plan.messages.forEach {
            assertTrue(
                "a user message must never be sent with neither text nor images",
                it.role != MessageRole.USER || !it.content.isNullOrBlank() || it.images.isNotEmpty()
            )
        }
        plan.messages.firstOrNull { it.images.isNotEmpty() }?.let {
            assertEquals(1, it.images.size)
        }
    }

    @Test
    fun `eviction boundary marks the first retained index`() {
        val history = buildList {
            add(user("ORIGINAL GOAL"))
            repeat(30) { index -> add(assistant("filler-$index ".repeat(60))) }
            add(user("continue"))
        }

        val plan = planContextWindow(history, maxTokens = 900)

        assertTrue(plan.evictionBoundary > 0)
        // Everything from the boundary on is retained, plus the separately pinned anchor.
        val retained = history.drop(plan.evictionBoundary)
        assertTrue(retained.all { message -> plan.messages.any { it === message || it.content == message.content } })
        assertEquals(history[0].content, plan.pinnedAnchor?.content?.substringBefore("\n…"))
    }

    /**
     * The agent loop re-plans a growing history every iteration; without memoisation the same
     * multi-megabyte tool results are re-scanned each time.
     */
    @Test
    fun `a cost cache returns the same plan as an uncached run`() {
        val history = buildList {
            add(user("ORIGINAL GOAL"))
            repeat(10) { index ->
                add(toolCall("c-$index"))
                add(toolResult("c-$index", "payload-$index ".repeat(300)))
            }
            add(user("continue"))
        }
        val cache = CostCache()

        val uncached = planContextWindow(history, maxTokens = 5_000)
        val cached = planContextWindow(history, maxTokens = 5_000, cache = cache)
        // A second pass over the same instances must hit the memo and still agree.
        val again = planContextWindow(history, maxTokens = 5_000, cache = cache)

        assertEquals(uncached.messages, cached.messages)
        assertEquals(uncached.evicted, cached.evicted)
        assertEquals(uncached.usedTokens, cached.usedTokens)
        assertEquals(uncached.evictionBoundary, again.evictionBoundary)
        assertEquals(uncached.messages, again.messages)
    }

    @Test
    fun `the cost cache stays bounded and keeps agreeing after it is dropped`() {
        val cache = CostCache(maxEntries = 4)
        val messages = (1..50).map { user("m-$it ".repeat(20)) }

        messages.forEach { message ->
            assertEquals(TokenEstimator.message(message), TokenEstimator.message(message, cache = cache))
        }
    }

    @Test
    fun `plan partitions the input exactly`() {
        val random = Random(20260727)
        repeat(200) {
            val size = random.nextInt(1, 24)
            val history = (0 until size).map { index ->
                when (random.nextInt(4)) {
                    0 -> user("u-$index ".repeat(random.nextInt(1, 40)))
                    1 -> assistant("a-$index ".repeat(random.nextInt(1, 40)))
                    2 -> toolCall("call-$index")
                    else -> toolResult("call-$index", "r-$index ".repeat(random.nextInt(1, 60)))
                }
            }
            val plan = planContextWindow(history, maxTokens = random.nextInt(0, 4_000))

            assertEquals(
                "size mismatch for $history",
                history.size,
                plan.messages.size + plan.evicted.size
            )
            // The accounting must match what is actually sent: charging a message at one size and
            // then substituting a different one is how an over-budget request slips through.
            assertTrue(
                "planned window exceeds its own budget",
                plan.messages.isEmpty() || TokenEstimator.messages(plan.messages) <= plan.usedTokens
            )
            // Order is preserved and no message is invented.
            assertEquals(plan.messages, plan.messages.sortedBy { message -> history.indexOfFirst { it === message } })
        }
    }

    @Test
    fun `plan keeps assistant tool spans atomic`() {
        val history = buildList {
            add(user("start"))
            repeat(12) { index ->
                add(toolCall("call-$index"))
                add(toolResult("call-$index", "payload-$index ".repeat(200)))
            }
            add(user("continue the task"))
        }

        val plan = planContextWindow(history, maxTokens = 900)

        val keptCallIds = plan.messages
            .flatMap { it.toolCalls.orEmpty() }
            .map { it.id }
            .toSet()
        plan.messages.mapNotNull { it.toolResult }.forEach { result ->
            assertTrue(
                "orphaned tool result ${result.toolCallId} without its assistant tool call",
                result.toolCallId in keptCallIds
            )
        }
    }

    @Test
    fun `stream continuation prompt never becomes the anchor`() {
        val history = listOf(
            user("Build a settings screen with a dark-mode toggle"),
            assistant("partial answer that got cut off"),
            ChatMessage(MessageRole.USER, STREAM_CONTINUATION_PROMPT)
        )

        val plan = planContextWindow(history, maxTokens = 100_000)

        assertTrue(
            "the real user request must survive a stream continuation",
            plan.messages.any { it.content == "Build a settings screen with a dark-mode toggle" }
        )
        assertTrue(plan.evicted.isEmpty())
    }

    @Test
    fun `images are counted so an oversized image turn cannot slip through`() {
        val screenshot = ChatImage(base64 = "x".repeat(400_000), mediaType = "image/png")
        val withImage = ChatMessage(MessageRole.USER, "what is on screen?", images = listOf(screenshot))
        val withoutImage = ChatMessage(MessageRole.USER, "what is on screen?")

        assertEquals(
            TokenEstimator.PER_IMAGE_TOKENS,
            TokenEstimator.message(withImage) - TokenEstimator.message(withoutImage)
        )

        // A budget that comfortably fits the text alone must still reject the image turn.
        val plan = planContextWindow(listOf(withImage), maxTokens = 200)
        assertTrue(plan.messages.isEmpty())
        assertEquals(listOf(withImage), plan.evicted)
    }

    @Test
    fun `bridge screenshot metadata is counted like an image`() {
        val plain = toolResult("call", "{\"status\":\"ok\"}")
        val withScreenshot = ChatMessage(
            MessageRole.TOOL,
            toolResult = ToolResultMessage("call", "{\"status\":\"ok\"}", metadata = mapOf("bridge_image_base64" to "y".repeat(9_000)))
        )

        assertEquals(
            TokenEstimator.PER_IMAGE_TOKENS,
            TokenEstimator.message(withScreenshot) - TokenEstimator.message(plain)
        )
    }

    @Test
    fun `newest tool result survives verbatim while older ones decay`() {
        val history = buildList {
            add(user("inspect the page"))
            repeat(9) { index ->
                add(toolCall("call-$index", name = "browser"))
                add(toolResult("call-$index", "dom-chunk-$index ".repeat(400)))
            }
        }

        val plan = planContextWindow(history, maxTokens = 3_000)

        val newest = plan.messages.last { it.role == MessageRole.TOOL }
        assertEquals(
            "the freshest tool result must not be truncated",
            history.last().toolResult?.content,
            newest.toolResult?.content
        )
        assertTrue("older results should have decayed", plan.truncatedOriginals.isNotEmpty())
        assertTrue(plan.isLossy)
    }

    @Test
    fun `tool result decay respects the token budget it was given`() {
        val history = buildList {
            add(user("go"))
            repeat(6) { index ->
                add(toolCall("call-$index"))
                add(toolResult("call-$index", "payload-$index ".repeat(900)))
            }
        }
        val budget = 2_500

        val plan = planContextWindow(history, maxTokens = budget)

        assertTrue(
            "planned window (${TokenEstimator.messages(plan.messages)}) must fit $budget",
            TokenEstimator.messages(plan.messages) <= budget
        )
    }

    /**
     * A write_file carrying a whole file body is not a tool *result* and used to be undecayable, so
     * one large edit could push the active turn past the window with no way to recover.
     */
    @Test
    fun `already-executed tool call arguments decay so a large write does not kill the turn`() {
        val bigBody = "line of source code\n".repeat(4_000)
        val history = listOf(
            user("apply the refactor"),
            ChatMessage(
                MessageRole.ASSISTANT,
                toolCalls = listOf(ToolCallMessage("w1", "write_file", mapOf("path" to "A.kt", "content" to bigBody)))
            ),
            toolResult("w1", "written"),
            ChatMessage(
                MessageRole.ASSISTANT,
                toolCalls = listOf(ToolCallMessage("w2", "read_file", mapOf("path" to "B.kt")))
            ),
            toolResult("w2", "ok")
        )

        val plan = planContextWindow(history, maxTokens = 2_000)

        assertTrue("the turn must survive", plan.messages.isNotEmpty())
        assertTrue(TokenEstimator.messages(plan.messages) <= 2_000)
        val writeArgs = plan.messages
            .flatMap { it.toolCalls.orEmpty() }
            .first { it.name == "write_file" }
            .arguments
        assertTrue((writeArgs["content"] as String).contains("argument truncated"))
        assertEquals("A.kt", writeArgs["path"])
    }

    @Test
    fun `nothing is evicted while the history fits under the high-water mark`() {
        val history = buildList {
            add(user("goal"))
            repeat(10) { index -> add(assistant("reply-$index ".repeat(20))) }
            add(user("next"))
        }
        val exactCost = TokenEstimator.messages(history)

        val plan = planContextWindow(history, maxTokens = exactCost)

        assertTrue(plan.evicted.isEmpty())
        assertEquals(history.size, plan.messages.size)
    }

    @Test
    fun `an empty or unusable history yields an empty plan without crashing`() {
        assertEquals(0, planContextWindow(emptyList(), 1_000).messages.size)
        assertEquals(0, planContextWindow(listOf(assistant("no user turn")), 1_000).messages.size)
        assertEquals(1, planContextWindow(listOf(assistant("no user turn")), 1_000).evicted.size)

        val history = listOf(user("hello"))
        val zeroBudget = planContextWindow(history, 0)
        assertTrue(zeroBudget.messages.isEmpty())
        assertEquals(history, zeroBudget.evicted)
    }

    @Test
    fun `truncateToTokens lands under budget whenever the marker fits`() {
        val long = "kalimat panjang dalam bahasa Indonesia ".repeat(500)
        listOf(50, 200, 1_000).forEach { budget ->
            val truncated = TokenEstimator.truncateToTokens(long, budget)
            assertTrue(
                "budget=$budget produced ${TokenEstimator.text(truncated)} tokens",
                TokenEstimator.text(truncated) <= budget
            )
            assertTrue(truncated.contains("truncated"))
        }
        assertEquals("short", TokenEstimator.truncateToTokens("short", 1_000))
    }

    /**
     * Handing back a bare prefix would present cut content as complete — for a JSON tool result that
     * is malformed data the model cannot tell apart from a real payload.
     */
    @Test
    fun `truncateToTokens never returns an unmarked prefix`() {
        val payload = "{\"status\":\"ok\",\"body\":\"" + "x".repeat(4_000) + "\"}"
        listOf(0, 1, 5, 10, 40).forEach { budget ->
            val truncated = TokenEstimator.truncateToTokens(payload, budget)
            assertTrue(
                "budget=$budget produced content with no truncation marker: $truncated",
                truncated.contains("truncated")
            )
            assertTrue(truncated.isNotBlank())
        }
    }

    /**
     * The regression this file exists to prevent, re-entered through the decay path: the newest tool
     * result expanded to fill the whole window, leaving nothing for the pinned goal.
     */
    @Test
    fun `goal anchor survives an active turn that ends in a huge tool result`() {
        val history = listOf(
            user("ORIGINAL GOAL: migrate the database layer to Room"),
            assistant("ok"),
            user("also: never touch the network module"),
            assistant("understood"),
            user("keep going"),
            toolCall("c1", name = "browser"),
            toolResult("c1", "dom ".repeat(200_000))
        )

        val plan = planContextWindow(history, maxTokens = 3_000)

        assertEquals(
            "the original goal must be pinned",
            "ORIGINAL GOAL: migrate the database layer to Room",
            plan.messages.first().content
        )
        assertTrue(TokenEstimator.messages(plan.messages) <= 3_000)
    }

    @Test
    fun `goal anchor is pinned across randomized tool-heavy histories`() {
        val random = Random(4242)
        var lost = 0
        repeat(500) {
            val history = buildList {
                add(user("ORIGINAL GOAL marker"))
                repeat(random.nextInt(1, 6)) { index ->
                    add(assistant("reply-$index ".repeat(random.nextInt(1, 30))))
                    add(user("follow-$index ".repeat(random.nextInt(1, 20))))
                }
                add(toolCall("cx", name = "browser"))
                add(toolResult("cx", "dom ".repeat(random.nextInt(500, 40_000))))
            }
            val plan = planContextWindow(history, maxTokens = random.nextInt(500, 4_000))
            if (plan.messages.isNotEmpty() && plan.messages.none { it.content == "ORIGINAL GOAL marker" }) lost++
        }
        assertEquals("the goal anchor was evicted in $lost of 500 histories", 0, lost)
    }

    /**
     * An empty tool_result tells the model the tool returned nothing — strictly worse than telling
     * it the result was dropped, and rejected outright by some providers.
     */
    @Test
    fun `decayed tool results are never blanked to the empty string`() {
        val history = buildList {
            add(user("inspect the page"))
            repeat(9) { index ->
                add(toolCall("call-$index", name = "browser"))
                add(toolResult("call-$index", "dom-chunk-$index ".repeat(400)))
            }
        }

        val plan = planContextWindow(history, maxTokens = 3_000)

        plan.messages.mapNotNull { it.toolResult }.forEach { result ->
            assertTrue("a tool result was blanked entirely", result.content.isNotBlank())
        }
        val decayedBodies = plan.messages.mapNotNull { it.toolResult?.content }
            .filterNot { it.startsWith("dom-chunk") }
        assertTrue(decayedBodies.isNotEmpty())
        decayedBodies.forEach {
            assertTrue("decayed body must say it was decayed: $it", it.contains("elided") || it.contains("truncated"))
        }
    }

    @Test
    fun `estimator counts tool payloads that the old content-only gate ignored`() {
        val contentOnly = ChatMessage(MessageRole.ASSISTANT, "ok")
        val heavyToolResult = ChatMessage(
            MessageRole.TOOL,
            toolResult = ToolResultMessage("call", "x".repeat(40_000))
        )
        val heavyArguments = ChatMessage(
            MessageRole.ASSISTANT,
            toolCalls = listOf(ToolCallMessage("call", "write_file", mapOf("content" to "y".repeat(40_000))))
        )

        assertTrue(TokenEstimator.message(heavyToolResult) > 10_000)
        assertTrue(TokenEstimator.message(heavyArguments) > 10_000)
        assertTrue(TokenEstimator.message(contentOnly) < 20)
    }

    @Test
    fun `anchor is not duplicated when it also sits inside the retained window`() {
        val history = listOf(user("goal"), assistant("a"), user("now this"))

        val plan = planContextWindow(history, maxTokens = 1_000_000)

        assertEquals(1, plan.messages.count { it.content == "goal" })
        assertEquals(history, plan.messages)
    }

    @Test
    fun `a single oversized active turn fails closed instead of sending an oversized request`() {
        val history = listOf(user("goal"), user("x ".repeat(20_000)))

        val plan = planContextWindow(history, maxTokens = 500)

        assertTrue(plan.messages.isEmpty())
        assertEquals(history.size, plan.evicted.size)
        assertNotNull(plan)
    }
}
