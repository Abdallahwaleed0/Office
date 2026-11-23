package com.officesuite.app.word

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import java.io.File
import java.io.FileOutputStream

/** Lays the document out on A4 pages and writes a PDF (Android's built-in PdfDocument). */
object WordPdfExporter {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 56

    fun export(res: Resources, text: Spanned, out: File) {
        val density = res.displayMetrics.density
        val pt = 0.75f / density // screen px -> PDF points
        val cw = PAGE_W - 2 * MARGIN
        val ch = PAGE_H - 2 * MARGIN
        val sb = SpannableStringBuilder(text)

        fun swap(old: Any, new: Any) {
            val s = sb.getSpanStart(old); val e = sb.getSpanEnd(old); val f = sb.getSpanFlags(old)
            sb.removeSpan(old)
            sb.setSpan(new, s, e, f)
        }
        for (sp in sb.getSpans(0, sb.length, DocImageSpan::class.java)) {
            val b = sp.drawable.bounds
            var w = b.width() * pt
            var h = b.height() * pt
            if (w > cw) { h *= cw / w; w = cw.toFloat() }
            val d = BitmapDrawable(res, sp.bitmap)
            d.setBounds(0, 0, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            swap(sp, ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM))
        }
        for (sp in sb.getSpans(0, sb.length, TableSpan::class.java)) swap(sp, TableSpan(sp.table, cw, 0.75f))
        for (sp in sb.getSpans(0, sb.length, ListSpan::class.java)) swap(sp, ListSpan(sp.numId, sp.ilvl, 0.75f, sp.numbered))
        for (sp in sb.getSpans(0, sb.length, OpaqueBlockSpan::class.java)) swap(sp, OpaqueBlockSpan(sp.element, sp.label, cw, 0.75f))
        for (sp in sb.getSpans(0, sb.length, InlineOpaqueSpan::class.java)) swap(sp, InlineOpaqueSpan(sp.element, sp.label, 0.75f))

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.density = 0.75f
            textSize = 12f
            color = Color.BLACK
        }
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, cw)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()

        val doc = PdfDocument()
        var line = 0
        var pageNo = 1
        do {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo++).create())
            if (layout.lineCount > 0) {
                val top = layout.getLineTop(line)
                var last = line
                while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= ch) last++
                val bottom = layout.getLineBottom(last)
                val c = page.canvas
                c.save()
                c.translate(MARGIN.toFloat(), (MARGIN - top).toFloat())
                c.clipRect(0, top, cw, bottom)
                layout.draw(c)
                c.restore()
                line = last + 1
            }
            doc.finishPage(page)
        } while (line < layout.lineCount)
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }
}
