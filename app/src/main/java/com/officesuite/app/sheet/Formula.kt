package com.officesuite.app.sheet

/* ===================== Tokenizer ===================== */

enum class TT { NUM, STR, BOOL, REF, COL, NAME, FUNC, OP, LP, RP, SEP, COLON, ERR }

/**
 * [start]/[end] delimit the token text in the formula (for REF/COL: the reference itself, without the sheet prefix).
 * [sheetStart] is where a sheet prefix ("Sheet1!" or "'My sheet'!") begins, or == start when there is none.
 */
class Tok(val t: TT, val s: String, val start: Int, val end: Int, val sheet: String? = null, val sheetStart: Int = start)

class FormulaError(val code: String) : Exception(code)

val ERROR_CODES = listOf("#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#N/A", "#NUM!", "#NULL!", "#GETTING_DATA")
val STANDARD_ERRORS = setOf("#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#N/A", "#NUM!", "#NULL!")

object Lexer {
    private val CELL = Regex("^\\$?[A-Za-z]{1,3}\\$?[0-9]{1,7}$")
    private val COLUMN = Regex("^\\$?[A-Za-z]{1,3}$")

    private fun identChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '.'

    private fun refTok(text: String, start: Int, end: Int, sheet: String?, sheetStart: Int): Tok = when {
        CELL.matches(text) && Addr.parse(text) != null -> Tok(TT.REF, text, start, end, sheet, sheetStart)
        COLUMN.matches(text) -> Tok(TT.COL, text, start, end, sheet, sheetStart)
        sheet == null && (text.equals("TRUE", true) || text.equals("FALSE", true)) -> Tok(TT.BOOL, text.uppercase(), start, end)
        else -> Tok(TT.NAME, text, start, end, sheet, sheetStart)
    }

    fun tokenize(f: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = f.length
        while (i < n) {
            val ch = f[i]
            when {
                ch.isWhitespace() -> i++
                ch.isDigit() || (ch == '.' && i + 1 < n && f[i + 1].isDigit()) -> {
                    val st = i
                    while (i < n && (f[i].isDigit() || f[i] == '.')) i++
                    if (i < n && (f[i] == 'e' || f[i] == 'E') && i + 1 < n &&
                        (f[i + 1].isDigit() || ((f[i + 1] == '+' || f[i + 1] == '-') && i + 2 < n && f[i + 2].isDigit()))
                    ) {
                        i += 2
                        while (i < n && f[i].isDigit()) i++
                    }
                    out.add(Tok(TT.NUM, f.substring(st, i), st, i))
                }
                ch == '"' -> {
                    val st = i
                    val sb = StringBuilder()
                    i++
                    while (i < n) {
                        if (f[i] == '"') {
                            if (i + 1 < n && f[i + 1] == '"') { sb.append('"'); i += 2 } else { i++; break }
                        } else { sb.append(f[i]); i++ }
                    }
                    out.add(Tok(TT.STR, sb.toString(), st, i))
                }
                ch == '#' -> {
                    val st = i
                    val code = ERROR_CODES.firstOrNull { f.startsWith(it, i, ignoreCase = true) }
                    if (code != null) { i += code.length; out.add(Tok(TT.ERR, code, st, i)) } else { i++; out.add(Tok(TT.ERR, "#NAME?", st, i)) }
                }
                ch == '\'' -> {
                    val st = i
                    val sb = StringBuilder()
                    i++
                    while (i < n) {
                        if (f[i] == '\'') {
                            if (i + 1 < n && f[i + 1] == '\'') { sb.append('\''); i += 2 } else { i++; break }
                        } else { sb.append(f[i]); i++ }
                    }
                    if (i < n && f[i] == '!') {
                        i++
                        val rs = i
                        while (i < n && identChar(f[i])) i++
                        out.add(refTok(f.substring(rs, i), rs, i, sb.toString(), st))
                    } else {
                        out.add(Tok(TT.NAME, sb.toString(), st, i))
                    }
                }
                ch.isLetter() || ch == '_' || ch == '$' || ch == '\\' -> {
                    val st = i
                    i++
                    while (i < n && identChar(f[i])) i++
                    val word = f.substring(st, i)
                    if (i < n && f[i] == '!') {
                        i++
                        val rs = i
                        while (i < n && identChar(f[i])) i++
                        out.add(refTok(f.substring(rs, i), rs, i, word, st))
                    } else {
                        var j = i
                        while (j < n && f[j] == ' ') j++
                        if (j < n && f[j] == '(') {
                            val fn = word.uppercase().removePrefix("_XLFN.").removePrefix("_XLWS.")
                            out.add(Tok(TT.FUNC, fn, st, i))
                        } else {
                            out.add(refTok(word, st, i, null, st))
                        }
                    }
                }
                ch == '(' -> { out.add(Tok(TT.LP, "(", i, i + 1)); i++ }
                ch == ')' -> { out.add(Tok(TT.RP, ")", i, i + 1)); i++ }
                ch == ',' || ch == ';' -> { out.add(Tok(TT.SEP, ",", i, i + 1)); i++ }
                ch == ':' -> { out.add(Tok(TT.COLON, ":", i, i + 1)); i++ }
                ch == '<' -> {
                    if (i + 1 < n && (f[i + 1] == '=' || f[i + 1] == '>')) { out.add(Tok(TT.OP, f.substring(i, i + 2), i, i + 2)); i += 2 } else { out.add(Tok(TT.OP, "<", i, i + 1)); i++ }
                }
                ch == '>' -> {
                    if (i + 1 < n && f[i + 1] == '=') { out.add(Tok(TT.OP, ">=", i, i + 2)); i += 2 } else { out.add(Tok(TT.OP, ">", i, i + 1)); i++ }
                }
                ch in "+-*/^&=%" -> { out.add(Tok(TT.OP, ch.toString(), i, i + 1)); i++ }
                else -> throw FormulaError("#VALUE!")
            }
        }
        return out
    }
}

/* ===================== AST + Parser ===================== */

sealed class Nd
class NLit(val v: V) : Nd()
class NRef(val sheet: String?, val r: Int, val c: Int) : Nd()
class NArea(val sheet: String?, val r1: Int, val c1: Int, val r2: Int, val c2: Int) : Nd()
class NUn(val op: String, val x: Nd) : Nd()
class NBin(val op: String, val a: Nd, val b: Nd) : Nd()
class NCall(val name: String, val args: List<Nd>) : Nd()
object NMissing : Nd()

class Parser(private val toks: List<Tok>) {
    private var p = 0
    private val cmp = setOf("=", "<>", "<", ">", "<=", ">=")

    fun parse(): Nd {
        if (toks.isEmpty()) throw FormulaError("#VALUE!")
        val n = comparison()
        if (p < toks.size) throw FormulaError("#VALUE!")
        return n
    }

    private fun peek(): Tok? = toks.getOrNull(p)
    private fun next(): Tok = toks.getOrNull(p++) ?: throw FormulaError("#VALUE!")
    private fun isOp(s: String): Boolean { val t = peek(); return t != null && t.t == TT.OP && t.s == s }
    private fun expect(t: TT) { if (next().t != t) throw FormulaError("#VALUE!") }

    private fun comparison(): Nd {
        var a = concat()
        while (true) {
            val t = peek()
            if (t != null && t.t == TT.OP && t.s in cmp) { p++; a = NBin(t.s, a, concat()) } else break
        }
        return a
    }

    private fun concat(): Nd {
        var a = additive()
        while (isOp("&")) { p++; a = NBin("&", a, additive()) }
        return a
    }

    private fun additive(): Nd {
        var a = term()
        while (isOp("+") || isOp("-")) { val op = next().s; a = NBin(op, a, term()) }
        return a
    }

    private fun term(): Nd {
        var a = power()
        while (isOp("*") || isOp("/")) { val op = next().s; a = NBin(op, a, power()) }
        return a
    }

    private fun power(): Nd {
        var a = unary()
        while (isOp("^")) { p++; a = NBin("^", a, unary()) }
        return a
    }

    private fun unary(): Nd {
        if (isOp("-")) { p++; return NUn("-", unary()) }
        if (isOp("+")) { p++; return unary() }
        return postfix()
    }

    private fun postfix(): Nd {
        var a = primary()
        while (isOp("%")) { p++; a = NUn("%", a) }
        return a
    }

    private fun primary(): Nd {
        val t = next()
        return when (t.t) {
            TT.NUM -> NLit(V.Num(t.s.toDoubleOrNull() ?: throw FormulaError("#VALUE!")))
            TT.STR -> NLit(V.Str(t.s))
            TT.BOOL -> NLit(V.Bool(t.s == "TRUE"))
            TT.ERR -> NLit(V.Err(t.s))
            TT.LP -> { val e = comparison(); expect(TT.RP); e }
            TT.FUNC -> {
                expect(TT.LP)
                val args = ArrayList<Nd>()
                if (peek()?.t == TT.RP) {
                    p++
                } else {
                    while (true) {
                        val pk = peek()?.t
                        args.add(if (pk == TT.SEP || pk == TT.RP) NMissing else comparison())
                        val k = next()
                        if (k.t == TT.RP) break
                        if (k.t != TT.SEP) throw FormulaError("#VALUE!")
                    }
                }
                NCall(t.s, args)
            }
            TT.REF -> {
                val (r, c) = Addr.parse(t.s) ?: throw FormulaError("#REF!")
                if (peek()?.t == TT.COLON) {
                    p++
                    val t2 = next()
                    if (t2.t != TT.REF) throw FormulaError("#REF!")
                    val (r2, c2) = Addr.parse(t2.s) ?: throw FormulaError("#REF!")
                    NArea(t.sheet, minOf(r, r2), minOf(c, c2), maxOf(r, r2), maxOf(c, c2))
                } else NRef(t.sheet, r, c)
            }
            TT.COL -> {
                if (peek()?.t == TT.COLON) {
                    p++
                    val t2 = next()
                    if (t2.t != TT.COL) throw FormulaError("#NAME?")
                    val a = Addr.colIndex(t.s.replace("$", ""))
                    val b = Addr.colIndex(t2.s.replace("$", ""))
                    NArea(t.sheet, 0, minOf(a, b), Sheet.MAX_ROWS - 1, maxOf(a, b))
                } else NLit(V.Err("#NAME?"))
            }
            TT.NAME -> NLit(V.Err("#NAME?"))
            else -> throw FormulaError("#VALUE!")
        }
    }

    companion object {
        fun parse(formula: String): Nd = Parser(Lexer.tokenize(formula)).parse()
    }
}

/* ===================== Reference rewriting ===================== */

object FormulaRewrite {
    private val PARTS = Regex("^(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})$")
    private val COLPARTS = Regex("^(\\$?)([A-Za-z]{1,3})$")

    private class Rep(val start: Int, val end: Int, val text: String)

    private fun apply(f: String, reps: List<Rep>): String {
        if (reps.isEmpty()) return f
        val sb = StringBuilder(f)
        for (r in reps.sortedByDescending { it.start }) sb.replace(r.start, r.end, r.text)
        return sb.toString()
    }

    private fun safeTokens(f: String): List<Tok>? = try { Lexer.tokenize(f) } catch (_: FormulaError) { null }

    /** Shifts relative references (copy/paste, fill, shared formulas). [f] has no leading '='. */
    fun shiftRelative(f: String, dr: Int, dc: Int): String {
        if (dr == 0 && dc == 0) return f
        val toks = safeTokens(f) ?: return f
        val reps = ArrayList<Rep>()
        for (t in toks) {
            when (t.t) {
                TT.REF -> {
                    val m = PARTS.matchEntire(t.s) ?: continue
                    var col = Addr.colIndex(m.groupValues[2])
                    var row = m.groupValues[4].toInt() - 1
                    if (m.groupValues[1].isEmpty()) col += dc
                    if (m.groupValues[3].isEmpty()) row += dr
                    val txt = if (col < 0 || row < 0 || col >= Sheet.MAX_COLS || row >= Sheet.MAX_ROWS) "#REF!"
                    else m.groupValues[1] + Addr.colName(col) + m.groupValues[3] + (row + 1)
                    reps.add(Rep(t.start, t.end, txt))
                }
                TT.COL -> {
                    val m = COLPARTS.matchEntire(t.s) ?: continue
                    if (m.groupValues[1].isNotEmpty()) continue
                    val col = Addr.colIndex(m.groupValues[2]) + dc
                    reps.add(Rep(t.start, t.end, if (col < 0) "#REF!" else Addr.colName(col)))
                }
                else -> {}
            }
        }
        return apply(f, reps)
    }

    /**
     * Adjusts references after rows/columns are inserted (count > 0) or deleted (count < 0)
     * at index [at] of sheet [target]. [formulaSheet] is the sheet that holds the formula.
     */
    fun insertDelete(f: String, formulaSheet: String, target: String, isRow: Boolean, at: Int, count: Int): String {
        val toks = safeTokens(f) ?: return f
        val reps = ArrayList<Rep>()
        fun appliesTo(t: Tok) = (t.sheet ?: formulaSheet).equals(target, ignoreCase = true)

        // returns new index or null when the position is deleted
        fun move(idx: Int): Int? = when {
            count > 0 -> if (idx >= at) idx + count else idx
            else -> {
                val n = -count
                when {
                    idx < at -> idx
                    idx < at + n -> null
                    else -> idx - n
                }
            }
        }

        fun rebuild(t: Tok, newRow: Int?, newCol: Int?): String {
            val m = PARTS.matchEntire(t.s) ?: return t.s
            val col = newCol ?: Addr.colIndex(m.groupValues[2])
            val row = newRow ?: (m.groupValues[4].toInt() - 1)
            return m.groupValues[1] + Addr.colName(col) + m.groupValues[3] + (row + 1)
        }

        fun idxOf(t: Tok): Int? {
            val m = PARTS.matchEntire(t.s) ?: return null
            return if (isRow) m.groupValues[4].toInt() - 1 else Addr.colIndex(m.groupValues[2])
        }

        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.t == TT.REF && appliesTo(t)) {
                val isArea = i + 2 < toks.size && toks[i + 1].t == TT.COLON && toks[i + 2].t == TT.REF
                if (isArea) {
                    val t2 = toks[i + 2]
                    val a = idxOf(t) ?: 0
                    val b = idxOf(t2) ?: 0
                    var na: Int? = move(a)
                    var nb: Int? = move(b)
                    if (count < 0) {
                        val n = -count
                        if (na == null) na = if (b >= at + n) at else null
                        if (nb == null) nb = if (a < at) at - 1 else null
                    }
                    if (na == null || nb == null || nb < na) {
                        reps.add(Rep(t.sheetStart, t2.end, "#REF!"))
                    } else {
                        reps.add(Rep(t.start, t.end, if (isRow) rebuild(t, na, null) else rebuild(t, null, na)))
                        reps.add(Rep(t2.start, t2.end, if (isRow) rebuild(t2, nb, null) else rebuild(t2, null, nb)))
                    }
                    i += 3
                    continue
                } else {
                    val a = idxOf(t)
                    if (a != null) {
                        val na = move(a)
                        if (na == null) reps.add(Rep(t.sheetStart, t.end, "#REF!"))
                        else reps.add(Rep(t.start, t.end, if (isRow) rebuild(t, na, null) else rebuild(t, null, na)))
                    }
                }
            } else if (t.t == TT.COL && !isRow && appliesTo(t)) {
                val m = COLPARTS.matchEntire(t.s)
                if (m != null) {
                    val na = move(Addr.colIndex(m.groupValues[2]))
                    reps.add(Rep(t.start, t.end, if (na == null) "#REF!" else m.groupValues[1] + Addr.colName(na)))
                }
            }
            i++
        }
        return apply(f, reps)
    }

    fun quoteSheet(name: String): String =
        if (Regex("^[A-Za-z_][A-Za-z0-9_.]*$").matches(name) && !Regex("^[A-Za-z]{1,3}[0-9]+$").matches(name)) name
        else "'" + name.replace("'", "''") + "'"

    fun renameSheet(f: String, old: String, new: String): String {
        val toks = safeTokens(f) ?: return f
        val reps = ArrayList<Rep>()
        for (t in toks) {
            if ((t.t == TT.REF || t.t == TT.COL || t.t == TT.NAME) && t.sheet != null && t.sheet.equals(old, ignoreCase = true) && t.sheetStart < t.start) {
                reps.add(Rep(t.sheetStart, t.start, quoteSheet(new) + "!"))
            }
        }
        return apply(f, reps)
    }
}
