package com.amaya.intelligence.tools

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes supported office-document containers while [WriteFileTool] owns validation and routing. */
internal class DocumentWriter(private val context: Context) {

    fun write(
        file: File,
        content: String,
        ext: String,
        append: Boolean
    ): ToolResult {
        return try {
            when (ext) {
                "docx" -> writeDocx(file, content, append)
                "xlsx" -> writeXlsx(file, content, append)
                "pptx" -> writePptx(file, content, append)
                "odt" -> writeOdt(file, content, append)
                "ods" -> writeOds(file, content, append)
                "odp" -> writeOdp(file, content, append)
                else -> return ToolResult.Error(
                    "Unsupported document format for writing: $ext",
                    ErrorType.VALIDATION_ERROR
                )
            }

            val operation = if (append) "appended to" else "written to"
            ToolResult.Success(
                output = "Successfully $operation document: ${file.name} (${content.length} chars)",
                metadata = mapOf(
                    "path" to file.absolutePath,
                    "format" to ext,
                    "size" to content.length
                )
            )
        } catch (e: Exception) {
            Log.e(WriteFileTool.TAG, "writeDocument failed: ext=$ext: ${e.javaClass.simpleName}: ${e.message}", e)
            ToolResult.Error(
                "Failed to write document (${e.javaClass.simpleName}): ${e.message}",
                ErrorType.EXECUTION_ERROR
            )
        }
    }

    // ── DOCX Write ──────────────────────────────────────────────────────────────
    private fun writeDocx(file: File, content: String, append: Boolean) {
        val paragraphs = content.lines()

        // Create minimal DOCX structure
        val documentXml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            appendLine("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
            appendLine("""  <w:body>""")

            paragraphs.forEach { line ->
                appendLine("""    <w:p>""")
                appendLine("""      <w:r>""")
                appendLine("""        <w:t>${line.escapeXmlText()}</w:t>""")
                appendLine("""      </w:r>""")
                appendLine("""    </w:p>""")
            }

            appendLine("""  </w:body>""")
            appendLine("""</w:document>""")
        }

        // Write ZIP via temp file first to avoid corrupting target on failure
        val tempFile = File.createTempFile(".docx_write_", ".tmp",
            file.parentFile?.takeIf { it.exists() } ?: context.cacheDir)
        try {
            ZipOutputStream(tempFile.outputStream()).use { zip ->
                // [Content_Types].xml
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".toByteArray())
                zip.closeEntry()

                // _rels/.rels
                zip.putNextEntry(ZipEntry("_rels/.rels"))
                zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".toByteArray())
                zip.closeEntry()

                // word/document.xml
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.toByteArray())
                zip.closeEntry()
            }
            // Move temp to final target
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            throw e
        }
    }

    // ── XLSX Write ──────────────────────────────────────────────────────────────
    // Content format:
    //   - Tab-separated values per row, newline per row
    //   - First row is treated as header (bold + border)
    //   - Prefix cell with "##" for bold, "=formula" for formula
    private fun writeXlsx(file: File, content: String, append: Boolean) {
        val lines = content.lines().filter { it.isNotEmpty() }
        val hasHeader = lines.isNotEmpty()

        // Style IDs: 0=normal, 1=header (bold+border), 2=data with border
        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts>
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><name val="Calibri"/></font>
  </fonts>
  <fills>
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFD9E1F2"/></fgColor></patternFill>
  </fills>
  <borders>
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FF000000"/></left>
      <right style="thin"><color rgb="FF000000"/></right>
      <top style="thin"><color rgb="FF000000"/></top>
      <bottom style="thin"><color rgb="FF000000"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
  </cellXfs>
</styleSheet>"""

        val sheetXml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            appendLine("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            // Auto column width hint
            if (lines.isNotEmpty()) {
                val maxCols = lines.maxOf { it.split("\t").size }
                appendLine("""  <cols>""")
                for (c in 1..maxCols) {
                    appendLine("""    <col min="$c" max="$c" width="18" customWidth="1"/>""")
                }
                appendLine("""  </cols>""")
            }
            appendLine("""  <sheetData>""")

            lines.forEachIndexed { rowIdx, line ->
                val cells = line.split("\t")
                val isHeader = rowIdx == 0 && hasHeader
                // styleId: 1=header, 2=data with border
                val styleId = if (isHeader) 1 else 2
                appendLine("""    <row r="${rowIdx + 1}">""")
                cells.forEachIndexed { colIdx, rawValue ->
                    val cellRef = colIdxToRef(colIdx) + (rowIdx + 1)
                    val cellValue = rawValue.trim()
                    when {
                        // Formula cell
                        cellValue.startsWith("=") -> {
                            appendLine("""      <c r="$cellRef" s="$styleId">""")
                            appendLine("""        <f>${cellValue.drop(1).escapeXmlText()}</f>""")
                            appendLine("""      </c>""")
                        }
                        // Numeric cell
                        cellValue.toDoubleOrNull() != null -> {
                            appendLine("""      <c r="$cellRef" s="$styleId">""")
                            appendLine("""        <v>${cellValue.escapeXmlText()}</v>""")
                            appendLine("""      </c>""")
                        }
                        // String cell
                        else -> {
                            appendLine("""      <c r="$cellRef" t="inlineStr" s="$styleId">""")
                            appendLine("""        <is><t>${cellValue.escapeXmlText()}</t></is>""")
                            appendLine("""      </c>""")
                        }
                    }
                }
                appendLine("""    </row>""")
            }

            appendLine("""  </sheetData>""")
            // Freeze first row if has header
            if (hasHeader && lines.size > 1) {
                appendLine("""  <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
            }
            appendLine("""</worksheet>""")
        }

        ZipOutputStream(file.outputStream()).use { zip ->
            // [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""".toByteArray())
            zip.closeEntry()

            // _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".toByteArray())
            zip.closeEntry()

            // xl/workbook.xml
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>""".toByteArray())
            zip.closeEntry()

            // xl/styles.xml
            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(stylesXml.toByteArray())
            zip.closeEntry()

            // xl/_rels/workbook.xml.rels
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""".toByteArray())
            zip.closeEntry()

            // xl/worksheets/sheet1.xml
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml.toByteArray())
            zip.closeEntry()
        }
    }

    // Convert column index (0-based) to Excel letter ref: 0→A, 25→Z, 26→AA
    private fun colIdxToRef(idx: Int): String {
        var n = idx
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    // ── PPTX Write ──────────────────────────────────────────────────────────────
    private fun writePptx(file: File, content: String, append: Boolean) {
        val lines = content.lines()

        val slideXml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            appendLine("""<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
            appendLine("""  <p:cSld>""")
            appendLine("""    <p:spTree>""")
            appendLine("""      <p:sp>""")
            appendLine("""        <p:txBody>""")
            appendLine("""          <a:p>""")
            lines.forEach { line ->
                appendLine("""            <a:r><a:t>${line.escapeXmlText()}</a:t></a:r>""")
            }
            appendLine("""          </a:p>""")
            appendLine("""        </p:txBody>""")
            appendLine("""      </p:sp>""")
            appendLine("""    </p:spTree>""")
            appendLine("""  </p:cSld>""")
            appendLine("""</p:sld>""")
        }

        ZipOutputStream(file.outputStream()).use { zip ->
            // Minimal PPTX structure (simplified)
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
</Types>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zip.write(slideXml.toByteArray())
            zip.closeEntry()
        }
    }

    // ── ODT Write ───────────────────────────────────────────────────────────────
    private fun writeOdt(file: File, content: String, append: Boolean) {
        val paragraphs = content.lines()

        val contentXml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">""")
            appendLine("""  <office:body>""")
            appendLine("""    <office:text>""")
            paragraphs.forEach { line ->
                appendLine("""      <text:p>${line.escapeXmlText()}</text:p>""")
            }
            appendLine("""    </office:text>""")
            appendLine("""  </office:body>""")
            appendLine("""</office:document-content>""")
        }

        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/vnd.oasis.opendocument.text".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("content.xml"))
            zip.write(contentXml.toByteArray())
            zip.closeEntry()
        }
    }

    // ── ODS Write ───────────────────────────────────────────────────────────────
    private fun writeOds(file: File, content: String, append: Boolean) {
        val lines = content.lines()

        val contentXml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">""")
            appendLine("""  <office:body>""")
            appendLine("""    <office:spreadsheet>""")
            appendLine("""      <table:table table:name="Sheet1">""")
            lines.forEach { line ->
                appendLine("""        <table:table-row>""")
                line.split("\t").forEach { cellValue ->
                    appendLine("""          <table:table-cell><text:p>${cellValue.escapeXmlText()}</text:p></table:table-cell>""")
                }
                appendLine("""        </table:table-row>""")
            }
            appendLine("""      </table:table>""")
            appendLine("""    </office:spreadsheet>""")
            appendLine("""  </office:body>""")
            appendLine("""</office:document-content>""")
        }

        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/vnd.oasis.opendocument.spreadsheet".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("content.xml"))
            zip.write(contentXml.toByteArray())
            zip.closeEntry()
        }
    }

    // ── ODP Write ───────────────────────────────────────────────────────────────
    private fun writeOdp(file: File, content: String, append: Boolean) {
        val lines = content.lines()

        val contentXml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0">""")
            appendLine("""  <office:body>""")
            appendLine("""    <office:presentation>""")
            appendLine("""      <draw:page>""")
            lines.forEach { line ->
                appendLine("""        <text:p>${line.escapeXmlText()}</text:p>""")
            }
            appendLine("""      </draw:page>""")
            appendLine("""    </office:presentation>""")
            appendLine("""  </office:body>""")
            appendLine("""</office:document-content>""")
        }

        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/vnd.oasis.opendocument.presentation".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("content.xml"))
            zip.write(contentXml.toByteArray())
            zip.closeEntry()
        }
    }
}
