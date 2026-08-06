package com.amaya.intelligence.domain.skills

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillPatchApplier @Inject constructor() {
    fun applyPatch(existingMarkdown: String, patch: String): String {
        val cleanPatch = patch.trim()
        if (cleanPatch.isBlank()) return existingMarkdown.trimEnd() + "\n"

        val frontMatter = extractFrontMatter(existingMarkdown)
        val body = if (frontMatter != null) existingMarkdown.removePrefix(frontMatter).trimStart() else existingMarkdown
        val patchLines = cleanPatch.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val existingLines = body.lines().map { it.trim() }.toSet()
        val uniquePatch = patchLines.filterNot { it.trim() in existingLines }
        if (uniquePatch.isEmpty()) return existingMarkdown.trimEnd() + "\n"

        val targetHeading = findMatchingHeading(body, cleanPatch) ?: if (isUserCorrection(cleanPatch)) "# User Corrections" else "# Notes / Updates"
        var updatedBody = appendToSection(body, targetHeading, uniquePatch.joinToString("\n"))
        updatedBody = appendToSection(
            updatedBody,
            "# Change Log",
            "- ${LocalDate.now()}: ${uniquePatch.first().trimStart('-', '*').trim().take(160)}"
        )
        return ((frontMatter ?: "") + updatedBody.trimEnd() + "\n")
    }

    private fun extractFrontMatter(markdown: String): String? {
        if (!markdown.trimStart().startsWith("---")) return null
        val start = markdown.indexOf("---")
        val end = markdown.indexOf("---", startIndex = start + 3)
        if (end <= start) return null
        return markdown.substring(0, end + 3) + "\n"
    }

    private fun findMatchingHeading(body: String, patch: String): String? {
        val lowerPatch = patch.lowercase()
        val headings = Regex("(?m)^#{1,3}\\s+.+$").findAll(body).map { it.value.trim() }.toList()
        return headings.firstOrNull { heading ->
            val words = heading.removePrefix("#").trim().lowercase().split(Regex("\\s+")).filter { it.length > 3 }
            words.any { it in lowerPatch }
        }
    }

    private fun appendToSection(body: String, heading: String, content: String): String {
        val lines = body.lines().toMutableList()
        val sectionIndex = lines.indexOfFirst { it.trim().equals(heading, ignoreCase = true) }
        if (sectionIndex < 0) {
            return body.trimEnd() + "\n\n$heading\n\n$content\n"
        }
        var insertAt = lines.size
        for (i in sectionIndex + 1 until lines.size) {
            if (lines[i].startsWith("#")) {
                insertAt = i
                break
            }
        }
        val existing = lines.joinToString("\n")
        val unique = content.lines().filterNot { line -> existing.lines().any { it.trim() == line.trim() } }
        if (unique.isEmpty()) return body.trimEnd() + "\n"
        lines.addAll(insertAt, listOf("") + unique)
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun isUserCorrection(patch: String): Boolean {
        val lower = patch.lowercase()
        return listOf("correction", "correct", "bukan", "should be", "harusnya", "instead").any { it in lower }
    }
}
