package com.amaya.intelligence.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandPolicyTest {
    @Test
    fun `parser preserves quoted argv`() {
        assertEquals(
            listOf("git", "commit", "-m", "hello world"),
            parseSafeCommandArguments("git commit -m \"hello world\"")
        )
    }

    @Test
    fun `parser rejects compound shell grammar`() {
        listOf(
            "echo ok; reboot",
            "printf x && dd if=/dev/zero",
            "cat file | sh",
            "echo x > /system/x",
            "echo $(reboot)",
            "echo ok\nreboot"
        ).forEach { assertNull(it, parseSafeCommandArguments(it)) }
    }
}
