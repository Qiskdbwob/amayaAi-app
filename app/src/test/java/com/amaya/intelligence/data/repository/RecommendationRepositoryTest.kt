package com.amaya.intelligence.data.repository

import android.content.ContextWrapper
import com.amaya.intelligence.domain.memory.Recommendation
import com.amaya.intelligence.domain.memory.RecommendationPriority
import com.amaya.intelligence.domain.memory.RecommendationStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecommendationRepositoryTest {
    private val root = Files.createTempDirectory("amaya-recommendation-test-").toFile()
    private val testContext = object : ContextWrapper(null) { override fun getFilesDir(): File = root }
    private val repository = FileRecommendationRepository(testContext)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun `suggest creates suggested recommendation and dedupes active titles`() = run {
        val id = repository.suggest("/ws", "Support arm64-v8a", rationale = "Release target", priority = RecommendationPriority.HIGH)
            .getOrThrow()
        val record = repository.get(id)!!
        assertEquals(RecommendationStatus.SUGGESTED, record.status)
        assertEquals(RecommendationPriority.HIGH, record.priority)
        assertTrue(record.rationale.contains("Release"))

        assertTrue(repository.suggest("/ws", "  support arm64-v8a  ").isFailure)
        // Same title under a different workspace is a different recommendation.
        assertTrue(repository.suggest("/other", "Support arm64-v8a").isSuccess)
    }

    @Test
    fun `lifecycle transitions are guarded`() = run {
        val id = repository.suggest("/ws", "Migrate to SQLite").getOrThrow()

        // Can't skip stages.
        assertTrue(repository.transition(id, RecommendationStatus.IN_PROGRESS).isFailure)
        assertTrue(repository.transition(id, RecommendationStatus.VERIFIED).isFailure)
        assertTrue(repository.transition(id, RecommendationStatus.COMPLETED).isFailure)

        assertTrue(repository.transition(id, RecommendationStatus.ACCEPTED).isSuccess)
        assertTrue(repository.transition(id, RecommendationStatus.IN_PROGRESS).isSuccess)
        assertTrue(repository.transition(id, RecommendationStatus.VERIFIED).isSuccess)
        assertTrue(repository.transition(id, RecommendationStatus.COMPLETED).isSuccess)

        // Completed is terminal history.
        assertTrue(repository.transition(id, RecommendationStatus.ARCHIVED).isFailure)
        assertEquals(RecommendationStatus.COMPLETED, repository.get(id)!!.status)
    }

    @Test
    fun `archive drops from any active stage`() = run {
        val id = repository.suggest("/ws", "Support arm64-v8a").getOrThrow()
        assertTrue(repository.archive(id).isSuccess)
        assertEquals(RecommendationStatus.ARCHIVED, repository.get(id)!!.status)
        assertTrue(repository.list(workspacePath = "/ws", statuses = Recommendation.ACTIVE_STATUSES).isEmpty())
    }

    @Test
    fun `verify requires the verification rule to match the evidence`() = run {
        val id = repository.suggest(
            "/ws",
            "Support arm64-v8a",
            verificationRule = "arm64-v8a, build successful"
        ).getOrThrow()

        // Verification is only possible after acceptance.
        assertTrue(repository.verify(id, "arm64-v8a is in the manifest; build successful").isFailure)

        assertTrue(repository.transition(id, RecommendationStatus.ACCEPTED).isSuccess)

        // Partial evidence does not satisfy the rule.
        assertTrue(repository.verify(id, "arm64-v8a added to ndk abiFilters").isFailure)
        // Full evidence satisfies the rule and appends provenance.
        val verified = repository.verify(id, "arm64-v8a added; build successful (assembleDebug)").getOrThrow()
        assertEquals(RecommendationStatus.VERIFIED, verified.status)
        assertEquals(1, verified.evidence.size)
        assertEquals(verified.id, repository.get(id)!!.id)
    }

    @Test
    fun `blank rule accepts any non-blank evidence`() = run {
        val id = repository.suggest("/ws", "Resolve: widget flickers").getOrThrow()
        assertTrue(repository.transition(id, RecommendationStatus.IN_PROGRESS).isFailure)
        assertTrue(repository.transition(id, RecommendationStatus.ACCEPTED).isSuccess)
        assertTrue(repository.transition(id, RecommendationStatus.IN_PROGRESS).isSuccess)
        val verified = repository.verify(id, "Reproduced and fixed the flicker").getOrThrow()
        assertEquals(RecommendationStatus.VERIFIED, verified.status)
        assertTrue(repository.verify(id, "   ").isFailure)
    }

    @Test
    fun `completed is a user claim while verified is evidence-gated`() = run {
        val userClaimed = repository.suggest("/ws", "Add dark mode toggle").getOrThrow()
        assertTrue(repository.transition(userClaimed, RecommendationStatus.ACCEPTED).isSuccess)
        val completed = repository.transition(userClaimed, RecommendationStatus.COMPLETED).getOrThrow()
        assertEquals(RecommendationStatus.COMPLETED, completed.status)
        assertFalse(repository.get(userClaimed)!!.evidence.isNotEmpty())
    }

    @Test
    fun `renderForContext lists only active recommendations`() = run {
        val active = repository.suggest("/ws", "Support arm64-v8a", rationale = "Release target").getOrThrow()
        val done = repository.suggest("/ws", "Clean up dead code").getOrThrow()
        assertTrue(repository.transition(done, RecommendationStatus.COMPLETED).isSuccess)

        val rendered = repository.renderForContext("/ws")
        assertTrue(rendered.contains("Support arm64-v8a"))
        assertTrue(rendered.contains("Release target"))
        assertFalse(rendered.contains("Clean up dead code"))

        // Completed/archived recommendations stop appearing.
        assertTrue(repository.archive(active).isSuccess)
        assertTrue(repository.renderForContext("/ws").isBlank())
    }

    @Test
    fun `suggest is persisted across repository instances`() = run {
        repository.suggest("/ws", "Persist across restarts").getOrThrow()
        val reopened = FileRecommendationRepository(testContext)
        val record = reopened.list(workspacePath = "/ws").single()
        assertEquals("Persist across restarts", record.title)
        assertTrue(File(root, "memory/recommendations.jsonl").exists())
    }
}
