package com.amaya.intelligence.data.repository

import android.content.Context
import com.amaya.intelligence.domain.memory.MemoryAction
import com.amaya.intelligence.domain.memory.MemoryClassifier
import com.amaya.intelligence.domain.memory.MemoryCompactor
import com.amaya.intelligence.domain.memory.MemoryDeduper
import com.amaya.intelligence.domain.memory.MemoryProposal
import com.amaya.intelligence.domain.memory.MemoryScope
import com.amaya.intelligence.domain.memory.MemoryType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileMemoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: MemoryClassifier,
    private val deduper: MemoryDeduper,
    private val compactor: MemoryCompactor
) : MemoryRepository {
    private val fileLock = Any()

    private val memoryDir: File
        get() = File(context.filesDir, "memory").also { it.mkdirs() }

    private val legacyPersonaDir: File
        get() = File(context.filesDir, "persona")

    override suspend fun applyProposal(proposal: MemoryProposal): Result<String> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            runCatching {
            migrateLegacyFilesIfNeeded()
            if (proposal.action == MemoryAction.IGNORE) return@runCatching "Ignored: ${proposal.reason}"
            require(proposal.confidence >= 0.55) { "Memory confidence is too low." }
            require(!classifier.containsSecret(proposal.content)) { "Rejected because content appears to contain a secret." }
            when (proposal.type) {
                MemoryType.USER_PROFILE -> applyToMemoryFile(userProfileFile, "Learned Preferences", proposal, "User Profile")
                MemoryType.LONG_TERM_MEMORY -> applyToMemoryFile(hotMemoryFile, "Important Facts", proposal, "Important Memory")
                MemoryType.WORKSPACE_FACT -> applyToMemoryFile(workspaceFactsFile, "Workspace Facts", proposal, "Project Memory")
                MemoryType.DAILY_LOG -> {
                    appendDailyLogInternal(proposal.content)
                    appendMemoryIndex(proposal, dailyLogTarget(), "daily_log")
                    "Wrote daily note."
                }
                MemoryType.SKILL_CANDIDATE -> "Skipped: skills are managed by Skills, not Memory."
                MemoryType.REMINDER -> "Skipped: reminders must be created with create_reminder, not memory."
            }
            }
        }
    }

    override suspend fun readUserProfile(): String = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            migrateLegacyFilesIfNeeded()
            userProfileFile.readOrDefault(DEFAULT_USER_PROFILE_MD)
        }
    }

    override suspend fun readHotMemory(): String = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            migrateLegacyFilesIfNeeded()
            compactor.compactHotMemory(hotMemoryFile.readOrDefault(DEFAULT_HOT_MEMORY_MD))
        }
    }

    override suspend fun readWorkspaceFacts(): String = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            migrateLegacyFilesIfNeeded()
            workspaceFactsFile.readOrDefault(DEFAULT_WORKSPACE_FACTS_MD)
        }
    }

    override suspend fun readRecentDailyNotes(limit: Int): String = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            val logs = dailyLogFiles().take(limit.coerceIn(1, 14))
            if (logs.isEmpty()) {
                "No recent daily notes."
            } else {
                logs.joinToString("\n\n") { file -> sanitizeDailyNoteText(file.readText()).take(1_500).trim() }
                    .ifBlank { "No recent daily notes." }
            }
        }
    }

    override suspend fun appendDailyLog(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            runCatching { appendDailyLogInternal(content) }
        }
    }

    override suspend fun compactStoredMemory(): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            runCatching {
            migrateLegacyFilesIfNeeded()
            atomicWrite(hotMemoryFile, compactor.compactHotMemory(hotMemoryFile.readOrDefault(DEFAULT_HOT_MEMORY_MD)))
            atomicWrite(userProfileFile, compactor.compactHotMemory(userProfileFile.readOrDefault(DEFAULT_USER_PROFILE_MD)))
            atomicWrite(workspaceFactsFile, compactor.compactHotMemory(workspaceFactsFile.readOrDefault(DEFAULT_WORKSPACE_FACTS_MD)))
            ensureMemoryIndex()
            }
        }
    }

    override suspend fun listMemoryRecords(type: MemoryType?, query: String?, limit: Int): List<MemoryRecord> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            migrateLegacyFilesIfNeeded()
            ensureMemoryIndex()
            val records = activeMemoryRecords()
                .filter { type == null || it.type == type }
            val filtered = if (query.isNullOrBlank()) {
                records.sortedWith(compareByDescending<MemoryRecord> { it.importance }.thenByDescending { it.updatedAt })
            } else {
                records.map { it to scoreMemoryRecord(it, query) }
                    .filter { it.second > 0.0 }
                    .sortedByDescending { it.second }
                    .map { it.first }
            }
            filtered.take(limit.coerceIn(1, 200))
        }
    }

    override suspend fun removeMemoryById(id: String): Result<String> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            runCatching {
            ensureMemoryIndex()
            val record = activeMemoryRecords().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Memory not found: $id")
            val file = fileForTarget(record.target)
            val current = if (record.type == MemoryType.DAILY_LOG) file.readTextIfExists() else file.readOrDefault(defaultContentFor(file))
            val updated = if (record.type == MemoryType.DAILY_LOG) removeDailyLogBlock(current, record.content, record.label) else removeBullet(current, record.content, record.label)
            atomicWrite(file, updated)
            appendMemoryRecord(record.copy(action = MemoryAction.REMOVE, reason = "Removed by memory_manage.", updatedAt = System.currentTimeMillis()))
            "Removed memory ${record.id}. This affects the next chat."
            }
        }
    }

    override suspend fun updateMemoryById(id: String, content: String): Result<String> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            runCatching {
            ensureMemoryIndex()
            require(content.isNotBlank()) { "Content is required." }
            require(!classifier.containsSecret(content)) { "Rejected because content appears to contain a secret." }
            val record = activeMemoryRecords().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Memory not found: $id")
            if (record.type == MemoryType.DAILY_LOG) throw IllegalArgumentException("Daily logs cannot be updated by memory id.")
            val file = fileForTarget(record.target)
            val current = file.readOrDefault(defaultContentFor(file))
            atomicWrite(file, replaceBullet(current, "${record.content} => $content", record.label))
            appendMemoryRecord(record.copy(action = MemoryAction.REPLACE, title = inferTitle(content.trim(), record.type), content = content.trim(), reason = "Updated by memory_manage.", updatedAt = System.currentTimeMillis()))
            "Updated memory ${record.id}. This affects the next chat."
            }
        }
    }

    private val userProfileFile: File get() = File(memoryDir, "USER.md")
    private val hotMemoryFile: File get() = File(memoryDir, "MEMORY.md")
    private val workspaceFactsFile: File get() = File(memoryDir, "PROJECT.md")
    private val memoryIndexFile: File get() = File(memoryDir, "index.jsonl")

    private fun appendToMemoryFile(file: File, defaultContent: String, section: String, content: String): String {
        val current = file.readOrDefault(defaultContent)
        if (deduper.isDuplicate(content, current)) return current
        val sectionHeader = "## $section"
        val entry = "- $content"
        return if (current.contains(sectionHeader)) {
            current.replace(sectionHeader, "$sectionHeader\n$entry")
        } else {
            current.trimEnd() + "\n\n$sectionHeader\n$entry\n"
        }
    }

    private fun applyToMemoryFile(file: File, section: String, proposal: MemoryProposal, label: String): String {
        val defaultContent = defaultContentFor(file)
        val current = file.readOrDefault(defaultContent)
        val updated = when (proposal.action) {
            MemoryAction.ADD -> appendOrReplaceConflictingMemory(file, defaultContent, section, proposal)
            MemoryAction.REPLACE -> replaceBullet(current, proposal.content, label)
            MemoryAction.REMOVE -> removeBullet(current, proposal.content, label)
            MemoryAction.IGNORE -> return "Ignored: ${proposal.reason}"
        }
        if (updated == current && proposal.action == MemoryAction.ADD) {
            return "Skipped duplicate $label."
        }
        atomicWrite(file, updated)
        appendMemoryIndex(proposal, file.name, label)
        return when (proposal.action) {
            MemoryAction.ADD -> "Saved to $label."
            MemoryAction.REPLACE -> "Replaced matching $label."
            MemoryAction.REMOVE -> "Removed matching $label."
            MemoryAction.IGNORE -> "Ignored: ${proposal.reason}"
        }
    }

    private fun appendOrReplaceConflictingMemory(file: File, defaultContent: String, section: String, proposal: MemoryProposal): String {
        val current = file.readOrDefault(defaultContent)
        val conflictKey = conflictKey(proposal.content) ?: return appendToMemoryFile(file, defaultContent, section, proposal.content)
        val lines = current.lines().toMutableList()
        val conflictIndex = lines.indexOfFirst { line ->
            val trimmed = line.trimStart()
            (trimmed.startsWith("-") || trimmed.startsWith("*")) && conflictKey(trimmed.trimStart('-', '*').trim()) == conflictKey
        }
        return if (conflictIndex >= 0) {
            lines[conflictIndex] = preserveBulletPrefix(lines[conflictIndex], proposal.content)
            lines.joinToString("\n").trimEnd() + "\n"
        } else appendToMemoryFile(file, defaultContent, section, proposal.content)
    }

    private fun conflictKey(content: String): String? {
        val lower = content.lowercase()
        return when {
            listOf("call me", "panggil", "nama saya", "my name", "nickname").any { it in lower } -> "user_nickname"
            listOf("bahasa", "language", "respond in", "jawab saya").any { it in lower } -> "response_language"
            listOf("concise", "ringkas", "detail", "verbose", "panjang", "singkat").any { it in lower } -> "response_detail"
            else -> null
        }
    }

    private fun replaceBullet(current: String, content: String, label: String): String {
        val (query, replacement) = parseReplacement(content)
        val lines = current.lines().toMutableList()
        val index = findMatchingBulletIndex(lines, query)
        require(index >= 0) { "No matching item found in $label for replace." }
        lines[index] = preserveBulletPrefix(lines[index], replacement)
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun removeBullet(current: String, content: String, label: String): String {
        val lines = current.lines().toMutableList()
        val index = findMatchingBulletIndex(lines, content)
        require(index >= 0) { "No matching item found in $label for remove." }
        lines.removeAt(index)
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun parseReplacement(content: String): Pair<String, String> {
        listOf("=>", "->", "→").forEach { delimiter ->
            val index = content.indexOf(delimiter)
            if (index > 0 && index < content.lastIndex) {
                val oldValue = content.substring(0, index).trim().trimStart('-', '*').trim()
                val newValue = content.substring(index + delimiter.length).trim().trimStart('-', '*').trim()
                if (oldValue.isNotBlank() && newValue.isNotBlank()) return oldValue to newValue
            }
        }
        return content to content
    }

    private fun findMatchingBulletIndex(lines: List<String>, query: String): Int {
        return lines.indexOfFirst { line ->
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("-") && !trimmed.startsWith("*")) return@indexOfFirst false
            deduper.isDuplicate(query, trimmed.trimStart('-', '*').trim())
        }
    }

    private fun preserveBulletPrefix(existingLine: String, replacement: String): String {
        val prefix = existingLine.takeWhile { it.isWhitespace() } + existingLine.trimStart().take(1)
        return "$prefix ${replacement.trim().trimStart('-', '*').trim()}"
    }

    private fun appendMemoryIndex(proposal: MemoryProposal, target: String, label: String) {
        appendMemoryRecord(
            MemoryRecord(
                id = proposal.id,
                type = proposal.type,
                action = proposal.action,
                scope = proposal.scope,
                target = target,
                label = label,
                title = proposal.title,
                content = proposal.content,
                reason = proposal.reason,
                confidence = proposal.confidence,
                importance = proposal.importance,
                createdAt = proposal.createdAt,
                updatedAt = proposal.createdAt,
                expiresAt = proposal.expiresAt
            )
        )
    }

    private fun appendMemoryRecord(record: MemoryRecord) {
        memoryIndexFile.parentFile?.mkdirs()
        memoryIndexFile.appendText(record.toJson().toString() + "\n")
    }

    private fun activeMemoryRecords(): List<MemoryRecord> {
        val latestById = readMemoryIndex().associateBy { it.id }
        val active = latestById.values
            .filter { it.action != MemoryAction.REMOVE && it.action != MemoryAction.IGNORE }
            .filter { it.expiresAt == null || it.expiresAt > System.currentTimeMillis() }
            .filterNot { it.type == MemoryType.DAILY_LOG && isGenericDailyNoteContent(it.content) }
            .filter { it.type == MemoryType.DAILY_LOG || recordExistsInMarkdown(it) }
            .sortedBy { it.updatedAt }
        val collapsed = linkedMapOf<String, MemoryRecord>()
        active.forEach { record ->
            val key = conflictKey(record.content)?.let { "${record.type.name}:${record.scope.name}:$it" } ?: record.id
            collapsed[key] = record
        }
        return collapsed.values.toList()
    }

    private fun readMemoryIndex(): List<MemoryRecord> = runCatching {
        if (!memoryIndexFile.exists()) return emptyList()
        memoryIndexFile.readLines().mapNotNull { line -> runCatching { JSONObject(line).toMemoryRecord() }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun ensureMemoryIndex() {
        val existingKeys = readMemoryIndex()
            .filter { it.action != MemoryAction.REMOVE && it.action != MemoryAction.IGNORE }
            .map { memoryIdentityKey(it.target, it.content) }
            .toMutableSet()
        buildRecordsFromMarkdown(userProfileFile, MemoryType.USER_PROFILE, MemoryScope.USER, "User Profile")
            .plus(buildRecordsFromMarkdown(hotMemoryFile, MemoryType.LONG_TERM_MEMORY, MemoryScope.GLOBAL, "Important Memory"))
            .plus(buildRecordsFromMarkdown(workspaceFactsFile, MemoryType.WORKSPACE_FACT, MemoryScope.WORKSPACE, "Project Memory"))
            .plus(buildRecordsFromDailyLogs())
            .filter { existingKeys.add(memoryIdentityKey(it.target, it.content)) }
            .forEach { appendMemoryRecord(it) }
    }

    private fun recordExistsInMarkdown(record: MemoryRecord): Boolean {
        val file = fileForTarget(record.target)
        if (!file.exists()) return false
        return file.readText().lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("*") }
            .map { it.trimStart('-', '*').trim() }
            .any { existing -> deduper.isDuplicate(record.content, existing) }
    }

    private fun buildRecordsFromDailyLogs(): List<MemoryRecord> = dailyLogFiles().flatMap { file ->
        parseDailyLogBlocks(file).map { (timestampLabel, content) ->
            val now = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            MemoryRecord(
                id = stableMemoryId(file.name, "$timestampLabel:$content"),
                type = MemoryType.DAILY_LOG,
                action = MemoryAction.ADD,
                scope = MemoryScope.SESSION,
                target = file.name,
                label = timestampLabel,
                title = inferTitle(content, MemoryType.DAILY_LOG),
                content = content,
                reason = "Backfilled from daily note.",
                confidence = 0.75,
                importance = 0.4,
                createdAt = now,
                updatedAt = now,
                source = "daily-backfill"
            )
        }
    }

    private fun buildRecordsFromMarkdown(file: File, type: MemoryType, scope: MemoryScope, label: String): List<MemoryRecord> {
        val defaultContent = defaultContentFor(file)
        val text = file.readOrDefault(defaultContent)
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("*") }
            .map { it.trimStart('-', '*').trim() }
            .filter { it.isNotBlank() }
            .map { content ->
                val now = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                MemoryRecord(
                    id = stableMemoryId(file.name, content),
                    type = type,
                    action = MemoryAction.ADD,
                    scope = scope,
                    target = file.name,
                    label = label,
                    title = inferTitle(content, type),
                    content = content,
                    reason = "Backfilled from markdown memory.",
                    confidence = 0.8,
                    importance = if (type == MemoryType.USER_PROFILE) 0.7 else 0.6,
                    createdAt = now,
                    updatedAt = now,
                    source = "markdown-backfill"
                )
            }.toList()
    }

    private fun stableMemoryId(target: String, content: String): String = "mem_" + UUID.nameUUIDFromBytes("$target:$content".toByteArray()).toString()

    private fun inferTitle(content: String, type: MemoryType): String {
        val lower = content.lowercase()
        return when {
            type == MemoryType.DAILY_LOG && "language" in lower -> "Communication preference update"
            type == MemoryType.DAILY_LOG && "memory" in lower -> "Memory activity"
            type == MemoryType.DAILY_LOG && ("browser" in lower || "search" in lower) -> "Browser task"
            type == MemoryType.DAILY_LOG -> "Daily summary"
            "user's name" in lower -> "User name"
            "prefers" in lower && "responses" in lower -> "Response language preference"
            "works at" in lower -> "Workplace context"
            type == MemoryType.USER_PROFILE -> "User profile"
            type == MemoryType.WORKSPACE_FACT -> "Workspace fact"
            else -> content.removeSuffix(".").take(80).ifBlank { "Memory" }
        }
    }

    private fun memoryIdentityKey(target: String, content: String): String = "$target:${normalizeMemoryText(content)}"

    private fun normalizeMemoryText(content: String): String = content
        .lowercase()
        .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun MemoryRecord.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("action", action.name)
        .put("scope", scope.name)
        .put("target", target)
        .put("label", label)
        .put("title", title)
        .put("content", content)
        .put("reason", reason)
        .put("confidence", confidence)
        .put("importance", importance)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("expiresAt", expiresAt)
        .put("source", source)

    private fun JSONObject.toMemoryRecord(): MemoryRecord = MemoryRecord(
        id = optString("id"),
        type = runCatching { MemoryType.valueOf(optString("type")) }.getOrDefault(MemoryType.LONG_TERM_MEMORY),
        action = runCatching { MemoryAction.valueOf(optString("action")) }.getOrDefault(MemoryAction.ADD),
        scope = runCatching { MemoryScope.valueOf(optString("scope")) }.getOrDefault(MemoryScope.GLOBAL),
        target = optString("target"),
        label = optString("label"),
        title = optString("title").takeIf { it.isNotBlank() } ?: inferTitle(optString("content"), runCatching { MemoryType.valueOf(optString("type")) }.getOrDefault(MemoryType.LONG_TERM_MEMORY)),
        content = optString("content"),
        reason = optString("reason"),
        confidence = optDouble("confidence", 0.0),
        importance = optDouble("importance", 0.0),
        createdAt = optLong("createdAt", 0L),
        updatedAt = optLong("updatedAt", optLong("createdAt", 0L)),
        expiresAt = if (has("expiresAt") && !isNull("expiresAt")) optLong("expiresAt") else null,
        source = optString("source", "index")
    )

    private fun fileForTarget(target: String): File = when (target) {
        "USER.md" -> userProfileFile
        "MEMORY.md" -> hotMemoryFile
        "PROJECT.md" -> workspaceFactsFile
        else -> File(memoryDir, target)
    }

    private fun parseDailyLogBlocks(file: File): List<Pair<String, String>> {
        if (!file.exists()) return emptyList()
        val blocks = mutableListOf<Pair<String, String>>()
        var currentLabel: String? = null
        val current = StringBuilder()
        fun flush() {
            val label = currentLabel ?: return
            val content = cleanStoredSummary(current.toString())
            if (content.isNotBlank() && !isGenericDailyNoteContent(content)) blocks.add(label to content)
            currentLabel = null
            current.clear()
        }
        file.readLines().forEach { line ->
            val match = Regex("^[-*]\\s*\\[([^]]+)]\\s*(.*)$").find(line.trim())
            if (match != null) {
                flush()
                currentLabel = match.groupValues[1]
                current.append(match.groupValues[2].trim())
            } else if (currentLabel != null && line.isNotBlank() && !line.trimStart().startsWith("#")) {
                current.append(' ').append(line.trim())
            }
        }
        flush()
        return blocks
    }

    private fun removeDailyLogBlock(current: String, content: String, label: String): String {
        val lines = current.lines().toMutableList()
        val start = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            trimmed.startsWith("-") && trimmed.contains("[$label]") && deduper.isDuplicate(content, trimmed)
        }.takeIf { it >= 0 } ?: lines.indexOfFirst { line ->
            val trimmed = line.trim().trimStart('-', '*').trim()
            deduper.isDuplicate(content, trimmed)
        }
        require(start >= 0) { "No matching daily note found." }
        var end = start + 1
        while (end < lines.size) {
            val trimmed = lines[end].trim()
            if (trimmed.startsWith("- [") || trimmed.startsWith("* [") || trimmed.startsWith("## ")) break
            end++
        }
        repeat(end - start) { lines.removeAt(start) }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun sanitizeDailyNoteText(text: String): String = text.lines()
        .filterNot { line -> isGenericDailyNoteContent(line) }
        .joinToString("\n")
        .trim()

    private fun isGenericDailyNoteContent(text: String): Boolean {
        val lower = text.lowercase()
            .replace(Regex("^[-*]\\s*(\\[[^]]+])?\\s*"), "")
            .trim()
            .removeSuffix(".")
        if (lower.isBlank()) return true
        return lower in setOf(
            "user completed a chat task with amaya",
            "user completed a tool-assisted task",
            "user completed a browser/search task",
            "interaction summarized",
            "the session completed successfully",
            "the session focused on a browser or search request from the user",
            "the session focused on a browser or search request",
            "the session handled a request to find and remove saved memory",
            "the session reviewed saved memory and memory-management behavior",
            "the session captured a user profile detail for follow-up memory handling"
        ) || lower.startsWith("user asked/discussed") ||
            lower.startsWith("the session focused on") ||
            lower.startsWith("the session handled") ||
            lower.startsWith("the session reviewed") ||
            lower.startsWith("the session captured") ||
            "tools used:" in lower ||
            "tool-assisted" in lower
    }

    private fun cleanStoredSummary(text: String): String {
        val clean = text
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .replace(Regex("(?is)<think>.*"), "")
            .replace(Regex("(?is)</think>"), "")
            .replace(Regex("(?i)\\btools used:\\s*[^.]+\\.?"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isBlank() || isGenericDailyNoteContent(clean)) return ""
        val outcomePart = Regex("(?i)outcome:\\s*(.*?)(?:\\btools used:|$)").find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        val source = outcomePart?.takeIf { it.isNotBlank() && !isGenericDailyNoteContent(it) } ?: clean
        return source.take(220).trim().trim('.', ';').takeIf { it.isNotBlank() }?.let { "$it." }.orEmpty()
    }

    private fun scoreMemoryRecord(record: MemoryRecord, query: String): Double {
        val terms = expandTerms(query)
        if (terms.isEmpty()) return 0.0
        val haystack = expandTerms(record.content + " " + record.label + " " + record.type.name)
        var score = 0.0
        terms.forEach { term ->
            if (term in haystack) score += 2.0
            else if (haystack.any { it.contains(term) || term.contains(it) }) score += 0.75
        }
        return score + record.importance
    }

    private fun expandTerms(text: String): Set<String> {
        val base = text.lowercase()
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toMutableSet()
        SYNONYMS.forEach { (key, values) ->
            if (key in base || values.any { it in base }) {
                base.add(key)
                base.addAll(values)
            }
        }
        return base
    }

    private fun dailyLogTarget(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md"

    private fun appendDailyLogInternal(text: String) {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val file = File(memoryDir, "$today.md")
        val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        if (!file.exists() || file.readText().isBlank()) {
            atomicWrite(file, "# Daily Notes - $today\n\n## Events\n")
        }
        file.appendText("- [$timestamp] $text\n")
    }

    private fun dailyLogFiles(): List<File> = memoryDir.listFiles()
        ?.filter { it.extension == "md" && Regex("\\d{4}-\\d{2}-\\d{2}\\.md").matches(it.name) }
        ?.sortedByDescending { it.name }
        ?: emptyList()

    private fun migrateLegacyFilesIfNeeded() {
        migrateLegacy("USER.md", userProfileFile)
        migrateLegacy("MEMORY.md", hotMemoryFile)
        migrateLegacy("AGENTS.md", workspaceFactsFile)
    }

    private fun migrateLegacy(name: String, target: File) {
        if (target.exists() && target.readText().isNotBlank()) return
        val legacy = File(legacyPersonaDir, name)
        if (legacy.exists() && legacy.readText().isNotBlank()) {
            atomicWrite(target, legacy.readText())
        }
    }

    private fun File.readOrDefault(defaultContent: String): String {
        if (!exists() || readText().isBlank()) {
            atomicWrite(this, defaultContent)
        }
        return readText()
    }

    private fun File.readTextIfExists(): String = if (exists()) readText() else ""

    private fun defaultContentFor(file: File): String = when (file.name) {
        "USER.md" -> DEFAULT_USER_PROFILE_MD
        "MEMORY.md" -> DEFAULT_HOT_MEMORY_MD
        "PROJECT.md" -> DEFAULT_WORKSPACE_FACTS_MD
        else -> ""
    }

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content.trimEnd() + "\n")
        if (!tmp.renameTo(file)) {
            file.writeText(content.trimEnd() + "\n")
            tmp.delete()
        }
    }

    companion object {
        private val SYNONYMS = mapOf(
            "language" to setOf("bahasa", "jawab", "respond", "reply"),
            "tone" to setOf("gaya", "style", "nada", "cara"),
            "concise" to setOf("ringkas", "singkat", "pendek", "brief"),
            "detail" to setOf("rinci", "lengkap", "panjang", "verbose"),
            "name" to setOf("nama", "panggil", "nickname", "call"),
            "project" to setOf("workspace", "repo", "repository", "codebase", "kode"),
            "memory" to setOf("ingat", "remember", "memori")
        )

        private val DEFAULT_USER_PROFILE_MD = """
            # User Profile

            > Stable user preferences and profile facts only.

            ## Learned Preferences
        """.trimIndent() + "\n"

        private val DEFAULT_HOT_MEMORY_MD = """
            # Important Memory

            > Durable important facts only. Do not store daily events, reminders, credentials, or temporary guesses here.

            ## Important Facts

            ## Ongoing Tasks & Goals
        """.trimIndent() + "\n"

        private val DEFAULT_WORKSPACE_FACTS_MD = """
            # Project Memory

            > Workspace-specific facts, rules, and environment notes.

            ## Workspace Facts
        """.trimIndent() + "\n"
    }
}
