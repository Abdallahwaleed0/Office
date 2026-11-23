package com.officesuite.app.sheet

import org.w3c.dom.Document

enum class HAlign { GENERAL, LEFT, CENTER, RIGHT }

/** Display formatting of a cell. [baseXf] is the styles.xml cellXfs entry this style is derived from. */
data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val fontColor: Int? = null,
    val fillColor: Int? = null,
    val hAlign: HAlign = HAlign.GENERAL,
    val wrap: Boolean = false,
    val fontSize: Float = 11f,
    val numFmt: String = "General",
    val baseXf: Int = 0,
)

/**
 * A cell as the user typed it: "=SUM(A1:A3)", "12.5", "hello".
 * [text] forces the content to be treated as text even if it looks like a number or formula.
 */
data class Cell(val raw: String, val style: Int = 0, val text: Boolean = false) {
    val isFormula: Boolean get() = !text && raw.length > 1 && raw[0] == '='
}

class Sheet(var name: String) {
    var cells = HashMap<Long, Cell>()
    val colWidths = HashMap<Int, Float>()   // Excel character-width units
    val rowHeights = HashMap<Int, Float>()  // points
    var colsDirty = false
    var partPath: String? = null
    var dom: Document? = null
    var relId: String? = null

    operator fun get(r: Int, c: Int): Cell? = cells[key(r, c)]

    fun put(r: Int, c: Int, cell: Cell?) {
        val k = key(r, c)
        if (cell == null || (cell.raw.isEmpty() && cell.style == 0)) cells.remove(k) else cells[k] = cell
    }

    fun maxRow(): Int {
        var m = -1
        for (k in cells.keys) { val r = rowOf(k); if (r > m) m = r }
        return m
    }

    fun maxCol(): Int {
        var m = -1
        for (k in cells.keys) { val c = colOf(k); if (c > m) m = c }
        return m
    }

    companion object {
        const val MAX_ROWS = 1_048_576
        const val MAX_COLS = 16_384
        fun key(r: Int, c: Int): Long = (r.toLong() shl 20) or c.toLong()
        fun rowOf(k: Long): Int = (k shr 20).toInt()
        fun colOf(k: Long): Int = (k and 0xFFFFF).toInt()
    }
}

class Workbook {
    val sheets = mutableListOf<Sheet>()
    val styles = mutableListOf(CellStyle())
    var fileXfCount = 1
    var zip: LinkedHashMap<String, ByteArray>? = null
    var workbookPart = "xl/workbook.xml"

    fun styleIndex(st: CellStyle): Int {
        val i = styles.indexOf(st)
        if (i >= 0) return i
        styles.add(st)
        return styles.size - 1
    }

    fun style(i: Int): CellStyle = styles.getOrNull(i) ?: styles[0]

    fun sheet(name: String?): Sheet? = if (name == null) null else sheets.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun uniqueSheetName(base: String = "Sheet"): String {
        var n = sheets.size + 1
        while (sheet("$base$n") != null) n++
        return "$base$n"
    }
}

object Addr {
    private val CELL = Regex("^\\$?([A-Za-z]{1,3})\\$?([0-9]{1,7})$")

    fun colName(c: Int): String {
        var n = c + 1
        val sb = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            sb.append('A' + m)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    fun colIndex(letters: String): Int {
        var n = 0
        for (ch in letters.uppercase()) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    fun name(r: Int, c: Int): String = colName(c) + (r + 1)

    /** "B12" or "$B$12" -> (row=11, col=1) */
    fun parse(ref: String): Pair<Int, Int>? {
        val m = CELL.matchEntire(ref) ?: return null
        val row = m.groupValues[2].toInt() - 1
        if (row < 0) return null
        return row to colIndex(m.groupValues[1])
    }

    fun rangeName(r1: Int, c1: Int, r2: Int, c2: Int): String =
        if (r1 == r2 && c1 == c2) name(r1, c1) else name(r1, c1) + ":" + name(r2, c2)
}
