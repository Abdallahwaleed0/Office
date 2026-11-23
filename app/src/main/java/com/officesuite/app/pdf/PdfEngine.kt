package com.officesuite.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import com.officesuite.app.core.FileOps
import com.officesuite.app.core.OoXml
import com.officesuite.app.core.Templates
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

/** PDF operations built on PDFBox-Android. All functions are blocking: call them off the main thread. */
object PdfEngine {

    class WrongPasswordException : Exception("Wrong password")

    /** Loads a document; any (owner-only) encryption is dropped so the result can be saved freely. */
    fun open(file: File, password: String? = null): PDDocument {
        val doc = try {
            if (password == null) PDDocument.load(file) else PDDocument.load(file, password)
        } catch (e: InvalidPasswordException) {
            throw WrongPasswordException()
        }
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }

    fun needsPassword(file: File): Boolean = try {
        PDDocument.load(file).close(); false
    } catch (e: InvalidPasswordException) {
        true
    }

    fun decrypt(src: File, password: String, out: File) {
        open(src, password).use { it.save(out) }
    }

    fun protect(src: File, out: File, password: String) {
        open(src).use { doc ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(out)
        }
    }

    /* ----------------------------- flatten editor objects ----------------------------- */

    /** Maps display coordinates (points, origin top-left of the shown page) to PDF user space. */
    private fun displayTransform(page: PDPage): Pair<Matrix, FloatArray> {
        val box = page.cropBox
        val llx = box.lowerLeftX
        val lly = box.lowerLeftY
        val w = box.width
        val h = box.height
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> Matrix(0f, 1f, 1f, 0f, llx, lly) to floatArrayOf(h, w)
            180 -> Matrix(-1f, 0f, 0f, 1f, llx + w, lly) to floatArrayOf(w, h)
            270 -> Matrix(0f, -1f, -1f, 0f, llx + w, box.upperRightY) to floatArrayOf(h, w)
            else -> Matrix(1f, 0f, 0f, -1f, llx, box.upperRightY) to floatArrayOf(w, h)
        }
    }

    private fun rgb(color: Int) = PDColor(
        floatArrayOf(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f),
        PDDeviceRGB.INSTANCE
    )

    private fun imageXObject(doc: PDDocument, bmp: Bitmap): PDImageXObject =
        if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, 0.92f)

    /** Draws an image so that it fills the display-space rectangle (x, y, w, h) in points. */
    private fun drawImageAt(cs: PDPageContentStream, img: PDImageXObject, x: Float, y: Float, w: Float, h: Float) {
        cs.drawImage(img, Matrix(w, 0f, 0f, -h, x, y + h))
    }

    fun flatten(src: File, out: File, objs: List<PdfObj>, images: Map<String, Bitmap>) {
        open(src).use { doc ->
            val imageCache = HashMap<String, PDImageXObject>()
            for ((pageIndex, list) in objs.groupBy { it.page }) {
                if (pageIndex !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(pageIndex)
                val (m, dims) = displayTransform(page)
                val wd = dims[0]
                val hd = dims[1]
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(m)
                    for (o in list) {
                        when (o) {
                            is InkObj -> {
                                if (o.pts.size < 2) continue
                                cs.saveGraphicsState()
                                if (o.highlighter) {
                                    val gs = PDExtendedGraphicsState()
                                    gs.strokingAlphaConstant = 0.35f
                                    cs.setGraphicsStateParameters(gs)
                                }
                                cs.setStrokingColor(rgb(o.color))
                                cs.setLineWidth(o.width * wd)
                                cs.setLineCapStyle(1)
                                cs.setLineJoinStyle(1)
                                cs.moveTo(o.pts[0] * wd, o.pts[1] * hd)
                                if (o.pts.size == 2) cs.lineTo(o.pts[0] * wd + 0.01f, o.pts[1] * hd)
                                var i = 2
                                while (i + 1 < o.pts.size) { cs.lineTo(o.pts[i] * wd, o.pts[i + 1] * hd); i += 2 }
                                cs.stroke()
                                cs.restoreGraphicsState()
                            }
                            is RectObj -> {
                                cs.setNonStrokingColor(rgb(o.fill))
                                cs.addRect(o.x * wd, o.y * hd, o.w * wd, o.h * hd)
                                cs.fill()
                            }
                            is ImageObj -> {
                                val bmp = images[o.key] ?: continue
                                val img = imageCache.getOrPut(o.key) { imageXObject(doc, bmp) }
                                drawImageAt(cs, img, o.x * wd, o.y * hd, o.w * wd, o.h * hd)
                            }
                            is TextObj -> {
                                o.cover?.let { c ->
                                    cs.setNonStrokingColor(rgb(0xFFFFFF))
                                    cs.addRect(c.l * wd, c.t * hd, c.w * wd, c.h * hd)
                                    cs.fill()
                                }
                                // Text is rendered by Android (correct shaping for every script) at 4 px per point.
                                val scale = 4f
                                val pw = wd * scale
                                val ph = hd * scale
                                val r = ObjPainter.textRect(o, pw, ph)
                                val bw = ceil(r.width()).toInt().coerceIn(1, 6000)
                                val bh = ceil(r.height()).toInt().coerceIn(1, 6000)
                                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bmp)
                                canvas.translate(-r.left, -r.top)
                                ObjPainter.drawTextOnly(canvas, o, pw, ph)
                                val img = LosslessFactory.createFromImage(doc, bmp)
                                drawImageAt(cs, img, r.left / scale, r.top / scale, bw / scale, bh / scale)
                                bmp.recycle()
                            }
                        }
                    }
                    cs.restoreGraphicsState()
                }
            }
            doc.save(out)
        }
    }

    /* ----------------------------- pages ----------------------------- */

    fun rotate(src: File, out: File, pages: Collection<Int>, delta: Int) {
        open(src).use { doc ->
            for (i in pages) {
                val p = doc.getPage(i)
                p.rotation = (((p.rotation + delta) % 360) + 360) % 360
            }
            doc.save(out)
        }
    }

    fun delete(src: File, out: File, pages: Collection<Int>) {
        open(src).use { doc ->
            if (pages.size >= doc.numberOfPages) throw IllegalArgumentException("A PDF needs at least one page")
            for (i in pages.sortedDescending()) doc.removePage(i)
            doc.save(out)
        }
    }

    fun move(src: File, out: File, from: Int, to: Int) {
        open(src).use { doc ->
            val page = doc.getPage(from)
            doc.removePage(from)
            if (to >= doc.numberOfPages) doc.addPage(page) else doc.pages.insertBefore(page, doc.getPage(to))
            doc.save(out)
        }
    }

    fun insertBlank(src: File, out: File, after: Int) {
        open(src).use { doc ->
            val ref = doc.getPage(after.coerceIn(0, doc.numberOfPages - 1))
            val blank = PDPage(PDRectangle(ref.mediaBox.width, ref.mediaBox.height))
            blank.rotation = ref.rotation
            doc.pages.insertAfter(blank, ref)
            doc.save(out)
        }
    }

    fun duplicate(src: File, out: File, index: Int) {
        open(src).use { doc ->
            val ref = doc.getPage(index)
            val copy = PDPage(COSDictionary(ref.cosObject))
            doc.pages.insertAfter(copy, ref)
            doc.save(out)
        }
    }

    fun extract(src: File, out: File, pages: List<Int>) {
        open(src).use { doc ->
            PDDocument().use { nd ->
                for (i in pages.sorted()) nd.importPage(doc.getPage(i))
                nd.save(out)
            }
        }
    }

    fun merge(inputs: List<File>, out: File) {
        val dest = PDDocument()
        val sources = ArrayList<PDDocument>()
        try {
            val merger = PDFMergerUtility()
            for (f in inputs) {
                val d = open(f)
                sources.add(d)
                merger.appendDocument(dest, d)
            }
            dest.save(out)
        } finally {
            dest.close()
            sources.forEach { it.close() }
        }
    }

    fun imagesToPdf(ctx: Context, uris: List<Uri>, out: File, fitA4: Boolean) {
        PDDocument().use { doc ->
            for (u in uris) {
                val bmp = FileOps.decodeImage(ctx, u, 2400) ?: continue
                val img = JPEGFactory.createFromImage(doc, bmp, 0.88f)
                val landscape = bmp.width > bmp.height
                val pw: Float
                val ph: Float
                if (fitA4) {
                    pw = if (landscape) PDRectangle.A4.height else PDRectangle.A4.width
                    ph = if (landscape) PDRectangle.A4.width else PDRectangle.A4.height
                } else {
                    pw = bmp.width * 0.75f
                    ph = bmp.height * 0.75f
                }
                val page = PDPage(PDRectangle(pw, ph))
                doc.addPage(page)
                val margin = if (fitA4) 24f else 0f
                val s = min((pw - 2 * margin) / bmp.width, (ph - 2 * margin) / bmp.height)
                val w = bmp.width * s
                val h = bmp.height * s
                PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, (pw - w) / 2f, (ph - h) / 2f, w, h) }
                bmp.recycle()
            }
            if (doc.numberOfPages == 0) throw IllegalArgumentException("None of the images could be read")
            doc.save(out)
        }
    }

    /* ----------------------------- text ----------------------------- */

    fun pageTexts(file: File): List<String> = open(file).use { doc ->
        val st = PDFTextStripper()
        (1..doc.numberOfPages).map { p ->
            st.startPage = p
            st.endPage = p
            st.getText(doc)
        }
    }

    data class SearchHit(val page: Int, val snippet: String)

    fun search(file: File, query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<SearchHit>()
        pageTexts(file).forEachIndexed { i, text ->
            var idx = text.indexOf(query, ignoreCase = true)
            var n = 0
            while (idx >= 0 && n < 20) {
                val s = (idx - 30).coerceAtLeast(0)
                val e = (idx + query.length + 30).coerceAtMost(text.length)
                out.add(SearchHit(i, text.substring(s, e).replace(Regex("\\s+"), " ").trim()))
                idx = text.indexOf(query, idx + query.length, ignoreCase = true)
                n++
            }
        }
        return out
    }

    /** Converts a PDF into a .docx with one paragraph per text paragraph (text only). */
    fun toDocx(src: File): ByteArray {
        val paragraphs = ArrayList<String>()
        for (pageText in pageTexts(src)) {
            val lines = pageText.replace("\r", "").split('\n')
            val maxLen = lines.maxOfOrNull { it.trim().length } ?: 0
            val sb = StringBuilder()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) {
                    if (sb.isNotEmpty()) { paragraphs.add(sb.toString()); sb.clear() }
                    continue
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(line)
                // a clearly short line ends a paragraph
                if (line.length < maxLen * 0.6 || line.endsWith(":")) { paragraphs.add(sb.toString()); sb.clear() }
            }
            if (sb.isNotEmpty()) paragraphs.add(sb.toString())
            paragraphs.add("")
        }
        return OoXml.zipBytes(Templates.newDocx(paragraphs))
    }

    /* ----------------------------- forms ----------------------------- */

    enum class FieldKind { TEXT, CHECK, CHOICE }
    data class FormField(val name: String, val kind: FieldKind, val value: String, val options: List<String>)

    fun formFields(file: File): List<FormField> = open(file).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return@use emptyList()
        form.fieldTree.mapNotNull { f ->
            when (f) {
                is PDTextField -> FormField(f.fullyQualifiedName, FieldKind.TEXT, f.valueAsString ?: "", emptyList())
                is PDCheckBox -> FormField(f.fullyQualifiedName, FieldKind.CHECK, if (f.isChecked) "true" else "false", emptyList())
                is PDChoice -> FormField(f.fullyQualifiedName, FieldKind.CHOICE, f.valueAsString ?: "", f.options ?: emptyList())
                else -> null
            }
        }
    }

    fun fillForm(src: File, out: File, values: Map<String, String>) {
        open(src).use { doc ->
            val form = doc.documentCatalog.acroForm ?: throw IllegalStateException("This PDF has no form fields")
            for ((name, v) in values) {
                val f = form.getField(name) ?: continue
                try {
                    when (f) {
                        is PDTextField -> f.setValue(v)
                        is PDCheckBox -> if (v == "true") f.check() else f.unCheck()
                        is PDChoice -> f.setValue(v)
                    }
                } catch (_: Exception) {
                }
            }
            doc.save(out)
        }
    }
}
