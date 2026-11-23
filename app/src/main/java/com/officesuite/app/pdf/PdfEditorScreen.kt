@file:OptIn(ExperimentalMaterial3Api::class)

package com.officesuite.app.pdf

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.officesuite.app.core.DocKind
import com.officesuite.app.core.FileOps
import com.officesuite.app.ui.BusyOverlay
import com.officesuite.app.ui.ColorSwatches
import com.officesuite.app.ui.HIGHLIGHTS
import com.officesuite.app.ui.PALETTE
import com.officesuite.app.ui.TextPromptDialog
import com.officesuite.app.ui.UnsavedChangesDialog
import kotlinx.coroutines.launch

private val DRAW_TOOLS = setOf(Tool.PEN, Tool.HIGHLIGHT, Tool.ERASER, Tool.WHITEOUT)

@Composable
fun PdfEditorScreen(uri: Uri, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { PdfEditorState(ctx.applicationContext, scope, uri) }
    LaunchedEffect(Unit) { st.load() }
    DisposableEffect(Unit) { onDispose { st.close() } }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(st.message) {
        val m = st.message ?: return@LaunchedEffect
        st.message = null
        snackbar.showSnackbar(m)
    }
    val listState = rememberLazyListState()
    var dialog by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var closeAfterSave by remember { mutableStateOf(false) }
    var extractPages by remember { mutableStateOf<List<Int>>(emptyList()) }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u: Uri? ->
        if (u != null) st.save(u) { ok -> if (ok && closeAfterSave) onClose() }
        closeAfterSave = false
    }
    val extractLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u: Uri? ->
        val pages = extractPages
        if (u != null && pages.isNotEmpty()) scope.launch {
            st.busy = "Extracting pages…"
            try {
                val src = st.exportFile()
                val out = FileOps.tempFile(ctx, "pdf")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PdfEngine.extract(src, out, pages) }
                FileOps.writeFileToUri(ctx, out, u)
                st.message = "Saved ${pages.size} page(s) as a new PDF"
            } catch (e: Exception) {
                st.message = "Extract failed: ${e.message}"
            } finally {
                st.busy = null
            }
        }
    }
    val toWord = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocKind.WORD.mime)) { u: Uri? ->
        if (u != null) scope.launch {
            st.busy = "Converting to Word…"
            try {
                FileOps.writeBytesToUri(ctx, st.toDocx(), u)
                st.message = "Converted to Word"
            } catch (e: Exception) {
                st.message = "Conversion failed: ${e.message}"
            } finally {
                st.busy = null
            }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        if (u != null) scope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FileOps.decodeImage(ctx, u, 2000) }
            val s = st.session
            if (bmp == null || s == null) { st.message = "Could not read that image"; return@launch }
            val page = listState.firstVisibleItemIndex.coerceIn(0, s.pageCount - 1)
            val size = s.sizes[page]
            st.addImage(bmp, page, 0.5f, size.width / size.height)
        }
    }

    fun save(thenClose: Boolean = false) {
        st.save(null) { ok ->
            if (ok && thenClose) onClose()
            if (!ok) { closeAfterSave = thenClose; saveAs.launch(st.name) }
        }
    }

    BackHandler {
        when {
            st.selectedId != null -> st.selectedId = null
            st.tool != Tool.VIEW -> st.tool = Tool.VIEW
            st.dirty -> confirmExit = true
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            var menu by remember { mutableStateOf(false) }
            TopAppBar(
                title = { Text(st.name + if (st.dirty) " •" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = { if (st.dirty) confirmExit = true else onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { st.undo() }, enabled = st.canUndo) { Icon(Icons.Filled.Undo, "Undo") }
                    IconButton(onClick = { st.redo() }, enabled = st.canRedo) { Icon(Icons.Filled.Redo, "Redo") }
                    IconButton(onClick = { save() }, enabled = st.session != null) { Icon(Icons.Filled.Save, "Save") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Save as copy") }, onClick = { menu = false; saveAs.launch(st.name) })
                            DropdownMenuItem(text = { Text("Share") }, onClick = {
                                menu = false
                                scope.launch {
                                    try { FileOps.share(ctx, st.exportFile(), "application/pdf", st.name) } catch (e: Exception) { st.message = "Share failed: ${e.message}" }
                                }
                            })
                            DropdownMenuItem(text = { Text("Print") }, onClick = {
                                menu = false
                                scope.launch {
                                    try { FileOps.print(ctx, st.exportFile(), st.name) } catch (e: Exception) { st.message = "Print failed: ${e.message}" }
                                }
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Pages: rotate, reorder, delete…") }, onClick = { menu = false; dialog = "pages" })
                            DropdownMenuItem(text = { Text("Fill form") }, onClick = { menu = false; dialog = "form" })
                            DropdownMenuItem(text = { Text("Search text") }, onClick = { menu = false; dialog = "search" })
                            DropdownMenuItem(text = { Text("Go to page") }, onClick = { menu = false; dialog = "goto" })
                            DropdownMenuItem(text = { Text(if (st.night) "Day mode" else "Night mode") }, onClick = { menu = false; st.night = !st.night })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(if (st.hasPassword) "Change / remove password" else "Protect with password") }, onClick = { menu = false; dialog = "password" })
                            DropdownMenuItem(text = { Text("Convert to Word (.docx)") }, onClick = { menu = false; toWord.launch(FileOps.withExt(st.name, "docx")) })
                        }
                    }
                }
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val session = st.session
                when {
                    st.error != null -> Text(st.error!!, Modifier.padding(24.dp))
                    session == null -> if (st.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> PdfPages(st, session, listState)
                }
            }

            ToolOptions(st)
            HorizontalDivider()
            ToolBar(st, onSignature = { dialog = "signature" }, onImage = { pickImage.launch("image/*") })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
        st.busy?.let { BusyOverlay(it) }
    }

    // ----- dialogs -----
    if (st.needPassword) {
        TextPromptDialog(
            title = "Password required", label = "Password", confirm = "Open", password = true,
            onDismiss = { st.needPassword = false; onClose() }
        ) { st.unlock(it) }
    }
    st.textRequest?.let { PdfTextDialog(st, it) }
    if (confirmExit) {
        UnsavedChangesDialog(
            onSave = { confirmExit = false; save(thenClose = true) },
            onDiscard = { confirmExit = false; onClose() },
            onCancel = { confirmExit = false }
        )
    }
    when (dialog) {
        "signature" -> SignatureDialog(onDismiss = { dialog = null }) { bmp ->
            dialog = null
            val s = st.session ?: return@SignatureDialog
            val page = listState.firstVisibleItemIndex.coerceIn(0, s.pageCount - 1)
            val size = s.sizes[page]
            st.addImage(bmp, page, 0.32f, size.width / size.height)
            st.message = "Drag the signature into place; use the corner handle to resize"
        }
        "pages" -> PageOrganizer(st, onDismiss = { dialog = null }) { pages ->
            extractPages = pages
            extractLauncher.launch(st.name.substringBeforeLast('.') + "_pages.pdf")
        }
        "form" -> FormFillDialog(st) { dialog = null }
        "search" -> SearchDialog(st, onDismiss = { dialog = null }) { page ->
            dialog = null
            scope.launch { listState.animateScrollToItem(page) }
        }
        "goto" -> TextPromptDialog(title = "Go to page", label = "Page (1-${st.session?.pageCount ?: 1})", confirm = "Go", keyboardType = KeyboardType.Number, onDismiss = { dialog = null }) {
            val n = it.trim().toIntOrNull()
            val count = st.session?.pageCount ?: 0
            if (n != null && n in 1..count) scope.launch { listState.scrollToItem(n - 1) } else st.message = "No such page"
            dialog = null
        }
        "password" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Password") },
            text = {
                var pw by remember { mutableStateOf("") }
                Column {
                    Text("Anyone opening the saved PDF will need this password.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(pw, { pw = it }, label = { Text("New password") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = { if (pw.length >= 1) { st.setPassword(pw); dialog = null } }) { Text("Set password") }
                        if (st.hasPassword) TextButton(onClick = { st.setPassword(null); dialog = null }) { Text("Remove") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun PdfPages(st: PdfEditorState, session: PdfRenderSession, listState: androidx.compose.foundation.lazy.LazyListState) {
    var zoom by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(if (st.night) Color(0xFF0E0F11) else Color(0xFFE6E8EC))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } >= 2) {
                            val z = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            zoom = (zoom * z).coerceIn(1f, 5f)
                            val maxX = (zoom - 1f) * size.width / 2f
                            val maxY = (zoom - 1f) * size.height / 2f
                            offX = (offX + pan.x).coerceIn(-maxX, maxX)
                            offY = (offY + pan.y).coerceIn(-maxY, maxY)
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
    ) {
        val pad = 12
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val baseWidth = (constraints.maxWidth - 2 * pad * density).toInt().coerceAtLeast(100)
        val renderWidth = (baseWidth * (if (zoom > 1.6f) 2f else 1f)).toInt()
        LazyColumn(
            state = listState,
            userScrollEnabled = st.tool !in DRAW_TOOLS,
            contentPadding = PaddingValues(pad.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom; translationX = offX; translationY = offY
            }
        ) {
            items(session.pageCount, key = { it }) { i ->
                PdfPageView(st, i, session.sizes[i], renderWidth)
            }
        }
        // page indicator
        Surface(
            color = Color(0xAA1B1D22), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
        ) {
            Text(
                "${listState.firstVisibleItemIndex + 1} / ${session.pageCount}",
                color = Color.White, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        if (zoom > 1.01f) {
            SmallFloatingActionButton(
                onClick = { zoom = 1f; offX = 0f; offY = 0f },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            ) { Icon(Icons.Filled.ZoomOutMap, "Reset zoom") }
        }
        if (st.tool == Tool.EDIT_TEXT) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)
            ) {
                Text(
                    "Tap a highlighted line to change its text",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

@Composable
private fun ToolBar(st: PdfEditorState, onSignature: () -> Unit, onImage: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        fun pick(t: Tool) { st.tool = t; st.selectedId = null }
        ToolButton(Icons.Filled.PanTool, "Select", st.tool == Tool.VIEW) { pick(Tool.VIEW) }
        ToolButton(Icons.Filled.EditNote, "Edit text", st.tool == Tool.EDIT_TEXT) { pick(Tool.EDIT_TEXT) }
        ToolButton(Icons.Filled.TextFields, "Add text", st.tool == Tool.ADD_TEXT) { pick(Tool.ADD_TEXT) }
        ToolButton(Icons.Filled.Draw, "Sign", false) { onSignature() }
        ToolButton(Icons.Filled.AddPhotoAlternate, "Image", false) { onImage() }
        ToolButton(Icons.Filled.Gesture, "Pen", st.tool == Tool.PEN) { pick(Tool.PEN) }
        ToolButton(Icons.Filled.Highlight, "Highlight", st.tool == Tool.HIGHLIGHT) {
            pick(Tool.HIGHLIGHT); if (st.color !in HIGHLIGHTS) st.color = HIGHLIGHTS[0]
        }
        ToolButton(Icons.Filled.CropSquare, "White-out", st.tool == Tool.WHITEOUT) { pick(Tool.WHITEOUT) }
        ToolButton(Icons.Filled.DeleteSweep, "Eraser", st.tool == Tool.ERASER) { pick(Tool.ERASER) }
    }
}

@Composable
private fun ToolOptions(st: PdfEditorState) {
    val sel = st.selected()
    when {
        sel != null -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Selected", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (sel is TextObj) TextButton(onClick = {
                st.textRequest = TextRequest(sel.page, sel.x, sel.y, sel.text, sel.size, sel.color, editId = sel.id)
            }) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(4.dp)); Text("Edit") }
            TextButton(onClick = { st.removeObject(sel.id) }) { Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(4.dp)); Text("Delete") }
            TextButton(onClick = { st.selectedId = null }) { Icon(Icons.Filled.Check, null); Spacer(Modifier.width(4.dp)); Text("Done") }
        }
        st.tool == Tool.PEN || st.tool == Tool.HIGHLIGHT -> Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            ColorSwatches(if (st.tool == Tool.HIGHLIGHT) HIGHLIGHTS else PALETTE, st.color, onPick = { st.color = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Thickness", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                Slider(value = st.strokeWidth, onValueChange = { st.strokeWidth = it }, valueRange = 0.0015f..0.012f, modifier = Modifier.weight(1f))
            }
        }
        st.tool == Tool.ADD_TEXT -> Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text("Tap the page where the text should go", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
            ColorSwatches(PALETTE, st.color, onPick = { st.color = it })
        }
        st.tool == Tool.WHITEOUT -> Text(
            "Drag over anything to cover it with white", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        st.tool == Tool.ERASER -> Text(
            "Drag over pen strokes, text boxes, images or white-outs you added to remove them",
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SearchDialog(st: PdfEditorState, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PdfEngine.SearchHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search text") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Find") })
                    TextButton(onClick = {
                        searching = true
                        scope.launch { results = st.search(q); searching = false }
                    }) { Text("Go") }
                }
                when {
                    searching -> CircularProgressIndicator(Modifier.padding(16.dp))
                    results?.isEmpty() == true -> Text("No matches", Modifier.padding(top = 12.dp))
                    results != null -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(results!!) { hit ->
                            Column(Modifier.fillMaxWidth().clickable { onGo(hit.page) }.padding(vertical = 8.dp)) {
                                Text("Page ${hit.page + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(hit.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
