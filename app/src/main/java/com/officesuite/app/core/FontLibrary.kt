package com.officesuite.app.core

import android.content.Context
import android.graphics.Typeface
import java.io.InputStream

/**
 * Fonts bundled in assets/fonts. Each family is metric-compatible with a common Office/PDF font
 * (same character widths), so replacement text keeps the original look and line length.
 * All fonts are under the SIL Open Font License or Apache 2.0 (see assets/fonts/licenses).
 */
object FontLibrary {

    class Family(
        val id: String,
        val label: String,
        val file: String,
        /** lowercase, alphanumeric fragments of PDF font names that map to this family */
        val keys: List<String>,
        val serif: Boolean = false,
        val mono: Boolean = false,
    )

    val FAMILIES = listOf(
        Family("calibri", "Calibri", "Carlito", listOf("calibri", "carlito")),
        Family("cambria", "Cambria", "Caladea", listOf("cambria", "caladea"), serif = true),
        Family("arialnarrow", "Arial Narrow", "LiberationSansNarrow",
            listOf("arialnarrow", "helveticanarrow", "liberationsansnarrow", "helveticacondensed", "arialcondensed")),
        Family("arial", "Arial / Helvetica", "LiberationSans",
            listOf("arial", "helvetica", "liberationsans", "arimo", "nimbussans", "freesans", "swiss")),
        Family("times", "Times New Roman", "LiberationSerif",
            listOf("times", "liberationserif", "tinos", "nimbusroman", "freeserif", "roman")),
        Family("courier", "Courier New", "LiberationMono",
            listOf("courier", "liberationmono", "cousine", "consolas", "nimbusmono", "freemono", "mono")),
        Family("verdana", "Verdana / Tahoma", "DejaVuSans",
            listOf("verdana", "tahoma", "segoe", "dejavusans", "bitstreamvera", "lucida", "trebuchet", "roboto", "opensans", "noto sans")),
        Family("georgia", "Georgia / Book", "DejaVuSerif",
            listOf("georgia", "dejavuserif", "bookantiqua", "palatino", "garamond", "bookman", "century")),
    )

    /** Family used when nothing else can draw the characters (widest language coverage). */
    const val WIDE_COVERAGE = "verdana"

    private var appContext: Context? = null
    private val cache = HashMap<String, Typeface>()

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun byId(id: String?): Family? = FAMILIES.firstOrNull { it.id == id }

    fun label(id: String?): String = byId(id)?.label ?: "Original font"

    fun assetPath(f: Family, bold: Boolean, italic: Boolean): String {
        val style = when {
            bold && italic -> "BoldItalic"
            bold -> "Bold"
            italic -> "Italic"
            else -> "Regular"
        }
        return "fonts/${f.file}-$style.ttf"
    }

    /** Android typeface for drawing on screen (falls back to the system font). */
    fun typeface(id: String?, bold: Boolean, italic: Boolean): Typeface {
        val styleFlag = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        val f = byId(id)
        val ctx = appContext
        if (f == null || ctx == null) return Typeface.create(Typeface.DEFAULT, styleFlag)
        val path = assetPath(f, bold, italic)
        synchronized(cache) {
            cache[path]?.let { return it }
            val tf = try {
                Typeface.createFromAsset(ctx.assets, path)
            } catch (_: Exception) {
                Typeface.create(Typeface.DEFAULT, styleFlag)
            }
            cache[path] = tf
            return tf
        }
    }

    /** Raw TrueType data for embedding into a PDF. Caller closes the stream. */
    fun open(id: String?, bold: Boolean, italic: Boolean): InputStream? {
        val f = byId(id) ?: return null
        val ctx = appContext ?: return null
        return try {
            ctx.assets.open(assetPath(f, bold, italic))
        } catch (_: Exception) {
            null
        }
    }

    data class Match(val familyId: String, val bold: Boolean, val italic: Boolean, val displayName: String)

    private val BOLD_WORDS = Regex("bold|black|heavy|semibold|demibold|demi|extrabold|ultrabold|fett")
    private val ITALIC_WORDS = Regex("italic|oblique|slanted|kursiv")

    /**
     * Finds the closest bundled family for a PDF font.
     * [fontName] is the PDF BaseFont (e.g. "ABCDEF+Calibri-Bold", "TimesNewRomanPS-BoldItalicMT", "Arial,Bold").
     */
    fun match(
        fontName: String?,
        weight: Float = 0f,
        italicAngle: Float = 0f,
        serif: Boolean = false,
        fixedPitch: Boolean = false,
        forceBold: Boolean = false,
    ): Match {
        val raw = (fontName ?: "").substringAfter('+')
        val n = raw.lowercase().replace(Regex("[^a-z0-9 ]"), "")
        val compact = n.replace(" ", "")
        val family = FAMILIES.firstOrNull { f -> f.keys.any { k -> compact.contains(k.replace(" ", "")) } }
            ?: when {
                fixedPitch -> byId("courier")!!
                serif -> byId("times")!!
                else -> byId("arial")!!
            }
        val bold = BOLD_WORDS.containsMatchIn(compact) || weight >= 600f || forceBold
        val italic = ITALIC_WORDS.containsMatchIn(compact) || kotlin.math.abs(italicAngle) > 0.5f
        val display = raw
            .replace(Regex("(PS)?MT$"), "")
            .replace(Regex("[,_-]"), " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { family.label }
        return Match(family.id, bold, italic, display)
    }
}
