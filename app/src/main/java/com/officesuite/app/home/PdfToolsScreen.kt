@file:OptIn(ExperimentalMaterial3Api::class)

package com.officesuite.app.home

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.officesuite.app.AppNav
import com.officesuite.app.core.DocKind
import com.officesuite.app.core.FileOps
import com.officesuite.app.core.OoXml
import com.officesuite.app.pdf.PdfEngine
import com.officesuite.app.sheet.Evaluator
import com.officesuite.app.sheet.SheetPdfExporter
import com.officesuite.app.sheet.XlsxIO
import com.officesuite.app.ui.BusyOverlay
import com.officesuite.app.ui.PdfRed
import com.officesuite.app.ui.SheetGreen
import com.officesuite.app.ui.TextPromptDialog
import com.officesuite.app.ui.WordBlue
import com.officesuite.app.word.DocxIO
import com.officesuite.app.word.WordPdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun PdfToolsScreen(nav: AppNav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf<String?>(null) }
    var mergeList by remember { mutableStateOf<List<Uri>?>(null) }
    var imageList by remember { mutableStateOf<List<Uri>?>(null) }
    var fitA4 by remember { mutableStateOf(true) }
    var passwordFor by remember { mutableStateOf<Pair<String, Uri>?>(null) }
    var pending by remember { mutableStateOf<(suspend (Uri) -> Unit)?>(null) }

    fun say(m: String) { scope.launch { snackbar.showSnackbar(m) } }

    fun runPending(u: Uri?) {
        val job = pending
        pending = null
        if (u == null || job == null) return
        scope.launch {
            busy = "Working…"
            try {
                job(u)
                say("Done — saved to your chosen location")
            } catch (e: PdfEngine.WrongPasswordException) {
                say("Wrong password")
            } catch (e: Exception) {
                say("Failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { runPending(it) }
    val createDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocKind.WORD.mime)) { runPending(it) }

    suspend fun copy(u: Uri) = FileOps.copyToTemp(ctx, u, "tmp")
    fun baseName(u: Uri) = FileOps.displayName(ctx, u).substringBeforeLast('.')

    val openForEdit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) { FileOps.persist(ctx, u); nav.open(ctx, u) }
    }
    val pickPdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list: List<Uri> ->
        if (list.size >= 2) mergeList = list else if (list.isNotEmpty()) say("Pick at least two PDF files")
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { list: List<Uri> ->
        if (list.isNotEmpty()) imageList = list
    }
    val pickProtect = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) passwordFor = "protect" to u
    }
    val pickUnlock = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) passwordFor = "unlock" to u
    }
    val pickPdfToWord = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) {
            pending = { out ->
                val f = copy(u)
                val bytes = withContext(Dispatchers.IO) { PdfEngine.toDocx(f) }
                FileOps.writeBytesToUri(ctx, bytes, out)
            }
            createDocx.launch(baseName(u) + ".docx")
        }
    }
    val pickWordToPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) {
            pending = { out ->
                val res = ctx.resources
                val pdf = withContext(Dispatchers.Default) {
                    val zip = ctx.contentResolver.openInputStream(u)?.use { OoXml.readZip(it) } ?: throw IOException("Cannot open the file")
                    val (_, text) = DocxIO.read(zip, res, res.displayMetrics.widthPixels)
                    FileOps.tempFile(ctx, "pdf").also { WordPdfExporter.export(res, text, it) }
                }
                FileOps.writeFileToUri(ctx, pdf, out)
            }
            createPdf.launch(baseName(u) + ".pdf")
        }
    }
    val pickExcelToPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) {
            pending = { out ->
                val pdf = withContext(Dispatchers.Default) {
                    val zip = ctx.contentResolver.openInputStream(u)?.use { OoXml.readZip(it) } ?: throw IOException("Cannot open the file")
                    val wb = XlsxIO.read(zip)
                    val ev = Evaluator(wb).snapshot().computeAll()
                    FileOps.tempFile(ctx, "pdf").also { SheetPdfExporter.export(wb, wb.sheets[0], ev, it) }
                }
                FileOps.writeFileToUri(ctx, pdf, out)
            }
            createPdf.launch(baseName(u) + ".pdf")
        }
    }

    BackHandler { nav.pop() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("PDF tools") },
                    navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item { ToolRow(Icons.Filled.Edit, PdfRed, "Edit & sign a PDF", "Edit text, add text, images, signatures, highlight, fill forms") { openForEdit.launch(arrayOf("application/pdf")) } }
                item { ToolRow(Icons.Filled.CallMerge, PdfRed, "Merge PDFs", "Combine several PDF files into one") { pickPdfs.launch(arrayOf("application/pdf")) } }
                item { ToolRow(Icons.Filled.Collections, PdfRed, "Images to PDF", "Turn photos or scans into a PDF") { pickImages.launch("image/*") } }
                item { ToolRow(Icons.Filled.Lock, PdfRed, "Protect PDF", "Add a password") { pickProtect.launch(arrayOf("application/pdf")) } }
                item { ToolRow(Icons.Filled.LockOpen, PdfRed, "Unlock PDF", "Remove a password you know") { pickUnlock.launch(arrayOf("application/pdf")) } }
                item { ToolRow(Icons.Filled.Description, WordBlue, "PDF to Word", "Extract the text into an editable .docx") { pickPdfToWord.launch(arrayOf("application/pdf")) } }
                item { ToolRow(Icons.Filled.PictureAsPdf, WordBlue, "Word to PDF", "Convert a .docx document") { pickWordToPdf.launch(arrayOf(DocKind.WORD.mime)) } }
                item { ToolRow(Icons.Filled.GridOn, SheetGreen, "Excel to PDF", "Convert the first sheet of an .xlsx workbook") { pickExcelToPdf.launch(arrayOf(DocKind.SHEET.mime)) } }
            }
        }
        busy?.let { BusyOverlay(it) }
    }

    mergeList?.let { list ->
        AlertDialog(
            onDismissRequest = { mergeList = null },
            title = { Text("Merge ${list.size} PDFs") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(list) { i, u ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. ${FileOps.displayName(ctx, u)}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { if (i > 0) mergeList = list.toMutableList().apply { add(i - 1, removeAt(i)) } }) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                            IconButton(onClick = { if (i < list.size - 1) mergeList = list.toMutableList().apply { add(i + 1, removeAt(i)) } }) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                            IconButton(onClick = { mergeList = list.filterIndexed { j, _ -> j != i }.takeIf { it.size >= 2 } }) { Icon(Icons.Filled.Close, "Remove") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val inputs = list
                    mergeList = null
                    pending = { out ->
                        val files = inputs.map { copy(it) }
                        val f = FileOps.tempFile(ctx, "pdf")
                        withContext(Dispatchers.IO) { PdfEngine.merge(files, f) }
                        FileOps.writeFileToUri(ctx, f, out)
                    }
                    createPdf.launch("Merged.pdf")
                }) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { mergeList = null }) { Text("Cancel") } }
        )
    }

    imageList?.let { list ->
        AlertDialog(
            onDismissRequest = { imageList = null },
            title = { Text("Images to PDF") },
            text = {
                Column {
                    Text("${list.size} image(s), one per page, in the order you picked them.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(fitA4, { fitA4 = it })
                        Text("Fit on A4 pages (otherwise page = image size)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val inputs = list
                    val a4 = fitA4
                    imageList = null
                    pending = { out ->
                        val f = FileOps.tempFile(ctx, "pdf")
                        withContext(Dispatchers.IO) { PdfEngine.imagesToPdf(ctx, inputs, f, a4) }
                        FileOps.writeFileToUri(ctx, f, out)
                    }
                    createPdf.launch("Images.pdf")
                }) { Text("Create PDF") }
            },
            dismissButton = { TextButton(onClick = { imageList = null }) { Text("Cancel") } }
        )
    }

    passwordFor?.let { (mode, u) ->
        TextPromptDialog(
            title = if (mode == "protect") "Set a password" else "Enter the current password",
            label = "Password", password = true, confirm = "Continue",
            onDismiss = { passwordFor = null }
        ) { pw ->
            passwordFor = null
            if (pw.isEmpty()) { say("Password can't be empty"); return@TextPromptDialog }
            pending = { out ->
                val src = copy(u)
                val f = FileOps.tempFile(ctx, "pdf")
                withContext(Dispatchers.IO) {
                    if (mode == "protect") PdfEngine.protect(src, f, pw) else PdfEngine.decrypt(src, pw, f)
                }
                FileOps.writeFileToUri(ctx, f, out)
            }
            createPdf.launch(baseName(u) + if (mode == "protect") "_protected.pdf" else "_unlocked.pdf")
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, color: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = color) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
