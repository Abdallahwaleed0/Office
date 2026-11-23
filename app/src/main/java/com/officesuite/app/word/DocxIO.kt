package com.officesuite.app.word

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import com.officesuite.app.core.OoXml
import com.officesuite.app.core.Templates
import com.officesuite.app.core.descendants
import com.officesuite.app.core.kid
import com.officesuite.app.core.kids
import com.officesuite.app.core.kidsNamed
import com.officesuite.app.core.removeAllChildren
import com.officesuite.app.core.removeKids
import com.officesuite.app.core.setOrdered
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.math.roundToInt

/** An opened .docx package plus the parts of its XML the editor keeps around. */
class WordDocument(
    val zip: LinkedHashMap<String, ByteArray>,
    val partPath: String,
    val dom: Document,
    val body: Element,
    val sectPr: Element?,
    /** lowercase style name -> styleId */
    val styleIdByName: Map<String, String>,
    /** styleId -> lowercase style name */
    val styleNameById: Map<String, String>,
)

object DocxIO {
    private const val W = OoXml.W
    private const val EMU_PER_PX = 9525.0

    private val RPR_ORDER = listOf(
        "rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow", "emboss",
        "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern", "position", "sz", "szCs",
        "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout",
        "specVanish", "oMath", "rPrChange"
    )
    private val PPR_ORDER = listOf(
        "pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr", "suppressLineNumbers",
        "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct", "autoSpaceDE",
        "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents",
        "suppressOverlap", "jc", "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle",
        "rPr", "sectPr", "pPrChange"
    )

    private val HIGHLIGHT = mapOf(
        "yellow" to 0xFFFFFF00.toInt(), "green" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(), "blue" to 0xFF0000FF.toInt(), "red" to 0xFFFF0000.toInt(),
        "darkBlue" to 0xFF000080.toInt(), "darkCyan" to 0xFF008080.toInt(), "darkGreen" to 0xFF008000.toInt(),
        "darkMagenta" to 0xFF800080.toInt(), "darkRed" to 0xFF800000.toInt(), "darkYellow" to 0xFF808000.toInt(),
        "darkGray" to 0xFF808080.toInt(), "lightGray" to 0xFFC0C0C0.toInt(), "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt()
    )

    private fun Element.wv(): String? = getAttributeNS(W, "val").ifEmpty { null }
    private fun on(e: Element?): Boolean {
        if (e == null) return false
        val v = e.wv() ?: return true
        return v != "0" && v != "false" && v != "off" && v != "none"
    }

    private fun parseHex(s: String?): Int? {
        if (s == null || s == "auto" || s.length != 6) return null
        return s.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
    }

    fun headingLevel(styleName: String?): Int? {
        val n = styleName?.lowercase() ?: return null
        if (n == "title") return 0
        if (n.startsWith("heading ")) return n.removePrefix("heading ").trim().toIntOrNull()?.coerceIn(1, 3)
        return null
    }

    /* ================================ READ ================================ */

    private class ReadCtx(
        val zip: Map<String, ByteArray>,
        val part: String,
        val res: Resources,
        val density: Float,
        val maxWidth: Int,
        val rels: Map<String, OoXml.Rel>,
        val styleNameById: Map<String, String>,
        val numFormats: Map<String, String>,
        val dom: Document,
    ) {
        val sb = SpannableStringBuilder()
        val paraSpans = ArrayList<Triple<Any, Int, Int>>()
    }

    fun read(zip: LinkedHashMap<String, ByteArray>, res: Resources, maxWidthPx: Int): Pair<WordDocument, SpannableStringBuilder> {
        val part = OoXml.mainPart(zip, "word/document.xml")
        val dom = OoXml.parse(zip[part] ?: throw IllegalArgumentException("This is not a Word document"))
        val body = dom.documentElement.kid("body") ?: throw IllegalArgumentException("The document has no body")
        val rels = OoXml.readRels(zip, OoXml.relsPathFor(part)).associateBy { it.id }

        val idByName = HashMap<String, String>()
        val nameById = HashMap<String, String>()
        rels.values.firstOrNull { it.type.endsWith("/styles") }?.let { zip[OoXml.resolveTarget(part, it.target)] }?.let { bytes ->
            try {
                for (s in OoXml.parse(bytes).documentElement.kidsNamed("style")) {
                    val id = s.getAttributeNS(W, "styleId")
                    val name = s.kid("name")?.wv()?.lowercase() ?: continue
                    if (id.isNotEmpty()) { idByName.putIfAbsent(name, id); nameById[id] = name }
                }
            } catch (_: Exception) {
            }
        }
        // numbering formats: "numId:ilvl" -> numFmt
        val numFormats = HashMap<String, String>()
        rels.values.firstOrNull { it.type.endsWith("/numbering") }?.let { zip[OoXml.resolveTarget(part, it.target)] }?.let { bytes ->
            try {
                val root = OoXml.parse(bytes).documentElement
                val abs = root.kidsNamed("abstractNum").associateBy { it.getAttributeNS(W, "abstractNumId") }
                for (num in root.kidsNamed("num")) {
                    val numId = num.getAttributeNS(W, "numId")
                    val a = abs[num.kid("abstractNumId")?.wv()] ?: continue
                    for (lvl in a.kidsNamed("lvl")) numFormats["$numId:${lvl.getAttributeNS(W, "ilvl")}"] = lvl.kid("numFmt")?.wv() ?: "bullet"
                }
            } catch (_: Exception) {
            }
        }

        val density = res.displayMetrics.density
        val ctx = ReadCtx(zip, part, res, density, maxWidthPx, rels, nameById, numFormats, dom)
        val sb = ctx.sb
        var sectPr: Element? = null
        var first = true
        for (el in body.kids()) {
            when (el.localName) {
                "sectPr" -> { sectPr = el; continue }
                "bookmarkStart", "bookmarkEnd", "proofErr", "permStart", "permEnd", "commentRangeStart", "commentRangeEnd" -> continue
            }
            if (!first) sb.append('\n')
            first = false
            when (el.localName) {
                "p" -> readParagraph(ctx, el)
                "tbl" -> {
                    val st = sb.length
                    sb.append(OBJ)
                    sb.setSpan(TableSpan(readTable(el), maxWidthPx, density), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    val st = sb.length
                    sb.append(OBJ)
                    val label = el.textContent?.trim()?.replace(Regex("\\s+"), " ")?.take(80).orEmpty()
                        .ifEmpty { "Content block" }
                    sb.setSpan(OpaqueBlockSpan(el.cloneNode(true) as Element, label, maxWidthPx, density), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        for ((span, s, e0) in ctx.paraSpans) {
            val e = if (e0 < sb.length && sb[e0] == '\n') e0 + 1 else e0
            if (e > s) sb.setSpan(span, s, e, Spanned.SPAN_PARAGRAPH)
        }
        val doc = WordDocument(zip, part, dom, body, sectPr, idByName, nameById)
        return doc to sb
    }

    private fun readParagraph(ctx: ReadCtx, p: Element) {
        val sb = ctx.sb
        val start = sb.length
        readInline(ctx, p)
        val end = sb.length
        val pPr = p.kid("pPr") ?: return
        ctx.paraSpans.add(Triple(ParaPropsSpan(pPr), start, end))
        when (pPr.kid("jc")?.wv()) {
            "center" -> ctx.paraSpans.add(Triple(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end))
            "right", "end" -> ctx.paraSpans.add(Triple(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), start, end))
        }
        headingLevel(ctx.styleNameById[pPr.kid("pStyle")?.wv()])?.let {
            ctx.paraSpans.add(Triple(HeadingSpan(it), start, end))
        }
        pPr.kid("numPr")?.let { np ->
            val numId = np.kid("numId")?.wv()
            if (numId != null && numId != "0") {
                val ilvl = np.kid("ilvl")?.wv()?.toIntOrNull() ?: 0
                val fmt = ctx.numFormats["$numId:$ilvl"] ?: "bullet"
                ctx.paraSpans.add(Triple(ListSpan(numId, ilvl, ctx.density, fmt != "bullet" && fmt != "none"), start, end))
            }
        }
    }

    private fun readInline(ctx: ReadCtx, container: Element) {
        for (k in container.kids()) when (k.localName) {
            "r" -> readRun(ctx, k)
            "hyperlink", "ins", "smartTag", "customXml", "fldSimple", "moveTo", "dir", "bdo" -> readInline(ctx, k)
            "sdt" -> k.kid("sdtContent")?.let { readInline(ctx, it) }
            "oMath", "oMathPara" -> {
                val st = ctx.sb.length
                ctx.sb.append(OBJ)
                ctx.sb.setSpan(InlineOpaqueSpan(k.cloneNode(true) as Element, "Equation", ctx.density), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            else -> {}
        }
    }

    private fun readRun(ctx: ReadCtx, r: Element) {
        val sb = ctx.sb
        val rPr = r.kid("rPr")
        var segStart = sb.length
        for (c in r.kids()) when (c.localName) {
            "t" -> sb.append(c.textContent ?: "")
            "tab", "ptab" -> sb.append('\t')
            "br", "cr" -> sb.append('\n')
            "noBreakHyphen" -> sb.append('\u2011')
            "drawing", "pict", "object" -> {
                applyRunSpans(sb, segStart, sb.length, rPr)
                val nr = ctx.dom.createElementNS(W, "w:r")
                rPr?.let { nr.appendChild(it.cloneNode(true)) }
                nr.appendChild(c.cloneNode(true))
                appendImage(ctx, c, nr)
                segStart = sb.length
            }
        }
        applyRunSpans(sb, segStart, sb.length, rPr)
    }

    private fun appendImage(ctx: ReadCtx, drawing: Element, run: Element) {
        val sb = ctx.sb
        val st = sb.length
        sb.append(OBJ)
        val blip = drawing.descendants("blip").firstOrNull()
        val rid = blip?.getAttributeNS(OoXml.R, "embed")?.ifEmpty { null }
            ?: drawing.descendants("imagedata").firstOrNull()?.getAttributeNS(OoXml.R, "id")?.ifEmpty { null }
        val bytes = rid?.let { ctx.rels[it] }?.let { ctx.zip[OoXml.resolveTarget(ctx.part, it.target)] }
        var bmp: Bitmap? = null
        if (bytes != null) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (o.outWidth / sample > 1600) sample *= 2
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Throwable) {
            }
        }
        if (bmp == null) {
            sb.setSpan(InlineOpaqueSpan(run, "Image", ctx.density), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }
        val ext = drawing.descendants("extent").firstOrNull()
        val cx = ext?.getAttribute("cx")?.toLongOrNull() ?: (bmp.width * EMU_PER_PX).toLong()
        val cy = ext?.getAttribute("cy")?.toLongOrNull() ?: (bmp.height * EMU_PER_PX).toLong()
        sb.setSpan(makeImageSpan(ctx.res, bmp, run, null, cx, cy, ctx.maxWidth), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun makeImageSpan(res: Resources, bmp: Bitmap, run: Element?, bytes: ByteArray?, cx: Long, cy: Long, maxWidth: Int, ext: String = "png"): DocImageSpan {
        val density = res.displayMetrics.density
        var w = (cx / EMU_PER_PX * density).toFloat()
        var h = (cy / EMU_PER_PX * density).toFloat()
        if (w <= 0f || h <= 0f) { w = bmp.width.toFloat(); h = bmp.height.toFloat() }
        if (w > maxWidth) { h *= maxWidth / w; w = maxWidth.toFloat() }
        val d = BitmapDrawable(res, bmp)
        d.setBounds(0, 0, w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
        return DocImageSpan(d, run, bytes, bmp, cx, cy, ext)
    }

    private fun applyRunSpans(sb: SpannableStringBuilder, s: Int, e: Int, rPr: Element?) {
        if (rPr == null || e <= s) return
        val f = Spanned.SPAN_EXCLUSIVE_INCLUSIVE
        if (on(rPr.kid("b"))) sb.setSpan(StyleSpan(Typeface.BOLD), s, e, f)
        if (on(rPr.kid("i"))) sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, f)
        rPr.kid("u")?.let { if (on(it)) sb.setSpan(UnderlineSpan(), s, e, f) }
        if (on(rPr.kid("strike")) || on(rPr.kid("dstrike"))) sb.setSpan(StrikethroughSpan(), s, e, f)
        rPr.kid("sz")?.wv()?.toIntOrNull()?.let { hp -> sb.setSpan(AbsoluteSizeSpan((hp / 2f * 4f / 3f).roundToInt(), true), s, e, f) }
        parseHex(rPr.kid("color")?.wv())?.let { sb.setSpan(ForegroundColorSpan(it), s, e, f) }
        val hl = rPr.kid("highlight")?.wv()?.let { HIGHLIGHT[it] } ?: parseHex(rPr.kid("shd")?.getAttributeNS(W, "fill"))
        hl?.let { sb.setSpan(BackgroundColorSpan(it), s, e, f) }
        when (rPr.kid("vertAlign")?.wv()) {
            "superscript" -> sb.setSpan(SuperscriptSpan(), s, e, f)
            "subscript" -> sb.setSpan(SubscriptSpan(), s, e, f)
        }
        sb.setSpan(RunPropsSpan(rPr), s, e, f)
    }

    private fun paraText(p: Element): String = p.descendants("t").joinToString("") { it.textContent ?: "" }

    private fun readTable(tbl: Element): TableData {
        val rows = tbl.kidsNamed("tr").map { tr ->
            tr.kidsNamed("tc").map { tc -> tc.kidsNamed("p").joinToString("\n") { paraText(it) } }.toMutableList()
        }.toMutableList()
        if (rows.isEmpty()) rows.add(mutableListOf(""))
        return TableData(rows, tbl, false)
    }

    /* ================================ WRITE ================================ */

    private class WriteCtx(val doc: WordDocument, val density: Float) {
        var bulletNumId: String? = null
        var docPrId = 7000
    }

    private fun Document.w(local: String): Element = createElementNS(W, "w:$local")
    private fun Element.setVal(v: String): Element { setAttributeNS(W, "w:val", v); return this }

    /** Writes [text] into the document and returns the bytes of the .docx package. */
    fun write(doc: WordDocument, text: Spanned, density: Float): ByteArray {
        val ctx = WriteCtx(doc, density)
        val body = doc.body
        body.removeAllChildren()
        val len = text.length
        var ps = 0
        while (true) {
            var pe = TextUtils.indexOf(text, '\n', ps)
            if (pe < 0) pe = len
            writeParagraph(ctx, text, ps, pe)
            if (pe >= len) break
            ps = pe + 1
        }
        doc.sectPr?.let { body.appendChild(it.cloneNode(true)) }
        doc.zip[doc.partPath] = OoXml.serialize(doc.dom)
        return OoXml.zipBytes(doc.zip)
    }

    private fun <T> paraSpan(text: Spanned, ps: Int, pe: Int, cls: Class<T>): T? {
        val q2 = if (pe > ps) pe else minOf(ps + 1, text.length)
        return text.getSpans(ps, q2, cls).firstOrNull {
            val s = text.getSpanStart(it)
            val e = text.getSpanEnd(it)
            s <= ps && e > ps
        }
    }

    private fun writeParagraph(ctx: WriteCtx, text: Spanned, ps: Int, pe: Int) {
        val dom = ctx.doc.dom
        val body = ctx.doc.body
        if (pe - ps == 1 && text[ps] == OBJ) {
            text.getSpans(ps, pe, TableSpan::class.java).firstOrNull()?.let {
                body.appendChild(tableElement(ctx, it.table).cloneNode(true)); return
            }
            text.getSpans(ps, pe, OpaqueBlockSpan::class.java).firstOrNull()?.let {
                body.appendChild(it.element.cloneNode(true)); return
            }
        }
        val p = dom.w("p")
        val pPr = (paraSpan(text, ps, pe, ParaPropsSpan::class.java)?.pPr?.cloneNode(true) as? Element) ?: dom.w("pPr")

        // heading style
        val heading = paraSpan(text, ps, pe, HeadingSpan::class.java)
        val curStyle = pPr.kid("pStyle")?.wv()
        val curIsHeading = headingLevel(ctx.doc.styleNameById[curStyle]) != null
        var fallbackHeading: Int? = null
        if (heading != null) {
            val name = if (heading.level == 0) "title" else "heading ${heading.level}"
            val id = ctx.doc.styleIdByName[name]
            if (id != null) setOrdered(pPr, dom.w("pStyle").setVal(id), PPR_ORDER)
            else {
                if (curIsHeading) removeKids(pPr, "pStyle")
                fallbackHeading = heading.level
            }
        } else if (curIsHeading) removeKids(pPr, "pStyle")

        // alignment
        paraSpan(text, ps, pe, AlignmentSpan::class.java)?.let { a ->
            when (a.alignment) {
                Layout.Alignment.ALIGN_CENTER -> setOrdered(pPr, dom.w("jc").setVal("center"), PPR_ORDER)
                Layout.Alignment.ALIGN_OPPOSITE -> setOrdered(pPr, dom.w("jc").setVal("right"), PPR_ORDER)
                else -> removeKids(pPr, "jc")
            }
        }

        // lists
        val list = paraSpan(text, ps, pe, ListSpan::class.java)
        if (list != null) {
            val numId = list.numId ?: bulletNumId(ctx)
            val numPr = dom.w("numPr")
            numPr.appendChild(dom.w("ilvl").setVal(list.ilvl.toString()))
            numPr.appendChild(dom.w("numId").setVal(numId))
            setOrdered(pPr, numPr, PPR_ORDER)
            if (list.numId == null && pPr.kid("ind") == null) {
                val ind = dom.w("ind")
                ind.setAttributeNS(W, "w:left", (720 * (list.ilvl + 1)).toString())
                ind.setAttributeNS(W, "w:hanging", "360")
                setOrdered(pPr, ind, PPR_ORDER)
            }
        } else removeKids(pPr, "numPr")

        if (pPr.hasChildNodes() || pPr.hasAttributes()) p.appendChild(pPr)

        // runs
        var i = ps
        while (i < pe) {
            if (text[i] == OBJ) {
                val inl = text.getSpans(i, i + 1, PreservedInline::class.java).firstOrNull()
                if (inl != null) inlineElement(ctx, inl)?.let { p.appendChild(it.cloneNode(true)) }
                i++
                continue
            }
            var next = text.nextSpanTransition(i, pe, CharacterStyle::class.java)
            val obj = TextUtils.indexOf(text, OBJ, i, next)
            if (obj in (i + 1) until next) next = obj
            p.appendChild(runElement(ctx, text, i, next, fallbackHeading))
            i = next
        }
        body.appendChild(p)
    }

    private fun runElement(ctx: WriteCtx, text: Spanned, a: Int, b: Int, fallbackHeading: Int?): Element {
        val dom = ctx.doc.dom
        val r = dom.w("r")
        val spans = text.getSpans(a, b, CharacterStyle::class.java).filter {
            val s = text.getSpanStart(it); val e = text.getSpanEnd(it)
            s != e && s <= a && e >= b
        }
        val rPr = (spans.filterIsInstance<RunPropsSpan>().firstOrNull()?.rPr?.cloneNode(true) as? Element) ?: dom.w("rPr")
        fun flag(name: String, value: Boolean) {
            if (value) setOrdered(rPr, dom.w(name), RPR_ORDER) else removeKids(rPr, name)
        }
        val bold = fallbackHeading != null || spans.any { it is StyleSpan && (it.style and Typeface.BOLD) != 0 }
        val italic = spans.any { it is StyleSpan && (it.style and Typeface.ITALIC) != 0 }
        flag("b", bold)
        if (!bold) removeKids(rPr, "bCs")
        flag("i", italic)
        if (!italic) removeKids(rPr, "iCs")
        if (spans.any { it is UnderlineSpan }) {
            if (rPr.kid("u") == null || !on(rPr.kid("u"))) setOrdered(rPr, dom.w("u").setVal("single"), RPR_ORDER)
        } else removeKids(rPr, "u")
        if (spans.any { it is StrikethroughSpan }) {
            if (rPr.kid("dstrike") == null) flag("strike", true)
        } else removeKids(rPr, "strike", "dstrike")

        val size = spans.filterIsInstance<AbsoluteSizeSpan>().lastOrNull()
        val halfPoints: Int? = when {
            size != null -> (if (size.dip) size.size * 0.75f * 2f else size.size / ctx.density * 0.75f * 2f).roundToInt()
            fallbackHeading != null -> when (fallbackHeading) { 0 -> 56; 1 -> 36; 2 -> 30; else -> 26 }
            else -> null
        }
        if (halfPoints != null) {
            setOrdered(rPr, dom.w("sz").setVal(halfPoints.toString()), RPR_ORDER)
            setOrdered(rPr, dom.w("szCs").setVal(halfPoints.toString()), RPR_ORDER)
        } else removeKids(rPr, "sz", "szCs")

        val fg = spans.filterIsInstance<ForegroundColorSpan>().lastOrNull()
        if (fg != null) setOrdered(rPr, dom.w("color").setVal(String.format("%06X", fg.foregroundColor and 0xFFFFFF)), RPR_ORDER)
        else removeKids(rPr, "color")

        val bg = spans.filterIsInstance<BackgroundColorSpan>().lastOrNull()
        removeKids(rPr, "highlight")
        if (bg != null) {
            val shd = dom.w("shd")
            shd.setAttributeNS(W, "w:val", "clear")
            shd.setAttributeNS(W, "w:color", "auto")
            shd.setAttributeNS(W, "w:fill", String.format("%06X", bg.backgroundColor and 0xFFFFFF))
            setOrdered(rPr, shd, RPR_ORDER)
        } else removeKids(rPr, "shd")

        if (rPr.hasChildNodes()) r.appendChild(rPr)

        val s = text.subSequence(a, b).toString()
        val sb = StringBuilder()
        fun flushText() {
            if (sb.isEmpty()) return
            val t = dom.w("t")
            t.setAttributeNS(OoXml.XML_NS, "xml:space", "preserve")
            t.textContent = sb.toString()
            r.appendChild(t)
            sb.clear()
        }
        for (ch in s) {
            when (ch) {
                '\t' -> { flushText(); r.appendChild(dom.w("tab")) }
                '\u2011' -> { flushText(); r.appendChild(dom.w("noBreakHyphen")) }
                else -> if (ch.code >= 0x20 || ch == '\u0009') sb.append(ch)
            }
        }
        flushText()
        return r
    }

    private fun inlineElement(ctx: WriteCtx, span: PreservedInline): Element? {
        if (span is DocImageSpan && span.element == null && span.bytes != null) span.element = newImageRun(ctx, span)
        return span.element
    }

    private fun newImageRun(ctx: WriteCtx, span: DocImageSpan): Element {
        val doc = ctx.doc
        val ext = span.ext
        var n = 1
        while (doc.zip.containsKey("word/media/image$n.$ext")) n++
        doc.zip["word/media/image$n.$ext"] = span.bytes!!
        OoXml.ensureDefault(doc.zip, ext, if (ext == "png") "image/png" else "image/jpeg")
        val rid = OoXml.addRel(doc.zip, OoXml.relsPathFor(doc.partPath), Templates.REL_IMAGE, "media/image$n.$ext")
        val id = ctx.docPrId++ + n
        val cx = span.emuW
        val cy = span.emuH
        val xml = "<w:r xmlns:w=\"${OoXml.W}\" xmlns:wp=\"${OoXml.WP}\" xmlns:a=\"${OoXml.A}\" xmlns:pic=\"${OoXml.PIC}\" xmlns:r=\"${OoXml.R}\">" +
            "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"$id\" name=\"Picture $id\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>" +
            "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"image$n.$ext\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
        return doc.dom.importNode(OoXml.parse(xml).documentElement, true) as Element
    }

    /** numId of a bullet list definition, created in numbering.xml when the document has none. */
    private fun bulletNumId(ctx: WriteCtx): String {
        ctx.bulletNumId?.let { return it }
        val doc = ctx.doc
        val relsPath = OoXml.relsPathFor(doc.partPath)
        val rel = OoXml.readRels(doc.zip, relsPath).firstOrNull { it.type.endsWith("/numbering") }
        val path: String
        val ndoc: Document
        if (rel != null && doc.zip.containsKey(OoXml.resolveTarget(doc.partPath, rel.target))) {
            path = OoXml.resolveTarget(doc.partPath, rel.target)
            ndoc = OoXml.parse(doc.zip.getValue(path))
        } else {
            path = "word/numbering.xml"
            ndoc = OoXml.parse("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:numbering xmlns:w=\"${OoXml.W}\"/>")
            OoXml.addRel(doc.zip, relsPath, Templates.REL_NUMBERING, "numbering.xml")
            OoXml.addOverride(doc.zip, path, Templates.CT_NUMBERING)
        }
        val root = ndoc.documentElement
        val absId = (root.kidsNamed("abstractNum").maxOfOrNull { it.getAttributeNS(W, "abstractNumId").toIntOrNull() ?: 0 } ?: -1) + 1
        val numId = (root.kidsNamed("num").maxOfOrNull { it.getAttributeNS(W, "numId").toIntOrNull() ?: 0 } ?: 0) + 1
        val lvls = listOf("\u2022", "\u25E6", "\u25AA").mapIndexed { i, sym ->
            "<w:lvl w:ilvl=\"$i\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"$sym\"/><w:lvlJc w:val=\"left\"/>" +
                "<w:pPr><w:ind w:left=\"${720 * (i + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        }.joinToString("")
        val absXml = "<w:abstractNum xmlns:w=\"${OoXml.W}\" w:abstractNumId=\"$absId\"><w:multiLevelType w:val=\"hybridMultilevel\"/>$lvls</w:abstractNum>"
        val numXml = "<w:num xmlns:w=\"${OoXml.W}\" w:numId=\"$numId\"><w:abstractNumId w:val=\"$absId\"/></w:num>"
        val absEl = ndoc.importNode(OoXml.parse(absXml).documentElement, true)
        val numEl = ndoc.importNode(OoXml.parse(numXml).documentElement, true)
        val firstNum = root.kidsNamed("num").firstOrNull()
        if (firstNum != null) root.insertBefore(absEl, firstNum) else root.appendChild(absEl)
        root.appendChild(numEl)
        doc.zip[path] = OoXml.serialize(ndoc)
        ctx.bulletNumId = numId.toString()
        return numId.toString()
    }

    private fun tableElement(ctx: WriteCtx, t: TableData): Element {
        val dom = ctx.doc.dom
        val orig = t.element
        if (orig != null && !t.dirty) return orig
        if (orig != null) {
            val trs = orig.kidsNamed("tr")
            val sameShape = trs.size == t.rows.size && trs.indices.all { trs[it].kidsNamed("tc").size == t.rows[it].size }
            if (sameShape) {
                for ((ri, tr) in trs.withIndex()) for ((ci, tc) in tr.kidsNamed("tc").withIndex()) {
                    val paras = tc.kidsNamed("p")
                    val firstP = paras.firstOrNull()
                    val pPr = firstP?.kid("pPr")?.cloneNode(true) as? Element
                    val rPr = firstP?.descendants("rPr")?.firstOrNull { it.parentNode is Element && (it.parentNode as Element).localName == "r" }
                        ?.cloneNode(true) as? Element
                    for (k in tc.kids()) if (k.localName == "p" || k.localName == "tbl") tc.removeChild(k)
                    for (line in t.rows[ri][ci].split('\n')) tc.appendChild(cellParagraph(dom, line, pPr?.cloneNode(true) as? Element, rPr?.cloneNode(true) as? Element))
                }
                t.dirty = false
                return orig
            }
        }
        val cols = maxOf(1, t.cols)
        val tbl = dom.w("tbl")
        val tblPr = dom.w("tblPr")
        tblPr.appendChild(dom.w("tblW").apply { setAttributeNS(W, "w:w", "0"); setAttributeNS(W, "w:type", "auto") })
        val borders = dom.w("tblBorders")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            borders.appendChild(dom.w(side).apply {
                setAttributeNS(W, "w:val", "single"); setAttributeNS(W, "w:sz", "4")
                setAttributeNS(W, "w:space", "0"); setAttributeNS(W, "w:color", "auto")
            })
        }
        tblPr.appendChild(borders)
        tblPr.appendChild(dom.w("tblLook").apply { setAttributeNS(W, "w:val", "04A0") })
        tbl.appendChild(tblPr)
        val grid = dom.w("tblGrid")
        val colW = 9000 / cols
        repeat(cols) { grid.appendChild(dom.w("gridCol").apply { setAttributeNS(W, "w:w", colW.toString()) }) }
        tbl.appendChild(grid)
        for (row in t.rows) {
            val tr = dom.w("tr")
            for (c in 0 until cols) {
                val tc = dom.w("tc")
                val tcPr = dom.w("tcPr")
                tcPr.appendChild(dom.w("tcW").apply { setAttributeNS(W, "w:w", colW.toString()); setAttributeNS(W, "w:type", "dxa") })
                tc.appendChild(tcPr)
                for (line in (row.getOrNull(c) ?: "").split('\n')) tc.appendChild(cellParagraph(dom, line, null, null))
                tr.appendChild(tc)
            }
            tbl.appendChild(tr)
        }
        t.element = tbl
        t.dirty = false
        return tbl
    }

    private fun cellParagraph(dom: Document, text: String, pPr: Element?, rPr: Element?): Element {
        val p = dom.w("p")
        pPr?.let { p.appendChild(it) }
        if (text.isNotEmpty()) {
            val r = dom.w("r")
            rPr?.let { r.appendChild(it) }
            val t = dom.w("t")
            t.setAttributeNS(OoXml.XML_NS, "xml:space", "preserve")
            t.textContent = text
            r.appendChild(t)
            p.appendChild(r)
        }
        return p
    }

    /** Plain text of a document (used for .txt export). */
    fun plainText(text: CharSequence): String = text.toString().replace(OBJ.toString(), "")
}
