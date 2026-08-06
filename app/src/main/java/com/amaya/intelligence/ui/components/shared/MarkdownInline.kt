@file:Suppress("DEPRECATION")

package com.amaya.intelligence.ui.components.shared

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*

private val MD_BOLD = FontWeight.SemiBold
private val MD_CODE_WEIGHT = FontWeight.Light
private val MD_CODE_TRACKING = 0.6.sp
private val MARK_TINT = Color(0xFFFFD60A)
private val LINK_ON_DARK = Color(0xFF64D2FF)
private val LINK_ON_LIGHT = Color(0xFF0A84FF)
private const val LOCALHOST_URL_DELIMITER = "\u0000LOCALHOST\u0000"
private val LOCALHOST_HOSTS = "localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|::1"
private val LOCALHOST_URL_REGEX = Regex("(https?)://($LOCALHOST_HOSTS)(:\\d{1,5})?(/[^\\s]*)?", RegexOption.IGNORE_CASE)
private val LOCALHOST_PLAIN_REGEX = Regex("($LOCALHOST_HOSTS)(:\\d{1,5})(/[^\\s]*)?", RegexOption.IGNORE_CASE)
private val MARKDOWN_FILE_LINK_RE = Regex("\\[([^\\]]+)\\]\\((file:///[^)]+)\\)")

private fun linkColorFor(textColor: Color): Color = if (textColor.luminance() > 0.6f) LINK_ON_DARK else LINK_ON_LIGHT
private fun codeFontSizeFor(style: TextStyle): TextUnit = if (style.fontSize.isSpecified) style.fontSize * 0.92f else 13.sp
private fun codeWithSoftBreaks(code: String): String {
    if (code.length <= 20 || code.any { it.isWhitespace() }) return code
    return buildString(code.length + 8) { code.forEachIndexed { idx, c -> append(c); if (idx < code.lastIndex && c in charArrayOf('/', '\\', '.', '_', '-')) append('\u200B') } }
}



//  Inline segment model — for mixed text + file-icon rendering
// ═══════════════════════════════════════════════════════════════════

internal sealed class InlineSegment {
    /** A run of styled/annotated text rendered with ClickableText. */
    data class PlainText(val annotated: AnnotatedString) : InlineSegment()
    /** A [label](file:///...) link that gets a file-type icon. */
    data class FileLink(val label: String, val url: String, val filePath: String) : InlineSegment()
}

// ═══════════════════════════════════════════════════════════════════
//  InlineText — routes to plain or icon-aware renderer
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun InlineText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onLocalhostLinkClick: ((String) -> Unit)? = null,
    enableFileReferenceIcons: Boolean = false
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = linkColorFor(color)
    val codeSize = codeFontSizeFor(style)
    val markBg = MARK_TINT.copy(alpha = if (isSystemInDarkTheme()) 0.30f else 0.36f)

    if (enableFileReferenceIcons && text.contains("](file:///")) {
        // Use segment-based rendering to show file icons inline
        val context = LocalContext.current
        val assetNames = remember(context) { loadFileTypeIconAssetNames(context) }
        val segments = remember(text, color, linkColor, codeSize, markBg) {
            parseInlineSegments(text, color, linkColor, codeSize, markBg)
        }
        InlineContentRenderer(
            segments = segments,
            style = style,
            color = color,
            linkColor = linkColor,
            compact = compact,
            assetNames = assetNames,
            modifier = modifier,
            uriHandler = uriHandler,
            onLocalhostLinkClick = onLocalhostLinkClick
        )
    } else {
        // Every colour and size read inside the block is a key — without them a theme switch
        // left the cached AnnotatedString (and so links and code) painted in the old palette.
        val annotated = remember(text, color, linkColor, codeSize, markBg) {
            parseInline(text, color, linkColor, codeSize, markBg)
        }
        ClickableText(
            text = annotated,
            style = style.copy(color = color),
            modifier = modifier,
            onClick = { offset ->
                val localhostAnnotations = annotated.getStringAnnotations(tag = "LOCALHOST", start = offset, end = offset)
                val urlAnnotations = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                localhostAnnotations.firstOrNull()?.let { onLocalhostLinkClick?.invoke(it.item) }
                urlAnnotations.firstOrNull()?.let { uriHandler.openUri(it.item) }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
//  InlineContentRenderer — renders segments with optional file icons
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun InlineContentRenderer(
    segments: List<InlineSegment>,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    compact: Boolean,
    assetNames: Set<String>,
    modifier: Modifier = Modifier,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    onLocalhostLinkClick: ((String) -> Unit)? = null
) {
    val iconSize = if (compact) 13.dp else 15.dp
    val iconSpacing = if (compact) 3.dp else 4.dp
    val chipSpacing = if (compact) 4.dp else 6.dp

    Column(modifier = modifier) {
        // Group consecutive plain-text segments; each FileLink breaks into its own row context.
        // We render each run of (optional plain text)(file link chips)(optional plain text) as a Column of rows.
        var i = 0
        while (i < segments.size) {
            val seg = segments[i]
            when (seg) {
                is InlineSegment.PlainText -> {
                    if (seg.annotated.isNotEmpty()) {
                        ClickableText(
                            text = seg.annotated,
                            style = style.copy(color = color),
                            onClick = { offset ->
                                seg.annotated.getStringAnnotations("LOCALHOST", offset, offset)
                                    .firstOrNull()?.let { onLocalhostLinkClick?.invoke(it.item) }
                                seg.annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { uriHandler.openUri(it.item) }
                            }
                        )
                    }
                    i++
                }
                is InlineSegment.FileLink -> {
                    // Collect consecutive file links into one row of chips
                    val fileLinks = mutableListOf<InlineSegment.FileLink>()
                    while (i < segments.size && segments[i] is InlineSegment.FileLink) {
                        fileLinks += segments[i] as InlineSegment.FileLink
                        i++
                    }
                    Row(
                        modifier = Modifier.padding(vertical = if (compact) 1.dp else 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(chipSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        fileLinks.forEach { link ->
                            val assetName = remember(link.filePath, assetNames) {
                                resolveFileTypeIconAssetName(link.filePath, assetNames)
                            }
                            Row(
                                modifier = Modifier
                                    .clickable { uriHandler.openUri(link.url) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(iconSpacing)
                            ) {
                                if (assetName != null) {
                                    FileTypeHeaderIcon(
                                        filePath = link.filePath,
                                        resolvedAssetName = assetName,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
                                Text(
                                    text = link.label,
                                    style = style.copy(
                                        color = linkColor,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = TextDecoration.None
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseInline(
    text: String,
    color: Color,
    linkColor: Color,
    codeFontSize: TextUnit,
    markBg: Color
): AnnotatedString = buildAnnotatedString {
    parseInlineToBuilder(text, this, color, linkColor, codeFontSize, markBg)
}

/**
 * Finds the closing run of [delim] at or after [from], honouring CommonMark's flanking rules:
 * the delimiter may not be preceded by whitespace, and an underscore delimiter may not be
 * followed by a word character. Without this, `indexOf` alone turned `snake_case_name` into
 * "snake*case*name".
 */
private fun findClosingDelim(src: String, from: Int, delim: String, underscore: Boolean): Int {
    var idx = src.indexOf(delim, from)
    while (idx >= 0) {
        val prev = src.getOrNull(idx - 1)
        val next = src.getOrNull(idx + delim.length)
        val prevOk = prev != null && !prev.isWhitespace()
        val nextOk = !underscore || next == null || !next.isLetterOrDigit()
        if (prevOk && nextOk) return idx
        idx = src.indexOf(delim, idx + 1)
    }
    return -1
}

/** An underscore run only opens emphasis when it is not sitting inside a word. */
private fun opensEmphasis(src: String, i: Int, delim: String): Boolean {
    val next = src.getOrNull(i + delim.length) ?: return false
    if (next.isWhitespace()) return false
    if (delim[0] != '_') return true
    val prev = src.getOrNull(i - 1)
    return prev == null || !prev.isLetterOrDigit()
}

private fun parseInlineToBuilder(
    src: String,
    builder: AnnotatedString.Builder,
    color: Color,
    linkColor: Color,
    codeFontSize: TextUnit,
    markBg: Color
) {
    var i = 0
    with(builder) {
        while (i < src.length) {
            when {
                // Escaped character
                src[i] == '\\' && i + 1 < src.length -> {
                    append(src[i + 1]); i += 2
                }

                // Bold italic  ***text*** or ___text___
                (matchesAt(src, i, "***") || matchesAt(src, i, "___")) &&
                    opensEmphasis(src, i, src.substring(i, i + 3)) -> {
                    val delim = src.substring(i, i + 3)
                    val end = findClosingDelim(src, i + 3, delim, delim[0] == '_')
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = MD_BOLD, fontStyle = FontStyle.Italic)) {
                            parseInlineToBuilder(src.substring(i + 3, end), builder, color, linkColor, codeFontSize, markBg)
                        }
                        i = end + 3
                    } else { append(src[i]); i++ }
                }

                // Bold  **text** or __text__
                (matchesAt(src, i, "**") || matchesAt(src, i, "__")) &&
                    opensEmphasis(src, i, src.substring(i, i + 2)) -> {
                    val delim = src.substring(i, i + 2)
                    val end = findClosingDelim(src, i + 2, delim, delim[0] == '_')
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = MD_BOLD)) {
                            parseInlineToBuilder(src.substring(i + 2, end), builder, color, linkColor, codeFontSize, markBg)
                        }
                        i = end + 2
                    } else { append(src[i]); i++ }
                }

                // Strikethrough ~~text~~ — same flanking guard as the other paired delimiters
                matchesAt(src, i, "~~") && opensEmphasis(src, i, "~~") -> {
                    val end = findClosingDelim(src, i + 2, "~~", underscore = false)
                    if (end > i) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            parseInlineToBuilder(src.substring(i + 2, end), builder, color, linkColor, codeFontSize, markBg)
                        }
                        i = end + 2
                    } else { append(src[i]); i++ }
                }

                // Highlight ==text== — a marker-pen wash, the one place a background belongs.
                // Flanking rules matter here: a bare indexOf let prose like "check x == y
                // and a == b" open a highlight and swallow everything between the two
                // comparisons.
                matchesAt(src, i, "==") && opensEmphasis(src, i, "==") -> {
                    val end = findClosingDelim(src, i + 2, "==", underscore = false)
                    if (end > i + 2) {
                        withStyle(SpanStyle(background = markBg)) {
                            parseInlineToBuilder(src.substring(i + 2, end), builder, color, linkColor, codeFontSize, markBg)
                        }
                        i = end + 2
                    } else { append(src[i]); i++ }
                }

                // Italic  *text* or _text_
                (src[i] == '*' || src[i] == '_') && opensEmphasis(src, i, src.substring(i, i + 1)) -> {
                    val delim = src[i].toString()
                    val end = findClosingDelim(src, i + 1, delim, delim[0] == '_')
                    if (end > i) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            parseInlineToBuilder(src.substring(i + 1, end), builder, color, linkColor, codeFontSize, markBg)
                        }
                        i = end + 1
                    } else { append(src[i]); i++ }
                }

                // Inline code — ``text with ` inside`` or `text`.
                // No background at all. The span reads as code purely from its own letterforms:
                // monospace, a lighter weight than the surrounding body, and airier tracking.
                // Nothing is drawn behind it, so it never interrupts the line the way a filled
                // box does.
                src[i] == '`' -> {
                    val fence = if (matchesAt(src, i, "``")) "``" else "`"
                    val end = src.indexOf(fence, i + fence.length)
                    if (end > i) {
                        withStyle(SpanStyle(
                            fontFamily    = FontFamily.Monospace,
                            fontSize      = codeFontSize,
                            fontWeight    = MD_CODE_WEIGHT,
                            letterSpacing = MD_CODE_TRACKING
                        )) {
                            append(codeWithSoftBreaks(src.substring(i + fence.length, end).trim()))
                        }
                        i = end + fence.length
                    } else { append(src[i]); i++ }
                }

                // Link [text](url)
                src[i] == '[' -> {
                    val closeBracket = src.indexOf(']', i + 1)
                    if (closeBracket > i && closeBracket + 1 < src.length && src[closeBracket + 1] == '(') {
                        val closeParens = src.indexOf(')', closeBracket + 2)
                        if (closeParens > closeBracket) {
                            val label = src.substring(i + 1, closeBracket)
                            val url = src.substring(closeBracket + 2, closeParens)
                            val internalReference = url.startsWith("agent:") ||
                                url.startsWith("workspace:") || url.startsWith("command:")
                            if (!internalReference) pushStringAnnotation(tag = "URL", annotation = url)
                            withStyle(SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.None,
                                fontWeight = FontWeight.Medium
                            )) {
                                append(label)
                            }
                            if (!internalReference) pop()
                            i = closeParens + 1
                        } else { append(src[i]); i++ }
                    } else { append(src[i]); i++ }
                }

                // Image ![alt](url) — show as text label
                src[i] == '!' && i + 1 < src.length && src[i + 1] == '[' -> {
                    val closeBracket = src.indexOf(']', i + 2)
                    if (closeBracket > i) {
                        val closeParens = src.indexOf(')', closeBracket + 2)
                        if (closeParens > closeBracket) {
                            val alt = src.substring(i + 2, closeBracket)
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = color.copy(alpha = 0.5f))) {
                                append("📷 $alt")
                            }
                            i = closeParens + 1
                        } else { append(src[i]); i++ }
                    } else { append(src[i]); i++ }
                }

                // Detect localhost URLs before bare URLs
                matchesLocalhostLink(src, i) != null -> {
                    val matchResult = matchesLocalhostLink(src, i)!!
                    val fullMatch = matchResult.value
                    val isUrlMatch = LOCALHOST_URL_REGEX.find(fullMatch) != null

                    val host: String
                    val portGroup: String?
                    val pathGroup: String?

                    if (isUrlMatch) {
                        // URL pattern: (https?)://(host)(:port)?(/path)?
                        // Groups: 0=whole, 1=protocol, 2=host, 3=port, 4=path
                        host = matchResult.groupValues[2]
                        portGroup = matchResult.groupValues[3].ifEmpty { null }
                        pathGroup = matchResult.groupValues[4].ifEmpty { null }
                    } else {
                        // Plain pattern: (host)(:port)?(/path)?
                        // Groups: 0=whole, 1=host, 2=port, 3=path
                        host = matchResult.groupValues[1]
                        portGroup = matchResult.groupValues[2].ifEmpty { null }
                        pathGroup = matchResult.groupValues[3].ifEmpty { null }
                    }

                    val (annotation, displayText) = parseLocalhostAnnotation(host, portGroup, pathGroup, isUrlMatch)

                    pushStringAnnotation(tag = "LOCALHOST", annotation = annotation)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.None, fontWeight = FontWeight.Medium)) {
                        append(displayText)
                    }
                    pop()
                    i += fullMatch.length
                }

                // Bare URL (https://... or http://...)
                matchesAt(src, i, "https://") || matchesAt(src, i, "http://") -> {
                    val urlEnd = findUrlEnd(src, i)
                    val url = src.substring(i, urlEnd)
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.None
                    )) {
                        append(url)
                    }
                    pop()
                    i = urlEnd
                }

                // Normal character
                else -> { append(src[i]); i++ }
            }
        }
    }
}

/** Find the end of a bare URL. */
private fun findUrlEnd(src: String, start: Int): Int {
    var i = start
    while (i < src.length && src[i] != ' ' && src[i] != '\n' && src[i] != '\r' && src[i] != '\t') {
        i++
    }
    // trim trailing punctuation that's unlikely part of the URL
    while (i > start && src[i - 1] in ".,;:!?)\"'") i--
    return i
}

private fun matchesAt(s: String, i: Int, sub: String): Boolean =
    i + sub.length <= s.length && s.substring(i, i + sub.length) == sub

private fun matchesLocalhostLink(src: String, i: Int): MatchResult? {
    val urlMatch = LOCALHOST_URL_REGEX.find(src, i)
    if (urlMatch != null && urlMatch.range.first == i) return urlMatch
    val plainMatch = LOCALHOST_PLAIN_REGEX.find(src, i)
    if (plainMatch != null && plainMatch.range.first == i) return plainMatch
    return null
}

private fun parseLocalhostAnnotation(host: String, port: String?, path: String?, isHttpScheme: Boolean): Pair<String, String> {
    val actualHost = when (host.lowercase()) {
        "localhost", "127.0.0.1", "0.0.0.0", "::1" -> "LOCALHOST_IP_PLACEHOLDER"
        else -> host
    }

    // Extract port number without colon prefix
    val actualPort = port?.removePrefix(":")?.ifEmpty { null }
    val actualPath = path?.ifEmpty { null } ?: "/"

    val protocol = if (isHttpScheme && host.startsWith("https")) "https" else "http"

    // Build full URL with IP placeholder for annotation
    val fullUrl = if (actualPort != null) {
        "$protocol://$actualHost:$actualPort$actualPath"
    } else {
        "$protocol://$actualHost$actualPath"
    }

    // Display text should show original host (localhost), not the placeholder
    val displayHost = host.lowercase()
    val displayText = if (actualPort != null) {
        if (isHttpScheme) "$protocol://$displayHost:$actualPort$actualPath" else "$displayHost:$actualPort$actualPath"
    } else {
        if (isHttpScheme) "$protocol://$displayHost$actualPath" else "$displayHost$actualPath"
    }

    return "$LOCALHOST_URL_DELIMITER$fullUrl$LOCALHOST_URL_DELIMITER" to displayText
}

// ═══════════════════════════════════════════════════════════════════
//  parseInlineSegments — splits text into PlainText / FileLink segments
// ═══════════════════════════════════════════════════════════════════

/**
 * Walks the same inline syntax as [parseInlineToBuilder] but emits [InlineSegment]s.
 * When a `[label](file:///...)` link is encountered it becomes a [InlineSegment.FileLink]
 * so the caller can render a file-type icon alongside it.
 * All other content is accumulated into [InlineSegment.PlainText] spans.
 */
private fun parseInlineSegments(
    text: String,
    color: Color,
    linkColor: Color,
    codeFontSize: TextUnit,
    markBg: Color
): List<InlineSegment> {
    val result = mutableListOf<InlineSegment>()
    val src = text
    var i = 0
    var builder = AnnotatedString.Builder()

    fun flushBuilder() {
        val a = builder.toAnnotatedString()
        if (a.isNotEmpty()) result += InlineSegment.PlainText(a)
        builder = AnnotatedString.Builder()
    }

    while (i < src.length) {
        // File link: [label](file:///...)
        if (src[i] == '[') {
            val closeBracket = src.indexOf(']', i + 1)
            if (closeBracket > i && closeBracket + 1 < src.length && src[closeBracket + 1] == '(') {
                val closeParens = src.indexOf(')', closeBracket + 2)
                if (closeParens > closeBracket) {
                    val label = src.substring(i + 1, closeBracket)
                    val url   = src.substring(closeBracket + 2, closeParens)
                    if (url.startsWith("file:///")) {
                        // Decode percent-encoded URI to get a real file path for icon resolution
                        val filePath = try {
                            Uri.decode(url.removePrefix("file:///")).let { p ->
                                if (p.getOrNull(1) == ':') p.replace('/', '\\') else "/$p"
                            }
                        } catch (_: Exception) { url.removePrefix("file:///") }
                        flushBuilder()
                        result += InlineSegment.FileLink(label, url, filePath)
                        i = closeParens + 1
                        continue
                    }
                }
            }
        }
        // For everything else, delegate character-by-character to parseInlineToBuilder logic.
        // We pass a temporary builder just for this character run; simpler: parse entire tail
        // as plain text once we know no more file links exist, or just handle char-by-char.
        // For simplicity and zero-duplication: re-use the existing parser on the non-file-link
        // portions by processing one character at a time into the current builder.
        parseInlineSingleChar(src, i, builder, color, linkColor, codeFontSize, markBg).let { newI ->
            i = newI
        }
    }
    flushBuilder()
    return result
}

/**
 * Processes one inline token starting at [i] in [src], appends to [builder],
 * and returns the new index after the token.
 * This is a lightweight character-dispatch that mirrors [parseInlineToBuilder].
 */
private fun parseInlineSingleChar(
    src: String,
    i: Int,
    builder: AnnotatedString.Builder,
    color: Color,
    linkColor: Color,
    codeFontSize: TextUnit,
    markBg: Color
): Int {
    // Find the next '[' after i — everything before it is safe to parse as a chunk.
    val nextBracket = src.indexOf('[', i)
    val chunkEnd = if (nextBracket == -1) src.length else nextBracket
    if (chunkEnd > i) {
        // No '[' in this range → parse safely as a chunk (bold, italic, code, URLs etc.)
        parseInlineToBuilder(src.substring(i, chunkEnd), builder, color, linkColor, codeFontSize, markBg)
        return chunkEnd
    }
    // We are AT a '['. Find the matching ](…) to pass the full link token as one chunk,
    // so parseInlineToBuilder can handle it correctly (e.g. [label](https://...)).
    val closeBracket = src.indexOf(']', i + 1)
    if (closeBracket > i && closeBracket + 1 < src.length && src[closeBracket + 1] == '(') {
        val closeParens = src.indexOf(')', closeBracket + 2)
        if (closeParens > closeBracket) {
            // Full [label](url) token — pass to parseInlineToBuilder in one shot
            parseInlineToBuilder(src.substring(i, closeParens + 1), builder, color, linkColor, codeFontSize, markBg)
            return closeParens + 1
        }
    }
    // Bare '[' with no matching structure — just emit the character
    parseInlineToBuilder("[", builder, color, linkColor, codeFontSize, markBg)
    return i + 1
}

// ═══════════════════════════════════════════════════════════════════
//  Regex helper for extracting file paths from markdown text
// ═══════════════════════════════════════════════════════════════════


/**
 * Extracts local file paths from `[label](file:///...)` markdown links.
 * Used by [ChatMessageList] to prefetch file-type icons.
 */
internal fun extractMarkdownFilePaths(text: String): List<String> {
    return MARKDOWN_FILE_LINK_RE.findAll(text).mapNotNull { match ->
        val url = match.groupValues[2]
        try {
            Uri.decode(url.removePrefix("file:///")).let { p ->
                if (p.getOrNull(1) == ':') p.replace('/', '\\') else "/$p"
            }
        } catch (_: Exception) { null }
    }.toList()
}

// ═══════════════════════════════════════════════════════════════════
//  Table measurement helpers
// ═══════════════════════════════════════════════════════════════════

internal fun tableColumnWidthValues(headers: List<String>, rows: List<List<String>>, compact: Boolean): List<Float> {
    val columnCount = headers.size
    if (columnCount == 0) return emptyList()
    val minShort = if (compact) 44f else 56f
    val minMedium = if (compact) 72f else 88f
    val minLong = if (compact) 112f else 144f
    val maxLong = if (compact) 190f else 260f

    return List(columnCount) { column ->
        val values = buildList {
            add(headers.getOrElse(column) { "" })
            rows.forEach { add(it.getOrElse(column) { "" }) }
        }
        val maxChars = values.maxOfOrNull { it.trim().length } ?: 0
        val longestWord = values.flatMap { it.split(Regex("\\s+")) }.maxOfOrNull { it.length } ?: 0
        when {
            maxChars <= 3 && longestWord <= 3 -> minShort
            maxChars <= 8 && longestWord <= 8 -> minMedium
            else -> (minLong + longestWord.coerceAtMost(24) * if (compact) 3.2f else 4.2f).coerceAtMost(maxLong)
        }
    }
}

internal fun tableWrapText(value: String, chunkSize: Int = 28): String {
    if (value.length <= chunkSize) return value
    val breakable = Regex("[/._?&=#:-]").replace(value) { it.value + "\u200B" }
    return Regex("\\S+|\\s+").findAll(breakable).joinToString("") { match ->
        val part = match.value
        if (part.isBlank() || part.length <= chunkSize) part
        else part.chunked(chunkSize).joinToString("\u200B")
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Block parser
// ═══════════════════════════════════════════════════════════════════

/**
 * Process-wide memo for the block AST.
 *
 * [MarkdownText]'s own `remember(text)` is scoped to the composable instance, and
 * LazyColumn destroys rows the moment they leave the viewport — so without this every
 * scroll back through history re-parses from scratch, and opening a long conversation
 * parses the whole first window on the main thread in one frame. The AST is immutable
 * and keyed purely by the source text, so it is safe to share across instances.
 *
 * Streaming still parses once per emitted string: each token produces a new key. Those
 * intermediate entries age out of the LRU on their own.
 *
 * Budgeted by character count rather than entry count — a chat holds a handful of very
 * long answers alongside many one-liners, and a flat "200 entries" cap would size itself
 * off the worst case. Anything past [MARKDOWN_CACHE_MAX_ENTRY_CHARS] is parsed but not
 * stored, so one huge document can't evict everything else.
 */
