package com.cheek1.app

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Minimal XLSX reader/writer using the Open XML ZIP package.
 * Import reads the first column of the first worksheet.
 */
object SimpleXlsx {
    fun readFirstColumn(input: InputStream): List<String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val shared = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { xml ->
            Regex("<si\\b[^>]*>(.*?)</si>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml).forEach { m ->
                val texts = Regex("<t\\b[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL))
                    .findAll(m.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
                shared.add(texts)
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: error("برگه اول در فایل اکسل پیدا نشد")
        val result = mutableListOf<Pair<Int, String>>()
        val cellRegex = Regex("<c\\b([^>]*)>(.*?)</c>", setOf(RegexOption.DOT_MATCHES_ALL))
        for (m in cellRegex.findAll(sheet)) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val ref = Regex("\\br=\"([A-Z]+)(\\d+)\"").find(attrs) ?: continue
            if (ref.groupValues[1] != "A") continue
            val rowNum = ref.groupValues[2].toIntOrNull() ?: continue
            val type = Regex("\\bt=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
            val raw = Regex("<v\\b[^>]*>(.*?)</v>", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.get(1)
            val value = when (type) {
                "s" -> raw?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                "inlineStr" -> Regex("<t\\b[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL))
                    .find(body)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
                else -> raw?.let { unescape(it) } ?: ""
            }
            result.add(rowNum to value)
        }
        return result.sortedBy { it.first }.map { it.second }
    }

    fun write(output: OutputStream, rows: List<List<String>>) {
        val xmlRows = rows.mapIndexed { index, row ->
            val cells = row.mapIndexed { col, value ->
                val ref = "${columnName(col + 1)}${index + 1}"
                """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escape(value)}</t></is></c>"""
            }.joinToString("")
            """<row r="${index + 1}">$cells</row>"""
        }.joinToString("")
        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <sheetData>$xmlRows</sheetData></worksheet>""".trimIndent()

        ZipOutputStream(output).use { zip ->
            put(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>""".trimIndent())
            put(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""".trimIndent())
            put(zip, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="Checklist" sheetId="1" r:id="rId1"/></sheets></workbook>""".trimIndent())
            put(zip, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>""".trimIndent())
            put(zip, "xl/worksheets/sheet1.xml", sheetXml)
        }
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun columnName(number: Int): String {
        var n = number
        var result = ""
        while (n > 0) {
            val rem = (n - 1) % 26
            result = ('A'.code + rem).toChar() + result
            n = (n - 1) / 26
        }
        return result
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescape(value: String) = value.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
