package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.ToolCategory
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolInfoIcon
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.ToolUiMetadata

/**
 * `read_file` with a `paths[]` array is one tool call carrying many files. The timeline
 * showed it as a single "Read files" card holding a `=== file ===` blob, so five files
 * looked like one action and their individual outcomes were buried in text.
 *
 * This splits that one execution into one card per file — same treatment
 * [synthesizeBrowserGroup] gives a batched browser step — and hands them to
 * [ToolExecutionGroupCard], which renders a lone file flush and grows the "Read N files"
 * wrapper in as soon as there are two.
 *
 * Children never carry file content: a read card has no expandable body, and the content
 * belongs to the model's context, not to the timeline. Only a failure note travels with
 * the child, so a file that was blocked or missing still says so.
 */
internal fun synthesizeBatchReadGroup(parent: ToolExecution, stepIndex: Int): ToolExecutionGroup? {
    if (parent.name != "read_file") return null
    if (!parent.metadata["source"].equals("local", ignoreCase = true)) return null

    // An approval prompt has to stay on the parent card — that is where Approve and
    // Decline live, and the children have no callbacks of their own.
    if (parent.metadata["approvalRequired"].equals("true", ignoreCase = true) ||
        parent.metadata["approvalState"].equals("pending", ignoreCase = true)
    ) return null

    val paths = (parent.arguments["paths"] as? List<*>)
        ?.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    val sections = parseBatchReadSections(parent.result)

    // A batch that failed as a whole — denied, or over the file cap — never reached the
    // per-file stage. One card carrying the reason beats N empty ones.
    if (parent.status == ToolStatus.ERROR && sections.isEmpty()) return null

    val children = paths.mapIndexed { index, path ->
        val section = sections[index + 1]
        val status = when {
            parent.status == ToolStatus.PENDING || parent.status == ToolStatus.RUNNING -> parent.status
            section == null -> parent.status
            section.failed -> ToolStatus.ERROR
            else -> ToolStatus.SUCCESS
        }
        ToolExecution(
            toolCallId = "${parent.toolCallId}#read$index",
            name = "read_file",
            arguments = mapOf("path" to path),
            result = section?.note?.takeIf { section.failed },
            status = status,
            metadata = parent.metadata + ("source" to "local") + ("syntheticReadFile" to "true"),
            uiMetadata = ToolUiMetadata(
                category = ToolCategory.FILE_IO,
                label = path.replace('\\', '/').substringAfterLast('/'),
                actionIcon = ToolInfoIcon.READ,
                targetIcon = ToolInfoIcon.FILE,
                badges = listOf("READ")
            )
        )
    }

    return ToolExecutionGroup(
        key = "read_file",
        startIndex = stepIndex,
        endIndex = stepIndex,
        executions = children,
        isActive = children.any { it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING },
        parentToolCallId = parent.toolCallId
    )
}

internal class BatchReadSection(val path: String, val note: String, val failed: Boolean)

/**
 * Reads back the section headers `ReadFileTool` writes in batch mode, keyed by their
 * 1-based position:
 *
 *     === [2/3] app/src/main/Foo.kt — 312 lines ===
 *
 * Only the header line is matched — the index prefix and the trailing note keep it from
 * colliding with file content or with the `=== Sheet 1 ===` markers the spreadsheet
 * extractor emits. A result that predates this format simply yields no sections, and the
 * children fall back to the parent's status.
 */
internal fun parseBatchReadSections(result: String?): Map<Int, BatchReadSection> {
    if (result.isNullOrBlank()) return emptyMap()
    val sections = mutableMapOf<Int, BatchReadSection>()
    result.lineSequence().forEach { line ->
        val match = BATCH_SECTION_HEADER.matchEntire(line.trim()) ?: return@forEach
        val index = match.groupValues[1].toIntOrNull() ?: return@forEach
        val note = match.groupValues[3]
        sections[index] = BatchReadSection(
            path = match.groupValues[2],
            note = note,
            failed = note.startsWith("ERROR") || note.startsWith("BLOCKED")
        )
    }
    return sections
}

private val BATCH_SECTION_HEADER = Regex("""^=== \[(\d+)/\d+] (.+?) — (.+) ===$""")
