@file:OptIn(ExperimentalMaterial3Api::class)

package com.officesuite.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.officesuite.app.ui.ColorSwatches
import com.officesuite.app.ui.PALETTE
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/* ============================ Signatures ============================ */

object SignatureStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "signatures").apply { mkdirs() }
    fun list(ctx: Context): List<File> = dir(ctx).listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun save(ctx: Context, bmp: Bitmap) {
        FileOutputStream(File(dir(ctx), "sig_${System.currentTimeMillis()}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private fun renderSignature(strokes: List<List<Offset>>, color: Int, strokePx: Float): Bitmap? {
    val pts = strokes.flatten()
    if (pts.isEmpty()) return null
    val pad = strokePx * 2
    val l = pts.minOf { it.x } - pad
    val t = pts.minOf { it.y } - pad
    val r = pts.maxOf { it.x } + pad
    val b = pts.maxOf { it.y } + pad
    val scale = 2f
    val w = ((r - l) * scale).roundToInt().coerceAtLeast(4)
    val h = ((b - t) * scale).roundToInt().coerceAtLeast(4)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = strokePx * scale
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    for (s in strokes) {
        val path = android.graphics.Path()
        s.forEachIndexed { i, o ->
            val x = (o.x - l) * scale
            val y = (o.y - t) * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (s.size == 1) path.lineTo((s[0].x - l) * scale + 0.5f, (s[0].y - t) * scale)
        c.drawPath(path, p)
    }
    return bmp
}

@Composable
fun SignatureDialog(onDismiss: () -> Unit, onPick: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    var saved by remember { mutableStateOf(SignatureStore.list(ctx)) }
    var drawing by remember { mutableStateOf(saved.isEmpty()) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var ink by remember { mutableStateOf(0xFF1D3A8A.toInt()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp)) {
                Text(if (drawing) "Draw your signature" else "Choose a signature", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                if (!drawing) {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(saved, key = { it.name }) { f ->
                            val bmp = remember(f) { BitmapFactory.decodeFile(f.absolutePath) }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                Box(
                                    Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFF5F6F8)).clickable { bmp?.let(onPick) }.padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bmp != null) Image(bmp.asImageBitmap(), "Signature", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                }
                                IconButton(onClick = { f.delete(); saved = SignatureStore.list(ctx); if (saved.isEmpty()) drawing = true }) {
                                    Icon(Icons.Filled.Delete, "Delete signature")
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(onClick = { drawing = true }) { Text("New signature") }
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF5F6F8))
                            .pointerInputDraw(
                                onStart = { current = listOf(it) },
                                onMove = { current = current + it },
                                onEnd = { if (current.isNotEmpty()) strokes.add(current); current = emptyList() }
                            )
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val base = size.height * 0.75f
                            drawLine(Color(0xFFCFD3DA), Offset(16f, base), Offset(size.width - 16f, base), strokeWidth = 1.5f)
                            val all = strokes + listOf(current)
                            for (s in all) {
                                if (s.isEmpty()) continue
                                val path = Path()
                                path.moveTo(s[0].x, s[0].y)
                                if (s.size == 1) path.lineTo(s[0].x + 0.5f, s[0].y)
                                for (i in 1 until s.size) path.lineTo(s[i].x, s[i].y)
                                drawPath(path, Color(ink), style = Stroke(width = 3f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                        if (strokes.isEmpty() && current.isEmpty()) {
                            Text("Sign here", Modifier.align(Alignment.Center), color = Color(0xFF9AA0AB))
                        }
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(0xFF000000.toInt(), 0xFF1D3A8A.toInt(), 0xFFB3261E.toInt()).forEach { c ->
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                    .border(if (ink == c) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    .clickable { ink = c }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { if (saved.isEmpty()) onDismiss() else drawing = false }) { Text("Cancel") }
                        Button(onClick = {
                            val bmp = renderSignature(strokes.toList(), ink, 3f * density)
                            if (bmp != null) {
                                SignatureStore.save(ctx, bmp)
                                onPick(bmp)
                            }
                        }, enabled = strokes.isNotEmpty()) { Text("Use signature") }
                    }
                }
            }
        }
    }
}

/** Simple drag capture for the signature pad. */
private fun Modifier.pointerInputDraw(onStart: (Offset) -> Unit, onMove: (Offset) -> Unit, onEnd: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { onStart(it) },
            onDragEnd = { onEnd() },
            onDragCancel = { onEnd() }
        ) { change, _ ->
            change.consume()
            onMove(change.position)
        }
    }

/* ============================ Text dialog ============================ */

@Composable
fun PdfTextDialog(st: PdfEditorState, req: TextRequest) {
    var text by remember(req) { mutableStateOf(req.initial) }
    var sizePt by remember(req) { mutableStateOf((req.size * 595f).coerceIn(6f, 60f)) }
    var color by remember(req) { mutableStateOf(req.color) }
    val line = req.line
    AlertDialog(
        onDismissRequest = { st.textRequest = null },
        title = { Text(when { line != null -> "Edit text"; req.editId != null -> "Edit text box"; else -> "Add text" }) },
        text = {
            Column {
                if (line != null) {
                    Text(
                        if (line.editable) "Changes the text inside the PDF, keeping its position, size and color."
                        else "This line can't be rewritten in place; it will be covered and replaced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = line != null,
                    minLines = if (line != null) 1 else 2
                )
                if (line == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Size ${sizePt.roundToInt()} pt", style = MaterialTheme.typography.labelMedium)
                    Slider(value = sizePt, onValueChange = { sizePt = it }, valueRange = 6f..60f)
                    ColorSwatches(PALETTE, color, onPick = { color = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { st.commitText(req, text, sizePt / 595f, color) }) { Text("OK") } },
        dismissButton = {
            Row {
                if (line != null || req.editId != null) {
                    TextButton(onClick = { st.commitText(req, "", req.size, color) }) { Text("Delete") }
                }
                TextButton(onClick = { st.textRequest = null }) { Text("Cancel") }
            }
        }
    )
}

/* ============================ Page organizer ============================ */

@Composable
private fun PageThumb(st: PdfEditorState, index: Int, selected: Boolean, onClick: () -> Unit) {
    val session = st.session ?: return
    val version = st.version
    val bmp by produceState<Bitmap?>(null, session, index, version) { value = session.render(index, 260) }
    val size = session.sizes.getOrNull(index)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(size?.let { it.width / it.height } ?: 0.707f)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000))
                .background(Color.White)
        ) {
            bmp?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun OrgAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(78.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        Icon(icon, label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun PageOrganizer(st: PdfEditorState, onDismiss: () -> Unit, onExtract: (List<Int>) -> Unit) {
    val session = st.session ?: return
    val sel = remember { mutableStateListOf<Int>() }
    val one = sel.singleOrNull()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.navigationBarsPadding()) {
                TopAppBar(
                    title = { Text(if (sel.isEmpty()) "Pages (${session.pageCount})" else "${sel.size} selected") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") } },
                    actions = {
                        TextButton(onClick = {
                            if (sel.size == session.pageCount) sel.clear() else { sel.clear(); sel.addAll(0 until session.pageCount) }
                        }) { Text(if (sel.size == session.pageCount) "None" else "All") }
                    }
                )
                Text(
                    "Tap pages to select them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                LazyVerticalGrid(columns = GridCells.Adaptive(108.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(10.dp)) {
                    items(session.pageCount) { i ->
                        PageThumb(st, i, i in sel) { if (i in sel) sel.remove(i) else sel.add(i) }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)) {
                    val any = sel.isNotEmpty()
                    OrgAction(Icons.Filled.RotateLeft, "Rotate left", any) {
                        val pages = sel.toList(); st.fileOp("Rotating…") { s, o -> PdfEngine.rotate(s, o, pages, -90) }
                    }
                    OrgAction(Icons.Filled.RotateRight, "Rotate right", any) {
                        val pages = sel.toList(); st.fileOp("Rotating…") { s, o -> PdfEngine.rotate(s, o, pages, 90) }
                    }
                    OrgAction(Icons.Filled.KeyboardArrowLeft, "Move earlier", one != null && one > 0) {
                        val i = one!!; st.fileOp("Moving…") { s, o -> PdfEngine.move(s, o, i, i - 1) }; sel.clear(); sel.add(i - 1)
                    }
                    OrgAction(Icons.Filled.KeyboardArrowRight, "Move later", one != null && one < session.pageCount - 1) {
                        val i = one!!; st.fileOp("Moving…") { s, o -> PdfEngine.move(s, o, i, i + 1) }; sel.clear(); sel.add(i + 1)
                    }
                    OrgAction(Icons.Filled.NoteAdd, "Blank page after", one != null) {
                        val i = one!!; st.fileOp("Inserting…") { s, o -> PdfEngine.insertBlank(s, o, i) }; sel.clear()
                    }
                    OrgAction(Icons.Filled.ContentCopy, "Duplicate", one != null) {
                        val i = one!!; st.fileOp("Duplicating…") { s, o -> PdfEngine.duplicate(s, o, i) }; sel.clear()
                    }
                    OrgAction(Icons.Filled.ContentCut, "Extract to new PDF", any) { onExtract(sel.sorted()) }
                    OrgAction(Icons.Filled.Delete, "Delete", any && sel.size < session.pageCount) {
                        val pages = sel.toList(); st.fileOp("Deleting…") { s, o -> PdfEngine.delete(s, o, pages) }; sel.clear()
                    }
                }
            }
        }
    }
}

/* ============================ Forms ============================ */

@Composable
fun FormFillDialog(st: PdfEditorState, onDismiss: () -> Unit) {
    var fields by remember { mutableStateOf<List<PdfEngine.FormField>?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) {
        val f = st.formFields()
        f.forEach { values[it.name] = it.value }
        fields = f
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fill form") },
        text = {
            val list = fields
            when {
                list == null -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text("This PDF has no fillable form fields. Use Add text and Signature to fill it in instead.")
                else -> LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(list, key = { it.name }) { f ->
                        when (f.kind) {
                            PdfEngine.FieldKind.CHECK -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(values[f.name] == "true", { values[f.name] = if (it) "true" else "false" })
                                Text(f.name)
                            }
                            else -> OutlinedTextField(
                                value = values[f.name] ?: "",
                                onValueChange = { values[f.name] = it },
                                label = { Text(f.name) },
                                supportingText = if (f.options.isNotEmpty()) ({ Text("Options: " + f.options.joinToString(", ")) }) else null,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !fields.isNullOrEmpty(), onClick = {
                val changed = fields.orEmpty().filter { values[it.name] != it.value }.associate { it.name to (values[it.name] ?: "") }
                if (changed.isNotEmpty()) st.fillForm(changed)
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
