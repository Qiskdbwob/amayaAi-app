package com.amaya.intelligence.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityToolMapperTest {
    @Test
    fun `workspace patch maps to legacy edit handler`() {
        val call = CapabilityToolMapper.map(
            "workspace_change",
            mapOf("operation" to "patch", "path" to "src/Main.kt", "diff" to "@@ -1 +1 @@")
        )!!
        assertEquals("edit_file", call.handlerName)
        assertEquals("src/Main.kt", call.arguments["path"])
    }

    @Test
    fun `memory update preserves compare and swap version`() {
        val call = CapabilityToolMapper.map(
            "memory",
            mapOf("operation" to "update", "id" to "mem_1", "expected_version" to 3, "content" to "New value")
        )!!
        assertEquals("memory_manage", call.handlerName)
        assertEquals(3, call.arguments["expected_version"])
    }

    @Test
    fun `memory archive and delete are rejected`() {
        assertTrue(CapabilityToolMapper.map("memory", mapOf("operation" to "archive")) == null)
        assertTrue(CapabilityToolMapper.map("memory", mapOf("operation" to "delete")) == null)
    }

    @Test
    fun `unknown workspace operation is rejected`() {
        assertTrue(CapabilityToolMapper.map("workspace_change", mapOf("operation" to "restore")) == null)
    }
}
