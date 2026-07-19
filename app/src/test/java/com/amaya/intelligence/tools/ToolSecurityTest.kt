package com.amaya.intelligence.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import org.junit.Test

class ToolSecurityTest {
    @Test
    fun `ordinary arguments survive unchanged`() {
        val args = mapOf<String, Any?>("path" to "/tmp/a", "limit" to 5.0)
        assertEquals(args, sanitizeModelArguments(args).getOrThrow())
    }

    @Test
    fun `nested host control keys are rejected`() {
        assertTrue(sanitizeModelArguments(mapOf("params" to mapOf("allow_sensitive" to true))).isFailure)
    }

    @Test
    fun `host control keys are rejected`() {
        listOf("__confirmed", "__eventEmitter", "allow_sensitive", "parent_call_id").forEach { key ->
            assertTrue(key, sanitizeModelArguments(mapOf(key to true)).isFailure)
        }
    }

    @Test
    fun `archive budget fails closed across entries`() {
        val budget = ByteReadBudget(4)
        assertEquals(3, budget.readBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3))).size)
        assertTrue(runCatching { budget.readBytes(ByteArrayInputStream(byteArrayOf(4, 5))) }.isFailure)
    }

    @Test
    fun `readonly subagent cannot mutate`() {
        assertTrue(isAllowedInReadOnlyMode("read_file"))
        assertTrue(!isAllowedInReadOnlyMode("write_file"))
        assertTrue(!isAllowedInReadOnlyMode("memory_manage"))
        assertTrue(!isAllowedInReadOnlyMode("run_shell"))
    }

    @Test
    fun `host execution context discards model directories`() {
        val args = applyHostExecutionContext(
            "run_shell",
            mapOf("command" to "pwd", "cwd" to "/evil", "working_dir" to "/evil"),
            "/workspace"
        )
        assertEquals("/workspace", args["working_dir"])
        assertTrue("cwd" !in args)
    }

    @Test
    fun `subagent output has no character truncation`() {
        val root = java.io.File(requireNotNull(System.getProperty("user.dir")))
        val project = generateSequence(root) { it.parentFile }
            .first { java.io.File(it, "app/src/main/java/com/amaya/intelligence/tools/InvokeSubagentsTool.kt").isFile }
        val runner = java.io.File(project, "app/src/main/java/com/amaya/intelligence/tools/InvokeSubagentsTool.kt").readText()
        val card = java.io.File(project, "app/src/main/java/com/amaya/intelligence/ui/components/shared/ToolCallCard.kt").readText()
        assertTrue("output.take(6_000)" !in runner)
        assertTrue("take(2_000)" !in runner)
        assertTrue("truncateAt    = 2000" !in card)
        assertTrue("(execution.result ?: \"\").take(3000)" !in card)
    }

    @Test
    fun `write and edit tools contain no backup feature`() {
        val root = java.io.File(requireNotNull(System.getProperty("user.dir")))
        val toolsDir = generateSequence(root) { it.parentFile }
            .map { java.io.File(it, "app/src/main/java/com/amaya/intelligence/tools") }
            .first { it.isDirectory }
        val source = listOf("WriteFileTool.kt", "EditFileTool.kt").joinToString("\n") { java.io.File(toolsDir, it).readText() }
        assertTrue(".bak." !in source)
        assertTrue("create_backup" !in source)
        assertTrue("UndoChangeTool" !in source)
    }
}
