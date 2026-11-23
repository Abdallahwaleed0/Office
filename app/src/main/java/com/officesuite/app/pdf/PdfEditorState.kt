package com.officesuite.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.officesuite.app.core.DocKind
import com.officesuite.app.core.FileOps
import com.officesuite.app.core.Recents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Request to show the text dialog (new text box, editing a text box, or editing an existing PDF line). */
data class TextRequest(
    val page: Int,
    val x: Float,
    val y: Float,
    val initial: String,
    val sizePt: Float,
    val color: Int,
    /** FontLibrary family id; null = the PDF's original font (existing lines only) */
    val family: String?,
    val bold: Boolean,
    val italic: Boolean,
    val editId: Long? = null,
    val line: PdfTextLine? = null,
)

class PdfEditorState(private val ctx: Context, private val scope: CoroutineScope, private val sourceUri: Uri) {
    var name by mutableStateOf("Document.pdf")
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var needPassword by mutableStateOf(false)

    var session by mutableStateOf<PdfRenderSession?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set
    private var working: File? = null
    private var original: File? = null
    private var password: String? = null
    var currentUri: Uri = sourceUri
        private set

    val objects = mutableStateListOf<PdfObj>()
    val images = HashMap<String, Bitmap>()
    var tool by mutableStateOf(Tool.VIEW)
    var color by mutableIntStateOf(0xFF1D3A8A.toInt())
    var strokeWidth by mutableStateOf(0.004f)
    var selectedId by mutableStateOf<Long?>(null)
    var livePreview by mutableStateOf<PdfObj?>(null)
    var night by mutableStateOf(false)
    var textRequest by mutableStateOf<TextRequest?>(null)
    var fileDirty by mutableStateOf(false)

    /** Existing text lines per page (loaded on demand in "Edit text" mode). */
    val textLines = mutableStateMapOf<Int, List<PdfTextLine>>()
    private val loadingLines = HashSet<Int>()

    val dirty: Boolean get() = fileDirty || objects.isNotEmpty()

    private var nextId = 1L
    fun newId() = nextId++

    /* ---------------- undo / redo (objects and file edits) ---------------- */

    private sealed class Step {
        class Objects(val list: List<PdfObj>) : Step()
        class FileState(val file: File, val list: List<PdfObj>, val fileDirty: Boolean) : Step()
    }

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun updateHistoryFlags() {
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
    }

    fun pushObjectsUndo() {
        undoStack.addLast(Step.Objects(objects.toList()))
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryFlags()
    }

    private fun pushFileUndo(previous: File) {
        undoStack.addLast(Step.FileState(previous, objects.toList(), fileDirty))
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryFlags()
    }

    private fun currentStep(forFile: Boolean): Step =
        if (forFile) Step.FileState(working!!, objects.toList(), fileDirty) else Step.Objects(objects.toList())

    private fun apply(step: Step) {
        when (step) {
            is Step.Objects -> { objects.clear(); objects.addAll(step.list) }
            is Step.FileState -> {
                objects.clear(); objects.addAll(step.list)
                fileDirty = step.fileDirty
                reopen(step.file)
            }
        }
        selectedId = null
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(currentStep(s is Step.FileState))
        apply(s)
        updateHistoryFlags()
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(currentStep(s is Step.FileState))
        apply(s)
        updateHistoryFlags()
    }

    /* ---------------- opening ---------------- */

    fun load() {
        scope.launch {
            try {
                name = FileOps.displayName(ctx, sourceUri)
                val f = FileOps.copyToTemp(ctx, sourceUri, "pdf")
                original = f
                val ok = withContext(Dispatchers.IO) {
                    try { PdfRenderSession(f).close(); true } catch (e: SecurityException) { false }
                }
                if (!ok) {
                    needPassword = true
                } else {
                    reopen(f)
                    Recents.add(ctx, sourceUri, name, DocKind.PDF)
                }
            } catch (e: Exception) {
                error = "Could not open this PDF.\n${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    fun unlock(pw: String) {
        val src = original ?: return
        scope.launch {
            busy = "Unlocking…"
            try {
                val out = FileOps.tempFile(ctx, "pdf")
                withContext(Dispatchers.IO) { PdfEngine.decrypt(src, pw, out) }
                password = pw
                needPassword = false
                reopen(out)
                Recents.add(ctx, sourceUri, name, DocKind.PDF)
            } catch (e: PdfEngine.WrongPasswordException) {
                message = "Wrong password"
            } catch (e: Exception) {
                error = "Could not open this PDF.\n${e.message}"
                needPassword = false
            } finally {
                busy = null
            }
        }
    }

    private fun reopen(file: File) {
        val old = session
        session = try {
            PdfRenderSession(file)
        } catch (e: Exception) {
            error = "Could not display the PDF: ${e.message}"
            null
        }
        working = file
        old?.close()
        textLines.clear()
        loadingLines.clear()
        version++
    }

    fun close() {
        session?.close()
    }

    /* ---------------- overlay objects ---------------- */

    fun addObject(o: PdfObj, select: Boolean = false) {
        pushObjectsUndo()
        objects.add(o)
        if (select) { selectedId = o.id; tool = Tool.VIEW }
    }

    fun updateObject(id: Long, change: (PdfObj) -> PdfObj) {
        val i = objects.indexOfFirst { it.id == id }
        if (i >= 0) objects[i] = change(objects[i])
    }

    fun removeObject(id: Long) {
        val i = objects.indexOfFirst { it.id == id }
        if (i < 0) return
        pushObjectsUndo()
        objects.removeAt(i)
        if (selectedId == id) selectedId = null
    }

    fun selected(): PdfObj? = selectedId?.let { id -> objects.firstOrNull { it.id == id } }

    /** Drag on the corner handle: scales images and text, stretches white-out boxes. */
    fun resizeSelected(dx: Float, dy: Float, pw: Float, ph: Float) {
        val id = selectedId ?: return
        updateObject(id) { o ->
            when (o) {
                is ImageObj -> {
                    val nw = (o.w + dx / pw).coerceIn(0.03f, 1.5f)
                    o.copy(w = nw, h = o.h * nw / o.w)
                }
                is RectObj -> o.copy(w = (o.w + dx / pw).coerceAtLeast(0.01f), h = (o.h + dy / ph).coerceAtLeast(0.005f))
                is TextObj -> {
                    val r = ObjPainter.textRect(o, pw, ph)
                    val f = ((r.width() + dx) / r.width().coerceAtLeast(1f)).coerceIn(0.5f, 2f)
                    o.copy(size = (o.size * f).coerceIn(0.004f, 0.2f))
                }
                else -> o
            }
        }
    }

    fun addImage(bmp: Bitmap, page: Int, widthNorm: Float, pageAspect: Float) {
        val key = "img${newId()}"
        images[key] = bmp
        val w = widthNorm
        val h = w * bmp.height / bmp.width * pageAspect
        addObject(ImageObj(newId(), page, (1f - w) / 2f, ((1f - h) / 2f).coerceAtLeast(0.02f), w, h, key), select = true)
    }

    /* ---------------- text ---------------- */

    fun requestLines(page: Int) {
        val f = working ?: return
        if (textLines.containsKey(page) || !loadingLines.add(page)) return
        val v = version
        scope.launch {
            val lines = withContext(Dispatchers.IO) {
                try { PdfTextEditor.lines(f, page) } catch (_: Exception) { emptyList() }
            }
            if (v == version) textLines[page] = lines
            loadingLines.remove(page)
        }
    }

    /** Style used for the next new text box (remembers the last one chosen). */
    var lastStyle = TextStyleChoice("arial", false, false, 12f, 0xFF000000.toInt())

    fun pageWidthPt(page: Int): Float = session?.sizes?.getOrNull(page)?.width?.takeIf { it > 0f } ?: 595f

    fun requestNewText(page: Int, x: Float, y: Float) {
        val s = lastStyle
        textRequest = TextRequest(page, x, y, "", s.sizePt, s.color, s.family, s.bold, s.italic)
    }

    fun requestEditObject(o: TextObj) {
        textRequest = TextRequest(o.page, o.x, o.y, o.text, o.size * pageWidthPt(o.page), o.color, o.family, o.bold, o.italic, editId = o.id)
    }

    fun requestEditLine(line: PdfTextLine) {
        textRequest = TextRequest(line.page, line.box.l, line.box.t, line.text, line.sizePt, line.color, null, line.bold, line.italic, line = line)
    }

    fun commitText(req: TextRequest, text: String, choice: TextStyleChoice) {
        val line = req.line
        val sizeNorm = choice.sizePt / pageWidthPt(req.page)
        when {
            line != null -> {
                val styleChanged = choice.family != null || choice.bold != line.bold || choice.italic != line.italic ||
                    kotlin.math.abs(choice.sizePt - line.sizePt) > 0.05f || choice.color != line.color
                if (text != line.text || styleChanged) replaceExistingLine(line, text, choice)
            }
            req.editId != null -> {
                if (text.isBlank()) removeObject(req.editId)
                else {
                    pushObjectsUndo()
                    updateObject(req.editId) {
                        (it as TextObj).copy(
                            text = text, size = sizeNorm, color = choice.color,
                            family = choice.family ?: "arial", bold = choice.bold, italic = choice.italic
                        )
                    }
                }
            }
            text.isNotBlank() -> addObject(
                TextObj(
                    newId(), req.page, req.x, req.y, text, sizeNorm, choice.color,
                    bold = choice.bold, family = choice.family ?: "arial", italic = choice.italic
                ),
                select = true
            )
        }
        if (line == null) lastStyle = choice.copy(family = choice.family ?: "arial")
        textRequest = null
    }

    private fun replaceExistingLine(line: PdfTextLine, text: String, choice: TextStyleChoice) {
        val src = working ?: return
        scope.launch {
            busy = "Updating text…"
            try {
                val out = FileOps.tempFile(ctx, "pdf")
                val result = withContext(Dispatchers.IO) {
                    try { PdfTextEditor.replaceLine(src, out, line.page, line, text, choice) } catch (_: Throwable) { PdfTextEditor.Result.FALLBACK }
                }
                if (result == PdfTextEditor.Result.OK) {
                    pushFileUndo(src)
                    fileDirty = true
                    reopen(out)
                    message = if (text.isEmpty()) "Text deleted" else "Text updated"
                } else {
                    // Visual replacement: cover the old line and draw the new text in its place.
                    val pad = 0.002f
                    val scale = if (line.sizePt > 0f) choice.sizePt / line.sizePt else 1f
                    addObject(
                        TextObj(
                            newId(), line.page, line.box.l, line.box.t, text, line.sizeNorm * scale,
                            choice.color, bold = choice.bold, cover = line.box.inflate(pad, pad),
                            family = choice.family ?: line.family, italic = choice.italic
                        )
                    )
                    message = "This line was replaced visually (its font or script can't be rewritten in place)"
                }
            } finally {
                busy = null
            }
        }
    }

    /* ---------------- file-level operations ---------------- */

    /** Writes overlay objects into a new working file (keeps them undoable as a file step). */
    private suspend fun commitObjects(): File {
        val src = working!!
        if (objects.isEmpty()) return src
        val out = FileOps.tempFile(ctx, "pdf")
        val snapshot = objects.toList()
        withContext(Dispatchers.IO) { PdfEngine.flatten(src, out, snapshot, images) }
        pushFileUndo(src)
        objects.clear()
        selectedId = null
        fileDirty = true
        reopen(out)
        return out
    }

    /** Runs a page operation on the working file (objects are written into the page first). */
    fun fileOp(label: String, op: (File, File) -> Unit) {
        scope.launch {
            busy = label
            try {
                val src = commitObjects()
                val out = FileOps.tempFile(ctx, "pdf")
                withContext(Dispatchers.IO) { op(src, out) }
                pushFileUndo(src)
                fileDirty = true
                reopen(out)
            } catch (e: Exception) {
                message = e.message ?: "Operation failed"
            } finally {
                busy = null
            }
        }
    }

    /** Produces the final PDF (working file + objects, re-protected if it had a password). */
    suspend fun exportFile(): File {
        val src = working ?: throw IllegalStateException("No document")
        return withContext(Dispatchers.IO) {
            var f = src
            if (objects.isNotEmpty()) {
                val out = FileOps.tempFile(ctx, "pdf")
                PdfEngine.flatten(f, out, objects.toList(), images)
                f = out
            }
            val pw = password
            if (pw != null) {
                val out = FileOps.tempFile(ctx, "pdf")
                PdfEngine.protect(f, out, pw)
                f = out
            }
            f
        }
    }

    fun save(target: Uri?, onDone: (Boolean) -> Unit = {}) {
        val uri = target ?: currentUri
        scope.launch {
            busy = "Saving…"
            try {
                // overlay objects become part of the PDF (still undoable as one step)
                val flat = commitObjects()
                val pw = password
                val out = if (pw == null) flat else withContext(Dispatchers.IO) {
                    FileOps.tempFile(ctx, "pdf").also { PdfEngine.protect(flat, it, pw) }
                }
                FileOps.writeFileToUri(ctx, out, uri)
                if (target != null) {
                    currentUri = target
                    FileOps.persist(ctx, target)
                    name = FileOps.displayName(ctx, target)
                }
                Recents.add(ctx, uri, name, DocKind.PDF)
                fileDirty = false
                message = "Saved"
                onDone(true)
            } catch (e: Exception) {
                message = "Could not save here: ${e.message}"
                onDone(false)
            } finally {
                busy = null
            }
        }
    }

    fun setPassword(pw: String?) {
        password = pw?.takeIf { it.isNotEmpty() }
        fileDirty = true
        message = if (password != null) "Password will be applied when you save" else "Password will be removed when you save"
    }

    val hasPassword get() = password != null

    suspend fun search(query: String): List<PdfEngine.SearchHit> {
        val f = working ?: return emptyList()
        return withContext(Dispatchers.IO) { try { PdfEngine.search(f, query) } catch (_: Exception) { emptyList() } }
    }

    suspend fun formFields(): List<PdfEngine.FormField> {
        val f = working ?: return emptyList()
        return withContext(Dispatchers.IO) { try { PdfEngine.formFields(f) } catch (_: Exception) { emptyList() } }
    }

    fun fillForm(values: Map<String, String>) = fileOp("Filling form…") { s, o -> PdfEngine.fillForm(s, o, values) }

    suspend fun toDocx(): ByteArray {
        val f = exportFile()
        return withContext(Dispatchers.IO) { PdfEngine.toDocx(f) }
    }
}
