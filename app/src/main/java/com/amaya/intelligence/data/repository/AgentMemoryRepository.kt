package com.amaya.intelligence.data.repository

import android.content.Context
import com.amaya.intelligence.domain.memory.MemoryAction
import com.amaya.intelligence.domain.memory.MemoryClassifier
import com.amaya.intelligence.domain.memory.MemoryScope
import com.amaya.intelligence.domain.memory.MemoryType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentMemoryRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val classifier: MemoryClassifier
) {
    private val root = File(context.filesDir, "memory/agents").apply { mkdirs() }
    private val lock = Any()

    suspend fun list(agentId: Long, query: String? = null, limit: Int = 20): List<AgentMemoryRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            active(agentId).filter { record ->
                query.isNullOrBlank() || record.content.contains(query, true) || record.title.contains(query, true)
            }.sortedByDescending(AgentMemoryRecord::updatedAt).take(limit.coerceIn(1, 100))
        }
    }

    suspend fun save(agentId: Long, title: String?, content: String): Result<AgentMemoryRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) { runCatching {
            val proposal = validate(agentId, title, content)
            val now = System.currentTimeMillis()
            AgentMemoryRecord(UUID.randomUUID().toString(), proposal.title, proposal.content, 1, now, now)
                .also { append(agentId, it, active = true) }
        } }
    }

    suspend fun update(agentId: Long, id: String, content: String, expectedVersion: Int): Result<AgentMemoryRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) { runCatching {
            val current = active(agentId).firstOrNull { it.id == id } ?: error("Agent memory not found: $id")
            require(current.version == expectedVersion) { "Agent memory conflict: expected version $expectedVersion, current version is ${current.version}." }
            val proposal = validate(agentId, current.title, content)
            rewrite(agentId, read(agentId).map { if (it.id == id && it.active) it.copy(active = false) else it })
            current.copy(title = proposal.title, content = proposal.content, version = current.version + 1, updatedAt = System.currentTimeMillis())
                .also { append(agentId, it, active = true) }
        } }
    }

    suspend fun delete(agentId: Long, id: String, expectedVersion: Int): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(lock) { runCatching {
            val current = active(agentId).firstOrNull { it.id == id } ?: error("Agent memory not found: $id")
            require(current.version == expectedVersion) { "Agent memory conflict: refresh before deleting." }
            rewrite(agentId, read(agentId).map { if (it.id == id && it.active) it.copy(active = false) else it })
        } }
    }

    suspend fun deleteOwner(agentId: Long) = withContext(Dispatchers.IO) { File(root, "$agentId.jsonl").delete() }

    private fun validate(agentId: Long, title: String?, content: String) = classifier.classify(
        content = content,
        requestedType = MemoryType.WORKSPACE_FACT,
        requestedAction = MemoryAction.ADD,
        requestedScope = MemoryScope.WORKSPACE,
        requestedTitle = title,
        reason = "Saved explicitly to private Agent Memory.",
        confidence = 0.95,
        workspacePath = "agent:$agentId"
    ).also { require(it.action != MemoryAction.IGNORE) { it.reason } }

    private fun active(agentId: Long): List<AgentMemoryRecord> = read(agentId).groupBy(Stored::id).mapNotNull { (_, revisions) ->
        revisions.maxWithOrNull(compareBy<Stored> { it.version }.thenBy { it.updatedAt })?.takeIf(Stored::active)?.record
    }

    private fun file(agentId: Long) = File(root, "$agentId.jsonl")
    private fun read(agentId: Long): List<Stored> = file(agentId).takeIf(File::isFile)?.readLines().orEmpty().mapNotNull { line ->
        runCatching { JSONObject(line).toStored() }.getOrNull()
    }
    private fun append(agentId: Long, record: AgentMemoryRecord, active: Boolean) {
        file(agentId).appendText(record.toJson(active).toString() + "\n")
    }
    private fun rewrite(agentId: Long, records: List<Stored>) {
        val target = file(agentId)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(records.joinToString("\n") { it.record.toJson(it.active).toString() })
        val previous = File(target.parentFile, target.name + ".previous")
        if (target.exists() && !target.renameTo(previous)) error("Could not prepare agent memory update")
        if (!tmp.renameTo(target)) {
            previous.renameTo(target)
            error("Could not commit agent memory update")
        }
        previous.delete()
    }

    private data class Stored(val record: AgentMemoryRecord, val active: Boolean) {
        val id get() = record.id
        val version get() = record.version
        val updatedAt get() = record.updatedAt
    }
    private fun AgentMemoryRecord.toJson(active: Boolean) = JSONObject()
        .put("id", id).put("title", title).put("content", content).put("version", version)
        .put("createdAt", createdAt).put("updatedAt", updatedAt).put("active", active)
    private fun JSONObject.toStored() = Stored(
        AgentMemoryRecord(getString("id"), optString("title", "Agent memory"), getString("content"), optInt("version", 1), optLong("createdAt"), optLong("updatedAt")),
        optBoolean("active", true)
    )
}

data class AgentMemoryRecord(
    val id: String,
    val title: String,
    val content: String,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long
)
