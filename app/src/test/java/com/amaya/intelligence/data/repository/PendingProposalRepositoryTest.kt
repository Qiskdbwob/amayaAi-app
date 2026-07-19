package com.amaya.intelligence.data.repository

import android.content.ContextWrapper
import com.amaya.intelligence.data.local.files.FileSkillStore
import com.amaya.intelligence.domain.memory.MemoryClassifier
import com.amaya.intelligence.domain.memory.MemoryContentNormalizer
import com.amaya.intelligence.domain.memory.MemoryDeduper
import com.amaya.intelligence.domain.memory.MemorySafetyFilter
import com.amaya.intelligence.domain.memory.PendingProposal
import com.amaya.intelligence.domain.memory.PendingProposalAction
import com.amaya.intelligence.domain.memory.PendingProposalStatus
import com.amaya.intelligence.domain.memory.PendingProposalType
import com.amaya.intelligence.domain.skills.SkillPatchApplier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PendingProposalRepositoryTest {
    private val root = Files.createTempDirectory("pending-proposal-").toFile()
    private val context = object : ContextWrapper(null) { override fun getFilesDir(): File = root }
    private val classifier = MemoryClassifier(MemorySafetyFilter(), MemoryContentNormalizer())
    private val memory = FileMemoryRepository(context, classifier, MemoryDeduper(), com.amaya.intelligence.data.local.files.FileWorkspaceMemoryStore(context))
    private val skills = FileSkillRepository(FileSkillStore(context), classifier, SkillPatchApplier())
    private val pending = FilePendingProposalRepository(context, memory, skills, classifier)

    @After fun cleanUp() { root.deleteRecursively() }

    @Test
    fun `pending proposal cannot write saved memory`() = runBlocking {
        val proposal = PendingProposal(
            id = "proposal",
            sourceSessionId = "session",
            type = PendingProposalType.USER_PROFILE,
            target = "records.jsonl#user",
            action = PendingProposalAction.ADD,
            title = "Response language",
            content = "The user prefers Indonesian responses.",
            reason = "Hidden chat extraction",
            confidence = 0.9,
            createdAt = 1L,
            status = PendingProposalStatus.PENDING
        )
        assertTrue(pending.addProposal(proposal).isFailure)
        assertTrue(memory.listMemoryRecords().isEmpty())
    }
}
