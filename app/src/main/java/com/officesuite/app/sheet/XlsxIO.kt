package com.officesuite.app.sheet

import com.officesuite.app.core.OoXml
import com.officesuite.app.core.Templates
import com.officesuite.app.core.attr
import com.officesuite.app.core.elementLikeRoot
import com.officesuite.app.core.kid
import com.officesuite.app.core.kids
import com.officesuite.app.core.kidsNamed
import com.officesuite.app.core.removeAllChildren
import com.officesuite.app.core.setOrdered
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.math.BigDecimal
import java.math.MathContext

/**
 * Reads and writes .xlsx workbooks. Parts the app does not understand (charts, drawings, merged cells,
 * conditional formats, ...) are kept as they are; only cell data, sheet list and new styles are rewritten.
 */
object XlsxIO {
    private const val REL_STYLES = "/styles"
    private const val REL_SHARED = "/sharedStrings"
    private const val REL_CALC = "/calcChain"

    private val WS_ORDER = listOf(
        "sheetPr", "dimension", "sheetViews", "sheetFormatPr", "cols", "sheetData", "sheetCalcPr", "sheetProtection",
        "protectedRanges", "scenarios", "autoFilter", "sortState", "dataConsolidate", "customSheetViews", "mergeCells",
        "phoneticPr", "conditionalFormatting", "dataValidations", "hyperlinks", "printOptions", "pageMargins", "pageSetup",
        "headerFooter", "rowBreaks", "colBreaks", "customProperties", "cellWatches", "ignoredErrors", "smartTags", "drawing",
        "legacyDrawing", "legacyDrawingHF", "picture", "oleObjects", "controls", "webPublishItems", "tableParts", "extLst"
    )
    private val WB_ORDER = listOf(
        "fileVersion", "fileSharing", "workbookPr", "workbookProtection", "bookViews", "sheets", "functionGroups",
        "externalReferences", "definedNames", "calcPr", "oleSize", "customWorkbookViews", "pivotCaches", "smartTagPr",
        "smartTagTypes", "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst"
    )
    private val STYLE_ORDER = listOf(
        "numFmts", "fonts", "fills", "borders", "cellStyleXfs", "cellXfs", "cellStyles", "dxfs", "tableStyles", "colors", "extLst"
    )
    private val FONT_ORDER = listOf(
        "b", "i", "strike", "condense", "extend", "outline", "shadow", "u", "vertAlign", "sz", "color", "name", "family", "charset", "scheme"
    )

    /* ============================== READ ============================== */

    fun newWorkbook(): Workbook = read(Templates.newXlsx())

    fun read(zip: LinkedHashMap<String, ByteArray>): Workbook {
        val wb = Workbook()
        wb.zip = zip
        val wbPart = OoXml.mainPart(zip, "xl/workbook.xml")
        wb.workbookPart = wbPart
        val wbDoc = OoXml.parse(zip[wbPart] ?: throw IllegalArgumentException("This is not an Excel workbook"))
        val rels = OoXml.readRels(zip, OoXml.relsPathFor(wbPart)).associateBy { it.id }

        val shared = rels.values.firstOrNull { it.type.endsWith(REL_SHARED) }
            ?.let { zip[OoXml.resolveTarget(wbPart, it.target)] }
            ?.let { readSharedStrings(OoXml.parse(it)) } ?: emptyList()

        rels.values.firstOrNull { it.type.endsWith(REL_STYLES) }
            ?.let { zip[OoXml.resolveTarget(wbPart, it.target)] }
            ?.let { readStyles(OoXml.parse(it), wb) }

        for (se in wbDoc.documentElement.kid("sheets")?.kidsNamed("sheet").orEmpty()) {
            val rid = se.getAttributeNS(OoXml.R, "id")
            val rel = rels[rid] ?: continue
            if (!rel.type.endsWith("/worksheet")) continue
            val path = OoXml.resolveTarget(wbPart, rel.target)
            val sh = Sheet(se.getAttribute("name"))
            sh.partPath = path
            sh.relId = rid
            zip[path]?.let { readSheet(sh, OoXml.parse(it), shared) }
            wb.sheets.add(sh)
        }
        if (wb.sheets.isEmpty()) wb.sheets.add(Sheet("Sheet1"))
        return wb
    }

    private fun textOf(e: Element): String {
        e.kid("t")?.let { return it.textContent ?: "" }
        val sb = StringBuilder()
        for (r in e.kidsNamed("r")) sb.append(r.kid("t")?.textContent ?: "")
        return sb.toString()
    }

    private fun readSharedStrings(doc: Document): List<String> =
        doc.documentElement.kidsNamed("si").map { textOf(it) }

    private fun parseColor(e: Element?): Int? {
        val rgb = e?.attr("rgb") ?: return null
        return try {
            val v = if (rgb.length == 6) "FF$rgb" else rgb
            v.toLong(16).toInt()
        } catch (_: Exception) {
            null
        }
    }

    private class FontInfo(val b: Boolean, val i: Boolean, val u: Boolean, val s: Boolean, val color: Int?, val size: Float)

    private fun readStyles(doc: Document, wb: Workbook) {
        val root = doc.documentElement
        val numFmts = HashMap<Int, String>()
        root.kid("numFmts")?.kidsNamed("numFmt")?.forEach {
            val id = it.attr("numFmtId")?.toIntOrNull() ?: return@forEach
            numFmts[id] = it.getAttribute("formatCode")
        }
        val fonts = root.kid("fonts")?.kidsNamed("font").orEmpty().map { f ->
            fun on(name: String): Boolean {
                val k = f.kid(name) ?: return false
                val v = k.attr("val")
                return v == null || (v != "0" && v != "false" && v != "none")
            }
            FontInfo(on("b"), on("i"), on("u"), on("strike"), parseColor(f.kid("color")), f.kid("sz")?.attr("val")?.toFloatOrNull() ?: 11f)
        }
        val fills = root.kid("fills")?.kidsNamed("fill").orEmpty().map { fill ->
            val pf = fill.kid("patternFill")
            if (pf?.attr("patternType") == "solid") parseColor(pf.kid("fgColor")) else null
        }
        val xfs = root.kid("cellXfs")?.kidsNamed("xf").orEmpty()
        if (xfs.isEmpty()) return
        wb.styles.clear()
        xfs.forEachIndexed { idx, xf ->
            val font = fonts.getOrNull(xf.attr("fontId")?.toIntOrNull() ?: 0)
            val fill = fills.getOrNull(xf.attr("fillId")?.toIntOrNull() ?: 0)
            val nfId = xf.attr("numFmtId")?.toIntOrNull() ?: 0
            val al = xf.kid("alignment")
            val h = when (al?.attr("horizontal")) {
                "left" -> HAlign.LEFT
                "center", "centerContinuous" -> HAlign.CENTER
                "right" -> HAlign.RIGHT
                else -> HAlign.GENERAL
            }
            wb.styles.add(
                CellStyle(
                    bold = font?.b ?: false, italic = font?.i ?: false, underline = font?.u ?: false, strike = font?.s ?: false,
                    fontColor = font?.color, fillColor = fill, hAlign = h, wrap = al?.attr("wrapText") == "1",
                    fontSize = font?.size ?: 11f, numFmt = numFmts[nfId] ?: NumFmt.BUILTIN[nfId] ?: "General", baseXf = idx
                )
            )
        }
        wb.fileXfCount = wb.styles.size
    }

    private fun normalizeNumber(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        if (d.isNaN() || d.isInfinite()) return v
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return BigDecimal(d).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    private fun readSheet(sh: Sheet, doc: Document, shared: List<String>) {
        sh.dom = doc
        val root = doc.documentElement
        root.kid("cols")?.kidsNamed("col")?.forEach { col ->
            val min = col.attr("min")?.toIntOrNull() ?: return@forEach
            val max = col.attr("max")?.toIntOrNull() ?: min
            val w = col.attr("width")?.toFloatOrNull() ?: return@forEach
            if (max - min < 1000) for (c in min..max) sh.colWidths[c - 1] = w
        }
        val data = root.kid("sheetData") ?: return
        val masters = HashMap<String, Triple<String, Int, Int>>()
        var nextRow = 0
        for (row in data.kidsNamed("row")) {
            val r = row.attr("r")?.toIntOrNull()?.minus(1) ?: nextRow
            nextRow = r + 1
            if (row.attr("customHeight") == "1") row.attr("ht")?.toFloatOrNull()?.let { sh.rowHeights[r] = it }
            var nextCol = 0
            for (c in row.kidsNamed("c")) {
                val pos = c.attr("r")?.let { Addr.parse(it) } ?: (r to nextCol)
                val rr = pos.first
                val cc = pos.second
                nextCol = cc + 1
                val t = c.attr("t") ?: "n"
                val style = c.attr("s")?.toIntOrNull() ?: 0
                val f = c.kid("f")
                val v = c.kid("v")?.textContent
                var isText = false
                val raw: String
                if (f != null) {
                    var ftext = f.textContent ?: ""
                    if (f.attr("t") == "shared") {
                        val si = f.attr("si") ?: ""
                        if (ftext.isNotBlank()) masters[si] = Triple(ftext, rr, cc)
                        else {
                            val m = masters[si]
                            ftext = if (m != null) FormulaRewrite.shiftRelative(m.first, rr - m.second, cc - m.third) else ""
                        }
                    }
                    raw = if (ftext.isNotBlank()) "=$ftext" else (v ?: "")
                } else {
                    raw = when (t) {
                        "s" -> { isText = true; shared.getOrNull(v?.trim()?.toIntOrNull() ?: -1) ?: "" }
                        "inlineStr" -> { isText = true; c.kid("is")?.let { textOf(it) } ?: "" }
                        "str" -> { isText = true; v ?: "" }
                        "b" -> if (v?.trim() == "1") "TRUE" else "FALSE"
                        "e" -> v ?: "#N/A"
                        else -> if (v != null) normalizeNumber(v.trim()) else ""
                    }
                }
                if (raw.isEmpty() && style == 0) continue
                sh.cells[Sheet.key(rr, cc)] = Cell(raw, style, isText && raw.isNotEmpty())
            }
        }
    }

    /* ============================== WRITE ============================== */

    private fun numText(d: Double): String =
        if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    fun write(wb: Workbook, ev: Evaluator): ByteArray {
        val zip = LinkedHashMap(wb.zip ?: Templates.newXlsx())
        val wbPart = wb.workbookPart
        val relsPath = OoXml.relsPathFor(wbPart)
        val wbDoc = OoXml.parse(zip[wbPart] ?: throw IllegalStateException("workbook part missing"))
        val root = wbDoc.documentElement
        var sheetsEl = root.kid("sheets")
        if (sheetsEl == null) {
            sheetsEl = wbDoc.elementLikeRoot("sheets")
            setOrdered(root, sheetsEl, WB_ORDER)
        }
        val rels = OoXml.readRels(zip, relsPath).associateBy { it.id }

        // 1. remove worksheets deleted in the app
        val keep = wb.sheets.mapNotNull { it.relId }.toSet()
        for (se in sheetsEl.kidsNamed("sheet")) {
            val rid = se.getAttributeNS(OoXml.R, "id")
            val rel = rels[rid] ?: continue
            if (rel.type.endsWith("/worksheet") && rid !in keep) {
                sheetsEl.removeChild(se)
                val path = OoXml.resolveTarget(wbPart, rel.target)
                zip.remove(path)
                zip.remove(OoXml.relsPathFor(path))
                OoXml.removeOverride(zip, path)
                OoXml.removeRels(zip, relsPath) { it.id == rid }
            }
        }

        // 2. add / rename sheets and write their data
        for (sh in wb.sheets) {
            if (sh.relId == null) {
                var n = 1
                while (zip.containsKey("xl/worksheets/sheet$n.xml")) n++
                val path = "xl/worksheets/sheet$n.xml"
                val target = relativeTo(wbPart, path)
                val rid = OoXml.addRel(zip, relsPath, Templates.REL_SHEET, target)
                OoXml.addOverride(zip, path, Templates.CT_SHEET)
                val maxId = sheetsEl.kidsNamed("sheet").maxOfOrNull { it.attr("sheetId")?.toIntOrNull() ?: 0 } ?: 0
                val se = wbDoc.elementLikeRoot("sheet")
                se.setAttribute("name", sh.name)
                se.setAttribute("sheetId", (maxId + 1).toString())
                se.setAttributeNS(OoXml.R, "r:id", rid)
                sheetsEl.appendChild(se)
                sh.relId = rid
                sh.partPath = path
            } else {
                sheetsEl.kidsNamed("sheet").firstOrNull { it.getAttributeNS(OoXml.R, "id") == sh.relId }
                    ?.setAttribute("name", sh.name)
            }
            val doc = sh.dom ?: OoXml.parse(Templates.newSheetXml()).also { sh.dom = it }
            writeSheet(sh, doc, ev, wb)
            zip[sh.partPath!!] = OoXml.serialize(doc)
        }

        // 3. ask Excel to recalculate, keep the active tab valid, drop the stale calc chain
        val calc = root.kid("calcPr") ?: wbDoc.elementLikeRoot("calcPr").also { setOrdered(root, it, WB_ORDER) }
        calc.setAttribute("fullCalcOnLoad", "1")
        root.kid("bookViews")?.kidsNamed("workbookView")?.forEach {
            it.setAttribute("activeTab", "0")
            it.removeAttribute("firstSheet")
        }
        for (rel in OoXml.readRels(zip, relsPath).filter { it.type.endsWith(REL_CALC) }) {
            val path = OoXml.resolveTarget(wbPart, rel.target)
            zip.remove(path)
            OoXml.removeOverride(zip, path)
            OoXml.removeRels(zip, relsPath) { it.id == rel.id }
        }
        zip[wbPart] = OoXml.serialize(wbDoc)

        // 4. styles created in the app
        writeStyles(wb, zip, wbPart, relsPath)

        wb.zip = zip
        return OoXml.zipBytes(zip)
    }

    /** Path of [part] relative to the folder of [from] (both package paths, no leading slash). */
    private fun relativeTo(from: String, part: String): String {
        val dir = from.substringBeforeLast('/', "")
        return if (dir.isNotEmpty() && part.startsWith("$dir/")) part.substring(dir.length + 1) else "/$part"
    }

    private fun writeSheet(sh: Sheet, doc: Document, ev: Evaluator, wb: Workbook) {
        val root = doc.documentElement
        val data = root.kid("sheetData") ?: doc.elementLikeRoot("sheetData").also { setOrdered(root, it, WS_ORDER) }
        data.removeAllChildren()
        val byRow = sh.cells.entries.groupBy { Sheet.rowOf(it.key) }
        val rows = (byRow.keys + sh.rowHeights.keys).toSortedSet()
        var minR = Int.MAX_VALUE; var minC = Int.MAX_VALUE; var maxR = -1; var maxC = -1
        for (r in rows) {
            val row = doc.elementLikeRoot("row")
            row.setAttribute("r", (r + 1).toString())
            sh.rowHeights[r]?.let { row.setAttribute("ht", it.toString()); row.setAttribute("customHeight", "1") }
            for (e in byRow[r].orEmpty().sortedBy { Sheet.colOf(it.key) }) {
                val c = Sheet.colOf(e.key)
                val cell = e.value
                minR = minOf(minR, r); maxR = maxOf(maxR, r); minC = minOf(minC, c); maxC = maxOf(maxC, c)
                val ce = doc.elementLikeRoot("c")
                ce.setAttribute("r", Addr.name(r, c))
                if (cell.style != 0) ce.setAttribute("s", cell.style.toString())
                fun addV(text: String) {
                    val v = doc.elementLikeRoot("v"); v.textContent = text; ce.appendChild(v)
                }
                if (cell.isFormula) {
                    val f = doc.elementLikeRoot("f")
                    f.textContent = cell.raw.substring(1)
                    ce.appendChild(f)
                    when (val v = ev.cached(sh, r, c) ?: ev.value(sh, r, c)) {
                        is V.Num -> addV(numText(v.v))
                        is V.Str -> { ce.setAttribute("t", "str"); addV(v.v) }
                        is V.Bool -> { ce.setAttribute("t", "b"); addV(if (v.v) "1" else "0") }
                        is V.Err -> if (v.code in STANDARD_ERRORS) { ce.setAttribute("t", "e"); addV(v.code) }
                        else -> {}
                    }
                } else if (cell.raw.isNotEmpty()) {
                    when (val v = Evaluator.literal(cell)) {
                        is V.Num -> addV(numText(v.v))
                        is V.Bool -> { ce.setAttribute("t", "b"); addV(if (v.v) "1" else "0") }
                        is V.Err -> { ce.setAttribute("t", "e"); addV(v.code) }
                        is V.Str -> {
                            ce.setAttribute("t", "inlineStr")
                            val isEl = doc.elementLikeRoot("is")
                            val t = doc.elementLikeRoot("t")
                            t.setAttributeNS(OoXml.XML_NS, "xml:space", "preserve")
                            t.textContent = v.v
                            isEl.appendChild(t)
                            ce.appendChild(isEl)
                        }
                        else -> {}
                    }
                }
                row.appendChild(ce)
            }
            data.appendChild(row)
        }
        val dim = root.kid("dimension")
        if (dim != null) dim.setAttribute("ref", if (maxR < 0) "A1" else Addr.rangeName(minR, minC, maxR, maxC))

        if (sh.colsDirty) {
            root.kid("cols")?.let { root.removeChild(it) }
            if (sh.colWidths.isNotEmpty()) {
                val cols = doc.elementLikeRoot("cols")
                for ((c, w) in sh.colWidths.toSortedMap()) {
                    val col = doc.elementLikeRoot("col")
                    col.setAttribute("min", (c + 1).toString())
                    col.setAttribute("max", (c + 1).toString())
                    col.setAttribute("width", w.toString())
                    col.setAttribute("customWidth", "1")
                    cols.appendChild(col)
                }
                setOrdered(root, cols, WS_ORDER)
            }
        }
    }

    private fun hex(argb: Int): String = String.format("%08X", argb)

    private fun writeStyles(wb: Workbook, zip: LinkedHashMap<String, ByteArray>, wbPart: String, relsPath: String) {
        if (wb.styles.size <= wb.fileXfCount) return
        val rel = OoXml.readRels(zip, relsPath).firstOrNull { it.type.endsWith(REL_STYLES) } ?: return
        val path = OoXml.resolveTarget(wbPart, rel.target)
        val doc = OoXml.parse(zip[path] ?: return)
        val root = doc.documentElement
        fun section(name: String): Element = root.kid(name) ?: doc.elementLikeRoot(name).also { setOrdered(root, it, STYLE_ORDER) }
        val fonts = section("fonts")
        val fills = section("fills")
        val xfs = section("cellXfs")
        val baseXfs = xfs.kidsNamed("xf")

        for (i in wb.fileXfCount until wb.styles.size) {
            val st = wb.style(i)
            val base = baseXfs.getOrNull(st.baseXf)
            val xf = (base?.cloneNode(true) as? Element) ?: doc.elementLikeRoot("xf").apply {
                setAttribute("numFmtId", "0"); setAttribute("fontId", "0"); setAttribute("fillId", "0")
                setAttribute("borderId", "0"); setAttribute("xfId", "0")
            }
            // font
            val baseFont = fonts.kidsNamed("font").getOrNull(xf.attr("fontId")?.toIntOrNull() ?: 0)
            val font = (baseFont?.cloneNode(true) as? Element) ?: doc.elementLikeRoot("font")
            fun flag(name: String, on: Boolean) {
                if (on) setOrdered(font, doc.elementLikeRoot(name), FONT_ORDER)
                else font.kidsNamed(name).forEach { font.removeChild(it) }
            }
            flag("b", st.bold); flag("i", st.italic); flag("strike", st.strike); flag("u", st.underline)
            setOrdered(font, doc.elementLikeRoot("sz").apply { setAttribute("val", st.fontSize.toString().removeSuffix(".0")) }, FONT_ORDER)
            st.fontColor?.let { setOrdered(font, doc.elementLikeRoot("color").apply { setAttribute("rgb", hex(it)) }, FONT_ORDER) }
            fonts.appendChild(font)
            fonts.setAttribute("count", fonts.kidsNamed("font").size.toString())
            xf.setAttribute("fontId", (fonts.kidsNamed("font").size - 1).toString())
            xf.setAttribute("applyFont", "1")
            // fill
            if (st.fillColor != null) {
                val fill = doc.elementLikeRoot("fill")
                val pf = doc.elementLikeRoot("patternFill")
                pf.setAttribute("patternType", "solid")
                pf.appendChild(doc.elementLikeRoot("fgColor").apply { setAttribute("rgb", hex(st.fillColor)) })
                pf.appendChild(doc.elementLikeRoot("bgColor").apply { setAttribute("indexed", "64") })
                fill.appendChild(pf)
                fills.appendChild(fill)
                fills.setAttribute("count", fills.kidsNamed("fill").size.toString())
                xf.setAttribute("fillId", (fills.kidsNamed("fill").size - 1).toString())
                xf.setAttribute("applyFill", "1")
            } else if (wb.style(st.baseXf).fillColor != null) {
                xf.setAttribute("fillId", "0")
            }
            // number format
            val nfId = NumFmt.builtinId(st.numFmt) ?: run {
                val numFmts = section("numFmts")
                val existing = numFmts.kidsNamed("numFmt").firstOrNull { it.getAttribute("formatCode") == st.numFmt }
                if (existing != null) existing.attr("numFmtId")?.toIntOrNull() ?: 0
                else {
                    val id = maxOf(163, numFmts.kidsNamed("numFmt").maxOfOrNull { it.attr("numFmtId")?.toIntOrNull() ?: 0 } ?: 0) + 1
                    numFmts.appendChild(doc.elementLikeRoot("numFmt").apply {
                        setAttribute("numFmtId", id.toString()); setAttribute("formatCode", st.numFmt)
                    })
                    numFmts.setAttribute("count", numFmts.kidsNamed("numFmt").size.toString())
                    id
                }
            }
            xf.setAttribute("numFmtId", nfId.toString())
            xf.setAttribute("applyNumberFormat", "1")
            // alignment
            val al = xf.kid("alignment") ?: doc.elementLikeRoot("alignment")
            when (st.hAlign) {
                HAlign.GENERAL -> al.removeAttribute("horizontal")
                HAlign.LEFT -> al.setAttribute("horizontal", "left")
                HAlign.CENTER -> al.setAttribute("horizontal", "center")
                HAlign.RIGHT -> al.setAttribute("horizontal", "right")
            }
            if (st.wrap) al.setAttribute("wrapText", "1") else al.removeAttribute("wrapText")
            if (al.attributes.length > 0) {
                if (al.parentNode == null) xf.insertBefore(al, xf.firstChild)
                xf.setAttribute("applyAlignment", "1")
            } else if (al.parentNode != null) xf.removeChild(al)
            xfs.appendChild(xf)
        }
        xfs.setAttribute("count", xfs.kidsNamed("xf").size.toString())
        zip[path] = OoXml.serialize(doc)
        wb.fileXfCount = wb.styles.size
    }

    /* ============================== CSV ============================== */

    fun parseCsv(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var i = 0
        var quoted = false
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                delim -> { row.add(field.toString()); field.clear() }
                '\r' -> {}
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = ArrayList() }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    fun readCsv(text: String): Workbook {
        val wb = newWorkbook()
        val sh = wb.sheets[0]
        parseCsv(text.removePrefix("\uFEFF")).forEachIndexed { r, cols ->
            cols.forEachIndexed { c, v -> if (v.isNotEmpty()) sh.cells[Sheet.key(r, c)] = Cell(v) }
        }
        return wb
    }

    fun writeCsv(wb: Workbook, sh: Sheet, ev: Evaluator): String {
        val sb = StringBuilder()
        val maxR = sh.maxRow()
        val maxC = sh.maxCol()
        for (r in 0..maxR) {
            for (c in 0..maxC) {
                if (c > 0) sb.append(',')
                val s = SheetText.display(wb, ev, sh, r, c)
                if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) sb.append('"').append(s.replace("\"", "\"\"")).append('"')
                else sb.append(s)
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }
}

/** Text shown for a cell (value formatted with the cell's number format). */
object SheetText {
    fun value(ev: Evaluator, sh: Sheet, r: Int, c: Int): V {
        val cell = sh[r, c] ?: return V.Empty
        return if (cell.isFormula) ev.cached(sh, r, c) ?: V.Empty else Evaluator.literal(cell)
    }

    fun display(wb: Workbook, ev: Evaluator, sh: Sheet, r: Int, c: Int): String {
        val cell = sh[r, c] ?: return ""
        return when (val v = value(ev, sh, r, c)) {
            is V.Num -> NumFmt.format(v.v, wb.style(cell.style).numFmt)
            is V.Str -> v.v
            is V.Bool -> if (v.v) "TRUE" else "FALSE"
            is V.Err -> v.code
            else -> ""
        }
    }
}
