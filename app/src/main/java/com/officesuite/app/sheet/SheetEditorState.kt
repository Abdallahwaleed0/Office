package com.officesuite.app.sheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.officesuite.app.core.DocKind
import com.officesuite.app.core.FileOps
import com.officesuite.app.core.OoXml
import com.officesuite.app.core.Recents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SheetEditorState(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val sourceUri: Uri?,
    val kind: DocKind
) {
    var wb by mutableStateOf<Workbook?>(null)
        private set
    var name by mutableStateOf(if (kind == DocKind.CSV) "Untitled.csv" else "Untitled.xlsx")
    var currentUri: Uri? = sourceUri
        private set
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)

    var active by mutableIntStateOf(0)
        private set
    var selR by mutableIntStateOf(0)
        private set
    var selC by mutableIntStateOf(0)
        private set
    var selR2 by mutableIntStateOf(0)
        private set
    var selC2 by mutableIntStateOf(0)
        private set

    var ev by mutableStateOf<Evaluator?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set

    var editText by mutableStateOf(TextFieldValue(""))
    var editing by mutableStateOf(false)
    var focusRequests by mutableIntStateOf(0)

    var scrollTarget by mutableStateOf<Pair<Int, Int>?>(null)
    var zoom by mutableStateOf(1f)
    var scrollX by mutableStateOf(0f)
    var scrollY by mutableStateOf(0f)

    var canUndo by mutableStateOf(false)

        private set
    var canRedo by mutableStateOf(false)
        private set

    private class Snapshot(val sheets: List<Pair<Sheet, HashMap<Long, Cell>>>, val widths: List<Pair<Sheet, Map<Int, Float>>>)
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private class Clip(val originR: Int, val originC: Int, val rows: Int, val cols: Int, val cells: Map<Pair<Int, Int>, Cell>)
    private var clip: Clip? = null

    private val main = Handler(Looper.getMainLooper())
    private var calcGen = 0

    val sheet: Sheet? get() = wb?.sheets?.getOrNull(active)
    val r1 get() = minOf(selR, selR2)
    val r2 get() = maxOf(selR, selR2)
    val c1 get() = minOf(selC, selC2)
    val c2 get() = maxOf(selC, selC2)

    /* ---------------- loading / saving ---------------- */

    fun load() {
        scope.launch {
            try {
                val w = withContext(Dispatchers.IO) {
                    if (sourceUri == null) XlsxIO.newWorkbook()
                    else {
                        val bytes = ctx.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                            ?: throw IOException("Cannot open the file")
                        if (kind == DocKind.CSV) XlsxIO.readCsv(String(bytes, Charsets.UTF_8))
                        else XlsxIO.read(OoXml.readZip(bytes.inputStream()))
                    }
                }
                if (sourceUri != null) {
                    name = FileOps.displayName(ctx, sourceUri)
                    Recents.add(ctx, sourceUri, name, kind)
                }
                wb = w
                select(0, 0)
                recalc()
            } catch (e: Exception) {
                error = "Could not open this spreadsheet.\n${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    /** Recalculates every formula on a background thread with a large stack (deep dependency chains). */
    fun recalc() {
        val w = wb ?: return
        val e = Evaluator(w).snapshot()
        val gen = ++calcGen
        Thread(null, {
            try { e.computeAll() } catch (_: Throwable) {}
            main.post { if (gen == calcGen) { ev = e; version++ } }
        }, "calc", 64L shl 20).start()
        version++
    }

    private fun evaluatorForSave(): Evaluator {
        val w = wb!!
        return ev ?: Evaluator(w).snapshot().computeAll()
    }

    /** Writes the workbook. [asKind] = SHEET (xlsx) or CSV. Returns false if a Save As location is needed. */
    fun save(target: Uri?, asKind: DocKind, onDone: (Boolean) -> Unit = {}) {
        val w = wb ?: return
        val uri = target ?: currentUri
        if (uri == null) { onDone(false); return }
        commitEditIfAny()
        busy = "Saving…"
        val e = evaluatorForSave()
        val sh = sheet!!
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    if (asKind == DocKind.CSV) XlsxIO.writeCsv(w, sh, e).toByteArray() else XlsxIO.write(w, e)
                }
                FileOps.writeBytesToUri(ctx, bytes, uri)
                if (target != null) {
                    currentUri = target
                    FileOps.persist(ctx, target)
                    name = FileOps.displayName(ctx, target)
                }
                Recents.add(ctx, uri, name, asKind)
                dirty = false
                message = if (asKind == DocKind.CSV && w.sheets.size > 1) "Saved (CSV keeps only the current sheet)" else "Saved"
                onDone(true)
            } catch (ex: Exception) {
                message = "Save failed: ${ex.message}"
                onDone(false)
            } finally {
                busy = null
            }
        }
    }

    /** True when "Save" can write back to the opened file in the same format. */
    fun canSaveInPlace(): Boolean = currentUri != null

    suspend fun writeTemp(ext: String): File {
        val w = wb!!
        val e = evaluatorForSave()
        val sh = sheet!!
        return withContext(Dispatchers.Default) {
            val f = FileOps.tempFile(ctx, ext)
            when (ext) {
                "pdf" -> SheetPdfExporter.export(w, sh, e, f)
                "csv" -> f.writeText(XlsxIO.writeCsv(w, sh, e))
                else -> f.writeBytes(XlsxIO.write(w, e))
            }
            f
        }
    }

    /* ---------------- selection & editing ---------------- */

    fun select(r: Int, c: Int, r2: Int = r, c2: Int = c) {
        selR = r.coerceIn(0, Sheet.MAX_ROWS - 1); selC = c.coerceIn(0, Sheet.MAX_COLS - 1)
        selR2 = r2.coerceIn(0, Sheet.MAX_ROWS - 1); selC2 = c2.coerceIn(0, Sheet.MAX_COLS - 1)
        if (!editing) loadEditText()
    }

    fun extendTo(r: Int, c: Int) {
        selR2 = r.coerceAtLeast(0); selC2 = c.coerceAtLeast(0)
    }

    private fun loadEditText() {
        val raw = sheet?.get(selR, selC)?.raw ?: ""
        editText = TextFieldValue(raw, TextRange(raw.length))
    }

    fun startEditing() {
        editing = true
        focusRequests++
    }

    fun cancelEdit() {
        editing = false
        loadEditText()
    }

    /** While typing a formula, tapping a cell inserts its reference. */
    fun wantsReference(): Boolean {
        if (!editing) return false
        val t = editText.text
        if (!t.startsWith("=")) return false
        val before = t.substring(0, editText.selection.start.coerceIn(0, t.length)).trimEnd()
        return before.isEmpty() || before.last() in "=(,;+-*/^&<>:"
    }

    fun insertReference(r: Int, c: Int, r2: Int = r, c2: Int = c) {
        val ref = Addr.rangeName(minOf(r, r2), minOf(c, c2), maxOf(r, r2), maxOf(c, c2))
        val t = editText.text
        val pos = editText.selection.start.coerceIn(0, t.length)
        val nt = t.substring(0, pos) + ref + t.substring(pos)
        editText = TextFieldValue(nt, TextRange(pos + ref.length))
    }

    fun commitEditIfAny() {
        if (editing) commitEdit(moveDown = false)
    }

    fun commitEdit(moveDown: Boolean = true) {
        val sh = sheet ?: return
        editing = false
        val raw = editText.text
        val old = sh[selR, selC]
        if ((old?.raw ?: "") != raw) {
            pushUndo()
            val style = old?.style ?: 0
            val forceText = wb?.style(style)?.numFmt == "@" && !raw.startsWith("=")
            sh.put(selR, selC, Cell(raw, style, forceText))
            changed()
        }
        if (moveDown) select(selR + 1, selC) else loadEditText()
    }

    /* ---------------- undo ---------------- */

    private fun snapshot(): Snapshot {
        val w = wb!!
        return Snapshot(w.sheets.map { it to HashMap(it.cells) }, w.sheets.map { it to HashMap(it.colWidths) })
    }

    fun pushUndo() {
        if (wb == null) return
        undoStack.addLast(snapshot())
        if (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true; canRedo = false
    }

    private fun restore(s: Snapshot) {
        for ((sh, cells) in s.sheets) sh.cells = HashMap(cells)
        for ((sh, w) in s.widths) { sh.colWidths.clear(); sh.colWidths.putAll(w); sh.colsDirty = true }
        loadEditText()
        changed()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(s)
        canUndo = undoStack.isNotEmpty(); canRedo = true
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(s)
        canUndo = true; canRedo = redoStack.isNotEmpty()
    }

    private fun clearHistory() {
        undoStack.clear(); redoStack.clear(); canUndo = false; canRedo = false
    }

    private fun changed() {
        dirty = true
        recalc()
    }

    /* ---------------- formatting ---------------- */

    fun activeStyle(): CellStyle {
        val w = wb ?: return CellStyle()
        return w.style(sheet?.get(selR, selC)?.style ?: 0)
    }

    fun applyStyle(change: (CellStyle) -> CellStyle) {
        val w = wb ?: return
        val sh = sheet ?: return
        pushUndo()
        val lastR = if (r2 - r1 > 5000) minOf(r2, maxOf(sh.maxRow(), r1)) else r2
        val lastC = if (c2 - c1 > 500) minOf(c2, maxOf(sh.maxCol(), c1)) else c2
        for (r in r1..lastR) for (c in c1..lastC) {
            val cell = sh[r, c] ?: Cell("")
            val ns = w.styleIndex(change(w.style(cell.style)))
            sh.put(r, c, cell.copy(style = ns))
        }
        changed()
    }

    fun toggleBold() { val on = !activeStyle().bold; applyStyle { it.copy(bold = on) } }
    fun toggleItalic() { val on = !activeStyle().italic; applyStyle { it.copy(italic = on) } }
    fun toggleUnderline() { val on = !activeStyle().underline; applyStyle { it.copy(underline = on) } }
    fun toggleWrap() { val on = !activeStyle().wrap; applyStyle { it.copy(wrap = on) } }
    fun setAlign(a: HAlign) = applyStyle { it.copy(hAlign = a) }
    fun setFill(c: Int?) = applyStyle { it.copy(fillColor = c) }
    fun setFontColor(c: Int?) = applyStyle { it.copy(fontColor = c) }
    fun setNumFmt(code: String) = applyStyle { it.copy(numFmt = code) }
    fun changeFontSize(delta: Float) {
        val s = (activeStyle().fontSize + delta).coerceIn(6f, 48f)
        applyStyle { it.copy(fontSize = s) }
    }

    /* ---------------- cell operations ---------------- */

    fun clearContents() {
        val sh = sheet ?: return
        pushUndo()
        val keys = sh.cells.keys.filter { val r = Sheet.rowOf(it); val c = Sheet.colOf(it); r in r1..r2 && c in c1..c2 }
        for (k in keys) {
            val cell = sh.cells[k] ?: continue
            if (cell.style == 0) sh.cells.remove(k) else sh.cells[k] = Cell("", cell.style)
        }
        loadEditText()
        changed()
    }

    fun copy(cut: Boolean = false) {
        val sh = sheet ?: return
        val w = wb ?: return
        val cells = HashMap<Pair<Int, Int>, Cell>()
        for ((k, cell) in sh.cells) {
            val r = Sheet.rowOf(k); val c = Sheet.colOf(k)
            if (r in r1..r2 && c in c1..c2) cells[(r - r1) to (c - c1)] = cell
        }
        clip = Clip(r1, c1, r2 - r1 + 1, c2 - c1 + 1, cells)
        // plain text for other apps
        val e = ev
        if (e != null && (r2 - r1) * (c2 - c1) < 20000) {
            val text = (r1..r2).joinToString("\n") { r -> (c1..c2).joinToString("\t") { c -> SheetText.display(w, e, sh, r, c) } }
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cells", text))
            } catch (_: Exception) {
            }
        }
        if (cut) clearContents()
        message = if (cut) "Cut ${Addr.rangeName(r1, c1, r2, c2)}" else "Copied ${Addr.rangeName(r1, c1, r2, c2)}"
    }

    fun paste() {
        val sh = sheet ?: return
        val cp = clip
        pushUndo()
        if (cp != null) {
            for (dr in 0 until cp.rows) for (dc in 0 until cp.cols) {
                val src = cp.cells[dr to dc]
                val tr = selR + dr; val tc = selC + dc
                if (src == null) { sh.put(tr, tc, null); continue }
                val raw = if (src.isFormula) "=" + FormulaRewrite.shiftRelative(src.raw.substring(1), tr - (cp.originR + dr), tc - (cp.originC + dc)) else src.raw
                sh.put(tr, tc, src.copy(raw = raw))
            }
            select(selR, selC, selR + cp.rows - 1, selC + cp.cols - 1)
        } else {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: return
            val rows = text.trimEnd('\n', '\r').split("\n")
            rows.forEachIndexed { dr, line ->
                line.trimEnd('\r').split("\t").forEachIndexed { dc, v ->
                    val old = sh[selR + dr, selC + dc]
                    sh.put(selR + dr, selC + dc, Cell(v, old?.style ?: 0))
                }
            }
        }
        loadEditText()
        changed()
    }

    fun autoSum() {
        val sh = sheet ?: return
        val e = ev
        fun isNum(r: Int, c: Int): Boolean {
            val cell = sh[r, c] ?: return false
            val v = if (cell.isFormula) e?.cached(sh, r, c) else Evaluator.literal(cell)
            return v is V.Num
        }
        var r = selR - 1
        while (r >= 0 && isNum(r, selC)) r--
        val formula = if (r < selR - 1) "=SUM(${Addr.rangeName(r + 1, selC, selR - 1, selC)})"
        else {
            var c = selC - 1
            while (c >= 0 && isNum(selR, c)) c--
            if (c < selC - 1) "=SUM(${Addr.rangeName(selR, c + 1, selR, selC - 1)})" else "=SUM()"
        }
        editText = TextFieldValue(formula, TextRange(if (formula == "=SUM()") 5 else formula.length))
        startEditing()
    }

    fun insertFunction(fn: String) {
        val t = if (editing && editText.text.startsWith("=")) editText.text else "="
        val pos = if (editing && editText.text.startsWith("=")) editText.selection.start.coerceIn(0, t.length) else 1
        val nt = t.substring(0, pos) + "$fn(" + t.substring(pos)
        editText = TextFieldValue(nt, TextRange(pos + fn.length + 1))
        startEditing()
    }

    /* ---------------- rows / columns ---------------- */

    private fun shiftCells(isRow: Boolean, at: Int, count: Int) {
        val w = wb ?: return
        val target = sheet ?: return
        pushUndo()
        // move cells in the target sheet
        val moved = HashMap<Long, Cell>()
        for ((k, cell) in target.cells) {
            val r = Sheet.rowOf(k); val c = Sheet.colOf(k)
            val idx = if (isRow) r else c
            val nIdx = when {
                count > 0 -> if (idx >= at) idx + count else idx
                idx < at -> idx
                idx < at - count -> -1
                else -> idx + count
            }
            if (nIdx < 0) continue
            moved[if (isRow) Sheet.key(nIdx, c) else Sheet.key(r, nIdx)] = cell
        }
        target.cells = moved
        if (isRow) {
            val hs = target.rowHeights.toMap(); target.rowHeights.clear()
            for ((r, h) in hs) { val n = if (count > 0) (if (r >= at) r + count else r) else (if (r < at) r else if (r < at - count) -1 else r + count); if (n >= 0) target.rowHeights[n] = h }
        } else {
            val ws = target.colWidths.toMap(); target.colWidths.clear()
            for ((c, wd) in ws) { val n = if (count > 0) (if (c >= at) c + count else c) else (if (c < at) c else if (c < at - count) -1 else c + count); if (n >= 0) target.colWidths[n] = wd }
            target.colsDirty = true
        }
        // fix formulas everywhere
        for (sh in w.sheets) {
            for ((k, cell) in sh.cells.entries.toList()) {
                if (!cell.isFormula) continue
                val nf = FormulaRewrite.insertDelete(cell.raw.substring(1), sh.name, target.name, isRow, at, count)
                if (nf != cell.raw.substring(1)) sh.cells[k] = cell.copy(raw = "=$nf")
            }
        }
        loadEditText()
        changed()
    }

    // whole-column / whole-row selections count as one row / column for inserts
    private val rowCount get() = if (r2 - r1 > 10_000) 1 else r2 - r1 + 1
    private val colCount get() = if (c2 - c1 > 1_000) 1 else c2 - c1 + 1

    fun insertRowsAbove() = shiftCells(true, r1, rowCount)
    fun insertRowsBelow() = shiftCells(true, r1 + rowCount, rowCount)
    fun insertColsLeft() = shiftCells(false, c1, colCount)
    fun insertColsRight() = shiftCells(false, c1 + colCount, colCount)
    fun deleteRows() { shiftCells(true, r1, -(r2 - r1 + 1)); select(r1, selC) }
    fun deleteCols() { shiftCells(false, c1, -(c2 - c1 + 1)); select(selR, c1) }

    fun sort(ascending: Boolean) {
        val sh = sheet ?: return
        val e = ev ?: return
        pushUndo()
        val single = r1 == r2
        val top = if (single) 0 else r1
        val bottom = if (single) sh.maxRow() else minOf(r2, sh.maxRow())
        val left = if (single) 0 else c1
        val right = if (single) sh.maxCol() else minOf(c2, sh.maxCol())
        if (bottom <= top) return
        // keep a text header row when sorting the whole sheet
        val start = if (single && sh[top, selC]?.let { Evaluator.literal(it) } is V.Str &&
            (top + 1..bottom).any { r -> SheetText.value(e, sh, r, selC) is V.Num }
        ) top + 1 else top
        val rows = (start..bottom).toList()
        val sorted = rows.sortedWith { a, b ->
            val va = SheetText.value(e, sh, a, selC)
            val vb = SheetText.value(e, sh, b, selC)
            if (va is V.Empty && vb !is V.Empty) 1 else if (vb is V.Empty && va !is V.Empty) -1
            else { val c = e.compare(va, vb); if (ascending) c else -c }
        }
        val old = HashMap<Pair<Int, Int>, Cell?>()
        for (r in rows) for (c in left..right) old[r to c] = sh[r, c]
        sorted.forEachIndexed { i, srcRow ->
            val dst = start + i
            for (c in left..right) {
                val cell = old[srcRow to c]
                sh.put(dst, c, cell?.let { if (it.isFormula) it.copy(raw = "=" + FormulaRewrite.shiftRelative(it.raw.substring(1), dst - srcRow, 0)) else it })
            }
        }
        loadEditText()
        changed()
    }

    fun autofitColumns() {
        val w = wb ?: return
        val sh = sheet ?: return
        val e = ev ?: return
        pushUndo()
        val maxR = sh.maxRow()
        for (c in c1..minOf(c2, maxOf(sh.maxCol(), c1))) {
            var longest = 0
            for (r in 0..maxR) longest = maxOf(longest, SheetText.display(w, e, sh, r, c).length)
            sh.colWidths[c] = (longest * 1.1f + 1.5f).coerceIn(4f, 80f)
        }
        sh.colsDirty = true
        changed()
    }

    fun find(query: String): Boolean {
        val w = wb ?: return false
        val sh = sheet ?: return false
        val e = ev ?: return false
        if (query.isBlank()) return false
        val keys = sh.cells.keys.sortedWith(compareBy({ Sheet.rowOf(it) }, { Sheet.colOf(it) }))
        val after = Sheet.key(selR, selC)
        val ordered = keys.filter { it > after } + keys.filter { it <= after }
        for (k in ordered) {
            val r = Sheet.rowOf(k); val c = Sheet.colOf(k)
            val text = SheetText.display(w, e, sh, r, c)
            if (text.contains(query, true) || (sh[r, c]?.raw ?: "").contains(query, true)) {
                select(r, c)
                return true
            }
        }
        return false
    }

    /* ---------------- sheets ---------------- */

    fun switchSheet(i: Int) {
        commitEditIfAny()
        active = i.coerceIn(0, (wb?.sheets?.size ?: 1) - 1)
        scrollX = 0f; scrollY = 0f
        select(0, 0)
        version++
    }

    fun addSheet() {
        val w = wb ?: return
        w.sheets.add(Sheet(w.uniqueSheetName()))
        clearHistory()
        dirty = true
        switchSheet(w.sheets.size - 1)
        recalc()
    }

    fun renameSheet(i: Int, newName: String) {
        val w = wb ?: return
        val nn = newName.trim().replace(Regex("[\\\\/?*\\[\\]:]"), "").take(31)
        if (nn.isEmpty() || w.sheets.any { it !== w.sheets[i] && it.name.equals(nn, true) }) {
            message = "Invalid or duplicate sheet name"; return
        }
        val old = w.sheets[i].name
        w.sheets[i].name = nn
        for (sh in w.sheets) for ((k, cell) in sh.cells.entries.toList()) {
            if (cell.isFormula) {
                val nf = FormulaRewrite.renameSheet(cell.raw.substring(1), old, nn)
                if (nf != cell.raw.substring(1)) sh.cells[k] = cell.copy(raw = "=$nf")
            }
        }
        clearHistory()
        changed()
    }

    fun deleteSheet(i: Int) {
        val w = wb ?: return
        if (w.sheets.size <= 1) { message = "A workbook needs at least one sheet"; return }
        w.sheets.removeAt(i)
        clearHistory()
        dirty = true
        switchSheet(minOf(active, w.sheets.size - 1))
        recalc()
    }
}
