package com.officesuite.app.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.officesuite.app.core.FontLibrary
import android.text.TextPaint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Rectangle in normalized page coordinates (0..1 of the displayed page width / height, origin top-left). */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    fun contains(x: Float, y: Float) = x in l..r && y in t..b
    fun inflate(dx: Float, dy: Float = dx) = NRect(l - dx, t - dy, r + dx, b + dy)
    fun overlaps(o: NRect) = l < o.r && o.l < r && t < o.b && o.t < b
}

enum class Tool { VIEW, EDIT_TEXT, ADD_TEXT, PEN, HIGHLIGHT, ERASER, WHITEOUT }

/**
 * Objects added on top of the PDF while editing. Coordinates are normalized to the displayed page so they
 * stay correct at any zoom level; they are written into the PDF ("flattened") on save.
 */
sealed class PdfObj {
    abstract val id: Long
    abstract val page: Int
    abstract fun moved(dx: Float, dy: Float): PdfObj
}

/** Freehand pen / highlighter stroke. [pts] = x0,y0,x1,y1,... ; [width] is relative to page width. */
data class InkObj(
    override val id: Long, override val page: Int,
    val pts: FloatArray, val color: Int, val width: Float, val highlighter: Boolean
) : PdfObj() {
    override fun moved(dx: Float, dy: Float) =
        copy(pts = FloatArray(pts.size) { i -> pts[i] + if (i % 2 == 0) dx else dy })

    override fun equals(other: Any?) = other is InkObj && other.id == id && other.pts.contentEquals(pts) && other.color == color
    override fun hashCode() = id.hashCode()
}

/**
 * Text box. [size] is the font size relative to page width. [family] is a FontLibrary family id.
 * [cover] paints a white box first (used to replace existing text visually).
 */
data class TextObj(
    override val id: Long, override val page: Int,
    val x: Float, val y: Float, val text: String, val size: Float, val color: Int,
    val bold: Boolean = false, val cover: NRect? = null,
    val family: String? = "arial", val italic: Boolean = false
) : PdfObj() {
    override fun moved(dx: Float, dy: Float) =
        copy(x = x + dx, y = y + dy, cover = cover?.let { NRect(it.l + dx, it.t + dy, it.r + dx, it.b + dy) })
}

/** Picture or signature. [key] refers to a bitmap held by the editor. */
data class ImageObj(
    override val id: Long, override val page: Int,
    val x: Float, val y: Float, val w: Float, val h: Float, val key: String
) : PdfObj() {
    override fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
}

/** Filled rectangle (white-out). */
data class RectObj(
    override val id: Long, override val page: Int,
    val x: Float, val y: Float, val w: Float, val h: Float, val fill: Int
) : PdfObj() {
    override fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
}

/**
 * A line of existing PDF text found on a page. [ops] are content-stream operator indices that draw it.
 * Style fields describe the original font: [fontName] as written in the PDF, [family] the closest
 * FontLibrary family, plus weight, slant, size in points and fill color.
 */
data class PdfTextLine(
    val page: Int,
    val text: String,
    val box: NRect,
    val ops: List<Int>,
    val sizeNorm: Float,
    val color: Int,
    val editable: Boolean,
    val fontName: String = "",
    val family: String = "arial",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sizePt: Float = 12f,
)

/** Text style chosen in the editor. [family] null = keep the PDF's own font when possible. */
data class TextStyleChoice(
    val family: String?,
    val bold: Boolean,
    val italic: Boolean,
    val sizePt: Float,
    val color: Int,
)

/** Draws editor objects on an Android canvas whose size is the page in pixels (pw x ph). */
object ObjPainter {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun textPaint(o: TextObj, pw: Float): TextPaint {
        text.textSize = (o.size * pw).coerceAtLeast(1f)
        text.color = o.color
        text.typeface = FontLibrary.typeface(o.family, o.bold, o.italic)
        return text
    }

    fun textRect(o: TextObj, pw: Float, ph: Float): RectF {
        val tp = textPaint(o, pw)
        val lines = o.text.split('\n')
        val w = lines.maxOf { tp.measureText(it) }
        val h = lines.size * tp.fontSpacing
        return RectF(o.x * pw, o.y * ph, o.x * pw + max(w, 4f), o.y * ph + h)
    }

    fun bounds(o: PdfObj, pw: Float, ph: Float): RectF = when (o) {
        is InkObj -> {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            var i = 0
            while (i + 1 < o.pts.size) {
                l = min(l, o.pts[i] * pw); r = max(r, o.pts[i] * pw)
                t = min(t, o.pts[i + 1] * ph); b = max(b, o.pts[i + 1] * ph)
                i += 2
            }
            val half = o.width * pw / 2f
            RectF(l - half, t - half, r + half, b + half)
        }
        is TextObj -> textRect(o, pw, ph).also { r ->
            o.cover?.let { c -> r.union(c.l * pw, c.t * ph, c.r * pw, c.b * ph) }
        }
        is ImageObj -> RectF(o.x * pw, o.y * ph, (o.x + o.w) * pw, (o.y + o.h) * ph)
        is RectObj -> RectF(o.x * pw, o.y * ph, (o.x + o.w) * pw, (o.y + o.h) * ph)
    }

    fun drawTextOnly(c: Canvas, o: TextObj, pw: Float, ph: Float) {
        val tp = textPaint(o, pw)
        val fm = tp.fontMetrics
        var y = o.y * ph - fm.ascent
        for (line in o.text.split('\n')) {
            c.drawText(line, o.x * pw, y, tp)
            y += tp.fontSpacing
        }
    }

    fun draw(c: Canvas, o: PdfObj, pw: Float, ph: Float, images: Map<String, Bitmap>) {
        when (o) {
            is InkObj -> {
                if (o.pts.size < 2) return
                ink.color = if (o.highlighter) (o.color and 0x00FFFFFF) or 0x5A000000 else o.color
                ink.strokeWidth = o.width * pw
                val path = Path()
                path.moveTo(o.pts[0] * pw, o.pts[1] * ph)
                if (o.pts.size == 2) path.lineTo(o.pts[0] * pw + 0.1f, o.pts[1] * ph)
                var i = 2
                while (i + 1 < o.pts.size) { path.lineTo(o.pts[i] * pw, o.pts[i + 1] * ph); i += 2 }
                c.drawPath(path, ink)
            }
            is TextObj -> {
                o.cover?.let { cv ->
                    fill.color = 0xFFFFFFFF.toInt()
                    c.drawRect(cv.l * pw, cv.t * ph, cv.r * pw, cv.b * ph, fill)
                }
                drawTextOnly(c, o, pw, ph)
            }
            is ImageObj -> {
                val bmp = images[o.key] ?: return
                c.drawBitmap(bmp, null, RectF(o.x * pw, o.y * ph, (o.x + o.w) * pw, (o.y + o.h) * ph), bmpPaint)
            }
            is RectObj -> {
                fill.color = o.fill
                c.drawRect(o.x * pw, o.y * ph, (o.x + o.w) * pw, (o.y + o.h) * ph, fill)
            }
        }
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Hit test in pixels. */
    fun hit(o: PdfObj, x: Float, y: Float, pw: Float, ph: Float, slop: Float): Boolean {
        if (o is InkObj) {
            val limit = o.width * pw / 2f + slop
            if (o.pts.size == 2) return hypot(x - o.pts[0] * pw, y - o.pts[1] * ph) <= limit
            var i = 0
            while (i + 3 < o.pts.size) {
                if (segDist(x, y, o.pts[i] * pw, o.pts[i + 1] * ph, o.pts[i + 2] * pw, o.pts[i + 3] * ph) <= limit) return true
                i += 2
            }
            return false
        }
        val r = bounds(o, pw, ph)
        r.inset(-slop, -slop)
        return r.contains(x, y)
    }
}
