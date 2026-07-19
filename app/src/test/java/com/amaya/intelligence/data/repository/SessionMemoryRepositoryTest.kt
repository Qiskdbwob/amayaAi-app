package com.amaya.intelligence.data.repository

import android.content.ContextWrapper
import com.amaya.intelligence.data.local.files.FileSessionStore
import com.amaya.intelligence.domain.memory.MemoryClassifier
import com.amaya.intelligence.domain.memory.MemoryContentNormalizer
import com.amaya.intelligence.domain.memory.MemorySafetyFilter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionMemoryRepositoryTest {
    private val root = Files.createTempDirectory("amaya-session-test-").toFile()
    private val testContext = object : ContextWrapper(null) { override fun getFilesDir(): File = root }
    private val workspaceStore = com.amaya.intelligence.data.local.files.FileWorkspaceMemoryStore(testContext)
    private val store = FileSessionStore(testContext)
    private val repository = FileSessionMemoryRepository(
        store,
        workspaceStore,
        MemoryClassifier(MemorySafetyFilter(), MemoryContentNormalizer())
    )

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `sensitive session content is not searchable`() = runBlocking {
        repository.saveMessage(SessionMessage(sessionId = "secret", role = "user", content = "api_key=sk-abcdefgh12345678"))
        assertTrue(repository.searchSessions("abcdefgh12345678", workspacePath = null).isEmpty())
        assertTrue(File(root, "sessions/sessions.jsonl").readText().contains("[REDACTED"))
    }

    @Test
    fun `summary and tool result participate in recall scoring`() = runBlocking {
        repository.saveSummary(SessionSummary("summary", "Resolved the Gradle signing failure", listOf("android"), 1L, 2L))
        repository.saveToolCall(SessionToolCall(sessionId = "tool", toolName = "read_file", input = "manifest", output = "Gradle signing configuration fixed"))
        assertEquals("summary", repository.searchSessions("Gradle signing failure", workspacePath = null).first().sessionId)
        assertTrue(repository.searchSessions("Gradle signing configuration", workspacePath = null).any { it.sessionId == "tool" })
    }

    @Test
    fun `recall is workspace scoped and irrelevant query returns none`() = runBlocking {
        val workspaceA = File(root, "workspace-a").apply { mkdirs() }.canonicalPath
        val workspaceB = File(root, "workspace-b").apply { mkdirs() }.canonicalPath
        repository.saveMessage(SessionMessage(sessionId = "a", role = "user", content = "Fix the Gradle build failure", workspacePath = workspaceA, workspaceId = workspaceStore.resolve(workspaceA)?.id))
        repository.saveMessage(SessionMessage(sessionId = "b", role = "user", content = "Fix the Maven build failure", workspacePath = workspaceB, workspaceId = workspaceStore.resolve(workspaceB)?.id))

        assertEquals(listOf("a"), repository.searchSessions("Gradle build", workspacePath = workspaceA).map { it.sessionId })
        assertTrue(repository.searchSessions("Maven build", workspacePath = workspaceA).isEmpty())
        assertTrue(repository.searchSessions("unrelated quantum recipe", workspacePath = workspaceA).isEmpty())
        assertTrue(repository.searchSessions("Gradle build", workspacePath = null).isEmpty())

        val idA = workspaceStore.resolve(workspaceA)!!.id
        File(workspaceA).deleteRecursively()
        val movedA = File(root, "workspace-a-moved").apply { mkdirs() }.canonicalPath
        workspaceStore.remap(idA, movedA)
        assertEquals(listOf("a"), repository.searchSessions("Gradle build", workspacePath = movedA).map { it.sessionId })

        repository.saveMessage(SessionMessage(sessionId = "legacy", role = "user", content = "Gradle legacy session", workspacePath = workspaceA))
        assertEquals(listOf("legacy", "a").toSet(), repository.searchSessions("Gradle", workspacePath = movedA).map { it.sessionId }.toSet())
    }
}
