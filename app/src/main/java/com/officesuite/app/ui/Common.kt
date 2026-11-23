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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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

/** 8 grays + 14 hues x 5 tones. */
val FULL_PALETTE: List<Int> by lazy {
    val out = ArrayList<Int>()
    listOf(0xFF000000, 0xFF333333, 0xFF555555, 0xFF777777, 0xFF999999, 0xFFBBBBBB, 0xFFDDDDDD, 0xFFFFFFFF)
        .forEach { out.add(it.toInt()) }
    val hues = listOf(0f, 15f, 30f, 45f, 60f, 90f, 120f, 160f, 190f, 210f, 230f, 260f, 290f, 320f)
    val tones = listOf(0.25f to 1f, 0.55f to 1f, 1f to 0.95f, 1f to 0.7f, 1f to 0.45f)
    for ((sat, v) in tones) for (h in hues) out.add(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v)))
    out
}

fun hexOf(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

private fun parseHex(s: String): Int? {
    val h = s.trim().removePrefix("#")
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { (it or 0xFF000000).toInt() }
}

@Composable
private fun Swatch(c: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(3.dp).size(32.dp).clip(CircleShape).background(Color(c))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
            .clickable(onClick = onClick)
    )
}

/**
 * Color picker with presets, a full palette and a custom HSV / hex color.
 * [original] adds a "Use original color" button (e.g. the color of the PDF text being edited).
 */
@Composable
fun ColorPickerDialog(
    title: String,
    colors: List<Int>,
    allowNone: Boolean,
    onDismiss: () -> Unit,
    initial: Int? = null,
    original: Int? = null,
    onPick: (Int?) -> Unit
) {
    val start = initial ?: original ?: colors.firstOrNull() ?: 0xFF000000.toInt()
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start, it) } }
    var hue by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }
    var hex by remember { mutableStateOf(hexOf(start)) }
    val custom = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    fun setFrom(c: Int) {
        val t = FloatArray(3)
        android.graphics.Color.colorToHSV(c, t)
        hue = t[0]; sat = t[1]; value = t[2]; hex = hexOf(c)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (original != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(original) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Swatch(original, false) { onPick(original) }
                        Spacer(Modifier.width(8.dp))
                        Text("Use original color  ${hexOf(original)}")
                    }
                }
                (colors + FULL_PALETTE).distinct().chunked(7).forEach { row ->
                    Row { row.forEach { c -> Swatch(c, c == initial) { onPick(c) } } }
                }
                Spacer(Modifier.height(12.dp))
                Text("Custom color", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(custom))
                            .border(1.dp, Color(0x33000000), RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { v -> hex = v; parseHex(v)?.let { setFrom(it); hex = v } },
                        singleLine = true,
                        label = { Text("Hex") },
                        modifier = Modifier.width(150.dp)
                    )
                }
                Text("Hue", style = MaterialTheme.typography.labelSmall)
                Slider(value = hue, onValueChange = { hue = it; hex = hexOf(android.graphics.Color.HSVToColor(floatArrayOf(it, sat, value))) }, valueRange = 0f..360f)
                Text("Saturation", style = MaterialTheme.typography.labelSmall)
                Slider(value = sat, onValueChange = { sat = it; hex = hexOf(android.graphics.Color.HSVToColor(floatArrayOf(hue, it, value))) }, valueRange = 0f..1f)
                Text("Brightness", style = MaterialTheme.typography.labelSmall)
                Slider(value = value, onValueChange = { value = it; hex = hexOf(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, it))) }, valueRange = 0f..1f)
                Row {
                    TextButton(onClick = { onPick(custom) }) { Text("Use custom color") }
                    if (allowNone) TextButton(onClick = { onPick(null) }) { Text("None") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
