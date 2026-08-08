package com.amaya.intelligence.domain.security

import com.amaya.intelligence.data.repository.TerminalSettings
import com.amaya.intelligence.data.repository.commandMatchesWildcard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPolicyTest {
    @Test
    fun `wildcard matches the full normalized command`() {
        assertTrue(commandMatchesWildcard("npm run build", "npm *"))
        assertTrue(commandMatchesWildcard("npm run build", "npm run *"))
        assertFalse(commandMatchesWildcard("npm install", "npm run *"))
        assertFalse(commandMatchesWildcard("prefix npm run build", "npm *"))
    }

    @Test
    fun `trusted shell grammar is allowed`() {
        val settings = TerminalSettings(
            trustedCommands = listOf("echo *", "printf *"),
            declinedCommands = emptyList()
        )
        listOf(
            "echo hello > out.txt",
            "printf x | grep x",
            "echo first && echo second",
            "echo $(pwd)"
        ).forEach { command ->
            assertTrue(command, validator().validateCommand(command, settings) is ValidationResult.Allowed)
        }
    }

    @Test
    fun `declined command wins over trusted wildcard`() {
        val settings = TerminalSettings(
            trustedCommands = listOf("npm *"),
            declinedCommands = listOf("npm publish*")
        )
        assertTrue(validator().validateCommand("npm run build", settings) is ValidationResult.Allowed)
        assertTrue(validator().validateCommand("npm publish", settings) is ValidationResult.Denied)
    }

    @Test
    fun `unmatched command requires review`() {
        // autoApproveNonDestructive defaults to true, which would auto-allow a harmless command;
        // the review path is what this test pins down, so it disables auto-approval explicitly.
        val result = validator().validateCommand(
            "custom-linter --check",
            TerminalSettings(trustedCommands = emptyList(), autoApproveNonDestructive = false)
        )
        assertTrue(result is ValidationResult.RequiresConfirmation)
    }

    @Test
    fun `host destructive boundary cannot be trusted`() {
        val trustAll = TerminalSettings(trustedCommands = listOf("*"))
        listOf(
            "rm -rf /",
            "mkfs.ext4 /dev/block/test",
            "dd if=/dev/zero of=/dev/sda",
            "echo x > /system/build.prop"
        ).forEach { command ->
            assertTrue(command, validator().validateCommand(command, trustAll) is ValidationResult.Denied)
        }
    }

    private fun validator(): CommandValidator = CommandValidator(
        object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): java.io.File = java.nio.file.Files.createTempDirectory("command-policy").toFile()
        }
    )
}
