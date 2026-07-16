package com.amaya.intelligence.domain.security

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ToolTargetPolicyTest {
    @Test
    fun `workspace tool without target returns actionable error`() {
        assertNotNull(missingToolTarget("list_files", emptyMap()))
        assertNotNull(missingToolTarget("read_file", mapOf("path" to "")))
    }

    @Test
    fun `explicit path or batch paths satisfy target`() {
        assertNull(missingToolTarget("list_files", mapOf("path" to "/tmp")))
        assertNull(missingToolTarget("read_file", mapOf("paths" to listOf("/tmp/a"))))
        assertNull(missingToolTarget("web_search", emptyMap()))
    }
}
