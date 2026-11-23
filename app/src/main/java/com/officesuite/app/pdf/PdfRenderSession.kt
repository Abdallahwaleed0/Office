package com.officesuite.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.SizeF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Renders pages with Android's built-in PdfRenderer (fast, no extra libraries).
 * Throws SecurityException for password-protected files.
 */
class PdfRenderSession(val file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close(); throw e
    }
    private val lock = Any()
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount
    val sizes: List<SizeF> = (0 until pageCount).map { i ->
        val p = renderer.openPage(i)
        try { SizeF(p.width.toFloat(), p.height.toFloat()) } finally { p.close() }
    }

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in 0 until pageCount || widthPx <= 0) return@withContext null
        val key = "$index@$widthPx"
        cache.get(key)?.let { return@withContext it }
        synchronized(lock) {
            if (closed) return@withContext null
            val page = renderer.openPage(index)
            try {
                val w = widthPx.coerceAtMost(3000)
                val h = (w * page.height.toFloat() / page.width).toInt().coerceIn(1, 4500)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bmp)
                bmp
            } finally {
                page.close()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { renderer.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }
}
