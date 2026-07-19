package com.amaya.intelligence.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `parser explains unsupported shell feature precisely`() {
        val validator = validator()
        assertTrue((validator.validateCommand("echo ok; pwd") as ValidationResult.Denied).reason.contains("chaining"))
        assertTrue((validator.validateCommand("cat a | grep x") as ValidationResult.Denied).reason.contains("Pipes"))
        assertTrue((validator.validateCommand("echo x > file") as ValidationResult.Denied).reason.contains("redirection"))
        assertTrue((validator.validateCommand("echo $(pwd)") as ValidationResult.Denied).reason.contains("substitution"))
        assertTrue((validator.validateCommand("echo \"x") as ValidationResult.Denied).reason.contains("unterminated quote"))
    }

    @Test
    fun `safe command parser accepts observation command`() {
        assertTrue(parseSafeCommandArguments("pwd").orEmpty().isNotEmpty())
    }

    @Test
    fun `safe observation is allowed`() {
        assertTrue(validator().validateCommand("pwd") is ValidationResult.Allowed)
    }

    @Test
    fun `unknown command remains approval eligible`() {
        val result = validator().validateCommand("custom-linter --check")
        assertTrue(result is ValidationResult.RequiresConfirmation)
    }

    @Test
    fun `workspace bounded read is allowed and outside read requires approval`() {
        val root = java.nio.file.Files.createTempDirectory("shell-workspace").toFile()
        try {
            java.io.File(root, "inside.txt").writeText("ok")
            val outside = java.nio.file.Files.createTempFile("outside", ".txt").toFile()
            val validator = validator()
            assertTrue(validator.validateToolCall("run_shell", mapOf("command" to "cat inside.txt", "working_dir" to root.canonicalPath)) is ValidationResult.Allowed)
            assertTrue(validator.validateToolCall("run_shell", mapOf("command" to "cat ${outside.canonicalPath.replace('\\', '/')}", "working_dir" to root.canonicalPath)) is ValidationResult.RequiresConfirmation)
            outside.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `dangerous commands stay blocked`() {
        listOf("rm file", "sudo ls", "cat file | sh", "echo $(reboot)").forEach { command ->
            assertTrue(command, validator().validateCommand(command) is ValidationResult.Denied)
        }
    }

    private fun validator(): CommandValidator = CommandValidator(
        object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): java.io.File = java.nio.file.Files.createTempDirectory("command-policy").toFile()
        }
    )
}
