@file:OptIn(ExperimentalMaterial3Api::class)

package com.officesuite.app.sheet

import android.net.Uri
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.officesuite.app.core.DocKind
import com.officesuite.app.core.FileOps
import com.officesuite.app.ui.BusyOverlay
import com.officesuite.app.ui.ColorPickerDialog
import com.officesuite.app.ui.HIGHLIGHTS
import com.officesuite.app.ui.PALETTE
import com.officesuite.app.ui.TextPromptDialog
import com.officesuite.app.ui.UnsavedChangesDialog
import kotlinx.coroutines.launch

@Composable
fun SheetEditorScreen(uri: Uri?, kind: DocKind, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { SheetEditorState(ctx.applicationContext, scope, uri, kind) }
    LaunchedEffect(Unit) { st.load() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(st.message) {
        val m = st.message ?: return@LaunchedEffect
        st.message = null
        snackbar.showSnackbar(m)
    }

    var confirmExit by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var closeAfterSave by remember { mutableStateOf(false) }

    val saveAsXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocKind.SHEET.mime)) { u: Uri? ->
        if (u != null) st.save(u, DocKind.SHEET) { ok -> if (ok && closeAfterSave) onClose() }
        closeAfterSave = false
    }
    val saveAsCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u: Uri? ->
        if (u != null) st.save(u, DocKind.CSV)
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u: Uri? ->
        if (u != null) scope.launch {
            st.busy = "Exporting PDF…"
            try {
                val f = st.writeTemp("pdf")
                FileOps.writeFileToUri(ctx, f, u)
                st.message = "PDF exported"
            } catch (e: Exception) {
                st.message = "Export failed: ${e.message}"
            } finally {
                st.busy = null
            }
        }
    }

    fun save(thenClose: Boolean = false) {
        if (st.canSaveInPlace()) {
            st.save(null, if (kind == DocKind.CSV) DocKind.CSV else DocKind.SHEET) { ok ->
                if (ok && thenClose) onClose()
                if (!ok) { closeAfterSave = thenClose; saveAsXlsx.launch(FileOps.withExt(st.name, "xlsx")) }
            }
        } else {
            closeAfterSave = thenClose
            saveAsXlsx.launch(FileOps.withExt(st.name, "xlsx"))
        }
    }

    fun back() {
        when {
            st.editing -> st.cancelEdit()
            st.dirty -> confirmExit = true
            else -> onClose()
        }
    }
    BackHandler { back() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            var menu by remember { mutableStateOf(false) }
            TopAppBar(
                title = {
                    Text(st.name + if (st.dirty) " \u2022" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { st.undo() }, enabled = st.canUndo) { Icon(Icons.Filled.Undo, "Undo") }
                    IconButton(onClick = { st.redo() }, enabled = st.canRedo) { Icon(Icons.Filled.Redo, "Redo") }
                    IconButton(onClick = { save() }) { Icon(Icons.Filled.Save, "Save") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Save as Excel (.xlsx)") }, onClick = {
                                menu = false; saveAsXlsx.launch(FileOps.withExt(st.name, "xlsx"))
                            })
                            DropdownMenuItem(text = { Text("Save as CSV") }, onClick = {
                                menu = false; saveAsCsv.launch(FileOps.withExt(st.name, "csv"))
                            })
                            DropdownMenuItem(text = { Text("Export as PDF") }, onClick = {
                                menu = false; exportPdf.launch(FileOps.withExt(st.name, "pdf"))
                            })
                            DropdownMenuItem(text = { Text("Share") }, onClick = {
                                menu = false
                                scope.launch {
                                    try {
                                        val ext = if (kind == DocKind.CSV) "csv" else "xlsx"
                                        val f = st.writeTemp(ext)
                                        FileOps.share(ctx, f, if (ext == "csv") "text/csv" else DocKind.SHEET.mime, FileOps.withExt(st.name, ext))
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
                            DropdownMenuItem(text = { Text("Find") }, onClick = { menu = false; dialog = "find" })
                            DropdownMenuItem(text = { Text("Go to cell") }, onClick = { menu = false; dialog = "goto" })
                            DropdownMenuItem(text = { Text("Add sheet") }, onClick = { menu = false; st.addSheet() })
                        }
                    }
                }
            )
            when {
                st.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                st.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(st.error!!) }
                else -> {
                    FormulaBar(st)
                    FormatToolbar(st) { dialog = it }
                    HorizontalDivider()
                    SheetGrid(st, Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider()
                    SheetTabs(st) { dialog = it }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
        st.busy?.let { BusyOverlay(it) }
    }

    if (confirmExit) {
        UnsavedChangesDialog(
            onSave = { confirmExit = false; save(thenClose = true) },
            onDiscard = { confirmExit = false; onClose() },
            onCancel = { confirmExit = false }
        )
    }

    val d = dialog
    when {
        d == null -> {}
        d == "find" -> TextPromptDialog(title = "Find", label = "Text or number", confirm = "Find next", onDismiss = { dialog = null }) { q ->
            if (!st.find(q)) st.message = "No matches for \"$q\""
        }
        d == "goto" -> TextPromptDialog(title = "Go to cell", label = "e.g. B12", confirm = "Go", onDismiss = { dialog = null }) { q ->
            val p = Addr.parse(q.trim().uppercase())
            if (p == null) st.message = "Invalid cell reference" else { st.select(p.first, p.second); st.scrollTarget = p }
            dialog = null
        }
        d == "fill" -> ColorPickerDialog("Fill color", HIGHLIGHTS + PALETTE, allowNone = true, onDismiss = { dialog = null }) {
            st.setFill(it); dialog = null
        }
        d == "fontcolor" -> ColorPickerDialog("Text color", PALETTE, allowNone = true, onDismiss = { dialog = null }) {
            st.setFontColor(it); dialog = null
        }
        d == "functions" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Insert function") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(Functions.NAMES) { fn ->
                        Text(
                            fn,
                            Modifier.fillMaxWidth().clickable { st.insertFunction(fn); dialog = null }.padding(vertical = 10.dp, horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } }
        )
        d.startsWith("rename:") -> {
            val i = d.removePrefix("rename:").toInt()
            TextPromptDialog(title = "Rename sheet", initial = st.wb?.sheets?.getOrNull(i)?.name ?: "", confirm = "Rename", onDismiss = { dialog = null }) {
                st.renameSheet(i, it); dialog = null
            }
        }
        d.startsWith("delete:") -> {
            val i = d.removePrefix("delete:").toInt()
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Delete sheet?") },
                text = { Text("\"${st.wb?.sheets?.getOrNull(i)?.name}\" and its data will be removed. This can't be undone.") },
                confirmButton = { TextButton(onClick = { st.deleteSheet(i); dialog = null }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun FormulaBar(st: SheetEditorState) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(st.focusRequests) {
        if (st.focusRequests > 0) try { focus.requestFocus() } catch (_: Exception) {}
    }
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            Addr.rangeName(st.r1, st.c1, minOf(st.r2, 99_999), minOf(st.c2, 999)).let {
                if (st.r2 >= Sheet.MAX_ROWS - 1 || st.c2 >= Sheet.MAX_COLS - 1) Addr.name(st.selR, st.selC) else it
            },
            Modifier.widthIn(min = 52.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
        Text("fx", fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
        BasicTextField(
            value = st.editText,
            onValueChange = { st.editText = it; st.editing = true },
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { st.commitEdit(); focusManager.clearFocus() }),
            modifier = Modifier.weight(1f)
                .focusRequester(focus)
                .onFocusChanged { if (it.isFocused) st.editing = true }
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 9.dp)
        )
        if (st.editing) {
            IconButton(onClick = { st.cancelEdit(); focusManager.clearFocus() }) { Icon(Icons.Filled.Close, "Cancel") }
            IconButton(onClick = { st.commitEdit(); focusManager.clearFocus() }) { Icon(Icons.Filled.Check, "Enter") }
        }
    }
}

@Composable
private fun TB(icon: ImageVector? = null, label: String? = null, desc: String, on: Boolean = false, onClick: () -> Unit) {
    val bg = if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(
        Modifier.padding(horizontal = 2.dp).size(width = if (label != null && label.length > 2) 52.dp else 40.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Icon(icon, desc, Modifier.size(22.dp))
        else Text(label ?: "", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun FormatToolbar(st: SheetEditorState, openDialog: (String) -> Unit) {
    val style = st.activeStyle()
    var fmtMenu by remember { mutableStateOf(false) }
    var insMenu by remember { mutableStateOf(false) }
    var delMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TB(label = "Σ", desc = "AutoSum") { st.autoSum() }
        TB(label = "fx", desc = "Functions") { openDialog("functions") }
        TB(Icons.Filled.FormatBold, desc = "Bold", on = style.bold) { st.toggleBold() }
        TB(Icons.Filled.FormatItalic, desc = "Italic", on = style.italic) { st.toggleItalic() }
        TB(Icons.Filled.FormatUnderlined, desc = "Underline", on = style.underline) { st.toggleUnderline() }
        TB(label = "A−", desc = "Smaller text") { st.changeFontSize(-1f) }
        TB(label = "A+", desc = "Larger text") { st.changeFontSize(1f) }
        TB(Icons.Filled.FormatColorText, desc = "Text color") { openDialog("fontcolor") }
        TB(Icons.Filled.FormatColorFill, desc = "Fill color") { openDialog("fill") }
        TB(Icons.Filled.FormatAlignLeft, desc = "Align left", on = style.hAlign == HAlign.LEFT) { st.setAlign(if (style.hAlign == HAlign.LEFT) HAlign.GENERAL else HAlign.LEFT) }
        TB(Icons.Filled.FormatAlignCenter, desc = "Center", on = style.hAlign == HAlign.CENTER) { st.setAlign(if (style.hAlign == HAlign.CENTER) HAlign.GENERAL else HAlign.CENTER) }
        TB(Icons.Filled.FormatAlignRight, desc = "Align right", on = style.hAlign == HAlign.RIGHT) { st.setAlign(if (style.hAlign == HAlign.RIGHT) HAlign.GENERAL else HAlign.RIGHT) }
        TB(Icons.Filled.WrapText, desc = "Wrap text", on = style.wrap) { st.toggleWrap() }
        Box {
            TB(label = "123", desc = "Number format") { fmtMenu = true }
            DropdownMenu(expanded = fmtMenu, onDismissRequest = { fmtMenu = false }) {
                NumFmt.PRESETS.forEach { (label, code) ->
                    DropdownMenuItem(
                        text = { Text(if (code == style.numFmt) "✓  $label" else "     $label") },
                        onClick = { fmtMenu = false; st.setNumFmt(code) }
                    )
                }
            }
        }
        Box {
            TB(Icons.Filled.Add, desc = "Insert") { insMenu = true }
            DropdownMenu(expanded = insMenu, onDismissRequest = { insMenu = false }) {
                DropdownMenuItem(text = { Text("Insert row above") }, onClick = { insMenu = false; st.insertRowsAbove() })
                DropdownMenuItem(text = { Text("Insert row below") }, onClick = { insMenu = false; st.insertRowsBelow() })
                DropdownMenuItem(text = { Text("Insert column left") }, onClick = { insMenu = false; st.insertColsLeft() })
                DropdownMenuItem(text = { Text("Insert column right") }, onClick = { insMenu = false; st.insertColsRight() })
            }
        }
        Box {
            TB(Icons.Filled.Delete, desc = "Delete") { delMenu = true }
            DropdownMenu(expanded = delMenu, onDismissRequest = { delMenu = false }) {
                DropdownMenuItem(text = { Text("Clear contents") }, onClick = { delMenu = false; st.clearContents() })
                DropdownMenuItem(text = { Text("Delete rows") }, onClick = { delMenu = false; st.deleteRows() })
                DropdownMenuItem(text = { Text("Delete columns") }, onClick = { delMenu = false; st.deleteCols() })
            }
        }
        Box {
            TB(Icons.Filled.SortByAlpha, desc = "Sort") { sortMenu = true }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                DropdownMenuItem(text = { Text("Sort A → Z (by this column)") }, onClick = { sortMenu = false; st.sort(true) })
                DropdownMenuItem(text = { Text("Sort Z → A (by this column)") }, onClick = { sortMenu = false; st.sort(false) })
            }
        }
        TB(Icons.Filled.ContentCopy, desc = "Copy") { st.copy() }
        TB(Icons.Filled.ContentCut, desc = "Cut") { st.copy(cut = true) }
        TB(Icons.Filled.ContentPaste, desc = "Paste") { st.paste() }
        TB(label = "↔", desc = "Autofit column width") { st.autofitColumns() }
    }
}

@Composable
private fun SheetTabs(st: SheetEditorState, openDialog: (String) -> Unit) {
    val wb = st.wb ?: return
    var menuFor by remember { mutableStateOf(-1) }
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { st.addSheet() }) { Icon(Icons.Filled.Add, "Add sheet") }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(wb.sheets.map { it.name }) { i, name ->
                val sel = i == st.active
                Box {
                    Surface(
                        color = if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { if (sel) menuFor = i else st.switchSheet(i) }
                    ) {
                        Text(
                            name,
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                    DropdownMenu(expanded = menuFor == i, onDismissRequest = { menuFor = -1 }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = -1; openDialog("rename:$i") })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = -1; openDialog("delete:$i") })
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}
