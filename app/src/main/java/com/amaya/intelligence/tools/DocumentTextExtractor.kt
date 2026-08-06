package com.amaya.intelligence.tools

import java.io.File
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Stateless document-to-text extraction. ZIP expansion stays bounded by [ByteReadBudget]. */
internal object DocumentTextExtractor {
    const val MAX_EXPANDED_DOCUMENT_BYTES = 20L * 1024 * 1024

    fun extractDocumentText(file: File, ext: String): String = when (ext) {
        "docx" -> extractDocx(file)
        "xlsx" -> extractXlsx(file)
        "pptx" -> extractPptx(file)
        "odt" -> extractOdt(file)
        "ods" -> extractOds(file)
        "odp" -> extractOdp(file)
        "rtf" -> extractRtf(file)
        else -> throw IllegalArgumentException("Unsupported document format: $ext")
    }

    // ── PDF Extraction ──────────────────────────────────────────────────────────
    // Disabled - PDFBox-Android dependency not available in public repos
    // private fun extractPdf(file: File): String {
    //     return PDDocument.load(file).use { document ->
    //         val stripper = PDFTextStripper()
    //         stripper.sortByPosition = true
    //         stripper.getText(document)
    //     }
    // }

    // ── DOCX Extraction ─────────────────────────────────────────────────────────
    private fun extractDocx(file: File): String = extractFromZipXml(
        file = file,
        targetPath = "word/document.xml",
        textTag = "w:t"
    )

    // ── XLSX Extraction ─────────────────────────────────────────────────────────
    private fun extractXlsx(file: File): String {
        val sharedStrings = mutableListOf<String>()
        val sheetTexts = mutableListOf<String>()
        val budget = ByteReadBudget(MAX_EXPANDED_DOCUMENT_BYTES)

        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "xl/sharedStrings.xml" -> {
                        // Extract shared strings
                        val factory = XmlPullParserFactory.newInstance()
                        val parser = factory.newPullParser()
                        parser.setInput(budget.wrap(zip).reader())

                        var eventType = parser.eventType
                        val textBuilder = StringBuilder()
                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            if (eventType == XmlPullParser.START_TAG && parser.name == "t") {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    textBuilder.append(parser.text)
                                }
                            } else if (eventType == XmlPullParser.END_TAG && parser.name == "si") {
                                sharedStrings.add(textBuilder.toString())
                                textBuilder.clear()
                            }
                            eventType = parser.next()
                        }
                    }
                    entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml") -> {
                        // Extract sheet content
                        val factory = XmlPullParserFactory.newInstance()
                        val parser = factory.newPullParser()
                        parser.setInput(budget.wrap(zip).reader())

                        val sheetText = StringBuilder()
                        var eventType = parser.eventType
                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            if (eventType == XmlPullParser.START_TAG && parser.name == "v") {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    val value = parser.text
                                    // Try to get from shared strings, otherwise use raw value
                                    val cellValue = value.toIntOrNull()?.let { idx ->
                                        sharedStrings.getOrNull(idx) ?: value
                                    } ?: value
                                    sheetText.append(cellValue).append("\t")
                                }
                            } else if (eventType == XmlPullParser.END_TAG && parser.name == "row") {
                                sheetText.append("\n")
                            }
                            eventType = parser.next()
                        }
                        sheetTexts.add(sheetText.toString())
                    }
                }
                entry = zip.nextEntry
            }
        }

        return sheetTexts.mapIndexed { idx, text ->
            "=== Sheet ${idx + 1} ===\n$text"
        }.joinToString("\n\n")
    }

    // ── PPTX Extraction ─────────────────────────────────────────────────────────
    private fun extractPptx(file: File): String {
        // Read all slide entry bytes first (can't parse while ZipInputStream is open for other entries)
        val slideEntries = mutableListOf<Pair<String, ByteArray>>()
        val budget = ByteReadBudget(MAX_EXPANDED_DOCUMENT_BYTES)

        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // Match slide files only, skip _rels and slideLayouts
                if (entry.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))) {
                    slideEntries.add(entry.name to budget.readBytes(zip))
                }
                entry = zip.nextEntry
            }
        }

        // Sort slides by number to ensure correct order
        slideEntries.sortBy { (name, _) ->
            name.replace("ppt/slides/slide", "").replace(".xml", "").toIntOrNull() ?: 0
        }

        val slides = slideEntries.mapIndexedNotNull { _, (_, bytes) ->
            val slideText = StringBuilder()

            // Parse XML bytes — use non-namespace-aware mode for simpler tag name matching
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(bytes.inputStream(), "UTF-8")

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tagName = parser.name ?: ""
                    // PPTX text runs: a:t. Also handle <t> in case namespace stripped
                    if (tagName == "a:t" || tagName == "t") {
                        // Collect all TEXT tokens until end tag
                        val sb = StringBuilder()
                        eventType = parser.next()
                        while (eventType == XmlPullParser.TEXT || eventType == XmlPullParser.ENTITY_REF) {
                            sb.append(parser.text ?: "")
                            eventType = parser.next()
                        }
                        val text = sb.toString()
                        if (text.isNotBlank()) {
                            slideText.append(text).append(" ")
                        }
                        continue // already advanced eventType
                    }
                    // End of paragraph → add newline
                    if (tagName == "a:p" || tagName == "p") {
                        if (slideText.isNotEmpty() && !slideText.endsWith("\n")) {
                            slideText.append("\n")
                        }
                    }
                }
                eventType = parser.next()
            }

            slideText.toString().trim().takeIf { it.isNotEmpty() }
        }

        return if (slides.isEmpty()) {
            "No text content found in presentation"
        } else {
            slides.mapIndexed { idx, text ->
                "=== Slide ${idx + 1} ===\n$text"
            }.joinToString("\n\n")
        }
    }

    // ── ODT Extraction ──────────────────────────────────────────────────────────
    private fun extractOdt(file: File): String = extractFromZipXml(
        file = file,
        targetPath = "content.xml",
        textTag = "text:p"
    )

    // ── ODS Extraction ──────────────────────────────────────────────────────────
    private fun extractOds(file: File): String = extractFromZipXml(
        file = file,
        targetPath = "content.xml",
        textTag = "text:p"
    )

    // ── ODP Extraction ──────────────────────────────────────────────────────────
    private fun extractOdp(file: File): String = extractFromZipXml(
        file = file,
        targetPath = "content.xml",
        textTag = "text:p"
    )

    // ── RTF Extraction ──────────────────────────────────────────────────────────
    private fun extractRtf(file: File): String {
        val content = file.readText()
        // Simple RTF stripping - remove control words and groups
        return content
            .replace(Regex("""\\[a-z]+(-?\d+)? ?"""), " ") // Control words
            .replace(Regex("""\{|\}"""), "") // Braces
            .replace(Regex("""\\'[0-9a-f]{2}"""), "") // Hex chars
            .replace(Regex("""\s+"""), " ") // Normalize whitespace
            .trim()
    }

    // ── Helper: Extract from ZIP+XML ────────────────────────────────────────────
    private fun extractFromZipXml(file: File, targetPath: String, textTag: String): String {
        val textBuilder = StringBuilder()
        val budget = ByteReadBudget(MAX_EXPANDED_DOCUMENT_BYTES)

        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == targetPath) {
                    val factory = XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(budget.wrap(zip).reader())

                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG && parser.name == textTag) {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                textBuilder.append(parser.text).append("\n")
                            }
                        }
                        eventType = parser.next()
                    }
                    break
                }
                entry = zip.nextEntry
            }
        }

        return textBuilder.toString().trim()
    }
}
