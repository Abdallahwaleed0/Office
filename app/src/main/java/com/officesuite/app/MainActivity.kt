package com.officesuite.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import com.officesuite.app.core.DocKind
import com.officesuite.app.core.FileOps
import com.officesuite.app.core.FontLibrary
import com.officesuite.app.home.HomeScreen
import com.officesuite.app.home.PdfToolsScreen
import com.officesuite.app.pdf.PdfEditorScreen
import com.officesuite.app.sheet.SheetEditorScreen
import com.officesuite.app.ui.OfficeTheme
import com.officesuite.app.word.WordEditorScreen
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class OfficeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        FontLibrary.init(applicationContext)
    }
}

private var keySeq = 0L
private fun nextKey() = ++keySeq

sealed class Screen {
    abstract val key: Long

    data class Home(override val key: Long = 0L) : Screen()
    data class PdfTools(override val key: Long = nextKey()) : Screen()
    data class Pdf(val uri: Uri, override val key: Long = nextKey()) : Screen()
    data class Word(val uri: Uri?, val kind: DocKind, override val key: Long = nextKey()) : Screen()
    data class Sheet(val uri: Uri?, val kind: DocKind, override val key: Long = nextKey()) : Screen()
}

class AppNav {
    val stack = mutableStateListOf<Screen>(Screen.Home())

    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    /** Opens a document in the matching editor. */
    fun open(ctx: Context, uri: Uri) {
        val name = FileOps.displayName(ctx, uri)
        if (DocKind.isLegacyBinary(name)) {
            Toast.makeText(
                ctx,
                "Old binary formats (.doc, .xls, .ppt) are not supported yet. Please save the file as .docx or .xlsx.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        when (DocKind.detect(name, FileOps.mime(ctx, uri))) {
            DocKind.PDF -> push(Screen.Pdf(uri))
            DocKind.WORD -> push(Screen.Word(uri, DocKind.WORD))
            DocKind.TEXT -> push(Screen.Word(uri, DocKind.TEXT))
            DocKind.SHEET -> push(Screen.Sheet(uri, DocKind.SHEET))
            DocKind.CSV -> push(Screen.Sheet(uri, DocKind.CSV))
            null -> Toast.makeText(ctx, "This file type is not supported: $name", Toast.LENGTH_LONG).show()
        }
    }
}

class MainActivity : ComponentActivity() {
    private val nav = AppNav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            OfficeTheme { AppRoot(nav) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) nav.open(this, data)
    }
}

@Composable
fun AppRoot(nav: AppNav) {
    val screen = nav.stack.last()
    key(screen.key) {
        when (screen) {
            is Screen.Home -> HomeScreen(nav)
            is Screen.PdfTools -> PdfToolsScreen(nav)
            is Screen.Pdf -> PdfEditorScreen(screen.uri, onClose = { nav.pop() })
            is Screen.Word -> WordEditorScreen(screen.uri, screen.kind, onClose = { nav.pop() })
            is Screen.Sheet -> SheetEditorScreen(screen.uri, screen.kind, onClose = { nav.pop() })
        }
    }
}
