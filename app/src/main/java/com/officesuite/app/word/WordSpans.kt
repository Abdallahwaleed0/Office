package com.officesuite.app.word

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.CharacterStyle
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ParagraphStyle
import android.text.style.ReplacementSpan
import org.w3c.dom.Element

/** Object replacement character used for tables, images and other embedded objects. */
const val OBJ = '\uFFFC'

/** Original paragraph properties (spacing, indentation, borders, ...) kept so they survive editing. */
class ParaPropsSpan(val pPr: Element) : ParagraphStyle

/** Original run properties (fonts, language, effects, ...) kept so they survive editing. */
class RunPropsSpan(val rPr: Element) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) {}
}

/** Title (level 0) or Heading 1..3. */
class HeadingSpan(val level: Int) : MetricAffectingSpan(), ParagraphStyle {
    val factor: Float
        get() = when (level) { 0 -> 1.9f; 1 -> 1.55f; 2 -> 1.3f; else -> 1.15f }

    private fun style(tp: TextPaint) {
        tp.textSize = tp.textSize * factor
        tp.typeface = Typeface.create(tp.typeface, Typeface.BOLD)
    }

    override fun updateMeasureState(tp: TextPaint) = style(tp)
    override fun updateDrawState(tp: TextPaint) = style(tp)
}

/** Bulleted or numbered list paragraph. [numId] null = new bullet list created in the app. */
class ListSpan(val numId: String?, val ilvl: Int, val density: Float, val numbered: Boolean = false) : LeadingMarginSpan, ParagraphStyle {
    private val indentPx get() = 22f * density

    override fun getLeadingMargin(first: Boolean): Int = ((ilvl + 1) * indentPx).toInt()

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?
    ) {
        if (!first) return
        if (start > 0 && text[start - 1] != '\n') return
        val old = p.style
        p.style = Paint.Style.FILL
        val left = x + dir * (ilvl * indentPx)
        if (numbered && text is Spanned) {
            val label = "${number(text, start)}."
            val w = p.measureText(label)
            c.drawText(label, if (dir > 0) left else left - w, baseline.toFloat(), p)
        } else {
            val r = 2.6f * density
            val cx = left + dir * (7f * density)
            c.drawCircle(cx, baseline - p.textSize * 0.32f, r, p)
        }
        p.style = old
    }

    /** Position of the paragraph starting at [paraStart] within its list. */
    private fun number(text: Spanned, paraStart: Int): Int {
        var n = 1
        var p = paraStart
        while (p > 0) {
            val prevStart = TextUtils.lastIndexOf(text, '\n', p - 2) + 1
            val spans = text.getSpans(prevStart, maxOf(prevStart, p - 1), ListSpan::class.java)
                .filter { text.getSpanStart(it) <= prevStart && text.getSpanEnd(it) >= p - 1 }
            val ls = spans.firstOrNull() ?: break
            if (ls.numId != numId) break
            if (ls.ilvl == ilvl) n++ else if (ls.ilvl < ilvl) break
            p = prevStart
        }
        return n
    }
}

/** Something that is written back as an XML element inside a paragraph (images, equations, ...). */
interface PreservedInline {
    val element: Element?
}

/** Table contents. [element] is the original w:tbl (null for tables created in the app). */
class TableData(val rows: MutableList<MutableList<String>>, var element: Element?, var dirty: Boolean = false) {
    val cols: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

class TableSpan(val table: TableData, var widthPx: Int, val density: Float) : ReplacementSpan() {
    private fun rowH(p: Paint) = p.textSize * 1.8f

    fun heightPx(p: Paint): Int = (rowH(p) * maxOf(1, table.rows.size) + 6 * density).toInt()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val h = heightPx(paint)
            fm.ascent = -h; fm.top = -h; fm.descent = 0; fm.bottom = 0
        }
        return widthPx
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val rh = rowH(paint)
        val n = maxOf(1, table.cols)
        val cw = widthPx.toFloat() / n
        val t0 = y - heightPx(paint) + 3 * density
        val line = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, density * 0.8f); color = 0xFF8A909C.toInt() }
        val tp = TextPaint(paint)
        tp.style = Paint.Style.FILL
        for (r in table.rows.indices) for (c in 0 until n) {
            val l = x + c * cw
            val tt = t0 + r * rh
            canvas.drawRect(l, tt, l + cw, tt + rh, line)
            val s = table.rows[r].getOrNull(c)?.substringBefore('\n') ?: ""
            if (s.isNotEmpty()) {
                val e = TextUtils.ellipsize(s, tp, cw - 8 * density, TextUtils.TruncateAt.END)
                canvas.drawText(e, 0, e.length, l + 4 * density, tt + rh / 2f + tp.textSize * 0.35f, tp)
            }
        }
    }
}

/** A block the editor can't edit (content controls, tables of contents, ...). Kept verbatim. */
class OpaqueBlockSpan(val element: Element, val label: String, var widthPx: Int, val density: Float) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val h = (paint.textSize * 2.2f).toInt()
            fm.ascent = -h; fm.top = -h; fm.descent = 0; fm.bottom = 0
        }
        return widthPx
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val h = paint.textSize * 2.2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEEF0F4.toInt() }
        val rect = RectF(x, y - h + 2 * density, x + widthPx, y.toFloat() - 2 * density)
        canvas.drawRoundRect(rect, 6 * density, 6 * density, bg)
        val tp = TextPaint(paint).apply { color = 0xFF5D6371.toInt(); textSize = paint.textSize * 0.85f }
        val e = TextUtils.ellipsize(label, tp, widthPx - 16 * density, TextUtils.TruncateAt.END)
        canvas.drawText(e, 0, e.length, x + 8 * density, rect.centerY() + tp.textSize * 0.35f, tp)
    }
}

/** Small inline object kept verbatim (equations, unsupported shapes). */
class InlineOpaqueSpan(override val element: Element, val label: String, val density: Float) : ReplacementSpan(), PreservedInline {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (paint.measureText(label) + 12 * density).toInt()

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val w = paint.measureText(label) + 12 * density
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEEF0F4.toInt() }
        canvas.drawRoundRect(RectF(x, top.toFloat(), x + w, bottom.toFloat()), 4 * density, 4 * density, bg)
        val tp = TextPaint(paint).apply { color = 0xFF5D6371.toInt() }
        canvas.drawText(label, x + 6 * density, y.toFloat(), tp)
    }
}

/** An image in the document. New images carry their encoded [bytes] until the first save writes them into the package. */
class DocImageSpan(
    drawable: BitmapDrawable,
    override var element: Element?,
    val bytes: ByteArray?,
    val bitmap: Bitmap,
    val emuW: Long,
    val emuH: Long,
    val ext: String = "png"
) : ImageSpan(drawable, DynamicDrawableSpan.ALIGN_BOTTOM), PreservedInline
