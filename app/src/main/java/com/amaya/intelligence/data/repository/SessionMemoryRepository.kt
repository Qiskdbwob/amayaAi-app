package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.local.files.FileSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SessionMessage(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList()
)

data class SessionToolCall(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val toolName: String,
    val input: String = "",
    val output: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val toolCallId: String = id,
    val argumentsJson: String = input,
    val resultJson: String? = output
)

data class SessionSummary(
    val sessionId: String,
    val summary: String,
    val tags: List<String>,
    val createdAt: Long,
    val updatedAt: Long
)

data class SessionSearchResult(
    val sessionId: String,
    val timestamp: Long,
    val summary: String,
    val matchedText: String,
    val score: Double,
    val tags: List<String>
)

interface SessionMemoryRepository {
    suspend fun saveMessage(message: SessionMessage)
    suspend fun saveToolCall(toolCall: SessionToolCall)
    suspend fun saveSummary(summary: SessionSummary)
    suspend fun searchSessions(query: String, limit: Int = 10): List<SessionSearchResult>
    suspend fun summarizeSession(sessionId: String, forceRebuild: Boolean = false): String
    suspend fun listSessionIds(limit: Int = 50): List<String>
    suspend fun listSessionsNeedingSummary(limit: Int = 50): List<String>
}

@Singleton
class FileSessionMemoryRepository @Inject constructor(
    private val store: FileSessionStore
) : SessionMemoryRepository {
    private val fileMutex = Mutex()
    override suspend fun saveMessage(message: SessionMessage) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            appendRecord(JSONObject()
                .put("kind", "message")
                .put("id", message.id)
                .put("sessionId", message.sessionId)
                .put("role", message.role)
                .put("content", message.content.take(MAX_CONTENT_CHARS))
                .put("timestamp", message.timestamp)
                .put("tags", JSONArray(message.tags))
            )
        }
    }

    override suspend fun saveToolCall(toolCall: SessionToolCall) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            appendRecord(JSONObject()
                .put("kind", "tool_call")
                .put("id", toolCall.id)
                .put("sessionId", toolCall.sessionId)
                .put("toolCallId", toolCall.toolCallId)
                .put("toolName", toolCall.toolName)
                .put("input", toolCall.input.take(MAX_CONTENT_CHARS))
                .put("output", toolCall.output.take(MAX_CONTENT_CHARS))
                .put("argumentsJson", toolCall.argumentsJson.take(MAX_CONTENT_CHARS))
                .put("resultJson", toolCall.resultJson?.take(MAX_CONTENT_CHARS))
                .put("timestamp", toolCall.timestamp)
            )
        }
    }

    override suspend fun saveSummary(summary: SessionSummary) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val summaries = readSummaries().toMutableMap()
            summaries[summary.sessionId] = summary
            writeSummaries(summaries.values.toList())
        }
    }

    override suspend fun searchSessions(query: String, limit: Int): List<SessionSearchResult> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val phrase = query.lowercase().trim()
            val terms = expandTerms(phrase).filter { it.length > 2 }
            if (terms.isEmpty()) return@withLock emptyList()
            val summaries = readSummaries()
            val records = readRecords().groupBy { it.optString("sessionId") }
            val allSessionIds = (records.keys + summaries.keys).filter { it.isNotBlank() }.toSet()
            allSessionIds.mapNotNull { sessionId ->
                val sessionRecords = records[sessionId].orEmpty()
                val summary = summaries[sessionId]
                scoreSession(sessionId, phrase, terms, summary, sessionRecords)
            }.sortedByDescending { it.score }
                .take(limit.coerceIn(1, 25))
        }
    }

    override suspend fun summarizeSession(sessionId: String, forceRebuild: Boolean): String = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (forceRebuild) buildDeterministicSummary(sessionId).summary
            else readSummaries()[sessionId]?.summary ?: buildDeterministicSummary(sessionId).summary
        }
    }

    override suspend fun listSessionIds(limit: Int): List<String> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            sessionGroupsByRecency()
                .map { it.key }
                .take(limit.coerceIn(1, 500))
        }
    }

    override suspend fun listSessionsNeedingSummary(limit: Int): List<String> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val summaries = readSummaries()
            sessionGroupsByRecency()
                .filter { (sessionId, records) ->
                    val latestRecord = records.maxOfOrNull { it.optLong("timestamp") } ?: 0L
                    val summary = summaries[sessionId]
                    summary == null || latestRecord > summary.updatedAt
                }
                .map { it.key }
                .take(limit.coerceIn(1, 500))
        }
    }

    private fun scoreSession(
        sessionId: String,
        phrase: String,
        terms: List<String>,
        summary: SessionSummary?,
        records: List<JSONObject>
    ): SessionSearchResult? {
        var score = 0.0
        val matches = mutableListOf<String>()
        val tags = linkedSetOf<String>()
        summary?.tags?.forEach { tags.add(it) }
        summary?.let {
            val text = it.summary.lowercase()
            if (phrase in text) score += 5.0
            val tagText = it.tags.joinToString(" ").lowercase()
            if (terms.any { term -> term in tagText }) score += 4.0
            val hits = terms.count { term -> term in text }
            if (hits > 0) {
                score += hits * 2.5
                matches.add(it.summary)
            }
        }
        var latest = summary?.updatedAt ?: 0L
        records.forEach { json ->
            latest = maxOf(latest, json.optLong("timestamp"))
            json.optJSONArray("tags")?.let { array -> repeat(array.length()) { idx -> tags.add(array.optString(idx)) } }
            val kind = json.optString("kind")
            val role = json.optString("role")
            val text = searchableText(json)
            val lower = text.lowercase()
            if (phrase in lower) score += 5.0
            val hits = terms.count { it in lower }
            if (hits > 0) {
                score += hits * when {
                    kind == "message" && role == "user" -> 3.0
                    kind == "message" && role == "assistant" -> 2.0
                    kind == "tool_call" && terms.any { it in json.optString("toolName").lowercase() } -> 4.0
                    kind == "tool_call" -> 1.0
                    else -> 1.0
                }
                matches.add(text.take(500))
            }
        }
        if (score <= 0.0) return null
        val recencyBoost = if (latest > 0L) (1.0 / (1.0 + ((System.currentTimeMillis() - latest).coerceAtLeast(0L) / DAY_MS).toDouble())) else 0.0
        score += recencyBoost
        return SessionSearchResult(
            sessionId = sessionId,
            timestamp = latest,
            summary = summary?.summary ?: buildDeterministicSummary(sessionId).summary,
            matchedText = matches.distinct().joinToString("\n").take(900),
            score = score,
            tags = tags.toList()
        )
    }

    private fun appendRecord(json: JSONObject) {
        store.rootDir.mkdirs()
        store.sessionsFile.appendText(json.toString() + "\n")
    }

    private fun sessionGroupsByRecency(): List<Map.Entry<String, List<JSONObject>>> = readRecords()
        .groupBy { it.optString("sessionId") }
        .filterKeys { it.isNotBlank() }
        .entries
        .sortedByDescending { (_, records) -> records.maxOfOrNull { it.optLong("timestamp") } ?: 0L }

    private fun readRecords(): List<JSONObject> = runCatching {
        if (!store.sessionsFile.exists()) return emptyList()
        store.sessionsFile.readLines().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun searchableText(json: JSONObject): String = when (json.optString("kind")) {
        "message" -> "${json.optString("role")}: ${json.optString("content")}" 
        "tool_call" -> "${json.optString("toolName")} ${json.optString("input", json.optString("argumentsJson"))} ${json.optString("output", json.optString("resultJson"))}" 
        else -> json.toString()
    }

    private fun buildDeterministicSummary(sessionId: String): SessionSummary {
        val records = readRecords().filter { it.optString("sessionId") == sessionId }
        if (records.isEmpty()) return SessionSummary(sessionId, "No session records found.", emptyList(), 0L, 0L)
        val user = records.filter { it.optString("kind") == "message" && it.optString("role") == "user" }.takeLast(3).joinToString("; ") { it.optString("content").take(140) }
        val assistant = records.filter { it.optString("kind") == "message" && it.optString("role") == "assistant" }.takeLast(3).joinToString("; ") { it.optString("content").take(140) }
        val tools = records.filter { it.optString("kind") == "tool_call" }.map { it.optString("toolName") }.distinct()
        val latest = records.maxOfOrNull { it.optLong("timestamp") } ?: System.currentTimeMillis()
        val created = records.minOfOrNull { it.optLong("timestamp") } ?: latest
        val summary = buildString {
            if (user.isNotBlank()) append("User discussed $user. ")
            if (assistant.isNotBlank()) append("Assistant concluded $assistant. ")
        }.trim().ifBlank { "Session $sessionId at ${Instant.ofEpochMilli(latest)}." }
        return SessionSummary(sessionId, summary, inferTags(summary + " " + tools.joinToString(" ")), created, latest)
    }

    private fun readSummaries(): Map<String, SessionSummary> = runCatching {
        if (!store.summariesFile.exists()) return emptyMap()
        store.summariesFile.readLines().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                json.optString("sessionId") to SessionSummary(
                    sessionId = json.optString("sessionId"),
                    summary = json.optString("summary"),
                    tags = json.optJSONArray("tags")?.let { array -> List(array.length()) { idx -> array.optString(idx) } } ?: emptyList(),
                    createdAt = json.optLong("createdAt"),
                    updatedAt = json.optLong("updatedAt")
                )
            }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeSummaries(summaries: List<SessionSummary>) {
        store.rootDir.mkdirs()
        val tmp = java.io.File(store.rootDir, store.summariesFile.name + ".tmp")
        tmp.writeText(summaries.joinToString("\n") { summary ->
            JSONObject()
                .put("sessionId", summary.sessionId)
                .put("summary", summary.summary)
                .put("tags", JSONArray(summary.tags))
                .put("createdAt", summary.createdAt)
                .put("updatedAt", summary.updatedAt)
                .toString()
        } + if (summaries.isNotEmpty()) "\n" else "")
        if (!tmp.renameTo(store.summariesFile)) {
            store.summariesFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun expandTerms(text: String): List<String> {
        val terms = text
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toMutableSet()
        SYNONYMS.forEach { (key, values) ->
            if (key in terms || values.any { it in terms }) {
                terms.add(key)
                terms.addAll(values)
            }
        }
        return terms.toList()
    }

    private fun inferTags(text: String): List<String> {
        val lower = text.lowercase()
        return listOf("android", "webview", "oauth", "browser", "skill", "memory", "persona", "reminder", "kotlin", "gradle")
            .filter { it in lower }
            .take(8)
    }

    companion object {
        private val SYNONYMS = mapOf(
            "memory" to setOf("ingat", "remember", "memori"),
            "previous" to setOf("sebelumnya", "kemarin", "tadi", "earlier"),
            "browser" to setOf("webview", "chrome", "situs", "website"),
            "project" to setOf("workspace", "repo", "repository", "codebase", "kode"),
            "skill" to setOf("workflow", "prosedur", "cara", "reuse"),
            "persona" to setOf("tone", "gaya", "karakter")
        )

        private const val MAX_CONTENT_CHARS = 8_000
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
