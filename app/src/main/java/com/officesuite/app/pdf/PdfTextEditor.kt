package com.officesuite.app.pdf

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import kotlin.math.abs

/**
 * Real (in-place) editing of existing PDF text.
 *
 * 1. The page is parsed with a PDFTextStripper subclass that records, for every glyph, which operator of the
 *    page content stream drew it (Tj / TJ / ' / ").
 * 2. Glyphs are grouped into lines; lines that share an operator are merged so an edit never cuts an operator in half.
 * 3. To replace a line, the string operands of those operators are emptied (the original characters are removed from
 *    the file, not hidden), and the new text is written at the same text matrix (position, size, rotation) and color.
 *    The original font is reused when every new character already exists in it; otherwise Helvetica or an embedded
 *    system font is used. Right-to-left / complex scripts fall back to a visual replacement done by the caller.
 */
object PdfTextEditor {

    enum class Result { OK, FALLBACK, NOT_FOUND }

    private class Glyph(val tp: TextPosition, val op: Int, val color: Int) {
        val x get() = tp.x
        val y get() = tp.y
        val w get() = tp.width
        val fs: Float get() = tp.fontSizeInPt.let { if (it >= 1f) it else maxOf(tp.height * 1.4f, 4f) }
        val uni: String get() = tp.unicode ?: ""
    }

    private class Line(val glyphs: List<Glyph>, val text: String, val box: NRect, val ops: Set<Int>, val fs: Float, val color: Int, val editable: Boolean)

    private class GlyphCollector : PDFTextStripper() {
        val glyphs = ArrayList<Glyph>()
        val opTotal: Int get() = opIndex + 1
        private var opIndex = -1
        private var currentOp = -1
        private var depth = 0

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (depth == 0) {
                opIndex++
                currentOp = opIndex
            }
            super.processOperator(operator, operands)
        }

        override fun showForm(form: PDFormXObject) {
            depth++
            try { super.showForm(form) } finally { depth-- }
        }

        override fun showTransparencyGroup(form: PDTransparencyGroup) {
            depth++
            try { super.showTransparencyGroup(form) } finally { depth-- }
        }

        override fun processTextPosition(text: TextPosition) {
            val color = try { graphicsState.nonStrokingColor.toRGB() } catch (_: Exception) { 0 }
            glyphs.add(Glyph(text, if (depth == 0) currentOp else -1, color or 0xFF000000.toInt()))
        }
    }

    private class Collected(val glyphs: List<Glyph>, val consistent: Boolean)

    private fun operatorCount(doc: PDDocument, pageIndex: Int): Int {
        val parser = PDFStreamParser(doc.getPage(pageIndex))
        parser.parse()
        return parser.tokens.count { it is Operator }
    }

    /** Collects glyphs; [Collected.consistent] is false if operator numbering can't be trusted for this page. */
    private fun collect(doc: PDDocument, pageIndex: Int): Collected {
        val c = GlyphCollector()
        c.sortByPosition = false
        c.startPage = pageIndex + 1
        c.endPage = pageIndex + 1
        c.writeText(doc, StringWriter())
        val ok = try { operatorCount(doc, pageIndex) == c.opTotal } catch (_: Exception) { false }
        return Collected(c.glyphs, ok)
    }

    private fun displaySize(doc: PDDocument, pageIndex: Int): Pair<Float, Float> {
        val page = doc.getPage(pageIndex)
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        return if (rot == 90 || rot == 270) box.height to box.width else box.width to box.height
    }

    private fun buildLines(doc: PDDocument, pageIndex: Int, glyphs: List<Glyph>): List<Line> {
        val (dw, dh) = displaySize(doc, pageIndex)
        if (glyphs.isEmpty() || dw <= 0f || dh <= 0f) return emptyList()

        // 1. cluster by baseline
        val sorted = glyphs.sortedWith(compareBy({ it.y }, { it.x }))
        val rows = ArrayList<MutableList<Glyph>>()
        for (g in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(last[0].y - g.y) <= 0.4f * maxOf(last[0].fs, g.fs)) last.add(g) else rows.add(mutableListOf(g))
        }
        // 2. split rows into segments at large gaps
        val segments = ArrayList<MutableList<Glyph>>()
        for (row in rows) {
            row.sortBy { it.x }
            var seg = mutableListOf(row[0])
            for (i in 1 until row.size) {
                val prev = row[i - 1]
                val g = row[i]
                val gap = g.x - (prev.x + prev.w)
                if (gap > maxOf(prev.fs, g.fs) * 1.6f) { segments.add(seg); seg = mutableListOf() }
                seg.add(g)
            }
            segments.add(seg)
        }
        // 3. merge segments drawn by the same operator (union-find)
        val parent = IntArray(segments.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        val byOp = HashMap<Int, Int>()
        segments.forEachIndexed { si, seg ->
            for (g in seg) if (g.op >= 0) {
                val other = byOp.putIfAbsent(g.op, si)
                if (other != null) parent[find(si)] = find(other)
            }
        }
        val groups = segments.indices.groupBy { find(it) }.values
        val lines = ArrayList<Line>()
        for (idxs in groups) {
            val gl = idxs.flatMap { segments[it] }.sortedWith(compareBy({ it.y }, { it.x }))
            val sb = StringBuilder()
            var prev: Glyph? = null
            for (g in gl) {
                val p = prev
                if (p != null) {
                    val gap = g.x - (p.x + p.w)
                    if ((gap > 0.18f * g.fs || abs(g.y - p.y) > 0.4f * g.fs) && !sb.endsWith(" ") && !g.uni.startsWith(" ")) sb.append(' ')
                }
                sb.append(g.uni)
                prev = g
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) continue
            val fs = gl.maxOf { it.fs }
            val l = gl.minOf { it.x }
            val r = gl.maxOf { it.x + it.w }
            val t = gl.minOf { it.y - it.fs * 0.85f }
            val b = gl.maxOf { it.y + it.fs * 0.25f }
            val ops = gl.map { it.op }.toSet()
            val editable = ops.none { it < 0 } && gl.none { it.uni.isEmpty() }
            lines.add(Line(gl, text, NRect(l / dw, t / dh, r / dw, b / dh), ops, fs, gl.first().color, editable))
        }
        return lines.sortedWith(compareBy({ it.box.t }, { it.box.l }))
    }

    /** Text lines of a page, in normalized display coordinates. */
    fun lines(file: File, pageIndex: Int): List<PdfTextLine> = PdfEngine.open(file).use { doc ->
        if (pageIndex !in 0 until doc.numberOfPages) return@use emptyList()
        val (dw, _) = displaySize(doc, pageIndex)
        val col = collect(doc, pageIndex)
        buildLines(doc, pageIndex, col.glyphs).map {
            PdfTextLine(pageIndex, it.text, it.box, it.ops.sorted(), it.fs / dw, it.color, it.editable && col.consistent)
        }
    }

    private fun needsShaping(text: String): Boolean = text.any { ch ->
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
            else -> {
                val script = Character.UnicodeScript.of(ch.code)
                script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON &&
                    script != Character.UnicodeScript.INHERITED && script != Character.UnicodeScript.GREEK &&
                    script != Character.UnicodeScript.CYRILLIC && script != Character.UnicodeScript.HAN &&
                    script != Character.UnicodeScript.HIRAGANA && script != Character.UnicodeScript.KATAKANA &&
                    script != Character.UnicodeScript.HANGUL
            }
        }
    }

    /** Encodes [text] with the codes the original font already uses on this page, as a TJ array. */
    private fun encodeWithOriginal(text: String, font: PDFont, pageGlyphs: List<Glyph>): COSArray? {
        val bytesPerCode = when (font) {
            is PDType0Font -> if (font.cMap?.name?.startsWith("Identity") == true) 2 else return null
            is PDSimpleFont -> 1
            else -> return null
        }
        val map = HashMap<String, Int>()
        for (g in pageGlyphs) {
            if (g.tp.font !== font) continue
            val codes = g.tp.characterCodes ?: continue
            if (codes.size == 1 && g.uni.isNotEmpty()) map.putIfAbsent(g.uni, codes[0])
        }
        val spaceAdjust = try { font.spaceWidth.let { if (it in 100f..700f) it else 250f } } catch (_: Exception) { 250f }
        val arr = COSArray()
        val cur = ByteArrayOutputStream()
        fun flush() {
            if (cur.size() > 0) { arr.add(COSString(cur.toByteArray())); cur.reset() }
        }
        var i = 0
        while (i < text.length) {
            val two = if (i + 1 < text.length) text.substring(i, i + 2) else null
            val one = text[i].toString()
            val code: Int?
            val adv: Int
            if (two != null && map.containsKey(two) && !map.containsKey(one)) { code = map[two]; adv = 2 } else { code = map[one]; adv = 1 }
            if (code == null) {
                if (text[i] == ' ') { flush(); arr.add(COSFloat(-spaceAdjust)); i++; continue }
                return null
            }
            if (bytesPerCode == 2) { cur.write((code shr 8) and 0xFF); cur.write(code and 0xFF) } else cur.write(code and 0xFF)
            i += adv
        }
        flush()
        return arr
    }

    private val SYSTEM_FONTS = listOf(
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSans.ttf"
    )

    private fun systemFont(doc: PDDocument): PDType0Font? {
        for (path in SYSTEM_FONTS) {
            val f = File(path)
            if (!f.exists()) continue
            try { return PDType0Font.load(doc, f) } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Replaces [target] on page [pageIndex] of [src] with [newText] (empty = delete) and writes [out].
     * Returns FALLBACK when the text cannot be rewritten in place (caller then replaces it visually).
     */
    fun replaceLine(src: File, out: File, pageIndex: Int, target: PdfTextLine, newText: String): Result {
        if (newText.isNotEmpty() && needsShaping(newText)) return Result.FALLBACK
        PdfEngine.open(src).use { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) return Result.NOT_FOUND
            val page = doc.getPage(pageIndex)
            val col = collect(doc, pageIndex)
            if (!col.consistent) return Result.FALLBACK
            val glyphs = col.glyphs
            val lines = buildLines(doc, pageIndex, glyphs)
            val wanted = target.ops.toSet()
            val line = lines.firstOrNull { it.ops == wanted }
                ?: lines.firstOrNull { it.text == target.text && it.box.overlaps(target.box) }
                ?: return Result.NOT_FOUND
            if (!line.editable) return Result.FALLBACK

            // choose font and encode the replacement
            val first = line.glyphs.minByOrNull { it.x } ?: return Result.NOT_FOUND
            var showOperand: COSBase? = null
            var font: PDFont? = null
            if (newText.isNotEmpty()) {
                val orig = first.tp.font
                if (orig != null) {
                    encodeWithOriginal(newText, orig, glyphs)?.let { showOperand = it; font = orig }
                }
                if (showOperand == null) {
                    try {
                        val std = PDType1Font.HELVETICA
                        showOperand = COSString(std.encode(newText))
                        font = std
                    } catch (_: Exception) {
                        val sys = systemFont(doc)
                        if (sys != null) try {
                            showOperand = COSString(sys.encode(newText)); font = sys
                        } catch (_: Exception) {
                        }
                    }
                }
                if (showOperand == null) return Result.FALLBACK
            }

            // remove the original characters from the content stream
            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens: MutableList<Any?> = parser.tokens
            var opCount = -1
            for (i in tokens.indices) {
                val t = tokens[i]
                if (t is Operator) {
                    opCount++
                    if (opCount in line.ops && i > 0) {
                        when (t.name) {
                            "Tj", "'", "\"" -> if (tokens[i - 1] is COSString) tokens[i - 1] = COSString(ByteArray(0))
                            "TJ" -> if (tokens[i - 1] is COSArray) tokens[i - 1] = COSArray()
                        }
                    }
                }
            }

            val outTokens = ArrayList<Any?>(tokens.size + 32)
            outTokens.add(Operator.getOperator("q"))
            outTokens.addAll(tokens)
            outTokens.add(Operator.getOperator("Q"))

            val f = font
            val operand = showOperand
            if (f != null && operand != null) {
                val resources = page.resources ?: PDResources().also { page.resources = it }
                val fontName: COSName = resources.add(f)
                val m = first.tp.textMatrix
                val box = page.cropBox
                val c = line.color
                outTokens.addAll(
                    listOf(
                        Operator.getOperator("q"),
                        Operator.getOperator("BT"),
                        fontName, COSInteger.ONE, Operator.getOperator("Tf"),
                        COSFloat(((c shr 16) and 0xFF) / 255f), COSFloat(((c shr 8) and 0xFF) / 255f), COSFloat((c and 0xFF) / 255f),
                        Operator.getOperator("rg"),
                        COSFloat(m.scaleX), COSFloat(m.shearY), COSFloat(m.shearX), COSFloat(m.scaleY),
                        COSFloat(m.translateX + box.lowerLeftX), COSFloat(m.translateY + box.lowerLeftY),
                        Operator.getOperator("Tm"),
                        operand, Operator.getOperator(if (operand is COSArray) "TJ" else "Tj"),
                        Operator.getOperator("ET"),
                        Operator.getOperator("Q")
                    )
                )
            }

            val stream = PDStream(doc)
            stream.createOutputStream(COSName.FLATE_DECODE).use { os -> ContentStreamWriter(os).writeTokens(outTokens) }
            page.setContents(stream)
            doc.save(out)
            return Result.OK
        }
    }
}
