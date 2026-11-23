package com.officesuite.app.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

enum class DocKind(val mime: String, val ext: String, val label: String) {
    PDF("application/pdf", "pdf", "PDF"),
    WORD("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx", "Document"),
    SHEET("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx", "Spreadsheet"),
    TEXT("text/plain", "txt", "Text"),
    CSV("text/csv", "csv", "CSV");

    companion object {
        val OPEN_MIMES = arrayOf(
            PDF.mime, WORD.mime, SHEET.mime, TEXT.mime, CSV.mime,
            "text/comma-separated-values", "application/octet-stream"
        )

        /** Returns null for unsupported types. */
        fun detect(name: String?, mime: String?): DocKind? {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            entries.firstOrNull { it.ext == ext }?.let { return it }
            return when (mime) {
                PDF.mime -> PDF
                WORD.mime -> WORD
                SHEET.mime -> SHEET
                "text/csv", "text/comma-separated-values" -> CSV
                "text/plain" -> TEXT
                else -> null
            }
        }

        fun isLegacyBinary(name: String?): Boolean {
            val ext = name?.substringAfterLast('.', "")?.lowercase()
            return ext == "doc" || ext == "xls" || ext == "ppt" || ext == "pptx" || ext == "odt" || ext == "ods"
        }
    }
}

object FileOps {

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "file"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun mime(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.getType(uri)
    } catch (_: Exception) {
        null
    }

    fun workDir(ctx: Context): File = File(ctx.cacheDir, "work").apply { mkdirs() }

    fun tempFile(ctx: Context, ext: String): File = File.createTempFile("doc_", ".$ext", workDir(ctx))

    suspend fun copyToTemp(ctx: Context, uri: Uri, ext: String): File = withContext(Dispatchers.IO) {
        val f = tempFile(ctx, ext)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
        input.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        f
    }

    suspend fun writeFileToUri(ctx: Context, file: File, uri: Uri) = withContext(Dispatchers.IO) {
        val out = try {
            ctx.contentResolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            null
        } ?: ctx.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write to this location")
        out.use { o -> FileInputStream(file).use { it.copyTo(o) } }
    }

    suspend fun writeBytesToUri(ctx: Context, bytes: ByteArray, uri: Uri) = withContext(Dispatchers.IO) {
        val out = try {
            ctx.contentResolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            null
        } ?: ctx.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write to this location")
        out.use { it.write(bytes) }
    }

    /** Keep access to a document picked with ACTION_OPEN_DOCUMENT across restarts. */
    fun persist(ctx: Context, uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, rw)
        } catch (_: Exception) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }

    fun share(ctx: Context, file: File, mime: String, name: String) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val out = File(dir, safe)
        file.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(safe, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share $safe"))
    }

    fun print(ctx: Context, pdf: File, jobName: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(jobName, PdfPrintAdapter(pdf, jobName), null)
    }

    /** Decodes an image, downsampled so its longest side is at most [maxSide], with EXIF rotation applied. */
    fun decodeImage(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > maxSide) {
                    val s = maxSide.toFloat() / longest
                    decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Exception) {
        null
    }

    fun withExt(name: String, ext: String): String {
        val base = name.substringBeforeLast('.', name)
        return "$base.$ext"
    }
}

class PdfPrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: PrintDocumentAdapter.LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled(); return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: PrintDocumentAdapter.WriteResultCallback
    ) {
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
