package com.officesuite.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.officesuite.app.core.DocKind

val Brand = Color(0xFF2A56C6)
val PdfRed = Color(0xFFD93A2F)
val WordBlue = Color(0xFF2463D6)
val SheetGreen = Color(0xFF188A4F)
val TextGray = Color(0xFF5D6371)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF0E2A78),
    secondary = Color(0xFF52607A),
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7F9),
    surfaceVariant = Color(0xFFECEEF2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC4FF),
    onPrimary = Color(0xFF0E2A78),
    primaryContainer = Color(0xFF233F94),
    onPrimaryContainer = Color(0xFFDDE6FF),
    surface = Color(0xFF16181C),
    background = Color(0xFF111316),
    surfaceVariant = Color(0xFF2A2D33),
)

@Composable
fun OfficeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

fun kindColor(kind: DocKind): Color = when (kind) {
    DocKind.PDF -> PdfRed
    DocKind.WORD, DocKind.TEXT -> WordBlue
    DocKind.SHEET, DocKind.CSV -> SheetGreen
}

fun kindIcon(kind: DocKind): ImageVector = when (kind) {
    DocKind.PDF -> Icons.Filled.PictureAsPdf
    DocKind.WORD -> Icons.Filled.Description
    DocKind.SHEET, DocKind.CSV -> Icons.Filled.GridOn
    DocKind.TEXT -> Icons.Filled.InsertDriveFile
}
