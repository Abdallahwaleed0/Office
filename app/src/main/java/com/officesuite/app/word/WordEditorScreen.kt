@file:OptIn(ExperimentalMaterial3Api::class)

package com.officesuite.app.word

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.officesuite.app.core.DocKind
import com.officesuite.app.core.FileOps
import com.officesuite.app.core.OoXml
import com.officesuite.app.core.Recents
import com.officesuite.app.core.Templates
import com.officesuite.app.ui.BusyOverlay
import com.officesuite.app.ui.ColorPickerDialog
import com.officesuite.app.ui.HIGHLIGHTS
import com.officesuite.app.ui.MessageDialog
import com.officesuite.app.ui.PALETTE
import com.officesuite.app.ui.UnsavedChangesDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class WordEditorState(private val ctx: Context, private val scope: CoroutineScope, private val sourceUri: Uri?, val kind: DocKind) {
    var name by mutableStateOf(if (kind == DocKind.TEXT) "Untitled.txt" else "Untitled.docx")
    var currentUri: Uri? = sourceUri
        private set
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)
    var fmt by mutableStateOf(FormatState())
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var doc: WordDocument? = null
        private set
    var content: CharSequence? = null
        private set
    var editor: RichEditText? = null
        private set
    private val res: Resources = ctx.resources
    private val density = res.displayMetrics.density
    private fun initialWidth() = res.displayMetrics.widthPixels - (44 * density).toInt()

    fun load() {
        scope.launch {
            try {
                val result: Pair<WordDocument?, CharSequence> = withContext(Dispatchers.IO) {
                    if (kind == DocKind.TEXT) {
                        val bytes = sourceUri?.let { u -> ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw IOException("Cannot open the file") }
                        Pair<WordDocument?, CharSequence>(null, SpannableStringBuilder(bytes?.let { String(it, Charsets.UTF_8).removePrefix("\uFEFF").replace("\r\n", "\n") } ?: ""))
                    } else {
                        val zip = if (sourceUri == null) Templates.newDocx()
                        else ctx.contentResolver.openInputStream(sourceUri)?.use { OoXml.readZip(it) } ?: throw IOException("Cannot open the file")
                        val (d, sb) = DocxIO.read(zip, res, initialWidth())
                        Pair<WordDocument?, CharSequence>(d, sb)
                    }
                }
                doc = result.first
                content = result.second
                if (sourceUri != null) {
                    name = FileOps.displayName(ctx, sourceUri)
                    Recents.add(ctx, sourceUri, name, kind)
                }
                editor?.let { bindContent(it) }
            } catch (e: Exception) {
                error = "Could not open this document.\n${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    fun attach(e: RichEditText) {
        editor = e
        e.onChanged = { dirty = true }
        e.onSelectionChange = { _, _ -> fmt = e.formatState() }
        e.onHistoryChanged = { canUndo = e.canUndo; canRedo = e.canRedo }
        if (content != null) bindContent(e)
    }

    private fun bindContent(e: RichEditText) {
        val c = content ?: return
        if (e.tag == "loaded") return
        e.tag = "loaded"
        e.load(c)
        e.post { e.refreshObjectWidths() }
        fmt = e.formatState()
    }

    private suspend fun encode(asKind: DocKind): ByteArray {
        val e = editor ?: throw IllegalStateException("Editor not ready")
        val snap = SpannableStringBuilder(e.text ?: "")
        return withContext(Dispatchers.Default) {
            if (asKind == DocKind.TEXT) DocxIO.plainText(snap).toByteArray()
            else {
                val d = doc ?: DocxIO.read(Templates.newDocx(), res, initialWidth()).first.also { doc = it }
                DocxIO.write(d, snap, density)
            }
        }
    }

    fun canSaveInPlace() = currentUri != null

    fun save(target: Uri?, asKind: DocKind, onDone: (Boolean) -> Unit = {}) {
        val uri = target ?: currentUri
        if (uri == null) { onDone(false); return }
        busy = "Saving…"
        scope.launch {
            try {
                val bytes = encode(asKind)
                FileOps.writeBytesToUri(ctx, bytes, uri)
                if (target != null) {
                    currentUri = target
                    FileOps.persist(ctx, target)
                    name = FileOps.displayName(ctx, target)
                }
                Recents.add(ctx, uri, name, asKind)
                dirty = false
                message = "Saved"
                onDone(true)
            } catch (ex: Exception) {
                message = "Save failed: ${ex.message}"
                onDone(false)
            } finally {
                busy = null
            }
        }
    }

    suspend fun writeTemp(ext: String): File {
        val e = editor ?: throw IllegalStateException("Editor not ready")
        val f = FileOps.tempFile(ctx, ext)
        if (ext == "pdf") {
            val snap = SpannableStringBuilder(e.text ?: "")
            withContext(Dispatchers.Default) { WordPdfExporter.export(res, snap, f) }
        } else {
            val bytes = encode(if (ext == "txt") DocKind.TEXT else DocKind.WORD)
            withContext(Dispatchers.IO) { f.writeBytes(bytes) }
        }
        return f
    }

    fun insertImage(uri: Uri) {
        val e = editor ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = FileOps.decodeImage(ctx, uri, 1600) ?: return@withContext null
                val jpeg = !bmp.hasAlpha()
                val bos = ByteArrayOutputStream()
                bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 88, bos)
                Triple(bmp, bos.toByteArray(), if (jpeg) "jpeg" else "png")
            }
            if (result == null) { message = "Could not read that image"; return@launch }
            val (bmp, bytes, ext) = result
            // fit to a 6-inch text column (EMU = 914400 per inch)
            var cx = bmp.width * 9525L
            var cy = bmp.height * 9525L
            val maxCx = 6L * 914400L
            if (cx > maxCx) { cy = cy * maxCx / cx; cx = maxCx }
            e.insertInline(DocxIO.makeImageSpan(res, bmp, null, bytes, cx, cy, e.contentWidth(), ext))
        }
    }
}

@Composable
fun WordEditorScreen(uri: Uri?, kind: DocKind, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { WordEditorState(ctx.applicationContext, scope, uri, kind) }
    LaunchedEffect(Unit) { st.load() }
    val rich = kind != DocKind.TEXT

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(st.message) {
        val m = st.message ?: return@LaunchedEffect
        st.message = null
        snackbar.showSnackbar(m)
    }

    var confirmExit by remember { mutableStateOf(false) }
    var closeAfterSave by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var tableEdit by remember { mutableStateOf<TableSpan?>(null) }

    val saveAsDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocKind.WORD.mime)) { u: Uri? ->
        if (u != null) st.save(u, DocKind.WORD) { ok -> if (ok && closeAfterSave) onClose() }
        closeAfterSave = false
    }
    val saveAsTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { u: Uri? ->
        if (u != null) st.save(u, DocKind.TEXT) { ok -> if (ok && closeAfterSave) onClose() }
        closeAfterSave = false
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u: Uri? ->
        if (u != null) scope.launch {
            st.busy = "Exporting PDF…"
            try {
                FileOps.writeFileToUri(ctx, st.writeTemp("pdf"), u)
                st.message = "PDF exported"
            } catch (e: Exception) {
                st.message = "Export failed: ${e.message}"
            } finally {
                st.busy = null
            }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        if (u != null) st.insertImage(u)
    }

    fun save(thenClose: Boolean = false) {
        if (st.canSaveInPlace()) {
            st.save(null, kind) { ok ->
                if (ok && thenClose) onClose()
                if (!ok) {
                    closeAfterSave = thenClose
                    if (rich) saveAsDocx.launch(FileOps.withExt(st.name, "docx")) else saveAsTxt.launch(FileOps.withExt(st.name, "txt"))
                }
            }
        } else {
            closeAfterSave = thenClose
            if (rich) saveAsDocx.launch(FileOps.withExt(st.name, "docx")) else saveAsTxt.launch(FileOps.withExt(st.name, "txt"))
        }
    }

    BackHandler { if (st.dirty) confirmExit = true else onClose() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            var menu by remember { mutableStateOf(false) }
            TopAppBar(
                title = { Text(st.name + if (st.dirty) " \u2022" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = { if (st.dirty) confirmExit = true else onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { st.editor?.undo() }, enabled = st.canUndo) { Icon(Icons.Filled.Undo, "Undo") }
                    IconButton(onClick = { st.editor?.redo() }, enabled = st.canRedo) { Icon(Icons.Filled.Redo, "Redo") }
                    IconButton(onClick = { save() }) { Icon(Icons.Filled.Save, "Save") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Save as Word (.docx)") }, onClick = { menu = false; saveAsDocx.launch(FileOps.withExt(st.name, "docx")) })
                            DropdownMenuItem(text = { Text("Save as plain text (.txt)") }, onClick = { menu = false; saveAsTxt.launch(FileOps.withExt(st.name, "txt")) })
                            DropdownMenuItem(text = { Text("Export as PDF") }, onClick = { menu = false; exportPdf.launch(FileOps.withExt(st.name, "pdf")) })
                            DropdownMenuItem(text = { Text("Share") }, onClick = {
                                menu = false
                                scope.launch {
                                    try {
                                        val ext = if (rich) "docx" else "txt"
                                        FileOps.share(ctx, st.writeTemp(ext), if (rich) DocKind.WORD.mime else "text/plain", FileOps.withExt(st.name, ext))
                                    } catch (e: Exception) { st.message = "Share failed: ${e.message}" }
                                }
                            })
                            DropdownMenuItem(text = { Text("Print") }, onClick = {
                                menu = false
                                scope.launch {
                                    try { FileOps.print(ctx, st.writeTemp("pdf"), st.name) } catch (e: Exception) { st.message = "Print failed: ${e.message}" }
                                }
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Find & replace") }, onClick = { menu = false; dialog = "find" })
                            DropdownMenuItem(text = { Text("Word count") }, onClick = { menu = false; dialog = "count" })
                        }
                    }
                }
            )
            if (rich && !st.loading && st.error == null) {
                WordToolbar(st, onDialog = { dialog = it }, onPickImage = { pickImage.launch("image/*") })
                HorizontalDivider()
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    st.error != null -> Text(st.error!!, Modifier.padding(24.dp))
                    else -> AndroidView(
                        factory = { c ->
                            RichEditText(c).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                onTableTap = { tableEdit = it }
                                st.attach(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (st.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        st.busy?.let { BusyOverlay(it) }
    }

    if (confirmExit) {
        UnsavedChangesDialog(
            onSave = { confirmExit = false; save(thenClose = true) },
            onDiscard = { confirmExit = false; onClose() },
            onCancel = { confirmExit = false }
        )
    }

    tableEdit?.let { span ->
        TableEditDialog(
            span.table,
            onDismiss = { tableEdit = null },
            onDelete = { st.editor?.deleteObject(span); tableEdit = null },
            onDone = { rows ->
                st.editor?.snapshot()
                span.table.rows.clear()
                span.table.rows.addAll(rows)
                span.table.dirty = true
                st.editor?.tableChanged(span)
                tableEdit = null
            }
        )
    }

    when (dialog) {
        "color" -> ColorPickerDialog("Text color", PALETTE, allowNone = true, onDismiss = { dialog = null }) { st.editor?.setColor(it); dialog = null }
        "highlight" -> ColorPickerDialog("Highlight", HIGHLIGHTS, allowNone = true, onDismiss = { dialog = null }) { st.editor?.setHighlight(it); dialog = null }
        "table" -> InsertTableDialog(onDismiss = { dialog = null }) { r, c ->
            dialog = null
            val e = st.editor ?: return@InsertTableDialog
            val td = TableData(MutableList(r) { MutableList(c) { "" } }, null, dirty = true)
            e.insertBlock(TableSpan(td, e.contentWidth(), e.density))
        }
        "find" -> FindReplaceDialog(st, onDismiss = { dialog = null })
        "count" -> {
            val (w, c) = st.editor?.wordCount() ?: (0 to 0)
            MessageDialog("Word count", "Words: $w\nCharacters (no spaces): $c") { dialog = null }
        }
    }
}

@Composable
private fun WB(icon: ImageVector? = null, label: String? = null, desc: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).height(40.dp).let { if (label != null && icon == null) it.width(56.dp) else it.width(40.dp) }
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Icon(icon, desc, Modifier.size(22.dp)) else Text(label ?: "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun WordToolbar(st: WordEditorState, onDialog: (String) -> Unit, onPickImage: () -> Unit) {
    val f = st.fmt
    var styleMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            val styleName = when (f.heading) { null -> "Normal"; 0 -> "Title"; else -> "Heading ${f.heading}" }
            Text(
                styleName + " ▾",
                Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp)).clickable { styleMenu = true }
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 9.dp),
                maxLines = 1
            )
            DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                listOf<Pair<String, Int?>>("Normal" to null, "Title" to 0, "Heading 1" to 1, "Heading 2" to 2, "Heading 3" to 3).forEach { (n, lvl) ->
                    DropdownMenuItem(text = { Text(n) }, onClick = { styleMenu = false; st.editor?.setHeading(lvl) })
                }
            }
        }
        Box {
            Text(
                "${f.sizePt} ▾",
                Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp)).clickable { sizeMenu = true }
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 9.dp)
            )
            DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                listOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36, 48, 72).forEach { s ->
                    DropdownMenuItem(text = { Text("$s") }, onClick = { sizeMenu = false; st.editor?.setSizePt(s) })
                }
            }
        }
        WB(Icons.Filled.FormatBold, desc = "Bold", on = f.bold) { st.editor?.toggleStyle(android.graphics.Typeface.BOLD) }
        WB(Icons.Filled.FormatItalic, desc = "Italic", on = f.italic) { st.editor?.toggleStyle(android.graphics.Typeface.ITALIC) }
        WB(Icons.Filled.FormatUnderlined, desc = "Underline", on = f.underline) { st.editor?.toggleUnderline() }
        WB(Icons.Filled.FormatStrikethrough, desc = "Strikethrough", on = f.strike) { st.editor?.toggleStrike() }
        WB(Icons.Filled.FormatColorText, desc = "Text color") { onDialog("color") }
        WB(Icons.Filled.BorderColor, desc = "Highlight") { onDialog("highlight") }
        WB(Icons.Filled.FormatAlignLeft, desc = "Align left", on = f.align == Layout.Alignment.ALIGN_NORMAL) { st.editor?.setAlignment(Layout.Alignment.ALIGN_NORMAL) }
        WB(Icons.Filled.FormatAlignCenter, desc = "Center", on = f.align == Layout.Alignment.ALIGN_CENTER) { st.editor?.setAlignment(Layout.Alignment.ALIGN_CENTER) }
        WB(Icons.Filled.FormatAlignRight, desc = "Align right", on = f.align == Layout.Alignment.ALIGN_OPPOSITE) { st.editor?.setAlignment(Layout.Alignment.ALIGN_OPPOSITE) }
        WB(Icons.Filled.FormatListBulleted, desc = "Bullets", on = f.list) { st.editor?.toggleBullets() }
        WB(Icons.Filled.Image, desc = "Insert image") { onPickImage() }
        WB(Icons.Filled.TableChart, desc = "Insert table") { onDialog("table") }
        WB(Icons.Filled.FormatClear, desc = "Clear formatting") { st.editor?.clearFormatting() }
    }
}

@Composable
private fun InsertTableDialog(onDismiss: () -> Unit, onInsert: (Int, Int) -> Unit) {
    var rows by remember { mutableStateOf("3") }
    var cols by remember { mutableStateOf("3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert table") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(rows, { rows = it.filter(Char::isDigit).take(2) }, label = { Text("Rows") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(cols, { cols = it.filter(Char::isDigit).take(2) }, label = { Text("Columns") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onInsert((rows.toIntOrNull() ?: 3).coerceIn(1, 50), (cols.toIntOrNull() ?: 3).coerceIn(1, 12))
            }) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TableEditDialog(table: TableData, onDismiss: () -> Unit, onDelete: () -> Unit, onDone: (MutableList<MutableList<String>>) -> Unit) {
    val cols0 = maxOf(1, table.cols)
    val grid = remember {
        mutableStateListOf<MutableList<String>>().apply {
            table.rows.forEach { r -> add(MutableList(cols0) { c -> r.getOrNull(c) ?: "" }) }
        }
    }
    var cols by remember { mutableStateOf(cols0) }
    var version by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit table") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { grid.add(MutableList(cols) { "" }); version++ }) { Text("+ Row") }
                    TextButton(onClick = { grid.forEach { it.add("") }; cols++; version++ }) { Text("+ Column") }
                    TextButton(onClick = { if (grid.size > 1) { grid.removeAt(grid.size - 1); version++ } }) { Text("− Row") }
                    TextButton(onClick = { if (cols > 1) { grid.forEach { it.removeAt(it.size - 1) }; cols--; version++ } }) { Text("− Column") }
                }
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    @Suppress("UNUSED_VARIABLE") val v = version
                    grid.forEachIndexed { r, row ->
                        Row {
                            for (c in 0 until cols) {
                                var value by remember(r, c, version) { mutableStateOf(row.getOrNull(c) ?: "") }
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { value = it; if (c < row.size) row[c] = it },
                                    modifier = Modifier.width(130.dp).padding(2.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(grid.map { it.toMutableList() }.toMutableList()) }) { Text("Done") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete table") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun FindReplaceDialog(st: WordEditorState, onDismiss: () -> Unit) {
    var find by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find & replace") },
        text = {
            Column {
                OutlinedTextField(find, { find = it }, label = { Text("Find") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(repl, { repl = it }, label = { Text("Replace with") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(matchCase, { matchCase = it })
                    Text("Match case")
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { status = if (st.editor?.findNext(find, matchCase) == true) "" else "Not found" }) { Text("Find next") }
                    TextButton(onClick = { status = if (st.editor?.replaceCurrent(find, repl, matchCase) == true) "" else "No more matches" }) { Text("Replace") }
                    TextButton(onClick = { status = "Replaced ${st.editor?.replaceAll(find, repl, matchCase) ?: 0}" }) { Text("Replace all") }
                }
                if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
