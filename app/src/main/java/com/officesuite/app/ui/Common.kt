package com.officesuite.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.officesuite.app.core.DocKind

/** Palette used by text color, highlight and fill pickers (ARGB ints). */
val PALETTE: List<Int> = listOf(
    0xFF000000.toInt(), 0xFF444B57.toInt(), 0xFF9AA0AB.toInt(), 0xFFFFFFFF.toInt(),
    0xFFD93A2F.toInt(), 0xFFE8742A.toInt(), 0xFFF2C232.toInt(), 0xFF2E9B4F.toInt(),
    0xFF1F8A8A.toInt(), 0xFF2463D6.toInt(), 0xFF1D3A8A.toInt(), 0xFF7A3FC4.toInt(),
)
val HIGHLIGHTS: List<Int> = listOf(
    0xFFFFF176.toInt(), 0xFFA5F2A0.toInt(), 0xFF9BE7FF.toInt(), 0xFFFFC0CB.toInt(), 0xFFFFD59E.toInt(), 0xFFE0E0E0.toInt()
)

@Composable
fun KindTile(kind: DocKind, size: Dp = 40.dp) {
    val c = kindColor(kind)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(kindIcon(kind), contentDescription = kind.label, tint = c, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun TextPromptDialog(
    title: String,
    initial: String = "",
    label: String = "",
    confirm: String = "OK",
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = if (label.isNotEmpty()) ({ Text(label) }) else null,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun UnsavedChangesDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save changes?") },
        text = { Text("You have unsaved changes in this file.") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
fun BusyOverlay(message: String) {
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(message)
            }
        }
    }
}

@Composable
fun ColorSwatches(colors: List<Int>, selected: Int?, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        colors.forEach { c ->
            val sel = selected == c
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                    .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
fun ColorPickerDialog(title: String, colors: List<Int>, allowNone: Boolean, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                val rows = colors.chunked(6)
                rows.forEach { row ->
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { c ->
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                                    .border(1.dp, Color(0x33000000), CircleShape).clickable { onPick(c) }
                            )
                        }
                    }
                }
                if (allowNone) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onPick(null) }) { Text("None") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
