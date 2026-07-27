package com.amaya.intelligence.ui.components.markdown

import com.amaya.intelligence.util.LocalStreamPerfLog

internal sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class CodeBlock(val lang: String, val code: String) : MdBlock()
    data class UnorderedList(val items: List<ListEntry>) : MdBlock()
    data class OrderedList(val items: List<ListEntry>) : MdBlock()
    data class TaskList(val items: List<TaskEntry>) : MdBlock()
    data class BlockQuote(val text: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    object HorizontalRule : MdBlock()
}

internal data class ListEntry(val text: String, val indent: Int = 0, val number: Int = 1)
internal data class TaskEntry(val text: String, val checked: Boolean, val indent: Int = 0)

// ═══════════════════════════════════════════════════════════════════
//  Regex constants — compiled once
// ═══════════════════════════════════════════════════════════════════

private val HEADING_RE = Regex("^(#{1,6})\\s+(.*)")
private val HR_RE = Regex("^[-*_]{3,}\\s*$")
private val UL_RE = Regex("^(\\s*)[-*+]\\s+(.*)")
// The marker must be a real "1." / "1)" — an earlier `[.)\s]` also accepted a space,
// which turned any paragraph opening with a number ("2024 was a long year") into a list.
private val OL_RE = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)")
private val TASK_RE = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s*(.*)")
private val TABLE_SEP_RE = Regex("^\\|?[\\s:]*-{2,}[\\s:]*([|][\\s:]*-{2,}[\\s:]*)*\\|?\\s*$")
private val BQ_RE = Regex("^>\\s?(.*)")

// ═══════════════════════════════════════════════════════════════════

private const val MARKDOWN_CACHE_BUDGET_CHARS = 512_000
private const val MARKDOWN_CACHE_MAX_ENTRY_CHARS = 64_000

private val markdownBlockCache =
    object : android.util.LruCache<String, List<MdBlock>>(MARKDOWN_CACHE_BUDGET_CHARS) {
        override fun sizeOf(key: String, value: List<MdBlock>): Int = key.length.coerceAtLeast(1)
    }

internal fun parseBlocksCached(text: String, compact: Boolean): List<MdBlock> {
    markdownBlockCache.get(text)?.let { return it }
    val startNs = System.nanoTime()
    val parsed = parseBlocks(text)
    LocalStreamPerfLog.onMarkdownParsed(
        textChars = text.length,
        blocks = parsed.size,
        elapsedMs = (System.nanoTime() - startNs) / 1_000_000,
        compact = compact
    )
    if (text.length <= MARKDOWN_CACHE_MAX_ENTRY_CHARS) markdownBlockCache.put(text, parsed)
    return parsed
}

/** True when [line] opens a block of its own and therefore cannot be list/paragraph continuation. */
private fun startsNewBlock(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("```") || HEADING_RE.matches(t) || HR_RE.matches(t) || BQ_RE.matches(t) ||
        (t.startsWith("|") && t.count { it == '|' } >= 3) || TABLE_SEP_RE.matches(t) ||
        TASK_RE.matches(line) || UL_RE.matches(line) || OL_RE.matches(line)
}

private data class RawListItem(val markerLine: String, val continuation: String)

/**
 * Collects the items of one list, tolerating the two shapes models actually emit: blank lines
 * between items ("loose" lists) and wrapped prose indented under an item.
 *
 * The previous `while (RE.matches(lines[i]))` loop stopped at the first blank line, so a loose
 * ordered list became one single-item block per entry — and since numbering was rendered from
 * the index within a block, every item was labelled "1.".
 */
private fun collectListItems(
    lines: List<String>,
    startIndex: Int,
    isItem: (String) -> Boolean
): Pair<List<RawListItem>, Int> {
    val markers = mutableListOf<String>()
    val continuations = mutableListOf<StringBuilder>()
    var i = startIndex
    var sawBlank = false

    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            var j = i + 1
            while (j < lines.size && lines[j].isBlank()) j++
            // The blank only stays inside the list when another item of the same kind follows.
            if (j < lines.size && isItem(lines[j])) { sawBlank = true; i = j; continue }
            break
        }
        if (isItem(line)) {
            markers += line
            continuations += StringBuilder()
            sawBlank = false
            i++
            continue
        }
        if (!sawBlank && markers.isNotEmpty() && line.first().isWhitespace() && !startsNewBlock(line)) {
            continuations.last().append('\n').append(line.trim())
            i++
            continue
        }
        break
    }
    return markers.mapIndexed { idx, m -> RawListItem(m, continuations[idx].toString()) } to i
}

/**
 * Maps raw leading-space counts to nesting levels by rank rather than dividing by a fixed
 * width, so 2-, 3- and 4-space conventions all nest one level per step.
 */
private fun indentLevels(widths: List<Int>): List<Int> {
    val ranks = widths.distinct().sorted()
    return widths.map { ranks.indexOf(it) }
}

private fun parseBlocks(text: String): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trim()

        when {
            // Empty line — skip
            trimmed.isEmpty() -> { i++ }

            // Fenced code block
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val buf = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    buf.add(lines[i]); i++
                }
                result += MdBlock.CodeBlock(lang, buf.joinToString("\n"))
                i++ // skip closing ```
            }

            // Heading
            HEADING_RE.matches(trimmed) -> {
                val m = HEADING_RE.find(trimmed)!!
                result += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                i++
            }

            // Horizontal rule  (---, ***, ___)
            HR_RE.matches(trimmed) -> {
                result += MdBlock.HorizontalRule; i++
            }

            // Blockquote
            BQ_RE.matches(trimmed) -> {
                val bqLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (l.isEmpty()) break              // a blank line closes the quote
                    val m = BQ_RE.find(l) ?: break
                    bqLines += m.groupValues[1]
                    i++
                }
                // Joined on newlines, not spaces — collapsing them flattened every paragraph
                // break inside a quote into one run-on line.
                result += MdBlock.BlockQuote(bqLines.joinToString("\n").trim())
            }

            // Table  —  line starts with | and has at least 2 |
            trimmed.startsWith("|") && trimmed.count { it == '|' } >= 3 -> {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val l = lines[i].trim()
                    if (!TABLE_SEP_RE.matches(l)) tableLines += l
                    i++
                }
                if (tableLines.size >= 2) {
                    val splitRow = { r: String -> r.split("|").map { it.trim() }.filter { it.isNotEmpty() } }
                    result += MdBlock.Table(splitRow(tableLines[0]), tableLines.drop(1).map { splitRow(it) })
                } else if (tableLines.size == 1) {
                    result += MdBlock.Paragraph(tableLines[0].replace("|", " ").trim())
                }
            }

            // Skip standalone table separator rows
            TABLE_SEP_RE.matches(trimmed) -> { i++ }

            // Task list item
            TASK_RE.matches(raw) -> {
                val (raws, next) = collectListItems(lines, i) { TASK_RE.matches(it) }
                i = next
                val levels = indentLevels(raws.map { TASK_RE.find(it.markerLine)!!.groupValues[1].length })
                result += MdBlock.TaskList(raws.mapIndexed { idx, item ->
                    val m = TASK_RE.find(item.markerLine)!!
                    TaskEntry(
                        text    = m.groupValues[3] + item.continuation,
                        checked = m.groupValues[2].lowercase() == "x",
                        indent  = levels[idx]
                    )
                })
            }

            // Unordered list
            UL_RE.matches(raw) -> {
                val isItem = { l: String -> UL_RE.matches(l) && !TASK_RE.matches(l) }
                val (raws, next) = collectListItems(lines, i, isItem)
                i = next
                val levels = indentLevels(raws.map { UL_RE.find(it.markerLine)!!.groupValues[1].length })
                result += MdBlock.UnorderedList(raws.mapIndexed { idx, item ->
                    val m = UL_RE.find(item.markerLine)!!
                    ListEntry(text = m.groupValues[2] + item.continuation, indent = levels[idx])
                })
            }

            // Ordered list
            OL_RE.matches(raw) -> {
                val (raws, next) = collectListItems(lines, i) { OL_RE.matches(it) }
                i = next
                val levels = indentLevels(raws.map { OL_RE.find(it.markerLine)!!.groupValues[1].length })
                result += MdBlock.OrderedList(raws.mapIndexed { idx, item ->
                    val m = OL_RE.find(item.markerLine)!!
                    ListEntry(
                        text   = m.groupValues[3] + item.continuation,
                        indent = levels[idx],
                        // Authored number is kept so a list starting at "3." isn't renumbered.
                        number = m.groupValues[2].toIntOrNull() ?: (idx + 1)
                    )
                })
            }

            // Fall-through: paragraph
            else -> {
                // Gather consecutive non-blank, non-special lines
                val paraLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.isBlank() || startsNewBlock(l)) break
                    paraLines += l.trim()
                    i++
                }
                if (paraLines.isNotEmpty()) {
                    result += MdBlock.Paragraph(paraLines.joinToString("\n"))
                }
            }
        }
    }
    return result
}
