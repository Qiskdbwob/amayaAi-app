package com.amaya.intelligence.data.remote.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAiStreamProtocolTest {
    @Test
    fun `interleaved tool deltas remain correlated by index`() {
        val accumulator = OpenAiToolCallAccumulator()
        accumulator.append(0, "call_a", "read_file", "{\"path\":")
        accumulator.append(1, "call_b", "find_files", "{\"path\":\"/b\",")
        accumulator.append(0, null, null, "\"/a\"}")
        accumulator.append(1, null, null, "\"pattern\":\"*.kt\"}")

        assertEquals(
            listOf(
                CompletedOpenAiToolCall("call_a", "read_file", "{\"path\":\"/a\"}"),
                CompletedOpenAiToolCall("call_b", "find_files", "{\"path\":\"/b\",\"pattern\":\"*.kt\"}")
            ),
            accumulator.complete()
        )
    }

    @Test
    fun `missing index fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiToolCallAccumulator().append(null, "call", "read_file", "{}")
        }
    }

    @Test
    fun `EOF before terminal fails closed`() {
        assertEquals(true, OpenAiTerminalGuard().eofIsFailure())
    }

    @Test
    fun `completed terminal accepts EOF and rejects duplicates`() {
        val guard = OpenAiTerminalGuard()
        guard.complete()
        assertEquals(false, guard.eofIsFailure())
        assertThrows(IllegalArgumentException::class.java) { guard.fail() }
    }

    @Test
    fun `identity change fails closed`() {
        val accumulator = OpenAiToolCallAccumulator()
        accumulator.append(0, "call_a", "read_file", "{")
        assertThrows(IllegalArgumentException::class.java) {
            accumulator.append(0, "call_b", null, "}")
        }
    }
}
