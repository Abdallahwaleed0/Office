@file:OptIn(ExperimentalMaterial3Api::class)

package com.officesuite.app.home

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.officesuite.app.AppNav
import com.officesuite.app.Screen
import com.officesuite.app.core.DocKind
import com.officesuite.app.core.FileOps
import com.officesuite.app.core.RecentFile
import com.officesuite.app.core.Recents
import com.officesuite.app.ui.KindTile
import com.officesuite.app.ui.PdfRed
import com.officesuite.app.ui.SheetGreen
import com.officesuite.app.ui.WordBlue

private enum class Filter(val label: String, val kinds: Set<DocKind>) {
    ALL("All", DocKind.entries.toSet()),
    DOCS("Docs", setOf(DocKind.WORD, DocKind.TEXT)),
    SHEETS("Sheets", setOf(DocKind.SHEET, DocKind.CSV)),
    PDF("PDFs", setOf(DocKind.PDF))
}

@Composable
fun HomeScreen(nav: AppNav) {
    val ctx = LocalContext.current
    var recents by remember { mutableStateOf(Recents.load(ctx)) }
    var filter by remember { mutableStateOf(Filter.ALL) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            FileOps.persist(ctx, uri)
            nav.open(ctx, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Office Suite", fontWeight = FontWeight.SemiBold) })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openLauncher.launch(DocKind.OPEN_MIMES) },
                icon = { Icon(Icons.Filled.FolderOpen, null) },
                text = { Text("Open file") }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Text(
                    "Create",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CreateCard("Document", Icons.Filled.NoteAdd, WordBlue, Modifier.weight(1f)) {
                        nav.push(Screen.Word(null, DocKind.WORD))
                    }
                    CreateCard("Spreadsheet", Icons.Filled.GridOn, SheetGreen, Modifier.weight(1f)) {
                        nav.push(Screen.Sheet(null, DocKind.SHEET))
                    }
                    CreateCard("PDF tools", Icons.Filled.PictureAsPdf, PdfRed, Modifier.weight(1f)) {
                        nav.push(Screen.PdfTools())
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Filter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
            }
            val shown = recents.filter { it.kind in filter.kinds }
            if (shown.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No recent files", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Open a PDF, Word or Excel file, or create a new one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(shown, key = { it.uri }) { rf ->
                RecentRow(
                    rf,
                    onOpen = { nav.open(ctx, Uri.parse(rf.uri)) },
                    onRemove = {
                        Recents.remove(ctx, rf.uri)
                        recents = Recents.load(ctx)
                    }
                )
            }
        }
    }
}

@Composable
private fun CreateCard(label: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(color),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = Color.White) }
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun RecentRow(rf: RecentFile, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KindTile(rf.kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(rf.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${rf.kind.label} · ${DateUtils.getRelativeTimeSpanString(rf.time)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Remove from recent") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menu = false; onRemove() }
                )
            }
        }
    }
}
