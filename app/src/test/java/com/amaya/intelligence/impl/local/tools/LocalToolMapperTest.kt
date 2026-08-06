package com.amaya.intelligence.impl.local.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalToolMapperTest {
    @Test
    fun `display label uses real tool action and target`() {
        assertEquals("Read hai.txt", LocalToolMapper.displayLabel("read_file", mapOf("path" to "docs/hai.txt")))
        assertEquals("Edit MainActivity.kt", LocalToolMapper.displayLabel("workspace_change", mapOf("operation" to "replace", "path" to "app/MainActivity.kt")))
        assertEquals("Run ./gradlew assembleDebug", LocalToolMapper.displayLabel("run_shell", mapOf("command" to "./gradlew assembleDebug")))
    }
}
