package com.officesuite.app.sheet

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/** Exports the used range of a sheet to a paginated A4 PDF (pages run down, then across). */
object SheetPdfExporter {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val M = 36f

    fun export(wb: Workbook, sh: Sheet, ev: Evaluator, out: File) {
        val maxR = maxOf(sh.maxRow(), 0)
        val maxC = maxOf(sh.maxCol(), 0)
        val colW = FloatArray(maxC + 1) { c -> ((sh.colWidths[c] ?: 8.43f) * 5.6f + 4f).coerceAtMost(PAGE_W - 2 * M) }
        val rowH = FloatArray(maxR + 1) { r -> (sh.rowHeights[r] ?: 15f).coerceAtLeast(12f) }
        // split columns into page-wide groups
        val colGroups = ArrayList<IntRange>()
        var start = 0
        var acc = 0f
        for (c in 0..maxC) {
            if (acc + colW[c] > PAGE_W - 2 * M && c > start) { colGroups.add(start until c); start = c; acc = 0f }
            acc += colW[c]
        }
        colGroups.add(start..maxC)

        val doc = PdfDocument()
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint().apply { color = 0xFFC8CCD3.toInt(); strokeWidth = 0.5f; style = Paint.Style.STROKE }
        val fill = Paint()
        var pageNo = 1
        for (group in colGroups) {
            var r = 0
            while (r <= maxR) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo++).create())
                val cv = page.canvas
                var y = M
                while (r <= maxR && y + rowH[r] <= PAGE_H - M) {
                    var x = M
                    for (c in group) {
                        val w = colW[c]
                        val cell = sh[r, c]
                        val style = wb.style(cell?.style ?: 0)
                        style.fillColor?.let { fill.color = it; cv.drawRect(x, y, x + w, y + rowH[r], fill) }
                        cv.drawRect(x, y, x + w, y + rowH[r], line)
                        if (cell != null && cell.raw.isNotEmpty()) {
                            val v = SheetText.value(ev, sh, r, c)
                            val s = SheetText.display(wb, ev, sh, r, c)
                            text.textSize = style.fontSize * 0.85f
                            text.color = style.fontColor ?: 0xFF000000.toInt()
                            text.typeface = Typeface.create(
                                Typeface.DEFAULT,
                                (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
                            )
                            text.isUnderlineText = style.underline
                            val align = when (style.hAlign) {
                                HAlign.GENERAL -> if (v is V.Num) HAlign.RIGHT else HAlign.LEFT
                                else -> style.hAlign
                            }
                            val tw = text.measureText(s)
                            val tx = when (align) {
                                HAlign.RIGHT -> x + w - 3f - tw
                                HAlign.CENTER -> x + (w - tw) / 2f
                                else -> x + 3f
                            }
                            cv.save()
                            cv.clipRect(x, y, x + w, y + rowH[r])
                            cv.drawText(s, tx, y + rowH[r] / 2f + text.textSize * 0.35f, text)
                            cv.restore()
                        }
                        x += w
                    }
                    y += rowH[r]
                    r++
                }
                doc.finishPage(page)
            }
        }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }
}
