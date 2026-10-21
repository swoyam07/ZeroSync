package com.zerosync.app.files

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.END_TAG
import org.xmlpull.v1.XmlPullParser.START_TAG
import org.xmlpull.v1.XmlPullParser.TEXT
import java.io.InputStream
import java.util.zip.ZipInputStream

/*
 * View-only readers for modern Office files (.docx / .pptx / .xlsx).
 * These files are zip archives of XML. We unzip them in memory and pick out text,
 * pictures and simple tables with Android's built-in XML parser. No libraries.
 * Layout is simplified: this is for reading, not exact printing.
 */

// ---------- Word ----------
data class Run(val text: String, val bold: Boolean, val italic: Boolean)

sealed interface DocBlock
/** [heading]: 0 = normal text, 1..3 = heading size. */
data class DocParagraph(val runs: List<Run>, val heading: Int, val bullet: Boolean) : DocBlock
class DocImage(val bytes: ByteArray) : DocBlock
data class DocTable(val rows: List<List<String>>) : DocBlock

// ---------- PowerPoint (positions in EMU, PowerPoint's unit) ----------
data class SlideText(val text: String, val sizePt: Float, val bold: Boolean, val color: Int?)

sealed interface SlideItem {
    val x: Long
    val y: Long
    val w: Long
    val h: Long
}

data class SlideTextBox(
    override val x: Long, override val y: Long, override val w: Long, override val h: Long,
    val paragraphs: List<List<SlideText>>,
    val isTitle: Boolean,
) : SlideItem

class SlidePicture(
    override val x: Long, override val y: Long, override val w: Long, override val h: Long,
    val bytes: ByteArray,
) : SlideItem

data class Slide(val items: List<SlideItem>)
data class Presentation(val width: Long, val height: Long, val slides: List<Slide>)

// ---------- Excel ----------
data class Sheet(val name: String, val rows: List<List<String>>)

/** Biggest file we unzip, so a huge file can't use up the phone's memory. */
private const val MAX_UNZIPPED = 200L * 1024 * 1024
private const val MAX_ROWS = 5000
private const val MAX_COLS = 100

private fun readZip(input: InputStream): Map<String, ByteArray> {
    val out = HashMap<String, ByteArray>()
    var total = 0L
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val bytes = zip.readBytes() // reads just this entry
            total += bytes.size
            if (total > MAX_UNZIPPED) error("File is too big to preview")
            out[entry.name] = bytes
        }
    }
    return out
}

private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) // tag names keep their prefix, e.g. "w:p"
    setInput(bytes.inputStream(), null)
}

private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

/** Relationship file: id -> target path (how XML points at pictures and slides). */
private fun rels(zip: Map<String, ByteArray>, path: String): Map<String, String> {
    val bytes = zip[path] ?: return emptyMap()
    val p = parser(bytes)
    val map = HashMap<String, String>()
    while (p.next() != END_DOCUMENT) {
        if (p.eventType == START_TAG && p.name == "Relationship") {
            val id = p.attr("Id")
            val target = p.attr("Target")
            if (id != null && target != null) map[id] = target
        }
    }
    return map
}

/** Join a folder and a relative target like "../media/image1.png" into a zip path. */
private fun resolve(baseDir: String, target: String): String {
    if (target.startsWith("/")) return target.drop(1)
    val parts = ArrayList<String>()
    for (s in (baseDir + target).split('/')) {
        when (s) {
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            ".", "" -> Unit
            else -> parts.add(s)
        }
    }
    return parts.joinToString("/")
}

private fun isOn(value: String?) = value == null || (value != "0" && value != "false")

private fun headingLevel(style: String?): Int {
    val s = style?.lowercase() ?: return 0
    if (s == "title") return 1
    if (!s.startsWith("heading")) return 0
    return (s.removePrefix("heading").toIntOrNull() ?: 3).coerceIn(1, 3)
}

fun parseDocx(input: InputStream): List<DocBlock> {
    val zip = readZip(input)
    val doc = zip["word/document.xml"] ?: error("Not a Word (.docx) file")
    val rel = rels(zip, "word/_rels/document.xml.rels")
    val blocks = ArrayList<DocBlock>()
    val p = parser(doc)

    var runs = ArrayList<Run>()
    var images = ArrayList<ByteArray>()
    var heading = 0
    var bullet = false
    var bold = false
    var italic = false
    var inText = false
    // Tables: text of each cell, row by row. Nested tables are flattened into their cell.
    var tableDepth = 0
    var rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    var cell = StringBuilder()

    while (true) {
        when (p.next()) {
            END_DOCUMENT -> break
            START_TAG -> when (p.name) {
                "w:p" -> {
                    runs = ArrayList(); images = ArrayList(); heading = 0; bullet = false
                }
                "w:pStyle" -> heading = headingLevel(p.attr("w:val"))
                "w:numPr" -> bullet = true
                "w:r" -> { bold = false; italic = false }
                "w:b" -> bold = isOn(p.attr("w:val"))
                "w:i" -> italic = isOn(p.attr("w:val"))
                "w:t" -> inText = true
                "w:tab" -> runs.add(Run("\t", bold, italic))
                "w:br", "w:cr" -> runs.add(Run("\n", bold, italic))
                "a:blip" -> p.attr("r:embed")?.let { rel[it] }?.let { zip[resolve("word/", it)] }?.let { images.add(it) }
                "w:tbl" -> { if (tableDepth == 0) rows = ArrayList(); tableDepth++ }
                "w:tr" -> if (tableDepth == 1) row = ArrayList()
                "w:tc" -> if (tableDepth == 1) cell = StringBuilder()
            }
            TEXT -> if (inText) runs.add(Run(p.text, bold, italic))
            END_TAG -> when (p.name) {
                "w:t" -> inText = false
                "w:p" -> {
                    val text = runs.joinToString("") { it.text }
                    if (tableDepth > 0) {
                        if (cell.isNotEmpty() && text.isNotEmpty()) cell.append('\n')
                        cell.append(text)
                    } else {
                        if (text.isNotBlank() || images.isEmpty()) blocks.add(DocParagraph(runs, heading, bullet))
                        images.forEach { blocks.add(DocImage(it)) }
                    }
                }
                "w:tc" -> if (tableDepth == 1) row.add(cell.toString())
                "w:tr" -> if (tableDepth == 1) rows.add(row)
                "w:tbl" -> { tableDepth--; if (tableDepth == 0) blocks.add(DocTable(rows)) }
            }
        }
    }
    return blocks
}

fun parsePptx(input: InputStream): Presentation {
    val zip = readZip(input)
    val pres = zip["ppt/presentation.xml"] ?: error("Not a PowerPoint (.pptx) file")
    val presRels = rels(zip, "ppt/_rels/presentation.xml.rels")
    var cx = 12192000L // default 16:9
    var cy = 6858000L
    val ids = ArrayList<String>()
    val p = parser(pres)
    while (p.next() != END_DOCUMENT) {
        if (p.eventType != START_TAG) continue
        when (p.name) {
            "p:sldSz" -> {
                cx = p.attr("cx")?.toLongOrNull() ?: cx
                cy = p.attr("cy")?.toLongOrNull() ?: cy
            }
            "p:sldId" -> p.attr("r:id")?.let { ids.add(it) }
        }
    }
    val slides = ids.mapNotNull { presRels[it] }
        .map { resolve("ppt/", it) }
        .mapNotNull { path -> zip[path]?.let { parseSlide(zip, path, it, cx, cy) } }
    return Presentation(cx, cy, slides)
}

private fun parseSlide(zip: Map<String, ByteArray>, path: String, bytes: ByteArray, cx: Long, cy: Long): Slide {
    val dir = path.substringBeforeLast('/') + "/"
    val rel = rels(zip, dir + "_rels/" + path.substringAfterLast('/') + ".rels")
    val items = ArrayList<SlideItem>()
    val p = parser(bytes)

    var kind = 0 // 0 = none, 1 = text shape, 2 = picture
    var x = -1L; var y = -1L; var w = -1L; var h = -1L
    var placeholder: String? = null
    var paras = ArrayList<List<SlideText>>()
    var para = ArrayList<SlideText>()
    var size = 0f
    var bold = false
    var color: Int? = null
    var inRunProps = false
    var inText = false
    var picture: ByteArray? = null

    while (true) {
        when (p.next()) {
            END_DOCUMENT -> break
            START_TAG -> when (p.name) {
                "p:sp" -> { kind = 1; x = -1; y = -1; w = -1; h = -1; placeholder = null; paras = ArrayList() }
                "p:pic" -> { kind = 2; x = -1; y = -1; w = -1; h = -1; picture = null }
                "p:ph" -> if (kind == 1) placeholder = p.attr("type") ?: "body"
                "a:off" -> if (kind != 0) {
                    x = p.attr("x")?.toLongOrNull() ?: x
                    y = p.attr("y")?.toLongOrNull() ?: y
                }
                "a:ext" -> if (kind != 0) p.attr("cx")?.toLongOrNull()?.let {
                    w = it
                    h = p.attr("cy")?.toLongOrNull() ?: h
                }
                "a:p" -> para = ArrayList()
                "a:r", "a:fld" -> { size = 0f; bold = false; color = null }
                "a:rPr" -> {
                    inRunProps = true
                    size = p.attr("sz")?.toFloatOrNull()?.div(100f) ?: 0f
                    bold = p.attr("b") == "1"
                }
                "a:srgbClr" -> if (inRunProps) color = p.attr("val")?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
                "a:t" -> inText = true
                "a:br" -> para.add(SlideText("\n", size, bold, color))
                "a:blip" -> if (kind == 2) picture = p.attr("r:embed")?.let { rel[it] }?.let { zip[resolve(dir, it)] }
            }
            TEXT -> if (inText) para.add(SlideText(p.text, size, bold, color))
            END_TAG -> when (p.name) {
                "a:t" -> inText = false
                "a:rPr" -> inRunProps = false
                "a:p" -> if (kind == 1) paras.add(para)
                "p:sp" -> {
                    if (kind == 1 && paras.any { line -> line.any { it.text.isNotBlank() } }) {
                        val title = placeholder == "title" || placeholder == "ctrTitle"
                        // No position on the slide = it comes from the layout; use a typical spot.
                        val box = when {
                            w > 0 -> listOf(x.coerceAtLeast(0), y.coerceAtLeast(0), w, h)
                            title -> listOf(cx / 20, cy / 20, cx * 9 / 10, cy / 5)
                            else -> listOf(cx / 20, cy / 4, cx * 9 / 10, cy * 7 / 10)
                        }
                        items.add(SlideTextBox(box[0], box[1], box[2], box[3], paras, title))
                    }
                    kind = 0
                }
                "p:pic" -> {
                    val pic = picture
                    if (pic != null && w > 0) items.add(SlidePicture(x.coerceAtLeast(0), y.coerceAtLeast(0), w, h, pic))
                    kind = 0
                }
            }
        }
    }
    return Slide(items)
}

fun parseXlsx(input: InputStream): List<Sheet> {
    val zip = readZip(input)
    val workbook = zip["xl/workbook.xml"] ?: error("Not an Excel (.xlsx) file")
    val wbRels = rels(zip, "xl/_rels/workbook.xml.rels")

    // Text cells point into one shared list of strings.
    val shared = ArrayList<String>()
    zip["xl/sharedStrings.xml"]?.let { bytes ->
        val p = parser(bytes)
        var sb = StringBuilder()
        var inT = false
        while (p.next() != END_DOCUMENT) {
            when (p.eventType) {
                START_TAG -> when (p.name) { "si" -> sb = StringBuilder(); "t" -> inT = true }
                TEXT -> if (inT) sb.append(p.text)
                END_TAG -> when (p.name) { "t" -> inT = false; "si" -> shared.add(sb.toString()) }
            }
        }
    }

    val sheets = ArrayList<Pair<String, String>>() // name -> zip path
    val p = parser(workbook)
    while (p.next() != END_DOCUMENT) {
        if (p.eventType == START_TAG && p.name == "sheet") {
            val target = p.attr("r:id")?.let { wbRels[it] } ?: continue
            sheets.add((p.attr("name") ?: "Sheet") to resolve("xl/", target))
        }
    }
    return sheets.mapNotNull { (name, path) -> zip[path]?.let { Sheet(name, parseSheet(it, shared)) } }
}

private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
    val cells = HashMap<Int, HashMap<Int, String>>()
    var maxRow = -1
    var maxCol = -1
    val p = parser(bytes)
    var rowIndex = -1
    var colIndex = -1
    var type: String? = null
    var value = StringBuilder()
    var inValue = false

    while (p.next() != END_DOCUMENT) {
        when (p.eventType) {
            START_TAG -> when (p.name) {
                "row" -> { rowIndex = p.attr("r")?.toIntOrNull()?.minus(1) ?: (rowIndex + 1); colIndex = -1 }
                "c" -> {
                    val ref = p.attr("r")
                    colIndex = if (ref != null) columnOf(ref) else colIndex + 1
                    type = p.attr("t")
                    value = StringBuilder()
                }
                "v", "t" -> inValue = true
            }
            TEXT -> if (inValue) value.append(p.text)
            END_TAG -> when (p.name) {
                "v", "t" -> inValue = false
                "c" -> {
                    if (rowIndex in 0 until MAX_ROWS && colIndex in 0 until MAX_COLS && value.isNotEmpty()) {
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            else -> raw.removeSuffix(".0")
                        }
                        cells.getOrPut(rowIndex) { HashMap() }[colIndex] = text
                        maxRow = maxOf(maxRow, rowIndex)
                        maxCol = maxOf(maxCol, colIndex)
                    }
                }
            }
        }
    }
    return List(maxRow + 1) { r -> List(maxCol + 1) { c -> cells[r]?.get(c) ?: "" } }
}

/** "B3" -> 1 (columns are letters: A = 0, B = 1, ... AA = 26). */
private fun columnOf(ref: String): Int {
    var n = 0
    for (ch in ref) {
        if (!ch.isLetter()) break
        n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
    }
    return n - 1
}
